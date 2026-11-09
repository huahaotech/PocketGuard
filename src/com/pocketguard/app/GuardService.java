package com.pocketguard.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 口袋守护核心服务：
 * 1. 监听距离感应器，被遮挡（贴脸/进兜）后自动熄屏；
 * 2. 记录“口袋里”状态，检测到屏幕被点亮时立刻再次熄灭，让误触没有机会发生；
 * 3. 在口袋里铺一层全屏透明悬浮窗吞掉所有触摸事件（没有设备管理员权限时的兜底）。
 */
public class GuardService extends Service implements SensorEventListener {

    public static final String ACTION_STOP = "com.pocketguard.app.action.STOP";

    private static final String TAG = "PocketGuard";
    private static final String CHANNEL_ID = "guard_channel";
    private static final int NOTIF_ID = 1001;

    /** 遮挡持续多久算“进兜” */
    private static final int ENTER_POCKET_MS = 500;
    /** 离开遮挡多久算“出兜” */
    private static final int EXIT_POCKET_MS = 400;

    public interface Listener {
        void onGuardState(boolean running, boolean near, boolean pocket, String lastAction);
    }

    static volatile boolean sRunning = false;
    static volatile boolean sNear = false;
    static volatile boolean sPocket = false;
    static volatile String sLastAction = "—";
    /** 最近一次实际使用的熄屏方式，用于界面提示 */
    static volatile String sLockMethod = "—";
    static volatile Listener sListener;

    private Handler handler;
    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private DevicePolicyManager dpm;
    private ComponentName admin;
    private PowerManager powerManager;
    private AudioManager audioManager;
    private WindowManager windowManager;

    private View touchGuardView;
    private boolean overlayAdded = false;
    private boolean decisionPending = false;
    private long lastScreenOffAt = 0;

    private final Runnable enterPocketTask = new Runnable() {
        @Override
        public void run() {
            if (sNear && !sPocket) {
                sPocket = true;
                onPocketEnter();
                publish();
            }
        }
    };

    private final Runnable exitPocketTask = new Runnable() {
        @Override
        public void run() {
            if (!sNear && sPocket) {
                sPocket = false;
                hideTouchGuard();
                publish();
            }
        }
    };

    private final Runnable wakeTimeoutTask = new Runnable() {
        @Override
        public void run() {
            if (decisionPending) {
                decisionPending = false;
                decideWake(false);
            }
        }
    };

    private final Runnable overlayTimeoutTask = new Runnable() {
        @Override
        public void run() {
            hideTouchGuard();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                // 亮屏后不要用旧读数做判断：等一次真实传感器回调，最多等 900ms
                decisionPending = true;
                refreshProximity();
                handler.removeCallbacks(wakeTimeoutTask);
                handler.postDelayed(wakeTimeoutTask, 900);
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                lastScreenOffAt = SystemClock.elapsedRealtime();
                decisionPending = false;
                handler.removeCallbacks(wakeTimeoutTask);
                hideTouchGuard();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        proximitySensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, PocketAdminReceiver.class);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        createNotificationChannel();
        goForeground();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }

        registerProximity();
        sRunning = true;
        sLastAction = proximitySensor == null ? "未检测到距离感应器" : "已就绪";
        publish();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
        }
        unregisterProximity();
        hideTouchGuard();
        sRunning = false;
        sNear = false;
        sPocket = false;
        publish();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 传感器

    private void registerProximity() {
        if (sensorManager == null || proximitySensor == null) {
            return;
        }
        sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void unregisterProximity() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    /** 亮屏后强制刷新一次读数（很多机型息屏期间不派发非唤醒传感器事件） */
    private void refreshProximity() {
        unregisterProximity();
        registerProximity();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_PROXIMITY) {
            return;
        }
        float max = event.sensor.getMaximumRange();
        float value = event.values.length > 0 ? event.values[0] : max;
        boolean near = max > 0f ? value < max : value < 1f;
        applyProximity(near);
        if (decisionPending) {
            decisionPending = false;
            handler.removeCallbacks(wakeTimeoutTask);
            decideWake(true);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void applyProximity(boolean near) {
        handler.removeCallbacks(enterPocketTask);
        handler.removeCallbacks(exitPocketTask);
        boolean changed = near != sNear;
        sNear = near;
        if (near) {
            handler.postDelayed(enterPocketTask, ENTER_POCKET_MS);
        } else {
            handler.postDelayed(exitPocketTask, EXIT_POCKET_MS);
        }
        if (changed) {
            publish();
        }
    }

    // ------------------------------------------------------------------ 核心动作

    private void onPocketEnter() {
        if (!Prefs.getBool(this, Prefs.KEY_POCKET_LOCK, false)) {
            sLastAction = "口袋里（未启用防护）";
            return;
        }
        // 正常使用模式：屏幕亮着且在被操作时，不因遮挡而熄屏，
        // 防护只作用于“已熄屏后防止误点亮”（见 decideWake）
        if (Prefs.getBool(this, Prefs.KEY_SKIP_ACTIVE_LOCK, true)) {
            sLastAction = "口袋里（正常使用中，不熄屏）";
            return;
        }
        if (isInCall()) {
            sLastAction = "通话中，忽略";
            return;
        }
        if (!powerManager.isInteractive()) {
            sLastAction = "口袋里（屏幕已熄灭）";
            return;
        }
        showTouchGuard();
        if (requestLock("口袋遮挡 → 已自动息屏")) {
            hideTouchGuardDelayed(1200);
        }
    }

    private void decideWake(boolean freshReading) {
        if (!Prefs.getBool(this, Prefs.KEY_POCKET_LOCK, false)) {
            return;
        }
        if (!Prefs.getBool(this, Prefs.KEY_RELOCK, true)) {
            return;
        }
        if (isInCall()) {
            return;
        }
        if (!powerManager.isInteractive()) {
            return;
        }
        boolean inPocket = freshReading ? sNear : (sNear || sPocket);
        if (!inPocket) {
            return;
        }
        showTouchGuard();
        requestLock(freshReading ? "口袋里被点亮 → 已立即熄灭" : "口袋里被点亮 → 已立即熄灭(推定)");
    }

    /**
     * 熄屏入口。
     * 优先用无障碍 GLOBAL_ACTION_LOCK_SCREEN：等同按电源键，写入的是“便捷锁”，
     * 下次仍可指纹/面容解锁。
     * 设备管理员 lockNow() 会置上强认证标记，下次必须输 PIN/图案/密码，
     * 所以只作为无障碍不可用时的兜底。
     */
    private boolean requestLock(final String reason) {
        final long requestAt = SystemClock.elapsedRealtime();
        if (lockViaAccessibility(reason)) {
            // 700ms 后确认是否真的熄屏，没有就退回设备管理员
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!powerManager.isInteractive()) {
                        return;
                    }
                    if (lastScreenOffAt > requestAt) {
                        return;
                    }
                    lockNowViaAdmin(reason + "（兜底）");
                }
            }, 700);
            return true;
        }
        return lockNowViaAdmin(reason);
    }

    private boolean lockViaAccessibility(String reason) {
        if (!ShadeGuardService.lockScreenViaAccessibility()) {
            return false;
        }
        sLockMethod = "无障碍 · 指纹可解锁";
        sLastAction = reason;
        publish();
        return true;
    }

    private boolean lockNowViaAdmin(String reason) {
        if (dpm == null || admin == null) {
            sLastAction = "无法熄屏：缺少设备管理员";
            publish();
            return false;
        }
        if (!dpm.isAdminActive(admin)) {
            sLastAction = "无法熄屏：请开启无障碍权限（推荐）或设备管理员";
            publish();
            return false;
        }
        try {
            dpm.lockNow();
            sLockMethod = "设备管理员 · 需输入密码";
            sLastAction = reason;
            publish();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "lockNow failed", t);
            sLastAction = "熄屏失败：" + t.getClass().getSimpleName();
            publish();
            return false;
        }
    }

    private boolean isInCall() {
        if (audioManager == null) {
            return false;
        }
        int mode = audioManager.getMode();
        return mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE;
    }

    // ------------------------------------------------------------------ 触控拦截层

    private void showTouchGuard() {
        if (!Settings.canDrawOverlays(this)) {
            return;
        }
        if (overlayAdded) {
            return;
        }
        try {
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setTitle("PocketGuard-Guard");

            View view = new View(this);
            view.setBackgroundColor(0x00000000);
            view.setClickable(true);
            view.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return true; // 吞掉所有触摸
                }
            });
            windowManager.addView(view, lp);
            touchGuardView = view;
            overlayAdded = true;
            handler.removeCallbacks(overlayTimeoutTask);
            handler.postDelayed(overlayTimeoutTask, 20000);
        } catch (Throwable t) {
            Log.w(TAG, "add overlay failed", t);
        }
    }

    private void hideTouchGuardDelayed(long delay) {
        handler.removeCallbacks(overlayTimeoutTask);
        handler.postDelayed(overlayTimeoutTask, delay);
    }

    private void hideTouchGuard() {
        handler.removeCallbacks(overlayTimeoutTask);
        if (!overlayAdded || touchGuardView == null) {
            overlayAdded = false;
            touchGuardView = null;
            return;
        }
        try {
            windowManager.removeView(touchGuardView);
        } catch (Throwable t) {
            Log.w(TAG, "remove overlay failed", t);
        }
        overlayAdded = false;
        touchGuardView = null;
    }

    // ------------------------------------------------------------------ 前台通知

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "口袋守护", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持距离感应监测");
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    private void goForeground() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_guard)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(pi)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setChannelId(CHANNEL_ID);
        }
        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void publish() {
        Listener listener = sListener;
        if (listener != null) {
            listener.onGuardState(sRunning, sNear, sPocket, sLastAction);
        }
    }
}

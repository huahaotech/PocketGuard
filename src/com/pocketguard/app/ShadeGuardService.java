package com.pocketguard.app;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;

/**
 * 锁屏锁栏守护：
 * 普通应用无法“盖住”状态栏，所以采用检测 + 收起的方式——
 * 锁屏状态下检测到通知栏面板被展开，立即执行返回键把它收回去。
 */
public class ShadeGuardService extends AccessibilityService {

    /** 无障碍服务当前实例，供 GuardService 调用熄屏能力 */
    private static volatile ShadeGuardService sInstance;

    public static boolean isConnected() {
        return sInstance != null;
    }

    /**
     * 用 GLOBAL_ACTION_LOCK_SCREEN 熄屏：效果等同按电源键，
     * 不会写入“强认证”标记，所以下次仍可指纹解锁。
     */
    public static boolean lockScreenViaAccessibility() {
        ShadeGuardService service = sInstance;
        if (service == null || Build.VERSION.SDK_INT < 28) {
            return false;
        }
        try {
            return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        } catch (Throwable t) {
            return false;
        }
    }

    private static final long TICK_MS = 900;
    private static final long COOLDOWN_MS = 700;
    private static final long PAUSE_MS = 8000;
    private static final int MAX_ATTEMPTS = 4;

    private Handler handler;
    private KeyguardManager keyguardManager;
    private PowerManager powerManager;

    private long lastCollapseAt = 0;
    private long pauseUntil = 0;
    private int attempts = 0;

    private final Runnable tickTask = new Runnable() {
        @Override
        public void run() {
            collapseIfNeeded();
            if (handler != null) {
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private final Runnable collapseTask = new Runnable() {
        @Override
        public void run() {
            collapseIfNeeded();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        handler = new Handler(Looper.getMainLooper());
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        handler.removeCallbacks(tickTask);
        handler.postDelayed(tickTask, TICK_MS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || handler == null) {
            return;
        }
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !"com.android.systemui".contentEquals(pkg)) {
            return;
        }
        handler.removeCallbacks(collapseTask);
        handler.postDelayed(collapseTask, 150);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        handler = null;
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }

    private void collapseIfNeeded() {
        if (!Prefs.isShadeBlockEnabled(this)) {
            return;
        }
        if (keyguardManager == null || !keyguardManager.isKeyguardLocked()) {
            attempts = 0;
            return;
        }
        if (powerManager != null && !powerManager.isInteractive()) {
            return;
        }
        if (Prefs.getBool(this, Prefs.KEY_SHADE_POCKET_ONLY, true) && !GuardService.sNear) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now < pauseUntil) {
            return;
        }
        if (!isShadeExpanded()) {
            attempts = 0;
            return;
        }
        if (now - lastCollapseAt < COOLDOWN_MS) {
            return;
        }
        lastCollapseAt = now;
        performGlobalAction(GLOBAL_ACTION_BACK);
        attempts++;
        if (attempts >= MAX_ATTEMPTS) {
            attempts = 0;
            pauseUntil = now + PAUSE_MS;
        }
    }

    /** 判断通知栏面板是否被完整展开（宽高接近全屏） */
    private boolean isShadeExpanded() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (Throwable t) {
            return false;
        }
        if (windows == null) {
            return false;
        }
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        for (int i = 0; i < windows.size(); i++) {
            AccessibilityWindowInfo window = windows.get(i);
            if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) {
                continue;
            }
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            if (bounds.height() < screenHeight * 0.5f) {
                continue;
            }
            if (Build.VERSION.SDK_INT >= 24) {
                CharSequence title = window.getTitle();
                if (title != null
                        && title.toString().toLowerCase(Locale.US).contains("notificationshade")) {
                    return true;
                }
            }
            if (hasShadePanel(window)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasShadePanel(AccessibilityWindowInfo window) {
        try {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) {
                return false;
            }
            if (containsId(root, "com.android.systemui:id/notification_panel")) {
                return true;
            }
            if (containsId(root, "com.android.systemui:id/qs_frame")) {
                return true;
            }
            if (containsId(root, "com.android.systemui:id/quick_settings_panel")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean containsId(AccessibilityNodeInfo root, String viewId) {
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
            return nodes != null && !nodes.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}

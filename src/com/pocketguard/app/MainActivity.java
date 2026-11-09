package com.pocketguard.app;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements GuardService.Listener {

    private static final int REQ_NOTIF = 21;

    private static final int P_ADMIN = 0;
    private static final int P_OVERLAY = 1;
    private static final int P_ACCESS = 2;
    private static final int P_NOTIF = 3;
    private static final int P_BATTERY = 4;

    private TextView tvService;
    private TextView tvSensor;
    private TextView tvPocket;
    private TextView tvLast;
    private TextView tvLocker;
    private View dot;

    private Switch swPocket;
    private Switch swRelock;
    private Switch swSkip;
    private Switch swShade;
    private Switch swShadePocket;

    private final TextView[] permStatus = new TextView[5];
    private final Button[] permButton = new Button[5];

    private DevicePolicyManager dpm;
    private ComponentName admin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, PocketAdminReceiver.class);

        dot = findViewById(R.id.dot);
        tvService = (TextView) findViewById(R.id.tv_service);
        tvSensor = (TextView) findViewById(R.id.tv_sensor);
        tvPocket = (TextView) findViewById(R.id.tv_pocket);
        tvLast = (TextView) findViewById(R.id.tv_last);
        tvLocker = (TextView) findViewById(R.id.tv_locker);

        buildSwitchRows();
        buildPermissionRows();

        if (Prefs.isGuardEnabled(this)) {
            startGuard();
        }
        maybeRequestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        GuardService.sListener = this;
        loadSwitchStates();
        refreshPermissions();
        updateStatus(GuardService.sRunning, GuardService.sNear, GuardService.sPocket, GuardService.sLastAction);
    }

    @Override
    protected void onPause() {
        GuardService.sListener = null;
        super.onPause();
    }

    // ------------------------------------------------------------------ 开关

    private void buildSwitchRows() {
        LinearLayout box = (LinearLayout) findViewById(R.id.box_switches);
        LayoutInflater inflater = LayoutInflater.from(this);

        swPocket = addSwitchRow(box, inflater,
                getString(R.string.row_pocket_lock), getString(R.string.row_pocket_lock_desc));
        swRelock = addSwitchRow(box, inflater,
                getString(R.string.row_relock), getString(R.string.row_relock_desc));
        swSkip = addSwitchRow(box, inflater,
                getString(R.string.row_skip_active), getString(R.string.row_skip_active_desc));
        swShade = addSwitchRow(box, inflater,
                getString(R.string.row_shade), getString(R.string.row_shade_desc));
        swShadePocket = addSwitchRow(box, inflater,
                getString(R.string.row_shade_pocket), getString(R.string.row_shade_pocket_desc));
    }

    private Switch addSwitchRow(LinearLayout box, LayoutInflater inflater, String title, String desc) {
        if (box.getChildCount() > 0) {
            box.addView(divider());
        }
        View row = inflater.inflate(R.layout.row_switch, box, false);
        ((TextView) row.findViewById(R.id.tv_row_title)).setText(title);
        ((TextView) row.findViewById(R.id.tv_row_desc)).setText(desc);
        box.addView(row);
        return (Switch) row.findViewById(R.id.sw_row);
    }

    private void loadSwitchStates() {
        setSwitchSilently(swPocket, Prefs.getBool(this, Prefs.KEY_POCKET_LOCK, false));
        setSwitchSilently(swRelock, Prefs.getBool(this, Prefs.KEY_RELOCK, true));
        setSwitchSilently(swSkip, Prefs.getBool(this, Prefs.KEY_SKIP_ACTIVE_LOCK, true));
        setSwitchSilently(swShade, Prefs.getBool(this, Prefs.KEY_SHADE_BLOCK, false));
        setSwitchSilently(swShadePocket, Prefs.getBool(this, Prefs.KEY_SHADE_POCKET_ONLY, true));
        swRelock.setAlpha(swPocket.isChecked() ? 1f : 0.45f);
        swSkip.setAlpha(swPocket.isChecked() ? 1f : 0.45f);
        swShadePocket.setAlpha(swShade.isChecked() ? 1f : 0.45f);
    }

    private void setSwitchSilently(Switch sw, boolean checked) {
        if (sw == null) {
            return;
        }
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        if (sw == swPocket) {
            sw.setOnCheckedChangeListener(pocketListener);
        } else if (sw == swRelock) {
            sw.setOnCheckedChangeListener(relockListener);
        } else if (sw == swSkip) {
            sw.setOnCheckedChangeListener(skipListener);
        } else if (sw == swShade) {
            sw.setOnCheckedChangeListener(shadeListener);
        } else if (sw == swShadePocket) {
            sw.setOnCheckedChangeListener(shadePocketListener);
        }
    }

    // 保存监听器引用，避免 setChecked 触发回调
    private final CompoundButton.OnCheckedChangeListener pocketListener =
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Prefs.setBool(MainActivity.this, Prefs.KEY_POCKET_LOCK, isChecked);
                    if (isChecked) {
                        startGuard();
                        if (!isAccessibilityEnabled()) {
                            toast("请开启「口袋守护」无障碍服务，熄屏后才能用指纹解锁");
                            try {
                                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            } catch (Throwable ignored) {
                            }
                        }
                    } else {
                        stopGuard();
                    }
                    swRelock.setAlpha(isChecked ? 1f : 0.45f);
                    refreshPermissions();
                }
            };

    private final CompoundButton.OnCheckedChangeListener relockListener =
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Prefs.setBool(MainActivity.this, Prefs.KEY_RELOCK, isChecked);
                }
            };

    private final CompoundButton.OnCheckedChangeListener skipListener =
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Prefs.setBool(MainActivity.this, Prefs.KEY_SKIP_ACTIVE_LOCK, isChecked);
                }
            };

    private final CompoundButton.OnCheckedChangeListener shadeListener =
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Prefs.setBool(MainActivity.this, Prefs.KEY_SHADE_BLOCK, isChecked);
                    if (isChecked && !isAccessibilityEnabled()) {
                        toast("请开启「口袋守护」无障碍服务");
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Throwable ignored) {
                        }
                    }
                    swShadePocket.setAlpha(isChecked ? 1f : 0.45f);
                }
            };

    private final CompoundButton.OnCheckedChangeListener shadePocketListener =
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Prefs.setBool(MainActivity.this, Prefs.KEY_SHADE_POCKET_ONLY, isChecked);
                }
            };

    // ------------------------------------------------------------------ 权限

    private void buildPermissionRows() {
        LinearLayout box = (LinearLayout) findViewById(R.id.box_perms);
        LayoutInflater inflater = LayoutInflater.from(this);

        permStatus[P_ADMIN] = addPermRow(box, inflater,
                getString(R.string.perm_admin), getString(R.string.perm_admin_desc), P_ADMIN);
        permStatus[P_OVERLAY] = addPermRow(box, inflater,
                getString(R.string.perm_overlay), getString(R.string.perm_overlay_desc), P_OVERLAY);
        permStatus[P_ACCESS] = addPermRow(box, inflater,
                getString(R.string.perm_access), getString(R.string.perm_access_desc), P_ACCESS);
        permStatus[P_NOTIF] = addPermRow(box, inflater,
                getString(R.string.perm_notif), getString(R.string.perm_notif_desc), P_NOTIF);
        permStatus[P_BATTERY] = addPermRow(box, inflater,
                getString(R.string.perm_battery), getString(R.string.perm_battery_desc), P_BATTERY);
    }

    private TextView addPermRow(LinearLayout box, LayoutInflater inflater, String title, String desc, final int which) {
        if (box.getChildCount() > 0) {
            box.addView(divider());
        }
        View row = inflater.inflate(R.layout.row_perm, box, false);
        ((TextView) row.findViewById(R.id.tv_perm_title)).setText(title);
        ((TextView) row.findViewById(R.id.tv_perm_desc)).setText(desc);
        Button button = (Button) row.findViewById(R.id.btn_perm);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handlePermClick(which);
            }
        });
        permButton[which] = button;
        box.addView(row);
        return (TextView) row.findViewById(R.id.tv_perm_title);
    }

    private void handlePermClick(int which) {
        try {
            if (which == P_ADMIN) {
                openAdminSettings();
            } else if (which == P_OVERLAY) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else if (which == P_ACCESS) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } else if (which == P_NOTIF) {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                } else {
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(i);
                }
            } else if (which == P_BATTERY) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Throwable t) {
            toast("无法打开设置页：" + t.getClass().getSimpleName());
        }
    }

    private void refreshPermissions() {
        setPermState(P_ADMIN, isAdminActive());
        setPermState(P_OVERLAY, Settings.canDrawOverlays(this));
        setPermState(P_ACCESS, isAccessibilityEnabled());
        setPermState(P_NOTIF, isNotificationGranted());
        setPermState(P_BATTERY, isIgnoringBattery());
    }

    private void setPermState(int which, boolean granted) {
        TextView title = permStatus[which];
        Button button = permButton[which];
        if (title == null || button == null) {
            return;
        }
        if (granted) {
            button.setText(R.string.perm_granted);
            button.setEnabled(false);
            button.setTextColor(getColor(R.color.text_faint));
        } else {
            button.setText(R.string.perm_missing);
            button.setEnabled(true);
            button.setTextColor(getColor(R.color.accent));
        }
        if (which == P_BATTERY) {
            button.setEnabled(true);
            button.setText(granted ? R.string.perm_granted : R.string.perm_missing);
        }
    }

    private boolean isAdminActive() {
        try {
            return dpm != null && dpm.isAdminActive(admin);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isAccessibilityEnabled() {
        String expected = new ComponentName(this, ShadeGuardService.class).flattenToString();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        String[] parts = enabled.split(":");
        for (int i = 0; i < parts.length; i++) {
            ComponentName cn = ComponentName.unflattenFromString(parts[i]);
            if (cn != null && expected.equalsIgnoreCase(cn.flattenToString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isNotificationGranted() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void openAdminSettings() {
        try {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "「口袋守护」需要此权限才能在手机进兜时立即熄屏。");
            startActivity(intent);
        } catch (Throwable t) {
            toast("无法打开设备管理员设置");
        }
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !isNotificationGranted()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPermissions();
    }

    // ------------------------------------------------------------------ 服务与状态

    private void startGuard() {
        Intent intent = new Intent(this, GuardService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable t) {
            toast("启动失败：" + t.getClass().getSimpleName());
        }
    }

    private void stopGuard() {
        try {
            Intent intent = new Intent(this, GuardService.class);
            intent.setAction(GuardService.ACTION_STOP);
            startService(intent);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onGuardState(final boolean running, final boolean near, final boolean pocket, final String lastAction) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateStatus(running, near, pocket, lastAction);
            }
        });
    }

    private void updateStatus(boolean running, boolean near, boolean pocket, String lastAction) {
        if (running) {
            tvService.setText("运行中");
            tvService.setTextColor(getColor(R.color.accent));
            dot.setBackgroundResource(R.drawable.dot_on);
        } else {
            tvService.setText("未开启");
            tvService.setTextColor(getColor(R.color.text_dim));
            dot.setBackgroundResource(R.drawable.dot_off);
        }
        if (!running) {
            tvSensor.setText("—");
            tvPocket.setText("—");
        } else {
            tvSensor.setText(near ? "被遮挡（近）" : "无遮挡（远）");
            tvSensor.setTextColor(near ? getColor(R.color.warn) : getColor(R.color.text));
            tvPocket.setText(pocket ? "在口袋里" : "正常");
            tvPocket.setTextColor(pocket ? getColor(R.color.warn) : getColor(R.color.text));
        }
        tvLast.setText(lastAction == null ? "—" : lastAction);
        tvLast.setTextColor(getColor(R.color.text_dim));

        if (isAccessibilityEnabled()) {
            tvLocker.setText("无障碍 · 指纹可解锁");
            tvLocker.setTextColor(getColor(R.color.accent));
        } else if (isAdminActive()) {
            tvLocker.setText("设备管理员 · 需输入密码");
            tvLocker.setTextColor(getColor(R.color.warn));
        } else {
            tvLocker.setText("未就绪");
            tvLocker.setTextColor(getColor(R.color.danger));
        }
    }

    // ------------------------------------------------------------------ 工具

    private View divider() {
        View view = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (int) (getResources().getDisplayMetrics().density * 0.6f)));
        lp.leftMargin = dp(16);
        view.setLayoutParams(lp);
        view.setBackgroundColor(0xFF1B2430);
        return view;
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}

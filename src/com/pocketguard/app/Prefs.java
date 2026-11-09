package com.pocketguard.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 极简偏好存储 */
public final class Prefs {

    public static final String KEY_POCKET_LOCK = "pocket_lock";
    public static final String KEY_RELOCK = "relock_in_pocket";
    /** 正常使用（屏幕亮着在操作）时不因遮挡而熄屏 */
    public static final String KEY_SKIP_ACTIVE_LOCK = "skip_active_lock";
    public static final String KEY_SHADE_BLOCK = "shade_block";
    public static final String KEY_SHADE_POCKET_ONLY = "shade_pocket_only";

    private static final String NAME = "pocket_guard_prefs";

    private Prefs() {
    }

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean getBool(Context ctx, String key, boolean def) {
        return get(ctx).getBoolean(key, def);
    }

    public static void setBool(Context ctx, String key, boolean value) {
        get(ctx).edit().putBoolean(key, value).apply();
    }

    public static boolean isGuardEnabled(Context ctx) {
        return getBool(ctx, KEY_POCKET_LOCK, false);
    }

    public static boolean isShadeBlockEnabled(Context ctx) {
        return getBool(ctx, KEY_SHADE_BLOCK, false);
    }
}

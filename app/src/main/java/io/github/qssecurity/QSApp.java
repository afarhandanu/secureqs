package io.github.qssecurity;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Module application side of the official libxposed RemotePreferences bridge. */
public final class QSApp extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "QSSecurityApp";
    private static volatile XposedService xposedService;
    private static volatile SharedPreferences remotePreferences;

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService service) {
        xposedService = service;
        try {
            SharedPreferences remote = service.getRemotePreferences(ModuleConfig.REMOTE_PREF_GROUP);
            remotePreferences = remote;

            // Preserve settings from previous versions and push them into RemotePreferences.
            SharedPreferences local = getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE);
            int localMode = normalizeMode(local.getInt(
                    ModuleConfig.PREF_MODE, ModuleConfig.MODE_REQUIRE_UNLOCK));
            Set<String> whitelist = new HashSet<>(local.getStringSet(
                    ModuleConfig.PREF_WHITELIST, Collections.emptySet()));
            boolean protectPower = local.getBoolean(ModuleConfig.PREF_PROTECT_POWER, true);

            remote.edit()
                    .putInt(ModuleConfig.PREF_MODE, localMode)
                    .putStringSet(ModuleConfig.PREF_WHITELIST, whitelist)
                    .putBoolean(ModuleConfig.PREF_PROTECT_POWER, protectPower)
                    .apply();
            Log.i(TAG, "LSPosed service connected; settings synced. mode=" + localMode
                    + " whitelist=" + whitelist + " protectPower=" + protectPower);
        } catch (Throwable t) {
            Log.e(TAG, "Unable to initialize libxposed RemotePreferences", t);
        }
    }

    @Override
    public void onServiceDied(XposedService service) {
        if (xposedService == service) {
            xposedService = null;
            remotePreferences = null;
        }
    }

    public static int getLocalMode(Context context) {
        return normalizeMode(context.getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE)
                .getInt(ModuleConfig.PREF_MODE, ModuleConfig.MODE_REQUIRE_UNLOCK));
    }

    public static Set<String> getLocalWhitelist(Context context) {
        return new HashSet<>(context.getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE)
                .getStringSet(ModuleConfig.PREF_WHITELIST, Collections.emptySet()));
    }

    /** Save locally for UI/migration and to LSPosed RemotePreferences for SystemUI. */
    public static boolean saveMode(Context context, int mode) {
        int normalized = normalizeMode(mode);
        context.getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE)
                .edit()
                .putInt(ModuleConfig.PREF_MODE, normalized)
                .apply();

        SharedPreferences remote = remotePreferences;
        if (remote == null) return false;
        try {
            remote.edit().putInt(ModuleConfig.PREF_MODE, normalized).apply();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Unable to write remote mode=" + normalized, t);
            return false;
        }
    }

    public static boolean saveWhitelist(Context context, Set<String> whitelist) {
        Set<String> copy = new HashSet<>(whitelist);
        context.getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE)
                .edit()
                .putStringSet(ModuleConfig.PREF_WHITELIST, copy)
                .apply();

        SharedPreferences remote = remotePreferences;
        if (remote == null) return false;
        try {
            remote.edit().putStringSet(ModuleConfig.PREF_WHITELIST, copy).apply();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Unable to write whitelist=" + copy, t);
            return false;
        }
    }

    public static boolean getLocalPowerProtection(Context context) {
        return context.getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE)
                .getBoolean(ModuleConfig.PREF_PROTECT_POWER, true);
    }

    public static boolean savePowerProtection(Context context, boolean enabled) {
        context.getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(ModuleConfig.PREF_PROTECT_POWER, enabled)
                .apply();

        SharedPreferences remote = remotePreferences;
        if (remote == null) return false;
        try {
            remote.edit().putBoolean(ModuleConfig.PREF_PROTECT_POWER, enabled).apply();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Unable to write power protection=" + enabled, t);
            return false;
        }
    }

    public static boolean isXposedServiceConnected() {
        return remotePreferences != null;
    }

    private static int normalizeMode(int mode) {
        return mode == ModuleConfig.MODE_BLOCK_SHADE
                ? ModuleConfig.MODE_BLOCK_SHADE
                : ModuleConfig.MODE_REQUIRE_UNLOCK;
    }
}

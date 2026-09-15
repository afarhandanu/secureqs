package io.github.qssecurity;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Module application side of the official libxposed RemotePreferences bridge.
 *
 * The old build used our own exported ContentProvider. On this device that bridge could fail and
 * MainHook deliberately fell back to MODE_REQUIRE_UNLOCK, which made "block shade" behave exactly
 * like the first mode. RemotePreferences avoids cross-app provider/SELinux/user-routing issues and
 * is the configuration channel designed for modern libxposed modules.
 */
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

            // Preserve the user's setting from v1.0-v1.3. Those versions saved it locally.
            SharedPreferences local = getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE);
            int localMode = normalizeMode(local.getInt(
                    ModuleConfig.PREF_MODE, ModuleConfig.MODE_REQUIRE_UNLOCK));
            remote.edit().putInt(ModuleConfig.PREF_MODE, localMode).apply();
            Log.i(TAG, "LSPosed service connected; remote mode synced=" + localMode);
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

    public static boolean isXposedServiceConnected() {
        return remotePreferences != null;
    }

    private static int normalizeMode(int mode) {
        return mode == ModuleConfig.MODE_BLOCK_SHADE
                ? ModuleConfig.MODE_BLOCK_SHADE
                : ModuleConfig.MODE_REQUIRE_UNLOCK;
    }
}

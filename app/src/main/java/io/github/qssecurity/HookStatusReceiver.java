package io.github.qssecurity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Stores a lightweight heartbeat from the hooked SystemUI process for the settings UI. */
public final class HookStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ModuleConfig.ACTION_HOOK_READY.equals(intent.getAction())) return;

        int bootCount = intent.getIntExtra("boot_count", -1);
        String version = intent.getStringExtra("version");
        if (bootCount < 0) {
            try {
                bootCount = Settings.Global.getInt(
                        context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            } catch (Throwable ignored) {
            }
        }

        context.getSharedPreferences(ModuleConfig.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(ModuleConfig.PREF_HOOK_LAST_SEEN, System.currentTimeMillis())
                .putInt(ModuleConfig.PREF_HOOK_BOOT_COUNT, bootCount)
                .putString(ModuleConfig.PREF_HOOK_VERSION,
                        version == null ? ModuleConfig.VERSION_NAME : version)
                .apply();
    }
}

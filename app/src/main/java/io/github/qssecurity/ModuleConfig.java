package io.github.qssecurity;

public final class ModuleConfig {
    private ModuleConfig() {}

    public static final String APP_PACKAGE = "io.github.qssecurity";
    public static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    public static final String PREFS = "qs_security";
    public static final String PREF_MODE = "mode";
    public static final String PREF_WHITELIST = "tile_whitelist";
    public static final String PREF_HOOK_LAST_SEEN = "hook_last_seen";
    public static final String PREF_HOOK_BOOT_COUNT = "hook_boot_count";
    public static final String PREF_HOOK_VERSION = "hook_version";

    /** QS can open, but tile actions require dismissing/authenticating keyguard. */
    public static final int MODE_REQUIRE_UNLOCK = 1;

    /** Notification shade / QS cannot expand while keyguard is locked. */
    public static final int MODE_BLOCK_SHADE = 2;

    /** Preferences stored by LSPosed/libxposed, readable from hooked SystemUI. */
    public static final String REMOTE_PREF_GROUP = "qs_security";

    /** Explicit heartbeat broadcast sent only by the hooked SystemUI process. */
    public static final String ACTION_HOOK_READY = APP_PACKAGE + ".action.HOOK_READY";

    public static final String VERSION_NAME = "1.5.0";
}

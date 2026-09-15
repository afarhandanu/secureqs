package io.github.qssecurity;

public final class ModuleConfig {
    private ModuleConfig() {}

    public static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    public static final String PREFS = "qs_security";
    public static final String PREF_MODE = "mode";

    /** QS can open, but tile actions require dismissing/authenticating keyguard. */
    public static final int MODE_REQUIRE_UNLOCK = 1;

    /** Notification shade / QS cannot expand while keyguard is locked. */
    public static final int MODE_BLOCK_SHADE = 2;

    /** Preferences stored by LSPosed/libxposed, readable from hooked SystemUI. */
    public static final String REMOTE_PREF_GROUP = "qs_security";
}

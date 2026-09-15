package io.github.qssecurity;

import android.net.Uri;

public final class ModuleConfig {
    private ModuleConfig() {}

    public static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    public static final String PREFS = "qs_security";
    public static final String PREF_MODE = "mode";

    /** QS can open, but tile actions require dismissing/authenticating keyguard. */
    public static final int MODE_REQUIRE_UNLOCK = 1;

    /** Notification shade / QS cannot expand while keyguard is locked. */
    public static final int MODE_BLOCK_SHADE = 2;

    public static final String PROVIDER_AUTHORITY = "io.github.qssecurity.settings";
    public static final Uri PROVIDER_URI = Uri.parse("content://" + PROVIDER_AUTHORITY);
    public static final String PROVIDER_METHOD_GET_MODE = "getMode";
    public static final String PROVIDER_RESULT_MODE = "mode";
}

package io.github.qssecurity;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float sp) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        return tv;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int savedMode = getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE)
                .getInt(ModuleConfig.PREF_MODE, ModuleConfig.MODE_REQUIRE_UNLOCK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("QS Security", 28);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView subtitle = text(
                "LineageOS 23 / Android 16 • libxposed API 102 • v1.3\n" +
                "Proteksi hanya aktif ketika keyguard/lock screen sedang terkunci.", 15);
        subtitle.setPadding(0, 0, 0, dp(24));
        root.addView(subtitle);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);

        RadioButton requireUnlock = new RadioButton(this);
        requireUnlock.setId(1001);
        requireUnlock.setText(
                "Boleh tarik Quick Settings, tetapi tile dikunci\n" +
                "Saat tile ditekan, SystemUI meminta unlock/security terlebih dahulu. " +
                "Setelah berhasil, aksi tile diteruskan.");
        requireUnlock.setPadding(0, dp(8), 0, dp(12));

        RadioButton blockShade = new RadioButton(this);
        blockShade.setId(1002);
        blockShade.setText(
                "Blok Quick Settings / notification shade saat terkunci\n" +
                "Panel dari atas tidak dapat dibuka sampai lock screen berhasil dibuka.");
        blockShade.setPadding(0, dp(8), 0, dp(12));

        group.addView(requireUnlock);
        group.addView(blockShade);
        root.addView(group);

        TextView note = text(
                "Setup: install APK hasil GitHub Actions → aktifkan modul di LSPosed → " +
                "scope hanya System UI (com.android.systemui) → restart SystemUI atau reboot.\n\n" +
                "Perubahan mode dibaca langsung oleh SystemUI. Setelah update APK modul, restart SystemUI/reboot sekali.", 14);
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note);

        if (savedMode == ModuleConfig.MODE_BLOCK_SHADE) {
            blockShade.setChecked(true);
        } else {
            requireUnlock.setChecked(true);
        }

        group.setOnCheckedChangeListener((g, checkedId) -> {
            int mode = checkedId == 1002
                    ? ModuleConfig.MODE_BLOCK_SHADE
                    : ModuleConfig.MODE_REQUIRE_UNLOCK;

            getSharedPreferences(ModuleConfig.PREFS, MODE_PRIVATE)
                    .edit()
                    .putInt(ModuleConfig.PREF_MODE, mode)
                    .apply();

            Toast.makeText(this,
                    mode == ModuleConfig.MODE_BLOCK_SHADE
                            ? "Mode: blok shade saat terkunci"
                            : "Mode: minta unlock saat tile ditekan",
                    Toast.LENGTH_SHORT).show();
        });

        setContentView(root);
    }
}

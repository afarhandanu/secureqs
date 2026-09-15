package io.github.qssecurity;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity {

    private final Map<CheckBox, String[]> whitelistBoxes = new LinkedHashMap<>();
    private LinearLayout whitelistContent;
    private TextView frameworkStatus;
    private TextView hookStatus;
    private TextView modeStatus;

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float sp) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(Color.rgb(30, 30, 30));
        return tv;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(247, 247, 249));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(225, 225, 230));
        return bg;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(cardBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView tv = text(value, 17);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(10));
        return tv;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(28));
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("QS Security", 30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text(
                "LineageOS 23 • Android 16 • libxposed API 101 • v" + ModuleConfig.VERSION_NAME,
                14);
        subtitle.setTextColor(Color.rgb(95, 95, 105));
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        // Status card.
        LinearLayout statusCard = card();
        statusCard.addView(sectionTitle("Status"));
        frameworkStatus = text("", 14);
        hookStatus = text("", 14);
        modeStatus = text("", 14);
        frameworkStatus.setPadding(0, dp(2), 0, dp(5));
        hookStatus.setPadding(0, dp(2), 0, dp(5));
        modeStatus.setPadding(0, dp(2), 0, dp(8));
        statusCard.addView(frameworkStatus);
        statusCard.addView(hookStatus);
        statusCard.addView(modeStatus);

        Button refresh = new Button(this);
        refresh.setText("Refresh status");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> updateStatus());
        statusCard.addView(refresh);
        root.addView(statusCard);

        // Mode card.
        LinearLayout modeCard = card();
        modeCard.addView(sectionTitle("Protection mode"));

        TextView modeHint = text("Proteksi hanya berlaku saat lock screen benar-benar terkunci.", 13);
        modeHint.setTextColor(Color.rgb(100, 100, 110));
        modeHint.setPadding(0, 0, 0, dp(8));
        modeCard.addView(modeHint);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);

        RadioButton requireUnlock = new RadioButton(this);
        requireUnlock.setId(1001);
        requireUnlock.setText("Require unlock\nQS boleh dibuka, tetapi tile meminta fingerprint/PIN sebelum dijalankan.");
        requireUnlock.setTextSize(15);
        requireUnlock.setPadding(0, dp(5), 0, dp(8));

        RadioButton blockShade = new RadioButton(this);
        blockShade.setId(1002);
        blockShade.setText("Block shade\nQuick Settings dan notification shade tidak bisa ditarik saat terkunci.");
        blockShade.setTextSize(15);
        blockShade.setPadding(0, dp(5), 0, dp(6));

        group.addView(requireUnlock);
        group.addView(blockShade);
        modeCard.addView(group);
        root.addView(modeCard);

        // Whitelist card. This only changes behavior when REQUIRE_UNLOCK is selected.
        LinearLayout whitelistCard = card();
        whitelistCard.addView(sectionTitle("Tile whitelist"));
        TextView whitelistHint = text(
                "Opsional. Tile yang dipilih boleh dipakai tanpa unlock pada mode Require unlock. " +
                "Default kosong, jadi perilaku inti v1.4.2 tetap sama.", 13);
        whitelistHint.setTextColor(Color.rgb(100, 100, 110));
        whitelistHint.setPadding(0, 0, 0, dp(8));
        whitelistCard.addView(whitelistHint);

        whitelistContent = new LinearLayout(this);
        whitelistContent.setOrientation(LinearLayout.VERTICAL);
        addWhitelistBox("Flashlight", new String[]{"flashlight"});
        addWhitelistBox("Internet / Wi-Fi", new String[]{"internet", "wifi"});
        addWhitelistBox("Bluetooth", new String[]{"bt", "bluetooth"});
        addWhitelistBox("Hotspot", new String[]{"hotspot"});
        addWhitelistBox("Auto-rotate", new String[]{"rotation"});
        addWhitelistBox("Do Not Disturb", new String[]{"dnd"});
        addWhitelistBox("Location", new String[]{"location"});
        addWhitelistBox("Battery Saver", new String[]{"battery"});
        addWhitelistBox("Airplane mode", new String[]{"airplane"});
        addWhitelistBox("Screen Record", new String[]{"screenrecord"});
        whitelistCard.addView(whitelistContent);
        root.addView(whitelistCard);

        LinearLayout infoCard = card();
        infoCard.addView(sectionTitle("Notes"));
        TextView note = text(
                "• Scope LSPosed: System UI (com.android.systemui) saja.\n" +
                "• Setelah update APK, restart SystemUI atau reboot sekali.\n" +
                "• Whitelist bersifat fail-secure: jika tile spec ROM tidak dikenali, tile tetap diproteksi.\n" +
                "• Core hook mode Block shade dan replay unlock dipertahankan dari build stabil v1.4.2.",
                13);
        note.setTextColor(Color.rgb(75, 75, 85));
        infoCard.addView(note);
        root.addView(infoCard);

        int savedMode = QSApp.getLocalMode(this);
        Set<String> savedWhitelist = QSApp.getLocalWhitelist(this);
        applyWhitelistSelection(savedWhitelist);

        if (savedMode == ModuleConfig.MODE_BLOCK_SHADE) {
            blockShade.setChecked(true);
        } else {
            requireUnlock.setChecked(true);
        }
        setWhitelistEnabled(savedMode == ModuleConfig.MODE_REQUIRE_UNLOCK);

        group.setOnCheckedChangeListener((g, checkedId) -> {
            int mode = checkedId == 1002
                    ? ModuleConfig.MODE_BLOCK_SHADE
                    : ModuleConfig.MODE_REQUIRE_UNLOCK;

            boolean pushed = QSApp.saveMode(this, mode);
            setWhitelistEnabled(mode == ModuleConfig.MODE_REQUIRE_UNLOCK);
            updateStatus();

            String label = mode == ModuleConfig.MODE_BLOCK_SHADE
                    ? "Block shade"
                    : "Require unlock";
            Toast.makeText(this,
                    pushed ? label + " tersinkron ke LSPosed" : label + " disimpan; LSPosed service belum terhubung",
                    Toast.LENGTH_SHORT).show();
        });

        for (CheckBox box : whitelistBoxes.keySet()) {
            box.setOnCheckedChangeListener((buttonView, isChecked) -> saveWhitelistFromUi());
        }

        setContentView(scroll);
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (frameworkStatus != null) updateStatus();
    }

    private void addWhitelistBox(String label, String[] specs) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(14);
        box.setPadding(0, dp(2), 0, dp(2));
        whitelistBoxes.put(box, specs);
        whitelistContent.addView(box);
    }

    private void applyWhitelistSelection(Set<String> saved) {
        for (Map.Entry<CheckBox, String[]> entry : whitelistBoxes.entrySet()) {
            boolean checked = false;
            for (String spec : entry.getValue()) {
                if (saved.contains(spec)) {
                    checked = true;
                    break;
                }
            }
            entry.getKey().setChecked(checked);
        }
    }

    private void saveWhitelistFromUi() {
        Set<String> specs = new HashSet<>();
        for (Map.Entry<CheckBox, String[]> entry : whitelistBoxes.entrySet()) {
            if (!entry.getKey().isChecked()) continue;
            for (String spec : entry.getValue()) specs.add(spec);
        }
        QSApp.saveWhitelist(this, specs);
    }

    private void setWhitelistEnabled(boolean enabled) {
        if (whitelistContent == null) return;
        whitelistContent.setEnabled(enabled);
        whitelistContent.setAlpha(enabled ? 1.0f : 0.45f);
        for (int i = 0; i < whitelistContent.getChildCount(); i++) {
            whitelistContent.getChildAt(i).setEnabled(enabled);
        }
    }

    private void updateStatus() {
        boolean service = QSApp.isXposedServiceConnected();
        frameworkStatus.setText(service
                ? "● LSPosed service: connected"
                : "○ LSPosed service: not connected yet");
        frameworkStatus.setTextColor(service ? Color.rgb(26, 120, 65) : Color.rgb(170, 95, 25));

        SharedPreferences prefs = getSharedPreferences(ModuleConfig.PREFS, Context.MODE_PRIVATE);
        int seenBoot = prefs.getInt(ModuleConfig.PREF_HOOK_BOOT_COUNT, -1);
        int currentBoot = getBootCount();
        String hookVersion = prefs.getString(ModuleConfig.PREF_HOOK_VERSION, "");
        boolean hookAlive = currentBoot >= 0 && currentBoot == seenBoot;

        hookStatus.setText(hookAlive
                ? "● SystemUI hook: active this boot" + (hookVersion.isEmpty() ? "" : " (v" + hookVersion + ")")
                : "○ SystemUI hook: not detected this boot");
        hookStatus.setTextColor(hookAlive ? Color.rgb(26, 120, 65) : Color.rgb(170, 95, 25));

        int mode = QSApp.getLocalMode(this);
        modeStatus.setText("Current mode: " + (mode == ModuleConfig.MODE_BLOCK_SHADE
                ? "Block shade"
                : "Require unlock"));
        modeStatus.setTextColor(Color.rgb(65, 65, 75));
    }

    private int getBootCount() {
        try {
            return Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }
}

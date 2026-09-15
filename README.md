# QS Security v1.5.0 — LineageOS 23 / Android 16

Polished build of the working v1.4.2 module. The proven **Require unlock** replay hook and **Block shade** hook are kept intact; v1.5 adds UI/status/whitelist features around them.

## Requirements

- LineageOS 23 / Android 16 (API 36)
- LSPosed-compatible framework implementing modern libxposed API 101
- Secure lock screen

## Protection modes

### Require unlock

Quick Settings remains visible on the lock screen, but protected tile actions require fingerprint/PIN/pattern/password. After authentication, the exact intercepted tile action is replayed once.

### Block shade

Quick Settings / notification shade cannot be pulled down while keyguard is locked. It becomes normal again after unlock.

## New in v1.5

- Cleaner scrollable settings UI with separate Status, Protection mode, Tile whitelist and Notes cards.
- **LSPosed service status** indicator.
- **SystemUI hook detected this boot** indicator. SystemUI sends a lightweight heartbeat after hooks are installed; this is a diagnostic indicator, not a security boundary.
- Shorter mode descriptions.
- Optional **tile whitelist** for Require unlock mode.
- Whitelist defaults to empty, preserving v1.4.2 behavior after upgrade.
- Whitelist is fail-secure: if a ROM's tile spec cannot be identified, that tile remains protected.

### Whitelist choices

- Flashlight
- Internet / Wi-Fi
- Bluetooth
- Hotspot
- Auto-rotate
- Do Not Disturb
- Location
- Battery Saver
- Airplane mode
- Screen Record

Whitelist is ignored in **Block shade** mode because the shade itself is blocked.

## Build on GitHub

1. Upload the contents of this project to the root of your GitHub repository.
2. Open **Actions → Build APK → Run workflow**.
3. Download artifact **QS-Security-APK**.
4. The artifact contains `QS-Security-v1.5.0.apk`.

The release artifact is debug-signed for easy personal testing, matching previous project builds.

## Install / update

1. Install/update the APK.
2. Enable it in LSPosed.
3. Scope only **System UI (`com.android.systemui`)**.
4. Reboot or restart SystemUI once after replacing the module APK.
5. Open QS Security and select a mode.

## Logs

```sh
adb logcat -c
adb logcat | grep -i QSSecurity
```

Useful v1.5 lines include:

```text
RemotePreferences ready; mode=... whitelist=...
ALLOW whitelisted tile=flashlight via NEW_VM
BLOCK NEW_VM ...
Authenticated QS action replayed: ...
BLOCK status-bar handler ...
```

## Project configuration

- libxposed API: **101.0.1**
- libxposed service: **101.0.0**
- min/target Xposed API: **101**
- compileSdk / targetSdk: **36**
- Java: **17**
- scope: **com.android.systemui** only
- modern `META-INF/xposed/*` metadata

# QS Security v1.4.1 — LineageOS 23 / Android 16

Modern LSPosed/libxposed module that protects Quick Settings while the device is locked.

## Requirements

- LineageOS 23 / Android 16 (API 36)
- LSPosed-compatible framework implementing **libxposed API 101**
- Secure lock screen (PIN / pattern / password; biometrics are handled by SystemUI)

## Modes

### 1. Require unlock for QS tile actions

Quick Settings can still be opened while locked. Primary click, secondary click and long-click are intercepted on both the legacy and Android 16 QS paths.

When a protected tile is tapped:

1. The original tile action is blocked.
2. SystemUI's native keyguard bouncer is requested where available.
3. A 15-second unlock watcher is armed at the same time.
4. As soon as Android reports that keyguard is actually dismissed, the **exact intercepted tile action is replayed once**.
5. If authentication is cancelled, the queued action expires and is not executed.

The unlock watcher fixes ROMs where the bouncer appears and unlock succeeds but `postQSRunnableDismissingKeyguard()` never runs a module-provided callback.

### 2. Block QS / shade pull-down while locked

The module blocks the top-edge touch stream before Android can transfer it into the shade. It covers:

- `PhoneStatusBarView` top-bar touch routing
- `NotificationShadeWindowView.dispatchTouchEvent()`
- `NotificationPanelViewController` legacy touch methods
- Android 16 `handleExternalTouch()` / `handleExternalInterceptTouch()` migration paths
- `CommandQueue.panelsEnabled()` and direct-QS expansion fallbacks

Only the top-edge shade gesture is consumed at the shade root, so normal lockscreen content lower on the screen (notifications / swipe-up-to-unlock) is not intentionally disabled. The panel works normally again immediately after keyguard is unlocked.

## Android 16 QS compatibility

The module hooks:

- `com.android.systemui.qs.tileimpl.QSTileImpl`
- `QSTileViewModelAdapter` variants
- `com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelImpl#onActionPerformed`

## Build on GitHub

1. Create/open your GitHub repository.
2. Upload the contents of this project to the repo root.
3. Open **Actions → Build APK → Run workflow**.
4. Download artifact **QS-Security-APK**.
5. The artifact contains `QS-Security-v1.4.1.apk`.

The test release is signed with the Android debug key so it can be installed directly. Keep the same signing scheme for updates to this test package, or uninstall the previous build if Android reports a signature mismatch.

## Install / activate

1. Install the APK.
2. Enable the module in LSPosed.
3. Scope only **System UI (`com.android.systemui`)**.
4. Reboot once after replacing the module APK.
5. Open QS Security and select one of the two modes.

Mode changes are read by SystemUI with a short cache, so normally they do not require another reboot.

## Runtime log test

```sh
adb logcat -c
adb logcat | grep -i QSSecurity
```

For mode 1, after tapping a locked tile and authenticating, expected lines include:

```text
BLOCK NEW_VM ...
Native SystemUI keyguard bouncer requested + replay armed
UNLOCK detected by watcher; replaying pending QS action
Authenticated QS action replayed: ...
```

For mode 2, a pull from the top while locked should produce one or more of:

```text
BLOCK status-bar touch ...
BLOCK_SHADE gesture armed ...
BLOCK shade root dispatch ...
BLOCK panel touch handleExternalTouch ...
```

## Project configuration

- libxposed API: **101.0.1** / service: **101.0.0**
- min/target Xposed API: **101**
- compileSdk / targetSdk: **36**
- Java: **17**
- scope: **com.android.systemui** only
- modern `META-INF/xposed/*` module metadata


## v1.4 block-shade fix

Mode **Require unlock** dari v1.2 tidak diubah. Perubahan v1.4 hanya memperkuat mode **Block Quick Settings / notification shade** untuk Android 16/LineageOS 23:

- hook `CentralSurfacesImpl#getCommandQueuePanelsEnabled()`
- hook `PhoneStatusBarViewController` nested `Gefingerpoken` touch handler (`onInterceptTouchEvent`/`onTouchEvent`)
- hook `PhoneStatusBarViewController#sendTouchToView()`
- hook `PhoneStatusBarView#onInterceptTouchEvent()` selain touch/dispatch lama
- capture `SystemUI` application context sebelum gesture pertama
- top-edge fallback memakai `rawY` agar tidak salah koordinat pada window/status-bar terpisah

Saat mode block aktif dan keyguard terkunci, gesture dari status bar dimakan sebelum diteruskan ke SceneContainer atau shade controller.


## v1.4 configuration fix

Mode selection now uses the official libxposed `RemotePreferences` channel. Earlier builds used a custom exported `ContentProvider`; when SystemUI could not read it, the hook intentionally fell back to `REQUIRE_UNLOCK`, so selecting **Block shade** could appear to behave exactly like the first mode. v1.4 removes that provider bridge.


## v1.4.1 build fix

GitHub Actions log from v1.4 showed `io.github.libxposed:service:102.0.0` and its interface dependency require `compileSdk >= 37`, while this module intentionally targets LineageOS 23 / Android 16 (`compileSdk 36`). v1.4.1 therefore uses modern libxposed API 101 instead. This still satisfies the module requirement of Xposed API > 100 and keeps the Android 16 build on SDK 36. The QS hook logic from v1.4 is unchanged.

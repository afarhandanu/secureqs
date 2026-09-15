# QS Security v1.1 — LineageOS 23 / Android 16


Modern LSPosed/libxposed module that protects Quick Settings while the device is locked.

## Requirements

- LineageOS 23 / Android 16 (API 36)
- An LSPosed-compatible framework implementing **libxposed API 102**
- Secure lock screen recommended (PIN / pattern / password; biometric behavior is controlled by SystemUI)

## Modes

### 1. Require unlock for QS tile actions

The notification shade / Quick Settings can still be opened while locked.
Primary click, secondary/toggle click, and long-click entry points are intercepted.
SystemUI's own `ActivityStarter.postQSRunnableDismissingKeyguard()` is used where available.
After successful keyguard authentication, the exact original tile action is replayed once.

Fallback behavior on ROMs where ActivityStarter cannot be resolved: Android's device-credential
screen is opened, then the user taps the tile again.

### 2. Block shade while locked

While keyguard is locked, `CommandQueue.panelsEnabled()` is forced to `false` and two additional
AOSP/Lineage fallback gates are also protected. The panel becomes available normally as soon as
the device is unlocked.

This mode intentionally blocks the **whole notification shade**, not only the tile grid.

## Android 16 tile compatibility

The module hooks both:

- Legacy/current `com.android.systemui.qs.tileimpl.QSTileImpl`
- Android's newer `QSTileViewModelAdapter` compatibility layer when present

Third-party Quick Settings tiles are also normally represented through SystemUI's `CustomTile`,
which inherits the legacy QSTile path.

## Build on GitHub

1. Create a new GitHub repository.
2. Upload the contents of this project (not the outer ZIP folder if you extracted it).
3. Open **Actions → Build APK → Run workflow**.
4. After the workflow completes, download artifact **QS-Security-APK**.
5. It contains `QS-Security-v1.0.0.apk`.

The release build is signed with Android's generated debug key so the artifact is directly
installable for personal testing. If you later publish updates publicly, replace this with your
own persistent release keystore.

## Install / activate

1. Install the APK.
2. Open **QS Security** and choose one of the two modes.
3. In LSPosed, enable the module. Its static scope is only:
   `com.android.systemui`
4. Restart SystemUI or reboot once after enabling/updating the hook.
5. Changing modes later does not require reboot; SystemUI reads the app's read-only settings
   provider with a short cache (under one second).

## Troubleshooting

Check LSPosed logs for tag `QSSecurity`. On a compatible Android 16 build you should see entries
similar to:

- `SystemUI ready; installing hooks`
- `Hooked CommandQueue.panelsEnabled`
- `Legacy QSTileImpl action hooks: ...`
- optionally `New-arch adapter hooks ...`

If a ROM fork renames a SystemUI class, the module fails that specific fallback hook rather than
crashing SystemUI (`exceptionMode=protective`).

## Project choices

- libxposed API: **102.0.0**
- min/target Xposed API: **102**
- Android compile/target SDK: **36**
- SystemUI-only scope
- No legacy `assets/xposed_init`


## v1.1 fix

Android 16 migrates Quick Settings from legacy `QSTileImpl` to the new ViewModel architecture.
This version blocks both paths:

- `QSTileImpl.click/secondaryClick/longClick` (legacy)
- `QSTileViewModelAdapter.click/secondaryClick/longClick` (migration adapter)
- `QSTileViewModelImpl.onActionPerformed` (new architecture hard gate)

Useful log check after reboot:

```sh
adb logcat -d | grep -i QSSecurity
```

When a locked tile press is intercepted you should see a line beginning with `BLOCK`.

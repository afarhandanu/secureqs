# QS Security v1.6.0 – Power Guard

Based on the stable v1.5/v1.4.2 hook set for LineageOS 23 / Android 16.

## Added in v1.6
- Protects the Quick Settings footer power button while the keyguard is locked.
- Protects stock Power off / Restart actions from the global power menu.
- AdvancedPowerMenu 2.x compatibility: gates its SHOW/RUN_POWER broadcasts, covering Recovery, Bootloader, Safe Mode, SystemUI/Zygote restart and power-off/reboot actions.
- Uses the same native keyguard + replay mechanism as protected QS tiles: authenticate first, then the requested action is replayed once.
- Independent `Protect power actions while locked` toggle, enabled by default.
- Emergency is not hooked.

## Existing behavior preserved
- Require unlock mode for QS tiles.
- Block shade mode from the stable build.
- Tile whitelist.
- libxposed API 101 / Android 16 API 36.

## After updating
Reboot the device once after updating the module.

## Scope
`com.android.systemui` only.

## Build
Push the project to GitHub and run **Actions → Build APK**.

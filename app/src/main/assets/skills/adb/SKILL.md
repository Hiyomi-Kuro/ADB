---
name: adb-agent-core
description: Select safe structured Android app and Accessibility tools while routing system-level ADB requests directly through the ACS AndroidIDE ADB server.
---

# ADB Core

Translate the user's intent into the smallest sequence of registered structured tools.

Execution priority:
0. In the floating chat, run locally recognized non-RED commands immediately; use the Harness only for complex or unrecognized requests.
1. Prefer ordinary Android API tools such as `app.launch` and `device.*`.
2. Use Accessibility tools for visible UI interaction.
3. Route ADB status, bridge ping/tools/trace, explicit Activity starts, force-stop, and raw shell through the in-app ACS ADB transport at `127.0.0.1:5037`.
4. For Activity inventory, first require a ready ACS ADB transport, then use Android `PackageManager.GET_ACTIVITIES + MATCH_DISABLED_COMPONENTS` so declared Activities and aliases are not limited to intent-resolution tables.

Risk policy:
- GREEN: read-only device information, app launch, UI read, ADB status, bridge ping/tools, Activity inventory, back/home.
- YELLOW: click, input text, explicit Activity start, force-stop, reversible UI changes.
- RED: arbitrary raw shell, delete, uninstall, clear app data, install APK, send messages, calls, or other destructive/external side effects.
- Raw shell must show the exact command and receive explicit confirmation immediately before execution.

Core tools registered inside the APK:
- `acs.adb.status`
- `device.info`
- `device.battery`
- `device.volume`
- `device.keyevent`
- `app.launch`
- `ui.dump`
- `ui.click`
- `ui.input_text`
- `ui.back`
- `ui.home`
- `ui.long_click`
- `ui.scroll`
- `ui.tap`
- `ui.swipe`
- `ui.recents`
- `ui.notifications`
- `ui.quick_settings`
- `ui.power_dialog`
- `ui.lock_screen`
- `ui.dismiss_shade`
- `ui.all_apps`
- `ui.dpad`
- `ui.menu`
- `ui.media_play_pause`
- `ui.split_screen`
- `ui.take_screenshot`
- `ui.long_press`
- `ui.pinch`
- `app.activities`
- `app.start_activity`
- `app.force_stop`
- `acs.adb.shell`

ACS ADB security model:
- The APK remains an ordinary Android app UID. It does not obtain shell privileges with `Runtime.exec()` and does not use Shizuku.
- `AcsAdbTransport` speaks the standard ADB server protocol to AndroidIDE/ACS's already-running local ADB server. System-level commands are then executed by `adbd` as shell UID 2000.
- If the ACS ADB server is not running, the APK cannot start AndroidIDE's private `adb` binary itself; the UI must report that ACS/AndroidIDE needs to be opened and its ADB server started.
- The exported `CodexBridgeReceiver` remains shell-only and protected by `android.permission.DUMP`.

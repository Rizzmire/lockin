# LockIn Android wrapper

Your `focuslock-5.html` is bundled unchanged as `app/src/main/assets/index.html`.
This app implements the native `Android` bridge the page expects, so the real lock,
alarms, app list and device-owner mode work, and "demo speed" switches itself off.

## Build
1. Install Android Studio (Koala or newer; JDK 17 is bundled).
2. File > Open > this folder. Let Gradle sync (it downloads Gradle 8.7 itself).
3. Build > Build APK(s). Output: `app/build/outputs/apk/debug/app-debug.apk`.
   (Command line: `gradle assembleDebug`, or `assembleRelease` for a smaller APK.)
4. Install: `adb install -r app-debug.apk`, or copy the APK to the phone.

Requires Android 10 (API 29) or newer.

## Full lock (device owner), once
Home, Recents and the status bar are only blocked in device-owner mode.
Without it the app falls back to screen pinning (user can escape with Back + Recents).

1. Remove all Google/other accounts from the phone temporarily
   (Settings > Accounts). Android refuses device owner while accounts exist.
   Xiaomi/Redmi: also enable "USB debugging (Security settings)"; remove secondary users/Second space.
2. Enable USB debugging, connect to a computer, run:
       adb shell dpm set-device-owner com.focuslock.app/.AdminReceiver
3. Open LockIn > Setup: "Full lock (device owner)" turns green. Re-add your accounts.
4. Allow the other rows too: exact alarms, notifications, battery unrestricted, display over other apps.

To remove the app later use the in-app "Request release" (24 h wait, blocked during a session),
then "Release and allow uninstall".

## What each bridge method does
| Page calls | Native behaviour |
|---|---|
| isNative | true, so the page leaves demo mode |
| getApps / openApp / openDialer | launcher apps with icons; launch an app; open the dialer |
| startLock / stopLock / isLocked | device owner: whitelist (LockIn + allowed apps + dialer), `startLockTask()` with all system UI disabled; otherwise plain screen pinning. Safety timeout = task minutes + 2; survives app kill and reboot |
| setAlarms | `AlarmManager.setAlarmClock` per task, repeats weekly, re-armed after reboot, clock or timezone change |
| alarm fires | full-screen notification (works with screen off), plus direct launch when overlay permission or device owner; page gets `onNativeStart(id)` |
| getStatus / openSetting | status of owner, exact alarms, notifications, battery, overlay; opens the matching settings screen |
| copy | clipboard |
| releaseState / requestRelease / cancelRelease / confirmRelease | 24 h wait, then `clearDeviceOwnerApp` |
| (injected shim) | backup and proof-image downloads are saved to the Downloads folder |

Camera (`getUserMedia`) and the restore file picker are handled by the WebView client.
The page is served from `https://appassets.androidplatform.net`, so if your sync/AI server
checks CORS, allow that origin. Plain `http://` server URLs are permitted.

## Notes
- Data from an old converter-made APK is separate: use Backup in the old app, Restore here.
- While locked, an allowed app opens from the LockIn tiles; return with Back.
  Test this on your phone, behaviour of third-party apps under lock-task varies.
- Change the package name in `app/build.gradle`, the manifest namespace and the adb command together if you ever rename it.

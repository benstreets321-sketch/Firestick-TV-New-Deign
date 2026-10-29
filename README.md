# My Home: a custom Fire TV home screen

A home screen for the Fire TV Stick (tested target: Fire TV Stick 4K Plus, Fire OS 8.1):

- Rotating background photos of landscapes and wildlife (Bing's photos of the day), with a slow crossfade.
  Pictures pushed to `/sdcard/Android/media/com.firestick.home/wallpapers/` join the rotation.
- Time and date top right, with live Premier League scores underneath (ESPN's public scoreboard).
- A small menu button top left: Settings, Appstore, a new background photo, Amazon's home screen.
- Every app in one row along the bottom, banners kept at their own proportions.

The HOME button takeover is adapted from [dumbtv home](https://github.com/humblemuzzu/dumbtv) (MIT, see
`app/LICENSE-dumbtv`): an accessibility service that replaces Amazon's home window whenever it appears.

## Build

```sh
sudo apt-get install aapt apksigner zipalign dalvik-exchange
ANDROID_JAR=/path/to/android-30/android.jar ./app/build.sh   # app/build/my-home.apk
```

## Install (Windows PowerShell, in the platform-tools folder)

```powershell
.\adb connect <stick-ip>:5555
.\adb install -r my-home.apk
.\adb shell cmd package set-home-activity com.firestick.home/.Home
.\adb shell "settings put secure enabled_accessibility_services ''"
.\adb shell settings put secure enabled_accessibility_services com.firestick.home/com.firestick.home.Guard
.\adb shell settings put secure accessibility_enabled 1
.\adb shell settings put global alarm_manager_constants min_futurity=0
```

Undo: `.\adb shell "settings put secure enabled_accessibility_services ''"` gives HOME back to Amazon.

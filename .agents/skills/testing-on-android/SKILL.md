---
name: testing-on-android
description: Deploy No Papers Needed to a connected phone or emulator, run the Android device tests, read logcat and drive the UI. Use when asked to test on a device, verify Android-only behaviour (scanner, share intents, WorkManager, permissions, sync), reproduce an Android bug, or capture a phone screenshot for a bug report.
---

# Testing on Android

The app can open a **real library**, so know which library you are about to touch before
tapping anything.

## 1. Find the device

```sh
adb devices -l
mise devices          # same, with the model/product columns
```

- A wireless phone can appear twice (an IP serial and an mDNS serial). `mise deploy` pins
  `ANDROID_SERIAL` to the IP one; set `ANDROID_SERIAL=<serial>` yourself when a phone and an
  emulator are both online.
- Nothing connected: `mise pair` is interactive and needs the user's pairing code — ask them to
  run it, or use an emulator (`mise emulator --headless [avd]`), which needs no pairing.
- After a network change, reconnect an already-paired phone with `mise connect <ip:port>`; don't
  re-pair.

## 2. Deploy the build you mean

```sh
mise deploy               # debug package, installs beside the release build
mise deploy --release     # release package; needs NPN_KEYSTORE_* in the environment
```

The debug package is `io.github.jeroenev.NoPapersNeeded.debug` ("NPN debug", amber icon); the
release package is `io.github.jeroenev.NoPapersNeeded`. The debug build proposes
`Documents/NoPapersNeeded-debug`, so it only touches the real library if someone points it there.

Check what is actually installed before blaming the code:

```sh
adb shell pm list packages | grep -i nopapers
adb shell dumpsys package io.github.jeroenev.NoPapersNeeded.debug | grep -E 'versionName|lastUpdateTime'
```

Update an existing build with `mise deploy`. If installation fails because the signing keys
differ, explain that uninstalling deletes app-private device state, including chats and queues,
and obtain approval before uninstalling. `mise deploy` rebuilds the bundled rclone for the device ABI when it is
not `arm64-v8a`; a bare `./gradlew installDebug` does not, and cloud sync then fails on an
emulator.

## 3. Keep the device usable

- **Battery optimisation** kills background sync. On the test device, disable battery
  optimisation for the app (or ask the user; OEM settings differ), and keep the screen awake:
  `adb shell svc power stayon true`.
- **Network**: cloud-sync timings are only meaningful on a stable network. "Slow" on a congested
  or shared uplink is not a finding — ask about the link before profiling.
- **Scanner**: ML Kit runs in Play services and needs a real phone with Play services; the
  emulator is for everything else.
- **Real vs demo library**: More → Settings → Library shows the folder in use. Don't switch the
  user's library silently; note the real path if you must change it, and tell them how to switch
  back.

## 4. Observe

App diagnostics go to logcat under one tag:

```sh
adb logcat -s npn
adb logcat --pid="$(adb shell pidof -s io.github.jeroenev.NoPapersNeeded.debug)"
```

Screenshots and taps (no display needed on this machine):

```sh
adb exec-out screencap -p > shot.png
adb shell input tap X Y
adb shell input swipe X1 Y1 X2 Y2 300
adb shell input keyevent KEYCODE_BACK
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
```

`uiautomator dump` shows Compose semantics text and bounds when it is exposed; otherwise locate
text by OCR: `.agents/skills/driving-the-desktop-app/scripts/ocr.py` works on any PNG. A capture
can catch the lock screen or notification shade with the user's personal notifications — check it
and delete it, never commit one.

## 5. Device tests

```sh
./gradlew :core:connectedAndroidDeviceTest :composeApp:connectedAndroidDeviceTest \
  :androidApp:connectedDebugAndroidTest
```

These need a device and are not part of `check`; `compileDeviceTests` only builds them. A failure
that only appears here is still real: read the stack trace, then reproduce with `mise deploy` and
logcat. R8-specific paths need the release build (`installBenchmarkRelease`, DEVELOPMENT.md,
"Release builds").

## 6. Finish

- Stop an emulator you started: `adb emu kill`.
- Leave the phone on the library the user expects; if you switched it, switch back and say so.
- Report the serial, package, build type, the library path, and the log or screenshot that shows
  the behaviour. For README/Play Store captures, use `refreshing-screenshots` instead of this
  skill's raw captures.

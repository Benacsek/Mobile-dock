## Mobile Dock for Android

The phone app. It runs in landscape and keeps the screen on while connected, so it works as a dock beside your keyboard.

## Supported versions

- Minimum: Android 4.1 Jelly Bean (API 16), from 2012.
- Target: Android 16 (API 36).

The app has no libraries. It uses only the Android framework, plain Java and crypto that every Android version ships. The UI is built in code, so there are no layout features that need a newer system.

Android 4.0 and older are not supported. The current Android build tools do not produce apps for them, and those phones lack features the app needs.

## Install

1. Download `MobileDock-<version>.apk` from the latest GitHub release.
2. Copy it to the phone and open it. Allow installs from this source when asked.

You can also install over USB:

```bash
adb install -r app-debug.apk
```

After this first install the app updates itself through the PC. See Updates below.

Debug builds from the Actions tab are signed with a different key on every run. They cannot update each other or the release build. If you installed one, uninstall it once before you install the release APK.

## Use

The dock bar sits at the bottom of the screen with eight tiles. Tap a tile to open its page.

App icons all get the same rounded-square shape. Names can use two lines. The grid spreads apps evenly over the rows, so 8 apps make two rows of 4.

- Apps: tap a tile to open it on the PC. Long press a tile to drag it. Drop it on another tile to move it there, or on the red bar to unpin. The header shows which PC you are connected to. Its icons are + to pin more apps and the arrows for Arrange.
- Playing: album art, title, artist, album and a progress bar you can drag to seek. Play, skip, shuffle, repeat and volume. Opening Spotify or another music app from Apps jumps here.
- Shortcuts: tiles for the app in front on the PC. The target icon in the header turns Follow on or off. Tap the pad name to pick another pad.
- Controls: volume, mute, lock, screen off, sleep, show desktop, Task Manager and Disconnect. Sleep asks first. The volume mixer lists every app playing sound with its own slider and mute button. Tap the refresh icon after you start a new app.
- Capture: screenshot, snip a region (Win+Shift+S on Windows), record, picture to the PC clipboard and file to Downloads. Clipboard text: Phone to PC and PC to phone.
- Touch: switch between Touchpad, Keyboard and Sign at the top.
  - Touchpad: one finger moves. Tap to click. Two finger tap is right click, three finger tap is middle click. Two fingers scroll. Hold Left and move to drag.
  - Keyboard: type in the box and the text goes to the PC as you type. Tap Ctrl, Alt, Shift or Win before a key to make a shortcut.
  - Sign: draw with your finger. Send to PC puts a PNG with a clear background on the clipboard. Paste it with Ctrl+V.
- Stats: CPU, GPU, memory, network and disk graphs for the last minute, plus the busiest apps. Temperatures and fan speeds show when the PC can read them.
- Settings: accent color, tile size, background glow, dock labels and the volume buttons switch on the left. Phone and PC versions, the PC's update status and Check now on the right.
- Arrange: the arrows icon on Apps. A tap picks a tile up. Tap the icon again or press Back when you are done.
- Fullscreen: the corners icon at the right end of the dock bar hides the bar on any page. Tap the small button in the corner or press Back to bring it back.

Volume buttons: while the dock is open and connected, the phone's volume buttons change the PC volume. Turn this off in Settings.

Pair with a QR code: when you click Pair new phone, the PC shows a QR code. Scan it with the phone's camera app and tap the link. Mobile Dock opens and asks before it pairs. If your camera app does not read QR codes, type the PIN instead.

## Wake the PC

After the phone has connected once, the connect page shows Wake PC. It sends a Wake-on-LAN signal, which can turn on a PC that sleeps or is shut down. The PC must allow it. See the host README.

## Start with your PC

Turn on "Wake up and connect when my PC turns on" on the connect page. Tap Disconnect on the Controls page to reach it.

- A small background service sends the discovery probe every 5 seconds while charging and every 60 seconds on battery.
- When a paired PC answers after being off, or after the PC app restarted, the phone turns its screen on, opens the dock and connects.
- Android 10 and newer only let an app open itself when you allow "Display over other apps". The app asks for it. Without it, you get a "Your PC is on" notification to tap.
- On Android 13 and newer, allow notifications, or the service's notification stays hidden.
- Allow the battery exemption when asked, or Android may stop the service.
- After you tap Disconnect, the phone stays quiet until the PC app starts again.
- "Show the dock over the lock screen" is a separate switch and off by default. With it on, anyone near the phone can use the dock.
- The status box under the switches shows the app version, what the watcher saw last, and each permission. Fix permissions asks for the next missing one. Show log lists the last 30 watcher events, such as "PC app is running", "Woke the phone" or why it could not open.
- The PC app starts when you log in to Windows, not at power on. Until you log in, the phone has nothing to find.

## Build

You need JDK 17 or newer and the Android SDK with platform 36.

```bash
cd android
```

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Updates

1. When the phone connects, it asks the PC for a newer version.
2. The PC has already downloaded it from the latest GitHub release.
3. The phone downloads it from the PC, checks its SHA-256 and shows Update ready.
4. You tap Install. On Android 8 and newer, the first time, Android asks you to allow Mobile Dock to install apps.

## Release signing

Phones accept an update only when it's signed with the same key as the installed app. So CI needs one fixed key.

Create a key once and keep it safe:

```bash
keytool -genkeypair -keystore mobiledock.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 -alias mobiledock
```

Add two repository secrets under Settings, Secrets and variables, Actions:

- `MDOCK_KEYSTORE_B64`: the .jks file in base64, on one line.
- `MDOCK_KEYSTORE_PASSWORD`: its password.

For a local build, set `MDOCK_KEYSTORE` to the file path and `MDOCK_KEYSTORE_PASSWORD`, then run `./gradlew assembleRelease`.

If you lose the key, phones can't update anymore. You'd have to uninstall and reinstall with a new key. Never commit the keystore. The `.gitignore` blocks `.jks` files.

## Privacy

- Permissions: Internet, to reach your PC. Wi-Fi state, to find PCs on the subnet. Install packages, to open the installer for updates. No storage permission: updates stay in app-private storage until they are installed.
- For the optional PC watcher: foreground service, wake lock, start at boot, notifications, display over other apps and the battery exemption. None of them do anything until you turn the watcher on.
- No analytics, no ads, no Google services.
- Pairing keys stay in app private storage. Backup and device transfer are turned off for them. On Android 6 and newer they are also encrypted with a Keystore key that never leaves the phone.
- Sharing a file to Mobile Dock always asks before anything is sent.

## Code map

| File | Role |
| --- | --- |
| `proto/` | Pure Java protocol: SPAKE2, AES and HMAC channel, framing |
| `DockClient.java` | Connection, reconnect, requests |
| `Discovery.java` | UDP broadcast to find PCs |
| `MainActivity.java` | Connect page, dock bar, pages, fullscreen |
| `Ui.java`, `Icon.java` | Theme, dock tiles and line icons drawn in code |
| `AppsPanel.java` | Pinned apps grid with drag and drop and arrange mode |
| `ControlsPanel.java`, `CapturePanel.java` | PC controls, capture and sending |
| `TouchPanel.java`, `SignPanel.java` | Touchpad, keyboard and signature pad |
| `AppearancePanel.java`, `UpdatesPanel.java` | Theme settings and versions |
| `MediaPanel.java` | Now playing with art, seek, shuffle and repeat |
| `PadPanel.java`, `KeysPanel.java` | Touchpad and keyboard |
| `IconCache.java` | App icons from the PC, cached on the phone |
| `PhoneUpdater.java`, `ApkProvider.java` | Updates through the PC |

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

- Apps: tap a tile to open it on the PC. Long press a tile to drag it. Drop it on another tile to move it there, or on the red bar to unpin. Tap + Add app to pin more.
- Now playing: album art, title, artist, album and a progress bar you can drag to seek. Play, skip, shuffle, repeat and volume. Opening Spotify or another music app from the Apps grid jumps here. Back returns to Apps.
- Touchpad: one finger moves. Tap to click. Two finger tap is right click, three finger tap is middle click. Two fingers scroll. Hold Left and move to drag. Speed changes the pointer speed.
- Keys: type in the box and the text goes to the PC as you type. Tap Ctrl, Alt, Shift or Win before a key to make a shortcut. The shortcut row has copy, paste, undo and more.
- Screenshot and Record sit at the bottom of the side rail. Files save on the PC.

## Start with your PC

Turn on "Wake up and connect when my PC turns on" on the connect page. Tap Disconnect in the rail to reach it.

- A small background service sends the discovery probe every 6 seconds while charging and every 60 seconds on battery.
- When a paired PC answers after being off, the phone turns its screen on, opens the dock and connects.
- Android 10 and newer only let an app open itself when you allow "Display over other apps". The app asks for it. Without it, you get a "Your PC is on" notification to tap.
- On Android 13 and newer, allow notifications, or the service's notification stays hidden.
- Allow the battery exemption when asked, or Android may stop the service.
- After you tap Disconnect, the phone stays quiet until the PC turns off and on again.
- "Show the dock over the lock screen" is a separate switch and off by default. With it on, anyone near the phone can use the dock.

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

- Permissions: Internet, to reach your PC. Wi-Fi state, to find PCs on the subnet. Install packages, to open the installer for updates. Storage on Android 4.1 to 4.3 only, to hand the update to the installer.
- For the optional PC watcher: foreground service, wake lock, start at boot, notifications, display over other apps and the battery exemption. None of them do anything until you turn the watcher on.
- No analytics, no ads, no Google services.
- Pairing keys stay in app private storage. Backup and device transfer are turned off for them.

## Code map

| File | Role |
| --- | --- |
| `proto/` | Pure Java protocol: SPAKE2, AES and HMAC channel, framing |
| `DockClient.java` | Connection, reconnect, requests |
| `Discovery.java` | UDP broadcast to find PCs |
| `MainActivity.java` | Connect page, side rail, panels |
| `AppsPanel.java` | Pinned apps grid with drag and drop |
| `MediaPanel.java` | Now playing with art, seek, shuffle and repeat |
| `PadPanel.java`, `KeysPanel.java` | Touchpad and keyboard |
| `IconCache.java` | App icons from the PC, cached on the phone |
| `PhoneUpdater.java`, `ApkProvider.java` | Updates through the PC |

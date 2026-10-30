## Mobile Dock for Android

The phone app. It runs in landscape and keeps the screen on while connected, so it works as a dock beside your keyboard.

## Supported versions

- Minimum: Android 4.1 Jelly Bean (API 16), from 2012.
- Target: Android 16 (API 36).

The app has no libraries. It uses only the Android framework, plain Java and crypto that every Android version ships. The UI is built in code, so there are no layout features that need a newer system.

Android 4.0 and older are not supported. The current Android build tools do not produce apps for them, and those phones lack features the app needs.

## Install

1. Download `app-debug.apk` or a signed `app-release.apk` from the latest GitHub Actions run or release.
2. Copy it to the phone and open it. Allow installs from this source when asked.

You can also install over USB:

```bash
adb install -r app-debug.apk
```

Each CI run signs debug builds with a fresh key. To update without uninstalling, use a release build signed with your own key. See below.

## Use

- Apps: tap a tile to open it on the PC. Long press a tile to drag it. Drop it on another tile to move it there, or on the red bar to unpin. Tap + Add app to pin more.
- Media: play, pause, skip and volume. The title updates every 3 seconds.
- Touchpad: one finger moves. Tap to click. Two finger tap is right click, three finger tap is middle click. Two fingers scroll. Hold Left and move to drag. Speed changes the pointer speed.
- Keys: type in the box and the text goes to the PC as you type. Tap Ctrl, Alt, Shift or Win before a key to make a shortcut. The shortcut row has copy, paste, undo and more.
- Screenshot and Record sit at the bottom of the side rail. Files save on the PC.

## Build

You need JDK 17 or newer and the Android SDK with platform 36.

```bash
cd android
```

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Release signing

Create a key once and keep it safe:

```bash
keytool -genkeypair -v -keystore mobiledock.jks -keyalg RSA -keysize 4096 -validity 10000 -alias mobiledock
```

For a local build, set `MDOCK_KEYSTORE`, `MDOCK_KEYSTORE_PASSWORD`, `MDOCK_KEY_ALIAS` and `MDOCK_KEY_PASSWORD`, then run `./gradlew assembleRelease`.

For CI, add these repository secrets: `MDOCK_KEYSTORE_B64` (the .jks file in base64), `MDOCK_KEYSTORE_PASSWORD`, `MDOCK_KEY_ALIAS` and `MDOCK_KEY_PASSWORD`. Never commit the keystore. The `.gitignore` blocks `.jks` files.

## Privacy

- Permissions: Internet, to reach your PC, and Wi-Fi state, to find PCs on the subnet.
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
| `MediaPanel.java`, `PadPanel.java`, `KeysPanel.java` | The other panels |

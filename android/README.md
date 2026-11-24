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

On a phone, a slim floating column of icons on the left holds Fullscreen, the 7 pages and Settings (the gear, at the bottom). Long press an icon to see its name. The gear shows an orange dot while the PC is not connected. Your PC's name and connection sit at the top of Settings, with the reason and what to check when it is not connected. On a tablet (600 dp or more on the short side), a sidebar lists the 7 pages by name, with your PC, Fullscreen and Settings at the top. The look follows Apple's design guidelines, in light or dark.

App icons all get the same rounded-square shape. Names can use two lines. The grid spreads apps evenly over the rows, so 8 apps make two rows of 4.

- Apps: tap a tile to open it on the PC. Long press a tile to drag it. Drop it on another tile to move it there, or on the red bar to unpin. Its icons are + to pin more apps and the dots for Arrange.
- Search: type in the box and tap Search. Auto picks the place: a keyword like `w Eiffel Tower` or `map pizza near me`, a site like `youtube` or `github.com`, the app in front on the PC, then the default search. Tap a preset chip to choose one yourself. Long press a chip to see its keyword. Your last 10 searches sit below, one tap to repeat.
- Playing: album art, title, artist, album and a progress bar you can drag to seek. Play, skip, shuffle and repeat. A slider and mute button set the PC volume, the same level as the taskbar. The -5 and +5 buttons move a video or song by 5 seconds, even with its window in the background. Opening Spotify or another music app from Apps jumps here.
- Shortcuts: tiles for the app in front on the PC. The pad name at the top opens a list: Follow the app in front, or one pad that stays. While it follows, the name reads "Auto: Figma".
- Controls: six tiles for Lock, Screen off, Sleep, Show desktop, Task Manager and Capture. Sleep asks first. Shut down sits below the tiles in red and also asks first. Sound has the PC volume on top, then one line per app playing sound, each with a slider and a mute button. The list reloads every 5 seconds while the page is open.
- Capture (from Controls, Back returns there): screenshot, snip a region (Win+Shift+S on Windows), record, picture to the PC clipboard and file to Downloads. Clipboard text: Phone to PC and PC to phone.
- Touch: switch between Touchpad, Keyboard and Sign at the top. Pointer speed (Slow, Normal or Fast) sits in the touchpad's top corner.
  - Touchpad: one finger moves. Tap to click. Two finger tap is right click, three finger tap is middle click. Two fingers scroll. Hold Left and move to drag.
  - Keyboard: type in the box and the text goes to the PC as you type. Tap Ctrl, Alt, Shift or Win before a key to make a shortcut.
  - Sign: draw with your finger. Send to PC puts a PNG with a clear background on the clipboard. Paste it with Ctrl+V.
- Stats: CPU, GPU, memory, network and disk graphs for the last minute, plus the busiest apps. Temperatures and fan speeds show when the PC can read them.
- Settings: the PC, Open when your PC starts and Disconnect at the top, then a short list of four groups. Disconnect asks first. Each group opens its own page, and Back or Settings at the top returns to the list.
  - Display: Appearance (Automatic, Light or Dark), accent color, app icon size and app labels. With labels off, Apps shows only the icons, a little bigger.
  - Idle screen: its color, its tiles, when it shows, and Preview.
  - Buttons and feedback: tap vibration and the volume buttons switch.
  - Updates: the phone and PC versions, the PC's update status, Check now and Copy diagnostics.
- Arrange: the dots icon on Apps. A tap picks a tile up. Tap the icon again or press Back when you are done.
- Fullscreen: the two arrows icon at the top of the icons or the sidebar hides them on any page. Tap the small button in the corner or press Back to bring it back.

Idle screen: after 5 minutes without a touch, the dock dims to a bedside clock on black. The first touch brings the dock back and does not press anything. In Settings, Idle screen, pick:

- The color: Blue, White, Red, Orange, Green or Pink. Red is the easiest on the eyes in a dark room.
- The big tile: Clock, Calendar or Playing (the song with its cover).
- The side tiles, from a checklist: Clock, Date, Calendar, Playing and Battery (the phone's charge). Check up to 4. The arrows on a checked tile move it up or down. One or two sit one above the other. With three, the first is wide on top and two share the row below. Four share a 2 x 2 grid.
- When it shows: Off, 1, 5 or 15 minutes.

Preview shows it right away. The tiles move a few dp now and then, so no pixel stays lit in one place.

Tap vibration: a short buzz when you tap a tile or button, so you know it worked without looking. It buzzes when you lift your finger, only for a tap. Scrolling across buttons, long presses, the touchpad, signature pad and sliders do not buzz. In Settings pick Off, Light, Medium or Strong. The phone buzzes once when you pick, so you can feel it. Older phones need Medium or Strong, because their motors need about 30 ms to start.

Volume buttons: while the dock is open and connected, the phone's volume buttons change the PC volume. Turn this off in Settings.

Pair with a QR code: when you click Pair new phone, the PC shows a QR code. Scan it with the phone's camera app and tap the link. Mobile Dock opens and asks before it pairs. If your camera app does not read QR codes, type the PIN instead.

## Wake the PC

After the phone has connected once, the connect page shows Turn on your PC when it cannot find that PC. It sends a Wake-on-LAN signal, which can turn on a PC that sleeps or is shut down. The PC must allow it. See the host README.

## Open when your PC starts

Turn on Open when your PC starts at the top of Settings. The same switch is on the connect page, under Other ways to connect. Turn it off in Settings when someone else uses the PC and the phone should stay asleep. The lock screen option, the status and Fix permissions stay on the connect page.

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

## Screenshots in CI

Every build starts a 720p landscape Android emulator, opens each page in demo mode and saves a picture of it, including each state of the connect page. Some pages are shot again on a small phone, on a tablet and with large system text, to catch labels that no longer fit. Plain unit tests in `app/src/test` check the logic that has no screen, such as pairing links and the app grid. Download `phone-screenshots` from the build in the Actions tab.

Demo mode shows sample data instead of a PC. It only works in debuggable builds, so the release app never uses it. To try it on a test phone with a debug build:

```bash
adb shell am start -n dev.mobiledock/.MainActivity --ez demo true --ei page 0
```

Pages: -1 connect, 0 Apps, 1 Search, 2 Playing, 3 Shortcuts, 4 Controls, 5 Capture, 6 Touch, 7 Stats, 8 Settings, 99 the night clock.
On page 8, `--ei settings_page N` opens a Settings page: 0 the list, 1 Display, 2 Idle screen, 3 Buttons and feedback, 4 Updates, 5 the big tile picker.
`--ez labels false` saves Labels under app icons as off, `--ez labels true` turns it back on.
On page -1, `--es connect_state searching|found|none` fills the PC list with sample PCs, and `--ei connect_page N` opens one of the other ways to connect: 0 the list, 1 Enter address, 2 USB cable, 3 Open when your PC starts.
On page 99, `--ei idle_main`, `--ei idle_tiles`, `--ei idle_color` and `--es idle_order` pick the tiles, color and order. idle_tiles adds up the side tiles: 1 Date, 2 Calendar, 4 Playing, 8 Battery, 32 Clock.

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

- Permissions: Internet, to reach your PC. Vibrate, for the buzz when you press a button. Wi-Fi state, to find PCs on the subnet. Install packages, to open the installer for updates. No storage permission: updates stay in app-private storage until they are installed.
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
| `MainActivity.java` | Connect page, icon rail and sidebar, pages, fullscreen |
| `Ui.java`, `Icon.java`, `IconPaths.java` | Light and dark colors, Inter, list rows, and the Phosphor icons drawn as paths |
| `Kit.java` | iOS controls drawn in code: switch, segmented control, slider, floating glass panel |
| `AppsPanel.java` | Pinned apps grid with drag and drop and arrange mode |
| `ControlsPanel.java`, `CapturePanel.java` | PC controls, capture and sending |
| `VolumeRow.java` | One volume slider with its mute button, on Controls and Now Playing |
| `TouchPanel.java`, `SignPanel.java` | Touchpad, keyboard and signature pad |
| `SettingsPanel.java`, `UpdatesPanel.java` | Settings list, its pages, and versions |
| `MediaPanel.java` | Now playing with art, seek, shuffle and repeat |
| `PadPanel.java`, `KeysPanel.java` | Touchpad and keyboard |
| `IconCache.java` | App icons from the PC, cached on the phone |
| `PhoneUpdater.java`, `ApkProvider.java` | Updates through the PC |

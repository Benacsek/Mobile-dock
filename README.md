## Mobile Dock

Mobile Dock turns an Android phone into a dock for your PC. Put the phone next to your keyboard in landscape. Tap it to open apps, control music, take screenshots, record the screen, or use it as a touchpad and keyboard.

It works on your local network or over USB. There is no account, no cloud service and no telemetry. The phone and the PC talk directly to each other, and all traffic is encrypted.

| Part | Folder | Runs on |
| --- | --- | --- |
| PC host | [host](host/README.md) | Windows 10 and 11, Linux with Hyprland |
| Phone app | [android](android/README.md) | Android 4.1 (API 16) up to Android 16 |

## What you can do from the phone

- Browse your pinned apps in a grid with their real icons. Tap one to open it on the PC.
- Long press a tile and drag it to a new spot. Drop it on the red bar to unpin it.
- Add apps from a searchable list of everything installed on the PC. Pin them from the phone or from the PC window.
- Open Spotify or another music app and the phone switches to Now playing. You see the album art, title, artist and album, a progress bar you can drag, and buttons for play, skip, shuffle, repeat and volume. It reads Windows media controls or MPRIS on Linux, so it needs no Spotify account.
- Shortcuts: tiles for the app in front on the PC. Pads for Figma, VS Code, browsers and Windows are built in. The page switches by itself when you switch apps. Edit pads and add your own in the PC window.
- Stats: live graphs for CPU, GPU, memory, network and disk, plus the busiest apps, like Task Manager. GPU load works for AMD, NVIDIA and Intel.
- Send: share a photo to Mobile Dock from any app, or tap Send in the dock. Pictures go to the PC clipboard, so Ctrl+V pastes them into Figma. Other files land in Downloads/Mobile Dock.
- Take a screenshot. It saves on the PC in Pictures/Mobile Dock.
- Start and stop screen recording. Videos save in Videos/Mobile Dock.
- Use the touchpad. Tap to click. Use two fingers to scroll or to right click.
- Type on the PC. Use Ctrl, Alt, Shift and Win keys, arrows, F1 to F12 and common shortcuts.
- Optional: turn on "Wake up and connect when my PC turns on". When your PC starts, or when you close and reopen the PC app, the phone turns its screen on, opens the dock and connects. It works best while the phone charges.

The PC window shows a green banner with the phone's name, how it connects and since when.

## Quick start

1. Install the host on your PC. See [host/README.md](host/README.md).
2. Install the APK on your phone. See [android/README.md](android/README.md).
3. Start the host. On Windows, allow it through the firewall on private networks when asked.
4. Open the app on the phone. Your PC shows up in the list if both are on the same Wi-Fi.
5. Click Pair new phone on the PC. Type the 6 digit PIN on the phone.

The phone remembers the PC. Next time it connects on its own.

## Connect over USB

1. Turn on USB debugging on the phone and plug it in.
2. Install Android platform-tools on the PC so the adb command works.
3. Click Enable USB in the host window. On Linux, run `mobile-dock-host --headless --usb`.
4. Tap Connect over USB on the phone.

This uses `adb reverse`, which needs Android 5.0 or newer. On Android 4.x, turn on USB tethering instead and connect by the PC address.

## Where to get the builds

Every push to main creates a GitHub release with `MobileDockHost.exe` and the signed APK. Download both from the Releases page once. After that, they update themselves.

## Automatic updates

- The PC checks this repository's latest release every 6 hours and once at start.
- The Windows .exe downloads the new version, checks its SHA-256, replaces itself and restarts. Your phone reconnects on its own.
- The PC also downloads the new phone app and sends it to the phone over the encrypted link. Old Android versions often cannot reach GitHub over HTTPS, so the PC does it for them.
- The phone shows Update ready. Tap Install and confirm. Android requires that tap for any app outside the Play Store.
- Turn updates off with `"auto_update": false` in the host config.
- Phone updates need a stable signing key in the repository secrets. See [android/README.md](android/README.md).

You can also build everything yourself. Each README explains how.

## Security

- Pairing uses a one-time 6 digit PIN with the SPAKE2 protocol. Someone on your network gets one guess per attempt, and the PIN dies after 5 wrong guesses or 3 minutes.
- After pairing, each phone has its own 256-bit key. Both sides prove they hold it on every connection.
- Every message is encrypted with AES-256 and signed with HMAC-SHA256. Replayed or changed messages close the connection.
- The phone can only open apps the PC found on its own. It never sends a command line.
- You can remove a phone at any time in the host window. Its key stops working at once.
- Updates come only from this repository's GitHub releases over HTTPS. Each file must match the SHA-256 in the release. Android also checks that the APK carries the same signature as the app you have.

Read [SECURITY.md](SECURITY.md) for the full threat model and [docs/PROTOCOL.md](docs/PROTOCOL.md) for the wire format.

## Project layout

```
host/        Python host for Windows and Linux
android/     Android app, plain Java, no libraries
docs/        Protocol description
.github/     CI that tests and builds everything
```

## License

MIT. See [LICENSE](LICENSE).

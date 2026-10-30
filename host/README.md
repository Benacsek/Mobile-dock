## Mobile Dock host

This is the PC side. One Python program runs on Windows and on Linux with Hyprland. It shows a small window with the pairing PIN and your paired phones.

## Windows

### Install

Option A. Download `MobileDockHost.exe` from the latest GitHub Actions run or release. Put it anywhere and run it. No installer is needed.

Option B. Run from source with Python 3.10 or newer:

```bash
pip install "./host[windows-media]"
```

```bash
mobile-dock-host
```

The `windows-media` extra shows the song title on the phone. Media keys work without it.

### First run

1. Windows Firewall asks about network access. Allow it on private networks only.
2. Click Pair new phone. A 6 digit PIN appears for 3 minutes.
3. Type that PIN in the phone app.
4. Tick Start with Windows if you want the dock ready after every login.

### Optional tools

- Screen recording needs ffmpeg. Install it with `winget install ffmpeg`.
- USB mode needs adb from Android platform-tools.

### Notes

- Apps come from the Start menu, including Store apps.
- Windows blocks input into apps that run as administrator unless the host also runs as administrator. This is a Windows rule.

## Linux with Hyprland

### Install

```bash
pipx install ./host
```

Install the tools the host calls. On Arch Linux:

```bash
sudo pacman -S python-evdev wtype playerctl grim wf-recorder libnotify tk
```

`wpctl` comes with PipeWire. `pactl` works as a fallback.

### Allow the virtual touchpad and keyboard

The host creates a virtual input device through `/dev/uinput`. Give your desktop session access:

```bash
sudo cp host/linux/99-mobile-dock-uinput.rules /etc/udev/rules.d/
```

```bash
sudo udevadm control --reload && sudo udevadm trigger
```

The rule uses the `uaccess` tag. Only the user at the active seat gets access. You do not need to join the input group, which would let programs read every keyboard.

### Start with Hyprland

Add the lines from `host/linux/hyprland.conf` to `~/.config/hypr/hyprland.conf`:

```
exec-once = mobile-dock-host --headless
bind = SUPER SHIFT, P, exec, mobile-dock-host --pair
```

Press Super+Shift+P to get a pairing PIN as a notification. Starting from Hyprland matters. It gives the host your Wayland session, so apps open with your window rules.

Prefer a window? Run `mobile-dock-host` without flags. It uses Tk through XWayland.

### What each tool does

| Tool | Feature |
| --- | --- |
| python-evdev | Touchpad, clicks, scroll, special keys |
| wtype | Typing text in any keyboard layout |
| playerctl | Play, pause, next, song title |
| wpctl or pactl | Volume |
| grim | Screenshots |
| wf-recorder or wl-screenrec | Screen recording of the focused monitor |
| notify-send | PIN and pairing notifications |

Without wtype, typing falls back to the US layout and ASCII only.

## Command line

```
mobile-dock-host [--headless] [--pair] [--usb] [--minimized] [--port N] [--verbose]
```

- `--headless` runs without a window.
- `--pair` shows a PIN. If a headless host already runs, it asks that host to show one.
- `--usb` runs `adb reverse` for USB mode.

## Where files go

| What | Windows | Linux |
| --- | --- | --- |
| Settings and pairing keys | `%APPDATA%\MobileDock\config.json` | `~/.config/mobile-dock/config.json` |
| Screenshots | `Pictures\Mobile Dock` | `~/Pictures/Mobile Dock` |
| Recordings | `Videos\Mobile Dock` | `~/Videos/Mobile Dock` |

On Linux the config file is mode 600 and its folder is mode 700. You can change `bind`, `port`, `discovery`, `screenshots_dir` and `recordings_dir` in the file. Set `bind` to `127.0.0.1` to allow USB only.

## Network ports

- TCP 47800 for the encrypted connection.
- UDP 47801 for discovery. It only answers with the PC name and port. Set `discovery` to false to turn it off.

## Tests

```bash
pip install -e "./host[dev]"
```

```bash
python -m pytest host/tests
```

`test_interop.py` runs the Android app's Java protocol code against this host. It needs a JDK and the org.json jar in `MDOCK_JSON_JAR`. CI runs it on every push.

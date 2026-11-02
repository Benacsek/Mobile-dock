## Mobile Dock host

This is the PC side. One Python program runs on Windows and on Linux with Hyprland. It shows a small window with the pairing PIN and your paired phones.

## Windows

### Install

Option A. Download `MobileDockHost.exe` from the latest GitHub release. Put it in a folder you own, such as `Documents\MobileDock`, and run it. No installer is needed. It updates itself from then on.

Option B. Run from source with Python 3.10 or newer:

```bash
pip install "./host[windows-media]"
```

```bash
mobile-dock-host
```

The `windows-media` extra powers Now playing on the phone: album art, progress, seek, shuffle and repeat. The .exe includes it. Media keys work without it.

### First run

1. Windows Firewall asks about network access. Allow it on private networks only.
2. Click Pair new phone. A 6 digit PIN appears for 3 minutes.
3. Under Pinned apps, click Add apps to choose what the phone shows. You can also do this from the phone.
4. Type that PIN in the phone app.
5. Tick Start with Windows if you want the dock ready after every login.

### Shortcut pads

Click Edit shortcut pads in the window. Each pad has a name, the programs it belongs to (for example `Figma.exe`) and its tiles. Write a shortcut like `Ctrl+Shift+G`. Separate steps with commas, like `Win+Left, Enter`. The Windows pad is used when no other pad matches.

Shortcuts with punctuation, such as `Ctrl+backslash`, use the key's position on a US keyboard. On another layout, such as Hungarian, pick the shortcut your app shows in its own menus.

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
| wl-clipboard | Pictures sent from the phone to the clipboard |
| hyprctl | Shortcuts page follows the focused window |

Without wtype, typing falls back to the US layout and ASCII only.

## Updates

The host checks the latest GitHub release of this repository at start and every 6 hours. Check for updates in the window runs it now.

- The Windows .exe replaces itself and restarts. Keep it in a folder you can write to, not Program Files.
- Source installs on Windows or Linux do not replace themselves. Update them with `git pull` and `pipx install --force ./host`.
- On every system, the host downloads new phone versions and serves them to the phone.
- Set `"auto_update": false` in the config to stop all checks. Set `"update_repo"` to use a fork.

### Private repository

GitHub hides releases of a private repository from anyone who isn't signed in. The host then needs a token:

1. On GitHub, open Settings, Developer settings, Personal access tokens, Fine-grained tokens, and click Generate new token.
2. Under Repository access, pick Only select repositories and choose Mobile-dock.
3. Under Permissions, set Contents to Read-only. Leave everything else at No access.
4. Copy the token. In the host window, click GitHub token and paste it.

The token is saved in the host config file. It can only read this one repository. On Linux you can set `MDOCK_GITHUB_TOKEN` instead.

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
| Files from the phone | `Downloads\Mobile Dock` | `~/Downloads/Mobile Dock` |

On Windows, pairing keys and the GitHub token in this file are encrypted with DPAPI, so they only open under your Windows account. On Linux the file is mode 600 and its folder is mode 700.

You can change `bind`, `port`, `discovery`, `screenshots_dir`, `recordings_dir` and `downloads_dir` in the file. Set `bind` to `127.0.0.1` to allow USB only. The host refuses connections from internet addresses. Set `allow_public_networks` to true only if you really need that.

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

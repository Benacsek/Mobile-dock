## Security

Mobile Dock lets a phone type and click on your PC. Treat a paired phone like a second keyboard and mouse. This file explains what protects that link and where the limits are.

## What is protected

| Threat | Protection |
| --- | --- |
| Someone on your Wi-Fi tries to pair | Pairing only works after you click Pair new phone. The PIN is random, single use, expires in 3 minutes and dies after 5 wrong guesses. |
| Someone records the pairing and guesses the PIN offline | SPAKE2 does not leak anything that allows offline guessing. Each guess needs a live attempt. |
| A fake PC on your network | On reconnect the PC must prove it holds the shared key. During pairing it must prove it knows the PIN. |
| Reading or changing traffic | AES-256-CBC with HMAC-SHA256, encrypt then MAC. Each direction has its own keys. |
| Replaying old messages | Each message carries an implicit sequence number in its MAC. A replay or a gap closes the connection. |
| Running commands on the PC | The phone sends app IDs only. The PC launches an ID only if it found that app itself. Shortcut tiles that open pages or start programs are written on the PC. The phone only picks a tile by its number, so it can never make the PC run something new. Web pages must start with http:// or https://. |
| A pairing link from a web page or another app | A mobiledock://pair link only works with a private network address, and the phone always asks before it pairs. The PIN in it is the same short-lived PIN as on screen. |
| Learning the PC's network card address | The address for Wake-on-LAN is sent only inside the encrypted session, so only paired phones learn it. Discovery replies never include it. |
| Brute force on reconnect | 8 failed attempts from one address in 5 minutes block that address for 5 minutes. |
| Files sent from the phone | Only paired phones can send. Up to 200 MB. The file name is cut down to a plain name, so it cannot point outside Downloads/Mobile Dock. A SHA-256 check rejects damaged files. |
| Oversized messages | Frames over 1 MiB close the connection. Text, mouse and scroll values are clamped. |
| A fake update | Updates come only from this repository's GitHub releases over HTTPS. latest.json is signed with an Ed25519 update key, and the PC app checks that signature with the public key built into it. latest.json holds the SHA-256 of every download, so even someone who took over the repository or the GitHub account could not get a fake .exe or APK installed. Android also refuses an APK signed with a different key. |
| Someone on the internet | The PC only accepts connections and discovery from your own network: LAN, USB, link-local and VPN ranges. Internet addresses are dropped before any data is read. |
| Using the PC to flood others | Discovery answers at most 5 times per second per address. |
| A stolen copy of the PC config | On Windows, pairing keys and the GitHub token are encrypted with DPAPI, tied to your Windows account. A copy is useless on another PC or account. On Linux the file is readable only by you. |
| A stolen copy of the phone's app data | On Android 6 and newer, pairing keys are encrypted with a key held in the Android Keystore, which never leaves the phone. |
| Another app on the phone | It cannot send files to your PC without your tap, and it cannot wake the dock or show it over the lock screen. |
| Stolen phone | Click Remove selected in the host window. The key stops working at once, and any open session closes. |

## Limits you should know

- The 6 digit PIN gives about 20 bits. That is enough against online guessing with the limits above. It is not a password.
- On Android 4.1 to 5.1 the pairing key sits unencrypted in app private storage, because those versions have no usable Keystore for this. A rooted phone can expose it.
- The host config file holds the PC side keys. On Linux it is mode 600. On Windows it lives in your user profile.
- Discovery answers anyone on the network with the PC name and port. Turn it off with `"discovery": false` in the config.
- The host listens on all interfaces by default. Set `"bind": "127.0.0.1"` to accept USB connections only.
- Anyone who unlocks your paired phone can control your PC while the host runs.
- With "Show the dock over the lock screen" on, the phone does not even need unlocking. It is off by default.
- Updates trust whoever can publish releases in this repository. Protect your GitHub account with two-factor authentication. Keep the APK signing key and its password only in the repository secrets.
- The Windows .exe is not code-signed, so SmartScreen warns on the first run.
- For a private repository, the host stores a GitHub token in its config file. Give it read-only access to Contents of this repository and nothing else. The token goes only to api.github.com. It is removed before GitHub redirects a download to its storage servers.

## Why custom crypto and not TLS

TLS on Android 4.x lacks modern cipher suites without extra libraries, and a self-signed certificate would still need a way to verify it. SPAKE2 with a PIN solves the trust problem directly. The building blocks are standard: RFC 3526 MODP group 14, SHA-256, HMAC, HKDF (RFC 5869) and AES. They exist on every Android version and in Python's `cryptography` package. The protocol is short and documented in [docs/PROTOCOL.md](docs/PROTOCOL.md). Tests check the Java and Python sides against each other.

On Android 4.1 to 4.3, `SecureRandom` had a seeding bug. The app reads `/dev/urandom` directly to avoid it.

## Supply chain

- The Android app has zero third-party dependencies.
- The host depends on `cryptography`, and `evdev` on Linux.
- GitHub Actions are pinned to commit hashes. Dependabot opens one grouped update PR per ecosystem each week.
- CodeQL scans the Python, Java and workflow code for security bugs on every push and every week.
- Every release carries a signed build attestation. It proves the file was built by this repository's workflow from a specific commit. See "Check a download" below.
- Workflows run with read-only tokens. Only the release job gets write access, and only for pushes to main.

## Before you make the repository public

The code and its history contain no passwords, tokens or keys. These steps are about your GitHub account and settings, which only you can change.

1. Turn on two-factor authentication for your GitHub account. Whoever controls the account can publish an update that every installed copy will install.
2. Under Settings, Emails, turn on "Keep my email addresses private" and "Block command line pushes that expose my email".
3. In the repository, under Settings, Code security, turn on private vulnerability reporting, Dependabot alerts, secret scanning and push protection.
4. Under Settings, Rules, add a ruleset for main that blocks force pushes and deletion.
5. Under Settings, Actions, General, keep "Require approval for all outside collaborators" and set workflow permissions to read only. The release job asks for write access itself.
6. Once the repository is public, the PC no longer needs a GitHub token. Revoke it on GitHub, then click GitHub token in the PC window and save it empty.

Repository secrets stay secret in a public repository. Pull requests from forks never receive them, and only pushes to main can publish releases.

## Update signing key

The private half of the update key is the `MDOCK_UPDATE_KEY` repository secret. The public half is `UPDATE_PUBLIC_KEY` in `host/mobiledock_host/updater.py`. The release job signs latest.json and refuses to publish if the secret is missing or does not match the public key.

Keep a backup of the private key file next to the Android keystore. If it is lost, make a new pair, put the new public key in `updater.py` and ship one release signed with the old key, so installed apps accept the new one.

## Check a download

With the GitHub CLI installed, run this in the folder with the downloaded file:

```bash
gh attestation verify MobileDockHost.exe --repo Benacsek/Mobile-dock
```

It works the same for the APK. A file that was changed after the build fails this check.

## Report a problem

Use "Report a vulnerability" on the repository's Security tab, or the private report link offered when you open a new issue. Please do not file a public issue for security bugs.

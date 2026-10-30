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
| Running commands on the PC | The phone sends app IDs only. The PC launches an ID only if it found that app itself. |
| Brute force on reconnect | 8 failed attempts from one address in 5 minutes block that address for 5 minutes. |
| Files sent from the phone | Only paired phones can send. Up to 200 MB. The file name is cut down to a plain name, so it cannot point outside Downloads/Mobile Dock. A SHA-256 check rejects damaged files. |
| Oversized messages | Frames over 1 MiB close the connection. Text, mouse and scroll values are clamped. |
| A fake update | Updates come only from this repository's GitHub releases over HTTPS. Every file must match the SHA-256 in that release's latest.json. Android refuses an APK signed with a different key. |
| Stolen phone | Click Remove selected in the host window. The key stops working at once, and any open session closes. |

## Limits you should know

- The 6 digit PIN gives about 20 bits. That is enough against online guessing with the limits above. It is not a password.
- The pairing key on the phone sits in app private storage. A rooted phone or a phone with an unlocked bootloader can expose it.
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
- GitHub Actions are pinned to commit hashes. Dependabot opens update PRs weekly.
- Workflows run with read-only tokens. Only the release job gets write access, and only for pushes to main.

## Report a problem

Open a private security advisory on GitHub for this repository. Please do not file a public issue for security bugs.

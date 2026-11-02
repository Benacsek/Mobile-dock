## Mobile Dock protocol, version 1

The phone is the client. The PC is the server. Both sides implement this file exactly:

- Python: `host/mobiledock_host/protocol.py` and `server.py`
- Java: `android/app/src/main/java/dev/mobiledock/proto/`

## Transport

- TCP port 47800. Every frame is a 4-byte big-endian length, then the payload. The length must be 1 to 1048576.
- Handshake frames are UTF-8 JSON objects.
- After the handshake, frames are encrypted. See Channel below.
- Errors during the handshake look like `{"t":"error","code":"bad_pin","message":"..."}`.

## Discovery

The phone sends `MDOCK1?` by UDP to port 47801 on the broadcast addresses. The PC answers:

```json
{"t":"mdock","v":1,"port":47800,"host_id":"...","host_name":"...","os":"windows"}
```

Discovery is a convenience. Nothing in it is trusted.

## Group and helpers

- p is the RFC 3526 2048-bit MODP prime. q = (p - 1) / 2. g = 2.
- M and N: for a label L, concatenate SHA-256(L || byte i) for i = 0 to 8, read it as a big-endian integer, reduce mod p, and square mod p. Labels are `MobileDock SPAKE2 M` and `MobileDock SPAKE2 N`.
- w = SHA-256("MobileDock PIN v1" || PIN as UTF-8), read as an integer, mod q.
- Elements are written as lowercase hex without padding in JSON. In transcripts they are 256-byte big-endian.
- lp(x) is a 4-byte big-endian length followed by x.
- HKDF is RFC 5869 with SHA-256. An empty salt means 32 zero bytes.
- A received element v is valid only if 1 < v < p - 1 and v^q mod p = 1.

## Pairing

1. Phone picks random x. It sends `{"v":1,"t":"pair","name":"...","X":hex}` where X = g^x * M^w mod p.
2. PC rejects the request unless the user started pairing. It picks random y and sends `{"t":"pair_r","Y":hex}` where Y = g^y * N^w mod p.
3. Both compute Z. The phone uses Z = (Y / N^w)^x. The PC uses Z = (X / M^w)^y.
4. K = SHA-256(lp("MobileDock-SPAKE2-v1") || lp(X) || lp(Y) || lp(Z) || lp(w)).
5. C = HKDF(K, empty, "mdock pair confirm v1", 64). The client confirmation is HMAC(C[0:32], "client"). The server confirmation is HMAC(C[32:64], "server").
6. Phone sends `{"t":"pair_c","c":hex}`. The PC checks it. On failure it counts a wrong guess and closes.
7. PC sends `{"t":"pair_ok","c":hex}`. The phone checks it before it trusts anything.
8. The session material is HKDF(K, empty, "mdock session v1", 128). The encrypted channel starts.
9. PC sends the first encrypted message: `{"t":"paired","device_id":"...","secret":hex32,"host_id":"...","host_name":"...","os":"...","version":"..."}`. The phone stores the secret under host_id.

## Reconnect

1. Phone sends `{"v":1,"t":"hello","nc":hex32}`.
2. PC sends `{"t":"challenge","ns":hex32,"host_id":"...","host_name":"...","os":"...","version":"..."}`.
3. Phone looks up the secret S for host_id. Let salt = nc || ns and D = device_id.
   - A = HKDF(S, salt, "mdock auth v1|" || D, 64)
   - session material = HKDF(S, salt, "mdock session v1|" || D, 128)
4. Phone sends `{"t":"auth","device_id":D,"mac":HMAC(A[0:32],"client")}`.
5. PC checks it and answers `{"t":"auth_ok","mac":HMAC(A[32:64],"server")}`. The phone checks that too.

## Channel

Session material is 128 bytes: client-to-server AES key, client-to-server MAC key, server-to-client AES key, server-to-client MAC key. 32 bytes each.

Each frame is IV (16 bytes) || AES-256-CBC ciphertext with PKCS#7 padding || tag (32 bytes). The tag is HMAC-SHA256(mac key, seq || IV || ciphertext), where seq is a 64-bit big-endian counter per direction that starts at 0. The receiver checks the tag in constant time before it decrypts.

## Commands

Requests are JSON with `t` and an optional numeric `id`. If `id` is present, the PC replies with `{"re":id,"ok":true,...}` or `{"re":id,"ok":false,"error":"..."}`.

| t | Fields | Reply |
| --- | --- | --- |
| ping | | recording |
| apps.pinned | | apps: [{id,name}] |
| apps.available | | apps: [{id,name}] |
| apps.pin | app | apps |
| apps.unpin | app | apps |
| apps.reorder | ids: [app ids] | apps |
| apps.launch | app | |
| apps.icon | app | png (base64, empty when there is none) |
| media | action: play_pause, next, prev, vol_up, vol_down, mute | title, artist, playing |
| media.status | | title, artist, album, playing, position, duration, shuffle, repeat, art_id, app |
| media.art | | art (base64 PNG or JPEG) |
| media.seek | position (seconds) | same as media.status |
| media.shuffle | | same as media.status |
| media.repeat | | same as media.status. Cycles off, all, one. |
| perf | | cpu, cores, cpu_name, cpu_mhz, ram_used, ram_total, gpu, gpu_name, vram_used, vram_total, gpu_temp, net_down, net_up, disk_read, disk_write (bytes per second), procs: [{name, cpu, mem}]. Missing keys are not available. |
| context | | app, title, pad |
| pads.list | | pads: [{id, name, tiles: [{label}]}], context |
| pad.run | pad, index | |
| file.begin | name, size, mode: clipboard or downloads | |
| file.chunk | data (base64, at most 256 KiB decoded) | got |
| file.end | sha256 | where: "clipboard" or the saved path |
| update.check | code (installed versionCode) | available, version, code, size, sha256 |
| update.chunk | offset | data (base64, up to 256 KiB), total |
| update.status | | pc_version, status, phone_ready (phone version kept on the PC, or empty) |
| update.refresh | | same as update.status, after a fresh check on GitHub |
| system | action: lock, sleep, display_off, desktop, task_manager | |
| mixer.list | | apps: [{id, name, volume 0 to 100, muted}] |
| mixer.set | id, volume (0 to 100) and/or muted | |
| clipboard.set_text | text (at most 100,000 characters) | |
| clipboard.get_text | | text |
| host.info | | mac, the PC's network card address for Wake-on-LAN |
| screenshot | | path |
| record.start | | recording |
| record.stop | | recording, path |
| input.text | text (max 2000 chars) | |
| input.key | key, mods: [ctrl, alt, shift, meta] | |
| mouse.move | dx, dy (clamped to 2000) | |
| mouse.button | button: left, right, middle. action: click, down, up | |
| mouse.scroll | dx, dy. One unit is a third of a wheel notch. Positive dy scrolls up. | |

Mouse and keyboard messages are usually sent without `id`, so the PC does not reply.

`system` has no shutdown or restart on purpose. A phone left on a desk should not be able to end your work.

The PC may also send events on its own:

- `{"t":"event","e":"apps"}`: pinned apps changed. The phone reloads them.
- `{"t":"event","e":"pads"}`: shortcut pads changed.
- `{"t":"event","e":"context","app":"Figma.exe","title":"...","pad":"figma"}`: another app came to the front.

Discovery replies and challenges also carry `boot`, a random id that changes each time the host starts. The phone's watcher uses it to notice that the PC app restarted.

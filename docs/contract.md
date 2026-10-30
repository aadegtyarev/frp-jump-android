# frp-jump for Android — contract

An Android app that is a frp-jump device of its own: it lists the owner's other
devices and opens their web UI or an SSH console through frp-jump tunnels. It
only consumes ports; it never exposes anything of the phone.

Status: draft for the owner's review. Nothing is built yet.

## Scope

- One user: the owner. APK installed by hand, no Google Play. Android 10+ (API 29), arm64.
- Works against the existing frp-jump server, wire protocol version 1, frp 0.70.0.
  No server changes.
- All devices reached are the owner's own (the server only allows that).

Out of scope for v1: exposing phone ports, other people's devices, key-based
enrollment, admin commands, Google Play, x86/armv7 builds.

## Scenarios

### 1. First run: enroll the phone

1. On a laptop: `frp-jump-client devices add-token --name phone`.
2. In the app: paste the line the server or client printed as is,
   `frp-jump-client enroll https://tunnel.example.com <token> [--name phone]`.
   The app takes the URL (`https://` only), the token and the optional name
   from it; a bare `<url> <token>` works too. Later: scan a QR code of the
   same line.
3. App calls `POST /api/agent/enroll`, stores the device token, certificate, key
   and CA in app-private storage encrypted with an Android Keystore key.
4. App opens the device list.

Errors shown as they are: bad or used token, unreachable server, protocol
version mismatch ("update the app" or "update the server").

### 2. Device list

- `GET /api/agent/devices`: name, online (last seen < 30 s), last seen time,
  disabled flag. The phone itself is not listed.
- Pull to refresh; refreshed every poll while the app is open.
- Tapping a device opens its card.

### 3. Device card

- Ports remembered for this device: presets "Web" (80) and "SSH" (22), plus
  ports the owner adds (port, name, kind: web / ssh / tcp).
- Each port: Connect / Disconnect, state (connecting, direct, relayed, failed),
  the local address `127.0.0.1:<port>`.
- Web → in-app WebView on `http://127.0.0.1:<port>`; button "open in browser".
- SSH → built-in terminal (scenario 4); fallback button "open in external app"
  (`ssh://user@127.0.0.1:<port>` intent for Termius / JuiceSSH).
- TCP → shows and copies the local address.

### 4. SSH console

- Built-in terminal: login by user + key or password.
- The app generates one ed25519 key; its public half can be copied or shared.
  Optionally import a private key from a file.
- Host keys pinned per device+port on first use; a changed key blocks the
  connection with a warning.
- Passwords are not stored unless the owner ticks "remember" (Keystore-encrypted).
- Several sessions at once, switchable.

### 5. Connect / disconnect

- Connect: `POST /api/agent/connect {device_name, target_port, protocol}`,
  then poll `desired-state` until the grant appears, write frpc config, start or
  reload frpc. The local port is picked by the app from its range and kept per
  device+port, so bookmarks stay valid.
- Disconnect: `POST /api/agent/disconnect`; the exposing side tears its end down
  on its next poll.
- "Disconnect all" in the app and in the notification.

### 6. Background

- While the app is in the foreground: poll every 2 s (heartbeat + desired-state).
- While any tunnel is up: a foreground service keeps frpc alive and shows a
  notification ("N tunnels", "Disconnect all"). This does not depend on the
  background-poll setting.
- Background poll setting: off (default) / 30 s / 1 min / 5 min / 15 min.
  Off: no polling with the app closed; tunnels already up keep running, but
  changes made elsewhere are picked up only when the app is opened.
- No tunnels and background poll off: nothing runs when the app is closed.

### 7. Settings

- Server URL, device name (read-only), background poll interval, local port range.
- "Forget this phone": disconnects everything, calls
  `POST /api/agent/devices/<own name>/delete`, then wipes local credentials.
  Works offline too: then only the local wipe, and the owner deletes the device
  from a laptop.

## Wire behaviour the app must match

- Bearer token on every `/api/agent/*` call; `protocol_version` checked on every
  response, a mismatch stops applying changes but leaves running tunnels alone.
- frpc config as the Linux agent builds it (`driver/frp/config.py`): TLS block
  with the device cert/key/CA, `metadatas.frp_jump_device` / `frp_jump_token`,
  heartbeat 5 s / timeout 30 s; per consumed grant an `stcp` visitor plus an
  `xtcp` visitor with `fallbackTo` the stcp one, both `useEncryption`, bound on
  127.0.0.1 only. A "relay only" switch in settings (like `set-p2p`) keeps just
  the stcp visitor.
- frpc is the upstream 0.70.0 built for android/arm64 and shipped inside the APK.

## Stored on the phone

| What | Where |
| --- | --- |
| Device token, cert, key, CA | app-private files, encrypted with a Keystore key |
| SSH private key, remembered passwords | same |
| Server URL, device list cache, ports per device, local port map, host keys, settings | app-private database |

Nothing leaves the phone except calls to the frp-jump server and the tunnels.

## Acceptance

- Enroll on ikar with a fresh token; the phone appears in `devices list`.
- From mobile data: open homeui of a WB controller (port 80) and an SSH shell
  (port 22) on two different devices at the same time.
- Relay fallback works when P2P fails (mobile NAT); the card says which one is used.
- Close the app with tunnels up: they keep working, the notification is there;
  "Disconnect all" removes them server-side.
- Background off, no tunnels: no network activity from the app when closed.

## Open risks

- frpc on Android: Go's resolver without `/etc/resolv.conf`; build with the NDK
  (cgo) or use an IP for the relay. To be proven first.
- Android may still kill the foreground service on aggressive vendor firmware;
  the owner may need to exempt the app from battery optimisation.
- Mobile carriers' NAT usually blocks P2P, so most traffic will go via the relay.

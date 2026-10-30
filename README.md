# frp-jump for Android

An Android client for [frp-jump](https://github.com/aadegtyarev/frp-jump): the
phone enrolls as a frp-jump device of its own, lists the owner's other devices
and (in later stages) opens their web UI or an SSH console through frp-jump
tunnels. It only consumes ports; it never exposes anything of the phone.

Behaviour is defined in [docs/contract.md](docs/contract.md).

Current stage: enrollment, the device list, tunnels from the device card, the
in-app web view, the SSH terminal and settings (relay only, local ports,
background checks, battery optimisation, SSH keys, "Forget this phone").

## How tunnels work

- The card lists Web (80), SSH (22) and ports the owner adds (port, name, kind).
  Connect calls `POST /api/agent/connect` (`ssh` for kind ssh or ports 22/2222,
  `tcp` otherwise, as the Linux client classifies them), then polls
  desired state every second for up to 15 s until the grant shows up.
- Desired state is the only source of truth: frpc runs one stcp + xtcp
  visitor pair per consumed grant, laid out as the Linux agent's
  `driver/frp/config.py` does, except frpc logs at `info` so the app can tell
  connecting / ready / direct / relayed / failed from its output.
- Local ports come from 40000-40999 and stay with (device, port), so
  `127.0.0.1:<port>` bookmarks keep working.
- frpc runs from `nativeLibraryDir` as a child process; its config, cert, key
  and CA are written 0600 into `no_backup/frpc/`. The key and the config are
  deleted whenever frpc stops or exits by itself, and on every app start (a
  killed process never got to delete them); "Forget this phone" deletes the
  whole directory.
- While at least one tunnel is up a foreground service (type `specialUse`)
  keeps the process alive and shows "N tunnels" with "Disconnect all".

## Polling and background

One loop in the app polls: heartbeat + desired state, plus the device list
while it is on screen.

| App | Tunnels | Polls |
| --- | --- | --- |
| visible | any | every 2 s |
| closed | up | at Settings > "Check the server in the background": Off (default), 30 s, 1 min, 5 min, 15 min |
| closed | none | never |

The phone exposes nothing, so a background check only keeps it shown
online (`last_seen`) and tears down tunnels disconnected elsewhere or of a
device disabled meanwhile. With no tunnel up there is nothing to tear down,
so the app does not run in the background at all, whatever the setting;
that is also why there is no WorkManager job for the 15 min option. Off
keeps tunnels up without checking; changes made elsewhere apply when the
app is opened.

Android may still stop a background app (Doze, vendor firmware). Settings
shows whether the app is exempt from battery optimisation, with a button
to the system dialog (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, fine for a
sideloaded app).

## Settings

- Relay only: one `stcp` visitor per tunnel bound directly, as
  `frp-jump-client set-p2p off`; tunnels show "Relayed". Changing it
  restarts frpc.
- Local ports: a range within 1024-65535 of at least 10 ports, 40000-40999
  by default. Existing mappings stay unless they fall outside a new range.
- Settings are kept in `settings.json`; "Forget this phone" keeps them.

## Web ports

"Open" on a connected web port shows `http://127.0.0.1:<local port>/` in an
in-app WebView: JavaScript and DOM storage on, cookies kept, Back walks the
page history, the top bar has the title, the URL, Reload and "Open in
browser". Only loopback pages stay in the app; links to other hosts (and
`mailto:`, `tel:`, …) go to other apps. Downloads go to the system
DownloadManager with the page's cookies; files a page makes itself (`blob:`)
cannot be handed over, use "Open in browser" for those. WebSockets to the
same origin work, so homeui's MQTT over WS shows live values.

## SSH console

"Terminal" on a connected SSH port asks for the user (remembered per device
and port, `root` by default) and Key or Password, then opens a session to
`127.0.0.1:<local port>`.

- Key: the app makes one ed25519 key on first use; Settings shows its
  `authorized_keys` line with Copy / Share. "Import private key" takes an
  OpenSSH (or PEM) private key file and its passphrase; it is tried before
  the app's key. Private keys, passphrases and remembered passwords are
  sealed with a Keystore AES-GCM key of their own (`frpjump-ssh`), so
  re-enrolling the phone keeps them; "Forget this phone" wipes them.
- Password: kept only when "Remember on this phone" is ticked; a remembered
  password that is refused is dropped.
- Host keys are pinned per (device name, target port) on first use, shown as
  OpenSSH `SHA256:` fingerprints. A different key blocks the connection and
  shows both fingerprints; "Forget the old key" drops the pin and asks again.
- The terminal requests `xterm-256color` and sends `window-change` when the
  view or the font size changes. Below it: Esc, Tab, sticky Ctrl / Alt,
  arrows, `| - / ~`. Long-press selects text (Copy / Paste), pinch or A− / A+
  changes the font size.
- Sessions live in the app, not in the screen: several at once, as tabs;
  "N terminals" on the device list leads back. A session ends when its
  tunnel goes (Disconnect, Disconnect all, Forget); "Reconnect" starts a new
  one with the same login.
- "Open in external app" sends `ssh://<user>@127.0.0.1:<port>` to an SSH app
  (Termius, JuiceSSH, …).

Connecting or disconnecting another port does not restart frpc: the app
rewrites `frpc.toml` and calls frpc's admin API (`GET /api/reload`, on
127.0.0.1 with a random port, user and password made for each frpc run),
and frpc swaps only the visitors that changed. Open SSH sessions and web
sockets of the other tunnels stay up. frpc is restarted (dropping them)
only when the reload fails or something frpc reads only at start changes:
the server, the device's TLS material or token, or "Relay only".

The other end still restarts: a connect or disconnect changes what the
target device exposes, and its Linux agent restarts its own frpc on any
config change. So a port connected or disconnected on the same device
drops that device's other open sessions (seen on a WB controller: its frpc
restarted 2 s after the phone connected port 80). Tunnels to other devices
and phone-only changes (a moved local port) keep their connections.

## Third-party libraries

| Library | Version | License |
| --- | --- | --- |
| [sshj](https://github.com/hierynomus/sshj) | 0.41.2 | Apache-2.0 |
| [BouncyCastle](https://www.bouncycastle.org/) bcprov / bcpkix (via sshj) | 1.84 | MIT (Bouncy Castle licence) |
| [Termux](https://github.com/termux/termux-app) terminal-emulator / terminal-view (JitPack) | v0.118.3 | Apache-2.0 |
| slf4j-api, asn-one (via sshj) | 2.0.17, 0.6.0 | MIT, Apache-2.0 |

termux-app as a whole is GPLv3, but its LICENSE.md lists terminal-emulator
and terminal-view as Apache-2.0 (derived from Jack Palevich's Terminal
Emulator for Android); only those two modules are used. The app drives
Termux's `TerminalSession` from the SSH stream instead of a local pty
through package-private members (`com.termux.terminal.RemoteTerminal`);
re-check that class when bumping the Termux version.

Android's built-in "BC" provider lacks ed25519 and curve25519, so the app
replaces it with the full BouncyCastle provider at startup (appended, so TLS
and the Keystore keep their providers).

## Build

Needs the Android SDK (platform 35, NDK 26.3 for frpc), a JDK 17+ with `javac`,
and Docker for the frpc build.

```sh
scripts/build-frpc.sh        # upstream frpc 0.70.0 -> app/src/main/jniLibs/<abi>/libfrpc.so
./gradlew testDebugUnitTest assembleDebug
```

The APK builds without `libfrpc.so` too, but tunnels then cannot start.
If Gradle reports that the toolchain has no `JAVA_COMPILER`, point `JAVA_HOME`
at a full JDK, e.g. `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Enroll

On a laptop that is already enrolled:

```sh
frp-jump-client devices add-token --name phone
```

Paste the printed `frp-jump-client enroll https://… <token> --name phone` line
into the app as is; line breaks from a narrow terminal are fine. A bare
`<url> <token>` works too. The token is single-use.

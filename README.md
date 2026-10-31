# frp-jump for Android

An Android client for [frp-jump](https://github.com/aadegtyarev/frp-jump): the
phone enrolls as a frp-jump device of its own, lists the owner's other devices
and (in later stages) opens their web UI or an SSH console through frp-jump
tunnels. It only consumes ports; it never exposes anything of the phone.

Behaviour is defined in [docs/contract.md](docs/contract.md).

Current stage: profiles (switcher, list, add, name and colour, forget), the
device list, named connections on the device card and in the Connections
tab, direct or through-the-server tunnels with the "Direct didn't work"
notification, the in-app web view (HTTP and HTTPS), the SSH terminal and
settings (direct connection, local ports, background checks, battery
optimisation, SSH keys), and tunnels coming back after the process is
killed or the phone reboots, and enrolling by scanning the laptop's QR code. The app has its own light and dark colour scheme
(`ui/Palette.kt`), following the system or set by hand, speaks Russian
and English, can ask for the fingerprint or screen lock when opened, and
the terminal has saved commands (snippets).

## Profiles

A profile is one enrollment: a server, a device name, its token,
certificate and key. Each profile has a random id; `profiles.json` in
filesDir lists them in order with the current one (the one the app shows),
and each profile's own files live in `profiles/<id>/`: the enrollment
(sealed with a Keystore key of its own, `frpjump-profile-<id>`), the cached
device list, connections, SSH logins, pinned host keys and confirmed HTTPS
certificates. Remembered SSH
passwords are kept per profile too, in the shared, sealed `ssh_passwords.json`. Every profile
runs its own poller and, while it has tunnels, its own frpc
(`no_backup/frpc/<id>/`), so tunnels of several profiles are up at once.
Shared by all profiles: settings, the local port map (one range, keyed by
profile, device and port), the web view's last site and the SSH key.

The switcher at the top of the device list opens the profile list; tapping a
profile shows it (its cached list at once). "Add profile" enrolls a new one
and shows it. "Forget profile" (Settings, or "⋮" in the profile list)
forgets a profile: its tunnels go, the server is told to delete it (offline:
on the phone only, and a notice says what to run on a laptop), its directory
and Keystore key are wiped, and the next profile is shown, or enrollment with
none left. A profile its server deleted (401) while another one is shown is
marked in the list and named in a line above the devices. Forgetting the
last profile also wipes the SSH key and passwords, as before, before the
index drops it. Profile directories the index does not list (an add or a
forget cut short) are deleted with their keys on the next start.

The magnifier next to the switcher searches the device list: the bar
becomes a field (focused, keyboard up) and the list keeps the devices whose
name contains what is typed, ignoring case (`devices/DeviceSearch.kt`;
names only, as connection names like "Web" or "SSH" repeat on most
devices). The cached list offline filters the same. Nothing found says
"No devices match …"; ✕ or Back closes the search and shows them all.

Upgrading from 0.1.x makes the single enrollment the first profile on the
first start, without re-enrolling: v1's files move into the profile's
directory (each by an atomic rename, the enrollment keeps v1's Keystore key
`frpjump-credentials`), the shared maps' keys get the profile's id, and the
profile is listed. A marker (`profiles_migration.json`) holding the id is
written first and removed last, so an upgrade interrupted at any point is
finished on the next start.

## Connections

A connection is a device, a port on it, a kind (web, SSH or TCP) and a
name; web adds an HTTPS flag (only on ports other than 80, which is always
HTTP, and 443, always HTTPS), SSH the user the login dialog starts with.
They are kept per profile in `profiles/<id>/connections.json`, in the order
they were added. Several connections may name the same device and port
(SSH as root and as another user): they share one tunnel and one local
port, which stay keyed by (profile, device, port).

- The device card lists the device's connections with their tunnel's state,
  local address, a Disconnect (power) button and the main action (Open,
  Terminal, Copy); "⋮" or a long press has Copy address, Open in browser /
  external app, Files (SSH, see [Files](#files)), Change and Delete. "Connection" opens a sheet: kind, port (presets per
  kind, any 1-65535), name (blank takes the preset's name, the SSH user or
  "Web 8080"), HTTPS or user, "Connect now". Names must differ per device
  and port, and SSH cannot share a port with web or TCP (the server knows a
  port under one protocol). The kind and port of a connected connection
  cannot be changed; moving or deleting the last connection of a port
  disconnects its tunnel.
- The Connections tab lists every profile's connections, marked with the
  profile's colour: active ones first with state, local address,
  Disconnect and the main action, the rest with a connect button, "⋮" on
  each saved one; "Disconnect all" stops every tunnel. Tunnels up without a
  saved connection are listed too, as "Port N", so they can be
  disconnected.
- Disconnect, "Disconnect all" and deleting the last connection of a
  tunnel that is up ask first when something open goes with the tunnel:
  live terminal sessions on it (same profile, device and port; for "all",
  any) — "N terminal sessions will close" — and open Files screens.
  With nothing open they disconnect at once.
- The bottom navigation: Devices, Connections, Terminals (the open SSH
  sessions), Settings.

Upgrading from 0.2.0-beta1 (or 0.1.x) turns each profile's ports into
connections on its first start: every port the owner added keeps its
label, kind and number (on 80 and 22 too); the old presets Web 80 and SSH 22 become
connections only on devices where they were used (the local port map has
an entry for them), with the SSH user last logged in as. Local ports,
logins, passwords, host keys and the web view's site memory are untouched,
since tunnels keep their keys. `connections.json` is written in one atomic
write before `device_ports.json` is deleted, so a crash at any point is
finished, or redone, on the next start. A `device_ports.json` that does
not parse is left in place and nothing is migrated from it; a
`connections.json` that does not parse is copied to
`connections.corrupt.json` before anything is saved over it.

## How tunnels work

- Connect calls `POST /api/agent/connect` (`ssh` for kind ssh or ports 22/2222,
  `tcp` otherwise, as the Linux client classifies them), then polls
  desired state every second for up to 15 s until the grant shows up.
- Desired state is the only source of truth: frpc runs one stcp + xtcp
  visitor pair per consumed grant (a single stcp one when direct is off
  for it), laid out as the Linux agent's `driver/frp/config.py` does,
  except frpc logs at `info` so the app can tell connecting / trying
  direct / direct / through the server / failed from its output, and the
  xtcp visitor keeps its tunnel open (see below).
- Local ports come from 40000-40999 and stay with (profile, device, port),
  so `127.0.0.1:<port>` bookmarks keep working and two profiles never share
  a port. When another app holds a tunnel's port and frpc reports "address
  already in use", the tunnel moves to a free port and frpc reloads (up to
  three times in a row per tunnel).
- frpc runs from `nativeLibraryDir` as a child process, one per profile with
  tunnels; its config, cert, key and CA are written 0600 into
  `no_backup/frpc/<profile id>/`. The key and the config are
  deleted whenever frpc stops or exits by itself, and on every app start (a
  killed process never got to delete them); "Forget this phone" deletes the
  profile's directory.
- While at least one tunnel of any profile is up a foreground service (type
  `specialUse`) keeps the process alive and shows "N tunnels" (all profiles),
  "· M terminal sessions" when any is open, with "Disconnect all" (all
  profiles), which cannot ask first: the title is the warning.

## Restore after kill and reboot

Tunnels that were up come back by themselves, in every profile and on the
same local ports, when Android kills the process or the phone reboots.

- The server keeps the grants (desired state) and the local port map keeps
  their ports; the app only records which profiles had tunnels up, and how
  many: `restore.json` in filesDir, profile id to count, written atomically
  whenever the count changes (`restore/RestoreRecord.kt`). A profile leaves
  it when it has no tunnel left: "Disconnect all", its last tunnel
  disconnected here or elsewhere, "Forget profile", its server removing it.
  A failed poll or a Keystore not readable yet keeps it.
- A cold start reads the record before any profile polls. A profile in it
  with no tunnel running polls every 5 s (10 s, 30 s after failures) until
  its tunnels run, whichever profile is shown and whatever the background
  setting; then it polls as usual (below).
- Process killed: the tunnels service is sticky, so Android starts it again
  (after 1 s, then longer when it keeps being killed) and the app's start
  brings the tunnels back. Until they run the notification and the
  Connections tab say "Restoring N tunnels…" ("Restoring tunnels…" before
  the record is read), with "Waiting for network…" or "Waiting for
  unlock…" when that is what holds them. "Disconnect all" there also
  disconnects tunnels not yet back (it asks the server for them); when a
  server cannot be told, frpc stops anyway but the record stays, and "Couldn't
  reach the server. Tunnels may come back." is shown where it was tapped.
  Profiles that cannot be read on a cold start are tried again (10 s, 30 s,
  then every minute, five times).
- A frpc that outlived a killed process would hold the local ports: its pid
  is kept in `frpc.pid` next to its config, and the next start kills it
  first, if `/proc/<pid>/cmdline` is still that frpc on that config.
- Reboot: `restore/BootReceiver.kt` takes `BOOT_COMPLETED` and starts the
  service when the record lists tunnels. Android 12+ lets an app start a
  foreground service from `BOOT_COMPLETED`; Android 15 bars only `dataSync`,
  `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` and
  `microphone` from it, not `specialUse`. Not `LOCKED_BOOT_COMPLETED`: the
  credentials are in credential-encrypted storage under Keystore keys, so
  nothing can start before the first unlock.
- When Android does not let the service come to the foreground from the
  background (12+, the app not exempt from battery optimisation, or
  restricted in the background), a "Tap to restore N tunnels" notification
  opens the app, which starts the service. Exempt from battery
  optimisation, the app may always start it; Settings shows "Battery ·
  Android may stop tunnels · Fix" while it is not.
- After a restore the direct-or-server logic runs as on a connect: "Trying
  direct…", "Direct" or "Through the server". "Direct didn't work" is
  offered once per device and network: the offers made are kept in
  `direct_offered.json`, so tunnels brought back that fail the same way
  do not offer it again. A device is offered again after all its tunnels
  went direct there, its tunnels are gone, or "Retry direct".
- With notifications not allowed (13+) none of these notices can show:
  the device list says so in one line, with "Allow".
- Force stop in the system settings disables all of this until the app is
  opened again (Android's rule); the tunnels then come back on opening.

## Polling and background

Each profile has one poll loop: heartbeat + desired state, plus the device
list while it is on screen.

| Profile | App | Tunnels | Polls |
| --- | --- | --- | --- |
| current | visible | any | every 2 s |
| current | closed | up | at Settings > "Check the server in the background": Off (default), 30 s, 1 min, 5 min, 15 min |
| other | any | up | at the same background setting |
| any | any | none yet, recorded up before a kill or reboot | every 5 s until they run |
| any | closed (or other) | none | never |

The phone exposes nothing, so a background check only keeps it shown
online (`last_seen`) and tears down tunnels disconnected elsewhere or of a
device disabled meanwhile. With no tunnel up there is nothing to tear down,
so the app does not run in the background at all, whatever the setting;
that is also why there is no WorkManager job for the 15 min option. Off
keeps tunnels up without checking; changes made elsewhere apply when the
app is opened.

Android may still stop a background app (Doze, vendor firmware). While
the app is not exempt from battery optimisation Settings shows "Battery ·
Android may stop tunnels" with "Fix", which opens the system dialog
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, fine for a sideloaded app); the
row goes once the exemption is granted.

## Direct or through the server

With "Direct connection (P2P)" on (the default) every tunnel has an xtcp
visitor that punches a peer-to-peer path and hands each connection to the
stcp visitor (through the server) when there is none within 500 ms, so a
connection never waits for the outcome.

- Connecting shows "Trying direct…" for up to 10 s, then "Direct" or
  "Through the server". The xtcp visitor has `keepTunnelOpen`, so it
  punches as soon as it starts instead of on the first connection.
- frpc's log is the only source of the outcome (its admin API reports
  proxies, not visitors), read by `tunnel/FrpcLogParser.kt` per visitor
  name: `establishing nat hole connection successful` is a punched hole,
  but frpc then dials QUIC over it, which can still fail (`init tunnel
  session error`, seen behind the emulator's NAT 5 s later), so the tunnel
  is "Direct" when that error has not come in 6 s. `nathole prepare /
  exchange info error`, `make hole error`, `init tunnel session error` or
  no success in 10 s: "Through the server". `nathole precheck error` comes
  on every new connection, while the device's frpc restarts for the new
  grant: the app tries again 2 s later within the same 10 s.
- A direct tunnel that dies (its periodic check or a connection logs `get
  tunnel connection error` / `open tunnel error`) is "Trying direct…"
  again while frpc punches anew, with the same outcomes.
- Through the server after trying, for 3 s (a punch confirmed late
  would otherwise flash it): one "Direct didn't work: <names>"
  notification per device, naming its failed connections (own channel
  "Direct connection", apart from the tunnels' one), with "Retry direct"
  and "Keep through the server"; tapping it opens the device card. The
  same two answers sit behind the "Through the server ›" state on the card
  and in the Connections tab; a device kept through the server shows
  "Kept through the server" (neutral), and tapping it offers "Retry
  direct". The actions run in the app process, which the foreground
  service keeps alive, so they work with the app closed; notifications a
  dead process left behind are removed when it starts again.
- "Retry direct" acts on the whole device: each of its tunnels that is not
  direct gets its xtcp visitor renamed (`<grant>-xtcp-visitor-N`) and
  frpc reloads: only those visitors restart and punch again; connections
  already going through the server stay (frpc closes only a visitor's
  listeners, not the connections it handed to the stcp visitor), and
  other tunnels are untouched. A tunnel turning direct again (kept or off
  until then) gets a new name too, so late lines of its old visitor
  cannot count for the new attempt.
- "Keep through the server" switches the device's tunnels to a single
  stcp visitor on the current network (by reload, same as above): no more
  attempts or notifications there until "Retry direct" from the card. The
  choices are kept in `direct_kept.json` per profile, device and network.
  The network is recognised without the location permission the Wi-Fi
  name needs: mobile data is one network; any other is its kind plus a
  hash of its gateways (an IPv6 router's link-local address usually
  carries its MAC), DNS servers, search domains and IPv4 subnets
  (`direct/NetworkIdentity.kt`). Two plain Wi-Fis with identical settings
  look the same. A new network counts once it has held for 1 s (a switch
  reports passing states), and losing the network keeps the last one, so
  a kept device does not turn direct meanwhile. Keeping needs a network to
  tie the choice to; without one the app says so and changes nothing.
- While frpc stays through the server after a failure it retries by itself
  quietly (below); a success turns the tunnel "Direct" without a word.

Keepalive (frp 0.70, `client/visitor/xtcp.go`, `pkg/config/v1`): the xtcp
visitor sets `keepTunnelOpen = true`, `minRetryInterval = 30` (every 30 s
it opens a stream on the tunnel to check it and punches again if it is
dead, upstream default 90 s) and `maxRetriesAnHour = 8` (upstream default:
after a burst of 8, one failed attempt per 7.5 min, so a network that
never punches costs little). `[transport.quic]` is written out as
`keepalivePeriod = 10`, `maxIdleTimeout = 30` (upstream defaults): a PING
every 10 s keeps an idle tunnel's NAT mapping open, and a silent peer ends
the tunnel within 30 s for the check to notice.

Over a direct tunnel the device sees a connection only once the phone's
side sends its first bytes (QUIC opens the stream lazily): fine for HTTP
and SSH clients, which speak first or at once; a protocol where the
server must speak first to an idle client would wait.

## Settings

Grouped as the mockup: the current profile (name and colour, forget),
tunnels (direct connection, local ports), background (server checks,
battery), security (the phone's SSH key, unlock with fingerprint, hide in Recents) and the
app (language, theme, version). Rows show their value; local ports, the
check interval, the SSH key, language and theme open a sheet.

- Direct connection (P2P): see above; off is one `stcp` visitor per tunnel
  bound directly, as `frp-jump-client set-p2p off`, with no attempts and
  no notifications. Switching it reloads frpc (connections through the
  server stay; direct ones end). A `relay_only` saved by 0.2.0-beta1 and
  earlier is read as the opposite of this switch.
- Local ports: a range within 1024-65535 of at least 10 ports, 40000-40999
  by default. Existing mappings stay unless they fall outside a new range.
- Language: System (default), Русский, English, applied at once. It is
  Android's per-app language (`AppCompatDelegate.setApplicationLocales`):
  on Android 13+ the system keeps it, and it also shows under the app in
  the system's app language settings (`res/xml/locales_config.xml`); on
  Android 10-12 AppCompat stores it (`autoStoreLocales`) and applies it to
  the activity, and `settings.json` keeps a copy that is applied at process
  start, so notifications of a process started in the background (restore
  after a reboot) speak it too. Code without a screen takes its texts from
  `locale/Texts.kt`, in the app's language.
- Connections without a name of their own show a default made at display
  time in the current language: the kind, and the port unless it is the
  kind's own ("Web" / "Веб" on 80, "Web HTTPS" on 443, "SSH" on 22, "Веб
  8080", "TCP 1883"). A preset chip in the sheet (Grafana, MQTT, ...) fills
  in its name, which the owner may change. Names the app made up from the
  kind and port up to 0.2.0-beta1 ("Web", "SSH", "Port 8080", "Web 8080")
  are read as "no name of its own" when they match exactly
  (`connections/ConnectionMigration.kt`, `readConnections`), unless another
  connection to the same port already shows that default; all other names
  stay as they are.
- `connections.json` is written in the new format (`custom_name`) on the
  first change. A build older than this one cannot read it: going back to
  one loses the list of connections (the file is kept aside as
  `connections.corrupt.json`, nothing else is lost).
- Theme: System (default), Light, Dark, applied at once and kept in
  `settings.json` (`theme`; an older file without it, or a value a newer
  build wrote, reads as System). Compose takes the palette from it, and
  `AppCompatDelegate.setDefaultNightMode` gives the same to the window, the
  system's dialogs and the web view; AppCompat keeps that per process only,
  so the app sets it again at every start (`ui/Theme.kt`). The terminal's
  bars stay dark in both themes.
- Unlock with fingerprint: off by default. Turning it on asks for the
  phone's fingerprint or screen lock first (BiometricPrompt,
  `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`; Android 10 does not take that
  pair, so there it is `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`). A phone
  without a screen lock says so and the switch stays off. When on, the app
  shows a lock screen and asks again on a cold start and when it comes
  back after more than 30 s out of sight (`lock/LockPolicy.kt`); a
  cancelled prompt leaves the lock screen with "Unlock". Only the screen is
  locked: tunnels, the service and the notification's "Disconnect all" go
  on, and the screens stay underneath (an open web page or terminal is
  where it was) but take no input: the focused view lets go, the keyboard
  is hidden and keys other than Back and volume are dropped. A prompt that
  ends while the app is away (the owner left with it up) counts the
  absence from then, so the 30 s rule still holds. If the screen lock is
  removed later, the next prompt turns the setting off rather than lock
  the owner out, and says so.
- Hide in Recents (off by default, apart from the lock): Recents shows no
  picture of the app, `setRecentsScreenshotEnabled(false)` on Android 13+
  and `FLAG_SECURE` before that. `FLAG_SECURE` blocks screenshots, and so
  does the Android 13+ call on some phones; with it off, screenshots work.
- Version: the app's and frpc's, and updates (see [Updates](#updates)).
- Settings are kept in `settings.json`; "Forget this phone" keeps them.

## Web ports

"Open" on a connected web connection shows `http://127.0.0.1:<local port>/`
(`https://` on 443 or with the HTTPS flag) in an in-app WebView. An HTTPS
certificate (self-signed or not, it never matches 127.0.0.1) is shown with its SHA-256
fingerprint and accepted only once the owner trusts it; it is then pinned
per (profile, device, target port) in `web_certs.json`. A different
certificate later asks again, showing both fingerprints. Files of an HTTPS
connection are not handed to the system downloader, which would not trust
the pinned certificate; the app says so.

When an HTTPS page gets a plain HTTP answer (`ERR_SSL_PROTOCOL_ERROR`, and
no certificate question or refusal in that load), the screen says the
device answers this port over HTTP and offers "Open over HTTP": it opens
http and clears the HTTPS flag of the profile's web connections to that
device and port. On 443, which always opens over HTTPS, it opens http for
this time only and says so. A device that closes the connection on a TLS
handshake instead of answering in HTTP gets WebView's own error page.

The web view: JavaScript and DOM storage on, cookies kept, Back walks the
page history. The top bar is a thin one (44 dp) with the page's title,
"Full screen" and "⋮" (the address, which a tap copies; Reload; "Open in
browser"). Full screen hides the bar and the system bars; a small round
button, which can be dragged aside, and Back leave it. The choice is kept
for the next page. Only loopback pages stay in the app; links to other hosts (and
`mailto:`, `tel:`, …) go to other apps. Downloads go to the system
DownloadManager with the page's cookies; files a page makes itself (`blob:`)
cannot be handed over, use "Open in browser" for those. WebSockets to the
same origin work, so homeui's MQTT over WS shows live values.

Leaving the web view (Back) closes it, but the app remembers, in memory
for as long as it runs, the last page of each tunnel (profile, device,
target port and local port; `web/WebPageMemory.kt`): "Open" on the same
tunnel again reloads that page instead of `/`, as a browser restores a
tab, when it is a page of that tunnel with the same scheme. Only pages
that loaded without an error are kept, and the in-page routes after them
(homeui's `#!/…`); "⋮ › Start page" goes to `/`. Forgetting a profile drops
its pages, with or without its data. Cookies are kept as above. When the
tunnel goes away while the page is shown (Disconnect here or elsewhere,
the server, a rebind to another local port), the page is hidden and the
screen says the tunnel is no longer there, with Back, instead of
WebView's error page; Back then leaves the screen. If the tunnel comes
back meanwhile, the page loads again.

Requests a page makes to another loopback port (another tunnel, another
app's server) get a 403, but WebSockets cannot be filtered: WebView passes
them to no hook. So a page whose device is compromised can open
`ws://127.0.0.1:<port>` to other tunnels or other apps' servers that are up,
and talk to any that serves HTTP or WebSocket.

Every tunnel is a plain TCP port on `127.0.0.1`, and any app on the phone
may connect to it while it is up, just as this app does. SSH still asks for
its login; a device web UI without a password of its own is open to them.
Give device web UIs a password, and disconnect tunnels you no longer use.

## SSH console

"Terminal" on a connected SSH connection opens the session already open to
that device and port (the last live one among the tabs, else the last ended one,
with Reconnect), so leaving the terminal and coming back loses nothing; a new
session is "+" on the tab row. With none open it asks for the user (the
connection's, `root` by default; the method is remembered per device and
port) and Key or Password, then opens a session to
`127.0.0.1:<local port>`.

- Key: the app makes one ed25519 key on first use; Settings shows its
  `authorized_keys` line with Copy / Share. "Import private key" takes an
  OpenSSH (or PEM) private key file and its passphrase; it is tried before
  the app's key. Private keys, passphrases and remembered passwords are
  sealed with a Keystore AES-GCM key of their own (`frpjump-ssh`), so
  re-enrolling the phone keeps them; forgetting the last profile wipes them.
- Password: kept only when "Remember on this phone" is ticked; a remembered
  password that is refused is dropped.
- Host keys are pinned per (profile, device name, target port) on first use, shown as
  OpenSSH `SHA256:` fingerprints. A different key blocks the connection and
  shows both fingerprints; "Forget the old key" drops the pin and asks again.
- The terminal requests `xterm-256color` and sends `window-change` when the
  view or the font size changes. Below it, as the mockup: Esc, Tab, Ctrl,
  Alt, ↑, ↓ and "⋯", which opens a second row (← → Home End PgUp PgDn
  `| ~ / -`). Ctrl and Alt stay held, lit, until the next key.
  Long-press selects text (Copy / Paste), pinch or A− / A+ changes the font
  size, ⌨ brings the keyboard back.
- Full screen (the corners icon in the bar) hides the bar and the system
  bars; the terminal, the session tabs (when there are several), the
  snippets and the keys stay, and a thin line says what a session that is
  not open yet is doing. The small round button (drag it aside) or Back
  leaves it, a second Back leaves the terminal. The terminal remembers it
  separately from the web pages. Landscape with the keyboard up hides the
  bar anyway, and the session tabs too while there is only one session;
  hide the keyboard to reach the icon.
- Snippets: a row of saved commands above the keys. Tap types a command
  without Enter (to finish it first), long press types it and presses
  Enter; the manager says so. The last chip ("+ Add command" while there
  are none) opens it: add, change, delete, ↑ / ↓ to reorder. One list per
  phone, for every profile and device, in `snippets.json`
  (`snippets/SnippetStore.kt`; a file that does not parse is kept as
  `snippets.corrupt.json`). Sent only into an open session; otherwise the
  app says the session is not open. Line breaks are sent as CR, as Enter
  is. A held Ctrl / Alt does not apply to a snippet: it is typed as saved,
  and sending it releases the hold. A write that fails keeps the list as it
  was and says so (as a setting that cannot be saved does).
- Sessions live in the app, not in the screen: several at once; the
  Terminals tab leads back. A session ends when its tunnel goes (Disconnect,
  Disconnect all, Forget); "Reconnect" starts a new one with the same login.
  When the session shown goes that way, the terminal says "The tunnel was
  closed." and shows the next one (or leaves when none is left).
- Session tabs: a thin row between the bar and the terminal, shown with one
  session too (`ui/ssh/SessionTabs.kt`). A tab has the profile's colour
  dot and "user · device" (":port" when not 22); a second and third
  session to the same user, device and port read "· 2", "· 3", in the
  order they were opened, kept while they live (closing one renumbers
  nothing; the next one counts on from the highest open one). A small
  spinner and a dimmed name while connecting, a red dot next to the
  profile's once it ended or failed. Tap switches (no swipes: the terminal
  uses them to select text); the row scrolls and brings the selected tab
  into view. Long press: Close session, and Reconnect once it has ended
  (never on a live shell). Closing the tab shown
  shows its left neighbour. "+" at the end opens another session to the
  selected one's device and port with the same login, at once, and selects
  it. A login the device refused, or a remembered password forgotten since,
  asks with the login dialog instead; a session that went meanwhile says
  "The session is gone";
  long press on "+" opens the login dialog for that device and port, to log
  in as another user.
- "Open in external app" sends `ssh://<user>@127.0.0.1:<port>` to an SSH app
  (Termius, JuiceSSH, …).

Connecting or disconnecting another port does not restart frpc: the app
rewrites `frpc.toml` and calls frpc's admin API (`GET /api/reload`, on
127.0.0.1 with a random port, user and password made for each frpc run),
and frpc swaps only the visitors that changed. Open SSH sessions and web
sockets of the other tunnels stay up. frpc is restarted (dropping them)
only when the reload fails or something frpc reads only at start changes:
the server, the device's TLS material or token.

The other end still restarts: a connect or disconnect changes what the
target device exposes, and its Linux agent restarts its own frpc on any
config change. So a port connected or disconnected on the same device
drops that device's other open sessions (seen on a WB controller: its frpc
restarted 2 s after the phone connected port 80). Tunnels to other devices
and phone-only changes (a moved local port) keep their connections.

## Files

"Files" in the "⋮" menu of a saved SSH connection (device card and
Connections tab) asks for the login as Terminal does (the same dialog,
remembered method, pinned host key) and opens the device's files over
SFTP; the folder icon in the terminal's bar does the same for the session
shown, with its login. A connection that is down is connected first; the
screen waits for the tunnel. Files use an SSH connection of their own
(`files/SftpBrowser.kt`), so a transfer and an open terminal do not touch
each other; both log in through `ssh/SshConnector.kt`.

- It opens in the user's home folder. The path bar on top: tap a part to
  go there, tap the last part to type a path (absolute, relative, `~`).
  Back goes up a folder and leaves at `/`; the arrow in the bar leaves at
  once. Leaving while files are being transferred asks first ("N
  transfers in progress": Stay, or Leave and cancel); should they all
  finish meanwhile, the screen leaves, and should one fail, it stays on
  the failure. The path shown is always the folder's real one (links
  resolved), so relative link targets in it are shown right.
- Folders first, then files, by name; size and modified time. Dot names
  only with "⋮ › Show hidden files", remembered in `settings.json`.
- Symlinks carry a link badge and their target (`→ /mnt/data/etc`); a link
  to a folder opens at its target, a broken one (nothing at the target)
  says so. A target the user may not look at, or a loop, is not "broken":
  it can still be downloaded, the device says whether it serves it. A
  link's card has "Go to the target" (the target folder, or the folder its
  target is in). Links are looked into eight at a time.
- Tap a folder: it opens. Tap a file, or the small "⋮" at the end of any
  row: its card, a bottom sheet like "New connection" — name, path, a
  link's target, size, modified, permissions (`rwxr-xr-x  755`) and owner
  (SFTP gives the uid and gid as numbers, so numbers are shown). Nothing is
  transferred until an action is tapped: Open, and for files Download and
  Share; a folder's card has Open only (folders are not downloaded yet). A
  transfer of that file shows in the card with its bar and ✕; the card
  closes when the file is handed to another app.
- Long press starts selecting: rows get a checkbox, taps add and remove,
  the bar shows the count, "Select all", and Download and Share while a
  file is among the picked (folders and special files are left out, and a
  toast says how many). Back or ✕ ends it, as does going to another folder.
  The actions come from `ui/files/FileSelection.kt`, where stage 2 adds
  Copy, Move, Rename, Delete. Several files shared at once are downloaded
  to the cache, then shared together.
- Open hands the file to another app after downloading it to the app's
  cache ("Open with", through the FileProvider). Download writes into a phone folder chosen once with the system folder
  picker (kept with a persistable grant; "⋮" changes it, and losing access
  to it asks for it again); a name that is there already becomes
  `name (1).ext`. A download that fails or is cancelled is deleted, never
  left half written under its name.
- "+" uploads files picked with the system picker into the folder shown,
  one at a time, each written to a hidden `.name.frpjump-part` (a long
  name is cut to fit 255 bytes, with a short hash) and renamed at the
  end, so a half upload never replaces a file. A part file there already
  is replaced when it is over ten minutes old (an interrupted upload),
  else the upload takes the next name (another upload may be writing
  it); a failed or cancelled upload removes the part file it made, and
  only that one, before the connection closes. A taken name becomes
  `name (1).ext`; names being uploaded to are held, so two uploads of one
  name get two names, and a name taken just before the rename moves the
  upload to the next one.
- Progress shows on the row, with ✕ to cancel just that transfer (its half
  copy or part file is removed as on any cancel; an upload waiting its turn
  is dropped, the others go on); a failure stays there, in plain words,
  with Retry and Dismiss. A refused login offers "Change login" (the login
  dialog again) instead of Retry; a host key to decide on asks as the
  terminal does, also when a transfer meets it.
- Pull to refresh. With the tunnel down, or SSH failing, each folder shows
  as last listed (kept per profile, device, port and folder in the app's
  cache, `files-listings/`, at most 5000 entries a folder, which the screen
  then says; removed when the profile is forgotten), marked "No
  connection", with Retry (which connects the tunnel when it is down);
  nothing can be downloaded or uploaded then.
- Transfers belong to the screen in this version: leaving it stops them,
  which is why leaving with transfers going asks first.
  Files handed to other apps (`files-open/<profile>/`) stay a while, as a
  share target may read them later: closing a file screen removes those
  older than two hours, the app's start those older than a day, and
  forgetting the profile all of its own. Closing waits up to 15 s for
  cancelled transfers to clean up, then closes the connection anyway.

## Third-party libraries

| Library | Version | License |
| --- | --- | --- |
| [frp](https://github.com/fatedier/frp) `frpc`, built into the APK | 0.70.0 | Apache-2.0 |
| [sshj](https://github.com/hierynomus/sshj) | 0.41.2 | Apache-2.0 |
| [BouncyCastle](https://www.bouncycastle.org/) bcprov / bcpkix (via sshj) | 1.84 | MIT (Bouncy Castle licence) |
| [Termux](https://github.com/termux/termux-app) terminal-emulator / terminal-view (JitPack) | v0.118.3 | Apache-2.0 |
| slf4j-api, asn-one (via sshj) | 2.0.17, 0.6.0 | MIT, Apache-2.0 |
| [CameraX](https://developer.android.com/jetpack/androidx/releases/camera) camera-camera2 / camera-lifecycle / camera-view | 1.4.1 | Apache-2.0 |
| [ZXing](https://github.com/zxing/zxing) core | 3.5.3 | Apache-2.0 |

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

## Release

The release build carries frpc for arm64-v8a only (phones; the debug build
also has x86_64 for the emulator), is minified with R8 and shrinks resources
(`app/proguard-rules.pro` says what is kept and why; the mapping for stack
traces is `app/build/outputs/mapping/release/mapping.txt`, keep it with the
release). It is signed with the release key, which never goes into the
repository. Put its location and passwords in `~/.gradle/gradle.properties`
(or the environment, same names):

```properties
FRPJUMP_RELEASE_STORE_FILE=/path/to/frp-jump-release.jks
FRPJUMP_RELEASE_STORE_PASSWORD=...
FRPJUMP_RELEASE_KEY_ALIAS=...
FRPJUMP_RELEASE_KEY_PASSWORD=...
```

Without all four, `./gradlew assembleRelease` still builds, says so, and
leaves an unsigned `app-release-unsigned.apk` that cannot be installed; the
debug build never needs them. Every release must be signed with the same
key: Android installs an update only over an app with the same signature,
so a phone that has a debug build (or one signed with another key) has to
uninstall it once before the first release goes on.

Releases are built and published by GitHub Actions
(`.github/workflows/release.yml`), which holds the key in the secrets of the
GitHub environment `release`, open only to `main` and `v*` tags:
`FRPJUMP_RELEASE_KEYSTORE_B64` (the PKCS#12 file, base64) and
`FRPJUMP_RELEASE_STORE_PASSWORD` (store and key password, alias `frpjump`).
To publish a version:

1. Raise `versionName` and `versionCode` in `app/build.gradle.kts` and add
   the release notes as `docs/releases/<versionName>.md`; merge to `main`.
2. Tag that commit and push the tag:

   ```sh
   git tag v0.2.0 && git push origin v0.2.0
   ```

The workflow builds frpc and the APK, runs the unit tests, then
`scripts/release.sh --publish`. Running the workflow by hand on `main`
(Actions › Release › Run workflow) builds and checks the same signed APK
without publishing; on any other branch it refuses. Either way the APK, its
`.sha256` and the R8 mapping are kept as the run's artifact for 90 days
(public, as the repository is); download and keep the mapping of each
published release.

`scripts/release.sh` does the same on this machine:

```sh
scripts/release.sh                                   # dist/frp-jump-android-<version>.apk, .apk.sha256, -mapping.txt
scripts/release.sh --publish --notes-file notes.md   # also GitHub release v<version> with the APK and .sha256
```

`--publish` refuses unless the tree is clean and on `origin/main`, there is no
`v<version>` release yet and no `v<version>` tag other than on this commit,
versionCode is above that of every other `v*` tag, and apksigner and aapt2
are there; the built APK must say the versionName and versionCode of
`app/build.gradle.kts`. With `FRPJUMP_RELEASE_CERT_SHA256` set (CI sets it
to the release certificate,
`1ae85a21696641cf9cb167abab8846b9b2705d5e2e36f79c61e6b921f3352336`), an APK
signed with any other key is refused.

The release is published as a normal one, not a pre-release, even for a
version such as `0.2.0-beta2`: the app asks GitHub for the latest release,
which skips pre-releases and drafts. The notes file is what the app shows
under "What's new".

## Updates

The app looks for a newer release in this repository's GitHub Releases
(`releases/latest`, no token): when it is opened and a day has passed since
GitHub last answered, and whenever the owner taps the version row in
Settings. The answer's ETag is kept with the release it described, so an
unchanged release costs a `304 Not Modified`, which GitHub does not count
against the 60 requests an hour it allows without a token
(`update/Updater.kt`, `update.json` in the app's files).

- Versions compare as semver, a pre-release before its release:
  0.2.0-beta1 < 0.2.0-beta2 < 0.2.0 (`update/AppVersion.kt`).
- A newer release shows in Settings as "Update available: x.y.z" with an
  "Update" button, and once per version as a snackbar on the other tabs.
- "Update" opens the release notes and "Download and install". The APK is
  the release's `.apk` file (`frp-jump-android-<version>.apk` when there are
  several); it is downloaded into the app's cache with the progress in the
  sheet and checked against the SHA-256 in `<apk>.sha256`. A release without
  that file, or a download that does not match it, is not installed.
- Then the APK itself is read (`update/ApkCheck.kt`): it must be this app's
  package, with a higher versionCode, versionName the release's version, and
  exactly the installed app's signing certificates. Anything else is
  deleted and the sheet says why.
- What each check protects: the SHA-256 only shows the file arrived as it was
  published (no truncated or swapped download); it comes from the same
  release, so it does not protect against a release someone else published.
  The signature check does: only an APK signed with the release key passes,
  which Android's installer would insist on anyway, but the owner then
  learns why before the installer opens. Downloads go only to GitHub
  (`github.com`, `api.github.com`, `*.githubusercontent.com`, https only, no
  redirect elsewhere) and only from this repository's release files.
- The checked APK goes to the system installer, which asks the owner. If
  Android does not yet let the app install apps, a dialog says so and opens
  "Install unknown apps" for it; back with it allowed, installation goes on.
  Nothing is installed without the owner's taps.

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Enroll

On a laptop that is already enrolled:

```sh
frp-jump-client devices add-token --name phone --qr
```

It prints the enroll line and, below it, a QR code of the same line drawn
with block characters. In the app, "Add profile" (or the first screen):

- "Scan QR code" opens the camera, asking for the permission at that tap.
  A code that is an enroll line ticks, closes the scanner and fills the
  line field, which is then read as a paste is; any other code says "This
  QR is not a frp-jump enroll line" and scanning goes on. The torch button
  helps with a dim screen. The terminal draws light modules as glyphs, so
  on a light terminal the code comes out inverted; the scanner reads both.
  Refused once, a line says what the camera is for; refused for good, a
  line offers "Open settings". Pasting works either way.
- "From gallery" (under "Scan QR code", and on the scanner beside the
  torch) reads a picture of the code instead: a screenshot, or the code
  sent as an image to this same phone, which cannot scan its own screen. It opens the system photo picker, images only, with no
  storage permission. An enroll line fills the field as a scan does; a
  picture without a code says "No QR code found in this picture", another
  code says it is not an enroll line, and the scanner stays. On the form
  that line goes once the enroll line changes.
- Or paste the printed `frp-jump-client enroll https://… <token> --name phone`
  line as is; line breaks from a narrow terminal are fine. A bare
  `<url> <token>` works too. Without `--name` on the laptop the line ends in
  `--name <pick-a-name>`: that placeholder is ignored, and the app asks for
  the phone's name when the server wants one.

Either way the app shows "Server: <host>"; name and colour the profile and
tap "Connect phone". The token is single-use.

Frames are decoded with ZXing (`enroll/QrDecoder.kt`: QR only, try harder,
also inverted) on one background thread, the newest frame only, from
CameraX's analysis stream; no Google Play Services. A picture is loaded
upright and scaled to at most 2000 px a side, transparent pixels as white,
and decoded with the same hints, first with ZXing's hybrid binarizer, then
its global-histogram one (`enroll/QrPicture.kt`).

## License

[MIT](LICENSE), as [frp-jump](https://github.com/aadegtyarev/frp-jump).
The bundled components keep their own licenses, see
[Third-party libraries](#third-party-libraries).

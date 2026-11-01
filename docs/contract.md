# frp-jump for Android — contract

An Android app that is a frp-jump device of its own: it lists the owner's other
devices and opens their web UI, an SSH console or any TCP port through frp-jump
tunnels. It only consumes ports; it never exposes anything of the phone.

Status: v2 draft for the owner's review. v1 (0.1.x) is built and in use; this
version describes 0.2. UX mockups are shared with the owner separately.

## Scope

- One user: the owner. APK installed by hand, no Google Play. Android 10+ (API 29), arm64.
- Works against the existing frp-jump server, wire protocol version 1, frp 0.70.0.
  No server changes, except device labels and icons (scenario 11), which need
  a newer server. Against an older server the app hides that feature and
  everything else works.
- All devices reached are the owner's own (the server only allows that).
- UI in Russian and English.

Out of scope for v2: exposing phone ports, other people's devices, key-based
enrollment, admin commands, Google Play, x86/armv7 builds, more UI languages.

## What changes from v1

| Area | v1 | v2 |
| --- | --- | --- |
| Accounts | one enrollment | profiles: several enrollments, tunnels from all at once |
| Ports | presets Web/SSH + added ports | named connections to any port, a tab listing all of them |
| Without network | empty screen | last known lists, marked "no connection" |
| P2P | silent fallback to relay | try direct, then work through the server and offer a retry |
| Direct tunnel | drops when idle | kept open while the connection is up |
| Process killed / phone rebooted | tunnels gone until the app is opened | tunnels come back by themselves |
| Enroll | paste the line | paste the line or scan a QR code of it |
| Language | English | Russian and English, follows the system |
| Builds | debug APK | signed release APK, update check in the app |
| Look | default Material | redesign per the mockups |

## UI rules

- Every action answers at once: the pressed control shows it was pressed, and
  then something visibly changes — a state, a progress indicator, a message.
  No tap may look as if nothing happened.
- Anything that takes longer than about half a second shows progress where
  the owner tapped, not somewhere else on the screen.
- Failures are shown next to what failed, in plain words, with the action
  that may fix it (retry, open settings).
- The same thing is called the same way on every screen and in notifications.
- Undo instead of confirm. A destructive action happens on screen at once,
  without a question: the row goes, the tunnel goes dark. A top notice with
  **Undo** stays about 6 s; until it goes, nothing is done on the device or
  the server, and Undo puts everything back as it was. Several actions in a
  row share one notice ("Deleted: 3 · Undo"). If the app is closed in those
  seconds, the action still completes. This covers deleting a connection, a
  snippet or an MQTT pin; forgetting a saved password or login;
  disconnecting a tunnel or all tunnels (it goes dark at once; its terminal
  and Files sessions close only when Undo is gone); closing a terminal tab
  or a session on the Sessions tab (its shell ends when Undo is gone);
  deleting files; deleting retained MQTT
  topics. The one confirm left is "Forget profile": it wipes the phone's
  keys and only a new enroll brings them back.
- Consistency: controls that do the same action look the same everywhere —
  same glyph, same kind of control, same placement relative to what they act
  on (Disconnect is the same button on a connection row, a device card and
  the Devices top bar). Similar actions look alike, with only the difference
  that matters (a folder and a file tile share shape and size and differ by
  glyph and tint). A new control starts from an existing one, not from a
  new design.
  Every bottom sheet has a ✕ on the right of its header that closes it,
  read out as "Close", besides a swipe down, a tap outside and Back: a
  sheet pulled to full height leaves no other visible way out.
- Keep screens light. Each screen shows the main action and the state; a
  clear icon replaces a caption where the meaning is obvious. Rare actions
  (rename, delete, copy address, open in another app, forget profile) live in
  a "⋮" menu or a long press, not as extra buttons. Disconnect is not rare:
  it sits on the row of an active connection, next to its main action.
- Light and dark themes, each designed on its own rather than inverted: dark
  is a warm graphite, not black, with a toned-down accent and its own status
  and profile colours; text contrast at least WCAG AA in both. Follows the
  system, or set by hand in settings.
- No technical details on the main screens: addresses and frpc states are on
  the card or in details, not in the lists. The one exception is the compact
  mark of open tunnel ports on a device row (link glyph and port numbers),
  which tells at a glance where a tunnel is open.

## Scenarios

### 1. Enroll a profile

A profile is one enrollment: one server, one device name, its own token,
certificate and key. The owner may have several, for example "Work" and
"Home", which are different users on the same or different servers.

1. On a laptop: `frp-jump-client devices add-token --name phone --qr`; it
   prints the enroll line and a QR code of it in the terminal.
2. In the app, "Add profile": scan the QR code (the camera is asked for at
   that tap; the code reads on a dark or a light terminal, where it comes out
   inverted), or paste the line as is
   (`frp-jump-client enroll https://… <token> [--name phone]`; a bare
   `<url> <token>` works too; line breaks and extra spaces are ignored; the
   CLI's `--name <pick-a-name>` placeholder counts as no name). A scanned
   code fills the line as a paste would; a code that is not an enroll line
   says so on the scanner, which keeps scanning. "From gallery" reads the
   code from a picture (sent to this same phone) the same way.
3. The owner gives the profile a name and a colour (prefilled from the server
   host). The app calls `POST /api/agent/enroll` and stores the credentials
   encrypted with a Keystore key.
4. The profile opens with its device list.

Errors shown as they are: bad or used token, unreachable server, protocol
version mismatch.

The single v1 enrollment becomes the first profile on upgrade, nothing to
re-enroll.

### 2. Switching profiles

- The device list shows one profile at a time; a switcher at the top changes it.
- The visible profile polls every 2 s while the app is open. Other profiles
  poll only while they have tunnels up, at the background interval.
- "Connections" and the notification show tunnels of all profiles, each
  marked with its profile colour.

### 3. Device list and offline

- `GET /api/agent/devices`: name, online (last seen < 30 s), last seen time,
  disabled flag, and, from a server that has them, label and icon
  (scenario 11). The phone itself is not listed.
- A row shows the device's icon on its tile (the state dot stays in the
  corner) and the name. The second line puts the label in front of the state:
  «Щиток, гараж · Онлайн · ⛓ 22». When the line does not fit, the label is
  cut with an ellipsis first, so the state and the tunnel ports always stay
  visible. A device without a label shows the line as before.
- A magnifier in the title opens search. It matches the device name or its
  label, ignoring case and the spaces around the query; the hint says «Имя
  или подпись». Nothing found says so.
- The last answer is kept on the phone per profile. On start the app shows it
  at once, then refreshes; until the server answers, devices online in it
  say "Checking…" rather than an age that is really the app's absence.
- No answer from the server: the list stays, a banner says "No connection to
  the server · last update <time>", devices show their last known state
  marked as possibly stale. Saved connections are still listed and can be
  opened when the network returns.
- Pull to refresh; the banner goes away on the first good answer.
- A device row with tunnels open swipes left to show the power button (the
  bar's glyph and red tint): it disconnects only that device's tunnels of
  this profile, with Undo; the device card's bar has the same button. The row
  slides back on scroll, refresh, Back or a finger landing elsewhere on the
  screen; a tap on a row still opens the card. TalkBack offers it as the row's action
  "Disconnect tunnels of <device>"; rows without tunnels do not swipe.

### 4. Device card and named connections

- A connection is: device, port on the device, kind (web / SSH / TCP), name,
  plus for web the HTTPS flag (not asked on 80, always HTTP, and 443, always
  HTTPS) and for SSH the user.
- "New connection": pick kind, type a port or tap a preset (web: 80, 443,
  8080, 1880, 3000; SSH: 22, 2222; TCP: 1883, 502, 5432), give a name, and
  optionally "connect now".
- Several connections to the same device and port are allowed under different
  names (for example SSH as root and as another user).
- Local port: "Auto" by default (from the range in Settings), or a fixed
  number 1024–65535 pinned to the connection, for programs that need a
  constant `127.0.0.1:N` (an assistant reaching its backend over a TCP
  tunnel). A pinned port is never given to another tunnel, even while the
  connection is off, and never moved: when another app holds it, the card
  says so and the tunnel stays on it, trying again every 30 s, until it is
  free or the pin changes. Deleting the connection or setting it back to
  Auto releases the port.
  Two connections cannot pin the same port; connections to the same device
  and port share their tunnel and its pin; a port another tunnel uses now
  is refused. It cannot be changed while the tunnel is up.
- The card's header shows the device's icon on a tile, then the whole name and
  under it the whole label (wrapped, never cut). The top bar's "⋮" holds
  «Подпись…» and «Значок…» (scenario 11).
- Rename, change and delete a connection from the card or the Connections tab.
- Web → in-app WebView; HTTPS with a self-signed certificate is remembered on
  first visit. A device answering an HTTPS connection in plain HTTP is
  told as such, with "Open over HTTP". SSH → built-in terminal. TCP → shows and copies the local address.
- Back in the web view, system Back and the bar's arrow alike: full screen is
  left first, then the page's history is walked (in-page routes of single-page
  UIs too), and only with no page to go back to does the screen close. An
  entry that sends the page straight forward again (homeui's `/`, which
  pushes its dashboard) is stepped over, so Back never loops on it. With
  the page hidden (tunnel closed, renderer crashed) Back closes. A long press
  on the arrow closes at once (TalkBack: the arrow's "Close" action). The ⋮
  menu has Back and Forward (enabled when there is a page to go to), Reload,
  Start page, Open in browser and Close.
- A connection the device has not opened yet (its agent is still setting up
  a new tunnel) is "Waiting for the device…", not an error; a terminal,
  explorer or file screen opened with it waits too. Still not open 30 s
  after the first refusal: "The device did not open port N: its agent did
  not answer. Check that frp-jump-client runs on the device." It
  recovers by itself once the device takes connections (checked every 10 s).
- A tap anywhere on a connection's card (device card and Connections tab)
  does its main action, which the visible button names: connected, it
  opens (Terminal, Explore, Open) or, for TCP and a tunnel no connection
  names, copies the address; not connected, it connects, then does the
  same (TCP: connects and copies the address). SSH with no login to use at
  once opens the login dialog first. Taps while it is coming up open it
  once. Leaving the screen before it is up cancels the open (the tunnel
  stays). On a device offline now, a connection that is down has no tap
  action and its Connect is off (scenario 5); "⋮" and a long press work. The disconnect button, "⋮" and the other buttons keep their own
  actions, and so does the state when it offers a choice ("Through the
  server ›"). While a connection is being disconnected the card has no
  action. TalkBack reads the card's action as the main action's label
  when connected and "Connect and open" when not.

### 5. Connections tab

Quick access to the saved connections of every device, active ones first.

- Grouped by device. A group's header is the device's whole name (wrapped,
  never cut) after its state as a dot in the Devices list's colours (online,
  online as of an old list, offline; none while its profile's list is not
  read). Under the name, a muted line shows the device's label, whole and
  wrapped, taken from the profile's last device list. No label, or a list not
  read yet, means no line. The header has no icon: the dot already marks the
  device. With more than one profile on the tab, the header also shows the
  profile's colour; devices of the same name in two profiles are two groups.
- Order: devices with an active connection first, then online ones, then the
  rest (offline, or not in the list); within each part by device name.
  Within a group the connections keep their stored order; a tunnel up that
  no saved connection names is listed after them, under its device.
- Each connection is the same card as on the device card: kind tile, its own
  name only when it has one other than the kind (a default "SSH" is not
  repeated), port (and the SSH user and login method), state, the main
  button ("Connect" / "Terminal" / "Explore" / "Open" / "Copy"),
  Disconnect while up, and "⋮". A tap on the card does its main action
  (scenario 4).
- On a device offline as of a fresh answer of the server, a connection that
  is down is dimmed: its Connect and the tap on its card are off, and its
  state says "Device offline" instead of "Not connected". Its "⋮" and a long
  press still work. An active one stays as it is, so it can be disconnected.
  An old list (the server not answering, or not checked yet since the start)
  blocks nothing: the device may be back. The device card does the same.
- While the tab shows, every profile's device list is kept fresh: each is
  asked when the tab shows, the shown profile's is polled as on Devices,
  the others are asked again every 10 s; pull to refresh asks them all.
  Dots and buttons follow at once. The order of the devices is fixed for
  the visit once the lists were asked (answered or not), so a device that
  connects or comes online does not jump under the finger; it is sorted
  again on the next visit or after a pull.
- From 6 connections, a magnifier in the title opens search, as on Devices:
  it matches the device name or its label (all its connections) or a
  connection's name; nothing found says so.
- While any tunnel is up (or being restored), the title has the Devices
  bar's power button: it disconnects every profile's tunnels, with Undo.
- Nothing saved: "No saved connections yet. Open a device on Devices and tap
  “Connection”."

### 6. Direct or through the server

- Setting "Direct connection (P2P)", on by default.
- On: connecting shows "Trying direct…" for up to 10 s. Success shows
  "Direct".
- Direct failed: the connection works through the server right away, the
  card says "Through the server", and a notification "Direct didn't work:
  <name>" offers "Retry direct" and "Keep through the server". The second
  one stops offering for this device on the current network (the Wi-Fi
  network, recognised without the location permission, or mobile data)
  until the owner retries from the card.
- A direct tunnel that drops mid-session falls back to the server the same
  way, with the same notification.
- Tapping the state on the card offers the same two actions.
- Off: always through the server, no attempts, no notifications.
- While a connection is up, its direct tunnel is kept open so it does not
  drop after idle.

### 7. SSH console

As in v1: user + key or password, one generated ed25519 key per phone (shared
by all profiles), host keys pinned per profile+device+port, passwords stored
only on "remember", several sessions at once. New: a row of snippets (saved
commands, owner-defined) above the extra keys; the sessions as a row of tabs
above the terminal, where "+" opens another session to the same device with
the same login (long press: as another user).

The extra keys under the terminal are one row: ^C, Paste, ← ↑ ↓ →, ⌫, ⏎
and ⌨, which shows and hides the soft keyboard (the bar has no keyboard
button). Paste works as the selection menu's paste and is disabled while the
clipboard has no text. Any paste drops all trailing line breaks and blank
lines, so a single line never runs by itself. With bracketed paste enabled by the remote side it
goes as is; several lines without it show a sheet first, "You are pasting N
lines" (ru «Вы вставляете N строк») — "Without bracketed paste each line runs
at once": Paste (as is, the lines run), Remove line breaks (each line trimmed,
blank lines dropped, joined by a single space; no shell syntax, so a trailing
`\` continuation stays); the sheet's ✕ or a swipe down cancels. In a tmux
session the bracketed flag is not trusted (tmux keeps it on toward the app
whatever the pane asked), so several lines always show the sheet there; a
multiplexer started by hand in a plain-shell tab can't be detected. While the
keyboard is up, extra rows slide in above it: Esc, Tab, sticky Ctrl and
Alt, ^D ^Z ^R ^L, Home End PgUp PgDn, `| ~ / -`. The arrows and ⌫ repeat
while held (400 ms, then about 15 a second); the other keys do not.

A tap on the command line moves the shell's cursor there: the app sends as
many ← or → as there are characters between the cursor and the tapped cell
(a wide character counts once, at most 1000, which stays under the terminal's 4 KB write queue), and the shell moves its
cursor and echoes it; no other feedback. It counts only on the cursor's row
and the rows wrapped into it at the margin (one logical line); a tap past
the end of the text goes to its end. A row the shell broke with a real line
break (a pasted multi-line command, a `for` loop's `>` lines), output above
and the empty screen below keep the usual tap: it shows the keyboard (the
tap on the command line shows it too). The arrows are plain keys: a held
Ctrl / Alt stays held. Until their echo shows (at most 400 ms), another
tap on the command line does nothing: it would count from where the
cursor was. With TalkBack's touch exploration on, taps stay as
they were.

What reads the arrows:
- Plain shell: whatever runs on the normal screen. In `vim`, `htop`, `mc`
  or `less` (the alternate screen) the tap goes to the program as before,
  mouse click included. A program on the normal screen gets the arrows
  too: a `read` or password prompt, `cat`, a REPL; one without a line
  editor shows them as `^[[D`.
- tmux (the app's sessions): tmux holds the alternate screen toward the
  app, so a tap on the cursor's line is kept from tmux as a click and one
  exec asks tmux (`display-message`) about the pane. The arrows go when it
  is on its normal screen, in no copy mode, alone on the screen (one pane
  or zoomed) and runs a shell in the foreground (`sh`, `bash`, `zsh`,
  `fish`, `dash`, `ash`, `ksh`, `mksh`), and only if the cursor's line and
  the cursor's place in it are unchanged since the tap (a re-wrap from the
  keyboard showing is no change). Any other answer within 2 s hands the
  program the click it would have had. No answer within 2 s, or a failed
  one: nothing, never a late click. One question at a time: a tap while
  one is out is dropped, and one open 2.5 s counts as no answer; an
  answer stands 400 ms for an unchanged line. A `read` prompt of a script runs in `bash` too, so it gets the arrows,
  and without `read -e` shows them as `^[[D`. The keyboard shows once
  tmux has answered: coming up, it resizes the terminal and tmux redraws.
- The text right of the cursor counts as the line: zsh's RPROMPT, or an
  autosuggestion (fish, zsh-autosuggestions) shown after the cursor. A tap
  there sends → past the end of what was typed, which the shell either
  stops at or, for an autosuggestion, takes as accepting it.

Swipes (scrolling, tmux copy mode) and long-press selection work as before.

### 8. Background, kill and reboot

- While any tunnel is up: a foreground service keeps frpc alive and shows a
  notification ("N tunnels in M profiles", "Open", "Disconnect all").
- Background poll interval: off / 30 s / 1 min / 5 min / 15 min, as in v1.
- If Android kills the process, or the phone reboots, while tunnels were up:
  the service starts again by itself and brings back the same connections on
  the same local ports. "Disconnect all" or disconnecting the last tunnel
  clears this.
- The app points to the battery-optimisation exemption when the system may
  stop the service.

### 9. Settings

Grouped as in the mockup: profile (name and colour, forget profile); tunnels
(direct connection, local port range); background (poll interval, battery);
security (phone SSH key, unlock with fingerprint); app (language, theme,
version and update).

- "Forget profile": disconnects its tunnels, deletes the phone from that
  server (offline: local wipe only), wipes its credentials and cached lists.
- Fingerprint unlock: off by default; when on, opening the app asks for the
  device's biometrics or screen lock. Running tunnels are not affected.
- Language: system (default), Russian, English.
- Theme: system (default), light, dark.

### 10. Updates

- Release APKs are signed with one release key kept outside the repository.
- Releases are published as GitHub Releases of this repository, which is
  public: the APK and its `<apk>.sha256`, read without a token.
- The app checks for a newer release on start and at most once a day, and
  when the owner asks from Settings; shows "Update to x.y.z" with the release
  notes, downloads the APK and hands it to the system installer. Nothing is
  installed without the owner's tap.
- Before the installer, the download must match the published SHA-256 and be
  this app (same package), newer (higher versionCode), the version the
  release names, and signed with the installed app's key; otherwise it is
  deleted and the owner is told why.

### 11. Device label and icon

A label is free text such as «Щиток, гараж» that tells the owner what a device
is and where it stands. An icon is one of a fixed set. Both live on the
frp-jump server, so every phone and the CLI show the same values
(frp-jump `docs/device-labels-icons_plan.md`). The device name does not change.

- Icons, all drawn as stroked glyphs in the app's style: Linux (Tux), Wiren
  Board, server, computer, laptop, router, single-board computer, camera,
  home. The device's agent reports a detected one; the owner's choice wins
  over it. With neither, or with a value this app version does not know, the
  row shows the generic device glyph used today.
- The device card's "⋮" has «Подпись…» and «Значок…». Both open the same
  bottom sheet, «Подпись и значок», with the ✕ in its header. «Подпись…»
  opens it with the keyboard up in the text field; «Значок…» opens it with
  the keyboard down.
- The sheet has:
  - a single-line text field «Подпись», with the current label, the
    placeholder «Например, Щиток, гараж» and a counter up to 64. Line breaks
    in pasted text become spaces; nothing can be typed past 64;
  - a grid of icon tiles, three per row, each with its caption. The first
    tile is «Авто», showing the detected glyph and captioned with its name
    («Авто: Wiren Board»), or the generic glyph when the device reports
    none. The current choice has the accent border. TalkBack reads each tile
    as its caption plus "selected";
  - «Сохранить», enabled once something differs from what was saved.
- «Сохранить» saves both in one call (`POST /api/agent/devices/{name}/display`)
  and shows progress on the button. On success the sheet closes, and the
  card, the row and the Connections header show the new values at once (from
  the server's answer, not a guess). On failure the sheet stays open with the
  reason under the button (the server's own reason for a bad label, under the
  field) and the button offers to try again. The typed text is kept.
- An empty field clears the label. Picking «Авто» drops the owner's choice.
  Neither has an Undo: the sheet shows the old value until «Сохранить», and
  the next edit restores it if needed. This matches renaming a connection.
  The Undo rule covers actions that remove something at once, and here
  nothing goes before an explicit save.
- Without a connection to the server, the sheet opens with the last known
  values. «Сохранить» fails with «Нет связи с сервером — не сохранено» and the
  retry, as above. Edits are not queued for later: another phone may change
  the same device in the meantime.
- Against a server without labels (its device list has no `label` key), the
  "⋮" items are not shown, and rows show no label. Nothing tells the owner
  about it: there is nothing they can do from the phone.
- Labels and icons are kept in the cached device list. Offline, the list, the
  card and the Connections tab show them as last known.
- A label is plain text everywhere: no links, no markup, one line in the list
  row, wrapped on the card and the Connections header.

## Wire behaviour the app must match

- Bearer token on every `/api/agent/*` call; `protocol_version` checked on
  every response, a mismatch stops applying changes but leaves running tunnels
  alone.
- One frpc process per profile with tunnels up (each has its own certificate).
- frpc config as the Linux agent builds it (`driver/frp/config.py`): TLS block,
  `metadatas.frp_jump_device` / `frp_jump_token`, heartbeat 5 s / timeout
  30 s; per grant an `stcp` visitor plus, when direct is on, an `xtcp`
  visitor with `fallbackTo` the stcp one; both `useEncryption`, bound on
  127.0.0.1 only. Config changes are applied by hot reload.
- frpc is the upstream 0.70.0 built for android/arm64 and shipped inside the APK.
- `GET /api/agent/devices` items may carry `label`, `icon` (the owner's
  choice) and `kind` (the agent's detection), each possibly null. The shown
  icon is `icon`, then `kind`, then the generic glyph. The `label` key being
  present at all marks a server that supports editing.
- `POST /api/agent/devices/{name}/display` with `{"label": …}`, `{"icon": …}`
  or both. A missing field is left as it is, null clears it (`icon: null` is
  "automatic"), and the answer is the updated device. The phone sends no
  `kind` in its heartbeat (see Open decisions).

## Stored on the phone

| What | Where |
| --- | --- |
| Per profile: device token, cert, key, CA | app-private files, encrypted with a Keystore key |
| SSH private key, remembered passwords | same |
| Profiles, cached device lists, connections, local port map, host keys, snippets, active tunnels to restore, "keep through the server" choices, settings | app-private storage |

Nothing leaves the phone except calls to the frp-jump servers, the tunnels and
the update check.

## Delivery

Each stage is a separate PR, reviewed and checked on the emulator, then a
build for the owner's phone.

1. Profiles and the offline cache, with the v1 upgrade path.
2. Named connections and the Connections tab.
3. Direct/through-the-server behaviour, keepalive and its notifications.
4. Restore after kill and reboot.
5. QR enroll.
6. Russian and English.
7. Redesign of the remaining screens per the mockups, dark theme; fingerprint unlock; snippets.
8. Release signing and in-app updates.
9. Device labels and icons (scenario 11): the glyphs, the list row, search,
   the card header and sheet, the Connections header. It can ship before the
   server supports it, with the feature hidden until then.

Screens touched by stages 1–5 get the new look in the same stage.

## Acceptance

- Upgrade from 0.1.1 keeps the enrollment, ports and SSH key.
- Two profiles on ikar (two device names); tunnels from both up at once.
- Airplane mode, open the app: both lists shown with the offline banner.
- From mobile data: direct fails, the connection works through the server,
  the notification appears; "Retry direct" retries.
- An SSH session left idle for 10 minutes on a direct tunnel is still alive.
- `adb shell am kill` and a reboot with tunnels up: they come back on the
  same local ports.
- Scan a QR code from the laptop screen and enroll.
- System language English → English UI; Russian → Russian.
- Install 0.2.0, publish 0.2.1: the app offers and installs the update.
- Labels: set «Щиток, гараж» on wb01 from the card. The row reads
  «Щиток, гараж · Онлайн», the Connections header shows it under wb01, and
  search for «гараж» finds wb01. A second phone and `frp-jump-client devices
  list` show the same label after their next refresh. Pick "router", then
  «Авто»: the detected Wiren Board icon is back.
- Against a server without labels: no «Подпись…»/«Значок…», no labels, and no
  errors. In airplane mode the sheet's «Сохранить» says it was not saved and
  keeps the text.

## Decided

- Where release APKs live: GitHub Releases of this repository, now public;
  no token in the app.

## Open decisions

- frp-jump release tag: the hot-reload change for exposing devices is in
  master but not released; until devices update, their tunnels still restart
  when a grant changes.

- Device icon for phones: each phone profile is a device in the owner's
  list, and the icon set has no phone. Either the server adds a `phone` value
  that the app reports in its heartbeat, or phones keep the generic glyph.

## Open risks

- Aggressive vendor firmware may still kill the foreground service; the restart
  covers it but adds a gap of seconds.
- Mobile carriers' NAT usually blocks P2P, so on mobile data most connections
  will go through the server.
- A tunnel is a TCP port on 127.0.0.1, open to every app on the phone while
  it is up; device web UIs need a password of their own.
- WebView cannot filter WebSockets: a compromised device's page can reach
  the other tunnels that are up.

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
  «Щиток, гараж · Онлайн · ⛓ 22». The label is never cut, because it is what
  tells similar devices apart. When the label and the state do not fit on
  one line, the label takes its own line or lines, wrapped, and the state
  and the tunnel ports go on the line below. A device without a label shows
  the line as before.
- A magnifier in the title opens search. It matches the device name or its
  label, ignoring the spaces around the query. Both sides are normalised to
  NFC and compared without case, whatever the phone's language (Locale.ROOT
  folding). The hint says «Имя или подпись». When nothing matches, the list
  says so.
- TalkBack reads a row as its name, the label, the state, and the icon's
  name («wb01, Щиток, гараж, Онлайн, Wiren Board»). The generic glyph is not
  read out.
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

- Grouped by device. A group's header starts with the device tile from the
  Devices list, the same component in a smaller size: the device's icon,
  with its state as a dot in the corner in the Devices list's colours
  (online, online as of an old list, offline; no dot while its profile's
  list is not read, and then the generic glyph). After it comes the
  device's whole name, wrapped and never cut. Under the name, a muted line
  shows the device's label, whole and wrapped, taken from the profile's
  last device list. No label, or a list not read yet, means no line. With
  more than one profile on the tab, the header also shows the
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
  connection's name, compared as in Devices search; nothing found says so.
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
version and update, MQTT plugins (scenario 12), components and licenses).

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

A label is free text, such as «Щиток, гараж», that says what a device is and
where it stands. An icon is one of a fixed set. Both are stored on the
frp-jump server, so every phone and the CLI show the same values (see
frp-jump `docs/device-labels-icons_plan.md`). The device name stays as it is.

- **Icons.** All are drawn as stroked glyphs in the app's style: Linux
  (Tux), Wiren Board, server, computer, laptop, router, single-board
  computer, camera, home, phone. Each device reports a detected icon, and
  the owner's choice wins over it. This app reports "phone" for itself in
  every heartbeat. Where neither is set, or the value is one this app
  version does not know, the generic device glyph used today is shown.
- **Is the feature there?** The app asks the server's
  `GET /api/agent/version` for its `features` list once per profile at
  start and on pull to refresh. Until it has answered once in this run
  (offline at start, or enrolled since), it is asked again after each good
  device-list refresh, without holding up the poll. It keeps the last
  answer stored per profile, next to the cached device list, so the
  feature survives a restart while offline. "device-display" in the list turns the feature on.
  An old server has no such list, and the app treats that as "not
  supported". A failed `/version` keeps the last stored value. A profile
  with no stored answer yet counts as "not supported" until the first one
  arrives. The feature is never guessed from the shape of the device list
  or from a 404.
- **Where to edit.** The device card's "⋮" has «Подпись…» and «Значок…».
  Both open the same bottom sheet, «Подпись и значок», with the ✕ in its
  header. «Подпись…» opens it with the keyboard up in the text field;
  «Значок…» opens it with the keyboard down. The phone itself is not in its
  own list, so in this version its label can only be set from another phone
  or the CLI; its icon is the automatic "phone".
- **The sheet has:**
  - A single-line field «Подпись» holding the current label, with the
    placeholder «Например, Щиток, гараж» and a counter up to 64. The counter
    counts code points after NFC, as the server does. Pasted line breaks and
    tabs become spaces. Nothing can be typed past 64. A longer paste is cut
    at 64 code points, and the counter, at 64/64, stays in view so the cut is
    seen. Any other character the server refuses is reported under the
    field (see Saving).
  - A grid of icon tiles, three per row, each with a caption. The captions
    (ru / en) are: «Авто» / "Auto", Linux, Wiren Board, «Сервер» /
    "Server", «Компьютер» / "Computer", «Ноутбук» / "Laptop", «Роутер» /
    "Router", «Одноплатный ПК» / "Single-board computer", «Камера» /
    "Camera", «Дом» / "Home", «Телефон» / "Phone", and «Другой» / "Other"
    (below). The first tile is «Авто». It shows the detected glyph and its name («Авто: Wiren
    Board»), or the generic glyph when the device reports nothing or a value
    this app does not know. The current choice has the accent border.
    TalkBack reads each tile as its caption plus "selected".
  - If the owner's choice on the server is a value this app does not know,
    an extra tile «Другой» with the generic glyph is shown selected. It
    stays as it is unless the owner picks another tile.
  - «Сохранить», enabled once something differs from what was loaded. For
    the label, «differs» compares the field after NFC and trimming with the
    current label, so a trailing space alone changes nothing.
- **Saving.** «Сохранить» sends one call,
  `POST /api/agent/devices/{name}/display`, carrying only the fields the
  owner changed, so an edit made meanwhile on another phone to the other
  field survives. Progress shows on the button. On success the sheet
  closes, and the card, the row and the Connections header show the new
  values at once, taken from the server's answer rather than guessed. On
  failure the sheet stays open with the typed text kept, and the button
  offers to try again. The reason appears where it belongs:
  - a refused label: under the field. The server's five known reasons are
    matched as whole strings and shown in the app's own Russian or English
    text. They are fixed in frp-jump's plan:
    - `label is too long`
    - `label is longer than 64 characters`
    - `label contains a line break or control character`
    - `label contains an invisible or formatting character`
    - `label has no visible characters`

    A reason the app does not know is shown as the server's text;
  - this profile's phone disabled on the server (403): «Этот телефон
    отключён на сервере — изменить нельзя», under the button;
  - anything else: the reason, under the button.
- **Clearing.** An empty field clears the label, and «Авто» drops the
  owner's choice. Neither has Undo: the sheet keeps showing the old value
  until «Сохранить», and the next edit brings it back if needed, as with
  renaming a connection. The Undo rule covers actions that remove something
  at once; here nothing goes before an explicit save.
- **Closing without saving.** ✕, a swipe down, a tap outside or Back closes
  the sheet and drops the draft without asking. This is accepted: nothing
  was saved yet, and typing up to 64 characters again is cheap.
- **No connection to the server.** The sheet opens with the last known
  values. «Сохранить» fails with «Нет связи с сервером — не сохранено» and
  the retry described above. Edits are not queued for later, because
  another phone may change the same device in the meantime.
- **Old server.** If the cached features lack "device-display", the "⋮"
  items are not shown and rows show no label. The owner is not told, since
  nothing can be done about it from the phone.
- **Offline display.** Labels and icons are kept in the cached device list,
  so offline the list, the card and the Connections tab show them as last
  known.
- **Plain text.** A label is plain text everywhere: no links, no markup. It
  is never cut: it wraps in the list row (scenario 3), on the card and in
  the Connections header.

### 12. MQTT plugins in Settings

The MQTT explorer's plugins (`docs/mqtt-contract.md`, "Payload views
(plugins)") are turned on and off, and set up, for the whole app in
Settings. One MQTT connection can differ from that, for example Wiren
Board on for the controllers and off for a home Mosquitto. This replaces
the per-connection Plugins sheet of MQTT stage 6. Status and command
topics and Wiren Board work as that contract describes; only where their
on/off state and settings are kept changes.

**Plugins.** Ids are as stored today. Rows are listed in this order:

| Id | Name (ru / en) | What it changes | Settings |
| --- | --- | --- | --- |
| `wirenboard` | Wiren Board | the «WB» tab (WB devices) | title language, hidden controls |
| `status-command` | «Топики статуса и команд» / "Status and command topics" | the topic switch in the publish sheet | presets, own rules |

A plugin is either on or off. When it is off, nothing of it shows: no
tab, no filters held, no switch in the publish sheet. Its topics stay in
the Tree, which no plugin ever changes. The explorer with every plugin
off is the plain explorer of MQTT stages 1–5.

**The rule: where a plugin is on.** For a plugin P and an MQTT connection C:

- C has its own setting for P (on or off): that setting applies.
- Otherwise the app-level switch of P applies.

This holds when the app level is off too: a connection set to "on"
has the plugin on, and that is how "Wiren Board only on the
controllers" works.

The switch on C stores its own setting only when the new position
differs from the app-level one. Moving it back to the app-level
position removes that setting, and so does «Как в настройках». Changing
the app-level switch never touches settings of connections. A
connection set to "off" stays off when the app-level switch is turned
off and on again. It changes only when the owner changes it there or
resets it.

A connection that has its own setting stored is an **exception**, even
when that setting happens to equal the app level now. It is still
listed as an exception on the plugin's screen and counted in the hints.
That way the owner can see every stored setting that will matter the
next time the app level changes. Settings (presets, rules, language,
hidden controls) are only app-level. A connection overrides only on or
off.

Two pure functions, tested by table, hold this:

- `resolve(app: Boolean, own: Boolean?): Boolean = own ?: app`
- `exceptions(own: Map<connection, Boolean?>): (on: Int, off: Int)`
  counts the connections with `own` not null, split by its value.

| app | own | resolve | exception |
| --- | --- | --- | --- |
| on | none | on | no |
| on | off | off | yes |
| on | on | on | yes (left from before the app level was turned on) |
| off | none | off | no |
| off | on | on | yes |
| off | off | off | yes |

Switch on C moved to v, with the app level at a: `own = if (v == a) null
else v`. «Как в настройках»: `own = null`.

**New plugins.** A plugin added in a later version is on at app level,
and no connection has its own setting for it. It shows only once a
session sees its topics, as the Wiren Board tab does today: the tab
appears when the session sees a control's meta, never takes the screen
by itself, and is absent on a broker without them. A plugin without a
tab (Status and command topics) shows only when one of its rules
matches the publish sheet's topic.

**Scenarios** (taps counted from the screen named first):

1. *Wiren Board off everywhere.* Settings tab → «Плагины» → the switch on
   the Wiren Board row. Three taps. Every open session drops the tab at
   once (see edge cases).
2. *Wiren Board off for the home broker only.* In that broker's explorer,
   "⋮" → «Плагины…» → the Wiren Board switch. Three taps, no leaving the
   explorer. The same sheet opens from the connection's "⋮" →
   «Изменить» → «Плагины».
3. *Wiren Board only on the controllers.* Off in Settings (scenario 1),
   then on for each controller's connection (scenario 2). The rarer
   case costs more taps.
4. *Undo an exception.* In the connection's «Плагины» sheet, «Как в
   настройках» under the switch. One tap. Or from Settings: «Плагины» →
   the plugin → its row in «Свои настройки подключений» → «Как в
   настройках».
5. *An own rule for a non-Wiren Board device.* Settings → «Плагины» →
   «Топики статуса и команд» → «Добавить правило» → templates → «Сохранить».
   The rule applies to every connection with the plugin on. From the
   explorer: "⋮" → «Плагины…» → a tap on the plugin's name opens the same
   screen over the explorer, and Back returns to the sheet.
6. *English titles on a Russian phone.* Settings → «Плагины» → Wiren
   Board → «Язык названий» → English. Five taps. An open WB tab shows the
   new titles at once.
7. *Look at a hidden control.* Settings → «Плагины» → Wiren Board →
   «Показывать скрытые». Four taps. Hidden controls appear in their
   devices, marked.

**Screens.**

- **Settings, group «Приложение».** A row «Плагины» before «Компоненты и
  лицензии», built the same way: a tile, the label, a hint line and a
  chevron. The hint reads «Для MQTT · включено: 2 из 2». When a
  connection has its own setting, the hint adds «· есть исключения».
  A tap opens the plugins screen.
- **«Плагины» (the plugins screen).** A full screen like «Компоненты и
  лицензии»: AppTopBar with the title «Плагины», the subtitle «Для
  MQTT-подключений» and the back arrow. Back closes one level at a time.
  The body is one card of plugin rows with dividers, as in the licenses
  list. Under the card, one muted line: «Отдельное подключение можно
  настроить иначе: «⋮» → «Плагины…» в его обзоре».
- **The plugin row.** It is one component used here and in the
  connection's sheet (below). It shows the name (whole, wrapped, never
  cut), a hint line and a switch on the right:
  - the switch has a touch target of at least 48 dp, with its own
    pressed ripple. A tap on it turns the plugin on or off, and the
    switch moving at once is the feedback;
  - a tap on the rest of the row opens the plugin's screen, with the
    row's own ripple. Every plugin has a screen: at least its «Включён»
    switch and its exceptions. A chevron between the text and the
    switch shows that the row leads on;
  - the hint is the plugin's purpose, as today («Вкладка «Устройства
    WB»: устройства, значения с единицами, переключатели»). Under it, on
    the plugins screen only, a muted line names the exceptions, for
    example «Выключен для 1 подключения» or «Включён для 2 подключений».
- **The plugin's screen.** It has the same top bar, with the plugin's
  name as the title. It is made of groups as in Settings. The first
  card holds one switch row, «Включён» («On»), with the hint «Для всех
  MQTT-подключений, кроме исключений ниже». It is the same switch as on
  the plugins screen. The plugin's own groups follow, and then the
  exceptions. With the plugin off, its groups stay visible and can be
  edited, muted, with the line «Плагин выключен — настройки сохранятся».
  The owner can then set the plugin up before turning it on. This
  differs from today's sheet, which hides the settings.
  - *Status and command topics*: the groups «Готовые правила»
    (the four presets as switch rows, the name with the first rule's
    templates in monospace under it, as today) and «Свои правила» (the
    rule list with drag, a tap to change it, the bin with Undo, and
    «Добавить правило»). The rule sheet, its checks and its texts are
    those of today's Plugins sheet, unchanged.
  - *Wiren Board*: a group «Отображение» with:
    - «Язык названий» with the value and a chevron. It opens the
      ChoiceSheet of Settings with «Как в приложении» (default), «Русский»,
      «English». "As in the app" is the app's language (Settings →
      «Язык»), which today's code already uses. Russian and English pick
      the `ru` or `en` key. When it is missing, the reading falls back as
      the WB contract says: `en`, then the first key. The choice applies
      to device and control titles and to enum titles;
    - «Показывать скрытые» («Show hidden controls»), off by default,
      with the hint «Элементы с hidden в meta. В дереве они видны
      всегда». When it is on, a hidden control is listed in its device
      in `order` like the others. Its title is followed by a muted
      «скрытый» («hidden»), and its error counts toward the device's
      "!". The control works as any other control.
  - *Exceptions*: a group «Свои настройки подключений» («Connections
    set on their own»). It shows only when there is at least one
    exception (as defined above, equal to the app level or not). A row
    per connection holds the device's tile (as in the Connections
    header), the device name and the connection's name in full,
    wrapped, the profile's colour when there is more than one profile,
    and the state «Выключен здесь» / «Включён здесь». It ends with a
    text button «Как в настройках». A tap on it removes the row at once,
    with no Undo: tapping the switch in the connection's sheet brings it
    back, and nothing is lost.
- **The connection's «Плагины» sheet.** It opens from the explorer's "⋮"
  and from the connection's edit sheet, as today. The title is «Плагины»
  and the subtitle is the connection's label, as today. It holds the
  plugin rows only, with the switch showing the state in force for this
  connection. The hint line under each row says where that state comes
  from:
  - «Как в настройках» when the connection follows the app level;
  - «Только для этого подключения» («Only for this connection») with a
    text button «Как в настройках» after it when the connection has its
    own setting. A tap on the button returns the switch to the
    app-level state at once.

  A tap on a plugin's name opens its screen (above) over the sheet, and
  Back returns to the sheet. The presets and own rules leave this sheet,
  since they are app-level now.
- **Failures.** When writing a change fails, the notice «Не удалось
  сохранить настройку: …» appears, as it does today. The switch keeps
  the new state in memory for the rest of the run.
- **No announcement.** The upgrade shows no one-time notice about the
  move to Settings. The «Плагины…» sheet still opens from the same
  places and says where its state comes from.

**TalkBack.**

- Plugin row: «Wiren Board, переключатель, включён, Вкладка «Устройства
  WB»: …, выключен для 1 подключения». A row with settings has the
  action «Настройки Wiren Board» («Wiren Board settings») on the row, and
  the switch is a separate focus.
- In the connection's sheet, the hint is read as part of the row
  («…, только для этого подключения»). «Как в настройках» is a button of
  its own and also the row's custom action «Как в настройках».
- An exception row is read as the device, the connection, the profile
  (when more than one) and the state, with the action «Как в настройках».
- A hidden control: its title, then «скрытый».

**Texts** (new strings; the rest exist).

| Where | ru | en |
| --- | --- | --- |
| Settings row | Плагины | Plugins |
| its hint | Для MQTT · включено: %1$d из %2$d | For MQTT · on: %1$d of %2$d |
| its hint, exceptions | · есть исключения | · with exceptions |
| plugins screen subtitle | Для MQTT-подключений | For MQTT connections |
| plugins screen footer | Отдельное подключение можно настроить иначе: «⋮» → «Плагины…» в его обзоре | One connection can differ: "⋮" → "Plugins…" in its explorer |
| exceptions line (plural) | Выключен для %d подключения / подключений | Off for %d connection(s) |
| | Включён для %d подключения / подключений | On for %d connection(s) |
| plugin screen switch | Включён | On |
| its hint | Для всех MQTT-подключений, кроме исключений ниже | For every MQTT connection except those below |
| settings while off | Плагин выключен — настройки сохранятся | The plugin is off; its settings are kept |
| exceptions group | Свои настройки подключений | Connections set on their own |
| exception state | Выключен здесь / Включён здесь | Off here / On here |
| source: app level | Как в настройках | As in Settings |
| source: own | Только для этого подключения | Only for this connection |
| reset button and action | Как в настройках | As in Settings |
| unreadable file line | Настройки плагинов не прочитались — действуют исходные | Plugin settings could not be read; the defaults apply |
| settings action | Настройки %1$s | %1$s settings |
| WB group | Отображение | Display |
| WB language | Язык названий | Title language |
| its values | Как в приложении · Русский · English | As in the app · Русский · English |
| WB hidden | Показывать скрытые | Show hidden controls |
| its hint | Элементы с hidden в meta. В дереве они видны всегда | Controls marked hidden in meta. The Tree always shows them |
| hidden mark | скрытый | hidden |

**Considered and left out.**

- *Show read-only controls.* They are always shown with their value and
  no control. Hiding them would hide most sensors, which defeats the
  tab.
- *Wiren Board topic prefix (`/devices`).* The prefix is fixed in the
  plugin's filters and patterns (`WbDevices.kt`), its writes and the
  Wiren Board preset. A different prefix only appears on a broker that
  bridges one or more controllers under a prefix. There the tab would
  also have to tell several controllers apart. That is a feature of its
  own, deferred (Decided).
- *Own rules per connection.* Rules are app-level, as the owner asked.
  A rule matches only its own topics, so it does nothing on a broker
  that does not have them. Where it does get in the way, that
  connection can turn the plugin off. Rules stay when the connection
  that was open while they were added is deleted.

**Edge cases.**

- *Plugin turned off while its tab is open.* This can happen from the
  explorer's «Плагины…» sheet, or from Settings in split screen. The tab
  goes, and the explorer shows Tree at the Tree's own path, as today.
  The plugin's filters are dropped. A write already sent is not called
  back, and its answer is no longer shown. Turned on again, the tab
  comes back as soon as meta is seen and does not take the screen.
- *Plugin turned on while connected.* The tab appears from the meta the
  tree already holds, without switching to it.
- *Settings changed while connected.* The language, hidden controls,
  presets and rules apply at once to every open session: titles and
  lists are redrawn from the tree with nothing subscribed again, and
  the publish sheet matches its topic again, as it does today on a rule
  change. A change to a connection's own setting reaches its session
  even with no explorer screen open.
- *Where the change is applied.* Today the session's plugin filters are
  dropped and taken by `MqttViewModel` (with the screen) and by
  `PluginsSheet` (off only). That moves to one app-level observer that
  lives with each MQTT session, not with a screen. It watches the
  store, resolves each plugin for the session's connection, and
  drops the plugin's filters when the plugin is off. When the plugin
  turns on, the observer takes the filters again under the rules of the
  WB contract. Settings, the «Плагины» sheet and the explorer screen
  only write the store. The screen still decides the tab and what is
  shown.
- *Stale meta in a session with no screen.* While a plugin is off, its
  meta filters are not held. Meta outside the open branch and the pins
  then stops updating in the session's tree. Turning the plugin on
  takes the filters again. The broker then re-sends the retained meta
  and errors (Retain Handling 0), so the tree is current again within
  moments, before a screen opens on it. Under 3.1.1 it re-sends them
  too.
- *The Wiren Board preset that turns on by itself* (MQTT contract) is
  now app-level: it turns on once, the first time any connection shows
  the WB tab while Status and command topics is on there. Turned off by
  hand afterwards, it stays off.
- *Connection deleted.* Its own settings go with it, as today, but the
  app-level rules stay. *Profile forgotten*: its connections' own
  settings go. In both cases the same keys are also removed from the
  old `mqtt_plugins.json` while it exists, so a downgrade does not bring
  them back. *Last profile forgotten*: both files are deleted, app
  level included, as the whole file is wiped today, since own rules name
  the owner's topics.
- *TCP connection opened as MQTT*: it has no own setting and follows the
  app level.
- *Own rule deleted and the app closed before Undo is gone*: the delete
  completes (UI rules).

**Stored.** The data is kept in `mqtt_plugins_v2.json`, in app-private
storage:

```json
{
  "version": 2,
  "app": {
    "plugins": {
      "wirenboard": {"on": true, "titleLanguage": "auto", "showHidden": false},
      "status-command": {
        "on": true,
        "presets": ["wirenboard", "zigbee2mqtt"],
        "rules": [{"id": "…", "status": "home/{x...}/state", "command": "home/{x...}/cmd", "except": [], "name": null}],
        "wirenBoardPresetAutoDone": true
      }
    }
  },
  "connections": {
    "<profile>/<connection id>": {
      "plugins": {"wirenboard": {"on": false}},
      "wbTabShown": true
    }
  }
}
```

- Plugin ids (`wirenboard`, `status-command`) and preset ids
  (`wirenboard`, `zigbee2mqtt`, `tasmota`, `state-set`) are separate
  namespaces. Preset ids appear only inside `status-command.presets`.
  The shared word `wirenboard` names two different things.
- Defaults: a plugin is on, the title language is `auto`, show hidden is
  off, no presets, no rules. A plugin or field missing from the file has
  its default. A connection entry with no own setting and the tab not
  shown is not written.
- `version` is 2. A file with an unknown higher version, or one that
  does not parse, reads as the defaults. It is kept as
  `mqtt_plugins_v2.corrupt.json` (the first one only, as snippets do) and
  is not overwritten until the owner changes something. The plugins
  screen then shows one muted warning line under its card: «Настройки
  плагинов не прочитались — действуют исходные». The line goes with the
  first saved change. There is no popup.

**Migration** from `mqtt_plugins.json` (per connection) runs once, at
the store's first read, when `mqtt_plugins_v2.json` does not exist yet.
The v2 file is written at once, before any session reads it.

- On/off is kept exactly. Both plugins are on at app level, as today's
  defaults were. A connection with a plugin off gets its own "off". A
  connection with it on has no own setting. Every connection then has
  each plugin on or off as before.
- Presets and own rules are merged, not kept per connection. Only
  connections that had Status and command topics on contribute: a
  connection with the plugin off had no rules in force, and its stored
  presets and rules are dropped.
  - Presets on: the union of the presets of those connections. A
    preset on for one connection and off by hand for another is on for
    both now. Only the publish sheet's default target changes, and its
    switch still offers the status topic.
  - Own rules: those of each contributing connection, connections taken
    in the file's order (the JSON object's order, as the store's map
    keeps it), rules in their order within each. A rule equal to one
    already taken (same status, command and exclusions) is dropped. Two
    rules with the same status template but different commands are both
    kept in that order. The first one wins, so on the connection that
    had the second one its command topic changes. The owner can reorder
    them. A rule whose id is already taken by an earlier, different rule
    gets a new id. Names are kept.
- The Wiren Board preset that turns on by itself counts as done if any
  connection had seen the WB tab. "The last session showed the tab" is
  copied per connection.
- An unreadable old file gives the defaults, as it does today.
- The old file is left in place. It is updated only by removing keys of
  deleted connections and forgotten profiles (above), so an older APK
  installed again finds the settings as they were at the upgrade.
  Changes made after the upgrade are lost to it. Upgrading again after
  such a downgrade does not migrate again, because the v2 file exists,
  so changes made in the older version are lost to the newer one.
  `wipe` deletes both files. A later slice (10e) deletes the old file
  once a release or two have shipped.

**Acceptance.**

- Upgrade with two MQTT connections, one with Wiren Board off and an own
  rule, the other with the Zigbee2MQTT preset on (Status and command
  topics on for both). After the upgrade:
  Settings → «Плагины» shows both plugins on, and Wiren Board says
  «Выключен для 1 подключения». The first connection has no WB tab. The
  rule and the preset are on the Status and command topics screen and
  work on both connections.
- Settings → «Плагины» → Wiren Board off: an open explorer on WB devices
  falls back to Tree at once. Turned on again, the tab returns without
  taking the screen.
- From a controller's explorer, «Плагины…» → Wiren Board off: only that
  connection loses the tab. The sheet says «Только для этого
  подключения», and «Как в настройках» brings the tab back. Turning the
  app-level switch off and on again leaves a connection set to off still
  off.
- The app level is off and one connection is on: the tab shows only
  there. The Settings row reads «Включён для 1 подключения», and the
  exception is listed with «Как в настройках».
- App level on, a connection set to off, then the app level turned off:
  the connection's row stays an exception («Выключен здесь») although
  it equals the app level now. The Settings hint keeps «есть
  исключения». Turned on again at app level, the connection is still
  off.
- Migration: a connection with Status and command topics off and an own
  rule; that rule is not on the app-level list. Two connections with the
  same status template and different commands: both rules are listed,
  in file order.
- Forget a profile, then install the previous APK: that profile's
  connections are not in its old plugin settings.
- «Язык названий» English on a Russian phone: WB titles and enum titles
  are English where the meta has `en`; a title with only `ru` stays
  Russian.
- «Показывать скрытые» on: a hidden control is listed with «скрытый»,
  and its error marks the device with "!". Off: as today.
- An own rule added in Settings works in an explorer already open.
- Upgrade, then install the previous APK: its per-connection plugin
  settings are as they were before the upgrade.
- TalkBack reads the rows as above. At 200 % font size, plugin names,
  device names and connection names wrap and are not cut.

**Delivery** as small PRs, each reviewed, each a build for the owner's
phone once visible:

- 10a. The store: v2 format, `resolve` and `exceptions`, the migration,
  and the scrub of the old file on delete and forget, with tests for
  each migration case and for the table above. The app-level observer
  that drops and takes a session's plugin filters replaces the code in
  `MqttViewModel` and `PluginsSheet`. Today's Plugins sheet reads and
  writes through the new store, so presets and rules become app-wide.
  Its switches write the connection's own setting.
- 10b1. Settings → «Плагины», the plugin rows, and each plugin's screen
  with its «Включён» switch and the exceptions group. The connection's
  sheet gets the source hints and «Как в настройках». The Settings hint
  «есть исключения» and the exceptions line are in this slice too,
  since own settings can be created here.
- 10b2. The Status and command topics screen gets the presets and own
  rules, which leave the connection's sheet.
- 10c. Wiren Board «Язык названий».
- 10d. Wiren Board «Показывать скрытые».
- 10e. Later, after a release or two: delete `mqtt_plugins.json` and
  the scrub of it.

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
- `GET /api/agent/version` may carry `features`. "device-display" in it is
  the only sign that labels and icons are supported. The list is cached per
  profile.
- `GET /api/agent/devices` items may carry `label`, `icon` (the owner's
  choice) and `kind` (what the device reports). Each may be null or absent.
  The shown icon is `icon`, then `kind`, then the generic glyph.
- `POST /api/agent/devices/{name}/display` takes `{"label": …}`,
  `{"icon": …}` or both. A missing field is left as it is. Null clears it
  (`icon: null` means "automatic"). The answer is the updated device.
- The heartbeat carries `"kind": "phone"`. An old server ignores the field.

## Stored on the phone

| What | Where |
| --- | --- |
| Per profile: device token, cert, key, CA | app-private files, encrypted with a Keystore key |
| SSH private key, remembered passwords | same |
| Per profile: the server's last `features` answer, next to its cached device list (labels and icons included) | app-private storage |
| MQTT plugins: app-level on/off and settings, each connection's own on/off (scenario 12) | app-private storage |
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
10. MQTT plugins in Settings (scenario 12), in slices 10a–10e.

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
- This phone shows with the phone icon in another phone's list and in
  `frp-jump-client devices list` (`phone (auto)`).
- Against a server without labels: no «Подпись…»/«Значок…», no labels, and no
  errors. In airplane mode the sheet's «Сохранить» says it was not saved and
  keeps the text.
- MQTT plugins in Settings: the checks of scenario 12.

## Decided

- Where release APKs live: GitHub Releases of this repository, now public;
  no token in the app.
- Device labels and icons (2026-11-01): a label is at most 64 characters. The
  icon set has `phone`, which the app reports for itself. Support is detected
  through the server's `features` list.
- MQTT plugins (2026-11-01): turned on and off and set up at app level
  in Settings → «Плагины»; a connection may turn a plugin on or off for
  itself, and that wins over the app level; a plugin off has no tab and
  its topics stay in the Tree; new plugins are on and show only once
  their topics are seen.
- MQTT plugins, the owner's answers (2026-11-01): presets and own rules
  are app-wide, not per connection; a connection's own "on" wins even
  with the app level off; the Wiren Board topic prefix is deferred,
  since it needs the tab to tell several bridged controllers apart.

## Open decisions

- frp-jump release tag: the hot-reload change for exposing devices is in
  master but not released; until devices update, their tunnels still restart
  when a grant changes.

## Open risks

- Aggressive vendor firmware may still kill the foreground service; the restart
  covers it but adds a gap of seconds.
- Mobile carriers' NAT usually blocks P2P, so on mobile data most connections
  will go through the server.
- A tunnel is a TCP port on 127.0.0.1, open to every app on the phone while
  it is up; device web UIs need a password of their own.
- WebView cannot filter WebSockets: a compromised device's page can reach
  the other tunnels that are up.

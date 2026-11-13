# frp-jump for Android — contract

An Android app that is a frp-jump device of its own: it lists the owner's other
devices and opens their web UI, an SSH console or any TCP port through frp-jump
tunnels. It only consumes ports; it never exposes anything of the phone.

## Scope

- One user: the owner, reaching only their own devices. APK installed by hand,
  not through Google Play. Android 10+ (API 29); release APKs target arm64.
- Existing frp-jump server, wire protocol version 1 and frp 0.70.0. Device
  labels and icons require the server's `device-display` feature; an older
  server hides that feature without blocking the other flows.
- Russian and English UI; language and light/dark theme follow the system
  unless overridden in Settings.

This contract owns enrollment, profiles, device discovery/display, saved
connections, shared local ports, tunnel lifecycle, app settings, updates and
universal UI rules. Users consume these through Devices, Connections, Sessions,
Settings and the foreground notification. Profile runtimes own each profile's
server/session work; the application owns shared ports, sessions, settings and
pending Undo actions. Screens are not the lifetime owners of running tunnels.

Related surfaces own their specific behavior and inherit [UI rules](#ui-rules):

| Surface | Owner contract |
| --- | --- |
| SSH authentication, reconnect, tmux and session lifetime | [SSH persistence](ssh-persist-contract.md) |
| Terminal keys, panel geometry and accessibility | [Terminal panel](terminal-panel-contract.md) |
| Text drafts, insertion, execution gestures and paste safety | [Terminal text input](terminal-text-input-contract.md) |
| Terminal history and numerical measurements | [Terminal diagnostics](terminal-diagnostics-contract.md) |
| SFTP browsing, transfers and file viewing | [Files](files-contract.md) |
| MQTT sessions, tree, publishing and plugin behavior | [MQTT](mqtt-contract.md) |
| Optional kernel, provider setup, text chat and external adapter | [Assistant](agent-contract.md) |

The numbered scenarios below are stable references, not delivery stages.
Behavioral requirements are normative; this reference is not a runtime
conformance or device-validation record.

## UI rules

All UI surfaces must be concise, understandable and functional. Use the minimum
necessary steps for the task, keep common actions directly accessible, and use
short, clear labels and messages. Visual minimalism must not remove function,
accessibility, truthful waiting/error feedback or required safety warnings.

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
  and Files sessions close only when Undo is gone; the operation is `tunnel_down`, see
  [operations](contracts/surfaces/operations.md)); closing a terminal tab
  or a session on the Sessions tab (its shell ends when Undo is gone);
  deleting files; deleting retained MQTT
  topics. Two confirms are left: "Forget profile" (it wipes the phone's
  keys and only a new enroll brings them back) and the assistant's question before a changing tool call (a remote change cannot be
  undone, so Undo cannot apply; the owner can turn the question off app-wide).
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

If the credentials were saved but starting the new connection fails, show
"Connection saved, could not start" instead of claiming the keys were lost
or suggesting server-side deletion. Keep its identity and keys, offer manual
retry of that saved connection without another enrollment request, and keep
working connections selected and running. A saved pending connection stays
available after leaving the form or restarting the app; starting it remains
an explicit action. Distinguish this from failure before credential storage.

On upgrade from the single-profile app, its enrollment becomes the first
profile without re-enrollment.

### 2. Switching profiles

- The device list shows one profile at a time; a switcher at the top changes it.
- The visible profile polls every 2 s while the app is open. Other profiles
  poll only while they have tunnels up, at the background interval.
- "Connections" and the notification show tunnels of all profiles, each
  marked with its profile colour.
- Loading prepares every runtime before publishing the batch. Polling,
  startup cache/features work and runtime collectors start only after the
  published state owns the runtimes. Failed or cancelled preparation closes
  all candidates, including their tunnel managers' idle observers; a retry
  must not leave an older owner running. Preparation finishes file IO before
  allocating coroutine jobs. Duplicate ids in a damaged index are rejected
  before opening any candidate. Transient preparation failure does not wipe
  enrollments, port reservations or restore intent. This does not define
  recovery of an enrollment saved before a later activation failure.

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

- A connection is: device, port on the device, kind (web / SSH / TCP / MQTT), name,
  plus for web the HTTPS flag (not asked on 80, always HTTP, and 443, always
  HTTPS) and for SSH the user.
- "New connection": pick kind, type a port or tap a preset (web: 80, 443,
  8080, 1880, 3000; SSH: 22, 2222; TCP: 502, 5432; MQTT: 1883), give a name, and
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
  Saved connections are the source of truth for pins. Before any profile
  allocates Auto ports, restore all published profiles' pins, including off
  connections, into the shared map. Preserve known held allocations; reject
  conflicting reservations instead of moving a live Auto tunnel.
  The connection and reservation map are not a crash-atomic two-file save:
  if the connection was committed but the map write failed, report it as
  saved with port repair pending, keeping its identity. The next planning
  restores reservations before allocation; map I/O failure stops the new
  plan rather than starting frpc with uncommitted allocations.
- A failed visitor start is cleared after frpc confirms that same visitor
  successfully started on retry. A different visitor's success must not hide
  the failure, nor overwrite a later authentication/transport/device error.
  If another required visitor still has a startup failure, keep it visible.
  Existing SSH tabs are not evidence of listener recovery and are not reset.
- The card's header shows the device's icon on a tile, then the whole name and
  under it the whole label (wrapped, never cut). The top bar's "⋮" holds
  «Подпись…» and «Значок…» (scenario 11).
- Rename, change and delete a connection from the card or the Connections tab.
- Web → in-app WebView; HTTPS with a self-signed certificate is remembered on
  first visit. A device answering an HTTPS connection in plain HTTP is
  told as such, with "Open over HTTP". If saving Trust in the certificate fails,
  the page stays closed (fail closed) and the owner sees "Could not save trust
  in the certificate (<kind>). Not opened." with "Try again"; the kind is
  "no space left", "no access" or "write error" (one wording, shared with host
  keys), never a path or an exception message. SSH → built-in terminal. MQTT → explorer. TCP → shows and copies the local address.
  Existing TCP connections, including port 1883, stay TCP; “Open as MQTT”
  converts the kind and opens the explorer without changing the wire protocol
  or requiring an active tunnel to stop. See the [MQTT contract](mqtt-contract.md).
- A web page's `alert`, `confirm` and `prompt` (and the question when leaving
  a page) are shown by the app, not by WebView, so a page cannot pose as one of
  the app's own dialogs. The title is always the asking page's origin
  (`scheme://host[:port]`, an international host in its ASCII form, never the
  title the page sets), or "Page" when the origin is empty or opaque. The
  message is plain text (no links or markup), cut at 1000 characters; a prompt
  keeps its default value (cut the same way). `alert` has OK; `confirm` and
  `prompt` have OK and Cancel; leaving a page asks "Leave this page? Changes
  you made may not be saved." with Leave and Stay (the page's own wording is
  not shown). From the second dialog of the same page on, the dialog has
  "Don't show more dialogs from this page". Once chosen, the page's further
  dialogs are answered at once until the page loads again or the screen is
  left: alert OK, confirm and prompt Cancel, leaving the page allowed.
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
- Bringing a connection's tunnel up for these actions (and for "Use key login" and "Change login") is the `tunnel_up` operation of the
  [operations surface](contracts/surfaces/operations.md): same order, same 45 s wait for the tunnel, same notices as before it was
  an operation. The assistant does not use it.

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

The [SSH persistence](ssh-persist-contract.md),
[terminal panel](terminal-panel-contract.md),
[terminal text input](terminal-text-input-contract.md) and
[diagnostics](terminal-diagnostics-contract.md) contracts own the detailed
session, input and measurement guarantees. The entry points and common
terminal behavior are summarized here.

User + key or password, one generated ed25519 key per phone (shared
by all profiles), host keys pinned per profile+device+port, passwords stored
only on "remember", several sessions at once. A row of snippets (saved
commands, owner-defined) above the extra keys; the sessions as a row of tabs
above the terminal, where "+" opens another session to the same device with
the same login (long press: as another user).

Automatic OSC 52 clipboard writes are accepted only from the selected SSH
tab. Switching tabs revokes the previous tab's authority immediately; a
removed or replaced terminal cannot copy through a late callback. Leaving
the terminal screen or putting the app in the background does not revoke
the last selected tab. Explicit selection Copy remains unchanged, including
copying from an ended terminal. The existing shared clipboard writer cap
of 64 × 1024 UTF-16 code units and copied-text notice remain unchanged; this adds no
prompt, opt-in, foreground check or new limit for the selected tab.

The extra keys under the terminal are one row: ^C, Paste, ← ↑ ↓ →, ⌫, ⏎
and ⌨, which shows and hides the soft keyboard (the bar has no keyboard
button). Paste works as the selection menu's paste and is disabled while the
clipboard has neither text nor an image. Any paste drops all trailing line breaks and blank
lines, so a single line never runs by itself. With bracketed paste enabled by the remote side it
goes as is; several lines without it show a sheet first, "You are pasting N
lines" (ru «Вы вставляете N строк») — "Without bracketed paste each line runs
at once": Paste (as is, the lines run), Remove line breaks (each line trimmed,
blank lines dropped, joined by a single space; no shell syntax, so a trailing
`\` continuation stays); the sheet's ✕ or a swipe down cancels. In a tmux
session the bracketed flag is not trusted (tmux keeps it on toward the app
whatever the pane asked), so several lines always show the sheet there; a
multiplexer started by hand in a plain-shell tab can't be detected.

**Insert image.** ⋮ → Insert image opens the system photo picker (no storage
permission) where up to 10 photos can be chosen (the platform's own lower limit
where there is one); so does a long press on the Paste key (TalkBack action "Insert image")
and the image button of the text editor, all by one rule and one flow. A tap on Paste is unchanged. The long press and its TalkBack action exist only while
the picker is available; otherwise the key has no long-click action, and a long hold
never pastes. With an empty clipboard the key says so and mentions the long press. With an image and no text on the clipboard, Paste (key or the
selection menu) offers "Insert from clipboard?" (ru «Вставить из буфера?») instead of pasting; the one offer covers a single image or several (at most 10), so the clipboard
is not read until the owner confirms (reading it shows the system's "pasted from clipboard"
notice); the batch bar then shows "Image N of M"; with any
text it pastes the text as before.
The picture is re-encoded without metadata (EXIF, GPS, XMP, text chunks and
colour profiles are not carried): rotated upright, the long side at most 2000
px; PNG stays PNG, a GIF becomes its first frame as PNG, anything else becomes
JPEG, or PNG when it has real transparency; the encoder's colour profile and
any Ultra HDR gain map are removed too. Input up to 40 MiB and 250 MP,
output up to 8 MiB (a PNG over it is sent once as JPEG). It is uploaded on a
new SFTP channel of the tab's open connection (no new login) to
`~/.cache/frp-jump/images/` and its absolute path (never `~`) is inserted
exactly as Paste inserts one line of text, without Enter. Several photos are one batch: they are
read, encoded and uploaded one at a time over that one channel (never all in memory), and
all the successful paths go in ONE paste, joined by single spaces in the order the picker
returned them, never by newlines (Claude Code turns "path1 path2" into "[Image #1] [Image #2]"
and keeps newline-separated paths as text). The item and the editor's button are dimmed, the Paste long press does nothing, and
the offer is not offered, unless the tab is Open and has no image or batch on its way
(also after Cancel, until that job has really ended). A bar shows "Preparing image…" and
"Uploading image… N%" with Cancel (a batch: "Image N of M · P%", ru «Изображение N из M · P%»,
and "Image N of M · preparing" while one is read); a failure inserts nothing and shows one
short reason. In a batch an unreadable image is skipped and a failed upload ends the batch:
the images that went are pasted and one notice says how many were not sent and the first
reason ("2 of 5 images were not sent: The image is too large."); if none went, the single
reason shows. Cancel stops the whole batch: nothing is inserted and the files it made are
removed as far as the connection allows (see the SSH contract). The image or batch goes to the tab it was started on only, never to
another tab or the same tab's next connection, and leaving the terminal screen
or closing the tab (also with its Undo pending) cancels it. The device-side
rules (modes, no overwrite, cleanup after 60 minutes) are in the [SSH contract](ssh-persist-contract.md#remote-image-files).
From the text editor the path also goes straight to the terminal, in the tab that
started it and without Enter; the editor's draft is not read, changed or sent. The
progress bar and Cancel show in text mode too (above the editor; in the full-screen
editor, inside it). The remote program decides what to do with the path.
Image notices also show over the full-screen editor. One batch per tab at a time, at most
10 images (a picker that returns more is cut to the first 10, with a notice).

Additional
rows start collapsed; the small ⋯ above the main row explicitly toggles Esc,
Tab, sticky Ctrl and
Alt, Shift, ^D ^Z ^R, Home End PgUp PgDn, `| ~ / -`. The arrows and ⌫ repeat
while held (400 ms, then about 15 a second); the other keys do not.

Settings → Terminal → Direct input is on by default, including when an
existing settings file has no value for it. An explicitly stored on/off
value is preserved. Off uses ordinary text input:
the keyboard may compose a word and offer suggestions before committing it
to the terminal. On requests no suggestions and forwards composing changes
immediately, as in earlier versions. The choice is app-wide and persisted;
changing it finishes the old composition and restarts the keyboard input
connection, not the SSH session. Hardware key events pass through in both
modes. With direct input off, sticky Ctrl/Alt/Shift applies when the keyboard
commits the text, not while it is still composing it.

**History limit.** Settings → Terminal → History rows offers 500, 1000,
3000 and 10000, default 1000. This is the main Termux buffer limit including
visible rows, not a guarantee of that many off-screen rows. Older files
without the setting use 1000; invalid values/types are normalized without
resetting unrelated settings. New terminal sessions capture the setting;
changing it never trims or recreates an open terminal. Automatic reconnect
keeps the same emulator and limit; a new or explicitly replaced session uses
the current preference.

**Terminal diagnostics.** The SSH card's short response/down/up line opens
per-tab numerical measurements. Traffic is terminal payload only, aggregated
by profile/device/port; files, MQTT, web and SSH encryption overhead are not
counted. Missing/stale measurements are unavailable, not a successful zero;
a genuinely idle observed channel may show zero. Reports include recent
response mean/max/last/age, probe failures, input-to-stream-write, the tmux
before-write guard, incoming queue/emulator processing, draw CPU and buffer
state. CPU time is not physical display latency; accepted writes are not
remote acknowledgements. Numbers stay in memory, no text or credentials are
recorded, and keepalive/reconnect policy is unchanged. See the
[terminal diagnostics contract](terminal-diagnostics-contract.md) for probe,
freshness, visibility and measurement boundaries. SSH metrics must not hide
an independent tunnel/listener error.

**Keys and Text.** Keys is the initial input surface. Short ⌨ shows/hides
the system keyboard; hold ⌨ or use ⋮ → Text input to open the visible
multiline editor. Settings → Terminal explains the gesture. No separate
Keys/Text selector row is shown. Text hides all terminal-key and command-chip
rows; >_ returns to Keys without submitting or discarding the draft, with
additional rows collapsed again. This never guesses the remote
application or changes the saved direct-input preference. The editor uses
ordinary IME composition and suggestions; typing and editor Enter remain
local. Expand opens the same draft and selection full screen. Collapse or
Back returns to compact Text; switching to Keys does not send or discard it.
In Keys mode, terminal buttons address the terminal, not the retained draft.

Short Insert captures the exact SSH session and draft revision, applies the same
paste checks described above, and adds no Enter. Revalidate session identity,
open state and pending-close status before dispatch. A confirmation can never
redirect insertion to a newly selected or replacement session. Accepted local
paste clears only the captured revision, not newer edits. Cancellation,
non-writable destination and failed local paste keep the draft. Local
acceptance is not proof of remote receipt or processing. Drafts, selection
and surface state live with each SSH session only in memory, not saved-state
bundles, settings, logs or clipboard; closing or replacing a session releases
its draft. They survive screen/configuration changes while the session owner
lives, not process death. Text mode contains only the editor; command chips
remain available in Keys and the menu. An empty snippet list never
shows the «+ Добавить команду» row; existing command chips remain, and
⋮ → Commands opens their manager even when there are none.

A tap on the command line moves the shell's cursor there: the app sends as
many ← or → as there are characters between the cursor and the tapped cell
(a wide character counts once, at most 1000, which stays under the terminal's 4 KB write queue), and the shell moves its
cursor and echoes it; no other feedback. It counts only on the cursor's row
and the rows wrapped into it at the margin (one logical line); a tap past
the end of the text goes to its end. A row the shell broke with a real line
break (a pasted multi-line command, a `for` loop's `>` lines), output above
and the empty screen below keep the usual tap: it shows the keyboard (the
tap on the command line shows it too). The arrows are plain keys: a held
Ctrl / Alt / Shift stays held. Until their echo shows (at most 400 ms), another
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
- Launcher badge: the notification carries the number of tunnels up in all
  profiles (`setNumber`), and its channel allows badges; the owner's app and
  channel badge settings still apply. Launchers that show numbers (for example
  Samsung) show it on the icon; stock and Pixel launchers show a dot, and the
  long-press menu shows the notification. The icon is never swapped.
  - It counts tunnels, not connections: several connections on one device port
    share one tunnel, and a tunnel is what runs and what the service keeps
    alive. It is the same number the title says as "N tunnels", so they never
    disagree; tunnels hidden while their disconnect waits for Undo are not
    counted, as in the title.
  - While none is up (restoring after a restart, disconnecting) the
    notification carries no number (0); a launcher still badges it: a dot, or
    1 where it shows numbers. The service stops with the last tunnel and the
    badge goes with its notification.
  - The restore notice is on the same channel and badges without a count.
- Background poll interval: off / 30 s / 1 min / 5 min / 15 min.
- If Android kills the process, or the phone reboots, while tunnels were up:
  the service starts again by itself and brings back the same connections on
  the same local ports. "Disconnect all" or disconnecting the last tunnel
  clears this.
- The app points to the battery-optimisation exemption when the system may
  stop the service.

### 9. Settings

Settings are grouped by purpose: profile (name and colour, forget profile); tunnels
(direct connection, local port range); background (poll interval, battery);
security (phone SSH key, unlock with fingerprint, lock after); app (language, theme,
version and update, MQTT plugins (scenario 12), components and licenses).

- "Forget profile": disconnects its tunnels, deletes the phone from that
  server (offline: local wipe only), wipes its credentials and cached lists.
- Fingerprint unlock: off by default; when on, the app asks for the device's
  biometrics or screen lock. Only the screen is locked: running tunnels, the
  service and the notification's actions are not affected.
  - It asks on every cold start of the process, and when the app comes back
    after more than the interval out of sight. The interval is "Lock after"
    (shown only while the lock is on; its value is kept when it goes off):
    Immediately (any absence), 30 s (default), 1 min, 5 min, 15 min. There is
    no "never" and no "only on start". The row's hint names the interval.
  - Running tunnels keep the process alive, so a return to the app is not a
    cold start. The prompt's own stops and starts are not an absence: while
    it is up they are ignored, and if it ends with the screen out of sight
    (the PIN screen of older Android is an activity of its own), that stop
    counts like an app-opened window (next bullet), so Immediately does not
    lock again at once.
  - A window the app opened itself (photo or file picker, Share, installer,
    system settings, an external browser or SSH client) counts as an absence
    only after 30 s, or the chosen interval if that is longer. The app marks
    such a launch at `MainActivity.startActivity` and `startActivityForResult`;
    the mark is dropped if the screen does not go out of sight within 5 s.
  - A window the app launches by itself (a viewer or Share after a download,
    the installer, a web page's redirect to an external app) does not open
    over the lock screen. It is held, at the same two `MainActivity` calls,
    when the lock is engaged, and also when the screen is out of sight and
    the lock would engage on return (the setting on and the interval, with
    the app-opened floor, already elapsed). Only the latest is kept. The next
    start locks as usual, and after a successful unlock the held launch runs
    once, as an app-opened window (marked only if it really started). A held
    launch that fails when it runs (no app for it) gives no message. It is
    dropped, and never runs later, when:
    - the app leaves the screen while locked, including while the prompt is
      up: with the prompt up the drop comes when the prompt ends with the
      screen out of sight, cancelled or answered. A launch held while out of
      sight and not yet prompted is kept for the next start's lock and
      prompt;
    - the activity is destroyed;
    - the lock goes off other than by a successful unlock ("no screen lock
      any more", the setting turned off).

    A held launch that expected a result (a web page's file chooser) is
    answered as cancelled when it is dropped. Launches with the lock off, or
    on screen and unlocked, or out of sight before the interval has elapsed,
    are unchanged. The prompt's own screens are never held: the PIN fallback
    of older Android is the system's confirm-credential screen, told by the
    action the system itself gives (`KeyguardManager`; the platform constant
    if it gives none), by action only, while our prompt is active and on the
    result path only.
  - A prompt whose activity is destroyed before the answer (for example by a
    font-scale change) is abandoned: its answer is lost in the system
    library, so it is dropped without a result and unlocks nothing. A locked
    app stays locked and the new activity asks again; a prompt to turn the
    setting on does not turn it on. An answer that reaches the abandoned
    request unlocks nothing. A prompt that replaces one still open ends the
    old one as Cancelled. A prompt requested after the activity's state was
    saved is Cancelled at once ("Not unlocked. Tap “Unlock” to try again." /
    "Not turned on: not confirmed.").
  - A phone whose screen lock was removed turns the setting off.
  - A locked app shows none of its own separate windows. The screens stay
    composed under the lock screen (back stack, open pages, polling), but the
    windows they would draw are left out: they take no touch, key or TalkBack
    focus. These are dialogs, bottom sheets, dropdown menus and popups, and a
    web page's `alert`, `confirm`, `prompt` and leave-page question.
    - A dialog or sheet comes back after unlock as it was, with what was typed
      in it (state kept in the window's content is `rememberSaveable`). A menu
      or popup of a screen that was open at the lock comes back too; a menu
      inside a sheet may close instead. A page's script
      waits for its question until then, and it is cancelled when the page or
      screen goes away first.
    - Windows that are not Compose windows are handled one by one: the
      selection toolbars of the terminal, a web page and text fields (system
      action modes) end at the lock, and one that starts while locked ends at
      once; Termux's selection handles are stopped; the page's selection is
      cleared; the focus is cleared and the soft keyboard is kept off the
      window (`FLAG_ALT_FOCUSABLE_IM` while locked, cleared on every way out of
      the lock; the keyboard and focus requests wait for the unlock); the
      autofill popup is cancelled. Messages are dropped while locked, and the
      toast fallback for messages (no screen of the app started) is dropped
      too.
    - The app's own windows are made through `AppAlertDialog`, `AppDialog`,
      `AppModalBottomSheet`, `AppPopup` and `AppDropdownMenu`
      (`ui/LockedWindows.kt`), which read `LocalAppLocked`. A source-scan unit
      test flags a direct use of a listed window API elsewhere, as a tripwire:
      it does not see windows made by other means.
    - Not covered: the biometric prompt and other windows of the system are
      not the app's and are not affected. WebView's own native pickers (such
      as a date or time input), if Chromium shows one for a page, are not
      held by this gate.
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
  version does not know, the generic device glyph is shown.
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

    A reason the app does not know is shown as the server's text, under
    the field when only the label was sent, otherwise under the button;
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
  the retry described above. A connection that broke while the answer
  was coming says what the system says instead, since the server may
  have saved it. Edits are not queued for later, because
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

Plugin behavior belongs to [MQTT payload views](mqtt-contract.md#payload-views-plugins).
This section owns app-level settings, connection overrides, their UI and stored
compatibility. Plugins never change the generic Tree; with all plugins off,
the plain explorer still works.

#### Controls and common tasks

Settings → Plugins opens app-level switches and exceptions. In order:

| Stored id | Name | Purpose |
| --- | --- | --- |
| `wirenboard` | Wiren Board | WB devices tab |
| `status-command` | Status and command topics | Publish-sheet topic selection by rules |

- The Settings row is before Components and licenses. Its hint reports
  “For MQTT · on: N of M”, adding “with exceptions” when any are stored.
- The Plugins screen has a back arrow, the subtitle “For MQTT connections”,
  one card of plugin rows and a hint that a connection can differ through
  its explorer's ⋮ → Plugins. Back closes one level at a time.
- A plugin row shows its whole wrapped name, purpose, a chevron and a switch.
  The switch has its own pressed feedback and at least a 48 dp touch target;
  tapping the rest opens that plugin's screen. Switching visibly updates it
  immediately. Each plugin screen has an app-level On switch and, when
  present, “Connections set on their own”.
- Each exception names the device and connection in full, wrapped, with the
  device tile, profile colour/name when there is more than one profile, and
  “On here” / “Off here”. “As in Settings” removes the exception immediately,
  without Undo: nothing is lost and the connection switch can restore it.
- A connection's Plugins sheet opens from its explorer's ⋮ or its edit
  sheet. The subtitle names the connection. Switches show the effective
  value; hints say “As in Settings” or “Only for this connection”, with a
  separate “As in Settings” reset button for an exception. Tapping a plugin
  name opens its screen over the sheet; Back returns to it.
- Status and command presets and own rules are currently edited in that
  connection sheet while the plugin is on there. They are **app-wide**, not
  specific to the connection used to open the sheet. Presets are switches;
  own rules can be added, edited, reordered by drag and deleted with Undo.
  Template validation and publish warnings follow the MQTT contract.
- To disable WB everywhere, use Settings → Plugins → its switch. To disable
  it for one broker, use that explorer's ⋮ → Plugins → its switch. To enable
  it only for controllers, turn it off app-wide, then on for those
  connections. Reset an exception in the sheet or the plugin's Settings
  screen. No upgrade announcement interrupts these paths.
- TalkBack exposes a labelled settings action on the row and a separate
  switch focus. The purpose, exception count and connection-state source are
  read with the row. Reset is both its own button and a custom row action;
  exception rows identify device, connection, profile when needed and state.
  Names must remain readable at 200% font, not truncated.

Relocating preset/rule editing to the plugin's Settings screen, and exposing
WB title-language and hidden-control settings there, are deferred UI work,
not available controls promised by this reference. Their stored fields remain
part of the compatibility format below. In the deferred Settings editors,
plugin groups must stay visible, muted but editable while the plugin is Off,
with “The plugin is off; its settings are kept”, so it can be set up before
being enabled. Turning a plugin Off retains its settings; this does not make
today's connection-sheet editor available while Off. These conditional
requirements do not promise a delivery date. Bridged WB topic prefixes and
per-connection own rules are also not provided; the WB prefix stays `/devices`.
WB read-only controls remain visible as values, without a control to write them.

#### Resolution and session ownership

For plugin P and connection C, `resolve(app, own) = own ?: app`. An explicit
connection On wins even if the app-level switch is Off. A stored own value
is an **exception**, even when it happens to equal the app-level value now.
Count exceptions by stored non-null value, split into On and Off:

| App | Own | Effective | Exception |
| --- | --- | --- | --- |
| on | absent | on | no |
| on | off | off | yes |
| on | on | on | yes |
| off | absent | off | no |
| off | on | on | yes |
| off | off | off | yes |

Moving the connection switch to `v` stores `own = if (v == app) null else v`.
“As in Settings” stores null. Changing the app-level switch never edits
connection overrides: an own Off survives app Off → On. Connections override
only On/Off; presets, own rules and other plugin settings are app-level.
A TCP connection opened as MQTT without its own setting follows the app level.
New plugins default On with no connection overrides and show only when their
topics are seen/matched, never by taking the screen automatically.

The application owns one settings observer per live MQTT session, independent
of explorer screens. It resolves settings and drops/takes plugin filters;
screens write the store and decide what is shown. Changes reach sessions even
with no explorer open. Turning a plugin off removes its tab/filters and its
publish-sheet topic switch at once;
an open WB tab returns to Tree at Tree's own path. A write already sent is not
recalled, and its answer is no longer shown. Turning WB on uses meta already
in the tree without selecting its tab. Retaken meta/error filters request
retained data again (MQTT 5 Retain Handling 0, also re-sent under 3.1.1).
While Off, meta outside open branches and pins can be stale until re-read.
Presets and rules take effect immediately in every live session and re-match
an open publish sheet without resubscribing. The retained conditional WB
title-language and show-hidden semantics likewise require changes to redraw
titles and lists immediately in every live session from the existing tree,
without resubscribing, independently of the deferred Settings UI. Title language
is app language (`auto`), Russian or English, applying to device, control and
enum titles with the MQTT contract's fallback order. Show-hidden retains the
MQTT contract's ordering, hidden marker, error counting and normal-operation
constraints, not a bypass of readonly or exact owner/current-meta checks.
The WB preset auto-enables once for the app when a connection first shows the
WB tab with Status and command topics on there. Turning that preset off by
hand afterwards keeps it off.

#### Storage, failure and migration

`mqtt_plugins_v2.json` is app-private. Its version and field names are retained:

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

This is a shape example, not the defaults: plugins default On, title language
`auto`, hidden controls Off, no presets or rules. Missing plugins/fields use
their defaults. Entries with no own setting and no previously shown WB tab
are not written. Plugin ids (`wirenboard`, `status-command`) and preset ids
(`wirenboard`, `zigbee2mqtt`, `tasmota`, `state-set`) are separate namespaces;
preset ids occur only in `status-command.presets`.

An unparsable or unknown higher-version file uses defaults, preserves the first
`mqtt_plugins_v2.corrupt.json` copy and is not overwritten until the owner
changes something. The Plugins screen shows “Plugin settings could not be
read; the defaults apply”, without a popup; the warning goes with the first
saved change. A failed setting write reports “Could not save setting: …”; the
new value remains in memory for that run.

On first read, if v2 is absent, migrate `mqtt_plugins.json` before sessions
consume settings and write v2 immediately:

- Both plugins start On app-wide. Old Off becomes own Off; old On follows
  the app. Every connection keeps its previous effective On/Off.
- Only connections with Status and command topics On contribute presets
  and rules. Presets are their union. Rules follow JSON connection order,
  then each connection's rule order. Equal status/command/exclusions are
  deduplicated, names retained, and colliding ids for different rules renewed.
  Different commands for the same status template remain in order; first
  match wins. This can change a connection's default command target; the
  owner can reorder rules and the publish sheet still offers the status topic.
- The preset auto-enable is marked done if any old connection had seen the
  WB tab; the per-connection “tab shown” state is carried over. An unreadable
  old file gives defaults.
- The legacy file stays in place. Only deletion/forget scrubs its keys;
  subsequent setting changes write v2 only. Downgrading sees pre-upgrade
  settings, not newer edits. Upgrading again does not re-migrate while v2
  exists, so changes made in the older APK are not imported again.
- Deleting a connection removes its own settings in both files, not app
  rules. Forgetting a profile removes its connections in both files.
  Forgetting the last profile wipes both files and app-level rules because
  those rules contain the owner's topics. Closing the app during a rule's
  Undo interval still commits the delete under the shared UI rules.

## Optional LLM assistant

The optional module is off by default. Settings exposes provider setup,
secure key storage, model discovery, effort and context limits, the owner's own
instructions for the model (guidance only, sent with every message, never
authority over permissions or confirmations), plus a Chat
entry for explicit conversations. Automatic working summaries use the chat model
or an optional other model of the same provider. The configurable trigger defaults
to 80% of estimated context use, including output reserve; summary calls have no
tools and incur an additional model request. Tools offers independent, default-off Web
search, Read page, Inspect image, Profile information and SSH command switches, with
public/private-data disclosure and model token-charge notices. SSH command carries
an explicit FULL SSH ACCESS warning: commands can read/change remote systems and
files within account rights; commands and output may contain secrets and go to
the configured model. Before such a changing call runs, the owner is asked (Allow, For session, Always, Decline) unless the app-wide
"Ask before changing commands" switch on Tools is off or the owner already answered For session for that connection (until Chat is left); see the [assistant contract](agent-contract.md#confirmation-of-changing-calls). These operate only during explicit Send; opening
or saving makes no request. Switches autosave through the same Tools binding in
Settings and Chat. Actual committed values, pending progress and inline failures
come from one snapshot; no Save button or pre-commit checked value. Storage runs
off Main; successful completion presents current canonical values with pending
cleared together. Back/recreation retain parent-owned saves; actual parent pop
cancels publication, without promising rollback of blocking storage.
Each Send keeps its admitted tool snapshot and may
finish with it; committed changes apply only to the next Send without clearing
history or draft. Stop/pop, assistant disable and provider changes still revoke
work. Search has no shared results cache; process quotas/cooldowns remain. Image
requests return bounded unverified model text, including inability/refusal, not
claims of visual understanding. Technical failures give understandable sanitized
descriptions and known status/limits, never silent success or raw server errors. Chat also opens Tools without popping its conversation;
returning from Tools keeps that owner/history. Actual Chat exit cancels execution
and releases route memory; one encrypted local conversation is restored on return.
Restoring makes no provider/tool call. Chat's menu clears it with shared Undo;
read/write failures stay visible and accepted unsaved data remains available in
memory for retry. The file contains one canonical conversation, not separate UI
and model histories. Context/output limit changes retain continuity; changing API
root/model keeps previous messages readable but begins a new active context with
a notice, without forwarding old private continuation or requiring deletion.
Before old context is evicted, Chat saves a working summary and its covered
position in the same encrypted document, then uses it with recent complete
exchanges. Original records remain readable within finite archive/file limits;
they are not deleted by summarization. The summary preserves task/target facts,
observed and uncertain outcomes, and remaining steps. Failure retains history
and the last useful summary with recovery feedback; Stop suppresses late results
under the existing actual-exit ownership. See [summary behavior and limits](agent-contract.md#automatic-working-summaries).
The information tool reads the same derived application API as Devices and
Connections, including devices without saved connections and distinct profiles.
Optional text/profile selection searches that complete available source before
bounded whole-record paging. One or several suitable results are legitimate;
profiles/connection identities remain distinct and larger sets expose continuation.
Missing sources do not become a false “not found”. Received selected facts remain
available in Chat if the subsequent model request fails, under the same memory,
aggregate-history and authority lifecycle. Each actual invocation makes one ordinary all-profile device-list pull, with
existing authentication, feature checks, cache writes and error/stale behavior;
no extra tunnel polling/probing or control is started. Information-owned request
start checks use the existing Chat authority guard (a start admitted just before a
commit may begin and is then cancelled); cancellation retains its slot until the
HTTP exchange has actually returned, without cancelling unrelated UI/background
requests. Missing information stays
unknown. A fixed error kind (`{"kind": ...}`, listed in the agent contract) and
typed app states are sent, not raw UI/server/exception diagnostics or credentials.
It is not an atomic live inventory or proof of remote service readiness. The
separate SSH-command tool executes one noninteractive command on a saved SSH
target from any profile over an already ready tunnel, with ordinary login/pinning,
bounded separate stdout/stderr and honest exit/unknown status. It owns its client
and cancellation/actual cleanup, not Terminal or Files clients. No tunnel-control
tools are available.
Enabling, opening settings and saving do not start provider requests. Fetch models
is an explicit setup action, available even while the module is off. Its target-bound credentials,
cancellation and current execution boundaries belong to the
[assistant contract](agent-contract.md), not to tunnel permissions.

## Wire behaviour the app must match

- Saved device Bearer token on authenticated `/api/agent/*` operations.
  `GET /api/agent/version` is unauthenticated; enrollment uses its one-use
  token in the `POST /api/agent/enroll` body before device credentials exist.
  Control-server calls require HTTPS, including stored URLs, and never follow
  redirects that could forward either token.
- `protocol_version` must be checked on every response; a mismatch stops
  applying changes but leaves running tunnels alone. This remains a normative
  compatibility requirement, not a claim that every current response type
  carries the field. The breadth of “every response” versus the existing
  wire shapes/check sites remains unresolved for the separate compatibility
  audit; it is not relaxed by this rewrite.
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
| Assistant provider metadata and root-bound sealed credential | app-private atomic document; no plaintext key |
| Current assistant conversation, draft, tool receipts and opaque continuation | encrypted atomic document in app-private no-backup storage; explicit clear with Undo |
| Profiles, cached device lists, connections, local port map, host keys, snippets, active tunnels to restore, "keep through the server" choices, settings | app-private storage |

Permitted app-originated network flows are calls to the configured frp-jump
servers, traffic through the owner's tunnels, public GitHub update checks and
requested APK/checksum downloads, and explicit provider model discovery and
chat (including explicitly invoked profile information and SSH commands/output), and explicitly enabled
assistant public HTTPS read/search/image requests.
These optional credential-free public requests and same-model image analysis
follow the [assistant contract](agent-contract.md#optional-public-web-tools), not
connection permissions. They cannot access local/tunnel/file targets and perform
no background activity.
Discovery and explicit chat send the provider credential only to its configured
target under the [assistant contract](agent-contract.md). Discovery does not send
chat; Chat sends explicit text/history, fixed instructions, the owner's own
instructions when the owner set them (they go with every message) and enabled
tool schemas/results, including private profile/device/connection information only
when the explicitly enabled information tool is invoked, and commands/output when
the SSH tool is invoked. App/connection data is never automatically attached.
The SSH tool grants remote account-level access, not tunnel control. No provider request is
implied by enabling the module,
opening settings or saving, and setup discovery does not require the module
switch to be on. These flows do not authorize telemetry or other background
provider traffic. Explicit sharing/opening in another app is governed by the
[Files contract](files-contract.md).

## Acceptance

These are review scenarios, not a record of tests run or releases published:

- An upgrade from the single-profile app preserves enrollment, ports and the
  shared SSH key; no re-enrollment is required. Two profiles can keep tunnels
  open concurrently, identified in Connections and the notification.
- On an offline start, cached lists remain visible with checking/stale state
  and truthful feedback. A stale list does not block Connect; a fresh Offline
  answer disables only down connections. Refresh clears a recovered error.
- Enrollment storage success followed by activation failure keeps a durable
  pending identity and manual retry without another enrollment request.
  Failed batch preparation cannot leave candidate runtimes alive.
- Connections open through their whole-card primary action without stealing
  child controls. Repeated taps during startup open once; leaving cancels
  the pending open, not the tunnel. Saved pins are restored for all profiles
  before any Auto allocation; storage failure is not reported as lost identity.
- Failed direct access falls back to relay with Retry direct / Keep through
  the server. An SSH session left idle for 10 minutes on a direct tunnel
  stays alive. Kill/reboot restores intended
  tunnels on the same local ports; Disconnect clears that intent.
- QR enrollment works from a light or inverted terminal image and the gallery;
  invalid QR feedback keeps scanning. Russian/English and theme preferences
  apply without losing access to common actions or accessible labels.
- Updates are offered with notes, downloaded on request and installed only
  through the owner's system-installer action after all integrity, identity,
  version and signing checks pass. A failed check explains the refusal.
- Labels/icons update from the server answer across list, card and Connections;
  search finds labels with NFC/case normalization. Another client sees saved
  values on refresh. Unknown icons remain compatible. An old server hides
  editing; offline failures keep the draft, never queue uncertain writes.
- Plugin override resolution follows all six table rows, including own On
  over app Off and exceptions equal to the app level. Changes reach live
  sessions without screens. Migration preserves effective switches, rule
  ordering/deduplication and downgrade cleanup; failures remain visible.
- Review shared UI feedback, Undo, non-truncated names, TalkBack actions,
  portrait/landscape and large fonts. Component evidence does not establish
  vendor-IME, physical-device or live-service guarantees.

## Non-goals

The phone does not expose ports, reach other people's devices or enroll by a key.
The separately enabled assistant SSH tool can run administrative commands within
the SSH account’s rights (after the owner's confirmation unless that is turned off); existing manual SSH and Files behavior is unchanged. Google Play distribution, armv7 release builds
and additional UI languages are not promised. x86_64 builds are for local
debug/minified validation, not the arm64 release target. Assistant chat is
locally saved and encrypted, with optional public-read, profile-information and
SSH-command tools but no tunnel control, cloud synchronization or automatic action
replay. Model context remains bounded, with automatic working summaries rather
than an unbounded window. Summaries are model-generated notes, not verified truth
or independent background task/session management. Public tools do not execute/render JavaScript or
claim full client-only articles, and cannot read local files or private/tunnel
addresses. The explicit 2026-11-06 amendment returns available bounded static text,
including warning-only/shell text, without JS/usefulness detection; the model
judges it. Fixed no-JS/incomplete-extraction metadata remains, and empty content or
technical fetch failures are not fabricated successful articles. This supersedes
the earlier warning-only rejection policy prospectively, not past failed gates. Deferred
plugin UI is identified in scenario 12; it is not an implementation plan in this contract.

## Open risks

- Aggressive vendor firmware may still kill the foreground service; restarting
  introduces a gap and is not an uninterrupted-availability guarantee.
- Carrier NAT commonly prevents P2P, so mobile connections often use the relay.
- A tunnel is a TCP port on `127.0.0.1`, open to every app on the phone while
  it is up. Device web UIs and brokers need their own authentication.
- WebView cannot filter WebSockets: a compromised device page can reach the
  other tunnels that are up.
- Remote agents without hot reload can restart their tunnels when grants
  change. Phone-side hot reload does not upgrade or control those agents.

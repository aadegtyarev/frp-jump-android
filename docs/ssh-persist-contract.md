# frp-jump for Android — SSH sessions that survive a dropped connection

Status: Approved by the owner 2026-10-31. The UI rules of
`docs/contract.md` apply unchanged.

On mobile data the tunnel drops often, and today a drop ends the shell:
whatever ran in it stops, the screen is lost, and the owner taps Reconnect
to get an empty prompt. This contract makes a dropped connection a pause,
not an end: the app reconnects by itself, and where the device has tmux the
shell never stopped and comes back as it was. Mosh is out: the tunnels are
TCP.

## Today (what the code does)

- `SshSession` opens one sshj connection to `127.0.0.1:<local port>`, logs
  in, allocates a PTY (`xterm-256color`, the terminal's size) and starts a
  **shell channel** (`startShell`). Keep-alive every 20 s, connect timeout
  20 s (`SshConnector`).
- Any end of the shell (read error, exit status, close) makes it
  `SshState.Ended(message)`: the terminal keeps its text and gets a
  `[message]` line, and the bar above the snippets shows the reason with
  **Reconnect** and **Change login**. Reconnect (`SshSessions.reconnect`)
  builds a fresh session with the same login in the same tab: a new shell.
- Sessions live in `SshSessions`, in memory, as long as the process and
  their tunnel: a tunnel that goes away (Disconnect, Forget) closes its
  sessions. Nothing about tabs is stored; a killed process loses them.
- The terminal is Termux `terminal-emulator`/`terminal-view` v0.118.3 with a
  3000-row scrollback of its own (`RemoteTerminal`). A swipe scrolls that
  scrollback, or, when the remote program turned on mouse reporting, is sent
  as mouse-wheel events; a tap is sent as a click then too. Long press
  selects text from what is on the screen.

## Scenarios

### 1. The network blinks (tmux on the device)

Running `apt upgrade` in a tab, the phone changes cell or loses signal for
a minute. The bar shows "Connection lost · reconnecting…"; the owner does
nothing. When the network is back the tab re-attaches: the screen is
redrawn as it is now, `apt` kept running the whole time.

### 2. The same without tmux

The bar says the same while it reconnects. Back, the tab gets a fresh shell
and says so plainly: a line in the terminal and the bar "New shell: the
previous one ended with the connection". Nothing pretends to continue.

### 3. The app was killed

Android or the owner killed the app while two tabs ran `top` and a long
build. Later the owner opens Terminal on that connection: two tabs open and
attach to the two tmux sessions still alive, the build still going.

### 4. Done with a tab

Closing a tab ends its tmux session on the device when the close's Undo
is gone (UI rules); Undo brings the tab back with its session. Nothing is left behind that the owner forgot.

### 5. tmux missing

The first time a terminal opens on a connection whose device has no tmux,
a top notice says once: "No tmux on <device>: a dropped connection will
stop running commands. Install it: `apt install tmux`". After that the
terminal works as today plus auto-reconnect, silently.

### 6. tmux not wanted

The owner turns off **Keep sessions on the device (tmux)** on an SSH
connection (its edit sheet). Its terminals then open a plain shell, as
today, still with auto-reconnect.

## Behaviour

### Opening a terminal

- After login the app runs one probe on an exec channel: `command -v tmux`.
  Found and the connection's switch on: the tab's channel is an **exec** of
  one command with the PTY (below) instead of `startShell`. Not found, or
  the switch off: `startShell` as today. The result is kept for the
  session; every reconnect probes again (tmux may get installed).
- The command:

  ```
  exec tmux -u new-session -A -s 'frpjump-<install>-<n>' ';' set-option -t '=frpjump-<install>-<n>:' status off ';' set-option -t '=frpjump-<install>-<n>:' mouse on
  ```

  `-A` attaches when the session exists and creates it otherwise, so the
  first open, a reconnect and the pick-up after a kill are the same command.
  `-u`: UTF-8 even when the non-login exec has no `LANG`. Targets are exact
  (`=name` for a session, `=name:` for a pane), so `-1` never matches
  `-10`. Every argument is single-quoted and the separator is `';'`, which
  reads the same in sh, bash, zsh and fish. `tmux` is found on the exec's
  PATH, the same one the probe used. The probe is
  `command -v tmux >/dev/null && echo frpjump-tmux && tmux has-session -t '=<name>' && echo frpjump-attach`:
  only its own markers count, and it also tells whether the session still
  runs, so a re-attach is told apart from a fresh session.
- Each tab is one tmux session with one window. The tab bar of the app is
  the only list of sessions the owner sees.

### Dropped connection and auto-reconnect

- What counts as a drop: the shell ends with a read error, or the SSH
  connection closes without an exit status, or connect fails while the tab
  is reconnecting. A normal end (exit status: `exit`, tmux session killed
  or detached from inside) stays `Ended` with Reconnect, as today.
- Also not retried: login refused (Change login, as today), host key
  unknown or changed (the dialogs, as today).
- New state `Reconnecting(attempt)` between `Open` and `Ended`. The
  terminal keeps its text; keys and snippets are not sent ("Not connected"
  notice, as today for a closed session).
- Timing: first try at once, then after 2, 4, 8, 15 s, then every 30 s.
  The network coming back (the default network callback the app already
  has) tries at once and resets the pause to 2 s.
- Gives up after 10 minutes without success: `Ended` with the last reason
  and Reconnect, as today. A tap on Reconnect starts the same loop again.
- Tunnel gone vs network gone: while the tunnel is up but frpc cannot reach
  the server, connect fails and the loop keeps trying, nothing else to do.
  A tunnel the owner disconnected (Disconnect, Disconnect all, Forget)
  closes its tabs as today, with no retry; their tmux sessions stay on the
  device and are picked up next time (scenario 3).
- Login on reconnect is the tab's own: the key, the password it holds in
  memory, or the remembered one. Nothing is asked unless the device refuses.

### What the owner sees

- The existing bar above the snippets, in the tab that dropped:
  "Connection lost · reconnecting (try 3)…" with **Now** (try at once) and
  **Close**. The top bar's status line says "Reconnecting".
- Tabs not shown get a muted state mark like the existing Ended one.
- Back with tmux on the same session: the bar goes, a short top notice
  "Session restored" (only while the app is on screen). No text is written
  into the terminal: tmux redraws the screen on attach. tmux there but the
  session gone (the device rebooted): told as without tmux, a new shell.
- Back without tmux: the emulator is reset to the normal screen (it may
  have been inside `vim` or `htop`), a line `[New shell: the previous one
  ended with the connection]` is written, and the bar shows the same words
  until the owner types. The old text stays in the scrollback above.

### Pick-up after a kill

- Opening Terminal on a connection with no tab of its own in the app:
  after login (no extra prompt beyond today's), if tmux is used, the app
  lists `tmux list-sessions -F '#{session_name}'` on an exec channel and
  takes the names starting with `frpjump-<install>-`. None: one new tab, as
  today. Some: one tab per session in name order, each attaching, the first
  selected; a top notice "Restored N sessions".
- The device knows only the SSH user's sessions, so sessions of another
  user of the same device stay with that user's connection.

### Closing

- Closing a tab with tmux runs `tmux kill-session -t <name>` on an exec
  channel of the tab's connection before it disconnects. When the
  connection is down at that moment the tab still closes and the session
  stays; the owner can clear it with the action below.
- In the terminal's "⋮": **Close all sessions of this phone on <device>**
  (asks once, says how many it found) kills every `frpjump-<install>-*`
  of that user and closes the app's tabs to that device and port.

### Scrollback and copying inside tmux

- tmux runs on the alternate screen, so the app's own 3000 rows do not fill
  while it is attached; the history lives in tmux (its `history-limit`, 2000
  lines unless the owner's config says otherwise).
- `mouse on` makes the Termux view send a swipe as wheel events: tmux
  enters copy mode and scrolls its history; scrolling back to the bottom
  leaves it. A tap is a click for tmux (harmless with one pane).
- Copying stays the app's long press: it copies what is on the screen,
  including a part of tmux history scrolled into view. tmux's own drag
  selection is not used on a phone.

## Decided

- **Name `frpjump-<install>-<n>`.** `<install>`: 6 random hex characters
  made once per app install and stored; `<n>`: a counter that only grows.
  Two phones and the owner's own sessions (`0`, `work`) never collide, and
  `tmux ls` on a laptop shows plainly which are the phone's.
- **The owner's default tmux server, not a private socket.** The owner can
  `tmux attach -t frpjump-…` from a laptop to continue a phone session; one
  server to reason about.
- **Never touch `~/.tmux.conf` or global options.** Only `set-option -t
  <session>`: `status off` (the app has tabs), `mouse on` (swipe scrolls
  history). The rest of the owner's config applies as is.
- **Detect with `command -v tmux` per connect.** One cheap exec, no version
  parsing; `new-session -A` exists since tmux 1.8, Debian 11 ships 3.1.
- **Exec instead of a typed command.** Typing `tmux …` into a shell would
  echo it and race the login scripts; an exec channel with the PTY starts
  tmux directly.
- **Auto-reconnect for every SSH tab, tmux or not.** The drop is the pain
  either way; without tmux the fresh shell is announced, never hidden.
- **Backoff 0/2/4/8/15/30 s, give up at 10 min.** Fast after a short blink,
  little battery on a long outage, and an end the owner can see.
- **A tunnel the owner disconnected does not retry.** Disconnect means
  stop; the tmux sessions wait on the device for the next Terminal.
- **Close kills, drop keeps.** Only the owner's Close or "Close all
  sessions of this phone" ends a tmux session; nothing ends them on a
  timer.
- **The missing-tmux notice once per connection.** It is advice, not an
  error; repeating it would be noise.

## Stored on the phone

| What | Where |
| --- | --- |
| Install id for session names, the session counter | app-private settings |
| Per SSH connection: "Keep sessions on the device (tmux)", default on | the connection (a new field, absent = on) |
| Per connection: the missing-tmux notice was shown | app-private settings |

Tabs themselves are not stored: the device's `tmux list-sessions` is the
list to pick up from.

## Delivery

Each slice a separate PR, reviewed, checked on the emulator against a test
device, then a build for the owner's phone.

1. **Auto-reconnect for all SSH tabs.** `Reconnecting` state, backoff,
   network-callback trigger, 10-minute give-up, the bar and the "New
   shell" line. No tmux yet.
2. **tmux attach and naming.** Install id and counter, the probe, the exec
   command with per-session options, re-attach on reconnect, kill on Close.
3. **Pick-up after a kill.** `list-sessions` on opening Terminal, one tab
   per session, "Restored N sessions"; "Close all sessions of this phone"
   in "⋮".
4. **The switch and the notice.** The connection field and its row in the
   SSH edit sheet; the once-per-connection notice. `SshSession` gets the
   flag from the connection it was opened from.

Outside this repo: **frp-jump** (`aadegtyarev/frp-jump`, `packaging/`):
the `frp-jump-client` Debian package gains `Depends: tmux`. Devices set up
with pip or a venv do not get it; they fall under scenario 5.

## Acceptance

On a Wiren Board reached over the tunnel, from the owner's phone:

- Run `ping -i 1 127.0.0.1` in a tab, airplane mode for 1 minute, off: the
  tab re-attaches by itself, the ping counter has kept going without a gap
  of lines.
- Same with Wi-Fi to mobile data and back: no tap needed.
- `apt remove tmux`, repeat: the notice shows once; after the blink the tab
  says "New shell", the ping is gone.
- Two tabs with `top` and `sleep 600`, `adb shell am kill`, open Terminal on
  the connection: two tabs come back attached, `sleep` still running.
- Close one tab: `tmux ls` on the device no longer has it. "Close all
  sessions of this phone": no `frpjump-<install>-*` left; the owner's own
  tmux session is untouched.
- In a tmux tab: `seq 5000`, swipe down: tmux history scrolls; long press
  copies a line from it.
- The switch off: `tmux ls` shows no new session for that connection.
- Disconnect the tunnel while reconnecting: the loop stops, the tab
  closes with "The tunnel was closed.", the tmux session stays.

## Open risks

- **Copy mode eats keys.** After scrolling up, tmux is in copy mode and
  typed keys would be its commands. The app watches its own outgoing bytes
  for a wheel-up report; the owner's next input (key, extra key, snippet,
  paste) is then held until one exec of
  `tmux if-shell -F -t '=<name>:' '#{pane_in_mode}' 'send-keys -t =<name>: -X cancel'`
  has run, and then sent. Costs one round trip on the first key after a
  scroll; nothing global is bound. A copy mode entered another way (the
  owner's own key binding) is not noticed.
- **Sessions of a lost or reinstalled phone stay** on the devices until
  killed by hand (`tmux ls | grep frpjump-`); a new install has a new id and
  does not see them.
- **The owner's tmux config can disagree** (another prefix, `mouse off` in a
  hook, a `destroy-unattached` setting); per-session options win only for
  what they set.
- **A tmux session alive but its tab gone** (Close while the connection was
  down) lingers until "Close all sessions of this phone".
- **Long drops** past 10 minutes need one tap on Reconnect; the commands
  keep running under tmux regardless.
- **Without tmux nothing survives**; the package dependency covers devices
  installed from the .deb only.

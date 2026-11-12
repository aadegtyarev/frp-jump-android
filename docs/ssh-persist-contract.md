# SSH session persistence

SSH tabs reconnect after a transport drop. With a surviving remote tmux
session they reattach to the running shell; without one they announce a new
shell instead of pretending that commands continued.

## Boundary and ownership

This surface owns terminal SSH connection lifetime, reconnect, remote tmux
identity, recovery and closing. It serves the owner through Terminal and the
Sessions list. `SshSessions` owns in-memory tabs beyond the terminal screen;
`SshSession` owns each tab's transport attempts and emulator. A committed tunnel
close or profile forget closes its tabs. Process death loses local tabs and
text, not necessarily remote tmux sessions.

UI inherits the [shared UI rules](contract.md#ui-rules): common actions stay
directly accessible, waiting and failures are visible, and concise controls
must not remove functionality, accessibility or safety warnings. Terminal input,
clipboard and paste behavior remain governed by [SSH console](contract.md#7-ssh-console),
[text input](terminal-text-input-contract.md) and the
[terminal panel](terminal-panel-contract.md). [Files](files-contract.md) uses
the shared connector/login/host-key rules but owns a separate SFTP connection
and lifetime. [Diagnostics](terminal-diagnostics-contract.md) owns measurements,
not reconnect policy or proof that a tunnel listener recovered. The optional
[assistant SSH command](agent-contract.md#optional-ssh-command) uses the same
connector, stored login/refusal and host-key policy on its own per-operation
client. It allocates no PTY/tmux session, does not reconnect/replay commands or
change terminal/Files ownership, and never starts a tunnel. Stop/target loss
cancels only that command’s client; the Chat slot waits for actual cleanup.
Commands/output, potentially secret, are shared with the configured model only
when that explicitly enabled tool is invoked. Accepted commands and results may
also enter the assistant's encrypted local conversation; restoring that history
never reconnects or replays a command. During explicit Send, working summaries
may carry these historical facts to the same configured provider, using the chat
model or an optional summary model. A summary is unverified model data, not proof
of remote completion or authority to repeat work, and owns no SSH task/session.
This does not persist manual terminal text/tabs. Unknown/refused login or host
trust requires the ordinary SSH UI; the model cannot supply credentials or trust.

The approved opt-out and missing-tmux notice requirements remain binding but
are not currently available; see [Required controls and notice](#required-controls-and-notice).
This reference does not certify runtime conformance to those requirements.

## Behaviour

### Opening a terminal

- Connect through the tunnel at `127.0.0.1:<local port>` with SSHJ. Connect
  timeout is 20 s; keep-alive interval is 20 s. Authenticate with the tab's
  user and key or password. Allocate an `xterm-256color` PTY at the terminal's
  size and send window changes on resize.
- With tmux enabled, probe on an exec channel at each connect/reconnect:

  ```sh
  command -v tmux >/dev/null && echo frpjump-tmux && tmux has-session -t '=<name>' && echo frpjump-attach
  ```

  Only the probe's marker lines distinguish absent, fresh and existing
  sessions. The exec environment's PATH applies, not an interactive login
  profile's PATH. An exec refusal/timeout while transport remains up falls
  back to a plain shell; lost transport fails the connection attempt.
- Found: open a PTY exec channel, not a command typed into a login shell:

  ```sh
  exec tmux -u new-session -A -D -s 'frpjump-<install>-<n>' ';' set-option -t '=frpjump-<install>-<n>:' status off ';' set-option -t '=frpjump-<install>-<n>:' mouse on
  ```

  `-A` creates or attaches; `-D` detaches other clients, including a stale
  mobile connection or a laptop; `-u` requests UTF-8 without relying on LANG.
  Missing tmux or disabled persistence uses `startShell` instead.
- Each app tab is one remote tmux session with one window. Within Terminal,
  the app's tab bar is the only session list.
- If tmux exits nonzero within the first 5 s, announce that tmux failed and
  immediately open a plain shell. This tab, including manual replacement,
  then keeps to plain shells. A drop without exit status, normal detach/exit
  or failure after that window is not this startup fallback.

### Dropped connection and auto-reconnect

| Event | Result |
| --- | --- |
| Shell ends without exit status or signal, including transport EOF/read failure | Keep tab and text; enter `Reconnecting(attempt, trying)` |
| Shell has an exit status or signal (`exit`, logout, killed/detached tmux) | `Ended(message)`; no automatic retry |
| Connect/transport failure during a reconnect, including transport loss during authentication | Another failed try, not a login refusal |
| Device refuses login | Stop retrying; explain refusal and offer Change login |
| Unknown or changed host key | Stop retrying; require the owner's host-key decision |
| Owner commits tunnel Disconnect, Disconnect all or Forget | Close tabs, stop retries; keep remote tmux sessions |

- Try immediately, then pause 2, 4, 8, 15 s, then 30 s between failures.
  The default network returning/changing triggers an immediate try and resets
  the next pause to 2 s. **Now** tries immediately unless a try is in flight.
- Give up after 10 minutes without success, ending with the last reason and
  **Reconnect**. Manual Reconnect after give-up starts the loop again with
  the same login.
- During reconnect, keys/snippets/paste are not sent or saved for replay.
  Input explains that the tab is not connected. A tab or tunnel hidden behind
  pending Undo starts no further retries; Undo resumes an active loop at once
  with short pauses. A committed close stops it permanently.
- Login belongs to the tab: key, typed password still held in memory, or the
  remembered password. Reconnect does not silently change user or bypass
  authentication/host-key decisions.
- A tunnel still up while frpc cannot reach the server leaves the loop trying.
  An existing SSH connection does not prove a new local listener can start.

### What the owner sees

- The affected tab keeps its terminal text. Its bar above the snippets shows
  “Connection lost · reconnecting (try N)…” with **Now** and **Close**;
  the top status says “Reconnecting”. Other tabs carry a muted state mark.
- Reattached to the same tmux session: remove the reconnect bar, show a short
  “Session restored” notice only while the app is on screen, and let tmux
  redraw. Do not inject a restoration line into the terminal.
- No surviving session, including tmux present after a device reboot: reset
  the emulator to its normal screen and input modes, retain preceding
  scrollback, and write `[New shell: the previous one ended with the connection]`.
  The same warning stays in the bar until owner input. Without tmux the old
  commands are not preserved by the app.
- An ended tab retains readable text and a `[reason]` line, with the reason,
  **Reconnect** and **Change login** in the bar. Manual Reconnect replaces
  the session instance in the same tab, using the same login unless changed;
  it retains the assigned tmux name so a surviving remote session can attach.

### Pick-up after a kill

When opening Terminal with no local tab for that profile/device/port, log in
normally and, when tmux is enabled, list the SSH user's remote sessions:

```sh
tmux list-sessions -F '#{session_name}'
```

Take only valid `frpjump-<this install>-<n>` names, once each, in session-name
order. Skip names already held or claimed by another tab. Reserve
the counter beyond recovered names. With none, open one new tab. Otherwise
attach one tab per available session, first selected, and show “Restored N
sessions”. A failed listing while transport remains up is treated as no
sessions found; a transport loss fails the attempt. Tabs themselves are not
persisted, and remote sessions belong to the SSH user, not all users on a device.

### Closing

- **Close kills; drop keeps.** Closing a tab hides it immediately and commits
  only after shared Undo expires (about 6 s). Undo restores the same live
  session and ends nothing. Closing the app during pending Undo must not
  cancel the committed destructive action under the shared rules.
- On committed tab Close, if its authenticated tmux connection is still open,
  end its assigned remote tmux session by running `tmux kill-session -t '=<name>'`
  before disconnecting. Successful Close requires that remote session to end,
  not merely that the command was attempted. If the connection is down at
  commit, still close the tab and leave the remote session for close-all.
  Tunnel close/profile forget/manual replacement do not kill it.
- Terminal ⋮ → **Close all sessions of this phone on <device>** first lists
  this install's sessions read-only, with a “Looking for…” notice. Then tabs
  to that profile/device/port leave the screen, and the shared “Closed: N ·
  Undo” counts tabs plus discovered sessions no tab holds. Nothing remote
  ends until Undo expires; Undo restores tabs and ends nothing.
- On commit, close the tabs and end the discovered sessions in one exec,
  then list again. Report any still listed. Do not end sessions held by tabs
  that stay, including another port/profile reaching that device and user.
  Nothing found: say so. If unreachable, tabs still close with Undo and a
  warning that remote sessions remain running. Kill failures are visible.

The current single-tab kill path is best effort and logs failures. This is an
implementation limitation, not a waiver of the required remote-end outcome.
Logging does not prove termination; a failed or unknown outcome must not be
presented as successful remote termination. The shared truthful failure-feedback
rules still apply. Close-all can later clear a session left running; that
recovery does not make the earlier Close successful.

Closing or ending is final for that instance. Each attempt owns its client
and completion. State publication and terminal handover serialize against
close: late Trust/Forget writes, host-key/login results, EOF, failure and
fallback callbacks must not revive a closed instance or end/replace/reconnect
a newer client. Disconnect every unaccepted client, including construction
completed after close. Transport cleanup must run even with an already canceled
caller scope. Blocking transport work, fresh-shell queue preparation and
reconnect-monitor calls stay outside the lifetime commit.

### Scrollback and copying inside tmux

The terminal uses pinned Termux `terminal-emulator`/`terminal-view` v0.118.3.
History is configurable at 500, 1000, 3000 or 10000 rows, default 1000, including
older settings without a value. It is the total main-buffer limit including
visible rows, not that many off-screen rows. A new terminal captures the
setting; changes do not trim/recreate existing terminals. Automatic reconnect
keeps its emulator and original limit; manual replacement/new tabs capture the
current choice. See [history setting](terminal-diagnostics-contract.md#history-setting).

While tmux uses the alternate screen, that local main-buffer history does not
fill; remote history follows tmux's `history-limit` (normally 2000 lines unless
the owner's config changes it). With mouse reporting, swipes become wheel
events and taps clicks. `mouse on` lets tmux enter copy mode to scroll history
and leave it at the bottom. Long press still selects/copies visible text,
including history scrolled into view; phone copying does not use tmux drag
selection. Tap-to-cursor exceptions belong to the shared SSH-console rules.

After an observed outgoing wheel-up, hold the next owner input (key, extra
key, snippet or paste) until one exec asks tmux to cancel pane mode:

```sh
tmux if-shell -F -t '=<name>:' '#{pane_in_mode}' 'send-keys -t =<name>: -X cancel'
```

No global binding changes. Only the first owner input after scrolling incurs
that round trip; copy mode entered by another route is not detected. The
original requirement that stalled keys proceed after at most 10 s remains
unverified as a total-wait bound: implementation joins the command for 10 s
**plus channel-opening time**, then proceeds even on failure. Do not present
that join timeout as a proven end-to-end maximum or remove the protection;
[diagnostics](terminal-diagnostics-contract.md#local-measurements) measures it.

## Compatibility and remote ownership

- Names are `frpjump-<install>-<n>`: six random lower-case hex characters
  generated once per install and an increasing persisted counter. Keep names
  stable across reconnect/replacement and distinct from other phone installs
  and the owner's own sessions. Recovery advances the counter, not reuses a
  recovered name.
- Use the owner's default tmux server, not a private socket; the owner may
  attach from a laptop. Never modify `~/.tmux.conf` or global options. Apply
  only per-session `status off` and `mouse on`; other config remains in force.
- Quote command arguments and the `';'` separator for sh, bash, zsh and fish.
  Use exact session targets `=name` and pane targets `=name:` so `-1` cannot
  match `-10`. Only validated app session names enter tmux commands.
- tmux 2.4 or later is needed for the full behavior (`=` targets and
  `send-keys -X`), even though `new-session -A` alone is older. Do not infer
  compatibility from the probe alone; early failure uses the plain-shell path.
- The owner's config can conflict (hooks, `mouse off`, `destroy-unattached`).
  Options override only what the app explicitly sets. `mouse on` also affects
  a laptop attached to these sessions, and phone reattach detaches it (`-D`).

### Remote image files

- Insert image ([contract](contract.md#7-ssh-console)) opens one separate SFTP
  channel on the tab's authenticated connection, one per batch of up to 10 images
  (the images go one at a time over it, no second channel per image; the opening
  and every rule below are per channel, not per image): no new login, no change to
  the shell channel, tmux, reconnect or host keys. Closing or cancelling it
  closes only that channel. The SFTP wait is 10 s per request, not a deadline
  for the whole upload; the SFTP version exchange is not timed by sshj at all.
  sshj also waits up to its own 30 s for a channel open and for the subsystem
  request, and a close waits up to 30 s for the peer. So opening the channel and
  starting SFTP runs on a helper thread that the job abandons at 10 s in all or on
  Cancel (a channel it makes later is closed as soon as it exists; at most 6 such
  helpers in all, 2 abandoned per connection, ended by an interrupt or by sshj's
  30 s). The app never ends a channel's input stream itself, only sends CLOSE:
  a reply arriving on a stream marked finished would make sshj drop the whole
  connection, shell included. Cancel mid-upload typically ends within one round
  trip on a live device and at most the 10 s request timeout on a hung one, up to
  30 s while a write waits for the peer's window (the peer's CLOSE does not wake
  it), and a stalled socket write ends only when the connection does; the same
  holds for an interrupted helper. Interrupting is safe because the transport is a
  plain `java.net.Socket`; that stops holding if a connection is ever tunnelled
  through another SSH channel. The removal of the part file (and, on Cancel, of the batch's
  complete files too) is bounded to 5 s in all by its own helper, including the
  opening of the channel it uses; files not reached by then stay for the age
  cleanup. The per-connection limit of 2 abandoned helpers is checked before it is
  counted and can briefly be exceeded in a tight race; the 6-thread pool is the
  hard cap. A failed or abandoned opening
  closes what it made (a channel sshj confirms after its own open timeout can,
  rarely, stay). A device without SFTP is reported, nothing else changes.
- The app creates `~/.cache/frp-jump/images/` (absolute path from the SFTP
  home; a home with characters outside `A-Za-z0-9._/-` is refused). Missing
  folders are created 0700. `~/.cache` may be a link to a folder; `frp-jump`
  and `images` must be real folders of the login user (same uid as the home),
  otherwise the upload is refused; a wider `images` mode is tightened to 0700.
- Files are `img-YYYYMMDD-HHMM-xxxx.jpg` or `.png`, created exclusively with
  mode 0600 and never overwritten (a clash takes a new suffix, 4 tries).
  Failure removes the part file the job was writing when the connection
  allows; Cancel of a batch removes the part file and every complete file that
  batch created (best effort, within the 5 s bound above, in order); a lost
  connection can leave them. A failed upload ends the batch's uploads; the
  complete files before it stay and are inserted unless the connection or tab
  changed. An unreadable image is skipped.
- Once per batch (after its last file is written, on its channel), best effort and bounded, files in that folder that match the
  app's own name pattern, are regular files (never links or folders, never
  followed), and are more than 60 minutes older than the batch's last file's device
  modification time (the batch's own files are never deleted by it) are deleted (a file with no known time is kept). The
  cleanup runs on a thread of its own after the file is written (the path does
  not wait for it), with a 15 s deadline that also closes its channel, at most
  500 entries read, and a failure never reaches the owner; its thread lives about
  15 s plus one 10 s request timeout, and the channel close can take sshj's 30 s
  more, on threads nothing waits for.
  Nothing else is deleted; images are not removed on tab close or app exit, so
  they stay on the device for about an hour until a later upload.
- An image upload is never retried or replayed by Reconnect; a connection or
  terminal that changed meanwhile (also between two images of a batch), or a tab
  closed (also with its Undo pending), gets no insertion. Complete files stay when
  the insertion is refused or the tab changed; only a cancel removes them.

## Security, privacy and stored data

- Host keys are pinned per profile/device/target port, never the shared
  loopback address. First use requires explicit Trust of the presented
  algorithm/SHA256 fingerprint. Changed keys block login and show old/new
  keys; forgetting the old key leads to a new first-use decision, not silent
  acceptance. Terminal and Files share those decisions.
- Use the phone's generated ed25519 key across profiles, with optional imported
  identity tried first. Private keys, import passphrases and remembered
  passwords are encrypted in app-private storage with the Keystore-backed
  SSH secret box. Public keys and host fingerprints are not secrets.
- Typed passwords remain in the tab's login object for reconnect; persist
  only on Remember. Remembered passwords are scoped to profile/device/port/user.
  A refusal is not permission to erase a remembered password or to keep
  offering it silently; distinguish missing/unreadable credentials from wrong
  credentials and offer Change login. Transport loss is not a credential refusal.
- Local tabs/emulator text and per-tab text drafts are process-memory state,
  not a stored tab list. Install id and counter are app-private persisted
  naming data. Saved login choices and host pins follow the shared app rules.
- Preserve selected-tab-only automatic OSC 52 writes, exact terminal identity
  checks on late callbacks, explicit selection Copy (including ended terminals),
  the common 64 × 1024 UTF-16-code-unit cap and copied-text notice. Leaving the
  screen/backgrounding does not revoke the last selected tab. No new prompt,
  opt-in or foreground restriction is introduced here.
- Image insertion keeps image bytes and their source URI out of logs, notices
  and diagnostics, and never logs the remote path or folder names.
- Preserve paste confirmation and session/output-generation binding. Dropped
  input is never replayed into the next shell; stale queued chunks cannot
  cross handover after the copy-mode guard. Local write/paste acceptance is
  not acknowledgement of remote receipt or command execution.

### Required controls and notice

These already approved requirements are **not implemented in the current
connection UI/persistence/notice path**; startup-failure fallback is not their
substitute:

- An SSH connection's edit sheet must offer **Keep sessions on the device
  (tmux)**, default on, including when the stored field is absent. Off opens
  plain shells while keeping automatic reconnect.
- The first terminal on a connection without tmux must show once:
  “No tmux on <device>: a dropped connection will stop running commands.
  Install it: `apt install tmux`”. Subsequent opens work without repeated
  advice. Persist the per-connection shown flag in app-private settings.

The current path probes unless that tab disabled tmux after startup failure;
there is no available per-connection opt-out or missing-tmux advice. This
implementation gap does not withdraw the requirements or define a new schema.

## Intentional limits

- No mosh: tunnels are TCP. No local shell or automatic tmux installation by
  this app. The companion `frp-jump-client` Debian package is required to
  depend on tmux; that packaging belongs to `aadegtyarev/frp-jump`, outside
  this repository. Pip/venv/manual installations may lack tmux.
- No survival guarantee without tmux, after remote reboot/session destruction,
  or against conflicting remote config. Process death does not preserve local
  screen text/drafts. The recovery source is the remote session listing.
- No timer kills remote sessions. Lost/reinstalled phones leave sessions until
  ended manually; a new install id cannot discover the old install's sessions.
  Close while disconnected can leave the same kind of orphan until close-all.
- After a drop longer than 10 minutes, Reconnect needs a tap; remote tmux work
  can continue independently. Mobile/vendor process survival and live SSH
  delivery are not certified by synthetic lifecycle or buffer tests.

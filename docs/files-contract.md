# frp-jump for Android — files (SFTP) contract

Status: approved by the owner 2026-10-31. Comes after 0.2.0; not part of the v2
contract (`docs/contract.md`), whose UI rules apply here unchanged.

Working with a device's files over the same SSH login the terminal uses:
browse, copy both ways, manage, edit text, pack and unpack, with large
transfers that resume and run in the background.

## Scope

- Any device reached through an SSH connection of the app; the files are
  those the SSH user may read and write. No root escalation (`sudo`).
- SFTP for listing, reading and writing; plain commands over the same SSH
  connection (`cp`, `mv`, `rm`, `tar`, `unzip`) where SFTP has no operation
  (copy on the device, pack, unpack). A device without them says so.
- Out of scope: two devices at once (device to device), sync of folders,
  previews of images or media inside the app (they open in other apps),
  editing binary files.

## Where it lives

- "Files" on an SSH connection: in its "⋮" menu (device card and
  Connections tab; the row already has Disconnect and Terminal) and in the
  terminal's top bar for the open session. It opens a file screen for that
  connection; the tunnel is brought up first if it is down, as Terminal
  does.
- No new bottom tab: the bar stays four items. Transfers are visible in the
  notification and on a "Transfers" sheet reachable from any file screen and
  from the notification.
- Login is the terminal's: the same user, key or password (the login
  dialog remembers them per device and port), the same pinned host key.
  Files use an SSH connection of their own, so a long transfer or a drop
  does not touch an open terminal, and the other way round.
- Kept with the terminal: leaving the file screen (top arrow, or Back at
  `/`) while a terminal session is live on the same connection keeps its
  file session (SFTP connection, folder, listing, scroll positions; the
  selection ends). "Files" on that connection, from its row or the
  terminal's top bar (same login), returns to it at once in that folder,
  read again, reconnecting into it if the link dropped meanwhile. It closes
  when the last terminal session on the connection ends or goes (the
  tunnel with it). With no terminal open, leaving closes it. Transfers do
  not outlive the screen: leaving with transfers going asks first and
  cancels them, as without a terminal.

## Scenarios

### 1. Browse

- Opens in the user's home folder; a path bar on top (tap a part to go
  there, tap the path to type one), Back goes up a level, then closes.
- Rows: icon by type, name, size, modified time; folders first, then files,
  by name; hidden files (`.name`) behind a toggle in "⋮", remembered.
- Symlinks are marked with a link badge and show their target
  (`→ /mnt/data/etc`); a link to a folder opens the folder, a broken link is
  marked broken. A symlink's card has "Go to the target".
- Pull to refresh; the list keeps its position after an operation.
- Offline (no tunnel): the last listing of that folder is shown, marked
  "No connection", operations disabled.

### 2. Card and select

- Tap a folder: it opens. Tap a file, or the small "⋮" at the end of any
  row: its card (a bottom sheet) with name, path, a link's target, size,
  modified, permissions and owner, and its actions; nothing is transferred
  until one is tapped, and a transfer of it shows there with Cancel.
- Long press starts selection; taps then add and remove; "Select all" in
  the top bar; Back ends it. The top bar shows the count and the actions
  for the selection: Download, Copy, Move, Pack, Delete, and "⋮" for
  Rename (one item), Properties. Stage 1 offers Download and Share; they
  take the selected files, and a note says how many folders were skipped.

### 3. Copy between phone and device

- Download: selected files and folders (folders recursively) to a folder on
  the phone chosen once and remembered (the system folder picker), or
  "Share" for one or a few files (downloaded to the app's cache first).
- Upload: "+" › Upload files picks one or more files from the phone (the
  system picker, any app's documents); a share from another app to
  frp-jump offers the connection and folder to upload to.
- Name clash: Replace / Skip / Keep both (`name (1).ext`), with "for all".
- Permissions of a replaced file are kept; a new file gets the device's
  default (umask).

### 4. Copy and move on the device

- Copy / Move: select, tap Copy or Move, browse to the target folder, tap
  "Paste here". Copy runs `cp -a` on the device (keeps modes, times and
  symlinks as symlinks); Move is an SFTP rename within one file system and
  `mv` across.
- New folder, Rename, Delete (asks once for the whole selection, says how
  many files and folders; a folder is deleted with its content).
- Properties: path, real path for a symlink, size (folders on request: it
  runs `du`), owner, mode, modified.

### 5. View and edit

- Tap a file: its card opens. Open there: text files (by extension and
  content sniffing, up to 2 MB) open in the app's viewer (the editor comes
  in stage 4); others are downloaded to the cache and handed to another
  app (Open with…), as web downloads are.
- Viewer (decided 2026-11-01, read only): the file downloads to the cache
  with its bar on the card (Cancel, a failure with Retry), then shows over
  the file screen; Back or the arrow closes it. A name known as binary
  (images, media, archives, `.pdf`, `.apk`, `.db`, …) goes to Open with at
  once. Any other file over 2 MB goes to Open with, and a note says why.
  The rest is downloaded and looked at: no NUL byte and mostly printable
  UTF-8 in its first 8 KB, and still at most 2 MB as downloaded → the
  viewer, else Open with. A name known as text (`.txt`, `.log`, `.md`,
  `.json`, `.yaml`, `.conf`, `.ini`, `.sh`, `.js`, systemd units, `.env`,
  dotfiles such as `.bashrc`, …) picks the colours; it is looked at all
  the same, as a name can lie.
- The viewer's copy is deleted as it closes. Share and Open with from the
  viewer hand on a copy of their own, kept as files opened with other apps
  are. Copies left by a viewer that did not close (the screen or the
  process went) go once a few minutes old: when a file screen goes, and at
  the next start. A copy gone from under a viewer (the cache cleared)
  closes it with a note.
- It shows the text as UTF-8 (a bad byte shows as �), monospace, line
  numbers off, lines wrapped; "⋮" has Wrap lines, Line numbers, the text
  size (A− / A+, shared with the terminal), Copy all, Share and Open with.
  It scrolls by line, so a 2 MB log of 50 000 lines scrolls smoothly; a
  line longer than 1000 characters (minified JSON, a long log line) is
  shown in pieces of 1000, each piece after the first marked `↪`. Control
  characters show as their signs (`␛`, a lone CR as `␍`). TalkBack reads
  each row's text, without its number or `↪`.
  Copy all takes up to 128 K characters (the system clipboard's limit is
  near); a larger file says to use Share. A copy of a secret-looking file
  (`.env`, keys, `.nmconnection`) is marked sensitive, so Android 13 and
  later do not preview it.
- Colours, from the theme's colours in light and dark, picked by the
  file's name and, without a known one, its first line (`#!` of a shell or
  `node`, a leading `{` / `[`, `---`, a log timestamp); a `.conf` or
  `.cfg` whose text is a JSON object or array (as `wb-mqtt-serial.conf`)
  is JSON: Markdown (headings,
  emphasis, inline and fenced code, links, list marks, quotes); logs
  (time stamps; ERROR/FATAL/CRIT in red, WARN in amber, INFO/DEBUG muted);
  JSON (keys, strings, numbers, `true` / `false` / `null`); YAML (keys,
  comments, strings, anchors and aliases, list dashes, block scalars);
  INI, conf, properties, systemd units, `.env` (`[sections]`, keys,
  comments); shell (comments, strings, `$variables`, keywords);
  JavaScript, wb-rules among it (comments, strings, keywords, numbers,
  regular expressions). Anything else is plain text. Colouring is by line
  with the state a line leaves (an open fence, block comment, quote or
  here-document), worked out off the main thread; the text shows at once
  and the colours follow. Lines over 4000 characters stay plain in
  Markdown, logs, YAML and INI.
- Search: the magnifier opens the search field over the top bar, as in
  the file list; matches of the text, ignoring case, are marked, the
  current one stronger, with "3 of 17" and next / previous; ✕ or Back
  closes it. Nothing found: "No matches". It counts up to 10 000 matches.
  The keyboard shrinks the text, so a match stepped to is never under it.
- Editor (stage 4): the viewer plus editing, undo; "Save" uploads it back.
- Saving through a symlink writes the file it points to, never replaces the
  link with a regular file: the app resolves the real path, writes a
  temporary file next to it, keeps its mode, then renames it over the real
  file. A file changed on the device since it was opened asks before
  overwriting (shows "changed on the device at 14:02").

### 6. Pack and unpack

- Pack: the selection into `name.tar.gz` (default) or `name.zip`, in the
  current folder, on the device (`tar -czf` / `zip -r`).
- Unpack: tap "Unpack" on a `.tar`, `.tar.gz`, `.tgz`, `.zip` into a folder
  of the same name (or here), on the device.
- Both run as transfers do: progress where possible, in the background,
  cancel.

### 7. Large transfers, resume, background

- Every download, upload and device-side operation is a transfer in one
  queue, two at a time; the rest wait.
- Transfers run in the foreground service that keeps the tunnels up, so
  leaving the app or locking the phone does not stop them; the notification
  shows progress and "Cancel all".
- Resume: a download writes `name.part` on the phone and an upload
  `.name.frpjump-part` on the device; after a dropped connection or a
  restart the transfer continues from the partial file's size, if the
  source's size and modified time have not changed (else it starts over and
  says so). Retry is automatic three times with a growing pause, then the
  transfer waits for "Retry".
- A finished upload is renamed to its name at the end, so a half-written
  file never replaces a good one.

### 8. Search

Decided 2026-10-31: by name only; content is searched in the terminal.

- The magnifier in the top bar opens the search field over it, as on
  Devices and in the MQTT explorer (✕ or Back closes it and clears it).
- Typing filters the folder shown at once, on the phone: the rows whose
  name holds the text, ignoring case. Taps work as on the full list; a
  folder opened from it closes the search.
- Under them, "Search subfolders" looks for the same text in the names
  below this folder, on the device (`find` over the files' SSH
  connection). Hits come in as they are found, each as its path relative
  to this folder, under "Searching… N found" with Stop. A tap on a folder
  opens it; on a file, its card, over the hits.
- It stays on the folder's file system (`find -xdev`: no `/proc`, `/sys`
  or other mounts below), does not follow symlinks, skips hidden names
  while hidden files are off (switching them runs a search going again,
  and clears one that is over), and passes
  over folders it may not read, saying how many there were. It ends by itself after 500 hits ("Showing the first 500") or two
  minutes ("Stopped after 2 minutes"). Changing the text, closing the
  search, opening another folder or leaving the screen stops it, and the
  `find` on the device with it.
- Ignoring case on the device is sure for Latin letters only: other
  letters fold as the device's locale does, and a hint under the action
  says so when the text has them. A `/` in the text: "Searching by name
  only, without /" in place of the action.
- Nothing found: "Nothing found in subfolders". `find` failed or is
  missing: what it said, with Retry. A login that refuses commands
  (nologin) says so; no answer within 15 seconds (an SFTP-only login,
  or a stalled device): "The device did not answer the search command",
  with Retry. A folder it cannot enter: "Folder unavailable". Offline: the filter works on the
  folder as last seen; the search in subfolders needs the connection.
- It does not search file contents, use patterns or regular expressions
  (the text is taken literally), or remember past searches.

## Feedback (the UI rules applied)

- Each operation shows progress where it was started (a bar on the row, a
  spinner on the button) and a short result ("Copied 12 files", "Deleted 3").
- Failures say what failed and why in plain words (permission denied, no
  space left on the device or the phone, the tunnel dropped) with Retry.

## Stored on the phone

| What | Where |
| --- | --- |
| Download folder choice, hidden-files toggle, last folder per connection | app-private settings |
| Last listings for offline view | app-private cache, per connection |
| Partial downloads | the chosen folder (`.part`), removed on cancel |
| Files opened with other apps | app cache, wiped on exit and daily |
| Files shown in the viewer | app cache, deleted as the viewer closes |

## Delivery

1. Browse, symlinks, download and upload of files, Share, Open with.
2. Selection, folders recursively, copy/move/rename/delete/new folder on the
   device, name clashes, properties.
3. Transfer queue in the service, notification, resume and retries.
4. Text editor with symlink-safe save and change detection.
5. Pack and unpack.
6. Search by name: the folder's filter and the search in subfolders.
7. Text viewer with minimal colouring (before the editor of stage 4).

Each stage a separate PR, reviewed, checked on the emulator against a test
device, then a build for the owner's phone.

## Acceptance

- Download a 300 MB backup; turn off Wi-Fi halfway, turn it on: it
  continues from where it stopped. Lock the phone: it goes on.
- Upload three files and a folder to `/mnt/data/tmp`; one exists: "Keep
  both" gives `name (1)`.
- Edit `/etc/wb-rules/x.js` through a symlink to it: the link stays a link,
  the target has the new content and its old mode.
- Pack a folder to `.tar.gz`, download it, unpack another on the device.
- No network: the last listing shows with "No connection".
- Open a 2 MB log: it shows in the viewer, scrolls smoothly, ERROR
  marked red; search `error` steps through the matches. A `.png` opens with
  another app; a 5 MB log too, with a note.
- In `/etc`, type `wb-`: the folder's matching rows show at once;
  "Search subfolders" lists `wb-rules/…` hits as they come; Stop ends it
  and the `find` on the device is gone (`pgrep find`).

## Open questions

- Decided 2026-11-01: minimal colouring by format (see "5. View and
  edit"), in a viewer first; the editor reuses it.

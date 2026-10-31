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
  content sniffing, up to 2 MB) open in the app's editor (stage 4); others
  are downloaded to the cache and handed to another app (Open with…), as
  web downloads are.
- Editor: monospace, line numbers off, search, undo; "Save" uploads it back.
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
| Files opened for viewing or editing | app cache, wiped on exit and daily |

## Delivery

1. Browse, symlinks, download and upload of files, Share, Open with.
2. Selection, folders recursively, copy/move/rename/delete/new folder on the
   device, name clashes, properties.
3. Transfer queue in the service, notification, resume and retries.
4. Text editor with symlink-safe save and change detection.
5. Pack and unpack.

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

## Open questions

- Whether the editor needs syntax colouring (JS, JSON, YAML) or plain text
  is enough at first.

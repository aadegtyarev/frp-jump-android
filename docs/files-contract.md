# frp-jump for Android — files (SFTP) contract

## Scope

Files lets the owner browse a device over SSH, download and upload files,
share them, open them in another app, read text in the app, and search names.
This is the durable contract for that surface, including its session lifetime,
transfer ownership and local-copy cleanup requirements. It is not a delivery
plan or a claim that every requirement has passed a fresh runtime audit.

The [shared UI rules](contract.md#ui-rules) apply unchanged: concise, clear
controls, directly accessible common actions, local progress and actionable
errors, accessible light/dark presentation, and no loss of function or safety
for visual minimalism. SSH identity and tunnel behavior belong to the
[app contract](contract.md) and [SSH contract](ssh-persist-contract.md).

- Files works with devices reached through the app's SSH connections, within
  the SSH user's permissions. It does not escalate privileges with `sudo`.
- Listing and file bytes use SFTP. Search in subfolders uses a command channel
  on the Files SSH connection; basic browsing does not require a command shell.
- Terminal "Insert image" does not use a Files visit: it opens its own SFTP
  channel on the terminal tab's connection ([SSH contract](ssh-persist-contract.md#remote-image-files))
  and changes no Files ownership, transfer or cleanup rule here.
- Files owns its own SSH/SFTP connection, separate from terminal channels.
  A transfer or failure on one must not close the other's SSH connection.
  They still depend on the same tunnel.
- `FilesKeeper` owns sessions; a file screen attaches to one `FilesSession`.
  The session owns the current folder, listings, scroll positions and transfer
  visit. Transfer rows and jobs belong to that visit, not to a terminal or a
  process-persistent service queue.

## Where it lives

- Open Files from an SSH connection's “⋮” menu on its device card or the
  Connections tab, or from the terminal's top bar. Bring up its tunnel first
  if needed, as for Terminal. Files adds no bottom navigation tab.
- Use the terminal's login: the same user, key or password, remembered login
  per device and port, and pinned host key per profile/device/port. Password
  storage remains opt-in through the SSH login dialog.
- Leaving with no live terminal on the same connection closes the Files
  session. With a live terminal, keep its SFTP connection, folder, listing and
  scroll positions; end selection. Reopening Files returns to that folder at
  once and reads it again, reconnecting if needed. The terminal route reuses
  only the same login. At most one off-screen session is kept per tunnel,
  the one left last. End the kept session when the last live terminal there
  ends or is removed, including when its tunnel goes.
- Keeping a session does **not** keep transfers running after the file screen
  is left. The top arrow leaves directly; Back first ends selection or search,
  otherwise goes up a folder and leaves at `/`.
- If transfers are running or waiting when leaving, ask whether to leave and
  cancel or stay. While that question is open, all completing successfully
  leaves as requested; a new failure keeps the screen open so it can be seen.
  This transfer-cancellation question is not a remote-file deletion policy.

### Visit ownership checks

Leaving or closing revokes the current visit's progress authority, clears its
transfer rows and selection, and synchronously cancels its current job snapshot
before returning. Asynchronous cleanup waits only for that snapshot; it must
not cancel work admitted after a return to the kept session.

Returning creates a fresh visit. Late IO progress from the old visit cannot
restore cleared rows or overwrite the new visit's rows. Upload metadata
preparation and aggregate batch notifications belong to the visit too; late
metadata must not enqueue old work. Row cancellation affects only that row's
job, and cleanup of an older job cannot remove a replacement row owner. Closing
is final: later resume, upload or progress callbacks cannot revive the session.

## Scenarios

### 1. Browse

- Open in the user's home folder. The path bar opens an ancestor by tapping
  its part, or accepts a typed absolute path, a path relative to the current
  folder, or `~` for home. Entered folders use physical paths with links resolved.
- Rows show type, name, size and modification time. Folders come first, then
  other entries; each group is sorted by name ignoring case. “⋮” has the
  remembered hidden-files toggle for dot names.
- Mark symlinks with a link badge and target (`→ /mnt/data/etc`). A folder
  link opens its target; mark missing targets broken. “Go to the target” opens
  a folder target itself, otherwise the target's parent folder.
- Pull to refresh. Keep per-folder scroll position when returning and after
  an operation.
- Without a connection, show the last cached listing, marked “No connection”
  with its age when known. Cached-folder navigation and local filtering remain
  available; operations requiring the device are disabled. Say when no listing
  is known or the saved listing was truncated, rather than imply it is complete.

### 2. Card and select

- A folder tap opens it. A file tap, or a row's “⋮”, opens its bottom-sheet
  card: name, path, target/broken state for a link, size for a regular file,
  modification time, permissions and numeric owner/group where available.
  Link metadata describes the target when SFTP provides it.
- Merely opening the card transfers nothing. Open, Download and Share start
  file operations; show their progress and Cancel on the card and row. Broken
  links and non-transferable special entries say why they cannot transfer.
- Long press starts selection outside search; taps then toggle entries.
  The top bar shows the count, Select all, Download and Share where applicable.
  Back or ✕ ends selection. Changing folders ends it; refreshing the same
  folder keeps only entries still visible.
- Download and Share take transferable files, not folders recursively. Say
  how many selected folders or other entries were skipped. Cancelling the
  download-folder picker does not lose the selection.

### 3. Copy between phone and device

| Action | Input and result |
| --- | --- |
| Download | Choose a phone folder through the system folder picker; remember its persistable grant for later downloads. Save selected files there. “⋮” can change the folder. |
| Upload | “+” opens the system multi-file picker. Upload the chosen documents into the folder shown when they were picked. |
| Share | Download one or several files to app cache, then open the system share chooser. A multi-file share uses one chooser for the files that arrived, reporting partial success; failures remain retryable. |
| Open with | Download to app cache and hand that file to the system app chooser. |

Current name clashes use Keep both (`name (1).ext`), not Replace or Skip.
Concurrent uploads reserve names per device/folder so they cannot claim the
same destination. A new upload writes a hidden `.name.frpjump-part` beside the
destination, then renames it when complete. An existing fresh part is left
alone and another name is chosen; a part at least ten minutes old may be
replaced as stale, using its modification time. This is a fresh upload, not
resuming its bytes. A final-name race retries a free name once. Never remove a
part file this upload did not create during failure cleanup. New files use
the device's default permissions; there is no current replacement-mode flow.

### 5. View and edit

Open on a file's card routes it as follows:

1. A known binary name (images, media, archives, `.pdf`, `.apk`, `.db`, etc.)
   goes to Open with, not an in-app preview.
2. Other files larger than 2 MB (2 × 1024 × 1024 bytes) go to Open with,
   with a note explaining the size limit.
3. Download remaining candidates to the viewer cache, with progress, Cancel
   and Retry on failure. Check the downloaded size again and sniff its first
   8 KB: no NUL byte and mostly printable UTF-8. A text-looking name is still
   checked. Binary content or growth past the limit goes to Open with.
4. Show accepted text read-only over the file screen. Back or the arrow closes
   it and returns to the retained folder/scroll position. Editing and Save
   are not available.

The viewer uses [Sora Editor](https://github.com/Rosemoe/sora-editor) 0.24.6
(LGPL-2.1), with TextMate support (tm4e/joni), embedded in Compose. Its controls:

- UTF-8 text, invalid bytes as �, monospace, line numbers off and wrapping on
  initially. CR, LF and CR LF each end a line. ASCII controls render as boxed
  names such as `ESC`, `BEL` and `NUL`.
- “⋮”: Wrap lines, Line numbers, text size A−/A+ shared with Terminal,
  Copy all, Share and Open with. If any line exceeds 16 K characters,
  disable wrapping with “Lines too long to wrap”; scroll horizontally while
  line numbers stay fixed.
- Long press selects text with the editor's Copy. Copy all accepts up to
  128 K characters; above that, say to use Share rather than silently truncate.
  Mark copies of secret-looking files (`.env`, keys, `.nmconnection`, etc.)
  sensitive on Android 13+ so the system does not preview them.
- TalkBack reads visible lines, not the entire file, and offers no editing
  actions. Search and size controls have accessible labels.
- The magnifier opens literal, case-insensitive search over the top bar.
  Mark matches, select the current one, show “3 of 17”, and provide next and
  previous with wraparound. Count every match; show “No matches” when empty.
  ✕ or Back closes search first. Search runs off the main thread; the keyboard
  shrinks the text so a stepped-to match remains visible.
- Local read failure gives its reason, Retry and Open with. A cache copy that
  has disappeared closes the viewer with a note.

#### Conditional editor and Save requirements

The editor and Save are unavailable. The previously approved editor extends
this viewer with editing and undo, with Save uploading the text back. Any later
implementation must preserve these safety requirements:

- Saving through a symlink writes its real target, never replaces the link
  with a regular file. Resolve the real path, write a temporary file next to
  that target, preserve the target's mode, then rename over the real file.
- If the file changed on the device since it was opened, warn that it changed
  (for example, “changed on the device at 14:02”) and ask before overwriting.
  This protection applies whether or not the file was opened through a symlink.

These are conditional guarantees, not current Save UI. A later editor feature
requires approval; any unresolved interaction between the changed-file question
and the shared Undo rule must be explicitly resolved in that feature, without
silently dropping either requirement.

#### Colouring

Choose the grammar by name, otherwise by the first nonblank line: shell/node/
python shebang, JSON, YAML, INI section or log timestamp. A `.conf`, `.cfg` or
`.cnf` containing a JSON object or array is JSON; otherwise it is INI.

Supported colouring: Markdown with fenced bundled languages; logs (timestamps,
ERROR/FATAL/CRITICAL red, WARN amber, INFO/DEBUG muted); JSON, YAML, INI,
properties, systemd units, `.env` and TOML as INI; shell, JavaScript including
wb-rules, Python, Go, C, Lua, Dockerfile, SQL, XML/SVG, HTML and CSS. Unknown
formats stay plain text.

Grammars are bundled under `assets/textmate/`, from VS Code 1.142.0 (YAML:
1.90.0), with upstream sources, licenses and joni adaptations in
[NOTICE.md](../app/src/main/assets/textmate/NOTICE.md). Read a grammar on first
use; show plain text immediately and keep scrolling smooth while colouring
progresses in 1000-line batches. A line taking over 2 s to colour stays plain.
Grammar failure must not prevent reading the text. Colours use the app's
light/dark palette, with at least 4.5:1 contrast against the card background.

### 7. Large transfers, resume, background

Transfers belong to the file-screen visit. Downloads, Open and Share run at
most two at a time per screen; waiting rows can be cancelled. Uploads run one
at a time per screen and can be cancelled individually without cancelling the
rest of their batch. Navigation within the file screen does not end its visit.

Leaving the screen cancels transfers even when its session is kept with a
terminal. There is no service-owned transfer queue, transfer notification or
Transfers sheet, automatic retry, byte-offset resume, or restart restoration.
Backgrounding the app is not the same as leaving this screen, but Files does
not promise service-backed continuation through lock, process death or restart.

On a failed or cancelled download, discard the incomplete phone copy. On a
failed or cancelled upload, remove its own part file; never publish it under
the final name. Close cancels transfers before closing their connection and
allows cleanup to finish, with a bounded wait for a stalled device. Network
failure can prevent remote cleanup; a stale part may be encountered later.

### 8. Search

Search names only. File contents belong in the terminal, except for search
within text already open in the viewer.

- The magnifier replaces the top bar with a search field. Typing filters
  the current folder on the phone by literal, case-insensitive substring.
  ✕ or Back clears and closes it. Opening another folder ends search.
- “Search subfolders” searches below this folder over the Files SSH command
  channel (`sh -s` running `find`). Stream hits as relative paths, with
  “Searching… N found” and Stop. A folder hit opens it; a file hit opens its
  card over the results. Stop keeps hits received so far.
- Stay on the folder's file system (`find -xdev`), do not follow symlinks,
  and skip hidden names/subtrees while hidden files are off. Changing that
  toggle restarts a running search and clears completed results.
- Skip unreadable folders and report how many. Stop after 500 hits (“Showing
  the first 500”) or two minutes (“Stopped after 2 minutes”). Changing query,
  closing search, changing folder or leaving the screen stops both the run
  and its remote `find`. Late results from an ended/replaced run are ignored.
- Remote case folding is guaranteed only for Latin letters; other letters
  depend on the device locale, with a hint for such queries. A `/` in the
  query shows “Searching by name only, without /” instead of the action.
- No results: “Nothing found in subfolders”. Missing/failed `find`: its reason
  with Retry. A command-refusing login says so; no initial answer within 15 s
  says “The device did not answer the search command”, with Retry. An
  unavailable root says “Folder unavailable”. Offline filtering works on the
  cached listing; subfolder search requires a connection.
- Queries are literal, not glob patterns or regular expressions, and are not
  stored as search history.

## Feedback (the UI rules applied)

Show progress where an operation started, including queued/running state and
Cancel on its transfer row/card, and short completion results. A failed transfer
stays with its reason, Retry and Dismiss. Retry starts again; it does not resume
partial bytes.

Distinguish device refusals (permission denied, missing file, no space) from
phone-side read/write or folder-grant failures and transport loss. A refusal of
one file must not be reported as a lost connection. Lost folder access offers
the folder picker again; a batch asks once. A transport failure shows cached
listings as offline; refresh/Retry reconnects and can bring up a down tunnel.
A folder that cannot be entered from an otherwise live listing reports the
failure and leaves the current folder visible.

Unknown or changed host keys stop access for the shared SSH trust decision;
never silently accept a changed key. If saving Trust (or forgetting the old key)
fails, the key is not trusted (or the old one stays pinned), the screen shows
that error with Retry, and nothing crashes. The error names only the kind of
failure ("no space left", "no access" or "write error"), never a path or an exception message. Refused authentication offers
Change login rather than a blind retry. A target/login no longer available says the session
is gone, not that an empty folder was read successfully. A missing external app
or unshareable cache URI produces a visible handover failure, not a crash.

## Stored on the phone

These are required retention and cleanup policies for app-owned data, not a
promise that another app deletes a copy it has received.

| Data | Ownership and retention |
| --- | --- |
| Download-folder choice and hidden-files toggle | App-private settings; the folder uses a persistable system grant, which the owner/provider may revoke. |
| Last folder per connection | App-private settings, as a required persistence policy; this does not restore a live session or its transfers after restart. |
| Live folder, scroll positions, selection and transfer state | Session memory. A kept session retains folder/scroll state, not an active selection or transfer visit; no transfer restoration after process death. |
| Last listings and known home folder | App-private cache per profile/device/port (home lookup also uses user). Cache may be missing or unreadable. Save at most 5000 entries per folder and mark truncation. |
| Incomplete phone downloads | Remove on failure/cancel; not a durable `.part` resume store. Completed downloads belong to the chosen phone folder. |
| Files opened/shared with other apps | App cache; required cleanup is on exit and daily. Grant read access only to the handed files through FileProvider, not the entire cache. |
| Viewer copies | App cache; delete as the viewer closes. Share/Open with makes its own handover copy. Copies left by a lost screen/process must be cleaned once a few minutes old when a file screen goes and at the next start. |

Typed passwords stay in memory rather than navigation routes; remembered
passwords and keys remain governed by the SSH security rules. Cached names,
paths and downloaded content may be sensitive; they are not terminal traffic
for [terminal diagnostics](terminal-diagnostics-contract.md).

## Unavailable operations and retained constraints

The operations below remain unavailable. Removing their delivery stages does
not revoke these previously approved conditions. A later implementation needs
its own approved feature, including explicit reconciliation of service-owned
transfers with the current screen-visit lifetime; none of these conditions
changes current Keep both, cancellation or read-only behavior.

- **Folder transfer and clashes:** recursive downloads include selected folders
  and their contents, using the remembered system-picked phone folder. The
  specified clash choices are Replace, Skip and Keep both (`name (1).ext`),
  with an apply-to-all choice. Replace must keep the replaced file's permissions;
  a new file uses the device's default permissions (umask). Receiving another
  app's share for upload must let the owner choose the connection and folder.
- **Remote copy/move and properties:** Copy uses `cp -a`, preserving modes,
  timestamps and symlinks as symlinks, not copying their targets instead. Copy
  and Move use a selected destination and explicit “Paste here”. Move uses
  SFTP rename within one file system and `mv` across file systems. Required
  device commands use the Files SSH connection, within the same user's rights;
  a device missing a required command must say so. Properties include path,
  real path for a symlink, size, owner, mode and modification time; recursive
  folder size is requested explicitly (`du`), not fetched merely to open a card.
  Remote folder Delete includes its contents and reports the selected file and
  folder counts, under shared Undo, not the superseded “asks once” rule.
- **Archives:** Pack takes the selection into `name.tar.gz` by default or
  `name.zip` in the current remote folder (`tar -czf` / `zip -r`). Unpack
  supports `.tar`, `.tar.gz`, `.tgz` and `.zip`, into a same-name folder or
  here. Both are transfers with progress where possible and cancellation;
  background support is conditional on the separately approved transfer flow.
- **Service-backed transfers and resume:** the specified queue covers downloads,
  uploads and device-side operations, two at a time while the rest wait. It uses
  the tunnel foreground service so leaving the app or locking the phone does
  not stop it, with progress and Cancel all in the notification and a Transfers
  sheet reachable from file screens and the notification. Automatic retries
  are limited to three with increasing pauses, then wait for explicit Retry.
  Offset resume after a drop or restart uses phone `name.part` or remote
  `.name.frpjump-part`, continuing from the partial file's size **only if both
  the source size and modification time are unchanged**. Otherwise start fresh
  and tell the owner. Remove partial downloads on cancel. Publish a completed
  upload by final rename only, so a half-written upload never replaces a good
  file; current part-file ownership protections must not be lost.

The conditional editor/Save requirements above continue to apply independently.
These constraints do not authorize new operations or settle the outstanding
changed-file confirmation/Undo interaction.

## Non-goals

No device-to-device transfer, folder synchronization, in-app image/media preview
or binary editing. Recursive folder transfer, Replace/Skip clash choices,
remote copy/move/rename/delete/new-folder operations, recursive size calculation,
text editing/save (including symlink-safe save and change detection), and remote
pack/unpack are not current available actions. Receiving another app's share
as an upload is not part of the current flow.

A later implementation of these operations needs its own approved feature
contract and must respect the shared UI rules unless the operator explicitly
approves a change. In particular, absence of remote Delete is not an exception
to the inherited Undo policy. Temporary local-copy/part cleanup is separate
from deleting the owner's remote files.

## Acceptance

Check observable requirements, without treating this document as an execution
record:

- Browse and follow folder links; mark broken links; show cached listings and
  disabled remote actions offline, including missing/truncated-cache feedback.
- Download/upload files with name clashes; keep existing files intact, show
  progress, cancel just one row, and clean incomplete copies/own upload parts.
- Leave and return with a live terminal: keep folder/scroll state, cancel the
  old visit only, reject late metadata/progress, and allow fresh transfers.
  Ending the last terminal closes the off-screen session; final close cannot
  be resumed.
- Open a 2 MB log read-only, colour it progressively and step through search
  matches; show a very long line unwrapped. Binary names and a 5 MB log use
  another app, the latter with a size note. Preserve accessible visible text,
  copy limits, sensitive-copy marking and cache cleanup requirements.
- Filter names locally; search subfolders with streamed hits, literal queries,
  mount/hidden/symlink limits and distinct failures. Stop ends the remote search;
  ended-run callbacks cannot repopulate results.

`FilesKeeperTest`, `TransfersTest`, `TransferSlotsTest`, `TransferSnapshotsTest`
and `FilesSessionVisitTest` exercise ownership and transfer boundaries.
The visit fixtures use no SFTP sockets, real profiles, credentials or phone
provider queries; they do not establish live-network, background-service,
external-provider or retention-policy conformance. Viewer/search tests likewise
do not substitute for device accessibility or performance validation.

# Terminal: insert an image

Status: Approved, in progress (core in PR1, UI in PR2). Date: 2026-11-12. Base: main a87424c (0.3.64).
The operator approved the behavior: menu item plus Paste offer,
`~/.cache/frp-jump/images`, re-encode ≤2000 px without metadata, cleanup after
60 minutes. Two PRs: PR1 is the pure core, encoder, remote store and job with
tests (no UI, no surface-contract edits); PR2 is the UI, strings and
surface-contract updates. The decisions that were open in the draft, and the
findings of the emulator run and the independent review, are folded into the
text below.

## Goal and user-visible behavior

From the phone, get a picture to a program running in an SSH terminal tab
(Claude Code, codex, aider, a shell) in one or two taps. The app re-encodes
the picture without metadata, uploads it over the tab's existing SSH
connection to a private folder on the device, and pastes the picture's
absolute remote path into the terminal exactly as the existing Paste key
inserts a single line of text (the same accepted-paste path and wrapping
rule: the terminal emulator wraps it as bracketed paste when the remote side
enabled DECSET 2004, which tmux does for the outer terminal when its pane
enables it), without Enter. Claude Code 2.1.322 turns
such a pasted absolute image path into an "[Image #1]" attachment (verified
by the operator, also inside tmux); the app only inserts text and makes no
claim about what the remote program does with it.

Entry points (both only while the tab is Open):

1. Terminal top bar ⋮ → **Insert image** opens the Android system photo
   picker (no storage permission).
2. Paste key, when the phone clipboard holds an image and no text: instead
   of being dimmed, the key opens a short offer **Insert image from
   clipboard?**; confirming runs the same flow with the clipboard image.

## Scenarios

1. Pick a camera photo (JPEG, HEIC) in portrait/landscape: it is rotated by
   its EXIF orientation, downscaled so the long side is at most 2000 px,
   saved as JPEG, uploaded, and `~/.cache/frp-jump/images/img-YYYYMMDD-HHMM-xxxx.jpg`
   (absolute) appears at the cursor.
2. Pick a PNG screenshot: PNG out (lossless, metadata dropped, downscaled
   when over 2000 px).
3. Pick WebP/other still format: JPEG out, or PNG when it has real
   transparency. A GIF is decoded to its first frame and written as PNG;
   animation is lost and nothing is ever uploaded as `.gif`.
4. Tap Paste with an image-only clipboard: offer; confirm; as 1. With text on
   the clipboard (even together with an image) Paste keeps today's text
   behavior unchanged.
5. Under tmux and without tmux: same result; the inserted text is one line,
   so `planPaste` never asks.
6. Cancel while encoding or uploading: nothing inserted, the partial remote
   file is removed.
7. Failure (unreadable or unsupported image, too large, connection lost,
   permission or space on the device, SFTP disabled, name clash retries
   exhausted): nothing inserted, one short reason notice.
8. The tab is Reconnecting, Connecting, Ended or host-key state: the menu
   item is disabled and the Paste-key image offer is not offered (the key
   stays dimmed), as Paste is today.
9. Connection drops or the tab is closed mid-upload: the job ends, nothing is
   inserted; no automatic retry or replay into the next connection.
10. Several tabs: the image goes to the tab on which it was started, never
    to the tab selected at completion.

## Non-goals

- Android "Share to frp-jump" into an open terminal (v1 out of scope).
- Video, documents, multi-image selection, a gallery, a remote image browser,
  editing/cropping, EXIF-preserving mode, a size/quality setting.
- Sending Enter, quoting or wrapping the path, or detecting whether the remote
  program is an image-aware CLI.
- Deleting images on tab close or app exit (cleanup is age-based on the next
  upload only); Files-screen integration.
- Windows/non-POSIX hosts, SFTP servers without v3 `realpath`/`open` semantics.

## UI (exact labels; English / Russian are the only app languages)

| Where | English | Russian |
| --- | --- | --- |
| ⋮ menu item (after Snippets, before Close all) | Insert image | Вставить изображение |
| Clipboard offer title | Insert image from clipboard? | Вставить изображение из буфера? |
| Clipboard offer text | It is sent to the device and its path is inserted. | Оно отправится на устройство, а путь к нему будет вставлен. |
| Offer button | Insert image (sheet ✕ and swipe down cancel) | Вставить изображение |
| Progress, encoding (indeterminate) | Preparing image… | Подготовка изображения… |
| Progress, upload (determinate, %) | Uploading image… 45% | Отправка изображения… 45% |
| Progress action | Cancel | Отмена |
| Done (`NoticeKind.DONE`, short) | Image inserted | Изображение вставлено |
| Cancelled | no notice (the bar disappears) | то же |
| Unreadable (any format the phone cannot open: ImageDecoder calls an unknown format malformed, so no "unsupported" is claimed) | Could not read this image. | Не удалось прочитать изображение. |
| Too large (also running out of memory) | The image is too large. | Изображение слишком большое. |
| Connection lost (only when the tab's connection is gone, or the tab is no longer the one asked or is closed) | Connection lost. Nothing was inserted. | Соединение потеряно. Ничего не вставлено. |
| Permission (only the device's real permission-denied status) | The device refused the upload: permission denied. | Устройство отклонило отправку: нет доступа. |
| Not saved (any other device failure while writing, and names that kept clashing) | The device could not save the image. | Устройство не смогло сохранить изображение. |
| No space (only when the device reports it) | No space left on the device. | На устройстве нет места. |
| Not available (every failure while opening or starting SFTP: refused, no answer in time, garbage in the channel, with the connection up) | File transfer is not available on this device. | Передача файлов на устройстве недоступна. |
| No photo picker app | No photo picker is available. | Нет приложения для выбора фото. |
| Path unsafe | The home folder name has special characters. | В имени домашней папки есть спецсимволы. |
| Folder unsafe | The images folder on the device is not safe to use. | Папка для изображений на устройстве небезопасна. |
| Insert refused | Could not insert the path. | Не удалось вставить путь. |
| Paste key, image-only clipboard | tap = offer; semantics label stays "Paste", stateDescription "clipboard has an image" (ru «в буфере изображение») | |

Notes. The progress words change in whole tens of percent (the thin bar under
them is continuous), so a screen reader is not told about every packet. Tapping
Cancel hides the bar at once; the tab keeps its one-image guard (the menu item
stays off) until the job has really ended. Failure notices use `NoticeKind.WARN`, one notice with a fixed key so
repeats replace each other. Progress is a thin bar between the terminal and
the snippets row (same placement idea as the "new shell" bar), single line,
with its Cancel; it does not cover the keys. The menu item shows no state
text; while a job is running for that tab it is disabled (one job per tab).
No new key on the extra-keys row. Strings are new resources in
`values/strings.xml` and `values-ru/strings.xml` (Russian is the only other
language); no hard-coded text.

## States

`idle → (picking) → encoding → uploading → inserting → done`, with
`cancelled` and `failed(reason)` reachable from encoding, uploading and
inserting. `picking` is owned by the system picker/activity result; no
cancel UI (the picker's own back returns to idle without a notice). Only
`encoding` and `uploading` show the progress bar. `done` and `failed` return
to `idle` after the notice. One job per tab; a second request is ignored
(menu item disabled). State is in memory, per terminal-screen visit.

## Image pipeline

1. Input cap: the source's byte size up to **40 MiB**; a source of known length
   is read into one array of that size (no doubling), an unknown length with
   the same cap in chunks. Over the cap → "too large", nothing read further.
   The stream is handed to the job so Cancel closes it and a blocked read
   returns; the (CPU-bound) decode itself is not interruptible.
2. Decode with `ImageDecoder` (minSdk 29: HEIF yes, AVIF only from API 31, so
   AVIF on 29/30 fails to decode and is "unreadable"). `onHeaderDecoded`: software allocator
   (never hardware, to compress), `setTargetSize` so the long side is at most
   2000 px and no upscaling (decoding at reduced size also bounds memory),
   `setTargetColorSpace(sRGB)` so the pixels are sRGB (this does **not** keep a
   colour profile out of the encoder's output: see 5), animated formats
   decoded as `decodeBitmap` (first frame). On API 34+ a gain map (Ultra HDR)
   is dropped from the decoded bitmap before compressing, or `compress` would
   write Ultra HDR again. Decode exceptions → "unreadable";
   `OutOfMemoryError` anywhere in reading, decoding, compressing or
   flattening → "too large". Pixel-count guard before decode from
   `ImageInfo.size`: over **250 MP** → "too large" (decoding is downsampled
   via `setTargetSize`, so 108 and 200 MP phone photos work).
3. Orientation: the decoded `Bitmap` must already be upright. Android's
   `ImageDecoder` is documented/believed to apply EXIF orientation before
   returning the bitmap; **this is not yet proven in this repo and phase B
   must prove it** with an instrumentation test on a synthetic JPEG whose
   EXIF Orientation is 6 and 8 (and a HEIC sample if available). If the test
   shows it is not applied, rotate explicitly from `ExifInterface` before
   encoding. Orientation is applied before metadata is dropped.
4. Output format: PNG source → PNG; GIF → first frame as PNG; every other source
   → JPEG quality 85, or PNG when the decoded bitmap has real transparency
   (any pixel alpha < 255, scanned on the downscaled bitmap, never trusting
   `hasAlpha()` alone). Extension is `.jpg` or `.png`.
5. Metadata and colour profile: the platform encoders add more than pixels
   (verified on API 35: an ICC profile in both JPEG and PNG even for sRGB
   bitmaps, sBIT in PNG; Ultra HDR adds XMP, MPF and a second JPEG), so the
   output is not trusted. After `compress` a pure-Kotlin sanitizer keeps only
   the picture and then a separate scan checks the result, failing closed
   ("unreadable", nothing uploaded) on anything else.
   - JPEG kept: SOI, a canonical JFIF APP0 (no thumbnail), quantization and
     Huffman tables, restart interval, frame header, scans with their entropy
     data, EOI. Dropped: APP1–APP15, COM, non-canonical APP0, and every byte
     after EOI. An unknown marker (DNL and the like) fails. It assumes the
     platform encoder's YCbCr or gray output: APP14 (Adobe) is dropped with the other APPn.
   - PNG kept: IHDR, PLTE, IDAT, tRNS, IEND, each with its CRC checked. Dropped:
     every other ancillary chunk (colour profile, sBIT, sRGB, gAMA, cHRM,
     pHYs, text, EXIF, time) and every byte after IEND. An unknown critical
     chunk or a bad CRC fails. The result carries no colour profile or tag;
     viewers assume sRGB, which is what the pixels are.
   Both are pure JVM and unit-tested on constructed bytes (including entropy
   data with 0xFF00 and restart markers); the real encoders' output is
   checked in an instrumentation test: clean, and still decoding to the same
   size.
6. Output cap: **8 MiB**; a larger PNG is re-encoded as JPEG 85 once
   (transparency is flattened on white), else "too large".
7. The encoded bytes stay in memory (≤ cap) or in an app-cache temp file
   deleted in `finally`; nothing of the image is written to app storage
   otherwise. Content is never decoded twice from the picker URI.

## Remote side

- Reach: `SshSession.capturePasteTarget()` (existing; client + terminal
  output generation) is taken when the job starts. The SFTP channel is
  opened with `target.client.newSFTPClient()` on that same authenticated
  `SSHClient`, i.e. a new channel, not a login; no password/key is touched.
  If the tab has no `openClient()` the action is unavailable.
- Home: `canonicalize(".")` gives the absolute home. All paths are built from
  that absolute string; `~` is never inserted.
- Safe set: the final absolute path must match `[A-Za-z0-9._/-]+`; otherwise
  the job fails before uploading (the point of "no quoting").
- Folders: `<home>/.cache/frp-jump/images`. Missing components are created
  with mode 0700; mode is requested at create time (SFTP attrs), not by a
  later chmod, so there is no 0755 window. Existing `.cache` is accepted
  as is (it may be a symlink to a user location); `frp-jump`
  and `images` must be real directories (lstat DIR, not a link) owned by the
  login user (same uid as the canonical home directory, from `stat`; if
  the server reports no uid the check cannot prove ownership and fails);
  an existing `images` with group/other bits is tightened to 0700.
  Any other kind of thing there (file, link, foreign owner) fails the job.
- File: `img-YYYYMMDD-HHMM-xxxx.<ext>`, phone local time, `xxxx` four random
  `[a-z0-9]` characters from `SecureRandom`. Opened with
  WRITE|CREAT|EXCL and mode 0600 directly under its final name (no rename;
  the path is not shown to anyone before the upload completes). On
  `FILE_ALREADY_EXISTS` a new suffix is tried, at most 3 times. An existing
  file is never opened for write, truncated or replaced.
- Written through the SFTP channel with a bounded write-ahead (as Files does),
  progress by bytes sent. The SFTP engine timeout on this channel is 10 s
  **per request** (each response wait), not a total deadline: a large upload
  over a slow tunnel may take any time as long as every request is answered
  within 10 s; a server that stops answering fails after 10 s. This is
  sshj's own `SFTPEngine.timeoutMs` semantics and is covered by a test.
  What sshj does and does not time, in 0.41.2: every SFTP request after the
  opening waits up to `timeoutMs` (10 s here). The SFTP version exchange does
  not: it reads the server's answer without a timeout (the channel's read
  timeout is 0), so only our own bound ends that wait. The session channel open
  and the subsystem request wait up to the connection's own 30 s, and closing a
  channel waits up to 30 s for the peer's CLOSE and does not end a wait. So the
  opening (channel open, subsystem request, version exchange) runs on a helper
  thread and the job only waits for it: at most 10 s in all, and Cancel ends the
  wait at once. On the bound or on Cancel the helper is abandoned: whatever
  channel or engine it makes later is closed as soon as it exists, and a helper
  blocked inside sshj is interrupted once its channel exists (only our own
  helper thread, never a shared one). **Nothing ever marks a channel's input
  stream finished from our side**: sshj treats data that arrives on such a stream
  as a protocol error and drops the whole connection, the shell and the other
  tabs included, and replies can still be on their way after we close (up to 16
  write answers during an upload, a late VERSION). A channel is closed by
  sending CLOSE, on a thread of its own; the server's CLOSE ends reads blocked
  on it. Real bounds: Cancel hides the bar at once and ends a Cancel mid-upload
  after about one round trip on a live device (the server's CLOSE), and at most
  the 10 s request timeout on a hung one; the job, and the tab's one-image
  guard, end then, never when an abandoned helper or a close has finished. An
  abandoned helper lives until its blocked call ends: at once if it was
  interrupted, otherwise by sshj's own 30 s. Helpers are capped at 6 in all
  (live and abandoned together; a seventh opening is refused at once as "not
  available") and at 2 abandoned per connection, so one stalled tab cannot take
  the others' openings. Any failure while opening with the connection up is "not
  available" (also a shell startup file printing into the channel), and a failed
  or abandoned opening closes what it made, so repeated failures leave no channel
  behind (tests count them on the server and with stalled stand-ins for the
  network). One rare case remains: a channel that sshj itself confirms after its
  own 30 s open timeout can stay open until the connection closes.
- Cleanup runs once the file is fully written and closed, best effort, on a
  thread of its own, so the path is inserted without waiting for it, and it
  never reaches the owner. Its deadline is 15 s: checked before reading the new
  file's time, before the listing and before each delete, and a watchdog closes
  the cleanup's own channel at the deadline if one request hangs (the close runs
  on another thread, so the shared watchdog thread is never held). A request that
  hangs ends by the 10 s request timeout at the latest: the cleanup thread lives
  about 15 s plus one request timeout (10 s), and closing its channel can take
  sshj's 30 s more, on threads nothing waits for. The directory is read in batches and reading stops after
  500 entries. A file whose device time is missing or 0 is kept, never expired,
  and if the new file has no time nothing is judged. List the images dir; select entries that match the app's own name regex
  `^img-\d{8}-\d{4}-[a-z0-9]{4}\.(jpg|png)$`, whose `lstat` kind is
  regular FILE (symlinks and anything else are skipped, never followed), and
  whose mtime is more than 60 minutes older than the mtime of the file just
  written (the device's own clock, so phone/device clock skew does not
  matter). `rm` only those, by exact name in that directory. Any error is
  swallowed (class name logged only); the just-written file is never a
  candidate. Selection is a pure function over `(name, kind, mtime)` and the
  reference time.
- Failures are told truthfully: permission denied only for the device's
  permission status, no space only when reported, otherwise "could not save";
  an exception the app did not expect is "could not save" too, never a crash
  (the job and the launcher catch every non-cancellation throwable).

## Security and privacy

- No Android permission added: the photo picker needs none; the clipboard
  image is read only after the owner taps Paste and confirms. The picker
  `Uri` grant is used only during the job and not persisted.
- Availability checks of the clipboard use `ClipDescription` MIME types only,
  never the content (as `clipboardHasText` does), so the system's "pasted
  from" notice is not raised by polling. Content is read once after
  confirmation.
- Metadata (EXIF, GPS, camera, XMP, ICC) never leaves the phone; see Image
  pipeline. The image content itself is sent; the offer text and the product
  description state that the picture is sent to the device.
- Remote: private folder 0700, files 0600, both created by the login user; no
  overwrite; symlinks are never followed for `frp-jump`/`images` or cleanup
  targets; deletion only of own-pattern regular files in that one directory.
  Images stay for about 60 minutes (until the next upload cleans up; there
  is no timer and no cleanup on tab close). This is documented as a
  retention limit, not erased on a lost connection.
- Logs and diagnostics: never the local URI, image bytes, the remote path,
  home folder, user or host. Log only the failure class name and
  coarse stage. Terminal diagnostics (`terminal-diagnostics-contract.md`)
  record no new fields. The inserted path is terminal input like any paste
  and is subject to the same non-logging rule.
- The terminal paste goes through the existing accepted-paste path, so no
  Enter is sent and local acceptance is not claimed as remote receipt.

## Cancellation and lifecycle

- One coroutine per job, owned by the terminal screen's visit scope
  (leaving the terminal screen, closing the tab or process death cancels or
  kills it; no hidden background upload after the owner left).
- Blocking calls run through `blockingCancellable(onCancel = close what the
  call is on)`, on `Dispatchers.IO`: the picture's stream while it is read, the
  session channel while SFTP is opened, the SFTP channel after. The closer
  closes only these; it never disconnects the tab's `SSHClient`, never touches
  the shell channel. Returns only after the blocking call has returned.
- A tab that closes (including closed with its Undo pending, Close all
  sessions, its tunnel going) cancels its image job, and just before the path
  is pasted the job checks again that the tab is still the same session, still
  listed and not hidden by a pending Undo; otherwise nothing is pasted
  ("connection lost" wording) and the finished file stays for the age cleanup.
- Admission: at the start of the job (after the picker returned, before the
  picture is read), after encoding, and just before the path is pasted the job
  checks that the tab is still the one asked (`openClient() === captured
  client`, the same session, listed, not hidden by a pending Undo) and that it
  is not cancelled; mismatch → cancelled silently or "connection lost".
- Partial file: on cancel or failure after creation, the exact file the job
  created is removed on a fresh short-lived SFTP channel of the same client,
  inside `NonCancellable + Dispatchers.IO`, bounded by a 5 s timeout that
  includes opening that channel: the removal (open, remove, close) runs on a
  helper thread of its own that the job abandons at the bound, so the 5 s holds
  whatever sshj is waiting for and without ending any stream; the helper ends when
  its blocked call does and closes its channel itself; if the
  connection is gone the removal cannot run: the file (0600, own pattern)
  is picked up by a later cleanup after 60 minutes. Only the job's own
  created name is removed.
- Insert: after upload, `SshSession.paste(path, target, enter = false)`
  revalidates client identity and terminal output generation; if the tab
  reconnected, closed or the terminal ended meanwhile, `INSERTED` is false
  → "Connection lost. Nothing was inserted." (a complete file stays when the
  insertion is refused or the tab changed, and is cleaned by age; only a
  cancel removes it). Never retargets another tab and never replays after
  reconnect.
- Rotation/configuration change: `MainActivity` handles
  `orientation|screenSize|density|uiMode|locale…` itself (no recreation), so
  the job and bar survive rotation, font-scale and theme changes. The
  progress state is in the screen's state holder; a recomposition does not
  restart or duplicate the job. A real activity recreation or process death
  cancels the job and loses the progress (nothing is persisted); the photo
  picker's result for a process-killed app is dropped without a notice.
- Reconnecting: unavailable (see UI); a job already running when the
  connection drops fails as "connection lost". The Paste key is dimmed for an
  image-only clipboard whenever the tab is not Open or already has an image
  on its way. The Paste tap decides image-or-text from the clip's description
  alone and shows the offer without reading the clip; its content is read only
  after the owner confirms.
- Tmux copy-mode guard: the paste goes through `SshSession.typed()` +
  `RemoteTerminal.paste`, same as Paste, so it leaves copy mode first.

## Limits

Input ≤ 40 MiB, ≤ 250 MP; long side ≤ 2000 px; output ≤ 8 MiB; one job per tab; at most 3 name-clash retries; cleanup
cleanup deadline 15 s on its own thread and 500 directory entries read; SFTP timeout 10 s per request (no total deadline), opening bounded to 10 s in all and cancellable at once, at most 6 opening helpers in all and 2 abandoned per connection;
write progress reported at most every 100 ms.

## Accessibility

- Menu item and offer buttons are standard Material controls (48 dp min
  height), with the text above as their accessible name.
- The progress bar is one `liveRegion = Polite` node reading "Uploading
  image, 45 percent"; Cancel is a focusable button with name "Cancel". The
  bar wraps (never cut) at 200% font scale and does not hide the extra keys;
  the terminal keeps at least its minimum rows in landscape with the
  keyboard up.
- Failure/done notices use the existing top notice host (readable by
  TalkBack). The Paste key keeps its label; the stateDescription says what a
  tap will do when the clipboard has an image.
- No colour-only state: text plus percentage.

## Affected surface contracts

Read at their real paths:

- [App and shared UI, §7 SSH console](../../contract.md#7-ssh-console)
- [Terminal panel](../../terminal-panel-contract.md)
- [Terminal text input](../../terminal-text-input-contract.md)
- [SSH persistence](../../ssh-persist-contract.md)
- [Files (SFTP)](../../files-contract.md): cross-reference only (separate
  connection, no change to Files promises).
- [Terminal diagnostics](../../terminal-diagnostics-contract.md): no change
  except one sentence (below).

Exact promises to add on completion (written as current behavior):

- `contract.md` §7 (extra keys paragraph and entry points): "⋮ → Insert image
  opens the system photo picker. Paste, with an image and no text on the
  clipboard, offers 'Insert image from clipboard?' instead of being dimmed;
  with any text it pastes the text as before. The selected image is
  re-encoded without metadata (long side ≤ 2000 px; PNG stays PNG, others
  become JPEG or PNG with transparency; animation is not kept), uploaded to
  `~/.cache/frp-jump/images/` on the tab's open connection, and its absolute
  path is pasted as a normal paste with no Enter. Unavailable while the tab is
  not Open. Progress and Cancel are shown; a failure inserts nothing."
- `terminal-panel-contract.md` (Keys table / ⋮ row): add "Terminal ⋮ → Insert
  image" next to "Terminal ⋮ → Text input", and in the Paste row "an
  image-only clipboard enables Paste to offer the image"; panel geometry
  rule: the progress bar must not hide keys at 200% font in landscape.
- `terminal-text-input-contract.md`, Clipboard compatibility: "Paste key
  availability is text-or-image from the clipboard description. An
  image-only clipboard never pastes its content URI as text from any paste
  entry (key or selection menu); it offers the image flow. The inserted
  image path is one line and uses the same accepted-paste, destination and
  generation binding; local acceptance is not remote receipt." (This fixes
  the adjacent issue in the recon: today `onPasteTextFromClipboard` on an
  image clip would `coerceToText` its `content://` URI.)
- `ssh-persist-contract.md`, Compatibility and remote ownership + Security:
  "The app opens a separate SFTP channel on the tab's authenticated
  connection for image insertion; no new login, no change to the shell
  channel, tmux, reconnect or host keys. Remote data the app creates:
  `~/.cache/frp-jump/images/` (0700) with files 0600 named
  `img-YYYYMMDD-HHMM-xxxx.(jpg|png)`, never overwritten, own-pattern
  regular files older than 60 minutes deleted on the next upload (best
  effort, no symlink following); not removed on tab close. Reconnect never
  replays or retries an insertion."
- `files-contract.md` (Scope or Non-goals): one sentence: "Image insertion
  from the terminal uses the terminal tab's connection, not a Files visit; it
  does not change Files ownership or transfers."
- `terminal-diagnostics-contract.md`: one sentence "Image insertion records
  no image content, URIs, paths or file names in diagnostics."

## Acceptance criteria

1. ⋮ → Insert image opens the system photo picker, requests no permission,
   disabled when the tab is not Open.
2. With an image-only clipboard, tapping Paste shows the offer; text on the
   clipboard keeps the old behavior bit-for-bit; selection-menu paste of an
   image-only clip does not insert a `content://` string.
3. A JPEG with EXIF orientation 6 comes out upright, with no EXIF/XMP/GPS;
   long side ≤ 2000 px; a PNG stays PNG; WebP becomes JPEG, or PNG with real
   transparency; a GIF becomes a PNG of its first frame.
4. The remote file is in an absolute `…/.cache/frp-jump/images/`, folder
   0700, file 0600, name matches the pattern, never overwritten (clash
   retried with a new suffix); the inserted text is that absolute path, no
   `~`, no Enter, inserted through exactly the same accepted-paste path and
   wrapping rule as the Paste key uses for a single line of text. The
   contract states nothing about tmux beyond that.
5. Upload uses a new channel on the tab's connection: no second login
   (asserted on the synthetic server: auth count unchanged).
6. Cancel at encode, at upload and at insert leaves nothing inserted and
   (connection up) no partial remote file; the shell stays interactive.
7. Failures produce exactly one short reason and no insertion; connection
   drop mid-upload inserts nothing and does not retry on reconnect.
8. Cleanup removes only own-pattern regular files older than 60 minutes in
   that directory, never symlinks or other names, never the new file, and a
   cleanup failure does not fail the upload.
9. No new permission, dependency in the runtime graph, or log line with
   paths/URIs/content.
10. Portrait, landscape and 200% font: progress bar and offer fit without
    cutting text or hiding the keys; TalkBack names are present.

## Validation (test plan)

Pure JVM unit tests (`:app:testDebugUnitTest`):

- `ImageNamingTest`: pattern, phone-local timestamp, suffix alphabet/length,
  clash retry sequence, extension per format, safe-path regex incl. spaces,
  quotes, `~`, unicode, `..`.
- `ImageCleanupSelectionTest`: only regex matches, FILE only (LINK/DIR/OTHER
  skipped), 59/60/61 minutes boundary against the device-clock reference, the
  new file excluded, entry cap, bad names (`img-..`, extra suffix) skipped.
- `ImageBoundsTest`: target-size function (long side 2000, no upscale,
  portrait/landscape, 1×1, extreme aspect), pixel cap, input cap.
- `ImageFormatPolicyTest`: source mime → output format incl. transparency
  decision input, PNG over cap fallback.
- `ImageSanitizerTest`: the stripper on constructed JPEG/PNG bytes: APPn, COM, ICC, MPF, a second
  picture and trailing bytes go; entropy data with 0xFF00, restarts and fill bytes is copied exactly; kept
  PNG chunks keep a valid CRC; unknown critical chunks, bad CRCs and truncation fail closed.
- `ImageContainerScanTest`: the JPEG/PNG walker on constructed bytes: finds
  APP1 Exif, XMP, COM, `eXIf`, `tEXt`, `iCCP`; accepts clean ones; truncated
  and malformed input.
- `ImageInsertJobTest` (fake `RemoteImageStore` and fake paste target, as
  `FakeFs`): state transitions, cancel in each state, partial removal, tab
  identity change, insert refused, one job per tab, failure mapping.
- Real SFTP upload engine against Apache SSHD with its SFTP subsystem and a
  real sshj client (JVM): modes 0700/0600 at create time, EXCL clash,
  symlinked `images`/`frp-jump` refused, `canonicalize` of home, cleanup on a
  tree with symlinks and foreign names, cancel mid-write removes the file,
  no second authentication. Needs the SFTP module of the already-used
  `sshd-core` version as a **test-only** dependency (`sshd-sftp:2.14.0`,
  `testImplementation`); the app runtime graph must stay unchanged
  (verify with the resolved runtime classpath).

Instrumentation (`:app:assembleDebugAndroidTest`, then on `emulator-5580`,
API 35 only):

- `ImageEncoderTest`: real `ImageDecoder`/`Bitmap.compress` on generated
  samples: EXIF 6/8 upright, metadata absent in JPEG and PNG output (walker),
  2000 px bound, transparency → PNG, ICC absent, animated GIF first frame,
  oversize and corrupt input errors, no leak of bytes on failure.
- `TerminalImageInsertTest` (compose, in the style of
  `SshClipboardAuthorityTest`, no sockets): ⋮ item enabled/disabled by state,
  Paste with image/text/both clipboards, offer sheet, progress bar and
  Cancel, no-key-hidden checks; instrumentation arguments
  `composerOrientation=landscape`, `composerFontScale=2.0` as the existing
  terminal tests use. Reset font scale to 1.0 afterwards.
- Emulator scope is API 35 only; physical-device, vendor photo pickers,
  HEIC/AVIF on API 29-34, real Claude Code behavior and live-service SFTP
  are not covered and are reported as such.

Full gates per `AGENTS.md`: focused tests, then
`./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`
and `git diff --check`; Gitleaks before push; `repo-docs` checker for the
documentation updates; inspect actual instrumentation counts.

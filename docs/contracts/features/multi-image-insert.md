# Insert several images into the SSH terminal at once

Status: Approved by the operator on 2026-11-12 (multi-photo insert, agreed design). Complete for final verification once the checks below have run; this file is then deleted.
Date: 2026-11-12. Base: `origin/main` a15d2d8 (0.3.67).

## 1. Goal and user-visible behavior

The owner sends up to 10 photos to the device in one go and gets all their paths in one paste. Today one image is sent at a time. A single image and a text clip behave exactly as before.

## 2. Scenarios

| # | Scenario | Result |
| --- | --- | --- |
| S1 | ⋮ → Insert image, long press on Paste, or the editor's Insert image button | One multi-select photo picker (`PickMultipleVisualMedia`) at all three entry points; at most 10, or the platform's lower `MediaStore.getPickImagesMaxLimit()` on API 33+ (10 where there is no such limit) |
| S2 | Picker returns 1 image | Exactly today's flow, words and notices |
| S3 | Picker returns N (2..10) | One batch for the tab: images go one at a time over ONE SFTP channel (encode one, upload it, release it; never all in memory); all successful absolute paths go in ONE paste, joined by single spaces, in the picker's order, no Enter, no newline |
| S4 | A fallback picker returns more than 10 | The first 10 are used, a short notice says so |
| S5 | Clipboard holds several images and no text | Paste offers one insert: "Insert N images from clipboard?" (ru «Вставить изображения из буфера (N)?»), N at most 10. A text clip and a single image are unchanged |
| S6 | Progress | A batch bar says "Image N of M · P%" (ru «Изображение N из M · P%»), "Image N of M · preparing" while one is read; a single image keeps "Uploading image… P%" |
| S7 | Cancel mid-batch | Stops the whole batch: nothing is inserted; the part file is removed as today; the complete files this batch created are removed best effort, all together within the existing 5 s removal bound (including opening its channel); files not reached stay and go with the 60-minute cleanup |
| S8 | An image cannot be read (too large, unreadable) | It is skipped; the rest go |
| S9 | A device-side failure during an upload | The batch ends there (the rest are not sent), the part file is removed |
| S10 | Some failed, some inserted | The inserted paths are pasted; one notice: "2 of 5 images were not sent: The image is too large." (ru «2 из 5 изображений не отправлены: Изображение слишком большое.») with the first reason |
| S11 | All failed | Today's single-reason notice, nothing inserted |
| S12 | Connection lost or tab changed (also between images) | Nothing is inserted; complete files stay (cleaned by age), the part file is removed when the connection allows |
| S13 | One batch per tab | The menu item, the editor button, the Paste long press and the clipboard offer are off while the tab has a batch on its way, also after Cancel until the job has really ended |
| S14 | Age cleanup | Runs once per batch, after the last image is written, on the batch's channel |

## 3. Non-goals

- No newline-separated paths (the remote program keeps them as text), no Enter.
- No reconnect replay, no background upload, no more than 10 images, no per-image retry.
- No change to the transport: opening, helper threads, per-request timeouts, closing and the helper caps stay as in the SSH contract.
- No change to the encoder (metadata, sizes, caps), to file names, modes or the exclusive create.

## 4. Affected surface contracts

- [docs/contract.md](../../contract.md), section 7 (Insert image).
- [docs/ssh-persist-contract.md](../../ssh-persist-contract.md), "Remote image files".
- [docs/terminal-panel-contract.md](../../terminal-panel-contract.md) (the bar's geometry with the disclosure: unchanged, text is longer) and [docs/terminal-text-input-contract.md](../../terminal-text-input-contract.md) (editor button, bar in text mode: unchanged flow).

## 5. Interfaces and constraints

- `ImageInsertJob` takes a list of sources (first 10 used); outcome adds `InsertedPartly(inserted, notSent, firstReason)`; state adds `Preparing(index, count)` and `Uploading(..., index, count)`.
- Every existing promise holds: metadata stripped, size caps, 0600 exclusive create, per-request timeouts, helper caps, a channel is only closed by CLOSE (never by ending its input stream), no crash on any throwable, logs carry class names only, the stale-tab guard and the hidden-tab rules.
- Memory: at most one encoded image is alive at a time.

## 6. Acceptance criteria

- A batch of 3 uploads in order over one channel (no second opening) and pastes once, space-joined.
- Partial failure inserts the successes; the notice counts the rest; all failing gives the single-reason notice.
- Cancel mid-batch inserts nothing and removes the batch's files and the part file; connection loss mid-batch inserts nothing.
- Progress reports N of M; cleanup runs once; 10 is the maximum.
- The bar text "Image 2 of 5 · 40%" fits at 200% font in portrait and landscape and covers neither Cancel nor the keys' disclosure; the multi-image offer sheet shows.

## 7. Validation

JVM: `ImageInsertJobTest`, `SftpRemoteImagesTest`, `ImageRunTest`, `ClipKindTest`. Instrumentation: `TerminalImageInsertTest` (portrait 2.0 EN, landscape 2.0 RU). Mutations (each reverted): newline join; a second channel per image; Cancel inserts the finished ones. Full gate from AGENTS.md, `git diff --check`, repo-docs checker.

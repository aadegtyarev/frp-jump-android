# Image insertion: more entry points

Status: Approved by the operator on 2026-11-12 (long press on Paste; editor button with the path going straight to the terminal; multi-photo deferred). Base: `origin/main` e8c7d5b (0.3.66).

## Goal and user-visible behavior

Two more ways into the existing terminal image insertion (photo picker, upload, path pasted without Enter):

1. A long press on the Paste key opens the system photo picker, exactly like ⋮ → Insert image. A tap is unchanged.
2. The text editor has an image button that opens the same picker. The path goes straight to the terminal, not into the draft.

## Scenarios

| # | Scenario | Result |
| --- | --- | --- |
| S1 | Long press on Paste, tab Open, no image on its way | Picker opens; the normal flow runs |
| S2 | Same, clipboard empty | Picker still opens; a tap still does nothing and is announced as unavailable |
| S3 | Long press, tab not Open or image already on its way | Nothing happens |
| S4 | Tap on Paste | As before (text pastes, an image clip offers the flow); never opens the picker |
| S5 | TalkBack on Paste | Long-click action "Insert image" / «Вставить изображение»; state description kept |
| S6 | Editor image button, same availability rule | Opens the picker; dimmed when unavailable; label for TalkBack |
| S7 | Upload ends from the editor | Absolute path pasted into the terminal of the starting tab, no Enter; draft text, selection and revision unchanged |
| S8 | Image in flight with the editor open (compact or expanded) | Bar with progress and Cancel visible; Insert and the field stay reachable; no disclosure reserve (there are no keys) |
| S9 | Picker missing | Existing "No photo picker is available." notice |

## Non-goals

- Several photos at once (not approved). Extension point only: the paste step joins absolute paths with single spaces into one paste (`joinImagePaths`), never newlines; the job keeps one source today.
- No change to encoding, upload, cleanup, cancellation or security rules; no Enter; no draft changes.

## Affected surface contracts

- [docs/contract.md](../../contract.md) section 7 (Insert image)
- [docs/terminal-panel-contract.md](../../terminal-panel-contract.md) (Paste row, bar geometry)
- [docs/terminal-text-input-contract.md](../../terminal-text-input-contract.md) (editor controls)
- [docs/ssh-persist-contract.md](../../ssh-persist-contract.md) (Remote image files; unchanged)

## Interfaces and constraints

- One launcher path in `TerminalScreen` serves the menu, the Paste long press and the editor button, with the `ActivityNotFoundException` guard.
- `ExtraKeys(onInsertImage)`, `TerminalTextComposer(canInsertImage, onInsertImage)`, `ImageProgressBar(keysShown)`.
- Paste stays enabled for `combinedClickable` while the picker is on offer, so its long press works with an empty clipboard.

## Acceptance criteria

All scenarios above hold in portrait/landscape at 1.0 and 2.0 font scale; existing image, paste and editor tests still pass; mutations (long press while busy, tap opens picker, editor insert changes draft, newline join) are caught.

## Validation

JVM tests, instrumentation (`TerminalImageInsertTest`, `TerminalTextComposerTest`), full gates, `git diff --check`, repo-docs checker, screenshots `entry-<name>-<orientation>-<font>.png`.

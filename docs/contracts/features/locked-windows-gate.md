# Feature: no app windows over the lock screen

Status: Approved by the operator via the peer session (Р20, 2026-11-13). Release: the one after 0.3.74 (0.3.74 shipped as F11). It was implemented in parallel with 0.3.72
(launch gate) and 0.3.73 (F10).

## Goal and user-visible behavior

While the fingerprint lock is engaged, no window the app itself owns appears over the lock screen or accepts input. The app keeps its
screens composed under the lock (back stack, open pages, polling), but every separate window is left out while locked:

- Compose `Dialog` and `AlertDialog`;
- `ModalBottomSheet`;
- `Popup` and `DropdownMenu`;
- the terminal's selection menu;
- the assistant's confirmation dialogs, including "Always";
- the paste and host-key trust dialogs;
- WebView JavaScript `alert`, `confirm` and `prompt`.

Today they stay above the lock screen and take touches. One example is the "Forget profile" confirmation, a destructive action reachable
without authentication.

After a successful unlock every such window is shown again, with the same state, as if the lock had never been there.

## Scenarios

- A dialog or sheet is open, the owner leaves longer than the interval, then returns. The lock screen shows with no dialog above it.
  After unlock, the dialog is back as it was, with its text field contents and its scroll position.
- A web page calls `alert`, `confirm` or `prompt` while locked. Nothing is shown. The call waits, and its dialog appears after unlock.
  If the page or the screen goes away first, the call is cancelled the way WebView cancels it.
- A dropdown or popup was open when the lock engaged. It does not show while locked and may simply be closed after unlock. Menus need
  no state restore.
- The WebView file chooser is an external launch, already held by the 0.3.72 launch gate. It is listed here only for the inventory.
- System windows the app does not own (the biometric prompt, the system PIN screen, permission dialogs) are not affected.
- Unlocked: no change at all.

## Non-goals

- No change to what the lock guards or to its timing rules.
- No change to the screens under the lock.
- No new UI.

## Affected surfaces

- [`docs/contract.md`](../../contract.md) §9 "Fingerprint unlock", plus the shared UI rules if a rule about windows belongs there.
- [`docs/terminal-panel-contract.md`](../../terminal-panel-contract.md) and
  [`docs/terminal-text-input-contract.md`](../../terminal-text-input-contract.md), only if their menus or dialogs are named there.

## Interfaces and constraints

- One mechanism, not per-screen patches:
  - a `CompositionLocal` such as `LocalAppLocked`, provided by `MainActivity` from `app.lock.locked`;
  - app-wide wrappers (for example in `ui/Controls.kt`) for Dialog, AlertDialog, ModalBottomSheet, Popup and DropdownMenu. While locked
    they do not compose the window; their state stays in the caller, so it returns after unlock;
  - every direct use of these window APIs in `app/src/main` goes through the wrappers.
- A JVM source-scan test fails if a direct use of a window API appears outside the wrapper file. That stops a new screen from silently
  bypassing the gate.
- WebView JavaScript dialogs: `onJsAlert`, `onJsConfirm` and `onJsPrompt` (and `onJsBeforeUnload`, if it is used) are deferred while locked
  and shown after unlock, or cancelled when the page or screen goes away.
- `MainActivity` is also being changed by 0.3.72 (the launch overrides). Keep this change in its own spots so the merge stays simple.
- Accessibility: while locked, the hidden windows do not reach TalkBack.

## Acceptance criteria

1. The PR lists every window creation site in `app/src/main`, with file:line and which wrapper it now uses.
2. The source-scan test passes, and fails when a direct use is added (show this once).
3. An instrumented test opens a dialog, a sheet and a dropdown, then sets locked. No window node exists: `isDialog()` and
   popup roots count 0, and taps where the dialog's buttons were do not reach it. After unlock the dialog and sheet are back with the
   text that was typed into them.
4. A WebView test (or a unit test with a fake chrome client) shows that a JS alert while locked is deferred, appears after unlock and is
   cancelled when the page goes away.
5. The §9 text is updated.
6. The full gate is green. Emulator: the new tests, plus the lock tests and a chat screen smoke run (the assistant confirmation dialog
   uses the wrapper).

## Validation

- `./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`
- `git diff --check`
- The repo-docs checker.
- Emulator-5580 runs, as in acceptance criterion 6.

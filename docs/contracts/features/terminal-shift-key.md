# Terminal additional keys: Shift replaces ^L

Status: Approved by the operator on 2026-11-12 (request relayed: replace ^L with Shift; semantics delegated to and fixed by the parent). Implemented; final verification pending.

## Goal and user-visible behavior
- In the additional keys, `^L` is replaced by a sticky `Shift` toggle. First group order: Esc, Tab, Ctrl, Alt, Shift, ^D, ^Z, ^R. Second group unchanged.
- Shift behaves like the Ctrl and Alt toggles: same look and Switch semantics, applies to the next key and then releases, no lock; combines with Ctrl and Alt.
- Ctrl+L stays available as sticky Ctrl, then `l`.

## Scenarios
- Shift, then a key-code key (arrows, Tab, Esc, Enter, Backspace, Home, End, PgUp, PgDn): sent with the Shift modifier through the emulator's own key table (Shift+Tab `ESC [ Z`, Shift+Up `ESC [ 1 ; 2 A`, Ctrl+Shift+Up `ESC [ 1 ; 6 A`); the emulator's table is authoritative (Shift+PgUp is plain `ESC [ 5 ~`).
- Shift, then a typed character (soft keyboard or `| ~ / -`): a letter is uppercased, anything else is unchanged; Shift is released. Ctrl/Alt are still applied by Termux to the resulting character.
- Shift, then a ready combo (^C ^D ^Z ^R): the control byte is sent; Shift (and Ctrl/Alt) are released unapplied.
- Held-repeat keys (arrows, backspace) repeat with the Shift held at touch-down.
- Paste, entering Text mode and snippet send release Shift with Ctrl/Alt.
- Hardware keyboard keys read the sticky Shift like Ctrl/Alt.

## Non-goals
Lock/double-tap, Shift for the Text editor, new key descriptions (Ctrl/Alt have none), other key changes.

## Affected surface contracts
- [Terminal panel](../../terminal-panel-contract.md)
- [Shared UI rules](../../contract.md)
- [Terminal text input](../../terminal-text-input-contract.md) (only mentions Ctrl/Alt generically; no change)

## Interfaces and constraints
- `Modifiers.shift`/`takeShift()`; `readShiftKey()` takes it; `ViewClient.onCodePoint` takes it, repeats `inputCodePoint` with the shifted character and the already-taken Ctrl/Alt flags, so Termux applies Ctrl/Alt itself.
- Remove `ExtraKey.CTRL_L` and `terminal_key_ctrl_l`.

## Acceptance criteria
Order and behavior above; unit tests pin slot order, bytes and release rules; existing tests pass.

## Validation
Unit tests, full gate from AGENTS.md, `git diff --check`, docs checker; emulator portrait, landscape, 200% font for the extra rows.

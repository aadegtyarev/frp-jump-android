# Terminal additional keys: Shift replaces ^L

Status: Approved by the operator on 2026-11-12 (request relayed: replace ^L with Shift; semantics delegated to and fixed by the parent). Implemented; final verification pending.

## Goal and user-visible behavior
- In the additional keys, `^L` is replaced by a sticky `Shift` toggle. First group order: Esc, Tab, Ctrl, Alt, Shift, ^D, ^Z, ^R. Second group unchanged.
- Shift behaves like the Ctrl and Alt toggles: same look and Switch semantics, applies to the next key and then releases, no lock; combines with Ctrl and Alt.
- Ctrl+L stays available as sticky Ctrl, then `l`.

## Scenarios
- Shift, then Tab/arrows/Home/End: sent with the Shift modifier through the emulator's key table (Shift+Tab `ESC [ Z`, Shift+Up `ESC [ 1 ; 2 A`, Ctrl+Shift+Up `ESC [ 1 ; 6 A`, Alt+Shift+Tab `ESC [ Z`).
- Shift, then Esc/Enter/Backspace: bytes unchanged, Shift used up.
- Shift, then PgUp/PgDn: sent without Shift (Termux's handleKeyCodeAction would scroll locally or send arrows); Shift used up. No other extra key is intercepted for Shift (checked in the bytecode: only key codes 92/93).
- Shift, then soft-keyboard text: Termux's own handling (readShiftKey, then `Character.toUpperCase`, then Ctrl/Alt) uppercases the next typed character and releases Shift; Ctrl+Shift+a is `\u0001`, Alt+Shift+a is `ESC A`.
- Shift, then `| ~ / -`: typed as is; Shift released.
- Shift, then a ready combo (^C ^D ^Z ^R): control byte; all three released.
- Held-repeat keys repeat with the Shift held at touch-down.
- Released like Ctrl/Alt: by the next key or typed character, ready combos, entering Text mode, snippet send and the confirmed multi-line paste. A direct paste leaves sticky modifiers as they are (unchanged).
- Hardware keyboard keys read the sticky Shift like Ctrl/Alt.

## Non-goals
Lock/double-tap, Shift for the Text editor, new key descriptions (Ctrl/Alt have none), other key changes.

## Affected surface contracts
- [Terminal panel](../../terminal-panel-contract.md)
- [Shared UI rules](../../contract.md)
- [Terminal text input](../../terminal-text-input-contract.md) (only mentions Ctrl/Alt generically; no change)

## Interfaces and constraints
- `Modifiers.shift`/`takeShift()`; `ViewClient.readShiftKey()` returns `takeShift()`; `pressFor` adds `KEYMOD_SHIFT` except for PgUp/PgDn and consumes Shift for typed symbol keys. No hook in `onCodePoint`.
- Remove `ExtraKey.CTRL_L` and `terminal_key_ctrl_l`.

## Acceptance criteria
Order and behavior above; unit tests pin slot order, bytes and release rules; existing tests pass.

## Validation
Unit tests, full gate from AGENTS.md, `git diff --check`, docs checker; emulator portrait, landscape, 200% font for the extra rows.

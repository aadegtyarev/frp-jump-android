# Compact terminal input panel

Status: compact panel released in 0.3.30; shared column geometry added in 0.3.37.

## Scope

Keep the familiar main row (^C, Paste, left/up/down/right, Backspace,
Enter, keyboard) and the draft/paste guarantees in
[terminal-text-input-contract.md](terminal-text-input-contract.md).
Remove the dedicated Keys/Text selector row.

- A short tap on the keyboard button keeps its current show/hide behavior.
- A long press opens the Text composer once, without also triggering a
  short click. Expose a labelled accessibility long-click action.
- Terminal ⋮ → Text input opens or focuses the same editor as an explicit
  alternative to the gesture. Settings → Terminal explains the gesture
  and the menu entry; no new persisted setting is needed.
- In Text mode, show only the editor: no main row, no additional key rows,
  and no additional-keys disclosure. The `>_` action returns to Keys and
  keeps the draft. Preserve expand/collapse, short Insert without Enter, and
  the existing session-bound paste confirmation behavior.
- Additional keys start collapsed. A small ⋯ disclosure above the main
  row toggles them explicitly, rather than opening automatically with
  the system keyboard. No extra full-width header row is reserved.
- Keep the disclosure's target independent from the keyboard button and
  other terminal keys. Its full target must not overlap a real key when
  the additional rows open; keep its position anchored to the main row.
- All rows use the main row's nine column tracks. Eight extra keys use
  the same cell widths; on wide screens sixteen use half-cells in one row.
  Wrapped rows use whole-cell multiples, with a larger minimum width for
  large fonts. Do not stretch each row to an unrelated set of columns.
- The disclosure symbol is centred on the keyboard column and the last
  extra row. Its independent touch target ends above the main row, does
  not cover another key and stays within the panel's viewport. Its height
  is 48 dp; width is up to 48 dp, limited by the available space on narrow
  viewports. Keep the existing key order and compact row heights.
- Entering Text collapses the additional rows. No automatic remote-program
  detection or changes to the direct-input preference.

## Checks

- Default Keys has only the existing main row; additional keys are absent.
- Disclosure opens/closes additional keys without sending terminal input
  and does not steal IME focus.
- Keyboard short click requests one visibility toggle; long press opens
  Text and never also toggles the keyboard.
- Verify shared tracks and symbol centres in screen coordinates, including
  the independent popup window. Check complete target bounds and no overlap
  with real keys at normal and 320 dp widths, portrait/landscape and 200% font.
- Text contains no terminal key rows or disclosure. Returning to Keys
  keeps the draft and leaves additional rows collapsed.
- Long-click accessibility action, explicit menu entry, and Settings hint
  use Russian and English labels.
- Existing composer tests remain green: per-session drafts, visible IME
  composition, full-screen/Back, selection and focus through rotation,
  and safe insertion.
- Run JVM tests, debug and AndroidTest builds, Lint, documentation checks,
  and the API 35 component suite in portrait, landscape and 200% font.

## Validation

### 0.3.30 baseline

- 1,146 JVM tests passed; debug/AndroidTest APK builds and Lint passed.
- Twelve API 35 component/IME tests passed in each of portrait, landscape
  and 200% font configurations (36 executions). Tests assert orientation
  and font scale, short/long keyboard actions, the accessibility long-click
  label, stable keyboard-button bounds when additional rows open, and the
  absence of all key rows in Text mode. Existing draft/selection/rotation,
  native composition and paste-action component checks remain green.
- Screenshots inspected for collapsed/expanded keys and the integrated
  editor block. Documentation and whitespace checks passed.
- No physical-phone, keyboard-vendor, TalkBack-by-ear or end-to-end SSH
  performance claims follow from these component tests.

### 0.3.37 shared grid

- 1,198 JVM tests, debug/AndroidTest builds and Lint passed.
- Eighteen API 35 composer/panel tests passed in each of portrait 100%,
  actual landscape 100% and portrait 200% font (54 executions). Each
  configuration includes normal and 320 dp viewport geometry checks:
  shared tracks and spans, disclosure-symbol centres, full target bounds,
  no target overlap with real keys, and no terminal input from toggling.
- Screenshots reviewed for portrait, landscape, large fonts and the narrow
  viewport. Existing short/long actions, draft, IME and paste component
  checks remain green. Physical-phone and vendor-IME behavior remains
  unverified.

Environment: Pi.
Model: openai/gpt-6.1-sol.

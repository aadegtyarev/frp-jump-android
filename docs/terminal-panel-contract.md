# Compact terminal input panel

## Scope

This surface owns the terminal panel's Keys/Text controls, additional-key
layout, gestures and accessibility. It serves the owner using an SSH tab and
maintainers changing terminal UI. It inherits the shared
[UI rules](contract.md#ui-rules): keep common actions direct, copy concise,
and waiting/error feedback and accessibility intact.

The [text-input contract](terminal-text-input-contract.md) owns drafts,
composition, insertion and paste safety. The panel must preserve those rules;
it does not create a second input or session owner. SSH transport, reconnect
and tmux lifetime belong to [SSH persistence](ssh-persist-contract.md).

## Keys and Text flows

Keys is the initial surface. There is no dedicated Keys/Text selector row.

| Control or transition | Required result |
| --- | --- |
| Main row | Keep this order: ^C, Paste, left, up, down, right, Backspace, Enter, keyboard. |
| Short keyboard tap | Request one system-keyboard visibility toggle. |
| Hold keyboard | Open the selected tab's Text composer once; releasing must not also trigger the short tap. Expose a labelled accessibility long-click action. |
| Terminal ⋮ → Text input | Open or focus the same editor as an explicit alternative to the gesture. |
| Long press on Paste | Open the system photo picker, as ⋮ → Insert image, and run the same flow. Works while the tab is Open and has no image on its way (the same rule as the menu item), also with an empty clipboard; otherwise it does nothing. A tap is unchanged (it pastes the clipboard text or offers the clipboard image, and is announced as unavailable, by state description, when there is neither). TalkBack long-click label: "Insert image" / «Вставить изображение». The key carries a small image glyph in its top-end corner as a decorative hint of this long press, like the pencil on ⌨: always shown, at Material's disabled emphasis (the available colour at 38% alpha) while the long press is not available (tab not Open, or an image on its way), with no semantics or touch target of its own. The paste glyph stays the larger mark and the two never overlap, also at 320 dp, in landscape and at 200% font. The terminal panel is dark in both app themes; against the key colour the available hint has about 10.4:1 contrast and the dimmed one about 2.8:1 (computed for both theme palettes: 2.81 and 2.83), so the dimmed hint is clearly weaker yet still visible. |
| Terminal ⋮ → Insert image | Open the system photo picker. Dimmed (not hidden) unless the tab is Open, and while that tab already has an image on its way, until that job has really ended (a tap on the bar's Cancel hides the bar at once but the item stays dimmed until then). See [Insert image](contract.md#7-ssh-console). |
| Settings → Terminal | Explain the keyboard gesture and menu entry; no separate persisted panel setting is required. |
| ⋯ above the main row | Explicitly open/close additional keys without sending terminal input or stealing IME focus. Opening the system keyboard alone does not open these rows. |
| Enter Text | Collapse additional rows. Show only the editor in the input area: no main key row, additional rows, disclosure or command chips. |
| `>_` in Text | Return to Keys without inserting or discarding the draft; additional rows remain collapsed. |

Additional keys start collapsed. Keep their order: Esc, Tab, sticky Ctrl,
Alt, Shift, ^D, ^Z, ^R, then Home, End, PgUp, PgDn, `|`, `~`, `/`, `-`.
Sticky Ctrl, Alt and Shift apply to the next key, then release; they combine.
They are also released by ready combos (^C ^D ^Z ^R, unapplied), entering Text
mode, snippet send and the confirmed multi-line paste; a direct paste leaves
sticky modifiers as they are. Shift sends the arrows, Tab, Home and End with the
Shift modifier as the terminal emulator encodes it (Shift+Tab is `ESC [ Z`,
Shift+Up `ESC [ 1 ; 2 A`); Alt+Shift+Tab is also `ESC [ Z`. Esc, Enter and
Backspace bytes do not change with Shift (it is used up). PgUp and PgDn are sent
without Shift (it is used up), because Termux would turn Shift+PgUp/PgDn into a
local scroll, on the alternate screen into a screenful of arrow keys, or with
mouse tracking into a screenful of wheel events. A hardware keyboard keeps stock
Termux behavior: sticky Shift then a physical PgUp/PgDn scrolls locally. For
soft-keyboard text Termux's own Shift handling applies: the
next typed character goes through `Character.toUpperCase` (letters become
uppercase; Termux also maps a few non-letters, such as circled letters). The
`| ~ / -` keys are typed as is and release Shift. Ctrl+L is sticky Ctrl then `l`.
Keys address the terminal, not a retained draft. Paste retains the existing
clipboard availability and session-bound confirmation behavior; panel
compaction must not bypass it. With an image and no text on the clipboard Paste
stays enabled and offers to insert the image instead; it is dimmed for an
image-only clipboard when the tab is not Open or already has an image on its
way. A clipboard with text is never affected. The image progress bar
(text and Cancel) sits between the terminal and the snippets row; it wraps at
200% font and must not hide any key. Its Cancel ends left of the disclosure
target, by the same geometry the target is placed with (the target's reach from
the panel's end, which differs between portrait and wide landscape), so neither
covers the other in any orientation or font scale. In text mode there are no keys
and no disclosure: the same bar sits above the editor with Cancel at the end
(small end padding), and in the full-screen editor it is inside it.

Text retains compact/full-screen expand and collapse, Back to the compact
editor, visible ordinary IME composition, and text/selection/focus through
rotation while the owning session remains alive. Short Insert inserts
without an extra Enter. Explicit long-Insert execution and its warnings are
specified by the text-input contract, not a second panel policy.

Keyboard long-click and menu actions must follow the selected session,
including repeated tab switches and an initial selection other than the
first tab. Refresh the action binding on selection changes without removing
the stale-session guard. Switching surfaces must neither send text nor
change the persisted direct-input preference.

## Shared geometry and accessibility

- All rows use the main row's nine column tracks. Reserve the last visual
  cell above the keyboard for the independent disclosure, not another key.
- Eight extra keys use the same cell widths; on wide screens sixteen use
  half-cells in one row. Wrapped rows use whole-cell multiples, with a
  larger minimum width for large fonts. Do not stretch each row onto an
  unrelated set of columns.
- Preserve compact heights: main keys are 44 dp high, extra keys 36 dp,
  with 3 dp gaps. The extra-key minimum width is 36 dp scaled upward with
  font scale, never downward below the normal-font minimum.
- Anchor the disclosure to the main row. Centre its symbol horizontally on
  the keyboard column and vertically on the last extra row. Do not reserve
  a separate full-width header row.
- Keep its full touch target independent of the keyboard and all real
  keys, including when additional rows are open. The target ends above the
  main row and stays inside the panel viewport. Its height is 48 dp; its
  width is up to 48 dp, limited by available space on narrow viewports.
- The disclosure does not take IME focus. Give it show/hide labels matching
  its state. Provide Russian and English labels for the keyboard's
  long-click action, the menu entry, Settings hint and editor controls.
  Decorative gesture hints are not separate touch targets.

## Session safety, failure and data ownership

Mode changes, disclosure toggles and keyboard visibility changes are local
UI operations, not remote writes. They must not submit a draft implicitly.
A pending insertion remains bound to its captured destination; changing tabs
must not redirect it. Cancellation or failed/unavailable insertion keeps the
draft and editor and follows the text-input contract's feedback and revision
guards. Successful local insertion is not proof of remote delivery.

Drafts and editor state belong to each SSH session in process memory, not
the panel, clipboard, settings, logs or saved-state bundles. Closing a session
releases its draft; a replacement session does not inherit it. Configuration
changes retain drafts while that owner lives; process death need not.
See the text-input contract for the complete retention and paste constraints.

## Checks

Changes to this surface must preserve these observable conditions:

- Default Keys contains the main row, not additional rows or a selector.
  Toggling disclosure sends no input, requests no keyboard toggle and keeps
  the keyboard button anchored.
- Short/long keyboard actions remain independent, with a labelled
  accessibility long-click. Test both the component and real TerminalScreen
  selection across three synthetic sessions and already-open editors.
- Check shared tracks, cell spans, symbol centres and full disclosure bounds
  in screen coordinates, including the independent popup window. Check no
  overlap with real keys at normal and 320 dp viewport widths, portrait,
  landscape and 200% font.
- Text has no terminal rows, disclosure or command chips. Returning to Keys
  preserves the draft and leaves additional rows collapsed. Existing saved
  commands and their manager remain available in Keys/the menu; an empty
  command list adds no row.
- Preserve composer checks for per-session drafts, native IME composition,
  full-screen/Back, selection and focus through rotation, safe insertion
  and stale callback/revision handling.

## Validation

Existing regression coverage is in `TerminalTextComposerTest`,
`MultiSessionTextModeTest` and `TerminalDraftsTest`. Read coverage separately
from executed evidence: a source assertion or this reference is not a passing
runtime check. For panel implementation changes, run the focused JVM and
API 35 component tests, debug/AndroidTest builds, Lint, documentation and
whitespace checks. Include portrait, actual landscape and 200% font for the
UI checks, and verify the requested configuration is actually applied.

Component tests do not establish physical-phone, keyboard-vendor,
TalkBack-by-ear or end-to-end SSH performance/delivery guarantees. Report
those limits rather than treating layout checks as device validation.

## Non-goals and related surfaces

No automatic remote-program detection, replacement direct-input preference,
persistent drafts or new transport/paste path is introduced by this panel.

- [App and shared UI rules](contract.md#ui-rules)
- [Terminal text input](terminal-text-input-contract.md)
- [SSH persistence](ssh-persist-contract.md)
- [Terminal history and diagnostics](terminal-diagnostics-contract.md)

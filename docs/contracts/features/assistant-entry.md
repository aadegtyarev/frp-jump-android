# Feature: reach the assistant from the navigation and the terminal

Status: Approved by the operator via the peer session (Р26, 2026-11-13). Scope follows the connections plan §6.7 and questions В7а/В7в.
Its own release.

## Goal and user-visible behavior

1. **A fifth bottom-navigation item, "Assistant" / «Помощник».**
   - It is shown only while the assistant is on (Settings › LLM assistant). It appears and disappears with the setting.
   - It opens the same full-screen chat screen over the current section. Back returns to where the owner came from.
   - Labels of all five items fit in RU and EN at 200% font and in landscape, without being cut.
   - The icon is neutral, for example a chat bubble. No brand mark.
2. **Terminal › ⋮ › "Ask the assistant" / «Спросить помощника».**
   - With text selected in the shown tab, that text goes into the chat draft as a quoted code block, and the cursor is placed after it.
     Without a selection, the chat simply opens.
   - The quote is limited to a reasonable length, at most 4,000 characters, without splitting a surrogate pair.
   - A cut quote keeps the END of the selection (the latest lines); its first line is an honest localized note, "[start truncated]" / «[начало обрезано]»,
     visible in the draft before sending. The cut is on a line boundary (a single longer line: by characters). The owner sees the draft and can edit it
     before sending.
   - If the draft already has text, it is kept and the quote is appended after it.
   - The item exists only while the assistant is on.
3. **The Settings entry to the chat stays.**
4. **Fix:** the provider screen route (`PROVIDER`) looks up `getBackStackEntry(Routes.SETTINGS)` (`AppNav.kt`, near line 380). It crashes
   when the chat was opened from anywhere other than Settings. The provider screen must work from every entry.

## Unchanged

- Confirmations: "For session" still lasts until the chat screen is left.
- The app lock, Stop on leaving the chat, a disabled assistant, and an unconfigured provider all behave as today, whichever entry
  opened the chat.
- Model-facing texts: none change. The quote is the owner's draft text, not app instructions.
- The chat's saved history and draft behave as today.

## Non-goals (later)

- A tab reference, the "Tab: …" label, or a long-press on a tab: Э4 of the connections plan.
- A launcher shortcut (В7г) and the Termux selection-menu item (В7д).
- No reading of tabs by the assistant.

## Affected surfaces

- [`docs/contract.md`](../../contract.md): the navigation, the terminal menu, and the "Optional LLM assistant" entry points.
- [`docs/agent-contract.md`](../../agent-contract.md): the Settings flow and Boundary, for how the chat is reached. Wording only; promises
  are not weakened.
- [`docs/terminal-panel-contract.md`](../../terminal-panel-contract.md), if it lists the ⋮ menu items.
- [`docs/mqtt-contract.md`](../../mqtt-contract.md): its bottom-bar item count (four, plus the assistant item while it is on).

## Interfaces and constraints

- Use the existing chat route and screen; no second chat instance.
- Navigation: a fifth `NavigationBarItem`. Selecting it opens the chat route on top of the current back stack, so Back returns.
  Selecting it again while the chat is open does nothing harmful.
- The quote reads the shown tab's selection from the Termux view at the moment ⋮ is used. Find the API, for example the selected text of
  the `TerminalView` or the emulator's selection. It is never read in the background or without the owner's action.
- Respect the lock gates from 0.3.71–0.3.75: no window outside the `ui/LockedWindows.kt` wrappers (the source-scan test enforces this).

## Acceptance criteria

1. UI test: the nav item is hidden while the assistant is off and shown when it is on, in both directions without restarting the app.
2. UI test: opening the chat from the nav item and pressing Back returns to the previous section.
3. UI test: opening the provider screen from a chat that was opened from the nav item does not crash. This is a regression test for the
   `getBackStackEntry` crash.
4. Unit and UI tests for the quote:
   - selection to code block;
   - the limit and the truncation note;
   - no surrogate split;
   - an existing draft is kept and the quote appended;
   - the cursor ends after the quote;
   - no selection just opens the chat.
5. Five nav labels uncut in RU and EN × portrait/landscape × 1.0/2.0.
6. The contracts are updated.
7. One chat entry only: chat, then a notification's device card, then the terminal, then Ask the assistant ends with exactly one chat entry
   (the one that was open) and the quote in its draft. Opening the chat twice in a row opens one.
8. A waiting quote is taken by the chat at once and dropped with it (Back before the load ends leaves nothing for a later chat); memory only.
9. The quote keeps the end of a long selection, with the "start truncated" note first; tests at the threshold and one over it.
10. Five labels whole also in 320 and 360 dp windows, RU, 200% font. The assistant item is a button, not an unselected tab.

## Validation

- `./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`
- `git diff --check`
- The repo-docs checker.
- Emulator:
  - the new UI tests in 8 configurations;
  - `AssistantChatScreenTest` (chat runner) in EN and RU;
  - the lock tests once (`LockedWindowsTest`, `LockedImeTest`).

# Terminal text composer

Status: operator-approved interaction model; implementation not started.

## Goal

Let the owner prepare ordinary text with the keyboard's composition,
suggestions, correction and voice input without hiding uncommitted text
inside the terminal input connection. Keep immediate terminal keys as the
default. Do not guess whether the remote application is a shell, vi or Pi.

This supplements the terminal behavior in [contract.md](contract.md),
scenario 7. It does not replace the existing paste safety rules.

## Agreed interaction

- The terminal starts in Keys mode. Direct input remains on by default;
  this feature does not change an explicitly stored input preference.
- An explicit Keys/Text control switches the input surface. Switching
  never sends text, presses Enter or discards a draft.
- Text replaces the additional keyboard rows above the Android keyboard;
  it is not another panel stacked above those rows. The terminal remains
  visible above the compact editor. Keep the existing common terminal-key
  row available rather than stacking both sets of additional rows.
- The compact editor is a visible multiline text field with ordinary IME
  composition. Provide actions to expand, insert and return to Keys.
  Suggestions and voice input depend on the installed keyboard.
- Expand opens the same draft in a full-screen editor. Collapse and Back
  return to the compact editor without inserting or discarding anything.
- Insert targets the exact current SSH tab. It inserts text, never an
  extra Enter. Terminal Enter remains a separate explicit action.
- Drafts are independent per SSH session. Switching tabs or returning to
  Keys does not lose them. A closed tab releases its draft; a different
  session, including one to the same device, does not inherit it.
- Drafts live only in process memory: no settings file, saved-state bundle,
  log or clipboard persistence. Configuration changes should retain drafts
  while the owning session model is alive; process death need not.
- Available terminal Ctrl/Alt and service-key buttons address the terminal,
  not the draft. A terminal action must not implicitly insert the draft or
  apply its modifier to buffered editor text.

## Paste safety and session binding

Use the existing `planPaste` / `PastePlan` decision path and
`SshSession.paste`, not a new raw session-write path:

- Drop trailing line breaks and blank lines as existing paste does.
- Do not insert whitespace-only content.
- Preserve the existing multiline confirmation when bracketed paste is
  unavailable, including the conservative tmux handling.
- Confirmation retains the exact destination session; switching tabs must
  not redirect a pending insertion. Revalidate the destination before send.
- Canceling the confirmation leaves the draft intact and sends nothing.
- A closed, disconnected or otherwise non-writable destination must not
  cause text to be sent to a replacement tab. Keep the draft and explain
  that insertion was not performed.
- Do not claim that multiline insertion can never execute commands. The
  existing confirmation explicitly permits that risk when the owner
  chooses to paste without bracketed paste.

A successful insertion clears the submitted draft. Cancellation and failed
insertion always retain it. If the draft was edited while confirmation was
open, sending the captured text must not clear the newer draft. Success here
means the local paste call completed, not proof that a remote application
received or processed the text.

## Non-goals

- Automatic mode detection from screen contents, alternate-screen state,
  process names or escape sequences.
- A new local shell, rich-text editor, persistent notes or draft sync.
- Automatic prompt submission or a combined Insert-and-Enter action.
- Shipping the paused standalone Ctrl/Alt prototype as an unrelated fix.
- Replacing the existing paste warnings with an assumption that TUI input
  is always safe.

## Implementation boundaries

Existing integration points, verified in source:

- `ui/ssh/ExtraKeys.kt`: common key row and additional rows.
- `ui/ssh/TerminalScreen.kt`: selected session, terminal focus and paste
  confirmation UI.
- `ui/ssh/TerminalInput.kt`: direct terminal input; editor focus must not
  accidentally send composition through this connection.
- `ssh/PastePlan.kt`: trailing-break handling, multiline checks and joining.
- `ssh/SshSession.kt`: `paste` marks owner input and uses terminal paste.

Keep draft ownership separate from the terminal input connection and bind
it to the session ID. Provide Russian and English strings and accessible
labels for mode, expand/collapse and insertion controls.

## Acceptance and checks

1. Default Keys mode still sends ordinary terminal input immediately when
   the persisted direct-input preference is on.
2. In Text mode, a composing word is visible locally before commit; nothing
   reaches SSH until Insert or an explicit terminal-key action.
3. Switching Keys/Text, expanding/collapsing, keyboard dismissal and tab
   switching do not insert text or lose a draft.
4. Full-screen and compact editors share the same text and selection.
5. Two tabs keep different drafts; closing one does not affect the other.
6. Single-line insertion adds no Enter. Multiline insertion follows the
   existing bracketed-paste and confirmation tests, including tmux.
7. Canceling a paste, losing a connection or closing the destination never
   redirects the text or silently discards the draft.
8. Terminal Ctrl/Alt and service-key actions work independently of the draft
   and never submit it implicitly.
9. Review compact/full-screen focus, Russian and English IME composition,
   keyboard suggestions, landscape, large font and accessibility labels.
   Clearly report any real-device checks that could not be performed.
10. Run unit tests, `assembleDebug`, `lintDebug`, documentation checks and
    `git diff --check`. Update durable terminal documentation after verified
    implementation. Follow the repository's version and release procedure
    when the feature is ready to ship.

Environment for contract preparation: Pi.
Model: openai/gpt-6.1-sol.

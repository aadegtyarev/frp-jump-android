# Terminal text composer

Status: implemented; local validation completed for PR review.

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
- Hold ⌨ or choose terminal ⋮ → Text input to enter Text; >_ returns to
  Keys. No separate Keys/Text selector row is shown. Switching never sends
  text, presses Enter or discards a draft. The compact-panel follow-up is
  specified in [terminal-panel-contract.md](terminal-panel-contract.md).
- Text replaces all terminal key and command-chip rows, not another panel
  stacked above them. The terminal remains visible above the compact editor.
  Additional key rows in Keys mode start collapsed and open only via ⋯.
- Operator-approved layout follow-up: an empty saved-command list must not
  show the «+ Добавить команду» row. Existing command chips remain; the
  terminal's ⋮ → Commands opens the manager even when the list is empty.
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
- Return to Keys for terminal Ctrl/Alt and service-key actions. Those
  actions address the terminal, not the retained draft. Returning must not
  implicitly insert the draft or apply a modifier to buffered editor text.

## Paste safety and session binding

Use the existing `planPaste` / `PastePlan` decision path and
`SshSession.paste`, not a new raw session-write path:

- Drop trailing line breaks and blank lines as existing paste does.
- Do not insert whitespace-only content.
- Preserve the existing multiline confirmation when bracketed paste is
  unavailable, including the conservative tmux handling.
- Confirmation retains the exact destination session; switching tabs must
  not redirect a pending insertion. Revalidate the destination before send.
- An outbound chunk belongs to the output/generation captured when it leaves
  the terminal queue, not whichever output is current after a blocking tmux
  guard. Connection handover discards old queued input. A paste cannot be
  split by handover while it is waiting for queue space; keep the writer
  free to drain it. Idle writers wait on the queue, without polling.
- Canceling the confirmation leaves the draft intact and sends nothing.
- A closed, disconnected or otherwise non-writable destination must not
  cause text to be sent to a replacement tab. Keep the draft and explain
  that insertion was not performed.
- Do not claim that multiline insertion can never execute commands. The
  existing confirmation explicitly permits that risk when the owner
  chooses to paste without bracketed paste.

A successful insertion clears the submitted draft and returns that tab to
Keys: close compact/fullscreen editors, restore the main key row and leave
additional rows collapsed. Return focus only if that tab is still selected;
do not implicitly open a hidden IME or append Enter. Cancellation and failed
insertion retain the draft and editor. If the draft was edited while
confirmation was open, sending the captured text must neither clear the newer
draft nor close its editor. Duplicate completion callbacks must not close an
editor reopened after insertion. Success here means the local paste call
completed, not proof that a remote application received or processed the text.

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

## Recorded validation (original editor, 0.3.29)

The compact-panel follow-up and its gesture/layout checks are recorded in
[terminal-panel-contract.md](terminal-panel-contract.md).

- 1,146 JVM tests passed with no failures, errors or skips, including draft
  lifecycle/revision guards, paste dispatch results and existing paste plans.
- `assembleDebug`, `assembleDebugAndroidTest` and `lintDebug` passed.
- Nine component/IME tests passed on a clean API 35 x86_64 emulator in each
  configuration: portrait at 100%, portrait at 200% font, and landscape at
  100%. Orientation and font scale are asserted inside the test activity.
  The suite also rotates an active compact editor and checks text and
  selection in both orientations.
- The suite covers local typing/newlines, native InputConnection composition,
  expand/collapse and Back, per-tab drafts, the common Ctrl+C button, and
  removal of the empty command row while preserving existing commands.
- Screenshots were inspected for compact/full-screen and large-font layouts.
  Component tests do not establish end-to-end remote SSH delivery.
- Physical phones, Gboard/Yandex-specific behavior, voice recognition and
  TalkBack by ear have not been checked. No controller/broker writes were
  made to validate this feature.

To reproduce the component tests, use a clean emulator with no enrolled
profiles or stored credentials. Build and run with the normal Android
instrumentation runner:

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
./gradlew connectedDebugAndroidTest
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.composerOrientation=landscape
```

For the 200% run, set that emulator's system font scale to `2.0` first and
pass `-Pandroid.testInstrumentationRunnerArguments.composerFontScale=2.0`.
Restore the emulator font scale to `1.0` afterwards. Tests use only the
component host, but the real app Application starts; do not target a phone
containing production profiles for these checks.

Environment: Pi.
Model: openai/gpt-6.1-sol.

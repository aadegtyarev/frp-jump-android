# Terminal text composer

## Boundary and ownership

The composer lets the owner prepare ordinary text locally with IME
composition, suggestions, correction and voice input, then insert it into an
exact SSH session. Keys remains the default surface for immediate terminal
input. The app does not guess whether the remote application is a shell, vi
or Pi.

This surface inherits the [shared UI rules](contract.md#ui-rules) and
supplements the [SSH console](contract.md#7-ssh-console). The
[terminal panel](terminal-panel-contract.md) owns key-row geometry and
keyboard gestures; [SSH persistence](ssh-persist-contract.md) owns connection
lifecycle and tmux behavior. This contract owns local drafts, insertion
intent, destination binding and the paste-safety path shared with clipboard
input. It does not replace the existing paste warnings.

Drafts belong to SSH sessions, not to a screen or terminal input connection.
The terminal input connection must not receive the editor's composing text.

## Agreed interaction

- A new session starts in Keys. Direct input is on by default; an explicitly
  stored input preference is preserved. Entering Text never changes it.
- Short ⌨ shows or hides the system keyboard. Hold ⌨ or choose terminal
  ⋮ → Text input to open or focus Text; `>_` returns to Keys. There is no
  separate Keys/Text selector row. Switching modes never inserts the draft,
  presses Enter or discards text or selection.
- Text replaces all terminal-key and command-chip rows, rather than stacking
  above them. The terminal remains visible above the compact multiline
  editor. Returning to Keys restores the main row with additional rows
  collapsed; only ⋯ opens additional rows.
- The compact editor offers expand, Insert and return to Keys. Typing,
  composition and editor Enter stay local. Suggestions and voice input
  depend on the installed keyboard.
- Expand opens the same draft and selection full screen. Collapse and Back
  return to compact Text without insertion or loss. Keyboard dismissal and
  tab switching also retain the draft.
- Existing command chips remain available in Keys. An empty saved-command
  list shows no «+ Добавить команду» row; ⋮ → Commands still opens the
  manager when the list is empty.
- Return to Keys for terminal Ctrl/Alt and service-key actions. Those
  actions address the terminal, not the retained draft; they never implicitly
  submit it or apply a modifier to buffered editor text.
- Provide Russian and English labels for mode, expand/collapse and insertion
  controls, including explicit accessible short- and long-action labels.
  The small pencil on ⌨ and return glyph on Insert are decorative hints,
  not separate buttons or permanent instruction rows.

## Draft lifetime and privacy

Each session has independent text, selection and editor state. Switching tabs
or returning to Keys retains them. A closed or replaced session releases its
draft; another session, even to the same device, does not inherit it. Late
callbacks from an outgoing editor or previously selected tab must not change
the live draft or recreate a closed one.

Drafts live only in process memory, not settings, saved-state bundles, logs
or clipboard. Screen/configuration changes retain them while the owning
session model lives; process death need not.

## Paste safety and session binding

Short Insert captures the current session and draft revision. It uses the
existing `planPaste` / `PastePlan` decision path and `SshSession.paste`, not a
new raw-write path, and never appends Enter:

1. Drop trailing line breaks and blank lines. Keep spaces at the end of the
   last real line; whitespace-only content sends nothing.
2. With remote bracketed paste enabled outside tmux, insert through terminal
   paste framing. Without it, several lines require confirmation. Treat
   CRLF, LF and CR as line breaks. If the bracketed flag cannot be read,
   conservatively treat it as off.
3. In an app-managed tmux session, do not trust the emulator's bracketed flag:
   tmux can keep it enabled toward the app even when its pane did not ask.
   Several lines therefore always require confirmation there. A multiplexer
   started manually inside a plain-shell tab cannot be detected.
4. The confirmation states that lines may run without bracketed paste. Offer
   Paste as-is, Remove line breaks, or cancel. Joining trims each line,
   removes blank lines and inserts one space between remaining lines; it
   does not parse shell syntax or remove a trailing `\` continuation. The
   sheet's ✕ or dismissal cancels without sending.

Capture the exact SSH client, terminal instance and output generation before
confirmation. Revalidate the session's identity, open/writable state,
pending-close status and captured connection at dispatch. A normal pending
insertion remains bound to its original destination when tabs change; it
must never redirect to the newly selected or replacement session. A stale
connection is rejected even if it reuses an output object. Do not retry or
fall back to another tab.

An outbound chunk belongs to the output/generation captured when it leaves
the terminal queue, not whichever output is current after a blocking tmux
guard. Connection handover discards old queued input. Handover cannot split
a paste while it waits for queue space; the writer must remain free to drain
it. Idle writers wait on the queue without polling. These are queue-ownership
guarantees, not guarantees that every queued byte will reach the remote side.

**Multiline insertion can execute commands.** The owner may explicitly accept
that risk in the confirmation; do not replace the warning with an assumption
that TUI input is safe.

## Explicit long-press execution

Holding Insert requests the same paste followed by exactly one CR (Enter),
outside bracketed-paste framing. Releasing a long press must not also trigger
short insertion. Empty or unavailable controls dispatch neither action; an
empty or control-only payload must not become a bare Enter.

Execute intent has a stricter selection guard than normal paste: selecting
another tab invalidates it, even if the owner returns to the original tab
before confirming. Paste and its explicit CR are enqueued under the same
connection-handover lock, never by appending CR later through the currently
selected tab.

Unsafe multiline confirmation remains mandatory. In execute mode both
Paste and Remove line breaks explicitly include Enter, and the sheet warns
that joining lines does not remove that final Enter. Cancellation sends
nothing. The same draft-revision and success safeguards apply to both gestures.

## Insertion results and recovery

- Successful local insertion clears only the submitted revision and returns
  that tab to Keys: close compact/fullscreen editors, restore the main key
  row and leave additional rows collapsed. Return terminal focus only if
  that tab is still selected; do not implicitly show a hidden IME or append
  Enter (except for the explicit long action).
- If the draft changed while confirmation was open, sending the captured
  text must neither clear the newer draft nor close its editor. Duplicate
  completion callbacks must not close an editor reopened after insertion.
- Cancellation and failed insertion retain the draft and editor. A closed
  session still follows the draft-release rule above. An unavailable,
  disconnected or pending-close destination must not send to a replacement;
  explain that insertion was not performed. A local paste failure reports
  that the draft was kept.
- Success means the local paste call completed, not proof that the remote
  application received, processed or executed the text.

## Clipboard compatibility

Clipboard Paste uses the same planning and multiline safety rules. The Paste
key is disabled when the clipboard has neither text nor an image; an image and
no text offers the image flow ([Insert image](contract.md#7-ssh-console)) from
the key and from the selection menu, and an image clip's content URI is never
pasted as text. A clip with any text pastes the text as before. The inserted
image path is one line through the same accepted-paste path, destination and
output-generation binding as any single-line paste, with no Enter; local
acceptance is not remote receipt. Availability checks inspect
the clipboard description rather than repeatedly reading its contents.
Whitespace-only clipboard text is rejected on use with feedback. If a
multiline paste needs confirmation but no terminal screen can show it, send
nothing.

Automatic OSC 52 clipboard writes are accepted only from the selected SSH
tab. Tab selection immediately revokes the old tab's authority. Removed or
replaced terminals cannot copy through late callbacks. Leaving the terminal
screen or backgrounding the app does not revoke the last selected tab;
there is no new prompt, opt-in or foreground gate.

Explicit selection Copy is unchanged, including copying from an ended
terminal. Both accepted OSC copies and manual Copy use the existing writer:
cap text at 64 × 1024 UTF-16 code units and show the copied-text notice.
Successive copy notices replace one another. The notice preview strips
control and bidi-control characters; this does not sanitize the copied
payload itself. An Android clipboard write failure must not show success.

## Non-goals and validation boundaries

- No automatic mode detection from screen contents, alternate-screen state,
  process names or escape sequences.
- No new local shell, rich-text editor, persistent notes or draft sync.
- No automatic submission on short Insert or any other implicit Enter.
- No unrelated standalone Ctrl/Alt prototype or replacement of paste warnings.
- No promise of remote delivery from local acceptance, universal keyboard
  suggestions/voice support, or device/vendor-IME conformance from component
  tests alone.

## Implementation boundaries and checks

Maintainer entry points are `TerminalDrafts` for per-session memory/revision
ownership, `TerminalScreen` for selection/focus and pending insertion,
`TerminalTextComposer` for local IME editing, `TerminalInput` for immediate
terminal input, `PastePlan` / `PasteSheet` for safety decisions, and
`SshSession` / `RemoteTerminal` for captured destination and queue ownership.
`SshSessions` owns OSC selection authority and the shared clipboard writer.

Existing regression checks include `TerminalDraftsTest`, `PastePlanTest`,
`PasteAttemptTest`, `BracketedPasteTest`, `TerminalTextComposerTest`,
`TerminalMetricsIoTest`, `SshSessionLifecycleTest`,
`RemoteClipboardRoutingTest` and `SshClipboardAuthorityTest`. Check per-tab
routing, revision/stale-callback rejection, short/long gestures, local IME
composition, compact/fullscreen selection and Back, queue handover and
selected-tab versus manual Copy separately.

UI verification should cover Russian and English composition, suggestions,
compact/fullscreen focus, landscape, large fonts and accessible labels.
Report physical-device, vendor-keyboard, voice and TalkBack checks separately
from synthetic component evidence. Use only synthetic sessions/credentials;
Android component tests start the real Application, so do not target a device
with production profiles. Follow repository checks for an approved behavior
change; this reference is not a validation diary or a release claim.

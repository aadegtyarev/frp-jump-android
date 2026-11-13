# Feature: an assistant attempt outlives the chat screen (Р32)

Status: approved by the operator (the expectation) and the coordinator (the details, Р14, including the off-screen indicator,
the ongoing notification with Stop and the real-time limit), 2026-11-13. The coordinator sees the
`docs/agent-contract.md` wording before merge. Released together with the assistant entry points as 0.3.80.

Affected surface contracts: `docs/agent-contract.md` (Entry points, Send lifetime, Saved history, Tools, confirmations, SSH,
tests), `docs/contract.md` (Optional LLM assistant, notifications).

## Problem

Leaving the chat stops a running Send. Three mechanisms do it:

- Back and the arrow call `AssistantConversation.close()`;
- `AssistantChatViewModel.onCleared()` calls it too;
- the Send job is a child of `viewModelScope`.

The owner who sends a question and goes back finds "Previous attempt did not finish" twice (grey under the turn, red above the
field) and the question back in the draft. With the bottom-navigation entry, leaving the chat is common.

## Required behavior

1. **Lifetime.**
   - The app keeps one chat conversation while its screen is open (anywhere on the back stack, including under the Tools
     sub-screen or screens opened over it) or while an attempt runs (a Send, including a confirmation wait).
   - When both have ended, the conversation closes as today: the draft is saved, the "For session" grants are cleared, memory is
     released. The next opening restores the saved history.
   - Leaving the chat (Back, the arrow, opening another section, another app, the lock, activity recreation, the activity
     finishing) does not stop an attempt. Its reply and results are saved to the history and are there when the chat is opened again.
   - Reopening the chat while an attempt runs shows that same attempt, with its Stop. There is no second conversation.
   - An attempt is stopped only by:
     - Stop;
     - turning the assistant off (also off/on);
     - a provider change that revokes it (as today);
     - its own limits (as today);
     - the server removing this phone's profile (the REMOVED screen; today this stops it by popping the chat, and the guarantee is kept);
     - process death, which leaves the existing unknown-outcome note.
   - Clearing the history is not a stop path: Clear stays unavailable for the whole attempt, a card wait included, as today
     (the owner presses Stop first). The defensive stop in `deleteHistory` may stay in code, but the contracts do not list it.
   - The reactions that today live in the ViewModel move with the conversation and work while no chat screen exists:
     - disable;
     - provider revision;
     - the busy slot;
     - a tool switched off clears that tool's grants.
2. **Confirmations after leaving.**
   - A card raised while the chat is not shown waits; it is shown in the chat when the owner returns, never over other screens.
   - The five-minute limit runs meanwhile, measured in real time with device sleep included. A card older than five minutes is
     never answered: it ends as "No answer, declined" and nothing runs. If the limit ran out while the chat was closed, the owner
     returns to "No answer, declined"; no press runs the call.
   - The lock behaves the same: the card waits behind it and the limit runs.
3. **For session.**
   - A grant lasts while the conversation lasts, as defined in item 1: until the chat screen is left AND no attempt runs. It also
     ends on any of today's conditions: disable, tool off, history clear, target edited or removed.
   - It gives no new authority; a session no longer ends in the middle of an attempt.
   - The hint under the answer says so: EN "…until you leave Chat and the assistant finishes."; RU «…пока вы не выйдете из чата
     и помощник не закончит.»
4. **Visible while it works off screen** (Р32, stricter by Р14: an assistant that runs commands while the owner does not see it
   must be noticeable and stoppable without opening the chat).
   - **Indicator, required in this release.** While an attempt runs (a card wait included), the bottom navigation's "Assistant"
     item shows a small dot. TalkBack state: "Working" / «Работает», or "Waiting for your answer" / «Ждёт ответа» while a card
     waits. The chat itself has no bottom bar, so the dot is seen exactly when the chat is not on screen. This works without the
     notification permission.
   - **One notification per attempt**, on its own channel "Assistant" / «Помощник», silent (low importance), which the owner can
     turn off in the system:
     - While an attempt runs and the chat is not on screen, it is ongoing: "The assistant is working" / «Помощник работает», with
       the action Stop / «Стоп».
     - The same notification then changes to:
       - "The assistant answered" / «Помощник ответил» for a completed reply;
       - "The assistant could not answer" / «Помощник не смог ответить» for a failed or timed-out attempt;
       - "The assistant is waiting for your answer" / «Помощник ждёт ответа» while a card waits (still with Stop).
     - It never carries reply text, tool names or targets.
     - "Not on screen" means: the app is not visible, another screen is over the chat, or the app is locked.
     - It is removed when the chat is shown, and after a Stop or disable by the owner. No final notification is posted for those.
     - A tap opens the app (behind the lock when it is set) at the chat.
     - Stop from the notification is the chat's Stop, also during a card wait: nothing runs. It works from the lock screen; stopping
       never widens anything.
     - No new permission prompt: without the notification permission nothing is posted, and the dot remains.
   - The notification may ship as a small PR right after this one if it makes this one large. The dot is required here.
5. **One interrupted note.** An interrupted last attempt is shown once: the grey line under its turn. The red notice above the
   field does not repeat it.

## Unchanged

- Model-facing texts and tools. No new tool, permission or authority.
- The app-lived admission slot, Stop semantics, the slot held until exact cleanup, no queued replacement.
- History encryption, coalesced saves, Undo clear, read and write failure handling.
- The kernel limits: 30 s tool, 60 s model, 120 s active Send time (the confirmation wait is not counted).
- Opening the chat restores history off Main, lazily (not at app start).
- Process death interrupts an attempt; there is no foreground service and no journal.

## Acceptance criteria

1. Unit and/or instrumented tests:
   - leaving the chat during a Send and returning: the reply is in the history and on screen;
   - Back, the arrow and opening a bottom-navigation section from a screen over the chat;
   - a card raised after leaving waits, is shown on return and answerable; after five minutes it is "No answer" and nothing ran;
     a stale answer after the limit is ignored;
   - "For session" is cleared once the attempt ends with the chat closed, and kept while the chat stays open;
   - Stop after returning stops the same attempt;
   - activity recreation keeps the attempt;
   - disable, provider change and tool off still revoke while no chat screen exists;
   - REMOVED stops it;
   - the interrupted note is shown once;
   - the dot and its TalkBack state while an attempt runs and while a card waits, gone when it ends;
   - a card that expired while the chat was closed is "No answer, declined" on return, and no answer press runs it;
   - the notification (if in this PR): ongoing "working" with Stop when off screen; none while on screen; it changes to the end
     or waiting text; Stop from it stops the attempt and a waiting card runs nothing; removed on showing the chat; no content;
     a tap opens the chat.
2. Existing tests that pinned "pop stops the Send" are changed to the new behavior, each listed in the PR, with the reason.
   Tests about Stop, disable, revision, slot and exact cleanup stay.
3. Mutations killed:
   - the attempt stopped on detach;
   - grants cleared on detach while the attempt runs;
   - grants not cleared after both have ended;
   - a collector left in the ViewModel;
   - the real-time limit removed.
4. The contracts are updated; the old promises are accounted for (retained, moved or changed by Р32).
5. The full gate, `git diff --check`, the docs checker. Emulator: the chat, entry and confirmation androidTests in the 8
   configurations where UI changed, and the lock tests.

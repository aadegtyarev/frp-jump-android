# Fix: no "Direct didn't work" notifications

Status: approved by the operator via the coordinator, 2026-11-13, decision Р34. A small PR; it does not hold up 0.3.80.
Scope change, same decision: the first draft also added an in-app message on the owner's connect. It is dropped: the card already
tells (state "Through the server" in the warning tone; a tap opens the choice).

The operator: a pile of notifications, one per tunnel that could not connect directly and went through the server, is useless.
Notifications outside the app must be useful and compact.

Affected surface contract: `docs/contract.md` §6 "Direct or through the server" and the checks list.

## Required behavior

1. **No notifications.**
   - The app posts no notification on the "direct" channel: not on connect, and not when a direct tunnel drops mid-session.
   - The channel is deleted at every start (`NotificationManager.deleteNotificationChannel`), so it no longer shows in the system
     settings, and notifications already shown are removed on the first start after the update.
   - The notification action receiver, its manifest entry and the offer storage (`direct_offered.json`) are removed.
2. **Unchanged.**
   - The card state "Through the server" and its tap, which offers "Retry direct" / "Keep through the server".
   - "Keep through the server" stops attempts for this device on the current network until the owner retries from the card
     (`DirectChoices`, `DirectKeptStore`).
   - The traffic path and the direct attempt (up to 10 s, then the server), the "Direct connection (P2P)" setting, the card
     states, keeping the direct tunnel open while up. No security change: only the announcement goes.

## Contract changes

- §6: the state on the card and the choice on tap, no notifications. Removed promises (operator decision Р34): "a notification
  'Direct didn't work: <name>' offers Retry direct / Keep through the server", and "with the same notification" for a mid-session
  drop. "Off: ... no notifications" stays true.
- Checks list: keep "falls back to relay with Retry direct / Keep through the server" (now on the card).

## Acceptance criteria

1. Tests: the channel is deleted on start (clears shown notifications); nothing creates or posts to the channel and the receiver is
   gone; the keep/retry storage stays covered (`DirectKeptStoreTest`).
2. Removed tests, with reasons: `DirectOffersTest`, `DirectOfferedStoreTest` (the offer logic and its storage are gone).
3. The full gate, `git diff --check`, the docs checker. Emulator: the connection androidTests, portrait 1.0 in EN and RU.

## Notes

- `refactor/ops-tunnels` also touches `direct/DirectChoices.kt` and `ui/connections/ConnectionActions.kt`. This fix changes only
  `DirectChoices.kt` (drops the `retried` callback to the notifier) and leaves `ConnectionActions.kt` untouched.

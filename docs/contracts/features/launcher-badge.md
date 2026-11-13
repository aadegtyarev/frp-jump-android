# Feature: launcher badge with the number of open tunnels

Status: Approved by the operator (deferred decision of 2026-11-12, released into the overnight queue on 2026-11-13). Its own release.

## Goal and user-visible behavior

The launcher shows how many tunnels are open:

- launchers that show numbers (Samsung, Xiaomi) show the number on the app icon;
- stock and Pixel launchers show a dot, with the number in the long-press menu.

The app icon itself is never swapped. No activity-alias and no icon variants: the operator accepts only the dot where a launcher shows
no number.

## Scenarios

- The tunnel service's notification on `TunnelService.CHANNEL` carries `NotificationCompat.Builder.setNumber(n)`. `n` is the number of
  open tunnels across all profiles.
- `n` is updated on every change.
- When nothing is open the service stops as today, and the badge goes with its notification.
- "Open" uses the same definition the notification text already uses for its count, if it shows one. The badge and the text must never
  disagree.
- Decide from the code and `docs/contract.md` whether to count tunnels or connections. Several connections on one device port share one
  tunnel. Recommendation: count tunnels, because that is what runs and what the service keeps alive. Record the choice in the contract
  and pin it with a test.
- The channel must allow badges (`setShowBadge(true)`). This is the default for app channels; check it, and keep any existing channel
  settings stable.

## Non-goals

- No icon swap.
- No badge for other things (errors, unread chat).
- No new setting.
- No change to the notification's text, actions or importance.

## Affected surfaces

- [`docs/contract.md`](../../contract.md): the tunnel notification/service section. Add the badge and what it counts.

## Acceptance criteria

1. A unit test of the count:
   - several profiles;
   - several connections on one tunnel count as one, if tunnels are chosen;
   - pending and failed states, following the chosen definition;
   - zero.
2. A test that the built notification carries `number == count`. Use a JVM test with the builder seam, or an instrumented test that reads
   the posted notification's `number`.
3. The channel shows badges. This is checked on API 35 with `NotificationManager.getNotificationChannel(...).canShowBadge()`.
4. The contract is updated, including the tunnels-vs-connections choice.

## Validation

- The full gate.
- `git diff --check`.
- The repo-docs checker.
- An emulator check that the posted notification's `number` matches, if an instrumented test is used. The visual badge depends on the
  launcher and is not checked on the emulator; say so.

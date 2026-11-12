# Feature: app lock interval and abandoned prompts

Status: Approved by the operator via the peer session, 2026-11-13. Release 0.3.71, before F10.

## Goal and user-visible behavior

The owner asked why the fingerprint lock did not ask on return. It works as designed: the lock asks on a cold start of the process and
after more than 30 s out of sight, and the tunnel service keeps the process alive. This feature:

1. Lets the owner choose the interval: Settings › Security › "Lock after" / «Запирать через», next to "Unlock with fingerprint",
   shown only while the lock is on. The choices are Immediately, 30 s (the default, as today), 1 min, 5 min and 15 min. There is no
   "never" or "only on start".
2. Fixes an abandoned prompt. When the activity that showed the prompt is destroyed before the answer, androidx.biometric 1.1.0 drops
   the answer (its `ResetCallbackObserver` clears the client callback on destroy). The lock then stays "authenticating" for good: the
   lock screen's Unlock button spins forever until the process dies. The same happens when `authenticate` is called after the state was
   saved: androidx then shows nothing and never answers.
3. Describes the lock honestly in `docs/contract.md` and in the row's hint.

## Scenarios

- Default (30 s), and an upgrade from 0.3.70 or earlier: unchanged behavior.
- A missing, invalid or unknown stored value means 30 s. It never resets other settings.
- Immediately: any return after the screen went out of sight asks. Two exceptions:
  - the prompt's own stops and starts, as today;
  - a return from a window the app opened itself (photo or file picker, Share, installer, system settings, an external browser or SSH
    client opened from a connection). This counts as an absence only after 30 s.
- Window the app opened itself: the app goes out of sight within a few seconds of launching it. If the window never opened, the mark
  expires and the next absence counts normally.
- 30 s and longer: an app-opened window changes nothing (the selected interval is at least 30 s).
- An abandoned prompt (its activity was destroyed first) leaves the lock as it was:
  - locked stays locked, and the new activity asks again by itself;
  - a prompt to turn the setting on does not turn it on.
  - A late answer to an abandoned prompt is ignored.
- `authenticate` after the state was saved returns Cancelled at once (lock screen: "Not unlocked. Tap Unlock to try again."; turning on:
  "Not turned on: not confirmed."), never an endless spinner.
- Lock off: the interval row is hidden. Its value is kept.

## Non-goals

- No stricter modes: always on swipe from Recents, or always on screen off. The operator declined them.
- No change to what the lock guards (only the screen; tunnels, the service and notification actions go on).
- No re-attaching the androidx callback after recreation (the abandoned-prompt rule replaces it).
- No change to the prompt's authenticators or texts.

## Affected surfaces

- [`docs/contract.md`](../../contract.md) §9 Settings (fingerprint unlock) and the shared UI rules.
- No other surface contract covers the lock.

## Interfaces and constraints

- `AppSettings.appLockAfter`, stored as `app_lock_after`: one of `0s`, `30s`, `1m`, `5m`, `15m`. It is normalized in `decode`, like the
  MQTT limits, so a bad value is 30 s and nothing else resets.
- `LockPolicy`:
  - takes the interval on each `onStart`;
  - `0` locks on any absence;
  - an absence that started within a short window after `handoff(now)` uses `max(interval, 30 s)`;
  - `authAbandoned(now)` clears the prompt state without unlocking.
- `AppLock`:
  - each prompt has an id and an owning activity;
  - `onDestroy(activity)` abandons that activity's open prompt;
  - callbacks of a prompt that is not the current one do nothing;
  - `FragmentManager.isStateSaved` yields Cancelled without calling androidx.
- `MainActivity` overrides `startActivity(Intent, Bundle?)` and `startActivityForResult(Intent, Int, Bundle?)` to mark a handoff. This is
  the one place; launch sites are not edited.
- Settings UI: a `SettingRow` with `ValueChevron` and a `ChoiceSheet` with a footer, like "Check the server in the background". The
  "Unlock with fingerprint" hint names the interval. RU and EN texts must fit at 200% font, portrait and landscape.
- Preserve: the cold start lock, the 30 s default, the lock screen, key blocking while locked, "no screen lock any more turns it off",
  and Hide in Recents.

## Acceptance criteria

1. Unit tests of `LockPolicy`:
   - each interval, including Immediately;
   - the handoff rule and the expiry of its mark;
   - an abandoned prompt, both locked and unlocked;
   - the existing tests keep passing.
2. Unit tests of `AppLock` (or of a seam it uses) for the following. Each must be killed by a mutation.
   - abandon on owner destroy;
   - ignoring a stale callback;
   - Cancelled when the state was saved.
3. Settings decode test: missing, invalid and unknown `app_lock_after` give 30 s, and other fields are kept.
4. UI test of the row:
   - hidden when the lock is off;
   - the sheet lists the five choices;
   - the pick is saved;
   - EN and RU, at 1.0 and 2.0 font, portrait and landscape.
5. Emulator reproduction with a synthetic PIN:
   - before the fix, a prompt abandoned by recreating the activity leaves the lock screen spinning;
   - after the fix, it asks again and unlocks with the PIN;
   - Immediately locks after Home; returning from the photo picker within 30 s does not lock.
6. `docs/contract.md` describes:
   - the cold start rule and the interval;
   - that the service keeps the process alive;
   - the app-opened window rule;
   - the abandoned prompt.

## Validation

- `./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`
- `git diff --check`
- The repo-docs checker.
- Emulator runs on emulator-5580 (API 35): the settings UI test in 4 configurations × EN/RU, and the manual reproduction. Restore the
  font scale to 1.0 and remove the synthetic PIN afterwards.
- Not covered: a physical Xiaomi device and a real fingerprint sensor.

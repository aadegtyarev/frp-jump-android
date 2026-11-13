# Feature: hold external windows while the app is locked

Status: Approved by the operator via the peer session (Р18, 2026-11-13). This is a small release right after 0.3.71.

## Goal and user-visible behavior

While the fingerprint lock is engaged, nothing the app launches by itself may appear over the lock screen. Some launches start
asynchronously: a download that finishes, an update, a web page's redirect to an external app, and so on. Such a launch is held. It
runs once the owner unlocks, unless it is dropped first.

## Scenarios

- Locked when the app calls `MainActivity.startActivity` or `startActivityForResult` for an external window (viewer, Share, browser,
  SSH client, installer, system settings, pickers): nothing is started.
- The app keeps only the latest held launch: a newer one replaces an older one. After a successful unlock, the held launch runs once, as
  an app-opened window. It sets the handoff mark only when it actually starts.
- A held launch is dropped, and never runs later, when:
  - the screen goes out of sight while locked, except during the prompt's own stops;
  - the activity is destroyed;
  - the lock is turned off by "no screen lock any more". In that case nothing was protected; dropping keeps this simple and strict.
- Unlocked: launches behave exactly as today.
- The lock's own prompt never goes through this gate. The androidx fallback for device credentials uses `startActivityForResult` from
  its fragment, and that must still start while locked. Only launches made by the app's own code are held.
- In-app navigation and screens under the lock are not affected.

## Non-goals

- No queue of several launches and no UI for held launches. The owner can repeat an action that was dropped.
- No change to what the lock guards, nor to the interval rules from 0.3.71.

## Affected surfaces

- [`docs/contract.md`](../../contract.md) §9 "Fingerprint unlock": add the held-launch rule.

## Interfaces and constraints

- The gate is one place: the `MainActivity` overrides that 0.3.71 added.
- Distinguish the androidx biometric fallback from the app's own launches. Options include checking the intent's component, checking
  whether the call comes from the biometric fragment, or a flag around `AppLock.authenticate`. Choose the simplest one that cannot be
  fooled by app code, and test it.
- Inventory every asynchronous source of external launches and list them in the PR: Files (download, open with, share), the updater
  and installer, the WebView's external links, notifications and their actions, MQTT, and SSH key share. For each, say whether it can
  fire while locked.

## Acceptance criteria

1. Unit tests of the gate's rules:
   - held while locked;
   - the latest replaces an older one;
   - runs once after unlock;
   - dropped on stop while locked, on destroy, and on "no screen lock";
   - not held while unlocked;
   - the biometric fallback is never held.
   - Each rule is killed by a mutation.
2. An instrumented test on the emulator: while locked, an app launch (for example `Settings.ACTION_SETTINGS` through `MainActivity`)
   does not start. After unlock (or the no-screen-lock path) it does or is dropped, as specified.
3. The PR lists the inventory of asynchronous launch sources.
4. The `docs/contract.md` §9 text is updated.

## Validation

- `./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`
- `git diff --check`
- The repo-docs checker.
- Emulator runs of the new test and of the lock tests from 0.3.71.

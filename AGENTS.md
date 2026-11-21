# Project instructions

## Working principles

- Most result for least effort: first the 20% of work that gives 80% of the result.
- Simplest code in the existing architecture. Handle realistic failures only; no speculative abstractions or fallbacks. Never weaken contract or security invariants or widen scope.
- Split a large feature into small slices: fewest surfaces, least code, checkable result, one PR each.
- Before designing, see how good apps solve the task. Name and shape equivalent actions alike everywhere.
- UI: fewest steps, common actions direct, short labels. Never drop functionality, accessibility, truthful feedback or safety warnings for minimalism. Check your UI on screenshots before showing it.
- No archaeology: code, comments, contracts and docs describe only the current state. No "previously", "no longer", "removed in #N", commented-out or kept-for-reference code, or history notes; git keeps history. Comments give only a short "why" the code cannot say.
- Run only the tests the change can affect, plus the unit gate before merge (Checks).

## Contracts
- Follow `docs/contracts/meta-contract.md` (approval, exact paths for every role, stop on scope change, cleanup).
- Only large or risky features (security, assistant permissions, stored data, cross-surface) need a feature contract in `docs/contracts/features/`. Read the relevant sections of affected surface contracts; keep contracts compact.
- Never overwrite or delete instructions or contracts silently.
- Delegate nontrivial read-only reconnaissance to a configured role; the parent owns scope, decisions, verification and user communication.

## Project facts

- Kotlin/Compose, Java 17; modules `app`, `agent-core`, `agent-openai` (plain JVM); SDK compile 36, target 35, min 29; Gradle wrapper.
- Legacy surface contracts stay normative in place: `docs/contract.md` (shared UI rules, app behavior) and every `docs/*-contract.md`. Link them; never move, replace or duplicate them.
- Contracts hold security, assistant-permission and stored-data promises and core behavior; layout details live in code and tests.
- Leave unrelated TV, icon and clipboard work alone. Keep security and cancellation guarantees.
- Docs in the existing English style. End every task with a short report in Russian: changes, checks, risks; separate inspected, reproduced, implemented, tested, merged, published, downloaded-artifact verified. Never report unrun checks or releases as done.

## Checks

- Code: focused tests, then the unit gate. Read test counts and failures, not just exit status.
  ```sh
  ./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest \
    :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
  git diff --check
  ```
- Docs: run the `repo-docs` skill's checker.
- Emulator: run only the changed screens' test classes (a label change: that screen's), portrait 1.0 RU, plus landscape 200% EN for layout changes; gestures, drawing or system dialogs may get one targeted set. Full suite only for shared parts (navigation, settings storage, app lock, tunnels) or every few releases.
- Mutation testing only for security: confirmations, login, permissions, secrets.
- Reuse API35 `emulator-5580`; never wipe or duplicate it; restore font scale 1.0. Emulator evidence is not physical-device, vendor-IME or live-service proof. Synthetic credentials and services only; never production profiles, keys, providers, SSH, tunnels or brokers.

## Delivery and authority

- Merge at the matched head after the unit gate; scan new commits with Gitleaks before push. One writer per worktree.
- The parent keeps scope, acceptance, commit, PR, merge and release authority. Releases are pre-authorized: after a slice's version PR is reviewed and merged, tag and publish it; otherwise no release, tag or version bump without explicit operator instruction.
- Models and roles: plan on the strongest model (astra, or sol at high effort), implement on terra, review on another model (sol). Every slice gets that review, scaled by risk: medium effort plus the unit gate for small UI changes; high for security, assistant permissions, stored data or shared navigation. With one model: plan at high effort, implement, review in a fresh context. Never below medium effort. No silent external-CLI fallback.
- Release from a main commit whose changed screens passed both configurations, with 1.0 and 200% screenshots viewed (`ltest.sh` → `build/shots`). The version PR has only version and notes. A flaky test gets one rerun; if green, release, name it in the notes, open an issue.
- Attribution, two trailing lines: `Environment: Pi`, `Model: <the model that wrote the change>`.

## Review

- Block only on a reachable defect with real harm, unapproved scope, a weakened security, assistant-permission or stored-data promise, unjustified complexity, or added archaeology or essay comments.
- A fix covers the reachable cases of the same defect class, with a test each.
- A change to a security, assistant-permission or stored-data promise keeps an "old → new → basis" table in the PR: each old promise retained, moved, conditionally retained or removed on approved grounds. A removed plan keeps its approved safety, data and compatibility constraints; code never authorizes a weaker promise.
- Review rounds are bounded; at the cap, decide.
- `.pi/agents/` profiles keep their tool and role boundaries; no task adds mutation, policy, publication or delegation authority.

## Redesign requirements

For every redesign slice and its review. The full wording in [docs/redesign-requirements.md](docs/redesign-requirements.md#rule-details) binds equally: read the rules your slice touches there before starting. The rules record approved direction, not proof of implementation; mocks and gallery suggestions alone approve no change. Surface contracts and their security, privacy, retention and lifecycle guarantees stay normative.

1. Scope: Devices, Tunnels, Sessions, Settings, assistant entry and context, list gestures.
2. Terminal, Files, Web, MQTT stay unchanged except approved narrow routing; check opening and return.
3. State and operations stay in their layers ([operations contract](docs/contracts/surfaces/operations.md)). The assistant gets only already permitted tools and data; references grant no authority, credentials or data access.
4. One owner per decision; shared logic lives once in the owning layer; no second execution path.
5. The working principles apply; they never cut required checks.
6. One home per task, each detail once; a duplicate control only as an approved shortcut with the same action and form. Name first; labels reflow.
7. Frequent actions in context, rare in menus. Show scope before bulk actions; keep the top Undo notice and irreversibility warnings. Show applied filters; clearing one keeps the others.
8. Reuse FRP Jump's fonts, palette, glyphs, shapes, notices and forms. Before mocking an existing interaction, inspect its production component, strings and a render; build on it. Touch targets at least 48 × 48 dp.
9. Useful empty, loading and error states; input survives save failure; status not by color alone; controls expose name, role, state.
10. Settings opens from the gear on Devices; Back keeps profile and list.
11. One assistant chat for Settings and bottom navigation; when off, entries hidden, chat disabled with an explanation.
12. Tunnels: one list across profiles; device filter is a bottom sheet with search, multiselect, count, one-tap reset.
13. Tunnel form reused; SSH on port 22 by default.
14. Devices: online filter, search, favorites; tunnel management stays on Tunnels.
15. Sessions: search, close one or selected with Undo; tunnels stay on.
16. Swipes reveal, never execute: right favorite (Devices, Tunnels), left Ask Assistant (all three lists). Long press selects.
17. Context attaches to the next chat message; no prefill, auto-send or permission change.
18. Bulk actions hit exactly the selection with one Undo.
19. Root orchestrates; roles per Models and roles. Review top-down across layers and entry points, not only the diff, for fragmented or overengineered logic.
20. The reviewer compares changed screens and states with the approved mock (same viewport, data) and exercises changed transitions; a failed acceptance criterion blocks merge. After merge, recheck main, release under the rules above, verify the APK and checksum, then start the next slice.

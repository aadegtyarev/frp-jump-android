# Project instructions

<!-- BEGIN ad-pi-addons contract workflow -->
## Contracts and feature work
- Read `docs/contracts/meta-contract.md` before implementing or reviewing a feature.
- Before implementing a large or risky feature (security, the assistant's permissions, stored data, or a cross-surface change), agree on and save the approved feature contract under `docs/contracts/features/`. Smaller work needs no feature contract. Always identify and read every affected surface contract at its actual repository path, including the legacy documents listed below. Prefer `docs/contracts/surfaces/` for new surface contracts when a distinct boundary justifies one.
- Give the exact contract paths to the planner, implementer, and reviewer. If working directly, use the same contracts as the source of truth.
- If implementation reveals a changed surface or acceptance scope, stop and get approval before proceeding. After verification, update affected surface contracts, mark the feature contract complete for final verification, then delete the temporary feature-contract file.
- Preserve existing instructions and surface contracts; never overwrite or delete them silently. Feature contracts are temporary task artifacts and must not remain after verified completion.
- Use available subagent roles only when configured. For nontrivial codebase or documentation reconnaissance, delegate bounded, read-only investigation to a suitable configured role when available; request relevant source paths and concise evidence, not whole-document dumps. Keep trivial lookups direct. The parent owns scope, decisions, synthesis, verification, and user communication. If no suitable role is available, work directly from the same contracts and checks, and state if independent review cannot be obtained. This rule does not require named Pi skills.
- End every task with a concise user-facing report of what changed, checks and outcomes, and remaining risks or blockers. Do not finish with tool output alone or end silently; this rule requires no installed skills.
<!-- END ad-pi-addons contract workflow -->

## Whole-class review and correction

- The parent preserves each finding's stable class ID, violated invariant,
  original scope and closure criteria when delegating. Example lines do not
  narrow a class. In particular, loss of conditional requirements is not merely
  a Save-specific defect because Save was the first example.
- Route a cross-document/class sweep to every affected owner. Each owner checks
  its entire applicable diff and reports adjacent-owner cases; ownership remains
  exclusive. The parent consolidates coverage before declaring global closure.
- Before another review, require a per-class table:
  `scope/case | inspected evidence | disposition/change | validation | remainder`.
  Compare it with the actual diff and original requirements. Named examples
  repaired, a green mechanical checker, or an unsupported "complete" claim is
  insufficient. Record partial/unreviewed scope and unresolved decisions.
- Reviewer reports class/invariant, applicable boundary, proven examples,
  checked-case inventory, closure criteria and inspection limits. Worker fixes
  all applicable owned cases in one pass, validates the blast radius, and
  distinguishes complete-within-scope, partial and blocked outcomes.
- When a change alters a security, assistant-permission or stored-data
  promise, account for each old promise as retained, moved, conditionally
  retained or removed with approved grounds (an "old → new → basis" table in
  the PR). Removing a future
  implementation plan must not remove its approved safety/data/compatibility
  constraints; implementation behavior alone cannot authorize weaker promises.
- A later analogous instance is a same-class coverage failure, not automatically
  a new independent line-item finding. Reassess scope/root cause before another
  fix loop. Review limits still apply; reaching a cap requires disposition,
  not an automatic extra round or repetitive requests for tiny patches.
- These project profiles live in `.pi/agents/worker.md` and
  `.pi/agents/reviewer.md`. They retain native tool/role boundaries; no task or
  class sweep grants extra mutation, policy, publication or delegation authority.
  Retained runs keep their recorded contracts; use fresh launches for new
  profiles or explicitly provide the protocol in an authorized retained follow-up.

## Redesign requirements

- For UI work, inspect the existing production component, localized strings and a rendered reference before creating a mock. Reuse the component in the app. Compare the mock and implementation with the production reference during review.
- Terra implements and Sol independently reviews every redesign slice, including UI slices. Root orchestrates. This assignment overrides the default roles below for redesign work.

These 20 rules apply to every redesign slice and its review. They record approved direction, not evidence that later slices are implemented or verified. Existing surface contracts and their security, privacy, data-retention and lifecycle guarantees remain normative. Resolve unapproved interaction, layout and implementation details through each slice's contract and review; gallery suggestions alone do not approve additional changes.

### Scope and architecture

1. Redesign Devices, Tunnels, Sessions, Settings, assistant entry and context, and gestures on those lists. Devices selects a device, Tunnels manages access, and Sessions returns to started work.
2. Keep Terminal, Files, Web and MQTT as they are except for separately approved narrow routing changes; check opening and return when a route changes. A possible hidden-files empty-state fix is a proposal, not approval to change Files.
3. State and operations remain owned by existing layers, under the [operations surface contract](docs/contracts/surfaces/operations.md). UI and LLM tools consume shared operations and state where already provided; UI owns presentation, selection, navigation and feedback. The assistant receives only already permitted tools and data; references do not grant authority or introduce credentials, data access or capabilities.
4. Each decision or rule has one authoritative owner. Extract shared logic with the same semantics into reusable functions or modules at that owning layer, and have callers consume them rather than duplicate it across layers. Keep UI- and LLM-specific concerns out of shared logic. Moving a control or adding bulk actions must not create another execution path.
5. Favor fast delivery and readable, compact code following existing architecture and APIs. Handle realistic failures and preserve contract and security invariants; avoid speculative fallbacks, abstractions and defenses for impossible or unsupported cases. This does not reduce required checks or widen scope.

### Design principles

6. Use the fewest elements and understandable steps that achieve the task. Give each task one clear home, show each detail once, and avoid duplicate controls unless an approved shortcut adds value and uses the same action and form. Put the object name first and distinguishing details second. Let labels reflow when space is tight.
7. Keep frequent actions in context and rare actions in menus. Show scope before bulk actions; retain the existing top Undo notice and required warnings for irreversible actions. Show applied filters. Clearing a query or resetting a filter must preserve independent criteria.
8. Reuse FRP Jump's Roboto, palette, glyphs, shapes, notices and forms; equivalent actions look and behave alike. Before drafting a mock for an existing interaction, inspect the production component, its localized strings and a rendered reference. Match the component's layout, labels, action order, emphasis and applicable states in the mock. Implement through the same production component, extending its data where necessary. In review, compare the mock and the implementation with the existing reference at comparable sizes and state. Before designing each UX change, study how popular apps handle the same task and consult relevant platform guidance. Adapt those patterns to FRP Jump's controls and normal mobile sizing. In review, identify the relevant references and the behavior adapted. Match equivalent header controls to the mock in corner radius, height, alignment and interaction states. Follow platform accessibility guidance for touch targets independently of visible control size. Show compact segmented pairs as one group, with a separate, non-overlapping touch target of at least 48 × 48 dp for each action. Preserve approved R39 Settings order, appearance, controls and values.
9. Make state suggest the next step: provide useful empty, loading and error states, retain input on save failure, distinguish status without color, and preserve accessibility, progress and safety feedback. Distinguish an empty collection from a search with no matches. Offer the recovery appropriate to the cause, and expose each interactive control’s name, role and state to accessibility services.

### Settled slice direction

10. General Settings opens from the gear on Devices; contextual paths such as the update announcement remain available. Back retains the invoking device profile and list.
11. Settings and bottom navigation open the same assistant chat. When off, hide bottom and contextual entries; Settings can open that chat with an explanation, disabled composer and a route back to assistant settings. Preserve history, drafts and lifecycle under the existing privacy guarantees.
12. Tunnels has one list across profiles. Device navigation selects the profile and device; preserve search, active filtering, favorites, permission marks and Undo. The device filter opens as a bottom sheet with search and checkbox multiselect; show the selection count and provide single-tap reset. Keep each device group name visible when filtered; hide empty groups in the general list and give a selected empty device one add action. Tapping a picker row toggles it once. Preserve selection through search and scrolling and keep the last result reachable with the keyboard open. At rest, show search, the compact square device picker, active toggle/count and power action in one row. The count and power action refer to the same scope. While search has focus, give it the toolbar and provide one-tap return to the ordinary controls. Preserve the query and selected devices on return. Allow reflow at large font.
13. Reuse the tunnel form with SSH and port 22 as the approved default. Active SSH's folder opens existing Files; the off-state menu retains its Files entry.
14. Devices supports online filtering, search and favorites without duplicate entries; label and icon editing reuse the existing form. Retain the profile-name treatment. Tunnel management belongs on Tunnels.
15. Sessions supports search, opening a session, closing one or selected sessions with existing Undo, and assistant sessions. Closing a session leaves its tunnel on. A tunnel's Terminal action creates a session at zero, opens the one at one, and offers a choice at several.
16. On Devices and Tunnels, a right swipe reveals favorite without executing it. On Devices, Tunnels and Sessions, a left swipe reveals Ask Assistant without executing it; long press starts multi-selection. Preserve vertical scrolling and system Back.
17. Context entries use the shared chat and attach one or more identifiable objects to the next message. Provide a stable context slot, show and allow removal of context, and keep the question field as a question rather than prefilling an object title; never send automatically or change permissions. History reflects what was sent, and the context clears from the next message.
18. Multi-selection actions affect exactly the selected active tunnels or selected sessions, with one Undo. Select all targets the current list.

### Delivery and verification

19. Root orchestrates scope, acceptance and closure; Terra implements and Sol independently reviews every slice, including UI slices. One coherent slice is one PR. Before implementation, inspect the worktree and agree and record any required feature contract. Review each slice top-down across every affected layer and entry point, not only the diff, for fragmented or overengineered logic and the architecture boundary; review the diff and Gitleaks results before pushing.
20. Run focused logic and changed-screen tests and required repository gates, covering complete changed paths, immediate transitions, visible outcomes and steps. The independent reviewer must compare implementation screenshots of each changed screen and its distinct changed states side by side with the corresponding approved mock at matching viewport and data. Exercise affected transitions, such as search focus and return, filter reset, selection across scrolling, bulk scope and Undo. Verify the resulting objects and state. Block merge on a failed acceptance criterion until the fix passes review. Check portrait, narrow screens and long names; check landscape, large font and keyboard when relevant. Broaden checks for shared navigation, security, stored data or authority. After merge, verify the matched commit on main, repeat relevant checks and inspect changed-screen screenshots; release the slice and verify the downloaded APK and checksum under repository release authorization rules. Start the next slice after the previous release is confirmed. Report actual evidence and limits, never unrun checks or releases as complete.

[Reference examples](docs/redesign-requirements.md#reference-examples) illustrate the interaction conventions.

## Project-specific commands and conventions

- Android/Kotlin/Jetpack Compose, Java 17; modules `app`, `agent-core`,
  `agent-openai`. The latter two are plain JVM modules. Android compile SDK 36,
  target SDK 35, minimum SDK 29; use the Gradle wrapper.
- Existing surface contracts remain normative at their current paths:
  `docs/contract.md` (shared UI rules and app behavior),
  `docs/agent-contract.md`, `docs/files-contract.md`, `docs/mqtt-contract.md`,
  `docs/ssh-persist-contract.md`, `docs/terminal-panel-contract.md`,
  and `docs/terminal-text-input-contract.md`. Link the affected legacy paths from
  feature contracts; do not silently move, replace or duplicate these documents.
  Empty `docs/contracts/surfaces/` does not mean surface contracts are absent.
- Surface contracts record promises about security, the assistant's
  permissions and stored data, plus each surface's core behavior. Layout and
  small UI behavior details live in code and tests, not in contracts.
- UI/UX must be concise, understandable and functional, with minimal steps.
  Keep common actions directly accessible and labels/messages short and clear.
  Do not remove functionality, accessibility, truthful feedback or required
  safety warnings for visual minimalism. Check these properties in UI review.
- Run relevant focused tests, then the affected full gates:

  ```sh
  ./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest \
    :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
  git diff --check
  ```

  Load the available `repo-docs` skill for contract/documentation work and run
  its documentation checker. Inspect actual test counts and failures; command
  exit status alone is insufficient for Android instrumentation.
- For local API35 checks, reuse `emulator-5580`; do not wipe or duplicate it.
  Check portrait, landscape and 200% font where UI changes require them; restore
  font scale to 1.0. Distinguish scoped emulator evidence from physical-device,
  vendor-IME and live-service validation. Use synthetic credentials/services;
  do not access production profiles, keys, providers, SSH, tunnels or brokers.
- Small coherent checked PRs, then matched-head merge. An independent review
  is required only for risky changes: security, the assistant's permissions,
  stored data. Small UI changes go by the unit gate.
  The parent retains scope, acceptance, commit/PR/merge and release authority.
  Use one writer per worktree. Configured implementation role:
  `openai/gpt-6.1-sol:medium`; independent fresh read-only review:
  `openai/gpt-6.1-terra`. No silent external-CLI fallback after lane failure.
- Scan new commits with Gitleaks before pushing. Do not release, tag or bump
  versions without explicit operator instruction. Do not touch unrelated TV,
  icon or clipboard work. Preserve existing security and cancellation guarantees.
- Keep repository documentation in its existing English style; report progress
  to the operator in Russian. State separately what was inspected, reproduced,
  implemented, tested, merged, published and downloaded-artifact verified.
- Commit/PR attribution:

  ```text
  Environment: Pi
  Model: openai/gpt-6.1-sol
  ```
- Merge each slice as its own PR after the unit gate and Gitleaks (plus an
  independent review when the rule above requires one). Emulator checks are
  scoped to what changed: each PR runs the test classes of the screens it
  changes in portrait 1.0 RU, plus landscape 200% EN when it changes layout.
  Before a release, run the changed screens' classes on main in both
  configurations and look at screenshots of those screens at 1.0 and 200%
  (tests save them; `ltest.sh` pulls them into `build/shots`): instrumentation
  checks that nodes exist, not how a screen looks. Run the full suite only
  when shared parts change (navigation, settings storage, app lock, tunnels)
  or every few releases. Release only from a main commit that passed these
  checks; the release PR carries only the version and notes. A flaky test gets one rerun; if it passes, release, name it flaky in
  the notes and open an issue. Mutation testing only for security:
  confirmations, login, permissions, secrets. A slice that touches what unit
  tests cannot catch (a gesture, drawing, a system dialog) may ask for one
  targeted emulator set.

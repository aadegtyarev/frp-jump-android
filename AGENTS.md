# Project instructions

<!-- BEGIN ad-pi-addons contract workflow -->
## Contracts and feature work
- Read `docs/contracts/meta-contract.md` before implementing or reviewing a feature.
- Before implementation, agree on and save the approved feature contract under `docs/contracts/features/`; identify and read every affected surface contract at its actual repository path, including the legacy documents listed below. Prefer `docs/contracts/surfaces/` for new surface contracts when a distinct boundary justifies one.
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
- For contract rewrites, account for old normative promises as retained, moved,
  conditionally retained or removed with approved grounds. Removing a future
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

## Project-specific commands and conventions

- Android/Kotlin/Jetpack Compose, Java 17; modules `app`, `agent-core`,
  `agent-openai`. The latter two are plain JVM modules. Android compile SDK 36,
  target SDK 35, minimum SDK 29; use the Gradle wrapper.
- Existing surface contracts remain normative at their current paths:
  `docs/contract.md` (shared UI rules and app behavior),
  `docs/agent-contract.md`, `docs/files-contract.md`, `docs/mqtt-contract.md`,
  `docs/ssh-persist-contract.md`, `docs/terminal-panel-contract.md`,
  `docs/terminal-text-input-contract.md`, and
  `docs/terminal-diagnostics-contract.md`. Link the affected legacy paths from
  feature contracts; do not silently move, replace or duplicate these documents.
  Empty `docs/contracts/surfaces/` does not mean surface contracts are absent.
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
- Small coherent checked PRs, independent review, then matched-head merge.
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

# Rewrite existing surface contracts

Status: complete; documentation verified and accepted by the parent for final
publication. The separate code-versus-contract audit has not started.

Verification: all eight durable surfaces were reviewed against their original
normative text. Parent whole-class coverage and exact-diff/scope checks passed;
fresh independent Terra review returned **OK with limits / No issues found**
for the complete eight-document preservation scope. Documentation/link and
whitespace checks passed. Review limitations and unresolved policy/code gaps
remain explicit; this is not certification of implementation conformance.
No runtime, build, device, live-service or release verification was performed.
This temporary feature is deleted after final verification; approval and this
completion record remain in Git history.

## Goal and approval

Establish a reliable contract basis for future work by rewriting the eight
existing surface contracts according to the [meta-contract](../meta-contract.md).
The operator explicitly confirmed this scope and the sequence: contracts first,
then a separate code review against them, then decisions on targeted changes.
This approval is not permission to rewrite the application or automatically fix
code-review findings. It does not retrospectively approve the frozen A4 work.

The app's runtime behavior must not change in this feature. Readers should be
able to identify each surface's current promises, failure behavior, boundaries
and intentional limits without reconstructing a development chronology.

## Affected surfaces

Keep the existing repository paths; do not perform a directory migration:

- [App and shared UI rules](../../contract.md)
- [Assistant](../../agent-contract.md)
- [Files](../../files-contract.md)
- [MQTT](../../mqtt-contract.md)
- [SSH persistence](../../ssh-persist-contract.md)
- [Terminal panel](../../terminal-panel-contract.md)
- [Terminal text input](../../terminal-text-input-contract.md)
- [Terminal diagnostics](../../terminal-diagnostics-contract.md)

The newly authorized root `AGENTS.md` and `docs/contracts/meta-contract.md`
connect this workflow to those existing normative paths. Preserve the canonical
meta-contract; do not redefine it during the surface rewrite.

## Scenarios and edge cases

- A maintainer starts a bounded change: find the relevant surface, identify its
  consumers and owner, and read observable inputs, outputs, guarantees and errors.
- A reviewer checks a change: distinguish approved promises from implementation
  choices and locate checkable acceptance conditions and related surfaces.
- A document mixes old implementation stages, historical validation and future
  plans: retain current normative behavior, move no history into a new duplicate
  specification, and rely on Git for removed chronology. Keep intentional
  non-goals and limitations explicit. Do not silently delete an approved promise
  merely because current code does not implement it.
- Source and contract disagree, or two contracts prescribe different policies:
  record the exact conflict and evidence for the parent. Do not select a new
  product/security policy or bless a code defect by rewriting the promise.
  Seek operator approval when the normative resolution changes the agreed scope.
- A claim cannot be verified from the inspected source/tests or prior evidence:
  label the inspection limit in the handoff. Do not invent a guarantee, device
  validation, successful publication or historical approval.
- The frozen, unmerged A4 coordinator exists outside the main baseline: it is
  not current available app behavior and must not be presented as accepted or
  wired. Chat and real assistant tools remain unavailable in this feature.

## Required document shape

Use concise, surface-appropriate sections rather than a repeated empty template:

- Boundary, consumers and ownership.
- Observable inputs, outputs and flows/scenarios.
- Interfaces/protocols and compatibility guarantees where applicable.
- Failure behavior and recovery.
- Security, privacy and data-retention limits.
- Intentional non-goals and behavior not guaranteed.
- Links to related surface contracts.

Do not turn surface documents into file-by-file implementation plans. Consolidate
shared promises by linking to their owner, without losing specific constraints.
Keep meaningful filenames and existing paths. Preserve internal link targets or
compatibility anchors when reorganizing a document.

## UX requirements

Keep the shared UX rules in `docs/contract.md` and explicitly inherit them from
surfaces that expose UI. They require concise, understandable and functional UI,
minimal necessary steps, directly accessible common actions, and short clear
labels/messages. Visual minimalism must not remove functionality, accessibility,
truthful waiting/error feedback or required safety warnings.

UI-facing contracts should make the relevant common task path and primary
operation checkable. Do not invent arbitrary tap-count targets without an agreed
flow; do not change existing Undo, confirmation, paste or permission policies.

## Constraints and non-goals

- Documentation only: no runtime, UI implementation, tests, dependencies, CI,
  persistence formats, wire protocols, security policy or version changes.
- No broad README rewrite, source refactor, surface-file relocation or mass
  reorganization. Preserve existing useful links and external references.
- No A4 continuation, acceptance, fixes, chat, real tools or release/tag.
- Inspect only synthetic/local source and existing test/evidence artifacts;
  no production data, credentials, live providers, SSH, tunnels or brokers.
- The later code-versus-contract review is a separate read-only stage after
  this foundation passes acceptance. Its findings require disposition, not
  automatic implementation.

## Acceptance criteria

1. All eight documents describe their current surface coherently and include
   the applicable sections above; no obsolete feature-branch status is claimed
   as current and no unimplemented future stage is promised as available.
2. Existing approved guarantees, error/recovery paths, security/privacy limits
   and accessibility requirements are preserved. Every changed normative claim
   has source/test/approval evidence or an explicitly reported unresolved conflict.
3. Shared UX principles are explicit and linked by the relevant UI contracts;
   related documents agree without duplicating entire specifications.
4. Each component handoff identifies source/test paths inspected, substantive
   old-to-new claim changes and their reasons, unresolved decisions, and limits
   of verification. A cleaner document is not evidence that code conforms.
5. The integrated diff contains only approved documentation changes, preserves
   working links, passes documentation/whitespace checks, and receives independent
   read-only review against this feature and all affected surface contracts.
6. Parent accepts and publishes only checked, coherent PRs. No code/release
   change or hidden policy resolution is bundled into them.

## Validation and completion

Read actual source and relevant tests for each changed factual assertion. Use
prior release artifacts only where their provenance is established. Run the
repository documentation checker and `git diff --check`; scan new commits with
Gitleaks before pushing. No new runtime/device test execution is required solely
for documentation, and none may be claimed without actual execution.

After integrated independent review and verification, update durable surface
contracts, mark this feature complete for final verification, and delete this
temporary file. Its approval and evolution remain in Git history. Keep the code
review and any remediation decisions separate from this documentation completion.

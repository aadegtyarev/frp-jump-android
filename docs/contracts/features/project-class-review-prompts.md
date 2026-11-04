# Project-local class-based review and correction prompts

Status: approved by the operator; configuration and verification in progress.

## Goal and approval

The operator explicitly requested project-local prompt overrides after agreeing
that review findings must identify defect classes and fixes must cover the full
applicable diff, not only reported example lines. Establish durable instructions
for worker, reviewer and parent without changing the app or global Pi settings.

## Affected maintainer-facing surface

- Root `AGENTS.md`: parent routing and acceptance responsibilities.
- Project-local `.pi/agents/worker.md` and `.pi/agents/reviewer.md`: role prompts.
- The [meta-contract](../meta-contract.md) remains unchanged. There is no separate
  existing surface contract for these project instructions; the instructions
  themselves are the durable maintainer interface.

## Scenarios and interfaces

- Reviewer finds repeated defects: report a stable class identifier, violated
  invariant, affected scope, representative examples, closure criteria and honest
  coverage limits. Examples are not an exhaustive fix list unless stated/proven.
- Worker receives the finding: inspect all applicable changes within its owned
  scope, fix analogous cases, validate the class and report coverage/dispositions.
  A cross-owner case goes to the parent, not an unauthorized adjacent-file edit.
- Parent routes a class across component owners, retains its original breadth,
  consolidates evidence and checks coverage before another review. Clearing only
  the named examples is insufficient for class closure.
- A later review finds another occurrence of the same class: identify incomplete
  coverage/root cause rather than mechanically opening another line-item loop.
- Changed scope/policy or exhausted review budget still requires escalation.
  A class-wide sweep does not authorize project-wide cleanup or code fixes.

## Constraints and non-goals

Preserve existing role instructions, strict tool boundaries, context/acceptance
settings and operational authority. Project files shadow their bundled roles;
copy the complete native definitions rather than accidentally inheriting broader
omitted defaults. No global/user package, settings, prompt or canonical
meta-contract edits. No model/provider changes, extension/CLI fallback, new tool
permissions, app changes, surface rewrites or automatic third foundation fix round.
The eight-contract candidate stays frozen and its surface-file bytes unchanged.
Existing retained runs keep their recorded contracts; do not claim they have
picked up these prompts or that prompts guarantee exhaustive correctness.

## Acceptance criteria

1. Both overrides are discovered from this repository and preserve their original
   role configuration and prompt content, with a bounded project-specific addendum.
2. Reviewer requires class/scope/examples/closure/coverage; worker requires a full
   applicable-scope sweep and case dispositions; parent must not narrow classes
   to examples or bypass ownership, scope and review gates.
3. Prompts distinguish documentation preservation from runtime conformance and
   retain approved guarantees when future availability is removed.
4. A bounded read-only synthetic exercise demonstrates the expected report and
   scope-sweep behavior; configuration/source checks remain separate evidence
   from any broader reliability claim.
5. Documentation/whitespace checks pass; no app or frozen surface-file change is
   bundled. Report exact runtime discovery and exercise limits.

## Validation and completion

Inspect effective definitions and permissions before/after through native agent
management and compare original role fields/body against project copies. Check
project discovery, run documentation/whitespace gates and a small synthetic
exercise without app mutation or live services. Parent reviews results, marks
this feature complete for final verification and deletes the temporary contract
when verified, retaining durable behavior in AGENTS and role files. Publication
and the blocked foundation review remain separate decisions.

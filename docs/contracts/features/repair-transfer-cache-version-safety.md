# Repair transfer, cache and explicit-version safety

Status: approved by the operator; implementation and verification pending.

## Goal and approval

After the separate read-only code-versus-contract audit, the operator agreed
to the recommended first safety batch: FILES-1 (physical transfer cancellation
and commit), FILES-2 (app-owned copy ownership and retention), and the explicit
incompatible `/version` case of APP-VERSION-01. Correct these whole applicable
classes through small checked PRs, not isolated illustrative lines. This is not
approval to fix all audit findings or rewrite the application.

The operator subsequently resolved the external-handover retention boundary:
**10 minutes by default, configurable in Files settings**. This intentionally
supersedes the former ambiguous handover cleanup wording “on exit and daily”;
no receiver-finished callback or arbitrary one-day grace period is required.
The narrow settings field/UI and corresponding retention-policy updates are
part of this approved feature, not a general settings or persistence refactor.

Affected durable surfaces:

- [Files](../../files-contract.md): visit/row ownership, transfers, viewer and
  handover copies, cancellation/failure cleanup, privacy and retention.
- [App and shared UI](../../contract.md): existing `/version` compatibility,
  feature discovery/cache, truthful feedback and unchanged running tunnels.

Read the [meta-contract](../meta-contract.md) and project instructions. Existing
normative promises remain the authority; implementation absence is not a waiver.

## Scenarios and required behavior

### FILES-1: cancellation through physical commit and handoff

- Cancel a row, leave a visit, return to a kept session, or close it while an
  upload/download is blocked in read, write, flush or close. A cancellation
  observed before the operation's irrevocable commit must prevent subsequent
  publication and clean the exact app-owned incomplete copy/part.
- Cover upload final rename and its clash retry, phone-folder downloads and
  both viewer and handover-cache downloads. A cancellation between download
  completion and owner delivery must not orphan a newly created cache copy.
- Revoked visits and replaced row owners cannot publish success/progress,
  remove newer owners or transfer their physical cleanup responsibilities.
- Distinguish an already committed remote action from a still-preventable
  action; do not claim transport cancellation rolls back a rename already
  performed. Network failure can still prevent remote part cleanup.
- Preserve current Keep both, exclusive part creation, same-user permissions,
  independent Files transport, transfer concurrency and bounded close cleanup.

### FILES-2: ownership-aware app-copy retention

- Normal viewer close releases/deletes its viewer copy. Share/Open with uses
  an independently owned handover copy and keeps narrow FileProvider access.
- Viewer/orphan cleanup retains the existing close/exit/start guarantees;
  protect active viewer/transfer/copy-operation owners across screens/profiles.
- A copy handed to an external application becomes eligible for cleanup
  after the configured interval, **10 minutes by default**, counted from the
  handover launch. Files route exit and application backgrounding do not
  shorten that interval. Launch success is not proof the recipient has read
  the file. Unlaunched/failed/abandoned handover copies retain explicit owner
  cleanup rather than being treated as successfully delivered.
- Run expiry cleanup while the application process is alive and on startup,
  including after a suspended or dead process missed an expiry. Do not promise
  exact Android scheduling or immediate deletion during process death. A daily
  sweep alone must not make the ten-minute setting effectively a one-day delay.
- Add a concise duration choice in Files settings, defaulting to ten minutes
  for missing/invalid old values without resetting unrelated preferences.
  Persist changes through the existing settings owner, report failed writes
  truthfully and apply the chosen interval consistently to existing and new
  handed copies, using their original handover age. Preserve live in-app owners.
  No new service, dependency, receiver-completion tracking or broad grant.
- Cleanup by one screen/profile must never delete another live owner's copy
  merely because it is old. Transfer creation, successful delivery, cancellation,
  disposal, handover and cleanup must agree on physical-copy ownership.
- Before handover, preserve the required usable copy under its live owner.
  After launch, the approved finite interval is the protection: expiry may
  remove a copy a recipient has not yet opened or wants to reopen. This is an
  accepted limitation, not a claim that launch proves reading completion.
  An already open descriptor may continue reading after unlink. Do not promise
  deletion of another app's independent copy or control its grant lifetime.
  Do not silently extend retention because an external application might still
  use the file, or shorten it merely because Files goes into the background.
- Cleanup failure must not be claimed as successful deletion; do not introduce
  content/path/credential logging or broader cache exposure.

### APP-VERSION-01: existing explicit `/version` field

- Reject a decoded `/version` response naming a protocol version other than
  the supported version before its features can be applied or cached.
- Preserve the prior feature cache/state on rejection, enrollment's check
  before spending its single-use token, existing error behavior and running
  tunnels. Valid version 1 and older compatible responses without optional
  `features` must continue to work.
- No new version field, response wrapper or server requirement. The broader
  every-response/legacy/204/error wire-shape question stays unresolved; do not
  turn absent optional fields on other endpoints into mismatches.

## Non-goals and constraints

- No repair of the other twelve audit classes, last-folder persistence,
  search startup/stale hits, plugin migration, MQTT dedup/WB, SSH/tmux or
  diagnostic behavior. Report newly discovered out-of-scope cases separately.
- No editor/Save, remote management, Replace/Skip, archives, automatic retries,
  resume, service-owned transfer queue, new assistant features or A4 work.
- No security/privacy change beyond the explicitly approved timed handover
  retention and its narrow app-private setting. No other retention relaxation,
  production credentials/services, live SSH/provider/broker access or global
  settings.
- Preserve concise shared UI, accessibility, truthful waiting/errors, Undo,
  selected-tab OSC52/manual Copy and unrestricted generic MQTT Publish.
- No dependency/CI/toolchain change or release/tag/version bump. Public version
  remains 0.3.48/code60. Parent retains publication and acceptance authority.

## Acceptance and validation

1. Establish deterministic failing regressions on the original implementation
   before fixes: blocked IO-to-commit/cancel and handoff cases; active versus
   orphan copy cleanup, cross-owner/profile and expiry/lifecycle cases; explicit
   incompatible version through actual adapter and feature-cache consumer.
   Record RED assertions/results, then GREEN, not just tests added after edits.
   Include default/missing/invalid duration settings, persisted choice and failed
   write, expiry boundaries, changed duration, startup catch-up, background versus
   route exit, live in-app copy protection and failed external launch.
2. Per class provide `scope/case | inspected evidence | disposition/change |
   validation | remainder`, including all analogous entry/exit/error/replacement
   paths, existing safe cases and honest physical-provider/transport limits.
3. Use synthetic clocks/files/sinks/transport/HTTP and production ownership
   seams; no sleep-based race claims or fixtures bypassing the behavior tested.
   If Android integration is required, use scoped API35 checks on existing
   emulator-5580; no wipe/duplicate, restore font scale to 1.0 when changed.
4. Run focused regressions, affected full JVM suites, debug and Android-test
   assembly, Lint, documentation/whitespace and pre-push Gitleaks gates. Inspect
   actual counts/failures. Runtime/device evidence must be attributed accurately;
   no live-service, vendor-IME or physical-device guarantee from synthetic tests.
5. Use exclusive component ownership and one writer per worktree; integrate only
   frozen checked patches. Parent checks actual class coverage/diff before fresh
   independent Terra review. No automatic example-only repair/review loop.
6. Preserve durable contracts, updating only verified behavior/limits without
   weakening requirements. Parent merges only checked matched-head coherent PRs
   with revalidation between merges; no automatic release.

After verification and durable updates, mark this feature complete for final
verification, then delete it. Git preserves approval and completion. Remaining
partial audit coverage and other findings remain separate work.

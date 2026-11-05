# Assistant chat presentation

Status: approved for ready-component rewrite, 2026-11-05. The operator explicitly
requested removal of the custom implementation, approved a dependency and the
revised scope, then instructed «Делай». Original base:
`2541969c4149a4d54b2f46aad8a974a770bd6ce1`.

## Goal and scenarios

Make Chat a quiet, readable dialogue: restrained accent for user text, normal
ink for answers, small localized role labels and whitespace. Keep left alignment,
one background and the normal body font. No message bubbles, cards or avatars.
Pending user messages use the same presentation as completed ones.

Render assistant Markdown with an existing Compose component: bold, italic,
ordered/unordered lists and monospaced inline/fenced code. Keep text selection
and copying, long-answer scrolling, Unicode and readable code whitespace.
User input remains literal. Original turns, provider history and continuation
remain unchanged; formatting is exclusively presentation.

The library owns Markdown grammar, including malformed/nested constructs. The
operator explicitly replaced the earlier custom literal-preservation grammar
with library behaviour. Do not recreate that parser, its scanners or exhaustive
syntax-test programme in an adapter. Ordinary code must remain useful for reading
and copying; a parse error must show selectable original text rather than an
empty answer. This is not a promise of source-Markdown round trips.

HTML, links and images remain inert: no execution, actionable links, resource
loading or WebView. Use library rendering hooks for inert text, not another
lexer. Do not add image loaders, syntax-highlighting engines or streaming support.

## Scope and non-goals

Replace the unmerged custom formatter entirely with a small configuration of
Mike Penz's Compose Markdown renderer and Material 3 integration. Pin a version
compatible with the existing toolchain. One library family and its required
transitive dependencies are approved; broad dependency/toolchain upgrades are not.

Keep presentation changes to Chat, its dependency declarations, focused tests
and the verified description in [the assistant contract](../../agent-contract.md).
Read that full contract, [shared app/UI contract](../../contract.md) and
[meta-contract](../meta-contract.md), complete affected files and the cumulative
base-to-candidate diff. Necessary connected source is not limited to edit paths.

Preserve existing history limits, memory-only ownership, provider authority,
cancellation/cleanup, Send/Stop/Back, setup/error/retry and omission feedback.
No backend, provider, storage, composer/navigation or unrelated UI changes.
No live-service or real-profile probes. No version bump, tag, release, signing or
CI change. No new framework or future-facing abstraction.

## Acceptance and checks

- Required formatting and role distinction work in both themes without shrinking
  the body or introducing message containers; contrast remains sufficient.
- Selection/copy affordance, representative code/backslashes/whitespace, long
  answers and unchanged provider history are checked in the real Chat component.
- Links/images/HTML cannot trigger actions or fetch resources; parser failure
  retains the original answer. Verify our integration, not all library grammar.
- Replace obsolete custom-parser tests with focused integration/policy tests;
  preserve existing owner, Stop/recovery and navigation coverage.
- Run focused checks, then full core/adapter/app JVM suites, both debug APKs and
  Lint with `-PassistantChatComponentTests=true`; run docs and whitespace checks.
- Use existing API35 `emulator-5580`, explicit `AssistantChatTestRunner` and chat
  class filter: EN/light and RU/dark, portrait at font 1 and landscape at font 2.
  Inspect screenshots and restore font 1. No wipe/clear/uninstall/downgrade.
- Record actual test failures/counts, cache attribution and source/APK identity.
  Synthetic screenshots do not certify production system bars, physical devices,
  vendor clipboard contents or live providers. Never erase earlier failures.
- Same retained Sol reviewer performs one final independent R4; parent consumes
  source, evidence and findings before accepting. No automatic R5.

## Ownership, resource and publication

One whole feature, branch, final PR and acceptance. Same Terra writer and Sol
reviewer; no additional planning/scout/implementation lanes. The writer returns
a frozen handoff without waiting for parent tests. Review may overlap parent
validation, but a positive verdict must use actual matching new gate evidence.

Latest go-ahead restarts implementation under the revised scope. Parent applies
a conservative execution stop at **2026-11-05T19:17:42Z**, 40 minutes from the
first observed go-ahead at 18:37:42Z; this is a safety cap, not an hour-long coding
estimate or a claim that the operator prescribed that number. No automatic
extension or short forced-verdict phase windows. Stop and report a real blocker.
Earlier expired custom-parser plans are superseded; their failures remain history.

Parent owns commits, acceptance, Gitleaks, one checked PR and matched-head merge.
No release. After verified acceptance, update the durable contract, mark this
contract complete, check and snapshot it, then delete this temporary contract.

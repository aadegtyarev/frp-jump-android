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
reviewer. The operator now requires a read-only scout before any further coder
work: inspect the existing component integration and recommend the minimum
necessary plan, including an explicit no-code-change result when appropriate.
One file has one responsibility; cohesion and clarity matter more than line count.
Do not invent a rewrite or rerun passing tests merely to fill a workflow stage.

The operator explicitly restarted this goal as an active bounded task on
2026-11-05 (first observed 20:29 UTC), with the configured limit of 40 automatic
continuations. This renews work on the same feature, not a new review campaign.
Finish the interrupted same Sol R4; do not launch an automatic R5. Native children
receive normal uninterrupted run limits, not short forced-verdict windows tied
to the expired parent timer. Stop at the task limit or an unresolved decision.
Earlier expired timers and failed native runs remain historical evidence.

The existing candidate already has full JVM, APK, Lint and 36 component results.
If production/test bytes remain identical, verify their identities and consume
those results rather than rerunning them. If scout establishes a necessary code
change, parent first dispositions its plan; the same writer returns a frozen
handoff without waiting for parent gates. Validate the changed candidate before
positive final review or acceptance. Documentation-only authorization amendments
must be distinguished from the tested source and checked separately.

Parent owns commits, acceptance, Gitleaks, one checked PR and matched-head merge.
No release. After verified acceptance, update the durable contract, mark this
contract complete, check and snapshot it, then delete this temporary contract.

# Assistant text chat

Status: approved by the operator; implementation and verification pending.

## Goal and approval

Deliver **one usable text-chat slice in one candidate and one PR** using the
already configured OpenAI-compatible provider. The operator approved sending a
message, receiving a complete answer, continuing the dialogue, Stop and clear
failure feedback, with memory-only history and the boundaries below.

The complete slice includes navigation, conversation ownership, provider
integration, UI, tests and durable documentation. Backend-only or UI-only
handoffs do not satisfy it. Earlier unfinished Files and conversation candidates
were discarded by the operator; do not restore or continue them.

## Affected surfaces

- [Assistant](../../agent-contract.md): chat availability, conversation ownership,
  explicit provider use, memory history and verification.
- [App and shared UI](../../contract.md): the chat entry, concise accessible UI,
  permitted external traffic and unchanged unrelated app behavior.

Read both surfaces in full where applicable, the [meta-contract](../meta-contract.md)
and project instructions. Existing credential, cancellation, privacy and
conditional tool-safety requirements remain normative.

## User-visible behavior

- Settings → LLM assistant → Chat opens a normal back-stack screen next to the
  existing provider setup. Opening it does not resolve credentials or contact
  a provider. Disabled, unconfigured or unreadable setup has a clear explanation
  and a direct enable/setup/recovery path, not a dead control.
- Send explicitly starts one request. Show the user's text, truthful pending
  state and Stop, then the complete final assistant text. Follow-up messages
  use the same successful dialogue and provider instance. No streaming is added.
- Only one request, including credential preparation, may own the pending slot.
  Stop covers preparation and HTTP; show stopping until that owner exits, do not
  silently queue a replacement send, and suppress late answer/error/finally work.
- A failure leaves entered text recoverable for editing and explicit retry.
  Use concise fixed error messages for setup/read/crypto failures, timeout,
  context-full, invalid replies and provider failure. No automatic retry.
- Configuration recreation preserves the in-memory conversation and draft.
  Actual chat exit/pop and process death cancel/discard them. Do not put chat
  text or opaque continuation in persistent storage or saved-instance state.
- A committed module disable invalidates old work, including rapid off/on.
  A saved provider revision change cancels old work and starts a fresh active
  context rather than retargeting old history. Failed settings writes retain
  prior authority. Old results cannot publish into a newer conversation.

## Interfaces, ownership and privacy

- Reuse the existing kernel, adapter and root-bound credential store. Resolve
  saved credentials off Main only for explicit Send. Bind conversation identity
  to its provider instance, saved revision and committed-disable authority.
  Check current admission/publication authority, not only delayed UI observers.
- Preserve the same provider instance for opaque continuation; keep that state
  internal, bounded and out of rendered text, logs and persistence. Use the
  saved context/output settings and existing whole-turn request window.
- Only COMPLETE successful turns enter subsequent model context. Failed/stopped
  attempts must not become successful context. Unexpected tool proposals must
  not execute anything or start a tool-driven series of provider requests in
  this text-only slice. No provider/model fallback or implicit replay.
- Keep at most 32 complete successful turns in memory and accept at most 8,000
  UTF-16 characters of input. Reject oversize input with clear feedback; when
  dropping oldest complete retained turns or omitting request-window history,
  show an understandable omission indication. Never silently truncate the
  current input/answer or split a turn. These are small in-memory bounds, not
  a storage/compression feature.
- Send only explicit chat text/history and fixed chat instructions to the
  configured provider. Do not automatically attach app/connection data, the
  connection skill, profiles, files, credentials or diagnostics. Preserve the
  adapter's endpoint, redirect, retry, payload and sanitized-error protections.

## Non-goals

No streaming, tools, SSH/MQTT/Files/TCP actions, automatic app-context upload,
permission evaluator, durable history, new provider, dependency or agent
framework. No unrelated audit fixes, settings/navigation redesign, general test
platform, CI/toolchain change, release/tag/version bump or live-service probes.
Existing tunnels, manual Copy/OSC52 and generic MQTT behavior remain unchanged.

## Acceptance and validation

1. From enabled/saved setup, open Chat without provider traffic; explicitly send
   two messages and receive two complete answers using the same dialogue and
   provider identity. The second synthetic request proves first-turn context.
2. Check busy, Stop during preparation/HTTP, genuine cancellation, late
   completion/error/finally, timeout/invalid/tool reply/context-full, recovery,
   committed disable/off-on and provider revision replacement through actual
   production ownership seams. Preserve new owners and unrelated app work.
3. Check recreation versus actual exit, finite memory/input bounds, omission
   feedback and no credential/chat persistence or raw-sensitive error exposure.
4. Use existing fake providers/stores, controlled coroutine gates, synthetic
   loopback adapter responses and ComponentActivity/Compose patterns. No
   production profiles, keys, external LLM, SSH, broker or DocumentsProvider.
   Minimal test-only application selection is allowed if required for safe
   component execution; do not create a general runner/report/fixture framework,
   add libraries, or claim zero framework/OS IO. Never start the production
   Application on saved profiles merely to test this slice.
5. Run focused tests and required full JVM suites, debug and Android-test
   assembly, Lint, docs/whitespace and pre-push Gitleaks. Reuse API35 emulator-5580
   only for scoped synthetic UI checks: send/answer/follow-up, Stop/error/setup,
   recreation/exit and focused portrait/landscape/200%-font, RU/EN and light/dark
   usability coverage without an exhaustive Cartesian matrix. No wipe/duplicate;
   restore font1.0 and changed rotation. Inspect actual results, not shell exit
   status alone; distinguish component evidence from live-provider/device proof.
6. Executor and independent reviewer inspect the whole cumulative feature diff
   and necessary full connected source/lifecycle, not a file whitelist or last
   patch. Collect one consolidated findings packet; preserve honest read/test
   limits. Update durable surfaces after verification, complete/final-check/delete
   this temporary feature contract, and merge only the checked matched head.

## Resource and delivery boundary

The operator approved a **120-minute total bound** covering implementation,
checks, review, at most one consolidated correction and final acceptance work.
Use one executor/reviewer pair and at most two review rounds. Resumes/new
processes do not reset the bound; no autonomous extension, extra scope or
partial delivery. If the whole slice cannot finish, preserve it as unfinished
and stop for operator disposition. Publication remains parent-controlled;
public version stays 0.3.48/code60 unless separately authorized.

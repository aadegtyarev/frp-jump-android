# Assistant chat presentation

Status: approved for implementation. Operator: «Принято» to the agreed visual
slice, 2026-11-05. Base: `2541969c4149a4d54b2f46aad8a974a770bd6ce1`.

## Goal and user-visible behavior

Make the existing text conversation readable at a glance, like a quiet dialogue
in a document, not messenger bubbles. User text uses the existing restrained
teal/green accent; assistant text uses the theme's normal ink. Small localized
“You” / “Assistant” role labels provide a non-colour distinction. Both align
left on one background: no message borders, cards, bubbles or avatars. Separate
question and answer with whitespace, and complete turns a little more strongly.
Do not shrink the main text merely to fit more content.

Assistant answers support a basic, inert formatting subset: bold, italic,
unordered/ordered lists and monospaced inline/fenced code. Recognized formatting
markers do not leak into the ordinary rendered answer. Unsupported/malformed
markup stays readable as literal text rather than silently losing content.
No complete Markdown/HTML/browser engine is promised. User input remains literal.
Formatting is display-only: original answers, requests/history, limits and
provider continuation remain unchanged. Text remains selectable/copyable.

## Scenarios and acceptance

- A short question/answer and consecutive turns have clearly distinguishable
  roles, calm labels and spacing in both themes; distinction is not colour-only.
- Long, multiline, RU/EN and formatted answers remain complete and scrollable.
  Code retains meaningful whitespace; plain text and malformed delimiters stay
  readable. Formatting is not an excuse to truncate or mutate conversation data.
- Waiting/stopping displays the submitted user text consistently with completed
  user messages. Error/setup/omission notices, recoverable input and explicit
  retry remain accessible and truthful. Existing Send/Stop/Back/selection work.
- Portrait, landscape and 200% system font keep message text and controls usable;
  role/body colours meet WCAG AA on the actual background in light and dark.
- HTML, links/images and code are inert text, not executable/clickable contents;
  rendering never fetches resources or contacts providers. No WebView is added.

## Affected surfaces and constraints

Read the complete [app/shared UI contract](../../contract.md), especially UI
rules, and [assistant contract](../../agent-contract.md), plus the
[meta-contract](../meta-contract.md). Preserve all existing security,
authority, cancellation/cleanup, memory-only history and provider guarantees.
Read all affected files and necessary connected owners/callers, not merely an
edit-path list, and the full cumulative diff from the approved base.

The seam is chat presentation + its pure formatting and component tests; the
conversation/backend remain the existing consumer/owner. Prefer existing
Compose/palette facilities and a small inert subset, without adding dependencies,
frameworks or a general Markdown subsystem. If this requires a new dependency,
changed surface/acceptance scope or altered lifecycle, stop and ask first.
Durably document the verified presentation in `docs/agent-contract.md`; do not
rewrite unrelated contracts or the README.

## Non-goals

No provider/transport/tool/Internet capability changes, app-data attachment,
streaming, persistent history, new chat settings, composer/navigation redesign,
new copy/export actions or changes to other application surfaces. No version
bump, tag, APK release, signing/CI changes or real-provider/device-data probes.

## Validation and evidence

- Focused pure rendering tests: each supported style/list/code form, plain and
  malformed/unsupported input, Unicode, multiline/long content, inert HTML/URLs/
  images, code whitespace and unchanged original conversation text. Check theme
  contrasts and role distinction.
- Full `:agent-core:test :agent-openai:test :app:testDebugUnitTest
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` with
  `-PassistantChatComponentTests=true`, plus documentation checker/whitespace.
- Safe synthetic `AssistantChatScreenTest` component suite on existing API35
  `emulator-5580`: representative EN/light/portrait/1.0,
  RU/dark/portrait/1.0, EN/light/landscape/2.0 and RU/dark/landscape/2.0.
  Preserve existing ownership/recovery cases and exercise formatted/long answer
  selection/rendering. Inspect representative screenshots in both themes and
  large font. Restore font scale1.0; do not wipe, uninstall, clear data or
  downgrade. Use the explicit plain-Application runner/class filter, never
  production-profile startup. No live LLM/SSH/frpc/broker probes.
- Record actual XML/instrumentation counts/failures and executed versus cached
  tasks, checked source/APK identities and screenshot paths. Component evidence
  is not physical-device/vendor-IME/live-provider proof.
- Independent fresh read-only Sol review of the complete candidate; parent
  consumes actual source/evidence and finding dispositions before acceptance.
  Gitleaks before push; matched-head publication only after successful gates.

## Resource, ownership and publication

One complete slice, one branch/PR/acceptance. Terra implements, Sol independently
reviews: one stable pair, **one review round**, no automatic additional round.
Parent owns scope, actual gate/finding disposition, acceptance and publication.
Total **45 minutes including preparation, implementation, validation, review
and reporting**, first recorded start `2026-11-05T08:54:09Z`, hard stop
`2026-11-05T09:39:09Z`. Runs/resume/tool changes never extend it. Save partial
state and stop at a blocker/deadline; no hidden scope or budget extensions.

Operator authorized one checked PR and acceptance/merge, not a release/version
change. On success, update the durable surface contract, mark this contract
complete for final verification, then delete this temporary feature contract.

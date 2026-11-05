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
reviews: one stable pair. Completed R1 and R2 returned BLOCK; only the
explicitly approved R3 below may follow. No automatic R4.
Parent owns scope, actual gate/finding disposition, acceptance and publication.
Total **45 minutes including preparation, implementation, validation, review
and reporting**, first recorded start `2026-11-05T08:54:09Z`, hard stop
`2026-11-05T09:39:09Z`. Runs/resume/tool changes never extend it. Save partial
state and stop at a blocker/deadline; no hidden scope or budget extensions.

After that limit expired, the operator explicitly approved **10 more minutes**
(`2026-11-05T10:22:08Z`–`2026-11-05T10:32:08Z`) only to finish the same
interrupted Sol review and, if successful, perform parent acceptance and the
single PR/merge. No production edits, test reruns, additional review round or
release are authorized. The frozen production candidate remains
`81ac3f590a75dd433df06a046d219428e999e8d7`; this amendment changes resources only.

After the completed first review returned BLOCK, the operator explicitly
approved **30 more minutes** (`2026-11-05T14:35:37Z`–`2026-11-05T15:05:37Z`)
to close the entire `ACP-LITERAL-01` preservation class with the same Terra
writer, run the affected required checks, and obtain **one second review by the
same Sol reviewer**. This remains the same whole feature, branch and final PR.
No third review, resource extension or release is authorized automatically.

The correction must protect code payload and unsupported/malformed literal
regions before removing escapes, emphasis or list syntax. Cover supported inline
code ending in backslashes; unsupported equal/unequal/repeated/escaped tick runs
with formatting/escape payloads; non-triple/mismatched fenced regions; and
multiline HTML/link/image regions, including their supported-formatting and list
contents. Preserve ordinary formatting, code whitespace, inertness, Unicode,
long answers, selection and unchanged original history. Fix the shared mechanism,
not only the review's examples; do not weaken literal preservation or remove
formatting to obtain a pass. See the complete first-review packet retained by
the parent. Existing full gates and four-configuration component validation
apply to the corrected source; earlier green results do not validate it.

After R2 was interrupted, the operator explicitly instructed the parent to let
the same reviewer work normally, without short phase cutoffs. Continue that same
retained, read-only R2 in one uninterrupted session with the normal native
30-minute review safeguard; expired earlier phase clocks do not apply to this
continuation. No production changes, test reruns, new reviewer/round, acceptance
waiver or release are authorized by this instruction.

After completed R2 returned BLOCK, the operator explicitly approved **60 more
minutes** (`2026-11-05T16:06:28Z`–`2026-11-05T17:06:28Z`) for the same Terra
writer to close the remaining `ACP-LITERAL-01` HTML/link/image boundary cases
and `ACP-CODE-02` supported-fence regression, required checks, and **one R3 by the
same Sol reviewer**, followed by conditional acceptance/PR/merge, without release.
Both receive uninterrupted working sessions, not short phase cutoffs. The writer
returns a frozen candidate without waiting for parent validation; parent checks
may run alongside read-only R3 inspection, but no positive final verdict or
acceptance may precede actual gate evidence. No automatic R4 or budget extension.

Fix each class across quoted HTML attributes, self-closing/container and multiline
variants, opaque bracket-bearing code/HTML in link and image labels, and short
tick-only payload lines in supported triple fences. Preserve earlier literal,
formatting, inertness, Unicode/long-content and unchanged-history guarantees;
new exact-output/style regressions must cover the mechanisms, not only examples.
Dependencies, broader Markdown support and unrelated changes remain out of scope.

Operator authorized one checked PR and acceptance/merge, not a release/version
change. On success, update the durable surface contract, mark this contract
complete for final verification, then delete this temporary feature contract.

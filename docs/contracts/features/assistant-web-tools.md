# Assistant public Web tools

Status: completed and verified, 2026-11-06. The operator accepted free DuckDuckGo search,
static page reading/following links, same-model image inspection and simple
per-tool switches, explicitly accepting no JavaScript execution for this slice.
Separate read-only research into JS-agent browsers is authorized, not delivery
of a browser or reader-backend selector. Base: `5f9b8176a78c9a657742100ef7c78100432b1a84`.

## Completion verification

The parent accepted the whole feature on frozen R5 after the same independent
reviewer returned **OK with notes; no issues found**. The matching gate recorded
1,548 JVM results with zero failures, errors or skips: 1,504 app tests executed;
18 core and 26 adapter results were up-to-date. Both debug APKs, Lint, documentation,
whitespace and cumulative-patch Gitleaks passed. The four-configuration synthetic
API 35 matrix passed **60/60**, once per configuration without retries.

R5 replaces the old split Tools autosave binding on both entry paths. Historical
R1–R4 failures remain recorded; their exact runtime cause is not claimed known.
The new path passed canonical-state, error, concurrency and lifetime checks.
No physical-device, vendor-IME, API 29 runtime, live-service or visual-understanding
certification is claimed. Blocking storage may commit after cancellation.

The reviewed source is HEAD `27e06d9acbfb05192af3c37d36bdc8b65247c946` plus
frozen uncommitted R5, with 713 source files. Its source-manifest SHA256 is
`7d4a3b10b351920df0a65323d1bb4de7cbb82a44699a3209a9248cc9cd42c0f0`.
Only completion metadata changes after this verification; durable behavior is in
the affected surface contracts. Archive this completed contract and remove it
from the final branch before the single feature PR. No release is authorized.

## Result and affected surfaces

One complete feature: Settings → LLM assistant → Tools lists **Web search**,
**Read page**, **Inspect image**, each with a short EN/RU description and a
persisted on/off switch. Existing Chat uses enabled tools when answering a
question or following a supplied/fetched public URL, reports progress/failures
and shows selectable fetched source titles/URLs. Follow a link through the same
reader, not another engine. No capability registry, discovery prerequisite,
model-name inference, support probes or separate vision-support switch.

Read [assistant behavior](../../agent-contract.md) and the full affected
[application/shared UI contract](../../contract.md), plus the
[meta-contract](../meta-contract.md). Their no-tools/text-only network promises
are prospectively superseded **only** for these explicitly enabled public-read
operations. All credential, owner, memory-only, provider-continuation and unrelated
connection restrictions remain. Tool changes follow the next-Send snapshot policy
below; Stop/pop, assistant disable and provider changes retain cancellation. Replace the first-tool prototype's
inapplicable full-access wording with an accurate public-read/data-disclosure
notice: these tools cannot operate connections or files. Enabling tools permits
model-selected public requests during explicit Send, not background activity.

## Settings and conversation authority

Tools start off on upgrade/new settings. Show plain switches and a concise notice:
queries go to DuckDuckGo; selected public text/images go to the configured LLM;
model token charges still apply. Save only booleans using existing settings;
missing/malformed new values default independently. No content, URLs or image
bytes enter persistent storage. Saving/enabling/opening Settings makes no request.
A failed save keeps committed authority/switch state and shows an inline error.

Prospective operator rewrite approval (2026-11-06): keep ordinary per-switch
**autosave**, consistent with the rest of Settings; do not add a Save button or
accept a known stale displayed value. Rewrite the Tools presentation/save binding
shared by the Settings and Chat entry paths. Use existing application patterns and
one clear source of committed presentation state: successful storage completion
must deliver the actual saved snapshot and the control must display it before
reporting completion/re-enabling. Do not render the requested value as saved
before commit. Pending and failed saves must be explicit; failed writes do not
pretend success. Both entry paths use the same production binding, including in
synthetic tests, not a synchronous test-only Store shortcut.

Keep the rewrite local to assistant tool settings, its ViewModel/UI/save wiring
and regression tests. Small tool-boolean operations/accessors in SettingsStore may
be adjusted if necessary; do not rewrite the global store, persistence format,
unrelated settings or create a general settings framework. Preserve disposal,
duplicate-save/callback, navigation and recreation correctness. Replacement may
retire the old independent checked-state/completion paths; validate the new
binding end to end rather than inventing a cause for historical captures.

Prospective operator simplification (2026-11-06): each Send captures the committed
enabled-tool set at admission. It may finish using that fixed set even when a
switch changes meanwhile; the next Send uses the newly committed settings.
Changing tools neither cancels the current Send nor clears completed history or
the draft. Tools remain changeable while busy. Remove tool-change epochs and
revocation machinery used only to stop an admitted Send. Unknown or unavailable
calls against that Send's captured set return bounded results without network.

Every model, page/search/image dispatch and resulting source/progress/answer/error
publication remains bound to the admitted owner, provider revision and committed
assistant-disable version. Stop/pop, assistant disable and provider changes still
cancel their exact work and retain their existing context/reset behavior. Hold the shared busy slot through callback/body/parser/coroutine cleanup,
including pop/reopen; no queued replacement or late publication. Do blocking
DNS/parsing/image/storage work off Main and outside commit guards. Reuse existing
kernel execution/exchange window, not another coordinator/framework.

## Free search and document reading

Use one DuckDuckGo HTML-search endpoint, unofficial/best-effort. No paid
Search/Fetch services, API keys, native provider-search fallback, endpoint
rotation, proxies, CAPTCHA solving or automatic retries. An unusable response,
markup drift, no result, CAPTCHA, rate limit or failure is truthful feedback;
direct page reading remains available when search is blocked.

App-lived monotonic search limits: 10 seconds between dispatches, at most
3 searches per Send and 20 per process-hour; return at most 5 results for a
query of at most 256 characters. There is no cross-Send search-results cache or
TTL/reuse promise. A repeated search is subject to the same limits and may spend
another quota slot; previous conversation history is not a content cache. No
cooldown queue; report the limit promptly.
Count attempted dispatches, including failures. A block/CAPTCHA/403/429 stops
search for the send and applies a shared minimum 15-minute cooldown, extended
by a bounded Retry-After value. No silent resume. Process death resets these
process-local limits; do not claim durable anti-abuse enforcement.

Read public HTTPS HTML/plain text/Markdown. Use pinned **jsoup 1.24.1** for HTML
parsing, not a handwritten HTML parser or WebView. If needed by its documented
Android support, add **desugar_jdk_libs 2.1.5** and core-library desugaring only;
no Kotlin/AGP/Gradle/BOM upgrade. Preserve useful heading/list/pre/code whitespace;
return title/final URL/text and bounded resolved link/image URLs with alt labels.
No JS execution, login, PDF, SVG, animation/video, interaction or recursive crawl.
Prospective operator amendment (2026-11-06): return the bounded readable static
text actually obtained, including JavaScript/login/enable-JS warnings and shell
text, as untrusted data with title/final URL. Do not classify JS dependence,
usefulness or warning-only pages, and apply no minimum text-length threshold.
The model judges the available data. Fixed extraction metadata must state that
JavaScript was not executed and static extraction may be incomplete; successful
retrieval is not proof of a full article or rendered JS. Empty/script-only/no-
readable-text results may report no content. HTTP/network/MIME/URL/byte/timeout
failures remain truthful failures, not fabricated successful article text. This
explicitly supersedes the earlier warning-only-shell rejection requirement;
prior failed reviews/gates are not retroactively accepted. Static server-rendered
content remains supported even when the original website also uses JS. Search
CAPTCHA/block/rate handling is unchanged; image replies follow the simplified
ordinary-response policy below.

Use a dedicated stateless credential-free public client: HTTPS, port 443 only,
no userinfo/local schemes, cookies, authenticator, client certificates, provider
headers, WebView state, persistent cache or logging of query/URL/body/key.
Reject private, loopback, link-local, multicast, unspecified and special-use
IPv4/IPv6/mapped destinations, including local/tunnel/app targets. Validate actual
DNS connection addresses and every manually followed redirect without a
resolution/rebinding gap. Reject unsafe resolution rather than bypassing policy.
No provider credential is ever sent to fetched pages. Page/search/image content
is untrusted data, never tool authority or instructions; prompt warnings do not
replace dispatch/URL guards. Public GET is not guaranteed free of remote effects.

Limits per Send: 12 public HTTP requests including redirect hops, 3 redirects
per fetch, 1 MiB decoded page/search response, 8,000 returned text characters,
100 link and 20 image references per page. Enforce byte caps while streaming,
including decompression, before HTML parsing. Mark clipped results. Whole Send
has a 120-second deadline including model/tool/image work; existing per-call
kernel/transport limits still apply. Bound anti-abuse state and source metadata
as well as text.

## Optimistic image inspection

`inspect_image` fetches only an explicitly selected public JPEG/PNG under the same
URL/authority/budget policy, then makes one bounded non-streaming image-analysis
call to the **same configured provider root/model**, with no tools. Reuse the
prepared provider transport and root-bound credential; tool code receives no key.
A narrow user-role text + inline image operation returning text avoids changing
core message/tool protocols or retaining pixels in conversation history.

No capability check or test request. Return a bounded ordinary model response to
the image request, with neutral labels: this is unverified model text, not proof
that an image was seen or understood. An answer such as "I cannot process images"
is valid returned data for the main dialogue. Do not require an observed/unavailable
JSON envelope, exact response fields, or a refusal-language classifier. A usable
textual refusal, including a standard structured refusal carrying text, is not a
silent failure. This prospectively supersedes the image self-report requirement.

Technical HTTP/timeout/invalid or empty API response/oversize failures remain
explicit sanitized tool failures. The normal text loop may continue and explain
the limitation. Never present alt text as visual inspection, remove an image and
claim success, replay the turn, retry automatically or change model/provider.

At most 2 image attempts per Send; download cap 1 MiB/image, 4 megapixels, question
at most 1,000 characters, 512 output tokens and 4,000 returned text characters.
Validate actual MIME/image dimensions before decoding; bound and reject an
encoded request exceeding the existing provider byte cap, including base64/JSON.
Release transient image/base64/bitmap resources on every exit; retain only bounded
response/provenance text. Additional same-model token charges are disclosed.

Tool failures must give a short understandable description of what failed, with a
known status or limit when available, rather than an empty result or opaque enum.
Keep current-attempt failure feedback visible and let the model explain it. Never
expose raw server bodies, exception messages/stacks, credentials or private data.

## History, feedback and non-goals

Keep the existing 32 successful-turn limit and add a 512,000 UTF-16 aggregate
history/source-metadata bound, counting tool arguments/results and opaque state.
Evict oldest complete successful turns visibly. Never retain half a tool exchange
or silently truncate current input/answer; fail context-full if required current
content cannot fit. Show omissions of current-turn tool exchanges too. Source
metadata follows retained/current turns; no unbounded page registry or pixels.
Display verified fetched provenance separately from arbitrary model Markdown;
citations are evidence of retrieval, not proof of generated claims. Keep answer
Markdown links/images inert; no automatic external opening/loading.

No connection/SSH/MQTT/Files authority, local browsing, uploads, persistent chat,
streaming, provider fallback, new permission/plugin framework, RAG/vector database,
reader selector, JS browser or general-purpose parser/test platform. The parallel
JS research cannot change this contract or delay acceptance for optional work.

## Validation, execution and publication

The mandatory read-only scout completed before coding; its actual minimal plan
is reviewed by the parent and handed to the writer. One stable writer and one
fresh independent reviewer inspect the complete cumulative diff, full affected
files and connected owners from the start. Cohesion over line counts.

Initial R1/R2 and the separately approved R3 finish ended with BLOCK and remain
failed history, not interrupted reviews or retrospectively accepted candidates.
After a read-only simplicity report, the operator explicitly approved this
smaller policy package: next-Send tool snapshots, no shared search-results cache,
ordinary model image responses and understandable technical errors. Keep live
sources, ready parsers and the existing safety limits; no general cleanup.

That R4 allowance is exhausted: the approved simplifications passed final
1538-result JVM and 56-case UI gates, but review remained BLOCK on earlier
saved-versus-displayed switch captures without a demonstrated UI correction.
Both positive and negative evidence remain history; no retrospective PASS.

The operator then explicitly rejected replacing autosave with a Save button and
requested a well-written replacement of the tool-settings binding. This request
is bounded to **one read-only scout delta consumed by the parent, one coherent
implementation pass, necessary focused checks, one matching full gate/matrix and
one final same-reviewer round (R5)**. Keep the same writer/reviewer pair and the
existing next-Send/cache/image/error policies. No automatic correction/R6, blind
retry, general settings rewrite, new platform investigation or repetition of
unchanged passing checks. A remaining blocker requires another operator decision.
Native safeguards remain normal uninterrupted windows, not a shared countdown or
hour estimate. Stop on infrastructure failure, new scope or exhausted allowance;
preserve all evidence. Completed JS research is not another coder or delivery gate.

Validate production seams with synthetic providers/DNS/HTTP and existing test
fixtures: DDG fixtures/block/quota without a shared content cache; safe DNS,
redirect/IP and no credential leaks; streaming/MIME/HTML/image bounds; same-model
image text/refusal and explicit technical errors; current-Send snapshots and
next-Send settings, failed writes/off-on, Stop/pop/disable/provider revocation
and full cleanup; actual saved/rendered switches, complete history/source eviction
and no persistent content. Do not weaken production public-only rules for loopback tests.
Run focused tests, full JVM suites, both debug APKs and Lint, documentation checker,
whitespace and candidate-range Gitleaks. Reuse only emulator-5580/plain synthetic
Application/explicit chat class; representative EN/RU/light/dark/portrait/landscape
and 200% font, restore 1.0. Verify jsoup API29 compatibility without new emulator
or production startup; distinguish static API evidence from runtime certification.
No live paid model calls or production profile/key probes. Live DDG requests are
not required acceptance evidence; synthetic green is not a live availability claim.

One feature branch, one final PR and whole-feature parent acceptance. Before
publication, update durable surface contracts, complete/check/snapshot/delete
only this temporary contract, final Gitleaks and matched-head/equal-tree merge.
No version, tag, release, CI/signing change. Parallel JS research has no code or
publication authority and makes no next-feature implementation commitment.

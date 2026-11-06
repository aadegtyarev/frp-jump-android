# Assistant public Web tools

Status: approved scope, 2026-11-06. The operator accepted free DuckDuckGo search,
static page reading/following links, same-model image inspection and simple
per-tool switches, explicitly accepting no JavaScript execution for this slice.
Separate read-only research into JS-agent browsers is authorized, not delivery
of a browser or reader-backend selector. Base: `5f9b8176a78c9a657742100ef7c78100432b1a84`.

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
operations. All credential, owner, cancellation, memory-only, provider-continuation
and unrelated connection restrictions remain. Replace the first-tool prototype's
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

Advertise only enabled tools; independently enforce current authorization before
actual execution. Commit tool configuration with a monotonic process-local
invalidation version under the existing shared admission/publication gate.
Rapid off/on cannot revive old work. A committed tool change stops an active
send; retain already completed chat text/history, not erase it merely for toggling.
New sends use current tools. Unknown/disabled calls produce explicit bounded
results without network. Changed provider revision or assistant disable keeps
existing stronger context reset behavior. Tools remain changeable while busy.

Every model, page/search/image dispatch and resulting source/progress/answer/error
publication is bound to the admitted owner, provider revision, disable version
and tool version. Stop, pop, revoke and provider changes cancel only their exact
work. Hold the shared busy slot through callback/body/parser/coroutine cleanup,
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
query of at most 256 characters. Deduplicate normalized identical queries with
bounded 60-second in-memory reuse. No cooldown queue; report the limit promptly.
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
A client-only JS shell is not a successfully read full article; say when useful
content could not be obtained. Static server-rendered content is supported even
when the original website also uses JS.

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
kernel/transport limits still apply. Bound caches/source metadata as well as text.

## Optimistic image inspection

`inspect_image` fetches only an explicitly selected public JPEG/PNG under the same
URL/authority/budget policy, then makes one bounded non-streaming image-analysis
call to the **same configured provider root/model**, with no tools. Reuse the
prepared provider transport and root-bound credential; tool code receives no key.
A narrow user-role text + inline image operation returning text avoids changing
core message/tool protocols or retaining pixels in conversation history.

No capability check or test request. Rejection, timeout, malformed response,
oversize or other image failure becomes a sanitized explicit tool failure. The
normal text loop may continue with available text and disclose the limitation.
Never claim alt text is visual inspection, remove an image and report success,
replay the turn, retry automatically or choose another model/provider.

At most 2 inspections per Send; download cap 1 MiB/image, 4 megapixels, question
at most 1,000 characters, 512 output tokens and 4,000 observation characters.
Validate actual MIME/image dimensions before decoding; bound and reject an
encoded request exceeding the existing provider byte cap, including base64/JSON.
Release transient image/base64/bitmap resources on every exit; retain only bounded
observation/provenance text. Additional same-model token charges are disclosed.

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

Overall work allowance is one implementation pass plus at most one consolidated
correction pass, at most two independent review rounds, focused regression tests
and one matching mandatory full gate per frozen candidate. No automatic extra
review/fix campaign or repeated passing checks. Native run safeguards are normal
uninterrupted windows, not short phase countdowns. On infrastructure failure,
new scope decision or exhausted allowance, preserve evidence and stop for the
operator. Parallel research is one bounded read-only report, not another coder.

Validate production seams with synthetic providers/DNS/HTTP and existing test
fixtures: DDG fixtures/block/quota/dedup; safe DNS/redirect/IP and no credential
leaks; streaming/MIME/HTML/image bounds; same-model image encoding and truthful
failure continuation; committed-switch/revoke races, failed writes/off-on,
Stop/pop/reopen and full cleanup; complete history/source eviction and no
persistent content. Do not weaken production public-only rules for loopback tests.
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

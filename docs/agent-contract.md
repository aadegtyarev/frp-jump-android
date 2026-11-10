# Optional connection assistant

## Boundary and availability

This surface covers the optional assistant kernel, external API adapter and
provider setup and a locally saved chat with optional public-read, profile-information and SSH-command tools. The owner uses Settings to opt in,
configure one provider and open Chat; maintainers can supply provider/tool
implementations to the kernel. Tunnel-control tools remain unavailable. The enabled SSH-command exception and read tools are described below.

Assistant UI inherits the [shared UI rules](contract.md#ui-rules): concise,
understandable and functional screens, minimal necessary steps, direct common
actions and short labels/messages. Keep accessibility, waiting/error feedback
and safety warnings; visual minimalism must not remove them.

<a id="current-stage-kernel-external-adapter-and-provider-setup"></a>

### Settings flow

Settings → LLM assistant enables or disables the experimental module. It is
**off by default**, including upgrades from versions without this setting.
The preference is committed before the switch reports success. A failed
write retains the previous preference and shows an error. A malformed new
flag falls back to off without resetting unrelated settings.

Settings → LLM assistant → Assistant provider configures one external
OpenAI-compatible provider. Chat is available next to provider setup. SSH commands require their own default-off tool switch; tunnel control is **not available**. Enabling the module, opening settings or
saving a provider performs no network request. Fetch models is an explicit
user action and is available during setup even while the agent module is off.
A saved provider is configuration, not evidence of a successful API call.
See [provider settings and credentials](#provider-settings-and-credentials) for
Save, Fetch models and recovery behavior.

## Separation

- `agent-core` is a plain Kotlin/JVM module: provider-neutral messages,
  suspend provider/tool interfaces, one execution loop, bounded window and
  results, cancellation and observable events. It has no Android, HTTP,
  SSH, connection-discovery or credential dependency. It uses Kotlin
  coroutines/JSON already used by the app, not an agent framework.
- The app owns the opt-in setting and module lifetime. Every successfully
  committed disable advances an in-memory invalidation version, even if a
  conflated settings observer sees only the later enabled value. Admission and
  run ownership check that authority directly; the observer cancels stale child
  jobs outside the settings monitor. Failed writes do not invalidate work.
  Enabling again cannot revive an old run. This version is process-local: no
  execution survives process restart and no persistent journal is needed.
- `agent-openai` implements non-streaming OpenAI-compatible Chat Completions
  and model discovery. It is not constructed or called by enabling the module.
- `AssistantProviderStore` owns the app-private atomic provider document and
  Keystore-backed credential; its observable snapshot contains metadata only.
- The kernel does not itself grant target access. Any connection-tool adapter
  must bind to its admitted connection identity and must never resolve a
  replacement client after a disconnect. The SSH adapter below owns one ordinary client; the information tool reads shared application state. Neither starts or controls tunnels.
- The versioned [application skill](../app/src/main/assets/assistant/skill.md)
  is input for a coordinator, not a permission system or an automatically
  executing script. Text chat does not attach it or any automatic app context.

## Minimal execution

One run at a time. A new request while one is running returns busy rather
than waiting and silently running later. A stopped/noncooperative run keeps
that slot until it exits. Runs own child jobs; stopping the assistant must
not cancel unrelated application work.

Each step is one provider request followed by the proposed tools, in order.
Tool calls and their results form one indivisible exchange. Duplicate call
IDs within a run, incomplete exchanges, excessive replies and empty final
responses are rejected. Unknown tools and tool failures are explicit results.
Raw exception messages are not given to the model. There are no automatic
provider/tool retries.

Execution defaults are finite, validated kernel parameters, not settings UI:

| Limit | Default |
| --- | --- |
| Model steps per run | 12 |
| Tool calls per step | 4 |
| Model request timeout | 60 seconds |
| Tool timeout | 30 seconds |

Disabled and busy admission return without a provider/tool call. A run finishes
with complete, context-full, step-limit, model-timeout, provider-failed or
invalid-reply status; cancellation propagates to its caller. Tool outcomes are
success, failed, timeout or unknown-tool, distinct from the run's finish status.

Timeouts and cancellation are cooperative: adapters must support cancellation
of their own I/O. An already performed remote change is not rolled back by
Stop. A timed-out tool may have an uncertain remote outcome and must not be
blindly repeated.

## Sliding window, not compression

The kernel builds an active request while retaining the caller's history
unchanged. Instructions, tool schemas, the current user request and output
reserve count toward the budget. Only a suffix of complete earlier turns is
included. When necessary, whole older exchanges of the current turn are also
left out, never half of a tool-call/result pair. Current user/instructions and
the latest complete exchange are not silently discarded. If those cannot fit,
the run returns context-full without another model call.

Defaults: a 16,384-token estimated total budget with 2,048 tokens reserved for
output. The default counter uses UTF-8 byte lengths plus framing allowances:
**it is an estimate, not a provider tokenizer or a guarantee that every API
accepts the request**. A provider-specific counter can replace it. Window
omissions are observable kernel events, not fabricated summaries. Callers may
prepare a request before window selection under the same cancellation and model
timeout; fixed instructions, enabled schemas, output reserve and current user
remain unchanged. Chat uses this point for the working summaries described below,
rather than silently evicting unsummarized context. Other callers retain the
ordinary sliding window. Chat still indicates any pre-existing omissions.

Tool result text is capped at 8,000 UTF-16 characters by default, preserving
head/tail with a visible omission marker and an omitted-character count.
Cuts never split a valid surrogate pair. Model reply text/call arguments are
limited to 32,000 aggregate characters per response. A returned String is
already allocated: these are kernel-result bounds, not a total-heap or
transport-buffer guarantee. Stream-reading adapters must cap capture as data
arrives. The kernel does not own full UI history/storage or an unbounded
background store; text chat owns its bounded encrypted history separately.

## Text chat

The conversation is a quiet, left-aligned text dialogue on one background, not
message bubbles or cards. Localized “You” and “Assistant” labels distinguish
roles as well as the theme's accent for user text and normal ink for answers;
pending user text uses the same presentation. Text remains selectable. Answers
have display-only basic bold, italic, unordered/ordered lists and monospaced
inline/fenced code through a Compose Markdown component. The library owns
Markdown parsing, including malformed or nested input. HTML, links and images
are shown as inert text and have no execution, opening or loading behavior.
If parsing fails, the original answer remains selectable. User text is literal.
Formatting changes neither the stored turns nor provider requests or
continuation, and never truncates an answer. Display is not a source-Markdown
round trip or an HTML implementation.

Settings → LLM assistant → Chat opens a normal back-stack screen. Opening it
restores local encrypted history off Main, without unsealing provider credentials
or contacting the provider. Disabled, missing-model
and unreadable setup explain the problem and link directly to Settings or
provider setup. Recovery re-reads provider metadata without unsealing a key.

Send explicitly owns one pending operation, including off-Main credential
preparation and the HTTP request. Follow-ups reuse the same provider instance
and complete historical protocol exchanges. Interrupted attempts retain received
results and an explicit unknown-outcome note, never executable pending calls.
Stop cancels that operation, not unrelated
app work; a noncooperative preparation/request keeps the slot and shows
Stopping until it exits. An app-lived, text-free admission token spans preparation,
HTTP callback and response-body cleanup, post-kernel publication and actual job
cleanup, even after pop/reopen; a new screen may restore saved text but cannot
start a replacement while that slot is occupied. Cancellation closes the exact call promptly, but the slot
is held until both its callback execution and the coroutine have exited. No replacement send is queued. Failed/stopped input
stays editable for explicit retry. Setup/read/key, timeout, context-full,
invalid-answer and provider failures use fixed messages, never raw diagnostics.
There is no automatic retry, streaming or provider/model fallback.

The back-stack ViewModel retains the conversation across configuration changes.
The app owns one canonical saved conversation: messages, observed protocol
exchanges, turn outcomes, sources and draft. UI text, tool-detail panels and model
requests are derived from those records, not separately persisted histories.
It seals one atomic `noBackupFilesDir/assistant_chat.json` document using the
existing AES-256-GCM Keystore box, alias `frpjump-assistant-chat-v1`. There is no
cloud synchronization, Android backup, conversation Bundle or plaintext log.
Encoding, crypto and file IO run off Main, outside the authority guard. Messages,
received replies/results, outcomes and route exit trigger saving; editor keystrokes
do not rewrite the conversation. Retry state contains data only, not a live API
provider. Saves coalesce; accepted local saves may finish after route exit. Process restart
restores the last committed snapshot, not an unfinished write or running action.

Read/decryption failure is not an empty conversation and blocks overwriting it;
Retry reads again. Write failure keeps the latest accepted snapshot in memory,
including across route reopening, with an unsaved warning until Retry succeeds.
That memory cannot survive process death. Chat's menu clears history through
shared Undo; editing/sending waits while clearing/restoring. Undo restores the
same data without a provider call. A committed clear outlives the original route;
clear failure retains known data and offers retry. Only one load is admitted at a
time, preventing duplicate reloads from replacing newer editor/model state.

The active operation is bound to the provider revision and committed-disable
version. A saved revision change or committed disable (including rapid off/on)
cancels old work; enabling cannot revive it. Accepted saved history is retained
when disabled. Execution revision is not conversation compatibility: changing
context/output limits preserves the dialog and model history. Changing API root
or model keeps old messages readable and starts a new active context, with an
explicit notice; an index in the same document separates old records from new
model input. No deletion is required and old opaque state is never retargeted.
Failed settings writes retain prior authority. Admission and publication
recheck current authority, including after the kernel returns. Successful settings
and provider snapshot commits serialize with admission, nonblocking provider-call
start and every guarded answer/draft/error/window publication on a shared guard.
Store IO/credential work happens outside that guard; state notifications and
cancellation run outside store commit monitors. An old completion
cannot publish an answer/error or release another owner's pending slot.

The saved conversation keeps original records until explicit clearing, not just
the model's request window. It admits at most 1,000 records and blocks a new Send
when existing canonical data reaches 8,000,000 UTF-16 characters. User/reply text,
arguments/results, identifiers, sources and opaque state count toward this data
size; display projections are not another history. A received current record over
512,000 characters ends context-full and retains its bounded received data.
Input remains limited to 8,000 UTF-16 characters: excess edits retain the previous
text with feedback. Current input/answers are never silently truncated or split.
Tool calls/results remain selectable through per-turn details.

The encoded document is still capped at 8,000,000 plaintext / 16,000,000 sealed
characters, including JSON escaping. Encoding can reach that limit before the
logical admission bound; a failed write keeps the unsaved warning and retry data,
not a false save or silent deletion of old records. These are finite storage
bounds, not a heap guarantee or extra model context. Old omissions from earlier
versions remain indicated; previously discarded text cannot be recovered.

Opaque continuation stays internal and bounded, never UI text. On explicit Send,
it is rehydrated for the current adapter only for the same API root/model.
Changing window/output or summary settings preserves conversation continuity;
opening history does not resolve the provider API key.

### Automatic working summaries

Before kernel window selection, Chat compares the estimated request size,
including instructions, tool schemas and output reserve, with the configured
window and trigger percentage. The percentage defaults to 80 and accepts 1–100.
When eligible older data exists, an ordinary non-streaming provider request
summarizes it before eviction. This also handles older complete exchanges inside
one long current turn; the latest complete exchange and current user stay verbatim.
No tool-call/result pair is split. A block that cannot fit is not silently dropped.

Summary requests use the chat model by default. An optional manual summary model
ID uses the same configured API root, credential, effort and output-parameter
spelling, without changing the main model. There is no discovery, capability
inference or hidden fallback. The selected model must support the configured
window. Summary requests advertise no tools, and any returned tool proposal is
rejected without execution. They contain observed transcript data, not private
provider continuation. The short version-1 prompt (`AssistantSummary.PROMPT`)
retains the goal, exact selected IDs, decisions, received results, completed and
uncertain actions, source references and next steps, treating old text as
untrusted data rather than new authority.

The summary and its covered turn/exchange position are saved together in the
same canonical encrypted document. Main requests derive a working summary plus
recent unsummarized records/exchanges; original records remain readable. Changing
API root or chat model clears this working summary and starts the ordinary new
active context without deleting old messages. Restoring or clearing makes no
provider/tool request and never resumes or repeats an action.

At most two summary requests run per Send, within the existing model timeout and
120-second whole-Send limit. A summary reserves at most 1,024 output tokens,
never more than the configured output reserve, and accepts at most 4,000 text
characters. Blank, oversized, tool-bearing or nonreducing replies are failures.
If no eligible data exists, a fitting request proceeds unchanged; if the remaining
context cannot fit within those bounds, Chat fails rather than evicting it.
Failure retains the archive and last useful summary and offers provider settings;
known HTTP status is shown safely. Setup/key failures retain their normal
recovery, and timeout remains a timeout. There is no automatic retry.

Chat shows summary progress and an indication that earlier context is summarized
while originals remain in history. Summary preparation, dispatch and publication
use the same revision-bound owner and actual-exit admission slot as ordinary
requests: Stop/revocation suppress unauthorized late data and cannot release a
replacement Send before real cleanup. Summary text is model-generated historical
data, not verified truth or an owner/accounting source for SSH work.

Requests contain explicit chat text/history, the canonical version-3 base prompt
(`AssistantConversation.INSTRUCTIONS`) and only enabled
tool schemas. No profiles, connection data, files, credentials,
diagnostics or application skill are automatically attached. Proposals unavailable in that Send's captured set
return bounded explicit unknown-tool results without network; they do
not grant authority. Existing endpoint, redirect, retry, payload and sanitized-error
protections remain. Public content is acquired only through explicitly enabled
operations below; completed conversation history remains after tools change.

## Optional profile information

The independent default-off `profile_information` tool accepts optional `query`
(text up to 256 UTF-16 characters), exact `profile_id` (up to 256 characters) and
nonnegative integer `offset`. Empty arguments browse the first page. Only an
actual enabled invocation transfers information; opening Chat/Tools, enabling
or saving does not invoke it or automatically attach an inventory to a prompt.
Each invocation performs one ordinary all-profile device-list pull, the same
operation as Connections refresh, then reads the application's known state.
Normal authenticated server requests, feature checks and cache writes still
apply. Each information-owned request's actual nonblocking enqueue is serialized
with the existing Chat authority guard; credential/storage work, body reading and
cleanup remain outside it. Cancellation signals only those owned calls and keeps
the shared Chat slot until their callback/body and full HTTP worker execution
have exited. Ordinary UI/background requests retain their existing path and
lifetime. It starts no extra tunnel poll, probe, connection or control operation.

Devices, Connections and the tool consume the same derived application API over
existing profile/device/connection/tunnel/Undo owners. Complete device lists are
separate from connection rows, including devices without saved connections.
Profile IDs distinguish repeated device names. UI selection, grouping/order,
authentication decoration, actions, visibility and polling lifetimes stay with
the existing screens/runtime; the API introduces no inventory cache or mirror.

Selection searches the complete available source before paging. It reuses the
Devices/Connections NFC/Locale.ROOT substring comparison over device names/labels,
connection names and profile names; exact profile/connection IDs also match.
A matching profile includes its records, a matching device includes its connection
variants. Several suitable results are legitimate: active tool guidance requests
a concise answer distinguishing profiles/devices/connections, not a silent first
choice or an unnecessary clarification. Exact saved action IDs remain available.

Results are whole-record pages under the default 8,000-character kernel cap.
The envelope contains `query`, `profile_id`, `offset`, `total_records`,
`next_offset`, `source_status` and `results`. Records have a `record_type` of
`profile`, `device`, `connection` or `tunnel_operation` and repeated safe profile
context. Counts refer to records, not unique devices. Continue with the same
selection and `next_offset`; null means no further known records. Offsets describe
live data, not a cached/atomic snapshot: catalogue changes may require restarting
at zero. Unchanged data can be traversed without lost/repeated records.
Unknown, unavailable, stale and unreachable source counts and completeness are
explicit, including when no known record matches. This is not a global absence
claim when sources are incomplete. Display fields normally retain up to 256
UTF-16 characters, with `shortened_fields` and surrogate-safe cuts; an unusually
large first record may shorten them further to fit. Saved action IDs are never
shortened. Unrepresentable identifiers produce an explicit unavailable record,
not invented IDs or an endlessly non-progressing page. Smaller custom kernel
caps can still apply their ordinary clipping indication.

Records include profile availability, device list age/stale/checking states,
saved and unnamed connection rows, local tunnel addresses and existing typed
pending/waiting/tunnel states. Profile summaries count pending/errors; individual
`tunnel_operation` records preserve keys, pending enums and sanitized errors even
without a connection row. Missing sources remain unknown, not empty success.
An ordinary refresh failure retains the existing app-known stale/error state;
reading it successfully does not claim a successful refresh, atomic live
inventory or remote service readiness. Error fields convey fixed descriptions
by source field (profile loading, device refresh, connection operation, tunnel
synchronization or protocol mismatch), not raw UI/server/exception/frpc strings.
Typed facts such as relay after direct failure, waiting and device-missing remain
separate; diagnostic text alone is not classified as a failure. Credentials,
keys, tokens, certificates and runtime/store objects are never serialized.

The tool uses the existing captured-per-Send set, progress/failure feedback,
result/context/history bounds, omission, cancellation, slot and module ownership
rules. Chat retains the same bounded received result with an off-Main parsed
presentation charged separately to the aggregate history budget. A selectable
localized facts view is available on demand with completed turns and opens
automatically for an incomplete attempt's provider/context failure. Received
facts therefore do not depend on another model explanation. They clear with
the next current attempt, their whole-turn eviction, revocation or close; late
unauthorized output is still withheld. Information retained in successful chat
exchanges follows the same bounded encrypted-history lifecycle; a later tool-switch change
does not erase that history.
This explicit informational exception does not authorize connection control,
SSH, device HTTP, Files or MQTT tools. It has an accurate private-data disclosure,
not a full-access claim. Other future connection-tool prototypes still require
separate approval and the full-access warning described below.

## Optional SSH command

The independent default-off `ssh_command` tool accepts exactly `profile_id`,
`connection_id` and one nonempty `command` string. Discover saved IDs through
`profile_information`; there is no additional catalogue or automatic attachment.
One unambiguous saved SSH connection in any profile must already have a ready
visible tunnel. Missing, non-SSH, hidden, removed, down or unavailable targets
fail without starting/reviving a tunnel. No open terminal is required.

Each invocation captures the saved connection, runtime, tunnel configuration and
one operation-owned ordinary SSHJ client. It never substitutes a Terminal/Files
client or re-resolves a replacement after target loss. Operation-local observers
stop owned work on target removal, pending Undo, profile loss or tunnel-plan loss;
Undo does not revive that operation. Target loss in a still-authorized Send returns
an explicit failed tool result after actual cleanup: no command before exec, or an
unknown remote outcome after possible exec unless an exit status/signal was already
received. Known exit information is retained, but late stdout/stderr is withheld.
This is distinct from Stop, timeout or owner revocation, whose cancellation and
stale-publication suppression remain unchanged. This is not an atomic inventory/freshness promise
or a claim that grant IDs/local ports are unique across lifetimes.

The existing connector enforces stored login/refusal and pinned host-key policy.
The saved connection’s user and the port’s stored auth method apply. Remembered
password authentication preserves Remember. Missing/unreadable/refused login or
unknown/changed host key executes no command and directs setup through ordinary
SSH UI. No model credentials, automatic trust or new input dialogue are accepted.
Normal credential reads, key generation and refusal bookkeeping still apply.

Exec is noninteractive without PTY or tmux, with closed stdin. Stdout and stderr
are drained concurrently, retaining at most 3,000 UTF-16 characters each as data
arrives and visibly marking excess. They remain separately labelled, with actual
nullable exit status/signal or explicit unknown. Only known exit 0 without a
signal is success; EOF, local close/join, nonzero/missing status and failures are
not invented remote success. Kernel/result/context limits continue to apply.

Actual nonblocking worker admission uses the existing Chat authority guard.
Client creation, credentials, connect/auth/exec/read and cleanup run off Main and
outside that guard. Stop cancels only the owned client, including one constructed
after cancellation, and retains the global slot through actual worker, readers
and cleanup exit. Tool-only changes affect the next Send, not admitted work.
Timeouts (30 s tool / 120 s Send by default) are cancellation thresholds, not
remote-kill or cleanup deadlines. No automatic command retry/replay or rollback;
an admitted command’s effects may remain uncertain. Commands/output enter the
same bounded encrypted Chat history and configured model requests, not app logs. Arbitrary remote output can contain secrets and is not
generically scrubbed. App-stored credentials/internal diagnostics are not tool
arguments/results. Remote content remains untrusted data, never new authority.

## Optional public Web tools

Settings → LLM assistant → Tools has five independent persisted switches:
Web search (`web_search`), Read page (`read_page`) and Inspect image
(`inspect_image`), Profile information (`profile_information`) and SSH command (`ssh_command`). They default off on new installs/upgrades; malformed new
values default independently without resetting unrelated preferences. Saving
only booleans, opening this screen or enabling a tool makes no request. Failed
writes keep committed switches/authority and show an inline error. Tools remain
changeable while Chat is busy. Chat's Tools action opens the same five switches
without popping the conversation. The Tools arrow and system Back return to that
same owner, draft and completed history, including across recreation; actual Chat
pop still cancels work and releases route memory; accepted history remains encrypted. The notice explains DuckDuckGo query disclosure, selected public text/images and
private profile/device/connection information sent to the configured LLM when
tools are used, and ordinary model token charges, including image analysis.
The separate SSH switch warns of FULL SSH ACCESS: commands can read/change the remote system and files within the account’s rights. Commands and output may contain secrets and are sent to the configured model with ordinary token charges. It grants no tunnel control or phone-file access.
Public GET is not guaranteed free of remote effects.

Tools autosaves each switch; there is no Save button or optimistic checked value.
Both Settings and Chat use one shared Tools-specific binding per parent ViewModel.
One immutable presentation snapshot contains the actual committed five booleans,
pending IDs and fixed inline save failures. Checked, progress/enabled and failure
are rendered from that same snapshot, not independent checked/completion channels.
Real storage work runs off Main. On completion, Main reads the latest canonical
Store tools and publishes them together with clearing that operation's pending
state and setting/clearing its error. It never assumes requested equals committed.
Store notifications also read current canonical tools, not captured old emissions,
while preserving pending/errors. External/concurrent writes cannot be rolled back
by a delayed acknowledgement. Same-ID pending actions are ignored without a queue;
different IDs may save independently, preserving unrelated settings.

Back hides Tools without cancelling the parent-owned save; reopen and recreation
retain that binding and pending/error state. Actual parent pop/clear cancels child
operations and prevents old UI publication. Already executing blocking storage may
still commit: cancellation does not promise rollback. A new owner reads actual Store
state and notifications. There are no save completion/navigation callbacks or
persistent copies of presentation state. Construction/opening does no storage IO,
credential unseal or network operation. Current-Send admission still reads Store
authority, never presentation; pending UI acknowledgement is not network authority.

Each Send captures the committed tool booleans atomically at admission and keeps
that fixed set throughout the run, including calls dispatched after a switch
changes. Changes apply only to the next Send; the current Send may finish with
its original tools. They neither cancel work nor clear history, provider or draft.
There is no tool-change epoch/revocation signal. Failed saves retain prior settings.
Admission, every model/public/image dispatch and source/progress/answer/error
publication still check the captured owner, provider revision and disable version.
Stop/pop, assistant disable (including off/on), provider revision/read failures
cancel exact work; the shared slot remains occupied through DNS, callback/body,
parser, owned SSH worker/readers and coroutine cleanup. No replacement is queued. Blocking DNS, parsing,
image preparation and settings IO run off Main and outside commit guards. The
whole Send deadline is 120 seconds; existing kernel per-call limits also apply.

### Search and static documents

Search uses one unofficial, free, best-effort DuckDuckGo HTML endpoint. No API
key/account, paid Search/Fetch, provider-search fallback, endpoint rotation,
proxy, CAPTCHA bypass or automatic retries. Block, markup drift, empty results,
rate limit or failure is explicit feedback, never fabricated success. Direct
reading remains available when search is blocked. Process-local monotonic limits:
10 seconds between attempted dispatches, 3 searches per Send and 20 per hour,
query at most 256 characters, at most 5 results. There is no shared successful-
search payload cache, TTL or cross-Send reuse. Repeats are ordinary attempts under
the same quotas; completed history is retained, not used as a content cache. Both
attempt admission before DNS and actual HTTP spacing after DNS are enforced.
Already charged attempts and mandatory block cooldowns are not rolled back by
Stop/pop or real revocation. There is no cooldown queue. Attempts include failures;
403/429/CAPTCHA blocks search for that
Send and applies a shared minimum 15-minute cooldown, extended by Retry-After
bounded to 24 hours. No silent resume. Process death resets these limits; this
is not durable anti-abuse enforcement. Search targets are links, not evidence
that those target pages were fetched.

Read page accepts public HTTPS HTML, plain text and Markdown. HTML uses pinned
jsoup 1.24.1 with desugar_jdk_libs 2.1.5/core-library desugaring for Android 29.
Useful headings/lists/pre/code whitespace, title/final URL and resolved links/
image URLs with labels are returned. At most 8,000 text characters, 100 links and
20 image references per page; clipped text/results are marked. Under the explicit
2026-11-06 operator amendment, return whatever readable static text was obtained,
including warning-only JavaScript/login/enable-JS and shell text. The model judges
usefulness; the reader performs no JS/usefulness classification, phrase detection
or minimum-length filtering. Fixed metadata says JavaScript was not executed and
static extraction may be incomplete. Retrieval of static data is not proof of a
full article or rendered JavaScript. Empty/script-only/no-readable-text results
may report no content; HTTP/network/MIME/URL/byte/timeout failures remain failures.
This prospectively replaces the earlier warning-only-shell rejection promise,
without changing previous failed acceptance history or search anti-abuse requirements. Static server-rendered content remains supported.
No JavaScript, login, PDF, SVG, animation/video, interaction or recursive crawl.
Following a selected link uses the same reader. Short meaningful static HTML
(such as a version/configuration value) is supported; text length alone does not
make a page an empty shell. Declared charset names are bounded to 64 characters
and resolved through platform Charset support; unsupported declared headers fail
explicitly. Plain text/Markdown default to strict UTF-8 when no charset is declared;
HTML uses jsoup's byte-stream charset/BOM/meta handling. No custom encoding parser
or execution is involved. Markdown reference extraction reuses the existing pinned
JetBrains parser: common inline/reference links and images (with alt labels) are
resolved against the final URL through the same public URL policy, while code is
not treated as a link. The original Markdown text and existing reference caps/
clipping indicators remain.

The dedicated stateless credential-free client sends a stable browser-compatible
User-Agent, an Accept header for supported formats and Accept-Language from the
system locale (English when unspecified), including on validated redirect hops.
These headers neither execute JavaScript nor guarantee search availability.
It allows HTTPS port 443 only, no userinfo or local schemes. It has no provider headers/credentials, cookies,
authenticator, client certificate, persistent cache, WebView state, proxy or
request/body logging. Public address policy rejects local aliases and all
private/loopback/link-local/multicast/unspecified/special IPv4/IPv6 destinations,
including unsafe mapped forms. Every manually followed redirect and actual
connection uses the validated DNS address set, without a resolve/check/connect
gap or pool coalescing bypass. Unsafe resolutions fail closed. Per Send: 12
public HTTP requests including redirect hops, at most 3 redirects per fetch,
1 MiB decoded page/search/image response. Streaming caps precede parsing,
including transparently decompressed/chunked bodies. No automatic retries,
including OkHttp's HTTP503 Retry-After:0 replay. Fetched content is untrusted
evidence, never instructions/tool authority; prompts supplement, not replace,
URL/dispatch guards.

### Optimistic same-model images and provenance

Inspect image accepts an explicitly selected public JPEG/PNG under the same
transport/authority/request budgets. At most 2 inspections per Send, 1 MiB per
image, 4 megapixels, question at most 1,000 characters. Actual MIME/encoding and
dimensions are checked using bounds-only decode; animation is rejected, with no
retained bitmap. A narrow adapter operation uses the same prepared provider,
root-bound key, model and effort for one non-streaming user-role text + base64
image request, with no tools. It requests 512 output tokens, accepts at most
4,000 model-response characters and checks the encoded JSON/base64 request against
the existing provider byte cap. The image request returns bounded ordinary model
text, including an inability/refusal such as "I cannot process images" or a usable
standard structured textual refusal. No nested observed/unavailable JSON envelope
or refusal-language classifier is required. Source/result labels say "model response
to image request, unverified", never claim pixels were seen or understood. This
prospectively supersedes the previous image self-report/plain-refusal-as-failure
policy, not past failed reviews. The normal outer API schema, complete finish,
no-tools reply, nonempty text and response/request byte bounds still apply.
No core multimodal protocol is added.

No support probe, capability registry, model-name heuristic, separate vision
switch or compatibility guarantee. Rejection, timeout, malformed/oversize image
or response and other failures produce a sanitized explicit failed tool result;
the text loop may continue, disclosing limitations. Never strip the image and
claim success, present alt text as visual inspection, replay the turn, retry
implicitly, or choose another provider/model. Release transient bytes/base64 on
every exit; only bounded model-response/provenance text enters history.

Tool failures use short fixed understandable descriptions, with known numeric
HTTP status/limits when available: public-address refusal, network/HTTP failure,
unsupported/empty/oversized content, request/rate budget and image API failure.
They remain explicit tool results and visible current-attempt failure feedback;
normal dialogue may explain them. No raw body, exception message/stack, credential
or private app context is disclosed. Nonempty model inability text is data, not a
client-classified technical failure.

Chat shows localized tool progress and tool/outcome failure labels. Each received
failure retains the same bounded sanitized tool-result text and, for a valid
search call, its generated query. These literal selectable details are shown
directly, including while the next model reply is pending or after that provider
request fails; an explanation from the model is not required. They accompany
completed turns and interrupted history entries. Current-attempt presentation
advances with the next attempt; route exit releases its memory, not the accepted
checkpoint. Late unauthorized output remains suppressed. These details reference
already budgeted call/result data, not a separate persistent diagnostic log.
Raw internal exceptions, credentials and withheld late output are not added.
Selectable fetched titles/URLs remain separate from arbitrary model Markdown.
Retrieval is not proof of generated claims. A generic omission label covers both older turns and current exchanges;
current-attempt failure feedback is independent of any prior completed turn's
failure indicator. Links/images in answers remain inert: no automatic opening/loading.
Source metadata follows the same complete canonical records, archive limits and
512,000-character current-record bound, not an unbounded separate page registry.
Summarization changes request context, not the original selectable sources.
Current mandatory input/answer/exchanges that cannot fit fail context-full rather
than silently clipping user input/answers or keeping half exchanges. Bounded
Chat text, queries, URLs and tool results may enter the encrypted conversation
checkpoint. Download buffers and image pixels do not; none enter logs or Bundles.
There is no background fetching, uploads, RAG/vector database, browser framework,
reader selector or private/tunnel/file authority for these public Web tools.

## Provider settings and credentials

From Settings → LLM assistant → Assistant provider, enter the API root and key,
then enter a model ID or use Fetch models and select one. Save commits the form;
model discovery is not required to enter a manual model ID.

The form accepts API root, model ID (manual or explicitly fetched), optional
effort (provider default, presets or a manually entered value), model context
window, output reserve and the output-token parameter spelling. It also accepts
the summary trigger percentage (default 80, range 1–100) and an optional summary
model ID; blank uses the chat model. The form explains the additional model
request and shared provider/key/window requirements. Saving makes no request. The API key
field is masked, never prefilled, and deliberately excluded from saved instance
state. A restored form that lost a replacement key requires re-entry rather
than silently falling back to the stored key. Draft metadata and the original
revision survive recreation; an old draft cannot retarget newer settings.

A blank key keeps the saved credential only for the same canonical API root.
Changing the root requires explicit key entry, for both Save and Fetch models.
The key and its canonical root are sealed together with AES-256-GCM through
Android Keystore alias `frpjump-assistant-provider-v1`. Root binding is checked
on unseal too, so changing plaintext metadata cannot redirect the saved key.
One atomic file, `assistant_provider.json`, commits metadata plus sealed
credential together. No plaintext key or sealed blob enters the public StateFlow.
Unsealing happens only for explicit provider use; metadata-only saves keep the
existing sealed bytes without decrypting them.

Each commit has a new revision. Save, credential resolution and discovery
completion check their originating revision, reading current storage rather
than trusting an old UI snapshot. Read/decode failures are not treated as an
empty profile and cannot overwrite unknown data.

### Failure and recovery

Retry reads an unreadable provider document again; there is no destructive
reset UI for it. An unreadable Keystore credential can be replaced by entering
a new key without decrypting the old one.

Save waits for commit and keeps the form/error on failure. Pending operations
prevent duplicate input; disposed or duplicate callbacks cannot finish a newer
operation. Leaving the form cancels model discovery. Failed discovery does not
prevent a manual model ID. Errors use fixed messages/status codes, never raw
server, crypto or storage exception content. No live credentials are required
by tests. Deactivation stops agent work, not an explicitly requested settings
inventory operation; saving/enabling never silently starts discovery or chat.

## External API adapter

`OpenAiConfig` selects the API root (usually ending in `/v1`), model ID,
optional explicit `reasoning_effort`, output-token parameter spelling
(`max_tokens` or `max_completion_tokens`) and finite byte bounds. Discovery
can run before model selection. No provider/model capability is inferred
from an ID; unsupported options produce a status error, not an implicit retry
with different semantics. This is Chat Completions, not the Responses API.

`OpenAiAgentProvider` receives a key in memory from the app-owned credential
store; the transport itself performs no persistence. It sends Bearer
authorization only to the configured target.
Remote roots require HTTPS; HTTP is accepted only for loopback hosts. Roots
with user information, query or fragment are rejected. Redirects and automatic
connection retries are disabled. A transport guard also removes HTTP503 retry
hints, because OkHttp4 otherwise replays `Retry-After: 0` despite its connection
retry switch. No body/endpoint/key is included in adapter
exceptions, and no external tracing or fallback is installed.

Defaults: response body at most 1 MiB, request at most 2 MiB; configurable
bounds 1 KiB–8 MiB. The response cap is enforced while reading, including
chunked and transparently decompressed bodies. Requests are checked after
JSON encoding, not claimed as a pre-allocation heap bound. JSON nesting is
preflight-limited to 64 before parsing, including tool-argument JSON. Truncated,
filtered, malformed or unsupported tool responses cannot authorize execution.
Cancellation cancels the exact OkHttp Call; it does not cancel unrelated calls.

Some compatible APIs require continuation state alongside assistant messages,
including [DeepSeek reasoning tool calls](https://api-docs.deepseek.com/guides/thinking_mode/).
Such returned state is opaque provider-owned data, not assistant UI text. It
is budgeted with the reply and request, redacted in string representations,
and returned through its owning provider instance. The app's encrypted checkpoint
may export and rehydrate it for a new instance under the same API root/model,
without displaying it or exposing credentials. Changing root/model starts a new
active context while preserving readable history. Limit-only changes preserve
continuation. Old state is not silently forwarded to another provider/model. Other callers must still reuse the
provider or explicitly restore an appropriately bound checkpoint. This does not claim
compatibility with every provider-specific thinking parameter.

## Verification and non-goals

The app surface includes provider setup, chat, optional public-read tools and
the profile-information and SSH-command tools above, and bounded automatic
working summaries. Tunnel-control, dedicated Files/MQTT/TCP tools, evaluators, permission modes and
subagents remain outside this surface. No tunnel creation, start, reconfiguration
or deletion is granted to the agent. No permissions evaluator or confirmation modes are shipped. The enabled SSH tool has full access within the SSH account’s rights. Public tools carry the public-read/data-disclosure notice above, not
a misleading full-access warning. Any other future connection-tool prototype requires
separate approval and an explicit full-access warning until confirmation modes
exist; availability does not waive that conditional safety requirement.

Synthetic shared-source/projection tests cover multiple profiles, repeated device
names, devices without connection rows, saved/unnamed tunnels, Undo, unavailable
sources and stale/error state. Fake-provider information-tool tests exercise
captured admission, one ordinary refresh, typed facts and secret exclusion,
result bounds and cancellation. Catalogue task checks include thousands of
entries, partial queries, all connection variants across repeated-name profiles,
whole-record continuation, unknown sources, long Unicode fields, standalone
operation facts and retained received data after provider failure/revision/eviction.
The synthetic consumer derives its answer from actual received records, not an
unconditional completion. The production-owner/API35 screen fixture also exercises
these multiple-result and stale-data/provider-failure outcomes; this is not live
LLM quality certification. Deterministic ownership tests cover both refresh
endpoints across profiles, revoke/start ordering, held HTTP/body/callback exit,
Stop/pop, module off/on and provider authority changes, without revoking a Send
for tool-switch-only changes. Synthetic SSH client/owner tests cover saved target/auth/trust, bounded dual streams, honest status, target loss, guarded admission and actual cleanup under Stop/timeout/revocation. The shared Tools binding and component fixtures
include all five switches, save errors, retained owners and explicit invocation.
Existing kernel tests use fake providers/tools. Adapter tests use synthetic
loopback HTTP responses. Settings/store tests cover committed opt-in,
malformed-field fallback, disable invalidation, credential/root binding,
revisions and read/write/crypto failures. Compose test fixtures cover
committed/busy/error/disposed-callback states, explicit discovery and restored
drafts; a separate Android fixture exercises an ephemeral Keystore alias.
Chat tests exercise the production conversation owner with controlled preparation,
providers and cancellation, plus the real credential-store → adapter loopback
path. Controlled loopback callback tests also hold real adapter cleanup across
completion, failure, cancellation and authority changes before explicit retry.
Synthetic navigation/Compose checks use the production Settings chat entry,
chat ViewModel and screen: complete answers/follow-up, Stop, recoverable errors,
setup recovery, recreation and pop. The explicit test-only plain-Application
runner is selected by `-PassistantChatComponentTests=true` and an explicit chat
class filter; ordinary discovery keeps the default runner. Only that explicit
debug fixture disables BootReceiver in the merged manifest, preventing boot
restoration from targeting the plain Application. Ordinary debug and release
keep BootReceiver enabled, including release builds with the test property. Scoped API35 component
checks cover representative EN/RU, light/dark, portrait/landscape and 200% font
configurations; font scale is restored afterwards. These fixtures do not establish
live LLM, SSH or broker compatibility, or physical-device/vendor behavior.

## Related surfaces and implementation references

- [App behavior and shared UI rules](contract.md#ui-rules) own common UX,
  settings and tunnel behavior. This surface adds explicit external-provider
  discovery; it is not a general permission to send app data off the phone.
- [SSH persistence](ssh-persist-contract.md) owns terminal sessions and the shared connector/login/host-key policies also used by the assistant’s separate owned command client.
- [Kernel interfaces and limits](../agent-core/src/main/kotlin/ru/adsrv/frpjump/agent/AgentProtocol.kt),
  [execution](../agent-core/src/main/kotlin/ru/adsrv/frpjump/agent/AgentModule.kt)
  and [window selection](../agent-core/src/main/kotlin/ru/adsrv/frpjump/agent/AgentWindow.kt).
- [External adapter](../agent-openai/src/main/kotlin/ru/adsrv/frpjump/agent/openai/OpenAiAgentProvider.kt)
  and [provider configuration](../agent-openai/src/main/kotlin/ru/adsrv/frpjump/agent/openai/OpenAiConfig.kt).
- [Conversation owner](../app/src/main/java/ru/adsrv/frpjump/assistant/AssistantConversation.kt)
  and [chat screen](../app/src/main/java/ru/adsrv/frpjump/ui/settings/AssistantChatScreen.kt).
- [Provider store](../app/src/main/java/ru/adsrv/frpjump/assistant/AssistantProviderStore.kt)
  and [settings form](../app/src/main/java/ru/adsrv/frpjump/ui/settings/AssistantProviderScreen.kt).

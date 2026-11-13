# Optional connection assistant

## Boundary and availability

This surface covers the optional assistant kernel, external API adapter and
provider setup and a locally saved chat with optional public-read,
profile-information and SSH-command tools. The owner uses Settings to opt in,
configure one provider and open Chat (also from the bottom navigation and the
terminal menu, see [Entry points](#entry-points)); maintainers can supply provider/tool
implementations to the kernel. Tunnel-control tools remain unavailable. Every
tool is one row of the [tool table](#tool-table) and follows the common
[tool policy](#tool-policy); a tool's own subsection describes only what is
specific to it. `ssh_command` and `profile_information` are assistant adapters
over operations of the [operations surface](contracts/surfaces/operations.md);
the texts and behavior below are unchanged by that layer. Calls of tools that
change something (today `ssh_command`) ask the owner first unless the owner
turned the question off or answered For session for that tool and target; see
[Confirmation of changing calls](#confirmation-of-changing-calls).

What the assistant can never do. Each rule is stated where it applies:

- Tunnels: no tunnel creation, start, reconfiguration or deletion is granted to
  the agent. No tool starts or controls a tunnel ([tool policy](#tool-policy),
  [tool table](#tool-table)).
- Credentials: no current tool takes or returns app-stored credentials, and
  `ssh_command` accepts no credentials from the model; the rules are in
  [SSH command](#optional-ssh-command) and
  [profile information](#optional-profile-information). No credentials are
  automatically attached to model requests' content
  ([Instructions](#instructions)). The provider key is sent only to its
  configured target ([External API adapter](#external-api-adapter)).

Assistant UI inherits the [shared UI rules](contract.md#ui-rules): concise,
understandable and functional screens, minimal necessary steps, direct common
actions and short labels/messages. Keep accessibility, waiting/error feedback
and safety warnings; visual minimalism must not remove them.

### Entry points

One chat screen, one conversation, reached three ways; the entry changes nothing about what the chat does.

- Settings → LLM assistant → Chat.
- The bottom navigation's fifth item, "Assistant" / «Помощник» (a neutral chat-bubble icon). It is shown only while the module is on,
  appears and disappears with the setting without a restart, and opens the chat over the current section; Back returns to that
  section. All five labels stay whole in both languages, in landscape, at 200% font and in a 320 dp wide window (the label size steps
  down to a floor, tracking is tight, nothing is cut). The item opens a screen and is never selected, so it is announced as a button,
  not as an unselected tab. While an attempt runs (a confirmation wait included) the item shows a small dot that takes no room, and
  TalkBack announces its state: "Working" / «Работает», or "Waiting for your answer" / «Ждёт ответа» while a card waits; the dot is gone
  when the attempt ends. The chat itself has no bottom bar, so the dot is seen exactly when the chat is not on screen, with or without
  the notification permission.
- Terminal ⋮ → "Ask the assistant" / «Спросить помощника», offered only while the module is on. The text selected in the shown tab
  with the app's own selection (a selection made inside tmux copy mode lives on the server; the app does not see it and does not
  quote it) is read from the terminal view when ⋮ is used, never in the background, and goes into the chat draft as a fenced code
  block; the cursor is after it, so the owner types the question below. The whole quote is at most 4,000 characters. A longer
  selection keeps its END, the latest lines, which matter most in terminal output: the cut is on a line boundary (a single longer line
  is cut by characters, never inside a surrogate pair) and the first line of the quote says "[start truncated]" / «[начало обрезано]»,
  so the draft shows what is missing before Send. An existing draft is kept and the quote is appended after it; a result over the
  message limit is not applied and the usual "too long" notice shows. The owner sees and may edit the draft; nothing is sent until
  Send. Without a selection the chat just opens. The quote is the owner's own draft text, not an app instruction, and no
  model-facing text changes.

There is only ever one chat entry: opening it while it is already open (from the navigation, Settings or the terminal menu, also from
screens that were opened over it, such as a notification's device card) returns to that chat by closing the screens above it, so
its conversation, its saved history and a running Send are never doubled or stopped. A waiting terminal quote is taken by the chat at
once and kept in memory only; it goes after the draft once the saved draft has loaded and no Send runs, and it is dropped with the chat
screen if the owner leaves first (an attempt that goes on does not keep it). If the history cannot be read the chat's own "could not restore history" notice shows and the quote is not added.

Whichever entry opened the chat, the app lock, Stop, "For session" lasting while the chat is open or an attempt runs, a disabled
module and an unconfigured provider behave as described elsewhere in this contract, and the provider screen opened from the chat
works from every entry. The assistant never reads terminal tabs by itself.

<a id="current-stage-kernel-external-adapter-and-provider-setup"></a>

### Settings flow

Settings → LLM assistant enables or disables the experimental module. It is
**off by default**, including upgrades from versions without this setting.
The preference is committed before the switch reports success. A failed
write retains the previous preference and shows an error. A malformed new
flag falls back to off without resetting unrelated settings. The opposite holds
for the confirmation value ([below](#confirmation-of-changing-calls)): a
malformed value falls back to ask (requests on).

Settings → LLM assistant → Own instructions holds the owner's own text for
the model ([Owner's own instructions](#owners-own-instructions)); it is empty by
default and only guidance.

Settings → LLM assistant → Assistant provider configures one external
OpenAI-compatible provider. Chat is available next to provider setup. SSH
commands require their own default-off tool switch; tunnel control is **not
available**. Enabling the module, opening settings or saving a provider
performs no network request. Fetch models is an explicit user action and is
available during setup even while the agent module is off. A saved provider is
configuration, not evidence of a successful API call. See
[provider settings and credentials](#provider-settings-and-credentials) for
Save, Fetch models and recovery behavior.

## Kernel

`agent-core` is a plain Kotlin/JVM module: provider-neutral messages, suspend
provider/tool interfaces, one execution loop, bounded window and results,
cancellation (including the blocking-call and start-admission primitives
`blockingCancellable` and `SideEffectAdmission`) and observable events. It has
no Android, HTTP, SSH, connection-discovery or credential dependency. It uses
Kotlin coroutines/JSON already used by the app, not an agent framework.

### Module lifetime and revocation

The app owns the opt-in setting and module lifetime. Every successfully
committed disable revokes admitted work, even a rapid off/on that an observer
sees only as enabled. Admission and
run ownership check the committed disable directly; the observer cancels stale child
jobs. Failed writes do not invalidate work. Enabling again cannot revive an old
run. This revocation is process-local: no execution survives process restart and
no persistent journal is needed. Neutral providers need no admission API: the
app checks admission before each provider call. Transports that start their own
network work (public web, image) check admission immediately before each start.

### Turn loop

One run at a time. A new request while one is running returns busy rather
than waiting and silently running later. A stopped/noncooperative run keeps
that slot until it exits. Runs own child jobs; stopping the assistant must
not cancel unrelated application work.

Each step is one provider request followed by the proposed tools, in order. A
call of a changing tool may first wait for the owner (see
[Confirmation of changing calls](#confirmation-of-changing-calls)); the calls
stay in order. Tool calls and their results form one indivisible exchange.
Duplicate call IDs within a run, incomplete exchanges, excessive replies and
empty final responses are rejected.

Disabled and busy admission return without a provider/tool call. A run finishes
with complete, context-full, step-limit, model-timeout, provider-failed or
invalid-reply status; cancellation propagates to its caller. Tool outcomes are
success, failed, timeout, unknown-tool or declined (the owner refused the call;
nothing ran), distinct from the run's finish status.

Timeouts and cancellation are cooperative: provider and tool adapters must
support cancellation of their own I/O.

### Limits

Execution defaults are finite, validated kernel parameters, not settings UI:

| Limit | Default |
| --- | --- |
| Model steps per run | 12 |
| Tool calls per step | 4 |
| Model request timeout | 60 seconds |
| Tool timeout | 30 seconds (the owner's confirmation wait is not part of it) |

The model timeout does not include the confirmation wait either.

### Sliding window, not compression

The kernel builds an active request while retaining the caller's history
unchanged. Instructions, tool schemas, the current user request and output
reserve, and the owner's own instructions when set
([Owner's own instructions](#owners-own-instructions)), count toward the budget. Only a suffix of complete earlier turns is
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
timeout; fixed instructions, the owner's own instructions, enabled schemas,
output reserve and current user remain unchanged. Chat uses this point for the working summaries described below,
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

## Provider

`agent-openai` implements non-streaming OpenAI-compatible Chat Completions and
model discovery. It is not constructed or called by enabling the module.
`AssistantProviderStore` owns the app-private atomic provider document and
Keystore-backed credential; its observable snapshot contains metadata only.

### Provider settings and credentials

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

#### Failure and recovery

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

### External API adapter

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

## Instructions

The application skill is the base system prompt,
[`app/src/main/assets/assistant/skill.md`](../app/src/main/assets/assistant/skill.md)
(version 6). The base prompt is the unchanged byte prefix of the first message
of every main chat-loop request, stable within and across Sends; without owner
instructions the first message equals it exactly. The owner's own instructions
never edit it; they are appended after it in that same message
([below](#owners-own-instructions)). It is guidance for
the model, not a permission system or an executing script; the code enforces
every limit. The compact summarizer prompt is `assets/assistant/summary.md`
(version 2); summary requests open with it instead. Both are read strictly
(UTF-8, no byte order mark) and there is no compiled-in fallback prompt. Still
compiled into the app are the fixed image-inspection question wording, the
summary request framing text ("Earlier conversation summary", "Previous
summary / Observed conversation"), the fixed owner-instructions header
(`## Owner's additional instructions`), tool descriptions and schemas, and fixed
tool-result texts. No automatic app context is attached. A changed prompt asset
gets a new version and a new pinned hash.

Requests contain explicit chat text/history, the base prompt (`skill.md`,
version 6), the owner's own instructions when set, and only enabled tool schemas.
The base prompt contains no profile,
connection, enabled-tool list, date, version or language data; the same bytes
open every main chat-loop request (summary requests open with `summary.md`).
Tool schemas follow catalog order with stable JSON; summary, history and
the current request follow the system message. No provider-specific cache
parameter is sent. A prompt asset that is missing, malformed (not strict UTF-8, or
with a byte order mark) or empty fails the Send as an ordinary setup problem,
with no fallback text and no request. No profiles,
connection data, files, credentials or diagnostics are automatically attached.
Existing endpoint, redirect, retry, payload and sanitized-error protections
remain.

What the summarizer prompt retains, and the limits of summary requests, are
described in [Automatic working summaries](#automatic-working-summaries).

<a id="owners-own-instructions"></a>

### Owner's own instructions

The owner can give the assistant standing instructions of their own (style,
language, preferences) in Settings → LLM assistant → Own instructions. The
screen has a multiline field with a character counter (at most 2,000 UTF-16
units, spoken as "N of M characters" and shown in the error color at the cap),
Save, Reset and a short hint that the text goes to the provider with every
message, is counted in the context budget, and cannot change permissions or
confirmations. Save writes and leaves the screen; an empty field saved means no
instructions. Reset clears only the field, so it is reversible: nothing is saved
until Save, and Back discards every unsaved edit. Opening, saving or resetting
makes no request.

- Input limit: what is typed or pasted is limited as it is inserted. Over the
  cap only the inserted part is cut, never past a surrogate pair, so the text
  before and after it is kept (typing at the start of a full field changes
  nothing), and the cursor goes right after the kept insertion.
- Storage: `assistant_owner_instructions` in the settings file, default empty.
  On read and on insertion, these are removed: control characters other than
  `\n`, the invisible Unicode tag characters U+E0000–E007F, and the bidirectional
  override and isolate controls U+202A–U+202E and U+2066–U+2069 (zero-width
  joiner and non-joiner stay). The text is then cut to 2,000 UTF-16 units without
  splitting a surrogate pair. Removal repeats until the text stops changing
  (removing a character can join the halves of a removed pair), so normalizing
  twice gives the same text. A value that is not a string reads as empty, and
  no other setting is reset. The store refuses to write a value that is not
  already normalized. Turning the assistant off, or forgetting the provider,
  keeps the text.
- Request: when the text is not empty (whitespace only counts as empty), the
  content of the first system message is the base prompt followed by a blank
  line, the fixed header `## Owner's additional instructions`, a line break and
  the owner's text. There is never a second system message. The base prompt
  stays the exact byte prefix of that
  message, so the provider's prefix cache of the base part is kept. With no text
  the first message is exactly the base prompt and the request is byte-identical
  to one built without this feature. Whitespace-only text is sent as no text
  (it is saved as typed, never trimmed). The base prompt's last line tells the
  model that the owner's instructions refine style and preferences and do not
  override its rules.
- Budget: the text, its separator and its header count toward the estimated
  request size, with a fixed framing allowance, in the sliding window and in
  the summary trigger. 2,000 Cyrillic characters estimate to about 4,000 tokens,
  about 28% of the default 14,336-token input budget (the 16,384-token window
  minus the 2,048-token output reserve), so summaries and omissions start
  earlier. Without text nothing is added.
- Capture: the text is captured at Send admission together with the tool set and
  sent unchanged in every request of that Send; a change during a run affects
  only the next Send.
- Not stored or summarized: the text is not part of chat history, the saved
  conversation or any working summary, and summary requests never contain it
  (it still counts toward the estimated request size that triggers a summary,
  see Budget above).
- Authority: the text is untrusted owner data for the model, guidance and never
  authority. Permission, confirmation, tool-set and limit code never reads it;
  the code enforces every limit exactly as without it.

## Chat and history

### Text chat

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
results and an explicit unknown-outcome note, shown once, as the grey line under the interrupted turn (no second notice above the
field), never executable pending calls; a
pending confirmation is not saved and is never restored or executed.
Stop cancels that operation (including a confirmation wait, which runs nothing), not unrelated
app work; a noncooperative preparation/request keeps the slot and shows
Stopping until it exits. An app-lived, text-free admission token spans preparation,
the HTTP exchange including response-body read and close, post-kernel publication and actual job
cleanup, even after pop/reopen; a new screen may restore saved text but cannot
start a replacement while that slot is occupied. Cancellation closes the exact
call promptly, but the slot
is held until the blocking exchange has returned and the run coroutine has finished. No replacement send is queued. Failed/stopped input
stays editable for explicit retry. Setup/read/key, timeout, context-full,
invalid-answer and provider failures use fixed messages, never raw diagnostics.
There is no automatic retry, streaming or provider/model fallback.

**An attempt outlives the chat screen.** The conversation belongs to the app, not to the screen. It is built when the chat is first
opened (never at app start) and lasts while a chat screen is open (anywhere on the back stack, including under Tools or a screen
opened over it) or while an attempt (a Send, a confirmation wait included) runs. When both have ended it closes: the draft is saved,
the "For session" grants are cleared and memory is released; the next opening restores the saved history. Leaving the chat (Back, the
arrow, another section, another app, the lock, activity recreation, the activity finishing) does not stop an attempt: its reply and
results are saved to the history and are there when the chat is opened again. Reopening while it runs shows that same attempt with its
Stop; there is no second conversation and no second admission. The chat's reactions to a disabled assistant, a provider revision, the
busy slot and a tool switched off belong to the conversation, not the screen, and work while no chat screen exists. An attempt is
stopped only by Stop, by turning the assistant off (off/on included), by a provider change that revokes it, by its own limits, by the
server removing this phone's profile (the REMOVED screen) and by process death (the existing unknown-outcome note; there is no
foreground service and no journal). Clearing the history is not a stop path: Clear stays unavailable for the whole attempt, a card
wait included; the owner presses Stop first.

### Saved history

The back-stack ViewModel keeps the chat screen attached to the app-owned conversation across configuration changes.
The app owns one canonical saved conversation: messages, observed protocol
exchanges, turn outcomes, sources and draft. UI text, tool-detail panels and model
requests are derived from those records, not separately persisted histories.
It seals one atomic `noBackupFilesDir/assistant_chat.json` document using the
existing AES-256-GCM Keystore box, alias `frpjump-assistant-chat-v1`. There is no
cloud synchronization, Android backup, conversation Bundle or plaintext log.
Encoding, crypto and file IO run off Main. Messages,
received replies/results, outcomes, leaving the chat screen (the draft) and closing the conversation trigger saving; editor keystrokes
do not rewrite the conversation. Retry state contains data only, not a live API
provider. Saves coalesce; accepted local saves may finish after the screen or the conversation is gone. Process restart
restores the last committed snapshot, not an unfinished write or running action.

Read/decryption failure is not an empty conversation and blocks overwriting it;
Retry reads again. Write failure keeps the latest accepted snapshot in memory,
including across route reopening, with an unsaved warning until Retry succeeds.
That memory cannot survive process death. Chat's menu clears history through
shared Undo; editing/sending waits while clearing/restoring. Undo restores the
same data without a provider call. A committed clear outlives the original route;
clear failure retains known data and offers retry. Only one load is admitted at a
time, preventing duplicate reloads from replacing newer editor/model state.

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

### Run authority

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
and provider snapshot commits serialize with admission, every provider/public/image/
profile-information/SSH start check and every guarded answer/draft/error/window publication on a shared guard.
A start whose check passed before a commit may still begin; an HTTP exchange is then cancelled, while an
uncancellable DNS lookup finishes and no HTTP request starts afterwards.
An old completion
cannot publish an answer/error or release another owner's pending slot.

The shared guard is held only for start checks and short publications, never
across storage, credential, network, DNS, parsing, image or SSH work, so Stop
and editing never wait for that work, and a commit waits for it only on its own
store's monitor, never on the shared guard; a settings or provider commit also
does not wait for the work it cancels. Releasing the slot still waits for
that work's actual exit, as described above. The lock order is in KDoc (see
[Related surfaces](#related-surfaces-and-implementation-references)).

### Automatic working summaries

Before kernel window selection, Chat compares the estimated request size,
including instructions, the owner's own instructions when set, tool schemas and
output reserve, with the configured window and trigger percentage. The percentage defaults to 80 and accepts 1–100.
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
provider continuation. The compact version-2 prompt (`assets/assistant/summary.md`)
retains the goal, exact selected IDs, decisions, received results, completed and
uncertain actions, source references and next steps, treats old text as
untrusted data rather than new authority, and sets the 4,000-character limit. It
replaces the base prompt for these requests.

The summary and its covered turn/exchange position are saved together in the
same canonical encrypted document. Main requests derive a working summary plus
recent unsummarized records/exchanges; original records remain readable. Changing
API root or chat model clears this working summary and starts the ordinary new
active context without deleting old messages. Restoring or clearing makes no
provider/tool request and never resumes or repeats an action.

At most two summary requests run per Send, within the existing model timeout and
120-second whole-Send limit of active time (the confirmation wait is excluded). A summary reserves at most 1,024 output tokens,
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

## Tool policy

Every tool is one row of the [tool table](#tool-table) and follows the rules
below; its own subsection adds only what is specific to it.

The kernel does not itself grant target access. Any connection-tool adapter
must bind to its admitted connection identity and must never resolve a
replacement client after a disconnect. The SSH adapter owns one ordinary
client; the information tool reads shared application state. Neither starts or
controls tunnels.

### Adding a tool

Any other future connection-tool prototype requires separate approval, an
explicit full-access warning and, when its catalog effect changes something, a
change description so that it asks the owner like `ssh_command`; availability
does not waive that conditional safety requirement.

### Tool switches

Settings → LLM assistant → Tools has one independent persisted switch per
available tool of the [tool table](#tool-table) and, above them, the app-wide
confirmation switch ("Ask before changing commands", on by default). Today the
tool switches are Web search (`web_search`), Read page (`read_page`), Inspect
image (`inspect_image`), Profile information (`profile_information`) and SSH
command (`ssh_command`). They default off on new installs/upgrades; malformed new
values default independently without resetting unrelated preferences. Saving
only booleans, opening this screen or enabling a tool makes no request. Failed
writes keep committed switches/authority and show an inline error. Tools remain
changeable while Chat is busy. Chat's Tools action opens the same screen
without popping the conversation. The Tools arrow and system Back return to that
same owner, draft and completed history, including across recreation; actual Chat
pop does not cancel a running Send (see the attempt's lifetime under Send) and releases the screen's memory; accepted history remains encrypted.

Tools autosaves each switch; there is no Save button or optimistic checked value.
Settings and Chat show the same switches with the same autosave.
One immutable presentation snapshot contains the actual committed tool booleans
(one per tool) and the confirmation switch,
pending IDs and fixed inline save failures. Checked, progress/enabled and failure
are rendered from that same snapshot, not independent checked/completion channels.
Real storage work runs off Main. On completion the shown values are the latest
committed ones, published together with clearing that switch's pending state and
setting or clearing its error. It never assumes requested equals committed.
Store notifications show current committed values and keep pending and error
state. External/concurrent writes cannot be rolled back
by a delayed acknowledgement. Same-ID pending actions are ignored without a queue;
different IDs may save independently, preserving unrelated settings.

Back hides Tools without cancelling the parent-owned save; reopen and recreation
retain the parent-owned autosave and its pending/error state. Actual parent pop/clear cancels child
operations and prevents old UI publication. Already executing blocking storage may
still commit: cancellation does not promise rollback. A new owner reads actual Store
state and notifications. There are no save completion/navigation callbacks or
persistent copies of presentation state. Construction/opening does no storage IO,
credential unseal or network operation. Current-Send admission still reads Store
authority, never presentation; pending UI acknowledgement is not network authority.

### Tool set captured at Send

Each Send captures the committed tool booleans atomically at admission and keeps
that fixed set throughout the run, including calls dispatched after a switch
changes. Changes apply only to the next Send; the current Send may finish with
its original tools (the confirmation switch and session grants are the exception:
they are read at each call). They neither cancel work nor clear history, provider
or draft. There is no tool-change epoch/revocation signal. Failed saves retain
prior settings. Proposals unavailable in that Send's captured set return bounded
explicit unknown-tool results without network; they do not grant authority.
Public content is acquired only through explicitly enabled tools; completed
conversation history remains after tools change.

### Admission before parsing

Every tool checks the captured owner before validating its arguments, so an
invalid-argument result is reported only for an owned call. The confirmation
hook runs before the tool, only for known tools, and the owner is checked again
after it returns, so a Stop or disable that raced the answer wins and nothing
runs.

Admission, every model/public/image start check and every source/progress/answer/error
publication still check the captured owner, provider revision and disable version.
Stop, assistant disable (including off/on), provider revision/read failures and the removal of
this phone's profile cancel exact work (leaving the chat does not); the shared slot remains occupied through DNS, the HTTP exchange and body read,
parser, owned SSH worker/readers and coroutine cleanup. No replacement is queued. Blocking DNS, parsing,
image preparation and settings IO run off Main. How start checks serialize with
settings and provider commits is described in [Run authority](#run-authority).

### Confirmation of changing calls

Before a call whose effect changes something runs, the owner is asked, unless
the app-wide switch (Settings → LLM assistant → Tools → "Ask before changing
commands") is off or a For session grant covers that call's tool and target.
The effect is the tool's catalog effect; for a tool built on an operation the
operation's effect and target are used, and a test pins its effect equal to the
catalog's. Today the only changing tool is `ssh_command`, whose target is a
saved connection. Calls whose effect only reads never ask. The default is on.

The question is a card above the message field (the history takes the room the
card and Stop leave; with the keyboard up at large font the history stays at
least three lines high because the plain-text notices above and below it (never
the setup or retry buttons) and the message field's height are capped; a capped
notice region ends on a whole text line, shows at least one full line, and while
more is below shows "▼ Scroll for more" / «▼ Прокрутите вниз»; the disabled
message field is hidden while a question is open; each question opens at its
top). The four answers are pinned at the bottom of the card; above them a
section scrolls with the tool, the target by name, never by ID (for a saved
connection: profile · device · connection; "Unknown connection" when
unresolved), what the call will run, in full, and the tool's warning. For a command
(today `ssh_command`) that is the full command (monospace, selectable, always
left to right, in its own scrolling box with its line count; non-printing and
disguising characters, including the whole default-ignorable set and long
combining-mark runs, are shown as highlighted escapes, and a note says when it
has right-to-left letters or Arabic digits; in the rendered text a left-to-right
mark follows each of them, so the order shown is the order that runs) and the
full-access warning. A string argument with an unpaired surrogate is invalid for
every tool (no card, nothing runs).

Answers: **Allow** (this call), **For session**, **Always**, **Decline**. For
session covers the tool and the exact target (today a saved connection) until
the chat screen is left and no attempt runs (the conversation ends; it gives no new authority, and a session no longer ends in the
middle of an attempt), the assistant is disabled, the tool is switched off,
the history is cleared, or the target, or what it belongs to, is edited or
removed (for a saved connection: the connection or its profile; a grant is
stamped with the target's saved definition and dropped when it changes). A For session grant is in memory
only. Always turns the app-wide switch off after a second confirmation (a dialog
says what is turned off and where to turn it back on; Cancel returns to the
question, still waiting); if that cannot be saved, the call is allowed once, an
inline note says so, and requests stay on. Decline, and five minutes without an
answer (measured in real time, device sleep included), give the model a fixed text (`declined` outcome) and nothing runs. A
declined call is saved as `DECLINED` (sent to the model as `declined`) and shown
as "Declined", or "No answer, declined" when the wait ran out, never as a
failure. Stop, disable, provider changes and the removal of this phone's profile during the wait cancel it and
run nothing; a late answer or a stale card id is ignored. Leaving the chat does not cancel the wait: a card raised while the chat is
not shown waits, is shown in the chat when the owner returns and never over other screens, and the lock leaves it waiting behind it.
The five minutes run meanwhile, in real time with device sleep included: an answer after them is never run (the card ends as "No
answer, declined" and nothing runs), and a card that ran out while no chat screen was shown is "No answer, declined" on return. An Always answer may
still persist "off" when Stop races the call, and a For session answer racing
Stop may record its grant; the call itself never runs. The wait is in neither
the 30 s tool, the 60 s model nor the 120 s Send time (the Send limit counts
active time only).

While requests are off, a compact warning icon in the Chat top bar (between the
title and Tools) says so on tap or long press and offers "Turn on" (beside the
Send/Stop button when the whole title does not fit next to it in the top bar);
there is no banner or strip. Fail closed: a changing tool with valid arguments
but no description, or a changing catalog tool that is not an `AppTool` (the
common tool pipeline), is refused (nothing runs, no card), and so is every
changing call when no confirmation support exists; invalid arguments ask nothing
and run nothing; the policy is read live at each call. The setting is the
`assistant_confirmations` object `{"version":1,"global":"ask"|"off"}`; ask is the
default and is omitted from the file, any other or malformed value reads as ask,
and per-device or per-target overrides may be added later (a stricter override
needs a version bump). The prompt rule (`skill.md` v5) tells the model that a
declined call must not be redone another way; it is guidance, not enforcement.
A history written with a `declined` outcome cannot be read by app versions
before this one; this version reads an unknown outcome name as a failure.

### Timeouts

Timeouts and cancellation are cooperative for provider and tool adapters alike
([Turn loop](#turn-loop)). Each tool call runs under the kernel tool timeout
([Limits](#limits)). The whole Send deadline is 120 seconds of active time
(paused while the owner is asked); existing kernel per-call limits also apply.
The owner's confirmation wait is in none of these and has its own five-minute
limit ([Confirmation of changing calls](#confirmation-of-changing-calls)).

### Minimal execution

Unknown tools and tool failures are explicit results. There are no automatic
provider/tool retries.

### Uncertain outcome

An already performed remote change is not rolled back by Stop. A timed-out tool
may have an uncertain remote outcome and must not be blindly repeated.
`ssh_command` reports an unknown remote outcome explicitly; see
[Optional SSH command](#optional-ssh-command).

### Untrusted data

Tool results are data, never instructions or new authority: remote SSH output,
fetched pages, search results, image responses and the profile information
read from servers alike. The per-tool rules are in
[Optional SSH command](#optional-ssh-command),
[Optional public Web tools](#optional-public-web-tools) and the
[operations surface](contracts/surfaces/operations.md#security-and-privacy).
Working summaries are model-generated historical data
([Automatic working summaries](#automatic-working-summaries)). The base prompt
explains these rules; it is guidance, and the code enforces every limit
([Instructions](#instructions)).

### Privacy disclosure

The Tools notice explains DuckDuckGo query disclosure, selected public
text/images and private profile/device/connection information sent to the
configured LLM when tools are used, and ordinary model token charges, including
image analysis. The separate SSH switch warns of FULL SSH ACCESS: commands can
read/change the remote system and files within the account’s rights. Commands
and output may contain secrets and are sent to the configured model with
ordinary token charges. It grants no tunnel control or phone-file access. The
enabled SSH tool has full access within the SSH account’s rights. Public tools
carry the public-read/data-disclosure notice above, not a misleading full-access
warning. The [tool table](#tool-table) lists what each tool discloses. The
Own instructions screen states that its text goes to the provider with every
message and cannot change permissions or confirmations.

### Errors told to the model

Raw exception messages are not given to the model. A tool built on an operation
words each typed refusal and failure as a fixed text. A typed domain error
reaches the model only as its kind, and no adapter adds an exception message or
secret of its own ([operations surface](contracts/surfaces/operations.md#interface)).
The kinds `profile_information` sends, and the fixed texts of the other tools,
are described in each tool's subsection.

## Tool table

| Tool | Effect | Operation | Arguments | Limits | Confirmation | Disclosure | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `web_search` | `READ_REMOTE` | none: public transport | `query`, at most 256 characters | at most 5 results; 3 searches per Send and 20 per hour, 10 s between attempts; block cooldown at least 15 minutes; shared public budget | never | the query goes to DuckDuckGo; the results go to the model | optional, default off |
| `read_page` | `READ_REMOTE` | none: public transport | `url`, at most 2,048 characters: public HTTPS, port 443 | 8,000 text characters, 100 links and 20 image references per page; shared public budget | never | selected public text goes to the model | optional, default off |
| `inspect_image` | `READ_REMOTE` | none: public transport and a same-model image request | `url`, at most 2,048 characters; `question`, at most 1,000 characters | 2 per Send; JPEG/PNG up to 1 MiB and 4 megapixels; 512 output tokens and 4,000 response characters; shared public budget | never | the selected public image goes to the configured model; token charges include image analysis | optional, default off |
| `profile_information` | `READ_REMOTE` | `profile_query` | optional `query` and `profile_id`, each at most 256 characters; optional nonnegative integer `offset` | one ordinary device-list pull per call; whole-record pages under the 8,000-character result cap | never | private profile, device and connection information goes to the model | optional, default off |
| `ssh_command` | `CHANGE_REMOTE` | `ssh_exec` | exactly `profile_id`, `connection_id` and one nonempty `command` | stdout and stderr: at most 3,000 characters each, drained | asks, unless requests are off or a For session grant covers the tool and target; the target is the saved connection | FULL SSH ACCESS; commands and output, which may contain secrets, go to the model | optional, default off |
| tunnel control | n/a | none: `tunnel_up` is UI only | n/a | n/a | n/a | n/a | not available |

Every tool also has the kernel limits ([Limits](#limits)) and the Send's 120
seconds of active time. The public tools share one per-Send budget: 12 public
HTTP requests including redirect hops, at most 3 redirects per fetch and 1 MiB
per decoded page, search or image response
([Search and static documents](#search-and-static-documents)).

The table summarizes; each tool's subsection governs the details. Qualifiers
such as "attempted dispatches", the 24-hour bound on Retry-After and UTF-16
character counts are stated there.

### Optional profile information

The independent default-off `profile_information` tool accepts optional `query`
(text up to 256 UTF-16 characters), exact `profile_id` (up to 256 characters) and
nonnegative integer `offset`. Empty arguments browse the first page. Only an
actual enabled invocation transfers information; opening Chat/Tools, enabling
or saving does not invoke it or automatically attach an inventory to a prompt.
Each invocation performs one ordinary all-profile device-list pull, the same
operation as Connections refresh, then reads the application's known state.
Normal authenticated server requests, feature checks and cache writes still
apply. Each information-owned request's start check is serialized with the
existing Chat authority guard. A start whose check passed before a commit may
still begin and is then cancelled. Cancellation signals only those owned calls (the
exact HTTP call) and keeps the shared Chat slot until each HTTP
exchange, including body read and close, has returned. Ordinary UI/background
requests use the same call path without the start check; a cancelled caller waits
for the actual exit of its HTTP call. It starts no extra tunnel poll, probe,
connection or control operation.

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
inventory or remote service readiness. Error fields convey a fixed kind by
source field (profile loading, device refresh, connection operation, tunnel
synchronization or protocol mismatch), not raw UI/server/exception/frpc strings:
the field is null, or an object `{"kind": "<KIND>"}` and nothing else. A kind
carries no payload: no server detail, host, URL, exception message or class name,
HTTP body or frpc text. The kinds are a model-visible format: adding, removing or
renaming one is a contract change, and a unit test pins the exact set per field.
Chats saved by 0.3.70 and earlier hold the former fixed phrases (a string in these
fields); they are re-sent as saved and the chat's facts view accepts both shapes.
Typed facts such as relay after direct failure, waiting and device-missing remain
separate; diagnostic text alone is not classified as a failure. Credentials,
keys, tokens, certificates and runtime/store objects are never serialized.

Error kinds, and the fields each can appear in. `source_status.load_error` is
`PROFILES_LOAD_FAILED` or null. `device_list.error`, `tunnel_state.sync_error`,
`connection.error` and `operation.error` hold how a server or network call failed.
`tunnel_state.mismatch` holds a protocol kind:

| Kind | Meaning | Fields |
| --- | --- | --- |
| `PROFILES_LOAD_FAILED` | the profiles could not be loaded | `source_status.load_error` |
| `SERVER_ERROR` | the server answered with an error | `device_list.error`, `tunnel_state.sync_error`, `connection.error`, `operation.error` |
| `NOT_HTTPS` | the control address is not HTTPS | same four |
| `UNREACHABLE` | the server could not be reached | same four |
| `CONNECTION_BROKEN` | the connection broke during the call | same four |
| `REDIRECTED` | the server redirected | same four |
| `UNEXPECTED_ANSWER` | the answer could not be read | same four |
| `UNEXPECTED` | anything else (including a failure the app could not classify) | same four |
| `NO_FREE_PORT` | no free local port in the range | `tunnel_state.sync_error` |
| `NOT_LISTED` | the tunnel was granted, but it was not listed in time | `connection.error`, `operation.error` |
| `PROTOCOL_UPDATE_APP` | the server speaks a newer protocol: the app is to be updated | `tunnel_state.mismatch` |
| `PROTOCOL_UPDATE_SERVER` | the server speaks an older protocol: the server is to be updated | `tunnel_state.mismatch` |

In the four server-kind fields (`device_list.error`,
`tunnel_state.sync_error`, `connection.error`, `operation.error`) a kind
outside the field's set is told as `UNEXPECTED`, so these sets stay true.
`load_error` and `mismatch` take only their own kinds.

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
This informational tool does not authorize connection control, SSH, device
HTTP, Files or MQTT tools. It has an accurate private-data disclosure, not a
full-access claim.

### Optional SSH command

The independent default-off `ssh_command` tool accepts exactly `profile_id`,
`connection_id` and one nonempty `command` string. Owner admission precedes this
validation (see [Admission before parsing](#admission-before-parsing)). Discover
saved IDs through
`profile_information`; there is no additional catalogue or automatic attachment.
One unambiguous saved SSH connection in any profile must already have a ready
visible tunnel. Missing, non-SSH, hidden, removed, down or unavailable targets
fail without starting/reviving a tunnel. No open terminal is required.
The command runs through the `ssh_exec` operation; each of its typed refusals
and failures has the one fixed text described here, and an unexpected error is
always the fixed "remote outcome unknown" text, never the error message.

Each invocation, once allowed (see
[Confirmation of changing calls](#confirmation-of-changing-calls)), captures the
saved connection, runtime, tunnel configuration and
one operation-owned ordinary SSHJ client; no client exists before the owner's answer. It never substitutes a Terminal/Files
client or re-resolves a replacement after target loss. Operation-local observers
stop owned work on target removal, pending Undo, profile loss or tunnel-plan loss;
Undo does not revive that operation. Target loss in a still-authorized Send returns
an explicit failed tool result after actual cleanup: no command before exec, or an
unknown remote outcome after possible exec unless an exit status/signal was already
received. Known exit information is retained, but late stdout/stderr is withheld.
This is distinct from Stop, timeout or owner revocation, whose cancellation and
stale-publication suppression remain unchanged. This is not an atomic inventory/freshness promise
or a claim that grant IDs/local ports are unique across lifetimes.

The assistant's target readiness, target loss and login without a dialog follow
one rule; the Connections tab's "connect without the dialog" uses the same login
rule, and the Terminal, Connections and Files login dialogs derive credentials
from the chosen method the same way.
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

The SSH start check uses the existing Chat authority guard. Client creation,
credentials, connect/auth/exec/read and cleanup run off Main; a start whose
check passed before a commit may begin, is re-checked for
cancellation before the client, after connect, after authentication and after
opening the session, and is then cancelled. Stop, disable, revision, the profile's removal and
timeout (not leaving the chat) disconnect only the owned client, including one
constructed after cancellation, and keep the global slot through actual worker,
readers and cleanup exit.
Timeouts (30 s tool / 120 s of Send active time by default; the confirmation wait is in neither, and has its own 5-minute limit) are cancellation thresholds, not
remote-kill or cleanup deadlines. No automatic command retry/replay or rollback;
an admitted command’s effects may remain uncertain. Commands/output enter the
same bounded encrypted Chat history and configured model requests, not app logs.
Arbitrary remote output can contain secrets and is not
generically scrubbed. App-stored credentials/internal diagnostics are not tool
arguments/results. Remote content remains untrusted data, never new authority.

### Optional public Web tools

This covers Web search (`web_search`), Read page (`read_page`) and Inspect image
(`inspect_image`). Public GET is not guaranteed free of remote effects.

#### Search and static documents

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
Stop or real revocation. There is no cooldown queue. Attempts include failures;
403/429/CAPTCHA blocks search for that
Send and applies a shared minimum 15-minute cooldown, extended by Retry-After
bounded to 24 hours. No silent resume. Process death resets these limits; this
is not durable anti-abuse enforcement. Search targets are links, not evidence
that those target pages were fetched.

Read page accepts public HTTPS HTML, plain text and Markdown; its `url` is at
most 2,048 characters. HTML uses pinned
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
URL policy and start-admission checks.

#### Optimistic same-model images and provenance

Inspect image accepts an explicitly selected public JPEG/PNG under the same
transport/authority/request budgets. At most 2 inspections per Send, 1 MiB per
image, 4 megapixels, `url` at most 2,048 characters, question at most 1,000
characters. Actual MIME/encoding and
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

#### Failures and sources in Chat

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
advances with the next attempt; closing the conversation releases its memory, not the accepted
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

## Verification and non-goals

The app surface includes provider setup, chat, optional public-read tools and
the profile-information and SSH-command tools above, and bounded automatic
working summaries. Tunnel-control, dedicated Files/MQTT/TCP tools, evaluators, permission modes and
subagents remain outside this surface. No permissions evaluator or confirmation
modes beyond the single app-wide switch are shipped. Tunnel control and new
connection tools are covered by the [boundary](#boundary-and-availability) and
[Adding a tool](#adding-a-tool).

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
endpoints across profiles, revoke/start ordering, held HTTP interceptor/body exit with Stop
reaching the exact calls, Stop, module off/on and provider authority changes (a pop of the screen no longer stops a Send, and tests prove the Send goes on), without revoking a Send
for tool-switch-only changes. Synthetic SSH client/owner tests cover saved target/auth/trust, bounded dual streams, honest status, target loss, admission refusal and phase-by-phase cancellation, disconnect thread and actual cleanup under Stop/timeout/revocation; a loopback SSH server test covers disconnect unblocking a blocked exec. The shared Tools binding and component fixtures
include all five tool switches and the confirmation switch, save errors, retained owners and explicit invocation.
Existing kernel tests use fake providers/tools. Adapter tests use synthetic
loopback HTTP responses. Settings/store tests cover committed opt-in,
malformed-field fallback, disable invalidation, credential/root binding,
revisions and read/write/crypto failures. Compose test fixtures cover
committed/busy/error/disposed-callback states, explicit discovery and restored
drafts; a separate Android fixture exercises an ephemeral Keystore alias.
Confirmation tests use the production conversation, tool factory and kernel with a counting fake command owner: no command or client before the answer, each answer, grant scope and resets (including an edited target), Stop/disable/removal during the wait, leaving the chat during it (the card waits and is answerable on return), stale and repeated answers, virtual-time and real-clock five-minute cases (an answer after the limit and a card expired while no screen was shown run nothing) and Send-limit cases, the fail-closed rules for an undescribed changing call, the persisted object and its malformed values. Component checks (chat runner, portrait/landscape/200% font, EN/RU) cover the card, the four answers, the hints, the indicator and the Tools switch.
Chat tests exercise the production conversation owner with controlled preparation,
providers and cancellation, plus the real credential-store → adapter loopback
path. Real-adapter loopback tests prove prompt cancellation (stalled response and
body) and that an exchange held by an application-interceptor latch keeps the caller
until it returns; held-exchange tests of the public transport (stalled body, latched
DNS) do the same. Slot retention across completion, failure, cancellation and authority
changes before explicit retry is proven with a fake provider built on `blockingCancellable`.
Synthetic navigation/Compose checks use the production Settings chat entry,
chat ViewModel and screen: complete answers/follow-up, Stop, recoverable errors,
setup recovery, recreation, Back with an attempt that goes on, and the close of an idle conversation. Owner tests (unit and
component) cover: the reply of an attempt that outlived the screen, a reopen that gets the same attempt and its Stop, grants kept while
the screen or an attempt remains and cleared when both have ended, disable, provider change and tool-off reactions with no screen,
the profile's removal stopping the attempt, the one interrupted note, and the bottom navigation's dot and its TalkBack state. The explicit test-only plain-Application
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
- [Operations layer](contracts/surfaces/operations.md) owns the operations
  under `ssh_command` and `profile_information`, their typed results and the
  rules every adapter keeps.
- [Kernel interfaces and limits](../agent-core/src/main/kotlin/ru/adsrv/frpjump/agent/AgentProtocol.kt),
  [execution](../agent-core/src/main/kotlin/ru/adsrv/frpjump/agent/AgentModule.kt),
  [window selection](../agent-core/src/main/kotlin/ru/adsrv/frpjump/agent/AgentWindow.kt)
  and [cancellation primitives](../agent-core/src/main/kotlin/ru/adsrv/frpjump/agent/Cancellation.kt).
- [External adapter](../agent-openai/src/main/kotlin/ru/adsrv/frpjump/agent/openai/OpenAiAgentProvider.kt)
  and [provider configuration](../agent-openai/src/main/kotlin/ru/adsrv/frpjump/agent/openai/OpenAiConfig.kt).
- [Conversation owner](../app/src/main/java/ru/adsrv/frpjump/assistant/AssistantConversation.kt)
  (public facade; its parts live in the
  [chat package](../app/src/main/java/ru/adsrv/frpjump/assistant/chat/): saved
  document and pure projection/error mapping, run ownership under the runtime
  gate, one Send in `TurnRunner`, per-Send tool list in `AssistantToolFactory`)
  and [chat screen](../app/src/main/java/ru/adsrv/frpjump/ui/settings/AssistantChatScreen.kt).
- Implementation mechanics are documented in KDoc, not in this contract: the
  lock order and which work runs outside the chat gate and store monitors in
  [`AssistantChatRuntime`](../app/src/main/java/ru/adsrv/frpjump/assistant/AssistantChatRuntime.kt),
  [`bindAssistantLifecycle`](../app/src/main/java/ru/adsrv/frpjump/assistant/AssistantLifecycle.kt),
  [`AssistantProviderStore`](../app/src/main/java/ru/adsrv/frpjump/assistant/AssistantProviderStore.kt),
  [`FrpJumpApi.call`](../app/src/main/java/ru/adsrv/frpjump/api/FrpJumpApi.kt),
  [`SshCommandOperation`](../app/src/main/java/ru/adsrv/frpjump/ops/ssh/SshCommandOperation.kt),
  [`AssistantWebTools`](../app/src/main/java/ru/adsrv/frpjump/assistant/AssistantWebTools.kt)
  and [`OpenAiAgentProvider.execute`](../agent-openai/src/main/kotlin/ru/adsrv/frpjump/agent/openai/OpenAiAgentProvider.kt);
  the disable version in
  [`AgentModule.refreshAvailability`](../agent-core/src/main/kotlin/ru/adsrv/frpjump/agent/AgentModule.kt);
  the Tools autosave in
  [`AssistantToolsBinding`](../app/src/main/java/ru/adsrv/frpjump/ui/settings/AssistantToolsBinding.kt);
  the SSH target and login owner in
  [`SshTargets`](../app/src/main/java/ru/adsrv/frpjump/ssh/SshTargets.kt).
- [Provider store](../app/src/main/java/ru/adsrv/frpjump/assistant/AssistantProviderStore.kt)
  and [settings form](../app/src/main/java/ru/adsrv/frpjump/ui/settings/AssistantProviderScreen.kt).

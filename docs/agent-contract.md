# Optional connection assistant

## Boundary and availability

This surface covers the optional assistant kernel, external API adapter and
provider setup and a locally saved chat with optional public-read,
profile-information and SSH-command tools. The owner uses Settings to opt in,
configure one provider and open Chat (also from the bottom navigation and the
terminal menu, see [Entry points](#entry-points)); maintainers can supply provider/tool
implementations to the kernel. The only tunnel-control tools are `start_tunnel` and `stop_tunnel` (default-off switch "Tunnels"). Every
tool is one row of the [tool table](#tool-table) and follows the common
[tool policy](#tool-policy); a tool's own subsection describes only what is
specific to it. `ssh_command` and `profile_information` are assistant adapters
over operations of the [operations surface](contracts/surfaces/operations.md);
the texts and behavior below are unchanged by that layer. Calls of tools that
change something (today `ssh_command`) ask the owner first unless the owner
turned the question off or answered For session for that action class on that connection (and that tool is covered by it); see
[Confirmation of changing calls](#confirmation-of-changing-calls).

The assistant answers the owner's message. The one exception is an event of a background wait the owner's switch "Event waits" let it order
([event waits](#optional-event-waits)): it may then start a reply by itself, visible in the chat, at most the owner's limit in a row without a message
from the owner, and never with the switch off.

What the assistant can never do. Each rule is stated where it applies:

- Tunnels: no tunnel creation, reconfiguration or deletion is granted to the
  agent. Only `start_tunnel` and `stop_tunnel`, behind their own default-off
  switch, start and stop a saved tunnel ([tool policy](#tool-policy),
  [tool table](#tool-table), [tunnel tools](#optional-tunnel-tools)); `ssh_command`
  never starts one.
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
- The bottom navigation's item after the sections (Devices, Tunnels, Sessions), "Assistant" / «Помощник» (a neutral chat-bubble icon). It is shown only while the module is on,
  appears and disappears with the setting without a restart, and opens the chat over the current section; Back returns to that
  section. All the labels stay whole in both languages, in landscape, at 200% font and in a 320 dp wide window (the label size steps
  down to a floor, tracking is tight, nothing is cut). The item opens a screen and is never selected, so it is announced as a button,
  not as an unselected tab. While an attempt runs (a confirmation wait included) the item shows a small dot that takes no room, and
  TalkBack announces its state: "Working" / «Работает», or "Waiting for your answer" / «Ждёт ответа» while a card waits; the dot is gone
  when the attempt ends. The chat has no bottom bar; the dot is shown on the bottom bar of the section screens (Devices, Tunnels,
  Sessions) while an attempt runs, with or without the notification permission.
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
screens that were opened over it) returns to that chat by closing the screens above it, so
its conversation, its saved history and a running Send are never doubled or stopped. A waiting terminal quote is taken by the chat at
once and kept in memory only; it goes after the draft once the saved draft has loaded and no Send runs, and it is dropped with the chat
screen if the owner leaves first (an attempt that goes on does not keep it). If the history cannot be read the chat's own "could not restore history" notice shows and the quote is not added.

Whichever entry opened the chat, the app lock, Stop, "For session" lasting for the tunnel's open period, a disabled
module and an unconfigured provider behave as described elsewhere in this contract, and the provider screen opened from the chat
works from every entry. The assistant never reads terminal tabs by itself: only the opt-in, default-off [Terminal tabs](#optional-terminal-tabs)
tools do, when the model calls them in a Send. It never types into terminal tabs by itself either: only the opt-in, default-off
`terminal_input` does, when the model calls it in a Send, under the common [confirmation for changing tools](#confirmation-of-changing-calls).

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

Settings → LLM assistant → Chat text size sets the size of the chat's text apart
from the system one: 90, 100, 115, 130, 150, 175 or 200 percent (default 100) of the
system font size, applied to the conversation feed only (answers, the user's
messages, the tool block and status lines); the top bar, the message field and
dialogs keep the system size. It is the integer `assistant_chat_text_size` in the
settings file, omitted at 100; a missing, non-numeric or malformed value reads as
100, any other number reads as the nearest step, and no other setting is reset.
It is a display preference only: it changes no prompt, request, permission or
confirmation, and makes no request.

Settings → LLM assistant → Assistant provider configures one external
OpenAI-compatible provider. Chat is available next to provider setup. SSH
commands require their own default-off tool switch; so does starting and stopping a saved
tunnel (creating, reconfiguring and deleting tunnels is **not available**). Enabling the module, opening settings or saving a provider
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

Every limit that bounds what the assistant, its terminal tools, the tunnels it starts or the terminal's reconnect do is an owner's setting with a default and
a range, on the Limits screen (Settings → Assistant → Limits, steppers in four sections), saved in the one `assistant_limits` group of the settings file
(each key read on its own; a file without a key keeps the default). The agent's limits are read at the start of each Send and held for it; the terminal and
tunnel limits are read at use. The kernel's own parameters stay validated (`AgentLimits`); the app builds them from the owner's settings:

| Section | Limit (default; range; step) |
| --- | --- |
| Assistant | One call, s (30; 10-300; 5) = tool timeout (the owner's confirmation wait is not part of it) |
| Assistant | Model answer, s (60; 10-600; 10) = model request timeout (it does not include the confirmation wait either); the provider gets it as the call's own timeout and has no fixed transport timeout shorter than it |
| Assistant | Whole reply, s (120; 30-1800; 30) = the Send's active-time deadline; never less than one call plus one model answer (the larger is used) |
| Assistant | Steps per reply (12; 1-50; 1) and Calls per step (4; 1-10; 1) |
| Assistant | Skills, at most (50; 1-200; 1), Skill size, characters (4,000; 500-20,000; 500), Skills list per request, characters (2,000; 500-8,000; 500) |
| Assistant | Tool output, characters (8,000; 2,000-32,000; 1,000) = the tool-result cap (`ssh_command`, the terminal reads and the `profile_information` pages keep that less 200) |
| Assistant | The history, reply and summary shares of the model's window are not on this screen: they are the «Окно модели» group of the provider form ([Provider settings](#provider-settings-and-credentials)); the reply length cap is derived from the reply share (below) |
| Waits | Waits at once (3; 1-10), Longest wait, min (30; 1-120), Event replies in a row (5; 1-20), Assistant sessions (3; 1-10) |
| Terminal | Input at once, characters (2,000; 100-10,000; 100), Keys at once (32; 1-100; 1), Lines per read, at most (1,000; 100-10,000; 100) |
| Tunnels | Start wait, s (45; 5-120; 5) = how long a tunnel brought up by the owner's Start, "Use key login" or `start_tunnel` is waited for (`start_tunnel` still ends at three quarters of One call); Terminal auto-reconnect, min (10; 1-60; 1) |

The window split is stored in the same `assistant_limits` group, beside the old keys, which an older version still reads: `history_percent`, `reply_percent`,
`summary_size_percent` (kept from the earlier version: the summary share, same meaning) and `reply_limited_from` (tokens; present only while the old answer
reserve is shown as cut). The shares are absent until the owner saves the provider form or the old values are converted, once, at app start when a provider is
saved: reply share = the provider's old answer reserve over its window, rounded up and held in 1-20 (when held, `reply_limited_from` keeps the old tokens and the form
says so under «Ответ модели» until the owner changes it); history share = the old trigger (the `summary_percent` limit when set, else the provider form's old value) less the
reply share, held in 40-90; the summary share is the old `summary_size_percent`; then both rules hold, the history lowered first. The old trigger key and the
provider's `summaryPercent` and `outputTokens` stay in their files as they were, never read again once the shares are set (an older version still uses them; the
form writes `outputTokens` as the reply share in tokens with each save, so it stays a sensible reserve there). `reply_chars` and `fresh_tool_percent` are no longer
written. The reply length cap is derived: max(32,000, reply-share tokens × 4); an owner value above that is no longer kept.

The model's texts that state these numbers (the input, key, line and output limits, the `wait_for` limits) are built from the settings when the tools are described:
at the defaults the words are exactly the ones this contract quotes with the default numbers. The numbers below are the defaults.
Kept as internal constants (mechanics, not limits of what the assistant does): poll and settle intervals, probe and read timeouts, the display truncation of labels and
names, file search, image jobs, the history window's token budget.

### Sliding window, not compression

The kernel builds an active request while retaining the caller's history
unchanged. Instructions, tool schemas, the current user request and output
reserve, and the owner's own instructions when set
([Owner's own instructions](#owners-own-instructions)), count toward the budget. Only a suffix of complete earlier turns is
included. When necessary, whole older exchanges of the current turn are also
left out, never half of a tool-call/result pair. Current user/instructions and
the latest complete exchange are not silently discarded. If those cannot fit,
the run returns context-full without another model call.

Defaults: a 262,144-token (256K) estimated total budget with 13,107 tokens (the reply
share, 5 % of the window) reserved for output. The default counter uses UTF-8 byte lengths plus framing allowances:
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
limited per response to the larger of 32,000 aggregate characters and four characters for each token of the
reply share (the app derives the cap from the window split; the kernel default stays 32,000). A returned String is
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
window and the output-token parameter spelling. It also accepts
an optional summary model ID; blank uses the chat model. The form's one group
«Окно модели» / "Model window" holds the window, then a bar of the whole window and three
steppers, then the summary model. Each stepper is one piece of the bar, in percent of the window:
«История до сжатия» (default 75; 40-90; step 5), «Ответ модели» (5; 1-20; step 1) and «Сводка» (15; 5-40; step 5).
The margin (100 − history − reply) is derived and shown on the bar, not a setting. The model's
reply share is the request's output limit (share × window, at least 1 token); it replaces the old
token field. Two rules keep a 5 % margin: history + reply ≤ 95 and history + summary ≤ 95 (the summary
request reads the whole history); at a limit the «+» is off and one line under that row names the rule.
The bar is the whole window in three calm pieces (history, reply, margin) with a line where the shortening
starts (history + reply) and a dashed mark at the summary's size inside the history («after shortening»),
labels with the shares and tokens, a TalkBack description of the same, and is pinned at the top of the
form's view while the steppers scroll. The shares are the owner's limits, saved with Save after the
provider (a refused save leaves them as they were); the old trigger and answer reserve stored by an older
version are converted once ([Limits](#limits)). The form explains the additional model
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

The application skill is the base system prompt. It is composed, by ONE function (`AssistantPrompts.system`, which every reader
uses): the core, [`app/src/main/assets/assistant/skill.md`](../app/src/main/assets/assistant/skill.md) (the role, the rules every
request needs, the safety and reporting rules, the app-origin line), plus the note of each tool switch that is on in the request,
[`assets/assistant/tools/<switch>.md`](../app/src/main/assets/assistant/tools/) (the setting key without `assistant_`), in the
catalog's switch order at the core's `<tool-notes>` line (version 17). A tool's guidance lives in its own note, so a request with
the tool off does not carry it; the rules that must hold whatever is on (for tunnels, never to create, reconfigure or delete them)
are in the core. The core is at most 6,144 bytes and the prompt with every switch on at most 8,192 (build-time checks, not runtime
limits). The composed prompt is a function of the tool switches alone: it is the unchanged byte prefix of the first message
of every main chat-loop request of a Send, stable within it; without owner
instructions the first message equals it exactly. The owner's own instructions
never edit it; they are appended after it in that same message
([below](#owners-own-instructions)). It is guidance for
the model, not a permission system or an executing script; the code enforces
every limit. The compact summarizer prompt is `assets/assistant/summary.md`
(version 3); summary requests open with it instead. Both are read strictly
(UTF-8, no byte order mark) and there is no compiled-in fallback prompt. Still
compiled into the app are the fixed image-inspection question wording, the
summary request framing text ("Earlier conversation summary", "Previous
summary / Observed conversation"), the fixed owner-instructions header
(`## Owner's additional instructions`), tool descriptions and schemas, and fixed
tool-result texts. No automatic app context is attached. A changed prompt asset
gets a new version and a new pinned hash (one per asset).

Requests contain explicit chat text/history, the base prompt (the core `skill.md` with the notes of the switches that are on,
version 17), the owner's own instructions when set, only enabled tool schemas and,
while some tool is off, the service tool `request_tool`
([below](#asking-for-a-tool-that-is-off)).
The base prompt contains no profile,
connection, date, version or language data (its tool notes are those of the switches that are on, which the request's tool list shows too); the same bytes
open every main chat-loop request of a Send (summary requests open with `summary.md`).
Tool schemas follow catalog order with stable JSON; summary, history and
the current request follow the system message. No provider-specific cache
parameter is sent. A prompt asset that is missing, malformed (not strict UTF-8, or
with a byte order mark) or empty fails the Send as an ordinary setup problem,
with no fallback text and no request. A tool that is not in the request is
asked for through `request_tool`, not by a line of the base prompt. No profiles,
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
  about 1.6% of the default 249,037-token input budget (the 262,144-token
  window minus the 13,107-token reply share), so summaries and omissions
  start earlier. Without text nothing is added.
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
have display-only bold, italic, headings, unordered/ordered (nested) lists,
quotes, horizontal rules, GFM tables and monospaced inline/fenced code through a
Compose Markdown component. A table scrolls horizontally inside its own area,
keeps whole cells (no ellipsis) and is read row by row; a fenced code block
scrolls horizontally instead of wrapping. Table, code and rule colours come from
the app theme. The library owns Markdown parsing, including malformed or nested
input; the one display-only adjustment is a blank line before a table header that
directly follows a text line (never inside fenced code). HTML, links and images
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
opened over it; an entry that has left the back stack no longer counts, even if its state was saved) or while an attempt (a Send, a confirmation wait included) runs. When both have ended it closes: the draft is saved,
memory is released (the "For session" grants are the app's, not the conversation's, and stay); the next opening restores the saved history. Leaving the chat (Back, the
arrow, another section, another app, the lock, activity recreation, the activity finishing) does not stop an attempt: its reply and
results are saved to the history and are there when the chat is opened again. Reopening while it runs shows that same attempt with its
Stop; there is no second conversation and no second admission. The chat's reactions to a disabled assistant, a provider revision, the
busy slot and a tool switched off belong to the conversation, not the screen, and work while no chat screen exists. An attempt is
stopped only by Stop, by turning the assistant off (off/on included), by a provider change that revokes it, by its own limits, by the
server removing this phone's profile (the owner follows that removal itself, so it stops the attempt with or without a screen, and the
REMOVED screen stops it too) and by process death (the existing unknown-outcome note; there is no
foreground service and no journal). Clearing the history is not a stop path: Clear stays unavailable for the whole attempt, a card
wait included; the owner presses Stop first.

**The assistant's notification.** While an attempt runs (a confirmation wait included) and the chat is not on screen, the app shows one
notification per attempt (one id) on its own channel "Assistant" / «Помощник»: without sound or vibration, at default importance so it shows on the lock screen,
no launcher badge; the owner can turn it off in the system. "On screen" means the chat route is resumed and the app is not locked; the app not being visible, another
screen over the chat or the lock all count as not on screen. While it runs the notification is ongoing, titled "The assistant is working" /
«Помощник работает», or "The assistant is waiting for your answer" / «Помощник ждёт ответа» while a card waits, with the action "Stop" /
«Стоп» in both. When the attempt ends while the chat is not on screen the same notification is replaced by a dismissible, auto-cancel one:
"The assistant answered" / «Помощник ответил» for a completed reply, "The assistant could not answer" / «Помощник не смог ответить» for a
failed or timed-out attempt, and "The assistant stopped: no answer from you" / «Помощник остановился: вы не ответили» when a card ran out
with no chat screen attached and ended the attempt. An end that happened while the chat was on screen is not announced afterwards. It is
removed whenever the chat is shown, at once when a Stop is requested (the chat's or the notification's; it is not posted again while the
attempt winds down) and when the assistant is turned off (an earlier final notification goes too), with no final notification after a
Stop or a disable. A notification left behind by a process that died is removed when the app starts, and Stop from it removes it when no
attempt runs. It carries only its fixed title and, while ongoing, the action: never reply text, tool names, targets or commands (its lock
screen visibility is public for that reason, which is also why Stop is always visible there). Without the notification permission nothing is posted and there is no new permission prompt;
the navigation dot remains. Stop from the notification is the chat's Stop, also during a card wait (nothing runs) and from the lock
screen (no authentication: stopping never widens anything); it is idempotent and does nothing when no attempt runs. It is a
`PendingIntent.getBroadcast` to an explicit receiver of this app that is not exported and only calls the owner's `stop()`. A tap opens the
app at the chat, behind the lock when one is set: a `PendingIntent.getActivity` to an explicit, not exported trampoline activity without UI
(excluded from recents, no history) that sets an in-process request and starts MainActivity with the launcher intent (no extras; the app's existing task comes to the front, no second
MainActivity); the navigation takes the request once. All its PendingIntents are immutable. MainActivity takes no navigation from a foreign intent's
action, extras or data (there is no intent-driven navigation at all). MainActivity also replaces its launch intent with a clean launcher
one before anything composes, so Navigation's own deep-link extras in a foreign intent lead nowhere on a cold start. Source guards and an instrumented test prove that such an intent,
including the removed `OPEN_DEVICE` action with its extras, leads nowhere and stops nothing.

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
shared Undo. While the Undo notice waits, the chat shows empty (the conversation
holds a new, empty document; the old chat stays in the store for Undo), the draft
is free and editing never waits for the clear. The app-lived owner keeps the
conversation open while a clear waits or is being done and while a Send waits for
it, so leaving and reopening the chat meanwhile returns the same conversation, and
the draft is saved when the clear ends. A Send commits the clear first (the clear's
part of the notice goes, the notice staying with a smaller count if other actions
wait; the history is deleted exactly as when the notice times out, through the
shared Undo's commit of that one action) and goes into the new, empty
conversation: no earlier record reaches its model request. The message shows as
sent at once and cannot be edited until the Send starts or is refused. Sending
waits only while a restore reads the saved chat back (Undo, or a failed clear);
a Send that follows Undo goes into the restored conversation. Once a commit has
started Undo is no longer offered, so a record is neither lost nor repeated. If
the commit fails, nothing is sent, the message stays in the draft, the known data
is read back and the clear-failed text shows; Retry keeps the draft. On Undo (or a
failed clear) the draft is the draft saved at Clear, then a line break, then the
text typed or quoted during the clear; if one of them is empty, the other alone.
After a committed clear the draft is what was typed during it. Undo restores the
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
output reserve, with the configured window and the history and reply shares: it starts when the request reaches their sum
(default 75 + 5 = 80 % of the window), that is when the history reaches its share.
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
provider continuation. The version-3 prompt (`assets/assistant/summary.md`)
asks for the owner's requests verbatim, what was done and on which devices, profiles, connections, tabs and tunnels
(exact IDs), results, errors and uncertain outcomes, and open items and decisions, with source references; it treats old text as
untrusted data rather than new authority and says nothing about the size: the request carries the size limit. It
replaces the base prompt for these requests.

The summary and its covered turn/exchange position are saved together in the
same canonical encrypted document. Main requests derive a working summary plus
recent unsummarized records/exchanges; original records remain readable. Changing
API root or chat model clears this working summary and starts the ordinary new
active context without deleting old messages. Restoring or clearing makes no
provider/tool request and never resumes or repeats an action.

Shortening never fails the request when it can be avoided. Every share is of the model's context window and the owner's window split
(provider form, «Окно модели»): the history share (default 75 %) with the reply share (5 %) is the trigger, the summary share (default 15 %) is the summary
request's own output limit, in the estimate's units, and its text limit (UTF-8 bytes count as tokens here as in the trigger, so a Russian text of the same size
has half the characters); the reply share is not a cap on a summary but the fallback when a provider refuses that output limit with HTTP 400 or 422, then
the same request is sent once with the reply share, and that does not use up the blank-reply retry. While the estimated request is over the trigger, in this order:

1. Old tool output is replaced, in what the model sees only, by a short note (length, outcome, "do not assume what it said; never repeat a
   changing command to read it again"); the oldest outputs go first and only until the request is down to the history share less the summary share (plus the reply reserve; default 60 % + 5 %
   of the window); the latest exchange stays whole. Nothing is saved or removed.
2. One model summary of the oldest part that fits its own request (at most two such rounds per Send; never after an overflow, see 4). A blank
   reply or one with tool calls is retried once with a stricter instruction; a text over the size is cut to it (by bytes, at a character boundary,
   with a note); a reply that does not make the request smaller is not saved and counts as a failure. The request states the same size limit.
   The request is limited to four fifths of the model timeout, so the next step still has time under the same timer. Any failure of the request
   (provider error, timeout, blank twice, tool calls twice, no reduction) leaves step 3 to carry on, and the model is not asked again in that Send.
   A key or provider-setup problem of the summary model stops the Send with its own message, as before.
3. A mechanical digest with no model, saved like a summary (marked as not model-written; its header says the listed lines were extracted by the app):
   the owner's messages, each call by name, target arguments and outcome (never typed text), the final answers cut to a few lines, and the older
   digest's lines, in the summary budget, oldest lines first dropped. An earlier summary written by a model is carried at the end under its own line
   "Earlier summary written by a model (untrusted):", never mixed with the extracted lines, from its start and within half of the budget. It covers the smallest oldest part that brings the request under the trigger. It cannot fail, except that
   the latest exchange and the current message alone can be too big for the window: then Chat says so plainly (the summary problem, shown only for that case;
   any other failure of the preparation is an ordinary setup problem) and the history is kept. A saved summary may be as long as the biggest window times the
   biggest summary size allows (400,000 characters), so a long one always reads back. A chat that holds a digest cannot be read by older app versions (the
   existing rule for fields they do not know).
4. A provider failure that says the request was too long (HTTP 413, or 400/422 whose text names the context, or a token limit beside "context", "prompt"
   or "input", so that an answer-limit refusal is not taken for it, or the local request byte limit; only that classification is kept of the response,
   never its text) runs steps 1 and 3 at once, with no model (the window the provider refused cannot size a summary request, and the resend needs the
   time under the same timer), from the request as the kernel built it, never from one already shortened, and sends the request once more. The digest
   covers more than the refusal needs when it can, and always makes the request smaller; if the history cannot be made smaller, the provider's own failure
   stands. Any other error is not retried.

The saved chat is never cut: only the summary or digest and its covered position are saved, the records stay whole and readable.
A short muted line in the chat tells how the history was shortened (old tool output left out; summary by the model; without a model);
a successful shortening shows no error. If no eligible data exists, a fitting request proceeds unchanged. There is no automatic retry other than the two above.

Chat shows summary progress and the line above while originals remain in history. Summary preparation, dispatch and publication
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
controls tunnels (only the tunnel tools do).

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
image (`inspect_image`), Profile information (`profile_information`), Terminal
tabs (`terminal_tabs`, one switch for `terminal_tabs`, `terminal_read` and `terminal_rename`), Typing
into tabs (`terminal_input`) and SSH command (`ssh_command`). They default off on new installs/upgrades; malformed new
values default independently without resetting unrelated preferences. Saving
only booleans, opening this screen or enabling a tool by its switch makes no
request (the chat's "Turn on" button, below, then sends the owner's fixed
message as a new Send). Failed
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

A tool is turned on only by the owner: by its switch in Tools, or by the "Turn
on …" button under a reply that asked for it. That button writes the same
persisted switch, then sends the owner's fixed message. `request_tool` runs
nothing and grants nothing by itself. "No thanks" turns nothing on and sends
nothing. `request_tool` is a row of the tool table without a tool switch of
its own: the separate "Offer to turn tools on" switch (below its tools, on by
default, saved like the others) governs it. While that switch is off,
`request_tool` is in no request (a model without tool calling then works with
plain chat) and no "Turn on" buttons show; its hint says it is for models that
do not support tools. Like the tool switches it is captured at Send. A
provider error about the tools field is not turned into a special hint: the
adapter drops error bodies by design, and a bare HTTP 400 does not tell the
cause.

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
they are read at each call, and a tool switched off loses its grant at once). They neither cancel work nor clear history, provider
or draft. There is no tool-change epoch/revocation signal. Failed saves retain
prior settings. Proposals unavailable in that Send's captured set return bounded
explicit unknown-tool results without network; they do not grant authority.
Public content is acquired only through explicitly enabled tools; completed
conversation history remains after tools change.

### Asking for a tool that is off

While the assistant is on, "Offer to turn tools on" is on and at least one tool
is off, requests carry the service tool `request_tool(name, why)`; with every
tool on, or the offer off, it is absent. `name`
is an enum of exactly the tools that are off now, and `why` is one short
sentence (at most 300 characters). The off tools reach the model only by name
and one line each inside `request_tool`'s description, never with their schemas;
a call of an off tool by name still returns the fixed unknown-tool result with
no network. The description is: "Ask the owner to turn on one tool that is
currently off, when the task cannot be done without it. Give the reason in one
short sentence. The tool stays off in this request: finish your answer and say
what you would do with it. The owner may turn it on and continue. Tools you can
ask for: `<name - one line>; …`." `request_tool` runs nothing, changes no
setting, makes no network request and returns at once with a fixed text:

- first ask for a tool in this attempt: `The owner sees a button to turn on
  <tool>. It is off in this request.`;
- a repeat ask for the same tool in the same attempt: `Already asked in this
  request. Do not ask again.` (no second button);
- after the owner pressed "No thanks" for that tool in this conversation:
  `The owner declined <tool> in this conversation. Do not ask again.` (no
  buttons);
- an invalid call (a `name` that is not a string, is not one of the tools that
  are off now, or is a tool that is on; a missing `why` or `name`; an extra
  argument; a `why` that is blank, over 300 characters or has a lone
  surrogate): `Provide name (one of the listed tools) and why (one short
  sentence) only.` (outcome failed). In receipts a `request_tool` call is titled
  "Request to turn on a tool".

The tool stays off for the rest of that request ("Tool set captured at Send"
is unchanged).

Under the reply of the latest completed attempt that asked, Chat shows two
buttons per asked tool: "Turn on “<tool>”" and "No thanks", one line with the
same disclosure warning that Tools shows under that tool's switch (FULL SSH
ACCESS for `ssh_command`) and, when that attempt called a web tool, even if the
call failed or was unknown, the mark "In this attempt the assistant read web
pages". "No thanks" is announced with the tool's name ("No thanks: <tool>").
`why` is never shown with the buttons; like every call argument it stays
visible in the call's receipt. The buttons are not shown while an attempt
runs, while the assistant is off, when the tool is on (they also go when it is
turned on in Tools) or declined, after a new Send, or after a history clear;
they are not saved, they follow from the saved reply and result.

- "Turn on" writes the same persisted switch as Tools (the same save path, no
  network), and only when the switch is on sends the owner's message "Turned on
  “<tool>”" as a new Send, so the assistant continues with the tool on. The typed
  draft is left as it is, and a fixed message that could not start (a Send
  runs, the history loads or a clear waits) is neither sent nor put into the
  draft. If the switch could not be saved, the tool's save error shows near the
  buttons, the buttons stay and nothing is sent. A changing `ssh_command` call
  still asks for confirmation as before while the confirmation switch is on.
- "No thanks" hides the buttons; nothing is turned on and nothing is sent. The
  decline is saved with the conversation (it survives screen recreation and
  restart, like the history) until the history is cleared or the tool is turned
  on, also when it is found on after the history loads. A chat saved before
  this feature reads as no declines; a chat with none is written exactly as
  before. An app version before this one cannot read a chat that stores a
  decline (as for the `declined` outcome above); this version reads one without.

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
commands") is off or a For session grant covers that call's tool on that call's connection.
The effect is the tool's catalog effect; for a tool built on an operation the
operation's effect, action class and target are used, and tests pin its effect and class equal to the
catalog's. Every catalog tool and operation has an action class (`PUBLIC_READ`, `CONNECTION_READ`, `CONNECTION_CHANGE`, `APP_READ`,
`APP_CHANGE`) and cannot be built without one; the class never decides by itself whether a call asks (that is the effect, the tool's
`confirms` flag, the switch and a grant), and a tool with no question stays one whatever its class. The card's tool line reads
"class · tool" ("Changes in connections · SSH command" / «Изменения в подключениях · Команда SSH»; "Changes in the app" /
«Изменения в приложении» for the app-changing tools). Today the changing tools are `ssh_command`, whose target is a
saved connection, and `terminal_input`, whose target is the saved SSH connection that matches the tab's profile, device and port (none when
no saved connection matches: the card then has no For session answer). Calls whose effect only reads never ask. The default is on.

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
full-access warning. For `terminal_input` the card names the tab (`profile · user@device №number`, or `tab <id>` when it is not known),
the program in the tab's foreground when a `terminal_tabs` probe of that tab is at most 60 seconds old (else no program line), the text
(the same scrolling box and escapes; every line break is shown as a highlighted ⏎ mark, because it is typed as Enter) and the keys by
name, with its own title, warning and For session hint. Nothing is cut: the whole text is in the scrolling box. A string argument with an
unpaired surrogate is invalid for every tool (no card, nothing runs).

Answers: **Allow** (this call), **For session**, **Always**, **Decline**. For
session is a grant of the action class on one connection: `ssh_command` and `terminal_input` share the `CONNECTION_CHANGE` grant of a saved
connection, and a grant covers only the grantable tools that the card said it covers (the hint on the card names them: "Commands in this
connection while its tunnel is open." / "Typing into tabs of this connection while its tunnel is open." / both) and that are still in effect;
a tool that was not in effect when the card was raised is never added by switching it on later. `grantable` is a property of the tool, not of
its class: `terminal_close`, `stop_tunnel`, `skill_save` and `skill_delete` are not covered by any grant whatever class they share, and a
card of theirs has no For session answer. The grant lives for the open period of the connection's tunnel: it survives leaving the chat,
closing the conversation and the end of an attempt, and ends, each at once, when
- the tunnel is disconnected (the owner's Disconnect, the assistant's `stop_tunnel`, an error that removes it, Disconnect all, the server removing
  this phone's profile) and connected again, or a Disconnect is pending, or the tunnel is hidden for Undo, also when Undo then cancels it, or a
  different grant id takes its place (a case that cannot be told apart ends the period); a transient change (frpc reconnect, via-server/direct,
  a local-port rebind) does not end it;
- the connection or its profile is edited, deleted or forgotten, or its enrollment is replaced: the fingerprint is every field of the
  connection, the profile's id, key alias and activation, the enrollment, and the non-secret SSH login (user and method) and the pinned host
  key of its device and port (no secret is read and no Keystore unlock is needed); the profile's name and colour are not in it;
- the assistant is turned off (off and on again included);
- a tool's switch goes off (or it falls out of effect: Typing goes off with Terminal tabs): that tool leaves the grant for good, an emptied
  grant is dropped, and switching it on again does not bring it back, even if no one looked in between (each switch-off is counted and the
  grant remembers the count);
- the chat history is cleared;
- the app lock engages, or would engage the moment the owner returns (Settings › Lock after has run out while the app is out of sight): all
  grants end, so no turn, a wait- or event-triggered one included, runs changing calls under a session grant after the lock. With the app lock off
  nothing changes. This is read at every use, not only when the lock changes.
Rename and colour of the profile, a transient reconnect, leaving the chat and closing the conversation end nothing. A grant is bound before
the question is shown (the tunnel's period, the fingerprint, the assistant's off count, the tools' switch-off counts), so a close, an edit or
a switch-off during the wait makes the answer act as Allow once: no grant is recorded and no line says so. An app-level cleaner
(independent of any conversation) drops a stale grant as soon as it goes stale and the connection card never shows a stale line; every use re-checks
all of it as well (fail closed). A For session grant is in memory
only, never in the history, receipts, logs or backups. Always turns the app-wide switch off after a second confirmation (a dialog
says what is turned off and where to turn it back on; Cancel returns to the
question, still waiting); if that cannot be saved, the call is allowed once, an
inline note says so, and requests stay on. Decline, and five minutes without an
answer (measured in real time, device sleep included), give the model a fixed text (`declined` outcome) and nothing runs. A
declined call is saved as `DECLINED` (sent to the model as `declined`) and shown
as "Declined", or "No answer, declined" when the wait ran out, never as a
failure. In "Tool calls and results", consecutive calls of one tool are one row with a counter ("Profile information ×2", read by TalkBack as "..., 2 calls"); each call shows a short detail taken from its saved arguments (SSH command, search query, page host and path, image name, profile query), cleaned to one line of at most 40 characters with control, bidi and invisible characters removed and never clickable. Older saved turns carry the same arguments; a call without a detail shows its tool name only. Stop, disable, provider changes and the removal of this phone's profile during the wait cancel it and
run nothing; a late answer or a stale card id is ignored. Leaving the chat does not cancel the wait: a card raised while the chat is
not shown waits, is shown in the chat when the owner returns and never over other screens, and the lock leaves it waiting behind it.
The five minutes run meanwhile, in real time with device sleep included: an answer after them is never run (the card ends as "No
answer, declined" and nothing runs). A card that runs out (measured at the moment of expiry) while no chat screen is attached ends the
whole attempt as Stop does: nothing runs, no later question is raised, the turn is saved as interrupted with the call's "no answer" result
and shows its own grey line ("The question was not answered, so the assistant stopped. Nothing more was run."), not the interrupted note,
and the conversation closes once no screen remains. With a chat screen attached, "No answer" only declines that call and the model goes on; a
card whose screen came back before the expiry keeps waiting. The model-facing declined text is unchanged. An Always answer may
still persist "off" when Stop races the call, and a For session answer racing
Stop may record its grant; the call itself never runs. A For session answer is not recorded when the tunnel's period ended, the connection
changed, the assistant was turned off or the tool was switched off during the wait. The wait is in neither
the 30 s tool, the 60 s model nor the 120 s Send time (the Send limit counts
active time only).

The Chat top bar has one shield icon (in place of a Tools button) that opens the
Tools screen at once: a check-mark shield while requests are on, a struck-through
muted one while they are off, described to a screen reader as "Tools.
Confirmations on" / "Tools. Confirmations off". It is a quiet state mark, not an
alarm, and it adds no way to turn requests off or on from the chat: the
confirmations switch is the first item on Tools; there is no banner, strip or
dialog. Fail closed: a changing tool with valid arguments
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
fetched pages, search results, image responses, terminal screens and the
profile information read from servers alike. The per-tool rules are in
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

Action classes (catalog `actionClass`; the class never decides by itself whether a call asks): `PUBLIC_READ` (`web_search`, `read_page`,
`inspect_image`), `CONNECTION_READ` (`profile_information`, `terminal_tabs`, `terminal_read`), `CONNECTION_CHANGE` (`ssh_command`, `terminal_input`,
`terminal_open`, `terminal_close`), `APP_READ` (`read_skill`, `skills_list`), `APP_CHANGE` (`skill_save`, `skill_delete`, `start_tunnel`,
`stop_tunnel`, `terminal_rename`, `terminal_show`, `wait_for`). Tools that ask: `ssh_command`, `terminal_input`, `terminal_close`, `skill_save`,
`skill_delete`, `stop_tunnel` (when the tunnel is on); `confirms = false` tools (`terminal_open`, `start_tunnel`, `terminal_rename`, `terminal_show`,
`wait_for`) never ask whatever their class. Grantable (a For session grant may cover them): `ssh_command` and `terminal_input` only.
`request_tool` is a service tool with no class.

| Tool | Effect | Operation | Arguments | Limits | Confirmation | Disclosure | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `web_search` | `READ_REMOTE` | none: public transport | `query`, at most 256 characters | at most 5 results; 3 searches per Send and 20 per hour, 10 s between attempts; block cooldown at least 15 minutes; shared public budget | never | the query goes to DuckDuckGo; the results go to the model | optional, default off |
| `read_page` | `READ_REMOTE` | none: public transport | `url`, at most 2,048 characters: public HTTPS, port 443 | 8,000 text characters, 100 links and 20 image references per page; shared public budget | never | selected public text goes to the model | optional, default off |
| `inspect_image` | `READ_REMOTE` | none: public transport and a same-model image request | `url`, at most 2,048 characters; `question`, at most 1,000 characters | 2 per Send; JPEG/PNG up to 1 MiB and 4 megapixels; 512 output tokens and 4,000 response characters; shared public budget | never | the selected public image goes to the configured model; token charges include image analysis | optional, default off |
| `profile_information` | `READ_REMOTE` | `profile_query` | optional `query` and `profile_id`, each at most 256 characters; optional nonnegative integer `offset` | one ordinary device-list pull per call; whole-record pages under the 8,000-character result cap | never | private profile, device and connection information goes to the model | optional, default off |
| `terminal_tabs` | `READ_REMOTE` | none: app state, and for tmux tabs one probe on the tab's own connection | no arguments | the owner's open (not hidden) terminal tabs; the call returns within 3 s of the probe start, probes in parallel; about 7,800 characters | never | tab ids, profile, device, connection and user names, state and the foreground program go to the model | optional, default off (shared switch with `terminal_read`) |
| `terminal_read` | `READ_REMOTE` | none: the tab's own connection, or its terminal buffer | exactly `tab_id` (integer from 1) and optional `lines` (integer 0 to 1,000, default 100) and `from` (a cursor from an earlier read or input) | about 7,800 characters, newest lines kept, a cut ends with `truncated: N of M characters`; a read over a connection returns within 10 s | never | the screen and history of the tab, which may contain secrets, go to the model | optional, default off (shared switch with `terminal_tabs`) |
| `terminal_rename` | `CHANGE_LOCAL`, no confirmation (flag `confirms = false`) | none: the tab's label in this app only, remembered app-privately for tmux tabs like the owner's own rename; no connection, session or device is touched | exactly `tab_id` (integer from 1) and `name` (at most 24 characters, one line; empty restores the automatic name) | the owner's tab-name rule; unknown, hidden or closed tabs are refused | never: an app-local label the owner can change from the tab's menu, shown with the tab's user and device on every card, call line and receipt | the name is model text shown to the owner; `terminal_tabs` shows it to the model | optional, default off (shared switch with `terminal_tabs`) |
| `wait_for` | `CHANGE_LOCAL`, no confirmation (flag `confirms = false`) | none: a background wait in the app's memory over tab and tunnel state, the tab's output source and the clock | `event` (`tab_connected`, `tab_closed`, `tab_text`, `tab_quiet`, `tunnel_on`, `tunnel_off` or `time`, only the kinds available now), `minutes` (1 to the owner's longest wait), `tab_id` or `profile_id` and `connection_id`, `text` (`tab_text`, 1 to 200 characters, one line), optional `from` (a cursor, `tab_text` and `tab_quiet` only) and `seconds` (`tab_quiet` only, 1 to the owner's longest wait in seconds, default 10) | at most the owner's number of waits at once | never: it changes only this app, the owner sees every wait and can cancel it | the tab's or tunnel's name is shown to the owner; an event message (never the screen) goes to the model | optional, default off (switch `assistant_waits`; tab events need Terminal tabs, tunnel events Profile information) |
| `terminal_open` | `CHANGE_REMOTE`, no confirmation (flag `confirms = false`) | a session of the assistant's own: a tmux session on a saved SSH tunnel's device (that is on and ready; none is started), logged in with the tunnel's stored login (no dialog, no password), no tab for the owner | `profile_id` and `connection_id` (from `profile_information`) | at most the owner's «Сессий помощника» at once (default 3, 1 to 10); the device must have a working tmux | never: it changes nothing on the device but a tmux session, and shows in the chat and in Sessions | `Session opened: tab_id <id> (<user>@<device>)…` | optional, default off (switch `assistant_sessions`; works only with Terminal tabs and Typing into tabs on) |
| `terminal_show` | `CHANGE_LOCAL`, no confirmation (flag `confirms = false`) | none: the owner is asked (a card in the chat, a notification while the chat is not on screen) | `tab_id` of one of the assistant's sessions and `reason` (1 to 80 characters, shown to the owner as plain text) | one ask per session at a time | never: it only asks | the reason is model text shown to the owner | same switch as `terminal_open` |
| `terminal_close` | `CHANGE_REMOTE` | the tmux session ends, with anything running in it | `tab_id` of a session the assistant opened | only the assistant's own sessions: an owner's tab is refused | asks like every changing tool; no session grant; the call is pinned to the session of the moment it was described | none | same switch as `terminal_open` |
| `read_skill` | `READ_LOCAL`, no switch, no confirmation | none: app state | exactly `name` (1 to 40 characters) | the body of one skill that is in effect now; offered only while at least one skill is in effect | none | the skill's text goes to the model | active |
| `skills_list` | `READ_LOCAL`; owns the switch «Скиллы» | none: app state | no arguments | every skill, one line each, within the tool-output limit | none | names, descriptions and sizes go to the model | active, off by default |
| `skill_save` | `CHANGE_LOCAL` | none: the owner skills file | exactly `name`, `description`, `requires`, `body` (see Optional skills) | at most the owner's skill size and skill count | common confirmation (the card shows the whole skill; no session grant) | the skill text is shown to the owner | active, off by default |
| `skill_delete` | `CHANGE_LOCAL` | none: the owner skills file | exactly `name` | an owner skill; a built-in one cannot be deleted | common confirmation (name and description) | none | active, off by default |
| `terminal_input` | `CHANGE_REMOTE` | none: the tab's own terminal input; for tmux tabs one cursor read on its connection, which also answers whether the pane is in copy mode | `tab_id` (integer from 1); optional `text` (1 to 2,000 characters) and `keys` (1 to 32 of `enter`, `tab`, `escape`, `backspace`, `up`, `down`, `left`, `right`, `home`, `end`, `page_up`, `page_down`, `ctrl_c`, `ctrl_d`, `ctrl_z`, `ctrl_l`), at least one of them | one transaction on the tab's connection; returns at once with `cursor`, taken before typing (it waits for nothing and returns no screen) | asks, unless requests are off or a For session grant covers the tool and target; the target is the tab's saved connection when one matches | what is typed goes to the model (the screen is read separately, with `terminal_read`) | optional, default off; in effect only while Terminal tabs is on too |
| `ssh_command` | `CHANGE_REMOTE` | `ssh_exec` | exactly `profile_id`, `connection_id` and one nonempty `command` | stdout and stderr together: about 7,800 characters inside the 8,000-character tool-result cap, stderr at least 1,500 when present, drained, with a truncation note per stream | asks, unless requests are off or a For session grant covers the tool and target; the target is the saved connection | FULL SSH ACCESS; commands and output, which may contain secrets, go to the model | optional, default off |
| `request_tool` | none: service tool, only while some tool is off and "Offer to turn tools on" is on | none: runs nothing | exactly `name` (an enum of the tools that are off) and `why`, at most 300 characters | fixed text only; no network | never; it grants nothing: the owner's "Turn on" button is the only way it leads to a tool being on | none: the request itself carries only the name and one line of each off tool | always offered while a tool is off |
| `start_tunnel` | `CHANGE_LOCAL`, no confirmation (flag `confirms = false`) | `tunnel_up`, the screens' own operation: brings the saved tunnel up when it is down and waits until the device takes connections; the whole wait, the device's listening included, ends at three quarters of the core's tool time limit (`AgentLimits.toolTimeoutMs`), so a slow start is answered, not timed out | exactly `profile_id` and `connection_id` (strings, at most 256 characters) | one tunnel; no retries | never: it changes only this app and the owner can stop it from the Connections list | the tunnel's name goes to the model | optional, default off (switch `assistant_tunnels`, shared with `stop_tunnel`) |
| `stop_tunnel` | `CHANGE_LOCAL` | `tunnel_down` for one tunnel at once, with no Undo (the card is the check): its terminal and MQTT sessions close (Files screens stay open and show the tunnel down), tmux sessions on the device stay | exactly `profile_id` and `connection_id` | one tunnel; acts only on the tunnel that was described | as every changing tool: asks when "Ask before changing commands" is on, then runs at once | the tunnel's name goes to the model | optional, default off (shared switch) |
| tunnel creation, reconfiguration, deletion | n/a | none | n/a | n/a | n/a | n/a | not available |

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
operation as Tunnels refresh, then reads the application's known state.
Normal authenticated server requests, feature checks and cache writes still
apply. Each information-owned request's start check is serialized with the
existing Chat authority guard. A start whose check passed before a commit may
still begin and is then cancelled. Cancellation signals only those owned calls (the
exact HTTP call) and keeps the shared Chat slot until each HTTP
exchange, including body read and close, has returned. Ordinary UI/background
requests use the same call path without the start check; a cancelled caller waits
for the actual exit of its HTTP call. It starts no extra tunnel poll, probe,
connection or control operation.

Devices, Tunnels and the tool consume the same derived application API over
existing profile/device/connection/tunnel/Undo owners. Complete device lists are
separate from connection rows, including devices without saved connections.
Profile IDs distinguish repeated device names. UI selection, grouping/order,
authentication decoration, actions, visibility and polling lifetimes stay with
the existing screens/runtime; the API introduces no inventory cache or mirror.

Selection searches the complete available source before paging. It reuses the
Devices/Tunnels NFC/Locale.ROOT substring comparison over device names/labels,
connection names and profile names; exact profile/connection IDs also match.
A matching profile includes its records, a matching device includes its connection
variants. Several suitable results are legitimate: active tool guidance requests
a concise answer distinguishing profiles/devices/connections, not a silent first
choice or an unnecessary clarification. Exact saved action IDs remain available.

Results are whole-record pages of at most the owner's tool-output limit less 200 characters (7,800 by default), so the kernel cap never cuts a page in the middle of a record.
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

### Optional terminal tabs

One independent default-off switch, "Terminal tabs", turns on two read-only
tools, `terminal_tabs` and `terminal_read`, and `terminal_rename` (below); the Tools screen and `request_tool`
show it as one entry (`terminal_tabs`: "read and rename the owner's open terminal tabs").
The two reading tools have the catalog effect `READ_REMOTE`, ask for no confirmation and, as the
other reading tools, keep working while the app is locked: nothing they read is
shown over the lock. `terminal_rename` has the catalog effect `CHANGE_LOCAL` and the catalog flag `confirms = false` (the only changing
tool that does not ask): it is an app-local label. The content goes to the configured model provider and may
contain secrets; the switch's hint says so.

- `terminal_tabs` takes no arguments (any key is invalid). It lists the open
  tabs in the order of the tab bar, one line each: `tab_id` (the tab's own id:
  it stays through the automatic reconnect inside one session and is never
  reused, but Reconnect and Change login make a new session, so a new `tab_id`
  in the same place; call `terminal_tabs` again), profile name and id, device, the saved
  SSH connection's own name when one matches profile, device and port, the tab's name (`name`, only when the owner or the assistant set one;
  the same cleaned, `|`-free text as the owner's [tab name](ssh-persist-contract.md#tab-names)), user, tab
  number, state (`connected`; `reconnecting` for connecting, reconnecting and
  host-key questions; `closed`), `tmux: yes|no` and, for connected tabs that run
  the app's own tmux session, the program in the foreground of its active pane
  (one cleaned line of at most 64 characters, no `|`, control or invisible
  characters, so that a remote program name cannot add fields to the record).
  A tmux started by hand inside a tab is not known (`tmux: no`). The program
  comes from one `tmux display-message` on the tab's EXISTING connection, probes
  in parallel; a failed or late probe leaves the program out.
- `terminal_read` takes exactly `tab_id` (integer, at least 1), optional
  `lines` (integer 0 to 1,000, default 100) and optional `from` (a cursor, below). Without `from`: the visible screen plus up to
  `lines` rows of history. With `from`: only the lines printed after that cursor. Every read returns `cursor` for the next one, in its
  header line (`tab <id>: <profile> · <user>@<device> #<number>, tmux|app buffer, cursor: <cursor>`). The output is that header, then the
  text with trailing blank lines cut, limited to the tool-result budget (about 7,800 characters); over it the oldest lines go and the
  text ends with `truncated: N of M characters`. Control and invisible formatting characters are removed.
- One output source per tab, read by cursor (`TabText`, the only code that knows what a cursor is; `terminal_read`, `terminal_input` and
  `wait_for` all go through it, so a read and an event see the same text). A tmux tab's source is tmux's own history on the device, read
  over the tab's existing connection in ONE tmux invocation per read: its commands are chained with `;`, which tmux runs as one list without
  serving the pane's output in between, so the rows and the numbers belong together (also on a tab that prints all the time). Without a cursor:
  `capture-pane -p -J -S -<lines>` (the screen and up to `lines` rows of history, wrapped lines joined, no `-e`), `capture-pane -p -S -3` (the
  physical rows from three above the screen, of which the rows above the terminal's cursor are cut out) and a last line `history_size
  history_limit cursor_y pane_height pane_in_mode`. To place a cursor: `capture-pane -p -S -<W>` (physical rows; a window relative to the
  bottom: one trim of the history, a tenth of `history_limit`, plus the screen's height and three, as last seen, so no question comes first)
  and the same last line; when the window does not place the cursor (it is older than the window, or the match inside is not trusted, below)
  the whole history, `capture-pane -p -S -`, again one invocation, and then the reply if that does not either. Any other tab's source is the app's
  terminal buffer (its history is sized by the «История терминала» setting), copied on the main thread with no more rows than the read needs (from
  three rows above the cursor's row; when rows may have dropped, one trim, a tenth of its capacity, higher; the whole transcript only as the last
  fallback); the rows are cleaned and searched off the main thread. There is no second buffer and no log: the app's output tap
  (`lastOutputNanos`) only says that something was printed. The app does not set tmux's `history-limit` when it creates a session: tmux keeps its own history (its default, normally 2000 lines, or the owner's `tmux.conf`); the owner's "Terminal history" setting is the app's own terminal buffer only.
- The cursor is opaque text to the model (`<row>.<history>.<8 hex digits>`, at most 32 characters): the row the terminal's cursor is on
  (counted in physical rows from the oldest kept one), how many rows of history were kept, and a checksum of the three rows just above that
  row. The row itself is the one still being written (a prompt that is typed on, a progress bar, a half-printed line), so it belongs to what
  comes AFTER the cursor. A read from a cursor places it again, and never guesses: (1) exactly, by its row number, when the source cannot
  have dropped rows since (the app's buffer below its capacity; tmux's history below 90 % of its `history_limit`, because tmux drops a tenth
  at once; and no fewer rows of history than at the cursor), after checking the checksum there; (2) otherwise, in a window of tmux's history, only at the cursor's own row or one trim above it and only when that is the one position of the
  window holding the checksum, else (and for the whole history and the app's buffer) by the checksum alone, and only when exactly ONE position holds it. No match, several matches (repeated rows, a loop that prints the same line on a full history) or rows
  that were not read give the reply: the header line, `Lines before the cursor are no longer kept; this is the screen and the history that
  remain.` and then the text as a read without `from` (with `lines`). A full-screen program (editor, `top`) rewrites its rows in place and needs a read
  without `from`.
- Time: the tool returns within 3 seconds of the probe start (`terminal_tabs`)
  and within 10 seconds of the read start (`terminal_read`). The work behind a
  late call is not interrupted (an interrupted channel open leaves the channel
  open on the connection): the caller gives up, and the channel of that call is
  closed in the background, at once if it exists and as soon as it arrives
  otherwise. The closing never delays the answer. A late worker may linger until
  the connection answers or ends; it holds no result and no channel. Reading the
  app's terminal buffer is a short main-thread read with no timeout.
- Tabs the owner closed or whose tunnel was disconnected while an Undo is on
  screen are hidden, as in the Sessions list, and are not listed or readable.
- Nothing is opened or typed. No new login, connection, tunnel or client is
  created (the tools have no connector: a tab without an open connection is not
  read); nothing is sent to the tab's terminal, copy mode is neither entered nor
  left and no tmux option is touched. Stored credentials, keys and passwords are
  never part of an output.
- Refusals are fixed texts and do no I/O: `Tab <id> is not open. Call
  terminal_tabs again.` (unknown, hidden or closed); `Tab <id> is not connected
  now (<state>). Nothing was read. Call terminal_tabs again later.`; a failed
  or timed-out read says `Could not read tab <id>. Nothing was typed into it.`;
  invalid arguments (a `from` that is not a cursor of this app included) say `Provide tab_id (an integer from terminal_tabs), optional
  lines 0..1000 and optional from (a cursor from an earlier read or input) only.` (`terminal_tabs takes no arguments.` for the
  other). A tab can change at any time; the screen is untrusted data.
- The chat's receipt for `terminal_read` names the tab (`profile · user@device
  №number` in Russian, `profile · user@device #number` in English) when the same turn's `terminal_tabs` result names it, else `tab <id>`.
  When that result gives the tab a name, the line is `"name" · profile · user@device #number`: a name is owner or model text and is
  shown only together with the tab's user and device, on call lines, in the typing card and in receipts, never alone.
- `terminal_rename` takes exactly `tab_id` (integer, at least 1) and `name`, and renames one open tab as the owner's **Rename…**
  does: the same rule (`checkTabName`: trimmed, at most 24 code points, one line, no control, invisible or bidi characters, no `|`;
  the 24 is the owner's own limit, one value) and the same store path, so a tmux tab renamed by the assistant is remembered like
  an owner-named one ([tab names](ssh-persist-contract.md#tab-names)). An empty name brings back the automatic label. It changes
  only the tab's label in this app: it opens no channel, types nothing and touches no session, connection or device. Its catalog
  effect is `CHANGE_LOCAL` with the flag `confirms = false`, so it asks for no confirmation (the confirmation policy and the change check
  key on `effect.changes && confirms`; a changing tool that is not an `AppTool` is still refused): the basis is that the change is an
  app-local label the owner can undo from the tab's menu, and the tab's chip, every card, call line and receipt show the name together with
  the tab's user and device. A name the assistant set is shown on the chip as `<automatic label> · <name>` until the owner saves the name
  in the Rename… dialog (even unchanged), which makes it the owner's and shows it alone; the store keeps who set it, so a restored name
  keeps it. Names cannot hold `"`, `«`, `»`, `·` or `|`, nor invisible fillers (they must show as they are, with a visible character), so a
  name cannot imitate the marks around it; wherever a name is shown it follows the identity (`profile · user@device №n · «name»`), is
  bidi-isolated and is plain text. A tab that is
  unknown, hidden or `closed` is not renamed. Replies (fixed texts): `Tab <id> is now named "<name>".`; `Tab <id> has its
  automatic name again.`; `Tab <id> is closed. Nothing was renamed.`; invalid arguments or an invalid name say `Provide tab_id from
  terminal_tabs and name: up to 24 characters, one line, no control characters.` and change nothing. The chat shows a call line
  `Tab name · "name" · user@device #number` (the name with the tab's user and device from the same turn's `terminal_tabs`
  result, else `tab <id>` alone) and, expanded, `before → after` (each the name or the automatic `user@device #number`) and the reply.

### Optional tunnel tools

One independent default-off switch, "Tunnels" («Туннели», setting `assistant_tunnels`), turns on `start_tunnel` and `stop_tunnel`; the Tools
screen and `request_tool` show it as one entry (`start_tunnel`: "start and stop the owner's saved tunnels"). A saved tunnel is a saved
connection of a profile, named by `profile_information`'s `profile.id` and `connection.id`; the tools never create, reconfigure or delete one,
and `ssh_command` still never starts one.

- `start_tunnel` takes exactly `profile_id` and `connection_id`. It runs the screens' own `tunnel_up` operation, as the owner's Start does, and
  waits until the device takes connections through it. It runs without a question (catalog effect `CHANGE_LOCAL`, flag `confirms = false`): it
  changes only this app and the owner can stop the tunnel. Replies (fixed texts, `<name>` the tunnel's name as the owner sees it, cleaned):
  `Tunnel <name> is on. The device takes connections.`; `Tunnel <name> was already on.`; `Tunnel <name> is on, but the device does not take
  connections yet.`; an unknown, removed or hidden profile or tunnel and bad arguments: `Provide profile_id and connection_id from
  profile_information.`; a tunnel that is being stopped (the owner's Stop waits for Undo, or it is going down): `Tunnel <name> is being stopped. Nothing was started.` (nothing changes, the owner's Undo stays; pending operator approval); a tunnel that did not start within the wait: `Tunnel <name> did not start. Nothing else was changed.` (pending operator approval); any other failure: `Could not start the
  tunnel.` (these two are not in the approved list yet).
- `stop_tunnel` takes exactly `profile_id` and `connection_id`. It goes through the common confirmation: the card's title is "Stop tunnel?"
  («Выключить туннель?»), its identity line `profile · "tunnel name" · device:port` (the name between its marks, bidi-isolated), then, only
  when sessions of the tunnel are open (terminal and MQTT), `N tabs of this tunnel will close. tmux sessions on the device stay.` Allow, Always and Decline are the
  common answers; there is no session grant for a stop (`stop_tunnel` is not grantable although it shares the `APP_CHANGE` class). After Allow it runs `tunnel_down` for that one tunnel at once with no 5 s Undo, as the
  card is the check; the owner's own Stop keeps its Undo. Its terminal and MQTT sessions close with it; tmux sessions on the device stay
  ([SSH persistence](ssh-persist-contract.md)). The call is pinned to the tunnel that was described (the saved tunnel, its profile and enrollment,
  and the running tunnel instance): when it was removed, edited, restarted or is already stopping, nothing is stopped (`Tool was not authorized;
  nothing was executed.`, or the "Provide…" reply when it is gone). A stop of a tunnel that is unknown, hidden or not on (also one already stopping) asks nothing and shows no card: it replies `Provide profile_id and connection_id from profile_information.` or `Tunnel <name> was not on. Nothing was stopped.` The at-once stop counts as going down from its start until the tunnel is down, so a start in that window is refused as above. Replies: `Tunnel <name> is off. Its terminal and MQTT sessions closed; tmux sessions on the
  device stay.`; `Tunnel <name> was not on. Nothing was stopped.`; declined and unanswered questions use the common texts; any other failure:
  `Could not stop the tunnel.` (not in the approved list yet).
- The chat's call lines are `Start tunnel · profile · "name"` and `Stop tunnel · profile · "name"` (the names as the received
  `profile_information` facts hold them, the profile before the tunnel's name, which is plain owner text, bidi-isolated).

### Optional event waits

One independent default-off switch, "Event waits" («Ожидание событий», setting `assistant_waits`), turns on `wait_for`. Tab events are offered only while "Terminal tabs" is on and
tunnel events only while "Profile information" is on: the schema's `event` enum lists the kinds available at that Send. The tool registers one
wait and returns at once (`Waiting for <what> until <HH:MM>. Finish your answer; a new turn starts when it happens.`); the model finishes its answer.
Replies: `Already <n> waits, the most at once. Nothing was added.`; `Provide event and its target: tab_id from terminal_tabs, or profile_id and connection_id from profile_information; minutes 1 to <max>.`
The numbers are the owner's limits, read at each Send and never held in a text of the app: **Waits at once** (default 3, 1 to 10), **Longest wait, min** (default 30, 1 to 120)
and **Event replies in a row** (default 5, 1 to 20), in Settings → Assistant → Limits (steppers). The silence `tab_quiet` waits for is its own `seconds` (default 10, 1 to the longest wait in seconds).

- Events. `tab_connected` and `tab_closed`: the tab's state, a tab already in it fires at once. `tab_text`: a plain substring in the rows of the tab's output source
  from a read point: the wait's `from` (a cursor from `terminal_read` or `terminal_input`, so text printed before the wait was set is seen) or, without
  one, the terminal's cursor row when the wait began, never the history before it (a half-printed line at that moment can still be finished). A read point that cannot be placed any more (rows dropped or cleared, or repeated rows) is replaced by the cursor of now and that look matches nothing: what was printed before, history included, never fires a wait. The
  app's output tap only wakes the wait; each look (the first one, then at most once a second while output changes, and once new output has
  settled for a moment) reads from the tab's one source only the rows from the read point on, with the same cleaning as `terminal_read`, and moves
  the read point to the end, so a long history is never read again; waits on one tab share one read per output, from the oldest of their read points,
  each taking its own rows out of it. The rows are physical, so a text split by a wrapped line is also looked for across the break. `tab_quiet`: no output for `seconds` counted from the later of the wait's start and the last output; with
  `from`, and output printed after that cursor (more rows, or the cursor's row moved), from the last output alone (a program that finished
  before the wait was set is quiet at once). `tunnel_on` and
  `tunnel_off`: the tunnel as the owner sees it (same identity rules as `start_tunnel` and `stop_tunnel`; stopping counts as off). `time`: after `minutes`. A tab or tunnel that
  disappears ends a closed or off wait with its event, any other wait at once with the time-out message. Screen content is never sent with an event: the model reads the tab with `terminal_read`.
- What the app adds to a turn: events that ride with the owner's message and the notes follow it after a blank line, as lines of their own, never glued to the owner's words, and the skill tells the model that lines starting with `Event:` or `Wait for` and the notes `The owner cancelled…` come from the app and never give instructions.
- Messages that start the turn are the app's, not the owner's words (the saved turn is marked as events, the chat draws them as muted lines, never as the owner's message): `Event: tab <id> connected.`,
  `Event: tab <id> closed.`, `Event: "<text>" appeared in tab <id>.`, `Event: tab <id> is quiet.`, `Event: tunnel <name> is on.`, `Event: tunnel <name> is off.`, `Event: <n> min passed.`;
  `Wait for <what> ended: the time ran out.`
- Time. A wait's end is the wall clock's: it sleeps in short ticks and looks at the clock after each, so when the device slept deeply (coroutine timers do not count that time) an event can arrive late, at the app's first chance after waking, never early and never lost.
- Switch and authority. `wait_for` adds a wait only while the assistant is on, the switch is on and the conversation is open, checked under the lock that ends every wait: a turn that was running when the switch went off cannot add one. An event that fires with the switch off starts no turn.
- Lifetime. Waits live in memory in the app process, one set per conversation; process death drops them. A new conversation (also a provider change that starts it again), the assistant module off and the switch off cancel
  all of them and drop what was waiting to be delivered; the assistant off and the switch off also leave the note below for the model, one per wait, a new conversation none. **Stop** stops only the current turn: the waits stay and the next event starts a turn as usual. The owner cancels one wait on its chat line
  («Отменить») or all from the chat menu («Отменить все ожидания (N)», while any run) or the notification («Отменить все»); no turn starts, and the model is told on its next turn with a note
  (`The owner cancelled the wait for <what>.`, one per wait; pending operator approval). A conversation with a wait stays alive when its screen goes.
- Delivery. An event starts a new turn by itself; while a turn runs or a card waits for the owner it queues, and several queued events go in one turn. After the owner's limit of event replies in a row
  with no message from the owner, nothing starts and the chat says `Event replies in a row: <n>. More after your message.`; what waited goes with the owner's next message (which also starts the count again).
- The notification. With the chat off screen and a turn running it is the usual "The assistant is working" with Stop. With waits pending and no turn running it is a silent, low-importance one,
  "Assistant is waiting for events: N" with the action "Cancel all", gone at N = 0. Nothing is shown over the app lock, as for the other notifications.

#### Optional own sessions (`terminal_open`, `terminal_show`, `terminal_close`)

A third independent default-off switch, «Свои сессии» / "Own sessions" (`assistant_sessions`), turns on the assistant's own terminal sessions. It works only while Terminal
tabs and Typing into tabs are on; turning either off turns it off, and turning them on again never brings it back. The Limits screen has «Сессий помощника» /
"Assistant sessions" (default 3, 1 to 10).

- ONE session concept: an assistant session is the same session object, buffer and cursor reads as an owner's tab, opened by the assistant and not shown as a tab. It is a tmux
  session of this install on the device; who opened it is a tmux user option set in the same invocation that creates it, and the session is remembered in the one session-record
  store (see [ssh-persist-contract](ssh-persist-contract.md#session-records)), which is the ONLY owner of which assistant sessions exist: the list, the count and the limit derive from the records, never from the live connections. Nothing else keeps its output.
- `terminal_open` refuses past the owner's limit (`Already <n> sessions of yours, the most at once. Close one with terminal_close first.`), reuses `ssh_command`'s refusals (one shared definition of the texts) for a tunnel that is not on
  or ready and for a login or host key that needs the ordinary SSH UI (nothing is started, no password is asked or typed), and refuses when the device has no working tmux (`tmux is not
  available on the device, so a session cannot be kept. Nothing was opened.`). A session that logs in but whose host key needs the owner's decision is closed, not left waiting.
- `terminal_tabs` lists them with `opened by: you` and `shown: no|yes`, and with state `detached` when remembered from before a restart and not attached yet. `terminal_read`, `terminal_input`,
  `terminal_rename` and `wait_for` work on them unchanged; the first read, input or wait on a detached one (or one whose connection ended) attaches it again over the tunnel's saved login (tunnel on; none is started; ONLY attaches: a tmux session that is gone is not created, the reply is `Session <id> has ended.`; a saved login of another user than the record's is refused with the needs-setup text). The confirmation of
  `terminal_input`, its pins and the password rule are unchanged: the model never types a password, it calls `terminal_show`.
- `terminal_show` asks the owner to act in one of the assistant's sessions: a card «Нужен ваш ввод» / "Your input is needed" in the chat (the session's identity, the reason as plain text
  with the chat's cleaning and cap, and «Открыть» / "Open", which shows the session as a tab), and a notification «Помощнику нужен ваш ввод» / "The assistant needs your input" with Open
  while the chat is not on screen (low importance, no sound; nothing is shown over the app lock). Reply `The owner was asked to act in tab <id>.`
- `terminal_close` goes through the common confirmation (card «Закрыть сессию?» / "Close session?", the identity line, `Всё, что в ней запущено, остановится.` / `Anything running in it stops.`;
  no "For session": `terminal_close` is not grantable although it shares the `CONNECTION_CHANGE` class with the grantable `ssh_command` and `terminal_input`, and a grant of that class never covers it). An owner's tab is never closed by it (`Tab <id> is the owner's; only the owner closes it.`). Reply `Session <id> is closed.`; a remembered session is attached first, and
  when it cannot be reached nothing is closed. Open failure: `Could not open a session. Check terminal_tabs.`; a Stop in the middle closes the half-open session.
- Owner UI: Sessions has a group «Помощник» / "Assistant" (only while there are sessions the owner has not opened as tabs) with each session's label, state and «Открыть» / «Закрыть»
  (his Close: no confirmation, the shared Undo; when its tunnel is off it stays listed and he is told «Не удалось закрыть: туннель выключен»); progress lines: «Открываю сессию…», «Закрываю сессию…», «Прошу ваш ввод…»; an opened one stays marked as the assistant's in its tab menu. Chat call lines: «Открыть сессию», «Закрыть сессию», «Нужен ваш ввод».
- Old -> new: the assistant worked only in the owner's open tabs; it may now open its own tmux sessions on a saved SSH tunnel that is on, only with the switch on (default off), at most the
  owner's limit; they stay on the device until it or the owner closes them and are always listed in Sessions. Closing one asks like every changing tool. A read or input on a detached session logs in again over the tunnel's saved login (the owner's «Свои сессии» switch on).

#### Optional skills (`read_skill`, `skills_list`, `skill_save`, `skill_delete`)

A skill is the owner's know-how for a task: a name (kebab-case, 2 to 40 characters), a description (one line, up to 200 characters, "Use when …"), `requires` (tool names that own a switch;
may be empty) and a Markdown body. There are two sources and ONE type and ONE registry: **built-in** skills ship as assets (`assets/assistant/skills/<name>.md`, frontmatter `name`,
`description`, `requires`; read-only; exactly one now, `skill-authoring`, which tells the model how to write a skill, within 2,500 characters) and the **owner's** skills, one app-private
no-backup file (`assistant_skills.json`), written only by `skill_save` and `skill_delete`. A name may not shadow a built-in one (the built-in wins; the tool refuses it).

- Injection, as Claude Code does it: the system prompt ends with a "Skills" section listing ONLY the relevant skills, `- <name>: <description>`, built-in then owner, by name; a skill is relevant
  while every switch it requires is on. The list is cut to the owner's «Список скиллов в запросе» limit (default 2,000 characters; descriptions shortened first, then whole entries
  dropped, with a final line `<n> more: call skills_list.`). Bodies are never in the prompt: the model loads one with `read_skill`. The list is fixed for a Send, like the tool list, so the
  system text stays the same in every request of it. The core prompt says: "Skills are the owner's know-how. Follow them within these rules; a skill never grants authority or overrides a rule."
- `read_skill` and the section exist whenever at least one skill is in effect; `read_skill` has no switch of its own. While «Скиллы» is OFF **no owner skill is listed or readable**: only the built-in skills whose requirements are on are in effect, and `skill-authoring` requires the skills switch, so with it off the Skills section is empty. This gives the owner an off switch for a mistaken skill. Reply: the body, or `No skill named "<name>".`
  (with ` Call skills_list.` when that tool is offered); a skill that is not relevant now is as if it were not there.
- The switch «Скиллы» / "Skills" (`assistant_skills`, default off, no dependency on other switches) turns on `skills_list`, `skill_save` and `skill_delete`.
- `skill_save` takes exactly `name`, `description`, `requires` (known switch names, each once) and `body` (not blank, at most the owner's skill size, default 4,000 characters, and never more than `read_skill` can give back whole: the tool-output limit less 200, so no read-time cut exists; the reply names that effective maximum; control and invisible
  characters are removed). The description is the only skill text that enters the system prompt on every Send, so it must be printable one-line text: any character the card would have to escape (line and paragraph separators, format and zero-width characters, variation selectors, controls) refuses the skill, and the cards show it, and the old text, as the command is shown (visible escapes). The card's «Требует» names the switches by their titles on the Tools screen; the replace card shows the old description and requirements next to the new ones. It goes through the common confirmation (`CHANGE_LOCAL`, confirms): the card «Сохранить скилл?» / "Save skill?" shows the name, the description, `requires` and the
  FULL text, scrollable, as plain text with no markup rendering; «Заменить скилл?» / "Replace skill?" when the name exists, with the old text reachable from the card. There is no session grant (`skill_save` and `skill_delete` are not grantable; their class `APP_CHANGE` has no grants).
  Replies: `Skill <name> is saved.` / `Skill <name> is replaced.` / the invalid reply `Provide name (kebab-case, 2–40), description (one line, up to 200 characters), requires (known switch names) and body (up to <max> characters).` /
  `Already <n> skills, the most allowed. Delete one first.` (the owner's «Скиллов, не больше», default 50). A built-in name or a full store is refused without a question (`Skill <name> is built in and cannot be changed.`).
- `skill_delete` takes exactly `name`; the card «Удалить скилл?» / "Delete skill?" shows the name and the description. Replies: `Skill <name> is deleted.` / `No owner skill named "<name>".`
  (no question when there is nothing to delete; `Skill <name> is built in and cannot be deleted.`).
- The owner skills file: one that exists but cannot be read is never replaced (writes are refused with `Could not save/delete the skill. Nothing was changed.`, and a failed read is not remembered as "no skills"); an entry that does not parse (an unknown switch name) is not listed but is carried through every write unchanged.
- A skill never grants authority: it is text for the model like the owner's own instructions; every tool it names still goes through its own switch and the owner's confirmation.
- Old -> new:

| Old | New | Basis |
| --- | --- | --- |
| The assistant's guidance is fixed by the app and the owner's additional instructions | + skills: built-in and owner skills, loaded on demand, in effect for owner skills only while «Скиллы» is on; they never override the rules or grant authority | operator 2026-11-15 |
| The assistant writes no persistent owner data except tab names | It may save and delete owner skills only with «Скиллы» on (default off), through the common confirmation showing the full text | same |
| (stored data) none | the owner skills file, app-private, backup excluded, removed with the app's data | same |

#### Optional typing into a tab (`terminal_input`)

A second independent default-off switch, "Typing into tabs" («Ввод во вкладки»), turns on `terminal_input`. It is in effect only while
"Terminal tabs" is on too: with the tabs off the switch is off and greyed and says "Works together with Terminal tabs.", the
tool is not offered, and it leaves every session grant for good (switching it on later does not bring it back). Turning Terminal tabs off also turns Typing into tabs off; turning the tabs
on never turns typing on (the owner does that, or the chat's "Turn on" for `terminal_input`). `request_tool` lists `terminal_input` ("type into the owner's open terminal
tabs") only while the tabs are on (otherwise the model asks for `terminal_tabs` first); the owner's "Turn on" for it turns on only this
switch. The tool has the catalog effect `CHANGE_REMOTE`.

- It takes `tab_id` (integer, at least 1) and optional `text` (1 to 2,000 characters) and `keys` (1 to 32 names from the fixed list in
  the table), at least one of `text` and `keys`; any other key or value is invalid. `text` may hold printable characters, newline and
  tab only: any other C0 control, DEL and every C1 control (U+0080..U+009F) make the call invalid (keys are the way to press them).
  Invalid arguments ask nothing and type nothing.
- It types as the owner would, not as a paste: no bracketed paste, so a newline acts as Enter. `\r\n`, `\n` and `\r` are each one CR, a tab is
  0x09, the text is UTF-8. Keys follow the text in order: Enter CR, Tab 0x09, Esc 0x1b, Backspace 0x7f, Ctrl+C 0x03, Ctrl+D 0x04,
  Ctrl+Z 0x1a, Ctrl+L 0x0c; the cursor, Home, End and page keys are what the tab's emulator sends in its current mode. Everything goes
  to the connection in one handover-protected transaction, like the owner's paste, and never to another connection after a reconnect.
  It is not the owner's input: it does not count as the owner typing (metrics, the new-shell bar, the copy-mode guard).
- It never opens a connection or logs in: it uses the tab's open connection and terminal. A tab that is unknown, hidden or closed is
  "not open", one that is not connected is "not connected now", and neither does any I/O.
- A tmux tab's pane is checked for copy mode LAST before the bytes go: the answer (`#{pane_in_mode}`, the last number of the line) comes from the
  same single tmux invocation as the cursor read, after its capture, within the cursor read's own limit of 3 seconds. In copy mode the owner may be
  scrolling and nothing is typed; the app never enters or leaves copy mode for it. A failed or late invocation types nothing (`Could not read tab
  <id>. Nothing was typed into it.`). Typing depends on the pin and that answer only: the rows are not compared with anything, so a tab that
  prints all the time can always be typed into (Ctrl+C for a runaway program). Other tabs are not asked anything over a connection.
- Pinning: the description for the card records the tab's connection (the client and the input generation, no credentials). If the
  tab's connection is another one when the call runs (a reconnect between the question and the run), nothing is typed
  (`Tab <id> reconnected after the request. Nothing was typed. Read the tab again.`).
- It returns at once and waits for nothing. The result is `Typed into tab <id>. cursor: <cursor>`: the cursor of the tab's output source
  (see `terminal_read`) taken BEFORE typing, in the read that also answers the copy-mode question, so what the input causes is printed after it. The model reads it
  with `terminal_read` (`from`) or waits for it with `wait_for` (`tab_text`, `tab_quiet`, `from`). If the output cannot be read for the cursor,
  nothing is typed (`Could not read tab <id>. Nothing was typed into it.`). If the tab left Open right after the bytes were queued, or is
  another connection now, the result is the failed `Input to tab <id> may be partly sent; the outcome is unknown. Read the tab before anything
  else.`
- Refusals are fixed texts and type nothing: `Tab <id> is not open. Nothing was typed. Call terminal_tabs again.`; `Tab <id> is not
  connected now (<state>). Nothing was typed.`; `Tab <id> is in tmux copy mode: the owner may be scrolling. Nothing was typed.`;
  invalid arguments say `Provide tab_id from terminal_tabs and text and/or keys only. Text: up to 2,000 characters, no control
  characters except newline and tab. Keys: up to 32 from the list.`
- Confirmation is the common one (see [Confirmation of changing calls](#confirmation-of-changing-calls)): the card shows the tab, the
  program, the text and the keys; the description is pure and does no I/O; an answer of Decline or none types nothing. There is no
  lock gate in the tool: while the app is locked the card waits behind the lock like every card, and the five minutes run on.
- The text and keys never go to a log or to a `toString`. The chat's receipt is titled "Type into tab" («Ввод во вкладку»), names the
  tab as the one of `terminal_read` does, and its expanded body shows the typed text with visible escapes and a `Keys: Enter, Ctrl+C` line
  («Клавиши: …»); a refusal or failure adds its outcome and text. A successful call shows the input only: the cursor it returned is for the model.

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
one rule; the Tunnels tab's "connect without the dialog" uses the same login
rule, and the Terminal, Tunnels and Files login dialogs derive credentials
from the chosen method the same way.
The existing connector enforces stored login/refusal and pinned host-key policy.
The saved connection’s user and the port’s stored auth method apply. Remembered
password authentication preserves Remember. Missing/unreadable/refused login or
unknown/changed host key executes no command and directs setup through ordinary
SSH UI. No model credentials, automatic trust or new input dialogue are accepted.
Normal credential reads, key generation and refusal bookkeeping still apply.

Exec is noninteractive without PTY or tmux, with closed stdin. Stdout and stderr
are drained concurrently. Together they keep at most about 7,800 UTF-16
characters, so that the labels, notes and exit facts fit in the kernel's
8,000-character tool-result cap and the kernel never cuts an `ssh_command`
result. The budget is derived from that cap. Stderr, when present, keeps at
least 1,500 characters (or all of it, if shorter); stdout gets the rest. The
excess is drained and counted, not retained; a cut stream ends with the note
`[truncated: N of M characters]`, where N is what the model receives for that
stream and M is the exact total, both in UTF-16 characters. A cut never splits
a surrogate pair. The note is part of the result text for the model and the UI
receipt. The stdout and stderr texts stay separately labelled, with actual
nullable exit status/signal or explicit unknown. Only known exit 0 without a
signal is success; EOF, local close/join, nonzero/missing status and failures
are not invented remote success. Kernel/result/context limits continue to apply.

Model texts: the `ssh_command` description tells the model that output is
limited to about 7,800 characters for both streams together (stderr at least
1,500 when present), that a cut stream ends with "truncated: N of M characters",
and how to read more (narrow with `tail -n`, `head -n`, `grep`; print only what
the task needs; read big output in pieces; for tmux scrollback
`tmux capture-pane -p -S -<N>`, with `-E <line>` for the end line). The base
prompt's first paragraph also asks for brevity: "Answer briefly: give the answer
first, do not retell raw output, and add no caveats beyond the required
disclosures below unless asked; use a table only when it is clearer than a
list."

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
working summaries. Dedicated Files/MQTT/TCP tools, evaluators, permission modes and
subagents remain outside this surface. No permissions evaluator or confirmation
modes beyond the single app-wide switch are shipped. Tunnel creation and new
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
Confirmation tests use the production conversation, tool factory and kernel with a counting fake command owner: no command or client before the answer, each answer, grant scope (class and connection; never a tool that is not grantable) and every end of a grant (tunnel disconnect/reconnect/pending/Undo-hide with Undo cancelling, a different grant id, each connection field, the profile's key alias, activation and enrollment, SSH login method and host key, assistant off and off/on, a tool switched off and on again, history clear, the app lock engaging or its interval running out), a grant never wider than the card's hint, no record when the period, the fingerprint or a switch changed during the wait, a cleaner with no conversation, the 19-row class table (class, effect, `confirms`, `grantable`, six asking tools, two grantable) and the operation class pin, Stop/disable/removal during the wait, leaving the chat during it (the card waits and is answerable on return), stale and repeated answers, virtual-time and real-clock five-minute cases (an answer after the limit and a card expired while no screen was shown run nothing) and Send-limit cases, the fail-closed rules for an undescribed changing call, the persisted object and its malformed values. Component checks (chat runner, portrait/landscape/200% font, EN/RU) cover the card, the four answers, the hints, the shield and the Tools switch.
Tool-request tests (production conversation, factory and kernel with a fake provider and a fetcher that fails when reached) cover: `request_tool`
offered only while a tool is off with an enum of exactly the off tools and descriptions without schemas; an off tool called by name still
unknown with no network; the three fixed answers and the invalid-call answer with no setting changed; the decline saved with the history,
restored after a restart, cleared by a clear and by turning the tool on, and an old saved chat reading with none; "Turn on" switching exactly
that tool and sending the fixed message once, and "No thanks" sending and switching nothing; when the buttons show (never while an attempt
runs, with the assistant off, for a tool on or declined, after a Send or a clear); the web-reading mark. Component checks (chat runner,
portrait, 100% and 200% font, EN/RU) cover the buttons, the warning line, the mark and the press. Mutation runs (each run, then restored)
covered a button that turns a tool on unpressed, the wrong tool turned on, `request_tool` granting a tool (the substitute for "`request_tool` turns
something on", which cannot be written literally: the tool holds no handle to settings, so that promise rests on this structural argument plus the
unchanged-settings and no-network checks), and "No thanks" turning on or sending.
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
component) cover: the reply of an attempt that outlived the screen, a reopen that gets the same attempt and its Stop, grants kept after the screen and the attempt have ended (they are the app's), disable, provider change and tool-off reactions with no screen,
the profile's removal stopping the attempt, the one interrupted note, and the bottom navigation's dot and its TalkBack state. The
notification is covered by: unit tests of its decision (off-screen working and waiting with Stop, none while shown or locked, each final
text, none after Stop, a requested Stop or a disable, none without the permission, the end reset by a new Send, ends not told for an
attempt that was stopped, disabled or revoked, a stale notification cancelled at start); source and manifest guards (not exported
components, immutable explicit PendingIntents, the channel's importance, sound and vibration, no incoming intent read in MainActivity
or the screens); and instrumented tests on the real activity (Stop from the receiver stops a running fake attempt, foreign intents
including Navigation's deep-link keys lead nowhere and stop nothing, a tap through the trampoline leaves one MainActivity and consumes
the request). Not verified: a real notification shade, OEM or locked-screen rendering, and a real tap through the lock. The explicit test-only plain-Application
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

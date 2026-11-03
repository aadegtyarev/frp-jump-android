# Optional connection assistant

## Current slice: kernel only

Settings → LLM assistant enables or disables the experimental module. It is
**off by default**, including upgrades from versions without this setting.
The preference is committed before the switch reports success. A failed
write retains the previous preference and shows an error. A malformed new
flag falls back to off without resetting unrelated settings.

This slice is infrastructure, not a usable chat: **no provider, API key,
network request or real connection tool is configured or started**. The
settings row explicitly says so. Enabling the kernel does not perform work.
The approved follow-up slices are an external OpenAI-compatible provider,
then chat and minimal existing-connection/SSH tools. Web, Files, MQTT, TCP,
compression, evaluators, permission modes and subagents are later work.
No tunnel creation/start/reconfiguration/deletion is granted to the agent.

## Separation

- `agent-core` is a plain Kotlin/JVM module: provider-neutral messages,
  suspend provider/tool interfaces, one execution loop, bounded window and
  results, cancellation and observable events. It has no Android, HTTP,
  SSH, connection-discovery or credential dependency. It uses Kotlin
  coroutines/JSON already used by the app, not an agent framework.
- The app owns the opt-in setting and module lifetime. Disabling a committed
  setting prevents new runs immediately through the authoritative setting
  check; the settings observer cancels an active run. Disabling invalidates
  the run before cancellation. Enabling again cannot revive it.
- Providers and connection tools will be separate adapters. A tool must bind
  to its admitted connection identity; it must never resolve a replacement
  client after a disconnect. The kernel does not itself grant target access.
- The application skill is versioned in
  `app/src/main/assets/assistant/skill.md`; it is input to the future app
  coordinator, not a permission system or an automatically executing script.

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

Defaults: 12 model steps, at most 4 tool calls per step, 60 seconds per model
request, 30 seconds per tool. Limits are finite, validated kernel parameters;
this slice does not yet add their configuration UI. Timeouts and cancellation
are cooperative: adapters must support cancellation of their own I/O. An
already performed remote change is not rolled back by Stop. A timed-out tool
may have an uncertain remote outcome and must not be blindly repeated.

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
omissions are events for the future UI; they are not fabricated summaries.

Tool result text is capped at 8,000 UTF-16 characters by default, preserving
head/tail with a visible omission marker and an omitted-character count.
Cuts never split a valid surrogate pair. Model reply text/call arguments are
limited to 32,000 aggregate characters per response. A returned String is
already allocated: these are kernel-result bounds, not a total-heap or
transport-buffer guarantee. Future stream-reading adapters must cap capture
as data arrives. The full UI history/storage is the app coordinator's
responsibility, not an unbounded background store in the kernel.

## Verification and non-goals

Kernel tests use fake providers/tools only. Settings tests cover persistence,
failed writes and independent malformed-field fallback; scoped Compose tests
cover committed/busy/error/disposed-callback switch states. This is not live
LLM, SSH or broker validation. No permissions evaluator/full-access agent is
shipped in this infrastructure slice. The first real tool-enabled prototype
will need an explicit full-access warning until confirmation modes exist.

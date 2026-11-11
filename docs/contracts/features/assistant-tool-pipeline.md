# Assistant tool pipeline and strict arguments

Status: Approved by the operator on 2026-11-11 (phase F2 of the assistant
architecture review; decision R5: admission before argument parsing).
Implemented; independent review passed with notes, which are fixed.

## Goal

Each assistant tool describes only its schema, its typed argument parsing and
its action. Admission, the invalid-argument result, exception mapping and
cancellation are written once, in `AppTool`. Argument parsing goes through one
strict helper, `StrictArgs`, instead of four hand-written parsers.

No user-visible change. What the model sees stays the same: the same tool
names, descriptions and schemas (equal as JSON values), and the same result
text and outcome for every input. The only intended behavior change is R5
(scenario 3).

## Scenarios

1. **Valid call, owned Send.** Each of the five tools (`web_search`,
   `read_page`, `inspect_image`, `profile_information`, `ssh_command`) returns
   the same text and `ToolOutcome` as before.
2. **Invalid arguments.** Unknown, missing or extra keys; wrong JSON types
   (number, boolean, `null`, object, array where a string is expected); blank
   values where a nonblank one is required; values over the length limit; for
   `offset` a string such as `"5"`, a non-integer such as `5.0`, a value outside
   the `Int` range, or a negative value. Each tool returns its existing fixed
   text with `ToolOutcome.FAILED`, and performs no fetch, refresh or SSH client
   construction.
3. **R5, admission first.** Every tool calls `dispatch("tool-start") { }`
   exactly once, before it parses its arguments. If the Send no longer owns the
   run, that call throws `CancellationException`, and the call ends with
   cancellation for valid and invalid arguments alike. What changes compared
   with before:
   - web tools: a call with a wrong key set used to return the invalid-argument
     text without an ownership check; it now checks ownership first;
   - `ssh_command`: had no `tool-start` at all; it now has one, before parsing;
   - `profile_information`: unchanged (it already admitted first).

   For an owned Send the result texts do not change.
4. **Failures.** An exception from the action maps to the tool's existing fixed
   failure text. `CancellationException` always propagates and never becomes a
   `FAILED` result.
5. **Schemas.** The `ToolSchema` DSL produces JSON equal (as `JsonElement`) to
   today's schemas. Key order becomes uniform: `type`, `additionalProperties`,
   `properties`, `required`. Only `ssh_command` changes key order; this resets
   the provider's prompt cache once and is otherwise invisible. After F2 a test
   pins the compact serialized text of all five specs, so later changes to
   bytes the model sees are caught.

## Non-goals

- No change to `WebDispatch`, the callback executors, timeouts or cancellation
  mechanics (F4); SSH target resolution (F5); splitting
  `AssistantConversation` (F6); prompts (F7); confirmations (F8).
- No new tool, and no change to descriptions, limits, result texts, string
  resources, `AppSettings` (`@SerialName`) or the `SavedChat` format.
- No `Json.decodeFromJsonElement` into data classes for arguments: the
  kotlinx decoder accepts `"5"` for an `Int`, which the current parser rejects.
- No edits in `agent-core` or `agent-openai`.

## Affected surface contracts

- [`docs/agent-contract.md`](../../agent-contract.md): the tool sections (web
  tools, optional profile information, SSH command). It does not specify the
  order of admission and argument validation today. After verification add
  one sentence there: every tool checks the captured owner before validating
  its arguments, so an invalid-argument result is reported only for an owned
  call.
- No other surface contract is affected.

## Interfaces and constraints

- `app/src/main/java/ru/adsrv/frpjump/assistant/tools/AppTool.kt`:

  ```kotlin
  internal abstract class AppTool<A : Any>(
      val id: AssistantToolId, description: String, schema: JsonObject,
      private val dispatch: WebDispatch,
  ) : AgentTool {
      final override val spec = ToolSpec(id.wire, description, schema)
      /** null means invalid arguments. */
      protected abstract fun parse(args: StrictArgs): A?
      protected abstract val invalidArguments: ToolOutput
      protected abstract suspend fun run(args: A): ToolOutput
      /** Fixed, sanitized text; never an exception message. */
      protected abstract fun failure(error: Exception): ToolOutput
      final override suspend fun execute(arguments: JsonObject): ToolOutput
      // 1. dispatch("tool-start") { }  2. parse -> null -> invalidArguments
      // 3. run  4. CancellationException rethrown; Exception -> failure(error)
  }
  ```

  The exact shape may differ if a test or the existing code needs it, but the
  order (admission, parse, run) and the single `tool-start` must hold.
- `assistant/tools/ToolSchema.kt`: a small DSL, for example
  `objectSchema { string("query", maxLength = 256); integer("offset", minimum = 0) }`
  with explicit `required` and `additionalProperties = false`.
- `assistant/tools/StrictArgs.kt`: the only place that inspects
  `JsonPrimitive`. It must reproduce the current rules exactly:
  - web: exact key set; each value a JSON string, nonblank, at most the tool's
    limit in UTF-16 units;
  - `profile_information`: keys only from `query`, `profile_id`, `offset`;
    strings up to 256; `profile_id` nonblank when present; `query` may be blank
    and is trimmed; `offset` a non-string JSON number that is an `Int` and
    `>= 0`;
  - `ssh_command`: exact key set; nonblank strings; no length limit.
- The three web tools stay one `AssistantWebTools` owner (shared budget,
  search state and sources); each tool becomes an `AppTool`. Web actions keep
  running on `Dispatchers.IO`.
- Test-probe categories (`AssistantChatRuntime.beforeAction` /
  `checkedAction`) gain `tool-start` for every `ssh_command` call and for web
  calls with a wrong key set. Nothing else in the observed sequence changes.

## Acceptance criteria

- `AssistantModelSurfaceTest` (F0) stays green. A test may change only where R5
  makes the old expectation obsolete; list each such change in the PR (old
  name, new name, scenario kept).
- New tests:
  - per tool, with a `dispatch` that throws `CancellationException` on
    `tool-start`, valid and invalid arguments both end in cancellation, and no
    fetch, refresh or SSH client happens;
  - per tool, `tool-start` is dispatched exactly once and before any parsing;
  - `StrictArgs` edge cases: `"5"`, `5.0`, `true`, `null`, `2147483648`, `-1`,
    nested object or array, blank and over-limit strings, extra and missing
    keys;
  - the compact serialized text of all five specs is pinned;
  - `CancellationException` thrown by `run` propagates.
- `grep -rn "as? JsonPrimitive" app/src/main/java/ru/adsrv/frpjump/assistant`
  finds matches only in `StrictArgs.kt`.
- Each tool class is about 40 lines or fewer, not counting its description;
  report the actual counts.
- No edits under `app/src/main/res`, `agent-core` or `agent-openai`; no
  `@SerialName` change.
- The one-sentence update to `docs/agent-contract.md` is made after the tests
  pass.

## Validation

```sh
./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
```

Inspect the real test counts and failures in the result XML files. Run the
repository documentation checker. No screen changes, so no emulator run is
required; state that in the PR. This feature contract is temporary: after
verified completion, delete it.

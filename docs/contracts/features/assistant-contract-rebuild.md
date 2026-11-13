# Feature: rebuild the assistant contract (F11)

Status: Approved by the operator as phase F11 of the assistant architecture refactor (autopilot plan, 2026-11-12). Stop-list items go
to the operator via the peer session (Р14) before they land. That covers removing or weakening any normative promise and any change to
model-facing text.

## Goal

`docs/agent-contract.md` stays the normative surface contract of the optional connection assistant, reorganized so that:

- a new tool is one row in a tool table plus its own short subsection only where it truly differs, not a new "exception" chapter;
- implementation mechanics live in KDoc and tests: lock order, "actual nonblocking enqueue", callback exit, executor details, internal
  field names. Owner-, model- and security-relevant guarantees stay in the contract;
- confirmation and grant wording is generic: by effect (changing vs reading) and target, never bound to one tool's name. A later feature
  (action classes, grants per tunnel or per connection) must fit without rewording the policy;
- nothing the contract promises today is lost. Every normative statement of the old text is accounted for in a promise ledger.

No behavior, code path, test or model-facing text changes. This is a documentation and KDoc phase.

## Target structure

1. Boundary and availability (scope, consumers, the settings flow, what the assistant can never do: tunnels, credentials).
2. Kernel (agent-core: turn loop, sliding window, limits, cancellation; provider-neutral).
3. Provider (the external API adapter, provider settings and credentials, failure and recovery).
4. Instructions (two prompts from F7: the base `skill.md` and the summarizer prompt, cache rules and versioning).
5. Chat and history (the text chat, saved history, working summaries, encryption, restore).
6. Tool policy, common to every tool:
   - the per-tool switch;
   - the tool set captured at Send;
   - admission before parsing;
   - confirmation of changing calls (F8);
   - timeouts;
   - the uncertain-outcome rule;
   - untrusted data;
   - privacy disclosure;
   - minimal execution;
   - errors told to the model (typed kinds from F10).
7. Tool table: `id | effect | operation | arguments | limits | confirmation | disclosure | status`. Each tool has a short subsection only
   for what is specific to it: profile information fields and kinds, SSH command semantics, web search and page reading, image
   inspection.
8. Verification and non-goals.
9. Related surfaces.

Keep the existing concise English style. Keep stable anchors that other docs link to, or update every link: run the docs checker and grep
for `agent-contract.md#`.

## Promise ledger (required by AGENTS.md "For contract rewrites")

- A table, `# | old text (section, short quote) | disposition | new place / KDoc location | grounds`. Each disposition is one of:
  - retained;
  - moved (to a new section of this contract, to another surface contract, or to KDoc);
  - conditionally retained;
  - removed.
- Coverage: 100% of the normative sentences of the old sections "Optional profile information", "Optional SSH command", "Optional
  public Web tools" (with its subsections) and "Minimal execution". Cover the other sections too. Where a section is moved whole and
  unchanged, one row per section with "moved verbatim" is enough.
- Moving a mechanic to KDoc:
  - the KDoc must exist at the named class or function in the same PR;
  - the statement must not be a security, privacy, safety, data or compatibility guarantee. Those stay in the contract;
  - removing a future plan must not remove its approved safety, data or compatibility constraints.
- "removed" requires approval through the peer session before merge. Do not remove anything on your own. If in doubt, retain.
- The ledger goes into the PR body. A copy goes to `docs/reviews/assistant-contract-ledger.md` (untracked working material).

## Non-goals

- No change to code behavior, tests or model-facing texts (`skill.md`, the summarizer prompt, tool specs, results).
- No new tools and no new promises. F12 (owner instructions) comes next and adds its own text.
- Other surface contracts change only where a moved statement lands, or where a link needs updating.

## Affected surfaces

- [`docs/agent-contract.md`](../../agent-contract.md) (rebuilt).
- Linked documents that cite its anchors: `docs/contract.md`, `docs/contracts/surfaces/operations.md`, and any other doc found by grep.
- KDoc in the classes that receive moved mechanics.

## Acceptance criteria

1. The new structure as above. Every tool appears in the tool table.
2. The ledger covers 100% of the normative sentences of the four named sections and accounts for every other section. No row reads
   "removed" without a recorded approval.
3. Every KDoc destination named in the ledger exists in the diff.
4. Confirmation and grant text is generic (effect and target), with no tool-name binding beyond examples.
5. The docs checker is green. `grep -rn "agent-contract.md#"` finds no broken anchor.
6. A full gate run shows the code is unaffected, apart from KDoc-only changes in Kotlin files.
7. Independent review compares old and new text against the ledger.

## Validation

- The repo-docs checker; `git diff --check`.
- `./gradlew --no-daemon :agent-core:test :agent-openai:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` (KDoc edits only).

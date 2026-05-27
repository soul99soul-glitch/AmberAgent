# ADR-0002: Agent Kernel Interface Design

> Status: **Accepted**
> Date: 2026-05-28
> Context: Phase A of Agent Kernel + Surfaces refactoring
> Module: `:core:agent-runtime`

## Decision

The Agent Kernel API is defined as a set of pure Kotlin interfaces in
`app.amber.core.agent.runtime`, with zero Android dependencies.

---

## 1. Why `Agent<INPUT, ARTIFACT>` instead of untyped `Agent`

Typed generics enable the registry to validate input types at registration time.
Without them, `AgentRunner.launch()` would accept `Any` and defer type errors
to runtime. The cost is one extra type parameter at declaration sites — acceptable
for a system where agents number in the tens, not thousands.

## 2. Why `AgentHandler` is a functional interface property, not `suspend fun run()`

Kotlin extension functions and Koin injection create a temptation to add alternative
entry points (`agent.runStreaming()`, `agent.runWith(retry)`). Making `handler` a
`fun interface` property forces all execution through `AgentRunner` — the single
entry point that manages RunScope lifecycle, tracing, and event persistence. The
cost is one extra line in agent declarations.

## 3. Why `RunScope` instead of bare `CoroutineScope`

An agent's execution needs lifecycle ownership (who creates/cancels the scope),
event writing (who commits Final events), and tool access (who mediates permission).
A bare `CoroutineScope` provides none of these. `RunScope` carries all execution
context as first-class properties, preventing the "pass 12 parameters" anti-pattern.

## 4. Why `RunScope` contains tracing, permission, messagePipeline, handoff

- **Tracing**: Not all spans originate from tool calls. Model turns and permission
  waits also need spans. Putting tracing in `ToolSession` would miss these.
- **Permission**: Workspace / Terminal / DeepRead operations need approval gates
  that aren't tool invocations. Permission must be a peer of `ToolSession`, not nested.
- **MessagePipeline**: ChatTurnAgent needs input/output transformers; other agents don't.
  Making it a `RunScope` property (vs. a ChatTurn-only concern) lets the pipeline
  be tested in isolation with any agent type.
- **Handoff**: Pre-reserved interface to prevent `child()` semantics from being
  stretched into handoff semantics later. Handoff = control transfer (parent ends);
  child = subtask (parent continues).

## 5. Why `AgentEvent` splits into Final and Transient

Streaming token chunks arrive at 30+ per second. Persisting all of them would produce
1MB+ per long conversation. Only discrete narrative facts (turn complete, tool result,
section done) need Room persistence. Transient events flow through `SharedFlow` for
in-flight UI and are garbage collected immediately.

## 6. Why `ChatTurnAgent` is a singleton shared by all Assistants

An `Assistant` is user-facing configuration (system prompt, model params, tool set).
An `Agent` is an engineering capability (how to execute a chat turn). Creating one
Agent per Assistant would duplicate logic and complicate the registry. Instead,
`ChatTurnInput.assistantId` carries the configuration key, and the singleton
`ChatTurnAgent` resolves it at runtime via the existing Assistant repository.

## 7. Why `agent_run` contains `messageNodeId` and `producesMessageId`

Regeneration creates multiple `UIMessage` candidates within the same `MessageNode`.
Each candidate corresponds to one `AgentRun`. The `messageNodeId` links the run to
its position in the conversation tree; `producesMessageId` links it to the specific
output candidate. This enables the UI to show "attempt 1/2/3" per message position.

## 8. Why `AgentRunner.launch()` returns `Result` instead of `AgentRunHandle` directly

The registry stores agents as `RegisteredAgent<*, *>` (star projections). Launching
requires an unchecked cast from `KClass<*>` to `KClass<I>`. Making this explicit via
`Result` prevents `ClassCastException` from surfacing as an opaque crash — callers
handle `AgentMismatchError` gracefully.

## 9. Why `AgentRuntimeDatabase` is separate from `AppDatabase`

- **Different lifecycles**: Chat history is user data (backup, export); agent runtime
  state is ephemeral infrastructure (trace spans expire after 30 days).
- **Migration independence**: AppDatabase has 30 migrations. Adding agent tables there
  couples their schema evolution.
- **Physical separation**: Allows independent vacuum/checkpoint without locking chat queries.

## 10. Why `Surface` is a Kotlin interface, not a documentation concept

Phase E exit criteria require a compilable assertion: "Surface implementations must
not depend on `:feature:*:impl` modules." A documentation-only concept cannot enforce
this. A Kotlin interface + future detekt rule can.

## 11. Why `ToolDescriptor` contains `version` and `isStable`

- **Version**: MCP servers update tool schemas independently. Stamping the version into
  `ToolCallPart` enables replay of old conversations with the correct schema.
- **isStable**: Unstable tools (experimental, beta) should not contribute to Final
  event streams. The `isStable` flag gates this at the descriptor level.

## 12. Why batch tokenizer API instead of single-call

JNI crossing has ~2μs overhead per call. For a 50-message conversation with system
prompt + tools, single-call would pay this 50+ times. Batch API amortizes the marshal
cost to one crossing regardless of segment count.

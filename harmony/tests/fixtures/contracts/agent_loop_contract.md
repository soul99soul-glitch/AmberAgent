# Agent Loop Contract

The ArkTS Agent loop must preserve AmberAgent's observable turn semantics.

## States

1. `started`: user message accepted and run context created.
2. `streaming`: provider emits deltas that update the assistant message.
3. `tool_pending`: a tool call appears and requires permission or execution.
4. `tool_result`: tool output is attached to the assistant/tool part.
5. `final`: the assistant turn is complete and persisted.
6. `failed`: an unrecoverable provider/tool/runtime error is surfaced.
7. `cancelled`: user cancellation stops streaming/tool execution where possible.

## Rules

- Tool calls must remain visible in message history.
- Permission state must be serializable and recoverable after restart.
- Partial visible text may be used as fallback only for known structured-report argument failures, not arbitrary generation errors.
- Checkpoints must be persisted often enough to recover from process death.

## Required Fixtures

- `../agent_loop/turn_events.jsonl`
- `../agent_loop/generation_chunk_messages.jsonl`
- `../agent_loop/chat_turn_input_output.json`
- `../agent_loop/turn_events_failed.jsonl`
- `../agent_loop/turn_events_cancelled.jsonl`

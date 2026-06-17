# Streaming Contract

This contract defines the minimum OpenAI-compatible SSE behavior the ArkTS provider layer must preserve.

## Input

- Raw SSE events may arrive as complete `data: {json}` lines.
- A single SSE event may contain a multi-line JSON payload.
- Some providers may emit nested `data:` prefixes inside the data payload.
- `[DONE]` terminates the stream and must not be parsed as JSON.
- Error payloads may arrive as JSON with an `error` object.

## Output

- Text deltas append in order to the current assistant message.
- Reasoning deltas append to a reasoning part and are marked finished when text starts.
- Tool-call deltas merge by `tool_calls[index]` first, then by id, then by last open tool only as fallback.
- Parallel tool deltas must not cross-wire arguments.
- Final full messages replace partial streamed text when providers emit both partial and final content.

## Current Executable Coverage

The source-only executable checks currently cover SSE normalization, `[DONE]` / error payload handling, independent JSON-line splitting, storage row mapping, fixture references, selected transformer expectations, and agent-loop terminal states.

The heavier stream-merge semantics in this contract are intentionally **not** executable yet:

- `tool_calls[index]` → id → last-open merge behavior
- reasoning `finishedAt` transition when text starts
- final full-message replacement of partial streamed deltas

These must become executable when the Phase 1 ArkTS provider/message accumulator is implemented.

## Required Fixtures

- `../streaming/openai_chat_completion_chunks.jsonl`
- `../streaming/openai_multi_line_payload.json`
- `../streaming/openai_parallel_tool_deltas.jsonl`
- `../streaming/openai_reasoning_content_chunks.jsonl`
- `../streaming/openai_malformed_nested_data_prefix.jsonl`
- `../streaming/openai_done_and_error.jsonl`
- `../agent_loop/turn_events_cancelled.jsonl`

# Phase 5 final narrow independent review

## Scope and result

Reviewed the final non-streaming tool-continuation fix in `chat_turn.ts` and the single necessary regression in `tool_loop.test.ts`, with read-only tracing through the existing accumulator, tool loop, Provider adapters, and Entry run service. No additional test, product change, build, or device operation was made by this reviewer.

**No blocking finding in this fix.** The change addresses the observed loss of executed tool results during non-streaming continuation at the generation boundary. It does not change the accumulator's public whole-message replacement contract.

## Call-chain checks

- `generateAssistantOnce` normalizes a non-streaming response's `message` to `delta` before append. Existing deltas remain deltas; the chunk's choices, usage, and other fields are retained. Previously, a response containing only `message` replaced the active assistant message, discarding earlier executed tools in that same message.
- The tool loop supplies the canonical current messages as the accumulator seed, writes the merged final assistant back into the existing node, and persists before processing further tools. The regression covers two separate approval/resume boundaries, one write and one edit, followed by a final reply. Both tool outputs remain in canonical history and in the final Provider request, each tool executes once, and completion facts still identify the changed file.
- OpenAI Chat, OpenAI Responses, Claude, and Google expose the same `ChatStreamProvider.generateText` shape to this boundary. The fix also applies to the step Provider override and the normal interactive wrapper; it does not alter Provider request/parser contracts.
- `ProviderResponseError.partialChunk` goes through the same normalization, publishes raw and visual snapshots, and rethrows the original error. The Entry service still drains the latest raw checkpoint and persists that tail on failure. A retry starts with a new accumulator seeded from canonical history, as before.
- The abort signal is still passed to the Provider. A cancelled attempt retains its available snapshot through the existing termination path; the tool loop checks abort after generation and again before tool effects. Normal send, tool resume, and regeneration with tools use this shared generation path. The separate ordinary regeneration path starts from its truncated seed and is unchanged by this fix.
- Direct accumulator callers retain `delta == null && message != null` whole-message replacement. Existing replacement, role-change, argument-merge, annotations, and usage tests remain green. The streaming branch is unchanged.

## Necessary verification

Executed only the five existing files directly relevant to this change:

```sh
node --import tsx --test \
  src/test/tool_loop.test.ts \
  src/test/stream_accumulator.test.ts \
  src/test/stream_output.test.ts \
  src/test/provider_partial_failure.test.ts \
  src/test/chat_turn.test.ts
```

Result: **76/76 PASS**, zero failures, cancelled tests, or skipped tests. Log: `/tmp/amber-ios-followup-phase5-final-narrow-review-tests.log`.

The single added regression is justified by the reproduced product failure and checks the complete approval/resume/Provider/history/completion chain. This review adds no cases and does not repeat previous phase reviews or full-suite checks. Simulator acceptance and package verification belong to the root operator and are reported separately.

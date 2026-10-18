# Phase 2 independent functional review

Result: **pass after one review-discovered MCP correction**. No remaining reachable blocker was found in the accepted B05–B13 / CP01–CP06 repair scope.

This reviewer did not implement the production changes. Review was read-only for production/tests. Android and MCP received an additional bounded independent review in `phase2-review-android-mcp.md`; the provider changes were reviewed here against current source, the preserved initial WIP baseline where applicable, the original protocol audit, and the implementation packet. `.agents/skills/harmony-arkts/SKILL.md` was read before Harmony source. Official OpenAI documentation was refreshed for the request contracts.

## Defect found during phase review and corrected

The initial MCP close repair cancelled the GET stream but still allowed **buffered old messages after `close()` resolved**. Actual `QueuedSseStream.cancel()` ends the source without discarding its existing event queue (`McpHttpPortImpl.ets:127-190`). `collectSse()` drained that queue without checking whether the transport remained open or still owned that stream.

The independent `phase2-review-mcp-close-probe.mts` buffers 20 ordinary SSE messages using those production queue/cancel semantics. I inspected the platform implementation and independently reproduced:

```json
{"cancelled":true,"atClose":3,"afterClose":20,"postCloseDeliveries":17}
```

The owner added a current-session/initialized check immediately after `await session.next()` and suppressed errors belonging to a closed/replaced collector. Re-reading this correction and independently rerunning the unchanged probe yielded:

```json
{"cancelled":true,"atClose":0,"afterClose":0,"postCloseDeliveries":0}
```

The formal regression and its red/green evidence are in `logs/phase2-mcp-buffered-red.log` and `logs/phase2-mcp-final.log`. This is a demonstrated post-completion callback bug, not an assumption that asynchronous DELETE must finish immediately.

## Verified repair contracts

- **CP01 refusal visibility:** Chat scalar/refusal deltas and full Responses refusal content become text. Responses refusal.done, output_item.done, and full terminal replay reuse the existing text reconciliation and display the tested refusal prefix/full sequence once.
- **CP02/CP03 image retention and identity:** nonstream Responses output retains all image_generation_call results and native IDs. Terminal replay forwards images to the shared append owner. Mutable and immutable Harmony accumulation complete existing image calls by ID across text/other-image interleaving and deduplicate repeated terminal results. Full Chat/Google data URIs preserve independent image boundaries; Google MIME and metadata are retained. Legacy unkeyed base64 fragments retain their existing concatenate behavior.
- **CP04 output cap:** the changed cap mapping applies to the existing o-series detector only on `api.openai.com`, with compatible-provider/MiMo mapping, null caps, ordinary custom-body precedence, and Novel-owned caps exercised. This matches the official [Chat create contract](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create), which excludes max_tokens for o-series.
- **CP05 foreign image replay:** the entire easy-message content array now uses input_text/input_image, retaining the assistant role and original content order. Native Responses image call replay stays on its ID path. The [official Responses input schema](https://developers.openai.com/api/reference/typescript/resources/responses) includes easy input messages with assistant history; the repair avoids the invalid output_image variant.
- **CP06 Mistral chunk arrays:** every supported nested thinking text and answer text chunk is retained, the array-to-scalar stream transition keeps the answer prefix, scalar reasoning precedence remains, and leading inline thinking remains before its associated tool call. The earlier peer review's ordering regression has a regression assertion and is corrected in the current source.
- **MCP negotiation/lifecycle:** GET sends text/event-stream with reserved-header precedence, DELETE failure still closes local resources, pending GET completion after close is cancelled before collection, and the follow-up guard stops buffered old events/errors.
- **Active Android image paths:** independent reviewer traced actual Google/Chat/Responses wire parsing through `GenerationHandler` to mutable stream and immutable nonstream accumulation. Both preserve complete images, native call IDs, interleaving, Google MIME/signature, and supported raw fragments. Its current-source JVM probe returns two separate payloads with ig_1/ig_2. The fresh six-suite Gradle result inspected by that reviewer has no failures/errors.

## Independent execution evidence

`phase2-independent-provider-tests.log`: **116 passed, 0 failed** across provider_protocol_regressions, openai_request, openai_responses_api, google_parse, stream_accumulator, novel_request_config, and provider_runtime.

`phase2-independent-mcp-tests.log`: **67 passed, 0 failed** across mcp_transports, mcp_protocol, and mcp_manager, including the new buffered-event case.

The exact source-based MCP probe was rerun independently both before and after the correction. Additional Android evidence and the precise division between execution and inspection are recorded in `phase2-review-android-mcp.md`. These test packets overlap the implementation owner's runs and should not be summed as unique coverage.

## Boundaries and limits

No missing-EOF-sentinel strictness or generalized provider compatibility layer is requested. The prior conditional Android final-only image candidate remains excluded: no normal official-stream failure was established, and it is outside the accepted change rather than a regression from it. Unsupported hypothetical model names and explicit arbitrary custom-body overrides were not promoted into defects.

This finite functional review does not prove live provider behavior, image decoding on a device, SDK/lint compliance, build/signing/install success, or physical-device/user acceptance. The root owns those integration gates. No production/test file, commit, push, or external message was created by this reviewer.

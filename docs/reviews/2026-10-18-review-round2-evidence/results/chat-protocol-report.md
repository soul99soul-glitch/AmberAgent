# Chat runtime and provider protocol audit packet A

Audit state: current dirty tree based on HEAD `e6d6c26b6`; previous fixed R01–R20/U01–U05/S01 excluded. Read-only production/tests. Skill read: `.agents/skills/harmony-arkts/SKILL.md`. All evidence below runs real current domain imports and actual API adapters with deterministic HTTP fixtures, without paid provider traffic or device claims.

Probe command:

```sh
harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json .workflow/comprehensive-review-round2-20261018/results/chat-protocol-probe.mts
harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json .workflow/comprehensive-review-round2-20261018/results/chat-protocol-request-probe.ts
```

Evidence: `chat-protocol-probe.mts`, `chat-protocol-probe.log`, `chat-protocol-request-probe.ts`, `chat-protocol-request-probe.json` in this directory. Request-specific report is `chat-protocol-request-report.md` when supplied by the independent request reviewer.

## Confirmed candidates

### CP01 — P2 — Official OpenAI refusal-only replies become blank successful assistant answers

Locations: `harmony/chat/src/main/ets/chat/openai_parse.ts:56` and `openai_responses_parse.ts:87`, `:150`, `:222`.

Trigger/reachability: OpenAI Chat returns `message.refusal` (nonstream) or `delta.refusal` (stream) while content is null/absent. The shared parser reads only content string and drops refusal entirely. Responses nonstream/full terminal output uses `content:[{type:'refusal',refusal:'...'}]`; both the full-output parser and done-message extractor read only `text`. `response.refusal.done` is unhandled. The normal Responses `response.refusal.delta` path already works, so do not claim all Responses streaming refusals fail.

Actual output: `chat_message=''`, `chat_stream=''`, `responses_full=''`, `responses_item_done=null`, `responses_refusal_done=null`; positive control `responses_refusal_delta='I cannot help with that request.'`. This loses a valid provider explanation and presents apparent successful blank completion.

Minimal owner fix: decode official refusal fields as visible text in Chat/full Responses/done-only paths, using the existing text reconciler to prevent duplicate terminal text. No new retry/fallback/provider selection is needed.

Official contracts: [Chat streaming delta refusal](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events), [Responses content refusal](https://developers.openai.com/api/reference/resources/responses/methods/create), [Responses refusal.done](https://developers.openai.com/api/reference/resources/responses/streaming-events).

Second check: independently confirmed by request_contract_audit agent from the same production call paths and probe output.

### CP02 — P2 — Responses nonstream image generation drops every generated image

Location: `harmony/chat/src/main/ets/chat/openai_responses_parse.ts:116` (output-item dispatch has no `image_generation_call` branch).

Trigger/reachability: model builtin image_generation is exposed by `ChatModelParamsPage.ets:417`, encoded by `openai_responses_request.ts:324`; persisted/imported assistant `streamOutput=false` selects `generateText` in `chat_turn.ts:276`. A successful official response containing `{type:'image_generation_call',id:'ig_1',status:'completed',result:'...'}` is parsed through parseResponsesOutput and discarded. An optional accompanying text survives, masking the missing product.

Actual API output: `createOpenAIResponsesApi(...).generateText` returns `message.parts=[]` for an image-only success. The identical output item through existing `response.output_item.done` yields the expected image part with `openai_image_call_id='ig_1'`.

Minimal owner fix: parse generated-image output items on the full output path and reconcile terminal images by existing call ID so streaming terminal replay does not duplicate a previously received image. Keep the ID so the current next-turn replay mechanism remains valid.

Official contract: [image_generation_call result is base64 image output](https://developers.openai.com/api/docs/guides/tools-image-generation).

Second check: independently confirmed by request_contract_audit.

### CP03 — P2 — Adjacent distinct provider images are combined into one image payload

Locations: `harmony/chat/src/main/ets/chat/stream_accumulator.ts:213` and immutable twin `:409`; provider boundaries include `openai_parse.ts:116`, Google inlineData, and Responses image-generation call IDs.

Trigger/reachability: one provider reply contains two adjacent complete image outputs, including OpenAI-compatible `images` array or two adjacent Responses image generation items. Parser emits two image parts, but both accumulator implementations append every adjacent image URL without checking completeness or distinct image identity. Responses already carries separate `openai_image_call_id` values but these are ignored while merging; final metadata becomes the latter ID.

Actual output: parser emits two image parts `aW1hZ2Ux` and `aW1hZ2Uy`; accumulator emits one `data:image/png;base64,aW1hZ2UxaW1hZ2Uy`. The second artifact is unavailable independently and the joined payload is not the requested two-image output. No assumption about exact image-decoder tolerance is needed to establish image loss.

Minimal owner fix: retain complete-image boundaries at provider parser ownership and use existing image call IDs for Responses append/replace. Preserve the current supported bare-base64 fragment accumulation behavior; simply disabling all image concatenation would break intentional fragment handling and existing tests. Ensure mutable and immutable helpers use the same boundary rule.

Second check: independently confirmed by request_contract_audit, including the fact distinct call IDs/full data URI do not currently prevent concatenation.

### CP04 — P2 — Finite output cap uses max_tokens for official OpenAI o-series Chat

Location: `harmony/chat/src/main/ets/chat/openai_request.ts:411`.

Trigger/reachability: official OpenAI Chat endpoint, model `o3` (o-series) with finite assistant cap, or Novel request config which always supplies a finite cap. Actual prepareProviderRuntime → buildChatCompletionRequest returns `max_tokens:4096` and no max_completion_tokens; Novel returns `max_tokens:8192`. Official API marks max_tokens incompatible with o-series, so these authorized normal requests are invalid rather than model/network failures. Adding custom body max_completion_tokens leaves both keys; Novel also blocks custom cap overrides.

Evidence: independent agent probe `chat-protocol-request-probe.json` contains ordinary/novel/customized requests. This is not inferred just from model-name popularity.

Minimal owner fix: choose the supported cap field at the request owner for the relevant confirmed official o-series family, preserving compatible-provider/MiMo behavior and existing novel cap ownership. No retries or feature gates.

Official contract: [Chat create max_tokens / max_completion_tokens](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create).

### CP05 — P2 — Replaying a foreign assistant image through Responses emits unsupported output_image

Location: `harmony/chat/src/main/ets/chat/openai_responses_request.ts:147`.

Trigger/reachability: existing history includes an assistant image produced through Google/OpenAI-compatible image output without Responses image-call metadata; the user switches the conversation model/provider or enables the visible Responses API toggle (`ChatProviderDetailPage.ets:1104`). The next request serializes that image as `{type:'output_image', image_url:...}` inside assistant content. The official Responses input schema has input_image and output message text/refusal, no output_image content type.

Actual probe: parseChatCompletionMessage of a real supported image wire shape → buildResponsesInput produces assistant content `[output_text,output_image]`, captured in `responsesReplay`. Native Responses image generation calls use the existing ID-based branch instead and are not affected by this finding.

Minimal owner fix: project retained foreign assistant images into valid Responses input content while preserving text/history ordering and native Responses image-generation ID replay. Verify the whole easy-message content shape; changing only image type while leaving incompatible text item shape is insufficient.

Official contract: [Responses TypeScript request content schema](https://developers.openai.com/api/reference/typescript/resources/responses).

### CP06 — P2 — Mistral chunk-array answers lose final text and part of reasoning

Location: `harmony/chat/src/main/ets/chat/openai_parse.ts:56`–`:69`.

Trigger/reachability: an OpenAI-compatible Mistral model with reasoning enabled returns official `content:[{type:'thinking',thinking:[text chunks...]},{type:'text',text:'final answer'}]`. Nonstream uses this shape for the whole reply. Streaming uses this array on the thinking-to-answer transition, followed by plain-string answer deltas. Existing support explicitly recognizes Mistral thinking arrays, so this is inside the implemented adapter scope.

Actual probe: both full message and transition delta return only `reasoning one`; `toText=''`; `reasoning two` and `final answer` disappear. Full nonstream answers are blank; streaming loses the first answer chunk at the transition. The source comment acknowledging arrays are excluded does not establish an intended product decision to discard the answer, and current official schema confirms these are normal successful responses.

Minimal owner fix: parse the documented thinking/text chunk array, preserving order and every nested text chunk while retaining scalar content and separate reasoning_content precedence. Do not add generalized unknown-block compatibility.

Official contract checked at audit time: [Mistral reasoning, handling thinking chunks and streaming transition](https://docs.mistral.ai/studio/conversations/reasoning). Documentation now marks Magistral native reasoning deprecated, but current Mistral small/medium/large reasoning uses this same supported shape; finding does not rely on a deprecated model remaining callable.

## Rejected / retained design boundaries

- **Missing stream sentinel alone:** actual Claude graceful premature EOF fixture returns success and allows a syntactically complete file_read call before content_block_stop/message_stop (`CLAUDE_PREMATURE_EOF` in log). This is reproducible protocol incompleteness, but no explicit provider failure/rejection was demonstrated. At root review request it is excluded from the accepted list to avoid tightening all compatible streams merely from a missing sentinel. Google normal EOF/unknown/MAX_TOKENS semantics are explicitly covered in current tests and remain design boundaries.
- **Nonstream replaces prior executed tools:** rejected as already fixed in current dirty tree. chat_turn converts this request's full message to a delta; existing two-approval/nonstream regression preserves tool history and final verification state.
- **Google/Responses failure envelope executes tools:** rejected as already fixed. Current adapters buffer tools, remove failed-envelope calls, preserve display text, and existing fixtures cover explicit failed statuses.
- **Tool index restarts corrupt previous rounds:** rejected as already fixed by excluding executed tool parts and preserving separate Claude block identity/Responses call identity.
- **Usage merge should always sum:** not reported. Current stream usage merge explicitly uses cumulative per-request replacement; no product accounting contract proving summation was found in this packet.

## Coverage and limits

Read `tool_loop`, `chat_turn`, `stream_accumulator`, `tool_merge`, OpenAI Chat/Responses/Google/Claude APIs and parsers, provider_runtime, and ConversationRunService/Dependencies/Tools production entry seams. Checked current regeneration/approval/cancellation changes against prior accepted reports. Request reviewer independently covered request serialization and official schema compatibility. Did not modify production code/tests, invoke paid providers, or perform SDK/device validation; screenshots and platform acceptance belong to integration phase. Root requested scan freeze once these real candidates were available.

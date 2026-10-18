# Packet F: Android / Web bounded review

Baseline: branch `harmony-preview`, HEAD `e6d6c26b6bf151ee3c58cbaa0b4e2acf61deac55`; review is read-only. Existing Harmony changes and untracked artifacts were preserved. No production or test edits, Gradle builds, device operations, network provider requests, credentials or metered calls.

## Active runtime boundary

- `settings.gradle.kts:48` includes `:app`, `:ai`, `:search` and the modular Android runtime. Android code is active source in this repository, even though the branch's current product work concentrates on Harmony.
- `settings.gradle.kts:48-96` excludes `:web`; `web/` contains only `.gitignore` files and no build/server implementation. Current Kotlin tree has no embedded Ktor server or `/conversations/paged` backend. `web-ui/` is retained frontend source, not a current Android/Harmony app surface. Its defects were not promoted. Independent explorer evidence is in `android-web-web-reachability.md`.

## Confirmed: F01 / P2 — Adjacent generated images corrupt into one image

**Primary owner:** `ai/src/main/java/app/amber/ai/ui/MessageStreamAccumulator.kt:123-133`.
**Parallel path:** `ai/src/main/java/app/amber/ai/ui/Message.kt:73-86` repeats the same adjacency-only image concatenation in `handleMessageChunk` (used by non-stream generation).

**Ordinary reachable trigger:** Enable the OpenAI Responses built-in image generation tool and obtain two consecutive `image_generation_call` items. `ResponseAPI.kt:725-744` emits a blank image on `response.output_item.added`; `:785-809` emits that item's full image on `response.output_item.done`. Each part carries `openai_image_call_id`. The reconciler forwards image deltas unchanged (`ResponseStreamReconciler.kt:58-60`). The accumulator merges any adjacent image with the last image without comparing this identity. The second image's data is appended onto the first data; its metadata then replaces the first image's metadata.

**Production caller:** `GenerationHandler.kt:498-525` constructs `MessageStreamAccumulator`, collects `providerImpl.streamText()`, appends each chunk, and publishes snapshots; final snapshots are published at `:550-557`. OpenAI Responses stream always goes through `ResponseStreamReconciler` (`ResponseAPI.kt:139-143`, `:192`). `GoogleProvider.kt:700-722` also returns complete raw image parts; multiple consecutive inlineData images have the same adjacency problem because they are independent complete images, not base64 fragments of one image.

**Observable impact:** Multiple requested/generated images collapse into one part. A sequence with padded base64 produces an invalid concatenated base64 payload, causing the finish transformer decoding to fail; a concatenation accepted by a decoder still has no separate second image. `FilesManager.kt:237-239` decodes the entire merged URL as one image. Existing chat output ends at `GenerationHandler.kt:268-274`, so this can fail final generation or show only a first image. The UI cannot recover the missing image boundary after this merge.

**Executable proof:** `android-web-probe/run.py` compiles the CURRENT `MessageStreamAccumulator.kt` and CURRENT `ResponseStreamReconciler.kt` against already cached Gradle dependency jars and existing model classes; no project build or dependency install. `ImageProbe.kt` supplies the exact parser-generated part sequence for two ordinary added/done item pairs. Output:

```text
two_images_count=1
two_images_values=[data:image/png;base64,QUJDRA==RUZHSA==]
two_images_metadata=[{"openai_image_call_id":"ig_2"}]
```

This is a data/protocol failure, not style, input validation or a defensive fallback request. The correct output has two image parts, `data:image/png;base64,QUJDRA==` and `data:image/png;base64,RUZHSA==`, carrying ig_1 / ig_2 respectively.

**Small owner-level fix:** Preserve image identity in both accumulation paths. Explicit OpenAI call IDs let added/done updates target one image without merging a different image; Google inlineData is already a complete image and each occurrence should remain a separate image. Avoid adding a generic retry/state machine or guessing that adjacency means base64 continuation. Preserve metadata/thought signature per image. A narrow shared merge policy can keep the existing mutable and immutable paths consistent if warranted.

**Required regression checks:** Add actual parser -> reconciler -> accumulator fixtures for two consecutive image calls; also assert two complete Google inlineData images remain independent and one OpenAI added/done pair produces exactly one image. Cover the immutable `handleMessageChunk` counterpart because non-stream `GenerationHandler.kt:655` uses it. Existing relevant suites are `MessageStreamAccumulatorTest`, `UIMessageChunkMergeTest`, `ResponseAPIStreamReconcileTest`, `ResponseAPIMessageTest`, `GoogleProviderMessageTest`. Root may coordinate `./gradlew :ai:testDebugUnitTest --tests ...`; no Gradle task was run in this packet.

## Conditional candidate: F02 — Reconciler drops images supplied only in completed output

**Owner:** `ai/src/main/java/app/amber/ai/provider/providers/openai/ResponseStreamReconciler.kt:110-131`; `parseResponseOutput` correctly constructs terminal image parts at `ResponseAPI.kt:998-1014`, but reconciler only copies text, reasoning, tools and annotations into its delta.

**Proof:** The current-source probe supplies a final message containing one image; after reconciliation and accumulation, `final_only_image_count=0`. This is independently reproducible, but requires the image result to arrive solely in terminal output. A standard complete OpenAI event sequence normally supplies `response.output_item.done` first, whose image is already retained. No real provider request was sent and this packet did not establish a configured compatible provider that omits item done.

**Classification:** Not promoted as an equal-confidence current-product defect. The app already intends to accept final-only text, refusals and tool parameters (documented and tested in `ResponseAPIStreamReconcileTest`), so root can decide whether images belong to that explicit compatibility contract. If accepted, reconcile terminal images by image call identity and dedupe images already received; do not append the same final image twice. Otherwise retain this as a bounded unsupported-provider/test-gap note.

## Rejected / by-design candidates

- **Non-stream raw base64 images look unnormalized:** rejected. `GoogleProvider.parseMessagePart` and `ResponseAPI.parseResponseOutput` return raw base64, but `GenerationHandler.kt:655` calls `handleMessageChunk`, whose `Message.kt:83-86` adds the data URL prefix before the finish transformer. The raw parser representation is intentional. This review does not propose blanket data URL rewriting.
- **Earlier audit says final/non-stream Responses parser drops generated images:** stale. Current `ResponseAPI.kt:998-1014` and `ResponseAPIMessageTest.kt:380` already implement and test image_generation_call parsing. F02 concerns the later reconciliation layer and is conditional as described above.
- **Web UI draft/race/reconnection candidates:** inactive source; excluded. See the independent web report.
- **Google missing candidates / usage causes `!!` crash:** prior audit claim is stale; current non-stream path checks candidates and handles optional usage. Not reported.
- **Unclosed HTTP error response and cancellation patterns:** code inspection alone in this bounded pass did not prove a new reachable failure of sufficient confidence. Not reported.

## Coverage and limits

Read root AGENTS/CLAUDE, web-ui CLAUDE, current Gradle module graph, all provider entrypoint flow around OpenAI/Google/Claude generation, Responses parsing/reconciliation, immutable and mutable message accumulation, finish image conversion, selected provider/message tests, and Android conversation persistence/checkpoint boundaries (`ConversationRepository`, `ChatStreamCheckpointRecorder`, `ChatEventProjector`). Sampled search service source. No additional concrete persistence/search defect was confirmed; this is not an exhaustive audit of every Android module.

The isolated compiler probe verifies current accumulation/reconciliation code only; it does not claim a full Android build, provider integration run, bitmap rendering test or physical-device acceptance. Parent agent owns independent second pass, phase planning, source edits and integrated verification.

Official protocol reference inspected read-only: https://developers.openai.com/api/docs/guides/tools-image-generation describes image_generation_call.result as a base64-encoded image. No source quotation is needed for the demonstrated call identity / adjacency failure.

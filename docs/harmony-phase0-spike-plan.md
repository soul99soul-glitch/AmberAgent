# HarmonyOS Phase 0 Spike Plan

> Purpose: convert the native HarmonyOS migration plan into executable Phase 0 probes.  
> Output target: evidence-backed go/no-go decisions, not production code.

## Phase 0 Reframing

Phase 0 is not a full migration phase. It answers whether the core AmberAgent experience can be built natively on HarmonyOS NEXT and which Android-only features must be replaced.

After architecture review, Phase 0 uses this priority model:

- **True go/no-go:** SSE streaming, RDB/Preferences, ArkUI streaming + Markdown rendering.
- **Semantic-risk early fixture:** Agent loop and message transformer behavior.
- **Optimization/evaluation:** Rust NAPI, syntax highlight native path, document native path.
- **Replacement decisions:** notification listening, local terminal/proot, system automation limits.

## Gate 0: Toolchain Readiness

Before any result is marked as verified:

1. DevEco Studio version is recorded.
2. HarmonyOS NEXT SDK/API level is recorded.
3. Full SDK availability is recorded for AccessibilityExtensionAbility.
4. Debug signing can install a minimal demo HAP.
5. A real device or emulator target is available.
6. Native build capability is classified: C++ NAPI only, Rust direct, or Rust via C++ shim.

If Gate 0 is not satisfied, Phase 0 can still produce source inventory and implementation plans, but not final pass/fail conclusions.

## Success Criteria

Phase 0 succeeds when we can make a written decision for each question:

1. Can ArkTS stream OpenAI-compatible SSE responses reliably enough for chat?
2. Can RDB + Preferences model the minimum conversation/settings state?
3. Can ArkUI render a streaming Markdown chat list without unacceptable jank?
4. Can the Agent loop semantics be frozen in fixtures before ArkTS rewrite?
5. Can AccessibilityExtensionAbility cover AmberAgent's observe/act/verify loop, or only a subset?
6. Are notification listening and local proot terminal feasible for ordinary apps? If not, what replaces them?
7. Is Rust NAPI useful as Phase 1 optimization, or should ArkTS fallbacks ship first?
8. Which HarmonyOS APIs are safe to use in Phase 1 without re-architecture risk?

## Workstreams

### WS0: Toolchain and Safe Scaffold

**Goal:** create a clear workspace for HarmonyOS work without committing fake DevEco/hvigor files.

Deliverables:

- `harmony/README.md`: setup assumptions and expected DevEco import flow.
- `harmony/spikes/README.md`: spike result format.
- `harmony/native/rust-napi/README.md`: NAPI/Rust notes and fallback strategy.
- `harmony/tests/fixtures/README.md`: corpus/fixture organization.

Gate:

- No fake `hvigorfile.ts`, `build-profile.json5`, `module.json5`, or generated SDK files unless produced by DevEco or verified templates.
- Gate 0 checklist is linked from the scaffold docs.

### WS1: Streaming Provider Spike

**Android anchors:** `ai/src/main/java/app/amber/ai/util/SSE.kt`, `ChatCompletionsAPI.kt`, `OpenAIProvider.kt`.

Tasks:

1. Implement a minimal ArkTS OpenAI-compatible streaming request.
2. Parse `data:` lines, JSON deltas, `[DONE]`, and errors.
3. Test cancellation and timeout.
4. Record whether `@ohos.net.http` or RCP is the better Phase 1 API.

Gate:

- Pass: incremental tokens arrive before completion and cancellation stops the request.
- Limited: response streams only after buffering or cancellation unreliable.
- Fail: no practical streaming API.

### WS2: Storage Spike

**Android anchors:** `AgentRuntimeDatabase.kt`, `Entities.kt`, `AgentRuntimeDao.kt`, `PreferencesStore.kt`.

Tasks:

1. Create minimal RDB schema for `conversation`, `message_node`, `ui_message`.
2. Insert/query/update a conversation with branch-like nodes.
3. Create preferences store for provider config and UI setting.
4. Test versioning and basic migration path.

Gate:

- Pass: state persists across restart and schema version can be detected.
- Limited: simple state works but migrations or larger JSON payloads need extra strategy.
- Fail: RDB/Preferences API blocks core storage needs.

### WS3: ArkUI Streaming + Markdown Spike

**Android anchors:** chat UI components, `jank-opt/`, native markdown crates.

Tasks:

1. Build a synthetic list of user/assistant/tool messages.
2. Append 5k token chunks to the active assistant message.
3. Include realistic Markdown: headings, code fences, lists, tables, blockquotes, tool-result blocks.
4. Test scroll follow, manual scroll interruption, and post-completion stability.
5. Compare parse strategies: full reparse, batched parse, incremental block parser.

Gate:

- Pass: no obvious freezing during dogfood-sized stress and parse strategy is clear.
- Limited: needs batching/throttling strategy before Phase 2.
- Fail: Chat rendering architecture must be redesigned before Phase 2.

### WS4: Agent Loop and Transformer Fixture Spike

**Android anchors:** `core/agent-runtime`, AI provider abstractions, message transformer pipeline.

Tasks:

1. Extract or write fixtures for prompt → streamed deltas → tool call → tool result → final message.
2. Add fixtures for ThinkTag, template, regex transform, document-as-prompt, and error cases.
3. Define expected normalized UIMessage-like JSON outputs.
4. Use fixtures as Phase 1 ArkTS rewrite contract.

Gate:

- Pass: fixtures can be replayed without Android runtime.
- Limited: fixtures need manual curation but cover key semantics.
- Fail: ArkTS rewrite lacks reliable semantic target.

### WS5: Rust/NAPI Evaluation Spike

**Android anchors:** `native/tokenizer/src/lib.rs`, `native/Cargo.toml`, `TokenCounterNative.kt`.

Tasks:

1. Create a minimal NAPI wrapper plan for tokenizer.
2. Define a tiny corpus: model name + two text samples + expected token counts from Android/Rust baseline.
3. Validate HarmonyOS native module import shape.
4. If Rust direct NAPI is blocked, try C++ shim calling Rust staticlib.
5. Document ArkTS fallback for tokenizer/highlight/markdown/document parsing.

Gate:

- Pass: same token counts on device.
- Limited: C++ shim works but workflow is awkward.
- Fail: native module path is deferred; ArkTS fallbacks ship first.

Important: WS5 does not block Phase 1 Chat unless all fallback strategies also fail.

### WS6: Platform Capability Spikes

**Android anchors:** Accessibility, notification listener, MediaProjection, terminal, WorkManager, WebView, Files, TTS.

Tasks:

1. Accessibility: implement observe/tap/swipe/back/find text if public API permits.
2. Notification: verify absence/presence of third-party notification read API.
3. Terminal: test sandbox native process execution policy with a harmless binary if policy permits.
4. Background: test short-time task, continuous task eligibility, and work scheduler limits.
5. ArkWeb: local page + JS bridge both directions.
6. File: picker → sandbox copy → persisted access.
7. TTS/audio: cloud-audio playback plus system TTS availability.
8. Clipboard/Want/share: test replacement primitives for user-confirmed actions.

Gate:

- Each capability gets one of: `P0/P1/P2`, `replace`, `drop`, or `unknown with blocker`.
- No P2 capability may remain an unstated assumption.

### WS7: Inventory Scripts

**Goal:** make Android source anchors reproducible instead of hand-maintained.

Deliverables:

- `scripts/harmony-inventory/scan-harmony-inventory.mjs`
- Generated Markdown under `docs/harmony-inventory/`:
  - `rust-native.md`
  - `providers-streaming.md`
  - `storage-room-preferences.md`
  - `platform-android-apis.md`
  - `tools.md`
  - `ui-pages.md`

Gate:

- Scripts are read-only with respect to source files.
- Generated inventory paths and matches are deterministic enough to review.

## AI-Assisted Execution Model

Use parallel workers where workstreams do not share generated files:

- Worker A: Rust/NAPI anchors and fallback strategy.
- Worker B: SSE Provider ArkTS skeleton and parser contract.
- Worker C: RDB/Preferences schema and DAO plan.
- Worker D: Platform capability API verification and matrix updates.
- Worker E: Inventory scripts.
- Worker F: Agent loop/transformer fixtures.

Orchestrator owns:

- final architecture decisions;
- updating `docs/harmony-capability-matrix.md`;
- verifying that generated scaffolds do not pretend to be tested;
- asking oracle for phase review before Phase 1.

## Immediate Implementation Checklist

- [ ] Create safe `harmony/` placeholder scaffold.
- [ ] Create `scripts/harmony-inventory/` scripts.
- [ ] Generate initial inventory Markdown files under `docs/harmony-inventory/`.
- [ ] Fill `docs/harmony-capability-matrix.md` Evidence Log after each spike.
- [ ] Update `progress.md` and `.slim/deepwork/harmony-native-phase0.md` after each milestone.

## Non-Goals

- Do not implement the full HarmonyOS app in Phase 0.
- Do not migrate all UI pages in Phase 0.
- Do not treat Rust NAPI as a Chat go/no-go item while ArkTS fallbacks exist.
- Do not commit API keys, generated build artifacts, or fake DevEco project files.
- Do not keep Android-only assumptions hidden; classify them explicitly.

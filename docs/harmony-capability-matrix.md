# HarmonyOS Phase 0 Capability Matrix

> Scope: Phase 0 feasibility spike for native HarmonyOS NEXT migration.  
> Branch/worktree: `harmony-preview` / `/Users/mi/Downloads/AI/AmberAgent-harmony-preview`  
> Status values: `not_started`, `toolchain_blocked`, `spike_ready`, `in_progress`, `pass`, `limited`, `fail`, `defer`.  
> Decision values: `P0`, `P1`, `P2`, `replace`, `drop`, `unknown`.

## Gate 0: Toolchain Readiness

No result may be marked `pass`, `limited`, or `fail` until the toolchain is available and the test has run on a HarmonyOS NEXT target or confirmed emulator.

| Gate | Requirement | Evidence Needed | Status |
|---|---|---|---|
| G0-01 | DevEco Studio installed | Version and SDK path recorded | toolchain_blocked |
| G0-02 | HarmonyOS NEXT SDK / Full SDK available | API level, Full SDK availability for AccessibilityExtensionAbility | toolchain_blocked |
| G0-03 | Signing/dev certificate configured | Debug signing can install a demo HAP | toolchain_blocked |
| G0-04 | Device or emulator available | Model/API level, install/run screenshot or logs | toolchain_blocked |
| G0-05 | Native build toolchain available | C++ NAPI demo can build, Rust path classified | toolchain_blocked |

Toolchain probe IDs in `docs/harmony-gate0-report.md` use `G0-Txx` to avoid ID collisions. Conceptual mapping: `G0-01` ← `G0-T01` or `G0-T02`; `G0-02` ← `G0-T03..G0-T08` plus `G0-T14`; `G0-03` requires DevEco signing setup outside this script; `G0-04` ← `G0-T08..G0-T09`; `G0-05` ← `G0-T11..G0-T13` plus HarmonyOS native SDK.

## True Phase 0 Go/No-Go Items

These are the only Phase 0 items that can block Phase 1 Chat work:

1. **HTTP streaming/SSE**: without incremental response delivery, AmberAgent's core chat experience changes materially.
2. **RDB + Preferences**: without local persistence, the app cannot maintain conversations/settings.
3. **ArkUI streaming + Markdown rendering**: without stable incremental rendering, the chat surface is not viable.

Everything else is an evaluation item with fallback, replacement, or deferral options.

## Capability Matrix

| ID | Capability | Android Source Anchors | HarmonyOS Candidate | Official/Research Status | Phase 0 Spike | Gate | Status | Proposed Decision | Verified Decision | Owner |
|---|---|---|---|---|---|---|---|---|---|---|
| H0-01 | OpenAI-compatible streaming | `ai/src/main/java/app/amber/ai/util/SSE.kt`; `ai/src/main/java/app/amber/ai/provider/providers/openai/ChatCompletionsAPI.kt` | `@ohos.net.http` `requestInStream()` or RCP streaming | Officially supported streaming APIs | Minimal streaming client with cancellation, timeout, `[DONE]`, error parsing | Incremental deltas arrive before completion; cancellation stops request | toolchain_blocked | P0 | unknown | Provider worker |
| H0-02 | RDB conversation store | `core/agent-store-room/src/main/kotlin/app/amber/core/agent/store/AgentRuntimeDatabase.kt`; app Room schemas | `@ohos.data.relationalStore` / `@kit.ArkData` RDB | Officially supported | Create `conversation`, `message_node`, `ui_message`; insert/query/update; migration version check | Multi-message conversation survives restart | toolchain_blocked | P0 | unknown | Storage worker |
| H0-03 | Preferences/settings store | `core/settings/src/main/kotlin/app/amber/core/settings/PreferencesStore.kt`; `PreferencesKeys.kt` | `@ohos.data.preferences` | Officially supported | Store provider config, UI setting, and serialized settings object | Read/write/observe works; limits documented | toolchain_blocked | P0 | unknown | Storage worker |
| H0-04 | ArkUI streaming chat list + Markdown | Android chat UI; `jank-opt/`; native markdown crates | ArkUI `List`/`LazyForEach`/`Scroller` plus ArkTS Markdown fallback | List APIs supported; Markdown strategy must be verified | Append 5k chunks while incrementally parsing Markdown; test scroll-follow/manual-scroll behavior | No obvious freeze; batching/throttling strategy documented | toolchain_blocked | P0 | unknown | UI worker |
| H0-05 | Agent loop semantic fixture | `core/agent-runtime`; tool call/result models; AI provider abstractions | ArkTS fixture harness | Needs repository fixture extraction | Freeze prompt → provider delta → tool call → tool result → final message fixture | ArkTS Phase 1 can replay fixture without semantic drift | spike_ready | P0 | unknown | Agent-core worker |
| H0-06 | Provider configuration and secrets | `PreferencesStore.kt`; provider prefs | Preferences plus HarmonyOS secret/encrypted storage if available | Preferences supported; secret storage needs verification | Store base URL/model/API key; reload after restart; audit logs/export | Secrets are not logged; export policy documented | toolchain_blocked | P0 | unknown | Storage worker |
| H0-07 | Ability lifecycle + permissions | `RouteActivity.kt`; Android service declarations | `UIAbility`, ExtensionAbility, `module.json5` permissions | Officially supported | Minimal lifecycle logger and permission declarations | Start/background/destroy/reopen map documented | toolchain_blocked | P0 | unknown | Platform worker |
| H0-08 | Rust native reuse strategy | `native/Cargo.toml`; `native/tokenizer/src/lib.rs`; `native/jni-common/src/lib.rs` | Node-API native module; possible C++ shim to Rust | NAPI officially supported for C/C++; Rust needs verification | Try tokenizer NAPI after Gate 0; define ArkTS fallback if blocked | Classified as pass/limited/fail; must not block Chat if fallback exists | spike_ready | P1 | unknown | Native worker |
| H0-09 | Syntax highlighting | `native/highlight-parser/src/lib.rs`; `HighlighterNative.kt` | ArkTS highlighter fallback; optional NAPI optimization | Native path optional in Android | Compare ArkTS fallback vs NAPI feasibility | Code block rendering acceptable for common languages | not_started | P1 | unknown | UI/Native worker |
| H0-10 | Markdown preprocessing/rendering | `native/markdown-parser`; `native/markdown-preprocess`; `native/html-diff-normalizer` | ArkTS Markdown parser first; NAPI optional | Native optimization optional | Render streaming Markdown samples with code/list/table | Visual output stable; incremental cost acceptable | spike_ready | P0 | unknown | UI worker |
| H0-11 | Message transformer pipeline | `app/src/main/java/app/amber/agent/data/ai/transformers/`; `native/regex-transformer` | ArkTS transformers | Needs ArkTS rewrite | Freeze fixtures for template/think/regex/document transforms | Fixture outputs match Android semantics or differences documented | spike_ready | P1 | unknown | Agent-core worker |
| H0-12 | Sync/export crypto compatibility | `native/sync-crypto`; backup/export code paths | ArkTS crypto or NAPI | Must be byte-compatible for encrypted backup import | Encrypt/decrypt a small Android-produced fixture | Byte-compatible or migration format revised | spike_ready | P1 | unknown | Storage worker |
| H0-13 | Reader/document extraction | `native/reader-extractor`; `native/office-parsers`; document module | ArkTS/cloud fallback; optional NAPI | Native optimization optional | Parse one text/docx fixture or classify fallback | Deep-read input extraction has a Phase 1/2 path | not_started | P1 | unknown | Document worker |
| H0-14 | Accessibility automation | `app/src/main/java/app/amber/core/automation/AmberAccessibilityService.kt` | `AccessibilityExtensionAbility` | Officially supported; parity unknown; user enablement required | Implement observe/tap/swipe/back/find text if public API permits | Classify full/limited/unavailable against observe-act-verify interface | toolchain_blocked | P2 | unknown | Platform worker |
| H0-15 | Notification listening | `AmberNotificationListenerService.kt`; `NotificationsAccessTools.kt` | Notification Kit | Publishing supported; no ordinary third-party listener found | Verify no public third-party notification read API | If absent, mark replacement: data-source aggregation | spike_ready | replace | unknown | Platform worker |
| H0-16 | MediaProjection / screen capture | `ScreenCaptureService.kt`; `ScreenCapturePermissionActivity.kt` | Public screen capture equivalent if any | Unknown | Check user-consented screenshot APIs and restrictions | Visual grounding classified possible/limited/unavailable | not_started | P2 | unknown | Platform worker |
| H0-17 | Local terminal / proot | `feature/terminal/.../TerminalRuntime.kt`; `AlpineRuntimeInstaller.kt` | None known; sandbox process execution check | Unknown/high risk | Test harmless bundled native binary only if policy permits | If blocked/non-shippable, replace with remote/desktop/cloud seat | spike_ready | replace | unknown | Terminal worker |
| H0-18 | Background generation | `AgentCronWorker.kt`; `BoardWorker.kt`; `DeepReadWorker.kt` | Background Tasks Kit | Supported with category/time limits | Test short-time task and Work Scheduler; classify long generation | Foreground-only vs background-supported behavior decided | toolchain_blocked | P1 | unknown | Platform worker |
| H0-19 | ArkWeb + JS bridge | `WebViewPool.kt`; `JsBridge.kt`; `WebViewScreenshot.kt` | ArkWeb `Web` + JavaScript proxy | Officially supported | Local HTML + ArkTS bridge both directions | Bridge works on local/trusted URLs; security rule documented | toolchain_blocked | P1 | unknown | Web worker |
| H0-20 | File picker + sandbox | `FilesManager.kt`; `ExternalFileTools.kt`; `ChatInput.kt` picker contracts | Core File Kit + picker + persisted permission | Officially supported | Pick image/doc, copy to sandbox, test persisted access | Attachment lifecycle documented | toolchain_blocked | P1 | unknown | Platform worker |
| H0-21 | TTS + audio playback | `SystemTTSProvider.kt`; `TTSManager.kt` | AudioRenderer; CoreSpeechKit TTS | Audio supported; TTS needs verification | Play cloud audio buffer; test system TTS if SDK permits | Cloud playback works; system TTS pass/limited/fail | toolchain_blocked | P1 | unknown | TTS worker |
| H0-22 | Clipboard / Want / share replacements | Android share/intent/clipboard usage inventory | HarmonyOS pasteboard, Want, share APIs | Needs docs/spike | Inventory Android usage; test one share/clipboard action | Replacement UX for automation fallbacks documented | not_started | P1 | unknown | Platform worker |
| H0-23 | Image/OCR input | OCR transformer; image attachment pipeline | HarmonyOS image APIs or cloud OCR fallback | Needs docs/spike | Pick image, attach to prompt, OCR fallback strategy | Image prompt path exists; OCR classified | not_started | P1 | unknown | Media worker |

## Decision Rules

- `Verified Decision` may only change after a real spike has run or a documented platform limitation is confirmed.
- A `P0` verified failure must get a fallback before Phase 1 starts.
- Rust/NAPI failures do not block Phase 1 Chat if ArkTS fallbacks meet acceptable quality/performance.
- A `replace` decision must include the replacement product behavior and UI copy before Phase 2 starts.
- `Accessibility`, `notification listening`, and `terminal` must not block P0 Chat unless their replacement requires architecture changes.
- Spike code may be disposable; spike findings are not disposable and must be recorded here and in `progress.md`.

## Evidence Log

| Date | Capability | Evidence | Result | Follow-up |
|---|---|---|---|---|
| 2026-06-15 | H0-01..H0-23 | Initial matrix created from official-doc research, Android source inventory, and oracle plan review | Baseline proposed decisions assigned; verified decisions remain `unknown` | Begin inventory scripts and Gate 0/toolchain check |
| 2026-06-15 | Gate 0 | `docs/harmony-gate0-report.md` generated by `scripts/harmony-inventory/check-harmony-gate0.mjs`; Node/rustc/cargo found; DevEco/hvigor/ohpm/hdc/device target missing | Gate 0 is `toolchain_blocked`; no device/SDK-dependent spike may be marked verified | Continue source-only prep: fixtures, contracts, inventories, replacement decisions |
| 2026-06-15 | H0-01/H0-02/H0-04/H0-05/H0-10/H0-11 | Source-only fixtures and contracts created under `harmony/tests/fixtures/`; validation script passed 51 JSON/JSONL records | Contracts are ready for future ArkTS implementation, but verified decisions remain `unknown` until Gate 0 unblocks | Use fixtures as Phase 1 rewrite contract and device-spike input |
| 2026-06-15 | H0-01/H0-02/H0-05/H0-11 | Executable source-only contract checks added in `scripts/harmony-inventory/check-source-contracts.mjs`; fixture validation passed 54 records; source contract checks passed 10 groups | Source-only parser/storage/agent-loop/transformer contracts are internally consistent; still not HarmonyOS-verified | Stop source-only expansion after caveats; wait for Gate 0 to unblock for device/SDK spikes |
| 2026-07-16 | Gate 0 / build readiness | Fixed `hdc [Empty]` false-positive; updated Harmony `build-profile.json5` from legacy `5.0.0(12)` to installed API 26 `26.0.0`; added `scripts/harmony-inventory/build-harmony-app.mjs`; `node scripts/harmony-inventory/build-harmony-app.mjs` completed `BUILD SUCCESSFUL` | Command-line build is verified; device/runtime Gate 0 remains blocked because `hdc list targets` returns no device/emulator | Connect device/emulator target, then run real P0 runtime spikes for SSE, RDB/Preferences, ArkUI streaming Markdown |

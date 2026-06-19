# Phase 2 Capability Gate Matrix

## Start State

- Branch: `codex/ios-port-wip`, ahead of `origin/codex/ios-port-wip` by 16.
- `git status --short --branch`: only untracked `.workflow/` at start.
- `git log --oneline --decorate -12`: starts at `dd0f86c6f Consolidate iOS capability parity work`.

## Gates

| Capability | Android fact anchors | iOS fact anchors | Gate status before implementation | Phase 2 close criteria |
| --- | --- | --- | --- | --- |
| Memory | `app/src/main/java/app/amber/core/memory/*`, `MemoryTools.kt`, memory DB/tests | `IOSMemoryPersistence.swift`, `MemoryOverviewView.swift`, `MemoryEditView.swift`, `ChatViewModel.swift`, `MemoryToolApprovalCard.swift`, memory tests | Partial. Persistence, prompt injection, tool list/create/edit/delete, and foreground write approval exist. UI is mostly switches plus a mixed edit/list page; no first-class search/filter/source/recall explanation or single-record editor. | Search/filter records; view source metadata; edit/delete one record; show recall explanation; show write approval/audit evidence; preserve current prompt injection and approval tests. |
| Search | `SearchTools.kt`, `SearchOrchestrator.kt`, `SearchAggregator.kt`, provider services/tests | `IOSSearchExecutor.swift`, `SearchServicesView.swift`, `SearchProviderView.swift`, `ChatViewModel.swift`, search tests | Partial. Provider selection, built-in fallback, `search_web`, `scrape_web`, citations as text, and chat approval exist. UI lacks runnable search/scrape console, provider status/fallback visibility, and normalized result management outside chat. | Configure provider; run search and scrape locally; show source/provider/fallback/citations; graceful disabled/error states; test provider selection, normalization, citations, scrape, fallback. |
| Image Generation | `ImageGenTool.kt`, `ImageGenerationRepository.kt`, `ImgGenVM.kt`, `ImgGenPage.kt`, generated image cards | No dedicated iOS image-generation files; `MessageBubbleView` does not render `UIMessagePart.Image`; `ChatViewModel` does not declare/execute `generate_image`. | Missing. No iOS entry, settings, generation runtime, history, save/export path, chat tool, or image rendering. | Add entry/page/settings; mockable OpenAI-compatible runtime; local history; save/export path; chat `generate_image` tool result; missing config/error states; tests for params/history/save/chat/error. |
| Mini App | `feature/miniapp/*`, bridge, storage/network/search, pages/tests | `IOSMiniAppModels.swift`, `IOSMiniAppRepository.swift`, `IOSMiniAppBridgeRuntime.swift`, `MiniAppListView.swift`, `MiniAppRunnerView.swift`, `MiniAppSettingsView.swift`, MiniApp tests | Near closed. Repository, sample seed, list/runner/settings, install/update/delete, grants/audit/storage/sharedStore/search/fetch/AI/host bridge, HTML validation, runtime errors exist. Settings only expose host context/write toggles; network/search/AI policy visibility is limited. | Keep working loop; expose bridge permission policy status honestly; ensure tests cover repository, runner bridge, storage/network/search/fetch permissions, install/update/delete, HTML errors. |

## Implementation Order

1. Memory UI/model helpers, then focused memory tests.
2. Search console/provider state, then focused search tests.
3. Image generation support files, route/UI/chat/tool declaration, then focused image tests.
4. Mini App small policy/status/test gap closure.
5. Roadmap and final verification.

## Close State

| Capability | Phase 2 result | Residual limits |
| --- | --- | --- |
| Memory | Closed for local loop: searchable/filterable manager, source summaries, single-record edit/delete, recall explanation, write approval/audit records, and focused tests. | Import/export remains tied to broader backup/workspace roadmap; no multi-assistant memory scope because iOS intentionally keeps one Amber Assistant. |
| Search | Closed for provider/use loop: services page can run `search_web`/`scrape_web`, shows provider/fallback/citations/errors, and preserves chat tool execution. | Real provider quality still depends on user API keys/network; no fake success when provider config is missing. P1 audit item remains: existing provider/API key persistence should move from UserDefaults-backed settings to Keychain in a dedicated security pass. |
| Image Generation | Closed for mockable and configured runtime loop: settings, page, OpenAI-compatible repository, local saved history, share/save UI, chat `generate_image` tool, image rendering, and focused tests. | Real generation requires a valid API key/model/base URL; reference-image/regenerate variants are not implemented in this pass. |
| Mini App | Closed for policy/safety gaps: runtime capability settings are visible, `fetch` blocks local/private hosts, WebKit navigation blocks external native-bridge inheritance, bridge tests updated. | Per-app granular grant UI remains basic; advanced WebMount sharing stayed out of scope. |

## Verification

- `git diff --check`: pass.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:linkDebugFrameworkIosSimulatorArm64`: pass after approving Gradle cache access.
- `env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "id=293252D5-CCF3-47DD-8736-8A8A26A6788C" build`: pass.
- `env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "id=293252D5-CCF3-47DD-8736-8A8A26A6788C" -derivedDataPath iosApp/.xcode-derived-data/phase2-tests test -only-testing:iosAppTests/IOSMemoryLibraryTests -only-testing:iosAppTests/IOSImageGenerationRepositoryTests -only-testing:iosAppTests/IOSMiniAppBridgeRuntimeTests -only-testing:iosAppTests/IOSSharedSettingsStoreSkillWriteBackTests -only-testing:iosAppTests/IOSSearchExecutorTests`: pass; temporary DerivedData removed.
- `generic/platform=iOS Simulator` build is not the valid verification target on this machine because it links x86_64 while the existing Shared/native simulator slices are arm64.

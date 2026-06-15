# iOS Capability Wiring Audit - 2026-06-15

This audit tracks which AmberAgent iOS SwiftUI surfaces are wired to real, repository-backed capabilities and which surfaces are still prototype/draft UI. It is intentionally conservative: if a capability is not backed by an inspected store, coordinator, runtime, provider, database, or KMP/Android implementation path, it is treated as not wired.

## 1. Capability Wiring Summary

### 已接真实能力

- App root wiring: `AmberAgentApp` owns one `SettingsStore`; `AppShell` injects `SettingsStore`, `IOSPermissionStore`, `DocumentAccessStore`, `IOSSystemPermissionCoordinator`, and `IOSLocalToolExecutor` into routed pages.
- Core OpenAI-compatible chat: `ChatViewModel` reads `SettingsStore.baseUrl`, `SettingsStore.apiKey`, and `SettingsStore.modelId`, builds `ProviderSetting.OpenAI`, calls `OpenAIKmpProvider.streamTextCancellable`, streams into `MessageStreamAccumulator`, supports cancellation, and records agent runs through `AgentRuntimeDatabase`.
- Chat reasoning parameter and model ability gate: `ChatViewModel` resolves the current model id once, hydrates `Model.abilities` from KMP `ModelRegistry.MODEL_ABILITIES`, and only passes a non-off `TextGenerationParams.reasoningLevel` when that real registry marks the model with `ModelAbility.REASONING`. Empty Chat no longer renders Open Design sample tool calls as if they were real history.
- Chat selected-file context: `ToolPermissionsView` uses SwiftUI `fileImporter`; `DocumentAccessStore` creates an in-memory one-file grant; `IOSLocalToolExecutor` routes `file_read_selected` through `IOSToolRuntime`; `ChatViewModel.attachSelectedFilePreviewToNextMessage()` appends a real bounded file preview to the next prompt.
- iOS permissions/capabilities: `ToolPermissionsView` reads `IOSCapabilityRegistry`, `IOSSystemPermissionCoordinator.cachedStatus`, and requests/refreshes permissions through the coordinator instead of fabricating status. Unsupported/non-requestable capabilities are represented by capability metadata.
- Permission policy/status substrate: `IOSPermissionStore` persists policies in `UserDefaults`; `IOSLocalToolExecutor.permissionsStatus()` exposes a real read-only status snapshot.
- Permission approval UI: `PermissionsApprovalView` now receives the app-level `IOSPermissionStore` from `AppShell` and edits the `ios.files.selected_read` policy consumed by the real `file_read_selected` tool path. Other system permissions stay on the full capability page until a matching iOS executor exists. `IOSPermissionStore` also normalizes legacy/programmatic `allowOncePerRun` values to `askEveryTime` until a true run-scoped approval store exists.
- Remote SSH runtime storage and gate: `SettingsStore` persists SSH profiles in `UserDefaults`, stores SSH passwords in Keychain through `IOSSSHSecretStore`, records trusted host fingerprints, and `RuntimeEnvironmentView` runs smoke tests through `IOSTerminalRuntime`.
- Runtime safety boundary: stable iOS runtime selection is constrained by `IOSTerminalBuildPolicy`; Remote SSH host mismatch is a hard failure before password save/command execution.
- Default model value: `SettingsHomeView`, `ModelDefaultsView`, and `ChatView` read/write `SettingsStore.modelId` for the single current OpenAI-compatible chat configuration. The composer picker no longer presents fake provider switching; it only writes the model id that `ChatViewModel.makeTextGenerationParams()` actually sends.
- Provider current config and presets: Settings home and `ProvidersView` now read the real current `SettingsStore` OpenAI-compatible config and expose Android/KMP `DEFAULT_PROVIDERS` as no-key preset templates. OpenAI-compatible templates can only write `SettingsStore.baseUrl`; Gemini, xAI, and Xiaomi MiMo templates are visible but blocked from one-click apply where the current iOS chain cannot faithfully represent their ProviderSetting/Response API/endpoint state.
- TTS default status: Settings home now shows the real KMP default `系统 TTS`, and the TTS page defaults to System TTS while marking cloud engine fields, preview, delete, and add flows as drafts with no save, no Keychain write, and no TTS request.
- Skill/MCP status honesty: Settings home, `SkillsView`, `SkillDetailView`, `SkillAddView`, and `McpServersView` no longer claim installed skill counts, enabled skill counts, connected MCP servers, tool counts, or toggle persistence. Android/KMP real capabilities remain documented, but iOS now presents this line as not yet bridged instead of fabricating state.
- Account stats honesty: `AccountView` no longer shows fake total conversations/messages/tokens/cache savings/launch counts or generated heatmap activity. It keeps the design shell but marks statistics as not wired until iOS exposes the Android `StatsVM`/DAO equivalents.

### 仍是草稿/占位

- Provider add/model edit/custom headers/body pages are draft-only. The Provider list can display KMP default templates, but iOS still does not read or persist the full mutable `Settings.providers` / `ProviderSetting` list, provider-specific API keys, provider models, custom headers/body, balances, or auth modes.
- Chat model picker is now a scalar `SettingsStore.modelId` editor, not a full provider/model registry. It still cannot switch `SettingsStore.baseUrl`, API key, auth mode, custom headers/body, or provider-specific model metadata.
- Chat context usage is still not a real token/window estimator. The context popover now avoids precise fake token/cache/speed numbers and only shows ViewModel/KMP-verifiable state: message count, current model id, KMP reasoning ability marker, and pending selected-file preview.
- TTS settings are still not wired to an iOS `TTSProviderSetting` store or real `TTSManager` execution path. The page has been downgraded to real default status plus draft-only cloud fields.
- Skills page is not wired to the real Android/KMP `SkillManager` / `SkillsVM` scanning/import/editing path. It has been downgraded to an explicit not-wired status with draft-only add/import/rescan flows.
- MCP server page is not wired to `Settings.mcpServers`, `McpServerConfig`, `McpManager`, or `McpImportParser`. The previous hardcoded connected-server list and isolated `@AppStorage` toggles have been removed; import/add remain draft-only, and import uses only rough text preview rather than real parser-backed validation.
- Account profile is still local preview only, with no inspected persistent account/profile store. Conversation storage still shows precise prototype usage/count values and needs a separate pass.
- Search services, Sync/Backup, Memory, Board, Council, SubAgent, MiniApp, and WebMount pages need separate verification before any values or actions can be treated as real.

### 不应接线的孤儿入口 / 不应误导的入口

- Provider "Add", Model "Done", Model custom Headers/Body "Done": safe as drafts only until a real iOS provider settings store exists. They must not claim to save.
- Provider preset templates: safe to show because Android/KMP ships `DEFAULT_PROVIDERS`, but they must not be labeled as configured accounts. Templates must not prefill API keys, fetch models, refresh balance, or imply multiple ProviderSettings are persisted on iOS.
- Chat empty-state sample messages/tool timeline/reasoning: should not be shown as default history. Real tool calls should appear only from `UIMessagePart.Tool` emitted through the message model.
- TTS cloud engine save/delete/preview: must remain disabled/draft or be wired to verified secure storage and real TTS providers. Do not run network preview as validation.
- MCP import/add "Done": replaced with `关闭`; must not show import success unless parsed and persisted through a real settings path.
- Skills import/rescan: must not claim success until local scan/import implementation is available on iOS; current copy explicitly says no download, no file picker, no filesystem mutation.
- Account exact usage stats and heatmap: downgraded in Slice 7; do not reintroduce precise values until a real iOS stats bridge exists.
- Conversation storage cleanup/delete: current alerts correctly do not delete data; exact storage values should not remain as if measured.

### 高风险区域

- Secret storage split: the single OpenAI API key is in Keychain, but general provider/TTS API keys are not yet modeled in iOS. Avoid expanding secret handling without a clear schema.
- Provider/model identity: current iOS chat creates a fresh `ProviderSetting.OpenAI` and fresh `Model` from three scalar settings. It cannot represent multiple providers, custom headers/body, OAuth/coding-plan auth modes, provider-specific models, Gemini's `ProviderSetting.Google`, or xAI's default `useResponseApi=true` template state.
- Permission approval scope: `Route.toolPermissions` routes to the approval-policy page and its "权限与能力" row routes to `ToolPermissionsView`; `SheetDestination.toolPermissions` still opens the full capability page directly. The approval page intentionally exposes only the implemented `file_read_selected` tool policy; photos/location/camera/notifications remain system-permission status/request entries until real Agent executors exist.
- Hardcoded precise numbers/status: Settings home and ConversationStorage still display exact counts/status that look real but are not backed by inspected iOS data. Provider count has been replaced by the current scalar config plus no-key KMP templates; TTS default status is now corrected to System TTS; Skill/MCP precise installed/connected counts have been removed; Account stats now show unavailable state instead of fake exact values.
- External effects: Provider connection test in legacy `SettingsView` calls `listModels`. Do not use it as routine validation unless the user explicitly supplies credentials/approves network use.

## 2. UI Entry Map

| 屏幕 | UI 入口 | 当前文件 | 真实能力是否存在 | 应接到哪里 | 当前处理 | 优先级 |
|---|---|---|---|---|---|---|
| Settings home | 外观 | `iosApp/iosApp/PlaceholderViews.swift` -> `.appearance` | Partial | local appearance settings if present | route exists; state mostly local in child page | P2 |
| Settings home | 显示与字体 | `PlaceholderViews.swift` -> `.displayFont` | Partial/unknown | display preferences store equivalent | hardcoded subtitle | P1 |
| Settings home | 核心记忆 | `PlaceholderViews.swift` -> `.memory` | Android/KMP memory exists; iOS wiring unknown | real memory store/page | route exists; needs separate audit | P1 |
| Settings home | 技能 | `PlaceholderViews.swift` -> `.skills` | Yes on Android: `SkillManager`, `SkillsVM` | iOS local skill scanning/import layer or shared bridge | subtitle downgraded to `Skill/MCP 配置桥尚未接线`; no installed/enabled count | P0 done/P1 bridge |
| Settings home | 执行与任务 | `PlaceholderViews.swift` -> `.execution` | Partial: Live Activity controller exists | `AgentLiveActivityController`, execution settings store | route exists; needs audit | P1 |
| Settings home | 工具权限 | `PlaceholderViews.swift` -> `.toolPermissions` | Yes | `PermissionsApprovalView(permissionStore:)` -> `.capabilities` -> `ToolPermissionsView(...)` | approval page edits the real `file_read_selected` policy; capability page is reachable for system permissions | P0 done |
| Settings home | 运行环境 | `PlaceholderViews.swift` -> `.sandbox` | Yes | `SettingsStore` + `IOSTerminalRuntime` + SSH Keychain | mostly wired; subtitle is generic/hardcoded | P1 |
| Settings home | 服务商 | `PlaceholderViews.swift` -> `.providers` | Yes in KMP: `DEFAULT_PROVIDERS`, `ProviderSetting`; partial iOS scalar config | `SettingsStore.baseUrl/apiKey` now; provider settings bridge/store later | value downgraded to current scalar `OpenAI-compatible`, not a fake count | P0 done/P1 bridge |
| Settings home | 默认模型 | `PlaceholderViews.swift` -> `.modelDefaults` | Partial | `SettingsStore.modelId`; later real model registry | chat model value is real scalar; options hardcoded | P1 |
| Settings home | 搜索服务 | `PlaceholderViews.swift` -> `.searchServices` | Android search providers exist | search settings store | value "4 个源" hardcoded | P1 |
| Settings home | 语音 TTS | `PlaceholderViews.swift` -> `.ttsSettings` | Yes in KMP: `TTSProviderSetting`, `DEFAULT_TTS_PROVIDERS` | TTS settings store + secure key handling | home value corrected to real default `系统 TTS`; settings page is draft-only beyond default status | P0 done/P1 store |
| Settings home | 同步与备份 | `PlaceholderViews.swift` -> `.syncBackup` | Android sync exists | iOS sync/backup implementation | prototype page; not audited | Blocked/P2 |
| Settings home | 对话存储 | `PlaceholderViews.swift` -> `.conversationStorage` | Conversation DB exists in KMP/Android; iOS stats unclear | real conversation/storage stats + safe cleanup | hardcoded exact usage/count | P1 |
| Settings home | 今日看板 | `PlaceholderViews.swift` -> `.board` | Android feature exists | board runtime/store | prototype; not audited | P2 |
| Settings home | 模型议会 | `PlaceholderViews.swift` -> `.council` | Android/KMP council exists | real council settings/runtime | prototype; not audited | P1 |
| Settings home | SubAgent | `PlaceholderViews.swift` -> `.subagents` | Android/KMP subagent exists | real subagent settings/runtime | prototype; not audited | P1 |
| Settings home | 小应用 | `PlaceholderViews.swift` -> `.miniApps` | Android miniapp exists | miniapp store/runtime | prototype; not audited | P2 |
| Settings home | WebMount | `PlaceholderViews.swift` -> `.webMount` | Android WebMount exists | real webmount profile store | prototype; not audited | P2 |
| Providers | list rows | `iosApp/iosApp/ProvidersView.swift` | Yes in KMP; partial iOS scalar config | current `SettingsStore` config plus `DEFAULT_PROVIDERS`; mutable list bridge later | shows real current config and no-key preset templates; no fake enabled/disabled accounts | P0 done/P1 bridge |
| Provider detail | config tab | `ProviderDetailView.swift` | Yes in KMP; partial iOS scalar config | current `SettingsStore` config plus selected preset template metadata | current config edits real Base URL/API Key; presets show no-key metadata and only safe apply actions | P0 done/P1 bridge |
| Provider detail | API Key row | `ProviderDetailView.swift` | secure storage exists only for single OpenAI key | provider-specific secure key storage | current config edits real Keychain value; preset templates explicitly show `未预置` | P0 done/P1 key schema |
| Model defaults | chat model | `ModelDefaultsView.swift` | Partial | `SettingsStore.modelId` | reads/writes scalar setting | P1 |
| Model defaults | image/aux/thinking/context | `ModelDefaultsView.swift` | KMP settings have richer model defaults | real settings bridge | local-only state | P1 |
| Chat | send/cancel streaming | `ChatView.swift`, `ChatViewModel.swift` | Yes | `OpenAIKmpProvider`, `MessageStreamAccumulator` | wired for single OpenAI-compatible provider | P0 done/needs hardening |
| Chat | attach selected file | `ChatView.swift`, `ChatViewModel.swift` | Yes | `IOSLocalToolExecutor` + `DocumentAccessStore` | wired; requires prior picker grant | P0 done |
| Chat | model picker | `ChatView.swift` | Partial | `SettingsStore.modelId`; later selected real provider/model from settings | writes the scalar model id used by generation; multi-provider switching intentionally not exposed until provider identity exists | P0 done/P1 provider registry |
| Chat | thinking/context controls | `ChatView.swift`, `ChatViewModel.swift` | Partial KMP params and model registry exist | `ModelRegistry.MODEL_ABILITIES`, `TextGenerationParams.reasoningLevel`, real usage stats | reasoning picker is gated by real model ability and writes generation params only for reasoning-capable models; context token/window stats downgraded to verifiable state only | P1 partial |
| Permissions | selected file | `ToolPermissionsView.swift` | Yes | `DocumentAccessStore` + `fileImporter` | wired | P0 done |
| Permissions | system permissions | `ToolPermissionsView.swift` | Yes | `IOSSystemPermissionCoordinator` | wired for listed capabilities | P0 done |
| Permissions | approval/policy page | `PermissionsApprovalView.swift` and `Route.toolPermissions` | Yes for `file_read_selected` | `IOSPermissionStore` policy consumed by `IOSToolRuntime.resolve()` | only exposes implemented selected-file tool policy; store normalizes run-scoped policy to ask-every-time because no true run scope exists | P0 done |
| Runtime | runtime picker/smoke | `RuntimeEnvironmentView.swift` | Yes | `SettingsStore` + `IOSTerminalRuntime` | wired | P0 done |
| Runtime | SSH profile/password/fingerprint | `RuntimeEnvironmentView.swift`, `SettingsStore.swift` | Yes | UserDefaults + Keychain + SSH trust | wired | P0 done |
| TTS | engine list/config | `TTSSettingsView.swift` | Yes in KMP | `TTSProviderSetting`, selected provider id, secure key storage | default status corrected to System TTS; cloud configs are explicitly draft/no-save/no-Keychain/no-request | P0 done/P1 store |
| TTS | add engine | `TTSSettingsView.swift` | Partial model-type evidence in KMP | real settings write + Keychain | explicitly draft, no save; not a complete KMP type bridge | P1 |
| Skills | installed list | `SkillsView.swift` | Yes Android `SkillManager` | iOS skill scanner/import bridge | hardcoded rows removed; shows explicit `Skill 扫描尚未接线` status | P0 done/P1 bridge |
| Skills | detail/add/import/rescan | `SkillDetailView.swift`, `SkillDraftViews.swift`, `SkillsView.swift` | Yes Android path | real local file scan/import/edit + `assistant.enabledSkills` | detail cannot claim version/source/enabled/files; add/import/rescan are draft/no-write | P1 |
| MCP | server list/toggles | `McpServersView.swift` | Yes Android/KMP `McpManager`/`McpServerConfig` | real `Settings.mcpServers` bridge | hardcoded connected list and isolated toggles removed; shows explicit not-wired status | P0 done/P1 bridge |
| MCP | import/add | `McpServersView.swift` | Yes `McpImportParser` | parse + persist `McpServerConfig` | rough text preview only; close button, no save/connect/header persistence | P1 |
| Account | profile name/avatar | `AccountView.swift` | No inspected persistent account store | local profile preference if added | local preview only; copy states no account storage is wired | P2 |
| Account | stats/heatmap | `AccountView.swift` | Android has `StatsVM`, `ConversationDAO`, `MessageStatsDAO`, `SettingsAggregator.launchCount`; iOS bridge not exposed | conversation/message/token usage stats | exact fake values removed; shows `未接线` and neutral heatmap | P1 done/P1 bridge |
| Conversation storage | usage/cleanup/delete | `ConversationStorageView.swift` | likely DB/cache sources exist; iOS service unclear | measured storage + safe transactions | hardcoded values; actions are no-op alerts | P1 |

## 3. Implementation Queue

### P0 - 已有能力但 UI 未接，影响核心使用

1. Done in Slice 5: Replace Provider list/home fake count/examples with current `SettingsStore` config plus no-key KMP default templates. Full mutable provider store remains P1.
2. Done in Slice 4: Replace Skills hardcoded list/count with clearly unavailable iOS bridge state until a real local scan/import layer exists.
3. Done in Slice 4: Replace MCP hardcoded connected servers and isolated toggles with explicit not-wired state until a real `mcpServers` bridge exists.

### P1 - 已有能力但只是硬编码状态

1. Settings home subtitles/trailing values: runtime, skills, providers, search, TTS, conversation storage, SubAgent/Council counts.
2. Chat and ModelDefaults need a real provider/model registry bridge before exposing multiple provider choices; current iOS generation only has a single OpenAI-compatible scalar config.
3. ModelDefaults auxiliary model/thinking/context controls: local-only state should either persist to real settings or be marked unavailable.
4. Done in Slice 6: Chat thinking level now hydrates `Model.abilities` from KMP `ModelRegistry` and writes `TextGenerationParams.reasoningLevel` only for reasoning-capable models; context popover no longer shows exact fake token/cache/speed numbers.
5. Account exact statistics replaced with unavailable copy in Slice 7; ConversationStorage still needs measured sources or unavailable/draft copy.
6. Runtime page subtitle/home value should reflect selected runtime/default SSH profile from `SettingsStore`.
7. Add policy editing for additional capabilities only after a matching iOS Agent executor exists; system-only permissions should stay in the capability status/request page.
8. Wire TTS to a real iOS settings bridge before enabling cloud provider save/delete/preview or system speech preview controls.
9. Wire full iOS Provider registry to `Settings.providers`, including provider-specific Keychain schema, auth mode, Response API, model list, custom headers/body, and balance settings before exposing multi-provider save/fetch behavior.

### P2 - 可改善但不阻塞

1. Account display name persistence if product wants local-only profile.
2. Appearance/display preference persistence.
3. Search services, Sync/Backup, Board, MiniApp, WebMount separate audits and staged wiring.
4. Clean up/deprecate legacy `SettingsView` if no longer reachable, after confirming no route depends on it.

### Blocked - 仓库缺少可验证 iOS 能力，不接

1. Provider-specific API key schema beyond the single `SettingsStore.apiKey` Keychain account: needs a designed storage model before saving arbitrary provider credentials.
2. Cloud TTS preview/network validation: requires user credentials and external network side effects; do not use for verification.
3. Real login/subscription/account: out of scope; no inspected implementation.
4. Destructive conversation cleanup/delete: requires a safe iOS storage transaction and backup decision path.
5. MCP live connection testing: external network side effect; do not perform without explicit approval.
6. Provider model fetching/balance refresh/network connection tests: external side effects; do not perform without explicit credentials and approval.
7. Real Chat context-window/token accounting: no inspected iOS usage estimator is wired to the composer context panel yet.

## 4. Phase Review Log

### Phase 1 - Audit map

- Status: drafted; subagent review completed and findings were folded into the queue.
- Scope: read iOS SwiftUI pages, `SettingsStore`, `ChatViewModel`, permission/runtime stores, KMP provider/TTS/MCP/Skill evidence, Open Design design/inventory docs.
- Review focus requested: route reachability, UI -> store/viewmodel -> runtime/provider/coordinator -> persistence/error closure, and hardcoded fake-status risks.
- Review findings applied: `Route.toolPermissions` is a two-step approval/capabilities flow, Chat model selection previously wrote only local preview state, TTS/MCP/Skills/Stats still expose hardcoded precise states, and Chat run statistics cannot yet be trusted because run records do not include conversation/message IDs.

### Slice 1 - Permission approval policy wiring

- Scope: `AppShell` now injects `IOSPermissionStore` into `PermissionsApprovalView`; `PermissionsApprovalView` edits the real `ios.files.selected_read` policy consumed by `IOSToolRuntime.resolve()` instead of isolated global `@AppStorage` switches. The page no longer exposes policy rows for capabilities without iOS Agent executors.
- Verification:
  - `mcp__xcodebuildmcp.test_sim` with `-only-testing:iosAppTests/IOSPermissionStoreTests` and `-only-testing:iosAppTests/IOSToolRuntimeTests` failed before execution because existing `iosAppTests` target has no Info.plist or generated Info.plist setting.
  - `mcp__xcodebuildmcp.test_sim` with `IOSPermissionStoreTests`, `IOSToolRuntimeTests`, and `IOSLocalToolExecutorTests` discovered the updated tests but again failed before execution on the same existing `iosAppTests` Info.plist issue.
  - `mcp__xcodebuildmcp.build_run_sim` succeeded for `iosApp` on iPhone 17 / iOS 26.5 before and after both reviewer P0 fixes.
  - UI snapshot path: Settings -> 工具权限 -> 权限与批准 showed only `Selected file read` under `已实现工具策略`.
  - UI snapshot path: Selected file read policy menu showed only `禁用` and `每次询问`; the persisted-but-not-run-scoped `allowOncePerRun` option is not exposed.
  - UI snapshot path: 权限与批准 -> 权限与能力 reached `ToolPermissionsView` with real file/photo/camera/location/notification statuses.
- Screenshot paths:
  - 权限与批准 final: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_c8fcc6f8-9389-43b6-9b4f-1ce0d5ebb533.jpg`
  - 权限策略菜单 after reviewer fix: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_55175ef3-1d61-4b44-aea7-181d9f90ccd8.jpg`
  - 权限与能力: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_f5af971b-2341-4ab6-b621-ac3fe0dca739.jpg`
- Subagent review: completed twice. First P0 findings were that non-executable system permissions were overexposed as approval policies and `allowOncePerRun` was persisted rather than truly run-scoped. Follow-up review found UI-only hiding was insufficient because store/runtime could still consume saved values.
- Review fixes applied: approval UI now exposes only `file_read_selected`; `IOSPermissionStore.availablePolicies(for:)` no longer offers `allowOncePerRun`; loading legacy saved values, `policy(for:)`, and `setPolicy(_:)` normalize `allowOncePerRun` to `askEveryTime`; runtime resolution therefore still requires foreground user action for non-user-initiated selected-file reads.
- Remaining risk: the enum case remains for compatibility with old persisted raw values, but it is no longer an offered or effective policy. A future true run-scoped approval store should introduce explicit run identity/state before re-exposing it.

### Slice 2 - Chat default model scalar wiring

- Scope: `ChatView` model sheet and `ModelDefaultsView` now write `SettingsStore.modelId`, the same scalar read by `ChatViewModel.makeTextGenerationParams()`. The sheet was downgraded from a multi-provider picker to a current OpenAI-compatible configuration picker so it does not imply provider/base URL/API key switching.
- Verification:
  - `mcp__xcodebuildmcp.build_run_sim` succeeded for `iosApp` on iPhone 17 / iOS 26.5 after the Chat/ModelDefaults edits.
  - UI snapshot path: Home -> New Chat -> focus composer -> model picker showed only `当前 OpenAI-compatible 配置` and the selected `gpt-4o` model id.
  - UI snapshot path: Settings -> 默认模型 showed the home value and the model menu using `gpt-4o`, not display-name-only values.
- Screenshot paths:
  - Chat model picker: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_68fdd963-bff6-42fd-8994-20e881674d5d.jpg`
  - Model defaults menu: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_673aeceb-6d2c-406f-a845-6a095a414b68.jpg`
- Remaining risk: there is still no iOS provider/model registry bridge. Full provider switching, provider-specific model metadata, custom headers/body, auth mode, and secure per-provider key storage remain P1/P0 provider work rather than a Chat composer responsibility.

### Slice 3 - TTS default/status downgrade

- Scope: Settings home and `TTSSettingsView` now reflect the real KMP default `SystemTTS` instead of implying MiniMax is configured. Cloud engines remain visible as draft fields only, with no save, no Keychain write, no delete, and no preview request.
- Verification:
  - `mcp__xcodebuildmcp.build_run_sim` succeeded for `iosApp` on iPhone 17 / iOS 26.5 after the TTS edits.
  - UI snapshot path: Settings -> 语音 TTS showed Settings home value `系统 TTS`, selected System TTS row `system · 默认`, cloud rows marked `草稿`, and `试听尚未接线`.
  - UI snapshot path: selecting MiniMax showed empty API Key input and draft fields rather than a fake masked credential.
- Screenshot paths:
  - TTS System default page: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_d6260964-a52a-422c-bd29-d171530a61b6.jpg`
  - TTS MiniMax draft page: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_b09cfe6f-53eb-4303-abcf-6fcf70f0b01a.jpg`
- Subagent review: completed; no P0 findings. P1 copy fixes were applied so Add TTS uses `关闭`, delete reads `删除尚未接线`, and System TTS draft copy no longer claims an iOS synthesis executor is available.
- Remaining risk: no iOS TTS settings bridge or synthesis executor exists yet; cloud provider save/delete/preview must stay draft until secure provider-specific storage and execution are implemented.

### Slice 4 - Skill/MCP not-wired state downgrade

- Scope: Settings home and the Skill/MCP SwiftUI pages no longer expose hardcoded installed skill rows, enabled counts, connected MCP servers, tool counts, or isolated `@AppStorage` MCP toggles. The pages now name the missing iOS bridge explicitly and keep add/import/rescan/detail controls draft-only with no download, no file picker, no filesystem mutation, no settings write, no server connection, and no header/tool-approval persistence.
- Verification:
  - `mcp__xcodebuildmcp.build_run_sim` succeeded for `iosApp` on iPhone 17 / iOS 26.5 after the Skill/MCP edits, and again after subagent P2 copy fixes.
  - UI snapshot path: Settings home -> 技能 showed `Skill/MCP 配置桥尚未接线`, not installed/enabled counts.
  - UI snapshot path: Skills page showed `Skill 扫描尚未接线`, `MCP 服务器 ... 未接线`, and draft-only import/rescan rows.
  - UI snapshot path: MCP page showed `MCP 配置桥尚未接线`, no hardcoded connected servers, no tool counts, and no toggles.
  - UI snapshot path: MCP import/add pages showed `关闭`, rough text preview or local draft state only, no settings write, no server connection, no header persistence, and no tool-approval persistence.
  - UI snapshot path: Skill add page showed `关闭`, draft enable marker only, no `assistant.enabledSkills` write, and no file creation.
- Screenshot paths:
  - Skills page: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_8a8edbe9-4cbe-4744-b93e-9466812bd4d0.jpg`
  - MCP page: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_28127385-a371-4189-9bad-d0dc84ee8d2b.jpg`
  - MCP import draft: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_7f5a8ebe-bcfd-4bf9-8bbc-36a7ca6d75d4.jpg`
  - MCP add draft: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_22d1d550-5b7f-4587-93f5-2c122e2ae111.jpg`
  - Skill add draft: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_8062fef7-0eef-4ce8-89e2-784a5b7f7a4f.jpg`
- Subagent review: completed by `Volta`; no P0/P1 findings. P2 copy risks were fixed: MCP status badge now says `未接线` instead of `未连接`, Skill detail trigger footer no longer implies Agent uses placeholder trigger text, and MCP import copy now says rough text preview without real `McpImportParser` validation.
- Remaining risk: Android/KMP has real `SkillManager`, `SkillsVM`, `SkillDetailVM`, `McpManager`, `McpManagementTools`, and `McpImportParser`, but no inspected iOS `SettingsStore` fields or bridge currently expose `enabledSkills`, `mcpServers`, MCP status, or local Skill filesystem access. These pages are honest placeholders until that bridge exists.

### Slice 5 - Provider preset templates and current config wiring

- Scope: `AppShell` now injects `SettingsStore` into Provider routes. Settings home shows the current scalar Provider state instead of a fake provider count. `ProvidersView` displays the real current OpenAI-compatible config plus the 9 Android/KMP `DEFAULT_PROVIDERS` as no-key preset templates. `ProviderDetailView` edits the current `SettingsStore.baseUrl` and existing Keychain-backed API key, while preset templates show no-key metadata and only safe actions. `ModelEditView` and custom headers/body pages have been downgraded to draft-only copy because KMP `Model` metadata is not bridged to iOS.
- Verification:
  - `mcp__xcodebuildmcp.session_show_defaults` confirmed project `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/AmberAgent.xcodeproj`, scheme `iosApp`, simulator `iPhone 17`.
  - `mcp__xcodebuildmcp.build_run_sim` succeeded for `iosApp` on iPhone 17 / iOS 26.5 after Provider/Model edits, again after explicit `ProviderRouteKind` routing, and again after subagent P2 copy/validation fixes.
  - UI snapshot path: Settings home showed Provider value `OpenAI-compatible`, not a hardcoded provider count.
  - UI snapshot path: Providers page showed current config plus all 9 KMP default templates with no API key and no fake enabled/connected state.
  - UI snapshot path: Current Provider detail showed editable real API Key/Base URL fields and static unbridged Response API/balance rows.
  - UI snapshot path: DeepSeek template detail showed `未预置` API Key and `写入当前配置`, with footer limiting the action to `SettingsStore.baseUrl` only; the action was not tapped during validation.
  - UI snapshot path: xAI template detail showed `需 Response API`, `/responses`, and did not imply that current `ChatViewModel` can represent `useResponseApi=true`.
  - UI snapshot path: Gemini template detail showed `Google ProviderSetting`, `需桥接`, and did not write the Gemini Base URL into the OpenAI-compatible chain.
  - UI snapshot path: Provider models tab showed only the current scalar `SettingsStore.modelId`; Add Model page showed draft-only copy and `关闭`, not save.
- Screenshot paths:
  - Provider list: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_57757204-1e6a-4f0b-a145-8b18605b7edd.jpg`
  - Current Provider detail: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_068cea10-be7a-4171-8fad-f203d5acbf79.jpg`
  - DeepSeek preset detail: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_bd652fd6-e297-465f-ba7a-5b20d7270fd1.jpg`
  - xAI preset detail: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_95c3b193-b3de-449f-ba96-409a729a04e2.jpg`
  - Gemini preset detail: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_35015d88-5f78-4c2a-84bc-d48b9d412984.jpg`
  - Provider model tab: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_bd7e5b54-4ef2-4c5d-952c-ff9fabe99528.jpg`
  - Add Model draft: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_2c6ff076-8f60-4004-9da6-6928a4266392.jpg`
- Subagent review: completed by `Galileo`; no P0/P1 findings. P2 fixes applied: Provider list now says `API Key 已/未填写` rather than claiming Keychain persistence status, current Provider footer no longer implies Keychain write success confirmation, Base URL validation now requires `http`/`https` and a non-empty host, and template type routing uses explicit `ProviderRouteKind` instead of provider-name string checks.
- Remaining risk: iOS still lacks full `Settings.providers` persistence, provider-specific Keychain accounts, Gemini/Claude provider type switching, xAI Response API persistence, custom headers/body, model list fetch/save, balance refresh, and provider-specific model metadata.

### Slice 6 - Chat reasoning and context honesty

- Scope: `ChatView` thinking picker now uses KMP-backed reasoning levels, but it is gated by `ChatViewModel.currentModelSupportsReasoning`. `ChatViewModel.makeTextGenerationParams()` hydrates `Model.abilities` from KMP `ModelRegistry.MODEL_ABILITIES` and only passes a non-off `TextGenerationParams.reasoningLevel` when the registry marks the model with `ModelAbility.REASONING`. The context popover no longer shows hardcoded percent/token/cache/speed figures and instead shows only `ChatContextSnapshot` values from the ViewModel/KMP registry. Empty Chat now shows a neutral empty state rather than Open Design sample messages/tool timeline/reasoning.
- Verification:
  - `mcp__xcodebuildmcp.session_show_defaults` confirmed project `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/AmberAgent.xcodeproj`, scheme `iosApp`, simulator `iPhone 17`.
  - `mcp__xcodebuildmcp.build_run_sim` succeeded for `iosApp` on iPhone 17 / iOS 26.5 after the Chat edits, and again after the subagent-discovered ModelAbility gate fix.
  - UI snapshot path: New Chat showed `Amber / 准备好了`, not sample messages or sample tool timeline.
  - UI snapshot path: focusing the composer showed model `gpt-4o`, thinking value `关闭`, and context accessibility value `0 条消息`.
  - UI snapshot path before the ability-gate fix showed the raw reasoning enum options. Subagent review caught that this overclaimed for models whose `Model.abilities` did not include `REASONING`.
  - UI snapshot path after the fix: default `gpt-4o` shows composer accessibility value `当前模型未标记 Reasoning` and the thinking popover shows `Reasoning 未启用`, because KMP `ModelRegistry` does not mark it as a reasoning model.
  - UI snapshot path after the fix: context popover showed `Token 统计未接线`, `当前消息 0 条`, `当前模型 gpt-4o`, `Reasoning 未标记`, and `待附加文件 无`; no send action or provider request was triggered.
- Screenshot paths:
  - Chat empty state: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_3c1ab3cc-749f-4d47-9138-5cba8e965423.jpg`
  - Reasoning blocked for default `gpt-4o`: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_61d677a7-5627-4060-849e-989c13abfc0c.jpg`
  - Context panel with KMP reasoning marker: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_fe700017-e5e3-449c-a678-20bfe72522df.jpg`
- Subagent review: completed by `Dewey`; P1 finding applied. Initial code passed `reasoningLevel` into `TextGenerationParams`, but the constructed `Model` had `abilities: []`, and KMP request builders only emit reasoning params for `ModelAbility.REASONING`. Fix: hydrate abilities from KMP `ModelRegistry.MODEL_ABILITIES`, gate non-off reasoning by that real ability, make UI unavailable when the current model is not marked reasoning-capable, and use one normalized model id for snapshot + params. Follow-up review found no P0/P1; its P2 model-name consistency finding was fixed by starting Live Activity with `params.model.modelId`.
- Remaining risk: reasoning is current ChatView state only; no inspected iOS settings/assistant bridge persists a default reasoning policy. Context token/window/cost/speed accounting remains unavailable until a real estimator or usage source is wired. Positive-path UI validation for a reasoning-capable model is not yet captured in this slice; the code path is gated by KMP registry and covered by build plus reviewer inspection.

### Slice 7 - Account stats honesty downgrade

- Scope: `AccountView` no longer displays hardcoded exact totals for conversations, messages, token usage, cache savings, or launch count, and its heatmap no longer generates synthetic activity. The page keeps the visual structure while marking stats as `未接线`; profile name/avatar are explicitly current-page preview only. Android evidence (`StatsVM`, `ConversationDAO`, `MessageStatsDAO`, `SettingsAggregator.launchCount`) remains documented as the real logic to bridge later, but no equivalent iOS stats store/repository is currently exposed.
- Verification:
  - `mcp__xcodebuildmcp.session_show_defaults` confirmed project `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/AmberAgent.xcodeproj`, scheme `iosApp`, simulator `iPhone 17`.
  - `mcp__xcodebuildmcp.build_run_sim` succeeded for `iosApp` on iPhone 17 / iOS 26.5 after the Account edits and the reviewer-driven heatmap fix.
  - UI snapshot: Home -> 我的账户 showed `当前页面预览`, profile storage not wired copy, `iOS 统计桥尚未接线`, `统计未接线`, and total conversation/message values as `未接线`.
  - UI snapshot path after scrolling showed input/output token, cache savings, and launch count all as `未接线`.
- Screenshot paths:
  - Account stats top: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_0c932d10-5e2a-4605-a57b-3a1212170a32.jpg`
  - Account stats detail: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_34919bbf-4cc2-4f31-b367-0b09939d6193.jpg`
- Subagent review: completed by `Gibbs`; no P0/P1 findings. P2 fixes applied: heatmap grid now sizes from available width instead of using a fixed width, and the month axis/`少`-`多` legend were replaced by an explicit `统计未接线` unavailable overlay so the neutral grid is not misread as real low activity.
- Remaining risk: no iOS Stats bridge or persistent profile/account store is wired. ConversationStorage still has separate hardcoded exact storage values and needs its own slice.

## 5. Commit Log

| commit hash | 接线范围 | 验证命令 | 截图路径 | 未覆盖风险 |
|---|---|---|---|---|
| `1cb4699d8` | Permission approval policy wiring; Chat default model scalar wiring | `test_sim` blocked by existing test target Info.plist issue; `build_run_sim` succeeded before/after reviewer fixes | see Slice 1 and Slice 2 screenshot paths | no provider/model registry bridge yet; enum retains `allowOncePerRun` for old-value compatibility, but store normalizes it to `askEveryTime` |
| `bf9eea664` | TTS default/status downgrade | `build_run_sim` succeeded | see Slice 3 screenshot paths | no iOS TTS settings bridge or synthesis executor yet |
| `a5dd008f6` | Skill/MCP not-wired state downgrade | `build_run_sim` succeeded before and after subagent P2 copy fixes | see Slice 4 screenshot paths | no iOS Skill/MCP settings bridge yet |
| `c77cc4b20` | Provider current config, no-key default templates, and model draft downgrade | `build_run_sim` succeeded before/after explicit route-kind and subagent P2 fixes | see Slice 5 screenshot paths | no full iOS provider registry, provider-specific Keychain schema, Response API persistence, or model metadata bridge yet |
| `c3d22f732` | Chat reasoning parameter wiring with KMP model ability gate, context-stat downgrade, and no-fake empty state | `build_run_sim` succeeded before and after subagent P1/P2 fixes | see Slice 6 screenshot paths | no persisted reasoning default; no real context token/window estimator |
| Pending | Account stats exact-value downgrade and profile preview honesty | `build_run_sim` succeeded before and after reviewer P2 heatmap fixes | see Slice 7 screenshot paths | no iOS Stats bridge; no persistent account/profile store; ConversationStorage still pending |

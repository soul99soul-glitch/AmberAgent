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
- Provider current config and presets: Settings home and `ProvidersView` now read the real current `SettingsStore` OpenAI-compatible config and expose Android/KMP `DEFAULT_PROVIDERS` as no-key preset templates. Preset provider templates are allowed because Android/KMP ships them as real provider defaults, but they are not treated as configured accounts and never prefill API keys. OpenAI-compatible templates can only write `SettingsStore.baseUrl`; Gemini, xAI, and Xiaomi MiMo templates are visible but blocked from one-click apply where the current iOS chain cannot faithfully represent their ProviderSetting/Response API/endpoint state.
- Model defaults honesty: `ModelDefaultsView` now keeps only the chat model picker wired to `SettingsStore.modelId`. Image generation, title/suggestion/OCR/compression models, default reasoning budget, context message count, and model group defaults are shown as not wired instead of saving local-only draft selections.
- TTS default status: Settings home now shows the real KMP default `系统 TTS`, and the TTS page defaults to System TTS while marking cloud engine fields, preview, delete, and add flows as drafts with no save, no Keychain write, and no TTS request.
- Skill/MCP status honesty: Settings home, `SkillsView`, `SkillDetailView`, `SkillAddView`, and `McpServersView` no longer claim installed skill counts, enabled skill counts, connected MCP servers, tool counts, or toggle persistence. Android/KMP real capabilities remain documented, but iOS now presents this line as not yet bridged instead of fabricating state.
- Account stats honesty: `AccountView` no longer shows fake total conversations/messages/tokens/cache savings/launch counts or generated heatmap activity. It keeps the design shell but marks statistics as not wired until iOS exposes the Android `StatsVM`/DAO equivalents.
- Conversation storage honesty: Settings home and `ConversationStorageView` no longer show fake exact file/count/MB values. Android evidence for the closest real source is `FilesManager.countChatFiles()`, but iOS currently lacks a matching files/conversation storage bridge, so the page is explicitly not wired and does not delete or clean up data.
- Settings home status honesty: `SettingsHomeView` now reads existing local `@AppStorage` appearance/display/execution preferences and `SettingsStore.terminalDefaultRuntime` / `sshProfiles` for runtime state. Memory, Search, Sync/Backup, Board, Council, SubAgent, MiniApp, and WebMount rows no longer show precise fake counts, cloud backup promises, source totals, executable-tool promises, or role examples; they explicitly mark missing iOS bridges instead.
- Search services honesty: `SearchServicesView` and `SearchProviderView` now present Android/KMP `SearchServiceOptions`, `SettingSearchPage`, `SearchPrefs`, `SearchTools`, and `SearchOrchestrator` as real capability evidence while making the iOS bridge gap explicit. The pages no longer show fake enabled counts, fake configured Serper/Tavily rows, masked API keys, local-only toggles, or "Done" saves; provider details are draft-only with empty API key fields, no Keychain write, no settings write, no delete, and no network test.
- Memory pages honesty: `MemoryOverviewView`, `MemoryEditView`, and `AgentsMarkdownView` now present Android/KMP `MemoryRepository`, `MemoryRecallStore`, `memory_tool`, `Settings.agentRuntime`, and `GenerationPrompts.buildAgentSoulPrompt()` as real capability evidence while making the missing iOS bridge explicit. Fake memory records, local `@AppStorage` toggles, fake agents.md persistence, "Done" saves, and destructive delete UI were removed; add/edit/agents.md pages are draft-only and do not write `UserDefaults`, `SettingsStore`, a memory repository, or a chat request.
- Model Council pages honesty: `CouncilView`, `CouncilSettingsView`, and `SeatEditorView` now present Android/KMP `ModelCouncilRuntimeSetting`, `ModelCouncilManager`, `ModelCouncilTools`, `ModelCouncilRolePresets`, `AgentPromptConfigRepository`, and the ChatService tool-injection gate as real capability evidence while making the missing iOS bridge explicit. The fake council chat transcript, fake live member states, mode menu, composer, local toggle/menu/stepper settings, fake configured seats, "Done" save, and destructive remove action were removed or downgraded to draft-only UI.
- SubAgent pages honesty: `SubAgentsView` and `SubAgentRoleView` now present Android/KMP `SubAgentRuntimeSetting`, `SubAgentDefinitions`, `SubAgentTools`, `SubAgentManager`, `SubAgentOverride`, and `AgentPromptConfigRepository` as real capability evidence while making the missing iOS bridge explicit. Local-only enable/mode/dynamic toggles, limit menus, fake per-role model assignments, fake custom roles, prompt editor, restore/default/delete actions, and any implication of `subagent_*` execution were removed or downgraded to not-wired/draft-only UI.
- Sync/Backup page honesty: `SyncBackupView` now presents common/Android `SyncSettings`, `BackupVM`, `SyncArchiveManager`, `GoogleDriveSyncRepository`, and `LocalBackupRepository` as real capability evidence while making the missing iOS bridge explicit. Fake Google account status, fake last-success timestamps, local `@AppStorage` auto-sync toggle, fake passphrase-set state, and upload/download/export/import action rows were removed or downgraded to disabled/not-wired status.

### 仍是草稿/占位

- Provider add/model edit/custom headers/body pages are draft-only. The Provider list can display KMP default templates, but iOS still does not read or persist the full mutable `Settings.providers` / `ProviderSetting` list, provider-specific API keys, provider models, custom headers/body, balances, or auth modes.
- Chat model picker is now a scalar `SettingsStore.modelId` editor, not a full provider/model registry. It still cannot switch `SettingsStore.baseUrl`, API key, auth mode, custom headers/body, or provider-specific model metadata.
- Chat context usage is still not a real token/window estimator. The context popover now avoids precise fake token/cache/speed numbers and only shows ViewModel/KMP-verifiable state: message count, current model id, KMP reasoning ability marker, and pending selected-file preview.
- TTS settings are still not wired to an iOS `TTSProviderSetting` store or real `TTSManager` execution path. The page has been downgraded to real default status plus draft-only cloud fields.
- Skills page is not wired to the real Android/KMP `SkillManager` / `SkillsVM` scanning/import/editing path. It has been downgraded to an explicit not-wired status with draft-only add/import/rescan flows.
- MCP server page is not wired to `Settings.mcpServers`, `McpServerConfig`, `McpManager`, or `McpImportParser`. The previous hardcoded connected-server list and isolated `@AppStorage` toggles have been removed; import/add remain draft-only, and import uses only rough text preview rather than real parser-backed validation.
- Account profile is still local preview only, with no inspected persistent account/profile store.
- Conversation storage cleanup/delete is still draft-only: the page only explains the missing iOS storage bridge and does not execute cache cleanup, age-based cleanup, or delete-all.
- Board, MiniApp, and WebMount child pages need separate verification before any values or actions can be treated as real. Settings home no longer shows precise fake Memory/Council/SubAgent status or capability-complete Sync/Board/MiniApp/WebMount copy; the Memory, Model Council, SubAgent, and Sync/Backup child pages have now been downgraded to draft/not-wired status.

### 不应接线的孤儿入口 / 不应误导的入口

- Provider "Add", Model "Done", Model custom Headers/Body "Done": safe as drafts only until a real iOS provider settings store exists. They must not claim to save.
- Provider preset templates: safe to show because Android/KMP ships `DEFAULT_PROVIDERS`; preset providers are real default templates, not fake data. They must not be labeled as configured accounts, prefill API keys, fetch models, refresh balance, or imply multiple ProviderSettings are persisted on iOS.
- Search service templates/add/edit: safe to show because Android/KMP ships `SearchServiceOptions` and real search services, but iOS must not label templates as enabled services, prefill API keys, persist `searchServices`, edit `searchEnabledServiceIds`, or run search/scrape/network tests until a real bridge exists.
- Chat empty-state sample messages/tool timeline/reasoning: should not be shown as default history. Real tool calls should appear only from `UIMessagePart.Tool` emitted through the message model.
- TTS cloud engine save/delete/preview: must remain disabled/draft or be wired to verified secure storage and real TTS providers. Do not run network preview as validation.
- MCP import/add "Done": replaced with `关闭`; must not show import success unless parsed and persisted through a real settings path.
- Skills import/rescan: must not claim success until local scan/import implementation is available on iOS; current copy explicitly says no download, no file picker, no filesystem mutation.
- Account exact usage stats and heatmap: downgraded in Slice 7; do not reintroduce precise values until a real iOS stats bridge exists.
- Conversation storage cleanup/delete: downgraded in Slice 8; do not add destructive cleanup/delete behavior until a real iOS storage transaction and backup/attachment policy exist.
- Memory add/edit/delete and agents.md injection: downgraded in Slice 12; do not save local-only memory drafts, persist agents.md, or inject agents.md into Chat until a real iOS `Settings.agentRuntime` / memory repository / chat request bridge exists.
- Model Council chat/settings/seat editor: downgraded in Slice 13; do not show fake live council transcripts, fake active members, local-only enabled toggles, local-only seat settings, model_council tool execution, report writing, or destructive seat removal until a real iOS `Settings.agentRuntime.modelCouncil` / `ModelCouncilManager` / tool executor bridge exists.
- SubAgent settings/role pages: downgraded in Slice 14; do not show local-only enable/mode/limit controls, fake model assignments, fake custom roles, prompt saves, reset/delete actions, or subagent_* tool execution until a real iOS `Settings.agentRuntime.subAgent` / `SubAgentManager` / tool executor bridge exists.
- Sync/Backup actions: downgraded in Slice 15; do not show connected Google accounts, last-success timestamps, auto-sync toggles, passphrase-set state, upload/download/export/import actions, or restore confirmations until a real iOS `SyncSettings` / archive manager / OAuth / file picker / restore transaction bridge exists.

### 高风险区域

- Secret storage split: the single OpenAI API key is in Keychain, but general provider/TTS API keys are not yet modeled in iOS. Avoid expanding secret handling without a clear schema.
- Provider/model identity: current iOS chat creates a fresh `ProviderSetting.OpenAI` and fresh `Model` from three scalar settings. It cannot represent multiple providers, custom headers/body, OAuth/coding-plan auth modes, provider-specific models, Gemini's `ProviderSetting.Google`, or xAI's default `useResponseApi=true` template state.
- Permission approval scope: `Route.toolPermissions` routes to the approval-policy page and its "权限与能力" row routes to `ToolPermissionsView`; `SheetDestination.toolPermissions` still opens the full capability page directly. The approval page intentionally exposes only the implemented `file_read_selected` tool policy; photos/location/camera/notifications remain system-permission status/request entries until real Agent executors exist.
- Hardcoded precise numbers/status: Settings home has been reduced to real current scalar/default/local state or explicit not-wired copy for Provider, TTS, Skill/MCP, Account, ConversationStorage, Appearance, Display, Execution, Runtime, Search, Memory, Sync/Backup, Board, Council, SubAgent, MiniApp, and WebMount. Search services, Memory, Model Council, SubAgent, and Sync/Backup have now been downgraded as child pages too; destination pages such as Board, MiniApp, and WebMount still require separate audits because several remain prototype-only.
- External effects: Provider connection test in legacy `SettingsView` calls `listModels`. Do not use it as routine validation unless the user explicitly supplies credentials/approves network use.

## 2. UI Entry Map

| 屏幕 | UI 入口 | 当前文件 | 真实能力是否存在 | 应接到哪里 | 当前处理 | 优先级 |
|---|---|---|---|---|---|---|
| Settings home | 外观 | `iosApp/iosApp/PlaceholderViews.swift` -> `.appearance` | Partial: local `@AppStorage` preference exists | `app.amber.ios.appearance.mode` | value reads the same local setting as the Appearance page | P2 done/home |
| Settings home | 显示与字体 | `PlaceholderViews.swift` -> `.displayFont` | Partial: local `@AppStorage` preferences exist | `app.amber.ios.display.fontScale`, `app.amber.ios.display.chatFont` | subtitle reads stored font scale and chat font instead of hardcoded copy | P1 done/home |
| Settings home | 核心记忆 | `PlaceholderViews.swift` -> `.memory` | Android/KMP memory exists; iOS bridge missing | real memory store/page | home subtitle downgraded to `本机草稿 · 记忆库未接线`; child pages now also show explicit not-wired/draft state | P1 done/home; P1 bridge |
| Settings home | 技能 | `PlaceholderViews.swift` -> `.skills` | Yes on Android: `SkillManager`, `SkillsVM` | iOS local skill scanning/import layer or shared bridge | subtitle downgraded to `Skill/MCP 配置桥尚未接线`; no installed/enabled count | P0 done/P1 bridge |
| Settings home | 执行与任务 | `PlaceholderViews.swift` -> `.execution` | Partial: local execution `@AppStorage` preferences and Live Activity controller exist | `app.amber.ios.execution.toolLoopLimit`, `app.amber.ios.execution.liveActivity`; runtime consumption still needs audit | home subtitle reads stored local settings and labels them as local settings | P1 done/home; P1 page |
| Settings home | 工具权限 | `PlaceholderViews.swift` -> `.toolPermissions` | Yes | `PermissionsApprovalView(permissionStore:)` -> `.capabilities` -> `ToolPermissionsView(...)` | approval page edits the real `file_read_selected` policy; capability page is reachable for system permissions | P0 done |
| Settings home | 运行环境 | `PlaceholderViews.swift` -> `.sandbox` | Yes | `SettingsStore` + `IOSTerminalRuntime` + SSH Keychain | home subtitle reads selected runtime and SSH profile count from `SettingsStore` | P1 done/home |
| Settings home | 服务商 | `PlaceholderViews.swift` -> `.providers` | Yes in KMP: `DEFAULT_PROVIDERS`, `ProviderSetting`; partial iOS scalar config | `SettingsStore.baseUrl/apiKey` now; provider settings bridge/store later | value downgraded to current scalar `OpenAI-compatible`, not a fake count | P0 done/P1 bridge |
| Settings home | 默认模型 | `PlaceholderViews.swift` -> `.modelDefaults` | Partial | `SettingsStore.modelId`; later real model registry | chat model value is real scalar; options hardcoded | P1 |
| Settings home | 搜索服务 | `PlaceholderViews.swift` -> `.searchServices` | Android search providers exist; iOS store not inspected | search settings store | home value downgraded to `未接线`; child page now also removed fake enabled/configured status | P1 done/home; P1 bridge |
| Settings home | 语音 TTS | `PlaceholderViews.swift` -> `.ttsSettings` | Yes in KMP: `TTSProviderSetting`, `DEFAULT_TTS_PROVIDERS` | TTS settings store + secure key handling | home value corrected to real default `系统 TTS`; settings page is draft-only beyond default status | P0 done/P1 store |
| Settings home | 同步与备份 | `PlaceholderViews.swift` -> `.syncBackup` | common/Android sync exists; iOS bridge missing | iOS `SyncSettings` + archive manager + Google Drive OAuth + local backup repository | home subtitle downgraded to `iOS 同步桥尚未接线`; child page now also shows explicit not-wired/disabled state | P1 done/page; P1 bridge |
| Settings home | 对话存储 | `PlaceholderViews.swift` -> `.conversationStorage` | Android `FilesManager.countChatFiles()` exists for uploaded chat files; iOS bridge not exposed | real conversation/storage stats + safe cleanup | value downgraded to `未接线` | P1 done/P1 bridge |
| Settings home | 今日看板 | `PlaceholderViews.swift` -> `.board` | Android feature exists; iOS bridge not inspected | board runtime/store | home subtitle downgraded to `数据源桥尚未接线`; child page still needs separate audit | P1 done/home; P2 page |
| Settings home | 模型议会 | `PlaceholderViews.swift` -> `.council` | Android/KMP council exists; iOS bridge missing | real council settings/runtime | home subtitle downgraded to `iOS 运行桥尚未接线`; child pages now also show explicit not-wired/draft state | P1 done/home; P1 bridge |
| Settings home | SubAgent | `PlaceholderViews.swift` -> `.subagents` | Android/KMP subagent exists; iOS bridge missing | real subagent settings/runtime | home subtitle downgraded to `iOS SubAgent 配置桥尚未接线`; child pages now also show explicit not-wired/draft state | P1 done/home; P1 bridge |
| Settings home | 小应用 | `PlaceholderViews.swift` -> `.miniApps` | Android miniapp exists; iOS bridge not inspected | miniapp store/runtime | home subtitle downgraded to `iOS 小应用运行桥尚未接线`; child page still needs separate audit | P1 done/home; P2 page |
| Settings home | WebMount | `PlaceholderViews.swift` -> `.webMount` | Android WebMount exists; iOS bridge not inspected | real webmount profile store | home subtitle downgraded to `iOS WebMount 桥尚未接线`; child page still needs separate audit | P1 done/home; P2 page |
| Providers | list rows | `iosApp/iosApp/ProvidersView.swift` | Yes in KMP; partial iOS scalar config | current `SettingsStore` config plus `DEFAULT_PROVIDERS`; mutable list bridge later | shows real current config and no-key preset templates; no fake enabled/disabled accounts | P0 done/P1 bridge |
| Provider detail | config tab | `ProviderDetailView.swift` | Yes in KMP; partial iOS scalar config | current `SettingsStore` config plus selected preset template metadata | current config edits real Base URL/API Key; presets show no-key metadata and only safe apply actions | P0 done/P1 bridge |
| Provider detail | API Key row | `ProviderDetailView.swift` | secure storage exists only for single OpenAI key | provider-specific secure key storage | current config edits real Keychain value; preset templates explicitly show `未预置` | P0 done/P1 key schema |
| Search services | Agent search/built-in sources/service types/options | `SearchServicesView.swift` | Yes in Android/KMP: `SearchServiceOptions`, `SearchPrefs`, `SettingSearchPage`, `SearchTools`, `SearchOrchestrator` | iOS `Settings`/`SearchPrefs` bridge plus search/scrape executor | removed local toggles, fake enabled counts, fake configured providers, and local result-size menu; page is evidence + not-wired status only | P1 done/page; P1 bridge |
| Search provider draft | add/edit service | `SearchProviderView.swift` | Yes in Android/KMP service editor and `SearchServiceOptions` fields | real `searchServices` persistence + provider-specific secure credentials + search executor | draft-only service type preview; API Key defaults empty; no save, no Keychain, no delete, no network test | P1 done/page; P1 bridge |
| Memory overview | evidence/config/records | `MemoryOverviewView.swift` | Yes in Android/KMP: `MemoryRepository`, `MemoryRecallStore`, `memory_tool`, `Settings.agentRuntime` | iOS memory repository/settings/tool bridge + chat injection path | removed fake records and local `@AppStorage` toggles; shows evidence plus explicit `未接线` rows | P1 done/page; P1 bridge |
| Memory edit | add/edit/delete memory | `MemoryEditView.swift` | Yes in Android/KMP repository methods and `memory_tool` | real memory repository transaction and settings/tool bridge | draft-only editor; no save button, no destructive delete, no repository write | P1 done/page; P1 bridge |
| agents.md | app soul markdown | `AgentsMarkdownView.swift` | Yes in Android/KMP: `Settings.agentRuntime.agentSoulMarkdown` + `GenerationPrompts` | iOS `Settings.agentRuntime` bridge + `ChatViewModel` prompt construction | draft-only editor; no UserDefaults/SettingsStore write and no chat request injection | P1 done/page; P1 bridge |
| Model Council overview | evidence/runtime status | `CouncilView.swift` | Yes in Android/KMP: `ModelCouncilRuntimeSetting`, `ModelCouncilManager`, `ModelCouncilTools`, ChatService tool gate | iOS `Settings.agentRuntime.modelCouncil` + `ModelCouncilManager`/tool executor + ChatViewModel bridge | fake live transcript/composer/member status removed; page shows evidence plus iOS not-wired status | P1 done/page; P1 bridge |
| Model Council settings | runtime fields/role presets/limits | `CouncilSettingsView.swift` | Yes in Android/KMP settings page and runtime models | real `Settings.agentRuntime.modelCouncil` persistence + prompt file sync | local toggles/menus/stepper/fake configured seats removed; page shows field mapping and draft seat entry | P1 done/page; P1 bridge |
| Model Council seat editor | add/edit/remove seat draft | `SeatEditorView.swift` | Yes in Android/KMP: `defaultSeats`, `ModelCouncilSeat`, `AgentPromptConfigRepository` | real settings update + prompt file write | draft-only editor; no save button, no destructive remove, no settings/prompt write | P1 done/page; P1 bridge |
| SubAgent overview | evidence/runtime status/roles | `SubAgentsView.swift` | Yes in Android/KMP: `SubAgentRuntimeSetting`, `SubAgentDefinitions`, `SubAgentTools`, `SubAgentManager`, ChatService tool gate | iOS `Settings.agentRuntime.subAgent` + `SubAgentManager`/tool executor + ChatViewModel bridge | local toggles/menus/fake custom roles removed; page shows evidence, field map, built-in role evidence, and iOS not-wired status | P1 done/page; P1 bridge |
| SubAgent role detail | role override fields/tool boundary | `SubAgentRoleView.swift` | Yes in Android/KMP: built-in definitions, `SubAgentOverride`, role prompts, tool allowlists, prompt markdown sync | real overrides/customDefinitions persistence + prompt file sync | model/reasoning/prompt editor/reset/delete removed; page shows field mapping and draft/not-wired status | P1 done/page; P1 bridge |
| Sync/Backup | Google/local backup evidence/actions | `SyncBackupView.swift` | Yes in common/Android: `SyncSettings`, `BackupVM`, `SyncArchiveManager`, `GoogleDriveSyncRepository`, `LocalBackupRepository` | iOS sync settings bridge + archive manager + Google OAuth/AppData client + file import/export + restore transaction | fake connected account/status/passphrase/auto-sync/action rows removed; page shows evidence, archive scope, and disabled/not-wired status | P1 done/page; P1 bridge |
| Model defaults | chat model | `ModelDefaultsView.swift` | Partial | `SettingsStore.modelId` | reads/writes scalar setting | P1 |
| Model defaults | image/aux/thinking/context/group defaults | `ModelDefaultsView.swift` | Android/KMP settings have richer defaults: `imageGenerationModelId`, `titleModelId`, `suggestionModelId`, `ocrModelId`, `compressModelId`, prompts, `modelGroupSessionDefaults`, reasoning/session defaults | real iOS settings bridge + execution paths | local-only menus removed; rows now display `未接线` and do not save draft selections | P1 done/home; P1 bridge |
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
| Conversation storage | usage/cleanup/delete | `ConversationStorageView.swift` | Android has `FilesManager.countChatFiles()` and `ConversationRepository` delete paths; iOS only exposes unrelated `AgentRuntimeDatabase` | measured storage + safe transactions | exact fake values removed; page shows not-wired status and info-only no-op alerts | P1 done/P1 bridge |

## 3. Implementation Queue

### P0 - 已有能力但 UI 未接，影响核心使用

1. Done in Slice 5: Replace Provider list/home fake count/examples with current `SettingsStore` config plus no-key KMP default templates. Full mutable provider store remains P1.
2. Done in Slice 4: Replace Skills hardcoded list/count with clearly unavailable iOS bridge state until a real local scan/import layer exists.
3. Done in Slice 4: Replace MCP hardcoded connected servers and isolated toggles with explicit not-wired state until a real `mcpServers` bridge exists.

### P1 - 已有能力但只是硬编码状态

1. Done in Slice 9 for home rows: Settings home subtitles/trailing values now read real local settings where available or show explicit not-wired copy for Search, Memory, Sync/Backup, Board, Council, SubAgent, MiniApp, and WebMount. Search, Memory, Model Council, SubAgent, and Sync/Backup destination pages have also been handled in Slice 11, Slice 12, Slice 13, Slice 14, and Slice 15; remaining destination pages still need separate audits/wiring.
2. Chat and ModelDefaults need a real provider/model registry bridge before exposing multiple provider choices; current iOS generation only has a single OpenAI-compatible scalar config.
3. Done in Slice 10: ModelDefaults auxiliary model/thinking/context/group controls are marked unavailable instead of persisting local-only draft state. Real Android/KMP model-default bridge remains P1.
4. Done in Slice 6: Chat thinking level now hydrates `Model.abilities` from KMP `ModelRegistry` and writes `TextGenerationParams.reasoningLevel` only for reasoning-capable models; context popover no longer shows exact fake token/cache/speed numbers.
5. Account exact statistics replaced with unavailable copy in Slice 7; ConversationStorage exact file/count/MB values replaced with unavailable copy in Slice 8.
6. Done in Slice 9 for home row: Runtime home subtitle reflects selected runtime/default SSH profile count from `SettingsStore`. Runtime detail page was already wired in earlier slices.
7. Add policy editing for additional capabilities only after a matching iOS Agent executor exists; system-only permissions should stay in the capability status/request page.
8. Wire TTS to a real iOS settings bridge before enabling cloud provider save/delete/preview or system speech preview controls.
9. Wire full iOS Provider registry to `Settings.providers`, including provider-specific Keychain schema, auth mode, Response API, model list, custom headers/body, and balance settings before exposing multi-provider save/fetch behavior.
10. Done in Slice 11: Search services and add/edit provider pages no longer expose local-only toggles, fake configured provider rows, masked API keys, result-size menus, delete, or save actions. Real iOS search settings/executor bridge remains P1.
11. Done in Slice 12: Memory overview/edit/agents.md pages no longer expose local-only memory toggles, fake memory records, fake agents.md persistence, destructive delete, or save actions. Real iOS memory repository/settings/tool/chat bridge remains P1.
12. Done in Slice 13: Model Council overview/settings/seat editor no longer expose a fake live council transcript, active member states, local-only settings toggles/menus, fake configured seats, "Done" save, destructive remove, or model_council tool execution. Real iOS model-council settings/runtime/tool bridge remains P1.
13. Done in Slice 14: SubAgent overview/role pages no longer expose local-only enable/mode/limit controls, fake role model assignments, fake custom roles, prompt editor, reset/delete actions, or subagent_* execution. Real iOS subagent settings/runtime/tool bridge remains P1.
14. Done in Slice 15: Sync/Backup page no longer exposes fake Google account status, fake backup timestamps, local-only auto-sync toggle, fake passphrase-set state, upload/download/export/import no-op actions, or restore promises. Real iOS sync settings/archive/OAuth/file/restore bridge remains P1.

### P2 - 可改善但不阻塞

1. Account display name persistence if product wants local-only profile.
2. Appearance/display preference persistence.
3. Board, MiniApp, WebMount separate audits and staged wiring.
4. Clean up/deprecate legacy `SettingsView` if no longer reachable, after confirming no route depends on it.

### Blocked - 仓库缺少可验证 iOS 能力，不接

1. Provider-specific API key schema beyond the single `SettingsStore.apiKey` Keychain account: needs a designed storage model before saving arbitrary provider credentials.
2. Cloud TTS preview/network validation: requires user credentials and external network side effects; do not use for verification.
3. Real login/subscription/account: out of scope; no inspected implementation.
4. Destructive conversation cleanup/delete: requires a safe iOS storage transaction and backup decision path.
5. MCP live connection testing: external network side effect; do not perform without explicit approval.
6. Provider model fetching/balance refresh/network connection tests: external side effects; do not perform without explicit credentials and approval.
7. Real Chat context-window/token accounting: no inspected iOS usage estimator is wired to the composer context panel yet.
8. Real iOS memory persistence/recall/agents.md injection: Android/KMP has the implementation, but iOS lacks `Settings.agentRuntime`, memory repository/DAO bridge, `memory_tool` executor bridge, and `ChatViewModel` prompt injection path.
9. Real iOS Model Council execution and settings persistence: Android/KMP has the implementation, but iOS lacks `Settings.agentRuntime.modelCouncil`, `ModelCouncilManager`, `model_council_*` tool execution, transcript/live-flow state, `AgentTaskStore` bridge, and prompt file sync.
10. Real iOS SubAgent execution and settings persistence: Android/KMP has the implementation, but iOS lacks `Settings.agentRuntime.subAgent`, `SubAgentManager`, `subagent_*` tool execution, live text/parts state, `AgentTaskStore` bridge, validated custom role persistence, and prompt file sync.
11. Real iOS Sync/Backup execution and settings persistence: common/Android has the implementation, but iOS lacks `Settings.syncSettings`, `SyncArchiveManager`, `GoogleDriveSyncRepository`, `LocalBackupRepository`, archive format compatibility validation, OAuth/AppData client, local file import/export, secret redaction/restore policy, and safe restore transaction/rollback.

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
- Remaining risk: no iOS Stats bridge or persistent profile/account store is wired. ConversationStorage exact values were handled separately in Slice 8.

### Slice 8 - Conversation storage not-wired downgrade

- Scope: Settings home `对话存储` value and `ConversationStorageView` no longer display prototype exact file counts or MB values. The usage bar is an explicit unavailable state, usage categories read as not wired, and cache cleanup / age-based cleanup / delete-all rows show explanatory no-op alerts instead of destructive confirmations.
- Evidence:
  - Android Settings home uses `FilesManager.countChatFiles()` for uploaded chat files (`app/src/main/java/app/amber/feature/ui/pages/setting/SettingPage.kt`) and `FilesManager.countChatFiles()` counts `FileFolders.UPLOAD`; Android history deletion uses `ConversationRepository` through `HistoryVM`.
  - iOS `ChatViewModel` creates `AgentRuntimeDatabase` through `IosDatabaseFactory`, but that DB stores agent runs/events/spans and is not a conversation/files storage source. No inspected iOS `ConversationRepository` or `FilesManager` equivalent exposes measured conversation storage or safe delete-all transactions.
- Verification:
  - `mcp__xcodebuildmcp.session_show_defaults` confirmed project `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/AmberAgent.xcodeproj`, scheme `iosApp`, simulator `iPhone 17`.
  - `mcp__xcodebuildmcp.build_run_sim` succeeded for `iosApp` on iPhone 17 / iOS 26.5 after the ConversationStorage edits and again after the reviewer-driven single-button alert fix.
  - UI snapshot: Settings -> 数据 showed `对话存储` value `未接线`, not `128 个 · 24 MB`.
  - UI snapshot: `ConversationStorageView` showed `用量统计未接线`, not-wired usage rows, cleanup rows marked `尚未接线`, and delete note stating no local data will be deleted.
  - UI snapshot: tapping `删除全部对话尚未接线` opened a single-button informational alert with `知道了`, not a destructive confirmation.
- Screenshot paths:
  - Settings data row: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_bcae9dd3-e6ed-4f56-968e-ea835b8f2afc.jpg`
  - Conversation storage page: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_951d9d26-1e36-43e2-af71-83afc051039c.jpg`
  - Delete-all informational alert: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_e4abd876-d8c4-4836-822b-4f3d6a66dae6.jpg`
- Subagent review: completed by `Feynman`; no P0/P1 findings. P2 fixes applied: audit/Open Design status no longer says review pending, and info-only alerts now use a single `知道了` dismiss button rather than a confirmation-like cancel/primary pair.
- Remaining risk: no iOS conversation/files storage bridge or safe cleanup/delete transaction is wired. Real implementation must define how conversation rows, message nodes, managed files, generated images, favorites, backups, and undo windows are handled on iOS before enabling cleanup/delete.

### Slice 9 - Settings home status honesty

- Scope: Settings home rows no longer mix real routes with fake precise status for Appearance, Display, Memory, Execution, Runtime, Search, Sync/Backup, Board, Council, SubAgent, MiniApp, and WebMount. Appearance, Display, and Execution rows read the same local `@AppStorage` keys as their destination pages; Runtime reads `SettingsStore.terminalDefaultRuntime` and SSH profile count; draft/unbridged feature rows are downgraded to explicit iOS bridge-not-wired copy instead of counts, cloud-backup promises, source totals, executable-tool promises, or role examples.
- Evidence:
  - `AppearanceSettingsView` persists `app.amber.ios.appearance.mode`.
  - `DisplayFontSettingsView` persists `app.amber.ios.display.fontScale` and `app.amber.ios.display.chatFont`.
  - `ExecutionSettingsView` persists `app.amber.ios.execution.toolLoopLimit` and `app.amber.ios.execution.liveActivity`; this is treated as local settings state, not proof that every execution runtime consumes it.
  - `RuntimeEnvironmentView` and `SettingsStore` expose `terminalDefaultRuntime`, `sshProfiles`, SSH profile persistence, Keychain password storage, and runtime smoke tests.
  - `SearchServicesView`, `MemoryOverviewView`, `CouncilView`, `CouncilSettingsView`, `SubAgentsView`, `SyncBackupView`, Board, MiniApp, and WebMount routes still contained local/hardcoded draft state or lacked an inspected iOS bridge at the time of Slice 9, so Settings home marked their status as unavailable instead of precise or capability-complete.
- Verification:
  - `mcp__xcodebuildmcp.session_show_defaults` confirmed project `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/AmberAgent.xcodeproj`, scheme `iosApp`, simulator `iPhone 17`.
  - `mcp__xcodebuildmcp.build_run_sim` succeeded after the Settings home edits and again after the Sync/Board/MiniApp/WebMount copy correction.
  - UI snapshot: Settings home top showed `外观` value `浅色`, `显示与字体` as `字号 标准 · 默认字体`, `核心记忆` as `本机草稿 · 记忆库未接线`, `执行与任务` as `本机设置 25 步 · 灵动岛 开`, `运行环境` as `Remote SSH · 未配置 Profile`, and `搜索服务` as `未接线`.
  - UI snapshot after scrolling showed `同步与备份` as `iOS 同步桥尚未接线`, `对话存储` as `未接线`, `今日看板` as `数据源桥尚未接线`, `模型议会` as `iOS 运行桥尚未接线`, `SubAgent` as `iOS SubAgent 配置桥尚未接线`, `小应用` as `iOS 小应用运行桥尚未接线`, and `WebMount` as `iOS WebMount 桥尚未接线`.
- Screenshot paths:
  - Settings home top: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_dd0ab3d8-099f-4c37-b51c-65d24de8bf19.jpg`
  - Settings home lower rows: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_b0e832ec-e6df-407f-8ddf-6d9748c0e662.jpg`
- Subagent review: completed by `Einstein`; no P0/P1 findings. P2 audit wording fix applied: Provider/TTS home values are described as current scalar/default status rather than implying every row has an `@AppStorage` / `SettingsStore` storage path.
- Remaining risk: Appearance/Display/Execution are real local preferences, but this slice does not prove each preference is globally consumed by all UI/runtime paths. Sync/Backup, Board, SubAgent, MiniApp, and WebMount destination pages still need separate passes to remove or wire their internal prototype state. Search services was handled separately in Slice 11, Memory was handled separately in Slice 12, and Model Council was handled separately in Slice 13.

### Slice 10 - Model defaults auxiliary controls downgrade

- Scope: `ModelDefaultsView` no longer stores image generation, title, suggestion, OCR, compression, reasoning budget, or context count selections in local `@State`. The page keeps the real chat model picker wired to `SettingsStore.modelId`, and marks the remaining Android/KMP model-default features as not wired until iOS has a real settings bridge.
- Evidence:
  - iOS `SettingsStore` currently persists only `baseUrl`, Keychain-backed `apiKey`, scalar `modelId`, and runtime/SSH settings. It has no fields for image generation, task-model defaults, prompt defaults, reasoning/session defaults, context policy, or model group defaults.
  - Android `SettingModelPage` writes real `Settings` fields such as `chatModelId`, `imageGenerationModelId`, `titleModelId`, `suggestionModelId`, `ocrModelId`, `compressModelId`, prompt fields, and `modelGroupSessionDefaults`; related Android runtime code consumes them through `resolveTaskChatModel`, image generation, OCR, compression, and session default helpers.
  - iOS `ChatViewModel.makeTextGenerationParams()` consumes only `SettingsStore.modelId` for the current chat request. Default reasoning in iOS is a current composer/ViewModel state, not a persisted default-model setting.
- Verification:
  - `mcp__xcodebuildmcp.session_show_defaults` confirmed project `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/AmberAgent.xcodeproj`, scheme `iosApp`, simulator `iPhone 17`.
  - `mcp__xcodebuildmcp.build_run_sim` succeeded for `iosApp` on iPhone 17 / iOS 26.5 after the `ModelDefaultsView` edits and again after the subagent P2 whitespace fallback fix.
  - UI snapshot: Settings -> 默认模型 showed the intro explaining only the default chat model is wired; `聊天模型` still shows `gpt-4o`, while `生图模型`, title, suggestion, OCR, compression, reasoning budget, and context rows all show `未接线`.
  - UI snapshot after scrolling showed `模型组默认规则` as `未接线`.
  - UI snapshot after tapping `模型组默认规则` showed a single-button informational alert explaining that Android/KMP has `modelGroupSessionDefaults` but iOS does not expose the bridge and will not save/apply the rule.
- Screenshot paths:
  - Model defaults top/auxiliary rows: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_d7888e92-abf6-4728-a3f2-564ccbca6a06.jpg`
  - Model defaults advanced rows: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_b3a915e8-5209-44cf-a1fa-04e17f168355.jpg`
  - Model group defaults informational alert: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_ae244dbb-1075-4085-9dc0-cb6c0c3386fc.jpg`
- Subagent review: completed by `Huygens`; no P0/P1 findings. P2 fixes applied: `ModelDefaultsView.currentChatModel` now trims whitespace before falling back to `gpt-4o`, matching `ChatViewModel.currentModelId`, and review-pending documentation was updated.
- Remaining risk: Android/KMP has real richer model-default settings, but iOS still lacks the bridge. This slice only removes local-only fake state; it does not implement title/suggestion/OCR/compression/image-generation/session-default execution on iOS.

### Slice 11 - Search services not-wired downgrade

- Scope: `SearchServicesView` and `SearchProviderView` no longer present prototype search configuration as if it were enabled or persisted. The Search services page keeps Android/KMP search service types visible as real repository-backed capability evidence, but removes local-only source toggles, fake enabled counts, fake configured Serper/Tavily rows, result-size menus, and per-row navigation that could imply direct edit of a saved service. The add/edit page is a draft preview only: service type menu, empty API Key field for key-based types, no save, no Keychain write, no delete, no enabled toggle, and no network test.
- Evidence:
  - Android/KMP `SearchServiceOptions` defines real service types and defaults (`BingLocalOptions`, `JinaOptions`, `TavilyOptions`, `ExaOptions`, `SearXNGOptions`, `BraveOptions`, `SerperOptions`, `SerpApiOptions`, `PerplexityOptions`, `FirecrawlOptions`, `GrokOptions`, and others).
  - Android `SettingSearchPage` reads/writes `settings.enableWebSearch`, built-in source flags, `settings.searchServices`, `searchEnabledServiceIds`, `searchServiceSelected`, and `SearchCommonOptions`.
  - `SearchPrefs` persists those Android/KMP settings through DataStore, and `SearchTools` / `SearchOrchestrator` consume them for `search_web` / `scrape_web`.
  - iOS `SettingsStore`, `ChatViewModel`, and `IOSLocalToolExecutor` currently expose no `searchServices`, `SearchCommonOptions`, `searchEnabledServiceIds`, or `search_web` / `scrape_web` executor bridge.
- Verification:
  - `mcp__xcodebuildmcp.session_show_defaults` confirmed project `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/AmberAgent.xcodeproj`, scheme `iosApp`, simulator `iPhone 17`.
  - `mcp__xcodebuildmcp.build_run_sim` succeeded after the Search services edits, and again after the local P2 fix that made the repository service-type rows static instead of all navigating to the default Bing draft.
  - UI snapshot: Settings home still shows `搜索服务` value `未接线`.
  - UI snapshot: Settings -> 搜索服务 shows Android/KMP evidence copy, `Agent 网络搜索` value `未接线`, every built-in source as `未接线`, and no source toggles or enabled count.
  - UI snapshot: repository service types show `草稿` and no chevron/tap target; only the top-right `+` opens the generic draft page.
  - UI snapshot: add draft defaults to `Bing HTML 兜底`, credential preview `无需 Key`, save mode `本页草稿`, and only a `关闭` button.
  - UI snapshot: selecting `Tavily` shows an empty API Key field, `API Key 待填写`, `服务启用 未保存`, and copy stating iOS will not write API Key, save the service, or test the connection.
- Screenshot paths:
  - Search services not-wired page after static-row fix: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_21ef896d-c522-45d9-91ac-684b722d01fc.jpg`
  - Search provider draft default Bing: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_06de30d8-a972-426d-8cd0-bbbcf1565a53.jpg`
  - Search provider draft Tavily empty-key state: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_1f52d002-33ec-489c-9187-07565058feba.jpg`
- Local review: no P0/P1 found. P2 fixed before final verification: repository service rows originally opened a generic draft page regardless of selected row, which could imply direct editing of that specific service type; they are now static evidence rows, and the generic draft entry is only the top-right `+`.
- Subagent review: completed by `Carver`; no P0/P1 findings. Two earlier subagent attempts (`Jason` and `Hegel`) failed before review because their refresh token could not be refreshed, but the follow-up `Carver` review succeeded. Carver specifically checked that there are no `SettingsStore`, Keychain, UserDefaults, network, or search/scrape executor calls in the Search draft UI, and that the audit matches the UI state.
- Open Design record: CD-78 content was prepared, but direct `REDESIGN_DELTAS.md` write outside the repo was rejected by the current approval/usage system. No workaround was attempted.
- Remaining risk: iOS still lacks a real search settings bridge, provider-specific search credential storage, and search/scrape executor integration. Search service add/edit remains a draft preview until those exist.

### Slice 12 - Memory pages not-wired downgrade

- Scope: `MemoryOverviewView`, `MemoryEditView`, and `AgentsMarkdownView` no longer present local prototype state as real memory or prompt behavior. The overview page keeps Android/KMP memory capabilities visible as real evidence, but removes fake memory records, precise fake record counts, and local `@AppStorage` toggles. The edit page is a draft preview only: no save button, no destructive delete, no repository write, and copy states the missing bridge. The agents.md page is also draft-only and no longer persists local UserDefaults text or claims System Prompt injection.
- Evidence:
  - Android/KMP `MemoryRepository` exposes real DAO-backed memory/candidate/event operations, including global/short-term/long-term reads plus add/update/delete.
  - Android `SettingAgentMemoryPage` / `SettingAgentMemoryVM` read and write real `Settings.agentRuntime` fields and memory records.
  - `MemoryRecallStore` consumes `Settings.agentRuntime.enableCoreMemory`, `enableShortTermMemory`, `enableLongTermMemory`, and recall settings to build memory prompts.
  - `ChatService` exposes a real `memory_tool` path for list/create/update/delete of memory records when the corresponding runtime settings are enabled.
  - `GenerationPrompts.buildAgentSoulPrompt()` consumes `Settings.agentRuntime.agentSoulMarkdown` for the Android/KMP prompt path.
  - iOS `SettingsStore`, `ChatViewModel`, and `IOSLocalToolExecutor` currently expose no `Settings.agentRuntime`, memory repository/DAO bridge, `memory_tool` executor bridge, or agents.md prompt injection path.
- Verification:
  - `git diff --check -- iosApp/iosApp/MemoryOverviewView.swift iosApp/iosApp/MemoryEditView.swift iosApp/iosApp/AgentsMarkdownView.swift` passed.
  - Targeted `rg` check found no remaining `@AppStorage`, fake sample-memory copy, "Done" save, destructive delete title, or Keychain/API persistence in the Memory pages; the only matching persistence term was negative copy stating no UserDefaults/SettingsStore write.
  - `mcp__xcodebuildmcp.session_show_defaults` confirmed project `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/AmberAgent.xcodeproj`, scheme `iosApp`, simulator `iPhone 17`.
  - `mcp__xcodebuildmcp.build_run_sim` succeeded after the Memory page edits.
  - UI snapshot: Settings home shows `核心记忆` value `本机草稿 · 记忆库未接线`.
  - UI snapshot: Settings -> 核心记忆 shows Android/KMP evidence rows for `MemoryRepository`, `记忆召回`, and `memory_tool`, plus `iOS 记忆桥` value `未接线`.
  - UI snapshot: add memory opens `记忆草稿`, shows `保存 未接线`, `真实后端 MemoryRepository 未桥接`, and no save button.
  - UI snapshot: agents.md opens `agents.md 草稿` and states the draft will not write UserDefaults/SettingsStore or affect the next chat request.
- Screenshot paths:
  - Memory overview not-wired evidence: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_ff9a5269-6f42-4283-8a81-272b8c36e95a.jpg`
  - Memory edit draft/no-save page: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_1ccdfd52-412d-48bd-9514-c6bddda284c6.jpg`
  - agents.md draft/no-injection page: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_9b258d9f-82b9-48ae-bb3b-7a28489f2d76.jpg`
- Subagent review: completed by `Meitner`; no P0/P1 findings. P2 fix applied before commit: Slice 12 and Commit Log review status no longer say pending.
- Open Design record: CD-79 should record the Memory not-wired downgrade, but direct `REDESIGN_DELTAS.md` write outside the repo remains blocked by the current approval/usage system after the Slice 11 rejection. No workaround was attempted.
- Remaining risk: iOS still lacks the real memory settings/repository/tool/chat bridge. Real implementation must define how iOS persists `Settings.agentRuntime`, reads/writes memory records, wires `memory_tool` into the local tool executor, and injects agents.md into `ChatViewModel` prompts before enabling saves or chat effects.

### Slice 13 - Model Council pages not-wired downgrade

- Scope: `CouncilView`, `CouncilSettingsView`, and `SeatEditorView` no longer present local prototype state as a running Model Council. The overview page keeps Android/KMP council runtime/tool capabilities visible as real evidence, but removes the fake council transcript, fake active member states, mode menu, live streaming caret, composer, stop button, and synthetic detail sheet. The settings page removes local-only enabled toggles, synthesis-model menus, hardcoded configured seat rows, steppers, and local limit selectors. The seat editor is a draft preview only: no "Done" save, no destructive remove, no settings write, and no prompt-file write.
- Evidence:
  - `ModelCouncilRuntimeSetting` defines the real Android/KMP settings fields: `enabled`, `defaultSeats`, `synthesisModelId`, `maxSeats`, `defaultRounds`, timeouts, output budget, and `showSeatOutputs`.
  - `ModelCouncilManager` starts/reads/waits/cancels council runs, records transcripts, publishes per-seat live text flows, and registers AgentTask state.
  - `ModelCouncilTools` exposes real `model_council_status`, `model_council_start`, `model_council_read`, `model_council_wait`, `model_council_cancel`, and report-writing tools.
  - Android `SettingExperimentalModelCouncilPage` reads/writes `settings.agentRuntime.modelCouncil`, model selectors, seat lists, role presets, runner types, limits, and prompt-file sync through `AgentPromptConfigRepository`.
  - Android/KMP `ChatService` injects `ModelCouncilTools` only when `settings.agentRuntime.modelCouncil.enabled` is true.
  - iOS `SettingsStore`, `ChatViewModel`, and `IOSLocalToolExecutor` currently expose no `Settings.agentRuntime.modelCouncil`, `ModelCouncilManager`, `model_council_*` executor bridge, transcript/live-flow state, `AgentTaskStore` bridge, or prompt-file sync.
- Verification:
  - `git diff --check -- iosApp/iosApp/CouncilView.swift iosApp/iosApp/CouncilSettingsView.swift iosApp/iosApp/SeatEditorView.swift` passed.
  - Targeted `rg` check found no remaining fake council chat/composer/live/member-state/save/delete/toggle components in the three Council pages; remaining matches are Android/KMP field names or negative copy.
  - `mcp__xcodebuildmcp.build_run_sim` succeeded after the Model Council page edits.
  - UI snapshot: Home sidebar -> 模型议会 shows Android/KMP evidence rows for `ModelCouncilRuntimeSetting`, `ModelCouncilManager`, and `model_council_* 工具`, plus `iOS 运行桥` value `未接线`; no fake transcript, composer, live member status, or stop button appears.
  - UI snapshot: Model Council -> 成员设置草稿 shows Settings field mapping and `未接线`, no local enabled toggle, synthesis model menu, fake configured seats, or steppers.
  - UI snapshot: add seat opens `席位草稿`, states it will not save, shows `保存 未接线` and `defaultSeats 未桥接`, and the remove action opens a single-button informational alert rather than deleting.
- Screenshot paths:
  - Model Council evidence page: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_62edda9d-5a6a-48d3-836b-1414da687ac9.jpg`
  - Model Council settings field map: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_c0fa1f02-579e-4059-a6e6-155b2eaec79a.jpg`
  - Seat editor draft top: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_ffd1306b-ab89-4d5e-b21d-5ce1da825e04.jpg`
  - Seat editor draft handling rows: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_7092cbcd-2304-4e8d-8a37-6e02258f4379.jpg`
  - Seat remove informational alert: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_236f1eff-e487-4fe8-a74e-88be75d491e5.jpg`
- Subagent review: completed by `Ramanujan`; no P0/P1 findings. P2 fix applied before commit: Slice 13 and Commit Log review status no longer say pending.
- Open Design record: CD-80 should record the Model Council not-wired downgrade, but direct `REDESIGN_DELTAS.md` write outside the repo remains blocked by the current approval/usage system after the Slice 11 rejection. No workaround was attempted.
- Remaining risk: iOS still lacks the real Model Council settings/runtime/tool bridge. Real implementation must define how iOS persists `Settings.agentRuntime.modelCouncil`, resolves provider/CLI seats, starts and observes model_council runs, exposes transcript/live-flow state, integrates AgentTask status, and syncs prompt files before enabling execution or saves.

### Slice 14 - SubAgent pages not-wired downgrade

- Scope: `SubAgentsView` and `SubAgentRoleView` no longer present local prototype state as a configurable/runnable SubAgent system. The overview page keeps Android/KMP SubAgent runtime, built-in roles, tools, manager, and settings fields visible as real evidence, but removes local-only enabled/mode/dynamic toggles, limit menus, fake per-role model values, and fake custom roles. The role detail page removes local model/reasoning menus, prompt editor, reset/default action, and destructive delete action; it now shows field mapping, tool allowlist evidence, and iOS not-wired status only.
- Evidence:
  - `SubAgentRuntimeSetting` defines the real Android/KMP settings fields: `enabled`, `mode`, `allowDynamicSubAgents`, `maxConcurrentRuns`, `timeoutMs`, `maxTurns`, `outputBudgetChars`, `overrides`, and `customDefinitions`.
  - `SubAgentDefinitions` defines the real built-in roles: `explorer`, `historian`, `oracle`, `designer`, `writer`, and `fixer`, including tool allowlists and routing hints.
  - `SubAgentTools` exposes real `subagent_list`, `subagent_start`, `subagent_read`, `subagent_wait`, and `subagent_cancel` tools.
  - `SubAgentManager` starts/reads/waits/cancels runs, enforces concurrency through `admissionLock`, records transcripts, publishes live text/parts flows, and registers AgentTask state.
  - Android `SettingExperimentalSubAgentPage` reads/writes `settings.agentRuntime.subAgent`, role overrides, customDefinitions, mode/limits, and prompt-file sync through `AgentPromptConfigRepository`.
  - Android/KMP `ChatService` injects `SubAgentTools` only when `conversationId != null` and `settings.agentRuntime.subAgent.enabled` is true.
  - iOS `SettingsStore`, `ChatViewModel`, and `IOSLocalToolExecutor` currently expose no `Settings.agentRuntime.subAgent`, `SubAgentManager`, `subagent_*` executor bridge, live text/parts flow state, `AgentTaskStore` bridge, validated custom role persistence, or prompt-file sync.
- Verification:
  - `git diff --check -- iosApp/iosApp/CouncilView.swift iosApp/iosApp/SubAgentsView.swift iosApp/iosApp/SubAgentRoleView.swift docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md` passed.
  - Targeted `rg` check found no remaining positive local-only SubAgent controls in the two SubAgent pages; remaining matches for enable/reset/delete/custom-role names are negative explanatory copy.
  - First `build_run_sim` failed because `CouncilView.swift` was missing from the working tree; it was restored from HEAD, and `CouncilView` received a compatibility initializer for the existing `AppShell` call without reading settings or changing behavior.
  - A transient `spawn xcrun EAGAIN` retry produced no build diagnostics; the subsequent `mcp__xcodebuildmcp.build_run_sim` succeeded with no warnings/errors.
  - UI snapshot: Settings -> SubAgent shows Android/KMP evidence rows for `SubAgentRuntimeSetting`, `SubAgentDefinitions`, and `SubAgentTools`, plus `iOS 运行桥` value `未接线`; no fake enable toggle, mode segmented control, limit menus, or fake custom role list appears.
  - UI snapshot after scrolling shows settings field mapping and built-in role evidence; `Explorer` is labeled `KMP 存在`, not configured with a fake model.
  - UI snapshot: `Explorer` role detail shows `modelId`, `reasoningLevel / temperature`, `systemPrompt`, and `turns / timeout / outputBudget` as `未接线`, with no prompt editor, save, reset, or delete control.
- Screenshot paths:
  - SubAgent evidence page: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_feef0bd8-d506-405d-ae99-c8effb9ad15f.jpg`
  - SubAgent field map and built-in role evidence: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_f65924f9-ed25-4d07-add6-0efa59ccce4d.jpg`
  - SubAgent role detail field map: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_c17b54fe-3532-4502-9bf7-e169830909d7.jpg`
- Subagent review: completed by `Descartes`; no P0/P1 findings. P2 fixes applied before commit: Writer/Fixer tool allowlist summaries now match KMP definitions, built-in role badges say `KMP 存在`, and Slice 14 / Commit Log review status no longer say pending.
- Open Design record: CD-81 should record the SubAgent not-wired downgrade, but direct `REDESIGN_DELTAS.md` write outside the repo remains blocked by the current approval/usage system after the Slice 11 rejection. No workaround was attempted.
- Remaining risk: iOS still lacks the real SubAgent settings/runtime/tool bridge. Real implementation must define how iOS persists `Settings.agentRuntime.subAgent`, validates and saves custom roles, resolves provider/model overrides, starts and observes subagent runs, exposes live text/parts state, integrates AgentTask status, and syncs prompt files before enabling execution or saves.

### Slice 15 - Sync/Backup page not-wired downgrade

- Scope: `SyncBackupView` no longer presents local prototype state as a connected backup system. The page keeps common/Android sync models, archive manager, Google Drive repository, local backup repository, and archive scope visible as real evidence, but removes fake connected Google account copy, fake last-success timestamps, local-only `@AppStorage` auto-sync toggle, fake passphrase-set state, and no-op upload/download/export/import action rows.
- Evidence:
  - commonMain `SyncSettings` defines real persisted fields: `googleEnabled`, account identity fields, `mode`, `autoSyncEnabled`, `deviceId`, last upload/download/local-export timestamps, remote revision, last error, and last backup version/device summary.
  - Android `BackupVM` reads/writes `settings.syncSettings`, restores Google sessions, handles Google authorization resolution, uploads, downloads, cloud snapshot selection, cloud conflicts, local export/import inspection, restore confirmation, progress state, and success/error messages.
  - Android `SyncArchiveManager` builds encrypted `.amberbackup` archives from Settings, secrets, Room tables, and file roots; it verifies SHA-256, decrypts payloads, validates safe relative paths, and restores data according to `SyncRestoreRequest`.
  - `SyncCrypto` uses PBKDF2WithHmacSHA256 and AES/GCM/NoPadding with 210,000 PBKDF2 iterations, 256-bit keys, and SHA-256 payload verification.
  - `SyncRedactor` masks sensitive settings fields and authorization-like headers for STANDARD mode; FULL mode can include `SyncSecretSnapshot` values such as WebMount OAuth and OpenAI Codex OAuth raw JSON.
  - Android `GoogleDriveSyncRepository` uses Google Identity authorization and Drive AppData snapshots; `LocalBackupRepository` exports/imports through Android document URIs.
  - iOS `SettingsStore`, `ChatViewModel`, and local tool/runtime files currently expose no `Settings.syncSettings`, `SyncArchiveManager`, Google Drive OAuth/AppData client, local `.amberbackup` import/export repository, restore preview, or safe restore transaction.
- Verification:
  - `git diff --check -- iosApp/iosApp/SyncBackupView.swift docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md` passed.
  - Targeted `rg` check found no remaining positive local-only Sync/Backup controls in `SyncBackupView`; remaining matches for `@AppStorage`, upload/download/export/import, and connected/success states are negative explanatory or audit-history copy.
  - `mcp__xcodebuildmcp.build_run_sim` succeeded with no warnings/errors.
  - UI snapshot: Settings -> Sync/Backup shows Settings home value `iOS 同步桥尚未接线`; the page shows `SyncSettings`, `BackupVM`, `SyncArchiveManager`, `GoogleDriveSyncRepository`, and `LocalBackupRepository` as evidence plus `iOS 同步桥` value `未接线`.
  - UI snapshot after scrolling shows `上传 / 下载` and `本地导出 / 导入` as `禁用`, `自动同步` and `加密口令` as `未接线`, and no switch/action button for fake backup flows.
  - UI snapshot at the bottom shows archive scope and the preconditions required before enabling backup actions.
- Screenshot paths:
  - Sync/Backup evidence page: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_9da9a954-39c4-4fa1-a72f-3b3e5044fb67.jpg`
  - Sync/Backup disabled action rows: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_49933aeb-207b-4bac-a1a6-de7f8f2274f8.jpg`
  - Sync/Backup archive scope and preconditions: `/var/folders/m6/vf3y7_wx4yj8jp71j8h9dhyh0000gn/T/screenshot_optimized_2c0fe095-fa82-487b-8bec-efdcc5b8e27a.jpg`
- Subagent review: completed by `Sartre`; no P0/P1 findings. P2 audit status fixes applied before commit: Slice 15 and Commit Log no longer say review pending.
- Open Design record: CD-82 should record the Sync/Backup not-wired downgrade, but direct `REDESIGN_DELTAS.md` write outside the repo remains blocked by the current approval/usage system after the Slice 11 rejection. No workaround was attempted.
- Remaining risk: iOS still lacks the real sync/backup settings/archive/OAuth/file/restore bridge. Real implementation must define how iOS persists `SyncSettings`, stores or avoids backup passphrases, redacts and restores secrets, serializes compatible archive payloads, uploads/downloads Drive AppData snapshots, imports/exports local backup files, previews archives, handles conflicts, and performs restore with rollback before enabling backup actions.

## 5. Commit Log

| commit hash | 接线范围 | 验证命令 | 截图路径 | 未覆盖风险 |
|---|---|---|---|---|
| `1cb4699d8` | Permission approval policy wiring; Chat default model scalar wiring | `test_sim` blocked by existing test target Info.plist issue; `build_run_sim` succeeded before/after reviewer fixes | see Slice 1 and Slice 2 screenshot paths | no provider/model registry bridge yet; enum retains `allowOncePerRun` for old-value compatibility, but store normalizes it to `askEveryTime` |
| `bf9eea664` | TTS default/status downgrade | `build_run_sim` succeeded | see Slice 3 screenshot paths | no iOS TTS settings bridge or synthesis executor yet |
| `a5dd008f6` | Skill/MCP not-wired state downgrade | `build_run_sim` succeeded before and after subagent P2 copy fixes | see Slice 4 screenshot paths | no iOS Skill/MCP settings bridge yet |
| `c77cc4b20` | Provider current config, no-key default templates, and model draft downgrade | `build_run_sim` succeeded before/after explicit route-kind and subagent P2 fixes | see Slice 5 screenshot paths | no full iOS provider registry, provider-specific Keychain schema, Response API persistence, or model metadata bridge yet |
| `c3d22f732` | Chat reasoning parameter wiring with KMP model ability gate, context-stat downgrade, and no-fake empty state | `build_run_sim` succeeded before and after subagent P1/P2 fixes | see Slice 6 screenshot paths | no persisted reasoning default; no real context token/window estimator |
| `34519052d` | Account stats exact-value downgrade and profile preview honesty | `build_run_sim` succeeded before and after reviewer P2 heatmap fixes | see Slice 7 screenshot paths | no iOS Stats bridge; no persistent account/profile store; ConversationStorage still pending |
| `d348bb113` | Conversation storage exact-value downgrade and no-op cleanup/delete honesty | `build_run_sim` succeeded before and after reviewer P2 alert fix | see Slice 8 screenshot paths | no iOS conversation/files storage bridge or safe cleanup/delete transaction |
| `8b21e96a3` | Settings home status honesty for Appearance, Display, Memory, Execution, Runtime, Search, Sync/Backup, Board, Council, SubAgent, MiniApp, and WebMount | `build_run_sim` succeeded after edits and after Sync/Board/MiniApp/WebMount correction; subagent review completed with no P0/P1 | see Slice 9 screenshot paths | child pages still need separate audits; Appearance/Display/Execution consumption outside their local settings pages remains partially unverified |
| `0877ec9c9` | Model defaults auxiliary controls downgraded from local-only selections to not-wired status; chat model remains wired to `SettingsStore.modelId` | `build_run_sim` succeeded before and after subagent P2 fix; subagent review completed with no P0/P1 | see Slice 10 screenshot paths | no iOS bridge for Android/KMP image/task model defaults, prompts, reasoning/session defaults, context policy, or model group defaults |
| `b1a245da4` | Search services and provider draft pages downgraded from local-only enabled/configured state to Android/KMP evidence plus iOS not-wired status | `build_run_sim` succeeded after edits and after local P2 static-row fix; subagent review completed by `Carver` with no P0/P1 | see Slice 11 screenshot paths | no iOS search settings bridge, search credential storage, or search/scrape executor |
| `abfaf73f5` | Memory overview/edit/agents.md pages downgraded from local-only toggles, fake records, fake agents.md persistence, and destructive delete to Android/KMP evidence plus iOS not-wired draft state | `diff --check` passed; `build_run_sim` succeeded; subagent review completed by `Meitner` with no P0/P1 | see Slice 12 screenshot paths | no iOS memory settings/repository/tool/chat bridge or agents.md prompt injection |
| `9334dfea6` | Model Council overview/settings/seat editor downgraded from fake live transcript, active member states, local settings toggles, fake configured seats, save/remove actions, and tool execution to Android/KMP evidence plus iOS not-wired draft state | `diff --check` passed; `build_run_sim` succeeded; subagent review completed by `Ramanujan` with no P0/P1 | see Slice 13 screenshot paths | no iOS Model Council settings/runtime/tool bridge, transcript/live-flow state, AgentTask bridge, or prompt-file sync |
| `892c6fc5b` | SubAgent overview/role pages downgraded from local-only enable/mode/limit controls, fake role models, fake custom roles, prompt editor, reset/delete actions, and tool execution to Android/KMP evidence plus iOS not-wired draft state | `diff --check` passed; `build_run_sim` succeeded; subagent review completed by `Descartes` with no P0/P1 | see Slice 14 screenshot paths | no iOS SubAgent settings/runtime/tool bridge, live text/parts state, AgentTask bridge, validated custom-role persistence, or prompt-file sync |
| `Pending` | Sync/Backup page downgraded from fake Google account/status/passphrase/auto-sync and no-op backup actions to common/Android evidence plus iOS not-wired disabled state | `diff --check` passed; `build_run_sim` succeeded; subagent review completed by `Sartre` with no P0/P1 | see Slice 15 screenshot paths | no iOS SyncSettings/archive/OAuth/AppData/file import/export/restore transaction bridge |

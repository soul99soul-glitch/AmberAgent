# iOS Capability Wiring Audit - 2026-06-15

This audit tracks which AmberAgent iOS SwiftUI surfaces are wired to real, repository-backed capabilities and which surfaces are still prototype/draft UI. It is intentionally conservative: if a capability is not backed by an inspected store, coordinator, runtime, provider, database, or KMP/Android implementation path, it is treated as not wired.

## 1. Capability Wiring Summary

### 已接真实能力

- App root wiring: `AmberAgentApp` owns one `SettingsStore`; `AppShell` injects `SettingsStore`, `IOSPermissionStore`, `DocumentAccessStore`, `IOSSystemPermissionCoordinator`, and `IOSLocalToolExecutor` into routed pages.
- Core OpenAI-compatible chat: `ChatViewModel` reads `SettingsStore.baseUrl`, `SettingsStore.apiKey`, and `SettingsStore.modelId`, builds `ProviderSetting.OpenAI`, calls `OpenAIKmpProvider.streamTextCancellable`, streams into `MessageStreamAccumulator`, supports cancellation, and records agent runs through `AgentRuntimeDatabase`.
- Chat selected-file context: `ToolPermissionsView` uses SwiftUI `fileImporter`; `DocumentAccessStore` creates an in-memory one-file grant; `IOSLocalToolExecutor` routes `file_read_selected` through `IOSToolRuntime`; `ChatViewModel.attachSelectedFilePreviewToNextMessage()` appends a real bounded file preview to the next prompt.
- iOS permissions/capabilities: `ToolPermissionsView` reads `IOSCapabilityRegistry`, `IOSSystemPermissionCoordinator.cachedStatus`, and requests/refreshes permissions through the coordinator instead of fabricating status. Unsupported/non-requestable capabilities are represented by capability metadata.
- Permission policy/status substrate: `IOSPermissionStore` persists policies in `UserDefaults`; `IOSLocalToolExecutor.permissionsStatus()` exposes a real read-only status snapshot.
- Permission approval UI: `PermissionsApprovalView` now receives the app-level `IOSPermissionStore` from `AppShell` and edits the `ios.files.selected_read` policy consumed by the real `file_read_selected` tool path. Other system permissions stay on the full capability page until a matching iOS executor exists. `IOSPermissionStore` also normalizes legacy/programmatic `allowOncePerRun` values to `askEveryTime` until a true run-scoped approval store exists.
- Remote SSH runtime storage and gate: `SettingsStore` persists SSH profiles in `UserDefaults`, stores SSH passwords in Keychain through `IOSSSHSecretStore`, records trusted host fingerprints, and `RuntimeEnvironmentView` runs smoke tests through `IOSTerminalRuntime`.
- Runtime safety boundary: stable iOS runtime selection is constrained by `IOSTerminalBuildPolicy`; Remote SSH host mismatch is a hard failure before password save/command execution.
- Default model value: `SettingsHomeView`, `ModelDefaultsView`, and `ChatView` read/write `SettingsStore.modelId` for the single current OpenAI-compatible chat configuration. The composer picker no longer presents fake provider switching; it only writes the model id that `ChatViewModel.makeTextGenerationParams()` actually sends.

### 仍是草稿/占位

- Provider list/detail/add/model edit pages are mostly hardcoded Open Design examples. They do not read or persist the real `Settings.providers` / `ProviderSetting` list and do not write provider/model configuration.
- Chat model picker is now a scalar `SettingsStore.modelId` editor, not a full provider/model registry. It still cannot switch `SettingsStore.baseUrl`, API key, auth mode, custom headers/body, or provider-specific model metadata.
- Chat thinking/context controls are presentation-only. `selectedThinkingLevel` does not feed `TextGenerationParams.reasoningLevel`; context ring/token stats are hardcoded.
- TTS settings are not wired to `DEFAULT_TTS_PROVIDERS`, `TTSProviderSetting`, Keychain, or real `TTSManager`. The main page currently implies Keychain/preview behavior, but the state is local SwiftUI state.
- Skills page is hardcoded. It does not use the real Android/KMP `SkillManager` / `SkillsVM` scanning/import/editing path.
- MCP server page is hardcoded and uses isolated `@AppStorage` toggles. It does not read/write `Settings.mcpServers`, `McpServerConfig`, `McpManager`, or `McpImportParser`.
- Account statistics and conversation storage show precise prototype numbers. No inspected iOS source currently proves these values come from DB, token usage, cache size, or conversation DAO.
- Search services, Sync/Backup, Memory, Board, Council, SubAgent, MiniApp, and WebMount pages need separate verification before any values or actions can be treated as real.

### 不应接线的孤儿入口 / 不应误导的入口

- Provider "Add", Provider "Save", Model "Done", Model custom Headers/Body "Done": safe as drafts only until a real iOS provider settings store exists. They must not claim to save.
- TTS cloud engine save/delete/preview: must remain disabled/draft or be wired to verified secure storage and real TTS providers. Do not run network preview as validation.
- MCP import/add "Done": must not show import success unless parsed and persisted through a real settings path.
- Skills import/rescan: must not claim success until local scan/import implementation is available on iOS.
- Account exact usage stats and heatmap: must either be wired to real stats or explicitly presented as unavailable/placeholder.
- Conversation storage cleanup/delete: current alerts correctly do not delete data; exact storage values should not remain as if measured.

### 高风险区域

- Secret storage split: the single OpenAI API key is in Keychain, but general provider/TTS API keys are not yet modeled in iOS. Avoid expanding secret handling without a clear schema.
- Provider/model identity: current iOS chat creates a fresh `ProviderSetting.OpenAI` and fresh `Model` from three scalar settings. It cannot represent multiple providers, custom headers/body, OAuth/coding-plan auth modes, or provider-specific models.
- Permission approval scope: `Route.toolPermissions` routes to the approval-policy page and its "权限与能力" row routes to `ToolPermissionsView`; `SheetDestination.toolPermissions` still opens the full capability page directly. The approval page intentionally exposes only the implemented `file_read_selected` tool policy; photos/location/camera/notifications remain system-permission status/request entries until real Agent executors exist.
- Hardcoded precise numbers: Settings home, Account stats, ConversationStorage, Skills, MCP, Providers, and TTS display exact counts/status that look real but are not backed by inspected iOS data.
- External effects: Provider connection test in legacy `SettingsView` calls `listModels`. Do not use it as routine validation unless the user explicitly supplies credentials/approves network use.

## 2. UI Entry Map

| 屏幕 | UI 入口 | 当前文件 | 真实能力是否存在 | 应接到哪里 | 当前处理 | 优先级 |
|---|---|---|---|---|---|---|
| Settings home | 外观 | `iosApp/iosApp/PlaceholderViews.swift` -> `.appearance` | Partial | local appearance settings if present | route exists; state mostly local in child page | P2 |
| Settings home | 显示与字体 | `PlaceholderViews.swift` -> `.displayFont` | Partial/unknown | display preferences store equivalent | hardcoded subtitle | P1 |
| Settings home | 核心记忆 | `PlaceholderViews.swift` -> `.memory` | Android/KMP memory exists; iOS wiring unknown | real memory store/page | route exists; needs separate audit | P1 |
| Settings home | 技能 | `PlaceholderViews.swift` -> `.skills` | Yes on Android: `SkillManager`, `SkillsVM` | iOS local skill scanning/import layer or shared bridge | hardcoded installed list/count | P0 |
| Settings home | 执行与任务 | `PlaceholderViews.swift` -> `.execution` | Partial: Live Activity controller exists | `AgentLiveActivityController`, execution settings store | route exists; needs audit | P1 |
| Settings home | 工具权限 | `PlaceholderViews.swift` -> `.toolPermissions` | Yes | `PermissionsApprovalView(permissionStore:)` -> `.capabilities` -> `ToolPermissionsView(...)` | approval page edits the real `file_read_selected` policy; capability page is reachable for system permissions | P0 done |
| Settings home | 运行环境 | `PlaceholderViews.swift` -> `.sandbox` | Yes | `SettingsStore` + `IOSTerminalRuntime` + SSH Keychain | mostly wired; subtitle is generic/hardcoded | P1 |
| Settings home | 服务商 | `PlaceholderViews.swift` -> `.providers` | Yes in KMP: `DEFAULT_PROVIDERS`, `ProviderSetting` | provider settings bridge/store | hardcoded list/value "10 个" | P0 |
| Settings home | 默认模型 | `PlaceholderViews.swift` -> `.modelDefaults` | Partial | `SettingsStore.modelId`; later real model registry | chat model value is real scalar; options hardcoded | P1 |
| Settings home | 搜索服务 | `PlaceholderViews.swift` -> `.searchServices` | Android search providers exist | search settings store | value "4 个源" hardcoded | P1 |
| Settings home | 语音 TTS | `PlaceholderViews.swift` -> `.ttsSettings` | Yes in KMP: `TTSProviderSetting`, `DEFAULT_TTS_PROVIDERS` | TTS settings store + secure key handling | hardcoded value "MiniMax", but real default is System TTS | P0 |
| Settings home | 同步与备份 | `PlaceholderViews.swift` -> `.syncBackup` | Android sync exists | iOS sync/backup implementation | prototype page; not audited | Blocked/P2 |
| Settings home | 对话存储 | `PlaceholderViews.swift` -> `.conversationStorage` | Conversation DB exists in KMP/Android; iOS stats unclear | real conversation/storage stats + safe cleanup | hardcoded exact usage/count | P1 |
| Settings home | 今日看板 | `PlaceholderViews.swift` -> `.board` | Android feature exists | board runtime/store | prototype; not audited | P2 |
| Settings home | 模型议会 | `PlaceholderViews.swift` -> `.council` | Android/KMP council exists | real council settings/runtime | prototype; not audited | P1 |
| Settings home | SubAgent | `PlaceholderViews.swift` -> `.subagents` | Android/KMP subagent exists | real subagent settings/runtime | prototype; not audited | P1 |
| Settings home | 小应用 | `PlaceholderViews.swift` -> `.miniApps` | Android miniapp exists | miniapp store/runtime | prototype; not audited | P2 |
| Settings home | WebMount | `PlaceholderViews.swift` -> `.webMount` | Android WebMount exists | real webmount profile store | prototype; not audited | P2 |
| Providers | list rows | `iosApp/iosApp/ProvidersView.swift` | Yes in KMP | `Settings.providers` / `DEFAULT_PROVIDERS` bridge | hardcoded examples; enabled/disabled not real | P0 |
| Provider detail | config tab | `ProviderDetailView.swift` | Yes in KMP | selected `ProviderSetting` fields | hardcoded values; save alert says not wired | P0 |
| Provider detail | API Key row | `ProviderDetailView.swift` | secure storage exists only for single OpenAI key | provider-specific secure key storage | masked fake key string | P0 |
| Model defaults | chat model | `ModelDefaultsView.swift` | Partial | `SettingsStore.modelId` | reads/writes scalar setting | P1 |
| Model defaults | image/aux/thinking/context | `ModelDefaultsView.swift` | KMP settings have richer model defaults | real settings bridge | local-only state | P1 |
| Chat | send/cancel streaming | `ChatView.swift`, `ChatViewModel.swift` | Yes | `OpenAIKmpProvider`, `MessageStreamAccumulator` | wired for single OpenAI-compatible provider | P0 done/needs hardening |
| Chat | attach selected file | `ChatView.swift`, `ChatViewModel.swift` | Yes | `IOSLocalToolExecutor` + `DocumentAccessStore` | wired; requires prior picker grant | P0 done |
| Chat | model picker | `ChatView.swift` | Partial | `SettingsStore.modelId`; later selected real provider/model from settings | writes the scalar model id used by generation; multi-provider switching intentionally not exposed until provider identity exists | P0 done/P1 provider registry |
| Chat | thinking/context controls | `ChatView.swift` | Partial KMP params exist | `TextGenerationParams.reasoningLevel`, real usage stats | local/hardcoded only | P1 |
| Permissions | selected file | `ToolPermissionsView.swift` | Yes | `DocumentAccessStore` + `fileImporter` | wired | P0 done |
| Permissions | system permissions | `ToolPermissionsView.swift` | Yes | `IOSSystemPermissionCoordinator` | wired for listed capabilities | P0 done |
| Permissions | approval/policy page | `PermissionsApprovalView.swift` and `Route.toolPermissions` | Yes for `file_read_selected` | `IOSPermissionStore` policy consumed by `IOSToolRuntime.resolve()` | only exposes implemented selected-file tool policy; store normalizes run-scoped policy to ask-every-time because no true run scope exists | P0 done |
| Runtime | runtime picker/smoke | `RuntimeEnvironmentView.swift` | Yes | `SettingsStore` + `IOSTerminalRuntime` | wired | P0 done |
| Runtime | SSH profile/password/fingerprint | `RuntimeEnvironmentView.swift`, `SettingsStore.swift` | Yes | UserDefaults + Keychain + SSH trust | wired | P0 done |
| TTS | engine list/config | `TTSSettingsView.swift` | Yes in KMP | `TTSProviderSetting`, selected provider id, secure key storage | local-only, contradictory default | P0 |
| TTS | add engine | `TTSSettingsView.swift` | Yes model types | real settings write + Keychain | explicitly draft, no save | P1 |
| Skills | installed list | `SkillsView.swift` | Yes Android `SkillManager` | iOS skill scanner/import bridge | hardcoded list/count | P0 |
| Skills | import/rescan | `SkillsView.swift` | Yes Android path | real local file scan/import | alerts only, no filesystem mutation | P1 |
| MCP | server list/toggles | `McpServersView.swift` | Yes Android/KMP `McpManager`/`McpServerConfig` | real `Settings.mcpServers` bridge | hardcoded + isolated `@AppStorage` toggles | P0 |
| MCP | import/add | `McpServersView.swift` | Yes `McpImportParser` | parse + persist `McpServerConfig` | local draft validation only | P1 |
| Account | profile name/avatar | `AccountView.swift` | No inspected persistent account store | local profile preference if added | local-only `@State` | P2 |
| Account | stats/heatmap | `AccountView.swift` | Android DAOs exist; iOS stats bridge unclear | conversation/message/token usage stats | hardcoded precise values | P1 |
| Conversation storage | usage/cleanup/delete | `ConversationStorageView.swift` | likely DB/cache sources exist; iOS service unclear | measured storage + safe transactions | hardcoded values; actions are no-op alerts | P1 |

## 3. Implementation Queue

### P0 - 已有能力但 UI 未接，影响核心使用

1. Replace Provider list/home values with real KMP default/provider metadata where safely available, or downgrade precise counts until a mutable iOS provider store exists.
2. Correct TTS default/status to the real default `SystemTTS` capability; downgrade cloud TTS save/preview claims until secure provider-specific storage exists.
3. Replace Skills hardcoded list/count with either real local scan or clearly disabled/unavailable state.
4. Replace MCP hardcoded connected servers and isolated toggles with real `mcpServers` bridge, or mark as draft without connected/tool counts.

### P1 - 已有能力但只是硬编码状态

1. Settings home subtitles/trailing values: runtime, skills, providers, search, TTS, conversation storage, SubAgent/Council counts.
2. Chat and ModelDefaults need a real provider/model registry bridge before exposing multiple provider choices; current iOS generation only has a single OpenAI-compatible scalar config.
3. ModelDefaults auxiliary model/thinking/context controls: local-only state should either persist to real settings or be marked unavailable.
4. Chat thinking level and context popover should reflect real params/usage or avoid exact token/cost/speed numbers.
5. Account and storage exact statistics should be wired to measured sources or replaced with unavailable/draft copy.
6. Runtime page subtitle/home value should reflect selected runtime/default SSH profile from `SettingsStore`.
7. Add policy editing for additional capabilities only after a matching iOS Agent executor exists; system-only permissions should stay in the capability status/request page.

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

## 5. Commit Log

No commits yet for this audit phase.

| commit hash | 接线范围 | 验证命令 | 截图路径 | 未覆盖风险 |
|---|---|---|---|---|
| pending | Permission approval policy wiring | `test_sim` blocked by existing test target Info.plist issue; `build_run_sim` succeeded before/after reviewer fixes | see Slice 1 screenshot paths | enum retains `allowOncePerRun` for old-value compatibility, but store normalizes it to `askEveryTime` |
| pending | Chat default model scalar wiring | `build_run_sim` succeeded | see Slice 2 screenshot paths | no provider/model registry bridge yet |

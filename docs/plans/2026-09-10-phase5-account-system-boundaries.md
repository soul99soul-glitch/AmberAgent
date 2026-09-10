# Phase 5（W12/W13/W16）实施边界：账号、日历、快捷入口与 TTS

基线：Android `ab984f6 + 当前 WIP`，iOS `1023e8a`。本文件是静态源码核对结果；没有运行两端 App、真实账号或测试。iOS 仅读取 `/Users/mi/Downloads/AI/AmberAgent/ios`，没有把命名或文档当作已接线行为。

## 覆盖结论

| 能力 | iOS 已核实契约 | Android 已核实契约 | Phase 5 判断 |
| --- | --- | --- | --- |
| Grok 账号 | Provider 详情页显示登录入口；`GrokWebLoginView` 完成浏览器/PKCE 回调，凭据按 provider 保存，模型可发现；聊天配置和请求链识别 Grok 登录态 | 只有 xAI OpenAI-compatible API Key 预置，没有 Grok 账号登录、OAuth token owner 或 Grok 专用请求/模型发现链 | **明确缺口，W12** |
| Gemini Antigravity | Google Provider 有 API Key/Antigravity 切换；独立 OAuth、onboarding、project/tier、刷新和 live model catalog；发送前以登录态 gate | 已有 Gemini **Code Assist OAuth**，但 `GoogleAuthMode` 没有 `ANTIGRAVITY_OAUTH`，UI、provider、preflight 只消费 Code Assist | **明确缺口；保留 Code Assist，W13** |
| 日历/提醒 | EventKit 日历事件 list/create/update/delete；提醒事项 list/create/update/delete/complete；稳定系统 ID、权限和高风险审批 | `calendar_list` + `calendar_create`，仅查询/创建事件；无 update/delete、无 Reminders 工具；已有权限/审批接线 | **明确缺口，W16**；Android Reminders 不能用 CalendarContract 事件冒充 |
| 快捷入口 | App Intents 提供询问、每日简报、快捷消息、指定/最近/当前任务等；Deep Link Inbox + AppShell 负责冷启动、旧对象和提交后确认 | 静态 launcher shortcut 只有相机；相机结果经 `ACTION_SEND` 进 `RouteActivity`。Quick Messages 虽有设置和持久化，但没有 `ShortcutManager`/App Intent consumer | **明确缺口，W16** |
| 独立 TTS 试听 | `AppShell` 有设置路由；`TTSSettingsView` 提供系统引擎、语速、试听/停止；`IOSTTSPlayer` 消费 `AVSpeechSynthesizer`。云端 TTS 只是历史记录，未接入播放/聊天朗读 | 没有独立 TTS 页面或播放器；旧 TTS 数据会被迁移清理，Live `voiceInputEnabled` 仍可写入配置但没有 SpeechRecognizer/TTS 消费者 | **独立试听明确缺口，W16**；Live 语音开关另列 W18 清理 |

这张表只表示源码中的实现/接线状态。iOS 运行可用性、Grok/Antigravity 服务资格和账号额度仍须在真实账号 smoke 中验证。

## 实际入口、owner 与持久化接线

### W12：Grok 账号登录

**iOS 对端证据。** 入口在 [`ProviderDetailView.swift:91-145`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ProviderDetailView.swift:91>)，登录视图是 `GrokWebLoginView`；Grok 区块只对 xAI provider 显示，见 [`ProviderDetailView.swift:644-697`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ProviderDetailView.swift:644>)。登录成功会把 endpoint 切到 CLI proxy、采用 `/chat/completions`，合入 fallback/chat catalog 并修正当前模型；退出时恢复备份 endpoint（同文件 `:101-143`）。

`IOSGrokOAuthAuthStore` 以 `grokweb.<provider>.oauth` 和 backup 作为 provider 绑定的 Keychain side-table，client cache、logout generation、exchange/refresh、`invalid_grant` 处理在 [`IOSGrokOAuthClient.swift:75-147`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSGrokOAuthClient.swift:75>)、`:191-303`；模型请求和 response 解析在同文件 `:327-365`。模型刷新入口在 `ProviderDetailView.swift:1114-1142`，发送链由 `IOSAgentToolEngine` 的 Grok provider branch 消费。这里的账号登录不是把 xAI API key 填进通用表单。

**Android 现状。** `xAI` 只是 [`DefaultProviders.kt:217-224`](</Users/mi/Downloads/AI/AmberAgent/android/core/settings/src/main/kotlin/app/amber/core/settings/DefaultProviders.kt:217>) 的 disabled OpenAI-compatible preset；provider catalog 仍只有 OpenAI/Google/Claude 三个 gateway，见 [`ProviderCatalog.kt:7-27`](</Users/mi/Downloads/AI/AmberAgent/android/ai/src/main/java/app/amber/ai/provider/ProviderCatalog.kt:7>)。发送前配置 gate 只识别 Codex 和 Gemini Code Assist，见 [`ChatConfigurationIssue.kt:14-49`](</Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/chat/ChatConfigurationIssue.kt:14>)；未发现 Grok OAuth store、Grok login UI 或 Grok 专用请求消费者。因此“Android 支持 xAI API key”不能记作“已支持 Grok 账号”。

**最小 Android owner 与接线。**

1. 新增 provider 绑定的 Grok auth store/client（复用现有 `OAuthTokenSecureStore` 的加密边界；不要把 refresh/access token 写入普通 `Settings`）。登录页沿用现有 provider detail 的登录/取消/退出结构，实际 UI 文件是 [`SettingProviderConfigPage.kt`](</Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderConfigPage.kt>)，但只新增 Grok 分支。
2. 请求层优先复用 OpenAI-compatible body/stream 代码；若 Grok CLI proxy 的 header、model discovery 或响应协议不能安全落入 `OpenAIProvider`，新增最小 Grok adapter，并在 [`ProviderCatalog.kt`](</Users/mi/Downloads/AI/AmberAgent/android/ai/src/main/java/app/amber/ai/provider/ProviderCatalog.kt>) 只加一个显式路由。不要把 xAI API key preset 改名后伪装成订阅登录。
3. provider 配置仍由 [`ProviderPrefs.kt:23-68`](</Users/mi/Downloads/AI/AmberAgent/android/core/settings/src/main/kotlin/app/amber/core/settings/prefs/ProviderPrefs.kt:23>) / [`SettingsAggregator.kt:95-128`](</Users/mi/Downloads/AI/AmberAgent/android/core/settings/src/main/kotlin/app/amber/core/settings/prefs/SettingsAggregator.kt:95>) 读写；现有 `SecretRedactor` 继续只在 DataStore 留 mask/reference。Grok token 只由新增的 per-provider 加密 store 持有。
4. 将 Grok 登录态接入 [`ChatConfigurationHint.kt:35-59`](</Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/chat/ChatConfigurationHint.kt:35>)、`ChatConfigurationIssue.kt`、[`ProviderConfigTools.kt:105-143`](</Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/ai/tools/ProviderConfigTools.kt:105>) 和模型刷新 UI；缺 token、过期、401、invalid grant 必须显示“登录/重新登录”，不能误报 Missing API Key。

### W13：Antigravity 独立登录模式

**iOS 对端证据。** Provider 详情页用 API Key/Antigravity segmented control 并持久化 auth mode，见 [`ProviderDetailView.swift:428-482`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ProviderDetailView.swift:428>)；登录/状态/邮箱/tier 行在同文件 `:484-529`。OAuth 常量、providerId 绑定 side-table、每 provider client generation 在 [`IOSAntigravityOAuthClient.swift:20-120`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSAntigravityOAuthClient.swift:20>)；exchange、`loadCodeAssist`/`onboardUser`/LRO、refresh 和 `invalid_grant` 在 `:319-466`。发送与模型发现分别由 [`IOSGeminiProvider.swift:182-207`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSGeminiProvider.swift:182>)、`:787-839` 消费；模型发现是 `/v1internal:fetchAvailableModels`，不是普通 API-key ListModels。

**Android 现状。** Android 已有一条完整但不同的 Code Assist OAuth：`GoogleAuthMode` 只有 `API_KEY` 和 `GEMINI_CODE_ASSIST_OAUTH`，并把 OAuth base pin 到 cloudcode-pa，见 [`ProviderSetting.kt:16-56`](</Users/mi/Downloads/AI/AmberAgent/android/ai/src/main/java/app/amber/ai/provider/ProviderSetting.kt:16>)、`:244-270`；token store/PKCE/loopback/onboarding 在 [`GoogleGeminiOAuth.kt:139-205`](</Users/mi/Downloads/AI/AmberAgent/android/ai/src/main/java/app/amber/ai/provider/providers/google/GoogleGeminiOAuth.kt:139>)；Google provider 的 Code Assist session 与 SSE 在 [`GoogleProvider.kt:76-98`](</Users/mi/Downloads/AI/AmberAgent/android/ai/src/main/java/app/amber/ai/provider/providers/GoogleProvider.kt:76>)、`:240-283`；UI 只有 API Key/OAuth（Code Assist）两段，见 [`SettingProviderConfigPage.kt:443-492`](</Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderConfigPage.kt:443>)、`:1050-1160`。这证明 Code Assist 已实现，也证明 Antigravity 尚未接入。

**最小 Android owner 与接线。** W13 复用 `GoogleGeminiAuthStore` 的加密/按 providerId 形状和现有登录结果竞态保护，但必须新增独立 Antigravity token/client 常量、headers、onboarding/project/tier 和 model catalog；不能把 `GEMINI_CODE_ASSIST_OAUTH` 改名或复用其服务身份。需要改动的边界是 `ProviderSetting.kt`（新增稳定序列化值）、`GoogleGeminiOAuth.kt` 同目录新 client/store 或明确抽出的共享 PKCE 小部件、`GoogleProvider.kt` 的 Antigravity transport、provider 设置 UI、模型刷新和配置 preflight。W12/W13 共用 `ProviderSetting.kt`、`GoogleProvider.kt`、配置 gate 的修改必须串行提交，避免 schema 和请求分支互相覆盖。

两个 OAuth 模式均须满足：provider UUID 绑定 token；刷新 single-flight；logout/invalid grant 使该 provider generation 失效；切换 provider/model 后一次请求冻结 provider、model、token/project；刷新或 onboarding 失败保留原可用配置并可重试；备份/同步只导出脱敏 provider 配置，不能导出明文 token。真实账号 smoke 是发布条件。

### W16-A：日历事件与 Reminders 边界

**iOS 对端证据。** 工具目录包含事件四操作和提醒事项五操作，见 [`IOSAppleIntegrationsView.swift:307-357`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSAppleIntegrationsView.swift:307>)；`IOSEventKitAgentToolExecutor` 在 `:360-417` 统一做 JSON、取消、权限和分派。权限 owner 是 `IOSSystemPermissionCoordinator`， capability/Info.plist/gate 见 [`IOSPermissionModels.swift:648-703`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSPermissionModels.swift:648>) 和 [`IOSSystemPermissionCoordinator.swift:132-165`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSSystemPermissionCoordinator.swift:132>)、`:220-247`。事件 list 返回 `eventIdentifier` 并支持 366 天/100 条上限（`:466-500`），update/delete 精确查 ID（`:520-567`）；提醒使用 `calendarItemIdentifier` 并支持完成（`:569-693`）。EventKit 是耐久 owner，不需要 Amber 自己复制事件表。

**Android 现状。** [`CalendarAccessTools.kt:15-60`](</Users/mi/Downloads/AI/AmberAgent/android/feature/tools/access/src/main/kotlin/app/amber/feature/tools/CalendarAccessTools.kt:15>) 只注册 `calendar_list` / `calendar_create`；list 用 `CalendarContract.Instances` 返回 numeric `event_id`，create 选第一个 writable calendar 后 insert 并返回 numeric ID（`:62-137`）。`SystemAccessTools.kt:25-68` 只把这两个工厂放进工具 registry；权限 owner [`AgentPermissionBroker.kt:298-316`](</Users/mi/Downloads/AI/AmberAgent/android/feature/system/src/main/kotlin/app/amber/feature/system/AgentPermissionBroker.kt:298>) 也只映射这两个工具。`AndroidManifest.xml:22-23` 已声明 READ/WRITE_CALENDAR，创建工具已有 `needsApproval=true`、`allowsAutoApproval=false`（`CalendarAccessTools.kt:34-53`）。因此权限不是主要缺口，操作面和稳定更新/删除才是缺口。

**最小 Android owner 与接线。** 继续由 `CalendarAccessTools.kt` 扩展 `calendar_update` / `calendar_delete`，由 `SystemAccessTools.kt` 注册，`AgentPermissionBroker.kt` 增加工具名映射；复用 `SystemAccessDeps.trackSystemTool`、现有审批和 runtime permission。update/delete 必须只接受并校验上一次 list/create 返回的稳定 `event_id`（必要时同时要求 calendar/account 维度），禁止按同名查找；执行前展示准确目标和字段 diff，外部删除、无写权限、改期冲突返回可见错误并保留重试入口。CalendarContract 本身承担持久化，不新增 Room 表。

Apple Reminders 不是 Android CalendarContract event。W16 最小交付应明确为“Android 日历事件 CRUD”；若产品要求对齐提醒事项，再单列 Android reminder backend/权限/数据契约和估算，不能把 `calendar_create` 改名为 `reminder_create` 交付。

### W16-B：快捷入口

**iOS 对端证据。** [`IOSAppIntents.swift:5-35`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSAppIntents.swift:5>) 统一把 intent 转 typed deep link；文件 `:37-115` 提供询问、每日简报、新建、最近、当前任务，`:117-229` 提供可查询会话实体和已保存快捷消息；`:232-281` 从 `IOSConversationStore.appIntentSummaries`、`IOSSharedSettingsStore.snapshot.quickMessages` 读取并注册三项 App Shortcuts。`AppShell.swift:351-353` 接收 URL，`:455-590` 等待 bootstrap 后处理新建/最近/指定会话/Prompt handoff，删除对象时给用户可见错误。`IOSDeepLinkInbox` 在 [`IOSLocalNotifications.swift:364-452`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSLocalNotifications.swift:364>) 做持久 pending URL、handoff consume 和 acknowledge。

**Android 现状。** 静态资源 [`shortcuts.xml:2-14`](</Users/mi/Downloads/AI/AmberAgent/android/app/src/main/res/xml/shortcuts.xml:2>) 只有 `camera`；`ShortcutHandlerActivity.kt:14-51` 请求相机、拍照后以 `ACTION_SEND + EXTRA_STREAM` 启动 `RouteActivity`。`RouteActivity.kt:269-293` 只消费外部 share/process-text，`AndroidManifest.xml:114-162` 注册 launcher/share/process-text 和静态 shortcut。Android Quick Messages 页面/VM 已存在，`QuickMessagesVM.kt:19-53` 写 `settings.quickMessages`，其 JSON 持久化在 [`ExtensionPrefs.kt:96-129`](</Users/mi/Downloads/AI/AmberAgent/android/core/settings/src/main/kotlin/app/amber/core/settings/prefs/ExtensionPrefs.kt:96>)；但没有 dynamic shortcut publisher 或快捷方式到会话/Prompt 的 consumer。

**最小 Android owner 与接线。** 在现有 `RouteActivity`/W04 路由 owner 上增加 typed route（新建、最近会话、指定会话、当前任务、固定 Quick Message），新增一个小的 `ShortcutManager` publisher，资源只保留静态相机并与动态入口共存。Quick Message 直接复用 `SettingsAggregator` 的稳定 UUID；最近/当前任务复用已有 conversation/ContinueCandidate/任务 owner，不在 shortcut 层复制数据库。外部 prompt 只能进入现有 Chat/发送授权 gate；快捷方式连续点击要去重或按 route revision 处理。冷启动等待 bootstrap；会话/快捷消息被删除、旧 UUID、无当前任务时给明确 fallback/错误，不 silently open wrong conversation。

### W16-C：独立 TTS 试听

**iOS 对端证据。** `AppShell` 路由枚举及页面接线在 [`AppShell.swift:874`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/AppShell.swift:874>)、`:1081-1086`。[`TTSSettingsView.swift:4-53`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/TTSSettingsView.swift:4>) 持有 player 并在离页 stop；`:94-145` 提供系统引擎、0.5×–2× 语速、试听/停止；[`IOSTTSPlayer.swift:4-80`](</Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSTTSPlayer.swift:4>) 消费 `AVSpeechSynthesizer`，处理 finish/cancel。语言走 `@AppStorage`/应用语言，语速目前是页面 `@State`（`TTSSettingsView.swift:9-20`、`:185-225`），不是跨重启设置；云端 TTS 行明确标记未接入，历史自定义记录由 `IOSSharedSettingsStore.swift:1072-1137` 脱敏保存/删除。

**Android 现状。** 未发现 `TextToSpeech`、`SpeechRecognizer` 或独立 TTS 页面/播放器消费者。`LocalToolOption.kt:35-43` 将 `tts` 保留为“已退役”的反序列化 tombstone；`SettingsSecretMigrator.kt:420-463` 会清理旧 TTS settings/secrets。另一方面 [`SettingAgentExecutionPage.kt:324-340`](</Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentExecutionPage.kt:324>) 和 [`LiveCompanionVM.kt:110-120`](</Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionVM.kt:110>) 仍能写 `liveMode.voiceInputEnabled`，但没有语音输入消费链；`RECORD_AUDIO` manifest 权限不是 TTS 证据。该死开关应在 W18 移除或准确改为已实现能力，不能把它当作 TTS/语音通话。

**最小 Android owner 与接线。** W11 若先落地系统 speech，W16 只复用其最小 `speak(text, language, rate)` / `stop()` owner；若 W11 尚未有可复用实现，W16 建立局部 Android `TextToSpeech` player 和设置页，保持模块边界，不先建立通用云端音频平台。新增设置 route/入口接入现有 `RouteActivity`/settings 页面；最小状态是当前引擎可用、语速、speaking、stop，处理无 TTS engine、初始化失败、取消、重复试听和 `onDispose`/离页 stop。第一版只承诺系统 TTS 试听，不承诺普通聊天自动朗读、录音转写或云端 TTS；这与 iOS 当前实际行为一致。

## 两条不冲突的实施分工

### A：W12 → W13 账号/provider（串行）

- owner：`ai/provider/ProviderSetting.kt`、OAuth stores/clients、`GoogleProvider.kt`/Grok adapter、`ProviderCatalog.kt`、设置页和模型刷新/preflight/config tools。
- W12 先新增 Grok provider-bound auth、model discovery、send/refresh/logout；W13 再新增 Antigravity auth mode/transport/catalog，复用 W12 的过期/401/竞态测试形状。
- 共享 schema、`GoogleProvider.kt`、配置 gate 必须由同一 owner 串行改；不要让 W12/W13 并行编辑同一 provider enum 或 token store。
- A 不触碰 CalendarContract、Shortcut resource、`RouteActivity` 的系统入口和 TTS 页面。

### B：W16 日历/快捷/TTS 系统入口

- owner：`CalendarAccessTools.kt`、`SystemAccessTools.kt`、`AgentPermissionBroker.kt` 及其工具测试；`shortcuts.xml`、动态 shortcut publisher、`RouteActivity`/现有 W04 route；TTS settings route/player 及其测试。
- B 只复用 Android 既有权限、审批、SettingsAggregator Quick Message、conversation/ContinueCandidate 和 W11 speech owner；不修改 provider schema、OAuth client、ChatService provider dispatch。
- A 与 B 没有产品文件交集；若动态 shortcut 需要 ChatService 发送入口，只传递 typed route/prompt，让现有 chat auth/approval 接管，不在 B 复制发送链。

## 必须接线与验收

1. **账号**：为每个 provider UUID 登录、取消、退出、重启恢复、过期 refresh、401/invalid grant、刷新竞争、多 provider 并发发送、切模型期间请求冻结；Grok 与 Antigravity 均应在发送前显示专属登录问题，成功后能真实获取/使用模型。无真实账号时只能标“静态实现/模拟测试通过”。
2. **日历**：首次读/写授权、拒绝/撤销、事件被系统日历外部删除、update/delete 精确 ID、改期冲突、重复点击审批；结果带对象 ID 和成功/失败语义。Reminders 若未另立 Android backend，验收中明确 unsupported。
3. **快捷入口**：桌面/助手冷启动、warm start、连续点击、最近会话为空、会话/Quick Message 已删除、当前任务不存在、旧 ID、不绕过登录/发送审批；目标必须是正确会话，错误要可见且可重试。
4. **TTS**：可用系统引擎试听、切换语速、重复点变 stop、取消后状态复位、无引擎/初始化失败、离页和进程销毁释放；不得以 Live voiceInputEnabled 或 `RECORD_AUDIO` 宣称录音/转写。

## 实施前不应扩大的范围

W12/W13 不删除 Android 已有 Code Assist、Vertex/API Key 或 Codex；W16 不承诺 Apple Reminders、HealthKit、WorkoutKit、完整聊天语音、云端 TTS 或跨端设置同步。所有 iOS 对端代码状态均为静态证据，正式发布还需各平台真实设备和账号验收。

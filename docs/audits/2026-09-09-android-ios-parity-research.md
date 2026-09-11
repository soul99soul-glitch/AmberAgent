# Android 与 iOS 功能及体验深度对比

Android 已有完整的 Agent、聊天、创作、工具和持久运行基础。最新 iOS 的主要增量在于把这些能力接成更完整的用户流程：网页登录与人工接管、可追踪的子代理协作、小应用系统交互、生成结果找回，以及系统快捷入口。追齐应围绕这些实际场景组织，复用 Android 当前实现，而不是按 iOS 文件列表逐项搬运。

本报告与[详细实施计划](/Users/mi/Downloads/AI/AmberAgent/android/docs/plans/2026-09-09-android-ios-parity-plan.md)共同构成研究交付。当前完成的是源码调研和计划，尚未实施产品改动。此前仅审计 Android 的结论已按最新 iOS 证据修订；尤其撤回“Android 整体缺少订阅额度”的泛化判断。

## 1. 基线与研究边界

| 项目 | 固定基线 |
| --- | --- |
| 研究日期 | 2026-09-09 |
| Android | `/Users/mi/Downloads/AI/AmberAgent/android`，HEAD `ab984f6df1d6b5f9bae2b007ccdcf43f1d467e62`（8 月 27 日）加当前工作区 |
| Android 工作区 | 39 个已跟踪文件已有修改/删除，涉及飞书办公移除、设置、工具和子代理；保留这些 WIP，不能当作已发布代码 |
| iOS | `/Users/mi/Downloads/AI/AmberAgent/ios`，HEAD `1023e8a08f5957157226725b43dbf3e2e7078a17`（9 月 9 日），工作区干净 |
| iOS 远端 | `git ls-remote origin HEAD refs/heads/main` 返回同一 SHA；已核实本地与 [main](https://github.com/soul99soul-glitch/AmberAgent-iOS/tree/1023e8a08f5957157226725b43dbf3e2e7078a17) 一致 |
| 版本说明 | Android 构建声明 2.6.8 / 396；iOS project.yml 声明 1.0.0 / 1。均不代表商店或设备当前版本 |
| 读取授权 | 已获得两端本地及 iOS 远端只读授权；本次跨仓研究以该明确授权为准 |
| 设备证据 | Android 未连接 adb 设备；本轮未操作两端真机，未执行最新版 iOS 测试或真机视觉比较 |

证据以“入口 → 配置或策略 → 运行消费者 → 持久状态”链路为准。最新提交和文档用于寻找变化，不能单独证明功能已生效。正文中的源码确认表示存在可定位的实现差异，不等于真实服务商、设备权限和系统后台行为已经通过验收。

分类约定：**差距**为两端生产路径可确认的缺失或局部实现；**已有/不同实现**不进入缺失统计；**平台扩展**单独安排；**风险**表示 Android 调用链可疑但后果尚未复现。没有定义统一的用户场景分母，因此不编造“落后百分比”。

## 2. 核心差距台账

核心台账共 **20 个差距组**，包含完整缺失、局部接线和体验策略差异；不能理解为 20 个功能全部从零开发。优先级 P1 表示手机核心追齐批次，P2 表示后续完善/扩展；独立风险与平台扩展不混入这 20 组。

| 领域 | 台账 | 主要工作 |
| --- | --- | --- |
| 服务商与发送 | D01、D02、D11 | Grok/Antigravity、模型候选刷新、持续配置提示 |
| 工作找回与创作 | D06、D07、D08 | 首页来源、精确定位、深读来源、小说中断恢复 |
| 子代理 | D09、D14 | 默认编排可用性、同一任务多轮状态卡 |
| 聊天细节 | D10、D12、D13 | 附件状态、首页筛选/历史错误、独立 TTS 试听 |
| Agent 浏览器 | D15–D18 | 可见会话、人工接管、多窗口/回执、站点确认 |
| 小应用 | D19 | 系统 SDK、权限、native owner 与生命周期 |
| 数据与系统 | D03–D05、D20 | 恢复隔离、日历、快捷入口、managed SSH |

### D01：账号登录方式尚未覆盖 iOS 新增的 Grok 和 Antigravity

**场景**：已有订阅账号，希望不填写 API Key 就开始聊天。

- iOS 的服务商详情有 Grok 登录与 Gemini Antigravity 模式切换，并持久化对应凭据：[ProviderDetailView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ProviderDetailView.swift:428)、[Grok 入口](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ProviderDetailView.swift:646)。[IOSAgentToolEngine.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSAgentToolEngine.swift:186) 在模型执行前解析 Grok/Codex 身份；[后台生成](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSChatBackgroundGenerationCoordinator.swift:1899)也接入该解析，并非只有登录页面。
- Android 的 [ProviderSetting.kt](/Users/mi/Downloads/AI/AmberAgent/android/ai/src/main/java/app/amber/ai/provider/ProviderSetting.kt:37) 包含 Google Code Assist OAuth；OpenAI 的 auth mode 有 Codex 和多个 coding plan，未包含 Grok/Antigravity 登录闭环。Grok API 模型/搜索配置存在，不能把它们算作订阅账号登录。
- **结论：已确认局部差距，P1。** 保留 Android 的 Code Assist OAuth，单独新增 Antigravity 身份及请求适配；不得直接重命名旧枚举。iOS [supportsChat](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSGeminiProvider.swift:200)反而不支持 Android 的 Code Assist、Vertex/service-account 路径，因此不是单向全量覆盖关系。
- **验收**：登录、取消、失效、刷新、退出、切换账号和多 provider 并发；同一账号可聊天及调用工具；错误时保留原有可用配置。当前只确认代码，不保证真实账号服务可用，开发时需独立核验协议。

### D02：模型刷新没有把真实候选结果交给用户

- iOS [ProviderDetailView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ProviderDetailView.swift:1092)拉取 Codex models，随后在 [1167 行](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ProviderDetailView.swift:1167)发布 `availableModels`，成功、空列表、失败分别反馈，并允许添加选中的候选。
- Android [SettingProviderConfigPage.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderConfigPage.kt:893)取得 `fetchedModels`，实际提交旧 models 过滤后的列表，却使用 fetched 数量提示成功。
- **结论：已确认体验/状态差距，P1。** 修候选刷新，不把所有返回模型覆盖成用户已选模型；[现有模型列表](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderModelTabPage.kt:115)已经区分候选与选择。
- **验收**：模拟服务商新增一个模型，刷新后能看到并选用；旧默认模型不变；401、超时、空列表不误报；离页后不被过期响应覆盖。

### D03：备份恢复缺少 iOS 已有的在途聊天隔离与旧写入失效

- iOS [AppShell.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/AppShell.swift:975)注入真实生成状态，[ChatViewModel.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatViewModel.swift:647)同时检查前台运行与后台待写入；[SyncBackupView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/SyncBackupView.swift:744)恢复前阻止该状态。
- 导入通过 [IOSConversationStore.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSConversationStore.swift:345)设置导入门、等待已有写入结束、导入会话/关系、递增 importEpoch、刷新内存，并在刷新期间保持门关闭。失败重试保留的是原始加密 Data，而不是已清理的明文验证对象。
- Android [SyncArchiveManager.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:173) finally 清理 payload，但 [BackupVM.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/backup/BackupVM.kt:580)失败后仍持有验证对象；[BackupPage.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/backup/BackupPage.kt:726)依赖恢复后退出应用防旧状态写回。
- **结论：已确认恢复链路差距，P1。** 失败重试契约缺口明确；实际旧写入覆盖、跨域混合结果仍须故障注入。iOS 也会报告“会话已恢复但子代理关系失败”，并非全域事务；不能照抄为 Android 的完整事务保证。
- **验收**：在途生成时不能开始替换；导入成功后旧回调不能覆写；部分失败可辨认；首次 apply 失败后可以重新验证再试；进程回收后状态可恢复。

### D04：系统日历操作深度不足，提醒事项缺少等价工作流

- iOS [IOSAppleIntegrationsView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSAppleIntegrationsView.swift:361)执行日历查询/创建/更新/删除，以及提醒事项查询/创建/更新/删除/完成。[ChatToolRuntime.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatToolRuntime.swift:5498)接入执行器；[能力策略](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSPermissionModels.swift:649)默认启用，仍须策略审批与系统授权。
- Android [SystemAccessTools.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/tools/SystemAccessTools.kt:34)注册日历查询和创建，[CalendarAccessTools.kt](/Users/mi/Downloads/AI/AmberAgent/android/feature/tools/access/src/main/kotlin/app/amber/feature/tools/CalendarAccessTools.kt:15)消费真实 CalendarContract。缺少对应更新/删除工具；本仓任务和 cron 不等价于系统提醒事项双向管理。
- **结论：日历增删改查范围有确认差距，P2；提醒事项是平台适配项。** 日历优先补已有工具族，提醒事项先定义 Android 内部任务的用户等效结果，不能声称与 Apple Reminders 同源同步。
- **验收**：用稳定事件 ID 改期和删除；执行前预览具体事件；授权拒绝可恢复；重复请求不误改同名事件；提醒事项完成状态可重开验证。

### D05：系统快捷入口尚未覆盖“提问—继续—当前任务”

- iOS [IOSAppIntents.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSAppIntents.swift:37)提供提问、每日简报、新对话、继续最近/指定对话、当前任务及保存动作；入口把问题持久交接并打开 App，不能算 Siri 后台无限运行模型。
- Android [shortcuts.xml](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/res/xml/shortcuts.xml:2)当前只声明拍照快捷方式，[ShortcutHandlerActivity.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/activity/ShortcutHandlerActivity.kt:17)负责拍照后分享进入聊天；检索未发现动态 shortcut 注册消费者。
- **结论：确认入口差距，P2。** 复用现有 RouteActivity/会话 owner 增加新建、继续、当前任务、固定提示动作；按 Android 快捷方式和分享入口实现。
- **验收**：冷启动、已有页面、原会话被删、重复点开、过期任务时行为可解释；未经确认的外部文本只进入草稿/既有授权发送流程。

### D06：首页“继续”少了小说、已运行小应用和精确图片定位

- iOS [HomeContinueCardModel](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/PlaceholderViews.swift:1644)与[候选选择](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/PlaceholderViews.swift:1692)覆盖 Novel、DeepRead、Council、MiniApp runner、ImageGen。小说按 project ID 恢复，小应用进入 runner；图片[导航前校验](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/PlaceholderViews.swift:2820)conversation/message/toolCall，并在缺失时反馈。
- Android [ContinueCandidate.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/home/ContinueCandidate.kt:12)仅 Council、DeepRead、MiniApp draft、ImageGen；[注册表](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/di/DataSourceModule.kt:726)没有 Novel source。[ImageGenerationContinueSource.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/home/ImageGenerationContinueSource.kt:35)仅投影未结束 run/effect，route 只带 conversation ID；已成功的最近生成结果不属于当前来源。
- **结论：已确认工作找回差距，P1。** 优先扩展现有候选/source/route，不另建全局任务数据库；新建小说和小应用本身均已有入口。
- **验收**：小说暂停/失败可发现并直达 project/job；最近小应用回到目标 runner；图片回到原 message/toolCall；原对象删除、任务完成或记录失效时反馈正确；冷启动后仍有效。

### D07：DeepRead 的来源与继续意图没有完整贯穿入口

- iOS [IOSBoardPersistence.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSBoardPersistence.swift:2143)保存 source kind、title/content/url/metadata；[IOSDeepReadTask](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSBoardPersistence.swift:2830)保存 task、sources、result、missingSections 等，Home 按 task ID 返回；[retry](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/DeepReadCreateView.swift:51)保留已完成内容。
- Android [DeepReadContinueSource.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/home/DeepReadContinueSource.kt:41)只传 topic/title，[SessionHomePage.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:550)不带 sourceUrl/fromHistory；[DeepReadScreen.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/board/DeepReadScreen.kt:211)会按进入方式决定 runAll。
- **结论：确认来源/路由差距，P1。** Android [runAll](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadAgentRunManager.kt:90)已有 missing-only 缓存保护，不能说每次继续必然全量重跑。
- **验收**：同一链接从创建、历史、Continue 返回均保留来源；“查看结果”和“继续缺失部分”意图可区分；已有阶段不重做；离页、断网、重启后状态与详情一致。

### D08：小说中断后的可恢复状态和定位能力较弱

- iOS 当前[生产组装](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/NovelCreation/NovelCreationComposition.swift:39)使用 DefaultNovelCreation、workspace 自动迁移与 durable store；[NovelGenerationLifecycle.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/NovelCreation/NovelGenerationLifecycle.swift:655)有 lifecycle reconciliation barrier、sidecar 扫描、run identity 校验与 partial/interrupted 收口；[AppShell](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/AppShell.swift:275)接入恢复。
- Android [GhostwriteController](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/novel/workspace/NovelWorkspaceGhostwriteController.kt:24)已有 unique WorkManager、暂停、继续、取消、重试；[Worker](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/novel/workspace/NovelWorkspaceGhostwriteWorker.kt:52)重读 job/ledger、校验 execution。其 [job 模型](/Users/mi/Downloads/AI/AmberAgent/android/feature/novel-workspace/src/main/kotlin/app/amber/feature/novelworkspace/NovelWorkspaceGhostwriteJobs.kt:8)没有同等的 partial/cursor/interrupted 记录和启动协调；损坏 JSON 可能被跳过。
- [通知](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/novel/workspace/NovelWorkspaceGhostwriteWorker.kt:263)创建的是普通 RouteActivity Intent，没有 project/job extras。
- **结论：确认恢复信息与通知入口差距，P1。** WorkManager 能重投递，不等于缺少后台功能；是否重复生成、丢部分内容须分阶段复现。Android 不必复制 iOS sidecar 类结构，应按现有 job/ledger 补必要状态。
- **验收**：生成中、准备提交、已提交未更新进度、暂停、断网、进程回收、坏 job 重启；已提交章节不重复，未提交 partial 可保留或明确中断，用户可选择继续/重试；通知返回同一 project/job。

### D09：双向子代理编排已有实现，但默认用户无法使用完整集合

- iOS [ChatViewModel.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatViewModel.swift:4537)将 spawn/list/interrupt/send/followup/wait 纳入工具发现目录；[ChatToolRuntime.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatToolRuntime.swift:6327)允许通过 deferred discovery 暴露这些工具；[IOSThreadOrchestrationToolService.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSThreadOrchestrationToolService.swift:542)先持久化子会话、关系、mailbox、消息再调度后台运行。
- Android [SubAgentTools.kt](/Users/mi/Downloads/AI/AmberAgent/android/feature/tools/impl/src/main/kotlin/app/amber/feature/tools/SubAgentTools.kt:27)只有 ThreadGraph 打开才追加 followup/sendMessage/interrupt；[CapabilityFlags.kt](/Users/mi/Downloads/AI/AmberAgent/android/core/settings/src/main/kotlin/app/amber/core/settings/CapabilityFlags.kt:25)默认关闭 ThreadGraphV2。默认仍有 list/start/read/wait/cancel，不能说没有子代理。
- **结论：默认可用性差距，P1。** 优先复用已有 Room graph 和管理器，验证后正式开放；不要求工具名称和 iOS 字面相同，也不切 dormant kernel。
- **验收**：子任务执行中发补充、执行后 followup、打断并保留历史、父子关联与原始结果可回看；冷启动后已承诺持久化的关系保留；取消/重复投递/过期操作不会污染别的 run。

### D10：选中文档后的导入、解析和归属状态不够清楚

- iOS [ChatView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatView.swift:599)在选文件时捕获 conversation ID，串行处理授权/Workspace 导入/解析预览，回调再次校验归属；Workspace 保存失败而附件成功时明确提示部分成功。[待发送文件卡片](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatComposerViews.swift:1203)显示名称、字节摘要、截断和附注。
- Android [文件选择回调](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/components/ai/ChatInput.kt:507)支持多文件复制和逐文件失败 Toast，然后加入 Document；[附件 UI](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/components/ai/ChatInputAttachments.kt:284)主要是名称 Chip，缺同等的可见解析状态及明确选择时会话校验。
- **结论：确认导入反馈粒度差距，P1；跨会话污染需复现。** Android [DocumentAsPromptTransformer](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/ai/transformers/DocumentAsPromptTransformer.kt:15)已做文档提取，不能说只上传不解析；Android 多文件选择也应保留，不回退成 iOS 当前只处理首个 URL 的行为。
- **验收**：大文件、PDF、多个文件中一个失败、切换会话、取消、权限失效；逐项显示导入/解析/失败、可移除和重试，草稿不丢，结果不会附到后来打开的会话。

### D11：发送前的配置阻塞反馈较弱

- iOS [ChatView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatView.swift:1641)持续显示配置相关 placeholder，区分 Key、地址、模型、服务商、OAuth 登录和禁用；[sendEnabled](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatView.swift:1604)消费 composerSendBlockReason。
- Android [ChatPage.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt:678)缺模型时在点击发送后 Toast；已有模型选择和设置入口，但发送前缺同等的持续原因提示。
- **结论：确认引导细节差距，P1。** 接到真实 provider/configuration 判定，保留无需模型也能路由的本地动作，不能全局一刀切禁发送。
- **验收**：缺 Key、非法地址、空模型、OAuth 失效、provider 禁用各有精确动作；修复后自动恢复；已有输入与附件不丢。

### D12：首页快速筛选与历史错误处理存在体验差异

- iOS [ConversationsView](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/PlaceholderViews.swift:2184)维护同页 searchQuery，过滤会话并给无匹配状态；Android [SessionHomePage.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:426)搜索进入独立 MessageSearch。Android 本文/标题 FTS 与高级筛选已有，差异在首页快速查找的步骤和上下文保留。
- iOS [IOSConversationStore.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSConversationStore.swift:1794)设置用户可见错误，经 [ChatView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatView.swift:390)展示；Android [HistoryVM.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/history/HistoryVM.kt:21)上游异常只记日志，[HistoryPage.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/history/HistoryPage.kt:67)缺 Paging error/retry 分支。
- **结论：首页快速筛选为体验补齐，P2；历史错误反馈为确认差距，P1。** Paging loadState 与 Flow 异常分开处理。保留独立全文搜索，不为同页筛选重建搜索后端。
- **验收**：同页输入/清除/无匹配/返回保持位置；明确区分空数据与失败；查询、磁盘读取或索引重建失败时保留已有列表，可重试。

### D13：独立系统 TTS 设置和试听尚未覆盖

- iOS [AppShell.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/AppShell.swift:1081)有 TTS 设置路由，[TTSSettingsView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/TTSSettingsView.swift:83)提供系统引擎、语速、试听、停止，[IOSTTSPlayer.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSTTSPlayer.swift:50)消费 AVSpeechSynthesizer。
- Android 本轮检索未发现独立 TTS 设置/播放器消费者；Live 的 voiceInputEnabled 仅存配置，无聊天录音/转写消费。
- **结论：独立设置/试听是确认的小范围差距，P2。** iOS 普通聊天未查到该播放器的朗读消费者，权限页也不等于聊天语音输入。两端普通附件栏都没有专用音视频按钮，不能将“实时语音对话”“音频模型输入”“聊天朗读”算作 iOS 已领先的功能。
- **验收**：系统引擎可用/不可用、语速、试听、停止和离页释放；聊天朗读若要增加，应另列产品新增范围。

### D14：子代理新操作还没有完整进入同一张任务卡

- iOS [ChatToolTimelineView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatToolTimelineView.swift:464)将 spawn/followup 投影为子代理卡，保留工具调用及子会话身份，区分编排请求回执与子任务实际运行状态。
- Android [ChatMessageCot.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/components/message/ChatMessageCot.kt:9)的子代理合并集合只有 start/wait/read/cancel；已实现的 followup/send_message/interrupt 没被该集合纳入。Android 已有独立子代理卡和 transcript，不能说工具结果都只是原始 JSON。
- **结论：确认新操作卡片覆盖差距，P1，随 D09 一起交付。** 不是简单把三个名字加到集合：followup 是新 turn，应明确旧结果、当前 turn 和接受请求三种状态，避免把“消息已入队”显示成“子任务已完成”。
- **验收**：同一子任务多轮 followup 不重复卡片 key、不丢历史；发送补充后状态准确；中断仍可继续；冷启动回看结果与持久记录一致。

### D15：Agent 浏览会话缺少可见任务卡和重开记录

- iOS [IOSWebMountSessionRecord](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSLocalToolExecutor.swift:3941)保存站点、页面摘要、状态、后端、needsReopen 和 owner；[WebMountView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/WebMountView.swift:256)有任务卡、观看页面、紧凑栏和[重新打开](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/WebMountView.swift:810)。
- Android [WebMountTabsTools.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/webmount/tools/WebMountTabsTools.kt:12)只枚举 live WebView pool；[SessionHandle.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/webmount/primitives/SessionHandle.kt:39)维护活跃对象，[池淘汰](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/webmount/primitives/WebViewPool.kt:121)销毁页面。网站设置/登录 UI 存在，未找到 Agent 当前浏览任务的对应卡片和持久重开入口。
- **结论：确认产品闭环差距，P1。** 先做 session metadata 和可见页面，复用现有池；持久化的是可恢复说明，不是假装把 WebView 进程保存了。
- **验收**：Agent 打开站点后用户知道当前页面与状态；可以观看；退出/池淘汰/重启后标记需要重开；重开后重新观察，不把旧 DOM 引用当有效。

### D16：登录和验证码缺少同一会话上的人工接管与交还

- iOS [会话控制 owner](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSLocalToolExecutor.swift:4428)绑定 conversation/run、检查控制权和过期 lease；[acquireUserControl / handBackToAgent](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSLocalToolExecutor.swift:4482)转移控制权；[preflight](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSLocalToolExecutor.swift:5617)生成需要人工处理的条件与回执。
- Android [WebMountLoginController.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/webmount/login/WebMountLoginController.kt:167)处理设置页登录 popup；SessionHandle 没有与 Agent 当前 run 绑定的 user/agent 控制状态。[交互工具](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/webmount/tools/WebMountInteractionTools.kt:76)的审批不等于切换同一页面控制权。
- **结论：确认高频阻断差距，P1。** 登录授权可在原 session 完成，用户交还后重新观察再继续；用户控制期间 Agent 不能同时修改页面。
- **验收**：登录/验证码触发接管，弹窗与 cookies 连贯；交还、取消、run 终止、过期 lease、跨会话误用均校验；重启只恢复 metadata，不能默认续跑旧点击。

### D17：通用浏览的多窗口、弹窗及动作回执不够完整

- iOS [WebMountView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/WebMountView.swift:1145)组合 popup、JS dialog、窗口选择和地址状态；[动作回执](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSLocalToolExecutor.swift:5450)区分 dispatched、verified、postcondition、mayHaveApplied、ambiguous，并有快照/目标校验。
- Android popup 主要在登录 controller；通用 session 缺同样的 active window/dialog UI。Android [runVerifiedAction](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/webmount/tools/WebMountInteractionTools.kt:449)已有 DOM fingerprint、network delta 和禁止自动 retry 标记，但没有同等的“动作已发出—目标已证实—结果未知”回执粒度。
- **结论：确认交互覆盖与结果语义差距，P1。** 保留已有观测能力；不是把 DOM 改变简单改名为“操作成功”。
- **验收**：SSO 多窗口、alert/confirm/prompt 可处理；旧 dialog 回调不能回应新窗口；stale ref 在动作前拒绝；动作后桥断/超时返回可能已应用，不能自动重试外部副作用；postcondition 原本就满足时不误判本次成功。

### D18：新增网站的确认策略与 iOS 不同

- iOS [wm_site_add](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSLocalToolExecutor.swift:5258)要求 user-initiated 操作，否则返回 needs_user_action。
- Android [WebMountSiteTools.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/webmount/tools/WebMountSiteTools.kt:196)直接增加 registry，[ToolRegistry.kt](/Users/mi/Downloads/AI/AmberAgent/android/feature/tools/api/src/main/kotlin/app/amber/feature/tools/ToolRegistry.kt:394)明确 needsApproval=false/autoApprovable=true。
- **结论：确认交互策略差异，P2。** 随浏览器闭环增加新增站点预览与确认，保证设置列表里的新增有可理解来源；不扩张成所有只读浏览都重复审批。
- **验收**：预览站点/域名、确认后才持久化、取消不写入、重复添加幂等；已有删除确认不退化。

### D19：MiniApp 缺少 iOS 新增的整套系统能力

- iOS [IOSMiniAppBridgeRuntime.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSMiniAppBridgeRuntime.swift:211)注册 haptics、device/battery、screen brightness/keep-awake、speech、share、openURL、qrcode；[MiniAppRunnerWebView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/MiniAppRunnerWebView.swift:154)创建 native owner 并注入 systemHandler，实际由 [IOSMiniAppDeviceCapabilities.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSMiniAppDeviceCapabilities.swift:41)执行。
- [系统 dispatch](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSMiniAppBridgeRuntime.swift:688)经过全局开关、manifest/grant、前台状态与必要确认，关闭 runner 后不再返回有效结果；仅记录动作摘要，分享内容和 URL 不进入该审计 payload。
- Android [MiniAppModels.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/miniapp/MiniAppModels.kt:19)的 V3 权限到 clipboard/location/sensor 等；[MiniAppBridge.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/miniapp/bridge/MiniAppBridge.kt:337)和 JS SDK 没有上述系统方法，未知方法直接失败。
- **结论：确认完整功能组缺失，P1。** 保留已有 sandbox/grants/audit/CAS 与版本历史，按同一 SDK 方法和错误契约扩展。先实现 app.info/app.capabilities，再分别加入无会话状态和有会话状态的能力。
- **验收**：同一个合成小应用可探测平台、震动、读电量、生成二维码、分享和打开链接；未声明/关闭/拒绝得到稳定错误；语音、亮度和保持唤醒归属当前 runner，后台/退出/新 runner 替换时正确释放。真实触感、音频、系统分享和外部 App 必须设备验收。

### D20：缺少宿主管理的 Remote SSH 连接与信任流程

- iOS [IOSTerminalBuildPolicy](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSTerminalRuntime.swift:108)把 Remote SSH 放在可选运行时；[生产实例](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSTerminalRuntime.swift:776)注入 IOSSSHRuntimeBackend；[IOSSSHModels.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSSSHModels.swift:16)维护 profile/known-host/凭据，[backend](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSSSHRuntimeBackend.swift:170)执行连接、host-key 校验和输出。
- Android [TerminalRuntimeModels.kt](/Users/mi/Downloads/AI/AmberAgent/android/feature/terminal/api/src/main/kotlin/app/amber/feature/terminal/TerminalRuntimeModels.kt:7)只有 builtin_alpine、android_shell、termux_external，缺该 managed SSH profile/backend。用户在外部终端自行安装 SSH 不等于宿主已经具备此流程。
- **结论：确认功能差距，P2。** Android 已有 command/job/工作区和非 PTY session；iOS AmberShell/iSH 不需要移植。SSH 的客户端复用/依赖可行性独立验证，不能为追齐未经授权引入新依赖。
- **验收**：指纹未信任时仅探测，信任后运行；host-key 改变阻止执行；凭据不进快照/日志；stdout/stderr/exit code、取消、超时、重启后未知远端状态明确。当前非 PTY 的 [TerminalTools](/Users/mi/Downloads/AI/AmberAgent/android/feature/tools/impl/src/main/kotlin/app/amber/feature/tools/TerminalTools.kt:192)继续如实声明限制，不把 TUI 支持混入 SSH 第一版。

## 3. 平台扩展、已有能力与共同限制

### E01：Apple Watch 是明确新增的产品面，需作为可穿戴专项

iOS [WatchTaskRootView.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/WatchApp/WatchTaskRootView.swift:39)有提问、任务、最近成果、会话、随手记和连接设置；[AppShell.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/AppShell.swift:269)将 WatchTaskCoordinator 接到真实 ChatViewModel/后台投影；[WatchTaskCoordinator.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/WatchTaskCoordinator.swift:1228)限制可在手表处理的审批。手机拥有执行与模型，手表负责输入、回看和受限操作。

Android 当前项目没有对应 Wear 模块，属于确认的产品面差异。它不能按“把 watchOS 页面翻译成 Compose”估算：需要配对、可靠交接、离线草稿、重复消息处理、隐私预览与手机 owner。建议单列 E01，不阻塞手机端追齐；iOS 自身的[Watch 验收记录](/Users/mi/Downloads/AI/AmberAgent/ios/docs/product/amber-watch-v1.md:1)仍需真机闭合，本轮也没有 Watch 设备实测。

### E02：健康、系统闹钟、运动计划和 WeatherKit 是平台能力扩展

iOS [ChatToolRuntime.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatToolRuntime.swift:5498)真实分发健康、日历、提醒、闹钟、运动工具；[IOSPermissionModels.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSPermissionModels.swift:706)记录健康/天气的 entitlement 与审批要求。这不等于所有设备默认可用，签名授权、系统版本和数据可得性都需要实测。

Android 的 [SystemAccessTools.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/tools/SystemAccessTools.kt:34)已有日历、位置、设备信息、通知等，健康摘要与运动计划未找到等效链路。应先补 D04 中可复用的日历操作，再按明确产品范围新增健康摘要、系统闹钟/内部提醒和运动计划；平台 API 不同，不能承诺与 Apple 健康数据库或 Reminders 直接同步。开发时核对官方 SDK 与权限文档，不在研究阶段承诺未经验证的 API 版本和上架条件。

### E03：Live Activity 应映射为 Android 的通知与任务面板

iOS 有 [AgentLiveActivityController.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/AgentLiveActivityController.swift:61)及 Widget UI；Android 已有前台服务、进度通知、审批/回复/停止动作和[绑定 conversation/run 的 PendingIntent](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/service/ChatService.kt:2578)。因此“没有灵动岛”不算 Android 功能缺陷。验收重点是状态、动作和回到原任务，特别补齐小说的精确路由；美术形式适配平台。

### N01：不要重复开发或撤掉的 Android 能力

| 已有资产 | 真实证据与结论 |
| --- | --- |
| Codex 账户额度 | [ChatInput.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/components/ai/ChatInput.kt:344)查询，[Slash OpenUsage](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/components/ai/ChatInputComposers.kt:282)打开用量页；ContextRing 的 false 仅是另一处入口未接线，不能称全局缺失 |
| 发送、停止、排队/追问 | 两端均有实际消费者；Android [ChatService.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/service/ChatService.kt:1191)，iOS [ChatViewModel.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/ChatViewModel.swift:3405)；行为一致性需真实旅程验证 |
| 文档提取、多文件选择 | Android [DocumentAsPromptTransformer.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/ai/transformers/DocumentAsPromptTransformer.kt:63)有真实解析；D10 修反馈和归属，不能降级为只支持单文件 |
| 全文检索 | Android [MessageFtsManager.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/agent/data/db/fts/MessageFtsManager.kt:20)与搜索页已有正文/标题/筛选；D12 的同页筛选不替代全文搜索 |
| 子代理与模型议会 | 已有执行、卡片、结果、Room/transcript；D09/D14 扩展默认编排和新操作呈现，不重写整个系统 |
| 记忆 | 已有提取、召回、作用域、dream、CAS 与编辑页；需修 UI 没传 revision 的并发边界，不能算缺少记忆 |
| cron | Android 通过现有 cron manager/WorkManager 执行；本轮未找到 iOS 等价产品 scheduler，不作为追齐缺口 |
| Workspace、Skills、MCP | 已有文件/产物、技能安装校验、MCP 配置导入与工具调用；Android [ChatRunCoordinator.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/ai/ChatRunCoordinator.kt:201)已用 ToolExposureState，[ToolSearch.kt](/Users/mi/Downloads/AI/AmberAgent/android/feature/tools/api/src/main/kotlin/app/amber/feature/tools/ToolSearch.kt:324)在工具数超过 40 时延迟暴露；不能把 raw registry 当作每轮全部发送的 schema |
| Android 系统访问 | 通知、应用、位置、短信/通话等平台工具是现有资产；iOS 的 Apple 能力列表不能取代它们 |
| Google Code Assist / Vertex | Android 已有；iOS [supportsChat](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSGeminiProvider.swift:200)不支持这些当前路径，新增 Antigravity 要并存 |

“已有”表示源码入口与消费存在；不代表各项已在两端设备通过同样验收。

### N02：两端共同限制与不能据此宣布落后的项目

1. **Novel 跨端契约尚未闭合。** iOS [NovelProjectRepository.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/NovelCreation/NovelProjectRepository.swift:224)保留领域 document authority 并投影到 checkout/.amber/engine；Android [NovelWorkspaceProjectRepository.kt](/Users/mi/Downloads/AI/AmberAgent/android/feature/novel-workspace/src/main/kotlin/app/amber/feature/novelworkspace/NovelWorkspaceProjectRepository.kt:14)和 ledger 树不同。两端都有 Markdown 不等于项目可直接交换。旧 [fixture README](/Users/mi/Downloads/AI/AmberAgent/android/test-fixtures/novel-v1/README.md:45)明确是手工 Swift wire 样本，且引用了过期小说 canary。
2. **快照备份不是实时同步。** Android [SyncProvider.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/sync/provider/SyncProvider.kt:18)是快照 CRUD；iOS [IOSSyncBackup.swift](/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSSyncBackup.swift:61)导出 settings 与可选 conversations/thread edges。Android 完整恢复还要求平台数据集，不能为了让 iOS 包“导入成功”移除保护。分别验收本机恢复、换机、会话交换、小说交换；本轮无证据支持完整实时双向同步。
3. **实时语音不是已证实 iOS 优势。** iOS 系统 TTS 设置、MiniApp speech 和 Speech 权限不是普通聊天录音转写消费者；Android Live 屏幕观察也不是语音通话。普通聊天音视频专用入口两端均未证实完整具备。
4. **性能与无障碍尚无两端设备对测。** iOS 当前 ChatView 使用 NativeChatTimelineView，并有滚动状态/分页锚定；Android 有 LazyColumn、分页和流式处理。不能从 UIKit/Compose 技术选型推断帧率。34dp 可见尺寸不等于真实点击区域，子图片 contentDescription=null 也不等于父节点没有语义。最终以语义树、TalkBack/VoiceOver 和设备触控为证。
5. **MCP 发现已有闭环。** iOS expanded MCP 工具进入 bridge 后延迟发现；Android 也在实际生成协调器中筛选 toolsForStep，并观察 tool_search 结果。两端差异是工具数量阈值和常驻集合，不能认定 Android 缺延迟发现，也不能未经测量断言 token 浪费。后续以少量/大量 MCP 注册场景捕获真实请求 schema，验证精确命中和禁用策略。
6. **新 kernel 不代表默认路径。** Android [ChatService.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/service/ChatService.kt:873)的 useKernelPath 默认 false。将当前生产功能追齐与内核替换分开，避免给所有任务增加无关前置重构。

## 4. Android 独立整改与发布前风险

以下有价值，但不能在没有 iOS 生产证据时都包装成“iOS 已领先”。它们作为追齐工作的稳定性配套，不挤占所有新增功能排期。

| ID | 问题及证据 | 分类与处理 |
| --- | --- | --- |
| Q01 | [BackupVM.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/backup/BackupVM.kt:595)默认 viewModelScope 直接调用同步归档解析/哈希/解密/表恢复，未见该链路切 IO | 主线程负载风险；随 D03 修线程归属，大备份真机测量，不能只加进度条 |
| Q02 | [SyncArchiveManager.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:524)先提交文件/DB，之后更新 secrets/FTS/settings | 跨域部分失败风险；复用已有文件恢复日志与 owners，按阶段故障注入；不声称已有数据损坏 |
| Q03 | [Live 设置](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentExecutionPage.kt:325)的 voiceInputEnabled 没有运行消费者 | 已确认无效设置；从正式界面移除或准确说明，不能以试听功能冒充录音转写 |
| Q04 | [SynaraWorkspacePage.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/synara/SynaraWorkspacePage.kt:178)主 frame 错误仅日志 | 增加 loading/error/retry/回连接设置；远程桌面的服务端能力本轮未实测 |
| Q05 | [MiniAppSourceEditor.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppSourceEditor.kt:79)虽计算 unsaved，但 onDismissRequest 直接关闭 | 源码确认未保存退出缺口；返回/外部点按/关闭统一处理，保留用户输入 |
| Q06 | [SettingAgentMemoryVM.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentMemoryVM.kt:85)编辑/删除未传 expectedRevision；[MemoryRepository.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/memory/store/MemoryRepository.kt:95)默认 blind update | CAS 接线缺口，复用现有方法；并发覆盖后果尚未复现，测试 N→N+1 冲突并保留编辑文本 |
| Q07 | [AgentTaskStore.kt](/Users/mi/Downloads/AI/AmberAgent/android/feature/task/src/main/kotlin/app/amber/feature/task/AgentTaskStore.kt:225)吞掉 persist 错误，更新先写内存；error 的 Elvis 无法清空旧错误 | 源码确认状态/持久化缺口；故障写入不能发布伪成功，失败后成功能清旧错误，不新建任务框架 |
| Q08 | [RunRecoveryService.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/runtime/RunRecoveryService.kt:205)服务端 COMPLETED 分支先 finish，再核对本地 STARTED effects | 默认关闭的 Responses 恢复测试门；先复现混合状态再决定修复，未验证前不开 flag |
| Q09 | [ThreadGraphDAO.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/agent/data/db/dao/ThreadGraphDAO.kt:47)claim 在 Room @Transaction 内，但未检查 UPDATE 数量 | 补并发 drain/取消/重启测试；事务可能已隔离竞争，不能仅凭忽略行数认定重复投递 bug，更不能承诺全链路 exactly-once |
| Q10 | [CapabilityFlags.kt](/Users/mi/Downloads/AI/AmberAgent/android/core/settings/src/main/kotlin/app/amber/core/settings/CapabilityFlags.kt:25)10 flags 中 8 个默认关闭；[BackupPage.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/backup/BackupPage.kt:431)WebDAV/本地文件夹区域被 SyncProviderV2 隐藏 | 逐一记录真实消费者、前置条件和升级行为；不要一键全开；无 Novel 消费者的 NovelPackageV2 不能用于判定小说不可用 |
| Q11 | [native build workflow](/Users/mi/Downloads/AI/AmberAgent/android/.github/workflows/android-native-build-check.yml:1)仅特定路径触发；[StartupBenchmarks.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/baselineprofile/src/main/java/app/amber/baselineprofile/StartupBenchmarks.kt:50)只覆盖启动 | 增加普通 Kotlin/domain PR 定点门，以及长聊天滚动、键盘、恢复旅程的设备基线 |

## 5. 细节体验验收矩阵

按用户旅程验收，正常路径之外必须检查适用的中断和恢复状态。这里是待实施/待测的验收定义，不是本轮已经观察到的设备表现。

| 旅程 | 输入与操作 | 对齐标准 |
| --- | --- | --- |
| 首次使用 | 新数据；无 Key/模型；从引导进入设置再返回 | 原草稿保留，知道为何不能发送，能一步定位对应设置 |
| 查找会话 | 首页筛选、全文搜索、清除、无匹配、磁盘错误 | 不混淆失败和空结果；保留位置；结果可定位到消息 |
| 流式聊天 | 120+ 消息；分页加载；长代码/表格/公式；流式时上滑阅读 | 旧内容不跳位，离开底部不抢滚动，返回底部后恢复跟随 |
| 输入与键盘 | 中文组合输入、多行粘贴、切模型、旋转/分屏、返回 | 不发出未确认组合文本、不丢草稿、键盘不盖关键动作 |
| 附件 | 多文件、部分失败、过大/截断、权限撤销、导入时切会话 | 有单项状态与重试；归属准确；可读预览和完整模型输入语义分开 |
| 子代理 | spawn、执行中补充、followup、interrupt、cancel、重启 | 父子/turn/run 身份准确，接受消息不等于任务完成，旧结果可回看 |
| 浏览器 | 登录、验证码、人工接管、关闭页面、后台、继续 | 自动化与人操有明确归属，继续前重新观察页面，不盲点旧坐标 |
| MiniApp | 创建、运行、授权系统能力、离页、编辑、撤销权限 | 产物可回看，权限/能力声明真实，音频/屏幕等会话状态可清理 |
| 小说 | writing/提交边界中断；手改；质量失败；暂停和通知返回 | 已提交章节不重复；候选与正式稿区分；原 project/job 能找回 |
| 深读 | 原 URL、缓存部分完成、继续、只读回看、来源不可访问 | 来源保留，已完成部分不抹掉，失败阶段可准确续跑 |
| 备份 | 错口令、空间不足、损坏、导入中写入、分阶段失败 | 旧数据不被未验证包覆盖；部分失败可辨；失效验证对象不能重用 |
| 系统与外观 | 锁屏/通知、深浅色、字体 1.0/1.3/2.0、TalkBack | 状态和操作可读；语义完整；实际触控目标合格；敏感预览遵守设置 |

性能记录必须包括设备、系统、build、数据规模、采样方式及帧耗时/启动/内存结果。先建立固定设备基线，再约定阈值，不用主观“更丝滑”作验收。

## 6. 已执行验证与尚未验证范围

Android 前置审计实际运行两组定点单测，合计 **53 通过，0 failure/error/skipped**：SyncArchiveV2 10、ProductionChainCanary 2（仅 Workspace）、SnapshotCompatibility 5、CapabilityFlags 5、NovelFixtureIntegrity 6、NovelIosCurrentWireCases 2、NovelWorkspaceRuntime 21、BackupVMRestoreCleanup 2。日志位于 `/tmp/android-parity-baseline-tests.log` 和 `/tmp/android-parity-domain-tests.log`。命令如下：

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'app.amber.agent.data.sync.SyncArchiveV2Test' \
  --tests 'app.amber.agent.canary.ProductionChainCanaryTest' \
  --tests 'app.amber.core.sync.provider.SnapshotCompatibilityTest' --console=plain

./gradlew :core:settings:testDebugUnitTest \
  --tests 'app.amber.core.settings.CapabilityFlagsTest' \
  :feature:novel:testDebugUnitTest \
  --tests 'app.amber.feature.novel.serialization.NovelIosCurrentWireCasesTest' \
  --tests 'app.amber.feature.novel.serialization.NovelFixtureIntegrityTest' \
  :app:testDebugUnitTest \
  --tests 'app.amber.feature.novel.workspace.NovelWorkspaceRuntimeTest' \
  --tests 'app.amber.feature.ui.pages.backup.BackupVMRestoreCleanupTest' --console=plain
```

首次 offline 因缺少已声明的 ktor-client-mock 缓存失败，在线重试成功，未改变依赖声明。补读 iOS 后没有产品实现改动。为核实 MCP 发现与子代理线程图，又运行以下定点测试，**30/30 通过**：ToolSearch 15、RoomThreadGraphStore 3、SubAgentThreadGraphIntegration 12；全程离线，日志 `/tmp/android-parity-discovery-threadgraph-tests.log`。首次运行被 Gradle 缓存锁权限阻止，授权执行后成功；测试筛选类名经源码包名纠正，最终以 3 份 XML 的实际执行数为准，不以 BUILD SUCCESSFUL 推断所有筛选项均命中。

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'app.amber.feature.tools.ToolSearchTest' \
  --tests 'app.amber.feature.subagent.RoomThreadGraphStoreTest' \
  --tests 'app.amber.feature.subagent.SubAgentThreadGraphIntegrationTest' \
  --offline --console=plain
```

三组不同用例合计 **83 项通过，0 failure/error/skipped**。这些测试不覆盖本次所有新增差距，也不替代当前 iOS runner、真实模型联网、真机性能或跨端导入。线程图测试通过不等于本次新增的进程死亡/并发故障场景已经覆盖。后续 Gradle 运行可能覆盖 build/test-results XML，按日志及本记录保存验证范围。

iOS 现有测试源可作为下一阶段起点，包括 NativeTimelineScrollCore、IOSConversationStore、IOSTTSPlayer、IOSGeminiProvider、NovelGenerationLifecycle、NovelWorkspaceContract、IOSDeepReadPipeline、IOSImageGenerationRepository 等。历史文档中的测试数量没有当作当前 HEAD 测试结果。

最终检查仅针对本轮文档链接、行号与空白。Android 原有 39 个已跟踪 WIP 保留，iOS 工作区保持干净。全仓 `git diff --check` 在既有 RouteActivity.kt 的 386、440、477、485 行有尾随空白，未修改这些无关代码，不报告全仓检查通过。

## 7. 建议的执行顺序

先并行推进三条清晰的工作线：**日常聊天/工作找回、子代理/浏览器、小应用**。备份恢复和持久状态修复作为相关批次前置门；小说跨端契约先做真实样本验证，再补恢复和交换。Grok/Antigravity 独立做账号矩阵，系统日历与快捷入口安排在手机核心差距之后。Watch、健康、运动与完整迁移分别估算，避免用平台扩展拖延高频体验的交付。

具体任务、依赖、工作量、批次、测试和完成定义见[详细实施计划](/Users/mi/Downloads/AI/AmberAgent/android/docs/plans/2026-09-09-android-ios-parity-plan.md)。最终追齐声明只适用于固定双端版本和已验收场景；未解决项继续留在台账中。

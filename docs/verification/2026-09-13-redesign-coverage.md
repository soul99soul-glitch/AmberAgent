# AmberAgent Android：最新版 81 个 HTML 原型覆盖映射

核对日期：2026-09-13。范围是 `/private/tmp/amber-redesign-latest-l7_ml_uj/` 下的 81 个 `*.html`，已排除 `index.html` 和 `prototype.html`。已读取该目录的 `DESIGN.md`、`HANDOFF.md` 及 Android 根目录 `AGENTS.md`。

`HANDOFF.md` 的“20 屏”是旧版说明；本报告以当前 81 文件清单和工作树源码为准。Android 路由总入口在 [`RouteActivity.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/agent/RouteActivity.kt)，`Screen` 定义在 [`RouteActivity.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/agent/RouteActivity.kt)。

状态含义：

- `直连`：有真实 `Screen` entry，页面由 Android Composable 直接承载。
- `嵌套`：没有独立 `Screen`，但 HTML 所示叶子在真实父页中有对应组件、sheet 或 tab。
- `参数化/样例`：真实入口存在，但 HTML 中的会话、项目、模型、远端页面或统计数据是固定样例，不能直接当作生产数据或无参导航。
- `缺口`：未发现等价的生产 UI/入口，或现有入口明确指向了错误页面。

## 覆盖映射

### 主流程、聊天、历史和辅助页

| HTML | 真实 Android 文件 / 组件 | 入口 | 结论 |
|---|---|---|---|
| `about.html` | [`SettingAboutPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAboutPage.kt) | `Screen.SettingAbout` (`RouteActivity.kt`) | 直连；版本/设备信息由 `BuildConfig` 和系统动态提供，HTML 的 1.4.0、Pixel 8 是样例。 |
| `backup.html` | [`BackupPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/backup/BackupPage.kt) | `Screen.Backup` (`RouteActivity.kt`) | 直连；Google/WebDAV/本地备份和快照状态来自 VM，HTML 账号、快照日期和容量是样例。 |
| `boot.html` | `amber_launch_background.xml` → `amber_launch_brand.xml` | 原生 Android 启动窗口 | 平台适配：复用点阵标并加入静态 cursor；不增加启动等待，不伪造固定进度。 |
| `chat.html` | [`ChatPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt) 的空会话分支 | `Screen.Chat` (`RouteActivity.kt`) | 直连/参数化；空态 hero、建议和 composer 有真实实现，HTML 没有真实 conversation UUID。 |
| `chat-input.html` | [`ChatPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt) → [`ChatInput.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/components/ai/ChatInput.kt)、`ChatInputAttachments.kt`、`ChatInputComposers.kt` | `Screen.Chat` | 嵌套/参数化；附件、slash、mention、发送/停止等有真实组件。HTML 底部软件键盘是系统 IME 的示意，不是 app-owned UI。 |
| `chat-messages.html` | [`ChatPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt) → `ChatList.kt`、`ChatMessage.kt`、`ChatMessageMessagePartsBlock.kt`、`ChatMessageTools.kt` | `Screen.Chat` | 嵌套/参数化；工具卡、审批、推理、子代理等由真实消息 parts 动态投影，HTML 的 `shell_exec`/`BUILD SUCCESSFUL` 是样例。 |
| `chat-storage.html` | `SettingChatStoragePage.kt` | `Screen.SettingChatStorage`，设置首页进入 | 已补齐：真实数据库位置、会话/消息数量、SQLite 格式、容量；导出复用交换服务，索引维护复用 SearchVM；保留附件管理入口。 |
| `debug.html` | [`DebugPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/debug/DebugPage.kt) | `Screen.Debug` (`RouteActivity.kt`) | 直连；debug 工具、flags、token-fit 和 launch count 有真实 VM/设置消费；固定数字和示例 Mermaid 仅用于示范。 |
| `developer.html` | [`DeveloperPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/developer/DeveloperPage.kt) | `Screen.Developer` (`RouteActivity.kt`) | 直连；日志来自 `DeveloperVM`，空日志文案可是真实空态。 |
| `favorites.html` | [`FavoritePage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/favorite/FavoritePage.kt) | `Screen.Favorite` (`RouteActivity.kt`) | 直连/样例；真实收藏由 `FavoriteVM` 投影并可定位回 conversation/node，HTML 的四条收藏是样例。 |
| `history.html` | [`HistoryPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/history/HistoryPage.kt) | `Screen.History` (`RouteActivity.kt`) | 直连/样例；会话分页、删除、恢复和错误重试有真实链路，HTML 行数据是样例。 |
| `live-companion.html` | [`LiveCompanionPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionPage.kt) | `Screen.LiveCompanion` (`RouteActivity.kt`) | 直连/样例；真实页面有无障碍引导、模型选择、分析结果和配置，HTML 上次分析内容是样例。 |
| `log.html` | [`LogPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/log/LogPage.kt) | `Screen.Log` (`RouteActivity.kt`) | 直连/样例；请求日志来自 `Logging`，HTML 的 URL/status/duration 是固定示例。 |
| `profile.html` | [`ProfilePage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/profile/ProfilePage.kt) | `Screen.Profile` (`RouteActivity.kt`) | 直连/样例；头像、昵称、统计和热力图有真实设置/`StatsVM`，`AX` 与统计数字是样例。 |
| `search.html` | [`SearchPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/search/SearchPage.kt) | `Screen.MessageSearch` (`RouteActivity.kt`) | 直连/样例；真实 FTS、筛选、分页/重建和错误态存在，HTML 结果标题是样例。 |
| `session-home.html` | [`SessionHomePage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt) | `Screen.SessionHome` (`RouteActivity.kt`) | 直连/样例；会话列表、continue candidate、删除/置顶和 rail 有真实数据链路。首页上传图的用户例外不在本覆盖报告中提出替换或重绘建议。 |
| `stats.html` | [`StatsPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/stats/StatsPage.kt) | `Screen.Stats` (`RouteActivity.kt`) | 直连/样例；热力图和累计统计来自 `StatsVM`，HTML 的 53 周数字是样例。 |

### 今日看板和深度阅读

| HTML | 真实 Android 文件 / 组件 | 入口 | 结论 |
|---|---|---|---|
| `board.html` | [`BoardPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/board/BoardPage.kt) (`TodayBoardPage`) | `Screen.TodayBoard` (`RouteActivity.kt`) | 直连/样例；热点、下拉刷新、原文、分享、深读动作由 `BoardViewModel`/repository 提供，HTML 话题列表是样例。 |
| `board-settings.html` | [`SettingTodayBoardPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/board/SettingTodayBoardPage.kt)，`paneRoute = null` | `Screen.SettingTodayBoard` (`RouteActivity.kt`) | 直连；看板开关、后台策略、刷新间隔、Wi-Fi、深读和信号来源设置均有真实消费。 |
| `board-sources.html` | [`HotListSourceSettings.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/board/HotListSourceSettings.kt)，由 [`SettingTodayBoardPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/board/SettingTodayBoardPage.kt) 挂载 | `Screen.SettingTodayBoardDetail("hot_list")` (`RouteActivity.kt`) | 嵌套；内置来源、自定义来源、NewsNow、删除/开关和刷新均有真实组件与 repository。 |
| `board-template.html` | [`DeepReadTemplateSettings.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/board/DeepReadTemplateSettings.kt)，同属 `hot_list` pane | `Screen.SettingTodayBoardDetail("hot_list")` | 嵌套；默认/自定义模板选择、预览、删除和创建工作台均有真实消费。 |
| `board-topic-sheet.html` | `BoardPage.kt` 挂载的私有 [`HotListActionSheet`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/board/BoardPage.kt) | `Screen.TodayBoard` 内选中 `HotTopic` | 嵌套/样例；深度阅读、重新生成、打开原文和分享在生产均有回调。HTML sheet 的话题与四个动作是静态演示，单独文件没有独立 route。 |
| `board-workbench.html` | [`DeepReadTemplateWorkbenchPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/board/DeepReadTemplateWorkbenchPage.kt) | `Screen.DeepReadTemplateWorkbench` (`RouteActivity.kt`) | 直连；模板 Agent 生成/修订、校验、预览、保存和退出保护有真实链路。 |
| `deepread-history.html` | [`DeepReadHistoryPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/board/DeepReadHistoryPage.kt) | `Screen.DeepReadHistory` (`RouteActivity.kt`) | 直连/样例；历史由 `HotListRepository` 提供，点击带 `topicId/title/sourceUrl/fromHistory`，HTML 四条记录是样例。 |
| `deepread-reader.html` | [`DeepReadScreen.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/board/DeepReadScreen.kt) | `Screen.DeepRead` (`RouteActivity.kt`) | 直连/参数化；真实页面按 topic/cache/history 状态展示生成中的各节、错误、重试和纸面阅读；HTML 文章正文是样例，不可硬编码到生产。 |

### 小说和模型议会

| HTML | 真实 Android 文件 / 组件 | 入口 | 结论 |
|---|---|---|---|
| `novel-projects.html` | [`NovelProjectsPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/novel/NovelProjectsPage.kt) | `Screen.NovelProjects` (`RouteActivity.kt`) | 直连/样例；项目列表、创建、导入、删除、重命名和导出有真实 VM，`星海拾荒者` 等项目是样例。 |
| `novel-workspace.html` | [`NovelMarkdownWorkspacePage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/novel/NovelMarkdownWorkspacePage.kt) 的创作 tab | `Screen.NovelMarkdown(projectId, branchSlug?, jobId?)` (`RouteActivity.kt`) | 直连/参数化；聊天、草稿、收录/追加和分支状态来自真实工作区。HTML 的项目/草稿/对话文字是样例。 |
| `novel-chapters.html` | 同一页面的 `MarkdownWorkspaceManuscript`（[`NovelMarkdownWorkspacePage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/novel/NovelMarkdownWorkspacePage.kt)） | `Screen.NovelMarkdown`，tab=正文 | 嵌套/参数化；章节目录、读取、重写、批量润色和一致性检查有真实组件。HTML 的章节数据是样例；另见下方原型 dead-link。 |
| `novel-editor.html` | [`MarkdownChapterEditor`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/novel/NovelMarkdownWorkspacePage.kt)，由 manuscript tab 在 `NovelMarkdownWorkspacePage.kt` 打开 | 无独立 `Screen` | 嵌套；标题/body 读取、保存、取消和分支写锁均有生产组件，HTML 是编辑器状态样例。 |
| `novel-ghostwrite.html` | [`MarkdownGhostwriteSheet`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/novel/NovelMarkdownWorkspacePage.kt)，调用点 [`NovelMarkdownWorkspacePage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/novel/NovelMarkdownWorkspacePage.kt) | 无独立 `Screen`，由 `Screen.NovelMarkdown` 工作区打开 | 嵌套/参数化；目标章节、计划、上下文注入、暂停/恢复/重试/取消和持久批次有真实链路。HTML 的第三章方向和开关是样例。 |
| `council-room.html` | [`CouncilRoomPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/councilroom/CouncilRoomPage.kt)，含 `CouncilTimelineTab`、`CouncilRoomComposer` | `Screen.CouncilRoom(conversationId)` (`RouteActivity.kt`) | 直连/参数化；真实 room 必须绑定已有 conversationId，HTML 的主持/成员/轮次/输出是样例。 |
| `council-members.html` | [`CouncilMembersSheet.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/councilroom/CouncilMembersSheet.kt)，由 `CouncilRoomPage.kt` 打开 | `Screen.CouncilRoom` 内的 members sheet | 嵌套/参数化；成员、状态和综合结论来自 room；HTML sheet 没有独立 route，成员列表是样例。 |
| `settings-council.html` | [`SettingExperimentalModelCouncilPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingExperimentalModelCouncilPage.kt) | `Screen.SettingExperimentalModelCouncil` (`RouteActivity.kt`) | 直连/样例；席位、外部 CLI、主持模型、轮数/超时/预算均接入 settings/repository，HTML 模型名是样例。 |

### 扩展、技能和小应用

| HTML | 真实 Android 文件 / 组件 | 入口 | 结论 |
|---|---|---|---|
| `extensions.html` | [`ExtensionsPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/extensions/ExtensionsPage.kt) | `Screen.Extensions` (`RouteActivity.kt`) | 直连；快捷消息、收藏、提示词三个入口真实存在。 |
| `prompts.html` | [`PromptPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/extensions/PromptPage.kt) | `Screen.Prompts` (`RouteActivity.kt`) | 直连；模式注入/世界书、排序、启停和导入由 `PromptVM`/settings 提供，HTML 条目是样例。 |
| `quick-message-editor.html` | [`EditQuickMessageDialog`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/extensions/QuickMessagesPage.kt)，由 `QuickMessagesPage.kt` 打开 | `Screen.QuickMessages` (`RouteActivity.kt`) | 嵌套；编辑对话框有真实新增/修改/删除消费，无独立 route。 |
| `skills.html` | [`SkillsPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/extensions/SkillsPage.kt) + [`SkillDetailPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/extensions/SkillDetailPage.kt) | `Screen.Skills` / `Screen.SkillDetail(skillName)` (`RouteActivity.kt`) | 直连/参数化；技能库和详情文件树、MCP 配置、启停/删除有真实 VM，HTML 的 `web-research` 树是样例。 |
| `skill-add.html` | [`AddSkillDialog`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/extensions/SkillsPage.kt) + `ImportSkillDialog` (`:807`)，由 `SkillsPage.kt` 打开 | `Screen.Skills` 内 sheet/dialog | 嵌套；GitHub 导入、手动添加和保存有真实消费，无独立 route。 |
| `mini-apps.html` | [`MiniAppListPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppListPage.kt) | `Screen.MiniAppList` (`RouteActivity.kt`) | 直连/样例；列表来自 `MiniAppRepository`，HTML 的 6 个小应用是样例。 |
| `miniapp-editor.html` | [`MiniAppSourceEditorDialog.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppSourceEditor.kt)（文件内 composable 名为 `MiniAppSourceEditorDialog`），由 `MiniAppListPage.kt` 打开 | `Screen.MiniAppList` 内 dialog | 嵌套；源码查看/编辑、校验、预览、保存和未保存退出保护有真实实现，无独立 route。 |
| `miniapp-runner.html` | [`MiniAppRunnerPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppRunnerPage.kt) | `Screen.MiniAppRunner(appId)` (`RouteActivity.kt`) | 直连/参数化；真实内容来自对应 `MiniAppEntity` 的 WebView。HTML 汇率计算器是样例，不代表固定内置数据。 |
| `miniapp-settings.html` | [`MiniAppSettingsPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppSettingsPage.kt) | `Screen.MiniAppSettings` / `Screen.MiniAppSettingsDetail(group)` (`RouteActivity.kt`) | 直连；common/host AI/advanced 分组与开关有真实 settings 消费。 |

### 设置体系、记忆和数据

| HTML | 真实 Android 文件 / 组件 | 入口 | 结论 |
|---|---|---|---|
| `settings-home.html` | [`SettingPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingPage.kt) | `Screen.Setting` (`RouteActivity.kt`) | 直连；生产设置根页拥有外观、记忆、执行、TTS、扩展、权限、Provider、模型、搜索、WebMount、子代理、议会、小应用、小说、看板、备份、存储、文件和关于入口。HTML 自身 MAP 断点见下方。 |
| `settings-display.html` | [`SettingDisplayPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingDisplayPage.kt) | `Screen.SettingDisplay` (`RouteActivity.kt`) | 直连；主题、accent、启动入口、消息/代码显示、字体/字号和通知均有 settings 消费。 |
| `theme-library.html` | [`ThemeLibrarySection.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/ThemeLibrarySection.kt)，由 [`SettingDisplayPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingDisplayPage.kt) 挂载 | `Screen.SettingDisplay` 内主题库区块 | 嵌套；内置/导入主题、预览、应用、导出和删除有真实 `ThemePackageManager`，没有独立 route。 |
| `settings-memory.html` | [`SettingAgentMemoryPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentMemoryPage.kt)，`subpage = Overview` | `Screen.SettingAgentMemory` (`RouteActivity.kt`) | 直连；Soul、四个记忆子页入口和记忆预览接入 VM。HTML 文字/数量是样例。 |
| `memory-switches.html` | `SettingAgentMemoryPage.kt` 的 `MemoryRecallSubpage`，公开包装 [`SettingAgentMemoryPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentMemoryPage.kt) | `Screen.SettingAgentMemoryRecall` (`RouteActivity.kt`) | 直连/样例；核心/短期/长期/最近会话/时间提醒/选择性召回均有真实 settings 消费。 |
| `memory-maintenance.html` | `SettingAgentMemoryPage.kt` 的 `MemoryWorkerSubpage`，公开包装 `SettingAgentMemoryWorkerPage` (`:874-876`) | `Screen.SettingAgentMemoryWorker` (`RouteActivity.kt`) | 直连/样例；本地/LLM 整理、空闲运行、dream 计划/应用/清除有真实 VM/worker。 |
| `memory-context.html` | `SettingAgentMemoryPage.kt` 的 `MemoryCompactionSubpage`，公开包装 `SettingAgentMemoryCompactionPage` (`:879-881`) | `Screen.SettingAgentMemoryCompaction` (`RouteActivity.kt`) | 直连；自动压缩/仅提醒、触发阈值和保护轮数绑定 `contextCompaction`。 |
| `memory-library.html` | `SettingAgentMemoryPage.kt` 的 `MemoryLibrarySubpage`，公开包装 `SettingAgentMemoryLibraryPage` (`:884-886`) | `Screen.SettingAgentMemoryLibrary` (`RouteActivity.kt`) | 直连/样例；候选审核、核心/短期/长期记录、编辑/删除、事件和导入导出有真实 VM。 |
| `settings-runtime.html` | [`SettingAgentExecutionPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentExecutionPage.kt) | `Screen.SettingAgentExecution` (`RouteActivity.kt`) | 直连；操作预览、生成式 UI、tool loop、Live 状态/模式、刷新/节点上限、重试和后台保活均有 settings 消费。HTML 的 1.5 秒/120 节点等数值是样例。隐藏的 `SettingAgentRuntimeTasksPage` (`Screen.SettingAgentRuntimeTasks`) 是另一开发 harness，不要与本页混淆。 |
| `settings-tts.html` | [`SettingTtsPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingTtsPage.kt) | `Screen.SettingTts` (`RouteActivity.kt`) | 直连；系统 TTS 可用性、0.5x–2x 试听、停止和离页关闭有真实实现，页面明确不承诺聊天朗读/录音转写。 |
| `settings-sandbox.html` | [`SettingSandboxPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingSandboxPage.kt) + `SettingSshProfilesSection` (`SettingSandboxPage.kt`) | `Screen.SettingSandbox` (`RouteActivity.kt`) | 直连；Workspace 授权、Alpine/Android shell/Termux 运行时、并发/输出/安装超时和 SSH profile 有真实接线，HTML 的主机/指纹是样例。 |
| `settings-system-access.html` | [`SettingSystemAccessPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingSystemAccessPage.kt) | `Screen.SettingSystemAccess` (`RouteActivity.kt`) | 直连；运行时权限、无障碍/应用列表、外部文件 allowlist 和系统设置跳转有真实 broker/Store。 |
| `settings-permissions.html` | [`SettingAgentPermissionsPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentPermissionsPage.kt) | `Screen.SettingAgentPermissions` (`RouteActivity.kt`) | 直连；系统权限、Capability 入口和全局/高风险批准有真实 gate。HTML 中的能力/状态是样例；其两个详情行在 HTML 内未接导航，见下方。 |
| `settings-capability.html` | [`SettingCapabilityPermissionsPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingCapabilityPermissionsPage.kt) | `Screen.SettingCapabilityPermissions` (`RouteActivity.kt`) | 直连/样例；能力级 disabled/ask/auto 与最近审批审计有真实 VM；受 capability flag 控制。 |
| `settings-skills.html` | [`SettingAgentExtensionsPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentExtensionsPage.kt) | `Screen.SettingAgentExtensions` (`RouteActivity.kt`) | 直连；生产页拥有 Skills/Extensions/MCP/Cron/Slides 五个入口。HTML 五行自身没有 click wiring，见下方。Cron 归此 owner。 |
| `cron.html` | `SettingAgentExtensionsPage.kt` 的 [`SettingCronTasksPage`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentExtensionsPage.kt) | `Screen.SettingCronTasks` (`RouteActivity.kt`)，由 Agent Extensions 进入 | 直连/参数化；任务列表、启停、立即运行、详情、删除和打开所属聊天有 `AgentCronManager` 消费。HTML 任务名称/状态是样例。 |
| `settings-mcp.html` | [`SettingMcpPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingMcpPage.kt) | `Screen.SettingMcp` (`RouteActivity.kt`) | 直连/样例；SSE/Streamable HTTP、OAuth、导入/新建/编辑/刷新和能力批准有真实 manager/transaction。 |
| `settings-slides-font.html` | [`SettingSlidesFontPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingSlidesFontPage.kt) | `Screen.SettingSlidesFonts` (`RouteActivity.kt`) | 直连/样例；字体包状态、下载、来源/许可证和删除有真实 repository。 |
| `settings-search.html` | [`SettingSearchPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingSearchPage.kt) | `Screen.SettingSearch` (`RouteActivity.kt`) | 直连/样例；内置源、服务排序/启停、结果数量和推荐组合有真实 settings。 |
| `settings-search-editor.html` | [`SearchServiceEditorSheet.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingSearchServiceEditorSheet.kt)，由 `SettingSearchPage.kt-` 打开 | `Screen.SettingSearch` 内 sheet | 嵌套；服务类型、能力、API key、各服务选项、删除/取消/保存有真实消费，无独立 route。 |
| `settings-webmount.html` | [`SettingExperimentalWebMountPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingExperimentalWebMountPage.kt) | `Screen.SettingExperimentalWebMount` (`RouteActivity.kt`) | 直连/样例；全局/评估开关、slash 安装、站点恢复/新增/登录/OAuth/cookie 和活动任务卡有真实 manager/registry。HTML Notion/Wiki/BI 是样例站点。 |
| `settings-experimental.html` | [`SettingExperimentalPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingExperimentalPage.kt) | `Screen.SettingExperimental` (`RouteActivity.kt`) | 直连；生产页有 WebMount/iCloud/SubAgent/Today Board/MiniApp/Synara/ZCode 子入口。HTML 行自身没有 click wiring，见下方。 |
| `settings-icloud.html` | [`SettingExperimentalICloudPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingExperimentalICloudPage.kt) | `Screen.SettingExperimentalICloud` (`RouteActivity.kt`) | 直连/样例；Vault、区域登录 WebView、读写探测和状态/下一步有真实 manager。 |
| `settings-subagent.html` | [`SettingExperimentalSubAgentPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingExperimentalSubAgentPage.kt) | `Screen.SettingExperimentalSubAgent` (`RouteActivity.kt`) | 直连/样例；模式、并发/轮数/超时/预算、内置/动态角色和议会入口有真实 settings/Prompt 写入。 |
| `settings-storage.html` | [`SettingStoragePage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingStoragePage.kt) | `Screen.SettingStorage` (`RouteActivity.kt`) | 直连；分类占用、会话交换导入/导出和 dry-run 清理有真实 `StorageVM`/exchange handler。HTML 数字是样例。 |
| `settings-files.html` | [`SettingFilesPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingFilesPage.kt) | `Screen.SettingFiles` (`RouteActivity.kt`) | 直连；上传文件网格、预览和删除有真实 `FilesManager`。它不是 `chat-storage.html` 的等价页。 |

### Provider、模型和 OAuth

| HTML | 真实 Android 文件 / 组件 | 入口 | 结论 |
|---|---|---|---|
| `providers.html` | [`SettingProviderPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderPage.kt) | `Screen.SettingProvider` (`RouteActivity.kt`) | 直连/样例；Provider 聚合、筛选、JSON/QR/图片导入、自定义新增和动态列表有真实 settings。HTML 的 3 provider/12 model 是样例。 |
| `provider-config.html` | [`SettingProviderDetailPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderDetailPage.kt)，配置 tab → [`SettingProviderConfigPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderConfigPage.kt) | `Screen.SettingProviderDetail(providerId)` (`RouteActivity.kt`) | 嵌套/参数化；协议、鉴权、端点、启停、测试、保存、删除和 provider-specific OAuth 有真实消费，HTML OpenAI/key/URL 是样例。 |
| `provider-models.html` | `SettingProviderDetailPage.kt` → [`SettingProviderModelTabPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderModelTabPage.kt) | 同一 `Screen.SettingProviderDetail(providerId)` 的模型 tab | 嵌套/参数化；模型拉取/加载错误/重试、设为当前、删除、排序和添加有真实 provider catalog。HTML 模型列表是样例。 |
| `provider-model-edit.html` | [`ModelEditorSheet.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderModelSettingsForm.kt) + `ModelSettingsForm` (`:218`)，由 `SettingProviderModelTabPage.kt` 打开 | Provider detail 模型 tab 内 sheet | 嵌套；能力、上下文、默认参数、headers/bodies、provider override 和保存/取消有真实组件，无独立 route。HTML `gpt-5.6` 是样例。 |
| `models-prompts.html` | [`SettingModelPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingModelPage.kt) | `Screen.SettingModels` (`RouteActivity.kt`) | 直连/样例；聊天/辅助任务模型、跟随关系、每项参数和 group defaults 有真实 settings。 |
| `model-selector.html` | [`ModelSelector.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/components/ai/ModelList.kt)，设置页通过 `SettingModelPage.kt` 调用；provider tab 也复用 `ModelPickerSheet` | 无独立 `Screen`；由模型/Provider父页或聊天菜单打开 | 嵌套/参数化；真实列表按 provider、类型、鉴权和 registry 动态生成。HTML provider/model id 只是样例，不能作为可直接导航的无参页。 |
| `model-params.html` | [`ModelPromptSheet`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingModelPage.kt)（聊天模型及各辅助模型的参数/提示词 sheet） | 无独立 `Screen`；由 `SettingModelPage` 的参数按钮打开 | 嵌套；推理级别、系统提示词、重置和保存有真实 settings 消费。HTML `gpt-5.6` 与文案是样例。该叶子归 Provider/模型 owner，不应新增独立路由来复制状态。 |
| `settings-oauth.html` | [`AntigravityOAuthConsole.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/AntigravityOAuthConsole.kt)，由 [`SettingProviderConfigPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderConfigPage.kt) 在 Google provider 配置中挂载 | 无独立 `Screen`；`Screen.SettingProviderDetail` 的 Google auth 分支 | 嵌套；Antigravity token/status、重新登录、退出、固定 endpoint 和登录后模型刷新有真实 OAuth store/client。HTML 邮箱和 endpoint 是样例，不是可复制配置。 |

### Synara、WebMount 和 ZCode

| HTML | 真实 Android 文件 / 组件 | 入口 | 结论 |
|---|---|---|---|
| `synara-connect.html` | [`SynaraConnectPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/synara/SynaraConnectPage.kt) | `Screen.SynaraCompanion` (`RouteActivity.kt`) | 直连/样例；QR/手动 host/port/token、测试和进入 WebView 有真实 VM。HTML 连接字段是样例。 |
| `synara-workspace.html` | [`SynaraWorkspacePage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/synara/SynaraWorkspacePage.kt) | `Screen.SynaraWorkspace(host, port, token, useHttps)` (`RouteActivity.kt`) | 直连/参数化；生产页面是远端 Synara WebView 加载/错误/重试壳，HTML 的 threads/files/terminal 面板不是 Android 自有数据。 |
| `webmount-session.html` | [`WebMountSessionPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/webmount/WebMountSessionPage.kt) | `Screen.WebMountSession(sessionId, reopen)` (`RouteActivity.kt`) | 直连/参数化；真实页从 pooled WebView handle 只读观看，用户明确 takeover 后才取得 lease；HTML `article-mock` 只是远端网页样例，不能当作本地文章数据。 |
| `zcode.html` | [`ZCodePage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/zcode/ZCodePage.kt) | `Screen.ZCode` (`RouteActivity.kt`) | 直连；URL 归一化、持久化、扫描和打开有真实 store/QR。 |
| `zcode-session.html` | [`ZCodeSessionPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/zcode/ZCodeSessionPage.kt) | `Screen.ZCodeSession(url)` (`RouteActivity.kt`) | 直连/参数化；生产只是加载用户提供的 ZCode 移动 WebView，HTML 对话内容是远端页面样例。 |

## 需要主代理决策的具体缺口

### 生产 Android 接线

1. **Boot 已按平台能力适配。** 使用真实 Android starting window 的点阵品牌与静态 cursor；加载文案、闪烁及固定 62% 进度不作为伪造的运行状态加入应用。

2. **聊天存储页面和入口已补齐。** 新增 `SettingChatStoragePage`，根设置行进入 `Screen.SettingChatStorage`。展示真实 SQLite/Room 存储事实，导出和索引重建走已有真实服务，附件文件管理作为该页入口保留；未伪造 JSONL 格式或清理成功状态。

### 原型自身的导航断点（不等同于生产功能缺失）

3. `session-home.html` 有 `rail-sites`（网站），但 `session-home.html` 的 `MAP` 没有 `rail-sites`；生产对应 [`SessionHomePage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt) 的 `onWebMount` → `Screen.SettingExperimentalWebMount`。原型点击网站 rail 当前不会进入 `settings-webmount.html`。

4. `settings-home.html` 的 `MAP` 只接了外观、显示、技能、Provider、模型、议会、小应用、小说和深读。以下实际存在的 row 没有 handler：`row-soul-memory`、`row-runtime`、`row-tts`、`row-permissions`、`row-search-service`、`row-webmount`、`row-subagents`、`row-sync`、`row-storage`、`row-chat-storage`；生产页还有 About 行（[`SettingPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingPage.kt)），但该 HTML 根页没有对应 row。其 `row-council` 还错误指向 `council-room.html`；生产设置入口是 `Screen.SettingExperimentalModelCouncil`（[`SettingPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingPage.kt)）。这些是原型可达性/目标页错误，不能作为 Android 路由缺失来处理。

5. `settings-memory.html` 的 `row-auto-organize`、`row-context` 没有 click wiring；生产总览确实在 [`SettingAgentMemoryPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentMemoryPage.kt) 分别通往 Worker/Compaction。`row-memory-toggles` 与 `row-memory-library` 已有原型链接。

6. `settings-skills.html` 的 `row-skill`、`row-extensions`、`row-mcp`、`row-cron`、`row-slides-fonts` 都没有原型 click handler；生产对应入口在 [`SettingAgentExtensionsPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentExtensionsPage.kt)。Cron 仍归该页 owner。

7. `settings-experimental.html` 的 WebMount、iCloud、SubAgent、Today Board、MiniApp、Synara、ZCode、模型议会行均没有原型 click handler；生产对应调用集中在 [`SettingExperimentalPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingExperimentalPage.kt)，且生产已将模型议会从此页移到设置根页/子代理高级入口（源码注释 `SettingExperimentalPage.kt`）。该 HTML 的 `row-council` 不能直接映射为此页的生产行。

8. `settings-permissions.html` 的 `row-system-permissions`、`row-capability-permissions` 没有原型 click handler；生产分别由 [`SettingAgentPermissionsPage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentPermissionsPage.kt) 进入 `Screen.SettingSystemAccess` / `Screen.SettingCapabilityPermissions`。

9. `novel-chapters.html` 把“设定” tab 指向 `novel-settings.html`，但该文件不在 81 个 HTML 清单中，目录内也只有这一处引用。生产没有缺少设置 tab：同一 `Screen.NovelMarkdown` 页面在 [`NovelMarkdownWorkspacePage.kt`](/Users/arquiel/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/novel/NovelMarkdownWorkspacePage.kt) 内置 `MarkdownWorkspaceCatalog`。原型需决定改为页内设定或补文件，不能留下死链接。

## 原型与实际能力的适配

- Skill 添加保持现有 GitHub/手动两条能力；没有新增缺少导入契约的 ZIP 按钮。
- 模型编辑展示真实上下文长度与能力；未把不存在的最大输出元数据写成固定值。
- 原型示例小应用、远程工作台内容保持由实际 WebView 和连接加载；不替换为演示计算器或假运行结果。

## 仅视觉样例的叶子

以下文件有生产组件映射，但单独打开时的内容/动作不能证明真实数据接线：

- 需要实体参数的 `chat.html`、`chat-input.html`、`chat-messages.html`、`council-room.html`、`deepread-reader.html`、`miniapp-runner.html`、`novel-*`、`provider-*`、`model-*`、`settings-oauth.html`、`webmount-session.html`、`synara-workspace.html`、`zcode-session.html`。真实入口分别需要 conversation/topic/project/provider/app/session/URL 等稳定 ID 或远端连接。
- 动态列表/统计的固定内容：`session-home.html` 会话与 continue、`board.html`/`deepread-history.html` 热点和历史、`history.html`/`favorites.html`/`search.html`、`profile.html`/`stats.html`、`memory-*`、`backup.html`、`log.html`、`mini-apps.html` 和 `providers.html`。这些只应作为布局/空态/状态样本，生产应继续从 VM、Room、repository、Keystore、WebView 或远端服务读取。
- `board-topic-sheet.html`、`council-members.html`、`novel-editor.html`、`novel-ghostwrite.html`、`miniapp-editor.html`、`quick-message-editor.html`、`skill-add.html`、`theme-library.html`、`model-selector.html`、`model-params.html`、`settings-search-editor.html` 是父页内 sheet/dialog/tab 的独立视觉稿；其没有独立 Android route 不构成缺失。尤其不要为了让样例 URL/模型/会话可点击而复制一套静态数据或绕过现有权限、持久化和参数 gate。

首页保持用户指定的上传图例外；本报告没有把它列为重绘或替换项。 

# AmberAgent Android → 鸿蒙候选页面与路由盘点

核对日期：2026-09-13
盘点范围：当前 Android 工作树；只读当前仓库，没有读取兄弟仓库或鸿蒙远端仓库。
最终基线：用户说明 Android 设计即将提交，最终提交 SHA 尚未提供；本文件是迁移规划候选清单，提交后需按该 SHA 重新核对。
鸿蒙状态：未在本机核对鸿蒙仓库或设备。用户提供的候选仓库为 amber-harmony-preview，待公司电脑 AI 在远端确认其实际仓库、分支和 SHA。

本附录是候选源码索引。任务范围、样板顺序和最小充分验收以 `START_TASK.md` 和 `README.md` 为准。平台依赖用于检查鸿蒙已有能力及记录缺口，不要求在 UI 重构中另建整套平台框架。本文的行号将在 Android 最终 SHA 固定后复核。

## 计数口径

真实导航入口是 app/src/main/java/app/amber/agent/RouteActivity.kt：

- Screen 声明位于 1006-1256 行；当前共 69 个 Screen 类型。
- NavDisplay entry 位于 625-942 行；当前共 69 个 entry，二者一一对应。
- 其中 55 个是无参数 data object，14 个是带参数的 data class。参数化路由必须保留实体 ID、远端连接或 URL 契约。
- 分组核对：10（首页/聊天）+ 6（看板/深读）+ 2（小说）+ 9（扩展/小应用）+ 32（设置）+ 7（远端/系统壳）+ 3（诊断）= 69。
- Navigator 的 push、pop、clearAndNavigate、returnToSessionHome 位于 app/src/main/java/app/amber/feature/ui/context/NavContext.kt:7-45。Chat 从首页进入时保留 SessionHome；clearAndNavigate(Chat) 会补 SessionHome。
- 设计覆盖文档记录了 81 个 HTML 输入（docs/verification/2026-09-13-redesign-coverage.md），其中包含 tab、sheet、dialog、输入态和样例数据；它不等于 81 个 Android route，也不等于已完成截图验收。本清单不把它当作截图证据。
- 下表的 route 数只统计 Screen/entry；同一父页内的 tab、sheet、dialog、系统 Activity 单列，不重复计入 69。

## 真实 Screen 分组表

### A. 首页、会话、聊天、个人与辅助（10 个 route）

| Screen | Android owner（当前工作树） | 当前可达关系与迁移要点 |
|---|---|---|
| SessionHome | app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:161；entry RouteActivity.kt:662-665 | 冷启动 HOME 模式；首页头部进入 Setting/Profile，展开搜索进入 MessageSearch，功能 rail 进入 TodayBoard/MiniAppList/NovelProjects/SettingExperimentalWebMount，并可创建 CouncilRoom；真实调用点 SessionHomePage.kt:327-383。 |
| Chat(id, text?, files[], nodeId?, messageId?, toolCallId?) | app/src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt:178-185；entry RouteActivity.kt:625-640 | 首页会话行/新会话、继续卡、通知 deep link、快捷方式、分享后的流程均进入；参数承载会话、预填文本、附件和消息/工具焦点。 |
| CouncilRoom(conversationId) | app/src/main/java/app/amber/feature/ui/pages/councilroom/CouncilRoomPage.kt:98；entry RouteActivity.kt:645-647 | 首页创建议会或 ContinueCandidate 进入；开发入口 CouncilRoomDevEntry.kt:88；conversationId 必须绑定已有会话。 |
| ShareHandler(text, streamUri?, streamUris[], deliveryId) | app/src/main/java/app/amber/feature/ui/pages/share/handler/ShareHandlerPage.kt:25；entry RouteActivity.kt:649-656 | 不是普通页面入口；RouteActivity 的 SEND、SEND_MULTIPLE、PROCESS_TEXT 处理在 RouteActivity.kt:314-339，Manifest intent-filter 在 app/src/main/AndroidManifest.xml:132-152。 |
| History | app/src/main/java/app/amber/feature/ui/pages/history/HistoryPage.kt:80；entry RouteActivity.kt:658-660 | 当前源码中未找到除 RouteActivity 注册外的 in-app caller；可能是旧入口/隐藏入口，最终 SHA 需决定保留或下线，不能直接假定鸿蒙不需要。 |
| Profile | app/src/main/java/app/amber/feature/ui/pages/profile/ProfilePage.kt:94；entry RouteActivity.kt:666-668 | 首页头像、设置顶栏头像进入；页面内直接展示统计卡和 heatmap，当前未找到 Screen.Stats 的调用。 |
| Favorite | app/src/main/java/app/amber/feature/ui/pages/favorite/FavoritePage.kt:68；entry RouteActivity.kt:670-672 | Extensions 页面进入；收藏项可定位回会话/消息。 |
| LiveCompanion | app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionPage.kt:82；entry RouteActivity.kt:674-676 | 当前源码中未找到 in-app caller；有 LiveCompanionVM 和真实配置/结果 UI，需按最终 SHA 核实是否由外部入口或隐藏入口使用。 |
| MessageSearch | app/src/main/java/app/amber/feature/ui/pages/search/SearchPage.kt:71；entry RouteActivity.kt:935-937 | 首页搜索展开、History 搜索入口进入；真实 FTS、筛选、分页、索引重建和错误态。 |
| Stats | app/src/main/java/app/amber/feature/ui/pages/stats/StatsPage.kt:70；entry RouteActivity.kt:939-941 | 当前源码中未找到 Screen.Stats caller；Profile 自带统计卡，需避免鸿蒙重复实现或遗漏独立统计页。 |

首页关键真实路径：SessionHomePage.kt:332-383、440-495、1253-1276、1292-1335。继续卡可能进入 CouncilRoom、DeepRead、Chat、MiniAppRunner 或 NovelMarkdown；不能把继续卡当成固定样例链接。

### B. 今日看板与深度阅读（6 个 route）

| Screen | Android owner | 当前可达关系与嵌套表面 |
|---|---|---|
| TodayBoard | app/src/main/java/app/amber/feature/ui/pages/board/BoardPage.kt:95；entry RouteActivity.kt:841-843 | 首页/设置/BoardNotifier 进入；看板下拉刷新、来源聚合、热点点击。 |
| DeepRead(topicId, title, sourceUrl?, forceRegenerate, fromHistory) | app/src/main/java/app/amber/feature/ui/pages/board/DeepReadScreen.kt:133；entry RouteActivity.kt:845-853 | TodayBoard topic action、DeepReadHistory、通知和 AppEvent.OpenDeepRead 进入；必须保留 topic/cache/history 状态。 |
| DeepReadHistory | app/src/main/java/app/amber/feature/ui/pages/board/DeepReadHistoryPage.kt:59；entry RouteActivity.kt:855-857 | TodayBoard 顶栏历史进入；点击带 topicId/title/sourceUrl/fromHistory，调用点 DeepReadHistoryPage.kt:108-121。 |
| SettingTodayBoard | app/src/main/java/app/amber/feature/ui/pages/board/SettingTodayBoardPage.kt:111；entry RouteActivity.kt:859-861 | TodayBoard 设置按钮或设置根页进入；paneRoute=null 时展示总览。 |
| SettingTodayBoardDetail(pane) | 同一 SettingTodayBoardPage.kt:111；entry RouteActivity.kt:863-865 | pane 由 SettingTodayBoardPage.kt:280-319 产生，当前有 general、hot_list、review 三类；不是三个独立 Screen。 |
| DeepReadTemplateWorkbench | app/src/main/java/app/amber/feature/ui/pages/board/DeepReadTemplateWorkbenchPage.kt:81；entry RouteActivity.kt:867-869 | SettingTodayBoard 的模板区进入；生成/修订/验证/预览/保存，带未保存退出保护。 |

看板/深读嵌套表面：

- TodayBoard 内 HotListActionSheet：BoardPage.kt:226-248、私有 composable 在 257-345；动作是深读、重新生成、打开原文、分享。
- SettingTodayBoardDetail 的来源区：HotListSourceSettings.kt:55，内含内置来源、自定义来源、NewsNowPresetDialog（:315）和 CustomHotListSourceDialog（:379）。
- 同一 pane 的模板区：DeepReadTemplateSettings.kt:54，预览对话框在 :300。
- DeepReadTemplateWorkbench 的 SourcePanel、SaveTemplateDialog（:550）和退出确认对话框（主函数 :321-378）。
- DeepReadScreen 的首用确认、加载、分节生成/失败/重试/过期和纸面阅读分支在 DeepReadScreen.kt:230-488；这些是同一 route 的状态，不应拆成多条静态页面。

### C. 小说与模型议会（2 个 route + 议会页已计入 A）

| Screen | Android owner | 当前可达关系与嵌套表面 |
|---|---|---|
| NovelProjects | app/src/main/java/app/amber/feature/ui/pages/novel/NovelProjectsPage.kt:93；entry RouteActivity.kt:875-877 | 首页/设置进入；项目真实来自 NovelProjectsViewModel，支持创建、导入、删除、重命名、ZIP/书籍导出。 |
| NovelMarkdown(projectId, branchSlug?, jobId?) | app/src/main/java/app/amber/feature/ui/pages/novel/NovelMarkdownWorkspacePage.kt:130-137；entry RouteActivity.kt:879-885 | 项目卡、ContinueCandidate、NovelWorkspaceNotificationRoute 进入；projectId 必须有效，branch/job 用于恢复焦点和批次。 |

NovelMarkdown 内部表面（不增加 route）：

- 三个 tab 在 NovelMarkdownWorkspacePage.kt:457-543：创作、正文、设定；实现分别是 MarkdownWorkspaceChat（:1876）、MarkdownWorkspaceManuscript（:2710）、MarkdownWorkspaceCatalog（:3218）。
- 创作 tab：MarkdownGhostwriteSheet（:685）、CharacterProposalDialog（:2307），以及草稿/提案/流式状态。
- 正文 tab：章节目录、章节读取、重写、批量一致性检查；MarkdownChapterEditor（:2561）打开编辑态。
- 顶栏分支 chip 打开 BranchSheet（:2987），新建分支打开 NewBranchDialog（:3139）；活跃批次时切换/新建受 branch lock 约束。
- 批量润色打开 PolishBatchSheet（:1302）；WorkspaceFileEditor 在 :3608。
- 原型 novel-chapters.html 的“设定”曾指向目录中不存在的 novel-settings.html；生产设置 tab 已在同一 NovelMarkdown 内。迁移时不要复制一个死链接或误造独立 route。
- CouncilRoom 页面 owner 为 CouncilRoomPage.kt:98，成员 sheet 为 CouncilMembersSheet.kt:67；设置页为 SettingExperimentalModelCouncilPage.kt:61。议会设置不是 CouncilRoom 的成员 sheet。

### D. 扩展、技能与小应用（9 个 route）

| Screen | Android owner | 当前可达关系与嵌套表面 |
|---|---|---|
| Extensions | app/src/main/java/app/amber/feature/ui/pages/extensions/ExtensionsPage.kt:52；entry RouteActivity.kt:915-917 | 当前源码未找到 in-app caller；页面提供 QuickMessages、Favorite、Prompts 三个入口，可能是旧扩展总页。 |
| QuickMessages | QuickMessagesPage.kt:74；entry RouteActivity.kt:919-921 | Extensions 进入；EditQuickMessageDialog 在 QuickMessagesPage.kt:141-163、定义 :376，删除使用通用 ConfirmDialog。 |
| Prompts | PromptPage.kt:107；entry RouteActivity.kt:923-925 | Extensions 进入；两个 tab 为模式注入和世界书，PromptPage.kt:131-149；ModeInjectionEditSheet :527、LorebookEditSheet :938、RegexInjectionEditDialog :1135。 |
| Skills | SkillsPage.kt:98；entry RouteActivity.kt:927-929 | Agent Extensions 进入；列表、启停、删除、优化跳 Chat；AddSkillDialog :649、ImportSkillDialog :822。 |
| SkillDetail(skillName) | SkillDetailPage.kt:80；entry RouteActivity.kt:931-933 | Skills 行进入；技能文件树、MCP 配置/预览；EditFileDialog :481、AddFileDialog :545。 |
| MiniAppList | MiniAppListPage.kt:50；entry RouteActivity.kt:871-873 | 首页、设置、Chat 消息中的 mini app 卡进入；列表真实来自 MiniAppRepository。 |
| MiniAppRunner(appId) | MiniAppRunnerPage.kt:96；entry RouteActivity.kt:887-889 | MiniAppList、Chat 工具结果、首页 Continue 进入；运行内容是对应实体 WebView，不能移植原型中的固定计算器。 |
| MiniAppSettings | MiniAppSettingsPage.kt:60；entry RouteActivity.kt:891-893 | MiniAppList/实验设置进入；设置分组卡。 |
| MiniAppSettingsDetail(group) | 同一 MiniAppSettingsPage.kt:60；entry RouteActivity.kt:895-897 | group 当前为 common、host_ai、advanced，产生调用点 MiniAppSettingsPage.kt:131-151；是同一页的参数化详情。 |

MiniAppList 内 MiniAppRenameDialog、MiniAppDeleteDialog、MiniAppVersionHistoryDialog 位于 MiniAppManagementDialogs.kt:41、99、156；源码查看/编辑/校验/预览/未保存退出保护为 MiniAppSourceEditor.kt:110。以上均为父页对话框或 WebView 内容，不单独计 route。

### E. 设置体系（32 个 route）

#### E1. 设置根、外观、运行与扩展（8 个）

| Screen | Android owner | 入口/内容 |
|---|---|---|
| Setting | SettingPage.kt:83；entry RouteActivity.kt:701-703 | 首页设置齿轮进入。根页实际行在 SettingPage.kt:138-360，包含外观、显示、记忆、执行、TTS、扩展、权限、Provider、模型、搜索、WebMount、子代理、议会、小应用、小说、深读、iCloud、Synara、ZCode、备份、存储、聊天存储、关于。 |
| SettingAppearance | 当前新增 app/src/main/java/app/amber/feature/ui/pages/setting/SettingAppearancePage.kt:48；entry RouteActivity.kt:724-726 | Setting 根页“外观”行，当前为工作树新增文件，最终 SHA 必须复核。 |
| SettingDisplay | SettingDisplayPage.kt:112；entry RouteActivity.kt:720-722 | 主题、accent、启动入口、消息/代码显示、字体/字号、通知；ThemeLibrarySection.kt:66 是页内主题库。 |
| SettingAbout | SettingAboutPage.kt:72；entry RouteActivity.kt:741-743 | Setting 根页关于行；长按进入 Debug，调用 SettingAboutPage.kt:176。 |
| SettingAgentExecution | SettingAgentExecutionPage.kt:48；entry RouteActivity.kt:781-783 | Agent 运行时设置；操作预览、生成式 UI、tool loop、Live、刷新/节点上限、重试和后台保活；Sandbox 入口在 SettingAgentExecutionPage.kt:80-92。 |
| SettingTts | SettingTtsPage.kt:67；entry RouteActivity.kt:785-787 | 系统 TTS 可用性、速度试听、停止和离页关闭；不应扩展成录音转写承诺。 |
| SettingAgentExtensions | SettingAgentExtensionsPage.kt:64；entry RouteActivity.kt:765-767 | Skills、MCP、Cron、Slides Fonts 四个生产入口在 :86-117；RuntimeTasks 刻意隐藏。 |
| SettingSlidesFonts | SettingSlidesFontPage.kt:63；entry RouteActivity.kt:769-771 | Agent Extensions 进入；字体包状态、下载、来源/许可证和删除。 |
| SettingCronTasks | SettingAgentExtensionsPage.kt:317；entry RouteActivity.kt:773-775 | Agent Extensions 进入；任务列表、启停、立即运行、详情、删除和打开所属 Chat。 |
| SettingAgentRuntimeTasks | SettingAgentExtensionsPage.kt:125；entry RouteActivity.kt:777-779 | 开发/测试 harness（审批原因、并发批次、能力快照等）；当前源码未找到 caller，保留给隐藏手势/开发版决策，不作为普通鸿蒙用户页默认暴露。 |

#### E2. 记忆（5 个 route，同一 owner）

| Screen | Android owner | 内部内容 |
|---|---|---|
| SettingAgentMemory | SettingAgentMemoryPage.kt:106-108；entry RouteActivity.kt:745-747 | Overview：Soul、四个记忆子页入口、摘要/预览。 |
| SettingAgentMemoryRecall | 同一文件公开包装 :869-871 | 核心/短期/长期/最近会话/时间提醒/选择性召回开关；实际内容 MemoryRecallSubpage :1050。 |
| SettingAgentMemoryWorker | 同一文件公开包装 :874-876 | 本地/LLM 整理、空闲运行、dream 计划/应用/清除；MemoryWorkerSubpage :1129。 |
| SettingAgentMemoryCompaction | 同一文件公开包装 :879-881 | 自动压缩/仅提醒、阈值、保护轮数；MemoryCompactionSubpage :1240。 |
| SettingAgentMemoryLibrary | 同一文件公开包装 :884-886 | 候选审核、核心/短期/长期记录、编辑/删除、事件、导入导出；MemoryLibrarySubpage :1355。 |

记忆嵌套对话框：新增/编辑 AlertDialog 在 SettingAgentMemoryPage.kt:216-316；删除确认 :434-502；冲突解决 MemoryConflictDialog :543；信息、导入导出和事件日志对话框在 MemoryLibrarySubpage :1373-1486。该页有真实 revision/CAS 冲突处理，迁移时不能只做列表视觉。

#### E3. 权限与系统访问（4 个 route）

| Screen | Android owner | 入口/门控 |
|---|---|---|
| SettingAgentPermissions | SettingAgentPermissionsPage.kt:47；entry RouteActivity.kt:789-791 | Setting 根页权限行；系统访问在 :107-116，Capability 入口在 :117-124，仅 capability_permissions flag 开启时显示；高风险自动批准确认对话框 :59-87。 |
| SettingCapabilityPermissions | SettingCapabilityPermissionsPage.kt:58；entry RouteActivity.kt:793-795 | capability 级 disabled/ask/auto 和最近审批审计；具体能力受 flag/permission gate 控制。 |
| SettingSystemAccess | SettingSystemAccessPage.kt:66；entry RouteActivity.kt:899-901 | runtime permissions、special access、无障碍/应用列表、外部文件 allowlist；请求/跳系统设置真实调用在 :80-118。 |
| SettingSandbox | SettingSandboxPage.kt:63；entry RouteActivity.kt:817-819 | Workspace 授权、Alpine/Android shell/Termux 运行时、并发/输出/安装超时和 SSH profile；SettingAgentExecution 进入。 |

#### E4. Provider、模型、搜索和 MCP（5 个 route）

| Screen | Android owner | 内部 tab/sheet |
|---|---|---|
| SettingProvider | SettingProviderPage.kt:100；entry RouteActivity.kt:728-730 | Provider 聚合/筛选、导入 QR/图片/JSON 文件、自定义新增；ProviderImportDialog 在 :652-711，ProviderEditorSheet :714-777。Provider 实体点击进入 Detail，调用 :199-231。 |
| SettingProviderDetail(providerId) | SettingProviderDetailPage.kt:61；entry RouteActivity.kt:732-735 | providerId 绑定实体；两 tab 配置/模型，tab 内容在 :182-239。配置 owner SettingProviderConfigPage.kt:122，模型 owner SettingProviderModelTabPage.kt:90。 |
| SettingModels | SettingModelPage.kt:124；entry RouteActivity.kt:737-739 | 聊天和辅助任务模型、参数/提示词及分组默认值；页面 section 在 :147-186。ModelPromptSheet :963、ImagePromptInjectionSheet :309、ModelGroupSessionDefaultsSheet :1105。 |
| SettingSearch | SettingSearchPage.kt:37；entry RouteActivity.kt:797-799 | 内置/配置搜索源、排序/启停、结果数量和推荐组合；SearchServiceEditorSheet.kt:49 由父页打开。 |
| SettingMcp | SettingMcpPage.kt:129；entry RouteActivity.kt:801-803 | SSE/Streamable HTTP、OAuth、导入/新建/编辑/刷新、工具能力批准；McpServerConfigModal :533、McpImportModal :1073。 |

ProviderDetail 的模型列表并非独立 Screen：ProviderModelRow 与 ModelPickerSheet 位于 SettingProviderModelTabPage.kt:496、622；提供商模型编辑器 ModelEditorSheet 位于 SettingProviderModelSettingsForm.kt:123。Codex/Gemini/Grok/Antigravity OAuth 内容挂在 SettingProviderConfigPage.kt:482-748、1083-1458；AntigravityOAuthConsole.kt:63 是配置页内真实 OAuth 内容，不复制为无参 settings-oauth route。

#### E5. 数据、实验和高级能力（10 个 route）

| Screen | Android owner | 入口/内容 |
|---|---|---|
| SettingFiles | SettingFilesPage.kt:65；entry RouteActivity.kt:805-807 | 上传文件网格、预览、删除；与 ChatStorage 不是同一个页面。 |
| SettingChatStorage | SettingChatStoragePage.kt:69；entry RouteActivity.kt:809-811 | SQLite/Room 存储事实、会话/消息数量、容量、交换导出、索引维护、附件管理入口；ChatStorage 页进入 Files 的调用在 :259。 |
| SettingStorage | SettingStoragePage.kt:69；entry RouteActivity.kt:813-815 | 分类占用、会话交换导入/导出、dry-run 清理；导入/预览、清理确认/结果对话框在 :318、544、617。 |
| SettingExperimental | SettingExperimentalPage.kt:60；entry RouteActivity.kt:821-823 | 当前源码未找到外部 caller；页面内部仍列 WebMount/iCloud/SubAgent/TodayBoard/MiniApp/Synara/ZCode，调用在 :71-122。生产 Setting 根页直接暴露这些子入口，迁移时不要重复造两套树。 |
| SettingExperimentalICloud | SettingExperimentalICloudPage.kt:49；entry RouteActivity.kt:825-827 | Vault 路径、全球/中国登录、读写探测、状态/下一步；ICloudLoginDialog :211。 |
| SettingExperimentalSubAgent | SettingExperimentalSubAgentPage.kt:72；entry RouteActivity.kt:829-831 | 模式、并发/轮数/超时/预算、内置/动态角色；Model Council 配置入口 :417-424。 |
| SettingExperimentalModelCouncil | SettingExperimentalModelCouncilPage.kt:61；entry RouteActivity.kt:833-835 | 席位、外部 CLI、主持模型、轮数/超时/预算；席位编辑器 :442。Setting 根页、SubAgent 页和 CouncilRoom 均可进入。 |
| SettingExperimentalWebMount | SettingExperimentalWebMountPage.kt:105；entry RouteActivity.kt:837-839 | 全局/评估开关、网站恢复/新增/登录、OAuth/cookie、slash 安装和活动任务卡；WebMountLoginDialog :819、OAuthEditDialog :1150、AddCustomSiteDialog :1212。 |
| SettingSystemAccess | 已在 E3 计数 | 这里只提醒其入口属于高级权限，不能因名字相似重复计数。 |
| SettingAgentPermissions | 已在 E3 计数 | capability flag 关闭时详情行不可见。 |

E5 去重说明：设置总计 32 个 route，包含 E1 10 + E2 5 + E3 4 + E4 5 + E5 新增 8 个（Files、ChatStorage、Storage、Experimental、ICloud、SubAgent、ModelCouncil、WebMount）；E5 表中的 SystemAccess、AgentPermissions 已在 E3 计过，不重复计数。完整 32 个名称以本节各表和 RouteActivity.kt:1077-1180 为准。

### F. Android/远端壳与数据同步（7 个 route）

| Screen | Android owner | 平台/参数风险 |
|---|---|---|
| Backup | BackupPage.kt:252；entry RouteActivity.kt:705-707 | Google Drive、WebDAV、本地备份/快照、加密导出/恢复、冲突/口令/验证对话框；真实 ActivityResult/OAuth 和持久状态在 BackupVM.kt:154-1225。 |
| SynaraCompanion | SynaraConnectPage.kt:68；entry RouteActivity.kt:678-680 | LAN Mac-hosted Synara 连接配置、QR/手动 host/port/token，测试后进入 Workspace。 |
| SynaraWorkspace(host, port, token, useHttps) | SynaraWorkspacePage.kt:115；entry RouteActivity.kt:682-691 | 远端 WebView 工作台壳；不把远端 threads/files/terminal 样例当本地数据。 |
| ZCode | ZCodePage.kt:60；entry RouteActivity.kt:693-695 | 输入/持久化/扫描 ZCode 分享 URL，成功后进入 Session。 |
| ZCodeSession(url) | ZCodeSessionPage.kt:18；entry RouteActivity.kt:697-699 | 全屏远端移动 WebView；内容由 URL 站点提供。 |
| WebView(url?, content?) | WebViewPage.kt:50；entry RouteActivity.kt:709-711 | Markdown 代码块/HTML 预览等通用内置 WebView；HighlightCodeBlock.kt:487 进入 content 模式。 |
| WebMountSession(sessionId, reopen) | WebMountSessionPage.kt:88；entry RouteActivity.kt:713-718 | WebMount 登录后会话的只读观看/takeover 壳；sessionId/reopen 不能伪造，必须绑定 pooled WebView handle 和 lease。 |

### G. 诊断与开发（3 个 route）

| Screen | Android owner | 当前可达关系与迁移要点 |
|---|---|---|
| Developer | DeveloperPage.kt:38；entry RouteActivity.kt:903-905 | SettingPage 在 developerMode 开启时显示入口，调用 SettingPage.kt:96-104；日志/生成记录来自 DeveloperVM。 |
| Debug | DebugPage.kt:65；entry RouteActivity.kt:907-909 | SettingAbout 长按进入，调用 SettingAboutPage.kt:176；flags、token-fit、launch count 等开发诊断。 |
| Log | LogPage.kt:63；entry RouteActivity.kt:911-913 | 当前源码未找到 in-app caller；请求/文本日志页仍注册，最终 SHA 决定隐藏/保留。 |

## 设计叶子归属：避免把 81 份 HTML 当 81 页

当前设计输入可按真实 owner 合并：

- 直接 route 页面：session-home、chat、council-room、about、backup、board、board-settings、board-workbench、deepread-history、deepread-reader、novel-projects、novel-workspace、extensions、prompts、skills、mini-apps、miniapp-runner、miniapp-settings、settings-home、settings-display、settings-memory、memory-switches、memory-maintenance、memory-context、memory-library、settings-runtime、settings-tts、settings-sandbox、settings-system-access、settings-permissions、settings-capability、settings-skills、cron、settings-mcp、settings-slides-font、settings-search、settings-webmount、settings-experimental、settings-icloud、settings-subagent、settings-council、settings-storage、settings-files、providers、models-prompts、synara-connect、synara-workspace、zcode、zcode-session、profile、favorites、history、search、stats、live-companion、log、debug、chat-storage。
- 父页嵌套叶子：chat-input、chat-messages、board-topic-sheet、board-sources、board-template、council-members、novel-chapters、novel-editor、novel-ghostwrite、quick-message-editor、skill-add、miniapp-editor、theme-library、settings-search-editor、provider-config、provider-models、provider-model-edit、model-selector、model-params、settings-oauth、settings/permission actions、memory dialogs、backup dialogs、WebMount login/OAuth/cookie dialogs。
- 设计输入中 novel-settings.html 是目录内死链接，不能当生产缺失 route。
- 原型固定的会话、项目、模型、远程站点、统计和生成结果只能作为布局/状态样本；真实 Android 页面从 Room/VM/repository/Keystore/WebView/远端服务读取。

## Android 专属平台能力（迁移时单独建适配项）

以下不是普通 Compose 页面，应在鸿蒙移交表中单列 platform adapter、权限和验收证据：

1. **Activity/Intent 与系统入口**

   - RouteActivity 是 ComponentActivity，负责 edge-to-edge、系统栏样式、崩溃安全模式、volume key listener 和 NavDisplay；RouteActivity.kt:198-311、RouteActivity.kt:217-227。
   - 主桌面启动、分享文本/多文件、PROCESS_TEXT 在 Manifest:111-169 和 RouteActivity.kt:314-339；鸿蒙需分别映射桌面 Ability、系统分享/文本处理入口。
   - 通知、任务和快捷方式可把 Chat、DeepRead、Memory、Novel workspace 带参数拉起；RouteActivity.kt:347-383、493-539。动态 launcher shortcut 发布在 RouteActivity.kt:272-281、DynamicShortcutPublisher。
   - 自定义 amberagent OAuth/MCP OAuth/WebMount inline login 回调分别在 Manifest:154-219；这些是 deep link/Ability 回调，不是普通页面。

2. **运行时权限和系统特殊访问**

   - Manifest 声明了相机、通知、媒体、位置、录音、蓝牙/Wi-Fi、联系人/短信/电话/日历、Health Connect、精确闹钟、电池优化、悬浮窗、PACKAGE_USAGE_STATS、Termux 等权限（app/src/main/AndroidManifest.xml:5-61）。
   - SettingSystemAccessPage.kt:80-118 通过 ActivityResultContracts.RequestMultiplePermissions 和特殊设置 Intent 真实请求；SettingAgentPermissionsPage.kt:51-87 有高风险自动批准确认，Capability 页受 flag 门控。
   - 无障碍服务、媒体投影录屏、通知监听、应用列表查询和系统弹窗服务在 Manifest:257-281；鸿蒙要逐项确认权限模型与可替代能力，不能只迁移开关文案。

3. **后台执行、通知和生命周期**

   - AgentGenerationForegroundService、WorkManager foreground service、ScreenCaptureService 和 Boot/Time/Timezone receiver 在 Manifest:221-281、241-272；需核对鸿蒙已有后台任务/通知/恢复能力，缺失能力另列依赖，不在本次 UI 任务中自动重写后台架构。
   - ChatService、Cron、Memory dream、Today Board、DeepRead、Novel workspace 会生成通知并带回具体 route/实体；通知点击只算完成到正确落点，不等于后台任务完成。
   - RouteActivity 对通知 runId 做 conversation ownership/freshness 验证（RouteActivity.kt:465-490）；鸿蒙移植必须保留同样的实体/运行归属校验。

4. **SAF、文件 URI、媒体与输入**

   - ChatInput 的相机、裁剪、图片/视频/音频/文档选择和 FileProvider 临时 URI 在 ChatInput.kt:702-877；系统 IME/adjustResize 由 Manifest:121-126。
   - NovelProjectsPage 使用 OpenDocument/CreateDocument 读取导入、写 TXT/Markdown/EPUB/ZIP（NovelProjectsPage.kt:121-237）；Backup、Provider 文件/QR 导入也依赖 ContentResolver/URI。
   - FileProvider 在 Manifest:283-291；鸿蒙需定义 URI 权限、临时文件、媒体预览和分享生命周期。

5. **Android WebView、Cookie、浏览器与安全窗口**

   - 通用 WebView、MiniAppRunner、SynaraWorkspace、ZCodeSession、iCloud 登录、WebMount login/session 和 DeepRead template preview 都是 Android WebView 或 AndroidView 内容。
   - WebMount 登录对话框启用 SecureFlagPolicy.SecureOn，并处理 cookie 复核、OAuth、导航阻断、renderer gone、takeover lease（SettingExperimentalWebMountPage.kt:819-1005；WebMountSessionPage.kt:88）。
   - OAuth 还会尝试 Custom Tabs/ACTION_VIEW，回调通过 amberagent scheme；鸿蒙需核对已有浏览器/安全存储/cookie/回调实现的对应关系；若本次改动触及这些行为，再做相应运行验证。

6. **本地持久化与安全凭据**

   - ChatStorage/Storage/Memory/Backup/Provider 页面都展示或修改 Room/SQLite、文件目录、交换包、索引和恢复状态；页面样例数字不能迁移为常量。
   - Provider OAuth、WebMount OAuth/cookie、Google Drive auth 和会话 token 需要 Android Keystore/安全存储等价物；清单只迁移字段和 UI 会造成凭据泄露或恢复错位。
   - 备份/恢复有加密、口令、manifest 校验、冲突与删除确认，需把数据协议和 UI 分开验收。

7. **厂商/外部应用适配**

   - Sandbox 支持 Android shell、Alpine、Termux、SSH profile；Manifest:58、SettingSandboxPage.kt:63、SettingSshProfiles.kt:95。鸿蒙端不能默认假定 Termux 或 Android shell 存在。
   - Health Connect、Xiaomi/系统通知、系统 TTS、媒体投影和无障碍属于 Android 能力或外部系统集成，必须标为“鸿蒙待核对”，不以 Android 编译通过替代。

## 重点遗漏与决策风险

- **最终基线风险**：当前工作树有大量未提交 WIP，包含 RouteActivity、NavContext、首页、聊天、设置、Novel、扩展和实验页变更；本清单的行号是当前快照定位。用户提交后以最终 SHA 重新跑 Screen/entry 计数、caller 搜索和源文件行号。
- **孤立注册 route**：当前源码除 RouteActivity 注册外未找到 caller 的有 History、LiveCompanion、SettingAgentRuntimeTasks、SettingExperimental、Log、Extensions、Stats；ShareHandler 虽无普通 caller，但由 Android SEND/PROCESS_TEXT intent 可达。公司端需核对隐藏/外部入口和既有用户范围，涉及移除须有明确用户依据；不要因未见 caller 直接漏迁。
- **设置树重复风险**：Setting 根页已经直接进入 WebMount/SubAgent/ModelCouncil/MiniApp/Novel/TodayBoard/iCloud/Synara/ZCode；SettingExperimental 仍注册且内部也列同类入口，但当前无外部 caller。鸿蒙计划需选一个用户树，并保留参数/深链兼容策略。
- **参数实体风险**：Chat、CouncilRoom、ProviderDetail、SkillDetail、NovelMarkdown、MiniAppRunner、MiniAppSettingsDetail、SynaraWorkspace、ZCodeSession、WebMountSession、DeepRead、SettingTodayBoardDetail 都带关键参数。页面截图或固定 HTML 链接不能证明实体存在、权限可用或任务可恢复。
- **父页状态遗漏**：Provider 配置/模型/编辑器/OAuth、Memory 五子页、Novel 三 tab+批次 sheet、Board source/template/action sheet、Chat approval/tool/usage/workspace sheet、MiniApp 编辑器/管理对话框都依赖父页 state/VM/持久化。移交项必须写 owner 和 state gate，不能只按设计文件建无状态页面。
- **平台权限遗漏**：Settings permissions、SystemAccess、Sandbox、WebMount login、iCloud login、Backup OAuth、分享/文件/相机/录屏/通知、后台 FGS/WorkManager 是功能契约的一部分；鸿蒙端需逐项标 pending，而不是把 Android permission 名字替换成鸿蒙文案。
- **远端内容误迁移风险**：Synara、ZCode、WebMount、MiniApp runner、iCloud login 和部分 DeepRead template 是远端/嵌入页面。固定 HTML 的 threads/files/terminal、文章、计算器、登录账号、URL 和成功状态不能写进鸿蒙本地数据。
- **证据层混淆**：源码映射、JVM/编译、Android 模拟器、Android 真机、Provider/network/OAuth、鸿蒙真机应分开记录；docs/verification/2026-09-13-redesign-coverage.md 只能说明映射记录，不能替代截图或真实账户验收。
- **首页基线**：历史设计文档记录过用户指定的首页例外，但当前收尾源码已继续变化。首页作为独立验收项，最终以用户确认与固定 SHA 为准，不能套用旧例外或所有二级页的规则。

## 可移交清单的建议结构

公司电脑 AI 接手时，每个条目按下面字段写，使用仓库相对路径：

- 条目 ID / 批次 / 优先级：P0 核心、P1 功能、P2 平台重/隐藏。
- Android Screen 或嵌套 surface：例如 Chat、SettingProviderDetail(providerId)、ModelEditorSheet。
- 类型：route、同页 tab、ModalBottomSheet、AlertDialog、Popup、Android Activity、Service/Receiver。
- Android owner：文件:行；entry：RouteActivity.kt:行；直接 caller：文件:行。
- 参数与数据 owner：conversationId/projectId/providerId/sessionId/topicId/appId/URL；Room、VM、repository、WebView handle、Keystore 或远端连接。
- 状态矩阵：首次/空数据、加载、运行、部分成功、失败/重试、权限拒绝、恢复/冲突、未保存退出、已完成。
- Android 专属依赖：Manifest 权限、SAF/ContentResolver、WebView/Cookie、OAuth/deep link、FGS/WorkManager、Accessibility/MediaProjection、Termux/SSH、TTS/IME。
- 鸿蒙映射状态：已确认、待公司端调查、无直接等价、需产品决策；记录对应 Harmony API/Ability/权限。
- 验收证据：源码/单测/构建、Android 模拟器、Android 真机、Provider/OAuth/network、鸿蒙模拟器/真机；每层单独打勾。
- 数据安全/回滚：凭据不落日志、不迁移样例账号；删除/覆盖/恢复操作保留确认和可回退证据。

## 建议分批交给公司电脑 AI

1. **P0-01 Shell 与首页/聊天**：SessionHome、Chat、ShareHandler、MessageSearch；先复核 launch mode、continue 路由、通知/快捷方式和聊天参数，再迁移 UI。
2. **P0-02 设置根与显示**：Setting、SettingAppearance、SettingDisplay、SettingAbout；确认首页例外、主题/字体/启动入口保存契约。
3. **P0-03 运行、记忆、权限、存储**：AgentExecution、Tts、Memory 五路由、AgentPermissions、CapabilityPermissions、SystemAccess、Sandbox、ChatStorage、Files、Storage；优先建立鸿蒙权限/后台/文件/安全存储适配矩阵。
4. **P1-01 Provider/模型/搜索/MCP**：Provider、ProviderDetail 两 tab、Models、Search、MCP，以及所有编辑/选择/OAuth sheet；先做 providerId、模型 ID、凭据安全和网络证据。
5. **P1-02 扩展/技能/小应用**：AgentExtensions、Skills/SkillDetail、Prompts、QuickMessages、Cron、Slides Fonts、MiniAppList/Runner/Settings；把 GitHub/文件导入、WebView runner、Cron 后台单列。
6. **P1-03 看板/深读/Novel/Council**：TodayBoard 六路由、Novel 两路由、CouncilRoom/成员 sheet/Model Council；先保留 topic/project/conversation/job 参数和持久批次/锁，再做视觉适配。
7. **P2-01 平台重与远端壳**：Backup、WebMount/WebMountSession、ICloud、Synara、ZCode、通用 WebView；公司端需真实鸿蒙浏览器/Web 组件、cookie/OAuth、SAF/备份和网络环境。
8. **P2-02 隐藏/诊断/兼容入口**：History、LiveCompanion、Stats、Log、Extensions、SettingExperimental、SettingAgentRuntimeTasks、Debug，以及 SafeMode/InlineLogin/McpOAuthCallback 等非 Screen Activity；以最终 SHA 的 caller 和产品决策决定是否公开。

每批完成的最小交付是：入口/参数表、owner/state gate、平台依赖清单、至少一条功能走通证据、缺口/阻塞项；视觉截图应单独记录，不把设计 HTML 或 Android 模拟器证据冒充鸿蒙真机验收。

## 参考入口索引

- 路由声明与 entry：app/src/main/java/app/amber/agent/RouteActivity.kt:625-942、1006-1256
- Navigator：app/src/main/java/app/amber/feature/ui/context/NavContext.kt:7-45
- 首页：app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:161、327-383、1253-1276
- 设置根：app/src/main/java/app/amber/feature/ui/pages/setting/SettingPage.kt:83、138-360
- 聊天与嵌套 surfaces：app/src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt:178、444-492、1170-1240
- Novel：app/src/main/java/app/amber/feature/ui/pages/novel/NovelMarkdownWorkspacePage.kt:130-137、457-543、598-653
- 实验/权限：app/src/main/java/app/amber/feature/ui/pages/setting/SettingExperimentalPage.kt:60-127、SettingAgentPermissionsPage.kt:47-124、SettingSystemAccessPage.kt:66-118
- Manifest/系统组件：app/src/main/AndroidManifest.xml:5-61、111-291
- 设计映射参考（非截图证据）：docs/verification/2026-09-13-redesign-coverage.md

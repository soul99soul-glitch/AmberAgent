# Android UI 重设计路由清单

日期：2026-09-12  
范围：`app/src/main/java/app/amber/agent/RouteActivity.kt` 中当前 `Screen` 的全部注册项（共 66 个），以及当前源码中能确认的入口。

这份清单用于防止高保真原型的页面范围遮蔽真实 Android 路由。`RouteActivity` 的 `Screen` 定义和 `entryProvider` 是路由全集；其余入口证据来自 `app/src/main/java` 的静态调用点。这里的“未确认入口”只表示本次抽查没有找到调用点，不表示死路由，也不授权删除或隐藏。

入口标签：

- **普通**：已找到用户可见的点击、启动模式或设置项。
- **隐藏**：已找到长按、开发者模式或高级设置入口。
- **外部**：由 Android Intent、通知或应用事件进入。
- **内部**：由另一个页面下钻进入。
- **未确认**：当前只确认路由注册或页面存在，入口仍需后续运行时/历史代码证据。

## Phase 2：首页、入口和基础设置

| `Screen` | 已确认入口 | 最小导航 / owner 检查 |
|---|---|---|
| `SessionHome` | **普通**：`LaunchStartMode.HOME` 冷启动；也是首页固定落点 | 首页列表、搜索、功能入口、继续区和返回链；`SessionHomeVM` 的分页、删除、置顶、头像设置回调保持接线 |
| `Profile` | **普通**：`SessionHomePage.HomeHeader` 头像 | `ProfilePage` 继续使用 `StatsVM`，头像更新走 `SessionHomeVM.updateSettings`，返回回到首页 |
| `Favorite` | **普通**：`ExtensionsPage` 收藏项 | `FavoriteVM` 的列表、打开节点和取消收藏回调不因卡片重排丢失 |
| `MessageSearch` | **普通**：首页搜索；历史页搜索入口也指向该路由 | `SearchVM` 查询、筛选、结果打开会话和返回链；搜索空态与加载态要保留 |
| `Setting` | **普通**：首页顶栏设置 | 设置首页所有现有行仍可下钻；`SettingVM` / `SettingsAggregator` 的状态源不替换 |
| `SettingDisplay` | **普通**：`Setting` 的显示设置项 | 颜色模式、Sage/Warm、accent、AMOLED、字体和启动模式写入原 `DisplaySetting`；返回后主题仍由 `AmberAgentTheme` 消费 |
| `SettingProvider` | **普通**：`Setting` 的服务商项和未配置提示卡 | 服务商列表、添加/编辑、测试和错误反馈仍由现有 provider owner 处理 |
| `SettingProviderDetail(providerId)` | **普通**：服务商列表；聊天模型列表也可进入 | UUID 参数解析、详情表单保存和返回到服务商列表；不得用展示层状态代替 provider 持久化 |
| `SettingModels` | **普通**：`Setting` 默认模型项；Live 页面也有设置跳转 | 模型选择、provider 关联和参数保存保持原 `SettingVM` / 设置仓库链路 |
| `SettingSearch` | **普通**：`Setting` 的搜索服务项 | 服务配置、选择模型、保存和失败提示保持原 owner；长 provider/model 名称不遮挡尾部控件 |
| `SettingAbout` | **未确认**：路由和 `SettingAboutPage` 已注册；本次静态抽查未找到调用点 | 页面可先保留原入口契约；进入后必须保留版本信息及其隐藏 Debug 长按链，不能因原型未展示而移除 |

首页的关键链路必须单独检查：会话行和 FAB 进入 `Chat` 时使用 push 保留 `SessionHome` 在栈底；首页代码明确不能改成会清空首页的 `navigateToChatPage`。`SessionHomePage.kt:285-291`、`:316-335` 是该约束的调用点。

## Phase 3：聊天、消息和外部输入

| `Screen` | 已确认入口 | 最小导航 / owner 检查 |
|---|---|---|
| `Chat(id, text, files, nodeId)` | **普通 + 外部**：首页会话行/FAB、启动模式、任务 Intent、通知会话深链 | `RouteActivity` 只负责构造参数；`ChatPage` → `ChatVM` → `ChatService` / 会话仓库的发送、取消、附件、审批、分支、流式 reveal 和持久化回调必须不变。通知的 `conversationId` 与 `runId` ownership 校验也要保留 |
| `ShareHandler(text, streamUri, streamUris, deliveryId)` | **外部**：`ACTION_SEND`、`ACTION_SEND_MULTIPLE`、`ACTION_PROCESS_TEXT`；相机快捷方式也回送 `RouteActivity.ACTION_SEND` | `RouteActivity.ShareHandler` 的消费标记、subject/text 合并、单/多 URI 解析和 `ShareHandlerPage` 投递链保持一次且仅一次；不要只验证页面显示而漏掉 URI grant |
| `WebView(url, content)` | **内部**：Markdown/代码块操作打开 HTML 内容页 | `HighlightCodeBlock` 传入的 content 编码、URL/content 二选一和 WebView 返回链不变；页面重设样式时不能覆盖内容安全边界 |

`RouteActivity` 还有不生成 `Screen` 的全局外部约束：冷/热启动都要先处理 `amberagent://oauth` 回调；`MemoryDreamNotifier`、`BoardNotifier` 和 `AppEventBus.OpenDeepRead` 要继续分别落到既有路由。UI 重构不能通过替换 `AppRoutes` 或 Intent 处理顺序把这些路径丢掉。

## Phase 4：工作台、实验功能和其余页面

### 议会、阅读和今日看板

| `Screen` | 已确认入口 | 最小导航 / owner 检查 |
|---|---|---|
| `CouncilRoom(conversationId)` | **普通**：首页功能 rail 的议会；**隐藏/内部**：`CouncilRoomDevEntry` | 首页创建议会必须保持 `SessionHomePage.kt:133-175` 的顺序：先 `ConversationRepository.insertConversation`，再 `CouncilRoomManager.openRoom`；失败删除占位会话并 toast，成功才导航；取消/异常清理与 `CouncilRoomVM` 加载同样保留 |
| `TodayBoard` | **普通**：首页深度阅读/看板入口；**外部**：`BoardNotifier` 通知 | `BoardViewModel` 加载、禁用/错误状态、列表点击和通知冷/热启动；返回链回到首页 |
| `DeepRead(topicId, title, sourceUrl, forceRegenerate, fromHistory)` | **普通**：看板条目、深读历史条目；**外部**：通知和 `AppEventBus.OpenDeepRead` | topic/title/source 参数完整传递；`fromHistory`、缓存读取、重新生成、错误和收费确认仍由 Deep Read owner 处理 |
| `DeepReadHistory` | **普通**：`BoardPage` 顶栏历史按钮 | 历史列表打开 `DeepRead` 时保留过期缓存和返回看板行为 |
| `SettingTodayBoard` | **普通**：`SettingExperimental`；`BoardPage` 顶栏设置 | 今日看板总设置、权限/状态和保存回调保持 `SettingTodayBoardPage` owner |
| `SettingTodayBoardDetail(pane)` | **内部**：今日看板设置中的 GENERAL/HOT_LIST/REVIEW 下钻 | pane 参数与分栏返回链不变；各 pane 的保存和错误反馈仍落到同一设置 owner |
| `DeepReadTemplateWorkbench` | **普通/内部**：今日看板设置的创建模板入口 | 模板编辑、预览、字体和保存动作仍由原 workbench owner 处理，不把 WebView 预览当成持久化成功 |

最小验证是：首页→看板→深读/历史/设置、通知→看板或深读、返回栈；以及首页→议会的成功、`openRoom` 返回错误和异常清理三条路径。不能只点击到页面而不核对会话占位和 council 状态落库。

### 小说和小应用

| `Screen` | 已确认入口 | 最小导航 / owner 检查 |
|---|---|---|
| `NovelProjects` | **普通**：首页小说入口 | 项目列表、创建/删除/打开和空态由 `NovelProjectsViewModel` 保持；不因换卡片布局改变项目 ID |
| `NovelMarkdown(projectId)` | **内部**：小说项目行 | `NovelMarkdownWorkspaceViewModel` 的加载、编辑、生成、保存、失败和返回项目列表链路完整 |
| `MiniAppList` | **普通**：首页小应用入口；聊天生成小应用卡也可打开 | 列表、置顶、重命名、删除、导出、编辑来源等回调仍由小应用 repository/VM 处理 |
| `MiniAppRunner(appId)` | **内部**：小应用列表和聊天小应用卡 | appId 传递、运行/错误/返回链不变；不得把运行中的状态做成静态原型 |
| `MiniAppSettings` | **普通**：小应用列表设置；实验设置入口 | 总设置页开关与保存仍由现有设置 owner 处理 |
| `MiniAppSettingsDetail(group)` | **内部**：小应用设置的 Common/HostAi/Advanced 分组 | group 参数、表单保存和返回设置页；敏感或失败状态仍可见 |

### 扩展、技能和统计

| `Screen` | 已确认入口 | 最小导航 / owner 检查 |
|---|---|---|
| `Extensions` | **普通**：`SettingAgentExtensionsPage` | 扩展总览和进入快捷消息、收藏、提示词；不改变已有 `PromptVM` 等 owner |
| `QuickMessages` | **内部**：扩展总览 | `QuickMessagesVM` 的增删改、插入聊天和保存反馈 |
| `Prompts` | **内部**：扩展总览 | 提示词列表、编辑/选择、插入聊天和返回链 |
| `Skills` | **普通**：Agent 扩展设置；技能卡列表 | `SkillsVM` 加载、启用开关和错误态；列表点击进入详情 |
| `SkillDetail(skillName)` | **内部**：技能列表；优化动作也会创建 Chat | skillName、启停/导入/配置、优化到聊天的参数和返回链 |
| `Stats` | **未确认**：路由已注册；个人资料当前直接复用 `StatsVM` 数据，未找到 `navigate(Screen.Stats)` 调用点 | 保留路由和 `StatsPage`；若后续不提供入口，也不能据静态缺少调用点判定 dead；Phase 4 记录最终范围决定 |

### 设置子页、同步和外部工作台

| `Screen` | 已确认入口 | 最小导航 / owner 检查 |
|---|---|---|
| `Backup` | **普通**：设置数据设置 | Google Drive/WebDAV/本地备份、冲突、加密和权限错误仍由 `BackupVM`/备份 provider 处理；不以视觉验收替代真实写入结果 |
| `SettingAgentMemory` | **普通 + 外部**：设置 Agent Runtime；`MemoryDreamNotifier` 通知 | `SettingVM` 的运行时设置、通知冷/热启动和 memory owner 链路；保存后重启仍读取同一值 |
| `SettingAgentMemoryRecall` | **内部**：Agent Memory 子页选择 | recall 配置与返回主 memory 页 |
| `SettingAgentMemoryWorker` | **内部**：Agent Memory 子页选择 | worker 开关/周期/状态与设置持久化 |
| `SettingAgentMemoryCompaction` | **内部**：Agent Memory 子页选择 | compaction 配置和边界值保存 |
| `SettingAgentMemoryLibrary` | **内部**：Agent Memory 子页选择 | 记忆列表、导入/导出、接受/忽略/删除确认；不要把删除动作当成纯 UI 测试 |
| `SettingAgentExtensions` | **普通**：设置 Agent Runtime | 下钻到扩展、技能、MCP、cron、幻灯片字体的入口和返回链 |
| `SettingSlidesFonts` | **内部**：Agent 扩展页 | 字体选择/预览/保存和生成内容消费 |
| `SettingCronTasks` | **内部**：Agent 扩展页 | 任务启停、调度和失败反馈；不重写 WorkManager owner |
| `SettingAgentRuntimeTasks` | **隐藏**：页面注释明确对终端用户隐藏；本次未找到普通调用点 | 保留 route/provider 和 developer harness 行为，Phase 4 只做可用时的视觉适配 |
| `SettingAgentExecution` | **普通**：设置 Agent Runtime；Live 页面设置按钮 | 执行上限、并发/工具恢复等设置写入原 settings owner，并由运行时读取 |
| `SettingAgentPermissions` | **普通**：设置 Agent Runtime | 自动批准、权限说明和下钻系统权限；保留危险操作确认 |
| `SettingCapabilityPermissions` | **条件普通**：Agent Permissions 在 capability flag 开启时显示 | flag 关闭时不显示入口；开启时权限列表和保存仍由权限 owner 处理 |
| `SettingMcp` | **内部**：Agent 扩展页 | MCP 列表、授权回调、断开/错误反馈和敏感字段处理 |
| `SettingFiles` | **普通**：设置数据设置 | 文件计数/预览/清理入口和文件 owner 状态，不把显示的计数当作成功写入证据 |
| `SettingStorage` | **普通**：设置数据设置 | 按时间清理、确认、失败和刷新列表；不在视觉测试中删除真实用户数据 |
| `SettingSandbox` | **普通**：设置 Agent Runtime | sandbox 开关、权限说明和保存/错误路径 |
| `SettingExperimental` | **普通**：设置 Agent Runtime | 实验功能入口全集、返回链和条件项 |
| `SettingExperimentalICloud` | **内部**：实验功能页 | iCloud 登录、Cookie/状态反馈和退出流程仍由 iCloud owner 处理 |
| `SettingExperimentalOfficePro` | **内部**：实验功能页 | Office Pro 登录/同步/变更日志状态和错误反馈 |
| `SettingExperimentalSubAgent` | **内部**：实验功能页 | subagent 开关、角色/并发配置和设置保存 |
| `SettingExperimentalModelCouncil` | **隐藏/高级**：SubAgent 页高级入口；Council Room 设置按钮 | model council 配置、座位/轮数和保存；与首页创建 council 使用同一持久化设置 |
| `SettingExperimentalWebMount` | **普通**：首页 WebMount 功能入口；实验功能页 | WebMount profile、登录/注销、Cookie 与状态检测；UI 不得跳过已有安全和 owner 检查 |
| `SettingSystemAccess` | **内部**：Agent Permissions | 系统权限状态、跳转系统设置和返回后的刷新 |
| `SynaraCompanion` | **普通**：实验功能页 Synara | LAN 连接表单、错误反馈和 `SynaraVM` 保存/连接回调 |
| `SynaraWorkspace(host, port, token, useHttps)` | **内部**：Synara 连接成功后 | 连接参数完整传入 WebView；连接失败不能伪装成已打开 |
| `ZCode` | **普通**：实验功能页 ZCode | 分享 URL 输入、校验和错误反馈 |
| `ZCodeSession(url)` | **内部**：ZCode 页面提交后 | URL 传递、WebView 加载/失败和返回实验设置 |

### 隐藏与当前未确认的保留路由

| `Screen` | 当前证据 | 最小检查 |
|---|---|---|
| `Developer` | **隐藏**：设置页在 `developerMode` 开启时显示入口 | 开发者模式开关、进入/返回和日志读取；不可从普通 UI 验收推断为不存在 |
| `Debug` | **隐藏**：`SettingAboutPage` 版本行 `combinedClickable` 的 `onLongClick` 进入 | 必须保留长按命中区域与 `Screen.Debug` 导航；Debug 页的开关/诊断 owner 不被普通视觉重构覆盖。证据：`SettingAboutPage.kt:132-136` |
| `Log` | **未确认**：路由和 `LogPage` 已注册，本次静态抽查未找到调用点 | 保留 route/provider；在确认入口前不删除、不改为默认首页入口 |
| `History` | **未确认**：路由和 `HistoryPage` 已注册，本次静态抽查未找到 `navigate(Screen.History)` 调用点 | 保留页面、搜索和撤销删除 owner；后续运行时确认是否仍有入口 |
| `LiveCompanion` | **未确认**：路由和页面已注册，本次静态抽查只找到页面内部设置/模型跳转 | 保留 route/provider 与 Live owner；不要把未找到静态 caller 当成删除依据 |

## 全局验收约束

1. 任何 `Screen` 的视觉改动都要沿“入口 → 页面 → ViewModel/manager/repository → 持久化或外部副作用 → 返回/错误”走一条最小闭环；点击到页面不等于链路完成。
2. Phase 2 负责首页和基础设置入口，Phase 3 负责聊天、分享和消息内容，Phase 4 负责本清单中的工作台、实验、扩展、同步、隐藏及未确认保留路由。每阶段结束时只验收该阶段实际触碰的 owner，但要检查跨阶段的返回栈。
3. `RouteActivity` 的冷启动、热启动和 `onNewIntent` 共享路由约束：通知会话、Memory、Today Board、Deep Read、分享投递和 OAuth 回调不能因重排 Compose 页面而改变消费次数或优先级。
4. 未确认入口需要运行时或历史调用证据后再决定是否展示；在证据出现前一律保留路由，不把“原型未覆盖”解释为功能不存在。


# AmberAgent iOS Android Feature Parity Roadmap 2026-06-19

本文档用于把 AmberAgent iOS 追平 Android 的剩余工作从零散页面修补收束为可并行推进的阶段计划。它不是 UI 视觉改版计划，也不是多 Assistant 系统计划；iOS 只保留一个 Amber Assistant，目标是把 Amber 自身的正式能力补齐到 Android 同等能力、功能和水平。

## 已确认产品决策

- iOS 不做 Android 式多 Assistant 系统，只做一个 Amber Assistant。
- 深度阅读是第一优先级，不能停留在“今日看板改名”或轻量摘要。
- 模型议会、小应用、深度阅读、WebMount、SubAgent 都是正式高级功能，不放实验区，不做总开关隐藏。
- 记忆、搜索、图片生成、Mini App 都是正式能力，目标是与 Android 同等级闭环。
- 收藏暂不做。
- 统计已合并到 iOS User 页面，不再追 Android 独立 Stats 页。
- 系统访问、自动化、分享、外部入口按 iOS 平台能力做适配。iOS 未开放的能力不伪实现，也不在主路径展示成可用功能。
- 设置页和功能页只保留真实可配置、可进入、可执行的内容；说明性工程项不伪装成设置项。

## 当前代码事实基线

### Android 主要能力锚点

- 路由总表：`app/src/main/java/app/amber/agent/RouteActivity.kt`
- 深度阅读和看板：`app/src/main/java/app/amber/feature/ui/pages/board/DeepReadScreen.kt`、`DeepReadHistoryPage.kt`、`DeepReadTemplateWorkbenchPage.kt`、`SettingTodayBoardPage.kt`
- 深度阅读后台和缓存：`app/src/main/java/app/amber/feature/board/hotlist/deepread/*`
- 搜索工具：`app/src/main/java/app/amber/core/ai/tools/SearchTools.kt`、`SearchOrchestrator.kt`、`SearchAggregator.kt`
- 记忆系统：`app/src/main/java/app/amber/core/memory/*`
- 图片生成：`app/src/main/java/app/amber/core/ai/tools/ImageGenTool.kt`、`app/src/main/java/app/amber/core/repository/ImageGenerationRepository.kt`
- Mini App：`app/src/main/java/app/amber/feature/miniapp/*`
- WebMount：`app/src/main/java/app/amber/feature/webmount/*`
- 工作区和 Artifact：`app/src/main/java/app/amber/feature/tools/WorkspaceArtifactTools.kt`
- SubAgent 和模型议会：`app/src/main/kotlin/app/amber/feature/subagent/*`、`app/src/main/kotlin/app/amber/feature/modelcouncil/*`

### iOS 当前能力锚点

- 路由入口：`iosApp/iosApp/AppShell.swift`
- Session 首页和设置入口：`iosApp/iosApp/ChatView.swift`、`iosApp/iosApp/SettingsView.swift`、`iosApp/iosApp/PlaceholderViews.swift`
- 深度阅读当前起点：`iosApp/iosApp/BoardView.swift`、`BoardSettingsView.swift`、`IOSBoardPersistence.swift`
- 搜索当前起点：`iosApp/iosApp/SearchServicesView.swift`、`SearchProviderView.swift`、`IOSSearchExecutor.swift`
- 记忆当前起点：`iosApp/iosApp/MemoryOverviewView.swift`、`MemoryEditView.swift`、`IOSMemoryPersistence.swift`
- Mini App 当前起点：`iosApp/iosApp/MiniAppListView.swift`、`MiniAppRunnerView.swift`、`MiniAppRunnerWebView.swift`、`IOSMiniAppRepository.swift`
- WebMount 当前起点：`iosApp/iosApp/WebMountView.swift`
- SubAgent 和模型议会当前起点：`iosApp/iosApp/SubAgentsView.swift`、`CouncilView.swift`、`CouncilChatRuntimeView.swift`
- 工具声明和执行：`iosApp/iosApp/ChatViewModel.swift`、`IOSLocalToolExecutor.swift`

## 总体分期

### Phase 0：产品结构收口

目标：先把 iOS 的信息架构和正式能力分类稳定下来，避免后续实现继续被错误入口拖偏。

范围：

- Session 首页只保留 4 到 5 个高频正式入口，深度阅读、小应用、WebMount、模型议会固定保留。
- 设置一级页不放技能、MCP、远程执行、工作区、全局搜索这类二级功能。
- 高级功能区保留 SubAgent、模型议会、小应用、深度阅读、WebMount。
- MCP 放技能下，远程执行放执行与任务下，工作区和全局搜索进入对应功能页。
- 一级设置页状态文案只显示真正需要用户决策的状态，例如外观跟随系统、模型服务是否缺配置、同步是否未登录；不显示“可用”“API Key”这类工程化后缀。

验收：

- 用户不会在设置页看到“看似设置项、实际只是注释”的内容。
- 所有高级功能在信息架构上是正式功能，不再进入实验性功能或开关池。
- iOS 不出现多 Assistant 管理入口。

推荐 goal：

```text
/goal 收口 AmberAgent iOS 产品结构：按一个 Amber Assistant 的产品决策整理 Session 首页、设置首页和高级功能区，不实现新功能，只清理错误分类、工程化说明项和实验性归类。保持现有设计规范，不做视觉大改；技能/MCP、远程执行、工作区、全局搜索进入各自二级位置；高级功能区展示 SubAgent、模型议会、小应用、深度阅读、WebMount。验证设置页和 Session 首页没有“可用/API Key/待接说明”式伪设置项，功能入口排序与 Android 用户习惯一致。
```

### Phase 1：深度阅读完整闭环

目标：把 iOS 深度阅读从当前 Board/TodayBoard 的轻量生成能力，推进到 Android DeepRead 同等级的正式功能。

必须补齐：

- 深度阅读首页：主题列表、来源状态、最近结果、正在生成任务、失败恢复。
- 输入来源：搜索结果、网页 URL、WebMount 页面、会话内容、文件上下文、手动粘贴文本。
- 生成任务：创建任务、进度状态、取消、重试、失败原因、token 成本提示。
- 结果页：文章式阅读体验、引用来源、目录、段落状态、图片或媒体占位、复制/分享/保存。
- 历史：按时间、来源、主题检索；支持删除和重新生成。
- 模板和版式：内置模板、用户模板、模板校验、字体/字号/密度设置。
- 模型配置：深度阅读使用的模型、搜索服务、预算策略、长文截断策略。
- 后台策略：iOS 能力范围内的前台刷新、BGTaskScheduler 尝试、通知提示；不能承诺 Android WorkManager 等价后台能力。
- 与聊天连接：聊天中可发起深度阅读，深度阅读结果可回填为上下文。

验收：

- 用户可以从一个真实来源开始深度阅读，并看到生成进度。
- 成功后能进入结果页、回看历史、重新生成、调整模板和模型。
- 无 API Key、无网络、来源不可读、生成失败、超预算时都有可恢复状态。
- 所有状态来自真实持久化和任务状态，不用静态说明冒充功能。

2026-06-19 iOS 落地记录：

- 已把 `BoardView` 从轻量线索摘要扩展为正式深度阅读入口，支持手动文本、搜索结果、当前会话、文件选择、当前 WebMount 页面五类来源；其中文件和 WebMount 会在不可读、未授权、页面未加载时给出诚实失败/降级。
- 已新增本地深度阅读任务模型、状态机、历史持久化、结果详情页、复制结果、回填当前聊天、失败/unsupported 重试，以及最小模板/版式选择和 HTML 模板校验。
- 已保留 iOS 单 Amber Assistant 的产品边界；没有实现 Android 多 Assistant，也没有把深度阅读放进实验区或总开关。
- 未实现 Android WorkManager/通知/24h 后台缓存等平台特有能力；iOS 目前是前台本地可验证闭环，真实联网搜索仍依赖现有搜索配置。

推荐 goal：见本文档末尾“首个推荐 /goal Prompt”。

### Phase 2：记忆、搜索、图片生成、Mini App 正式能力追平

目标：把四个正式能力从“入口和基础执行”推进到日常可用。

#### 2A 记忆

必须补齐：

- 记忆搜索、过滤、分类和来源追踪。
- 记忆详情编辑、合并、删除、禁用、恢复。
- 会话写入记忆的审批和记录。
- 召回解释：本次为什么用了这些记忆。
- 导入导出或至少接入备份恢复。
- 与聊天、深度阅读、SubAgent 的可控共享边界。

验收：

- 用户能查找、理解、修改和清理 Amber 记住的内容。
- 聊天引用记忆时可解释、可追溯、可撤回。

#### 2B 搜索

必须补齐：

- provider 管理、默认搜索服务、API Key 状态、失败降级。
- 搜索结果页、来源引用、图片或媒体结果展示。
- `search_web` 和 `scrape_web` 的工具调用闭环。
- 搜索结果进入聊天、深度阅读和工作区。
- 多 provider 编排策略：至少明确默认 provider、fallback、超时和去重。

验收：

- 用户能配置搜索服务并在聊天或深度阅读里真实使用。
- 结果可引用、可打开、可追踪工具记录。

#### 2C 图片生成

必须补齐：

- 图片生成入口。
- 模型、尺寸、数量、风格、参考图或提示词参数。
- 生成历史图库、保存、分享、重新生成。
- 聊天内图片生成工具调用和结果卡片。
- 失败、超时、缺模型、缺 API Key 的恢复状态。

验收：

- 用户能从独立入口或聊天里生成图片，并管理历史结果。

#### 2D Mini App

必须补齐：

- 小应用列表、运行器、设置和权限。
- 安装、更新、删除、置顶或最近使用。
- 与聊天生成/打开小应用的关系。
- Bridge capability、storage、network/search/fetch 权限。
- 运行错误、HTML 校验错误、权限拒绝的用户可理解反馈。

验收：

- 小应用不只是 WKWebView 跑一个 HTML，而是可管理、可复用、可授权的正式能力。

推荐并行方式：

- 记忆和搜索可先并行，因为它们都服务聊天和深度阅读。
- 图片生成可以独立推进。
- Mini App 可独立推进，但要避免和 WebMount 共用 WebKit/Bridge 文件时互相覆盖。

2026-06-19 iOS Phase 2 落地记录：

- 记忆：新增正式记忆库 helper、搜索/范围过滤、来源摘要、召回解释和写入审计；`MemoryOverviewView` 变为可搜索、可编辑、可删除、可看来源和审批记录的管理闭环，`MemoryEditView` 支持单条创建/编辑/删除。
- 搜索：`SearchServicesView` 可直接运行 `search_web` 与 `scrape_web`，展示 provider、fallback、引用和错误降级；`SearchProviderView` 的状态文案改为真实保存/缺 Key 状态。
- 图片生成：新增 OpenAI-compatible 图片生成设置、repository、历史图库、保存文件、分享、独立页面和聊天 `generate_image` 工具结果渲染；缺 API Key、缺模型、错误响应会显示真实错误，不新增伪设置项。
- Mini App：扩展 runtime 权限设置到 network/search/AI/clipboard/shared store/event bus/host context/host write，`fetch` 复用公开 URL 校验阻断私网/本机地址，runner 阻断外部导航继承 native bridge。
- 验证：`git diff --check` 通过；`env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "id=293252D5-CCF3-47DD-8736-8A8A26A6788C" build` 通过；相关 XCTest（`IOSMemoryLibraryTests`、`IOSImageGenerationRepositoryTests`、`IOSMiniAppBridgeRuntimeTests`、`IOSSharedSettingsStoreSkillWriteBackTests`、`IOSSearchExecutorTests`）通过。`generic/platform=iOS Simulator` 仍会选择 x86_64 并因现有 `Shared.framework`/`AmberNative` 只有 arm64 simulator slice 而链接失败，应使用真实 arm64 simulator destination。

### Phase 3：工作区与文件闭环

目标：让文件、上下文、Artifacts、工作区成为贯穿聊天、深度阅读和工具执行的统一能力，而不是散落入口。

必须补齐：

- Workspace 首页：最近文件、Artifacts、任务输出、网页快照、生成结果。
- 文件导入：Document Picker、Files App、安全书签、最近文件。
- 文件解析：PDF、DOCX、PPTX、Markdown、文本、图片 OCR 的 iOS 可行路径。
- 会话文件上下文：当前会话引用了哪些文件，如何移除、预览、重新解析。
- Artifact 展示：模型生成的文件、报告、代码、Mini App、深度阅读结果可沉淀到工作区。
- 权限边界：工具能访问哪些文件、何时需要审批。
- 清理管理：空间占用、失效文件、缓存清除。

验收：

- 用户能把文件交给 Amber，清楚知道哪些文件参与上下文。
- 生成物不只存在于一次聊天里，而能保存和回看。
- 工具访问文件时有清晰审批和范围。

2026-06-19 iOS 落地记录：

- 已新增本地 `IOSWorkspaceStore`：用户通过系统文件选择器导入的文件会复制进 AmberAgent app 容器下的 Workspace，记录 metadata、状态、预览文本、大小、来源和 `/workspace/...` 路径；不会自动扫描用户目录，也不会持久化跨启动安全书签。
- 已把 Workspace 首页从占位入口改成真实页面，支持导入文件、最近文件、Artifact 列表、metadata、文本预览、重新解析、移除文件和删除 Artifact；失效文件、过大文件、解析失败、unsupported 格式会显示诚实状态。
- 已把聊天文件选择接到 Workspace 导入和 one-shot 会话文件上下文：发送前明确提示解析文本会写入当前会话；失效文件会清理 grant 并要求用户从 Files 重新选择。
- 已支持文本、Markdown、JSON/CSV、PDF、DOCX 的基础解析路径；图片/OCR、Office 高阶解析和 iCloud/Files 真实账号状态仍按 unsupported/错误状态处理，不伪装完成。
- 已新增 Workspace 工具和权限边界：`workspace_file_read`、`workspace_file_write`、`workspace_artifact_read`、`workspace_artifact_delete` 均需要前台审批；路径穿越和设备绝对路径会被拒绝。
- 已让聊天回复、图片生成输出、Mini App 生成/host artifact、Deep Read 结果能保存为 Workspace Artifact。Mini App/Deep Read 主线仅做输出沉淀旁路，不改变原执行流程。
- 验证：`git diff --check` 通过；`JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:linkDebugFrameworkIosSimulatorArm64` 通过。`generic/platform=iOS Simulator` 在当前机器会选择 x86_64，仍因现有 `Shared.framework`/`AmberNative` 只有 arm64 simulator slice 而链接失败；使用真实 arm64 iPhone 17 simulator destination 可完成 app build 和相关测试。

### Phase 4：WebMount 与高级网页工具闭环

目标：把 iOS WebMount 从基础网页打开/读取推进到接近 Android 的网页工作台，同时遵守 iOS WebKit 安全边界。

必须补齐：

- 站点注册表：默认站点、添加、删除、恢复、启用/禁用。
- WebMount 设置：全局开关、eval 独立关闭、站点 allowlist。
- WKWebView runtime：页面状态、标题、URL、加载错误、前进后退、session。
- Cookie/session 摘要和清理：不泄露 cookie value。
- 只读 bridge：state、extract readable、extract links、get text/attr。
- 工具执行器：`wm_stations`、`wm_open`、`wm_state`、`wm_extract`、`wm_get`、`wm_back`、`wm_forward`、`wm_clear_session`。
- 内容转入：网页内容可进入聊天、深度阅读、工作区。
- 诚实降级：OAuth、signed fetch、站点 adapter、跨站自动操作等无法完成时明确 unsupported。

验收：

- 用户能添加/打开站点、读取网页内容、转入深度阅读或聊天上下文。
- 高风险网页动作受审批或默认关闭。
- iOS 不绕过 WebKit 安全限制，不伪造 Android 能力。

### Phase 5：执行与高级能力闭环

目标：让 SubAgent、模型议会、远程执行、任务状态、工具审批成为稳定高级功能，而不是入口集合。

必须补齐：

- SubAgent：角色管理、任务派发、运行状态、工具范围、结果回填、失败恢复。
- 模型议会：席位配置、模型预算、讨论过程、最终结论、结果引用到聊天。
- 远程执行：SSH 配置、连接测试、命令运行、输出日志、超时、取消、安全审批。
- 工具审批：高风险动作确认、记忆写入确认、文件写入确认、网页会话操作确认。
- 任务状态：正在运行、已完成、失败、可重试、可查看日志。

验收：

- 高级功能可以完成真实任务。
- 用户能理解每个工具动作的风险、范围和结果。
- 执行状态不靠静态文案，必须来自真实 runtime。

### Phase 6：iOS 平台适配能力

目标：在 iOS 开放能力内尽量接近 Android 的系统入口和自动化能力。

需要评估并实现：

- Share Extension：从 Safari、Files、其他 App 分享文本、URL、文件到 Amber。
- Document Picker 和 Files 集成：安全书签、重新授权、文件失效处理。
- App Intents / Shortcuts：新建聊天、搜索、深度阅读、导入链接、运行指定高级功能。
- URL Scheme / Universal Links：打开聊天、深度阅读任务、小应用、WebMount 站点。
- 通知：深度阅读完成、远程任务完成、同步失败、长任务失败。
- Live Activity：长任务、远程执行、深度阅读生成进度。
- BackgroundTasks：只做 iOS 允许的轻量后台刷新，不承诺常驻执行。
- 剪贴板/链接导入：用户主动触发，不做隐式读取。

验收：

- 每项能力被标记为可实现、受限实现或不可实现。
- 不可实现能力不出现在用户主路径。
- 受限能力要有清楚的用户预期和降级状态。

## 推荐并行推进顺序

第一波：

- Deep Read 完整闭环。
- 搜索闭环。
- 工作区与文件基础闭环。

原因：深度阅读依赖搜索、文件、网页输入和模型配置；这三条一起补，收益最大。

第二波：

- 记忆正式能力。
- 图片生成。
- Mini App 完整 runner。

原因：这些功能相对独立，但都会回流到聊天和工作区。

第三波：

- WebMount 高级工具。
- 执行与高级能力。
- iOS 平台入口适配。

原因：这些会碰安全边界、权限审批和系统限制，适合在前面能力模型稳定后推进。

## Work Packet 列表

| Packet | 目标 | 依赖 | 可并行性 | 推荐优先级 |
| --- | --- | --- | --- | --- |
| WP1 Deep Read | 深度阅读来源、任务、历史、模板、结果页 | 搜索/模型/存储基础 | 可独立主线 | P0 |
| WP2 Search | provider、结果、引用、scrape、多 provider fallback | 模型/API Key 设置 | 可与 WP1 并行 | P0 |
| WP3 Workspace Files | 文件导入、解析、上下文、Artifacts | 存储和权限审批 | 可与 WP1 并行 | P0 |
| WP4 Memory | 搜索、召回解释、写入审批、导入导出 | 聊天和存储 | 可与 WP2 并行 | P1 |
| WP5 Image Generation | 入口、参数、图库、聊天工具 | provider/model | 独立 | P1 |
| WP6 Mini App | repository、runner、bridge、权限、设置 | WebKit/Bridge | 独立，避开 WebMount 同文件冲突 | P1 |
| WP7 WebMount | 站点、runtime、bridge、tools、内容转入 | WebKit/权限审批 | 可独立，但需安全审查 | P1 |
| WP8 Advanced Execution | SubAgent、议会、SSH、审批、任务状态 | 工具审批和模型配置 | 可分拆 | P1 |
| WP9 iOS Platform Entrypoints | Share Extension、App Intents、URL、通知、Live Activity | 目标功能已稳定 | 后置 | P2 |

## Verification Plan

每个后续 `/goal` 都必须先做：

```bash
git status --short --branch
git log --oneline --decorate -12
```

如果只改文档：

```bash
git diff --check
```

如果改 iOS Swift 代码：

```bash
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" test
```

如果涉及 shared/KMP 对 iOS export：

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

如果涉及 Android parity 对照但不改 Android：

```bash
rg -n "目标功能关键词" app shared iosApp
```

每个 goal 的完成报告必须包含：

- 实际改了哪些文件。
- 对齐了 Android 哪些能力。
- iOS 因平台限制只能做到什么程度。
- 哪些缺口仍未完成，下一步最小 goal 是什么。
- 构建/测试/静态检查结果；如果环境阻塞，给出原始命令和精确错误。

## Stop / Pause 条件

遇到以下情况暂停，而不是继续伪实现：

- 需要真实账号、真实 API Key、付费服务或用户私有数据才能判断正确性。
- 需要 Apple 开发者账号、签名、App Group、Extension entitlement 或后台模式产品决策。
- iOS 不开放对应系统能力，例如任意后台常驻、全局屏幕读取、其他 App 私有数据访问。
- 需要破坏性删除大量已有代码或回滚用户未提交改动。
- 同一构建环境问题连续出现 3 次且无法通过静态检查继续获得有效证据。
- 功能边界出现产品取舍，例如是否允许 agent 自动网页登录、是否读取通知内容、是否让 SubAgent 写文件。

## 首个推荐 /goal Prompt

下面是建议下一步直接使用的目标。它聚焦深度阅读完整闭环，但会允许最小接入搜索、文件、WebMount 和聊天上下文，避免做成孤岛。

```text
/goal 为 AmberAgent iOS 补齐“深度阅读”完整闭环，使它成为正式可用功能，而不是今日看板改名或静态说明页。iOS 只保留一个 Amber Assistant；不要实现多 Assistant 系统，不要把深度阅读放实验区或做总开关隐藏。先读取当前 git 状态、iOS 的 BoardView/BoardSettingsView/IOSBoardPersistence/AppShell/ChatViewModel/IOSSearchExecutor/IOSConversationStore/DocumentAccessStore/WebMountView，以及 Android 的 DeepReadScreen、DeepReadHistoryPage、DeepReadTemplateWorkbenchPage、SettingTodayBoardPage、feature/board/hotlist/deepread、DeepReadOpenTool、DeepReadPlaybookTools、SearchTools、BoardRepository/BoardSignal/BoardAgent 作为对照；基于真实代码建立 iOS Deep Read Gap Matrix 后再实现。

验证：开始运行 git status --short --branch 和 git log --oneline --decorate -12；实现后运行 git diff --check；尽可能运行 xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build 以及相关 iosAppTests。新增或更新 Swift XCTest 覆盖深度阅读任务持久化、历史读写、来源输入归一化、模板校验、失败/重试状态、搜索结果转深度阅读、会话内容转深度阅读、文件/网页来源的诚实降级。若 iOS simulator、Xcode 组件、真实 API Key、网络、账号或平台权限阻塞，记录精确错误和可复现命令，继续完成可静态验证和可 mock 的部分，不要声称真实联调通过。

约束：遵守 AGENTS.md；保护当前工作区已有改动，不回滚未理解文件；不修改 Android 业务逻辑，不改 Gradle/Xcode project 生成物、证书、发布配置、google-services.json 或私有配置；不触碰真实账号、真实 API Key、付费服务和生产数据；不恢复 Android 多 Assistant；不做收藏；统计不拆回独立页；不大改视觉设计规范，只做为功能闭环必要的 SwiftUI 页面和状态；不要用静态文案冒充功能，不要新增“可用/API Key/待接说明”式伪设置项。

边界：允许修改 iosApp/iosApp 中与深度阅读直接相关的 BoardView、BoardSettingsView、IOSBoardPersistence、AppShell、ChatViewModel 的最小入口接线、IOSSearchExecutor 的最小深度阅读来源适配、IOSConversationStore/DocumentAccessStore/WebMountView 的只读 helper，以及新增 IOSDeepRead*.swift、小型 SwiftUI 页面和相关 iosAppTests；允许小幅更新 docs/ios-port/IOS_ANDROID_PARITY_ROADMAP_2026-06-19.md 记录实际落地范围。禁止修改 MiniApp 主线、WebMount 高级工具主线、远程 SSH 主线、SubAgent/模型议会主线，除非只是提供只读来源入口且无行为破坏。

迭代策略：先盘点 Android 深度阅读能力和 iOS 当前 Board 能力，列出 P0/P1/P2；然后按依赖顺序连续实现：DeepRead 数据模型和任务状态、Documents 持久化、来源输入归一化、历史列表和详情页、结果页、模板/版式最小系统、搜索/会话/文件/WebMount 来源接入、失败重试和空状态、测试和文案清理。不要在完成一个模型或一个页面后停止；每完成一个泳道运行最小验证。如果某一泳道连续 2 次被真实账号、网络、API Key、WebKit 或 iOS 权限卡住，降级为 mockable adapter 或诚实 unsupported 状态，并继续完成其它本地可验证泳道。优先真实数据链路和用户闭环，其次 Android parity breadth，最后视觉 polish。

完成条件：iOS 有真实深度阅读入口、任务创建、生成中/失败/成功状态、历史列表、结果详情页和本地持久化；至少支持手动文本、搜索结果、会话内容三类本地可验证来源，文件和 WebMount 来源有真实可用路径或诚实降级；模板/版式有最小可选系统和校验；结果可复制/保存/回到聊天上下文；无 API Key、无网络、来源不可读、生成失败时都有清晰恢复路径；设置和页面文案不再把未实现功能伪装成设置项；测试或构建通过，或环境阻塞被精确记录且所有可静态验证部分已完成。

暂停条件：需要真实搜索/API Key/付费模型才能判断核心逻辑、需要真实 WebMount 登录账号、需要真实文件权限或用户隐私数据、需要 Apple 账号或新增 entitlement、需要破坏性删除大量已有功能、需要决定是否允许后台自动生成深度阅读、需要大改当前设计规范，或同一外部环境阻塞连续出现 3 次时暂停并说明。
```

# AmberAgent iOS Release Readiness Plan

日期：2026-06-19

目标：把当前 iOS app 收口成“真实用户可用版本”的发布计划。本文只做产品发布与项目管理判断，不实现功能、不删除代码、不把工程接线状态或平台迁移状态当作用户价值。

## Release Definition

AmberAgent iOS 的首个真实用户可用版本，应定义为：

- 用户可以在不阅读开发说明的情况下完成一次端到端成功：配置一个 OpenAI-compatible 服务商/API Key/模型，新建聊天，得到回复，退出后历史仍可继续。
- 默认首页和设置页只承诺稳定能力；未达到稳定验收的能力必须隐藏或移入实验区，且不能出现在首屏快捷入口里伪装成正式功能。
- 所有非 P0、实验性、高风险或依赖外部配置的用户入口，都必须是明确的开关形式：默认关闭，关闭时不暴露可继续点击的正式入口，打开前说明风险/依赖/数据边界。
- 涉及用户数据、账号、文件、网页会话、远程机器或工具调用的入口，必须有清晰的启用条件、失败解释、用户确认和数据边界。
- 备份/同步必须按真实覆盖范围表述。当前代码导出 `settings.json`，不能让用户误以为聊天记录、技能文件、本地文件或附件已经被保护。
- P0 完成后才能称为可发布；P1 是首个小版本增强；P2 是高级/实验能力继续孵化。

不进入本版本定义：

- 让真实用户直接依赖 MCP、WebMount、MiniApp、SubAgent、模型议会、Remote SSH 作为默认成功路径。
- 承诺跨端同步、完整文件知识库、完整 provider matrix、完整工具生态或自动化代理运行环境。

## Code Evidence Read

本次审计先读取了干净工作区的 `git status --short`，随后核查了这些真实代码面：

- App 入口与路由：`iosApp/iosApp/AmberAgentApp.swift`、`iosApp/iosApp/AppShell.swift`、`iosApp/iosApp/ContentView.swift`。
- 首页、设置入口、占位页：`iosApp/iosApp/PlaceholderViews.swift`、`iosApp/iosApp/SettingsView.swift`。
- 聊天主链路：`iosApp/iosApp/ChatView.swift`、`iosApp/iosApp/ChatViewModel.swift`。
- Provider/API Key/模型：`iosApp/iosApp/SettingsStore.swift`、`iosApp/iosApp/ProviderRegistryStore.swift`、`iosApp/iosApp/ProvidersView.swift`、`iosApp/iosApp/ModelDefaultsView.swift`。
- 数据与工具：`iosApp/iosApp/IOSConversationStore.swift`、`iosApp/iosApp/ConversationStorageView.swift`、`iosApp/iosApp/DocumentAccessStore.swift`、`iosApp/iosApp/IOSLocalToolExecutor.swift`、`iosApp/iosApp/PermissionsApprovalView.swift`、`iosApp/iosApp/ToolPermissionsView.swift`。
- 搜索、记忆、同步：`iosApp/iosApp/IOSSearchExecutor.swift`、`iosApp/iosApp/SearchServicesView.swift`、`iosApp/iosApp/MemoryOverviewView.swift`、`iosApp/iosApp/MemoryEditView.swift`、`iosApp/iosApp/IOSMemoryPersistence.swift`、`iosApp/iosApp/SyncBackupView.swift`、`iosApp/iosApp/IOSSyncBackup.swift`。
- 高级能力：`iosApp/iosApp/SkillsView.swift`、`iosApp/iosApp/IOSSkillFileStore.swift`、`iosApp/iosApp/McpServersView.swift`、`iosApp/iosApp/IOSMcpManager.swift`、`iosApp/iosApp/IOSMcpClient.swift`、`iosApp/iosApp/WebMountView.swift`、`iosApp/iosApp/IOSMiniAppRepository.swift`、`iosApp/iosApp/IOSMiniAppBridgeRuntime.swift`、`iosApp/iosApp/SubAgentsView.swift`、`iosApp/iosApp/SubAgentRunner.swift`、`iosApp/iosApp/CouncilView.swift`、`iosApp/iosApp/CouncilRunner.swift`、`iosApp/iosApp/RuntimeEnvironmentView.swift`、`iosApp/iosApp/IOSTerminalRuntime.swift`、`iosApp/iosApp/IOSSSHModels.swift`。
- 现有测试：`iosApp/iosAppTests` 下已有 conversation、provider registry、file context、permission store、permission status、memory persistence、sync backup、skill store、MCP client/manager/config、search executor、tool runtime、MiniApp parser/repository/bridge、SSH runtime、runtime DAO、board persistence、live provider smoke 等测试。

关键入口事实：

- `AmberAgentApp` 的实际根视图是 `AppShell`；`ContentView` 文件注释明确为 deprecated。
- `AppShell` 以 `ConversationsView` 为根，`Route` 已暴露聊天、设置、搜索、账号、对话存储、同步备份、技能/MCP、执行、服务商、模型、搜索服务、TTS、看板、MiniApp、WebMount、记忆、模型议会、子代理、运行环境、工具权限等入口。
- `ConversationsView` 首屏快捷入口包含「今日看板」「小应用」「工作区」「核心记忆」「模型议会」；这些会被用户理解为正式能力。
- `SettingsHomeView` 已把模型议会、子代理、小应用、WebMount 放进“实验性”，但它们仍然出现在路由和部分首屏入口。
- `SearchView`、`WorkspaceView`、`AssistantsView` 仍有静态/占位内容；不能作为正式发布承诺。

## Golden Paths

### GP0: First Chat Success

用户首次打开 app，进入服务商设置，配置 OpenAI-compatible base URL/API Key/model，新建聊天，发送一句普通问题，看到流式回复，退出再进入仍能看到历史。

验收：

- 无 API Key 或 provider 不可用时，聊天入口给出可操作的设置引导，不产生空白失败。
- 只向模型声明稳定工具；MCP/SubAgent/模型议会/WebMount/MiniApp 不在默认 tool set 中误触发。
- 生成成功、取消、失败三种状态都保存到对话历史，并显示可理解的结果。

### GP1: Conversation Continuity

用户可以从首页继续历史会话、重命名、置顶、删除，并能在“对话存储”看到真实用量和清理动作。

验收：

- `IOSConversationStore` bootstrap 后不会丢失最近对话。
- 删除/批量删除有二次确认。
- 存储失败有用户可见的解释或降级提示，不能只 `print`。

### GP2: One-shot File Context

用户在工具权限页选择一个本地文件，回到聊天，将一次性预览附加到下一条消息，问一个关于文件的问题。

验收：

- 只承诺 selected-file preview，不承诺完整文件库或长期文件索引。
- 大文件、二进制、权限过期、无文件选择均有清晰提示。
- `file_read_selected` 仍保持一次性/短 TTL/用户发起的边界。

### GP3: Memory With Consent

用户能查看/新增/删除核心记忆，聊天会把启用范围内的记忆作为不可信上下文注入；模型请求写入记忆时必须前台批准。

验收：

- 记忆开关、列表、创建、删除、清空和聊天注入行为一致。
- 写入/修改/删除记忆必须走 `ios.agent.memory_write` 前台确认。
- UI 不暗示已有自动长期学习，除非能解释和撤销。

### GP4: Settings-only Backup

用户导出一份设置备份，重新导入前先看到 preview，并确认它不是聊天记录/附件/技能文件备份。

验收：

- UI 文案和 archive manifest 一致：当前只导出 settings payload。
- WebDAV/本机文件夹操作失败有明确状态；Google Drive/S3 不可在稳定入口中承诺。

## Capability Gate Matrix

| Capability | Code evidence | Release decision | Gate / acceptance |
| --- | --- | --- | --- |
| 聊天主链路 | `ChatView`、`ChatViewModel`；OpenAI-compatible streaming、cancel、error persistence、run record | 保留 | P0。默认只暴露稳定工具；空 API Key/provider 不可用时必须引导设置；生成/取消/失败后历史可继续。 |
| 服务商/API Key/模型：OpenAI-compatible | `ProviderRegistryStore` per-provider Keychain；`SettingsStore` Keychain；`makeProviderSetting()` 当前只构造 OpenAI chat completions | 保留 | P0。发布版明确只支持 OpenAI-compatible chat completions；provider selection 不能清空有效 key；模型默认和聊天实际使用一致。 |
| 服务商模板：Google/Claude/Response API/xAI/MiMo 等 | `ProviderRegistryStore.canActivate()` 明确不允许非当前链路能力；Provider UI 有 unsupported 状态 | 隐藏 | P0。从稳定入口移除或折叠到实验/不可选说明，不作为“可用服务商”展示。 |
| 对话存储 | `IOSConversationStore` 使用 `JsonConversationStorage`；`ConversationStorageView` 真实统计、清理、删除 | 保留 | P0。存储健康、删除确认、历史恢复是首发必需；I/O 失败不能只静默降级。 |
| 文件上下文 | `DocumentAccessStore` selected file grant，TTL/maxUses/maxReadableBytes；`ChatViewModel.attachSelectedFilePreviewToNextMessage()` | 保留 | P0。只保留“一次性选中文件预览”；隐藏任何完整文件系统/长期文件库暗示。 |
| 聊天内网络搜索与网页读取 | `IOSSearchExecutor` 支持 `search_web`、`scrape_web`；DuckDuckGo Lite/Bing HTML；URL 私网/本地地址防护 | 保留 | P1。可作为手动开关能力；默认开关、失败 fallback、结果来源必须可解释。 |
| 搜索服务/API 搜索商与独立 SearchView | `SearchProviderView` 多 provider 但 iOS executor 只支持无 key route；`SearchView` 仍是静态结果 | 隐藏 | P0。独立搜索页和未实现 API provider 不进入稳定入口；要么接真实数据，要么移出可见入口。 |
| 记忆 | `IOSMemoryPersistence`、`IOSMemoryToolExecutor`、`MemoryOverviewView`、`MemoryEditView`；聊天注入最多 20 条 active memory | 保留 | P0/P1。P0 保留新增/删除/清空/写入审批；P1 补编辑体验、可解释范围、冲突恢复。 |
| 同步备份 | `IOSSyncBackup.export()` 只封装 `settings.json`；`SyncBackupView` 支持本机文件夹/WebDAV，Google Drive/S3 unavailable | 补完 | P0 先修文案/范围；WebDAV 必须有独立开关且默认关；Google Drive/S3 隐藏。 |
| 技能 | `IOSSkillFileStore` 可管理 `Documents/skills/*/SKILL.md`；`SkillsView` 可启停；聊天未见 enabled skills 注入 | 实验区 | P1/P2。必须有“启用技能系统”总开关；关闭时不展示正式技能管理入口，不承诺“技能会影响回答”。 |
| MCP | `McpServersView` 可配置/import；`IOSMcpClient` 可 JSON-RPC；`ChatViewModel` 总是声明 `mcp_call`，但 manager 连接状态依赖 sync | 实验区 | P0 先从默认 tool set 移除；必须有 MCP 总开关与 server 级开关，默认全关，开启后才允许连接/声明工具。 |
| WebMount | `WebMountView` 管理 WKWebView stations；`IOSWebMountSettings.globalEnabled` 默认 false；支持 wm_* 受限工具，eval blocked | 实验区 | P0 不在首屏快捷或默认工具目录承诺；保留现有 global switch，并补站点级开关/风险说明，关闭时不可进入正式工具目录。 |
| MiniApp | `MiniAppListView`/repository/bridge/runtime；Bridge 默认禁用 network/search/AI/host write；聊天仅显式请求时解析 JSON | 实验区 | P0 从首屏正式快捷移出；必须有 MiniApp 总开关，默认关；关闭时不解析/保存 MiniApp 输出，不展示为正式入口。 |
| SubAgent | `SubAgentRunner` 注释说明无 key 时是 stub/调用链验证；聊天 tool `subagent_dispatch` 当前总是声明 | 实验区 | P0 从默认 tool set 移除；必须有 SubAgent 总开关，默认关；关闭时不声明工具、不展示运行入口。 |
| 模型议会 | `CouncilRunner` 注释说明 stub 验证；`CouncilView` 可手动启动；聊天 tool `model_council_run` 当前总是声明 | 实验区 | P0 从首屏快捷和默认 tool set 移除；必须有模型议会总开关，默认关；开启前显示成本/多模型依赖。 |
| Remote SSH / 运行环境 | `RuntimeEnvironmentView` 推荐 Remote SSH；`IOSTerminalRuntime` 稳定构建仅 Remote SSH/Local iOS Tools；SSH 仅密码/单命令/host key | 实验区 | P0 不作为普通用户路径；必须有“启用远程执行”开关，默认关；关闭时不可创建远程命令任务。 |
| 权限/工具审批 | `PermissionsApprovalView` 只列 `ios.files.selected_read`、`ios.agent.memory_write`、`ios.webmount.browser`；`ToolPermissionsView` 系统权限很宽 | 保留 | P0。稳定发布只强调已实现工具审批；广泛系统权限列表若无对应用户价值，应隐藏或折叠为高级诊断。 |
| 广泛系统权限目录 | `IOSPermissionModels` 覆盖相机/麦克风/位置/通讯录/日历等大量 capability；多数不是首个成功路径 | 隐藏 | P0。除 file/memory/WebMount 和实际使用的系统权限外，不在普通设置里制造权限焦虑。 |
| TTS | Settings 有 TTS 入口，非本次核心路径 | 隐藏 | P1 前不作为发布卖点；若保留入口，必须只说系统 TTS 设置。 |
| 今日看板/Workspace/Assistants placeholder | 首页 shortcut 有今日看板；`WorkspaceView`、`AssistantsView` 是 placeholder list | 隐藏 | P0。占位入口不能出现在稳定首屏；今日看板如未作为 P0 验收，不作为首发快捷。 |

## P0: Release Blockers

### P0.1 Stable IA and Capability Gate

目的：让用户看到的默认入口与稳定能力一致。

验收：

- 首页快捷入口只保留聊天成功路径直接相关能力；MiniApp、模型议会、WebMount、运行环境、SubAgent、未完成搜索页、placeholder workspace/assistants 不在稳定首屏。
- 设置页把“稳定”和“实验”清楚分区；实验区入口默认不出现在聊天工具目录。
- 设置页中所有非稳定能力必须呈现为开关，而不是普通导航选项；没有显式开关和默认关闭状态的选项不得保留在可见设置列表中。
- 开关关闭时，对应能力不能继续暴露正式子页面、快捷入口、tool declaration、后台连接或自动解析行为。
- `ExecutionSettingsView` 不再笼统写“搜索、记忆、网页、MCP、模型议会和子代理工具可用”，而是按真实 gate 展示。
- `ChatViewModel.currentToolDeclarationNames()` 在默认新装状态不包含 `mcp_call`、`subagent_dispatch`、`model_council_run`、`wm_*`。

### P0.2 Provider/API Key/Model Onboarding

目的：让没有开发背景的用户能配置一个可工作的模型。

验收：

- 无 key 时发送按钮或错误态引导到服务商/API Key 设置。
- OpenAI-compatible provider 的 base URL、API Key、model ID 三件事在一个流程内可完成。
- 非当前 chat chain 可用的 provider 模板隐藏或不可误选。
- Provider key 只进 Keychain；切换 provider 不会覆盖/清空有效现有配置。

### P0.3 Chat Golden Path Reliability

目的：首个问题能稳定答出来，失败也可理解。

验收：

- 新建会话、发送、流式显示、取消、失败重试、完成后保存均可通过测试。
- 默认工具目录只包含稳定工具：搜索按用户设置、记忆按 runtime、WebMount 仅 global enabled；MCP/SubAgent/Council 默认不声明。
- 文件上下文只作为一次性 preview 注入，不承诺长期索引。
- 错误消息避免裸 `Error: ...` 作为唯一用户反馈；至少给出设置/网络/权限方向。

### P0.4 Conversation Storage and Data Safety

目的：用户的聊天记录不因发布版基础缺陷丢失。

验收：

- 启动时加载最近会话；新建/重命名/置顶/删除/清理都有真实持久化。
- 存储目录不可写、JSON 损坏、删除失败都有用户可见提示。
- 删除全部、30 天前清理、清空记忆等破坏性操作均二次确认。

### P0.5 Permission and Tool Approval Surface

目的：只向用户索取和展示真实会用的权限。

验收：

- 普通设置只保留或突出 file selected read、memory write、WebMount browser 三类 tool approval。
- 广泛系统权限目录转入高级诊断或按实际功能触发，而不是一次性展示一长串未关联价值的权限。
- 记忆写入、WebMount cookie/session 清理、文件读取保持前台确认和可撤销解释。

### P0.6 Backup Scope Truthfulness

目的：不要让用户误以为数据已完整备份。

验收：

- 同步备份页明确“当前备份 settings payload，不包含聊天记录、附件、技能文件、MiniApp、memory 文件，除非代码实际包含它们”。
- Google Drive/S3 从稳定入口隐藏。
- WebDAV 只在用户填写并触发操作时联网；失败状态留在页面可见。

## P1: First Minor Release

- 搜索：保留 DuckDuckGo Lite/Bing HTML 路线，独立 SearchView 改为真实数据或继续隐藏；API 搜索商等 executor 完成后再展示。
- 记忆：补 existing memory 编辑、导入/导出、冲突恢复、范围解释；把“自动学习”限制在用户批准的写入路径。
- 同步备份：WebDAV 稳定化，补凭据保存策略、冲突文案、恢复 dry run；再决定是否包含对话/记忆/MiniApp/技能。
- 技能/MCP：如果产品要保留“可扩展 agent”定位，先让 enabled skills 明确进入 prompt/tool policy，再做 MCP 显式连接健康和服务器级审批。
- 运行环境：Local iOS Tools/Remote SSH 作为高级用户能力，补命令审批、输出脱敏、超时/取消、host key mismatch 恢复。

## P2: Experimental Track

- MiniApp：继续作为实验区，要求 HTML validator、bridge permission grant、版本恢复、审计日志、无默认网络/AI/host 写权限。
- WebMount：继续作为实验区，要求 station allowlist、cookie/session 生命周期、登录状态解释、clear session 强确认、禁止 eval。
- SubAgent：要求真实模型输出、可取消、可观察进度、成本/轮数限制、失败不伪造成成功。
- 模型议会：要求真实席位模型、输出展开、成本提示、席位配置、失败/超时和取消。
- Remote SSH/Mosh/iSH：Remote SSH 只能作为高级远程执行；Mosh/iSH 涉 GPL/实验构建，发布前必须完成法律与 App Store 策略审查。

## Parallel Work Packets

### WP-A: Stable Capability Gate and IA Cleanup

推荐优先级：最高，下一步最推荐先开。

范围：

- 调整首页 shortcut、设置分区、执行页 copy 和默认 tool gate。
- 不删除高级功能代码；把不稳定入口改成显式开关 gate，默认关闭，关闭时隐藏/禁用正式入口。

候选文件：

- `iosApp/iosApp/PlaceholderViews.swift`
- `iosApp/iosApp/AppShell.swift`
- `iosApp/iosApp/ExecutionSettingsView.swift`
- `iosApp/iosApp/ChatViewModel.swift`
- `iosApp/iosApp/IOSSharedSettingsStore.swift`

验收：

- 默认首页只承诺 P0 golden paths。
- 默认 tool declarations 不包含 MCP/SubAgent/Council/WebMount。
- 非稳定能力在设置页以开关呈现，默认关闭；没有开关的选项不保留在可见设置列表。
- 实验区入口只有打开对应开关后才可继续进入，且不会进入首个用户成功路径。

可直接转成后续 `/goal`：

```text
/goal 实现 AmberAgent iOS 稳定发布 Capability Gate：把 MiniApp/WebMount/SubAgent/模型议会/Remote SSH/技能/MCP 等非稳定入口改成显式开关，默认关闭；关闭时隐藏或禁用正式子页面、首屏 shortcut、后台连接、自动解析和 ChatViewModel tool declaration；修正 Execution 文案，不再把未开关能力称为可用。不要删除功能代码。验收：默认新装 currentToolDeclarationNames 不含 mcp_call/subagent_dispatch/model_council_run/wm_*；设置页所有非稳定能力都有 switch；没有 switch 的非稳定选项不保留在可见设置列表；首页只保留 P0 golden path 入口；运行 targeted tests 与全量 iosAppTests。
```

### WP-B: Provider/API Key/Model First-run Flow

范围：

- OpenAI-compatible 设置流程、空 key 引导、provider activation 防误选、模型默认一致性。

候选文件：

- `iosApp/iosApp/ProviderRegistryStore.swift`
- `iosApp/iosApp/ProvidersView.swift`
- `iosApp/iosApp/ModelDefaultsView.swift`
- `iosApp/iosApp/ChatView.swift`
- `iosApp/iosApp/ChatViewModel.swift`

验收：

- 无 key 不能让用户陷入纯错误消息。
- 非当前链路 provider 不可误选为可聊天。
- Keychain 行为有单元测试覆盖。

### WP-C: Conversation Storage and Backup Truth

范围：

- 存储健康提示、损坏/失败可见、备份范围文案与 payload 一致。

候选文件：

- `iosApp/iosApp/IOSConversationStore.swift`
- `iosApp/iosApp/ConversationStorageView.swift`
- `iosApp/iosApp/SyncBackupView.swift`
- `iosApp/iosApp/IOSSyncBackup.swift`

验收：

- 存储失败用户可见。
- 备份页不暗示包含未导出的数据。
- 现有 conversation/sync backup tests 通过。

### WP-D: File Context and Search Stabilization

范围：

- selected-file preview 体验、网络搜索开关和错误解释、独立 SearchView 隐藏或接真实数据。

候选文件：

- `iosApp/iosApp/DocumentAccessStore.swift`
- `iosApp/iosApp/IOSLocalToolExecutor.swift`
- `iosApp/iosApp/ChatViewModel.swift`
- `iosApp/iosApp/IOSSearchExecutor.swift`
- `iosApp/iosApp/SearchServicesView.swift`
- `iosApp/iosApp/PlaceholderViews.swift`

验收：

- 文件权限过期/大文件/二进制都有用户可读反馈。
- SearchView 不再展示静态假结果。
- 私网/本地 URL scrape 防护测试继续通过。

### WP-E: Memory Consent and Editing

范围：

- 记忆新增/删除/清空/写入审批稳定化，补 existing memory 编辑和恢复提示。

候选文件：

- `iosApp/iosApp/MemoryOverviewView.swift`
- `iosApp/iosApp/MemoryEditView.swift`
- `iosApp/iosApp/IOSMemoryPersistence.swift`
- `iosApp/iosApp/ChatViewModel.swift`
- `iosApp/iosApp/PermissionsApprovalView.swift`

验收：

- 写入/编辑/删除记忆均可被用户确认或拒绝。
- 聊天注入范围与设置页一致。
- memory persistence tests 通过。

### WP-F: Advanced Experimental Bundle

范围：

- 技能/MCP/WebMount/MiniApp/SubAgent/模型议会/Remote SSH 保持实验区，补总开关、子能力开关、风险文案、显式启用和健康状态。

候选文件：

- `iosApp/iosApp/SkillsView.swift`
- `iosApp/iosApp/McpServersView.swift`
- `iosApp/iosApp/IOSMcpManager.swift`
- `iosApp/iosApp/WebMountView.swift`
- `iosApp/iosApp/IOSMiniAppBridgeRuntime.swift`
- `iosApp/iosApp/SubAgentsView.swift`
- `iosApp/iosApp/CouncilView.swift`
- `iosApp/iosApp/RuntimeEnvironmentView.swift`

验收：

- 实验能力不会进入默认 tool set。
- 每类实验能力都有默认关闭的总开关；可细分能力还要有子开关。没有开关的实验选项不保留在可见列表。
- 每类实验能力都有权限边界、失败解释。
- 不要求真实账号/API Key 的测试用 stub；真实服务只做手动 smoke。

## Verification Plan

本次文档 goal：

```bash
git diff --check
```

后续实现 goal 至少必须跑：

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -showdestinations
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=<available iPhone simulator>' test
```

建议的 targeted tests：

```bash
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=<available iPhone simulator>' \
  -only-testing:iosAppTests/IOSConversationStoreTests \
  -only-testing:iosAppTests/ProviderRegistryStoreTests \
  -only-testing:iosAppTests/ChatViewModelSelectedFileContextTests \
  -only-testing:iosAppTests/IOSPermissionStoreTests \
  -only-testing:iosAppTests/IOSMemoryPersistenceTests \
  -only-testing:iosAppTests/IOSSearchExecutorTests \
  -only-testing:iosAppTests/IOSSyncBackupTests \
  test
```

高级能力实现后再追加：

```bash
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=<available iPhone simulator>' \
  -only-testing:iosAppTests/IOSMcpClientTests \
  -only-testing:iosAppTests/IOSMcpManagerTests \
  -only-testing:iosAppTests/IOSMiniAppBridgeRuntimeTests \
  -only-testing:iosAppTests/IOSMiniAppRepositoryTests \
  -only-testing:iosAppTests/IOSSSHRuntimeTests \
  -only-testing:iosAppTests/IOSSharedSettingsStoreSubAgentOverrideTests \
  -only-testing:iosAppTests/IOSSharedSettingsStoreCouncilSeatTests \
  test
```

手动 smoke：

- Fresh install: configure OpenAI-compatible provider, send first chat, relaunch, continue history.
- No key: attempt chat and verify guided recovery.
- File context: choose text file, attach preview, send question, verify one-shot behavior.
- Memory write: ask model to remember a preference, approve and deny once each.
- Backup: export settings-only backup, inspect preview, import after confirmation.

## Stop / Pause Conditions

暂停并回到产品取舍，而不是继续工程化：

- 必须决定 AmberAgent iOS 是“普通聊天助手优先”还是“开发者远程 agent 优先”，代码无法推断时不要替产品决定。
- 需要真实付费 API Key、WebDAV 账号、MCP server、WebMount 登录态或 SSH 主机才能验证的事项，只能列为手动 smoke，不能伪造发布结论。
- 需要删除大量高级功能代码才能达成发布收口；当前计划只要求隐藏/降级/实验区，不做破坏性删除。
- 发现未提交冲突或用户改动影响 `docs/ios-release-readiness-plan.md` 之外的文件，且无法安全只改文档。
- Mosh/iSH/GPL、远程命令执行、网页账号会话读取涉及法律/App Store 审核策略时，必须先完成专项评审。

## Self-review

Round 1：首个用户成功路径已覆盖：服务商/API Key/模型 -> 新建聊天 -> 得到回复 -> 历史保存 -> 可继续。

Round 2：每个 P0 都有验收标准，并且 WP-A 到 WP-F 均可直接拆成后续 `/goal`。最推荐先开的实现 goal 是 WP-A，因为它先切断默认入口与实验能力之间的误承诺，能降低后续所有功能修复的发布风险。

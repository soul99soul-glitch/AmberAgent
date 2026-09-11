# Android 追齐 iOS 的详细实施计划

依据[双端深度调研](/Users/mi/Downloads/AI/AmberAgent/android/docs/audits/2026-09-09-android-ios-parity-research.md)，以 iOS `1023e8a` 与 Android `ab984f6 + 当前 WIP` 为固定基线。源码调研已完成，现按用户授权进入实施。阶段划分、状态、subagent review、验证结果与剩余项持续记录在[执行台账](/Users/mi/Downloads/AI/AmberAgent/android/docs/plans/2026-09-09-android-ios-parity-execution.md)。

## 1. 目标、范围和实施原则

目标是让 Android 用户完成同样的手机端核心场景：账号登录、发送和文件上下文、子代理协作、网页登录接管、小应用系统交互、创作恢复、结果找回、备份和系统入口。Android 保留原生返回、权限、通知和现有优势，不复制 Apple 控件或删除 Code Assist/Vertex/Termux/cron。

研究台账的 D01–D20 进入手机追齐范围；Q01–Q11 按风险作为相关批次的稳定性工作；E01 Watch、E02 健康/运动/闹钟等单列扩展。NativeTimeline 的实现方式、iSH/AmberShell 和灵动岛不逐字移植。跨端 Novel/全量迁移先做真实格式验证，不把“都用了 Markdown/JSON”视为互通。

优先复用现有 ChatService、Room、WorkManager、WebViewPool、MiniApp sandbox、ContinueCandidate 和工具注册表。不切换默认聊天 kernel，不建立第二套任务数据库，不一次打开全部 flags，不自动新增依赖。每个修改批次只负责一个可以独立验收的行为闭环。

## 2. 工作包、依赖与估算

估算按熟悉代码的开发者计人日，包含实现、定点测试和一次修订。账号/设备/服务器等待、未发现的兼容性问题和新增依赖决策不算确定工期。P1/P2 是产品优先级，Wxx 是工作包编号。

| 工作包 | 范围 | 依赖 | 人日 |
| --- | --- | --- | --- |
| W01 | 固定可运行 build、合成数据、旅程基线和回归入口 | 当前研究 | 2–3 |
| W02 | 模型候选刷新 + 配置阻塞引导（D02/D11） | W01 | 2–3 |
| W03 | 附件导入反馈 + 历史错误 + 首页筛选（D10/D12） | W01 | 4–6 |
| W04 | Continue/通知精确定位（D06/D07/D08） | W01；与 W14 协调 | 3–5 |
| W05 | 任务持久化错误 + 记忆 CAS（Q06/Q07） | W01 | 2–4 |
| W06 | 恢复重试、线程归属、写入隔离（D03/Q01/Q02） | W01 | 4–7 |
| W07 | 子代理默认编排 + 新操作卡片（D09/D14/Q09） | W05 | 4–6 |
| W08 | WebMount 可见会话、控制权、登录接管（D15/D16） | W01 | 6–9 |
| W09 | WebMount 多窗口、回执、站点确认（D17/D18） | W08 | 4–6 |
| W10 | MiniApp 能力发现、权限和基础系统方法（D19） | W01 | 4–6 |
| W11 | MiniApp 语音/屏幕/分享/外链生命周期（D19） | W10 | 4–6 |
| W12 | Grok 登录与真实模型请求（D01） | W02 | 4–6 |
| W13 | Antigravity 独立登录模式与适配（D01） | W02；复用 W12 测试结构 | 3–5 |
| W14 | Novel 中断恢复和损坏状态呈现（D08） | W01/W05；与 W15 协调 | 5–8 |
| W15 | Novel/对话交换真实契约与 fixture（N02） | W01 | 3–5 |
| W16 | 日历补全、快捷入口、独立 TTS（D04/D05/D13） | W04 | 4–6 |
| W17 | 宿主管理的 Remote SSH（D20） | W05；客户端可行性验证 | 5–9 |
| W18 | 无效设置、编辑保护、工作台错误、flags（Q03/Q04/Q05/Q08/Q10） | 对应功能工作包 | 2–4 |
| W19 | 两端旅程验收、性能/无障碍、升级与发布构建（Q11） | 分批介入；最终依赖上述范围 | 5–8 |

手机范围合计 **70–112 人日**。这是初始估算，不是交付日期承诺；W15 如果证明需要全新迁移器，额外实现需重新估算，不能用 3–5 天的验证额度包办完整迁移。W17 若缺可复用 SSH 客户端，也必须重新估算，不能为了维持工期跳过 host-key 校验。

推荐两名 Android 开发分两条线，另由 iOS 维护者配合对端 fixture/行为验证、测试人员分批介入：约 **8–13 周**完成手机范围；单人串行约 **14–23 周**。第一轮可见体验改善应在前 **1–2 周**形成可安装内测包，不等所有专项一起交付。所有时间都是人员与环境到位后的计划假设。

## 3. 工作包的具体实现与验收

### W01：冻结实际构建与合成测试数据

- 固定两端 SHA、dirty 状态、设备 build ID、启动入口和普通用户配置；记录源码版与已安装版是否相同。不要重新做一遍已完成的目录调研。
- 建立少量无隐私场景：120+ 消息长会话、PDF/多文件、一个两轮子代理任务、一个测试登录站点、一个系统能力 MiniApp、部分完成 DeepRead、一个小说项目/中断 job、加密备份。
- 给 D01–D20 逐条设置 `待实现 / 实现中 / 源码通过 / 设备通过 / 延后并说明原因`；只在设备和持久状态都通过后标完成。
- 验证起点：保留研究中的 83 项 Android 定点测试结果；增加后续改动真正需要的失败用例。iOS 测试在其自身允许的构建环境执行，不能拿旧历史测试数量充当本次结果。
- 完成条件：开发和测试可重复启动同一合成旅程，记录实际结果；缺设备时先推进 JVM/domain 工作，不虚报 UI 验收。

### W02：真实模型候选刷新与发送阻塞引导

- 改动入口：[SettingProviderConfigPage.kt](/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderConfigPage.kt:893)、模型列表页、ChatPage/ChatVM 当前配置判定。
- 先用 fake provider 返回一个新增模型，复现“提示成功但列表没新增”。将 fetched candidates 发布到既有候选状态；用户选择/默认模型独立保留，处理旧请求晚返回。
- 按缺模型、Key、地址、禁用和未登录显示持续原因与修复入口；保留无需模型的本地路由。不要让错误 placeholder 覆盖用户输入。
- 验收：新模型可见可选，旧选择不变；空列表、401、超时、离页分别反馈；配置修复后原草稿可直接发出。
- 回滚：可回退 UI/候选发布逻辑，不迁移模型 ID 或删除配置。

### W03：附件状态、历史失败与首页快速筛选

- 改动入口：ChatInput/ChatInputAttachments、现有 DocumentAsPromptTransformer、HistoryPage/VM、SearchPage/VM、SessionHomePage/VM。
- 为每个附件保留本次选择的会话与导入身份；显示导入中/可用/截断/失败，失败可单项重试或移除。复用现有文档提取，不引入第二个解析器；保留多文件功能。
- Flow 错误与 Paging loadState 分开显示，保留已加载项；本地搜索与索引重建错误分别处理。首页增加轻量列表筛选，保留全文搜索入口。
- 测试：导入中切会话、一个文件复制失败、取消、权限失效；上游读取失败、Paging append 失败、搜索重建失败。
- 验收：无串会话、无丢草稿、失败不伪装空列表；可看到文件名及必要状态，清除首页查询回到原位置。

### W04：统一工作找回与精确深链

- 改动入口：ContinueCandidate/ContinueRoute、DataSourceModule、SessionHome 路由、ImageGenerationContinueSource、DeepReadContinueSource、NovelWorkspaceGhostwriteWorker 通知。
- 增加 Novel source/route；MiniApp 增加最近 runner 投影；ImageGen 从持久会话/结果关联补 conversation/message/toolCall anchor，支持最近已完成结果。避免高频全库扫描，复用已有 DAO/索引。
- DeepRead 保留原来源及“查看/继续”意图，仍只补缺失阶段；如果来源当前无法从存储重建，先补最小可持久字段和迁移默认值，不新建全局调度器。
- Novel 通知与首页使用相同 project/branch/job 路由，进入后由真实 owner 判断是否可恢复；导航本身不承诺已恢复执行。
- 验收：重启、原对象删除、旧版本缺字段、任务已结束、重复点击、多个同名任务；路由不会误开最新会话或别的任务。

### W05：现有任务与记忆的写入契约

- 改动入口：[AgentTaskStore.kt](/Users/mi/Downloads/AI/AmberAgent/android/feature/task/src/main/kotlin/app/amber/feature/task/AgentTaskStore.kt:61)、调用 scheduler、SettingAgentMemoryVM/MemoryRepository。
- 任务持久化失败必须返回失败，成功写入后再发布内存状态；复用现有原子文件模式。明确少量 nullable 字段的清空语义，成功重试不残留旧 error/code。
- 记忆编辑/删除携带打开时的 revision，调用已有 CAS；冲突时保留用户文本，展示最新版本供重新处理。
- 测试：写文件失败、残缺文件、重启、失败后成功；revision N 编辑期间后台更新到 N+1。断言真实存储和 UI 状态，不只断言文案。
- 回滚：保留旧快照读取兼容；不删除未知/损坏记录，不自动覆盖并发新版本。

### W06：备份恢复的可重试性和写入隔离

- 改动入口：BackupVM、SyncArchiveManager、备份预览页、ChatService 与实际后台写入者。
- 统一验证对象生命周期：保留原始加密归档，apply 失败后失效旧明文验证对象，再次操作重新验证；取消/离页清理临时副本，不删除原文件。
- 文件解析、哈希、解密、表写入放到合适 dispatcher；验证/apply 互斥，UI 正确反映不可取消的提交阶段。
- 在真实 owners 设置恢复门，阻止新写入、等待/终止已有安全写入；导入后让旧 callback/缓存失效。复用现有 restore journal，明确文件/DB 与 secrets/settings/FTS 部分失败的恢复步骤。
- 故障注入：apply 首次失败再试、后台流式写回、文件已提交而设置失败、阶段间进程回收、空间不足、错口令。
- 验收：没有失效 payload 重用；旧内存不能覆写新导入；部分成功不当作可盲目全量重放；大备份仍可操作 UI。不要仅删除 exitProcess 弹窗来宣称完成。

### W07：正式开放子代理编排并补完整状态卡

- 改动入口：SubAgentTools、SubAgentManager、Room thread graph/mailbox、CapabilityFlags、ChatMessageCot/子代理卡。
- 基线已通过的 [SubAgentThreadGraphIntegrationTest](/Users/mi/Downloads/AI/AmberAgent/android/app/src/test/java/app/amber/feature/subagent/SubAgentThreadGraphIntegrationTest.kt:1)和 [RoomThreadGraphStoreTest](/Users/mi/Downloads/AI/AmberAgent/android/app/src/test/java/app/amber/feature/subagent/RoomThreadGraphStoreTest.kt:1)继续作为回归，补并发 drain、消息入队后崩溃、followup 与取消竞争；Room 事务下已不可能的竞争用可执行测试证明，不臆造 bug。
- 确认 followup/send/interrupt 的参数、父子关系、持久化和旧版本兼容后开放正常入口/默认值，保留用户显式关闭。工具名可保持 Android 风格，但语义要对齐。
- 新操作进入现有任务卡投影；区分 accepted/queued、当前 turn running、最终结果；不能把一次 send_message 回执当成子任务完成。
- 验收：两轮任务、执行中补充、打断再继续、进程重启回看、多子任务隔离；关闭 flag 后数据保留，旧会话仍可读。

### W08：Agent 浏览器的可见会话与人工接管

- 改动入口：SessionHandle/WebViewPool、WebMount 工具、现有网站设置/登录控制器、聊天任务卡/路由。
- 在现有 WebMount owner 增加最小会话记录：sessionId、conversationId/runId、脱敏页面摘要、controlOwner、lease、needsReopen。复用持久化基础，只存恢复 metadata，不序列化 WebView。
- 暴露“观看页面/接管/交回/重开”；接管同一 session，Agent 修改前校验控制权；run 结束/取消/过期清理 owner。
- 登录或验证码需要用户处理时返回结构化 handoff 与继续条件，交回后重新观察页面。网站功能总开关按既有用户选择生效，不擅自打开所有站点。
- 验收：原页面 cookies/窗口连贯；用户控制期间 Agent 无法同时操作；错误 conversation/run 不能接管；池淘汰/进程重启显示重开需求；连接失败可返回设置。

### W09：多窗口、动作结果和站点确认

- 依赖 W08 的同一会话 owner，复用登录 controller 的 popup/cookie 经验；把 active window/dialog ID 放入会话状态，不做另一套孤立 WebView。
- 处理 JS alert/confirm/prompt 和 SSO popup；取消/返回/切窗口时失效旧回调。动作前校验 snapshot/ref，动作后返回 dispatched、verified、unknown/mayHaveApplied 和可用 postcondition 证据。
- 保留明确兼容路径；Agent 自动化默认用最新语义目标，不能把网络变化等同业务成功。未知外部副作用不自动重试。
- wm_site_add 先展示网站/域名预览，确认后写 registry；取消不写，删除保持现有确认。
- 验收：受控测试站覆盖 stale ref、多窗口、dialog、动作后桥断、预先满足后置条件；再用真实 SSO 做设备验证。

### W10：MiniApp 系统契约、发现与基础方法

- 改动入口：MiniAppModels、JS SDK、MiniAppBridge、sandbox/grants/settings、runner；不创建新的权限系统。
- 冻结 bridge 版本、方法名、参数/返回、unsupported/denied/invalid/busy/closed 错误。实现 app.info/app.capabilities，使小应用先探测，不在发现阶段弹授权。
- 首批 haptics、device.getInfo/getBattery、qrcode.generate；生成二维码优先用仓库已有工具，发现缺依赖时单独评估，不自动引入。
- manifest 声明、全局设置、每 app grant、native owner 均接线；参数限定、调用节流、审计仅记录必要动作摘要。
- 验收：同一 fixture 在两端探测能力、拒绝未授权、返回真实电量/二维码；旧 MiniApp 无新 permission 仍可运行；模拟器不支持震动时诚实返回，真机触感另验收。

### W11：MiniApp 有状态系统能力与生命周期

- 在 W10 同一 owner 加 screen brightness/keepAwake、speech voices/speak/stop/pause/resume、share、openURL。只作用于当前 runner/窗口，避免全应用共享状态互相覆盖。
- 离页、后台、关闭和 runner 替换时释放语音、亮度/唤醒租约和挂起操作；恢复亮度不能覆盖其他 owner 后来的修改。
- share 打开系统分享面板，不代表消息已发送；openURL 展示目标并按能力规则确认，使用验证后的地址；拒绝和取消按契约返回。
- 验收：两个 runner 交替、后台/锁屏、语音中关闭、分享取消、无可处理外部应用、权限撤销；native 状态与 Promise 结果不串会话。
- UI 和真实系统行为一起验收，不能仅以 JS mock 测试通过宣布完成。

### W12 / W13：补 Grok 与 Antigravity 登录，保留现有账号方式

- 改动入口：ProviderSetting、现有 OAuth/auth store、Provider 适配器、服务商详情、发送前配置判定与真实 ChatService 请求链。
- W12 先对齐 Grok 登录、凭据保存/退出、模型发现和当前请求适配；W13 新增独立 Antigravity 模式，不改写现有 Google Code Assist 枚举和值。
- 登录采用账号/服务商绑定，刷新 single-flight 与冻结请求参数配合，防并发切模型/切账号混用凭据；失败保留用户原可用配置，备份继续脱敏。
- 开发前核对当前协议及官方可用材料，使用已有 HTTP/凭据存储；不将 iOS hardcoded model 列表当作服务承诺。
- 验收矩阵：登录/取消/退出、凭据过期、401、刷新竞争、多 provider 同时发送、工具循环、取消、流式失败、无视觉能力模型收图。两端使用同一合法测试账号但不复制私人 token。
- 发布条件：实际账号 smoke 通过；无真实账号只可标“实现和模拟测试通过”，不能标完整可用。

### W14：Novel 启动协调与中断恢复

- 改动入口：NovelWorkspaceGhostwriteJobs/Controller/Worker、现有 ledger/runtime、项目页与启动初始化；保留 unique WorkManager/executionId 机制。
- 先画出当前写稿→候选→提交 ledger→进度写入的阶段，逐点 kill/抛错，确认 WorkManager 重投递已覆盖什么；只补缺失的 partial/interrupted/提交回执和协调逻辑。
- 启动时核对 durable job、正在执行 work 和 ledger，修复陈旧 running，坏 job 隔离并可见；旧 execution 不能发布到新任务。手改/计划变化后候选是否仍有效须按 digest/revision 验证。
- W04 提供导航，W14 决定下一步可恢复/重试/需用户处理；首页不能直接擅自恢复有未知副作用的任务。
- 验收：已提交章节不重做，未提交 partial 有明确处理，失败/暂停/取消不混淆，通知重进同一 job；质量失败、回退、手动编辑后计数和 candidate 正确。

### W15：真实跨端 Novel/对话交换契约

- 限定第一轮为验证与格式决策：两端各创建合成项目/会话，经真实 exporter 产生样本，并记录来源 SHA、schema、文件树和必要元数据。
- 比较 project/branch/tree/object/engine/ledger、候选/正式稿、日期/UUID/枚举、附件和线程关系。区分运行内部格式与可交换格式，不要求内部存储强行相同。
- Android 导入后修改再导出，由同一 iOS 基线重新导入；两端 runner 分别给证据。修正手写 fixture 与过期 canary 的说明，不删除有价值的旧 wire 回归。
- 验收：有明确可支持的数据子集和不支持说明；损坏/过新版本拒绝且不污染原项目。完整 Android restore 的数据集保护不解除。
- 若需新增交换迁移器：在该工作包结论中给出字段映射、兼容策略、最小实现和增量估算。当前不能承诺全量备份互导或实时同步。

### W16：日历、快捷入口和独立 TTS

- 日历在现有 CalendarAccessTools 补按稳定 ID 更新/删除；预览准确对象和变更，不靠同名匹配。保留既有审批和系统授权机制。
- 快捷方式复用 W04 路由，提供新建、最近会话、当前任务、固定提示动作；冷启动/对象删除时有明确回退。外部输入不绕过已有发送授权。
- 独立 TTS 页面复用 W11 的系统语音能力中适合共享的最小部分，提供语速、试听和停止；没有 W11 时保持模块边界，不新建通用音频平台。
- 验收：日历权限拒绝、事件被外部删除、改期冲突；快捷方式连续点击/旧数据；TTS 无引擎/取消/离页释放。
- Apple Reminders、HealthKit、WorkoutKit 和完整聊天语音交互不被该小工作包冒名交付，见扩展范围。

### W17：宿主管理的 Remote SSH

- 首先核对现有 Alpine/Termux/已声明库能否复用 SSH 客户端，形成具体可行实现。若无法满足稳定 host-key 验证、凭据保护、输出与取消契约，记录依赖需求并重估；不写自制 SSH 协议。
- 在 TerminalRuntimeKind/profile/现有 job owner 增加远端模式，持久化 endpoint 和受信指纹，秘密进入现有 Keystore 保护；调用前探测/信任，变更指纹必须阻断。
- 复用日志、状态、通知和取消；连接中断不伪造远端进程已停止，重启后按真实证据显示 unknown/interrupted。第一版明确非 PTY。
- 验收：受控 SSH server 的首次信任、mismatch、认证失败、超时、stdout/stderr、退出码、取消、断网、重启；输出有上限，日志与快照无凭据。

### W18：修掉误导入口与未闭合设置

- 清理 Live voiceInputEnabled 的无效正式入口；保留设置数据兼容，不能将系统试听接线冒充录音转写。
- MiniAppSourceEditor 返回、点外部、关闭按钮共用未保存规则；保存失败不退出。Synara 增加 loading/error/retry/回连接设置。
- 列出 10 flags 的真实消费者/默认/依赖/升级规则，逐项决定正式开放或保留实验。ThreadGraph 依赖 W07；SyncProvider 依赖 W06；Responses 恢复先跑服务端 COMPLETED + 本地 STARTED effect 混合状态测试，未闭合继续关闭。
- 验收：每个保留设置都能改变真实行为；升级不覆盖显式 false；关闭能力保留用户产物；无消费者开关不成为功能缺失统计。

### W19：持续回归、性能、无障碍和发布验收

- 普通 Kotlin/domain 变更运行相关 JVM tests 与 app compile；跨 UI/生命周期变化做 compose/设备旅程，最终正式变体 assemble。复用 native 专项工作流，不使所有文档变更触发重构建。
- 固定数据/设备跑长会话分页与流式阅读、输入法切换、浏览器接管、MiniApp 前后台、小说中断、备份恢复。记录本地 UI 耗时与网络等待，分别判断。
- TalkBack 检查父级合并语义、返回/新建/发送/停止/附件/模型/卡片动作；量实际触控区域，覆盖字体 2.0、深色、横屏/分屏/折叠尺寸。
- 安装升级保留原会话、flags、凭据引用、MiniApp/Novel 产物；检查 release 混淆/native/权限/签名配置。在最终产物上复测关键旅程，不以 debug 单测代替。
- 发布门：D01–D20 范围逐条有证据或明确延后记录；数据/归属/未知副作用类阻断问题解决；性能阈值依据 W01 实测基线约定。没有设备结果的条目保持待验收。

## 4. 推荐排期与首批交付

| 时间窗口（两名 Android 开发假设） | 工作与产出 |
| --- | --- |
| 第 1–2 周 | W01；W02/W03 中高频反馈；W04 精确路由；W05 定点修复。W08 会话 owner 与 W10 契约开始。产出第一版日常体验内测包 |
| 第 3–4 周 | W07 子代理编排；W08 登录接管第一条完整流程；W10 基础系统方法；W06 恢复。W15 产出真实格式差异，尽早暴露迁移工作量 |
| 第 5–7 周 | W09 浏览器异常闭环、W11 有状态系统能力、W12/W13 账号、W14 小说恢复，依赖项按小批次串行集成 |
| 第 8–10 周 | W16/W17 系统入口和 SSH、W18 设置收束；主线功能全部进入设备回归 |
| 第 11–13 周（缓冲上限） | 处理设备/升级/账号边界和必要返工，W19 release 验收；无返工时可提前结束 |

排期是容量规划，不代表每项都可同时开工。建议开发 A 主负责聊天/Continue/创作/设置，开发 B 主负责 runtime/WebMount/MiniApp；账号与备份在对应主线空档按文件所有权安排。两条线都涉及 ChatService/注册表时串行合并，避免互相覆盖当前 WIP。

首批建议按独立小批次落地：

1. 模型刷新与配置提示（W02）。
2. 附件/历史失败反馈（W03），首页筛选可单独交付。
3. Continue/通知深链（W04），恢复逻辑仍由 owner 判断。
4. 任务持久化/记忆 CAS（W05）与备份失败重试（W06）分别交付。
5. 子代理编排（W07）、浏览器接管（W08）、MiniApp 系统能力（W10）各自形成完整 feature 批次。

每批先有失败用例，再做最小行为修改；完成后更新对应 D/Q 状态与测试证据，不等待整个计划结束才反馈。

## 5. 平台扩展的单独计划

| 扩展 | 最小产品结果 | 前置条件 | 初始估算 |
| --- | --- | --- | --- |
| E01 可穿戴伴侣 | 手表提问/随手记、手机执行、结果/任务回看、取消/受限审批、可靠交还手机 | 手机 durable task/深链稳定；确定 Wear 设备范围和测试硬件 | 15–25 人日，另留设备适配缓冲 |
| E02 健康摘要/闹钟/运动/提醒等效 | 用户可授权读取有限健康摘要；可管理约定的提醒/计划；来源和系统限制明确 | 官方 SDK/设备/权限可行性；与现有任务/日历复用策略 | 10–18 人日的限定第一版；无法用一个数字包办所有生态 |
| 交换迁移器 | 经 W15 验证后，实现明确版本/数据子集的双向迁移 | 实际 schema 差异、两端 exporter/runner | 暂留 5–10 人日预算占位，必须以 W15 结果重估 |
| 交互 PTY/TUI | 真实交互终端与 resize/输入/取消 | 明确产品需要及现有 Termux/运行时可复用性 | 单独调研，未纳入手机 managed SSH 基础版 |

这些扩展有实际 iOS 能力或协议背景，但不应阻塞前几周的手机体验改善。若需要“包括手表和健康生态的全部一致”，按以上专项继续推进，不能在手机版完成后直接宣布全平台追齐。

## 6. 交付与回退规则

每个批次交付改动目的/文件、对应 D/Q、定点与设备验证、数据兼容和剩余限制。开关回退只关闭入口/新执行，保留产物与读取兼容；涉及存储变化先设计旧数据默认值和故障恢复。未知外部动作结果保留核对入口，不以自动重放掩盖失败。

现有 Android 39 个已跟踪 WIP 不自动提交、不回退、不覆盖；尤其飞书 Office Pro/DocRadar 正在移除，不因追齐无依据恢复。iOS 仅为只读对照源，产品实现留在 Android 项目；任何跨端共享制品须满足稳定、平台无关且已有两端消费者的条件，不能以本计划为由抽象新的大核心层。

“追齐完成”的判据是固定 iOS 基线下，所选 D01–D20 场景通过相同输入、结果、状态和恢复验收，并明确列出平台扩展及未支持范围。编译通过、目录齐全、测试名称带 parity 都不能单独证明完成。

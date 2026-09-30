# Android 性能优化：最终计划与执行裁决

> 当前状态：本机实现、阶段 review、原有 UI 长流程和本地 SSE 正式链路验收已闭环；P3 真机与真实 provider、长期运行仍未完成，F15 的 UI 缓存命中未验证。

日期：2026-09-29。本文描述最终方案；测量结果见 [验收报告](2026-09-29-android-performance-results.md) 与 [结构化证据](2026-09-29-android-performance-evidence.json)。

## 基线、分工和约束

- Android 原 HEAD：`04d77a8fbc737dee71ce3d343bd888f86da99e13`。原有主题、顶栏、回顾等 WIP 被完整复制到独立 worktree；`44ff09902b893f9900232c966737006be2fe04a2` 只是 WIP 快照，性能差异从它之后计算。
- 调查与架构顾问：Synara `claudeAgent / opus`，能力目录解析为 Opus 5.5，`effort=high`。执行和独立阶段审查：GPT-6 Sol，`xhigh`。主 agent 负责证据、裁决、集成与验证。
- 仅审 Android 真实调用链；没有读取兄弟 iOS/Core 仓库。保留原有设计、动画、内容、恢复门控、审批和持久化语义。
- 按文件分工并行实现；Gradle 与设备验证串行。每阶段执行、审查、复核问题、修复，再收敛。构建有超时和独占锁，不无限重试。
- 复用既有测试和性能日志。保留一个新增 Store 行为用例、历史/解析探针和一个本地 SSE 单场景 opt-in 探针；复用并修正既有 UI 与 durable 夹具，保留原行为断言。临时议会测量没有混入生产代码。

## 证据口径

| 层级 | 本次可得结论 |
| --- | --- |
| 源码 | 触发路径、线程、重复工作、状态与锁契约；不能等同帧率提升 |
| JVM/编译 | 定点行为回归、类型与接线；不能代替真实 provider 或界面 |
| Android 模拟器 | 真实 Room/ChatService/Compose 路径、固定负载对照、截图；Debug 时延不等于真机 |
| 真机/账户 | 本次没有物理设备和真实 provider 验收，相关候选保持待测，不据此改架构 |

已确认并保留的机制：聊天 33ms 聚合、议会 32ms 文本节流、流式 checkpoint 10s、Markdown 后台流式解析与有界缓存、时间线前缀复用、节点身份保持、reverseLayout 锚点、首窗 80/追加 40 节点分页、Room 线程解码、首页摘要 Paging、技能缓存和按需详情订阅。

## 全面调查清单与最终处置

| ID | 领域与发现 | 最终处置 |
| --- | --- | --- |
| F1 | 结束阶段同一窗口重复写入及 FTS 重建 | 已减少等价重复写，保留原 restore writer 门控 |
| F2 | 流式通知被过滤前已创建 PendingIntent | 已延迟到实际通知配置时创建；签名变化仍立即发送 |
| F3 | Sandbox 活动随文本 delta 重复派生、反复解析输入 JSON | 已按工具身份投影去重并复用输入解析；输出原已有缓存 |
| F4 | 长历史首开/分页在组合期同步解析 Markdown | 已实测后实施后台准备、就绪显示、同键解析合并；旧页采用只读预读 |
| F5 | 每 10s 重写整个已加载窗口 | 不做增量持久化；缺成本证据且涉及 FTS/删除/恢复契约 |
| F6 | 子代理 live 发布按尾消息引用跳过 | 撤回该方案：尾消息每 flush 都新建，不能靠引用获得收益；不宣称并发性能已全面实测 |
| F7 | 发送后查找逐节点创建 UUID 字符串 | 已改成一次解析规范 UUID，再反向查找 |
| F8 | ChatPageContent 高频整体求值 | 未改架构；需真机重组/布局证据 |
| F9 | 启动、主题纹理、Haze、快捷方式冷启动 | 未改视觉与启动架构；保留真机 Profile 项 |
| F10 | 不可见首页 Paging 是否因 checkpoint 重载 | 读码区分空搜索与非空搜索：默认分页不等同每次重查；保留非空搜索的摘要 Flow 存在后台重查路径，成本待 SQL trace，不改生命周期 |
| F11 | 发送时全窗口 FTS 重建 | 不采用缩小索引方案：不能牺牲中断消息即时可搜索性 |
| F12 | 分页长会话首 token 前重复全量加载 | 已将加载、sanitize 和必要持久化集中到 resolver 唯一入口 |
| F13 | 小说代笔轮询重复遍历、读未变文件、加载账本 | 已收敛目录遍历、复用一次账本、缓存运行中列表派生 |
| F14 | 议会高频更新是否持续取消在途写入 | 假阳性：正式写前已清空 persistJob；测量成功，不改生产 |
| F15 | 代码块重新进入组合后重复高亮 | 已添加有界、按真实渲染输入区分的结果缓存 |
| F16 | Mermaid/交互式 Widget 新建 WebView 成本 | 未改；保留真机测量，不用静态截图替代交互 |
| F17 | 流式表格重复提取 settledData | 已复用；不重写 SubcomposeLayout 或降低表格表现 |
| F18 | 首请求准备缺少分段观测 | 已补现有开关控制的 DEBUG 耗时日志，不改变请求逻辑 |

覆盖过的生产路径包括发送、准备、流式、结束、首页、上下文/记忆/工具、子代理、小说、模型议会、图片/WebView、启动和前后台。没有调用方的 ContextUsageIndicator、已有输出 transform/事件写入优化、已有节流与缓存不重复实施。

## P0：消除确定的重复工作

### F1/F2：ChatService 与通知器

`onRunFinished` 仅在强制 checkpoint 成功、最终 Conversation 是同一对象、分页状态相同的情况下省去第二次全窗口写；输入变化和失败仍走原写入。**不省略 `withConversationWrite`**：它还承担旧 restore epoch 的拒绝，必须在后续标题/建议/记忆等副作用前保留。

`notifyRunning` 接收惰性的 PendingIntent factory。IDLE、同签名过滤、权限或渠道阻止通知时不创建 PendingIntent。通知节流策略、内容与失败通知不变，不将它说成所有通知都最多每秒一次。

### F3：Sandbox 派生

Conversation 流先投影为会话 ID 与当前运行的工具 part，按同位置工具引用去重；文本变化不再重做活动派生。loading、processingStatus、locale 仍独立触发，工具执行/审批/取消的新 part 仍触发。输入 JSON 使用已有 MessageRenderCache，一次解析供标题、预览、runtime、workspace 复用；输出缓存与长度/错误边界原样保留。

阶段审查发现的 restore 门控遗漏已修；32 个定点 JVM 用例通过。

## P1：请求准备、列表读取与渲染

### F12：在 resolver 只加载一次

`prepareKernelGenerationTurn` 保留清建议、工具告警；全量读取、sanitize、必要落库移到唯一的 `conversationForGeneration`。不加全局交接槽，不把 Conversation 塞进 request hash。数据库更新与内存 session 替换处于同一 writer 门控。

resolver 在 hooks 创建前失败时，保留原错误提示，取消原样传播。keepalive、generation task 和 Live 通知尚未在该点建立；runner/dispatch 原清理继续执行。普通分页长会话且 sanitize 不改变节点时，全量 DAO 读取由两次降为一次；短会话原本可走内存，sanitize 改节点的旧路径也可能本来只读一次，不夸大收益。

真实 Android 探针验证了 120 节点读取、80 节点 UI 窗口保留、窗口外无效工具清理后返回/库/session 一致，以及部分会话保存可完成。

### F13：小说轮询

- `Store.list(prefix)` 保持原有精确路径边界、隐藏项、点段与排序语义；只在可见目录前缀下缩小递归范围。
- 先选定 job，再读取唯一账本供 progress 与刷新复用，避免终态 job 配旧账本而停止刷新。
- 缓存仅用于运行中章节/草稿列表派生。键含路径、fileKey、size、FileTime；属性不可用则直读；读前取属性，缓存前复核，支持生产原子换文件。
- 每轮仍枚举当前目录，删除/改名自然反映；重载、切分支和终态不沿用旧缓存。主线程捕获不可变快照、IO 使用局部副本，结果经现有代次约束发布，不加共享锁或 watcher。
- 正文、账本、runtime、导出等仍直接读文件。沿用 StateFlow 的结构相等过滤，不额外包装一层发布防御。

### F15/F17/F7：保留视觉的复用

高亮缓存最多 64 项、输入输出合计 400,000 字符，键含代码、语言、色板、换行模式、native 开关和高亮器身份；只保留不可变渲染数据。原 4096 字符/长行边界、未完成代码块和 120ms crossfade 不变。

表格传入已提取的 settledData，后缀不匹配/不完整的原解析规则不变。发送后的消息查找仅接受原先规范的 UUID 字符串，改用 UUID 反向查找，不改虚拟项 key 或 anchor。

### F4：以测量决定的后台准备

固定负载在 Main 的解析被实测后，原“600ms 内机会预热”方案撤回。最终方式：

1. `MarkdownParseCache` 按原完整键（含 version/preprocessed）合并并发解析。登记在锁内，解析和等待在锁外；失败传播并清理，保留原 LRU/字符预算。
2. ChatVM 用既有虚拟化预热 helper 和同样的 regex/气泡/流式尾部规则，在 Default 上准备初始历史；未就绪显示既有 spinner，完成后原 180ms 淡入。取消不冒充成功，恢复后的可见会话重新准备。
3. 深链定位等待历史就绪且入场结束；初始索引仍来自真实 VM 快照，不使用只含末条消息的输入栏摘要。
4. 旧页先经 Service 的**只读 helper**按现有 40 节点批大小预读，VM 预热后调用原分页方法重新读取、合并、发布。预读节点绝不发布，不改变原 mutex/writer/restore/anchor。代价是一次额外的后台页读取；预热期保留原历史指示。

曾尝试的 Service `beforePublish` 加 writer gate 已完整撤回：它会让部分会话保存重入非重入锁。最终实现没有该回调和门控，也没有新增锁、重试或协调层。

验收只报告固定负载主线程计划与解析线程，不将其等同整页帧率或端到端提速。具体 A/B 在验收报告中。

## P2：可复用观测

复用 AmberChatPerf。新增阶段覆盖发送持久化、prepare、resolver 会话/工具、round memory、prepareContext、fit、durable 请求快照、流首 chunk 与非流完整响应。仅有阶段名、耗时、step/attempt 和数量；没有 prompt、消息、工具参数、URL、凭据。

每阶段在入口捕获 `DEBUG && Log.isLoggable`，同一开关控制起止计时和输出。关闭时不进行新增计时或拼接。首 chunk 不等同首个可见 token；短会话的 load 日志也不等同一次 DAO 查询。真实 provider/Jev/MCP 细分没有运行验收。

## P3：测量边界与后续入口

P3 没有根据猜测改代码。保留真机 Profile 项：冷启动/主题纹理、首页绘制、后台 Paging 查询、Widget/WebView、长流式表格、8 路真实任务及 provider 请求准备。

现有 `scripts/amberagent_auto_jank.sh`、baselineprofile、AmberChatPerf 可继续使用；必须显式设备/包名，准备真实长会话，核对 Macrobenchmark 没有因无数据提前返回。默认脚本 serial/包名不能照抄到另一台设备。模拟器仍有冷布局慢测量，不能宣称零慢帧。

本次无物理设备，不填写真机帧率或电量改善。收尾的三条既有 UI 长流程已在最终 APK 完整通过，并保存相同 test APK 的完整 WIP 基线对照；任务是 metadata 夹具，不能替代真实并行任务、真机帧时间或长期运行。P3 真机测量仍未完成。

## 最小复现入口

`ChatTimelinePlanPerfProbeTest` 默认跳过，需真实 UI runner、模拟器和 `-e amberPerfProbe true`。方法必须分别运行，防止并发解析探针预热解析器后污染冷首开对照：

- `firstOpenWith80NodesAndLongMarkdown`
- `markdownParseSingleFlightOnDefaultThreads`
- `resolverLoadsFullConversationAndSanitizesOutsideWindow`
- `olderPageLoadsLongMarkdownThroughUiScroll`

它们只使用一次性本地数据，结束清理，不调用真实 provider。正式回归以 [验证入口说明](2026-09-29-android-performance-validation.md) 的现有定点用例为主，不扩大测试矩阵。

收尾另有一个同样默认跳过的 `ChatStreamingPerfProbeTest#scriptedOpenAiSseRendersAndPersistsFullReply`，用相同参数、真实 UI runner 和模拟器守卫。宿主需先运行工件目录中的 `closure-sse-server.py`；固定回答、运行脚本和协议自检都在同目录。它只验证本地 scripted provider 上的正式发送/流式/持久化/UI 路径，不扩大成通用服务框架，不使用真实凭据。长回复尾部仍保持同一个 lazy item，因此这次滚动截图不能证明 F15 缓存经历离开组合再命中。

## 阶段完成边界

| 阶段 | 当前状态 |
| --- | --- |
| P0 确定重复工作 | 实现、阶段独立 review 和定点验证完成；流式全文落库已补证 |
| P1 准备与渲染 | 实现、锁/restore 复核、固定负载 A/B、原有三条 UI 长流程完成；F15 的 UI 缓存命中未验证 |
| P2 观测 | 开关、定点构建与本地 SSE 路径有证据；真实 provider/Jev/MCP 阶段未运行 |
| P3 真机测量 | 未完成：当前无物理设备与真实 provider 验收环境；所需入口和限制在结果文档逐项列出 |

“本机实现与验收完成”不等于“全部性能目标完成”，不能把 P3 的未测项目填成通过。收尾定位到的 Extensions 既有入口缺陷，以及 UI/durable 测试契约漂移单独列明，不计入性能收益。

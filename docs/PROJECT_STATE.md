# AmberAgent Current Project State

Last updated: 2026-08-12 (background execution/recovery P0–P5；self-evolution Slice A–C 与终审修复完成)

本文件只记录当前可操作事实。开始任务时仍需核对真实 Git、代码、测试和设备状态；历史过程从 Git 追溯，不在这里追加会话日记。

## 代笔弹性 P0–P3（2026-08-12）

产品目标：硬地板不动，基建自愈，近距软门，停机文案可行动。

- **P0**：块内可恢复重试 2 次；incomplete ≠ 质量；继续再检不重写；文案分型。
- **P1**：代笔软门 `maxPriorManuscriptChapters=4`（近 4 已收 + 候选）；手动全书扫描仍 `auditContinuity` 全量；`NovelContinuityAuditScope`。
- **P2**：chunk 外层 `cancelled` rethrow（不记 incomplete）；`failedChunkCount>0` 静默近距再扫 1 次（`shouldSilentRerunIncomplete`）；不烧 quality。
- **P3**：status/board CTA 分型（再检查 / 情节硬伤 / 重写）；session 条对齐与 44pt；面板按钮与 footer；开关文案改为「代笔收录前做连续性检查」（关=整段软门，含 incomplete）。
- **审查**：code-reviewer P0 approve-with-nits；P1–P3 approve-with-nits；已补 cancel rethrow、静默再扫规则单测、UI 文案契约。
- **门禁**：`NovelContinuityAuditTests` 30+；协作 incomplete/blocking/silentRerun 契约绿。
- **刻意不做**：Supervisor Agent、近距用户旋钮、全书 partial 整次自动重跑、打扰预算、改验收 fail-closed。

## Repository

- Repo: `/Users/arquiel/Downloads/AI/amberagent-ios`
- Branch: `feat/ios-provider-parity-claude`
- Tracking: `origin/feat/ios-provider-parity-claude`；ahead/behind 以实时 `git status --short --branch` 为准。
- Current committed HEAD: 以实时 `git rev-parse HEAD` 为准。2026-08-09 16:43 的 iPhone Air 覆盖安装仍是历史设备证据，不代表当前后台收束改动已装机。
- Worktree: 主题/编排/Chat 等改动仍未提交；另含模型议会真问题精修 + 技能精修（未提交）。**主题 builtins 3 角色包** + **导入主题库**（审查最小修补已收口）。**Steer 闭环修补（2026-08-09 16:x）**：排队条与 dock 同宽（右对齐发送键）；≤3 条按内容高度（修 ScrollView 虚高）；成功终态自动发队列头一条，取消/失败才回填 composer。门禁：`IOSSteerQueueTests` **18/18**，已覆盖安装真机。**可选出厂 skill `visual-svg`**（diagram/illustration → `show-widget`，默认不启用）已接入。**Codex image2 垫图（2026-08-09）**：`generate_image` 声明加 `use_attached_image`/`source_image_url`；宿主在执行前把最近用户附图注入 `source_image_url`，经既有 Codex Responses `input_image` 路径垫图；路由提示 + OCR 回灌文案引导模型开开关。审查修补：后台 enrich 改用 `job.displayMessages`（不再读 live store）；`exec` 嵌套白名单排除 `generate_image`；生图卡圆角与 loading 横向条同构；生成中改图禁用并提示。未验证：真机 Codex OAuth 端到端垫图。议会 WIP 同前。以实时 `git status --short` 为准。
- Git policy: 未经用户明确要求，不 commit、push、stash、reset、checkout、rebase 或清理工作区。

## 后台执行与恢复收束（2026-08-12，六阶段完成）

- **统一耐久事实**：复用 `agent_run` / `agent_event`，以 typed status、descriptor-scope query、INSERT IGNORE、CAS transition 和原子事件序列替代 iOS 前后台各自直接覆盖行；Chat 启动恢复只扫描自己的 descriptor，审批恢复先 claim 回 running，durable start 失败不再继续调用 provider/tool。
- **单一生命周期链**：`BackgroundGenerationKeepAlive` 明确区分 uiOnly / submitted / adopted；AppShell 是 Chat scene 生命周期唯一入口；移除无生产写入者的 `IOSChatBackgroundSuspensionStore` 恢复模型。后台终态保存失败保留 payload + task map 为 `recovery_pending`，不再清掉唯一恢复 owner。
- **普通 Chat**：官方 OpenAI API-key + Responses 路径接入 `background=true` / `store=true`、response ID + sequence checkpoint、断流续接、前台/冷启动 reconcile、显式远端取消；进入本地工具前先耐久清除旧 cursor，避免重复副作用。用户主动取消记为 `cancelled`，系统后台到期记为 `interrupted`。Claude、Grok、自定义兼容端点仍是 iOS continued-processing 窗口内的 best effort，不伪装成可强杀恢复。
- **小说 / MiniApp / Council / DeepRead**：四类运行在调用 provider/tool effect 前都写入同一个 shared `agent_run` 外壳，并按 descriptor 独立对账 success / error / cancel / expiration / cold-start；领域文档、MiniApp audit、议会 task/archive 与 DeepRead task 仍是各自业务权威，不复制第二套业务存储。小说与议会在用户前台发起时立即提交 continued-processing request；ghostwrite 暂停只保留一个状态条和一组继续/重写 CTA；MiniApp `Amber.ai.generate` 系统到期会取消原生请求并让 JS promise 明确失败，冷恢复提示会投影回 Runner；议会早期中断仍可按原议题恢复并显示中断消息，运行中不再无提示切历史；DeepRead 在 durable start 与 expiration 竞态中也会回收 run，失败态统一为“未完成”，详情页 Workspace 重试命中区为 44pt，首页 CTA 与实际“打开详情”行为一致。
- **最终 UI**：Chat 顶栏左右按钮与活动岛进入同一个 `GlassEffectContainer`，活动岛允许在 320pt 宽度内压缩而不挤压两侧按钮；Live Activity base（development region `en`）使用英文资源，`zh-Hans` 保留中文。
- **验证**：KMP Room durable-run 定点测试与 iOS Shared 编译通过；iPhone 17 Pro Simulator 定点运行 `BackgroundGenerationKeepAliveTests`、`IOSDurableRunStoreTests`、`IOSCouncilRoomArchiveStoreTests` 共 **45/45**，另加 Chat 用户取消终态单例 **1/1**，均通过。`git diff --check` 与相关 Swift parse 通过。构建仍有仓库既有 warnings；未验证真机 continued-processing adopt/expiration、App Switcher 强杀、真实 OpenAI response 保留窗口、真实工具副作用 exactly-once 和 320pt 实机截图。
- **能力边界**：continued-processing 是系统给的执行机会，不是耐久计算本体；只有拥有服务端 response/run ID 的段能在进程死亡后续接。跨 provider、跨多轮工具的完整强杀续跑仍需要服务端 durable run orchestrator，本轮没有为此再造客户端状态机。

## 编排体系借鉴计划（P0–P3 全部完成 2026-08-08，iOS 侧）

- 计划文档 `docs/AGENT_ORCHESTRATION_ADOPTION_PLAN.md`（含 P0 完成记录）。**P0（tool_search/工具暴露 + MCP `mcp__*` 展开）已按实现代理 + 独立 checker 复核 + 精准修复闭环**：iOS 静态工具 51+ 默认进 lazy 模式（首轮 24 常驻 + `tool_search`，命中下轮可见）；MCP 发现工具展开为独立声明默认 deferred；后台桥经 handoff `fullToolNames` 全目录重建并按轮刷新 params。
- 关键行为变化：未暴露工具调用从"整轮硬失败"改为"软失败 + 搜索引导"（未知名仍硬失败）；lazy 时注入发现引导 system 片段；`type` 为数组的 MCP schema 不再崩溃。度量：20 个 MCP 工具展开后首轮可见 schema +0 字节。
- 验证：xcodebuild 四套件（IOSToolSearchExposure/IOSMcpExpandedTool/IOSSkillMcpTool/IOSAgentToolEngine）+ KMP `:ai-core/:feature:tools:api/:shared` jvmTest 全绿（JDK 21 用 `~/.gradle/jdks` 工具链，本机无默认 JAVA_HOME）；checker 两轮对抗复核，无越界（用户并发 WIP 均未触碰）。
- **P1-a（iOS steer 队列）已完成 2026-08-08，附件扩展+闭环修补 2026-08-09**：生成中发送入队（上限 20，含图/文件）；生成中附件钮可用（识图/审批/读文件中仍禁）；仅附件可入队；混合 leftover 分流；排队条最多可见 3 行可滚。门禁：`IOSSteerQueueTests` 17/17。未验证：真实 provider 端到端、后台 run 队列消费、真机。
- **P1-b（mailbox 存储 + 折入）与 P1-c（spawn/list/interrupt + FINAL_ANSWER 回传）已完成 2026-08-08**（工作区未提交）：Room `mailbox_envelope`/`thread_edge` + `MailboxDao`（drainPending 事务化 exactly-once，`AND delivered_at IS NULL` 并发加固 loser 返回空）；前台两边界消费（continueAfterToolResult 末尾 + prepareAndStartStreaming 头门控 displayMessagesOverride==nil）；`IOSThreadOrchestrationToolService`（spawn 三模式 fork/边/durable 后台 run，深度上限 2、并发上限 4，start 失败回收 running 行，cancel/finishStreaming 双触发 FINAL_ANSWER 幂等去重，空转录也回传终态）；KMP 三工具声明 + 中文别名（ToolSearch.kt 六工具词条 P1-c 已加）+ `OrchestrationToolDeclarationsTest`。
- **P1-d（线程间消息：send_message/followup_task/wait_agent + 后台引擎 mailbox drain）已完成 2026-08-08**（工作区未提交）：三工具声明进 `iosToolDeclaration`（非常驻 deferred 池；send 描述明示不唤醒、wait 描述含 steer 打断与 [5000,300000] clamp）；`IOSMailboxActivityCenter`（进程内 actor 广播，订阅在 actor 内同步注册 + bufferingNewest(1)，wait_agent 先订阅后查 pending 两窗口不丢）；`send_message` 入队不唤醒、`followup_task` 空闲目标走 spawn 同款 bootstrap（信封渲染直写目标会话 + delivered + durable 后台 run，抽取 `persistTargetMessages`/`startDurableBackgroundRun` 与 spawn 共用）、运行中目标仅入队等边界 drain；`wait_agent` 五出口（已有 pending 立即返回 / mailbox 活动事件 / steer 打断固定文案 / 超时 clamp 并在返回里说明 / Task 取消立即返回（引擎下一边界收口 wasCancelled，不吞、不等超时）；后台引擎 `IOSAgentToolEngine.run` 新增可选 `mailboxDrain`（默认 nil 零影响，SubAgent/Novel 不传），后台协调器接 `IOSMailboxStore.drainPending` + 会话持久化（复用既有写路径），调用点与 replacingTools 同点；信号点 = send/followup enqueue、notifyRunTerminal（FINAL_ANSWER）、`enqueueSteerMessage`；账本分类 wait_agent=pure、send/followup=sideEffect。门禁：`IOSThreadMessagingTests` 11/11（同树入队+边界折入/非树自身拒绝/followup idle bootstrap 与 running 仅入队（前后台两路径）/wait 五出口含 clamp 与取消/引擎两轮脚本 drain 折入+delivered+持久化）+ 回归四套件（IOSOrchestrationTool/IOSMailboxDelivery/IOSSteerQueue/IOSAgentToolEngine）共 66/66 全绿；`:ai-core:jvmTest :core:agent-store-room:jvmTest` 全绿（声明测试 6 项含三新工具）。残余：wait_agent 的 preempt-sampling 不做（v1 只在工具边界投递）、编排 UI（P1-e）、Android 接线；真机未验证。
- **P1-e（限额与控制面）已完成**：并发计数从全局账本改活注册表（前台 run + 后台 activeJobs + 在途 bootstrap，检查+占槽 MainActor 原子）——崩溃残留 running 行不再误伤 spawn；子线程会话只读（composer `.orchestratedThread` + 四守卫；会话列表徽标未做，ConversationsView 当时在用户 WIP，VM 层 `isOrchestratedChild` 查询就绪）；Android `InProcessAgentRunner` 账本吞异常修复（`onLedgerError` 接线 `ChatService.addError`，无 SDK 未编译验证）。
- **P2（记忆 polluted + lastUsedAt + citation）已完成 2026-08-08**：P2-a `ConversationMemoryMode` 三态挂 KMP `Conversation`（不动 Android Entity），iOS 置位于 `messagesByFinishingToolCall`（search_web/scrape_web/mcp_call/mcp__* 成功输出才标；顺带修复 MCP 失败输出结构化为 `toolFailureJSON`），`updateMemoryMode` 锁内 RMW（POLLUTED 唯一出口是 ENABLED 复位），设置页「受外部内容影响的会话」小节 + 复位，Android 文件级 polluted 集合 + 抽取 gate（未编译验证）；P2-b 前提修正——召回注入标记接线已存在于已提交代码，只补去抖（集合变化才写盘）+ 测试；P2-c（实验性）`<amber-mem-cite>` 流式状态机剥离（渲染管线零改动，剥离在 `dispatchStream.onChunk` 进 sink 前），前后台四终结边界 flush（后台引擎可选 `citationTracker`），引用标记 `markUsed(force: true)` 绕过去抖，memory-context 注入一行引导；模型遵循率预期：有则增值、无则无害。
- **P3-a/b/d（exec 沙箱/嵌套桥/ALL_TOOLS）已完成 2026-08-08**：P3-a JavaScriptCore 沙箱（每求值新 VM+context+独立队列；console 捕获；无 fs/网络/imports；超时/取消 abandon 语义，JSC 无终止 API 的 CPU 风险文档化；pristine `JSON.stringify` 捕获；`execJavaScriptEnabled` 默认关，声明/分类/执行三重 gate）；P3-b JS `tools.x()` 同步阻塞桥（信号量，无 Promise.all——描述明示），白名单 = 当轮 visibleTools − 排除集，嵌套走同一审批/账本链（`exec-nested-` 前缀），死锁逐队列论证 + teardown 无线程泄漏，Android 按逃逸条款未做（4 阻塞点）；P3-d `ALL_TOOLS` 冻结注入（同源白名单 {name,description}），8 项安全审查修复 3 GAP（`max_output_chars` clamp [1,100000]、16 驱逐补测试、wait 显式 sideEffect）。
- **全线终态（2026-08-08）**：全量终跑 **21 个 iOS 套件 351/351 通过、0 失败**（iPhone 17 Pro Simulator, ARCHS=arm64）；KMP `:ai-core :feature:tools:api :shared :core:agent-store-room :core:conversation-storage` jvmTest 全绿（JDK 21，`~/.gradle/jdks` 工具链）。P0–P3 各阶段均经独立 checker 对抗复核（14+ 轮，PASS/PASS_WITH_NOTES 收口），复核发现的 20+ 项一般级问题全部修复并复验；工作区全程未提交（遵循 Git policy），用户并发 WIP 零触碰（checker 逐轮越界核验）。
- **整体复核轮（2026-08-08，两路独立审查 + 终验 PASS）**：逐阶段复核之外的整体复核抓到 1 个严重 + 5 个一般级跨阶段问题并全部修复——**S1：`send_message`/`followup_task`/`wait_agent` 声明从未进 `makeTextGenerationParams` 组装点**（P1-d 子系统生产不可达，逐阶段测试绕过了组装链；已补声明 + 生产链端到端契约测试）；M1 后台 drain 折入与终态保存谱系漂移（信封重复落盘+误导 notice → `saveBackgroundCompletion` 按消息 id 去重 + 差异全来自 drain 不插 notice）；M2 后台引擎 executor 表冻结（tool_search 命中"可声明不可执行" → 每轮 executorRebuilder 重建）；M3 spawn/followup 的 fullToolNames 被父可见子集截断（→ 桥全目录）；M4 `role_assistant_id` 入库不生效（→ fork 应用 + UUID 预校验，顺带拆掉 K/N `Uuid.parse` 非法输入终止进程雷）；M5 引导引用未声明的 `tools_list`（→ 补声明 + 本地执行）；M6 cell wait 无取消感知（→ 取消收口 + terminate 唤醒同 cell waiter）。终验：KMP 135/135 + iOS 九套件 176/176，PASS 零遗留。
- **真机反馈增补（2026-08-09，均过 checker 复核）**：①跨会话读取 `session_search`/`session_read`（iOS；Android 的 conversation_search 只读当前会话，跨会话读取两端此前都没有；复用 iOS 存储层 `searchConversations`；非法 UUID 预校验防 K/N parse 终止进程；只读 pure 分类）；②**生成中切会话回归修复**：根因是交接守卫在任何前台工具执行中拒绝交接（wait_agent 把窗口从毫秒拉到分钟级）——纯工具（wait_agent/tool_search/session_* 等）在途时允许交接后台重放，副作用工具与生图维持拦截，被拦时经 publishUserVisibleError 提示；③**巨型工具输出超窗**：真机事故（Exa `contents.text=true` 把 1.09MB 全文灌进 search_web 输出并持久化，CJK 加权 ≈103 万 token 超 85 万预算，会话卡死）——三层修复：搜索输出 12,000 字符上限、工具输出漏斗统一截断（JSON 保形、判定键保护、地板 12）、`fitMessagesToTokenBudget` 兜底就地截断巨型工具输出（探针实证卡死会话 103 万 → 16,793 token 正常过窗，无需删历史）；**Exa 源头改为 `contents.highlights`**（相关摘录取代整页全文，text 字段回退保留）。门禁：iOS 各套件全绿（含 IOSSearchExecutorTests 新增 2 Exa 测试）+ KMP 绿。探针基线保留在 `IOSOverheadProbeTests`（真机数据复现）。
- 未验证/跟进：Android 后续专项（P0-b 接线/P1 消费点与编排工具/P2 编译验证/P3 exec executor 与 QuickJS 桥，全部无 SDK 未编译验证）；真机清单（JSC 终止限制、后台 BGTask 内 wait/cell/mailbox drain、真实 provider 全链、真实 MCP server、会话列表徽标 UI 接线）。
- **提示词管线闭环轮（2026-08-09，真机反馈驱动）**：用户报"直接问 agent 读其它 session 它说不能，告诉工具才行"——确诊为发现引导缺行为规则（非工具注入问题）。全量盘点 31 个注入面 + 55 工具声明 + 7 辅助提示后按"管线闭环"修断链点：①发现引导加行为规则（"声称做不到之前先 tool_search"+ 领域提示）；②`category()` 补 iOS 工具名映射（workspace_/编排六名→subagent，此前全落 utility 致领域计数无信息量）；③MCP 注入文案补 `mcp__server__tool` 直接调用路径；④新增编排语境条件注入（仅参与线程树的会话注入 mailbox 语义说明）；⑤checker 复核抓出刷新生命周期缺口并修复——缓存原只在会话切换时刷新（spawn 中涂/邮件到达全漏），改为 `bindings.refreshOrchestrationLinks` 每轮组装前刷新 + 子线程 handoff 注入子线程向变体文案（只进 upload 不进持久化）；⑥B11 空回复/小应用失败通知补 `LOCAL_GENERATION_ERROR` 标记（此前会作为 assistant 历史重新上传致角色混淆）。门禁：iOS 四套件 134/134 + KMP 绿（含"never claim inability"断言与刷新路径/子线程 handoff 注入测试）。
- **P3-c（exec cell 生命周期：wait + store/load + cell 持久化 + 后台约束）已完成 2026-08-08**（工作区未提交）：`wait` KMP 声明（cell_id 必填、timeout_ms 默认 10000 clamp [1000,60000]、terminate 布尔；描述含「cell 完成前 wait 会阻塞到输出或超时」）+ ToolSearch 中文词条；开关与 exec 同源（`execJavaScriptEnabled` 关时声明/执行路径零痕迹，wait 与 exec 同进 deferred 池）。`IOSJsCellRegistry` 为 cell + store 唯一 owner（actor 单写者，按 conversationId 键会话作用域、跨 run/前后台共享，run 结束 cell 不死；并发上限每会话 4，超限 exec 返回结构化错误；store/load 单 key 64KB/总会话 1MB，超限抛 JS 错误；sidecar `Documents/js-cells/{conversationId}.json` 原子写、空删文件、同文件存 cells+store；冷启动 sweep Running→interrupted 不假 completion，照 IOSChatBackgroundSuspensionRecord 语义）。exec 超时不再丢句柄：yield 返回 `Script running with cell ID {cell_id}`（照 codex 文案），求值在自己队列继续跑，引擎新增 completion 通道把终态交回注册表；cell 状态机 Running→Completed|Terminated|Failed（interrupted 为 sweep 专用第五态）；Completed 输出不保证跨进程恢复（v1 注释说明）。wait 三路径：完成返回 {status,output,logs} 且读一次清除 / terminate 标 Terminated（JSC 无强杀 API，失控脚本继续烧 CPU 至自终，注释写明）/ cell 不存在结构化错误；wait 阻塞期超时返回 running 状态可再 wait。嵌套 tools 桥选型：**yield 后 cell 内 tools 调用返回诚实错误**（hostCall 绑首轮 run 的协调器/账本上下文，续跑期不可复用；per-evaluation gate 在 abandon 时关闭，JS 侧报 "nested tools unavailable after the exec call yielded"）。store/load 为 JS 全局（pristine stringify 序列化、缺失 key 返回 undefined、跨 cell 会话共享、新持久化实例读回）。后台 executor 注册 exec+wait（后台可 wait 本会话 cell）；wait 是普通工具调用，天然占 maxToolResumeCount/maxSteps 预算，无新预算机制。门禁：`IOSJsCellTests` 11/11（yield→wait 闭环 / wait 三路径+clamp / 并发 5th 拒绝 / store 共享+超限+持久化读回 / 冷启动 interrupted / 引擎级 exec yield→模型 wait→回灌 3 步预算）+ 回归 IOSJsSandboxEngineTests、IOSAgentToolEngineTests、IOSToolSearchExposureTests、IOSChatBackgroundSuspensionTests、ChatViewModelGenerationParamsTests 合计 101/101 全绿；`:ai-core:jvmTest`（WaitToolDeclarationTest 3/3）与 `:feature:tools:api:jvmTest` 全绿；`git diff --check` 仅余 docs/IOS_THEME_PACK_DESIGN_SPEC.md:443 既有 WIP 尾随空格（未触碰）。未验证：真实 provider 下模型真实调用 exec/wait、后台 run 的 wait 端到端、JSC 终止限制真机证据、真机。

## 代码库瘦身（2026-08-08，已提交）

全仓七代理只读审计后执行的精准删除与修复，已与同批 G6/G7 生成参数、守卫链和主题改动按领域拆分提交。

- 死代码删除合计约 4,900 行、31 个文件、1 个 Gradle 模块：iOS 1,563 行（ContentView/AssistantParamsView/IOSHealthService 整文件 + 24 个零引用类型 + CouncilChatViewModel 等死成员）；Android 2,158 行（AssistantPicker/CompressContextDialog/CherryStudioProviderImporter/setting/memory 桩目录等 12 个死文件 + OfficeProjectEditorDialog 等死块 + USE_SPLIT_* 脚手架四文件连 flag）+ 级联死代码约 410 行（SearchPicker.kt 整文件、office 导入函数簇、ChatInputUsage 两组件）；KMP 404 行 + `:core:llm` 模块整体移除（settings/app/shared 三处构建配置同步清理）；iOS 测试 368 行（NovelPrefixReuseCostExperimentTests 纯打印探针 + ProviderRegistryStoreTests 的 #if false 死块）。
- 审计结论被事实修正而未删的项：AssistantImporter.kt（AssistantPage.kt:367 有活调用）、FeishuDocRadarWorker.kt（WorkManager 老 job 升级兼容桩）、NovelPolishTestSupport.swift（其 NovelPolishTestCase 被 3 个测试文件继承）、探针四件套中 3 件（含真实阈值/契约断言，是活跃防回归锁）、FeishuOfficeReportDraft/FeishuWorkSkillDefinition（有活引用）。
- 语义修复 5 处：agent_run 账本写失败由 print 改为 publishUserVisibleError（ChatViewModel/IOSChatBackgroundGenerationCoordinator 各一）；AccountAvatarStore.save 失败不假成功；IOSWebMountRegistry decode 失败不再用 seeds 覆盖写回用户存储；IOSSkillFileStore.saveSkillFiles 手写 staging+backup+rollback 换为 FileManager.replaceItemAt 原子替换；NovelProjectRepository.writeLifecycleMarker 删除写后读回校验。
- 顺带最小修复（非瘦身范围但阻塞门禁）：①G7 同批工作在 ChatGenerationCoordinator 内裸调 IOSGenerativeUiRequestPolicy 的 fileprivate systemMessage，已加类型限定；②G6 同批测试引用不存在的 ReasoningLevel.standard 与已改签名的 toText()，按当前 API 最小修正（注意 ObjC 保留字导出：Kotlin AUTO → Swift `.auto_`）；③project.yml 两个 widget target 显式引用 zh-Hans.lproj 内文件导致 XcodeGen 生成 zh-Hans.lproj/zh-Hans.lproj 双重路径，改为声明 lproj 目录（既有基线问题，重新生成工程才暴露）。
- 验证：`:shared:compileKotlinIosSimulatorArm64` BUILD SUCCESSFUL；XcodeGen 重新生成工程后 iosApp 与 iosAppExperimentalGPL 双 scheme BUILD SUCCEEDED（需 ARCHS=arm64，AmberNative.xcframework 无 x86_64 slice）；早期 3 个旧契约断言已按最终行为收口，最新 Chat 八类定点 173/173，MCP/Board/DeepRead/MiniApp/Memory/Skill 三组 111/111。
- 环境限制：本机无 Android SDK，`:app:compileDebugKotlin` 无法运行；Android 侧删除靠全仓（含 res/Manifest/测试）rg 零引用验证兜底。`:shared:compileCommonMainKotlinMetadata` 在 agent-store-room 的 Room 构造器处失败，属既有基线（本轮未触碰、非常规门禁任务）。
- 未执行（留待后续轮次/裁决）：wiring 源码断言测试约 3,300 行去留（当前是在用门禁）、聊天链路 coordinator @Observable 化与转发层收敛（约 800 行）、KMP 伪 KMP 模块合并（10-12 个模块）、SseEvent/工具声明双轨单源化、DeepReadStructures 40 处双层兜底解码。

## 代码库瘦身第二轮（2026-08-09，未提交）

七代理只读复检（iOS 死码/过度设计/测试臃肿/KMP 架构/Android 死码/过度防御/docs-config）后，只做纯死代码删除，逐条 rg 全仓零引用复核（含 iosAppTests/字符串/Manifest/资源），全程避开并发 WIP 文件。合计删除约 960 行 + 一批死构建配置。

- iOS 死码约 367 行（20 文件）：9 个零引用类型（SkillsView 四件传递死链、ProviderDraftSwitch、ChatMetaLine、BoardSourceLabels、ChatStreamingMarkdownTableRowView、SubAgentRoleStatus）+ 20+ 死成员（BoardSettingsView resolvedModelId/createSourceSection、CouncilRunner mapStatus、SearchProviderView credentialPreview、IOSTerminalRuntime markRetryRequested、PackedAstReader linkTitle 等）。
- Android 死码约 216 行：UseAssistant/Debounce/RabbitLoading 三整文件 + BoardPage TodoRow、ChatSizeChecker DefaultSizeInfo、MiniAppV1Permissions 别名、ChatInputSandbox 两个 sandboxStatus*Color。G6 reasoning-only 死机（GenerationHandler ~33 行）挂起——该文件在并发 WIP 且涉 streamWith 活路径、无 SDK 不可编译验证。
- Android 死 res：6 locale 共 286 条字符串 + 3 个零引用 launcher drawable。
- 构建配置：libs.versions.toml 删 16 死 alias（13 ktor-server + ktor-client-sse/logging/encoding + dom4j/navigation2/koin-core/leakcanary/sqlite-vector/room-gradle-plugin）+ 5 孤儿 version key + kotlin-jvm 死插件声明；settings.gradle.kts 删 objectbox resolutionStrategy 与 itext 仓库；根 build 删 kotlin-jvm apply false；4 模块死依赖（document/common 的 appcompat+material、board/impl 的 work-runtime、core/native 的 coroutines）。
- 审计结论被事实修正的项（未删/已恢复）：IOSMemoryCitationStripper.swift 非死文件（生产类 IOSMemoryCitationTracker 定义于此）；PerfTraceModifier.kt 非死（Markdown.kt 在用）；board/impl 的 jetbrains-markdown、automation/api 的 coroutines、feature/task 的 datetime 均实有用；countUnfinishedRuns 删后恢复——所在 IOSThreadOrchestrationToolService.swift 为未跟踪编排 WIP，且编排计划文档明记「死代码（记录不删）」，尊重其决定。
- 验证：iOS xcodebuild（iosApp scheme，-skipMacroValidation，ARCHS=arm64）BUILD SUCCEEDED；`./gradlew help`（JDK21 ~/.gradle/jdks）BUILD SUCCESSFUL（toml/settings/根配置有效、无悬挂 alias 引用）；`:core:native:compileKotlinIosSimulatorArm64/Jvm --rerun-tasks` BUILD SUCCESSFUL。所有生产文件 diff 均为纯删除（0 增/N 删），无并发 WIP 混入。
- 环境限制：本机无 Android SDK，Android 删除靠 rg 全仓零引用兜底（含 res/Manifest）；iOS test target 未编译（引用已按含 iosAppTests 的 rg 覆盖），未跑套件。
- 挂起/留待裁决（非本轮纯删范围）：过度防御 top10 语义修复（DocumentAccessStore.swift:519 读失败回退陈旧快照再写盘最危，需红测试先行）；6 处位于并发 WIP 文件的 iOS 死成员（AppShell sheetBinding、SettingsStore deleteSSHProfile、PlaceholderViews placeholder、CouncilChatRuntimeView appendToken、IOSMiniAppModels parseOrNull、ChatToolTimelineView iconFill）；架构级——KMP 21 个 ≤3 文件模块合并、SseEvent 三份单源化、工具声明双轨单源化（39 同名重复）、iOS 测试脚手架重复约 2000 行、wiring 源码断言 2655 行去留、docs 约 2.8 万行归档。

## Chat 流式生成五项修复轮（2026-08-09，未提交，真机反馈驱动）

- ① 淡入：vendor `MarkdownRenderConfig` 新增 `animatesAppendedTailAsUnit`（默认 false、共享默认不变、全部 builder 透传）；三界面共用 `streamingMarkdownConfig` 启用——流式 append 改**逐拍尾段整体淡入**（替代逐词 display-link alpha 重写，长表格门禁 p95 36.1→16.7ms）并解除 window 门控（修 cell 配置窗口期节拍无淡入、直跳显）。
- ② 宽度：工具胶囊 `combinedLine` subject 上限 56→14 字、动态工具名 16 字（按列宽预算倒推；无界提案下 lineLimit 不生效，超长 subject 曾把胶囊理想宽撑破列宽→列宽随 toolCallStarted/完成换词跳变、居中裁切顶边）；`ChatToolTimelineWidthOverflowTests` 新增无界提案理想宽契约测试。
- ③ 终态跳变：推理卡生成结束自动收起改**无动画单帧收口**（`suppressesShowsBodyAnimation`；展开/用户 toggle 动画保留）——0.28s 收起动画与 collection 终态重测错相位是最大单体跳变源；新回放测试在 `.assistantStreamClosed` 当轮采样（旧用例 0.6s 后才取 baseline，漏掉终态重建），推理卡收起 fixture 断言视口单调收敛、无两段式回跳。
- ④ 粗体：探针抓到两个被 CommonMark flanking 拒绝的真实形态——`**（重点）**说明`（定界符贴全角括号）与 CJK 紧邻 `__…__`——swift-markdown 与 pulldown-cmark **双链路**都渲染成字面星号；vendor 新增 `RejectedEmphasisRepairRewriter`（`MarkdownParseOption.repairsRejectedStrongEmphasis` 默认 false，聊天侧显式开启、独立于 speculativeRewrite）+ `AmberMarkdownView` 渲染层同源修复（两处正则同式）；只作用于解析器已拒绝、残留在 Text 节点的字面定界符，自限性+默认关回归锁齐备。踩坑记录：Swift Regex 不支持 lookbehind；带捕获组的字符串正则运行时类型是 `Regex<(Substring, Substring)>`，显式标注 `Regex<Substring>` 会运行时 cast 失败静默 nil（用 `AnyRegexOutput`）。顺带发现 vendor LaTexPreProcessor 的美元符正则含 lookbehind、同样静默 nil（记录未动，非本轮范围）。
- ⑤ 完成触觉：全仓穷尽确认完成路径原本**零触觉/声音/通知**（含 KMP iosMain/Watch/Live Activity）；新增 `AmberHapticEvent.rigidImpact` 单次刚性轻触，`ChatViewModel.onGenerationCompleted` 触发（仅成功完成、审批暂停不触发）；iOS 本地偏好 `completionHaptic` 默认开 +「显示与字体→消息显示」开关 + wiring 测试绿。刻意不复用 KMP `enableMessageGenerationHapticEffect`（Android-only 接线、默认关，动它改 Android 默认）。用户报告完成时「物理振动」与代码无振源矛盾，装机后 `log stream` haptic 谓词取证；大概率是 ③ 视觉跳变误感知，待真机复核。
- 门禁：必跑三套件 + 定点——ChatSwiftUIStreamReplayTests 23/24（唯一失败=已知不稳的 growing-table perf 探针 max 85.45>80 单尖峰、p95 16.67ms，未放宽阈值）、NativeTimelineScrollCore 4/4、ChatViewportPolicy 41/41、CJKEmphasis 6/6、ToolTimelineWidth 4/4；IOSSettingsWiringTests 2 失败与本圈无关（用户 WIP 源断言漂移：ChatView `isLoading: isCurrentConversationRunActive` 缺失、ChatGenerationCoordinator expiration MainActor 序断言）。
- **第二轮（手感追问：流式增长顺、完成那几下不顺 + 思考内容小幅跳变）**：①终态 settle 从 tau=0.06 指数缓动改**逐帧瞬时钉底**——完成瞬间布局落地期 bottomTarget 移动时缓动会拖出"再滑一段"，就是完成后不流畅的载体；core 新增 snap 契约测试（晚到布局一拍贴底、静默期满交还所有权、无缓动中间值）。②**第三个病灶**：`viewportChanged` 从 `.idle` 原为 no-op——终态 settle 交还所有权后，负载下晚到的二 pass 布局（终态重测/渲染态延迟刷新）再移动底部时无人重锚，确定性搁浅 16pt（旧缓动存活更久恰好掩盖）；修为 idle 近底（resumeEpsilon 48）时重进 settle 收锚、收敛后照常交还所有权。③思考正文 UITextView **测量竞态**：textStorage 追加后 TextKit 布局晚一帧，SwiftUI sizeThatFits 先读旧 contentSize → cell 高度两段到位=思考内容小幅跳变；`ChatReasoningBodyTextView.sizeThatFits` 测量前 `layoutIfNeeded()` 同步布局。④回放测试升级为「基准=收敛锚点、全窗口漂移≤25pt（缓动签名捕获）、尾段逐帧钉底≤2pt、无回跳」；窗口开头的过渡帧（snap 3 帧内拉回）不作为基准。门禁 **120/120 全绿**（含 perf 探针本轮通过）。
- **第七轮（小说流式统一到 Chat 同一套 driver 事件链）**：用户要求「不重复造轮子、改好一套统一用」。侦查确认小说与 Chat 的底层 driver/scroll core/markdown 渲染管线/pacing 策略已全部共享，唯一分叉是 driver 上层的「事件→intent 映射」和 follow 策略两套。统一三处：①小说流式增长从一次性 `explicitBottom(.streamGrowth)` snap 改提 `.streamContentGrew`（driver followingBottom + frame driver 连续追底，与 Chat 同构）——修「滑不到底」（旧每拍 snap 回调间隔欠账无人追）；②终态 settle 从视图层 `quietSettle 0.4s 定时 + 一次性 snap` 改提 `.generationTerminated`（driver settlingAfterTerminal 逐帧钉底 + 静默交还 + idle 近底重锚）——修「完成瞬间跳变」（旧 snap 吸收不了晚到布局）；③几何回调加近底恢复（driver 激活 + 生成中 + 近底 48pt + 非拖拽 → 再发 streamContentGrew，driver idle+nearBottom 自动接管）——修「被动推离后跟随不恢复」。保留薄适配：首行锚定门闩（awaitingInitialRows）、底部按钮可见性（setBottomButton）。小说特有的 NovelSessionBottomFollowState 状态机保留（browsingHistory=driver pausedForUser 语义等价，删它需更大重构，本轮不动）。门禁 NovelSessionReplay+Presentation **88/88 绿**（三条契约测试断言更新：streamContentGrew 替代 streamGrowth、terminateGeneration 替代 followBottom、阈值仍 96 由视图层管）。设备装机被签名证书过期阻塞（Apple Development YXNKT7GJ3W revoked/expired，需 Xcode Accounts 重登）。
- **第六轮（双路 subagent 收尾 review）**：checker 验闭环 A–G 全闭环（flag 透传/粗体双链路/排空/软收起/滚动/触觉/建议条动画），H 套件被一次外部临时切 main 阻塞后在恢复的工作区补跑 **49/49 绿**（期间工作区经并行工具切回 feat，75 文件改动完整在盘）。UI 审查发现并修复：①Reduce Motion 下正文流式淡入未门控（与思考框语义不一致）→ `streamingMarkdownConfig` 两动画 flag 加 `!reduceMotion`，`MarkdownConfigKey` 增 reduceMotion 字段（无默认值设计抓到第二构造点）；②短思考正文被固定 12pt 双渐变洗灰 → mask 渐变带 `min(12, h/3)` 自适应 + 底部渐变仅裁切时启用（`onClippedChanged` 回调）；③软收起卸载时的 chevron/圆角二次动画 → 软收路径置 `suppressesShowsBodyAnimation`，asyncAfter 余量 0.26→0.3s；④工具胶囊截断改宽度预算（CJK=2/ASCII=1，subject 28/名 32 单位），ASCII 信息损失修复；⑤`visualConfigHash` 归一化补 `animatesAppendedTailAsUnit` 对称；⑥SwiftUI fallback 四处 `withAnimation` 补 reduceMotion 门控。不采纳项（记录理由）：settle 速度上限（收 ramp 本身 ≤900pt/s，snap 与之同步，加限反而 reintroduce 滞后）；思考卡 maxHeight 随 Dynamic Type（标注可接受，内容可滚不丢数据）。126 测试 1 失败=已知负载敏感 perf 探针单尖峰。
- **第五轮（思考框流式乱跳/不流畅）**：三处。①逐词淡入改**尾段整体淡入**（与正文段落同构）：display-link 每帧属性重写从「全部词 N 条」降到 1 条；②撤掉上轮加在 `ChatReasoningBodyTextView.sizeThatFits` 的 `layoutIfNeeded()`——每拍多次测量时同步全量 TextKit 布局是掉帧源（cadence 门禁 p95 71ms→33ms）；③内部滚动**恢复 540pt/s 限速连续跟随**（曾误改逐帧钉底：主列表钉底不动视口，思考框钉底=每 chunk 整行跳，cadence 门禁 maxStep≤10pt 契约翻红后回正）。思考卡门禁 10/10 绿（p95 33ms≤40、maxStep 9pt≤10、淡入只作用新增段）。
- **第四轮（真机录像逐帧：收尾半句一次性跳出、上跳一格）**：根因=终态排空 `terminalDrain` 快排公式对小积压也一拍倒 36+ 字（约两行）。用户拍板「前台以顺滑输出结束为锚点、节奏应随 provider 速度连续加速、不要分档」→ 终版**连续节奏曲线**：节奏锚 `terminalDrainAdvance = clamp(backlog/16, 12, 1536)` 由完成时积压**一次决定、不逐拍衰减**（逐拍衰减会让后段掉回慢节拍，24k 实测拖 2.4s）；拍间隔 `clamp(48ms×36/advance, 8, 48)` 连续缩短。小积压≈12 字/拍×48ms 打字收尾；大积压≈1500 字/拍×8ms 逐帧 whoosh、滚动缓动抹成连续快滚；中间无断点。24k 契约改按真实耗时（≤0.5s，实际≈0.13s；旧「16 拍」口径在动态间隔下不再是耗时代理，断言过时按规则说明后更新）。新增小积压契约测试。横线=模型输出的 `---` 分隔符，非 UI 残影。
- **第三轮（完成瞬间仍跳一下）**：钉死两个残余跳源并修复。①带思考场景=思考卡终态收起的**高度砍除事件本身**（回放实测一帧 −169pt；滚动一直钉底，跳的是内容不是视口）→ 改**软收起**：高度上限 180→0 按 0.2s easeOut 逐帧 ramp，representable 逐帧重测、collection 高度连续变化、滚动逐帧钉底，ramp 结束（高度已 0）再卸载正文；Reduce Motion 保持瞬时收；软收期间忽略手动 toggle；`cancelPendingCollapse` token 防串。②无思考场景=**完成瞬间建议条插入** composer（minHeight 44）而转场动画只管建议条自己、时间轴可用高度一帧被吃 → 给 composer 容器加建议条显隐的布局动画（easeOut 0.2），高度连续变化 + 滚动逐帧重锚。活动岛高度跨状态稳定有既有测试锁，排除。定点 48/48 绿（含终态回放/wiring/scroll core）。
- 未验证：淡入/宽度/终态滚动/软收起的真机手感、物理振源取证、完成触觉真机观感。

## Home E 版视觉落地（已提交）

目标：把定稿 E 版首页设计（home-replica.html 同源规格）落到 `ConversationsView` 与全局主题令牌，并让顶部续接位只展示真实未完成工作。

- 全局令牌：三画布——暖纸 `paperLight`、暖灰 `neutralLight`（默认）、中性白 `whiteLight`（`#F5F5F4`/`#FFFFFF`/`#EEEEED`）；`Paper.white` 已加入外观设置三卡。`fab`/`fabInk`/`focusRing`/`activeAvatarGlow` 绑定 runtime accent（可选色板生效），不再钉死琥珀金。默认仍为暖灰 × 琥珀金。用户已持久化偏好不受影响。
- 首页：搜索胶囊原位展开为玻璃搜索条（品牌/齿轮/头像让位，Esc/取消收起清空，保留提交进全文搜索）；控制卡顶部改为跨功能续接位，稳定优先级为「待处理 > 运行中 > 可恢复 > 可查看结果 > 草稿」，候选来自当前议会房间、深度阅读持久任务、小说项目、AI 生图和最新版本尚未运行的小应用；普通完成态、损坏小说、已运行的小应用版本均不出现，无候选时整行收起，只保留五入口。五入口为单墨色 Phosphor fill 语义图标，顺序为深度阅读/小说创作/模型议会/小应用/WebMount；会话列表恢复切片一体卡外框（72pt、顶/底投影、卡内左缩进 hairline；激活 `activeCard` 随 accent 浅染；空态同卡壳）；控制卡外框保留；新建为右下拇指区浮层强调色混色玻璃胶囊「新对话」（高 42、on-accent 墨；`amberProminentGlass`；非圆 FAB、非假底栏）。列表 scroll 留白 + soft bottom edge。Continue 浅强调色 CTA + 主墨字；账户 38；Continue 显隐 0.30s。入场级联 40ms stagger 且尊重 Reduce Motion。玻璃为搜索胶囊/展开条、齿轮与底胶囊；内容卡零描边，深度仅来自两层 `cardShadow`。
- 图标体系（review 轮重做）：新增 `iosApp/iosApp/HomePhosphorIcons.swift`——21 个 Phosphor fill 字形（路径数据与 home-replica.html 内嵌 symbol 同源；chatCircle/pushPin/imageSquare 取自 phosphor-icons/core 同名资源）+ 最小 SVG path 解析器（M/L/H/V/C/S/Q/T/A/Z，椭圆弧按 SVG 1.1 F.6.5 转三次贝塞尔）。映射修正：剑=sword（原 shield）、天平=scales（原 medal）、AI 生图=imageSquare（原 pen）、crown 关键词补「在位」；swipe/context menu 系统图标保留 SF（系统 UI 层，不在设计约束内）。
- Review 轮修复（4 只读审查代理：逻辑链/几何/渲染/taste）：①会话卡投影从「逐行切片各带阴影」改为「仅 bottom/single 行携带」——消除 72pt 行间阴影接缝，保留原生 swipeActions 的 List 结构（卡顶/侧边投影略弱为已知取舍）；②全局 `glass/glassStrong` 恢复原值（`base(\.background, 0.72/0.85)`），首页三件玻璃改用首页专用 `homeGlass*` 令牌（浅 .78→.58 / 深 .14→.08 白渐变 + 双层投影），与内页材质完全隔离；③几何：header 去掉多余 top 6（精确 42）、「会话」标题与首卡补 12pt、搜索胶囊固定 78×38、展开条 gap7/左12右6/取消 h30 横8/输入 tracking 0.13、会话 meta 接 `.monospacedDigit()`、FAB 底部改 `max(67-safeAreaInsets.bottom, 12)`；④focus 环接入展开搜索条（3px `focusRing`，聚焦时显示）；⑤搜索延迟聚焦改可取消 `Task`（收起/离场撤销，不再残留 FocusState）；⑥`loadProjects` 加 latest-wins revision（首页 onAppear 与项目列表 .task 并发时旧快照不得回写）；⑦级联入场加一次性门控（0.9s 后重建行不再重播）；⑧暖纸 `avatarActive` 修正 `#EADBCC`→`#EADCBC`；⑨节标题用独立 `section` 令牌（与 foreground2 数值同构、语义独立）。
- 第二轮补充修复（多行运行时实测发现）：bottom 行投影向上越界，在上一行底部形成 Δ≤3 暗带并压暗该处 hairline（`#E8E7E6` vs 正常 `#ECEBE9`）；给 bottom/single 行投影加 mask，裁掉行界以上晕影（左右/下方延伸保留卡侧与卡底投影；single 上方是画布，向上晕影与控制卡外投影一致，不裁）。
- 收敛轮补充修复：latest-wins 原实现虽能阻止旧项目列表/错误回写，但旧请求的 `defer` 仍会提前清除较新请求的共享 `isLoading`；收尾现在也校验同一 revision，行为回归测试用两个独立 gate 稳定复现红灯后验证修复。
- 动态续接闭环：议会投影只认可可解码且 `taskId` 匹配的归档，并把持久任务的 failed/cancelled/timedOut/interrupted 映射回可恢复态；当前议会标识恢复 Observation 跟踪，首次开会即可刷新首页。小说项目摘要持久化 `hasRunningRun`，后台脱离恢复后重新加载摘要，首页不再只依赖当前选中项目的运行快照；旧索引首次读取会做一次项目扫描并重写，以免把历史运行中项目误判为静止。
- AI 生图续接：以持久化 assistant `generate_image` Tool 消息为唯一导航真相，记录所属 conversation/message/toolCall；空输出且该会话仍在生成时显示「正在生成」，只有 Tool output 已含真实 Image part 才显示「图片已生成」。点击后以 latest-wins 选择精确会话，并用稳定 UUID + messageID + toolCallID 重新投影验证，再滚到超高 assistant 消息内的图片/加载/失败 tool part；Native driver 与正式 fallback 共用该锚点，只有滚动动画逻辑完成后才消费已查看结果。目标缺失、会话切换失败、仅有成功 JSON 但没有 Image part 时均不消费。
- 无障碍与自适应：Continue 卡、快捷入口、会话行改用 ScaledMetric；辅助功能字号下 Continue 卡纵向排布、CTA 保持 44pt、快捷入口横向滚动且标签最多两行，默认字号几何不变；旋转进度环和图片锚点尊重 Reduce Motion；续接候选新增/替换/消失时为 VoiceOver 发布状态并在消失后回焦深度阅读入口，图片续接按正在生成/已完成/失败把焦点交给具名 tool part，首页在内页期间不抢播报；会话级联只在首次入场播放。
- 数值决策记录：深色卡投影保持原型实测 `rgba(0,0,0,.58)/.76`（规格禁止取整优化，渲染观感问题实际由逐行投影叠加引起，结构修复后即为设计意图）；深色玻璃取原型 `data-mode="dark"` 实测 `.14→.08`（用户规格「10% 白」为概述，HTML 为像素实测源）；FAB 外投影 `radius 10 y 8` ≈ CSS `0 8 20` 视觉等效（SwiftUI radius 与 CSS blur 非字面同义）；功能标签 600 / Continue 标题 600 与 HTML 一致（规格 §4 的 500/640 与自身 §3 字重三档约束冲突，按 HTML 与 §3 执行）。
- 纯逻辑可测：`HomeConversationIcon.icon(forTitle:isPinned:)`、跨功能 `HomeContinueCardModel.resolve(...)` 的空态/优先级/同级时间排序/真实未完成语义、20 字形解析完整性，契约测试在 `iosApp/iosAppTests/HomeDesignContractTests.swift`。

### Verification

- `xcodebuild -skipMacroValidation ... build`：iosApp 与 iosAppExperimentalGPL 双 scheme **BUILD SUCCEEDED**。另从受控 `iosApp/project.yml` 在临时目录运行 XcodeGen：`HomePhosphorIcons.swift` 自动进入稳定/Experimental 双 app target，`HomeDesignContractTests.swift` 自动进入 test target；被忽略的 pbxproj 只是生成物，不是交付缺口。
- 定点测试全绿：`HomeDesignContractTests`（20 字形解析、映射、调色板/accent 实测值、Continue 模型）、`NovelCreationViewModelTests`（含并发 `loadProjects` 旧请求不得清除新请求加载态）、`IOSNovelCreationWiringTests`（该用例原断言旧首页入口顺序 `小应用<小说创作<WebMount` 与 `route:` 参数风格，已按 E 版定稿顺序与 `router.navigate(to:)` 更新；设置页「核心记忆」断言保留）。
- 动态续接回归：`HomeDesignContractTests` + `IOSCouncilRunnerMechanicsTests` + `IOSNovelCreationWiringTests` + `NovelCreationViewModelTests` 合计 **205 passed / 0 failed / 0 skipped**。议会首页投影复用 runner 注入的持久任务 store，不旁路读取全局 store。iPhone 17 Pro Simulator 最新 Debug 包构建、覆盖安装并启动成功。实测未运行的小应用显示为「已生成，尚未打开」，点击进入正确 Runner；返回首页后该候选立即消失，控制卡收起为仅五入口状态。模型议会只投影当前可继续房间，不拿历史归档冒充可恢复任务。
- AI 生图续接回归：直接相关的 `HomeDesignContractTests` + `NativeTimelineScrollCoreTests` **57 passed / 0 failed / 0 skipped**；新增真实 SwiftUI Timeline 超高消息/延迟装载精确 tool-part 回放和真实 `IOSConversationStore` 跨会话持久扫描各 1 项，均单跑通过。扩大到 `ChatViewportPolicyTests`、`ChatSwiftUIStreamReplayTests`、`ChatMessageProjectionTests` 与该持久化用例共 195 项，最终源码下为 **194/195**：唯一未过的是既有 80 行长表格流式性能采样（本轮 p95 40.58–41.04ms > 40ms）；相关生图、投影、滚动和消费契约均通过，未放宽性能阈值，也未把无调用关系的波动修补到生图功能。iPhone 17 Pro Simulator Debug 包构建、覆盖安装并启动成功；无生图候选的真实首页保持仅五入口状态，控制卡无残留占位。
- 模拟器截图（iPhone 17 Pro, iOS 26.5）：三主题像素级核对——画布/卡片/激活带/头像墨色逐点匹配设计值；行高 72、卡左缘 16、FAB 右 21/底 67、节标题间距、「Amber」标题位置均实测通过。注意：本会话 view_image 渲染不可靠（曾把原型样例数据呈现为截图内容），一切以 PIL 逐像素探测为准。
- 多会话多行态运行时验证（容器种子化 4 条会话，updateAt 倒序、首条激活）：行高各 72pt；激活行通栏 `#EFE9DF` + 头像 `#E8DDC6`/`#6F5019`；idle 行 `#F6F5F3` + 头像 `#EDEBE7`/`#8F8B85`；行间 hairline 恰为 sep 合成值 `#ECEBE9`、左缩进 70/右距 16（屏上 x=86..370，与标题字框左缘 86 对齐）；行间零阴影接缝（经第二轮补充修复后 hairline 上下为纯卡色），末行下方 18pt 卡投影带与卡侧投影完整。种子化教训：会话 JSON 时间戳须为合法 ISO8601（`...T14:40:00.Z` 空小数位非法），非法时 `JsonConversationStorage` 按 index 损坏路径扫描重建——实测该容错路径按设计工作（丢弃不可解码条目并自动建新会话）。
- 首页当前会话 meta 交错（时间·条数 ↔ LLM 浓缩预览）：复用 titleModelId；FG/BG 共用 ConversationListPreviewGenerator（latest-wins）；setListPreview 拒绝已删 id；预览落盘 list-previews.json；光晕 56pt 自裁、按压 leading、meta 0.4s 淡切；Reduce Motion 不轮播。
- 当前会话头像呼吸光晕（E 版 `hm-breathe`）：`isCurrent` 头像琥珀 glow 3.4s / 延迟 1.6s，Reduce Motion 关闭；`HomeCurrentAvatarBreath` 强度契约 + 接线源码断言已纳入 `HomeDesignContractTests`。
- 首页 taste 可达性回正（2026-08-07）：右下拇指区浮层「新对话」胶囊（非圆 FAB、非顶栏、非假底栏）。Continue CTA、色带 0.16s、呼吸 glow、切片投影保留。
- 首页视觉层级（2026-08-09）：Continue CTA 改为浅强调色底+主墨字；右下「新对话」改为 accent 混色玻璃+on-accent 墨（`amberProminentGlass`），主强调从黑 CTA 挪到新建。
- Simulator 真实交互复验：搜索胶囊原位展开且 focus 环可见；输入「红酒」后实时只保留匹配会话；Esc 与「取消」均收起、清空并恢复完整列表。相邻页面抽查设置页与 Chat 页，暖灰画布/暖白分组表面层级仍清楚，首页专用玻璃没有泄漏到内页。
- Dynamic Type 实拍复验：iPhone 17 Pro 的 accessibility XXXL 下，Continue 标题/状态/主按钮完整，五入口可横向滚动且标签不压缩为单字列；恢复 Large 后卡片、72pt 会话行、FAB 与原 E 版默认几何一致。截图存于当前 Codex visualization 的 `home-resume-fix/02-max-dynamic-type.jpg` 与 `03-default-restored.jpg`。
- 未覆盖：真实 VoiceOver 开关下的播报顺序/焦点迁移、按压 0.98 回弹的触感/中间帧（自动化只能稳定取得释放后终态）、真机观感与 swipe 手感；模拟器没有可复用的真实生图记录，未支付调用真实 provider，因此「生成完成后从首页进入并滚到真实图片」仍缺一轮真实账号手工验收。「还没有会话」空态卡为生产不可达路径（`bootstrap()` 与 `deleteConversation()` 在列表为空时都自动 `newConversation()`；唯一可达空态是搜索过滤后的「没有匹配的会话」）——结构事实记录，非验证遗漏。

### Known Issues（非本轮改动引入）

- `iosApp/**/*.xcodeproj/` 仍是生成物；受控 `project.yml` 已重新纳入 `NovelCollaborationModeTests.swift` 与 `IOSMemoryRecallPolicyTests.swift`，本机 XcodeGen 生成成功。早先观察到的 Xcode 26.6 私有框架失配本轮未再阻断定点门禁：`iosApp` 已成功 `build-for-testing`，并在 iPhone 17 Pro Simulator 执行了受影响测试；全量测试仍未重跑。
- `ChatSwiftUIStreamReplayTests.testPerfGrowingTableStreamingKeepsDisplayLinkResponsive` 在当前 Simulator 采样不稳定：同一工作区本轮既有通过，也出现 p95 40.823–44.215ms（门槛 40ms）和 SIGKILL；未放宽阈值，需在安静设备/真机重新建立性能证据。
- 首页视觉真机验收仍缺（多数据多行态已在模拟器完成像素级验证，见 Verification；真机观感与 swipe 手感待确认）。

## iOS MiniApp 与 Android 对齐（已提交）

- 截图中的「Amber 小应用示例 + 状态/源码/版本 + 300pt 预览」来自 iOS MVP：生产仓库默认 seed 了固定样例，Runner 又把开发管理面板当成运行首页。现在生产默认不 seed；升级时只删除字段、版本、授权、运行次数、审计和本地数据都完全未变化的旧样例，任何已使用/编辑样例均保留。
- Runner 默认直接显示沉浸式 WebView；返回、标题/版本和右上角管理按钮是唯一常驻 chrome，源码、权限、版本恢复、审计和 bridge 日志收进 large sheet。源码加载、保存版本、恢复版本、外链图片授权和运行策略变化都会刷新实际 WebView，不再只改 SwiftUI 状态。
- 模型协议补齐 MiniApp V3 全能力、自检和长输出约束；截断后的紧邻「继续」会从 `{` 重生完整 JSON，跨过无关用户轮次不会误恢复旧请求。解析、校验、版本冲突或仓储失败会写入可见错误，并把前台/后台 run、Watch、Live Activity 收口为 failed，而不是假 completed。
- iOS bridge 已补 `window.fetch`、`externalImages`、`launch`、加速度计/陀螺仪、一次定位、剪贴板读取、WebView debug 和源码开关；敏感系统能力逐次确认，launch 全局限频，Runtime/WebView 关闭会取消请求、事件、传感器和系统 continuation。`host.sendToConversation` 已真实写入当前 Chat composer；`host.createArtifact` 继续落 Workspace。
- Review 收口：MiniApp 意图会抑制同轮 Generative UI planner，空响应与解析失败均按 failed 终态收口；后台 Workspace 同步失败只按 message id 替换目标卡片，不再拿旧全量快照覆盖并发消息。Repository 普通 mutation 在原子写失败时恢复最近 committed state，失败授权或 shared data 不再泄漏到内存并被后续写入带盘。
- 进程强杀闭环：Chat 生成/修订会把 app/version 与轻量 pending undo 一次原子写入同一 `miniapps.json`；会话正文落盘后前后台路径都先 commit pending，再同步 Workspace。若进程死在两次文件写之间，冷启动会在开放会话入口前扫描持久聊天卡，并用 `appId + version + htmlHash` 精确决议：卡片已落盘则保留 app，未落盘则 CAS 回滚；旧版本卡片不能替新版背书，后续 rename/run/version 改动不会被旧事务覆盖，30 版裁剪时被移出的版本也可恢复。没有引入独立数据库或第二套后台状态机。
- KMP conversation 的 `{id}.json` 是 canonical、`index.json` 是派生缓存；索引刷新失败不再把已原子提交的会话正文误报为 save failure，避免 iOS 随后错误回滚 MiniApp 并留下悬空聊天卡。列表读取仍会从会话文件扫描并机会性修复索引。
- 权限生命周期：首次授权弹窗因离页/重建取消时不再持久化为 DENY；确认返回后复核 app/version/permission/policy/grant。设置或授权变化会重建 runtime 并关闭旧 EventBus/Sensor/请求；EventBus unsubscribe 在撤权后仍可清理，订阅/发布加 Android 对齐的上限。AI 每次调用确认并按 app/day 限 50 次。
- 外链图片不再开放 WebView 直连 `https:`；只允许 `amber-miniapp-image:` 受控代理，复用公网 DNS/私网与重定向防护、HTTPS、image MIME 和 2MB 上限。`host.getTheme` 返回当前 Amber 深浅主题，WebView/错误页透明适配宿主。
- UI review：列表、Runner、管理 sheet、设置页已在 iPhone 17 Pro Simulator 实拍；返回语义、Toggle/源码/权限菜单 VoiceOver 标签、44pt 更多/源码动作/版本恢复、语义字体、窄宽 metadata 回流、设置 divider 对齐、状态色对比、可见 toast/announcement、明确 loading 态均已修。MiniApp 生成协议新增 44×44、320px reflow、lang/label/focus、深色与 Reduce Motion 契约；旧截图所示源码+预览同页已不再是当前结构。
- 为兼容已保存 iOS MiniApp，`Amber.search` 同时支持旧数组用法与 `.items`，EventBus/Sensor 回调同时保留 payload 和旧 envelope 字段。
- 定点门禁：`IOSMiniAppBridgeRuntimeTests`、`IOSMiniAppOutputParserTests`、`IOSMiniAppChatMessageFactoryTests`、`IOSMiniAppRepositoryTests`、`IOSConversationStoreTests`、`IOSParityRedLightTests` 受影响集合合计 **155 passed / 0 failed / 0 skipped**；新增覆盖创建/修订强杀恢复、精确卡片对账、旧卡拒绝、30 版裁剪恢复、冷启动端到端扫描，以及前后台 commit 顺序。`JsonConversationStorageTest` 全类 JVM 门禁 **BUILD SUCCESSFUL**，含 canonical 会话写成功而派生索引写失败的回归；`git diff --check` 通过。iOS 最终门禁需排除当前工作区中范围外且 API 已漂移的 `HomeDesignContractTests.swift`、`NativeTimelineScrollCoreTests.swift`，两者未修改。截图证据保存在当前 Codex visualization 的 `miniapp-audit/`。仍缺真实 CoreLocation/CoreMotion/剪贴板与真实 provider 的设备端闭环；磁盘上仍是两个独立文件，但强杀中间态已有可恢复协议，不再依赖进程内 closure。

## iOS Skill / MCP Chat 对齐

目标：对齐 Android Chat 创建本机 Skill / 连接 MCP 的最小闭环，不过度设计。

- 启动 seed：`IOSBuiltinSkills.installIfMissing` 写入并**启用**必需出厂 `skill-creator`；另 seed 可选出厂 `visual-svg`（**不**默认启用，可删、可恢复出厂）。启动时移除历史 `会议准备`/`监控文档`。`skill-creator` 可编辑/`skill_import` 自迭代，不可删除；未改动的旧英文 / 中文 2.1 出厂快照会自动刷到 **2.2**（吸收上游方法论的移动端轻量版：对话抽意图、小访谈、更主动的 description、写作手法、渐进披露、2～3 条试跑；显式拒绝桌面 eval harness）。详情页空 `allowed-tools` 显示「未限制」；有声明只展示、不运行时裁剪。恢复出厂 / 未改动快照刷新只写 `SKILL.md`；单文件 `skill_import(.../SKILL.md)` `mergeExisting` 保留附属文件，目录导入仍整包替换。
- `visual-svg`（2026-08-09）：一个 skill、两条画法（diagram / illustration），统一 `show-widget`；可选 seed 默认不启用。审查修复：`skill_import` 仅新包启用、可选删除用 `.removed-optional-seeds` 防冷启动回种、描述与 GenerativeUi「直接画图」对齐、`SkillsView` 启用徽标改 `dirName`。
- Chat 工具：`skills_list` / `use_skill` / `skill_validate` / `skill_import` / `skill_enable` / `skill_disable`，以及 `mcp_list` / `mcp_test` / `mcp_import_from_skill`（另保留既有 `mcp_call`）。声明在 KMP `Tool.kt`，执行在 `IOSSkillMcpToolService`。
- 写工具门控：用户说创建/连接/做一个 skill 或 MCP 时也会声明 `workspace_file_write`，不再要求必须出现 `/workspace` 字样。
- 创建路径：`use_skill(skill-creator)` → `workspace_file_write` → `skill_import`；若有 `mcp.json` 再 `mcp_import_from_skill` → `mcp_test` / `mcp_call`。
- Review 精准修复：skill enable/list/use 统一以目录名为键；单文件 `SKILL.md` import 顺带 sibling `mcp.json`；`mcp_import_from_skill` 跳过已存在同名 server；前台 `maxToolResumeCount` 4→6（与后台一致）。
- Skill 安全发布闭环（`be5b956645bd`）：`skill_import` 先生成只读 preview，显式批准绑定 prepared candidate；批准时重新读取 Workspace，并以稳定 base/candidate package hash 和 CAS 拒绝陈旧变更；应用后保留单槽 previous，Skill 详情页按所见 manifest 再验后回退。这个闭环是扩展制品的安全 promotion 底座，不等于已经具备自诊断、独立评测或新工具热加载。
- 自进化与热重载：背景与分期见 [`SELF_EVOLUTION_AND_HOT_RELOAD_PLAN.md`](SELF_EVOLUTION_AND_HOT_RELOAD_PLAN.md)（已修正为风险分级自治授权：T0/T1 policy engine 自动、T2 人工）。**Phase 0–4 全部落地**，全程「实现子代理 → 独立 checker 对抗复核 → 精准修复」循环，最终整体复核 PASS：
  - **Phase 0（证据与身份）**：ledger finished/terminal 结构化 payload 键、`approval_denied` 用户信号事件、按需 evidence projector、promotion/rollback receipt（active+previous 槽）。
  - **Phase 1（Recipe + 热加载）**：`amber.recipe.v1` 声明式 Recipe（schema/validator/store CAS/runner）、`networkRead` 第四 effect 类（search/scrape 升档，审计 38 工具）、versioned dynamic tool registry（snapshot 内嵌 manifest、round 边界 bridge 重建保留暴露集合）、`recipe__*` 前台通用路由、mutation step 审批暂停/恢复（checkpoint 落盘）、`recipe_import` 审批流 + Recipes 管理 UI、引擎级热加载 canary。
  - **Phase 2（诊断/评测/自治发布）**：host-schema 校验的 gap diagnoser（证据不足 no-op）；五层确定性评测（静态校验→契约→failure replay→protected success 硬拒绝→物理隔离 sealed holdout）；`IOSEvolutionSuiteProvider` 从真实 ledger/消息记录构建套件；**Promotion Policy Engine**（T0/T1 自动、T2 人工、neverAuto 硬门禁 + 冷却/日预算/熔断，`policy-state.json` 持久化）；`IOSEvolutionWorkflow`（@Observable 编排，production 走 aux-model one-shot 无工具路径）；T2 完整批准卡 + T0/T1 通知卡一键回退；设置页自治三档 + kill switch。
  - **Phase 3（经验/Playbook）**：经验模型 + CJK bigram 分词 + 字节计量；store（200 条/256KiB 上限、typed 拒绝、32 FIFO tombstone）；curator 五操作（add/merge/update/supersede/delete）+ Jaccard≥0.75 去重 + 对立关键词冲突检测；每轮新鲜注入（6000 字节池与 skill 共享、skill 优先）；设置页经验管理（建议批准/拒绝、归档折叠）。Recipe 详情现为唯一生产反馈入口：只在 ledger 存在精确 `recipe__name@version` 成功终态时允许“这次有帮助/没有解决”；helpful 用有限模板创建或更新带 artifact/version/evidence refs 的 Experience，harmful 精确记到同版本，未有 helpful 规则时只记 ledger 证据、不反向创建可注入规则；阈值仍只产生人工建议。
  - **Phase 4（指标 + 扩面闸门）**：`evolution/metrics.json`（只计数/时间戳/枚举键，无正文）；`wideningGate` 只读（≥5 次晋升、30 天回退率 ≤20%、用户拒绝率 ≤50% 才允许扩面）；promotions/rollbacks 序列 200 条硬上限。
  - **最终整体复核发现修复**：详情页回退与通知卡回退收敛到同一 workflow 公开方法（metrics + 熔断通知不可能再漂移）；protected 回归任何档位硬拒绝（不再可人工批准绕过，引导先更新套件）；建议卡文案与落点一致（「建议停用」）；评估结论文案按证据门禁通过与否分色。复核修正了 4 个对旧 bug 有隐性依赖的测试夹具。
  - 门禁：iOS 全量 300+ 测试绿（以最近一次 xcodebuild 为准）+ KMP `:ai-core`/`:feature:tools:api` jvmTest 绿；`git diff --check` 干净。未验证：真机/真实 provider 端到端、后台 recipe parity（§16.2 明确不声明）、模拟器小屏/大字号/VoiceOver 视觉项。任意 Harness 自改仍不做（plan §24 边界）。
  - **真实闭环收口 Slice A（2026-08-12）**：计划 `/private/tmp/amber-self-evolution-real-loop-closure-plan.md`。评测从「脚本自洽」改为「差分证明修复」，只覆盖一类失败：recipe run 内 workspace 读写原语的参数/binding/路径/schema 错误。`IOSEvaluationWorkspaceScenario` 冻结 baseline exact bytes + failingStepIndex + 可代码验证的后置条件；`IOSArtifactEvaluator` 在全新隔离临时 Workspace 分别跑 baseline（必须同位置同错误类复现，否则 insufficient-data 级降级）与候选（必须完成 + 满足后置条件 + 账本 Started/Finished 成对）；混合 executor（workspace 工具走真实 store 且 ok:false 抛错，其余走 scripted，未脚本化 fail closed）。`IOSEvolutionSuiteProvider` 学会 recipe-run 容器框架（`recipe__*` 容器行 + 带 artifactId/artifactVersion 的 step 行），只对分类成立的失败挂 scenario（活 recipe 版本漂移/非 workspace 原语 ⇒ 无 oracle）。**契约变更**：scripted-only failure replay 无论通过或失败都只产出 manualJudgmentRequired + `no_deterministic_task_oracle` 未决风险（fixture 自洽不再能 promote、fixture 抛错不再能误杀 reject）；T0/T1 自动晋级只对确定性 oracle 可达，而 v1 oracle 只覆盖 workspace 原语（生产目录 sideEffect → T2）⇒ 真实晋升路径收敛为「oracle 评测 promote → 人工批准卡」。生产修复：recipe step 路由把 workspace ok:false 冒泡为 step 失败（§10.3.6 stop-on-failure；普通聊天工具路径不变）——此前这类失败永远造不出 typed evidence。provider 修正：recipe-run case 的 originalFailure/expectedStepCalls 用 baseline manifest 真实 step id（合成 stepN 会把同一失败面误判 narrowed）。门禁：13 套件 161/161；三处 red-revert 探针（后置条件门禁 / scripted-pass 降级 / 生产 ok:false 诚实化）各自产生定点红后恢复；`git diff --check` 干净。未验证：真机/真实 provider 端到端。Slice B（身份绑定）/C（经验回灌）未开始。
  - **真实闭环收口 Slice B（2026-08-12）**：同一份计划的 B1/B2/B3「身份绑定」。**B1**：`projectEvidence` 删除显式会话的 7 天全局穿透（会话无证据 ⇒ 诚实 no-op，跨会话造候选的窟窿封死；全局窗口只留 RecipesView 管理入口 `conversationHex: nil`）；`originRunId` 贯穿 suite（provider 填 failureEvidence.runId）→ report（evaluator 拷贝）→ publish 闸（`IOSPromotionPublisher.publishRecipe` 新增 `expectedOriginRunId` + `reportCandidateHashMismatch` 校验，identity 不匹配 typed 抛错零写入）→ 通知卡；可选字段 nil 缺省不进编码字节，旧 canned 套件 hash 不变。**B2**：recipe step 审批卡 id 从「仅外层 toolCall id」改为复合 `outerId:executionId:stepId`（同 execution 的 step A/B 卡此前同 id，旧 A 卡能批准 B）；coordinator 持 `pendingRecipeRequest`，消费端核对 UI 传回 id，不匹配零执行零清槽并刷新 UI；Watch 批准/拒绝路径同步传 id；recipeImport 卡不动。**B3**：通知卡回退前校验卡上 `candidateHash == 当前 live hash`（卡晋升的版本已不是生效版本 ⇒ push「版本已变化」.failed，零回退不标回退）——此前旧通知能把后来被取代的版本回滚掉。4 个计划红测试（跨会话 no-op / 旧 step id 不能消费下一 pause / 被取代通知回退不改变最新版 / 发布闸 identity 不匹配零写）先红后绿。门禁：13 演化套件 165/165 + 协调器/工具 17 套件 276/276；`git diff --check` 干净。**checker 抓到实现代理 red-revert 探针未恢复（`guard true else` + `.bak` 残留），已精准修复复验全绿**。观察项（非阻塞）：显式会话取最近 terminal run 绑定（计划允许的兜底粒度，多 terminal run 会话对旧消息点分析会绑到较新 run）；nil candidateHash 的畸形通知文案略宽泛（生产路径不可达）。未验证：真机/真实 provider 端到端；Watch 审批路径无运行时测试（无 Watch 套件）。Slice C（经验回灌）未开始。
  - **真实闭环收口 Slice C + 终审修复（2026-08-12）**：Recipe 反馈已接回现有 ledger/curator，无新 outcome DB 或 lineage 表；Experience 只增加 `sourceArtifactId/sourceArtifactVersion` 两个可选兼容字段。终审同时修复三处已证实缺口：Workspace oracle 的 protected 后置条件参数化并复用于 sealed，硬编码已知样本不再 false-green；自动发布通知捕获 exact previous manifest，A→B→A 的 hash ABA 旧卡无法回退新槽；演化审批正文与最多 8 张通知卡改为局部有界滚动，操作按钮留在滚动区外。门禁：generic iOS Simulator `build-for-testing` 通过；5 条必要定点契约通过（Experience 3、sealed 防硬编码 1、ABA 回退 1）。未做 UI snapshot、真机或真实 provider 端到端。
- 定点测试：`IOSSkillMcpToolsTests` + `IOSSkillFileStoreTests`（含 optional `visual-svg` seed/恢复/删除）**TEST SUCCEEDED**。`project.yml` 排除已知 API 漂移的 `IOSMemoryRecallPolicyTests` / `NovelCollaborationModeTests`。
- 未覆盖：真机对话闭环（启用 `visual-svg` 后画流程/插画）、zip skill 导入、按 MCP 工具展开为 `mcp__*` 声明。

## 小说讨论项目写工具（2026-08-11，已提交 89ae266a7）

完整工作记录（plan + 实现 + 三轮复核）：[`NOVEL_AGENT_FIELD_TOOLS_WORKLOG.md`](NOVEL_AGENT_FIELD_TOOLS_WORKLOG.md)。

小说会话讨论工具循环新增 6 个项目字段写工具（`novel_rename_project` / `novel_set_polish_preference` / `novel_upsert_upcoming_arc` / `novel_clear_upcoming_arc` / `novel_revise_material` / `novel_propose_chapter_plan`），全部经既有 reducer 事务（`DefaultNovelCreation.perform`），无平行写通道。

- KMP：Tool.kt 加 6 个 `createNovel*ToolDeclaration()`（JSON schema + 英文描述），**不进 iosToolDeclaration 注册表/ToolSearch**（仅小说讨论作用域）；`NovelProjectToolDeclarationsTest` 7 项 schema 契约。
- iOS：新文件 `IOSNovelProjectToolExecutor.swift`（弱引用 creation + projectID + branchID；JSON 解码 → action → perform；成功返回旧值→新值人读回执；非法参数/非法 kind/arc 超限（>8 条或 >160 字）返回 failed 并说明；`novel_propose_chapter_plan` 恒存 draft）。
- 上下文注入：`NovelModelRequest`/`NovelLiveTransportRequest` 新增可选 projectID/branchID（默认 nil，非 discussion 零影响）；`startGenerationCore` 透传；executor 工厂签名改为 `(NovelProjectToolRunContext?) -> [String: any IOSToolExecutor]`；`ChatToolRuntime.novelDiscussionToolExecutors(projectContext:)` 注册 6 工具（weak `novelProjectCreation` 由 Composition 回填，无环）。
- 声明接线：`makeParameters` 加 `includeProjectTools`——与 executor 注册**共用同一谓词**（`novelProjectContext != nil && discussionTransport != nil`，context 由 purpose==.discussion 且非 GrokWeb 时从 request projectID/branchID 构造），防"声明了但没 executor"悬空。
- 提示词：讨论模板 **v6→v7**（NovelPromptCatalog），新增 PROJECT WRITE TOOLS 段——六工具契约与使用纪律（先收敛再一次写入、rename/revise_material 未明令时先确认且与写入分轮、chapter plan 恒草稿须面板人工确认、代笔中拒绝、上限文案与 executor 校验对齐）；v6 全文归档进 acceptedVersions/systemText，旧 receipt 校验不受影响。
- 代笔守卫：`novel_revise_material` 与 `novel_propose_chapter_plan` 仅在代笔进度 phase ∈ {writing/accepting/collecting/syncing/planning/revising} 时拒绝（对齐 UI `isGhostwriting` 判定）；rename/preference/arc 不挡。**不查 activeRuns**——调用方本身就是一条 .running 的 discussion run（checker 抓到的自锁 CRITICAL，已修并加真实 paused-run 回归锁）。
- 已确认合同保护：`novel_propose_chapter_plan` 遇到 status==.confirmed 的既有合同拒绝落草稿（reducer 只按 planID 判撞不拦状态回退），提示去面板处理。
- 验证：`:ai-core:jvmTest` 全绿；定点四套件 **101/101**——`IOSNovelProjectToolExecutorTests` **15/15**（6 工具 happy path + 非法参数 + arc 超限 + 代笔守卫 + 真实 discussion run 在场不自锁 + 确认合同拒降级 + adapter.start 组装链端到端）、`NovelLiveModelAdapterTests` 27/27（4 处讨论工具集契约改带项目上下文请求）、`NovelGenerationLifecycleTests` 53/53、`NovelPromptCatalogTests` 6/6（快照哈希随 v7 上限文案显式更新）；回归 125 项中仅 `NovelCollaborationModeTests.testGhostwriteSingleChapterHappyPathCollectsAndSyncs` 失败=**纯 HEAD 8e5a222d7 既有基线**（syncFailed，与本 diff 无关）。xcodegen 重新生成工程后 BUILD SUCCEEDED。
- 基线注记：同上——`NovelCollaborationModeTests` 1 用例（syncFailed）、`IOSNovelCreationWiringTests` 6 用例、`NovelSessionViewModelTests` 3 用例为纯 HEAD 基线失败。
- 第二轮复核（checker + UI 审查，2026-08-11 深夜）：逻辑链 PASS_WITH_NOTES。已修——①`decisionLog` 从工具白名单（KMP schema/executor/prompt v7/测试）移除：该类卡 UI 四 tab 不展示、编辑器无法保存，agent 写了会"失踪"，留作 pipeline 专用（后续 UI 支持后再开放）；②custom 卡更新保留既有自定义显示名、不按关联值判等（新增回归测试）。
- 第三轮精准修复（2026-08-12，全部收口）：**R1+R2（刷新链路断）**——根因是 agent 写入经 executor 直接 perform、绕过 VM perform wrapper（唯一会刷新快照的路径）；修复=`NovelCreation.mutationEvents()` 领域广播（每次成功 perform 发布 {projectID, operationID}，协议默认空流、8+ 测试替身免实现），`NovelCreationViewModel` 订阅后复用 `refreshCurrentSelection`（一处同时刷项目列表+选中快照）。**回声抑制**（首轮实现引发 13 个 VM 测试竞态失败的教训）：VM 两个 perform 入口（私有 wrapper + performSessionAction）登记自有 operationID，订阅跳过回声，否则异步刷新与 VM 自身流程中间状态竞争。**R3（会话流零工具痕迹）**——prompt 级修复：v7 补"写入成功后须在回复里说明改动（旧→新），回执不进 UI"，不新建工具胶囊 UI。**D2（custom 卡同名难区分）**——`custom_name` 可选参数端到端（KMP schema/executor/prompt/测试），新建可命名、更新仍保留原名。D3（面板"旧值+预览失效"临时割裂）判为非 bug 不修：字段禁用、预览拒显假数据、终态自愈，行为自洽。
- 验证（终态）：定点五套件 **152/152**（executor 17、VM 49 含 mutation 广播接线测试、lifecycle 53、adapter 27、catalog 6）；`:ai-core:jvmTest` 绿；回归 218 项中 14 失败全在既有基线集（syncFailed 1 例、SessionVM 1 例、wiring 3 例源码断言——断言对象是用户并发 WIP 改动的 NovelMaterialsView，本 diff 未触碰；另有用户连续性审计 WIP 8 文件非本轮范围）。
- 未验证：真实 provider 下模型真实调用 6 工具、真机。
- 附记（2026-08-12，persona 死链删除）：用户定调"只有 amber 一个助手，不需要多助手角色"——缺口地图中 persona/assistant 编辑项**不接线、直接删死代码**。已移除 KMP `IosSettingsMutations.updateCurrentAssistantParams`（~35 行）+ iOS `IOSSharedSettingsStore.updateCurrentAssistantParams` wrapper（~27 行，含 KotlinFloat/KotlinInt 装箱桥接），全仓 grep 零残留；`updateUserNickname` 文档里悬挂的 `[updateCurrentAssistantParams]` 引用改指 `[setSkillEnabledForCurrentAssistant]`。`:shared:compileKotlinIosSimulatorArm64` BUILD SUCCESSFUL、iOS xcodebuild BUILD SUCCEEDED。

## Novel Session Streaming Parity（2026-08-10，进行中）

计划：`docs/superpowers/plans/2026-08-10-novel-session-streaming-parity.md`。

**已落地（本轮）：**
- **A1 终态排空**：`StreamPresentationPacingPolicy` 抽出 `terminalDrainAdvance/DelayNanos/terminalTextAdvance`；Chat 与 `NovelSessionPresentationPacer` 共用；`publishTerminalPresentation` 完成时一次定锚 + 动态拍间隔（不再 36×48ms 慢拖）。
- **B 思考流（presentation-only）**：`NovelModelEvent/FrameEvent/RunEvent.reasoningDelta`；adapter 发思考正文；lifecycle **只 broadcast 不写** `partialContent`/sidecar；Session tail + bubble 复用 `ChatReasoningCard`；结构化 executor 只心跳不并入 JSON 文本。AGENTS 已写防火墙。
- **C1/C2 列表与滚动**：窗口内历史/活动行改 **eager VStack**（去掉 LazyVStack 空洞源）；native driver 跟底改 **explicitBottom(.streamGrowth) 钉底**，避免 τ 缓动欠账双写。

**验证：** `NovelSessionReplayTests` + `NovelLiveModelAdapterTests` **70/70**；VM 终态/围栏/整章 **3/3**。审查修复定点 **4/4**（lifecycle 污染、session reasoning 呈现、eager 契约、adapter reasoning-only）。真机未验。

**审查修复（精准）：** `NovelModelScript.reasoningDelta` + emit；`capturedEvents` 捕获思考；lifecycle/session 污染与呈现锁；stream-growth snap 在 userDragging/UIKit interacting 时 no-op。

**A2/C3/D 收口（2026-08-10）：** 呈现缓冲对 manuscript 围栏归一化（pacer/bubble 同源）；非前缀 replacement 按公共前缀限速推进；生成状态条覆盖终态呈现窗 + minHeight 28；上下文环 `supportsReasoning` 随当前 tail 思考态。定点 7/7 绿。

**审查 2 精准修复：** `isTerminalPresenting` 含 quiet retire 窗（flag 已清但 tail 仍为 `.terminalAwaitingRefresh`），避免完成后状态条先塌 ~28pt。不改 reparent/refresh 粘 busy（无真机证据 / 既有语义）。

**真机安装（2026-08-10）：** iPhone Air（iPhone18,4，coredevice `94918570-0680-5B93-8E38-7E6B355D4426` / UDID `00008150-000A594E0AF8401C`）Debug arm64 覆盖安装，未卸载。Team `89QRFX9548`，`codesign --verify --deep --strict` 通过，`app.amber.ios` 已 launch。容器 `60BD71D5-7B03-4578-8D65-43C4B9203792`。含小说流式对齐全切片（A1–A2/B/C1–C3/D + quiet 状态条）。

**未做 / 下一刀：** 真机 D1–D6 手感验收（思考卡 / 贴底 / 空白 / 完成瞬间）。

## Current Product Truth

- 当前工作主线是原生 iOS + KMP 共享能力。`app/` 是 Android 应用，不代表 iOS 运行时。
- 默认且唯一生产 Chat 列表路径是 `NativeChatTimelineView`。`ChatSwiftUIMessageList` 只在 `CHAT_PERF_REPLAY` 下保留，UICollectionView 路径只作非默认回归。
- 小说创作已经具备创作 / 正文 / 设定三入口、独立创作与剧情同步模型、Quick Start、资料建议、收录、编辑、剧情同步、分支/Fork、整章润色、导入导出和中断恢复。小说项目文档是领域权威，普通 Chat/Memory/Workspace 不是小说存储。
- 共创模式现已与规格对齐：`needsSync` 时仍可讨论，但正式正文生成、整章重写、润色和对应 retry 均失败闭锁。共创 / 代笔双模式计划见 `docs/NOVEL_COCREATION_GHOSTWRITE_PLAN.md`（Active；Phase 0–3c 完成，下一刀真机验收）。
- Chat、小说和模型议会在页面退出后由 App 级 owner 继续持有运行；iOS 本地后台仍受系统调度约束，不等于无限后台。
- 官方 OpenAI API-key 的普通 Chat，以及小说正文、重新生成和单章润色，已接入服务端 background response + cursor 恢复。Quick Start、讨论工具循环、模型议会、MiniApp AI、DeepRead 及其他 provider 的计算仍是本地 best-effort；它们复用 shared run 外壳做诚实终态与冷启动对账，不宣称跨进程继续计算。
- Live Activity、锁屏卡和 continued-processing task 按 `runId` 独立管理；旧 run 的完成、取消、深链和系统移除回调不得作用于新 run。

## Novel Collaboration Mode Phase 0 / 1 / 2

- Phase 0：`canStart(.prose)`、`NovelGenerationReducer`、injection planner 与 prose retry 投影均要求分支 `synchronized`；同步横幅与写正文占位对齐；精确重试同步 composer mode/granularity。
- Phase 1：项目 `collaborationMode`；分支级 `NovelChapterPlanRecord`（草稿/确认 + digest）；顶栏「项目控制」面板可切共创/代笔（切代笔做策划包就绪检查，不强制本章合同）；确认合同注入整章 prose；代笔写整章无确认合同则 UI/`canStart`/reducer/retry 挡；收录仍人手。
- Phase 2（验收深度 B）：候选/run 绑定 `chapterPlanDigest`；收录 digest 不匹配则拒；`systemAutoCollect` 来源；结构化 `chapterPlanAcceptance`（stateSync 模型）；SessionViewModel 单章 pipeline（写→验收→自动收录→同步→暂停）；面板开始/暂停/继续；进行中硬切共创挡。
- Phase 2 review 修复：复用候选只认 `progress.candidateID`；收录成功即清合同；继续按钮严格按 `canStart`；代笔中禁用合同编辑与模式 Picker；合同错误提示就近显示；清除需确认；合同字段常驻标签。
- Review 修复：协作模式/清除合同的历史账本校验不再断言当前态；`canStart` 按 run 粒度判断；面板 sheet 改为 large、合同按钮 44pt、IME commit、Picker 本地回滚。
- 完成计划后的生产 review 修复：代笔开始时重新校验策划包、本章合同与当前主分支；项目级模型 Picker 等待持久化成功后再关闭，失败留在原位并提示；小说 composer 输入命中区不低于 44pt；元信息切换遵循 Reduce Motion。
- Watch `WKCompanionAppBundleIdentifier` 改为字面量 `app.amber.ios`；`IOSMiniAppBridgeRuntimeTests` 的 `async let` 断言改为先 await 再 XCTAssert。

### Phase 3a — 跨章防复读（薄回执）

- Snapshot 携带有界 `recentWrittenHighlights`；收录后的 `finalizeCollection` / 手动同步 finalize 用新事件 summary 合并截断；旧文档缺字段 decode 为空。
- 整章 prose 注入 `RECENT WRITTEN BEATS`（计入 required 预算预检）；续写不注入。
- 验收 prompt `novel.chapter-plan-acceptance.v2`：必填 `obviousRepetition`；decoder 兼容 schemaVersion 1。
- Pipeline：合同通过但 `obviousRepetition` 非空 → 暂停不自动收录，继续时重写不复用同稿；收录后按 binding 清合同，失败则停；完成后 detail 提示已记入要点条数。
- Review 修复：失败 detail 用红色 Label；代笔中合同字段禁用；workspace 清合同后字段回填；代笔按钮补 contentShape。

### Phase 3b — 连续性软门

- 项目偏好 `pauseGhostwriteOnBlockingContinuity`（默认 true；旧文档缺字段 decode 为 true）；面板 Toggle「连续性出现「严重」问题时暂停」。
- 代笔：合同验收通过且无复读后、自动收录前调用 `auditContinuityIncludingCandidate`（已有正文 + 候选下一章）。
- 仅 `blocking`（界面「严重」）暂停不收录；`failedChunkCount > 0` 亦暂停（审计未结论不得放行）；`major`/`minor` 不挡。
- 继续：同 binding 保留 `autoCollectedCandidateIDs` 与可复用候选；复读/严重连续性强制重写；已 collected 同 digest 不再写第二遍。
- Review 修复：`ghostwriteProgressStorage` 可观察；owned-run 取消；blocker 文案与 Toggle 错误就近显示；`.cancelled` 非红错。

### Phase 3c — 审稿模型 / 下一弧 / 代笔看板（薄可交付）

- `NovelModelRole.review`：项目 `reviewModelPolicy` + App 默认偏好；设置页与项目设置暴露「审稿模型」；合同验收与连续性审计走 `.review`。
- 分支级 `NovelUpcomingArcRecord`（最多 8 条）；整章 prose 注入 `UPCOMING ARC`；面板「下一弧」保存/清除；续写不注入。
- 面板拆「代笔看板」（只读回执）与「代笔推进」（Toggle/按钮）；审稿显示走 effectivePolicy + displayName；步骤回执用短码，暂停原因只在 detail。

### Phase 5 — 多章代笔自纠正（2026-08-09）

- 设计：`docs/superpowers/specs/2026-08-09-ghostwrite-self-heal-loop-design.md`。
- **已落地**：P0–P3；P2 基建重试/指纹熔断/复读 mustNot；**单条 must 措辞对齐**（仅缺 1 条、无禁止与复读、每章一次，允许等价表达并记 amendments）；跨进程 sidecar 进度。
- **未落地**：真机多章批跑 + 杀进程恢复验收。
- 门禁：`NovelCollaborationModeTests` **36/36 PASSED**。

### 代笔链路闭环复审修复（2026-08-10，真实使用「一篇都出不来」驱动）

- 复审范围：写→验收→连续性→收录→清合同→同步→次章拟合同全链；结论全部钉死为真问题、无假阳性（Grok review 作参考）。
- **B1 预算墙**：代笔写稿 `inputBudgetTokens` 硬编码 16_000，常驻资料一多必撞注入预算墙（required 检查在 smart 裁剪之前）。现改为 `NovelGhostwriteBatch.writeInputBudgetTokens = NovelStructuredModelExecutor.maximumInternalInputBudgetTokens`，仍由 `effectiveInputBudget` 按窗口与输出留位收敛。
- **B2 审计未完整误强制重写**（Phase 5 回归）：`.continuityAuditIncomplete` 从 `requiresRewriteOnContinue` 移除；连续性门只在 `.blockingContinuity` 时 `qualityAttemptIndex+1` 并作废候选，审计未完整只留回执；`obtainGhostwriteCandidate` 的 mustRewrite 自愈三重条件加 `pauseReason == nil` 判别（暂停态一律以 pauseReason 为唯一权威）。
- **B3 基建错误误标质量失败**：新增暂停原因 `.infrastructureFailed`（不重写、可续跑、不进自愈、phase→failed、不带确认合同可续）；`failedReason(from:)` 全部抛错归基建（保留 invalidInput 的「不完整/合同/计划」子串映射）；外环 catch 不再把传输抖动变成强制重写循环。
- **验收/连续性审计补有界基建重试**：`NovelGhostwriteInfraRetry.run`（3 次、退避 400ms×attempt、仅 retryable 且非 `cancelled`；取消立即透传），与章计划拟定的 3 次重试对齐；VM 包裹层在进度面板提示「验收调用失败，正在重试 k/3…」。
- **U2 中文错误透传**：`operationErrorMessage` 对中文 invalidInput 原样透传，不再统一抹成「请重新载入后再试」（英文未知详情仍走兜底）。
- **U3/U1 露出**：代笔推进面板显示 `operationErrorMessage`（与红色 detail 去重）；主会话 composer 上方新增最小代笔状态条（状态+详情+暂停/继续，继续走 `canStartGhostwriteChapter` 门，44pt 命中区），中断后主界面可直接续跑。
- **测试**：新增谓词测试（审计未完整保留候选、基建原因 flags、`failedReason` 映射表）、重试 runner 四场景、预算常量契约、中文透传；**补上单章 happy-path VM 集成测试**（此前批循环零覆盖是本次漏检根因）——写→验→连续性→收录→清合同→stateRebuild 同步→chapterCompleted，断言恰好 4 次模型调用。注意：收录后的同步执行 `stateRebuild`（manualSync 交易），不是 stateDelta。
- 验证：`build` 成功；`NovelCollaborationModeTests` + `NovelCreationPresentationTests` + `NovelStructuredModelExecutorTests` 定点绿；回归门禁 `NovelSessionViewModelTests`/`NovelSessionReplayTests`/`NovelStructuredOutputTests`/`IOSNovelCreationWiringTests`/`NovelInjectionPlannerTests`/`NovelGenerationLifecycleTests` 全绿（iPhone 17 Pro Simulator，串行）。
- 未验证/刻意不做：真机批跑与后台寿命（B4 首 token 系统卡升级为工作区既有未提交改动，非本轮）；fail-closed 提示词严格度与自愈预算属产品决策；sidecar fire-and-forget 持久化小窗由自愈兜底；240s 同步墙钟有界可恢复。


### Phase 4 — 有界多章代笔（最多 10 章，2026-08-09）

- 全自动连写（方案 1）：**首章仍须用户确认本章合同**；第 2～N 章走结构化 `chapterPlanProposal`（创作模型）自动拟合同并 `upsert confirmed`，再写→验→收录→同步。
- 批字段挂 `NovelGhostwriteProgress`：`targetChapterCount`/`completedChapterCount`/`currentChapterIndex`（1…10）；相位新增 `planning`；暂停原因 `planProposalFailed`/`batchCompleted`；**`pendingSyncChapterCredit`**（收录+清合同后待同步记账，防 syncFailed 续跑少计章越过 N）。
- 失败即停（验收/复读/严重连续性/收录/同步/拟合同/用户暂停）；同步成功前不得开下一章；收录成功仍立即清合同。
- 续跑：`syncFailed`/`planProposalFailed`/待记账 可不带确认合同继续；复读/严重连续性继续强制重写。
- 面板：Stepper 选本批 N；按钮「开始代笔本章」或「开始代笔 · N 章」；批完成后显示「开始」非「继续」；看板 k/N。
- 新文件：`NovelChapterPlanProposalLifecycle.swift`；XcodeGen 已重新生成工程。
- Review 收口：逻辑 S1（syncFailed 计章）+ M1（planning 取消）+ UI M1/M2/M3 已修；定点 35/35 绿。

### Verification

- `NovelCollaborationModeTests` **29/29 PASSED**（含批 clamp/看板、pendingSyncCredit、proposal decoder fail-closed、proposal context）。
- `NovelPromptCatalogTests` **6/6**；与协作合计 **35/35 PASSED**（2026-08-09）。此前结构化执行器 10/10 亦绿。
- 真机：代笔多章批跑 2～3 章、自动拟合同质量、后台寿命 — 仍待设备验收。
- 下一刀：真机批跑验收。

## Current Review Fixes

- 模型自主性护栏拆除第二梯队 G6-G10 已实施并提交：widget 重试改「保留草稿 + 追加补绘 notice」（不剥工具、二次失败有中性失败终态）、工具循环上限参数化 `chatMaxToolResumeCount`（默认 12、clamp 4-24）、小说讨论模板 v5→v6 软化（v5 归档 receipt 兼容）、上下文压缩加工显式标注（`[tool output compacted]` 占位 + 移除计数 + 截尾注记）、记忆召回默认 24 条/6000 字符且 `memory_tool` 只读动作（read/search/query/status）补齐真实执行语义。细节与验证限制见 `docs/MODEL_AUTONOMY_GUARDRAILS_PLAN.md` 实施状态节；本轮已完成真实 `build-for-testing`，受影响 Chat/Background/SubAgent/Memory 测试 173/173 通过。
- G4 权限收口已提交：未引入通用 run-scoped grant，也未改变 Search、Workspace、Council 等既有策略。仅修复 SubAgent 的真实越权路径：`askEveryTime`（以及历史 `allowOncePerRun` 兼容值）在前台必须走审批卡，后台拒绝并要求回到 App；只有 `autoApprove` 维持静默执行。相关通用权限扩张与测试已撤回。
- 2026-08-08 全项目深审确认修复已提交：iOS 收口 Chat/Background 补绘、handoff、取消/恢复状态，SubAgent 权限/预算/取消，Keychain 清理，CJK 召回，MCP 同源 endpoint/取消/并发流/完整 schema，Board/MiniApp WebView 信任边界，DeepRead owner 取消，Memory Int32 边界，内置 Skill 保护及私密日志；KMP/Android 收口 Settings 并发 RMW、AgentTask/Cron 持久化与恢复、运行时 map 竞态，Sync 完整性/补偿回滚，Document 解压/输出上限与 MuPDF 释放，并移除会拒绝本版本自身备份的任意 64 MiB/100k 限制；Native 修复 FFI byte buffer 分配/释放 layout UB，并将声明与 CI MSRV 对齐为 Rust 1.86。
- 深审验证：iOS Chat 八类定点 173/173；MCP/Board/DeepRead/MiniApp/Memory/Skill 三组 111/111，MiniApp fragment 修正后 22/22 复跑；KMP `:feature:task:jvmTest` 及相关 JVM/iOS 编译门禁 `BUILD SUCCESSFUL`；Native workspace 122/122 通过（另 1 个 doc test 按既有标记 ignored），`amber-ffi` 定点通过，JNI 19/19 与 Swift export 10/10 契约检查通过；`git diff --check` 通过。Android `document:testDebugUnitTest` 仅因本机缺少 Android SDK 在配置阶段阻断，不是产品测试失败。
- 深审残余边界：Sync 跨 DataStore/密钥存储/SQLite/文件系统只能做写前校验与补偿回滚，进程死亡下不是单一 ACID 事务；大 table 仍整条 JSONL 进内存；真机 BGContinuedProcessingTask/强杀恢复、WebKit 实际导航时序、Keychain 持久化与 Android 真机大备份内存峰值仍未验证。
- 已提交两项批准改动：① 工具声明去除关键词门控——`workspace_file_write/edit/move/delete` 与 `workspace_artifact_delete` 在 `workspaceToolNamesForCurrentTurn()` 恒声明（原 Skill/MCP 单写、显式保存等关键词判定函数已删除）；iSH 内置执行（`ios_ish_execute`，保留 `ENABLE_EXPERIMENTAL_TERMINAL_RUNTIMES` 编译条件）与外部交接（`ish_handoff`）同时恒声明，不再二选一；执行层审批（`IOSLocalToolExecutor`/能力策略过滤）与 `ChatContextSupport` 工作区策略软提示均未动。② `core/types` 的 `Settings.enableWebSearch` 默认值 `false`→`true`（影响 iOS 种子快照与 Android 默认；`ChatPrefs` 中间层与审批/SSRF 防护未动）。测试按新契约翻转：`ChatViewModelGenerationParamsTests` 原「无关键词不声明写工具/iSH 二选一」用例反转为恒声明契约，并补「无写关键词消息仍声明全部 workspace 写工具」用例；`IOSSkillMcpToolsTests` 两个关键词用例与新契约仍兼容未改。验证：`git diff --check` 通过；`:core:types:jvmTest`（NO-SOURCE）与 `compileKotlinIosSimulatorArm64` 成功；两个 Swift 文件 `swiftc -parse` 通过。本轮后续 `build-for-testing` 已成功；该条原有 Kotlin 门禁保持通过，未放宽断言。
- 对 `da71c8597^..HEAD` 与当前 WIP 的复核补齐：MiniApp handoff 继续抑制 Generative UI；前后台 widget 补绘保留已经完成的工具轮次；后台 checkpoint 写失败不再把缺失必填视觉结果收为成功。
- MiniApp 授权 alert 被 SwiftUI 清空 Binding 时只取消等待，不持久化 DENY；代笔把“连续性审计未完成”与真实严重连续性分开，继续时保留已验收候选；owned durable run 的取消不再依赖展示态 `activeRunID`。
- 切回共创的 reducer 现在以项目内真实 running run 为硬闸；普通 Skill 导入禁止覆盖内置包，内置 seeder 使用显式特权路径。
- `workspaceToolNamesForCurrentTurn` 当前被并发的自主性 WIP 改为恒声明全部写/移/删工具，与 Draft G1 一致但尚标注“待用户裁决”；这与 review 中“Skill 意图不应扩大危险工具面”的产品判断冲突，本轮不覆盖该并发改动，必须先统一策略。
- `use_skill` 的 `mcp.json` 拒读、文件大小上限和 URL 凭证脱敏，以及失效的 Live Activity wiring 测试锚点，均已在同一 WIP 收束。“已查看生图后是否回显更早完成图”按当前单条最新完成态契约保留，不改造成未读队列。
- MiniApp 生成/修订现在以目标 app 的状态切片记录本次 mutation；前台或后台 conversation 保存失败时，只在该 app 未被后续修改的前提下恢复原记录与版本，不影响其他小应用。
- Workspace artifact 改为 conversation 首次保存成功后再同步；同步失败会保留可运行的 MiniApp 与已落盘聊天卡片，并在当前消息中显示失败原因后尝试补存提示。
- MiniApp 卡片导出缺失记录或临时文件写入失败时显示 alert；操作按钮保持原 bordered 视觉，提供 44pt 命中区，并在横向空间不足时切换纵向布局。
- SVG“保存”胶囊保持 28pt 视觉，只把交互命中形状扩大到 44pt。
- iOS 核心记忆在 `AppShell` 首次渲染前同步加载；损坏文件会保留原件并停止写入，缺失文件清空内存态，所有新增/编辑/删除在原子写失败时回滚完整快照并返回失败。
- Chat 召回统一使用运行时 `maxItems` / `maxPromptChars`、scope、归档/过期与相关性策略；实际进入 provider 请求的记录会只更新 `lastUsedAt` 并持久化，不再把编辑时间误当使用时间。
- 模型写入审批会区分保存、编辑、删除；删除展示目标正文并使用 destructive 语义，被拒绝或 scope 禁用的正文不落审批历史。记忆页补齐可观察刷新、陈旧编辑保护、错误反馈、审批历史清除和 44pt 交互区域。
- 核心记忆专用测试 **14 passed / 0 failed**，工具写入/审批链路定点 **5 passed / 0 failed**；iPhone 17 Pro Simulator 已实屏核对记忆主页首屏，审批卡真实触发态与真机 Dynamic Type 仍待设备验收。

## Generative UI Current State

目标是在 iOS 原生 timeline 中复用现有流式 `show-widget` 解析与安全 SVG 渲染，补齐 Android 已有的视觉意图路由、模型提示和终态兜底，不新增第二套渲染器或滚动 owner。

本轮新增：完成态且安全净化后的 SVG 卡片右上角提供 44pt“保存 SVG”命中区；通过系统 Files 导出从净化 HTML 中提取的 `.svg`；不导出 raw `widgetCode`、slides 或 full_html 封面预览；导出失败显示 alert。相关契约测试覆盖净化提取、完成态/安全门控和安全文件名。

- `GenerativeUiPlanner` 已从 Android app 下移到 `core:ai:generation:api`，并与共享 show-widget prompt/protocol 一起导出给 Swift；Android 保持原 FQN 调用。
- iOS 请求入口按真实用户意图注入共享 prompt：直接流程图/架构图/PPT 请求不暴露无关工具；图片生成和需要搜索、文件、skill、subagent 的请求保留工具路径。
- 直接 SVG/PPT 请求的流终态必须含完整可解析的 show-widget；缺失时清掉坏的可见 assistant 草稿，关闭工具与 reasoning 后最多重试一次。第二次仍失败就保留真实模型结果，不伪造本地 SVG，也不循环重试。
- 需要搜索、文件或其他工具的画图请求仍保留工具链，但最终 assistant 同样必须交付图；工具执行前后的 background handoff 会重新注入同一视觉协议 prompt。
- 这份要求和“是否已重试”随 iOS 前后台 handoff 持久化；后台补绘开始前原子写入补绘请求和 attempted checkpoint，输出上限截断但没有未闭合工具调用时仍可补绘一次。
- foreground terminal 以 `runId` 校验所有权，旧 run 的异步保存/Live Activity 回调不能清掉新 run。后台 expiration 在异步 finalize 前即暴露 terminal owner，save continuation 不再漏掉已抢占的终态。
- full_html 只通过专用 validator 成为完成卡片；共享 `amberagent.local/full-html` runtime URL 会改写到本地资源，非法 deck 不再靠静态封面伪装成功。
- `[ROUTE:image|svg|diagram|slides]` 仍参与路由，但原生用户气泡不再显示该元数据。partial/complete 卡片 identity 保持稳定；卡片初始/最小高度为 96pt，操作按钮 44pt 且窄屏纵向排列，WebView 流更新按一帧合并。结构化折线不再越过标签，单节点 flow 居中。

### Verification

- “保存 SVG”导出 helper 的 Python 镜像契约已核对：只提取净化 SVG，partial/unsafe/非 SVG 均被门控；`git diff --check` 通过。
- 本机定点 `xcodebuild -only-testing:iosAppTests/IOSGenerativeWidgetParserTests/testSVGExport*` 未能编译：沙箱无法写 `~/.cache/clang/ModuleCache` 与 SwiftPM ManifestLoading 诊断文件，且 CoreSimulatorService 不可用；这不是本次按钮逻辑失败。
- `GenerativeUiPlannerTest` 与 `IosChatBackgroundPayloadJsonBridgeTest` 的 JVM 定点测试均 **BUILD SUCCESSFUL**。
- SVG/parser、full_html runtime、前台 stale-run 和后台 expiration 定点：**22 passed / 0 failed / 0 skipped**；完整 `ChatViewModelSelectedFileContextTests`：**63 passed / 0 failed / 0 skipped**。
- 扩大到 `ChatSwiftUIStreamReplayTests`、`NativeTimelineScrollCoreTests`、`ChatViewportPolicyTests`、`IOSParityRedLightTests`：**170 passed / 1 failed / 0 skipped**。唯一失败是范围外的 24KB 纯文本 pacing 契约冲突：当前实现允许单拍 36 字，测试要求绝大多数更新不超过 24 字；隔离复跑仍失败。该用例不经过 widget parser/card，也未放宽阈值。
- Android app 的定点 `GenerativeUiPlannerTest` / `GenerationPromptsTest` 被当前工作区中范围外的 Model Council 缺失符号阻断在 app 编译阶段；共享 planner 自身的 JVM 测试已通过。

### Remaining Acceptance

- 真机使用至少一个 OpenAI/Claude-compatible provider 发起“画流程图/架构图”请求，确认 SVG 在同一条 assistant timeline card 中随流逐步出现，结束后无需重试或至多自动重试一次；完成后点右上角“保存 SVG”，确认 Files 导出可用、文件名安全、失败有提示。
- 再验证图片请求仍调用 `generate_image`、需要外部上下文的视觉请求仍先完成工具链、PPT 最终落为完整 `full_html` deck。
- Simulator 性能探针不能替代 ProMotion 真机的卡片高度增长和滚动手感验收。

## Novel Streaming Current State

目标是消除小说长文流式生成中数次大幅高度跳变及终态最后一拍闪烁，不新增滚动 owner 或几何补偿。

- Chat/小说共享 pacer 的单拍上限从 64 收紧到 36 个字符，仍沿用 48ms 节拍。
- 小说 completed、interrupted、failed、persistence-blocked 先按现有节拍排空可见正文，再切 durable terminal；排空期间关闭 Stop，并继续用既有 busy gate 阻止下一次 mutation。
- 终态规范化从当前可见正文前缀继续 pacing，避免模型误包 Markdown 围栏时整章一次替换。
- 小说 Native Timeline 复用 Chat 已验证的 UIKit 手势判定，程序化 `.interacting` 不再被误判成用户上滑。
- cancel 或 binding 失效会撤销排空标记；persistence-blocked 排空后仍回到原有重试入口。

### Verification

- 相关门禁：`NovelSessionViewModelTests`、`NovelSessionReplayTests`、`NativeTimelineScrollCoreTests`、`ChatViewportPolicyTests`、4 条 SwiftUI 长文/终态回放及共享 pacing，共 **206 passed / 0 failed / 0 skipped**。
- 扩大到完整 Chat SwiftUI：**223 passed / 1 failed**。唯一失败是独立完整 Markdown 表格 display-link probe 的 simulator 时序阈值（p95 约 40.6ms 对 40ms）；隔离复跑有过有败，未放宽阈值，也没有据此修改产品路径。
- 最新产品包已用 Team `89QRFX9548` 完成 iPhone Air Debug arm64 构建，`codesign --verify --deep --strict` 通过。
- 含「保存 SVG」的主包已于 2026-08-05 19:41 覆盖安装并启动 `app.amber.ios`；安装容器 `0693C392-DCFC-4B56-8DE1-EE37945B4DFF/iosApp.app`；主 Debug dylib SHA-256：`e4062b24644f92abc199f50dd84562ec349d868c11113c45df498eb0ba51a8a6`（二进制含「保存 SVG」）。覆盖安装未卸载，应保留既有数据。Files 导出与 SVG 卡按钮可见性仍待手测。

### Remaining Acceptance

- 在真机用真实 provider 生成长章节，观察中途高度增长、终态切换和轻拖上滑期间是否仍闪烁或被拉回底部。
- 真机 ProMotion、rubber-band、键盘安全区和后台系统到期行为不能由 Simulator/单测替代。
- 该 slice 已随 `61c3b4e46` commit/push；本页顶部列出的 `58b473837` review fixes 已构建并覆盖安装到真机，但长文手感仍需真实 provider 操作验收。

## Recently Landed Baseline

- `da71c8597`：修复 Quick Start 宽容解析边界、失败草稿污染、上下文人物建议和小说流式滚动；受影响小说回归 **439 passed**。
- `086e57525`：补齐长时间流式任务的本地后台所有权和官方 OpenAI 小说生成的服务端恢复路径。
- `5a767e480`：完善小说流式生成、Ask User、资料与设置交互。
- `db7371fcb`：修复交互终态串线、中文输入最后一拍和小说编辑内容失真。

## Current Priorities

1. 先完成 Generative UI 的真机真实 provider 验收，重点看同卡片渐进 SVG、一次兜底边界、右上角“保存 SVG”导出、图片工具路由和 full_html deck 完整性。
2. 完成当前流式实现的真机长文手感验收；若仍跳变，记录发生阶段、是否触摸屏幕、是否终态以及可见内容变化，再沿现有 owner 定位。
3. 只在真实复现支持时继续调整 pacer、终态排空或 Native Timeline 手势判定，不加第二套状态机或 offset 补偿。
4. 后台能力下一步只补真机与真实 OpenAI 账号下的 submit / adopt / expiration / 强杀 / 冷启动恢复证据；其他 provider 不伪装成服务端 durable job。
5. 小说共创 / 代笔：Phase 0–3c 已落地；下一刀真机验收（单章闭环、审稿模型、下一弧注入、看板回执）。
6. Android 小说复刻属于 Android 主仓；本仓的 `NOVEL_CREATION_ANDROID_IMPLEMENTATION_PLAN.md` 仅是跨仓草案。

## Known Risks

- Chat 思考/工具胶囊图标（未提交，已装机）：思考用 Koboyo `solidThoughtCloud`；工具胶囊 leading 统一 Koboyo `solid*`（search/globe/monitor/eye/camera/document/pen/image/terminal/puzzle/users/peopleGroup/brain/code/wrench），`ChatToolVisualKind` 细分 wm/workspace。进行中轻呼吸。顶栏活动岛/Live Activity **仍用 SF**，不动。验证：`ChatToolGlyphMappingTests` 4/4、`ChatToolTimelineWidthOverflowTests` 3/3、思考 path 解析通过。
- Chat 左右对称溢出修复（未提交，已装机）：根因是段落 ideal 宽广告整屏 + HStack/Spacer 与横滑表/码块撑破列宽。精准改动：vendor 段落/列表/表格绑列宽、`ParagraphUIView` 无 superview 时 `noIntrinsicMetric`、nil proposal 只回退 `lastProposedWidth`；`AmberMarkdownView` 宽表与不换行代码块加列内横滑上限；工具胶囊 hug+列宽上限、WebMount 标题认 `display_name`/`name`。未做全局 inset 统一或 vendor opt-in。验证：vendor `ChatColumnWidthOverflowTests` 4/4、`ParagraphUIViewWidthClampTests` 2/2；`ChatToolTimelineWidthOverflowTests` 3/3；`ChatViewportPolicyTests`+`ChatMessageProjectionTests` 122/122；真机覆盖安装并启动成功。**缺口**：镜像/真机视觉验收（长 CJK ~16pt 留白、DSML 列内换行、短工具名仍 chip、WebMount 人读标题）。
- “保存 SVG”契约 helper 与完整 `IOSGenerativeWidgetParserTests` 已通过；Files picker 与真机导出体验仍缺设备端证据。
- Generative UI 的终态契约已经自动化验证，但模型是否能在真实 provider 的 token/window 限制内稳定输出完整 SVG/full_html 仍需真机与真实账号证据。
- 当前 Chat pacer 上限 36 与 24KB 长文门禁的 24 字要求冲突；这是与 widget 无关的既有问题，需决定恢复 24、调整发布策略或同步契约。
- Android app 回归当前受范围外 Model Council 编译缺口阻断；不能把共享 planner 测试通过等同于 Android app 全门禁通过。
- 长表格 display-link 性能探针在 Simulator 40ms 边界附近波动，尚无足够证据修改 renderer identity、发布 owner 或测试阈值。
- iOS continued-processing 由系统决定调度与终止；App Switcher 强制结束后，只有已持久化服务端 response/run ID 的段可恢复，其他 provider 流和本地工具阶段会诚实收为未完成。
- 服务端恢复在首个 `response.created` cursor 落盘前遭进程终止时没有 response ID，无法恢复；还受服务端保留窗口和账户数据策略约束。
- 历史消息行内公式 `mathInline` 仍缺已接受的渲染设计；不要用字符串替换或额外 layout 分支硬补。
- 后台到期已覆盖单轮累计 partial，但仍缺“完成至少一轮 assistant/tool 后，下一轮 expiration”完整保留既有 suffix/tool output 的端到端契约。
- 小说真实 provider 全流程、长文手势、后台到期、Files picker 和最终视觉仍需设备证据；构建、安装和单测通过不等于这些体验已验收。

## Canonical References

先看 [`README.md`](README.md) 的主题地图。当前常用权威入口：

- 工程规则：`AGENTS.md`、`iosApp/AGENTS.md`
- 小说局部规则：`iosApp/iosApp/NovelCreation/AGENTS.md`
- 小说产品与领域：`docs/NOVEL_CREATION_SPEC.md`、`CONTEXT.md`
- 小说所有权 ADR：`docs/adr/0007-novel-creation-owns-project-state.md`
- 小说实现基线：`docs/NOVEL_CREATION_IMPLEMENTATION_PLAN.md`
- 共创 / 代笔计划：`docs/NOVEL_COCREATION_GHOSTWRITE_PLAN.md`
- 模型自主性护栏拆除计划（Draft，待裁决）：`docs/MODEL_AUTONOMY_GUARDRAILS_PLAN.md`
- iOS 主题系统完善计划（Active；P0–P3 已落地并 review 收口：MiniApp host CSS 注入消 FOUC、theme 热更新不重建 WebView、跨端字段差写入生成 prompt；Widget 静态回退已文档化；Appearance `miniPreview` 在深色系统下固定 light 纹理墨色。下一刀 **P4 需产品闸门**）：`docs/IOS_THEME_SYSTEM_ADVANCEMENT_PLAN.md`；设计契约：`docs/IOS_THEME_PACK_DESIGN_SPEC.md`
- 首页「新对话」胶囊 trailing：16→28（相对会话卡 16 内缩 12pt，消相切）；原型 `docs/HOME_NEW_CHAT_FAB_PROTOTYPE.html`。视觉层级：新对话 accent 混色玻璃 / Continue 浅强调色黑字；已装机试看。
- 点阵 · Pi：`canvasScope` appWide→shell；chat/工作页只留奶油纸色，方格仅首页/外观。冷启动迁移 legacy `pi+lineGrid+appWide`。
- 核心记忆页用户面精修：去掉「召回解释」与「本次候选/#id」控制台语义；搜索并入记忆库工具条；四范围标签等分铺开。库内 `recallExplanation` 仍保留给测试。
- Live Activity 视觉：`docs/ACTIVITY_ISLAND_REDESIGN.md`

## Update Contract

仅在分支/工作区、当前产品事实、最近验证、优先级、已知阻塞或权威入口变化时原地更新。删除已失效内容，保持可在几分钟内读完；不要恢复按日期无限追加的日志结构。

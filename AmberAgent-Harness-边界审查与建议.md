# AmberAgent Agent-Harness 边界审查与建议

> 审查日期：2026-06-02
> 审查对象：`main/` 分支的 agent 运行时框架（harness），而非业务功能本身
> 审查范围：工具注册与发现、权限与审批、主循环与编排、子 Agent 与 ModelCouncil、系统提示与上下文、工具错误契约、高危能力工具
> 方法：7 维度多 agent 并行审查 → 对每条候选发现做对抗性核验 → 跨维度综合（共 42 个 agent，34 条候选，30 条通过核验，4 条被推翻）

---

## 0. 一句话结论

**这套 harness 的"自由侧"已经接近理想，不需要继续加细则；真正该投入的工作，是把护栏从"靠作者记得手写标志"改成"靠结构默认从严（fail-closed）"。**

换句话说：不是把 agent 的房间缩小，而是把那几堵漏风的墙补好——同时把几扇被无谓焊死的门重新打开。

---

## 1. 核心心智模型：自由与护栏是两根独立的轴

最容易踩的认知陷阱，是把"给自由"和"加护栏"当成一根滑杆的两端——以为放手就等于放松，收紧就等于束缚。

**它们其实是两根正交的轴。** 一个模块完全可以**既该高自由、又该强护栏**。最典型的就是高危工具：agent 应该能随手调用 terminal、屏幕自动化、短信去解决复杂任务（高自由是必须的，否则它解决不了真实问题），但每一次触发都应该过硬闸（强护栏，因为副作用不可逆）。

您想要的"完美舒适区"，本质是 **一个足够大的房间，配一圈足够厚的墙**：

| | **轻护栏** | **强护栏** |
|---|---|---|
| **高自由** | ② 放手让它跑（创造与纠错）：ReAct 主循环、工具发现、错误契约、GenerativeUI/soul 的方法部分 | ① 大房间·厚墙（强大但危险）：高危工具、子 Agent 委派、ModelCouncil |
| **低自由** | （基本为空——低自由+低护栏意味着这个模块不重要） | ③ 锁死的墙（安全基座）：风险分级、审批引擎、上下文/记忆预算、无人值守 Board/Cron |

这套审查的全部结论，都可以归约成一句话：**当前边界在象限①里"墙偏薄"，在象限②里有几处"门焊死了"，而象限③的墙本身砌得很好。**

---

## 2. 模块评分总表

评分为 1–5。"应放手"= agent 在此应有的自我编排自由度；"应设界"= 此处应有的硬边界强度。标 `—` 的是纯护栏机制，不是 agent 行动的空间，故不打自由分。

| 模块 | 应放手 | 应设界 | 当前 vs 理想 | 方向 |
|---|:--:|:--:|---|---|
| ReAct 主循环 / 编排 | **5** | 2 | 自由到位；防跑飞缺第二道闸 | 护栏该补 |
| 工具发现（tool_search / lazy） | **5** | 1 | 大体到位；技能被藏在 3 跳之后 | 微放开 |
| 错误契约 / 韧性 | **5** | 2 | 全项目最成熟；个别 `recoverable` 过严 | 微放开 |
| 高危工具（agent 使用能力） | 4 | **5** | 能用=到位；terminal/screen/icloud 闸门偏松 | 护栏该补 |
| 风险分级机制 | — | **5** | 靠猜工具名、无 fail-closed 兜底 | **该收紧（治本）** |
| 审批决策引擎 | — | **5** | 决策树清晰可追溯 | **别动** |
| 子 Agent 委派 | 4 | 4 | 护栏到位；动态工具组合锁死在 5 档 | 自由该放 |
| ModelCouncil | 4 | 4 | external_cli 审批到位；并发无上限 | 护栏该补 |
| 系统提示 / soul | 4 | 3 | 方法写太死 **且** 缺止损/问人判据 | **两头都动** |
| GenerativeUI 提示 | 4 | 2 | 安全在代码层；提示过长规定"怎么画" | 该放开 |
| 上下文 / 记忆管理 | 3 | 4 | 分级压缩 + 双预算，到位 | **别动** |
| 无人值守 Board / DeepRead | 2 | **5** | 只读白名单，到位 | **别动** |
| 无人值守 Cron | 2 | **5** | 复用交互开关、无 Cron 档 | 护栏该补 |

---

## 3. 该收紧的地方（护栏缺口）

> 这一侧的共同主题是：**边界太松会造成不可逆的真实危害，或让 agent 跑飞烧配额。** 这恰恰是"舒适区不够稳"的体现——agent 一旦越过这些没补好的边界，失败或损失就是真实的。

### 3.A 根因：风险分级靠"猜名字"，且没有 fail-closed 兜底【最高优先级·治本】

**现象**：决定一个工具"能否无人值守"的唯一硬闸是 `ToolRisk.High`。但风险等级不是每个工具自己声明的，而是在 `ToolRegistry.kt` 里靠工具名前缀/关键字集中匹配（`risk()`、`mutatesState()`、`category()`），**未命中就 else → Normal、mutates=false**。一个工具安不安全，完全取决于作者是否记得在 `Tool` 构造里手写 `allowsAutoApproval=false`。

**证据**：`ToolRegistry.kt:99-115,390-414,416-442`；`PermissionDecisionResolver.kt:204-205`

**为什么要改**：这不是某个单点 bug，而是边界的**承重结构本身偏脆**——危险性判定与工具实现分离在另一个模块、靠名字猜，且缺少"未知/未分类/有外部副作用的工具按更严处理"的兜底。它的直接后果是下面 3.B 的一连串泄漏（terminal、screen、icloud、mcp 全部因此掉档），而 `icloud_write` 已经是"作者忘了手写标志"的真实漏网实例。靠继续打补丁逐个补名单，永远在追着新工具跑。

**建议（分三步，从应急到治本）**：
1. **应急兜底**：在 `invocationPolicy()` / `toMetadata()` 里，对"`mutates=true` 或 `category ∈ {cloud, system, external_file, terminal, screen, office}` 且未被 `risk()` 显式命中"的工具**强制 `autoApprovable=false`**（默认从严）。同时把 `cloud` 补进 `requiresSubAgentApproval` 的危险类别集合（当前漏了，只靠 mutates 兜）。
2. **治本**：让 `Tool` 定义直接带一个**就近声明**的 `risk` 字段，`ToolRegistry` 只做校验——名字启发式与声明冲突时直接报错。把"集中猜测"改成"就近声明 + 校验"。
3. **防回归**：补一个静态 lint 测试，扫描名字含 `create/append/post/comment/publish/send/update/delete/write/edit/move` 的工具工厂，断言它们都设了 `needsApproval=true + allowsAutoApproval=false`。把约定变成 CI 强制。

### 3.B 由此泄漏出的具体高危路径

#### 3.B.1 任意 shell / 真机壳只评 Sensitive，单开"全局自动批准"即静默执行【高】

**现象**：`risk()` 第 440 行 `terminal_*` → Sensitive（非 High），`terminal_execute`/`job_start` 等只设 `needsApproval=true`、`allowsAutoApproval` 默认 true。于是在 `PermissionDecisionResolver.kt:184`（`autoApproveTools && policy.autoApprovable → ALLOW`）命中，仅打开一个文案为"普通工具"的开关，就能静默跑 `rm -rf`、`curl` 外发、装包、写 SAF 工作区；`terminal_job_start` 还能选 `runtime=android_shell` 直达真机壳。

**证据**：`ToolRegistry.kt:440`；`TerminalTools.kt:48,70,77,151,205`；`TerminalRuntime.kt:433-435`；`PermissionDecisionResolver.kt:184`

**为什么要改**：它的杀伤半径**远超**已经被评为 High 的 `external_file_delete`，定级却更松。而且单测 `GenerationHandlerAutoApprovalTest.bypassesApprovalForRegularToolsWhenAutoApproveIsEnabled` 已经把这个行为固化下来了——说明这是"被当作正常"的，不是没人注意到。

**建议**：给写/执行类 terminal 工具（`execute`/`job_start`/`job_stop`/`session_start`/`session_exec`/`session_stop`/`install_packages`/`workspace_flush`）补 `allowsAutoApproval=false`，与 `sms_send`/`external_file` 对齐；只读类（`job_read`/`job_wait`/`session_read`）保持可自动。用精确名单或 `when` 分支，**不要**用 `terminal_` 前缀一刀切。可选增强：对 `runtime==android_shell` 入参在 `invocationPolicy` 里动态升 High（类比 `http_request` 按 method 区分），让真机壳那一次强制走高危门。

#### 3.B.2 屏幕自动化写操作只评 Sensitive，可在任意 App 静默连点【高】

**现象**：`screen_*` → Sensitive（`ToolRegistry.kt:439`），动作类工具（`click`/`long_click`/`swipe`/`input_text`/`open_app`/`tap_text`/`scroll_until`）只设 `needsApproval=true`、`allowsAutoApproval` 默认 true。主 Agent 在 `autoApproveTools=true` 下命中 resolve:184 静默连点——可以在银行/支付/聊天 App 里点"确认"、填表、发消息。

**证据**：`ToolRegistry.kt:439`；`ScreenAutomationTools.kt:48,67,99,140,184,208,281,342`；`PermissionDecisionResolver.kt:181,184,204-205`

**为什么要改**：危害与已被列 High 的 `sms_send` 同级甚至更高。而且团队**已经**在 SubAgent 路径显式拦了它（`requiresSubAgentApproval` + 测试 `subAgentSensitiveToolStillAsksEvenWithHighRiskAutoApproval`），唯独主 Agent 没有对称断言——这是**口径不一致**，不是设计意图。

**建议**：给动作类 `screen_*` 补 `allowsAutoApproval=false`（保持 Sensitive 即可，复用 `wm_eval` 范式），只读类（`read_ui`/`find_text`/`screenshot`/`wait_for_text`）保持可自动。首选 `allowsAutoApproval=false` 而非升 High——这样普通开关下命中 resolve:187 ASK，且不会被高危开关经 resolve:181 旁路。补一条主 Agent 在 `autoApproveTools=true` 下对 `screen_click`/`screen_input_text` 返回 ASK 的回归测试，锁住与 SubAgent 一致的口径。

#### 3.B.3 `icloud_write`（写覆盖真实云盘）被评为 Normal【高】

**现象**：`icloud_write` 只设 `needsApproval=true`、未设 `allowsAutoApproval=false`；`category='cloud'`，`risk()` 无分支 → Normal，于是 `autoApprovable=true`，在 `autoApproveTools=true` 下经 resolve:184 静默放行。`overwrite=true` 时先把旧文件移到 iCloud 回收站（externally-visible、半不可逆）。

**证据**：`ICloudDriveTools.kt:149-178,162`；`ICloudDriveClient.kt:170-172`；`ToolRegistry.kt:441`；`PermissionDecisionResolver.kt:184`

**为什么要改**：对比 `external_file_write`（High + `allowsAutoApproval=false`）、`calendar_create`/`contacts_write`/`sms_send`（均 `allowsAutoApproval=false`），`icloud_write` 是团队对"写到设备外部"统一处理里**唯一漏掉的一个**。这就是 3.A 根因的活体证据。

**建议**：补 `allowsAutoApproval=false`（与 `external_file_write` 对齐，最小且一致）。只改这一处工具定义；Board/DeepRead 路径因白名单未暴露此工具、SubAgent 路径已被 mutates 门拦截，都无需动。

#### 3.B.4 `mcp_call_tool`（黑盒外部副作用）评为 Sensitive【中】

**现象**：`invocationPolicy` 的 `mcp_call_tool` 分支设 `risk=Sensitive`、`autoApprovable=true`，全局开关即可静默放行。但 MCP 工具背后可以是发邮件、写文档、调外部 API（Gmail/Calendar/Drive/feishu 等），行为对 harness 完全不透明。

**证据**：`ToolRegistry.kt:213-219`；`McpManagementTools.kt:88`；`McpConfig.kt:21`；`PermissionDecisionResolver.kt:184`

**为什么要改**：这比 `wm_signed_fetch` 按 method、`http_request` 按 GET/POST 区分的细粒度做法更宽更松——同一套 harness 内部标准不一致。

**建议**：**不要**整体升 High（会误伤只读 MCP、制造审批疲劳）。优先：在 `mcp_call_tool` 分支读 MCP 工具的 `annotations`（`readOnlyHint`/`destructiveHint`）——只读降 Normal、写/破坏性升 High、无法判定保守落 Sensitive，与 `http_request` 按 method 分级的惯例一致。⚠️ **前置核实**：先确认接入的 MCP server 是否真的在 schema 里返回这些 annotation；若多数不返回，退化为"维持 Sensitive，但单开全局开关时仍 ASK，按 server+tool 维度记一次 run_trust 后续放行"。

#### 3.B.5 没有任何不可绕过层，两开关全开后无任何动作不可无人值守【高】

**现象**：`hardBlocked` 全仓从未被置 true（默认 false），`resolve:116` 的 DENY 分支是**死代码**。`mandatoryApproval` 工具（如 `wm_eval` 任意 JS）在两开关全开时经 resolve:124-132 直接 ALLOW；`sms_send`/`call_phone` 经 resolve:181 High 分支 ALLOW。harness 里**不存在**"无论任何设置都强制 ASK"的不可绕过层。

**证据**：`PermissionDecisionResolver.kt:116,124-132,181`；`ToolRegistry.kt:56`；`SmsAccessTools.kt:74`；`PhoneAccessTools.kt:62-66`

**为什么要改**：登录态 WebView 跑任意 JS、群发短信、拨打电话、删外部文件——这些是触达真实世界的不可逆副作用（外发/扣费/隐私），却都可以完全无人参与。一个"完美舒适区"应该有一条**任何配置都越不过去的底线**，专门留给这类不可逆动作。

**建议**：给 `ToolInvocationPolicy` 增加 `alwaysAsk` 标志，在 resolve 的 `hardBlocked` 检查之后、`mandatoryApproval`(124) 与 high-risk(181) 两个旁路分支**之前**统一判定：对极少数真实世界不可逆外部副作用工具（`sms_send`、`call_phone` 仅 `direct_call=true` 时、`contacts_write`）置 true，即使两开关全开仍 ASK。注意 `sms_send`/`call_phone` 走的是 181 而非 124，所以**不能只收紧 mandatoryApproval 旁路**。保留内部/可逆动作（装包、跑 JS）的无人值守能力。顺手删除或接入死代码 `hardBlocked`，避免误导后续维护者以为存在硬阻断层。

#### 3.B.6 "全局自动批准"开关文案与实际权限范围错位【中】

**现象**：`setting_page_agent_auto_approve_desc = "Auto-approve normal tool calls."`，开启后经 resolve:184 静默放行所有 `autoApprovable` 工具，含 Sensitive 的 `terminal_execute`、screen 动作、`icloud_write`。高风险开关有二次确认弹窗且文案明列"SMS, calls, contacts"，普通开关却**既无弹窗、文案也没提示**它覆盖任意 shell/屏幕控制/云盘写入。

**证据**：`strings.xml:699,1318`；`SettingAgentPermissionsPage.kt:117-130,140-141`；`PreferencesStore.kt:164`

**为什么要改**：用户的认知和实际授予的权限范围错位，会在不知情下打开一个比预期宽得多的自由空间。

**建议**：若已落地 3.B.1–3.B.3（terminal/screen/icloud 补 `allowsAutoApproval=false`），则"普通自动批准"真的只覆盖 Normal 工具，文案自然名副其实——这是首选。否则至少修文案，明确说明它会自动批准包括终端执行、屏幕自动化、云盘写入在内的工具。

#### 3.B.7 屏幕 run-trust 粒度过粗【中】

**现象**：`handleToolApproval`（`ChatService.kt:1142-1143`）只要被批准的工具名在 `screenSessionTrustTools()` 中，就把**整个集合**写入 `trustedRunToolNames`。于是批准 `screen_back`/`screen_home` 这类温和动作、或批准 `screen_screenshot`，就把 `screen_click`/`screen_input_text`/`screen_swipe` 一并静默授权到本轮结束。

**证据**：`ChatService.kt:1142-1143,1486-1502`；`PermissionDecisionResolver.kt:178`；`ToolRegistry.kt:439`

**为什么要改**：用户批准的是"看一眼截图"或"返回上一页"，实际却放行了"向任意输入框打字"和"点任意位置"——授权范围被悄悄放大。

**建议**：把整批赋值改成逐工具累加——只把**被实际批准的那一个**工具名并入 `trustedRunToolNames`。这样仍然消除同一工具的重复弹窗，但批准 `screen_back` 不会顺带放行 `screen_input_text`。补一个 resolver 测试断言这一点。

#### 3.B.8 webmount 适配器写类工具风险分级未兜底（面向未来的演进缺口）【中】

**现象**：`risk()` 没覆盖 `feishu_docs_*`/`github_*`/`reddit_*` 等适配器，全部 else → Normal；`mutatesState()` 只按 `_write`/`_edit` 等子串匹配，`feishu_docs_create`/`append_block` 这类"创建/追加"命名不命中 → mutates=false。当前 feishu 写工具都手动设了标志所以未爆雷，但一旦新增写类工具忘了标志，会被静默评为 Normal，甚至因 resolve:172-173 的"read-only 直接 ALLOW"连开关都不需要就放行。

**证据**：`ToolRegistry.kt:390-414,416-442`；`FeishuDocsTools.kt:447-448,480-481,535-536`；`PermissionDecisionResolver.kt:172-173`

**为什么要改**：又是 3.A 根因的一个面向未来的暴露面。它今天没出事，纯靠作者的纪律。

**建议**：在 `mutatesState()` 增加写类命名子串兜底（`_create`/`_append`/`_comment`/`_post`/`_publish`/`_send` 纳入 mutates 判定）。不要给 `github_`/`reddit_` 整前缀加 Sensitive（会误伤现存只读工具）。最稳的做法仍是 3.A 步骤 3 的静态 lint 测试，扫 `adapters/` 下的写类工具工厂。

### 3.C agent 会"跑飞"：主循环缺第二道闸

#### 3.C.1 主聊天循环只有步数一道闸【高】

**现象**：主循环（`GenerationHandler.kt:161`，`for stepIndex in 0 until maxSteps`）**只有步数上限一道闸**——没有挂钟超时、没有 token/成本累计预算、没有死循环检测。而子 agent（`withTimeout` + `outputBudgetChars`）、看板（`withTimeout`）、终端（超时 + 并发上限）、ModelCouncil 各自都有时间/预算闸。

**证据**：`GenerationHandler.kt:161,349,367-373`；`SubAgentManager.kt:168`；`BoardTaskRunner.kt:264`

**为什么要改**：最核心、最常跑、用户直面的聊天主循环，反而是**唯一没有第二道闸的执行路径**。两类真实跑飞：(a) 少数几步但每步极慢/极贵的模型在前台长时间烧付费配额；(b) 模型对持续失败的工具反复发同样参数，直到 256 步（上限可达 512）耗尽全在原地打转。步数闸约束往返次数，挡不住单步耗时，而且要 256 步才兜底，太晚。

**建议**：做两道**软兜底**而非收紧编排自由——
1. 在 `generateText` 的 `coroutineScope` 外层加可配置挂钟软上限（默认偏大，如 10–15 分钟，沿用 `SubAgentRuntimeSetting.timeoutMs` 的 `withTimeout` 模式），超时**优雅收尾** commit 一条"已达运行时长上限"，而非抛异常。
2. token/成本预算可作为 P2。

#### 3.C.2 无死循环/无进展检测【中】

**现象**：`executeBatch` 执行后结果写回，循环直接进下一步，没有"本步与前几步是否重复同样工具名+参数"或"连续 N 步无新有效结果"的检测；dispatcher 也不记录调用指纹。

**证据**：`GenerationHandler.kt:349,367-373`；`AgentToolDispatcher.kt:63-117`

**为什么要改**：这是 3.C.1 里"原地打转"那一类跑飞的精确成因。步数闸要到 256 才兜底，太晚。

**建议**：在循环里维护一个轻量"工具调用指纹"窗口（`toolName + 规范化 input 的 hash + 失败标记`），分级介入：连续 3 次同名同参且**失败** → 注入文本提示"你在重复失败调用 X，请改变策略或直接给结论"；升到 5 次才把该工具临时隐藏**一轮**（只隐藏这一个、只隐藏一轮）。只对"重复且失败"计数，避免误伤合法的分页查询。全部是循环内本地状态，不触碰 hook/policy 契约。

#### 3.C.3 ModelCouncil 无并发 run 上限【高】

**现象**：`ModelCouncilManager.start()` 直接 `runs[runId]=runtimeRun` 并 launch，没有 admission/并发计数检查（对比 `SubAgentManager.start()` 在 `admissionLock` 内用 `maxConcurrentRuns` 拒绝超额）。单 run 内部可放大 `maxSeats=8 × maxRounds=5 ≈ 40` 次模型生成；模型还可以在多个 step 背靠背连发多个 `model_council_start`。

**证据**：`ModelCouncilManager.kt:125-126,141,245-272`；`ModelCouncilModels.kt:23-35`；`SubAgentManager.kt:132-153`

**为什么要改**：council 是最贵的多模型变体（单 run 约 40 次付费调用，可能含终端进程），却是**唯一连并发闸都没有**的——比它便宜的 SubAgent 都设了闸。这是不对称缺口，叠加"鼓励背靠背并行启动"的提示词，单轮就可能放大出失控成本。

**建议**：在 `ModelCouncilRuntimeSetting` 新增 `maxConcurrentRuns`（默认 2，与 SubAgent 对齐）；在 `start()` 的 runs 写入前仿 `SubAgentManager` 加 `synchronized(admissionLock)` 块（council 现用 `ConcurrentHashMap`，需补 admissionLock 保证"检查 + 写入"原子），超限返回 `too_many_councils`；在 `runtimeSummary()` 输出 `max_concurrent_runs` 让编排者看到边界。

#### 3.C.4 Cron 无人值守复用交互开关【低】

**现象**：`ToolInvocationContext` 枚举定义了 `Normal/SubAgent/Cron/ModelCouncil`，但 resolve **只对 SubAgent 特判**，Cron 无任何分支且从未作为 `invocationContext` 传入。`AgentCronWorker` 走 `chatService.sendMessage` 普通会话路径（Normal），复用会话的 auto-approve 开关。

**证据**：`ToolInvocationContext.kt:3-8`；`PermissionDecisionResolver.kt:46,143`；`AgentCronWorker.kt:56-60`

**为什么要改**：Cron 是无人值守场景，理想边界是"比交互更克制，但对预声明的安全工具更自主"。当前它被钉死在和交互同一套开关：要么为让 cron 干活长期开全局开关（交互态也一起放松），要么开关关着导致 cron 命中 ASK 后挂起等一个永不来的人。属表达力缺口。

**建议**：保留枚举（`invocation_context` 已序列化进 permission trace，删除有持久化副作用）。若接入 Cron 档，**必须**定位为"在 Normal 安全基线之上更克制"——Cron 分支须位于 `hardBlocked`/`mandatoryApproval`/`alwaysAsk` 三道门之后，只在 `autoApprovable && risk==Normal && !mutates` 子集上自动放行，命中其它工具按 `BoardTaskRunner.markWaitingUser` 范式记 blocked 并通知。落地前先和用户确认期望的自主度。

### 3.D 发现与纠错的小缺口

#### 3.D.1 lazy 模式下技能完全不可发现【高·性价比最高】

**现象**：`ToolExposureState.isResidentTool` 对 `category=='skill'` 一律返回 false，lazy 模式（工具数 >40，满配助手常态）下 `skills_list`/`use_skill` 全部非常驻。而"已启用技能清单"（`<available_skills>` 目录）的唯一注入入口正是 `skills_list` 的 `systemPrompt`。叠加之后 agent 开局既看不到 `use_skill`，也收不到任何"你装了哪些技能"的提示，必须盲猜 `tool_search("skill")` → 暴露 → 下一步再调用（3 跳）才知道技能存在。

**证据**：`ToolSearch.kt:313-321,329-356`；`GenerationHandler.kt:159-186,739-741`；`SkillsTools.kt:39-65,90`

**为什么要改**：技能是"让模型自己想办法解决复杂任务"的**核心放权手段**。装了技能却长期发现不了，等于这道放权门被焊死了——典型的"边界把核心能力藏得太深，导致跑不通"。

**建议**：在 `isResidentTool` 只增加一条 `name == "skills_list"` 分支（与现有 `RESIDENT_CONTEXT_TOOLS` 精选小子集做法一致），使其 `<available_skills>` 目录每个 turn 开局即注入；`use_skill` 仍懒暴露（模型读到目录后自然会去 tool_search）。`skills_list` 的目录块在零技能时只渲染一行状态，对零技能助手几乎零成本。无需新抽象。

#### 3.D.2 调用"目录里有但本步未暴露"的工具时报误导性 not found【中】

**现象**：loop 用 `toolsInternal`（本步已暴露工具）作为 `toolDefinitions`，dispatcher 里 `toolDef ?: error("Tool X not found")`，归一化为裸 "not found"。当模型从 `tools_list` 看到名字直接调用、但还没 `tool_search` 暴露它时，会误以为工具不存在而放弃换路。

**证据**：`AgentToolDispatcher.kt:205,208`；`GenerationHandler.kt:290,351`；`PermissionDecisionResolver.kt:104-111`（resolver 这条路径**已有**正确的 tool_search 恢复文案，但 dispatcher 路径没享受到——两处不一致）

**为什么要改**：这削弱了 agent 的自我纠错——它本来只差一步 `tool_search`，却被一个错误信号劝退。

**建议**：区分三态——调用名存在于本 run 完整 registry 但本步未暴露 → 返回结构化失败 `reason="tool_hidden"` + 恢复指引（"该工具存在但未暴露，请先 `tool_search(query=<name>)` 再于下一步执行"）且 `recoverable=true`；registry 也没有 → 保留裸 not found。优先用已有的 `ToolInvocationHook.before` seam 拦截，与 resolver 现有文案对齐。

#### 3.D.3 工具失败 payload 缺机器可读的 failure category【中】

**现象**：`toAgentToolFailurePayload()` 只产 `status`/`message`（截断到 360 字的自由文本）/`recoverable`（布尔）。而 core/ai 侧**已有**成熟的 `GenerationFailureClassifier` 能分类 NETWORK/TIMEOUT/RATE_LIMIT/AUTH/BAD_REQUEST/QUOTA/CONTEXT，工具失败归一化却完全没用上。

**证据**：`ToolFailure.kt:9-13,29-33`；`GenerationRetry.kt:22-102`；`AgentToolDispatcher.kt:304,309-313`

**为什么要改**：模型要决定"换工具 / 改参 / 等等再试 / 放弃"，目前只能从一行文本里猜——一个超时（应等）和一个鉴权失败（应放弃）在模型眼里都只是一段 message。给它机器可读的分类，直接提升自我纠错的准确度。

**建议**：在 `toAgentToolFailurePayload()` 调 `GenerationFailureClassifier.classify(this)`，`put("category", classification.category.name)`——零风险高收益。⚠️ **不要**用 `classification.retryable` 覆盖 `recoverable`：retryable 是"该不该自动重试"，recoverable 是"模型该不该当致命错误"，二者不等价（`BAD_REQUEST` retryable=false 但 recoverable=true，改参即恢复）。如需暴露 retryable 另加独立字段 `auto_retryable`。

---

## 4. 该放开的地方（过度约束）

> 这一侧的共同主题是：**收益低，却直接顶撞"让 agent 自己发现工具、自己编排"的诉求。** 它们不构成安全风险——核验确认所有真正的安全约束都在别处由代码硬执行——纯粹是无谓地缩小了房间。

### 4.1 动态子 Agent 的工具集被锁死在 5 个固定 profile【中】

**现象**：`resolveDynamicDefinition` 里 `requestedTools = profileTools.intersect(explicitTools)`，即 `tool_allowlist` 只能是所选**单一** profile 的子集，而 profile 是 5 个硬编码集合（NONE/READ_ONLY/WORKSPACE_READ/WEB_READ/HISTORY_READ）。动态角色无法把"workspace 只读 + web 只读"组合，也加不进任何不在某 profile 列表里的无害只读工具。

**证据**：`SubAgentValidator.kt:131,135-139,237,244`；`SubAgentTools.kt:71`；`SubAgentModels.kt:57`

**为什么放（但要窄化放）**：这与"让 agent 自己编排"直接冲突。⚠️ 核验确认：`intersect` 是动态子 agent **唯一**的工具收窄手段（`parentToolNames` 含写类工具，`validateToolAllowlist` 只查名字不查 risk），所以这一层是**承重的**——放开必须保留"不能扩权到危险工具"。

**建议**：保留 profile 边界但放宽只读组合：允许 `tool_profile` 传**数组**取多个只读 profile 的并集，或允许 `read_only` 跨域组合；放开时按工具 risk/mutates 元数据**二次过滤**，只对 write/terminal/send/install/delete 等继续硬拒，把只读工具的组合自由交还模型。`smart_dynamic` 模式已另有 `defaultDynamicReadOnlyTools` 只读硬收窄兜底，风险可控。

### 4.2 动态角色靠关键词黑白名单强校验，易误杀【低】

**现象**：`validateNarrowDynamicRole` 硬性要求：name 不得含 `general`/`helper`/`通用`/`全能`（命中即抛错）；description 必须含 `when`/`invoke`/`用于`/`何时`/`调用` 之一；system_prompt 必须**同时**含 boundary cue（`boundary`/`do not`/`边界`/`不要`/`禁止`）和 report cue（`report`/`output`/`返回`/`报告`）。

**证据**：`SubAgentValidator.kt:186,193-208,288,293-312`；`SubAgentRunner.kt:224-238`

**为什么放**：模型写出边界清晰、但用了 `scope`/`limits`/`deliver`/`produce` 这类同义词的英文 prompt，会被整单拒绝，迫使它反复猜关键词。核验确认这组校验**不承重**——真正的安全边界在 tool allowlist（默认只读、交集、禁 `subagent_*`），且 boundaries/output_format 已由 `validateTask` 硬性要求、`buildTaskPrompt` 运行时逐字注入完整 report 协议，cue 命中与否对实际运行毫无影响。

**建议**：① 名字命中黑名单改为用 `SmartSubAgentNames.pick` 自动换具体名（与 SMART_DYNAMIC 现有行为对齐），去掉硬抛错；② 删除 system_prompt 的 `hasBoundaryCue`/`hasReportCue` 两条 require；③ description 的 `hasInvocationCue` 可保留 `length>=24` 的廉价下限即可。保留所有 length 下限与工具/预算校验。

### 4.3 校验失败被硬编码为 `recoverable:false`【中】

**现象**：`ToolArgumentValidationHook.validationFailure` 对"工具未找到/参数未解析/参数非 JSON 对象"三类统一返回 `recoverable:false`，而这恰是模型最易自我纠正的失败（换名/补全 JSON 即可）。

**证据**：`ToolInvocationHooks.kt:88-100`；`ToolFailure.kt:29-33`

**为什么放**：核验确认 `recoverable` 字段在全仓**没有任何 agent-loop 代码读取它做硬决策**，仅作为工具结果 JSON 透传给 LLM——所以它不是承重约束，只是写给模型看的纠错信号。用最强的"放弃"信号去对待最轻的失败，与"让 agent 自己想办法"相悖。

**建议**：把这三类校验失败的 `recoverable` 改为 true，并在 message 里给可执行的纠正指引（见 3.D.2）。DENY 分支（权限被拒）的 `recoverable:false` 是合理的，保留。

### 4.4 soul 提示把工具特定步骤写死【中】

**现象**：`DEFAULT_AGENT_SOUL_MARKDOWN` 写死多条工具特定流程（"iCloud → 先 `icloud_status`"、"小米办公 Pro → 先 `officepro_status`/`dashboard`"单行约 8 个工具名、"`subagent_start` 前先 `subagent_list` once"、"`session_list`/search first"），`buildSystemPromptParts` 整段**无条件**拼入、无 tool gating。

**证据**：`PreferencesStore.kt:287-296`；`GenerationHandler.kt:721-724`；`SubAgentTools.kt:55-57,60-119`（此处注释明说"注入 roster 让编排者无需先 call subagent_list"——与 soul 直接矛盾）

**为什么放**：当这些工具族当前 run 未启用时仍占每轮 token，且诱导模型去想一个调不到的工具。核验确认这些约束不承重——真正的安全闸在代码（`icloud_write`/officepro draft 类 `needsApproval=true`、draft 物理上无法外发），删 soul 文案不移除任何安全闸。

**建议**：分段处理——SubAgent 段直接删（`SubAgentTools.kt:60-119` 已 tool-gated 注入完整 roster + routing）；Session 段瘦身为通用原则、不写死工具名；iCloud/OfficePro 段**不要纯删**（这两族目前无 systemPrompt），迁移到各自工具新增的 `systemPrompt`（仿 SubAgentTools 写法，仅工具挂载时注入），迁移时精简掉 8 个工具名细节。soul 最终只保留与工具解耦的通用原则。

### 4.5 GenerativeUI 系统提示块过长且高度规定"怎么画"【低】

**现象**：`buildGenerativeUiPrompt` 在 enabled 时每轮注入约 40+ 行高度规定性指令（viewBox 数值、`x+width<=656`、`padding>=24px`、字号、PPT 骨架），外加 `buildGenerativeUiModelGuidance` 逐模型（deepseek/kimi/claude/gemini/qwen/gpt）追加绘图细则。

**证据**：`GenerationPrompts.kt:30-140`；`GenerationHandler.kt:729,731-735`；`GenerativeWidgetSanitizer.kt:22-127`（安全约束在此代码层硬拦截）

**为什么放**：整块无条件进 dynamicPrompt，很多对话根本不涉及画图；走 PROSE 路由的纯对话轮同时收到"教你怎么画"和"别画"互相拉扯。核验确认安全约束（禁 iframe/script/外链 CDN）由 `GenerativeWidgetSanitizer`/`GuizangHtmlDeckValidator` 在渲染前代码层硬拦截，瘦身提示词不引入危害——绘图方法细则才是低收益部分。

**建议**：分三层——(1) 常驻最小存根（每轮 4–6 行：show-widget 围栏名 + 一行 JSON 形状 + 几条硬上限），保证自发想画时知道契约；(2) 方法学长版仅当 planner 判定为 DIAGRAM_WIDGET/SLIDES/AMBIGUOUS_VISUAL 时注入；(3) 逐模型细则下沉到 retry 修复路径（`withGenerativeUiVisibleFallbackPrompt` 已存在）首轮出问题再补发。务必保留常驻存根这一层。

### 4.6 受限 profile 的只读名单是手工硬编码（要窄化处理）【低】

**现象**：`WEB_READ`/`WORKSPACE_READ` 是逐个工具名写死的大列表，新增只读工具需手工补名单，漏补则该工具在对应 profile 下静默消失。

**证据**：`ToolProfileFilter.kt:54-159`；`ToolRegistry.kt:100,171-179`

**为什么这条要特别小心**：⚠️ 核验**推翻**了"改成 `category + !mutates` 派生"的直觉方案——`metadata.mutates` 是无入参算的不完整名字启发式，`http_request`/`wm_eval`/`wm_signed_fetch` 等危险工具静态 `mutates=false`，按 category 派生会把它们静默并入"只读"profile，**把安全的 fail-closed 缺口换成不安全的 fail-open 越权**。

**建议**：**不要**按 `category + 静态 !mutates` 派生。保留显式白名单，加一个单元测试断言任何 read 类 profile 成员都不得 `mutates==true`/`risk!=Normal`/`mandatoryApproval==true`——既挡住漏补被忽视、又在误把危险工具加进白名单时立刻 CI 失败。

### 4.7 tool_search 中文意图召回靠手工别名表（有兜底链，严重度低）【低】

**现象**：`scoreTool` 的语义召回主要来自 `searchAliases` 手工表，只覆盖约 8 个固定前缀的中文别名。`officepro_`/`system`/`webview_`/`deep_read_`/`cron_`/`skill` 等无别名族，纯中文意图查询在这些族上几乎得 0 分。

**证据**：`ToolSearch.kt:170-247,303,323-327`；`ToolsListTool.kt:62-69,75`

**为什么严重度低**：存在确定可达的兜底链——`tools_list` 常驻、列全量目录且支持 category 过滤，最坏只是多一跳，不会搜不到而失败。

**建议**：轻量补强——给 `searchAliases` 加一个"按 category 返回通用中文词"的尾分支，覆盖零别名族（skill→[技能]、office→[办公,日报]、system→[手机,短信,日历,联系人]、webview→[网页,浏览器]、cron→[定时,计划任务]、deep_read→[深读,精读]）。改动局限在 `ToolSearch.kt:212-247` 一处。

---

## 5. 别动的承重墙（已经调得很好）

> 列出这些不是凑数——"完美舒适区"的前提是**知道哪些墙是承重的、绝不能拆**。这些就是把您的边界撑稳的结构。

1. **委派深度钉死 depth-1**：子 agent 不能再生子 agent 或 council，且工具集构建顺序保证 council 工具根本不在子 agent 的 parentTools 里。
2. **子 agent 白名单"只能收窄不能扩权"**（profile 交集 + 强制剔除 `subagent_*`），smart_dynamic 把自定义角色硬收窄到只读。
3. **无人值守 Board/DeepRead 只挂只读白名单**（时间/看板记录/飞书文档读/网页搜索），把跑飞和不可逆副作用入口直接关死。
4. **错误契约核心成熟**：所有异常（除 `CancellationException`）统一捕获、归一化成结构化 JSON、去栈帧/去多行/360 字截断后喂回模型——agent 几乎永不被单个失败卡死。这是全项目舒适区设计最成熟的部分。
5. **参数校验偏宽松**：`required` 只作 schema 提示、不在 dispatcher 硬拒，缺参运行时抛 `recoverable` error 让模型补参重试。
6. **工具级自动重试克制**：只对只读/非变更/Normal/自动批准的工具重试、复用 `GenerationFailureClassifier`。
7. **ReAct 主循环本身**：不强制固定步骤顺序、收敛节奏交给模型，`AgentLoopBudgetPrompt` 的"软提示 + 临近上限才硬隐藏工具"是恰到好处的引导而非束缚。
8. **审批决策引擎**：`PermissionDecisionTrace` 全程记录 source/reason/policy、按"工具+输入"动态评估风险、高风险开关带二次确认、子 agent 命中审批优雅返回 `APPROVAL_REQUIRED` 而非死锁。
9. **external_cli council 席位的强制审批已正确实现**：`invocationPolicy` 对含 external_cli 席位的 `model_council_start` 强制 `mandatoryApproval=true`，`containsExternalCliSeat` 直接扫描 `planned_seats` 不依赖模型诚实。
10. **上下文/记忆管理**：`ConversationContextEngine` 分级压缩 + force/fit 兜底、`MemoryRecallStore` 双预算 + 相关性打分 + CORE/pinned 优先、压缩后 `AgentCapabilitySnapshotBuilder` 重喂能力快照。
11. **historian 子 agent 对 session_read 的预批准**：严格门控在现铸的 `SessionAccessGrant` 上（scoped sessionIds + 字符预算 + 30 分钟 TTL + 逐次预算消耗 + resolver 二次校验），是承重设计——historian 无回问用户通道，移除即破坏它。
12. **officepro 草稿/只读类工具评为 High**：因为它们全部能把 `include_current_screen` 透传给 `captureContext` 读实时屏幕（含 PII），按"工具能力最大边界"评 High 正确，降级会引入静默连续读隐私屏幕的真实路径。

---

## 6. 优先级与实施路线

| 排名 | 动作 | 对应章节 | 收益 | 风险/工作量 |
|:--:|---|:--:|---|---|
| 1 | terminal 写/执行类 + screen 动作类补 `allowsAutoApproval=false`（icloud_write 同步） | 3.B.1-3 | 一次堵住三条最危险、与开关预期最错位的不可逆路径 | 极小（补标志，与 sms 模式一致） |
| 2 | 加 fail-closed 兜底层 + 写类命名 lint 测试 | 3.A | 治本，消除"靠作者记得"的结构性脆弱 | 中（动 ToolRegistry 元数据派生） |
| 3 | 新增 `alwaysAsk` 不可绕过层 + 清理 hardBlocked 死代码 | 3.B.5 | 给真实世界不可逆动作一条永不静默的底线 | 小（resolver 加一道判定） |
| 4 | 主循环补挂钟软上限 + 重复失败检测 | 3.C.1-2 | 唯一没有第二道闸的执行路径 | 中（循环内本地状态，不碰契约） |
| 5 | `skills_list` 加入常驻集 | 3.D.1 | 性价比最高的放权修复 | 极小（isResidentTool 加一行） |
| 6 | ModelCouncil 补并发 run 上限 | 3.C.3 | 最贵变体却唯一没并发闸 | 小（仿 SubAgentManager） |
| 7 | 放宽动态子 agent 只读工具组合（保留 risk 过滤） | 4.1 | over_constraint 侧最直接冲突诉求的一条 | 中（validator 改造 + 二次过滤） |
| 8 | 失败 payload 注入 category + 校验失败翻 recoverable | 3.D.3 / 4.3 | 直接提升自我纠错准确度，几乎零新增逻辑 | 极小 |

**建议的串行顺序**：先做 1（应急止血）→ 2（治本，让 1 不再复发）→ 3（底线）。这三条是一个完整的"把权限边界从约定改成结构"的闭环，彼此独立可分别验证，且都不削弱 agent 的能力（agent 仍能用这些工具，只是默认要过闸）。4–8 可并行推进。

---

## 7. 对抗性核验：被推翻的 4 条（说明严谨性）

这套审查对每条候选发现都做了反方质证，以下 4 条被证伪、**未**进入结论——列出来是为了说明本报告不是把所有"看起来像问题"的东西都收进来了：

1. **"external_cli 席位没有人工审批闸门"** → 证伪。`ToolRegistry.kt:305-314` 对含 external_cli 席位的 `model_council_start` 已强制 `mandatoryApproval=true`，且靠扫描 `planned_seats` 检测、不依赖模型自报，`startJob` 之前就有硬审批门。
2. **"子 agent 继承父级开关可静默跑敏感工具"** → 证伪。resolver 有专门的 SubAgent 分支（`PermissionDecisionResolver.kt:143-170`），任何 `requiresSubAgentApproval` 工具都返回 ASK，高风险需用户**双开关**显式授权。
3. **"officepro 草稿工具锁太死、应降级"** → 证伪。这些工具全部能读实时屏幕（含 PII），评 High + `allowsAutoApproval=false` 正确，降级会引入静默连续读隐私屏幕的真实路径。
4. **"小循环 FINAL 强制清空工具集应放开"** → 证伪。这是子 agent report 收口（2 步预算逼模型产出结构化报告）的承重设计，且主聊天路径（最小 64 步）根本走不到该分支；放开反而破坏唯一需要它的场景。

---

## 8. 本次未充分覆盖、值得后续单独核实

1. **MCP server 的 annotation 可用性**：3.B.4 的首选方案依赖 `readOnlyHint`/`destructiveHint`，需先确认接入的 server（Gmail/Calendar/Drive/feishu/amap）是否真返回这些字段。
2. **上下文压缩的极端行为**：`ConversationContextEngine` 被归为 wellBalanced，但只看了摘要级证据，未深入核验"压缩反复失败后 agent 的实际恢复路径"。
3. **`useKernelPath` 休眠路径**：本次只确认 `ChatTurnAgent` 硬编码 `maxToolIterations=256`（绕过用户设置）这一处，kernel 路径整体是否还有其它绕过审批/设置的不一致未系统排查；它一旦转正为默认路径需整体重审。
4. **ai provider 流式层的 read/idle 超时**：核验时 grep 未命中流式超时配置，疑似存在"单次 provider 调用挂死"的独立缺口（可能在 OkHttp/Ktor 客户端层有 socket 超时），建议单列核实。
5. **Cron 与 Board 的组合放大**：两者都是无人值守路径，Board 白名单核验过，但 Cron 走普通会话路径，二者组合（cron 触发的会话又派 board 任务）是否存在放大未审查。

---

## 9. 关键文件索引

| 关注点 | 文件 |
|---|---|
| 权限决策树 | `app/.../feature/runtime/PermissionDecisionResolver.kt` |
| 工具风险分级/元数据 | `feature/tools/api/.../ToolRegistry.kt` |
| 工具分发/重试/hooks | `app/.../feature/runtime/AgentToolDispatcher.kt`、`ToolInvocationHooks.kt` |
| 主循环 | `app/.../core/ai/GenerationHandler.kt` |
| 系统提示构建 | `app/.../core/ai/GenerationPrompts.kt`、`feature/prompts/AgentPromptConfigRepository.kt` |
| 工具发现 | `feature/tools/api/.../ToolSearch.kt`、`ToolProfileFilter.kt` |
| 子 Agent | `feature/subagent/.../SubAgentManager.kt`、`SubAgentValidator.kt`、`SubAgentRunner.kt` |
| ModelCouncil | `feature/modelcouncil/.../ModelCouncilManager.kt` |
| 高危工具 | `feature/tools/impl/.../TerminalTools.kt`、`app/.../feature/tools/ScreenAutomationTools.kt`、`ICloudDriveTools.kt`、`FeishuOfficeTools.kt` |
| 错误契约 | `feature/runtime/api/.../ToolFailure.kt`、`core/ai/api/.../GenerationRetry.kt` |

---

*本报告基于 2026-06-02 `main/` 分支代码。所有结论均有 file:line 证据，并经过对抗性核验。如需将任一条落成可审查的代码改动 + 回归测试，建议从优先级 1–3 的"权限边界从约定改成结构"闭环开始。*

# Jev Android 接入设计——三步计划的 Android 适配

日期：2026-09-17。状态：**设计稿，未实现**。
上游输入：iOS 版执行计划 v2.0（`~/Downloads/jev-integration-execution-plan.md`，2026-09-17 与官方接口核对）。
本文只覆盖 Android 仓；按根 AGENTS.md，不把 iOS 实施当 Android 已验证行为，跨端契约需两端分别留证据。

## 0. 总判断

iOS 计划的骨架（三步走、off/shadow/active、预算/失败策略、"Jev 只评分、Amber 代码管过滤/预算/执行/权限"）**平台无关，直接沿用**。Android 侧的映射度高于预期：工具搜索/暴露、记忆召回、上下文压缩、run 账本、WebMount 快照契约都有现成符号可挂。真正的工作分三类：

1. **基建换皮**（确定性工作）：Keychain→SecretStore、URLSession/Codable→OkHttp+kotlinx.serialization、UserDefaults→DataStore Settings、设置页用 Search 服务页模板。
2. **两处结构性差异，需要裁决**：
   - 子代理**没有**模型池自动选择（`SubAgentRunner` 优先级 = role.modelId 显式 → 当前聊天模型；池轮转只在议会 `defaultCouncilModelPool`）。Phase 3a 的挂点比 iOS 窄得多。
   - 记忆链路**没有** citation allowlist、没有 memory 主动 search/query 工具、注入是纯文本（ID 仅 debug 显示）。iOS 计划里"保持现有行为"的步骤在 Android 对应的是"本来就没有"。
3. **一处身份契约改造**：无 `turnRevision` 概念，用现有 `messagesDigest`/`argsDigest` 充当输入版本。

## 1. 不变的架构原则（从上游计划继承）

- Jev（`POST https://api.typesafe.ai/v1/systemone`，Bearer，Choice/Score/Noul 三种题型）返回**有限候选的评分和选择**；过滤范围、预算、执行、权限全部留在 Amber 代码；规划/生成/摘要/复杂恢复归现有生成模型。
- 每用途独立 off/shadow/active；**shadow 也外发数据**（设置页文案必须写明，不是本地模式）；active 错误/超时/不确定一律回退原路径。
- 预算初始参数沿用上游（单判断 1,200ms deadline、单请求 32 题/64 候选/state 48KiB、单轮 6 次出站、日 1,000 次/16MiB、内存缓存 128 项 TTL 5min、400/401/403 不重试、暂时性最多重试 1 次且须有余量、429 尊重 Retry-After、连续 3 败冷却 60s）。这些是实验参数，实测后可调并升 policyVersion。
- API Key 只从应用设置写入，不进计划/聊天/日志。Jev 不进普通聊天 provider/模型列表。

## 2. 基建映射

| iOS 计划 | Android 现状 | 动作 |
|---|---|---|
| `IOSJevClient.swift`（URLSession/Codable） | OkHttp 5.3.2 + kotlinx.serialization（`ai/` 模块惯例） | 新建纯 Kotlin `core/jev` 模块：client + decision coordinator + 预算/缓存/并发；endpoint 生产固定官方 HTTPS，测试用 OkHttp interceptor 桩注入（仿 `OpenCodeSessionHeaderTest`，仓内无 MockWebServer） |
| Keychain + `IOSCredentialSideTable` | `core/settings/.../secret/SecretStore.kt`：`SecretDescriptor(scope, ownerId, fieldName)`，Keystore AES/GCM 信封；`put` 失败 `commit()` 抛错、`update` upsert、read 解密失败=null | Key 存 `SecretDescriptor("jev", "default", "apiKey")`；沿用现有替换失败语义，不改 SecretStore |
| `IOSSharedSettingsStore` 版本化设置 | 单一 `@Serializable Settings` data class（`PreferencesStore` DataStore） | 增嵌套 `JevSetting`（见 §7），默认全 off；版本化默认值走仓内既有 legacy 解码器模式 |
| `ExecutionSettingsView` | `feature/ui/pages/setting/`；最佳模板 = `SettingSearchPage` + `SettingSearchServiceEditorSheet`（外部 HTTP 服务 + per-service Key 的完整先例） | 新增 `SettingJevPage`：Key、合成数据连接测试、每用途模式、数据范围、状态与近期开销、关闭/清 Key；闭环按"可见控件→Settings 持久化→运行时消费"核对 |
| MainActor 约束 | `AppScope` 全局 + `Dispatchers.IO` 惯例；无 DispatcherProvider | 全部 IO 调度，不占主线程；shadow 任务挂 run 生命周期（见 §3） |
| DI | Koin 4.2.1，按域 Module | `core/jev` 出 `jevModule` |
| 共享 KMP bridge 验证 | 本仓 `core/` 即 Android 平台实现 | **不碰**跨仓 KMP；按 AGENTS.md，仅当 JevClient 稳定、平台无关且两端都有消费者后才提议迁往版本化 Core 制品 |

## 3. 身份、预算、并发契约的 Android 化

- **run = `AgentRunId`**（`AgentRunner` 每 run 铸造 UUID；ChatService 经 `ChatRunHooks.onRunStarted` 落 durable）。**用户轮 ≈ run**：ChatService 每次 send 起一个 run，steer 在 run 内追加——正好匹配上游"steer 使输入版本失效但不重置本轮预算"。
- **turnId = kernel `stepIndex`**；**turnRevision 不存在，不新造**：输入版本复用 `RequestSnapshot` 已有的 `messagesDigest`（sha256）与工具侧 `argsDigest`。缓存键 = 用途 + 完整输入/候选哈希 + 模型/问题/策略版本 + 数据范围 + runId。
- **并发上限**（每 run 1 在途 / 全局 3）在 `JevDecisionCoordinator` 内实现：全局 `Semaphore(3)` + per-run 互斥；排队计入 1,200ms deadline，超时取消实际 Call（OkHttp cancel 传播到socket）。
- **取消语义**：Jev 调用挂在发起它的 run 协程（kernel dispatch loop / ChatService session scope）；主开关、数据范围或 Key 变化时由 coordinator 内部检查并拒绝/取消不再允许的用途，结果返回前重验身份与配置 revision。
- 移动弱网下 1,200ms 偏紧：照上游要求在真机报告 p50/p95 后再调参，不预先放宽。

## 4. Phase 1：工具发现与记忆召回

### 4.1 工具发现

现状挂点（均已核实）：

| 上游概念 | Android 符号 |
|---|---|
| `ToolSearchIndex` | `feature/tools/api/.../ToolSearch.kt:80`（纯词法 `scoreTool`:173，硬编码中英别名，limit 1..20 默认 5） |
| 暴露集合 | `ToolExposureState`（同文件 :276）：lazy 模式（目录>40 才启用，`from`:324）、`exposeToolNames`、`observeExecutedTools`:315 解析 tool_search 输出的 `expanded_tools` 追加暴露 |
| 三条消费路径 | Android 是**单一路径**（`AgentToolDispatcher.executeBatch` + kernel 单例；前台/后台差异只在目录组成，`ChatService` foregroundRegistry :3676）——iOS 的"三路统一"改造在 Android 不需要 |
| 审批/scope/profile | `PermissionDecisionResolver` + `ExecutionPolicyGate` + `ToolProfileFilter`（MAIN_AGENT_TOOL_PROFILE） |

接入设计：

- 精确工具名命中直走原搜索，零 Jev 调用（上游同款要求）。
- 候选生成：**先过** profile/scope/启用状态/risk/category 硬过滤（复用 `ToolProfileFilter` + `ToolRegistry` 元数据），再 union 词面 Top-N（**N 取到 limit 上界附近，不是默认 5**）+ 类别补充；大目录先类别选择再类内评分，记录候选覆盖率。
- 评分经 `core/jev` 异步请求（state 携带任务文本——须 `TASK_TEXT` 数据范围允许）；active 模式下把选中名走**既有** `exposeToolNames`/`expanded_tools` 通路更新暴露；shadow 零暴露。返回后重查目录（`refreshDynamicTools` 边界）再应用。
- **价值前提要先量化**：lazy 模式仅在目录>40（典型 = 挂了 MCP server）时存在；实施第一步先量当前用户目录规模分布，若绝大多数 run 都在非 lazy 模式，工具发现用途的优先级让位记忆召回。

### 4.2 记忆召回

现状挂点（已核实）：

| 上游概念 | Android 符号 |
|---|---|
| `ChatMemoryContextBuilder` | `MemoryRecallStore`（`core/memory/recall/`）：`buildPrompt`:18（召回即 `touchMemories`:25）/ `recall`:40（不 touch——LiveTurnRuntime 已用此先例）/ `rankRecords`:65（internal 纯函数，有测试） |
| 硬筛选 | scope 开关 :45-50 + SQL 层 archived/expiresAt（`MemoryDAO.getActiveMemoriesByScopes`） |
| 注入点 | `ChatGenerationRoundEngine`:157 每轮组装 `<memory_context>` 系统段 |
| citation allowlist | **不存在**（全仓 citation 均指 web 引用）；记忆 ID 仅 debug 进文本（`MemoryPromptBuilder`:34） |
| memory 主动 search/query 工具 | **不存在**（`MemoryTools.kt` 只有 list/write/delete）——上游"保持现有行为"在此=不新增 |

接入设计：

- 顺序：SQL 硬筛选 → 候选集（词面命中 + always-eligible 置顶/核心/feedback/durable-user + 新近补充）→ Jev 重排（每条 Noul/Score，`PERSONAL_MEMORY` 范围须显式允许）→ 低置信/失败回退 `rankRecords` 原排序 → 原有 `takeBudget`（maxItems/maxPromptChars 不变）。
- **候选不能复用 `rankRecords` 的 `score>0` 过滤产物**：该过滤会丢零词面命中条目（`:80`），语义召回的价值恰恰在此，候选集须独立构建。
- **shadow 不 touch**：shadow 路径调 `recall()`（无副作用）只记录指标；active 沿用 `buildPrompt()` 现有 touch 语义。
- **选中集合一次计算**：同一 user turn 的工具循环内复用结果（键含 messagesDigest，steer 后 digest 变即失效重算），统一供 prompt 文本、touch/usage marking、指标。
- citation allowlist 列为可选后续（需先决定非 debug 模式是否常显记忆 ID）；本阶段不做。

### 4.3 Phase 1 验收要点（上游门槛的 Android 化）

- 精确工具名命中零回归、零额外 Jev 网络；相同 limit 下语义 Recall@5 不低于基线。
- 相同注入字符预算下必要记忆召回不低于基线；弱词面子集目标 +10pp（基线近满分则报告天花板）。
- fixtures：工具/记忆各 ≥40 条，放 `app/src/test`（**Android 优势：全部逻辑 JVM 可测，Phase 1 无需模拟器**），阈值调试集与冻结验收集分离；不让 Jev 自评。
- off=零调用零缓存应用；shadow 业务结果零变更（含 touch 零变更）；active 超时回退原路径。报告写 `docs/reviews/jev/phase-1-report.md`（沿上游模板）。

## 5. Phase 2：上下文筛选

现状链路（已核实）：`ChatGenerationRoundEngine`（记忆→system prompt 组装）→ `ConversationContextEngine.prepareContext`（`CompactPolicy` 规划/执行压缩，**压缩 source = MessageNode 树原文**）→ transforms → `TokenBudgetFitter.fit` → provider。长工具结果现状无内核级截断，仅压缩摘要源经 `ToolResultCompactor`（takeMiddle 8000 字符）。

- **插入点**：`prepareContext` 之后、`TokenBudgetFitter` 之前，加一个"长工具结果投影" transform：仅处理已完成、>8,000 字符（对齐 `ToolResultCompactor` 阈值）的工具文本输出；按段落/结构块切分（JSON/代码/表格拆坏即整块保留），程序先标 must-keep（错误/审批/未知状态/分页 token/引用依赖/用户要全文），其余块批量判分，低相关且无保留信号才隐藏，未知/缺题一律保留。
- **请求副本投影**：省略标记 + 恢复引用替换隐藏块；不改 toolCallId 对应关系；压缩摘要源保持 MessageNode 原文（现有设计天然满足"压缩不吃投影副本"）。
- **恢复路径**：`conversation_expand` 已是常驻工具（`ToolSearch.kt:390`，与 `conversation_search` 同组）——Phase 2 第一步核实它对**工具输出**的恢复保真度；不达标则补最小读回（按 messageId+toolCallId 从会话树读原文），没有恢复路径的类型不启用隐藏。
- **账本**：`RequestSnapshotFactory` 已记 messagesDigest+模型实际所见；投影后的请求快照须体现投影结果；持久化会话始终存完整工具输出，重复准备幂等（`ToolEffectLedger` (runId,toolCallId) 幂等先例可参考）。
- 门槛沿用：冻结集必要证据漏失=0、原文恢复率 100%、任务正确性不低于基线、适用场景主模型输入 token 中位数 −20%（计入 Jev 成本与恢复调用）。

## 6. Phase 3：模型调度与网页自动化

### 6.1 模型调度（结构性差异，需裁决）

Android 现状：子代理无自动模型池——`SubAgentRunner.kt:92-97` 优先级 = role `definition.modelId` 显式 → `getCurrentChatModel()`；池轮转仅议会有（`ModelCouncilValidator.defaultCouncilModelPool` + `roundRobinByProvider`）。`Model.kt` 有模态/abilities/contextWindow，**无价格字段**。

裁决建议（按优先序）：

- **(a) v1 挂议会席位**：议会三固定席+lens 席目前按 provider 轮转，可在此加"任务→候选模型评分"判断；显式模型/用户配置永远优先。
- **(b) 缓做**：不为接 Jev 先造子代理模型池——那是独立的产品决策。
- 若做，Jev 只在"原本就允许自动选池"的分支插入；等待网络期间不占并发名额（`SubAgentManager` 的 `reserveFollowupAdmission` 语义要保护）；价格 unknown 时启用门槛（费用 −10% / 耗时 −15%）只能用耗时维度评估，样本不足不得声称成本收益。

### 6.2 网页自动化

Android **已有 WebMount 快照契约**（比 iOS 预期更好）：`feature/webmount/tools/` 全套 `wm_*` 工具，`wm_click` 要求 `target` = `wm_extract` 返回的 node ref + `snapshot_id`（mandatoryApproval），`WebMountPageSnapshotCache` TTL 3s。上游 Step 6/7 的"快照+revision 校验、动作前重查"映射到该契约，接近 1:1。

- 新增 `wm_run_goal` 有界实验工具：输入 session、用户目标、允许操作范围、完成条件、可选更小预算（运行时预算不可被输入调大）；输出 `completed/handback/needs_user_action/cancelled/outcome_unknown` + 真实步骤与来源引用。dry-run 只产决策轨迹；shadow 不点击不输入。
- 每轮从当前快照生成合法动作候选；Choice 选操作 + Score 独立评目标，不拼非法组合；文本值由主模型/现有生成入口提供，Jev 不产任意字符串。动作白名单沿上游表（观察/滚动/选择/展开/导航/草稿输入/只读搜索提交可进；发送/发布/提交/支付/删除/账号一律 handback）。
- 每个副作用动作走现有执行器+审批+ledger；外层工具一次授权不替代内层每步权限。`ToolEffectLedger` 的 `OUTCOME_UNKNOWN` 状态机已存在，禁止重放未核验动作直接复用。
- 循环上限：6 次动作决策 / 15s / 3 次无进展，且服从 run 剩余预算。注意 kernel 重复守卫对 `wm_` 前缀有 fresh-callId 豁免（`REPEATABLE_OBSERVATION_TOOLS`）——快速循环必须自带独立步数上限，不依赖 kernel 豁免兜底。
- **v1 明确排除 `screen_*`**：无障碍工具族是 bounds/坐标定位、无快照元素身份契约，不进快速循环；accessibility 快速操控留作后续独立设计。

## 7. 新增符号建议

```
core/jev/                                  # 新 Gradle 模块（纯 Kotlin + OkHttp 注入式）
  JevClient.kt            # typed questions/answers、解码校验（题 ID/类型/候选/数值范围）
  JevDecisionCoordinator.kt # deadline/预算/并发/重试/短路/缓存/取消/身份重验
  JevBudget.kt            # 单轮(run)与 App 日预算、冷却、计数
  JevMode.kt              # OFF / SHADOW / ACTIVE
  JevPurpose.kt           # TOOL_DISCOVERY / MEMORY_RECALL / CONTEXT_SELECTION / MODEL_ROUTING / WEB_AUTOMATION
  JevDataScope.kt         # TOOL_METADATA / TASK_TEXT / PERSONAL_MEMORY / TOOL_OUTPUT / WEB_CONTENT

Settings.jev: JevSetting(                  # 嵌套进单一 Settings data class
  purposes: Map<JevPurpose, JevMode> = 全 OFF,
  dataScopes: Set<JevDataScope> = emptySet(),
  model: String? = null,                   # active 用固定版本；jev-latest 仅连接测试
  policyVersion: Int = 1,
)
SecretStore: SecretDescriptor("jev", "default", "apiKey")

feature/ui/pages/setting/SettingJevPage.kt  # 仿 SettingSearchPage 模板
app/src/test/.../jev/                       # JVM：client/coordinator/预算/缓存/失败路径 + fixtures
docs/reviews/jev/phase-{1,2,3}-report.md   # 沿上游报告模板，执行时才创建
```

## 8. 风险与守门

- **工作区现状**：当前有 25 个文件的未提交 WIP（记忆系统、气泡、Live 等多条并行线混存）。Jev 实施必须等这些落地后独立分支/独立提交组进行，绝不混存。
- 测试策略按根 AGENTS.md：先定点 JVM 测试，再按风险扩大到 app compile/assemble；新增 fixtures 不依赖模拟器；真机证据（弱网、后台、取消）单列，缺真机时用途保持 off/shadow。
- off/shadow/active 是新枚举（仓内无三态先例）；shadow=也外发必须在设置页明示。数据范围请求所需全允许才发送。
- 日志与指标不含 Key、Authorization、原始 state、私有响应全文；缺 usage 的请求标未知不计零费。
- 上游的"执行 AI 约定"中 iOS 专属项（xcodegen、Shared.framework、Codex 前缀分支）不适用；其余（先基线后改动、可回退、不用 mock 冒充收益、不发布应用）全部继承。

## 9. 定稿实施决策（2026-09-17 执行前裁决）

- **D-3a**：`MODEL_ROUTING` 只挂议会席位自动池选择（`defaultCouncilModelPool` + `roundRobinByProvider` 所在分支）；role/modelId 显式配置与用户选择永远优先；**不为子代理新造模型池**，子代理回落分支最多接 shadow 指标。
- **D-模式语义**：OFF=零网络零缓存应用；SHADOW=计算+记录指标，业务零变更（记忆不 touch Jev 选集、工具不暴露 Jev 选集、投影不生效、网页 dry-run 且不点击）；ACTIVE=应用结果，失败/超时/缺题/低置信/预算不足一律走原路径。
- **D-轮次锚点**：Jev 请求的用户轮锚 = 最后一条用户消息内容哈希（工具循环各步稳定；steer 后变化即失效重算），候选集哈希参与缓存键。
- **D-模块**：`core/jev` 用 `kotlin("jvm")`；OkHttp 注入式（测试 interceptor 桩）；App 日预算计数器经注入接口由 app 层持久化（SharedPreferences），core 层零 Android 依赖。
- **D-线格式**：请求 `POST /v1/systemone`，`{model, state, questions:[{question_id, type: choice|score|noul, ...}]}`；响应含逐题答案。字段名以首次真实连接测试核对官方文档为准，解码端严格校验题 ID/类型/候选 ID/数值范围，未核对前所有用途保持 OFF/SHADOW。
- **D-不做的**（防过度设计）：不建通用评估框架、不做跨用途配置中心、不给记忆加 citation allowlist（后续可选）、不给 memory 加主动 search 工具、不把 screen_* 纳入快速循环、不改 SecretStore、不动议会既有席次结构。

## 11. 实施后记（2026-09-17 三阶段完成）

- 三阶段全部落地，离线定点测试全绿，三轮对抗性审查零 P0；修复清单与不修清单见 `docs/reviews/jev/final-report.md`。
- 审查补充的既成裁决：Jev 请求体在 DEBUG 构建也不落日志（typesafe.ai 全构建 body 脱敏）；解码失败是确定性错误（不重试，报 INVALID_RESPONSE）；投影器工具级失败信号（`"status":"failed"/"denied"/"policy_denied"/"approval_required"/"error"`）整体不筛，块级 must-keep 仅作第二道保留；>1500 字符判题包强制保留；wm_run_goal 的时长上界是"步间+动作派发前"双检的软上界（单步观察/判题超时可小幅越界，不引入中断动作的硬 withTimeout 以避免把已派发动作变成 unknown）。
- 已知权衡（不修）：投影与记忆重排共享单轮 6 次出站预算，冷缓存长会话首轮最坏增加数秒延迟（两侧均回退安全）；MODEL_ROUTING 的 runKey 不穿透 Manager 三层，仅受日预算约束；候选池 24+16 截断下 >24 条置顶记录的极端边界。

## 10. 建议顺序

1. **基建 + shadow 度量**：`core/jev` 全套 + SecretStore + 设置页 + 连接测试。零业务风险，独立可交付。
2. **Phase 1 记忆召回**（候选构建 → 重排 → 回退，shadow 先行）；同时量目录规模分布决定工具发现优先级。
3. **Phase 1 工具发现**（lazy 模式才动手）。
4. **Phase 2**（先核实 `conversation_expand` 对工具输出的恢复保真度）。
5. **Phase 3b `wm_run_goal`**；**Phase 3a 按裁决**（议会席位或缓做）。

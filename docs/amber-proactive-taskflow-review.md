# Amber 主动智能任务流 — 设计评审 + 场景目录

> 状态：设计评审（reviewer 视角，仅文档，不含代码改动）
> 范围：`Signal → Opportunity → BoardTask` 三层主动识别框架，以「会议准备」为首个 vertical slice
> 依据：基于实读代码 —— `SignalAggregator` / 各 `*SignalCollector` / `BoardAgent` / `BoardScheduler` /
> `BoardItemEntity`（`toEntity` = `stableItemId(boardDate|sourceType|sourceRef|category)`，category 当前硬编码 `"todo"`）/
> `BoardWeightEntity`（反馈权重）/ `FeishuDocRadarWorker` + `FeishuDoc{Snapshot,Change,Dependency}` + `DocSubscription` /
> `AgentLiveStatusNotifier` + `XiaomiSuperIsland` / `core:agent-store-room` 的 `permission_intent` + `agent_event`
> 待验证项已在文中标注「⚠️需先验证」。

---

## 0. 一句话结论

三层模型方向正确、可落地；降噪的关键不在「分三层」本身，而在 **确定性闸门 + 人类派发 + 抑制/静音** 三件套。
最大结构风险是 **Opportunity 与上一轮刚定义的 `BoardTask(suggested)` 是同一事物的两种表示**，必须先收敛。

---

## 1. 总体结论：方向对，但 Opportunity 不要做成第三套重子系统

把整个看板收敛成**两条轨道**，让 Opportunity 取代「suggested」这个中间态：

```
Track 1（被动/资讯）: Signal → BoardItem      → 看板展示（今日动态/资讯，不可派发）
Track 2（主动/意图）: Signal → Opportunity → 派发 → BoardTask（已承接：状态/事件/通知/结果）
```

- `BoardTask` 从此 **只在派发后存在**，起始态 `in_progress`（或 `accepted`），**删除 `suggested` 状态**。
- 「未承接的建议」由 Opportunity 表达；噪音、置信度、抑制、过期全部留在 Opportunity 层，不污染任务历史。
- 这条决策是后续字段/UI/调度的地基。

---

## 2. 关键风险（按严重度）

| # | 风险 | 说明 | 对策 |
|---|---|---|---|
| R1 | **概念重叠** Opportunity vs suggested-BoardTask | 不收敛会得到三份「未确认建议」表示（BoardItem / suggested / Opportunity）互相污染 | 见第 1 节两轨模型，删掉 suggested |
| R2 | **LLM 自报 confidence 是假的** | 模型给的 `0.8` 不可校准、跨会话漂移 | confidence 由**确定性 scorecard** 算，LLM 只写标题/解释 |
| R3 | **每个 signal 过 LLM = 烧钱+二次噪音** | | 先过确定性 rule gate（复用 `SignalAggregator` 去重+权重+阈值），LLM 只处理少量候选 |
| R4 | **主动通知 = 功能被关头号原因** | 打扰一次，用户三天内静音 | v1 默认静默，仅 app 内 + 每日摘要；通知/超级岛仅留给「高置信+时间紧迫」 |
| R5 | **投机式贵分析炸电量/token** | 给 7 天每个会议都抓文档跑缺口分析，很多会议会取消 | 分层惰性：每天便宜扫；贵分析只在 T-72h 且过闸门 |
| R6 | **文档缺口分析最不可靠** | 误报「你文档不完整」很伤信任 | 缺口=低置信证据，必须带原文引用，绝不自动据此行动 |
| R7 | ⚠️**数据可得性** | 飞书日程经小米办公同步进系统日历后，可能只剩 title/time/desc，结构化参与人/文档链接丢失 | 开 slice 前确认同步后字段；不足则先只做「desc 内含文档链接」的会议 |
| R8 | **后台调度泄漏** | 每会议挂 T-72/T-24 work，会议改期/取消不清理会留陈旧 work | `enqueueUniqueWork(key=meetingId+window)` + 改期/取消时 `cancelUniqueWork` |

---

## 3. 建议架构

### 3.1 组件（沿用现有包约定）

```
Signal 层（复用现有）      : *SignalCollector + SignalAggregator（已去重/打分/阈值）+ BoardSignalEntity
Opportunity 层（新增最小）  : OpportunityDetector(便宜闸门) → OpportunityClassifier(scorecard)
                            → <场景>Planner(贵分析) → OpportunityEntity + Dao + Repository
派发                      : OpportunityToBoardTaskMapper（Opportunity → BoardTask, 回填 dispatchedTaskId）
BoardTask 层（复用上一轮） : BoardTaskEntity / BoardTaskEvent / 任务流 UI / 通知
调度                      : 复用 BoardScheduler anchor 模式 + 每会议 anchored OneTimeWork
审计                      : 复用 core:agent-store-room 的 permission_intent + agent_event
```

### 3.2 Opportunity vs BoardTask 边界

- **Opportunity = 「要不要我做」**：可忽略、可静音、Amber 未投入、会过期。CTA。
- **BoardTask = 「我在做 / 我做完了等你确认」**：用户已认领、Amber 已投入、有事件和结果。Status。
- **何时不应生成 / 不应升级**：
  - 缺时间锚点或对象锚点（纯闲聊）→ 不生成
  - 来源是模型自生成、无底层 signal → 不生成
  - 已有同 `(type, object_ref)` 活跃机会或已派发任务 → 去重
  - 用户对该类型/对象 mute，或 dismiss 后冷却期内 → 抑制
  - **任何情况都不自动升级为 BoardTask**——升级只能由用户点「派发」（产品红线）

---

## 4. 会议准备 pipeline + 调度

### 4.1 Pipeline（标注成本与确认点）

```
1 日历扫描        每日便宜：读系统日历未来7天 → 抽会议(title/time/desc) → dedupe        [便宜, 自动]
2 关联文档发现    解析 desc 链接 / 飞书API / DocDependency → 候选文档                    [中, 自动(元数据)]
3 缺口分析        T-72h, 仅过闸门会议：抓主文档正文 → 1次LLM → 带引用的缺口             [贵, 读正文需opt-in]
4 候选生成        scorecard 打分 → confidence → 生成/更新 OpportunityEntity(suggested)  [便宜, 自动]
5 用户派发        UI「派发」→ Mapper → BoardTask(in_progress)                            [人类闸门]
6 执行与进度      只读研究：查文档/历史纪要/聊天/(opt-in)联网 → 整理草稿                 [中, 读自动 写不自动]
7 等待确认        产出草稿 → waiting_user                                                [人类闸门]
8 写回结果        写飞书/发消息 = 必须确认后手动；v1 先产应用内/本地草稿                [高, 永远确认]
```

### 4.2 扫描机制

- **每日低频扫 7 天**：合理，但只做第 1 步（便宜），不抓文档。
- **事件触发**：合理但去抖。文档变更复用 doc radar（`FeishuDocRadarWorker` + `FeishuDocChange`，⚠️需确认现有能力边界）。日历变更靠每次扫描 diff，别上 ContentObserver 长监听。
- **临近复查分层（权重不同）**：
  - **T-72h = 准备机会**（跑一次贵缺口分析，生成 Opportunity）
  - **T-24h = 复查**（文档变了/缺口还在 → 重算 confidence/证据，dedupe 幂等更新）
  - **T-3h = 轻提醒**（来不及准备，只提示「明天的会，材料还没补」）
- **省电/保活/被杀**：
  - **WorkManager 为主**（`OneTimeWork + initialDelay`，沿用 `BoardScheduler` anchor 模式，避免 PeriodicWork 漂移，尊重 Doze）
  - **AlarmManager 仅用于** T-3h 精确提醒（`setExactAndAllowWhileIdle`），不用于扫描
  - **前台服务不用于扫描**；v1 执行短时只读，连 FGS 都不需要
  - 每会议 work 唯一键 + 改期/取消清理（R8）

### 4.3 Skill 模式（`/会议准备`）

- 询问范围 3/7/14 天（默认 7，快捷 chip）
- 读飞书文档：询问或读已保存的按来源设置（手动模式别偷偷读正文）
- 联网搜索：询问，默认关
- **输出 = Opportunity 列表**，让用户挑着派发；**手动模式绝不静默建 BoardTask**
- **共用底层**：`Scanner` / `OpportunityClassifier` / `<场景>Planner` 做成纯服务，注入后台 worker 与 Skill 处理器两个入口；差异只在触发/交互/是否持久化

---

## 5. OpportunityClassifier — scorecard（系统灵魂，不要问 LLM 要分）

confidence = 各锚点 bool/分档 加权求和：

| 锚点 | 含义 | 权重 |
|---|---|---|
| 时间锚点 time | 有具体且落在有用窗口(T-72h~T-3h)的时间 | **gate 必需** |
| 对象锚点 object | 具体实体：会议/文档/人/截止 | **gate 必需** |
| 资料锚点 material | 找到 ≥1 关联产物（文档/纪要/聊天） | 高 |
| 缺口证据 gap | 可引用的具体缺口（非「可能不完整」） | 最高（但标低可信，带引用） |
| 可执行下一步 action | 明确、有界、Amber 真能做 | 高 |
| 来源可信度 source | 日历/飞书文档 > 明确请求 > 随口 > 模型自述(≈0) | 乘子 |
| 用户历史 history | **复用 `BoardWeightEntity`**：同类派发/完成↑，dismiss/mute↓ | 乘子 |

- **threshold**：低于阈值只入库不展示；高于才进「建议准备」区；仅「极高+紧迫」升级通知。阈值=可配置常量，先保守。
- **suppression/mute**：去重键 `dedupe_key = type+object_ref`；mute by type / by object；dismiss 记 reason + N 天冷却；每日机会数上限(≤5)。
- **硬排除**：无双锚点的聊天碎片、模型自生成无 signal、已派发对象、已静音类型。

---

## 6. 数据模型 — OpportunityEntity（值得加的唯一新表）

v1 **不给 Opportunity 单独建 event log**（状态机太短，`status + updated_at` 够；事件账本是 BoardTask 才需要）。

```kotlin
@Entity(tableName = "opportunity",
  indices = [Index("status"), Index("dedupe_key", unique = true), Index("trigger_at"), Index("due_at")])
data class OpportunityEntity(
  @PrimaryKey val id: String,              // = hash(dedupeKey)，确定性主键 + OnConflict.REPLACE（沿用 BoardItem 模式）
  val dedupeKey: String,                   // type|object_ref
  val opportunityType: String,             // v1 仅 "meeting_prep"
  val sourceType: String, val sourceRef: String,
  val title: String, val summary: String,
  val evidenceJson: String,                // 关联文档 + 缺口(带引用) + 命中锚点
  val scoreJson: String,                   // scorecard 各项明细（可解释/可调参/可回归测试）
  val confidence: Float,
  val status: String,                      // suggested | dispatched | dismissed | expired | muted
  val suggestedActionsJson: String,        // v1 仅描述，不绑 CapabilityRegistry
  val dueAt: Long?,                        // 事件本身时间（会议时间）
  val triggerAt: Long?,                    // 下次复查/展示时间（T-72/T-24）
  val dispatchedTaskId: String? = null,    // 派发后回填 → BoardTask
  val dismissedReason: String? = null,
  val muteScope: String? = null,           // type | object | null
  val expiresAt: Long?,                    // 过会议时间自动 expire
  val createdAt: Long, val updatedAt: Long,
)
```

- **关系**：Opportunity 1—0..1 BoardTask（派发时建任务、回填 `dispatchedTaskId`、自身转 `dispatched`）。
- **与 BoardTaskEvent**：派发时往 BoardTaskEvent 写 `origin: opportunity:<id>`，证据快照冻结进任务（机会过期不影响任务）。
- **置信度演化**：T-24h 复查重算 score、更新行（dedupe_key 幂等），不新建。

---

## 7. UI 与隐私权限

### 7.1 UI

- **独立分区**：任务流顶部「✨ 建议准备 / 可能事项」(Opportunity)，下方才是「正在进行 / 等我确认 / 已完成」(BoardTask)。一眼分清「建议你派发」vs「已经在做」。
- **去黑盒**：机会卡可展开「依据」——找到哪些文档、判断出哪些缺口（**带原文引用**）、命中哪些锚点、为什么现在提。
- **操作**：`派发` / `忽略` / `不再提醒这类`。
- **不打扰**：默认静默，仅打开 app 时出现 + 每日一次摘要（可关）。

### 7.2 隐私/权限分级

| 数据/动作 | 级别 | 策略 |
|---|---|---|
| 系统日历读取 | 低 | 一次性授权后自动（已有 READ_CALENDAR） |
| 通知监听 | 中 | 显式授权后自动（已是 NotificationListenerService） |
| 聊天上下文 | 低 | 本地自有数据，自动 |
| 飞书文档元数据 | 中低 | 按来源 opt-in 后自动 |
| 飞书文档全文 | 中 | 按来源显式 opt-in（后台主动读正文 > 当面要求读） |
| 联网搜索 | 中 | 后台默认关；Skill 询问；执行期按任务 opt-in |
| 写回飞书/发消息/改正式文档/ADB·Accessibility | 高 | **永远等待确认，绝不自动** |

- **审计日志**：复用 `core:agent-store-room` 的 `permission_intent`（kind/tool_id/payload_digest/reason/channel/decision/decided_by/decided_at）+ `agent_event`，不新建审计表。读正文/联网/任何写都落一条 intent。

---

## 8. v1 最小可交付（meeting_prep 一个 vertical slice）

满足：不自动控机、写回前必确认、不做 CapabilityRegistry、避免过度工程化。

1. ⚠️先验证 R7（同步后日历事件有无文档链接/参与人；不足则缩小 slice）
2. 1 张新表 `opportunity` + Dao + Repository（确定性主键 + REPLACE）；**不加** opportunity event 表、不加 step/capability
3. 复用 signal 层：OpportunityDetector 直接消费 `SignalAggregator` scored signals 作闸门
4. scorecard classifier（纯 Kotlin 可单测）+ **1 次 LLM** 只写标题/解释/缺口；**confidence 由 scorecard 算**
5. 调度：复用 `BoardScheduler` anchor + 每日 7 天便宜扫 + 每会议 T-72/T-24 anchored work（唯一键+清理）
6. UI：任务流顶部「建议准备」区渲染 `opportunity(suggested)`，静默 + 可选每日摘要；`派发/忽略/不再提醒这类`
7. 派发：`OpportunityToBoardTaskMapper` → 复用上一轮 `BoardTask(in_progress)`；证据快照冻结进任务
8. 执行 = 只读研究 → 应用内草稿；写回飞书/发消息 = `waiting_user` 手动确认
9. 审计：读正文/联网/写 → `permission_intent`

不在 v1：CapabilityRegistry、step 表、自动执行、自动写回、OPPO 流体云、多场景。

---

## 9. 最容易返工的点

1. Opportunity 与 suggested-BoardTask 没收敛（同上一轮 AgentTask 撞名同级坑）
2. 用了 LLM 自报 confidence（迟早全撕掉换 scorecard）
3. 去重/抑制键没在建表期定（每日扫描反复生成同一机会，重蹈 boardDate 去重覆辙）
4. 通知太积极（上线一周被静音，是信任问题不是代码问题）
5. 投机式贵分析（电量/token 双爆）
6. 每会议定时 work 不清理（陈旧调度泄漏、误触发）
7. 缺口分析当事实（误报伤信任）

---

# 10. 场景目录（头脑风暴 — 用场景牵引功能）

### 10.1 「好场景」的统一形状

能带来**真实可派发任务**的场景，几乎都满足同一个判别式：

> **（强时间/截止锚点 OR 变更事件）+ 具体对象 + 可找到的资料 + 缺口/需求证据 + 有界的下一步**

任何缺时间锚点或对象锚点的「建议」都是噪音。提任何新场景，先拿这把尺子量。

### 10.2 场景候选（评分：信号现成度 / 锚点 / 真实价值 / 噪音风险 / infra 复用）

时间锚点类：

| 场景 | 触发信号 | 价值 | 噪音 | infra 复用 |
|---|---|---|---|---|
| **1 会议准备** meeting_prep（首个 slice） | 日历 + 飞书文档 | 高 | 中 | 高 |
| **2 会议后跟进** meeting_followup：纪要→action items→催办 | 日历(会议结束) + 妙记/纪要 | **很高** | **低** | 高（⚠️妙记/纪要应用内能力需确认，CLI 有 lark-vc/lark-minutes） |
| **3 定期汇报草稿** recurring_report：周报/月报到点前生成草稿 | 周期性日历/文档 + 本周任务/聊天/usage | 高 | 低 | 高（复用 DailyReviewAgent 的输入） |
| 4 截止雷达 deadline_radar：审批/OKR/文档里的 due date | 飞书审批/OKR/文档解析 | 中高 | 中 | 中（需结构化 due） |

文档/知识类：

| 场景 | 触发信号 | 价值 | 噪音 | infra 复用 |
|---|---|---|---|---|
| **5 依赖文档过期** dependency_stale：我引用的源文档更新了，派生文档没跟上 | `FeishuDocDependency` + `FeishuDocChange` | **高** | **极低** | **很高（infra 几乎现成）** |
| 6 文档变更影响 doc_change_impact：我关注的文档被别人改了关键部分 | doc radar + `DocSubscription` | 中高 | 中 | 高 |
| 7 草稿烂尾续写 stale_draft：我的文档停更且未完成 | `FeishuDocSnapshot` + 最后编辑时间 | 中 | 中 | 高 |
| 8 知识消化批处理 knowledge_digest：存了一堆链接/好文没消化 | 通知/聊天链接 + 热榜深读 | 中 | 中 | 中（接 llm-wiki / DeepRead） |

通讯类：

| 场景 | 触发信号 | 价值 | 噪音 | infra 复用 |
|---|---|---|---|---|
| **9 重要消息待回** unreplied_message：要紧的人问我超 N 小时没回 → 起草回复 | `FeishuMessageSignalCollector` | **高** | **高（需重要性过滤）** | 高 |
| 10 聊天派活 chat_action_request：消息里有人请求/派活 → 待办 | 飞书消息/聊天 | 中 | 高 | 中 |
| 11 邮件待处理 email_triage | lark-mail | 中 | 中 | 低（应用内邮件能力需确认） |

任务内省类：

| 场景 | 触发信号 | 价值 | 噪音 | infra 复用 |
|---|---|---|---|---|
| **12 阻塞任务复活** blocked_task_revival：之前 blocked 的任务，依赖物到位了（文档更新/对方回复）→ 可继续 | BoardTask 历史 + signals | 高 | 低 | 高（最 agentic，闭环） |
| 13 挂起任务催促 stale_task_nudge：waiting_user 挂太久 | BoardTask 状态 | 中 | 低 | 高 |

### 10.3 反面清单（明确不做 — 噪音陷阱）

- ❌ 纯 app 使用时长建议（「你今天刷了 2h 抖音」——无对象/无下一步）
- ❌ 模型自省式点子（无底层 signal 支撑）
- ❌ 无个人锚点的热点/趋势（属于 Track 1 资讯，不进 Opportunity）
- ❌ 任何缺时间锚点 + 对象锚点双 gate 的「建议」

### 10.4 推荐排期（会议准备之后）

- **Tier 1（紧接其后，高价值低噪音强复用）**
  - **5 依赖文档过期** —— `FeishuDocDependency`/`FeishuDocChange` infra 几乎现成，证据天然可引用，近零噪音，几乎是「免费」的高质量场景。
  - **2 会议后跟进** —— 与 meeting_prep 共用日历扫描器，锚点最强（会议刚结束），产出 action items 极具体。
- **Tier 2**
  - **9 重要消息待回**（需先做好「重要性」确定性过滤：发件人权重 + 是否含问题/@我 + 时长阈值）
  - **3 定期汇报草稿**（复用 DailyReviewAgent 输入，周期锚点强）
- **Tier 3**
  - 12 阻塞任务复活、7 草稿烂尾、8 知识消化
- **暂缓/需谨慎**：11 邮件、4 截止雷达（需结构化 due）、10 聊天派活（噪音高）

### 10.5 为什么这些场景能「牵引功能」——共用 pipeline 抽象

所有场景共享同一条流水线，**新增一个场景 = 插一对 `Scanner + Planner`**，其余全复用：

```
<Scanner>           ── 场景特有：从某来源发现候选对象（会议/依赖文档/未回消息/阻塞任务…）
   ↓
OpportunityClassifier ── 共用：scorecard 打分 + 抑制/去重（同一套锚点尺子）
   ↓
<Planner>            ── 场景特有：贵分析 + 生成 suggestedActions（缺口分析/纪要拆解/回复草稿…）
   ↓
OpportunityEntity → 派发 → BoardTask → 执行(只读) → waiting_user → 确认后写回
   （共用：实体 / UI / 通知 / 审计 / 派发映射）
```

这正是「场景驱动」的杠杆：先把 meeting_prep 的 Scanner/Classifier/Planner 三件套和共用层做扎实，
之后每个新场景只需补一对 Scanner+Planner，边际成本很低，且自动继承降噪/抑制/审计/UI 一致性。

---

## 11. 待用户拍板的收敛决策

1. **Opportunity 是否取代 `BoardTask.suggested`？**（建议：是，删 suggested，两轨模型）
2. **第二个 vertical slice 选哪个？**（建议：5 依赖文档过期 或 2 会议后跟进）
3. 缺口分析的「贵分析」预算上限（每日 LLM 次数 / token 配额）由谁配置、默认多少？

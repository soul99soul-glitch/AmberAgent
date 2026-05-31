# 依赖文档过期监控 — 场景设计（dependency_stale）

> 状态：设计稿（brainstorming 产出，待用户评审 → 再转实现计划）
> 关联：本场景是 [amber-proactive-taskflow-review.md](./amber-proactive-taskflow-review.md) 中
> `Signal → Opportunity → BoardTask` 两轨模型下的第二个 vertical slice（场景 5）。
> ⚠️ 文中涉及"已有基建"处只看了实体命名（`FeishuWatchedDoc/Snapshot/Change/Dependency` +
> `FeishuDocRadarWorker`），实现完整度需开工前确认。

---

## 1. 用户场景与需求

### 1.1 真实痛点（用户原话还原）

> 我写一份文档，上游有 ~20 篇别人的文档。我文档里**大量数据是手工从上游复制粘贴进来的**（少量是超链接）。
> 上游作者隔三差五改一次，**但不会通知我**。于是我文档里的数据越来越旧，我得不停人工去刷这些上游文档、
> 或者追问作者——成本极高，非常累。

### 1.2 需求拆解

- **基线 + 定时监控**：本地存一份上游快照基线，每天定时（如 9:00 / 15:00 / 20:00）让 Agent 去比对上游变化。
- **精准定位变化**：不是"文档变了"，而是"**哪篇**文档的**哪一节/哪个数据**变了"（删了某章节、改了某数字、加了新内容）。
- **映射到我**：变化要落到"我文档里引用它的那一处"，并给出**具体怎么改**的建议。
- **推送**：有实质变化时推一条通知，我点开就能看。
- **灵活**：现在飞书定位文档是写死的实验功能。我希望能**丢一堆链接 + 一句话说清哪篇是我的、哪些是上游**，
  Agent 就能自己把监控关系建起来，而不是我去手工配死。

### 1.3 关键约束（决定难度的那条）

引用方式以**手工复制粘贴数据**为主，超链接很少。

> 含义：机器层面，你文档里那句"2024 营收 1.2 亿"和上游那张表之间**没有任何链接**，只能靠语义去对。
> 但你判断"语义上十分接近，有机会"——这个判断是对的，下面的实现就建立在"**值级语义锚点**"上。
>
> 补充确认点（不影响 v1 主路径，但值得知道）：飞书原生**同步块**会自动同步，那部分根本不需要监控；
> 这个功能真正救的，正是"**手抄、不会自动同步**"的数据——也就是你最累的那部分。

---

## 2. 产品定义

一句话：**一个"可声明 + 半自动建立"的文档依赖监控器**——你把"我的文档 ← 上游文档"的依赖关系交给
Amber，它为你引用的每个关键数据建立"值级语义锚点 + 基线值"，定时只复查**变化命中了你锚点**的部分，
有实质漂移才推送，并把"怎么改"做成一个可派发的任务，确认后才写回。

### 2.1 它解决什么 / 不解决什么

| 解决 | 不解决（v1 明确不做） |
|---|---|
| 上游手抄数据过期，你不知道 | 自动改你的文档（写回永远要确认） |
| 人工刷 20 篇文档太累 | 全自动无人确认的同步 |
| "文档变了但我不知道变了哪、影不影响我" | 给上游作者自动发消息（高风险，需确认） |
| 写死的订阅关系、无法灵活声明 | 飞书以外的源（v1 只做飞书，留接口） |

### 2.2 核心概念：值级语义锚点（ReferenceAnchor）

这是整个方案的灵魂，也是应对"手抄无链接"的关键：

- 一个锚点 = **「我文档里的某个数据点」↔「上游某文档的某处」+ 建立锚点时的上游基线值**。
- 例：`我.第3节."2024营收 1.2亿"` ↔ `上游A.营收表` ，baseline = `1.2亿`。
- 过期判定 = **上游该锚点现在的值 ≠ 我记录的基线值** → 精准到"值"，不是"文档动没动"。
- 语义匹配**只在建锚点时做一次**（找到数据来自哪）；之后定时扫描是**定向复查**已有锚点，不是每天重算全文。
  这同时保证了**精准**（值级、可解释、能给新旧对比）和**省**（成本被锚点数 × 变化区域 bound 住）。

---

## 3. 实现方式（合适 · 优雅 · 简洁 · 精准）

### 3.1 架构落点（最大化复用，最小化新增）

```
复用现有：
  FeishuWatchedDocEntity     —— 被监控的上游文档（+定时拉取）
  FeishuDocSnapshotEntity    —— 上游基线快照
  FeishuDocChangeEntity      —— 检测到的结构/内容 diff
  FeishuDocDependencyEntity  —— "我的文档 ← 上游文档" 依赖边
  FeishuDocRadarWorker       —— 定时扫描 + 事件触发（⚠️确认现有能力）
  SignalAggregator / Opportunity / BoardTask / 通知 / permission_intent 审计  —— 见主文档

唯一新增（保持精简）：
  ReferenceAnchorEntity      —— 值级语义锚点（下 §3.2）
  DependencyMonitorPlanner   —— 场景特有的"建锚点 + 漂移分析"逻辑（Scanner+Planner 那一对）
```

> 即：这个场景 = 在主文档的统一流水线里，插一对 `Scanner(上游拉取+diff) + Planner(锚点漂移分析)`，
> 其余全复用。新增持久化只有一张 `reference_anchor` 表。

### 3.2 数据模型（唯一新表，刻意精简）

```kotlin
@Entity(tableName = "reference_anchor",
  indices = [Index("my_doc_ref"), Index("upstream_doc_ref"), Index("status"), Index(value=["dedupe_key"], unique=true)])
data class ReferenceAnchorEntity(
  @PrimaryKey val id: String,            // hash(dedupeKey)
  val dedupeKey: String,                 // myDocRef|claimDigest
  val myDocRef: String,                  // 我的文档 token + 定位（heading/锚点/文本片段）
  val myClaimText: String,               // 被引用的数据/陈述，如 "2024营收 1.2亿"
  val upstreamDocRef: String,            // 上游文档 token
  val upstreamHint: String,              // 上游命中处（section/snippet），用于定向复查
  val baselineValue: String,             // 建锚点时上游的值，如 "1.2亿"
  val lastValue: String? = null,         // 最近一次复查到的上游值
  val matchConfidence: Float,            // 由确定性信号算（见 §3.4），非 LLM 自报
  val status: String,                    // proposed | confirmed | dismissed | drifted | acked
  val lastCheckedAt: Long? = null,
  val createdAt: Long, val updatedAt: Long,
)
```

- 与 `FeishuDocDependency` 关系：Dependency 是**文档级**边（我 ← 上游A）；ReferenceAnchor 是**值级**边（我的某数据 ← 上游A的某处）。一条 Dependency 下可有 0..N 个 Anchor。
- 与 Opportunity/BoardTask 关系：锚点 `drifted` → 生成 Opportunity（带新旧对比证据）→ 派发 → BoardTask 整理改写建议 → 确认 → 写回 → 锚点 `acked`（基线更新）。

### 3.3 Pipeline

```
A 建监控（可声明 + 半自动）
  - 你发链接 + "第一篇是我的，其余是上游" → 解析为 doc token → 写 WatchedDoc + Dependency 边 → 抓基线快照
  - 半自动：Agent 从你主文档抽超链接/@引用 → 反向建议上游 → 你勾选确认（少手工）

B 建锚点（语义匹配，只做一次，人确认）
  - 从你主文档抽"数据点/关键陈述"（数字、命名实体、强陈述）
  - 每个数据点 → 在上游候选文档里检索 top-k 段落（关键词/嵌入）→ 1 次 LLM 确认"它是不是源、当前上游值是多少"
  - 生成 ReferenceAnchor(status=proposed, baselineValue=上游当前值, confidence=确定性算)
  - 列给你确认；低置信/匹配不到的，标"未溯源"，不静默信任（§3.5 去黑盒）

C 定时监控（9:00 / 15:00 / 20:00，省）
  - 拉上游 → 与快照 diff（FeishuDocChange）→ 得到"变化区域"
  - 只复查 upstreamHint 落在变化区域内的 confirmed 锚点（定向，非全量重算）
  - 复查 = 重新提取该锚点处上游当前值，与 baselineValue 比 → 不同则锚点 drifted
  - 无锚点漂移的扫描 → 不推送

D 推送（仅实质漂移）
  - 聚合通知："N 篇上游有变化，M 处可能影响你"；点开 = 每条 [哪篇文档][哪一节][旧值→新值][你文档第X处]

E 派发 → 执行 → 确认 → 写回
  - 每个 drifted 锚点 = 一个 Opportunity（证据 = 新旧值 + 双侧定位 + 是谁改的）
  - 派发 → BoardTask：整理成"我文档第X处 1.2亿 → 建议改为 1.5亿（依据上游A 2024营收表）"
  - waiting_user → 你确认 → 写回飞书（高风险，永远手动确认）→ 锚点 acked + 基线更新为新值
```

### 3.4 语义匹配与置信度（精准的来源）

- **置信度由确定性信号算，不问 LLM**：
  - 精确值匹配（你的"1.2亿"在上游该处原样出现）→ 强
  - 命名实体/指标名匹配（"2024营收"在上游同段出现）→ 中强
  - 章节标题/上下文匹配 → 中
  - 用户历史确认过类似锚点 → 乘子（复用 `BoardWeightEntity` 思路）
- **成本被 bound 住**：建锚点是一次性（每数据点一次 LLM）；每日扫描只对"**变化区域 ∩ 已有锚点**"做定向复查——绝不每天 re-embed/re-LLM 全文。
- **匹配不到就如实说**：溯源失败的数据点不建锚点、不监控，并明确告诉你"这 3 个数据我没找到来源"——宁缺毋滥，不假装覆盖。

### 3.5 去黑盒（避免"Agent 瞎猜"的不信任）

- 锚点建立必须**人确认**（proposed → confirmed），尤其手抄数据这种纯语义猜的。
- 每条提醒都摊开依据：哪篇文档、哪一节、**旧值→新值**、你文档里的位置、是谁改的。
- 你可对单个锚点/单篇上游 mute；acked 后同一变化不再提醒，直到出现新漂移。

### 3.6 调度 / 保活（见主文档 §4.2，此处具体化）

- WorkManager 为主，沿用 `BoardScheduler` 的 `OneTimeWork + initialDelay` anchor 模式，排三个锚点 9:00/15:00/20:00（自重排），尊重 Doze/低电。
- 上游文档变更可叠加事件触发（doc radar），与定时去抖合并。
- 不需要前台服务（拉取+diff+定向复查都是短时）。
- 监控关系删除/上游失效时清理对应 WatchedDoc 与 work。

### 3.7 隐私 / 审计（见主文档 §7.2）

- 读上游文档正文：按来源 opt-in（后台主动读正文是中等敏感级）。
- 写回我的文档 / 给作者发消息：高风险，永远确认。
- 读正文、写回、发消息都落 `permission_intent` 审计。

---

## 4. v1 最小可交付（dependency_stale 垂直切片）

满足：不自动写回、不做 CapabilityRegistry、最大化复用、避免过度工程化。

1. ⚠️先确认 `FeishuWatchedDoc/Snapshot/Change/Dependency` + `FeishuDocRadarWorker` 现有能力边界。
2. **建监控入口**：对话/skill `/监控文档`——发链接 + 声明我方/上游 → 建 WatchedDoc + Dependency + 基线快照。（超链接自动抽取建议为 P2，先手工声明）
3. **1 张新表** `reference_anchor` + Dao + Repository。
4. **建锚点**：抽数据点 → 候选检索 → 1 次 LLM 确认值与来源 → proposed → 用户确认。
5. **定时监控** 9/15/20：diff → 定向复查变化区域内 confirmed 锚点 → drifted。
6. **Opportunity + 推送**：仅漂移时生成机会 + 聚合通知（复用主文档机会层/通知）。
7. **派发 → BoardTask**：整理新旧对比改写建议 → `waiting_user` → 确认后写回（手动）→ 锚点 acked + 更新基线。

**不在 v1**：飞书以外的源、超链接自动反向发现、给作者自动发消息、CapabilityRegistry、无人确认的写回。

---

## 5. 最容易返工的点

1. 想一步到位"全自动语义对齐全文" → 成本爆炸且不可信。**坚持"建锚点一次性 + 定向复查"**。
2. 用 LLM 自报 confidence → 换成 §3.4 的确定性信号。
3. 锚点不做人确认 → 手抄数据纯靠猜，误报会让你直接关功能。
4. 每次扫描全量重算 → 必须"变化区域 ∩ 已有锚点"定向。
5. 漂移 acked 后不更新基线 → 同一变化天天提醒。
6. 把它做成又一套独立子系统 → 必须落在主文档的 Signal→Opportunity→BoardTask 两轨里，只加一张表 + 一对 Scanner/Planner。

---

## 6. 待确认

1. 锚点确认的交互放在哪：建监控时一次性批量确认，还是首轮扫描后边用边确认？
2. "数据点"抽取粒度：只抽数字/指标，还是也含强陈述句（结论/定义）？（建议 v1 先数字/指标，最易精准）
3. 推送时段 9/15/20 是否可配；无变化是否完全静默（建议：是）。

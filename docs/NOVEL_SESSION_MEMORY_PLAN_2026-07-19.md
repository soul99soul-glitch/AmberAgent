# 小说创作：会话生命周期与记忆分层补墙计划

Created: 2026-07-19
Status: S1-S3 已完成（2026-07-19），按计划停在 S3；S4-S7 未开始，等待真实 provider 与真机评估后再决策。

## 目标与边界

解决三个已确认的产品级逻辑问题：讨论过长导致的静默失忆/决定矛盾/噪音劣化、讨论结论无归档出口、正文成品滞留聊天流。全程遵守仓库纪律：

- 一次一个小闭环：契约测试 → 最小实现 → 定点 + 回归门禁。
- 不新增第二套滚动状态机、不加几何补偿；schema 变化过 `NovelDocumentValidator`，旧文档单向兼容（无新字段 = 旧行为）。
- 所有模型蒸馏产物必须经用户确认才落盘；确认流复用 settingProposals 形态。
- 触碰投影/滚动/注入的切片，按 `iosApp/AGENTS.md` 跑对应定点 + 强制 `ChatStreamReplayTests`；真实 provider 与真机手感明确记为外部验证项。

## 切片顺序与依赖

S1、S2 独立可先行；S3 是核心，S4-S6 依赖 S3 的数据结构；S7 独立但建议最后（触碰滚动行身份）。

---

## S1 中断草稿可收录（小切片，先赢）

**状态：已完成（2026-07-19）**

**问题**：停止 ≠ 作废，但 interrupted 草稿只有"重新生成"，2000 字好内容不能用。

**改动**：
1. 领域层：定位收录命令对 candidate status 的校验点（collection command / reducer 侧），放开 `.interrupted` candidate 的收录合法性；`branchNeedsSync`、`staleCandidate`、`sourceChapterChanged` 等既有 blocker 全部保留。
2. 投影层：`NovelSessionPresentation.actions(for:)` 对 interrupted prose 候选行输出 `collectProse` 可用性（与 retry 并列）；transientRow `.interrupted` 分支同样补 collect action（tail 未清时）。
3. UI：`NovelSessionBubble` statusLine 中断文案改为「生成已中断 · 可收录已生成部分」；收录按钮文案沿用 granularity 语义。
4. 收录内容 = 终态持久化的 partialContent 权威快照，不做任何自动续写或补全。

**验收**：中断→收录成功、收录后 retry 不再可用、needsSync 仍阻塞、undo 收录回滚正常。跑 `NovelSessionViewModelTests` + `NovelSessionReplayTests` + 收录链定点。

**不做**：自动续写、interrupted 语义本身、quickStart 中断路径。

---

## S2 注入可见化：token 环 → 注入清单面板（只读，零行为变化）

**状态：已完成（2026-07-19）**

**问题**：连续性失忆的前提是"用户不知道模型看到了什么"；receipt 数据已存在，UI 只有一个数字环。

**改动**：
1. 核对 `NovelInjectionReceiptRecord` 现有字段；缺失则补记：本次注入的 material ID 清单、是否携带剧情状态摘要、携带的最近消息轮数、因预算未纳入的条目计数。只加记录字段，不改注入行为。
2. `ComposerContextPanel` 增加分区列表：设定条目（名称+kind）、剧情状态（有/无）、会话窗口（N 轮）、「未纳入 M 项」。数据源 = `latestContextReceipt`（NovelSessionView 已有该派生）。
3. 纯投影函数 `NovelInjectionPanelPresentation`（receipt → 面板模型），View 只消费。

**验收**：投影纯函数测试覆盖空 receipt/满预算/有排除项；`IOSNovelCreationWiringTests` 补面板 wiring 断言。

**不做**：pin/unpin（S6）、注入内容修改入口。

---

## S3 讨论归档 MVP：收录整章时蒸馏决定清单（核心）

**状态：已完成（2026-07-19）；真实 provider 蒸馏质量与真机折叠手感待外部验证。**

**机制**：
1. **触发点**：`collectProse(wholeChapter)` 成功后的确认页追加「归档本章讨论」步骤（可跳过）；另在会话菜单提供手动「归档当前讨论」。不做定时/自动触发。
2. **蒸馏**：单次模型调用（复用现有 provider 链路与"连续无输出"超时语义），输入 = 自上次归档游标以来的讨论消息（排除正文候选全文，控制 token），输出 = 结构化 JSON：决定清单 `[{topic, decision, relatedMaterialID?}]` + ≤300 字摘要。解析容错复用 Grok 整对象 JSON fallback 模式；解析失败 = 归档失败可重试，不落半截。
3. **确认流**：复用 settingProposals 的确认 UI 形态，逐条 确认/编辑/删除；全部拒绝 = 归档取消。确认后写入：
   - 决定 → 新 material kind `.decisionLog`（走既有 material 修订链路，随 checkpoint 语义可回滚）；
   - 摘要 → 归档记录自身携带（供注入层与折叠卡片展示）。
4. **归档游标**：`NovelSessionRecord` 新增 `archiveCursor`（sequence 或 messageID）+ 归档记录列表（时间、章节关联、摘要）。投影层把游标之前的行折叠为一张「第 X 章讨论已归档（N 条）」卡片；点击展开复用现有 historyWindow 机制。**数据只折叠不删除**。
5. **注入变化**：游标之前的原始讨论消息退出注入窗口，由 decisionLog materials + 归档摘要替代。

**schema 兼容**：无 `archiveCursor` 的旧文档 = 不折叠、注入行为不变；`NovelDocumentValidator` 同步校验。

**验收**：蒸馏解析容错、确认/编辑/拒绝写入路径、游标折叠投影（行身份稳定、digest 契约）、注入排除已归档消息、旧文档兼容、undo 对 decisionLog 的回滚。门禁：`NovelSessionReplayTests`、`NovelSessionViewModelTests`、注入/文档校验定点、强制 `ChatStreamReplayTests`。蒸馏质量属真实 provider 外部验证项。

**不做**：自动定时归档、跨章节全局重摘要、多轮蒸馏对话、删除原始消息。

---

## S4 决定记录 supersede 语义

**机制**：decisionLog material 带归一化 topic key；新决定确认时同 topic 已有 active 条目 → 旧条标记 superseded（保留历史）。注入只带 active 集。设定页新增「决定」分区：当前有效集 + 可展开历史。

**验收**：同 topic 覆盖、注入只含 active、undo/Fork 后决定随 checkpoint 语义回滚、跨分支隔离。

**不做**：模糊 topic 合并（精确归一化匹配，对齐人物别名的既有取舍）。

---

## S5 注入分层重排（权威递减）

**机制**：injection builder 从「设定 + 尽量塞最近原文到 16k」改为三层：
1. 权威层：设定 + 剧情状态 + active 决定集（最后才被裁剪）；
2. 蒸馏层：归档摘要（按新到旧）；
3. 工作层：最近 K 轮原文（K=6 起，常量集中定义），预算紧张时最先裁剪。

receipt 记录各层实际 token，S2 面板按层展示。

**验收**：裁剪顺序纯函数测试（预算收紧时先丢工作层、权威层最后）；receipt 分层数字；`forceInclude/Exclude` 语义不变。**风险**：改变模型输入分布——上线前用真实 provider 对同一项目做前后对照试写，记为外部验证项，不以单测冒充。

---

## S6 钉住（pin）

**机制**：讨论消息长按「钉住」→ session 级 `pinnedMessageIDs`；注入工作层始终优先包含 pinned（仍受预算约束，被裁剪时面板明示）；S2 面板列出 pinned 可取消。归档不清除 pin，但已归档的 pinned 消息以原文进入蒸馏层之上。

**验收**：pin 持久化/跨重入、注入优先级、面板联动。依赖 S5 分层结构。

---

## S7 收录正文塌缩卡片（架构投资，滚动性能的产品级根治）

**机制**：
1. 投影层：`candidate.status == .collected`（或 committedChange 存在）的正文行改投影为摘要卡片：章节标题/字数/首行摘录 + 「查看正文」跳转（现有 reader 路由）+ 「展开全文」内联动作（展开为一次性完整渲染，默认收起）。
2. 行身份：id 不变、digest 变（kind token 变化触发一次重渲染，行为与现状"收录后状态行变化"同级），同容器不迁移。
3. 收益：transcript 不再滚动整本书；历史窗口、冷实例化占位、完成瞬间重排的问题规模全部骤减。

**验收**：投影切换契约、卡片跳转 wiring、展开/收起、收录瞬间行身份稳定（红 canary：收录前后同 id 同容器）；滚动定点 + 强制 `ChatStreamReplayTests`；真机长项目滚动手感复验。

**不做**：默认展开、把讨论消息也卡片化、删除全文数据。

---

## 全局风险与外部验证清单

- 蒸馏/摘要质量：确认流是硬门；真实 provider 对照试写（S3、S5）。
- 注入分布变化可能影响文风连续性：S5 上线前后各留一次同项目对照样本。
- 真机手感：S3 折叠卡片与 S7 塌缩卡片各需一次真机长项目复验（轻拖、完成瞬间、重入）。
- schema 迁移：S3/S4 字段全部可选、单向归一化，禁止破坏旧项目包导入。

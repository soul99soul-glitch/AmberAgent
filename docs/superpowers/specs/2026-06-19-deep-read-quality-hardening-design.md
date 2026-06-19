# Deep Read 质量硬化设计

- 日期：2026-06-19
- 分支：feature/council-host-orchestration-tools
- 状态：已批准，待写实现计划

## 背景与动机

Deep Read 是杂志风格的全屏深度阅读功能，由隐藏 agent 分四段（OVERVIEW / NARRATIVE / ANALYSIS / EXTENDED_READING）通过 writer tool 模式生成。代码审查发现当前实现存在三个体验问题：

1. **失败伪装成成功**：模型没调 writer tool 时，`writeFallbackSection` 产出占位正文（"围绕 xxx，当前来源已提供可继续阅读的基础事实…"）并标成 `READY + BASIC`，与 playbook 明确禁止的占位话术相矛盾。用户看到的是看似成功、实则废话的内容。
2. **质量门槛与规格脱节**：prompt 要求"summary 120-250 字"，但写入门槛 `OVERVIEW_SUMMARY_MIN_CHARS = 24`；seed source 可用门槛仅 15 字符，能放进大量纯标题垃圾来源。
3. **缓存策略不适合杂志档案定位**：固定 24h TTL，无法配置；一周前的深读直接看不到，也不支持手动收藏。

本设计聚焦这三类问题。**明确排除** claim 级来源标注和段落级流式——它们各自够写一份独立 spec，且与已被否决的 VERIFYING 验真面临相同的弱模型产出率问题，留待后续单独设计。

## 范围

六个增强项，按改动耦合度分三个簇：

| 簇 | 项 | 成本 |
|---|---|---|
| A：质量门槛治理 | 废除正文降级 + 门槛温和拉齐 + 弱模型策略（合一） | 中 |
| B：历史与缓存 | TTL 可配置 + pin 永久保留 | 中（含 DB migration） |
| C：文案与死代码 | token 估算诚实化 + 死代码清理 | 小 |

### 核心设计决策（用户已确认）

- **不要降级**：失败段正文一律 FAILED，不产出占位正文。失败段的 `references`/`extendedReading` 仍由其他 READY 段或规划阶段保留（真实来源链接有价值，不伪装成正文）。
- **门槛温和拉齐**：overview 最小 80 字、seed source 最小 60 字。门槛与"不要降级"配套——门槛不能高，否则本来能成段的也被判 FAILED，产出率崩。
- **pin 只存 DB.pinned**：单一真相源，prune/查询都走 DB；不在 Settings 里冗余 pin 集合。
- **TTL 通过 saveDeepRead 参数传入**：Repository 保持不依赖 Settings（符合现有分层）。
- **保留未来验真脚手架**：`VERIFIED`/`BASIC` 质量枚举、`verificationState` 字段、`DeepReadEvidenceRegistry`、`VERIFYING` 相位不删，留给未来 claim 标注/轻量验真。

---

## 簇 A：质量门槛治理

### A1. 废除正文降级

**现状**：`runStageSupervisorLoop` 中 stage 未达 READY → `tryFallbackAfterStageFailure` → `writeFallbackSection` 产出占位正文 + 标 `READY + BASIC`。

**目标**：所有失败情形（没调 tool / 调了但稀薄 / timeout / provider 错误）→ **标 FAILED，无正文**。

**改动点**：

1. `DeepReadSectionWriterTools.writeFallbackSection`（`DeepReadSectionWriterTools.kt:94-141`）：
   - 保留**唯一**职责：把来源链接合并进 `references`/`extendedReading`。
   - 删除正文兜底逻辑（`fallbackBody` 产出 summary/timeline/corePoints/analysis 的分支）。
   - 不再调用 `withSectionStatus(stage, READY)` + `withSectionQuality(stage, BASIC)`。
   - 返回值仅承载 links 合并结果，**不改 section 状态**——状态交还调用方 `markFailed`。

2. `DeepReadAgentRunManager.tryFallbackAfterStageFailure`（`DeepReadAgentRunManager.kt:632-659`）：
   - 调用 `writeFallbackSection` 仅用于保留链接。
   - 其判断 `fallback.statusOf(stage) == READY` 在新语义下永远为 false，所以最终走到 `writer.markFailed(stage, ...)`。
   - 简化逻辑：保留链接合并后直接 `markFailed`，不再尝试用 `statusOf(stage) == READY` 判定恢复。
   - **返回值语义**：函数返回 `Boolean`（recovered），被 `runStageSupervisorLoop` 的 timeout 分支（`:497`）和 other 分支（`:512`）消费，用于 `if (!recovered && status != READY) markFailed(...)`。新语义下 recovered 恒为 false，调用点会再次 markFailed，但 `markFailed`（`:85-92`）对已 FAILED 段是 no-op（先判 `statusOf(stage) == READY` 才写），不会出错。可保留 Boolean 返回（恒 false）或改为内部直接 markFailed 后返回 Unit 简化调用方；实现时任选其一，关键是行为正确。

3. `DeepReadSectionWriterTools.markRequiredWrite` / `writeFallbackSection` 中的 `markRequiredWrite()` 调用：links-only 的 fallback 不算一次"有效写入"，不调 `markRequiredWrite`，避免污染 supervisor loop 的 `requiredWriteCount` 计数。

### A2. 门槛温和拉齐

| 常量 | 现值 | 目标 | 位置 |
|---|---|---|---|
| `OVERVIEW_SUMMARY_MIN_CHARS` | 24 | **80** | `DeepReadSectionWriterTools.kt:31` |
| `MIN_SEED_SOURCE_CHARS` | 15 | **60** | `DeepReadSourcePrefetcher.kt:712` |
| `MIN_SOURCE_CHARS` | 280 | 280（不动） | `DeepReadSourcePrefetcher.kt:710` |

- `MIN_SOURCE_CHARS`（搜索结果来源）保持 280，合理。
- `OVERVIEW_SUMMARY_STORAGE_MAX_CHARS = 1200`（存储 cap）保持不变——它是截断保护，不是质量门槛。

**风险与预期**：门槛拉高后更多 overview 被 `hasOverviewContent()` 判否 → 不标 READY → 走 A1 的 FAILED。这是预期行为——弱模型写不出 80 字概览本就该失败，不该用占位话冒充。产出率会下降，但符合"不要降级"的产品意图。

### A3. UI 配套（无需新增，含连带清理）

- `SectionErrorCard` 已存在，FAILED 段会显示"概览生成失败 + 仅重试这一段"，**不改 UI**。
- `BasicDraftNotice`（"基础稿，可继续增强"，`DeepReadScreen.kt:848`）成为死代码——A1 后无 BASIC 段产生。连带清理全部 6 处引用（经核实）：`hasBasicDraft()` 扩展函数定义（`:487`）、状态变量 `val hasBasicDraft`（`:193`）、两处 when 分支 `hasBasicDraft && !complete -> TemplateFallbackNotice(...)`（`:331`、`:436`）、一处 `if (output.hasBasicDraft())` 块（`:784`，含 `BasicDraftNotice` 调用 `:787`）、`BasicDraftNotice` composable 定义本身（`:848`）。归入簇 C。

---

## 簇 B：历史与缓存

### B1. 数据模型与存储

**`DeepReadCacheEntity` 加 `pinned` 列**（`HotListEntities.kt:64-78`）：

```kotlin
@ColumnInfo("pinned")
val pinned: Boolean = false,
```

**DB migration**：当前 `version = 6`（`AppDatabase.kt:122`），新增 `MIGRATION_6_7`：

```kotlin
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // BOOLEAN 存为 INTEGER。SQLite 的 ALTER TABLE ADD COLUMN 允许 NOT NULL，
        // 但声明 NOT NULL 时必须同时提供非 NULL 的 DEFAULT；DEFAULT 0 让既有行回填为未收藏。
        // 范式参照 MIGRATION_4_5（AppDatabase.kt:395-406），而非 MIGRATION_5_6（后者是可空列 DEFAULT NULL）。
        db.execSQL(
            "ALTER TABLE `deep_read_cache` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0"
        )
    }
}
```

- `AppDatabase` 的 `version = 6` → `version = 7`。
- migration 注册点：`app/src/main/java/app/amber/core/di/DataSourceModule.kt:125-131` 的 `.addMigrations(...)` 调用链，追加 `MIGRATION_6_7`。
- 新增 schema 快照 `app/schemas/app.amber.agent.data.db.AppDatabase/7.json`（构建时 Room 自动生成，需提交）。

**`TodayBoardSetting` 加 TTL 字段**（`BoardSettings.kt:171-190`）：

```kotlin
/**
 * Deep Read 缓存有效期（天）。0 = 永不过期（仅手动删除）。
 * 默认 7 天，对齐杂志档案定位。
 */
val deepReadCacheTtlDays: Int = 7,
```

- 不加 `deepReadPinnedTopicIds`——pin 状态只存 DB.pinned。

### B2. TTL 应用与 prune

**现状两层 TTL**（`HotListRepository.kt:205-206`）：
- `DEEP_READ_TTL_MS = 24h`：fresh 判定期，过期后标记"已失效"但保留。
- `DEEP_READ_HISTORY_RETENTION_MS = 7天`：彻底删除期，过期后从 DB 删除。

**问题**：若 TTL 默认变 7 天而 retention 仍 7 天，则"刚失效就删除"，用户看不到已失效历史。

**目标**：

| 设置项 | 值 | 行为 |
|---|---|---|
| `deepReadCacheTtlDays` | 1 | fresh 期 24h（旧行为） |
| `deepReadCacheTtlDays` | 3 | fresh 期 3 天 |
| `deepReadCacheTtlDays` | 7（默认） | fresh 期 7 天 |
| `deepReadCacheTtlDays` | 0 | 永不过期（fresh 永真） |

**改动点**：

1. `HotListRepository.saveDeepRead`（`HotListRepository.kt:177-193`）加可选参数：
   ```kotlin
   suspend fun saveDeepRead(
       topicId: String,
       title: String,
       output: DeepReadOutput,
       now: Long = System.currentTimeMillis(),
       ttlDays: Int = DEFAULT_TTL_DAYS,  // 新增，默认 7
   )
   ```
   - `ttlDays == 0` → `expiresAt = Long.MAX_VALUE`（避免溢出，isFresh 永真）。
   - `ttlDays > 0` → `expiresAt = now + ttlDays * 24h`。
   - `DEFAULT_TTL_DAYS = 7` 作为常量。
   - 调用方取数方式：
     - `DeepReadAgentRunManager`：在 `createRunContext`（`:291-381`，`:300` 已读 `settings`）读取 `settings.agentRuntime.todayBoard.deepReadCacheTtlDays`，new writer 时传入（见下条）。
     - `DeepReadWorker`：是 `KoinComponent`（`:23`），用 `get<SettingsAggregator>().settingsFlow.value.agentRuntime.todayBoard.deepReadCacheTtlDays` 同步取值，传给自己 3 处 `saveDeepRead` 调用（`:44,116,124`）。
     - `DeepReadSectionWriterTools`：不持有 settings，**在构造时接收 `ttlDays` 参数并存为私有字段**。这是关键——writer 的私有 `update()`（`:564-569`）是段落写入的主要路径（overview/narrative/analysis/extendedReading 各 tool 都走它），每次调用 `repository.saveDeepRead(topicId, topicTitle, next)` 时必须把 `ttlDays` 一并传入，否则每次段落写入都用默认 TTL 重算 expiresAt，配置形同虚设。构造时机：`createRunContext`（`:341`）已能拿到 settings，直接 `DeepReadSectionWriterTools(repository, topicId, topicTitle, imageCandidates, allowTitleFallback, ttlDays = settings...)`。

2. **保留** `DEEP_READ_HISTORY_RETENTION_MS` 语义不变。关键理解：retention 不是"从生成点起 N 天删除"，而是"**过期后再保留 7 天才删除**"。证据是 `pruneExpiredDeepReads(now)` 调用 `dao.pruneExpiredDeepReads(now - DEEP_READ_HISTORY_RETENTION_MS)`，即删除 `expires_at < now - 7天` 的行。

   - 现状：`expires_at = createdAt + 24h`，删除点 = `createdAt + 24h + 7天 ≈ 生成后 8 天`。
   - TTL 改 7 天后：`expires_at = createdAt + 7天`，删除点 = `createdAt + 7天 + 7天 = 生成后 14 天`。

   所以 retention 常量**无需改动**，"过期后留 7 天"的语义自动适配新 TTL，不存在"刚失效就删"问题。`deepReadCacheTtlDays = 0`（永久）时 `expires_at = Long.MAX_VALUE`，`expires_at < now - 7天` 永为 false，永久项天然不被 prune（叠加 B2 的 `AND pinned = 0`，但永久项即使 pinned=false 也不会因过期被删，只是不会跨重启保留 pin 意图——这是可接受的，永久 TTL 是显式设置）。

3. `DeepReadCachePolicy.isFresh`（`HotListFetchSupport.kt:51-53`）加 pinned 参数：
   ```kotlin
   fun isFresh(expiresAt: Long, now: Long = System.currentTimeMillis(), pinned: Boolean = false): Boolean =
       pinned || expiresAt > now
   ```

4. `HotListDAO.pruneExpiredDeepReads`（`HotListDAO.kt:74`）SQL：
   ```sql
   DELETE FROM deep_read_cache WHERE expires_at < :historyCutoff AND pinned = 0
   ```

### B3. 查询：pin 永远 fresh

- `HotListRepository.toFreshDeepRead`（`HotListRepository.kt:255-258`）和 `toHistoryItem`（`:260-272`）：`isFresh` 调用传入实体的 `pinned`。pin 项 `expired` 永远 false。
- `observeDeepReadEntry`（`HotListRepository.kt:43-51`）：`includeExpired=false` 时仍展示 pin 项（即使 TTL 到了）。
- **`getFreshDeepReadByTitle`（`HotListDAO.kt:52-60`）SQL 补 pin 豁免**：现 SQL `WHERE title = :title AND expires_at >= :now`，pin 项 expires_at 过期后查不到。改为 `WHERE title = :title AND (expires_at >= :now OR pinned = 1)`。这条路径被 `materializeFreshDeepRead`（`HotListRepository.kt:163`）和 `getFreshDeepRead` 的 title fallback（`:150-151`）使用，是 RunManager `fresh()`（`:1002-1009`）的取数路径，必须覆盖 pin 豁免，否则 pin 的文章跨重启或重新进入时按 title 匹配会漏。

### B4. UI

1. **`DeepReadHistoryPage.kt`**：
   - 历史列表排序：pin 置顶（`ORDER BY pinned DESC, updated_at DESC`，DAO `observeDeepReadHistory` SQL 改）。
   - 每个 item 加 pin/unpin 按钮（书签图标）。
   - pin 项不显示"已失效"，显示"已收藏"。
   - 调用 repository 新增 `setDeepReadPinned(topicId, pinned)` 方法 → DAO 新增 `UPDATE deep_read_cache SET pinned = :pinned WHERE topic_id = :topicId`。

2. **`SettingTodayBoardPage.kt`**：加 TTL 选择项（单选或下拉）：
   - 24 小时 / 3 天 / 7 天（默认）/ 永久
   - 对应 `deepReadCacheTtlDays` = 1 / 3 / 7 / 0。

3. **`DeepReadScreen.kt`**（可选，轻量）：完成态时顶部加 pin 按钮，便于在阅读页直接收藏。若与历史页按钮逻辑重复，抽共享 composable。

4. **`HotListDAO.observeDeepReadHistory`**（`HotListDAO.kt:65-66`）：SQL 加 `ORDER BY pinned DESC, updated_at DESC`。

---

## 簇 C：文案与死代码清理

### C1. token 估算文案诚实化

**位置**：`DeepReadScreen.kt:734`（`DeepReadConfirmation`）。

现状文案：
> "每次生成约消耗 3 万 tokens。同一话题 24 小时内优先使用缓存。"

问题：3 万严重低估。真实上限：4 段 × 每段最多 32 步 tool loop + 12 源 × 2000 字摘要内联 + 规划阶段 + supervisor 2 passes，单篇可达 3-10 万 tokens。

**改为**：
> "单篇深读约消耗 3-10 万 tokens（取决于来源数量和模型多步程度）。已生成的话题在缓存有效期内不会重复计费。"

呼应簇 B：不再写死"24 小时"，改"缓存有效期内"。

**连带**：`DeepReadOpenTool.kt:63` 的 `put("cache_ttl_hours", 24)` 这个返回给 LLM 的固定字段去掉，避免与可配置 TTL 矛盾。

### C2. 死代码清理

| 项 | 处理 | 理由 |
|---|---|---|
| `DeepReadAgentRunManager.appendPrefetchedSources`（私有方法，主路径用 `appendEvidenceCards`） | **删** | 无调用 |
| `DeepReadOutput.verificationWarningMessage()`（永远返回 null） | **删** | 无消费者 |
| `DeepReadSectionStateTest` 中两个 `assertNull(...verificationWarningMessage())`（`:86`、`:104`） | **删** | 随上一项移除 |
| `DeepReadSectionStateTest` 中对应 import（`:20`） | **删** | 随上一项移除 |
| `BasicDraftNotice` + `hasBasicDraft()` + 全部 6 处引用（定义/状态变量/2 个 when 分支/1 个 if 块/composable 定义） | **删** | 簇 A 后无 BASIC 段 |
| `DeepReadSectionQuality.VERIFIED` 枚举值 | **保留** | wire 格式一部分，删了破坏序列化；未来 claim 标注会用到 |
| `DeepReadSectionQuality.BASIC` 枚举值 | **保留** | 同上 |
| `DeepReadEvidenceRegistry`（写不读） | **保留** | 未来验真白名单基础 |
| `verificationState` 字段 / `VERIFYING` 枚举 / `DeepReadProgress` 的 VERIFYING 分支 | **保留** | 删了引入状态机断层；留给未来轻量验真 |

### C3. 本地化

按 AGENTS.md 规则，用户未明确要求本地化时优先实现功能。文案直接中文硬编码，不动 `strings.xml`（与现有 `DeepReadConfirmation` 做法一致）。

---

## 测试策略

### 簇 A

- **修改** `DeepReadSectionStateTest`、`DeepReadResearchHarnessTest`：删除对降级正文的断言。
- **新增** `writeFallbackSection` links-only 行为测试：验证失败段不再标 READY，但 references 被合并。
- **新增** 门槛测试：overview 80 字、seed 60 字边界用例。
- **回归** `DeepReadBackgroundReliabilityTest`：确认 FAILED 路径（而非降级）被正确标记。

### 簇 B

- **新增** `DeepReadCachePolicyTest`：pinned 项 isFresh 永真。
- **新增** TTL 参数化测试：ttlDays=0/1/3/7 的 expiresAt 计算。
- **新增** migration 测试：v6→v7 的 pinned 列回填为 0。
- **新增** prune 测试：pinned 项不被 prune。
- **回归** `DeepReadRepositoryTest`：历史列表 pin 置顶、includeExpired 对 pin 的处理。

### 簇 C

- 验证 token 文案改动不破坏 `DeepReadConfirmation` 快照测试（如有）。
- 删除 `verificationWarningMessage` 后编译通过、相关测试通过。

---

## 风险与缓解

1. **产出率下降**（簇 A 主要风险）：门槛拉高 + 废除降级，弱模型下更多段失败。这是预期行为，但需观察实际生成成功率。缓解：门槛选 80/60 而非对齐 prompt 的 120/100，留出模型偏短余量。
2. **DB migration 失败**（簇 B）：Room schema 验证严格，`pinned` 列必须带 `NOT NULL DEFAULT 0` 且类型匹配。缓解：参照现有 `MIGRATION_4_5`（`AppDatabase.kt:395-406`）的 NOT NULL DEFAULT 范式，构建时生成 schema 快照验证。测试上需在 `app/src/androidTest/.../AppDatabaseMigrationTest.kt` 新增 v6→v7 测试方法，构造含 `deep_read_cache` 表及既有行的 v6 库，验证 migration 后 `pinned` 列存在且回填为 0（参照现有 `helper.runMigrationAndValidate(TEST_DB, startVersion, true, MIGRATION_X_Y)` 范式，`:148-153`）。
3. **TTL 变更影响既有缓存**：已存数据的 `expiresAt` 是旧 24h 算的，升级后不会自动延长。可接受——新生成内容用新 TTL，旧内容按原值过期。如需统一可加一次性回填，但非必需。
4. **文案"3-10 万 tokens"仍不准确**：这是粗估，真实值取决于来源数和模型多步程度。作为向用户透明的大致量级可接受；若要精确需在生成后统计实际 token，属后续增强。

## 非目标（明确排除）

- claim 级来源标注（timeline/corePoints/analysis 每条挂来源 ID）——大改，prompt+writer schema+model 行为+UI 四层，且面临弱模型产出率问题，留待独立 spec。
- 段落级流式（`StreamChunk` event 接通）——大改，数据流+writer 协议+渲染三层，留待独立 spec。
- VERIFYING 验真（覆盖度校验 + claim 级证据核对）——已被否决，太重；但本地覆盖度校验 `verifyCoverage`（纯本地、无 LLM）可作为未来轻量增强单独评估。

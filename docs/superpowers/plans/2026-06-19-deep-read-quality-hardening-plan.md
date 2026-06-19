# Deep Read 质量硬化 — 实现计划

- 日期：2026-06-19
- 对应 spec：`docs/superpowers/specs/2026-06-19-deep-read-quality-hardening-design.md`
- 分支：feature/council-host-orchestration-tools

## 执行原则

- 顺序严格按依赖：簇 A → 簇 C（A 依赖部分）→ 簇 C（其余）→ 簇 B DB → 簇 B 逻辑 → 簇 B UI。
- 每个 commit 末尾必须编译通过（`./gradlew assembleDebug`）。
- 每簇末尾跑相关单测（`./gradlew test --tests "*DeepRead*"`）。
- migration 相关步骤末尾额外跑 instrumented test（`./gradlew connectedDebugAndroidTest`，如环境支持；否则至少 lint）。

---

## 阶段 1 — 簇 A：废除正文降级 + 门槛拉齐

### 步骤 A1：门槛常量拉齐
- `app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadSectionWriterTools.kt:31`：`OVERVIEW_SUMMARY_MIN_CHARS = 24` → `80`
- `app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadSourcePrefetcher.kt:712`：`MIN_SEED_SOURCE_CHARS = 15` → `60`
- 验证：编译。这两个常量无下游推导依赖，纯数值改。
- 新增/修改测试：`DeepReadSectionStateTest` 或新门槛边界用例（overview 79 字判否、80 字判过）。

### 步骤 A2：改造 writeFallbackSection 为 links-only
- `DeepReadSectionWriterTools.kt:94-141`：
  - 删除 `fallbackBody(...)` 产出正文（summary/timeline/corePoints/analysis）的所有分支。
  - 保留：`mergeReadingLinks(current.references, links, ...)` 和 `extendedReading` 合并。
  - 删除：`withSectionStatus(stage, READY)` + `withSectionQuality(stage, BASIC)` 调用。
  - 删除：`markRequiredWrite()` 调用（links-only 不算有效写入）。
  - 返回值语义：仅承载 links 合并后的 output，**不改 section 状态**。
- `fallbackBody` / `cleanAssistantFallback` / `isUsefulFallbackText` / `toFallbackCorePoints` / `toFallbackTimeline` / `fallbackSentences` / `fallbackTextMax` 这些私有辅助函数：若改完后无其他调用方，一并删除（先 grep 确认无引用）。
- 验证：编译 + `DeepReadResearchHarnessTest` / `DeepReadSectionStateTest`。

### 步骤 A3：简化 tryFallbackAfterStageFailure
- `DeepReadAgentRunManager.kt:632-659`：
  - 调用 `writeFallbackSection`（现为 links-only）仅用于保留链接。
  - `fallback.statusOf(stage) == READY` 在新语义下恒 false，最终走到 `writer.markFailed(stage, ...)`。
  - 选择实现：保留 Boolean 返回（恒 false）或改为内部直接 markFailed 后返回 Unit。任选其一，spec A1.2 已确认两者行为等价（markFailed 对已 FAILED 段是 no-op）。
  - 不动调用方 `runStageSupervisorLoop` 的 timeout（`:497`）/other（`:512`）分支——它们用 recovered 判定，recovered=false 时会 markFailed，no-op 安全。
- 验证：编译 + `DeepReadBackgroundReliabilityTest`（确认 FAILED 路径而非降级）。

### 步骤 A commit 边界
- 建议：A1（常量）+ A2（writeFallbackSection）+ A3（tryFallback）合为一个 commit `feat(deep-read): abolish content fallback, tighten thresholds`。
- 若改完后 BASIC 写路径完全消失，验证 `DeepReadSectionQuality.BASIC` 仍保留枚举（spec C2 明确保留），不删。

---

## 阶段 2 — 簇 C（依赖 A 部分）：删 BasicDraftNotice

### 步骤 C1：删除 BasicDraftNotice 及全部 6 处引用
> 必须在阶段 1 之后：A 废除 BASIC 写路径后，`hasBasicDraft()` 永远 false，这些引用才成为死代码。

- `app/src/main/java/app/amber/feature/ui/pages/board/DeepReadScreen.kt`，删除：
  - `:487` `private fun DeepReadOutput.hasBasicDraft()` 扩展函数定义
  - `:193` `val hasBasicDraft = output?.hasBasicDraft() == true` 状态变量
  - `:331`、`:436` 两处 when 分支 `hasBasicDraft && !complete -> TemplateFallbackNotice(...)`
  - `:784-789` `if (output.hasBasicDraft()) { ... BasicDraftNotice(...) }` 块
  - `:848-864` `private fun BasicDraftNotice(...)` composable 定义
- 验证：编译（删除引用后无悬挂符号）。
- 注意：`hasBasicDraft` 在 `when` 里删掉分支后，检查 `when` 是否变成非穷尽（Kotlin 会对 Boolean when 报警告/错误），必要时补 `else -> {}`。

### 步骤 C commit 边界
- C1 单独 commit `refactor(deep-read): remove dead BasicDraftNotice after fallback abolition`。

---

## 阶段 3 — 簇 C（其余死代码 + 文案）

### 步骤 C2：删除 appendPrefetchedSources
- `DeepReadAgentRunManager.kt:814-847`：删除 `appendPrefetchedSources` 私有方法。
- 先 grep 确认无调用（spec review 已确认是死代码，但改动前再验一次）。
- 验证：编译。

### 步骤 C3：删除 verificationWarningMessage
- `feature/board/impl/src/main/kotlin/app/amber/feature/board/hotlist/deepread/DeepReadModels.kt:106-107`：删除 `fun DeepReadOutput.verificationWarningMessage(): String? = null`
- `app/src/test/java/app/amber/agent/data/agent/board/hotlist/DeepReadSectionStateTest.kt`：
  - `:20` 删 import `verificationWarningMessage`
  - `:86`、`:104` 删两处 `assertNull(...verificationWarningMessage())`
  - 检查对应测试方法是否还有其他断言，若只剩这一个则整个测试方法删除。
- 验证：编译 + `DeepReadSectionStateTest` 通过。

### 步骤 C4：token 文案诚实化
- `DeepReadScreen.kt:734`（`DeepReadConfirmation` 内）：
  - "每次生成约消耗 3 万 tokens。同一话题 24 小时内优先使用缓存。" 
  - → "单篇深读约消耗 3-10 万 tokens（取决于来源数量和模型多步程度）。已生成的话题在缓存有效期内不会重复计费。"
- `app/src/main/java/app/amber/core/ai/tools/DeepReadOpenTool.kt:63`：删除 `put("cache_ttl_hours", 24)` 这一行。
- 验证：编译。

### 步骤 C commit 边界
- C2+C3+C4 合为一个 commit `chore(deep-read): drop dead code, honest token estimate copy`。

---

## 阶段 4 — 簇 B DB 层

### 步骤 B1：DeepReadCacheEntity 加 pinned 列
- `app/src/main/java/app/amber/agent/data/db/entity/HotListEntities.kt:64-78`：
  ```kotlin
  @ColumnInfo("pinned")
  val pinned: Boolean = false,
  ```

### 步骤 B2：DB migration 6→7
- `app/src/main/java/app/amber/agent/data/db/AppDatabase.kt`：
  - `:122` `version = 6` → `version = 7`
  - companion object 内（MIGRATION_5_6 之后）新增：
    ```kotlin
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `deep_read_cache` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
    ```
- `app/src/main/java/app/amber/core/di/DataSourceModule.kt:125-131`：`addMigrations(...)` 调用链追加 `MIGRATION_6_7`。

### 步骤 B3：生成 schema 快照
- 构建（`./gradlew assembleDebug`）会自动生成 `app/schemas/app.amber.agent.data.db.AppDatabase/7.json`，需 git add 提交。

### 步骤 B4：migration 测试
- `app/src/androidTest/.../AppDatabaseMigrationTest.kt`：新增 v6→v7 测试方法，构造含 `deep_read_cache` 表及既有行的 v6 库，验证 migration 后 `pinned` 列存在且回填为 0。
- 参照现有 `helper.runMigrationAndValidate(TEST_DB, startVersion, true, MIGRATION_X_Y)` 范式（`:148-153`）。

### 步骤 B-DB commit 边界
- B1+B2+B3 合为一个 commit `feat(deep-read): add pinned column with migration 6->7`。
- B4 单独 commit `test(deep-read): verify pinned column migration`（若 instrumented test 环境可用）。

---

## 阶段 5 — 簇 B 逻辑层

### 步骤 B5：isFresh 加 pinned 参数
- `app/src/main/java/app/amber/feature/board/hotlist/HotListFetchSupport.kt:51-53`：
  ```kotlin
  fun isFresh(expiresAt: Long, now: Long = System.currentTimeMillis(), pinned: Boolean = false): Boolean =
      pinned || expiresAt > now
  ```
- 所有现有调用点（`HotListRepository.kt:256,271`）需补传 `pinned` 参数（从 entity 取）。
- 更新 `app/src/test/.../DeepReadCachePolicyTest.kt`：新增 pinned 项 isFresh 永真用例。

### 步骤 B6：DAO pin 豁免
- `HotListDAO.kt:52-60` `getFreshDeepReadByTitle` SQL：
  ```sql
  WHERE title = :title AND (expires_at >= :now OR pinned = 1)
  ```
- `HotListDAO.kt:74` `pruneExpiredDeepReads` SQL：
  ```sql
  DELETE FROM deep_read_cache WHERE expires_at < :historyCutoff AND pinned = 0
  ```
- `HotListDAO.kt:65-66` `observeDeepReadHistory` SQL 排序加 pin 置顶：
  ```sql
  ORDER BY pinned DESC, updated_at DESC LIMIT :limit
  ```
- 新增 DAO 方法 `setDeepReadPinned(topicId, pinned)`：
  ```kotlin
  @Query("UPDATE deep_read_cache SET pinned = :pinned WHERE topic_id = :topicId")
  suspend fun setDeepReadPinned(topicId: String, pinned: Boolean)
  ```

### 步骤 B7：saveDeepRead 加 ttlDays 参数
- `HotListRepository.kt:177-193`：
  ```kotlin
  suspend fun saveDeepRead(
      topicId: String,
      title: String,
      output: DeepReadOutput,
      now: Long = System.currentTimeMillis(),
      ttlDays: Int = DEFAULT_TTL_DAYS,
  )
  ```
  - `ttlDays == 0` → `expiresAt = Long.MAX_VALUE`
  - `ttlDays > 0` → `expiresAt = now + ttlDays * 24h`
  - companion object 加 `const val DEFAULT_TTL_DAYS = 7`。
- `TodayBoardSetting`（`BoardSettings.kt:171-190`）加字段：
  ```kotlin
  val deepReadCacheTtlDays: Int = 7,
  ```

### 步骤 B8：调用方传 ttlDays
- `DeepReadAgentRunManager.createRunContext`（`:291-381`，`:300` 已读 settings）：
  - 读 `settings.agentRuntime.todayBoard.deepReadCacheTtlDays`
  - `:341` new writer 时传入：`DeepReadSectionWriterTools(repository, topicId, topicTitle, imageCandidates, allowTitleFallback, ttlDays = settings...)`
- `DeepReadSectionWriterTools`：
  - 构造参数加 `ttlDays: Int`
  - 存为私有字段
  - `update()`（`:564-569`）调用 `repository.saveDeepRead(topicId, topicTitle, next, ttlDays = ttlDays)`
- `DeepReadWorker`（`:23` KoinComponent）：
  - 3 处 `saveDeepRead`（`:44,116,124`）前取 `get<SettingsAggregator>().settingsFlow.value.agentRuntime.todayBoard.deepReadCacheTtlDays`，传入。
  - 注意：Worker 的 `saveDeepRead` 是在 failure/retry 路径（`failureOutput`/`retryableOutput`/`idleOutput` 之后），这些 output 也要按配置 TTL 存。

### 步骤 B9：toHistoryItem / toFreshDeepRead 传 pinned
- `HotListRepository.kt:255-272`：`isFresh` 调用传 entity 的 `pinned`；pin 项 `expired` 永远 false。
- 新增 repository 方法 `setDeepReadPinned(topicId, pinned)` 包装 DAO。

### 步骤 B-逻辑 commit 边界
- B5+B6 合一个 commit（isFresh + DAO 豁免）
- B7+B8+B9 合一个 commit（TTL 参数 + 调用方 + setting 字段）

---

## 阶段 6 — 簇 B UI

### 步骤 B10：历史页 pin 按钮 + 置顶
- `app/src/main/java/app/amber/feature/ui/pages/board/DeepReadHistoryPage.kt`：
  - 每个 item 加 pin/unpin 按钮（书签图标），调用 `setDeepReadPinned`。
  - pin 项不显示"已失效"，显示"已收藏"（`:135-136` 的 `expired` 标签逻辑加 pin 判断）。
  - 列表排序由 DAO SQL 保证（B6 已改 ORDER BY pinned DESC）。

### 步骤 B11：设置页 TTL 选择
- `app/src/main/java/app/amber/feature/ui/pages/board/SettingTodayBoardPage.kt`：
  - 加 TTL 选择项（单选或下拉）：24 小时 / 3 天 / 7 天（默认）/ 永久
  - 对应 `deepReadCacheTtlDays` = 1 / 3 / 7 / 0
  - 写入 `settingsStore.update { ... deepReadCacheTtlDays = ... }`

### 步骤 B12：阅读页 pin 按钮（可选）
- `DeepReadScreen.kt`：完成态时顶部加 pin 按钮（书签图标）。
- 若与历史页按钮逻辑重复，抽共享 composable。
- 若时间紧可跳过，历史页 pin 已够用。

### 步骤 B-UI commit 边界
- B10+B11 合一个 commit `feat(deep-read): pin retention and configurable TTL in UI`。
- B12 若做则单独 commit。

---

## 阶段 7 — 全量验证

### 步骤 V1：全量编译 + 单测
- `./gradlew assembleDebug`
- `./gradlew test --tests "*DeepRead*"`
- `./gradlew lint`

### 步骤 V2：instrumented test（如环境支持）
- `./gradlew connectedDebugAndroidTest`（migration 测试在此）

### 步骤 V3：回归检查
- 既有 DeepRead 测试全绿
- 确认 `DeepReadSectionQuality.VERIFIED`/`BASIC` 枚举保留（序列化兼容）
- 确认 `DeepReadEvidenceRegistry`/`verificationState`/`VERIFYING` 保留

---

## 风险节点（执行时重点验证）

| 步骤 | 风险 | 验证 |
|---|---|---|
| A2 | 删 fallbackBody 后私有辅助函数是否还有引用 | grep 确认 |
| A3 | tryFallback 返回值改动是否破坏 runStageSupervisorLoop | 编译 + BackgroundReliabilityTest |
| C1 | 删 hasBasicDraft 后 when 分支是否非穷尽 | 编译 |
| B2 | migration SQL 是否通过 Room schema 验证 | assembleDebug 生成 schema + migration test |
| B8 | writer.update() 是否正确传 ttlDays | 单测：写入后 expiresAt 按 TTL |
| B8 | Worker settingsFlow.value 是否线程安全 | Worker 在 IO 线程，.value 同步读安全 |

## 明确排除（不在本计划内）

- claim 级来源标注（下一轮，做法 B 非阻断 + 白名单校验 + 只做 Compose 路径）
- 段落级流式（下一轮独立 spec）
- VERIFYING 验真（已否决，太重）
- 保留的脚手架不动：VERIFIED/BASIC 枚举、EvidenceRegistry、verificationState、VERIFYING 相位

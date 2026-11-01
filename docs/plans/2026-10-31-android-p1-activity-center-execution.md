# Android 选定差异补全 · P1 跨对话活动中心执行记录（2026-11-01）

> 本文件是 P1 专用记录，不修改 `2026-10-31-android-selected-parity-handoff.md`。
> 主代理已对 `04c6a3116` 做代码验收通过（§10）；设备 migration/面板实操仍待最后统一验证，P1 不能标完全完成。

## 1. 基线

- 工作分支：`codex/android-p0-ocr`
- 父提交：`b9682dd55 fix(ocr): P0 OCR 截图新鲜度、块去重、取消与脱敏返工`（主代理代码验收通过，真机仍待统一验证）
- 原 P1 提交：`d07a05f98 feat(android): P1 跨对话活动中心四态投影与已读水位`（独立验收不通过，见 §9 返工）
- 三个既有未跟踪文档保持不提交。

## 2. 状态映射（显式）

`conversationActivityKindFor(RunTerminalState)`：

| RunTerminalState | ConversationActivityKind | 理由 |
| --- | --- | --- |
| `RUNNING` | `RUNNING` | 现有运行提示 |
| `WAITING_EXTERNAL` | `RUNNING` | 仍在飞行中，沿用运行提示 |
| `RESUMABLE` | `RUNNING` | 冷启动恢复中的 in-flight，沿用运行提示 |
| `WAITING_USER` | `AWAITING_USER` | 工具批准 / 提问 |
| `OUTCOME_UNKNOWN` | `AWAITING_USER` | 用户必须确认重试或放弃；与 `ImageGenerationContinueSource` 一致 |
| `FAILED` | `FAILED` | 失败 |
| `STEP_LIMIT` | `FAILED` | 异常终态，不得映射为 COMPLETED |
| `OUTPUT_LIMIT` | `FAILED` | 同上 |
| `GUARD_STOPPED` | `FAILED` | 同上 |
| `INTERRUPTED` | `FAILED` | 进程死亡后不可恢复的终态 |
| `COMPLETED` | `COMPLETED` | 正常完成 |
| `CANCELLED` | （不投影） | 用户主动停止，不跨对话提醒 |

优先级：**等待用户 > 失败 > 完成 > 运行中**。同一对话只留最重要且最新的一条（kind rank，然后 `versionMs` 新者优先）。

`versionMs = finishedAtMs ?: updatedAtMs`。进程内 `ActiveRun` 的 version 用 `registeredAtMs`。同一 `runId` 上 **registry 覆盖 terminal**，避免 active→terminal 交接出现两条或空窗闪烁。

## 3. 已读水位与首次升级基线

不另建 run 状态库。水位落在同一 `AppDatabase` 的两张新表：

- `conversation_activity_seen`：PK `run_id`，列 `conversation_id` + `version_ms`
- `conversation_activity_baseline`：单行 `id=1`，`established_at_ms`

规则：

- `RUNNING` / `AWAITING_USER`：未被该 run 的 `versionMs` 覆盖则未读（含升级前就在等的 WAITING_USER）。
- `FAILED` / `COMPLETED`：仅当 `versionMs > baseline` 且未见过才提醒，升级时历史终态不会轰炸。
- `markActivitySeen`：DAO `@Transaction` 内先 `UPDATE ... WHERE run_id=? AND conversation_id=? AND version_ms < ? AND EXISTS (run_terminal 同归属且 COALESCE(finished_at_ms, updated_at_ms) >= versionMs)`，再 `INSERT OR IGNORE ... SELECT` 自匹配的 `run_terminal` 行，冲突后再 UPDATE 一次。
- 真实 run 不存在、conversation 错配、future version 一律拒绝，不生成任意 run 水位。registry-only RUNNING（尚未 `begin`）不能靠宽松写入绕过 owner 校验。
- 未读匹配同时校验 `runId + conversationId`；错配 conversation 的 seen 行清不掉本对话提醒。
- 同对话新 run 或同 run 更高 version 可再次提醒。
- 进程重启读同一 Room 行，已读仍有效。

collect 启动：`notices()` / `consumeWhileOpen()` 都先 `ensureActivityBaseline(now)` 再 combine。基线 `INSERT OR IGNORE`，后到的 collect 复用原时间戳。baseline 写入之后才出现的终态 `versionMs > baseline`，不会被 seed 吞掉。

打开消费：

- 点击面板：仅当 `activityNoticeNavigationTarget` 解析出合法且非当前对话的 Uuid 后，才 `rememberOpenedNotice` + `markSeen` + `navigateToChatPage`。Uuid 解析失败不清提醒。
- 目标页 `LaunchedEffect` 调 `consumeWhileResumed`（`repeatOnLifecycle(RESUMED)`）。退后台取消 collect；恢复后新开一次 `consumeWhileOpen`，按可见首帧消费。
- `consumeWhileOpen` 看该对话全部 candidate，不是只盯 min-priority 一条。已读高优先级不能饿死同对话新 run / 低优先级事件。
- 导航 pending：首帧只标点击的 runId+version；该 run 首帧若已是更新 version，记为保护身份，**自身 markSeen 引起的 Flow 重放不得把未看到的新 version 标掉**。同对话其他未读 sibling 仍消费。
- 直达打开（无 pending）：首帧标该对话全部 candidate。
- 停留期间未读新事件（新 run、同 run 高于保护身份的 version）继续 markSeen。collect 在首帧前被取消则把 pending 放回。

## 4. 生产路径（UI gate → domain/CAS → 持久化）

1. `AgentInfraModule`：`ConversationActivityCenter(registry, terminals=RunTerminalStore)`，标题仍走 `ConversationRepository`。
2. `ChatPageScaffold`：`notices(excluding = conversation.id)`；点击先 `activityNoticeNavigationTarget`，合法目标才 `rememberOpenedNotice` + `markSeen` + `navigateToChatPage`；`LaunchedEffect` 调 `consumeWhileResumed`（`RESUMED` 前台可见才 `consumeWhileOpen`）。
3. `ChatActivityPanelHost` / `ChatActivityPanelContent` 按 kind 换图标和文案（Radio / Clock / CircleAlert / CircleCheck），不再写死「正在运行」。
4. 独立 48dp `ChatActivityButton`、与产物架 / 活动岛 / 回顾互斥、时间线滚动位置均未改。
5. 空列表 / loading fallback 保留；标题查询失败回落 id 前 8 位；`CancellationException` 继续上抛。
6. `RunTerminalDAO.observeActivityCandidates()` 是全局 Flow，CANCELLED 不进投影。

## 5. Schema / migration

- `AppDatabase` 21 → **22**。
- `MIGRATION_21_22` 建 `conversation_activity_seen`（含 conversation_id 索引）和 `conversation_activity_baseline`。
- KSP 导出 `app/schemas/app.amber.agent.data.db.AppDatabase/22.json`。
- `DataSourceModule` 注册该 migration。
- 两表加入 `SyncArchiveManager.SYNC_TABLES`（备份恢复后已读水位仍在，避免恢复后轰炸）。
- `AppDatabaseMigrationTest.migration_21_22_creates_activity_watermark_tables` 已写；**androidTest 真机/模拟器未跑**。

## 6. 测试命令与结果

定点 JVM（Robolectric SDK 34）。原提交 `d07a05f98` 为 51 项全通过。返工后：

```bash
./gradlew :app:testFullDebugUnitTest --offline \
  --tests '*ConversationActivityCenterTest.review*' \
  --tests '*ConversationActivityWatermarkStoreTest.review*'
# 2 tests, 0 failures
# 日志 /tmp/amber-p1-review-regressions.log

./gradlew :app:testFullDebugUnitTest --offline \
  --tests "app.amber.feature.runtime.ConversationActivityCenterTest" \
  --tests "app.amber.feature.runtime.ConversationActivityWatermarkStoreTest" \
  --tests "app.amber.feature.runtime.RunTerminalStoreTest" \
  --tests "app.amber.agent.data.sync.SyncArchiveTableCoverageTest" \
  --tests "app.amber.feature.ui.pages.chat.ConversationActivityConsumeTest"
# 65 tests, 0 failures, 0 errors, 0 skipped
# 日志 /tmp/amber-p1-rework-tests.log
```

分类：

- `ConversationActivityCenterTest` **30**：原 24 + 主代理 `reviewOpenConversationConsumesNewRunBehindAlreadySeenHigherPriorityRun` + 错配 conversation 未读、直达首帧多 run、导航后新 run、导航后同 run 更新、退后台停止/恢复首帧。
- `ConversationActivityWatermarkStoreTest` **13**：原 8（CAS/错配 run 改为真实 begin/pause/finish fixtures，断言未降）+ 主代理 `reviewWrongConversationCannotMarkExistingRunSeen` + 缺失 run、future version、registry-only 无 begin、并发 CAS。
- `RunTerminalStoreTest` **16**：既有 P1-03 状态机，确认接口扩展未破坏。
- `SyncArchiveTableCoverageTest` **3**：schema v22 表与 SYNC_TABLES 对齐（本轮未改表形，无 v23）。
- `ConversationActivityConsumeTest` **3**：非法 Uuid / 当前对话不导航、Scaffold 接线 `consumeWhileResumed`+`activityNoticeNavigationTarget`、`RESUMED` 前台才 collect。

编译：

```bash
./gradlew :app:compileFullDebugKotlin --offline
# BUILD SUCCESSFUL
# 日志 /tmp/amber-p1-rework-compile.log
```

本地化：`python3 scripts/check_android_localization.py`。本轮新增的 `chat_activity_kind_{waiting,failed,completed}` 与更新后的 empty/button 文案六套 locale 均在。脚本仍 FAIL 的是既有缺口（ja/ko/ru 缺 `chat_shelf_kind_html` / `live_update_*` 等、zh 缺 grok oauth waiting），不是本轮引入，未顺手修补。

未修 `AntigravityOAuthSessionTest`。

## 7. 剩余真机事项（本轮不做）

统一留到选定差异补全最后一轮：

- 安装/启动/操作真机。
- Room `MIGRATION_21_22` 在真实库文件上的 migration 与 `AppDatabaseMigrationTest`。
- 跨对话：等待 / 失败 / 完成 / 运行按钮与面板、产物架/活动岛/回顾互斥、定位与时间线滚动。
- 冷启动后已读仍有效、升级不轰炸、打开目标只清对应提醒、当前打开对话新事件已见。

## 8. 本轮文件

- `ConversationActivityCenter.kt` / `RunTerminalStore.kt` / `RunTerminalDAO.kt`
- `ConversationActivitySeenEntity.kt` / `ConversationActivityBaselineEntity.kt`
- `AppDatabase.kt` + `MIGRATION_21_22` + schema `22.json`
- `AgentInfraModule.kt` / `DataSourceModule.kt` / `SyncArchiveManager.kt`
- `ChatPageScaffold.kt` / `ChatPagePanels.kt` / `ChatTopBarPanels.kt` / `ConversationActivityConsume.kt`
- 六套 `strings.xml`
- `ConversationActivityCenterTest.kt` / `ConversationActivityWatermarkStoreTest.kt` / `ConversationActivityConsumeTest.kt`
- `AppDatabaseMigrationTest.kt`（androidTest，真机未跑）

## 9. 独立验收返工（相对 `d07a05f98`）

主代理独立复跑原 51 项全通过后补了两条真实回归，均失败。本轮只返工 P1，不进 P2，不操作真机。两条 review 断言保留未降。

缺陷与修复：

1. `consumeWhileOpen` 原先对全部 candidate 取 min-priority 且只盯一条，已读 waiting@50 会挡住同对话新 done@90；首帧 identity 相同直接 return。现改为消费当前对话全部 pending run 版本；导航保护只跳过「点击 run 的首帧更新 version」，不能靠单纯 `.filter unread` 让 seen 重放清掉该保护身份。
2. `markActivitySeen` 原先不校验 run 归属，错 conversation + 真实 runId 仍写 seen，`isConversationActivityUnread` 只按 runId 误清其他对话。现于 DAO 用 SQL 条件 UPDATE/INSERT + `@Transaction` 校验 `run_id + conversation_id` 及 version 上界；缺失/错配/future 拒绝；未读匹配也按 conversation。旧 CAS/错配测试改为真实 begin/finish fixtures。
3. `ChatPageScaffold` 原先裸 `LaunchedEffect` 退后台仍 collect。现 `repeatOnLifecycle(RESUMED)`；点击 markSeen 只在合法导航目标下调用。

schema 仍为 v22，未改表形，无新 migration。三个未跟踪用户文档未提交。未 push / 未开 PR。

## 10. 主代理代码验收（`04c6a3116`）

主代理已独立核对多 run 消费、DAO 事务归属校验与 version 上界、`RESUMED` lifecycle 和合法导航，并复跑：

```bash
./gradlew :app:testFullDebugUnitTest --offline \
  --tests '*ConversationActivity*Test' \
  --tests '*RunTerminalStoreTest' \
  --tests '*SyncArchiveTableCoverageTest'
# 65 tests / 0 failures / 0 errors / 0 skipped
# 日志 /tmp/amber-p1-rereview.log
```

**代码验收通过。** P1 不能标完全完成：设备安装/启动、Room `MIGRATION_21_22` 实库 migration、跨对话面板实操仍待选定差异补全最后一轮统一验证（见 §7）。

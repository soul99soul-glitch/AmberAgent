# Android 选定差异补全 · P2 手机自控 per-App 成功路径记忆执行记录（2026-11-01）

> 本文件是 P2 per-App 成功路径记忆专用记录，不修改 `2026-10-31-android-selected-parity-handoff.md`。
> 本轮只做 handoff §8。视频 `33deefa7d` 代码验收已记入竖屏视频记录 §9。不安装/启动/操作真机。
> 真机「同 App 新快照辅助、接管后不重复动作、设置清除」仍待最后统一验证，不能标完全完成。

## 1. 基线

- 工作分支：`codex/android-p0-ocr`
- 首轮提交：`a90718ae6 feat(android): P2 手机自控 per-App 成功路径记忆`（独立代码验收不通过：主代理两条 review 回归失败，测试留在工作区未提交）
- 本返工父提交：`a90718ae6`
- 三个既有未跟踪文档保持不提交：
  - `docs/plans/2026-10-26-chat-history-performance-phase2.md`
  - `docs/plans/2026-10-31-android-selected-parity-handoff.md`
  - `docs/plans/2026-10-31-ios-android-feature-diff.md`
- 未 `reset` / `clean` / `rebase`，未 push / 未开 PR。
- 无新 Gradle 依赖，无第二套 run 数据库，无 Room 迁移。
- 未删/降主代理两条 review 断言。

## 2. 数据 schema / 范围 / 容量

落盘文件：`filesDir/jev/per_app_success_paths.json`（`FilePerAppSuccessPathStore`，同目录 tmp+rename；rename/写失败保留原文件，禁止直接覆写原文件）。

```json
{
  "version": 1,
  "epoch": 1,
  "packages": {
    "com.example.app": [
      {
        "package_name": "com.example.app",
        "goal_fingerprint": "hex32",
        "goal_hint": "open the draft",
        "recorded_at_ms": 0,
        "steps": [
          {"kind": "click", "label": "Open draft", "semantic_id": "id/open"}
        ]
      }
    ]
  }
}
```

只保存可重新解析的语义：

| 字段 | 含义 |
| --- | --- |
| `kind` | `click` / `type` / `scroll_forward` / `scroll_backward` |
| `label` | 截断后的目标文案 |
| `semantic_id` | 稳定 resource-id（`pkg:id/name` 或 `id/name`） |
| `goal_fingerprint` | 非敏感目标的 SHA-256 前 32 hex；敏感目标留空 |
| `goal_hint` | 截断后的非敏感目标摘要 |

明确不落盘：屏幕坐标、`ScreenNode.ref`、snapshot id、observation id、TYPE 原文、`editableValue`、密码/OTP/支付/剪贴板原文。未知或敏感语义整步丢弃。敏感过滤是保守闸门，不是完整密钥检测。

`looksSensitive` 统一用于 `stepFrom` / `sanitizeStep` / `buildPath` / `readDisk`：关键词、`[password]`、整段 4–8 位 OTP、连续 ≥8 位数字、空格/连字符分隔的银行卡数字。TYPE/可编辑节点若 `typedValue` 或 `editableValue` 与 label 相等或互相包含，只保留可信 `semantic_id` 并去掉 label；没有稳定 id 则整步不存。`goal_hint` / fingerprint 在任务标敏感、goal 本身敏感、或 goal 含输入秘密时留空。旧磁盘记录在 read 时按同一规则丢掉敏感 step，并在可写时原子写回清洗结果。

容量上限（`PerAppSuccessPathLimits.DEFAULT`）：

| 项 | 上限 |
| --- | --- |
| package 数 | 24 |
| 每 package 路径 | 6（新的在前） |
| 每路径步骤 | 10 |
| 单文本 | 80 字符 |
| 文件 | 64 KiB |

损坏 JSON：尽量保留 `epoch`，packages 回空；非法 package / 坏 step 跳过。`clear()` 成功才把 `epoch+1` 与空 `packages` 原子替换；写失败返回 `Failed`，epoch 与原文件不变。

## 3. 生产读写 / 清除图

```
设置页 SettingAgentExecutionPage
    确认对话框 → clearPerAppSuccessPaths(store)  // 唯一用户清除入口
        成功：关确认并刷新 hasPaths
        失败：关确认、显示可重试错误；取消仍只关对话框
        │
        ▼
FilePerAppSuccessPathStore  (Koin single, filesDir/jev/per_app_success_paths.json)
        clear(): Cleared(epoch) | Failed(reason)
        ▲
        │ pathsFor(package) 每步新快照读取
        │ recordSuccess(runEpoch, path) 仅 completed ∧ dispatched>0
        │
JevScreenGoalRunner.run
    开始时记下 runEpoch
    candidates(当前树) → PerAppSuccessPathPlanner.guide(identityOf=kind+ref+text)
        前台 package 一致 ∧ 全新 snapshot ∧ 当前树唯一 enabled 目标
        只对原候选多重集做纯置换；缺 kind 或同 ref 多 TYPE 文本则保持原序 / hint null
        旧 ref 不复用、不直接点击
    performScreenAction 仍走 controller
        stale_snapshot / takeover / resume / tryRecordSideEffectDispatch 原权威
    finish("completed") 且至少一步 ok dispatch → sanitizer.buildPath → recordSuccess(runEpoch)
    verification-only（0 动作）、handback / cancel / timeout / unknown /
    steps_exhausted / shadow / dry-run / Jev 猜测  → 不写
```

复用失败（页面偏离、目标歧义、验证失败）回退原候选序。历史不能触发点击。

清除与 inflight：run 开始捕获 epoch；`recordSuccess` 读盘 CAS。成功 `clear()` 后旧 epoch 回写返回 false，后续 `pathsFor` 为空。失败 `clear()` 不抬 epoch，inflight 仍可按旧 epoch 写入。

## 4. 成功写入依据

`JevScreenGoalRunner.finish` 同时满足才写：

1. `status == "completed"`（PHONE_VERIFICATION applicable ∧ verified ≥ 阈值 ∧ 完成时 fresh snapshot id+package 一致）
2. `dispatched > 0`（verification-only 不凭空造路径）
3. 非 `dryRun`
4. 至少一步可持久化语义（敏感/未知已丢）
5. `recordSuccess(runEpoch)` epoch 仍匹配

不写：Jev 猜测、仅 dispatched、`steps_exhausted`、handback、cancel、timeout/`budget_exhausted`、`outcome_unknown`、shadow、接管后未重新验证的完成路径（完成路径本身要求 fresh capture）。

## 5. 关键文件

生产：

- `app/src/main/java/app/amber/core/jev/PerAppSuccessPath.kt`（schema、sanitizer、planner）
- `app/src/main/java/app/amber/core/jev/PerAppSuccessPathStore.kt`（文件仓库 + `clearPerAppSuccessPaths`）
- `app/src/main/java/app/amber/core/jev/JevScreenGoalRunner.kt`（读候选 / 写成功）
- `app/src/main/java/app/amber/core/di/JevModule.kt`
- `app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentExecutionPage.kt`
- 六套 `setting_agent_screen_experience_*`

测试：

- `PerAppSuccessPathStoreTest`（隔离 / 重开 / 容量 / 损坏 / 磁盘无坐标 ref 敏感原文 / 清除+双实例 inflight / 裸卡号 / 分隔卡号 / 输入镜像 / readDisk 清洗 / 写失败 / rename 失败 / Failed 走重试）
- `PerAppSuccessPathPlannerTest`（新 snapshot 唯一 fresh ref、歧义、偏离、跨 package、不发明击、同 ref 多 kind 纯置换、多 TYPE 文本、缺 kind）
- `PerAppSuccessPathRunnerMemoryTest`（生产 runner 读写、失败状态不写、TYPE 不记值、歧义不派发记忆目标、stale 拒绝、side-effect 去重、设置 clear 挡回写、scroll 方向、多 typed text）

## 6. 测试命令与结果

```bash
./gradlew :app:testFullDebugUnitTest --offline \
  --tests 'app.amber.core.jev.PerAppSuccessPathStoreTest' \
  --tests 'app.amber.core.jev.PerAppSuccessPathPlannerTest' \
  --tests 'app.amber.core.jev.PerAppSuccessPathRunnerMemoryTest' \
  --tests 'app.amber.core.jev.JevScreenGoalRunnerTest'
# 返工：/tmp/amber-perapp-rework-tests.log  BUILD SUCCESSFUL
# Store 13 + Planner 10 + RunnerMemory 10 + JevScreenGoalRunner 32 = 65, 0 fail

./gradlew :core:jev:testDebugUnitTest :app:testFullDebugUnitTest --offline \
  --tests 'app.amber.core.automation.PhoneAutomationTakeoverControllerTest' \
  --tests 'app.amber.agent.di.KoinGraphVerifyTest' \
  --tests 'app.amber.core.automation.ScreenOcrFallbackTest'
# 返工：/tmp/amber-perapp-rework-jev-reg.log  BUILD SUCCESSFUL
# core:jev 71; takeover 5; KoinGraph 1; ScreenOcrFallback 21; 0 fail

./gradlew :app:compileFullDebugKotlin --offline
# /tmp/amber-perapp-rework-compile.log  BUILD SUCCESSFUL
```

本地化：`python3 scripts/check_android_localization.py`（`/tmp/amber-perapp-rework-l10n.log`）。`setting_agent_screen_experience_*` 现 7 个 key（含 `clear_failed_title` / `clear_failed`）在 `values` / `zh` / `zh-rTW` / `ja` / `ko-rKR` / `ru` 均在。脚本仍 FAIL 的是既有缺口（ja/ko/ru 缺 `chat_shelf_kind_html` / `live_update_*` 等、zh/zh-rTW 缺 grok oauth waiting、`IntelligentUiStudyView` 内联中文），不是本轮引入，未顺手修补。

未跑 `:app:assembleFullDebug`，未安装真机（本轮 Paseo 明确禁止）。

## 7. 最终真机待验点

本轮不安装、不启动、不操作真机。统一 APK 时至少验：

1. **同 App 新快照**：已验收成功的应用，下一次 `screen_run_goal` 只把当前树唯一目标提前，不按旧坐标/旧 ref 点击。
2. **跨 App**：A 的经验不出现在 B。
3. **歧义 / 偏页**：两个同名按钮或页面已变时回退原规划，不强行重放。
4. **接管**：点「我来操作」后不重放已派发动作；继续后要新快照。
5. **设置清除**：执行页清除成功经验后，后续任务不再读旧路径；清除时若有进行中的成功收尾，旧经验不得写回。清除失败必须显示可重试错误，不能假装已清。
6. **敏感**：密码/验证码/支付类控件成功也不该留下原文；裸卡号、分隔卡号、输入镜像 label 不应出现在磁盘。

P0 OCR、P1 水位、P2 Jev、P2 视频本轮未改行为。

## 8. 停等验收

P2 per-App 成功路径记忆返工完成（独立提交），停等主代理代码验收。未通过继续返工。未获批准不启动真机。本项不能标完全完成。

## 9. 首轮验收失败后的返工（相对 a90718ae6）

主代理新增两条 review 回归原先 2/2 失败（`/tmp/amber-perapp-review-regressions.log`），断言保留：

1. `reviewStandaloneCardNumberCannotBecomePersistableStep`：CLICK `label=6222021234567890` / `viewId=id/item` 不得成为可持久化 step。
2. `reviewGuidancePreservesBothScrollDirectionsAndPromotesMatchingKind`：同 ref 的 scroll forward/back 必须保留并提升匹配 kind。

修复：

- 敏感分类并入 `looksSensitive`；TYPE 镜像输入去 label / 无稳定 id 整步丢；`goal_hint` 不存输入秘密；readDisk 同规则清洗并可写回。
- `guide` 改为 `PerAppCandidateIdentity(kind, ref, text)`；重排是原候选多重集的纯置换；缺 kind 或同 ref 多 TYPE 文本退回原列表。Runner 接同一 identity。
- `clear()` 返回 `Cleared` / `Failed`；同目录 rename，失败不覆写原文件、不抬 epoch。设置页失败显示可重试错误，取消仍关闭确认。

本轮不操作真机。

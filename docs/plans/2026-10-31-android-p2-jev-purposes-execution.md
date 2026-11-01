# Android 选定差异补全 · P2 Jev 用途补齐与手机用途拆分执行记录（2026-11-01）

> 本文件是 P2 Jev 专用记录，不修改 `2026-10-31-android-selected-parity-handoff.md`。
> 主代理已对 `b42ea9c2a` 做代码验收通过（§9）；设备/migration/面板仍待最后统一验证，P2 Jev 不能标完全完成。

## 1. 基线

- 工作分支：`codex/android-p0-ocr`
- 父提交：`04c6a3116 fix(android): P1 活动中心多run消费、水位归属与前台可见`（主代理代码验收通过，见 P1 记录 §10；P1 不能标完全完成）
- 三个既有未跟踪文档保持不提交：
  - `docs/plans/2026-10-26-chat-history-performance-phase2.md`
  - `docs/plans/2026-10-31-android-selected-parity-handoff.md`
  - `docs/plans/2026-10-31-ios-android-feature-diff.md`
- 未 `reset` / `clean` / `rebase`，未 push / 未开 PR。

## 2. 可见用途 13 个

`JevPurpose.entries` 最终 13 项，`VISIBLE_COUNT = 13`，枚举里没有 `SCREEN_AUTOMATION`：

| # | Purpose | requiredScopes | 生产消费方 |
| --- | --- | --- | --- |
| 1 | TOOL_DISCOVERY | TOOL_METADATA, TASK_TEXT | 既有 |
| 2 | MEMORY_RECALL | PERSONAL_MEMORY, TASK_TEXT | 既有 |
| 3 | CONTEXT_SELECTION | TOOL_OUTPUT, TASK_TEXT | 既有 |
| 4 | MODEL_ROUTING | TASK_TEXT, TOOL_METADATA | 既有 |
| 5 | WEB_AUTOMATION | WEB_CONTENT, TASK_TEXT | 既有 |
| 6 | PHONE_VERIFICATION | SCREEN_CONTENT, TASK_TEXT | `JevScreenGoalRunner` done 核验 |
| 7 | PHONE_ACTIONS | SCREEN_CONTENT, TASK_TEXT | `JevScreenGoalRunner` 下一步动作 |
| 8 | TOOL_RESULT_RETENTION | TOOL_OUTPUT, TASK_TEXT | 既有 |
| 9 | AUTO_APPROVAL_GATE | TOOL_METADATA, TASK_TEXT | 既有 |
| 10 | APPROVAL_TRIAGE | TOOL_METADATA, TASK_TEXT | `JevApprovalTriage` ← `ApprovalBatchPlanner` |
| 11 | COMPLETION_CHECK | TASK_TEXT, TOOL_METADATA | 既有 |
| 12 | SUBAGENT_INTENT | TOOL_METADATA, TASK_TEXT | `JevSubagentIntent` ← `SubAgentManager.start` |
| 13 | SCRIPT_JUDGMENT | TOOL_OUTPUT | `JevScriptJudgment` ← `DefaultRunKernel` executeBatch 之后 |

`Capability.SCREEN_AUTOMATION` 仍是工具能力（`screen_run_goal` 等），不是 Jev 用途，未改。

`requiredScopes` 仍是运行时 `JevDecisionCoordinator.decide` 与设置页 `JevPurpose.unavailableReason` 的单一可用性来源。

## 3. 生产调用图

```
设置 UI  JevPurpose.entries (13)
    │  unavailableReason ← purpose.requiredScopes − dataScopes
    ▼
AgentPrefs.readFrom
    decodeJsonOrNull<JevSetting>() → JsonInstant.decodeFromString
    JevPurposeModesSerializer 读 SCREEN_AUTOMATION 字符串
    migrateJevPurposeModes：显式 PHONE_* 优先，缺省才填 legacy
    encode 永不写 SCREEN_AUTOMATION

JevRuntime.configFor(purpose)     OFF → null（零开销）
    │
    ├─ SUBAGENT_INTENT
    │     SubAgentValidator.resolveDefinition
    │     → SubAgentIntentAlignment.assess
    │     → JevSubagentIntent（app；feature/subagent 无 core:jev 依赖）
    │     OFF/失败/timeout/无效值：NotJudged，spawn 继续（fail-open）
    │     ACTIVE 偏离：errorPayload("intent_misaligned")，不改 allowlist
    │     不外发 systemPrompt
    │
    ├─ APPROVAL_TRIAGE   （独立于 AUTO_APPROVAL_GATE）
    │     ApprovalBatchPlanner.planFresh / planResume
    │     先 gate 再 triage；只维持或收紧
    │     DENY 建议 vs 非 DENY 原决定 → ASK（不代替用户批准）
    │     ASK / 高风险不能变成 ALLOW
    │     ChatApprovalActions 批量批准 / 设置续跑拒绝 triage metadata
    │
    ├─ SCRIPT_JUDGMENT
    │     DefaultRunKernel：toolDispatcher.executeBatch 之后 annotate
    │     只评估 terminal_execute / terminal_session_exec / terminal_job_start
    │     已有输出截成 400+200；只写 metadata，不改 output 文本
    │     消费方：JevScriptJudgment.noticeFor → ChatMessageTools 已执行回执
    │     中性展示 ok / failed / needs_attention；不改 AgentToolStatus、不重试
    │     OFF/SHADOW：不写 metadata，noticeFor=null，用户/主模型可见结果不变
    │
    └─ PHONE_VERIFICATION then PHONE_ACTIONS
          JevScreenGoalRunner.run 入口按两用途独立授权，不再要求 actionsConfig 非 null
          verification 有 scopes 且 actions OFF：只读核验路径，零 PHONE_ACTIONS decide/派发
          verification：Noul done，state 无 candidates/history
          ACTIVE + done≥threshold + 同 id 新鲜快照 → completed；verification-only 不自动回 host
          ACTIVE 失败/timeout/Skipped：fail-closed handback，不派发
          SHADOW verification：真实 decide，不 complete；actions OFF 时 shadow_trace
          actions：Choice(handback+aN) + safe_aN Noul；Box 不出现
          canExecute = PHONE_ACTIONS ACTIVE + ACTION_SCOPES（不因 verification 开启点击/回 host）
          截图默认关；已授权用途含 SCREEN_SCREENSHOTS 才捕获一次，两路共用
          stale_snapshot / 唯一目标 / enabled+visible / 审批 / 接管 /
          动作后验收 / 副作用去重仍由 AccessibilityController 与
          PhoneAutomationTakeoverController 掌权
```

DI：`JevModule` 绑定 `JevApprovalTriage` / `JevSubagentIntent` / `JevScriptJudgment`；`GenerationModule` 注入 kernel；`AgentRuntimeModule` 把 `SubAgentIntentAlignment` 注入 `SubAgentManager`。

## 4. 设置迁移

真实持久化路径：`AgentPrefs.readFrom` → `decodeJsonOrNull<JevSetting>()` → `JsonInstant.decodeFromString`（`ignoreUnknownKeys` / `encodeDefaults` / `isLenient` / `allowTrailingComma`）。

自定义 `JevPurposeModesSerializer`：

1. JSON object 逐 key 读取；未知 key 丢弃，不让整份 JevSetting 解码失败。
2. `SCREEN_AUTOMATION` 不进枚举，只作为 legacy 字符串。
3. `migrateJevPurposeModes` 共存优先级：
   - 显式 `PHONE_VERIFICATION` / `PHONE_ACTIONS` 赢。
   - 缺省的手机 key 才用 legacy 值填充（含显式 `OFF`）。
   - 旧配置没有该 key（旧 OFF 存成缺省）→ legacy=null → 两个手机用途保持 OFF。
4. encode 只写可见 enum 名，永不写 `SCREEN_AUTOMATION`。
5. 最终可见 13 个，不能留下第 14 个伪完成用途。

`JevSetting.enabled=false` 时 `modeFor` 仍全部 OFF。整份 JSON 损坏时 `decodeJsonOrNull` 仍回落空 `JevSetting()`；仅删除 enum / 旧 key 不再触发这条路径。

`JevPolicy.CURRENT_POLICY_VERSION` 3 → 4（参与缓存键）。手机预算共享一个 bucket（`PHONE_VERIFICATION|PHONE_ACTIONS` → `PHONE_ACTIONS`），上限仍 150，拆分不会把天花板加倍。别名 `SCREEN_AUTOMATION_MAX_REQUESTS` 保留给旧测试名。

## 5. 关键文件

新增：

- `core/jev/.../JevPurposeModesSerializer` + `migrateJevPurposeModes`（`JevModels.kt`）
- `core/jev/src/test/.../JevPurposeModesTest.kt`
- `core/settings/src/test/.../JevSettingPersistenceTest.kt`（真实 `decodeJsonOrNull` 路径）
- `feature/subagent/.../SubAgentIntentAlignment.kt`
- `app/.../jev/JevSubagentIntent.kt`
- `app/.../jev/JevApprovalTriage.kt`
- `app/.../jev/JevScriptJudgment.kt`
- `app/src/test/.../JevSubagentIntentTest.kt`
- `app/src/test/.../JevApprovalTriageTest.kt`
- `app/src/test/.../JevScriptJudgmentTest.kt`

接线：

- `JevScreenGoalRunner.kt` / `JevPolicy.kt` / `JevBudget.kt`
- `ApprovalBatchPlanner.kt` / `DefaultRunKernel.kt` / `ChatApprovalActions.kt`
- `SubAgentManager.kt` / `JevModule.kt` / `GenerationModule.kt` / `AgentRuntimeModule.kt`
- `SettingJevPage.kt` / `JevStatusTool.kt` / `ChatMessageTools.kt`
- 六套 `strings.xml`（替换 `setting_jev_purpose_screen_automation*`，新增 triage/intent/script 与审批卡文案）

测试同步：`JevScreenGoalRunnerTest`、`DefaultRunKernelTest`、`ChatApprovalActionsTest`、`JevStatusToolTest`、`ScreenOcrFallbackTest`、`PhoneAutomationTakeoverControllerTest`、`JevScreenDeviceTest`、`JevBudgetTest`、`SubAgentThreadGraphIntegrationTest`。

无新依赖。

## 6. 测试命令与结果

首轮 `1a499d424` 代码验收未通过（SCRIPT_JUDGMENT 无 reader、verification 绑死 actions、无 SubAgentManager.start 集成用例）。返工后重跑：

```bash
./gradlew :core:jev:testDebugUnitTest --offline --rerun-tasks
# 6 suites / 71 tests / 0 failures / 0 errors / 0 skipped
# /tmp/amber-p2-rework-core-jev.log  BUILD SUCCESSFUL

./gradlew :app:testFullDebugUnitTest --offline \
  --tests 'app.amber.core.jev.JevScreenGoalRunnerTest' \
  --tests 'app.amber.core.jev.JevApprovalTriageTest' \
  --tests 'app.amber.core.jev.JevSubagentIntentTest' \
  --tests 'app.amber.core.jev.JevScriptJudgmentTest' \
  --tests 'app.amber.core.jev.JevAutoApprovalGateTest' \
  --tests 'app.amber.core.ai.tools.JevStatusToolTest' \
  --tests 'app.amber.core.service.ChatApprovalActionsTest' \
  --tests 'app.amber.core.ai.DefaultRunKernelTest' \
  --tests 'app.amber.core.automation.ScreenOcrFallbackTest' \
  --tests 'app.amber.core.automation.PhoneAutomationTakeoverControllerTest' \
  --tests 'app.amber.core.jev.baseline.JevScreenCorpusBaselineTest' \
  --tests 'app.amber.feature.runtime.ExecutionPolicyCoveragePinTest' \
  --tests 'app.amber.feature.subagent.SubAgentThreadGraphIntegrationTest'
# 13 suites / 194 tests / 0 failures / 0 errors / 0 skipped
# /tmp/amber-p2-rework-app.log  BUILD SUCCESSFUL

./gradlew :app:compileFullDebugKotlin --offline
# /tmp/amber-p2-rework-compile.log  BUILD SUCCESSFUL
```

消费路径（本轮闭合）：

- SCRIPT_JUDGMENT：`DefaultRunKernel.executeBatch` → `annotate` → 持久化 tool.metadata → `JevScriptJudgment.noticeFor` → `ChatMessageTools` 回执中性文案。kernel 测试证明 ACTIVE 可见/被消费，OFF/SHADOW noticeFor=null 且原 output/错误状态保留；`toolStatusFromMessagePart` 不因 verdict=ok 把 FAILED 改成成功。
- PHONE_VERIFICATION：`JevScreenGoalRunner.run` 在 actions OFF 时仍走 PHONE_VERIFICATION decide。V ACTIVE/A OFF：done→completed 且 `verification_only_keep_foreground`（零派发、零回 host）；notdone→`goal_not_verified`；HTTP 500 仍 2 次后 fail-closed；缺 scope 零请求；共享预算耗尽 handback；SHADOW 真实 decide 后 `shadow_trace` 不 complete。`canExecute()` 仍为 false。替换原 `actionsOffDisablesRunnerEvenIfVerificationActive`：保留零 actions 派发，按独立配置修正为 verification 可发自己的请求。
- SUBAGENT_INTENT：`SubAgentThreadGraphIntegrationTest.manager().start` 真实 explorer catalog。OFF/SHADOW spawn 且 allowlist 仍是 parent∩catalog（`tools_list`）；ACTIVE misaligned → `intent_misaligned` 不 spawn；fail-open spawn；invalid catalog / 空 allowlist 不调用 alignment、不 spawn。

既有安全断言未降：triage 只收紧、AUTO_APPROVAL_GATE、stale/package/takeover/configuration_changed、`failedDecisionFallsBackWithoutDispatch` 仍期望 2 次 HTTP。

本地化：新增 `chat_tool_jev_script_judgment_{ok,failed,needs_attention}` 六套 locale 均在。`python3 scripts/check_android_localization.py` 仍 FAIL 的是既有缺口（ja/ko/ru 缺 `chat_shelf_kind_html` / `live_update_*`、zh 缺 grok oauth waiting、`IntelligentUiStudyView` 内联中文），不是本轮引入。

首轮记录（保留备查）：

## 6a. 首轮测试命令与结果（`1a499d424`）

日志均在 `/tmp`。

```bash
./gradlew :core:jev:testDebugUnitTest --offline
# 6 suites / 71 tests / 0 failures / 0 errors / 0 skipped
# /tmp/amber-p2-core-jev.log  BUILD SUCCESSFUL

./gradlew :core:settings:testDebugUnitTest \
  --tests 'app.amber.core.settings.prefs.JevSettingPersistenceTest'
# 6 tests / 0 failures / 0 errors / 0 skipped
# /tmp/amber-p2-settings-persist.log  BUILD SUCCESSFUL
# 首次 --offline 缺 lifecycle-common-jvm-2.9.4 缓存，改为在线后通过

./gradlew :app:testFullDebugUnitTest --offline \
  --tests 'app.amber.core.jev.JevScreenGoalRunnerTest' \
  --tests 'app.amber.core.jev.JevApprovalTriageTest' \
  --tests 'app.amber.core.jev.JevSubagentIntentTest' \
  --tests 'app.amber.core.jev.JevScriptJudgmentTest' \
  --tests 'app.amber.core.jev.JevAutoApprovalGateTest' \
  --tests 'app.amber.core.ai.tools.JevStatusToolTest' \
  --tests 'app.amber.core.service.ChatApprovalActionsTest' \
  --tests 'app.amber.core.ai.DefaultRunKernelTest' \
  --tests 'app.amber.core.automation.ScreenOcrFallbackTest' \
  --tests 'app.amber.core.automation.PhoneAutomationTakeoverControllerTest' \
  --tests 'app.amber.core.jev.baseline.JevScreenCorpusBaselineTest' \
  --tests 'app.amber.feature.runtime.ExecutionPolicyCoveragePinTest'
# 12 suites / 147 tests / 0 failures / 0 errors / 0 skipped
# /tmp/amber-p2-app-tests.log  BUILD SUCCESSFUL
# 中间一次 failedDecisionFallsBackWithoutDispatch 期望 2 次 HTTP、实为 3：
# ACTIVE verification HTTP 500 重试后再走 actions。已改为 ACTIVE verification
# 失败 fail-closed handback（与测试及原 SCREEN_AUTOMATION 失败语义一致）。

./gradlew :app:compileFullDebugKotlin --offline
# /tmp/amber-p2-compile.log  BUILD SUCCESSFUL
```

覆盖：

- 新用途各 OFF / SHADOW / ACTIVE + 真实 `decide()` 路径
- 旧 `SCREEN_AUTOMATION` ACTIVE/OFF/缺省/与 PHONE_* 共存、未知 key 不炸整份设置
- scope 不足、预算耗尽、失败/timeout/无效值回落
- triage：`appliedPosture` 穷举任意 original×suggested；`suggestedPosture` 多组 choice×risk；任意有效答案不能把 ASK 放成 ALLOW，高风险 ALLOW 升为 ASK，DENY 建议对非 DENY 原决定落 ASK
- 手机 verification/actions 独立配置、请求 purpose 分离、共享预算、截图默认关
- AUTO_APPROVAL_GATE 既有测试仍过；batch-approve 同时拒绝 gate 与 triage metadata

本地化：`python3 scripts/check_android_localization.py`（`/tmp/amber-p2-l10n.log`）。本轮 11 个新/替换 key 在 `values` / `zh` / `zh-rTW` / `ja` / `ko-rKR` / `ru` 均在，无残留 `setting_jev_purpose_screen_automation*`。脚本仍 FAIL 的是既有缺口（ja/ko/ru 缺 `chat_shelf_kind_html` / `live_update_*` 等、zh 缺 grok oauth waiting、`IntelligentUiStudyView` 内联中文），与 P1 记录相同，不是本轮引入，未顺手修补。

## 7. 剩余风险（代码验收范围外）

- P0 OCR / P1 活动中心 / 本轮 P2 Jev **均未做真机安装、启动、面板或 Jev 实操**；`JevScreenDeviceTest` 已改成两个手机用途 ACTIVE，设备上未跑。
- Room `MIGRATION_21_22` 实库、跨对话活动面板仍待最后统一验证。P1 不能标完全完成。
- SCRIPT_JUDGMENT / PHONE_VERIFICATION / SUBAGENT_INTENT 的生产消费路径已有 kernel / runner / `SubAgentManager.start` 测试；未做 Compose UI 截图或真机回执展示。
- 手机截图仍依赖宿主已有 MediaProjection；本轮不拉起授权 Activity。
- 不做 P2 竖屏视频、不做 per-App 成功路径记忆。

## 8. 停等验收

P2 Jev 返工完成（独立提交），停等主代理重验。未通过继续返工。本轮不进入视频 / perApp，不操作真机。

## 9. 主代理代码验收（`b42ea9c2a`）

主代理已独立核对 SCRIPT receipt reader+UI、verification-only 零动作与禁止 autoReturn、manager 真实 catalog 集成路径，并复跑：

```bash
./gradlew :core:jev:testDebugUnitTest :app:testFullDebugUnitTest \
  --tests '*Jev*Test' \
  --tests '*DefaultRunKernelTest' \
  --tests '*ChatApprovalActionsTest' \
  --tests '*ScreenOcrFallbackTest' \
  --tests '*PhoneAutomationTakeoverControllerTest' \
  --tests '*ExecutionPolicyCoveragePinTest' \
  --tests '*SubAgentThreadGraphIntegrationTest'
# core 71 + app 278 = 349 tests / 0 failures / 0 errors / 0 skipped
# 日志 /tmp/amber-p2-rereview.log
```

**代码验收通过。** P2 Jev 不能标完全完成：设备安装/启动、Jev 实操、migration/面板仍待选定差异补全最后一轮统一验证（见 §7）。竖屏视频与 per-App 记忆不在本文件范围。

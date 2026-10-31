# Phase 5：Android UI/交互一致性与可访问性验收报告

- **日期**：2026-10-31
- **工作项 ID**：`hub-work:67b7707fce20c939d7629a0f582b51977e2e7be5`
- **目标工作树**：`/Users/arquiel/.synara/worktrees/e5e767e345ee`
- **分支 / 基线**：`synara/amber-android-baseline-validation` @ `a2a159aa1`（保留 P1–P4 全部未提交改动，未做任何 `git commit` 或 `git push`）

---

## 1. 阶段目标与覆盖范围

1. **A5.1 Compose UI 与可访问性矩阵（Font Scale / 深浅色 / 窄宽屏 / 长文本 / 48dp 触控热区）**
   - 覆盖字号缩放：`0.85x / 1.0x / 1.3x / 2.0x`
   - 覆盖主题：`AmberBase.LIGHT`（Paper 浅色）与 `AmberBase.DARK`（Ink 深色）
   - 覆盖屏幕宽度：窄屏 `320dp`、标准屏 `412dp`、宽屏 `600dp`
   - 覆盖长文本与中英混排、单行省略与多行自适应折行
   - 覆盖 5 类核心界面并生成 Robolectric Native 渲染截图（全部经视觉巡检通过）：
     1. **聊天主界面**：顶栏返回/导航、`ChatIsland` 状态岛、`ChatActivityButton` 跨会话角标、`ChatShelfButton` 产物架、`ContextUsageSummary` 用量统计、`ContextCompactMarker` 压缩标记、`PendingUserMessageBubble` 排队消息气泡、`SandboxPhoneTakeoverBanner` 人工接管横幅、底部输入框与发送栏
     2. **审批卡**：`ChatToolApprovalCard`（含 Jev 高风险安全门禁提示与三按钮操作区）+ `AgentToolCallCapsule` 工具胶囊
     3. **回执卡**：`ScreenToolReceiptInlineSummary`（拦截待确认态 `needs_user_action` + 已验证态 `verified`）及中英文双语本地化验证
     4. **设置页主要入口**：`ProviderConfigWarningCard` 顶栏告警卡 + `SettingCardGroup`（单行紧凑项 `settingSingleLine` 与双行描述项 `settingTwoLine`）
     5. **底部弹层**：`SandboxSheetHeader` + `SandboxToolActivityContent` + `ScreenToolReceiptDetailSheetContent`

2. **A5.2 长会话、流式输出与滚动性能核验**
   - 增量时间线构建：`ChatTimelinePlanIncrementalTest`（尾部流式追加增量复用 vs 前缀失效重建）
   - 流式输出与大历史窗口虚拟化：`ChatStreamingPerformanceTest`（数百次尾部更新、80 路混合成功/失败/淘汰边界、视口锚点保护）
   - 视口阅读锚点无跳变切换：`ChatStreamingHandoverTest`（`reverseLayout` 下流式块向历史块交接时保持阅读行零跳变）
   - 流式 Markdown 微基准测试：`StreamingRenderMicrobenchmarkTest`（`AMBER_RENDER_MICROBENCH=1` 量化稳定块缓存与有界步进扫描加速比）
   - Baseline Profile 规则核验：`app/src/main/baseline-prof.txt`（共 `1,947` 行预编译热点规则）

3. **A5.3 设置页、底部弹层、审批卡、回执卡专项修复与多语言资源补齐**
   - 消除 `ScreenToolReceiptPresentation.kt` 中全部硬编码中文文案，补齐 6 套语言资源（`values`、`values-zh`、`values-zh-rTW`、`values-ja`、`values-ko-rKR`、`values-ru`）共 79 个键（76 个 `screen_receipt_*` + `setting_page_mac_gateway` / `setting_page_mac_gateway_desc` / `setting_provider_page_grok_oauth_waiting*`）。

---

## 2. 发现的真实 UI / 无障碍问题与修复清单

| 编号 | 界面 / 模块 | 问题描述 | 根因定位 | 修复方案与代码位置 |
| :--- | :--- | :--- | :--- | :--- |
| **UI-01** | 聊天输入框（`ChatInput.kt`） | 左侧 `+` 展开按钮、内联附件按钮（相机/相册/文件）及右侧发送按钮的真实点击热区与语义边界仅为 `44dp × 44dp`，低于 Android 无障碍 `48dp` 标准 | `.clickable` / `.combinedClickable` 放在 `.size(44.dp)` 之后，导致 `PointerInputModifierNode` 与 `SemanticsModifierNode(mergeDescendants=true)` 绑定在 `44dp` 内层协调器上 | 在 `app/src/main/java/app/amber/feature/ui/components/ai/ChatInput.kt` 中将 `.clickable` / `.combinedClickable` 置于 `.minimumInteractiveComponentSize().size(44.dp)` 之前，保持 `44dp` 视觉圆钮尺寸同时暴露 `48dp × 48dp` 触控与无障碍热区；外层 `Row` 改为 `.heightIn(min = 44.dp)` |
| **UI-02** | 待发送排队消息气泡（`ChatListIndicators.kt`） | `PendingUserMessageBubble` 右侧取消按钮使用 `.size(26.dp)`，热区远小于 `48dp` | `IconButton` 被 `.size(26.dp)` 强制压缩了外部布局尺寸 | 在 `app/src/main/java/app/amber/feature/ui/pages/chat/ChatListIndicators.kt` 中改为 `.sizeIn(minWidth = 48.dp, minHeight = 48.dp)`，内部图标保持 `14.dp` |
| **UI-03** | 沙盒底部弹层头部（`SandboxActivitySheetContent.kt`） | 在 `320dp` 窄屏或 `1.3x` / `2.0x` 字体下，右侧「中断」胶囊按钮与步骤翻页器（`← 2/4 →`）把左侧标题挤压至接近 `0dp` 宽度 | 单行 `Row` 未感知容器宽度与 `fontScale`，固定右侧控件占满窄屏宽度 | 在 `app/src/main/java/app/amber/feature/ui/components/ai/SandboxActivitySheetContent.kt` 的 `SandboxSheetHeader` 中引入 `BoxWithConstraints`，当 `maxWidth < 360.dp \|\| fontScale >= 1.3f` 时自动切换为上下双行布局（首行完整展示标题与耗时，次行右对齐展示中断与翻页控件） |
| **UI-04** | 手机接管横幅（`ChatInputSandbox.kt`） | `SandboxPhoneTakeoverBanner` 在 `CompositionLocal` 覆盖 `LocalDensity` 缩放时未能统一感知字体缩放，且测试无法直接注入内存态 `PhoneAutomationTakeoverController` | 仅读取了 `LocalConfiguration.current.fontScale`，且在函数体内强绑定 `koinInject()` | 在 `app/src/main/java/app/amber/feature/ui/components/ai/ChatInputSandbox.kt` 中使用 `maxOf(LocalConfiguration.current.fontScale, LocalDensity.current.fontScale)`，并将 `takeoverController: PhoneAutomationTakeoverController = koinInject()` 提为带默认值的参数 |
| **UI-05** | 顶栏跨会话活动角标（`ChatTopBarPanels.kt`） | 在 `1.3x` 与 `2.0x` 大字体下，`ChatActivityButton` 右上角橙色数字角标（`2`）被 `.size(14.dp)` 圆框严重裁剪甚至不可见 | 角标容器写死 `.size(14.dp)`，而内部 `Text` 使用 `MaterialTheme.typography.labelSmall`（默认 `11.sp / lineHeight 16.sp`），随 `fontScale` 放大后超出圆框 | 在 `app/src/main/java/app/amber/feature/ui/pages/chat/ChatTopBarPanels.kt` 中将角标容器改为 `.sizeIn(minWidth = 14.dp, minHeight = 14.dp).padding(horizontal = 3.dp, vertical = 1.dp)`，字号设为 `9.sp / lineHeight 10.sp` 且 `maxLines = 1` |
| **UI-06** | 上下文用量统计卡（`ContextRing.kt`） | 在 `320dp` 窄屏 + `2.0x` 字体下，`ContextUsageSummary` 底部三列指标（`本次 / 12,800 tok`、`缓存命中 / 75%`、`速度 / 333 tok/s`）横向完全贴死（`12,800 tok75%`、`缓存命中速度`） | 底部 `Row(horizontalArrangement = Arrangement.SpaceBetween)` 无最小间距且不支持折行 | 在 `app/src/main/java/app/amber/feature/ui/pages/chat/ContextRing.kt` 中使用 `BoxWithConstraints`，当 `maxWidth < 360.dp && fontScale >= 1.5f` 时切换为 `FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp))` 自动折行 |
| **UI-07** | 设置页告警卡与分组列表（`SettingPage.kt` / `CardGroup.kt`） | 设置页 `ProviderConfigWarningCard` 右侧「配置」按钮高度不足 `48dp`；`CardGroup` 单行列表项在 `1.3x / 2.0x` 字体下上下贴边（`vertical = 0.dp`） | `TextButton` 未设最小高度；`CardGroup` 单行分支 vertical padding 为 `0.dp` | 在 `app/src/main/java/app/amber/feature/ui/pages/setting/SettingPage.kt` 为告警按钮增加 `.heightIn(min = 48.dp)` 与单行省略；在 `app/src/main/java/app/amber/feature/ui/components/ui/CardGroup.kt` 将单行内边距调整为 `vertical = 8.dp` |
| **UI-08** | 屏幕工具回执卡与详情弹层（`ScreenToolReceiptPresentation.kt` + 6 套 `strings.xml`） | 1) `ScreenToolReceiptPresentation.kt` 存在大量硬编码中文，非中文系统下仍显示中文；2) 内联回执卡的待确认动作 chip 前缀过长（复用详情标题）；3) 详情弹层步骤序号圆圈固定 `.size(20.dp)` 在 `2.0x` 字体下裁剪数字 | 未抽取多语言资源；内联 chip 误用长标题；步骤徽章固定宽高 | 1) 在 `values`、`values-zh`、`values-zh-rTW`、`values-ja`、`values-ko-rKR`、`values-ru` 补齐 76 个 `screen_receipt_*` 与 3 个 `setting_*` 缺失键；2) `ScreenToolReceiptParser.parse(...)` 支持传入可选 `Context` 解析本地化步骤与未达成原因；3) 新增精简 `screen_receipt_chip_held_action`（`待确认：%1$s` / `Held: %1$s`）；4) 步骤徽章改为 `.sizeIn(minWidth = 20.dp, minHeight = 20.dp).padding(horizontal = 4.dp, vertical = 2.dp)` |

---

## 3. 截图与视觉证据目录

### 3.1 Phase 5 UI 与可访问性矩阵截图（`app/build/reports/p5-ui-matrix/`）
- **聊天主界面（Surface 1）**：
  - `app/build/reports/p5-ui-matrix/chat-main-light-320dp-font0.85.png`
  - `app/build/reports/p5-ui-matrix/chat-main-light-412dp-font1.0.png`
  - `app/build/reports/p5-ui-matrix/chat-main-dark-412dp-font1.3.png`
  - `app/build/reports/p5-ui-matrix/chat-main-dark-320dp-font2.0.png`
  - `app/build/reports/p5-ui-matrix/chat-main-light-600dp-font1.0.png`
- **审批卡与工具胶囊（Surface 2）**：
  - `app/build/reports/p5-ui-matrix/approval-card-light-320dp-font0.85.png`
  - `app/build/reports/p5-ui-matrix/approval-card-light-412dp-font1.0.png`
  - `app/build/reports/p5-ui-matrix/approval-card-dark-412dp-font1.3.png`
  - `app/build/reports/p5-ui-matrix/approval-card-dark-320dp-font2.0.png`
- **屏幕工具回执卡（Surface 3）**：
  - `app/build/reports/p5-ui-matrix/receipt-card-light-412dp-font1.0.png`
  - `app/build/reports/p5-ui-matrix/receipt-card-dark-320dp-font1.3.png`
  - `app/build/reports/p5-ui-matrix/receipt-card-light-320dp-font2.0.png`
- **设置页主要入口（Surface 4）**：
  - `app/build/reports/p5-ui-matrix/settings-main-light-412dp-font1.0.png`
  - `app/build/reports/p5-ui-matrix/settings-main-dark-412dp-font1.3.png`
  - `app/build/reports/p5-ui-matrix/settings-main-light-320dp-font2.0.png`
  - `app/build/reports/p5-ui-matrix/settings-main-dark-600dp-font0.85.png`
- **底部弹层（Surface 5）**：
  - `app/build/reports/p5-ui-matrix/bottom-sheet-light-412dp-font1.0.png`
  - `app/build/reports/p5-ui-matrix/bottom-sheet-dark-320dp-font1.3.png`
  - `app/build/reports/p5-ui-matrix/bottom-sheet-dark-320dp-font2.0.png`
- **专项回归与模拟器实机运行截图**：
  - `app/build/reports/p5-ui-matrix/emulator-live-verify.png`（`emulator-5556` 安装 `app-full-arm64-v8a-debug.apk` 并启动 `RouteActivity` 实机截屏）
  - `app/build/reports/context-usage-light.png` / `app/build/reports/context-usage-dark.png`
  - `app/build/reports/jev-approval-card.png`
  - `app/build/reports/screen-receipt-inline-light-380dp.png` / `app/build/reports/screen-receipt-inline-dark-320dp-font1.5.png` / `app/build/reports/screen-receipt-detail-light-font1.3.png`

---

## 4. A5.2 流式输出、长会话与滚动性能量化指标

| 测试套件 / 指标项 | 测试方法 / 场景 | 实测结果 | 结论 |
| :--- | :--- | :--- | :--- |
| `StreamingRenderMicrobenchmarkTest` | `map-all-20-snapshots`（全量映射 20 个流式快照） | `median = 8.204 ms`, `p95 = 10.387 ms` | 基准对照 |
| `StreamingRenderMicrobenchmarkTest` | `map-stable-cache-20-snapshots`（启用稳定块缓存映射 20 个快照） | `median = 2.558 ms`, `p95 = 7.800 ms` | **3.21x 提速**（单帧远低于 16.6ms 60fps 预算） |
| `StreamingRenderMicrobenchmarkTest` | `count-all-100-frames`（全量扫描 100 帧大积压字符） | `median = 28.240 ms`, `p95 = 28.851 ms` | 基准对照 |
| `StreamingRenderMicrobenchmarkTest` | `count-bounded-100-frames`（有界窗口扫描 100 帧大积压字符） | `median = 1.016 ms`, `p95 = 1.176 ms` | **27.80x 提速**（消除长文流式尾帧扫描抖动） |
| `ChatTimelinePlanIncrementalTest` | 2 项测试：流式尾部增量复用 vs 保护节点/前缀变更重建 | `2/2 PASSED`（总耗时 `3 ms`，单次 `< 2 ms`） | 流式追加时复用历史前缀节点与跳转索引，避免 O(N) 全量重建 |
| `ChatStreamingPerformanceTest` | 18 项测试：数百次尾部更新、80 路混合异常与容量淘汰、跨代视口保护 | `18/18 PASSED`（总耗时 `1.46 s`） | 解析队列有界、历史节点异步虚拟化、仅空闲钉底视口执行高度动画 |
| `ChatStreamingHandoverTest` | 6 项测试：`reverseLayout` 下阅读行保持、手势进行中延迟交换、顶部锚点稳定 | `6/6 PASSED`（总耗时 `4.38 s`） | 用户向上滚动阅读历史消息时，流式完成交接不发生视口跳变 |
| `StreamingRenderPerformanceTest` | 5 项测试：Unicode 码点边界完整性、引用定义失效传播、有界步进决策 | `5/5 PASSED`（总耗时 `22 ms`） | 流式字符揭示无半个 emoji/CJK 乱码或样式丢失 |
| Baseline Profile | `app/src/main/baseline-prof.txt` 预置热点规则行数 | `1,947` 行规则，且 `:app:baselineprofile` 模块配置完备 | 覆盖启动与聊天渲染核心路径 |

---

## 5. 执行的测试与构建命令汇总

1. **Phase 5 UI 矩阵、触控热区与多语言回执单测**：
   ```bash
   JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testFullDebugUnitTest \
     --tests "app.amber.feature.ui.Phase5UiAccessibilityMatrixScreenshotTest" \
     --tests "app.amber.feature.ui.components.message.ScreenToolReceiptUiTest" \
     --tests "app.amber.feature.ui.components.message.ScreenToolReceiptPresentationTest" \
     --tests "app.amber.feature.ui.components.message.ChatMessageTouchTargetTest" \
     --tests "app.amber.feature.ui.components.message.ChatToolApprovalCardNoticeTest" \
     --tests "app.amber.feature.ui.pages.chat.ContextUsageSummaryScreenshotTest"
   ```
   - **结果**：`27 tests completed, 0 failed, 0 skipped`（100% 通过）。

2. **Phase 5 流式渲染与长会话性能单测**：
   ```bash
   AMBER_RENDER_MICROBENCH=1 JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testFullDebugUnitTest \
     --tests "app.amber.feature.ui.pages.chat.ChatStreamingPerformanceTest" \
     --tests "app.amber.feature.ui.pages.chat.ChatTimelinePlanIncrementalTest" \
     --tests "app.amber.feature.ui.components.richtext.StreamingRenderMicrobenchmarkTest" \
     --tests "app.amber.feature.ui.components.richtext.StreamingRenderPerformanceTest" \
     --tests "app.amber.feature.ui.pages.chat.ChatStreamingHandoverTest"
   ```
   - **结果**：`32 tests completed, 0 failed, 0 skipped`（100% 通过）。

3. **APK 组装与在线模拟器 `emulator-5556` 安装启动验证**：
   ```bash
   JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleFullDebug
   adb -s emulator-5556 install -r app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk
   adb -s emulator-5556 shell am start -n app.amber.agent.graphite/app.amber.agent.RouteActivity
   ```
   - **结果**：`:app:assembleFullDebug` 构建成功（`20s`），`emulator-5556` 流式安装 `Success`，`RouteActivity` 正常拉起并完成实机截屏验证。

---

## 6. 未验证项 / 条件性阻塞项（CONDITIONAL）与 Phase 6 交接点

- **`CONDITIONAL`（物理真机 Macrobenchmark 帧率采集）**：
  - 当前连接设备为 `emulator-5556`（`sdk_gphone64_arm64` 模拟器）；Android Jetpack Macrobenchmark 的硬件帧耗时（`FrameTimingMetric`）与 Baseline Profile 实机编译基准采集要求非调试态物理真机，故物理真机 Macrobenchmark 标记为 `CONDITIONAL`，在 JVM Robolectric 与微基准测试中已完成算法级与帧预算级量化核验。
- **已知历史遗留项（非本阶段引入）**：
  - `AntigravityOAuthSessionTest` 的 2 个用例在 P1–P4 基线中已知失败（`loopback callback still succeeds after manual code exchange loses the race` / `manual pasted redirect url completes login and persists account`），与 Phase 5 UI/无障碍改动无关。
- **Phase 6（构建、发布门禁与真机回归）交接点**：
  1. 本阶段新增的 `Phase5UiAccessibilityMatrixScreenshotTest` 与扩充的 `ScreenToolReceiptUiTest` 可直接纳入 Phase 6 回归门禁。
  2. 6 套语言资源（`values` / `values-zh` / `values-zh-rTW` / `values-ja` / `values-ko-rKR` / `values-ru`）已全部对齐并编译通过，Phase 6 可直接执行 `:app:lintFullDebug` / Release 资源收缩与混淆验证。

---

## 7. P3/P4 独立审查（G3/G4）7 项问题修复闭环

1. **【阻断 1】通知栏和锁屏能直接「允许一次」高风险工具**：
   - `AgentLiveStatusNotifier.kt`：新增 `requiresInAppApprovalOnly(tool: UIMessagePart.Tool)`（覆盖 `ToolRisk.High`、`mandatoryApproval`、`alwaysAsk`、`JevAutoApprovalGate` 失败回退与固定高敏工具列表），`approvalActions` 对高风险/强制应用内确认工具不再提供「批准」按钮，仅保留「拒绝」。
   - `NotificationUtil.kt`：`NotificationActionConfig` 默认 `authenticationRequired = true`，`buildNotification` 统一通过 `NotificationCompat.Action.Builder(...).setAuthenticationRequired(action.authenticationRequired)` 构建通知按钮，拦截未解锁锁屏直接触发。
   - `ChatRunCoordinator.kt`：`handleNotificationApproval` 入口增加兜底校验，若 `approved == true && AgentLiveStatusNotifier.requiresInAppApprovalOnly(toolPart)` 则直接拒绝并警告返回。
2. **【阻断 2】Mac Gateway 远端审批没接上**：
   - `MacGatewayClient.kt`：`MacGatewayStatus` 新增 `PendingApproval(toolCallId, tool, detail, highRisk)`，`Session` 新增 `summary` 与 `pendingApproval`；实现 `decide(sessionKey, toolCallId, allow, highRisk)` 调用 `POST /v1/amber/decision`，并在客户端拦截 `highRisk && allow`。
   - `MacGatewayRepository.kt`：实现 `decide(sessionKey, toolCallId, allow, highRisk)`，对 `highRisk && allow` 做客户端阻断，成功后乐观清理待审批项并刷新状态。
   - `SettingExperimentalMacGatewayPage.kt`（及 6 套 `strings.xml`）：在任务卡片中渲染 `session.summary` 与 `PendingApprovalCard`；普通风险展示「批准」「拒绝」，高风险仅展示「拒绝」并提示「高风险操作请回 Mac 批准」。
3. **【阻断 3】进程重启后 PAUSED 接管态会卡死**：
   - `PhoneAutomationTakeoverController.kt`：`restoredState()` 先将 `saved.invalidatedSnapshotIds` 与 `saved.lastSnapshotId` 重新写入 `ScreenSnapshotInvalidationRegistry.invalidate(..., "takeover_snapshot_invalidated")`；当旧 run 已随进程消亡（`!isRunAlive(saved.runId)`）时，不将死会话永久留在 `PAUSED`，而是重置并持久化为 `IDLE`。
   - `ScreenOperationOwner.kt`、`AgentBubbleReducer.kt`、`AgentTaskBubbleController.kt`：`trackScreenTool` 与 `AgentBubbleReducer.reduce` 均校验 `takeoverSession.runId` 是否匹配当前活跃 `runId`（并拦截孤儿会话），旧 run 不存在时自动 `finishSession` 收口，绝不阻塞新 run。
4. **【阻断 4】`ScreenFlowTools.kt` 与 `AccessibilityNativeScreen.captureScreenSnapshot`**：
   - `ScreenFlowTools.kt`（99–167）：固定流程每一步均检查 `operations.isStopped(runId)`、`takeoverController` 暂停/恢复状态（`awaitResumeIfPaused` / `confirmResumedWithFreshSnapshot` / `recordObservedSnapshot`）以及分发前 `tryRecordSideEffectDispatch(snapshot.id, action)` 防重放。
   - `AccessibilityNativeScreen.kt` 与 `AccessibilityController.kt`（`ScreenSnapshotInvalidationRegistry`）：跟踪 `takeoverPauseActive` 状态；接管暂停期间及 `takeover_snapshot_invalidated` 快照禁止通过 `markFreshCapture` 无条件清除失效标记。
5. **【应修 5】`CompactSummaryPayloads.normalizeModelOutput`（109–135）**：
   - `CompactSummaryPayload.kt`：`timelineSummary` 与 `handoffMarkdown` 在进入 `fitHandoff(handoffMarkdown, mustNotLose)` 前均先经过 `ContextFactRedaction.redact(...)` 脱敏。
6. **【应修 6】`ConversationContextRepository.search`**：
   - `ConversationContextModels.kt`、`ContextSearchLimits.kt`、`ConversationContextRepository.kt`、`ConversationContextEngine.kt`、`ConversationContextTools.kt`：先合并全量候选、按 `coveredCompactIds` 与 `(source, id)` 去重、再按 `role` 过滤，最后才按 `limit` 与字符预算截断，准确输出 `has_more`、`truncated` 与 `total_matches`。
7. **【应修 7】真机 E3 证据记为 `CONDITIONAL`，不阻塞**：
   - 已在交接文档与报告中显式标记真机 E3（跨应用无障碍实机闭环）与物理真机 Macrobenchmark 为 `CONDITIONAL`，不阻塞本阶段收口。

# Android 性能项目：现有验证入口与最小清单

> 这是执行前的入口盘点；下文“未运行”和当时环境状态只描述盘点阶段。最终运行结果与限制以 [验收报告](2026-09-29-android-performance-results.md) 为准。

范围：只读盘点 `/tmp/amber-android-perf-20260929` 固定快照；**没有运行 Gradle、ADB 或测试**。本机 16 GB，`emulator-5560` 已因内存压力停止；先完成主 agent 的串行编译，再考虑 UI smoke。下列测试结果均需实际运行后才能报告。

## 建议执行顺序（按改动选用，不必全跑）

在快照根目录使用 JDK 21、指定 SDK、离线与最多 3 workers。应用层首选这一条定点 JVM 命令；`--tests` 可按实际改动删减：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :app:testDebugUnitTest \
  --tests '*ChatTimelinePlanTest' \
  --tests '*ChatTimelinePlanIncrementalTest' \
  --tests '*StreamCheckpointCoalescerTest' \
  --tests '*ChatTurnAgentTest' \
  --tests '*ConversationSessionQueueTest' \
  --tests '*SubAgentDockStateTest' \
  --tests '*SubAgentDockInteractionTest' \
  --tests '*ChatListNormalAnchorTest' \
  --tests '*NovelMarkdownWorkspacePageTest' \
  --tests '*CouncilTimelineLayoutTest' \
  --tests '*ThemeDesignRenderingTest' \
  --offline --console=plain --max-workers=3 -Pksp.incremental=false
```

这覆盖聊天列表计划的流式尾部/增量复用、checkpoint 节流、生成 hook、待发消息 FIFO、dock 状态与手势、长 reasoning 展开锚点、小说编辑保存门控、议会窄屏作者标签以及主题绘制像素。均为内存对象、脚本生成或 Robolectric Compose；不需 provider/账户/真实数据。它**不测真实帧率、发送到 provider 的端到端延迟或长聊天滚动吞吐**。`ReplayGoldenTest` 的 10 个仓内 golden 还覆盖中途 steer、子代理回报与工具恢复；只在运行链改动时追加 `--tests '*ReplayGoldenTest'`，不要使用 `-PupdateGoldens=true`（会重写 golden）。

小说运行链、模型议会生成链若被改动，再各用现有 fake/临时目录用例，不接真实 provider：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:testDebugUnitTest --tests '*NovelWorkspaceRuntimeTest' --offline --console=plain --max-workers=3 -Pksp.incremental=false
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :feature:modelcouncil:testDebugUnitTest --tests '*CouncilRoomManagerTest' --offline --console=plain --max-workers=3 -Pksp.incremental=false
```

`NovelWorkspaceRuntimeTest` 使用 `TemporaryFolder`、scripted fake kernel，现有章节批处理、恢复、暂停和提交边界；`CouncilRoomManagerTest` 使用 fake runner/虚拟时钟，现有多 chunk 合并、guest/host/synthesis/close。两者验证状态正确性，不给出设备渲染数据或真实模型时延。

## 设备入口与数据边界

编译结束后在**独立、无个人数据的 AVD**构建 Debug 应用和测试 APK，再只选定序列号、只运行指定类。`debug` 的应用 ID 是 `app.amber.agent.graphite`，默认 instrumentation runner 是轻量 `Application`；UI 类必须在构建测试 APK 时传 `-PuiSmokeTest=true`，切换为真实 `AmberAgentApp`。安装 APK 后可使用以下入口（先核对安装包/runner 与 AVD 序列号）：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PuiSmokeTest=true -PdeviceTestBuildType=debug --offline --console=plain --max-workers=3 -Pksp.incremental=false
/opt/homebrew/share/android-commandlinetools/platform-tools/adb -s emulator-5560 shell am instrument -w -e class app.amber.agent.ChatSoftTimelineSmokeTest app.amber.agent.graphite.test/app.amber.agent.AmberAgentUiSmokeTestRunner
```

`ChatSoftTimelineSmokeTest` 由测试自身插入并清理 **2 个本地会话 + 3 个 metadata-only 子代理任务**，核对混合消息、待审批卡、展开/折叠 dock、跨会话切换、IME、完成与 dismiss；有模拟器 guard，**不真实创建子代理、不发送聊天、不调用 provider**。`UiRefreshDeviceSmokeTest#homeSettingsDisplayThemeAndEmptyChat` 可补主题切换、首页、空聊天与截图；其其它方法会改写并恢复持久设置或插入本地缓存/会话，应只在可丢弃 AVD 使用。`RedesignNavigationSmokeTest` 同样有模拟器 guard，创建并清理本地小说工程、Skill、缓存 provider，覆盖 Novel/Council 的**导航和静态布局**，不覆盖生成或压力。

`app/baselineprofile` 的 `StartupBenchmarks` 只测冷启动（无 profile/要求 profile，各 10 次）和首页→已有会话滚动帧时间；`BaselineProfileGenerator` 只采启动与首页/会话路径，`automaticGenerationDuringBuild=false`。`ProfileJourneys.scrollHomeAndConversation` 找不到首页分区或会话行时会提前返回，短会话也可能没有实际滚动，因此必须先核对测试设备有可点击且足够长的**本地**会话，并检查 trace/屏幕路径，才可将结果记作会话滚动。入口是 `:app:baselineprofile:connectedBenchmarkReleaseAndroidTest`，但该 Gradle connected task 不限定设备；性能结论留给空闲**物理设备**上的单设备运行，当前退到软件渲染的模拟器只能做功能 smoke。

`scripts/amberagent_auto_jank.sh` 对当前前台页面执行往返滑动，采 `gfxinfo`/framestats/`AmberChatPerf` 日志和 UI XML，默认包名 `app.amber.agent`、默认 serial `c9a8a837`，所以若使用必须显式设置 `SERIAL=emulator-5560 PACKAGE=app.amber.agent.graphite EXPECTED_TEXT=...`。脚本会启动 Activity、清空设备 logcat、重置 gfxinfo、写系统 log tag；必须先打开且核实目标长聊 fixture，不能在空聊天上得到有效性能样本。当前软件渲染/内存压力下**不要用其阈值 PASS/FAIL 宣称优化收益**。

`NativePathDeviceBenchmarkTest` 只比较 Markdown/regex/SHA/Reader native 与 JVM 微基准，要求 arm64 真机；与聊天、dock、小说、议会的整页帧性能无关。`JevScreenDeviceTest#realJevBilibiliSearch` / `#realJevRunsNativeGoalThroughProductionTool` 明确用已保存 Jev key、HTTP 与 Accessibility，`SettingsTransferDeviceTest` 会导入/导出真实配置；两类都不属于本次无 provider 验证，切勿顺手运行整套 `connected*AndroidTest`。

## 当前确切缺口

- 没有现成的无 provider 设备脚本覆盖**真实发送 → 持续流式更新 → 长聊天滚动**的同一次运行；JVM 测试只能证明计划/节流/状态，`ChatSoftTimelineSmokeTest` 只展示预置内容。
- 没有现成的 dock/小说/议会高负载 Macrobenchmark 或可比的自动性能基线；这些页面现有设备 smoke 偏导航/视觉，运行链现有测试偏状态正确性。
- 多并发方面有 `ConversationSessionQueueTest`、`AgentCronManagerConcurrencyTest`、议会虚拟时钟与子代理状态测试，但缺少多个**真实同时运行**任务下的设备帧时间/内存样本。真实 provider 时延和账户行为需另设明确授权的设备/账户验收；不能由 fixture 或模拟器指标推断。

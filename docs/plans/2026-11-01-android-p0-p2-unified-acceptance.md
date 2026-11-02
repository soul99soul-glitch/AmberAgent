# Android 选定差异补全 · P0–P2 统一 JVM / 构建 / 真机验收（2026-11-01）

> 本文件是 handoff §9 专用交付记录，不修改 `2026-10-31-android-selected-parity-handoff.md`。
> 五阶段**代码**均已独立验收通过。本文件只记录统一测试/构建/真机结果。
> **不能**把「五阶段代码通过」写成全部完成。下文每项区分 JVM 与真机，未闭合项单列。

## 0. 结论（先看这个）

`752feca80` 最终验收曾**未通过**（设备证据未闭合）。五阶段**代码**仍独立通过。用户于 2026-11-01 回复「已解锁」后，§11.3 前台真机项已按 class 跑完并留下完整 raw 日志。本文件停等 Paseo 最终审核；未 push / 未开 PR。

| 层 | 状态 |
| --- | --- |
| 五阶段代码 | 独立验收通过（见 §1 提交） |
| JVM / 编译 / APK | 已跑。app 全量 3567 tests 中 2 个既有 `AntigravityOAuthSessionTest` 并发基线失败，未降断言、未无关修。本轮定点 `VideoPlaybackSessionTest` BUILD SUCCESSFUL |
| 非 debuggable / R8 等价 | 同包 profileable 已组装、覆盖安装并跑通生产 OCR；结束后已还原 DEBUGGABLE fullDebug。`fullBenchmarkRelease` 已组装、**未**装到生产 `app.amber.agent` |
| 真机安装/启动 | graphite `-r -t` 保留用户数据。`firstInstallTime=2026-10-28 19:02:18`。结束 `lastUpdateTime=2026-11-01 12:27:21`，`pkgFlags` 含 `DEBUGGABLE`。`RouteActivity` Status ok / WARM，前台可见用户会话列表 |
| P0 离屏 ML Kit 像素 OCR | **部分证据已闭合（锁屏）**：`screenshotSource=offscreen_view_draw`。`/tmp/amber-s9-class-ocr-offscreen.log` OK (4 tests) CODE -1 exit 0 |
| P0 前台生产截图 OCR | **已闭合（debug + 同包 profileable）**：`DefaultScreenOcrEngine(context, ScreenCaptureManager(context))`，`screenshotSource=accessibility_screenshot`，AX 空、树无夹具词。debug `/tmp/amber-s9-fg-ocr-debug.log` OK (2 tests) CODE -1 exit 0；profileable `/tmp/amber-s9-fg-ocr-profileable.log` OK (2 tests) CODE -1 exit 0。canvas 精确 `琥珀验证完成`；WebView ML Kit 读成 `號珀验证完成`（像素仍是琥） |
| P1 Room 四态 / 水位 / 冷启 | **已闭合（锁屏）**：per-class 完整日志 CODE -1 + exit 0（§11.2） |
| P1 Compose 四态面板 | **已闭合**：`ConversationActivityDeviceTest` `/tmp/amber-s9-fg-conversation.log` OK (1 test) CODE -1 exit 0。截图 Wait/Fail/Done/Run；点击 `opened=wait-conv title=Wait chat kind=waiting` |
| P2 Jev 设置 decode | **已闭合（锁屏）**：`JevSettingsDeviceTest` OK (2 tests) CODE -1 exit 0 |
| P2 Jev 原生快照 / scripted controller | **本地原生已闭合**：`nativeSnapshotsRejectStaleAndVerifyLocalActions` STATUS 0。`/tmp/amber-s9-fg-jev-native.log` OK (3 tests) CODE -1 exit 0。联网 `realJev*` 两项 STATUS -4 skip（无外部 key），不阻塞本地原生 |
| P2 视频 contain-fit 数学 + 静音 MediaPlayer | **补充证据已闭合（锁屏）**。**不是** Dialog 实操 |
| P2 视频 Dialog 控制 / 时间线 | **已闭合**：`/tmp/amber-s9-fg-video.log` OK (2 tests) CODE -1 exit 0。横竖方 Dialog 播放；ON_STOP 暂停、ON_START 不自动续播、手动 play、close 静音、row-12 仍在。生产返工：二次 `onPrepared` 不再 auto-start |
| P2 per-App ART 假 snapshot | **已闭合（锁屏，假 snapshot）**，与真 controller **分开标** |
| P2 per-App 真 controller + 隔离 fixture | **已闭合**：`/tmp/amber-s9-fg-perapp-real.log` OK (3 tests) CODE -1 exit 0。同包新 snapshot / 跨包隔离 / 接管去重 / 清除。未操作微信等真实业务包 |

未 push / 未开 PR。三个用户未跟踪计划文档未动。停等 Paseo 最终审核。

---

## 1. 基线与五阶段代码提交

工作分支：`codex/android-p0-ocr`。本记录提交前 HEAD：`80ed4a964`。

| 阶段 | 首轮 | 返工 / 记录 | 代码验收 |
| --- | --- | --- | --- |
| P0 OCR | `fb65d85a0` | `b9682dd55`；记录 `66ad927d8` | 通过，真机当时待验 |
| P1 活动中心 | `d07a05f98` | `04c6a3116`；记录 `40a42abc4` | 通过，真机当时待验 |
| P2 Jev | `1a499d424` | `b42ea9c2a`；记录 `34807ca56` | 通过，真机当时待验 |
| P2 竖屏视频 | `cb52fa0ba` | `33deefa7d`；记录 `92a865912` | 通过，真机当时待验 |
| P2 per-App | `a90718ae6` | `80ed4a964`；记录见 per-App 执行文件 | 主代理独立复跑 92 tests `/tmp/amber-perapp-rereview.log` 通过 |

三个既有未跟踪用户文档保持不提交：

- `docs/plans/2026-10-26-chat-history-performance-phase2.md`
- `docs/plans/2026-10-31-android-selected-parity-handoff.md`
- `docs/plans/2026-10-31-ios-android-feature-diff.md`

未 `reset` / `clean` / `rebase`，未 push / 未开 PR。未读其他产品仓库。APK / 日志 / 截图只在 `app/build` 与 `/tmp`。

---

## 2. 环境（本轮开始时记录）

- 工作区：`/Users/hugo/Desktop/git/AmberAgent`
- Java：JBR 21.0.11（`/Users/hugo/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home`）
- SDK：`/Users/hugo/Library/Android/sdk`；Gradle `--offline`
- adb：`/opt/homebrew/bin/adb`

真机（报告可掩码）：

| 项 | 值 |
| --- | --- |
| 型号 | Xiaomi `2608BPX34C` |
| product / device | `lhasa` |
| 制造商 | Xiaomi |
| Android / API | 17 / 37 |
| ABI | `arm64-v8a` |
| 安全补丁 | 2026-09-01 |
| 无线 TLS ADB serial | `adb-0A07846009983540-Rmxr9u._adb-tls-connect._tcp` |
| 硬件 serial | `0A07…3540` |
| 封面屏 | 1168×1712（port 1） |
| 内屏 | 1672×2364（port 0） |
| 折叠状态 | 物理 `CLOSED`（0）。本轮曾留下 `OPENED`/`TENT` override，结束时 `cmd device_state state reset`，override 已空 |
| 锁屏 | `CredentialType: PIN`，`deviceLocked=1`，`mDreamingLockscreen=true`，FLAG_SECURE。未清 PIN、未卸载、未清库 |
| 无障碍 | 仅 `io.github.jackwener.dsh/...DshAccessibilityService`。Amber 无障碍未开，本轮未改 |

安装包：`app.amber.agent.graphite` versionName `2.6.8` / versionCode `396`，签名 `[97b4b87b]`。

- `firstInstallTime=2026-10-28 19:02:18`（全程保留）
- 结束时 `lastUpdateTime=2026-11-01 04:46:06`，`pkgFlags` 含 `DEBUGGABLE`（已从 profileable 还原 fullDebug）

---

## 3. 每阶段关键文件 / 依赖 / 迁移

### 3.1 P0 OCR

生产：`ScreenOcrService.kt`（`DefaultScreenOcrEngine`、`MlKitScreenTextRecognizer`）、`ScreenOcrModels.kt`、`ScreenPrimitiveTools`、`ScreenToolReceiptPresentation`、`JevScreenGoalRunner` 透传 `ocr_source`。

依赖：`com.google.mlkit:text-recognition-chinese:16.0.1`（bundled）。`gradle/libs.versions.toml` `mlkitTextRecognition = "16.0.1"`。

APK 实测（profileable 与 benchmarkRelease 相同）：

- `lib/arm64-v8a/libmlkit_google_ocr_pipeline.so` **11,064,544** bytes（未压缩）
- OCR 相关 zip 条目 34 个、**14,436,256** bytes，含 `Hani_ctc` / `Latn_ctc` / taser / `taser_tflite_gocrchinese_and_latin_*`
- R8 `fullBenchmarkRelease` 中上述 so 与模型仍在，未被 shrink 掉

支持 script：简中 / 繁中 / Latin。不宣称日/韩/俄。

无 Room 迁移。

### 3.2 P1 活动中心

生产：`ConversationActivityCenter`、`RoomRunTerminalStore`、`ChatActivityPanelContent`、`AppDatabase` version **22**。

`MIGRATION_21_22`：建 `conversation_activity_seen`（PK `run_id`）与 `conversation_activity_baseline`（单行 `id=1`）。

无新 Gradle 依赖。

### 3.3 P2 Jev

生产：`JevPurpose` 13 项（无 `SCREEN_AUTOMATION` 枚举值）、`PHONE_VERIFICATION` / `PHONE_ACTIONS`、`JevScreenGoalRunner`、`AgentPrefs` JSON 迁移（显式 PHONE_* 优先，legacy `SCREEN_AUTOMATION` 填缺省）。

设置资源六语种已有。无 Room 迁移。

### 3.4 P2 竖屏视频

生产：`VideoPlaybackDialog.kt`、`VideoDisplaySize.kt`（`containFitVideo` / `videoControlHeightPx`）、`ContainVideoView`。无 media3 / ExoPlayer 新依赖。

### 3.5 P2 per-App

生产：`PerAppSuccessPath.kt`、`PerAppSuccessPathStore.kt`、`JevScreenGoalRunner` 读写、`SettingAgentExecutionPage` 清除。落盘 `filesDir/jev/per_app_success_paths.json`。无 Room 迁移、无新依赖。

---

## 4. 命令与结果（可复核）

统一 JVM（`--offline`）：

```bash
./gradlew :core:jev:testDebugUnitTest \
  :core:settings:testDebugUnitTest \
  :app:testFullDebugUnitTest --offline
# 日志：/tmp/amber-unified-jvm.log
# core:jev 71 / 0 fail
# core:settings 122 / 0 fail
# app 3567 tests, 2 failed, 15 skipped → BUILD FAILED
```

既有失败（不降断言、本轮不修）：

1. `AntigravityOAuthSessionTest.late invalid grant cannot clear a session created after logout` — `AssertionError` at `AntigravityOAuthSessionTest.kt:58` / invokeSuspend `:77`
2. `AntigravityOAuthSessionTest.late successful refresh cannot replace a session created after logout` — `AssertionError` at `:27` / invokeSuspend `:46`

XML：`app/build/test-results/testFullDebugUnitTest/TEST-app.amber.ai.provider.providers.google.AntigravityOAuthSessionTest.xml`。

编译 / 组装：

```bash
./gradlew :app:assembleFullDebug --offline
# /tmp/amber-unified-assemble-debug.log  BUILD SUCCESSFUL in 21s
# 含 :app:compileFullDebugKotlin；APK /tmp/amber-s9-full-arm64-debug.apk 116M
# aapt: package app.amber.agent.graphite；含 application-debuggable

./gradlew :app:assembleFullBenchmarkRelease --offline
# /tmp/amber-unified-r8-benchmark.log  BUILD SUCCESSFUL in 2m 14s
# APK /tmp/amber-s9-full-arm64-benchmarkRelease.apk 71M
# package app.amber.agent；无 application-debuggable；minify true
# mapping：app/build/outputs/mapping/fullBenchmarkRelease/
# 仓库无 google-services.json / 无发行密钥；未造假 Firebase 配置
# 该 variant 签名为 debug，applicationId 为生产包名，故未安装以免覆盖用户数据

./gradlew :app:assembleFullDebug -PprofileableDebug=true --offline
# /tmp/amber-unified-profileable.log  BUILD SUCCESSFUL in 1m 13s
# APK /tmp/amber-s9-full-arm64-profileable.apk 94M
# 同包 graphite、同 debug 签名；aapt 无 application-debuggable

./gradlew :app:assembleFullDebugAndroidTest --offline
# /tmp/amber-s9-full-debug-androidTest.apk 2.8M
```

另组 `fullBaseline`（`app.amber.agent.debug`，非 debuggable，minify false）`/tmp/amber-s9-full-arm64-baseline.apk` 94M，未安装（不同 applicationId，与本次 graphite 用户数据无关）。

本地化脚本既有 FAIL（ja/ko/ru 缺部分 key、zh grok oauth waiting、`IntelligentUiStudyView` 内联中文）不是本轮引入，未顺手修。

---

## 5. 安装 / 启动（保留用户数据）

```bash
adb -s "$SERIAL" install -r -t /tmp/amber-s9-full-arm64-debug.apk     # Success
adb -s "$SERIAL" install -r -t /tmp/amber-s9-full-debug-androidTest.apk
adb -s "$SERIAL" shell am start -W -n app.amber.agent.graphite/app.amber.agent.RouteActivity
# Status: ok；Activity: ...RouteActivity；WaitTime ~3018ms
```

profileable 覆盖安装后再还原 debug：

```bash
adb -s "$SERIAL" install -r -t /tmp/amber-s9-full-arm64-profileable.apk  # Success；pkgFlags 无 DEBUGGABLE
# RouteActivity Status ok pid 9668
# 随后 OCR+video instrument OK（见 §6）
adb -s "$SERIAL" install -r -t /tmp/amber-s9-full-arm64-debug.apk        # Success
# firstInstallTime 仍为 2026-10-28 19:02:18
# RouteActivity Status ok pid 18443；pkgFlags 恢复 DEBUGGABLE
```

logcat 无 `FATAL EXCEPTION` / `AndroidRuntime`。锁屏下 `visibleRequested=false` 是 keyguard 遮挡，不是进程崩溃。FLAG_SECURE 使 `screencap` 得不到应用画面，不能当 UI 通过证据。

未卸载、未 `pm clear`、未改 locksettings。

---

## 6. 真机证据矩阵

两个显式 runner（`am instrument` 组件选择，`newApplication` 固定 Application，不读私有 instrumentation 参数）：

- `AmberAgentAndroidTestRunner`：plain `Application`，锁屏 Room / JSON / ART / 离屏 OCR。`newApplication` 先装空 store / 无 AlarmManager 的 no-op `ReminderScheduler` Koin，再 disable `ReminderRescheduleReceiver`，`finish()` 恢复原 `enabledSetting`。plain Application 无生产 Koin 时 TIME_SET 会崩测试，这是测试隔离问题，不是已证实的生产 boot 缺陷。
- `ChatDeviceFixtureRunner`：`ChatDeviceFixtureApplication`（隔离 files/DB/prefs + Koin，不跑 `AmberAgentApp` cron/bubble/recoverInterrupted）。前台 Compose / Dialog 用此 runner。同样 no-op Koin + disable/restore，不碰其他组件。

Compose 使用 `createEmptyComposeRule`，规则启动时不拉 Activity。

证据目录：`/tmp/amber-s9-evidence/` 与 `/tmp/amber-s9-evidence/profileable-ocr/amber-s9/`。

### 6.1 P0 OCR — 离屏 ML Kit 像素（部分证据，不是生产截图链路）

命令（profileable，FLAG_DEBUGGABLE 关闭）：

```bash
adb shell am instrument -w -e class \
  'app.amber.agent.ScreenOcrDeviceTest,app.amber.agent.VideoContainFitDeviceTest' \
  app.amber.agent.graphite.test/app.amber.agent.AmberAgentAndroidTestRunner
# /tmp/amber-s9-connected-profileable-ocr.log
# Time: 3.228  OK (6 tests)
```

夹具：离屏 `WebView` / 自绘 `Canvas` `view.draw` → 1080×1600 bitmap；`IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`；`bitmapRecognizer=null` + 生产 `MlKitScreenTextRecognizer`；`accessibilityRegionsProvider={emptyList()}`。无 fake recognizer。

| 项 | 结果 | 证据 |
| --- | --- | --- |
| Canvas 中文 | `琥珀验证完成` bounds 59,255,578,344 | `canvas-ocr.txt` / `canvas-source.png` |
| Canvas Latin | `OCR-LATIN-MARK` | 同上 |
| Canvas 可点区域 | `立即查看详情` 非空包围盒 | 同上 |
| WebView | `琥珀验证`/`完成`/`OCR-`/`LATIN-`/`MARK`/`立即查看详`；断言含 琥珀+验证、LATIN、立即/查看 | `webview-ocr.txt` / `webview-source.png` |
| source | `BITMAP_OCR` / `RECOGNIZED` / `screenshotSource=offscreen_view_draw` / `recognizer=mlkit_text_recognition_chinese` | 两份 ocr.txt |
| AX 未供词 | `axHasChinese=false` `axHasLatin=false`；dump 为锁屏「无线调试/微信」 | `canvas-ax.txt` `webview-ax.txt` |
| 充分树零截图 | `triggered=false reason=tree_coverage_sufficient shots=0` | `sufficient-tree.txt` |
| 缺截图 | `diagnostics=[screenshot_unavailable]` `UNAVAILABLE` | `screenshot-unavailable.txt` |

同一 4 条 OCR 先前也在 debuggable graphite 上 2.513s 全过（`/tmp/amber-s9-connected-ocr.log`）。profileable 6 tests = 4 条离屏 OCR + 2 条 contain-fit 数学，**不是**前台 WebView/Canvas 生产截图通过。

生产路径（待解锁）见 §11：前台 `ScreenOcrFixtureActivity` + `DefaultScreenOcrEngine(context, ScreenCaptureManager(context))` + `screenshotSource=accessibility_screenshot` + AX 不给词 + 可点区域映射当前树唯一目标。离屏注入不得再冒充该链路。

### 6.2 P1 活动中心

真机 Room（隔离库名 `amber_s9_activity_*`，不碰用户 DB）：

`ConversationActivityStoreDeviceTest.excludePriorityOpenAsReadColdStartAndUpgradeBaseline` — `/tmp/amber-s9-connected-core.log` OK。

覆盖：等待 / 失败 / 完成 / 运行 四态投影与优先级、当前对话排除、打开即已读、关库重开冷启仍在、升级基线前历史完成不轰炸。

`AppDatabaseMigrationTest` 曾在未完成的 `core.log` 中出现过 STATUS 0；本轮以 §11 per-class 完整日志 + `INSTRUMENTATION_CODE` + 退出码为准。

Compose `ConversationActivityDeviceTest` 四态面板：**未跑**。先前挂起不能只写成 PIN：plain Application 无生产 Koin 时 TIME_SET 崩测试、`createAndroidComposeRule` 在规则启动时等 RESUMED、keyguard 下 Activity 到不了 RESUMED。夹具已改为 empty compose rule + `ChatDeviceFixtureRunner`；TIME_SET 隔离见 §11.5。解锁后跑前台面板。

### 6.3 P2 Jev

真机 ART：

- `legacyScreenAutomationSplitsToPhoneVerificationAndActions`
- `explicitPhoneKeysWinAndUnknownKeysDoNotDropTheSetting`

均 OK（`/tmp/amber-s9-connected-core.log`）。

`JevScreenDeviceTest.nativeSnapshotsRejectStaleAndVerifyLocalActions`：**未跑**（待解锁）。本轮允许临时开启 Amber 无障碍，保存并逐项恢复原 `enabled_accessibility_services`，禁止覆盖丢 DSH。

opt-in `realJev` / `bilibiliJev`：**未跑**。无外部 Jev key 只阻塞联网 realJev，不阻塞上述本地原生 + scripted transport。

### 6.4 P2 视频

真机无 Activity（profileable 同跑）：

- `portraitLandscapeSquareDecodeAndContainFitWithinDisplaySlot`
- `mutePlayerPausesAndDoesNotAutoResumeUntilStart`

封面屏 1168×1712、density 2.75、控制条 reserved 132、availH 1580（`video-contain.txt`）：

| 片段 | coded | decoded | fitted |
| --- | --- | --- | --- |
| portrait | 360×640 | 360×640 1.5s | 888×1580（高限制） |
| landscape | 640×360 | 640×360 1.5s | 1168×657（宽限制） |
| square | 360×360 | 360×360 1.5s | 1168×1168 ≤ availH |

`MediaPlayer` 预置音量 0，`pause` 后不自动 `start`（`video-player-pause.txt`）。这是解码器补充证据，**不能**代替 Dialog 实操。

`VideoPlaybackDialog` 横竖方控制可点、ON_STOP 暂停、关弹层无声、时间线滚到 row-12：**未在 Dialog UI 上完成**。解锁后跑改写后的 `VideoPlaybackDeviceTest`，记录截图与 pause/resume/close 点击，不用 contain-fit 数学冒充。

### 6.5 P2 per-App

真机 ART + `FilePerAppSuccessPathStore` 于 `cacheDir`（不写用户 `filesDir/jev`）：

| 测试 | 结果 |
| --- | --- |
| `samePackageFreshSnapshotUniquelyAssistsAndCrossPackageIsIsolated` | OK |
| `ambiguousAndPageDriftFallBackWithoutInventingClicks` | OK（首轮失败后改为与 JVM 相同：fresh store + settings seed） |
| `plannerUniqueKindRefOnlyReordersLiveCandidates` | OK |
| `takeoverDoesNotRepeatAndClearBlocksInflightWrite` | OK |

日志：当时写在未完成的 `/tmp/amber-s9-connected-core.log`（无 `INSTRUMENTATION_CODE`）。本轮 ART 假 snapshot 以 §11 per-class 完整日志为准。用 `FakeAccessibilityController` + scripted transport，**不是**真实 AccessibilityController，也**不是**对微信等真实包发点击。真 controller 见 `PerAppRealControllerDeviceTest`（待解锁）。未支付、未发消息、未删用户数据。

---

## 7. 本轮夹具（可提交）

| 文件 | 作用 |
| --- | --- |
| `app/src/androidTest/AndroidManifest.xml` | 登记 `ScreenOcrFixtureActivity` / `JevScreenFixtureActivity`（测试 APK） |
| `ScreenOcrFixtureActivity.kt` | WebView/Canvas 工厂，AX hide descendants |
| `ScreenOcrDeviceTest.kt` | 离屏 ML Kit 像素（部分证据） |
| `ScreenOcrProductionCaptureDeviceTest.kt` | 前台生产截图 OCR（待解锁） |
| `ConversationActivityDeviceTest.kt` | Store 隔离 Room + empty-compose 面板（后者待解锁） |
| `JevSettingsDeviceTest.kt` | 用途拆分 decode |
| `PerAppSuccessPathDeviceTest.kt` | ART + FakeAccessibilityController |
| `PerAppRealControllerDeviceTest.kt` | 真实 controller + 隔离 fixture（待解锁） |
| `VideoPlaybackDeviceTest.kt` | Dialog UI 实操（待解锁） |
| `VideoContainFitDeviceTest.kt` | 补充 contain + 静音 pause，不是 Dialog |
| `AmberS9VideoAssets.kt` | 本地 ffmpeg 色块 mp4 的 base64 |
| `AmberAgentAndroidTestRunner.kt` | 默认 plain `Application`；no-op ReminderScheduler Koin + disable/restore |
| `ChatDeviceFixtureRunner` | 第二 instrumentation；固定 `ChatDeviceFixtureApplication` |
| `TestReminderReceiverIsolation.kt` | 只动 reminder 接收器；persist 原 enabledSetting；no-op Koin 吞 TIME_SET |
| `AmberS9DeviceSupport.kt` | 解锁 assume、a11y 保存/恢复 DSH、证据、unique tree target |

生产代码无调试绕过。不启动真实用户作业。

---

## 8. 未闭合风险

1. 联网 `realJev` 无外部 key，两项 STATUS -4 skip。本地原生 / scripted 已闭合，不把 skip 写成失败，也不把 skip 写成联网通过。
2. `fullBenchmarkRelease` 未装：生产 `applicationId`，debug 签名。非 debuggable 证据是同签 graphite profileable OCR。
3. 仓库无 `google-services.json`、无发行 keystore；未留假配置。
4. WebView 无障碍截图上 ML Kit 中文有时把 `琥` 读成 `號`/`号`。像素仍是琥；断言接受这三种字形。离屏 `view.draw` 仍不得冒充生产截图。
5. 既有 `AntigravityOAuthSessionTest` 2 失败、本地化脚本既有缺口：基线，本轮未动。
6. 可折叠封面屏上 MIUI 可能先把 exported fixture 挂成小窗；夹具须等 `hasWindowFocus` 且 snapshot 宽度 ≥ 800 再 OCR。
7. per-App ART 假 snapshot 与真 controller 必须分开标；不对用户真实账号应用操作。
8. 本文件停等 Paseo 最终审核；未 push / 未开 PR。

---

## 9. git status（本记录提交前）

工作树在提交本文件前应只剩三份用户未跟踪计划：

```
?? docs/plans/2026-10-26-chat-history-performance-phase2.md
?? docs/plans/2026-10-31-android-selected-parity-handoff.md
?? docs/plans/2026-10-31-ios-android-feature-diff.md
```

本轮已提交（独立 commit，不 push）：

1. `ebad70b98` `fix(android): 视频 ON_STOP 后 surface 重建不自动续播`
2. `17a398680` `test(android): 前台夹具迁入 debug 并用 shell am start 验收`
3. 本文件 `docs(android):` §0 / §8 / §11.3 前台证据

三份用户文档不加入。`/tmp` APK、日志、截图不进 git。

---

## 10. 停等最终验收（历史，752feca80）

当时把离屏 OCR 写成「非 debuggable 真实 OCR 真机通过」，并把不完整 `core.log` 当 suite 通过。主代理最终验收未通过。更正见 §0 与 §11。本项**不能**标完全完成。

---

## 11. 752feca80 返工（本轮）

### 11.1 夹具 / runner

Compose/Activity 挂起**不能**只写成 PIN。锁屏下复现：

1. 默认 runner 用 plain `Application`，目标进程收到 `TIME_SET` 时 `ReminderRescheduleReceiver` 抛 `KoinApplication has not been started`，instrument 在测试执行前崩溃。这只证明 **plain test Application 不启动 Koin** 时会崩，**不能**写成已证实的生产 boot/时间变化竞态。
2. 曾用 `-e amberFixture s9` + 反射扫 `ActivityThread` 私有 Bundle；Android 版本不稳，已弃用。
3. `createAndroidComposeRule` 在规则启动时等 `RESUMED`；keyguard 下到不了 RESUMED。
4. 测试 APK fixture Activity 与目标包不是同一进程。

androidTest 隔离（不改生产 reminder 行为）：

- `AmberAgentAndroidTestRunner`：`newApplication` 固定 plain `Application`。
- `ChatDeviceFixtureRunner`：同一测试 APK 的第二 instrumentation，`newApplication` 固定 `ChatDeviceFixtureApplication`。选择方式：`am instrument -w -r -e class … app.amber.agent.graphite.test/app.amber.agent.ChatDeviceFixtureRunner`。
- `TestReminderReceiverIsolation`：先装空 store / 无 AlarmManager 的 no-op `ReminderScheduler` Koin（吞掉已在途的 TIME_SET，且不碰用户闹钟），再 disable 这一个 receiver；`finish()` 恢复 persist 的原 `enabledSetting`。不碰无障碍、不碰 WorkManager `RescheduleReceiver`。shell `pm disable`/`pm default-state` 在本机抛 `Shell cannot change component state`，不能当隔离手段。
- Compose：`createEmptyComposeRule` + `assumeUnlocked` / `assumeS9FixtureApplication`。
- 生产 OCR / 真 controller 夹具仍待解锁跑。

`d2ad26d88` 两行生产 Koin 守卫已由 `ecab66e9b` 撤销。那两行只覆盖 plain test Application 不启动 Koin 的路径，**不能**写成已证实的生产 boot/时间变化缺陷修复。

### 11.2 锁屏可跑 class（完整 raw 日志）

`/tmp/amber-s9-connected-core.log` 止于 `ScreenOcrDeviceTest` START，无 `INSTRUMENTATION_CODE`，**不算** suite 通过。本轮逐 class `am instrument -w -r`，要求文件含 `INSTRUMENTATION_CODE: -1`、`OK (N tests)`、adb exit 0。Gradle `--console plain`。不把中途 `STATUS_CODE: 0` 当整 suite 通过。

当时 APK 仍含后来撤销的生产守卫与反射 runner。结果只证明那些 class 能跑完，**不再**当作生产守卫的证据：

| class | log | OK | INSTRUMENTATION_CODE | exit |
| --- | --- | --- | --- | --- |
| `AppDatabaseMigrationTest` | `/tmp/amber-s9-class-migration.log` | 7 tests / 0.75s | -1 | 0 |
| `JevSettingsDeviceTest` | `/tmp/amber-s9-class-jevsettings.log` | 2 tests / 0.023s | -1 | 0 |
| `ConversationActivityStoreDeviceTest` | `/tmp/amber-s9-class-activity-store.log` | 1 test / 0.185s | -1 | 0 |
| `PerAppSuccessPathDeviceTest`（ART 假 snapshot） | `/tmp/amber-s9-class-perapp-art.log` | 4 tests / 0.228s | -1 | 0 |
| `VideoContainFitDeviceTest`（补充，非 Dialog） | `/tmp/amber-s9-class-containfit.log` | 2 tests / 0.609s | -1 | 0 |
| `ScreenOcrDeviceTest`（离屏部分证据） | `/tmp/amber-s9-class-ocr-offscreen.log` | 4 tests / 2.537s | -1 | 0 |

`PerAppSuccessPathDeviceTest` 第一次 120s 内 log 0 字节、host perl alarm exit 142。`am force-stop` + 一次 `KEYCODE_WAKEUP` 后重跑成功；**只认重跑这份完整 log**。无障碍结束后仍为 DSH。撤销生产守卫后的隔离复跑见 §11.5。

### 11.3 前台真机（用户已解锁，2026-11-01）

用户明确回复「已解锁」。未再请求解锁确认，未清 PIN，未清用户数据。双 runner 仍为 `c3b66eb25`；生产 reminder 守卫仍为 `ecab66e9b` 撤销态。

设备：Xiaomi `2608BPX34C` / `lhasa` / Android 17 API 37 / USB `0A07846009983540`。封面屏 1168×1712 ON，内屏 OFF。本轮会话开始时 `enabled_accessibility_services` **为空**（不是 DSH）；临时 Amber 无障碍用完后恢复为空。亮屏曾延长 `screen_off_timeout=1800000` / `stay_on_while_plugged_in=7`，结束恢复 `60000` / `0`。动画 scale 保持 1。`ReminderRescheduleReceiver` 保持 DEFAULT（不在 enabled/disabled 列表）。未改 WorkManager。宿主一律 `ChatDeviceFixtureRunner`。每 class 单独 `am instrument -w -r`。

夹具迁到 `app/src/debug` 且 `exported=true`，shell `am start -W --display 0 --windowingMode 1 -f 0x10008000` 打 MIUI BAL 102。Compose Dialog 的 `VideoView` 从 `WindowManagerGlobal` 根扫描。ON_STOP 用 debug `S9LifecycleCoverActivity` 盖住宿主（不 `moveTaskToBack`，避免 `excludeFromRecents` 销毁）。生产 `VideoPlaybackSession.dispatchPrepared` 只在**首次** prepare 且前台时 auto-start；二次 prepare（surface 重建）不续播。

| class | log | OK / skip | CODE | exit | 证据 |
| --- | --- | --- | --- | --- | --- |
| `ScreenOcrProductionCaptureDeviceTest` debug | `/tmp/amber-s9-fg-ocr-debug.log` | 2 tests / 6.117s | -1 | 0 | canvas `琥珀验证完成`；webview `號珀验证完成`；`screenshotSource=accessibility_screenshot`；AX 空 |
| `ScreenOcrProductionCaptureDeviceTest` profileable | `/tmp/amber-s9-fg-ocr-profileable.log` | 2 tests / 6.698s | -1 | 0 | 同包非 debuggable；`hasFocus=true width=1168`；AX 空；树 `FrameLayout 0,0,1168,1712` 无夹具词。首次 canvas 曾在小窗 OCR 失败，等待焦点/宽度后重跑通过 |
| `ConversationActivityDeviceTest` | `/tmp/amber-s9-fg-conversation.log` | 1 test / 1.549s | -1 | 0 | `/tmp/amber-s9-evidence/conversation/activity-panel.png` 四态；`activity-panel-click.txt` `opened=wait-conv title=Wait chat kind=waiting` |
| `VideoPlaybackDeviceTest` | `/tmp/amber-s9-fg-video.log` | 2 tests / 5.645s | -1 | 0 | 竖 737×1310 / 横 1079×607 / 方 1080×1080 均 playing；`on-start.txt` `playing=false views=1`；`controls.txt` `pause=ON_STOP resume=play-control close=silent row12=displayed` |
| `JevScreenDeviceTest` | `/tmp/amber-s9-fg-jev-native.log` | 3 tests / 1.456s；其中 2 skip | -1 | 0 | `nativeSnapshotsRejectStaleAndVerifyLocalActions` STATUS 0。`realJevRunsNativeGoalThroughProductionTool` / `realJevBilibiliSearch` STATUS -4 skip |
| `PerAppRealControllerDeviceTest` | `/tmp/amber-s9-fg-perapp-real.log` | 3 tests / 3.284s | -1 | 0 | 同包新 snapshot、跨包隔离、接管去重/清除。未操作真实业务包 |

截图/OCR 文字：`/tmp/amber-s9-evidence/`（不进 git）。结束还原 debug APK：`/tmp/amber-s9-full-arm64-debug.apk`，`application-debuggable`，`RouteActivity` 前台，用户会话仍在。`11-01 11:20` 之后 graphite `*:F` 无 FATAL。

### 11.4 本轮完成 / 停等审核

**已经完成：** 报告矩阵更正；`ecab66e9b` 撤销生产守卫；双 runner + androidTest 隔离；锁屏 8 项；§11.3 前台 6 class；视频 first-prepare 生产返工；最终 DEBUGGABLE APK 保留用户数据安装启动；临时亮屏/无障碍已恢复。

**不停在用户动作。** 主代理最终审核结果见 §12。未 push / 未开 PR。三个用户未跟踪文档未动。

### 11.5 撤销生产守卫后的锁屏隔离复跑

命令（plain）：

```bash
adb shell am instrument -w -r -e class app.amber.agent.JevSettingsDeviceTest \
  app.amber.agent.graphite.test/app.amber.agent.AmberAgentAndroidTestRunner
```

命令（fixture）：

```bash
adb shell am instrument -w -r -e class app.amber.agent.JevSettingsDeviceTest \
  app.amber.agent.graphite.test/app.amber.agent.ChatDeviceFixtureRunner
```

本轮从 **DEFAULT（enabledSetting=0）** 冷启动复跑。`setComponentEnabledSetting` 单独不够：disable 返回后约 80ms `onReceive` 仍跑。no-op `ReminderScheduler` Koin（空 store、无 AlarmManager）让已在途 TIME_SET 不崩、不改用户闹钟，再 disable/restore 这一只 receiver。

| 项 | log | OK | CODE | exit | 说明 |
| --- | --- | --- | --- | --- | --- |
| plain `JevSettingsDeviceTest` | `/tmp/amber-s9-iso-plain-jev.log` | 2 tests / 0.019s | -1 | 0 | `AmberAgentAndroidTestRunner`；`newApplication app=android.app.Application`；logcat `original enabledSetting=0 (current=0)` → disable → `restored enabledSetting=0` |
| plain `PerAppSuccessPathDeviceTest` | `/tmp/amber-s9-iso-plain-perapp.log` | 4 tests / 0.208s | -1 | 0 | 同上 runner；force-stop 后 DEFAULT 冷启动未崩 |
| fixture `JevSettingsDeviceTest` | `/tmp/amber-s9-iso-fixture-jev.log` | 2 tests / 0.008s | -1 | 0 | `ChatDeviceFixtureRunner`；`newApplication app=…ChatDeviceFixtureApplication`；同样 restore 0 |
| reminder 接收器 | host dumpsys | — | — | — | 每 class 跑后 `disabledComponents` 只剩 WorkManager `RescheduleReceiver`。`ReminderRescheduleReceiver` 不在 disabled/enabled 列表（DEFAULT=0）。`SystemJobService` 仍在 `enabledComponents` |
| 无障碍 | `enabled_accessibility_services` | — | — | — | 仍仅为 DSH |

`firstInstallTime=2026-10-28 19:02:18` 保留。`lastUpdateTime=2026-11-01 05:46:18`（本轮只重装 androidTest APK）。完整 host 记录：`/tmp/amber-s9-iso-run3.log`。前台项仍等解锁，未轮询 PIN。

## 12. 主代理最终验收（2026-11-01）

五阶段代码及本地真机验收通过；联网 realJev 验证保留为未完成项，不声称外部服务端到端已通过。

- 独立核对 §11.3 六组 raw 日志，均正常结束 `INSTRUMENTATION_CODE: -1`：11 项实际通过，2 项 realJev 因无 key 跳过。
- 查看非 debuggable 前台 OCR 原始输出：`screenshotSource=accessibility_screenshot`、`BITMAP_OCR`、中文/Latin 及文字区域 bounds；WebView 个别字形误识别的限制仍保留。
- 查看四态活动面板和竖屏视频截图，并核对点击、后台暂停、前台手动恢复、关闭后 row-12 及真实 controller 接管/清除的证据。
- 独立复跑最终视频 session 回归：`./gradlew :app:testFullDebugUnitTest --offline --tests '*VideoPlaybackSessionTest'` 成功，日志 `/tmp/amber-final-video-review.log`。最终生产增量仅视频首次 prepare 自动播放修复；提醒生产守卫已撤销。
- 全量 JVM 既有 2 项 OAuth 并发失败、15 项 skip 与本地化既有缺口仍单列；本验收不将这些记为通过。
- APK 已保留用户数据安装启动。未 push、未开 PR；工作区仅原有三份未跟踪计划，构建产物和临时证据未入库。

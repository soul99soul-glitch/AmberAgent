# Android 选定差异补全 · P2 竖屏视频播放适配执行记录（2026-11-01）

> 本文件是 P2 竖屏视频专用记录，不修改 `2026-10-31-android-selected-parity-handoff.md`。
> 本轮只做 handoff §7。不做 per-App 记忆、不安装/启动/操作真机。
> `cb52fa0ba` 独立代码验收未通过（§10）；返工 `33deefa7d` 主代理代码验收通过（§9）。真机渲染/触控/后台无声仍待最后统一验证，不能标完全完成。

## 1. 基线

- 工作分支：`codex/android-p0-ocr`
- 父提交：`b42ea9c2a fix(android): P2 Jev 脚本判断消费与核验独立路径`（主代理代码验收通过，见 P2 Jev 记录 §9；设备仍待统一验证）
- 三个既有未跟踪文档保持不提交：
  - `docs/plans/2026-10-26-chat-history-performance-phase2.md`
  - `docs/plans/2026-10-31-android-selected-parity-handoff.md`
  - `docs/plans/2026-10-31-ios-android-feature-diff.md`
- 未 `reset` / `clean` / `rebase`，未 push / 未开 PR。
- 未引入 media3 / ExoPlayer / 新 Gradle 依赖。

## 2. 调查：改前 `VideoPlaybackDialog` 实际行为

生产代码在 `ChatMessageMessagePartsBlock.kt`（现已抽到 `media/VideoPlaybackDialog.kt`）。改前现场，不以文档为准：

| 行为 | 改前 |
| --- | --- |
| 打开 | 时间线芯片点击才设 `playing=true`，才组合 Dialog / `VideoView` |
| 显示比例 | `Surface.fillMaxWidth().aspectRatio(16f/9f)`，忽略 prepared 尺寸 |
| 内部测量 | 普通 `VideoView` + `fillMaxWidth`；Compose 把 view 铺满 16:9，竖屏大黑框，横屏碰巧接近 |
| 控制条 | 未挂 `MediaController`，无播放/暂停 overlay |
| 失败 | 文案 +「用其他应用打开」；**无重试** |
| 关闭 | `onDismiss` / `AndroidView.onRelease` / `DisposableEffect.onDispose` 调 `stopPlayback` |
| 退后台 | `ON_STOP` → `pause()`；无 `ON_START` 自动 resume |
| prepared | `onPrepared` **无条件 `start()`**，即使宿主已 `ON_STOP` |
| URL 变化 | `remember(url)` 重建部分状态；`DisposableEffect` 只 key `lifecycleOwner`，旧 listener 可能残留 |

时间线芯片保持：固定 44.dp 缩略图、`ContentScale.Crop` 1:1，封面到位前后尺寸不变。本轮未改芯片。

## 3. 数学与旋转语义

`VideoDisplaySize.kt`：

- `effectiveVideoSize(reportedW, reportedH, rotation)`：`<=0` 得 `0×0`；90/270 交换轴一次；0/180 保持。
- `normalizeRotationDegrees`：模 360，负值回绕，就近对齐到 `{0,90,180,270}`。
- `rotationToApplyOnce`：**最多转一次**。若 coded 尺寸已知且与 reported 已经轴对调（player 已把 90/270 吃进 `getVideoWidth/Height`），返回 0，避免再转 90°。
- `resolvedVideoContentSize`：无效尺寸才回落 **16:9**。
- `displaySizeFromPlayerMetrics`：把上述规则接到 `MediaPlayer` 的 reported + `MediaFormat.KEY_WIDTH/HEIGHT/ROTATION`。
- `containFitVideo`：contain，不裁切不拉伸；结果 `width≤availableW` 且 `height≤availableH`。竖屏用高度限制，禁止 `width*(h/w)` 撑破 Dialog。
- `videoDialogAvailablePx` / `fitPlayerInDialog`：窗口减去 system insets 与双侧 extra padding。

生产接线：`MediaPlayer.getVideoWidth/Height` + 视频轨 `MediaFormat`（缺省 0）。`ContainVideoView.onMeasure` 用同一套 contain-fit，外层 Compose `Surface` 也按 fitted 像素设宽高，避免只改 Surface、View 内部仍被铺满拉伸。

## 4. 生命周期与控制

每个 `VideoView` 实例对应一个 `VideoPlaybackSession`（`remember(url, retryNonce)`）。`release()` 幂等，失败 / 关闭 / retry / URL / `onRelease` / `onDispose` 都走它。release 之后 `dispatchPrepared` / `dispatchVideoSizeChanged` **不再调用**读播放器的 lambda，也不 `start()`。新 view 必须 new session，旧 session 作废不影响新实例。

`ContainVideoView.releasePlayback()`：摘掉 bound `MediaPlayer` 的 size listener、VideoView prepared/error/completion、`setMediaController(null)`，再 `stopPlayback()`。

前后台：

- 前台 prepared：自动 `start()`（点击芯片即播）。
- `ON_STOP`：`pause()`，playing=false。**不**在 `ON_START` 自动 `start()`，避免退后台回来偷偷出声。
- prepared 在 STOP 期间落定：不 start；回到前台用户点播放条恢复。
- 播放条：Compose play/pause，48.dp 从可用高度里扣掉，竖屏/极矮槽内仍可达。外层全屏 clickable 只关 scrim；播放器面板与控制条 `consumeClicks`，点视频/按钮不关弹层。

## 5. 失败与控制：前后对照

| | 原始 Dialog | `cb52fa0ba` | 本返工 |
| --- | --- | --- | --- |
| 控制条 | 无 MediaController、无 Compose 播放/暂停 | 仍无 | Compose 播放/暂停/恢复 |
| 失败 | 仅外部打开 | 外部打开 + `R.string.retry` | 同左 |
| `ON_START` | 不 resume | 只 `allowed=true`，prepared-in-background 无法再播 | 不自动 resume；前台点播放 |
| release | stopPlayback | onRelease 不清 session | 每 view session.release + 清 size listener |

未加 media3。新增 `chat_video_play` / `chat_video_pause` 六套 locale。

## 6. 关键文件

生产：

- `app/src/main/java/app/amber/feature/ui/components/media/VideoDisplaySize.kt`
- `app/src/main/java/app/amber/feature/ui/components/media/VideoPlaybackSession.kt`（替换已删的 `VideoPlaybackStartGate`）
- `app/src/main/java/app/amber/feature/ui/components/media/ContainVideoView.kt`
- `app/src/main/java/app/amber/feature/ui/components/media/VideoPlaybackDialog.kt`
- `app/src/main/java/app/amber/feature/ui/components/message/ChatMessageMessagePartsBlock.kt`（芯片未改）
- 六套 `strings.xml`（`chat_video_play` / `chat_video_pause`）

测试：

- `app/src/test/java/app/amber/feature/ui/components/media/VideoDisplaySizeTest.kt`
- `app/src/test/java/app/amber/feature/ui/components/media/VideoPlaybackSessionTest.kt`
- `app/src/test/java/app/amber/feature/ui/components/media/ContainVideoViewTest.kt`

无新依赖、无 Room / 设置迁移、无 media3。

## 7. 测试命令与结果

首轮 `cb52fa0ba`（比例 27 项通过，控制/release 未过，见 §10）。返工后：

```bash
./gradlew :app:testFullDebugUnitTest --offline \
  --tests 'app.amber.feature.ui.components.media.VideoDisplaySizeTest' \
  --tests 'app.amber.feature.ui.components.media.VideoPlaybackSessionTest' \
  --tests 'app.amber.feature.ui.components.media.ContainVideoViewTest'
# 3 suites / 36 tests / 0 failures / 0 errors / 0 skipped
# VideoDisplaySize 20 + Session 12 + ContainVideoView 4
# /tmp/amber-p2-video-rework-tests.log  BUILD SUCCESSFUL

./gradlew :app:compileFullDebugKotlin --offline
# /tmp/amber-p2-video-rework-compile.log  BUILD SUCCESSFUL
```

比例测试保留。Session 覆盖：前台 prepared 自动 start；STOP 后 prepared 不 start；`ON_START` 不自动 start、用户 play 才恢复；release 后迟到 prepared/size **零次**读播放器、不 start；旧 session error 不污染新 url/retry；queued post 在 release 之后 Ignored。

未跑 `:app:assembleFullDebug`，未安装真机（本轮 Paseo 明确禁止）。

## 8. 最终真机待验点

本轮不安装、不启动、不操作真机。统一 APK 时至少验：

1. **竖屏视频**：弹层按真实比例完整显示，无固定 16:9 大黑框，无裁切、无拉伸；播放/暂停条在 insets/横屏/小窗内可点。
2. **横屏 / 方形**：完整显示。
3. **关闭**：关 Dialog 后无继续出声。
4. **退后台**：`ON_STOP` 暂停无出声；回到前台**不**自动出声；点播放可恢复。
5. **prepared 在后台落定**：回前台画面停住，点播放才出声。
6. **点播放器/控制条不关弹层**；点 scrim 关闭。
7. **时间线**：返回后滚动位置不被抢走；芯片 44.dp 占位不跳。

## 9. 主代理代码验收通过（`33deefa7d`）

主代理独立核查 Compose 播放/暂停/恢复、STOP 后手动播放、release session 使迟到回调不再 read/start；独立复跑 `VideoDisplaySizeTest` / `VideoPlaybackSessionTest` / `ContainVideoViewTest` 36 tests 全通过（`/tmp/amber-video-rereview.log`）。

代码验收通过。真机渲染、触控、后台无声仍待最后统一验证，**不能标完全完成**。后续阶段不再改视频实现，除非验收明确返工。

## 10. 首轮代码验收未通过（`cb52fa0ba`）

主代理复跑比例三套 27 项通过（`/tmp/amber-video-review.log`），fit 无阻塞。未过：

1. 无 MediaController / Compose 控制；`ON_START` 不 start 也无手动 resume，prepared-after-STOP 只能关开。
2. `onRelease` 未作废 session，failed 卸掉 AndroidView 后迟到 prepared 仍可能读已释放的 MediaPlayer。

本返工针对这两项。比例测试保留。

# Cold Start Baseline — 2026-05-15

> Phase 0 末（commit `09c012cd`）冷启动性能基线。后续每阶段对比这个数据。

## 测试环境

| 项 | 值 |
|---|---|
| 设备 | Nothing A069 |
| SoC | Qualcomm Snapdragon 7s Gen 4 ("volcano") |
| 处理器档位 | 中端 |
| Android | 16 (SDK 36) |
| ABI | arm64-v8a |
| LCD density | 480 |
| App versionName | 1.8.17 |
| App versionCode | 349 |
| APK | 92 MB (universal) |
| 数据状态 | 干净安装（无 Provider 配置、无对话历史） |

## Trace

- 文件: `docs/perf-baseline/2026-05-15/cold-start.pftrace` (66 MB)
- 时长: 30 秒
- 总 slices: 681,241
- Perfetto v49.0
- atrace categories: view / wm / am / gfx / input / binder_driver / atrace_apps=me.rerere.amberagent.notion
- 用户操作:
  - t≈2s: 点桌面图标启动 app（冷启动）
  - t≈4s: 看到 app 主界面
  - t≈10-25s: 进 settings 页 / 返回 launcher / 切换页面（"复现切换界面卡顿"）

## 1. 冷启动 timeline (关键指标)

| 阶段 | ts (ms) | 耗时 (ms) |
|---|---|---|
| Start proc | 17,375,364 | 21.7 |
| **APK dex load** | 17,375,481 | **2354.5** ⚠️ |
| publishContentProviders | 17,378,553 | 0.3 |
| launching: me.rerere.amberagent.notion (start) | 17,381,313 | — |
| launchingActivity#3:completed-cold | 17,384,636 | — |
| **冷启动总时长** | — | **3,323 ms** |

**TTFP（首屏可见）≈ 3.3 秒**，在 Snapdragon 7s Gen 4 上属于 **慢**。Google Play "Cold start guideline" 是 < 1.5s 算良好、< 0.5s 算优秀。

## 2. 冷启动主线程 top hotspots（> 5ms 的 slice）

| Rank | Slice | 耗时 (ms) | 备注 |
|---|---|---|---|
| 1 | `base.apk` dex 加载 | **2354.5** | PackageManager 直接加载 APK，跟 92MB 体积 + dex 数量正相关。降低需要 Phase 1-3 的代码瘦身 + Baseline Profile |
| 2 | `fire-cls` (Firebase Crashlytics) | 193.3 | Firebase Crashlytics 启动 |
| 3 | `fire-sessions` (Firebase Sessions) | 179.9 | Firebase Sessions 启动 |
| 4 | `Startup` (androidx.startup) | 104.5 | 跑所有 `Initializer.onCreate` |
| 5 | `Firebase` | 83.5 | Firebase Core init |
| 6 | `Runtime` | 80.3 | Android Runtime 初始化（非 app code） |
| 7 | `PlatformInitializer` | 68.6 | 自定义 initializer (源码 grep app/ai/common 未找到 — 可能在 androidx 内部) |
| 8 | `fire-sessions-component` | 30.4 | Firebase Sessions component |
| 9 | `makeApplication` | 30.2 | Application.onCreate |
| 10 | `EmojiCompatInitializer` | 24.0 | androidx.emoji2 字体加载 |
| 11 | `fire-rc` (Firebase Remote Config) | 14.5 | Firebase RC |
| 12 | `ProcessLifecycleInitializer` | 14.0 | androidx 进程生命周期 |
| 13 | `fire-installations` | 9.2 | Firebase Installations API |
| 14 | `setSystemFontMap` | 8.7 | 系统调用，不可控 |
| 15 | `ProfileInstallerInitializer` | 5.9 | androidx Baseline Profile installer |

**Firebase 全套合计 ~480ms 主线程时间**（fire-cls 193 + fire-sessions 180 + Firebase 84 + fire-sessions-component 30 + fire-rc 14 + fire-installations 9）。这是冷启动 3.3s 里第二大块（仅次于 dex 加载）。

**冷启动期间没看到** jLatexMath / Mermaid / jmDNS 在主线程出现（top 25 内）—— 怀疑这些已经懒加载了，或者只占很少 main-thread 时间（Phase A 再 deep dive 一次确认）。

## 3. 帧率统计（30s 完整 trace）

| 指标 | 值 | 评估 |
|---|---|---|
| 总帧数 | 1,784 | — |
| 60fps jank (> 16.6ms) | **175 (9.8%)** | 旗舰应 <2%，中端 7-8% — **偏高** |
| 严重 jank (> 33ms) | **30 (1.7%)** | — |
| 最慢帧 | **1,079 ms** | 冷启动期间首帧 |
| 第 2 慢 | 226 ms | t≈17386 (启动后用户首次交互) |
| 第 3 慢 | 216 ms | t≈17388 |

启动后用户操作期间出现的 100ms+ jank 帧（>10 个）在 t = 17386–17396 ms 范围（启动完成后约 3–13 秒）—— 这跟"切换界面卡"的报告吻合。

## 4. ⚠️ 切换界面卡顿 — 数据现状

```
主线程后 27 秒（用户操作期间）单个 slice > 5ms 数量: 0
RenderThread 后 27 秒单个 slice > 2ms 数量: 0
```

但 frame timeline 显示这期间有 ~10 个 100ms+ 的 jank frame。两者矛盾的合理解释：

1. **卡顿不是 main-thread CPU bound** — 没有单一阻塞操作
2. **可能是 Compose 高频重组** — `Recomposer:animation` 共 396 次，总 512ms（虽然单次 max 7.5ms，但累积频繁触发 + 帧 deadline miss）
3. **可能是 GPU side** — RenderThread CPU 端没活但 GPU 命令堆积
4. **可能是 binder 等待 system_server**（atrace 没启用 `binder_lock` category 看不到）
5. **可能是 JIT 编译**（首次跑某些路径需要编译，JIT 在后台但占 CPU 影响主线程调度）

**这条线索是 Phase A 的重点 deep-dive**（启用更多 atrace category + 录制更长 trace + 分析 JIT 编译耗时）。Phase 0 只建立 baseline 数字，不解决根因。

## 5. 备份：所有 atrace categories 启用清单

```
linux.ftrace:
  - sched/sched_switch
  - sched/sched_waking
  - power/cpu_frequency
  - power/cpu_idle
atrace:
  - view / wm / am / gfx / input / binder_driver
  - atrace_apps: me.rerere.amberagent.notion
android.surfaceflinger.frame
android.surfaceflinger.frametimeline
```

**Phase A 建议加**：`am` 已有但加 `pm`、`ss`（systemserver）、`hal`、`res` (resource loading)、`dalvik` (JIT/GC)、`rs`、`webview`。同时启用 process_stats / heap_profile 看 JIT/GC 行为。

## 6. 对比基线（后续每个 Phase 末跑一次相同 config）

填表（每个 Phase 完成后填上对应数字）：

| Phase | Date | Cold start TTFP (ms) | Jank rate (%) | dex load (ms) | Firebase total (ms) | APK size |
|---|---|---|---|---|---|---|
| Phase 0 (此 baseline) | 2026-05-15 | **3,323** | **9.8** | 2,355 | ~480 | 92 MB |
| Phase 1 末 | | | | | | |
| Phase 2 末 | | | | | | |
| Phase 3 末 | | | | | | |
| **Phase A 开始**（下半场） | | | | | | |

## 7. 立刻可做的快胜（如有人想顺手优化，不属 Phase 0 范围）

- **Firebase 移到 background thread**：通过自定义 Initializer + Coroutine 把 fire-cls / fire-sessions / fire-rc 移出主线程 onCreate。预估省 ~300–400ms 冷启动。
- **app/src/main/baselineProfiles**：项目有 baseline buildType 但没有实际生成的 profile。生成 Baseline Profile（macrobenchmark）能让 dex 加载快 30%（按 Google 数据）。
- **EmojiCompatInitializer 改 lazy**：24ms 不大但 trivial 改动。

**这些都放到 Phase 2 末或 Phase C，不在当前重构范围**。

## 8. 待后续补的 baseline trace

- **Trace 2 滚动**: 需要先在 app 内累积 50+ 消息的对话
- **Trace 3 流式响应**: 需要配 Provider + API key
- **Memory Profile**: 需要 Android Studio Profiler（非 adb），看常驻 heap 和 jmDNS / WebView / jLatexMath 是否常驻

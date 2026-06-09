# AmberAgent 性能 Baseline — 2026-05-15

> Phase 0 终点的性能基线，作为后续每阶段的对比参照 + 驱动下半场 Phase A/B/C 优先级。

## 上下文

- App 版本: 1.8.17 (versionCode 349)
- Commit: `f62f3843` (Phase 0 末)
- APK: 92MB (Phase -1 后 95MB，本次 -3MB)
- 设备建议: 你提到"卡顿的低端机"。当前连接的是 `3B164901CEF00000`（中高端），如有更老/低端机，优先用那台。

## 三个必须录制的 trace

每个 trace 录完保存到此目录：`docs/perf-baseline/2026-05-15/`。文件命名见下。

### Trace 1 — 冷启动

**目标**：从图标点击到首帧渲染、再到可交互的耗时。

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
PKG=me.rerere.amberagent.notion

# 1. 强杀已运行实例
$ADB shell am force-stop $PKG
$ADB shell pm clear-startup-times $PKG 2>/dev/null

# 2. 启动 Perfetto 录制（10 秒覆盖冷启动）
$ADB shell perfetto -c - --txt -o /data/misc/perfetto-traces/cold-start.pftrace <<'CONFIG'
buffers: { size_kb: 65536 }
duration_ms: 10000
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
    }
  }
}
data_sources {
  config {
    name: "android.surfaceflinger.frame"
  }
}
data_sources {
  config {
    name: "android.surfaceflinger.frametimeline"
  }
}
CONFIG

# 3. 在 perfetto 启动后立即点击桌面图标启动 app（手动）

# 4. 等录制结束（10s）后拉到本地
$ADB pull /data/misc/perfetto-traces/cold-start.pftrace docs/perf-baseline/2026-05-15/cold-start.pftrace
```

**记录的指标**（用 [ui.perfetto.dev](https://ui.perfetto.dev) 打开 trace）:
- TTFP (Time To First Frame, 从 launch intent 到第一个 onDraw)
- TTI (Time To Interactive, 主线程空闲 100ms 起算)
- 主线程阻塞 hotspot（class.method）
- 冷启动期间 GC 次数

### Trace 2 — 聊天列表滚动 10 秒

**目标**：长列表滚动卡顿来源。

```bash
# 1. 启动 app，进入一个有 50+ 消息的对话（如没有，先发 30 条历史消息再开始）
$ADB shell am start -n $PKG/.RouteActivity

# 2. 启动 Perfetto 录制 12 秒
$ADB shell perfetto -c - --txt -o /data/misc/perfetto-traces/scroll.pftrace <<'CONFIG'
buffers: { size_kb: 65536 }
duration_ms: 12000
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
    }
  }
}
data_sources { config { name: "android.surfaceflinger.frame" } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "android.input.inputevent" } }
CONFIG

# 3. 在 trace 开始后立即开始上下滚动（手动，连续 10 秒）

# 4. 拉到本地
$ADB pull /data/misc/perfetto-traces/scroll.pftrace docs/perf-baseline/2026-05-15/scroll.pftrace
```

**记录的指标**:
- 帧率 (FPS, target 60 / 120)
- Janky frame 百分比
- 每帧 deadline missed 数量
- Recomposition hotspot（哪些 Composable 重组最频繁）

### Trace 3 — 流式响应渲染

**目标**：SSE 流式响应中 token 渲染 + Markdown 解析的主线程压力。

```bash
# 1. 启动 app，选一个 provider，输入一个会产生 ~500 token 输出的问题（如"详细介绍 Kotlin 协程"）

# 2. 启动 Perfetto 录制 30 秒
$ADB shell perfetto -c - --txt -o /data/misc/perfetto-traces/streaming.pftrace <<'CONFIG'
buffers: { size_kb: 131072 }
duration_ms: 30000
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
    }
  }
}
data_sources { config { name: "android.surfaceflinger.frame" } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
CONFIG

# 3. 在 trace 开始后立即点击发送，等 30 秒完整捕获流式响应

# 4. 拉到本地
$ADB pull /data/misc/perfetto-traces/streaming.pftrace docs/perf-baseline/2026-05-15/streaming.pftrace
```

**记录的指标**:
- 流式期间帧率
- MessageStreamAccumulator 累积频率（已是 33ms batch）
- Markdown 解析每次开销（jetbrains/markdown 调用栈）
- 主线程上是否有 jLatexMath / Mermaid 初始化

## Memory Profile

Android Studio Profiler（不是 adb，需要 IDE）:
1. Profile > Memory > Record allocation tracking
2. 启动 app → 等到可交互
3. 开几个对话切换
4. 进设置页 → 返回主页
5. 停止录制，导出 .hprof 到此目录

**记录的指标**:
- 常驻 heap 大小
- 大对象 top 10
- jmDNS / WebView / jLatexMath 是否被实例化（plan v2 提到这几个怀疑是常驻浪费）

## 输出格式（每个 trace 都写一份）

录完后，在此目录创建 `cold-start.md` / `scroll.md` / `streaming.md` / `memory.md`，每份记录：

```markdown
# Cold Start Baseline — 2026-05-15

- 设备: Pixel/Xiaomi/...
- Android 版本:
- TTFP: 1234ms
- TTI: 2345ms
- 主线程 top 5 阻塞 (从 Perfetto Flame Graph):
  1. methodA (450ms)
  2. methodB (300ms)
  ...
- 异常发现 / 反直觉的 hotspot:
```

## 后续

这份 baseline 在以下时刻对比：
- Phase 1 末 — 数据层 + god class 拆分完，跑同样 3 个 trace
- Phase 2 末 — UI 拆完 + Compose 稳定性优化
- Phase 3 末 — 模块化收尾
- **Phase A**（下半场起点）— 基于干净代码重新跑 baseline，与本次对比，输出"剩余瓶颈清单"驱动 Phase B 候选模块

---

## 当前阻塞

- 用户需要决定测试机选哪台（推荐用"卡顿的那台老/低端机"，不是当前连的 3B164901CEF00000）
- 如不区分，用当前设备录也行，但要注明

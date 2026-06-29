# Board 采集 iOS 原生实现 — 实施计划

> 目标：让 iOS 能采集 Board 信号（时间/日历/聊天历史）、生成今日看板内容。

## 当前状态（HEAD `3e876389`）

### Board 架构总览

```
BoardScheduler (WorkManager 定时触发)
  → SignalAggregator (聚合所有信号源)
    → BoardSignalCollector (接口)
      ├── TimeAnchorSignalCollector     — 时间锚点（纯 Kotlin）
      ├── ChatHistorySignalCollector    — 聊天历史（Room DAO）
      ├── CalendarSignalCollector       — 日历（android.provider.CalendarContract）
      ├── FeishuMessageSignalCollector  — 飞书消息（飞书 API）
      ├── FeishuDocSignalCollector      — 飞书文档（飞书 API）
      └── AppUsageCollector             — 应用使用（Android UsageStatsManager）
  → BoardAgent (调用 LLM 生成看板内容)
  → BoardRepository (Room 持久化)

HotListScheduler (WorkManager 定时)
  → HotListRepository (Room)
  → 各 HotListProvider (RSS/JSON/自定义)
```

### 平台依赖分析

| 组件 | Android 原生 API | iOS 等价物 | KMP 可行? |
|---|---|---|---|
| **BoardScheduler** | `androidx.work.WorkManager` (11 个依赖) | `BGTaskScheduler` / `Timer` | ❌ 需 iOS 原生重写 |
| **CalendarSignalCollector** | `android.provider.CalendarContract` | `EventKit` (EKEventStore) | ❌ 需 iOS 原生重写 |
| **ChatHistorySignalCollector** | Room DAO (ConversationDao) | iOS 需自己的聊天持久化 | ❌ iOS 没有聊天 DB |
| **FeishuMessage/DocCollector** | 飞书 SDK (Android) | 飞书 API (HTTP，平台无关) | ⚠️ 部分（需 HTTP 客户端） |
| **TimeAnchorSignalCollector** | 纯 Kotlin | — | ✅ 可直接用 |
| **AppUsageCollector** | `UsageStatsManager` | iOS 无等价（隐私限制） | ❌ 无法实现 |
| **BoardAgent** | 1 个平台依赖 | OpenAIKmpProvider (已 KMP) | ⚠️ 需抽接口 |
| **BoardRepository** | Room DAO | iOS 需 SQLite/SwiftData | ❌ 需 iOS 原生 |
| **HotListScheduler** | `WorkManager` | `BGTaskScheduler` | ❌ 需 iOS 原生 |
| **HotListRepository** | Room DAO | iOS SQLite | ❌ 需 iOS 原生 |

### 已在 commonMain + 已 export

```
TodayBoardSetting        // ✅ feature/board/api commonMain，已 export（11 hits）
BoardSignalSourceType    // ✅ feature/board/api commonMain
HotListModels            // ✅ feature/board/api commonMain
HotListItem              // ✅ feature/board/api commonMain
```

### feature/board 模块结构

```
feature/board/
├── api/          ← KMP ✅ (BoardSettings + HotListModels)
└── impl/         ← Android-only (android.library)
    ├── agent/    ← BoardAgent (LLM 生成)
    ├── aggregator/ ← SignalAggregator
    ├── collector/  ← 6 个信号采集器
    ├── hotlist/    ← 热榜 + DeepRead (大量文件)
    └── worker/     ← BoardScheduler (WorkManager)
```

**impl 模块约 30+ 文件**，重度 Android 依赖。

### iOS 当前状态

- BoardView 已读 `sharedSettings.agentRuntime.todayBoard`（只读展示，Slice 26）
- BoardSettingsView 已读 todayBoard 字段（只读展示，Slice 33）
- **无信号采集、无看板生成、无调度**

## 实施方案

Board 是 5 项中**最大的工程**（30+ 文件 + 6 个信号源 + WorkManager + Room + DeepRead）。无法整体 KMP 化。建议分阶段实施。

### 阶段 1：Board 接口 + 时间信号（最小可行）

**目标**：证明 Board 采集链在 iOS 上能跑（即使只有时间信号）。

**步骤 1**：在 feature/board/api commonMain 定义 Board 采集接口

```kotlin
// feature/board/api/src/commonMain
interface BoardSignalCollectorInterface {
    val sourceType: String
    suspend fun collect(context: BoardCollectContext): List<BoardSignal>
}

data class BoardCollectContext(
    val assistantId: String,
    val now: Long,
    val anchorTime: Long,
)

data class BoardSignal(
    val sourceType: String,
    val content: String,
    val timestamp: Long,
)

interface BoardAgentInterface {
    suspend fun generate(s_signals: List<BoardSignal>, setting: TodayBoardSetting): String
}
```

**步骤 2**：iOS iosMain 实现

```kotlin
// feature/board/src/iosMain（或 iosApp）
object IosBoardFactory {
    fun createCollectors(): List<BoardSignalCollectorInterface> {
        // iOS 能实现的信号源：
        // 1. TimeAnchorSignalCollector — 纯 Kotlin，直接用
        // 2. EventKitCollector — EventKit framework (EKEventStore)，需 iOS 原生
        // 3. ChatHistoryCollector — 需要 iOS 聊天持久化（当前没有）
        // AppUsageCollector — iOS 隐私限制，不支持
        // FeishuCollector — 需要 HTTP 客户端 + 飞书 API
        return [TimeAnchorCollector(), IosCalendarCollector()]
    }

    fun createAgent(): BoardAgentInterface {
        // 用 OpenAIKmpProvider 生成看板内容（和 Council 同模式）
    }
}
```

**步骤 3**：BoardView 加"生成今日看板"按钮（手动触发，不用 WorkManager）

**步骤 4**：结果展示

### 阶段 2：日历信号（EventKit）

**步骤**：iOS iosMain 实现 `IosCalendarCollector`
- 用 `EKEventStore` 读取日历事件
- 需要 `NSCalendarsUsageDescription` 权限
- 转换为 `BoardSignal` 格式

### 阶段 3：热榜（如果需要）

**步骤**：iOS iosMain 实现 `IosHotListCollector`
- 用 URLSession 拉 RSS/JSON 热榜源
- 解析（参照 Android HotListProvider）
- 持久化用 UserDefaults 或 SQLite

### 阶段 4：调度（如果需要）

**步骤**：iOS 用 `BGTaskScheduler` 替代 WorkManager
- 注册后台任务
- 定时触发采集

## 验证

- `git diff --check`
- `compileKotlinIosSimulatorArm64` BUILD SUCCESSFUL
- `xcodebuild` BUILD SUCCEEDED
- `ios_build_and_run` → BoardView 点"生成今日看板" → 看到时间信号 + 日历信号
- subagent review

## 风险

- **最大工程**：30+ 文件，6 个信号源，涉及 EventKit/WorkManager/Room/飞书 API
- **隐私权限**：日历需要用户授权，应用使用统计 iOS 不支持
- **DeepRead 子系统**：feature/board/impl/hotlist/deepread 有 ~15 个文件，重度 Android
- **建议分阶段**：先做阶段 1（接口 + 时间信号），验证链路通了再做日历/热榜

## 涉及文件

- 新建：`feature/board/api/src/commonMain/.../BoardInterfaces.kt`
- 新建：`feature/board/src/iosMain/.../IosBoardFactory.kt`（或 iosApp）
- 新建：`feature/board/src/iosMain/.../IosCalendarCollector.swift`（或 Kotlin iosMain）
- 改：`iosApp/iosApp/BoardView.swift`（生成按钮 + 结果展示）
- 不动：feature/board/impl（Android-only 保持不变）
- 不动：BoardScheduler/WorkManager（iOS 用 BGTaskScheduler 替代，阶段 4）

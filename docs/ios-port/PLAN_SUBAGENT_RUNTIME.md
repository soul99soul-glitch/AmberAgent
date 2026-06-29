# SubAgent 运行 KMP 化 — 实施计划

> 目标：让 iOS 能启动 SubAgent、管理运行、读取结果（start/read/wait/cancel），参照 feature:modelcouncil 的成功模式。

## 当前状态（HEAD `3e876389`）

### SubAgentManager 在 feature/subagent/src/main（Android-only）

**文件**：`feature/subagent/src/main/.../SubAgentManager.kt`（~360 行）

**构造参数**：
```kotlin
class SubAgentManager(
    context: Context,                              // ← android.content.Context
    private val appScope: AppScope,                 // ← 已 KMP ✅ (Slice 30)
    private val settingsStore: SettingsAggregator,  // ← Android-only (:core:settings)
    private val json: Json,                         // ← KMP ✅
    private val runner: SubAgentRunner,             // ← 纯 Kotlin 接口 ✅ (0 平台依赖)
    private val agentTaskStore: AgentTaskStore,     // ← 已 KMP ✅ (Slice 29)
    private val sessionAccessGrantStore: SessionAccessGrantStore,  // ← 已 KMP ✅ (feature:history commonMain)
)
```

**平台依赖（仅 SubAgentManager.kt）**：
| 行 | 依赖 | 替代方案 |
|---|---|---|
| 3 | `android.content.Context` | → `runDirPath: String`（参照 modelcouncil） |
| 29 | `java.io.File` | → `TaskFile`（参照 feature:task 已有 expect-actual） |
| 30 | `java.time.Instant` | → `kotlin.time.Clock.System.now()` |
| 31 | `java.util.concurrent.ConcurrentHashMap` | → `mutableMapOf` + `Mutex`（参照 modelcouncil） |

### feature/subagent 源文件平台依赖一览

| 文件 | 平台依赖 | 说明 |
|---|---|---|
| SubAgentManager.kt | **4 个**（Context/File/Instant/ConcurrentHashMap） | 核心，需改造 |
| SubAgentTranscriptReader.kt | **2 个**（File/RandomAccessFile） | transcript 读取 |
| SubAgentDefinitions.kt | **0** ✅ | 已在 api/commonMain（Slice 35 下沉） |
| SubAgentRunner.kt | **0** ✅ | 纯 Kotlin 接口 |
| SubAgentValidator.kt | **0** ✅ | 纯 Kotlin |
| SubAgentToolScope.kt | **0** ✅ | 纯 Kotlin |
| SubAgentReportTool.kt | **0** ✅ | 纯 Kotlin |
| SmartSubAgentNames.kt | **0** ✅ | 纯 Kotlin |

### 上游依赖模块 KMP 状态

| 模块 | KMP? | 说明 |
|---|---|---|
| `:feature:subagent:api` | ✅ KMP | SubAgentModels + SubAgentDefinitions |
| `:ai` | ❌ Android-only | ProviderManager（SubAgentRunner 的 Android 实现 用它） |
| `:core:model` | ✅ KMP | AssistantMemory 等 |
| `:core:settings` | ❌ Android-only | SettingsAggregator |
| `:core:app-infra` | ✅ KMP (Slice 30) | AppScope |
| `:core:agent-utils` | ✅ KMP | JsonInstant |
| `:core:ai:generation:api` | ✅ KMP | generation 接口 |
| `:feature:history` | ✅ KMP | SessionAccessGrantStore |
| `:feature:runtime:api` | ✅ KMP | runtime 接口 |
| `:feature:task` | ✅ KMP (Slice 29) | AgentTaskStore |

**关键发现**：`SubAgentRunner` 是**纯 Kotlin 接口**（0 平台依赖），和 `ModelCouncilTextRunner` 模式一样！Android 实现用 ProviderManager（:ai），iOS 可以用 OpenAIKmpProvider（已 KMP + 已在 modelcouncil 验证）。

### 已在 commonMain + 已 export 的 SubAgent 类型

```
SubAgentRuntimeSetting     // ✅ 已 export（11 hits）
SubAgentResult             // ✅ 已 export（11 hits）
SubAgentRun                // ✅ 已 export（39 hits）
SubAgentDefinition         // ✅ 已 export（SubAgentDefinitions 下沉，Slice 35）
SubAgentRunStatus          // ✅ 已 export
SubAgentMode               // ✅ 已 export
SubAgentOverride           // ✅ 已 export
SubAgentTaskSpec           // ✅ 已 export
```

### 与 Council 的对比（参照成功模式）

| 维度 | Council (已完成) | SubAgent (待做) |
|---|---|---|
| Manager 构造参数 | 7 个（抽 3 个接口） | 7 个（可抽同样模式） |
| 平台依赖 | Context/File/Instant/ConcurrentHashMap | **完全相同** |
| Runner 接口 | ModelCouncilTextRunner（纯 Kotlin） | SubAgentRunner（纯 Kotlin） |
| Android Runner 实现 | ProviderModelCouncilTextRunner（:ai） | Android SubAgentRunner impl（:ai） |
| iOS Runner 实现 | RealOpenAIModelRunner（iosMain） | 需新建（同模式） |
| AgentTaskStore | ✅ 已 KMP | ✅ 已 KMP |
| AppScope | ✅ 已 KMP | ✅ 已 KMP |
| SettingsAggregator | 抽 ModelCouncilSettingsSource 接口 | 可复用同接口或新建 |
| KMP 化路径 | feature/modelcouncil commonMain + iosMain | feature/subagent commonMain + iosMain |

## 实施步骤

### 步骤 1：抽接口到 commonMain（参照 modelcouncil 模式）

新建 3 个接口（和 modelcouncil 完全同构）：

```kotlin
// feature/subagent/api/src/commonMain
interface SubAgentSettingsSource {
    val settingsFlow: StateFlow<Settings>
}

interface SubAgentRunStorage {
    fun newTranscriptPath(runId: String): String
    fun appendEvent(path: String, line: String)
    fun transcriptExists(path: String): Boolean
}

// SubAgentRunner 已是接口，无需新建
```

### 步骤 2：SubAgentManager 主体移到 commonMain

改造（和 modelcouncil 完全同构）：
- `Context` → `runDirPath: String`
- `File` → `TaskFile`（复用 feature:task 的 expect-actual）
- `Instant.now()` → `Clock.System.now()`
- `ConcurrentHashMap` → `mutableMapOf` + `Mutex`
- `SettingsAggregator` → `SubAgentSettingsSource` 接口
- `synchronized` → `Mutex.withLock`
- `Dispatchers.IO` → `Dispatchers.Default`

### 步骤 3：feature/subagent build.gradle.kts 改 KMP

参照 feature/modelcouncil 模式：
```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
}
sourceSets {
    commonMain.dependencies {
        api(project(":feature:subagent:api"))
        api(project(":ai-core"))         // ← 替代 :ai
        api(project(":ai-provider-openai")) // ← iOS runner 用
        api(project(":core:app-infra"))
        api(project(":core:types"))
        api(project(":core:model"))
        api(project(":core:agent-utils"))
        api(project(":core:ai:generation:api"))
        api(project(":feature:history"))
        api(project(":feature:runtime:api"))
        api(project(":feature:task"))
    }
}
```

Android 实现（ProviderManager-based runner）移到 `:app`（参照 modelcouncil 模式）。

### 步骤 4：iOS iosMain 实现

```kotlin
// feature/subagent/src/iosMain
object IosSubAgentFactory {
    fun create(documentsDir: String): SubAgentManager {
        // 和 IosCouncilFactory 完全同构
        // 用 StubSettingsSource + RealOpenAISubAgentRunner + StubRunStorage
    }
}
```

`RealOpenAISubAgentRunner` 用 `OpenAIKmpProvider.generateText()`（和 modelcouncil 的 RealOpenAIModelRunner 同模式）。

### 步骤 5：export + iOS UI 接线

- `shared/build.gradle.kts` 加 `:feature:subagent` export
- SubAgentsView 加"启动 SubAgent"执行入口（参照 CouncilView runnerSection）
- 显示 start/runId + 结果

## 验证

- `git diff --check`
- `compileKotlinJvm` + `compileKotlinIosSimulatorArm64` BUILD SUCCESSFUL
- `shared linkDebugFramework` + Shared.h 确认 SubAgentManager 符号
- `xcodebuild` BUILD SUCCEEDED
- `ios_build_and_run` → SubAgentsView 启动 SubAgent
- subagent review（调用链闭环：SubAgentsView → IosSubAgentFactory → SubAgentManager.start → runner.generate → 结果）

## 风险

- **低风险**：和 modelcouncil 完全同构（已验证过 3 次同样的模式）
- `SubAgentTranscriptReader` 用 `RandomAccessFile` — 需抽 expect-actual（参照 TaskFile）
- `SubAgentToolScope` / `SubAgentReportTool` 可能依赖 runtime:api 的工具注册 — 需检查是否在 commonMain
- SubAgent 的 `start()` 输入结构（SubAgentTaskSpec）比 Council 简单

## 涉及文件

- 新建：`feature/subagent/api/src/commonMain/.../SubAgentInterfaces.kt`（3 个接口）
- 改/移：`feature/subagent/src/main/.../SubAgentManager.kt` → commonMain
- 改/移：`feature/subagent/src/main/.../SubAgentTranscriptReader.kt` → commonMain（抽 RandomAccessFile）
- 移：`feature/subagent/src/main/.../SubAgentRunner.kt` → commonMain（已 0 依赖）
- 移：`feature/subagent/src/main/.../SubAgentValidator.kt` → commonMain（已 0 依赖）
- 移：`feature/subagent/src/main/.../SubAgentToolScope.kt` → commonMain（已 0 依赖）
- 新建：`feature/subagent/src/iosMain/.../IosSubAgentFactory.kt`
- 改：`feature/subagent/build.gradle.kts`（KMP 化）
- 改：`shared/build.gradle.kts`（export）
- 改：`iosApp/iosApp/SubAgentsView.swift`（执行入口）
- Android 实现移到 `:app`（参照 modelcouncil）

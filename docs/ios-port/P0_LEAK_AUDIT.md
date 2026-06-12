# P0-T2 Android 依赖泄漏盘点报告

> 日期：2026-06-12
> 执行者：AI Agent

## 审计范围

- **Task 1**：14 个 "纯 JVM 候选" 模块（已使用 `kotlin("jvm")` 插件）
- **Task 2**：10 个 `android.library` 接口模块（检查是否仅为惯性挂载）

---

## Task 1：纯 JVM 候选模块

全部 14 个模块已使用 `kotlin("jvm")` 插件，**零 Android/AndroidX import**，无 Android-only 依赖。

| # | 模块 | 插件 | `import android.*` | `import androidx.*` | 结论 |
|---|---|---|---|---|---|
| 1 | `core/agent-runtime` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 2 | `core/agent-utils` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 3 | `core/ai-prompts` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 4 | `core/event` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 5 | `core/llm` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 6 | `core/sync/api` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 7 | `feature/history` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 8 | `feature/webview` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 9 | `feature/board/api` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 10 | `feature/chat/api` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 11 | `feature/deepread/api` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 12 | `feature/live/api` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 13 | `feature/office/api` | kotlin.jvm | — | — | ✅ 可直接转 KMP |
| 14 | `feature/terminal/api` | kotlin.jvm | — | — | ✅ 可直接转 KMP |

**结论：14/14 全部可直接转 KMP，无需任何代码修改。**

---

## Task 2：`android.library` 接口模块

| # | 模块 | Android Gradle 依赖 | Android Import | 结论 |
|---|---|---|---|---|
| 1 | `core/ai/api` | 无 | — | ✅ 惯性挂载，可直接转 KMP |
| 2 | `core/ai/generation/api` | 无 | — | ✅ 惯性挂载，可直接转 KMP |
| 3 | `core/ai/transformers/api` | 无 | `android.content.Context` | ⚠️ 少量泄漏需先剥离 |
| 4 | `core/automation/api` | 无 | `android.graphics.Rect` | ⚠️ 少量泄漏需先剥离 |
| 5 | `core/context/api` | 无 | — | ✅ 惯性挂载，可直接转 KMP |
| 6 | `core/memory/api` | 无 | — | ✅ 惯性挂载，可直接转 KMP |
| 7 | `feature/modelcouncil/api` | 无 | — | ✅ 惯性挂载，可直接转 KMP |
| 8 | `feature/runtime/api` | 无 | `android.util.Log` | ⚠️ 少量泄漏需先剥离 |
| 9 | `feature/subagent/api` | 无 | — | ✅ 惯性挂载，可直接转 KMP |
| 10 | `feature/tools/api` | 无 | — | ✅ 惯性挂载，可直接转 KMP |

---

## 泄漏详情

### 1. `core/ai/transformers/api` — `android.content.Context`

- **文件**：`src/main/kotlin/app/amber/core/ai/transformers/Transformer.kt`
- **行 3**：`import android.content.Context`
- **用法**：`TransformerContext` data class 中 `val context: Context` 字段，以及多个扩展函数的 `context: Context` 参数
- **剥离策略**：用平台无关接口或 typealias 替换 `Context`，Android 侧 actual typealias 到 `android.content.Context`，iOS 侧可传空或自定义上下文

### 2. `core/automation/api` — `android.graphics.Rect`

- **文件**：`src/main/kotlin/app/amber/core/automation/AccessibilityController.kt`
- **行 3**：`import android.graphics.Rect`
- **用法**：`AccessibilityTextMatch.bounds: Rect` 类型（行 45）
- **剥离策略**：自定义 `data class Rect(left, top, right, bottom)` 或 expect/actual，改动极小

### 3. `feature/runtime/api` — `android.util.Log`

- **文件**：`src/main/kotlin/app/amber/feature/runtime/AgentToolActivityStore.kt`
- **行 3**：`import android.util.Log`
- **用法**：`Log.e(TAG, ...)` 单处调用（行 97，`fail()` 方法内的错误日志）
- **剥离策略**：替换为 `println` 或轻量日志接口，最低改动

---

## 汇总

| 分类 | 数量 | 模块 |
|---|---|---|
| ✅ 可直接转 KMP | **21** | 14 个 Task 1 模块 + 7 个 Task 2 惯性模块 |
| ⚠️ 少量泄漏需先剥离 | **3** | `core/ai/transformers/api`、`core/automation/api`、`feature/runtime/api` |
| ❌ 深度绑定 Android | **0** | — |

**总体评价**：共享层代码质量非常好，Android 依赖隔离做得干净。3 处泄漏均为单一 import、改动量 ~5 行，可快速剥离后转入 KMP。

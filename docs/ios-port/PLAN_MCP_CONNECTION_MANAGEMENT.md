# MCP 连接管理 KMP 化 — 实施计划

> 目标：让 iOS 能管理 MCP 服务器配置、建立连接、列出工具、调用工具。

## 当前状态（HEAD `3e876389`）

### McpManager 在 :app（Android-only）

**文件**：`app/src/main/java/app/amber/core/ai/mcp/McpManager.kt`（~510 行）

**构造参数**：
```kotlin
class McpManager(
    private val settingsStore: SettingsAggregator,   // ← Android-only (:core:settings)
    private val appScope: AppScope,                   // ← 已 KMP (Slice 30)
    private val filesManager: FilesManager,           // ← Android-only (:app, 15 个平台依赖)
)
```

**平台依赖**：
| 行 | 依赖 | 说明 |
|---|---|---|
| 3 | `android.util.Log` | 日志（可抽 logE） |
| 4 | `androidx.core.net.toUri` | 文件路径转 URI（可移除/替代） |
| 6 | `io.ktor.client.engine.okhttp.OkHttp` | HTTP engine（JVM-only，iOS 需 Darwin） |
| 43 | `okhttp3.OkHttpClient` | OkHttp 客户端（JVM-only） |
| 44 | `java.util.concurrent.TimeUnit` | 超时单位（可替代） |

**MCP SDK 依赖**：
```
io.modelcontextprotocol:kotlin-sdk          // MCP 协议 SDK
io.modelcontextprotocol:kotlin-sdk.client   // Client 类
io.modelcontextprotocol:kotlin-sdk.shared   // AbstractTransport
io.modelcontextprotocol:kotlin-sdk.types    // CallToolRequest 等
```

**关键问题**：MCP SDK (`io.modelcontextprotocol:kotlin-sdk`) 是否支持 KMP/iOS？
- 需要检查：该库是否有 iosArm64/iosSimulatorArm64 target
- 如果不支持 iOS，整个 MCP 连接管理在 iOS 上无法用这个 SDK 实现

### transport 层

两个自定义 transport 类（`SseClientTransport` / `StreamableHttpClientTransport`）在 `:app`，各 1 个平台依赖（OkHttp）。

### 已在 commonMain 的 MCP 类型

```
McpServerConfig     // core/ai/api commonMain ✅ 已 export（27 hits in Shared.h）
McpTool             // core/ai/api commonMain ✅ 已 export
McpCommonOptions    // core/ai/api commonMain ✅ 已 export
InputSchema         // ai-core commonMain ✅ 已 export
```

### McpImportParser

`app/src/main/.../McpImportParser.kt` — 纯 Kotlin（0 平台依赖），可直接移到 commonMain。
```kotlin
fun parseMcpServersFromJson(json: String): List<McpServerConfig>
```

### McpStatus

`app/src/main/.../McpStatus.kt` — sealed class，需检查平台依赖。

### iOS 当前状态

- `McpServersView` 已读 `sharedSettings.snapshot.mcpServers`（只读展示，Slice 38）
- `Settings.mcpServers` 已 export（真实 KMP 数据）
- **无连接管理、无工具调用**

## 实施步骤

### 前置：确认 MCP SDK 是否支持 iOS

**结论（2026-06-17）**：`io.modelcontextprotocol:kotlin-sdk` 不支持 iOS target，走 **方案 B：iOS 原生实现**。

证据：
- 仓库当前版本：`gradle/libs.versions.toml` 中 `mcp = "0.8.4"`，`modelcontextprotocol-kotlin-sdk = "io.modelcontextprotocol:kotlin-sdk"`。
- Maven `kotlin-sdk-0.8.4.module` 仅发现 JVM、Linux、macOS、mingw 等 variants，未发现 `iosArm64` / `iosSimulatorArm64` / `iosX64`。
- 显式检查 `kotlin-sdk-iosarm64`、`kotlin-sdk-iossimulatorarm64`、`kotlin-sdk-iosx64` 的 `0.8.4` artifacts 均为 404。
- 抽样检查 Maven latest `0.13.0` 同样未发现 iOS variants，显式 iOS artifacts 也均为 404。

```bash
# 检查 io.modelcontextprotocol:kotlin-sdk 是否有 iOS target
# 结果：不支持 → 整个方案改为 iOS 原生实现（URLSession WebSocket/SSE）
```

### 方案 A：MCP SDK 支持 iOS → KMP 化

如果 SDK 支持 iOS：

**步骤 1**：把 McpManager 核心逻辑抽到 commonMain 接口

```kotlin
// core/ai/api commonMain
interface McpManagerInterface {
    suspend fun callTool(toolName: String, args: JsonObject): List<UIMessagePart>
    fun getAllAvailableTools(): List<McpTool>
    suspend fun syncAll()
    fun getStatus(config: McpServerConfig): Flow<McpStatus>
}
```

**步骤 2**：transport 层抽 expect-actual
- `commonMain`: `expect class McpTransport(config: McpServerConfig)`
- `jvmMain`: 用 OkHttp + ktor SSE（现有实现）
- `iosMain`: 用 Darwin HTTP engine + 自定义 SSE

**步骤 3**：McpManager 主体移到 commonMain
- 去 `android.util.Log` → `logE`
- 去 `OkHttpClient` → Ktor HttpClient（engine 用 expect-actual）
- 去 `FilesManager` → 接口注入（参照 modelcouncil 模式）
- 去 `SettingsAggregator` → `ModelCouncilSettingsSource` 同类接口

**步骤 4**：export 给 Shared.framework + iOS McpServersView 接连接管理

### 方案 B：MCP SDK 不支持 iOS → iOS 原生实现

如果 SDK 不支持 iOS：

**步骤 1**：在 commonMain 定义 MCP 客户端接口

```kotlin
// core/ai/api commonMain
interface McpClientInterface {
    suspend fun connect(config: McpServerConfig): Boolean
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(name: String, args: JsonObject): String
    fun disconnect()
}
```

**步骤 2**：iOS iosMain 用 URLSession 实现 SSE/HTTP transport
- `URLSession` + `URLSessionWebSocketTask`（for StreamableHTTP）
- 手动实现 SSE 解析（for SSE transport）
- JSON-RPC over SSE/WebSocket

**步骤 3**：新建 `IosMcpManager`（feature/task iosMain 或独立 iosMain）
- 用 `IosMcpClientInterface` actual
- 连接管理 + 工具调用 + 重连

**步骤 4**：iOS McpServersView 接连接状态 + 工具列表

### McpImportParser 下沉（两种方案都需要）

```kotlin
// 从 :app 移到 core/ai/api commonMain
fun parseMcpServersFromJson(json: String): List<McpServerConfig>
```

纯 Kotlin，0 平台依赖，直接移。

## 验证

- MCP SDK iOS target 检查
- `git diff --check`
- `compileKotlinJvm` + `compileKotlinIosSimulatorArm64` BUILD SUCCESSFUL
- `shared linkDebugFramework` + Shared.h 确认新符号
- `xcodebuild` BUILD SUCCEEDED
- `ios_build_and_run` → McpServersView 显示连接状态
- subagent review

## 风险

- **MCP SDK iOS 支持未知** — 这是最大的不确定性，决定走方案 A 还是 B
- transport 层 SSE 解析复杂 — iOS 需要手动实现 SSE 帧解析
- 连接管理涉及后台协程 + 重连逻辑 — 需要正确的生命周期管理
- 工具调用结果格式（ImageContent/Base64）需 iOS 端处理

## 涉及文件

- 改/移：`app/src/main/.../mcp/McpManager.kt`（核心逻辑 → commonMain）
- 改/移：`app/src/main/.../mcp/McpImportParser.kt`（→ commonMain）
- 改/移：`app/src/main/.../mcp/transport/*.kt`（→ expect-actual）
- 可能新建：`core/ai/api/src/commonMain/.../mcp/McpClientInterface.kt`
- 可能新建：`core/ai/api/src/iosMain/.../mcp/IosMcpClient.kt`（方案 B）
- 改：`iosApp/iosApp/McpServersView.swift`（接连接状态）
- 改：`shared/build.gradle.kts`（export 新模块）
- 不动：McpServerConfig/McpTool/McpCommonOptions（已在 commonMain）

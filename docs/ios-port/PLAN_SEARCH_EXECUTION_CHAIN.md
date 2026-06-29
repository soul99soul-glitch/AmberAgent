# Search 执行链接入 ChatViewModel 工具桥 — 实施计划

> 目标：让 iOS ChatViewModel 能声明 BuiltInTools.Search、处理 provider 返回的 tool call、执行搜索（HTTP + HTML 提取）、把结果送回 provider 做二次生成。

## 当前状态（HEAD `3e876389`）

### ChatViewModel tool pipeline 现状

```
ChatViewModel.sendMessage()
  → makeTextGenerationParams()
    → Model(tools: Set<BuiltInTools>())     // ← 空集，不声明任何工具
    → TextGenerationParams(tools: [])       // ← 空列表
  → provider.streamTextCancellable(providerSetting, messages, params, onChunk, onComplete)
    → onChunk: accumulator.append(chunk)    // ← 只处理文本流，不处理 tool call
    → onComplete: recordRun + finishStreaming  // ← 无 tool call 循环
```

**关键文件**：
- `iosApp/iosApp/ChatViewModel.swift:399-426` — `makeTextGenerationParams()`（tools 为空）
- `iosApp/iosApp/ChatViewModel.swift:242-275` — `streamTextCancellable`（只处理文本 chunk）
- `ai-provider-openai/src/commonMain/.../OpenAIKmpProvider.kt` — provider 实现（KMP）

### KMP 侧 search 相关类型

```
BuiltInTools.Search      // ai-core commonMain，已 export
Tool                     // ai-core commonMain，已 export
ToolDescriptor           // ai-core commonMain，已 export
ToolInvocation           // ai-core commonMain，已 export
SearchServiceOptions     // core/types commonMain，已 export
SearchOrchestrator       // app/src/main (Android-only)
SearchTools              // app/src/main (Android-only)
```

**问题**：`SearchOrchestrator` 和 `SearchTools` 在 `:app`（Android-only），不在 KMP commonMain。iOS 需要自己实现搜索执行。

### MessageChunk 中 tool call 的表示

```
MessageChunk.choices[].delta / message
  → UIMessageChoice
    → message: UIMessage?
      → parts: [UIMessagePart]
        → UIMessagePart.Tool  // ← tool call 结果
```

`UIMessagePart.Tool` 已在 `ai-core` commonMain 且已 export。

## 实施步骤

### 步骤 1：声明 search tool

修改 `ChatViewModel.makeTextGenerationParams()`：
- 在 `Model.tools` 中加入 `BuiltInTools.search`
- 在 `TextGenerationParams.tools` 中加入 search tool descriptor

```swift
// ChatViewModel.swift makeTextGenerationParams()
let model = Model(
    // ...existing...
    tools: sharedSettings.snapshot.enableWebSearch
        ? Set([BuiltInTools.search])
        : Set<BuiltInTools>(),
)
return TextGenerationParams(
    // ...existing...
    tools: sharedSettings.snapshot.enableWebSearch
        ? [Tool(descriptor: searchToolDescriptor)]  // 需要构造 search tool
        : [],
)
```

**注意**：需要确认 `Tool` 和 `ToolDescriptor` 的构造方式——检查 KMP commonMain 的定义。

### 步骤 2：检测 stream 中的 tool call

修改 `streamTextCancellable` 的 `onChunk` 回调：
- 检查 `chunk.choices[].delta.message.parts` 是否包含 `UIMessagePart.Tool`
- 如果包含，暂停当前流，执行搜索，然后用搜索结果构造新的 messages 数组做二次 stream

```swift
onChunk: { chunk in
    accumulator.append(chunk: chunk)
    // 检查是否有 tool call
    if let toolCall = chunk.choices.first?.delta?.message?.parts
        .compactMap({ $0 as? UIMessagePart.Tool }).first {
        // 暂停流，执行搜索
        Task { await self.executeSearchToolCall(toolCall, runId: runId) }
    }
    // ...existing snapshot update...
}
```

### 步骤 3：实现 iOS 搜索执行

新建 `iosApp/iosApp/IOSSearchExecutor.swift`：

```swift
/// iOS-native search executor — uses URLSession to fetch search results.
/// Mirrors what Android's SearchOrchestrator does but with iOS APIs.
struct IOSSearchExecutor {
    /// Execute a search_web tool call.
    /// Returns the search result text to inject back into the conversation.
    static func execute(query: String, services: [SearchServiceOptions]) async -> String {
        // 1. 选一个启用的搜索服务（从 sharedSettings.snapshot.searchServices）
        // 2. 用 URLSession 发 HTTP 请求到搜索 API
        // 3. 解析结果（JSON/HTML）
        // 4. 返回格式化文本
    }
}
```

**关键决策**：
- 使用 `URLSession`（iOS 原生，不是 Ktor）
- 搜索 API 取决于配置的服务类型（Jina/DuckDuckGo/Bing/etc.）
- 每种服务的 API 格式不同——先支持最简单的（DuckDuckGo Lite 或 Jina Reader）

### 步骤 4：二次 prompt 循环

在 `executeSearchToolCall` 完成后：
1. 把搜索结果作为新的 `UIMessage`（role: tool）追加到 messages
2. 再次调用 `provider.streamTextCancellable`（二次生成）
3. 二次生成的结果追加到对话

```swift
func executeSearchToolCall(_ toolCall: UIMessagePart.Tool, runId: String) async {
    let query = toolCall.function.arguments  // 提取搜索 query
    let result = await IOSSearchExecutor.execute(query: query, ...)
    // 构造 tool result message
    let toolMessage = UIMessage(role: .tool, parts: [.text(result)])
    messages.append(toolMessage)
    // 二次 stream
    streamJob = provider.streamTextCancellable(
        providerSetting: providerSetting,
        messages: messages,
        params: params,
        onChunk: { ... },
        onComplete: { ... }
    )
}
```

### 步骤 5：UI 提示

在 `ChatView` 的消息流中展示 tool call 状态：
- 显示"正在搜索：{query}…"
- 显示搜索结果摘要
- 然后显示二次生成的回复

## 验证

- `git diff --check`
- `xcodebuild` BUILD SUCCEEDED
- `ios_build_and_run` 启动，发送"搜索一下 X"，观察 tool call → 搜索 → 二次生成
- subagent review（调用链闭环：UI → ChatViewModel → OpenAIKmpProvider → tool call → IOSSearchExecutor → 二次 stream）

## 风险

- `UIMessagePart.Tool` 的 Swift API 表面需要确认（function/arguments 字段名）
- 二次 stream 需要正确处理 messages 数组（包含 tool result）
- 搜索 API 格式因服务而异——先支持 1-2 种
- ChatViewModel 是 `@MainActor`——搜索执行需要后台线程

## 涉及文件

- 改：`iosApp/iosApp/ChatViewModel.swift`（makeTextGenerationParams + tool call 检测 + 二次 prompt）
- 新建：`iosApp/iosApp/IOSSearchExecutor.swift`
- 可能改：`iosApp/iosApp/ChatView.swift`（tool call UI 提示）
- 不动：KMP 代码（所有类型已在 commonMain export）

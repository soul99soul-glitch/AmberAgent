# P4-T2b + P4-T3 Review 报告

>  reviewer：Kimi Code CLI  
>  日期：2026-06-13  
>  范围：`codex/ios-port-wip` 分支，提交 `6169e43c` + `1e7415ae`  
>  依据：当时提交 `6169e43c` + `1e7415ae` 的 review checklist 与真实代码；一次性 prompt 已从当前工作树移除，可从 Git 历史追溯。

---

## 总体结论

本轮提交完成了 P4-T2（流式对话 + Room 落库）和 P4-T3（Markdown 渲染管线）的核心目标，架构方向正确，关键路径可编译通过。主要基础设施（KMP hierarchy、Ktor 引擎、Flow→Swift 桥接、Rust AST 解码）都已就位。

但仍存在几个需要处理的问题才能安全合并：

1. **流式文本累加方式与 KMP 的 `UIMessage.appendChunk()` 不一致**，Swift 侧自行拼字符串，会丢失 reasoning、tool、annotation 等增量语义。
2. **Room 数据库实例每次 `recordRun()` 都重新创建**，存在多实例并发风险。
3. **Markdown 流式场景下每个 chunk 都完整重解析**，性能存在隐患；`PackedAstReader` 的 Swift 移植大体正确，但缺少部分边界校验和 extras 解码器。
4. **`MarkdownBridge` 使用 `@_silgen_name`** 直接绑定 C 函数，功能可用但可维护性不如 modulemap。
5. **Swift `Text` 拼接 `+` 在 iOS 26 已废弃**。

** verdict**：本轮是高质量的进展，但不建议直接合并到主线。建议再补一个 fix-up 提交处理上述问题后合并。

---

## 验证结果

| 验证项 | 命令 | 结果 |
|---|---|---|
| Android 不回归 | `./gradlew :app:assembleDebug --no-daemon` | ✅ BUILD SUCCESSFUL |
| KMP iOS 编译 | `./gradlew :shared:compileKotlinIosSimulatorArm64 --no-daemon` | ✅ BUILD SUCCESSFUL |
| ai-provider-openai iOS 编译 | `./gradlew :ai-provider-openai:compileKotlinIosSimulatorArm64 --no-daemon` | ✅ BUILD SUCCESSFUL |
| Shared.framework 链接 | `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon` | ✅ BUILD SUCCESSFUL |
| Swift 类型检查 | `xcrun swiftc -typecheck`（9 个 Swift 文件） | ⚠️ 通过，1 个 deprecation warning |

### 编译警告

- `iosApp/iosApp/MarkdownView.swift:170:56`: `'+' was deprecated in iOS 26.0: Use string interpolation on Text instead`。
- KMP 侧：
  - `core/agent-store-room` expect/actual classes Beta 警告（可接受，Room KMP 已知）。
  - `core:native` CInterop commonization 禁用警告（已存在）。

---

## 问题清单

| # | 严重度 | 文件 | 描述 | 建议 |
|---|---|---|---|---|
| 1 | 🔴 阻塞 | `iosApp/iosApp/ChatViewModel.swift` | 流式文本累加没有使用 `:ai-core` 的 `UIMessage.appendChunk()`，而是自己拼 `accumulatedText`，会丢失 reasoning、tool call、annotation、usage 等增量语义，也与 Android 行为不一致。 | 改用 `self.messages[idx] = self.messages[idx].appendChunk(chunk: chunk)`。 |
| 2 | 🔴 阻塞 | `iosApp/iosApp/ChatViewModel.swift` | `recordRun()` 每次调用都执行 `AgentRuntimeDatabaseConstructor.shared.initialize()`，生成新的 Room 数据库实例；多次实例访问同一 SQLite 文件可能引发并发/锁定问题。 | 在 ViewModel 中持有单一 `db` 实例，或把数据库封装成 Swift singleton。 |
| 3 | 🟡 警告 | `iosApp/iosApp/ChatViewModel.swift` | `inputSnapshot` 在 `generateResponse()` 开头捕获的是清空后的 `inputText`（用户消息已 append），所以 `inputDigest` 实际为空字符串或旧值，失去记录意义。 | 在用户输入清空前捕获 `inputText`，或直接用 `userMsg.toText()`。 |
| 4 | 🟡 警告 | `iosApp/iosApp/MarkdownView.swift` | 每个 chunk 都完整调用 `MarkdownBridge.parse(markdown)` 并重建整棵 AST；流式长回复时 O(n²) 开销明显。 | 缓存最后一次解析结果/AST，仅在有新内容时重解析；或改用 `amber_markdown_to_html` 走纯文本 HTML 渲染。 |
| 5 | 🟡 警告 | `iosApp/iosApp/PackedAstReader.swift` | `children` 构建时 `skipNode` 失败只是 `break` 而不是抛出，可能把截断的 blob 当成有效结构，隐藏数据损坏。 | 失败时抛出或至少记录；与 Kotlin 原版的异常行为保持一致。 |
| 6 | 🟡 警告 | `iosApp/iosApp/PackedAstReader.swift` | `headingLevel()` 没有校验 1–6 范围；Kotlin 原版会过滤掉非法 level。 | 增加 `1...6` 范围校验。 |
| 7 | 🟡 警告 | `iosApp/iosApp/PackedAstReader.swift` | 缺少 Kotlin 原版中的 `codeFenceKindExtra`、`taskCheckedExtra`、`listStartExtra`、`tableAlignmentsExtra` 等 extras 解码器。当前 UI 不需要，但后续支持 task list、ordered list start、table 时会缺能力。 | 后续补齐；本次可接受但需记录。 |
| 8 | 🟡 警告 | `iosApp/iosApp/MarkdownBridge.swift` | 使用 `@_silgen_name` 直接绑定 Rust FFI 函数，绕过了 `AmberNative.xcframework` 的 modulemap；功能可用，但链接依赖不透明，且 `amber_free_string`/`amber_free_bytes` 的签名与 Rust 头文件需手动同步。 | 中期改为 `import AmberNative`（通过 modulemap）或把 `AmberNativeBridge` 的 Kotlin wrapper export 到 Swift。 |
| 9 | 🟡 警告 | `iosApp/iosApp/MarkdownView.swift` | `Text` 拼接使用 `+` 运算符，在 iOS 26/Swift 6 中已废弃。 | 改用 `Text` 插值或 `AttributedString` 拼接。 |
| 10 | 🟡 警告 | `iosApp/iosApp/MarkdownView.swift` | `sliceSource()` 在 start/end 越界时返回空字符串，静默吞掉错误；多字节字符场景下 `source.utf8.index(...offsetBy:)` 是正确的，但代码块直接切整段可能包含围栏标记（```）。 | 对 code block 使用 AST 子节点文本而不是直接切整段；统一错误日志。 |
| 11 | 🟢 建议 | `ai-provider-openai/.../SseEvent.kt` | `sseFlow` 与 `:common/.../SSE.kt` 高度重复。 | 等 `:common` 净化 OkHttp 后统一抽取到公共 KMP 网络模块。 |
| 12 | 🟢 建议 | `iosApp/iosApp/ChatViewModel.swift` | `ChunkCollector` 用 completion-handler 桥接 Flow，虽可行但代码较绕；`Task { @MainActor }` 嵌套在主线程 ViewModel 中仍有一层冗余调度。 | 启用 SKIE 后可直接 `for try await chunk in flow`，但当前受限 Kotlin 2.3.21，可维持现状。 |
| 13 | 🟢 建议 | `iosApp/iosApp/ChatView.swift` | `ChatViewModel` 在 `ChatView.init` 中创建，Tab 切换导致 View 重建时会丢失对话状态。 | 把 ViewModel 生命周期上提到 `AmberAgentApp` 或用 `@StateObject`/`@Observable` 全局持有。 |

---

## 逐项 Review

### 1. 正确性

#### 1.1 `PackedAstReader` 是否忠实移植？

**结论：基本忠实，但有三处差异。**

- **Header 解析**：与 Kotlin 原版一致（PMDA magic、version、flags）。
- **LEB128 / varint**：与 Kotlin 原版一致；Swift 用 `Int(blob[cursor])` 等价于 Kotlin 的 `blob[cursor].toInt() and 0xFF`（UInt8 → Int 本身就是 0–255）。
- **`skipNode` 迭代实现**：与 Kotlin 原版一致，用 stack 避免递归溢出。
- **差异点**：
  1. Kotlin 的 `children` 在 `skipNode` 失败时会抛出；Swift 是 `break` 并返回已解析的部分。这会掩盖 blob 截断/损坏。
  2. Kotlin `headingLevelExtra()` 校验 `1..6`；Swift 没校验。
  3. Swift 未实现 `codeFenceKindExtra`、`taskCheckedExtra`、`listStartExtra`、`tableAlignmentsExtra`。

**评级：🟡 警告**

#### 1.2 `MarkdownBridge` 内存管理

**结论：正确。**

```swift
guard let ptr = text.withCString({ ... amber_markdown_parse(...) }) else { return nil }
defer { amber_free_bytes(ptr, outLen) }
return Data(bytes: ptr, count: Int(outLen))
```

- `defer` 保证正常路径和提前返回都释放。
- `toHtml` 同样用 `defer { amber_free_string(cStr) }`。
- 没有路径泄漏 Rust 分配的内存。

**评级：✅ 通过**

#### 1.3 `ChunkCollector` Flow 收集

**结论：实现正确，但 Swift 并发边界较复杂。**

```swift
private class ChunkCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let chunk = value as? MessageChunk { onChunk(chunk) }
        completionHandler(nil)
    }
}
```

- 实现了 K/N 的 `FlowCollector` 协议。
- `emit` 总是调用 `completionHandler(nil)`，不会把 Swift 错误抛回 Kotlin Flow。
- 在 `withCheckedThrowingContinuation` 中，`flow.collect(collector:completionHandler:)` 的 completion handler 只在 Flow 完成或失败时调用一次，符合 continuation resume 一次的要求。
- 风险：`onChunk` 闭包内部又嵌套 `Task { @MainActor }`，而 `emit` 本身可能不在主线程执行，双重调度增加了延迟和复杂度。

**评级：🟡 警告**

#### 1.4 Room `AgentRunEntity` 构造器签名

**结论：匹配。**

`Shared.h` 中 `AgentRunEntity` 的 Swift 初始化器：

```objc
- (instancetype)initWithRunId:(NSString *)runId
                  parentRunId:(NSString * _Nullable)parentRunId
              agentDescriptorId:(NSString *)agentDescriptorId
                 agentVersion:(NSString *)agentVersion
               conversationId:(NSString * _Nullable)conversationId
                messageNodeId:(NSString * _Nullable)messageNodeId
            producesMessageId:(NSString * _Nullable)producesMessageId
                  assistantId:(NSString * _Nullable)assistantId
                       status:(NSString *)status
                  inputDigest:(NSString *)inputDigest
           inputSnapshotRef:(NSString * _Nullable)inputSnapshotRef
          inputSchemaVersion:(int32_t)inputSchemaVersion
                    startedAt:(int64_t)startedAt
                   finishedAt:(SharedLong * _Nullable)finishedAt
            interruptedReason:(NSString * _Nullable)interruptedReason
```

Swift 代码中 `AgentRunEntity(...)` 的参数顺序、类型完全匹配。

**评级：✅ 通过**

#### 1.5 `sliceSource()` UTF-8 字节偏移 → `String.Index`

**结论：正确。**

```swift
guard let startIdx = source.utf8.index(source.utf8.startIndex, offsetBy: start, limitedBy: source.utf8.endIndex)
```

- 使用 `source.utf8` 的 index 进行字节偏移遍历，再切片回 `String`，能正确处理多字节 UTF-8 字符。
- 边界检查 `limitedBy:` 防止越界。
- 失败时返回空字符串，属于优雅降级。

**评级：✅ 通过**

---

### 2. 并发安全

#### 2.1 `ChatViewModel` 的 `@MainActor` 隔离

**结论：基本完整，但 Flow 回调路径仍有嵌套调度。**

- 类标记 `@MainActor`。
- `generateResponse()` 内部 `Task { @MainActor in ... }` 是冗余的，因为整个类已经在主 actor 上。
- `ChunkCollector.onChunk` 闭包内又包了一层 `Task { @MainActor [weak self] in ... }`，同样冗余，但无害。
- 没有明显竞态，因为 `messages` 数组的所有写入最终都发生在主线程。

**评级：🟡 警告（冗余调度可清理）**

#### 2.2 `recordRun()` 与 Room 数据库实例

**结论：有并发隐患。**

```swift
let db = AgentRuntimeDatabaseConstructor.shared.initialize()
let dao = db.agentRuntimeDao()
```

- 每次 `recordRun()` 调用都创建新的 `AgentRuntimeDatabase` 实例。
- Room 的 `RoomDatabase` 设计为单例；多实例同时访问同一 SQLite 文件可能导致：
  - WAL 模式下的并发问题。
  - 事务隔离异常。
  - 性能下降（每个实例有自己的连接池）。
- 生成代码 `AgentRuntimeDatabaseConstructor.initialize()` 只是 `AgentRuntimeDatabase_Impl()`，无缓存逻辑。

**建议**：在 `ChatViewModel` 初始化时创建一次 `db`，或封装一个 `AgentRuntimeDatabase.shared` singleton。

**评级：🔴 阻塞**

---

### 3. 性能

#### 3.1 `MarkdownView` 每个 chunk 都完整重解析

**结论：确实是性能隐患。**

流式回复中，每收到一个 chunk：
1. `MarkdownBridge.parse(markdown)` 调用 Rust FFI 解析整段 markdown。
2. `PackedAstReader` 解码整棵 AST。
3. `MarkdownView` 重建整个 SwiftUI 视图树。

对于长回复，这是 O(n²) 行为。实际体验上：
- 短回复（< 1KB）不明显。
- 长代码块/长列表会明显掉帧。

**建议**：
- 短期：缓存 `PackedAstReader`，只有内容变化时才重新解析。
- 中期：在 KMP 侧实现 `MarkdownParserNative` 的增量更新，或等 Android 的 streaming reveal 策略文档化后对齐。
- 替代：如果只是展示纯文本流，可先用 `amber_markdown_to_html` 出 HTML，再用 `AttributedString` 显示，避免自己遍历 AST。

**评级：🟡 警告**

#### 3.2 `PackedAstNode.children` 懒加载

**结论：单次访问是 O(n)，但嵌套列表遍历确实存在重复扫描。**

- `children` 是 `lazy var`，第一次访问后缓存。
- 但 `nextSibling()` 会触发父节点的 `children`（如果未缓存），导致从父节点 body 开始完整扫描所有兄弟节点。
- 在深度嵌套列表中，多次 `nextSibling()` 可能重复触发父节点 `children` 计算。

由于 Swift `lazy var` 线程不安全，但在 `@MainActor` 下无并发问题，缓存生效后后续访问是 O(1)。

**评级：🟡 警告（可接受，但需留意大文档）**

---

### 4. 错误处理

#### 4.1 Rust FFI NULL 回退

**结论：优雅。**

```swift
if let data = MarkdownBridge.parse(markdown),
   let reader = PackedAstReader(data: data),
   let root = reader.root() {
    blockStack(root.children, source: markdown)
} else {
    Text(markdown)
}
```

解析失败时直接回退到纯文本 `Text(markdown)`，不会崩溃。

**评级：✅ 通过**

#### 4.2 Flow 收集中 `SseEvent.Failure`

**结论：正确捕获并显示。**

- Kotlin 侧 `streamText` 在 `SseEvent.Failure` 时 `throw event.throwable ?: Exception("Stream failed")`。
- Swift 侧 `do { ... } catch { ... }` 会捕获，并把错误显示成 assistant 消息。
- 取消场景单独 `catch is CancellationError`，记录为 `interrupted`。

**评级：✅ 通过**

#### 4.3 Room 写入失败

**结论：仅 `print`，无用户反馈。**

```swift
do {
    try await withCheckedThrowingContinuation { ... }
} catch {
    print("[Room] Failed to insert agent_run: \(error)")
}
```

开发阶段可接受，但产品化时应该把错误记录到日志系统或至少显示一个 toast/indicator。

**评级：🟡 警告**

---

### 5. 架构一致性

#### 5.1 `SseEvent` / `sseFlow()` 重复

**结论：重复，但当前有合理理由。**

`:common` 仍含 OkHttp legacy helper，`ai-provider-openai` 不依赖 `:common` 是正确的。但长期来看应把 SSE 工具抽到干净的 KMP 网络模块。

**评级：🟡 警告（技术债务）**

#### 5.2 `ChatViewModel` 直接构造数据库

**结论：不符合 DI/生命周期管理最佳实践。**

当前直接调用 `AgentRuntimeDatabaseConstructor.shared.initialize()`。建议：
- 在 ViewModel 中注入 `AgentRuntimeDatabase`。
- 或使用 `@MainActor` singleton 包装。

**评级：🟡 警告**

#### 5.3 `MarkdownBridge` 使用 `@_silgen_name`

**结论：功能可用，但可维护性较差。**

`@_silgen_name` 是 Swift 内部特性，未来 Swift 版本可能变化。更稳健的做法：
- 通过 `AmberNative.xcframework` 的 `module.modulemap` `import AmberNative`。
- 或者把 Rust FFI 的 Swift 调用封装在 Kotlin 的 `AmberNativeBridge` 中，让 Swift 只调用 Kotlin API。

当前 P4-T3 为了快速验证而使用 `@_silgen_name` 可接受，但应在 P5/P6 替换。

**评级：🟡 警告**

---

## 做得好的地方

1. **KMP hierarchy 修复**：移除 6 个模块的显式 `nativeMain by creating { dependsOn(commonMain.get()) }`，改用 Kotlin 2.x 默认 hierarchy 模板，消除了构建警告。
2. **Ktor 引擎依赖**：`ai-provider-openai` 正确声明了 `ktor-client-okhttp`（JVM）和 `ktor-client-darwin`（iOS）。
3. **Flow→Swift 桥接**：`ChunkCollector` 实现了 `Kotlinx_coroutines_coreFlowCollector`，在无 SKIE 情况下让 Swift 能消费 Kotlin Flow。
4. **取消传播**：`Task.cancel()` 会取消 Flow 收集，进而取消 Ktor SSE 请求，满足 P4-T2 取消要求。
5. **Markdown 渲染完整度**：覆盖了标题、代码块、引用、列表、行内格式、链接等核心元素，且能正确回退到纯文本。
6. **Rust UTF-8 修复**：`cstr_array_to_vec` 改为 `to_string_lossy()`，避免了无效 UTF-8 导致规则配对错位。
7. **计划书同步**：P2-T2、P3-T2/T3、P4-T1 状态已更新。

---

## 下一步建议（按优先级）

| 优先级 | 事项 | 原因 |
|---|---|---|
| P0 | 改用 `UIMessage.appendChunk(chunk:)` 增量更新 | 保持与 Android 行为一致，支持 reasoning/tool/usage |
| P0 | Room 数据库单例化 | 避免多实例并发问题 |
| P1 | 修复 `inputSnapshot` 捕获时机 | 让 `inputDigest` 真正记录用户输入 |
| P1 | 缓存 Markdown AST / 改用 toHtml | 改善流式渲染性能 |
| P1 | 修复 `PackedAstNode.children` 失败处理 | 与 Kotlin 行为一致，不静默吞错误 |
| P2 | 替换 `Text` 拼接 `+` | iOS 26 deprecation |
| P2 | 给 `MarkdownBridge` 加 modulemap 导入 | 可维护性 |
| P3 | 补齐 extras 解码器 | 支持 task list、ordered list start、table |
| P3 | `ChatViewModel` 生命周期上提 | Tab 切换不丢失对话 |

---

## 最终评级

| 维度 | 评级 | 说明 |
|---|---|---|
| 正确性 | 🟡 | PackedAstReader 基本正确，但文本累加和 DB 实例化有偏差 |
| 并发安全 | 🟡 | `@MainActor` 正确，但 DB 多实例有隐患 |
| 性能 | 🟡 | Markdown 每 chunk 重解析是明显瓶颈 |
| 错误处理 | 🟢 | FFI 回退、Flow 错误捕获都到位；Room 写入仅 print |
| 架构一致性 | 🟡 | SSE 重复、DB 直接构造、@_silgen_name 均为债务 |
| 构建验证 | ✅ | 全部通过 |

**总体建议**：**条件通过，需 fix-up 后合并**。核心修复项是 P0 的两条（`appendChunk` 和 Room 单例），其余可延后。

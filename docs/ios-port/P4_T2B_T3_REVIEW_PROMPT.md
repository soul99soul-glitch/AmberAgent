# Review Prompt: P4-T2b (Streaming) + P4-T3 (Markdown Rendering)

## 角色

你是一位资深 iOS/ Kotlin Multiplatform 工程师，精通 KMP、Kotlin/Native 互操作、Rust FFI、Swift concurrency 和 SwiftUI。请对本轮提交进行严格的技术 review。

## 仓库结构

- **工作目录**: `/Users/arquiel/Downloads/AI/amberagent-ios`
- **分支**: `codex/ios-port-wip`
- **对照分支**: `main`（只读，Android 主线，不要修改）
- **排除目录**: `main/`, `legacy/`, `jank-opt/`, `ui-graphite/`, `arch/`, `OpenOmniBot/`

## 本轮变更范围

以下 3 个提交覆盖了 P4-T2b 和 P4-T3：

```
ebed39df  ios-port(P4-T2a): OpenAI KMP provider + settings + review + fixes
6169e43c  ios-port(P4-T2b): streaming via Flow collection + Ktor engine deps + hierarchy fix
1e7415ae  ios-port(P4-T3): Markdown rendering pipeline — Rust AST → SwiftUI
```

## 变更概要

### P4-T2a: OpenAI KMP Provider + 设置
- **新模块** `ai-provider-openai/`：KMP 模块，实现 `Provider<ProviderSetting.OpenAI>` 接口
  - `OpenAIKmpProvider`：`streamText()` / `generateText()` / `listModels()`
  - `SseEvent` + `sseFlow()`：Ktor SSE 事件流（从 `:common` 复制，因 `:common` 仍含 OkHttp）
- **Swift 设置持久化**：`SettingsStore`（UserDefaults JSON）+ `SettingsView`（Form）
- **KMP hierarchy 修复**：6 个模块移除 `nativeMain by creating { dependsOn(commonMain.get()) }`，改为依赖 Kotlin 2.x 默认 hierarchy 模板
- **Rust UTF-8 修复**：`cstr_array_to_vec` 从 `to_str()`（静默跳过）改为 `to_string_lossy()`
- **计划书状态更新**：P2-T2, P3-T2/T3, P4-T1 标记完成

### P4-T2b: 流式渲染 + Room 落库
- **ChatViewModel**：从 `generateText`（非流式回调）切换到 `streamText`（Kotlin Flow 收集）
  - `ChunkCollector`：NSObject + `Kotlinx_coroutines_coreFlowCollector` 桥接
  - 增量文本累加 + 逐 chunk UI 更新
  - 取消：`Task.cancel()` 传播到 Flow 收集，捕获 `CancellationError`
- **Room 落库**：`recordRun()` — 每次生成完成后写入 `AgentRunEntity`（completed/interrupted/failed）
- **Ktor 引擎依赖**：`ai-provider-openai` 添加 `ktor-client-okhttp`（JVM）和 `ktor-client-darwin`（iOS）
- **Swift 并发**：`ChatViewModel` 添加 `@MainActor`

### P4-T3: Markdown 渲染管线
- **MarkdownBridge.swift**：Rust FFI 桥接（`amber_markdown_parse`, `amber_markdown_to_html` + 内存释放）
- **PackedAstReader.swift**：Kotlin `PackedAstReader` + `PackedAstNode` + `NodeType` 的 Swift 移植
  - PMDA 二进制 AST 解码（LEB128 varint、懒加载节点、28 种 NodeType）
  - Extras 解码：heading level、code lang、link href/title
- **MarkdownView.swift**：AST → SwiftUI 渲染
  - 行内：粗体、斜体、删除线、行内代码（monospaced）、链接（蓝色）
  - 块级：标题（H1-H6 缩放）、代码块（圆角矩形 + 语言标签）、引用（左边框）、有序/无序列表、水平线
  - UTF-8 字节偏移 → `String.Index` 源文本切片
- **MessageBubbleView**：助手消息从 `Text` 改为 `MarkdownView`

## Review 要求

请逐项检查以下方面，给出 **通过 / 警告 / 阻塞** 评级：

### 1. 正确性 🔴
- [ ] `PackedAstReader` 是否忠实移植了 Kotlin 原版？LEB128 解码、节点偏移计算、extras 解析是否正确？
- [ ] `MarkdownBridge` 的 FFI 内存管理是否正确？`amber_free_bytes` / `amber_free_string` 是否在所有路径（包括错误路径）上都被调用？
- [ ] `ChunkCollector` 的 Flow 收集是否正确处理了 Kotlin/Native 的回调式 suspend 桥接？`withCheckedThrowingContinuation` 的 resume 是否保证只调用一次？
- [ ] Room `AgentRunEntity` 构造器参数是否与 ObjC header 中的实际签名匹配？
- [ ] `sliceSource()` 的 UTF-8 字节偏移 → `String.Index` 转换是否正确处理了多字节字符？

### 2. 并发安全 🟡
- [ ] `ChatViewModel` 的 `@MainActor` 隔离是否完整？`ChunkCollector.onChunk` 内的 `Task { @MainActor }` 是否会产生竞态？
- [ ] `recordRun()` 是 async 方法但在 `@MainActor` 上下文中调用 — Room 的 iOS 初始化是否线程安全？多次调用 `AgentRuntimeDatabaseConstructor.shared.initialize()` 是否会创建多个数据库实例？

### 3. 性能 🟡
- [ ] `MarkdownView` 每次渲染都调用 `MarkdownBridge.parse()` — 流式场景下每个 chunk 都触发完整重解析，是否有性能问题？
- [ ] `PackedAstNode.children` 的懒加载实现 — 每次访问子节点都重新扫描 blob，嵌套列表是否有 O(n²) 问题？

### 4. 错误处理 🟡
- [ ] Rust FFI 返回 NULL 时 `MarkdownView` 回退到纯文本 — 回退是否优雅？
- [ ] Flow 收集中 `SseEvent.Failure` 抛出异常 — 是否被正确捕获并显示给用户？
- [ ] Room 写入失败是否静默吞掉（`print` only）？是否应该有用户可见的反馈？

### 5. 架构一致性 🟡
- [ ] `ai-provider-openai` 的 `SseEvent` + `sseFlow()` 与 `:common` 中的同名代码重复 — 是否应共享？
- [ ] `ChatViewModel` 直接构造 `AgentRuntimeDatabase` — 是否应该通过 DI/单例管理数据库生命周期？
- [ ] `MarkdownBridge` 使用 `@_silgen_name` — 是否应该用 modulemap / C header 替代？

### 6. 构建验证
- [ ] `./gradlew :app:assembleDebug` 是否通过
- [ ] `./gradlew :ai-provider-openai:compileKotlinIosSimulatorArm64` 是否通过
- [ ] 所有 Swift 文件是否通过 `swiftc -typecheck`

## 关键文件清单

| 文件 | 职责 |
|---|---|
| `ai-provider-openai/build.gradle.kts` | KMP 模块定义 + Ktor 引擎依赖 |
| `ai-provider-openai/src/commonMain/.../OpenAIKmpProvider.kt` | KMP OpenAI provider（SSE + 非流式） |
| `ai-provider-openai/src/commonMain/.../SseEvent.kt` | Ktor SSE 事件类型 + sseFlow() |
| `iosApp/iosApp/ChatViewModel.swift` | 流式对话 + Room 落库 |
| `iosApp/iosApp/ChatView.swift` | 聊天 UI |
| `iosApp/iosApp/MessageBubbleView.swift` | 消息气泡（集成 MarkdownView） |
| `iosApp/iosApp/MarkdownBridge.swift` | Rust FFI 桥接 |
| `iosApp/iosApp/PackedAstReader.swift` | 二进制 AST 解码器 |
| `iosApp/iosApp/MarkdownView.swift` | AST → SwiftUI 渲染 |
| `iosApp/iosApp/SettingsStore.swift` | 设置持久化 |
| `iosApp/iosApp/SettingsView.swift` | 设置 UI |
| `native/amber-ffi/src/lib.rs` | Rust FFI 导出（cstr_array_to_vec 已修复） |
| `core/agent-runtime/build.gradle.kts` | hierarchy 修复（5 个同类文件之一） |

## 输出格式

请输出结构化报告：

```
## 总体结论
（一段话总结：是否可以合并，主要风险点）

## 验证结果
- 构建检查结果

## 问题清单
| # | 严重度 | 文件 | 描述 | 建议 |
|---|---|---|---|---|

## 做得好的地方
（列出做得好的设计决策）

## 下一步建议
（按优先级排列的改进项）
```

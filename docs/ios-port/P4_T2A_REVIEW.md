# P4-T2a Review 报告

>  reviewer：Kimi Code CLI  
>  日期：2026-06-12  
>  范围：opencode 完成的 P4-T2a（最小 OpenAI 对话链路，非流式初版）  
>  验证基线：`codex/ios-port-wip` 分支当前工作目录

---

## 1. 一句话结论

P4-T2a 完成了「从 SwiftUI 输入 → KMP OpenAI provider → 非流式 API → 渲染回复」的端到端骨架，方向正确，关键模块可编译。但当前实现是**非流式**的，且存在 Swift 并发警告、设置持久化方式与计划书不符、`Settings` 构造器冗长等问题，需要在进入 P4-T2b 流式之前先收口。

---

## 2. 实际验证结果

| 验证项 | 命令 | 结果 |
|---|---|---|
| Android 不回归 | `./gradlew :app:assembleDebug --no-daemon` | ✅ BUILD SUCCESSFUL |
| KMP iOS 编译 | `./gradlew :shared:compileKotlinIosSimulatorArm64 --no-daemon` | ✅ BUILD SUCCESSFUL |
| 新模块 iOS 编译 | `./gradlew :ai-provider-openai:compileKotlinIosSimulatorArm64 --no-daemon` | ✅ BUILD SUCCESSFUL |
| 新模块 JVM 编译 | `./gradlew :ai-provider-openai:compileKotlinJvm --no-daemon` | ✅ BUILD SUCCESSFUL（1 个 warning） |
| Shared.framework 链接 | `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon` | ✅ BUILD SUCCESSFUL |
| Swift 编译 | `xcrun swiftc ...`（6 个 Swift 文件） | ⚠️ 成功，但 2 个 warning |

### Swift 编译警告

```
iosApp/iosApp/ChatViewModel.swift:69:27: warning: consider using asynchronous alternative function
iosApp/iosApp/ChatViewModel.swift:75:31: warning: capture of 'self' with non-Sendable type 'ChatViewModel?' in a '@Sendable' closure [#SendableClosureCaptures]
```

说明：
- 第一条是 Swift 6 提示「可用 async 版本替代 completion handler」。这其实是好事，说明 Kotlin suspend 函数在 Swift 侧呈现了 `async throws` 形态（Swift 自动把 completion-handler ObjC 方法映射成 async）。
- 第二条是 Swift 6 严格并发检查：completion handler 被标记为 `@Sendable`，但 `[weak self]` 捕获了一个未遵循 `Sendable` 的类。当前写法在 `@MainActor` Task 里使用，运行时大概率安全，但编译器会报警。

---

## 3. 改动清单

```
 M iosApp/iosApp/AmberAgentApp.swift       # 添加 TabView（Chat + Settings）
 M iosApp/iosApp/ChatView.swift            # 注入 SettingsStore，增加取消按钮
 M iosApp/iosApp/ChatViewModel.swift       # 接入 OpenAIKmpProvider.generateText
 M settings.gradle.kts                     # include(":ai-provider-openai")
 M shared/build.gradle.kts                 # export(:ai-provider-openai)
?? ai-provider-openai/                     # 新增 KMP OpenAI provider 模块
?? iosApp/iosApp/SettingsStore.swift       # UserDefaults 存储 baseUrl/apiKey/modelId
?? iosApp/iosApp/SettingsView.swift        # 设置页表单
```

---

## 4. 分文件 Review

### 4.1 `ai-provider-openai` 模块

#### 4.1.1 总体评价

**做得好**：

- 新建独立 KMP 模块 `:ai-provider-openai`，把 OpenAI provider 实现从 Android-only 的 `:ai` 中剥离出来，符合我之前 review 中「把 `:ai` 拆分为 KMP provider 实现 + Android 胶水」的建议。
- 实现 `Provider<ProviderSetting.OpenAI>` 接口，包含 `listModels`、`generateText`、`streamText`、`generateImage`，接口完整。
- 完全使用 Ktor，无 OkHttp、无 `java.net.URL`、无 Android 类型，真正跨平台。
- 复用了 `:ai-core` 的类型（`UIMessage`、`MessageChunk`、`TextGenerationParams` 等），与现有模型层对齐。
- 手动复制了 `common/.../SSE.kt` 中的 `sseFlow` 逻辑到模块内部，避免依赖仍含 OkHttp 的 `:common` 模块，这是务实的妥协。

#### 4.1.2 存在的问题

1. **Ktor SSE artifact 未显式声明**

   `build.gradle.kts` 中声明了：
   ```kotlin
   implementation(libs.ktor.client.core)
   implementation(libs.ktor.client.content.negotiation)
   implementation(libs.ktor.serialization.kotlinx.json)
   ```
   但代码中使用了 `io.ktor.client.plugins.sse.SSE` 和 `io.ktor.client.plugins.sse.sse`。当前能编译是因为 Ktor 3.4.3 把 SSE 插件合并到了 `ktor-client-core`（注释里也写了），但显式声明 `ktor-client-sse` 更稳健，也避免未来 Ktor 拆分 artifact 时突然挂掉。

2. **JVM 引擎未指定**

   ```kotlin
   private val sseClient by lazy { HttpClient { install(SSE) } }
   private val httpClient by lazy { HttpClient { } }
   ```

   iOS 上会自动解析到 `ktor-client-darwin`（前提是 classpath 上有），JVM 上会解析到默认 JVM 引擎。但 `build.gradle.kts` 没有声明 JVM 引擎（`ktor-client-okhttp` 或 `ktor-client-java`）。在 JVM 单元测试或 Android 以外的 JVM 消费场景下会失败。

   建议：
   - `commonMain` 保留无引擎 `HttpClient`。
   - `jvmMain` 增加 `implementation(libs.ktor.client.okhttp)`（或 `ktor-client-java`）。
   - `iosMain` 增加 `implementation(libs.ktor.client.darwin)`。

3. **SSE 实现与 `:common` 重复**

   `SseEvent` 和 `sseFlow` 是 `:common/.../SSE.kt` 的几乎完整拷贝。`:common` 仍含 OkHttp legacy helper，所以 `:ai-provider-openai` 不依赖 `:common` 是对的。但长期应该把 `:common` 中的 `sseFlow` 净化后提到一个干净的 KMP 公共模块（例如 `:common-kmp` 或 `:core:network`），避免多处维护同一套 SSE 封装。

4. **错误处理比 Android 版简化**

   `OpenAIKmpProvider.sseFlow` 只 catch `Exception`，没有单独区分 `ClientRequestException` / `ServerRequestException` 来 enriched 错误信息。非流式 `generateText` 也只是 throw raw exception。对比 `:ai/.../ChatCompletionsAPI` 会把 HTTP status + body 塞进错误消息，这里用户体验会稍差。

5. **JVM 编译 warning**

   ```
   OpenAIKmpProvider.kt:253:55 Unnecessary safe call on a non-null receiver of type 'UIMessagePart.Reasoning'.
   ```

   这行：
   ```kotlin
   val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank()
   ```
   因为 `reasoningPart` 已经是 `UIMessagePart.Reasoning?` 但上面 `if (!hasText && !hasReasoning && tools.isEmpty())` 之前的逻辑里 `reasoningPart` 不可空？需要清理这个 safe call。

6. **未实现 `getBalance` 等默认行为**

   继承 `Provider` 接口后，`getBalance` 使用默认实现返回 `"TODO"`，这是接口本身的行为，不是 bug。但 `generateImage` 显式 throw，更干净。

---

### 4.2 `ChatViewModel.swift`

#### 4.2.1 总体评价

**做得好**：

- 从 mock 响应切换到真实 provider 调用。
- 使用 `OpenAIKmpProvider()` 作为默认 provider。
- 增加了 `cancelGeneration()`，UI 上也有停止按钮。
- 错误处理有基本兜底（把错误显示成 assistant 消息）。

#### 4.2.2 关键问题：当前是「伪取消 + 非流式」

这是 P4-T2a 最大的偏差：

```swift
self.provider.generateText(...) { [weak self] chunk, error in ... }
```

调用的是 **非流式** `generateText`。这意味着：

1. 用户点击发送后，需要等待整个回复从网络返回，界面长时间空白或只显示 placeholder。
2. 点击「取消」只是取消 Swift 侧的 `Task`，但 Ktor 端的 HTTP 请求仍在继续，网络流量和 token 消耗不会停止。
3. 不满足 P4-T2 计划中的验收标准：「模拟器+真机各完成一次真实 API 的完整流式对话」。

**建议**：P4-T2b 必须切换到 `streamText()`。没有 SKIE 时，`Flow<MessageChunk>` 在 Swift 中会变成 `SharedKotlinx_coroutines_coreFlow`，需要写一个 Kotlin/Native helper 把 Flow 转成 callback，或者在 Swift 侧用 `for await`（如果 Swift 能把 K/N Flow 的某些方法映射成 AsyncSequence）。

当前看 `Shared.h` 里 `streamText` 返回的是 `id<SharedKotlinx_coroutines_coreFlow>`，Swift 无法直接 `for await`。可选方案：

- **方案 A**：在 `:ai-provider-openai` 增加一个 Swift-friendly 的 wrapper：
  ```kotlin
  fun OpenAIKmpProvider.streamTextCallback(
      providerSetting: ProviderSetting.OpenAI,
      messages: List<UIMessage>,
      params: TextGenerationParams,
      onChunk: (MessageChunk) -> Unit,
      onError: (Throwable) -> Unit,
      onComplete: () -> Unit,
  ): Cancellable
  ```
- **方案 B**：启用 SKIE（一旦支持 Kotlin 2.3.21），`Flow` 直接变成 `AsyncSequence`。

#### 4.2.3 Sendable 警告

```swift
self.provider.generateText(...) { [weak self] chunk, error in
    Task { @MainActor in
        guard let self else { return }
        ...
    }
}
```

编译器警告 `capture of 'self' with non-Sendable type 'ChatViewModel?' in a '@Sendable' closure`。解决方式：

1. 让 `ChatViewModel` 遵循 `@MainActor`：
   ```swift
   @MainActor
   @Observable
   final class ChatViewModel { ... }
   ```
   这样整个类在主线程，completion handler 内的 `@MainActor` Task 可以简化。
2. 或者显式把回调里的操作包装成 `@MainActor` 并避免 weak self：
   ```swift
   self.provider.generateText(...) { @MainActor [weak self] chunk, error in
       guard let self else { return }
       ...
   }
   ```
   但 Kotlin/Native 生成的 completion handler 是否支持 `@MainActor` 取决于 header 生成。

更简单的做法是把 ViewModel 标为 `@MainActor`，因为 `@Observable` 的 ViewModel 本来就应该在主线程。

#### 4.2.4 `makeProviderSetting()` 和 `makeTextGenerationParams()` 构造器冗长

```swift
ProviderSetting.OpenAI(
    id: KotlinUuid.companion.random(),
    enabled: true,
    name: "OpenAI",
    models: [],
    balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
    builtIn: false,
    descriptionText: nil,
    shortDescriptionText: nil,
    apiKey: settingsStore.apiKey,
    baseUrl: settingsStore.baseUrl,
    chatCompletionsPath: "/chat/completions",
    useResponseApi: false,
    authMode: OpenAIAuthMode.apiKey,
    brand: OpenAIBrand.generic
)
```

这是无 SKIE 的代价。短期可接受，但建议后续在 Kotlin 侧提供一个 Swift-friendly factory：

```kotlin
fun ProviderSetting.OpenAI.Companion.simple(
    apiKey: String,
    baseUrl: String,
    chatCompletionsPath: String = "/chat/completions",
): ProviderSetting.OpenAI
```

#### 4.2.5 `Model` 构造问题

```swift
let model = Model(
    modelId: settingsStore.modelId,
    displayName: settingsStore.modelId,
    id: KotlinUuid.companion.random(),
    type: ModelType.chat,
    customHeaders: [],
    customBodies: [],
    inputModalities: [],
    outputModalities: [],
    abilities: [],
    tools: NSSet(),
    contextWindowTokens: nil,
    providerOverwrite: nil
)
```

- `tools: NSSet()` 的语义是「无工具」。需要确认 `Model` 在 Kotlin 侧是否真的用 `Set<Tool>`，且空集合是否被正确序列化。
- 同样建议在 Kotlin 侧加 factory：
  ```kotlin
  fun Model.Companion.chat(modelId: String, displayName: String = modelId): Model
  ```

#### 4.2.6 消息更新逻辑

当前实现：

```swift
let placeholder = UIMessage(... parts: [UIMessagePartText(text: "", metadata: nil)] ...)
self.messages.append(placeholder)

self.provider.generateText(...) { chunk, error in
    // 用完整回复替换 placeholder
    let updatedPlaceholder = UIMessage(... parts: [UIMessagePartText(text: assistantText, ...)] ...)
    self.messages[lastIndex] = updatedPlaceholder
}
```

这是非流式的「placeholder → 完整替换」模式。如果切换到流式，应该复用 `:ai-core` 中的 `UIMessage.appendChunk()`（它在 Swift 中应该被导出为 `func appendChunk(chunk: MessageChunk) -> UIMessage`），这样 Swift 侧只需要：

```swift
self.provider.streamText(...) { chunk in
    self.messages[lastIndex] = self.messages[lastIndex].appendChunk(chunk: chunk)
}
```

---

### 4.3 `ChatView.swift`

**做得好**：

- 注入 `SettingsStore`，支持设置页共享同一实例。
- 增加取消按钮（加载中时显示 stop，否则显示 send）。

**小问题**：

- `ChatView` 现在通过 `init(settingsStore:)` 创建，而 `AmberAgentApp` 中 `ChatView(settingsStore: settingsStore)` 会重新创建一个新的 `ChatViewModel`（因为 `@State viewModel` 在 View init 时创建）。由于 `SettingsStore` 是值类型（struct?）——不，它是 `@Observable final class`，是引用类型，所以共享没问题。但 `ChatViewModel` 每次 `ChatView` 重建都会新建。在 TabView 切换时如果 ChatView 被销毁重建，对话状态会丢失。
- 建议：把 `ChatViewModel` 的生命周期提到 `AmberAgentApp` 或用一个 `SceneStorage` / 全局持有，避免 Tab 切换丢失对话。

---

### 4.4 `SettingsStore.swift` 与 `SettingsView.swift`

#### 4.4.1 与计划书偏差

P4-T2 计划书中明确写：

> 极简设置页：录入一个 Provider 的 base URL + API key（DataStore KMP 持久化）。

但当前 `SettingsStore` 使用 **UserDefaults** 本地持久化，没有使用 KMP DataStore。这虽然更简单、iOS 原生，但：

- 偏离了计划书中「DataStore KMP 持久化」的验收标准。
- 没有把设置持久化逻辑放到共享层，Android 和 iOS 无法共享同一套设置存储。

**建议**：

- 如果 P4-T2a 的目标只是「先跑通 UI」，可以临时用 UserDefaults，但必须在 P4-T2b 或后续子任务中替换为 `DataStore KMP`。
- 或者现在就引入 `datastore-preferences-core`，在 KMP 模块中实现 `SettingsRepository`，Swift 只调用 repository API。

#### 4.4.2 SettingsStore 安全性

```swift
SecureField("API Key", text: $settingsStore.apiKey)
```

用了 `SecureField` 是对的，但 UserDefaults 中的 API key 是明文存储。DataStore 默认也是明文文件，但至少可以统一走 `security-crypto`（Android）/ Keychain（iOS）包装。P4-T2 阶段可以先用明文，但需要在 P5/P6 备注为安全债务。

#### 4.4.3 SettingsView 体验

- 三个输入框都设置了 `autocorrectionDisabled()` 和 `textInputAutocapitalization(.never)`，细节到位。
- 缺少「保存/测试连接」按钮，用户输入后立刻生效。这符合极简要求，但建议后续加一个「Test Connection」按钮调用 `provider.listModels()` 验证 API key 是否有效。

---

### 4.5 `AmberAgentApp.swift`

**评价**：

- 使用 `TabView` 组织 Chat 和 Settings 是合理的 P4-T2 架构。
- `@State private var settingsStore = SettingsStore()` 在 App 级别持有，通过引用传递给子 View，状态共享正确。

**建议**：

- 考虑给 `SettingsStore` 增加 `@MainActor` 或确保它在主线程创建，因为 `ChatViewModel` 会用到它。
- TabView 的图标使用了 SF Symbols，符合 P5 设计原则。

---

### 4.6 构建配置

#### 4.6.1 `settings.gradle.kts`

新增 `include(":ai-provider-openai")`，位置在 `:shared` 之前，合理。

#### 4.6.2 `shared/build.gradle.kts`

新增 `export(project(":ai-provider-openai"))` 和 `api(project(":ai-provider-openai"))`，合理。这让 Swift 能看到 `OpenAIKmpProvider`。

**问题**：`ai-provider-openai` 没有被加入 `gradle/libs.versions.toml` 的依赖版本，因为直接引用 `libs.plugins.kotlin.multiplatform` 等，没问题。

---

## 5. 关键风险与阻塞项

| 风险 | 严重程度 | 说明 |
|---|---|---|
| 非流式实现 | 🔴 高 | P4-T2 验收标准要求「完整流式对话」，当前只做了非流式 |
| 取消不真正终止网络请求 | 🔴 高 | 因为用非流式 API，取消只是 UI 状态重置 |
| 设置未用 DataStore KMP | 🟡 中 | 偏离计划书，但功能可用 |
| Swift Sendable 警告 | 🟡 中 | 编译警告，运行时大概率安全，但需清理 |
| JVM 引擎未声明 | 🟡 中 | `:ai-provider-openai` 在 JVM 测试/运行可能失败 |
| SSE 代码重复 | 🟢 低 | 技术债务，长期应抽到公共模块 |

---

## 6. 下一步建议（P4-T2b）

1. **切换到 `streamText()`**：
   - 在 `:ai-provider-openai` 增加 callback 包装，或在 Swift 侧消费 `Flow<MessageChunk>`。
   - 用 `UIMessage.appendChunk(chunk:)` 增量更新消息。
2. **真正支持取消**：
   - 保存 `streamText` 返回的 `Cancellable` / `Job`，取消时同时取消 KMP 协程。
3. **清理 Swift 并发警告**：
   - 给 `ChatViewModel` 加 `@MainActor`。
4. **用 DataStore KMP 替换 UserDefaults**（可与 opencode 商量是否在本阶段做）。
5. **在 Kotlin 侧加 factory 函数**，简化 Swift 构造器。
6. **声明 Ktor JVM/iOS 引擎依赖**。
7. **修复 JVM 编译 warning**（不必要的 safe call）。

---

## 7. 总体评分

| 维度 | 评分 | 说明 |
|---|---|---|
| 架构方向 | ✅ 正确 | 新增 KMP provider 模块是正确拆分 |
| 功能完成度 | 🟡 50% | 非流式通路已通，流式未实现 |
| 代码质量 | 🟡 良好 | 有编译警告和一处 safe-call 误用 |
| 与计划书一致性 | 🟡 部分偏离 | DataStore 未使用、流式未实现 |
| Android 回归 | ✅ 无影响 | 新模块不参与 Android 构建，`:app:assembleDebug` 通过 |
| Swift 编译 | ⚠️ 有警告 | 2 个 warning，不影响运行 |

**结论**：P4-T2a 是一个合格的「非流式骨架」里程碑，但还不能算完成 P4-T2。建议把当前状态标记为 `P4-T2a 完成`，并继续 `P4-T2b` 实现流式 + 真实取消。

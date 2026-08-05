# AmberAgent iOS 移植工作 Review 报告

>  reviewer：Kimi Code CLI  
>  日期：2026-06-12  
>  范围：`/Users/arquiel/Downloads/AI/amberagent-ios`（`codex/ios-port-wip` 分支）  
>  依据：当时的 review checklist、`docs/IOS_PORT_PLAN_2026-06-12.md` 及仓库实际代码；一次性 prompt 已从当前工作树移除，可从 Git 历史追溯。

---

## 1. 总体结论

当前 iOS 移植工作已完成了 **P0–P4 的核心基础设施**：27 个 KMP 模块（不含 `:shared` umbrella）全部可编译到 iOS，Android 端构建未回归，Rust FFI 通过 C-ABI 成功桥接到 Kotlin/Native，Swift 最小聊天切片可编译通过。整体方向正确，工程执行力强。

但存在几个需要尽快处理的结构性问题：

1. **`:ai` 仍是 `android.library` 且继续 `api(libs.okhttp)`**，与 P2「共享层零 OkHttp」目标不符；iOS 真实 AI 对话链路尚未接通。
2. **`core:types` 边界模糊**，成了依赖 11 个模块的「settings 大聚合器」，长期会拖慢 KMP 构建并增加循环依赖风险。
3. **`:common` 与 `:core:usage` 仍依赖 OkHttp**，而 `:ai` 又依赖 `:common`，P2-T2 的「共享层无 OkHttp」验收标准并未真正达成。
4. **SKIE 禁用** 导致 Swift 侧 API 笨重；虽然当前 demo 可用，但 P5 全量 UI 开发前必须解决。
5. **计划书文档状态与实际代码严重不同步**：P3-T2/T3、P4-T1/T2 在代码中已完成，但 `IOS_PORT_PLAN_2026-06-12.md` 仍标记为 ⬜ 未开始。

** verdict**：这是一个扎实的「基础设施已通、细节待收口」的中间状态。建议在进入 P5 之前先解决架构边界和 OkHttp 泄漏问题，否则后续 UI 层会不断为共享层的债务买单。

---

## 2. 已执行的验证命令

以下命令均在 `/Users/arquiel/Downloads/AI/amberagent-ios` 下实际运行并通过。

### 2.1 Android 不回归

```bash
./gradlew :app:assembleDebug --no-daemon
```

结果：**BUILD SUCCESSFUL**（~2 分钟，全部 up-to-date/executed）。Rust JNI 构建正常，APK 产出成功。

### 2.2 KMP iOS 编译

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64 --no-daemon
```

结果：**BUILD SUCCESSFUL**（16s）。27 个 KMP 模块全部通过 iOS Simulator Arm64 编译。

> 配置阶段出现警告：「Default Kotlin Hierarchy Template Not Applied Correctly」，涉及 6 个模块（`:core:agent-runtime`、`:core:agent-store-room`、`:core:llm`、`:core:native`、`:feature:webview`、`:feature:live:api`）。原因是代码显式写了 `nativeMain.dependsOn(commonMain)` 与默认模板冲突。建议统一在 `gradle.properties` 加 `kotlin.mpp.applyDefaultHierarchyTemplate=false` 或移除显式 dependsOn。

### 2.3 Shared.framework 链接

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon
```

结果：**BUILD SUCCESSFUL**（31s）。

生成的 ObjC header：

```bash
$ wc -l shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers/Shared.h
15159
$ grep -c "swift_name" shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers/Shared.h
5965
```

满足 REVIEW_PROMPT 中「> 4000」的验收标准。

### 2.4 Swift 切片编译

```bash
xcrun swiftc \
  -target arm64-apple-ios26.0-simulator \
  -sdk $(xcrun --show-sdk-path --sdk iphonesimulator) \
  -F shared/build/bin/iosSimulatorArm64/debugFramework \
  -I shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers \
  -emit-module -module-name AmberAgentTest -o /tmp/AmberAgentTest.swiftmodule \
  iosApp/iosApp/ChatViewModel.swift \
  iosApp/iosApp/MessageBubbleView.swift \
  iosApp/iosApp/ChatView.swift \
  iosApp/iosApp/AmberAgentApp.swift
```

结果：**零错误**，Swift module 生成成功。

### 2.5 Rust FFI iOS 交叉编译

```bash
cd native && cargo build --package amber-ffi --target aarch64-apple-ios-sim --release
```

结果：**Finished release**（~59s，含首次全量编译）。`amber-ffi` 及其 10 个组件 crate 均成功编译到 iOS simulator。

---

## 3. 分领域 Review

### 3.1 架构决策

#### 3.1.1 `:ai` 保持 `android.library` 是否合理？

**结论：当前阶段合理，但不应长期如此。**

`:ai` 目前包含 OpenAI / Claude / Google 等 provider 实现，内部仍使用 `android.util.Log`、`java.net.URL`、`android.content.Intent` 等 Android 类型，且继续以 `api(libs.okhttp)` 把 OkHttp 泄漏给下游。REVIEW_PROMPT 将其解释为「iOS 需要在 Swift 侧原生实现 provider」，这是务实的 P4 折中。

但原计划在 P2-T2 中明确写了：「`:ai` 转为 KMP 模块（jvm/android + ios 目标）」。当前代码**只完成了源码层面的 OkHttp→Ktor 替换**，并未完成模块层面的 KMP 转换。这会导致：

- iOS 无法直接复用 `:ai` 中的 provider 实现，必须重新写一套 Swift provider（工作量大）。
- `:ai` 的 `api(libs.okhttp)` 使得「共享层零 OkHttp」的 M2 里程碑无法成立。

**建议**：将 `:ai` 进一步拆分为：

- `:ai-core`（已是 KMP）：纯类型 + provider 接口。
- `:ai-impl` 或 `:ai-providers`（目标 KMP）：把现在 `:ai` 中除 Android 类型外的实现逻辑下沉到 KMP，仅保留最小 Android 平台胶水在 `:ai`。

#### 3.1.2 `core:types` 的边界是否清晰？

**结论：边界偏宽，有「god module」倾向。**

`core:types` 的 `build.gradle.kts` 依赖了 11 个 project：

```kotlin
api(project(":ai-core"))
api(project(":core:ai:api"))
api(project(":core:ai-prompts"))
api(project(":core:sync:api"))
api(project(":core:context:api"))
api(project(":feature:live:api"))
api(project(":feature:modelcouncil:api"))
api(project(":feature:terminal:api"))
api(project(":feature:office:api"))
api(project(":feature:board:api"))
api(project(":feature:subagent:api"))
```

原因是 `Settings` 数据类直接聚合了各 feature 的运行时配置（`TodayBoardSetting`、`LiveModeSetting`、`ModelCouncilRuntimeSetting` 等）。

当前静态依赖无循环，但风险在于：

- 任何 feature API 模块若需要 `Settings` 或某个 `core:types` 类型，就会立刻产生循环。
- `:shared` 通过 `export(:core:types)` 间接把所有这些 feature API 类型全部暴露到 Swift，导致 `Shared.h` 膨胀到 15,159 行、5,965 个 `swift_name`。

**建议**：

1. 把 `Settings` 拆成按 feature 独立的 data class（`ChatSettings`、`BoardSettings`…），由各 feature 模块自己持有。
2. `core:types` 只保留真正的跨模块基础模型：`Conversation`、`MessageNode`、`Assistant`、`UIMessage` 等。
3. 若拆分成本太高，至少为 P5 创建一个轻量的 `:core:ui-types` 模块，专门给 Swift 侧消费，避免整个 `core:types` 进入 framework。

#### 3.1.3 28 个 KMP 模块的粒度

**结论：粒度基本合理，但「28」这个数字有误导。**

实际使用 `kotlin("multiplatform")` 插件的模块是 **29 个**（含 `:shared`）。REVIEW_PROMPT 正文说 28，是因为 `:shared` 的 `sharedProjects` 列表里有 28 个被 export 的模块。文档应把表述统一为「28 个被 umbrella 导出的共享模块 + 1 个 `:shared` umbrella」。

`:feature:*:api` 大量存在是合理的，因为 Android 侧已经按 feature 划分；转成 KMP 接口模块成本很低。

#### 3.1.4 `core:native` 作为统一 cinterop 桥接层是否可扩展？

**结论：设计正确，可扩展。**

`AmberNativeBridge` 用 expect/actual 把 Rust FFI 封装成 Kotlin 友好 API，iOS 侧通过 `kotlinx.cinterop` 调用，Android 侧返回 stub（保留现有 JNI 桥接）。这种设计的好处：

- iOS 共享层不直接碰 C 指针。
- 未来 Android 也可以逐步迁移到统一桥接。
- 新增 Rust crate 只需要在 `amber-ffi/src/lib.rs` 加函数、`AmberNativeBridge` 加 expect/actual，扩展路径清晰。

---

### 3.2 OkHttp → Ktor 迁移质量

#### 3.2.1 `:ai` 内部迁移

**源码层面已完成。**

```bash
$ grep -r "import okhttp3" --include="*.kt" ai/src/main/java/
# 无输出
```

`common/src/main/java/app/amber/common/http/SSE.kt` 已改为 Ktor-based `sseFlow`：

```kotlin
fun HttpClient.sseFlow(url: String, block: HttpRequestBuilder.() -> Unit = {}): Flow<SseEvent>
```

`:ai` 中的 4 个 SSE provider（Claude、Google、ChatCompletionsAPI、ResponseAPI）均通过 `HttpClient(OkHttp) { install(SSE) }` + `sseFlow()` 实现流式。`SseEvent.Failure` 简化为 `Failure(throwable: Throwable?)` 是合理的，因为 Ktor 的错误信息足够丰富。

**但存在几个问题：**

1. `:ai/build.gradle.kts` 没有显式声明 `ktor-client-sse`。当前能编译是因为 SSE 插件被某个传递依赖带进来，但显式声明更稳健。
2. `:ai` 仍声明 `api(libs.okhttp)` 和 `api(libs.okhttp.logging)`，与「零 OkHttp」目标矛盾。
3. `:ai` 仍是 `android.library`，没有 iOS 目标，因此也不需要 `ktor-client-darwin`；但这是模块未 KMP 化的结果，不是迁移质量本身的问题。

#### 3.2.2 共享层其他模块的 OkHttp 泄漏

**问题比 REVIEW_PROMPT 描述的更严重。**

```bash
$ grep -rl "import okhttp3" --include="*.kt" \
    common/src core/usage/src ai/src
```

结果：

- `common/src/main/java/app/amber/common/http/Request.kt`：保留 `Call.await()`  legacy helper。
- `core/usage/src/main/kotlin/app/amber/core/usage/ProviderUsageClient.kt`：仍用 OkHttp 构造请求。
- `ai/src/main/java/...`：零 import（见上文）。

`:ai` 依赖 `:common`，因此 `:ai` 实际上仍通过 `:common` 间接使用 OkHttp。P2-T2 验收标准「`grep -r "import okhttp3" --include="*.kt"` 在共享模块中为零」目前不满足。

**建议**：

1. 删除 `common/.../Request.kt` 中的 OkHttp `Call.await()`，或将其移到 `:app` 的 Android-only 工具包。
2. 把 `ProviderUsageClient` 迁移到 Ktor。
3. 将 `:ai` 的 `api(libs.okhttp)` 改为 `implementation(libs.okhttp)` 或彻底移除。

#### 3.2.3 `GoogleGeminiOAuth.generateContent()` 返回 `CloudCodeAssistRequest`

**合理。** 该改动把「构造请求」与「执行请求」解耦，`CloudCodeAssistRequest` 是仅含 `url`/`body`/`headers` 的 data class，Ktor 侧可直接复用。没有破坏 Android 行为，也没把 OkHttp 类型泄漏到公共 API。

---

### 3.3 Rust FFI 层

#### 3.3.1 函数签名与内存约定

**整体安全、清晰。**

`amber-ffi/src/lib.rs` 暴露 20 个 `#[no_mangle] extern "C"` 函数，覆盖 markdown、highlight、html-diff、reader、office parsers、regex、crypto、json-expr、tokenizer。内存约定写入 `native/FFI_CONVENTIONS.md`：

- 输入字符串：`*const c_char`，调用方持有。
- 输出字符串：`*mut c_char`，调用方用 `amber_free_string` 释放。
- 字节缓冲：`*mut u8` + `*mut usize` 输出长度，调用方用 `amber_free_bytes` 释放。

所有导出函数都包在 `catch_unwind` 里，防止 Rust panic 跨 FFI 边界。

**发现一处正确性隐患：**

```rust
unsafe fn cstr_array_to_vec(arr: *const *const c_char) -> Vec<String> {
    ...
    if let Ok(s) = CStr::from_ptr(*ptr).to_str() {
        result.push(s.to_string());
    }
    ...
}
```

无效 UTF-8 的条目会被**静默跳过**。如果 `find_patterns` 和 `replacements` 中同时存在无效 UTF-8，跳过位置一致则结果仍正确；但如果只跳过一侧，规则配对就会错位。建议改为：遇到无效 UTF-8 时返回空 Vec 或返回错误标记，而不是静默跳过。

#### 3.3.2 amber-ffi 统一入口的粒度

**正确。** 10 个组件 crate 合并为一个 `libamber_ffi.a` 静态库 + cdylib，通过 `cbindgen` 生成单一 `AmberNative.h`。这比每个 crate 一个 FFI 入口更容易在 Xcode 中管理，也减少了 Swift/Kotlin-Native 的链接复杂度。

#### 3.3.3 cinterop 与 XCFramework

`core/native/build.gradle.kts` 配置正确：

- 按 simulator/device 区分 xcframework slice。
- cinterop `defFile` 指向 `AmberNative.def`。
- linkerOpts `-lamber_ffi` 链接静态库。

`native/build-xcframework.sh` 手动创建 XCFramework 结构，不依赖 `xcodebuild -create-xcframework`。脚本会安装缺失的 Rust target、安装 cbindgen、生成头文件、写 `Info.plist` 和 `module.modulemap`。脚本健全，但有两处可以改进：

1. 未对 cbindgen 版本做锁定，不同环境可能产生不同 header。
2. `Info.plist` 里的 `LibraryPath` 通过 `sed -i ''` 替换占位符，做法可行但不够健壮；建议用 `plutil` 或 Python 生成 plist。

---

### 3.4 Shared Framework 打包

#### 3.4.1 `export()` 逐一导出 28 个模块

`:shared/build.gradle.kts` 对 28 个 `sharedProjects` 逐一调用 `export(project(it))`，这是 `Shared.h` 从 167 行暴涨到 15,159 行的原因。debug framework 约 65MB，符合预期。

**问题不是 export 数量，而是 exported 的模块本身太宽。** `core:types` 把 11 个 feature API 全部带进来，导致 Swift 看到大量永远不会直接使用的类型。`transitiveExport = true` 并不能解决体积问题，反而会让 header 更难预测。

**建议**：

1. 优先收窄 `core:types` 边界。
2. 评估是否所有 28 个模块都需要出现在 Swift header。例如 `core:agent-store-room` 的 Room DAO/Entity 对 Swift UI 完全不可见，export 它只是增加 binary/header 体积。
3. 考虑在 `:shared` 之上再加一层 `:shared-ui` 或 `:shared-public`，只导出 Swift 真正需要的类型。

#### 3.4.2 cinterop 库不能被 export 的警告

链接时出现：

```
Interop library ... can't be exported with -Xexport-library
```

这是预期行为。cinterop 底层 C 类型不需要也不应该进入 `Shared.h`，`AmberNativeBridge` 的 Kotlin wrapper 已正常导出。无需修复，但建议在文档中说明。

---

### 3.5 Swift / KMP 类型桥接

#### 3.5.1 无 SKIE 的影响

SKIE 0.9.5 不支持 Kotlin 2.3.21，已完全禁用。当前 Swift 切片为了绕过这一点，大量使用了 Kotlin/Native 默认导出形态：

```swift
let conversationId = KotlinUuid.companion.random()
let instant = KotlinInstant.companion.fromEpochMilliseconds(...)
let localDt = Kotlinx_datetimeLocalDateTime(year: Int32(...), ...)
let msg = UIMessage(id: ..., role: ..., parts: ..., annotations: [], ... 9 个参数)
```

这些写法「能用」但非常不 Swift。`KotlinUuid`、`KotlinInstant`、`Kotlinx_datetimeLocalDateTime` 等类型名冗长；`UIMessage` 9 参数构造器容易出错；`companion` 工厂需要了解 Kotlin 语义。

**建议**：

1. **短期**：在 Kotlin 侧为 Swift 提供 factory extension / wrapper，例如：
   ```kotlin
   fun UIMessage.Companion.user(text: String, createdAt: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())): UIMessage
   ```
2. **中期**：密切关注 SKIE 对 Kotlin 2.3.x 的支持，一旦发布立刻启用。SKIE 会把 `Flow` 变成 `AsyncSequence`、`suspend` 变成 `async`、data class 默认参数变成 Swift 默认参数，显著改善 P5 开发体验。

#### 3.5.2 `Flow` / `suspend` 目前在 Swift 中的可见性

当前 Swift 切片没有消费任何 KMP 的 `Flow` 或 `suspend` 函数（只用了 `DispatchQueue.main.asyncAfter` 模拟响应）。因此 SKIE 缺失的急性问题尚未暴露。

**但 P4-T2 的真实 AI 链路必然需要消费 `Flow<ChatEvent>` 或类似流。** 没有 SKIE 时，Swift 侧需要手动处理 Kotlin/Native 的 continuation，或把共享层流封装成 callback。这会在 P5 成为主要摩擦点。

---

### 3.6 遗漏与风险

#### 3.6.1 应该转 KMP 但没转的模块

以下模块仍使用 `com.android.library` 插件，且被 `:app` 依赖，iOS 无法直接使用：

- `:ai`（provider 实现）
- `:common`（含 OkHttp legacy helper）
- `:core:model`
- `:core:settings`
- `:core:app-infra`
- `:core:usage`
- `:core:agent-runtime-impl`
- `:document`、`:highlight`、`:search`、`:tts`
- 多个 `:feature:*` 非 API 实现模块

其中最关键的是 `:ai` 和 `:common`。`:ai` 的 provider 实现是 iOS 对话功能的核心；`:common` 被大量模块依赖，若不 KMP 化，很多共享逻辑会被困在 Android 侧。

#### 3.6.2 Android 运行时行为回归风险

`:app:assembleDebug` 通过，且 P0 基线 `test` 也通过。但以下行为未在 review 中实际验证：

- 真实流式对话（SSE 取消、超时、错误处理）。
- Room KMP 在 Android 上的运行时行为（schema 是否一致）。
- Ktor 引擎替换后，自定义 header、代理、证书 pinning 等是否仍生效。

建议在进入 P5 前补一次 Android 端端到端冒烟测试，特别是流式对话和数据库读写。

#### 3.6.3 Room KMP 在 iOS 上未测试

`core:agent-store-room` 已转 KMP，使用 `BundledSQLiteDriver` 和 `@ConstructedBy` expect/actual。iOS 编译通过，但**尚未在 iOS 模拟器/真机上运行过实际数据库操作**。P4-T2 验收标准包含「agent_run/agent_event 正确落库（Room KMP 查询验证）」，目前未满足。

#### 3.6.4 循环依赖风险

当前静态依赖无循环，但 `core:types` 的宽边界是潜在隐患。建议：

1. 用 `./gradlew :core:types:dependencies --configuration commonMainResolvableDependenciesMetadata` 定期检查。
2. 把 `Settings` 拆到各 feature 模块后重新评估。

#### 3.6.5 文档与代码状态不同步

`docs/IOS_PORT_PLAN_2026-06-12.md` 中：

- P2-T2 状态为 🟡 进行中（实际源码层面已完成，模块层面未完成）。
- P3-T2、P3-T3 标记为 ⬜ 未开始（实际已完成）。
- P4-T1、P4-T2 标记为 ⬜ 未开始（实际已完成）。

**建议**：立即更新计划书状态列，避免后续多 Agent 协作时产生误解。

---

## 4. 按优先级排序的行动建议

| 优先级 | 事项 | 影响 | 建议负责人 |
|---|---|---|---|
| P0 | 更新 `docs/IOS_PORT_PLAN_2026-06-12.md` 状态列 | 避免后续协作混乱 | 任意 Agent |
| P0 | 解决 KMP hierarchy template 警告（`gradle.properties` 或移除显式 dependsOn） | 消除构建噪音，避免未来 Kotlin 升级失败 | 架构/构建 |
| P1 | 将 `:ai` 模块进一步拆分为 KMP provider 实现 + Android 胶水 | iOS 才能复用真实 AI provider | 后端共享层 |
| P1 | 移除 `:common` 和 `:core:usage` 的 OkHttp 依赖 | 达成 P2-T2 验收标准 | 后端共享层 |
| P1 | 收窄 `core:types` 边界，拆分 `Settings` | 减少 framework/header 体积，降低循环依赖风险 | 架构 |
| P2 | 为 Swift 提供 Kotlin factory/wrapper，缓解无 SKIE 痛点 | 提升 P5 UI 开发体验 | iOS/KMP 桥接 |
| P2 | 修复 `cstr_array_to_vec` 静默跳过无效 UTF-8 的问题 | 避免 regex pipeline 配对错误 | Rust FFI |
| P3 | 补 Android 端到端冒烟测试（流式对话 + Room） | 确保 Android 行为未回归 | QA/测试 |
| P3 | 在 iOS 模拟器上验证 Room KMP 实际落库 | 满足 P4-T2 验收标准 | iOS |
| P4 | 安装/配置 XcodeGen，生成完整 `.xcodeproj` | 替代 `swiftc` 手动验证 | iOS 工程 |

---

## 5. 最终评价

| 维度 | 评分 | 说明 |
|---|---|---|
| 架构方向 | ✅ 正确 | KMP + SwiftUI + Rust FFI 是当前最优路径 |
| 工程完成度 | 🟡 70% | P0–P4 基础设施已通，P4-T2 真实链路未接通 |
| 代码质量 | ✅ 良好 | 迁移改动最小化，Android 构建未回归 |
| 文档同步 | ❌ 滞后 | 计划书状态与实际代码差距大 |
| 边界清晰度 | 🟡 一般 | `core:types` 过宽，`:ai` 未彻底 KMP 化 |
| Swift 体验 | 🟡 可用但笨重 | 无 SKIE 是主要瓶颈 |
| 风险可控性 | 🟡 中等 | OkHttp 泄漏、Room iOS 未验证、循环依赖隐患 |

**总体建议**：先不要大规模进入 P5 UI。优先用 1–2 个迭代完成 P1/P2 收口（`:ai` KMP 化、OkHttp 清理、`core:types` 拆分、文档同步），再启动 Liquid Glass 全量 UI，否则会不断回头修共享层债务。

# AmberAgent iOS 分模块 Review

日期：2026-06-13
范围：当前工作区的 iOS Swift 代码、KMP `shared` 导出、AI provider、agent runtime、Room 存储、Native/Rust FFI、构建脚本与测试覆盖。
说明：本 review 只记录发现和建议，没有修改代码。工作区已有未提交改动，本 review 按当前文件内容判断。

## 结论

`shared` 的 iOS framework 可以生成，Swift 文件可以通过 typecheck，但当前 iOS 端还不是一个可稳定启动和上线的端到端实现。最大风险集中在：

- iOS 最终链接失败，Room/SQLite 符号未解析。
- Room KMP 数据库初始化方式不完整。
- Swift `Task.cancel()` 没有可靠取消 Kotlin Flow / Ktor SSE。
- Native FFI 对空数组、空输出、错误通道的边界处理不够安全。
- `Shared.framework` 导出面过大，Swift 侧 API 复杂且编译成本高。

## 验证结果

### 已通过

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon
```

结果：通过。
附带警告：`core:native` 的 cinterop commonization 未启用，Gradle 建议设置 `kotlin.mpp.enableCInteropCommonization=true`。

```bash
SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
xcrun swiftc -typecheck \
  -sdk "$SDK" \
  -target arm64-apple-ios26.0-simulator \
  -F shared/build/bin/iosSimulatorArm64/debugFramework \
  -I shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers \
  -I native/AmberNative.xcframework/ios-arm64-sim/Headers \
  iosApp/iosApp/*.swift
```

结果：通过。

### 未通过

```bash
SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
xcrun swiftc \
  -sdk "$SDK" \
  -target arm64-apple-ios26.0-simulator \
  -F shared/build/bin/iosSimulatorArm64/debugFramework \
  -I shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers \
  -I native/AmberNative.xcframework/ios-arm64-sim/Headers \
  -L native/AmberNative.xcframework/ios-arm64-sim \
  -lamber_ffi \
  iosApp/iosApp/*.swift \
  -o /tmp/AmberAgentTypeLinkTest
```

结果：失败。主要问题是 `Shared.framework` 内 Room/SQLite 相关符号未解析，例如：

- `_sqlite3_mutex_held`
- `_sqlite3_mutex_notheld`
- `_sqlite3_unlock_notify`
- `_sqlite3_win32_set_directory`
- `_sqlite3_win32_set_directory8`

补充 `-lsqlite3` 后仍失败，说明问题不只是缺少系统 SQLite 链接参数，更可能是 Room KMP / bundled SQLite 在 iOS framework 中的链接策略没有配置完整。

### 未能执行

`xcodebuild` 未执行成功，因为仓库当前没有生成好的 `.xcodeproj`，本机也没有 `xcodegen`。

## P0 阻塞问题

### 1. iOS 最终链接失败

相关文件：

- `core/agent-store-room/build.gradle.kts`
- `iosApp/project.yml`

`core/agent-store-room` 依赖 Room runtime 和 `androidx.sqlite.bundled`，但 iOS 侧最终链接时 SQLite 相关符号未解析。`shared` framework 自身能 link，不等于 App 最终可 link。

影响：

- App 不能形成可运行的 simulator binary。
- 任何依赖 Room 的运行记录、会话记录、agent state 都无法可靠验证。

建议：

- 明确 Room KMP iOS 推荐构建方式，确认 bundled SQLite 是否需要额外 framework / linker settings。
- 给 iOS CI 增加真实 App link 步骤，而不是只跑 `:shared:linkDebugFrameworkIosSimulatorArm64`。
- 修好后用最小 Room smoke test 验证建库、写入、读取。

### 2. Room 数据库初始化方式不完整

相关文件：

- `iosApp/iosApp/ChatViewModel.swift`
- `core/agent-store-room/src/commonMain/kotlin/app/amber/agent/core/agent/store/room/AgentRuntimeDatabase.kt`

iOS 端直接调用：

```swift
AgentRuntimeDatabaseConstructor.shared.initialize()
```

common 层只是 `expect` 构造器。没有看到 iOS database path、`Room.databaseBuilder(...)`、`setDriver(BundledSQLiteDriver())` 等配置。即使编译链接问题解决，运行时数据库也可能没有正确初始化。

影响：

- `recordRun()` 写库可能失败。
- 当前 `recordRun()` 捕获错误后只 `print`，UI 不会体现失败。
- 后续 history / runtime audit 依赖的数据可能丢失。

建议：

- 在 Kotlin iOS actual 层集中提供数据库 builder，不要在 Swift 侧直接拿 generated constructor 当 database factory。
- 明确 database 文件路径、migration 策略和 bundled SQLite driver。
- 把写入失败暴露到 debug UI 或日志系统，至少开发阶段不能静默吞掉。

### 3. 停止生成没有可靠取消 Kotlin Flow / Ktor SSE

相关文件：

- `iosApp/iosApp/ChatViewModel.swift`
- `ai-provider-openai/src/commonMain/kotlin/me/rerere/ai/provider/openai/OpenAIKmpProvider.kt`
- `ai-provider-openai/src/commonMain/kotlin/me/rerere/ai/provider/openai/SseEvent.kt`

`ChatViewModel.stopGeneration()` 只取消 Swift `Task` 并设置 UI 状态：

```swift
streamingTask?.cancel()
streamingTask = nil
isLoading = false
```

但实际流式收集通过 `withCheckedThrowingContinuation` 包装 Kotlin Flow collect。Swift task cancellation 没有明确映射到 Kotlin coroutine job / Ktor request cancellation。

影响：

- 用户点击停止后，请求可能仍在服务端继续跑。
- token 可能继续消耗。
- 旧 stream 仍可能回调并更新 `messages`。
- 多次快速发送/停止会放大竞态。

建议：

- Kotlin 层提供可取消 handle，Swift 层持有并在 `cancel()` 时调用。
- Flow 桥接不要只靠 continuation，应明确处理 cancellation。
- 给 `stopGeneration()` 增加测试或手动验证：停止后服务端连接关闭，旧流不能再更新 UI。

### 4. Rust XCFramework deployment target 不一致

相关文件：

- `native/build-xcframework.sh`
- `iosApp/project.yml`

App target 是 iOS 26.0，但 `amber_ffi` 链接时出现 object files built for iOS simulator 26.5 的警告。构建脚本没有固定 `IPHONEOS_DEPLOYMENT_TARGET`。

影响：

- 现在是 warning，未来可能变成链接或上架兼容性问题。
- Debug/CI/本机环境不同会产生不一致产物。

建议：

- 在 Rust iOS build 中显式设置 `IPHONEOS_DEPLOYMENT_TARGET=26.0`。
- 同步 device 和 simulator target。
- 重新生成 XCFramework 后用真实 App link 验证。

## P1 高风险问题

### 1. Markdown AST reader 对坏数据不安全

相关文件：

- `iosApp/iosApp/PackedAstReader.swift`
- `iosApp/iosApp/MarkdownView.swift`

`PackedAstReader.readVarint()` 直接访问 `blob[cursor]`，越界会崩。`root()` 返回根节点前也没有强制 `validate()`。如果 Rust AST 编码版本变化、数据损坏、offset 不匹配，Swift UI 可能直接 crash。

建议：

- `readVarint()` 改成 throwing 或返回 optional。
- `root()` 前执行 header/version/bounds 校验。
- `MarkdownView` 对解析失败显示 plain text fallback。

### 2. Kotlin/Native FFI wrapper 对空 ByteArray 不安全

相关文件：

- `core/native/src/nativeMain/kotlin/app/amber/agent/core/nativebridge/AmberNativeBridge.kt`
- `native/amber-ffi/src/lib.rs`

Kotlin/Native wrapper 多处使用 `addressOf(0)`。当 `ByteArray` 为空时会崩。Rust FFI 已经能处理 null pointer + zero length，因此问题主要在 Kotlin 包装层。

影响范围包括：

- PBKDF2 salt
- AES encrypt/decrypt 输入
- SHA-256 data
- HMAC key/message

建议：

- Kotlin 层对空数组传 null pointer 和 length 0。
- 给每个 native crypto API 增加 empty input smoke test。

### 3. OpenAI streaming usage 会被丢掉

相关文件：

- `ai-provider-openai/src/commonMain/kotlin/me/rerere/ai/provider/openai/OpenAIKmpProvider.kt`
- `ai-core/src/commonMain/kotlin/me/rerere/ai/core/MessageStreamAccumulator.kt`

Provider 请求了 `stream_options.include_usage`，但 accumulator 对空 `choices` chunk 直接 return。OpenAI 最后的 usage-only chunk 通常就是空 choices，因此 token usage 会丢失。

影响：

- token 统计不准。
- run record / billing / debug 数据不可信。

建议：

- accumulator 先处理 usage，再判断 choices。
- 增加一个 usage-only final chunk 的单元测试。

### 4. OpenAI `listModels()` 隐藏 HTTP 错误

相关文件：

- `ai-provider-openai/src/commonMain/kotlin/me/rerere/ai/provider/openai/OpenAIKmpProvider.kt`

`listModels()` 没有检查 HTTP status。鉴权失败、base URL 错误、服务端错误可能都被解析成空列表。

影响：

- UI 可能显示“没有模型”，但真实问题是鉴权或网络错误。
- 用户难以诊断配置问题。

建议：

- 非 2xx 返回结构化错误。
- 把 provider error message 传到设置页或 debug log。

### 5. iOS API key 明文存储在 UserDefaults

相关文件：

- `iosApp/iosApp/SettingsStore.swift`
- `iosApp/iosApp/SettingsView.swift`

`SecureField` 只隐藏 UI 输入，不保护落盘。当前 `apiKey` 被编码进 JSON 后保存在 UserDefaults。

影响：

- API key 可被普通偏好存储读取。
- 不符合移动端敏感凭据存储习惯。

建议：

- API key 迁到 Keychain。
- UserDefaults 只保存 base URL、model、非敏感偏好。
- 设置页保存失败需要反馈。

## iosApp / UI Review

### ChatView

相关文件：

- `iosApp/iosApp/ChatView.swift`

问题：

- `ForEach(Array(messages.enumerated()), id: \.offset)` 使用 offset 作为 identity。插入、删除、重新生成、切换分支时会导致 SwiftUI 状态错位。
- `scrollTo(messages.count - 1)` 在清空消息或切换会话时可能产生负 id。

建议：

- 使用稳定 message id，而不是数组下标。
- scroll 前 guard `messages.indices.last`。

### ChatViewModel

相关文件：

- `iosApp/iosApp/ChatViewModel.swift`

问题：

- 直接调用 provider，没有走 agent runtime。
- streaming cancellation 不完整。
- Room 写入失败只打印。
- `inputDigest` 写入的是用户原文，不是 digest。

影响：

- iOS 端行为和 Android agent runtime 分叉。
- 权限、工具、事件、运行记录的语义没有真正移植。
- 运行记录可能泄露正文。

建议：

- 短期：明确这是 P4 vertical slice，只做 text chat。
- 中期：Swift 调用共享 runtime facade，而不是直接拼 provider。
- `inputDigest` 改为稳定 hash，并避免保存原文到 digest 字段。

### MarkdownView

相关文件：

- `iosApp/iosApp/MarkdownView.swift`
- `iosApp/iosApp/PackedAstReader.swift`

问题：

- body 解析路径中修改 `@State` cache。
- link 只做视觉样式，没有点击行为。
- table/math/html/footnote/task list marker 等 node type 没有完整渲染。
- code block 直接 slice source，需确认 offset 是否包含 fence/language。

建议：

- 把 AST parse/cache 放到 model 或纯函数缓存，不在 body update 中写 state。
- Link 使用 `OpenURLAction`。
- 不支持的 block 明确 fallback 为 plain text，而不是半渲染。

### MessageBubbleView

相关文件：

- `iosApp/iosApp/MessageBubbleView.swift`
- `docs/ios-port/IOS_LIQUID_GLASS_DESIGN_SYSTEM.md`

问题：

- assistant 正文气泡使用 `.ultraThinMaterial`，和设计文档中“阅读面不用玻璃”的约束冲突。

建议：

- 正文阅读面使用 solid background。
- 玻璃效果只用于导航、工具栏、浮层或低文本密度区域。

### Settings

相关文件：

- `iosApp/iosApp/SettingsStore.swift`
- `iosApp/iosApp/SettingsView.swift`

问题：

- API key 存 UserDefaults。
- 每个字段 `didSet` 立即保存，缺少 URL/model 校验。
- 没有连接测试入口。

建议：

- API key 用 Keychain。
- 保存前校验 base URL。
- 增加最小 provider health check / list models 反馈。

### AppShell / Placeholder

相关文件：

- `iosApp/iosApp/AppShell.swift`
- `iosApp/iosApp/PlaceholderViews.swift`
- `iosApp/iosApp/ContentView.swift`

问题：

- `Workspace`、`Assistants` 仍是 placeholder。
- `ContentView.swift` 看起来已不是实际入口，但仍保留在 target 中，容易误导后续维护。

建议：

- 明确 P5 IA 哪些 tab 是真实功能，哪些是占位。
- 对废弃入口文件做标注或在获得授权后删除。

## shared / KMP Review

### Shared framework 导出面过大

相关文件：

- `shared/build.gradle.kts`
- `core/types/build.gradle.kts`

当前 `shared` export 了大量 core/feature API。实测：

- `Shared.h` 约 19,087 行。
- `swift_name` 约 6,782 个。
- `Shared.framework` 约 81MB。

影响：

- Swift 自动补全和编译成本高。
- 内部 Room/entity/tool/runtime API 泄露到 App 层。
- iOS 端容易绕过预期 facade 直接调用底层对象。

建议：

- 为 iOS 建一个窄 facade module，只 export Swift 需要的接口。
- UI 不直接 import 大量 runtime/entity。
- 把 Room、内部 tool schema、agent internal state 从 Swift public surface 中收回。

### SKIE 禁用

相关文件：

- `shared/build.gradle.kts`

SKIE 因 Kotlin 2.3.21 兼容问题被注释。结果是 Swift 侧直接面对 Kotlin/Native 原始类型和 Flow collector。

影响：

- Swift 代码样板多。
- Flow/coroutine cancellation 更容易写错。
- UUID、Long、Float 等类型桥接体验差。

建议：

- 在 Kotlin 版本和 SKIE 兼容后恢复。
- 在恢复前，用手写 Swift-friendly facade 降低调用复杂度。

### Settings 模块未 KMP 化

相关文件：

- `core/settings/build.gradle.kts`
- `iosApp/iosApp/SettingsStore.swift`

`core/settings` 仍是 Android library，iOS 另写 `SettingsStore`。这会造成设置 schema、校验、默认值和迁移逻辑分叉。

建议：

- 抽出 common settings schema / validation。
- 平台层只负责存储介质：Android DataStore，iOS UserDefaults + Keychain。

## AI Provider Review

### OpenAI message 转换不完整

相关文件：

- `ai-provider-openai/src/commonMain/kotlin/me/rerere/ai/provider/openai/OpenAIKmpProvider.kt`

问题：

- 非 assistant message 只序列化 `Text` part。
- image/document/audio/video 等 `UIMessagePart` 被忽略。
- assistant response 只解析 primitive string content，不处理 content array。

影响：

- iOS P4 text chat 可用，但与 `UIMessage` 抽象能力不匹配。
- 后续多模态、附件、document-as-prompt 容易出现静默丢内容。

建议：

- 明确当前 provider 支持矩阵。
- 对不支持 part 返回错误或 warning，不要静默丢弃。
- 增加 message conversion tests。

### SSE Flow 生命周期不够明确

相关文件：

- `ai-provider-openai/src/commonMain/kotlin/me/rerere/ai/provider/openai/SseEvent.kt`

问题：

- `callbackFlow` 内部直接执行 `sse { incoming.collect { ... } }`。
- `awaitClose {}` 在正常收集结束后才到达，对取消路径没有显式 close handle。

建议：

- 明确 Ktor SSE session 的取消和关闭路径。
- 和 Swift cancellation handle 一起设计，不要只依赖外层 task cancellation。

## Agent Runtime Review

### iOS 端绕过 agent runtime

相关文件：

- `iosApp/iosApp/ChatViewModel.swift`
- `core/agent-runtime-impl/src/main/kotlin/app/amber/agent/core/agent/runtime/impl/InProcessAgentRunner.kt`

iOS 目前直接 provider streaming，没有走 agent runtime。因此 Android 上的 assistant 配置、工具、权限、事件、运行状态、handoff 等语义没有在 iOS 闭环。

建议：

- 短期在文档里明确 P4 只验证 provider streaming。
- 下一阶段提供 iOS 可调用的 `AgentRuntimeFacade`。
- Swift 只关心 send/cancel/state stream，不直接拼底层 provider。

### LegacyRunScope 风险

相关文件：

- `core/agent-runtime/src/commonMain/kotlin/app/amber/agent/core/agent/runtime/LegacyRunScope.kt`

`LegacyRunScope` 是 no-op event/tools/llm/tracing，权限 auto-approve。若未来 iOS 复用它，会导致工具和权限行为与 Android 不一致。

建议：

- 不要把它作为 iOS runtime 默认实现。
- iOS 必须显式提供 permission、event、tool handling。

### inputDigest 语义不一致

相关文件：

- `iosApp/iosApp/ChatViewModel.swift`
- `core/agent-runtime-impl/src/main/kotlin/app/amber/agent/core/agent/runtime/impl/InProcessAgentRunner.kt`

Android runtime 使用 `input.hashCode().toString()`，iOS 当前写用户原文。两者都不理想：前者不是稳定 digest，后者会泄露正文。

建议：

- 定义稳定 digest：例如 SHA-256 over canonical input。
- 原文和 digest 字段分开，不要混用。

## Native / Rust FFI Review

### 空输出与错误混淆

相关文件：

- `native/amber-ffi/src/lib.rs`

`vec_to_cbytes` 对空 Vec 返回 null。调用侧无法区分合法空输出和错误。

建议：

- 空输出返回非 null sentinel 或明确 out_len=0 + status code。
- C ABI 最好统一返回 status，数据通过 out pointer 返回。

### UTF-8 错误被吞掉

相关文件：

- `native/amber-ffi/src/lib.rs`

`cstr_to_str` 遇到非法 UTF-8 返回空字符串。这会隐藏真实输入错误。

建议：

- 返回 Result。
- FFI 层暴露结构化错误，而不是把错误变成空字符串。

### 手写 JSON

相关文件：

- `native/amber-ffi/src/lib.rs`

`reader_extract` 手写 JSON 字符串和 escape。当前看起来能处理基础字符串，但后续 schema 变化容易出错。

建议：

- 使用 `serde_json` 生成 JSON。
- 给包含换行、引号、反斜杠、unicode 的 case 加测试。

### XCFramework 构建方式较脆弱

相关文件：

- `native/build-xcframework.sh`

脚本手动拼 XCFramework 目录，没有用 `xcodebuild -create-xcframework`。结合 deployment target warning，说明产物 metadata 和 target 控制需要收紧。

建议：

- 固定 target。
- 尽量用 `xcodebuild -create-xcframework`。
- 产出后用 `lipo`、`otool`、真实 App link 验证。

## Build / CI Review

### setup.sh 与 project.yml 不一致

相关文件：

- `iosApp/setup.sh`
- `iosApp/project.yml`

问题：

- `setup.sh` 跑的是 `compileKotlinIosSimulatorArm64`，不会生成 `Shared.framework`。
- 脚本提示 framework 输出在 `shared/build/apple`，但 `project.yml` 用的是 `shared/build/bin/iosSimulatorArm64/debugFramework`。
- 脚本提示 embed `Shared.framework`，但 `project.yml` 里 `Shared.framework` 是 `embed: false`，且 shared framework 是 static。

建议：

- `setup.sh` 改为执行 `:shared:linkDebugFrameworkIosSimulatorArm64`。
- 文档、脚本、project.yml 的路径统一。
- 明确 static framework 不需要 embed。

### 缺少可复现 Xcode 构建入口

问题：

- 仓库没有 `.xcodeproj`。
- 当前环境没有 `xcodegen`。
- 因此无法直接跑标准 `xcodebuild`。

建议：

- 在文档中固定生成命令。
- CI 安装或缓存 xcodegen。
- 增加 `xcodebuild -scheme AmberAgent -destination 'platform=iOS Simulator,...' build`。

### 测试覆盖不足

当前相关模块只看到少量 JVM tests，没有 Swift tests、iOS link tests、FFI smoke tests。

建议最小测试集：

- OpenAI stream usage-only chunk accumulator test。
- OpenAI message conversion unsupported part test。
- Room iOS smoke test：open/write/read。
- Native empty input tests：SHA-256、HMAC、AES、PBKDF2。
- PackedAstReader corrupted blob tests。
- Swift cancellation manual/integration test。

## 建议修复顺序

1. 修 iOS 最终链接：Room/SQLite/bundled driver/native linker。
2. 修 Room 初始化：Kotlin 层提供 iOS database builder 和 path。
3. 修 streaming cancel：Swift cancel 明确传播到 Kotlin coroutine/Ktor SSE。
4. 修 Native FFI 空数组和空输出边界。
5. 修 Markdown AST bounds check 和 fallback。
6. API key 迁 Keychain。
7. 收窄 `Shared.framework` 导出面，提供 Swift-friendly facade。
8. 补 iOS CI：shared framework、native XCFramework、Swift typecheck、真实 App link、xcodebuild。

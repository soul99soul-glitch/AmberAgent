# AmberAgent iOS 移植工作 Review Prompt

> 给另一个 AI Agent 的 review 指令。请将本文件连同下面列出的关键文件一起提供给 reviewer。

---

## 1. 项目背景

AmberAgent 是一个 Android 原生 AI Agent 应用（约 16 万行 Kotlin），核心功能是与多种 LLM 提供商（OpenAI、Google Gemini、Anthropic Claude 等）进行流式对话，支持工具调用、记忆、文档解析、代码高亮等。应用架构为 Jetpack Compose + MVVM，使用 Room 数据库、DataStore、Koin DI。

项目内有一组 Rust crate（`native/` 目录，12 个 crate）负责 markdown 渲染、代码高亮、文档解析、加密、token 计数等，通过 JNI 桥接 Android 端。

**当前正在进行 iOS 移植**，策略是：KMP 共享业务内核 + SwiftUI Liquid Glass 重做 UI + Rust crate 通过 C-ABI FFI 复用。

---

## 2. 仓库结构

项目使用 git worktree 分为两个独立目录：

- `/Users/arquiel/Downloads/AI/amberagent` — **main 分支**，Android 主线，**不要修改**
- `/Users/arquiel/Downloads/AI/amberagent-ios` — **codex/ios-port-wip 分支**，iOS 移植工作目录

**请只在 `amberagent-ios` 目录操作。** 仓库根目录下有多个历史副本目录（`main/`、`legacy/`、`jank-opt/`、`ui-graphite/`、`arch/`、`OpenOmniBot/`），**搜索时必须排除它们**。

---

## 3. 原始计划书

完整的移植计划书在 `docs/IOS_PORT_PLAN_2026-06-12.md`（308 行），包含 6 个 Phase、20+ 任务卡，请先通读。以下是要点摘要：

### 阶段总览
```
P0 基线与盘点 → P1 纯 JVM 模块转 KMP → P2 网络/数据层跨平台化 → P3 Rust 引擎 iOS 化 → P4 iOS 工程 + 最小可用链路 → P5 Liquid Glass 全量 UI → P6 CI/TestFlight
```

### 关键技术决策
| 决策点 | 结论 | 理由 |
|---|---|---|
| 跨端方案 | KMP 共享逻辑 | 大量纯 Kotlin 模块近零成本转换 |
| iOS UI | SwiftUI + Liquid Glass | 拿不到系统玻璃材质与原生手感 |
| 网络 | OkHttp → Ktor Client | 行为对 Android 透明，iOS 获得原生 NSURLSession |
| 数据库 | Room KMP (BundledSQLiteDriver) | 已在用版本支持，迁移成本最小 |
| Rust | C-ABI (cbindgen) + XCFramework | Rust 交叉编译 iOS 是成熟路径 |
| Kotlin↔Swift 桥 | SKIE（但当前因 Kotlin 2.3.21 不兼容而禁用） | — |
| ViewModel | 不共享，iOS 用 Swift @Observable | UI 重新设计，强行共享只会别扭 |

---

## 4. 已完成的工作

共 8 个提交在 `codex/ios-port-wip` 分支（基于 origin/main）：

```
f831a5d2 ios-port(P4-T2): fix Swift K/N API usage against actual ObjC header
5220a88f ios-port(P4-T2): minimal chat vertical slice — Swift UI + KMP types
c0ca10b5 ios-port(P4-T1): add export() to Shared.framework for ObjC header generation
fef8507d chore: add shared/ and iosApp/ build dirs to .gitignore
7258a393 ios-port(P3-T3 + P4-T1): core:native cinterop bridge + shared umbrella + iosApp skeleton
364f85cc ios-port(P3): Rust C-ABI FFI export layer + XCFramework packaging
f38596f6 ios-port(P2-T2): complete OkHttp → Ktor migration for :ai module
2488e253 ios-port(P0-P2): KMP conversion of 27 modules + Ktor SSE migration + type extraction
```

总计 364 文件变更，+8888 / -12771 行。

### P0 基线 (2488e253)
- `assembleDebug` + `test` 全绿
- 24 模块依赖审计：21 可直接转 KMP，3 个少量 Android 泄漏
- 报告：`docs/ios-port/P0_BASELINE.md`、`P0_LEAK_AUDIT.md`

### P1 KMP 转换 (2488e253)
- 16 个纯 JVM/少量泄漏模块 → KMP（`jvm()` + `iosArm64()` + `iosSimulatorArm64()`）
- 模板：`docs/ios-port/P1_CONVERSION_TEMPLATE.md`（以 `core/agent-utils` 为样板）
- Android 泄漏剥离：`Rect` → expect/actual, `Log` → expect/actual, `Context` → `Any?`
- 5 个惯性 `android.library` 接口模块 → KMP（无实际 Android 依赖）

### P2 网络与数据层 (2488e253, f38596f6)
- **P2-T1**: `:ai` 拆分 → `ai-core`（KMP，19 纯类型文件）+ `:ai`（provider 实现，保持 android.library）
- **P2-T2**: OkHttp → Ktor 全量迁移（~30 函数/10 文件）：
  - 4 AI provider SSE（Claude, Google, ChatCompletionsAPI, ResponseAPI）
  - 18 搜索服务文件
  - 7 TTS provider
  - 非流式 HTTP（`generateText`, `listModels`, `getBalance`, `generateEmbedding`, `generateImage`）
  - OAuth（OpenAICodexOAuth, GoogleGeminiOAuth, ServiceAccountTokenProvider）
  - `:ai` 模块现在 **零 `import okhttp3.*`**
  - `okhttp-sse` 依赖从 `:ai` 和 `:common` 移除
  - `SseEvent.Failure` 简化为 `Failure(throwable: Throwable?)`
- **P2-T3**: Room KMP — `core/agent-store-room` + `BundledSQLiteDriver`
- **P2-T4**: DataStore → `datastore-preferences-core`; Koin catalog 就绪
- **额外**: `core:types` 提取 — 从 `core:model`（9 文件）和 `core:settings`（Settings 数据类 + DefaultProviders + 嵌套类型）分离到独立 KMP 模块

### P3 Rust 引擎 iOS 化 (364f85cc, 7258a393)
- **P3-T1**: 全部 10 Rust crate 成功编译 `aarch64-apple-ios` + `aarch64-apple-ios-sim`
- **P3-T2**: `amber-ffi` crate 创建 — 22 个 `#[no_mangle] extern "C"` FFI 函数，覆盖全部 JNI 桥接操作
  - `cbindgen` 生成 194 行 C 头文件 `AmberNative.h`
  - `AmberNative.xcframework` 手动打包（32MB/slice，device + simulator）
  - `build-xcframework.sh` 使用手动打包（不依赖 `xcodebuild -create-xcframework`）
  - 内存约定文档：`native/FFI_CONVENTIONS.md`
- **P3-T3**: `core/native/` KMP cinterop 模块
  - `AmberNativeBridge` expect/actual — nativeMain 通过 `kotlinx.cinterop` 调用全部 FFI 函数
  - jvmMain actual 返回 null（Android 保持自己的 JNI 桥接）
  - `TokenCounterNative` nativeMain 通过 `AmberNativeBridge.tokenizerCount()` 调用 Rust

### P4 iOS 工程 (fef8507d, c0ca10b5, 5220a88f, f831a5d2)
- **P4-T1**: `:shared` KMP umbrella 模块（25 个 `api()` + `export()` 依赖）
  - 关键发现：`api()` 不会让类型出现在 ObjC header → 改用 `export()` 后 Shared.h 从 167 行暴涨到 15159 行，4900+ Swift 可见类型
  - `iosApp/` 骨架：SwiftUI App, ContentView, XcodeGen project.yml, setup.sh
  - SKIE 因不兼容 Kotlin 2.3.21 而禁用
- **P4-T2**: 最小聊天垂直切片
  - `ChatViewModel.swift` — `@Observable`，使用 KMP 类型（UIMessage, Conversation, MessageNode, AmberNativeBridge）
  - `ChatView.swift` — NavigationStack + LazyVStack + glass input bar
  - `MessageBubbleView.swift` — 用户/助手气泡 + 可折叠推理块
  - Swift 代码已对照 15159 行 ObjC header 精确验证所有 K/N API 调用
  - **4 个 Swift 文件全部通过 `swiftc` 编译（零错误）**
  - `project.yml` 使用条件 sdk 路径（`[sdk=iphonesimulator*]` / `[sdk=iphoneos*]`）

### 当前 28 个 KMP 模块
```
ai-core, core:types, core:native, shared,
core:agent-utils, core:ai-prompts, core:agent-runtime, core:event, core:llm,
core:sync:api, core:automation:api, core:ai:api, core:context:api, core:memory:api,
core:agent-store-room, core:ai:transformers:api, core:ai:generation:api,
feature:{history, webview, board:api, chat:api, deepread:api, live:api,
         office:api, terminal:api, modelcouncil:api, subagent:api, runtime:api, tools:api}
```

---

## 5. 已知问题和待办

1. **SKIE 兼容性**：SKIE 0.9.5 不支持 Kotlin 2.3.21，禁用中。没有 SKIE 意味着 Flow 在 Swift 中是 `kotlinx.coroutines.flow.Flow`（不透明），suspend fun 只能用 completion handler。等 SKIE 发布兼容版本后启用。

2. **`:ai` 保持 android.library**：Provider 实现使用 Android 类型（Context, Log, Intent, Bitmap, ExifInterface），iOS 需要在 Swift 侧原生实现 provider。共享类型在 `ai-core`（KMP）。

3. **ConversationDAO 在 app 模块**：Android 的 `ConversationDAO`、`MessageNodeDAO` 在 `app/` 中（Room，绑定大量 app 类型），未转入 KMP。iOS 端需要自行实现会话持久化（或后续在共享层新建一个轻量 DAO）。

4. **core:native cinterop 导出警告**：`linkDebugFramework` 时有 "Interop library ... can't be exported with -Xexport-library" 警告。AmberNativeBridge 的 Kotlin wrapper 正常导出，cinterop 底层 C 类型不导出（不影响使用）。

5. **P4-T2 最小链路**：当前用 mock response，真实 AI provider 调用未接通。计划书中 P4-T2 的验收标准（"模拟器+真机各完成一次真实 API 的完整流式对话"）尚未满足。

6. **Xcode 完整项目**：未用 XcodeGen 生成 `.xcodeproj`（XcodeGen 未安装），用 `swiftc` 直接编译验证。完整项目构建需安装 XcodeGen 或手动创建 Xcode 项目。

7. **计划书中 P3-T2 和 P3-T3 状态标记为 ⬜ 未开始**，但实际已完成。计划书文档未同步更新。

---

## 6. 请 Review 的重点

### 6.1 架构决策
- `:ai` 保持 android.library 的决定是否合理？是否有更好的拆分方式？
- `core:types` 从 `core:model` 和 `core:settings` 提取的边界是否清晰？有无遗漏或多余？
- 28 个 KMP 模块的粒度是否合理？是否应该合并一些？
- `core:native` 作为统一 cinterop 桥接层的做法是否可扩展？

### 6.2 OkHttp → Ktor 迁移质量
- SSE 流式迁移是否完整？有没有遗漏的 OkHttp 使用？
- `SseEvent.Failure` 简化是否会影响错误处理？
- `GoogleGeminiOAuth.generateContent()` 返回 `CloudCodeAssistRequest` data class 而非 `okhttp3.Request`，这个接口变化是否合理？

### 6.3 Rust FFI 层
- 22 个 FFI 函数的签名是否合理？内存管理约定（`amber_free_string`、`amber_free_bytes`）是否安全？
- `amber-ffi` 将所有 crate 合并为一个 FFI 入口是否是正确的粒度？
- cinterop `.def` 文件和 `AmberNativeBridge` 的 expect/actual 设计是否健壮？

### 6.4 Shared Framework 打包
- `export()` 对 28 个模块的逐一导出是否会导致过大的 framework binary（当前 debug 65MB）？
- `transitiveExport = true` 是否是更好的选择？
- cinterop 库不能被 export 的限制是否有更优解？

### 6.5 Swift / KMP 类型桥接
- 当前无 SKIE，所有 suspend fun 变 completion handler、Flow 不透明、sealed class 变 ObjC 子类。这是否会严重影响 P5 的 UI 开发体验？
- `UIMessage` 的 9 参数构造器在 Swift 中很笨重。是否应该提供 Kotlin 侧的工厂扩展函数来简化？
- `KotlinUuid`、`KotlinInstant`、`Kotlinx_datetimeLocalDateTime` 这些类型名在 Swift 中很冗长。有没有更好的映射方式？

### 6.6 遗漏和风险
- 有没有应该转 KMP 但没转的模块？
- Android 端是否有运行行为回归的风险（虽然 `assembleDebug` 通过，但运行时行为是否验证过）？
- Room KMP 在 iOS 上的实际表现是否测试过？
- `core:model` 保留在 Android 侧，但 `core:types` 提取了部分类型，两边的依赖关系是否会有循环依赖问题？

---

## 7. Review 需要看的文件

### 最高优先级
```
docs/IOS_PORT_PLAN_2026-06-12.md          # 完整计划书
settings.gradle.kts                        # 模块包含列表
build.gradle.kts                           # 根 build（KMP/Room/SKIE 插件）
gradle/libs.versions.toml                  # 依赖版本目录
shared/build.gradle.kts                    # umbrella 模块（export + api）
```

### 新增模块
```
ai-core/build.gradle.kts                   # AI 共享类型模块
ai-core/src/commonMain/kotlin/             # 19 个纯类型文件
core/types/build.gradle.kts                # 提取的纯模型模块
core/types/src/commonMain/kotlin/          # Settings + 模型类型
core/native/build.gradle.kts               # cinterop 桥接模块
core/native/src/                           # AmberNativeBridge expect/actual
core/agent-store-room/build.gradle.kts     # Room KMP
```

### Rust FFI
```
native/amber-ffi/src/lib.rs                # 22 个 FFI 函数（595 行）
native/amber-ffi/cbindgen.toml             # 头文件生成配置
native/amber-ffi/AmberNative.h             # 生成的 C 头文件
native/build-xcframework.sh                # XCFramework 打包脚本
native/FFI_CONVENTIONS.md                  # 内存管理约定
```

### Swift
```
iosApp/iosApp/ChatViewModel.swift           # KMP 类型使用示范
iosApp/iosApp/ChatView.swift                # SwiftUI 聊天视图
iosApp/iosApp/MessageBubbleView.swift       # 消息气泡组件
iosApp/project.yml                          # XcodeGen 配置
```

### 关键改动文件
```
ai/build.gradle.kts                         # OkHttp → Ktor，零 okhttp3 import
ai/src/main/java/.../SSE.kt                # Ktor SSE 实现
common/src/main/java/.../http/SSE.kt       # 共享 sseFlow()（OkHttp 侧已删除）
core/llm/src/commonMain/kotlin/            # TokenCounter expect/actual
core/settings/src/.../PreferencesStore.kt   # 瘦身为 Android DataStore 委托
```

### 辅助报告
```
docs/ios-port/P0_BASELINE.md
docs/ios-port/P0_LEAK_AUDIT.md
docs/ios-port/P1_CONVERSION_TEMPLATE.md
```

---

## 8. 验证命令

```bash
cd /Users/arquiel/Downloads/AI/amberagent-ios

# Android 不回归
./gradlew :app:assembleDebug

# 所有 KMP 模块 iOS 编译
./gradlew :shared:compileKotlinIosSimulatorArm64

# Shared.framework 构建
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkDebugFrameworkIosArm64

# Swift 编译验证（需要先构建 framework）
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

# Rust iOS 交叉编译
cd native && cargo build --target aarch64-apple-ios --release && cargo build --target aarch64-apple-ios-sim --release

# 检查 :ai 零 OkHttp import
grep -r "import okhttp3" --include="*.kt" ai/src/main/java/
# 应该返回空

# 查看导出的 Swift 类型数量
grep "swift_name" shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers/Shared.h | wc -l
# 应该 > 4000
```

---

## 9. 技术栈版本

| 组件 | 版本 |
|---|---|
| Kotlin | 2.3.21 |
| Gradle | 9.4.1 |
| AGP | 9.2.0 |
| Ktor | 3.4.3 |
| Room | 2.8.4 |
| Koin | BOM 4.2.1 |
| DataStore | 1.2.1 |
| kotlinx.serialization | 1.11.0 |
| kotlinx.coroutines | 1.10.2 |
| kotlinx-datetime | 0.7.1 |
| SKIE | 0.9.5（禁用） |
| Rust | stable（iOS target） |
| Xcode | 26.5 (17F42) |
| iOS deployment target | 26.0 |
| Swift | 6.1 |

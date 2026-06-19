# AmberAgent iOS 权限能力最终完成调试计划

日期：2026-06-14
范围：iOS 权限能力注册、平台 gate、本地 executor、Document Picker 单文件读取、`permissions_status`、Chat 文件 preview 附加链路。
状态：功能阶段已完成；后续仅做调试、收敛、验证，不再开发新权限能力。

## 最终完成目标

我们认为该权限能力阶段最终完成的标准是：

1. iOS 端所有本地能力调用都只能通过统一 executor 进入。
2. `file_pick` 只能由前台 UI 触发，不能作为可执行工具被调用。
3. `file_read_selected` 必须经过 policy、runtime gate、grant 匹配、TTL、使用次数和文件大小校验。
4. `permissions_status` 能稳定返回 iOS capability 状态，不触发系统权限，不读取用户数据，不复用 Android permission 文案。
5. Chat 只消费用户显式附加的 selected-file preview，不会在发送消息时隐式读取文件。
6. unsupported / planned / iOS-only disabled 能力不可执行，且 UI/状态快照能解释原因。
7. Swift app 源码 typecheck 通过；XcodeGen 可用后 XCTest 能编译并覆盖关键路径。
8. Android 回归若失败，失败点必须明确与 iOS 权限能力无关。

## 当前已完成

- 已建立 iOS capability registry，区分 `uiActionNames`、`modelToolNames`、`blockedToolNames`。
- 已建立 `IOSPermissionStore`，policy 使用 `UserDefaults` 持久化，grant 不持久化。
- 已建立 `IOSToolRuntime`，负责 policy、capability 状态、grant 匹配和文件读取 gate。
- 已建立 `IOSLocalToolExecutor`，作为 iOS 本地能力统一入口。
- 已建立 `IOSPermissionsStatusSnapshot`，供未来 UI/debug/agent context 使用。
- 已完成 Document Picker 单文件 grant：内存态、10 分钟 TTL、1 次使用、2MB 文件上限、64KB preview。
- 已完成 Chat 显式 `attachSelectedFilePreviewToNextMessage()`，发送消息只消费 pending preview。
- 已新增 Swift tests target 和核心测试文件。
- 已验证 `iosApp/iosApp/*.swift` 可通过 `swiftc -typecheck`。

## 剩余调试任务

### P1：关闭权限绕过路径

目标：文件读取不能绕过 executor/runtime。

需要调试和收敛：

- 收窄 `DocumentAccessStore` 的读取 API 可见性。
- 避免同 module 代码直接调用 `consumeSelectedFileRead` 绕过 policy。
- 保留 UI 所需的只读 grant summary，但不暴露可直接构造读取请求的完整 grant 能力。
- 确认 `ToolPermissionsView`、Chat attach、测试都只通过 `IOSLocalToolExecutor` 执行读取。

完成标准：

- 禁用 `ios.files.selected_read` 后，即使已有 grant，直接能力入口也不能读取文件。
- 代码搜索中除 runtime/executor 受控路径外，不存在直接消费文件 grant 的调用点。

### P1：补齐 `Ask every time` 语义

目标：`Ask every time` 不能被非用户触发请求复用。

需要调试和收敛：

- 在 runtime gate 中明确 policy 语义：
  - `disabled`：deny。
  - `askEveryTime && !isUserInitiated`：needs user action。
  - `allowOncePerRun`：允许同 run/scope/payload 的受控复用。
- 确认 `file_read_selected` 默认策略不会允许后台或模型静默触发。

完成标准：

- `isUserInitiated=false` 且 policy 为 `askEveryTime` 时，`file_read_selected` 返回 `needsUserAction`。
- `allowOncePerRun` 仍受 grant、scope digest、payload digest、TTL、max uses 限制。

### P1：让 Swift tests 可在 XcodeGen 工程中编译

目标：新增 XCTest target 在真实 Xcode 工程中可运行。

需要调试和收敛：

- `iosAppTests` 继承 app target 必需的 framework/header search paths。
- `iosAppTests` 显式依赖 `Shared.framework`。
- 如测试继续触达 KMP 类型，确保 test target 链接配置与 app target 一致。
- 安装或配置 XcodeGen 后执行完整 XCTest。

完成标准：

- `xcodegen generate` 成功。
- `xcodebuild test -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17'` 至少能编译 test target。

### P1：降低 Chat tests 对 KMP/Room 的依赖

目标：权限 preview 的单元测试不因 KMP、Room、SQLite 初始化失败而脆弱。

需要调试和收敛：

- 避免纯 prompt/preview 测试初始化真实 `OpenAIKmpProvider` 和 `IosDatabaseFactory`。
- 可选调试方向：
  - 将 provider/database 改为 lazy。
  - 或抽出 Swift-only 的 selected-file prompt composition helper。
  - 或对 ChatViewModel 注入轻量 test doubles。
- 保留最多一个真实 ChatViewModel smoke test，避免所有测试都依赖 KMP runtime。

完成标准：

- Chat selected-file prompt 测试只验证 Swift 状态与文本注入，不需要启动数据库。
- 真实 provider/db 初始化问题不会阻塞权限能力核心测试。

### P2：修复 streaming callback 竞态

目标：旧 run 的 callback 不能污染新 run。

需要调试和收敛：

- `onChunk`、`onComplete`、`onError` 回 MainActor 后检查 `currentRunId == runId`。
- cancel 后旧 callback 不得：
  - 覆盖 `messages`。
  - append stale error。
  - 记录 stale run status。
  - 清掉新 run 的 loading 状态。
- `ChatViewModel` deinit 时取消 `streamJob`。

完成标准：

- 快速 cancel / resend 不会出现旧响应覆盖新消息。
- 离开或销毁 ChatViewModel 后不会继续保留无主 stream job。

### P2：修复 attach preview 竞态

目标：文件 preview 只附加到用户意图中的下一条消息。

需要调试和收敛：

- 增加 `isAttachingSelectedFile`。
- attach 时生成 request token，completion 只接受最新 token。
- attach 进行中禁用发送，或发送时忽略未完成 attach。
- 重复点击 attach 不应产生 stale pending preview。

完成标准：

- 用户点击 attach 后立刻发送，不会把稍后完成的 preview 挂到下一条无关消息。
- 重复点击 attach 只保留最后一次有效结果。

### P2：强化文件大小最终校验

目标：2MB 限制不依赖过期 metadata。

需要调试和收敛：

- 在 `startAccessingSecurityScopedResource()` 后重新读取文件大小。
- size unknown 或大于 2MB 时拒绝读取。
- grant 创建后文件变化也不能绕过最终校验。

完成标准：

- `fileSizeKey` 缺失时不会默认放行。
- grant 后文件变大超过 2MB 时读取失败。
- 64KB preview 限制保持不变。

### P2：补强测试断言

目标：测试锁住关键安全语义，而不是只测试“没有成功”。

需要调试和收敛：

- `permissions_status` 在相关 capability disabled 时仍返回 snapshot，并显示 Disabled。
- Android 文案扫描覆盖 snapshot 的所有 string 字段，包括 tool arrays 和 Info.plist keys。
- scope/tool/payload mismatch 必须断言 `.denied`。
- unknown tool、planned tool、blocked Android-only tool 分开断言。
- Chat tests 明确只验证“没有 `UIMessagePart.Tool`”，不宣称已验证 OpenAI params。

完成标准：

- 测试失败信息能直接指向被破坏的 policy/gate 语义。
- 测试不依赖 UI 截图或真实系统权限弹窗。

## 最终验收清单

### 代码验收

- `file_pick` 不在 executable/model tool list。
- `file_read_selected` 只能通过 `IOSLocalToolExecutor` 执行。
- `permissions_status` 不触发系统授权、不读取文件、不依赖 grant。
- `DocumentAccessStore` 不向任意同模块代码暴露可绕过 policy 的读取入口。
- `Ask every time`、`Allow once per run`、`Disabled` 三种 policy 语义明确且有测试。
- Chat 不自动读取文件，只发送 pending preview。
- unsupported/planned/iOS-only disabled 不能启用，也不能执行。

### 测试验收

- Swift app typecheck 通过：

```bash
SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
CLANG_MODULE_CACHE_PATH=/private/tmp/amberagent-swift-module-cache \
xcrun swiftc -typecheck \
  -sdk "$SDK" \
  -target arm64-apple-ios26.0-simulator \
  -F shared/build/bin/iosSimulatorArm64/debugFramework \
  -I shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers \
  -I native/AmberNative.xcframework/ios-arm64-sim/Headers \
  iosApp/iosApp/*.swift
```

- XcodeGen 可用后：

```bash
cd iosApp
xcodegen generate
xcodebuild test -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17'
```

- Android 回归：

```bash
./gradlew test -Djava.awt.headless=true
```

若 Android 仍失败于 `:app:kspDebugKotlin` / `java.awt.Toolkit`，记录为既有 Gradle/KSP 环境问题，不作为 iOS 权限能力阻塞项。

## 不再开发的新功能

本阶段不再加入以下能力：

- HealthKit 真授权或数据读取。
- Core Motion / pedometer 真读取。
- Location、Contacts、Calendar、Camera、Microphone、Notifications 真系统权限请求。
- OpenAI tools schema 暴露。
- 持久化 security-scoped bookmark。
- 文件写入、覆盖、删除。
- 全相册搜索、通讯录 dump、跨 App 自动化、短信读取、通话记录读取。

这些能力只能进入后续独立阶段，不能混入本阶段调试收敛。

## 完成定义

当以下条件同时满足时，本阶段视为最终完成：

1. P1/P2 调试任务全部关闭。
2. iOS app Swift typecheck 通过。
3. XcodeGen 环境可用时，Swift tests 可编译并通过。
4. 手动验证 Document Picker：选择文件、attach preview、发送消息、二次复用失败。
5. 手动验证 policy：Disabled 拒绝、Ask every time 需要用户触发、Allow once per run 仍受 grant 限制。
6. `permissions_status` 快照可用于 UI，不包含 Android 设置文案或 Android permission 字符串。
7. 没有已知的 V3 范围内权限绕过、误授权或不可验证路径。

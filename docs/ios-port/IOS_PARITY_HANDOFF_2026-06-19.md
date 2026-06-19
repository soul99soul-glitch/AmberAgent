# AmberAgent iOS Parity Handoff 2026-06-19

这份文档给接手的 AI 使用。目标是让另一个 agent 不需要翻完整聊天记录，也能理解 AmberAgent iOS 的产品背景、当前分支状态、已经完成的 Android parity 工作、验证结果、已知风险，以及下一步应该怎么继续。

## TL;DR

- 仓库：`/Users/arquiel/Downloads/AI/amberagent-ios`
- 分支：`codex/ios-port-wip`
- 当前 HEAD：`68192b345 Fix iOS parity closure test regressions`
- 当前分支状态：领先 `origin/codex/ios-port-wip` 19 个提交。
- 工作区状态：创建本文档前是干净的。
- 主要结论：iOS 版已经完成了一轮大规模 Android parity closure，覆盖深度阅读、记忆、搜索、图片生成、Mini App、Workspace/File/Artifacts、WebMount、高级执行能力。
- 最新验证：`git diff --check` 通过；iOS build 通过；KMP shared iOS framework 编译/链接通过；全量 `iosAppTests` 通过。
- 下一个 AI 不应重新发明计划，而应先做最终验收、模拟器手工 smoke、warning cleanup、PR/推送准备。

## 产品背景

AmberAgent 是一个 AI agent 应用，Android 版功能更完整，iOS 版正在追平 Android 的正式用户能力。当前产品方向已经明确：

- iOS 只保留一个 Amber Assistant，不做 Android 式多 Assistant 系统。
- 深度阅读、Mini App、WebMount、SubAgent、模型议会是正式高级功能，不放实验区，不做总开关隐藏。
- 记忆、搜索、图片生成、Mini App 都是正式能力，目标是达到 Android 同等能力、功能和水平。
- 收藏暂不做。
- 统计已合并到 iOS User 页面，不再追 Android 独立 Stats 页。
- 系统访问、自动化、分享、外部入口只能在 iOS 平台开放能力范围内适配；iOS 不开放的能力不能伪实现。
- 设置页和功能页不能放“看似设置项，实际只是说明”的工程化内容。
- 不要用“可用 / API Key / 待接 / 已接”这类后缀污染面向用户的页面，除非那确实是用户必须理解的状态。

## 重要文档

请先读这些文档，再判断下一步：

- `docs/ios-port/IOS_ANDROID_PARITY_ROADMAP_2026-06-19.md`
  - Android parity 总路线图。
  - 定义了阶段、产品决策、验证命令和暂停条件。
- `docs/ios-release-readiness-plan.md`
  - iOS 发布收口计划。
  - 包含 Capability Gate Matrix、P0/P1/P2 和后续 work packets。
- `.workflow/amberagent-ios-deep-read-closure/`
  - 深度阅读闭环 workflow 记录。
- `.workflow/amberagent-ios-phase-2-capability-closure/`
  - 记忆、搜索、图片生成、Mini App closure 记录。
- `.workflow/amberagent-ios-phase-3-workspace-files/`
  - Workspace/File/Artifacts closure 记录。
- `.workflow/amberagent-ios-webmount-advanced-closure/`
  - WebMount 高级网页工具 closure 记录。
- `.workflow/amberagent-ios-advanced-execution-closure/`
  - SubAgent、模型议会、远程执行、任务状态、工具审批 closure 记录。

注意：部分 workflow final-report 早期记录了构建阻塞，后来已经被后续提交修掉。不要只读早期报告就下结论，必须以当前 HEAD 的验证结果为准。

## 最近提交基线

```text
68192b345 Fix iOS parity closure test regressions
8880a8fd6 Complete iOS capability parity closures
fb7a4b428 Implement iOS advanced execution capabilities
dd0f86c6f Consolidate iOS capability parity work
31bff7b79 Clean user-facing iOS capability copy
1165248e8 Remove dead iOS routes and pages
1f1645396 Close unsupported iOS UI options
985ddfe7f Wire MiniApp host bridge actions
8fad59cb1 Wire MiniApp AI generate bridge
1e1c80321 Add WebMount clear session approval flow
fa69fcc5d Add iOS memory write approval flow
0b7bac3ef Gate iOS memory tool writes
```

最新提交 `68192b345` 修了 3 个测试暴露出来的回归：

- `IOSBoardPersistence.cleanMultiline`：保留深度阅读源文本换行，不再把多行标题折成一行。
- `IOSAdvancedTaskStore.redacted`：先脱敏 Bearer token，再处理 authorization/password/token key，避免 token 残留。
- `IOSWebMountController.json`：JSON 输出使用 `.withoutEscapingSlashes`，WebMount 时间线里脱敏 URL 仍保持可读。

## 已完成的功能进度

### 1. 深度阅读完整闭环

关键文件：

- `iosApp/iosApp/BoardView.swift`
- `iosApp/iosApp/BoardSettingsView.swift`
- `iosApp/iosApp/IOSBoardPersistence.swift`
- `iosApp/iosApp/IOSSearchExecutor.swift`
- `iosApp/iosApp/IOSConversationStore.swift`
- `iosApp/iosApp/DocumentAccessStore.swift`
- `iosApp/iosApp/WebMountView.swift`
- `iosApp/iosAppTests/IOSBoardPersistenceTests.swift`

当前能力：

- 有 iOS 本地 Deep Read task/source/template/result 模型。
- 支持任务持久化、历史、成功/失败/重试状态。
- 支持手动文本、搜索结果、会话内容、文件、WebMount 来源的归一化。
- 有最小模板校验和 draft generator。
- Board/Deep Read 结果可作为 Workspace Artifact 保存。
- iOS 不承诺 Android WorkManager 等价后台能力，后台策略需按 iOS 能力继续收口。

刚修复过的测试：

- `IOSBoardPersistenceTests.testDeepReadSourceNormalizationCoversManualFileConversationAndWeb`

### 2. 记忆、搜索、图片生成、Mini App

关键文件：

- `iosApp/iosApp/IOSMemoryLibrary.swift`
- `iosApp/iosApp/IOSMemoryPersistence.swift`
- `iosApp/iosApp/MemoryOverviewView.swift`
- `iosApp/iosApp/MemoryEditView.swift`
- `iosApp/iosApp/MemoryToolApprovalCard.swift`
- `iosApp/iosApp/IOSSearchExecutor.swift`
- `iosApp/iosApp/SearchServicesView.swift`
- `iosApp/iosApp/SearchProviderView.swift`
- `iosApp/iosApp/IOSImageGenerationModels.swift`
- `iosApp/iosApp/IOSImageGenerationRepository.swift`
- `iosApp/iosApp/ImageGenerationView.swift`
- `iosApp/iosApp/IOSMiniAppRepository.swift`
- `iosApp/iosApp/IOSMiniAppBridgeRuntime.swift`
- `iosApp/iosApp/MiniAppRunnerView.swift`
- `iosApp/iosApp/MiniAppRunnerWebView.swift`
- `iosApp/iosApp/MiniAppSettingsView.swift`
- `iosApp/iosApp/ChatViewModel.swift`
- `iosApp/iosApp/MessageBubbleView.swift`

当前能力：

- 记忆支持搜索、过滤、来源摘要、召回解释、写入审批记录。
- 搜索支持 provider 选择、DuckDuckGo/Bing/Tavily/Jina 等 mockable 路径、结果进入 Deep Read source、`scrape_web` 最小闭环和本地安全拒绝。
- 图片生成有 iOS repository、参数解析、历史保存、base64 图片落地、聊天工具结果 JSON 和图片消息展示。
- Mini App 有 repository、版本历史、运行器、HTML 校验、bridge storage/search/fetch/AI generate/host actions、权限和 audit。
- 这些能力都按正式功能处理，不再作为实验性入口。

相关测试：

- `IOSMemoryLibraryTests`
- `IOSSearchExecutorTests`
- `IOSImageGenerationRepositoryTests`
- `IOSMiniAppRepositoryTests`
- `IOSMiniAppBridgeRuntimeTests`
- `MiniAppHtmlValidatorTests`
- `IOSMiniAppOutputParserTests`
- `ChatViewModelSelectedFileContextTests`

### 3. Workspace / 文件 / Artifacts

关键文件：

- `iosApp/iosApp/DocumentAccessStore.swift`
- `iosApp/iosApp/PlaceholderViews.swift`
- `iosApp/iosApp/ChatView.swift`
- `iosApp/iosApp/ChatViewModel.swift`
- `iosApp/iosApp/MessageBubbleView.swift`
- `iosApp/iosApp/IOSConversationStore.swift`
- `iosApp/iosApp/IOSLocalToolExecutor.swift`
- `iosApp/iosApp/PermissionsApprovalView.swift`
- `iosApp/iosAppTests/DocumentAccessStoreTests.swift`
- `iosApp/iosAppTests/IOSLocalToolExecutorTests.swift`

当前能力：

- iOS 有 app-local Workspace file registry。
- 支持文件导入/copy、metadata、解析状态、预览、reparse、remove。
- 文本、Markdown、JSON/CSV、PDF、DOCX 有测试覆盖；图片 OCR 和不支持二进制保持诚实降级。
- Workspace Artifact 支持保存、读取、删除。
- Chat text、generated image、Mini App 输出、MiniApp host artifacts、Deep Read 结果可以沉淀为 Artifact。
- Workspace 工具读取/写入/delete 走权限审批，不做 Android all-files/external-root 语义。

注意：

- iOS 不应自动扫描用户目录。
- 不要把 Android 外部文件 root/all-files 能力照搬到 iOS。
- Durable external-folder bookmarks 仍需产品隐私策略，不要贸然扩大文件访问范围。

### 4. WebMount 高级网页工具

关键文件：

- `iosApp/iosApp/WebMountView.swift`
- `iosApp/iosApp/IOSLocalToolExecutor.swift`
- `iosApp/iosApp/ChatViewModel.swift`
- `iosApp/iosApp/ChatView.swift`
- `iosApp/iosApp/MessageBubbleView.swift`
- `iosApp/iosApp/IOSPermissionModels.swift`
- `iosApp/iosAppTests/IOSLocalToolExecutorTests.swift`
- `iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`
- `iosApp/iosAppTests/IOSCapabilityRegistryTests.swift`

当前能力：

- WebMount 是正式高级功能，不是实验性功能。
- 有站点 registry、seed、enable/disable、add/remove/restore。
- 有 URL allowlist，阻止 file/javascript/data 和未注册 host。
- 有 WKWebView runtime state、open/state/extract/get/back/forward/clear session。
- 有 cookie summary/clear session 路径，但不暴露 cookie value。
- 有只读 bridge 和 WebMount safe extraction。
- WebMount tool output、approval preview、chat timeline 都做了 token/URL 脱敏。
- WebMount 内容可以转入聊天和 Deep Read fallback。

不做或仍 unsupported：

- 不启用任意 JS eval。
- 不做真实 OAuth、signed fetch、站点专用 adapter、profile synthesis。
- 不处理真实账号/cookie/生产数据。
- DOM snapshot 目前是安全候选元素近似，不是原生截图/视觉读取终局。

刚修复过的测试：

- `ChatViewModelSelectedFileContextTests.testWebMountOpenApprovalRequestAndTimelineRedactsInput`

### 5. 执行与高级能力

关键文件：

- `iosApp/iosApp/SubAgentsView.swift`
- `iosApp/iosApp/SubAgentRoleView.swift`
- `iosApp/iosApp/SubAgentRunner.swift`
- `iosApp/iosApp/CouncilView.swift`
- `iosApp/iosApp/CouncilSettingsView.swift`
- `iosApp/iosApp/CouncilChatRuntimeView.swift`
- `iosApp/iosApp/CouncilRunner.swift`
- `iosApp/iosApp/ExecutionSettingsView.swift`
- `iosApp/iosApp/RuntimeEnvironmentView.swift`
- `iosApp/iosApp/IOSTerminalRuntime.swift`
- `iosApp/iosApp/IOSLocalToolExecutor.swift`
- `iosApp/iosApp/IOSPermissionModels.swift`
- `iosApp/iosApp/PermissionsApprovalView.swift`
- `iosApp/iosApp/ChatViewModel.swift`
- `iosApp/iosAppTests/IOSSSHRuntimeTests.swift`
- `iosApp/iosAppTests/IOSPermissionStoreTests.swift`
- `iosApp/iosAppTests/IOSPermissionsStatusSnapshotTests.swift`
- `iosApp/iosAppTests/IOSCapabilityRegistryTests.swift`

当前能力：

- 有 iOS-local advanced task records/store。
- SubAgent/Council/Remote Command 都能写入任务状态。
- SubAgent 有角色、任务 payload、工具 scope summary、结果回填。
- 模型议会有 seats/budget/process/conclusion/fallback。
- Remote SSH 有 profile validation、known host trust、mock backend、job status/log/cancel/timeout/dangerous command rejection。
- Tool approvals 记录会脱敏，permission snapshot 包含高级执行能力。
- 高风险远程/文件/网页/记忆动作默认需要前台审批或显式权限。

刚修复过的测试：

- `IOSTerminalSSHRuntimeTests.testAdvancedTaskStorePersistsAndRedactsRemoteTaskState`

## 验证结果

当前 HEAD 验证过：

```bash
git diff --check
```

通过。

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet \
  -project iosApp/AmberAgent.xcodeproj \
  -scheme iosApp \
  -destination "generic/platform=iOS Simulator" \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO build
```

通过。

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew \
  :shared:compileKotlinIosSimulatorArm64 \
  :shared:linkDebugFrameworkIosSimulatorArm64 \
  --no-daemon
```

通过。注意这个命令可能需要访问 `~/.gradle`，沙箱内可能被 lock 文件权限挡住；必要时用已有批准的 Gradle 权限重跑。

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet \
  -project iosApp/AmberAgent.xcodeproj \
  -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17" \
  -derivedDataPath /tmp/amberagent-test-full-final \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO test
```

通过。这个命令跑了全量 `iosAppTests`。

## 已知非阻塞 warning

当前构建仍有一些 warning，但不阻塞 build/test：

- `MiniAppRunnerView.swift`、`ChatViewModel.swift`、`BoardView.swift` 中若干 `try? IOSWorkspaceStore.shared.saveArtifact(...)` 结果未使用。
- `WebMountView.swift`、`McpServersView.swift` 中 Toggle binding 的 non-Sendable closure 警告。
- `IOSSyncBackup.swift` 中 `UIDevice.current/systemName/model` 从 nonisolated context 访问的 Swift 6 main actor warning。
- `AmberNative.xcframework/ios-arm64-sim/libamber_ffi.a` 若干对象文件 built for newer iOS-simulator version 26.5 than linked 26.0 的 linker warning。
- `xcodebuild` generic simulator destination 如果不指定 `ARCHS=arm64 ONLY_ACTIVE_ARCH=NO`，可能选到 x86_64，导致 Shared.framework / AmberNative arm64 sim slice 链接失败。

建议下一轮先单开 warning cleanup，而不是把它混入功能继续扩张。

## 接手建议

下一个 AI 的优先级应该是：

1. 不要继续大规模补功能；先做最终验收。
2. 在 iPhone 17 simulator 上手工 smoke 核心路径。
3. 修掉影响用户体验或全量测试的真实问题。
4. 清理 warning。
5. 准备 push/PR 或最终发布前 checklist。

建议手工 smoke 路径：

- 首次启动/无 API Key：是否诚实提示，不写入假消息。
- Session 首页：深度阅读、小应用、WebMount、模型议会等高级入口是否还在，是否无工程化状态后缀。
- 设置首页：无伪设置项；高级功能不在实验区。
- 聊天：普通消息、搜索工具、记忆写入审批、文件上下文、图片生成工具结果、WebMount 工具审批。
- 深度阅读：手动文本、搜索结果、会话内容、文件、WebMount 来源；历史、失败重试、结果保存。
- 记忆：搜索、过滤、详情、编辑/删除、召回解释。
- 搜索：provider 选择、search_web、scrape_web、安全拒绝 localhost/private host。
- 图片生成：缺 key 状态、mock/history、图片卡片保存/分享。
- Mini App：列表、运行器、权限、storage/search/fetch/AI generate/host actions、错误状态。
- Workspace：文件导入、预览、reparse、remove、Artifact 保存/读取/删除。
- WebMount：站点 enable、open、extract、get、back/forward、clear session、内容 handoff。
- SubAgent：角色、任务、结果回填。
- 模型议会：席位、运行、结论展示。
- Remote SSH：profile validation、mock job、取消/超时、危险命令拒绝。
- 权限审批：文件、记忆、网页、远程执行是否都走正确审批/拒绝。

## 给另一个 AI 的接手 Prompt

下面这段可以直接复制给另一个 AI：

```text
你接手的是 AmberAgent iOS Android parity closure 项目。仓库在 /Users/arquiel/Downloads/AI/amberagent-ios，当前分支 codex/ios-port-wip，当前 HEAD 应为 68192b345 Fix iOS parity closure test regressions。先读取 docs/ios-port/IOS_PARITY_HANDOFF_2026-06-19.md、docs/ios-port/IOS_ANDROID_PARITY_ROADMAP_2026-06-19.md、docs/ios-release-readiness-plan.md，以及 .workflow/amberagent-ios-*/ 下的 plan/results/final-report；然后运行 git status --short --branch 和 git log --oneline --decorate -12 确认起点。不要假设聊天记录仍可用，以本 handoff 和当前代码为准。

产品决策：iOS 只保留一个 Amber Assistant，不做多 Assistant 系统；深度阅读、Mini App、WebMount、SubAgent、模型议会是正式高级功能，不放实验区，不做总开关隐藏；记忆、搜索、图片生成、Mini App 是正式能力；收藏暂不做；统计已合并到 User 页面；系统访问/自动化/分享/外部入口只在 iOS 开放能力范围内适配，不伪实现 Android-only 能力；设置页和功能页不能有伪设置项或工程化说明项。

当前进度：大规模 parity closure 已完成并提交，覆盖深度阅读、记忆、搜索、图片生成、Mini App、Workspace/File/Artifacts、WebMount、SubAgent/模型议会/远程执行/任务状态/工具审批。最新一轮验证中 git diff --check、iOS build、KMP shared iOS framework 编译/链接、全量 iosAppTests 均通过。之前的失败测试已由 68192b345 修复：DeepRead 多行清洗、AdvancedTaskStore Bearer 脱敏、WebMount JSON URL 可读性。

你的任务不是继续无边界加功能，而是做最终验收、模拟器手工 smoke、warning cleanup 和发布/PR 前收口。先运行：
git diff --check
env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO build
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon
env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17" -derivedDataPath /tmp/amberagent-test-full-final ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO test

如果 Gradle 被 ~/.gradle lock 权限挡住，按环境规则请求提升权限后重跑。若 generic simulator destination 选到 x86_64，使用 iPhone 17 或 ARCHS=arm64 ONLY_ACTIVE_ARCH=NO。不要把环境问题误判为代码失败。

然后做手工 smoke：Session 首页、设置首页、聊天、深度阅读、记忆、搜索、图片生成、Mini App、Workspace/File/Artifacts、WebMount、SubAgent、模型议会、Remote SSH、工具审批。重点检查是否还有伪设置项、实验性归类、静态说明冒充功能、缺失入口、状态不可恢复、隐私/权限边界过宽、token/cookie/URL 未脱敏。

约束：保护当前工作区，不回滚未理解文件；不修改 Android 业务逻辑、Gradle/Xcode project 生成物、证书、发布配置、google-services.json 或私有配置；不触碰真实账号、真实 API Key、付费服务和生产数据；不新增多 Assistant 系统；不大改视觉设计规范；不要继续扩大功能范围，除非验收发现真实 P0/P1 问题。

允许修改：iOS Swift 文件、iosAppTests、与收口直接相关的 docs/.workflow，且每次修改要有对应验证。优先修 warning 和真实回归：unused try? artifact saves、Toggle non-Sendable closure、IOSSyncBackup UIDevice main actor warning、native sim version warning 如可安全处理。

完成条件：工作区干净；git diff --check、iOS build、shared iOS framework compile/link、全量 iosAppTests 通过；手工 smoke 记录主要路径结果；剩余问题按 P0/P1/P2 分类，并给出下一步最小 work packet。暂停条件：需要真实 API Key、付费服务、真实账号/cookie/SSH、Apple entitlement、App Store 签名、生产数据、破坏性删除、产品隐私策略决定，或同一外部环境阻塞连续 3 次。
```

## 建议下一条 /goal

如果要继续用 Codex goal mode，可以直接用这一条：

```text
/goal 对 AmberAgent iOS Android parity closure 做最终验收与发布前收口，不再扩大功能范围。先读取 docs/ios-port/IOS_PARITY_HANDOFF_2026-06-19.md、docs/ios-port/IOS_ANDROID_PARITY_ROADMAP_2026-06-19.md、docs/ios-release-readiness-plan.md 和 .workflow/amberagent-ios-*/ 记录，确认当前 HEAD 与工作区状态；然后运行 git diff --check、iOS build、KMP shared iOS framework 编译/链接、全量 iosAppTests，并在 iPhone 17 simulator 上手工 smoke 深度阅读、记忆、搜索、图片生成、Mini App、Workspace/File/Artifacts、WebMount、SubAgent、模型议会、Remote SSH、工具审批和设置/Session 首页。
验证：git status --short --branch；git log --oneline --decorate -12；git diff --check；env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO build；JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon；env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17" -derivedDataPath /tmp/amberagent-test-full-final ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO test；记录手工 smoke 结果。
约束：不扩大功能范围，不做多 Assistant，不把正式高级功能放实验区，不触碰真实账号/API Key/付费服务/生产数据/证书/发布配置，不修改 Android 业务逻辑，不大改视觉设计规范，不伪实现 iOS 不开放能力。
边界：允许修改 iOS Swift、iosAppTests、docs/.workflow 中与验收发现问题直接相关的文件；禁止修改 Gradle/Xcode project 生成物、google-services.json、证书、私有配置和无关模块。
迭代策略：先验证，再 smoke，再只修 P0/P1；每个修复都跑最小相关测试，最后跑全量 iosAppTests；同一错误连续失败 2 次后换证据来源，例如 xcresult、日志、静态检查或缩小测试。
完成条件：构建和测试通过，主要用户路径 smoke 有记录，P0/P1 已修复或明确阻塞，剩余 warning/风险按 P2 列表交接，工作区干净。
暂停条件：需要真实 API Key、付费模型/搜索/图片服务、真实账号/cookie/SSH、Apple entitlement、App Store 签名、生产数据、破坏性删除、产品隐私策略决定，或同一外部环境阻塞连续出现 3 次。
```

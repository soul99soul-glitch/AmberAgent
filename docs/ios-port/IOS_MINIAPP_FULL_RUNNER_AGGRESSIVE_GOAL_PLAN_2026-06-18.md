# iOS MiniApp Full Runner Aggressive Goal Plan 2026-06-18

本文档用于 AmberAgent iOS port 的第一个大工程：MiniApp Full Runner 激进推进。

目标不是再做几个保守小切片，而是在一个 `/goal` 内尽可能把 iOS MiniApp 从当前只读 appId Runner MVP 推到接近可用：本地 repository、列表管理、版本、grant store、Runner、bridge、聊天生成入口、测试和状态文案一起推进。只有外部账号、系统权限、发布配置、Xcode 工程结构或连续环境阻塞才暂停。

## 推荐执行版（中文，可直接复制）

```text
/goal 激进推进 AmberAgent iOS MiniApp Full Runner：在一个连续目标内尽可能完成 iOS MiniApp 的本地可用闭环，而不是只做保守薄片。基于当前 MiniAppReadOnlyCatalog、miniAppRunner(appId:)、MiniAppHtmlValidator、MiniAppRunnerWebView 和 MiniAppBridge MVP，继续实现 Swift 原生本地 MiniApp repository、Documents 持久化、list/get/saveRevision/rename/pin/delete/markRun/version history/grant/sharedData/audit metadata；让 MiniAppListView 完全从 repository 展示和管理真实小应用；让 MiniAppRunnerView 通过 appId 读取持久化记录、校验和运行 HTML、保存新版本、显示版本和 grant 状态；尽量补齐非系统权限型 bridge 能力，包括 app.info、toast/theme、storage/sharedStore、clipboard.copy、host.updateBoardSummary、eventBus、本地 search/ai/fetch 的受限实现或诚实错误；并尝试接入聊天生成 MiniApp 的最小链路，让显式 MiniApp 请求能生成、解析、保存并在聊天或列表中打开。除非遇到暂停条件，不要在完成单个小切片后结束。
验证：开始先运行 git status --short --branch、git log --oneline --decorate -12，并读取 MiniAppListView.swift、MiniAppRunnerView.swift、MiniAppBridge.swift、MiniAppRunnerWebView.swift、MiniAppHtmlValidator.swift、MiniAppHtmlValidatorTests.swift、ChatViewModel.swift 以及 Android 侧 MiniAppRepository、MiniAppModels、MiniAppOutputParser、MiniAppPromptTransformer、MiniAppBridge 作为对照；实现后新增或更新 Swift XCTest 覆盖 repository 持久化、非法 HTML 拒写、version history、rename/pin/delete/markRun、grant/sharedData/audit metadata、bridge dispatch 权限检查、Runner appId 读取；尽可能运行 xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build 和相关 iosAppTests；涉及 KMP 或 Android 对照测试时尽可能运行最小 Gradle 测试。若缺 iOS simulator runtime、Java、Xcode 组件或 test target 配置，记录精确错误和可复现命令，并继续用 swiftc -parse、纯 Swift store 测试或静态检查推进可验证部分。
约束：遵守 AGENTS.md；先读实际代码再改；保护用户已有改动和未跟踪的 iosApp/iosApp/CouncilChatRuntimeView.swift；不触碰密钥、账号、云服务、发布配置、证书或生产数据；不伪造能力状态；bridge 的网络、AI、搜索、剪贴板、host 写回能力必须受 grant 和设置约束，没有凭证或权限时返回诚实错误；不得为了完整度绕过 MiniAppHtmlValidator 或放宽沙箱；不得修改无关 Android 业务逻辑。
边界：允许修改 iosApp/iosApp 内 MiniApp、ChatViewModel、message rendering、router、settings/status 文案和相关 tests；允许新增 Swift 小模块如 IOSMiniAppStore、IOSMiniAppModels、IOSMiniAppOutputParser、IOSMiniAppBridgeRuntime；必要时允许最小修改 shared/src/commonMain/kotlin/shared 的 iOS bridge helper 或 ai-core tool/model export；允许小幅更新 docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md 和本 plan。禁止修改发布脚本、证书、google-services.json、Xcode project 生成物、远端 Sync provider、Board collector、WebMount 主工程和无关重构。
迭代策略：用激进单目标方式推进，不把工作拆成多个后续 goal。先快速审计并列出可并行泳道，然后按依赖顺序连续实现：repository 和模型、list 管理 UI、runner/version/markRun、grant/sharedData/audit metadata、bridge 非系统权限能力、聊天生成最小链路、测试和文案。每完成一个泳道就跑最小验证；如果某泳道连续 2 次被同类问题卡住，降级为诚实错误或最小可用实现并继续下一个泳道，不要整体停止；只有暂停条件出现才停。优先完整纵向可用，其次 Android parity，最后视觉细节。
完成条件：MiniAppListView 不再依赖静态 catalog 作为主要数据源；MiniApp 记录能持久化到 Documents 并重启恢复；Runner 能通过 appId 读取真实记录、校验 HTML、运行 WKWebView、记录 runCount、保存新版本并显示 version/grant 状态；rename、pin、delete、version restore、grant allow/deny、sharedData、audit metadata 至少有 store API 和测试，关键管理动作有 UI 入口；bridge 至少完成 app.info、log/echo、toast/theme、storage/sharedStore、clipboard.copy、host.updateBoardSummary、eventBus，并对 search/ai/fetch 给出受 grant 控制的可用实现或诚实错误；显式 MiniApp 聊天请求有最小生成或解析保存链路，若模型输出解析受限则至少能从符合格式的 assistant 输出保存为 MiniApp；测试或构建通过，或环境阻塞被精确记录且所有可静态验证部分已完成。
暂停条件：需要安装系统组件且当前环境无法继续、需要 Apple 账号登录、需要真实云账号或付费服务、需要真实 API Key 才能判断正确性、需要修改 Xcode project 结构、需要删除用户未跟踪文件、需要产品决定是否允许 location/sensor/clipboard.read 等敏感权限、需要 App Store 权限文案或隐私合规判断、或同一外部环境阻塞连续出现 3 次时暂停。
```

## Goal Draft English Compatible

```text
/goal Aggressively advance AmberAgent iOS MiniApp Full Runner in one continuous goal instead of stopping after conservative thin slices. Starting from the current MiniAppReadOnlyCatalog, miniAppRunner(appId:), MiniAppHtmlValidator, MiniAppRunnerWebView, and MiniAppBridge MVP, implement a Swift-native local MiniApp repository with Documents persistence, list/get/saveRevision/rename/pin/delete/markRun/version history/grant/sharedData/audit metadata; make MiniAppListView fully display and manage real repository apps; make MiniAppRunnerView load persisted records by appId, validate and run HTML, save new versions, and show version and grant state; complete as many non-system-permission bridge capabilities as possible, including app.info, toast/theme, storage/sharedStore, clipboard.copy, host.updateBoardSummary, eventBus, and guarded local search/ai/fetch implementations or honest errors; and attempt the minimum chat-generated MiniApp path so explicit MiniApp requests can generate, parse, save, and open from chat or list. Do not stop after a single small slice unless a pause condition is reached.
Verification: first run git status --short --branch and git log --oneline --decorate -12, then inspect MiniAppListView.swift, MiniAppRunnerView.swift, MiniAppBridge.swift, MiniAppRunnerWebView.swift, MiniAppHtmlValidator.swift, MiniAppHtmlValidatorTests.swift, ChatViewModel.swift, plus Android MiniAppRepository, MiniAppModels, MiniAppOutputParser, MiniAppPromptTransformer, and MiniAppBridge for parity guidance; add or update Swift XCTest coverage for repository persistence, invalid HTML rejection, version history, rename/pin/delete/markRun, grant/sharedData/audit metadata, bridge permission checks, and Runner appId loading; when possible run xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build and related iosAppTests; if KMP or Android comparison changes are made, run the smallest relevant Gradle tests. If simulator runtimes, Java, Xcode components, or test target setup block verification, record exact errors and reproducible commands, and continue with swiftc -parse, pure Swift store tests, or static checks for verifiable parts.
Constraints: follow AGENTS.md; inspect real code before editing; protect existing user changes and the untracked iosApp/iosApp/CouncilChatRuntimeView.swift; do not touch secrets, accounts, cloud services, release config, certificates, or production data; do not fake capability status; bridge network, AI, search, clipboard, and host-write capabilities must obey grants and settings and return honest errors when credentials or permissions are missing; never bypass MiniAppHtmlValidator or loosen the sandbox for completeness; do not modify unrelated Android business logic.
Boundaries: edits are allowed in iosApp/iosApp MiniApp files, ChatViewModel, message rendering, router, settings/status copy, and related tests; adding small Swift modules such as IOSMiniAppStore, IOSMiniAppModels, IOSMiniAppOutputParser, or IOSMiniAppBridgeRuntime is allowed; minimal shared/src/commonMain/kotlin/shared iOS bridge helper or ai-core export edits are allowed only when required; small updates to docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md and this plan are allowed. Do not modify release scripts, certificates, google-services.json, generated Xcode project files, remote Sync provider work, Board collector work, WebMount main work, or unrelated refactors.
Iteration policy: work as one aggressive goal, not a queue of future goals. Quickly audit and list parallel lanes, then implement continuously in dependency order: repository and models, list management UI, runner/version/markRun, grant/sharedData/audit metadata, non-system bridge capabilities, minimum chat generation path, tests and status copy. Run the smallest relevant verification after each lane. If one lane repeats the same failure twice, downgrade that lane to an honest error or minimum viable implementation and continue to the next lane instead of stopping everything. Stop only for pause conditions. Prioritize full vertical usability first, Android parity second, visual polish last.
Stop when: MiniAppListView no longer uses the static catalog as the main data source; MiniApp records persist under Documents and restore after restart; Runner loads real records by appId, validates HTML, runs WKWebView, records runCount, saves new versions, and displays version/grant state; rename, pin, delete, version restore, grant allow/deny, sharedData, and audit metadata have store APIs and tests, with UI for key management actions; bridge completes app.info, log/echo, toast/theme, storage/sharedStore, clipboard.copy, host.updateBoardSummary, and eventBus, and search/ai/fetch are either grant-controlled usable implementations or honest errors; explicit MiniApp chat requests have a minimum generate or parse-and-save path, or at least conforming assistant output can be saved as a MiniApp; tests or builds pass, or environment blockers are recorded exactly and all statically verifiable parts are complete.
Pause if: system components must be installed and the environment cannot continue, Apple login is required, real cloud accounts or paid services are required, a real API key is required to determine correctness, Xcode project restructuring is required, user-untracked files must be deleted, product decisions are required for sensitive permissions such as location, sensor, or clipboard.read, App Store privacy wording or compliance judgment is required, or the same external blocker repeats three times.
```

## 激进范围说明

这版 goal 默认使用 GPT-5.5 xhigh 更合适。它不是“MiniApp phase 1”，而是一次大推进，允许跨 MiniApp store、SwiftUI UI、Runner、Bridge、ChatViewModel 和测试移动。

激进不等于乱来。它的核心策略是：

- 能本地完成的尽量一口气完成。
- 能用现有 iOS executor 完成的 bridge 能力尽量接上。
- 需要真实权限、账号、密钥或合规判断的能力给诚实错误和 grant 框架，不假装完成。
- 一个泳道卡住后降级继续，不让单点阻塞吞掉整轮。

## 当前代码起点

当前 HEAD：

```text
81c7cf3ca Board 今日看板内容 persistence (narrowed scope: content only, no task-flow)
```

当前工作区已有 MiniApp 相关改动，应基于它继续，不能回滚：

```text
iosApp/iosApp/AppShell.swift
iosApp/iosApp/MiniAppListView.swift
iosApp/iosApp/MiniAppRunnerView.swift
```

当前已具备：

- `Route.miniAppRunner(appId:)`
- `MiniAppReadOnlyCatalog`
- appId 到 Runner 的只读样例链路
- `MiniAppHtmlValidator`
- `MiniAppRunnerWebView`
- `MiniAppBridge` 的 log、echo、app.info MVP
- `MiniAppHtmlValidatorTests`

当前主要缺口：

- 无 iOS 本地 MiniApp repository。
- 无持久化 metadata、HTML、version、runCount、pinned、permissions、grant、sharedData、audit。
- 无完整 list 管理动作。
- Runner 未基于持久化 repository。
- Bridge 多数能力未接。
- Chat 生成 MiniApp 链路未接。

## 一口气完成清单

### Repository 和模型

新增 Swift Codable 模型，尽量对齐 Android：

- MiniApp record：id、title、description、htmlContent、sourceConversationId、sourceMessageId、iconEmoji、category、permissions、pinned、runCount、boardSummary、version、htmlHash、createdAt、updatedAt。
- Version record：appId、versionNumber、htmlContent、htmlHash、changeNote、createdAt。
- Grant record：appId、permission、decision、updatedAt。
- Audit record：id、appId、method、permission、summary、payloadHash、createdAt。
- Shared data record：namespace、key、value、lastWriterId、updatedAt。

推荐存储位置：

```text
Documents/miniapps/miniapps.json
```

要求：

- 原子写入。
- 解码失败不清空用户数据。
- 保存 HTML 前必须过 `MiniAppHtmlValidator.validate`。
- 记录 SHA-256 hash。
- 首次启动自动 seed 当前 MVP sample，除非已有数据。

### List 管理

MiniAppListView 要从 repository 读取，而不是从只读 catalog 读取。

尽量完成：

- 列表展示真实 records。
- pin/unpin。
- rename。
- delete。
- version 计数或当前 version。
- runCount。
- permission/grant summary。
- 空状态和 seed 状态诚实显示。

### Runner

MiniAppRunnerView 要从 repository 按 appId 读取。

尽量完成：

- appId missing state。
- 加载持久化 HTML。
- 运行前校验 HTML。
- 成功运行后 markRun。
- 显示 metadata、version、runCount、permissions、grant。
- 保存当前编辑 HTML 为新版本。
- version history 和 restore，若 UI 时间不够，至少完成 store API 和测试。

### Bridge

优先完成非系统权限型能力：

- app.info
- log
- echo
- toast
- theme
- storage
- sharedStore
- clipboard.copy
- host.updateBoardSummary
- eventBus

尽量完成受限能力：

- search：可复用 `IOSSearchExecutor`，必须检查 grant 和设置。
- ai.generate：可复用现有 provider path，缺 API key 返回诚实错误。
- fetch：只允许 HTTPS，必须检查 grant，限制方法和响应大小。

继续诚实拒绝或延后：

- clipboard.read
- location
- sensor
- launch 外部 app

这些涉及敏感权限或系统能力，不应在没有产品决策和权限文案时强接。

### Chat 生成链路

激进尝试接入最小链路：

- 读取 Android `MiniAppPromptTransformer` 和 `MiniAppOutputParser`。
- 在 iOS 侧识别显式 MiniApp 请求。
- 给模型追加 MiniApp 生成格式约束。
- 解析符合格式的 assistant 输出。
- 保存为 MiniApp record。
- 在聊天或列表中提供打开入口。

如果完整 streaming transformer 代价过大，允许降级为：

- generation finish 后解析完整 assistant text。
- 符合 MiniApp JSON 或 HTML 包装格式时保存。
- 失败时保留原 assistant text，并提示解析失败。

### 测试

优先新增纯 Swift tests：

- store seed/list/get。
- save invalid HTML rejected。
- save valid generated app。
- save revision increments version。
- rename/pin/delete。
- markRun increments runCount。
- grant allow/deny persists。
- sharedData set/get。
- audit append/list。
- bridge permission denied。
- app.info includes appId and version。
- parser accepts a conforming generated MiniApp output.

## 验证命令

起点：

```bash
git status --short --branch
git log --oneline --decorate -12
```

定位：

```bash
rg -n "MiniAppReadOnlyCatalog|miniAppRunner|MiniAppRunnerView|MiniAppBridge|MiniAppHtmlValidator|grant|runCount|pinned|MiniAppPromptTransformer|MiniAppOutputParser" iosApp app shared
```

优先构建：

```bash
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build
```

优先测试：

```bash
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" test
```

回退检查：

```bash
xcrun swiftc -parse iosApp/iosApp/IOSMiniAppStore.swift
xcrun swiftc -parse iosApp/iosApp/IOSMiniAppModels.swift
xcrun swiftc -parse iosApp/iosApp/IOSMiniAppOutputParser.swift
```

如涉及 KMP 或 Android 对照：

```bash
./gradlew test --tests "*MiniApp*"
```

环境缺失时，不要停止所有可推进工作。记录错误后继续做纯 Swift 可验证部分。

## 完成报告要求

最终报告必须列出：

- 完成了哪些 MiniApp 能力。
- 哪些 bridge method 可用，哪些返回诚实错误。
- repository 文件位置和数据结构。
- 哪些管理动作有 UI。
- 聊天生成链路做到哪一步。
- 跑过哪些测试或构建。
- 哪些验证因环境阻塞无法运行。
- 剩余缺口是否属于系统权限、真实账号、产品决策或后续 polish。

## 2026-06-19 执行结果摘要

已完成第一轮 iOS MiniApp Full Runner 本地闭环推进：

- 新增 `IOSMiniAppModels` / `IOSMiniAppRepository` / `IOSMiniAppBridgeRuntime`，以 `Documents/miniapps/miniapps.json` 持久化 app、version、grant、sharedData、audit metadata。
- `MiniAppListView` 已从本地 repository 展示真实记录，并提供打开、置顶、重命名、删除入口。
- `MiniAppRunnerView` 已通过 appId 读取持久化记录、校验并运行 HTML、markRun、保存新版本、恢复版本、展示/修改 grant、展示 audit 和 bridge log。
- `MiniAppBridge` / `MiniAppRunnerWebView` 已接 Promise 风格 `window.Amber` API：`app.info`、`log`、`echo`、`toast`、`host.getTheme`、`storage.*`、`sharedStore.*`、`clipboard.copy`、`host.updateBoardSummary`、`eventBus.*`、`search`、`fetch`、`ai.generate`。其中 network/search/ai/clipboard/host 写回均受 grant 和设置约束；AI 无 API key 或 iOS MiniApp AI bridge 未实现时返回诚实错误。
- `ChatViewModel` 已接最小显式 MiniApp 请求链路：上传给模型的消息追加 MiniApp JSON 生成规范；完成后解析符合格式的 assistant 输出，保存为新 MiniApp 或目标 app revision，并追加保存确认消息。
- 新增 XCTest：`IOSMiniAppRepositoryTests`、`IOSMiniAppOutputParserTests`、`IOSMiniAppBridgeRuntimeTests`。

验证结果：

- `git diff --check` 通过。
- `swiftc -parse` 覆盖 MiniApp 相关 Swift 源和新增 tests，通过。
- `swiftc -swift-version 6 -typecheck` 覆盖 `IOSSearchExecutor`、`MiniAppHtmlValidator`、`IOSMiniAppModels`、`IOSMiniAppRepository`、`IOSMiniAppBridgeRuntime`、`MiniAppBridge`，通过。
- `xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build` 与 `test` 均被环境阻塞：当前 Xcode 未安装 iOS 26.5 platform / Simulator destination，错误为 `iOS 26.5 is not installed. Please download and install the platform from Xcode > Settings > Components.`

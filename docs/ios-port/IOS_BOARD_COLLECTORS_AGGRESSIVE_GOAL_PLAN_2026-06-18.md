# iOS Board Collectors Aggressive Goal Plan 2026-06-18

本文档用于并行 Session B：AmberAgent iOS Board Collectors 大工程。

目标是在一个独立 session 内激进推进 iOS 今日看板的数据采集闭环：从当前“时间锚点采集 + 手动模型生成 + Markdown 内容持久化”，推进到本地 Board signal repository、多 collector、去重聚合、手动刷新、轻量后台调度、UI 状态和测试。它不做 MiniApp、Remote Sync、WebMount，也不恢复已明确不做的任务流、机会、派发。

## 推荐执行版（中文，可直接复制）

```text
/goal 激进推进 AmberAgent iOS Board Collectors 大工程：在独立 session 中尽可能完成 iOS 今日看板的数据采集闭环，而不是只做一个保守小切片。基于当前 BoardView、IOSBoardPersistence、IosBoardFactory 时间锚点采集、手动模型生成和 Documents/boards 内容持久化，继续实现 Swift 原生 Board signal repository、Documents 持久化、sourceRef 去重、contentHash 去重、processed 标记、聊天历史 collector、EventKit 日历和提醒事项 collector、轻量 hotlist collector、手动 runOnce 聚合、可选 BGTaskScheduler 或前台刷新框架、BoardView/BoardSettingsView 状态 UI 和测试；尽可能让生成今日看板时使用真实 collected signals，而不是只有时间锚点。通知、飞书、深度阅读等需要系统权限、外部账号或更大安全边界的能力，可以实现框架和诚实降级，但不要阻塞其他可本地完成的 collectors。
验证：开始先运行 git status --short --branch、git log --oneline --decorate -12，并读取 BoardView.swift、BoardSettingsView.swift、IOSBoardPersistence.swift、IOSBoardPersistenceTests.swift、IOSConversationStore.swift 以及 Android 侧 BoardSignalEntity、BoardSignalCollector、SignalAggregator、ChatHistorySignalCollector、CalendarSignalCollector、BoardRepository、BoardScheduler 作为对照；实现后新增或更新 Swift XCTest 覆盖 signal store 持久化、sourceRef 去重、contentHash 去重、processed 标记、聊天历史 collector 过滤、EventKit collector mock、hotlist collector mock、runOnce 聚合和 Board persistence 联动；尽可能运行 xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build 和相关 iosAppTests；涉及 KMP 对照或 Android 共享层改动时尽可能运行最小 Gradle 测试。若缺 iOS simulator runtime、Java、Xcode 组件或 test target 配置，记录精确错误和可复现命令，并继续用 swiftc -parse、纯 Swift store 测试或静态检查推进可验证部分。
约束：遵守 AGENTS.md；先读实际代码再改；保护当前工作区已有改动，尤其不要回滚 MiniApp、Search、Skill、plan 相关修改；不处理未跟踪的 iosApp/iosApp/CouncilChatRuntimeView.swift；不触碰密钥、账号、云服务、发布配置、证书或生产数据；不伪造日历、通知、飞书或热榜数据；不把任务流、机会、日报派发、BoardItemEntity 恢复为本轮目标；不修改 MiniApp、Remote Sync、WebMount 主线；没有权限或外部账号时必须返回诚实空状态或错误。
边界：允许修改 iosApp/iosApp 内 BoardView、BoardSettingsView、IOSBoardPersistence 及新增 Board store、collectors、aggregator、scheduler、EventKit adapter、hotlist adapter、小型 UI 组件和相关 tests；必要时允许只读使用 IOSConversationStore 并新增最小只读 helper，但不要破坏会话存储语义；允许小幅更新 docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md 和本 plan 的 Board 状态说明。禁止修改 MiniApp 文件、Sync 文件、WebMount 文件、Android 业务逻辑、Gradle 配置、Xcode project 生成物、发布脚本、证书、google-services.json 和无关重构。
迭代策略：用激进单目标方式推进，不在完成第一个 collector 后结束。先审计当前 iOS Board 和 Android 对照，再按依赖顺序连续实现：Board signal 模型和本地 store、dedup 和 processed、聊天历史 collector、EventKit 日历和提醒事项 collector、轻量 hotlist collector、runOnce 聚合与 BoardAgent 输入、BoardView 和 BoardSettings 状态更新、测试和文案。每完成一个泳道运行最小验证；如果某个泳道连续 2 次被同类问题卡住，降级为 mockable adapter、诚实错误或后续标记，并继续下一个泳道，不要整体停止。优先真实本地数据链路，其次 collector 数量，最后后台调度和视觉 polish。
完成条件：iOS 有本地 Board signal repository，能在 Documents 下持久化并重启恢复；sourceRef 和 contentHash 去重可验证；processed 标记和 signal pruning 有最小实现；聊天历史 collector 能从 IOSConversationStore 或其只读 helper 生成有意义信号；EventKit 日历和提醒事项 collector 有真实 adapter 或可 mock adapter，并在无权限时诚实空状态；hotlist 至少有轻量网络或 mockable provider 框架，不伪造新闻；runOnce 能聚合多来源 signals 并喂给现有 BoardAgent 手动生成；BoardView 展示各来源采集数量、最近信号和错误状态；BoardSettingsView 不再声称只有时间锚点；测试或构建通过，或环境阻塞被精确记录且所有可静态验证部分已完成。
暂停条件：需要安装系统组件且当前环境无法继续、需要 Apple 账号登录、需要真实日历或提醒事项权限才能判断正确性、需要真实飞书账号或外部 MCP、需要真实热榜 provider 凭证、需要修改 Xcode project 结构、需要删除用户未跟踪文件、需要产品决定是否读取通知或敏感个人数据、需要恢复任务流/机会/派发范围，或同一外部环境阻塞连续出现 3 次时暂停。
```

## Goal Draft English Compatible

```text
/goal Aggressively advance AmberAgent iOS Board Collectors in an independent session: complete as much of the iOS Today Board data collection loop as possible instead of stopping after one conservative slice. Starting from the current BoardView, IOSBoardPersistence, IosBoardFactory time-anchor collection, manual model generation, and Documents/boards content persistence, implement a Swift-native Board signal repository with Documents persistence, sourceRef dedup, contentHash dedup, processed markers, chat-history collector, EventKit calendar and reminder collectors, lightweight hotlist collector, manual runOnce aggregation, optional BGTaskScheduler or foreground refresh scaffolding, BoardView and BoardSettingsView status UI, and tests. Make generated Today Board content use real collected signals rather than only time anchors whenever possible. For notifications, Feishu, and DeepRead, implement framework and honest degradation if system permissions, external accounts, or larger security decisions are required, but do not let those block locally achievable collectors.
Verification: first run git status --short --branch and git log --oneline --decorate -12, then inspect BoardView.swift, BoardSettingsView.swift, IOSBoardPersistence.swift, IOSBoardPersistenceTests.swift, IOSConversationStore.swift, plus Android BoardSignalEntity, BoardSignalCollector, SignalAggregator, ChatHistorySignalCollector, CalendarSignalCollector, BoardRepository, and BoardScheduler for parity guidance; add or update Swift XCTest coverage for signal store persistence, sourceRef dedup, contentHash dedup, processed markers, chat-history collector filtering, EventKit collector mock, hotlist collector mock, runOnce aggregation, and Board persistence integration; when possible run xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build and related iosAppTests; if KMP comparison or shared changes are made, run the smallest relevant Gradle tests. If simulator runtimes, Java, Xcode components, or test target setup block verification, record exact errors and reproducible commands, and continue with swiftc -parse, pure Swift store tests, or static checks for verifiable parts.
Constraints: follow AGENTS.md; inspect real code before editing; protect existing worktree changes, especially MiniApp, Search, Skill, and plan edits; do not touch untracked iosApp/iosApp/CouncilChatRuntimeView.swift; do not touch secrets, accounts, cloud services, release config, certificates, or production data; do not fake calendar, notification, Feishu, or hotlist data; do not revive task-flow, opportunity, daily dispatch, or BoardItemEntity scope in this run; do not modify MiniApp, Remote Sync, or WebMount main lines; when permissions or external accounts are missing, return honest empty states or errors.
Boundaries: edits are allowed in iosApp/iosApp BoardView, BoardSettingsView, IOSBoardPersistence, new Board store, collectors, aggregator, scheduler, EventKit adapter, hotlist adapter, small UI components, and related tests; minimal read-only helpers around IOSConversationStore are allowed if they preserve conversation storage semantics; small updates to docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md and this Board plan are allowed. Do not modify MiniApp files, Sync files, WebMount files, Android business logic, Gradle config, generated Xcode project files, release scripts, certificates, google-services.json, or unrelated refactors.
Iteration policy: work as one aggressive goal and do not stop after the first collector. Audit current iOS Board and Android parity first, then implement continuously in dependency order: Board signal models and local store, dedup and processed markers, chat-history collector, EventKit calendar and reminders collector, lightweight hotlist collector, runOnce aggregation into BoardAgent input, BoardView and BoardSettings state updates, tests and status copy. Run the smallest relevant verification after each lane. If one lane repeats the same failure twice, downgrade that lane to a mockable adapter, honest error, or follow-up marker and continue to the next lane instead of stopping everything. Prioritize a real local data chain first, collector breadth second, background scheduling and visual polish last.
Stop when: iOS has a local Board signal repository that persists under Documents and restores after restart; sourceRef and contentHash dedup are verified; processed markers and minimal pruning exist; chat-history collector produces meaningful signals from IOSConversationStore or a read-only helper; EventKit calendar and reminders collectors have real or mockable adapters and honest empty states without permission; hotlist has at least a lightweight network or mockable provider framework without fake news; runOnce aggregates multi-source signals and feeds the existing BoardAgent manual generation path; BoardView shows source counts, recent signals, and error states; BoardSettingsView no longer claims only time anchors are available; tests or builds pass, or environment blockers are recorded exactly and all statically verifiable parts are complete.
Pause if: system components must be installed and the environment cannot continue, Apple login is required, real calendar or reminders permission is required to determine correctness, real Feishu account or external MCP is required, real hotlist provider credentials are required, Xcode project restructuring is required, user-untracked files must be deleted, product decisions are required for reading notifications or sensitive personal data, task-flow/opportunity/dispatch scope must be reopened, or the same external blocker repeats three times.
```

## 并行 Session 建议

推荐分支：

```text
codex/ios-board-collectors
```

推荐模型：

```text
GPT-5.5 xhigh
```

原因：Board B 会跨 SwiftUI、EventKit、KMP Board 类型、conversation store、collector heuristics、scheduler 和测试。它比 Search/Skill 清账更吃架构判断。

## 当前 iOS 起点

当前已具备：

- `BoardView` 能调用 `IosBoardFactory.createTimeCollectContext` 和 `createCollectors`。
- 当前 iOS 已有时间锚点信号。
- 当前 iOS 能手动调用 KMP `BoardAgent` 生成 Markdown。
- 当前 iOS 能通过 `IOSBoardPersistence` 把生成内容保存到 `Documents/boards/`。
- `IOSBoardPersistenceTests` 已覆盖 board content save/load/delete/recent。
- `IOSConversationStore` 已有真实 conversation persistence，是聊天历史 collector 的最佳起点。

当前缺口：

- 没有 iOS Board signal repository。
- 没有持久化 raw signals。
- 没有 sourceRef/contentHash 去重。
- 没有 processed marker 和 pruning。
- 没有 iOS chat-history collector。
- 没有 EventKit 日历或提醒事项 collector。
- 没有通知 collector。
- 没有 Feishu collector。
- 没有 hotlist collector。
- 没有 runOnce 聚合器。
- 没有 BGTaskScheduler 或前台 refresh 框架。
- UI 仍主要说“时间锚点已接，其他待接”。

## 一口气完成清单

### Board Signal Store

新增 Swift Codable 模型，尽量对齐 Android `BoardSignalEntity`：

- id
- sourceType
- sourceRef
- title
- content
- contentHash
- signalTime
- metadataJson
- processed
- processedAt
- createdAt

推荐存储位置：

```text
Documents/boards/signals.json
```

要求：

- 原子写入。
- 解码失败时不清空用户数据。
- `sourceType + sourceRef` 去重。
- `contentHash + sourceType` 近期去重。
- 支持 `getUnprocessed`、`getSince`、`markProcessed`、`pruneOlderThan`。

### Chat History Collector

目标：

- 基于 `IOSConversationStore` 或只读 helper 获取最近会话摘要和 tail messages。
- 复刻 Android `ChatHistoryBoardSignalHeuristics` 的关键词思路。
- 避免把纯测试、乱码、长文渲染测试等低价值对话写入 Board。
- metadata 至少记录 conversation id、node count、relevance。

### EventKit Collectors

目标：

- 日历 collector：读取未来 24 小时和过去 1 小时的事件。
- 提醒事项 collector：读取未完成、今日或逾期提醒。
- 权限缺失时返回诚实空状态和 UI 提示。
- 用 adapter protocol 包起来，便于 tests mock。

注意：

- 不要为了通过测试要求真实用户日历。
- 不要自动请求敏感权限，除非 UI 已明确触发。

### Hotlist Collector

目标：

- 先做轻量 provider 框架和 mockable provider。
- 可选接一个无需密钥的公开源，但必须标明来源和错误状态。
- 不伪造新闻，不硬编码假热榜。
- 不做 DeepRead 全链路，但可以保留 deepread-ready metadata。

### RunOnce Aggregator

目标：

- 统一调用 time、chat、calendar、reminder、hotlist collectors。
- 写入 signal store。
- 读取 unprocessed signals。
- 把 signals 转成现有 `BoardSignal` 或 BoardAgent 可消费的输入。
- 调用当前 BoardAgent 生成。
- 生成成功后 mark processed。
- 更新 `IOSBoardPersistence` 的 signalCount 和 source summary。

### UI 更新

BoardView：

- 展示各来源采集数量。
- 展示最近 signals。
- 展示 collector 错误和权限状态。
- 手动生成按钮改为 runOnce。
- 保留“不做任务流/机会/派发”的边界。

BoardSettingsView：

- 展示哪些 source 已接、哪些需要权限、哪些是外部账号阻塞。
- 如果实现 source toggles，必须持久化到 iOS 设置或本地 Board settings store。

## 明确不做

本 session 不做：

- MiniApp。
- Remote Sync。
- WebMount。
- Android 业务逻辑改动。
- 任务流、机会、日报派发。
- BoardItemEntity。
- 真实 Feishu 账号集成。
- 真实通知读取，除非 iOS 已有安全权限模型并且用户明确授权。
- DeepRead 完整 agent。

## 验证命令

起点：

```bash
git status --short --branch
git log --oneline --decorate -12
```

定位：

```bash
rg -n "BoardView|BoardSettingsView|IOSBoardPersistence|IOSConversationStore|BoardSignal|EventKit|Reminder|HotList|runOnce" iosApp app shared
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
xcrun swiftc -parse iosApp/iosApp/IOSBoardSignalStore.swift
xcrun swiftc -parse iosApp/iosApp/IOSBoardCollectors.swift
xcrun swiftc -parse iosApp/iosApp/IOSBoardRunOnce.swift
```

如涉及 shared 或 Android 对照：

```bash
./gradlew test --tests "*Board*"
```

## 完成报告要求

最终报告必须列出：

- 哪些 collector 已真实接入。
- 哪些 collector 是 mockable adapter 或诚实空状态。
- Board signal store 的文件位置和数据结构。
- 去重、processed、pruning 的验证证据。
- runOnce 生成链路是否使用多来源 signals。
- BoardView 和 BoardSettingsView 的状态变化。
- 跑过哪些测试或构建。
- 哪些验证因环境阻塞无法运行。
- 剩余缺口是否属于系统权限、外部账号、产品决策或后续 polish。

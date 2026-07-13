# AmberAgent iOS 流式性能/滚动 Handoff（C5/C6 后）

日期：2026-07-10
Repo：`/Users/arquiel/Downloads/AI/amberagent-ios`
Branch：`feat/ios-provider-parity-claude`

## 硬约束

- 全程中文。
- 不要 `git commit` / `push` / `stash` / `reset` / `checkout`。
- 工作区大量未提交改动都是有效工作，不要回滚计划之外的内容。
- 不要用 `scrollTo(y:)` / 直接写 `contentOffset` 这类几何补偿来掩盖滚动异常。
- 屏幕内动画只能升级不能降级；屏幕外不可见部分可以做性能降级。
- 修 bug / 做性能结构改动尽量先有测试或 canary，再改，再验证。
- 不要写事无巨细的执行手册消耗 token；抓关键约束和成功标准，执行者按实际代码自主判断。

## 背景判断

旧的 SwiftUI clean-list 链路视觉上已经基本可用，主要短板是：

1. 流式生成时内容向上生长仍不够像 ChatGPT iOS 那样连续、高帧率。
2. session 变长、尤其包含表格时，流式过程中滑历史仍会明显卡顿。
3. 流式过程中查看历史再回到底部，偶发“最新内容卡住、不实时更新”，需要通过滑远/点向下箭头才冒出一批内容。

原生滚动容器实验链路此前做过多轮，但视觉/跟随/表格都不稳定，不能作为当前主路径依据。现在的策略是：**保留旧链路的视觉成功经验，先把旧/默认路径性能和事件管线做干净，再决定是否继续推进原生容器。**

## 本轮刚完成的关键改动

### C5：流式 chunk 消费管线

改动文件：

- `iosApp/iosApp/ChatGenerationCoordinator.swift`
- `iosApp/iosApp/IOSAgentToolEngine.swift`
- `iosApp/iosAppTests/IOSParityRedLightTests.swift`
- `iosApp/iosAppTests/IOSAgentToolEngineTests.swift`

核心变化：

- 前台 provider 回调不再每个 chunk 创建 `Task { @MainActor }`。
- 新增 `ChatStreamEvent` / `ChatStreamEventSink`，把 chunk / complete / error 投递进一个 `AsyncStream<ChatStreamEvent>`。
- 由单一 MainActor FIFO consumer 串行 append accumulator、探测工具调用、登记 16ms snapshot provider。
- `accumulator.snapshot()` 只在 16ms flush、cancel、background handoff、complete/error 等必要点取。
- 后台/子代理 `IOSAgentToolEngine.streamStep` 去掉每 chunk `snapshot()+join`，改为 `StreamStepState.appendAssistantTextDelta` 增量维护累计 assistant 文本。

已加测试：

- `IOSParityRedLightTests/testForegroundStreamingChunksDoNotSnapshotBeforeThrottledFlush`
- `IOSAgentToolEngineTests/testStreamingAssistantTextDoesNotSnapshotOnEveryChunk`

### C6：默认 SwiftUI 路径行级 digest 门控

改动文件：

- `iosApp/iosApp/ChatCollectionMessageList.swift`
- `iosApp/iosAppTests/ChatRowContentHashCacheTests.swift`

核心变化：

- 默认 `ChatSwiftUIMessageList` 接入 `swiftUIRenderStateStore` + `swiftUIContentHashCache`。
- 消息行改走 `ChatSwiftUIMessageBubble(...).equatable()`。
- 用共享 `ChatRowDigests` 做行级门控，历史行不比较整条 `UIMessage`。
- 目标是尾部流式 delta 不再让整屏历史 Markdown/表格子树一起重建。
- 为避免视觉降级，默认路径对非尾部已流式 assistant 保持 `liveRenderingEnabled=true`，没有复用 native/collection 的 frozen 历史态。

已加测试：

- `ChatRowContentHashCacheTests/testSwiftUICleanListRowsUseDigestEquatableWrapper`

## 已验证

最近一次通过：

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/IOSParityRedLightTests/testForegroundStreamingChunksDoNotSnapshotBeforeThrottledFlush \
  -only-testing:iosAppTests/IOSParityRedLightTests/testBackgroundExpirationFailureMergesPartialAndFailureIntoSingleAssistantMessage \
  -only-testing:iosAppTests/IOSAgentToolEngineTests/testStreamingAssistantTextDoesNotSnapshotOnEveryChunk test
```

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/ChatRowContentHashCacheTests test
```

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/ChatStreamReplayTests test
```

```bash
git diff --check
```

说明：

- `ChatStreamReplayTests` 全类通过是滚动/底部跟随门禁。
- 编译输出里仍有很多既有 warning，不是本轮新增的重点。
- 尚未真机装机验证，因为用户当时上班无法装机。

## 当前工作区注意事项

- `IOS_FIX_PLAN_2026-07-08.md` 是未跟踪文件，但已作为当前批次计划文档使用。
- 工作区非常脏，包含多轮有效修改；不要用回滚类命令。
- `git status --short` 会看到大量 `M`/`MM`/`A`/`AM`/`??`，这是预期状态。

## 下一 session 推荐推进方式

先不要直接再开大分支。建议顺序：

1. 快速 review C5/C6 的调用链是否闭环：
   - complete/error 是否可能在 pending chunk 前被处理。
   - cancel / background handoff 是否会丢掉最新 pending snapshot。
   - `ChatSwiftUIMessageBubble ==` 是否遗漏会影响视觉的字段。
   - 默认路径 `swiftUIRenderState(for:)` 是否可能让历史已流式消息渲染器跳变。
2. 能装机时优先真机验证：
   - 长表格流式输出时，滑历史是否比之前少卡。
   - 流式过程中滑历史再回到底部，是否还会卡住不实时更新。
   - 表格尾行是否仍会越过输入框边界一瞬间。
   - 向上生长动画是否保持之前视觉稳定性。
3. 如果不能装机，继续做低风险、正统性能修复：
   - 表格解析/探测判据与真实渲染器统一，避免流式表格反复在“表格/普通文本/半截行”之间切换。
   - 给 `ChatStreamingMarkdownBlockCache` 加 hit/miss 计数或测试；如果全文 key 在 delta 下几乎必 miss，就改成块级/表格级 key 或去掉无效缓存。
   - 查 `ChatStableStreamingMarkdownController` renderable cache 是否还会在完成态/流式态之间错误切换，避免“先露出未渲染 markdown，再瞬间重组”。
4. C7 多工具批量执行是计划里的长期项，但它不是当前“流式性能/动效”的优先级；除非用户明确转向工具链，否则先别做。

## 成功标准

- 不降低屏幕内动画效果。
- 默认 SwiftUI clean-list 仍是可用主路径，视觉不倒退。
- 长表格流式输出时，滑动历史的掉帧应明显减少。
- 回到底部后流式内容应继续实时更新，不需要滑远/点击箭头才能冒出一批内容。
- 所有触碰滚动/布局/渲染的改动至少跑 `ChatStreamReplayTests`。

## 给新 session 的可复制 Prompt

见本文件末尾，或直接复制用户消息中的 prompt。

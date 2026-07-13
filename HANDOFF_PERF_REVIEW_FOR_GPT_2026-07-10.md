# 交接 Prompt：对 2026-07-10 流式性能批次做独立对抗性 review

你接手 AmberAgent iOS Chat 流式性能优化批次的**独立对抗性 review**。本轮任务只做审查和风险裁决，**不要修改任何代码**，等用户确认后再谈修复。

Repo：`/Users/arquiel/Downloads/AI/amberagent-ios`
Branch：`feat/ios-provider-parity-claude`（工作区大量未提交改动全部有效，不得回滚/覆盖/commit/push/stash/reset/checkout）

## 硬约束

- 全程中文。
- 结论必须区分：代码证据坐实 / 代码级风险（需运行时证据）/ 测试盲区 / 必须真机验证的假设。每条 finding 给文件与精确行号。
- 禁止提出 `scrollTo(y:)`、直接写 `contentOffset` 的几何补偿方案。
- 屏幕内动画只能升级不能降级；本批次多处修复声称"视觉零变化"，这些声称正是你要对抗性攻击的点。
- 默认路径是 `ChatSwiftUIMessageList`（SwiftUI clean-list）。
- 不要为了显得全面而制造问题；没有新 P0/P1 就明说。

## 背景

用户症状：生成内容一长（几 KB~几十 KB，尤其含表格）就大幅掉帧卡顿；流式期间滑动看历史也卡。本批次用运行时证据（sim 基准 + 缩放矩阵 + 静置/衰减/裸挂对照 + macOS `sample` 采样）定罪并修复了 8 个凶手。基准测试在 `iosApp/iosAppTests/ChatStreamingPerfBaselineTests.swift`（诊断型，打印 `[PERF]` 行），复现命令：

```bash
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -parallel-testing-enabled NO \
  -only-testing:iosAppTests/ChatStreamingPerfBaselineTests/testPerf_endToEnd_streamingBubbleDeltas test
```

实测成绩（主线程 CPU 口径，24KB 含表格夹具）：每 delta 317ms → 76ms（4.2×）；生成期常驻 240ms/s → 8ms/s（30×）；流式 UI 发布频率 60Hz → 20Hz（÷3，生产口径累计 ~12×）。

## 本批次改动清单（按文件）

### 1. `iosApp/iosApp/MessageBubbleView.swift`（改动最重，审查重点）

- **config 记忆化**（头号修复）：`ChatStreamingDetectionBox.markdownConfig(for:build:)` 按 (liveStreaming, fontScale, chatFont) 缓存 `MarkdownRenderConfig` 实例。根因：每次 body 求值 `UIColor(AmberTheme.x)` 生成新 dynamic-provider 实例 → `config ==` 恒假 → vendor `DocumentView` Equatable 门每 delta 击穿 → 全表格 33Hz 重建重测量。
- **widget 探测增量化**：body 不再每次求值调 `IOSGenerativeWidgetParser.mayContainWidgetPayload`（32KB 实测 46ms/次），改读 `mayContainWidgetPayload` @State latch，由 `IOSGenerativeWidgetPayloadDetector`（增量、latch、跨 chunk 重叠窗）在 onAppear/onChange 喂全量累计文本维护。
- **探测盒惰性化**：`ChatStreamingDetectionBox` 的 eager init 全量扫描只保留给非流式行；流式行空种子起步 + 增量补齐（采样坐实 eager init 占主线程 6.6%）。
- **heading 前缀分块**：`ChatStreamingMarkdownBlockParser.parseBlocks` 在列 0 ATX heading（`isATXHeadingLine`）处切分文本块（fence 外）。
- **流式默认块路径**：`shouldUseBlockStreamingRenderer` 改为 `liveStreaming || hasUsedBlockMarkdownRenderer || (hasEverStreamed && liveRenderingEnabled && detection.table.containsTable)`——流式从首帧一律块渲染，永不中途切换。
- **块列表结构隔离**：`ChatStreamingBlockMarkdownView` 拆出 `ChatStreamingMarkdownBlockListView`（只观察 controller），text 每 delta 变化不再重求值块子树。
- **块控制器**：发布节流（`publishInterval`）+ `publishPreservingSettledBlocks`（未变化块复用旧实例，全等不发布）。
- **renderable 探针重排**：`ChatStableStreamingMarkdownController.renderable(for:)` 改为 精确匹配(utf16 长度先行)→stale-prefix→静态缓存 的顺序。

### 2. `iosApp/iosApp/ChatCollectionMessageList.swift`

- **@State 装箱**：滚动几何、follow 任务、settle token 等 14 个纯回调字段移入 `ChatSwiftUIListScrollRuntime` 引用盒（滚动帧/任务建清不再失效整个列表 body）。类注释写明契约：body 不得读盒内字段。
- `updateStreamedMessageIDs` 的 delta 快路径（不再每 chunk O(消息数) KMP 桥接 + 无条件 @State 重写）。
- digest 设置签名反射上提到 body 级一次（`ChatRowSettingSignatures`）。

### 3. `iosApp/iosApp/ChatMessageProjection.swift`

- `compactRenderToken` 的 `text.count`（O(n) 字素遍历）→ `utf16.count`。

### 4. `iosApp/iosApp/ChatMiscViews.swift`

- **symbolEffect UIKit 隔离**：思考卡时钟的 `.symbolEffect(.variableColor.iterative.reversing)` 换成 `ChatUIKitVariableColorSymbol`（UIImageView + addSymbolEffect，CA 驱动同一动画）。根因：SwiftUI 永动 symbolEffect 把 ViewGraphDisplayLink 钉在 60fps，每帧工作 ∝ 整窗显示列表。

### 5. `iosApp/iosApp/IOSGenerativeWidgetParser.swift`

- needle 表提为共享静态 + 新增 `IOSGenerativeWidgetPayloadDetector`（ASCII 小写折叠、JSON 首非空白字符 `{` 门控、checkpoint 非追加改写重扫）。

### 6. `iosApp/iosApp/ChatGenerationCoordinator.swift`

- `streamSnapshotFlushDelayNanos` 16ms → 48ms（流式 UI 快照发布节流；cancel/complete/handoff 直接取 accumulator 快照不经过此延迟）。

### 7. vendor `iosApp/vendor/SwiftStreamingMarkdown/`

- `TextTransition/FadeInTextTransitionViewModifier.swift`：**fade 播完拆脚手架**——新增 `settled` @State，`config.totalDuration + 0.25s` 后卸下 transition 包装（ZStack/transaction 改写/TextRenderer），渲染裸 content。根因：常驻 transition 的 `content.transaction { animation = linear }` 让表格 cell 每事务重绘（采样 `TextRendererBox.draw` 坐实）。
- `UI/ParagraphView.swift`：三处等值比较加 `===` 指针先行短路（严格快路径，实测无效果但无害保留）。

### 8. 新增测试

- `iosApp/iosAppTests/ChatStreamingPerfBaselineTests.swift`：微基准 + 端到端缩放矩阵 + 静置衰减/裸挂对照 + 采样 hold（env 门控）。
- `iosApp/iosAppTests/IOSGenerativeWidgetPayloadDetectorTests.swift`：探测器等价性/边界切分/JSON 门控/重写重扫 + 热路径接线 canary。
- `iosApp/iosAppTests/ChatSwiftUIStreamReplayTests.swift`：默认路径执行层集成回放门禁（真实列表 + windowScene UIWindow + 谓词等待），5 用例：入场锚定、流式不丢底不回跳、terminal settle 承接晚到布局、短内容不假滚、切会话重锚。连跑 3 轮零 flaky。

## 已证伪的假设（不要重走）

- 块控制器发布节流与前缀实例冻结对 delta 成本无效（成本不在发布频率）。
- vendor ParagraphView 指针短路无实测效果。
- 块列表 ForEach churn 不是成本源。
- 探测器 eager init "微秒级"的评估错误（采样 6.6%）。

## 验证状态

- 最全 sim 门禁：**200 passed / 2 skipped（显式门控的 spike/采样测试）/ 0 failed**，含 `IOSParityRedLightTests`（C5 契约）、`ChatStreamReplayTests`、`ChatMessageWidthOverflowTests`、`ChatSwiftUIStreamReplayTests` 等。
- 真机（iPhone Air, iOS 27）：Debug 构建、签名、安装、启动成功；**体感验证清单尚未由用户执行**。

## 需要你重点对抗审查的问题

1. **config 记忆化的键完备性**：`MarkdownConfigKey = (liveStreaming, fontScale, chatFont)`。`streamingMarkdownConfig` 构建时读取的其他输入（AmberTheme 各色、displaySetting 相关排版参数？）是否存在"会变化但不在键里"的输入？深浅色依赖动态色绘制期解析的说法是否对 config 内所有颜色成立（有没有非动态的快照色）？缓存实例跨 delta 复用后，`textContextMenu`/`markdownController` 一类引用字段是否有会话生命周期问题？
2. **widget 探测器语义等价**：ASCII 折叠 vs `.caseInsensitive` 的偏差面；JSON brace 门控跨 chunk 的正确性；checkpoint 启发式（等长同尾改写漏检）；latch 永不回退（编辑掉 widget 内容后过检）——这些设计取舍是否有实际可触达的用户可见错误？
3. **@State 装箱契约**：`ChatSwiftUIListScrollRuntime` 的前提是"body 不读盒内字段"。逐一核对 body 调用链（含 `swiftUIRenderState`、`makeTimelinePlan`、transition/transaction 闭包）是否真的零读取；有没有"读了但恰好总有其他失效源掩护"的隐性依赖。
4. **`updateStreamedMessageIDs` delta 快路径**：`.assistantStreamDelta` 期间消息集合绝不增删的前提是否严格成立（工具流、后台补全落盘穿插、variant 切换竞态）？
5. **fade settled 拆除**：时长余量 0.25s 是否覆盖所有调度延迟；settled 翻转的一帧结构重建是否可能引起可见闪动/高度扰动；流式中 cell 内容原地更新时（表格行增长）settled 前后的行为差；iOS<18 分支。
6. **heading 分块的 CommonMark 安全性**：HTML block 内列 0 `#`、跨块 link reference definitions、setext、列表内缩进 `#`、`#` 后无空格（非 heading）的处理。块 id（顺序号）在追加流下的稳定性论证是否有反例（晚到的行改变**早前**行的块归属？）。
7. **流式默认块路径的覆盖面扩大**：非表格消息从单文档换到块 VStack 的视觉一致性（块间距同源 `config.blockSpacing` 的论证是否完备——heading 前后间距、首块顶部、文本选择/长按交互跨块）；完成后回收再入场的非表格行会回到单文档渲染（不一致但离屏发生）——是否存在可见场景？
8. **flush 16→48ms**：全仓搜索对 16ms 节奏的隐性依赖（录制回放 fixture 时序、`ChatStreamRecorder`、首 token 上屏延迟、工具卡出现时延、`IOSParityRedLightTests` 时序断言）。48ms 对"思考文本"（无 parse 节流的纯 Text）更新频率的体感影响评估是否成立。
9. **集成门禁的断言强度**：`ChatSwiftUIStreamReplayTests` 的阈值（回跳 <300pt、高度回缩容差 300pt）是否宽到失去拦截力；谓词等待超时 4s 在慢 CI 上的 flaky 风险。
10. **vendor 改动纪律**：两处 vendor 修改是否可能影响其他使用方语义（settled 拆除对非表格使用方——目前只有表格 cell 和 DEBUG 预览，是否属实）。

## 输出格式

1. Findings 按 P0/P1/P2 排序，每条含文件:精确行号、证据等级、失败场景。
2. 对上述 10 个问题逐一给出裁决（成立/不成立/需真机），查证不成立的也要明说理由。
3. 最多 3 个下一步工作包（如有），按收益与风险排序，写清成功标准与最小验证集合。
4. 没有新的 P0/P1 就明确说明，不要制造问题。
5. 不修改代码。

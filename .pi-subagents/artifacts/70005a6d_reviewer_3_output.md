## Review

**前置说明**:`plan.md` 与 `progress.md` 在仓库根目录不存在（ENOENT)。已改用 `docs/PROJECT_STATE.md`（其中确有 2026-07-23 P2-15 Reduce Motion 记录）和当前工作区代码取证。全程只读，未修改任何文件。

### 条目24:Chat 正文绕过 Dynamic Type — **确认成立**
- `iosApp/iosApp/ChatMessageListSupport.swift:89`(ChatUserBubble):`.font(.system(size: 17 * boundedScale, design: selectedFont.design))`，固定 point size,`boundedScale` 钳制在 0.88–1.25 且来自 App 自己的 `fontScale` 设置，与系统 Dynamic Type 无关。
- 助手正文同构：`ChatMessageListSupport.swift:134`(ChatAssistantText)`.font(.system(size: 17 * boundedScale…))`；流式 Markdown 路径 `MessageBubbleView.swift:685-688` `ChatStreamingMarkdownTypography.bodyFonts(chatFont:…, pointSize: 17 * scale)`，同样固定点值。
- 证据行号基本吻合（:76 是 `fontScale` AppStorage 声明，:676 落在排版注释区，实际 pointSize 在 :688)。
- 修复方向评价：合理。正文改走 Dynamic Type（如 `UIFontMetrics.scaledValue` 或语义样式 × `fontScale` 系数）即可；注意保留 App 内 fontScale 这一既有用户可调维度，两者应相乘而非互斥。

### 条目25:Reduce Motion 覆盖不完整 — **确认成立**
四处均仍缺 `accessibilityReduceMotion`，与 PROJECT_STATE 2026-07-23 P2-15 记录（只接了回底动画 + part 插入动画）一致：
- `ChatMiscViews.swift:550-575`(VisionRecognitionIndicator OCR 呼吸）:`withAnimation(.easeInOut(duration:0.7).repeatForever)` 无任何 reduceMotion 门控（该文件 :76/:377 的 reduceMotion 属于其他视图）。
- `ChatComposerViews.swift:365`(ContextRingButton):`.animation(.linear(duration:1.0).repeatForever(autoreverses:false), value: rotates)`，无门控。
- `ChatMessageListSupport.swift:267-283`(TypingDots):`TimelineView(.animation(minimumInterval: 1/20))` 直接驱动正弦透明度，无 reduceMotion。
- 流式 glyph fade:vendor 目录全量 grep `reduceMotion` 零命中（`iosApp/vendor/SwiftStreamingMarkdown`)，确实不受系统设置控制。
- 修复方向评价：合理且与既有 P2-15 模式一致（降级为静态帧/无动画）。vendor 侧改动需遵守其局部 AGENTS.md（外科修改 + 注解）；TypingDots 可在 reduceMotion 下用固定中等透明度替代 TimelineView。

### 条目26：高频控件点击区域 < 44pt — **确认成立**
- 顶栏：`ChatView.swift:9` `toolbarButtonDiameter: CGFloat = 38`,:740/:753 返回/新建对话均用 38pt。
- 回到底部：`ChatComposerViews.swift:59` `.frame(width: 38, height: 38)`。
- 附件：`ChatView.swift:1104` `.frame(width: 32, height: 32)`。
- 思考/上下文：`ChatComposerViews.swift:28` `ComposerIconButton` 默认 `size: CGFloat = 34`，调用方（`ChatView.swift:1181` 等）未覆盖。
- `.contentShape(Circle())`（如 ChatView.swift:1105）仅按现有 bounds 裁剪命中形状，不外扩——说法准确。
- 修复方向评价：合理。视觉尺寸可保持，用额外 padding + `contentShape` 扩到 ≥44pt 命中区即可；注意附件键 32pt 扩到 44pt 后与相邻输入胶囊的间距/玻璃胶囊布局需复核。

### 条目27：流式渲染器设置开关无效 — **确认成立**
- `MessageBubbleView.swift:616-618` 与 `:620-626`:`shouldUseExperimentalMarkdownRenderer` 与 `shouldUseFadeStreamingRenderer` 谓词逐字相同：`liveStreaming || hasUsedStreamingMarkdownRenderer || (hasEverStreamed && liveRenderingEnabled)`。
- 消费链 `MessageBubbleView.swift:780-790`:fade 分支无设置门控且排在 liyanan/microsoft 分支之前；谓词相同 ⇒ 后两个 `else if … && shouldUseExperimental…` 永远不可达（死代码）。
- `DisplayFontSettingsView.swift:170-190` "使用微软流式 MD 渲染库"/"使用 MarkdownView 流式渲染库" 两个 toggle 因此无实际效果（:162 落在该设置组内，吻合）。
- 修复方向评价：合理。最小修法是把 fade 分支改为"未启用任何 experimental 库时"的默认路径（`else if !liyananStreamingMarkdown && !microsoftStreamingMarkdown && shouldUseFade…`)，或直接把两个 experimental 分支提前；顺带应补一条选择器单测防回归。

### 条目28:"长文本粘贴为文件"是无副作用状态写入 — **确认成立**
- `ChatView.swift:1136-1142`:`onChange` 超阈值只执行 `pasteHintShown = true`。
- 全仓库 grep `pasteHintShown` 仅 3 处命中：声明（:122）与这两行读写，**没有任何消费者**——无提示 UI、无附件转换、无发送分支。开关 `pasteLongTextAsFile` 因此整体无效。
- 修复方向评价：合理，但属二选一的产品决策——要么实现真实的"粘贴转附件"（确认卡片 + `attachSelectedFile` 路径），要么删掉该设置项与死状态；不应只补个 toast 就算闭环。

### 条目29：多图生成占位只有 1 张 — **确认成立**
- `MessageBubbleView.swift:2708`:`requestedCount = min(max(intValue(object?["count"]) ?? 1, 1), 4)`,count 已解析。
- 进行态 `ChatGeneratedImageLoadingPlaceholder`(:2636-2659)：只画单个 `ChatGeneratedImageDotPlaceholder`,`requestedCount > 1` 仅影响宽度（`multiCardWidth`)，不画 N 张。
- 完成态 `ChatGeneratedImageGrid`(:2584 起）:`images.count > 1` 时横向 ScrollView 铺 N 张 tile ⇒ 占位 1 张 → 完成 N 张，content size 瞬时跳变成立（条目所引 :2584 正是 grid 结构体起点）。
- 修复方向评价：合理。进行态按 `requestedCount` 画 N 张占位（沿用完成态的 HStack/横向布局），即可消除完成瞬间的几何跳变，改动局部、风险低。

### 条目30:$x^2$ 行内公式被当字面文本 — **确认成立**
- `MarkdownParseOption.swift:26-32`:`LatexMatching` 只有 `inlineSlashBracket` `\(...\)`、`blockDollar` `$$...$$`、`blockSlashBracket` `\[...\]`,**无单美元 inline 规则**。
- Known Gap 测试存在（但不在 vendor，在 App 测试）:`iosApp/iosAppTests/ChatMessageProjectionTests.swift:116` `testKnownGap_B3b_inlineMathRendersAsLiteralText`，注释明示"B3b 实现时本测试应转红并被替换"，当前断言 `$x^2$` 以字面文本落盘。条目把测试位置含糊为 vendor 证据行，实际证据文件正确、测试在他处——不影响结论。
- **修复建议（单美元解析规则）的流式误伤风险评价：风险真实且偏高，需要额外护栏**。具体三点：
  1. **货币误伤**:LLM 输出中 "$5 和 $10"、"price is $100, discount $20" 这类双美元句极常见，朴素 `$...$` 配对会把价格区间吞成公式。至少需要 pandoc 式约束（开 `$` 后非空白/非数字、闭 `$` 前非空白、闭 `$` 后非数字），或要求内容含 LaTeX 特征字符。
  2. **增量解析振荡**：流式逐 chunk 到达时，"$x^2"（未闭合）与 "$x^2$"（闭合）会交替出现，公式↔字面文本来回切换会引发尾部重排；在本仓库还会触发 vendor 尾段 fade 重放（P1-4 修过的那类问题），视觉闪烁。需要把单美元规则与 `speculativeRewrite` 的交互想清楚（未闭合 `$` 应视为字面文本直到闭合，且闭合后不得回改已稳定的早前块）。
  3. **完成态重渲染**:Known Gap 测试的注释已预设"实现时旧断言转红"，意味着完成态从字面文本切成公式渲染会有高度/宽度跳变；历史消息冷加载也应走同规则，避免流式/历史两条路径（streaming vendor vs AmberMarkdownView）判定不一致——目前两条链路解析器不同，规则需双端对齐，否则又制造一个新的 renderer 不一致缺口。

### Blocker
无（七条均为既有缺陷复核，全部确认成立，未发现条目夸大或已修复的过时断言）。

### Note
- 条目25/27/28 属"开关存在但无效/覆盖不全"，修复后建议各补一条最小契约测试（27 已述；25 可仿 `testReduceMotionStaticFrame`;28 若实现转附件需 UI 层接缝）。
- `plan.md`/`progress.md` 缺失已按只读任务记录，未补建。
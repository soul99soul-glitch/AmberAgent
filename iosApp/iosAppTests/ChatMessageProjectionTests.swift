import XCTest
import Combine
import Shared
@testable import SwiftStreamingMarkdown
@testable import iosApp

@MainActor
final class ChatMessageProjectionTests: XCTestCase {

    private func source(_ relativePath: String) throws -> String {
        let testsDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let iosAppRoot = testsDir.deletingLastPathComponent()
        return try String(
            contentsOf: iosAppRoot.appendingPathComponent(relativePath),
            encoding: .utf8
        )
    }

    func testLiveTailModelPublishesAtMostOncePerSignalRevision() {
        let message = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: "stream", metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let renderState = ChatRenderState(
            rendererMode: .streamingMarkdown,
            hasEverStreamed: true,
            liveRenderingEnabled: true,
            frozenMarkdownSnapshot: nil
        )
        let model = ChatLiveTailModel(
            message: message,
            isGenerationActive: true,
            renderState: renderState
        )
        var publishedUpdates = 0
        let cancellable = model.objectWillChange.sink { publishedUpdates += 1 }

        model.update(
            message: message,
            isGenerationActive: true,
            renderState: renderState,
            sourceRevision: 7
        )
        model.update(
            message: message,
            isGenerationActive: true,
            renderState: renderState,
            sourceRevision: 7
        )
        XCTAssertEqual(publishedUpdates, 1)

        model.update(
            message: message,
            isGenerationActive: true,
            renderState: renderState,
            sourceRevision: 8
        )
        XCTAssertEqual(publishedUpdates, 2)
        withExtendedLifetime(cancellable) {}
    }

    func testFrozenMarkdownSnapshotUsesTheSingleNonEmptyTextPartVerbatim() {
        let messageID = KotlinUuid.companion.random()
        let message = UIMessage(
            id: messageID,
            role: MessageRole.assistant,
            parts: [
                UIMessagePart.Text(text: "", metadata: nil),
                UIMessagePart.Text(text: "正文", metadata: nil),
            ],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )

        XCTAssertEqual(message.singleNonEmptyTextPart, "正文")

        let multipleTextParts = UIMessage(
            id: messageID,
            role: MessageRole.assistant,
            parts: [
                UIMessagePart.Text(text: "第一段", metadata: nil),
                UIMessagePart.Text(text: "第二段", metadata: nil),
            ],
            annotations: [],
            createdAt: message.createdAt,
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        XCTAssertNil(multipleTextParts.singleNonEmptyTextPart)
    }

    func testPackedAstReaderParsesBlockMathNode() throws {
        let markdown = "$$E=mc^2$$"
        let node = try XCTUnwrap(Self.firstNode(ofType: .mathBlock, in: Self.astNodes(for: markdown)))

        XCTAssertEqual(Self.slice(markdown, node: node), "$$E=mc^2$$")
    }

    func testBlockMathLatexExtraction() throws {
        let inline = try XCTUnwrap(Self.firstNode(ofType: .mathBlock, in: Self.astNodes(for: "$$E=mc^2$$")))
        XCTAssertEqual(AmberMarkdownMath.blockLatex(from: inline, source: "$$E=mc^2$$"), "E=mc^2")

        let multilineMarkdown = """
        $$
          a^2 + b^2 = c^2
        $$
        """
        let multiline = try XCTUnwrap(Self.firstNode(ofType: .mathBlock, in: Self.astNodes(for: multilineMarkdown)))
        XCTAssertEqual(AmberMarkdownMath.blockLatex(from: multiline, source: multilineMarkdown), "a^2 + b^2 = c^2")
    }

    func testKnownGap_B3b_inlineMathRendersAsLiteralText() throws {
        // B3b 实现时本测试应转红并被替换为真渲染断言；设计见 IOS_FIX_PLAN_2026-07-08.md B3b。
        let markdown = "inline $x^2$ math"
        let node = try XCTUnwrap(Self.firstNode(ofType: .mathInline, in: Self.astNodes(for: markdown)))

        XCTAssertEqual(Self.slice(markdown, node: node), "$x^2$")
    }

    func testToolStepModelMarksStructuredFailureOutputAsFailed() {
        let tool = UIMessagePart.Tool(
            toolCallId: "search-denied",
            toolName: "search_web",
            input: #"{"query":"swift"}"#,
            output: [
                UIMessagePart.Text(
                    text: #"{"ok":false,"denied":true,"reason":"用户拒绝搜索。","tool":"search_web"}"#,
                    metadata: nil
                )
            ],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )

        let model = ChatToolStepModel(tool: tool)

        XCTAssertEqual(model.state, .failed)
        XCTAssertEqual(model.detail, "用户拒绝搜索。")
    }

    func testChatMarkdownOpenURLPolicyAllowsOnlyWebAndMailtoSchemes() throws {
        XCTAssertTrue(ChatMarkdownOpenURLPolicy.isAllowed(try XCTUnwrap(URL(string: "https://example.com/a"))))
        XCTAssertTrue(ChatMarkdownOpenURLPolicy.isAllowed(try XCTUnwrap(URL(string: "http://example.com/a"))))
        XCTAssertTrue(ChatMarkdownOpenURLPolicy.isAllowed(try XCTUnwrap(URL(string: "mailto:hello@example.com"))))

        XCTAssertFalse(ChatMarkdownOpenURLPolicy.isAllowed(try XCTUnwrap(URL(string: "shortcuts://run-shortcut?name=bad"))))
        XCTAssertFalse(ChatMarkdownOpenURLPolicy.isAllowed(try XCTUnwrap(URL(string: "file:///private/var/mobile/Library/foo"))))
        XCTAssertFalse(ChatMarkdownOpenURLPolicy.isAllowed(try XCTUnwrap(URL(string: "javascript:alert(1)"))))
    }

    func testInvalidDataImageURLResolvesToFailure() {
        guard case .failure = ChatDataImageLoadState.resolve(
            urlString: "data:image/jpeg;base64,this-is-not-base64"
        ) else {
            return XCTFail("损坏的 data URL 必须结束 loading 并进入失败态")
        }
    }

    func testGeneratedImageLoadingShowsOnePlaceholderPerRequestedImage() throws {
        let bubble = try source("iosApp/MessageBubbleView.swift")

        XCTAssertTrue(bubble.contains("ForEach(0..<display.requestedCount"))
    }

    func testToolDetailSelectionKeepsStableIdentityAndResolvesCurrentMessageOutput() throws {
        let bubble = try source("iosApp/MessageBubbleView.swift")
        let detail = try source("iosApp/ChatToolDetailSheet.swift")

        XCTAssertTrue(detail.contains("let toolCallId: String"))
        XCTAssertFalse(detail.contains("let id = UUID()"))
        XCTAssertTrue(bubble.contains("toolPart(toolCallId: target.toolCallId)"))
    }

    func testNativeTimelineSessionIdentityChangesAcrossConversations() {
        let first = KotlinUuid.companion.random()
        let second = KotlinUuid.companion.random()

        XCTAssertNotEqual(
            NativeChatTimelineSessionIdentity.viewID(conversationId: first),
            NativeChatTimelineSessionIdentity.viewID(conversationId: second)
        )
    }

    func testTableCellFadeInPolicyDoesNotDisableVisibleAnimationForLargeTables() {
        XCTAssertTrue(
            TableViewAnimationPolicy.shouldAnimateCellText(
                configShouldAnimateText: true,
                headingCount: 4,
                rowCount: 8,
                characterCount: 120
            )
        )
    }

    func testTableStreamingThrottleTiersOnlySlowDownHugeTables() {
        // <12K 档位保持历史值;超大表格(≥12K utf16)降频,减少整表布局卡顿(P1-5 止血)。
        let live = ChatStreamingMarkdownThrottleTestSupport.liveParseInterval
        XCTAssertEqual(live(800, true), 0.12)
        XCTAssertEqual(live(3_000, true), 0.20)
        XCTAssertEqual(live(10_000, true), 0.32)
        XCTAssertEqual(live(24_000, true), 0.5)
        XCTAssertEqual(live(24_000, false), 0, "普通文本不在此加独立定时门")

        let publish = ChatStreamingMarkdownThrottleTestSupport.blockPublishInterval
        XCTAssertEqual(publish(800, true), 0.09)
        XCTAssertEqual(publish(3_000, true), 0.12)
        XCTAssertEqual(publish(10_000, true), 0.16)
        XCTAssertEqual(publish(24_000, true), 0.22)
        XCTAssertEqual(publish(24_000, false), 0)
    }

    func testStreamingMarkdownConfigCacheKeyTracksPaperAndAccent() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testDirectory.deletingLastPathComponent()
                .appendingPathComponent("iosApp/MessageBubbleView.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("let themePaper: String"))
        XCTAssertTrue(source.contains("let themeAccentHex: UInt32"))
        XCTAssertTrue(source.contains("themePaper: AmberThemeRuntime.shared.paper.rawValue"))
        XCTAssertTrue(source.contains("themeAccentHex: AmberThemeRuntime.shared.accentHex"))
        XCTAssertEqual(
            ChatStreamingMarkdownConfigCacheTestSupport.buildCount(themeKeys: [
                (paper: "paper", accentHex: 0xB8623A),
                (paper: "paper", accentHex: 0xB8623A),
                (paper: "neutral", accentHex: 0xB8623A),
                (paper: "neutral", accentHex: 0x5E9C6E),
            ]),
            3,
            "相同主题必须命中缓存，paper/accent 任一变化必须重建 config"
        )
    }

    func testStreamingBlockParserKeepsHeadingContextInOneMarkdownDocument() {
        let markdown = """
        [文档][reference]

        # 标题

        [reference]: https://example.com

        <script>
        # 这不是 Markdown 标题
        </script>
        """

        let blocks = ChatStreamingMarkdownBlockParserTestSupport.blocks(
            in: markdown,
            includeTrailingPartialTableRow: true
        )

        XCTAssertEqual(blocks.count, 1)
        XCTAssertEqual(blocks.first?.kind, "text")
        XCTAssertEqual(blocks.first?.text, markdown)
    }

    func testStreamingBlockParserKeepsPartialHashAppendInOneTextBlock() {
        let prefix = ChatStreamingMarkdownBlockParserTestSupport.blocks(
            in: "正文\n\n#",
            includeTrailingPartialTableRow: false
        )
        let completed = ChatStreamingMarkdownBlockParserTestSupport.blocks(
            in: "正文\n\n#tag",
            includeTrailingPartialTableRow: false
        )

        XCTAssertEqual(prefix.count, 1)
        XCTAssertEqual(completed.count, 1)
        XCTAssertEqual(prefix.first?.kind, "text")
        XCTAssertEqual(completed.first?.kind, "text")
    }

    func testTextKit1LongParagraphMeasuresEachAppendAtLineGranularity() {
        let width: CGFloat = 337.3
        let view = ParagraphUIView.makeTextKit1View()
        view.frame = CGRect(x: 0, y: 0, width: width, height: 1)
        XCTAssertTrue(view.usesTextKit1)
        let attributes: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 17),
            .foregroundColor: UIColor.label
        ]
        var text = "连续正文开始。" + String(
            repeating: "这是一段没有空行分隔的长篇连续正文，用来复现真实回答累计变长后同一段落仍在增长的布局压力。",
            count: 180
        )
        func contents() -> NSMutableAttributedString {
            NSMutableAttributedString(string: text, attributes: attributes)
        }

        view.setParagraphContents(contents(), lineSpacing: 4, animatedByWord: false)
        var measuredHeight = view.sizeThatFits(CGSize(
            width: width,
            height: .greatestFiniteMagnitude
        )).height
        view.frame.size.height = measuredHeight
        var positiveGrowth: [CGFloat] = []

        for _ in 0..<60 {
            text += String(repeating: "流", count: 12)
            view.setParagraphContents(contents(), lineSpacing: 4, animatedByWord: false)
            let nextHeight = view.sizeThatFits(CGSize(
                width: width,
                height: .greatestFiniteMagnitude
            )).height
            if nextHeight > measuredHeight + 0.5 {
                positiveGrowth.append(nextHeight - measuredHeight)
            }
            measuredHeight = nextHeight
            view.frame.size.height = nextHeight
        }

        XCTAssertGreaterThanOrEqual(positiveGrowth.count, 30)
        XCTAssertLessThanOrEqual(positiveGrowth.max() ?? .greatestFiniteMagnitude, 26)
    }

    func testStreamingTableParserDoesNotLeakTrimmedTrailingRowAsPipeText() {
        let markdown = """
        | 阶段 | 能力 |
        | --- | --- |
        | 初觉 | 雷感 |
        | 一劫雷 | 掌心
        """

        let blocks = ChatStreamingMarkdownBlockParserTestSupport.blocks(
            in: markdown,
            includeTrailingPartialTableRow: false
        )

        XCTAssertEqual(blocks.count, 1)
        XCTAssertEqual(blocks.first?.kind, "table")
        XCTAssertEqual(blocks.first?.rows, [["初觉", "雷感"]])
        XCTAssertFalse(blocks.contains { $0.kind == "text" && $0.text.contains("|") })
    }

    func testStreamingTableParserKeepsSinglePartialRowAsEmptyTableBlock() {
        let markdown = """
        | 层次 | 说明 |
        | --- | --- |
        | 人间界 | 现代都市
        """

        let blocks = ChatStreamingMarkdownBlockParserTestSupport.blocks(
            in: markdown,
            includeTrailingPartialTableRow: false
        )

        XCTAssertEqual(blocks.count, 1)
        XCTAssertEqual(blocks.first?.kind, "table")
        XCTAssertEqual(blocks.first?.headers, ["层次", "说明"])
        XCTAssertEqual(blocks.first?.rows, [])
        XCTAssertFalse(blocks.contains { $0.kind == "text" && $0.text.contains("|") })
    }

    func testStreamingTableParserHidesNoLeadingPipePartialRow() {
        let markdown = """
        名称 | 数值
        --- | ---
        Alpha | 1
        Bet
        """

        let blocks = ChatStreamingMarkdownBlockParserTestSupport.blocks(
            in: markdown,
            includeTrailingPartialTableRow: false
        )

        XCTAssertEqual(blocks.count, 1)
        XCTAssertEqual(blocks.first?.kind, "table")
        XCTAssertEqual(blocks.first?.rows, [["Alpha", "1"]])
    }

    func testStreamingTableDetectorMatchesAcceptedSyntaxAndIgnoresFences() {
        XCTAssertTrue(ChatStreamingMarkdownBlockParserTestSupport.containsTable(in: """
        名称 | 数值
        --- | ---
        Alpha | 1
        """))
        XCTAssertFalse(ChatStreamingMarkdownBlockParserTestSupport.containsTable(in: """
        ~~~markdown
        ```not-a-closing-fence
        | 名称 | 数值 |
        | --- | --- |
        ~~~
        """))
    }

    func testStreamingTableDetectorConsumesOnlyAppendedUTF8AcrossDeltas() {
        let chunks = [
            "普通文本",
            "继续增长",
            "\n名称 | 数值",
            "\n---",
            " | ---"
        ]
        var text = ""
        let prefixes = chunks.map { chunk -> String in
            text += chunk
            return text
        }

        let result = ChatStreamingTableDetectionTestSupport.replay(prefixes)

        XCTAssertTrue(result.containsTable)
        XCTAssertEqual(result.consumedUTF8Count, text.utf8.count)
    }

    func testIncrementalStreamingTableDetectorKeepsFenceStateAcrossDeltas() {
        let prefixes = [
            "~~~markdown\n",
            "~~~markdown\n| 名称 | 数值 |\n",
            "~~~markdown\n| 名称 | 数值 |\n| --- | --- |"
        ]

        XCTAssertFalse(ChatStreamingTableDetectionTestSupport.replay(prefixes).containsTable)
    }

    func testStreamingTableRowCacheReusesCompletedRowsAcrossDeltas() {
        ChatStreamingMarkdownBlockParserTestSupport.resetRowCache()
        let prefix = """
        | 名称 | 数值 |
        | --- | --- |
        | Alpha | 1 |
        | Beta | 2 |
        """
        _ = ChatStreamingMarkdownBlockParserTestSupport.blocks(
            in: prefix,
            includeTrailingPartialTableRow: false
        )
        let firstMetrics = ChatStreamingMarkdownBlockParserTestSupport.rowCacheMetrics

        _ = ChatStreamingMarkdownBlockParserTestSupport.blocks(
            in: prefix + "\n| Gamma | 3",
            includeTrailingPartialTableRow: false
        )
        let secondMetrics = ChatStreamingMarkdownBlockParserTestSupport.rowCacheMetrics

        XCTAssertGreaterThan(firstMetrics.misses, 0)
        XCTAssertGreaterThan(secondMetrics.hits, firstMetrics.hits)
    }

    func testProductionStreamingTableBlocksDoNotMaterializeUnusedCellModels() {
        let markdown = """
        | 名称 | 数值 |
        | --- | --- |
        | Alpha | 1 |
        | Beta | 2 |
        """

        let counts = ChatStreamingMarkdownBlockParserTestSupport.productionTableCellCounts(in: markdown)

        XCTAssertEqual(counts?.headers, 0)
        XCTAssertEqual(counts?.rows, 0)
    }

    func testStreamingTableBlockPreservesEscapedPipeForRealMarkdownRenderer() {
        let markdown = """
        | 表达式 | 含义 |
        | --- | --- |
        | `a \\| b` | escaped pipe |
        """

        let blocks = ChatStreamingMarkdownBlockParserTestSupport.blocks(
            in: markdown,
            includeTrailingPartialTableRow: true
        )

        XCTAssertEqual(blocks.count, 1)
        XCTAssertEqual(blocks.first?.kind, "table")
        XCTAssertEqual(blocks.first?.rows, [["`a | b`", "escaped pipe"]])
        XCTAssertEqual(blocks.first?.markdown, markdown)
    }

    func testStreamingTableBlockUsesVendorMarkdownRendererAndAsyncBlockController() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testDirectory.deletingLastPathComponent()
                .appendingPathComponent("iosApp/MessageBubbleView.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("text: table.markdown,"))
        XCTAssertTrue(source.contains("cacheIdentity: renderCacheNamespace.map"))
        XCTAssertTrue(source.contains("private final class ChatStreamingMarkdownBlockController"))
        XCTAssertTrue(source.contains("Task.detached(priority: .userInitiated)"))

        let controllerSource = source
            .components(separatedBy: "private final class ChatStreamingMarkdownBlockController")
            .last?
            .components(separatedBy: "private struct ChatStreamingMarkdownBlock:")
            .first ?? ""
        XCTAssertTrue(controllerSource.contains("publishPreservingSettledBlocks(parsed)"))
        XCTAssertFalse(
            controllerSource.contains("if pendingParse == nil"),
            "Continuous deltas must not starve every completed block parse publication."
        )
    }

    func testCompletedMarkdownRenderableCacheDoesNotReuseSpeculativeStreamingEntry() {
        ChatStableStreamingMarkdownCacheTestSupport.reset()
        let markdown = """
        | 层次 | 说明 |
        | --- | --- |
        | 人间界 |
        """

        ChatStableStreamingMarkdownCacheTestSupport.store(text: markdown, animate: true)

        XCTAssertFalse(
            ChatStableStreamingMarkdownCacheTestSupport.hasCachedRenderable(text: markdown, animate: false)
        )
    }

    func testCompletedMarkdownRenderableCacheDoesNotUsePrefixFallback() {
        ChatStableStreamingMarkdownCacheTestSupport.reset()
        let prefix = "第一段已经解析"
        let completedText = "\(prefix)\n第二段完成态新增内容"

        ChatStableStreamingMarkdownCacheTestSupport.store(text: prefix, animate: true)

        XCTAssertTrue(
            ChatStableStreamingMarkdownCacheTestSupport.hasCachedRenderable(text: completedText, animate: true)
        )
        XCTAssertFalse(
            ChatStableStreamingMarkdownCacheTestSupport.hasCachedRenderable(text: completedText, animate: false)
        )
    }

    func testIdentityCacheRetainsEnoughCompletedParagraphsToSuppressRemountFade() {
        ChatStableStreamingMarkdownCacheTestSupport.reset()
        // 长篇(小说/长对话)可有上百个段落 block。完成态重挂载/回滚时,只要 identity
        // 缓存还持有该段落的 renderable,resolution 就返回 suppressesInitialFade=true,
        // 不再重新淡入。旧上限 64 会让靠前的段落被后续解析挤出 → 完成瞬间整屏重新淡入
        // (闪烁);提到 256 后,第 0 段在缓存了 100 段之后必须仍可解析。
        let paragraphCount = 100
        for index in 0..<paragraphCount {
            ChatStableStreamingMarkdownCacheTestSupport.storeIdentity(
                identity: "block-\(index)",
                text: "paragraph \(index)",
                animate: false
            )
        }
        XCTAssertTrue(
            ChatStableStreamingMarkdownCacheTestSupport.hasCachedIdentity(
                identity: "block-0",
                text: "paragraph 0",
                animate: false
            ),
            "identity 缓存应至少容纳 100 个完成段落,否则已完成段落被挤出,完成/回滚时重新淡入闪烁。"
        )
    }

    func testStableStreamingMarkdownControllerResumesAnimatedParsingAfterNonAnimatedParseCompletes() async {
        let renderedText = await ChatStableStreamingMarkdownControllerTestSupport
            .renderedTextAfterNonAnimatedThenAnimatedParse()

        XCTAssertEqual(renderedText, "initial completed text with live delta")
    }

    func testStableStreamingMarkdownControllerQueuesAnimatedDeltaDuringNonAnimatedParse() async {
        let renderedText = await ChatStableStreamingMarkdownControllerTestSupport
            .renderedTextWhenAnimatedParseArrivesDuringNonAnimatedParse()

        XCTAssertEqual(renderedText, "non-animated parse followed immediately by live delta")
    }

    func testStableStreamingMarkdownControllerReusesOnlyGrowingPrefixRenderable() {
        XCTAssertTrue(
            ChatStableStreamingMarkdownControllerTestSupport.hasStaleRenderable(
                renderedText: "已解析前缀",
                requestedText: "已解析前缀继续增长"
            )
        )
        XCTAssertFalse(
            ChatStableStreamingMarkdownControllerTestSupport.hasStaleRenderable(
                renderedText: "正文\n| 表头 | 数值 |",
                requestedText: "正文"
            ),
            "表格拆块使文本收缩时不能继续显示包含表头的旧 renderable"
        )
    }

    func testStableStreamingMarkdownControllerKeepsInstanceRenderableAcrossCompletionParse() {
        let resolution = ChatStableStreamingMarkdownControllerTestSupport
            .instanceResolutionAfterSpeculativeModeChange()

        XCTAssertTrue(resolution.hasRenderable, "完成态解析落地前应保留同一文本的已渲染内容，不能退回纯文本。")
        XCTAssertTrue(resolution.suppressesInitialFade, "完成态复用已有内容时不能让整段文字重新淡入。")
    }

    func testStableStreamingMarkdownControllerKeepsIdentityRenderableAcrossColdCompletion() {
        let resolution = ChatStableStreamingMarkdownControllerTestSupport
            .coldCompletionIdentityResolution()

        XCTAssertTrue(resolution.hasRenderable, "完成瞬间发生视图重建时也不能退回纯文本。")
        XCTAssertTrue(resolution.suppressesInitialFade, "冷完成复用已有内容时不能让整段文字重新淡入。")
    }

    func testStableStreamingMarkdownControllerKeepsSpeculativeRenderableForUnclosedMarkupAtCompletion() {
        let resolution = ChatStableStreamingMarkdownControllerTestSupport
            .instanceResolutionAfterSpeculativeModeChangeWithUnclosedMarkup()

        XCTAssertTrue(
            resolution.hasRenderable,
            "中断/超时使文本停在未闭合语法时，完成态仍复用流式 renderable 保持连续，随后由立即重解析纠正。"
        )
        XCTAssertTrue(
            resolution.suppressesInitialFade,
            "跨模式复用未闭合内容时同样不能让整段文字重新淡入。"
        )
    }

    func testRenderableDocumentReusesOnlyUnchangedPrefixObjects() async {
        let parser = MarkdownParserImpl()
        let initial = await parser.parse(text: "First paragraph.\n\nSecond paragraph.")
        let updated = await parser.parse(text: "First paragraph.\n\nSecond paragraph grows.")
        let initialRenderable = await RenderableDocument(document: initial, config: .default)
        let converted = await RenderableDocument(document: updated, config: .default)
        let updatedRenderable = converted.reusingUnchangedPrefix(from: initialRenderable)

        guard case let .paragraph(_, initialFirst) = initialRenderable.renderables[0],
              case let .paragraph(_, updatedFirst) = updatedRenderable.renderables[0],
              case let .paragraph(_, initialSecond) = initialRenderable.renderables[1],
              case let .paragraph(_, updatedSecond) = updatedRenderable.renderables[1] else {
            return XCTFail("Expected two paragraphs")
        }

        XCTAssertTrue(initialFirst === updatedFirst)
        XCTAssertFalse(initialSecond === updatedSecond)
        XCTAssertEqual(updatedSecond.string, "Second paragraph grows.")
    }

    func testStableStreamingMarkdownControllerRejectsInstanceRenderableAcrossVisualConfigChange() {
        XCTAssertFalse(
            ChatStableStreamingMarkdownControllerTestSupport
                .hasInstanceRenderableAfterVisualConfigChange(),
            "字体或排版配置变化后不能先显示旧 config renderable。"
        )
    }

    func testStableStreamingMarkdownControllerReusesIdentityPrefixOnColdReentry() {
        let result = ChatStableStreamingMarkdownControllerTestSupport
            .coldReentryIdentityPrefixResolution()

        XCTAssertTrue(result.hasRenderable, "冷重入必须复用该消息块已解析的累计前缀，而不是退回 raw Markdown。")
        XCTAssertTrue(result.suppressesInitialFade, "重建已有前缀时不能把整段内容再次从 alpha 0 淡入。")
    }

    func testStreamingTableHonorsColdReentryFadeSuppression() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let tableViewURL = testDirectory.deletingLastPathComponent()
            .appendingPathComponent("vendor/SwiftStreamingMarkdown/Sources/MarkdownText/UI/TableView.swift")
        let source = try String(contentsOf: tableViewURL, encoding: .utf8)

        XCTAssertTrue(source.contains("@Environment(\\.markdownAnimateInitialText) var animateInitialText"))
        XCTAssertTrue(source.contains("config.shouldAnimateText && animateInitialText"))
    }

    func testCompletedStreamedMarkdownKeepsBlockTopologyAfterColdRecreation() {
        XCTAssertTrue(
            ChatStreamingMarkdownRendererPolicy.initialBlockRendererLatch(
                isStreaming: false,
                hasEverStreamed: true,
                liveRenderingEnabled: true
            ),
            "完成态流式消息回收后必须继续使用 block renderer，不能切回 monolith。"
        )
    }

    func testStreamingMarkdownTypographyFollowsChatFont() {
        let defaultFont = ChatStreamingMarkdownTypographyTestSupport.bodyFontName(chatFont: .default)
        let serifFont = ChatStreamingMarkdownTypographyTestSupport.bodyFontName(chatFont: .serif)
        let monospaceFont = ChatStreamingMarkdownTypographyTestSupport.bodyFontName(chatFont: .monospace)

        XCTAssertNotEqual(defaultFont, serifFont)
        XCTAssertNotEqual(defaultFont, monospaceFont)
        XCTAssertNotEqual(serifFont, monospaceFont)
    }

    func testStreamingParagraphAnimationUsesPromotionFrameRateRange() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let paragraphViewURL = testDirectory.deletingLastPathComponent()
            .appendingPathComponent("vendor/SwiftStreamingMarkdown/Sources/MarkdownText/UI/UIKit/ParagraphUIView.swift")
        let source = try String(contentsOf: paragraphViewURL, encoding: .utf8)

        XCTAssertTrue(source.contains("preferredFrameRateRange"))
        XCTAssertFalse(source.contains("preferredFramesPerSecond = 60"))
        XCTAssertFalse(source.contains("completedAnimations.contains"))
        XCTAssertFalse(source.contains("let id: UUID = UUID()"))
    }

    func testCheckedOutParagraphViewCannotBeIssuedTwice() {
        let contents = NSMutableAttributedString(string: "streaming paragraph")
        let first = ParagraphUIViewCache.shared.createOrReuseParagraphUIView(
            contents: contents,
            lineSpacing: nil
        )
        let second = ParagraphUIViewCache.shared.createOrReuseParagraphUIView(
            contents: contents,
            lineSpacing: nil
        )

        XCTAssertFalse(
            first === second,
            "同一个 ParagraphUIView 不能同时交给两个 SwiftUI representable"
        )

        ParagraphUIViewCache.shared.recycle(first)
        ParagraphUIViewCache.shared.recycle(second)
    }

    func testParagraphViewCacheDoesNotCrossTextLayoutEngines() {
        let contents = NSMutableAttributedString(string: "layout engine isolation")
        let textKit1 = ParagraphUIViewCache.shared.createOrReuseParagraphUIView(
            contents: contents,
            lineSpacing: nil,
            usesTextKit1: true
        )
        XCTAssertTrue(textKit1.usesTextKit1)
        ParagraphUIViewCache.shared.recycle(textKit1)

        let textKit2 = ParagraphUIViewCache.shared.createOrReuseParagraphUIView(
            contents: contents,
            lineSpacing: nil,
            usesTextKit1: false
        )
        XCTAssertFalse(textKit2.usesTextKit1)
        ParagraphUIViewCache.shared.recycle(textKit2)

        let reusedTextKit1 = ParagraphUIViewCache.shared.createOrReuseParagraphUIView(
            contents: contents,
            lineSpacing: nil,
            usesTextKit1: true
        )
        XCTAssertTrue(reusedTextKit1.usesTextKit1)
        ParagraphUIViewCache.shared.recycle(reusedTextKit1)
    }

    func testInitialLoadRowsDoNotAnimateInsertion() {
        let rows = ChatMessageProjector.rows(
            messages: [
                UIMessage.companion.user(prompt: "你好"),
                UIMessage.companion.assistant(prompt: "在。")
            ],
            event: .conversationLoaded
        )

        XCTAssertEqual(rows.count, 2)
        XCTAssertFalse(rows[0].canAnimateInsertion)
        XCTAssertFalse(rows[1].canAnimateInsertion)
    }

    func testOnlyLastUserAppendCanAnimateInsertion() {
        let user = UIMessage.companion.user(prompt: "新的问题")
        let rows = ChatMessageProjector.rows(
            messages: [user],
            event: .userMessageAppended
        )

        XCTAssertEqual(rows.first?.messageId, ChatMessageProjector.messageId(for: user))
        XCTAssertTrue(rows.first?.canAnimateInsertion ?? false)
    }

    func testUserAppendQueuesOnlyNewUserRowForInsertionAnimation() {
        let previous = UIMessage.companion.assistant(prompt: "旧回复")
        let user = UIMessage.companion.user(prompt: "新的问题")
        let previousItemID = "message-\(ChatMessageProjector.messageId(for: previous))"
        let rows = ChatMessageProjector.rows(
            messages: [previous, user],
            event: .userMessageAppended
        )

        let animationIDs = ChatInsertionAnimationPolicy.animatedInsertionItemIDs(
            previousItemIDs: [previousItemID],
            rows: rows
        )

        XCTAssertEqual(animationIDs, ["message-\(ChatMessageProjector.messageId(for: user))"])
    }

    func testUserAppendDoesNotQueueAnimationWhenUserItemAlreadyExists() {
        let user = UIMessage.companion.user(prompt: "新的问题")
        let existingItemID = "message-\(ChatMessageProjector.messageId(for: user))"
        let rows = ChatMessageProjector.rows(
            messages: [user],
            event: .userMessageAppended
        )

        let animationIDs = ChatInsertionAnimationPolicy.animatedInsertionItemIDs(
            previousItemIDs: [existingItemID],
            rows: rows
        )

        XCTAssertEqual(animationIDs, [])
    }

    func testBranchChangeDoesNotAnimateUserRows() {
        let rows = ChatMessageProjector.rows(
            messages: [UIMessage.companion.user(prompt: "切分支后的用户消息")],
            event: .branchChanged
        )

        XCTAssertFalse(rows.first?.canAnimateInsertion ?? true)
    }

    func testStreamingFinalReplaceKeepsRowIdentityAndStreamedMemory() {
        let streamed = UIMessage.companion.assistant(prompt: "正在生成")
        let messageId = ChatMessageProjector.messageId(for: streamed)

        let streamingRows = ChatMessageProjector.rows(
            messages: [streamed],
            event: .assistantStreamDelta
        )
        let finalRows = ChatMessageProjector.rows(
            messages: [streamed],
            event: .generationCompleted,
            streamedMessageIDs: [messageId]
        )

        XCTAssertEqual(streamingRows.first?.rowId, finalRows.first?.rowId)
        XCTAssertTrue(streamingRows.first?.isStreaming ?? false)
        XCTAssertTrue(finalRows.first?.hasEverStreamed ?? false)
    }

    func testTimelinePlanAlwaysEndsWithStableBottomAnchor() {
        let plan = ChatTimelinePlanner.build(
            messages: [UIMessage.companion.user(prompt: "你好")],
            event: .userMessageAppended
        )

        XCTAssertEqual(plan.entries.last, .bottomAnchor(id: ChatTimelinePlanner.bottomAnchorID))
    }

    func testTimelinePlanKeepsStreamingRendererAfterGenerationCompletes() {
        let assistant = UIMessage.companion.assistant(prompt: "# 标题\n\n正在生成长内容")
        let messageId = ChatMessageProjector.messageId(for: assistant)

        let streamingPlan = ChatTimelinePlanner.build(
            messages: [assistant],
            event: .assistantStreamDelta
        )
        let completedPlan = ChatTimelinePlanner.build(
            messages: [assistant],
            event: .generationCompleted,
            streamedMessageIDs: [messageId]
        )

        XCTAssertEqual(streamingPlan.messageEntry(for: messageId)?.id, completedPlan.messageEntry(for: messageId)?.id)
        XCTAssertEqual(streamingPlan.messageEntry(for: messageId)?.renderer, .streamingAssistantMarkdown)
        XCTAssertEqual(completedPlan.messageEntry(for: messageId)?.renderer, .streamingAssistantMarkdown)
        XCTAssertFalse(completedPlan.messageEntry(for: messageId)?.isStreaming ?? true)
        XCTAssertTrue(completedPlan.messageEntry(for: messageId)?.hasEverStreamed ?? false)
    }

    func testTimelinePlanUsesStaticRendererForHistoricalAssistantWithoutStreamMemory() {
        let assistant = UIMessage.companion.assistant(prompt: "历史回复")
        let messageId = ChatMessageProjector.messageId(for: assistant)

        let plan = ChatTimelinePlanner.build(
            messages: [assistant],
            event: .conversationLoaded
        )

        XCTAssertEqual(plan.messageEntry(for: messageId)?.renderer, .staticAssistantMarkdown)
    }

    func testTimelineRenderTokenChangesWhenStreamingTextChanges() {
        let short = UIMessage.companion.assistant(prompt: "第一段")
        let longer = UIMessage.companion.assistant(prompt: "第一段，继续追加新的 token")

        let shortPlan = ChatTimelinePlanner.build(messages: [short], event: .assistantStreamDelta)
        let longerPlan = ChatTimelinePlanner.build(messages: [longer], event: .assistantStreamDelta)

        XCTAssertNotEqual(shortPlan.latestRenderToken, longerPlan.latestRenderToken)
    }

    func testMessageIdKeepsDescriptionFormatViaCheapAccessor() {
        // messageId 改用 toHexDashString() 直接访问器;app 内另有少量
        // String(describing: message.id) 站点(MessageBubbleView 身份串、
        // context compaction),两种写法必须逐字等价,否则 ForEach 身份会串线。
        let message = UIMessage.companion.assistant(prompt: "格式 canary")

        XCTAssertEqual(message.id.toHexDashString(), String(describing: message.id))
        XCTAssertEqual(ChatMessageProjector.messageId(for: message), String(describing: message.id))
    }

    func testTimelinePlanCanSkipRenderTokensForNonNativePaths() {
        let assistant = UIMessage.companion.assistant(prompt: "正在生成")

        let skipped = ChatTimelinePlanner.build(
            messages: [assistant],
            event: .assistantStreamDelta,
            includeRenderTokens: false
        )
        let skippedEntries = skipped.entries.compactMap { entry -> ChatTimelineMessageEntry? in
            guard case let .message(messageEntry) = entry else { return nil }
            return messageEntry
        }
        XCTAssertFalse(skippedEntries.isEmpty)
        XCTAssertTrue(skippedEntries.allSatisfy { $0.renderToken.isEmpty })
        XCTAssertTrue(skipped.latestRenderToken.isEmpty)

        // 默认路径(native mirror diff 消费 token)必须保留非空 token。
        let withTokens = ChatTimelinePlanner.build(
            messages: [assistant],
            event: .assistantStreamDelta
        )
        let tokenEntries = withTokens.entries.compactMap { entry -> ChatTimelineMessageEntry? in
            guard case let .message(messageEntry) = entry else { return nil }
            return messageEntry
        }
        XCTAssertTrue(tokenEntries.allSatisfy { !$0.renderToken.isEmpty })
        XCTAssertFalse(withTokens.latestRenderToken.isEmpty)
    }

    func testNativeTimelineProjectionMirrorsTimelinePlanIdentityAndDecorations() {
        let user = UIMessage.companion.user(prompt: "问题")
        let assistant = UIMessage.companion.assistant(prompt: "回答")
        let plan = ChatTimelinePlanner.build(
            messages: [user, assistant],
            event: .assistantStreamDelta,
            includePendingAssistant: true
        )
        let projection = NativeTimelineProjector.build(
            messages: [user, assistant],
            event: .assistantStreamDelta,
            includePendingAssistant: true
        )

        XCTAssertEqual(projection.latestRenderToken, plan.latestRenderToken)
        XCTAssertEqual(projection.entries.map(\.id), plan.entries.map(\.id))
        XCTAssertEqual(projection.entries.map(\.kind), [
            .message,
            .message,
            .pendingAssistant,
            .bottomAnchor
        ])
        XCTAssertEqual(projection.entries.last?.id, ChatTimelinePlanner.bottomAnchorID)
    }

    func testNativeTimelineProjectionIncludesEmptyAndConfigurationDecorations() {
        let emptyProjection = NativeTimelineProjector.build(
            messages: [],
            event: .conversationLoaded
        )
        let configurationProjection = NativeTimelineProjector.build(
            messages: [],
            event: .conversationLoaded,
            configurationIssue: .missingAPIKey
        )

        XCTAssertEqual(emptyProjection.entries.map(\.kind), [.emptyState, .bottomAnchor])
        XCTAssertEqual(configurationProjection.entries.map(\.kind), [
            .configurationIssue(compact: false),
            .bottomAnchor
        ])
    }

    func testNativeTimelineProjectionIncludesTailDecorationsBeforeBottomAnchor() {
        let context = ChatContextCompactState(
            status: .completed,
            summary: "已压缩上下文",
            updatedAt: Date(timeIntervalSince1970: 1)
        )
        let projection = NativeTimelineProjector.build(
            messages: [UIMessage.companion.assistant(prompt: "回答")],
            event: .conversationLoaded,
            isRecognizingImages: true,
            contextCompactState: context
        )

        XCTAssertEqual(projection.entries.map(\.kind), [
            .message,
            .visionRecognition,
            .contextMarker,
            .bottomAnchor
        ])
    }

    func testNativeTimelineProjectionLeavesWaitingStatusToTopIslandByDefault() {
        let user = UIMessage.companion.user(prompt: "问题")
        let assistant = UIMessage.companion.assistant(prompt: "回答")

        let loadingAfterUser = NativeTimelineProjector.build(
            messages: [user],
            event: .userMessageAppended,
            isLoading: true
        )
        let explicitPendingAfterUser = NativeTimelineProjector.build(
            messages: [user],
            event: .userMessageAppended,
            isLoading: true,
            includePendingAssistant: true
        )
        let generatingAfterAssistant = NativeTimelineProjector.build(
            messages: [assistant],
            event: .assistantStreamDelta,
            isGenerationActive: true
        )

        XCTAssertEqual(loadingAfterUser.entries.map(\.kind), [.message, .bottomAnchor])
        XCTAssertEqual(
            explicitPendingAfterUser.entries.map(\.kind),
            [.message, .pendingAssistant, .bottomAnchor]
        )
        XCTAssertEqual(generatingAfterAssistant.entries.map(\.kind), [.message, .bottomAnchor])
    }

    func testNativeTimelineProjectionPreservesStreamingRendererMemory() {
        let assistant = UIMessage.companion.assistant(prompt: "流式内容")
        let messageId = ChatMessageProjector.messageId(for: assistant)

        let completedProjection = NativeTimelineProjector.build(
            messages: [assistant],
            event: .generationCompleted,
            streamedMessageIDs: [messageId]
        )
        let entry = completedProjection.messageEntry(for: messageId)

        XCTAssertEqual(entry?.kind, .message)
        XCTAssertEqual(entry?.messageId, messageId)
        XCTAssertEqual(entry?.renderer, .streamingAssistantMarkdown)
        XCTAssertFalse(entry?.isStreaming ?? true)
        XCTAssertTrue(entry?.hasEverStreamed ?? false)
    }

    func testNativeTimelineRendererMatrixMatchesTimelinePlanner() {
        let historical = UIMessage.companion.assistant(prompt: "历史回复")
        let streaming = UIMessage.companion.assistant(prompt: "正在生成")
        let completed = UIMessage.companion.assistant(prompt: "刚刚完成")
        let completedId = ChatMessageProjector.messageId(for: completed)
        let cases: [(message: UIMessage, event: ChatEvent, streamedIDs: Set<String>)] = [
            (historical, .conversationLoaded, []),
            (streaming, .assistantStreamDelta, []),
            (completed, .generationCompleted, [completedId])
        ]

        for item in cases {
            let messageId = ChatMessageProjector.messageId(for: item.message)
            let plan = ChatTimelinePlanner.build(
                messages: [item.message],
                event: item.event,
                streamedMessageIDs: item.streamedIDs
            )
            let projection = NativeTimelineProjector.build(
                messages: [item.message],
                event: item.event,
                streamedMessageIDs: item.streamedIDs
            )

            XCTAssertEqual(
                projection.messageEntry(for: messageId)?.renderer,
                plan.messageEntry(for: messageId)?.renderer
            )
            XCTAssertEqual(
                projection.messageEntry(for: messageId)?.hasEverStreamed,
                plan.messageEntry(for: messageId)?.hasEverStreamed
            )
            XCTAssertEqual(
                projection.messageEntry(for: messageId)?.isStreaming,
                plan.messageEntry(for: messageId)?.isStreaming
            )
        }
    }

    func testNativeTimelineProjectionCarriesActiveStreamingTailRendererIdentity() {
        let user = UIMessage.companion.user(prompt: "问题")
        let assistant = UIMessage.companion.assistant(prompt: "正在流式")
        let messageId = ChatMessageProjector.messageId(for: assistant)

        let projection = NativeTimelineProjector.build(
            messages: [user, assistant],
            event: .assistantStreamDelta,
            isGenerationActive: true,
            streamedMessageIDs: [messageId]
        )
        let entry = projection.messageEntry(for: messageId)

        XCTAssertEqual(entry?.renderer, .streamingAssistantMarkdown)
        XCTAssertTrue(entry?.isStreaming ?? false)
        XCTAssertTrue(entry?.hasEverStreamed ?? false)
    }

    func testNativeTimelineProjectionCarriesFullVariantInfoAndRenderDigest() {
        let user = UIMessage.companion.user(prompt: "问题")
        let messageId = ChatMessageProjector.messageId(for: user)
        let projection = NativeTimelineProjector.build(
            messages: [user],
            event: .conversationLoaded,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            variantInfoProvider: { index in
                index == 0
                    ? IOSConversationStore.VariantInfo(variantCount: 3, selectedIndex: 1)
                    : nil
            },
            contentHashProvider: { _, _ in 42 }
        )
        let entry = projection.messageEntry(for: messageId)

        XCTAssertEqual(entry?.variantInfo, NativeTimelineVariantInfo(variantCount: 3, selectedIndex: 1))
        XCTAssertEqual(entry?.hasMultipleVariants, true)
        XCTAssertNotNil(entry?.renderDigest)
    }

    func testNativeStaticTimelineViewportPolicyPublishesBottomButtonAndLODState() {
        let shortContent = NativeStaticTimelineViewportPolicy.state(
            distanceToBottom: 0,
            visibleHeight: 800,
            contentHeight: 400,
            hasMessages: true
        )
        XCTAssertTrue(shortContent.isAtBottom)
        XCTAssertFalse(shortContent.isContentScrollable)
        XCTAssertFalse(shortContent.showScrollToBottom)
        XCTAssertFalse(shortContent.followPaused)
        XCTAssertFalse(shortContent.liveRenderingFarFromBottom)

        let awayFromBottom = NativeStaticTimelineViewportPolicy.state(
            distanceToBottom: 950,
            visibleHeight: 800,
            contentHeight: 2_200,
            hasMessages: true
        )
        XCTAssertFalse(awayFromBottom.isAtBottom)
        XCTAssertTrue(awayFromBottom.isContentScrollable)
        XCTAssertTrue(awayFromBottom.showScrollToBottom)
        XCTAssertFalse(awayFromBottom.followPaused)
        XCTAssertTrue(awayFromBottom.liveRenderingFarFromBottom)

        let userDraggingAwayFromBottom = NativeStaticTimelineViewportPolicy.state(
            distanceToBottom: 950,
            visibleHeight: 800,
            contentHeight: 2_200,
            hasMessages: true,
            userInteracting: true
        )
        XCTAssertTrue(userDraggingAwayFromBottom.followPaused)

        let driverPausedAfterDrag = NativeStaticTimelineViewportPolicy.state(
            distanceToBottom: 950,
            visibleHeight: 800,
            contentHeight: 2_200,
            hasMessages: true,
            driverPausedForUser: true
        )
        XCTAssertTrue(driverPausedAfterDrag.followPaused, "手指抬起后的几何帧不能清掉 native driver 持有的历史浏览暂停。")

        let bottomOfScrollableContent = NativeStaticTimelineViewportPolicy.state(
            distanceToBottom: 0,
            visibleHeight: 800,
            contentHeight: 2_200,
            hasMessages: true
        )
        XCTAssertTrue(bottomOfScrollableContent.isAtBottom)
        XCTAssertTrue(bottomOfScrollableContent.isContentScrollable)
        XCTAssertFalse(bottomOfScrollableContent.showScrollToBottom)
        XCTAssertFalse(bottomOfScrollableContent.followPaused)
        XCTAssertFalse(bottomOfScrollableContent.liveRenderingFarFromBottom)
    }

    func testNativeStaticTimelineRendererMemoryKeepsCompletedStreamingAssistant() {
        let user = UIMessage.companion.user(prompt: "问题")
        let assistant = UIMessage.companion.assistant(prompt: "回答")
        let assistantID = ChatMessageProjector.messageId(for: assistant)

        let remembered = NativeStaticTimelineRendererMemory.nextStreamedMessageIDs(
            previous: [],
            event: .generationCompleted,
            messages: [user, assistant]
        )

        XCTAssertEqual(remembered, Set([assistantID]))
    }

    func testNativeTimelineKeepsLiveTailModelAcrossGenerationCompletion() throws {
        let assistant = UIMessage.companion.assistant(prompt: "流式回答")
        let messageID = ChatMessageProjector.messageId(for: assistant)
        let store = ChatRenderStateStore()
        let streamingRow = ChatMessageRowModel(
            rowId: messageID,
            messageId: messageID,
            message: assistant,
            role: MessageRole.assistant,
            parts: assistant.parts,
            index: 0,
            isLast: true,
            isStreaming: true,
            hasEverStreamed: true,
            canAnimateInsertion: false
        )
        let streamingState = store.stateForRow(
            streamingRow,
            isLiveRenderingFarFromBottom: false
        )
        let streamingModel = try XCTUnwrap(store.liveTailModel(
            for: streamingRow,
            renderState: streamingState,
            isGenerationActive: true
        ))
        let completedRow = ChatMessageRowModel(
            rowId: messageID,
            messageId: messageID,
            message: assistant,
            role: MessageRole.assistant,
            parts: assistant.parts,
            index: 0,
            isLast: true,
            isStreaming: false,
            hasEverStreamed: true,
            canAnimateInsertion: false
        )
        let completedState = store.stateForRow(
            completedRow,
            isLiveRenderingFarFromBottom: false
        )

        let completedModel = store.liveTailModel(
            for: completedRow,
            renderState: completedState,
            isGenerationActive: false
        )

        XCTAssertNotNil(completedModel)
        XCTAssertTrue(streamingModel === completedModel)
    }

    func testNativeStaticTimelineRendererMemoryResetsOnConversationBoundary() {
        let assistant = UIMessage.companion.assistant(prompt: "回答")
        let assistantID = ChatMessageProjector.messageId(for: assistant)

        let reset = NativeStaticTimelineRendererMemory.nextStreamedMessageIDs(
            previous: [assistantID],
            event: .conversationLoaded,
            messages: [assistant]
        )

        XCTAssertTrue(reset.isEmpty)
    }

    func testNativeTimelineProjectionUsesSharedRenderStateStoreFreezeLifecycle() {
        let assistant = UIMessage.companion.assistant(prompt: "正在流式生成")
        let messageId = ChatMessageProjector.messageId(for: assistant)
        let streamedIDs: Set<String> = [messageId]
        let liveStore = ChatRenderStateStore()
        let frozenStore = ChatRenderStateStore()
        frozenStore.freeze(messageID: messageId, latestText: "冻结快照")

        let live = NativeTimelineProjector.build(
            messages: [assistant],
            event: .generationCompleted,
            streamedMessageIDs: streamedIDs,
            renderStateStore: liveStore,
            contentHashProvider: { _, _ in 1 }
        )
        let frozen = NativeTimelineProjector.build(
            messages: [assistant],
            event: .generationCompleted,
            streamedMessageIDs: streamedIDs,
            renderStateStore: frozenStore,
            contentHashProvider: { _, _ in 1 }
        )

        XCTAssertNotEqual(
            live.messageEntry(for: messageId)?.renderDigest,
            frozen.messageEntry(for: messageId)?.renderDigest
        )
    }

    func testNativeTimelineFrozenStreamingTailDoesNotRequestContentHash() {
        let assistant = UIMessage.companion.assistant(prompt: "正在流式生成")
        let messageId = ChatMessageProjector.messageId(for: assistant)
        var contentHashCallCount = 0

        let projection = NativeTimelineProjector.build(
            messages: [assistant],
            event: .assistantStreamDelta,
            isGenerationActive: true,
            viewportState: ChatViewportState(liveRenderingFarFromBottom: true),
            streamedMessageIDs: [messageId],
            renderStateStore: ChatRenderStateStore(),
            contentHashProvider: { _, _ in
                contentHashCallCount += 1
                return 1
            }
        )

        XCTAssertEqual(projection.messageEntry(for: messageId)?.renderState?.rendererMode, .frozen)
        XCTAssertEqual(contentHashCallCount, 0)
    }

    func testNativeTimelineProjectionCarriesRenderStateForNativeUIConsumption() {
        let assistant = UIMessage.companion.assistant(prompt: "正在流式生成")
        let messageId = ChatMessageProjector.messageId(for: assistant)
        let store = ChatRenderStateStore()
        store.freeze(messageID: messageId, latestText: "冻结快照")

        let projection = NativeTimelineProjector.build(
            messages: [assistant],
            event: .generationCompleted,
            viewportState: ChatViewportState(liveRenderingFarFromBottom: true),
            streamedMessageIDs: [messageId],
            renderStateStore: store,
            contentHashProvider: { _, _ in 1 }
        )
        let entry = projection.messageEntry(for: messageId)

        XCTAssertEqual(entry?.renderState?.rendererMode, .frozen)
        XCTAssertEqual(entry?.renderHasEverStreamed, true)
        XCTAssertEqual(entry?.liveMarkdownRenderingEnabled, false)
        XCTAssertEqual(entry?.frozenMarkdownSnapshot, "冻结快照")
    }

    private static func astNodes(for markdown: String) -> [PackedAstNode] {
        guard let data = MarkdownBridge.parse(markdown),
              let reader = PackedAstReader(data: data),
              let root = reader.root() else {
            return []
        }
        return root.children
    }

    private static func firstNode(ofType type: NodeType, in nodes: [PackedAstNode]) -> PackedAstNode? {
        for node in nodes {
            if node.type == type {
                return node
            }
            if let found = firstNode(ofType: type, in: node.children) {
                return found
            }
        }
        return nil
    }

    private static func slice(_ source: String, node: PackedAstNode) -> String {
        guard node.startOffset < node.endOffset else { return "" }
        guard let startIndex = source.utf8.index(
            source.utf8.startIndex,
            offsetBy: node.startOffset,
            limitedBy: source.utf8.endIndex
        ), let endIndex = source.utf8.index(
            source.utf8.startIndex,
            offsetBy: node.endOffset,
            limitedBy: source.utf8.endIndex
        ) else {
            return ""
        }
        return String(source[startIndex..<endIndex])
    }
}

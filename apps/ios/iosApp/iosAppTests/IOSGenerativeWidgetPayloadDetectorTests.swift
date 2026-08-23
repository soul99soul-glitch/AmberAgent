import XCTest
@testable import iosApp

/// 增量 widget 载荷探测器的行为等价与热路径接线守护。
final class IOSGenerativeWidgetPayloadDetectorTests: XCTestCase {

    /// 与全文版 `mayContainWidgetPayload` 的判定等价(一次性全文喂入)。
    func testDetectorMatchesFullScanVerdicts() {
        let positives = [
            "前置说明\n```show-widget\n{}\n```",
            "```WIDGET\ncode\n```",
            "看这个 ```Generative-UI 输出",
            "<!DOCTYPE HTML><body></body>",
            "<div id=\"deck\">x</div>",
            "class='SLIDES'",
            "  { \"renderer\": \"chart\" }",
            "\n\t{\"widget_code\": 1}",
            "{ 前缀 \"HTML\" }",
        ]
        let negatives = [
            "普通长回复,没有任何标记。| 表格 | 也无妨 |",
            "代码块 ```swift\nlet a = 1\n```",
            // JSON needle 存在但首个非空白字符不是 {
            "文字开头 \"renderer\" 出现也不算",
            "",
        ]
        for text in positives {
            XCTAssertTrue(
                IOSGenerativeWidgetParser.mayContainWidgetPayload(text),
                "全文版应为真: \(text.prefix(30))"
            )
            XCTAssertTrue(
                IOSGenerativeWidgetPayloadDetector(text: text).mayContainPayload,
                "增量版应为真: \(text.prefix(30))"
            )
        }
        for text in negatives {
            XCTAssertFalse(
                IOSGenerativeWidgetParser.mayContainWidgetPayload(text),
                "全文版应为假: \(text.prefix(30))"
            )
            XCTAssertFalse(
                IOSGenerativeWidgetPayloadDetector(text: text).mayContainPayload,
                "增量版应为假: \(text.prefix(30))"
            )
        }
    }

    /// needle 被 chunk 边界劈开时,重叠窗必须兜住(跨 delta 检测)。
    func testDetectorLatchesAcrossChunkBoundarySplits() {
        let full = "前文若干。\n```show-widget\n{\"renderer\":\"x\"}\n```"
        // 在 needle 中间逐个位置切一刀,全部应命中。
        for cut in 8..<(full.count - 1) {
            var detector = IOSGenerativeWidgetPayloadDetector()
            let head = String(full.prefix(cut))
            detector.update(with: head)
            detector.update(with: full)
            XCTAssertTrue(detector.mayContainPayload, "cut=\(cut) 应命中")
        }
    }

    /// JSON 组必须受"首个非空白字符是 {"门控,且跨 chunk 依然成立。
    /// 注意探测器契约与视图一致:每次喂入的是**全量累计文本**,不是增量分片。
    func testDetectorJSONGateAcrossChunks() {
        var gated = IOSGenerativeWidgetPayloadDetector()
        gated.update(with: "  \n\t")
        gated.update(with: "  \n\t{ \"rend")
        gated.update(with: "  \n\t{ \"renderer\": 1 }")
        XCTAssertTrue(gated.mayContainPayload)

        var notJSON = IOSGenerativeWidgetPayloadDetector()
        notJSON.update(with: "文本开头")
        notJSON.update(with: "文本开头 \"renderer\" 出现")
        XCTAssertFalse(notJSON.mayContainPayload)
    }

    /// 非追加改写(变体切换/编辑)必须重扫,不能沿用旧进度漏检。
    func testDetectorRescansOnNonAppendRewrite() {
        var detector = IOSGenerativeWidgetPayloadDetector(text: "普通文本,没有标记,足够长以建立进度。")
        XCTAssertFalse(detector.mayContainPayload)
        detector.update(with: "完全不同的内容 <html> 开头改写")
        XCTAssertTrue(detector.mayContainPayload)
    }

    func testDetectorRescansSameLengthRewriteWithStableTail() {
        let tail = String(repeating: "z", count: 24)
        let original = "plain".padding(toLength: 40, withPad: "x", startingAt: 0) + tail
        let rewritten = "<html".padding(toLength: 40, withPad: "y", startingAt: 0) + tail
        XCTAssertEqual(original.utf8.count, rewritten.utf8.count)

        var detector = IOSGenerativeWidgetPayloadDetector(text: original)
        XCTAssertFalse(detector.mayContainPayload)
        detector.update(with: rewritten)
        XCTAssertFalse(detector.mayContainPayload, "普通增量路径不为极窄 final rewrite 付全文校验成本")
        detector.reconcileFinalText(rewritten)

        XCTAssertTrue(detector.mayContainPayload)
    }

    func testDetectorFinalReconciliationKeepsPositiveLatch() {
        var detector = IOSGenerativeWidgetPayloadDetector(text: "```widget\n{}\n```")
        XCTAssertTrue(detector.mayContainPayload)

        detector.reconcileFinalText("普通完成态文本")

        XCTAssertTrue(detector.mayContainPayload)
    }

    /// 热路径接线守护:流式气泡 body 不得再对全文调用 mayContainWidgetPayload,
    /// 必须读增量 latch;列表滚动几何不得回到逐字段 @State(每帧失效整个列表 body)。
    func testHotPathWiringDoesNotRegressToFullScansOrStateGeometry() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let bubble = try String(
            contentsOf: testDirectory.deletingLastPathComponent()
                .appendingPathComponent("iosApp/MessageBubbleView.swift"),
            encoding: .utf8
        )
        XCTAssertFalse(
            bubble.contains("IOSGenerativeWidgetParser.mayContainWidgetPayload(renderedMarkdown)"),
            "流式 body 不得每次求值全文扫描 widget 载荷"
        )
        XCTAssertTrue(bubble.contains("ChatStreamingDetectionBox"))
        XCTAssertTrue(bubble.contains("updateWidgetPayloadLatch"))
        guard let streamingChangeStart = bubble.range(of: ".onChange(of: isStreaming)"),
              let liveRenderingChangeStart = bubble.range(
                of: ".onChange(of: liveRenderingEnabled)",
                range: streamingChangeStart.upperBound..<bubble.endIndex
              ) else {
            return XCTFail("Expected streaming completion and live-rendering change handlers")
        }
        let streamingChange = bubble[streamingChangeStart.lowerBound..<liveRenderingChangeStart.lowerBound]
        XCTAssertTrue(streamingChange.contains("else if !newValue"))
        XCTAssertTrue(
            streamingChange.contains("reconcileFinalWidgetPayloadLatch(with: markdown)"),
            "Final full-message reconciliation must stay on the streaming completion boundary."
        )
        guard let markdownChangeStart = bubble.range(of: ".onChange(of: markdown)"),
              markdownChangeStart.lowerBound < streamingChangeStart.lowerBound else {
            return XCTFail("Expected markdown delta handler before streaming completion handler")
        }
        let markdownChange = bubble[markdownChangeStart.lowerBound..<streamingChangeStart.lowerBound]
        XCTAssertFalse(
            markdownChange.contains("reconcileFinalWidgetPayloadLatch"),
            "Per-delta updates must not rescan the full final message."
        )

        let list = try String(
            contentsOf: testDirectory.deletingLastPathComponent()
                .appendingPathComponent("iosApp/ChatCollectionMessageList.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(list.contains("@State private var runtime = ChatSwiftUIListScrollRuntime()"))
        XCTAssertFalse(
            list.contains("@State private var latestScrollGeometry"),
            "滚动几何不得回到 @State:每个滚动帧都会失效整个列表 body"
        )
        XCTAssertFalse(list.contains("@State private var streamFollowTask"))
    }
}

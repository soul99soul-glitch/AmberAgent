import XCTest
import Shared
@testable import iosApp

final class IOSGenerativeWidgetParserTests: XCTestCase {
    func testWidgetPayloadPreflightSkipsPlainMarkdown() {
        XCTAssertFalse(IOSGenerativeWidgetParser.mayContainWidgetPayload(
            """
            ## 标题
            - 第一条
            - 第二条

            普通 Markdown 正文不应该进入生成式 UI 完整解析路径。
            """
        ))
    }

    func testWidgetPayloadPreflightRecognizesWidgetAndHtmlPayloads() {
        XCTAssertTrue(IOSGenerativeWidgetParser.mayContainWidgetPayload(
            """
            Intro
            ```show-widget
            {"title":"Flow","widget_code":"<svg></svg>"}
            ```
            """
        ))
        XCTAssertTrue(IOSGenerativeWidgetParser.mayContainWidgetPayload(
            #"<!DOCTYPE html><html><body><div id="deck">A</div></body></html>"#
        ))
    }

    func testParsesWidgetBetweenMarkdownText() {
        let segments = IOSGenerativeWidgetParser.parse(
            """
            Before
            ```show-widget
            {"title":"Flow","widget_code":"<svg viewBox=\\"0 0 680 240\\"></svg>"}
            ```
            After
            """,
            streaming: false
        )

        XCTAssertEqual(segments.count, 3)
        if case .text(_, let before) = segments[0] {
            XCTAssertEqual(before, "Before")
        } else {
            XCTFail("Expected leading text")
        }
        guard case .widget(let widget) = segments[1] else {
            return XCTFail("Expected widget")
        }
        XCTAssertEqual(widget.title, "Flow")
        XCTAssertEqual(widget.widgetCode, #"<svg viewBox="0 0 680 240"></svg>"#)
        XCTAssertTrue(widget.complete)
        if case .text(_, let after) = segments[2] {
            XCTAssertEqual(after, "After")
        } else {
            XCTFail("Expected trailing text")
        }
    }

    func testExtractsPartialWidgetCodeWhileStreaming() {
        let segments = IOSGenerativeWidgetParser.parse(
            """
            Intro
            ```show-widget
            {"title":"Draft","widget_code":"<svg><rect
            """,
            streaming: true
        )

        XCTAssertEqual(segments.count, 2)
        guard case .widget(let widget) = segments[1] else {
            return XCTFail("Expected partial widget")
        }
        XCTAssertEqual(widget.title, "Draft")
        XCTAssertEqual(widget.widgetCode, "<svg><rect")
        XCTAssertFalse(widget.complete)
    }

    func testPartialWidgetIDStaysStableWhileStreamingCodeGrows() {
        let first = IOSGenerativeWidgetParser.parse(
            """
            Intro
            ```show-widget
            {"title":"Draft","widget_code":"<svg><rect
            """,
            streaming: true
        )
        let second = IOSGenerativeWidgetParser.parse(
            """
            Intro
            ```show-widget
            {"title":"Draft","widget_code":"<svg><rect width=\\"20\\"
            """,
            streaming: true
        )

        guard case .widget(let firstWidget) = first.last,
              case .widget(let secondWidget) = second.last else {
            return XCTFail("Expected streaming widgets")
        }
        XCTAssertEqual(firstWidget.id, secondWidget.id)
        XCTAssertFalse(firstWidget.complete)
        XCTAssertFalse(secondWidget.complete)
    }

    func testWidgetIDStaysStableFromPartialToComplete() {
        let partial = IOSGenerativeWidgetParser.parse(
            """
            Intro
            ```show-widget
            {"title":"Draft","widget_code":"<svg><rect
            """,
            streaming: true
        )
        let complete = IOSGenerativeWidgetParser.parse(
            """
            Intro
            ```show-widget
            {"title":"Draft","widget_code":"<svg><rect width=\\"20\\"/></svg>"}
            ```
            """,
            streaming: false
        )

        guard case .widget(let partialWidget) = partial.last,
              case .widget(let completeWidget) = complete.dropFirst().first else {
            return XCTFail("Expected partial and complete widgets")
        }
        XCTAssertEqual(partialWidget.id, completeWidget.id)
        XCTAssertFalse(partialWidget.complete)
        XCTAssertTrue(completeWidget.complete)
    }

    func testRendersStructuredChartSpec() {
        let segments = IOSGenerativeWidgetParser.parse(
            """
            ```show-widget
            {"title":"Trend","renderer":"chart","spec":{"type":"line","x":["Mon","Tue"],"series":[{"name":"Count","data":[1,3]}]}}
            ```
            """,
            streaming: false
        )

        guard case .widget(let widget) = segments.single else {
            return XCTFail("Expected widget")
        }
        XCTAssertEqual(widget.renderer, "chart")
        XCTAssertTrue(widget.widgetCode.contains("<svg"))
        XCTAssertTrue(widget.widgetCode.contains("Count"))
    }

    func testChartDoesNotRenderPointsPastItsLabels() {
        let svg = IOSGenerativeWidgetRenderer().render(
            renderer: "chart",
            specJson: #"{"type":"line","x":["Only"],"series":[{"name":"Count","data":[1,2,3]}]}"#
        )

        XCTAssertNotNil(svg)
        XCTAssertFalse(svg?.contains("1198.0") ?? true)
    }

    func testSingleFlowNodeIsHorizontallyCentered() {
        let svg = IOSGenerativeWidgetRenderer().render(
            renderer: "diagram",
            specJson: #"{"type":"flow","nodes":[{"label":"Only"}]}"#
        )

        XCTAssertTrue(svg?.contains(#"x="254.0""#) ?? false)
    }

    func testNormalizesWrappedSlidesSpec() {
        let segments = IOSGenerativeWidgetParser.parse(
            """
            ```show-widget
            {"title":"Deck","renderer":"slides","spec":{"schemaVersion":2,"style":"magazine","accent":"#123456","fontPack":"source-han-serif-sc-regular","slides":[{"layout":"cover","title":"A","content":["B"]}]}}
            ```
            """,
            streaming: false
        )

        guard case .widget(let widget) = segments.single else {
            return XCTFail("Expected widget")
        }
        XCTAssertEqual(widget.renderer, "slides")
        XCTAssertTrue(widget.specJson?.contains(#""fontPack":"source-han-serif-sc-regular""#) ?? false)
        XCTAssertTrue(widget.widgetCode.contains("A"))
    }

    func testParsesFullHtmlRendererAndLegacyAlias() {
        let html = #"<!DOCTYPE html><html><body><div id="deck"><section class="slide">A</section></div></body></html>"#
        let content = """
        ```show-widget
        {"title":"Live Deck","renderer":"guizang_html","widget_code":"<svg viewBox=\\"0 0 20 20\\"><text>G</text></svg>","spec":{"html":\(jsonStringLiteralForTest(html))}}
        ```
        """

        let segments = IOSGenerativeWidgetParser.parse(content, streaming: false)
        guard case .widget(let widget) = segments.single else {
            return XCTFail("Expected widget")
        }
        XCTAssertEqual(widget.renderer, "full_html")
        XCTAssertTrue(widget.specJson?.contains(#"<div id=\"deck\">"#) ?? false)
    }

    func testRejectsInvalidFullHtmlInsteadOfAcceptingPreviewCover() {
        let content = """
        ```show-widget
        {"title":"Broken","renderer":"full_html","spec":{"html":"<html><body>no slides</body></html>"}}
        ```
        """

        let segments = IOSGenerativeWidgetParser.parse(content, streaming: false)

        XCTAssertFalse(segments.contains { segment in
            if case .widget = segment { return true }
            return false
        })
    }

    func testActionsMatchAndroidLimitsAndSafetyRules() {
        let widgetJson = jsonString([
            "title": "Actions",
            "widget_code": #"<svg viewBox="0 0 20 20"><text>A</text></svg>"#,
            "actions": [
                ["id": "do one!", "label": " 继续   优化 ", "instruction": " 生成   三条 摘要 "],
                ["id": "two", "label": "对比", "instruction": "对比两个版本"],
                ["id": "blocked", "label": "链接", "instruction": "打开链接 https://example.com"],
                ["id": "long-label", "label": String(repeating: "长", count: 21), "instruction": "忽略"],
                ["id": "long-instruction", "label": "过长", "instruction": String(repeating: "x", count: 241)],
                ["id": "three", "label": "总结", "instruction": "总结关键点"],
                ["id": "four", "label": "第四", "instruction": "这条会被上限截断"],
            ],
        ])!
        let segments = IOSGenerativeWidgetParser.parse(
            """
            ```show-widget
            \(widgetJson)
            ```
            """,
            streaming: false
        )

        guard case .widget(let widget) = segments.single else {
            return XCTFail("Expected widget")
        }
        XCTAssertEqual(widget.actions.map(\.label), ["继续 优化", "对比", "总结"])
        XCTAssertEqual(widget.actions.map(\.instruction), ["生成 三条 摘要", "对比两个版本", "总结关键点"])
        XCTAssertEqual(widget.actions.first?.id, "do-one")
    }

    func testFullHtmlRuntimeRewritesAllowedRemoteResourcesAndBlocksRawHttps() {
        let html = """
        <!DOCTYPE html>
        <html>
        <head>
          <link rel="stylesheet" href="https://cdn.example.com/deck.css">
          <style>
            .hero { background-image: url(https://cdn.example.com/hero.png); }
            .tracker { background-image: url(https://tracker.example.com/pixel); }
          </style>
        </head>
        <body>
          <div id="deck">
            <section class="slide">
              <img src="https://cdn.example.com/photo.png">
              <img src="https://tracker.example.com/pixel">
            </section>
          </div>
        </body>
        </html>
        """
        let prepared = IOSGuizangHtmlDeckValidator.prepareRuntimeHtml(
            IOSGuizangHtmlDeckValidator.DeckSpec(
                html: html,
                allowRemoteImages: true,
                allowRemoteFonts: true
            )
        )

        XCTAssertTrue(prepared.contains("amber-full-html-resource://image/"))
        XCTAssertTrue(prepared.contains("amber-full-html-resource://stylesheet/"))
        XCTAssertFalse(prepared.contains("https://cdn.example.com/photo.png"))
        XCTAssertFalse(prepared.contains("https://cdn.example.com/deck.css"))
        XCTAssertFalse(prepared.contains("https://tracker.example.com/pixel"))
        XCTAssertFalse(prepared.contains("img-src https:"))

        let imageBlocked = IOSGuizangHtmlDeckValidator.prepareRuntimeHtml(
            IOSGuizangHtmlDeckValidator.DeckSpec(
                html: html,
                allowRemoteImages: false,
                allowRemoteFonts: true
            )
        )
        XCTAssertFalse(imageBlocked.contains("amber-full-html-resource://image/"))
    }

    func testFullHtmlRuntimeAcceptsSharedProtocolAssetURLs() {
        let html = """
        <!DOCTYPE html><html><body>
        <div id="deck"><section class="slide">A</section></div>
        <script src="https://amberagent.local/full-html/lucide.min.js"></script>
        <script type="module">await import('https://amberagent.local/full-html/motion.min.js')</script>
        </body></html>
        """

        let prepared = IOSGuizangHtmlDeckValidator.prepareRuntimeHtml(html)

        XCTAssertTrue(IOSGuizangHtmlDeckValidator.validateHtml(html).valid)
        XCTAssertTrue(prepared.contains(IOSGuizangHtmlDeckValidator.localLucideURL))
        XCTAssertTrue(prepared.contains(IOSGuizangHtmlDeckValidator.localMotionURL))
        XCTAssertFalse(prepared.contains("https://amberagent.local/full-html/"))
    }

    func testSanitizerRemovesDangerousContent() {
        let result = IOSGenerativeWidgetSanitizer.sanitize(
            """
            <div onclick="alert(1)" style="position:fixed;background:url(https://example.com/bg.png)">
              <script>alert(1)</script>
              <iframe src="https://example.com"></iframe>
              <img src="https://example.com/chart.png">
              <a href="javascript:alert(1)">open</a>
              <svg onload="alert(1)"></svg>
            </div>
            """
        )

        XCTAssertEqual(result.status, .ready)
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("script"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("onclick"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("onload"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("iframe"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("javascript:"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("https://example.com/chart.png"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("position:fixed"))
    }

    func testGenerativeUiTerminalPolicyRejectsMissingRequiredWidget() {
        let baseline = [message(role: .user, text: "画一个流程图")]
        let messages = baseline + [message(role: .assistant, text: "这里是文字版流程。")]

        let issue = IOSGenerativeUiRequestPolicy.widgetIssue(
            in: messages,
            afterDisplayMessageCount: baseline.count,
            requirement: IOSGenerativeUiRequirement(
                required: true,
                expectSlides: false,
                expectFullHtmlDeck: false
            )
        )

        XCTAssertEqual(issue, "missing required complete show-widget")
    }

    func testGenerativeUiTerminalPolicyAcceptsCompleteStreamedSvgWidget() {
        let baseline = [message(role: .user, text: "画一个流程图")]
        let messages = baseline + [message(
            role: .assistant,
            text: """
            ```show-widget
            {"title":"Flow","widget_code":"<svg viewBox=\\"0 0 680 180\\"><rect x=\\"24\\" y=\\"24\\" width=\\"632\\" height=\\"132\\"/></svg>"}
            ```
            """
        )]

        XCTAssertNil(IOSGenerativeUiRequestPolicy.widgetIssue(
            in: messages,
            afterDisplayMessageCount: baseline.count,
            requirement: IOSGenerativeUiRequirement(
                required: true,
                expectSlides: false,
                expectFullHtmlDeck: false
            )
        ))
    }

    func testGenerativeUiTerminalPolicyDoesNotAcceptSvgForDeckRequirement() {
        let baseline = [message(role: .user, text: "做一份 PPT")]
        let messages = baseline + [message(
            role: .assistant,
            text: """
            ```show-widget
            {"title":"Deck","widget_code":"<svg viewBox=\\"0 0 680 180\\"><text x=\\"24\\" y=\\"48\\">Deck</text></svg>"}
            ```
            """
        )]

        let issue = IOSGenerativeUiRequestPolicy.widgetIssue(
            in: messages,
            afterDisplayMessageCount: baseline.count,
            requirement: IOSGenerativeUiRequirement(
                required: true,
                expectSlides: true,
                expectFullHtmlDeck: true
            )
        )

        XCTAssertEqual(issue, "expected renderer \"full_html\"")
    }

    func testSVGExportUsesSanitizedSVGFragmentAndSafeFilename() {
        let widget = IOSGenerativeWidget(
            id: "flow",
            title: "流程图 / v1",
            widgetCode: "<svg><text>raw</text></svg>",
            complete: true
        )
        let sanitized = IOSSanitizedGenerativeWidget(
            status: .ready,
            html: "<div class=\"widget\"><svg viewBox=\"0 0 10 10\"><rect/></svg></div>"
        )

        let artifact = IOSGenerativeWidgetSVGExport.artifact(widget: widget, sanitized: sanitized)

        XCTAssertEqual(artifact?.svg, "<svg viewBox=\"0 0 10 10\"><rect/></svg>")
        XCTAssertEqual(artifact?.filename, "流程图 - v1.svg")
    }

    func testSVGExportOnlyAllowsCompleteReadySVGWidgets() {
        let svg = "<svg><rect/></svg>"
        let completeWidget = IOSGenerativeWidget(id: "complete", title: nil, widgetCode: svg, complete: true)
        let partialWidget = IOSGenerativeWidget(id: "partial", title: nil, widgetCode: svg, complete: false)

        XCTAssertNotNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: completeWidget,
            sanitized: IOSSanitizedGenerativeWidget(status: .ready, html: svg)
        ))
        XCTAssertNil(
            IOSGenerativeWidgetSVGExport.artifact(
                widget: completeWidget,
                sanitized: IOSSanitizedGenerativeWidget(status: .ready, html: "<div>wrapped</div>")
            ),
            "净化内容未保留 SVG 时不应回退原始 SVG"
        )
        XCTAssertNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: partialWidget,
            sanitized: IOSSanitizedGenerativeWidget(status: .ready, html: svg)
        ))
        XCTAssertNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: completeWidget,
            sanitized: IOSSanitizedGenerativeWidget(status: .unsafe)
        ))
        XCTAssertNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: IOSGenerativeWidget(id: "html", title: nil, widgetCode: "<div>not svg</div>", complete: true),
            sanitized: IOSSanitizedGenerativeWidget(status: .ready, html: "<div>not svg</div>")
        ))
    }

    func testSVGExportExcludesFullHtmlAndSlidesRenderers() {
        let svg = "<svg><rect/></svg>"
        let sanitized = IOSSanitizedGenerativeWidget(status: .ready, html: svg)

        XCTAssertNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: IOSGenerativeWidget(
                id: "full-html",
                title: "Deck cover",
                widgetCode: svg,
                complete: true,
                renderer: IOSGuizangHtmlDeckValidator.renderer
            ),
            sanitized: sanitized
        ))
        XCTAssertNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: IOSGenerativeWidget(
                id: "slides",
                title: "Deck preview",
                widgetCode: svg,
                complete: true,
                renderer: "slides"
            ),
            sanitized: sanitized
        ))
    }

    func testSVGExportFilenamePreservesCaseInsensitiveExtensionAndCleansControlCharacters() {
        XCTAssertEqual(IOSGenerativeWidgetSVGExport.filename(for: "Title.SVG"), "Title.SVG")
        XCTAssertEqual(IOSGenerativeWidgetSVGExport.filename(for: "设计.SVG"), "设计.SVG")
        XCTAssertEqual(IOSGenerativeWidgetSVGExport.filename(for: "flow\nchart\u{0007}"), "flowchart.svg")
        XCTAssertEqual(IOSGenerativeWidgetSVGExport.filename(for: "\n\u{0000}\t"), "visualization.svg")
    }

    private func message(role: MessageRole, text: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: role,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func jsonStringLiteralForTest(_ value: String) -> String {
        let data = try! JSONSerialization.data(withJSONObject: [value])
        let array = String(data: data, encoding: .utf8)!
        return String(array.dropFirst().dropLast())
    }
}

private extension Array {
    var single: Element? {
        count == 1 ? self[0] : nil
    }
}

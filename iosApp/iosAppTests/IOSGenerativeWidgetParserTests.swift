import XCTest
@testable import iosApp

final class IOSGenerativeWidgetParserTests: XCTestCase {
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

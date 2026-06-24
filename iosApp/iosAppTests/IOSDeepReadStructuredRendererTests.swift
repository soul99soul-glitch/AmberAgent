import XCTest
@testable import iosApp

final class IOSDeepReadStructuredRendererTests: XCTestCase {
    func testDecodeAndRenderStructuredEditorialCards() throws {
        let json = """
        {
          "topic_type": "event",
          "summary": "**摘要**正文。",
          "key_entities": ["甲", "乙"],
          "timeline": [{"date": "2024", "event": "发生了 *某事*", "is_highlight": true}],
          "core_points": [{"point": "核心要点", "supporting": "支撑材料"}],
          "diagram": {
            "type": "causal_chain", "title": "因果",
            "nodes": [{"id": "a", "label": "原因"}, {"id": "b", "label": "结果"}],
            "edges": [{"from": "a", "to": "b", "label": "导致"}]
          },
          "analysis": {
            "core_dispute": "分歧点",
            "perspectives": [{"viewpoint": "观点A", "holder": "甲方"}],
            "implications": "影响分析"
          },
          "extended_reading": [{"title": "延伸阅读", "url": "https://example.com", "source": "来源站"}]
        }
        """
        let output = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))

        // Codable + snake_case mapping
        XCTAssertEqual(output.topicType, "event")
        XCTAssertEqual(output.timeline.count, 1)
        XCTAssertTrue(output.timeline.first?.isHighlight ?? false)
        XCTAssertEqual(output.corePoints.first?.point, "核心要点")
        XCTAssertEqual(output.diagram?.nodes.count, 2)
        XCTAssertEqual(output.analysis.perspectives.first?.holder, "甲方")
        XCTAssertTrue(output.hasStructuredBody)

        let html = IOSDeepReadEditorialRenderer.renderHTML(
            IOSDeepReadEditorialRenderer.Input(title: "标题", markdown: "", structured: output)
        )

        // Each Android-parity card is present
        XCTAssertTrue(html.contains(#"<p class="section">时间轴</p>"#))
        XCTAssertTrue(html.contains(#"class="timeline-marker""#))
        XCTAssertTrue(html.contains(#"<p class="section">关键脉络</p>"#))
        XCTAssertTrue(html.contains(#"class="core-point""#))
        XCTAssertTrue(html.contains(#"class="diagram-block""#))
        XCTAssertTrue(html.contains(#"class="diagram-step-index""#))
        XCTAssertTrue(html.contains(#"class="perspective""#))
        XCTAssertTrue(html.contains(#"class="holder""#))
        XCTAssertTrue(html.contains(#"class="reading-link""#))
        XCTAssertTrue(html.contains("https://example.com"))

        // Markdown inside structured fields is rendered (same parser as the flat reader)
        XCTAssertTrue(html.contains("<strong>摘要</strong>"))
        XCTAssertTrue(html.contains("<em>某事</em>"))
    }

    func testEmptyStructuredFallsBackToFlatMarkdown() {
        let empty = IOSDeepReadOutput()
        XCTAssertFalse(empty.hasStructuredBody)

        let html = IOSDeepReadEditorialRenderer.renderHTML(
            IOSDeepReadEditorialRenderer.Input(title: "T", markdown: "## 摘要\n正文。", structured: empty)
        )
        // Degrades to the magazine markdown body, no structured sections
        XCTAssertTrue(html.contains(#"class="markdown-body""#))
        XCTAssertFalse(html.contains(#"<p class="section">时间轴</p>"#))
    }

    func testPartialJSONMergeDoesNotThrow() throws {
        // Stage-by-stage JSON may carry only some keys; decoding must be tolerant.
        let overviewOnly = #"{"topic_type":"opinion","summary":"只有摘要"}"#
        let output = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(overviewOnly.utf8))
        XCTAssertEqual(output.summary, "只有摘要")
        XCTAssertTrue(output.timeline.isEmpty)
        XCTAssertTrue(output.corePoints.isEmpty)
        XCTAssertNil(output.diagram)
    }
}

import XCTest
import Markdown
@testable import SwiftStreamingMarkdown
@testable import iosApp

/// 「部分粗体不生效」排查探针：对两条渲染链路（流式 swift-markdown /
/// 完成态原生 pulldown-cmark AST）批量断言常见粗体形态都应解析为 strong。
/// 任何一条失败即定位到具体形态；全绿则需要用户提供真实不生效样例。
final class ChatMarkdownCJKEmphasisTests: XCTestCase {

    // MARK: - 流式链路（swift-markdown，修复 flag 开启）

    func testStreamingBoldVariantsAllParseAsStrong() async {
        let variants: [(name: String, markdown: String)] = [
            ("空白分隔对照组", "这是 **重点** 内容"),
            ("CJK 紧邻", "这是**重点**内容"),
            ("段首紧邻", "**重点**内容"),
            ("内部全角冒号", "结论：**很重要**，请记住。"),
            ("逗号收尾", "**重点**，内容继续"),
            ("全角括号包裹", "**（重点）**说明"),
            ("跨 soft break", "这是**重\n点**内容"),
            ("列表项内", "- **重点**内容"),
            ("表格 cell 内", "| 列 |\n| --- |\n| **重点**内容 |"),
            ("紧邻行内代码", "**粗**`code`尾部"),
            ("下划线形式", "这是__重点__内容"),
            ("三星粗斜体", "这是***重点***内容"),
            ("弯引号包裹", "掀起了**“大宋风雅”热潮**："),
            ("直引号包裹", "掀起了**\"大宋风雅\"热潮**："),
        ]
        let parser = MarkdownParserImpl()
        for variant in variants {
            let result = await parser.parse(
                text: variant.markdown,
                option: MarkdownParseOption(
                    speculativeRewrite: false,
                    repairsRejectedStrongEmphasis: true
                )
            )
            XCTAssertTrue(
                containsStrong(result.document),
                "流式链路粗体形态失败：\(variant.name) —— \(variant.markdown)"
            )
            let plain = collectedText(result.document)
            XCTAssertFalse(
                plain.contains("**") || plain.contains("__"),
                "流式链路仍残留字面定界符：\(variant.name) —— \(plain)"
            )
        }
    }

    func testNativeQuotedTitleHasNoLiteralDelimitersAfterRender() {
        let samples = [
            "掀起了**“大宋风雅”热潮**：",
            "掀起了**\"大宋风雅\"热潮**：",
            "掀起了**「大宋风雅」热潮**：",
        ]
        for sample in samples {
            let nodes = nativeTextNodeContents(sample)
            // pulldown-cmark 会把未配对的 ** 拆成四个单独的 `*` Text 节点；
            // 逐节点修看不到成对定界符。完成态渲染必须先拼接相邻文本再修。
            XCTAssertTrue(
                nodes.contains(where: { $0 == "*" }),
                "预期原生 AST 把 ** 拆成单星号节点：\(nodes) —— \(sample)"
            )
            let repaired = AmberMarkdownView.repairRejectedStrong(inAdjacentTextFragments: nodes)
            let repairedText = String(repaired.characters)
            XCTAssertFalse(
                repairedText.contains("**") || repairedText.contains("*"),
                "拼接后再修仍有字面星号：nodes=\(nodes) text=\(repairedText) sample=\(sample)"
            )
            XCTAssertTrue(
                repaired.runs.contains { $0.inlinePresentationIntent?.contains(.stronglyEmphasized) == true },
                "拼接后再修没有 strong：nodes=\(nodes) sample=\(sample)"
            )
        }
    }

    func testQuotedTitleStrongLeavesNoLiteralDelimiters() async {
        let samples = [
            "掀起了**“大宋风雅”热潮**：",
            "掀起了**\"大宋风雅\"热潮**：",
            "掀起了**「大宋风雅」热潮**：",
        ]
        let parser = MarkdownParserImpl()
        for sample in samples {
            let result = await parser.parse(
                text: sample,
                option: MarkdownParseOption(
                    speculativeRewrite: false,
                    repairsRejectedStrongEmphasis: true
                )
            )
            XCTAssertTrue(containsStrong(result.document), "未解析成粗体：\(sample)")
            XCTAssertFalse(
                collectedText(result.document).contains("**"),
                "粗体修完仍有字面 **：\(collectedText(result.document)) —— \(sample)"
            )
        }
    }

    /// vendor 默认值回归锁：flag 关闭时保持历史行为（被 flanking 拒绝的粗体
    /// 仍是字面定界符），确认修复是显式 opt-in 而不是偷改共享默认。
    func testStreamingRepairDefaultsOffPreservesHistoricalBehavior() async {
        let parser = MarkdownParserImpl()
        let result = await parser.parse(
            text: "**（重点）**说明",
            option: MarkdownParseOption(speculativeRewrite: false)
        )
        XCTAssertFalse(
            containsStrong(result.document),
            "默认选项必须保持历史行为：flanking 拒绝的粗体不修复"
        )
    }

    /// 流式未闭合粗体靠 speculative rewrite 兜底（既有行为的回归锁）。
    func testStreamingUnclosedBoldSpeculativelyRewritesToStrong() async {
        let parser = MarkdownParserImpl()
        let result = await parser.parse(
            text: "这是**重点",
            option: MarkdownParseOption(speculativeRewrite: true)
        )
        XCTAssertTrue(
            containsStrong(result.document),
            "未闭合粗体应由 PartialStrongMarkupPostParsingRewriter 补全"
        )
    }

    // MARK: - 完成态链路（原生 pulldown-cmark AST + 渲染层修复）

    /// pulldown-cmark 原生可解析的形态：AST 必须直接含 strong 节点。
    func testNativeAstBoldVariantsAllContainStrong() {
        let variants: [(name: String, markdown: String)] = [
            ("空白分隔对照组", "这是 **重点** 内容"),
            ("CJK 紧邻", "这是**重点**内容"),
            ("段首紧邻", "**重点**内容"),
            ("内部全角冒号", "结论：**很重要**，请记住。"),
            ("逗号收尾", "**重点**，内容继续"),
            ("跨 soft break", "这是**重\n点**内容"),
            ("列表项内", "- **重点**内容"),
            ("表格 cell 内", "| 列 |\n| --- |\n| **重点**内容 |"),
            ("紧邻行内代码", "**粗**`code`尾部"),
            ("三星粗斜体", "这是***重点***内容"),
        ]
        for variant in variants {
            XCTAssertTrue(
                nativeAstContainsStrong(variant.markdown),
                "原生 AST 粗体形态失败：\(variant.name) —— \(variant.markdown)"
            )
        }
    }

    /// flanking 拒绝的形态：AST 里只剩字面定界符（这是解析器事实，不在测试里
    /// 否认它），渲染层 `repairRejectedStrong` 必须把它们修复成 strong 意图。
    func testNativeRenderRepairFixesRejectedStrongForms() {
        let variants: [(name: String, textNodeContent: String, expectedBold: String)] = [
            ("全角括号包裹", "**（重点）**说明", "（重点）"),
            ("下划线形式 CJK 紧邻", "这是__重点__内容", "重点"),
            ("弯引号包裹", "掀起了**“大宋风雅”热潮**：", "“大宋风雅”热潮"),
            ("直引号包裹", "掀起了**\"大宋风雅\"热潮**：", "\"大宋风雅\"热潮"),
        ]
        for variant in variants {
            let attr = AmberMarkdownView.repairRejectedStrong(in: variant.textNodeContent)
            var bolded = ""
            for run in attr.runs {
                if run.inlinePresentationIntent?.contains(.stronglyEmphasized) == true {
                    bolded += String(attr[run.range].characters)
                }
            }
            XCTAssertEqual(
                bolded,
                variant.expectedBold,
                "原生渲染层粗体修复失败：\(variant.name) —— \(variant.textNodeContent)"
            )
        }
    }

    /// 渲染修复的自限性：没有字面成对定界符的文本必须原样通过，
    /// `** 后跟空白`这类非粗体语义不得误伤。
    func testNativeRenderRepairLeavesNonBoldTextUntouched() {
        let samples = ["普通文本没有星号", "用 ** 来强调的说明", "a ** b ** c"]
        for sample in samples {
            let attr = AmberMarkdownView.repairRejectedStrong(in: sample)
            XCTAssertEqual(String(attr.characters), sample)
            for run in attr.runs {
                XCTAssertNil(run.inlinePresentationIntent, "误伤：\(sample)")
            }
        }
    }

    // MARK: - Helpers

    private func collectedText(_ node: Markup) -> String {
        if let text = node as? Text { return text.string }
        return node.children.map(collectedText).joined()
    }

    private func containsStrong(_ node: Markup) -> Bool {
        if node is Strong { return true }
        for child in node.children where containsStrong(child) {
            return true
        }
        return false
    }

    private func nativeTextNodeContents(_ markdown: String) -> [String] {
        guard let data = MarkdownBridge.parse(markdown),
              let reader = PackedAstReader(data: data),
              let root = reader.root() else {
            XCTFail("原生 AST 解析失败")
            return []
        }
        var texts: [String] = []
        func walk(_ node: PackedAstNode) {
            if node.type == .text {
                texts.append(sliceSource(markdown, node: node))
            }
            for child in node.children { walk(child) }
        }
        walk(root)
        return texts
    }

    private func sliceSource(_ source: String, node: PackedAstNode) -> String {
        let utf8 = Array(source.utf8)
        let start = Int(node.startOffset)
        let end = Int(node.endOffset)
        guard start >= 0, end >= start, end <= utf8.count else { return "<bad \(start)..\(end)>" }
        return String(decoding: utf8[start..<end], as: UTF8.self)
    }

    private func nativeAstContainsStrong(_ markdown: String) -> Bool {
        guard let data = MarkdownBridge.parse(markdown),
              let reader = PackedAstReader(data: data),
              let root = reader.root() else {
            XCTFail("原生 AST 解析失败")
            return false
        }
        return containsStrong(root)
    }

    private func containsStrong(_ node: PackedAstNode) -> Bool {
        if node.type == .strong { return true }
        for child in node.children where containsStrong(child) {
            return true
        }
        return false
    }
}

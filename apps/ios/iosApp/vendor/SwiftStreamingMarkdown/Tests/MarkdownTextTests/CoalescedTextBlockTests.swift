//
//  Vendored addition (AmberAgent). Not part of upstream SwiftStreamingMarkdown.
//

import Markdown
@testable import SwiftStreamingMarkdown
import UIKit
import XCTest

/// 判据（canary）：合并相邻纯文本块之后，一条长回复的文本视图数必须从「段落数」
/// 降到 1，且流式增长必须仍然走 `ParagraphUIView` 的 append 快路径——包括跨段落
/// 边界的那一次增长。后者是整个设计的命门：如果新段落到来会让前缀属性变化，
/// 快路径就会退化成全量替换，合并反而更慢。
///
/// 断言的是**比值/结构**而不是绝对耗时，避免机器差异导致门禁不稳。
@MainActor
final class CoalescedTextBlockTests: XCTestCase {

  private let parser: MarkdownParser = MarkdownParserImpl()

  private func config(coalescing: Bool) -> MarkdownRenderConfig {
    MarkdownRenderConfig(shouldAnimateText: true)
      .withParagraphLineSpacing(value: 4)
      .withBlockSpacing(value: 8)
      .withCoalescesAdjacentTextBlocks(value: coalescing)
  }

  private func longProse(paragraphs: Int) -> String {
    (0..<paragraphs)
      .map { "第 \($0) 段：这是一段用于测量的中文正文，长度足够触发折行。" }
      .joined(separator: "\n\n")
  }

  private func renderables(
    _ text: String,
    coalescing: Bool
  ) async -> [MarkdownRenderable] {
    let config = self.config(coalescing: coalescing)
    let parsed = await parser.parse(text: text, option: MarkdownParseOption(speculativeRewrite: true))
    return await RenderableDocument(document: parsed.document, config: config).renderables
  }

  // MARK: - 视图数

  func testCoalescingCollapsesSixtyParagraphsToOneTextBlock() async {
    let text = longProse(paragraphs: 60)

    let before = await renderables(text, coalescing: false)
    let after = await renderables(text, coalescing: true)

    XCTAssertEqual(before.count, 60, "基线：每段一个 renderable（即一个 ParagraphUIView）")
    XCTAssertEqual(after.count, 1, "合并后整篇纯正文只剩一个文本块")
    XCTAssertEqual(after.first?.id, "coalesced-0", "合并块按 run 序号取 id，不跟随首段 id")
  }

  func testNonTextBlocksTerminateTheRun() async {
    let text = """
    第一段。

    第二段。

    ```swift
    let x = 1
    ```

    第三段。

    第四段。
    """

    let after = await renderables(text, coalescing: true)

    XCTAssertEqual(after.count, 3, "代码块打断合并：文本块 + 代码块 + 文本块")
    guard case .coalescedText = after[0] else { return XCTFail("首块应为合并文本块") }
    guard case .codeBlock = after[1] else { return XCTFail("中间块应原样保留为代码块") }
    guard case .coalescedText = after[2] else { return XCTFail("尾块应为合并文本块") }
  }

  func testSingleParagraphIsStillCoalescedSoIdentityStaysStableAsItGrows() async {
    // 单段也走 .coalescedText：否则「1 段 → 2 段」时首段会从 .paragraph 变成
    // .coalescedText，SwiftUI 视图身份翻转，已显示的文字会从 alpha 0 重新淡入。
    let one = await renderables("只有一段。", coalescing: true)
    XCTAssertEqual(one.count, 1)
    guard case .coalescedText = one[0] else { return XCTFail("单段也应产出合并文本块") }

    let two = await renderables("只有一段。\n\n又来一段。", coalescing: true)
    XCTAssertEqual(two.count, 1)
    guard case .coalescedText(let twoID, _) = two[0] else { return XCTFail("两段应合并") }
    XCTAssertEqual(twoID, one[0].id, "增长到两段时块 id 不变")
  }

  // MARK: - append 快路径（命门）

  /// 逐段增长的全过程都必须是「纯追加」：任何一步只要前缀的字符或属性发生变化，
  /// `appendedTailRange` 就会返回 nil，`setParagraphContents` 退回全量替换，
  /// 合并的收益立刻归零。
  func testGrowingAcrossParagraphBoundariesStaysAppendOnly() async {
    var previous: NSMutableAttributedString?
    var appendCount = 0

    for paragraphCount in 1...12 {
      let after = await renderables(longProse(paragraphs: paragraphCount), coalescing: true)
      XCTAssertEqual(after.count, 1, "\(paragraphCount) 段仍应合并成一个块")
      guard case .coalescedText(_, let content) = after[0] else {
        return XCTFail("\(paragraphCount) 段应产出合并文本块")
      }
      if let previous {
        let range = previous.appendedTailRange(toBecome: content)
        XCTAssertNotNil(
          range,
          "从 \(paragraphCount - 1) 段增长到 \(paragraphCount) 段不是纯追加："
            + previous.appendedTailRangeMissReasonForTesting(toBecome: content)
        )
        if range != nil { appendCount += 1 }
      }
      previous = content
    }

    XCTAssertEqual(appendCount, 11, "11 次跨段增长应全部命中 append 快路径")
  }

  /// 合并块把行距烘进 paragraphStyle，视图侧必须传 lineSpacing: nil；
  /// 这里锁住烘焙结果，防止以后有人把行距改回视图侧而悄悄破坏快路径。
  func testMergedContentBakesLineSpacingAndBlockSpacing() async {
    let after = await renderables(longProse(paragraphs: 3), coalescing: true)
    guard case .coalescedText(_, let content) = after[0] else {
      return XCTFail("应产出合并文本块")
    }

    // 按段落起点取样，而不是数属性 run：值相等的相邻 run 会被
    // NSAttributedString 合并（第 2、3 段的 style 相等，只会剩一个 run）。
    var paragraphStarts: [Int] = [0]
    let string = content.string as NSString
    string.enumerateSubstrings(in: NSRange(location: 0, length: string.length), options: .byLines) { _, range, _, _ in
      let next = range.location + range.length + 1
      if next < string.length { paragraphStarts.append(next) }
    }
    let styles = paragraphStarts.compactMap {
      content.attribute(.paragraphStyle, at: $0, effectiveRange: nil) as? NSParagraphStyle
    }

    XCTAssertEqual(paragraphStarts.count, 3, "三段正文应有三个段落起点")
    XCTAssertEqual(styles.count, 3, "每个段落起点都必须带 paragraphStyle")
    XCTAssertTrue(styles.allSatisfy { $0.lineSpacing == 4 }, "行距必须烘进属性串")
    XCTAssertEqual(styles[0].paragraphSpacingBefore, 0, "首段前不加块间距")
    XCTAssertEqual(styles[1].paragraphSpacingBefore, 8, "段间距用 paragraphSpacingBefore 表达")
    XCTAssertEqual(styles[2].paragraphSpacingBefore, 8)
    XCTAssertTrue(
      styles.allSatisfy { $0.paragraphSpacing == 0 },
      "不得用 paragraphSpacing：那会在新段落到来时回改上一段属性，打断纯追加"
    )
  }

  // MARK: - 开关默认关闭

  func testDefaultConfigKeepsUpstreamOneViewPerBlock() async {
    XCTAssertFalse(
      MarkdownRenderConfig.default.coalescesAdjacentTextBlocks,
      "新开关必须默认关闭，默认路径行为与上游一致"
    )
    let after = await renderables(longProse(paragraphs: 5), coalescing: false)
    XCTAssertEqual(after.count, 5)
    guard case .paragraph = after[0] else { return XCTFail("关闭时应保持 .paragraph") }
  }

  /// 占位（未解析）文档必须和解析后的文档产出同一种 case 和同一个 id，
  /// 否则解析结果落地的瞬间视图身份翻转，已显示文字会重新淡入。
  func testPlaceholderAndParsedDocumentAgreeOnIdentity() async {
    let text = longProse(paragraphs: 6)
    let placeholder = RenderableDocument(
      plainText: text,
      id: "0",
      config: config(coalescing: true),
      splittingParagraphsOnBlankLines: true
    ).renderables
    let parsed = await renderables(text, coalescing: true)

    XCTAssertEqual(placeholder.count, 1)
    XCTAssertEqual(parsed.count, 1)
    guard case .coalescedText = placeholder[0] else { return XCTFail("占位也应合并") }
    XCTAssertEqual(placeholder[0].id, parsed[0].id, "占位与解析结果 id 必须一致")
  }

  /// 段内换行（软/硬换行）不是段落边界，不能吃到块间距。
  /// TextKit 把每一个 `\n` 都当段落分隔符，`paragraphSpacingBefore` 若泼在整段
  /// 范围上，段内换行处会再次生效——同一段文字会因为它前面有没有别的段落而
  /// 改变自身行距。
  func testInnerLineBreaksDoNotGetBlockSpacing() async {
    let after = await renderables("第一段。\n\n第二行A\n第二行B", coalescing: true)
    guard case .coalescedText(_, let content) = after[0] else {
      return XCTFail("应产出合并文本块")
    }

    let string = content.string as NSString
    let innerBreak = string.range(of: "第二行B")
    XCTAssertNotEqual(innerBreak.location, NSNotFound, "样本必须包含段内换行")
    let style = content.attribute(
      .paragraphStyle,
      at: innerBreak.location,
      effectiveRange: nil
    ) as? NSParagraphStyle

    XCTAssertEqual(style?.lineSpacing, 4, "段内换行仍应保持行距")
    XCTAssertEqual(style?.paragraphSpacingBefore, 0, "段内换行不得吃到块间距")
  }

  /// 占位文档不解析 markdown,会把「# 标题」也当成普通段落,于是整篇被合并成
  /// 一个块;解析后标题独立成块,正文另成一块。两侧 renderable 数量必然不同,
  /// 所以合并块的 id 不能取「首个成员的 id」——否则解析结果落地瞬间正文块会
  /// 拿到一个全新身份,整篇重新挂载并从 alpha 0 淡入。
  func testPlaceholderAndParsedAgreeOnIdentityWhenDocumentStartsWithHeading() async {
    let text = "# 标题\n\n正文一\n\n正文二"
    let placeholder = RenderableDocument(
      plainText: text,
      id: "0",
      config: config(coalescing: true),
      splittingParagraphsOnBlankLines: true
    ).renderables
    let parsed = await renderables(text, coalescing: true)

    let placeholderTextID = placeholder.compactMap { renderable -> String? in
      guard case .coalescedText(let id, _) = renderable else { return nil }
      return id
    }.first
    let parsedTextID = parsed.compactMap { renderable -> String? in
      guard case .coalescedText(let id, _) = renderable else { return nil }
      return id
    }.first

    XCTAssertNotNil(placeholderTextID, "占位侧应有合并文本块")
    XCTAssertNotNil(parsedTextID, "解析侧应有合并文本块")
    XCTAssertEqual(
      placeholderTextID,
      parsedTextID,
      "正文合并块的身份必须跨越占位→解析的交接,否则整篇重新淡入"
    )
  }
}

//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Markdown
@testable import SwiftStreamingMarkdown
import SwiftUI
import UIKit
import XCTest

/// 探针（取证，不修复）：块级回溯定型（半截表格/半截代码围栏在语法完整瞬间从
/// paragraph 变成 table/codeBlock）对 `RenderableDocument` 身份与布局高度的影响。
///
/// 复刻生产管线 `MessageBubbleView.parseNow`：
/// `MarkdownParserImpl.parse(text:option:)`（`speculativeRewrite: true`）→
/// `RenderableDocument(document:config:)` → `reusingUnchangedPrefix(from:)`。
///
/// 这些测试如实记录定型瞬间发生了什么；红色结果和绿色结果同样是有效产出，
/// 不因为想让测试转绿而调整断言口径或改动生产代码。
@MainActor
final class StreamingBlockRetypingProbeTests: XCTestCase {

  private let parser: MarkdownParser = MarkdownParserImpl()
  // 与生产 MessageBubbleView.parseNow 一致：流式场景（shouldAnimateText）下启用
  // speculativeRewrite，让 PartialTableMarkupPostParsingRewriter 等生效。
  private let streamingOption = MarkdownParseOption(speculativeRewrite: true)
  private let config = MarkdownRenderConfig.default

  private struct StepResult {
    let text: String
    let ids: [String]
    let cases: [String]
    let renderable: RenderableDocument
  }

  /// 复刻生产的流式解析管线：每次传入完整的增长后文本快照（而非增量 delta）。
  private func runStreamingPipeline(_ snapshots: [String]) async -> [StepResult] {
    var results: [StepResult] = []
    var previousText: String?
    var previousRenderable: RenderableDocument?
    for text in snapshots {
      let parseResult = await parser.parse(text: text, option: streamingOption)
      let converted = await RenderableDocument(document: parseResult.document, config: config)
      let renderable: RenderableDocument
      if let previousText, let previousRenderable, text.hasPrefix(previousText) {
        renderable = converted.reusingUnchangedPrefix(from: previousRenderable)
      } else {
        renderable = converted
      }
      results.append(StepResult(
        text: text,
        ids: renderable.renderables.map(\.id),
        cases: renderable.renderables.map(Self.caseName(of:)),
        renderable: renderable
      ))
      previousText = text
      previousRenderable = renderable
    }
    return results
  }

  private static func caseName(of renderable: MarkdownRenderable) -> String {
    switch renderable {
    case .paragraph: return "paragraph"
    case .latex: return "latex"
    case .heading: return "heading"
    case .orderedList: return "orderedList"
    case .unorderedList: return "unorderedList"
    case .codeBlock: return "codeBlock"
    case .table: return "table"
    case .thematicBreak: return "thematicBreak"
    case .blockQuote: return "blockQuote"
    }
  }

  private func paragraphContent(_ renderable: MarkdownRenderable) -> NSMutableAttributedString? {
    if case let .paragraph(_, content) = renderable { return content }
    return nil
  }

  /// 在 `steps` 中找到 index 位置的 case 序列第一次从 `from` 变为 `to` 的下标；
  /// 要求变化前一步确实是 `from`（证明这是一次真正的定型转变，而不是从一开始
  /// 就已经是 `to`）。找不到则返回 nil（取证事实：本次序列没有发生该定型）。
  private func firstFlipIndex(in steps: [StepResult], at position: Int, from: String, to: String) -> Int? {
    for index in 1..<steps.count {
      guard steps[index].cases.count > position, steps[index - 1].cases.count > position else { continue }
      if steps[index - 1].cases[position] == from, steps[index].cases[position] == to {
        return index
      }
    }
    return nil
  }

  private func logSteps(_ label: String, _ steps: [StepResult]) {
    var log = "[probeA-\(label)]"
    for (index, step) in steps.enumerated() {
      log += "\n  step\(index) ids=\(step.ids) cases=\(step.cases)"
    }
    print(log)
  }

  // MARK: - 段落 → 表格回溯定型

  private static let tablePrefix = "在这条街的尽头，老式路灯把雨水切成细小的光斑，主角想起许多旧事，" +
    "却没有停下脚步，也没有回头，城市的喧嚣仿佛都远去了，只剩下心跳声清晰可闻。"

  private var tableSnapshots: [String] {
    let prefix = Self.tablePrefix
    return [
      prefix,
      prefix + "\n\n| 月份 ",
      prefix + "\n\n| 月份 | 数据 |",
      prefix + "\n\n| 月份 | 数据 |\n| --",
      prefix + "\n\n| 月份 | 数据 |\n| ----",
      prefix + "\n\n| 月份 | 数据 |\n| ---- | ---- |",
      prefix + "\n\n| 月份 | 数据 |\n| ---- | ---- |\n| 一月",
      prefix + "\n\n| 月份 | 数据 |\n| ---- | ---- |\n| 一月 | 100 |",
      prefix + "\n\n| 月份 | 数据 |\n| ---- | ---- |\n| 一月 | 100 |\n\n他继续在雨里前行，脚步声渐渐远去。"
    ]
  }

  func testParagraphToTableRetypingPrefixIdentity() async throws {
    let steps = await runStreamingPipeline(tableSnapshots)
    logSteps("table", steps)

    // --- 前缀块（index 0）身份稳定 ---
    for (index, step) in steps.enumerated() {
      XCTAssertEqual(step.ids.first, "0", "step\(index): 前缀块 id 漂移")
      XCTAssertEqual(step.cases.first, "paragraph", "step\(index): 前缀块类型漂移")
    }

    guard let firstContent = paragraphContent(steps[0].renderable.renderables[0]) else {
      return XCTFail("expected paragraph at index 0")
    }
    for (index, step) in steps.enumerated().dropFirst() {
      guard let content = paragraphContent(step.renderable.renderables[0]) else {
        XCTFail("step\(index): index0 不再是 paragraph")
        continue
      }
      XCTAssertTrue(
        content === firstContent,
        "step\(index): 前缀块的 NSMutableAttributedString 被重新分配（对象身份丢失）—— " +
          "reusingUnchangedPrefix 未能在表格回溯定型时保住前缀引用（前缀被株连）"
      )
    }

    // --- 定型块自身（index 1）：记录 id 是否延续、case 从什么变成什么 ---
    guard let flipIndex = firstFlipIndex(in: steps, at: 1, from: "paragraph", to: "table") else {
      return XCTFail("未观测到 paragraph -> table 的定型；表格语法未按预期被识别，需要重新核对测试输入")
    }
    XCTAssertEqual(
      steps[flipIndex].ids[1], steps[flipIndex - 1].ids[1],
      "定型前后 id 应保持一致 —— 这正是 SwiftUI ForEach(id:) 身份陷阱所在：" +
        "同一 id 槽位换了完全不同的 case，行内视图仍会被销毁重建"
    )
    print("[probeA-table] 定型发生在 step\(flipIndex - 1) -> step\(flipIndex)，id=\(steps[flipIndex].ids[1]) 保持不变")
  }

  // MARK: - 段落 → 代码围栏回溯定型

  private static let codeFencePrefix = "他从口袋里摸出手机，屏幕的光在黑暗里格外刺眼，" +
    "路上几乎没有行人，只有风声穿过巷子。"

  private var codeFenceSnapshots: [String] {
    let prefix = Self.codeFencePrefix
    return [
      prefix,
      prefix + "\n\n`",
      prefix + "\n\n``",
      prefix + "\n\n```",
      prefix + "\n\n```swift",
      prefix + "\n\n```swift\n",
      prefix + "\n\n```swift\nprint(\"你好",
      prefix + "\n\n```swift\nprint(\"你好\")",
      prefix + "\n\n```swift\nprint(\"你好\")\n```",
      prefix + "\n\n```swift\nprint(\"你好\")\n```\n\n他把手机揣回口袋，继续朝地铁站走去。"
    ]
  }

  func testParagraphToCodeFenceRetypingPrefixIdentity() async throws {
    let steps = await runStreamingPipeline(codeFenceSnapshots)
    logSteps("codefence", steps)

    for (index, step) in steps.enumerated() {
      XCTAssertEqual(step.ids.first, "0", "step\(index): 前缀块 id 漂移")
      XCTAssertEqual(step.cases.first, "paragraph", "step\(index): 前缀块类型漂移")
    }

    guard let firstContent = paragraphContent(steps[0].renderable.renderables[0]) else {
      return XCTFail("expected paragraph at index 0")
    }
    for (index, step) in steps.enumerated().dropFirst() {
      guard let content = paragraphContent(step.renderable.renderables[0]) else {
        XCTFail("step\(index): index0 不再是 paragraph")
        continue
      }
      XCTAssertTrue(
        content === firstContent,
        "step\(index): 前缀块的 NSMutableAttributedString 被重新分配（对象身份丢失）—— " +
          "reusingUnchangedPrefix 未能在代码围栏回溯定型时保住前缀引用（前缀被株连）"
      )
    }

    guard let flipIndex = firstFlipIndex(in: steps, at: 1, from: "paragraph", to: "codeBlock") else {
      return XCTFail("未观测到 paragraph -> codeBlock 的定型；围栏语法未按预期被识别，需要重新核对测试输入")
    }
    XCTAssertEqual(
      steps[flipIndex].ids[1], steps[flipIndex - 1].ids[1],
      "定型前后 id 应保持一致 —— 同一 id 槽位从 paragraph 换成 codeBlock，行内视图仍会被销毁重建"
    )
    print("[probeA-codefence] 定型发生在 step\(flipIndex - 1) -> step\(flipIndex)，id=\(steps[flipIndex].ids[1]) 保持不变")
  }

  // MARK: - 布局高度序列：定型瞬间是否出现「先减后增」结构性回摆
  //
  // 复刻 2026-07-18 的「布局级判别实验」先例（见 MarkdownTextTests.
  // testSplitPlaceholderTracksParsedLayoutHeightFarCloserThanLegacyPlaceholder）：
  // 用 UIHostingController(rootView: DocumentView(...)).sizeThatFits 逐 chunk 测量总高度。
  //
  // 局限（如实说明，不冒充）：这里对每一步都新建一次 UIHostingController 并同步调用
  // sizeThatFits，测到的是该步「结算后」的高度——SwiftUI 在这次同步调用里会跑到布局
  // 收敛为止。ParagraphView 里 sizeThatFits 先于 updateUIView 执行、量到旧文本高度
  // 又被后续 invalidateIntrinsicContentSize 修正的那"一帧"，属于同一次 CADisplayLink
  // 驱动的实时更新时序，headless XCTest 无法在同一个持久化 UIView 上重放这个时序，
  // 因此这组测试只能证伪/证实"结构性"回摆（例如清空-重定型导致真实内容变矮又变高），
  // 测不到单帧瞬态回摆，这点与探针 B 的 contentSize 采样互补。
  private func assertHeightSequenceMonotonic(label: String, snapshots: [String]) async throws {
    let steps = await runStreamingPipeline(snapshots)
    var heights: [CGFloat] = []
    for step in steps {
      let host = UIHostingController(rootView: DocumentView(
        renderableDocument: step.renderable,
        config: config,
        animateInitialText: false
      ))
      let height = host.sizeThatFits(in: CGSize(width: 360, height: CGFloat.greatestFiniteMagnitude)).height
      heights.append(height)
    }
    print("[probeA-height-\(label)] heights=\(heights)")

    var oscillations = 0
    for index in 1..<heights.count {
      let delta = heights[index] - heights[index - 1]
      if delta < -8 {
        oscillations += 1
        print("[probeA-height-\(label)] 回摆: step\(index - 1)(\(heights[index - 1])) -> step\(index)(\(heights[index])) Δ=\(delta)")
      }
    }
    XCTAssertEqual(oscillations, 0, "[\(label)] 高度序列出现 >8pt 的先减后增回摆，见上方日志")
  }

  func testTableRetypingHeightSequenceHasNoStructuralOscillation() async throws {
    try await assertHeightSequenceMonotonic(label: "table", snapshots: tableSnapshots)
  }

  func testCodeFenceRetypingHeightSequenceHasNoStructuralOscillation() async throws {
    try await assertHeightSequenceMonotonic(label: "codefence", snapshots: codeFenceSnapshots)
  }

  // MARK: - 段落 → 段落分裂（散文场景，小说主场景，此前的探针盲区）
  //
  // 与「段落 → 表格/代码围栏」回溯定型不同，这里没有语法歧义：一段持续增长的
  // 纯 CJK 长段落，流式中出现完整空行（两个 "\n"）后紧跟下一段的首个片段，
  // 才回溯定型分裂成两个段落；继续增长后重复一次。逐句累积、空行与下一段首个
  // 片段分开落在不同 step（不假设两者在同一条 delta 里到达），贴近生产逐 chunk
  // 追加的真实节奏。

  private static let proseSplitFirstParagraphSentences = [
    "在这条街的尽头，老式路灯把雨水切成细小的光斑，",
    "主角想起许多旧事，却没有停下脚步，也没有回头，",
    "城市的喧嚣仿佛都远去了，只剩下心跳声清晰可闻，",
    "他数着自己的呼吸，试图让翻涌的心情重新平静下来。"
  ]

  private static let proseSplitSecondParagraphSentences = [
    "他推开虚掩的木门",
    "，屋内弥漫着旧书与灰尘混合的气味，",
    "墙角堆着几箱未拆封的信件，窗帘半掩着，光线从缝隙里挤进来，",
    "落在斑驳的地板上，像一条模糊的界线。"
  ]

  private static let proseSplitThirdParagraphSentences = [
    "又过了许久，远处传来钟声",
    "，一下又一下，敲碎了这片刻的宁静，",
    "他终于抬起头，望向窗外渐渐亮起来的天色，",
    "才发觉窗外已经不知不觉地下起了小雨。"
  ]

  private var proseSplitSnapshots: [String] {
    var text = ""
    var snapshots: [String] = []
    func append(_ delta: String) {
      text += delta
      snapshots.append(text)
    }
    Self.proseSplitFirstParagraphSentences.forEach(append)
    append("\n")
    append("\n")
    Self.proseSplitSecondParagraphSentences.forEach(append)
    append("\n")
    append("\n")
    Self.proseSplitThirdParagraphSentences.forEach(append)
    return snapshots
  }

  func testProseSplitPreservesPrefixBlockIdentityAcrossSplits() async throws {
    let steps = await runStreamingPipeline(proseSplitSnapshots)
    logSteps("prose-split", steps)

    // --- 定位两次分裂：块数量 1 -> 2 -> 3 的跃迁 ---
    guard let firstSplit = (1..<steps.count).first(where: {
      steps[$0].ids.count == 2 && steps[$0 - 1].ids.count == 1
    }) else {
      return XCTFail("未观测到首次段落分裂；输入未按预期触发空行分段，需要重新核对测试输入")
    }
    guard let secondSplit = ((firstSplit + 1)..<steps.count).first(where: {
      steps[$0].ids.count == 3 && steps[$0 - 1].ids.count == 2
    }) else {
      return XCTFail("未观测到第二次段落分裂；输入未按预期触发第二次分段，需要重新核对测试输入")
    }
    print(
      "[probeA-prose-split] 首次分裂 step\(firstSplit - 1) -> step\(firstSplit)，" +
      "二次分裂 step\(secondSplit - 1) -> step\(secondSplit)"
    )

    // --- (a) 分裂前块（index 0）的 id/case 在整个序列里是否漂移 ---
    for (index, step) in steps.enumerated() {
      XCTAssertEqual(step.ids.first, "0", "step\(index): index0 id 漂移")
      XCTAssertEqual(step.cases.first, "paragraph", "step\(index): index0 case 漂移")
    }

    // --- index0 的内容：分裂前后是否缩短失去尾巴 ---
    guard let block0AtSettle = paragraphContent(steps[firstSplit - 1].renderable.renderables[0]) else {
      return XCTFail("expected paragraph at index 0")
    }
    let block0ContentAtSettle = block0AtSettle.string
    for index in firstSplit..<steps.count {
      let content = paragraphContent(steps[index].renderable.renderables[0])?.string
      XCTAssertEqual(
        content, block0ContentAtSettle,
        "step\(index): index0 分裂后内容漂移——如果与分裂前不等，就实锤了" +
          "「前块缩短失去尾巴」的散文分裂闪烁嫌疑"
      )
    }
    print("[probeA-prose-split] index0 分裂前后内容一致（无缩短/失尾现象）：\"\(block0ContentAtSettle)\"")

    // index0 的 NSMutableAttributedString 对象引用在分裂前后是否延续（如实记录，不设阈值）
    var identityPreservedAcrossSplits = true
    for index in firstSplit..<steps.count {
      guard let content = paragraphContent(steps[index].renderable.renderables[0]) else { continue }
      if content !== block0AtSettle { identityPreservedAcrossSplits = false }
    }
    print("[probeA-prose-split] index0 对象引用在两次分裂后是否延续（===）：\(identityPreservedAcrossSplits)")

    // --- (b) 新块（index 1 / index 2）的出现方式：内容是否在分裂前就已经上屏过 ---
    let block1Content = paragraphContent(steps[firstSplit].renderable.renderables[1])?.string ?? ""
    let priorText1 = steps[firstSplit - 1].renderable.renderables
      .compactMap { paragraphContent($0)?.string }
      .joined()
    let block1WasAlreadyOnscreen = priorText1.contains(block1Content)
    XCTAssertFalse(
      block1WasAlreadyOnscreen,
      "index1 首次出现的内容在分裂前就已经作为其它块的一部分上屏——" +
        "实锤了「新块携带尾巴回流重新入场」的嫌疑"
    )
    print(
      "[probeA-prose-split] index1 首现内容=\"\(block1Content)\"，" +
      "分裂前是否已上屏=\(block1WasAlreadyOnscreen)"
    )

    let block2Content = paragraphContent(steps[secondSplit].renderable.renderables[2])?.string ?? ""
    let priorText2 = steps[secondSplit - 1].renderable.renderables
      .compactMap { paragraphContent($0)?.string }
      .joined()
    let block2WasAlreadyOnscreen = priorText2.contains(block2Content)
    XCTAssertFalse(
      block2WasAlreadyOnscreen,
      "index2 首次出现的内容在二次分裂前就已经作为其它块的一部分上屏"
    )
    print(
      "[probeA-prose-split] index2 首现内容=\"\(block2Content)\"，" +
      "二次分裂前是否已上屏=\(block2WasAlreadyOnscreen)"
    )
  }

  // MARK: - 布局高度序列：散文分裂瞬间是否出现结构性回摆
  //
  // 与 testTableRetypingHeightSequenceHasNoStructuralOscillation /
  // testCodeFenceRetypingHeightSequenceHasNoStructuralOscillation 同款局限说明：
  // 只能证伪/证实结构性回摆，测不到单帧瞬态回摆。

  func testProseSplitHeightSequenceHasNoStructuralOscillation() async throws {
    try await assertHeightSequenceMonotonic(label: "prose-split", snapshots: proseSplitSnapshots)
  }

  // MARK: - 淡入探针：定型/分裂是否让 ParagraphView.makeUIView 对已上屏文本重放入场淡入
  //
  // 与上面的高度序列测试不同（那组测试每步新建一次 UIHostingController，量的是
  // "结算后"的高度，天然测不出 SwiftUI ForEach 身份是否被保留），这里用持久的
  // UIWindow + UIHostingController 承载同一个文档更新序列，让 SwiftUI 的身份 diff
  // 真正跑起来，再用 `ParagraphInitialFadePolicy.testFadeObserver` 记录每一次
  // `ParagraphView.makeUIView` 的淡入决策，而不依赖真机动画时序去读 view.alpha。

  private final class FadeProbeModel: ObservableObject {
    @Published var renderableDocument: RenderableDocument = .empty
  }

  private struct FadeProbeHarness: View {
    @ObservedObject var model: FadeProbeModel
    let config: MarkdownRenderConfig

    var body: some View {
      DocumentView(renderableDocument: model.renderableDocument, config: config, animateInitialText: true)
        .frame(width: 360, alignment: .topLeading)
    }
  }

  private struct FadeEvent {
    let step: Int
    let contents: String
    let willFade: Bool
  }

  /// 与 `runStreamingPipeline` 同构的解析管线，但挂在持久窗口上，让每次
  /// `model.renderableDocument` 赋值都经过真实的 SwiftUI diff（而非每步重新
  /// 构造一次性 hosting controller）。
  private func runLiveHostedFadeProbe(
    _ snapshots: [String],
    config: MarkdownRenderConfig
  ) async -> [FadeEvent] {
    let model = FadeProbeModel()
    let host = UIHostingController(rootView: FadeProbeHarness(model: model, config: config))
    let window: UIWindow
    if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
      window = UIWindow(windowScene: scene)
    } else {
      window = UIWindow(frame: .zero)
    }
    window.frame = CGRect(x: 0, y: 0, width: 360, height: 4000)
    window.rootViewController = host
    window.makeKeyAndVisible()
    window.layoutIfNeeded()

    var events: [FadeEvent] = []
    var previousText: String?
    var previousRenderable: RenderableDocument?
    for (step, text) in snapshots.enumerated() {
      ParagraphInitialFadePolicy.testFadeObserver = { [step] contents, willFade in
        events.append(FadeEvent(step: step, contents: contents, willFade: willFade))
      }
      let parseResult = await parser.parse(text: text, option: streamingOption)
      let converted = await RenderableDocument(document: parseResult.document, config: config)
      let renderable: RenderableDocument
      if let previousText, let previousRenderable, text.hasPrefix(previousText) {
        renderable = converted.reusingUnchangedPrefix(from: previousRenderable)
      } else {
        renderable = converted
      }
      model.renderableDocument = renderable
      // 必须真正让 RunLoop 转起来:仅调用 layoutIfNeeded() 不足以保证 SwiftUI
      // 把这一步当成独立的 diff pass 处理——Combine 的 @Published 通知虽然同步,
      // 但 SwiftUI 的 body 重新求值可能被调度到下一次 RunLoop tick,不转
      // RunLoop 就可能把相邻几步的文档更新合并成一次 diff,从而掩盖定型瞬间
      // 该有的身份变化。与 ChatStructuralRetypeReplayTests 的 pump() 同构。
      let deadline = Date().addingTimeInterval(0.05)
      while Date() < deadline {
        RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.01))
      }
      window.layoutIfNeeded()
      previousText = text
      previousRenderable = renderable
    }

    ParagraphInitialFadePolicy.testFadeObserver = nil
    window.isHidden = true
    window.rootViewController = nil
    return events
  }

  /// 如实记录：对每一次 `willFade == true` 的事件，检查它的文本是否与此前某次
  /// 已经淡入过的文本相同或存在前缀/后缀重叠——这正是「同一段可视内容被第二次
  /// 播放入场淡入」的直接证据（旧文本重新入场淡入 = 定型/分裂时视图被销毁重建）。
  private func assertNoRefadeOfPreviouslyDisplayedText(_ label: String, _ events: [FadeEvent]) {
    print(
      "[probeFade-\(label)] events=\(events.map { "step\($0.step) fade=\($0.willFade) text=\"\($0.contents)\"" })"
    )
    var previouslyFadedTexts: [String] = []
    for event in events where event.willFade {
      if let overlapping = previouslyFadedTexts.first(where: { previous in
        !previous.isEmpty && !event.contents.isEmpty &&
          (previous == event.contents || previous.hasSuffix(event.contents) || event.contents.hasSuffix(previous))
      }) {
        XCTFail(
          "[\(label)] step\(event.step) 对已上屏文本重放了入场淡入：新淡入=\"\(event.contents)\" " +
            "与此前已淡入文本重叠=\"\(overlapping)\""
        )
      }
      previouslyFadedTexts.append(event.contents)
    }
  }

  func testTableRetypingDoesNotRefadePreviouslyDisplayedText() async throws {
    let events = await runLiveHostedFadeProbe(tableSnapshots, config: config.withShouldAnimateText(value: true))
    assertNoRefadeOfPreviouslyDisplayedText("table", events)
  }

  func testCodeFenceRetypingDoesNotRefadePreviouslyDisplayedText() async throws {
    let events = await runLiveHostedFadeProbe(codeFenceSnapshots, config: config.withShouldAnimateText(value: true))
    assertNoRefadeOfPreviouslyDisplayedText("codefence", events)
  }

  func testProseSplitDoesNotRefadePreviouslyDisplayedText() async throws {
    let events = await runLiveHostedFadeProbe(proseSplitSnapshots, config: config.withShouldAnimateText(value: true))
    assertNoRefadeOfPreviouslyDisplayedText("prose-split", events)
  }
}

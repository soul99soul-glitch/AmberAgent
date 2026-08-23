//
//  Vendored regression test (AmberAgent): chat-column width overflow.
//

import XCTest
@testable import SwiftStreamingMarkdown
import SwiftUI
import UIKit

/// 真机报告（2026-08-08，iPhone Air，iOS 27）：聊天正文 UITextView 左右对称飞出屏幕。
/// 生产链路特征：ScrollView 内的 eager VStack（左右各 16pt padding），
/// 段落走 `BlockView` 的 `HStack { ParagraphView; Spacer() }`，
/// `ParagraphUIView.intrinsicContentSize` 在 bounds 无效时按整屏宽度排版并缓存。
/// 本测试按生产几何（窗口宽 == 屏宽、内容列左右各缩 16pt）托管完整的
/// DocumentView 链路，然后巡视所有 ParagraphUIView 的 window 坐标 frame：
/// 任何超出屏幕左右边界（即被父容器居中裁切）的实例都是用户可见的溢出。
final class ChatColumnWidthOverflowTests: XCTestCase {

  /// 与 AmberAgent 生产一致的渲染参数。
  private var config: MarkdownRenderConfig {
    MarkdownRenderConfig.default
      .withBlockSpacing(value: 8)
      .withParagraphLineSpacing(value: 4)
      .withCollapsesSoftBreaks(value: true)
  }

  /// 模拟生产里 assistant 长正文（含 CJK 长行，保证整列换行而非短行居左）。
  private var fixtureText: String {
    (0..<6).map { index in
      "第\(index)段：网页节点在 iOS 上是未启用状态，我尝试把它加为可用站点（需要确认网络权限），然后读取页面内容并总结给你。"
    }.joined(separator: "\n\n")
  }

  @MainActor
  func testParagraphsInPaddedChatColumnStayInsideScreen() async throws {
    let renderable = await MarkdownParserImpl().parse(text: fixtureText, config: config)
    let offenders = hostAndAudit(renderable: renderable, label: "fresh")
    XCTAssertTrue(offenders.isEmpty, "聊天列段落溢出屏幕: \(offenders)")
  }

  /// 真机会话触发物：几乎无断词的 DSML / tool_calls 长串。
  /// TextKit usedRect 可宽于测量容器；若不钳制，整列 ideal width 爆炸并被居中裁切。
  @MainActor
  func testUnbreakableDSMLParagraphStaysInsideScreen() async throws {
    let dsml = """
    <|DSML|><tool_calls><|DSML|><invoke name="wm_read_page"><|DSML|><parameter \
    name="session_id">ios_wm_44D388B2</parameter><parameter name="wait_timeout_ms" \
    string="false">5000</parameter><parameter name="url">https://github.com/openai/codex</parameter>\
    </invoke></tool_calls>
    """
    let renderable = await MarkdownParserImpl().parse(text: dsml, config: config)
    let offenders = hostAndAudit(renderable: renderable, label: "dsml")
    XCTAssertTrue(offenders.isEmpty, "不可断 DSML 段落溢出屏幕: \(offenders)")
  }

  /// 变体：先挂载再拆解（ParagraphUIView 回收到 ParagraphUIViewCache），
  /// 用不同文本重新挂载——复用视图带着按旧几何缓存的尺寸进入新布局。
  @MainActor
  func testReusedParagraphViewInPaddedChatColumnStaysInsideScreen() async throws {
    let first = await MarkdownParserImpl().parse(text: fixtureText, config: config)
    _ = hostAndAudit(renderable: first, label: "warmup")

    let secondText = (0..<4).map { index in
      "复用第\(index)段：缓存视图进入新窗口时不得沿用整屏宽度排版，否则左右会被居中裁掉。"
    }.joined(separator: "\n\n")
    let second = await MarkdownParserImpl().parse(text: secondText, config: config)
    let offenders = hostAndAudit(renderable: second, label: "reused")
    XCTAssertTrue(offenders.isEmpty, "复用段落视图溢出屏幕: \(offenders)")
  }


  /// ScrollView 内容宽不得大于视口：这是真机「左右对称裁切」的直接度量。
  @MainActor
  func testScrollContentWidthStaysWithinViewport() async throws {
    let renderable = await MarkdownParserImpl().parse(text: fixtureText, config: config)
    let screenWidth = UIScreen.main.bounds.width
    let root = ScrollView {
      VStack(alignment: .leading, spacing: 14) {
        DocumentView(
          renderableDocument: renderable,
          config: config,
          animateInitialText: false,
          usesTextKit1ForAttachmentFreeText: true
        )
      }
      .padding(.horizontal, 16)
    }
    .frame(width: screenWidth)

    let host = UIHostingController(rootView: root)
    let window: UIWindow
    if let scene = UIApplication.shared.connectedScenes
      .compactMap({ $0 as? UIWindowScene }).first {
      window = UIWindow(windowScene: scene)
      window.frame = CGRect(x: 0, y: 0, width: screenWidth, height: 800)
    } else {
      window = UIWindow(frame: CGRect(x: 0, y: 0, width: screenWidth, height: 800))
    }
    window.rootViewController = host
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    let deadline = Date().addingTimeInterval(1.0)
    while Date() < deadline {
      RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.05))
    }
    window.layoutIfNeeded()

    var contentWidth: CGFloat = 0
    func walk(_ view: UIView) {
      if let scroll = view as? UIScrollView {
        contentWidth = max(contentWidth, scroll.contentSize.width)
      }
      view.subviews.forEach(walk)
    }
    walk(window)
    print("[contentWidth] content=\(contentWidth) screen=\(screenWidth)")
    XCTAssertGreaterThan(contentWidth, 0)
    XCTAssertLessThanOrEqual(
      contentWidth,
      screenWidth + 1,
      "ScrollView contentWidth \(contentWidth) > viewport \(screenWidth)"
    )
    window.isHidden = true
    window.rootViewController = nil
  }

  // MARK: - Harness

  @MainActor
  private func hostAndAudit(renderable: RenderableDocument, label: String) -> [String] {
    let cfg = config
    // 生产是 ScrollView 内的消息列：只夹视口宽，不夹内容 ideal width。
    let content = ScrollView {
      VStack(alignment: .leading, spacing: 14) {
        DocumentView(
          renderableDocument: renderable,
          config: cfg,
          animateInitialText: false,
          usesTextKit1ForAttachmentFreeText: true
        )
      }
      .padding(.horizontal, 16)
    }

    let screenSize = UIScreen.main.bounds.size
    let host = UIHostingController(
      rootView: content.frame(width: screenSize.width)
    )

    let window: UIWindow
    if let scene = UIApplication.shared.connectedScenes
      .compactMap({ $0 as? UIWindowScene }).first {
      window = UIWindow(windowScene: scene)
      window.frame = CGRect(origin: .zero, size: screenSize)
    } else {
      window = UIWindow(frame: CGRect(origin: .zero, size: screenSize))
    }
    window.rootViewController = host
    window.makeKeyAndVisible()
    window.layoutIfNeeded()

    // DocumentView 的段落测量与 SwiftUI 布局需要若干轮 runloop。
    let deadline = Date().addingTimeInterval(1.5)
    while Date() < deadline {
      RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.05))
    }
    window.layoutIfNeeded()

    // 只约束宽度：高度按内容自然高度放大窗口，避免竖向截断掩盖横向溢出。
    let naturalSize = host.sizeThatFits(in: CGSize(
      width: screenSize.width,
      height: .greatestFiniteMagnitude
    ))
    if naturalSize.height > window.frame.height {
      window.frame = CGRect(x: 0, y: 0, width: screenSize.width, height: naturalSize.height)
      host.view.frame = window.bounds
      window.layoutIfNeeded()
      let secondDeadline = Date().addingTimeInterval(0.5)
      while Date() < secondDeadline {
        RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.05))
      }
      window.layoutIfNeeded()
    }

    // 生产 VStack 左右各 16pt padding。段落若按整屏宽排版再被居中，
    // 会吃掉 padding 贴边（真机「两边溢出」的常见形态），不一定越出 window。
    let padding: CGFloat = 16
    var offenders: [String] = []
    var paragraphViewCount = 0
    func walk(_ view: UIView) {
      if view is ParagraphUIView {
        paragraphViewCount += 1
        let frame = view.convert(view.bounds, to: window)
        if frame.minX < padding - 1 || frame.maxX > screenSize.width - padding + 1 {
          offenders.append(
            "ParagraphUIView frame=\(frame) expectedInset=\(padding)"
          )
        }
      }
      view.subviews.forEach(walk)
    }
    walk(window)
    print("[\(label)] paragraphViews=\(paragraphViewCount) screen=\(screenSize) offenders=\(offenders.count)")
    // 哨兵：树里必须真的渲染出了段落视图，否则"无溢出"是假阴性。
    XCTAssertGreaterThan(paragraphViewCount, 0, "[\(label)] ParagraphUIView 未渲染，测试无效")

    window.isHidden = true
    window.rootViewController = nil
    return offenders
  }
}

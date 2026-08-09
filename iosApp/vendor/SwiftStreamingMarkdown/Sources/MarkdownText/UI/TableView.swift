//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Foundation
import SwiftUI
import UIKit

enum RowContent: Equatable {
  case text(string: AttributedString)
  case containsAttachment(string: NSAttributedString)

  init(_ content: NSMutableAttributedString) {
    if content.containsAttachments(in: NSRange(location: 0, length: content.length)) {
      self = .containsAttachment(string: content)
    } else {
      self = .text(string: AttributedString(content))
    }
  }

  var attributedString: NSAttributedString {
    switch self {
    case .text(let string):
      NSAttributedString(string)
    case .containsAttachment(let string):
      string
    }
  }
}

struct TableView: View {
  @Environment(\.markdownConfig) var config: MarkdownRenderConfig
  @Environment(\.markdownAnimateInitialText) var animateInitialText
  @Environment(\.markdownController) var controller: MarkdownController?
  @Environment(\.markdownUsesLayerBackedTableAnimation) var usesLayerBackedTableAnimation

  let headings: [AttributedString]
  let rows: [[RowContent]]
  let columnMaxWidths: [Int: CGFloat]

  @State private var scrollWidth: CGFloat = 0
  @State private var isExpanded: Bool = false
  @State private var isCopyPressed: Bool = false
  @State private var isCopyScaled: Bool = false

  private let rawMarkdown: String

  init(headings: [AttributedString], rows: [[RowContent]], columnMaxWidths: [Int: CGFloat] = [:], rawMarkdown: String = "") {
    self.headings = headings
    self.rows = rows
    self.columnMaxWidths = columnMaxWidths
    self.rawMarkdown = rawMarkdown
  }

  init(headings: [NSMutableAttributedString], rows: [[NSMutableAttributedString]], columnMaxWidths: [Int: CGFloat] = [:], rawMarkdown: String = "") {
    self.init(
      headings: headings.map { AttributedString($0) },
      rows: rows.map { $0.map(RowContent.init) },
      columnMaxWidths: columnMaxWidths,
      rawMarkdown: rawMarkdown
    )
  }

  private var numOfRows: Int {
    return rows.count
  }

  private func headerView(colIdx: Int) -> some View {
    TableCellTextView(
      attributedString: headings[colIdx],
      foregroundColor: config.tableStyle.headerTextColor,
      shouldAnimate: shouldAnimateTableText(headings[colIdx]),
      usesLayerBackedAnimation: usesLayerBackedTableAnimation
    )
      .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
      .accessibilityValue(String.itemPositionInTable(rowIndex: 1, totalRow: numOfRows + 1, columnIndex: colIdx + 1, totalColumn: headings.count))
      .padding(.horizontal, config.tableCellHorizontalPadding)
      .padding(.vertical, config.tableCellVerticalPadding)
      .id("\(colIdx)-heading")
      .background(Color(config.tableStyle.headerBackgroundColor))
      .applyHeaderBorder(colIndex: colIdx, colCount: headings.count, color: Color(config.tableStyle.borderColor))
  }

  @ViewBuilder
  var gridView: some View {
    VStack(alignment: .leading, spacing: 0) {
      TableLayout(columnCount: headings.count, columnMaxWidths: self.actualColumnMaxWidths()) {
        ForEach(0..<headings.count, id: \.self) { colIdx in
          headerView(colIdx: colIdx)
        }

        ForEach(0..<numOfRows, id: \.self) { rowIdx in
          ForEach(0..<headings.count, id: \.self) { colIdx in
            gridCellViewFor(rowIdx: rowIdx, colIdx: colIdx)
          }
        }
      }
    }
  }

  private func actualColumnMaxWidths() -> [CGFloat] {
    let averageWidth = scrollWidth / CGFloat(headings.count)
    var actualColumnMaxWidths = Array(repeating: CGFloat(0), count: headings.count)
    for idx in 0..<headings.count {
      let maxColumnWidth = columnMaxWidths[idx] ?? config.tableMaxColumnWidth
      actualColumnMaxWidths[idx] = max(averageWidth, maxColumnWidth)
    }

    return actualColumnMaxWidths
  }

  @ViewBuilder
  private func gridCellViewFor(rowIdx: Int, colIdx: Int) -> some View {
    let content = rows[rowIdx][colIdx]
    switch content {
    case .containsAttachment(let nsAttributedString):
      // Vendored fix (AmberAgent): no trailing Spacer — it let Paragraph ideal
      // width expand the cell/column the same way chat paragraphs used to.
      ParagraphView(contents: applyTypographyThemingAndGetContent(nsAttributedString))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityValue(String.itemPositionInTable(rowIndex: rowIdx + 2, totalRow: numOfRows + 1, columnIndex: colIdx + 1, totalColumn: headings.count))
        .frame(maxHeight: .infinity)
        .padding(.horizontal, config.tableCellHorizontalPadding)
        .padding(.vertical, config.tableCellVerticalPadding)
        .id("\(colIdx)-\(rowIdx)")
        .applyCellBorder(colIndex: colIdx, colCount: headings.count, rowIndex: rowIdx, rowCount: numOfRows, color: Color(config.tableStyle.borderColor))
    case .text(let attributedString):
      TableCellTextView(
        attributedString: attributedString,
        foregroundColor: config.tableStyle.regularTextColor,
        shouldAnimate: shouldAnimateTableText(attributedString),
        usesLayerBackedAnimation: usesLayerBackedTableAnimation
      )
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityValue(String.itemPositionInTable(rowIndex: rowIdx + 2, totalRow: numOfRows + 1, columnIndex: colIdx + 1, totalColumn: headings.count))
        .frame(maxHeight: .infinity)
        .padding(.horizontal, config.tableCellHorizontalPadding)
        .padding(.vertical, config.tableCellVerticalPadding)
        .id("\(colIdx)-\(rowIdx)")
        .applyCellBorder(colIndex: colIdx, colCount: headings.count, rowIndex: rowIdx, rowCount: numOfRows, color: Color(config.tableStyle.borderColor))
    }
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      if controller != nil {
        scrollView.onTapGesture {
          withAnimation(.easeInOut(duration: 0.2)) {
            isExpanded.toggle()
          }
        }
      } else {
        scrollView
      }

      if isExpanded {
        HStack(spacing: 0) {
          tableCopyButton
          tableDownloadButton
        }
        .padding(.top, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .transition(.opacity)
      }
    }
  }

  private func shouldAnimateTableText(_ attributedString: AttributedString) -> Bool {
    TableViewAnimationPolicy.shouldAnimateCellText(
      configShouldAnimateText: config.shouldAnimateText && animateInitialText,
      headingCount: headings.count,
      rowCount: numOfRows,
      characterCount: attributedString.characters.count
    )
  }

  var scrollView: some View {
    ScrollView([.horizontal]) {
      gridView
        .overlay(
          RoundedRectangle(cornerRadius: 12)
            .inset(by: 0.5)
            .stroke(Color(config.tableStyle.borderColor), lineWidth: 1)
        )
        .cornerRadius(12)
        .onWidthChange { newWidth in
          scrollWidth = newWidth
        }
    }
    .background {
      GeometryReader { geo in
        Color.clear
          .onAppear {
            scrollWidth = geo.size.width
          }
          .onChange(of: geo.size.width) { newValue in
            scrollWidth = newValue
          }
      }
    }
    .scrollIndicators(.hidden)
    .contentShape(Rectangle())
  }

  private var tableCopyButton: some View {
    Button(action: {
      controller?.onTableCopyTap(content: rawMarkdown)
      isCopyPressed = true
      withAnimation(.easeInOut(duration: 0.2)) {
        isCopyScaled = true
      }
      Task { @MainActor in
        try? await Task.sleep(nanoseconds: 200_000_000)
        withAnimation(.easeInOut(duration: 0.25)) {
          isCopyPressed = false
          isCopyScaled = false
        }
      }
    }, label: {
      ZStack {
        Image("Copy", bundle: .module)
          .renderingMode(.template)
          .foregroundStyle(Color(config.tableStyle.actionButtonColor))
          .frame(width: 20, height: 20)
          .opacity(isCopyPressed ? 0.0 : 1.0)

        Image("CopyFilled", bundle: .module)
          .renderingMode(.template)
          .foregroundStyle(Color(config.tableStyle.actionButtonColor))
          .frame(width: 20, height: 20)
          .opacity(isCopyPressed ? 1.0 : 0.0)
      }
      .scaleEffect(isCopyScaled ? 1.3 : 1.0)
    })
    .frame(width: 32, height: 32)
    .contentShape(Rectangle())
  }

  private var tableDownloadButton: some View {
    Button(action: {
      controller?.onTableDownloadTap(content: rawMarkdown)
    }, label: {
      Image("downloadArrow", bundle: .module)
        .renderingMode(.template)
        .foregroundStyle(Color(config.tableStyle.actionButtonColor))
        .frame(width: 20, height: 20)
        .padding(2)
    })
    .frame(width: 32, height: 32)
    .contentShape(Rectangle())
  }
}

private struct TableCellTextView: View {
  let attributedString: AttributedString
  let foregroundColor: UIColor
  let shouldAnimate: Bool
  let usesLayerBackedAnimation: Bool

  @State private var layerAnimationCompleted = false

  var body: some View {
    let text = Text(attributedString)
      .foregroundStyle(Color(foregroundColor))
      .lineLimit(nil)
      .multilineTextAlignment(.leading)

    if shouldAnimate && usesLayerBackedAnimation && !layerAnimationCompleted {
      text
        .opacity(0)
        .overlay(alignment: .topLeading) {
          TableTextEntranceOverlay(
            attributedString: attributedString,
            foregroundColor: foregroundColor
          ) {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
              layerAnimationCompleted = true
            }
          }
          .allowsHitTesting(false)
          .accessibilityHidden(true)
        }
    } else {
      text.if(shouldAnimate && !usesLayerBackedAnimation) { view in
        view.fadeInTextTransition(attributedString: attributedString)
      }
    }
  }
}

private struct TableTextEntranceOverlay: UIViewRepresentable {
  let attributedString: AttributedString
  let foregroundColor: UIColor
  let onCompletion: () -> Void

  func makeUIView(context: Context) -> TableTextEntranceLabel {
    let label = TableTextEntranceLabel()
    label.numberOfLines = 0
    label.lineBreakMode = .byWordWrapping
    label.backgroundColor = .clear
    label.setContentHuggingPriority(.defaultHigh, for: .vertical)
    label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
    label.configure(
      attributedString: themedAttributedString(),
      onCompletion: onCompletion
    )
    return label
  }

  func updateUIView(_ uiView: TableTextEntranceLabel, context: Context) {
    uiView.attributedText = themedAttributedString()
  }

  func sizeThatFits(
    _ proposal: ProposedViewSize,
    uiView: TableTextEntranceLabel,
    context: Context
  ) -> CGSize? {
    guard let width = proposal.width, width > 0, width.isFinite else { return nil }
    return uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
  }

  private func themedAttributedString() -> NSAttributedString {
    let result = NSMutableAttributedString(attributedString: NSAttributedString(attributedString))
    let range = NSRange(location: 0, length: result.length)
    result.enumerateAttribute(.foregroundColor, in: range) { value, subrange, _ in
      if value == nil {
        result.addAttribute(.foregroundColor, value: foregroundColor, range: subrange)
      }
    }
    return result
  }
}

private final class TableTextEntranceLabel: UILabel {
  private var hasStarted = false
  private var onCompletion: (() -> Void)?

  func configure(attributedString: NSAttributedString, onCompletion: @escaping () -> Void) {
    attributedText = attributedString
    alpha = 0
    self.onCompletion = onCompletion
    setNeedsLayout()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    startAnimationIfNeeded()
  }

  override func drawText(in rect: CGRect) {
    let fitted = super.textRect(forBounds: rect, limitedToNumberOfLines: numberOfLines)
    super.drawText(in: CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: fitted.height))
  }

  private func startAnimationIfNeeded() {
    guard !hasStarted, window != nil, bounds.width > 0, bounds.height > 0 else { return }
    hasStarted = true

    let gradient = CAGradientLayer()
    gradient.colors = [
      UIColor.white.cgColor,
      UIColor.white.cgColor,
      UIColor.clear.cgColor,
      UIColor.clear.cgColor
    ]
    gradient.locations = [0, 0.55, 0.65, 1]
    gradient.startPoint = CGPoint(x: 0, y: 0.5)
    gradient.endPoint = CGPoint(x: 1, y: 0.5)
    gradient.frame = CGRect(x: 0, y: 0, width: bounds.width * 2, height: bounds.height)
    layer.mask = gradient
    alpha = 1

    transform = CGAffineTransform(translationX: 0, y: 3).scaledBy(x: 0.995, y: 0.995)
    let sweep = CABasicAnimation(keyPath: "transform.translation.x")
    sweep.fromValue = -bounds.width * 2
    sweep.toValue = 0
    sweep.duration = 0.34
    sweep.timingFunction = CAMediaTimingFunction(name: .easeOut)
    sweep.fillMode = .forwards
    sweep.isRemovedOnCompletion = false
    gradient.add(sweep, forKey: "table-text-reveal")

    UIView.animate(
      withDuration: 0.34,
      delay: 0,
      options: [.curveEaseOut, .allowUserInteraction, .beginFromCurrentState]
    ) {
      self.transform = .identity
    } completion: { _ in
      self.layer.mask = nil
      self.onCompletion?()
      self.onCompletion = nil
    }
  }
}

enum TableViewAnimationPolicy {
  static func shouldAnimateCellText(
    configShouldAnimateText: Bool,
    headingCount: Int,
    rowCount: Int,
    characterCount: Int
  ) -> Bool {
    configShouldAnimateText
  }
}

extension View {

  func applyHeaderBorder(colIndex: Int, colCount: Int, color: Color) -> some View {
    var edges: [Edge] = [.bottom]
    if colIndex != colCount - 1 {
      edges.append(.trailing)
    }
    return border(width: 1, edges: edges, color: color)
  }

  func applyCellBorder(colIndex: Int, colCount: Int, rowIndex: Int, rowCount: Int, color: Color) -> some View {
    var edges: [Edge] = []
    if rowIndex < rowCount - 1 {
      edges.append(.bottom)
    }
    if colIndex < colCount - 1 {
      edges.append(.trailing)
    }
    return border(width: 1, edges: edges, color: color)
  }

  @ViewBuilder
  func fadeInTextTransition(attributedString: AttributedString) -> some View {
    self.fadeInTextTransition(config: .variableDuration(
      glyphCount: attributedString.characters.count,
      glyphDelay: 0.02,
      glyphDuration: 0.2))
  }
}

struct TableLayout: Layout {
  struct CacheData {
    let columnWidths: [CGFloat]
    let rowHeights: [CGFloat]
  }

  let columnCount: Int
  let columnMaxWidths: [CGFloat]

  private let defaultRowHeight: CGFloat = 44

  func makeCache(subviews: Subviews) -> CacheData {
    guard columnCount > 0 else { return CacheData(columnWidths: [], rowHeights: []) }
    let rowCount = subviews.count / columnCount

    var columnWidths = Array(repeating: CGFloat(0), count: columnCount)
    for row in 0..<rowCount {
      for col in 0..<columnCount {
        let index = row * columnCount + col
        let size = subviews[index].sizeThatFits(.unspecified)
        columnWidths[col] = min(max(columnWidths[col], size.width), columnMaxWidths[col])
      }
    }

    var rowHeights = Array(repeating: CGFloat(0), count: rowCount)
    for row in 0..<rowCount {
      var rowHeight: CGFloat = 0
      for col in 0..<columnCount {
        let index = row * columnCount + col
        let height = subviews[index].sizeThatFits(.init(width: columnWidths[col], height: nil)).height
        let cellHeight = height.isFinite ? height : defaultRowHeight
        rowHeight = max(rowHeight, cellHeight)
      }
      rowHeights[row] = rowHeight
    }

    return CacheData(columnWidths: columnWidths, rowHeights: rowHeights)
  }

  func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout CacheData) -> CGSize {
    let totalWidth = cache.columnWidths.reduce(0, +)
    let totalHeight = cache.rowHeights.reduce(0, +)
    return CGSize(width: totalWidth, height: totalHeight)
  }

  func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout CacheData) {
    guard bounds.origin.y.isFinite else {
      return
    }
    let rowCount = subviews.count / columnCount
    var y: CGFloat = bounds.origin.y

    for row in 0..<rowCount {
      var x: CGFloat = bounds.minX.isNaN ? 0 : bounds.minX
      let rowHeight = cache.rowHeights[row]

      for col in 0..<columnCount {
        let index = row * columnCount + col
        subviews[index].place(at: CGPoint(x: x, y: y), proposal: .init(width: cache.columnWidths[col], height: rowHeight))
        x += cache.columnWidths[col]
      }

      y += rowHeight
    }
  }
}

// MARK: - Helper Functions
extension TableView {
  /// Apply typography theming and return themed content for use with ParagraphView
  private func applyTypographyThemingAndGetContent(_ attributedString: NSAttributedString) -> NSMutableAttributedString {
    // Apply typography theming for table cells
    let mutableAttributedString = NSMutableAttributedString(attributedString: attributedString)
    let fullRange = NSRange(location: 0, length: mutableAttributedString.length)
    let themeColor = config.tableStyle.regularTextColor

    // Apply theme color to text that doesn't already have a foreground color
    mutableAttributedString.enumerateAttribute(.foregroundColor, in: fullRange, options: []) { existingColor, range, _ in
      if existingColor == nil {
        mutableAttributedString.addAttribute(.foregroundColor, value: themeColor, range: range)
      }
    }

    // Apply citation baseline offset for proper alignment
    // This is needed because table cells bypass Paragraph+ parsing where baseline offset is normally applied
    var containsCitationAttachments = false
    var containsNonAttachmentContent = false
    var citationRanges: [NSRange] = []

    // One pass: detect & collect citation ranges
    mutableAttributedString.enumerateAttributes(in: fullRange, options: []) { attributes, range, _ in
      guard range.length > 0 else { return }

      // Check if it's a citation attachment
      if let citationAttachment = attributes[.attachment] as? InlineCitationAttachment,
         citationAttachment.citationData != nil {
        containsCitationAttachments = true
        citationRanges.append(range)
        return
      }

      if attributes[.link] != nil {
        containsNonAttachmentContent = true
        return
      }

      // Plain text (non-attachment content)
      containsNonAttachmentContent = true
    }

    // Apply baseline offset only when we have both citations and non-attachment content
    let shouldApplyBaselineOffset = containsCitationAttachments && containsNonAttachmentContent
    if shouldApplyBaselineOffset {
      let baselineOffsetValue = Typography.base.uiFont.descender
      for range in citationRanges {
        mutableAttributedString.addAttribute(.baselineOffset, value: baselineOffsetValue, range: range)
      }
    }

    // Since citations are now already NSTextAttachment in the attributed string during parsing,
    // we can return the themed string directly
    return mutableAttributedString
  }
}

#if DEBUG

let tableViewHeadingMock: [NSMutableAttributedString] = [
  NSMutableAttributedString(string: "Table Heading"),

  NSMutableAttributedString(string: "heading2"),
  NSMutableAttributedString(string: "heading3"),
  NSMutableAttributedString(string: "heading4"),
  NSMutableAttributedString(string: "heading5")
]

let tableviewRowsMock: [[NSMutableAttributedString]] =  [
  [
    NSMutableAttributedString(string: "row1-1 cell Dragon"),
    NSMutableAttributedString(string: "Table body"),
    NSMutableAttributedString(string: "row1-3 cell"),
    NSMutableAttributedString(string: "row1-4 cell"),
    NSMutableAttributedString(string: "row1-5 cell")
  ],
  [
    NSMutableAttributedString(string: "row2-1 cell"),
    NSMutableAttributedString(string: "row2-2 cell"),
    NSMutableAttributedString(string: "row2-3 cell"),
    NSMutableAttributedString(string: "row2-4 cell"),
    NSMutableAttributedString(string: "row2-5 cell")
  ],
  [
    NSMutableAttributedString(string: "row3-1 cell"),
    NSMutableAttributedString(string: "row3-2 cell"),
    NSMutableAttributedString(string: "row3-3 cell"),
    NSMutableAttributedString(string: "row3-4 cell"),
    NSMutableAttributedString(string: "row3-5 cell")
  ]
]

#Preview("Full Table", body: {
  return VStack {
    Spacer()
      .frame(maxHeight: .infinity)
    TableView(
      headings: tableViewHeadingMock,
      rows: tableviewRowsMock
    )
    Spacer()
      .frame(maxHeight: .infinity)
  }
})

#Preview("Header Only", body: {
  return VStack {
    Spacer()
      .frame(maxHeight: .infinity)
    TableView(
      headings: tableViewHeadingMock,
      rows: []
    )
    Spacer()
      .frame(maxHeight: .infinity)
  }
})

#Preview("Table with Citations", body: {
  // Create citation attachments safely
  guard let citationData1 = CitationCoder.default.decode(
    linkDestination: "http://example.com?citationMarker=9F742443&citationTitle=Research&citationA11yValue=Research%20Study"
  ),
    let citation1 = InlineCitationAttachment(citationData: citationData1, citationConfig: .default),
    let citationData2 = CitationCoder.default.decode(
      linkDestination: "http://example.com?citationMarker=9F742443&citationTitle=Analysis&citationA11yValue=Data%20Analysis"
    ),
    let citation2 = InlineCitationAttachment(citationData: citationData2, citationConfig: .default) else {
    return Text(verbatim: "Preview unavailable: Citation creation failed")
  }

  // Create rows with citations
  let rowWithCitation1 = NSMutableAttributedString(string: "Study shows ")
  rowWithCitation1.append(NSAttributedString(attachment: citation1))
  rowWithCitation1.append(NSAttributedString(string: " significant results"))

  let rowWithCitation2 = NSMutableAttributedString(string: "According to ")
  rowWithCitation2.append(NSAttributedString(attachment: citation2))
  rowWithCitation2.append(NSAttributedString(string: ", data confirms trends"))

  let headings = [
    NSMutableAttributedString(string: "Finding"),
    NSMutableAttributedString(string: "Source")
  ]

  let rows = [
    [rowWithCitation1, NSMutableAttributedString(string: "Primary research")],
    [rowWithCitation2, NSMutableAttributedString(string: "Secondary analysis")]
  ]

  return VStack {
    TableView(
      headings: headings,
      rows: rows
    )
    Spacer()
  }
})

#Preview("Citation Only Cells", body: {
  // Create citation with NFL (known rendering issue case)
  guard let nflCitationData = CitationCoder.default.decode(
    linkDestination: "http://example.com?citationMarker=9F742443&citationTitle=NFL&citationA11yValue=National%20Football%20League"
  ),
    let nflCitation = InlineCitationAttachment(citationData: nflCitationData, citationConfig: .default),
    let espnCitationData = CitationCoder.default.decode(
      linkDestination: "http://example.com?citationMarker=9F742443&citationTitle=ESPN&citationA11yValue=ESPN%20Sports"
    ),
    let espnCitation = InlineCitationAttachment(citationData: espnCitationData, citationConfig: .default) else {
    return Text(verbatim: "Preview unavailable: Citation creation failed")
  }

  // Create cells with ONLY citations (no other text) to test alignment
  let nflOnlyCell = NSMutableAttributedString(attachment: nflCitation)
  let espnOnlyCell = NSMutableAttributedString(attachment: espnCitation)

  let headings = [
    NSMutableAttributedString(string: "Citation Only"),
    NSMutableAttributedString(string: "Status")
  ]

  let rows = [
    [nflOnlyCell, NSMutableAttributedString(string: "NFL citation alignment test")],
    [espnOnlyCell, NSMutableAttributedString(string: "ESPN citation alignment test")],
    [NSMutableAttributedString(string: "Regular text"), NSMutableAttributedString(string: "Standard text alignment")]
  ]

  return VStack {
    TableView(
      headings: headings,
      rows: rows
    )
    Spacer()
  }
})

#Preview("Citation Only - Dark Mode", body: {
  // Create citation with NFL (known rendering issue case)
  guard let nflCitationData = CitationCoder.default.decode(
    linkDestination: "http://example.com?citationMarker=9F742443&citationTitle=NFL&citationA11yValue=National%20Football%20League"
  ),
    let nflCitation = InlineCitationAttachment(citationData: nflCitationData, citationConfig: .default),
    let espnCitationData = CitationCoder.default.decode(
      linkDestination: "http://example.com?citationMarker=9F742443&citationTitle=ESPN&citationA11yValue=ESPN%20Sports"
    ),
    let espnCitation = InlineCitationAttachment(citationData: espnCitationData, citationConfig: .default) else {
    return Text(verbatim: "Preview unavailable: Citation creation failed")
      .preferredColorScheme(.dark)
  }

  // Create cells with ONLY citations (no other text) to test alignment
  let nflOnlyCell = NSMutableAttributedString(attachment: nflCitation)
  let espnOnlyCell = NSMutableAttributedString(attachment: espnCitation)

  let headings = [
    NSMutableAttributedString(string: "Citation Only"),
    NSMutableAttributedString(string: "Status")
  ]

  let rows = [
    [nflOnlyCell, NSMutableAttributedString(string: "NFL citation alignment test")],
    [espnOnlyCell, NSMutableAttributedString(string: "ESPN citation alignment test")],
    [NSMutableAttributedString(string: "Regular text"), NSMutableAttributedString(string: "Standard text alignment")]
  ]

  return VStack {
    TableView(
      headings: headings,
      rows: rows
    )
    Spacer()
  }
  .preferredColorScheme(.dark)
})

#Preview("Table with Mixed Content", body: {
  // Create citation safely (NO LaTeX to avoid iosMath bundle issues)
  guard let citationData = CitationCoder.default.decode(
    linkDestination: "http://example.com?citationMarker=9F742443&citationTitle=Source&citationA11yValue=Primary%20Source"
  ),
    let citation = InlineCitationAttachment(citationData: citationData, citationConfig: .default) else {
    return Text(verbatim: "Preview unavailable: Citation creation failed")
  }

  // Create content with citation, bold text, and links (NO LaTeX)
  let complexContent = NSMutableAttributedString(string: "Results from ")
  complexContent.append(NSAttributedString(attachment: citation))
  complexContent.append(NSAttributedString(string: " show "))

  let boldText = NSAttributedString(string: "significant improvement", attributes: [
    .font: UIFont.boldSystemFont(ofSize: 14)
  ])
  complexContent.append(boldText)

  // Add regular link instead of LaTeX
  if let docURL = URL(string: "https://example.com") {
    complexContent.append(NSAttributedString(string: " and see "))
    let linkText = NSAttributedString(string: "documentation", attributes: [
      .link: docURL,
      .foregroundColor: UIColor.systemBlue
    ])
    complexContent.append(linkText)
    complexContent.append(NSAttributedString(string: " for details."))
  }

  let headings = [
    NSMutableAttributedString(string: "Summary"),
    NSMutableAttributedString(string: "Status")
  ]

  let rows = [
    [complexContent, NSMutableAttributedString(string: "Complete")],
    [NSMutableAttributedString(string: "Standard text only"), NSMutableAttributedString(string: "Pending")]
  ]

  return VStack {
    TableView(
      headings: headings,
      rows: rows
    )
    Spacer()
  }
})

#Preview("Mixed Content - Dark Mode", body: {
  // Create citation safely (NO LaTeX to avoid iosMath bundle issues)
  guard let citationData = CitationCoder.default.decode(
    linkDestination: "http://example.com?citationMarker=9F742443&citationTitle=Source&citationA11yValue=Primary%20Source"
  ),
    let citation = InlineCitationAttachment(citationData: citationData, citationConfig: .default) else {
    return Text(verbatim: "Preview unavailable: Citation creation failed")
      .preferredColorScheme(.dark)
  }

  // Create content with citation, bold text, and links (NO LaTeX)
  let complexContent = NSMutableAttributedString(string: "Results from ")
  complexContent.append(NSAttributedString(attachment: citation))
  complexContent.append(NSAttributedString(string: " show "))

  let boldText = NSAttributedString(string: "significant improvement", attributes: [
    .font: UIFont.boldSystemFont(ofSize: 14)
  ])
  complexContent.append(boldText)

  // Add regular link instead of LaTeX
  if let docURL = URL(string: "https://example.com") {
    complexContent.append(NSAttributedString(string: " and see "))
    let linkText = NSAttributedString(string: "documentation", attributes: [
      .link: docURL,
      .foregroundColor: UIColor.systemBlue
    ])
    complexContent.append(linkText)
    complexContent.append(NSAttributedString(string: " for details."))
  }

  let headings = [
    NSMutableAttributedString(string: "Summary"),
    NSMutableAttributedString(string: "Status")
  ]

  let rows = [
    [complexContent, NSMutableAttributedString(string: "Complete")],
    [NSMutableAttributedString(string: "Standard text only"), NSMutableAttributedString(string: "Pending")]
  ]

  return VStack {
    TableView(
      headings: headings,
      rows: rows
    )
    Spacer()
  }
  .preferredColorScheme(.dark)
})

#Preview("Compact Table", body: {
  let headings = [
    NSMutableAttributedString(string: "Item"),
    NSMutableAttributedString(string: "Value")
  ]

  let rows = [
    [NSMutableAttributedString(string: "Price"), NSMutableAttributedString(string: "$29.99")],
    [NSMutableAttributedString(string: "Tax"), NSMutableAttributedString(string: "$2.40")],
    [NSMutableAttributedString(string: "Total"), NSMutableAttributedString(string: "$32.39")]
  ]

  return VStack {
    TableView(
      headings: headings,
      rows: rows
    )
    Spacer()
  }
})

#endif

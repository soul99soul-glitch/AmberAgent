import SwiftUI
import SwiftStreamingMarkdown
import UIKit
import Shared

/// Visual treatment for rendered Markdown.
/// - `.standard`: chat-grade defaults (system font, tight spacing).
/// - `.magazine`: deep-read reader — serif body, generous line/paragraph
///   rhythm, larger serif headings, accented pull-quotes.
enum MarkdownStyle {
    case standard
    case magazine
}

private struct AmberMarkdownParseResult {
    let children: [PackedAstNode]
    let failed: Bool
}

private final class AmberMarkdownParseResultBox {
    let value: AmberMarkdownParseResult

    init(_ value: AmberMarkdownParseResult) {
        self.value = value
    }
}

private final class AmberMarkdownAstCache: @unchecked Sendable {
    static let shared = AmberMarkdownAstCache()

    private let cache = NSCache<NSString, AmberMarkdownParseResultBox>()

    private init() {
        cache.countLimit = 200
    }

    func result(for markdown: String) -> AmberMarkdownParseResult {
        let key = markdown as NSString
        if let cached = cache.object(forKey: key) {
            return cached.value
        }

        let result: AmberMarkdownParseResult
        if let data = MarkdownBridge.parse(markdown),
           let reader = PackedAstReader(data: data),
           let root = reader.root() {
            result = AmberMarkdownParseResult(children: root.children, failed: false)
        } else {
            result = AmberMarkdownParseResult(children: [], failed: true)
        }
        cache.setObject(AmberMarkdownParseResultBox(result), forKey: key)
        return result
    }
}

enum AmberMarkdownMath {
    static func blockLatex(from node: PackedAstNode, source: String) -> String {
        let raw = sliceSource(source, start: node.startOffset, end: node.endOffset)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let unwrapped: String

        if raw.hasPrefix("$$"), raw.hasSuffix("$$"), raw.count >= 4 {
            unwrapped = String(raw.dropFirst(2).dropLast(2))
        } else if raw.hasPrefix("\\["), raw.hasSuffix("\\]"), raw.count >= 4 {
            unwrapped = String(raw.dropFirst(2).dropLast(2))
        } else {
            unwrapped = raw
        }

        return unwrapped.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func sliceSource(_ source: String, start: Int, end: Int) -> String {
        guard start < end else { return "" }
        guard let startIndex = source.utf8.index(
            source.utf8.startIndex,
            offsetBy: start,
            limitedBy: source.utf8.endIndex
        ), let endIndex = source.utf8.index(
            source.utf8.startIndex,
            offsetBy: end,
            limitedBy: source.utf8.endIndex
        ) else {
            return ""
        }
        return String(source[startIndex..<endIndex])
    }
}

private extension NodeType {
    /// Block-level nodes get their own layout; everything else is inline content that
    /// must be coalesced into a flowing Text (see `renderListItemContent`).
    var isBlockLevel: Bool {
        switch self {
        case .paragraph, .heading, .blockquote, .codeBlock,
             .listOrdered, .listUnordered, .listItem,
             .table, .tableHead, .tableRow, .tableCell,
             .horizontalRule, .htmlBlock, .mathBlock:
            return true
        default:
            return false
        }
    }
}

/// Two-pass table layout (same algorithm as the vendored SwiftStreamingMarkdown
/// `TableLayout`): pass 1 resolves each column width from the cells' unconstrained
/// ideal widths (capped at `maxColumnWidth`); pass 2 resolves each row height by
/// re-measuring every cell at its final column width, so wrapped multi-line cells
/// report their true height and rows never overlap.
///
/// Subviews must be supplied in row-major order with exactly
/// `columnCount` cells per row (header row first).
private struct AmberTableLayout: Layout {
    struct CacheData {
        let columnWidths: [CGFloat]
        let rowHeights: [CGFloat]
    }

    let columnCount: Int

    private let maxColumnWidth: CGFloat = 300
    private let defaultRowHeight: CGFloat = 44

    func makeCache(subviews: Subviews) -> CacheData {
        guard columnCount > 0, !subviews.isEmpty else {
            return CacheData(columnWidths: [], rowHeights: [])
        }
        let rowCount = (subviews.count + columnCount - 1) / columnCount

        // Pass 1: column widths from unconstrained ideal sizes, capped.
        var columnWidths = Array(repeating: CGFloat(0), count: columnCount)
        for row in 0..<rowCount {
            for col in 0..<columnCount {
                let index = row * columnCount + col
                guard index < subviews.count else { break }
                let size = subviews[index].sizeThatFits(.unspecified)
                columnWidths[col] = min(max(columnWidths[col], size.width), maxColumnWidth)
            }
        }

        // Pass 2: row heights re-measured at the resolved column widths.
        var rowHeights = Array(repeating: CGFloat(0), count: rowCount)
        for row in 0..<rowCount {
            var rowHeight: CGFloat = 0
            for col in 0..<columnCount {
                let index = row * columnCount + col
                guard index < subviews.count else { break }
                let height = subviews[index]
                    .sizeThatFits(ProposedViewSize(width: columnWidths[col], height: nil))
                    .height
                rowHeight = max(rowHeight, height.isFinite ? height : defaultRowHeight)
            }
            rowHeights[row] = rowHeight
        }

        return CacheData(columnWidths: columnWidths, rowHeights: rowHeights)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout CacheData) -> CGSize {
        CGSize(
            width: cache.columnWidths.reduce(0, +),
            height: cache.rowHeights.reduce(0, +)
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout CacheData) {
        guard columnCount > 0, bounds.origin.y.isFinite else { return }
        var y = bounds.minY
        for row in 0..<cache.rowHeights.count {
            var x = bounds.minX.isNaN ? 0 : bounds.minX
            let rowHeight = cache.rowHeights[row]
            for col in 0..<columnCount {
                let index = row * columnCount + col
                guard index < subviews.count else { break }
                subviews[index].place(
                    at: CGPoint(x: x, y: y),
                    anchor: .topLeading,
                    proposal: ProposedViewSize(width: cache.columnWidths[col], height: rowHeight)
                )
                x += cache.columnWidths[col]
            }
            y += rowHeight
        }
    }
}

struct AmberMarkdownView: View {
    let markdown: String
    var displaySetting: DisplaySetting? = nil
    var style: MarkdownStyle = .standard

    var body: some View {
        let resolved = AmberMarkdownAstCache.shared.result(for: markdown)
        Group {
            if resolved.failed || resolved.children.isEmpty {
                Text(markdown)
                    .font(.body)
            } else {
                blockStack(resolved.children, source: markdown)
            }
        }
        // UIHostingConfiguration cell self-sizing 会以无约束提案询问理想尺寸,
        // 裸 Text 按"理想单行宽度"报高 → 多行段落被按一行计高,历史行高度系统性
        // 低估、内容被裁(与 ChatMessageListSupport.swift:90-94 修过的 user 气泡
        // 单行化是同一个坑)。fixedSize(h:false,v:true) 让文本在提案宽度内折行、
        // 垂直按完整理想高度参与测量。
        .frame(maxWidth: .infinity, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - Block Rendering

    /// Renders block-level children in a VStack. Uses AnyView to avoid opaque-type-inference recursion.
    private func blockStack(_ nodes: [PackedAstNode], source: String) -> some View {
        VStack(alignment: .leading, spacing: style == .magazine ? 16 : 8) {
            ForEach(nodes) { node in
                renderBlock(node, source: source)
            }
        }
    }

    /// Single entry point for rendering any node. Returns AnyView to break recursive opaque-type chains.
    private func renderBlock(_ node: PackedAstNode, source: String) -> AnyView {
        switch node.type {
        case .paragraph:
            let t = buildInlineText(node.children, source: source)
            if style == .magazine {
                return AnyView(
                    t.font(.system(size: 17, design: .serif))
                        .lineSpacing(7)
                        .frame(maxWidth: .infinity, alignment: .leading)
                )
            }
            return AnyView(t)

        case .heading:
            let t = renderHeading(node, source: source)
            if style == .magazine {
                // Extra air above headings establishes the magazine section rhythm.
                return AnyView(t.padding(.top, 10).frame(maxWidth: .infinity, alignment: .leading))
            }
            return AnyView(t)

        case .codeBlock:
            return AnyView(renderCodeBlock(node, source: source))

        case .blockquote:
            return AnyView(renderBlockquote(node, source: source))

        case .listUnordered:
            return AnyView(renderUnorderedList(node, source: source))

        case .listOrdered:
            return AnyView(renderOrderedList(node, source: source))

        case .horizontalRule:
            return AnyView(Divider())

        case .table:
            return AnyView(renderTable(node, source: source))

        case .listItem:
            return renderListItemContent(node, source: source)

        case .mathBlock:
            let latex = AmberMarkdownMath.blockLatex(from: node, source: source)
            guard !latex.isEmpty else {
                return renderDefaultBlock(node, source: source)
            }
            return AnyView(
                BlockMathView(
                    latex: latex,
                    color: .primary,
                    pointSize: UIFont.preferredFont(forTextStyle: .body).pointSize
                )
                .frame(maxWidth: .infinity, alignment: .leading)
            )

        default:
            return renderDefaultBlock(node, source: source)
        }
    }

    private func renderDefaultBlock(_ node: PackedAstNode, source: String) -> AnyView {
        if node.children.isEmpty {
            let raw = sliceSource(source, start: node.startOffset, end: node.endOffset)
            return AnyView(raw.isEmpty ? AnyView(EmptyView()) : AnyView(Text(raw).font(.body)))
        } else {
            return AnyView(buildInlineText(node.children, source: source))
        }
    }

    // MARK: - Heading

    private func renderHeading(_ node: PackedAstNode, source: String) -> Text {
        let level = node.headingLevel() ?? 1
        let sizes: [CGFloat] = style == .magazine
            ? [30, 23, 19, 17, 16, 15]
            : [28, 24, 20, 18, 16, 14]
        let size = sizes[max(0, min(5, level - 1))]
        let design: Font.Design = style == .magazine ? .serif : .default
        return buildInlineText(node.children, source: source)
            .font(.system(size: size, weight: .bold, design: design))
    }

    // MARK: - Code Block

    @State private var codeBlockExpanded: Set<String> = []

    private func renderCodeBlock(_ node: PackedAstNode, source: String) -> some View {
        let code = sliceSource(source, start: node.startOffset, end: node.endOffset)
        let lang = node.codeLang()
        let autoWrap = displaySetting?.codeBlockAutoWrap ?? true
        let autoCollapse = displaySetting?.codeBlockAutoCollapse ?? false
        let blockId = "\(node.startOffset)-\(node.endOffset)"
        let isExpanded = codeBlockExpanded.contains(blockId) || !autoCollapse
        let shouldCollapse = autoCollapse && code.count > 500 && !isExpanded

        return VStack(alignment: .leading, spacing: 0) {
            if let lang, !lang.isEmpty {
                HStack {
                    Text(lang)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    if ["svg", "html"].contains(lang.lowercased()) {
                        WidgetCodePreviewButton(code: code)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)
                .padding(.bottom, 4)
            }
            if shouldCollapse {
                Text(String(code.prefix(300)))
                    .font(.system(.body, design: .monospaced))
                    .lineLimit(8)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    codeBlockExpanded.insert(blockId)
                } label: {
                    Text("展开（共 \(code.count) 字符）")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                        .padding(.bottom, 8)
                        .padding(.leading, 12)
                }
                .buttonStyle(.plain)
            } else if autoWrap {
                Text(code)
                    .font(.system(.body, design: .monospaced))
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                // 不换行时必须自己横滑；裸 fixedSize(horizontal:true) 会把外层
                // ScrollView 内容宽撑破，触发聊天列左右对称裁切。
                ScrollView(.horizontal, showsIndicators: false) {
                    Text(code)
                        .font(.system(.body, design: .monospaced))
                        .fixedSize(horizontal: true, vertical: false)
                        .padding(12)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - Table

    /// GFM table AST shape (mirrors pulldown-cmark's event stream, see
    /// native/markdown-parser/src/tree_builder.rs): `.table` has one `.tableHead`
    /// child (whose children are `.tableCell` directly) followed by zero or more
    /// `.tableRow` children (whose children are also `.tableCell` directly —
    /// cells are never wrapped in an extra row node inside `.tableHead`).
    private func renderTable(_ node: PackedAstNode, source: String) -> AnyView {
        var headerCells: [PackedAstNode] = []
        var bodyRows: [[PackedAstNode]] = []
        for child in node.children {
            switch child.type {
            case .tableHead:
                headerCells = child.children.filter { $0.type == .tableCell }
            case .tableRow:
                bodyRows.append(child.children.filter { $0.type == .tableCell })
            default:
                break
            }
        }

        // Malformed/empty table: fall back to the default inline-text flattening
        // rather than rendering an empty box.
        guard !headerCells.isEmpty else {
            return AnyView(buildInlineText(node.children, source: source))
        }

        let columnCount = headerCells.count

        return AnyView(
            ScrollView(.horizontal, showsIndicators: false) {
                // Subviews are emitted in row-major order (header row first); the
                // layout maps index i to (row: i / columnCount, col: i % columnCount).
                AmberTableLayout(columnCount: columnCount) {
                    ForEach(Array(headerCells.enumerated()), id: \.offset) { _, cell in
                        renderTableCell(
                            cell, source: source,
                            isHeader: true, isLastRow: bodyRows.isEmpty
                        )
                    }
                    ForEach(Array(bodyRows.enumerated()), id: \.offset) { rowIndex, row in
                        // Pad/trim ragged rows so every layout row contributes exactly
                        // `columnCount` subviews and the index arithmetic stays valid.
                        ForEach(0..<columnCount, id: \.self) { colIndex in
                            renderTableCell(
                                colIndex < row.count ? row[colIndex] : nil, source: source,
                                isHeader: false, isLastRow: rowIndex == bodyRows.count - 1
                            )
                        }
                    }
                }
                .background(Color(.systemGray6).opacity(0.4))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(.separator), lineWidth: 0.5))
            }
            // 限制在列宽内横滑；否则宽表 ideal width 会撑破外层聊天 ScrollView。
            .frame(maxWidth: .infinity, alignment: .leading)
        )
    }

    private func renderTableCell(
        _ cell: PackedAstNode?, source: String, isHeader: Bool, isLastRow: Bool
    ) -> some View {
        // No fixedSize / maxWidth here: AmberTableLayout measures each cell at the
        // resolved column width and proposes (columnWidth, rowHeight), so the Text
        // wraps naturally and never gets truncated or overlapped.
        buildInlineText(cell?.children ?? [], source: source)
            .font(isHeader ? .subheadline.weight(.semibold) : .body)
            .multilineTextAlignment(.leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(isHeader ? Color(.systemGray6) : Color.clear)
            .overlay(alignment: .bottom) {
                if !isLastRow {
                    // Per-cell bottom hairline; adjacent cells join into a full row rule.
                    Rectangle()
                        .fill(Color(.separator))
                        .frame(height: 0.5)
                }
            }
    }

    // MARK: - Blockquote

    private func renderBlockquote(_ node: PackedAstNode, source: String) -> some View {
        let isMagazine = style == .magazine
        return HStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 2)
                .fill(isMagazine ? AmberTheme.accent.opacity(0.55) : Color.secondary.opacity(0.4))
                .frame(width: isMagazine ? 3 : 4)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(node.children) { child in
                    renderBlock(child, source: source)
                }
            }
            .padding(.leading, isMagazine ? 16 : 12)
            .padding(.vertical, isMagazine ? 6 : 0)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, isMagazine ? 4 : 0)
        .background {
            if isMagazine {
                RoundedRectangle(cornerRadius: 8)
                    .fill(AmberTheme.accent.opacity(0.05))
            }
        }
    }

    // MARK: - Lists

    private func renderUnorderedList(_ node: PackedAstNode, source: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(node.children.enumerated()), id: \.offset) { _, child in
                HStack(alignment: .top, spacing: 8) {
                    Text("\u{2022}")
                    renderListItemContent(child, source: source)
                }
            }
        }
    }

    private func renderOrderedList(_ node: PackedAstNode, source: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(node.children.enumerated()), id: \.offset) { index, child in
                HStack(alignment: .top, spacing: 8) {
                    Text("\(index + 1).")
                    renderListItemContent(child, source: source)
                }
            }
        }
    }

    private func renderListItemContent(_ node: PackedAstNode, source: String) -> AnyView {
        let children = node.children.filter { $0.type != .taskListMarker }
        // Loose item (all paragraphs) → flatten every paragraph's inline runs into one
        // flowing Text.
        if !children.isEmpty, children.allSatisfy({ $0.type == .paragraph }) {
            return AnyView(buildInlineText(children.flatMap(\.children), source: source))
        }
        // Tight item: the parser puts inline content (text/strong/emphasis/link/breaks)
        // DIRECTLY under the listItem with no paragraph wrapper. Rendering each inline
        // node as its own block put every fragment — and each `[1]` / `**` token — on a
        // separate line. Coalesce consecutive inline nodes into ONE flowing Text; keep
        // genuine block children (nested lists, code) as their own blocks.
        let segments = listItemSegments(children)
        if segments.count == 1, case .inline(let nodes) = segments[0] {
            return AnyView(buildInlineText(nodes, source: source))
        }
        return AnyView(
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(segments.enumerated()), id: \.offset) { _, segment in
                    switch segment {
                    case .inline(let nodes):
                        buildInlineText(nodes, source: source)
                    case .block(let blockNode):
                        renderBlock(blockNode, source: source)
                    }
                }
            }
        )
    }

    private enum ListItemSegment {
        case inline([PackedAstNode])
        case block(PackedAstNode)
    }

    /// Split list-item children into consecutive inline runs and block nodes, so a run
    /// of inline content renders as one flowing Text while genuine block children keep
    /// their own layout.
    private func listItemSegments(_ children: [PackedAstNode]) -> [ListItemSegment] {
        var segments: [ListItemSegment] = []
        var inlineRun: [PackedAstNode] = []
        func flushInline() {
            if !inlineRun.isEmpty {
                segments.append(.inline(inlineRun))
                inlineRun.removeAll()
            }
        }
        for child in children {
            if child.type.isBlockLevel {
                flushInline()
                segments.append(.block(child))
            } else {
                inlineRun.append(child)
            }
        }
        flushInline()
        return segments
    }

    // MARK: - Inline Text Concatenation

    /// Build a SwiftUI `Text` by constructing an AttributedString from inline children.
    /// Avoids the deprecated `Text + Text` operator (deprecated in iOS 26).
    private func buildInlineText(_ nodes: [PackedAstNode], source: String) -> Text {
        Text(buildInlineAttrString(nodes, source: source))
    }

    /// Render a single inline node into an AttributedString fragment.
    private func renderInlineAttr(_ node: PackedAstNode, source: String) -> AttributedString? {
        switch node.type {
        case .text:
            let raw = sliceSource(source, start: node.startOffset, end: node.endOffset)
            guard !raw.isEmpty else { return nil }
            return AttributedString(raw)

        case .softBreak, .hardBreak:
            // Handled with neighbour context in buildInlineAttrString's loop so a soft
            // break can collapse to a space (or nothing, between CJK) instead of a hard
            // newline. Returning nil here keeps the default branch from slicing the raw
            // "\n" back in.
            return nil

        case .emphasis:
            var result = buildInlineAttrString(node.children, source: source)
            // Use a presentation intent (not an absolute font) so italic composes with
            // the resolved base font — including the magazine serif and heading sizes.
            result.inlinePresentationIntent = .emphasized
            return result

        case .strong:
            var result = buildInlineAttrString(node.children, source: source)
            result.inlinePresentationIntent = .stronglyEmphasized
            return result

        case .strikethrough:
            var result = buildInlineAttrString(node.children, source: source)
            result.strikethroughStyle = .single
            return result

        case .inlineCode:
            let raw = sliceSource(source, start: node.startOffset, end: node.endOffset)
            guard !raw.isEmpty else { return nil }
            var result = AttributedString(raw)
            result.font = .system(.body, design: .monospaced)
            return result

        case .link:
            var result = buildInlineAttrString(node.children, source: source)
            result.foregroundColor = .blue
            result.underlineStyle = .single
            if let urlString = node.linkHref(), let url = safeExternalURL(from: urlString) {
                result.link = url
            }
            return result

        case .image:
            let alt = sliceSource(source, start: node.startOffset, end: node.endOffset)
            return AttributedString("[\(alt)]")

        default:
            if !node.children.isEmpty {
                return buildInlineAttrString(node.children, source: source)
            }
            let raw = sliceSource(source, start: node.startOffset, end: node.endOffset)
            guard !raw.isEmpty else { return nil }
            return AttributedString(raw)
        }
    }

    private func safeExternalURL(from raw: String) -> URL? {
        ChatMarkdownOpenURLPolicy.url(from: raw)
    }

    /// Build a plain AttributedString by concatenating inline children.
    ///
    /// Soft breaks (single newlines inside a paragraph) are NOT hard line breaks.
    /// CommonMark renders them as a space; for CJK text a space mid-sentence reads
    /// wrong, so we only insert a space when the preceding character is non-CJK and
    /// join with nothing between CJK characters. This stops model-wrapped prose — and
    /// inline citations like `[1]` — from being shattered onto one fragment per line.
    private func buildInlineAttrString(_ nodes: [PackedAstNode], source: String) -> AttributedString {
        var attrStr = AttributedString()
        for node in nodes {
            switch node.type {
            case .softBreak:
                if let last = attrStr.characters.last, !last.isWhitespace, !isCJK(last) {
                    attrStr.append(AttributedString(" "))
                }
            case .hardBreak:
                attrStr.append(AttributedString("\n"))
            default:
                if let part = renderInlineAttr(node, source: source) {
                    attrStr.append(part)
                }
            }
        }
        return attrStr
    }

    /// Whether a character belongs to a CJK script (or CJK/fullwidth punctuation), used
    /// to decide that a soft break between such characters needs no joining space.
    private func isCJK(_ c: Character) -> Bool {
        for scalar in c.unicodeScalars {
            switch scalar.value {
            case 0x3000...0x303F,   // CJK symbols & punctuation
                 0x3040...0x30FF,   // Hiragana + Katakana
                 0x3400...0x4DBF,   // CJK Unified Ideographs Ext A
                 0x4E00...0x9FFF,   // CJK Unified Ideographs
                 0xF900...0xFAFF,   // CJK Compatibility Ideographs
                 0xFF00...0xFFEF:   // Halfwidth & Fullwidth forms
                return true
            default:
                continue
            }
        }
        return false
    }

    // MARK: - Source Slicing

    /// Slice the source string using UTF-8 byte offsets from the AST.
    private func sliceSource(_ source: String, start: Int, end: Int) -> String {
        guard start < end else { return "" }
        guard let startIdx = source.utf8.index(
            source.utf8.startIndex, offsetBy: start, limitedBy: source.utf8.endIndex
        ), let endIdx = source.utf8.index(
            source.utf8.startIndex, offsetBy: end, limitedBy: source.utf8.endIndex
        ) else {
            return ""
        }
        return String(source[startIdx..<endIdx])
    }
}

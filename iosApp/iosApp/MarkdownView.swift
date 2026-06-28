import SwiftUI
import Shared

/// Visual treatment for rendered Markdown.
/// - `.standard`: chat-grade defaults (system font, tight spacing).
/// - `.magazine`: deep-read reader — serif body, generous line/paragraph
///   rhythm, larger serif headings, accented pull-quotes.
enum MarkdownStyle {
    case standard
    case magazine
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

struct MarkdownView: View {
    let markdown: String
    var displaySetting: DisplaySetting? = nil
    var style: MarkdownStyle = .standard

    /// Cache the parsed AST to avoid re-parsing on every SwiftUI body evaluation.
    /// Only re-parses when `markdown` content changes.
    @State private var cachedMarkdown: String = ""
    @State private var cachedChildren: [PackedAstNode] = []
    @State private var cachedSource: String = ""
    @State private var parseFailed: Bool = false

    var body: some View {
        Group {
            if parseFailed || cachedChildren.isEmpty {
                Text(markdown)
                    .font(.body)
            } else {
                blockStack(cachedChildren, source: markdown)
            }
        }
        .onChange(of: markdown) { _, newMarkdown in
            parseMarkdown(newMarkdown)
        }
        .onAppear {
            parseMarkdown(markdown)
        }
    }

    private func parseMarkdown(_ md: String) {
        guard md != cachedMarkdown else { return }
        cachedMarkdown = md
        if let data = MarkdownBridge.parse(md),
           let reader = PackedAstReader(data: data),
           let root = reader.root() {
            cachedChildren = root.children
            cachedSource = md
            parseFailed = false
        } else {
            cachedChildren = []
            cachedSource = md
            parseFailed = true
        }
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

        case .listItem:
            return renderListItemContent(node, source: source)

        default:
            if node.children.isEmpty {
                let raw = sliceSource(source, start: node.startOffset, end: node.endOffset)
                return AnyView(raw.isEmpty ? AnyView(EmptyView()) : AnyView(Text(raw).font(.body)))
            } else {
                return AnyView(buildInlineText(node.children, source: source))
            }
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
            } else {
                Text(code)
                    .font(.system(.body, design: .monospaced))
                    .lineLimit(autoWrap ? nil : nil)
                    .fixedSize(horizontal: !autoWrap, vertical: false)
                    .padding(12)
                    .frame(maxWidth: autoWrap ? .infinity : nil, alignment: .leading)
            }
        }
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 8))
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
            if let urlString = node.linkHref(), let url = URL(string: urlString) {
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

import SwiftUI

struct MarkdownView: View {
    let markdown: String

    var body: some View {
        if let data = MarkdownBridge.parse(markdown),
           let reader = PackedAstReader(data: data),
           let root = reader.root() {
            blockStack(root.children, source: markdown)
        } else {
            Text(markdown)
                .font(.body)
        }
    }

    // MARK: - Block Rendering

    /// Renders block-level children in a VStack. Uses AnyView to avoid opaque-type-inference recursion.
    private func blockStack(_ nodes: [PackedAstNode], source: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
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
            return AnyView(t)

        case .heading:
            let t = renderHeading(node, source: source)
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
        let sizes: [CGFloat] = [28, 24, 20, 18, 16, 14]
        let size = sizes[max(0, min(5, level - 1))]
        return buildInlineText(node.children, source: source)
            .font(.system(size: size, weight: .bold))
    }

    // MARK: - Code Block

    private func renderCodeBlock(_ node: PackedAstNode, source: String) -> some View {
        let code = sliceSource(source, start: node.startOffset, end: node.endOffset)
        let lang = node.codeLang()
        return VStack(alignment: .leading, spacing: 0) {
            if let lang, !lang.isEmpty {
                Text(lang)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
                    .padding(.bottom, 4)
            }
            Text(code)
                .font(.system(.body, design: .monospaced))
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - Blockquote

    private func renderBlockquote(_ node: PackedAstNode, source: String) -> some View {
        HStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 2)
                .fill(Color.secondary.opacity(0.4))
                .frame(width: 4)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(node.children) { child in
                    renderBlock(child, source: source)
                }
            }
            .padding(.leading, 12)
            .foregroundStyle(.secondary)
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
        let blockChildren = node.children.filter { $0.type != .taskListMarker }
        if blockChildren.allSatisfy({ $0.type == .paragraph }) {
            let inlineNodes = blockChildren.flatMap(\.children)
            return AnyView(buildInlineText(inlineNodes, source: source))
        } else if !blockChildren.isEmpty {
            return AnyView(
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(blockChildren) { child in
                        renderBlock(child, source: source)
                    }
                }
            )
        } else {
            return AnyView(buildInlineText(node.children, source: source))
        }
    }

    // MARK: - Inline Text Concatenation

    /// Build a SwiftUI `Text` by concatenating inline children with modifiers.
    private func buildInlineText(_ nodes: [PackedAstNode], source: String) -> Text {
        var parts = [Text]()
        for node in nodes {
            if let t = renderInline(node, source: source) {
                parts.append(t)
            }
        }
        if parts.isEmpty { return Text("") }
        return parts.dropFirst().reduce(parts[0]) { $0 + $1 }
    }

    /// Render a single inline node into a SwiftUI `Text`, or nil if empty.
    private func renderInline(_ node: PackedAstNode, source: String) -> Text? {
        switch node.type {
        case .text:
            let raw = sliceSource(source, start: node.startOffset, end: node.endOffset)
            guard !raw.isEmpty else { return nil }
            return Text(raw)

        case .softBreak, .hardBreak:
            return Text("\n")

        case .emphasis:
            return buildInlineText(node.children, source: source).italic()

        case .strong:
            return buildInlineText(node.children, source: source).bold()

        case .strikethrough:
            return buildInlineText(node.children, source: source).strikethrough()

        case .inlineCode:
            let raw = sliceSource(source, start: node.startOffset, end: node.endOffset)
            guard !raw.isEmpty else { return nil }
            return Text(raw).font(.system(.body, design: .monospaced))

        case .link:
            return buildInlineText(node.children, source: source)
                .foregroundColor(.blue)
                .underline(true)

        case .image:
            let alt = sliceSource(source, start: node.startOffset, end: node.endOffset)
            return Text("[\(alt)]")

        default:
            if !node.children.isEmpty {
                return buildInlineText(node.children, source: source)
            }
            let raw = sliceSource(source, start: node.startOffset, end: node.endOffset)
            guard !raw.isEmpty else { return nil }
            return Text(raw)
        }
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

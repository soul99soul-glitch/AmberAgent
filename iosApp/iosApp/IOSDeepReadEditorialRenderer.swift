import Foundation
import Shared

/// Renders a Deep Read article as a self-contained HTML "editorial" magazine
/// page for the WKWebView reader — a Swift port of the Android
/// `DeepReadTemplateRenderer.renderEditorialSlant` plus its BASE / runtime / dark
/// CSS (kept verbatim so both platforms share one visual language).
///
/// Shell-first scope: a magazine headline (with an OPTIONAL diagonal hero image —
/// the `.hero-cut` clip-path slant) over a magazine-typeset Markdown body, plus a
/// sources list. The Markdown is parsed by the SAME `MarkdownBridge` the SwiftUI
/// `MarkdownView` uses, then walked to HTML here (no second parser, no drift).
/// The structured timeline / core-points / diagram cards Android renders from a
/// typed `DeepReadOutput` are a later increment (their CSS is already included).
enum IOSDeepReadEditorialRenderer {

    struct SourceLink {
        let title: String
        let url: String
        var source: String? = nil
    }

    struct Input {
        let title: String
        let markdown: String
        var kicker: String = "DEEP READ"
        /// When non-nil/non-empty, renders the diagonal hero figure. Degrades to a
        /// kicker headline when absent.
        var heroImageURL: String? = nil
        var heroCaption: String? = nil
        var sourceLabel: String? = nil
        var sources: [SourceLink] = []
        var dark: Bool = false
    }

    // MARK: - Entry point

    static func renderHTML(_ input: Input) -> String {
        let body = markdownToHTML(stripLeadingH1(input.markdown))
        let hero = input.heroImageURL?.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasHero = !(hero ?? "").isEmpty

        var b = "<!doctype html><html><head>"
        b += #"<meta name="viewport" content="width=device-width, initial-scale=1"/>"#
        b += "<style>\n"
        b += defaultFontCSS + "\n" + baseCSS + "\n" + runtimeCSS + "\n"
        // After the base/runtime CSS so the :root + code overrides win the cascade.
        b += bundledFontCSS + "\n"
        if input.dark { b += darkCSS + "\n" }
        b += emptyImageFallbackCSS + "\n</style></head><body><article>"

        if hasHero, let hero {
            b += #"<figure class="hero"><img src=""# + esc(hero) + #""/><div class="hero-cut"><div>"#
            b += #"<span class="hero-type">"# + esc(input.kicker) + "</span>"
            if let sl = input.sourceLabel, !sl.isEmpty {
                b += #"<span class="hero-source">"# + esc(sl) + "</span>"
            }
            b += "</div>"
            if let cap = input.heroCaption, !cap.isEmpty {
                b += "<figcaption>" + esc(cap) + "</figcaption>"
            }
            b += "</div></figure>"
        }

        b += #"<section class="headline">"#
        if !hasHero {
            b += #"<p class="kicker">"# + esc(input.kicker) + "</p>"
        }
        b += "<h1>" + esc(input.title) + "</h1></section>"

        b += #"<section><div class="markdown-body">"# + body + "</div></section>"

        if !input.sources.isEmpty {
            b += #"<section><p class="section">来源</p>"#
            for s in input.sources where !s.url.isEmpty {
                b += #"<a class="reading-link" href=""# + esc(s.url) + #"">"#
                b += "<p>" + esc(s.title) + "</p>"
                if let src = s.source, !src.isEmpty {
                    b += "<small>" + esc(src) + "</small>"
                }
                b += "</a>"
            }
            b += "</section>"
        }

        b += "</article></body></html>"
        return b
    }

    // MARK: - Markdown -> HTML (same parser as MarkdownView)

    private static func markdownToHTML(_ md: String) -> String {
        let source = md.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !source.isEmpty else { return "" }
        guard let data = MarkdownBridge.parse(source),
              let reader = PackedAstReader(data: data),
              let root = reader.root() else {
            // Parser unavailable → degrade to escaped paragraphs split on blank lines.
            return source
                .components(separatedBy: "\n\n")
                .map { "<p>" + esc($0.trimmingCharacters(in: .whitespacesAndNewlines)) + "</p>" }
                .joined()
        }
        return root.children.map { blockHTML($0, source: source) }.joined()
    }

    private static func blockHTML(_ node: PackedAstNode, source: String) -> String {
        switch node.type {
        case .paragraph:
            return "<p>" + inlineHTML(node.children, source: source) + "</p>"
        case .heading:
            let level = min(max(node.headingLevel() ?? 2, 1), 6)
            return "<h\(level)>" + inlineHTML(node.children, source: source) + "</h\(level)>"
        case .blockquote:
            return "<blockquote>" + node.children.map { blockHTML($0, source: source) }.joined() + "</blockquote>"
        case .listUnordered:
            return "<ul>" + node.children.map { "<li>" + listItemHTML($0, source: source) + "</li>" }.joined() + "</ul>"
        case .listOrdered:
            return "<ol>" + node.children.map { "<li>" + listItemHTML($0, source: source) + "</li>" }.joined() + "</ol>"
        case .listItem:
            return "<li>" + listItemHTML(node, source: source) + "</li>"
        case .codeBlock:
            return "<pre><code>" + esc(sliceSource(source, start: node.startOffset, end: node.endOffset)) + "</code></pre>"
        case .horizontalRule:
            return "<hr/>"
        case .table:
            return tableHTML(node, source: source)
        default:
            if !node.children.isEmpty {
                return inlineHTML(node.children, source: source)
            }
            let raw = sliceSource(source, start: node.startOffset, end: node.endOffset)
            return raw.isEmpty ? "" : "<p>" + esc(raw) + "</p>"
        }
    }

    /// A list item is usually a loose paragraph or a tight run of inline nodes;
    /// coalesce inline runs and keep genuine block children (nested lists, code).
    private static func listItemHTML(_ node: PackedAstNode, source: String) -> String {
        let children = node.children.filter { $0.type != .taskListMarker }
        if !children.isEmpty, children.allSatisfy({ $0.type == .paragraph }) {
            return children.map { inlineHTML($0.children, source: source) }.joined(separator: "<br/>")
        }
        var out = ""
        var inlineRun: [PackedAstNode] = []
        func flush() {
            if !inlineRun.isEmpty { out += inlineHTML(inlineRun, source: source); inlineRun.removeAll() }
        }
        for child in children {
            if child.type.isBlockLevelForHTML {
                flush()
                out += blockHTML(child, source: source)
            } else {
                inlineRun.append(child)
            }
        }
        flush()
        return out
    }

    private static func tableHTML(_ node: PackedAstNode, source: String) -> String {
        var head = ""
        var body = ""
        for child in node.children {
            switch child.type {
            case .tableHead:
                let cells = child.children.flatMap { $0.type == .tableRow ? $0.children : [$0] }
                head += "<tr>" + cells.map { "<th>" + inlineHTML($0.children, source: source) + "</th>" }.joined() + "</tr>"
            case .tableRow:
                body += "<tr>" + child.children.map { "<td>" + inlineHTML($0.children, source: source) + "</td>" }.joined() + "</tr>"
            default:
                break
            }
        }
        var t = "<table>"
        if !head.isEmpty { t += "<thead>" + head + "</thead>" }
        if !body.isEmpty { t += "<tbody>" + body + "</tbody>" }
        return t + "</table>"
    }

    private static func inlineHTML(_ nodes: [PackedAstNode], source: String) -> String {
        var out = ""
        for node in nodes {
            switch node.type {
            case .softBreak:
                // CommonMark soft break = space; but no space between CJK characters.
                if let last = out.last, !last.isWhitespace, !isCJK(last) { out += " " }
            case .hardBreak:
                out += "<br/>"
            case .text:
                out += esc(sliceSource(source, start: node.startOffset, end: node.endOffset))
            case .emphasis:
                out += "<em>" + inlineHTML(node.children, source: source) + "</em>"
            case .strong:
                out += "<strong>" + inlineHTML(node.children, source: source) + "</strong>"
            case .strikethrough:
                out += "<s>" + inlineHTML(node.children, source: source) + "</s>"
            case .inlineCode:
                out += "<code>" + esc(sliceSource(source, start: node.startOffset, end: node.endOffset)) + "</code>"
            case .link:
                let inner = inlineHTML(node.children, source: source)
                if let href = node.linkHref(), href.hasPrefix("http") {
                    out += #"<a href=""# + esc(href) + #"">"# + inner + "</a>"
                } else {
                    out += inner
                }
            case .image:
                break // hero / inline images are handled out-of-band; skip in body text
            default:
                if !node.children.isEmpty {
                    out += inlineHTML(node.children, source: source)
                } else {
                    out += esc(sliceSource(source, start: node.startOffset, end: node.endOffset))
                }
            }
        }
        return out
    }

    // MARK: - Helpers

    /// The generated markdown leads with `# <title>`; the headline already shows the
    /// title, so drop a single leading level-1 heading to avoid duplication.
    private static func stripLeadingH1(_ md: String) -> String {
        var lines = md.components(separatedBy: "\n")
        while let first = lines.first, first.trimmingCharacters(in: .whitespaces).isEmpty {
            lines.removeFirst()
        }
        if let first = lines.first, first.hasPrefix("# ") {
            lines.removeFirst()
        }
        return lines.joined(separator: "\n")
    }

    private static func esc(_ s: String) -> String {
        var r = ""
        r.reserveCapacity(s.count)
        for c in s {
            switch c {
            case "&": r += "&amp;"
            case "<": r += "&lt;"
            case ">": r += "&gt;"
            case "\"": r += "&quot;"
            case "'": r += "&#39;"
            default: r.append(c)
            }
        }
        return r
    }

    /// Slice the source using UTF-8 byte offsets from the AST (same as MarkdownView).
    private static func sliceSource(_ source: String, start: Int, end: Int) -> String {
        guard start < end else { return "" }
        guard let s = source.utf8.index(source.utf8.startIndex, offsetBy: start, limitedBy: source.utf8.endIndex),
              let e = source.utf8.index(source.utf8.startIndex, offsetBy: end, limitedBy: source.utf8.endIndex) else {
            return ""
        }
        return String(source[s..<e])
    }

    private static func isCJK(_ c: Character) -> Bool {
        for scalar in c.unicodeScalars {
            switch scalar.value {
            case 0x3000...0x303F, 0x3040...0x30FF, 0x3400...0x4DBF,
                 0x4E00...0x9FFF, 0xF900...0xFAFF, 0xFF00...0xFFEF:
                return true
            default:
                continue
            }
        }
        return false
    }

    // MARK: - CSS (verbatim port of Android DeepReadTemplateRenderer constants)

    private static let defaultFontCSS = """
    :root{
      --deep-read-serif:"Noto Serif SC","Source Han Serif SC","Songti SC",serif;
      --deep-read-sans:"PingFang SC","Source Han Sans SC","Noto Sans SC",system-ui,sans-serif;
      --deep-read-font-scale:1;
    }
    """

    private static let baseCSS = """
    html,body{margin:0;padding:0;background:#fafaf8;color:#191919;font-family:var(--deep-read-serif);}
    article{padding-bottom:34px;}
    .hero{margin:0 0 8px 0;position:relative;background:#f0f0ec;min-height:310px;overflow:hidden;}
    .hero img{display:block;width:100%;height:265px;object-fit:cover;}
    .hero-cut{height:106px;background:#fafaf8;clip-path:polygon(0 24%,100% 0,100% 100%,0 100%);margin-top:-54px;position:relative;padding:46px 22px 0;box-sizing:border-box;}
    .hero-cut>div{display:flex;align-items:center;justify-content:space-between;gap:14px;}
    .hero-type{font-family:var(--deep-read-sans);letter-spacing:.24em;color:#991b1b;font-size:10px;}
    .hero-source{font-family:var(--deep-read-sans);letter-spacing:.18em;color:#6b7280;font-size:10px;white-space:nowrap;}
    figcaption{font-size:9px;color:#6b7280;line-height:1.45;margin:10px 0 0;text-align:right;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
    .headline,section{padding:0 22px;}
    .kicker,.section,.date,.holder,small{font-family:var(--deep-read-sans);letter-spacing:.18em;text-transform:uppercase;color:#6b7280;font-size:10px;}
    h1{font-weight:500;font-size:32px;line-height:1.13;margin:12px 0 16px;}
    h2{font-weight:500;font-size:18px;line-height:1.34;margin:0 0 6px;}
    p{font-size:15px;line-height:1.68;margin:0 0 13px;}
    .summary{font-size:15px;line-height:1.68;}
    section{margin-top:28px;}
    .timeline{display:grid;grid-template-columns:32px 1fr;gap:10px;padding:11px 0;border-top:1px solid #ddd;}
    .num{font-family:var(--deep-read-sans);color:#ef4444;letter-spacing:.12em;font-size:12px;padding-top:4px;}
    .timeline-item{display:grid;grid-template-columns:32px minmax(0,1fr);gap:10px;padding:11px 0;border-top:1px solid #ddd;}
    .timeline-marker{width:18px;height:18px;border-radius:50%;border:1px solid #ef4444;margin-top:3px;}
    .timeline-body{min-width:0;}
    .timeline-date{font-family:var(--deep-read-sans);letter-spacing:.18em;text-transform:uppercase;color:#ef4444;font-size:10px;margin-bottom:4px;}
    .core-point{padding:12px 0;border-top:1px solid #ddd;}
    .inline{margin:12px 0 4px;background:#f0f0ec;}
    .inline img{display:block;width:100%;aspect-ratio:16/9;object-fit:cover;}
    .inline figcaption{text-align:left;margin:7px 9px 9px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
    .timeline-item figure,.core-point figure{margin:12px 0 4px;background:#f0f0ec;}
    .timeline-item img,.core-point img{display:block;width:100%;aspect-ratio:16/9;object-fit:cover;}
    .timeline-item figcaption,.core-point figcaption{text-align:left;margin:7px 9px 9px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
    .diagram-block{padding:0 22px;margin-top:30px;}
    .diagram-block h2{margin:4px 0 14px;font-size:18px;}
    .diagram-frame{background:#f4f1ec;border-top:1px solid #ddd;border-bottom:1px solid #ddd;padding:8px 12px 10px;}
    .diagram-steps{list-style:none;margin:0;padding:0;}
    .diagram-step{display:grid;grid-template-columns:34px minmax(0,1fr);gap:10px;padding:12px 0;border-top:1px solid rgba(107,114,128,.18);}
    .diagram-step:first-child{border-top:0;}
    .diagram-step-index{font-family:var(--deep-read-sans);font-size:11px;letter-spacing:.12em;color:#ef4444;padding-top:2px;}
    .diagram-grid{display:grid;grid-template-columns:1fr;gap:8px;margin:0;}
    .diagram-card{background:#fafaf8;border:1px solid #ddd8cf;padding:11px 12px;}
    .diagram-step h3,.diagram-card h3{font-size:15px;line-height:1.42;margin:0 0 5px;font-weight:500;}
    .diagram-step p,.diagram-card p{font-family:var(--deep-read-sans);font-size:12px;line-height:1.58;color:#6b7280;margin:0;}
    .diagram-group{display:block;font-family:var(--deep-read-sans);letter-spacing:.14em;text-transform:uppercase;color:#991b1b;font-size:9px;margin-bottom:4px;}
    .diagram-relations{list-style:none;margin:10px 0 0;padding:8px 0 0;border-top:1px solid rgba(107,114,128,.18);}
    .diagram-relations li{font-family:var(--deep-read-sans);font-size:11px;line-height:1.55;color:#6b7280;margin:4px 0;}
    .diagram-relations b{font-weight:500;color:#ef4444;margin:0 5px;}
    .diagram-caption{font-family:var(--deep-read-sans);font-size:11px;line-height:1.5;color:#6b7280;margin:10px 0 0;}
    blockquote{font-size:18px;line-height:1.48;margin:0 0 16px;padding-left:12px;border-left:3px solid #ef4444;}
    .reading{display:grid;grid-template-columns:30px 1fr;gap:10px;border-top:1px solid #ddd;padding:10px 0;text-decoration:none;color:inherit;}
    .reading span{font-family:var(--deep-read-sans);color:#ef4444;font-size:12px;letter-spacing:.12em;}
    .reading p{font-size:13px;line-height:1.45;margin-bottom:2px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
    .reading small{letter-spacing:.08em;font-size:9px;}
    .reading-link{display:block;border-top:1px solid #ddd;padding:10px 0;text-decoration:none;color:inherit;}
    .reading-link p{font-size:13px;line-height:1.45;margin-bottom:2px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
    .reading-link small{font-family:var(--deep-read-sans);letter-spacing:.08em;text-transform:uppercase;color:#6b7280;font-size:9px;}
    """

    private static let runtimeCSS = """
    @keyframes deepReadPulse{0%,100%{opacity:.36}50%{opacity:.76}}
    .markdown-body>:first-child{margin-top:0;}
    .markdown-body>:last-child{margin-bottom:0;}
    .markdown-body strong,.markdown-body b{font-weight:650;color:inherit;}
    .markdown-body em,.markdown-body i{font-style:italic;}
    .markdown-body s,.markdown-body del{text-decoration:line-through;}
    .markdown-body h2,.markdown-body h3,.markdown-body h4{font-weight:500;line-height:1.35;margin:14px 0 7px;}
    .markdown-body h2{font-size:18px;}
    .markdown-body h3{font-size:16px;}
    .markdown-body h4{font-size:15px;}
    .markdown-body ul,.markdown-body ol{font-size:15px;line-height:1.68;margin:0 0 13px 1.25em;padding:0;}
    .markdown-body li{margin:0 0 6px;padding-left:2px;}
    .markdown-body li>p{margin:0 0 6px;}
    .markdown-body code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.88em;background:rgba(107,114,128,.12);padding:0 .22em;border-radius:4px;}
    .markdown-body pre{overflow:auto;background:#f0f0ec;padding:10px 12px;border-radius:10px;margin:0 0 13px;}
    .markdown-body pre code{background:transparent;padding:0;border-radius:0;}
    .markdown-body a{color:#991b1b;text-decoration:none;border-bottom:1px solid currentColor;}
    .markdown-body table{width:100%;border-collapse:collapse;font-family:var(--deep-read-sans);font-size:12px;line-height:1.5;margin:0 0 13px;}
    .markdown-body th,.markdown-body td{border-top:1px solid #ddd;padding:7px 6px;text-align:left;vertical-align:top;}
    .markdown-body blockquote{margin:0 0 13px;padding-left:10px;border-left:2px solid #ef4444;font-size:15px;line-height:1.68;}
    """

    /// App-bundled fonts served via the `amberfont://` scheme handler
    /// (IOSDeepReadFontSchemeHandler): Noto Serif SC for the body (matches Android),
    /// JetBrains Mono for code. Prepended to the serif / code font stacks; the system
    /// fonts remain as fallbacks.
    private static let bundledFontCSS = """
    @font-face{font-family:"AmberDeepReadSerif";src:url("amberfont://deepread/serif.otf") format("opentype");font-weight:400;font-style:normal;font-display:swap;}
    @font-face{font-family:"AmberDeepReadMono";src:url("amberfont://deepread/mono.ttf") format("truetype");font-weight:400;font-style:normal;font-display:swap;}
    :root{--deep-read-serif:"AmberDeepReadSerif","Noto Serif SC","Source Han Serif SC","Songti SC",serif;}
    .markdown-body code,.markdown-body pre code{font-family:"AmberDeepReadMono",ui-monospace,SFMono-Regular,Menlo,monospace;}
    """

    private static let darkCSS = """
    :root{color-scheme:dark;}
    html,body{background:#0b0a09;color:#f1ece3;}
    article{background:#0b0a09;}
    .hero{background:#14110e;}
    .hero-cut{background:#0b0a09;}
    .hero-type,.diagram-group{color:#d18752;}
    .hero-source,figcaption,.kicker,.section,.date,.holder,small,.diagram-step p,.diagram-card p,.diagram-relations li,.diagram-caption,.reading-link small{color:#a89d90;}
    .timeline,.timeline-item,.core-point,.reading,.reading-link{border-top-color:#3a332b;}
    .timeline-marker{border-color:#d18752;}
    .num,.timeline-date,.diagram-step-index,.diagram-relations b,.reading span{color:#d18752;}
    blockquote{border-left-color:#d18752;}
    .inline,.timeline-item figure,.core-point figure,.diagram-frame{background:#181410;}
    .diagram-frame{border-top-color:#3a332b;border-bottom-color:#3a332b;}
    .diagram-step,.diagram-relations{border-top-color:rgba(168,157,144,.24);}
    .diagram-card{background:#120f0c;border-color:#3a332b;}
    .markdown-body code{background:rgba(168,157,144,.16);}
    .markdown-body pre{background:#181410;}
    .markdown-body a{color:#d18752;}
    .markdown-body th,.markdown-body td{border-top-color:#3a332b;}
    .markdown-body blockquote{border-left-color:#d18752;}
    """

    private static let emptyImageFallbackCSS = """
    img:not([src]),img[src=""]{display:none!important;}
    figure:has(> img:not([src])),figure:has(> img[src=""]){display:none!important;}
    """
}

private extension NodeType {
    /// Block-level nodes get their own HTML element; everything else is inline
    /// content coalesced into a flowing run (see `listItemHTML`).
    var isBlockLevelForHTML: Bool {
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

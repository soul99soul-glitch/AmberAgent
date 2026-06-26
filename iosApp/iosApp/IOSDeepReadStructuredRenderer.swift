import Foundation

/// Renders a structured `IOSDeepReadOutput` into the Android-parity editorial cards
/// (timeline / core-points / diagram / analysis / reading-links) — a Swift port of the
/// Kotlin `DeepReadTemplateRenderer` section builders. The CSS classes it targets are
/// already in `IOSDeepReadEditorialRenderer`'s ported CSS; text fields run through the
/// same Markdown→HTML the flat reader uses.
enum IOSDeepReadStructuredRenderer {

    /// The summary block that sits inside the headline section.
    static func summaryHTML(_ summary: String) -> String {
        let body = md(summary)
        guard !body.isEmpty else { return "" }
        return #"<div class="summary markdown-body">"# + body + "</div>"
    }

    /// All rich sections after the headline, in Android's order.
    static func sectionsHTML(_ output: IOSDeepReadOutput) -> String {
        var b = ""
        b += timelineSection(output.timeline)
        b += corePointsSection(output.corePoints)
        if let diagram = output.diagram { b += diagramSection(diagram) }
        b += analysisSection(output.analysis)
        // 扩展阅读与原生「相关报道」(BoardView.sourcesSection)数据重复、样式割裂,
        // 已统一为原生杂志列表(真实来源、可点击)。不再在 WebView 里单独渲染。
        // extendedReadingSection(_:) 保留以备需要时恢复。
        return b
    }

    // MARK: - Sections

    private static func timelineSection(_ events: [IOSDeepReadTimelineEvent]) -> String {
        let items = events.filter { !$0.event.isEmpty || !$0.date.isEmpty }
        guard !items.isEmpty else { return "" }
        var b = #"<section><p class="section">时间轴</p>"#
        for event in items {
            b += #"<div class="timeline-item"><div class="timeline-marker"></div><div class="timeline-body">"#
            if !event.date.isEmpty { b += #"<p class="timeline-date">"# + esc(event.date) + "</p>" }
            b += #"<div class="timeline-copy markdown-body">"# + md(event.event) + "</div>"
            b += figure(event.imageUrl, event.imageCaption)
            b += "</div></div>"
        }
        return b + "</section>"
    }

    private static func corePointsSection(_ points: [IOSDeepReadCorePoint]) -> String {
        let items = points.filter { !$0.point.isEmpty }
        guard !items.isEmpty else { return "" }
        var b = #"<section><p class="section">关键脉络</p>"#
        for point in items {
            b += #"<div class="core-point"><h2>"# + mdInline(point.point) + "</h2>"
            if let supporting = point.supporting, !supporting.isEmpty {
                b += #"<div class="core-support markdown-body">"# + md(supporting) + "</div>"
            }
            b += figure(point.imageUrl, point.imageCaption)
            b += "</div>"
        }
        return b + "</section>"
    }

    private static func diagramSection(_ diagram: IOSDeepReadDiagram) -> String {
        let nodes = Array(diagram.nodes.prefix(6))
        guard nodes.count >= 2 else { return "" }
        let labels = Dictionary(nodes.map { ($0.id, $0.label) }, uniquingKeysWith: { first, _ in first })
        let edges = Array(diagram.edges.filter { labels[$0.from] != nil && labels[$0.to] != nil }.prefix(6))
        let typeLabel: String
        switch diagram.type {
        case "causal_chain": typeLabel = "因果链"
        case "process_flow": typeLabel = "流程图"
        case "stakeholder_map": typeLabel = "关系图"
        case "system_structure": typeLabel = "结构图"
        case "comparison_matrix": typeLabel = "对比图"
        default: typeLabel = "图解"
        }
        let useSteps = diagram.type == "causal_chain" || diagram.type == "process_flow"
        var b = #"<section class="diagram-block"><p class="section">"# + esc(typeLabel) + "</p>"
        b += "<h2>" + mdInline(diagram.title) + "</h2>"
        b += #"<div class="diagram-frame">"#
        b += useSteps ? diagramSteps(nodes) : diagramCards(nodes)
        b += diagramRelations(edges, labels: labels)
        b += "</div>"
        if let caption = diagram.caption, !caption.isEmpty {
            b += #"<p class="diagram-caption">"# + esc(caption) + "</p>"
        }
        return b + "</section>"
    }

    private static func diagramSteps(_ nodes: [IOSDeepReadDiagramNode]) -> String {
        var b = #"<ol class="diagram-steps">"#
        for (index, node) in nodes.enumerated() {
            b += #"<li class="diagram-step"><span class="diagram-step-index">"#
            b += String(format: "%02d", index + 1) + "</span><div>" + diagramNodeInner(node) + "</div></li>"
        }
        return b + "</ol>"
    }

    private static func diagramCards(_ nodes: [IOSDeepReadDiagramNode]) -> String {
        var b = #"<div class="diagram-grid">"#
        for node in nodes {
            b += #"<div class="diagram-card">"# + diagramNodeInner(node) + "</div>"
        }
        return b + "</div>"
    }

    private static func diagramNodeInner(_ node: IOSDeepReadDiagramNode) -> String {
        var b = ""
        if let group = node.group, !group.isEmpty { b += #"<small class="diagram-group">"# + esc(group) + "</small>" }
        b += "<h3>" + mdInline(node.label) + "</h3>"
        if let note = node.note, !note.isEmpty { b += #"<div class="diagram-note markdown-body">"# + md(note) + "</div>" }
        return b
    }

    private static func diagramRelations(_ edges: [IOSDeepReadDiagramEdge], labels: [String: String]) -> String {
        guard !edges.isEmpty else { return "" }
        var b = #"<ul class="diagram-relations">"#
        for edge in edges {
            let from = labels[edge.from] ?? edge.from
            let to = labels[edge.to] ?? edge.to
            b += "<li><span>" + esc(from) + "</span><b>→</b><span>" + esc(to) + "</span>"
            if let label = edge.label, !label.isEmpty { b += "：" + esc(label) }
            b += "</li>"
        }
        return b + "</ul>"
    }

    private static func analysisSection(_ analysis: IOSDeepReadAnalysis) -> String {
        guard analysis.hasContent else { return "" }
        var b = #"<section><p class="section">深度分析</p>"#
        if let dispute = analysis.coreDispute, !dispute.isEmpty {
            b += #"<blockquote class="markdown-body">"# + md(dispute) + "</blockquote>"
        }
        for perspective in analysis.perspectives.prefix(6) where !perspective.viewpoint.isEmpty {
            b += #"<div class="perspective"><p class="holder">"# + esc(perspective.holder ?? "") + "</p>"
            b += #"<div class="markdown-body">"# + md(perspective.viewpoint) + "</div></div>"
        }
        if let implications = analysis.implications, !implications.isEmpty {
            b += #"<div class="markdown-body">"# + md(implications) + "</div>"
        }
        return b + "</section>"
    }

    private static func extendedReadingSection(_ links: [IOSDeepReadLink]) -> String {
        let items = links.filter { !$0.title.isEmpty && !$0.url.isEmpty }.prefix(10)
        guard !items.isEmpty else { return "" }
        var b = #"<section><p class="section">扩展阅读</p>"#
        for link in items {
            let label = (link.source?.isEmpty == false) ? link.source! : link.url
            b += #"<a class="reading-link" href=""# + esc(link.url) + #"">"#
            b += "<p>" + esc(link.title) + "</p><small>" + esc(label) + "</small></a>"
        }
        return b + "</section>"
    }

    // MARK: - Helpers

    private static func figure(_ url: String?, _ caption: String?) -> String {
        guard let url = url?.trimmingCharacters(in: .whitespaces), url.hasPrefix("http") else { return "" }
        var b = #"<figure><img src=""# + esc(url) + #""/>"#
        if let caption, !caption.isEmpty { b += "<figcaption>" + esc(caption) + "</figcaption>" }
        return b + "</figure>"
    }

    private static func esc(_ s: String) -> String { IOSDeepReadEditorialRenderer.esc(s) }
    private static func md(_ s: String) -> String { IOSDeepReadEditorialRenderer.markdownToHTML(s) }
    private static func mdInline(_ s: String) -> String { IOSDeepReadEditorialRenderer.markdownToInlineHTML(s) }
}

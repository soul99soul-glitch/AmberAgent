import Foundation

/// Swift mirror of Android's structured Deep Read output (Kotlin
/// `DeepReadOutput` in feature/board/impl, which is NOT in commonMain so it can't
/// be reached through the Shared framework). The 4-stage iOS LLM pipeline fills
/// these from the model's JSON, and `IOSDeepReadStructuredRenderer` turns them into
/// the Android-parity editorial cards (timeline / core-points / diagram / analysis /
/// reading-links). Decoding is tolerant: every field defaults, so a partial or
/// stage-by-stage JSON merge never throws.

struct IOSDeepReadOutput: Codable, Equatable {
    var topicType: String = ""
    var summary: String = ""
    var keyEntities: [String] = []
    var timeline: [IOSDeepReadTimelineEvent] = []
    var corePoints: [IOSDeepReadCorePoint] = []
    var analysis: IOSDeepReadAnalysis = .init()
    var diagram: IOSDeepReadDiagram? = nil
    var extendedReading: [IOSDeepReadLink] = []
    var references: [IOSDeepReadLink] = []
    var heroImageUrl: String? = nil
    var heroCaption: String? = nil

    enum CodingKeys: String, CodingKey {
        case topicType = "topic_type"
        case summary
        case keyEntities = "key_entities"
        case timeline
        case corePoints = "core_points"
        case analysis
        case diagram
        case extendedReading = "extended_reading"
        case references
        case heroImageUrl = "hero_image_url"
        case heroCaption = "hero_caption"
    }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        topicType = (try? c.decodeIfPresent(String.self, forKey: .topicType)) ?? ""
        summary = (try? c.decodeIfPresent(String.self, forKey: .summary)) ?? ""
        keyEntities = (try? c.decodeIfPresent([String].self, forKey: .keyEntities)) ?? []
        timeline = (try? c.decodeIfPresent([IOSDeepReadTimelineEvent].self, forKey: .timeline)) ?? []
        corePoints = (try? c.decodeIfPresent([IOSDeepReadCorePoint].self, forKey: .corePoints)) ?? []
        analysis = (try? c.decodeIfPresent(IOSDeepReadAnalysis.self, forKey: .analysis)) ?? .init()
        diagram = try? c.decodeIfPresent(IOSDeepReadDiagram.self, forKey: .diagram)
        extendedReading = (try? c.decodeIfPresent([IOSDeepReadLink].self, forKey: .extendedReading)) ?? []
        references = (try? c.decodeIfPresent([IOSDeepReadLink].self, forKey: .references)) ?? []
        heroImageUrl = try? c.decodeIfPresent(String.self, forKey: .heroImageUrl)
        heroCaption = try? c.decodeIfPresent(String.self, forKey: .heroCaption)
    }

    /// Whether there's enough structured content to render the rich layout (vs.
    /// falling back to the flat-markdown reader).
    var hasStructuredBody: Bool {
        !timeline.isEmpty || !corePoints.isEmpty || diagram != nil
            || analysis.hasContent || !extendedReading.isEmpty || !summary.isEmpty
    }

    /// Merge a later stage's partial output in: every non-empty / non-nil field from
    /// `other` overwrites this one, so OVERVIEW → NARRATIVE → ANALYSIS →
    /// EXTENDED_READING accumulate without a later empty stage wiping earlier work.
    func merged(with other: IOSDeepReadOutput) -> IOSDeepReadOutput {
        var result = self
        if !other.topicType.isEmpty { result.topicType = other.topicType }
        if !other.summary.isEmpty { result.summary = other.summary }
        if !other.keyEntities.isEmpty { result.keyEntities = other.keyEntities }
        if !other.timeline.isEmpty { result.timeline = other.timeline }
        if !other.corePoints.isEmpty { result.corePoints = other.corePoints }
        if other.analysis.hasContent { result.analysis = other.analysis }
        if let diagram = other.diagram, diagram.nodes.count >= 2 { result.diagram = diagram }
        if !other.extendedReading.isEmpty { result.extendedReading = other.extendedReading }
        if !other.references.isEmpty { result.references = other.references }
        if let hero = other.heroImageUrl, !hero.isEmpty { result.heroImageUrl = hero }
        if let caption = other.heroCaption, !caption.isEmpty { result.heroCaption = caption }
        return result
    }
}

struct IOSDeepReadTimelineEvent: Codable, Equatable {
    var date: String
    var event: String
    var isHighlight: Bool
    var imageUrl: String?
    var imageCaption: String?

    enum CodingKeys: String, CodingKey {
        case date, event
        case isHighlight = "is_highlight"
        case imageUrl = "image_url"
        case imageCaption = "image_caption"
    }

    init(date: String = "", event: String = "", isHighlight: Bool = false, imageUrl: String? = nil, imageCaption: String? = nil) {
        self.date = date; self.event = event; self.isHighlight = isHighlight
        self.imageUrl = imageUrl; self.imageCaption = imageCaption
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        date = (try? c.decodeIfPresent(String.self, forKey: .date)) ?? ""
        event = (try? c.decodeIfPresent(String.self, forKey: .event)) ?? ""
        isHighlight = (try? c.decodeIfPresent(Bool.self, forKey: .isHighlight)) ?? false
        imageUrl = try? c.decodeIfPresent(String.self, forKey: .imageUrl)
        imageCaption = try? c.decodeIfPresent(String.self, forKey: .imageCaption)
    }
}

struct IOSDeepReadCorePoint: Codable, Equatable {
    var point: String
    var supporting: String?
    var imageUrl: String?
    var imageCaption: String?

    enum CodingKeys: String, CodingKey {
        case point, supporting
        case imageUrl = "image_url"
        case imageCaption = "image_caption"
    }

    init(point: String = "", supporting: String? = nil, imageUrl: String? = nil, imageCaption: String? = nil) {
        self.point = point; self.supporting = supporting; self.imageUrl = imageUrl; self.imageCaption = imageCaption
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        point = (try? c.decodeIfPresent(String.self, forKey: .point)) ?? ""
        supporting = try? c.decodeIfPresent(String.self, forKey: .supporting)
        imageUrl = try? c.decodeIfPresent(String.self, forKey: .imageUrl)
        imageCaption = try? c.decodeIfPresent(String.self, forKey: .imageCaption)
    }
}

struct IOSDeepReadAnalysis: Codable, Equatable {
    var coreDispute: String? = nil
    var perspectives: [IOSDeepReadPerspective] = []
    var implications: String? = nil
    var quotes: [IOSDeepReadQuote] = []

    enum CodingKeys: String, CodingKey {
        case coreDispute = "core_dispute"
        case perspectives
        case implications
        case quotes
    }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        coreDispute = try? c.decodeIfPresent(String.self, forKey: .coreDispute)
        perspectives = (try? c.decodeIfPresent([IOSDeepReadPerspective].self, forKey: .perspectives)) ?? []
        implications = try? c.decodeIfPresent(String.self, forKey: .implications)
        quotes = (try? c.decodeIfPresent([IOSDeepReadQuote].self, forKey: .quotes)) ?? []
    }

    var hasContent: Bool {
        !(coreDispute ?? "").isEmpty
            || perspectives.contains { !$0.viewpoint.isEmpty }
            || !(implications ?? "").isEmpty
            || quotes.contains { !$0.text.isEmpty }
    }
}

struct IOSDeepReadQuote: Codable, Equatable {
    var text: String
    var attribution: String?

    enum CodingKeys: String, CodingKey { case text, attribution }

    init(text: String = "", attribution: String? = nil) {
        self.text = text
        self.attribution = attribution
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        text = (try? c.decodeIfPresent(String.self, forKey: .text)) ?? ""
        attribution = try? c.decodeIfPresent(String.self, forKey: .attribution)
    }
}

struct IOSDeepReadPerspective: Codable, Equatable {
    var viewpoint: String
    var holder: String?

    enum CodingKeys: String, CodingKey { case viewpoint, holder }

    init(viewpoint: String = "", holder: String? = nil) { self.viewpoint = viewpoint; self.holder = holder }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        viewpoint = (try? c.decodeIfPresent(String.self, forKey: .viewpoint)) ?? ""
        holder = try? c.decodeIfPresent(String.self, forKey: .holder)
    }
}

struct IOSDeepReadDiagram: Codable, Equatable {
    var type: String
    var title: String
    var nodes: [IOSDeepReadDiagramNode]
    var edges: [IOSDeepReadDiagramEdge]
    var caption: String?

    enum CodingKeys: String, CodingKey { case type, title, nodes, edges, caption }

    init(type: String = "", title: String = "", nodes: [IOSDeepReadDiagramNode] = [], edges: [IOSDeepReadDiagramEdge] = [], caption: String? = nil) {
        self.type = type; self.title = title; self.nodes = nodes; self.edges = edges; self.caption = caption
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        type = (try? c.decodeIfPresent(String.self, forKey: .type)) ?? ""
        title = (try? c.decodeIfPresent(String.self, forKey: .title)) ?? ""
        nodes = (try? c.decodeIfPresent([IOSDeepReadDiagramNode].self, forKey: .nodes)) ?? []
        edges = (try? c.decodeIfPresent([IOSDeepReadDiagramEdge].self, forKey: .edges)) ?? []
        caption = try? c.decodeIfPresent(String.self, forKey: .caption)
    }
}

struct IOSDeepReadDiagramNode: Codable, Equatable {
    var id: String
    var label: String
    var note: String?
    var group: String?

    enum CodingKeys: String, CodingKey { case id, label, note, group }

    init(id: String = "", label: String = "", note: String? = nil, group: String? = nil) {
        self.id = id; self.label = label; self.note = note; self.group = group
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? ""
        label = (try? c.decodeIfPresent(String.self, forKey: .label)) ?? ""
        note = try? c.decodeIfPresent(String.self, forKey: .note)
        group = try? c.decodeIfPresent(String.self, forKey: .group)
    }
}

struct IOSDeepReadDiagramEdge: Codable, Equatable {
    var from: String
    var to: String
    var label: String?

    enum CodingKeys: String, CodingKey { case from, to, label }

    init(from: String = "", to: String = "", label: String? = nil) {
        self.from = from; self.to = to; self.label = label
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        from = (try? c.decodeIfPresent(String.self, forKey: .from)) ?? ""
        to = (try? c.decodeIfPresent(String.self, forKey: .to)) ?? ""
        label = try? c.decodeIfPresent(String.self, forKey: .label)
    }
}

struct IOSDeepReadLink: Codable, Equatable {
    var title: String
    var url: String
    var source: String?

    enum CodingKeys: String, CodingKey { case title, url, source }

    init(title: String = "", url: String = "", source: String? = nil) {
        self.title = title; self.url = url; self.source = source
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = (try? c.decodeIfPresent(String.self, forKey: .title)) ?? ""
        url = (try? c.decodeIfPresent(String.self, forKey: .url)) ?? ""
        source = try? c.decodeIfPresent(String.self, forKey: .source)
    }
}

/// Swift mirror of Android's `DeepReadArticlePlan` (feature/board/impl
/// `DeepReadResearchHarness.kt`): a planning LLM call decides the article angle,
/// narrative slots, analysis questions, stakeholders and risks before the stage
/// loop. Tolerant decode — every field defaults, and `normalized` fills the
/// deterministic local fallback for whatever the model left blank.
/// `requiredSourceIds` are 1-based indexes into the generation source block
/// (Android uses "s1"-style ids; iOS uses the block's stable numbering).
struct IOSDeepReadArticlePlan: Codable, Equatable {
    var overviewAngle: String = ""
    var narrativeSlots: [String] = []
    var analysisQuestions: [String] = []
    var stakeholders: [String] = []
    var riskOrUncertainty: [String] = []
    var requiredSourceIds: [Int] = []

    enum CodingKeys: String, CodingKey {
        case overviewAngle = "overview_angle"
        case narrativeSlots = "narrative_slots"
        case analysisQuestions = "analysis_questions"
        case stakeholders
        case riskOrUncertainty = "risk_or_uncertainty"
        case requiredSourceIds = "required_source_ids"
    }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        overviewAngle = (try? c.decodeIfPresent(String.self, forKey: .overviewAngle)) ?? ""
        narrativeSlots = (try? c.decodeIfPresent([String].self, forKey: .narrativeSlots)) ?? []
        analysisQuestions = (try? c.decodeIfPresent([String].self, forKey: .analysisQuestions)) ?? []
        stakeholders = (try? c.decodeIfPresent([String].self, forKey: .stakeholders)) ?? []
        riskOrUncertainty = (try? c.decodeIfPresent([String].self, forKey: .riskOrUncertainty)) ?? []
        requiredSourceIds = (try? c.decodeIfPresent([Int].self, forKey: .requiredSourceIds)) ?? []
    }

    /// Merges a parsed (possibly partial) plan with the deterministic fallback,
    /// mirroring Android's `normalizePlan`: blank fields take the fallback,
    /// lists are capped, source ids are filtered to the valid range.
    func normalized(with fallback: IOSDeepReadArticlePlan, sourceCount: Int) -> IOSDeepReadArticlePlan {
        let validRange = 1...max(sourceCount, 1)
        var result = self
        result.overviewAngle = overviewAngle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? fallback.overviewAngle : overviewAngle
        result.narrativeSlots = narrativeSlots.isEmpty ? fallback.narrativeSlots : Array(narrativeSlots.prefix(6))
        result.analysisQuestions = analysisQuestions.isEmpty ? fallback.analysisQuestions : Array(analysisQuestions.prefix(8))
        result.stakeholders = stakeholders.isEmpty ? fallback.stakeholders : Array(stakeholders.prefix(8))
        result.riskOrUncertainty = riskOrUncertainty.isEmpty
            ? fallback.riskOrUncertainty : Array(riskOrUncertainty.prefix(8))
        let ids = requiredSourceIds.filter { validRange.contains($0) }.distinctPreservingOrder()
        result.requiredSourceIds = ids.isEmpty ? fallback.requiredSourceIds : ids
        return result
    }
}

private extension Array where Element == Int {
    func distinctPreservingOrder() -> [Int] {
        var seen = Set<Int>()
        return filter { seen.insert($0).inserted }
    }
}

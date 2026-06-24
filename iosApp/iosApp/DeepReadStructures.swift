import Foundation

/// Swift mirror of Android's structured Deep Read output (Kotlin
/// `DeepReadOutput` in feature/board/impl, which is NOT in commonMain so it can't
/// be reached through the Shared framework). The 4-stage iOS LLM pipeline fills
/// these from the model's JSON, and `IOSDeepReadStructuredRenderer` turns them into
/// the Android-parity editorial cards (timeline / core-points / diagram / analysis /
/// reading-links). Decoding is tolerant: every field defaults, so a partial or
/// stage-by-stage JSON merge never throws.

struct IOSDeepReadOutput: Codable, Equatable {
    var topicType: String = "event"
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
        topicType = (try? c.decodeIfPresent(String.self, forKey: .topicType)) ?? "event"
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
}

struct IOSDeepReadTimelineEvent: Codable, Equatable {
    var date: String = ""
    var event: String = ""
    var isHighlight: Bool = false
    var imageUrl: String? = nil
    var imageCaption: String? = nil

    enum CodingKeys: String, CodingKey {
        case date, event
        case isHighlight = "is_highlight"
        case imageUrl = "image_url"
        case imageCaption = "image_caption"
    }
}

struct IOSDeepReadCorePoint: Codable, Equatable {
    var point: String = ""
    var supporting: String? = nil
    var imageUrl: String? = nil
    var imageCaption: String? = nil

    enum CodingKeys: String, CodingKey {
        case point, supporting
        case imageUrl = "image_url"
        case imageCaption = "image_caption"
    }
}

struct IOSDeepReadAnalysis: Codable, Equatable {
    var coreDispute: String? = nil
    var perspectives: [IOSDeepReadPerspective] = []
    var implications: String? = nil

    enum CodingKeys: String, CodingKey {
        case coreDispute = "core_dispute"
        case perspectives
        case implications
    }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        coreDispute = try? c.decodeIfPresent(String.self, forKey: .coreDispute)
        perspectives = (try? c.decodeIfPresent([IOSDeepReadPerspective].self, forKey: .perspectives)) ?? []
        implications = try? c.decodeIfPresent(String.self, forKey: .implications)
    }

    var hasContent: Bool {
        !(coreDispute ?? "").isEmpty
            || perspectives.contains { !$0.viewpoint.isEmpty }
            || !(implications ?? "").isEmpty
    }
}

struct IOSDeepReadPerspective: Codable, Equatable {
    var viewpoint: String = ""
    var holder: String? = nil
}

struct IOSDeepReadDiagram: Codable, Equatable {
    var type: String = ""
    var title: String = ""
    var nodes: [IOSDeepReadDiagramNode] = []
    var edges: [IOSDeepReadDiagramEdge] = []
    var caption: String? = nil
}

struct IOSDeepReadDiagramNode: Codable, Equatable {
    var id: String = ""
    var label: String = ""
    var note: String? = nil
    var group: String? = nil
}

struct IOSDeepReadDiagramEdge: Codable, Equatable {
    var from: String = ""
    var to: String = ""
    var label: String? = nil
}

struct IOSDeepReadLink: Codable, Equatable {
    var title: String = ""
    var url: String = ""
    var source: String? = nil
}

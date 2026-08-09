import CoreFoundation
import Foundation

struct NovelQuickStartSuggestionV1: Codable, Equatable, Sendable {
    let title: String
    let content: String
    let aliases: [String]?

    init(title: String, content: String, aliases: [String]? = nil) {
        self.title = title
        self.content = content
        self.aliases = aliases
    }
}

struct NovelQuickStartSuggestionsV2: Codable, Equatable, Sendable {
    static let legacySchemaVersion = 1
    static let previousSchemaVersion = 2
    static let currentSchemaVersion = 3

    let schemaVersion: Int
    let overview: String
    let world: NovelQuickStartSuggestionV1
    let characters: [NovelQuickStartSuggestionV1]
    let masterOutline: NovelQuickStartSuggestionV1
    let writingRequirements: NovelQuickStartSuggestionV1

    private enum CodingKeys: String, CodingKey {
        case schemaVersion
        case overview
        case world
        case characters
        case masterOutline
        case writingRequirements
    }

    init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try values.decode(Int.self, forKey: .schemaVersion)
        overview = try values.decode(String.self, forKey: .overview)
        world = try values.decode(NovelQuickStartSuggestionV1.self, forKey: .world)
        if schemaVersion == Self.legacySchemaVersion {
            characters = [try values.decode(NovelQuickStartSuggestionV1.self, forKey: .characters)]
        } else {
            characters = try values.decode([NovelQuickStartSuggestionV1].self, forKey: .characters)
        }
        masterOutline = try values.decode(NovelQuickStartSuggestionV1.self, forKey: .masterOutline)
        writingRequirements = try values.decode(
            NovelQuickStartSuggestionV1.self,
            forKey: .writingRequirements
        )
    }
}

enum NovelCharacterRelatedSuggestionKind: String, Codable, CaseIterable, Sendable {
    case relationship
    case world
    case plot
}

struct NovelCharacterRelatedSuggestionV1: Codable, Equatable, Sendable {
    let kind: NovelCharacterRelatedSuggestionKind
    let title: String
    let content: String
}

struct NovelCharacterProposalV1: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 1

    let schemaVersion: Int
    let character: NovelQuickStartSuggestionV1
    let relatedSuggestions: [NovelCharacterRelatedSuggestionV1]
}

/// Converts the incomplete Quick Start JSON stream into the same user-facing
/// section order used by the durable completion message. It never creates
/// proposal records; the full strict decoder remains the only commit gate.
enum NovelQuickStartStreamingPresentation {
    static func markdown(from partialJSON: String) -> String {
        let overview = stringValue(for: "overview", in: partialJSON)
        let world = section(for: "world", heading: "世界观", in: partialJSON)
        var characterRanges = objectRanges(inArrayFor: "characters", in: partialJSON)
        if characterRanges.isEmpty,
           let legacyCharacter = objectRange(for: "characters", in: partialJSON) {
            characterRanges = [legacyCharacter]
        }
        let characters = characterRanges
            .compactMap { section(heading: "人物", in: partialJSON, range: $0) }
        let outline = section(for: "masterOutline", heading: "总剧情大纲", in: partialJSON)
        let requirements = section(
            for: "writingRequirements",
            heading: "写作要求",
            in: partialJSON
        )

        var blocks: [String] = []
        if let overview, !overview.isEmpty {
            blocks.append("# 创作建议\n\n\(overview)")
        }
        blocks.append(contentsOf: [world].compactMap { $0 })
        blocks.append(contentsOf: characters)
        blocks.append(contentsOf: [outline, requirements].compactMap { $0 })
        return blocks.joined(separator: "\n\n")
    }

    static func characterProposalMarkdown(from partialJSON: String) -> String {
        var blocks: [String] = []
        if let range = objectRange(for: "character", in: partialJSON) {
            let title = stringValue(for: "title", in: partialJSON, searchRange: range) ?? ""
            let content = stringValue(for: "content", in: partialJSON, searchRange: range) ?? ""
            if !title.isEmpty || !content.isEmpty {
                let heading = title.isEmpty ? "# 人物建议" : "# 人物建议：\(title)"
                blocks.append(content.isEmpty ? heading : heading + "\n\n" + content)
            }
        }
        for range in objectRanges(inArrayFor: "relatedSuggestions", in: partialJSON) {
            let kind = stringValue(for: "kind", in: partialJSON, searchRange: range) ?? ""
            let title = stringValue(for: "title", in: partialJSON, searchRange: range) ?? ""
            let content = stringValue(for: "content", in: partialJSON, searchRange: range) ?? ""
            guard !title.isEmpty || !content.isEmpty else { continue }
            let label = switch kind {
            case "relationship": "人物关系"
            case "world": "世界观"
            case "plot": "剧情"
            default: "相关设定"
            }
            let heading = title.isEmpty ? "## \(label)" : "## 相关\(label)：\(title)"
            blocks.append(content.isEmpty ? heading : heading + "\n\n" + content)
        }
        return blocks.joined(separator: "\n\n")
    }

    private static func section(
        for key: String,
        heading: String,
        in text: String
    ) -> String? {
        guard let range = objectRange(for: key, in: text) else { return nil }
        return section(heading: heading, in: text, range: range)
    }

    private static func section(
        heading: String,
        in text: String,
        range: Range<String.Index>
    ) -> String? {
        let title = stringValue(for: "title", in: text, searchRange: range) ?? ""
        let content = stringValue(for: "content", in: text, searchRange: range) ?? ""
        guard !title.isEmpty || !content.isEmpty else { return nil }
        if title.isEmpty { return "## \(heading)\n\n\(content)" }
        if content.isEmpty { return "## \(heading)：\(title)" }
        return "## \(heading)：\(title)\n\n\(content)"
    }

    private static func stringValue(
        for key: String,
        in text: String,
        searchRange: Range<String.Index>? = nil
    ) -> String? {
        let range = searchRange ?? text.startIndex..<text.endIndex
        guard let keyRange = unescapedKeyRange(key, in: text, searchRange: range),
              let colon = text[keyRange.upperBound..<range.upperBound].firstIndex(of: ":") else {
            return nil
        }
        var cursor = text.index(after: colon)
        skipWhitespace(in: text, cursor: &cursor, limit: range.upperBound)
        guard cursor < range.upperBound, text[cursor] == "\"" else { return nil }
        return decodeJSONString(in: text, openingQuote: cursor, limit: range.upperBound)
    }

    private static func objectRange(for key: String, in text: String) -> Range<String.Index>? {
        guard let keyRange = unescapedKeyRange(
            key,
            in: text,
            searchRange: text.startIndex..<text.endIndex
        ), let colon = text[keyRange.upperBound...].firstIndex(of: ":") else {
            return nil
        }
        var cursor = text.index(after: colon)
        skipWhitespace(in: text, cursor: &cursor, limit: text.endIndex)
        guard cursor < text.endIndex, text[cursor] == "{" else { return nil }
        return containerRange(in: text, start: cursor, opening: "{", closing: "}")
    }

    private static func objectRanges(
        inArrayFor key: String,
        in text: String
    ) -> [Range<String.Index>] {
        guard let keyRange = unescapedKeyRange(
            key,
            in: text,
            searchRange: text.startIndex..<text.endIndex
        ), let colon = text[keyRange.upperBound...].firstIndex(of: ":") else {
            return []
        }
        var cursor = text.index(after: colon)
        skipWhitespace(in: text, cursor: &cursor, limit: text.endIndex)
        guard cursor < text.endIndex, text[cursor] == "[" else { return [] }

        var ranges: [Range<String.Index>] = []
        var index = text.index(after: cursor)
        var inString = false
        var escaped = false
        var arrayDepth = 1
        while index < text.endIndex, arrayDepth > 0 {
            let character = text[index]
            if inString {
                if escaped {
                    escaped = false
                } else if character == "\\" {
                    escaped = true
                } else if character == "\"" {
                    inString = false
                }
            } else if character == "\"" {
                inString = true
            } else if character == "[" {
                arrayDepth += 1
            } else if character == "]" {
                arrayDepth -= 1
            } else if character == "{", arrayDepth == 1 {
                let range = containerRange(
                    in: text,
                    start: index,
                    opening: "{",
                    closing: "}"
                )
                ranges.append(range)
                index = range.upperBound
                continue
            }
            index = text.index(after: index)
        }
        return ranges
    }

    private static func containerRange(
        in text: String,
        start: String.Index,
        opening: Character,
        closing: Character
    ) -> Range<String.Index> {
        var index = start
        var depth = 0
        var inString = false
        var escaped = false
        while index < text.endIndex {
            let character = text[index]
            if inString {
                if escaped {
                    escaped = false
                } else if character == "\\" {
                    escaped = true
                } else if character == "\"" {
                    inString = false
                }
            } else if character == "\"" {
                inString = true
            } else if character == opening {
                depth += 1
            } else if character == closing {
                depth -= 1
                if depth == 0 {
                    return start..<text.index(after: index)
                }
            }
            index = text.index(after: index)
        }
        return start..<text.endIndex
    }

    private static func unescapedKeyRange(
        _ key: String,
        in text: String,
        searchRange: Range<String.Index>
    ) -> Range<String.Index>? {
        let marker = "\"\(key)\""
        var lowerBound = searchRange.lowerBound
        while lowerBound < searchRange.upperBound,
              let match = text.range(
                  of: marker,
                  range: lowerBound..<searchRange.upperBound
              ) {
            var slashCount = 0
            var cursor = match.lowerBound
            while cursor > searchRange.lowerBound {
                let previous = text.index(before: cursor)
                guard text[previous] == "\\" else { break }
                slashCount += 1
                cursor = previous
            }
            if slashCount.isMultiple(of: 2) { return match }
            lowerBound = match.upperBound
        }
        return nil
    }

    private static func skipWhitespace(
        in text: String,
        cursor: inout String.Index,
        limit: String.Index
    ) {
        while cursor < limit, text[cursor].isWhitespace {
            cursor = text.index(after: cursor)
        }
    }

    private static func decodeJSONString(
        in text: String,
        openingQuote: String.Index,
        limit: String.Index
    ) -> String {
        var result = ""
        var index = text.index(after: openingQuote)
        while index < limit {
            let character = text[index]
            guard character != "\"" else { break }
            guard character == "\\" else {
                result.append(character)
                index = text.index(after: index)
                continue
            }

            let escape = text.index(after: index)
            guard escape < limit else { break }
            switch text[escape] {
            case "\"": result.append("\"")
            case "\\": result.append("\\")
            case "/": result.append("/")
            case "b": result.append("\u{8}")
            case "f": result.append("\u{c}")
            case "n": result.append("\n")
            case "r": result.append("\r")
            case "t": result.append("\t")
            case "u":
                var hex = ""
                var hexIndex = text.index(after: escape)
                for _ in 0..<4 where hexIndex < limit {
                    hex.append(text[hexIndex])
                    hexIndex = text.index(after: hexIndex)
                }
                guard hex.count == 4,
                      let value = UInt32(hex, radix: 16),
                      let scalar = Unicode.Scalar(value) else {
                    return result
                }
                result.unicodeScalars.append(scalar)
                index = hexIndex
                continue
            default:
                return result
            }
            index = text.index(after: escape)
        }
        return result
    }
}

struct NovelStateEventV1: Codable, Equatable, Sendable {
    let id: String
    let kind: String
    let summary: String
    let entityReferences: [String]
    let evidence: String
}

struct NovelCharacterStateFactV1: Codable, Equatable, Sendable {
    let id: String
    let characterName: String
    let attribute: String
    let value: String
    let evidence: String
}

struct NovelRelationshipFactV1: Codable, Equatable, Sendable {
    let id: String
    let sourceEntity: String
    let targetEntity: String
    let relationship: String
    let state: String
    let evidence: String
}

enum NovelForeshadowingStatusV1: String, Codable, CaseIterable, Sendable {
    case introduced
    case advanced
    case resolved
    case reopened
}

struct NovelForeshadowingFactV1: Codable, Equatable, Sendable {
    let id: String
    let thread: String
    let status: NovelForeshadowingStatusV1
    let summary: String
    let evidence: String
}

struct NovelSettingProposalDraftV1: Codable, Equatable, Sendable {
    let id: String
    let title: String
    let content: String
    let evidence: String
}

struct NovelStateDeltaV1: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 1

    let schemaVersion: Int
    let stateSummary: String
    let events: [NovelStateEventV1]
    let characterChanges: [NovelCharacterStateFactV1]
    let relationshipChanges: [NovelRelationshipFactV1]
    let foreshadowingChanges: [NovelForeshadowingFactV1]
    let unresolvedEntityNames: [String]
    let branchOutlinePatch: String?
    let settingProposals: [NovelSettingProposalDraftV1]
}

struct NovelStateRebuildV1: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 1

    let schemaVersion: Int
    let stateSummary: String
    let branchOutline: String
    let events: [NovelStateEventV1]
    let characterStates: [NovelCharacterStateFactV1]
    let relationships: [NovelRelationshipFactV1]
    let foreshadowing: [NovelForeshadowingFactV1]
    let unresolvedEntityNames: [String]
    let settingProposals: [NovelSettingProposalDraftV1]
}

enum NovelPolishDifferenceCategoryV1: String, Codable, CaseIterable, Sendable {
    case event
    case chronology
    case relationship
    case motivation
    case secret
    case outcome
    case pointOfView
    case ending
    case other
}

struct NovelPolishDifferenceV1: Codable, Equatable, Sendable {
    let id: String
    let category: NovelPolishDifferenceCategoryV1
    let summary: String
    let sourceEvidence: String
    let candidateEvidence: String
}

struct NovelPolishDriftV1: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 1

    let schemaVersion: Int
    let compatible: Bool
    let differences: [NovelPolishDifferenceV1]
}

struct NovelChapterPlanAcceptanceV1: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 2
    static let legacySchemaVersion = 1

    let schemaVersion: Int
    let accepted: Bool
    let missingMustHappen: [String]
    let forbiddenViolations: [String]
    /// Soft gate: beats that clearly rehash recent written highlights. May be non-empty even when accepted.
    let obviousRepetition: [String]
    let summary: String

    init(
        schemaVersion: Int = currentSchemaVersion,
        accepted: Bool,
        missingMustHappen: [String],
        forbiddenViolations: [String],
        obviousRepetition: [String] = [],
        summary: String
    ) {
        self.schemaVersion = schemaVersion
        self.accepted = accepted
        self.missingMustHappen = missingMustHappen
        self.forbiddenViolations = forbiddenViolations
        self.obviousRepetition = obviousRepetition
        self.summary = summary
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion
        case accepted
        case missingMustHappen
        case forbiddenViolations
        case obviousRepetition
        case summary
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try container.decode(Int.self, forKey: .schemaVersion)
        accepted = try container.decode(Bool.self, forKey: .accepted)
        missingMustHappen = try container.decode([String].self, forKey: .missingMustHappen)
        forbiddenViolations = try container.decode([String].self, forKey: .forbiddenViolations)
        obviousRepetition = try container.decodeIfPresent([String].self, forKey: .obviousRepetition) ?? []
        summary = try container.decode(String.self, forKey: .summary)
    }
}

/// Structured next-chapter contract for multi-chapter ghostwrite auto-planning.
struct NovelChapterPlanProposalV1: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 1

    let schemaVersion: Int
    let outlinePlacement: String
    let goalAndConflict: String
    let mustHappen: [String]
    let mustNotHappen: [String]
    let endingHook: String
    let visibleFacts: [String]

    init(
        schemaVersion: Int = currentSchemaVersion,
        outlinePlacement: String,
        goalAndConflict: String,
        mustHappen: [String],
        mustNotHappen: [String],
        endingHook: String,
        visibleFacts: [String]
    ) {
        self.schemaVersion = schemaVersion
        self.outlinePlacement = outlinePlacement
        self.goalAndConflict = goalAndConflict
        self.mustHappen = mustHappen
        self.mustNotHappen = mustNotHappen
        self.endingHook = endingHook
        self.visibleFacts = visibleFacts
    }
}

enum NovelContinuityIssueCategoryV1: String, Codable, CaseIterable, Sendable {
    /// 同一件事被写了两遍。
    case duplicatedPlot
    /// 前后事实互相矛盾。
    case contradiction
    /// 明明见过却写成初次见面（或反过来）。
    case identityDrift
    /// 时间线错乱。
    case chronology
    /// 生死、伤情、所在位置这类状态冲突。
    case statusConflict
    case other
}

enum NovelContinuityIssueSeverityV1: String, Codable, CaseIterable, Sendable {
    case blocking
    case major
    case minor
}

/// 一条问题在正文里的落点。`chapterOrdinal` 是注入正文里 `# Chapter N` 的 N（从 1 起），
/// 由执行入口映射回 `NovelChapterID` —— 模型不知道也不该知道内部 ID。
struct NovelContinuityReferenceV1: Codable, Equatable, Sendable {
    let chapterOrdinal: Int
    let chapterTitle: String
    let evidence: String
}

struct NovelContinuityIssueV1: Codable, Equatable, Sendable {
    let id: String
    let category: NovelContinuityIssueCategoryV1
    let severity: NovelContinuityIssueSeverityV1
    let summary: String
    /// 至少两条 —— 矛盾天然是成对的，只给一处就无从对照。
    let references: [NovelContinuityReferenceV1]
}

struct NovelContinuityAuditV1: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 1
    static let minimumReferenceCount = 2

    let schemaVersion: Int
    let consistent: Bool
    let issues: [NovelContinuityIssueV1]
}

struct NovelDiscussionArchiveDecisionV1: Codable, Equatable, Sendable {
    let topic: String
    let decision: String
    let relatedMaterialID: String?
}

struct NovelDiscussionArchiveV1: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 1

    let schemaVersion: Int
    let decisions: [NovelDiscussionArchiveDecisionV1]
    let summary: String
}

enum NovelStructuredOutputErrorCategory: String, Codable, Equatable, Sendable {
    case malformedJSON
    case expectedObject
    case missingField
    case unknownField
    case duplicateKey
    case typeMismatch
    case unsupportedVersion
    case invalidValue
    case duplicateIdentifier
    case invalidReference
}

struct NovelStructuredOutputFailure: Error, Equatable, Sendable {
    let category: NovelStructuredOutputErrorCategory
    let path: String
    let message: String
}

extension NovelStructuredOutputFailure: LocalizedError {
    var errorDescription: String? { message }
}

enum NovelPolishDriftVerdict: Equatable, Sendable {
    case compatible
    case incompatible([NovelPolishDifferenceV1])
    case invalidOutput(NovelStructuredOutputFailure)

    var allowsAdoption: Bool {
        if case .compatible = self { return true }
        return false
    }
}

enum NovelStructuredOutputDecoder {
    static func decodeCharacterProposal(from text: String) throws -> NovelCharacterProposalV1 {
        try decodeCharacterProposal(from: Data(text.utf8))
    }

    static func decodeCharacterProposal(from data: Data) throws -> NovelCharacterProposalV1 {
        let root = try StrictJSON.rootObject(from: data)
        try StrictJSON.validateCharacterProposal(root.object)
        let value: NovelCharacterProposalV1 = try decode(
            NovelCharacterProposalV1.self,
            from: root.data
        )
        try NovelStructuredOutputValidation.validate(value)
        return value
    }

    static func decodeQuickStartSuggestions(
        from text: String
    ) throws -> NovelQuickStartSuggestionsV2 {
        try decodeQuickStartSuggestions(from: Data(text.utf8))
    }

    static func decodeQuickStartSuggestions(
        from data: Data
    ) throws -> NovelQuickStartSuggestionsV2 {
        let root = try StrictJSON.rootObject(from: data)
        try StrictJSON.validateQuickStartSuggestions(root.object)
        let value: NovelQuickStartSuggestionsV2 = try decode(
            NovelQuickStartSuggestionsV2.self,
            from: root.data
        )
        try NovelStructuredOutputValidation.validate(value)
        return value
    }

    static func decodeStateDelta(from text: String) throws -> NovelStateDeltaV1 {
        try decodeStateDelta(from: Data(text.utf8))
    }

    static func decodeStateDelta(from data: Data) throws -> NovelStateDeltaV1 {
        let root = try StrictJSON.rootObject(from: data)
        try StrictJSON.validateStateDelta(root.object)
        let value: NovelStateDeltaV1 = try decode(NovelStateDeltaV1.self, from: root.data)
        try NovelStructuredOutputValidation.validate(value)
        return value
    }

    static func decodeStateRebuild(from text: String) throws -> NovelStateRebuildV1 {
        try decodeStateRebuild(from: Data(text.utf8))
    }

    static func decodeStateRebuild(from data: Data) throws -> NovelStateRebuildV1 {
        let root = try StrictJSON.rootObject(from: data)
        try StrictJSON.validateStateRebuild(root.object)
        let value: NovelStateRebuildV1 = try decode(NovelStateRebuildV1.self, from: root.data)
        try NovelStructuredOutputValidation.validate(value)
        return value
    }

    static func decodePolishDrift(from text: String) throws -> NovelPolishDriftV1 {
        try decodePolishDrift(from: Data(text.utf8))
    }

    static func decodePolishDrift(from data: Data) throws -> NovelPolishDriftV1 {
        let root = try StrictJSON.rootObject(from: data)
        try StrictJSON.validatePolishDrift(root.object)
        let value: NovelPolishDriftV1 = try decode(NovelPolishDriftV1.self, from: root.data)
        try NovelStructuredOutputValidation.validate(value)
        return value
    }

    static func decodeChapterPlanAcceptance(from text: String) throws -> NovelChapterPlanAcceptanceV1 {
        try decodeChapterPlanAcceptance(from: Data(text.utf8))
    }

    static func decodeChapterPlanAcceptance(from data: Data) throws -> NovelChapterPlanAcceptanceV1 {
        let root = try StrictJSON.rootObject(from: data)
        try StrictJSON.validateChapterPlanAcceptance(root.object)
        let value: NovelChapterPlanAcceptanceV1 = try decode(
            NovelChapterPlanAcceptanceV1.self,
            from: root.data
        )
        try NovelStructuredOutputValidation.validate(value)
        return value
    }

    static func decodeChapterPlanProposal(from text: String) throws -> NovelChapterPlanProposalV1 {
        try decodeChapterPlanProposal(from: Data(text.utf8))
    }

    static func decodeChapterPlanProposal(from data: Data) throws -> NovelChapterPlanProposalV1 {
        let root = try StrictJSON.rootObject(from: data)
        try StrictJSON.validateChapterPlanProposal(root.object)
        let value: NovelChapterPlanProposalV1 = try decode(
            NovelChapterPlanProposalV1.self,
            from: root.data
        )
        try NovelStructuredOutputValidation.validate(value)
        return value
    }

    static func decodeContinuityAudit(from text: String) throws -> NovelContinuityAuditV1 {
        try decodeContinuityAudit(from: Data(text.utf8))
    }

    static func decodeContinuityAudit(from data: Data) throws -> NovelContinuityAuditV1 {
        let root = try StrictJSON.rootObject(from: data)
        try StrictJSON.validateContinuityAudit(root.object)
        let value: NovelContinuityAuditV1 = try decode(
            NovelContinuityAuditV1.self,
            from: root.data
        )
        try NovelStructuredOutputValidation.validate(value)
        return value
    }

    static func decodeDiscussionArchive(from text: String) throws -> NovelDiscussionArchiveV1 {
        try decodeDiscussionArchive(from: Data(text.utf8))
    }

    static func decodeDiscussionArchive(from data: Data) throws -> NovelDiscussionArchiveV1 {
        let root = try StrictJSON.rootObject(from: data)
        try StrictJSON.validateDiscussionArchive(root.object)
        let value: NovelDiscussionArchiveV1 = try decode(
            NovelDiscussionArchiveV1.self,
            from: root.data
        )
        try NovelStructuredOutputValidation.validate(value)
        return value
    }

    static func polishDriftVerdict(from text: String) -> NovelPolishDriftVerdict {
        polishDriftVerdict(from: Data(text.utf8))
    }

    static func polishDriftVerdict(from data: Data) -> NovelPolishDriftVerdict {
        do {
            let result = try decodePolishDrift(from: data)
            return result.compatible ? .compatible : .incompatible(result.differences)
        } catch let failure as NovelStructuredOutputFailure {
            return .invalidOutput(failure)
        } catch {
            return .invalidOutput(NovelStructuredOutputFailure(
                category: .malformedJSON,
                path: "$",
                message: "The polish drift check returned unreadable JSON."
            ))
        }
    }

    private static func decode<Value: Decodable>(
        _ type: Value.Type,
        from data: Data
    ) throws -> Value {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch let error as DecodingError {
            throw map(error)
        } catch {
            throw NovelStructuredOutputFailure(
                category: .malformedJSON,
                path: "$",
                message: "The model returned unreadable JSON."
            )
        }
    }

    private static func map(_ error: DecodingError) -> NovelStructuredOutputFailure {
        switch error {
        case .keyNotFound(let key, let context):
            NovelStructuredOutputFailure(
                category: .missingField,
                path: StrictJSON.path(context.codingPath),
                message: "The model output is missing required field '\(key.stringValue)'."
            )
        case .typeMismatch(_, let context), .valueNotFound(_, let context):
            NovelStructuredOutputFailure(
                category: .typeMismatch,
                path: StrictJSON.path(context.codingPath),
                message: "The model output contains a value with the wrong type."
            )
        case .dataCorrupted(let context):
            NovelStructuredOutputFailure(
                category: .invalidValue,
                path: StrictJSON.path(context.codingPath),
                message: "The model output contains an unsupported value."
            )
        @unknown default:
            NovelStructuredOutputFailure(
                category: .malformedJSON,
                path: "$",
                message: "The model returned unreadable JSON."
            )
        }
    }
}

private enum NovelStructuredOutputValidation {
    static func validate(_ value: NovelCharacterProposalV1) throws {
        try schemaVersion(
            value.schemaVersion,
            expected: NovelCharacterProposalV1.currentSchemaVersion
        )
        try required(value.character.title, path: "$.character.title")
        try required(value.character.content, path: "$.character.content")
        let aliases = NovelCharacterIdentityResolver.normalizedAliases(
            value.character.aliases ?? []
        )
        guard aliases.count == value.character.aliases?.count else {
            throw failure(
                .invalidValue,
                path: "$.character.aliases",
                message: "Character aliases must be unique non-empty strings."
            )
        }
        guard value.relatedSuggestions.count <= NovelCharacterRelatedSuggestionKind.allCases.count,
              Set(value.relatedSuggestions.map(\.kind)).count == value.relatedSuggestions.count else {
            throw failure(
                .invalidValue,
                path: "$.relatedSuggestions",
                message: "Related setting suggestions may contain each supported kind at most once."
            )
        }
        for (index, suggestion) in value.relatedSuggestions.enumerated() {
            try required(suggestion.title, path: "$.relatedSuggestions[\(index)].title")
            try required(suggestion.content, path: "$.relatedSuggestions[\(index)].content")
        }
    }

    static func validate(_ value: NovelQuickStartSuggestionsV2) throws {
        guard value.schemaVersion == NovelQuickStartSuggestionsV2.legacySchemaVersion ||
                value.schemaVersion == NovelQuickStartSuggestionsV2.previousSchemaVersion ||
                value.schemaVersion == NovelQuickStartSuggestionsV2.currentSchemaVersion else {
            throw failure(
                .unsupportedVersion,
                path: "$.schemaVersion",
                message: "The model returned unsupported quick-start schema version \(value.schemaVersion)."
            )
        }
        try required(value.overview, path: "$.overview")
        let suggestions: [(String, NovelQuickStartSuggestionV1)] = [
            ("world", value.world),
            ("masterOutline", value.masterOutline),
            ("writingRequirements", value.writingRequirements)
        ]
        for (key, suggestion) in suggestions {
            try required(suggestion.title, path: "$.\(key).title")
            try required(suggestion.content, path: "$.\(key).content")
        }
        guard !value.characters.isEmpty else {
            throw failure(
                .invalidValue,
                path: "$.characters",
                message: "Quick Start must propose at least one independent character."
            )
        }
        for (index, character) in value.characters.enumerated() {
            try required(character.title, path: "$.characters[\(index)].title")
            try required(character.content, path: "$.characters[\(index)].content")
            if value.schemaVersion == NovelQuickStartSuggestionsV2.currentSchemaVersion {
                let aliases = NovelCharacterIdentityResolver.normalizedAliases(character.aliases ?? [])
                guard aliases.count == character.aliases?.count else {
                    throw failure(
                        .invalidValue,
                        path: "$.characters[\(index)].aliases",
                        message: "Character aliases must be unique non-empty strings."
                    )
                }
            }
        }
    }

    static func validate(_ value: NovelStateDeltaV1) throws {
        try schemaVersion(value.schemaVersion, expected: NovelStateDeltaV1.currentSchemaVersion)
        try required(value.stateSummary, path: "$.stateSummary")
        if let patch = value.branchOutlinePatch {
            try required(patch, path: "$.branchOutlinePatch")
        }
        try stateFacts(
            events: value.events,
            characters: value.characterChanges,
            relationships: value.relationshipChanges,
            foreshadowing: value.foreshadowingChanges,
            unresolvedEntityNames: value.unresolvedEntityNames,
            proposals: value.settingProposals
        )
    }

    static func validate(_ value: NovelStateRebuildV1) throws {
        try schemaVersion(value.schemaVersion, expected: NovelStateRebuildV1.currentSchemaVersion)
        try required(value.stateSummary, path: "$.stateSummary")
        try required(value.branchOutline, path: "$.branchOutline")
        try stateFacts(
            events: value.events,
            characters: value.characterStates,
            relationships: value.relationships,
            foreshadowing: value.foreshadowing,
            unresolvedEntityNames: value.unresolvedEntityNames,
            proposals: value.settingProposals
        )
    }

    static func validate(_ value: NovelPolishDriftV1) throws {
        try schemaVersion(value.schemaVersion, expected: NovelPolishDriftV1.currentSchemaVersion)
        var identifiers: Set<String> = []
        for (index, difference) in value.differences.enumerated() {
            let base = "$.differences[\(index)]"
            try identifier(difference.id, path: base + ".id", identifiers: &identifiers)
            try required(difference.summary, path: base + ".summary")
            try required(difference.sourceEvidence, path: base + ".sourceEvidence")
            try required(difference.candidateEvidence, path: base + ".candidateEvidence")
        }
        if value.compatible && !value.differences.isEmpty {
            throw failure(
                .invalidValue,
                path: "$.differences",
                message: "A compatible polish result cannot contain semantic differences."
            )
        }
        if !value.compatible && value.differences.isEmpty {
            throw failure(
                .invalidValue,
                path: "$.differences",
                message: "An incompatible polish result must describe at least one difference."
            )
        }
    }

    static func validate(_ value: NovelChapterPlanAcceptanceV1) throws {
        guard value.schemaVersion == NovelChapterPlanAcceptanceV1.legacySchemaVersion ||
            value.schemaVersion == NovelChapterPlanAcceptanceV1.currentSchemaVersion else {
            throw failure(
                .unsupportedVersion,
                path: "$.schemaVersion",
                message: "The model returned schema version \(value.schemaVersion); version \(NovelChapterPlanAcceptanceV1.currentSchemaVersion) is required."
            )
        }
        try required(value.summary, path: "$.summary")
        for (index, item) in value.missingMustHappen.enumerated() {
            try required(item, path: "$.missingMustHappen[\(index)]")
        }
        for (index, item) in value.forbiddenViolations.enumerated() {
            try required(item, path: "$.forbiddenViolations[\(index)]")
        }
        for (index, item) in value.obviousRepetition.enumerated() {
            try required(item, path: "$.obviousRepetition[\(index)]")
        }
        let hasViolation = !value.missingMustHappen.isEmpty || !value.forbiddenViolations.isEmpty
        if value.accepted && hasViolation {
            throw failure(
                .invalidValue,
                path: "$",
                message: "An accepted chapter-plan result cannot list contract violations."
            )
        }
        if !value.accepted && !hasViolation {
            throw failure(
                .invalidValue,
                path: "$",
                message: "A rejected chapter-plan result must list at least one violation."
            )
        }
    }

    static func validate(_ value: NovelChapterPlanProposalV1) throws {
        try schemaVersion(
            value.schemaVersion,
            expected: NovelChapterPlanProposalV1.currentSchemaVersion
        )
        try required(value.goalAndConflict, path: "$.goalAndConflict")
        if value.goalAndConflict.count > 8_000 {
            throw failure(
                .invalidValue,
                path: "$.goalAndConflict",
                message: "The chapter-plan goal is too long."
            )
        }
        if value.outlinePlacement.count > 500 {
            throw failure(
                .invalidValue,
                path: "$.outlinePlacement",
                message: "The chapter-plan placement note is too long."
            )
        }
        if value.endingHook.count > 4_000 {
            throw failure(
                .invalidValue,
                path: "$.endingHook",
                message: "The chapter-plan ending hook is too long."
            )
        }
        let mustHappen = NovelChapterPlanRecord.normalizedLines(value.mustHappen)
        let mustNotHappen = NovelChapterPlanRecord.normalizedLines(value.mustNotHappen)
        let visibleFacts = NovelChapterPlanRecord.normalizedLines(value.visibleFacts)
        guard !mustHappen.isEmpty else {
            throw failure(
                .invalidValue,
                path: "$.mustHappen",
                message: "A confirmed chapter plan requires at least one must-happen item."
            )
        }
        guard mustHappen.count <= 32, mustNotHappen.count <= 32, visibleFacts.count <= 32 else {
            throw failure(
                .invalidValue,
                path: "$",
                message: "The chapter plan has too many checklist items."
            )
        }
        for (index, item) in mustHappen.enumerated() {
            try required(item, path: "$.mustHappen[\(index)]")
        }
        for (index, item) in mustNotHappen.enumerated() {
            try required(item, path: "$.mustNotHappen[\(index)]")
        }
        for (index, item) in visibleFacts.enumerated() {
            try required(item, path: "$.visibleFacts[\(index)]")
        }
    }

    static func validate(_ value: NovelContinuityAuditV1) throws {
        try schemaVersion(
            value.schemaVersion,
            expected: NovelContinuityAuditV1.currentSchemaVersion
        )
        var identifiers: Set<String> = []
        for (index, issue) in value.issues.enumerated() {
            let base = "$.issues[\(index)]"
            try identifier(issue.id, path: base + ".id", identifiers: &identifiers)
            try required(issue.summary, path: base + ".summary")
            guard issue.references.count >= NovelContinuityAuditV1.minimumReferenceCount else {
                throw failure(
                    .invalidValue,
                    path: base + ".references",
                    message: "A continuity issue must cite at least \(NovelContinuityAuditV1.minimumReferenceCount) passages."
                )
            }
            for (referenceIndex, reference) in issue.references.enumerated() {
                let referencePath = base + ".references[\(referenceIndex)]"
                guard reference.chapterOrdinal >= 1 else {
                    throw failure(
                        .invalidValue,
                        path: referencePath + ".chapterOrdinal",
                        message: "Chapter ordinals start at 1."
                    )
                }
                try required(reference.chapterTitle, path: referencePath + ".chapterTitle")
                try required(reference.evidence, path: referencePath + ".evidence")
            }
        }
        if value.consistent && !value.issues.isEmpty {
            throw failure(
                .invalidValue,
                path: "$.issues",
                message: "A consistent audit cannot report issues."
            )
        }
        if !value.consistent && value.issues.isEmpty {
            throw failure(
                .invalidValue,
                path: "$.issues",
                message: "An inconsistent audit must describe at least one issue."
            )
        }
    }

    static func validate(_ value: NovelDiscussionArchiveV1) throws {
        try schemaVersion(
            value.schemaVersion,
            expected: NovelDiscussionArchiveV1.currentSchemaVersion
        )
        guard !value.decisions.isEmpty else {
            throw failure(
                .invalidValue,
                path: "$.decisions",
                message: "A discussion archive must contain at least one decision."
            )
        }
        for (index, decision) in value.decisions.enumerated() {
            try required(decision.topic, path: "$.decisions[\(index)].topic")
            try required(decision.decision, path: "$.decisions[\(index)].decision")
            if let relatedMaterialID = decision.relatedMaterialID,
               UUID(uuidString: relatedMaterialID) == nil {
                throw failure(
                    .invalidReference,
                    path: "$.decisions[\(index)].relatedMaterialID",
                    message: "A related material ID must be a UUID."
                )
            }
        }
        try required(value.summary, path: "$.summary")
        guard value.summary.count <= 300 else {
            throw failure(
                .invalidValue,
                path: "$.summary",
                message: "A discussion archive summary must not exceed 300 characters."
            )
        }
    }

    private static func stateFacts(
        events: [NovelStateEventV1],
        characters: [NovelCharacterStateFactV1],
        relationships: [NovelRelationshipFactV1],
        foreshadowing: [NovelForeshadowingFactV1],
        unresolvedEntityNames: [String],
        proposals: [NovelSettingProposalDraftV1]
    ) throws {
        var identifiers: Set<String> = []
        for (index, event) in events.enumerated() {
            let base = "$.events[\(index)]"
            try identifier(event.id, path: base + ".id", identifiers: &identifiers)
            try required(event.kind, path: base + ".kind")
            try required(event.summary, path: base + ".summary")
            try required(event.evidence, path: base + ".evidence")
            try uniqueNames(event.entityReferences, path: base + ".entityReferences")
        }
        for (index, character) in characters.enumerated() {
            let base = "$.characterFacts[\(index)]"
            try identifier(character.id, path: base + ".id", identifiers: &identifiers)
            try required(character.characterName, path: base + ".characterName")
            try required(character.attribute, path: base + ".attribute")
            try required(character.value, path: base + ".value")
            try required(character.evidence, path: base + ".evidence")
        }
        for (index, relationship) in relationships.enumerated() {
            let base = "$.relationshipFacts[\(index)]"
            try identifier(relationship.id, path: base + ".id", identifiers: &identifiers)
            try required(relationship.sourceEntity, path: base + ".sourceEntity")
            try required(relationship.targetEntity, path: base + ".targetEntity")
            try required(relationship.relationship, path: base + ".relationship")
            try required(relationship.state, path: base + ".state")
            try required(relationship.evidence, path: base + ".evidence")
            if normalized(relationship.sourceEntity) == normalized(relationship.targetEntity) {
                throw failure(
                    .invalidReference,
                    path: base,
                    message: "A relationship must reference two different entities."
                )
            }
        }
        for (index, thread) in foreshadowing.enumerated() {
            let base = "$.foreshadowingFacts[\(index)]"
            try identifier(thread.id, path: base + ".id", identifiers: &identifiers)
            try required(thread.thread, path: base + ".thread")
            try required(thread.summary, path: base + ".summary")
            try required(thread.evidence, path: base + ".evidence")
        }
        for (index, proposal) in proposals.enumerated() {
            let base = "$.settingProposals[\(index)]"
            try identifier(proposal.id, path: base + ".id", identifiers: &identifiers)
            try required(proposal.title, path: base + ".title")
            try required(proposal.content, path: base + ".content")
            try required(proposal.evidence, path: base + ".evidence")
        }
        try uniqueNames(unresolvedEntityNames, path: "$.unresolvedEntityNames")
    }

    private static func schemaVersion(_ actual: Int, expected: Int) throws {
        guard actual == expected else {
            throw failure(
                .unsupportedVersion,
                path: "$.schemaVersion",
                message: "The model returned schema version \(actual); version \(expected) is required."
            )
        }
    }

    private static func required(_ value: String, path: String) throws {
        guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw failure(
                .invalidValue,
                path: path,
                message: "The model output contains an empty required value at \(path)."
            )
        }
    }

    private static func identifier(
        _ value: String,
        path: String,
        identifiers: inout Set<String>
    ) throws {
        try required(value, path: path)
        guard value.count <= 128,
              value == value.trimmingCharacters(in: .whitespacesAndNewlines),
              value.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              value.rangeOfCharacter(from: .controlCharacters) == nil else {
            throw failure(
                .invalidValue,
                path: path,
                message: "The model output contains an invalid identifier at \(path)."
            )
        }
        guard identifiers.insert(value).inserted else {
            throw failure(
                .duplicateIdentifier,
                path: path,
                message: "The model output repeats identifier '\(value)'."
            )
        }
    }

    private static func uniqueNames(_ values: [String], path: String) throws {
        var seen: Set<String> = []
        for (index, value) in values.enumerated() {
            try required(value, path: "\(path)[\(index)]")
            let key = normalized(value)
            guard seen.insert(key).inserted else {
                throw failure(
                    .invalidReference,
                    path: path,
                    message: "The model output repeats an entity reference at \(path)."
                )
            }
        }
    }

    private static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private static func failure(
        _ category: NovelStructuredOutputErrorCategory,
        path: String,
        message: String
    ) -> NovelStructuredOutputFailure {
        NovelStructuredOutputFailure(category: category, path: path, message: message)
    }
}

private enum StrictJSON {
    typealias Object = [String: Any]

    static func rootObject(from data: Data) throws -> (object: Object, data: Data) {
        let objectData = try singleObjectData(from: data)
        try JSONDuplicateKeyValidator.validate(objectData)
        let value: Any
        do {
            value = try JSONSerialization.jsonObject(with: objectData)
        } catch {
            throw NovelStructuredOutputFailure(
                category: .malformedJSON,
                path: "$",
                message: "The model returned malformed JSON."
            )
        }
        guard let object = value as? Object else {
            throw NovelStructuredOutputFailure(
                category: .expectedObject,
                path: "$",
                message: "The model output must be one JSON object."
            )
        }
        return (object, objectData)
    }

    private static func singleObjectData(from data: Data) throws -> Data {
        if (try? JSONSerialization.jsonObject(with: data)) != nil {
            return data
        }

        let bytes = Array(data)
        var candidates: [Data] = []
        var index = bytes.startIndex

        while index < bytes.endIndex {
            guard bytes[index] == 0x7B,
                  let endIndex = balancedObjectEnd(in: bytes, startingAt: index) else {
                index += 1
                continue
            }

            let candidate = Data(bytes[index...endIndex])
            if let value = try? JSONSerialization.jsonObject(with: candidate),
               value is Object {
                candidates.append(candidate)
                index = endIndex + 1
            } else {
                index += 1
            }
        }

        guard candidates.count == 1, let candidate = candidates.first else {
            let message = candidates.count > 1
                ? "The model returned more than one JSON object."
                : "The model returned malformed JSON."
            throw NovelStructuredOutputFailure(
                category: .malformedJSON,
                path: "$",
                message: message
            )
        }
        return candidate
    }

    private static func balancedObjectEnd(
        in bytes: [UInt8],
        startingAt startIndex: Int
    ) -> Int? {
        var depth = 0
        var isInsideString = false
        var index = startIndex

        while index < bytes.endIndex {
            let byte = bytes[index]
            if isInsideString {
                if byte == 0x5C {
                    index += 2
                    continue
                }
                if byte == 0x22 {
                    isInsideString = false
                }
            } else {
                switch byte {
                case 0x22:
                    isInsideString = true
                case 0x7B:
                    depth += 1
                case 0x7D:
                    depth -= 1
                    if depth == 0 {
                        return index
                    }
                default:
                    break
                }
            }
            index += 1
        }
        return nil
    }

    static func validateQuickStartSuggestions(_ object: Object) throws {
        try keys(
            object,
            path: "$",
            required: [
                "schemaVersion", "overview", "world", "characters",
                "masterOutline", "writingRequirements"
            ]
        )
        guard let number = object["schemaVersion"] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite,
              number.doubleValue.rounded() == number.doubleValue else {
            throw typeFailure(path: "$.schemaVersion", expected: "an integer")
        }
        let version = number.intValue
        guard version == NovelQuickStartSuggestionsV2.legacySchemaVersion ||
                version == NovelQuickStartSuggestionsV2.previousSchemaVersion ||
                version == NovelQuickStartSuggestionsV2.currentSchemaVersion else {
            throw NovelStructuredOutputFailure(
                category: .unsupportedVersion,
                path: "$.schemaVersion",
                message: "The model returned an unsupported quick-start schema version."
            )
        }
        try string(object["overview"], path: "$.overview")
        for key in ["world", "masterOutline", "writingRequirements"] {
            guard let suggestion = object[key] as? Object else {
                throw NovelStructuredOutputFailure(
                    category: .typeMismatch,
                    path: "$.\(key)",
                    message: "The quick-start suggestion at $.\(key) must be an object."
                )
            }
            try keys(
                suggestion,
                path: "$.\(key)",
                required: ["title", "content"],
                optional: ["title_cn"]
            )
            try string(suggestion["title"], path: "$.\(key).title")
            try string(suggestion["content"], path: "$.\(key).content")
            if let translationHint = suggestion["title_cn"], !(translationHint is NSNull) {
                throw NovelStructuredOutputFailure(
                    category: .invalidValue,
                    path: "$.\(key).title_cn",
                    message: "Quick Start only accepts an empty title translation hint."
                )
            }
        }
        let characters: [Object]
        if version == NovelQuickStartSuggestionsV2.legacySchemaVersion {
            guard let character = object["characters"] as? Object else {
                throw typeFailure(path: "$.characters", expected: "an object")
            }
            characters = [character]
        } else {
            guard let values = object["characters"] as? [Any] else {
                throw typeFailure(path: "$.characters", expected: "an array")
            }
            guard !values.isEmpty else {
                throw NovelStructuredOutputFailure(
                    category: .invalidValue,
                    path: "$.characters",
                    message: "Quick Start must propose at least one independent character."
                )
            }
            characters = try values.enumerated().map { index, value in
                guard let character = value as? Object else {
                    throw typeFailure(path: "$.characters[\(index)]", expected: "an object")
                }
                return character
            }
        }
        for (index, character) in characters.enumerated() {
            let path = version == NovelQuickStartSuggestionsV2.legacySchemaVersion
                ? "$.characters"
                : "$.characters[\(index)]"
            let requiredKeys: Set<String> = version == NovelQuickStartSuggestionsV2.currentSchemaVersion
                ? ["title", "content", "aliases"]
                : ["title", "content"]
            try keys(
                character,
                path: path,
                required: requiredKeys,
                optional: ["title_cn"]
            )
            try string(character["title"], path: path + ".title")
            try string(character["content"], path: path + ".content")
            if let translationHint = character["title_cn"], !(translationHint is NSNull) {
                throw NovelStructuredOutputFailure(
                    category: .invalidValue,
                    path: path + ".title_cn",
                    message: "Quick Start only accepts an empty title translation hint."
                )
            }
            if version == NovelQuickStartSuggestionsV2.currentSchemaVersion {
                try stringArray(character["aliases"], path: path + ".aliases")
            }
        }
    }

    static func validateCharacterProposal(_ object: Object) throws {
        try keys(
            object,
            path: "$",
            required: ["schemaVersion", "character", "relatedSuggestions"]
        )
        try schemaVersion(
            object["schemaVersion"],
            expected: NovelCharacterProposalV1.currentSchemaVersion
        )
        guard let character = object["character"] as? Object else {
            throw typeFailure(path: "$.character", expected: "an object")
        }
        try keys(
            character,
            path: "$.character",
            required: ["title", "content", "aliases"]
        )
        try string(character["title"], path: "$.character.title")
        try string(character["content"], path: "$.character.content")
        try stringArray(character["aliases"], path: "$.character.aliases")
        try objectArray(
            object["relatedSuggestions"],
            path: "$.relatedSuggestions",
            requiredKeys: ["kind", "title", "content"]
        ) { item, path in
            try enumString(
                item["kind"],
                path: path + ".kind",
                allowed: Set(NovelCharacterRelatedSuggestionKind.allCases.map(\.rawValue))
            )
            try string(item["title"], path: path + ".title")
            try string(item["content"], path: path + ".content")
        }
    }

    static func validateStateDelta(_ object: Object) throws {
        try keys(
            object,
            path: "$",
            required: [
                "schemaVersion", "stateSummary", "events", "characterChanges",
                "relationshipChanges", "foreshadowingChanges", "unresolvedEntityNames",
                "branchOutlinePatch", "settingProposals"
            ]
        )
        try schemaVersion(object["schemaVersion"], expected: NovelStateDeltaV1.currentSchemaVersion)
        try string(object["stateSummary"], path: "$.stateSummary")
        try eventArray(object["events"], path: "$.events")
        try characterArray(object["characterChanges"], path: "$.characterChanges")
        try relationshipArray(object["relationshipChanges"], path: "$.relationshipChanges")
        try foreshadowingArray(object["foreshadowingChanges"], path: "$.foreshadowingChanges")
        try stringArray(object["unresolvedEntityNames"], path: "$.unresolvedEntityNames")
        try nullableString(object["branchOutlinePatch"], path: "$.branchOutlinePatch")
        try proposalArray(object["settingProposals"], path: "$.settingProposals")
    }

    static func validateStateRebuild(_ object: Object) throws {
        try keys(
            object,
            path: "$",
            required: [
                "schemaVersion", "stateSummary", "branchOutline", "events", "characterStates",
                "relationships", "foreshadowing", "unresolvedEntityNames", "settingProposals"
            ]
        )
        try schemaVersion(object["schemaVersion"], expected: NovelStateRebuildV1.currentSchemaVersion)
        try string(object["stateSummary"], path: "$.stateSummary")
        try string(object["branchOutline"], path: "$.branchOutline")
        try eventArray(object["events"], path: "$.events")
        try characterArray(object["characterStates"], path: "$.characterStates")
        try relationshipArray(object["relationships"], path: "$.relationships")
        try foreshadowingArray(object["foreshadowing"], path: "$.foreshadowing")
        try stringArray(object["unresolvedEntityNames"], path: "$.unresolvedEntityNames")
        try proposalArray(object["settingProposals"], path: "$.settingProposals")
    }

    static func validatePolishDrift(_ object: Object) throws {
        try keys(object, path: "$", required: ["schemaVersion", "compatible", "differences"])
        try schemaVersion(object["schemaVersion"], expected: NovelPolishDriftV1.currentSchemaVersion)
        try boolean(object["compatible"], path: "$.compatible")
        try objectArray(
            object["differences"],
            path: "$.differences",
            requiredKeys: ["id", "category", "summary", "sourceEvidence", "candidateEvidence"]
        ) { item, path in
            try string(item["id"], path: path + ".id")
            try enumString(
                item["category"],
                path: path + ".category",
                allowed: Set(NovelPolishDifferenceCategoryV1.allCases.map(\.rawValue))
            )
            try string(item["summary"], path: path + ".summary")
            try string(item["sourceEvidence"], path: path + ".sourceEvidence")
            try string(item["candidateEvidence"], path: path + ".candidateEvidence")
        }
    }

    static func validateChapterPlanAcceptance(_ object: Object) throws {
        guard let number = object["schemaVersion"] as? NSNumber,
              floor(number.doubleValue) == number.doubleValue else {
            throw typeFailure(path: "$.schemaVersion", expected: "an integer")
        }
        let version = number.intValue
        guard version == NovelChapterPlanAcceptanceV1.legacySchemaVersion ||
            version == NovelChapterPlanAcceptanceV1.currentSchemaVersion else {
            throw NovelStructuredOutputFailure(
                category: .unsupportedVersion,
                path: "$.schemaVersion",
                message: "The model returned schema version \(version); version \(NovelChapterPlanAcceptanceV1.currentSchemaVersion) is required."
            )
        }
        if version == NovelChapterPlanAcceptanceV1.currentSchemaVersion {
            try keys(
                object,
                path: "$",
                required: [
                    "schemaVersion",
                    "accepted",
                    "missingMustHappen",
                    "forbiddenViolations",
                    "obviousRepetition",
                    "summary",
                ]
            )
        } else {
            try keys(
                object,
                path: "$",
                required: [
                    "schemaVersion",
                    "accepted",
                    "missingMustHappen",
                    "forbiddenViolations",
                    "summary",
                ],
                optional: ["obviousRepetition"]
            )
        }
        try boolean(object["accepted"], path: "$.accepted")
        try string(object["summary"], path: "$.summary")
        try stringArray(object["missingMustHappen"], path: "$.missingMustHappen")
        try stringArray(object["forbiddenViolations"], path: "$.forbiddenViolations")
        if object["obviousRepetition"] != nil {
            try stringArray(object["obviousRepetition"], path: "$.obviousRepetition")
        }
    }

    static func validateChapterPlanProposal(_ object: Object) throws {
        try keys(
            object,
            path: "$",
            required: [
                "schemaVersion",
                "outlinePlacement",
                "goalAndConflict",
                "mustHappen",
                "mustNotHappen",
                "endingHook",
                "visibleFacts",
            ]
        )
        try schemaVersion(
            object["schemaVersion"],
            expected: NovelChapterPlanProposalV1.currentSchemaVersion
        )
        try string(object["outlinePlacement"], path: "$.outlinePlacement")
        try string(object["goalAndConflict"], path: "$.goalAndConflict")
        try string(object["endingHook"], path: "$.endingHook")
        try stringArray(object["mustHappen"], path: "$.mustHappen")
        try stringArray(object["mustNotHappen"], path: "$.mustNotHappen")
        try stringArray(object["visibleFacts"], path: "$.visibleFacts")
    }

    static func validateContinuityAudit(_ object: Object) throws {
        try keys(object, path: "$", required: ["schemaVersion", "consistent", "issues"])
        try schemaVersion(
            object["schemaVersion"],
            expected: NovelContinuityAuditV1.currentSchemaVersion
        )
        try boolean(object["consistent"], path: "$.consistent")
        try objectArray(
            object["issues"],
            path: "$.issues",
            requiredKeys: ["id", "category", "severity", "summary", "references"]
        ) { item, path in
            try string(item["id"], path: path + ".id")
            try enumString(
                item["category"],
                path: path + ".category",
                allowed: Set(NovelContinuityIssueCategoryV1.allCases.map(\.rawValue))
            )
            try enumString(
                item["severity"],
                path: path + ".severity",
                allowed: Set(NovelContinuityIssueSeverityV1.allCases.map(\.rawValue))
            )
            try string(item["summary"], path: path + ".summary")
            try objectArray(
                item["references"],
                path: path + ".references",
                requiredKeys: ["chapterOrdinal", "chapterTitle", "evidence"]
            ) { reference, referencePath in
                try integer(reference["chapterOrdinal"], path: referencePath + ".chapterOrdinal")
                try string(reference["chapterTitle"], path: referencePath + ".chapterTitle")
                try string(reference["evidence"], path: referencePath + ".evidence")
            }
        }
    }

    static func validateDiscussionArchive(_ object: Object) throws {
        try keys(object, path: "$", required: ["schemaVersion", "decisions", "summary"])
        try schemaVersion(
            object["schemaVersion"],
            expected: NovelDiscussionArchiveV1.currentSchemaVersion
        )
        try objectArray(
            object["decisions"],
            path: "$.decisions",
            requiredKeys: ["topic", "decision"],
            optionalKeys: ["relatedMaterialID"]
        ) { item, path in
            try string(item["topic"], path: path + ".topic")
            try string(item["decision"], path: path + ".decision")
            if let relatedMaterialID = item["relatedMaterialID"] {
                try nullableString(relatedMaterialID, path: path + ".relatedMaterialID")
            }
        }
        try string(object["summary"], path: "$.summary")
    }

    static func path(_ codingPath: [any CodingKey]) -> String {
        codingPath.reduce("$") { result, key in
            if let index = key.intValue { return result + "[\(index)]" }
            return result + "." + key.stringValue
        }
    }

    private static func eventArray(_ value: Any?, path: String) throws {
        try objectArray(
            value,
            path: path,
            requiredKeys: ["id", "kind", "summary", "entityReferences", "evidence"]
        ) { item, itemPath in
            try string(item["id"], path: itemPath + ".id")
            try string(item["kind"], path: itemPath + ".kind")
            try string(item["summary"], path: itemPath + ".summary")
            try stringArray(item["entityReferences"], path: itemPath + ".entityReferences")
            try string(item["evidence"], path: itemPath + ".evidence")
        }
    }

    private static func characterArray(_ value: Any?, path: String) throws {
        try objectArray(
            value,
            path: path,
            requiredKeys: ["id", "characterName", "attribute", "value", "evidence"]
        ) { item, itemPath in
            try string(item["id"], path: itemPath + ".id")
            try string(item["characterName"], path: itemPath + ".characterName")
            try string(item["attribute"], path: itemPath + ".attribute")
            try string(item["value"], path: itemPath + ".value")
            try string(item["evidence"], path: itemPath + ".evidence")
        }
    }

    private static func relationshipArray(_ value: Any?, path: String) throws {
        try objectArray(
            value,
            path: path,
            requiredKeys: [
                "id", "sourceEntity", "targetEntity", "relationship", "state", "evidence"
            ]
        ) { item, itemPath in
            try string(item["id"], path: itemPath + ".id")
            try string(item["sourceEntity"], path: itemPath + ".sourceEntity")
            try string(item["targetEntity"], path: itemPath + ".targetEntity")
            try string(item["relationship"], path: itemPath + ".relationship")
            try string(item["state"], path: itemPath + ".state")
            try string(item["evidence"], path: itemPath + ".evidence")
        }
    }

    private static func foreshadowingArray(_ value: Any?, path: String) throws {
        try objectArray(
            value,
            path: path,
            requiredKeys: ["id", "thread", "status", "summary", "evidence"]
        ) { item, itemPath in
            try string(item["id"], path: itemPath + ".id")
            try string(item["thread"], path: itemPath + ".thread")
            try enumString(
                item["status"],
                path: itemPath + ".status",
                allowed: Set(NovelForeshadowingStatusV1.allCases.map(\.rawValue))
            )
            try string(item["summary"], path: itemPath + ".summary")
            try string(item["evidence"], path: itemPath + ".evidence")
        }
    }

    private static func proposalArray(_ value: Any?, path: String) throws {
        try objectArray(
            value,
            path: path,
            requiredKeys: ["id", "title", "content", "evidence"]
        ) { item, itemPath in
            try string(item["id"], path: itemPath + ".id")
            try string(item["title"], path: itemPath + ".title")
            try string(item["content"], path: itemPath + ".content")
            try string(item["evidence"], path: itemPath + ".evidence")
        }
    }

    private static func objectArray(
        _ value: Any?,
        path: String,
        requiredKeys: Set<String>,
        optionalKeys: Set<String> = [],
        validate: (Object, String) throws -> Void
    ) throws {
        guard let array = value as? [Any] else {
            throw typeFailure(path: path, expected: "an array")
        }
        for (index, element) in array.enumerated() {
            let itemPath = "\(path)[\(index)]"
            guard let object = element as? Object else {
                throw typeFailure(path: itemPath, expected: "an object")
            }
            try keys(
                object,
                path: itemPath,
                required: requiredKeys,
                optional: optionalKeys
            )
            try validate(object, itemPath)
        }
    }

    private static func stringArray(_ value: Any?, path: String) throws {
        guard let array = value as? [Any] else {
            throw typeFailure(path: path, expected: "an array")
        }
        for (index, element) in array.enumerated() {
            try string(element, path: "\(path)[\(index)]")
        }
    }

    private static func string(_ value: Any?, path: String) throws {
        guard value is String else {
            throw typeFailure(path: path, expected: "a string")
        }
    }

    private static func nullableString(_ value: Any?, path: String) throws {
        guard value is NSNull || value is String else {
            throw typeFailure(path: path, expected: "a string or null")
        }
    }

    private static func integer(_ value: Any?, path: String) throws {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite,
              number.doubleValue.rounded() == number.doubleValue else {
            throw typeFailure(path: path, expected: "an integer")
        }
    }

    private static func boolean(_ value: Any?, path: String) throws {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID() else {
            throw typeFailure(path: path, expected: "a boolean")
        }
    }

    private static func enumString(
        _ value: Any?,
        path: String,
        allowed: Set<String>
    ) throws {
        try string(value, path: path)
        guard let rawValue = value as? String, allowed.contains(rawValue) else {
            throw NovelStructuredOutputFailure(
                category: .invalidValue,
                path: path,
                message: "The model output contains an unsupported value at \(path)."
            )
        }
    }

    private static func schemaVersion(_ value: Any?, expected: Int) throws {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite,
              number.doubleValue.rounded() == number.doubleValue else {
            throw typeFailure(path: "$.schemaVersion", expected: "an integer")
        }
        guard number.intValue == expected else {
            throw NovelStructuredOutputFailure(
                category: .unsupportedVersion,
                path: "$.schemaVersion",
                message: "The model returned schema version \(number.intValue); version \(expected) is required."
            )
        }
    }

    private static func keys(
        _ object: Object,
        path: String,
        required: Set<String>,
        optional: Set<String> = []
    ) throws {
        let present = Set(object.keys)
        let missing = required.subtracting(present).sorted()
        if !missing.isEmpty {
            throw NovelStructuredOutputFailure(
                category: .missingField,
                path: path,
                message: "The model output is missing required fields: \(missing.joined(separator: ", "))."
            )
        }
        let unknown = present.subtracting(required.union(optional)).sorted()
        if !unknown.isEmpty {
            throw NovelStructuredOutputFailure(
                category: .unknownField,
                path: path,
                message: "The model output contains unknown fields: \(unknown.joined(separator: ", "))."
            )
        }
    }

    private static func typeFailure(
        path: String,
        expected: String
    ) -> NovelStructuredOutputFailure {
        NovelStructuredOutputFailure(
            category: .typeMismatch,
            path: path,
            message: "The model output must contain \(expected) at \(path)."
        )
    }
}

/// `JSONSerialization` accepts duplicate object keys and silently keeps one
/// value. Validate that narrow integrity property before Foundation decodes the
/// otherwise-valid JSON document.
private struct JSONDuplicateKeyValidator {
    private let bytes: [UInt8]
    private var index = 0

    static func validate(_ data: Data) throws {
        var validator = JSONDuplicateKeyValidator(bytes: Array(data))
        try validator.parseValue(path: "$")
        validator.skipWhitespace()
        guard validator.index == validator.bytes.count else {
            throw validator.malformed()
        }
    }

    private mutating func parseValue(path: String) throws {
        skipWhitespace()
        guard let byte = current else { throw malformed() }
        switch byte {
        case 0x7B: // {
            try parseObject(path: path)
        case 0x5B: // [
            try parseArray(path: path)
        case 0x22: // "
            _ = try parseString()
        default:
            try parsePrimitive()
        }
    }

    private mutating func parseObject(path: String) throws {
        index += 1
        skipWhitespace()
        if consume(0x7D) { return }

        var keys: Set<String> = []
        while true {
            skipWhitespace()
            guard current == 0x22 else { throw malformed() }
            let key = try parseString()
            guard keys.insert(key).inserted else {
                throw NovelStructuredOutputFailure(
                    category: .duplicateKey,
                    path: path + "." + key,
                    message: "The model output repeats object key '\(key)'."
                )
            }
            skipWhitespace()
            guard consume(0x3A) else { throw malformed() }
            try parseValue(path: path + "." + key)
            skipWhitespace()
            if consume(0x7D) { return }
            guard consume(0x2C) else { throw malformed() }
        }
    }

    private mutating func parseArray(path: String) throws {
        index += 1
        skipWhitespace()
        if consume(0x5D) { return }

        var elementIndex = 0
        while true {
            try parseValue(path: "\(path)[\(elementIndex)]")
            elementIndex += 1
            skipWhitespace()
            if consume(0x5D) { return }
            guard consume(0x2C) else { throw malformed() }
        }
    }

    private mutating func parseString() throws -> String {
        let start = index
        guard consume(0x22) else { throw malformed() }
        var escaped = false
        while let byte = current {
            index += 1
            if escaped {
                escaped = false
            } else if byte == 0x5C {
                escaped = true
            } else if byte == 0x22 {
                let slice = Data(bytes[start..<index])
                guard let value = try? JSONDecoder().decode(String.self, from: slice) else {
                    throw malformed()
                }
                return value
            }
        }
        throw malformed()
    }

    private mutating func parsePrimitive() throws {
        let start = index
        while let byte = current,
              byte != 0x2C, byte != 0x5D, byte != 0x7D,
              !Self.isWhitespace(byte) {
            index += 1
        }
        guard index > start else { throw malformed() }
    }

    private mutating func skipWhitespace() {
        while let byte = current, Self.isWhitespace(byte) {
            index += 1
        }
    }

    private mutating func consume(_ byte: UInt8) -> Bool {
        guard current == byte else { return false }
        index += 1
        return true
    }

    private var current: UInt8? {
        index < bytes.count ? bytes[index] : nil
    }

    private static func isWhitespace(_ byte: UInt8) -> Bool {
        byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D
    }

    private func malformed() -> NovelStructuredOutputFailure {
        NovelStructuredOutputFailure(
            category: .malformedJSON,
            path: "$",
            message: "The model returned malformed JSON."
        )
    }
}

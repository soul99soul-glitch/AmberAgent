import Foundation

/// Compact, watch-safe view of a chat run. iPhone is the sole authority;
/// Watch only renders this snapshot and returns user intents.
struct WatchTaskSnapshot: Codable, Hashable, Sendable {
    var runId: String
    var conversationId: String?
    var kind: String
    var phase: String
    var stage: String
    var headline: String
    var detail: String?
    var summary: String?
    var metricText: String?
    var decision: WatchDecision?
    var actions: [WatchAction]
    var updatedAt: Date
    var isStale: Bool

    static let idle = WatchTaskSnapshot(
        runId: "",
        conversationId: nil,
        kind: "workflow",
        phase: "idle",
        stage: "idle",
        headline: "Amber",
        detail: "没有进行中的任务",
        summary: nil,
        metricText: nil,
        decision: nil,
        actions: [],
        updatedAt: .distantPast,
        isStale: false
    )

    var isActive: Bool {
        !runId.isEmpty && phase != "idle"
    }
}

struct WatchDecision: Codable, Hashable, Sendable {
    var id: String
    var type: WatchDecisionType
    var title: String
    var body: String
    var options: [WatchDecisionOption]
    var riskLevel: WatchRiskLevel
    var allowsVoice: Bool
}

enum WatchDecisionType: String, Codable, Hashable, Sendable {
    case approval
    case askUser
    case voiceReply
}

enum WatchRiskLevel: String, Codable, Hashable, Sendable {
    case low
    case medium
    case high
}

struct WatchDecisionOption: Codable, Hashable, Identifiable, Sendable {
    var id: String
    var title: String
    var style: WatchDecisionOptionStyle
}

enum WatchDecisionOptionStyle: String, Codable, Hashable, Sendable {
    case approve
    case deny
    case choice
    case openOnPhone
    case dictate
}

enum WatchAction: String, Codable, Hashable, Sendable {
    case openOnPhone
    case approve
    case deny
    case choose
    case dictate
    case cancel
}

struct WatchTaskActionRequest: Codable, Hashable, Sendable {
    var requestId: String
    var runId: String
    var conversationId: String?
    var decisionId: String?
    var action: WatchInboundAction
    var optionId: String?
    var text: String?
    var createdAt: Date
}

enum WatchInboundAction: String, Codable, Hashable, Sendable {
    case approve
    case deny
    case choose
    case answer
    case cancel
    case openOnPhone
    case refresh
}

struct WatchTaskActionResult: Codable, Hashable, Sendable {
    var requestId: String
    var runId: String
    var accepted: Bool
    var message: String?
    var snapshot: WatchTaskSnapshot?
}

enum WatchConnectivityPayloadKey {
    static let type = "type"
    static let snapshot = "snapshot"
    static let action = "action"
    static let result = "result"
    static let protocolVersion = "protocolVersion"
    static let currentProtocolVersion = 1

    static let typeSnapshot = "snapshot"
    static let typeAction = "action"
    static let typeActionResult = "actionResult"
    static let typeHello = "hello"
    static let typeRequestSnapshot = "requestSnapshot"
}

enum WatchTaskText {
    static func clipped(_ text: String?, maxLength: Int) -> String? {
        guard let text else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard trimmed.count > maxLength else { return trimmed }
        return String(trimmed.prefix(maxLength)).trimmingCharacters(in: .whitespacesAndNewlines) + "…"
    }

    static func singleLine(_ text: String?, maxLength: Int) -> String? {
        guard let clipped = clipped(text, maxLength: maxLength * 2) else { return nil }
        let collapsed = clipped
            .replacingOccurrences(of: "\r\n", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\t", with: " ")
        return Self.clipped(collapsed, maxLength: maxLength)
    }
}

enum WatchTaskCodec {
    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()

    static func encodeSnapshot(_ snapshot: WatchTaskSnapshot) throws -> Data {
        try encoder.encode(snapshot)
    }

    static func decodeSnapshot(_ data: Data) throws -> WatchTaskSnapshot {
        try decoder.decode(WatchTaskSnapshot.self, from: data)
    }

    static func encodeAction(_ action: WatchTaskActionRequest) throws -> Data {
        try encoder.encode(action)
    }

    static func decodeAction(_ data: Data) throws -> WatchTaskActionRequest {
        try decoder.decode(WatchTaskActionRequest.self, from: data)
    }

    static func encodeResult(_ result: WatchTaskActionResult) throws -> Data {
        try encoder.encode(result)
    }

    static func decodeResult(_ data: Data) throws -> WatchTaskActionResult {
        try decoder.decode(WatchTaskActionResult.self, from: data)
    }

    static func snapshotMessage(for snapshot: WatchTaskSnapshot) throws -> [String: Any] {
        [
            WatchConnectivityPayloadKey.type: WatchConnectivityPayloadKey.typeSnapshot,
            WatchConnectivityPayloadKey.protocolVersion: WatchConnectivityPayloadKey.currentProtocolVersion,
            WatchConnectivityPayloadKey.snapshot: try encodeSnapshot(snapshot)
        ]
    }

    static func actionMessage(for action: WatchTaskActionRequest) throws -> [String: Any] {
        [
            WatchConnectivityPayloadKey.type: WatchConnectivityPayloadKey.typeAction,
            WatchConnectivityPayloadKey.protocolVersion: WatchConnectivityPayloadKey.currentProtocolVersion,
            WatchConnectivityPayloadKey.action: try encodeAction(action)
        ]
    }

    static func resultMessage(for result: WatchTaskActionResult) throws -> [String: Any] {
        [
            WatchConnectivityPayloadKey.type: WatchConnectivityPayloadKey.typeActionResult,
            WatchConnectivityPayloadKey.protocolVersion: WatchConnectivityPayloadKey.currentProtocolVersion,
            WatchConnectivityPayloadKey.result: try encodeResult(result)
        ]
    }

    static func requestSnapshotMessage() -> [String: Any] {
        [
            WatchConnectivityPayloadKey.type: WatchConnectivityPayloadKey.typeRequestSnapshot,
            WatchConnectivityPayloadKey.protocolVersion: WatchConnectivityPayloadKey.currentProtocolVersion
        ]
    }
}

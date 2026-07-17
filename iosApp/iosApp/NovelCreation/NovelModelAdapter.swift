import CryptoKit
import Foundation

struct NovelResolvedModel: Codable, Equatable, Sendable {
    /// Effective provider UUID used by the transport and persisted in receipts.
    let providerID: String
    /// Provider that owns the selected model and is used to re-resolve it.
    let ownerProviderID: String
    /// Stable model UUID from the provider configuration, not the wire model name.
    let modelID: String
    let wireModelID: String
    let displayName: String
    let contextWindowTokens: Int?
}

enum NovelModelPurpose: String, Codable, CaseIterable, Sendable {
    case quickStart
    case discussion
    case prose
    case polish
    case stateExtraction
    case stateRebuild
    case driftCheck
}

struct NovelModelMessage: Codable, Equatable, Sendable {
    enum Role: String, Codable, Sendable {
        case system
        case user
        case assistant
    }

    let role: Role
    let content: String
}

enum NovelModelReasoningLevel: String, Codable, CaseIterable, Sendable {
    case off
    case automatic
    case low
    case medium
    case high
    case xhigh
    case max
}

struct NovelModelParameters: Codable, Equatable, Sendable {
    let temperature: Double?
    let topP: Double?
    let maxOutputTokens: Int?
    let reasoningLevel: NovelModelReasoningLevel
}

struct NovelModelRequest: Codable, Equatable, Sendable {
    let runID: NovelRunID
    let model: NovelResolvedModel
    let purpose: NovelModelPurpose
    let messages: [NovelModelMessage]
    let parameters: NovelModelParameters
}

extension NovelModelRequest {
    func canonicalSHA256() throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(self)
        return SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
    }
}

extension NovelModelParameters {
    var evidenceDictionary: [String: String] {
        var result = ["reasoningLevel": reasoningLevel.rawValue]
        if let temperature { result["temperature"] = String(temperature) }
        if let topP { result["topP"] = String(topP) }
        if let maxOutputTokens { result["maxOutputTokens"] = String(maxOutputTokens) }
        return result
    }
}

struct NovelModelUsage: Codable, Equatable, Sendable {
    let promptTokens: Int
    let completionTokens: Int
    let cachedTokens: Int
    let totalTokens: Int
}

struct NovelModelFailure: Error, Codable, Equatable, Sendable {
    let code: String
    let message: String
    let isRetryable: Bool
}

extension NovelModelFailure: LocalizedError {
    var errorDescription: String? { message }
}

enum NovelModelEvent: Equatable, Sendable {
    case textDelta(String)
    case textReplacement(String)
    case usage(NovelModelUsage)
    case askUser(NovelAskUserPrompt, preface: String)
    case completed
    case failed(NovelModelFailure)
}

protocol NovelModelRunning: Sendable {
    func resolveModel(for policy: NovelProjectModelPolicy) async throws -> NovelResolvedModel
    func start(_ request: NovelModelRequest) async throws -> AsyncStream<NovelModelEvent>
    func cancel(runID: NovelRunID) async
}

enum NovelModelAdapterError: Error, Equatable, Sendable {
    case duplicateRunID(NovelRunID)
}

extension NovelModelAdapterError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .duplicateRunID:
            "A model run reused an existing run identifier."
        }
    }
}

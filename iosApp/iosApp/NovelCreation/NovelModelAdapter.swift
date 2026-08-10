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
    case characterProposal
    case discussion
    case prose
    case polish
    case stateExtraction
    case stateRebuild
    case driftCheck
    case continuityAudit
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

/// The cursor that makes a stored OpenAI Responses stream resumable without
/// replaying the original prompt. `sequenceNumber` is the last event that was
/// durably handed to the Novel runtime.
struct NovelResponsesResumeCursor: Codable, Equatable, Sendable {
    let responseID: String
    let sequenceNumber: Int64
}

enum NovelModelFrameEvent: Equatable, Sendable {
    case activity
    /// Provider reasoning/thinking text for UI only. Never manuscript.
    case reasoningDelta(String)
    case textDelta(String)
    case textReplacement(String)
    case usage(NovelModelUsage)
}

/// An atomic response frame: the chunks received since the previous cursor
/// are published together with the checkpoint that follows them on the wire.
/// This prevents a crash between a visible chunk and its sequence checkpoint
/// from advancing the durable cursor past content that was never persisted.
struct NovelModelResponseFrame: Equatable, Sendable {
    let cursor: NovelResponsesResumeCursor
    let events: [NovelModelFrameEvent]
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
    case activity
    /// Provider reasoning/thinking text for UI only. Must never enter
    /// `partialContent`, candidates, collect, or structured JSON sources.
    case reasoningDelta(String)
    case textDelta(String)
    case textReplacement(String)
    case usage(NovelModelUsage)
    case responseFrame(NovelModelResponseFrame)
    case responseDisconnected(NovelModelFailure)
    case askUser(NovelAskUserPrompt, preface: String)
    case completed
    case failed(NovelModelFailure)
}

/// Minimal durable-resume input. The original prompt is intentionally absent:
/// OpenAI resumes a stored response with GET /responses/{id}, so reconstructing
/// or POSTing the prompt here would create a second server response.
struct NovelModelResumeRequest: Codable, Equatable, Sendable {
    let runID: NovelRunID
    let model: NovelResolvedModel
    let purpose: NovelModelPurpose
    let cursor: NovelResponsesResumeCursor
}

protocol NovelModelRunning: Sendable {
    func resolveModel(for policy: NovelProjectModelPolicy) async throws -> NovelResolvedModel
    func start(_ request: NovelModelRequest) async throws -> AsyncStream<NovelModelEvent>
    func cancel(runID: NovelRunID) async
}

protocol NovelDurableModelRunning: NovelModelRunning {
    func resume(_ request: NovelModelResumeRequest) async throws -> AsyncStream<NovelModelEvent>
    /// Detach the local consumer and stream. This must not call a provider
    /// cancel endpoint; a later foreground entry may resume from the cursor.
    func detach(runID: NovelRunID) async
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

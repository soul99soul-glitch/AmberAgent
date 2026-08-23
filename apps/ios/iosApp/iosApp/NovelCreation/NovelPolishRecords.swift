import Foundation

enum NovelPolishTransactionStatus: String, Codable, Equatable, Sendable {
    case pending
    case retryable
    case incompatible
    case completed
    case blocked
    case abandoned
}

struct NovelPendingPolishTransactionRecord: Codable, Equatable, Sendable {
    let id: NovelPendingOperationID
    let operationID: NovelOperationID
    let payloadSHA256: String
    let branchID: NovelBranchID
    let candidateID: NovelCandidateID
    let sourceChapterVersionID: NovelChapterVersionID
    let proposedChapterVersionID: NovelChapterVersionID
    let checkpointID: NovelCheckpointID
    let baseCheckpointID: NovelCheckpointID
    let baseHeadRevision: Int64
    let baseWorkingRevision: Int64
    let sessionCursor: NovelSessionCursor
    let sourceContentSHA256: String
    let candidateContentSHA256: String
    let createdAt: Date
    var status: NovelPolishTransactionStatus
    var attemptCount: Int
    var lastFailure: NovelFailure?
    /// Nil means the last failure happened before a provider attempt was reserved.
    var lastFailureAttemptIndex: Int? = nil
}

struct NovelPolishAttemptRecord: Codable, Equatable, Sendable {
    let transactionID: NovelPendingOperationID
    let attemptIndex: Int
    let runID: NovelRunID
    let injectionReceiptID: NovelReceiptID
    let generationReceiptID: NovelReceiptID
    let sourceContentSHA256: String
    let candidateContentSHA256: String
    let createdAt: Date
}

struct NovelPolishAssessmentRecord: Codable, Equatable, Sendable {
    let transactionID: NovelPendingOperationID
    let attemptIndex: Int
    let runID: NovelRunID
    let result: NovelPolishDriftV1?
    let failure: NovelFailure?
    let createdAt: Date
}

import Foundation

enum NovelFactRequestReceiptReducer {
    static func reserve(
        _ artifacts: NovelFactTransactionReceiptArtifacts,
        pendingID: NovelPendingOperationID,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        guard let pending = document.pendingOperations.first(where: {
            $0.id == pendingID
        }) else {
            throw NovelError.invalidInput("The fact request lost its pending operation.")
        }
        let injection = artifacts.injectionReceipt
        let generation = artifacts.generationReceipt
        guard let link = injection.factTransaction,
              generation.factTransaction == link,
              link.pendingID == pending.id,
              link.ownerOperationID == pending.operationID,
              injection.projectID == document.project.id,
              injection.branchID == pending.branchID,
              injection.runID == generation.runID,
              generation.injectionReceiptID == injection.id else {
            throw NovelError.invalidInput("The fact request receipts do not match their pending owner.")
        }
        let expectedKind: NovelFactReceiptKind = pending.kind == .collection
            ? .stateDelta
            : .manualRebuild
        guard link.kind == expectedKind,
              (expectedKind == .stateDelta ? link.chunkIndex == nil : link.chunkIndex != nil) else {
            throw NovelError.invalidInput("The fact request receipt kind is invalid.")
        }
        if link.attemptOperationID == pending.operationID {
            guard link.attemptPayloadSHA256 == pending.payloadSHA256 else {
                throw NovelError.idempotencyConflict(link.attemptOperationID)
            }
        } else {
            guard document.factAttempts.contains(where: {
                $0.pendingID == pending.id &&
                    $0.ownerOperationID == pending.operationID &&
                    $0.attemptOperationID == link.attemptOperationID &&
                    $0.attemptPayloadSHA256 == link.attemptPayloadSHA256 &&
                    $0.branchID == pending.branchID &&
                    $0.kind == expectedKind
            }) else {
                throw NovelError.invalidInput("The fact request retry was not durably reserved.")
            }
        }

        let allReceiptIDs = Set(document.injectionReceipts.map(\.id))
            .union(document.generationReceipts.map(\.id))
        guard injection.id != generation.id,
              !allReceiptIDs.contains(injection.id),
              !allReceiptIDs.contains(generation.id),
              !document.injectionReceipts.contains(where: { $0.runID == injection.runID }),
              !document.generationReceipts.contains(where: { $0.runID == generation.runID }) else {
            throw NovelError.immutableRecordConflict("fact-model request receipt identity")
        }

        var next = document
        next.injectionReceipts.append(injection)
        next.generationReceipts.append(generation)
        next.project.revision += 1
        next.project.updatedAt = now
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return next
    }
}

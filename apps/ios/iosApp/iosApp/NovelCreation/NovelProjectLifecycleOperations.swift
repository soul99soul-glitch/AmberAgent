import Foundation

enum NovelProjectLifecycleOperationState: String, Codable, Equatable, Sendable {
    case pending
    case completed
}

enum NovelProjectLifecycleOperationIntent: Codable, Equatable, Sendable {
    case importCreate
    case importReplace(expectedRevision: Int64)
    case restorePrevious(expectedRevision: Int64)
    case delete(expectedRevision: Int64)
}

struct NovelProjectLifecycleOperationRecord: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 1

    let schemaVersion: Int
    let projectID: NovelProjectID
    let operationID: NovelOperationID
    let kind: NovelOperationKind
    let payloadSHA256: String
    let intent: NovelProjectLifecycleOperationIntent
    let sourceProjectSHA256: String?
    let targetProjectSHA256: String?
    let outcome: NovelOutcome
    let state: NovelProjectLifecycleOperationState

    init(
        projectID: NovelProjectID,
        operationID: NovelOperationID,
        kind: NovelOperationKind,
        payloadSHA256: String,
        intent: NovelProjectLifecycleOperationIntent,
        sourceProjectSHA256: String?,
        targetProjectSHA256: String?,
        outcome: NovelOutcome,
        state: NovelProjectLifecycleOperationState = .pending
    ) {
        self.schemaVersion = Self.currentSchemaVersion
        self.projectID = projectID
        self.operationID = operationID
        self.kind = kind
        self.payloadSHA256 = payloadSHA256
        self.intent = intent
        self.sourceProjectSHA256 = sourceProjectSHA256
        self.targetProjectSHA256 = targetProjectSHA256
        self.outcome = outcome
        self.state = state
    }

    func completed() -> Self {
        Self(
            projectID: projectID,
            operationID: operationID,
            kind: kind,
            payloadSHA256: payloadSHA256,
            intent: intent,
            sourceProjectSHA256: sourceProjectSHA256,
            targetProjectSHA256: targetProjectSHA256,
            outcome: outcome,
            state: .completed
        )
    }

    func validate() throws {
        guard schemaVersion == Self.currentSchemaVersion else {
            throw NovelError.repositoryFailure("Unsupported novel lifecycle operation schema.")
        }
        guard payloadSHA256.count == 64,
              payloadSHA256.allSatisfy({ $0.isHexDigit }) else {
            throw NovelError.repositoryFailure("Novel lifecycle operation payload hash is invalid.")
        }
        guard outcome.projectID == projectID else {
            throw NovelError.repositoryFailure("Novel lifecycle operation outcome targets another project.")
        }
        switch (kind, intent, outcome, sourceProjectSHA256, targetProjectSHA256) {
        case (
            .importProject,
            .importCreate,
            .projectImported(_, projectID, _, _, _),
            .none,
            .some(let targetHash)
        ) where projectID == self.projectID && NovelDocumentValidator.isSHA256(targetHash):
            break
        case (
            .importProject,
            .importReplace,
            .projectImported(_, projectID, .replaced, _, _),
            .some(let sourceHash),
            .some(let targetHash)
        ) where projectID == self.projectID &&
            NovelDocumentValidator.isSHA256(sourceHash) &&
            NovelDocumentValidator.isSHA256(targetHash):
            break
        case (
            .deleteProject,
            .delete,
            .projectDeleted(let projectID),
            .some(let sourceHash),
            .none
        ) where projectID == self.projectID && NovelDocumentValidator.isSHA256(sourceHash):
            break
        case (
            .deleteProject,
            .delete,
            .projectDeleted(let projectID),
            .none,
            .none
        ) where projectID == self.projectID && state == .completed:
            break
        case (
            .restorePreviousProject,
            .restorePrevious(let expectedRevision),
            .previousProjectRestored(let projectID, let revision),
            .none,
            .some(let targetHash)
        ) where projectID == self.projectID &&
            revision == expectedRevision &&
            NovelDocumentValidator.isSHA256(targetHash):
            break
        default:
            throw NovelError.repositoryFailure("Novel lifecycle operation fields are inconsistent.")
        }
    }

    func validateReplay(
        kind: NovelOperationKind,
        payloadSHA256: String
    ) throws {
        try validate()
        guard self.kind == kind,
              self.payloadSHA256 == payloadSHA256 else {
            throw NovelError.idempotencyConflict(operationID)
        }
    }

    func validateTransition(to next: Self) throws {
        try validate()
        try next.validate()
        guard projectID == next.projectID,
              operationID == next.operationID,
              kind == next.kind,
              payloadSHA256 == next.payloadSHA256,
              intent == next.intent,
              sourceProjectSHA256 == next.sourceProjectSHA256,
              targetProjectSHA256 == next.targetProjectSHA256,
              outcome == next.outcome else {
            throw NovelError.idempotencyConflict(operationID)
        }
        guard state == next.state || (state == .pending && next.state == .completed) else {
            throw NovelError.repositoryFailure("Novel lifecycle operation state cannot move backwards.")
        }
    }
}

extension DefaultNovelCreation {
    func existingLifecycleOperation(
        projectID: NovelProjectID,
        operationID: NovelOperationID,
        kind: NovelOperationKind,
        payloadSHA256: String
    ) async throws -> NovelProjectLifecycleOperationRecord? {
        guard let record = try await repository.lifecycleOperation(
            projectID: projectID,
            operationID: operationID
        ) else {
            return nil
        }
        try record.validateReplay(kind: kind, payloadSHA256: payloadSHA256)
        return record
    }

    func persistPendingLifecycleOperation(
        _ record: NovelProjectLifecycleOperationRecord
    ) async throws -> NovelOutcome? {
        try record.validate()
        do {
            try await repository.writeLifecycleOperation(record)
        } catch let writeError {
            let installed: NovelProjectLifecycleOperationRecord?
            do {
                installed = try await repository.lifecycleOperation(
                    projectID: record.projectID,
                    operationID: record.operationID
                )
            } catch {
                registerPendingLifecycleOperation(record)
                frozenProjectIDs.insert(record.projectID)
                throw NovelError.storageIndeterminate(record.projectID)
            }
            guard let installed else {
                throw writeError
            }
            try installed.validateReplay(
                kind: record.kind,
                payloadSHA256: record.payloadSHA256
            )
            if installed.state == .completed {
                unregisterPendingLifecycleOperation(installed)
                return installed.outcome
            }
            registerPendingLifecycleOperation(installed)
            return nil
        }
        registerPendingLifecycleOperation(record)
        return nil
    }

    func persistCompletedLifecycleOperation(
        _ record: NovelProjectLifecycleOperationRecord
    ) async throws -> NovelOutcome {
        let completed = record.completed()
        do {
            try await repository.writeLifecycleOperation(completed)
        } catch {
            let installed: NovelProjectLifecycleOperationRecord?
            do {
                installed = try await repository.lifecycleOperation(
                    projectID: record.projectID,
                    operationID: record.operationID
                )
            } catch {
                registerPendingLifecycleOperation(record)
                frozenProjectIDs.insert(record.projectID)
                throw NovelError.storageIndeterminate(record.projectID)
            }
            if installed == completed {
                unregisterPendingLifecycleOperation(completed)
                return completed.outcome
            }
            if let installed, installed.state == .pending {
                registerPendingLifecycleOperation(installed)
            }
            frozenProjectIDs.insert(record.projectID)
            throw NovelError.storageIndeterminate(record.projectID)
        }
        unregisterPendingLifecycleOperation(completed)
        return completed.outcome
    }

    func abandonPendingLifecycleOperation(
        _ record: NovelProjectLifecycleOperationRecord
    ) async throws {
        do {
            try await repository.removeLifecycleOperation(record)
        } catch {
            if try await repository.lifecycleOperation(
                projectID: record.projectID,
                operationID: record.operationID
            ) == nil {
                unregisterPendingLifecycleOperation(record)
                return
            }
            frozenProjectIDs.insert(record.projectID)
            throw NovelError.storageIndeterminate(record.projectID)
        }
        unregisterPendingLifecycleOperation(record)
    }

    func reconcilePendingLifecycleOperations() async throws {
        blockedLifecycleProjectIDs.formUnion(
            try await repository.blockedLifecycleProjectIDs()
        )
        let records = try await repository.listPendingLifecycleOperations()
        for record in records {
            registerPendingLifecycleOperation(record)
        }
        for record in records {
            do {
                try record.validate()
                switch record.intent {
                case .importCreate:
                    guard let targetHash = record.targetProjectSHA256 else { continue }
                    do {
                        let loaded = try await repository.loadProject(id: record.projectID)
                        if try projectDocumentSHA256(loaded.document) == targetHash {
                            _ = try await persistCompletedLifecycleOperation(record)
                        } else {
                            frozenProjectIDs.insert(record.projectID)
                        }
                    } catch NovelError.projectNotFound {
                        try await abandonPendingLifecycleOperation(record)
                    }

                case .importReplace(let expectedRevision):
                    guard let sourceHash = record.sourceProjectSHA256,
                          let targetHash = record.targetProjectSHA256 else { continue }
                    do {
                        let loaded = try await repository.loadProject(id: record.projectID)
                        let currentHash = try projectDocumentSHA256(loaded.document)
                        if currentHash == targetHash {
                            _ = try await persistCompletedLifecycleOperation(record)
                        } else if currentHash == sourceHash,
                                  loaded.document.project.revision == expectedRevision {
                            try await abandonPendingLifecycleOperation(record)
                        } else {
                            frozenProjectIDs.insert(record.projectID)
                        }
                    } catch NovelError.projectNotFound {
                        frozenProjectIDs.insert(record.projectID)
                    }

                case .restorePrevious(let expectedRevision):
                    guard let targetHash = record.targetProjectSHA256 else { continue }
                    do {
                        let loaded = try await repository.loadProject(id: record.projectID)
                        let currentHash = try projectDocumentSHA256(loaded.document)
                        if loaded.access == .readWrite, currentHash == targetHash {
                            _ = try installLoadedProject(
                                loaded,
                                id: record.projectID,
                                allowsRollback: true
                            )
                            _ = try await persistCompletedLifecycleOperation(record)
                        } else if loaded.access != .readWrite,
                                  currentHash == targetHash,
                                  loaded.document.project.revision == expectedRevision {
                            try await abandonPendingLifecycleOperation(record)
                        } else {
                            frozenProjectIDs.insert(record.projectID)
                        }
                    } catch NovelError.projectNotFound {
                        frozenProjectIDs.insert(record.projectID)
                    }

                case .delete(let expectedRevision):
                    do {
                        let loaded = try await repository.loadProject(id: record.projectID)
                        guard let sourceHash = record.sourceProjectSHA256 else {
                            frozenProjectIDs.insert(record.projectID)
                            continue
                        }
                        if try projectDocumentSHA256(loaded.document) == sourceHash,
                           loaded.document.project.revision == expectedRevision {
                            try await abandonPendingLifecycleOperation(record)
                        } else {
                            frozenProjectIDs.insert(record.projectID)
                        }
                    } catch NovelError.projectNotFound {
                        _ = try await persistCompletedLifecycleOperation(record)
                    }
                }
            } catch {
                frozenProjectIDs.insert(record.projectID)
            }
        }
    }

    func projectDocumentSHA256(_ document: NovelProjectDocumentV1) throws -> String {
        try NovelProjectPackageCodec.encode(document).projectSHA256
    }

    func guardDocumentDoesNotUseLifecycleOperationID(
        _ document: NovelProjectDocumentV1,
        operationID: NovelOperationID
    ) throws {
        let isUsed = document.appliedOperations.contains { $0.operationID == operationID } ||
            document.pendingOperations.contains { $0.operationID == operationID } ||
            document.polishTransactions.contains { $0.operationID == operationID } ||
            document.factAttempts.contains {
                $0.ownerOperationID == operationID || $0.attemptOperationID == operationID
            } ||
            document.activeRuns.contains { $0.operationID == operationID }
        if isUsed {
            throw NovelError.idempotencyConflict(operationID)
        }
    }

    func guardDocumentOperationIDsDoNotCollideWithLifecycleLedger(
        _ document: NovelProjectDocumentV1,
        projectID: NovelProjectID
    ) async throws {
        let lifecycleIDs = try await repository.lifecycleOperationIDs(projectID: projectID)
        guard let collision = documentOperationIDs(document)
            .intersection(lifecycleIDs)
            .sorted(by: { $0.description < $1.description })
            .first else {
            return
        }
        throw NovelError.idempotencyConflict(collision)
    }

    func documentOperationIDs(_ document: NovelProjectDocumentV1) -> Set<NovelOperationID> {
        var ids = Set(document.appliedOperations.map(\.operationID))
        ids.formUnion(document.pendingOperations.map(\.operationID))
        ids.formUnion(document.polishTransactions.map(\.operationID))
        ids.formUnion(document.activeRuns.map(\.operationID))
        for attempt in document.factAttempts {
            ids.insert(attempt.ownerOperationID)
            ids.insert(attempt.attemptOperationID)
        }
        return ids
    }

    func registerPendingLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) {
        guard record.state == .pending else { return }
        pendingLifecycleOperationsByProject[record.projectID, default: []]
            .insert(record.operationID)
    }

    func unregisterPendingLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) {
        pendingLifecycleOperationsByProject[record.projectID]?.remove(record.operationID)
        if pendingLifecycleOperationsByProject[record.projectID]?.isEmpty == true {
            pendingLifecycleOperationsByProject[record.projectID] = nil
        }
    }
}

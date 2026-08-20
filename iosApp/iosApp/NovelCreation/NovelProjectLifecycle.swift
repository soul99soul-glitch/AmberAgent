import Foundation

struct NovelPreparedProjectImport: Sendable {
    let sourceProjectID: NovelProjectID
    let destinationProjectID: NovelProjectID
    let document: NovelProjectDocumentV1
    let disposition: NovelProjectImportDisposition
    let interruptedRunCount: Int
}

enum NovelProjectLifecycle {
    static func prepareImport(
        _ command: NovelImportProjectCommand
    ) throws -> NovelPreparedProjectImport {
        let decoded = try NovelProjectPackageCodec.decode(command.packageData)
        let sourceProjectID = decoded.document.project.id
        let destinationProjectID: NovelProjectID
        let disposition: NovelProjectImportDisposition
        let remapped: NovelProjectDocumentV1

        switch command.policy {
        case .reject:
            guard command.projectID == sourceProjectID else {
                throw NovelError.invalidPackage("Reject import must preserve the package project ID.")
            }
            destinationProjectID = sourceProjectID
            disposition = .created
            remapped = decoded.document

        case .replace(let expectedRevision):
            guard command.projectID == sourceProjectID else {
                throw NovelError.invalidPackage("Replace import must target the package project ID.")
            }
            guard command.context.expectedProjectRevision == expectedRevision else {
                throw NovelError.invalidInput("Replace import revision evidence is inconsistent.")
            }
            destinationProjectID = sourceProjectID
            disposition = .replaced
            remapped = decoded.document

        case .keepBoth(let destinationID):
            guard command.projectID == destinationID,
                  destinationID != sourceProjectID else {
                throw NovelError.invalidInput("Keep-both import requires a distinct stable project ID.")
            }
            destinationProjectID = destinationID
            disposition = .keptBoth
            remapped = try NovelProjectIdentityRemapper.remap(
                decoded.document,
                to: destinationID
            )
        }

        let normalized = try NovelImportedProjectNormalizer.normalizeRunningRuns(in: remapped)
        try NovelDocumentValidator.validate(normalized.document)
        return NovelPreparedProjectImport(
            sourceProjectID: sourceProjectID,
            destinationProjectID: destinationProjectID,
            document: normalized.document,
            disposition: disposition,
            interruptedRunCount: normalized.interruptedRunCount
        )
    }
}

extension DefaultNovelCreation {
    func exportProjectPackage(
        projectID: NovelProjectID
    ) async throws -> NovelProjectPackageArtifact {
        try beginLifecycleRead(projectID: projectID)
        defer { endLifecycleRead(projectID: projectID) }
        let loaded = try await loadCommittedProject(id: projectID)
        try guardNoRunningWork(projectID: projectID, document: loaded.document)
        return try NovelProjectPackageCodec.encode(loaded.document)
    }

    func exportBranchMarkdown(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelMarkdownExportArtifact {
        let loaded = try await loadCommittedProject(id: projectID)
        return try NovelMarkdownExporter.export(loaded.document, branchID: branchID)
    }

    func applyWorkspacePlot(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        path: String,
        body: String
    ) async throws {
        let loaded = try await loadCommittedProject(id: projectID)
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: projectID)
        }
        let next = try NovelWorkspacePlotCommit.apply(
            to: loaded.document,
            branchID: branchID,
            path: path,
            body: body,
            now: now()
        )
        let committed = try await repository.commitProject(
            next,
            expectedRevision: loaded.document.project.revision
        )
        _ = try installLoadedProject(committed, id: projectID, allowsRollback: false)
    }

    func applyWorkspaceFastForwardPlot(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        chapterID: NovelChapterID,
        chapterTitle: String,
        chapterContent: String
    ) async throws {
        let loaded = try await loadCommittedProject(id: projectID)
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: projectID)
        }
        let next = try await applyChapterPlotPointer(
            to: loaded.document,
            branchID: branchID,
            chapterID: chapterID,
            chapterTitle: chapterTitle,
            chapterContent: chapterContent
        )
        let committed = try await repository.commitProject(
            next,
            expectedRevision: loaded.document.project.revision
        )
        _ = try installLoadedProject(committed, id: projectID, allowsRollback: false)
    }

    /// Contract v1.1 D-B: a manual chapter edit and the plot module it
    /// triggers persist in ONE atomic commit (single `.manualSync`
    /// checkpoint) — no window where the text is on disk without its plot.
    /// Runs as the dedicated `.saveManualEdit` executor so the standard
    /// perform pipeline (guards, reload, error reporting) still applies.
    func executeSaveManualEditWithPlot(
        _ command: NovelSaveManualEditCommand
    ) async throws -> NovelOutcome {
        let loaded = try await loadCommittedProject(id: command.projectID)
        let payloadSHA256 = try NovelAction.saveManualEdit(command).canonicalPayloadSHA256()
        if let replay = try NovelReducer.replayOutcome(
            context: command.context,
            kind: .saveManualEdit,
            payloadSHA256: payloadSHA256,
            in: loaded.document
        ) {
            return replay
        }
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: command.projectID)
        }
        guard let branch = loaded.document.branches
            .first(where: { $0.id == command.branchID }) else {
            throw NovelError.branchNotFound(command.branchID)
        }
        // Contract v1.1 D-B: run the plot draft BEFORE the mutation so the
        // edit and its plot module commit in one atomic revision step. The
        // draft gets a tight silence budget: a user-typed edit must not wait
        // the full fact timeout before the excerpt fallback commits it.
        var moduleText: String?
        var summaryOverride: String?
        if NovelWorkspaceLedger.isFastForward(branch: branch, chapterID: command.chapterID),
           let draft = try await writeWorkspacePlotDraft(
            document: loaded.document,
            previousSummary: loaded.document.stateSnapshots.first {
                $0.id == branch.currentStateSnapshotID
            }?.summary ?? "",
            chapterTitle: command.title,
            chapterContent: command.content,
            noOutputTimeout: min(factRequestTimeout, 60)
           ) {
            moduleText = draft.chapterText
            if !draft.summary.isEmpty {
                summaryOverride = draft.summary
            }
        }
        let reduced = try NovelFactTransactionReducer.saveManualEditWithPlot(
            command,
            payloadSHA256: payloadSHA256,
            moduleText: moduleText,
            summaryOverride: summaryOverride,
            in: loaded.document,
            now: now()
        )
        guard reduced.document != loaded.document else {
            return reduced.outcome
        }
        let committed = try await repository.commitProject(
            reduced.document,
            expectedRevision: loaded.document.project.revision
        )
        guard committed.document == reduced.document else {
            throw NovelError.storageIndeterminate(command.projectID)
        }
        _ = try installLoadedProject(committed, id: command.projectID, allowsRollback: false)
        return reduced.outcome
    }

    func applyChapterPlotPointer(
        to document: NovelProjectDocumentV1,
        branchID: NovelBranchID,
        chapterID: NovelChapterID,
        chapterTitle: String,
        chapterContent: String
    ) async throws -> NovelProjectDocumentV1 {
        guard let branch = document.branches.first(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        var moduleText = NovelWorkspaceLedger.excerpt(
            title: chapterTitle,
            content: chapterContent
        )
        var summaryOverride: String?
        if NovelWorkspaceLedger.isFastForward(branch: branch, chapterID: chapterID),
           let draft = try await writeWorkspacePlotDraft(
            document: document,
            previousSummary: document.stateSnapshots.first {
                $0.id == branch.currentStateSnapshotID
            }?.summary ?? "",
            chapterTitle: chapterTitle,
            chapterContent: chapterContent
           ) {
            moduleText = draft.chapterText
            if !draft.summary.isEmpty {
                summaryOverride = draft.summary
            }
        }
        return try NovelWorkspacePlotCommit.applyChapterModule(
            to: document,
            branchID: branchID,
            chapterID: chapterID,
            chapterTitle: chapterTitle,
            chapterContent: chapterContent,
            moduleText: moduleText,
            summaryOverride: summaryOverride,
            now: now()
        )
    }

    func writeWorkspacePlotDraft(
        document: NovelProjectDocumentV1,
        previousSummary: String,
        chapterTitle: String,
        chapterContent: String,
        noOutputTimeout: TimeInterval? = nil
    ) async throws -> NovelWorkspacePlotDraft? {
        do {
            let executor = NovelStructuredModelExecutor(modelRunner: modelRunner)
            let preparation = try await executor.prepare(
                modelPolicy: modelPolicy(for: .review, in: document),
                taskKind: .workspacePlot,
                requestedInputBudgetTokens: 16_000
            )
            let request = NovelStructuredModelExecutionRequest(
                runID: NovelRunID(),
                modelPolicy: preparation.modelPolicy,
                task: .workspacePlot(
                    previousSummary: previousSummary,
                    chapterTitle: chapterTitle,
                    chapterContent: chapterContent
                )
            )
            let evidence = try await executor.executePrepared(
                try executor.prepareInvocation(request, preparation: preparation),
                noOutputTimeout: noOutputTimeout ?? factRequestTimeout
            )
            guard case .workspacePlot(let draft) = evidence.output else {
                return nil
            }
            return draft
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return nil
        }
    }

    func applyWorkspacePlotRelink(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws {
        let loaded = try await loadCommittedProject(id: projectID)
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: projectID)
        }
        let next = try NovelWorkspacePlotCommit.applyRelink(
            to: loaded.document,
            branchID: branchID,
            now: now()
        )
        let committed = try await repository.commitProject(
            next,
            expectedRevision: loaded.document.project.revision
        )
        _ = try installLoadedProject(committed, id: projectID, allowsRollback: false)
    }

    func applyWorkspacePlotAcceptStale(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws {
        let loaded = try await loadCommittedProject(id: projectID)
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: projectID)
        }
        let next = try NovelWorkspacePlotCommit.applyAcceptStale(
            to: loaded.document,
            branchID: branchID,
            now: now()
        )
        let committed = try await repository.commitProject(
            next,
            expectedRevision: loaded.document.project.revision
        )
        _ = try installLoadedProject(committed, id: projectID, allowsRollback: false)
    }

    func materializeWorktreeDrafts(projectID: NovelProjectID) async throws {
        guard let checkout = worktreeCheckoutDirectory(projectID: projectID) else { return }
        let loaded = try await loadCommittedProject(id: projectID)
        try NovelWorkspaceAuthority.publishDrafts(
            loaded.document,
            to: checkout
        )
    }

    func worktreeDraftBody(
        projectID: NovelProjectID,
        candidateID: NovelCandidateID
    ) async -> String? {
        guard let checkout = worktreeCheckoutDirectory(projectID: projectID) else { return nil }
        return NovelWorkspaceAuthority.draftBody(
            candidateID: candidateID,
            in: checkout
        )
    }

    func worktreeManifestExists(projectID: NovelProjectID) async -> Bool {
        worktreeCheckoutDirectory(projectID: projectID) != nil
    }

    private func worktreeCheckoutDirectory(projectID: NovelProjectID) -> URL? {
        guard let root = try? NovelFileProjectRepository.defaultRootDirectory() else {
            return nil
        }
        let checkout = NovelWorkspaceAuthority.checkoutDirectory(
            in: NovelProjectShardedStorage.packageDirectory(
                projectDirectory: root.appendingPathComponent("projects", isDirectory: true),
                projectID: projectID
            )
        )
        let manifest = checkout.appendingPathComponent("manifest.yaml")
        guard FileManager.default.fileExists(atPath: manifest.path) else { return nil }
        return checkout
    }

    func exportWorkspace(
        projectID: NovelProjectID
    ) async throws -> NovelWorkspaceExportArtifact {
        let loaded = try await loadCommittedProject(id: projectID)
        let files = try NovelWorkspaceBackup.export(loaded.document)
        let stem = NovelPresentation.fileName(loaded.document.project.name, fallback: "Novel")
        return NovelWorkspaceExportArtifact(
            projectID: projectID,
            fileName: stem,
            files: files
        )
    }

    func executeImportProject(
        _ command: NovelImportProjectCommand,
        prepared: NovelPreparedProjectImport
    ) async throws -> NovelOutcome {
        guard command.projectID == prepared.destinationProjectID else {
            throw NovelError.invalidPackage("The prepared import destination changed.")
        }
        try guardNoActiveGenerationRuntime(projectID: command.projectID)
        let payloadSHA256 = try NovelAction.importProject(command).canonicalPayloadSHA256()
        let targetSHA256 = try projectDocumentSHA256(prepared.document)
        let outcome = NovelOutcome.projectImported(
            sourceProjectID: prepared.sourceProjectID,
            projectID: prepared.destinationProjectID,
            disposition: prepared.disposition,
            interruptedRunCount: prepared.interruptedRunCount,
            revision: prepared.document.project.revision
        )
        let existing = try await existingLifecycleOperation(
            projectID: command.projectID,
            operationID: command.context.operationID,
            kind: .importProject,
            payloadSHA256: payloadSHA256
        )
        if let existing, existing.state == .completed {
            unregisterPendingLifecycleOperation(existing)
            return existing.outcome
        }
        if let existing, existing.targetProjectSHA256 != targetSHA256 {
            throw NovelError.idempotencyConflict(command.context.operationID)
        }
        try guardDocumentDoesNotUseLifecycleOperationID(
            prepared.document,
            operationID: command.context.operationID
        )
        try await guardDocumentOperationIDsDoNotCollideWithLifecycleLedger(
            prepared.document,
            projectID: command.projectID
        )

        let intent: NovelProjectLifecycleOperationIntent
        if let existing {
            intent = existing.intent
        } else {
            switch command.policy {
            case .reject, .keepBoth:
                intent = .importCreate
            case .replace(let expectedRevision):
                intent = .importReplace(expectedRevision: expectedRevision)
            }
        }

        let record: NovelProjectLifecycleOperationRecord
        let installed: NovelLoadedProject
        switch intent {
        case .importCreate:
            do {
                let current = try await loadCommittedProject(id: command.projectID)
                if let existing, current.document == prepared.document {
                    _ = try installLoadedProject(
                        current,
                        id: command.projectID,
                        allowsRollback: true
                    )
                    return try await persistCompletedLifecycleOperation(existing)
                }
                if existing != nil {
                    frozenProjectIDs.insert(command.projectID)
                    throw NovelError.storageIndeterminate(command.projectID)
                }
                throw NovelError.projectAlreadyExists(command.projectID)
            } catch NovelError.projectNotFound {
                // Absence is the create-only install precondition.
            }
            if let existing {
                record = existing
            } else {
                record = NovelProjectLifecycleOperationRecord(
                    projectID: command.projectID,
                    operationID: command.context.operationID,
                    kind: .importProject,
                    payloadSHA256: payloadSHA256,
                    intent: .importCreate,
                    sourceProjectSHA256: nil,
                    targetProjectSHA256: targetSHA256,
                    outcome: outcome
                )
                if let replay = try await persistPendingLifecycleOperation(record) {
                    return replay
                }
            }
            do {
                installed = try await installImportedProject(
                    prepared.document,
                    allowsRollback: false
                ) {
                    try await self.repository.createProject(prepared.document)
                }
            } catch {
                if !requiresRepositoryReconciliation(error) {
                    try await abandonPendingLifecycleOperation(record)
                }
                throw error
            }

        case .importReplace(let expectedRevision):
            let current = try await loadCommittedProject(id: command.projectID)
            try guardNoRunningWork(projectID: command.projectID, document: current.document)
            try guardDocumentDoesNotUseLifecycleOperationID(
                current.document,
                operationID: command.context.operationID
            )
            if let existing, current.document == prepared.document {
                _ = try installLoadedProject(
                    current,
                    id: command.projectID,
                    allowsRollback: true
                )
                return try await persistCompletedLifecycleOperation(existing)
            }
            if let existing {
                guard let sourceHash = existing.sourceProjectSHA256,
                      try projectDocumentSHA256(current.document) == sourceHash else {
                    frozenProjectIDs.insert(command.projectID)
                    throw NovelError.storageIndeterminate(command.projectID)
                }
            }
            guard current.document.project.revision == expectedRevision else {
                throw NovelError.staleProjectRevision(
                    expected: expectedRevision,
                    actual: current.document.project.revision
                )
            }
            if let existing {
                record = existing
            } else {
                record = NovelProjectLifecycleOperationRecord(
                    projectID: command.projectID,
                    operationID: command.context.operationID,
                    kind: .importProject,
                    payloadSHA256: payloadSHA256,
                    intent: .importReplace(expectedRevision: expectedRevision),
                    sourceProjectSHA256: try projectDocumentSHA256(current.document),
                    targetProjectSHA256: targetSHA256,
                    outcome: outcome
                )
                if let replay = try await persistPendingLifecycleOperation(record) {
                    return replay
                }
            }
            if current.document == prepared.document {
                installed = current
            } else {
                do {
                    installed = try await installImportedProject(
                        prepared.document,
                        allowsRollback: true
                    ) {
                        try await self.repository.replaceProject(
                            prepared.document,
                            expectedRevision: expectedRevision
                        )
                    }
                } catch {
                    if !requiresRepositoryReconciliation(error) {
                        try await abandonPendingLifecycleOperation(record)
                    }
                    throw error
                }
            }

        case .restorePrevious:
            throw NovelError.repositoryFailure("Import lifecycle record has a restore intent.")
        case .delete:
            throw NovelError.repositoryFailure("Import lifecycle record has a delete intent.")
        }
        guard installed.document == prepared.document else {
            throw NovelError.storageIndeterminate(command.projectID)
        }
        return try await persistCompletedLifecycleOperation(record)
    }

    func executeRestorePreviousProject(
        _ command: NovelRestorePreviousProjectCommand
    ) async throws -> NovelOutcome {
        try guardNoActiveGenerationRuntime(projectID: command.projectID)
        guard let requestedRevision = command.context.expectedProjectRevision else {
            throw NovelError.invalidInput("Restoring the previous project requires an expected revision.")
        }
        let payloadSHA256 = try NovelAction.restorePreviousProject(command).canonicalPayloadSHA256()
        let existing = try await existingLifecycleOperation(
            projectID: command.projectID,
            operationID: command.context.operationID,
            kind: .restorePreviousProject,
            payloadSHA256: payloadSHA256
        )
        if let existing, existing.state == .completed {
            unregisterPendingLifecycleOperation(existing)
            return existing.outcome
        }

        let expectedRevision: Int64
        if let existing {
            guard case .restorePrevious(let storedRevision) = existing.intent else {
                throw NovelError.repositoryFailure("Restore lifecycle record has another intent.")
            }
            guard requestedRevision == storedRevision else {
                throw NovelError.idempotencyConflict(command.context.operationID)
            }
            expectedRevision = storedRevision
        } else {
            expectedRevision = requestedRevision
        }

        let loaded = try await loadCommittedProject(id: command.projectID)
        try guardDocumentDoesNotUseLifecycleOperationID(
            loaded.document,
            operationID: command.context.operationID
        )
        guard loaded.document.project.revision == expectedRevision else {
            throw NovelError.staleProjectRevision(
                expected: expectedRevision,
                actual: loaded.document.project.revision
            )
        }
        let documentHash = try projectDocumentSHA256(loaded.document)
        if loaded.access == .readWrite {
            guard let existing,
                  existing.targetProjectSHA256 == documentHash else {
                throw NovelError.invalidInput("Only a read-only previous project can be restored.")
            }
            _ = try installLoadedProject(loaded, id: command.projectID, allowsRollback: true)
            return try await persistCompletedLifecycleOperation(existing)
        }

        let outcome = NovelOutcome.previousProjectRestored(
            projectID: command.projectID,
            revision: expectedRevision
        )
        let record: NovelProjectLifecycleOperationRecord
        if let existing {
            guard existing.sourceProjectSHA256 == nil,
                  existing.targetProjectSHA256 == documentHash else {
                frozenProjectIDs.insert(command.projectID)
                throw NovelError.storageIndeterminate(command.projectID)
            }
            record = existing
        } else {
            record = NovelProjectLifecycleOperationRecord(
                projectID: command.projectID,
                operationID: command.context.operationID,
                kind: .restorePreviousProject,
                payloadSHA256: payloadSHA256,
                intent: .restorePrevious(expectedRevision: expectedRevision),
                sourceProjectSHA256: nil,
                targetProjectSHA256: documentHash,
                outcome: outcome
            )
            if let replay = try await persistPendingLifecycleOperation(record) {
                return replay
            }
        }

        do {
            let restored = try await repository.restorePreviousProject(
                id: command.projectID,
                expectedDocumentSHA256: documentHash
            )
            guard restored.access == .readWrite,
                  restored.document == loaded.document,
                  try projectDocumentSHA256(restored.document) == documentHash else {
                frozenProjectIDs.insert(command.projectID)
                throw NovelError.storageIndeterminate(command.projectID)
            }
            _ = try installLoadedProject(restored, id: command.projectID, allowsRollback: true)
        } catch {
            let originalError = error
            let current: NovelLoadedProject
            do {
                current = try await repository.loadProject(id: command.projectID)
            } catch {
                frozenProjectIDs.insert(command.projectID)
                throw NovelError.storageIndeterminate(command.projectID)
            }
            let currentHash = try projectDocumentSHA256(current.document)
            if current.access == .readWrite,
               currentHash == documentHash,
               current.document == loaded.document {
                _ = try installLoadedProject(current, id: command.projectID, allowsRollback: true)
            } else if current.access != .readWrite,
                      currentHash == documentHash,
                      current.document.project.revision == expectedRevision {
                try await abandonPendingLifecycleOperation(record)
                throw originalError
            } else {
                frozenProjectIDs.insert(command.projectID)
                throw NovelError.storageIndeterminate(command.projectID)
            }
        }
        return try await persistCompletedLifecycleOperation(record)
    }

    func executeDeleteProject(
        _ command: NovelDeleteProjectCommand
    ) async throws -> NovelOutcome {
        try guardNoActiveGenerationRuntime(projectID: command.projectID)
        let payloadSHA256 = try NovelAction.deleteProject(command).canonicalPayloadSHA256()
        let existing = try await existingLifecycleOperation(
            projectID: command.projectID,
            operationID: command.context.operationID,
            kind: .deleteProject,
            payloadSHA256: payloadSHA256
        )
        if let existing, existing.state == .completed {
            unregisterPendingLifecycleOperation(existing)
            return existing.outcome
        }
        let expectedRevision: Int64
        var record = existing
        if let existing {
            guard case .delete(let storedRevision) = existing.intent else {
                throw NovelError.repositoryFailure("Delete lifecycle record has an import intent.")
            }
            expectedRevision = storedRevision
        } else {
            guard let requestedRevision = command.context.expectedProjectRevision else {
                throw NovelError.invalidInput("Project deletion requires an expected revision.")
            }
            expectedRevision = requestedRevision
        }
        let loaded: NovelLoadedProject
        do {
            loaded = try await loadCommittedProject(id: command.projectID)
        } catch NovelError.projectNotFound {
            let absentRecord = record ?? NovelProjectLifecycleOperationRecord(
                projectID: command.projectID,
                operationID: command.context.operationID,
                kind: .deleteProject,
                payloadSHA256: payloadSHA256,
                intent: .delete(expectedRevision: expectedRevision),
                sourceProjectSHA256: nil,
                targetProjectSHA256: nil,
                outcome: .projectDeleted(projectID: command.projectID)
            )
            evictProjectFromRuntime(command.projectID)
            return try await persistCompletedLifecycleOperation(absentRecord)
        } catch {
            return try await executeUnavailableProjectDeletion(
                command,
                payloadSHA256: payloadSHA256,
                expectedRevision: expectedRevision,
                existingRecord: record,
                loadError: error
            )
        }
        try guardNoRunningWork(projectID: command.projectID, document: loaded.document)
        try guardDocumentDoesNotUseLifecycleOperationID(
            loaded.document,
            operationID: command.context.operationID
        )
        if let existing {
            guard let sourceHash = existing.sourceProjectSHA256,
                  try projectDocumentSHA256(loaded.document) == sourceHash else {
                frozenProjectIDs.insert(command.projectID)
                throw NovelError.storageIndeterminate(command.projectID)
            }
        }
        guard loaded.document.project.revision == expectedRevision else {
            throw NovelError.staleProjectRevision(
                expected: expectedRevision,
                actual: loaded.document.project.revision
            )
        }
        if existing == nil {
            let pendingRecord = NovelProjectLifecycleOperationRecord(
                projectID: command.projectID,
                operationID: command.context.operationID,
                kind: .deleteProject,
                payloadSHA256: payloadSHA256,
                intent: .delete(expectedRevision: expectedRevision),
                sourceProjectSHA256: try projectDocumentSHA256(loaded.document),
                targetProjectSHA256: nil,
                outcome: .projectDeleted(projectID: command.projectID)
            )
            if let replay = try await persistPendingLifecycleOperation(pendingRecord) {
                return replay
            }
            record = pendingRecord
        }
        guard let record else {
            throw NovelError.storageIndeterminate(command.projectID)
        }

        do {
            try await repository.deleteProject(
                id: command.projectID,
                expectedRevision: expectedRevision
            )
        } catch {
            if requiresRepositoryReconciliation(error) {
                let deletionError = error
                let currentAfterFailure: NovelLoadedProject?
                do {
                    currentAfterFailure = try await repository.loadProject(id: command.projectID)
                } catch NovelError.projectNotFound {
                    currentAfterFailure = nil
                } catch {
                    frozenProjectIDs.insert(command.projectID)
                    throw deletionError
                }
                if let current = currentAfterFailure {
                    if let sourceHash = record.sourceProjectSHA256,
                       current.document.project.revision == expectedRevision,
                       try projectDocumentSHA256(current.document) == sourceHash {
                        try await abandonPendingLifecycleOperation(record)
                    } else {
                        frozenProjectIDs.insert(command.projectID)
                    }
                    throw deletionError
                }
                // A durable tombstone makes deletion authoritative before cleanup ends.
            } else {
                try await abandonPendingLifecycleOperation(record)
                throw error
            }
        }
        evictProjectFromRuntime(command.projectID)
        return try await persistCompletedLifecycleOperation(record)
    }

    private func executeUnavailableProjectDeletion(
        _ command: NovelDeleteProjectCommand,
        payloadSHA256: String,
        expectedRevision: Int64,
        existingRecord: NovelProjectLifecycleOperationRecord?,
        loadError: Error
    ) async throws -> NovelOutcome {
        let summaries = try await repository.listProjects()
        guard summaries.contains(where: {
            $0.id == command.projectID && $0.loadError != nil
        }) else {
            throw loadError
        }

        let record = existingRecord ?? NovelProjectLifecycleOperationRecord(
            projectID: command.projectID,
            operationID: command.context.operationID,
            kind: .deleteProject,
            payloadSHA256: payloadSHA256,
            intent: .delete(expectedRevision: expectedRevision),
            sourceProjectSHA256: nil,
            targetProjectSHA256: nil,
            outcome: .projectDeleted(projectID: command.projectID)
        )
        do {
            try await repository.discardUnavailableProject(id: command.projectID)
        } catch {
            let deletionError = error
            if requiresRepositoryReconciliation(error),
               let remaining = try? await repository.listProjects(),
               !remaining.contains(where: { $0.id == command.projectID }) {
                // The durable tombstone is already authoritative.
            } else {
                if existingRecord != nil {
                    try? await abandonPendingLifecycleOperation(record)
                }
                throw deletionError
            }
        }
        evictProjectFromRuntime(command.projectID)
        return try await persistCompletedLifecycleOperation(record)
    }
}

private extension DefaultNovelCreation {
    func installImportedProject(
        _ document: NovelProjectDocumentV1,
        allowsRollback: Bool,
        operation: () async throws -> NovelLoadedProject
    ) async throws -> NovelLoadedProject {
        do {
            let loaded = try await operation()
            guard loaded.document == document else {
                throw NovelError.storageIndeterminate(document.project.id)
            }
            return try installLoadedProject(
                loaded,
                id: document.project.id,
                allowsRollback: allowsRollback
            )
        } catch {
            guard requiresRepositoryReconciliation(error) else { throw error }
            frozenProjectIDs.insert(document.project.id)
            if let reconciled = try? await repository.loadProject(id: document.project.id),
               reconciled.document == document {
                return try installLoadedProject(
                    reconciled,
                    id: document.project.id,
                    allowsRollback: true
                )
            }
            throw error
        }
    }

    func beginLifecycleRead(projectID: NovelProjectID) throws {
        guard !blockedLifecycleProjectIDs.contains(projectID) else {
            throw NovelError.storageIndeterminate(projectID)
        }
        guard !isRecoveringGenerationState,
              (pendingLifecycleOperationsByProject[projectID]?.isEmpty ?? true),
              !mutationIsInFlight(projectID: projectID),
              !generationStartProjectIDs.contains(projectID),
              !generationWriteProjectIDs.contains(projectID),
              !lifecycleReadProjectIDs.contains(projectID) else {
            throw NovelError.projectBusy(projectID)
        }
        try guardNoActiveGenerationRuntime(projectID: projectID)
        lifecycleReadProjectIDs.insert(projectID)
    }

    func endLifecycleRead(projectID: NovelProjectID) {
        lifecycleReadProjectIDs.remove(projectID)
    }

    func guardNoActiveGenerationRuntime(projectID: NovelProjectID) throws {
        guard !generationRuntimes.values.contains(where: { $0.projectID == projectID }) else {
            throw NovelError.projectBusy(projectID)
        }
    }

    func guardNoRunningWork(
        projectID: NovelProjectID,
        document: NovelProjectDocumentV1
    ) throws {
        try guardNoActiveGenerationRuntime(projectID: projectID)
        guard !document.activeRuns.contains(where: { $0.status == .running }) else {
            throw NovelError.projectBusy(projectID)
        }
    }

    func evictProjectFromRuntime(_ projectID: NovelProjectID) {
        committedProjects[projectID] = nil
        frozenProjectIDs.remove(projectID)
        generationStartProjectIDs.remove(projectID)
        generationWriteProjectIDs.remove(projectID)
        generationStartRunIDsByProject[projectID] = nil
        generationStartReservations = generationStartReservations.filter {
            $0.value.projectID != projectID
        }
        generationRuntimes = generationRuntimes.filter {
            $0.value.projectID != projectID
        }
    }
}

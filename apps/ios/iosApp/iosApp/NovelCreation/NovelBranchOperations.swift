import CryptoKit
import Foundation

struct NovelWorkingManuscriptChapter: Equatable, Sendable {
    let ordinal: Int
    let chapterID: NovelChapterID
    let title: String
    let versionID: NovelChapterVersionID
}

struct NovelRecentChapterRevertPlan: Equatable, Sendable {
    let chapters: [NovelWorkingManuscriptChapter]
    let targetCheckpointID: NovelCheckpointID
    let undoStepCount: Int
}

enum NovelRecentChapterRevertFailure: Error, Equatable, Sendable {
    case invalidChapterCount
    case notEnoughChapters
    case workingDiverged
    case cannotReachTarget
}

extension NovelRecentChapterRevertFailure: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .invalidChapterCount:
            "回退章数必须是 1 到 64。"
        case .notEnoughChapters:
            "当前正文没有这么多章可以回退。"
        case .workingDiverged:
            "请先同步手动改写，再回退章节。"
        case .cannotReachTarget:
            "无法回退到分支起点之前。这几章里有从 Fork 继承下来的正文。"
        }
    }
}

enum NovelBranchSemantics {
    static func normalizingDecodedSyncStatus(
        _ document: NovelProjectDocumentV1
    ) -> NovelProjectDocumentV1 {
        var normalized = document
        for index in normalized.branches.indices where
            normalized.branches[index].syncStatus == .synchronized {
            guard let head = normalized.checkpoints.first(where: {
                $0.id == normalized.branches[index].headCheckpointID
            }), syncStatus(for: head, checkpoints: normalized.checkpoints) == .needsSync else {
                continue
            }
            normalized.branches[index].syncStatus = .needsSync
        }
        return normalized
    }

    static func syncStatus(
        for checkpoint: NovelBranchCheckpointRecord,
        in document: NovelProjectDocumentV1
    ) -> NovelBranchSyncStatus {
        syncStatus(for: checkpoint, checkpoints: document.checkpoints)
    }

    static func syncStatus(
        for checkpoint: NovelBranchCheckpointRecord,
        checkpoints: [NovelBranchCheckpointRecord]
    ) -> NovelBranchSyncStatus {
        guard checkpoint.kind == .collection,
              let parentID = checkpoint.parentCheckpointID,
              let parent = checkpoints.first(where: { $0.id == parentID }),
              checkpoint.stateSnapshotID == parent.stateSnapshotID else {
            return .synchronized
        }
        return .needsSync
    }

    static func canUndoHead(
        _ checkpoint: NovelBranchCheckpointRecord,
        branch: NovelBranchRecord,
        checkpoints: [NovelBranchCheckpointRecord]
    ) -> Bool {
        if branch.syncStatus == .synchronized { return true }
        return branch.syncStatus == .needsSync &&
            branch.workingChapterSelections == checkpoint.chapterSelections &&
            branch.overrideRevisionIDs == checkpoint.branchOverrideRevisionIDs &&
            syncStatus(for: checkpoint, checkpoints: checkpoints) == .needsSync
    }

    static func undoTarget(
        for checkpoint: NovelBranchCheckpointRecord,
        checkpoints: [NovelBranchCheckpointRecord]
    ) -> NovelBranchCheckpointRecord? {
        guard let parentID = checkpoint.parentCheckpointID,
              let parent = checkpoints.first(where: { $0.id == parentID }) else {
            return nil
        }
        guard checkpoint.kind == .manualSync,
              syncStatus(for: parent, checkpoints: checkpoints) == .needsSync,
              let grandparentID = parent.parentCheckpointID,
              let grandparent = checkpoints.first(where: { $0.id == grandparentID }) else {
            return parent
        }
        return grandparent
    }

    static func undoTarget(
        for checkpoint: NovelBranchCheckpointRecord,
        branch: NovelBranchRecord,
        checkpoints: [NovelBranchCheckpointRecord]
    ) -> NovelBranchCheckpointRecord? {
        guard let parentID = checkpoint.parentCheckpointID,
              let directParent = checkpoints.first(where: { $0.id == parentID }) else {
            return nil
        }
        if branch.forkOrigin?.checkpointID == directParent.id {
            return directParent
        }
        return undoTarget(for: checkpoint, checkpoints: checkpoints)
    }

    static func workingManuscriptChapters(
        branch: NovelBranchRecord,
        document: NovelProjectDocumentV1
    ) -> [NovelWorkingManuscriptChapter] {
        workingManuscriptChapters(
            branch: branch,
            chapters: document.chapters,
            chapterVersions: document.chapterVersions
        )
    }

    static func workingManuscriptChapters(
        branch: NovelBranchRecord,
        chapters: [NovelChapterRecord],
        chapterVersions: [NovelChapterVersionRecord]
    ) -> [NovelWorkingManuscriptChapter] {
        let discarded = Set(
            chapters.compactMap { chapter -> NovelChapterID? in
                chapter.discardedAt == nil ? nil : chapter.id
            }
        )
        return branch.workingChapterSelections.enumerated().compactMap { index, selection in
            guard !discarded.contains(selection.chapterID),
                  let version = chapterVersions.first(where: {
                      $0.id == selection.versionID && $0.chapterID == selection.chapterID
                  }) else {
                return nil
            }
            return NovelWorkingManuscriptChapter(
                ordinal: index + 1,
                chapterID: selection.chapterID,
                title: version.title,
                versionID: version.id
            )
        }
    }

    static func recentChapterRevertPlan(
        chapterCount: Int,
        branch: NovelBranchRecord,
        document: NovelProjectDocumentV1
    ) -> Result<NovelRecentChapterRevertPlan, NovelRecentChapterRevertFailure> {
        recentChapterRevertPlan(
            chapterCount: chapterCount,
            branch: branch,
            chapters: document.chapters,
            chapterVersions: document.chapterVersions,
            checkpoints: document.checkpoints
        )
    }

    static func recentChapterRevertPlan(
        chapterCount: Int,
        branch: NovelBranchRecord,
        chapters: [NovelChapterRecord],
        chapterVersions: [NovelChapterVersionRecord],
        checkpoints: [NovelBranchCheckpointRecord]
    ) -> Result<NovelRecentChapterRevertPlan, NovelRecentChapterRevertFailure> {
        guard chapterCount >= 1, chapterCount <= 64 else {
            return .failure(.invalidChapterCount)
        }
        let working = workingManuscriptChapters(
            branch: branch,
            chapters: chapters,
            chapterVersions: chapterVersions
        )
        guard chapterCount <= working.count else {
            return .failure(.notEnoughChapters)
        }
        let removing = Array(working.suffix(chapterCount))
        let removingIDs = Set(removing.map(\.chapterID))
        guard let head = checkpoints.first(where: { $0.id == branch.headCheckpointID }) else {
            return .failure(.cannotReachTarget)
        }
        guard canUndoHead(head, branch: branch, checkpoints: checkpoints) else {
            return .failure(.workingDiverged)
        }
        var current = head
        var steps = 0
        while workingChapterIDs(in: current.chapterSelections, chapters: chapters)
            .contains(where: { removingIDs.contains($0) })
        {
            guard let next = undoTarget(
                for: current,
                branch: branch,
                checkpoints: checkpoints
            ), checkpointBelongsToBranch(
                next.id,
                branch: branch,
                checkpoints: checkpoints
            ) else {
                return .failure(.cannotReachTarget)
            }
            current = next
            steps += 1
            if steps > 256 {
                return .failure(.cannotReachTarget)
            }
        }
        guard steps >= 1 else {
            return .failure(.cannotReachTarget)
        }
        return .success(
            NovelRecentChapterRevertPlan(
                chapters: removing,
                targetCheckpointID: current.id,
                undoStepCount: steps
            )
        )
    }

    private static func workingChapterIDs(
        in selections: [NovelChapterSelection],
        chapters: [NovelChapterRecord]
    ) -> [NovelChapterID] {
        let discarded = Set(
            chapters.compactMap { chapter -> NovelChapterID? in
                chapter.discardedAt == nil ? nil : chapter.id
            }
        )
        return selections.compactMap { selection in
            discarded.contains(selection.chapterID) ? nil : selection.chapterID
        }
    }

    private static func checkpointBelongsToBranch(
        _ checkpointID: NovelCheckpointID,
        branch: NovelBranchRecord,
        checkpoints: [NovelBranchCheckpointRecord]
    ) -> Bool {
        guard let initial = checkpoints.first(where: { $0.kind == .initial }) else {
            return false
        }
        let boundaryID = branch.forkOrigin?.checkpointID ?? initial.id
        let byID = Dictionary(
            checkpoints.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var visited: Set<NovelCheckpointID> = []
        var currentID = checkpointID
        while currentID != boundaryID {
            guard visited.insert(currentID).inserted,
                  let checkpoint = byID[currentID],
                  checkpoint.createdOnBranchID == branch.id,
                  let parentID = checkpoint.parentCheckpointID else {
                return false
            }
            currentID = parentID
        }
        return byID[boundaryID] != nil
    }

    static func checkpointBelongsToBranch(
        _ checkpointID: NovelCheckpointID,
        branch: NovelBranchRecord,
        document: NovelProjectDocumentV1
    ) -> Bool {
        checkpointBelongsToBranch(
            checkpointID,
            branch: branch,
            checkpoints: document.checkpoints
        )
    }

    static func inheritedMessageID(
        operationID: NovelOperationID,
        sourceID: NovelMessageID
    ) -> NovelMessageID {
        NovelMessageID(deterministicUUID(
            namespace: operationID,
            label: "fork-message",
            source: sourceID.description
        ))
    }

    static func inheritedCandidateID(
        operationID: NovelOperationID,
        sourceID: NovelCandidateID
    ) -> NovelCandidateID {
        NovelCandidateID(deterministicUUID(
            namespace: operationID,
            label: "fork-candidate",
            source: sourceID.description
        ))
    }

    private static func deterministicUUID(
        namespace: NovelOperationID,
        label: String,
        source: String
    ) -> UUID {
        var bytes = Array(SHA256.hash(
            data: Data("\(namespace.description):\(label):\(source)".utf8)
        ).prefix(16))
        bytes[6] = (bytes[6] & 0x0f) | 0x50
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }
}

extension NovelReducer {
    static func forkBranch(
        _ command: NovelForkBranchCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let sourceIndex = try requireBranchMutation(
            command.context,
            branchID: command.sourceBranchID,
            in: document
        )
        let source = document.branches[sourceIndex]
        guard source.lifecycle == .active else {
            throw NovelError.branchNotFound(command.sourceBranchID)
        }
        guard source.activeRunID == nil else {
            throw NovelError.projectBusy(command.projectID)
        }
        guard let checkpoint = document.checkpoints.first(where: {
            $0.id == command.checkpointID
        }) else {
            throw NovelError.checkpointNotFound(command.checkpointID)
        }
        guard checkpoint.kind != .initial,
              NovelBranchSemantics.checkpointBelongsToBranch(
                  checkpoint.id,
                  branch: source,
                  document: document
              ) else {
            throw NovelError.invalidInput("The selected checkpoint is not forkable on this branch.")
        }
        guard document.branches.allSatisfy({ $0.id != command.branchID }),
              document.sessions.allSatisfy({ $0.id != command.sessionID }) else {
            throw NovelError.immutableRecordConflict("fork branch or Session identity")
        }
        try requireUnusedBranchOperation(command.context.operationID, in: document)
        let name = try normalizedBranchName(command.name)
        let sourceSession = try requireSession(source.sessionID, in: document)
        let prefix = sessionPrefix(sourceSession, through: checkpoint.sessionCursor)
        let sourceCandidateByID = Dictionary(
            document.candidates.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var inheritedCandidateIDs: [NovelCandidateID: NovelCandidateID] = [:]
        for message in prefix {
            guard let sourceCandidateID = message.candidateID else { continue }
            guard sourceCandidateByID[sourceCandidateID] != nil else {
                throw NovelError.invalidInput(
                    "The forked Session contains an unresolved candidate reference."
                )
            }
            inheritedCandidateIDs[sourceCandidateID] = NovelBranchSemantics.inheritedCandidateID(
                operationID: command.context.operationID,
                sourceID: sourceCandidateID
            )
        }
        let messageIDs = prefix.map {
            NovelBranchSemantics.inheritedMessageID(
                operationID: command.context.operationID,
                sourceID: $0.id
            )
        }
        let existingMessageIDs = Set(document.sessions.flatMap { $0.messages.map(\.id) })
        let existingCandidateIDs = Set(document.candidates.map(\.id))
        guard Set(messageIDs).count == messageIDs.count,
              Set(messageIDs).isDisjoint(with: existingMessageIDs),
              Set(inheritedCandidateIDs.values).count == inheritedCandidateIDs.count,
              Set(inheritedCandidateIDs.values).isDisjoint(with: existingCandidateIDs) else {
            throw NovelError.immutableRecordConflict("forked Session identity")
        }

        let messages = zip(prefix, messageIDs).map { sourceMessage, messageID in
            NovelSessionMessageRecord(
                id: messageID,
                sequence: sourceMessage.sequence,
                role: sourceMessage.role,
                mode: sourceMessage.mode,
                kind: sourceMessage.kind,
                content: sourceMessage.content,
                createdAt: sourceMessage.createdAt,
                runID: nil,
                candidateID: sourceMessage.candidateID.flatMap { inheritedCandidateIDs[$0] }
            )
        }
        let messageIDBySource = Dictionary(
            uniqueKeysWithValues: zip(prefix.map(\.id), messageIDs)
        )
        let inheritedCandidates: [NovelCandidateRecord] = inheritedCandidateIDs.compactMap {
            entry -> NovelCandidateRecord? in
            let (sourceID, inheritedID) = entry
            guard let candidate = sourceCandidateByID[sourceID],
                  let sourceMessageID = messageIDBySource[candidate.sourceMessageID] else {
                return nil
            }
            return NovelCandidateRecord(
                id: inheritedID,
                kind: candidate.kind,
                branchID: command.branchID,
                sessionID: command.sessionID,
                sourceMessageID: sourceMessageID,
                baseCheckpointID: candidate.baseCheckpointID,
                baseHeadRevision: candidate.baseHeadRevision,
                status: .inheritedReadOnly,
                content: candidate.content,
                sourceChapterVersionID: candidate.sourceChapterVersionID,
                collectedCheckpointID: nil,
                chapterPlanDigest: candidate.chapterPlanDigest,
                ghostwritePlanID: candidate.ghostwritePlanID,
                createdAt: candidate.createdAt
            )
        }.sorted { $0.sourceMessageID.description < $1.sourceMessageID.description }
        guard inheritedCandidates.count == inheritedCandidateIDs.count else {
            throw NovelError.invalidInput("The forked Session has incomplete candidate history.")
        }

        var forkLineage: Set<NovelCheckpointID> = []
        var lineageCheckpoint: NovelBranchCheckpointRecord? = checkpoint
        while let current = lineageCheckpoint, forkLineage.insert(current.id).inserted {
            lineageCheckpoint = current.parentCheckpointID.flatMap { parentID in
                document.checkpoints.first(where: { $0.id == parentID })
            }
        }
        let prefixSequences = Set(prefix.map(\.sequence))
        let inheritedArchives = (sourceSession.discussionArchives ?? [])
            .filter {
                forkLineage.contains($0.checkpointID) && prefixSequences.contains($0.throughSequence)
            }
            .map { archive in
                NovelDiscussionArchiveRecord(
                    id: NovelBranchSemantics.inheritedMessageID(
                        operationID: command.context.operationID,
                        sourceID: archive.id
                    ),
                    checkpointID: archive.checkpointID,
                    throughSequence: archive.throughSequence,
                    messageCount: archive.messageCount,
                    chapterID: archive.chapterID,
                    summary: archive.summary,
                    createdAt: archive.createdAt
                )
            }
        let inheritedArchiveIDs = Set(inheritedArchives.map(\.id))
        guard inheritedArchiveIDs.count == inheritedArchives.count,
              inheritedArchiveIDs.isDisjoint(with: existingMessageIDs),
              inheritedArchiveIDs.isDisjoint(with: Set(messageIDs)) else {
            throw NovelError.immutableRecordConflict("forked discussion archive identity")
        }
        let inheritedArchiveCursor = inheritedArchives
            .max { $0.throughSequence < $1.throughSequence }
            .map { NovelSessionCursor.through(sequence: $0.throughSequence) }

        let session = NovelSessionRecord(
            id: command.sessionID,
            branchID: command.branchID,
            revision: 0,
            messages: messages,
            archiveCursor: inheritedArchiveCursor,
            discussionArchives: inheritedArchives.isEmpty ? nil : inheritedArchives
        )
        let branch = NovelBranchRecord(
            id: command.branchID,
            name: name,
            sessionID: command.sessionID,
            createdAt: now,
            updatedAt: now,
            forkOrigin: NovelForkOrigin(
                parentBranchID: source.id,
                checkpointID: checkpoint.id
            ),
            headCheckpointID: checkpoint.id,
            currentStateSnapshotID: checkpoint.stateSnapshotID,
            headRevision: 0,
            workingRevision: 0,
            syncStatus: NovelBranchSemantics.syncStatus(for: checkpoint, in: document),
            lifecycle: .active,
            overrideRevisionIDs: checkpoint.branchOverrideRevisionIDs,
            workingChapterSelections: checkpoint.chapterSelections,
            activeRunID: nil
        )

        var next = document
        next.branches.append(branch)
        next.sessions.append(session)
        next.candidates.append(contentsOf: inheritedCandidates)
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.branchForked(
            projectID: command.projectID,
            sourceBranchID: source.id,
            branchID: branch.id,
            checkpointID: checkpoint.id,
            revision: next.project.revision
        )
        recordBranchOperation(
            command.context,
            kind: .forkBranch,
            payloadSHA256: try NovelAction.forkBranch(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func renameBranch(
        _ command: NovelRenameBranchCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let index = try requireBranchMutation(
            command.context,
            branchID: command.branchID,
            in: document
        )
        guard document.branches[index].lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        try requireUnusedBranchOperation(command.context.operationID, in: document)
        var next = document
        next.branches[index].name = try normalizedBranchName(command.name)
        next.branches[index].updatedAt = now
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.branchRenamed(
            projectID: command.projectID,
            branchID: command.branchID,
            revision: next.project.revision
        )
        recordBranchOperation(
            command.context,
            kind: .renameBranch,
            payloadSHA256: try NovelAction.renameBranch(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func deleteBranch(
        _ command: NovelDeleteBranchCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let index = try requireBranchMutation(
            command.context,
            branchID: command.branchID,
            in: document
        )
        let branch = document.branches[index]
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        guard branch.id != document.project.mainBranchID else {
            throw NovelError.invalidInput("Choose another main branch before deleting this branch.")
        }
        guard document.branches.filter({ $0.lifecycle == .active }).count > 1 else {
            throw NovelError.invalidInput("The only active branch cannot be deleted.")
        }
        try requireIdleBranch(branch, in: document)
        try requireUnusedBranchOperation(command.context.operationID, in: document)

        var next = document
        next.branches[index].lifecycle = .deleted
        next.branches[index].updatedAt = now
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.branchDeleted(
            projectID: command.projectID,
            branchID: command.branchID,
            revision: next.project.revision
        )
        recordBranchOperation(
            command.context,
            kind: .deleteBranch,
            payloadSHA256: try NovelAction.deleteBranch(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func undoBranchHead(
        _ command: NovelUndoBranchHeadCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let index = try requireBranchMutation(
            command.context,
            branchID: command.branchID,
            in: document
        )
        let branch = document.branches[index]
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        try requireIdleBranch(branch, in: document)
        let boundaryID = branch.forkOrigin?.checkpointID ?? document.checkpoints.first(where: {
            $0.kind == .initial
        })?.id
        guard branch.headCheckpointID != boundaryID,
              let current = document.checkpoints.first(where: {
                  $0.id == branch.headCheckpointID
              }) else {
            throw NovelError.invalidInput("The branch head cannot move past its history boundary.")
        }
        let target = NovelBranchSemantics.undoTarget(
            for: current,
            branch: branch,
            checkpoints: document.checkpoints
        )
        guard let target,
              NovelBranchSemantics.checkpointBelongsToBranch(
                  target.id,
                  branch: branch,
                  document: document
              ) else {
            throw NovelError.invalidInput("The branch head cannot move past its history boundary.")
        }
        guard branch.workingRevision == command.expectedWorkingRevision,
              NovelBranchSemantics.canUndoHead(
                  current,
                  branch: branch,
                  checkpoints: document.checkpoints
              ) else {
            throw NovelError.invalidInput("Synchronize the working manuscript before undoing its head.")
        }
        try requireUnusedBranchOperation(command.context.operationID, in: document)

        var next = document
        next.branches[index].headCheckpointID = target.id
        next.branches[index].currentStateSnapshotID = target.stateSnapshotID
        next.branches[index].workingChapterSelections = target.chapterSelections
        next.branches[index].overrideRevisionIDs = target.branchOverrideRevisionIDs
        next.branches[index].headRevision += 1
        next.branches[index].workingRevision += 1
        next.branches[index].syncStatus = NovelBranchSemantics.syncStatus(
            for: target,
            in: document
        )
        next.branches[index].updatedAt = now
        if let sessionIndex = next.sessions.firstIndex(where: { $0.id == branch.sessionID }) {
            var lineage: Set<NovelCheckpointID> = []
            var checkpoint: NovelBranchCheckpointRecord? = target
            while let current = checkpoint, lineage.insert(current.id).inserted {
                checkpoint = current.parentCheckpointID.flatMap { parentID in
                    document.checkpoints.first(where: { $0.id == parentID })
                }
            }
            let latestArchive = (next.sessions[sessionIndex].discussionArchives ?? [])
                .filter { lineage.contains($0.checkpointID) }
                .max { $0.throughSequence < $1.throughSequence }
            next.sessions[sessionIndex].archiveCursor = latestArchive.map {
                .through(sequence: $0.throughSequence)
            }
            if next.sessions[sessionIndex].archiveCursor != document.sessions.first(where: {
                $0.id == branch.sessionID
            })?.archiveCursor {
                next.sessions[sessionIndex].revision += 1
            }
        }
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.branchHeadMoved(
            projectID: command.projectID,
            branchID: branch.id,
            fromCheckpointID: current.id,
            toCheckpointID: target.id,
            headRevision: next.branches[index].headRevision,
            revision: next.project.revision
        )
        recordBranchOperation(
            command.context,
            kind: .undoBranchHead,
            payloadSHA256: try NovelAction.undoBranchHead(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func archiveDiscussion(
        _ command: NovelArchiveDiscussionCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let branchIndex = try requireBranchMutation(
            command.context,
            branchID: command.branchID,
            in: document
        )
        let branch = document.branches[branchIndex]
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        try requireIdleBranch(branch, in: document)
        guard branch.syncStatus == .synchronized else {
            throw NovelError.invalidInput("Synchronize the working manuscript before archiving discussion.")
        }
        guard let sessionIndex = document.sessions.firstIndex(where: {
            $0.id == branch.sessionID && $0.branchID == branch.id
        }) else {
            throw NovelError.sessionNotFound(branch.sessionID)
        }
        let session = document.sessions[sessionIndex]
        let previousSequence: Int64 = switch session.archiveCursor {
        case .through(let sequence): sequence
        case .empty, nil: -1
        }
        guard command.throughSequence > previousSequence,
              session.messages.contains(where: { $0.sequence == command.throughSequence }) else {
            throw NovelError.invalidInput("Discussion archive cursor must advance within the Session history.")
        }
        let archivedMessages = session.messages.filter {
            $0.sequence > previousSequence && $0.sequence <= command.throughSequence
        }
        guard !archivedMessages.isEmpty else {
            throw NovelError.invalidInput("Discussion archive has no new messages.")
        }
        let summary = try normalizedRequired(command.summary, field: "Discussion archive summary")
        guard summary.count <= 300 else {
            throw NovelError.invalidInput("Discussion archive summary exceeds 300 characters.")
        }
        guard !command.decisions.isEmpty else {
            throw NovelError.invalidInput("Discussion archive requires at least one confirmed decision.")
        }
        guard document.sessions.allSatisfy({ session in
            !session.messages.contains(where: { $0.id == command.archiveID }) &&
                !(session.discussionArchives ?? []).contains(where: { $0.id == command.archiveID })
        }) else {
            throw NovelError.immutableRecordConflict("discussion archive \(command.archiveID)")
        }
        if let chapterID = command.chapterID,
           !document.chapters.contains(where: { $0.id == chapterID }) {
            throw NovelError.invalidInput("Discussion archive references a missing chapter.")
        }
        guard Set(command.decisions.map(\.materialID)).count == command.decisions.count,
              Set(command.decisions.map(\.revisionID)).count == command.decisions.count else {
            throw NovelError.invalidInput("Discussion archive repeats a decision identifier.")
        }
        try requireUnusedBranchOperation(command.context.operationID, in: document)

        var next = document
        var decisionRevisionIDs: [NovelMaterialRevisionID] = []
        for decision in command.decisions {
            guard !next.materials.contains(where: { $0.id == decision.materialID }),
                  !next.materialRevisions.contains(where: { $0.id == decision.revisionID }) else {
                throw NovelError.immutableRecordConflict("discussion decision \(decision.materialID)")
            }
            if let relatedMaterialID = decision.relatedMaterialID,
               !next.materials.contains(where: {
                   $0.id == relatedMaterialID && !$0.isDeleted
               }) {
                throw NovelError.invalidInput("Discussion decision references a missing material.")
            }
            let topic = try normalizedRequired(decision.topic, field: "Discussion decision topic")
            let content = try normalizedRequired(
                decision.decision,
                field: "Discussion decision content"
            )
            let tags = decision.relatedMaterialID.map {
                ["related-material:\($0.description)"]
            } ?? []
            let revision = NovelMaterialRevisionRecord(
                id: decision.revisionID,
                materialID: decision.materialID,
                revision: 1,
                title: topic,
                content: content,
                tags: tags,
                injectionMode: .always,
                createdAt: now,
                operationID: command.context.operationID
            )
            next.materialRevisions.append(revision)
            next.materials.append(NovelMaterialRecord(
                id: decision.materialID,
                kind: .decisionLog,
                currentRevisionID: revision.id,
                revisionIDs: [revision.id]
            ))
            decisionRevisionIDs.append(revision.id)
        }

        next.branches[branchIndex].overrideRevisionIDs.append(contentsOf: decisionRevisionIDs)
        next.sessions[sessionIndex].archiveCursor = .through(sequence: command.throughSequence)
        next.sessions[sessionIndex].discussionArchives =
            (next.sessions[sessionIndex].discussionArchives ?? []) + [NovelDiscussionArchiveRecord(
                id: command.archiveID,
                checkpointID: command.checkpointID,
                throughSequence: command.throughSequence,
                messageCount: archivedMessages.count,
                chapterID: command.chapterID,
                summary: summary,
                createdAt: now
            )]
        next.sessions[sessionIndex].revision += 1
        let checkpoint = NovelBranchCheckpointRecord(
            id: command.checkpointID,
            kind: .discussionArchive,
            createdOnBranchID: branch.id,
            parentCheckpointID: branch.headCheckpointID,
            chapterSelections: branch.workingChapterSelections,
            stateSnapshotID: branch.currentStateSnapshotID,
            sessionCursor: session.messages.last.map {
                .through(sequence: $0.sequence)
            } ?? .empty,
            branchOverrideRevisionIDs: next.branches[branchIndex].overrideRevisionIDs,
            sourceCandidateID: nil,
            baseHeadRevision: branch.headRevision,
            operationID: command.context.operationID,
            createdAt: now
        )
        try appendCheckpoint(
            checkpoint,
            to: &next,
            expectedHeadRevision: branch.headRevision,
            advancesWorkingRevision: false,
            now: now
        )
        next.project.revision += 1
        next.project.configRevision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.discussionArchived(
            projectID: command.projectID,
            branchID: branch.id,
            archiveID: command.archiveID,
            checkpointID: checkpoint.id,
            decisionRevisionIDs: decisionRevisionIDs,
            projectRevision: next.project.revision,
            configRevision: next.project.configRevision
        )
        recordBranchOperation(
            command.context,
            kind: .archiveDiscussion,
            payloadSHA256: try NovelAction.archiveDiscussion(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func cloneCandidate(
        _ command: NovelCloneCandidateCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let branchIndex = try requireBranchMutation(
            command.context,
            branchID: command.branchID,
            in: document
        )
        let branch = document.branches[branchIndex]
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        try requireIdleBranch(branch, in: document)
        guard branch.syncStatus == .synchronized else {
            throw NovelError.invalidInput("Synchronize the working manuscript before cloning a candidate.")
        }
        guard let source = document.candidates.first(where: {
            $0.id == command.sourceCandidateID &&
                $0.branchID == branch.id &&
                $0.sessionID == branch.sessionID
        }), source.kind == .prose,
        source.status == .collected,
        source.collectedCheckpointID != nil,
        NovelCandidateSemantics.cloneBaseMatches(
            source,
            currentCheckpointID: branch.headCheckpointID,
            in: document
        ) else {
            throw NovelError.invalidInput("Only previously collected prose can be cloned.")
        }
        guard document.candidates.allSatisfy({ $0.id != command.candidateID }) else {
            throw NovelError.immutableRecordConflict("candidate \(command.candidateID)")
        }
        try requireUnusedBranchOperation(command.context.operationID, in: document)

        let clone = NovelCandidateRecord(
            id: command.candidateID,
            kind: source.kind,
            branchID: branch.id,
            sessionID: branch.sessionID,
            sourceMessageID: source.sourceMessageID,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            status: .available,
            content: source.content,
            sourceChapterVersionID: source.sourceChapterVersionID,
            clonedFromCandidateID: source.id,
            collectedCheckpointID: nil,
            chapterPlanDigest: source.chapterPlanDigest,
            ghostwritePlanID: source.ghostwritePlanID,
            createdAt: now
        )
        guard let sessionIndex = document.sessions.firstIndex(where: {
            $0.id == branch.sessionID
        }) else {
            throw NovelError.sessionNotFound(branch.sessionID)
        }
        var next = document
        next.candidates.append(clone)
        next.sessions[sessionIndex].revision += 1
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.candidateCloned(
            projectID: command.projectID,
            branchID: branch.id,
            sourceCandidateID: source.id,
            candidateID: clone.id,
            revision: next.project.revision
        )
        recordBranchOperation(
            command.context,
            kind: .cloneCandidate,
            payloadSHA256: try NovelAction.cloneCandidate(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func clarifyCharacterIdentity(
        _ command: NovelClarifyCharacterIdentityCommand,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> (NovelProjectDocumentV1, NovelOutcome) {
        let branchIndex = try requireBranchMutation(
            command.context,
            branchID: command.branchID,
            in: document
        )
        let branch = document.branches[branchIndex]
        try requireIdleBranch(branch, in: document)
        guard branch.syncStatus == .synchronized else {
            throw NovelError.projectBusy(command.projectID)
        }
        let mention = try normalizedRequired(command.mention, field: "Character mention")
        let clarification = try normalizedRequired(
            command.clarification,
            field: "Character identity clarification"
        )
        guard mention.count <= 200 else {
            throw NovelError.invalidInput("Character mention is too long.")
        }
        guard clarification.count <= 1_000 else {
            throw NovelError.invalidInput("Character identity clarification is too long.")
        }
        guard let state = document.stateSnapshots.first(where: {
            $0.id == branch.currentStateSnapshotID
        }) else {
            throw NovelError.stateSnapshotNotFound(branch.currentStateSnapshotID)
        }
        let mentionKey = NovelCharacterIdentityResolver.normalize(mention)
        guard state.unresolvedEntityNames.contains(where: {
            NovelCharacterIdentityResolver.normalize($0) == mentionKey
        }) else {
            throw NovelError.invalidInput("The character identity question is no longer active.")
        }
        guard !state.characterIdentityClarifications.contains(where: {
            NovelCharacterIdentityResolver.normalize($0.mention) == mentionKey
        }) else {
            throw NovelError.invalidInput("The character identity question was already answered.")
        }
        guard document.stateSnapshots.allSatisfy({ $0.id != command.stateSnapshotID }),
              document.checkpoints.allSatisfy({ $0.id != command.checkpointID }) else {
            throw NovelError.immutableRecordConflict("character identity clarification")
        }
        guard let baseCheckpoint = document.checkpoints.first(where: {
            $0.id == branch.headCheckpointID
        }) else {
            throw NovelError.checkpointNotFound(branch.headCheckpointID)
        }

        let record = NovelCharacterIdentityClarificationRecord(
            mention: mention,
            clarification: clarification,
            operationID: command.context.operationID,
            createdAt: now
        )
        let stateSnapshot = NovelStateSnapshotRecord(
            id: command.stateSnapshotID,
            eventIDs: state.eventIDs,
            summary: state.summary,
            branchOutline: state.branchOutline,
            unresolvedEntityNames: state.unresolvedEntityNames.filter {
                NovelCharacterIdentityResolver.normalize($0) != mentionKey
            },
            createdAt: now,
            settingProposalIDs: state.settingProposalIDs,
            characterIdentityClarifications: state.characterIdentityClarifications + [record],
            recentWrittenHighlights: state.recentWrittenHighlights
        )
        let checkpoint = NovelBranchCheckpointRecord(
            id: command.checkpointID,
            kind: .identityClarification,
            createdOnBranchID: branch.id,
            parentCheckpointID: baseCheckpoint.id,
            chapterSelections: baseCheckpoint.chapterSelections,
            stateSnapshotID: stateSnapshot.id,
            sessionCursor: baseCheckpoint.sessionCursor,
            branchOverrideRevisionIDs: baseCheckpoint.branchOverrideRevisionIDs,
            sourceCandidateID: nil,
            baseHeadRevision: branch.headRevision,
            operationID: command.context.operationID,
            createdAt: now
        )

        var next = document
        next.stateSnapshots.append(stateSnapshot)
        try appendCheckpoint(
            checkpoint,
            to: &next,
            expectedHeadRevision: branch.headRevision,
            advancesWorkingRevision: false,
            now: now
        )
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.characterIdentityClarified(
            projectID: command.projectID,
            branchID: command.branchID,
            mention: mention,
            checkpointID: checkpoint.id,
            stateSnapshotID: stateSnapshot.id,
            revision: next.project.revision
        )
        recordApplied(
            command.context,
            kind: .clarifyCharacterIdentity,
            payloadSHA256: try NovelAction.clarifyCharacterIdentity(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }
}

private extension NovelReducer {
    static func requireBranchMutation(
        _ context: NovelMutationContext,
        branchID: NovelBranchID,
        in document: NovelProjectDocumentV1
    ) throws -> Int {
        guard let expectedProjectRevision = context.expectedProjectRevision else {
            throw NovelError.invalidInput("Expected project revision is missing.")
        }
        guard expectedProjectRevision == document.project.revision else {
            throw NovelError.staleProjectRevision(
                expected: expectedProjectRevision,
                actual: document.project.revision
            )
        }
        guard let index = document.branches.firstIndex(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        guard let expectedHeadRevision = context.expectedBranchHeadRevision else {
            throw NovelError.invalidInput("Expected branch head revision is missing.")
        }
        guard expectedHeadRevision == document.branches[index].headRevision else {
            throw NovelError.staleBranchHeadRevision(
                expected: expectedHeadRevision,
                actual: document.branches[index].headRevision
            )
        }
        return index
    }

    static func requireIdleBranch(
        _ branch: NovelBranchRecord,
        in document: NovelProjectDocumentV1
    ) throws {
        guard branch.activeRunID == nil,
              !document.pendingOperations.contains(where: { $0.branchID == branch.id }),
              !document.polishTransactions.contains(where: {
                  $0.branchID == branch.id &&
                      ($0.status == .pending || $0.status == .retryable)
              }) else {
            throw NovelError.projectBusy(document.project.id)
        }
    }

    static func requireSession(
        _ id: NovelSessionID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelSessionRecord {
        guard let session = document.sessions.first(where: { $0.id == id }) else {
            throw NovelError.sessionNotFound(id)
        }
        return session
    }

    static func sessionPrefix(
        _ session: NovelSessionRecord,
        through cursor: NovelSessionCursor
    ) -> [NovelSessionMessageRecord] {
        switch cursor {
        case .empty:
            []
        case .through(let sequence):
            session.messages.filter { $0.sequence <= sequence }
        }
    }

    static func requireUnusedBranchOperation(
        _ operationID: NovelOperationID,
        in document: NovelProjectDocumentV1
    ) throws {
        let used = document.appliedOperations.contains { $0.operationID == operationID } ||
            document.factAttempts.contains { $0.attemptOperationID == operationID } ||
            document.pendingOperations.contains { $0.operationID == operationID } ||
            document.polishTransactions.contains { $0.operationID == operationID } ||
            document.activeRuns.contains { $0.operationID == operationID }
        guard !used else { throw NovelError.idempotencyConflict(operationID) }
    }

    static func normalizedBranchName(_ value: String) throws -> String {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            throw NovelError.invalidInput("Branch name cannot be empty.")
        }
        return normalized
    }

    static func recordBranchOperation(
        _ context: NovelMutationContext,
        kind: NovelOperationKind,
        payloadSHA256: String,
        outcome: NovelOutcome,
        in document: inout NovelProjectDocumentV1,
        now: Date
    ) {
        document.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: context.operationID,
            kind: kind,
            payloadSHA256: payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: document.project.revision,
            appliedAt: now
        ))
    }
}

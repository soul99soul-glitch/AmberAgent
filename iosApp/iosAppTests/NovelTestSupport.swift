import Foundation
@testable import iosApp

enum NovelScriptedModelError: Error, Equatable, Sendable {
    case scriptUnavailable
}

extension NovelScriptedModelError: LocalizedError {
    var errorDescription: String? {
        "No scripted model response is available."
    }
}

enum NovelModelScriptStep: Equatable, Sendable {
    case activity
    case delta(String)
    case replacement(String)
    case usage(NovelModelUsage)
    case askUser(NovelAskUserPrompt, preface: String)
    case complete
    case fail(NovelModelFailure)
    case pause
}

struct NovelModelScript: Equatable, Sendable {
    let steps: [NovelModelScriptStep]
    let ignoresCancellation: Bool

    init(steps: [NovelModelScriptStep], ignoresCancellation: Bool = false) {
        self.steps = steps
        self.ignoresCancellation = ignoresCancellation
    }
}

/// Deterministic provider substitute for lifecycle and cancellation tests.
actor ScriptedNovelModelAdapter: NovelModelRunning {
    private struct ActiveScript: Sendable {
        let task: Task<Void, Never>
        let pauseGate: NovelModelScriptPauseGate
        let ignoresCancellation: Bool
    }

    private let resolvedModel: NovelResolvedModel
    private let resolutionFailure: NovelModelFailure?
    private var scripts: [NovelModelScript]
    private var activeScripts: [NovelRunID: ActiveScript] = [:]
    private var seenRunIDs: Set<NovelRunID> = []

    private(set) var resolvedPolicies: [NovelProjectModelPolicy] = []
    private(set) var requests: [NovelModelRequest] = []
    private(set) var cancelledRunIDs: [NovelRunID] = []

    init(
        resolvedModel: NovelResolvedModel,
        resolutionFailure: NovelModelFailure? = nil,
        scripts: [NovelModelScript] = []
    ) {
        self.resolvedModel = resolvedModel
        self.resolutionFailure = resolutionFailure
        self.scripts = scripts
    }

    func resolveModel(for policy: NovelProjectModelPolicy) async throws -> NovelResolvedModel {
        resolvedPolicies.append(policy)
        if let resolutionFailure {
            throw resolutionFailure
        }
        return resolvedModel
    }

    func start(_ request: NovelModelRequest) async throws -> AsyncStream<NovelModelEvent> {
        requests.append(request)
        guard !seenRunIDs.contains(request.runID) else {
            throw NovelModelAdapterError.duplicateRunID(request.runID)
        }
        guard !scripts.isEmpty else {
            throw NovelScriptedModelError.scriptUnavailable
        }

        seenRunIDs.insert(request.runID)
        let script = scripts.removeFirst()
        let pauseGate = NovelModelScriptPauseGate()
        let (stream, continuation) = AsyncStream<NovelModelEvent>.makeStream(
            bufferingPolicy: .unbounded
        )
        let runID = request.runID
        let task = Task { [weak self] in
            await Self.emit(script, through: continuation, pauseGate: pauseGate)
            await self?.scriptDidFinish(runID: runID)
        }
        activeScripts[runID] = ActiveScript(
            task: task,
            pauseGate: pauseGate,
            ignoresCancellation: script.ignoresCancellation
        )
        return stream
    }

    func cancel(runID: NovelRunID) async {
        cancelledRunIDs.append(runID)
        guard let active = activeScripts[runID], !active.ignoresCancellation else {
            return
        }

        active.task.cancel()
        active.pauseGate.cancel()
    }

    func enqueue(_ script: NovelModelScript) {
        scripts.append(script)
    }

    func resume(runID: NovelRunID) {
        activeScripts[runID]?.pauseGate.resume()
    }

    private func scriptDidFinish(runID: NovelRunID) {
        activeScripts[runID] = nil
    }

    private nonisolated static func emit(
        _ script: NovelModelScript,
        through continuation: AsyncStream<NovelModelEvent>.Continuation,
        pauseGate: NovelModelScriptPauseGate
    ) async {
        for step in script.steps {
            if Task.isCancelled, !script.ignoresCancellation {
                break
            }

            switch step {
            case .activity:
                continuation.yield(.activity)
            case .delta(let text):
                continuation.yield(.textDelta(text))
            case .replacement(let text):
                continuation.yield(.textReplacement(text))
            case .usage(let usage):
                continuation.yield(.usage(usage))
            case .askUser(let prompt, let preface):
                continuation.yield(.askUser(prompt, preface: preface))
            case .complete:
                continuation.yield(.completed)
            case .fail(let failure):
                continuation.yield(.failed(failure))
            case .pause:
                guard await pauseGate.wait() else {
                    continuation.finish()
                    return
                }
            }
        }
        continuation.finish()
    }
}

private final class NovelModelScriptPauseGate: @unchecked Sendable {
    private let lock = NSLock()
    private var permits = 0
    private var isCancelled = false
    private var waiters: [CheckedContinuation<Bool, Never>] = []

    func wait() async -> Bool {
        await withCheckedContinuation { continuation in
            lock.lock()
            if isCancelled {
                lock.unlock()
                continuation.resume(returning: false)
            } else if permits > 0 {
                permits -= 1
                lock.unlock()
                continuation.resume(returning: true)
            } else {
                waiters.append(continuation)
                lock.unlock()
            }
        }
    }

    func resume() {
        lock.lock()
        guard !isCancelled else {
            lock.unlock()
            return
        }
        if waiters.isEmpty {
            permits += 1
            lock.unlock()
            return
        }
        let waiter = waiters.removeFirst()
        lock.unlock()
        waiter.resume(returning: true)
    }

    func cancel() {
        lock.lock()
        guard !isCancelled else {
            lock.unlock()
            return
        }
        isCancelled = true
        let pending = waiters
        waiters.removeAll()
        lock.unlock()
        pending.forEach { $0.resume(returning: false) }
    }
}

private struct NovelRecoveryKey: Hashable, Sendable {
    let projectID: NovelProjectID
    let runID: NovelRunID
}

private struct NovelLifecycleOperationKey: Hashable, Sendable {
    let projectID: NovelProjectID
    let operationID: NovelOperationID
}

actor InMemoryNovelProjectRepository: NovelProjectPersisting {
    private var documents: [NovelProjectID: NovelProjectDocumentV1] = [:]
    private var previousDocuments: [NovelProjectID: NovelProjectDocumentV1] = [:]
    private var recoveries: [NovelRecoveryKey: NovelRecoverySidecarV1] = [:]
    private var lifecycleOperations: [
        NovelLifecycleOperationKey: NovelProjectLifecycleOperationRecord
    ] = [:]
    private var ghostwriteProgress: [
        String: NovelGhostwriteBatchProgressRecord
    ] = [:]
    private var nextWriteFailure: NovelError?

    private func ghostwriteProgressKey(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> String {
        "\(projectID.description)|\(branchID.description)"
    }

    func failNextWrite(with error: NovelError = .repositoryFailure("Injected write failure.")) {
        nextWriteFailure = error
    }

    func listProjects() async throws -> [NovelProjectSummary] {
        documents.values
            .map { NovelProjectSummary(document: $0) }
            .sorted(by: NovelProjectSummary.listOrder)
    }

    func loadProject(id: NovelProjectID) async throws -> NovelLoadedProject {
        guard let document = documents[id] else {
            throw NovelError.projectNotFound(id)
        }
        try NovelDocumentValidator.validate(document)
        return NovelLoadedProject(document: document, access: .readWrite)
    }

    func createProject(_ document: NovelProjectDocumentV1) async throws -> NovelLoadedProject {
        try consumeWriteFailureIfNeeded()
        guard documents[document.project.id] == nil else {
            throw NovelError.projectAlreadyExists(document.project.id)
        }
        try NovelDocumentValidator.validate(document)
        documents[document.project.id] = document
        return NovelLoadedProject(document: document, access: .readWrite)
    }

    func commitProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64,
        authorization: NovelRepositoryCommitAuthorization?
    ) async throws -> NovelLoadedProject {
        try consumeWriteFailureIfNeeded()
        guard let current = documents[document.project.id] else {
            throw NovelError.projectNotFound(document.project.id)
        }
        guard current.project.revision == expectedRevision else {
            throw NovelError.staleProjectRevision(
                expected: expectedRevision,
                actual: current.project.revision
            )
        }
        guard document.project.revision == expectedRevision + 1 else {
            throw NovelError.invalidDocument(["A commit must advance project revision exactly once."])
        }
        try NovelDocumentValidator.validateTransition(from: current, to: document)
        try authorization?.claim()
        previousDocuments[document.project.id] = current
        documents[document.project.id] = document
        return NovelLoadedProject(document: document, access: .readWrite)
    }

    func replaceProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64
    ) async throws -> NovelLoadedProject {
        try consumeWriteFailureIfNeeded()
        guard let current = documents[document.project.id] else {
            throw NovelError.projectNotFound(document.project.id)
        }
        guard current.project.revision == expectedRevision else {
            throw NovelError.staleProjectRevision(
                expected: expectedRevision,
                actual: current.project.revision
            )
        }
        guard !current.activeRuns.contains(where: { $0.status == .running }) else {
            throw NovelError.projectBusy(document.project.id)
        }
        try NovelDocumentValidator.validate(document)
        previousDocuments[document.project.id] = nil
        documents[document.project.id] = document
        recoveries = recoveries.filter { $0.key.projectID != document.project.id }
        return NovelLoadedProject(document: document, access: .readWrite)
    }

    func deleteProject(id: NovelProjectID, expectedRevision: Int64) async throws {
        try consumeWriteFailureIfNeeded()
        guard let current = documents[id] else {
            previousDocuments[id] = nil
            recoveries = recoveries.filter { $0.key.projectID != id }
            return
        }
        guard current.project.revision == expectedRevision else {
            throw NovelError.staleProjectRevision(
                expected: expectedRevision,
                actual: current.project.revision
            )
        }
        guard !current.activeRuns.contains(where: { $0.status == .running }) else {
            throw NovelError.projectBusy(id)
        }
        documents[id] = nil
        previousDocuments[id] = nil
        recoveries = recoveries.filter { $0.key.projectID != id }
    }

    func lifecycleOperation(
        projectID: NovelProjectID,
        operationID: NovelOperationID
    ) async throws -> NovelProjectLifecycleOperationRecord? {
        lifecycleOperations[NovelLifecycleOperationKey(
            projectID: projectID,
            operationID: operationID
        )]
    }

    func lifecycleOperationIDs(projectID: NovelProjectID) async throws -> Set<NovelOperationID> {
        Set(lifecycleOperations.keys.compactMap {
            $0.projectID == projectID ? $0.operationID : nil
        })
    }

    func listPendingLifecycleOperations() async throws -> [NovelProjectLifecycleOperationRecord] {
        lifecycleOperations.values
            .filter { $0.state == .pending }
            .sorted {
                if $0.projectID != $1.projectID {
                    return $0.projectID.description < $1.projectID.description
                }
                return $0.operationID.description < $1.operationID.description
            }
    }

    func blockedLifecycleProjectIDs() async throws -> Set<NovelProjectID> {
        []
    }

    func writeLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) async throws {
        try consumeWriteFailureIfNeeded()
        try record.validate()
        let key = NovelLifecycleOperationKey(
            projectID: record.projectID,
            operationID: record.operationID
        )
        if let current = lifecycleOperations[key] {
            try current.validateTransition(to: record)
        }
        lifecycleOperations[key] = record
    }

    func removeLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) async throws {
        try consumeWriteFailureIfNeeded()
        let key = NovelLifecycleOperationKey(
            projectID: record.projectID,
            operationID: record.operationID
        )
        guard let current = lifecycleOperations[key] else { return }
        guard current == record, current.state == .pending else {
            throw NovelError.storageIndeterminate(record.projectID)
        }
        lifecycleOperations[key] = nil
    }

    func restorePreviousProject(
        id: NovelProjectID,
        expectedDocumentSHA256: String
    ) async throws -> NovelLoadedProject {
        try consumeWriteFailureIfNeeded()
        guard let previous = previousDocuments[id] else {
            throw NovelError.projectNotFound(id)
        }
        try NovelDocumentValidator.validate(previous)
        guard try NovelProjectPackageCodec.encode(previous).projectSHA256 ==
            expectedDocumentSHA256 else {
            throw NovelError.storageIndeterminate(id)
        }
        documents[id] = previous
        return NovelLoadedProject(document: previous, access: .readWrite)
    }

    func listRecoverySidecars() async throws -> [NovelRecoverySidecarV1] {
        recoveries.values.sorted {
            if $0.updatedAt != $1.updatedAt { return $0.updatedAt < $1.updatedAt }
            return $0.runID.description < $1.runID.description
        }
    }

    func writeRecoverySidecar(_ sidecar: NovelRecoverySidecarV1) async throws {
        try consumeWriteFailureIfNeeded()
        try NovelDocumentValidator.validateRecovery(sidecar)
        let key = NovelRecoveryKey(projectID: sidecar.projectID, runID: sidecar.runID)
        if let current = recoveries[key] {
            if current.sequence > sidecar.sequence {
                throw NovelError.invalidRecovery("Recovery sequence cannot move backwards.")
            }
            if current.sequence == sidecar.sequence {
                guard current == sidecar else {
                    throw NovelError.invalidRecovery(
                        "A recovery sequence cannot be reused with different content."
                    )
                }
                return
            }
        }
        recoveries[key] = sidecar
    }

    func removeRecoverySidecar(projectID: NovelProjectID, runID: NovelRunID) async throws {
        try consumeWriteFailureIfNeeded()
        recoveries[NovelRecoveryKey(projectID: projectID, runID: runID)] = nil
    }

    func loadGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelGhostwriteBatchProgressRecord? {
        ghostwriteProgress[ghostwriteProgressKey(projectID: projectID, branchID: branchID)]
    }

    func saveGhostwriteBatchProgress(_ record: NovelGhostwriteBatchProgressRecord) async throws {
        try consumeWriteFailureIfNeeded()
        let key = ghostwriteProgressKey(
            projectID: record.projectID,
            branchID: record.branchID
        )
        ghostwriteProgress[key] = record
    }

    func removeGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws {
        try consumeWriteFailureIfNeeded()
        ghostwriteProgress[ghostwriteProgressKey(projectID: projectID, branchID: branchID)] = nil
    }

    private func consumeWriteFailureIfNeeded() throws {
        guard let failure = nextWriteFailure else { return }
        nextWriteFailure = nil
        throw failure
    }
}

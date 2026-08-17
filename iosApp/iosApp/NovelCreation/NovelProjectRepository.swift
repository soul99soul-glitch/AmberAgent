import Foundation

enum NovelRepositoryFailureStage: String, Hashable, Sendable {
    case afterTempWrite
    case afterTempValidation
    case beforePrimaryInstall
    case afterPrimaryInstallBeforeIndex
    case beforeIndexWrite
    case beforeRecoveryWrite
    case afterRecoveryWrite
    case beforeRecoveryDelete
    case beforeReplacementMarkerWrite
    case afterReplacementInstallBeforeCleanup
    case beforeDeletionTombstoneWrite
    case afterDeletionTombstoneWrite
    case beforeDeletionCleanup
}

actor NovelFileProjectRepository: NovelProjectPersisting {
    static let maximumProjectBytes = 100 * 1_024 * 1_024

    private struct IndexV1: Codable, Sendable {
        let schemaVersion: Int
        let projects: [NovelProjectSummary]
    }

    /// Sidecar written next to `index.json` so inventory can skip full document
    /// decode only when every on-disk project file still matches the last scan.
    private struct IndexManifestV1: Codable, Sendable, Equatable {
        struct Entry: Codable, Sendable, Equatable, Hashable {
            let projectID: NovelProjectID
            let primaryByteCount: Int?
            let primaryModifiedAt: TimeInterval?
            let previousByteCount: Int?
            let previousModifiedAt: TimeInterval?
        }

        let schemaVersion: Int
        let entries: [Entry]
    }

    private struct SchemaHeader: Decodable {
        let schemaVersion: Int
    }

    private struct ProjectLifecycleMarkerV1: Codable, Equatable {
        enum Kind: String, Codable {
            case deletion
            case replacement
        }

        let schemaVersion: Int
        let projectID: NovelProjectID
        let kind: Kind
        let expectedRevision: Int64
    }

    private struct RecoveryFileIdentity: Equatable {
        let projectID: NovelProjectID
        let runID: NovelRunID
    }

    private struct LifecycleFileIdentity: Equatable {
        let projectID: NovelProjectID
        let operationID: NovelOperationID
    }

    private enum FileReadFailure: Error {
        case missing
    }

    private let rootDirectory: URL
    private let fileManager: FileManager
    private let failingStages: Set<NovelRepositoryFailureStage>
    /// Per-project encode cache so unchanged heavy sections (injection receipts,
    /// snapshots, …) are not re-encoded on every small tool write.
    private var sectionCaches: [NovelProjectID: NovelProjectShardedStorage.SectionCache] = [:]

    init(
        rootDirectory: URL,
        fileManager: FileManager = .default,
        failingStages: Set<NovelRepositoryFailureStage> = []
    ) {
        self.rootDirectory = rootDirectory
        self.fileManager = fileManager
        self.failingStages = failingStages
    }

    static func defaultRootDirectory(fileManager: FileManager = .default) throws -> URL {
        guard let applicationSupport = fileManager.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else {
            throw NovelError.storageUnavailable("Application Support directory is unavailable.")
        }
        return applicationSupport
            .appendingPathComponent("AmberAgent", isDirectory: true)
            .appendingPathComponent("NovelCreation", isDirectory: true)
    }

    func listProjects() async throws -> [NovelProjectSummary] {
        try ensureDirectories()
        finishDeletionTombstonesBestEffort()
        // Fast path: the on-disk index already holds lightweight summaries. Full
        // document decode of every project on every entry made large novels feel
        // like a multi-second "正在读取项目" stall. Prefer the index when its
        // project set still matches disk and no project file is newer than the
        // index itself; fall back to a full scan when anything looks stale.
        if let cached = try? loadCachedProjectSummariesIfFresh() {
            return cached
        }
        do {
            let summaries = try scanProjectSummaries()
            writeIndexBestEffort(summaries)
            return summaries
        } catch {
            if let index = try? readIndex() {
                let deleted = deletionTombstoneProjectIDs()
                return index.projects
                    .filter { !deleted.contains($0.id) }
                    .sorted(by: NovelProjectSummary.listOrder)
            }
            throw error
        }
    }

    func loadProject(id: NovelProjectID) async throws -> NovelLoadedProject {
        try ensureDirectories()
        if isDeletionTombstoned(id) {
            finishDeletionBestEffort(projectID: id)
            throw NovelError.projectNotFound(id)
        }
        let package = packageURL(for: id)
        let hasPackage = NovelProjectShardedStorage.isPackage(at: package, fileManager: fileManager)
        let hasMonofile = fileManager.fileExists(atPath: primaryURL(for: id).path)
        if isReplacementMarked(id), !hasPackage, !hasMonofile {
            throw NovelError.storageIndeterminate(id)
        }
        guard hasPackage || hasMonofile ||
                fileManager.fileExists(atPath: previousURL(for: id).path) ||
                fileManager.fileExists(
                    atPath: NovelProjectShardedStorage.previousLayoutURL(in: package).path
                ) else {
            throw NovelError.projectNotFound(id)
        }
        do {
            let document = try readInstalledProjectDocument(projectID: id)
            finishReplacementBestEffort(projectID: id)
            return NovelLoadedProject(document: document, access: .readWrite)
        } catch let error as NovelError {
            if isReplacementMarked(id) {
                throw NovelError.storageIndeterminate(id)
            }
            switch error {
            case .unsupportedSchema, .storageUnavailable:
                throw error
            default:
                return try loadPreviousProject(id: id, primaryFailure: String(describing: error))
            }
        } catch {
            if isReplacementMarked(id) {
                throw NovelError.storageIndeterminate(id)
            }
            return try loadPreviousProject(id: id, primaryFailure: String(describing: error))
        }
    }

    func createProject(_ document: NovelProjectDocumentV1) async throws -> NovelLoadedProject {
        try ensureDirectories()
        try NovelDocumentValidator.validate(document)
        let projectID = document.project.id
        let isReinstallingDeletedProject = isDeletionTombstoned(projectID)
        if isReinstallingDeletedProject {
            try cleanupProjectFiles(projectID: projectID, includingPrimary: true)
        } else if isReplacementMarked(projectID) {
            throw NovelError.storageIndeterminate(projectID)
        }
        guard !projectExistsOnDisk(projectID) else {
            throw NovelError.projectAlreadyExists(projectID)
        }
        try failIfRequested(.beforePrimaryInstall)
        do {
            try writeShardedProject(document)
        } catch {
            if let installed = try? readInstalledProjectDocument(projectID: projectID),
               installed.project.revision == document.project.revision {
                // Install completed despite a late error.
            } else {
                throw NovelError.repositoryFailure("Could not create novel project: \(error)")
            }
        }
        if isReinstallingDeletedProject {
            do {
                try fileManager.removeItem(at: tombstoneURL(for: projectID))
            } catch {
                throw NovelError.storageIndeterminate(projectID)
            }
        }
        do {
            try failIfRequested(.afterPrimaryInstallBeforeIndex)
        } catch {
            throw NovelError.storageIndeterminate(projectID)
        }
        upsertIndexBestEffort(document: document)
        return NovelLoadedProject(document: document, access: .readWrite)
    }

    func commitProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64,
        authorization: NovelRepositoryCommitAuthorization?
    ) async throws -> NovelLoadedProject {
        try ensureDirectories()
        let projectID = document.project.id
        guard !isDeletionTombstoned(projectID), !isReplacementMarked(projectID) else {
            throw NovelError.projectNotFound(projectID)
        }
        let loaded = try await loadProject(id: projectID)
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: projectID)
        }
        guard loaded.document.project.revision == expectedRevision else {
            throw NovelError.staleProjectRevision(
                expected: expectedRevision,
                actual: loaded.document.project.revision
            )
        }
        guard document.project.revision == expectedRevision + 1 else {
            throw NovelError.invalidDocument(["A commit must advance project revision exactly once."])
        }
        try NovelDocumentValidator.validateTransition(from: loaded.document, to: document)
        try failIfRequested(.beforePrimaryInstall)
        try authorization?.claim()

        do {
            try writeShardedProject(document)
        } catch {
            if let installed = try? readInstalledProjectDocument(projectID: projectID),
               installed.project.revision == document.project.revision {
                // Treat an installed next revision as committed.
            } else if let stillCurrent = try? readInstalledProjectDocument(projectID: projectID),
                      stillCurrent.project.revision == expectedRevision {
                throw NovelError.repositoryFailure("Could not replace novel project: \(error)")
            } else {
                throw NovelError.storageIndeterminate(projectID)
            }
        }

        do {
            try failIfRequested(.afterPrimaryInstallBeforeIndex)
        } catch {
            throw NovelError.storageIndeterminate(projectID)
        }
        upsertIndexBestEffort(document: document)
        return NovelLoadedProject(document: document, access: .readWrite)
    }

    func replaceProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64
    ) async throws -> NovelLoadedProject {
        try ensureDirectories()
        try NovelDocumentValidator.validate(document)
        let projectID = document.project.id
        guard !isDeletionTombstoned(projectID) else {
            throw NovelError.projectNotFound(projectID)
        }
        let loaded = try await loadProject(id: projectID)
        guard loaded.document.project.revision == expectedRevision else {
            throw NovelError.staleProjectRevision(
                expected: expectedRevision,
                actual: loaded.document.project.revision
            )
        }
        guard !loaded.document.activeRuns.contains(where: { $0.status == .running }) else {
            throw NovelError.projectBusy(projectID)
        }

        try failIfRequested(.beforeReplacementMarkerWrite)
        do {
            try writeLifecycleMarker(ProjectLifecycleMarkerV1(
                schemaVersion: 1,
                projectID: projectID,
                kind: .replacement,
                expectedRevision: expectedRevision
            ), to: replacementMarkerURL(for: projectID))
        } catch {
            if isReplacementMarked(projectID) {
                throw NovelError.storageIndeterminate(projectID)
            }
            throw error
        }
        try failIfRequested(.beforePrimaryInstall)

        do {
            try writeShardedProject(document)
        } catch {
            if let installed = try? readInstalledProjectDocument(projectID: projectID),
               installed.project.revision == document.project.revision {
                // Continue cleanup after an indeterminate result.
            } else if let unchanged = try? readInstalledProjectDocument(projectID: projectID),
                      unchanged.project.revision == loaded.document.project.revision {
                do {
                    try fileManager.removeItem(at: replacementMarkerURL(for: projectID))
                } catch {
                    throw NovelError.storageIndeterminate(projectID)
                }
                throw NovelError.repositoryFailure("Could not replace imported novel project: \(error)")
            } else {
                throw NovelError.storageIndeterminate(projectID)
            }
        }
        do {
            try failIfRequested(.afterReplacementInstallBeforeCleanup)
            try cleanupProjectFiles(projectID: projectID, includingPrimary: false)
            try fileManager.removeItem(at: replacementMarkerURL(for: projectID))
        } catch {
            throw NovelError.storageIndeterminate(projectID)
        }
        upsertIndexBestEffort(document: document)
        return NovelLoadedProject(document: document, access: .readWrite)
    }

    func deleteProject(id: NovelProjectID, expectedRevision: Int64) async throws {
        try ensureDirectories()
        if isDeletionTombstoned(id) {
            finishDeletionBestEffort(projectID: id)
            return
        }
        let loaded = try await loadProject(id: id)
        guard loaded.document.project.revision == expectedRevision else {
            throw NovelError.staleProjectRevision(
                expected: expectedRevision,
                actual: loaded.document.project.revision
            )
        }
        guard !loaded.document.activeRuns.contains(where: { $0.status == .running }) else {
            throw NovelError.projectBusy(id)
        }

        try deleteProjectArtifacts(id: id, expectedRevision: expectedRevision)
    }

    func discardUnavailableProject(id: NovelProjectID) async throws {
        try ensureDirectories()
        if isDeletionTombstoned(id) {
            finishDeletionBestEffort(projectID: id)
            return
        }
        guard let summary = try scanProjectSummaries().first(where: { $0.id == id }) else {
            throw NovelError.projectNotFound(id)
        }
        guard summary.loadError != nil else {
            throw NovelError.staleProjectRevision(expected: 0, actual: summary.revision)
        }

        try deleteProjectArtifacts(id: id, expectedRevision: 0)
    }

    private func deleteProjectArtifacts(id: NovelProjectID, expectedRevision: Int64) throws {
        try failIfRequested(.beforeDeletionTombstoneWrite)
        do {
            try writeLifecycleMarker(ProjectLifecycleMarkerV1(
                schemaVersion: 1,
                projectID: id,
                kind: .deletion,
                expectedRevision: expectedRevision
            ), to: tombstoneURL(for: id))
        } catch {
            if isDeletionTombstoned(id) {
                throw NovelError.storageIndeterminate(id)
            }
            throw error
        }
        do {
            try invalidateIndex()
            try failIfRequested(.afterDeletionTombstoneWrite)
            try failIfRequested(.beforeDeletionCleanup)
            try cleanupProjectFiles(projectID: id, includingPrimary: true)
        } catch {
            refreshIndexBestEffort()
            throw NovelError.storageIndeterminate(id)
        }
        refreshIndexBestEffort()
    }

    func lifecycleOperation(
        projectID: NovelProjectID,
        operationID: NovelOperationID
    ) async throws -> NovelProjectLifecycleOperationRecord? {
        try ensureDirectories()
        let url = lifecycleOperationURL(projectID: projectID, operationID: operationID)
        guard fileManager.fileExists(atPath: url.path) else { return nil }
        return try readLifecycleOperation(
            at: url,
            expected: LifecycleFileIdentity(projectID: projectID, operationID: operationID)
        )
    }

    func lifecycleOperationIDs(projectID: NovelProjectID) async throws -> Set<NovelOperationID> {
        try ensureDirectories()
        let urls = try fileManager.contentsOfDirectory(
            at: lifecycleDirectory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        )
        return Set(urls.compactMap { url in
            guard url.pathExtension == "json",
                  let identity = lifecycleFileIdentity(for: url),
                  identity.projectID == projectID else {
                return nil
            }
            return identity.operationID
        })
    }

    func listPendingLifecycleOperations() async throws -> [NovelProjectLifecycleOperationRecord] {
        try ensureDirectories()
        let urls = try fileManager.contentsOfDirectory(
            at: lifecycleDirectory,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        )
        var records: [NovelProjectLifecycleOperationRecord] = []
        for url in urls where url.pathExtension == "json" {
            guard let identity = lifecycleFileIdentity(for: url) else {
                quarantineInvalidLifecycleOperation(at: url)
                continue
            }
            do {
                let record = try readLifecycleOperation(at: url, expected: identity)
                if record.state == .pending {
                    records.append(record)
                }
            } catch {
                // Keep the parseable filename as durable evidence. The blocked
                // project scan prevents any write from treating corruption as absence.
            }
        }
        return records.sorted {
            if $0.projectID != $1.projectID {
                return $0.projectID.description < $1.projectID.description
            }
            return $0.operationID.description < $1.operationID.description
        }
    }

    func blockedLifecycleProjectIDs() async throws -> Set<NovelProjectID> {
        try ensureDirectories()
        let urls = try fileManager.contentsOfDirectory(
            at: lifecycleDirectory,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        )
        var blocked: Set<NovelProjectID> = []
        for url in urls where url.pathExtension == "json" {
            guard let identity = lifecycleFileIdentity(for: url) else {
                quarantineInvalidLifecycleOperation(at: url)
                continue
            }
            do {
                _ = try readLifecycleOperation(at: url, expected: identity)
            } catch {
                blocked.insert(identity.projectID)
            }
        }
        return blocked
    }

    func writeLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) async throws {
        try ensureDirectories()
        try record.validate()
        let url = lifecycleOperationURL(
            projectID: record.projectID,
            operationID: record.operationID
        )
        if fileManager.fileExists(atPath: url.path) {
            let current = try readLifecycleOperation(
                at: url,
                expected: LifecycleFileIdentity(
                    projectID: record.projectID,
                    operationID: record.operationID
                )
            )
            try current.validateTransition(to: record)
            if current == record { return }
        }
        let data = try makeEncoder().encode(record)
        try data.write(to: url, options: [.atomic])
        let installed = try readLifecycleOperation(
            at: url,
            expected: LifecycleFileIdentity(
                projectID: record.projectID,
                operationID: record.operationID
            )
        )
        guard installed == record else {
            throw NovelError.storageIndeterminate(record.projectID)
        }
    }

    func removeLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) async throws {
        try ensureDirectories()
        let url = lifecycleOperationURL(
            projectID: record.projectID,
            operationID: record.operationID
        )
        guard fileManager.fileExists(atPath: url.path) else { return }
        let current = try readLifecycleOperation(
            at: url,
            expected: LifecycleFileIdentity(
                projectID: record.projectID,
                operationID: record.operationID
            )
        )
        guard current == record, current.state == .pending else {
            throw NovelError.storageIndeterminate(record.projectID)
        }
        try fileManager.removeItem(at: url)
    }

    func restorePreviousProject(
        id: NovelProjectID,
        expectedDocumentSHA256: String
    ) async throws -> NovelLoadedProject {
        try ensureDirectories()
        guard !isDeletionTombstoned(id), !isReplacementMarked(id) else {
            throw NovelError.projectNotFound(id)
        }
        let restored = try readPreviousProjectDocument(projectID: id)
        guard try NovelProjectPackageCodec.encode(restored).projectSHA256 ==
            expectedDocumentSHA256 else {
            throw NovelError.storageIndeterminate(id)
        }
        try writeShardedProject(restored)
        let installed = try readInstalledProjectDocument(projectID: id)
        guard installed.project.revision == restored.project.revision else {
            throw NovelError.storageIndeterminate(id)
        }
        upsertIndexBestEffort(document: installed)
        return NovelLoadedProject(document: installed, access: .readWrite)
    }

    func listRecoverySidecars() async throws -> [NovelRecoverySidecarV1] {
        try ensureDirectories()
        let deleted = deletionTombstoneProjectIDs()
        let urls = try fileManager.contentsOfDirectory(
            at: recoveryDirectory,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        )
        var sidecars: [NovelRecoverySidecarV1] = []
        for url in urls where url.pathExtension == "json" {
            guard let identity = recoveryFileIdentity(for: url) else {
                quarantineInvalidRecovery(at: url)
                continue
            }
            if deleted.contains(identity.projectID) {
                try? fileManager.removeItem(at: url)
                continue
            }
            do {
                let sidecar = try readRecoverySidecar(at: url, expected: identity)
                sidecars.append(sidecar)
            } catch {
                quarantineInvalidRecovery(at: url)
            }
        }
        return sidecars.sorted {
            if $0.updatedAt != $1.updatedAt { return $0.updatedAt < $1.updatedAt }
            return $0.runID.description < $1.runID.description
        }
    }

    func writeRecoverySidecar(_ sidecar: NovelRecoverySidecarV1) async throws {
        try ensureDirectories()
        guard !isDeletionTombstoned(sidecar.projectID),
              !isReplacementMarked(sidecar.projectID) else {
            throw NovelError.projectNotFound(sidecar.projectID)
        }
        try NovelDocumentValidator.validateRecovery(sidecar)
        let url = recoveryURL(projectID: sidecar.projectID, runID: sidecar.runID)
        if fileManager.fileExists(atPath: url.path) {
            let current: NovelRecoverySidecarV1?
            do {
                current = try readRecoverySidecar(
                    at: url,
                    expected: RecoveryFileIdentity(
                        projectID: sidecar.projectID,
                        runID: sidecar.runID
                    )
                )
            } catch {
                quarantineInvalidRecovery(at: url)
                current = nil
            }
            if let current {
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
        }
        try failIfRequested(.beforeRecoveryWrite)
        let data = try makeEncoder().encode(sidecar)
        try data.write(to: url, options: [.atomic])
        try failIfRequested(.afterRecoveryWrite)
    }

    func removeRecoverySidecar(projectID: NovelProjectID, runID: NovelRunID) async throws {
        try ensureDirectories()
        try failIfRequested(.beforeRecoveryDelete)
        let url = recoveryURL(projectID: projectID, runID: runID)
        if fileManager.fileExists(atPath: url.path) {
            try fileManager.removeItem(at: url)
        }
    }

    func loadGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelGhostwriteBatchProgressRecord? {
        try ensureDirectories()
        let url = ghostwriteProgressURL(projectID: projectID, branchID: branchID)
        guard fileManager.fileExists(atPath: url.path) else { return nil }
        let data = try Data(contentsOf: url)
        let record = try makeDecoder().decode(NovelGhostwriteBatchProgressRecord.self, from: data)
        guard record.projectID == projectID, record.branchID == branchID else {
            return nil
        }
        return record
    }

    func saveGhostwriteBatchProgress(_ record: NovelGhostwriteBatchProgressRecord) async throws {
        try ensureDirectories()
        if isDeletionTombstoned(record.projectID) || isReplacementMarked(record.projectID) {
            throw NovelError.projectNotFound(record.projectID)
        }
        guard projectExistsOnDisk(record.projectID) else {
            throw NovelError.projectNotFound(record.projectID)
        }
        let url = ghostwriteProgressURL(
            projectID: record.projectID,
            branchID: record.branchID
        )
        let data = try makeEncoder().encode(record)
        try data.write(to: url, options: [.atomic])
    }

    func removeGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws {
        try ensureDirectories()
        let url = ghostwriteProgressURL(projectID: projectID, branchID: branchID)
        if fileManager.fileExists(atPath: url.path) {
            try fileManager.removeItem(at: url)
        }
    }

    private var projectDirectory: URL {
        rootDirectory.appendingPathComponent("projects", isDirectory: true)
    }

    private var recoveryDirectory: URL {
        rootDirectory.appendingPathComponent("recovery", isDirectory: true)
    }

    private var ghostwriteProgressDirectory: URL {
        rootDirectory.appendingPathComponent("ghostwrite-progress", isDirectory: true)
    }

    private var tombstoneDirectory: URL {
        rootDirectory.appendingPathComponent("tombstones", isDirectory: true)
    }

    private var replacementDirectory: URL {
        rootDirectory.appendingPathComponent("replacements", isDirectory: true)
    }

    private var lifecycleDirectory: URL {
        rootDirectory.appendingPathComponent("lifecycle", isDirectory: true)
    }

    private var indexURL: URL {
        rootDirectory.appendingPathComponent("index.json")
    }

    private var indexManifestURL: URL {
        rootDirectory.appendingPathComponent("index.manifest.json")
    }

    /// Legacy single-file primary (still readable; migrated away on next commit).
    private func primaryURL(for projectID: NovelProjectID) -> URL {
        projectDirectory.appendingPathComponent("\(projectID.description).json")
    }

    /// Legacy single-file previous snapshot.
    private func previousURL(for projectID: NovelProjectID) -> URL {
        projectDirectory.appendingPathComponent("\(projectID.description).previous.json")
    }

    /// Sharded package directory: `projects/{id}/layout.json` + content-addressed blobs.
    private func packageURL(for projectID: NovelProjectID) -> URL {
        NovelProjectShardedStorage.packageDirectory(
            projectDirectory: projectDirectory,
            projectID: projectID
        )
    }

    private func projectExistsOnDisk(_ projectID: NovelProjectID) -> Bool {
        fileManager.fileExists(atPath: primaryURL(for: projectID).path)
            || NovelProjectShardedStorage.isPackage(
                at: packageURL(for: projectID),
                fileManager: fileManager
            )
            || fileManager.fileExists(atPath: previousURL(for: projectID).path)
            || fileManager.fileExists(
                atPath: NovelProjectShardedStorage.previousLayoutURL(
                    in: packageURL(for: projectID)
                ).path
            )
    }

    private func tombstoneURL(for projectID: NovelProjectID) -> URL {
        tombstoneDirectory.appendingPathComponent("\(projectID.description).json")
    }

    private func replacementMarkerURL(for projectID: NovelProjectID) -> URL {
        replacementDirectory.appendingPathComponent("\(projectID.description).json")
    }

    private func recoveryURL(projectID: NovelProjectID, runID: NovelRunID) -> URL {
        recoveryDirectory.appendingPathComponent(
            "\(projectID.description)-\(runID.description).json"
        )
    }

    private func ghostwriteProgressURL(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> URL {
        ghostwriteProgressDirectory.appendingPathComponent(
            "\(projectID.description)-\(branchID.description).json"
        )
    }

    private func lifecycleOperationURL(
        projectID: NovelProjectID,
        operationID: NovelOperationID
    ) -> URL {
        lifecycleDirectory.appendingPathComponent(
            "\(projectID.description)-\(operationID.description).json"
        )
    }

    private func lifecycleFileIdentity(for url: URL) -> LifecycleFileIdentity? {
        let name = url.deletingPathExtension().lastPathComponent
        let bytes = Array(name.utf8)
        guard bytes.count == 73, bytes[36] == 45 else { return nil }
        let projectText = String(decoding: bytes[0..<36], as: UTF8.self)
        let operationText = String(decoding: bytes[37..<73], as: UTF8.self)
        guard let projectUUID = UUID(uuidString: projectText),
              let operationUUID = UUID(uuidString: operationText) else {
            return nil
        }
        let identity = LifecycleFileIdentity(
            projectID: NovelProjectID(projectUUID),
            operationID: NovelOperationID(operationUUID)
        )
        guard url.lastPathComponent == lifecycleOperationURL(
            projectID: identity.projectID,
            operationID: identity.operationID
        ).lastPathComponent else {
            return nil
        }
        return identity
    }

    private func readLifecycleOperation(
        at url: URL,
        expected identity: LifecycleFileIdentity
    ) throws -> NovelProjectLifecycleOperationRecord {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true,
              let size = values.fileSize,
              size <= 1_024 * 1_024 else {
            throw NovelError.repositoryFailure("Novel lifecycle operation file is invalid.")
        }
        let data = try Data(contentsOf: url, options: [.mappedIfSafe])
        let record = try makeDecoder().decode(NovelProjectLifecycleOperationRecord.self, from: data)
        try record.validate()
        guard record.projectID == identity.projectID,
              record.operationID == identity.operationID else {
            throw NovelError.repositoryFailure(
                "Novel lifecycle operation filename does not match its contents."
            )
        }
        return record
    }

    private func quarantineInvalidLifecycleOperation(at url: URL) {
        let destination = lifecycleDirectory.appendingPathComponent(
            ".invalid-lifecycle-\(UUID().uuidString.lowercased()).record"
        )
        do {
            try fileManager.moveItem(at: url, to: destination)
        } catch {
            try? fileManager.removeItem(at: url)
        }
    }

    private func recoveryFileIdentity(for url: URL) -> RecoveryFileIdentity? {
        let name = url.deletingPathExtension().lastPathComponent
        let bytes = Array(name.utf8)
        guard bytes.count == 73, bytes[36] == 45 else {
            return nil
        }
        let projectText = String(decoding: bytes[0..<36], as: UTF8.self)
        let runText = String(decoding: bytes[37..<73], as: UTF8.self)
        guard let projectUUID = UUID(uuidString: projectText),
              let runUUID = UUID(uuidString: runText) else {
            return nil
        }
        let identity = RecoveryFileIdentity(
            projectID: NovelProjectID(projectUUID),
            runID: NovelRunID(runUUID)
        )
        guard url.lastPathComponent == recoveryURL(
            projectID: identity.projectID,
            runID: identity.runID
        ).lastPathComponent else {
            return nil
        }
        return identity
    }

    private func readRecoverySidecar(
        at url: URL,
        expected identity: RecoveryFileIdentity
    ) throws -> NovelRecoverySidecarV1 {
        let data = try readData(at: url, projectID: nil)
        do {
            let sidecar = try makeDecoder().decode(NovelRecoverySidecarV1.self, from: data)
            try NovelDocumentValidator.validateRecovery(sidecar)
            guard sidecar.projectID == identity.projectID,
                  sidecar.runID == identity.runID else {
                throw NovelError.invalidRecovery(
                    "Recovery filename does not match its contents."
                )
            }
            return sidecar
        } catch let error as NovelError {
            throw error
        } catch {
            throw NovelError.invalidRecovery("\(url.lastPathComponent): \(error)")
        }
    }

    private func quarantineInvalidRecovery(at url: URL) {
        let destination = recoveryDirectory.appendingPathComponent(
            ".invalid-recovery-\(UUID().uuidString.lowercased()).sidecar"
        )
        do {
            try fileManager.moveItem(at: url, to: destination)
        } catch {
            try? fileManager.removeItem(at: url)
        }
    }

    private func ensureDirectories() throws {
        do {
            try fileManager.createDirectory(
                at: projectDirectory,
                withIntermediateDirectories: true
            )
            try fileManager.createDirectory(
                at: recoveryDirectory,
                withIntermediateDirectories: true
            )
            try fileManager.createDirectory(
                at: tombstoneDirectory,
                withIntermediateDirectories: true
            )
            try fileManager.createDirectory(
                at: replacementDirectory,
                withIntermediateDirectories: true
            )
            try fileManager.createDirectory(
                at: lifecycleDirectory,
                withIntermediateDirectories: true
            )
            try fileManager.createDirectory(
                at: ghostwriteProgressDirectory,
                withIntermediateDirectories: true
            )
        } catch {
            throw NovelError.storageUnavailable("Could not prepare novel storage: \(error)")
        }
    }

    private func isDeletionTombstoned(_ projectID: NovelProjectID) -> Bool {
        fileManager.fileExists(atPath: tombstoneURL(for: projectID).path)
    }

    private func isReplacementMarked(_ projectID: NovelProjectID) -> Bool {
        fileManager.fileExists(atPath: replacementMarkerURL(for: projectID).path)
    }

    private func writeLifecycleMarker(
        _ marker: ProjectLifecycleMarkerV1,
        to url: URL
    ) throws {
        // .atomic 写失败本身会抛错；写后读回只防硬件故障，去掉这次读回校验。
        let data = try makeEncoder().encode(marker)
        try data.write(to: url, options: [.atomic])
    }

    private func deletionTombstoneProjectIDs() -> Set<NovelProjectID> {
        guard let urls = try? fileManager.contentsOfDirectory(
            at: tombstoneDirectory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }
        return Set(urls.compactMap { url in
            guard url.pathExtension == "json",
                  let uuid = UUID(uuidString: url.deletingPathExtension().lastPathComponent) else {
                return nil
            }
            return NovelProjectID(uuid)
        })
    }

    private func finishDeletionTombstonesBestEffort() {
        for projectID in deletionTombstoneProjectIDs() {
            finishDeletionBestEffort(projectID: projectID)
        }
    }

    private func finishDeletionBestEffort(projectID: NovelProjectID) {
        do {
            try cleanupProjectFiles(projectID: projectID, includingPrimary: true)
        } catch {
            // The durable tombstone keeps every read path fail closed until cleanup retries.
        }
    }

    private func finishReplacementBestEffort(projectID: NovelProjectID) {
        guard isReplacementMarked(projectID) else { return }
        do {
            try cleanupProjectFiles(projectID: projectID, includingPrimary: false)
            try fileManager.removeItem(at: replacementMarkerURL(for: projectID))
        } catch {
            // A valid new primary remains readable while the marker blocks previous fallback.
        }
    }

    private func cleanupProjectFiles(
        projectID: NovelProjectID,
        includingPrimary: Bool
    ) throws {
        var urls = [previousURL(for: projectID)]
        if includingPrimary {
            urls.append(primaryURL(for: projectID))
            urls.append(packageURL(for: projectID))
            urls.append(replacementMarkerURL(for: projectID))
            sectionCaches[projectID] = nil
        } else {
            // Replacement cleanup keeps the new primary package; drop legacy previous monofile only.
        }
        let recoveryURLs = try fileManager.contentsOfDirectory(
            at: recoveryDirectory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ).filter {
            $0.lastPathComponent.hasPrefix("\(projectID.description)-")
        }
        urls.append(contentsOf: recoveryURLs)
        for url in urls where fileManager.fileExists(atPath: url.path) {
            try fileManager.removeItem(at: url)
        }
    }

    private func writeShardedProject(_ document: NovelProjectDocumentV1) throws {
        try failIfRequested(.afterTempWrite)
        let cache = sectionCaches[document.project.id]
        let nextCache = try NovelProjectShardedStorage.writePackage(
            document: document,
            packageDirectory: packageURL(for: document.project.id),
            encoder: makeEncoder(),
            fileManager: fileManager,
            cache: cache
        )
        try failIfRequested(.afterTempValidation)
        sectionCaches[document.project.id] = nextCache
        // Drop legacy monofiles after a successful sharded write so inventory and
        // loads prefer the incremental package.
        let mono = primaryURL(for: document.project.id)
        if fileManager.fileExists(atPath: mono.path) {
            try? fileManager.removeItem(at: mono)
        }
        let monoPrevious = previousURL(for: document.project.id)
        if fileManager.fileExists(atPath: monoPrevious.path) {
            try? fileManager.removeItem(at: monoPrevious)
        }
        let checkout = packageURL(for: document.project.id)
            .appendingPathComponent("checkout", isDirectory: true)
        if NovelProjectShardedStorage.checkoutSidecarNeedsRefresh(
            previous: cache,
            next: nextCache
        ) {
            try? NovelWorkspaceBackup.write(document, to: checkout)
        }
    }

    private func readInstalledProjectDocument(projectID: NovelProjectID) throws -> NovelProjectDocumentV1 {
        let package = packageURL(for: projectID)
        let hasCurrentLayout = fileManager.fileExists(
            atPath: NovelProjectShardedStorage.layoutURL(in: package).path
        )
        let hasPreviousLayout = fileManager.fileExists(
            atPath: NovelProjectShardedStorage.previousLayoutURL(in: package).path
        )
        if hasCurrentLayout {
            do {
                let loaded = try NovelProjectShardedStorage.loadDocument(
                    packageDirectory: package,
                    projectID: projectID,
                    decoder: makeDecoder(),
                    fileManager: fileManager
                )
                sectionCaches[projectID] = loaded.cache
                return try finalizeLoadedDocument(loaded.document, projectID: projectID)
            } catch {
                // Migration left a monofile when package delete failed, or layout is
                // unreadable: prefer a still-valid monofile over falling to previous.
                let mono = primaryURL(for: projectID)
                if fileManager.fileExists(atPath: mono.path),
                   let monofile = try? readProjectDocument(at: mono, projectID: projectID) {
                    return monofile
                }
                throw error
            }
        }
        if hasPreviousLayout {
            // Previous-only package: surface as load via previous for degraded path.
            throw NovelError.corruptedProject(
                projectID: projectID,
                details: "Current package layout is missing."
            )
        }
        let mono = primaryURL(for: projectID)
        if fileManager.fileExists(atPath: mono.path) {
            return try readProjectDocument(at: mono, projectID: projectID)
        }
        let checkout = package.appendingPathComponent("checkout", isDirectory: true)
        if fileManager.fileExists(atPath: checkout.appendingPathComponent("manifest.yaml").path) {
            let files = try NovelWorkspaceFolderDocument.files(
                fromDirectory: checkout,
                fileManager: fileManager
            )
            let imported = try NovelWorkspaceImporter.makeDocument(from: files)
            let remapped = try NovelProjectIdentityRemapper.remap(imported, to: projectID)
            return try finalizeLoadedDocument(remapped, projectID: projectID)
        }
        throw NovelError.projectNotFound(projectID)
    }

    private func readPreviousProjectDocument(projectID: NovelProjectID) throws -> NovelProjectDocumentV1 {
        let package = packageURL(for: projectID)
        if fileManager.fileExists(
            atPath: NovelProjectShardedStorage.previousLayoutURL(in: package).path
        ) {
            let loaded = try NovelProjectShardedStorage.loadDocument(
                packageDirectory: package,
                projectID: projectID,
                decoder: makeDecoder(),
                fileManager: fileManager,
                layoutFileName: NovelProjectShardedStorage.previousLayoutFileName
            )
            return try finalizeLoadedDocument(loaded.document, projectID: projectID)
        }
        let monoPrevious = previousURL(for: projectID)
        return try readProjectDocument(at: monoPrevious, projectID: projectID)
    }

    private func finalizeLoadedDocument(
        _ document: NovelProjectDocumentV1,
        projectID: NovelProjectID
    ) throws -> NovelProjectDocumentV1 {
        let generationNormalized = NovelGenerationReducer
            .normalizingLegacyInterruptedProseCandidates(document)
        let normalized = NovelBranchSemantics.normalizingDecodedSyncStatus(generationNormalized)
        guard normalized.project.id == projectID else {
            throw NovelError.corruptedProject(
                projectID: projectID,
                details: "Document project ID does not match its filename."
            )
        }
        try NovelDocumentValidator.validate(normalized)
        return normalized
    }

    private func readProjectDocument(
        at url: URL,
        projectID: NovelProjectID
    ) throws -> NovelProjectDocumentV1 {
        let data = try readData(at: url, projectID: projectID)
        return try decodeProject(data, projectID: projectID)
    }

    private func decodeProject(
        _ data: Data,
        projectID: NovelProjectID,
        normalizesLegacySyncStatus: Bool = true
    ) throws -> NovelProjectDocumentV1 {
        do {
            let header = try makeDecoder().decode(SchemaHeader.self, from: data)
            guard header.schemaVersion == NovelProjectDocumentV1.currentSchemaVersion else {
                throw NovelError.unsupportedSchema(header.schemaVersion)
            }
            let decoded = try makeDecoder().decode(NovelProjectDocumentV1.self, from: data)
            let generationNormalized = NovelGenerationReducer
                .normalizingLegacyInterruptedProseCandidates(decoded)
            let document = normalizesLegacySyncStatus
                ? NovelBranchSemantics.normalizingDecodedSyncStatus(generationNormalized)
                : generationNormalized
            guard document.project.id == projectID else {
                throw NovelError.corruptedProject(
                    projectID: projectID,
                    details: "Document project ID does not match its filename."
                )
            }
            try NovelDocumentValidator.validate(document)
            return document
        } catch let error as NovelError {
            throw error
        } catch {
            throw NovelError.corruptedProject(
                projectID: projectID,
                details: "Decode failed: \(error)"
            )
        }
    }

    private func readData(at url: URL, projectID: NovelProjectID?) throws -> Data {
        guard fileManager.fileExists(atPath: url.path) else {
            throw FileReadFailure.missing
        }
        do {
            let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            guard values.isRegularFile == true else {
                throw NovelError.storageUnavailable("Storage path is not a regular file: \(url.path)")
            }
            if let size = values.fileSize, size > Self.maximumProjectBytes {
                if let projectID {
                    throw NovelError.corruptedProject(
                        projectID: projectID,
                        details: "Project exceeds the 100 MB storage limit."
                    )
                }
                throw NovelError.invalidRecovery("Recovery payload exceeds the 100 MB limit.")
            }
            return try Data(contentsOf: url, options: [.mappedIfSafe])
        } catch let error as NovelError {
            throw error
        } catch {
            throw NovelError.storageUnavailable("Could not read \(url.lastPathComponent): \(error)")
        }
    }

    private func readIndex() throws -> IndexV1 {
        let data = try readData(at: indexURL, projectID: nil)
        let index = try makeDecoder().decode(IndexV1.self, from: data)
        guard index.schemaVersion == 1 else {
            throw NovelError.repositoryFailure("Unsupported novel index schema.")
        }
        guard Set(index.projects.map(\.id)).count == index.projects.count else {
            throw NovelError.repositoryFailure("Novel index repeats a project ID.")
        }
        return index
    }

    /// Returns cached inventory summaries when the index + file signatures still match.
    private func loadCachedProjectSummariesIfFresh() throws -> [NovelProjectSummary]? {
        guard fileManager.fileExists(atPath: indexURL.path),
              fileManager.fileExists(atPath: indexManifestURL.path) else { return nil }

        let diskInventory = try diskProjectInventory()
        let index = try readIndex()
        let manifest = try readIndexManifest()
        let deleted = deletionTombstoneProjectIDs()
        let cachedProjects = index.projects.filter { !deleted.contains($0.id) }
        let cachedIDs = Set(cachedProjects.map(\.id))
        guard cachedIDs == diskInventory.projectIDs else { return nil }

        let expectedEntries = Set(manifest.entries)
        let actualEntries = Set(diskInventory.entries)
        guard expectedEntries == actualEntries else { return nil }
        return cachedProjects.sorted(by: NovelProjectSummary.listOrder)
    }

    private struct DiskProjectInventory {
        let projectIDs: Set<NovelProjectID>
        let entries: [IndexManifestV1.Entry]
    }

    private func diskProjectInventory() throws -> DiskProjectInventory {
        let urls = try fileManager.contentsOfDirectory(
            at: projectDirectory,
            includingPropertiesForKeys: [
                .isRegularFileKey,
                .isDirectoryKey,
                .contentModificationDateKey,
                .fileSizeKey,
            ],
            options: [.skipsHiddenFiles]
        )
        let deletedProjectIDs = deletionTombstoneProjectIDs()
        var projectIDs: Set<NovelProjectID> = []
        for url in urls {
            let name = url.lastPathComponent
            let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .isRegularFileKey])
            if values?.isDirectory == true {
                guard let uuid = UUID(uuidString: name),
                      NovelProjectShardedStorage.isPackage(at: url, fileManager: fileManager) else {
                    continue
                }
                let projectID = NovelProjectID(uuid)
                guard !deletedProjectIDs.contains(projectID) else { continue }
                projectIDs.insert(projectID)
                continue
            }
            let rawID: String
            if name.hasSuffix(".previous.json") {
                rawID = String(name.dropLast(".previous.json".count))
            } else {
                guard name.hasSuffix(".json"), !name.contains(".corrupt-") else {
                    continue
                }
                rawID = String(name.dropLast(".json".count))
            }
            guard let uuid = UUID(uuidString: rawID) else { continue }
            let projectID = NovelProjectID(uuid)
            guard !deletedProjectIDs.contains(projectID) else { continue }
            projectIDs.insert(projectID)
        }

        let entries = projectIDs.sorted(by: { $0.description < $1.description }).map { projectID in
            fileSignatureEntry(for: projectID)
        }
        return DiskProjectInventory(projectIDs: projectIDs, entries: entries)
    }

    private func fileSignatureEntry(for projectID: NovelProjectID) -> IndexManifestV1.Entry {
        let package = packageURL(for: projectID)
        if NovelProjectShardedStorage.isPackage(at: package, fileManager: fileManager) {
            let primary = fileSignature(
                at: NovelProjectShardedStorage.layoutURL(in: package)
            )
            let previous = fileSignature(
                at: NovelProjectShardedStorage.previousLayoutURL(in: package)
            )
            return IndexManifestV1.Entry(
                projectID: projectID,
                primaryByteCount: primary.byteCount,
                primaryModifiedAt: primary.modifiedAt,
                previousByteCount: previous.byteCount,
                previousModifiedAt: previous.modifiedAt
            )
        }
        let primary = fileSignature(at: primaryURL(for: projectID))
        let previous = fileSignature(at: previousURL(for: projectID))
        return IndexManifestV1.Entry(
            projectID: projectID,
            primaryByteCount: primary.byteCount,
            primaryModifiedAt: primary.modifiedAt,
            previousByteCount: previous.byteCount,
            previousModifiedAt: previous.modifiedAt
        )
    }

    private func fileSignature(at url: URL) -> (byteCount: Int?, modifiedAt: TimeInterval?) {
        guard fileManager.fileExists(atPath: url.path),
              let values = try? url.resourceValues(forKeys: [
                  .fileSizeKey,
                  .contentModificationDateKey,
                  .isRegularFileKey,
              ]),
              values.isRegularFile == true else {
            return (nil, nil)
        }
        return (
            values.fileSize,
            values.contentModificationDate?.timeIntervalSince1970
        )
    }

    private func scanProjectSummaries() throws -> [NovelProjectSummary] {
        let inventory = try diskProjectInventory()
        var summaries: [NovelProjectSummary] = []
        for projectID in inventory.projectIDs.sorted(by: { $0.description < $1.description }) {
            do {
                let loaded = try loadProjectSynchronously(id: projectID)
                summaries.append(NovelProjectSummary(
                    document: loaded.document,
                    isDegraded: loaded.access != .readWrite
                ))
            } catch {
                summaries.append(NovelProjectSummary(
                    unavailableProjectID: projectID,
                    error: error
                ))
            }
        }
        return summaries.sorted(by: NovelProjectSummary.listOrder)
    }

    private func loadProjectSynchronously(id: NovelProjectID) throws -> NovelLoadedProject {
        let hasPackage = NovelProjectShardedStorage.isPackage(
            at: packageURL(for: id),
            fileManager: fileManager
        )
        let hasMonofile = fileManager.fileExists(atPath: primaryURL(for: id).path)
        if isReplacementMarked(id), !hasPackage, !hasMonofile {
            throw NovelError.storageIndeterminate(id)
        }
        do {
            let document = try readInstalledProjectDocument(projectID: id)
            finishReplacementBestEffort(projectID: id)
            return NovelLoadedProject(document: document, access: .readWrite)
        } catch let error as NovelError {
            if isReplacementMarked(id) {
                throw NovelError.storageIndeterminate(id)
            }
            switch error {
            case .unsupportedSchema, .storageUnavailable:
                throw error
            default:
                return try loadPreviousProject(id: id, primaryFailure: String(describing: error))
            }
        } catch {
            if isReplacementMarked(id) {
                throw NovelError.storageIndeterminate(id)
            }
            return try loadPreviousProject(id: id, primaryFailure: String(describing: error))
        }
    }

    private func writeIndexBestEffort(_ summaries: [NovelProjectSummary]) {
        do {
            try failIfRequested(.beforeIndexWrite)
            let sorted = summaries.sorted(by: NovelProjectSummary.listOrder)
            let index = IndexV1(schemaVersion: 1, projects: sorted)
            let manifest = IndexManifestV1(
                schemaVersion: 1,
                entries: sorted.map { fileSignatureEntry(for: $0.id) }
            )
            try makeEncoder().encode(index).write(to: indexURL, options: [.atomic])
            try makeEncoder().encode(manifest).write(to: indexManifestURL, options: [.atomic])
        } catch {
            try? fileManager.removeItem(at: indexURL)
            try? fileManager.removeItem(at: indexManifestURL)
        }
    }

    private func readIndexManifest() throws -> IndexManifestV1 {
        let data = try readData(at: indexManifestURL, projectID: nil)
        let manifest = try makeDecoder().decode(IndexManifestV1.self, from: data)
        guard manifest.schemaVersion == 1 else {
            throw NovelError.repositoryFailure("Unsupported novel index manifest schema.")
        }
        return manifest
    }

    private func refreshIndexBestEffort() {
        do {
            let summaries = try scanProjectSummaries()
            writeIndexBestEffort(summaries)
        } catch {
            try? fileManager.removeItem(at: indexURL)
            try? fileManager.removeItem(at: indexManifestURL)
        }
    }

    /// Patch one project into the lightweight index without reloading every package.
    /// Falls back to a full scan only when no usable index exists yet.
    private func upsertIndexBestEffort(document: NovelProjectDocumentV1) {
        do {
            let summary = NovelProjectSummary(document: document, isDegraded: false)
            let deleted = deletionTombstoneProjectIDs()
            var projects: [NovelProjectSummary]
            if let index = try? readIndex() {
                projects = index.projects.filter { !deleted.contains($0.id) && $0.id != summary.id }
                projects.append(summary)
            } else {
                projects = try scanProjectSummaries()
                if let idx = projects.firstIndex(where: { $0.id == summary.id }) {
                    projects[idx] = summary
                } else if !deleted.contains(summary.id) {
                    projects.append(summary)
                }
            }
            writeIndexBestEffort(projects)
        } catch {
            try? fileManager.removeItem(at: indexURL)
            try? fileManager.removeItem(at: indexManifestURL)
        }
    }

    private func invalidateIndex() throws {
        if fileManager.fileExists(atPath: indexManifestURL.path) {
            try? fileManager.removeItem(at: indexManifestURL)
        }
        guard fileManager.fileExists(atPath: indexURL.path) else { return }
        do {
            try fileManager.removeItem(at: indexURL)
        } catch {
            throw NovelError.repositoryFailure("Could not invalidate the novel index: \(error)")
        }
    }

    private func failIfRequested(_ stage: NovelRepositoryFailureStage) throws {
        if failingStages.contains(stage) {
            throw NovelError.repositoryFailure("Injected repository failure at \(stage.rawValue).")
        }
    }

    private func loadPreviousProject(
        id: NovelProjectID,
        primaryFailure: String
    ) throws -> NovelLoadedProject {
        do {
            let previous = try readPreviousProjectDocument(projectID: id)
            guard previous.project.id == id else {
                throw NovelError.corruptedProject(
                    projectID: id,
                    details: "Previous document belongs to another project."
                )
            }
            return NovelLoadedProject(
                document: previous,
                access: .degradedPrevious(primaryFailure: primaryFailure)
            )
        } catch let error as NovelError {
            switch error {
            case .unsupportedSchema, .storageUnavailable:
                throw error
            default:
                throw NovelError.corruptedProject(
                    projectID: id,
                    details: "Primary: \(primaryFailure); previous: \(error)"
                )
            }
        } catch {
            throw NovelError.corruptedProject(
                projectID: id,
                details: "Primary: \(primaryFailure); previous: \(error)"
            )
        }
    }

    private func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }

    private func makeDecoder() -> JSONDecoder {
        JSONDecoder()
    }
}

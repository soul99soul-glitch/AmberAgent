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
        if isReplacementMarked(id),
           !fileManager.fileExists(atPath: primaryURL(for: id).path) {
            throw NovelError.storageIndeterminate(id)
        }
        guard fileManager.fileExists(atPath: primaryURL(for: id).path) ||
                fileManager.fileExists(atPath: previousURL(for: id).path) else {
            throw NovelError.projectNotFound(id)
        }
        do {
            let document = try readProjectDocument(at: primaryURL(for: id), projectID: id)
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
        let destination = primaryURL(for: projectID)
        let isReinstallingDeletedProject = isDeletionTombstoned(projectID)
        if isReinstallingDeletedProject {
            try cleanupProjectFiles(projectID: projectID, includingPrimary: true)
        } else if isReplacementMarked(projectID) {
            throw NovelError.storageIndeterminate(projectID)
        }
        guard !fileManager.fileExists(atPath: destination.path) else {
            throw NovelError.projectAlreadyExists(projectID)
        }
        let staged = try stage(document)
        defer { try? fileManager.removeItem(at: staged.url) }
        try invalidateIndex()
        try failIfRequested(.beforePrimaryInstall)
        do {
            try fileManager.moveItem(at: staged.url, to: destination)
        } catch {
            if let installed = try? readProjectDocument(at: destination, projectID: projectID),
               installed == document {
                // The install completed despite Foundation reporting an error.
            } else {
                throw NovelError.repositoryFailure("Could not create novel project: \(error)")
            }
        }
        let installed = try readProjectDocument(at: destination, projectID: projectID)
        guard installed == document else {
            throw NovelError.storageIndeterminate(projectID)
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
        refreshIndexBestEffort()
        return NovelLoadedProject(document: installed, access: .readWrite)
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
        let staged = try stage(document)
        defer { try? fileManager.removeItem(at: staged.url) }
        try invalidateIndex()
        try failIfRequested(.beforePrimaryInstall)
        try authorization?.claim()

        let destination = primaryURL(for: projectID)
        do {
            _ = try fileManager.replaceItemAt(
                destination,
                withItemAt: staged.url,
                backupItemName: previousURL(for: projectID).lastPathComponent,
                options: [.withoutDeletingBackupItem]
            )
        } catch {
            if let installed = try? readProjectDocument(at: destination, projectID: projectID),
               installed == document {
                // Treat an installed and validated next document as committed.
            } else if let stillCurrent = try? readProjectDocument(at: destination, projectID: projectID),
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
        refreshIndexBestEffort()
        let committed = try readProjectDocument(at: destination, projectID: projectID)
        guard committed == document else {
            throw NovelError.storageIndeterminate(projectID)
        }
        return NovelLoadedProject(document: committed, access: .readWrite)
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

        let staged = try stage(document)
        defer { try? fileManager.removeItem(at: staged.url) }
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
        try invalidateIndex()
        try failIfRequested(.beforePrimaryInstall)

        let destination = primaryURL(for: projectID)
        do {
            if fileManager.fileExists(atPath: destination.path) {
                _ = try fileManager.replaceItemAt(
                    destination,
                    withItemAt: staged.url,
                    backupItemName: nil,
                    options: []
                )
            } else {
                try fileManager.moveItem(at: staged.url, to: destination)
            }
        } catch {
            if let installed = try? readProjectDocument(at: destination, projectID: projectID),
               installed == document {
                // Continue cleanup after an indeterminate Foundation result.
            } else if let unchanged = try? readProjectDocument(
                at: destination,
                projectID: projectID
            ), unchanged == loaded.document {
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
        let installed = try readProjectDocument(at: destination, projectID: projectID)
        guard installed == document else {
            throw NovelError.storageIndeterminate(projectID)
        }
        do {
            try failIfRequested(.afterReplacementInstallBeforeCleanup)
            try cleanupProjectFiles(projectID: projectID, includingPrimary: false)
            try fileManager.removeItem(at: replacementMarkerURL(for: projectID))
        } catch {
            throw NovelError.storageIndeterminate(projectID)
        }
        refreshIndexBestEffort()
        return NovelLoadedProject(document: installed, access: .readWrite)
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
        let previous = previousURL(for: id)
        let restored = try readProjectDocument(at: previous, projectID: id)
        guard try NovelProjectPackageCodec.encode(restored).projectSHA256 ==
            expectedDocumentSHA256 else {
            throw NovelError.storageIndeterminate(id)
        }
        let bytes = try readData(at: previous, projectID: id)
        let staged = projectDirectory.appendingPathComponent(".restore-\(UUID().uuidString).tmp")
        try bytes.write(to: staged, options: [])
        defer { try? fileManager.removeItem(at: staged) }
        _ = try readProjectDocument(at: staged, projectID: id)
        try invalidateIndex()

        let destination = primaryURL(for: id)
        if fileManager.fileExists(atPath: destination.path) {
            let corruptName = "\(id.description).corrupt-\(UUID().uuidString.lowercased()).json"
            _ = try fileManager.replaceItemAt(
                destination,
                withItemAt: staged,
                backupItemName: corruptName,
                options: [.withoutDeletingBackupItem]
            )
        } else {
            try fileManager.moveItem(at: staged, to: destination)
        }
        let installed = try readProjectDocument(at: destination, projectID: id)
        guard installed == restored else {
            throw NovelError.storageIndeterminate(id)
        }
        refreshIndexBestEffort()
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

    private var projectDirectory: URL {
        rootDirectory.appendingPathComponent("projects", isDirectory: true)
    }

    private var recoveryDirectory: URL {
        rootDirectory.appendingPathComponent("recovery", isDirectory: true)
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

    private func primaryURL(for projectID: NovelProjectID) -> URL {
        projectDirectory.appendingPathComponent("\(projectID.description).json")
    }

    private func previousURL(for projectID: NovelProjectID) -> URL {
        projectDirectory.appendingPathComponent("\(projectID.description).previous.json")
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
            urls.append(replacementMarkerURL(for: projectID))
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

    private func stage(
        _ document: NovelProjectDocumentV1
    ) throws -> (url: URL, data: Data) {
        let data = try makeEncoder().encode(document)
        guard data.count <= Self.maximumProjectBytes else {
            throw NovelError.invalidDocument(["Project exceeds the 100 MB storage limit."])
        }
        let url = projectDirectory.appendingPathComponent(".\(UUID().uuidString).tmp")
        try data.write(to: url, options: [])
        try failIfRequested(.afterTempWrite)
        let staged = try readData(at: url, projectID: document.project.id)
        let decoded = try decodeProject(
            staged,
            projectID: document.project.id,
            normalizesLegacySyncStatus: false
        )
        guard decoded == document else {
            throw NovelError.repositoryFailure("Staged novel document changed during verification.")
        }
        try failIfRequested(.afterTempValidation)
        return (url, data)
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
                .contentModificationDateKey,
                .fileSizeKey,
            ],
            options: [.skipsHiddenFiles]
        )
        let deletedProjectIDs = deletionTombstoneProjectIDs()
        var projectIDs: Set<NovelProjectID> = []
        for url in urls {
            let name = url.lastPathComponent
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
        if isReplacementMarked(id),
           !fileManager.fileExists(atPath: primaryURL(for: id).path) {
            throw NovelError.storageIndeterminate(id)
        }
        do {
            let document = try readProjectDocument(at: primaryURL(for: id), projectID: id)
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
            let previous = try readProjectDocument(at: previousURL(for: id), projectID: id)
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

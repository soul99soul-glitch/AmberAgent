import CryptoKit
import Foundation
import Observation

struct IOSMiniAppConversationReference: Codable, Equatable, Hashable {
    let appId: String
    let version: Int
    let htmlHash: String

    init(appId: String, version: Int, htmlHash: String) {
        self.appId = appId
        self.version = version
        self.htmlHash = htmlHash
    }

    init(record: IOSMiniAppRecord) {
        self.init(appId: record.id, version: record.version, htmlHash: record.htmlHash)
    }
}

fileprivate struct IOSMiniAppRecordFingerprint: Codable, Equatable {
    let id: String
    let title: String
    let description: String
    let sourceConversationId: String?
    let sourceMessageId: String?
    let iconEmoji: String?
    let category: String
    let permissions: [String]
    let pinned: Bool
    let runCount: Int
    let boardSummary: String?
    let version: Int
    let htmlHash: String
    let createdAt: Int64
    let updatedAt: Int64
    let lastRunAt: Int64?

    init(_ record: IOSMiniAppRecord) {
        id = record.id
        title = record.title
        description = record.description
        sourceConversationId = record.sourceConversationId
        sourceMessageId = record.sourceMessageId
        iconEmoji = record.iconEmoji
        category = record.category
        permissions = record.permissions
        pinned = record.pinned
        runCount = record.runCount
        boardSummary = record.boardSummary
        version = record.version
        htmlHash = record.htmlHash
        createdAt = record.createdAt
        updatedAt = record.updatedAt
        lastRunAt = record.lastRunAt
    }
}

fileprivate struct IOSMiniAppMutationUndo: Codable, Equatable {
    let previousApp: IOSMiniAppRecord?
    let addedVersionNumber: Int
    let prunedVersions: [IOSMiniAppVersionRecord]
}

fileprivate struct IOSMiniAppPendingConversationMutation: Codable, Equatable {
    let id: String
    let reference: IOSMiniAppConversationReference
    let committedApp: IOSMiniAppRecordFingerprint
    let undo: IOSMiniAppMutationUndo
}

fileprivate struct IOSMiniAppRevisionApplication {
    let record: IOSMiniAppRecord
    let prunedVersions: [IOSMiniAppVersionRecord]
}

fileprivate struct IOSMiniAppStoreState: Codable, Equatable {
    var apps: [IOSMiniAppRecord] = []
    var versions: [IOSMiniAppVersionRecord] = []
    var grants: [IOSMiniAppGrantRecord] = []
    var auditLogs: [IOSMiniAppAuditRecord] = []
    var sharedData: [IOSMiniAppSharedDataRecord] = []
    // Optional keeps existing miniapps.json files decodable without a schema migration.
    var pendingConversationMutations: [IOSMiniAppPendingConversationMutation]?
}

@MainActor
@Observable
final class IOSMiniAppRepository {
    struct Mutation {
        fileprivate let id: String
        let record: IOSMiniAppRecord
        fileprivate let committedApp: IOSMiniAppRecordFingerprint
        fileprivate let undo: IOSMiniAppMutationUndo
    }

    static let shared = IOSMiniAppRepository()

    private(set) var apps: [IOSMiniAppRecord] = []
    private(set) var storageError: String?
    private(set) var revision: Int = 0

    @ObservationIgnored private var state = IOSMiniAppStoreState()
    @ObservationIgnored private var committedState = IOSMiniAppStoreState()
    @ObservationIgnored private let directory: URL
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private let fileManager: FileManager
    @ObservationIgnored private let encoder: JSONEncoder
    @ObservationIgnored private let decoder: JSONDecoder
    @ObservationIgnored private var writeBlockedByDecodeError = false
    @ObservationIgnored private let seedOnMissingStore: Bool

    init(
        baseDirectory: URL? = nil,
        seedOnMissingStore: Bool = false,
        fileManager: FileManager = .default
    ) {
        self.fileManager = fileManager
        self.seedOnMissingStore = seedOnMissingStore
        let root = baseDirectory ?? fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        self.directory = root.appendingPathComponent("miniapps", isDirectory: true)
        self.fileURL = directory.appendingPathComponent("miniapps.json", isDirectory: false)
        self.encoder = JSONEncoder()
        self.encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        self.decoder = JSONDecoder()
        reload()
    }

    func reload() {
        writeBlockedByDecodeError = false
        storageError = nil

        guard fileManager.fileExists(atPath: fileURL.path) else {
            state = IOSMiniAppStoreState()
            if seedOnMissingStore {
                seedSample()
                do {
                    try persist()
                } catch {
                    storageError = error.localizedDescription
                }
            }
            publish()
            return
        }

        do {
            let data = try Data(contentsOf: fileURL)
            let decodedState = try decoder.decode(IOSMiniAppStoreState.self, from: data)
            state = decodedState
            if !seedOnMissingStore, removeLegacySeedIfPresent() {
                do {
                    try persist()
                } catch {
                    state = decodedState
                    storageError = error.localizedDescription
                }
            }
        } catch {
            state = IOSMiniAppStoreState()
            storageError = error.localizedDescription
            writeBlockedByDecodeError = true
        }
        publish()
    }

    func list() -> [IOSMiniAppRecord] {
        apps
    }

    func get(_ id: String) -> IOSMiniAppRecord? {
        state.apps.first { $0.id == id }
    }

    func versions(appId: String) -> [IOSMiniAppVersionRecord] {
        state.versions
            .filter { $0.appId == appId }
            .sorted { $0.versionNumber > $1.versionNumber }
    }

    func grants(appId: String) -> [IOSMiniAppGrantRecord] {
        state.grants
            .filter { $0.appId == appId }
            .sorted { $0.permission < $1.permission }
    }

    func auditLogs(appId: String, limit: Int = 100) -> [IOSMiniAppAuditRecord] {
        Array(state.auditLogs
            .filter { $0.appId == appId }
            .sorted { $0.createdAt > $1.createdAt }
            .prefix(limit))
    }

    func saveGenerated(
        _ output: IOSMiniAppGeneratedOutput,
        sourceConversationId: String? = nil,
        sourceMessageId: String? = nil,
        forcedId: String? = nil
    ) throws -> IOSMiniAppRecord {
        try ensureWritable()
        let record = try applyGenerated(
            output,
            sourceConversationId: sourceConversationId,
            sourceMessageId: sourceMessageId,
            forcedId: forcedId
        )
        try persistAndPublish()
        return record
    }

    func saveGeneratedMutation(
        _ output: IOSMiniAppGeneratedOutput,
        sourceConversationId: String? = nil,
        sourceMessageId: String? = nil
    ) throws -> Mutation {
        try ensureWritable()
        let appId = UUID().uuidString
        let record = try applyGenerated(
            output,
            sourceConversationId: sourceConversationId,
            sourceMessageId: sourceMessageId,
            forcedId: appId
        )
        let mutation = stageConversationMutation(
            record: record,
            undo: IOSMiniAppMutationUndo(
                previousApp: nil,
                addedVersionNumber: record.version,
                prunedVersions: []
            )
        )
        try persistAndPublish()
        return mutation
    }

    func saveRevision(
        appId: String,
        output: IOSMiniAppGeneratedOutput,
        expectedBaseVersion: Int? = nil,
        sourceMessageId: String? = nil,
        changeNote: String? = nil
    ) throws -> IOSMiniAppRecord? {
        try ensureWritable()
        guard let application = try applyRevision(
            appId: appId,
            output: output,
            expectedBaseVersion: expectedBaseVersion,
            sourceMessageId: sourceMessageId,
            changeNote: changeNote
        ) else { return nil }
        try persistAndPublish()
        return application.record
    }

    func saveRevisionMutation(
        appId: String,
        output: IOSMiniAppGeneratedOutput,
        expectedBaseVersion: Int? = nil,
        sourceMessageId: String? = nil,
        changeNote: String? = nil
    ) throws -> Mutation? {
        try ensureWritable()
        let previousApp = state.apps.first { $0.id == appId }
        guard let application = try applyRevision(
            appId: appId,
            output: output,
            expectedBaseVersion: expectedBaseVersion,
            sourceMessageId: sourceMessageId,
            changeNote: changeNote
        ) else { return nil }
        let mutation = stageConversationMutation(
            record: application.record,
            undo: IOSMiniAppMutationUndo(
                previousApp: previousApp,
                addedVersionNumber: application.record.version,
                prunedVersions: application.prunedVersions
            )
        )
        try persistAndPublish()
        return mutation
    }

    var hasPendingConversationMutations: Bool {
        !(state.pendingConversationMutations ?? []).isEmpty
    }

    @discardableResult
    func commit(_ mutation: Mutation) throws -> Bool {
        guard removePendingConversationMutation(id: mutation.id) else { return false }
        try persistAndPublish()
        return true
    }

    @discardableResult
    func rollback(_ mutation: Mutation) throws -> Bool {
        let canRollback = state.apps.first(where: { $0.id == mutation.record.id })
            .map(IOSMiniAppRecordFingerprint.init) == mutation.committedApp
        let removedPending = removePendingConversationMutation(id: mutation.id)
        guard canRollback || removedPending else { return false }
        if canRollback {
            applyUndo(mutation.undo, appId: mutation.record.id)
        }
        try persistAndPublish()
        return canRollback
    }

    func reconcilePendingConversationMutations(
        referenced: Set<IOSMiniAppConversationReference>
    ) throws {
        let pending = state.pendingConversationMutations ?? []
        guard !pending.isEmpty else { return }

        // Newest-first lets a chain of uncommitted revisions unwind back to
        // the last card that was actually persisted in a conversation.
        for transaction in pending.reversed() where !referenced.contains(transaction.reference) {
            if state.apps.first(where: { $0.id == transaction.reference.appId })
                .map(IOSMiniAppRecordFingerprint.init) == transaction.committedApp {
                applyUndo(transaction.undo, appId: transaction.reference.appId)
            }
        }
        state.pendingConversationMutations = nil
        try persistAndPublish()
    }

    func saveNewVersion(appId: String, htmlContent: String, changeNote: String? = nil) throws -> IOSMiniAppRecord? {
        try ensureWritable()
        try MiniAppHtmlValidator.validate(htmlContent)
        guard let index = state.apps.firstIndex(where: { $0.id == appId }) else { return nil }
        let now = nowMillis()
        let nextVersion = maxVersionNumber(appId: appId) + 1
        var updated = state.apps[index]
        updated.htmlContent = htmlContent
        updated.htmlHash = sha256(htmlContent)
        updated.version = nextVersion
        updated.updatedAt = now
        state.apps[index] = updated
        state.versions.append(versionRecord(for: updated, changeNote: changeNote?.truncated(to: 240) ?? "Saved version", createdAt: now))
        pruneVersions(appId: appId)
        try persistAndPublish()
        return updated
    }

    func restoreVersion(appId: String, versionNumber: Int) throws -> IOSMiniAppRecord? {
        guard let version = state.versions.first(where: { $0.appId == appId && $0.versionNumber == versionNumber }) else {
            return nil
        }
        return try saveNewVersion(appId: appId, htmlContent: version.htmlContent, changeNote: "Restored from v\(versionNumber)")
    }

    func rename(id: String, title: String, description: String) throws {
        try ensureWritable()
        guard let index = state.apps.firstIndex(where: { $0.id == id }) else {
            throw IOSMiniAppStoreError.notFound(id)
        }
        state.apps[index].title = title.trimmingCharacters(in: .whitespacesAndNewlines)
            .truncated(to: 40)
            .nilIfBlank ?? "未命名小应用"
        state.apps[index].description = description.trimmingCharacters(in: .whitespacesAndNewlines).truncated(to: 120)
        state.apps[index].updatedAt = nowMillis()
        try persistAndPublish()
    }

    func setPinned(id: String, pinned: Bool) throws {
        try ensureWritable()
        guard let index = state.apps.firstIndex(where: { $0.id == id }) else {
            throw IOSMiniAppStoreError.notFound(id)
        }
        state.apps[index].pinned = pinned
        state.apps[index].updatedAt = nowMillis()
        try persistAndPublish()
    }

    func delete(id: String) throws {
        try ensureWritable()
        state.apps.removeAll { $0.id == id }
        state.versions.removeAll { $0.appId == id }
        state.grants.removeAll { $0.appId == id }
        state.auditLogs.removeAll { $0.appId == id }
        state.sharedData.removeAll { $0.lastWriterId == id || $0.namespace == id || $0.namespace == storageNamespace(id) }
        try persistAndPublish()
    }

    func markRun(id: String) throws {
        try ensureWritable()
        guard let index = state.apps.firstIndex(where: { $0.id == id }) else {
            throw IOSMiniAppStoreError.notFound(id)
        }
        state.apps[index].runCount += 1
        state.apps[index].lastRunAt = nowMillis()
        state.apps[index].updatedAt = nowMillis()
        try persistAndPublish()
    }

    func updateBoardSummary(id: String, summary: String) throws {
        try ensureWritable()
        guard let index = state.apps.firstIndex(where: { $0.id == id }) else {
            throw IOSMiniAppStoreError.notFound(id)
        }
        state.apps[index].boardSummary = summary.trimmingCharacters(in: .whitespacesAndNewlines).truncated(to: 500)
        state.apps[index].updatedAt = nowMillis()
        try persistAndPublish()
    }

    func setGrant(appId: String, permission: String, decision: IOSMiniAppGrantDecision) throws {
        try ensureWritable()
        let normalized = IOSMiniAppPermission.normalized(permission)
        let now = nowMillis()
        let record = IOSMiniAppGrantRecord(appId: appId, permission: normalized, decision: decision, updatedAt: now)
        state.grants.removeAll { $0.appId == appId && $0.permission == normalized }
        state.grants.append(record)
        try persistAndPublish()
    }

    func grantDecision(appId: String, permission: String) -> IOSMiniAppGrantDecision? {
        let normalized = IOSMiniAppPermission.normalized(permission)
        return state.grants.first { $0.appId == appId && $0.permission == normalized }?.decision
    }

    func audit(appId: String, method: String, permission: String, summary: String, payload: String) throws {
        try ensureWritable()
        state.auditLogs.append(
            IOSMiniAppAuditRecord(
                id: UUID().uuidString,
                appId: appId,
                method: method,
                permission: IOSMiniAppPermission.normalized(permission),
                summary: summary.truncated(to: 180),
                payloadHash: sha256(payload),
                createdAt: nowMillis()
            )
        )
        if state.auditLogs.count > 1_000 {
            state.auditLogs = Array(state.auditLogs.sorted { $0.createdAt > $1.createdAt }.prefix(1_000))
        }
        try persistAndPublish()
    }

    func storageGet(appId: String, key: String) throws -> IOSMiniAppJSONValue? {
        try sharedGetUnchecked(namespace: storageNamespace(appId), key: key)
    }

    func storageSet(appId: String, key: String, value: IOSMiniAppJSONValue) throws {
        try sharedSetUnchecked(appId: appId, namespace: storageNamespace(appId), key: key, value: value)
    }

    func storageRemove(appId: String, key: String) throws {
        try sharedRemoveUnchecked(namespace: storageNamespace(appId), key: key)
    }

    func sharedGet(appId: String, namespace: String?, key: String) throws -> IOSMiniAppJSONValue? {
        let safeNamespace = try validateSharedNamespace(appId: appId, namespace: namespace)
        return try sharedGetUnchecked(namespace: safeNamespace, key: key)
    }

    func sharedSet(appId: String, namespace: String?, key: String, value: IOSMiniAppJSONValue) throws {
        let safeNamespace = try validateSharedNamespace(appId: appId, namespace: namespace)
        try sharedSetUnchecked(appId: appId, namespace: safeNamespace, key: key, value: value)
    }

    func sharedRemove(appId: String, namespace: String?, key: String) throws {
        let safeNamespace = try validateSharedNamespace(appId: appId, namespace: namespace)
        try sharedRemoveUnchecked(namespace: safeNamespace, key: key)
    }

    func sha256(_ input: String) -> String {
        let digest = SHA256.hash(data: Data(input.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func applyGenerated(
        _ output: IOSMiniAppGeneratedOutput,
        sourceConversationId: String?,
        sourceMessageId: String?,
        forcedId: String?
    ) throws -> IOSMiniAppRecord {
        let normalized = try validated(output)
        let now = nowMillis()
        let record = IOSMiniAppRecord(
            id: forcedId ?? UUID().uuidString,
            title: normalized.title,
            description: normalized.description,
            htmlContent: normalized.html,
            sourceConversationId: sourceConversationId,
            sourceMessageId: sourceMessageId,
            iconEmoji: normalized.icon,
            category: normalized.category,
            permissions: normalized.permissions,
            pinned: false,
            runCount: 0,
            boardSummary: nil,
            version: 1,
            htmlHash: sha256(normalized.html),
            createdAt: now,
            updatedAt: now,
            lastRunAt: nil
        )
        state.apps.removeAll { $0.id == record.id }
        state.apps.append(record)
        state.versions.removeAll { $0.appId == record.id }
        state.versions.append(versionRecord(for: record, changeNote: "Initial version", createdAt: now))
        return record
    }

    private func applyRevision(
        appId: String,
        output: IOSMiniAppGeneratedOutput,
        expectedBaseVersion: Int?,
        sourceMessageId: String?,
        changeNote: String?
    ) throws -> IOSMiniAppRevisionApplication? {
        let normalized = try validated(output)
        guard let index = state.apps.firstIndex(where: { $0.id == appId }) else { return nil }
        let current = state.apps[index]
        if let expectedBaseVersion, current.version != expectedBaseVersion {
            throw IOSMiniAppStoreError.staleVersion(expected: expectedBaseVersion, actual: current.version)
        }
        let now = nowMillis()
        let nextVersion = maxVersionNumber(appId: appId) + 1
        var updated = current
        updated.title = normalized.title
        updated.description = normalized.description
        updated.htmlContent = normalized.html
        updated.sourceMessageId = sourceMessageId ?? current.sourceMessageId
        updated.iconEmoji = normalized.icon
        updated.category = normalized.category
        updated.permissions = normalized.permissions
        updated.version = nextVersion
        updated.htmlHash = sha256(normalized.html)
        updated.updatedAt = now
        state.apps[index] = updated
        state.versions.append(
            versionRecord(
                for: updated,
                changeNote: changeNote?.truncated(to: 240) ?? "MiniApp revision",
                createdAt: now
            )
        )
        let prunedVersions = pruneVersions(appId: appId)
        return IOSMiniAppRevisionApplication(record: updated, prunedVersions: prunedVersions)
    }

    private func stageConversationMutation(
        record: IOSMiniAppRecord,
        undo: IOSMiniAppMutationUndo
    ) -> Mutation {
        let mutationId = UUID().uuidString
        let committedApp = IOSMiniAppRecordFingerprint(record)
        let pending = IOSMiniAppPendingConversationMutation(
            id: mutationId,
            reference: IOSMiniAppConversationReference(record: record),
            committedApp: committedApp,
            undo: undo
        )
        state.pendingConversationMutations = (state.pendingConversationMutations ?? []) + [pending]
        return Mutation(
            id: mutationId,
            record: record,
            committedApp: committedApp,
            undo: undo
        )
    }

    @discardableResult
    private func removePendingConversationMutation(id: String) -> Bool {
        var pending = state.pendingConversationMutations ?? []
        let originalCount = pending.count
        pending.removeAll { $0.id == id }
        guard pending.count != originalCount else { return false }
        state.pendingConversationMutations = pending.isEmpty ? nil : pending
        return true
    }

    private func applyUndo(_ undo: IOSMiniAppMutationUndo, appId: String) {
        state.apps.removeAll { $0.id == appId }
        if let previousApp = undo.previousApp {
            state.apps.append(previousApp)
            state.versions.removeAll {
                $0.appId == appId && $0.versionNumber == undo.addedVersionNumber
            }
            for version in undo.prunedVersions where !state.versions.contains(where: {
                $0.appId == version.appId && $0.versionNumber == version.versionNumber
            }) {
                state.versions.append(version)
            }
        } else {
            state.versions.removeAll { $0.appId == appId }
            state.grants.removeAll { $0.appId == appId }
            state.auditLogs.removeAll { $0.appId == appId }
            state.sharedData.removeAll { sharedDataBelongsToApp($0, appId: appId) }
        }
    }

    private func validated(_ output: IOSMiniAppGeneratedOutput) throws -> IOSMiniAppGeneratedOutput {
        let normalized = output.normalized()
        try IOSMiniAppOutputParser.validate(normalized)
        return normalized
    }

    private func seedSample() {
        do {
            let record = try recordFromGeneratedOutput(IOSMiniAppFixtures.sampleOutput, forcedId: IOSMiniAppFixtures.sampleId)
            state.apps = [record]
            state.versions = [versionRecord(for: record, changeNote: "Seed sample", createdAt: record.createdAt)]
            state.grants = IOSMiniAppFixtures.sampleOutput.permissions.map {
                IOSMiniAppGrantRecord(
                    appId: record.id,
                    permission: IOSMiniAppPermission.normalized($0),
                    decision: .allow,
                    updatedAt: record.createdAt
                )
            }
        } catch {
            storageError = error.localizedDescription
        }
    }

    private func removeLegacySeedIfPresent() -> Bool {
        guard let sample = state.apps.first(where: { $0.id == IOSMiniAppFixtures.sampleId }),
              sample.version == 1,
              sample.title == IOSMiniAppFixtures.sampleOutput.title,
              sample.description == IOSMiniAppFixtures.sampleOutput.description,
              sample.htmlHash == sha256(IOSMiniAppFixtures.sampleOutput.html),
              sample.iconEmoji == IOSMiniAppFixtures.sampleOutput.icon,
              sample.category == IOSMiniAppFixtures.sampleOutput.category,
              sample.permissions == IOSMiniAppFixtures.sampleOutput.normalized().permissions,
              sample.sourceConversationId == nil,
              sample.sourceMessageId == nil,
              !sample.pinned,
              sample.runCount == 0,
              sample.boardSummary == nil,
              sample.lastRunAt == nil,
              sample.createdAt == sample.updatedAt,
              state.auditLogs.allSatisfy({ $0.appId != sample.id }),
              state.sharedData.allSatisfy({ !sharedDataBelongsToApp($0, appId: sample.id) }) else {
            return false
        }

        let sampleVersions = state.versions.filter { $0.appId == sample.id }
        guard sampleVersions.count == 1,
              sampleVersions[0].versionNumber == 1,
              sampleVersions[0].htmlHash == sample.htmlHash,
              sampleVersions[0].changeNote == "Seed sample" else {
            return false
        }
        let expectedGrants = Set(IOSMiniAppFixtures.sampleOutput.normalized().permissions)
        let sampleGrants = state.grants.filter { $0.appId == sample.id }
        guard sampleGrants.count == expectedGrants.count,
              Set(sampleGrants.map(\.permission)) == expectedGrants,
              sampleGrants.allSatisfy({ $0.decision == .allow }) else {
            return false
        }

        state.apps.removeAll { $0.id == sample.id }
        state.versions.removeAll { $0.appId == sample.id }
        state.grants.removeAll { $0.appId == sample.id }
        state.auditLogs.removeAll { $0.appId == sample.id }
        state.sharedData.removeAll { sharedDataBelongsToApp($0, appId: sample.id) }
        return true
    }

    private func recordFromGeneratedOutput(_ output: IOSMiniAppGeneratedOutput, forcedId: String? = nil) throws -> IOSMiniAppRecord {
        let normalized = try validated(output)
        let now = nowMillis()
        return IOSMiniAppRecord(
            id: forcedId ?? UUID().uuidString,
            title: normalized.title,
            description: normalized.description,
            htmlContent: normalized.html,
            sourceConversationId: nil,
            sourceMessageId: nil,
            iconEmoji: normalized.icon,
            category: normalized.category,
            permissions: normalized.permissions,
            pinned: false,
            runCount: 0,
            boardSummary: nil,
            version: 1,
            htmlHash: sha256(normalized.html),
            createdAt: now,
            updatedAt: now,
            lastRunAt: nil
        )
    }

    private func versionRecord(for app: IOSMiniAppRecord, changeNote: String?, createdAt: Int64) -> IOSMiniAppVersionRecord {
        IOSMiniAppVersionRecord(
            appId: app.id,
            versionNumber: app.version,
            htmlContent: app.htmlContent,
            htmlHash: app.htmlHash,
            changeNote: changeNote,
            createdAt: createdAt
        )
    }

    private func maxVersionNumber(appId: String) -> Int {
        state.versions
            .filter { $0.appId == appId }
            .map(\.versionNumber)
            .max() ?? 0
    }

    @discardableResult
    private func pruneVersions(appId: String) -> [IOSMiniAppVersionRecord] {
        let versions = state.versions
            .filter { $0.appId == appId }
            .sorted { $0.versionNumber > $1.versionNumber }
        guard versions.count > versionKeepLimit else { return [] }
        let keep = Set(versions.prefix(versionKeepLimit).map(\.versionNumber))
        let pruned = versions.filter { !keep.contains($0.versionNumber) }
        state.versions.removeAll { $0.appId == appId && !keep.contains($0.versionNumber) }
        return pruned
    }

    private func sharedGetUnchecked(namespace: String, key: String) throws -> IOSMiniAppJSONValue? {
        let safeKey = try validateSharedKey(key)
        return state.sharedData.first { $0.namespace == namespace && $0.key == safeKey }?.value
    }

    private func sharedSetUnchecked(appId: String, namespace: String, key: String, value: IOSMiniAppJSONValue) throws {
        try ensureWritable()
        let safeKey = try validateSharedKey(key)
        guard value.byteCount <= sharedValueBytesLimit else { throw IOSMiniAppStoreError.sharedValueTooLarge }

        let oldBytes = state.sharedData.first { $0.namespace == namespace && $0.key == safeKey }?.value.byteCount ?? 0
        let currentBytes = state.sharedData
            .filter { $0.namespace == namespace }
            .reduce(0) { $0 + $1.value.byteCount }
        guard currentBytes - oldBytes + value.byteCount <= sharedNamespaceBytesLimit else {
            throw IOSMiniAppStoreError.sharedNamespaceFull
        }

        let record = IOSMiniAppSharedDataRecord(
            namespace: namespace,
            key: safeKey,
            value: value,
            lastWriterId: appId,
            updatedAt: nowMillis()
        )
        state.sharedData.removeAll { $0.namespace == namespace && $0.key == safeKey }
        state.sharedData.append(record)
        try persistAndPublish()
    }

    private func sharedRemoveUnchecked(namespace: String, key: String) throws {
        try ensureWritable()
        let safeKey = try validateSharedKey(key)
        state.sharedData.removeAll { $0.namespace == namespace && $0.key == safeKey }
        try persistAndPublish()
    }

    private func validateSharedNamespace(appId: String, namespace: String?) throws -> String {
        let normalized = namespace?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank ?? appId
        guard normalized == appId else { throw IOSMiniAppStoreError.crossAppNamespace }
        return normalized
    }

    private func validateSharedKey(_ key: String) throws -> String {
        let normalized = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...64).contains(normalized.count),
              normalized.range(of: #"^[a-zA-Z0-9._:-]+$"#, options: .regularExpression) != nil else {
            throw IOSMiniAppStoreError.invalidSharedKey(key)
        }
        return normalized
    }

    private func storageNamespace(_ appId: String) -> String {
        "__storage__:\(appId)"
    }

    private func sharedDataBelongsToApp(_ record: IOSMiniAppSharedDataRecord, appId: String) -> Bool {
        record.lastWriterId == appId || record.namespace == appId || record.namespace == storageNamespace(appId)
    }

    private func ensureWritable() throws {
        if writeBlockedByDecodeError {
            throw IOSMiniAppStoreError.storeCorrupt(storageError ?? "decode failed")
        }
    }

    private func persistAndPublish() throws {
        do {
            try persist()
            storageError = nil
            publish()
        } catch {
            let persistenceError = error
            state = committedState
            storageError = persistenceError.localizedDescription
            publish()
            throw persistenceError
        }
    }

    private func persist() throws {
        try ensureWritable()
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try encoder.encode(state)
        try data.write(to: fileURL, options: [.atomic])
    }

    private func publish() {
        committedState = state
        apps = state.apps.sorted { lhs, rhs in
            if lhs.pinned != rhs.pinned { return lhs.pinned && !rhs.pinned }
            return lhs.updatedAt > rhs.updatedAt
        }
        revision &+= 1
    }

    private func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }

    private let versionKeepLimit = 30
    private let sharedValueBytesLimit = 32 * 1024
    private let sharedNamespaceBytesLimit = 2 * 1024 * 1024
}

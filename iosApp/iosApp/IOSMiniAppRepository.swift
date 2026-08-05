import CryptoKit
import Foundation
import Observation

fileprivate struct IOSMiniAppStoreState: Codable, Equatable {
    var apps: [IOSMiniAppRecord] = []
    var versions: [IOSMiniAppVersionRecord] = []
    var grants: [IOSMiniAppGrantRecord] = []
    var auditLogs: [IOSMiniAppAuditRecord] = []
    var sharedData: [IOSMiniAppSharedDataRecord] = []
}

fileprivate struct IOSMiniAppStoreSlice: Equatable {
    let app: IOSMiniAppRecord?
    let versions: [IOSMiniAppVersionRecord]
    let grants: [IOSMiniAppGrantRecord]
    let auditLogs: [IOSMiniAppAuditRecord]
    let sharedData: [IOSMiniAppSharedDataRecord]
}

@MainActor
@Observable
final class IOSMiniAppRepository {
    struct Mutation {
        let record: IOSMiniAppRecord
        fileprivate let previousSlice: IOSMiniAppStoreSlice
        fileprivate let committedSlice: IOSMiniAppStoreSlice
    }

    static let shared = IOSMiniAppRepository()

    private(set) var apps: [IOSMiniAppRecord] = []
    private(set) var storageError: String?
    private(set) var revision: Int = 0

    @ObservationIgnored private var state = IOSMiniAppStoreState()
    @ObservationIgnored private let directory: URL
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private let fileManager: FileManager
    @ObservationIgnored private let encoder: JSONEncoder
    @ObservationIgnored private let decoder: JSONDecoder
    @ObservationIgnored private var writeBlockedByDecodeError = false
    @ObservationIgnored private let seedOnMissingStore: Bool

    init(
        baseDirectory: URL? = nil,
        seedOnMissingStore: Bool = true,
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
            state = try decoder.decode(IOSMiniAppStoreState.self, from: data)
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
        let normalized = try validated(output)
        let now = nowMillis()
        let htmlHash = sha256(normalized.html)
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
            htmlHash: htmlHash,
            createdAt: now,
            updatedAt: now,
            lastRunAt: nil
        )
        state.apps.removeAll { $0.id == record.id }
        state.apps.append(record)
        state.versions.removeAll { $0.appId == record.id }
        state.versions.append(versionRecord(for: record, changeNote: "Initial version", createdAt: now))
        try persistAndPublish()
        return record
    }

    func saveGeneratedMutation(
        _ output: IOSMiniAppGeneratedOutput,
        sourceConversationId: String? = nil,
        sourceMessageId: String? = nil
    ) throws -> Mutation {
        let appId = UUID().uuidString
        let previousSlice = slice(appId: appId)
        do {
            let record = try saveGenerated(
                output,
                sourceConversationId: sourceConversationId,
                sourceMessageId: sourceMessageId,
                forcedId: appId
            )
            return Mutation(
                record: record,
                previousSlice: previousSlice,
                committedSlice: slice(appId: appId)
            )
        } catch {
            restore(previousSlice, appId: appId)
            publish()
            throw error
        }
    }

    func saveRevision(
        appId: String,
        output: IOSMiniAppGeneratedOutput,
        expectedBaseVersion: Int? = nil,
        sourceMessageId: String? = nil,
        changeNote: String? = nil
    ) throws -> IOSMiniAppRecord? {
        try ensureWritable()
        let normalized = try validated(output)
        guard let index = state.apps.firstIndex(where: { $0.id == appId }) else { return nil }
        let current = state.apps[index]
        if let expectedBaseVersion, current.version != expectedBaseVersion {
            throw IOSMiniAppStoreError.staleVersion(expected: expectedBaseVersion, actual: current.version)
        }
        let now = nowMillis()
        let nextVersion = maxVersionNumber(appId: appId) + 1
        let htmlHash = sha256(normalized.html)
        var updated = current
        updated.title = normalized.title
        updated.description = normalized.description
        updated.htmlContent = normalized.html
        updated.sourceMessageId = sourceMessageId ?? current.sourceMessageId
        updated.iconEmoji = normalized.icon
        updated.category = normalized.category
        updated.permissions = normalized.permissions
        updated.version = nextVersion
        updated.htmlHash = htmlHash
        updated.updatedAt = now
        state.apps[index] = updated
        state.versions.append(versionRecord(for: updated, changeNote: changeNote?.truncated(to: 240) ?? "MiniApp revision", createdAt: now))
        pruneVersions(appId: appId)
        try persistAndPublish()
        return updated
    }

    func saveRevisionMutation(
        appId: String,
        output: IOSMiniAppGeneratedOutput,
        expectedBaseVersion: Int? = nil,
        sourceMessageId: String? = nil,
        changeNote: String? = nil
    ) throws -> Mutation? {
        let previousSlice = slice(appId: appId)
        do {
            guard let record = try saveRevision(
                appId: appId,
                output: output,
                expectedBaseVersion: expectedBaseVersion,
                sourceMessageId: sourceMessageId,
                changeNote: changeNote
            ) else {
                return nil
            }
            return Mutation(
                record: record,
                previousSlice: previousSlice,
                committedSlice: slice(appId: appId)
            )
        } catch {
            restore(previousSlice, appId: appId)
            publish()
            throw error
        }
    }

    @discardableResult
    func rollback(_ mutation: Mutation) throws -> Bool {
        let appId = mutation.record.id
        guard slice(appId: appId) == mutation.committedSlice else { return false }
        restore(mutation.previousSlice, appId: appId)
        do {
            try persistAndPublish()
            return true
        } catch {
            restore(mutation.committedSlice, appId: appId)
            publish()
            throw error
        }
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

    private func pruneVersions(appId: String) {
        let versions = state.versions
            .filter { $0.appId == appId }
            .sorted { $0.versionNumber > $1.versionNumber }
        guard versions.count > versionKeepLimit else { return }
        let keep = Set(versions.prefix(versionKeepLimit).map(\.versionNumber))
        state.versions.removeAll { $0.appId == appId && !keep.contains($0.versionNumber) }
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

    private func slice(appId: String) -> IOSMiniAppStoreSlice {
        IOSMiniAppStoreSlice(
            app: state.apps.first { $0.id == appId },
            versions: state.versions.filter { $0.appId == appId },
            grants: state.grants.filter { $0.appId == appId },
            auditLogs: state.auditLogs.filter { $0.appId == appId },
            sharedData: state.sharedData.filter { sharedDataBelongsToApp($0, appId: appId) }
        )
    }

    private func restore(_ slice: IOSMiniAppStoreSlice, appId: String) {
        state.apps.removeAll { $0.id == appId }
        state.versions.removeAll { $0.appId == appId }
        state.grants.removeAll { $0.appId == appId }
        state.auditLogs.removeAll { $0.appId == appId }
        state.sharedData.removeAll { sharedDataBelongsToApp($0, appId: appId) }
        if let app = slice.app { state.apps.append(app) }
        state.versions.append(contentsOf: slice.versions)
        state.grants.append(contentsOf: slice.grants)
        state.auditLogs.append(contentsOf: slice.auditLogs)
        state.sharedData.append(contentsOf: slice.sharedData)
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
        try persist()
        publish()
    }

    private func persist() throws {
        try ensureWritable()
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try encoder.encode(state)
        try data.write(to: fileURL, options: [.atomic])
    }

    private func publish() {
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

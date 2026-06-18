import Foundation
import Observation
@preconcurrency import Shared

/// [Slice 6] iOS-local persistence for memories.
///
/// The KMP `IosMemoryFactory` is a pure in-memory StateFlow (no file IO). This
/// Swift wrapper adds real persistence to `Documents/memories/memories.json`:
///   - `load()` at app startup reads the file and calls
///     `IosMemoryFactory.shared.replaceAll(records:)` to seed the store.
///   - `persist()` is called after each mutation (add/update/delete) to write
///     `IosMemoryFactory.shared.snapshotRecords()` to disk (atomic write).
///
/// Because `MemoryRecord` is a KMP class (not Swift Codable), we round-trip
/// through a Codable Swift mirror (`PersistedMemoryRecord`) that maps the same
/// fields and the `MemoryScope`/`MemoryKind` enums (serialized by their
/// serialName to stay compatible with Android/KMP's @Serializable form).
///
/// HONESTY: This is real file persistence. A write failure is logged but does
/// not crash — the in-memory store stays correct for the session, and the next
/// successful mutation rewrites the whole file.
@Observable
@MainActor
final class IOSMemoryPersistence {

    static let shared = IOSMemoryPersistence()

    private let fileURL: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        fileURL = docs.appendingPathComponent("memories", isDirectory: true)
            .appendingPathComponent("memories.json", isDirectory: false)
        encoder = JSONEncoder()
        decoder = JSONDecoder()
    }

    /// Load persisted records into the KMP store. Call once at app startup.
    /// Missing/corrupt file → no-op (store keeps its seed/empty state).
    func load() {
        guard FileManager.default.fileExists(atPath: fileURL.path),
              let data = try? Data(contentsOf: fileURL),
              let persisted = try? decoder.decode([PersistedMemoryRecord].self, from: data) else {
            return
        }
        let kmpRecords = persisted.map { $0.toKmp() }
        IosMemoryFactory.shared.replaceAll(records: kmpRecords)
    }

    /// Write the current KMP records to disk. Call after each mutation.
    func persist() {
        let snapshot = IosMemoryFactory.shared.snapshotRecords()
        let persisted = snapshot.map { PersistedMemoryRecord.from($0) }
        do {
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            let data = try encoder.encode(persisted)
            // .atomic writes via a temp file + rename, so a crash mid-write
            // can't corrupt the existing store.
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            // Don't crash the UI; the in-memory store still works this session.
            print("[IOSMemoryPersistence] persist failed: \(error.localizedDescription)")
        }
    }
}

// MARK: - Codable mirror of KMP MemoryRecord

/// Swift Codable mirror of KMP `MemoryRecord`. Field names match the KMP
/// @Serializable defaults (camelCase) so the file is readable and round-trips
/// through kotlinx.serialization if ever consumed by KMP.
private struct PersistedMemoryRecord: Codable {
    var id: Int
    var content: String
    var scope: String
    var kind: String
    var assistantId: String
    var sourceConversationId: String?
    var sourceMessageIds: [String]
    var supersedesIds: [Int]
    var expiresAt: Int64?
    var confidence: Double
    var pinned: Bool
    var archived: Bool
    var createdAt: Int64
    var updatedAt: Int64
    var lastUsedAt: Int64?

    static func from(_ record: MemoryRecord) -> PersistedMemoryRecord {
        PersistedMemoryRecord(
            id: Int(record.id),
            content: record.content,
            // [Slice 6 review P1] Use wireName (the @SerialName form, e.g.
            // "core"/"short_term"/"user") so the JSON file is interchangeable
            // with Android/KMP's kotlinx.serialization — not the Kotlin
            // constant identifier ("CORE"/"USER").
            scope: record.scope.wireName,
            kind: record.kind.wireName,
            assistantId: record.assistantId,
            sourceConversationId: record.sourceConversationId,
            sourceMessageIds: record.sourceMessageIds as? [String] ?? [],
            supersedesIds: (record.supersedesIds as? [KotlinInt])?.map { Int(truncating: $0) } ?? [],
            expiresAt: record.expiresAt?.int64Value,
            confidence: Double(record.confidence),
            pinned: record.pinned,
            archived: record.archived,
            createdAt: record.createdAt,
            updatedAt: record.updatedAt,
            lastUsedAt: record.lastUsedAt?.int64Value
        )
    }

    func toKmp() -> MemoryRecord {
        let scopeValue = memoryScopeByName(scope) ?? MemoryScope.longTerm
        let kindValue = memoryKindByName(kind) ?? MemoryKind.note
        return MemoryRecord(
            id: Int32(id),
            content: content,
            scope: scopeValue,
            kind: kindValue,
            assistantId: assistantId,
            sourceConversationId: sourceConversationId,
            sourceMessageIds: sourceMessageIds,
            supersedesIds: supersedesIds.map { KotlinInt(value: Int32($0)) },
            expiresAt: expiresAt.map { KotlinLong(value: $0) },
            confidence: Float(confidence),
            pinned: pinned,
            archived: archived,
            createdAt: createdAt,
            updatedAt: updatedAt,
            lastUsedAt: lastUsedAt.map { KotlinLong(value: $0) }
        )
    }
}

/// Iterate a KotlinArray<MemoryScope> by index (no Collection conformance in
/// the bridge) and match by `wireName` (the @SerialName form).
private func memoryScopeByName(_ wireName: String) -> MemoryScope? {
    let array = MemoryScope.values()
    let count = Int(array.size)
    for index in 0..<count {
        guard let value = array.get(index: Int32(index)) else { continue }
        if value.wireName == wireName { return value }
    }
    return nil
}

private func memoryKindByName(_ wireName: String) -> MemoryKind? {
    let array = MemoryKind.values()
    let count = Int(array.size)
    for index in 0..<count {
        guard let value = array.get(index: Int32(index)) else { continue }
        if value.wireName == wireName { return value }
    }
    return nil
}

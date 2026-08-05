import Foundation
import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class IOSMemoryPersistenceTests: XCTestCase {
    func testAddAndPersistThenReloadSurvivesRestart() throws {
        try withIsolatedPersistence { persistence, _ in
            persistence.load()
            let previousRecords = IosMemoryFactory.shared.snapshotRecords()
            IosMemoryFactory.shared.addMemory(
                scope: .core,
                kind: .note,
                content: "persistence-round-trip",
                assistantId: IosMemoryFactory.shared.GLOBAL_MEMORY_ID
            )

            XCTAssertTrue(persistence.persist(previousRecords: previousRecords))
            IosMemoryFactory.shared.replaceAll(records: [])

            persistence.load()

            XCTAssertEqual(persistence.loadState, .loaded)
            XCTAssertEqual(persistence.records.map(\.content), ["persistence-round-trip"])
            XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().map(\.content), ["persistence-round-trip"])
        }
    }

    func testDeleteAndPersistThenReloadStaysDeleted() throws {
        try withIsolatedPersistence { persistence, _ in
            persistence.load()
            let record = IosMemoryFactory.shared.addMemory(
                scope: .longTerm,
                kind: .note,
                content: "delete-round-trip",
                assistantId: IosMemoryFactory.shared.LONG_TERM_MEMORY_ID
            )
            XCTAssertTrue(persistence.persist(previousRecords: []))

            let previousRecords = IosMemoryFactory.shared.snapshotRecords()
            IosMemoryFactory.shared.deleteMemory(id: record.id)
            XCTAssertTrue(persistence.persist(previousRecords: previousRecords))

            IosMemoryFactory.shared.replaceAll(records: [record])
            persistence.load()

            XCTAssertEqual(persistence.loadState, .loaded)
            XCTAssertTrue(persistence.records.isEmpty)
            XCTAssertTrue(IosMemoryFactory.shared.getAllRecords().isEmpty)
        }
    }

    func testLoadMissingFilePublishesCurrentEmptyStore() throws {
        try withIsolatedPersistence { persistence, _ in
            IosMemoryFactory.shared.replaceAll(records: [makeRecord(id: 1, content: "stale-memory")])
            persistence.load()

            XCTAssertEqual(persistence.loadState, .missing)
            XCTAssertTrue(persistence.records.isEmpty)
            XCTAssertTrue(IosMemoryFactory.shared.getAllRecords().isEmpty)
        }
    }

    func testUnreadableFileBlocksOverwriteAndPreservesBytes() throws {
        try withIsolatedPersistence(initialData: Data("not-json".utf8)) { persistence, fileURL in
            persistence.load()
            XCTAssertEqual(persistence.loadState, .unreadable)

            let previousRecords = IosMemoryFactory.shared.snapshotRecords()
            IosMemoryFactory.shared.addMemory(
                scope: .core,
                kind: .note,
                content: "must-not-overwrite-corrupt-file",
                assistantId: IosMemoryFactory.shared.GLOBAL_MEMORY_ID
            )

            XCTAssertFalse(persistence.persist(previousRecords: previousRecords))
            XCTAssertEqual(try Data(contentsOf: fileURL), Data("not-json".utf8))
            XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().count, previousRecords.count)
        }
    }

    func testWriteFailureRollsBackEntireStore() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSMemoryPersistenceTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let blockingFile = root.appendingPathComponent("not-a-directory")
        try Data("block".utf8).write(to: blockingFile)
        defer { try? FileManager.default.removeItem(at: root) }

        let originalRecords = IosMemoryFactory.shared.snapshotRecords()
        defer { IosMemoryFactory.shared.replaceAll(records: originalRecords) }
        let existing = makeRecord(id: 1, content: "existing")
        IosMemoryFactory.shared.replaceAll(records: [existing])
        let persistence = IOSMemoryPersistence(fileURL: blockingFile.appendingPathComponent("memories.json"))
        persistence.load()
        persistence.refresh()

        IosMemoryFactory.shared.addMemory(
            scope: .core,
            kind: .note,
            content: "new",
            assistantId: IosMemoryFactory.shared.GLOBAL_MEMORY_ID
        )

        XCTAssertFalse(persistence.persist(previousRecords: [existing]))
        XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().map(\.content), ["existing"])
        XCTAssertEqual(persistence.records.map(\.content), ["existing"])
    }

    func testLegacyRecordDefaultsOptionalFields() throws {
        let legacy = #"[{"id":7,"content":"legacy","scope":"core","kind":"note","assistantId":"__global__"}]"#
        try withIsolatedPersistence(initialData: Data(legacy.utf8)) { persistence, _ in
            persistence.load()

            let record = try XCTUnwrap(persistence.records.first)
            XCTAssertEqual(persistence.loadState, .loaded)
            XCTAssertEqual(record.content, "legacy")
            XCTAssertTrue(record.sourceMessageIds.isEmpty)
            XCTAssertTrue(record.supersedesIds.isEmpty)
            XCTAssertEqual(record.confidence, 1)
            XCTAssertFalse(record.pinned)
            XCTAssertFalse(record.archived)
        }
    }

    func testPersistBeforeLoadCannotOverwriteExistingFile() throws {
        let originalData = Data(#"[{"id":7,"content":"existing","scope":"core","kind":"note","assistantId":"__global__"}]"#.utf8)
        try withIsolatedPersistence(initialData: originalData) { persistence, fileURL in
            let previousRecords = IosMemoryFactory.shared.snapshotRecords()
            IosMemoryFactory.shared.addMemory(
                scope: .core,
                kind: .note,
                content: "premature-write",
                assistantId: IosMemoryFactory.shared.GLOBAL_MEMORY_ID
            )

            XCTAssertFalse(persistence.persist(previousRecords: previousRecords))
            XCTAssertEqual(try Data(contentsOf: fileURL), originalData)
            XCTAssertEqual(persistence.loadState, .notLoaded)
            XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().map(\.content), previousRecords.map(\.content))
        }
    }

    private func withIsolatedPersistence(
        initialData: Data? = nil,
        _ body: (IOSMemoryPersistence, URL) throws -> Void
    ) throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSMemoryPersistenceTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let fileURL = root.appendingPathComponent("memories.json")
        if let initialData {
            try initialData.write(to: fileURL)
        }
        defer { try? FileManager.default.removeItem(at: root) }

        let originalRecords = IosMemoryFactory.shared.snapshotRecords()
        defer { IosMemoryFactory.shared.replaceAll(records: originalRecords) }
        IosMemoryFactory.shared.replaceAll(records: [])

        try body(IOSMemoryPersistence(fileURL: fileURL), fileURL)
    }

    private func makeRecord(id: Int32, content: String) -> MemoryRecord {
        MemoryRecord(
            id: id,
            content: content,
            scope: .core,
            kind: .note,
            assistantId: IosMemoryFactory.shared.GLOBAL_MEMORY_ID,
            sourceConversationId: nil,
            sourceMessageIds: [],
            supersedesIds: [],
            expiresAt: nil,
            confidence: 1,
            pinned: false,
            archived: false,
            createdAt: 1,
            updatedAt: 1,
            lastUsedAt: nil
        )
    }
}

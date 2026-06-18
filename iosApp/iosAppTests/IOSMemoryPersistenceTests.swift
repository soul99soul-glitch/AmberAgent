import XCTest
@preconcurrency import Shared
@testable import iosApp

/// [Slice 6] Verifies memory persistence: addMemory → persist → fresh load
/// (simulates app restart) → record survives; delete → persist → restart → gone.
///
/// Uses the real Documents/memories/memories.json path. Because the singleton
/// IOSMemoryPersistence writes to the app's real Documents dir, each test
/// cleans up its file in tearDown to avoid cross-test bleed.
@MainActor
final class IOSMemoryPersistenceTests: XCTestCase {

    private var persistence: IOSMemoryPersistence { .shared }

    override func tearDown() async throws {
        // Clean the persisted file so tests don't bleed into each other or the app.
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        let file = docs?.appendingPathComponent("memories", isDirectory: true)
            .appendingPathComponent("memories.json", isDirectory: false)
        if let file, FileManager.default.fileExists(atPath: file.path) {
            try? FileManager.default.removeItem(at: file)
        }
    }

    func testAddAndPersistThenReloadSurvivesRestart() {
        // Add a memory via the KMP store, then persist.
        let beforeCount = IosMemoryFactory.shared.getAllRecords().count
        IosMemoryFactory.shared.addMemory(
            scope: MemoryScope.core,
            kind: MemoryKind.note,
            content: "slice6-persistence-test-\(UUID().uuidString)",
            assistantId: IosMemoryFactory.shared.GLOBAL_MEMORY_ID
        )
        persistence.persist()
        let afterCount = IosMemoryFactory.shared.getAllRecords().count
        XCTAssertEqual(afterCount, beforeCount + 1, "addMemory must add a record")

        // Simulate restart: clear the KMP store, then load from disk.
        IosMemoryFactory.shared.replaceAll(records: [])
        XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().count, 0, "store cleared before reload")

        persistence.load()
        let reloaded = IosMemoryFactory.shared.getAllRecords()
        XCTAssertEqual(reloaded.count, afterCount, "persisted record must survive a fresh load (restart)")
    }

    func testDeleteAndPersistThenReloadStaysDeleted() {
        // Add then delete, persist after each.
        let record = IosMemoryFactory.shared.addMemory(
            scope: MemoryScope.longTerm,
            kind: MemoryKind.note,
            content: "slice6-delete-test",
            assistantId: IosMemoryFactory.shared.LONG_TERM_MEMORY_ID
        )
        persistence.persist()
        let id = record.id

        IosMemoryFactory.shared.deleteMemory(id: id)
        persistence.persist()

        // Restart.
        IosMemoryFactory.shared.replaceAll(records: [])
        persistence.load()
        let reloaded = IosMemoryFactory.shared.getAllRecords()
        XCTAssertFalse(
            reloaded.contains { $0.id == id },
            "deleted memory must not resurrect after reload"
        )
    }

    func testLoadMissingFileIsNoOp() {
        // tearDown already removed the file. Clear store, load — should stay empty
        // (honest, no crash, no fake records).
        IosMemoryFactory.shared.replaceAll(records: [])
        persistence.load()
        XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().count, 0, "missing file must be a no-op")
    }
}

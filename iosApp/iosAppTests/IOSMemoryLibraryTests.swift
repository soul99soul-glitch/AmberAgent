import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class IOSMemoryLibraryTests: XCTestCase {
    func testFilteredRecordsSearchesContentSourceAndScope() {
        let core = memory(
            id: 1,
            content: "Prefers compact SwiftUI settings pages.",
            scope: .core,
            kind: .note,
            sourceConversationId: "conversation-abc",
            sourceMessageIds: ["message-1"],
            pinned: true,
            updatedAt: 30
        )
        let shortTerm = memory(
            id: 2,
            content: "Planning search parity pass.",
            scope: .shortTerm,
            kind: .project,
            sourceConversationId: "conversation-def",
            sourceMessageIds: ["source-hit"],
            updatedAt: 40
        )

        XCTAssertEqual(
            IOSMemoryLibrary.filteredRecords(records: [core, shortTerm], query: "swiftui", scopeFilter: .all).map(\.id),
            [1]
        )
        XCTAssertEqual(
            IOSMemoryLibrary.filteredRecords(records: [core, shortTerm], query: "source-hit", scopeFilter: .all).map(\.id),
            [2]
        )
        XCTAssertEqual(
            IOSMemoryLibrary.filteredRecords(records: [core, shortTerm], query: "", scopeFilter: .shortTerm).map(\.id),
            [2]
        )
    }

    func testRecallExplanationHonorsRuntimeScopesAndOrdering() {
        let store = isolatedSettings()
        store.setMemoryRuntimeEnabled(core: true, shortTerm: false, longTerm: true)
        let core = memory(id: 1, content: "older pinned", scope: .core, kind: .note, pinned: true, updatedAt: 10)
        let shortTerm = memory(id: 2, content: "disabled scope", scope: .shortTerm, kind: .project, updatedAt: 100)
        let longTerm = memory(id: 3, content: "newer long term", scope: .longTerm, kind: .note, updatedAt: 90)

        let candidates = IOSMemoryLibrary.recallCandidates(
            records: [shortTerm, longTerm, core],
            runtime: store.agentRuntime,
            nowMillis: 1_000
        )

        XCTAssertEqual(candidates.map(\.id), [1, 3])
        let explanation = IOSMemoryLibrary.recallExplanation(records: [shortTerm, longTerm, core], runtime: store.agentRuntime)
        XCTAssertTrue(explanation.contains("核心、长期"))
        XCTAssertTrue(explanation.contains("置顶优先"))
    }

    func testSourceSummaryAndPreviewAreStable() {
        let record = memory(
            id: 9,
            content: String(repeating: "abc", count: 80),
            scope: .longTerm,
            kind: .reference,
            sourceConversationId: "conv-9",
            sourceMessageIds: ["m1", "m2"],
            supersedesIds: [1, 2, 3].map { KotlinInt(value: Int32($0)) }
        )

        XCTAssertEqual(IOSMemoryLibrary.sourceSummary(record), "会话 conv-9 · 2 条消息 · 替代 3 条")
        XCTAssertTrue(IOSMemoryLibrary.preview(record.content, limit: 12).hasSuffix("..."))
        XCTAssertEqual(IOSMemoryLibrary.scopeTitle(.longTerm), "长期")
        XCTAssertEqual(IOSMemoryLibrary.kindTitle(.reference), "资料")
    }

    func testWriteAuditStorePersistsAndClears() {
        let suiteName = "MemoryAudit-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        let store = IOSMemoryWriteAuditStore(userDefaults: defaults, key: "audit")

        store.record(action: "create", status: "user_saved", memoryId: 42, scope: "core", kind: "note", contentPreview: "hello")

        let reloaded = IOSMemoryWriteAuditStore(userDefaults: defaults, key: "audit")
        XCTAssertEqual(reloaded.records.count, 1)
        XCTAssertEqual(reloaded.records.first?.status, "user_saved")
        XCTAssertEqual(reloaded.records.first?.memoryId, 42)

        reloaded.clear()
        XCTAssertTrue(IOSMemoryWriteAuditStore(userDefaults: defaults, key: "audit").records.isEmpty)
    }

    private func isolatedSettings() -> IOSSharedSettingsStore {
        let suiteName = "MemoryLibrary-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return IOSSharedSettingsStore(userDefaults: defaults)
    }

    private func memory(
        id: Int32,
        content: String,
        scope: MemoryScope,
        kind: MemoryKind,
        sourceConversationId: String? = nil,
        sourceMessageIds: [String] = [],
        supersedesIds: [KotlinInt] = [],
        expiresAt: KotlinLong? = nil,
        pinned: Bool = false,
        archived: Bool = false,
        updatedAt: Int64 = 1
    ) -> MemoryRecord {
        MemoryRecord(
            id: id,
            content: content,
            scope: scope,
            kind: kind,
            assistantId: IosMemoryFactory.shared.GLOBAL_MEMORY_ID,
            sourceConversationId: sourceConversationId,
            sourceMessageIds: sourceMessageIds,
            supersedesIds: supersedesIds,
            expiresAt: expiresAt,
            confidence: 1,
            pinned: pinned,
            archived: archived,
            createdAt: 1,
            updatedAt: updatedAt,
            lastUsedAt: nil
        )
    }
}

import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class IOSMemoryLibraryTests: XCTestCase {
    func testMemoryOverviewKeepsLibraryToolsUserFacing() throws {
        // 用户页：搜索贴着记忆库、四标签等分；不挂召回解释 / 本次候选等控制台语义。
        let testsDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testsDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/MemoryOverviewView.swift"),
            encoding: .utf8
        )
        XCTAssertFalse(source.contains("召回解释"))
        XCTAssertFalse(source.contains("本次候选"))
        XCTAssertFalse(source.contains("搜索与过滤"))
        XCTAssertNil(source.range(of: "recallSection"))

        let recordsStart = try XCTUnwrap(source.range(of: "private var recordsSection:"))
        let auditStart = try XCTUnwrap(source.range(of: "private var auditSection:", range: recordsStart.upperBound..<source.endIndex))
        let recordsBlock = String(source[recordsStart.lowerBound..<auditStart.lowerBound])
        XCTAssertTrue(
            recordsBlock.contains("libraryToolbar"),
            "libraryToolbar 必须挂在 recordsSection 内，不能只是文件里另有定义"
        )
        XCTAssertTrue(recordsBlock.contains("AmberSectionLabel(text: \"记忆库\")"))

        let toolbarStart = try XCTUnwrap(source.range(of: "private var libraryToolbar:"))
        let toolbarEnd = try XCTUnwrap(source.range(of: "private var recordsSection:", range: toolbarStart.upperBound..<source.endIndex))
        let toolbarBlock = String(source[toolbarStart.lowerBound..<toolbarEnd.lowerBound])
        XCTAssertTrue(
            toolbarBlock.contains(".frame(maxWidth: .infinity)"),
            "四标签须等分铺开"
        )
        XCTAssertFalse(
            toolbarBlock.contains("ScrollView(.horizontal)"),
            "四枚固定短标签应等分铺开，不再横滑左簇拥"
        )
        XCTAssertFalse(
            source.contains("parts.append(\"#\\(memoryId)\")") || source.contains("#\\(record.id)"),
            "用户面不得再拼内部 memory id"
        )
    }

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
        XCTAssertTrue(explanation.contains("置顶"))
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

        XCTAssertEqual(IOSMemoryLibrary.sourceSummary(record), "来自聊天 · 关联 2 条消息 · 替代 3 条旧记忆")
        XCTAssertFalse(
            IOSMemoryLibrary.sourceSummary(record).contains("conv-9"),
            "用户面来源摘要不得泄漏 conversation id"
        )
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

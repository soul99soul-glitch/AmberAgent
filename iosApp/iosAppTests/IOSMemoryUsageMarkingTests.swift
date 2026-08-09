import Foundation
import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P2-b: 记忆召回使用记录（零模型依赖）。
///
/// 契约：召回注入点把本次注入的 `MemoryRecord` id 集合交给
/// `IOSMemoryPersistence.markUsed` —— "injected into upload = used"（注入即
/// 使用，不要求模型实际引用）。只更新 `lastUsedAt`（不动 updatedAt/content），
/// 内存 StateFlow 同步 + 原子写盘；同一 run 内注入集合不变时不重复写盘。
@MainActor
final class IOSMemoryUsageMarkingTests: XCTestCase {

    func testMarkedRecordsUpdateLastUsedAtAndPersistRoundTrip() throws {
        try withIsolatedPersistence { persistence, _ in
            let records = [
                makeRecord(id: 1, content: "user likes blue", scope: .core, kind: .user, updatedAt: 10),
                makeRecord(id: 2, content: "blue project", scope: .longTerm, kind: .project, updatedAt: 20),
                makeRecord(id: 3, content: "untouched", scope: .longTerm, kind: .note, updatedAt: 30),
            ]
            IosMemoryFactory.shared.replaceAll(records: records)

            XCTAssertTrue(persistence.markUsed(ids: Set<Int32>([1, 2]), now: 999))

            let inMemory = IosMemoryFactory.shared.getAllRecords()
            XCTAssertEqual(inMemory.first { $0.id == 1 }?.lastUsedAt?.int64Value, 999)
            XCTAssertEqual(inMemory.first { $0.id == 2 }?.lastUsedAt?.int64Value, 999)
            XCTAssertNil(inMemory.first { $0.id == 3 }?.lastUsedAt)
            // 使用记录只动 lastUsedAt，不把编辑时间误当使用时间。
            XCTAssertEqual(inMemory.first { $0.id == 1 }?.updatedAt, 10)
            XCTAssertEqual(inMemory.first { $0.id == 1 }?.content, "user likes blue")

            // 新持久化实例从同一文件读回，注入集合的 lastUsedAt 可见、未注入记录不变。
            let reader = IOSMemoryPersistence(fileURL: persistenceFileURL)
            reader.load()
            XCTAssertEqual(reader.loadState, .loaded)
            XCTAssertEqual(reader.records.first { $0.id == 1 }?.lastUsedAt?.int64Value, 999)
            XCTAssertEqual(reader.records.first { $0.id == 2 }?.lastUsedAt?.int64Value, 999)
            XCTAssertNil(reader.records.first { $0.id == 3 }?.lastUsedAt)
        }
    }

    func testSameInjectedSetWritesToDiskOnlyOnce() throws {
        try withIsolatedPersistence { persistence, _ in
            let records = [
                makeRecord(id: 1, content: "user likes blue", scope: .core, kind: .user, updatedAt: 10),
                makeRecord(id: 2, content: "blue project", scope: .longTerm, kind: .project, updatedAt: 20),
            ]
            IosMemoryFactory.shared.replaceAll(records: records)

            let revisionBefore = persistence.revision
            XCTAssertTrue(persistence.markUsed(ids: Set<Int32>([1, 2]), now: 100))
            XCTAssertEqual(persistence.revision, revisionBefore + 1)

            // 同一 run 同集合（工具循环每轮都注入同一批）：不再写盘，也不再改时间戳。
            XCTAssertFalse(persistence.markUsed(ids: Set<Int32>([1, 2]), now: 200))
            XCTAssertEqual(persistence.revision, revisionBefore + 1)
            XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().first?.lastUsedAt?.int64Value, 100)

            // 集合变化（本轮召回多了一条）才再次写。
            IosMemoryFactory.shared.replaceAll(records: records + [
                makeRecord(id: 3, content: "third memory", scope: .longTerm, kind: .note, updatedAt: 30)
            ])
            XCTAssertTrue(persistence.markUsed(ids: Set<Int32>([1, 2, 3]), now: 300))
            XCTAssertEqual(persistence.revision, revisionBefore + 2)
            XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().first { $0.id == 3 }?.lastUsedAt?.int64Value, 300)
        }
    }

    /// P2-c 修复 2：模型显式引用（citation flush）与召回标记语义不同——引用是
    /// 模型的显式信号，即使与上一 run 的召回集合完全一致也必须生效（刷新
    /// lastUsedAt）。`force: true` 绕过同集去抖，但空集合 no-op 与去抖状态
    /// 同步照旧。
    func testCitationFlushWithForceBypassesSameSetDedup() throws {
        try withIsolatedPersistence { persistence, _ in
            let records = [
                makeRecord(id: 1, content: "user likes blue", scope: .core, kind: .user, updatedAt: 10),
                makeRecord(id: 2, content: "blue project", scope: .longTerm, kind: .project, updatedAt: 20),
            ]
            IosMemoryFactory.shared.replaceAll(records: records)

            // run A 召回注入 {1,2}（不 force，去抖生效）。
            XCTAssertTrue(persistence.markUsed(ids: Set<Int32>([1, 2]), now: 100))
            // 同一 run 内同集合再次召回：仍被去抖。
            XCTAssertFalse(persistence.markUsed(ids: Set<Int32>([1, 2]), now: 200))

            // citation flush 命中完全相同的集合：force 绕过去抖、刷新 lastUsedAt。
            XCTAssertTrue(persistence.markUsed(ids: Set<Int32>([1, 2]), now: 300, force: true))
            XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().first { $0.id == 1 }?.lastUsedAt?.int64Value, 300)
            XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().first { $0.id == 2 }?.lastUsedAt?.int64Value, 300)

            // force 写盘后去抖状态同步刷新：随后的非 force 同集合调用继续被去抖。
            XCTAssertFalse(persistence.markUsed(ids: Set<Int32>([1, 2]), now: 400))
            XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().first { $0.id == 1 }?.lastUsedAt?.int64Value, 300)

            // force 不改变空集合 no-op 守卫。
            XCTAssertFalse(persistence.markUsed(ids: [], now: 500, force: true))
        }
    }

    func testEmptyRecallResultDoesNotWrite() throws {
        try withIsolatedPersistence { persistence, _ in
            IosMemoryFactory.shared.replaceAll(records: [
                makeRecord(id: 1, content: "user likes blue", scope: .core, kind: .user, updatedAt: 10),
            ])
            let revisionBefore = persistence.revision

            XCTAssertFalse(persistence.markUsed(ids: [], now: 999))
            XCTAssertEqual(persistence.revision, revisionBefore)
            XCTAssertFalse(FileManager.default.fileExists(atPath: persistenceFileURL.path))
            XCTAssertNil(IosMemoryFactory.shared.getAllRecords().first?.lastUsedAt)
        }
    }

    func testRecallSelectionIsUnaffectedByUsageMarking() throws {
        // 防回归：P2-b 只写 lastUsedAt，召回打分与注入 prompt 必须与改动前一致。
        let runtime = runtime(maxItems: 10, maxPromptChars: 500)
        let base = [
            makeRecord(id: 1, content: "favorite color blue", scope: .core, kind: .user, updatedAt: 10),
            makeRecord(id: 2, content: "unrelated", scope: .longTerm, kind: .note, updatedAt: 20),
            makeRecord(id: 3, content: "blue project", scope: .longTerm, kind: .project, updatedAt: 5),
        ]
        let used = base.map { record in
            MemoryRecord(
                id: record.id,
                content: record.content,
                scope: record.scope,
                kind: record.kind,
                assistantId: record.assistantId,
                sourceConversationId: record.sourceConversationId,
                sourceMessageIds: record.sourceMessageIds,
                supersedesIds: record.supersedesIds,
                expiresAt: record.expiresAt,
                confidence: record.confidence,
                pinned: record.pinned,
                archived: record.archived,
                createdAt: record.createdAt,
                updatedAt: record.updatedAt,
                lastUsedAt: KotlinLong(value: 999)
            )
        }

        let before = ChatMemoryContextBuilder.contextPromptResult(records: base, runtime: runtime, queryText: "blue", now: 100)
        let after = ChatMemoryContextBuilder.contextPromptResult(records: used, runtime: runtime, queryText: "blue", now: 100)

        XCTAssertEqual(after.records.map(\.id), before.records.map(\.id))
        XCTAssertEqual(after.prompt, before.prompt)
    }

    // MARK: - Fixtures

    private var persistenceFileURL: URL!
    private var cleanupRoot: URL?

    private func withIsolatedPersistence(
        _ body: (IOSMemoryPersistence, URL) throws -> Void
    ) throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSMemoryUsageMarkingTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let fileURL = root.appendingPathComponent("memories.json")
        cleanupRoot = root
        persistenceFileURL = fileURL
        defer {
            try? FileManager.default.removeItem(at: root)
            cleanupRoot = nil
            persistenceFileURL = nil
        }

        let originalRecords = IosMemoryFactory.shared.snapshotRecords()
        defer { IosMemoryFactory.shared.replaceAll(records: originalRecords) }
        IosMemoryFactory.shared.replaceAll(records: [])

        let persistence = IOSMemoryPersistence(fileURL: fileURL)
        persistence.load()
        try body(persistence, fileURL)
    }

    private func runtime(
        maxItems: Int32 = 12,
        maxPromptChars: Int32 = 2_000
    ) -> AgentRuntimeSetting {
        let base = IosSettingsDefaults.shared.defaultSeededSettings().agentRuntime
        return AgentRuntimeSetting(
            enableCoreMemory: base.enableCoreMemory,
            enableShortTermMemory: base.enableShortTermMemory,
            enableLongTermMemory: base.enableLongTermMemory,
            enableRecentChatsReference: base.enableRecentChatsReference,
            enableTimeReminder: base.enableTimeReminder,
            agentSoulMarkdown: base.agentSoulMarkdown,
            operationPreviewMode: base.operationPreviewMode,
            generativeUi: base.generativeUi,
            enableLiveStatusNotification: base.enableLiveStatusNotification,
            hideSensitiveLiveStatus: base.hideSensitiveLiveStatus,
            liveMode: base.liveMode,
            maxToolLoopSteps: base.maxToolLoopSteps,
            autoApproveAllToolCalls: base.autoApproveAllToolCalls,
            autoApproveHighRiskToolCalls: base.autoApproveHighRiskToolCalls,
            terminalDefaultRuntime: base.terminalDefaultRuntime,
            terminalMaxConcurrentJobs: base.terminalMaxConcurrentJobs,
            terminalOutputTailChars: base.terminalOutputTailChars,
            terminalInstallTimeoutMs: base.terminalInstallTimeoutMs,
            feishuOfficeEnhancement: base.feishuOfficeEnhancement,
            todayBoard: base.todayBoard,
            miniApp: base.miniApp,
            contextCompaction: base.contextCompaction,
            memoryRecall: MemoryRecallSetting(
                maxItems: maxItems,
                maxPromptChars: maxPromptChars,
                debug: false
            ),
            memoryWorker: base.memoryWorker,
            subAgent: base.subAgent,
            modelCouncil: base.modelCouncil,
            externalFileAccess: base.externalFileAccess,
            harnessDebug: base.harnessDebug,
            speculativeToolExecution: base.speculativeToolExecution,
            generationRetry: base.generationRetry,
            keepGenerationAliveInBackground: base.keepGenerationAliveInBackground
        )
    }

    private func makeRecord(
        id: Int32,
        content: String,
        scope: MemoryScope,
        kind: MemoryKind,
        updatedAt: Int64 = 0
    ) -> MemoryRecord {
        MemoryRecord(
            id: id,
            content: content,
            scope: scope,
            kind: kind,
            assistantId: scope == .longTerm ? "__long_term__" : "__global__",
            sourceConversationId: nil,
            sourceMessageIds: [],
            supersedesIds: [],
            expiresAt: nil,
            confidence: 1,
            pinned: false,
            archived: false,
            createdAt: updatedAt,
            updatedAt: updatedAt,
            lastUsedAt: nil
        )
    }
}

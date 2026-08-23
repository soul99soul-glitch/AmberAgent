import XCTest
import Shared
@testable import iosApp

final class IOSMemoryRecallPolicyTests: XCTestCase {
    func testRecallFiltersAndBoundsDeterministically() {
        let records = [
            record(id: 2, content: "favorite color blue", scope: .core, kind: .user, updatedAt: 10),
            record(id: 1, content: "unrelated", scope: .longTerm, kind: .note, updatedAt: 20),
            record(id: 3, content: "blue project", scope: .longTerm, kind: .project, updatedAt: 5),
        ]
        let runtime = runtime(maxItems: 2, maxPromptChars: 120)
        let result = ChatMemoryContextBuilder.contextPromptResult(records: records, runtime: runtime, queryText: "blue", now: 100)
        XCTAssertEqual(result.records.map(\.id), [2, 3])
        XCTAssertLessThanOrEqual(result.records.count, Int(runtime.memoryRecall.maxItems))
    }

    func testUnrelatedProjectAndReferenceAreNotAlwaysEligible() {
        let records = [
            record(id: 4, content: "project detail", scope: .longTerm, kind: .project),
            record(id: 5, content: "reference detail", scope: .longTerm, kind: .reference),
        ]
        let runtime = runtime(maxItems: 10, maxPromptChars: 500)
        let result = ChatMemoryContextBuilder.contextPromptResult(records: records, runtime: runtime, queryText: "unmatched", now: 100)
        XCTAssertTrue(result.records.isEmpty)
    }

    func testChineseRecallUsesTermsInsteadOfSingleCharacterOverlap() {
        let records = [
            record(
                id: 6,
                content: "我今天喝咖啡",
                scope: .longTerm,
                kind: .project
            ),
            record(
                id: 7,
                content: "用户喜欢苹果",
                scope: .longTerm,
                kind: .project
            ),
        ]
        let runtime = runtime(maxItems: 10, maxPromptChars: 500)

        let singleCharacterOnly = ChatMemoryContextBuilder.contextPromptResult(
            records: records,
            runtime: runtime,
            queryText: "我明天跑步",
            now: 100
        )
        let meaningfulTerm = ChatMemoryContextBuilder.contextPromptResult(
            records: records,
            runtime: runtime,
            queryText: "苹果怎么保存",
            now: 100
        )

        XCTAssertTrue(singleCharacterOnly.records.isEmpty)
        XCTAssertEqual(meaningfulTerm.records.map(\.id), [7])
    }

    func testSingleCharacterChineseQueryDoesNotRecallEveryUnrelatedMemory() {
        let records = [
            record(id: 11, content: "咖啡冲煮项目", scope: .longTerm, kind: .project),
            record(id: 12, content: "旅行参考资料", scope: .longTerm, kind: .reference),
        ]
        let runtime = runtime(maxItems: 10, maxPromptChars: 500)

        let result = ChatMemoryContextBuilder.contextPromptResult(
            records: records,
            runtime: runtime,
            queryText: "猫",
            now: 100
        )

        XCTAssertTrue(result.records.isEmpty)
    }

    func testFirstOversizedCandidateDoesNotBreakPromptBudget() {
        let records = [
            record(
                id: 8,
                content: "budget " + String(repeating: "x", count: 2_500),
                scope: .longTerm,
                kind: .project,
                updatedAt: 20
            ),
            record(
                id: 9,
                content: "budget short",
                scope: .longTerm,
                kind: .project,
                updatedAt: 10
            ),
        ]
        let runtime = runtime(maxItems: 10, maxPromptChars: 256)

        let result = ChatMemoryContextBuilder.contextPromptResult(
            records: records,
            runtime: runtime,
            queryText: "budget",
            now: 100
        )

        XCTAssertEqual(result.records.map(\.id), [9])
    }

    @MainActor
    func testApprovedEditAndDeleteRejectStaleMemoryVersion() throws {
        let previousRecords = IosMemoryFactory.shared.snapshotRecords()
        defer { IosMemoryFactory.shared.replaceAll(records: previousRecords) }
        let approved = record(
            id: 10,
            content: "approved preview",
            scope: .core,
            kind: .note,
            updatedAt: 10
        )
        IosMemoryFactory.shared.replaceAll(records: [approved])
        let editInput = #"{"action":"edit","id":10,"content":"model edit"}"#
        let deleteInput = #"{"action":"delete","id":10}"#
        let editPreview = try XCTUnwrap(IOSMemoryToolExecutor.approvalPreview(input: editInput))
        let deletePreview = try XCTUnwrap(IOSMemoryToolExecutor.approvalPreview(input: deleteInput))
        XCTAssertEqual(editPreview.expectedUpdatedAt, 10)
        XCTAssertEqual(deletePreview.expectedUpdatedAt, 10)

        let changed = record(
            id: approved.id,
            content: "user changed it",
            scope: approved.scope,
            kind: approved.kind,
            createdAt: approved.createdAt,
            updatedAt: 20
        )
        IosMemoryFactory.shared.replaceAll(records: [changed])
        let runtime = runtime()
        let editOutput = IOSMemoryToolExecutor.execute(
            input: editInput,
            runtime: runtime,
            writePolicy: .allow,
            expectedUpdatedAt: editPreview.expectedUpdatedAt
        )
        let deleteOutput = IOSMemoryToolExecutor.execute(
            input: deleteInput,
            runtime: runtime,
            writePolicy: .allow,
            expectedUpdatedAt: deletePreview.expectedUpdatedAt
        )

        XCTAssertTrue(editOutput.contains(#""code":"stale_memory""#), editOutput)
        XCTAssertTrue(deleteOutput.contains(#""code":"stale_memory""#), deleteOutput)
        XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().first?.content, "user changed it")
    }

    @MainActor
    func testCreateAndEditRejectOutOfRangeSupersedesIdsWithoutMutation() {
        let previousRecords = IosMemoryFactory.shared.snapshotRecords()
        defer { IosMemoryFactory.shared.replaceAll(records: previousRecords) }
        let existing = record(id: 10, content: "unchanged", scope: .core, kind: .note, updatedAt: 10)
        IosMemoryFactory.shared.replaceAll(records: [existing])
        let runtime = runtime()

        let createOutput = IOSMemoryToolExecutor.execute(
            input: #"{"action":"create","scope":"core","kind":"note","content":"new","supersedesIds":[2147483648]}"#,
            runtime: runtime,
            writePolicy: .allow
        )
        let editOutput = IOSMemoryToolExecutor.execute(
            input: #"{"action":"edit","id":10,"content":"changed","supersedesIds":[-2147483649]}"#,
            runtime: runtime,
            writePolicy: .allow
        )
        let malformedOutput = IOSMemoryToolExecutor.execute(
            input: #"{"action":"create","content":"bad","supersedesIds":["not-an-integer"]}"#,
            runtime: runtime,
            writePolicy: .allow
        )

        XCTAssertTrue(createOutput.contains(#""code":"integer_out_of_range""#), createOutput)
        XCTAssertTrue(editOutput.contains(#""code":"integer_out_of_range""#), editOutput)
        XCTAssertTrue(malformedOutput.contains(#""code":"integer_out_of_range""#), malformedOutput)
        XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().map(\.content), ["unchanged"])
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

    private func record(
        id: Int32,
        content: String,
        scope: MemoryScope,
        kind: MemoryKind,
        createdAt: Int64? = nil,
        updatedAt: Int64 = 0,
        lastUsedAt: Int64? = nil
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
            createdAt: createdAt ?? updatedAt,
            updatedAt: updatedAt,
            lastUsedAt: lastUsedAt.map { KotlinLong(value: $0) }
        )
    }
}

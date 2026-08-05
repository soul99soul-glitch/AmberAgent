import XCTest
import Shared
@testable import iosApp

final class IOSMemoryRecallPolicyTests: XCTestCase {
    func testRecallFiltersAndBoundsDeterministically() {
        let records = [
            MemoryRecord(id: 2, content: "favorite color blue", scope: .core, kind: .user, assistantId: "__global__", updatedAt: 10),
            MemoryRecord(id: 1, content: "unrelated", scope: .core, kind: .note, assistantId: "__global__", updatedAt: 20),
            MemoryRecord(id: 3, content: "blue project", scope: .longTerm, kind: .project, assistantId: "__long_term__", updatedAt: 5)
        ]
        let runtime = AgentRuntimeSetting(memoryRecall: MemoryRecallSetting(maxItems: 2, maxPromptChars: 120, debug: false))
        let result = ChatMemoryContextBuilder.contextPromptResult(records: records, runtime: runtime, queryText: "blue", now: 100)
        XCTAssertEqual(result.records.map(\.id), [2, 3])
        XCTAssertLessThanOrEqual(result.records.reduce(0) { $0 + $1.content.count + 32 }, 120)
    }

    func testTouchMemoriesOnlyChangesLastUsedAt() {
        let previousRecords = IosMemoryFactory.shared.snapshotRecords()
        defer { IosMemoryFactory.shared.replaceAll(records: previousRecords) }
        let original = MemoryRecord(id: 7, content: "x", scope: .core, kind: .note, assistantId: "__global__", updatedAt: 11, lastUsedAt: 12)
        IosMemoryFactory.shared.replaceAll(records: [original])
        IosMemoryFactory.shared.touchMemories(ids: [7], timestamp: 99)
        let touched = IosMemoryFactory.shared.getAllRecords()[0]
        XCTAssertEqual(touched.lastUsedAt?.int64Value, 99)
        XCTAssertEqual(touched.updatedAt, 11)
        XCTAssertEqual(touched.content, "x")
    }

    func testUnrelatedProjectAndReferenceAreNotAlwaysEligible() {
        let records = [
            MemoryRecord(id: 4, content: "project detail", scope: .longTerm, kind: .project, assistantId: "__long_term__"),
            MemoryRecord(id: 5, content: "reference detail", scope: .longTerm, kind: .reference, assistantId: "__long_term__")
        ]
        let runtime = AgentRuntimeSetting(memoryRecall: MemoryRecallSetting(maxItems: 10, maxPromptChars: 500, debug: false))
        let result = ChatMemoryContextBuilder.contextPromptResult(records: records, runtime: runtime, queryText: "unmatched", now: 100)
        XCTAssertTrue(result.records.isEmpty)
    }
}

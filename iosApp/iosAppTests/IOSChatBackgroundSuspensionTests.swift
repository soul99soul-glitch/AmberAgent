import XCTest
@testable import iosApp

final class IOSChatBackgroundSuspensionTests: XCTestCase {
    private var directory: URL!
    private var store: IOSChatBackgroundSuspensionStore!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatBackgroundSuspensionTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        store = IOSChatBackgroundSuspensionStore(directory: directory)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        store = nil
        directory = nil
        try super.tearDownWithError()
    }

    private func record(
        requestId: String = "app.amber.ios.chat.run1",
        runId: String = "run1",
        partial: String = "已经流出来的正文",
        suspendedAt: Int64 = 1_700_000_000_000,
        resumeCount: Int = 0
    ) -> IOSChatBackgroundSuspensionRecord {
        IOSChatBackgroundSuspensionRecord(
            requestId: requestId,
            runId: runId,
            partialAssistantText: partial,
            suspendedAt: suspendedAt,
            resumeCount: resumeCount
        )
    }

    func testSaveThenLoadRoundTripsPartialText() {
        let saved = record()
        store.save(saved)

        XCTAssertEqual(store.load(requestId: saved.requestId), saved)
    }

    func testLoadReturnsNilWhenNothingSuspended() {
        XCTAssertNil(store.load(requestId: "app.amber.ios.chat.absent"))
    }

    func testRemoveDeletesTheRecord() {
        let saved = record()
        store.save(saved)
        store.remove(requestId: saved.requestId)

        XCTAssertNil(store.load(requestId: saved.requestId))
        XCTAssertTrue(store.allRecords().isEmpty)
    }

    func testAllRecordsIsOrderedByInterruptionTime() {
        let later = record(requestId: "app.amber.ios.chat.b", runId: "b", suspendedAt: 200)
        let earlier = record(requestId: "app.amber.ios.chat.a", runId: "a", suspendedAt: 100)
        store.save(later)
        store.save(earlier)

        XCTAssertEqual(store.allRecords().map(\.runId), ["a", "b"])
    }

    func testAllRecordsIgnoresThePayloadFileSittingInTheSameDirectory() throws {
        store.save(record())
        // payload 与挂起记录同目录，扫描必须只认 .suspended.json 后缀。
        let payload = directory
            .appendingPathComponent(IOSChatBackgroundJobFileNaming.sanitized("app.amber.ios.chat.run1"))
            .appendingPathExtension("json")
        try Data("{\"runId\":\"run1\"}".utf8).write(to: payload)

        XCTAssertEqual(store.allRecords().count, 1)
    }

    func testResumeAttemptsAreCappedSoExpiryCannotLoopForever() {
        var current = record()
        XCTAssertTrue(current.canResume)

        for _ in 0..<IOSChatBackgroundSuspensionRecord.maxResumeAttempts {
            current = current.markingResumeAttempt()
        }

        XCTAssertEqual(current.resumeCount, IOSChatBackgroundSuspensionRecord.maxResumeAttempts)
        XCTAssertFalse(current.canResume, "到达上限后必须停止自动重投，降级成用户可见的可重试失败")
    }

    func testResumeCountSurvivesAnotherExpiry() {
        // 恢复后又被系统打断：计数必须继续累加，否则上限形同虚设。
        store.save(record().markingResumeAttempt())
        let carried = store.load(requestId: "app.amber.ios.chat.run1")?.resumeCount ?? 0

        XCTAssertEqual(carried, 1)
    }

    func testSanitizedNameKeepsDotsAndDashesButDropsSeparators() {
        let sanitized = IOSChatBackgroundJobFileNaming.sanitized("app.amber.ios.chat.a-b/c d")

        XCTAssertEqual(sanitized, "app.amber.ios.chat.a-b-c-d")
    }

    func testSuspensionAndPayloadFileNamesNeverCollide() {
        let requestId = "app.amber.ios.chat.run1"
        let payloadName = IOSChatBackgroundJobFileNaming.sanitized(requestId) + ".json"

        XCTAssertNotEqual(store.url(for: requestId).lastPathComponent, payloadName)
    }
}

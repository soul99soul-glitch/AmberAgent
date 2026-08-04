import BackgroundTasks
import UIKit
import XCTest
@testable import iosApp

/// 机制层的红绿门禁。两条腿都靠闭包注入替身验证，不需要真机——
/// 唯一测不到的是 `adopt`（`BGContinuedProcessingTask` 无法构造），
/// 那一段只能靠设备验证。
@MainActor
final class BackgroundGenerationKeepAliveTests: XCTestCase {
    /// 记录机制层对系统的每一次调用，并留出手动触发到期的入口。
    @MainActor
    private final class SystemSpy {
        var begunNames: [String] = []
        var endedTaskIds: [UIBackgroundTaskIdentifier] = []
        var submittedRequests: [BGContinuedProcessingTaskRequest] = []
        var cancelledIdentifiers: [String] = []
        var registeredIdentifiers: [String] = []
        var events: [String] = []
        /// 按 begin 顺序保存的到期回调，测试用它模拟 30 秒到点。
        var expirationHandlers: [() -> Void] = []

        var nextTaskId: Int = 1
        var registrationResult = true
        var submitError: Error?

        func makeKeepAlive() -> BackgroundGenerationKeepAlive {
            BackgroundGenerationKeepAlive(
                beginBackgroundTask: { [self] name, expiration in
                    begunNames.append(name)
                    events.append("begin")
                    expirationHandlers.append(expiration)
                    let identifier = UIBackgroundTaskIdentifier(rawValue: nextTaskId)
                    nextTaskId += 1
                    return identifier
                },
                endBackgroundTask: { [self] identifier in
                    endedTaskIds.append(identifier)
                    events.append("end")
                },
                submitTaskRequest: { [self] request in
                    if let submitError { throw submitError }
                    events.append("submit")
                    submittedRequests.append(request)
                },
                cancelTaskRequest: { [self] identifier in
                    cancelledIdentifiers.append(identifier)
                },
                registerLaunchHandler: { [self] identifier, _ in
                    registeredIdentifiers.append(identifier)
                    return registrationResult
                }
            )
        }
    }

    private struct SubmitFailure: Error {}

    // MARK: - begin

    func testBeginTakesUITaskAndSubmitsQueuedRequest() throws {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin("run-1", title: "Amber 正在生成", subtitle: "GPT")

        XCTAssertEqual(spy.begunNames, ["AmberGeneration-run-1"])
        let request = try XCTUnwrap(spy.submittedRequests.first)
        XCTAssertEqual(spy.submittedRequests.count, 1)
        XCTAssertEqual(request.identifier, keepAlive.identifier(for: "run-1"))
        XCTAssertEqual(spy.registeredIdentifiers, [request.identifier])
        XCTAssertEqual(request.title, "Amber 正在生成")
        XCTAssertEqual(request.subtitle, "GPT")
        // .fail 会在系统忙时当场判死；排队才有第二次机会。
        XCTAssertEqual(request.strategy, .queue)
        XCTAssertTrue(keepAlive.holdsLease("run-1"))
    }

    func testBeginCanSkipSystemTaskWhileKeepingUIKitLease() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin(
            "novel-run",
            title: "Amber 小说创作中",
            subtitle: "后台生成",
            submitSystemTask: false
        )

        XCTAssertEqual(spy.begunNames, ["AmberGeneration-novel-run"])
        XCTAssertTrue(spy.registeredIdentifiers.isEmpty)
        XCTAssertTrue(spy.submittedRequests.isEmpty)
        XCTAssertTrue(keepAlive.holdsLease("novel-run"))
    }

    func testIdentifierStaysInsidePermittedNamespace() {
        let keepAlive = SystemSpy().makeKeepAlive()

        // Info.plist 只放行 `<bundle>.keepalive.*`，越界会被 register 拒掉。
        let identifier = keepAlive.identifier(for: "run/1 :: 议会")

        // 硬编码期望值，不要拿实现里那套谓词再算一遍——那样断言恒真，
        // 测的是标准库不是这段代码。CJK 也必须被换掉：`isLetter` 对它是 true。
        XCTAssertEqual(
            identifier,
            "\(Bundle.main.bundleIdentifier ?? "app.amber.ios").keepalive.run-1------"
        )
    }

    func testBeginIsIdempotentForSameLease() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin("run-1", title: "t", subtitle: "s")
        keepAlive.begin("run-1", title: "t", subtitle: "s")

        XCTAssertEqual(spy.begunNames.count, 1)
        XCTAssertEqual(spy.submittedRequests.count, 1)
    }

    func testTransferReleasesGenericLeaseBeforeStartingDedicatedRequest() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()
        keepAlive.begin("run-1", title: "t", subtitle: "s")

        let didStart = keepAlive.transfer("run-1") {
            spy.events.append("dedicated-submit")
            return true
        }

        XCTAssertTrue(didStart)
        XCTAssertFalse(keepAlive.holdsLease("run-1"))
        XCTAssertEqual(spy.events, ["begin", "submit", "end", "dedicated-submit"])
    }

    func testTransferRestoresGenericLeaseWhenDedicatedRequestFails() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()
        keepAlive.begin("run-1", title: "t", subtitle: "s")

        let didStart = keepAlive.transfer("run-1") {
            spy.events.append("dedicated-submit")
            return false
        }

        XCTAssertFalse(didStart)
        XCTAssertTrue(keepAlive.holdsLease("run-1"))
        XCTAssertEqual(
            spy.events,
            ["begin", "submit", "end", "dedicated-submit", "begin", "submit"]
        )
    }

    func testConcurrentLeasesAreTrackedIndependently() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin("run-1", title: "t", subtitle: "s")
        keepAlive.begin("run-2", title: "t", subtitle: "s")
        XCTAssertEqual(keepAlive.activeLeaseIds, ["run-1", "run-2"])

        keepAlive.end("run-1")
        XCTAssertEqual(keepAlive.activeLeaseIds, ["run-2"])
        XCTAssertFalse(keepAlive.holdsLease("run-1"))
        XCTAssertTrue(keepAlive.holdsLease("run-2"))
    }

    // MARK: - end

    func testEndReleasesUITaskAndDropsLease() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin("run-1", title: "t", subtitle: "s")
        keepAlive.end("run-1")

        XCTAssertEqual(spy.endedTaskIds.count, 1)
        XCTAssertFalse(keepAlive.holdsLease("run-1"))
    }

    func testEndCancelsTheSubmittedRequest() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin("run-1", title: "t", subtitle: "s")
        XCTAssertTrue(spy.cancelledIdentifiers.isEmpty)
        keepAlive.end("run-1")

        // .queue 的请求不会自己过期。不撤，系统会在这一轮早就结束之后才调度到，
        // 把 App 唤起来、弹一张名不副实的「正在生成」进度卡。
        XCTAssertEqual(spy.cancelledIdentifiers, [keepAlive.identifier(for: "run-1")])
    }

    func testUITaskExpirationAlsoCancelsTheSubmittedRequest() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin("run-1", title: "t", subtitle: "s")
        spy.expirationHandlers.first?()

        // 短腿到期意味着放弃这一轮，长窗口再来也没人认领了——必须一并撤掉。
        XCTAssertEqual(spy.cancelledIdentifiers, [keepAlive.identifier(for: "run-1")])
    }

    func testEndIsIdempotent() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin("run-1", title: "t", subtitle: "s")
        keepAlive.end("run-1")
        keepAlive.end("run-1")
        keepAlive.end("never-started")

        XCTAssertEqual(spy.endedTaskIds.count, 1)
    }

    func testEndDoesNotFireOnExpire() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()
        var expired = 0

        keepAlive.begin("run-1", title: "t", subtitle: "s") { expired += 1 }
        keepAlive.end("run-1")

        // 正常跑完不是失去执行权，触发交接就会白白重跑一遍。
        XCTAssertEqual(expired, 0)
    }

    // MARK: - 短腿到期

    func testUITaskExpirationBeforeAdoptionDropsLeaseAndNotifiesOwner() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()
        var expired = 0
        var heldInsideCallback: Bool?

        keepAlive.begin("run-1", title: "t", subtitle: "s") {
            expired += 1
            heldInsideCallback = keepAlive.holdsLease("run-1")
        }
        spy.expirationHandlers.first?()

        XCTAssertEqual(expired, 1)
        XCTAssertFalse(keepAlive.holdsLease("run-1"))
        // 回调里必须已经读不到租约，否则上层的交接会被自己短路掉，两边都不干活。
        XCTAssertEqual(heldInsideCallback, false)
        XCTAssertEqual(spy.endedTaskIds.count, 1)
    }

    func testUITaskExpirationAfterEndIsInert() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()
        var expired = 0

        keepAlive.begin("run-1", title: "t", subtitle: "s") { expired += 1 }
        keepAlive.end("run-1")
        spy.expirationHandlers.first?()

        XCTAssertEqual(expired, 0)
        XCTAssertEqual(spy.endedTaskIds.count, 1)
    }

    // MARK: - 降级路径

    func testSubmitFailureKeepsLeaseSoUITaskStillCovers() {
        let spy = SystemSpy()
        spy.submitError = SubmitFailure()
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin("run-1", title: "t", subtitle: "s")

        // BG 任务提交失败只是退化成 30 秒，不该连短腿一起丢掉。
        XCTAssertTrue(keepAlive.holdsLease("run-1"))
        XCTAssertEqual(spy.begunNames.count, 1)
        XCTAssertTrue(spy.endedTaskIds.isEmpty)
    }

    func testRegistrationRefusalStillKeepsLease() {
        let spy = SystemSpy()
        spy.registrationResult = false
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin("run-1", title: "t", subtitle: "s")

        // Continued Processing 的通配符只负责 Info.plist 放行；运行时注册必须
        // 使用本轮具体 identifier。注册失败后若仍 submit，真机会抛 Objective-C
        // exception，Swift do/catch 接不住并直接 SIGABRT。
        XCTAssertEqual(spy.registeredIdentifiers, [keepAlive.identifier(for: "run-1")])
        XCTAssertTrue(spy.submittedRequests.isEmpty)
        // 注册被拒只丢长窗口，短腿还在，不能连租约一起丢。
        XCTAssertTrue(keepAlive.holdsLease("run-1"))
        XCTAssertEqual(spy.begunNames.count, 1)
    }

    func testEachConcreteIdentifierRegistersBeforeSubmission() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin("run-1", title: "t", subtitle: "s")
        keepAlive.end("run-1")
        keepAlive.begin("run-2", title: "t", subtitle: "s")

        let expectedIdentifiers = [
            keepAlive.identifier(for: "run-1"),
            keepAlive.identifier(for: "run-2")
        ]
        XCTAssertEqual(spy.registeredIdentifiers, expectedIdentifiers)
        XCTAssertEqual(spy.submittedRequests.map { $0.identifier }, expectedIdentifiers)
    }

    func testReusingLeaseIdentifierDoesNotRegisterHandlerTwice() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()

        keepAlive.begin("run-1", title: "t", subtitle: "s")
        keepAlive.end("run-1")
        keepAlive.begin("run-1", title: "t", subtitle: "s")

        XCTAssertEqual(spy.registeredIdentifiers, [keepAlive.identifier(for: "run-1")])
        XCTAssertEqual(spy.submittedRequests.count, 2)
    }

    // MARK: - 诊断

    func testSnapshotDetailReportsLeaseCounts() {
        let spy = SystemSpy()
        let keepAlive = spy.makeKeepAlive()

        XCTAssertEqual(keepAlive.snapshotDetail, "keepAlive=0 adopted=0")
        keepAlive.begin("run-1", title: "t", subtitle: "s")
        XCTAssertEqual(keepAlive.snapshotDetail, "keepAlive=1 adopted=0")
        keepAlive.end("run-1")
        XCTAssertEqual(keepAlive.snapshotDetail, "keepAlive=0 adopted=0")
    }
}

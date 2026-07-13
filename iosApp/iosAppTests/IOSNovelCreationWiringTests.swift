import UIKit
import XCTest
@testable import iosApp

@MainActor
final class IOSNovelCreationWiringTests: XCTestCase {
    func testSessionShortcutAndAppRoutesExposeNovelCreationWithoutRemovingSettingsMemory() throws {
        let projectID = NovelProjectID()
        let routes: [Route] = [.novelCreation, .novelProject(id: projectID)]
        let appShell = try source("iosApp/AppShell.swift")
        let home = try source("iosApp/PlaceholderViews.swift")

        XCTAssertEqual(routes, [.novelCreation, .novelProject(id: projectID)])
        XCTAssertTrue(appShell.contains("NovelProjectListView("))
        XCTAssertTrue(appShell.contains("NovelProjectWorkspaceView("))

        let miniApps = try XCTUnwrap(home.range(of: "title: \"小应用\""))
        let novel = try XCTUnwrap(home.range(of: "title: \"小说创作\""))
        let webMount = try XCTUnwrap(home.range(of: "title: \"WebMount\""))
        XCTAssertLessThan(miniApps.lowerBound, novel.lowerBound)
        XCTAssertLessThan(novel.lowerBound, webMount.lowerBound)
        XCTAssertTrue(home.contains("route: .novelCreation"))
        XCTAssertTrue(home.contains("title: \"核心记忆\""))
        XCTAssertTrue(home.contains("route: .memory"))
    }

    func testBackgroundTaskEndsOnceWhenInterruptionAndExpirationRace() async {
        var expirationHandler: (@Sendable () -> Void)?
        var endedTaskIDs: [UIBackgroundTaskIdentifier] = []
        var interruptionCount = 0
        let taskID = UIBackgroundTaskIdentifier(rawValue: 41)
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            timeout: 5,
            beginBackgroundTask: { _, handler in
                expirationHandler = handler
                return taskID
            },
            endBackgroundTask: { endedTaskIDs.append($0) }
        )

        coordinator.enterBackground { _ in
            interruptionCount += 1
        }

        let didEnd = await eventually { endedTaskIDs.count == 1 }
        XCTAssertTrue(didEnd)
        expirationHandler?()
        await Task.yield()

        XCTAssertEqual(interruptionCount, 1)
        XCTAssertEqual(endedTaskIDs, [taskID])
    }

    func testExpirationEndsTaskOnceWhileDurableInterruptionIsStillFinishing() async {
        var expirationHandler: (@Sendable () -> Void)?
        var endedTaskIDs: [UIBackgroundTaskIdentifier] = []
        let taskID = UIBackgroundTaskIdentifier(rawValue: 42)
        let gate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            timeout: 5,
            beginBackgroundTask: { _, handler in
                expirationHandler = handler
                return taskID
            },
            endBackgroundTask: { endedTaskIDs.append($0) }
        )

        coordinator.enterBackground { _ in
            await gate.wait()
        }
        let didStartWaiting = await eventually { await gate.hasWaiter }
        XCTAssertTrue(didStartWaiting)

        expirationHandler?()
        let didEnd = await eventually { endedTaskIDs.count == 1 }
        XCTAssertTrue(didEnd)
        await gate.open()
        await Task.yield()

        XCTAssertEqual(endedTaskIDs, [taskID])
    }

    func testFiveSecondFallbackUsesSameExactlyOnceFinishGate() async {
        var endedTaskIDs: [UIBackgroundTaskIdentifier] = []
        let taskID = UIBackgroundTaskIdentifier(rawValue: 43)
        let gate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            timeout: 0.02,
            beginBackgroundTask: { _, _ in taskID },
            endBackgroundTask: { endedTaskIDs.append($0) }
        )

        coordinator.enterBackground { _ in
            await gate.wait()
        }

        let didEnd = await eventually { endedTaskIDs.count == 1 }
        XCTAssertTrue(didEnd)
        await gate.open()
        await Task.yield()
        XCTAssertEqual(endedTaskIDs, [taskID])
    }

    func testWorkspaceWiresBackgroundAndRouteExitToSessionLifecycle() throws {
        let source = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")

        XCTAssertTrue(source.contains("@Environment(\\.scenePhase)"))
        XCTAssertTrue(source.contains("phase == .background"))
        XCTAssertTrue(source.contains("lifecycleCoordinator.enterBackground"))
        XCTAssertTrue(source.contains("sessionViewModel.interruptForBackground"))
        XCTAssertTrue(source.contains(".onDisappear"))
        XCTAssertTrue(source.contains("sessionViewModel.interruptForRouteExit"))
        XCTAssertTrue(source.contains("guard chapterReaderRoute == nil else { return }"))
        XCTAssertTrue(source.contains("await sessionViewModel.bindToCurrentSelection()"))
    }

    private func source(_ relativePath: String) throws -> String {
        let testsDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        return try String(
            contentsOf: testsDirectory.deletingLastPathComponent().appendingPathComponent(relativePath),
            encoding: .utf8
        )
    }

    private func eventually(
        timeout: TimeInterval = 1,
        condition: @MainActor () async -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return await condition()
    }
}

private actor NovelWorkspaceLifecycleTestGate {
    private var isOpen = false
    private var continuation: CheckedContinuation<Void, Never>?

    var hasWaiter: Bool { continuation != nil }

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { continuation in
            self.continuation = continuation
        }
    }

    func open() {
        isOpen = true
        continuation?.resume()
        continuation = nil
    }
}

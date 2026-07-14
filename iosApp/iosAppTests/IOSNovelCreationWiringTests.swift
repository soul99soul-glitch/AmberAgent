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

    func testBackgroundLeaseWaitsForExpirationBeforeInterrupting() async {
        var expirationHandler: (@Sendable () -> Void)?
        var endedTaskIDs: [UIBackgroundTaskIdentifier] = []
        var interruptionCount = 0
        let taskID = UIBackgroundTaskIdentifier(rawValue: 41)
        let completionGate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginBackgroundTask: { _, handler in
                expirationHandler = handler
                return taskID
            },
            endBackgroundTask: { endedTaskIDs.append($0) }
        )

        coordinator.enterBackground(
            waitForCompletion: { await completionGate.wait() },
            interrupt: { _ in interruptionCount += 1 }
        )

        await Task.yield()
        XCTAssertEqual(interruptionCount, 0)
        XCTAssertTrue(endedTaskIDs.isEmpty)

        expirationHandler?()
        let didEnd = await eventually { endedTaskIDs.count == 1 }
        XCTAssertTrue(didEnd)

        XCTAssertEqual(interruptionCount, 1)
        XCTAssertEqual(endedTaskIDs, [taskID])
        await completionGate.open()
    }

    func testExpirationEndsTaskAfterDurableInterruptionFinishes() async {
        var expirationHandler: (@Sendable () -> Void)?
        var endedTaskIDs: [UIBackgroundTaskIdentifier] = []
        let taskID = UIBackgroundTaskIdentifier(rawValue: 42)
        let gate = NovelWorkspaceLifecycleTestGate()
        let completionGate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginBackgroundTask: { _, handler in
                expirationHandler = handler
                return taskID
            },
            endBackgroundTask: { endedTaskIDs.append($0) }
        )

        coordinator.enterBackground(
            waitForCompletion: { await completionGate.wait() },
            interrupt: { _ in await gate.wait() }
        )
        expirationHandler?()
        let didStartWaiting = await eventually { await gate.hasWaiter }
        XCTAssertTrue(didStartWaiting)
        XCTAssertTrue(endedTaskIDs.isEmpty)
        await gate.open()
        let didEnd = await eventually { endedTaskIDs.count == 1 }

        XCTAssertTrue(didEnd)
        XCTAssertEqual(endedTaskIDs, [taskID])
        await completionGate.open()
    }

    func testReturningForegroundEndsLeaseWithoutInterruptingGeneration() async {
        var endedTaskIDs: [UIBackgroundTaskIdentifier] = []
        var interruptionCount = 0
        let taskID = UIBackgroundTaskIdentifier(rawValue: 43)
        let completionGate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginBackgroundTask: { _, _ in taskID },
            endBackgroundTask: { endedTaskIDs.append($0) }
        )

        coordinator.enterBackground(
            waitForCompletion: { await completionGate.wait() },
            interrupt: { _ in interruptionCount += 1 }
        )
        coordinator.enterForeground()

        let didEnd = await eventually { endedTaskIDs.count == 1 }
        XCTAssertTrue(didEnd)
        XCTAssertEqual(endedTaskIDs, [taskID])
        XCTAssertEqual(interruptionCount, 0)
        await completionGate.open()
    }

    func testWorkspaceExitDetachesConsumerWhileAppOwnsBackgroundLease() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let appShell = try source("iosApp/AppShell.swift")

        XCTAssertTrue(workspace.contains(".onDisappear"))
        XCTAssertTrue(workspace.contains("sessionViewModel.detachConsumer()"))
        XCTAssertFalse(workspace.contains("@Environment(\\.scenePhase)"))
        XCTAssertFalse(workspace.contains("guard chapterReaderRoute == nil else { return }"))
        XCTAssertTrue(workspace.contains("await sessionViewModel.bindToCurrentSelection()"))
        XCTAssertTrue(appShell.contains("novelLifecycleCoordinator.enterBackground"))
        XCTAssertTrue(appShell.contains("waitForBackgroundGeneration"))
        XCTAssertTrue(appShell.contains("interruptSessionForBackground"))
        XCTAssertTrue(appShell.contains("novelLifecycleCoordinator.enterForeground()"))
    }

    func testNovelCreationUsesNativePushNavigationAndChatKeyboardDismissal() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let reader = try source("iosApp/NovelCreation/NovelChapterReaderView.swift")
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")

        XCTAssertTrue(workspace.contains(".navigationDestination(item: $chapterReaderRoute)"))
        XCTAssertFalse(workspace.contains(".fullScreenCover(item: $chapterReaderRoute)"))
        XCTAssertFalse(workspace.contains(".navigationBarBackButtonHidden(true)"))
        XCTAssertTrue(workspace.contains("case .manuscript:"))
        XCTAssertTrue(reader.contains(".toolbar { readerToolbar }"))
        XCTAssertTrue(reader.contains(".safeAreaInset(edge: .bottom"))
        XCTAssertTrue(reader.contains("ComposerDockCircleGlass(tint: nil)"))
        XCTAssertFalse(reader.contains(".navigationBarBackButtonHidden(true)"))
        XCTAssertTrue(session.contains("private func dismissKeyboard()"))
        XCTAssertGreaterThanOrEqual(session.components(separatedBy: "dismissKeyboard()").count - 1, 3)
        XCTAssertTrue(session.contains("#selector(UIResponder.resignFirstResponder)"))
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

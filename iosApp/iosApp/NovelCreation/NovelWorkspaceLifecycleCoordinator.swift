import Foundation
import UIKit

@MainActor
final class NovelWorkspaceLifecycleCoordinator {
    typealias BackgroundInterruption = @MainActor (Date) async -> Void
    typealias BeginBackgroundTask = @MainActor (
        _ name: String,
        _ expirationHandler: @escaping @Sendable () -> Void
    ) -> UIBackgroundTaskIdentifier
    typealias EndBackgroundTask = @MainActor (UIBackgroundTaskIdentifier) -> Void

    private struct Cycle {
        let id: UUID
        let backgroundTaskID: UIBackgroundTaskIdentifier
        let interruption: BackgroundInterruption
        var interruptionStarted: Bool
        let timeoutTask: Task<Void, Never>
    }

    private let timeout: TimeInterval
    private let now: @MainActor () -> Date
    private let beginBackgroundTask: BeginBackgroundTask
    private let endBackgroundTask: EndBackgroundTask
    private var cycle: Cycle?

    init(
        timeout: TimeInterval = 5,
        now: @escaping @MainActor () -> Date = Date.init,
        beginBackgroundTask: @escaping BeginBackgroundTask = { name, expirationHandler in
            UIApplication.shared.beginBackgroundTask(
                withName: name,
                expirationHandler: expirationHandler
            )
        },
        endBackgroundTask: @escaping EndBackgroundTask = { identifier in
            UIApplication.shared.endBackgroundTask(identifier)
        }
    ) {
        self.timeout = max(0, timeout)
        self.now = now
        self.beginBackgroundTask = beginBackgroundTask
        self.endBackgroundTask = endBackgroundTask
    }

    func enterBackground(interrupt: @escaping BackgroundInterruption) {
        guard cycle == nil else { return }

        let cycleID = UUID()
        let expirationHandler: @Sendable () -> Void = { [weak self] in
            Task { @MainActor in
                self?.expire(cycleID: cycleID)
            }
        }
        let backgroundTaskID = beginBackgroundTask(
            "Amber Novel Background Flush",
            expirationHandler
        )
        let timeoutNanoseconds = UInt64(timeout * 1_000_000_000)
        let timeoutTask = Task { @MainActor in
            if timeoutNanoseconds > 0 {
                try? await Task.sleep(nanoseconds: timeoutNanoseconds)
            }
            guard !Task.isCancelled else { return }
            expire(cycleID: cycleID)
        }
        cycle = Cycle(
            id: cycleID,
            backgroundTaskID: backgroundTaskID,
            interruption: interrupt,
            interruptionStarted: false,
            timeoutTask: timeoutTask
        )

        requestInterruption(
            cycleID: cycleID,
            deadline: now().addingTimeInterval(timeout)
        )
    }

    private func expire(cycleID: UUID) {
        requestInterruption(cycleID: cycleID, deadline: now())
        finish(cycleID: cycleID)
    }

    private func requestInterruption(cycleID: UUID, deadline: Date) {
        guard var current = cycle,
              current.id == cycleID,
              !current.interruptionStarted else { return }
        current.interruptionStarted = true
        cycle = current

        Task { @MainActor in
            await current.interruption(deadline)
            finish(cycleID: cycleID)
        }
    }

    private func finish(cycleID: UUID) {
        guard let current = cycle, current.id == cycleID else { return }
        cycle = nil
        current.timeoutTask.cancel()
        guard current.backgroundTaskID != .invalid else { return }
        endBackgroundTask(current.backgroundTaskID)
    }
}

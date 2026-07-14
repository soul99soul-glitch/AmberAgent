import Foundation
import UIKit

@MainActor
final class NovelWorkspaceLifecycleCoordinator {
    typealias BackgroundCompletion = @MainActor () async -> Void
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
        var completionTask: Task<Void, Never>?
        var isExpiring: Bool
    }

    private let now: @MainActor () -> Date
    private let beginBackgroundTask: BeginBackgroundTask
    private let endBackgroundTask: EndBackgroundTask
    private var cycle: Cycle?

    init(
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
        self.now = now
        self.beginBackgroundTask = beginBackgroundTask
        self.endBackgroundTask = endBackgroundTask
    }

    func enterBackground(
        waitForCompletion: @escaping BackgroundCompletion,
        interrupt: @escaping BackgroundInterruption
    ) {
        guard cycle == nil else { return }

        let cycleID = UUID()
        let expirationHandler: @Sendable () -> Void = { [weak self] in
            Task { @MainActor in
                self?.expire(cycleID: cycleID)
            }
        }
        let backgroundTaskID = beginBackgroundTask(
            "Amber Novel Background Generation",
            expirationHandler
        )
        cycle = Cycle(
            id: cycleID,
            backgroundTaskID: backgroundTaskID,
            interruption: interrupt,
            completionTask: nil,
            isExpiring: false
        )

        guard backgroundTaskID != .invalid else {
            expire(cycleID: cycleID)
            return
        }
        let completionTask = Task { @MainActor [weak self] in
            await waitForCompletion()
            guard !Task.isCancelled else { return }
            self?.finish(cycleID: cycleID)
        }
        cycle?.completionTask = completionTask
    }

    func enterForeground() {
        guard let cycle else { return }
        finish(cycleID: cycle.id)
    }

    private func expire(cycleID: UUID) {
        guard var current = cycle,
              current.id == cycleID,
              !current.isExpiring else { return }
        current.isExpiring = true
        current.completionTask?.cancel()
        current.completionTask = nil
        cycle = current

        Task { @MainActor [weak self] in
            guard let self else { return }
            await current.interruption(now())
            finish(cycleID: cycleID)
        }
    }

    private func finish(cycleID: UUID) {
        guard let current = cycle, current.id == cycleID else { return }
        cycle = nil
        current.completionTask?.cancel()
        guard current.backgroundTaskID != .invalid else { return }
        endBackgroundTask(current.backgroundTaskID)
    }
}

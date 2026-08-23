import Foundation
import UIKit

/// 小说工作区退到后台时的执行权持有者。
///
/// 执行权本身不归它管——统一走 `BackgroundGenerationKeepAlive`；它只负责
/// 「什么时候拿、什么时候还、被收走了怎么收口」这套时序。
@MainActor
final class NovelWorkspaceLifecycleCoordinator {
    typealias BackgroundCompletion = @MainActor () async -> Void
    typealias BackgroundInterruption = @MainActor (Date) async -> Void
    typealias BeginKeepAlive = @MainActor (
        _ leaseId: String,
        _ onExpire: @escaping () -> Void
    ) -> Void
    typealias EndKeepAlive = @MainActor (_ leaseId: String) -> Void
    typealias HasProtectedGenerationLease = @MainActor () -> Bool

    private struct Cycle {
        let id: UUID
        let leaseId: String
        let interruption: BackgroundInterruption
        var completionTask: Task<Void, Never>?
        var interruptionTask: Task<Void, Never>?
        var isExpiring: Bool
    }

    private let now: @MainActor () -> Date
    private let beginKeepAlive: BeginKeepAlive
    private let endKeepAlive: EndKeepAlive
    private let hasProtectedGenerationLease: HasProtectedGenerationLease
    private var cycle: Cycle?

    init(
        now: @escaping @MainActor () -> Date = Date.init,
        beginKeepAlive: @escaping BeginKeepAlive = { leaseId, onExpire in
            BackgroundGenerationKeepAlive.shared.begin(
                leaseId,
                title: "Amber 小说创作中",
                subtitle: "后台生成",
                onExpire: onExpire,
                submitSystemTask: false
            )
        },
        endKeepAlive: @escaping EndKeepAlive = { leaseId in
            BackgroundGenerationKeepAlive.shared.end(leaseId)
        },
        hasProtectedGenerationLease: @escaping HasProtectedGenerationLease = {
            BackgroundGenerationKeepAlive.shared.activeLeaseIds.contains(
                where: isProtectedNovelBackgroundLeaseID
            )
        }
    ) {
        self.now = now
        self.beginKeepAlive = beginKeepAlive
        self.endKeepAlive = endKeepAlive
        self.hasProtectedGenerationLease = hasProtectedGenerationLease
    }

    func enterBackground(
        waitForCompletion: @escaping BackgroundCompletion,
        interrupt: @escaping BackgroundInterruption
    ) {
        guard cycle == nil else { return }

        // Generation acquires its own continued-processing lease while the user
        // action starts. Do not create the legacy scene-phase short lease beside it:
        // that second lease would expire after roughly 30 seconds and interrupt a
        // run that already has a valid system task.
        guard !hasProtectedGenerationLease() else { return }

        let cycleID = UUID()
        let leaseId = "novel-\(cycleID.uuidString)"
        cycle = Cycle(
            id: cycleID,
            leaseId: leaseId,
            interruption: interrupt,
            completionTask: nil,
            interruptionTask: nil,
            isExpiring: false
        )
        // 先建好 cycle 再拿执行权：begin 有可能同步回调 onExpire（提交失败等），
        // 那时 expire 必须能查到这一轮，否则收口逻辑整个丢掉。
        beginKeepAlive(leaseId) { [weak self] in
            Task { @MainActor in
                self?.expire(cycleID: cycleID)
            }
        }

        let completionTask = Task { @MainActor [weak self] in
            await waitForCompletion()
            guard !Task.isCancelled else { return }
            self?.finish(cycleID: cycleID)
        }
        // expire 可能已经把这一轮收掉了，别再把任务塞回一个不存在的 cycle。
        guard cycle?.id == cycleID else {
            completionTask.cancel()
            return
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
        // A protected operation may start just after scenePhase entered background.
        // In that race the late continued-processing lease supersedes this short
        // scene lease; expiring the short lease must not cancel the protected work.
        guard !hasProtectedGenerationLease() else {
            finish(cycleID: cycleID)
            return
        }
        current.isExpiring = true
        current.completionTask?.cancel()
        current.completionTask = nil
        let interruption = current.interruption
        let deadline = now()
        let interruptionTask = Task { @MainActor [weak self] in
            guard let self else { return }
            guard !Task.isCancelled else { return }
            await interruption(deadline)
            guard !Task.isCancelled else { return }
            finish(cycleID: cycleID)
        }
        current.interruptionTask = interruptionTask
        cycle = current
    }

    private func finish(cycleID: UUID) {
        guard let current = cycle, current.id == cycleID else { return }
        cycle = nil
        current.completionTask?.cancel()
        current.interruptionTask?.cancel()
        endKeepAlive(current.leaseId)
    }
}

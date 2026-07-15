@preconcurrency import ActivityKit
import Foundation

enum IOSExecutionPreferenceKeys {
    static let liveActivity = "app.amber.ios.execution.liveActivity"
}

@MainActor
final class AgentLiveActivityController {
    static let shared = AgentLiveActivityController()

    private var activity: Activity<AgentActivityAttributes>?
    private var currentRunId: String?
    private var lastPresentation: AgentActivityPresentation?
    private var lastUpdateAt: Date?
    private var endingActivityIDs: Set<String> = []

    private init() {}

    var activitiesEnabled: Bool {
        ActivityAuthorizationInfo().areActivitiesEnabled
    }

    func start(
        runId: String,
        conversationId: String?,
        presentation: AgentActivityPresentation
    ) {
        guard activitiesEnabled else { return }
        reconcileExistingActivities(
            for: runId,
            conversationId: conversationId
        )

        if activity?.attributes.runId == runId {
            currentRunId = runId
            Task {
                await update(runId: runId, presentation: presentation, force: true)
            }
            return
        }

        requestActivity(
            runId: runId,
            conversationId: conversationId,
            presentation: presentation
        )
    }

    func update(
        runId: String,
        presentation: AgentActivityPresentation,
        force: Bool = false,
        minimumInterval: TimeInterval = 1.5
    ) async {
        guard let activity,
              currentRunId == runId,
              activity.attributes.runId == runId else { return }

        let now = Date()
        if !force,
           let lastUpdateAt,
           now.timeIntervalSince(lastUpdateAt) < minimumInterval,
           presentation == lastPresentation {
            return
        }

        lastPresentation = presentation
        self.lastUpdateAt = now
        await activity.update(Self.content(presentation: presentation, now: now))
    }

    func end(
        runId: String,
        presentation: AgentActivityPresentation,
        dismissalDelay: TimeInterval? = nil
    ) async {
        guard let activity,
              currentRunId == runId,
              activity.attributes.runId == runId else { return }
        guard endingActivityIDs.insert(activity.id).inserted else { return }

        let terminalPresentation = presentation.preservingKind(from: lastPresentation)
        lastPresentation = terminalPresentation
        lastUpdateAt = Date()
        self.activity = nil
        currentRunId = nil

        await Self.end(
            activity: activity,
            presentation: terminalPresentation,
            dismissalDelay: dismissalDelay ?? AgentActivityLifecyclePolicy
                .lockScreenDismissalDelay(for: terminalPresentation.phase)
        )
        endingActivityIDs.remove(activity.id)
    }

    func stopCurrent(dismissalDelay: TimeInterval = 1) async {
        var activitiesToEnd = Activity<AgentActivityAttributes>.activities
        if let activity,
           !activitiesToEnd.contains(where: { $0.id == activity.id }) {
            activitiesToEnd.append(activity)
        }

        let cancelledPresentation = AgentActivityPresentation(
            kind: lastPresentation?.kind ?? .response,
            phase: .cancelled,
            stage: .cancelled,
            action: nil
        )
        endingActivityIDs.formUnion(activitiesToEnd.map(\.id))
        clearCurrentActivity()

        for activity in activitiesToEnd {
            await Self.end(
                activity: activity,
                presentation: cancelledPresentation,
                dismissalDelay: dismissalDelay
            )
            endingActivityIDs.remove(activity.id)
        }
    }

    func restoreExistingActivity(ownedRunIds: Set<String>) {
        let existing = Activity<AgentActivityAttributes>.activities
        let candidates = existing.filter {
            isAdoptable($0) && AgentActivityLifecyclePolicy.shouldRestore(
                runId: $0.attributes.runId,
                ownedRunIds: ownedRunIds,
                activityState: $0.activityState
            )
        }
        guard let restored = candidates.max(by: {
            $0.content.state.updatedAt < $1.content.state.updatedAt
        }) else {
            clearCurrentActivity()
            for orphan in existing {
                scheduleEnd(activity: orphan, dismissalDelay: 1)
            }
            return
        }

        activity = restored
        currentRunId = restored.attributes.runId
        lastPresentation = restored.content.state.presentation
        lastUpdateAt = restored.content.state.updatedAt

        for duplicate in existing where duplicate.id != restored.id {
            scheduleEnd(activity: duplicate, dismissalDelay: 1)
        }
    }

    @discardableResult
    func adoptExistingActivity(
        runId: String,
        conversationId: String? = nil
    ) -> Bool {
        if let activity,
           isAdoptable(activity),
           activity.attributes.runId == runId,
           conversationId == nil || activity.attributes.conversationId == conversationId {
            currentRunId = runId
            return true
        }

        guard let restored = Activity<AgentActivityAttributes>.activities
            .filter({ candidate in
                isAdoptable(candidate) &&
                    candidate.attributes.runId == runId &&
                    (conversationId == nil || candidate.attributes.conversationId == conversationId)
            })
            .max(by: { $0.content.state.updatedAt < $1.content.state.updatedAt }) else {
            return false
        }

        activity = restored
        currentRunId = restored.attributes.runId
        lastPresentation = restored.content.state.presentation
        lastUpdateAt = restored.content.state.updatedAt
        return true
    }

    func ownsActivity(runId: String, conversationId: String) -> Bool {
        Activity<AgentActivityAttributes>.activities.contains {
            !endingActivityIDs.contains($0.id) &&
                $0.attributes.runId == runId &&
                $0.attributes.conversationId?.caseInsensitiveCompare(conversationId) == .orderedSame
        }
    }

    private func requestActivity(
        runId: String,
        conversationId: String?,
        presentation: AgentActivityPresentation
    ) {
        let now = Date()
        let attributes = AgentActivityAttributes(
            runId: runId,
            conversationId: conversationId,
            startedAt: now
        )

        do {
            activity = try Activity.request(
                attributes: attributes,
                content: Self.content(presentation: presentation, now: now),
                pushType: nil
            )
            currentRunId = runId
            lastPresentation = presentation
            lastUpdateAt = now
        } catch {
            print("[LiveActivity] Failed to start Agent activity: \(error)")
            clearCurrentActivity()
        }
    }

    private func reconcileExistingActivities(
        for runId: String,
        conversationId: String?
    ) {
        let existing = Activity<AgentActivityAttributes>.activities
        activity = existing
            .filter {
                isAdoptable($0) &&
                    $0.attributes.runId == runId &&
                    $0.attributes.conversationId == conversationId
            }
            .max(by: { $0.content.state.updatedAt < $1.content.state.updatedAt })
        currentRunId = activity?.attributes.runId
        lastPresentation = activity?.content.state.presentation
        lastUpdateAt = activity?.content.state.updatedAt

        for staleActivity in existing where staleActivity.id != activity?.id {
            scheduleEnd(activity: staleActivity, dismissalDelay: 1)
        }
    }

    private func isAdoptable(_ candidate: Activity<AgentActivityAttributes>) -> Bool {
        guard !endingActivityIDs.contains(candidate.id) else { return false }
        return candidate.activityState == .active || candidate.activityState == .stale
    }

    private func scheduleEnd(
        activity: Activity<AgentActivityAttributes>,
        dismissalDelay: TimeInterval
    ) {
        guard endingActivityIDs.insert(activity.id).inserted else { return }
        if self.activity?.id == activity.id {
            clearCurrentActivity()
        }
        let presentation = AgentActivityPresentation(
            kind: activity.content.state.presentation.kind,
            phase: .cancelled,
            stage: .cancelled,
            action: nil
        )
        Task { [weak self] in
            await Self.end(
                activity: activity,
                presentation: presentation,
                dismissalDelay: dismissalDelay
            )
            self?.endingActivityIDs.remove(activity.id)
        }
    }

    private func clearCurrentActivity() {
        activity = nil
        currentRunId = nil
        lastPresentation = nil
        lastUpdateAt = nil
    }

    private static func content(
        presentation: AgentActivityPresentation,
        now: Date
    ) -> ActivityContent<AgentActivityAttributes.ContentState> {
        ActivityContent(
            state: .init(presentation: presentation, updatedAt: now),
            staleDate: AgentActivityLifecyclePolicy.staleDate(
                for: presentation.phase,
                now: now
            ),
            relevanceScore: AgentActivityLifecyclePolicy.relevanceScore(
                for: presentation.phase
            )
        )
    }

    private static func end(
        activity: Activity<AgentActivityAttributes>,
        presentation: AgentActivityPresentation,
        dismissalDelay: TimeInterval
    ) async {
        let now = Date()
        // Ending removes the task from Dynamic Island immediately. The policy
        // below only controls how long its terminal card remains on Lock Screen.
        await activity.end(
            Self.content(presentation: presentation, now: now),
            dismissalPolicy: .after(now.addingTimeInterval(dismissalDelay))
        )
    }
}

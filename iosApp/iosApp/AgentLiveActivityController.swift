@preconcurrency import ActivityKit
import Foundation

@MainActor
final class AgentLiveActivityController {
    static let shared = AgentLiveActivityController()

    private var activity: Activity<AgentActivityAttributes>?
    private var currentRunId: String?
    private var lastPresentation: AgentActivityPresentation?
    private var lastUpdateAt: Date?

    private init() {}

    var activitiesEnabled: Bool {
        ActivityAuthorizationInfo().areActivitiesEnabled
    }

    func start(runId: String, presentation: AgentActivityPresentation) {
        guard activitiesEnabled else { return }

        reconcileExistingActivities(for: runId, replacingWith: presentation)

        if activity?.attributes.runId == runId {
            currentRunId = runId
            Task {
                await update(runId: runId, presentation: presentation, force: true)
            }
            return
        }

        if let oldActivity = activity {
            let oldPresentation = lastPresentation ?? .cancelled(toolTitle: presentation.toolTitle)
            activity = nil
            currentRunId = nil
            Task {
                await Self.end(
                    activity: oldActivity,
                    presentation: .cancelled(toolTitle: oldPresentation.toolTitle),
                    dismissalDelay: 1
                )
            }
        }

        requestActivity(runId: runId, presentation: presentation)
    }

    func update(
        runId: String,
        presentation: AgentActivityPresentation,
        force: Bool = false,
        minimumInterval: TimeInterval = 1.5
    ) async {
        guard let activity, currentRunId == runId, activity.attributes.runId == runId else { return }
        let now = Date()
        if !force,
           let lastUpdateAt,
           now.timeIntervalSince(lastUpdateAt) < minimumInterval,
           presentation.phase == lastPresentation?.phase {
            return
        }

        lastPresentation = presentation
        lastUpdateAt = now
        await activity.update(ActivityContent(state: .init(presentation: presentation), staleDate: nil))
    }

    func end(runId: String, presentation: AgentActivityPresentation, dismissalDelay: TimeInterval = 6) async {
        guard let activity, currentRunId == runId, activity.attributes.runId == runId else { return }
        lastPresentation = presentation
        lastUpdateAt = Date()
        self.activity = nil
        currentRunId = nil

        await Self.end(activity: activity, presentation: presentation, dismissalDelay: dismissalDelay)
    }

    private func requestActivity(runId: String, presentation: AgentActivityPresentation) {
        let attributes = AgentActivityAttributes(runId: runId, startedAt: Date())
        do {
            activity = try Activity.request(
                attributes: attributes,
                content: ActivityContent(state: .init(presentation: presentation), staleDate: nil),
                pushType: nil
            )
            currentRunId = runId
            lastPresentation = presentation
            lastUpdateAt = Date()
        } catch {
            print("[LiveActivity] Failed to start Agent activity: \(error)")
            activity = nil
            currentRunId = nil
            lastPresentation = nil
            lastUpdateAt = nil
        }
    }

    private func reconcileExistingActivities(
        for runId: String,
        replacingWith presentation: AgentActivityPresentation
    ) {
        let existing = Activity<AgentActivityAttributes>.activities
        activity = existing.first { $0.attributes.runId == runId }
        currentRunId = activity?.attributes.runId

        let staleActivities = existing.filter { $0.attributes.runId != runId }
        guard !staleActivities.isEmpty else { return }
        for staleActivity in staleActivities {
            Task {
                await Self.end(
                    activity: staleActivity,
                    presentation: .cancelled(toolTitle: presentation.toolTitle),
                    dismissalDelay: 1
                )
            }
        }
    }

    private static func end(
        activity: Activity<AgentActivityAttributes>,
        presentation: AgentActivityPresentation,
        dismissalDelay: TimeInterval
    ) async {
        await activity.end(
            ActivityContent(state: .init(presentation: presentation), staleDate: nil),
            dismissalPolicy: .after(Date().addingTimeInterval(dismissalDelay))
        )
    }
}

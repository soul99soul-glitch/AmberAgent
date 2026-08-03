import XCTest
import ActivityKit
@testable import iosApp

final class AgentActivityPresentationTests: XCTestCase {
    func testIndeterminateAgentWorkHasNoProgress() {
        let presentation = AgentActivityPresentation.generatingResponse(
            modelName: "private-model-name"
        )

        XCTAssertEqual(presentation.kind, .response)
        XCTAssertEqual(presentation.phase, .running)
        XCTAssertEqual(presentation.stage, .generating)
        XCTAssertEqual(presentation.metric, .none)
        XCTAssertNil(presentation.progressFraction)
        XCTAssertFalse(presentation.showsProgressRing)
        XCTAssertFalse(String(describing: presentation).contains("private-model-name"))
    }

    func testResponseLifecycleStartsConnectingThenTracksReasoningAndText() {
        XCTAssertEqual(AgentActivityResponseStagePolicy.initialStage, .preparing)
        XCTAssertNil(AgentActivityResponseStagePolicy.updatedStage(
            hasReasoningDelta: false,
            hasTextDelta: false
        ))
        XCTAssertEqual(
            AgentActivityResponseStagePolicy.updatedStage(
                hasReasoningDelta: true,
                hasTextDelta: false
            ),
            .thinking
        )
        XCTAssertEqual(
            AgentActivityResponseStagePolicy.updatedStage(
                hasReasoningDelta: true,
                hasTextDelta: true
            ),
            .thinking,
            "同一 delta 仍有 reasoning 内容时，应与 Chat 的 open reasoning 状态保持一致"
        )
        XCTAssertEqual(
            AgentActivityResponseStagePolicy.nextPublishedStage(
                current: .preparing,
                candidate: .thinking
            ),
            .thinking
        )
        XCTAssertEqual(
            AgentActivityResponseStagePolicy.nextPublishedStage(
                current: .thinking,
                candidate: .generating
            ),
            .generating
        )
        XCTAssertNil(
            AgentActivityResponseStagePolicy.nextPublishedStage(
                current: .generating,
                candidate: .thinking
            ),
            "交错 reasoning chunk 不得把系统状态从生成态拉回思考态"
        )
    }

    func testDisplayStageNormalizesPhaseOverridesForEverySystemSurface() {
        XCTAssertEqual(
            AgentActivityPresentation.response(stage: .thinking)
                .displayStage(isStale: false),
            .thinking
        )
        XCTAssertEqual(
            AgentActivityPresentation.response(stage: .thinking)
                .displayStage(isStale: true),
            .stale
        )
        XCTAssertEqual(
            AgentActivityPresentation.waitingForUser().displayStage(isStale: false),
            .waitingForConfirmation
        )
        XCTAssertEqual(
            AgentActivityPresentation.completed().displayStage(isStale: false),
            .completed
        )
    }

    func testMeasurableWorkUsesOnlyRealNumeratorAndDenominator() throws {
        let presentation = AgentActivityPresentation.measurablePreview(
            kind: .document,
            completed: 12,
            total: 30,
            unit: .item
        )

        XCTAssertEqual(
            presentation.metric,
            .progress(completed: 12, total: 30, unit: .item)
        )
        XCTAssertEqual(
            try XCTUnwrap(presentation.progressFraction),
            0.4,
            accuracy: 0.000_001
        )
        XCTAssertTrue(presentation.showsProgressRing)
        XCTAssertEqual(presentation.percentValue, 40)
    }

    func testInvalidMetricsDegradeToNoMetric() {
        XCTAssertEqual(
            AgentActivityMetric.validatedProgress(completed: -1, total: 0, unit: .item),
            .none
        )
        XCTAssertEqual(
            AgentActivityMetric.validatedProgress(completed: 31, total: 30, unit: .item),
            .none
        )
        XCTAssertEqual(
            AgentActivityMetric.count(completed: -1, unit: .source).validated,
            .none
        )
    }

    func testToolFactoryMapsRawNamesToFinitePublicSemantics() {
        XCTAssertEqual(
            AgentActivityPresentation.runningTool(toolName: "search_web").kind,
            .research
        )
        XCTAssertEqual(
            AgentActivityPresentation.runningTool(toolName: "scrape_web").stage,
            .readingWeb
        )
        XCTAssertEqual(
            AgentActivityPresentation.runningTool(toolName: "generate_image").kind,
            .imageGeneration
        )

        let privateTool = AgentActivityPresentation.runningTool(
            toolName: "curl https://internal.example.com?token=secret"
        )
        XCTAssertEqual(privateTool.kind, .workflow)
        XCTAssertEqual(privateTool.stage, .runningTool)
        XCTAssertFalse(String(describing: privateTool).contains("internal.example.com"))
        XCTAssertFalse(String(describing: privateTool).contains("secret"))
    }

    func testStateFactoriesSelectOnlySafeActions() {
        XCTAssertEqual(AgentActivityPresentation.waitingForUser().action, .openConfirmation)
        XCTAssertEqual(AgentActivityPresentation.waitingForUser(kind: .memory).kind, .memory)
        XCTAssertEqual(AgentActivityPresentation.waitingForUser(kind: .research).kind, .research)
        XCTAssertEqual(AgentActivityPresentation.waitingForUser(kind: .document).kind, .document)
        XCTAssertEqual(AgentActivityPresentation.completed().action, .viewResult)
        XCTAssertEqual(AgentActivityPresentation.failed().action, .openTask)
        XCTAssertEqual(AgentActivityPresentation.selectedFileReadFailed.action, .openTask)
        XCTAssertNil(AgentActivityPresentation.cancelled().action)
    }

    func testLockScreenDoesNotDuplicateTheWholeCardOpenTaskAction() {
        XCTAssertFalse(AgentActivityAction.openTask.showsLockScreenLabel)
        XCTAssertTrue(AgentActivityAction.openConfirmation.showsLockScreenLabel)
        XCTAssertTrue(AgentActivityAction.viewResult.showsLockScreenLabel)
    }

    func testTerminalPresentationPreservesTheRunKind() {
        let running = AgentActivityPresentation.runningTool(toolName: "generate_image")

        XCTAssertEqual(
            AgentActivityPresentation.failed().preservingKind(from: running).kind,
            .imageGeneration
        )
        XCTAssertEqual(
            AgentActivityPresentation.cancelled().preservingKind(from: running).kind,
            .imageGeneration
        )
    }

    func testStaleDisplayOverridesOnlyActivePhases() {
        XCTAssertEqual(
            AgentActivityPresentation.defaultRunning.displayPhase(isStale: true),
            .stale
        )
        XCTAssertEqual(
            AgentActivityPresentation.reconnecting().displayPhase(isStale: true),
            .stale
        )
        XCTAssertEqual(
            AgentActivityPresentation.completed().displayPhase(isStale: true),
            .completed
        )
    }

    func testStaticSystemMarkersDistinguishEveryNonRunningPhase() {
        let markers = [
            AgentActivityPresentation.reconnecting().displaySymbolName(isStale: false),
            AgentActivityPresentation.waitingForUser().displaySymbolName(isStale: false),
            AgentActivityPresentation.defaultRunning.displaySymbolName(isStale: true),
            AgentActivityPresentation.completed().displaySymbolName(isStale: false),
            AgentActivityPresentation.failed().displaySymbolName(isStale: false),
            AgentActivityPresentation.cancelled().displaySymbolName(isStale: false)
        ]

        XCTAssertEqual(Set(markers).count, markers.count)
    }

    func testLifecyclePolicyMakesOnlyActiveWorkStale() {
        let now = Date(timeIntervalSince1970: 1_000)

        XCTAssertEqual(
            AgentActivityLifecyclePolicy.staleDate(for: .running, now: now),
            now.addingTimeInterval(180)
        )
        XCTAssertEqual(
            AgentActivityLifecyclePolicy.staleDate(for: .reconnecting, now: now),
            now.addingTimeInterval(60)
        )
        XCTAssertNil(AgentActivityLifecyclePolicy.staleDate(for: .waitingForUser, now: now))
        XCTAssertGreaterThan(
            AgentActivityLifecyclePolicy.relevanceScore(for: .waitingForUser),
            AgentActivityLifecyclePolicy.relevanceScore(for: .running)
        )
    }

    func testRestoreRequiresBothDurableOwnershipAndUpdatableActivityState() {
        let ownedRunIds: Set<String> = ["background-run"]

        XCTAssertTrue(AgentActivityLifecyclePolicy.shouldRestore(
            runId: "background-run",
            ownedRunIds: ownedRunIds,
            activityState: .active
        ))
        XCTAssertTrue(AgentActivityLifecyclePolicy.shouldRestore(
            runId: "background-run",
            ownedRunIds: ownedRunIds,
            activityState: .stale
        ))
        XCTAssertFalse(AgentActivityLifecyclePolicy.shouldRestore(
            runId: "orphan-run",
            ownedRunIds: ownedRunIds,
            activityState: .active
        ))
        XCTAssertFalse(AgentActivityLifecyclePolicy.shouldRestore(
            runId: "background-run",
            ownedRunIds: ownedRunIds,
            activityState: .ended
        ))
    }

    func testTerminalDismissalKeepsFailuresLongerThanCompletions() {
        XCTAssertEqual(
            AgentActivityLifecyclePolicy.lockScreenDismissalDelay(for: .completed),
            20
        )
        XCTAssertEqual(
            AgentActivityLifecyclePolicy.lockScreenDismissalDelay(for: .failed),
            60
        )
        XCTAssertEqual(
            AgentActivityLifecyclePolicy.lockScreenDismissalDelay(for: .cancelled),
            6
        )
    }

    func testElapsedTimerFreezesOnlyForTerminalPhases() {
        let updatedAt = Date(timeIntervalSince1970: 1_120)

        XCTAssertNil(
            AgentActivityElapsedTimePolicy.frozenEndDate(
                for: .running,
                updatedAt: updatedAt
            )
        )
        XCTAssertNil(
            AgentActivityElapsedTimePolicy.frozenEndDate(
                for: .waitingForUser,
                updatedAt: updatedAt
            )
        )
        XCTAssertEqual(
            AgentActivityElapsedTimePolicy.frozenEndDate(
                for: .completed,
                updatedAt: updatedAt
            ),
            updatedAt
        )
        XCTAssertEqual(
            AgentActivityElapsedTimePolicy.frozenEndDate(
                for: .failed,
                updatedAt: updatedAt
            ),
            updatedAt
        )
        XCTAssertEqual(
            AgentActivityElapsedTimePolicy.frozenEndDate(
                for: .cancelled,
                updatedAt: updatedAt
            ),
            updatedAt
        )
    }

    func testRestoreRetainsNewestActivityForEveryOwnedRun() {
        let candidates = [
            AgentActivityOwnershipCandidate(
                id: "run-a-old",
                runId: "run-a",
                updatedAt: Date(timeIntervalSince1970: 10)
            ),
            AgentActivityOwnershipCandidate(
                id: "run-a-new",
                runId: "run-a",
                updatedAt: Date(timeIntervalSince1970: 20)
            ),
            AgentActivityOwnershipCandidate(
                id: "run-b",
                runId: "run-b",
                updatedAt: Date(timeIntervalSince1970: 15)
            ),
            AgentActivityOwnershipCandidate(
                id: "orphan",
                runId: "orphan-run",
                updatedAt: Date(timeIntervalSince1970: 30)
            ),
        ]

        XCTAssertEqual(
            AgentActivityOwnershipPolicy.retainedActivityIDs(
                from: candidates,
                ownedRunIds: ["run-a", "run-b"]
            ),
            ["run-a-new", "run-b"]
        )
    }

    func testOrbAnimationDurationUsesResolvedStateSpeedWithinWidgetLimit() {
        for state in OrbState.allCases {
            let speed = orbResolvePreset(state, .small).speed
            let duration = AgentActivityOrbAnimationTiming.duration(speed: speed)

            XCTAssertEqual(
                duration,
                min(2, (2 * Double.pi) / speed),
                accuracy: 0.000_001,
                "\(state)"
            )
            XCTAssertGreaterThan(duration, 0, "\(state)")
            XCTAssertLessThanOrEqual(duration, 2, "\(state)")
        }
    }

    func testNewPayloadDoesNotEncodeLegacyTextFields() throws {
        let data = try JSONEncoder().encode(AgentActivityPresentation.defaultRunning)
        let json = try XCTUnwrap(String(data: data, encoding: .utf8))

        XCTAssertFalse(json.contains("statusText"))
        XCTAssertFalse(json.contains("toolTitle"))
        XCTAssertFalse(json.contains("steps"))
    }
}

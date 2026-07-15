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

    func testNewPayloadDoesNotEncodeLegacyTextFields() throws {
        let data = try JSONEncoder().encode(AgentActivityPresentation.defaultRunning)
        let json = try XCTUnwrap(String(data: data, encoding: .utf8))

        XCTAssertFalse(json.contains("statusText"))
        XCTAssertFalse(json.contains("toolTitle"))
        XCTAssertFalse(json.contains("steps"))
    }
}

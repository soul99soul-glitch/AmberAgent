import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Phase 2 Wave C: independent evaluator tests (§12 / §13.1 / §15 Phase 2 /
/// §18.3). Uses REAL components: the real `IOSRecipeValidator`, the real
/// zero-write `IOSRecipeFileStore.prepareRecipe` canonicalize+hash, the real
/// `IOSRecipeRunner` with a scripted primitive executor built from each case's
/// fixtures, and the real Room-backed `IOSAgentRunLedger` on an isolated
/// database (`IosDatabaseFactory.shared.createDatabase(atFilePath:)` — the temp
/// directory is created FIRST via `FileManager.default.createDirectory`, Room
/// does not create parent directories). No source-string anchors: every
/// assertion compares decoded/re-encoded data or canonical bytes.
///
/// Coverage (§15 Phase 2 / §12):
///   - scripted-only all-green suite → manualJudgmentRequired（Slice A：无确定
///     性任务 oracle 不自动晋升；oracle-backed promote 见
///     IOSEvolutionWorkspaceOracleTests）;
///   - static failure → typed reject without a report;
///   - scripted failure replay unfixed → 事实如实记录 + oracle 缺失降级，
///     不被 fixture 误杀为 reject（oracle-backed reject 见 oracle 测试）;
///   - protected regression → hard reject even when failure replay improves
///     (§15 Phase 2 acceptance 3);
///   - no sealed holdout → manualJudgmentRequired, never auto-promote (§12.1);
///   - sealed isolation: proposer view carries no sealed content (I-12);
///   - one-byte candidate change → `report.matches(candidateHash:)` fails;
///   - budget exhaustion → typed terminal (§18.3);
///   - deterministic tier catches a binding error, an error-propagation
///     mismatch and a missing ledger record.
@MainActor
final class IOSArtifactEvaluatorTests: XCTestCase {
    private var tempDirs: [URL] = []
    private var databases: [AgentRuntimeDatabase] = []

    override func tearDown() async throws {
        databases.removeAll()
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    // MARK: - All-green suite（scripted-only）→ 无 oracle 不自动晋升

    /// Slice A 契约变更：scripted fixture 只能证明「脚本自洽」，不能证明
    /// 「真实修复」。全绿但无确定性任务 oracle 的套件 ⇒ 候选只产出草稿 +
    /// 报告，recommendation = manualJudgmentRequired（永不 promote）。
    /// oracle-backed 全绿 ⇒ promote 的覆盖在 IOSEvolutionWorkspaceOracleTests。
    func testScriptedAllGreenSuiteDowngradesToManualJudgmentWithoutOracle() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        let data = try recipeData()
        let hash = try candidateHash(root: root, data: data)
        let suite = allGreenSuite()

        let outcome = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: hash, suite: suite
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }

        XCTAssertEqual(report.candidateHash, hash, "report must bind the exact evaluated bytes")
        XCTAssertEqual(report.evaluatorVersion, IOSArtifactEvaluator.evaluatorVersion)
        XCTAssertEqual(report.suiteHash, suite.suiteHash)
        XCTAssertEqual(report.recommendation, .manualJudgmentRequired,
                       "scripted-only 全绿不得 promote（无确定性任务 oracle）")
        XCTAssertEqual(report.protectedRegressions, 0)
        XCTAssertEqual(report.unresolvedRisks, ["no_deterministic_task_oracle:case:replay-1"],
                       "oracle 缺失必须显式标注为未决风险")
        XCTAssertEqual(report.skippedTiers, [.llmJudge, .stochasticRepeat],
                       "v1 honestly records the tiers it does not implement")
        XCTAssertEqual(report.results.count, 3)
        XCTAssertTrue(report.results.allSatisfy(\.passed),
                      "case 结果仍如实记录（全过），降级只影响晋升结论")

        // proposerView wiring: refs + full suite hash + sealed boolean.
        let view = suite.proposerView
        XCTAssertEqual(view.suiteId, "suite-green")
        XCTAssertEqual(view.suiteHash, report.suiteHash)
        XCTAssertEqual(view.failureReplayCaseRefs, ["case:replay-1"])
        XCTAssertEqual(view.protectedSuccessCaseRefs, ["case:protected-1"])
        XCTAssertTrue(view.hasSealedHoldout)

        // Immutable report binds exactly this candidate.
        XCTAssertTrue(report.matches(candidateHash: hash))
        XCTAssertFalse(report.matches(candidateHash: "deadbeef"))
    }

    // MARK: - Static tier

    func testStaticFailureIsTypedRejectionWithoutReport() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        let data = try recipeData(summarizeTool: "no_such_tool")

        let outcome = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: "unused", suite: allGreenSuite()
        )
        guard case .staticRejected(let issues) = outcome else {
            return XCTFail("expected staticRejected, got \(outcome)")
        }
        XCTAssertTrue(issues.contains { $0.code == .unknownTool },
                      "static tier must run the full real validator: \(issues)")
    }

    // MARK: - Failure replay: scripted-only 失败如实记录但不误杀候选

    /// Slice A 契约变更：scripted replay 的 fixture 按 ToolId 永远抛旧错，
    /// 修好的候选只要仍调用同一工具就必然失败——据此 reject 是 fixture 误杀。
    /// 无 oracle ⇒ 失败事实如实记录 + 降级 manualJudgmentRequired；
    /// oracle-backed 的「未修复 ⇒ reject」覆盖在 IOSEvolutionWorkspaceOracleTests。
    func testScriptedFailureReplayUnfixedDowngradesToManualJudgmentWithoutOracle() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        // v1 binds ${step.fetch.output.title}: statically valid, but the replay
        // fixture's scrape_web output has no "title" — the original failure
        // reproduces exactly (completed == original completed → NOT narrowed).
        let data = try recipeData(textBinding: "${step.fetch.output.title}")
        // Protected/sealed fixtures DO provide "title", so v1 still passes
        // them — the replay-tier failure surface is reported honestly.
        let suite = IOSEvaluationSuite(
            suiteId: "suite-unfixed",
            failureReplayCases: [replayCase()],
            protectedSuccessCases: [protectedCaseLoose()],
            sealedHoldoutCases: [sealedCase()]
        )

        let outcome = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: try candidateHash(root: root, data: data),
            suite: suite
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        XCTAssertEqual(report.recommendation, .manualJudgmentRequired,
                       "scripted replay 失败不能当 reject 证据（fixture 永远抛旧错）")
        XCTAssertEqual(report.protectedRegressions, 0,
                       "protected/sealed cases pass — no regression signal")
        XCTAssertEqual(report.unresolvedRisks, ["no_deterministic_task_oracle:case:replay-1"])

        let replay = try XCTUnwrap(report.results.first { $0.caseId == "case:replay-1" })
        XCTAssertFalse(replay.passed)
        XCTAssertEqual(replay.failureCode, .expectedSuccessButFailed)
        XCTAssertEqual(replay.observedOutcome.failedStepId, "summarize")
        XCTAssertEqual(replay.observedOutcome.failedErrorKind, .argumentBinding)
        XCTAssertEqual(replay.observedOutcome.completedStepIds, ["fetch"],
                       "same failure surface as the original — not narrowed")

        XCTAssertTrue(report.results.filter { $0.caseId != "case:replay-1" }.allSatisfy(\.passed))
    }

    // MARK: - §15 Phase 2 acceptance 3: protected regression hard-rejects

    func testProtectedRegressionHardRejectsEvenWhenFailureReplayImproves() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        // v3 FIXES the replay binding (text) — but changes summarize lang, a
        // key-assertion regression on the protected case.
        let data = try recipeData(textBinding: "${step.fetch.output.text}", lang: "en-US")
        let suite = allGreenSuite()

        let outcome = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: try candidateHash(root: root, data: data),
            suite: suite
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        let replay = try XCTUnwrap(report.results.first { $0.caseId == "case:replay-1" })
        XCTAssertTrue(replay.passed, "failure replay IMPROVED (fixed) — must not block by itself")

        let protected = try XCTUnwrap(report.results.first { $0.caseId == "case:protected-1" })
        XCTAssertFalse(protected.passed)
        XCTAssertEqual(protected.failureCode, .stepCallArgumentMismatch)

        XCTAssertEqual(report.protectedRegressions, 1)
        XCTAssertEqual(report.recommendation, .reject,
                       "acceptance 3: protected regression → hard reject even when replay improved (I-13)")
    }

    // MARK: - §12.1 no sealed holdout → manual judgment

    func testNoSealedHoldoutDowngradesToManualJudgment() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        let data = try recipeData()
        let suite = IOSEvaluationSuite(
            suiteId: "suite-nosealed",
            failureReplayCases: [replayCase()],
            protectedSuccessCases: [protectedCaseWithFullArgs()],
            sealedHoldoutCases: []
        )

        let outcome = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: try candidateHash(root: root, data: data),
            suite: suite
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        XCTAssertTrue(report.results.allSatisfy(\.passed),
                      "downgrade must happen even when every case passes (§12.1)")
        XCTAssertEqual(report.recommendation, .manualJudgmentRequired,
                       "no sealed holdout → never auto-promote (§12.1)")
        XCTAssertTrue(report.unresolvedRisks.contains("no_sealed_holdout_cases"))
        XCTAssertFalse(suite.proposerView.hasSealedHoldout)
    }

    // MARK: - Sealed isolation (invariant 12)

    func testSealedIsolationProposerViewCarriesNoSealedContent() async throws {
        let secret = "SEALED-HOLDOUT-\(UUID().uuidString)"
        let sealed = IOSEvaluationCase(
            id: "case:sealed-1",
            kind: .sealedHoldout,
            recipeInputs: ["feed_url": .string("https://example.com/sealed.xml")],
            scriptedPrimitives: [
                "scrape_web": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"\#(secret)"}"#),
                "summarize_text": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"密封摘要"}"#),
            ],
            assertions: IOSEvaluationAssertions(
                expectedOutputs: ["summary": .string("密封摘要")],
                expectedError: nil,
                expectedStepCalls: [],
                expectedLedger: []
            ),
            originalFailure: nil
        )
        let suite = IOSEvaluationSuite(
            suiteId: "suite-sealed",
            failureReplayCases: [replayCase()],
            protectedSuccessCases: [protectedCaseWithFullArgs()],
            sealedHoldoutCases: [sealed]
        )

        let view = suite.proposerView
        // The proposer-view TYPE physically has no field that could carry case
        // content (I-12) — only public refs, the full-suite hash and the
        // boolean. Data-level proof: encoding the view's ENTIRE field set must
        // never contain sealed content.
        let encodedView = try JSONSerialization.data(withJSONObject: [
            "suiteId": view.suiteId,
            "suiteHash": view.suiteHash,
            "failureReplayCaseRefs": view.failureReplayCaseRefs,
            "protectedSuccessCaseRefs": view.protectedSuccessCaseRefs,
            "hasSealedHoldout": view.hasSealedHoldout,
        ] as [String: Any])
        let viewText = try XCTUnwrap(String(data: encodedView, encoding: .utf8))
        XCTAssertFalse(viewText.contains(secret),
                       "sealed holdout content must never appear in the proposer view (I-12)")
        XCTAssertEqual(view.failureReplayCaseRefs, ["case:replay-1"])
        XCTAssertEqual(view.protectedSuccessCaseRefs, ["case:protected-1"])
        XCTAssertFalse(view.failureReplayCaseRefs.contains("case:sealed-1"))
        XCTAssertFalse(view.protectedSuccessCaseRefs.contains("case:sealed-1"))
        XCTAssertTrue(view.hasSealedHoldout)

        // suiteHash covers sealed content (candidate bound to the exact suite
        // revision), while the view's public shape stays identical.
        let otherSealed = IOSEvaluationCase(
            id: "case:sealed-1",
            kind: .sealedHoldout,
            recipeInputs: sealed.recipeInputs,
            scriptedPrimitives: [
                "scrape_web": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"\#(secret)-v2"}"#),
                "summarize_text": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"密封摘要"}"#),
            ],
            assertions: sealed.assertions,
            originalFailure: nil
        )
        let otherSuite = IOSEvaluationSuite(
            suiteId: "suite-sealed",
            failureReplayCases: suite.failureReplayCases,
            protectedSuccessCases: suite.protectedSuccessCases,
            sealedHoldoutCases: [otherSealed]
        )
        XCTAssertNotEqual(otherSuite.suiteHash, suite.suiteHash,
                          "suiteHash must cover the sealed region")
        XCTAssertEqual(otherSuite.proposerView.failureReplayCaseRefs, view.failureReplayCaseRefs)
        XCTAssertEqual(otherSuite.proposerView.protectedSuccessCaseRefs, view.protectedSuccessCaseRefs)
        XCTAssertEqual(otherSuite.proposerView.hasSealedHoldout, view.hasSealedHoldout)
    }

    // MARK: - Invariant 5: one-byte change invalidates the report

    func testOneByteCandidateChangeInvalidatesReport() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        let store = IOSRecipeFileStore(baseDirectory: root)
        let packageA = try store.prepareRecipe(recipeJSON: recipeData(version: "1.0.0"))
        let packageB = try store.prepareRecipe(recipeJSON: recipeData(version: "1.0.1"))
        let canonicalA = packageA.candidate.canonicalJSON
        let canonicalB = packageB.candidate.canonicalJSON
        XCTAssertEqual(canonicalA.count, canonicalB.count)
        var diffCount = 0
        for (a, b) in zip(canonicalA, canonicalB) where a != b { diffCount += 1 }
        XCTAssertEqual(diffCount, 1, "fixture candidates must differ by exactly one byte")

        let suite = allGreenSuite()
        let outcomeA = await evaluator.evaluate(
            candidateBytes: canonicalA, expectedCandidateHash: packageA.candidate.hash, suite: suite
        )
        guard case .report(let report) = outcomeA else {
            return XCTFail("expected report, got \(outcomeA)")
        }
        XCTAssertTrue(report.matches(candidateHash: packageA.candidate.hash))
        XCTAssertFalse(report.matches(candidateHash: packageB.candidate.hash),
                       "one-byte candidate change must invalidate the report")

        let outcomeB = await evaluator.evaluate(
            candidateBytes: canonicalB, expectedCandidateHash: packageB.candidate.hash, suite: suite
        )
        guard case .report(let reportB) = outcomeB else {
            return XCTFail("expected report, got \(outcomeB)")
        }
        XCTAssertEqual(reportB.candidateHash, packageB.candidate.hash)
        XCTAssertNotEqual(reportB.reportHash, report.reportHash)
        XCTAssertTrue(reportB.matches(candidateHash: packageB.candidate.hash))
        XCTAssertFalse(reportB.matches(candidateHash: packageA.candidate.hash))
    }

    func testCandidateHashMismatchFailsClosed() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        let data = try recipeData()

        let outcome = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: "claimed-but-wrong", suite: allGreenSuite()
        )
        guard case .candidateHashMismatch(let expected, let actual) = outcome else {
            return XCTFail("expected candidateHashMismatch, got \(outcome)")
        }
        XCTAssertEqual(expected, "claimed-but-wrong")
        XCTAssertEqual(actual, try candidateHash(root: root, data: data))
    }

    // MARK: - Budgets (§18.3)

    func testCaseCountBudgetExhaustionIsTypedTerminal() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        let data = try recipeData()
        let hash = try candidateHash(root: root, data: data)
        let suite = allGreenSuite()

        // A non-positive budget is immediately exhausted — typed terminal,
        // never a silent lowering of the bar.
        let outcomeZero = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: hash, suite: suite,
            budget: IOSEvaluationBudget(maxCaseCount: 0, maxWallClockSeconds: 60)
        )
        guard case .budgetExhausted(.caseCount(let maxZero)) = outcomeZero else {
            return XCTFail("expected budgetExhausted(caseCount), got \(outcomeZero)")
        }
        XCTAssertEqual(maxZero, 0)

        // Exhaustion mid-suite: the evaluator runs the first case, then stops.
        let outcomeOne = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: hash, suite: suite,
            budget: IOSEvaluationBudget(maxCaseCount: 1, maxWallClockSeconds: 60)
        )
        guard case .budgetExhausted(.caseCount(let maxOne)) = outcomeOne else {
            return XCTFail("expected budgetExhausted(caseCount), got \(outcomeOne)")
        }
        XCTAssertEqual(maxOne, 1)
    }

    func testWallClockBudgetExhaustionIsTypedTerminal() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        let data = try recipeData()
        let hash = try candidateHash(root: root, data: data)

        // Immediate guard: a zero wall-clock budget is immediately exhausted.
        let outcomeZero = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: hash, suite: allGreenSuite(),
            budget: IOSEvaluationBudget(maxCaseCount: 64, maxWallClockSeconds: 0)
        )
        guard case .budgetExhausted(.wallClock(let secondsZero)) = outcomeZero else {
            return XCTFail("expected budgetExhausted(wallClock), got \(outcomeZero)")
        }
        XCTAssertEqual(secondsZero, 0)

        // A slow scripted case exceeds a tiny wall-clock budget → typed
        // terminal after the case (checked at case boundaries).
        let slowCase = replayCase(id: "case:replay-slow", scrapeDelaySeconds: 0.3)
        let slowSuite = IOSEvaluationSuite(
            suiteId: "suite-slow",
            failureReplayCases: [slowCase],
            protectedSuccessCases: [],
            sealedHoldoutCases: []
        )
        let outcomeSlow = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: hash, suite: slowSuite,
            budget: IOSEvaluationBudget(maxCaseCount: 64, maxWallClockSeconds: 0.05)
        )
        guard case .budgetExhausted(.wallClock) = outcomeSlow else {
            return XCTFail("expected budgetExhausted(wallClock), got \(outcomeSlow)")
        }
    }

    // MARK: - Deterministic contract tier catches

    func testDeterministicTierCatchesBindingError() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        // v1 binds ${step.fetch.output.title} — statically VALID, but the
        // case's scrape_web fixture output has no "title" → runtime binding
        // failure that only the deterministic tier can see.
        let data = try recipeData(textBinding: "${step.fetch.output.title}")
        let suite = IOSEvaluationSuite(
            suiteId: "suite-binding",
            failureReplayCases: [],
            protectedSuccessCases: [protectedCaseNoTitle()],
            sealedHoldoutCases: []
        )

        let outcome = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: try candidateHash(root: root, data: data),
            suite: suite
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        let result = try XCTUnwrap(report.results.first { $0.caseId == "case:protected-notitle" })
        XCTAssertFalse(result.passed)
        XCTAssertEqual(result.failureCode, .expectedSuccessButFailed)
        XCTAssertEqual(result.observedOutcome.failedStepId, "summarize")
        XCTAssertEqual(result.observedOutcome.failedErrorKind, .argumentBinding)
        XCTAssertEqual(report.protectedRegressions, 1)
        XCTAssertEqual(report.recommendation, .reject)
    }

    func testDeterministicTierCatchesErrorPropagationMismatch() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        // The case declares the failure must propagate from the fetch
        // primitive (stepFailed at fetch), but the candidate fails LATER at
        // summarize with argumentBinding — the propagation contract is broken.
        let data = try recipeData(textBinding: "${step.fetch.output.title}")
        let caseItem = IOSEvaluationCase(
            id: "case:sealed-prop",
            kind: .sealedHoldout,
            recipeInputs: ["feed_url": .string("https://example.com/rss.xml")],
            scriptedPrimitives: [
                "scrape_web": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"body"}"#),
                "summarize_text": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"摘要"}"#),
            ],
            assertions: IOSEvaluationAssertions(
                expectedOutputs: nil,
                expectedError: IOSEvaluationExpectedError(kind: .stepFailed, stepId: "fetch"),
                expectedStepCalls: [],
                expectedLedger: []
            ),
            originalFailure: nil
        )
        let suite = IOSEvaluationSuite(
            suiteId: "suite-prop",
            failureReplayCases: [],
            protectedSuccessCases: [],
            sealedHoldoutCases: [caseItem]
        )

        let outcome = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: try candidateHash(root: root, data: data),
            suite: suite
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        let result = try XCTUnwrap(report.results.first)
        XCTAssertFalse(result.passed)
        XCTAssertEqual(result.failureCode, .expectedErrorNotObserved)
        XCTAssertEqual(result.observedOutcome.failedStepId, "summarize")
        XCTAssertEqual(result.observedOutcome.failedErrorKind, .argumentBinding)
        XCTAssertEqual(report.recommendation, .manualJudgmentRequired)
        XCTAssertTrue(report.unresolvedRisks.contains("sealed_holdout_failed:case:sealed-prop"))
        XCTAssertTrue(report.unresolvedRisks.contains("no_failure_replay_cases"))
        XCTAssertTrue(report.unresolvedRisks.contains("no_protected_success_cases"))
    }

    func testDeterministicTierCatchesMissingLedgerRecord() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        // The primitive throws → the run stops at fetch (W1 Finished(failed)
        // IS written for fetch) — but the case's naive ledger contract also
        // expects a Finished(completed) record for summarize, which never ran.
        let data = try recipeData()
        let caseItem = IOSEvaluationCase(
            id: "case:sealed-ledger",
            kind: .sealedHoldout,
            recipeInputs: ["feed_url": .string("https://example.com/rss.xml")],
            scriptedPrimitives: [
                "scrape_web": IOSEvaluationScriptedPrimitive(outputJSON: nil, failureMessage: "network down"),
                "summarize_text": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"摘要"}"#),
            ],
            assertions: IOSEvaluationAssertions(
                expectedOutputs: nil,
                expectedError: IOSEvaluationExpectedError(kind: .stepFailed, stepId: "fetch"),
                expectedStepCalls: [
                    IOSEvaluationExpectedStepCall(
                        stepId: "fetch", tool: "scrape_web",
                        arguments: ["url": .string("https://example.com/rss.xml")]
                    ),
                ],
                expectedLedger: [
                    IOSEvaluationLedgerExpectation(
                        stepId: "fetch", outcome: "failed", outcomeKind: "error", errorCode: "step_failed"
                    ),
                    IOSEvaluationLedgerExpectation(
                        stepId: "summarize", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                ]
            ),
            originalFailure: nil
        )
        let suite = IOSEvaluationSuite(
            suiteId: "suite-ledger",
            failureReplayCases: [],
            protectedSuccessCases: [],
            sealedHoldoutCases: [caseItem]
        )

        let outcome = await evaluator.evaluate(
            candidateBytes: data, expectedCandidateHash: try candidateHash(root: root, data: data),
            suite: suite
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        let result = try XCTUnwrap(report.results.first)
        XCTAssertFalse(result.passed)
        XCTAssertEqual(result.failureCode, .ledgerRecordMissing,
                       "the missing summarize ledger record must be caught")
        XCTAssertEqual(result.observedOutcome.failedStepId, "fetch")
        XCTAssertEqual(result.observedOutcome.failedErrorKind, .stepFailed)
    }

    // MARK: - Fixtures

    private func makeDatabase() -> (root: URL, dao: AgentRuntimeDao) {
        let root = tempRoot()
        let path = root.appendingPathComponent("agent_runtime.db").path
        let db = IosDatabaseFactory.shared.createDatabase(atFilePath: path)
        databases.append(db)
        return (root, db.agentRuntimeDao())
    }

    private func makeEvaluator(root: URL, dao: AgentRuntimeDao) -> IOSArtifactEvaluator {
        let ledger = IOSAgentRunLedger(dao: dao)
        return IOSArtifactEvaluator(
            recipeStoreBaseDirectory: root,
            catalog: evaluatorCatalog,
            ledger: ledger,
            dao: dao
        )
    }

    private var evaluatorCatalog: IOSRecipeCatalogLookup {
        { tool in
            switch tool {
            case "scrape_web":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .pure)
            case "summarize_text":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "2.1.0", effectClass: .idempotent)
            default:
                return nil
            }
        }
    }

    private func candidateHash(root: URL, data: Data) throws -> String {
        try IOSRecipeFileStore(baseDirectory: root).prepareRecipe(recipeJSON: data).candidate.hash
    }

    // MARK: Candidates (test data, not assertions)

    /// digest_recipe manifest bytes. `textBinding` / `lang` let a test build
    /// the fixed (v2), unfixed (v1) and regressing (v3) variants of the same
    /// recipe; `version` allows an exactly-one-byte-different candidate.
    private func recipeData(
        version: String = "1.0.0",
        textBinding: String = "${step.fetch.output.text}",
        lang: String = "zh-CN",
        summarizeTool: String = "summarize_text"
    ) throws -> Data {
        let dict: [String: Any] = [
            "schema": "amber.recipe.v1",
            "name": "digest_recipe",
            "version": version,
            "description": "抓取并总结。",
            "inputs": ["feed_url": "string"],
            "steps": [
                ["id": "fetch", "tool": "scrape_web", "arguments": ["url": "${input.feed_url}"]],
                ["id": "summarize", "tool": summarizeTool, "arguments": ["text": textBinding, "lang": lang]],
            ],
            "outputs": ["summary": "${step.summarize.output.text}"],
        ]
        return try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
    }

    // MARK: Suite / case builders

    private func allGreenSuite() -> IOSEvaluationSuite {
        IOSEvaluationSuite(
            suiteId: "suite-green",
            failureReplayCases: [replayCase()],
            protectedSuccessCases: [protectedCaseWithFullArgs()],
            sealedHoldoutCases: [sealedCase()]
        )
    }

    /// Failure-replay case (§12.1): the ORIGINAL failure is an
    /// argumentBinding at `summarize` after `fetch` completed, because the
    /// scrape_web fixture output has NO "title" field. A fixed candidate binds
    /// `text` and passes; the unfixed candidate reproduces the exact failure.
    private func replayCase(id: String = "case:replay-1", scrapeDelaySeconds: TimeInterval? = nil) -> IOSEvaluationCase {
        IOSEvaluationCase(
            id: id,
            kind: .failureReplay,
            recipeInputs: ["feed_url": .string("https://example.com/rss.xml")],
            scriptedPrimitives: [
                "scrape_web": IOSEvaluationScriptedPrimitive(
                    outputJSON: #"{"text":"fetched body"}"#, delaySeconds: scrapeDelaySeconds
                ),
                "summarize_text": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"摘要"}"#),
            ],
            assertions: IOSEvaluationAssertions(
                expectedOutputs: ["summary": .string("摘要")],
                expectedError: nil,
                expectedStepCalls: [
                    IOSEvaluationExpectedStepCall(
                        stepId: "fetch", tool: "scrape_web",
                        arguments: ["url": .string("https://example.com/rss.xml")]
                    ),
                    // Summarize asserted by tool only: the fixed candidate runs
                    // both steps, and this case must stay green for the
                    // lang-regressing v3 (acceptance 3 asserts lang via the
                    // protected case's full contract, not here).
                    IOSEvaluationExpectedStepCall(stepId: "summarize", tool: "summarize_text", arguments: nil),
                ],
                expectedLedger: [
                    IOSEvaluationLedgerExpectation(
                        stepId: "fetch", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                    IOSEvaluationLedgerExpectation(
                        stepId: "summarize", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                ]
            ),
            originalFailure: IOSEvaluationOriginalFailure(
                failedStepId: "summarize", errorKind: .argumentBinding, completedStepIds: ["fetch"]
            )
        )
    }

    /// Protected-success case with a FULL step-call contract (exact resolved
    /// arguments). The fixture output provides BOTH "text" and "title" so the
    /// unfixed candidate (title binding) also succeeds — this case is what
    /// catches the lang regression (acceptance 3).
    private func protectedCaseWithFullArgs() -> IOSEvaluationCase {
        IOSEvaluationCase(
            id: "case:protected-1",
            kind: .protectedSuccess,
            recipeInputs: ["feed_url": .string("https://example.com/ok.xml")],
            scriptedPrimitives: [
                "scrape_web": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"body","title":"T"}"#),
                "summarize_text": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"摘要"}"#),
            ],
            assertions: IOSEvaluationAssertions(
                expectedOutputs: ["summary": .string("摘要")],
                expectedError: nil,
                expectedStepCalls: [
                    IOSEvaluationExpectedStepCall(
                        stepId: "fetch", tool: "scrape_web",
                        arguments: ["url": .string("https://example.com/ok.xml")]
                    ),
                    IOSEvaluationExpectedStepCall(
                        stepId: "summarize", tool: "summarize_text",
                        arguments: ["text": .string("body"), "lang": .string("zh-CN")]
                    ),
                ],
                expectedLedger: [
                    IOSEvaluationLedgerExpectation(
                        stepId: "fetch", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                    IOSEvaluationLedgerExpectation(
                        stepId: "summarize", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                ]
            ),
            originalFailure: nil
        )
    }

    /// Protected-success case with a LOOSE contract (outputs + fetch call +
    /// ledger only) — passes for both the fixed and the unfixed candidate.
    private func protectedCaseLoose() -> IOSEvaluationCase {
        IOSEvaluationCase(
            id: "case:protected-loose",
            kind: .protectedSuccess,
            recipeInputs: ["feed_url": .string("https://example.com/ok.xml")],
            scriptedPrimitives: [
                "scrape_web": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"body","title":"T"}"#),
                "summarize_text": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"摘要"}"#),
            ],
            assertions: IOSEvaluationAssertions(
                expectedOutputs: ["summary": .string("摘要")],
                expectedError: nil,
                expectedStepCalls: [
                    IOSEvaluationExpectedStepCall(
                        stepId: "fetch", tool: "scrape_web",
                        arguments: ["url": .string("https://example.com/ok.xml")]
                    ),
                    // Tool-only: the unfixed candidate (title binding) also runs
                    // summarize on this fixture (which HAS "title"), so this
                    // loose case stays green for both candidate variants.
                    IOSEvaluationExpectedStepCall(stepId: "summarize", tool: "summarize_text", arguments: nil),
                ],
                expectedLedger: [
                    IOSEvaluationLedgerExpectation(
                        stepId: "fetch", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                    IOSEvaluationLedgerExpectation(
                        stepId: "summarize", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                ]
            ),
            originalFailure: nil
        )
    }

    /// Sealed-holdout case: fixture output carries BOTH "text" and "title",
    /// so both the fixed and the unfixed candidate succeed — the sealed case
    /// only ever gates on outputs / fetch call / ledger.
    private func sealedCase() -> IOSEvaluationCase {
        IOSEvaluationCase(
            id: "case:sealed-1",
            kind: .sealedHoldout,
            recipeInputs: ["feed_url": .string("https://example.com/sealed.xml")],
            scriptedPrimitives: [
                "scrape_web": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"sealed body","title":"T"}"#),
                "summarize_text": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"密封摘要"}"#),
            ],
            assertions: IOSEvaluationAssertions(
                expectedOutputs: ["summary": .string("密封摘要")],
                expectedError: nil,
                expectedStepCalls: [
                    IOSEvaluationExpectedStepCall(
                        stepId: "fetch", tool: "scrape_web",
                        arguments: ["url": .string("https://example.com/sealed.xml")]
                    ),
                    // Tool-only: both candidate variants run summarize on this
                    // fixture (it HAS "title"), so the sealed case stays green
                    // for both.
                    IOSEvaluationExpectedStepCall(stepId: "summarize", tool: "summarize_text", arguments: nil),
                ],
                expectedLedger: [
                    IOSEvaluationLedgerExpectation(
                        stepId: "fetch", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                    IOSEvaluationLedgerExpectation(
                        stepId: "summarize", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                ]
            ),
            originalFailure: nil
        )
    }

    /// Protected case whose fixture output has NO "title" — the unfixed
    /// candidate fails its runtime binding here (deterministic-tier catch).
    private func protectedCaseNoTitle() -> IOSEvaluationCase {
        IOSEvaluationCase(
            id: "case:protected-notitle",
            kind: .protectedSuccess,
            recipeInputs: ["feed_url": .string("https://example.com/ok.xml")],
            scriptedPrimitives: [
                "scrape_web": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"body"}"#),
                "summarize_text": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"摘要"}"#),
            ],
            assertions: IOSEvaluationAssertions(
                expectedOutputs: ["summary": .string("摘要")],
                expectedError: nil,
                expectedStepCalls: [
                    IOSEvaluationExpectedStepCall(
                        stepId: "fetch", tool: "scrape_web",
                        arguments: ["url": .string("https://example.com/ok.xml")]
                    ),
                    IOSEvaluationExpectedStepCall(
                        stepId: "summarize", tool: "summarize_text",
                        arguments: ["text": .string("body"), "lang": .string("zh-CN")]
                    ),
                ],
                expectedLedger: [
                    IOSEvaluationLedgerExpectation(
                        stepId: "fetch", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                    IOSEvaluationLedgerExpectation(
                        stepId: "summarize", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                ]
            ),
            originalFailure: nil
        )
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-artifact-evaluator-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }
}

import XCTest
@preconcurrency import Shared
@testable import iosApp

/// 真实闭环收口 Slice A（/private/tmp/amber-self-evolution-real-loop-closure-plan.md
/// §4）：评测从「脚本自洽」改成「差分证明修复」——Workspace 本地读写 primitive
/// 的参数/binding/路径/schema 错误这一类可确定性隔离的失败，baseline 与候选
/// 分别在全新隔离临时 Workspace 中走真实 `IOSWorkspaceStore` 执行：
/// baseline 必须在预期位置复现原失败，候选必须完成且满足 Workspace 后置条件；
/// 无确定性 oracle 的 case（网络/MCP/无 recipe  provenance）不得进入自动晋升。
///
/// 全真实组件：真实 Room 账本（isolated DB）、真实 runner、真实 validator、
/// 真实 workspace store（temp root）、真实 suite provider（Ledger+消息夹具）。
@MainActor
final class IOSEvolutionWorkspaceOracleTests: XCTestCase {
    private var tempDirs: [URL] = []
    private var databases: [AgentRuntimeDatabase] = []

    override func tearDown() async throws {
        databases.removeAll()
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    // MARK: - 1. baseline 差分：旧失败（binding/schema 错）在隔离 Workspace 复现，修复候选成功

    func testBaselineBadBindingFailsCandidateFixedBindingPasses() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        let candidate = try IOSEvolutionWorkspaceOracleFixtures.noteWriterRecipeData(version: "1.0.1", copyContent: "${input.text}")
        let hash = try candidateHash(root: root, data: candidate)

        let outcome = await evaluator.evaluate(
            candidateBytes: candidate, expectedCandidateHash: hash, suite: try IOSEvolutionWorkspaceOracleFixtures.suite()
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        let replay = try XCTUnwrap(report.results.first { $0.kind == .failureReplay })
        XCTAssertTrue(replay.passed, "修复候选必须在真实隔离 Workspace 中通过：\(replay.failureCode as Any)")
        XCTAssertEqual(replay.observedOutcome.completedStepIds, ["write", "copy"])
        XCTAssertEqual(report.protectedRegressions, 0)
        XCTAssertTrue(report.unresolvedRisks.isEmpty, "全 oracle 套件不应有未决风险：\(report.unresolvedRisks)")
        XCTAssertEqual(report.recommendation, .promote,
                       "baseline 复现失败 + 候选成功 + protected/sealed 通过 ⇒ 可晋升")
    }

    // MARK: - 2. 防 false-green：候选没有修 binding（只 bump 版本）必须仍失败

    func testCandidateThatDoesNotFixBindingStillFails() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        let candidate = try IOSEvolutionWorkspaceOracleFixtures.noteWriterRecipeData(version: "1.0.1", copyContent: "${step.write.output.text}")
        let hash = try candidateHash(root: root, data: candidate)

        let outcome = await evaluator.evaluate(
            candidateBytes: candidate, expectedCandidateHash: hash, suite: try IOSEvolutionWorkspaceOracleFixtures.suite()
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        let replay = try XCTUnwrap(report.results.first { $0.kind == .failureReplay })
        XCTAssertFalse(replay.passed)
        XCTAssertEqual(replay.failureCode, .expectedSuccessButFailed)
        XCTAssertEqual(replay.observedOutcome.failedStepId, "copy")
        XCTAssertEqual(report.recommendation, .reject,
                       "未修复且未缩小（completed 与原失败相同）必须 reject")
    }

    // MARK: - 3. 后置条件门禁：工具全部「成功」但目标文件/内容不正确仍失败

    func testCandidateSuccessRequiresWorkspacePostcondition() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        // 候选「修好」了 binding 但把副本写到了别的路径——run 成功、文件落错位置。
        let candidate = try IOSEvolutionWorkspaceOracleFixtures.noteWriterRecipeData(
            version: "1.0.1", copyContent: "${input.text}", copyPath: "out/other.txt"
        )
        let hash = try candidateHash(root: root, data: candidate)

        let outcome = await evaluator.evaluate(
            candidateBytes: candidate, expectedCandidateHash: hash, suite: try IOSEvolutionWorkspaceOracleFixtures.suite()
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        let replay = try XCTUnwrap(report.results.first { $0.kind == .failureReplay })
        XCTAssertFalse(replay.passed)
        XCTAssertEqual(replay.failureCode, .workspacePostconditionFailed,
                       "run 成功但 postcondition（out/copy.txt 存在）不满足必须判失败")
        XCTAssertEqual(report.recommendation, .reject,
                       "postcondition 失败 = 未修复（不是 narrowed），硬拒绝")
    }

    // MARK: - 4. protected 回归：修复 replay 但破坏旧成功用例（内容不再来自 input）硬拒绝

    func testProtectedWorkspaceSuccessRegressionRejectsCandidate() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        // replay 的 copy content 无后置条件（模板是 stepOutput，不可静态派生），
        // 候选把 copy 内容写成字面常量：replay 仍通过，protected 的
        // fileContentEquals(out/copy.txt, 受保护正文) 回归。
        let candidate = try IOSEvolutionWorkspaceOracleFixtures.noteWriterRecipeData(version: "1.0.1", copyContent: nil, copyContentLiteral: "CONSTANT")
        let hash = try candidateHash(root: root, data: candidate)

        let outcome = await evaluator.evaluate(
            candidateBytes: candidate, expectedCandidateHash: hash, suite: try IOSEvolutionWorkspaceOracleFixtures.suite()
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        let replay = try XCTUnwrap(report.results.first { $0.kind == .failureReplay })
        XCTAssertTrue(replay.passed, "replay 无 copy 内容后置条件，候选应通过：\(replay.failureCode as Any)")
        XCTAssertEqual(report.protectedRegressions, 1)
        XCTAssertEqual(report.recommendation, .reject, "protected 回归是硬拒绝（§12.2）")
    }

    func testCandidateCannotPassByHardcodingProtectedInput() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        let candidate = try IOSEvolutionWorkspaceOracleFixtures.noteWriterRecipeData(
            version: "1.0.1",
            copyContent: nil,
            copyContentLiteral: "受保护正文"
        )
        let hash = try candidateHash(root: root, data: candidate)

        let outcome = await evaluator.evaluate(
            candidateBytes: candidate,
            expectedCandidateHash: hash,
            suite: try IOSEvolutionWorkspaceOracleFixtures.suite()
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        XCTAssertEqual(report.protectedRegressions, 0, "硬编码 protected 样本会通过已知样本")
        let sealed = try XCTUnwrap(report.results.first { $0.kind == .sealedHoldout })
        XCTAssertFalse(sealed.passed, "sealed 换值必须识别硬编码候选")
        XCTAssertEqual(sealed.failureCode, .workspacePostconditionFailed)
        XCTAssertEqual(
            report.recommendation,
            .manualJudgmentRequired,
            "sealed 失败按既有分层语义转人工判断，但绝不能继续 promote"
        )
    }

    // MARK: - 5. unsupported primitive：网络失败无确定性 oracle，不进入自动晋升

    /// 失败 step 是 scrape_web（networkRead）：provider 仍按记录建 scripted 套件
    /// （candidate draft + 报告合法），但不挂 workspace scenario —— evaluator 必须
    /// 标注「缺少确定性任务 oracle」，recommendation 永远不可能是 promote。
    func testUnsupportedPrimitiveProducesDraftOnlyWithoutAutoPromotion() async throws {
        let (root, dao, ledger, store) = makeEnvironment()
        let conversationHex = await makeConversation(store: store)
        let uuid = try XCTUnwrap(IOSEvolutionSuiteProvider.parseConversationId(conversationHex))
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // 一次真实 recipe run：外层 recipe__web_notes 调用 + 内层 scrape_web
        // 网络错误终态（recipe provenance 由 artifactId/artifactVersion 承载）。
        await store.save(messages: [
            makeToolMessage(parts: [
                makeToolPart(toolCallId: "tc-outer", toolName: "recipe__web_notes",
                             input: #"{"feed_url":"https://example.com/rss.xml"}"#,
                             output: #"{"ok":false,"tool":"recipe__web_notes","step":"fetch","reason":"network unreachable"}"#),
                makeToolPart(toolCallId: "recipe-e1-fetch", toolName: "scrape_web",
                             input: #"{"url":"https://example.com/rss.xml"}"#,
                             output: #"{"ok":false,"error":"network unreachable"}"#),
            ]),
        ], to: uuid)
        await insertRun(dao: dao, runId: "run-recipe-net", conversationId: conversationHex,
                        status: "failed", startedAt: now - 60_000)
        await recordCall(ledger: ledger, runId: "run-recipe-net", toolCallId: "tc-outer",
                         toolName: "recipe__web_notes", outcome: "failed", outcomeKind: "error",
                         errorCode: "step_failed")
        await recordCall(ledger: ledger, runId: "run-recipe-net", toolCallId: "recipe-e1-fetch",
                         toolName: "scrape_web", outcome: "failed", outcomeKind: "error",
                         errorCode: "step_failed",
                         artifactId: "recipe__web_notes", artifactVersion: "1.0.0")

        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.observedOutcome == .error },
            "需要一条可归因的失败证据"
        )
        // baseline recipe 存在于 store（web recipe 含 scrape step）——但 scrape_web
        // 不是 workspace 可隔离 primitive，scenario 仍不得挂上。
        let recipeStore = IOSRecipeFileStore(baseDirectory: root)
        let baseline = try webNotesRecipeData(version: "1.0.0")
        _ = try recipeStore.applyRecipe(
            name: "web_notes", recipeJSON: baseline,
            expectedBaseHash: nil,
            expectedCandidateHash: recipeStore.prepareRecipe(recipeJSON: baseline).candidate.hash
        )
        let provider = IOSEvolutionSuiteProvider(
            dao: dao,
            conversationStore: IOSConversationStore(baseDirectory: root),
            recipeStoreBaseDirectory: root
        )
        let result = await provider.build(
            hypothesis: makeHypothesis(evidenceIds: [failureEvidence.id]),
            evidence: evidence
        )
        guard case .built(let suite, _) = result else {
            return XCTFail("expected built, got \(result)")
        }
        let replay = try XCTUnwrap(suite.failureReplayCases.first)
        XCTAssertNil(replay.workspaceScenario,
                     "网络/MCP/无 oracle 的失败不得伪装成 workspace 差分 case")

        // evaluator 侧：scripted-only failure replay 通过 ⇒ 必须标注 oracle 缺失，
        // recommendation 不能是 promote（policy engine 的 recommendation_not_promote
        // 硬门禁随之拦截 T0/T1 自动晋升；候选只走 draft + 人工报告路径）。
        let evaluator = makeEvaluator(root: root, dao: dao)
        let candidate = try webNotesRecipeData(version: "1.0.1")
        let hash = try candidateHash(root: root, data: candidate)
        let outcome = await evaluator.evaluate(
            candidateBytes: candidate, expectedCandidateHash: hash, suite: suite
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        XCTAssertTrue(
            report.unresolvedRisks.contains { $0.hasPrefix("no_deterministic_task_oracle") },
            "缺少确定性任务 oracle 必须显式标注：\(report.unresolvedRisks)"
        )
        XCTAssertEqual(report.recommendation, .manualJudgmentRequired,
                       "无 oracle ⇒ 只能 draft + 人工判断，既不 promote 也不被 fixture 误杀")
    }

    // MARK: - 6. baseline 未复现原失败 ⇒ case 标 insufficient-data 级降级（不 reject 候选）

    func testBaselineThatDoesNotReproduceFailureDowngradesToManualJudgment() async throws {
        let (root, dao) = makeDatabase()
        let evaluator = makeEvaluator(root: root, dao: dao)
        // 篡改 scenario：声称失败位置是 step 0（write），但 baseline 真实失败在
        // step 1（copy）——oracle 自身不一致，不能据此判候选。
        var scenario = try IOSEvolutionWorkspaceOracleFixtures.scenario()
        scenario = IOSEvaluationWorkspaceScenario(
            baseline: IOSEvaluationWorkspaceScenario.Baseline(
                artifactId: scenario.baseline!.artifactId,
                version: scenario.baseline!.version,
                canonicalJSON: scenario.baseline!.canonicalJSON,
                failingStepIndex: 0
            ),
            postconditions: scenario.postconditions
        )
        let suite = try IOSEvolutionWorkspaceOracleFixtures.suite(replayScenario: scenario)
        let candidate = try IOSEvolutionWorkspaceOracleFixtures.noteWriterRecipeData(version: "1.0.1", copyContent: "${input.text}")
        let hash = try candidateHash(root: root, data: candidate)

        let outcome = await evaluator.evaluate(
            candidateBytes: candidate, expectedCandidateHash: hash, suite: suite
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        let replay = try XCTUnwrap(report.results.first { $0.kind == .failureReplay })
        XCTAssertFalse(replay.passed)
        XCTAssertEqual(replay.failureCode, .baselineFailureNotReproduced)
        XCTAssertTrue(
            report.unresolvedRisks.contains { $0.hasPrefix("baseline_failure_not_reproduced") },
            "baseline 未复现 = insufficient data 级降级（人工判断），不是候选的错"
        )
        XCTAssertEqual(report.recommendation, .manualJudgmentRequired)
    }

    // MARK: - Fixtures（测试数据，不是断言）

    private func makeDatabase() -> (root: URL, dao: AgentRuntimeDao) {
        let root = tempRoot()
        let path = root.appendingPathComponent("agent_runtime.db").path
        let db = IosDatabaseFactory.shared.createDatabase(atFilePath: path)
        databases.append(db)
        return (root, db.agentRuntimeDao())
    }

    private func makeEvaluator(root: URL, dao: AgentRuntimeDao) -> IOSArtifactEvaluator {
        IOSArtifactEvaluator(
            recipeStoreBaseDirectory: root,
            catalog: oracleCatalog,
            ledger: IOSAgentRunLedger(dao: dao),
            dao: dao
        )
    }

    private var oracleCatalog: IOSRecipeCatalogLookup {
        { tool in
            switch tool {
            case "workspace_file_write":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .idempotent)
            case "workspace_file_read":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .pure)
            case "scrape_web":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .networkRead)
            default:
                return nil
            }
        }
    }

    private func candidateHash(root: URL, data: Data) throws -> String {
        try IOSRecipeFileStore(baseDirectory: root).prepareRecipe(recipeJSON: data).candidate.hash
    }

    /// web_notes（test 5 的 baseline/candidate）：单 step scrape_web，网络类
    /// primitive——不是 workspace 可隔离失败。
    private func webNotesRecipeData(version: String) throws -> Data {
        let dict: [String: Any] = [
            "schema": "amber.recipe.v1",
            "name": "web_notes",
            "version": version,
            "description": "抓取 feed。",
            "inputs": ["feed_url": "string"],
            "steps": [
                ["id": "fetch", "tool": "scrape_web",
                 "arguments": ["url": "${input.feed_url}"]],
            ],
            "outputs": ["text": "${step.fetch.output.text}"],
        ]
        return try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
    }

    // MARK: - Provider 夹具（照 IOSEvolutionSuiteProviderTests 模式）

    private func makeEnvironment() -> (root: URL, dao: AgentRuntimeDao, ledger: IOSAgentRunLedger, store: IOSConversationStore) {
        let root = tempRoot()
        let path = root.appendingPathComponent("agent_runtime.db").path
        let db = IosDatabaseFactory.shared.createDatabase(atFilePath: path)
        databases.append(db)
        let dao = db.agentRuntimeDao()
        return (root, dao, IOSAgentRunLedger(dao: dao), IOSConversationStore(baseDirectory: root))
    }

    private func makeConversation(store: IOSConversationStore) async -> String {
        await store.newConversation()
        return store.currentConversation?.id.toHexDashString() ?? ""
    }

    private func insertRun(
        dao: AgentRuntimeDao,
        runId: String,
        conversationId: String?,
        status: String,
        startedAt: Int64
    ) async {
        let run = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: conversationId,
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: status,
            inputDigest: "digest",
            inputSnapshotRef: nil,
            inputSchemaVersion: 1,
            startedAt: startedAt,
            finishedAt: KotlinLong(value: startedAt + 1_000),
            interruptedReason: nil
        )
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            dao.insertRun(run: run) { _ in continuation.resume() }
        }
    }

    private func recordCall(
        ledger: IOSAgentRunLedger,
        runId: String,
        toolCallId: String,
        toolName: String,
        outcome: String,
        outcomeKind: String?,
        errorCode: String?,
        artifactId: String? = nil,
        artifactVersion: String? = nil
    ) async {
        await ledger.recordToolCallStarted(
            runId: runId, toolCallId: toolCallId, toolName: toolName,
            argsDigest: "digest", effectClass: .sideEffect
        )
        await ledger.recordToolCallFinished(
            runId: runId, toolCallId: toolCallId, outcome: outcome,
            artifactId: artifactId, artifactVersion: artifactVersion,
            outcomeKind: outcomeKind, errorCode: errorCode, sourceRef: nil
        )
    }

    private func recentEvidence(dao: AgentRuntimeDao) async -> [IOSEvolutionEvidence] {
        await IOSEvolutionEvidenceProjector.projectRecent(
            sinceEpochMs: Int64(Date().timeIntervalSince1970 * 1000) - 7 * 24 * 3600 * 1000,
            dao: dao
        )
    }

    private func makeHypothesis(evidenceIds: [String]) -> IOSGapHypothesis {
        IOSGapHypothesis(
            id: "hyp-\(UUID().uuidString)",
            evidenceIds: evidenceIds,
            kind: .composition,
            claim: "重复编排不稳定",
            confidence: 0.8,
            alternatives: ["可能是网络波动"],
            falsifier: "若手工逐步执行同样失败则推翻",
            recommendedArtifact: .recipe
        )
    }

    private func makeToolMessage(parts: [UIMessagePart.Tool]) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: parts,
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(
                year: 2026, month: 8, day: 10, hour: 0, minute: 0, second: 0, nanosecond: 0
            ),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func makeToolPart(toolCallId: String, toolName: String, input: String, output: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: toolCallId,
            toolName: toolName,
            input: input,
            output: [UIMessagePart.Text(text: output, metadata: nil)],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-ws-oracle-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }
}

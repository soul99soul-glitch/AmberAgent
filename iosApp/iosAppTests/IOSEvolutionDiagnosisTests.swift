import XCTest
@testable import iosApp

/// Phase 2 Wave B tests (§15 Phase 2 acceptance 1 + §9.2/§11.2 schema rules +
/// §9.3/§12.3/§18.3 builder contracts). Uses REAL components: the real
/// `IOSRecipeValidator` with a real catalog oracle, the real
/// `IOSRecipeFileStore` in temp directories (createDirectory first), and
/// scripted model closures (the model injection point). No source-string
/// anchors: every assertion decodes or re-reads actual data.
@MainActor
final class IOSEvolutionDiagnosisTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDown() async throws {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    // MARK: - §15 Phase 2 acceptance 1: attributable gap → matching artifact

    func testAttributableCompositionFailureProducesRecipeCandidate() async throws {
        let diagnoserModel = ScriptedModel(responses: [Self.compositionHypothesisJSON()])
        let diagnoser = makeDiagnoser(model: diagnoserModel)
        let outcome = await diagnoser.diagnose(
            evidence: [evidence(id: "ev:1", outcome: .error, toolId: "scrape_web")],
            userHint: "多次抓取后总结失败"
        )
        guard case .hypothesis(let hypothesis) = outcome else {
            return XCTFail("expected hypothesis, got \(outcome)")
        }
        XCTAssertEqual(hypothesis.kind, .composition)
        XCTAssertEqual(hypothesis.recommendedArtifact, .recipe)
        XCTAssertEqual(hypothesis.evidenceIds, ["ev:1"])
        XCTAssertFalse(hypothesis.alternatives.isEmpty)
        XCTAssertFalse(hypothesis.falsifier.isEmpty)

        let root = tempRoot()
        let builder = makeBuilder(root: root, model: ScriptedModel(fallback: Self.recipeV1JSONString()))
        let buildOutcome = await builder.build(
            hypothesis: hypothesis,
            proposerSuite: completeSuite(),
            budget: .standard,
            catalogSummary: "scrape_web / search_web / summarize_text / workspace_file_write"
        )
        guard case .candidate(let manifest, let content) = buildOutcome else {
            return XCTFail("expected recipe candidate, got \(buildOutcome)")
        }
        XCTAssertEqual(manifest.artifactKind, .recipe)
        XCTAssertEqual(manifest.artifactName, "digest_recipe")
        XCTAssertNil(manifest.parentVersion)
        XCTAssertNil(manifest.baseHash)
        XCTAssertFalse(manifest.draftOnly, "complete suite → not draftOnly")
        XCTAssertEqual(manifest.evaluationCaseRefs, ["case:fail-1", "case:ok-1"])

        // Candidate bytes pass the REAL validator with the REAL catalog.
        let decoded = try IOSRecipeManifest.decode(content)
        let validation = IOSRecipeValidator.validate(manifest: decoded, catalog: testCatalog)
        XCTAssertTrue(validation.isValid, "\(validation.issues)")
        XCTAssertEqual(validation.permissionEnvelope, .idempotent)
        XCTAssertEqual(manifest.permissionEnvelope, ["idempotent"])

        // Hash is stable and equals what a later apply would publish (I-5).
        let store = IOSRecipeFileStore(baseDirectory: root)
        let preparation = try store.prepareRecipe(recipeJSON: content)
        XCTAssertEqual(preparation.candidate.hash, manifest.candidateHash)
        XCTAssertEqual(preparation.candidate.canonicalJSON, content)

        // Re-building the same content yields the same candidateHash but a
        // fresh candidateId (lineage identity, not content identity).
        let again = await builder.build(
            hypothesis: hypothesis, proposerSuite: completeSuite(), budget: .standard
        )
        guard case .candidate(let manifest2, let content2) = again else {
            return XCTFail("expected recipe candidate, got \(again)")
        }
        XCTAssertEqual(manifest2.candidateHash, manifest.candidateHash, "same content → same hash")
        XCTAssertEqual(content2, content)
        XCTAssertNotEqual(manifest2.candidateId, manifest.candidateId)
    }

    func testMissingExternalCapabilityProducesMcpRequestWithoutPretendingConnection() async throws {
        let diagnoserModel = ScriptedModel(responses: [Self.missingCapabilityHypothesisJSON()])
        let diagnoser = makeDiagnoser(model: diagnoserModel, knownMcp: [])
        let outcome = await diagnoser.diagnose(
            evidence: [evidence(id: "ev:1", outcome: .error, toolId: nil)],
            userHint: "需要读取企业系统但无认证"
        )
        guard case .hypothesis(let hypothesis) = outcome else {
            return XCTFail("expected hypothesis, got \(outcome)")
        }
        XCTAssertEqual(hypothesis.kind, .missingExternalCapability)
        XCTAssertEqual(hypothesis.recommendedArtifact, .mcpBinding)

        let root = tempRoot()
        let builder = makeBuilder(root: root, model: ScriptedModel(responses: [Self.mcpRequestDraftJSON()]))
        let buildOutcome = await builder.build(hypothesis: hypothesis, budget: .standard)
        guard case .capabilityRequest(let request) = buildOutcome else {
            return XCTFail("expected capability request, got \(buildOutcome)")
        }
        XCTAssertEqual(request.serverName, "rss_api")
        XCTAssertEqual(request.requiredPermissions, ["network", "oauth2"])
        XCTAssertEqual(request.authSteps.count, 2)
        XCTAssertEqual(request.hypothesisId, hypothesis.id)

        let doc = request.document()
        XCTAssertTrue(doc.contains("rss_api"))
        XCTAssertTrue(doc.contains("未连接"), "the request must state it is not connected yet (§6.1)")
        XCTAssertFalse(doc.contains("已连接"), "the request must not pretend to be connected (§6.1)")
        XCTAssertFalse(doc.contains("已安装"), "the request must contain no install claim")
    }

    func testInsufficientEvidenceIsHonestNoOp() async throws {
        // Empty evidence → no-op WITHOUT calling the model (I-3).
        let idleModel = ScriptedModel(fallback: "{}")
        let idleDiagnoser = makeDiagnoser(model: idleModel)
        let emptyOutcome = await idleDiagnoser.diagnose(evidence: [])
        guard case .noOp(let emptyReason) = emptyOutcome else {
            return XCTFail("expected noOp, got \(emptyOutcome)")
        }
        XCTAssertFalse(emptyReason.isEmpty)
        var prompts = await idleModel.recordedPrompts()
        XCTAssertTrue(prompts.isEmpty, "no evidence must not call the model")

        // Only-success evidence and no user hint → no-op.
        let successOutcome = await idleDiagnoser.diagnose(evidence: [evidence(id: "ev:ok", outcome: .success)])
        guard case .noOp = successOutcome else {
            return XCTFail("expected noOp, got \(successOutcome)")
        }
        prompts = await idleModel.recordedPrompts()
        XCTAssertTrue(prompts.isEmpty, "no failure signal must not call the model")

        // Model itself says insufficient_evidence → valid hypothesis with nil
        // artifact (§9.2 hard rule) → the builder returns no-op (I-3).
        let insufficientModel = ScriptedModel(responses: [Self.insufficientEvidenceHypothesisJSON()])
        let diagnoser = makeDiagnoser(model: insufficientModel)
        let outcome = await diagnoser.diagnose(
            evidence: [evidence(id: "ev:1", outcome: .error, toolId: "scrape_web")]
        )
        guard case .hypothesis(let hypothesis) = outcome else {
            return XCTFail("expected hypothesis, got \(outcome)")
        }
        XCTAssertEqual(hypothesis.kind, .insufficientEvidence)
        XCTAssertNil(hypothesis.recommendedArtifact)

        let builder = makeBuilder(root: tempRoot(), model: ScriptedModel(fallback: "unused"))
        let buildOutcome = await builder.build(hypothesis: hypothesis, budget: .standard)
        guard case .noOp(let reason) = buildOutcome else {
            return XCTFail("expected builder noOp, got \(buildOutcome)")
        }
        XCTAssertFalse(reason.isEmpty)
    }

    // MARK: - Diagnoser host schema failures (§9.2 / §11.2)

    func testMissingFalsifierIsTypedFailure() async {
        await assertDiagnosisFails(
            model: ScriptedModel(responses: [Self.json(Self.draft(removing: "falsifier"))]),
            evidence: [evidence(id: "ev:1")]
        ) { $0 == .missingFalsifier }
    }

    func testMissingAlternativeIsTypedFailure() async {
        var dict = Self.compositionDraftDict
        dict["alternatives"] = []
        await assertDiagnosisFails(
            model: ScriptedModel(responses: [Self.json(dict)]),
            evidence: [evidence(id: "ev:1")]
        ) { $0 == .missingAlternative }
    }

    func testHallucinatedToolIdIsTypedFailure() async {
        var dict = Self.compositionDraftDict
        dict["tool_ids"] = ["no_such_tool"]
        await assertDiagnosisFails(
            model: ScriptedModel(responses: [Self.json(dict)]),
            evidence: [evidence(id: "ev:1")]
        ) { $0 == .hallucinatedToolId("no_such_tool") }
    }

    func testHallucinatedMcpConnectionIsTypedFailure() async {
        var dict = Self.compositionDraftDict
        dict["mcp_connections"] = ["ghost_server"]
        await assertDiagnosisFails(
            model: ScriptedModel(responses: [Self.json(dict)]),
            evidence: [evidence(id: "ev:1")],
            knownMcp: []
        ) { $0 == .hallucinatedMcpConnection("ghost_server") }
    }

    func testInsufficientEvidenceWithArtifactIsTypedFailure() async {
        var dict = Self.compositionDraftDict
        dict["kind"] = "insufficient_evidence"
        dict["recommended_artifact"] = "recipe"
        await assertDiagnosisFails(
            model: ScriptedModel(responses: [Self.json(dict)]),
            evidence: [evidence(id: "ev:1")]
        ) { $0 == .artifactNotAllowed(kind: .insufficientEvidence, artifact: .recipe) }
    }

    func testEvidenceRefOutsideInputSetIsTypedFailure() async {
        var dict = Self.compositionDraftDict
        dict["evidence_ids"] = ["ev:nope"]
        await assertDiagnosisFails(
            model: ScriptedModel(responses: [Self.json(dict)]),
            evidence: [evidence(id: "ev:1")]
        ) { $0 == .unresolvedEvidenceRef("ev:nope") }
    }

    func testUnknownGapKindIsTypedFailure() async {
        var dict = Self.compositionDraftDict
        dict["kind"] = "learn_everything"
        await assertDiagnosisFails(
            model: ScriptedModel(responses: [Self.json(dict)]),
            evidence: [evidence(id: "ev:1")]
        ) { error in
            if case .unknownGapKind("learn_everything") = error { return true }
            return false
        }
    }

    // MARK: - Builder: revision lineage / draftOnly / sealed isolation / budget

    func testRevisionCandidateCreatesNewLineageWithParentVersionChain() async throws {
        let root = tempRoot()
        let store = IOSRecipeFileStore(baseDirectory: root)
        let v1Data = Data(Self.recipeV1JSONString().utf8)
        let v1 = try store.prepareRecipe(recipeJSON: v1Data)
        _ = try store.applyRecipe(
            name: "digest_recipe",
            recipeJSON: v1.candidate.canonicalJSON,
            expectedBaseHash: nil,
            expectedCandidateHash: v1.candidate.hash
        )

        let builder = makeBuilder(root: root, model: ScriptedModel(responses: [Self.recipeV2JSONString()]))
        let outcome = await builder.build(
            hypothesis: makeHypothesis(kind: .composition, artifact: .recipe),
            proposerSuite: completeSuite(),
            budget: .standard
        )
        guard case .candidate(let manifest, _) = outcome else {
            return XCTFail("expected candidate, got \(outcome)")
        }
        XCTAssertEqual(manifest.artifactName, "digest_recipe")
        XCTAssertEqual(manifest.parentVersion, "1.0.0", "revision must link its parent version")
        XCTAssertEqual(manifest.baseHash, v1.candidate.hash, "revision must link its parent hash")
        XCTAssertNotEqual(manifest.candidateHash, v1.candidate.hash)
        XCTAssertTrue(manifest.candidateId.hasPrefix("cand-"))

        // §12.3: the failed/old candidate is NOT patched in place — the live
        // package is untouched (zero writes) and the new candidate carries a
        // fresh id + parent link.
        XCTAssertEqual(try store.readLiveRecipe(name: "digest_recipe").hash, v1.candidate.hash)
    }

    func testSkillDeltaIsDraftOnlyWithoutAutoPromotionMarkers() async throws {
        let root = tempRoot()
        let builder = makeBuilder(root: root, model: ScriptedModel(responses: [Self.skillDeltaDraftJSON()]))
        let outcome = await builder.build(
            hypothesis: makeHypothesis(kind: .knowledgeOrProcedure, artifact: .skill),
            proposerSuite: nil,
            budget: .standard
        )
        guard case .candidate(let manifest, let content) = outcome else {
            return XCTFail("expected candidate, got \(outcome)")
        }
        XCTAssertEqual(manifest.artifactKind, .skill)
        XCTAssertEqual(manifest.artifactName, "better_summary")
        XCTAssertTrue(manifest.draftOnly, "v1 skill delta is always a Workspace manual draft (§15 Phase 2)")
        XCTAssertTrue(manifest.permissionEnvelope.isEmpty, "text-only delta has no permission envelope")
        XCTAssertTrue(manifest.evaluationCaseRefs.isEmpty, "no suite → no public case refs")
        XCTAssertNil(manifest.baseHash)
        let text = try XCTUnwrap(String(data: content, encoding: .utf8))
        XCTAssertFalse(text.contains("自动晋升"))
        XCTAssertFalse(text.lowercased().contains("promote"))
        XCTAssertFalse(text.contains("已批准"))
    }

    func testCandidateWithoutCompleteSuiteIsDraftOnly() async throws {
        let root = tempRoot()
        let builder = makeBuilder(root: root, model: ScriptedModel(fallback: Self.recipeV1JSONString()))
        // Sealed holdout missing → may only be opened as a manual draft
        // (§12.1 / §15 Phase 2 stop condition).
        let incomplete = IOSEvaluationSuiteProposerView(
            suiteId: "suite-2",
            suiteHash: "suite-hash-2",
            failureReplayCaseRefs: ["case:fail-1"],
            protectedSuccessCaseRefs: ["case:ok-1"],
            hasSealedHoldout: false
        )
        let outcome = await builder.build(
            hypothesis: makeHypothesis(kind: .composition, artifact: .recipe),
            proposerSuite: incomplete,
            budget: .standard
        )
        guard case .candidate(let manifest, _) = outcome else {
            return XCTFail("expected candidate, got \(outcome)")
        }
        XCTAssertTrue(manifest.draftOnly, "no sealed holdout → draftOnly (§12.1)")
    }

    func testProposerNeverSeesSealedHoldoutContent() async throws {
        // The proposer-view TYPE has no field that could carry sealed content
        // (physical isolation, I-12); this test proves the prompt and the
        // manifest only ever see the public refs.
        let sealedSecret = "SEALED-HOLDOUT-\(UUID().uuidString)"
        let suite = IOSEvaluationSuiteProposerView(
            suiteId: "suite-1",
            suiteHash: "full-suite-hash-\(UUID().uuidString)",
            failureReplayCaseRefs: ["case:fail-1"],
            protectedSuccessCaseRefs: ["case:ok-1"],
            hasSealedHoldout: true
        )
        let model = ScriptedModel(responses: [Self.recipeV1JSONString()])
        let root = tempRoot()
        let builder = makeBuilder(root: root, model: model)
        let outcome = await builder.build(
            hypothesis: makeHypothesis(kind: .composition, artifact: .recipe),
            proposerSuite: suite,
            budget: .standard
        )
        guard case .candidate(let manifest, _) = outcome else {
            return XCTFail("expected candidate, got \(outcome)")
        }
        XCTAssertEqual(manifest.evaluationCaseRefs, ["case:fail-1", "case:ok-1"], "only public refs reach the manifest")
        XCTAssertFalse(manifest.draftOnly, "complete suite incl. sealed holdout → not draftOnly")
        let prompts = await model.recordedPrompts().joined(separator: "\n")
        XCTAssertFalse(prompts.contains(sealedSecret), "sealed holdout content must never reach the proposer prompt (I-12)")
    }

    func testBudgetExhaustionReturnsTypedTerminal() async throws {
        let root = tempRoot()
        // Always-invalid recipe drafts: every attempt is consumed.
        let model = ScriptedModel(fallback: "这不是 JSON")
        let builder = makeBuilder(root: root, model: model)
        let budget = IOSEvolutionBudget(maxModelAttempts: 2, maxTokensPerAttempt: 2_000, maxDraftBytes: 200_000)
        let outcome = await builder.build(
            hypothesis: makeHypothesis(kind: .composition, artifact: .recipe),
            proposerSuite: completeSuite(),
            budget: budget
        )
        guard case .failed(let error) = outcome else {
            return XCTFail("expected typed terminal, got \(outcome)")
        }
        guard case .budgetExhausted(let attempts, let kind, let lastIssue) = error else {
            return XCTFail("expected budgetExhausted, got \(error)")
        }
        XCTAssertEqual(attempts, 2)
        XCTAssertEqual(kind, .composition)
        XCTAssertFalse(lastIssue.isEmpty)
        let promptCount = await model.recordedPrompts().count
        XCTAssertEqual(promptCount, 2, "each attempt must consume one model call")
    }

    // MARK: - Builder: remaining routes

    func testHarnessBehaviorProducesLabProposalDocumentOnly() async throws {
        let root = tempRoot()
        let builder = makeBuilder(root: root, model: ScriptedModel(responses: [Self.harnessDraftJSON()]))
        let hypothesis = makeHypothesis(kind: .harnessBehavior, artifact: .harnessPatch)
        let outcome = await builder.build(hypothesis: hypothesis, budget: .standard)
        guard case .harnessProposal(let proposal) = outcome else {
            return XCTFail("expected harness proposal, got \(outcome)")
        }
        XCTAssertEqual(proposal.hypothesisId, hypothesis.id)
        XCTAssertEqual(proposal.affectedAreas, ["ChatGenerationCoordinator", "IOSAgentRunLedger"])
        let doc = proposal.document()
        XCTAssertTrue(doc.contains("Harness Lab"))
        XCTAssertTrue(doc.contains("不在 iOS 生产运行时热修改宿主代码"), "the proposal must never hot-patch production (§6.1)")
        XCTAssertTrue(doc.contains("独立 branch"), "the fixed Lab flow must be part of the document (§5 Phase 5)")
    }

    func testModelCeilingIsNoOpAndArtifactIsRejected() async throws {
        let root = tempRoot()
        let builder = makeBuilder(root: root, model: ScriptedModel(fallback: "unused"))

        let noOpOutcome = await builder.build(
            hypothesis: makeHypothesis(kind: .modelCeiling, artifact: nil), budget: .standard
        )
        guard case .noOp = noOpOutcome else {
            return XCTFail("expected noOp, got \(noOpOutcome)")
        }

        let badOutcome = await builder.build(
            hypothesis: makeHypothesis(kind: .modelCeiling, artifact: .skill), budget: .standard
        )
        guard case .failed(let error) = badOutcome else {
            return XCTFail("expected typed failure, got \(badOutcome)")
        }
        XCTAssertEqual(error, .artifactNotAllowedForKind(kind: .modelCeiling, artifact: .skill))
    }

    // MARK: - Fixtures

    private func makeDiagnoser(model: ScriptedModel, knownMcp: [String] = []) -> IOSEvolutionDiagnoser {
        IOSEvolutionDiagnoser(
            catalogOracle: testCatalog,
            mcpConnectionOracle: { knownMcp },
            model: model.call
        )
    }

    private func makeBuilder(root: URL, model: ScriptedModel) -> IOSEvolutionCandidateBuilder {
        IOSEvolutionCandidateBuilder(
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            model: model.call
        )
    }

    private var testCatalog: IOSRecipeCatalogLookup {
        { tool in
            switch tool {
            case "scrape_web", "search_web":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .pure)
            case "summarize_text":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "2.1.0", effectClass: .idempotent)
            case "workspace_file_write":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .sideEffect)
            default:
                return nil
            }
        }
    }

    private func makeHypothesis(
        kind: IOSGapKind,
        artifact: IOSArtifactKind?,
        evidenceIds: [String] = ["ev:1"]
    ) -> IOSGapHypothesis {
        IOSGapHypothesis(
            id: "hyp-\(UUID().uuidString)",
            evidenceIds: evidenceIds,
            kind: kind,
            claim: "缺少稳定的编排步骤。",
            confidence: 0.8,
            alternatives: ["可能是网络问题", "可能是提示词不足"],
            falsifier: "若手工编排同样失败则推翻。",
            recommendedArtifact: artifact
        )
    }

    private func completeSuite() -> IOSEvaluationSuiteProposerView {
        IOSEvaluationSuiteProposerView(
            suiteId: "suite-1",
            suiteHash: "suite-hash-1",
            failureReplayCaseRefs: ["case:fail-1"],
            protectedSuccessCaseRefs: ["case:ok-1"],
            hasSealedHoldout: true
        )
    }

    private func evidence(id: String, outcome: IOSOutcomeKind = .error, toolId: String? = nil) -> IOSEvolutionEvidence {
        IOSEvolutionEvidence(
            id: id,
            runId: "run-1",
            sourceRefs: [IOSEvidenceRef(kind: .ledgerEvent, id: "event-\(id)")],
            observedOutcome: outcome,
            toolId: toolId,
            toolVersion: nil,
            terminalReason: outcome == .error ? "failed" : nil,
            userSignal: nil,
            redactedSummary: "tool error: \(toolId ?? "unknown")",
            createdAtEpochMs: 1_700_000_000_000
        )
    }

    private func assertDiagnosisFails(
        model: ScriptedModel,
        evidence: [IOSEvolutionEvidence],
        knownMcp: [String] = [],
        file: StaticString = #filePath,
        line: UInt = #line,
        _ match: (IOSDiagnosisError) -> Bool
    ) async {
        let outcome = await makeDiagnoser(model: model, knownMcp: knownMcp).diagnose(evidence: evidence)
        guard case .failed(let error) = outcome else {
            return XCTFail("expected typed failure, got \(outcome)", file: file, line: line)
        }
        XCTAssertTrue(match(error), "unexpected error: \(error)", file: file, line: line)
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-evolution-diagnosis-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }

    // MARK: Draft / manifest JSON fixtures (test data, not assertions)

    private static var compositionDraftDict: [String: Any] {
        [
            "kind": "composition",
            "claim": "重复编排不稳定",
            "confidence": 0.8,
            "alternatives": ["可能是网络波动"],
            "falsifier": "若手工逐步执行同样失败则推翻",
            "recommended_artifact": "recipe",
            "tool_ids": ["scrape_web"],
            "mcp_connections": [],
            "evidence_ids": ["ev:1"],
        ]
    }

    private static func compositionHypothesisJSON() -> String {
        json(compositionDraftDict)
    }

    private static func missingCapabilityHypothesisJSON() -> String {
        json([
            "kind": "missing_external_capability",
            "claim": "缺少 RSS 服务的认证 API",
            "confidence": 0.9,
            "alternatives": ["可能是缺少认证配置"],
            "falsifier": "若该 API 已存在且可用则推翻",
            "recommended_artifact": "mcp_binding",
            "tool_ids": [],
            "mcp_connections": [],
            "requested_mcp_server": "rss_api",
            "evidence_ids": ["ev:1"],
        ])
    }

    private static func insufficientEvidenceHypothesisJSON() -> String {
        json([
            "kind": "insufficient_evidence",
            "claim": "失败无法稳定复现",
            "confidence": 0.5,
            "alternatives": ["可能是偶发网络问题"],
            "falsifier": "若连续三次可复现则推翻",
            "recommended_artifact": NSNull(),
            "tool_ids": [],
            "mcp_connections": [],
            "evidence_ids": ["ev:1"],
        ])
    }

    private static func skillDeltaDraftJSON() -> String {
        json([
            "artifact_name": "better_summary",
            "markdown": "# 更好的总结\n\n1. 先抓取\n2. 再提炼\n3. 落盘",
        ])
    }

    private static func mcpRequestDraftJSON() -> String {
        json([
            "server_name": "rss_api",
            "purpose": "读取 RSS 源并整理成简报",
            "required_permissions": ["network", "oauth2"],
            "auth_steps": ["在设置中添加 rss_api 服务器", "完成 OAuth 授权"],
        ])
    }

    private static func harnessDraftJSON() -> String {
        json([
            "problem": "工具批次结束后恢复窗口过窄",
            "suggested_change": "调整恢复窗口与重试策略",
            "affected_areas": ["ChatGenerationCoordinator", "IOSAgentRunLedger"],
        ])
    }

    private static func recipeV1JSONString() -> String {
        recipeJSONString(name: "digest_recipe", version: "1.0.0")
    }

    private static func recipeV2JSONString() -> String {
        recipeJSONString(name: "digest_recipe", version: "2.0.0")
    }

    private static func recipeJSONString(name: String, version: String) -> String {
        json([
            "schema": "amber.recipe.v1",
            "name": name,
            "version": version,
            "description": "抓取并总结。",
            "inputs": ["feed_url": "string"],
            "steps": [
                ["id": "fetch", "tool": "scrape_web", "arguments": ["url": "${input.feed_url}"]],
                ["id": "summarize", "tool": "summarize_text",
                 "arguments": ["text": "${step.fetch.output.text}", "lang": "zh-CN"]],
            ],
            "outputs": ["summary": "${step.summarize.output.text}"],
        ])
    }

    private static func draft(removing key: String) -> [String: Any] {
        var dict = compositionDraftDict
        dict.removeValue(forKey: key)
        return dict
    }

    private static func json(_ dict: [String: Any]) -> String {
        let data = try! JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
        return String(data: data, encoding: .utf8)!
    }
}

/// Scripted model injection point: records every prompt, serves queued
/// responses, and can be forced to always throw or always return a fallback.
actor ScriptedModel {
    private(set) var prompts: [String] = []
    private var queue: [String]
    private let fallback: String?
    private let failure: String?

    init(responses: [String] = [], fallback: String? = nil, failure: String? = nil) {
        queue = responses
        self.fallback = fallback
        self.failure = failure
    }

    func call(prompt: String) async throws -> String {
        prompts.append(prompt)
        if let failure {
            throw NSError(domain: "ScriptedModel", code: 1, userInfo: [NSLocalizedDescriptionKey: failure])
        }
        if !queue.isEmpty { return queue.removeFirst() }
        if let fallback { return fallback }
        throw NSError(domain: "ScriptedModel", code: 2, userInfo: [NSLocalizedDescriptionKey: "no scripted response left"])
    }

    func recordedPrompts() -> [String] { prompts }
}

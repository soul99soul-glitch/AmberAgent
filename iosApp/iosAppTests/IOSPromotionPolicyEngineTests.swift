import XCTest
@testable import iosApp

/// Phase 2 Wave C: promotion policy engine tests (§13.4 / §9.6 / 不变量 7/16/17).
///
/// Covers:
///   - 分级矩阵：pure recipe→T0、networkRead/本地幂等→T1、外部副作用/权限扩大/
///     MCP→T2、无包络→永不自动、Skill 纯文本→T0；
///   - 硬门禁：无 report / report hash 失配 / recommendation≠promote /
///     protected 回归 / draftOnly / kill switch / 自治级别 / 冷却 / 预算 / 熔断
///     → 全部不自动；T0/T1 全绿 → 自动发布且 receipt approvedBy 为
///     policy engine + 版本、registry 下一 round 可见（真实 store + registry）；
///   - 熔断：同一制品连续两次 rollback → 自治关闭（持久化）+ 通知信号；
///   - 设置三件套：kill switch / 自治级别键的默认值与读取（与设置页同 keys）。
@MainActor
final class IOSPromotionPolicyEngineTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDown() async throws {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    // MARK: - §13.4 分级矩阵

    func testClassificationMatrix() {
        func classifyRecipe(_ raw: String) -> IOSPromotionTier {
            IOSPromotionPolicyEngine.classify(
                artifactKind: .recipe,
                permissionEnvelopeRaw: [raw]
            ).tier
        }
        XCTAssertEqual(classifyRecipe(IOSToolEffectClass.pure.rawValue), .t0, "全 pure step Recipe → T0")
        XCTAssertEqual(classifyRecipe(IOSToolEffectClass.networkRead.rawValue), .t1, "仅 networkRead → T1")
        XCTAssertEqual(classifyRecipe(IOSToolEffectClass.idempotent.rawValue), .t1, "本地可逆副作用（幂等）→ T1")
        XCTAssertEqual(classifyRecipe(IOSToolEffectClass.sideEffect.rawValue), .t2, "外部副作用/权限扩大 → T2")
        XCTAssertEqual(
            IOSPromotionPolicyEngine.classify(artifactKind: .recipe, permissionEnvelopeRaw: []).tier,
            .neverAuto,
            "无权限包络 → 永不自动"
        )
        XCTAssertEqual(
            IOSPromotionPolicyEngine.classify(artifactKind: .mcpBinding, permissionEnvelopeRaw: []).tier,
            .t2,
            "新 MCP server 绑定 → T2"
        )
        XCTAssertEqual(
            IOSPromotionPolicyEngine.classify(artifactKind: .harnessPatch, permissionEnvelopeRaw: []).tier,
            .t2,
            "harness patch → T2（只进 Lab）"
        )
        XCTAssertEqual(
            IOSPromotionPolicyEngine.classify(artifactKind: .skill, permissionEnvelopeRaw: []).tier,
            .t0,
            "Skill/Playbook 纯文本 delta → T0"
        )
        XCTAssertEqual(
            IOSPromotionPolicyEngine.classify(artifactKind: .skill, permissionEnvelopeRaw: ["sideEffect"]).tier,
            .t2,
            "携带权限包络的 Skill → T2（超出纯文本范围）"
        )
        // networkRead step 升入 T1（T0 只允许全 pure，§13.4 表格）。
        XCTAssertEqual(
            IOSPromotionPolicyEngine.classify(artifactKind: .recipe, permissionEnvelopeRaw: ["pure", "networkRead"]).tier,
            .t1,
            "混入 networkRead step 的 Recipe → T1（保守上界）"
        )
    }

    // MARK: - 硬门禁

    func testNoReportNeverAuto() {
        let decision = decide(recipe: "pure", report: nil)
        XCTAssertFalse(decision.canAutoApprove)
        XCTAssertTrue(decision.gateFailures.contains { $0.hasPrefix("no_evaluation_report") })
        XCTAssertEqual(decision.tier, .t0, "分级仍报告 T0；门禁决定是否自动")
    }

    func testReportHashMismatchNeverAuto() {
        let report = makeReport(candidateHash: "some-other-hash")
        let decision = decide(recipe: "pure", report: report)
        XCTAssertFalse(decision.canAutoApprove)
        XCTAssertTrue(decision.gateFailures.contains { $0.hasPrefix("report_candidate_hash_mismatch") })
    }

    func testRecommendationNotPromoteNeverAuto() {
        let report = makeReport(candidateHash: fixtureHash, recommendation: .manualJudgmentRequired)
        let decision = decide(recipe: "pure", report: report)
        XCTAssertFalse(decision.canAutoApprove)
        XCTAssertTrue(decision.gateFailures.contains { $0.hasPrefix("recommendation_not_promote") })
    }

    func testProtectedRegressionNeverAuto() {
        let report = makeReport(candidateHash: fixtureHash, protectedRegressions: 2)
        let decision = decide(recipe: "pure", report: report)
        XCTAssertFalse(decision.canAutoApprove)
        XCTAssertTrue(decision.gateFailures.contains { $0.hasPrefix("protected_regressions") })
    }

    func testDraftOnlyNeverAuto() {
        let decision = decide(recipe: "pure", report: makeReport(candidateHash: fixtureHash), draftOnly: true)
        XCTAssertFalse(decision.canAutoApprove)
        XCTAssertTrue(decision.gateFailures.contains { $0.hasPrefix("draft_only") })
    }

    func testKillSwitchBlocksEverything() {
        let decision = decide(
            recipe: "pure",
            report: makeReport(candidateHash: fixtureHash),
            autonomyLevel: .t0T1Auto,
            killSwitchEnabled: true
        )
        XCTAssertFalse(decision.canAutoApprove)
        XCTAssertTrue(decision.gateFailures.contains { $0.hasPrefix("kill_switch") })
    }

    func testAutonomyLevelAllManualAndNotifyOnlyBlockAuto() {
        let allManual = decide(
            recipe: "pure",
            report: makeReport(candidateHash: fixtureHash),
            autonomyLevel: .allManual
        )
        XCTAssertFalse(allManual.canAutoApprove)
        XCTAssertTrue(allManual.gateFailures.contains { $0.hasPrefix("autonomy_level_all_manual") })

        let notifyOnly = decide(
            recipe: "pure",
            report: makeReport(candidateHash: fixtureHash),
            autonomyLevel: .notifyOnly
        )
        XCTAssertFalse(notifyOnly.canAutoApprove)
        XCTAssertTrue(notifyOnly.gateFailures.contains { $0.hasPrefix("autonomy_level_notify_only") })
    }

    func testCooldownBlocksSameArtifactUntilItExpires() {
        let state = makeState()
        let config = IOSPromotionPolicyConfiguration(
            dailyAutoPromotionLimit: 10,
            cooldownAfterPromotionSeconds: 3600,
            cooldownAfterRollbackSeconds: 3600,
            maxConsecutiveRollbacksBeforeCircuitBreak: 2
        )
        let now = Date()
        state.recordAutoPromotion(artifactId: fixtureName, now: now, configuration: config)

        let blocked = decide(
            recipe: "pure",
            report: makeReport(candidateHash: fixtureHash),
            state: state,
            now: now.addingTimeInterval(60),
            config: config
        )
        XCTAssertFalse(blocked.canAutoApprove)
        XCTAssertTrue(blocked.gateFailures.contains { $0.hasPrefix("cooldown") })

        let allowed = decide(
            recipe: "pure",
            report: makeReport(candidateHash: fixtureHash),
            state: state,
            now: now.addingTimeInterval(3600 + 1),
            config: config
        )
        XCTAssertTrue(allowed.canAutoApprove, "冷却期过后同一制品恢复自动")
    }

    func testDailyBudgetExhaustionBlocksAutoPromotion() {
        let state = makeState()
        let config = IOSPromotionPolicyConfiguration(
            dailyAutoPromotionLimit: 1,
            cooldownAfterPromotionSeconds: 0,
            cooldownAfterRollbackSeconds: 0,
            maxConsecutiveRollbacksBeforeCircuitBreak: 2
        )
        let now = Date()
        state.recordAutoPromotion(artifactId: "other-recipe", now: now, configuration: config)

        let decision = decide(
            recipe: "pure",
            report: makeReport(candidateHash: fixtureHash),
            state: state,
            now: now,
            config: config
        )
        XCTAssertFalse(decision.canAutoApprove)
        XCTAssertTrue(decision.gateFailures.contains { $0.hasPrefix("daily_budget_exhausted") })

        // 新的一天（跨 UTC 日期）预算复位。
        let tomorrow = now.addingTimeInterval(2 * 24 * 3600)
        let reset = decide(
            recipe: "pure",
            report: makeReport(candidateHash: fixtureHash),
            state: state,
            now: tomorrow,
            config: config
        )
        XCTAssertTrue(reset.canAutoApprove, "跨日后每日预算复位")
    }

    func testCircuitBrokenBlocksAutoPromotion() {
        let state = makeState()
        let config = IOSPromotionPolicyConfiguration()
        // 同一制品连续两次回退 → 熔断。
        XCTAssertFalse(state.recordRollback(artifactId: fixtureName, configuration: config))
        let tripped = state.recordRollback(artifactId: fixtureName, configuration: config)
        XCTAssertTrue(tripped, "第二次回退触发熔断")
        XCTAssertTrue(state.isAutonomyDisabled(artifactId: fixtureName))

        let decision = decide(
            recipe: "pure",
            report: makeReport(candidateHash: fixtureHash),
            state: state
        )
        XCTAssertFalse(decision.canAutoApprove)
        XCTAssertTrue(decision.gateFailures.contains { $0.hasPrefix("circuit_broken") })
    }

    // MARK: - T0/T1 全绿 → 自动发布（真实 store + registry + receipt）

    func testT0AllGreenAutoPublishesWithPolicyReceiptAndNextRoundVisibility() async throws {
        let root = tempRoot()
        let store = IOSRecipeFileStore(baseDirectory: root)
        let receiptStore = IOSPromotionReceiptStore(baseDirectory: root)
        let registry = IOSDynamicToolRegistry(baseDirectory: root)

        // Real candidate bytes + hash (invariant 5: what apply would publish).
        let content = try recipeData(tool: "tools_list")
        let preparation = try store.prepareRecipe(recipeJSON: content)
        let manifest = makeManifest(
            name: preparation.candidate.name,
            candidateHash: preparation.candidate.hash,
            envelope: ["pure"]
        )
        let report = makeReport(candidateHash: preparation.candidate.hash)

        let state = makeState(root: root)
        let decision = decide(
            recipe: "pure",
            report: report,
            state: state,
            candidateHash: preparation.candidate.hash
        )
        XCTAssertTrue(decision.canAutoApprove, "T0 全绿 → 自动授权: \(decision.gateFailures)")

        // 授权后立即 apply（CAS 不变）→ registry.refresh → receipt（不变量 7/16/17）。
        let publish = try await IOSPromotionPublisher.publishRecipe(
            content: content,
            manifest: manifest,
            report: report,
            approvedBy: IOSPromotionPolicyEngine.approvedBy,
            recipeStore: store,
            receiptStore: receiptStore,
            refreshRegistry: { await registry.refresh() }
        )
        XCTAssertEqual(publish.promotionReceipt.approvedBy, IOSPromotionPolicyEngine.approvedBy)
        XCTAssertTrue(
            publish.promotionReceipt.approvedBy.contains(IOSPromotionPolicyEngine.policyVersion),
            "approvedBy 必须记录 policy engine 标识 + 策略版本（不变量 7/16）"
        )
        XCTAssertEqual(publish.promotionReceipt.toHash, preparation.candidate.hash)
        XCTAssertEqual(publish.promotionReceipt.evaluationReportHash, report.reportHash)
        XCTAssertEqual(try store.readLiveRecipe(name: preparation.candidate.name).hash, preparation.candidate.hash)

        // registry 下一 round 可见（§13.2: 新 revision 携带新制品）。
        let snapshot = await registry.refresh()
        XCTAssertNotNil(snapshot)
        XCTAssertGreaterThanOrEqual(snapshot?.revision ?? 0, 2, "发布必须 bump catalog revision")
        XCTAssertTrue(
            snapshot?.recipeTools.contains { $0.recipeName == preparation.candidate.name } == true,
            "下一模型轮的 catalog snapshot 必须包含新 Recipe"
        )
        // 持久化 receipt 的 active 也是 policy engine。
        let persisted = receiptStore.snapshot(artifactId: preparation.candidate.name)
        XCTAssertEqual(persisted?.active?.approvedBy, IOSPromotionPolicyEngine.approvedBy)
    }

    func testT1NetworkReadAllGreenAutoPublishes() async throws {
        let root = tempRoot()
        let store = IOSRecipeFileStore(baseDirectory: root)
        let content = try recipeData(tool: "scrape_web")
        let preparation = try store.prepareRecipe(recipeJSON: content)
        let report = makeReport(candidateHash: preparation.candidate.hash)
        let decision = decide(
            recipe: "networkRead",
            report: report,
            state: makeState(root: root),
            candidateHash: preparation.candidate.hash
        )
        XCTAssertEqual(decision.tier, .t1)
        XCTAssertTrue(decision.canAutoApprove)
    }

    // MARK: - 设置三件套（keys 默认值与读取；与 EvolutionSettingsView 同 keys）

    func testAutonomyLevelAndKillSwitchDefaultsAndReads() {
        let suiteName = "IOSPromotionPolicyEngineTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        // 默认：T0+T1 自动、kill switch 关。
        XCTAssertEqual(IOSPromotionPolicyEngine.currentAutonomyLevel(defaults: defaults), .t0T1Auto)
        XCTAssertFalse(IOSPromotionPolicyEngine.killSwitchEnabled(defaults: defaults))

        defaults.set(IOSEvolutionAutonomyLevel.notifyOnly.rawValue, forKey: IOSEvolutionPreferenceKeys.autonomyLevel)
        defaults.set(true, forKey: IOSEvolutionPreferenceKeys.killSwitch)
        XCTAssertEqual(IOSPromotionPolicyEngine.currentAutonomyLevel(defaults: defaults), .notifyOnly)
        XCTAssertTrue(IOSPromotionPolicyEngine.killSwitchEnabled(defaults: defaults))

        defaults.set("garbage", forKey: IOSEvolutionPreferenceKeys.autonomyLevel)
        XCTAssertEqual(IOSPromotionPolicyEngine.currentAutonomyLevel(defaults: defaults), .t0T1Auto, "非法值回退默认")
    }

    // MARK: - Fixtures

    private let fixtureHash = "candidate-hash-\(UUID().uuidString)"
    private let fixtureName = "digest_recipe"

    private func decide(
        recipe envelopeRaw: String,
        report: IOSEvaluationReport?,
        draftOnly: Bool = false,
        autonomyLevel: IOSEvolutionAutonomyLevel = .t0T1Auto,
        killSwitchEnabled: Bool = false,
        state: IOSPromotionPolicyStateStore? = nil,
        now: Date = Date(),
        config: IOSPromotionPolicyConfiguration = .standard,
        candidateHash: String? = nil
    ) -> IOSPromotionPolicyDecision {
        IOSPromotionPolicyEngine.decide(
            input: IOSPromotionPolicyInput(
                artifactKind: .recipe,
                artifactName: fixtureName,
                permissionEnvelopeRaw: [envelopeRaw],
                candidateHash: candidateHash ?? fixtureHash,
                report: report,
                draftOnly: draftOnly
            ),
            state: state ?? makeState(),
            autonomyLevel: autonomyLevel,
            killSwitchEnabled: killSwitchEnabled,
            now: now,
            configuration: config
        )
    }

    // MARK: - Slice B：发布层 identity 门禁（红测试 4）

    /// Slice B 红测试 4：candidate/report/originRun 任一 identity 不匹配 →
    /// promotion 零写入（typed 错误，active / receipt / registry 全不落）。
    /// - report.originRunId != expectedOriginRunId → 抛错且 readLiveRecipe 不存在；
    /// - report.candidateHash 与 manifest.candidateHash 不匹配 → 同样零写
    ///   （publishRecipe 是最后一层防线，policy engine 之外必须再验一遍）。
    /// - identity 匹配时发布正常（防过度防御）。
    func testPublishRecipeRejectsIdentityMismatchWithZeroWrites() async throws {
        let root = tempRoot()
        let content = try recipeData(tool: "tools_list")
        let preparation = try IOSRecipeFileStore(baseDirectory: root).prepareRecipe(recipeJSON: content)
        let manifest = makeManifest(
            name: preparation.candidate.name,
            candidateHash: preparation.candidate.hash,
            envelope: ["pure"]
        )

        // 1. originRunId 不匹配 → 零写入。
        let mismatchedOriginReport = makeReport(
            candidateHash: preparation.candidate.hash,
            originRunId: "run-origin-A"
        )
        try await assertPublishFailsZeroWrites(
            root: root,
            content: content,
            manifest: manifest,
            report: mismatchedOriginReport,
            expectedOriginRunId: "run-origin-B"
        )

        // 2. report 的 candidateHash 与 manifest 不匹配 → 同样零写。
        let mismatchedHashReport = makeReport(
            candidateHash: "candidate-hash-other",
            originRunId: "run-origin-A"
        )
        try await assertPublishFailsZeroWrites(
            root: root,
            content: content,
            manifest: manifest,
            report: mismatchedHashReport,
            expectedOriginRunId: "run-origin-A"
        )

        // 3. identity 匹配 → 发布正常（防过度防御）。
        let goodReport = makeReport(
            candidateHash: preparation.candidate.hash,
            originRunId: "run-origin-A"
        )
        let store = IOSRecipeFileStore(baseDirectory: root)
        let receiptStore = IOSPromotionReceiptStore(baseDirectory: root)
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
        let publish = try await IOSPromotionPublisher.publishRecipe(
            content: content,
            manifest: manifest,
            report: goodReport,
            approvedBy: "user",
            expectedOriginRunId: "run-origin-A",
            recipeStore: store,
            receiptStore: receiptStore,
            refreshRegistry: { await registry.refresh() }
        )
        XCTAssertEqual(
            try store.readLiveRecipe(name: preparation.candidate.name).hash,
            preparation.candidate.hash
        )
        XCTAssertEqual(publish.promotionReceipt.evaluationReportHash, goodReport.reportHash)
        XCTAssertEqual(
            receiptStore.snapshot(artifactId: preparation.candidate.name)?.active?.toHash,
            preparation.candidate.hash
        )
    }

    /// 身份不匹配的发布必须：抛 typed 错误 + active/receipt/registry 零写入。
    private func assertPublishFailsZeroWrites(
        root: URL,
        content: Data,
        manifest: IOSEvolutionCandidateManifest,
        report: IOSEvaluationReport,
        expectedOriginRunId: String?
    ) async throws {
        let store = IOSRecipeFileStore(baseDirectory: root)
        let receiptStore = IOSPromotionReceiptStore(baseDirectory: root)
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
        do {
            _ = try await IOSPromotionPublisher.publishRecipe(
                content: content,
                manifest: manifest,
                report: report,
                approvedBy: "user",
                expectedOriginRunId: expectedOriginRunId,
                recipeStore: store,
                receiptStore: receiptStore,
                refreshRegistry: { await registry.refresh() }
            )
            XCTFail("identity 不匹配的发布必须抛错（零写入）")
        } catch {
            XCTAssertTrue(
                error is IOSPromotionPublishError,
                "必须抛 typed 发布错误，got \(error)"
            )
        }
        XCTAssertThrowsError(
            try store.readLiveRecipe(name: manifest.artifactName),
            "identity 不匹配必须零写入（active 不存在）"
        )
        XCTAssertNil(
            receiptStore.snapshot(artifactId: manifest.artifactName),
            "identity 不匹配必须零写入（receipt 不存在）"
        )
        let snapshot = await registry.refresh()
        XCTAssertFalse(
            snapshot?.recipeTools.contains { $0.recipeName == manifest.artifactName } == true,
            "identity 不匹配必须零写入（registry 不含制品）"
        )
    }

    private func makeState(root: URL? = nil) -> IOSPromotionPolicyStateStore {
        IOSPromotionPolicyStateStore(baseDirectory: root ?? tempRoot())
    }

    private func makeReport(
        candidateHash: String,
        recommendation: IOSEvaluationRecommendation = .promote,
        protectedRegressions: Int = 0,
        results: [IOSEvaluationCaseResult] = [],
        originRunId: String? = nil
    ) -> IOSEvaluationReport {
        IOSEvaluationReport(
            reportId: "report-\(UUID().uuidString)",
            candidateHash: candidateHash,
            evaluatorVersion: IOSArtifactEvaluator.evaluatorVersion,
            suiteHash: "suite-hash",
            results: results,
            protectedRegressions: protectedRegressions,
            unresolvedRisks: [],
            skippedTiers: [.llmJudge, .stochasticRepeat],
            recommendation: recommendation,
            originRunId: originRunId
        )
    }

    private func makeManifest(
        name: String,
        candidateHash: String,
        envelope: [String]
    ) -> IOSEvolutionCandidateManifest {
        IOSEvolutionCandidateManifest(
            candidateId: "cand-\(UUID().uuidString)",
            artifactKind: .recipe,
            artifactName: name,
            parentVersion: nil,
            baseHash: nil,
            candidateHash: candidateHash,
            hypothesisId: "hyp-1",
            evaluationCaseRefs: ["case:replay-1", "case:protected-1"],
            permissionEnvelope: envelope,
            draftOnly: false,
            createdAtEpochMs: 1_700_000_000_000
        )
    }

    /// Single-step recipe; `tool` must exist in the real catalog oracle
    /// (`IOSDynamicToolRegistry.primitiveCatalogEntry`) — "tools_list" is pure,
    /// "scrape_web" is networkRead.
    private func recipeData(tool: String) throws -> Data {
        let dict: [String: Any] = [
            "schema": "amber.recipe.v1",
            "name": "digest_recipe",
            "version": "1.0.0",
            "description": "抓取网页正文。",
            "inputs": ["url": "string"],
            "steps": [
                ["id": "fetch", "tool": tool, "arguments": ["url": "${input.url}"]],
            ],
            "outputs": ["text": "${step.fetch.output.text}"],
        ]
        return try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-promotion-policy-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }
}

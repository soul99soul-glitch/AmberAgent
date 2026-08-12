import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Phase 4 Wave 1: §19 观测指标 store + 埋点 + 扩面数据闸门测试。
///
/// 事件计数全部走真实 workflow / policy / registry / recipe_import 路径触发
/// （非手工调 record），再断言落在注入的临时 metrics store：
///   - 各终止路径：noOp / 草稿降级 / 用户拒绝 / 人工发布 / 自动发布 / 失败；
///   - policy 门禁拒绝（带原因键）、protected regression 拦截、评测失败；
///   - rollback 区分用户主动 / 熔断建议 + 熔断计数；
///   - 7/30 天 rollback 率窗口计算；
///   - catalog revision ↔ lease 不一致哨兵恒 0（正常路径）；
///   - §18.2 隐私：metrics 文档不含消息正文标记；
///   - wideningGate：无数据/数据不足/率越限 → refuse 并说明；足够数据 → allow；
///   - 持久化往返 + 坏文件降级空态；
///   - recipe step 失败 / 权限拒绝分布 API（runner/approval 层埋点为后续
///     wave，本测试只验证 store 契约）。
@MainActor
final class IOSEvolutionMetricsTests: XCTestCase {
    private var tempDirs: [URL] = []
    private var databases: [AgentRuntimeDatabase] = []

    override func tearDown() async throws {
        databases.removeAll()
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    // MARK: - 真实路径埋点：批准发布 / 无证据 no-op / 用户拒绝 / 人工发布

    /// Slice A 契约变更：T0/T1 自动通道只对确定性 oracle 套件可达，v1 oracle
    /// 只覆盖 workspace 原语（sideEffect → T2），真实发布路径 = oracle-backed
    /// 套件 → 人工批准（.userApprove / source=.manual）。
    func testApprovedPublishPathRecordsUserApproveAndPromotionTimestamp() async throws {
        let (root, dao, metrics) = makeEnvironment()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-metrics-auto", toolName: "workspace_file_write")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "workspace_file_write" })
        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics)
        let workflow = makeWorkflow(
            root: root, dao: dao, ledger: ledger, model: model, metrics: metrics,
            registry: registry,
            suite: Self.workspaceOracleSuiteResult()
        )

        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value
        XCTAssertNotNil(workflow.pendingApproval, "T2 必须出现人工批准卡")
        let approve = workflow.approvePending()
        await approve?.value

        let snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.count(.userApprove), 1, "人工批准发布必须落计数")
        XCTAssertEqual(snapshot.promotionCount, 1, "晋升时间戳序列 +1")
        XCTAssertEqual(snapshot.promotions.first?.source, .manual)
        XCTAssertEqual(snapshot.promotions.first?.artifactId, "note_writer")
        XCTAssertEqual(snapshot.count(.autoPublish), 0, "人工通道不得记自动发布")
        XCTAssertEqual(snapshot.count(.userReject), 0)
        XCTAssertTrue(snapshot.gateDenialsByReason.isEmpty, "oracle-backed promote 不得有证据门禁拒绝")
        // 时间戳为真实当前时间（±1 分钟内）。
        let at = snapshot.promotions.first?.atEpochMs ?? 0
        XCTAssertLessThanOrEqual(abs(at - Int64(Date().timeIntervalSince1970 * 1000)), 60_000)
    }

    func testNoEvidencePathRecordsEvidenceNoOp() async throws {
        let (root, dao, metrics) = makeEnvironment()
        let ledger = IOSAgentRunLedger(dao: dao)
        let model = ScriptedModel(fallback: "不应被调用")
        let workflow = makeWorkflow(
            root: root, dao: dao, ledger: ledger, model: model, metrics: metrics,
            registry: IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics)
        )

        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value

        let snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.count(.evidenceNoOp), 1, "无可归因证据 → no-op 计数（I-3）")
        XCTAssertEqual(snapshot.count(.diagnosisNoOp), 0)
        XCTAssertEqual(snapshot.promotionCount, 0)
    }

    func testUserRejectAndUserApprovePathsRecordRespectiveOutcomes() async throws {
        // 拒绝路径：T2 卡 → denyPending。
        let (root, dao, metrics) = makeEnvironment()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-metrics-deny", toolName: "workspace_file_write")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "workspace_file_write" })
        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "workspace_file_write", name: "save_digest"),
        ])
        let workflow = makeWorkflow(
            root: root, dao: dao, ledger: ledger, model: model, metrics: metrics,
            registry: IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics),
            suite: Self.sideEffectGreenSuiteResult()
        )
        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value
        XCTAssertNotNil(workflow.pendingApproval, "T2 必须出现人工批准卡")
        workflow.denyPending()
        var snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.count(.userReject), 1, "拒绝候选 → userReject")
        XCTAssertEqual(snapshot.promotionCount, 0, "拒绝不得产生晋升记录")
        XCTAssertEqual(snapshot.count(.userApprove), 0)

        // 批准路径：同一环境再来一次 → approvePending 发布。
        let model2 = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "workspace_file_write", name: "save_digest"),
        ])
        let workflow2 = makeWorkflow(
            root: root, dao: dao, ledger: ledger, model: model2, metrics: metrics,
            registry: IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics),
            suite: Self.sideEffectGreenSuiteResult()
        )
        let task2 = workflow2.analyzeAndImprove(conversationHex: nil)
        await task2?.value
        let card = try XCTUnwrap(workflow2.pendingApproval)
        let approve = workflow2.approvePending()
        await approve?.value

        snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.count(.userApprove), 1, "人工批准发布 → userApprove")
        XCTAssertEqual(snapshot.promotionCount, 1)
        XCTAssertEqual(snapshot.promotions.first?.source, .manual, "人工发布来源 = manual")
        XCTAssertEqual(snapshot.promotions.first?.artifactId, card.artifactName)
        XCTAssertEqual(snapshot.count(.userReject), 1, "此前拒绝仍保留")
    }

    func testDraftDowngradePathRecordsDraftDowngrade() async throws {
        let (root, dao, metrics) = makeEnvironment()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-metrics-draft", toolName: "scrape_web")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "scrape_web" })
        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "scrape_web", name: "fetch_digest"),
        ])
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "scrape_web (pure)",
            policyConfiguration: .standard,
            policyStateStore: IOSPromotionPolicyStateStore(baseDirectory: root),
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { false },
            // 没有 suite provider → 按 §15 停止条件降级为人工草稿。
            suiteProvider: nil,
            registryRefresh: { await IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics).refresh() },
            metrics: metrics
        )
        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value
        XCTAssertEqual(workflow.notifications.first?.kind, .draftOnly)
        let snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.count(.draftDowngrade), 1, "无套件 → 草稿降级计数")
        XCTAssertEqual(snapshot.count(.autoPublish), 0)
        XCTAssertEqual(snapshot.promotionCount, 0)
    }

    // MARK: - policy 门禁拒绝（带原因键）+ protected regression 拦截

    /// 真实评测路径：候选调用参数与受保护样例契约不符 → protected 回归 →
    /// 报告 reject → 门禁拒绝（带原因键）+ protectedRegressionBlocked +
    /// candidateEvalFail，且按 §12.2 硬拒绝（无人工批准卡，只有降级通知）。
    func testProtectedRegressionBlockedRecordsInterceptionAndGateDenials() async throws {
        let (root, dao, metrics) = makeEnvironment()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-metrics-protected", toolName: "scrape_web")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "scrape_web" })
        // 候选 step 参数与套件断言的字面 URL 不符 → 受保护样例必然回归。
        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "scrape_web", name: "fetch_digest", url: "https://wrong.example.com"),
        ])
        let workflow = makeWorkflow(
            root: root, dao: dao, ledger: ledger, model: model, metrics: metrics,
            registry: IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics)
        )
        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value

        // 报告建议 reject（protected 回归）→ 门禁拦截；按 §12.2 / 不变量 13
        // 硬拒绝——不展示人工批准卡（2026-08 复核修法：protected 回归不能
        // 人工批准绕过），降级为「先更新评测套件」通知。
        XCTAssertNil(workflow.pendingApproval, "protected 回归必须硬拒绝，不得出现人工批准卡")
        let notification = try XCTUnwrap(workflow.notifications.first)
        XCTAssertTrue(notification.summary.contains("受保护"), notification.summary)
        XCTAssertTrue(notification.summary.contains("评测套件"), notification.summary)
        let snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.count(.protectedRegressionBlocked), 1, "protected regression 拦截计数")
        XCTAssertEqual(snapshot.count(.candidateEvalFail), 1, "评测失败（报告建议不 promote）")
        XCTAssertEqual(snapshot.gateDenialCount(reasonKey: "protected_regressions"), 1, "门禁拒绝带原因键")
        XCTAssertEqual(snapshot.gateDenialCount(reasonKey: "recommendation_not_promote"), 1)
        XCTAssertEqual(snapshot.count(.autoPublish), 0, "拦截后不得自动发布")
        XCTAssertEqual(snapshot.promotionCount, 0)
    }

    /// kill switch 打开 → 分类放行但政策门禁拦截 → 拒绝原因键落计数。
    func testKillSwitchGateDenialRecordedWithReasonKey() async throws {
        let (root, dao, metrics) = makeEnvironment()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-metrics-kill", toolName: "scrape_web")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "scrape_web" })
        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "scrape_web", name: "fetch_digest"),
        ])
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "scrape_web (pure)",
            policyConfiguration: .standard,
            policyStateStore: IOSPromotionPolicyStateStore(baseDirectory: root),
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { true },
            suiteProvider: { _, _ in .built(suite: Self.allGreenSuite(), dataScopeSummary: "suite 数据范围") },
            registryRefresh: { await IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics).refresh() },
            metrics: metrics
        )
        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value

        XCTAssertNotNil(workflow.pendingApproval, "kill switch 下 T0 仍展示人工卡（带评估结论）")
        let snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.gateDenialCount(reasonKey: "kill_switch"), 1)
        XCTAssertEqual(snapshot.count(.autoPublish), 0)
        XCTAssertEqual(snapshot.count(.protectedRegressionBlocked), 0, "无受保护回归，不得误记拦截")
    }

    // MARK: - rollback：用户主动 / 熔断建议 + 熔断计数（真实回退路径）

    /// 批准发布 → 回退（用户主动）→ 再发布 → 回退（触发熔断）→ 再发布
    /// → 回退（熔断建议）。与 IOSEvolutionWorkflowTests 的熔断测试同构；
    /// Slice A 后发布路径均为 oracle-backed 套件 → 人工批准。
    func testRollbackRecordsUserInitiatedThenBreakerSuggestedAndTripsBreaker() async throws {
        let (root, dao, metrics) = makeEnvironment()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-metrics-rollback", toolName: "workspace_file_write")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "workspace_file_write" })
        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics)
        let policyState = IOSPromotionPolicyStateStore(baseDirectory: root)
        let config = IOSPromotionPolicyConfiguration(
            dailyAutoPromotionLimit: 10,
            cooldownAfterPromotionSeconds: 0,
            cooldownAfterRollbackSeconds: 0,
            maxConsecutiveRollbacksBeforeCircuitBreak: 2
        )
        var autonomyLevel: IOSEvolutionAutonomyLevel = .t0T1Auto
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "workspace_file_write (sideEffect)",
            policyConfiguration: config,
            policyStateStore: policyState,
            autonomyLevelProvider: { autonomyLevel },
            killSwitchProvider: { false },
            suiteProvider: { _, _ in Self.workspaceOracleSuiteResult() },
            registryRefresh: { await registry.refresh() },
            metrics: metrics
        )

        // 第 1 轮：oracle-backed promote → T2 人工卡 → 批准发布 → 用户主动回退（计数 1，未熔断）。
        let first = workflow.analyzeAndImprove(conversationHex: nil)
        await first?.value
        XCTAssertNotNil(workflow.pendingApproval, "T2 必须出现人工批准卡")
        let approve1 = workflow.approvePending()
        await approve1?.value
        let cardA = try XCTUnwrap(workflow.notifications.first { $0.kind == .autoPromoted && $0.artifactName == "note_writer" })
        let rollbackA = workflow.rollback(notificationId: cardA.id)
        await rollbackA?.value
        var snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.count(.rollbackUserInitiated), 1)
        XCTAssertEqual(snapshot.count(.circuitBreakerTripped), 0)
        XCTAssertEqual(snapshot.rollbacks.first?.source, .userInitiated)
        XCTAssertEqual(snapshot.rollbacks.first?.artifactId, "note_writer")
        XCTAssertFalse(policyState.isAutonomyDisabled(artifactId: "note_writer"))

        // 第 2 轮：再发布（不重置熔断计数）→ 回退触发熔断。
        autonomyLevel = .allManual
        let second = workflow.analyzeAndImprove(conversationHex: nil)
        await second?.value
        XCTAssertNotNil(workflow.pendingApproval, "全部人工档下也走人工卡")
        let approve2 = workflow.approvePending()
        await approve2?.value
        let cardB = try XCTUnwrap(workflow.notifications.first { $0.kind == .autoPromoted && $0.id != cardA.id })
        let rollbackB = workflow.rollback(notificationId: cardB.id)
        await rollbackB?.value
        snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.count(.rollbackUserInitiated), 2, "触发熔断的这一次回退本身是用户主动")
        XCTAssertEqual(snapshot.count(.circuitBreakerTripped), 1, "连续两次回退触发熔断")
        XCTAssertTrue(policyState.isAutonomyDisabled(artifactId: "note_writer"))

        // 第 3 轮：熔断状态下的回退 → 熔断建议来源。
        let third = workflow.analyzeAndImprove(conversationHex: nil)
        await third?.value
        XCTAssertNotNil(workflow.pendingApproval, "熔断后仍走人工卡（自治已关闭）")
        let approve3 = workflow.approvePending()
        await approve3?.value
        let cardC = try XCTUnwrap(workflow.notifications.first { $0.kind == .autoPromoted && $0.id != cardA.id && $0.id != cardB.id })
        let rollbackC = workflow.rollback(notificationId: cardC.id)
        await rollbackC?.value
        snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.count(.rollbackBreakerSuggested), 1, "熔断状态下的回退记熔断建议来源")
        XCTAssertEqual(snapshot.rollbacks.last?.source, .circuitBreakerSuggested)
        XCTAssertEqual(snapshot.count(.rollbackUserInitiated), 2)
    }

    // MARK: - 哨兵指标恒 0（正常路径）

    func testSentinelMetricsStayZeroOnNormalPath() async throws {
        let (root, dao, metrics) = makeEnvironment()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-metrics-sentinel", toolName: "workspace_file_write")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "workspace_file_write" })
        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
        ])
        // 注入同 metrics 的临时 registry：refresh 的哨兵断言点落同一 store。
        let registry = IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics)
        let workflow = makeWorkflow(
            root: root, dao: dao, ledger: ledger, model: model, metrics: metrics,
            registry: registry,
            suite: Self.workspaceOracleSuiteResult()
        )
        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value
        XCTAssertNotNil(workflow.pendingApproval, "T2 必须出现人工批准卡")
        let approve = workflow.approvePending()
        await approve?.value
        XCTAssertEqual(workflow.notifications.first?.kind, .autoPromoted)
        _ = await registry.refresh()

        let snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.count(.catalogLeaseInconsistency), 0, "catalog revision ↔ lease 不一致哨兵应恒 0")
        XCTAssertEqual(snapshot.count(.staleCASSkipped), 0, "正常路径无 stale CAS")
        XCTAssertEqual(snapshot.count(.publishFailed), 0)
        XCTAssertEqual(snapshot.count(.workflowFailed), 0)
        XCTAssertEqual(snapshot.count(.circuitBreakerTripped), 0)
    }

    // MARK: - 隐私（§18.2）：metrics 文档不含消息正文标记

    /// 真实 workflow 跑完（证据 redactedSummary 含私有标记），metrics 持久化
    /// 文档与快照编码都必须不含该标记——只记计数/时间戳/枚举键/制品名。
    func testMetricsDocumentContainsNoMessageBodies() async throws {
        let (root, dao, metrics) = makeEnvironment()
        let ledger = IOSAgentRunLedger(dao: dao)
        let marker = "PRIVATE_MARKER_7A2F"
        await seedFailedRun(
            dao: dao, ledger: ledger, runId: "run-metrics-privacy", toolName: "workspace_file_write",
            errorCode: marker
        )
        let evidence = await recentEvidence(dao: dao)
        XCTAssertTrue(
            evidence.contains { $0.redactedSummary.contains(marker) },
            "前提：证据摘要必须携带标记，测试才有意义"
        )
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "workspace_file_write" })
        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
        ])
        let workflow = makeWorkflow(
            root: root, dao: dao, ledger: ledger, model: model, metrics: metrics,
            registry: IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics),
            suite: Self.workspaceOracleSuiteResult()
        )
        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value
        XCTAssertNotNil(workflow.pendingApproval, "T2 必须出现人工批准卡")
        let approve = workflow.approvePending()
        await approve?.value
        XCTAssertEqual(workflow.notifications.first?.kind, .autoPromoted)

        // 快照编码不含标记。
        let encoder = JSONEncoder()
        let encoded = try XCTUnwrap(String(data: encoder.encode(metrics.snapshot()), encoding: .utf8))
        XCTAssertFalse(encoded.contains(marker), "快照编码不得含消息正文标记（§18.2）")
        // 持久化文档不含标记。
        let fileURL = root
            .appendingPathComponent("evolution", isDirectory: true)
            .appendingPathComponent("metrics.json")
        let fileData = try Data(contentsOf: fileURL)
        XCTAssertFalse(String(data: fileData, encoding: .utf8)?.contains(marker) == true)
        // 只含制品名/枚举键等标识，不含任何疑似长文本内容。
        XCTAssertTrue(metrics.snapshot().promotions.contains { $0.artifactId == "note_writer" })
    }

    // MARK: - rollback 率窗口计算（7/30 天，按时间戳现算）

    func testRollbackRateWindowsComputeFromTimestamps() async throws {
        let metrics = IOSEvolutionMetrics(baseDirectory: tempRoot())
        let now = Date()
        func ms(daysAgo: Double) -> Int64 {
            Int64(now.addingTimeInterval(-daysAgo * 24 * 3600).timeIntervalSince1970 * 1000)
        }
        metrics.recordPromotion(artifactId: "a", source: .auto, atEpochMs: ms(daysAgo: 1))
        metrics.recordPromotion(artifactId: "b", source: .manual, atEpochMs: ms(daysAgo: 10))
        metrics.recordPromotion(artifactId: "c", source: .auto, atEpochMs: ms(daysAgo: 40))
        metrics.recordRollback(artifactId: "a", source: .userInitiated, atEpochMs: ms(daysAgo: 2))
        metrics.recordRollback(artifactId: "c", source: .userInitiated, atEpochMs: ms(daysAgo: 45))

        let snapshot = metrics.snapshot()
        // 7 天：promotion a（1d），rollback a（2d）→ 1/1。
        XCTAssertEqual(try XCTUnwrap(snapshot.rollbackRate(days: 7, now: now)), 1.0, accuracy: 1e-9)
        // 30 天：promotion a+b（1d/10d），rollback a（2d）→ 1/2；45 天前的
        // rollback 不在窗口。
        XCTAssertEqual(try XCTUnwrap(snapshot.rollbackRate(days: 30, now: now)), 0.5, accuracy: 1e-9)
        // 90 天：3 次 promotion、2 次 rollback → 2/3。
        XCTAssertEqual(try XCTUnwrap(snapshot.rollbackRate(days: 90, now: now)), 2.0 / 3.0, accuracy: 1e-9)
        // 窗口无 promotion → nil（不是 0）。用 0 天窗口（起点 = now）保证
        // 全部记录都在窗口外。
        XCTAssertNil(snapshot.rollbackRate(days: 0, now: now))
        // 无任何数据 → nil。
        let empty = IOSEvolutionMetrics(baseDirectory: tempRoot()).snapshot()
        XCTAssertNil(empty.rollbackRate(days: 7, now: now))
        XCTAssertNil(empty.userRejectionRate)
    }

    // MARK: - recipe step 失败 / 权限拒绝分布（store 契约 + 真实路径测试：
    // 埋点已接进 ChatToolRuntime，见下方真实路径用例）

    func testStepFailureAndPermissionDenialDistributionAPIs() async throws {
        let metrics = IOSEvolutionMetrics(baseDirectory: tempRoot())
        metrics.recordRecipeStepFailure(toolId: "scrape_web")
        metrics.recordRecipeStepFailure(toolId: "scrape_web")
        metrics.recordRecipeStepFailure(toolId: "workspace_file_write")
        metrics.recordPermissionDenied(toolId: "workspace_file_write")

        let snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.recipeStepFailuresByTool["scrape_web"], 2)
        XCTAssertEqual(snapshot.recipeStepFailuresByTool["workspace_file_write"], 1)
        XCTAssertEqual(snapshot.permissionDenialsByTool["workspace_file_write"], 1)
        XCTAssertTrue(snapshot.permissionDenialsByTool["scrape_web"] == nil)
    }

    // MARK: - ChatToolRuntime 真实路径埋点（注入临时 metrics 实例，不碰 .shared）

    /// (a) recipe 路由 step 失败路径：已发布的 recipe 步骤执行时失败（discovery
    /// 路由缺 bridge → 结构化失败）→ `recipeStepFailuresByTool[step.tool]` +1。
    /// 走真实 ChatToolRuntime.execute，metrics 为注入的临时实例。
    func testRecipeStepFailurePathRecordsStepFailureByTool() async throws {
        let root = tempRoot()
        let metrics = IOSEvolutionMetrics(baseDirectory: root)
        let store = IOSRecipeFileStore(baseDirectory: root)

        // 真实发布一个 step 为 tools_list 的 recipe（catalog 中真实存在）。
        let content = try listingRecipeJSON(version: "1.0.0")
        let prep = try store.prepareRecipe(recipeJSON: content)
        _ = try store.applyRecipe(
            name: prep.candidate.name,
            recipeJSON: content,
            expectedBaseHash: prep.base?.hash,
            expectedCandidateHash: prep.candidate.hash
        )

        let registry = IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics)
        let snapshot = await registry.refresh()
        XCTAssertTrue(
            snapshot?.recipeTools.contains { $0.toolId == "recipe__catalog_probe" } == true,
            "recipe 必须进入 catalog snapshot 才能被路由执行"
        )

        let runtime = makeMetricsRuntime(root: root, metrics: metrics)
        let call = makeRecipeToolCall(name: "recipe__catalog_probe", input: "{}")
        _ = await executeRecipeCall(
            runtime: runtime, toolCall: call, snapshot: snapshot, bridge: nil,
            runId: "run-metrics-step-fail"
        )

        XCTAssertEqual(
            metrics.snapshot().recipeStepFailuresByTool["tools_list"], 1,
            "step 执行失败必须按 ToolId 落分布计数"
        )
    }

    /// (b) 权限拒绝漏斗：Workspace 审批拒绝 → `permissionDenialsByTool[toolName]`
    /// +1（走真实 finishWorkspaceApproval(allow: false)）。
    func testPermissionDenialFunnelRecordsPermissionDeniedByTool() async throws {
        let root = tempRoot()
        let metrics = IOSEvolutionMetrics(baseDirectory: root)
        let runtime = makeMetricsRuntime(root: root, metrics: metrics)

        let toolCall = makeRecipeToolCall(name: "workspace_file_write", input: "{}")
        let pending = pendingContext(for: toolCall, runId: "run-metrics-perm")
        _ = await runtime.finishWorkspaceApproval(pending: pending, allow: false)

        XCTAssertEqual(
            metrics.snapshot().permissionDenialsByTool["workspace_file_write"], 1,
            "审批拒绝必须按 ToolId 落分布计数"
        )
        XCTAssertTrue(metrics.snapshot().permissionDenialsByTool["scrape_web"] == nil)
    }

    // MARK: - neverAuto 分类（空权限包络 recipe）

    /// (c) 真实 policy engine 分类契约：空权限包络的 recipe → 永不自动。
    /// `recordDecisionMetrics` 的 neverAuto 分支（candidateNeverAutoRefused）
    /// 依赖这一分类结果。
    func testNeverAutoClassificationRefusedForEmptyEnvelopeRecipe() {
        let decision = IOSPromotionPolicyEngine.decide(
            input: IOSPromotionPolicyInput(
                artifactKind: .recipe,
                artifactName: "empty_envelope_recipe",
                permissionEnvelopeRaw: [],
                candidateHash: "cand-hash",
                report: nil,
                draftOnly: false
            ),
            state: IOSPromotionPolicyStateStore(baseDirectory: tempRoot()),
            autonomyLevel: .t0T1Auto,
            killSwitchEnabled: false
        )
        XCTAssertEqual(decision.tier, .neverAuto)
        XCTAssertFalse(decision.canAutoApprove)
        XCTAssertTrue(decision.gateFailures.contains { $0.hasPrefix("tier_never_auto") })
    }

    /// (c) workflow 端到端钉住空包络候选的真实路由：当前 builder 对 recipe
    /// 恒产出非空权限包络（`IOSEvolutionCandidateBuilder.draftRecipeCandidate`
    /// 的 `permissionEnvelope: [envelope.rawValue]`，校验要求 envelope 非 nil）；
    /// 空包络候选只存在于 skill/playbook 草稿（`draftOnly: true`），在
    /// routeCandidate 被 draftDowngrade 拦下，到不了 policy 决策——因此
    /// `candidateNeverAutoRefused` 在真实 workflow 中恒为 0（防御性埋点，
    /// 分类契约由上一测试在 policy 层覆盖）。本测试钉住该路由事实，防止
    /// builder 形态变化时静默漂移。
    func testEmptyEnvelopeCandidateRoutesToDraftDowngradeNotNeverAutoInWorkflow() async throws {
        let (root, dao, metrics) = makeEnvironment()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-metrics-neverauto", toolName: "scrape_web")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "scrape_web" })
        // skill 假设 → builder 产出空包络 + draftOnly 的 skill 草稿。
        let model = ScriptedModel(responses: [
            Self.skillHypothesisJSON(evidenceId: failureEvidence.id),
            Self.skillDraftJSON(name: "polish_skill"),
        ])
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "scrape_web (pure)",
            policyConfiguration: .standard,
            policyStateStore: IOSPromotionPolicyStateStore(baseDirectory: root),
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { false },
            suiteProvider: nil,
            registryRefresh: { await IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics).refresh() },
            metrics: metrics
        )
        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value

        XCTAssertEqual(workflow.notifications.first?.kind, .draftOnly, "空包络候选只能降级为人工草稿")
        let snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.count(.draftDowngrade), 1)
        XCTAssertEqual(snapshot.count(.candidateNeverAutoRefused), 0, "空包络候选到不了 policy 决策")
    }

    // MARK: - ChatToolRuntime harness（复用 IOSRecipeIntegrationTests 的构造模式）

    private func makeMetricsRuntime(root: URL, metrics: IOSEvolutionMetrics) -> ChatToolRuntime {
        let defaults = UserDefaults(suiteName: "metrics-runtime-\(UUID().uuidString)")!
        return ChatToolRuntime(
            settingsStore: SettingsStore(userDefaults: defaults),
            sharedSettings: IOSSharedSettingsStore(userDefaults: defaults),
            localToolExecutor: nil,
            searchTransport: MetricsNoopSearchTransport(),
            mcpManager: IOSMcpManager(
                sharedSettings: IOSSharedSettingsStore(userDefaults: defaults),
                configStore: .shared
            ),
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: root.appendingPathComponent("ws", isDirectory: true)
            ),
            ledger: nil,
            recipeStoreBaseDirectory: root,
            metrics: metrics
        )
    }

    private func makeRecipeToolCall(name: String, input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: "tc-\(name)-\(UUID().uuidString)",
            toolName: name,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func pendingContext(for toolCall: UIMessagePart.Tool, runId: String) -> ChatPendingToolApproval {
        ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: runId,
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [makeAssistantMessage(parts: [toolCall])]
        )
    }

    private func executeRecipeCall(
        runtime: ChatToolRuntime,
        toolCall: UIMessagePart.Tool,
        snapshot: IOSDynamicToolCatalogSnapshot?,
        bridge: IosToolExposureBridge?,
        runId: String
    ) async -> ChatToolRuntimeResult {
        let pending = pendingContext(for: toolCall, runId: runId)
        return await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: pending,
            toolExposureBridge: bridge,
            recipeCatalogSnapshot: snapshot
        )
    }

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "recipe-test",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "sk-test",
            baseUrl: "https://example.test",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    private func makeParams() -> TextGenerationParams {
        let model = Model(
            modelId: "test-model",
            displayName: "test-model",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        return TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func makeAssistantMessage(parts: [UIMessagePart]) -> UIMessage {
        let now = Kotlinx_datetimeLocalDateTime(
            year: 2026, month: 8, day: 12, hour: 0, minute: 0, second: 0, nanosecond: 0
        )
        return UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: parts,
            annotations: [],
            createdAt: now,
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private static func skillHypothesisJSON(evidenceId: String) -> String {
        json([
            "kind": "knowledgeOrProcedure",
            "claim": "步骤知识不完整",
            "confidence": 0.8,
            "alternatives": ["可能是工具参数问题"],
            "falsifier": "若手工按文档执行成功则推翻",
            "recommended_artifact": "skill",
            "tool_ids": ["scrape_web"],
            "mcp_connections": [],
            "evidence_ids": [evidenceId],
        ])
    }

    private static func skillDraftJSON(name: String) -> String {
        json([
            "artifact_name": name,
            "markdown": "# \(name)\n\n- 先调用 scrape_web 再整理正文。",
        ])
    }

    // MARK: - 持久化往返 + 坏文件降级

    func testPersistenceRoundTripAndCorruptFileDegradesToEmpty() async throws {
        let root = tempRoot()
        let metrics = IOSEvolutionMetrics(baseDirectory: root)
        metrics.record(.autoPublish)
        metrics.recordGateDenial(reasonKey: "cooldown")
        metrics.recordPromotion(artifactId: "fetch_digest", source: .auto, atEpochMs: 1_700_000_000_000)
        metrics.recordRollback(artifactId: "fetch_digest", source: .userInitiated, atEpochMs: 1_700_100_000_000)

        // 同一目录新实例重读 → 往返一致。
        let reloaded = IOSEvolutionMetrics(baseDirectory: root)
        XCTAssertEqual(reloaded.snapshot(), metrics.snapshot())
        XCTAssertEqual(reloaded.snapshot().count(.autoPublish), 1)
        XCTAssertEqual(reloaded.snapshot().gateDenialCount(reasonKey: "cooldown"), 1)
        XCTAssertEqual(reloaded.snapshot().promotions.count, 1)

        // 坏文件 → 降级空态（不崩溃、不阻塞后续记录）。
        let fileURL = root
            .appendingPathComponent("evolution", isDirectory: true)
            .appendingPathComponent("metrics.json")
        try Data("not-json{{{".utf8).write(to: fileURL)
        let degraded = IOSEvolutionMetrics(baseDirectory: root)
        XCTAssertTrue(degraded.snapshot().isEmpty, "坏文件降级空态")
        degraded.record(.autoPublish)
        XCTAssertEqual(degraded.snapshot().count(.autoPublish), 1, "降级后仍可继续记录（写恢复）")
    }

    // MARK: - 时间戳序列条数上限（防 metrics.json 无界增长）

    func testPromotionAndRollbackRecordsAreHardCappedAt200() async throws {
        let metrics = IOSEvolutionMetrics(baseDirectory: tempRoot())
        let base = Int64(Date().timeIntervalSince1970 * 1000)
        for index in 0..<205 {
            metrics.recordPromotion(artifactId: "p\(index)", source: .auto, atEpochMs: base + Int64(index))
            metrics.recordRollback(artifactId: "p\(index)", source: .userInitiated, atEpochMs: base + Int64(index))
        }
        let snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.promotions.count, 200)
        XCTAssertEqual(snapshot.rollbacks.count, 200)
        XCTAssertEqual(snapshot.promotions.first?.artifactId, "p5", "最旧的 5 条被裁掉，保留最新 200 条")
        XCTAssertEqual(snapshot.rollbacks.first?.artifactId, "p5")
    }

    // MARK: - wideningGate（§15 Phase 4 数据闸门）

    func testWideningGateRefusesWithoutAnyData() async throws {
        let metrics = IOSEvolutionMetrics(baseDirectory: tempRoot())
        let evaluation = IOSPromotionPolicyEngine.wideningGate(
            target: .dailyAutoPromotionLimit,
            metrics: metrics.snapshot()
        )
        XCTAssertFalse(evaluation.allow)
        XCTAssertEqual(evaluation.target, .dailyAutoPromotionLimit)
        XCTAssertTrue(evaluation.missingData.contains { $0.contains("没有任何真实 promotion") })
        XCTAssertTrue(evaluation.basis.contains("累计 promotion 0"))
    }

    func testWideningGateRefusesWhenSampleBelowMinimum() async throws {
        let metrics = IOSEvolutionMetrics(baseDirectory: tempRoot())
        let now = Date()
        for index in 0..<3 {
            let at = Int64(now.addingTimeInterval(-Double(index + 1) * 3600).timeIntervalSince1970 * 1000)
            metrics.recordPromotion(artifactId: "r\(index)", source: .auto, atEpochMs: at)
        }
        let evaluation = IOSPromotionPolicyEngine.wideningGate(
            target: .promotionCooldown,
            metrics: metrics.snapshot()
        )
        XCTAssertFalse(evaluation.allow, "样本不足不得扩面（Phase 4 进入条件）")
        XCTAssertTrue(
            evaluation.missingData.contains { $0.contains("\(IOSPromotionPolicyEngine.wideningMinimumPromotions) 次下限") },
            "必须说明缺多少样本: \(evaluation.missingData)"
        )
    }

    func testWideningGateRefusesWhenRollbackRateTooHigh() async throws {
        let metrics = IOSEvolutionMetrics(baseDirectory: tempRoot())
        let now = Date()
        for index in 0..<5 {
            let at = Int64(now.addingTimeInterval(-Double(index + 1) * 3600).timeIntervalSince1970 * 1000)
            metrics.record(.autoPublish)
            metrics.recordPromotion(artifactId: "r\(index)", source: .auto, atEpochMs: at)
        }
        metrics.recordRollback(artifactId: "r0", source: .userInitiated, atEpochMs: Int64(now.timeIntervalSince1970 * 1000) - 1800_000)
        metrics.recordRollback(artifactId: "r1", source: .userInitiated, atEpochMs: Int64(now.timeIntervalSince1970 * 1000) - 3600_000)
        let evaluation = IOSPromotionPolicyEngine.wideningGate(
            target: .dailyAutoPromotionLimit,
            metrics: metrics.snapshot()
        )
        XCTAssertFalse(evaluation.allow)
        XCTAssertTrue(
            evaluation.missingData.contains { $0.contains("rollback 率 40%") },
            "40% > 20% 上限必须拒绝: \(evaluation.missingData)"
        )
    }

    func testWideningGateRefusesWhenUserRejectionRateTooHigh() async throws {
        let metrics = IOSEvolutionMetrics(baseDirectory: tempRoot())
        let now = Date()
        for index in 0..<5 {
            let at = Int64(now.addingTimeInterval(-Double(index + 1) * 3600).timeIntervalSince1970 * 1000)
            metrics.record(.autoPublish)
            metrics.recordPromotion(artifactId: "r\(index)", source: .auto, atEpochMs: at)
        }
        for _ in 0..<6 {
            metrics.record(.userReject)
        }
        let evaluation = IOSPromotionPolicyEngine.wideningGate(
            target: .rollbackCooldown,
            metrics: metrics.snapshot()
        )
        XCTAssertFalse(evaluation.allow)
        XCTAssertTrue(
            evaluation.missingData.contains { $0.contains("用户拒绝率 55%") },
            "拒绝率 6/11 ≈ 55% > 50% 上限必须拒绝: \(evaluation.missingData)"
        )
    }

    func testWideningGateAllowsWithEnoughData() async throws {
        let metrics = IOSEvolutionMetrics(baseDirectory: tempRoot())
        let now = Date()
        for index in 0..<8 {
            let at = Int64(now.addingTimeInterval(-Double(index + 1) * 3600).timeIntervalSince1970 * 1000)
            if index < 5 {
                metrics.record(.autoPublish)
                metrics.recordPromotion(artifactId: "r\(index)", source: .auto, atEpochMs: at)
            } else {
                metrics.record(.userApprove)
                metrics.recordPromotion(artifactId: "r\(index)", source: .manual, atEpochMs: at)
            }
        }
        metrics.record(.userReject)
        metrics.recordRollback(artifactId: "r0", source: .userInitiated, atEpochMs: Int64(now.timeIntervalSince1970 * 1000) - 1800_000)

        let snapshot = metrics.snapshot()
        // 8 promotions、1 rollback（12.5%）、1/9 拒绝（11%）→ 全部低于上限。
        XCTAssertEqual(snapshot.promotionCount, 8)
        let evaluation = IOSPromotionPolicyEngine.wideningGate(
            target: .dailyAutoPromotionLimit,
            metrics: snapshot
        )
        XCTAssertTrue(evaluation.allow, "真实数据达标才允许扩面: \(evaluation.missingData)")
        XCTAssertTrue(evaluation.missingData.isEmpty)
        XCTAssertTrue(evaluation.basis.contains("累计 promotion 8"))
        XCTAssertTrue(evaluation.basis.contains("自动 5 / 人工 3"))
        XCTAssertTrue(evaluation.basis.contains("近 30 天 rollback 率"))
        XCTAssertTrue(evaluation.basis.contains("用户拒绝率"))
    }

    // MARK: - stale CAS：recipe_import apply 的 fail-closed 路径（真实路径）

    func testRecipeImportStaleCandidateFailClosedRecordsStaleCAS() async throws {
        let root = tempRoot()
        let workspace = IOSWorkspaceStore(
            baseDirectory: root.appendingPathComponent("workspace", isDirectory: true)
        )
        let metrics = IOSEvolutionMetrics(baseDirectory: root)
        let service = IOSRecipeToolService(
            workspaceStore: workspace,
            recipeStore: IOSRecipeFileStore(baseDirectory: root),
            refreshRegistry: { await IOSDynamicToolRegistry(baseDirectory: root, metrics: metrics).refresh() },
            metrics: metrics
        )

        // 写候选 v1 到 Workspace → preview。
        try await seedWorkspaceRecipe(
            workspace: workspace,
            json: try listingRecipeJSON(version: "1.0.0")
        )
        let prepared = try service.prepareRecipeImport(
            arguments: #"{"workspace_path":"/workspace/recipes/catalog_probe/recipe.json"}"#
        )
        XCTAssertNil(prepared.preview.baseHash)

        // 批准前候选被改写（v1.0.1）→ apply fail-closed，零写入，记 stale CAS。
        try await seedWorkspaceRecipe(
            workspace: workspace,
            json: try listingRecipeJSON(version: "1.0.1")
        )
        let output = try await service.applyPreparedRecipeImport(prepared)
        XCTAssertTrue(output.contains(#""code":"stale_candidate""#), output)
        XCTAssertEqual(metrics.snapshot().count(.staleCASSkipped), 1, "recipe_import stale CAS 必须落计数")
        XCTAssertTrue(metrics.snapshot().count(.autoPublish) == 0)

        // 零写入：active 未被替换。
        XCTAssertThrowsError(try IOSRecipeFileStore(baseDirectory: root).readLiveRecipe(name: "catalog_probe"))
    }

    // MARK: - Fixtures

    private func makeEnvironment() -> (root: URL, dao: AgentRuntimeDao, metrics: IOSEvolutionMetrics) {
        let root = tempRoot()
        let path = root.appendingPathComponent("agent_runtime.db").path
        let db = IosDatabaseFactory.shared.createDatabase(atFilePath: path)
        databases.append(db)
        return (root, db.agentRuntimeDao(), IOSEvolutionMetrics(baseDirectory: root))
    }

    private func makeWorkflow(
        root: URL,
        dao: AgentRuntimeDao,
        ledger: IOSAgentRunLedger,
        model: ScriptedModel,
        metrics: IOSEvolutionMetrics,
        registry: IOSDynamicToolRegistry,
        suite: IOSEvolutionSuiteBuildResult? = nil
    ) -> IOSEvolutionWorkflow {
        IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "scrape_web (pure), workspace_file_write (sideEffect)",
            policyConfiguration: .standard,
            policyStateStore: IOSPromotionPolicyStateStore(baseDirectory: root),
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { false },
            suiteProvider: { _, _ in suite ?? Self.allGreenSuiteResult() },
            registryRefresh: { await registry.refresh() },
            metrics: metrics
        )
    }

    private func seedFailedRun(
        dao: AgentRuntimeDao,
        ledger: IOSAgentRunLedger,
        runId: String,
        toolName: String,
        errorCode: String = "step_failed"
    ) async {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let run = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: nil,
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: "failed",
            inputDigest: "digest",
            inputSnapshotRef: nil,
            inputSchemaVersion: 1,
            startedAt: now - 60_000,
            finishedAt: KotlinLong(value: now),
            interruptedReason: nil
        )
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            dao.insertRunIfAbsent(run: run) { _, _ in
                continuation.resume()
            }
        }
        await ledger.recordToolCallStarted(
            runId: runId,
            toolCallId: "tc-1",
            toolName: toolName,
            argsDigest: "digest",
            effectClass: .sideEffect
        )
        await ledger.recordToolCallFinished(
            runId: runId,
            toolCallId: "tc-1",
            outcome: "failed",
            artifactId: nil,
            artifactVersion: nil,
            outcomeKind: "error",
            errorCode: errorCode,
            sourceRef: nil
        )
    }

    private func recentEvidence(dao: AgentRuntimeDao) async -> [IOSEvolutionEvidence] {
        await IOSEvolutionEvidenceProjector.projectRecent(
            sinceEpochMs: Int64(Date().timeIntervalSince1970 * 1000) - 7 * 24 * 3600 * 1000,
            dao: dao
        )
    }

    private func seedWorkspaceRecipe(
        workspace: IOSWorkspaceStore,
        json: Data,
        workspacePath: String = "/workspace/recipes/catalog_probe/recipe.json"
    ) async throws {
        let content = String(data: json, encoding: .utf8) ?? "{}"
        let input = String(data: try JSONSerialization.data(
            withJSONObject: [
                "path": workspacePath,
                "content": content,
                "overwrite": true,
            ] as [String: Any]
        ), encoding: .utf8) ?? "{}"
        let result = await workspace.executeTool(toolName: "workspace_file_write", input: input)
        XCTAssertTrue(result.contains(#""ok":true"#), result)
    }

    private func listingRecipeJSON(version: String, name: String = "catalog_probe") throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "schema": "amber.recipe.v1",
            "name": name,
            "version": version,
            "description": "列出当前工具目录并返回总数。",
            "inputs": [:],
            "steps": [
                ["id": "list", "tool": "tools_list", "arguments": [:]],
            ],
            "outputs": ["tool_count": "${step.list.output.total}"],
        ], options: [.sortedKeys])
    }

    private var testCatalog: IOSRecipeCatalogLookup {
        { tool in
            switch tool {
            case "scrape_web":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .pure)
            case "workspace_file_write":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .sideEffect)
            default:
                return nil
            }
        }
    }

    // MARK: Suite / case builders（同 IOSEvolutionWorkflowTests 风格）

    private static func allGreenSuite() -> IOSEvaluationSuite {
        IOSEvaluationSuite(
            suiteId: "suite-metrics",
            failureReplayCases: [caseItem(id: "case:replay-1", url: "https://example.com/rss.xml")],
            protectedSuccessCases: [caseItem(id: "case:protected-1", url: "https://example.com/ok.xml")],
            sealedHoldoutCases: [caseItem(id: "case:sealed-1", url: "https://example.com/sealed.xml")]
        )
    }

    private static func allGreenSuiteResult() -> IOSEvolutionSuiteBuildResult {
        .built(suite: allGreenSuite(), dataScopeSummary: "suite 数据范围")
    }

    /// sideEffect Recipe（workspace_file_write）专用的全绿套件：`caseItem`
    /// 的期望 step 工具与 scripted 输出必须匹配该 Recipe，否则受保护样例
    /// 会“回归”（真实 evaluator 报告 protectedRegressions = 1）。
    private static func sideEffectGreenSuite() -> IOSEvaluationSuite {
        IOSEvaluationSuite(
            suiteId: "suite-metrics-t2-sideeffect",
            failureReplayCases: [caseItem(id: "case:replay-t2-1", url: "https://example.com/rss.xml", tool: "workspace_file_write")],
            protectedSuccessCases: [caseItem(id: "case:protected-t2-1", url: "https://example.com/ok.xml", tool: "workspace_file_write")],
            sealedHoldoutCases: [caseItem(id: "case:sealed-t2-1", url: "https://example.com/sealed.xml", tool: "workspace_file_write")]
        )
    }

    /// Slice A oracle-backed 套件（workspace 差分）的 provider 形状返回。
    private static func workspaceOracleSuiteResult() -> IOSEvolutionSuiteBuildResult {
        .built(
            suite: try! IOSEvolutionWorkspaceOracleFixtures.suite(),
            dataScopeSummary: "suite 数据范围（workspace oracle）"
        )
    }

    private static func sideEffectGreenSuiteResult() -> IOSEvolutionSuiteBuildResult {
        .built(suite: sideEffectGreenSuite(), dataScopeSummary: "suite 数据范围")
    }

    private static func caseItem(id: String, url: String, tool: String = "scrape_web") -> IOSEvaluationCase {
        IOSEvaluationCase(
            id: id,
            kind: id.hasPrefix("case:replay") ? .failureReplay : (id.hasPrefix("case:protected") ? .protectedSuccess : .sealedHoldout),
            recipeInputs: ["url": .string(url)],
            scriptedPrimitives: [
                "scrape_web": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"fetched body"}"#),
                // 文本载荷：使同一套件对 workspace_file_write Recipe 也真正
                // 全绿（assertions 的 expectedOutputs 断言 text 字段）。
                "workspace_file_write": IOSEvaluationScriptedPrimitive(outputJSON: #"{"text":"fetched body"}"#),
            ],
            assertions: IOSEvaluationAssertions(
                expectedOutputs: ["text": .string("fetched body")],
                expectedError: nil,
                expectedStepCalls: [
                    IOSEvaluationExpectedStepCall(
                        stepId: "fetch", tool: tool,
                        arguments: ["url": .string(url)]
                    ),
                ],
                expectedLedger: [
                    IOSEvaluationLedgerExpectation(
                        stepId: "fetch", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                ]
            ),
            originalFailure: id.hasPrefix("case:replay")
                ? IOSEvaluationOriginalFailure(
                    failedStepId: nil, errorKind: .outputResolution, completedStepIds: ["fetch"]
                )
                : nil
        )
    }

    private static func hypothesisJSON(evidenceId: String) -> String {
        json([
            "kind": "composition",
            "claim": "重复编排不稳定",
            "confidence": 0.8,
            "alternatives": ["可能是网络波动"],
            "falsifier": "若手工逐步执行同样失败则推翻",
            "recommended_artifact": "recipe",
            "tool_ids": ["scrape_web"],
            "mcp_connections": [],
            "evidence_ids": [evidenceId],
        ])
    }

    private static func recipeJSON(tool: String, name: String, url: String = "${input.url}") -> String {
        json([
            "schema": "amber.recipe.v1",
            "name": name,
            "version": "1.0.0",
            "description": "抓取并返回正文。",
            "inputs": ["url": "string"],
            "steps": [
                ["id": "fetch", "tool": tool, "arguments": ["url": url]],
            ],
            "outputs": ["text": "${step.fetch.output.text}"],
        ])
    }

    private static func json(_ dict: [String: Any]) -> String {
        let data = try! JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
        return String(data: data, encoding: .utf8)!
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-evolution-metrics-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }
}

/// 最小 no-op 搜索 transport（照 IOSRecipeIntegrationTests 的
/// RecipeNoopSearchTransport 模式；该类型是 private，本文件自备一份）。
private struct MetricsNoopSearchTransport: IOSSearchHTTPTransport {
    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.com")!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: [:]
        )!
        return (response, Data())
    }
}

import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Phase 2 Wave C: 演化工作流端到端测试（§15 Phase 2 acceptance 1/5 + §14.2 +
/// 不变量 16/17）。
///
/// 用真实组件：真实 `IOSRecipeFileStore`（临时目录）、真实 Room 账本
/// （`IOSAgentRunLedger` on isolated DB）、真实 `IOSArtifactEvaluator`、
/// 真实 `IOSDynamicToolRegistry`；只有模型闭包是 scripted
/// （diagnoser/builder 的注入点）。断言解码/重读真实数据，不用源码锚点。
@MainActor
final class IOSEvolutionWorkflowTests: XCTestCase {
    private var tempDirs: [URL] = []
    private var databases: [AgentRuntimeDatabase] = []

    override func tearDown() async throws {
        databases.removeAll()
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    // MARK: - 端到端：失败证据 → 诊断 → 候选 → oracle 评测 → 人工批准发布 → 可回退

    /// Slice A 契约变更：T0/T1 自动晋级只对「确定性任务 oracle」套件可达，
    /// 而 v1 oracle 只覆盖 workspace 读写原语（生产目录 sideEffect → T2）。
    /// 因此晋升机制的真实路径 = oracle-backed 套件 → promote → T2 人工卡 →
    /// 批准发布（approvedBy=user）→ 一键回退。scripted-only 套件的全链覆盖
    /// 见 testT2SideEffectRecipeRequiresManualApprovalAndDenyWritesNothing。
    func testFailureEvidenceToApprovedPublishWithRealRollback() async throws {
        let (root, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-fail-1", toolName: "workspace_file_write")
        // 先投影一次取得真实 evidence id（模型闭包必须引用真实 id，I-1）。
        let evidence = await IOSEvolutionEvidenceProjector.projectRecent(
            sinceEpochMs: Int64(Date().timeIntervalSince1970 * 1000) - 7 * 24 * 3600 * 1000,
            dao: dao
        )
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.toolId == "workspace_file_write" && $0.observedOutcome == .error }
        )

        // Scripted model: 诊断 JSON（引用真实 evidence id）→ 修复 binding 的
        // note_writer 候选（workspace oracle 套件 ⇒ 真实差分评测 ⇒ promote）。
        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
        let policyState = IOSPromotionPolicyStateStore(baseDirectory: root)
        let suite = try IOSEvolutionWorkspaceOracleFixtures.suite()
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "workspace_file_write (sideEffect)",
            policyConfiguration: .standard,
            policyStateStore: policyState,
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { false },
            suiteProvider: { _, _ in .built(suite: suite, dataScopeSummary: "suite-e2e 数据范围") },
            registryRefresh: { await registry.refresh() }
        )

        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value

        // oracle-backed promote + sideEffect envelope（T2）→ 人工批准卡。
        let card = try XCTUnwrap(workflow.pendingApproval, "T2 必须出现人工批准卡")
        XCTAssertEqual(card.artifactName, "note_writer")
        XCTAssertFalse(card.evaluationResultsText.isEmpty, "批准卡必须带真实评测结果")
        XCTAssertTrue(workflow.notifications.isEmpty, "等待批准时不得发布任何通知/制品")

        // 批准 → 真实发布（approvedBy=user）；通知卡含一键回退（不变量 17）。
        let approve = workflow.approvePending()
        await approve?.value
        let notification = try XCTUnwrap(workflow.notifications.first)
        XCTAssertEqual(notification.kind, .autoPromoted)
        XCTAssertEqual(notification.artifactName, "note_writer")
        XCTAssertTrue(notification.canRollback, "发布通知必须带一键回退")
        XCTAssertFalse(notification.hasBeenRolledBack)
        XCTAssertNil(workflow.pendingApproval)

        // 真实 store 已发布；receipt approvedBy = user + 版本（不变量 7/16）。
        let store = IOSRecipeFileStore(baseDirectory: root)
        let live = try store.readLiveRecipe(name: "note_writer")
        let receipt = IOSPromotionReceiptStore(baseDirectory: root)
            .snapshot(artifactId: "note_writer")?.active
        XCTAssertNotNil(receipt)
        XCTAssertEqual(receipt?.approvedBy, "user")
        XCTAssertEqual(receipt?.toHash, live.hash)
        XCTAssertEqual(receipt?.evaluationReportHash, notification.reportHash)

        // registry 下一 round 可见（§13.2）。
        let snapshot = await registry.refresh()
        XCTAssertTrue(
            snapshot?.recipeTools.contains { $0.recipeName == "note_writer" } == true,
            "下一模型轮必须能发现已发布的 Recipe"
        )
        XCTAssertGreaterThanOrEqual(snapshot?.revision ?? 0, 2)

        // 一键回退真实可回退：新 Recipe 被移除，通知标记已回退，熔断计数 +1。
        let rollbackTask = workflow.rollback(notificationId: notification.id)
        await rollbackTask?.value
        XCTAssertThrowsError(try store.readLiveRecipe(name: "note_writer"), "回退后新 Recipe 必须被移除")
        XCTAssertTrue(workflow.notifications.first?.hasBeenRolledBack == true)
        XCTAssertEqual(policyState.snapshot().artifacts["note_writer"]?.consecutiveRollbacks, 1)
    }

    // MARK: - T2 人工批准卡（§14.2）→ 批准发布 / 拒绝零写入（acceptance 5）

    func testT2SideEffectRecipeRequiresManualApprovalAndDenyWritesNothing() async throws {
        let (root, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-fail-2", toolName: "workspace_file_write")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "workspace_file_write" })

        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "workspace_file_write", name: "save_digest"),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
        let workflow = IOSEvolutionWorkflow(
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
            suiteProvider: { _, _ in .built(suite: Self.sideEffectGreenSuite(), dataScopeSummary: "suite 数据范围") },
            registryRefresh: { await registry.refresh() }
        )

        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value

        // sideEffect envelope → T2 → 人工批准卡（非自动通道）。
        let card = try XCTUnwrap(workflow.pendingApproval, "T2 必须出现人工批准卡")
        XCTAssertEqual(card.artifactName, "save_digest")
        XCTAssertEqual(card.mutationKind, .new)
        XCTAssertFalse(card.candidateJSONPreview.isEmpty, "批准卡必须含完整候选（diff 入口）")
        XCTAssertFalse(card.evaluationResultsText.isEmpty, "批准卡必须含评测结果")
        XCTAssertNil(card.policyAssessment, "T2 卡不带 policy 自动评估结论（那不是 T0/T1）")
        XCTAssertTrue(card.title.contains("批准"))
        XCTAssertTrue(workflow.notifications.isEmpty, "等待批准时不得发布任何通知/制品")

        let store = IOSRecipeFileStore(baseDirectory: root)
        // §15 Phase 2 acceptance 5: 拒绝候选 → 不写 active、不偷偷重试发布。
        workflow.denyPending()
        XCTAssertNil(workflow.pendingApproval)
        XCTAssertThrowsError(try store.readLiveRecipe(name: "save_digest"), "拒绝后不得写入 active")
        let snapshot = await registry.refresh()
        XCTAssertFalse(
            snapshot?.recipeTools.contains { $0.recipeName == "save_digest" } == true,
            "拒绝后 registry 不得出现该制品"
        )
        XCTAssertNil(
            IOSPromotionReceiptStore(baseDirectory: root).snapshot(artifactId: "save_digest"),
            "拒绝后不得写入 receipt"
        )
    }

    func testT2ApprovalPublishesWithUserReceipt() async throws {
        let (root, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-fail-3", toolName: "workspace_file_write")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "workspace_file_write" })

        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "workspace_file_write", name: "save_digest"),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "workspace_file_write (sideEffect)",
            policyConfiguration: .standard,
            policyStateStore: IOSPromotionPolicyStateStore(baseDirectory: root),
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { false },
            suiteProvider: { _, _ in .built(suite: Self.sideEffectGreenSuite(), dataScopeSummary: "suite 数据范围") },
            registryRefresh: { await registry.refresh() }
        )
        _ = workflow.analyzeAndImprove(conversationHex: nil)
        // 等待流程推进到 pendingApproval（模型调用是异步脚本，轮询即可）。
        await waitUntil { workflow.pendingApproval != nil || workflow.notifications.first?.kind == .failed }
        let card = try XCTUnwrap(workflow.pendingApproval)

        let approveTask = workflow.approvePending()
        await approveTask?.value

        XCTAssertEqual(
            try IOSRecipeFileStore(baseDirectory: root).readLiveRecipe(name: "save_digest").hash,
            card.candidateHash,
            "人工批准后 active 与候选哈希一致"
        )
        let receipt = IOSPromotionReceiptStore(baseDirectory: root)
            .snapshot(artifactId: "save_digest")?.active
        XCTAssertEqual(receipt?.approvedBy, "user", "人工批准 receipt approvedBy = user")
        XCTAssertEqual(receipt?.evaluationReportHash, card.reportHash)
        XCTAssertTrue(workflow.notifications.first?.kind == .autoPromoted)
        XCTAssertTrue(workflow.notifications.first?.canRollback == true)
    }

    // MARK: - 「仅通知」档：T0/T1 也走人工卡但附带完整自动评估结论（§13.4）

    func testNotifyOnlyShowsT0CardWithPolicyAssessment() async throws {
        let (root, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-fail-4", toolName: "scrape_web")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(evidence.first { $0.toolId == "scrape_web" })

        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "scrape_web", name: "fetch_digest"),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
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
            autonomyLevelProvider: { .notifyOnly },
            killSwitchProvider: { false },
            suiteProvider: { _, _ in .built(suite: Self.allGreenSuite(), dataScopeSummary: "suite 数据范围") },
            registryRefresh: { await registry.refresh() }
        )
        _ = workflow.analyzeAndImprove(conversationHex: nil)
        await waitUntil { workflow.pendingApproval != nil || workflow.notifications.first?.kind == .failed }

        let card = try XCTUnwrap(workflow.pendingApproval)
        XCTAssertNotNil(card.policyAssessment, "「仅通知」档必须附带完整自动评估结论")
        XCTAssertTrue(card.policyAssessment?.contains("T0") == true)
        XCTAssertTrue(card.policyAssessment?.contains("硬门禁") == true)
        // 未批准前零写入。
        XCTAssertThrowsError(try IOSRecipeFileStore(baseDirectory: root).readLiveRecipe(name: "fetch_digest"))
    }

    // MARK: - 状态机收口回归：批准发布 / 拒绝后都必须能再次发起分析

    /// 发布成功路径必须与其余终止分支一致收口 isRunning/stage：
    /// 否则 `analyzeAndImprove` 的 `guard !isRunning` 从此永久拒绝
    /// （用户需重启 App 才能再次发起）。Slice A 后真实晋升路径走
    /// oracle-backed 套件 → T2 人工卡 → 批准发布。
    func testApprovedPublishResetsRunningStateSoAnalyzeCanRunAgain() async throws {
        let (root, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-fail-rerun", toolName: "workspace_file_write")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.toolId == "workspace_file_write" && $0.observedOutcome == .error }
        )

        // 两次运行的 scripted 响应（第二次用不同制品名，避开同制品冷却）。
        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(name: "note_writer_v2"),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
        let suite = try IOSEvolutionWorkspaceOracleFixtures.suite()
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "workspace_file_write (sideEffect)",
            policyConfiguration: .standard,
            policyStateStore: IOSPromotionPolicyStateStore(baseDirectory: root),
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { false },
            suiteProvider: { _, _ in .built(suite: suite, dataScopeSummary: "suite-e2e 数据范围") },
            registryRefresh: { await registry.refresh() }
        )

        // 第一次：人工卡 → 批准发布完成后状态机必须收口。
        let first = workflow.analyzeAndImprove(conversationHex: nil)
        await first?.value
        XCTAssertNotNil(workflow.pendingApproval, "T2 必须出现人工批准卡")
        XCTAssertTrue(workflow.isRunning, "等待批准时 isRunning 保持 true（防止重复发起）")
        let approveFirst = workflow.approvePending()
        await approveFirst?.value
        XCTAssertEqual(workflow.notifications.first?.kind, .autoPromoted)
        XCTAssertEqual(workflow.notifications.first?.artifactName, "note_writer")
        XCTAssertFalse(workflow.isRunning, "发布完成后 isRunning 必须复位（bug: 永久卡在 true）")

        // 第二次：guard !isRunning 必须放行，且能完整跑完再次批准发布。
        let second = workflow.analyzeAndImprove(conversationHex: nil)
        XCTAssertNotNil(second, "发布完成后必须能再次发起分析（guard !isRunning 不得永久拒绝）")
        await second?.value
        XCTAssertNotNil(workflow.pendingApproval, "第二次同样走到人工批准卡")
        let approveSecond = workflow.approvePending()
        await approveSecond?.value
        let secondNotification = try XCTUnwrap(
            workflow.notifications.first { $0.artifactName == "note_writer_v2" }
        )
        XCTAssertEqual(secondNotification.kind, .autoPromoted)
        XCTAssertTrue(secondNotification.canRollback)
        XCTAssertFalse(workflow.isRunning, "第二次发布后状态机同样收口")
    }

    /// 拒绝候选是终止路径：必须复位 isRunning，与通知文案
    /// 「可随时再次发起分析」一致。
    func testDenyPendingResetsRunningStateSoAnalyzeCanRunAgain() async throws {
        let (root, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-fail-deny", toolName: "workspace_file_write")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.toolId == "workspace_file_write" && $0.observedOutcome == .error }
        )

        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "workspace_file_write", name: "save_digest"),
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "workspace_file_write", name: "save_digest"),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "workspace_file_write (sideEffect)",
            policyConfiguration: .standard,
            policyStateStore: IOSPromotionPolicyStateStore(baseDirectory: root),
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { false },
            suiteProvider: { _, _ in Self.sideEffectGreenSuiteResult() },
            registryRefresh: { await registry.refresh() }
        )

        // 第一次：T2 人工卡出现（等待批准期间 isRunning 保持 true 是设计行为）。
        let first = workflow.analyzeAndImprove(conversationHex: nil)
        await first?.value
        XCTAssertNotNil(workflow.pendingApproval, "T2 必须出现人工批准卡")
        XCTAssertTrue(workflow.isRunning, "等待批准时 isRunning 保持 true（防止重复发起）")

        // 拒绝：终止路径必须复位 isRunning。
        workflow.denyPending()
        XCTAssertNil(workflow.pendingApproval)
        XCTAssertFalse(workflow.isRunning, "拒绝后 isRunning 必须复位（bug: 卡在 true 与文案矛盾）")

        // 第二次：能再次发起并再次走到人工卡。
        let second = workflow.analyzeAndImprove(conversationHex: nil)
        XCTAssertNotNil(second, "拒绝后必须能再次发起分析")
        await second?.value
        XCTAssertNotNil(workflow.pendingApproval, "第二次同样走到人工批准卡")
        workflow.denyPending()
        XCTAssertFalse(workflow.isRunning)
    }

    // MARK: - 熔断二次回退：原卡标记正确（bug: push 平移索引后标错卡片）

    /// 第二次回退触发熔断时 push(breaker) 会把所有卡片索引 +1；进入
    /// rollback Task 前捕获的 index 已失效。按 notificationId 重查再置位，
    /// 原发布卡必须被标记已回退、熔断卡不得被误标。
    /// 熔断真实路径：发布 → 回退（计数 1）→ 再发布（不重置计数）→
    /// 回退（计数 2 → 熔断）。Slice A 后两轮发布均走 oracle-backed
    /// 套件 → 人工批准（approvedBy=user）。
    func testSecondRollbackTripsBreakerAndMarksTheOriginalCardNotTheBreakerCard() async throws {
        let (root, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-fail-break", toolName: "workspace_file_write")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.toolId == "workspace_file_write" && $0.observedOutcome == .error }
        )

        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
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
            registryRefresh: { await registry.refresh() }
        )

        // 第一次：oracle-backed promote → T2 人工卡 → 批准发布 → 回退（计数 1，未熔断）。
        let first = workflow.analyzeAndImprove(conversationHex: nil)
        await first?.value
        XCTAssertNotNil(workflow.pendingApproval, "T2 必须出现人工批准卡")
        let approveA = workflow.approvePending()
        await approveA?.value
        let cardA = try XCTUnwrap(
            workflow.notifications.first { $0.kind == .autoPromoted && $0.artifactName == "note_writer" }
        )
        let rollbackA = workflow.rollback(notificationId: cardA.id)
        await rollbackA?.value
        XCTAssertEqual(policyState.snapshot().artifacts["note_writer"]?.consecutiveRollbacks, 1)
        XCTAssertFalse(policyState.isAutonomyDisabled(artifactId: "note_writer"))
        XCTAssertEqual(
            workflow.notifications.first { $0.id == cardA.id }?.hasBeenRolledBack,
            true,
            "第一次回退后原卡标记已回退"
        )

        // 第二次：再发布（不重置熔断计数）→ 回退触发熔断。
        autonomyLevel = .allManual
        let second = workflow.analyzeAndImprove(conversationHex: nil)
        await second?.value
        XCTAssertNotNil(workflow.pendingApproval, "全部人工档下也走人工卡")
        let approve = workflow.approvePending()
        await approve?.value
        let cardB = try XCTUnwrap(
            workflow.notifications.first { $0.kind == .autoPromoted && $0.id != cardA.id }
        )

        let rollbackB = workflow.rollback(notificationId: cardB.id)
        await rollbackB?.value
        XCTAssertEqual(policyState.snapshot().artifacts["note_writer"]?.consecutiveRollbacks, 2)
        XCTAssertTrue(policyState.isAutonomyDisabled(artifactId: "note_writer"), "连续两次回退必须熔断该制品自治")

        // push(breaker) 在 rollback 期间 insert at 0：原卡必须按 id 重查标记。
        let breaker = try XCTUnwrap(workflow.notifications.first)
        XCTAssertEqual(breaker.kind, .circuitBreakerTripped, "熔断通知必须出现在最前")
        XCTAssertFalse(breaker.hasBeenRolledBack, "熔断卡不得被误标为已回退")
        XCTAssertEqual(
            workflow.notifications.first { $0.id == cardB.id }?.hasBeenRolledBack,
            true,
            "第二次回退后原自动发布卡必须被标记已回退（按 id 重查，而不是按失效索引）"
        )
        XCTAssertEqual(
            workflow.notifications.first { $0.id == cardA.id }?.hasBeenRolledBack,
            true,
            "第一次回退的卡片标记保持"
        )
        XCTAssertTrue(workflow.notifications.allSatisfy { $0.kind != .circuitBreakerTripped || !$0.hasBeenRolledBack })
    }

    // MARK: - Slice B：显式会话证据冻结（跨会话不穿透）+ originRunId 贯通

    /// Slice B 红测试 1：显式指定会话后，该会话无可归因证据必须诚实 no-op
    /// （I-3），绝不回退到最近 7 天全局证据（跨会话泄漏）；全局 7 天窗口只
    /// 保留给 `conversationHex == nil` 的显式管理入口。同时验证 originRunId
    /// 从 evidence → suite → report → 通知卡贯通（B1）。
    func testExplicitConversationWithoutEvidenceIsNoOpAndGlobalEntryStillWorks() async throws {
        let (root, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        // convB 有失败证据；convA 没有任何 run。
        let convA = UUID().uuidString.lowercased()
        let convB = UUID().uuidString.lowercased()
        await seedFailedRun(
            dao: dao, ledger: ledger, runId: "run-fail-convB",
            toolName: "workspace_file_write", conversationId: convB
        )
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.toolId == "workspace_file_write" && $0.observedOutcome == .error }
        )

        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "workspace_file_write", name: "save_digest"),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "workspace_file_write (sideEffect)",
            policyConfiguration: .standard,
            policyStateStore: IOSPromotionPolicyStateStore(baseDirectory: root),
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { false },
            suiteProvider: { _, _ in .built(
                suite: Self.sideEffectGreenSuite(originRunId: failureEvidence.runId),
                dataScopeSummary: "suite 数据范围"
            ) },
            registryRefresh: { await registry.refresh() }
        )

        // 1. 显式会话无证据 → no-op、模型零调用（红点：当前穿透全局拿到
        //    convB 的证据生成候选）。
        let convATask = workflow.analyzeAndImprove(conversationHex: convA)
        await convATask?.value
        XCTAssertEqual(
            workflow.notifications.first?.kind, .noOp,
            "显式会话无可归因证据必须 no-op，不得跨会话生成候选（B1）"
        )
        XCTAssertNil(workflow.pendingApproval, "no-op 路径不得出现候选/批准卡")
        var prompts = await model.recordedPrompts()
        XCTAssertTrue(prompts.isEmpty, "显式会话无证据时不得调用模型（I-3）")

        // 2. 全局管理入口（nil）不受影响：正常出候选/人工批准卡。
        let globalTask = workflow.analyzeAndImprove(conversationHex: nil)
        await globalTask?.value
        let card = try XCTUnwrap(workflow.pendingApproval, "全局管理入口必须正常走到人工批准卡")
        XCTAssertEqual(card.artifactName, "save_digest")

        // 3. originRunId 贯通：发布通知保留失败 run 的 originRunId（B1）。
        let approve = workflow.approvePending()
        await approve?.value
        let notification = try XCTUnwrap(workflow.notifications.first)
        XCTAssertEqual(
            notification.originRunId, failureEvidence.runId,
            "通知必须保留同一 originRunId（B1）"
        )
        prompts = await model.recordedPrompts()
        XCTAssertEqual(prompts.count, 2, "只有全局入口消耗 2 次模型调用（no-op 路径零调用）")
    }

    // MARK: - 无证据 → 诚实 no-op（I-3）

    func testNoEvidenceProducesHonestNoOpWithoutCallingModel() async throws {
        let (root, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        let model = ScriptedModel(fallback: "不应被调用")
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "",
            policyConfiguration: .standard,
            policyStateStore: IOSPromotionPolicyStateStore(baseDirectory: root),
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { false },
            suiteProvider: nil,
            registryRefresh: { await IOSDynamicToolRegistry(baseDirectory: root).refresh() }
        )
        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value
        XCTAssertEqual(workflow.notifications.first?.kind, .noOp)
        let prompts = await model.recordedPrompts()
        XCTAssertTrue(prompts.isEmpty, "没有可归因证据时不得调用模型（I-3）")
    }

    // MARK: - Slice B：回退绑定通知晋升的版本（B3）

    /// Slice B 红测试 3：A(v1.0.1)→B(v1.0.2)→C(v1.0.1) 构成 ABA。
    /// A 卡的 candidate hash 会再次等于当前 live；只有绑定 A 当时的 previous
    /// manifest 才能拒绝旧卡，避免把 C 的槽位误回退到 B。C 卡仍可正常回退。
    func testRollbackOfSupersededPromotionDoesNotChangeLatestVersion() async throws {
        let (root, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-fail-supersede", toolName: "workspace_file_write")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.toolId == "workspace_file_write" && $0.observedOutcome == .error }
        )

        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(name: "note_writer", version: "1.0.2"),
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            IOSEvolutionWorkspaceOracleFixtures.noteWriterCandidateJSON(),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: model.call,
            catalogSummary: "workspace_file_write (sideEffect)",
            policyConfiguration: .standard,
            policyStateStore: IOSPromotionPolicyStateStore(baseDirectory: root),
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { false },
            suiteProvider: { _, _ in Self.workspaceOracleSuiteResult() },
            registryRefresh: { await registry.refresh() }
        )

        // 第一轮：发布 note_writer 1.0.1 → 通知卡 A。
        let first = workflow.analyzeAndImprove(conversationHex: nil)
        await first?.value
        XCTAssertNotNil(workflow.pendingApproval, "第一轮必须出现人工批准卡")
        let approveA = workflow.approvePending()
        await approveA?.value
        let cardA = try XCTUnwrap(
            workflow.notifications.first { $0.kind == .autoPromoted && $0.artifactName == "note_writer" }
        )
        let store = IOSRecipeFileStore(baseDirectory: root)
        let v101Hash = try store.readLiveRecipe(name: "note_writer").hash

        // 第二轮：发布 note_writer 1.0.2 → 通知卡 B（覆盖 previous 槽）。
        let second = workflow.analyzeAndImprove(conversationHex: nil)
        await second?.value
        XCTAssertNotNil(workflow.pendingApproval, "第二轮必须出现人工批准卡")
        let approveB = workflow.approvePending()
        await approveB?.value
        let cardB = try XCTUnwrap(
            workflow.notifications.first { $0.kind == .autoPromoted && $0.id != cardA.id }
        )
        let v102Hash = try store.readLiveRecipe(name: "note_writer").hash
        XCTAssertNotEqual(v101Hash, v102Hash, "两轮发布必须是不同版本")
        XCTAssertEqual(cardA.candidateHash, v101Hash, "A 卡必须绑定 v1.0.1 的 hash")
        XCTAssertEqual(cardB.candidateHash, v102Hash, "B 卡必须绑定 v1.0.2 的 hash")

        // 第三轮：再次发布与 A 字节完全相同的 v1.0.1，构造 ABA。
        let third = workflow.analyzeAndImprove(conversationHex: nil)
        await third?.value
        XCTAssertNotNil(workflow.pendingApproval, "第三轮必须出现人工批准卡")
        let approveC = workflow.approvePending()
        await approveC?.value
        let cardC = try XCTUnwrap(
            workflow.notifications.first {
                $0.kind == .autoPromoted && $0.id != cardA.id && $0.id != cardB.id
            }
        )
        let currentAHash = try store.readLiveRecipe(name: "note_writer").hash
        XCTAssertEqual(currentAHash, v101Hash, "第三轮必须回到与 A 相同的候选 hash")
        XCTAssertEqual(cardA.candidateHash, currentAHash, "必须真正覆盖 hash ABA 场景")

        // 点旧 A 的回退：即使 hash 再次相同，manifest 已变，仍必须零状态变更。
        let rollbackA = workflow.rollback(notificationId: cardA.id)
        await rollbackA?.value
        XCTAssertEqual(
            try store.readLiveRecipe(name: "note_writer").hash, currentAHash,
            "ABA 旧通知的回退不得改变当前生效版本（B3）"
        )
        let superseded = try XCTUnwrap(
            workflow.notifications.first { $0.kind == .failed && $0.title.contains("版本已变化") },
            "被取代通知的回退必须出现「版本已变化」失败通知"
        )
        XCTAssertTrue(superseded.summary.contains("未执行回退"), superseded.summary)
        XCTAssertEqual(
            workflow.notifications.first { $0.id == cardA.id }?.hasBeenRolledBack,
            false,
            "被取代通知的回退不得标记已回退"
        )

        // C 的回退：正常恢复 v1.0.2（当前 previous 槽）。
        let rollbackC = workflow.rollback(notificationId: cardC.id)
        await rollbackC?.value
        XCTAssertEqual(
            try store.readLiveRecipe(name: "note_writer").hash, v102Hash,
            "当前生效通知的回退必须正常恢复上一版本"
        )
        XCTAssertEqual(
            workflow.notifications.first { $0.id == cardC.id }?.hasBeenRolledBack,
            true,
            "当前生效通知的回退必须标记已回退"
        )
    }

    // MARK: - 详情页回退路径（2026-08 复核：与通知卡回退共用熔断/指标通道）

    /// 详情页「回退上一次导入」的记账等价路径（`workflow.recordRollback`，
    /// `RecipeDetailView.rollbackLastImport()` 在 store 侧回退成功后调用）：
    /// 真实 store 发布 v1 → v2，按详情页顺序（availability → rollbackRecipe
    /// → recordRollback）回退；重新发布后再回退第二次 → 熔断。
    /// 断言：§19 metrics rollback 计数 +2（两次均为用户主动）、第二次 tripped
    /// → workflow push 熔断通知（详情页没有自己的通知 UI，复用通知卡通道）。
    func testDetailRollbackPathRecordsMetricsAndTripsBreakerNotification() async throws {
        let root = tempRoot()
        let store = IOSRecipeFileStore(baseDirectory: root)
        let policyState = IOSPromotionPolicyStateStore(baseDirectory: root)
        let metrics = IOSEvolutionMetrics(baseDirectory: root)
        let (_, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        let config = IOSPromotionPolicyConfiguration(
            dailyAutoPromotionLimit: 10,
            cooldownAfterPromotionSeconds: 0,
            cooldownAfterRollbackSeconds: 0,
            maxConsecutiveRollbacksBeforeCircuitBreak: 2
        )
        let workflow = IOSEvolutionWorkflow(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            mcpConnectionOracle: { [] },
            model: ScriptedModel(fallback: "不应被调用").call,
            catalogSummary: "",
            policyConfiguration: config,
            policyStateStore: policyState,
            autonomyLevelProvider: { .t0T1Auto },
            killSwitchProvider: { false },
            suiteProvider: nil,
            registryRefresh: { await IOSDynamicToolRegistry(baseDirectory: root).refresh() },
            metrics: metrics
        )

        // 前置：v1 发布 → v2 覆盖，产生可回退槽位（详情页展示的前提）。
        try apply(store: store, json: Self.recipeJSON(tool: "scrape_web", name: "fetch_digest", version: "1.0.0"))
        try apply(store: store, json: Self.recipeJSON(tool: "scrape_web", name: "fetch_digest", version: "1.0.1"))

        // 第一次回退（详情页顺序）：不触发熔断、无熔断通知、metrics +1。
        let firstAvailability = try store.rollbackAvailability(name: "fetch_digest")
        guard case .available(let firstManifest) = firstAvailability else {
            return XCTFail("第一次回退应可用，got \(firstAvailability)")
        }
        _ = try store.rollbackRecipe(name: "fetch_digest", expectedManifest: firstManifest)
        let firstTripped = workflow.recordRollback(artifactId: "fetch_digest")
        XCTAssertFalse(firstTripped, "第一次回退不触发熔断")
        XCTAssertEqual(policyState.snapshot().artifacts["fetch_digest"]?.consecutiveRollbacks, 1)
        XCTAssertFalse(policyState.isAutonomyDisabled(artifactId: "fetch_digest"))
        XCTAssertFalse(
            workflow.notifications.contains { $0.kind == .circuitBreakerTripped },
            "第一次回退不得产生熔断通知"
        )

        // 重新发布 v2（真实用户流程：回退后再发布，才能再次回退）。
        try apply(store: store, json: Self.recipeJSON(tool: "scrape_web", name: "fetch_digest", version: "1.0.1"))

        // 第二次回退：tripped → 熔断通知对象产生。
        let secondAvailability = try store.rollbackAvailability(name: "fetch_digest")
        guard case .available(let secondManifest) = secondAvailability else {
            return XCTFail("第二次回退应可用，got \(secondAvailability)")
        }
        _ = try store.rollbackRecipe(name: "fetch_digest", expectedManifest: secondManifest)
        let secondTripped = workflow.recordRollback(artifactId: "fetch_digest")
        XCTAssertTrue(secondTripped, "连续两次回退必须触发熔断")
        XCTAssertEqual(policyState.snapshot().artifacts["fetch_digest"]?.consecutiveRollbacks, 2)
        XCTAssertTrue(policyState.isAutonomyDisabled(artifactId: "fetch_digest"))

        // §19 metrics：两次回退都记入（熔断状态在记账前采样 → 均为用户主动）。
        let snapshot = metrics.snapshot()
        XCTAssertEqual(snapshot.rollbackCount, 2, "详情页回退必须记入 §19 metrics")
        XCTAssertEqual(snapshot.count(.rollbackUserInitiated), 2)
        XCTAssertEqual(snapshot.count(.circuitBreakerTripped), 1)

        let breaker = try XCTUnwrap(
            workflow.notifications.first { $0.kind == .circuitBreakerTripped }
        )
        XCTAssertTrue(breaker.title.contains("fetch_digest"))
        XCTAssertTrue(breaker.summary.contains("熔断"))
    }

    // MARK: - protected regression 硬拒绝（2026-08 复核；§12.2 / 不变量 13）

    /// protectedRegressions > 0 的报告：任何档位都不进人工批准分支——无
    /// pendingApproval、有「先更新评测套件」通知、零写入。
    /// 用「仅通知」档 + T0 候选复现原 bug（protected 门禁失败时仍展示人工
    /// 批准卡）；真实组件：真实 evaluator 跑出带 protected 回归的报告。
    func testProtectedRegressionHardRejectsWithNoApprovalCardAndZeroWrites() async throws {
        let (root, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        await seedFailedRun(dao: dao, ledger: ledger, runId: "run-fail-protected", toolName: "scrape_web")
        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.toolId == "scrape_web" && $0.observedOutcome == .error }
        )

        let model = ScriptedModel(responses: [
            Self.hypothesisJSON(evidenceId: failureEvidence.id),
            Self.recipeJSON(tool: "scrape_web", name: "fetch_digest"),
        ])
        let registry = IOSDynamicToolRegistry(baseDirectory: root)
        let metrics = IOSEvolutionMetrics(baseDirectory: root)
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
            autonomyLevelProvider: { .notifyOnly },
            killSwitchProvider: { false },
            suiteProvider: { _, _ in .built(suite: Self.protectedRegressionSuite(), dataScopeSummary: "suite 数据范围") },
            registryRefresh: { await registry.refresh() },
            metrics: metrics
        )

        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value

        // 原 bug：「仅通知」档下 T0 候选 + protected 门禁失败仍展示人工批准卡。
        XCTAssertNil(workflow.pendingApproval, "protected 回归必须硬拒绝，不得出现人工批准卡")
        let notification = try XCTUnwrap(workflow.notifications.first)
        XCTAssertEqual(notification.kind, .noOp)
        XCTAssertTrue(notification.summary.contains("受保护"), notification.summary)
        XCTAssertTrue(notification.summary.contains("评测套件"), notification.summary)
        XCTAssertFalse(workflow.isRunning, "拒绝是终止路径，状态机必须收口")

        // 零写入：active 包 / receipt / registry 都不含该候选。
        XCTAssertThrowsError(
            try IOSRecipeFileStore(baseDirectory: root).readLiveRecipe(name: "fetch_digest"),
            "protected 拒绝不得写入 active"
        )
        XCTAssertNil(
            IOSPromotionReceiptStore(baseDirectory: root).snapshot(artifactId: "fetch_digest"),
            "protected 拒绝不得写入 receipt"
        )
        let catalog = await registry.refresh()
        XCTAssertFalse(
            catalog?.recipeTools.contains { $0.recipeName == "fetch_digest" } == true,
            "protected 拒绝后 registry 不得出现该制品"
        )
        XCTAssertEqual(
            metrics.snapshot().count(.protectedRegressionBlocked),
            1,
            "§19 protected regression 拦截计数"
        )
    }

    // MARK: - Fixtures

    private func makeDatabase() -> (root: URL, dao: AgentRuntimeDao) {
        let root = tempRoot()
        let path = root.appendingPathComponent("agent_runtime.db").path
        let db = IosDatabaseFactory.shared.createDatabase(atFilePath: path)
        databases.append(db)
        return (root, db.agentRuntimeDao())
    }

    /// 与详情页/发布通道相同的 apply 路径：prepare（CAS 双方哈希）→ apply。
    @discardableResult
    private func apply(store: IOSRecipeFileStore, json: String) throws -> String {
        let data = Data(json.utf8)
        let prep = try store.prepareRecipe(recipeJSON: data)
        return try store.applyRecipe(
            name: prep.candidate.name,
            recipeJSON: data,
            expectedBaseHash: prep.base?.hash,
            expectedCandidateHash: prep.candidate.hash
        ).promotedHash
    }

    private func seedFailedRun(
        dao: AgentRuntimeDao,
        ledger: IOSAgentRunLedger,
        runId: String,
        toolName: String,
        conversationId: String? = nil
    ) async {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let run = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: conversationId,
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
            dao.insertRun(run: run) { _ in
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
            errorCode: "step_failed",
            sourceRef: nil
        )
    }

    private func recentEvidence(dao: AgentRuntimeDao) async -> [IOSEvolutionEvidence] {
        await IOSEvolutionEvidenceProjector.projectRecent(
            sinceEpochMs: Int64(Date().timeIntervalSince1970 * 1000) - 7 * 24 * 3600 * 1000,
            dao: dao
        )
    }

    private func waitUntil(
        timeout: TimeInterval = 10,
        _ condition: @MainActor () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline {
                XCTFail("waitUntil 超时")
                return
            }
            try? await Task.sleep(for: .milliseconds(20))
        }
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

    // MARK: Suite / case builders (同 IOSArtifactEvaluatorTests 风格的确定性套件)

    private static func allGreenSuite() -> IOSEvaluationSuite {
        IOSEvaluationSuite(
            suiteId: "suite-e2e",
            failureReplayCases: [caseItem(id: "case:replay-1", url: "https://example.com/rss.xml")],
            protectedSuccessCases: [caseItem(id: "case:protected-1", url: "https://example.com/ok.xml")],
            sealedHoldoutCases: [caseItem(id: "case:sealed-1", url: "https://example.com/sealed.xml")]
        )
    }

    /// 套件：failure replay 通过、protected success 样例被 scripted 为失败 →
    /// 真实 evaluator 产出 protectedRegressions = 1、recommendation = .reject
    /// 的报告（§12.2 硬拒绝路径）。
    private static func protectedRegressionSuite() -> IOSEvaluationSuite {
        IOSEvaluationSuite(
            suiteId: "suite-protected-regression",
            failureReplayCases: [caseItem(id: "case:replay-prot-1", url: "https://example.com/rss.xml")],
            protectedSuccessCases: [protectedRegressionCase(id: "case:protected-regressed-1", url: "https://example.com/ok.xml")],
            sealedHoldoutCases: [caseItem(id: "case:sealed-prot-1", url: "https://example.com/sealed.xml")]
        )
    }

    /// protected success 样例：scrape_web 被 scripted 为失败 → 候选在旧成功
    /// 样例上回归（不变量 13）。
    private static func protectedRegressionCase(id: String, url: String) -> IOSEvaluationCase {
        IOSEvaluationCase(
            id: id,
            kind: .protectedSuccess,
            recipeInputs: ["url": .string(url)],
            scriptedPrimitives: [
                "scrape_web": IOSEvaluationScriptedPrimitive(
                    outputJSON: nil,
                    failureMessage: "simulated protected regression"
                ),
            ],
            assertions: IOSEvaluationAssertions(
                expectedOutputs: ["text": .string("fetched body")],
                expectedError: nil,
                expectedStepCalls: [
                    IOSEvaluationExpectedStepCall(
                        stepId: "fetch", tool: "scrape_web",
                        arguments: ["url": .string(url)]
                    ),
                ],
                expectedLedger: [
                    IOSEvaluationLedgerExpectation(
                        stepId: "fetch", outcome: "completed", outcomeKind: "success", errorCode: nil
                    ),
                ]
            ),
            originalFailure: nil
        )
    }

    /// Slice A oracle-backed 套件（workspace 差分）的 provider 形状返回——
    /// T0/T1 自动晋级唯一可达路径（v1 oracle 只覆盖 workspace 原语 → T2 卡）。
    private static func workspaceOracleSuiteResult() -> IOSEvolutionSuiteBuildResult {
        .built(
            suite: try! IOSEvolutionWorkspaceOracleFixtures.suite(),
            dataScopeSummary: "suite 数据范围（workspace oracle）"
        )
    }

    /// sideEffect Recipe（workspace_file_write）专用的全绿套件：`caseItem`
    /// 的期望 step 工具与 scripted 输出必须匹配该 Recipe，否则受保护样例
    /// 会“回归”（真实 evaluator 报告 protectedRegressions = 1）。
    private static func sideEffectGreenSuite(originRunId: String? = nil) -> IOSEvaluationSuite {
        IOSEvaluationSuite(
            suiteId: "suite-t2-sideeffect",
            failureReplayCases: [caseItem(id: "case:replay-t2-1", url: "https://example.com/rss.xml", tool: "workspace_file_write")],
            protectedSuccessCases: [caseItem(id: "case:protected-t2-1", url: "https://example.com/ok.xml", tool: "workspace_file_write")],
            sealedHoldoutCases: [caseItem(id: "case:sealed-t2-1", url: "https://example.com/sealed.xml", tool: "workspace_file_write")],
            originRunId: originRunId
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

    private static func recipeJSON(tool: String, name: String, version: String = "1.0.0") -> String {
        json([
            "schema": "amber.recipe.v1",
            "name": name,
            "version": version,
            "description": "抓取并返回正文。",
            "inputs": ["url": "string"],
            "steps": [
                ["id": "fetch", "tool": tool, "arguments": ["url": "${input.url}"]],
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
            .appendingPathComponent("ios-evolution-workflow-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }
}

import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Phase 2 收口: 生产评测套件构造器（§12.1 / §12.2 / §15 Phase 2 停止条件 /
/// §18.2）的真实取数契约测试。
///
/// 全真实组件：真实 Room 账本（isolated DB）+ 真实 `IOSConversationStore`
/// （临时目录，`newConversation` + `save` 落盘）→ 真实
/// `IOSEvolutionSuiteProvider` 从 ledger/消息 store 取数构造套件；评测断言串
/// 真实 `IOSArtifactEvaluator`；端到端串真实 workflow（scripted model + 真
/// suite provider / evaluator / store / registry / policy）从失败证据走到 T0
/// 自动发布。
@MainActor
final class IOSEvolutionSuiteProviderTests: XCTestCase {
    private var tempDirs: [URL] = []
    private var databases: [AgentRuntimeDatabase] = []

    override func tearDown() async throws {
        databases.removeAll()
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    // MARK: - 有失败证据 + 有成功历史 → 三类 case 齐备，fixture 与原始记录一致

    func testSuiteFromRealFactsHasAllThreeKindsWithFaithfulFixtures() async throws {
        let (root, dao, ledger, store) = makeEnvironment()
        let conversationHex = await makeConversation(store: store)
        let uuid = try XCTUnwrap(IOSEvolutionSuiteProvider.parseConversationId(conversationHex))
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // 失败 run: scrape_web 成功 → workspace_file_write 失败；run 终态 failed。
        // 成功历史 run: 同一任务形状（相同 primitive 序列），全部成功，终态 completed。
        // 注意：`store.save` 是整组替换（不是追加），两次 save 会互相覆盖——四个
        // tool part 必须放进同一条 assistant 消息一次落盘。
        await store.save(messages: [
            makeToolMessage(parts: [
                makeToolPart(toolCallId: "tc-1", toolName: "scrape_web",
                             input: #"{"url":"https://example.com/rss.xml"}"#,
                             output: #"{"ok":true,"text":"fetched body"}"#),
                makeToolPart(toolCallId: "tc-2", toolName: "workspace_file_write",
                             input: #"{"path":"/x/note.md","content":"fetched body"}"#,
                             output: #"{"ok":false,"tool":"workspace_file_write","reason":"directory missing"}"#),
                makeToolPart(toolCallId: "tc-3", toolName: "scrape_web",
                             input: #"{"url":"https://example.com/ok.xml"}"#,
                             output: #"{"ok":true,"text":"ok body"}"#),
                makeToolPart(toolCallId: "tc-4", toolName: "workspace_file_write",
                             input: #"{"path":"/x/ok.md","content":"ok body"}"#,
                             output: #"{"ok":true}"#),
            ]),
        ], to: uuid)
        await insertRun(dao: dao, runId: "run-fail-1", conversationId: conversationHex,
                        status: "failed", startedAt: now - 60_000)
        await recordCall(ledger: ledger, runId: "run-fail-1", toolCallId: "tc-1",
                         toolName: "scrape_web", outcome: "completed", outcomeKind: "success", errorCode: nil)
        await recordCall(ledger: ledger, runId: "run-fail-1", toolCallId: "tc-2",
                         toolName: "workspace_file_write", outcome: "failed", outcomeKind: "error", errorCode: "step_failed")
        await insertRun(dao: dao, runId: "run-ok-1", conversationId: conversationHex,
                        status: "completed", startedAt: now - 120_000)
        await recordCall(ledger: ledger, runId: "run-ok-1", toolCallId: "tc-3",
                         toolName: "scrape_web", outcome: "completed", outcomeKind: "success", errorCode: nil)
        await recordCall(ledger: ledger, runId: "run-ok-1", toolCallId: "tc-4",
                         toolName: "workspace_file_write", outcome: "completed", outcomeKind: "success", errorCode: nil)

        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.toolId == "workspace_file_write" && $0.observedOutcome == .error }
        )
        let provider = IOSEvolutionSuiteProvider(
            dao: dao,
            conversationStore: IOSConversationStore(baseDirectory: root)
        )
        let result = await provider.build(
            hypothesis: makeHypothesis(evidenceIds: [failureEvidence.id]),
            evidence: evidence
        )
        guard case .built(let suite, let scope) = result else {
            return XCTFail("expected built, got \(result)")
        }

        // 三类 case 齐备。
        XCTAssertEqual(suite.failureReplayCases.count, 1)
        XCTAssertEqual(suite.protectedSuccessCases.count, 1)
        XCTAssertEqual(suite.sealedHoldoutCases.count, 1)
        XCTAssertTrue(suite.proposerView.hasSealedHoldout)

        // failure replay: fixture 的 scripted 响应与原始记录一致。
        let replay = try XCTUnwrap(suite.failureReplayCases.first)
        XCTAssertEqual(replay.kind, .failureReplay)
        XCTAssertEqual(replay.recipeInputs, ["url": .string("https://example.com/rss.xml")])
        XCTAssertEqual(replay.scriptedPrimitives["scrape_web"]?.outputJSON, #"{"ok":true,"text":"fetched body"}"#)
        XCTAssertEqual(
            replay.scriptedPrimitives["workspace_file_write"]?.failureMessage,
            #"{"ok":false,"tool":"workspace_file_write","reason":"directory missing"}"#,
            "失败工具必须按观察到的错误转成 throwing fixture（错误传播）"
        )
        XCTAssertNil(replay.assertions.expectedError, "fix 判据 = 候选必须成功")
        XCTAssertEqual(replay.assertions.expectedStepCalls.count, 2)
        XCTAssertEqual(replay.assertions.expectedStepCalls[0].stepId, "step1")
        XCTAssertEqual(replay.assertions.expectedStepCalls[0].tool, "scrape_web")
        XCTAssertEqual(replay.assertions.expectedStepCalls[0].arguments,
                       ["url": .string("https://example.com/rss.xml")])
        XCTAssertEqual(replay.assertions.expectedStepCalls[1].tool, "workspace_file_write")
        XCTAssertNil(replay.assertions.expectedStepCalls[1].arguments, "失败 step 只断言工具，不断言精确参数")
        XCTAssertTrue(replay.assertions.expectedLedger.isEmpty,
                      "真实套件不写 ledger 期望（candidate 的 step id 在套件构造时未知，断言会变成巧合门禁）")
        XCTAssertEqual(replay.originalFailure?.failedStepId, "step2")
        XCTAssertEqual(replay.originalFailure?.errorKind, .stepFailed)
        XCTAssertEqual(replay.originalFailure?.completedStepIds, ["step1"])

        // sealed: 输入与 failure 不同、key 集合不变、只写契约级断言、物理隔离。
        let sealed = try XCTUnwrap(suite.sealedHoldoutCases.first)
        XCTAssertEqual(sealed.kind, .sealedHoldout)
        XCTAssertNotEqual(sealed.recipeInputs, replay.recipeInputs, "sealed 输入必须与 failure 输入不同")
        XCTAssertEqual(Set(sealed.recipeInputs.keys), Set(replay.recipeInputs.keys),
                       "sealed 保持同一输入 schema（key 集合不变）")
        XCTAssertNil(sealed.scriptedPrimitives["workspace_file_write"]?.failureMessage,
                     "sealed 区不重现失败（非 throwing）")
        XCTAssertEqual(sealed.assertions.expectedStepCalls.map(\.tool), ["scrape_web", "workspace_file_write"])
        XCTAssertTrue(sealed.assertions.expectedStepCalls.allSatisfy { $0.arguments == nil },
                      "sealed 只写契约级断言（step 序列），不写精确输出")
        XCTAssertNil(sealed.assertions.expectedError)
        XCTAssertNil(sealed.originalFailure)
        XCTAssertFalse(suite.proposerView.failureReplayCaseRefs.contains(sealed.id))
        XCTAssertFalse(suite.proposerView.protectedSuccessCaseRefs.contains(sealed.id),
                       "sealed 内容不得出现在 proposer 可见视图（I-12）")

        // protected: 成功历史的精确参数 + 关键断言。
        let protected = try XCTUnwrap(suite.protectedSuccessCases.first)
        XCTAssertEqual(protected.kind, .protectedSuccess)
        XCTAssertEqual(protected.recipeInputs, ["url": .string("https://example.com/ok.xml")])
        XCTAssertEqual(protected.assertions.expectedStepCalls.count, 2)
        XCTAssertEqual(protected.assertions.expectedStepCalls[1].arguments,
                       ["path": .string("/x/ok.md"), "content": .string("ok body")])
        XCTAssertTrue(protected.assertions.expectedLedger.isEmpty)
        XCTAssertNil(protected.originalFailure)

        // §18.2 数据范围：覆盖真实纳入的 run/工具，不含无关会话正文。
        XCTAssertTrue(scope.contains("run-fail-1"))
        XCTAssertTrue(scope.contains("run-ok-1"))
        XCTAssertTrue(scope.contains("scrape_web"))
        XCTAssertTrue(scope.contains("workspace_file_write"))
        XCTAssertTrue(scope.contains("url"), "数据范围包含输入字段名")
        XCTAssertFalse(scope.contains("fetched body"), "数据范围不得包含消息正文")
        XCTAssertFalse(scope.contains("ok body"))
    }

    // MARK: - 缺 protected 历史 → 套件缺 protected 区 → 真 evaluator 出 manualJudgmentRequired

    func testMissingProtectedHistoryDowngradesViaRealEvaluatorToManualJudgment() async throws {
        let (root, dao, ledger, store) = makeEnvironment()
        let conversationHex = await makeConversation(store: store)
        let uuid = try XCTUnwrap(IOSEvolutionSuiteProvider.parseConversationId(conversationHex))
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // 终端失败 run: 全部调用成功、run 终态 failed（无失败工具调用）。
        await store.save(messages: [
            makeToolMessage(parts: [
                makeToolPart(toolCallId: "tc-1", toolName: "scrape_web",
                             input: #"{"url":"https://example.com/rss.xml"}"#,
                             output: #"{"ok":true,"text":"fetched body"}"#),
            ]),
        ], to: uuid)
        await insertRun(dao: dao, runId: "run-fail-1", conversationId: conversationHex,
                        status: "failed", startedAt: now - 60_000)
        await recordCall(ledger: ledger, runId: "run-fail-1", toolCallId: "tc-1",
                         toolName: "scrape_web", outcome: "completed", outcomeKind: "success", errorCode: nil)

        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.runId == "run-fail-1" && $0.observedOutcome == .error && $0.toolId == nil }
        )
        let provider = IOSEvolutionSuiteProvider(
            dao: dao,
            conversationStore: IOSConversationStore(baseDirectory: root)
        )
        let result = await provider.build(
            hypothesis: makeHypothesis(evidenceIds: [failureEvidence.id]),
            evidence: evidence
        )
        guard case .built(let suite, let scope) = result else {
            return XCTFail("expected built, got \(result)")
        }
        XCTAssertTrue(suite.protectedSuccessCases.isEmpty, "无成功历史 → 套件缺 protected 区（诚实降级，不伪造）")
        XCTAssertEqual(suite.failureReplayCases.count, 1)
        XCTAssertEqual(suite.sealedHoldoutCases.count, 1)
        XCTAssertTrue(scope.contains("缺 protected 区"), "数据范围必须说明 protected 缺失")

        // 串真 evaluator：缺 protected 区 → manualJudgmentRequired（不是 reject——case 本身全过）。
        let evaluator = makeEvaluator(root: root, dao: dao)
        let data = try recipeData()
        let outcome = await evaluator.evaluate(
            candidateBytes: data,
            expectedCandidateHash: try candidateHash(root: root, data: data),
            suite: suite
        )
        guard case .report(let report) = outcome else {
            return XCTFail("expected report, got \(outcome)")
        }
        XCTAssertTrue(report.results.allSatisfy(\.passed), "case 全过，降级来自套件完整性而非 case 失败")
        XCTAssertEqual(report.recommendation, .manualJudgmentRequired, "§12.1: 缺 protected → 人工判断")
        XCTAssertTrue(report.unresolvedRisks.contains("no_protected_success_cases"))
    }

    // MARK: - protected 扫描上限：最近 K 条 completed run 之外不再扫描

    /// `findProtectedRun` 的候选扫描必须有界（§18.3 预算）：K 条最近的
    /// 非匹配 completed run 占满窗口后，窗口外更旧但形状匹配的成功 run
    /// 不得再被扫描——否则一次诊断的账本/消息读取随 run 数线性增长。
    /// 对照组由 `testSuiteFromRealFactsHasAllThreeKindsWithFaithfulFixtures`
    /// 覆盖（匹配 run 在窗口内 → protected 区恢复）。
    func testProtectedScanIsCappedToMostRecentKRuns() async throws {
        let (root, dao, ledger, store) = makeEnvironment()
        let conversationHex = await makeConversation(store: store)
        let uuid = try XCTUnwrap(IOSEvolutionSuiteProvider.parseConversationId(conversationHex))
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let cap = IOSEvolutionSuiteProvider.maximumProtectedRunCandidates
        XCTAssertGreaterThanOrEqual(cap, 1)

        // 失败 run: scrape_web 成功 → workspace_file_write 失败（形状
        // [scrape_web, workspace_file_write]，首个输入 key 集合 {url}）。
        // 窗口外旧匹配 run: 同形状、全部成功、input key 集合相同——按旧行为
        // 会被扫描到；上限生效后必须在窗口外被截断。
        await store.save(messages: [
            makeToolMessage(parts: [
                makeToolPart(toolCallId: "tc-1", toolName: "scrape_web",
                             input: #"{"url":"https://example.com/rss.xml"}"#,
                             output: #"{"ok":true,"text":"fetched body"}"#),
                makeToolPart(toolCallId: "tc-2", toolName: "workspace_file_write",
                             input: #"{"path":"/x/note.md","content":"fetched body"}"#,
                             output: #"{"ok":false,"tool":"workspace_file_write","reason":"directory missing"}"#),
                makeToolPart(toolCallId: "tc-ok-1", toolName: "scrape_web",
                             input: #"{"url":"https://example.com/ok.xml"}"#,
                             output: #"{"ok":true,"text":"ok body"}"#),
                makeToolPart(toolCallId: "tc-ok-2", toolName: "workspace_file_write",
                             input: #"{"path":"/x/ok.md","content":"ok body"}"#,
                             output: #"{"ok":true}"#),
            ]),
        ], to: uuid)
        await insertRun(dao: dao, runId: "run-fail-cap", conversationId: conversationHex,
                        status: "failed", startedAt: now - 60_000)
        await recordCall(ledger: ledger, runId: "run-fail-cap", toolCallId: "tc-1",
                         toolName: "scrape_web", outcome: "completed", outcomeKind: "success", errorCode: nil)
        await recordCall(ledger: ledger, runId: "run-fail-cap", toolCallId: "tc-2",
                         toolName: "workspace_file_write", outcome: "failed", outcomeKind: "error", errorCode: "step_failed")

        // 窗口内的 K 条最近 completed run：单调用 search_web（形状不匹配，
        // 每条只消耗一次账本读取）。
        for index in 0..<cap {
            await insertRun(dao: dao, runId: "run-close-\(index)", conversationId: conversationHex,
                            status: "completed", startedAt: now - 30_000 - Int64(index))
            await recordCall(ledger: ledger, runId: "run-close-\(index)", toolCallId: "tc-close-\(index)",
                             toolName: "search_web", outcome: "completed", outcomeKind: "success", errorCode: nil)
        }
        // 窗口外的旧匹配 run（startedAt 远早于全部窗口内 run）。
        await insertRun(dao: dao, runId: "run-old-match", conversationId: conversationHex,
                        status: "completed", startedAt: now - 10_000_000)
        await recordCall(ledger: ledger, runId: "run-old-match", toolCallId: "tc-ok-1",
                         toolName: "scrape_web", outcome: "completed", outcomeKind: "success", errorCode: nil)
        await recordCall(ledger: ledger, runId: "run-old-match", toolCallId: "tc-ok-2",
                         toolName: "workspace_file_write", outcome: "completed", outcomeKind: "success", errorCode: nil)

        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.runId == "run-fail-cap" && $0.toolId == "workspace_file_write" && $0.observedOutcome == .error }
        )
        let provider = IOSEvolutionSuiteProvider(
            dao: dao,
            conversationStore: IOSConversationStore(baseDirectory: root)
        )
        let result = await provider.build(
            hypothesis: makeHypothesis(evidenceIds: [failureEvidence.id]),
            evidence: evidence
        )
        guard case .built(let suite, let scope) = result else {
            return XCTFail("expected built, got \(result)")
        }
        XCTAssertTrue(
            suite.protectedSuccessCases.isEmpty,
            "形状匹配的成功 run 在最近 \(cap) 条窗口之外，不得被扫描（上限生效）"
        )
        XCTAssertTrue(scope.contains("缺 protected 区"), "数据范围必须如实说明 protected 缺失")
    }

    // MARK: - 取数失败/证据不足 → typed 结果，不出半成品 suite

    func testInsufficientFactsAndFetchFailuresAreTypedResultsNotHalfSuites() async throws {
        let (root, dao, ledger, store) = makeEnvironment()
        let provider = IOSEvolutionSuiteProvider(dao: dao, conversationStore: store)
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // (a) 没有失败证据 → insufficientData。
        let empty = await provider.build(
            hypothesis: makeHypothesis(evidenceIds: ["nope"]), evidence: []
        )
        guard case .insufficientData = empty else {
            return XCTFail("no evidence must be insufficientData, got \(empty)")
        }

        // (b) run 存在但没有账本事件 → insufficientData。
        let conversationHex = await makeConversation(store: store)
        await insertRun(dao: dao, runId: "run-empty", conversationId: conversationHex,
                        status: "failed", startedAt: now - 60_000)
        let evidenceB = await recentEvidence(dao: dao)
        let terminalB = try XCTUnwrap(evidenceB.first { $0.runId == "run-empty" })
        let resultB = await provider.build(
            hypothesis: makeHypothesis(evidenceIds: [terminalB.id]), evidence: evidenceB
        )
        guard case .insufficientData = resultB else {
            return XCTFail("run without ledger events must be insufficientData, got \(resultB)")
        }

        // (c) run 没有会话归属 → insufficientData（无法取回真实输入）。
        await insertRun(dao: dao, runId: "run-noconv", conversationId: nil,
                        status: "failed", startedAt: now - 30_000)
        await recordCall(ledger: ledger, runId: "run-noconv", toolCallId: "tc-1",
                         toolName: "scrape_web", outcome: "failed", outcomeKind: "error", errorCode: "boom")
        let evidenceC = await recentEvidence(dao: dao)
        let errorC = try XCTUnwrap(evidenceC.first { $0.runId == "run-noconv" })
        let resultC = await provider.build(
            hypothesis: makeHypothesis(evidenceIds: [errorC.id]), evidence: evidenceC
        )
        guard case .insufficientData = resultC else {
            return XCTFail("run without conversation must be insufficientData, got \(resultC)")
        }

        // (d) 失败事件无法归位到 run 的工具序列 → failed（账本与证据矛盾）。
        let ghost = IOSEvolutionEvidence(
            id: "ev:ghost",
            runId: "run-noconv",
            sourceRefs: [IOSEvidenceRef(kind: .ledgerEvent, id: "no-such-event")],
            observedOutcome: .error,
            toolId: "scrape_web",
            toolVersion: nil,
            terminalReason: nil,
            userSignal: nil,
            redactedSummary: "ghost",
            createdAtEpochMs: now
        )
        let resultD = await provider.build(
            hypothesis: makeHypothesis(evidenceIds: [ghost.id]), evidence: [ghost]
        )
        guard case .failed = resultD else {
            return XCTFail("unattributable failure event must be failed, got \(resultD)")
        }
    }

    // MARK: - knownToolNames 漂移回归（真实声明源派生）

    func testKnownToolNamesDeriveFromRealDeclarationSource() {
        let runtimeNames = ToolKt.iosToolDeclarationNames()
        XCTAssertEqual(
            IOSEvolutionCatalogSummary.knownToolNames,
            runtimeNames,
            "knownToolNames 必须与运行时真实声明列表相等（不允许手工镜像名单）"
        )
        XCTAssertGreaterThan(runtimeNames.count, 60, "声明表应覆盖全部 iOS 静态工具")
        for name in ["scrape_web", "search_web", "workspace_file_write", "tools_list",
                     "spawn_agent", "session_read", "generate_image"] {
            XCTAssertTrue(runtimeNames.contains(name), "\(name) 必须在真实声明列表中")
            XCTAssertNotNil(ToolKt.iosToolDeclaration(name: name), "\(name) 必须能解析出声明")
        }
        XCTAssertTrue(runtimeNames.allSatisfy { ToolKt.iosToolDeclaration(name: $0) != nil },
                      "声明列表中的每个名字都必须能解析出声明")

        // assemble 仍按真实 catalog oracle 过滤并标注 effect class。
        let summary = IOSEvolutionCatalogSummary.assemble(catalog: { tool in
            tool == "scrape_web"
                ? IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .pure)
                : nil
        })
        XCTAssertEqual(summary, "scrape_web (pure)")
    }

    // MARK: - 端到端：真实 recipe run 失败证据 → provider 差分套件 → oracle 评测 → 人工批准发布

    /// Slice A 组装链契约（真实 provider，不是测试直造 suite）：ledger 里的
    /// recipe run 事实（recipe__note_writer 容器行 + 带 artifactId/artifactVersion
    /// 的 step 行）+ 活 recipe baseline → provider 冻结 baseline 精确字节并从
    /// manifest 派生 workspace 后置条件 → evaluator 在隔离临时 Workspace 里做
    /// 真实差分（baseline 复现 binding 失败、候选修复、protected/sealed 通过）
    /// → promote → sideEffect envelope（T2）人工卡 → 批准发布（approvedBy=user）
    /// → 活 recipe 被 supersede 到修复版本。
    ///
    /// Slice A 契约变更：无 oracle 的 scripted-only 套件永远走不到 publish
    /// （manualJudgmentRequired），T0/T1 自动晋级只对确定性 oracle 可达；
    /// v1 oracle 只覆盖 workspace 原语（生产目录 sideEffect → T2），故端到端
    /// 真实路径止于人工批准卡。
    func testEndToEndRecipeRunEvidenceToApprovedPublishWithRealSuiteProvider() async throws {
        let (root, dao, ledger, store) = makeEnvironment()
        let conversationHex = await makeConversation(store: store)
        let uuid = try XCTUnwrap(IOSEvolutionSuiteProvider.parseConversationId(conversationHex))
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // 活 baseline：note_writer@1.0.0（copy 绑 ${step.write.output.text}，
        // write 真实输出无 "text" 字段 ⇒ 运行时 argumentBinding 失败）。
        let recipeStore = IOSRecipeFileStore(baseDirectory: root)
        let baseline = try IOSEvolutionWorkspaceOracleFixtures.noteWriterRecipeData(
            version: "1.0.0", copyContent: "${step.write.output.text}"
        )
        _ = try recipeStore.applyRecipe(
            name: "note_writer", recipeJSON: baseline,
            expectedBaseHash: nil,
            expectedCandidateHash: recipeStore.prepareRecipe(recipeJSON: baseline).candidate.hash
        )

        // 失败 run：recipe__note_writer@1.0.0 一次真实执行（write 成功、copy
        // binding 失败）。成功历史：同 recipe 0.9.0（copy 绑 ${input.text} 的
        // 可用版本）一次完整执行。全部 tool part 一次落盘（save 是整组替换）。
        await store.save(messages: [
            makeToolMessage(parts: [
                makeToolPart(toolCallId: "tc-outer", toolName: "recipe__note_writer",
                             input: #"{"text":"复盘正文"}"#,
                             output: #"{"ok":false,"tool":"recipe__note_writer","step":"copy","reason":"unresolved binding"}"#),
                makeToolPart(toolCallId: "recipe-e1-write", toolName: "workspace_file_write",
                             input: #"{"path":"inbox/note.txt","content":"复盘正文"}"#,
                             output: #"{"ok":true,"id":"f1","path":"inbox/note.txt","size_bytes":15}"#),
                makeToolPart(toolCallId: "recipe-e1-copy", toolName: "workspace_file_write",
                             input: #"{"path":"out/copy.txt"}"#,
                             output: #"{"ok":false,"tool":"workspace_file_write","error":"unresolved binding: step.write.output.text"}"#),
                makeToolPart(toolCallId: "tc-outer-ok", toolName: "recipe__note_writer",
                             input: #"{"text":"历史正文"}"#,
                             output: #"{"ok":true,"tool":"recipe__note_writer","status":"completed"}"#),
                makeToolPart(toolCallId: "recipe-e2-write", toolName: "workspace_file_write",
                             input: #"{"path":"inbox/note.txt","content":"历史正文"}"#,
                             output: #"{"ok":true,"id":"f2","path":"inbox/note.txt","size_bytes":12}"#),
                makeToolPart(toolCallId: "recipe-e2-copy", toolName: "workspace_file_write",
                             input: #"{"path":"out/copy.txt","content":"历史正文"}"#,
                             output: #"{"ok":true,"id":"f3","path":"out/copy.txt","size_bytes":12}"#),
            ]),
        ], to: uuid)
        await insertRun(dao: dao, runId: "run-fail-ws", conversationId: conversationHex,
                        status: "failed", startedAt: now - 60_000)
        await recordCall(ledger: ledger, runId: "run-fail-ws", toolCallId: "tc-outer",
                         toolName: "recipe__note_writer", outcome: "failed", outcomeKind: "error",
                         errorCode: "step_failed")
        await recordCall(ledger: ledger, runId: "run-fail-ws", toolCallId: "recipe-e1-write",
                         toolName: "workspace_file_write", outcome: "completed", outcomeKind: "success",
                         errorCode: nil, artifactId: "recipe__note_writer", artifactVersion: "1.0.0")
        await recordCall(ledger: ledger, runId: "run-fail-ws", toolCallId: "recipe-e1-copy",
                         toolName: "workspace_file_write", outcome: "failed", outcomeKind: "error",
                         errorCode: "argument_binding", artifactId: "recipe__note_writer", artifactVersion: "1.0.0")
        await insertRun(dao: dao, runId: "run-ok-ws", conversationId: conversationHex,
                        status: "completed", startedAt: now - 120_000)
        await recordCall(ledger: ledger, runId: "run-ok-ws", toolCallId: "tc-outer-ok",
                         toolName: "recipe__note_writer", outcome: "completed", outcomeKind: "success",
                         errorCode: nil)
        await recordCall(ledger: ledger, runId: "run-ok-ws", toolCallId: "recipe-e2-write",
                         toolName: "workspace_file_write", outcome: "completed", outcomeKind: "success",
                         errorCode: nil, artifactId: "recipe__note_writer", artifactVersion: "0.9.0")
        await recordCall(ledger: ledger, runId: "run-ok-ws", toolCallId: "recipe-e2-copy",
                         toolName: "workspace_file_write", outcome: "completed", outcomeKind: "success",
                         errorCode: nil, artifactId: "recipe__note_writer", artifactVersion: "0.9.0")

        let evidence = await recentEvidence(dao: dao)
        let failureEvidence = try XCTUnwrap(
            evidence.first { $0.runId == "run-fail-ws" && $0.observedOutcome == .error },
            "需要一条可归因的 recipe run 失败证据"
        )

        let provider = IOSEvolutionSuiteProvider(
            dao: dao,
            conversationStore: IOSConversationStore(baseDirectory: root),
            recipeStoreBaseDirectory: root
        )

        // 组装链中点断言：provider 必须把 recipe run 证据分类成 workspace 差分
        // case——冻结 baseline 精确字节、定位失败 step、派生后置条件。
        let probe = await provider.build(
            hypothesis: makeHypothesis(evidenceIds: [failureEvidence.id]),
            evidence: evidence
        )
        guard case .built(let probeSuite, _) = probe else {
            return XCTFail("expected built, got \(probe)")
        }
        let replayCase = try XCTUnwrap(probeSuite.failureReplayCases.first)
        let scenario = try XCTUnwrap(
            replayCase.workspaceScenario,
            "recipe run + workspace 原语失败必须挂差分 scenario（否则整链退化为无 oracle）"
        )
        XCTAssertEqual(scenario.baseline?.artifactId, "recipe__note_writer")
        XCTAssertEqual(scenario.baseline?.version, "1.0.0")
        XCTAssertEqual(scenario.baseline?.canonicalJSON, baseline,
                       "baseline 必须冻结 store 里的精确字节（exact-bytes CAS 语义）")
        XCTAssertEqual(scenario.baseline?.failingStepIndex, 1, "失败 step 必须定位到 copy")
        XCTAssertEqual(scenario.postconditions.count, 3,
                       "write 字面路径+输入模板、copy 字面路径派生 3 条后置条件（copy content 是 stepOutput，不可派生）")
        XCTAssertEqual(replayCase.originalFailure?.failedStepId, "copy")
        XCTAssertEqual(replayCase.originalFailure?.errorKind, .argumentBinding)
        XCTAssertEqual(replayCase.recipeInputs, ["text": .string("复盘正文")],
                       "recipe inputs 必须取自容器调用的输入")
        XCTAssertNil(probeSuite.protectedSuccessCases.first?.workspaceScenario?.baseline,
                     "protected 不挂 baseline（它不是失败重放）")
        XCTAssertEqual(probeSuite.protectedSuccessCases.count, 1, "0.9.0 成功历史必须成为 protected case")

        // Scripted model: 诊断 JSON（引用真实 evidence id）→ 修复 binding 的
        // note_writer v1.0.1 候选。
        let model = ScriptedModel(responses: [
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
            suiteProvider: { hypothesis, evidence in
                await provider.build(hypothesis: hypothesis, evidence: evidence)
            },
            registryRefresh: { await registry.refresh() }
        )

        let task = workflow.analyzeAndImprove(conversationHex: nil)
        await task?.value

        // oracle-backed promote + sideEffect envelope（T2）→ 人工批准卡。
        // （scripted-only 套件在此处只会得到 manualJudgmentRequired——但本测试
        // 的 suite 来自真实 provider 的差分分类，promote 才可达。）
        let card = try XCTUnwrap(workflow.pendingApproval, "T2 必须出现人工批准卡")
        XCTAssertEqual(card.artifactName, "note_writer")
        XCTAssertEqual(card.mutationKind, .update, "同名候选是对活 recipe 的 supersede")
        XCTAssertTrue(card.unresolvedRisks.isEmpty,
                      "oracle-backed 评测不得有未决风险（否则 recommendation 不可能是 promote）")
        XCTAssertFalse(card.evaluationResultsText.contains("✗"),
                       "三类 case 必须全部通过（含 baseline 差分 replay）：\(card.evaluationResultsText)")
        XCTAssertTrue(workflow.notifications.isEmpty, "等待批准时不得发布任何通知/制品")

        let approve = workflow.approvePending()
        await approve?.value
        let notification = try XCTUnwrap(workflow.notifications.first)
        XCTAssertEqual(notification.kind, .autoPromoted)
        XCTAssertEqual(notification.artifactName, "note_writer")
        XCTAssertTrue(notification.canRollback, "发布通知必须带一键回退")
        XCTAssertTrue(notification.detail.contains("评测数据范围"), "发布通知必须展示 §18.2 数据范围")

        // supersede：活 recipe 从 buggy 1.0.0 升到修复版 1.0.1，receipt 绑定精确字节。
        let fixed = try IOSEvolutionWorkspaceOracleFixtures.noteWriterRecipeData(
            version: "1.0.1", copyContent: "${input.text}"
        )
        let live = try recipeStore.readLiveRecipe(name: "note_writer")
        XCTAssertEqual(live.hash, try candidateHash(root: root, data: fixed),
                       "发布后活 recipe 必须是修复版本的精确字节")
        let receipt = IOSPromotionReceiptStore(baseDirectory: root)
            .snapshot(artifactId: "note_writer")?.active
        XCTAssertNotNil(receipt)
        XCTAssertEqual(receipt?.approvedBy, "user")
        XCTAssertEqual(receipt?.toHash, live.hash)

        let snapshot = await registry.refresh()
        XCTAssertTrue(
            snapshot?.recipeTools.contains { $0.recipeName == "note_writer" } == true,
            "下一模型轮必须能发现已发布的 Recipe"
        )
    }

    // MARK: - Fixtures

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
            dao.insertRun(run: run) { _ in
                continuation.resume()
            }
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
            runId: runId,
            toolCallId: toolCallId,
            toolName: toolName,
            argsDigest: "digest",
            effectClass: .sideEffect
        )
        await ledger.recordToolCallFinished(
            runId: runId,
            toolCallId: toolCallId,
            outcome: outcome,
            artifactId: artifactId,
            artifactVersion: artifactVersion,
            outcomeKind: outcomeKind,
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

    private var testCatalog: IOSRecipeCatalogLookup {
        { tool in
            switch tool {
            case "search_web":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .pure)
            case "scrape_web":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .pure)
            case "workspace_file_write":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .sideEffect)
            default:
                return nil
            }
        }
    }

    // MARK: Evaluator / candidate helpers（同 IOSArtifactEvaluatorTests 风格）

    private func makeEvaluator(root: URL, dao: AgentRuntimeDao) -> IOSArtifactEvaluator {
        IOSArtifactEvaluator(
            recipeStoreBaseDirectory: root,
            catalog: testCatalog,
            ledger: IOSAgentRunLedger(dao: dao),
            dao: dao
        )
    }

    private func candidateHash(root: URL, data: Data) throws -> String {
        try IOSRecipeFileStore(baseDirectory: root).prepareRecipe(recipeJSON: data).candidate.hash
    }

    /// 单 step 候选：scrape_web(url) → text。与终端失败 run 的套件契约一致。
    private func recipeData() throws -> Data {
        try Self.jsonData([
            "schema": "amber.recipe.v1",
            "name": "digest_recipe",
            "version": "1.0.0",
            "description": "抓取并返回正文。",
            "inputs": ["url": "string"],
            "steps": [
                ["id": "fetch", "tool": "scrape_web", "arguments": ["url": "${input.url}"]],
            ],
            "outputs": ["text": "${step.fetch.output.text}"],
        ])
    }

    private static func hypothesisJSON(evidenceId: String) -> String {
        json([
            "kind": "composition",
            "claim": "重复编排不稳定",
            "confidence": 0.8,
            "alternatives": ["可能是网络波动"],
            "falsifier": "若手工逐步执行同样失败则推翻",
            "recommended_artifact": "recipe",
            "tool_ids": ["search_web", "scrape_web"],
            "mcp_connections": [],
            "evidence_ids": [evidenceId],
        ])
    }

    private static func jsonData(_ dict: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
    }

    private static func json(_ dict: [String: Any]) -> String {
        let data = try! JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
        return String(data: data, encoding: .utf8)!
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-evolution-suite-provider-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }
}

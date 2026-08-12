import Foundation
@preconcurrency import Shared

// MARK: - IOSArtifactEvaluator
//
// Phase 2 Wave C: independent, deterministic evaluator (§12 / §13.1 / §15
// Phase 2 / §18.3). RECIPE candidates only in v1. The evaluator is separated
// from the proposer (invariant 12): it owns the FULL suite — including the
// sealed holdout region — and only ever hands out
// `IOSEvaluationSuiteProposerView` (no case content).
//
// Tier mapping onto the REAL runner (§12.2):
// - Static tier: the FULL real validator (`IOSRecipeValidator`) on the
//   candidate's exact bytes; ANY issue → typed terminal `.staticRejected`.
// - Deterministic contract tier: every suite case runs the REAL
//   `IOSRecipeRunner` with a scripted primitive executor built from the case's
//   fixtures, writing to a REAL `IOSAgentRunLedger` on an isolated DB. Per-case
//   assertions cover step order + exact resolved argument bindings (recorded
//   calls), error propagation (expected error shape), recipe outputs and W1
//   Started/Finished ledger records (read back through the real DAO).
//   "Executed bytes == candidateHash" (invariant 5): the manifest is decoded
//   from the SAME canonical bytes the recipe store hashes (zero-write
//   `prepareRecipe`), and the report binds that hash; a caller-supplied
//   expected hash mismatch fails closed (`.candidateHashMismatch`).
// - Failure replay tier (§12.1): the candidate must FIX the replayed failure
//   or clearly NARROW it. v1 deterministic reading of "明确缩小失败面": the
//   candidate gets strictly further (more completed steps) before failing.
//   Unfixed AND not narrowed → `.reject`. A failure-replay case whose
//   `originalFailure` is missing defaults to "must fix" (fail closed).
// - Protected success tier (invariant 13): ANY key assertion regression on a
//   protected case → `protectedRegressions` → HARD `.reject`, even when
//   failure replay improved (§15 Phase 2 acceptance 3).
// - Sealed holdout tier (§12.1/§12.3): feeds the recommendation; a suite
//   without sealed holdout cases (or without failure-replay / protected cases,
//   §15 Phase 2 stop condition) is downgraded to `.manualJudgmentRequired` and
//   can never auto-promote.
// - LLM judge / stochastic-repeat tiers are NOT implemented in v1; the report
//   records them in `skippedTiers` honestly (they never gate v1 promotion —
//   the deterministic gates above are the hard ones, §12.3).
//
// Budgets (§18.3): case-count and wall-clock caps. Exhaustion returns a typed
// terminal (`IOSEvaluationOutcome.budgetExhausted`) — the evaluator NEVER
// lowers the bar to fit the budget, and a non-positive budget is treated as
// immediately exhausted (documented, deterministic). Inner bound within a
// case is the runner's per-step timeout; the wall-clock budget is checked at
// case boundaries.

// MARK: - Budgets (§18.3)

struct IOSEvaluationBudget: Equatable, Sendable {
    /// Maximum number of cases the evaluator may run.
    var maxCaseCount: Int
    /// Maximum wall-clock time for the whole evaluation (checked at case
    /// boundaries; per-step timeouts are the inner bound).
    var maxWallClockSeconds: TimeInterval

    init(maxCaseCount: Int = 64, maxWallClockSeconds: TimeInterval = 60) {
        self.maxCaseCount = maxCaseCount
        self.maxWallClockSeconds = maxWallClockSeconds
    }

    static let standard = IOSEvaluationBudget()
}

/// Which budget ran out (§18.3: exhaustion is a typed terminal, never a
/// silent downgrade of the evaluation standard).
enum IOSEvaluationBudgetExhaustion: Equatable, Sendable {
    case caseCount(maxCaseCount: Int)
    case wallClock(maxWallClockSeconds: TimeInterval)
}

// MARK: - Outcome

enum IOSEvaluationOutcome: Equatable, Sendable {
    /// The evaluation finished; `report.recommendation` is the evaluator's
    /// recommendation (a host-side policy engine still owns the decision).
    case report(IOSEvaluationReport)
    /// Static tier failed (§12.2: "直接拒绝 candidate") — no report exists.
    case staticRejected(issues: [IOSRecipeValidationIssue])
    /// The provided bytes do not hash to the expected candidate hash (§13.1:
    /// candidate content changed between prepare and evaluate → fail closed).
    case candidateHashMismatch(expected: String, actual: String)
    /// §18.3 budget exhaustion — typed terminal, no report, no promotion.
    case budgetExhausted(IOSEvaluationBudgetExhaustion)
}

// MARK: - Ledger readback

/// One decoded `agent_event` row reduced to what the ledger assertions need.
/// Parsed inside the DAO callback so only Sendable Swift values cross the
/// continuation. Mirrors `IOSToolCallLedgerRow.decode`'s fail-closed rule
/// (a row without a recoverable `toolCallId` is dropped, never guessed) and
/// additionally decodes the evolution-contract Finished keys (`outcomeKind`,
/// `errorCode`) that the base row type deliberately does not carry.
struct IOSEvaluationLedgerRow: Equatable, Sendable {
    let toolCallId: String
    let type: String
    /// Present only on Finished rows.
    let outcome: String?
    let outcomeKind: String?
    let errorCode: String?

    static func decode(type: String, payload: String) -> IOSEvaluationLedgerRow? {
        guard let data = payload.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let toolCallId = (object["toolCallId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !toolCallId.isEmpty else {
            return nil
        }
        return IOSEvaluationLedgerRow(
            toolCallId: toolCallId,
            type: type,
            outcome: object["outcome"] as? String,
            outcomeKind: object["outcomeKind"] as? String,
            errorCode: object["errorCode"] as? String
        )
    }
}

// MARK: - Scripted primitive executor

/// Scripted primitive executor built from a case's fixtures: every recorded
/// call is kept (so the step-call contract can be asserted), a tool the case
/// did NOT script fails closed, an optional delay exercises time budgets, and
/// a `failureMessage` makes the step fail with that message.
actor IOSEvaluationScriptedExecutor: Sendable {
    struct Call: Equatable, Sendable {
        let tool: String
        let argsJSON: String
    }

    private let fixtures: [String: IOSEvaluationScriptedPrimitive]
    private(set) var calls: [Call] = []

    init(fixtures: [String: IOSEvaluationScriptedPrimitive]) {
        self.fixtures = fixtures
    }

    func execute(tool: String, argsJSON: String) async throws -> String {
        calls.append(Call(tool: tool, argsJSON: argsJSON))
        guard let fixture = fixtures[tool] else {
            // Deterministic contract violation: the case did not script this
            // tool. Fail closed — never invent an output for an unscripted
            // primitive (a candidate adding steps must be visible to the case).
            throw IOSEvaluationScriptedError.unknownTool(tool)
        }
        if let delaySeconds = fixture.delaySeconds, delaySeconds > 0 {
            try await Task.sleep(for: .seconds(delaySeconds))
        }
        if let failureMessage = fixture.failureMessage {
            throw NSError(
                domain: "IOSEvaluationScriptedExecutor", code: 1,
                userInfo: [NSLocalizedDescriptionKey: failureMessage]
            )
        }
        return fixture.outputJSON ?? "{}"
    }
}

enum IOSEvaluationScriptedError: LocalizedError, Equatable {
    case unknownTool(String)

    var errorDescription: String? {
        switch self {
        case .unknownTool(let tool):
            "评测 case 没有为工具「\(tool)」提供 scripted 响应。"
        }
    }
}

// MARK: - Hybrid executor（Slice A：workspace 真实执行 + 其余 scripted）

/// 差分 oracle 的执行器：Workspace 本地读写 primitive 走真实
/// `IOSWorkspaceStore`（隔离 temp root），其余工具仍按 case 的 scripted
/// fixture 响应，未脚本化的工具 fail closed。Workspace 的 `ok:false` 输出
/// 翻译为 step 失败——recipe 语义是 stop-on-failure（§10.3.6），评测语义
/// 必须与修复后的生产路由一致（见 ChatToolRuntime 的 recipe step 路径）。
actor IOSEvaluationHybridExecutor: Sendable {
    private let scripted: IOSEvaluationScriptedExecutor
    private let workspaceStore: IOSWorkspaceStore

    init(scripted: IOSEvaluationScriptedExecutor, workspaceStore: IOSWorkspaceStore) {
        self.scripted = scripted
        self.workspaceStore = workspaceStore
    }

    var recordedCalls: [IOSEvaluationScriptedExecutor.Call] {
        get async { await scripted.recordedCallsIncludingWorkspace() }
    }

    func execute(tool: String, argsJSON: String) async throws -> String {
        if IOSWorkspaceToolCatalog.supportedToolNames.contains(tool) {
            await scripted.recordCall(tool: tool, argsJSON: argsJSON)
            let output = await workspaceStore.executeTool(toolName: tool, input: argsJSON)
            // 与生产 recipe 路由同一解析器（ChatToolOutputFormatter）——
            // 评测语义与生产语义不允许漂移。
            if let failure = ChatToolOutputFormatter.workspaceFailureReason(inOutputJSON: output) {
                throw NSError(
                    domain: "IOSEvaluationHybridExecutor", code: 2,
                    userInfo: [NSLocalizedDescriptionKey: failure]
                )
            }
            return output
        }
        return try await scripted.execute(tool: tool, argsJSON: argsJSON)
    }
}

extension IOSEvaluationScriptedExecutor {
    /// 记录一次 hybrid executor 代管的 workspace 调用（step-call 契约断言需要
    /// 完整调用序列，含真实执行的 workspace 步骤）。
    func recordCall(tool: String, argsJSON: String) {
        calls.append(Call(tool: tool, argsJSON: argsJSON))
    }

    func recordedCallsIncludingWorkspace() -> [Call] { calls }
}

// MARK: - Evaluator

struct IOSArtifactEvaluator: Sendable {
    static let evaluatorVersion = "amber.ios.artifact.evaluator.v1"

    /// Base directory for the zero-write `IOSRecipeFileStore.prepareRecipe`
    /// canonicalize+hash (invariant 5: the hash is exactly what a later apply
    /// would publish; `prepareRecipe` never writes).
    let recipeStoreBaseDirectory: URL
    /// Host catalog oracle (production: `IOSDynamicToolRegistry.primitiveCatalogEntry`).
    let catalog: IOSRecipeCatalogLookup
    /// REAL ledger the runner writes to (production: `IOSAgentRunLedger`).
    let ledger: any IOSAgentRunLedgering
    /// Real Room DAO the ledger assertions read back through (isolated DB in
    /// tests; the same DB the ledger writes to).
    let dao: AgentRuntimeDao

    init(
        recipeStoreBaseDirectory: URL,
        catalog: @escaping IOSRecipeCatalogLookup,
        ledger: any IOSAgentRunLedgering,
        dao: AgentRuntimeDao
    ) {
        self.recipeStoreBaseDirectory = recipeStoreBaseDirectory
        self.catalog = catalog
        self.ledger = ledger
        self.dao = dao
    }

    /// Evaluates the EXACT candidate bytes against the suite (§13.1: 候选文件、
    /// 评测文件或 live base 任一发生变化 → fail closed). `expectedCandidateHash`
    /// is the hash the caller (manifest) claims for these bytes; a mismatch is
    /// a typed terminal — the evaluator never evaluates bytes that do not hash
    /// to the claimed candidate.
    func evaluate(
        candidateBytes: Data,
        expectedCandidateHash: String,
        suite: IOSEvaluationSuite,
        budget: IOSEvaluationBudget = .standard
    ) async -> IOSEvaluationOutcome {
        // 1. Static tier (§12.2): the FULL real validator on the exact bytes.
        let validation = IOSRecipeValidator.validate(data: candidateBytes, catalog: catalog)
        guard validation.isValid else {
            return .staticRejected(issues: validation.issues)
        }

        // 2. Invariant 5: canonicalize + hash exactly like a later apply would
        //    (zero-write), then fail closed on a claimed-hash mismatch.
        let store = IOSRecipeFileStore(baseDirectory: recipeStoreBaseDirectory)
        let package: IOSRecipePackage
        do {
            package = try store.prepareRecipe(recipeJSON: candidateBytes).candidate
        } catch {
            // Unreachable in practice: the validator already decoded the JSON.
            return .staticRejected(issues: validation.issues)
        }
        guard package.hash == expectedCandidateHash else {
            return .candidateHashMismatch(expected: expectedCandidateHash, actual: package.hash)
        }
        // The runner executes the manifest decoded from the SAME canonical
        // bytes the store hashed (executed bytes == hashed bytes).
        guard let manifest = try? IOSRecipeManifest.decode(package.canonicalJSON) else {
            return .staticRejected(issues: validation.issues)
        }

        // 3. Budgets (§18.3): a non-positive budget is immediately exhausted —
        //    typed terminal, never a silent lowering of the bar. Case-count is
        //    checked before wall-clock so the two degenerate budgets are
        //    deterministic.
        guard budget.maxCaseCount > 0 else {
            return .budgetExhausted(.caseCount(maxCaseCount: budget.maxCaseCount))
        }
        guard budget.maxWallClockSeconds > 0 else {
            return .budgetExhausted(.wallClock(maxWallClockSeconds: budget.maxWallClockSeconds))
        }

        let start = Date()
        var results: [IOSEvaluationCaseResult] = []
        let cases = suite.failureReplayCases + suite.protectedSuccessCases + suite.sealedHoldoutCases
        for caseItem in cases {
            if Date().timeIntervalSince(start) >= budget.maxWallClockSeconds {
                return .budgetExhausted(.wallClock(maxWallClockSeconds: budget.maxWallClockSeconds))
            }
            if results.count >= budget.maxCaseCount {
                return .budgetExhausted(.caseCount(maxCaseCount: budget.maxCaseCount))
            }
            results.append(await evaluateCase(caseItem, manifest: manifest, package: package))
            if Date().timeIntervalSince(start) >= budget.maxWallClockSeconds {
                return .budgetExhausted(.wallClock(maxWallClockSeconds: budget.maxWallClockSeconds))
            }
        }

        // 4. Tier aggregation → immutable report (§9.4).
        let analysis = Self.analyze(results: results, suite: suite)
        let report = IOSEvaluationReport(
            reportId: "eval-report-\(UUID().uuidString)",
            candidateHash: package.hash,
            evaluatorVersion: Self.evaluatorVersion,
            suiteHash: suite.suiteHash,
            results: results,
            protectedRegressions: analysis.protectedRegressions,
            unresolvedRisks: analysis.unresolvedRisks,
            skippedTiers: [.llmJudge, .stochasticRepeat],
            recommendation: analysis.recommendation,
            originRunId: suite.originRunId
        )
        return .report(report)
    }

    // MARK: Case execution (deterministic contract tier)

    private func evaluateCase(
        _ caseItem: IOSEvaluationCase,
        manifest: IOSRecipeManifest,
        package: IOSRecipePackage
    ) async -> IOSEvaluationCaseResult {
        // Slice A：workspace 差分 oracle case 走隔离真实执行路径。
        if let scenario = caseItem.workspaceScenario {
            return await evaluateWorkspaceCase(caseItem, scenario: scenario, manifest: manifest, package: package)
        }
        let runId = "eval-\(package.name)-\(caseItem.id)-\(UUID().uuidString)"
        let executor = IOSEvaluationScriptedExecutor(fixtures: caseItem.scriptedPrimitives)
        let executePrimitive: @MainActor @Sendable (String, String) async throws -> String = { tool, argsJSON in
            try await executor.execute(tool: tool, argsJSON: argsJSON)
        }
        let runner = IOSRecipeRunner(
            manifest: manifest,
            catalog: catalog,
            executePrimitive: executePrimitive,
            ledger: ledger,
            runId: runId
        )
        let outcome = await runner.run(inputs: caseItem.recipeInputs)
        let observed = IOSEvaluationObservedOutcome.from(outcome)
        let calls = await executor.calls
        let rows = await ledgerRows(runId: runId)

        let failureCode = Self.firstFailure(
            caseItem: caseItem, outcome: outcome, observed: observed, calls: calls, rows: rows
        )
        return IOSEvaluationCaseResult(
            caseId: caseItem.id,
            kind: caseItem.kind,
            passed: failureCode == nil,
            observedOutcome: observed,
            failureCode: failureCode
        )
    }

    // MARK: Workspace 差分 case（Slice A）

    /// baseline 与候选分别在内容相同、彼此隔离的全新临时 Workspace 里走真实
    /// `IOSWorkspaceStore` 执行（plan §4 行为契约 2/3/4/5/6）：
    /// - failureReplay：先跑冻结的 baseline bytes——必须在预期位置（同一
    ///   step、同一错误类）复现原失败，否则 oracle 不成立
    ///   （`.baselineFailureNotReproduced`，insufficient-data 级降级）；再跑
    ///   候选——必须成功、满足后置条件、账本 started/finished 成对且成功。
    /// - protected/sealed：无 baseline，候选在单一全新 root 里执行
    ///   （protected 带后置条件；sealed 只有契约级断言）。
    private func evaluateWorkspaceCase(
        _ caseItem: IOSEvaluationCase,
        scenario: IOSEvaluationWorkspaceScenario,
        manifest: IOSRecipeManifest,
        package: IOSRecipePackage
    ) async -> IOSEvaluationCaseResult {
        let evalRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("amber-eval-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: evalRoot) }
        do {
            try FileManager.default.createDirectory(at: evalRoot, withIntermediateDirectories: true)
        } catch {
            return workspaceCaseUnavailable(caseItem, reason: "temp workspace: \(error.localizedDescription)")
        }

        // 1. Baseline 复现（failureReplay 专属）。
        if let baseline = scenario.baseline {
            guard let baselineManifest = try? IOSRecipeManifest.decode(baseline.canonicalJSON),
                  baselineManifest.steps.indices.contains(baseline.failingStepIndex) else {
                return workspaceCaseUnavailable(caseItem, reason: "baseline bytes 不可解码或 step 下标越界")
            }
            let baselineRoot = evalRoot.appendingPathComponent("baseline", isDirectory: true)
            let baselineOutcome = await runWorkspaceRecipe(
                manifest: baselineManifest,
                inputs: caseItem.recipeInputs,
                scripted: caseItem.scriptedPrimitives,
                root: baselineRoot,
                runId: "eval-\(package.name)-\(caseItem.id)-baseline-\(UUID().uuidString)"
            )
            guard case .failed(let failedStep, let error, _) = baselineOutcome else {
                return IOSEvaluationCaseResult(
                    caseId: caseItem.id, kind: caseItem.kind, passed: false,
                    observedOutcome: IOSEvaluationObservedOutcome.from(baselineOutcome),
                    failureCode: .baselineFailureNotReproduced
                )
            }
            let expectedStepId = baselineManifest.steps[baseline.failingStepIndex].id
            guard failedStep == expectedStepId,
                  caseItem.originalFailure == nil
                      || IOSEvaluationObservedOutcome.errorKind(of: error) == caseItem.originalFailure?.errorKind else {
                return IOSEvaluationCaseResult(
                    caseId: caseItem.id, kind: caseItem.kind, passed: false,
                    observedOutcome: IOSEvaluationObservedOutcome.from(baselineOutcome),
                    failureCode: .baselineFailureNotReproduced
                )
            }
        }

        // 2. 候选在全新 root 执行（与 baseline 相同初始状态：空 root + 相同
        //    inputs + 相同 scripted 非 workspace fixture）。
        let candidateRoot = evalRoot.appendingPathComponent("candidate", isDirectory: true)
        let candidateRunId = "eval-\(package.name)-\(caseItem.id)-\(UUID().uuidString)"
        let scriptedExecutor = IOSEvaluationScriptedExecutor(fixtures: caseItem.scriptedPrimitives)
        let candidateOutcome = await runWorkspaceRecipe(
            manifest: manifest,
            inputs: caseItem.recipeInputs,
            scriptedExecutor: scriptedExecutor,
            root: candidateRoot,
            runId: candidateRunId
        )
        let observed = IOSEvaluationObservedOutcome.from(candidateOutcome)
        let calls = await scriptedExecutor.calls
        let rows = await ledgerRows(runId: candidateRunId)

        // 3. 既有确定性契约（outcome/step calls/outputs/ledger 期望）。
        if let failureCode = Self.firstFailure(
            caseItem: caseItem, outcome: candidateOutcome, observed: observed, calls: calls, rows: rows
        ) {
            return IOSEvaluationCaseResult(
                caseId: caseItem.id, kind: caseItem.kind, passed: false,
                observedOutcome: observed, failureCode: failureCode
            )
        }

        // 4. Workspace 后置条件（模板按 case inputs 解析后按真实 store 校验）。
        if let postconditionFailure = await verifyWorkspacePostconditions(
            scenario.postconditions, inputs: caseItem.recipeInputs, root: candidateRoot
        ) {
            return IOSEvaluationCaseResult(
                caseId: caseItem.id, kind: caseItem.kind, passed: false,
                observedOutcome: observed, failureCode: postconditionFailure
            )
        }

        // 5. 账本成对：本 case 的每个 step 都有 Started+Finished 且终态成功
        //    （成功契约下）。outcome 失败时第 3 步已拦截，这里只跑成功路径。
        if case .succeeded = candidateOutcome {
            for step in manifest.steps {
                let suffix = "-\(step.id)"
                let started = rows.contains {
                    $0.type == IOSToolCallLedgerClassifier.startedType && $0.toolCallId.hasSuffix(suffix)
                }
                let finished = rows.contains {
                    $0.type == IOSToolCallLedgerClassifier.finishedType
                        && $0.toolCallId.hasSuffix(suffix) && $0.outcome == "completed"
                }
                guard started, finished else {
                    return IOSEvaluationCaseResult(
                        caseId: caseItem.id, kind: caseItem.kind, passed: false,
                        observedOutcome: observed, failureCode: .ledgerRecordMissing
                    )
                }
            }
        }

        return IOSEvaluationCaseResult(
            caseId: caseItem.id, kind: caseItem.kind, passed: true,
            observedOutcome: observed, failureCode: nil
        )
    }

    /// 用真实 workspace store（root 隔离）+ scripted 其余工具跑一个 recipe。
    private func runWorkspaceRecipe(
        manifest: IOSRecipeManifest,
        inputs: [String: IOSRecipeJSONValue],
        scripted: [String: IOSEvaluationScriptedPrimitive],
        root: URL,
        runId: String
    ) async -> IOSRecipeRunOutcome {
        await runWorkspaceRecipe(
            manifest: manifest, inputs: inputs,
            scriptedExecutor: IOSEvaluationScriptedExecutor(fixtures: scripted),
            root: root, runId: runId
        )
    }

    private func runWorkspaceRecipe(
        manifest: IOSRecipeManifest,
        inputs: [String: IOSRecipeJSONValue],
        scriptedExecutor: IOSEvaluationScriptedExecutor,
        root: URL,
        runId: String
    ) async -> IOSRecipeRunOutcome {
        let store = await MainActor.run { IOSWorkspaceStore(baseDirectory: root) }
        let hybrid = IOSEvaluationHybridExecutor(scripted: scriptedExecutor, workspaceStore: store)
        let executePrimitive: @MainActor @Sendable (String, String) async throws -> String = { tool, argsJSON in
            try await hybrid.execute(tool: tool, argsJSON: argsJSON)
        }
        let runner = IOSRecipeRunner(
            manifest: manifest,
            catalog: catalog,
            executePrimitive: executePrimitive,
            ledger: ledger,
            runId: runId
        )
        return await runner.run(inputs: inputs)
    }

    /// 后置条件校验：模板解析失败 = oracle 不可用（套件缺陷，非候选过错）；
    /// 文件缺失/内容不符 = 后置条件失败（候选未完成任务）。
    private func verifyWorkspacePostconditions(
        _ postconditions: [IOSEvaluationWorkspaceScenario.Postcondition],
        inputs: [String: IOSRecipeJSONValue],
        root: URL
    ) async -> IOSEvaluationCaseFailureCode? {
        guard !postconditions.isEmpty else { return nil }
        let store = await MainActor.run { IOSWorkspaceStore(baseDirectory: root) }
        for postcondition in postconditions {
            switch postcondition {
            case .fileExists(let pathTemplate):
                guard let path = Self.resolveTemplate(pathTemplate, inputs: inputs) else {
                    return .workspaceOracleUnavailable
                }
                if await store.fileRecord(idOrPath: path) == nil {
                    return .workspacePostconditionFailed
                }
            case .fileContentEquals(let pathTemplate, let contentTemplate):
                guard let path = Self.resolveTemplate(pathTemplate, inputs: inputs),
                      let expected = Self.resolveTemplate(contentTemplate, inputs: inputs) else {
                    return .workspaceOracleUnavailable
                }
                guard let record = await store.fileRecord(idOrPath: path) else {
                    return .workspacePostconditionFailed
                }
                let url = await store.fileURL(for: record)
                guard let data = try? Data(contentsOf: url),
                      let text = String(data: data, encoding: .utf8),
                      text == expected else {
                    return .workspacePostconditionFailed
                }
            }
        }
        return nil
    }

    /// `${input.<key>}` 模板解析：只支持字符串输入值（workspace 参数都是
    /// 字符串）；任一占位不可解析 ⇒ nil（调用方按 oracle 不可用降级）。
    private static func resolveTemplate(
        _ template: String,
        inputs: [String: IOSRecipeJSONValue]
    ) -> String? {
        var result = ""
        var rest = Substring(template)
        while let range = rest.range(of: "${input.") {
            result += rest[..<range.lowerBound]
            let afterMarker = rest[range.upperBound...]
            guard let close = afterMarker.firstIndex(of: "}") else { return nil }
            let key = String(afterMarker[..<close])
            guard case .string(let value)? = inputs[key] else { return nil }
            result += value
            rest = afterMarker[afterMarker.index(after: close)...]
        }
        result += rest
        return result
    }

    private func workspaceCaseUnavailable(
        _ caseItem: IOSEvaluationCase,
        reason: String
    ) -> IOSEvaluationCaseResult {
        IOSEvaluationCaseResult(
            caseId: caseItem.id, kind: caseItem.kind, passed: false,
            observedOutcome: IOSEvaluationObservedOutcome(
                didSucceed: false, outputs: nil, failedStepId: nil,
                failedErrorKind: nil, completedStepIds: []
            ),
            failureCode: .workspaceOracleUnavailable
        )
    }

    /// The first violated contract check, in a fixed order (outcome → step
    /// calls → outputs → ledger) so a case fails with a stable, explainable
    /// code instead of an arbitrary one.
    private static func firstFailure(
        caseItem: IOSEvaluationCase,
        outcome: IOSRecipeRunOutcome,
        observed: IOSEvaluationObservedOutcome,
        calls: [IOSEvaluationScriptedExecutor.Call],
        rows: [IOSEvaluationLedgerRow]
    ) -> IOSEvaluationCaseFailureCode? {
        let assertions = caseItem.assertions

        // 1. Outcome contract: success (no expectedError) or the exact
        //    expected error shape (error propagation, §12.2).
        if let expectedError = assertions.expectedError {
            guard case .failed(let failedStepId, let error, _) = outcome,
                  Self.matches(expectedError, stepId: failedStepId, error: error) else {
                return .expectedErrorNotObserved
            }
        } else {
            guard case .succeeded = outcome else {
                return .expectedSuccessButFailed
            }
        }

        // 2. Ordered step-call contract with EXACT resolved arguments
        //    (catches binding errors the static validator cannot see).
        let expectedCalls = assertions.expectedStepCalls
        guard calls.count == expectedCalls.count else { return .stepCallCountMismatch }
        for (index, expected) in expectedCalls.enumerated() {
            guard calls[index].tool == expected.tool else { return .stepCallToolMismatch }
            if let expectedArguments = expected.arguments,
               Self.canonicalArgumentsJSON(expectedArguments) != calls[index].argsJSON {
                return .stepCallArgumentMismatch
            }
        }

        // 3. Output contract (only meaningful when the run succeeded).
        if case .succeeded(let outputs, _) = outcome {
            if let expectedOutputs = assertions.expectedOutputs, expectedOutputs != outputs {
                return .outputsMismatch
            }
        }

        // 4. W1 ledger contract: every expectation resolves to a real
        //    Started+Finished pair for this case's run (§12.2 ledger records).
        for expectation in assertions.expectedLedger {
            let suffix = "-\(expectation.stepId)"
            let finished = rows.first { row in
                row.type == IOSToolCallLedgerClassifier.finishedType
                    && row.toolCallId.hasSuffix(suffix)
                    && row.outcome == expectation.outcome
                    && (expectation.outcomeKind == nil || row.outcomeKind == expectation.outcomeKind)
                    && (expectation.errorCode == nil || row.errorCode == expectation.errorCode)
            }
            guard finished != nil else { return .ledgerRecordMissing }
            let started = rows.contains { row in
                row.type == IOSToolCallLedgerClassifier.startedType && row.toolCallId.hasSuffix(suffix)
            }
            guard started else { return .ledgerStartedMissing }
        }
        return nil
    }

    private static func matches(
        _ expected: IOSEvaluationExpectedError,
        stepId: String?,
        error: IOSRecipeRunError
    ) -> Bool {
        if let expectedStepId = expected.stepId, expectedStepId != stepId { return false }
        switch (expected.kind, error) {
        case (.planInvalid, .planInvalid),
             (.inputInvalid, .inputInvalid),
             (.argumentBinding, .argumentBinding),
             (.stepFailed, .stepFailed),
             (.stepTimeout, .stepTimeout),
             (.outputResolution, .outputResolution):
            return true
        default:
            return false
        }
    }

    // MARK: Tier aggregation

    private struct Analysis: Equatable {
        let protectedRegressions: Int
        let unresolvedRisks: [String]
        let recommendation: IOSEvaluationRecommendation
    }

    /// §12.2 tier semantics + §12.1/§15 Phase 2 stop-condition composition
    /// gates. Order of precedence: hard reject (protected regression, or
    /// failure replay unfixed AND not narrowed) > manual judgment (any
    /// unresolved risk — failed sealed case, narrowed-but-unfixed replay,
    /// missing suite region) > promote (all gates green).
    private static func analyze(
        results: [IOSEvaluationCaseResult],
        suite: IOSEvaluationSuite
    ) -> Analysis {
        var protectedRegressions = 0
        var unresolvedRisks: [String] = []
        var rejectReason: String?

        for result in results {
            switch result.kind {
            case .protectedSuccess:
                // Invariant 13: protected successes cannot regress silently.
                if !result.passed {
                    protectedRegressions += 1
                    rejectReason = rejectReason ?? "protected_success_regression:\(result.caseId)"
                }
            case .failureReplay:
                let scenario = caseScenario(suite: suite, caseId: result.caseId)
                if !result.passed {
                    switch result.failureCode {
                    case .baselineFailureNotReproduced:
                        // Slice A 契约 4：baseline 没在预期位置复现原失败 ⇒
                        // 这个 case 是 insufficient data，不是候选的错。
                        unresolvedRisks.append("baseline_failure_not_reproduced:\(result.caseId)")
                    case .workspaceOracleUnavailable:
                        unresolvedRisks.append("workspace_oracle_unavailable:\(result.caseId)")
                    case .workspacePostconditionFailed:
                        // run 成功但任务后置条件不满足 = 未修复（oracle 证伪，
                        // 硬拒绝，不是 narrowed）。
                        rejectReason = rejectReason ?? "failure_replay_postcondition_failed:\(result.caseId)"
                    default:
                        if scenario == nil {
                            // Slice A / plan §2.2：纯 scripted replay 的 fixture
                            // 对失败 ToolId 固定抛错，无法区分「未修复」与
                            // 「修好了但仍调用同一工具」——不据此 reject；显式
                            // 标注缺少确定性任务 oracle，转人工判断。
                            unresolvedRisks.append("no_deterministic_task_oracle:\(result.caseId)")
                        } else if isNarrowed(result, suite: suite) {
                            unresolvedRisks.append("failure_replay_narrowed_but_not_fixed:\(result.caseId)")
                        } else {
                            rejectReason = rejectReason ?? "failure_replay_not_fixed:\(result.caseId)"
                        }
                    }
                } else if scenario == nil {
                    // 纯 scripted 的 failure replay 即使通过也不能证明真实任务
                    // 完成（fixture 可以永远自洽）——同样只可走人工路径。
                    unresolvedRisks.append("no_deterministic_task_oracle:\(result.caseId)")
                }
            case .sealedHoldout:
                // Feeds the recommendation: a failed sealed case is an
                // unresolved risk, never auto-promotable.
                if !result.passed {
                    unresolvedRisks.append("sealed_holdout_failed:\(result.caseId)")
                }
            }
        }

        // §12.1 / §15 Phase 2 stop condition: without failure replay AND
        // protected success AND sealed holdout there is no auto-promotion.
        if suite.failureReplayCases.isEmpty { unresolvedRisks.append("no_failure_replay_cases") }
        if suite.protectedSuccessCases.isEmpty { unresolvedRisks.append("no_protected_success_cases") }
        if suite.sealedHoldoutCases.isEmpty { unresolvedRisks.append("no_sealed_holdout_cases") }

        let recommendation: IOSEvaluationRecommendation
        if rejectReason != nil {
            recommendation = .reject
        } else if unresolvedRisks.isEmpty {
            recommendation = .promote
        } else {
            recommendation = .manualJudgmentRequired
        }
        return Analysis(
            protectedRegressions: protectedRegressions,
            unresolvedRisks: unresolvedRisks,
            recommendation: recommendation
        )
    }

    /// Slice A：case 的 workspace 差分 oracle（若有）。
    private static func caseScenario(
        suite: IOSEvaluationSuite,
        caseId: String
    ) -> IOSEvaluationWorkspaceScenario? {
        (suite.failureReplayCases + suite.protectedSuccessCases + suite.sealedHoldoutCases)
            .first { $0.id == caseId }?
            .workspaceScenario
    }

    /// v1 deterministic reading of §12.1 "解决或明确缩小失败面": the candidate
    /// NARROWED the failure iff it got strictly further (more completed steps)
    /// before failing. A failure-replay case without `originalFailure` defaults
    /// to "must fix" (not narrowed — fail closed).
    private static func isNarrowed(
        _ result: IOSEvaluationCaseResult,
        suite: IOSEvaluationSuite
    ) -> Bool {
        guard let original = suite.failureReplayCases.first(where: { $0.id == result.caseId })?
            .originalFailure else {
            return false
        }
        return result.observedOutcome.completedStepIds.count > original.completedStepIds.count
    }

    // MARK: Ledger readback (real DAO)

    private func ledgerRows(runId: String) async -> [IOSEvaluationLedgerRow] {
        await withCheckedContinuation { continuation in
            dao.listEventsForRun(id: runId) { result, error in
                guard error == nil, let result else {
                    // A ledger read failure is a contract failure, not a
                    // silent pass: an empty readback fails every ledger
                    // expectation below.
                    continuation.resume(returning: [])
                    return
                }
                continuation.resume(returning: result.compactMap { event in
                    IOSEvaluationLedgerRow.decode(type: event.type, payload: event.payload)
                })
            }
        }
    }

    /// Deterministic JSON rendering of expected arguments — the same
    /// sortedKeys canonical form the runner produces for the recorded call.
    /// nil only on an (unreachable) encoding failure.
    private static func canonicalArgumentsJSON(_ args: [String: IOSRecipeJSONValue]) -> String? {
        let encoder = JSONEncoder.sortedKeysEncoder()
        guard let data = try? encoder.encode(args),
              let text = String(data: data, encoding: .utf8) else {
            return nil
        }
        return text
    }
}

// MARK: - Observed outcome conversion

extension IOSEvaluationObservedOutcome {
    static func from(_ outcome: IOSRecipeRunOutcome) -> IOSEvaluationObservedOutcome {
        switch outcome {
        case .succeeded(let outputs, let completedSteps):
            return IOSEvaluationObservedOutcome(
                didSucceed: true,
                outputs: outputs,
                failedStepId: nil,
                failedErrorKind: nil,
                completedStepIds: completedSteps
            )
        case .failed(let failedStepId, let error, let completedSteps):
            return IOSEvaluationObservedOutcome(
                didSucceed: false,
                outputs: nil,
                failedStepId: failedStepId,
                failedErrorKind: Self.errorKind(of: error),
                completedStepIds: completedSteps
            )
        }
    }

    static func errorKind(of error: IOSRecipeRunError) -> IOSEvaluationErrorKind {
        switch error {
        case .planInvalid: return .planInvalid
        case .inputInvalid: return .inputInvalid
        case .argumentBinding: return .argumentBinding
        case .stepFailed: return .stepFailed
        case .stepTimeout: return .stepTimeout
        case .outputResolution: return .outputResolution
        }
    }
}

import CryptoKit
import Foundation
@preconcurrency import Shared

// MARK: - IOSEvolutionSuiteProvider (Phase 2 收口; §12.1 / §12.2 / §15 Phase 2
// 停止条件 / §18.2 / §9.1)
//
// Host-side evaluation suite construction from REAL run facts. The workflow's
// `suiteProvider` is the ONLY production source of `IOSEvaluationSuite` — with
// it wired, candidates are no longer unconditionally downgraded to manual
// drafts; without it (or when the facts are insufficient) the workflow
// honestly downgrades (§15: 无法构造 failure replay + protected + sealed
// holdout 时不自动发布).
//
// Data ownership (§9.1): evaluation replay goes to the source owners for
// authorized data. This provider reads exactly two owners and nothing else:
//   - the Room ledger (`agent_event` / `agent_run` rows via the DAO) for the
//     run's tool-call sequence, outcomes and terminal status;
//   - the conversation message store (`IOSConversationStore.messages`) for the
//     REAL tool-part inputs/outputs (the ledger only stores args digests, so
//     the message store is the only place real inputs exist).
// It never copies unrelated message bodies into the suite (§18.2: 不复制无关
// 消息正文) — the `dataScopeSummary` the builder produces states exactly which
// runs / tool inputs entered the suite so the workflow UI can show the data
// range (§18.2: 将真实输入加入 evaluation case 前必须显示数据范围).
//
// Case construction (deterministic tiers, §12.2 — the evaluator owns the
// grading semantics, this provider owns faithful transcription):
//   - Failure replay: the failing run's observed step sequence becomes the
//     case contract (exact arguments for the steps that completed, tool-only
//     for the failing step), each primitive is scripted from its real
//     observed output, and the FAILING tool is scripted to throw its observed
//     error (error propagation reproduces when the candidate repeats the
//     failing call). `expectedError` is nil — the fix criterion is "the
//     candidate must SUCCEED in the environment that previously failed";
//     `originalFailure` records the observed failure shape for the evaluator's
//     fix-or-narrow rule. W1 ledger expectations are deliberately NOT written
//     by this provider: the evaluator matches ledger rows by the
//     `-<stepId>` suffix of the RUNNER's toolCallId, which derives from the
//     CANDIDATE's step ids — unknown at suite-construction time. Asserting
//     them would gate promotion on coincidental step-id alignment instead of
//     behavior.
//   - Protected success: a past run with the SAME task shape (same tool
//     sequence + same first-call input keys) and a completed terminal is
//     transcribed the same way with all-success fixtures and key assertions
//     (exact step calls + ledger). No such history → the suite is built
//     WITHOUT the protected region and the evaluator honestly reports
//     `manualJudgmentRequired` (never fabricated history).
//   - Sealed holdout: the failure case's inputs are deterministically
//     perturbed by the host (string values → hash-derived sealed sentinels).
//     No record exists for the perturbed inputs, so assertions are
//     contract-level ONLY (step sequence + ledger shape); the failing tool is
//     scripted with its observed response as a non-throwing output so the
//     sealed region never leaks failure-reproduction into promotion. Sealed
//     content is physically isolated inside `IOSEvaluationSuite` — the
//     proposer view carries refs + hash only (invariant 12).
//
// Typed results (§18.3 预算/停止条件): insufficient facts or fetch failures
// return `.insufficientData` / `.failed` — a half-built suite is never
// returned; the workflow routes those to the manual-draft path with the
// reason.

// MARK: - Typed build result

enum IOSEvolutionSuiteBuildResult: Equatable, Sendable {
    /// The suite was built from real run facts; `dataScopeSummary` states the
    /// data range for §18.2 display (which runs / tool inputs entered).
    case built(suite: IOSEvaluationSuite, dataScopeSummary: String)
    /// The facts do not support a faithful suite (no failure evidence, no
    /// readable messages, no matching success history is NOT this case — that
    /// builds a suite without the protected region instead). Legal output;
    /// the workflow routes to the manual-draft path.
    case insufficientData(String)
    /// The facts contradict each other (failure event cannot be attributed to
    /// the run's sequence). Typed failure; same manual-draft routing.
    case failed(String)
}

// MARK: - Provider

/// Host-side suite constructor. `@MainActor` because the conversation store is
/// MainActor-isolated (its storage serializes read-modify-write on the main
/// thread); the Room DAO reads cross continuations like every other ledger
/// read in this codebase.
@MainActor
struct IOSEvolutionSuiteProvider: Sendable {
    /// Real Room DAO over the ledger (`agent_run` + `agent_event` rows).
    let dao: AgentRuntimeDao
    /// Message store owning the real tool-part inputs/outputs.
    let conversationStore: IOSConversationStore
    /// Recipe store base directory for freezing the baseline recipe bytes
    /// (Slice A workspace differential oracle). Defaults to the documents
    /// directory (same convention as the receipt/policy stores).
    let recipeStoreBaseDirectory: URL

    init(
        dao: AgentRuntimeDao,
        conversationStore: IOSConversationStore,
        recipeStoreBaseDirectory: URL? = nil
    ) {
        self.dao = dao
        self.conversationStore = conversationStore
        self.recipeStoreBaseDirectory = recipeStoreBaseDirectory ?? ((
            try? FileManager.default.url(
                for: .documentDirectory, in: .userDomainMask,
                appropriateFor: nil, create: true
            )
        ) ?? FileManager.default.temporaryDirectory)
    }

    /// Builds the suite from the failure evidence that triggered the workflow
    /// (the evidence ids the hypothesis actually references, falling back to
    /// the first error/denied evidence). Never throws; all failure modes are
    /// typed results.
    func build(
        hypothesis: IOSGapHypothesis,
        evidence: [IOSEvolutionEvidence]
    ) async -> IOSEvolutionSuiteBuildResult {
        guard let failureEvidence = Self.failureEvidence(in: evidence, referencedBy: hypothesis) else {
            return .insufficientData("没有可归因的失败证据（需要 error/denied 信号且带 run 引用；I-3）。")
        }

        // 1. Run facts from the ledger (source owner 1).
        guard let run = await readRun(runId: failureEvidence.runId) else {
            return .insufficientData("失败 run「\(failureEvidence.runId)」的 agent_run 记录不可读。")
        }
        let rows = await readLedgerRows(runId: failureEvidence.runId)
        guard !rows.isEmpty else {
            return .insufficientData("失败 run「\(failureEvidence.runId)」没有账本事件，无法重建任务形状。")
        }
        let sequence = Self.observedSequence(rows: rows)
        guard sequence.count >= 1 else {
            return .insufficientData("失败 run「\(failureEvidence.runId)」的工具调用序列不可重建。")
        }

        // 2. Attribute the failure to a step. Terminal evidence (agentRun ref)
        // has no failing call — every observed call completed and the RUN
        // failed afterwards; tool-error/denial evidence points at its finished
        // ledger event.
        let failingIndex: Int?
        if let eventRef = failureEvidence.sourceRefs.first(where: { $0.kind == .ledgerEvent }) {
            guard let index = sequence.firstIndex(where: { $0.finishedEventId == eventRef.id }) else {
                return .failed("失败事件「\(eventRef.id)」无法归位到 run「\(failureEvidence.runId)」的工具序列（账本与证据不一致）。")
            }
            failingIndex = index
        } else {
            failingIndex = nil
        }
        let failingCall = failingIndex.map { sequence[$0] }

        // Slice A recipe-run framing：首个观察调用是 `recipe__*` 时它是容器
        // （模型对 recipe 工具的调用行），不是 step——case 的 step 序列 /
        // fixture / originalFailure 全部只用内层序列，recipe inputs 取容器
        // 调用的输入。证据行若指向容器（recipe 级失败），重新归因到携带
        // provenance 的内层错误 step；没有内层错误行 ⇒ 终端形状（step 前
        // 失败，originalFailure 用容器 errorCode）。
        let container: ObservedCall? = sequence.first.flatMap { first in
            first.toolName.hasPrefix("recipe__") ? first : nil
        }
        let stepSequence = container == nil ? sequence : Array(sequence.dropFirst())
        let stepFailingIndex: Int? = {
            if let failingCall, container == nil || failingCall.toolCallId != container!.toolCallId {
                return stepSequence.firstIndex(where: { $0.toolCallId == failingCall.toolCallId })
            }
            // 证据指向容器行（recipe 级失败）或根本没有 step 级归因（终端
            // 证据）：归因到携带 provenance 的内层错误 step；没有内层错误行
            // ⇒ nil（终端/step 前失败形状）。
            guard let container else { return nil }
            return stepSequence.lastIndex(where: {
                ($0.outcomeKind == "error" || $0.outcome == "failed")
                    && $0.artifactId == container.toolName
            })
        }()
        let stepFailingCall = stepFailingIndex.map { stepSequence[$0] }

        let completedCalls = stepSequence.enumerated().compactMap { index, call in
            index < (stepFailingIndex ?? stepSequence.count) ? call : nil
        }
        // Pre-failure steps must be plain successes to be transcribed
        // faithfully; a denied/interrupted/unresolved call has no deterministic
        // fixture (fail closed instead of inventing one).
        for call in completedCalls where call.outcome != "completed" || call.outcomeKind == "denied" {
            return .insufficientData(
                "run「\(failureEvidence.runId)」失败前的调用「\(call.toolName)」不是成功终态（\(call.outcome ?? "unfinished")），无法忠实转成 scripted fixture。"
            )
        }

        // 3. Real inputs/outputs from the message store (source owner 2).
        guard let conversationIdHex = run.conversationId,
              let uuid = Self.parseConversationId(conversationIdHex) else {
            return .insufficientData("失败 run「\(failureEvidence.runId)」没有可读的会话归属，无法取回真实工具输入。")
        }
        guard let messages = await conversationStore.messages(for: uuid) else {
            return .insufficientData("失败 run「\(failureEvidence.runId)」所属会话「\(conversationIdHex)」的消息不可读，无法取回真实工具输入。")
        }
        let toolParts = Self.toolPartsByCallId(in: messages)
        var resolved: [ObservedCall] = []
        for call in stepSequence {
            guard let part = toolParts[call.toolCallId] else {
                return .insufficientData(
                    "run「\(failureEvidence.runId)」的调用「\(call.toolName)(\(call.toolCallId))」在会话中没有持久化 tool part，无法取回真实输入/输出。"
                )
            }
            resolved.append(call.with(inputJSON: part.input, outputText: part.outputText))
        }
        // 容器调用也要取回真实输入（recipe inputs 的唯一来源）。
        let resolvedContainer: ObservedCall? = {
            guard let container else { return nil }
            guard let part = toolParts[container.toolCallId] else { return nil }
            return container.with(inputJSON: part.input, outputText: part.outputText)
        }()
        if container != nil && resolvedContainer == nil {
            return .insufficientData("失败 run「\(failureEvidence.runId)」的 recipe 容器调用没有持久化 tool part，无法取回真实 recipe 输入。")
        }
        guard let firstCall = resolvedContainer ?? resolved.first,
              let recipeInputs = Self.parseInputObject(firstCall.inputJSON) else {
            return .insufficientData("失败 run「\(failureEvidence.runId)」首个工具调用的输入不可解析，无法构成 case 输入。")
        }

        // 4. Protected success history: same task shape, completed terminal.
        let shapeSource = container.map { [$0] + resolved } ?? resolved
        let protected = await findProtectedRun(
            shape: shapeSource.map(\.toolName),
            inputKeys: Set(recipeInputs.keys),
            preferredConversation: conversationIdHex
        )

        // 5. Slice A workspace 差分 oracle：只为「失败 run 是 recipe run 且失败
        //    step 是 workspace 本地读写 primitive 的参数/binding/路径/schema
        //    错误」构造；冻结 baseline exact bytes（版本漂移 ⇒ 无 oracle，
        //    evaluator 会显式标注 no_deterministic_task_oracle）。分类成功时
        //    同时拿回 baseline manifest 的真实 step id——originalFailure 的
        //    failedStepId/completedStepIds 要喂 evaluator 的「未修复 vs 缩小」
        //    对比，用合成 stepN 会把同一失败面误判成 narrowed。
        let classified: (scenario: IOSEvaluationWorkspaceScenario, stepIds: [String])? = {
            guard let stepFailingCall, let stepFailingIndex else { return nil }
            return workspaceScenario(
                failingCall: stepFailingCall,
                failingStepIndex: stepFailingIndex,
                recipeInputs: recipeInputs
            )
        }()
        let replayScenario = classified?.scenario

        // 6. Assemble the three cases.
        let replay = Self.failureReplayCase(
            runId: failureEvidence.runId,
            calls: resolved,
            failingIndex: stepFailingIndex,
            failingCall: stepFailingCall,
            containerFailure: container != nil && stepFailingCall == nil ? failingCall : nil,
            recipeInputs: recipeInputs,
            evidenceOutcome: failureEvidence.observedOutcome,
            workspaceScenario: replayScenario,
            manifestStepIds: classified?.stepIds
        )
        let protectedCase = protected.map { runFacts -> IOSEvaluationCase in
            // protected run 若是同一 recipe 的运行，同样有容器框架行。
            let inner = runFacts.calls.filter { !$0.toolName.hasPrefix("recipe__") }
            let inputs = runFacts.calls.first?.inputObject ?? [:]
            return Self.protectedSuccessCase(
                runId: runFacts.runId,
                calls: inner,
                recipeInputs: inputs,
                workspaceScenario: Self.protectedWorkspaceScenario(calls: inner, inputs: inputs)
            )
        }
        // sealed 使用 protected 的同一份参数化任务后置条件，但换成隐藏输入。
        // 这样候选不能靠硬编码 protected 样本通过；sealed 的具体输入仍不进入
        // proposer view。
        let sealed = Self.sealedHoldoutCase(
            runId: failureEvidence.runId,
            calls: resolved,
            failingIndex: stepFailingIndex,
            failureInputs: recipeInputs,
            workspaceScenario: replayScenario.map { _ in
                IOSEvaluationWorkspaceScenario(
                    baseline: nil,
                    postconditions: protectedCase?.workspaceScenario?.postconditions ?? []
                )
            }
        )

        let suite = IOSEvaluationSuite(
            suiteId: "suite-\(hypothesis.id)",
            failureReplayCases: [replay],
            protectedSuccessCases: protectedCase.map { [$0] } ?? [],
            sealedHoldoutCases: [sealed],
            // Slice B（B1）：套件绑定触发流程的失败 run 身份；evaluator 拷贝
            // 进 report，发布闸用同一 originRunId 校验。
            originRunId: failureEvidence.runId
        )
        let scope = Self.dataScopeSummary(
            failureRunId: failureEvidence.runId,
            failureConversationHex: conversationIdHex,
            failureCalls: shapeSource,
            protectedRun: protected.map { ($0.runId, $0.calls) },
            replayScenario: replayScenario
        )
        return .built(suite: suite, dataScopeSummary: scope)
    }

    // MARK: Workspace 差分 oracle 构造（Slice A）

    /// 分类与冻结（plan §4 目标/契约 1）：只接受「recipe run 内、workspace
    /// 本地读写 step、参数/binding/路径/schema 错误类（argument_binding /
    /// step_failed）、outcomeKind=error」的失败；denied/timeout/终端
    /// outputResolution 与网络/MCP primitive 一律不构造（无确定性 oracle）。
    /// baseline = recipe store 里同 name 且版本与失败 run 账本一致的 exact
    /// canonical bytes；store 已演进（版本漂移）⇒ nil（套件退化为纯
    /// scripted，evaluator 标 no_deterministic_task_oracle，永不自动晋升）。
    private func workspaceScenario(
        failingCall: ObservedCall,
        failingStepIndex: Int,
        recipeInputs: [String: IOSRecipeJSONValue]
    ) -> (scenario: IOSEvaluationWorkspaceScenario, stepIds: [String])? {
        guard failingCall.outcomeKind == "error",
              let errorCode = failingCall.errorCode,
              ["argument_binding", "step_failed"].contains(errorCode),
              failingCall.toolName == "workspace_file_write",
              let artifactId = failingCall.artifactId, artifactId.hasPrefix("recipe__"),
              let version = failingCall.artifactVersion else {
            return nil
        }
        let name = String(artifactId.dropFirst("recipe__".count))
        let store = IOSRecipeFileStore(baseDirectory: recipeStoreBaseDirectory)
        guard let package = try? store.readLiveRecipe(name: name),
              let manifest = try? IOSRecipeManifest.decode(package.canonicalJSON),
              manifest.version == version,
              manifest.steps.indices.contains(failingStepIndex),
              manifest.steps[failingStepIndex].tool == failingCall.toolName else {
            return nil
        }
        return (
            IOSEvaluationWorkspaceScenario(
                baseline: IOSEvaluationWorkspaceScenario.Baseline(
                    artifactId: artifactId,
                    version: version,
                    canonicalJSON: package.canonicalJSON,
                    failingStepIndex: failingStepIndex
                ),
                postconditions: Self.derivedPostconditions(manifest: manifest, inputs: recipeInputs)
            ),
            manifest.steps.map(\.id)
        )
    }

    /// 从 baseline manifest 静态派生的后置条件（plan §4：只允许代码能确定
    /// 验证的事实）：每个 workspace_file_write step 的 path/content 模板，
    /// 字面量直接采用、`${input.x}` 绑定只对真实存在的字符串输入派生；
    /// stepOutput 绑定静态不可验证，跳过（宁可少一条后置条件，不发明事实）。
    nonisolated static func derivedPostconditions(
        manifest: IOSRecipeManifest,
        inputs: [String: IOSRecipeJSONValue]
    ) -> [IOSEvaluationWorkspaceScenario.Postcondition] {
        var postconditions: [IOSEvaluationWorkspaceScenario.Postcondition] = []
        for step in manifest.steps where step.tool == "workspace_file_write" {
            guard let pathValue = step.arguments["path"],
                  let pathTemplate = postconditionTemplate(for: pathValue, inputs: inputs) else { continue }
            postconditions.append(.fileExists(pathTemplate: pathTemplate))
            if let contentValue = step.arguments["content"],
               let contentTemplate = postconditionTemplate(for: contentValue, inputs: inputs) {
                postconditions.append(.fileContentEquals(
                    pathTemplate: pathTemplate, contentTemplate: contentTemplate
                ))
            }
        }
        return postconditions
    }

    /// 后置条件模板：字面字符串直接采用；input 绑定保留 `${input.<key>}` 模板
    /// （评测时按 case inputs 解析）——仅当该输入真实存在且为字符串。
    nonisolated private static func postconditionTemplate(
        for value: IOSRecipeValue,
        inputs: [String: IOSRecipeJSONValue]
    ) -> String? {
        switch value {
        case .literal(.string(let text)):
            return text
        case .binding(let binding):
            if case .input(let name) = binding.source, case .string = inputs[name] {
                return "${input.\(name)}"
            }
            return nil
        default:
            return nil
        }
    }

    /// protected run 的真实执行后置条件：观察到的 workspace_file_write 调用
    /// 带着运行时已解析的字面参数（path/content 都是确定值），直接转成
    /// fileContentEquals / fileExists。无 workspace 写入 ⇒ nil（纯 scripted
    /// protected case 保持原样）。
    nonisolated private static func protectedWorkspaceScenario(
        calls: [ObservedCall],
        inputs: [String: IOSRecipeJSONValue]
    ) -> IOSEvaluationWorkspaceScenario? {
        var postconditions: [IOSEvaluationWorkspaceScenario.Postcondition] = []
        for call in calls where call.toolName == "workspace_file_write" {
            guard let args = call.inputObject,
                  case .string(let path)? = args["path"] else { continue }
            let pathTemplate = observedTemplate(path, inputs: inputs)
            if case .string(let content)? = args["content"] {
                postconditions.append(.fileContentEquals(
                    pathTemplate: pathTemplate,
                    contentTemplate: observedTemplate(content, inputs: inputs)
                ))
            } else {
                postconditions.append(.fileExists(pathTemplate: pathTemplate))
            }
        }
        return postconditions.isEmpty
            ? nil
            : IOSEvaluationWorkspaceScenario(baseline: nil, postconditions: postconditions)
    }

    /// 历史成功调用只保存解析后的参数。值与唯一一个输入完全相等时，保留为
    /// input 模板供 sealed 换值复验；有歧义或不是输入值时保持字面量。
    nonisolated private static func observedTemplate(
        _ value: String,
        inputs: [String: IOSRecipeJSONValue]
    ) -> String {
        let matches = inputs.compactMap { key, input -> String? in
            guard case .string(let text) = input, text == value else { return nil }
            return key
        }
        guard matches.count == 1, let key = matches.first else { return value }
        return "${input.\(key)}"
    }

    // MARK: Failure replay case (§12.1)

    /// The failing run's observed sequence, scripted from the real records.
    /// The failing tool throws its observed error (error propagation
    /// reproduces for a candidate that repeats the call); `expectedError` is
    /// nil so the fix criterion is success, and `originalFailure` carries the
    /// observed shape for the evaluator's fix-or-narrow rule.
    private static func failureReplayCase(
        runId: String,
        calls: [ObservedCall],
        failingIndex: Int?,
        failingCall: ObservedCall?,
        containerFailure: ObservedCall?,
        recipeInputs: [String: IOSRecipeJSONValue],
        evidenceOutcome: IOSOutcomeKind,
        workspaceScenario: IOSEvaluationWorkspaceScenario?,
        manifestStepIds: [String]? = nil
    ) -> IOSEvaluationCase {
        let completedCount = failingIndex ?? calls.count
        let fixtures = scriptedPrimitives(
            calls: calls,
            failingIndex: failingIndex,
            failingCall: failingCall
        )
        // oracle-backed recipe run 用 baseline manifest 的真实 step id（喂
        // evaluator 的「未修复 vs 缩小」对比）；raw 调用序列保持合成 stepN。
        let stepIdAt: (Int) -> String = { index in
            if let manifestStepIds, index < manifestStepIds.count {
                return manifestStepIds[index]
            }
            return "step\(index + 1)"
        }
        let expectedSteps = calls.enumerated().map { index, call in
            IOSEvaluationExpectedStepCall(
                stepId: stepIdAt(index),
                tool: call.toolName,
                // The failing step is asserted tool-only: the candidate may
                // legitimately change THAT call's arguments as part of a fix.
                arguments: index == failingIndex ? nil : call.inputObject
            )
        }
        // NO `expectedLedger` in provider-built cases: the evaluator matches W1
        // rows by the `-<stepId>` suffix of the RUNNER's toolCallId, and the
        // runner derives that id from the CANDIDATE's step ids — which are
        // unknown at suite-construction time (the suite exists before the
        // model drafts). Asserting ledger rows here would gate promotion on
        // coincidental step-id alignment instead of behavior.
        //
        // errorKind：step 级失败按账本 errorCode 映射（baseline 复现校验要
        // 对比同一错误类）；容器级失败（recipe 调用在进入 step 前失败）按容器
        // errorCode；终端失败保持 outputResolution 形状。
        let errorKind: IOSEvaluationErrorKind
        if let failingCall {
            errorKind = errorKindForErrorCode(failingCall.errorCode)
        } else if let containerFailure {
            errorKind = errorKindForErrorCode(containerFailure.errorCode)
        } else {
            errorKind = .outputResolution
        }
        return IOSEvaluationCase(
            id: "replay-\(runId)",
            kind: .failureReplay,
            recipeInputs: recipeInputs,
            scriptedPrimitives: fixtures,
            assertions: IOSEvaluationAssertions(
                expectedOutputs: nil,
                expectedError: nil,
                expectedStepCalls: expectedSteps,
                expectedLedger: []
            ),
            originalFailure: IOSEvaluationOriginalFailure(
                failedStepId: failingIndex.map { stepIdAt($0) },
                errorKind: errorKind,
                completedStepIds: calls.prefix(completedCount).indices.map { stepIdAt($0) }
            ),
            workspaceScenario: workspaceScenario
        )
    }

    /// 账本 errorCode → 评测错误类（runner 的稳定键，见
    /// `IOSRecipeRunner.errorCode(for:)`）；未知/缺失按 stepFailed。
    nonisolated private static func errorKindForErrorCode(_ errorCode: String?) -> IOSEvaluationErrorKind {
        switch errorCode {
        case "plan_invalid": return .planInvalid
        case "input_invalid": return .inputInvalid
        case "argument_binding": return .argumentBinding
        case "step_timeout": return .stepTimeout
        case "output_resolution": return .outputResolution
        default: return .stepFailed
        }
    }

    // MARK: Protected success case (§12.1)

    /// A past successful run with the SAME task shape, transcribed with
    /// all-success fixtures and key assertions (exact step calls; no ledger
    /// expectations — see `failureReplayCase` for the candidate-owned step-id
    /// reason).
    private static func protectedSuccessCase(
        runId: String,
        calls: [ObservedCall],
        recipeInputs: [String: IOSRecipeJSONValue],
        workspaceScenario: IOSEvaluationWorkspaceScenario?
    ) -> IOSEvaluationCase {
        let fixtures = scriptedPrimitives(calls: calls, failingIndex: nil, failingCall: nil)
        let expectedSteps = calls.enumerated().map { index, call in
            IOSEvaluationExpectedStepCall(
                stepId: "step\(index + 1)",
                tool: call.toolName,
                arguments: call.inputObject
            )
        }
        return IOSEvaluationCase(
            id: "protected-\(runId)",
            kind: .protectedSuccess,
            recipeInputs: recipeInputs,
            scriptedPrimitives: fixtures,
            assertions: IOSEvaluationAssertions(
                expectedOutputs: nil,
                expectedError: nil,
                expectedStepCalls: expectedSteps,
                expectedLedger: []
            ),
            originalFailure: nil,
            workspaceScenario: workspaceScenario
        )
    }

    // MARK: Sealed holdout case (§12.1 / §12.3 / §18.2)

    /// Host-side deterministic perturbation of the failure case's inputs
    /// (string values → `sealed-` + SHA-256 prefix; numbers +1; booleans
    /// toggled — null/array/object pass through unchanged, real tool-call
    /// inputs are flat). No record exists for the perturbed inputs, so the
    /// assertions are contract-level ONLY: the same step sequence (tool-only,
    /// no exact arguments) and the same ledger shape; the failing tool is
    /// scripted with its observed response as a NON-throwing output — the
    /// sealed region gates generalization on unseen inputs, it never leaks
    /// failure-reproduction into the promotion path. Content is physically
    /// sealed inside `IOSEvaluationSuite` (invariant 12).
    private static func sealedHoldoutCase(
        runId: String,
        calls: [ObservedCall],
        failingIndex: Int?,
        failureInputs: [String: IOSRecipeJSONValue],
        workspaceScenario: IOSEvaluationWorkspaceScenario?
    ) -> IOSEvaluationCase {
        var fixtures = scriptedPrimitives(calls: calls, failingIndex: failingIndex, failingCall: nil)
        if let failingIndex, calls.indices.contains(failingIndex) {
            // The failing tool responds with its observed bytes instead of
            // throwing (no exact outputs are asserted here — 无记录可取).
            let observed = calls[failingIndex].outputText
            fixtures[calls[failingIndex].toolName] = IOSEvaluationScriptedPrimitive(
                outputJSON: (observed?.isEmpty == false) ? observed : "{}",
                failureMessage: nil,
                delaySeconds: nil
            )
        }
        let expectedSteps = calls.enumerated().map { index, call in
            IOSEvaluationExpectedStepCall(
                stepId: "step\(index + 1)", tool: call.toolName, arguments: nil
            )
        }
        return IOSEvaluationCase(
            id: "sealed-\(runId)",
            kind: .sealedHoldout,
            recipeInputs: Self.perturbedInputs(failureInputs),
            scriptedPrimitives: fixtures,
            assertions: IOSEvaluationAssertions(
                expectedOutputs: nil,
                expectedError: nil,
                expectedStepCalls: expectedSteps,
                expectedLedger: []
            ),
            originalFailure: nil,
            workspaceScenario: workspaceScenario
        )
    }

    /// Per-tool scripted fixtures from the observed records. The LAST observed
    /// occurrence of a tool wins (deterministic); the failing tool throws its
    /// observed error when `failingCall` is provided.
    private static func scriptedPrimitives(
        calls: [ObservedCall],
        failingIndex: Int?,
        failingCall: ObservedCall?
    ) -> [String: IOSEvaluationScriptedPrimitive] {
        var fixtures: [String: IOSEvaluationScriptedPrimitive] = [:]
        for (index, call) in calls.enumerated() {
            if let failingCall, index == failingIndex, failingCall.toolCallId == call.toolCallId {
                fixtures[call.toolName] = IOSEvaluationScriptedPrimitive(
                    outputJSON: nil,
                    failureMessage: Self.failureMessage(for: call),
                    delaySeconds: nil
                )
            } else {
                let output = call.outputText
                fixtures[call.toolName] = IOSEvaluationScriptedPrimitive(
                    outputJSON: (output?.isEmpty == false) ? output : "{}",
                    failureMessage: nil,
                    delaySeconds: nil
                )
            }
        }
        return fixtures
    }

    /// The failing call's observed error text — the message store's tool part
    /// output (the failure JSON / error text the user actually saw). Falls
    /// back to the ledger's structured code when the output is empty.
    private static func failureMessage(for call: ObservedCall) -> String {
        if let outputText = call.outputText, !outputText.isEmpty {
            return outputText
        }
        if let errorCode = call.errorCode, !errorCode.isEmpty {
            return "observed tool failure (errorCode=\(errorCode))"
        }
        return "observed tool failure (outcome=\(call.outcome ?? "unknown"))"
    }

    // MARK: Protected history lookup

    /// `findProtectedRun` 的候选扫描上限（最近 K 条 completed run，§18.3 预算
    /// 精神）。每个候选都要读账本、匹配候选还要读消息，全量扫描随 run 数
    /// 增长是无界开销；K 覆盖真实回看窗口——一次失败诊断只需要最近的同形状
    /// 成功样例，超出窗口的旧样例不纳入本回自动套件。K 是常量不是设置项：
    /// 不需要用户调节，也没有证据表明更大的窗口能改善套件质量。
    static let maximumProtectedRunCandidates = 40

    /// A past run with the same task shape: terminal `completed`, the SAME
    /// tool sequence (names in order), the same first-call input KEY SET (the
    /// shared input schema) and every call a plain success. Prefers the same
    /// conversation, then the most recent match anywhere. A run whose
    /// messages are unreadable is skipped (try the next). nil → the suite is
    /// built without a protected region (evaluator downgrades honestly).
    ///
    /// 扫描有界：先按时间取最近 `maximumProtectedRunCandidates` 条 completed
    /// run（窗口），再在窗口内按「同会话优先、其次最近」排序。
    private func findProtectedRun(
        shape: [String],
        inputKeys: Set<String>,
        preferredConversation: String
    ) async -> (runId: String, calls: [ObservedCall])? {
        let runs = await readAllRuns()
        let recentWindow = runs
            .filter { $0.status == "completed" && $0.conversationId != nil }
            .sorted { $0.startedAt > $1.startedAt }
            .prefix(Self.maximumProtectedRunCandidates)
        let candidates = recentWindow.sorted {
            if ($0.conversationId == preferredConversation) != ($1.conversationId == preferredConversation) {
                return $0.conversationId == preferredConversation
            }
            return $0.startedAt > $1.startedAt
        }
        for candidate in candidates {
            let runId = candidate.runId
            let rows = await readLedgerRows(runId: runId)
            let sequence = Self.observedSequence(rows: rows)
            guard sequence.count == shape.count else { continue }
            guard zip(sequence, shape).allSatisfy({ $0.toolName == $1 }) else { continue }
            guard sequence.allSatisfy({ $0.outcome == "completed" && $0.outcomeKind != "denied" }) else { continue }
            guard let conversationId = candidate.conversationId,
                  let uuid = Self.parseConversationId(conversationId),
                  let messages = await conversationStore.messages(for: uuid) else { continue }
            let toolParts = Self.toolPartsByCallId(in: messages)
            var resolved: [ObservedCall] = []
            for call in sequence {
                guard let part = toolParts[call.toolCallId] else { break }
                resolved.append(call.with(inputJSON: part.input, outputText: part.outputText))
            }
            guard resolved.count == sequence.count,
                  let firstInput = Self.parseInputObject(resolved.first?.inputJSON),
                  Set(firstInput.keys) == inputKeys else { continue }
            return (runId: runId, calls: resolved)
        }
        return nil
    }

    // MARK: Data scope summary (§18.2)

    /// States which runs / tool inputs entered the suite. Run ids, tool names
    /// and input FIELD NAMES only — never message bodies, never input values
    /// (§18.2: 不复制无关会话正文；显示数据范围后再用真实输入).
    private static func dataScopeSummary(
        failureRunId: String,
        failureConversationHex: String,
        failureCalls: [ObservedCall],
        protectedRun: (runId: String, calls: [ObservedCall])?,
        replayScenario: IOSEvaluationWorkspaceScenario? = nil
    ) -> String {
        let conversationTag = String(failureConversationHex.prefix(8))
        let tools = failureCalls.map(\.toolName).joined(separator: ", ")
        let inputKeys: String?
        if let firstCall = failureCalls.first, let firstInput = firstCall.inputObject {
            inputKeys = firstInput.keys.sorted().joined(separator: ", ")
        } else {
            inputKeys = nil
        }
        var lines = [
            "failure replay / sealed holdout 使用失败 run `\(failureRunId)`（会话 \(conversationTag)…）的 \(failureCalls.count) 次真实工具调用（\(tools)）与真实输入（字段：\(inputKeys ?? "无")）；sealed holdout 输入为 host 确定性扰动，不含真实数据。"
        ]
        if let protectedRun {
            let pTools = protectedRun.calls.map(\.toolName).joined(separator: ", ")
            lines.append("protected success 使用成功 run `\(protectedRun.runId)`（同一任务形状，\(protectedRun.calls.count) 次调用：\(pTools)）的真实记录。")
        } else {
            lines.append("protected success：没有找到同任务形状的成功历史，本套件缺 protected 区（评测将按 §12.1 降级为人工判断）。")
        }
        if let replayScenario, let baseline = replayScenario.baseline {
            lines.append("workspace 差分 oracle：冻结 baseline `\(baseline.artifactId)@\(baseline.version)` 的 exact bytes；baseline 与候选分别在全新隔离临时 Workspace 中真实执行本地读写，后置条件 \(replayScenario.postconditions.count) 条；不使用真实 Workspace 数据。")
        } else {
            lines.append("本套件无确定性任务 oracle（非 workspace 可隔离失败或 baseline 已漂移）：候选只走草稿 + 人工判断路径，不进入自动晋升。")
        }
        return lines.joined(separator: "\n")
    }

    // MARK: Evidence selection

    /// The failure evidence driving the suite: the first error/denied evidence
    /// the hypothesis references (falling back to the first error/denied
    /// evidence overall). Success-only evidence never builds a suite.
    private static func failureEvidence(
        in evidence: [IOSEvolutionEvidence],
        referencedBy hypothesis: IOSGapHypothesis
    ) -> IOSEvolutionEvidence? {
        let isFailure = { (item: IOSEvolutionEvidence) -> Bool in
            item.observedOutcome == .error || item.observedOutcome == .denied
        }
        let referenced = evidence.filter { hypothesis.evidenceIds.contains($0.id) }
        if let primary = referenced.first(where: isFailure) {
            return primary
        }
        return evidence.first(where: isFailure)
    }

    // MARK: Ledger reads (source owner 1)

    private struct RunSnapshot: Sendable {
        let runId: String
        let conversationId: String?
        let status: String
        let startedAt: Int64
    }

    private func readRun(runId: String) async -> RunSnapshot? {
        await withCheckedContinuation { (continuation: CheckedContinuation<RunSnapshot?, Never>) in
            dao.getRun(id: runId) { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: RunSnapshot(
                    runId: result.runId,
                    conversationId: result.conversationId,
                    status: result.status,
                    startedAt: result.startedAt
                ))
            }
        }
    }

    private func readAllRuns() async -> [RunSnapshot] {
        await withCheckedContinuation { (continuation: CheckedContinuation<[RunSnapshot], Never>) in
            dao.listAllRuns { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: [])
                    return
                }
                continuation.resume(returning: result.map {
                    RunSnapshot(
                        runId: $0.runId,
                        conversationId: $0.conversationId,
                        status: $0.status,
                        startedAt: $0.startedAt
                    )
                })
            }
        }
    }

    /// One decoded `agent_event` row reduced to what suite construction needs.
    /// Rows without a recoverable `toolCallId` are dropped (same fail-closed
    /// rule as `IOSToolCallLedgerRow.decode`). Internal because
    /// `observedSequence(rows:)` is a pure internal helper (used by the
    /// drift-regression tests).
    struct LedgerRow: Sendable {
        let eventId: String
        let type: String
        let seq: Int64
        let toolCallId: String
        let toolName: String?
        let outcome: String?
        let outcomeKind: String?
        let errorCode: String?
        /// Finished rows written by the recipe runner carry the executing
        /// recipe's identity (Phase 0 payload keys) — Slice A uses them to
        /// freeze the baseline recipe version for the workspace oracle.
        let artifactId: String?
        let artifactVersion: String?

        static func decode(_ event: AgentEventEntity) -> LedgerRow? {
            guard let data = event.payload.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let toolCallId = (object["toolCallId"] as? String)?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                  !toolCallId.isEmpty else {
                return nil
            }
            return LedgerRow(
                eventId: event.eventId,
                type: event.type,
                seq: event.seq,
                toolCallId: toolCallId,
                toolName: object["toolName"] as? String,
                outcome: object["outcome"] as? String,
                outcomeKind: object["outcomeKind"] as? String,
                errorCode: object["errorCode"] as? String,
                artifactId: object["artifactId"] as? String,
                artifactVersion: object["artifactVersion"] as? String
            )
        }
    }

    private func readLedgerRows(runId: String) async -> [LedgerRow] {
        await withCheckedContinuation { (continuation: CheckedContinuation<[LedgerRow], Never>) in
            dao.listEventsForRun(id: runId) { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: [])
                    return
                }
                continuation.resume(returning: result.compactMap(LedgerRow.decode))
            }
        }
    }

    /// The run's tool-call sequence in call order: one entry per started row,
    /// paired (by toolCallId) with the LAST finished row after it — the same
    /// pairing the W3 recovery classifier uses. A started row without a
    /// finished row keeps its place with nil outcome fields (an unresolved
    /// call is still part of the observed sequence). Pure; nonisolated so the
    /// drift-regression tests can call it without actor hops.
    nonisolated static func observedSequence(rows: [LedgerRow]) -> [ObservedCall] {
        let started = rows.filter { $0.type == IOSEvolutionEvidenceProjector.toolStartedEventType }
        let finished = rows.filter { $0.type == IOSEvolutionEvidenceProjector.toolFinishedEventType }
        return started.sorted { $0.seq < $1.seq }.map { row in
            let finish = finished
                .filter { $0.toolCallId == row.toolCallId && $0.seq > row.seq }
                .max { $0.seq < $1.seq }
            return ObservedCall(
                toolCallId: row.toolCallId,
                toolName: row.toolName ?? "unknown",
                seq: row.seq,
                inputJSON: nil,
                outputText: nil,
                outcome: finish?.outcome,
                outcomeKind: finish?.outcomeKind,
                errorCode: finish?.errorCode,
                finishedEventId: finish?.eventId,
                artifactId: finish?.artifactId,
                artifactVersion: finish?.artifactVersion
            )
        }
    }

    // MARK: Message store reads (source owner 2)

    /// The message store's real tool parts keyed by toolCallId. The persisted
    /// output is joined from the tool part's Text parts (the same way
    /// `ChatToolOutputFormatter.failureReason` reads it) — raw output bytes,
    /// not a re-encoded digest, so the scripted fixture matches the record.
    nonisolated static func toolPartsByCallId(in messages: [UIMessage]) -> [String: (input: String, outputText: String?)] {
        var result: [String: (input: String, outputText: String?)] = [:]
        for message in messages {
            for part in message.parts {
                guard let tool = part as? UIMessagePart.Tool else { continue }
                let texts = tool.output.compactMap { $0 as? UIMessagePart.Text }.map(\.text)
                result[tool.toolCallId] = (
                    input: tool.input,
                    outputText: texts.isEmpty ? nil : texts.joined(separator: "\n")
                )
            }
        }
        return result
    }

    /// Parses a tool part's input JSON into the case's recipe-input shape.
    /// Blank input parses as the empty object; a non-object JSON value cannot
    /// be recipe inputs (fail closed). Pure; nonisolated because
    /// `ObservedCall.inputObject` is a plain computed property.
    nonisolated static func parseInputObject(_ inputJSON: String?) -> [String: IOSRecipeJSONValue]? {
        guard let inputJSON, !inputJSON.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return [:]
        }
        guard let data = inputJSON.data(using: .utf8),
              let object = try? JSONDecoder().decode([String: IOSRecipeJSONValue].self, from: data) else {
            return nil
        }
        return object
    }

    /// Conversation ids are lowercase UUID hex; validate before constructing
    /// the Kotlin UUID (the same guard the session tools use). Pure;
    /// nonisolated so tests can call it directly.
    nonisolated static func parseConversationId(_ raw: String) -> KotlinUuid? {
        let normalized = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard UUID(uuidString: normalized) != nil else { return nil }
        return KotlinUuid.companion.parse(uuidString: normalized)
    }

    // MARK: Sealed perturbation

    /// Deterministic host-side perturbation of the failure case's inputs: the
    /// same keys (the candidate's input schema must stay valid — the runner
    /// rejects undeclared input keys), string values replaced by hash-derived
    /// sealed sentinels, numbers +1, booleans toggled. Deterministic across
    /// builds so the suite hash is stable for the same facts.
    nonisolated static func perturbedInputs(_ inputs: [String: IOSRecipeJSONValue]) -> [String: IOSRecipeJSONValue] {
        inputs.mapValues { value in
            switch value {
            case .string(let text):
                let digest = Self.sha256Prefix(text)
                let sealed = "sealed-\(digest)"
                return .string(sealed == text ? sealed + "-x" : sealed)
            case .number(let number):
                return .number(number + 1)
            case .bool(let bool):
                return .bool(!bool)
            case .null, .array, .object:
                // Real tool-call inputs are flat; structural values pass
                // through (perturbing them would change the input schema).
                return value
            }
        }
    }

    nonisolated private static func sha256Prefix(_ text: String) -> String {
        let digest = SHA256.hash(data: Data(text.utf8))
        return digest.map { String(format: "%02x", $0) }.joined().prefix(16).description
    }
}

// MARK: - Observed call

extension IOSEvolutionSuiteProvider {
    /// One call of a run's observed sequence: ledger identity + outcome, and
    /// the message store's real input/output once resolved.
    struct ObservedCall: Equatable, Sendable {
        let toolCallId: String
        let toolName: String
        let seq: Int64
        let inputJSON: String?
        let outputText: String?
        let outcome: String?
        let outcomeKind: String?
        let errorCode: String?
        let finishedEventId: String?
        /// Recipe provenance from the Finished payload (runner-written step
        /// rows only); nil for ordinary tool calls.
        let artifactId: String?
        let artifactVersion: String?

        /// Real input parsed as the case's recipe-input shape; nil when the
        /// input is missing or not a JSON object.
        var inputObject: [String: IOSRecipeJSONValue]? {
            IOSEvolutionSuiteProvider.parseInputObject(inputJSON)
        }

        func with(inputJSON: String?, outputText: String?) -> ObservedCall {
            ObservedCall(
                toolCallId: toolCallId,
                toolName: toolName,
                seq: seq,
                inputJSON: inputJSON,
                outputText: outputText,
                outcome: outcome,
                outcomeKind: outcomeKind,
                errorCode: errorCode,
                finishedEventId: finishedEventId,
                artifactId: artifactId,
                artifactVersion: artifactVersion
            )
        }
    }
}

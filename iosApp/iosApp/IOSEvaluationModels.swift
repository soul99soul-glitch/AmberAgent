import CryptoKit
import Foundation

// MARK: - Phase 2 Wave C: independent evaluation suite & report models (§12 /
// §9.4 / §13.1 / §15 Phase 2 / §18.3)
//
// Recipe candidates only in evaluator v1. A suite is the evaluator-side
// companion of the proposer's `IOSEvaluationSuiteProposerView`
// (IOSEvolutionCandidateBuilder.swift): it owns the FULL case content —
// including the sealed holdout region — while the proposer view physically
// cannot carry case content (invariant 12: sealed content never enters
// candidate-generation prompts; the view only carries public case refs, the
// full-suite hash and a `hasSealedHoldout` boolean).
//
// Case semantics (deterministic tiers, §12.2):
// - `recipeInputs`           → the call-time inputs the runner receives.
// - `scriptedPrimitives`     → per-ToolId scripted primitive behavior; the
//   evaluator builds the runner's scripted executor from these fixtures. A
//   tool the candidate calls but the case does NOT script fails closed
//   (deterministic contract violation, never a silent `{}`).
// - `assertions`             → the case's contract: expected outputs / expected
//   error shape / ordered step calls with EXACT resolved arguments / W1 ledger
//   records. All checks are explicit; an undeclared check is vacuous.
// - `originalFailure`        → failure-replay only (§12.1): the replayed
//   failure. Used by the "fix or clearly narrow" rule.
//
// Report semantics (§9.4 / §13.1): `reportHash` is computed at construction
// over the report's canonical bytes (excluding `reportHash` itself), so the
// report is immutable: `matches(candidateHash:)` fails the moment the
// candidate hash or any report content changes (one-byte candidate change
// invalidates the whole report — approval binds candidateHash AND reportHash).

// MARK: - Case kinds and error kinds

enum IOSEvaluationCaseKind: String, Codable, Equatable, Sendable {
    case failureReplay
    case protectedSuccess
    case sealedHoldout
}

/// The failure shapes the deterministic tier can assert on (§12.2 "错误传播").
/// Mirrors `IOSRecipeRunError`'s cases so a case can declare exactly how a
/// failure must propagate.
enum IOSEvaluationErrorKind: String, Codable, Equatable, Sendable {
    case planInvalid
    case inputInvalid
    case argumentBinding
    case stepFailed
    case stepTimeout
    case outputResolution
}

// MARK: - Case fixtures

/// One scripted primitive behavior for a case (deterministic contract tier).
struct IOSEvaluationScriptedPrimitive: Codable, Equatable, Sendable {
    /// Raw JSON text the primitive returns on success (later steps may bind
    /// into it). `"{}"` when nil — the empty JSON object.
    let outputJSON: String?
    /// When set, the primitive throws (the step fails with this message).
    let failureMessage: String?
    /// Optional artificial latency; exercises step-timeout / wall-clock
    /// budgets without any real I/O.
    let delaySeconds: TimeInterval?

    init(outputJSON: String? = nil, failureMessage: String? = nil, delaySeconds: TimeInterval? = nil) {
        self.outputJSON = outputJSON
        self.failureMessage = failureMessage
        self.delaySeconds = delaySeconds
    }
}

/// The original failure a failure-replay case replays (§12.1). v1 uses it for
/// the "fix or clearly narrow" rule (see `IOSArtifactEvaluator`).
struct IOSEvaluationOriginalFailure: Codable, Equatable, Sendable {
    /// 失败发生的 step；nil = 失败无法归因到具体 step（如输出解析失败）。
    /// evaluator 的 fix-or-narrow 规则只消费 `completedStepIds`。
    let failedStepId: String?
    let errorKind: IOSEvaluationErrorKind
    /// Steps that had completed when the original failure happened.
    let completedStepIds: [String]
}

// MARK: - Assertions

/// Expected error shape: the run must fail with this kind, at this step
/// (nil stepId accepts any step).
struct IOSEvaluationExpectedError: Codable, Equatable, Sendable {
    let kind: IOSEvaluationErrorKind
    let stepId: String?
}

/// One expected primitive call: step order is the array order. `arguments`
/// (when non-nil) must equal the step's EXACT resolved argument JSON — this is
/// what catches binding errors that static validation cannot see.
struct IOSEvaluationExpectedStepCall: Codable, Equatable, Sendable {
    let stepId: String
    let tool: String
    let arguments: [String: IOSRecipeJSONValue]?
}

/// One W1 ledger expectation: a `tool_call_started` + `tool_call_finished`
/// pair for the step's toolCallId, with the Finished payload matching
/// `outcome` and (when non-nil) `outcomeKind` / `errorCode`. The runner writes
/// toolCallIds as `recipe-<executionId>-<step.id>`, so the evaluator matches
/// by the `-<stepId>` suffix (execution ids are UUIDs and must not be
/// asserted).
struct IOSEvaluationLedgerExpectation: Codable, Equatable, Sendable {
    let stepId: String
    /// Finished-row `outcome` ("completed" / "failed").
    let outcome: String
    /// Finished-row `outcomeKind` ("success" / "error"); nil → not asserted.
    let outcomeKind: String?
    /// Finished-row `errorCode`; nil → not asserted.
    let errorCode: String?
}

/// A case's deterministic contract. All checks are explicit; undeclared checks
/// are vacuous. When `expectedError` is nil the run must SUCCEED (a
/// protected-success or sealed-holdout contract); when set, the run must fail
/// exactly in that shape (a failure-replay case may declare the narrowed
/// failure it expects the candidate to have converged to).
struct IOSEvaluationAssertions: Codable, Equatable, Sendable {
    let expectedOutputs: [String: IOSRecipeJSONValue]?
    let expectedError: IOSEvaluationExpectedError?
    let expectedStepCalls: [IOSEvaluationExpectedStepCall]
    let expectedLedger: [IOSEvaluationLedgerExpectation]

    init(
        expectedOutputs: [String: IOSRecipeJSONValue]? = nil,
        expectedError: IOSEvaluationExpectedError? = nil,
        expectedStepCalls: [IOSEvaluationExpectedStepCall] = [],
        expectedLedger: [IOSEvaluationLedgerExpectation] = []
    ) {
        self.expectedOutputs = expectedOutputs
        self.expectedError = expectedError
        self.expectedStepCalls = expectedStepCalls
        self.expectedLedger = expectedLedger
    }
}

// MARK: - Workspace scenario（真实闭环收口 Slice A）

/// Workspace 差分 oracle：case 存在此字段时，evaluator 不再用 scripted
/// fixture 模拟 workspace primitive，而是让 baseline 与候选分别在全新的隔离
/// 临时 Workspace（真实 `IOSWorkspaceStore`，temp root）里执行。只对
/// Workspace 本地读写 primitive 的参数/binding/路径/schema 错误这一类可
/// 确定性隔离的失败构造；网络/MCP/无 recipe provenance 的失败没有它，
/// evaluator 会把「缺少确定性任务 oracle」记入 unresolvedRisks（永不自动
/// 晋升）。
struct IOSEvaluationWorkspaceScenario: Codable, Equatable, Sendable {
    /// failureReplay 专属：从失败 run 冻结的 baseline 制品（exact bytes +
    /// identity）。evaluator 必须先用它在全新 Workspace 里复现原失败，候选的
    /// 成功才有意义；复现不了 ⇒ case 标 insufficient-data 级降级。
    struct Baseline: Codable, Equatable, Sendable {
        /// recipe__<name>（账本 Finished payload 的 artifactId）。
        let artifactId: String
        /// 失败 run 实际执行的版本（账本 artifactVersion）。
        let version: String
        /// store 的 canonical bytes（与发布/哈希同源）。
        let canonicalJSON: Data
        /// 失败 step 在 baseline manifest.steps 中的下标（0-based）。
        let failingStepIndex: Int
    }

    /// 代码能确定验证的任务后置条件（plan §4：文件存在 / 内容等于由输入
    /// 决定的期望内容）。模板里的 `${input.<key>}` 在评测时按 case 的
    /// recipeInputs 解析；stepOutput 绑定不可静态派生，provider 不会生成。
    enum Postcondition: Codable, Equatable, Sendable {
        case fileExists(pathTemplate: String)
        case fileContentEquals(pathTemplate: String, contentTemplate: String)
    }

    /// nil for protected/sealed cases（它们没有 baseline 要复现）。
    let baseline: Baseline?
    let postconditions: [Postcondition]
}

// MARK: - Case

/// One evaluation case (§12.1): a scripted scenario the candidate must
/// reproduce or fix, with a full deterministic contract.
struct IOSEvaluationCase: Codable, Equatable, Sendable {
    let id: String
    let kind: IOSEvaluationCaseKind
    /// Call-time inputs for the runner.
    let recipeInputs: [String: IOSRecipeJSONValue]
    /// Per-ToolId scripted primitive behaviors; the evaluator builds the
    /// runner's scripted executor from these.
    let scriptedPrimitives: [String: IOSEvaluationScriptedPrimitive]
    let assertions: IOSEvaluationAssertions
    /// Failure-replay only (§12.1); nil for other kinds.
    let originalFailure: IOSEvaluationOriginalFailure?
    /// Slice A：Workspace 差分 oracle；nil = 纯 scripted case（failureReplay
    /// 通过时 evaluator 记 `no_deterministic_task_oracle` 未决风险）。
    let workspaceScenario: IOSEvaluationWorkspaceScenario?

    init(
        id: String,
        kind: IOSEvaluationCaseKind,
        recipeInputs: [String: IOSRecipeJSONValue],
        scriptedPrimitives: [String: IOSEvaluationScriptedPrimitive],
        assertions: IOSEvaluationAssertions,
        originalFailure: IOSEvaluationOriginalFailure?,
        workspaceScenario: IOSEvaluationWorkspaceScenario? = nil
    ) {
        self.id = id
        self.kind = kind
        self.recipeInputs = recipeInputs
        self.scriptedPrimitives = scriptedPrimitives
        self.assertions = assertions
        self.originalFailure = originalFailure
        self.workspaceScenario = workspaceScenario
    }
}

// MARK: - Suite (public region + sealed region)

/// The evaluator-side evaluation suite (§12.1). Public region
/// (`failureReplayCases` + `protectedSuccessCases`) and sealed region
/// (`sealedHoldoutCases`) are physically separated: the sealed region is only
/// reachable through this type, and `proposerView` — the ONLY object that may
/// leave the evaluator side — carries no case content at all (invariant 12).
/// `suiteHash` is computed over the canonical bytes of the FULL suite
/// (including sealed content), so a candidate is bound to the exact suite
/// revision while sealed content stays out of every proposer-visible value.
struct IOSEvaluationSuite: Equatable, Sendable {
    let suiteId: String
    let failureReplayCases: [IOSEvaluationCase]
    let protectedSuccessCases: [IOSEvaluationCase]
    /// Sealed holdout region (§12.1/§12.3): host/evaluator-owned, never shown
    /// to the proposer. NOT exposed through `proposerView`.
    let sealedHoldoutCases: [IOSEvaluationCase]
    /// Full-suite content hash (public + sealed regions, canonical bytes).
    let suiteHash: String
    /// Slice B：触发本次流程的失败 run 标识（B1 身份绑定）。可选字段；
    /// nil 时以 encodeIfPresent 编码（不进字节），旧 canned 套件的
    /// 字节/hash 不变。
    let originRunId: String?

    init(
        suiteId: String,
        failureReplayCases: [IOSEvaluationCase],
        protectedSuccessCases: [IOSEvaluationCase],
        sealedHoldoutCases: [IOSEvaluationCase],
        originRunId: String? = nil
    ) {
        // Region/kind consistency is a suite construction contract: a case in
        // the failure-replay region must declare failureReplay, etc. Debug
        // assertion only — the evaluator additionally switches on each case's
        // own `kind`, so a (buggy) mismatched suite degrades to tier logic on
        // the declared kind rather than silently changing behavior.
        assert(failureReplayCases.allSatisfy { $0.kind == .failureReplay })
        assert(protectedSuccessCases.allSatisfy { $0.kind == .protectedSuccess })
        assert(sealedHoldoutCases.allSatisfy { $0.kind == .sealedHoldout })

        self.suiteId = suiteId
        self.failureReplayCases = failureReplayCases
        self.protectedSuccessCases = protectedSuccessCases
        self.sealedHoldoutCases = sealedHoldoutCases
        self.originRunId = originRunId
        self.suiteHash = IOSEvaluationHashing.suiteHash(
            of: (try? JSONEncoder.sortedKeysEncoder().encode(
                SuiteHashPayload(
                    suiteId: suiteId,
                    failureReplayCases: failureReplayCases,
                    protectedSuccessCases: protectedSuccessCases,
                    sealedHoldoutCases: sealedHoldoutCases,
                    originRunId: originRunId
                )
            )) ?? Data()
        )
    }

    /// The proposer-visible part of this suite (§12.3). Case CONTENT is
    /// physically absent from the returned type: only public refs, the full
    /// suite hash and the has-sealed-holdout boolean exist there.
    var proposerView: IOSEvaluationSuiteProposerView {
        IOSEvaluationSuiteProposerView(
            suiteId: suiteId,
            suiteHash: suiteHash,
            failureReplayCaseRefs: failureReplayCases.map(\.id),
            protectedSuccessCaseRefs: protectedSuccessCases.map(\.id),
            hasSealedHoldout: !sealedHoldoutCases.isEmpty
        )
    }

    /// Encodable payload for the suite hash: the whole suite EXCLUDING
    /// `suiteHash` itself (the hash must not be a function of itself).
    /// `originRunId` 只在非 nil 时进入字节（encodeIfPresent）：nil 缺省
    /// 不改变旧 canned 套件的字节/hash。
    private struct SuiteHashPayload: Encodable {
        let suiteId: String
        let failureReplayCases: [IOSEvaluationCase]
        let protectedSuccessCases: [IOSEvaluationCase]
        let sealedHoldoutCases: [IOSEvaluationCase]
        let originRunId: String?

        private enum CodingKeys: String, CodingKey {
            case suiteId, failureReplayCases, protectedSuccessCases, sealedHoldoutCases, originRunId
        }

        func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encode(suiteId, forKey: .suiteId)
            try container.encode(failureReplayCases, forKey: .failureReplayCases)
            try container.encode(protectedSuccessCases, forKey: .protectedSuccessCases)
            try container.encode(sealedHoldoutCases, forKey: .sealedHoldoutCases)
            try container.encodeIfPresent(originRunId, forKey: .originRunId)
        }
    }
}

// MARK: - Case result

/// Stable failure code on a case result; nil when the case passed.
enum IOSEvaluationCaseFailureCode: String, Codable, Equatable, Sendable {
    case expectedSuccessButFailed
    case expectedErrorNotObserved
    case outputsMismatch
    case stepCallCountMismatch
    case stepCallToolMismatch
    case stepCallArgumentMismatch
    case ledgerRecordMissing
    case ledgerStartedMissing
    /// Slice A：冻结 baseline 在隔离 Workspace 里没有复现原失败（oracle 自身
    /// 不成立 ⇒ insufficient-data 级降级，不是候选的错）。
    case baselineFailureNotReproduced
    /// Slice A：run 成功但 Workspace 后置条件不满足（文件缺失/内容不符）——
    /// 判未修复（不是 narrowed）。
    case workspacePostconditionFailed
    /// Slice A：scenario 自身不可执行（临时 Workspace 建不起来 / baseline
    /// bytes 不可解码 / 后置条件模板不可解析）——oracle 缺失级降级。
    case workspaceOracleUnavailable
}

/// What actually happened when the candidate ran the case.
struct IOSEvaluationObservedOutcome: Codable, Equatable, Sendable {
    let didSucceed: Bool
    /// Recipe outputs when the run succeeded; nil otherwise.
    let outputs: [String: IOSRecipeJSONValue]?
    /// Failed step when the run failed; nil otherwise.
    let failedStepId: String?
    /// Failure kind when the run failed; nil otherwise.
    let failedErrorKind: IOSEvaluationErrorKind?
    /// Steps that ran to completion (the completed side effects, §10.3.6).
    let completedStepIds: [String]
}

struct IOSEvaluationCaseResult: Codable, Equatable, Sendable {
    let caseId: String
    let kind: IOSEvaluationCaseKind
    let passed: Bool
    let observedOutcome: IOSEvaluationObservedOutcome
    let failureCode: IOSEvaluationCaseFailureCode?
}

// MARK: - Recommendation and skipped tiers

enum IOSEvaluationRecommendation: String, Codable, Equatable, Sendable {
    case promote
    case manualJudgmentRequired
    case reject
}

/// Tiers this evaluator version honestly reports as NOT run (§12.2/§12.3):
/// evaluator v1 is fully deterministic and implements no LLM judge and no
/// stochastic-repeat tier. Recorded as absent so no caller mistakes the report
/// for one that includes model judgment or sample-distribution evidence.
enum IOSEvaluationSkippedTier: String, Codable, Equatable, Sendable {
    case llmJudge
    case stochasticRepeat
}

// MARK: - Report (§9.4)

/// Immutable evaluation report. `reportHash` is computed at construction over
/// the canonical bytes of every other field, so it cannot drift from content:
/// `matches(candidateHash:)` re-verifies both bindings and fails on ANY change
/// (one-byte candidate change invalidates the report — §13.1 binds
/// candidateHash AND reportHash at approval).
struct IOSEvaluationReport: Codable, Equatable, Sendable {
    let reportId: String
    let candidateHash: String
    let evaluatorVersion: String
    let suiteHash: String
    let results: [IOSEvaluationCaseResult]
    let protectedRegressions: Int
    let unresolvedRisks: [String]
    /// Tiers deliberately absent in this evaluator version (v1: no LLM judge,
    /// no stochastic repeat) — recorded honestly, not silently skipped.
    let skippedTiers: [IOSEvaluationSkippedTier]
    let recommendation: IOSEvaluationRecommendation
    let reportHash: String
    /// Slice B：同一 identity 链上的 originRunId（evaluator 从 suite 拷贝，
    /// B1）。可选字段；nil 时以 encodeIfPresent 编码（不进字节），旧字节/
    /// hash 不变。
    let originRunId: String?

    init(
        reportId: String,
        candidateHash: String,
        evaluatorVersion: String,
        suiteHash: String,
        results: [IOSEvaluationCaseResult],
        protectedRegressions: Int,
        unresolvedRisks: [String],
        skippedTiers: [IOSEvaluationSkippedTier],
        recommendation: IOSEvaluationRecommendation,
        originRunId: String? = nil
    ) {
        self.reportId = reportId
        self.candidateHash = candidateHash
        self.evaluatorVersion = evaluatorVersion
        self.suiteHash = suiteHash
        self.results = results
        self.protectedRegressions = protectedRegressions
        self.unresolvedRisks = unresolvedRisks
        self.skippedTiers = skippedTiers
        self.recommendation = recommendation
        self.originRunId = originRunId
        self.reportHash = IOSEvaluationHashing.reportHash(of: Self.canonicalJSONData(
            reportId: reportId, candidateHash: candidateHash, evaluatorVersion: evaluatorVersion,
            suiteHash: suiteHash, results: results, protectedRegressions: protectedRegressions,
            unresolvedRisks: unresolvedRisks, skippedTiers: skippedTiers, recommendation: recommendation,
            originRunId: originRunId
        ))
    }

    /// The report is valid for exactly this candidate hash AND its own hash
    /// must still re-compute to `reportHash` (immutability). Any change — a
    /// different candidate (one-byte diff) or edited report content — fails.
    func matches(candidateHash other: String) -> Bool {
        guard other == candidateHash else { return false }
        return reportHash == IOSEvaluationHashing.reportHash(of: Self.canonicalJSONData(
            reportId: reportId, candidateHash: candidateHash, evaluatorVersion: evaluatorVersion,
            suiteHash: suiteHash, results: results, protectedRegressions: protectedRegressions,
            unresolvedRisks: unresolvedRisks, skippedTiers: skippedTiers, recommendation: recommendation,
            originRunId: originRunId
        ))
    }

    /// Canonical bytes of every field EXCEPT `reportHash` (sortedKeys at every
    /// level, like the recipe manifest's canonical encoding).
    private static func canonicalJSONData(
        reportId: String,
        candidateHash: String,
        evaluatorVersion: String,
        suiteHash: String,
        results: [IOSEvaluationCaseResult],
        protectedRegressions: Int,
        unresolvedRisks: [String],
        skippedTiers: [IOSEvaluationSkippedTier],
        recommendation: IOSEvaluationRecommendation,
        originRunId: String?
    ) -> Data {
        let payload = ReportHashPayload(
            reportId: reportId,
            candidateHash: candidateHash,
            evaluatorVersion: evaluatorVersion,
            suiteHash: suiteHash,
            results: results,
            protectedRegressions: protectedRegressions,
            unresolvedRisks: unresolvedRisks,
            skippedTiers: skippedTiers,
            recommendation: recommendation,
            originRunId: originRunId
        )
        let data = try? JSONEncoder.sortedKeysEncoder().encode(payload)
        return data ?? Data()
    }

    /// `originRunId` 只在非 nil 时进入字节（encodeIfPresent）：nil 缺省不
    /// 改变旧报告字节/hash。
    private struct ReportHashPayload: Encodable {
        let reportId: String
        let candidateHash: String
        let evaluatorVersion: String
        let suiteHash: String
        let results: [IOSEvaluationCaseResult]
        let protectedRegressions: Int
        let unresolvedRisks: [String]
        let skippedTiers: [IOSEvaluationSkippedTier]
        let recommendation: IOSEvaluationRecommendation
        let originRunId: String?

        private enum CodingKeys: String, CodingKey {
            case reportId, candidateHash, evaluatorVersion, suiteHash, results
            case protectedRegressions, unresolvedRisks, skippedTiers, recommendation, originRunId
        }

        func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encode(reportId, forKey: .reportId)
            try container.encode(candidateHash, forKey: .candidateHash)
            try container.encode(evaluatorVersion, forKey: .evaluatorVersion)
            try container.encode(suiteHash, forKey: .suiteHash)
            try container.encode(results, forKey: .results)
            try container.encode(protectedRegressions, forKey: .protectedRegressions)
            try container.encode(unresolvedRisks, forKey: .unresolvedRisks)
            try container.encode(skippedTiers, forKey: .skippedTiers)
            try container.encode(recommendation, forKey: .recommendation)
            try container.encodeIfPresent(originRunId, forKey: .originRunId)
        }
    }
}

// MARK: - Domain-separated hashing

/// Stable, domain-separated content hashes for suites and reports (same
/// length-prefix style as the skill/recipe package hashes; distinct domains
/// so evaluation artifacts cannot collide with recipe or skill hashes).
enum IOSEvaluationHashing {
    static let suiteDomain = Data("amber.evolution.suite.v1\0".utf8)
    static let reportDomain = Data("amber.evolution.evaluation.v1\0".utf8)

    static func suiteHash(of canonicalBytes: Data) -> String {
        hash(domain: suiteDomain, data: canonicalBytes)
    }

    static func reportHash(of canonicalBytes: Data) -> String {
        hash(domain: reportDomain, data: canonicalBytes)
    }

    private static func hash(domain: Data, data: Data) -> String {
        var hasher = SHA256()
        hasher.update(data: domain)
        hasher.update(data: encodedLength(data.count))
        hasher.update(data: data)
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func encodedLength(_ length: Int) -> Data {
        var value = UInt64(length).bigEndian
        return Data(bytes: &value, count: MemoryLayout<UInt64>.size)
    }
}

// MARK: - Canonical JSON encoding helper

extension JSONEncoder {
    /// Deterministic encoding: keys sorted at every level, no pretty printing.
    /// The same style the recipe manifest uses (`canonicalJSONData`), so hashes
    /// over suite/report payloads are stable across processes.
    static func sortedKeysEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }
}

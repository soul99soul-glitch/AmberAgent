import CryptoKit
import Foundation

// MARK: - Phase 2 Wave B: bounded candidate builder (§6.2 / §9.3 / §11.2 / §12.3 / §15 Phase 2 / §18.3)
//
// Routes a validated `IOSGapHypothesis` to the artifact its kind allows
// (§6.2 decision graph) and produces a bounded candidate:
// - composition → Recipe manifest candidate: the model drafts, the HOST runs
//   the real validator + catalog oracle, canonicalizes and hashes (invariant
//   5), and returns a §9.3 manifest. Validation failure is a typed failure —
//   no half-product is ever published.
// - knowledgeOrProcedure → Skill/Playbook Workspace draft. v1: ALWAYS
//   draftOnly (§15 Phase 2 stop condition: no auto-promotion without an
//   evaluation suite; the candidate may only be opened as a manual draft).
// - missingExternalCapability → MCP capability request document (required
//   server / permissions / auth steps). It REQUESTs; it never installs and
//   never pretends to be connected (§6.1).
// - harnessBehavior → Harness Lab patch proposal document. Documentation
//   only — the host-code loop never hot-patches production (§6.1 / §5 Phase 5).
// - modelCeiling / insufficientEvidence → no-op + explanation (I-3 / I-4).
//
// Sealed isolation (I-12 / §12.3): the builder receives ONLY the suite's
// proposer view (`IOSEvaluationSuiteProposerView`) — public case refs plus a
// boolean. Sealed holdout CONTENT is physically absent from the type, so it
// can never reach a candidate-generation prompt. Failed candidates are never
// patched in place: every build issues a NEW candidateId and links lineage via
// parentVersion + baseHash (§12.3: 失败后创建新 candidate lineage).
//
// Budgets (§18.3): proposer attempt/token budgets are injected parameters;
// exhaustion returns a typed terminal outcome (`.budgetExhausted`). Attempts
// and artifact bytes are enforced deterministically here; the token budget is
// stated in every drafting prompt and accounted by the model wiring (next
// wave). The loop never relaxes validation to fit the budget.

// MARK: - §12.3 proposer view (public part of an evaluation suite)

/// What the proposer (candidate builder) may see of an evaluation suite.
/// Sealed holdout CONTENT is physically absent from this type — only the
/// boolean tells whether the full suite has any (invariant 12: sealed content
/// never enters candidate-generation prompts). The evaluator owner (parallel
/// wave) builds this view from its suite model.
struct IOSEvaluationSuiteProposerView: Equatable, Sendable {
    let suiteId: String
    /// Content hash of the FULL suite (evaluator-computed, includes sealed
    /// content); binds candidates to the exact suite revision (§9.4 spirit).
    let suiteHash: String
    /// Case refs of failure-replay cases (§12.1) — public.
    let failureReplayCaseRefs: [String]
    /// Case refs of protected-success cases (§12.1) — public.
    let protectedSuccessCaseRefs: [String]
    /// Whether the full suite contains sealed holdout cases. The cases
    /// themselves are never shown here (§12.3).
    let hasSealedHoldout: Bool
}

// MARK: - §18.3 budgets

/// Per-evolution-task proposer budgets (§18.3).
struct IOSEvolutionBudget: Equatable, Sendable {
    /// How many model calls the proposer may make for one candidate.
    var maxModelAttempts: Int
    /// Proposer token budget per attempt, stated in every drafting prompt.
    /// Actual token accounting lives in the model wiring (next wave); this
    /// wave enforces attempts and artifact bytes deterministically.
    var maxTokensPerAttempt: Int
    /// Artifact byte cap (§18.3: artifact 文件数/总字节/step 数上限).
    var maxDraftBytes: Int

    init(maxModelAttempts: Int = 3, maxTokensPerAttempt: Int = 2_000, maxDraftBytes: Int = 200_000) {
        self.maxModelAttempts = maxModelAttempts
        self.maxTokensPerAttempt = maxTokensPerAttempt
        self.maxDraftBytes = maxDraftBytes
    }

    static let standard = IOSEvolutionBudget()
}

// MARK: - §9.3 EvolutionCandidateManifest

/// Candidate identity (§9.3), extended with `draftOnly` (Phase 2: candidates
/// without a complete evaluation suite may only be opened as manual drafts).
/// Content stays in the Workspace candidate package; this manifest only
/// describes identity + lineage.
struct IOSEvolutionCandidateManifest: Codable, Equatable, Sendable {
    /// Fresh per build — a failed candidate is never patched in place; a
    /// revision is a NEW candidate that links its parent (§12.3).
    let candidateId: String
    let artifactKind: IOSArtifactKind
    let artifactName: String
    /// Version of the currently-active artifact this candidate revises; nil
    /// for a brand-new artifact (§12.3 lineage).
    let parentVersion: String?
    /// Content hash of the currently-active artifact; nil for a new artifact.
    let baseHash: String?
    /// Stable hash of the exact candidate bytes (invariant 5).
    let candidateHash: String
    let hypothesisId: String
    /// Public evaluation case refs only (failure replay + protected success).
    let evaluationCaseRefs: [String]
    /// Permission summary for this artifact (recipe: conservative-union effect
    /// class, I-10; skill/playbook: none; §9.3).
    let permissionEnvelope: [String]
    /// true → may only be opened as a manual Workspace draft; no promotion.
    let draftOnly: Bool
    let createdAtEpochMs: Int64
}

// MARK: - MCP capability request (§6.1)

/// A REQUEST for a missing external capability: the required server, its
/// purpose, required permissions and authentication steps. It never installs
/// anything and always states the connection is not established yet.
struct IOSMcpCapabilityRequest: Codable, Equatable, Sendable {
    let requestId: String
    let hypothesisId: String
    let serverName: String
    let purpose: String
    let requiredPermissions: [String]
    let authSteps: [String]
    let createdAtEpochMs: Int64

    /// User-facing request document. Assembled deterministically from the
    /// validated fields: required server/permissions/auth steps, and the
    /// connection is ALWAYS marked as not yet established (§6.1: 不假装已连接;
    /// the assembly never contains an install action).
    func document() -> String {
        let permissions = requiredPermissions.map { "- \($0)" }.joined(separator: "\n")
        let auth = authSteps.enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n")
        return """
        # MCP 能力请求
        - 请求 ID: \(requestId)
        - 假设 ID: \(hypothesisId)
        - 目标 server: \(serverName)
        - 目的: \(purpose)
        - 所需权限:
        \(permissions)
        - 认证步骤:
        \(auth)
        - 状态: 未连接 — 需要用户在设置中配置并批准后才会建立连接；本请求仅声明所需能力，不会自行安装任何组件。
        """
    }
}

// MARK: - Harness Lab patch proposal (§6.1 / §5 Phase 5)

/// A DOCUMENT that feeds the isolated Harness Lab (§5 Phase 5). The host-code
/// loop never hot-patches iOS production; the fixed Lab flow is appended by
/// the host so the proposal cannot drift from the Lab procedure.
struct IOSHarnessPatchProposal: Codable, Equatable, Sendable {
    /// Fixed minimal Lab flow (§5 Phase 5 steps 1–6).
    static let labFlowSteps: [String] = [
        "在独立 branch/worktree 生成 source patch",
        "在无用户凭证、默认无网络的容器/虚拟机中构建",
        "运行固定 runtime canary、passing regression 和 hidden holdout",
        "验证构建字节、评测字节与 commit hash 一致",
        "人工 review、CI、签名，随正常 App 更新发布",
        "release telemetry 关联 patch lineage，必要时版本回滚",
    ]

    let proposalId: String
    let hypothesisId: String
    let problem: String
    let suggestedChange: String
    let affectedAreas: [String]
    let createdAtEpochMs: Int64

    /// Lab input document. States explicitly that production code is never
    /// hot-patched from the device (§6.1).
    func document() -> String {
        let areas = affectedAreas.map { "- \($0)" }.joined(separator: "\n")
        let steps = Self.labFlowSteps.enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n")
        return """
        # Harness Lab Patch Proposal（仅文档）
        - 提案 ID: \(proposalId)
        - 假设 ID: \(hypothesisId)
        - 问题: \(problem)
        - 建议变更: \(suggestedChange)
        - 影响区域:
        \(areas)
        - 说明: 本提案只作为 Harness Lab 输入文档；不在 iOS 生产运行时热修改宿主代码（§6.1）。
        - Lab 流程（固定，§5 Phase 5）:
        \(steps)
        """
    }
}

// MARK: - Outcomes

/// Result of one bounded build (§6.2 / §18.3).
enum IOSCandidateBuildOutcome: Equatable, Sendable {
    /// Validated candidate (recipe: canonical bytes that passed the real
    /// validator; skill/playbook: draft markdown). `draftOnly` in the manifest
    /// tells whether it may only be opened as a manual draft.
    case candidate(manifest: IOSEvolutionCandidateManifest, content: Data)
    /// MCP capability REQUEST (missing_external_capability) — never an install.
    case capabilityRequest(IOSMcpCapabilityRequest)
    /// Harness Lab patch PROPOSAL document (harness_behavior) — Lab flow only.
    case harnessProposal(IOSHarnessPatchProposal)
    /// modelCeiling / insufficientEvidence → no candidate (I-3/I-4).
    case noOp(String)
    /// Typed terminal failure — no half-product is ever published (§11.2).
    case failed(IOSCandidateBuildError)
}

enum IOSCandidateBuildError: Error, Equatable, Sendable {
    /// §18.3: attempt budget exhausted. `lastIssue` is the feedback of the
    /// final failed attempt so callers can surface why. Typed terminal —
    /// never a silent downgrade of validation or a half-product.
    case budgetExhausted(attemptsUsed: Int, kind: IOSGapKind, lastIssue: String)
    /// The hypothesis's `recommendedArtifact` does not match the kind's route
    /// (§6.2) — fail-closed, no candidate.
    case artifactNotAllowedForKind(kind: IOSGapKind, artifact: IOSArtifactKind?)
}

// MARK: - Content hashing (invariant 5)

/// Stable, domain-separated content hash for evolution candidate bytes (used
/// for skill/playbook drafts and documents; recipe candidates use the recipe
/// store's own package hash so manifest.candidateHash equals what an apply
/// would publish).
enum IOSEvolutionHashing {
    /// Domain separator so evolution candidate content cannot collide with
    /// skill/recipe package hashes.
    static let candidateContentDomain = Data("amber.evolution.candidate.v1\0".utf8)

    static func contentHash(_ data: Data) -> String {
        var hasher = SHA256()
        hasher.update(data: candidateContentDomain)
        hasher.update(data: encodedLength(data.count))
        hasher.update(data: data)
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func encodedLength(_ length: Int) -> Data {
        var value = UInt64(length).bigEndian
        return Data(bytes: &value, count: MemoryLayout<UInt64>.size)
    }
}

// MARK: - Names

enum IOSEvolutionNames {
    /// Skill/Playbook artifact name `^[a-z0-9][a-z0-9_-]{1,63}$`. Looser than
    /// recipe names on purpose: existing skills (`skill-creator`, `visual-svg`)
    /// use hyphens.
    static func isValidArtifactName(_ raw: String) -> Bool {
        guard let first = raw.first, isLowercaseDigit(first) else { return false }
        let rest = raw.dropFirst()
        guard (1...63).contains(rest.count) else { return false }
        return rest.allSatisfy { isLowercaseDigit($0) || $0 == "-" || $0 == "_" }
    }

    /// MCP server name `^[a-z0-9][a-z0-9._-]{0,63}$`.
    static func isValidMcpServerName(_ raw: String) -> Bool {
        guard let first = raw.first, isLowercaseDigit(first) else { return false }
        let rest = raw.dropFirst()
        guard rest.count <= 63 else { return false }
        return rest.allSatisfy { isLowercaseDigit($0) || $0 == "-" || $0 == "_" || $0 == "." }
    }

    private static func isLowercaseDigit(_ c: Character) -> Bool {
        ("a"..."z").contains(c) || ("0"..."9").contains(c)
    }
}

// MARK: - Raw model drafts (host-invalidated)

/// Raw skill/playbook delta draft from the model.
private struct IOSSkillDeltaDraft: Codable {
    let artifactName: String
    let markdown: String

    enum CodingKeys: String, CodingKey {
        case artifactName = "artifact_name"
        case markdown
    }
}

/// Raw MCP capability request draft from the model.
private struct IOSMcpRequestDraft: Codable {
    let serverName: String
    let purpose: String
    let requiredPermissions: [String]
    let authSteps: [String]

    enum CodingKeys: String, CodingKey {
        case serverName = "server_name"
        case purpose
        case requiredPermissions = "required_permissions"
        case authSteps = "auth_steps"
    }
}

/// Raw harness proposal draft from the model.
private struct IOSHarnessDraft: Codable {
    let problem: String
    let suggestedChange: String
    let affectedAreas: [String]

    enum CodingKeys: String, CodingKey {
        case problem
        case suggestedChange = "suggested_change"
        case affectedAreas = "affected_areas"
    }
}

// MARK: - Builder

/// Bounded candidate builder (§6.2 / §11.2 / §18.3). Never writes files and
/// never promotes; it only returns a validated value.
struct IOSEvolutionCandidateBuilder: Sendable {
    /// Base directory of the recipe store this builder mirrors for
    /// canonicalize + hash + base-version resolution. Only the store's
    /// zero-write preview path runs here (invariant 5: the bytes and hash are
    /// exactly what a later apply would publish; the builder never writes).
    let recipeStoreBaseDirectory: URL
    /// Host catalog oracle (production: `IOSDynamicToolRegistry.primitiveCatalogEntry`).
    let catalog: IOSRecipeCatalogLookup
    /// Model injection point: `(prompt:) async throws -> String`.
    let model: @Sendable (String) async throws -> String

    init(
        recipeStoreBaseDirectory: URL,
        catalog: @escaping IOSRecipeCatalogLookup,
        model: @escaping @Sendable (String) async throws -> String
    ) {
        self.recipeStoreBaseDirectory = recipeStoreBaseDirectory
        self.catalog = catalog
        self.model = model
    }

    /// §6.2 decision graph: routes the validated hypothesis to the artifact
    /// its kind allows. `catalogSummary` is prompt context only — validation
    /// is always done through the injected catalog oracle.
    func build(
        hypothesis: IOSGapHypothesis,
        proposerSuite: IOSEvaluationSuiteProposerView? = nil,
        budget: IOSEvolutionBudget = .standard,
        catalogSummary: String = ""
    ) async -> IOSCandidateBuildOutcome {
        switch hypothesis.kind {
        case .composition:
            guard hypothesis.recommendedArtifact == .recipe else {
                return .failed(.artifactNotAllowedForKind(kind: hypothesis.kind, artifact: hypothesis.recommendedArtifact))
            }
            return await draftRecipeCandidate(
                hypothesis: hypothesis, proposerSuite: proposerSuite,
                budget: budget, catalogSummary: catalogSummary
            )
        case .knowledgeOrProcedure:
            guard let artifact = hypothesis.recommendedArtifact,
                  artifact == .skill || artifact == .playbook else {
                return .failed(.artifactNotAllowedForKind(kind: hypothesis.kind, artifact: hypothesis.recommendedArtifact))
            }
            return await draftSkillCandidate(
                hypothesis: hypothesis, artifactKind: artifact,
                proposerSuite: proposerSuite, budget: budget
            )
        case .missingExternalCapability:
            return await draftMcpRequest(hypothesis: hypothesis, budget: budget)
        case .harnessBehavior:
            return await draftHarnessProposal(hypothesis: hypothesis, budget: budget)
        case .modelCeiling:
            guard hypothesis.recommendedArtifact == nil else {
                return .failed(.artifactNotAllowedForKind(kind: hypothesis.kind, artifact: hypothesis.recommendedArtifact))
            }
            return .noOp("model_ceiling：同样证据下多种尝试仍无法可靠完成，不制造无效候选掩盖模型能力上限（§6.1 / I-4）。")
        case .insufficientEvidence:
            guard hypothesis.recommendedArtifact == nil else {
                return .failed(.artifactNotAllowedForKind(kind: hypothesis.kind, artifact: hypothesis.recommendedArtifact))
            }
            return .noOp("insufficient_evidence：失败不能稳定复现或无法归因，继续采样或请用户澄清，不生成候选（I-3）。")
        }
    }

    // MARK: Composition → Recipe manifest candidate

    /// Model drafts → host decodes → REAL validator + catalog oracle →
    /// zero-write store canonicalize + hash + base → §9.3 manifest. Any
    /// failed attempt feeds the next prompt (bounded by the budget); when the
    /// budget is exhausted the outcome is a typed terminal, never a half
    /// product (§18.3).
    private func draftRecipeCandidate(
        hypothesis: IOSGapHypothesis,
        proposerSuite: IOSEvaluationSuiteProposerView?,
        budget: IOSEvolutionBudget,
        catalogSummary: String
    ) async -> IOSCandidateBuildOutcome {
        var feedback = ""
        for _ in 1...budget.maxModelAttempts {
            let prompt = Self.recipeDraftPrompt(
                hypothesis: hypothesis, proposerSuite: proposerSuite,
                catalogSummary: catalogSummary, budget: budget,
                feedback: feedback.isEmpty ? nil : feedback
            )
            let text: String
            do {
                text = try await model(prompt)
            } catch {
                feedback = "模型调用失败：\(error)"
                continue
            }
            guard let data = IOSEvolutionDraftParsing.objectText(from: text).data(using: .utf8) else {
                feedback = "模型输出不是文本"
                continue
            }
            guard let manifest = try? IOSRecipeManifest.decode(data) else {
                feedback = "候选不是合法的 amber.recipe.v1 JSON"
                continue
            }
            let validation = IOSRecipeValidator.validate(manifest: manifest, catalog: catalog)
            guard validation.isValid, let envelope = validation.permissionEnvelope else {
                feedback = "Recipe 校验失败：" + validation.issues.map { $0.code.rawValue }.joined(separator: ", ")
                continue
            }
            let store = IOSRecipeFileStore(baseDirectory: recipeStoreBaseDirectory)
            let preparation: IOSRecipePackagePreparation
            do {
                preparation = try store.prepareRecipe(recipeJSON: data)
            } catch {
                feedback = "Recipe 包被拒绝：\(error)"
                continue
            }
            guard preparation.candidate.canonicalJSON.count <= budget.maxDraftBytes else {
                feedback = "候选超出字节预算（\(preparation.candidate.canonicalJSON.count) > \(budget.maxDraftBytes)）"
                continue
            }
            return .candidate(
                manifest: IOSEvolutionCandidateManifest(
                    candidateId: "cand-\(UUID().uuidString)",
                    artifactKind: .recipe,
                    artifactName: preparation.candidate.name,
                    parentVersion: preparation.base?.version,
                    baseHash: preparation.base?.hash,
                    candidateHash: preparation.candidate.hash,
                    hypothesisId: hypothesis.id,
                    evaluationCaseRefs: Self.publicCaseRefs(proposerSuite),
                    permissionEnvelope: [envelope.rawValue],
                    draftOnly: Self.isDraftOnly(proposerSuite),
                    createdAtEpochMs: Self.nowMillis()
                ),
                content: preparation.candidate.canonicalJSON
            )
        }
        return .failed(.budgetExhausted(
            attemptsUsed: budget.maxModelAttempts, kind: hypothesis.kind, lastIssue: feedback
        ))
    }

    // MARK: knowledgeOrProcedure → Skill/Playbook Workspace draft (always draftOnly)

    /// v1: the delta is ALWAYS a Workspace manual draft (§15 Phase 2 stop
    /// condition — no evaluation suite, no auto-promotion). The content is
    /// plain markdown; the manifest carries no promotion marker of any kind.
    private func draftSkillCandidate(
        hypothesis: IOSGapHypothesis,
        artifactKind: IOSArtifactKind,
        proposerSuite: IOSEvaluationSuiteProposerView?,
        budget: IOSEvolutionBudget
    ) async -> IOSCandidateBuildOutcome {
        var feedback = ""
        for _ in 1...budget.maxModelAttempts {
            let prompt = Self.skillDraftPrompt(
                hypothesis: hypothesis, artifactKind: artifactKind,
                proposerSuite: proposerSuite, budget: budget,
                feedback: feedback.isEmpty ? nil : feedback
            )
            let text: String
            do {
                text = try await model(prompt)
            } catch {
                feedback = "模型调用失败：\(error)"
                continue
            }
            let draft: IOSSkillDeltaDraft
            do {
                draft = try JSONDecoder().decode(
                    IOSSkillDeltaDraft.self,
                    from: Data(IOSEvolutionDraftParsing.objectText(from: text).utf8)
                )
            } catch {
                feedback = "草稿不是合法 JSON（需要 {\"artifact_name\": ..., \"markdown\": ...}）"
                continue
            }
            let name = draft.artifactName.trimmingCharacters(in: .whitespacesAndNewlines)
            guard IOSEvolutionNames.isValidArtifactName(name) else {
                feedback = "artifact_name 必须匹配 ^[a-z0-9][a-z0-9_-]{1,63}$"
                continue
            }
            let markdown = draft.markdown.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !markdown.isEmpty else {
                feedback = "markdown 为空"
                continue
            }
            guard let content = markdown.data(using: .utf8), content.count <= budget.maxDraftBytes else {
                feedback = "草稿超出字节预算（\(budget.maxDraftBytes)）"
                continue
            }
            return .candidate(
                manifest: IOSEvolutionCandidateManifest(
                    candidateId: "cand-\(UUID().uuidString)",
                    artifactKind: artifactKind,
                    artifactName: name,
                    parentVersion: nil,
                    baseHash: nil,
                    candidateHash: IOSEvolutionHashing.contentHash(content),
                    hypothesisId: hypothesis.id,
                    evaluationCaseRefs: Self.publicCaseRefs(proposerSuite),
                    permissionEnvelope: [],
                    draftOnly: true,
                    createdAtEpochMs: Self.nowMillis()
                ),
                content: content
            )
        }
        return .failed(.budgetExhausted(
            attemptsUsed: budget.maxModelAttempts, kind: hypothesis.kind, lastIssue: feedback
        ))
    }

    // MARK: missingExternalCapability → MCP capability request document

    /// Assembles the REQUEST document from validated structured fields. The
    /// assembly never contains an install action and always states the server
    /// is not connected yet (§6.1: 不假装已连接).
    private func draftMcpRequest(
        hypothesis: IOSGapHypothesis,
        budget: IOSEvolutionBudget
    ) async -> IOSCandidateBuildOutcome {
        var feedback = ""
        for _ in 1...budget.maxModelAttempts {
            let prompt = Self.mcpRequestDraftPrompt(
                hypothesis: hypothesis, budget: budget,
                feedback: feedback.isEmpty ? nil : feedback
            )
            let text: String
            do {
                text = try await model(prompt)
            } catch {
                feedback = "模型调用失败：\(error)"
                continue
            }
            let draft: IOSMcpRequestDraft
            do {
                draft = try JSONDecoder().decode(
                    IOSMcpRequestDraft.self,
                    from: Data(IOSEvolutionDraftParsing.objectText(from: text).utf8)
                )
            } catch {
                feedback = "草稿不是合法 JSON（需要 {\"server_name\": ..., \"purpose\": ..., \"required_permissions\": [...], \"auth_steps\": [...]}）"
                continue
            }
            let serverName = draft.serverName.trimmingCharacters(in: .whitespacesAndNewlines)
            guard IOSEvolutionNames.isValidMcpServerName(serverName) else {
                feedback = "server_name 必须匹配 ^[a-z0-9][a-z0-9._-]{0,63}$"
                continue
            }
            let purpose = draft.purpose.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !purpose.isEmpty else {
                feedback = "purpose 为空"
                continue
            }
            let permissions = draft.requiredPermissions.map {
                $0.trimmingCharacters(in: .whitespacesAndNewlines)
            }.filter { !$0.isEmpty }
            guard !permissions.isEmpty else {
                feedback = "required_permissions 为空"
                continue
            }
            let authSteps = draft.authSteps.map {
                $0.trimmingCharacters(in: .whitespacesAndNewlines)
            }.filter { !$0.isEmpty }
            guard !authSteps.isEmpty else {
                feedback = "auth_steps 为空"
                continue
            }
            let request = IOSMcpCapabilityRequest(
                requestId: "mcp-req-\(UUID().uuidString)",
                hypothesisId: hypothesis.id,
                serverName: serverName,
                purpose: purpose,
                requiredPermissions: permissions,
                authSteps: authSteps,
                createdAtEpochMs: Self.nowMillis()
            )
            guard let bytes = request.document().data(using: .utf8),
                  bytes.count <= budget.maxDraftBytes else {
                feedback = "请求文档超出字节预算（\(budget.maxDraftBytes)）"
                continue
            }
            return .capabilityRequest(request)
        }
        return .failed(.budgetExhausted(
            attemptsUsed: budget.maxModelAttempts, kind: hypothesis.kind, lastIssue: feedback
        ))
    }

    // MARK: harnessBehavior → Harness Lab patch proposal document

    /// Documentation only: the fixed Lab flow (§5 Phase 5) is appended by the
    /// host, and the document states production code is never hot-patched.
    private func draftHarnessProposal(
        hypothesis: IOSGapHypothesis,
        budget: IOSEvolutionBudget
    ) async -> IOSCandidateBuildOutcome {
        var feedback = ""
        for _ in 1...budget.maxModelAttempts {
            let prompt = Self.harnessDraftPrompt(
                hypothesis: hypothesis, budget: budget,
                feedback: feedback.isEmpty ? nil : feedback
            )
            let text: String
            do {
                text = try await model(prompt)
            } catch {
                feedback = "模型调用失败：\(error)"
                continue
            }
            let draft: IOSHarnessDraft
            do {
                draft = try JSONDecoder().decode(
                    IOSHarnessDraft.self,
                    from: Data(IOSEvolutionDraftParsing.objectText(from: text).utf8)
                )
            } catch {
                feedback = "草稿不是合法 JSON（需要 {\"problem\": ..., \"suggested_change\": ..., \"affected_areas\": [...]}）"
                continue
            }
            let problem = draft.problem.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !problem.isEmpty else {
                feedback = "problem 为空"
                continue
            }
            let suggestedChange = draft.suggestedChange.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !suggestedChange.isEmpty else {
                feedback = "suggested_change 为空"
                continue
            }
            let affectedAreas = draft.affectedAreas.map {
                $0.trimmingCharacters(in: .whitespacesAndNewlines)
            }.filter { !$0.isEmpty }
            guard !affectedAreas.isEmpty else {
                feedback = "affected_areas 为空"
                continue
            }
            let proposal = IOSHarnessPatchProposal(
                proposalId: "harness-\(UUID().uuidString)",
                hypothesisId: hypothesis.id,
                problem: problem,
                suggestedChange: suggestedChange,
                affectedAreas: affectedAreas,
                createdAtEpochMs: Self.nowMillis()
            )
            guard let bytes = proposal.document().data(using: .utf8),
                  bytes.count <= budget.maxDraftBytes else {
                feedback = "提案文档超出字节预算（\(budget.maxDraftBytes)）"
                continue
            }
            return .harnessProposal(proposal)
        }
        return .failed(.budgetExhausted(
            attemptsUsed: budget.maxModelAttempts, kind: hypothesis.kind, lastIssue: feedback
        ))
    }

    // MARK: Suite helpers (§12.1 / §12.3 / §15 Phase 2 stop condition)

    /// Public case refs only — sealed holdout refs/cases are never in the
    /// proposer view.
    private static func publicCaseRefs(_ suite: IOSEvaluationSuiteProposerView?) -> [String] {
        guard let suite else { return [] }
        return suite.failureReplayCaseRefs + suite.protectedSuccessCaseRefs
    }

    /// §15 Phase 2 stop condition + §12.1: without failure replay AND
    /// protected success AND sealed holdout, the candidate may only be opened
    /// as a manual draft (never auto-promoted).
    private static func isDraftOnly(_ suite: IOSEvaluationSuiteProposerView?) -> Bool {
        guard let suite else { return true }
        return suite.failureReplayCaseRefs.isEmpty
            || suite.protectedSuccessCaseRefs.isEmpty
            || !suite.hasSealedHoldout
    }

    // MARK: Drafting prompts

    private static func compactJSON<T: Encodable>(_ value: T) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(value)
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    private static func suiteLine(_ suite: IOSEvaluationSuiteProposerView?) -> String {
        guard let suite else {
            return "（暂无评测套件——候选只能作为人工草稿，draftOnly）"
        }
        return "suite=\(suite.suiteId) failureReplay=\(suite.failureReplayCaseRefs) protectedSuccess=\(suite.protectedSuccessCaseRefs) hasSealedHoldout=\(suite.hasSealedHoldout)（sealed 内容对 proposer 不可见）"
    }

    private static func feedbackBlock(_ feedback: String?) -> [String] {
        guard let feedback, !feedback.isEmpty else { return [] }
        return ["", "上一次尝试被拒绝，请修正后重新输出完整候选：", feedback]
    }

    private static func recipeDraftPrompt(
        hypothesis: IOSGapHypothesis,
        proposerSuite: IOSEvaluationSuiteProposerView?,
        catalogSummary: String,
        budget: IOSEvolutionBudget,
        feedback: String?
    ) -> String {
        let hypothesisJSON = (try? compactJSON(hypothesis)) ?? "\(hypothesis)"
        var lines = [
            "你是 Amber 的候选生成器。根据下面的 gap 假设，起草一个 amber.recipe.v1 Recipe 候选。",
            "只输出一个 JSON 对象（amber.recipe.v1 schema），不要输出其它文字或 markdown 围栏。",
            "",
            "Gap 假设：",
            hypothesisJSON,
            "",
            "评测套件（proposer 公开视图）：",
            suiteLine(proposerSuite),
            "",
            "可用的 primitive 工具摘要：",
            catalogSummary.isEmpty ? "（未提供）" : catalogSummary,
            "",
            "硬性约束：",
            "- schema 必须是 \"amber.recipe.v1\"",
            "- name 必须匹配 ^[a-z][a-z0-9_]{1,31}$（将来以 recipe__<name> 暴露）",
            "- steps ≤ 8；每步只能引用已发布的 ToolId，禁止 recipe__ 前缀",
            "- 绑定必须是完整字符串：${input.<name>} 或 ${step.<id>.output.<field>}",
            "- outputs 必须绑定某个 step 的输出",
            "- 预算：每个候选 ≤ \(budget.maxTokensPerAttempt) tokens",
        ]
        lines.append(contentsOf: feedbackBlock(feedback))
        return lines.joined(separator: "\n")
    }

    private static func skillDraftPrompt(
        hypothesis: IOSGapHypothesis,
        artifactKind: IOSArtifactKind,
        proposerSuite: IOSEvaluationSuiteProposerView?,
        budget: IOSEvolutionBudget,
        feedback: String?
    ) -> String {
        let hypothesisJSON = (try? compactJSON(hypothesis)) ?? "\(hypothesis)"
        var lines = [
            "你是 Amber 的候选生成器。根据下面的 gap 假设，起草一个 \(artifactKind.rawValue) delta 候选。",
            "只输出一个 JSON 对象，不要输出其它文字或 markdown 围栏：",
            "{\"artifact_name\": \"...\", \"markdown\": \"...\"}",
            "",
            "Gap 假设：",
            hypothesisJSON,
            "",
            "评测套件（proposer 公开视图）：",
            suiteLine(proposerSuite),
            "",
            "硬性约束：",
            "- artifact_name 必须匹配 ^[a-z0-9][a-z0-9_-]{1,63}$",
            "- markdown 是新的 SKILL.md 内容（纯文本知识/流程说明）",
            "- 草稿中不得包含任何自动晋升、自我批准或绕过审批的内容",
            "- 预算：每个候选 ≤ \(budget.maxTokensPerAttempt) tokens",
        ]
        lines.append(contentsOf: feedbackBlock(feedback))
        return lines.joined(separator: "\n")
    }

    private static func mcpRequestDraftPrompt(
        hypothesis: IOSGapHypothesis,
        budget: IOSEvolutionBudget,
        feedback: String?
    ) -> String {
        let hypothesisJSON = (try? compactJSON(hypothesis)) ?? "\(hypothesis)"
        var lines = [
            "你是 Amber 的候选生成器。根据下面的 gap 假设，起草一个 MCP 能力请求（仅请求文档，不安装任何东西）。",
            "只输出一个 JSON 对象，不要输出其它文字或 markdown 围栏：",
            "{\"server_name\": \"...\", \"purpose\": \"...\", \"required_permissions\": [...], \"auth_steps\": [...]}",
            "",
            "Gap 假设：",
            hypothesisJSON,
            "",
            "硬性约束：",
            "- server_name 必须匹配 ^[a-z0-9][a-z0-9._-]{0,63}$",
            "- required_permissions 列出访问该能力所需的权限（如 network、oauth2、read）",
            "- auth_steps 列出用户完成认证的步骤",
            "- 这是请求文档：不得声称服务器已经连接或已经安装",
            "- 预算：每个候选 ≤ \(budget.maxTokensPerAttempt) tokens",
        ]
        lines.append(contentsOf: feedbackBlock(feedback))
        return lines.joined(separator: "\n")
    }

    private static func harnessDraftPrompt(
        hypothesis: IOSGapHypothesis,
        budget: IOSEvolutionBudget,
        feedback: String?
    ) -> String {
        let hypothesisJSON = (try? compactJSON(hypothesis)) ?? "\(hypothesis)"
        var lines = [
            "你是 Amber 的候选生成器。根据下面的 gap 假设，起草一个 Harness Lab patch 提案（仅文档，进入 Lab 流程）。",
            "只输出一个 JSON 对象，不要输出其它文字或 markdown 围栏：",
            "{\"problem\": \"...\", \"suggested_change\": \"...\", \"affected_areas\": [...]}",
            "",
            "Gap 假设：",
            hypothesisJSON,
            "",
            "硬性约束：",
            "- problem：宿主行为缺陷的描述",
            "- suggested_change：建议的源码变更方向",
            "- affected_areas：受影响的组件/模块",
            "- 提案只进入 Harness Lab；不得声称直接修改生产代码",
            "- 预算：每个候选 ≤ \(budget.maxTokensPerAttempt) tokens",
        ]
        lines.append(contentsOf: feedbackBlock(feedback))
        return lines.joined(separator: "\n")
    }

    private static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

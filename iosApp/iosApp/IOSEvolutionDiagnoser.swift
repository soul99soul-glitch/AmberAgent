import Foundation

// MARK: - Phase 2 Wave B: typed gap diagnoser (§6.1 / §9.2 / §11.2 / §15 Phase 2)
//
// Turns projected `IOSEvolutionEvidence` (the real projector's output) into a
// host-schema-validated `IOSGapHypothesis`, an honest no-op, or a typed
// failure. The model drafts over evidence it did not produce; the HOST
// validates every field before anything downstream can consume it
// (§11.2: "输出必须通过 host schema 校验").
//
// Invariants this file participates in:
// - I-1 (evidence before hypothesis): `evidenceIds` must be non-empty and
//   every entry must exist in the input evidence set; evidence is never
//   invented by the model.
// - I-3 (no evidence, no candidate): empty evidence — or evidence with no
//   failure/interruption/denial signal and no user hint — returns no-op
//   WITHOUT calling the model. `insufficientEvidence` from the model is a
//   valid hypothesis (with nil artifact, §9.2 hard rule) that the candidate
//   builder turns into no-op.
// - I-4 (artifact matches the gap): `recommendedArtifact` must match the
//   kind's allowed set (§6.1 table); `insufficientEvidence` / `modelCeiling`
//   must carry nil.
// - §11.2: a ToolId the catalog oracle does not know, or an MCP connection
//   that is not in the known set, is a typed failure — never silently
//   downgraded into a valid hypothesis.
// - §11.2: the diagnoser has NO file-write permission and cannot promote
//   anything; it only returns a validated value.

// MARK: - §9.2 GapHypothesis (host schema)

/// Host-schema-validated output of the diagnoser (§9.2). `id` is
/// host-assigned (`hyp-<uuid>`); `evidenceIds` ⊆ the input evidence set;
/// `kind` comes from the taxonomy; `alternatives` and `falsifier` are
/// required; when `kind` is `insufficientEvidence` or `modelCeiling`,
/// `recommendedArtifact` is nil.
struct IOSGapHypothesis: Codable, Equatable, Sendable {
    let id: String
    let evidenceIds: [String]
    let kind: IOSGapKind
    let claim: String
    let confidence: Double
    let alternatives: [String]
    let falsifier: String
    let recommendedArtifact: IOSArtifactKind?
}

// MARK: - Outcomes

/// Result of one diagnosis (§11.2). A validation failure is a typed `failed`
/// value — the diagnoser never auto-downgrades a bad model output into a
/// valid hypothesis.
enum IOSDiagnosisOutcome: Equatable, Sendable {
    /// Honest no-op (I-3): no attributable evidence, or no failure signal and
    /// no user hint. The model was not even called.
    case noOp(String)
    /// A host-schema-validated hypothesis (§9.2).
    case hypothesis(IOSGapHypothesis)
    /// Typed failure: the model call failed or its output violated the host
    /// schema / oracles.
    case failed(IOSDiagnosisError)
}

/// Typed diagnoser failures (§11.2). Each case is a deterministic host-side
/// rejection so callers can surface the exact reason.
enum IOSDiagnosisError: Error, Equatable, Sendable {
    case modelCallFailed(String)
    case malformedModelOutput(String)
    case unknownGapKind(String)
    case missingEvidenceRefs
    case unresolvedEvidenceRef(String)
    case missingAlternative
    case missingFalsifier
    case emptyClaim
    case missingConfidence
    case confidenceOutOfRange(Double)
    /// `insufficientEvidence` / `modelCeiling` must carry no artifact (§9.2).
    case artifactNotAllowed(kind: IOSGapKind, artifact: IOSArtifactKind?)
    /// The artifact does not match the kind's allowed set (§6.1 table).
    case artifactKindMismatch(kind: IOSGapKind, artifact: IOSArtifactKind?)
    case hallucinatedToolId(String)
    case hallucinatedMcpConnection(String)
    case missingRequestedMcpServer
    case invalidMcpServerName(String)
}

// MARK: - Raw model draft (host-invalidated)

/// Raw JSON the model returns. Every field is optional so a missing field is
/// reported as its precise schema failure instead of a generic decode error.
private struct IOSHypothesisDraft: Codable {
    let kind: String?
    let claim: String?
    let confidence: Double?
    let alternatives: [String]?
    let falsifier: String?
    let recommendedArtifact: String?
    /// ToolIds the diagnosis ASSERTS exist — host-checked against the catalog.
    let toolIds: [String]?
    /// MCP connections the diagnosis ASSERTS exist — host-checked against the
    /// known-connection query.
    let mcpConnections: [String]?
    /// Only meaningful for `missingExternalCapability`: the server whose
    /// connection is being requested (§6.1; may be a not-yet-connected server).
    let requestedMcpServer: String?
    let evidenceIds: [String]?

    enum CodingKeys: String, CodingKey {
        case kind, claim, confidence, alternatives, falsifier
        case recommendedArtifact = "recommended_artifact"
        case toolIds = "tool_ids"
        case mcpConnections = "mcp_connections"
        case requestedMcpServer = "requested_mcp_server"
        case evidenceIds = "evidence_ids"
    }
}

/// Shared draft parsing (used by the builder too): pulls the JSON object out
/// of a model response that may wrap it in ```json fences.
enum IOSEvolutionDraftParsing {
    static func objectText(from raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("{") { return trimmed }
        if let start = trimmed.range(of: "{"),
           let end = trimmed.range(of: "}", options: .backwards),
           start.lowerBound < end.lowerBound {
            return String(trimmed[start.lowerBound...end.lowerBound])
        }
        return trimmed
    }
}

// MARK: - Diagnoser

/// Typed gap diagnoser (§11.2). Model calls go through an injected closure;
/// production wiring is the next wave, tests script it.
struct IOSEvolutionDiagnoser: Sendable {
    /// ToolId existence / effect-class oracle (production:
    /// `IOSDynamicToolRegistry.primitiveCatalogEntry`).
    let catalogOracle: IOSRecipeCatalogLookup
    /// MCP known-connection query: returns the currently known/connected
    /// server names (production wiring: MCP config store / manager, next wave).
    let mcpConnectionOracle: @Sendable () -> [String]
    /// Model injection point: `(prompt:) async throws -> String`.
    let model: @Sendable (String) async throws -> String

    init(
        catalogOracle: @escaping IOSRecipeCatalogLookup,
        mcpConnectionOracle: @escaping @Sendable () -> [String],
        model: @escaping @Sendable (String) async throws -> String
    ) {
        self.catalogOracle = catalogOracle
        self.mcpConnectionOracle = mcpConnectionOracle
        self.model = model
    }

    /// Diagnoses projected evidence into a validated hypothesis, an honest
    /// no-op, or a typed failure (§11.2 / §15 Phase 2 acceptance 1).
    /// `catalogSummary` / `userHint` are prompt context only — validation is
    /// always done through the injected oracles.
    func diagnose(
        evidence: [IOSEvolutionEvidence],
        userHint: String? = nil,
        catalogSummary: String = ""
    ) async -> IOSDiagnosisOutcome {
        // I-3: no evidence → no-op without calling the model.
        guard !evidence.isEmpty else {
            return .noOp("没有可归因的 evidence（输入集合为空）——继续采样或请用户澄清，不生成候选（I-3）。")
        }
        // No failure signal and no user hint → nothing to attribute.
        let hasFailureSignal = evidence.contains { $0.observedOutcome != .success }
        guard hasFailureSignal || userHint != nil else {
            return .noOp("输入 evidence 没有失败/中断/拒绝信号且没有用户提示——没有可归因的缺口（I-3）。")
        }

        let knownMcp = mcpConnectionOracle()
        let prompt = Self.diagnosisPrompt(
            evidence: evidence,
            userHint: userHint,
            catalogSummary: catalogSummary,
            knownMcpConnections: knownMcp
        )
        let text: String
        do {
            text = try await model(prompt)
        } catch {
            return .failed(.modelCallFailed(String(describing: error)))
        }
        let draft: IOSHypothesisDraft
        do {
            draft = try JSONDecoder().decode(
                IOSHypothesisDraft.self,
                from: Data(IOSEvolutionDraftParsing.objectText(from: text).utf8)
            )
        } catch {
            return .failed(.malformedModelOutput("模型输出不是合法 JSON：\(error)"))
        }
        return Self.validatedHypothesis(
            draft: draft,
            evidence: evidence,
            catalogOracle: catalogOracle,
            knownMcpConnections: knownMcp
        )
    }

    // MARK: Host schema validation

    /// Validates a raw draft against the input evidence, the catalog oracle
    /// and the known MCP connections. Fail-closed: any violation is a typed
    /// failure, never a downgraded "valid" hypothesis (§11.2).
    private static func validatedHypothesis(
        draft: IOSHypothesisDraft,
        evidence: [IOSEvolutionEvidence],
        catalogOracle: @escaping IOSRecipeCatalogLookup,
        knownMcpConnections: [String]
    ) -> IOSDiagnosisOutcome {
        guard let kind = draft.kind.flatMap(Self.gapKind(from:)) else {
            return .failed(.unknownGapKind(draft.kind ?? ""))
        }
        let evidenceIds = (draft.evidenceIds ?? []).map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
        }.filter { !$0.isEmpty }
        guard !evidenceIds.isEmpty else { return .failed(.missingEvidenceRefs) }
        let knownIds = Set(evidence.map(\.id))
        if let missing = evidenceIds.first(where: { !knownIds.contains($0) }) {
            return .failed(.unresolvedEvidenceRef(missing))
        }
        let alternatives = (draft.alternatives ?? []).map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
        }.filter { !$0.isEmpty }
        guard !alternatives.isEmpty else { return .failed(.missingAlternative) }
        let falsifier = draft.falsifier?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !falsifier.isEmpty else { return .failed(.missingFalsifier) }
        let claim = draft.claim?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !claim.isEmpty else { return .failed(.emptyClaim) }
        guard let confidence = draft.confidence else { return .failed(.missingConfidence) }
        guard (0.0...1.0).contains(confidence) else {
            return .failed(.confidenceOutOfRange(confidence))
        }
        let artifact = draft.recommendedArtifact.flatMap(Self.artifactKind(from:))
        switch kind {
        case .modelCeiling, .insufficientEvidence:
            guard artifact == nil else {
                return .failed(.artifactNotAllowed(kind: kind, artifact: artifact))
            }
        default:
            let allowed = allowedArtifacts(for: kind)
            guard let artifact, allowed.contains(artifact) else {
                return .failed(.artifactKindMismatch(kind: kind, artifact: artifact))
            }
        }
        for toolId in draft.toolIds ?? [] {
            let trimmed = toolId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, catalogOracle(trimmed)?.exists == true else {
                return .failed(.hallucinatedToolId(trimmed))
            }
        }
        let knownMcp = Set(knownMcpConnections)
        for connection in draft.mcpConnections ?? [] {
            let trimmed = connection.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, knownMcp.contains(trimmed) else {
                return .failed(.hallucinatedMcpConnection(trimmed))
            }
        }
        if kind == .missingExternalCapability {
            guard let server = draft.requestedMcpServer?
                .trimmingCharacters(in: .whitespacesAndNewlines), !server.isEmpty else {
                return .failed(.missingRequestedMcpServer)
            }
            guard IOSEvolutionNames.isValidMcpServerName(server) else {
                return .failed(.invalidMcpServerName(server))
            }
        }
        return .hypothesis(IOSGapHypothesis(
            id: "hyp-\(UUID().uuidString)",
            evidenceIds: evidenceIds,
            kind: kind,
            claim: claim,
            confidence: confidence,
            alternatives: alternatives,
            falsifier: falsifier,
            recommendedArtifact: artifact
        ))
    }

    /// §6.1: the artifact kinds a gap kind may recommend.
    private static func allowedArtifacts(for kind: IOSGapKind) -> Set<IOSArtifactKind> {
        switch kind {
        case .knowledgeOrProcedure: return [.skill, .playbook]
        case .composition: return [.recipe]
        case .missingExternalCapability: return [.mcpBinding]
        case .harnessBehavior: return [.harnessPatch]
        case .modelCeiling, .insufficientEvidence: return []
        }
    }

    // MARK: Taxonomy normalization (plan snake_case → Swift enum)

    /// Accepts both the Swift raw value and the plan's snake_case spelling
    /// (missing_external_capability, insufficient_evidence, …).
    private static func gapKind(from raw: String) -> IOSGapKind? {
        if let kind = IOSGapKind(rawValue: raw) { return kind }
        return IOSGapKind(rawValue: camelized(raw))
    }

    private static func artifactKind(from raw: String) -> IOSArtifactKind? {
        if let kind = IOSArtifactKind(rawValue: raw) { return kind }
        return IOSArtifactKind(rawValue: camelized(raw))
    }

    private static func camelized(_ raw: String) -> String {
        raw.split(separator: "_").enumerated().map { index, part in
            index == 0 ? String(part) : part.prefix(1).uppercased() + part.dropFirst()
        }.joined()
    }

    // MARK: Prompt

    /// Builds the diagnosis prompt. Only `redactedSummary` and structured
    /// fields of the evidence ever enter the prompt (I-15) — the evidence
    /// type itself never carries bodies.
    private static func diagnosisPrompt(
        evidence: [IOSEvolutionEvidence],
        userHint: String?,
        catalogSummary: String,
        knownMcpConnections: [String]
    ) -> String {
        let evidenceLines = evidence.map { item -> String in
            let tool = item.toolId.map { " tool=\($0)" } ?? ""
            let terminal = item.terminalReason.map { " terminal=\($0)" } ?? ""
            let signal = item.userSignal.map { " userSignal=\($0.rawValue)" } ?? ""
            return "- id=\(item.id) outcome=\(item.observedOutcome.rawValue)\(tool)\(terminal)\(signal) summary=\(item.redactedSummary)"
        }.joined(separator: "\n")
        let knownMcpText = knownMcpConnections.isEmpty ? "（无）" : knownMcpConnections.joined(separator: ", ")
        let hintText: String
        if let userHint, !userHint.isEmpty {
            hintText = userHint
        } else {
            hintText = "（无）"
        }
        return """
        你是 Amber 的 gap 诊断器。根据下面的运行证据（只读，来自持久化账本；summary 已脱敏）和用户提示，判断最可能的能力缺口，只输出一个 JSON 对象（不要输出其它文字或 markdown 围栏）。

        允许的 kind（只能选一个）：
        - knowledge_or_procedure：工具足够，缺领域知识/顺序/判断规则 → recommended_artifact: "skill" 或 "playbook"
        - composition：现有工具足够但重复编排不稳定或成本高 → recommended_artifact: "recipe"
        - missing_external_capability：缺少 API/权限/认证/传感器/执行器 → recommended_artifact: "mcp_binding"
        - harness_behavior：问题来自调度/恢复/上下文/权限/工具循环 → recommended_artifact: "harness_patch"
        - model_ceiling：同样证据下多种尝试仍无法可靠完成 → recommended_artifact: null
        - insufficient_evidence：失败不能稳定复现或无法归因 → recommended_artifact: null（必须为 null）

        规则：
        - evidence_ids 必须 ≥1 且全部来自下面的证据 id（不能发明）。
        - alternatives 至少 1 条替代解释；falsifier 必须说明什么证据会推翻本判断。
        - confidence ∈ [0,1]。
        - tool_ids 只能填写确实已发布的工具；mcp_connections 只能填写确实已连接的 server（从已知连接列表选）。
        - 只有 kind=missing_external_capability 时才填写 requested_mcp_server（要请求连接的 server 名）。
        - 不存在的工具或连接会被 host 校验拒绝。

        已知 MCP 连接：\(knownMcpText)
        可用工具摘要：\(catalogSummary.isEmpty ? "（未提供）" : catalogSummary)
        用户提示：\(hintText)

        证据：
        \(evidenceLines)

        输出 JSON schema：
        {"kind": "...", "claim": "...", "confidence": 0.0, "alternatives": ["..."], "falsifier": "...", "recommended_artifact": "..." 或 null, "tool_ids": ["..."], "mcp_connections": ["..."], "requested_mcp_server": "..." 或 null, "evidence_ids": ["..."]}
        """
    }
}

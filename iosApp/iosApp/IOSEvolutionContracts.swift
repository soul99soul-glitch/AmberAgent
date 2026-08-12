import Foundation

// MARK: - Evolution evidence & identity contracts (§9 of
// docs/SELF_EVOLUTION_AND_HOT_RELOAD_PLAN.md, Phase 0).
//
// Phase 0 implements ONLY the fields the current phase actually writes/reads:
// artifact identity, evidence refs, outcome/signal enums, `EvolutionEvidence`
// (§9.1) and `PromotionReceipt` (§9.6). GapHypothesis / CandidateManifest /
// EvaluationReport (§9.2–9.5) are NOT declared here yet — they arrive with the
// phase that consumes them, per §15 ("Phase 0 只实现真实使用到的字段").
//
// Invariants this file participates in:
// - I-1 (evidence before hypothesis): `IOSEvolutionEvidence` is projected from
//   durable owners (ledger `agent_event` rows, `agent_run` rows, approval
//   records); the model never writes `observedOutcome` (§9.1).
// - I-15 (privacy): evidence carries `redactedSummary`, never message bodies
//   or full tool output.

/// The kind of artifact an evolution candidate/receipt refers to (§6.1/§9).
/// Phase 0 only exercises `skill`; the other raw values are part of the stable
/// taxonomy so later phases do not need to re-version the enum.
enum IOSArtifactKind: String, Codable, Equatable, Sendable {
    case skill
    case recipe
    case playbook
    case mcpBinding
    case harnessPatch
}

/// Finite gap taxonomy (§6.1). `insufficientEvidence` is the honest no-op.
/// Phase 0 declares the enum for the contract; nothing diagnoses yet.
enum IOSGapKind: String, Codable, Equatable, Sendable {
    case knowledgeOrProcedure
    case composition
    case missingExternalCapability
    case harnessBehavior
    case modelCeiling
    case insufficientEvidence
}

/// What actually happened, from a runtime owner (§9.1) — never model-written.
/// Phase 0 projects: tool structured errors, run terminals, approval denials.
enum IOSOutcomeKind: String, Codable, Equatable, Sendable {
    case success
    case error
    case denied
    case interrupted
}

/// Explicit user actions relevant to evolution (§11.1). Recipe feedback is
/// written only from the stable Recipe detail entry after an exact-version
/// execution has been found in the ledger.
enum IOSUserSignal: String, Codable, Equatable, Sendable {
    case approvalDenied
    case experienceHelpful
    case experienceHarmful
}

/// Stable reference to a durable fact owner (invariant 1). `id` is the owner's
/// stable identifier, never a copy of the owner's content:
/// - `.ledgerEvent`      → `eventId` of an `agent_event` row (Room).
/// - `.approvalDecision` → stable id of a permission/approval record.
/// - `.agentRun`         → `runId` of an `agent_run` row (terminal evidence).
struct IOSEvidenceRef: Codable, Equatable, Sendable {
    enum Kind: String, Codable, Equatable, Sendable {
        case ledgerEvent
        case approvalDecision
        case agentRun
    }

    let kind: Kind
    let id: String
}

/// One projected, attributable run fact (§9.1). Projected on demand by
/// `IOSEvolutionEvidenceProjector`; not a long-term candidate database (§15
/// Phase 0 stop condition).
struct IOSEvolutionEvidence: Codable, Equatable, Sendable {
    /// Deterministic id derived from the source owner(s) so re-projection
    /// yields the same id for the same durable fact.
    let id: String
    let runId: String
    let sourceRefs: [IOSEvidenceRef]
    let observedOutcome: IOSOutcomeKind
    let toolId: String?
    let toolVersion: String?
    let terminalReason: String?
    let userSignal: IOSUserSignal?
    /// Short structured summary for browsing only (I-15). Never contains user
    /// message bodies or full tool output; evaluation replay would go to the
    /// source owner for authorized data.
    let redactedSummary: String
    let createdAtEpochMs: Int64
}

/// Exact successful Recipe execution used to gate helpful/harmful feedback.
/// This is an on-demand projection of an `agent_run` + recipe-level Finished
/// event, not a second outcome store.
struct IOSRecipeExecutionEvidence: Equatable, Sendable {
    let runId: String
    let eventId: String
    let artifactId: String
    let artifactVersion: String
    let createdAtEpochMs: Int64
}

/// §9.6: the bridge for post-deploy attribution and rollback. Not a version
/// history system — a device keeps only the active + previous receipt per
/// artifact (§18.1). `toHash` MUST equal the actual live package hash after
/// apply/rollback (acceptance 2).
struct IOSPromotionReceipt: Codable, Equatable, Sendable {
    let artifactId: String
    let fromHash: String?
    let toHash: String
    /// Optional until an independent evaluator exists (Phase 2); approval
    /// binds base/candidate/report hashes once it does (§9.4/§13.1).
    let evaluationReportHash: String?
    /// Optional until a versioned tool catalog revision exists (Phase 1).
    let catalogRevision: Int64?
    /// "user" today (skill_import approval is always an explicit user
    /// decision); policy-engine identity + policy version in later phases
    /// (§13.4).
    let approvedBy: String
    let promotedAtEpochMs: Int64
}

/// Tiny JSON receipt store (§9.6/§18.1): per artifact, keeps ONLY the active
/// receipt and the previous one. Lives next to the artifact store (same base
/// directory), under `evolution/receipts/<artifactId>.json`.
struct IOSPromotionReceiptStore {
    struct Snapshot: Codable, Equatable {
        var active: IOSPromotionReceipt?
        var previous: IOSPromotionReceipt?
    }

    private static let mutationLock = NSLock()

    private let baseDirectory: URL
    private let fileManager: FileManager

    init(baseDirectory: URL, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        self.baseDirectory = baseDirectory
    }

    private var receiptsDirectory: URL {
        baseDirectory.appendingPathComponent("evolution/receipts", isDirectory: true)
    }

    private func fileURL(artifactId: String) -> URL {
        receiptsDirectory.appendingPathComponent("\(artifactId).json")
    }

    /// Current active+previous receipts for one artifact; nil when the
    /// artifact has no receipt file (never promoted or receipt cleared).
    func snapshot(artifactId: String) -> Snapshot? {
        guard let data = try? Data(contentsOf: fileURL(artifactId: artifactId)),
              let snapshot = try? JSONDecoder().decode(Snapshot.self, from: data) else {
            return nil
        }
        return snapshot
    }

    /// Promotes `receipt` to active and demotes the old active to previous
    /// (the old previous is dropped — only two receipts are kept, §18.1).
    func record(_ receipt: IOSPromotionReceipt) {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        let existing = snapshot(artifactId: receipt.artifactId)
        let snapshot = Snapshot(active: receipt, previous: existing?.active)
        write(snapshot, artifactId: receipt.artifactId)
    }

    /// Removes all receipts for an artifact (used when a newly-imported skill
    /// is rolled back to "not installed" — there is no active version anymore).
    func clear(artifactId: String) {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        try? fileManager.removeItem(at: fileURL(artifactId: artifactId))
    }

    private func write(_ snapshot: Snapshot, artifactId: String) {
        do {
            try fileManager.createDirectory(at: receiptsDirectory, withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try encoder.encode(snapshot).write(to: fileURL(artifactId: artifactId), options: .atomic)
        } catch {
            // Best-effort: a receipt write failure must not fail a promotion
            // that already succeeded on disk — it only loses attribution.
            print("[AmberChat] promotion receipt write failed artifact=\(artifactId): \(error)")
        }
    }
}

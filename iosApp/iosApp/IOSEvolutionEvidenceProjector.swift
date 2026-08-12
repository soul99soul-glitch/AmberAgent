import Foundation
import Shared

// MARK: - §11.1 / §15 Phase 0: on-demand evidence projector
//
// Projects durable run facts into `IOSEvolutionEvidence` on demand — for one
// runId or a recent time window. Phase 0 projects ONLY clear facts:
//   - tool structured errors (tool_call_finished with error outcome/errorCode),
//   - terminal outcomes (the `agent_run` row's final status),
//   - approval denials (`approval_denied` events and denied finished outcomes).
// Per-call successes are deliberately NOT projected (§9.1: no success noise).
//
// No long-term candidate DB and no caching layer (Phase 0 stop condition).
// `redactedSummary` is a short structured summary — this projector never
// receives message bodies or full tool output (the ledger only stores digests
// and codes), and it truncates every string it embeds (acceptance 3).
enum IOSEvolutionEvidenceProjector {
    static let toolStartedEventType = "tool_call_started"
    static let toolFinishedEventType = "tool_call_finished"
    static let approvalDeniedEventType = IOSAgentRunLedger.approvalDeniedEventType
    static let experienceFeedbackEventType = IOSAgentRunLedger.experienceFeedbackEventType

    /// Maximum length of anything embedded into `redactedSummary`, so a
    /// pathological error code can never smuggle body-sized text into
    /// evidence (I-15, acceptance 3).
    private static let maximumSummaryTokenLength = 200

    /// One decoded ledger row reduced to what projection needs. Parsed inside
    /// the DAO callback so only Sendable Swift values cross the continuation.
    private struct ProjectionRow {
        let runId: String
        let eventId: String
        let type: String
        let seq: Int64
        let ts: Int64
        let toolCallId: String?
        let toolName: String?
        let outcome: String?
        let artifactId: String?
        let artifactVersion: String?
        let outcomeKind: String?
        let errorCode: String?
        let sourceRef: String?
        let userSignal: String?

        static func decode(_ event: AgentEventEntity) -> ProjectionRow? {
            guard let data = event.payload.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return nil
            }
            return ProjectionRow(
                runId: event.runId,
                eventId: event.eventId,
                type: event.type,
                seq: event.seq,
                ts: event.ts,
                toolCallId: object["toolCallId"] as? String,
                toolName: object["toolName"] as? String,
                outcome: object["outcome"] as? String,
                // Evolution-contract keys (deliverable 2). All optional: rows
                // written before the contract decode with nil here, never crash.
                artifactId: object["artifactId"] as? String,
                artifactVersion: object["artifactVersion"] as? String,
                outcomeKind: object["outcomeKind"] as? String,
                errorCode: object["errorCode"] as? String,
                sourceRef: object["sourceRef"] as? String,
                userSignal: object["userSignal"] as? String
            )
        }
    }

    /// Sendable snapshot of the `agent_run` row, extracted inside the DAO
    /// callback to avoid crossing isolation with the non-Sendable KMP entity.
    private struct RunSnapshot {
        let runId: String
        let status: String
        let startedAtEpochMs: Int64
        let finishedAtEpochMs: Int64?
        let interruptedReason: String?
    }

    /// Projects one run's durable facts into evidence. Never throws: a
    /// partially unreadable ledger degrades to whatever is decodable.
    static func project(runId: String, dao: AgentRuntimeDao) async -> [IOSEvolutionEvidence] {
        let run = await readRun(runId: runId, dao: dao)
        let rows = await readRows(runId: runId, dao: dao)
        var evidence: [IOSEvolutionEvidence] = []
        if let run, let terminal = terminalEvidence(run: run) {
            evidence.append(terminal)
        }
        evidence.append(contentsOf: toolOutcomeEvidence(rows: rows))
        evidence.append(contentsOf: approvalDenialEvidence(rows: rows))
        evidence.append(contentsOf: experienceFeedbackEvidence(rows: rows))
        return evidence.sorted { $0.createdAtEpochMs < $1.createdAtEpochMs }
    }

    /// Latest exact-version Recipe success. Successes remain excluded from the
    /// general evidence feed; this lookup exists only to gate explicit user
    /// feedback in Recipe detail.
    static func latestSuccessfulRecipeExecution(
        artifactId: String,
        artifactVersion: String,
        dao: AgentRuntimeDao
    ) async -> IOSRecipeExecutionEvidence? {
        let runs = await readAllRuns(dao: dao)
            .sorted { $0.startedAtEpochMs > $1.startedAtEpochMs }
        for run in runs {
            let match = await readRows(runId: run.runId, dao: dao)
                .filter {
                    $0.type == toolFinishedEventType
                        && $0.toolCallId?.hasPrefix("recipe-level-") == true
                        && $0.artifactId == artifactId
                        && $0.artifactVersion == artifactVersion
                        && ($0.outcomeKind == IOSOutcomeKind.success.rawValue || $0.outcome == "completed")
                }
                .max { $0.seq < $1.seq }
            if let match {
                return IOSRecipeExecutionEvidence(
                    runId: run.runId,
                    eventId: match.eventId,
                    artifactId: artifactId,
                    artifactVersion: artifactVersion,
                    createdAtEpochMs: match.ts
                )
            }
        }
        return nil
    }

    /// Projects every run started at or after `sinceEpochMs` (recent window).
    /// Uses only existing DAO queries (`listAllRuns` + per-run events), so no
    /// KMP change is needed in Phase 0.
    static func projectRecent(sinceEpochMs: Int64, dao: AgentRuntimeDao) async -> [IOSEvolutionEvidence] {
        let runIds = await readRecentRunIds(sinceEpochMs: sinceEpochMs, dao: dao)
        var evidence: [IOSEvolutionEvidence] = []
        for runId in runIds {
            evidence.append(contentsOf: await project(runId: runId, dao: dao))
        }
        return evidence.sorted { $0.createdAtEpochMs < $1.createdAtEpochMs }
    }

    // MARK: - Fact projection

    /// Terminal outcome from the `agent_run` row. Only terminal statuses
    /// project; running / awaiting_permission / recovery_pending are not
    /// facts yet. `sourceRef` is the run row's stable id (runId).
    private static func terminalEvidence(run: RunSnapshot) -> IOSEvolutionEvidence? {
        let outcome: IOSOutcomeKind
        let terminalReason: String?
        switch run.status {
        case "completed":
            outcome = .success
            terminalReason = nil
        case "interrupted":
            outcome = .interrupted
            terminalReason = run.interruptedReason
        case "failed", "truncated":
            outcome = .error
            terminalReason = run.status
        default:
            return nil
        }
        return IOSEvolutionEvidence(
            id: "ev:terminal:\(run.runId)",
            runId: run.runId,
            sourceRefs: [IOSEvidenceRef(kind: .agentRun, id: run.runId)],
            observedOutcome: outcome,
            toolId: nil,
            toolVersion: nil,
            terminalReason: terminalReason,
            userSignal: nil,
            redactedSummary: redacted("run \(run.status)\(terminalReason.map { " (\($0))" } ?? "")"),
            createdAtEpochMs: run.finishedAtEpochMs ?? run.startedAtEpochMs
        )
    }

    /// Tool structured errors (and engine-level policy denials) from
    /// `tool_call_finished` rows. A finished row is paired with the LAST
    /// `tool_call_started` for the same toolCallId (by seq) to recover the
    /// tool name — the same pairing the W3 recovery classifier uses.
    private static func toolOutcomeEvidence(rows: [ProjectionRow]) -> [IOSEvolutionEvidence] {
        let finishedRows = rows.filter { $0.type == toolFinishedEventType }
        guard !finishedRows.isEmpty else { return [] }
        let startedRows = rows.filter { $0.type == toolStartedEventType }
        var evidence: [IOSEvolutionEvidence] = []
        for row in finishedRows.sorted(by: { $0.seq < $1.seq }) {
            guard let kind = outcomeKindForFinished(row: row) else { continue }
            let toolName = startedRows
                .filter { $0.toolCallId == row.toolCallId && $0.seq < row.seq }
                .max(by: { $0.seq < $1.seq })?
                .toolName
            let summary: String
            switch kind {
            case .error:
                summary = "tool error: \(toolName ?? "unknown")"
                    + (row.errorCode.map { " (\(redacted($0)))" } ?? "")
            case .denied:
                summary = "tool denied: \(toolName ?? "unknown")"
            case .success, .interrupted:
                continue
            }
            evidence.append(IOSEvolutionEvidence(
                id: "ev:\(row.eventId)",
                runId: row.runId,
                sourceRefs: [IOSEvidenceRef(kind: .ledgerEvent, id: row.eventId)],
                observedOutcome: kind,
                toolId: toolName,
                toolVersion: nil,
                terminalReason: nil,
                userSignal: nil,
                redactedSummary: redacted(summary),
                createdAtEpochMs: row.ts
            ))
        }
        return evidence
    }

    /// User approval denials from `approval_denied` rows (§11.1). The event's
    /// eventId is the stable evidence ref.
    private static func approvalDenialEvidence(rows: [ProjectionRow]) -> [IOSEvolutionEvidence] {
        rows
            .filter { $0.type == approvalDeniedEventType }
            .sorted { $0.seq < $1.seq }
            .map { row in
                IOSEvolutionEvidence(
                    id: "ev:\(row.eventId)",
                    runId: row.runId,
                    sourceRefs: [IOSEvidenceRef(kind: .ledgerEvent, id: row.eventId)],
                    observedOutcome: .denied,
                    toolId: row.toolName,
                    toolVersion: nil,
                    terminalReason: nil,
                    userSignal: .approvalDenied,
                    redactedSummary: redacted("approval denied: \(row.toolName ?? "unknown")"),
                    createdAtEpochMs: row.ts
                )
            }
    }

    private static func experienceFeedbackEvidence(rows: [ProjectionRow]) -> [IOSEvolutionEvidence] {
        rows
            .filter { $0.type == experienceFeedbackEventType }
            .sorted { $0.seq < $1.seq }
            .compactMap { row in
                guard let signalRaw = row.userSignal,
                      let signal = IOSUserSignal(rawValue: signalRaw),
                      signal == .experienceHelpful || signal == .experienceHarmful else {
                    return nil
                }
                let outcome: IOSOutcomeKind = signal == .experienceHelpful ? .success : .error
                return IOSEvolutionEvidence(
                    id: "ev:\(row.eventId)",
                    runId: row.runId,
                    sourceRefs: [
                        IOSEvidenceRef(kind: .ledgerEvent, id: row.eventId),
                        IOSEvidenceRef(kind: .agentRun, id: row.runId),
                    ],
                    observedOutcome: outcome,
                    toolId: row.artifactId,
                    toolVersion: row.artifactVersion,
                    terminalReason: nil,
                    userSignal: signal,
                    redactedSummary: redacted(
                        signal == .experienceHelpful
                            ? "recipe feedback: helpful"
                            : "recipe feedback: harmful"
                    ),
                    createdAtEpochMs: row.ts
                )
            }
    }

    /// Maps a finished row to a projected outcome kind, or nil when the row is
    /// not a Phase 0 fact (successes and pauses are not evidence yet, §9.1).
    /// Structured `outcomeKind` (new rows) wins over the legacy plain
    /// `outcome` string (old rows), keeping old ledgers projectable.
    private static func outcomeKindForFinished(row: ProjectionRow) -> IOSOutcomeKind? {
        if let outcomeKind = row.outcomeKind.flatMap(IOSOutcomeKind.init(rawValue:)) {
            switch outcomeKind {
            case .error, .denied:
                return outcomeKind
            case .success, .interrupted:
                return nil
            }
        }
        switch row.outcome {
        case "failed":
            return .error
        case "denied":
            return .denied
        default:
            return nil
        }
    }

    // MARK: - DAO reads

    private static func readRun(runId: String, dao: AgentRuntimeDao) async -> RunSnapshot? {
        await withCheckedContinuation { (continuation: CheckedContinuation<RunSnapshot?, Never>) in
            dao.getRun(id: runId) { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: RunSnapshot(
                    runId: result.runId,
                    status: result.status,
                    startedAtEpochMs: result.startedAt,
                    finishedAtEpochMs: result.finishedAt?.int64Value,
                    interruptedReason: result.interruptedReason
                ))
            }
        }
    }

    private static func readRows(runId: String, dao: AgentRuntimeDao) async -> [ProjectionRow] {
        await withCheckedContinuation { (continuation: CheckedContinuation<[ProjectionRow], Never>) in
            dao.listEventsForRun(id: runId) { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: [])
                    return
                }
                continuation.resume(returning: result.compactMap(ProjectionRow.decode))
            }
        }
    }

    private static func readRecentRunIds(sinceEpochMs: Int64, dao: AgentRuntimeDao) async -> [String] {
        await readAllRuns(dao: dao)
            .filter { $0.startedAtEpochMs >= sinceEpochMs }
            .map(\.runId)
    }

    private static func readAllRuns(dao: AgentRuntimeDao) async -> [RunSnapshot] {
        await withCheckedContinuation { (continuation: CheckedContinuation<[RunSnapshot], Never>) in
            dao.listAllRuns { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: [])
                    return
                }
                continuation.resume(returning: result
                    .map {
                        RunSnapshot(
                            runId: $0.runId,
                            status: $0.status,
                            startedAtEpochMs: $0.startedAt,
                            finishedAtEpochMs: $0.finishedAt?.int64Value,
                            interruptedReason: $0.interruptedReason
                        )
                    }
                )
            }
        }
    }

    // MARK: - Redaction

    /// Truncates any single token embedded into a summary (defense in depth:
    /// today's inputs are already structured codes, never bodies).
    private static func redacted(_ text: String) -> String {
        guard text.count > maximumSummaryTokenLength else { return text }
        return String(text.prefix(maximumSummaryTokenLength)) + "…"
    }
}

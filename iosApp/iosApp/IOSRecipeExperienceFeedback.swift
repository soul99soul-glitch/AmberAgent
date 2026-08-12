import Foundation
@preconcurrency import Shared

enum IOSRecipeExperienceFeedbackKind: Equatable, Sendable {
    case helpful
    case harmful

    var signal: IOSUserSignal {
        switch self {
        case .helpful: .experienceHelpful
        case .harmful: .experienceHarmful
        }
    }
}

enum IOSRecipeExperienceFeedbackAvailability: Equatable, Sendable {
    case available(IOSRecipeExecutionEvidence)
    case unavailable(String)

    var canSubmit: Bool {
        if case .available = self { return true }
        return false
    }

    var reason: String {
        switch self {
        case .available(let evidence):
            "已找到该版本的成功执行记录（run \(String(evidence.runId.prefix(8)))）。"
        case .unavailable(let reason):
            reason
        }
    }
}

struct IOSRecipeExperienceFeedbackReceipt: Equatable, Sendable {
    let kind: IOSRecipeExperienceFeedbackKind
    let experienceId: String?
    let suggestion: IOSExperienceActionSuggestion?
}

enum IOSRecipeExperienceFeedbackOutcome: Equatable, Sendable {
    case recorded(IOSRecipeExperienceFeedbackReceipt)
    case unavailable(String)
    case failed(String)
}

/// One production bridge from exact-version Recipe execution facts to the
/// existing bounded Experience curator. It owns no outcome/history database:
/// execution and feedback stay in the ledger; only the curated rule is stored
/// in `IOSEvolutionExperienceStore`.
@MainActor
struct IOSRecipeExperienceFeedbackService {
    private static let maximumDescriptionCharacters = 240

    let dao: AgentRuntimeDao
    let ledger: IOSAgentRunLedger
    let store: IOSEvolutionExperienceStore
    let curator: IOSEvolutionExperienceCurator

    init(
        dao: AgentRuntimeDao,
        ledger: IOSAgentRunLedger,
        store: IOSEvolutionExperienceStore
    ) {
        self.dao = dao
        self.ledger = ledger
        self.store = store
        self.curator = IOSEvolutionExperienceCurator(store: store)
    }

    static func production(baseDirectory: URL) -> IOSRecipeExperienceFeedbackService {
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        return IOSRecipeExperienceFeedbackService(
            dao: dao,
            ledger: IOSAgentRunLedger(dao: dao),
            store: IOSEvolutionExperienceStore(baseDirectory: baseDirectory)
        )
    }

    func availability(
        recipeName: String,
        version: String
    ) async -> IOSRecipeExperienceFeedbackAvailability {
        let artifactId = "recipe__\(recipeName)"
        guard let execution = await IOSEvolutionEvidenceProjector.latestSuccessfulRecipeExecution(
            artifactId: artifactId,
            artifactVersion: version,
            dao: dao
        ) else {
            return .unavailable("还没有找到 \(artifactId)@\(version) 的成功执行记录，暂不能评价。")
        }
        return .available(execution)
    }

    func record(
        _ kind: IOSRecipeExperienceFeedbackKind,
        recipeName: String,
        version: String,
        description: String
    ) async -> IOSRecipeExperienceFeedbackOutcome {
        let artifactId = "recipe__\(recipeName)"
        guard case .available(let execution) = await availability(
            recipeName: recipeName,
            version: version
        ) else {
            return .unavailable("该 Recipe 版本没有可归因的成功执行记录，未写入反馈。")
        }

        let existing: IOSEvolutionExperience?
        do {
            existing = try store.activeExperiences().first {
                $0.sourceArtifactId == artifactId && $0.sourceArtifactVersion == version
            }
        } catch {
            return .failed("读取 Experience 失败：\(error.localizedDescription)")
        }

        guard let feedbackEventId = await ledger.recordExperienceFeedback(
            runId: execution.runId,
            artifactId: artifactId,
            artifactVersion: version,
            signal: kind.signal.rawValue,
            sourceExecutionEventId: execution.eventId,
            experienceId: existing?.id
        ) else {
            return .failed("反馈未能写入运行账本；Experience 保持不变。")
        }

        let evidenceRefs = [
            IOSEvidenceRef(kind: .agentRun, id: execution.runId),
            IOSEvidenceRef(kind: .ledgerEvent, id: execution.eventId),
            IOSEvidenceRef(kind: .ledgerEvent, id: feedbackEventId),
        ]

        switch kind {
        case .helpful:
            if let existing {
                return feedbackOutcome(
                    curator.recordHelpful(experienceId: existing.id, evidenceRefs: evidenceRefs),
                    kind: kind
                )
            }
            let boundedDescription = Self.boundedDescription(description, fallback: recipeName)
            let outcome = curator.add(
                applicability: "任务与 Recipe「\(recipeName)」的描述匹配：\(boundedDescription)",
                counterexamples: ["任务与该 Recipe 描述不匹配时不适用。"],
                evidenceRefs: evidenceRefs,
                ruleText: "匹配该适用条件时，优先使用 \(artifactId)@\(version)。",
                sourceArtifactId: artifactId,
                sourceArtifactVersion: version,
                helpfulCount: 1
            )
            switch outcome {
            case .added(let experience, _):
                return .recorded(IOSRecipeExperienceFeedbackReceipt(
                    kind: kind,
                    experienceId: experience.id,
                    suggestion: nil
                ))
            case .merged(let report):
                return .recorded(IOSRecipeExperienceFeedbackReceipt(
                    kind: kind,
                    experienceId: report.experienceId,
                    suggestion: nil
                ))
            case .rejected(let error):
                return .failed("更新 Experience 失败：\(String(describing: error))")
            }

        case .harmful:
            guard let existing else {
                // 负反馈本身已是 durable evidence；未经 helpful 确认时不创建
                // 可注入 Experience，避免把“没解决”变成新规则。
                return .recorded(IOSRecipeExperienceFeedbackReceipt(
                    kind: kind,
                    experienceId: nil,
                    suggestion: nil
                ))
            }
            return feedbackOutcome(
                curator.recordHarmful(experienceId: existing.id, evidenceRefs: evidenceRefs),
                kind: kind
            )
        }
    }

    private func feedbackOutcome(
        _ outcome: IOSExperienceFeedbackOutcome,
        kind: IOSRecipeExperienceFeedbackKind
    ) -> IOSRecipeExperienceFeedbackOutcome {
        switch outcome {
        case .recorded(let experience, let suggestion):
            return .recorded(IOSRecipeExperienceFeedbackReceipt(
                kind: kind,
                experienceId: experience.id,
                suggestion: suggestion
            ))
        case .rejected(let error):
            return .failed("更新 Experience 失败：\(String(describing: error))")
        }
    }

    private static func boundedDescription(_ description: String, fallback: String) -> String {
        let trimmed = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let source = trimmed.isEmpty ? fallback : trimmed
        guard source.count > maximumDescriptionCharacters else { return source }
        return String(source.prefix(maximumDescriptionCharacters)) + "…"
    }
}

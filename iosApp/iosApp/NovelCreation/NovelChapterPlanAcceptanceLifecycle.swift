import Foundation

extension DefaultNovelCreation {
    /// Structured contract check for a whole-chapter prose candidate.
    /// Uses the project's review model policy (falls back to App default when unset).
    func acceptChapterPlan(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID
    ) async throws -> NovelChapterPlanAcceptanceV1 {
        try await recoverGenerationStateIfNeeded(requiredProjectID: projectID)
        let loaded = try await loadCommittedProject(id: projectID)
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: projectID)
        }
        guard let branch = loaded.document.branches.first(where: {
            $0.id == branchID && $0.lifecycle == .active
        }) else {
            throw NovelError.branchNotFound(branchID)
        }
        guard branch.activeRunID == nil else {
            throw NovelError.projectBusy(projectID)
        }
        guard let plan = loaded.document.confirmedChapterPlan(for: branch.id) else {
            throw NovelError.invalidInput("代笔验收需要已确认的本章合同。")
        }
        guard let candidate = loaded.document.candidates.first(where: {
            $0.id == candidateID &&
                $0.branchID == branch.id &&
                $0.kind == .prose
        }) else {
            throw NovelError.invalidInput("找不到要验收的正文候选。")
        }
        guard candidate.status == .available else {
            throw NovelError.invalidInput("只有完整可用的正文候选可以验收。")
        }
        guard let boundDigest = candidate.chapterPlanDigest,
              boundDigest == plan.contentDigest else {
            throw NovelError.invalidInput("候选未绑定当前确认合同，无法自动验收。")
        }
        let trimmed = candidate.content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              !NovelParagraphParser.paragraphs(in: candidate.content).isEmpty else {
            throw NovelError.invalidInput("候选正文不完整，无法验收。")
        }
        let recentHighlights = loaded.document.stateSnapshots
            .first(where: { $0.id == branch.currentStateSnapshotID })?
            .injectionHighlightsText() ?? ""

        let executor = NovelStructuredModelExecutor(modelRunner: modelRunner)
        let preparation = try await executor.prepare(
            modelPolicy: modelPolicy(for: .review, in: loaded.document),
            taskKind: .chapterPlanAcceptance,
            requestedInputBudgetTokens: NovelStructuredModelExecutor
                .maximumInternalInputBudgetTokens
        )
        let request = NovelStructuredModelExecutionRequest(
            runID: NovelRunID(),
            modelPolicy: preparation.modelPolicy,
            task: .chapterPlanAcceptance(
                plan: plan.injectionText(),
                candidate: candidate.content,
                recentHighlights: recentHighlights
            )
        )
        let evidence = try await executor.executePrepared(
            try executor.prepareInvocation(request, preparation: preparation),
            noOutputTimeout: factRequestTimeout
        )
        guard case .chapterPlanAcceptance(let result) = evidence.output else {
            throw NovelStructuredModelExecutionFailure(
                code: "invalid_structured_output",
                message: "本章合同验收返回了错误的结构。",
                isRetryable: true
            )
        }
        return result
    }
}

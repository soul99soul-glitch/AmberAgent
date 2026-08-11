import Foundation

extension DefaultNovelCreation {
    /// 代笔多章：用创作模型根据总纲/状态/下一弧自动拟下一章合同，并直接确认落盘。
    /// 批循环第 2～N 章、以及完批后「下一批」首章自动拟定走此路径。
    @discardableResult
    func proposeAndConfirmNextChapterPlan(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        nextChapterOrdinal: Int,
        previousPlanSummary: String?
    ) async throws -> NovelChapterPlanRecord {
        try await proposeNextChapterPlan(
            projectID: projectID,
            branchID: branchID,
            nextChapterOrdinal: nextChapterOrdinal,
            previousPlanSummary: previousPlanSummary,
            status: .confirmed
        )
    }

    /// 首次/无确认计划：根据前文生成**草稿**本章计划，仍须用户点确认后才能开代笔。
    @discardableResult
    func proposeNextChapterPlanDraft(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        nextChapterOrdinal: Int,
        previousPlanSummary: String?
    ) async throws -> NovelChapterPlanRecord {
        try await proposeNextChapterPlan(
            projectID: projectID,
            branchID: branchID,
            nextChapterOrdinal: nextChapterOrdinal,
            previousPlanSummary: previousPlanSummary,
            status: .draft
        )
    }

    /// 共用拟定：`status` 为 `.draft` 时落草稿；`.confirmed` 时直接确认（批内自动连写）。
    @discardableResult
    func proposeNextChapterPlan(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        nextChapterOrdinal: Int,
        previousPlanSummary: String?,
        status: NovelChapterPlanStatus
    ) async throws -> NovelChapterPlanRecord {
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
        guard branch.syncStatus == .synchronized else {
            throw NovelError.invalidInput("拟定下一章计划前，请先完成剧情同步。")
        }
        if loaded.document.confirmedChapterPlan(for: branch.id) != nil {
            throw NovelError.invalidInput("当前已有确认的本章计划，无需再自动拟定。")
        }

        let context = Self.chapterPlanProposalContext(
            document: loaded.document,
            branch: branch,
            nextChapterOrdinal: nextChapterOrdinal,
            previousPlanSummary: previousPlanSummary
        )
        let executor = NovelStructuredModelExecutor(modelRunner: modelRunner)
        let preparation = try await executor.prepare(
            modelPolicy: modelPolicy(for: .creation, in: loaded.document),
            taskKind: .chapterPlanProposal,
            requestedInputBudgetTokens: NovelStructuredModelExecutor
                .maximumInternalInputBudgetTokens
        )
        let request = NovelStructuredModelExecutionRequest(
            runID: NovelRunID(),
            modelPolicy: preparation.modelPolicy,
            task: .chapterPlanProposal(context: context)
        )
        let evidence = try await executor.executePrepared(
            try executor.prepareInvocation(request, preparation: preparation),
            noOutputTimeout: factRequestTimeout
        )
        guard case .chapterPlanProposal(let proposal) = evidence.output else {
            throw NovelStructuredModelExecutionFailure(
                code: "invalid_structured_output",
                message: "下一章计划拟定返回了错误的结构。",
                isRetryable: true
            )
        }

        // 与 ViewModel.upsertChapterPlan 一致：分支已有 plan 时复用 ID，
        // 否则「重新生成草稿」/ 残留 draft 后再 auto-confirm 会撞 reducer 的不同 ID 拒绝。
        let planID = loaded.document.chapterPlan(for: branchID)?.id ?? NovelChapterPlanID()
        _ = try await perform(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: loaded.document.project.revision,
                expectedConfigRevision: loaded.document.project.configRevision,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: projectID,
            branchID: branchID,
            planID: planID,
            status: status,
            outlinePlacement: proposal.outlinePlacement,
            goalAndConflict: proposal.goalAndConflict,
            mustHappen: proposal.mustHappen,
            mustNotHappen: proposal.mustNotHappen,
            endingHook: proposal.endingHook,
            visibleFacts: proposal.visibleFacts
        )))

        let refreshed = try await loadCommittedProject(id: projectID)
        guard let plan = refreshed.document.chapterPlan(for: branchID),
              plan.status == status else {
            throw NovelError.invalidInput(
                status == .confirmed
                    ? "自动拟定的本章计划未能确认保存。"
                    : "自动拟定的本章计划草稿未能保存。"
            )
        }
        return plan
    }

    static func chapterPlanProposalContext(
        document: NovelProjectDocumentV1,
        branch: NovelBranchRecord,
        nextChapterOrdinal: Int,
        previousPlanSummary: String?
    ) -> String {
        var sections: [String] = []
        sections.append("NEXT CHAPTER ORDINAL\n\(max(1, nextChapterOrdinal))")

        if let outline = materialText(kind: .masterOutline, in: document) {
            sections.append("MASTER OUTLINE\n" + clip(outline, limit: 6_000))
        }
        if let requirements = materialText(kind: .writingRequirements, in: document) {
            sections.append("WRITING REQUIREMENTS\n" + clip(requirements, limit: 2_000))
        }

        if let state = document.stateSnapshots.first(where: { $0.id == branch.currentStateSnapshotID }) {
            var stateLines: [String] = []
            let summary = state.summary.trimmingCharacters(in: .whitespacesAndNewlines)
            if !summary.isEmpty {
                stateLines.append("Summary:\n" + clip(summary, limit: 3_000))
            }
            let outline = state.branchOutline.trimmingCharacters(in: .whitespacesAndNewlines)
            if !outline.isEmpty {
                stateLines.append("Branch outline:\n" + clip(outline, limit: 2_000))
            }
            let highlights = state.injectionHighlightsText()
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !highlights.isEmpty {
                stateLines.append("Recent written beats:\n" + clip(highlights, limit: 2_000))
            }
            if !stateLines.isEmpty {
                sections.append("CURRENT STORY STATE\n" + stateLines.joined(separator: "\n\n"))
            }
        }

        if let arc = document.upcomingArc(for: branch.id), !arc.beats.isEmpty {
            sections.append(
                "UPCOMING ARC\n" + arc.beats.map { "- \($0)" }.joined(separator: "\n")
            )
        }

        if let previous = previousPlanSummary?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !previous.isEmpty {
            sections.append("PREVIOUS CHAPTER PLAN SUMMARY\n" + clip(previous, limit: 2_000))
        }

        let chapterCount = branch.workingChapterSelections.count
        sections.append("CANON CHAPTER COUNT ON BRANCH\n\(chapterCount)")

        return sections.joined(separator: "\n\n")
    }

    private static func materialText(
        kind: NovelMaterialKind,
        in document: NovelProjectDocumentV1
    ) -> String? {
        let materials = document.materials.filter { $0.kind == kind && !$0.isDeleted }
        var chunks: [String] = []
        for material in materials {
            guard let revision = document.materialRevisions.first(where: {
                $0.id == material.currentRevisionID
            }) else { continue }
            let body = revision.content.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !body.isEmpty else { continue }
            let title = revision.title.trimmingCharacters(in: .whitespacesAndNewlines)
            chunks.append(title.isEmpty ? body : "\(title)\n\(body)")
        }
        guard !chunks.isEmpty else { return nil }
        return chunks.joined(separator: "\n\n")
    }

    private static func clip(_ text: String, limit: Int) -> String {
        guard text.count > limit else { return text }
        return String(text.prefix(limit)) + "…"
    }
}

extension NovelChapterPlanRecord {
    /// 短摘要，供批循环自动拟下一章计划注入。
    func ghostwriteBatchSummary() -> String {
        var lines = [
            "Placement: \(outlinePlacement)",
            "Goal: \(goalAndConflict)",
        ]
        if !mustHappen.isEmpty {
            lines.append("Must happen: " + mustHappen.joined(separator: "；"))
        }
        if !mustNotHappen.isEmpty {
            lines.append("Must not: " + mustNotHappen.joined(separator: "；"))
        }
        let hook = endingHook.trimmingCharacters(in: .whitespacesAndNewlines)
        if !hook.isEmpty {
            lines.append("Ending hook: \(hook)")
        }
        return lines.joined(separator: "\n")
    }
}

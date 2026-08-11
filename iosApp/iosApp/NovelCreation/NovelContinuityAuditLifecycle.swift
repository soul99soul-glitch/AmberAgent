import Foundation

extension DefaultNovelCreation {
    func planContinuityAudit(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelContinuityAuditPlan {
        let prepared = try await prepareContinuityAudit(
            projectID: projectID,
            branchID: branchID
        )
        return NovelContinuityAuditPlan(
            projectID: projectID,
            branchID: branchID,
            chapterCount: prepared.chapters.count,
            chunkCount: prepared.chunks.count,
            totalCharacterCount: prepared.chapters.reduce(0) { $0 + $1.content.count }
        )
    }

    func auditContinuity(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelContinuityAuditReport {
        let prepared = try await prepareContinuityAudit(
            projectID: projectID,
            branchID: branchID
        )
        return try await runPreparedContinuityAudit(
            prepared,
            projectID: projectID,
            branchID: branchID
        )
    }

    func auditContinuityIncludingCandidate(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID,
        maxPriorManuscriptChapters: Int?
    ) async throws -> NovelContinuityAuditReport {
        let prepared = try await prepareContinuityAuditIncludingCandidate(
            projectID: projectID,
            branchID: branchID,
            candidateID: candidateID,
            maxPriorManuscriptChapters: maxPriorManuscriptChapters
        )
        return try await runPreparedContinuityAudit(
            prepared,
            projectID: projectID,
            branchID: branchID
        )
    }
}

private extension DefaultNovelCreation {
    struct PreparedContinuityAudit {
        let branch: NovelBranchRecord
        /// 扫描覆盖的章节清单(含正文为空的章),过期判断按它比对。
        let auditedSelections: [NovelChapterSelection]
        let chapters: [NovelContinuityAuditChapter]
        let chunks: [NovelContinuityAuditChunk]
        let preparation: NovelStructuredModelPreparation
        let ledgerBudgetTokens: Int
    }

    func runPreparedContinuityAudit(
        _ prepared: PreparedContinuityAudit,
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelContinuityAuditReport {
        let executor = NovelStructuredModelExecutor(modelRunner: modelRunner)
        let promptVersion = NovelPromptCatalog.template(for: .continuityAuditV1).version

        var issues: [NovelContinuityIssue] = []
        var droppedIssueCount = 0
        var failedChunkCount = 0
        var lastFailure: Error?
        for chunk in prepared.chunks {
            try Task.checkCancellation()
            do {
                let audit = try await runContinuityAuditChunkWithRetry(
                    chunk,
                    prepared: prepared,
                    priorIssues: issues,
                    executor: executor
                )
                let mapped = NovelContinuityAuditMapper.map(
                    audit,
                    chunkIndex: chunk.index,
                    chapters: prepared.chapters
                )
                issues.append(contentsOf: mapped.issues)
                droppedIssueCount += mapped.droppedCount
            } catch is CancellationError {
                throw CancellationError()
            } catch let structured as NovelStructuredModelExecutionFailure
                where structured.failure.code == "cancelled" {
                // 取消不得记成 incomplete 软失败,否则多块末段取消会误暂停代笔。
                throw structured
            } catch {
                // 一块失败不作废其余块:前面的块已经花过一次模型调用,把结果连同
                // 失败块数一起交出去,比让用户从头重扫诚实也便宜。
                // 可恢复错误已在块内有界重试;到这里仍失败才计入 incomplete。
                failedChunkCount += 1
                lastFailure = error
            }
        }
        if failedChunkCount == prepared.chunks.count, let lastFailure {
            throw lastFailure
        }

        return NovelContinuityAuditReport(
            projectID: projectID,
            branchID: branchID,
            auditedChapterSelections: prepared.auditedSelections,
            promptVersion: promptVersion,
            scannedChapterCount: prepared.chapters.count,
            chunkCount: prepared.chunks.count,
            failedChunkCount: failedChunkCount,
            issues: issues,
            droppedIssueCount: droppedIssueCount,
            createdAt: now()
        )
    }

    /// 单块有界重试:只对明确 `isRetryable` 的失败再试一次,取消立即透传。
    /// 不把部分失败抬成「整次审计 throw」——那会丢掉已成功块的结果。
    func runContinuityAuditChunkWithRetry(
        _ chunk: NovelContinuityAuditChunk,
        prepared: PreparedContinuityAudit,
        priorIssues: [NovelContinuityIssue],
        executor: NovelStructuredModelExecutor
    ) async throws -> NovelContinuityAuditV1 {
        var attempt = 0
        while true {
            attempt += 1
            do {
                return try await runContinuityAuditChunk(
                    chunk,
                    prepared: prepared,
                    priorIssues: priorIssues,
                    executor: executor
                )
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                guard attempt < continuityAuditChunkMaxAttempts,
                      isRetryableContinuityChunkFailure(error) else {
                    throw error
                }
            }
        }
    }

    /// 块级尝试次数:1 次初试 + 1 次可恢复重试。常量而非配置,避免旋钮膨胀。
    var continuityAuditChunkMaxAttempts: Int { 2 }

    func isRetryableContinuityChunkFailure(_ error: Error) -> Bool {
        if error is CancellationError { return false }
        if let structured = error as? NovelStructuredModelExecutionFailure {
            return structured.failure.isRetryable
                && structured.failure.code != "cancelled"
        }
        if let model = error as? NovelModelFailure {
            return model.isRetryable
        }
        return false
    }

    func runContinuityAuditChunk(
        _ chunk: NovelContinuityAuditChunk,
        prepared: PreparedContinuityAudit,
        priorIssues: [NovelContinuityIssue],
        executor: NovelStructuredModelExecutor
    ) async throws -> NovelContinuityAuditV1 {
        // 台账只在多块时才有意义:单块扫描时模型本来就看得到全书。
        let priorFindings = prepared.chunks.count > 1
            ? NovelContinuityAuditMapper.priorFindingsDigest(
                priorIssues,
                maximumTokens: prepared.ledgerBudgetTokens
            )
            : ""
        let request = NovelStructuredModelExecutionRequest(
            runID: NovelRunID(),
            modelPolicy: prepared.preparation.modelPolicy,
            task: .continuityAudit(
                priorFindings: priorFindings,
                manuscript: chunk.manuscript
            )
        )
        let evidence = try await executor.executePrepared(
            try executor.prepareInvocation(request, preparation: prepared.preparation),
            noOutputTimeout: factRequestTimeout
        )
        guard case .continuityAudit(let audit) = evidence.output else {
            throw NovelStructuredModelExecutionFailure(
                code: "invalid_structured_output",
                message: "剧情矛盾检查返回了错误的结构。",
                isRetryable: true
            )
        }
        return audit
    }

    func prepareContinuityAudit(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> PreparedContinuityAudit {
        let loaded = try await loadContinuityAuditContext(
            projectID: projectID,
            branchID: branchID
        )
        let chapters = try continuityAuditChapters(
            branch: loaded.branch,
            discardedChapterIDs: loaded.discardedChapterIDs,
            document: loaded.document
        )
        guard !chapters.isEmpty else {
            throw NovelError.invalidInput("当前分支还没有正文可供检查。")
        }
        return try await finalizePreparedContinuityAudit(
            branch: loaded.branch,
            document: loaded.document,
            chapters: chapters,
            auditedSelections: loaded.auditedSelections
        )
    }

    func prepareContinuityAuditIncludingCandidate(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID,
        maxPriorManuscriptChapters: Int?
    ) async throws -> PreparedContinuityAudit {
        let loaded = try await loadContinuityAuditContext(
            projectID: projectID,
            branchID: branchID
        )
        guard let candidate = loaded.document.candidates.first(where: {
            $0.id == candidateID && $0.branchID == branchID
        }) else {
            throw NovelError.invalidInput("找不到用于连续性检查的候选。")
        }
        guard candidate.kind == .prose, candidate.status == .available else {
            throw NovelError.invalidInput("只有可用的正文候选才能做代笔连续性检查。")
        }
        let content = candidate.content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !content.isEmpty else {
            throw NovelError.invalidInput("候选正文为空，无法做连续性检查。")
        }

        let manuscriptChapters = NovelContinuityAuditScope.priorManuscriptChapters(
            try continuityAuditChapters(
                branch: loaded.branch,
                discardedChapterIDs: loaded.discardedChapterIDs,
                document: loaded.document
            ),
            maxPrior: maxPriorManuscriptChapters
        )
        let placement = loaded.document.confirmedChapterPlan(for: branchID)?
            .outlinePlacement
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        var chapters = manuscriptChapters
        chapters.append(NovelContinuityAuditChapter(
            chapterID: NovelChapterID(),
            ordinal: loaded.branch.workingChapterSelections.count + 1,
            title: placement.isEmpty ? "候选下一章" : placement,
            content: candidate.content
        ))
        // 过期判断仍用全书 eligible：近距门只缩小扫描窗，不改「分支正文清单」权威。
        return try await finalizePreparedContinuityAudit(
            branch: loaded.branch,
            document: loaded.document,
            chapters: chapters,
            auditedSelections: loaded.auditedSelections
        )
    }

    struct ContinuityAuditLoadContext {
        let document: NovelProjectDocumentV1
        let branch: NovelBranchRecord
        let discardedChapterIDs: Set<NovelChapterID>
        let auditedSelections: [NovelChapterSelection]
    }

    func loadContinuityAuditContext(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> ContinuityAuditLoadContext {
        try await recoverGenerationStateIfNeeded(requiredProjectID: projectID)
        let loaded = try await loadCommittedProject(id: projectID)
        guard let branch = loaded.document.branches.first(where: {
            $0.id == branchID && $0.lifecycle == .active
        }) else {
            throw NovelError.branchNotFound(branchID)
        }
        // 只要求分支空闲,不要求剧情状态已同步:矛盾检查读的是正文逐字原文,跟状态
        // 摘要没关系,让「状态没同步」挡住一次纯粹的正文自查是没有道理的。
        guard branch.activeRunID == nil else {
            throw NovelError.projectBusy(projectID)
        }

        let discarded = Set(
            loaded.document.chapters.filter { $0.discardedAt != nil }.map(\.id)
        )
        let auditedSelections = NovelContinuityAuditReport.eligibleSelections(
            in: branch,
            discardedChapterIDs: discarded
        )
        return ContinuityAuditLoadContext(
            document: loaded.document,
            branch: branch,
            discardedChapterIDs: discarded,
            auditedSelections: auditedSelections
        )
    }

    func finalizePreparedContinuityAudit(
        branch: NovelBranchRecord,
        document: NovelProjectDocumentV1,
        chapters: [NovelContinuityAuditChapter],
        auditedSelections: [NovelChapterSelection]
    ) async throws -> PreparedContinuityAudit {
        let executor = NovelStructuredModelExecutor(modelRunner: modelRunner)
        let preparation = try await executor.prepare(
            modelPolicy: modelPolicy(for: .review, in: document),
            taskKind: .continuityAudit,
            requestedInputBudgetTokens: NovelStructuredModelExecutor
                .maximumInternalInputBudgetTokens
        )
        // 给系统提示词与前序台账留出余量,剩下的才是正文能占的额度。
        let promptTokens = NovelContinuityAuditPlanner.estimatedTokens(
            NovelPromptCatalog.template(for: .continuityAuditV1).systemText
        )
        // 台账额度不能写死:小窗模型的整个输入预算可能还不到 2k,固定预留会把正文
        // 挤成负数,直接判「超预算」。按预算比例封顶,硬约束交给 digest 的截断。
        let ledgerBudget = min(
            continuityAuditLedgerReserveTokens,
            max(0, preparation.effectiveInputBudgetTokens / 4)
        )
        let manuscriptBudget = preparation.effectiveInputBudgetTokens - promptTokens - ledgerBudget
        guard manuscriptBudget > 0 else {
            throw NovelError.injectionBudgetExceeded(
                required: promptTokens + ledgerBudget,
                limit: preparation.effectiveInputBudgetTokens,
                items: [NovelInjectionBudgetItem(
                    label: "剧情矛盾检查提示词",
                    estimatedTokens: promptTokens
                )]
            )
        }
        let chunks = try NovelContinuityAuditPlanner.chunks(
            chapters: chapters,
            maximumChunkTokens: manuscriptBudget
        )
        return PreparedContinuityAudit(
            branch: branch,
            auditedSelections: auditedSelections,
            chapters: chapters,
            chunks: chunks,
            preparation: preparation,
            ledgerBudgetTokens: ledgerBudget
        )
    }

    /// 前序台账的预留额度上限。台账每条只有一行摘要,2k token 是上限而不是保证值,
    /// 真正的硬约束由 `priorFindingsDigest(_:maximumTokens:)` 的截断负责。
    var continuityAuditLedgerReserveTokens: Int { 2_048 }

    func continuityAuditChapters(
        branch: NovelBranchRecord,
        discardedChapterIDs: Set<NovelChapterID>,
        document: NovelProjectDocumentV1
    ) throws -> [NovelContinuityAuditChapter] {
        var result: [NovelContinuityAuditChapter] = []
        // 序号取分支章节选择里的原始位置,废弃章也占号 —— 与正文页的章号同一口径。
        for (index, selection) in branch.workingChapterSelections.enumerated() {
            guard !discardedChapterIDs.contains(selection.chapterID) else { continue }
            guard let version = document.chapterVersions.first(where: {
                $0.id == selection.versionID && $0.chapterID == selection.chapterID
            }) else {
                throw NovelError.invalidInput("当前分支引用了一个不存在的章节版本。")
            }
            // 只有空白字符的章等同于空章:占额度、又必然没有可锚定的证据。
            guard !version.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                continue
            }
            result.append(NovelContinuityAuditChapter(
                chapterID: selection.chapterID,
                ordinal: index + 1,
                title: version.title,
                content: version.content
            ))
        }
        return result
    }
}

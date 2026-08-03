package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelActiveRunRecord
import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateKind
import app.amber.feature.novel.model.NovelCandidateRecord
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelFailure
import app.amber.feature.novel.model.NovelGenerationGranularity
import app.amber.feature.novel.model.NovelGenerationReceiptRecord
import app.amber.feature.novel.model.NovelInjectionReceiptRecord
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOperationKind
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProposalId
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelRunInterruptionReason
import app.amber.feature.novel.model.NovelRunKind
import app.amber.feature.novel.model.NovelRunStatus
import app.amber.feature.novel.model.NovelSessionMessageKind
import app.amber.feature.novel.model.NovelSessionMessageRecord
import app.amber.feature.novel.model.NovelSessionMode
import app.amber.feature.novel.model.NovelSessionRole
import app.amber.feature.novel.model.NovelSettingProposalOrigin
import app.amber.feature.novel.model.NovelSettingProposalRecord
import app.amber.feature.novel.serialization.normalizeUuidString
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import java.time.Instant
import java.util.UUID

data class NovelInternalRunRequest(
    val id: NovelRunId,
    val operationID: NovelOperationId,
    val projectID: app.amber.feature.novel.model.NovelProjectId,
    val branchID: app.amber.feature.novel.model.NovelBranchId,
    val kind: NovelRunKind,
    val mode: NovelSessionMode,
    val granularity: NovelGenerationGranularity?,
    val userText: String,
    val userMessageID: app.amber.feature.novel.model.NovelMessageId,
    val assistantMessageID: app.amber.feature.novel.model.NovelMessageId,
    val candidateID: app.amber.feature.novel.model.NovelCandidateId?,
    val generationReceiptID: app.amber.feature.novel.model.NovelReceiptId,
    val injectionReceiptID: app.amber.feature.novel.model.NovelReceiptId,
    val sourceChapterVersionID: app.amber.feature.novel.model.NovelChapterVersionId?,
    val expectedProjectRevision: Long,
    val expectedConfigRevision: Long,
    val expectedBranchHeadRevision: Long,
    val requestPayloadSHA256: String,
)

data class NovelGenerationStartArtifacts(
    val injectionReceipt: NovelInjectionReceiptRecord,
    val generationReceipt: NovelGenerationReceiptRecord,
)

data class NovelMessageSnapshot(
    val projectId: app.amber.feature.novel.model.NovelProjectId,
    val branchId: app.amber.feature.novel.model.NovelBranchId,
    val message: NovelSessionMessageRecord,
)

object NovelGenerationReducer {
    fun begin(
        request: NovelInternalRunRequest,
        artifacts: NovelGenerationStartArtifacts,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        require(request.projectID == document.project.id)
        val branchIndex = document.branches.indexOfFirst { it.id == request.branchID }
        if (branchIndex < 0) throw NovelError.BranchNotFound(request.branchID)
        val branch = document.branches[branchIndex]
        if (branch.lifecycle != NovelBranchLifecycle.Active) throw NovelError.BranchNotFound(request.branchID)
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync &&
            request.kind != NovelRunKind.Discussion &&
            request.kind != NovelRunKind.QuickStart
        ) {
            throw NovelError.InvalidInput("Branch needs sync before formal generation.")
        }
        if (request.expectedProjectRevision != document.project.revision) {
            throw NovelError.StaleProjectRevision(request.expectedProjectRevision, document.project.revision)
        }
        if (request.expectedConfigRevision != document.project.configRevision) {
            throw NovelError.StaleConfigRevision(request.expectedConfigRevision, document.project.configRevision)
        }
        if (request.expectedBranchHeadRevision != branch.headRevision) {
            throw NovelError.StaleBranchHeadRevision(request.expectedBranchHeadRevision, branch.headRevision)
        }
        if (branch.activeRunID != null) throw NovelError.ProjectBusy(document.project.id)
        if (document.activeRuns.any { it.id == request.id && it.status == NovelRunStatus.Running }) {
            throw NovelError.ProjectBusy(document.project.id)
        }

        val sessionIndex = document.sessions.indexOfFirst {
            it.id == branch.sessionID && it.branchID == branch.id
        }
        if (sessionIndex < 0) throw NovelError.SessionNotFound(branch.sessionID)

        val session = document.sessions[sessionIndex]
        val userMessage = NovelSessionMessageRecord(
            id = request.userMessageID,
            sequence = session.messages.size.toLong(),
            role = NovelSessionRole.User,
            mode = request.mode,
            kind = NovelSessionMessageKind.UserInput,
            content = request.userText,
            createdAt = now,
            runID = request.id,
            candidateID = null,
        )
        val activeRun = NovelActiveRunRecord(
            id = request.id,
            operationID = request.operationID,
            requestPayloadSHA256 = request.requestPayloadSHA256,
            branchID = request.branchID,
            sessionID = session.id,
            kind = request.kind,
            mode = request.mode,
            granularity = request.granularity,
            userMessageID = request.userMessageID,
            messageID = request.assistantMessageID,
            candidateID = request.candidateID,
            sourceChapterVersionID = request.sourceChapterVersionID,
            baseCheckpointID = branch.headCheckpointID,
            baseHeadRevision = branch.headRevision,
            status = NovelRunStatus.Running,
            partialContent = "",
            receiptID = request.generationReceiptID,
            startedAt = now,
        )

        val sessions = document.sessions.toMutableList()
        sessions[sessionIndex] = session.copy(
            messages = session.messages + userMessage,
            revision = session.revision + 1,
        )
        val branches = document.branches.toMutableList()
        branches[branchIndex] = branch.copy(activeRunID = request.id, updatedAt = now)
        val project = document.project.copy(
            revision = document.project.revision + 1,
            updatedAt = now,
            lastGenerationGranularity = request.granularity
                ?: document.project.lastGenerationGranularity,
        )
        val outcome = NovelOutcome.RunStarted(
            projectID = request.projectID,
            branchID = request.branchID,
            runID = request.id,
            receiptID = request.generationReceiptID,
            revision = project.revision,
        )
        val next = document.copy(
            project = project,
            branches = branches,
            sessions = sessions,
            activeRuns = document.activeRuns + activeRun,
            injectionReceipts = document.injectionReceipts + artifacts.injectionReceipt,
            generationReceipts = document.generationReceipts + artifacts.generationReceipt,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = request.operationID,
                kind = NovelOperationKind.StartRun,
                payloadSHA256 = request.requestPayloadSHA256,
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun complete(
        runId: NovelRunId,
        content: String,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): Pair<NovelProjectDocumentV1, NovelMessageSnapshot?> {
        val runIndex = document.activeRuns.indexOfFirst { it.id == runId }
        if (runIndex < 0) return document to null
        val run = document.activeRuns[runIndex]
        if (run.status != NovelRunStatus.Running) return document to null
        if (content.isBlank()) throw NovelError.InvalidInput("A completed generation cannot be empty.")

        val branchIndex = document.branches.indexOfFirst { it.id == run.branchID }
        if (branchIndex < 0 || document.branches[branchIndex].activeRunID != run.id) {
            return document to null
        }
        val sessionIndex = document.sessions.indexOfFirst {
            it.id == run.sessionID && it.branchID == run.branchID
        }
        if (sessionIndex < 0) throw NovelError.SessionNotFound(run.sessionID)

        val messageKind = when (run.kind) {
            NovelRunKind.QuickStart, NovelRunKind.Discussion -> NovelSessionMessageKind.Discussion
            NovelRunKind.Prose, NovelRunKind.Regenerate -> NovelSessionMessageKind.ProseCandidate
            NovelRunKind.Polish -> NovelSessionMessageKind.PolishCandidate
        }
        // QuickStart expects strict JSON; if the model returns free text, keep the message
        // as discussion content instead of failing the entire run.
        val quickStart = if (run.kind == NovelRunKind.QuickStart) {
            runCatching { NovelStructuredOutputDecoder.decodeQuickStartSuggestions(content) }.getOrNull()
        } else null
        val messageContent = quickStart?.let { toQuickStartMarkdown(it) } ?: content
        val message = NovelSessionMessageRecord(
            id = run.messageID,
            sequence = document.sessions[sessionIndex].messages.size.toLong(),
            role = NovelSessionRole.Assistant,
            mode = run.mode,
            kind = messageKind,
            content = messageContent,
            createdAt = now,
            runID = run.id,
            candidateID = run.candidateID,
        )

        var next = document
        val sessions = next.sessions.toMutableList()
        val session = sessions[sessionIndex]
        sessions[sessionIndex] = session.copy(
            messages = session.messages + message,
            revision = session.revision + 1,
        )
        next = next.copy(sessions = sessions)

        if (quickStart != null) {
            next = next.copy(
                settingProposals = next.settingProposals + quickStartProposals(quickStart, run, now),
            )
        }
        run.candidateID?.let { candidateId ->
            next = next.copy(
                candidates = next.candidates + NovelCandidateRecord(
                    id = candidateId,
                    kind = if (run.kind == NovelRunKind.Polish) {
                        NovelCandidateKind.Polish
                    } else {
                        NovelCandidateKind.Prose
                    },
                    branchID = run.branchID,
                    sessionID = run.sessionID,
                    sourceMessageID = run.messageID,
                    baseCheckpointID = run.baseCheckpointID,
                    baseHeadRevision = run.baseHeadRevision,
                    status = NovelCandidateStatus.Available,
                    content = content,
                    sourceChapterVersionID = run.sourceChapterVersionID,
                    createdAt = now,
                ),
            )
        }
        next = finishRun(next, runIndex, branchIndex, NovelRunStatus.Completed, content, null, null, now)
        NovelDocumentValidator.validateTransition(document, next)
        return next to NovelMessageSnapshot(next.project.id, run.branchID, message)
    }

    fun interrupt(
        runId: NovelRunId,
        reason: NovelRunInterruptionReason,
        partialContent: String,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): Pair<NovelProjectDocumentV1, NovelMessageSnapshot?> {
        val runIndex = document.activeRuns.indexOfFirst { it.id == runId }
        if (runIndex < 0) return document to null
        val run = document.activeRuns[runIndex]
        if (run.status != NovelRunStatus.Running) return document to null
        val branchIndex = document.branches.indexOfFirst { it.id == run.branchID }
        if (branchIndex < 0) return document to null

        val sessionIndex = document.sessions.indexOfFirst {
            it.id == run.sessionID && it.branchID == run.branchID
        }
        var next = document
        var messageSnap: NovelMessageSnapshot? = null
        if (partialContent.isNotBlank() && sessionIndex >= 0) {
            val message = NovelSessionMessageRecord(
                id = run.messageID,
                sequence = document.sessions[sessionIndex].messages.size.toLong(),
                role = NovelSessionRole.Assistant,
                mode = run.mode,
                kind = NovelSessionMessageKind.InterruptedDraft,
                content = partialContent,
                createdAt = now,
                runID = run.id,
                candidateID = run.candidateID,
            )
            val sessions = next.sessions.toMutableList()
            val session = sessions[sessionIndex]
            sessions[sessionIndex] = session.copy(
                messages = session.messages + message,
                revision = session.revision + 1,
            )
            next = next.copy(sessions = sessions)
            messageSnap = NovelMessageSnapshot(next.project.id, run.branchID, message)

            // Persist an Interrupted prose/regenerate candidate so partial text can still be collected.
            // Polish keeps adopt-only semantics and does not create a collectable candidate here.
            val candidateId = run.candidateID
            if (candidateId != null &&
                (run.kind == NovelRunKind.Prose || run.kind == NovelRunKind.Regenerate) &&
                next.candidates.none { it.id == candidateId }
            ) {
                next = next.copy(
                    candidates = next.candidates + NovelCandidateRecord(
                        id = candidateId,
                        kind = NovelCandidateKind.Prose,
                        branchID = run.branchID,
                        sessionID = run.sessionID,
                        sourceMessageID = run.messageID,
                        baseCheckpointID = run.baseCheckpointID,
                        baseHeadRevision = run.baseHeadRevision,
                        status = NovelCandidateStatus.Interrupted,
                        content = partialContent,
                        sourceChapterVersionID = run.sourceChapterVersionID,
                        createdAt = now,
                    ),
                )
            }
        }
        next = finishRun(
            next, runIndex, branchIndex, NovelRunStatus.Interrupted,
            partialContent, reason, null, now,
        )
        next = next.copy(
            appliedOperations = next.appliedOperations + NovelAppliedOperationRecord(
                operationID = NovelOperationId.generate(),
                kind = NovelOperationKind.CancelRun,
                payloadSHA256 = sha256HexOfUtf8("interrupt:${runId.rawValue}:${reason.name}"),
                outcome = NovelOutcome.RunInterrupted(
                    projectID = next.project.id,
                    runID = runId,
                    reason = reason,
                    revision = next.project.revision,
                ),
                appliedProjectRevision = next.project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return next to messageSnap
    }

    fun fail(
        runId: NovelRunId,
        failure: NovelFailure,
        partialContent: String,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): Pair<NovelProjectDocumentV1, NovelMessageSnapshot?> {
        val runIndex = document.activeRuns.indexOfFirst { it.id == runId }
        if (runIndex < 0) return document to null
        val run = document.activeRuns[runIndex]
        if (run.status != NovelRunStatus.Running) return document to null
        val branchIndex = document.branches.indexOfFirst { it.id == run.branchID }
        if (branchIndex < 0) return document to null
        val sessionIndex = document.sessions.indexOfFirst {
            it.id == run.sessionID && it.branchID == run.branchID
        }
        var next = document
        var messageSnap: NovelMessageSnapshot? = null
        if (sessionIndex >= 0) {
            val message = NovelSessionMessageRecord(
                id = run.messageID,
                sequence = document.sessions[sessionIndex].messages.size.toLong(),
                role = NovelSessionRole.Assistant,
                mode = run.mode,
                kind = NovelSessionMessageKind.Error,
                content = failure.message,
                createdAt = now,
                runID = run.id,
                candidateID = null,
            )
            val sessions = next.sessions.toMutableList()
            val session = sessions[sessionIndex]
            sessions[sessionIndex] = session.copy(
                messages = session.messages + message,
                revision = session.revision + 1,
            )
            next = next.copy(sessions = sessions)
            messageSnap = NovelMessageSnapshot(next.project.id, run.branchID, message)
        }
        next = finishRun(next, runIndex, branchIndex, NovelRunStatus.Failed, partialContent, null, failure, now)
        NovelDocumentValidator.validateTransition(document, next)
        return next to messageSnap
    }

    private fun finishRun(
        document: NovelProjectDocumentV1,
        runIndex: Int,
        branchIndex: Int,
        status: NovelRunStatus,
        content: String,
        interruptionReason: NovelRunInterruptionReason?,
        failure: NovelFailure?,
        now: Instant,
    ): NovelProjectDocumentV1 {
        val runs = document.activeRuns.toMutableList()
        runs[runIndex] = runs[runIndex].copy(
            status = status,
            partialContent = content,
            terminalAt = now,
            interruptionReason = interruptionReason,
            terminalFailure = failure,
        )
        val branches = document.branches.toMutableList()
        branches[branchIndex] = branches[branchIndex].copy(activeRunID = null, updatedAt = now)
        return document.copy(
            activeRuns = runs,
            branches = branches,
            project = document.project.copy(
                revision = document.project.revision + 1,
                updatedAt = now,
            ),
        )
    }

    private fun toQuickStartMarkdown(s: NovelQuickStartSuggestionsV1): String {
        val characterBlocks = s.characters.joinToString("\n\n") { item ->
            "## 人物：${item.title}\n\n${item.content}"
        }
        val body = listOf(
            "## 世界观：${s.world.title}\n\n${s.world.content}",
            characterBlocks,
            "## 总剧情大纲：${s.masterOutline.title}\n\n${s.masterOutline.content}",
            "## 写作要求：${s.writingRequirements.title}\n\n${s.writingRequirements.content}",
        ).joinToString("\n\n")
        return "# 创作建议\n\n${s.overview}\n\n$body"
    }

    private fun quickStartProposals(
        s: NovelQuickStartSuggestionsV1,
        run: NovelActiveRunRecord,
        now: Instant,
    ): List<NovelSettingProposalRecord> {
        val singles = listOf(
            Triple("world", NovelMaterialKind.World, s.world),
            Triple("master-outline", NovelMaterialKind.MasterOutline, s.masterOutline),
            Triple("writing-requirements", NovelMaterialKind.WritingRequirements, s.writingRequirements),
        )
        val characterItems = s.characters.mapIndexed { index, suggestion ->
            Triple("character-$index", NovelMaterialKind.Character, suggestion)
        }
        val items = singles + characterItems
        return items.map { (stable, kind, suggestion) ->
            NovelSettingProposalRecord(
                id = NovelProposalId(deterministicProposalId(run.operationID, stable)),
                branchID = run.branchID,
                title = suggestion.title,
                content = suggestion.content,
                createdAt = now,
                isResolved = false,
                origin = NovelSettingProposalOrigin.QuickStart(run.id, kind),
            )
        }
    }

    private fun deterministicProposalId(operationId: NovelOperationId, stableId: String): String {
        val hex = sha256HexOfUtf8("${operationId.rawValue}|quick-start-proposal|$stableId")
        val raw = buildString {
            append(hex.substring(0, 8)); append('-')
            append(hex.substring(8, 12)); append('-')
            append(hex.substring(12, 16)); append('-')
            append(hex.substring(16, 20)); append('-')
            append(hex.substring(20, 32))
        }
        return normalizeUuidString(raw)
    }
}

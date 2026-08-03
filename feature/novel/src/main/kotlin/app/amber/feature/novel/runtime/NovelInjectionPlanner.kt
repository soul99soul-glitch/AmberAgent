package app.amber.feature.novel.runtime

import app.amber.feature.novel.domain.NovelError
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelInjectionMode
import app.amber.feature.novel.model.NovelInjectionReceiptRecord
import app.amber.feature.novel.model.NovelInjectionReceiptSectionRecord
import app.amber.feature.novel.model.NovelInjectionSectionKind
import app.amber.feature.novel.model.NovelInjectionSelectionReason
import app.amber.feature.novel.model.NovelMaterialInjectionDecision
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelReceiptId
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import java.time.Instant

data class NovelInjectionPlan(
    val prompt: NovelPromptTemplate,
    val sections: List<PlannedSection>,
    val materialDecisions: List<NovelMaterialInjectionDecision>,
    val maxEstimatedInputTokens: Int,
    val estimatedInputTokens: Int,
    val contextText: String,
    val canonicalInput: String,
    val canonicalInputSHA256: String,
) {
    data class PlannedSection(
        val kind: NovelInjectionSectionKind,
        val label: String,
        val content: String,
        val reason: NovelInjectionSelectionReason,
        val estimatedTokens: Int,
        val contentSHA256: String,
    )
}

/**
 * Deterministic V1 injection planner (no embeddings).
 * Fixed prompt + current state + chapter tail + recent session + always materials + force includes.
 */
object NovelInjectionPlanner {
    fun plan(
        document: NovelProjectDocumentV1,
        branchId: NovelBranchId,
        promptKind: NovelPromptKind,
        userText: String,
        budgetTokens: Int = NovelInjectionDefaults.ESTIMATED_INPUT_TOKENS,
        chapterTailChars: Int = NovelInjectionDefaults.CHAPTER_TAIL_CHARS,
        recentMessages: Int = NovelInjectionDefaults.RECENT_SESSION_MESSAGES,
        forceIncludeMaterialIDs: Set<String> = emptySet(),
        forceExcludeMaterialIDs: Set<String> = emptySet(),
        sourceChapterVersionId: app.amber.feature.novel.model.NovelChapterVersionId? = null,
    ): NovelInjectionPlan {
        val branch = document.branches.firstOrNull { it.id == branchId && it.lifecycle == NovelBranchLifecycle.Active }
            ?: throw NovelError.BranchNotFound(branchId)
        if (branch.syncStatus.name == "NeedsSync" &&
            promptKind != NovelPromptKind.Discussion &&
            promptKind != NovelPromptKind.QuickStart
        ) {
            throw NovelError.InvalidInput("Branch needs sync before formal generation.")
        }

        val prompt = NovelPromptCatalog.template(promptKind)
        val sections = mutableListOf<NovelInjectionPlan.PlannedSection>()
        fun add(
            kind: NovelInjectionSectionKind,
            label: String,
            content: String,
            reason: NovelInjectionSelectionReason,
        ) {
            if (content.isBlank()) return
            val tokens = estimateTokens(content)
            sections += NovelInjectionPlan.PlannedSection(
                kind = kind,
                label = label,
                content = content,
                reason = reason,
                estimatedTokens = tokens,
                contentSHA256 = sha256HexOfUtf8(content),
            )
        }

        add(NovelInjectionSectionKind.FixedPrompt, "Prompt", prompt.systemText, NovelInjectionSelectionReason.RequiredPrompt)
        if (promptKind == NovelPromptKind.WholeChapterPolish && document.project.polishPreference.isNotBlank()) {
            add(
                NovelInjectionSectionKind.PolishPreference,
                "Polish preference",
                document.project.polishPreference,
                NovelInjectionSelectionReason.RequiredPolishPreference,
            )
        }

        document.project.quickStartSeed?.let { seed ->
            if (promptKind == NovelPromptKind.QuickStart) {
                add(
                    NovelInjectionSectionKind.QuickStartSeed,
                    "快速开始种子",
                    "题材：${seed.genre}\n核心想法：${seed.coreIdea}",
                    NovelInjectionSelectionReason.RequiredQuickStartSeed,
                )
            }
        }

        val state = document.stateSnapshots.firstOrNull { it.id == branch.currentStateSnapshotID }
        if (state != null) {
            val stateText = buildString {
                appendLine("Summary: ${state.summary}")
                appendLine("Outline: ${state.branchOutline}")
                if (state.unresolvedEntityNames.isNotEmpty()) {
                    appendLine("Unresolved: ${state.unresolvedEntityNames.joinToString()}")
                }
            }
            add(
                NovelInjectionSectionKind.CurrentState(state.id),
                "Current branch state",
                stateText,
                NovelInjectionSelectionReason.RequiredCurrentState,
            )
        }

        val selections = branch.workingChapterSelections
        if (promptKind == NovelPromptKind.WholeChapterRegeneration ||
            promptKind == NovelPromptKind.WholeChapterPolish
        ) {
            val source = sourceChapterVersionId?.let { id ->
                document.chapterVersions.firstOrNull { it.id == id }
            }
            if (source != null) {
                add(
                    NovelInjectionSectionKind.ChapterContext(source.id),
                    "Chapter to rewrite/polish: ${source.title}",
                    source.content,
                    NovelInjectionSelectionReason.RequiredCurrentState,
                )
            }
            // Also inject a short tail of previous chapter for continuity, if any.
            val idx = selections.indexOfFirst { it.versionID == sourceChapterVersionId }
            if (idx > 0) {
                val prev = document.chapterVersions.firstOrNull {
                    it.id == selections[idx - 1].versionID
                }
                if (prev != null) {
                    add(
                        NovelInjectionSectionKind.ChapterContext(prev.id),
                        "Previous chapter tail: ${prev.title}",
                        prev.content.takeLast(chapterTailChars),
                        NovelInjectionSelectionReason.CurrentChapterTail,
                    )
                }
            }
        } else if (selections.isNotEmpty() && promptKind != NovelPromptKind.QuickStart) {
            val last = selections.last()
            val version = document.chapterVersions.firstOrNull { it.id == last.versionID }
            if (version != null) {
                val tail = version.content.takeLast(chapterTailChars)
                add(
                    NovelInjectionSectionKind.ChapterContext(version.id),
                    "Chapter tail: ${version.title}",
                    tail,
                    NovelInjectionSelectionReason.CurrentChapterTail,
                )
            }
        }

        val session = document.sessions.firstOrNull { it.id == branch.sessionID }
        val archiveFloor = when (val c = session?.archiveCursor) {
            is app.amber.feature.novel.model.NovelSessionCursor.Through -> c.sequence
            else -> -1L
        }
        session?.messages
            ?.filter { it.sequence > archiveFloor }
            ?.takeLast(recentMessages)
            ?.forEach { msg ->
                add(
                    NovelInjectionSectionKind.SessionMessage(msg.id),
                    "Session ${msg.role}",
                    msg.content,
                    NovelInjectionSelectionReason.RecentSession,
                )
            }

        val materialDecisions = mutableListOf<NovelMaterialInjectionDecision>()
        for (material in document.materials.filter { !it.isDeleted }) {
            val revision = document.materialRevisions.firstOrNull { it.id == material.currentRevisionID }
                ?: continue
            val idRaw = material.id.rawValue
            val forcedIn = idRaw in forceIncludeMaterialIDs
            val forcedOut = idRaw in forceExcludeMaterialIDs
            val isDecisionLog =
                material.kind is app.amber.feature.novel.model.NovelMaterialKind.DecisionLog
            // DecisionLogs created by discussion archive live on branch.overrideRevisionIDs.
            // Requiring membership means undo (which rewinds overrides) stops injecting orphans.
            val decisionOnBranch =
                isDecisionLog && revision.id in branch.overrideRevisionIDs
            val include = when {
                forcedOut -> false
                forcedIn -> true
                decisionOnBranch -> true
                isDecisionLog -> false
                revision.injectionMode == NovelInjectionMode.Always -> true
                revision.injectionMode == NovelInjectionMode.Off -> false
                else -> false // smart without embedding: skip unless forced
            }
            val reason = when {
                forcedOut -> NovelInjectionSelectionReason.ForceExcluded
                forcedIn -> NovelInjectionSelectionReason.ForceIncluded
                revision.injectionMode == NovelInjectionMode.Always -> NovelInjectionSelectionReason.Always
                revision.injectionMode == NovelInjectionMode.Off -> NovelInjectionSelectionReason.Disabled
                else -> NovelInjectionSelectionReason.NoSmartMatch
            }
            materialDecisions += NovelMaterialInjectionDecision(
                materialID = material.id,
                revisionID = revision.id,
                included = include,
                reason = reason,
                relevanceScore = if (include) 1 else 0,
                estimatedTokens = estimateTokens(revision.content),
                contentSHA256 = sha256HexOfUtf8(revision.content),
            )
            if (include) {
                add(
                    NovelInjectionSectionKind.Material(revision.id),
                    "Material: ${revision.title}",
                    revision.content,
                    reason,
                )
            }
        }

        add(NovelInjectionSectionKind.UserInput, "User input", userText, NovelInjectionSelectionReason.RequiredUserInput)

        // Trim optional sections (oldest session messages, then non-required materials)
        // until under budget. Always keep system prompt + user input; fail only as last resort.
        trimSectionsToBudget(sections, materialDecisions, budgetTokens)

        val estimated = sections.sumOf { it.estimatedTokens }
        if (estimated > budgetTokens) {
            throw NovelError.InvalidInput(
                "Injection budget exceeded: required=$estimated limit=$budgetTokens",
            )
        }

        val contextText = sections.joinToString("\n\n---\n\n") { "${it.label}\n${it.content}" }
        val canonical = sections.joinToString("\n") { "${it.kind}:${it.contentSHA256}" }
        return NovelInjectionPlan(
            prompt = prompt,
            sections = sections,
            materialDecisions = materialDecisions,
            maxEstimatedInputTokens = budgetTokens,
            estimatedInputTokens = estimated,
            contextText = contextText,
            canonicalInput = canonical,
            canonicalInputSHA256 = sha256HexOfUtf8(canonical),
        )
    }

    /**
     * Drop lowest-priority sections until [sections] fit [budgetTokens].
     * Drop order: session messages (oldest first) → materials.
     * Never drops FixedPrompt / UserInput / other required sections.
     */
    private fun trimSectionsToBudget(
        sections: MutableList<NovelInjectionPlan.PlannedSection>,
        materialDecisions: MutableList<NovelMaterialInjectionDecision>,
        budgetTokens: Int,
    ) {
        fun total() = sections.sumOf { it.estimatedTokens }
        if (total() <= budgetTokens) return

        // 1) Drop oldest session messages first (indices ascending = oldest first).
        while (total() > budgetTokens) {
            val idx = sections.indexOfFirst { it.kind is NovelInjectionSectionKind.SessionMessage }
            if (idx < 0) break
            sections.removeAt(idx)
        }
        if (total() <= budgetTokens) return

        // 2) Drop material sections until under budget.
        while (total() > budgetTokens) {
            val idx = sections.indexOfLast { it.kind is NovelInjectionSectionKind.Material }
            if (idx < 0) break
            val removed = sections.removeAt(idx)
            val matKind = removed.kind as NovelInjectionSectionKind.Material
            val di = materialDecisions.indexOfFirst { it.revisionID == matKind.revisionID }
            if (di >= 0) {
                val prev = materialDecisions[di]
                materialDecisions[di] = prev.copy(
                    included = false,
                    reason = NovelInjectionSelectionReason.BudgetTrimmed,
                    estimatedTokens = 0,
                )
            }
        }
    }

    fun toReceipt(
        plan: NovelInjectionPlan,
        receiptId: NovelReceiptId,
        runId: NovelRunId,
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        model: NovelResolvedModel,
        createdAt: Instant,
        forceInclude: List<app.amber.feature.novel.model.NovelMaterialId> = emptyList(),
        forceExclude: List<app.amber.feature.novel.model.NovelMaterialId> = emptyList(),
    ): NovelInjectionReceiptRecord = NovelInjectionReceiptRecord(
        id = receiptId,
        runID = runId,
        projectID = projectId,
        branchID = branchId,
        promptVersion = plan.prompt.version,
        providerID = model.providerID,
        ownerProviderID = model.ownerProviderID,
        modelID = model.modelID,
        wireModelID = model.wireModelID,
        parameters = emptyMap(),
        sections = plan.sections.map {
            NovelInjectionReceiptSectionRecord(
                kind = it.kind,
                label = it.label,
                reason = it.reason,
                estimatedTokens = it.estimatedTokens,
                contentSHA256 = it.contentSHA256,
            )
        },
        materialDecisions = plan.materialDecisions,
        forceIncludeMaterialIDs = forceInclude,
        forceExcludeMaterialIDs = forceExclude,
        requestedInputBudgetTokens = plan.maxEstimatedInputTokens,
        maxEstimatedInputTokens = plan.maxEstimatedInputTokens,
        estimatedInputTokens = plan.estimatedInputTokens,
        canonicalInputSHA256 = plan.canonicalInputSHA256,
        createdAt = createdAt,
    )

    private fun estimateTokens(text: String): Int = (text.length / 3).coerceAtLeast(1)
}

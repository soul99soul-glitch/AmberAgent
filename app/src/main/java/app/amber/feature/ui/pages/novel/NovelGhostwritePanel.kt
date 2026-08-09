package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.feature.novel.NovelGhostwriteFailureReason
import app.amber.feature.novel.NovelGhostwriteBatchController
import app.amber.feature.novel.NovelGhostwritePauseReason
import app.amber.feature.novel.NovelGhostwritePhase
import app.amber.feature.novel.NovelGhostwriteProgress
import app.amber.feature.novel.domain.NovelGhostwriteReadiness
import app.amber.feature.novel.model.NovelChapterPlanRecord
import app.amber.feature.novel.model.NovelChapterPlanStatus
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelGhostwriteJobStatus
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelUpcomingArcRecord
import app.amber.feature.novel.persistence.NovelGhostwriteJobLoadAccess
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

data class NovelChapterPlanDraft(
    val outlinePlacement: String = "",
    val goalAndConflict: String = "",
    val mustHappen: String = "",
    val mustNotHappen: String = "",
    val endingHook: String = "",
    val visibleFacts: String = "",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NovelGhostwritePanel(
    state: NovelWorkspaceUiState,
    notificationPermissionDenied: Boolean,
    onDismiss: () -> Unit,
    onSetMode: (NovelCollaborationMode) -> Unit,
    onSetPauseOnBlockingContinuity: (Boolean) -> Unit,
    onSavePlan: (NovelChapterPlanDraft, NovelChapterPlanStatus) -> Unit,
    onClearPlan: () -> Unit,
    onSaveUpcomingArc: (List<String>) -> Unit,
    onClearUpcomingArc: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStartBatch: (Int) -> Unit,
    onPauseBatch: () -> Unit,
    onResumeBatch: () -> Unit,
    onCancelBatch: () -> Unit,
    onQuarantineBatchFailure: (String) -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val document = state.document ?: return
    val branchId = state.selectedBranchId
    val plan = branchId?.let(document::chapterPlan)
    val upcomingArc = branchId?.let(document::upcomingArc)
    val mode = document.project.collaborationMode
    val progress = state.ghostwriteProgress
    val batch = state.ghostwriteBatch
    val batchJob = batch.job
    val ghostwriteRunning = progress.isRunning()
    val canAttemptRecovery = progress.canAttemptRecovery()
    val canMutate = state.access == NovelProjectLoadAccess.ReadWrite &&
        !state.busy &&
        !state.generating &&
        !ghostwriteRunning &&
        !batch.loading &&
        !batch.ledgerBlocked &&
        !batch.projectHasActiveJob
    val switchIssues = if (branchId == null) {
        emptyList()
    } else {
        NovelGhostwriteReadiness.issues(document, branchId, requireChapterPlan = false)
    }
    val startIssues = if (canAttemptRecovery) {
        emptyList()
    } else if (branchId == null) {
        emptyList()
    } else {
        NovelGhostwriteReadiness.issues(document, branchId, requireChapterPlan = true)
    }

    var planDraft by remember(plan?.id?.rawValue, plan?.contentDigest, plan?.status) {
        mutableStateOf(plan.toDraft())
    }
    var arcText by remember(upcomingArc?.updatedAt, upcomingArc?.beats) {
        mutableStateOf(upcomingArc?.beats?.joinToString("\n").orEmpty())
    }
    var clearTarget by remember { mutableStateOf<GhostwriteClearTarget?>(null) }
    var quarantineToken by remember { mutableStateOf<String?>(null) }
    var batchTargetText by remember(batchJob?.id, batchJob?.isTerminal) {
        mutableStateOf(
            if (batchJob?.isTerminal == false) {
                batchJob.targetChapterCount.toString()
            } else {
                NovelGhostwriteBatchController.DEFAULT_TARGET_CHAPTER_COUNT.toString()
            },
        )
    }

    val mustHappen = novelPlanLines(planDraft.mustHappen)
    val mustNotHappen = novelPlanLines(planDraft.mustNotHappen)
    val visibleFacts = novelPlanLines(planDraft.visibleFacts)
    val planListsValid = listOf(mustHappen, mustNotHappen, visibleFacts).all {
        it.size <= MAX_PLAN_LINES
    }
    val draftValid = planDraft.goalAndConflict.isNotBlank() && planListsValid
    val confirmedValid = draftValid && mustHappen.isNotEmpty()
    val arcLines = novelUpcomingArcLines(arcText)
    val arcValid = arcLines.isNotEmpty() &&
        arcLines.size <= NovelUpcomingArcRecord.MAX_BEATS &&
        arcLines.all { it.length <= NovelUpcomingArcRecord.MAX_BEAT_CHARACTER_COUNT }
    val batchTarget = parseNovelGhostwriteBatchTarget(batchTargetText)

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item("header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "创作控制",
                        style = LocalAmberType.current.sessionTitle,
                        color = workspaceColors().ink,
                    )
                    Text(
                        text = "单章代笔，或按持久账本连续生成 1–50 章",
                        style = LocalAmberType.current.meta,
                        color = workspaceColors().muted,
                    )
                }
                NovelQuietButton(text = "完成", onClick = onDismiss)
            }
        }

        state.statusMessage?.let { message ->
            item("status") {
                NovelBanner(
                    text = message,
                    tone = WorkspaceTone.Success,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        state.errorMessage?.let { message ->
            item("error") {
                NovelBanner(
                    text = message,
                    tone = WorkspaceTone.Danger,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item("mode") {
            GhostwriteSection(title = "创作模式") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovelChipButton(
                        text = "共创",
                        selected = mode == NovelCollaborationMode.Cocreation,
                        onClick = { onSetMode(NovelCollaborationMode.Cocreation) },
                        enabled = canMutate,
                    )
                    NovelChipButton(
                        text = "代笔",
                        selected = mode == NovelCollaborationMode.Ghostwrite,
                        onClick = { onSetMode(NovelCollaborationMode.Ghostwrite) },
                        enabled = canMutate && switchIssues.isEmpty(),
                    )
                }
                Text(
                    text = if (mode == NovelCollaborationMode.Ghostwrite) {
                        "代笔会按已确认的本章计划写整章，验收后自动收录并同步剧情。"
                    } else {
                        "共创保持现有的候选稿与手动收录流程。"
                    },
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
                if (mode == NovelCollaborationMode.Cocreation && switchIssues.isNotEmpty()) {
                    GhostwriteIssueList(
                        title = "切入代笔还需：",
                        issues = switchIssues.map { it.displayName },
                    )
                }
                if (ghostwriteRunning || batch.projectHasActiveJob) {
                    Text(
                        text = "代笔任务尚未结束，取消连续批次后才能切换模式或修改计划。",
                        style = LocalAmberType.current.meta,
                        color = workspaceColors().muted,
                    )
                }
            }
        }

        if (mode == NovelCollaborationMode.Ghostwrite) {
            item("progress") {
                GhostwriteSection(title = "代笔进度") {
                    Text(
                        text = "连续代笔",
                        style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspaceColors().ink,
                    )
                    when {
                        batch.loading -> Text(
                            text = "正在读取持久化批次账本…",
                            style = LocalAmberType.current.meta,
                            color = workspaceColors().muted,
                        )
                        batch.loadFailure != null -> {
                            NovelBanner(
                                text = "批次账本暂时无法读取，正在自动重试。" +
                                    "不会在状态不明时继续生成。",
                                tone = WorkspaceTone.Warning,
                            )
                        }
                        batch.scanFailures.isNotEmpty() -> {
                            NovelBanner(
                                text = "发现损坏或不兼容的批次账本。隔离对应文件后才能继续。",
                                tone = WorkspaceTone.Danger,
                                actionLabel = batch.scanFailures.firstOrNull()?.let { "隔离损坏任务" },
                                onAction = batch.scanFailures.firstOrNull()?.let { failure ->
                                    { quarantineToken = failure.token }
                                },
                            )
                        }
                        batchJob?.isTerminal == false -> {
                            GhostwriteValueRow("完成进度", batchJob.batchProgressLabel())
                            GhostwriteValueRow("当前阶段", batchJob.batchStatusLabel())
                            humanizeNovelGhostwriteBatchReason(batchJob.statusReasonCode)?.let { reason ->
                                NovelBanner(
                                    text = reason,
                                    tone = if (batchJob.status == NovelGhostwriteJobStatus.Paused) {
                                        WorkspaceTone.Danger
                                    } else {
                                        WorkspaceTone.Neutral
                                    },
                                )
                            }
                            if (batch.access != NovelGhostwriteJobLoadAccess.ReadWrite) {
                                NovelBanner(
                                    text = "该批次处于只读恢复状态，不能继续修改。",
                                    tone = WorkspaceTone.Danger,
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                when (batchJob.status) {
                                    NovelGhostwriteJobStatus.Pending -> NovelPrimaryButton(
                                        text = "重试启动",
                                        onClick = { onStartBatch(batchJob.targetChapterCount) },
                                        enabled = !batch.commandPending,
                                        accent = true,
                                        compact = true,
                                    )
                                    NovelGhostwriteJobStatus.Running -> NovelGhostButton(
                                        text = "暂停批次",
                                        onClick = onPauseBatch,
                                        enabled = !batch.commandPending,
                                    )
                                    NovelGhostwriteJobStatus.Paused -> if (batchJob.batchCanResume()) {
                                        NovelPrimaryButton(
                                            text = "继续批次",
                                            onClick = onResumeBatch,
                                            enabled = !batch.commandPending,
                                            accent = true,
                                            compact = true,
                                        )
                                    }
                                    NovelGhostwriteJobStatus.Completed,
                                    NovelGhostwriteJobStatus.Failed,
                                    NovelGhostwriteJobStatus.Cancelled,
                                    -> Unit
                                }
                                NovelGhostButton(
                                    text = "取消批次",
                                    onClick = onCancelBatch,
                                    enabled = !batch.commandPending,
                                )
                            }
                        }
                        else -> {
                            batchJob?.let { finished ->
                                GhostwriteValueRow("最近批次", finished.batchProgressLabel())
                                GhostwriteValueRow("结果", finished.batchStatusLabel())
                                humanizeNovelGhostwriteBatchReason(finished.statusReasonCode)?.let { reason ->
                                    NovelBanner(
                                        text = reason,
                                        tone = if (finished.status == NovelGhostwriteJobStatus.Completed) {
                                            WorkspaceTone.Success
                                        } else {
                                            WorkspaceTone.Danger
                                        },
                                    )
                                }
                            }
                            GhostwriteField(
                                label = "本批次生成章数（1–50）",
                                placeholder = NovelGhostwriteBatchController.DEFAULT_TARGET_CHAPTER_COUNT.toString(),
                                value = batchTargetText,
                                onValueChange = { raw ->
                                    batchTargetText = raw.filter(Char::isDigit).take(2)
                                },
                                enabled = canMutate && !batch.commandPending,
                                minLines = 1,
                            )
                            if (batchTarget == null) {
                                Text(
                                    text = "请输入 1 到 50 之间的整数。",
                                    style = LocalAmberType.current.meta,
                                    color = workspaceColors().red,
                                )
                            }
                            NovelPrimaryButton(
                                text = if (batchJob == null) "开始连续代笔" else "开始新批次",
                                onClick = { batchTarget?.let(onStartBatch) },
                                enabled = canMutate &&
                                    !batch.projectHasActiveJob &&
                                    !batch.commandPending &&
                                    batchTarget != null &&
                                    startIssues.isEmpty(),
                                accent = true,
                                compact = true,
                            )
                        }
                    }
                    Text(
                        text = "首章使用你确认的计划；后续计划由系统逐章提出。" +
                            "每章只有在校验、收录、" +
                            "同步和清理计划全部完成后才计数。进程重启会按账本恢复阶段，" +
                            "不会宣称续传中断前的流式片段。",
                        style = LocalAmberType.current.meta,
                        color = workspaceColors().muted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "单章代笔",
                        style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                        color = workspaceColors().ink,
                    )
                    GhostwriteValueRow("状态", progress.statusLabel())
                    GhostwriteValueRow("本章计划", plan.statusLabel())
                    GhostwriteValueRow(
                        "往后几章",
                        upcomingArc?.beats?.takeIf { it.isNotEmpty() }?.let { "${it.size} 条" }
                            ?: "未设置",
                    )
                    progress?.detailText()?.takeIf { it.isNotBlank() }?.let { detail ->
                        NovelBanner(
                            text = detail,
                            tone = if (progress.phase == NovelGhostwritePhase.Failed ||
                                progress.phase == NovelGhostwritePhase.Paused &&
                                progress.pauseReason != NovelGhostwritePauseReason.UserPaused
                            ) {
                                WorkspaceTone.Danger
                            } else {
                                WorkspaceTone.Neutral
                            },
                        )
                    }
                    if (!ghostwriteRunning && startIssues.isNotEmpty()) {
                        GhostwriteIssueList(
                            title = "开始代笔还需：",
                            issues = startIssues.map { it.displayName },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "阻塞级连续性问题时暂停",
                                style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                                color = workspaceColors().ink,
                            )
                            Text(
                                text = "一般或较大问题只记录，不会误挡自动收录。",
                                style = LocalAmberType.current.meta,
                                color = workspaceColors().muted,
                            )
                        }
                        Switch(
                            checked = document.project.pauseGhostwriteOnBlockingContinuity,
                            onCheckedChange = onSetPauseOnBlockingContinuity,
                            enabled = canMutate,
                            modifier = Modifier.semantics {
                                contentDescription = "阻塞级连续性问题时暂停"
                            },
                        )
                    }
                    if (notificationPermissionDenied ||
                        batch.notificationRequired ||
                        progress?.failureReason == NovelGhostwriteFailureReason.NotificationPermissionRequired
                    ) {
                        NovelBanner(
                            text = "通知权限未开启。请在系统设置中允许 Amber 发送通知，" +
                                "再开始后台代笔。",
                            tone = WorkspaceTone.Danger,
                            actionLabel = "打开设置",
                            onAction = onOpenNotificationSettings,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ghostwriteRunning) {
                            NovelGhostButton(
                                text = "暂停",
                                onClick = onPause,
                                enabled = progress?.runId != null,
                            )
                        } else if (progress?.phase !in setOf(
                                NovelGhostwritePhase.Paused,
                                NovelGhostwritePhase.Failed,
                            ) || canAttemptRecovery
                        ) {
                            val continuing = canAttemptRecovery
                            NovelPrimaryButton(
                                text = if (continuing) "继续代笔" else "开始代笔本章",
                                onClick = onStart,
                                enabled = canMutate && startIssues.isEmpty(),
                                accent = true,
                                compact = true,
                            )
                        }
                    }
                    Text(
                        text = "写完一章并同步后会停住；下一章需要新的已确认计划。",
                        style = LocalAmberType.current.meta,
                        color = workspaceColors().muted,
                    )
                }
            }
        }

        item("plan") {
            GhostwriteSection(title = "本章计划") {
                GhostwriteValueRow("计划状态", plan.statusLabel())
                GhostwriteField(
                    label = "与总纲的位置",
                    placeholder = "例如：第 3 章",
                    value = planDraft.outlinePlacement,
                    onValueChange = {
                        planDraft = planDraft.copy(outlinePlacement = it.take(MAX_PLACEMENT_LENGTH))
                    },
                    enabled = canMutate,
                    minLines = 1,
                )
                GhostwriteField(
                    label = "目标与冲突",
                    placeholder = "本章要解决什么",
                    value = planDraft.goalAndConflict,
                    onValueChange = {
                        planDraft = planDraft.copy(goalAndConflict = it.take(MAX_GOAL_LENGTH))
                    },
                    enabled = canMutate,
                )
                GhostwriteField(
                    label = "必发生（每行一条）",
                    placeholder = "确认计划时至少一条",
                    value = planDraft.mustHappen,
                    onValueChange = { planDraft = planDraft.copy(mustHappen = it) },
                    enabled = canMutate,
                )
                GhostwriteField(
                    label = "禁止发生（每行一条）",
                    placeholder = "可留空",
                    value = planDraft.mustNotHappen,
                    onValueChange = { planDraft = planDraft.copy(mustNotHappen = it) },
                    enabled = canMutate,
                )
                GhostwriteField(
                    label = "章末钩子",
                    placeholder = "可留空",
                    value = planDraft.endingHook,
                    onValueChange = {
                        planDraft = planDraft.copy(endingHook = it.take(MAX_ENDING_HOOK_LENGTH))
                    },
                    enabled = canMutate,
                )
                GhostwriteField(
                    label = "POV 可见要点（每行一条）",
                    placeholder = "可留空",
                    value = planDraft.visibleFacts,
                    onValueChange = { planDraft = planDraft.copy(visibleFacts = it) },
                    enabled = canMutate,
                )
                if (!planListsValid) {
                    Text(
                        text = "每组清单最多 $MAX_PLAN_LINES 条。",
                        style = LocalAmberType.current.meta,
                        color = workspaceColors().red,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NovelGhostButton(
                        text = "保存草稿",
                        onClick = { onSavePlan(planDraft, NovelChapterPlanStatus.Draft) },
                        enabled = canMutate && draftValid,
                    )
                    NovelPrimaryButton(
                        text = "确认计划",
                        onClick = { onSavePlan(planDraft, NovelChapterPlanStatus.Confirmed) },
                        enabled = canMutate && confirmedValid,
                        accent = true,
                        compact = true,
                    )
                    if (plan != null) {
                        NovelQuietButton(
                            text = "清除",
                            onClick = { clearTarget = GhostwriteClearTarget.Plan },
                            enabled = canMutate,
                        )
                    }
                }
                Text(
                    text = if (mode == NovelCollaborationMode.Ghostwrite) {
                        "代笔写整章前必须确认计划；代笔进行中不能修改。"
                    } else {
                        "可以先写好本章计划；确认后生成整章时会带上。"
                    },
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
            }
        }

        item("arc") {
            GhostwriteSection(title = "往后几章") {
                GhostwriteField(
                    label = "后面几章想往哪走（每行一条）",
                    placeholder = "例如：使者身份曝光",
                    value = arcText,
                    onValueChange = { arcText = it },
                    enabled = canMutate,
                )
                if (arcLines.size > NovelUpcomingArcRecord.MAX_BEATS ||
                    arcLines.any { it.length > NovelUpcomingArcRecord.MAX_BEAT_CHARACTER_COUNT }
                ) {
                    Text(
                        text = "最多 ${NovelUpcomingArcRecord.MAX_BEATS} 条，每条最多 " +
                            "${NovelUpcomingArcRecord.MAX_BEAT_CHARACTER_COUNT} 字。",
                        style = LocalAmberType.current.meta,
                        color = workspaceColors().red,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NovelPrimaryButton(
                        text = "保存",
                        onClick = { onSaveUpcomingArc(arcLines) },
                        enabled = canMutate && arcValid,
                        accent = true,
                        compact = true,
                    )
                    if (upcomingArc != null) {
                        NovelQuietButton(
                            text = "清除",
                            onClick = { clearTarget = GhostwriteClearTarget.Arc },
                            enabled = canMutate,
                        )
                    }
                }
                Text(
                    text = "这是跨章软方向，写整章时会参考，不会替代本章计划。",
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
            }
        }

        item("bottom-space") { Spacer(Modifier.height(20.dp)) }
    }

    clearTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            containerColor = workspaceColors().paper,
            title = {
                Text(
                    text = if (target == GhostwriteClearTarget.Plan) {
                        "清除本章计划？"
                    } else {
                        "清除往后几章的备注？"
                    },
                    color = workspaceColors().ink,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = if (target == GhostwriteClearTarget.Plan) {
                        "清除后需要重新确认计划，才能继续代笔。"
                    } else {
                        "清除后，写整章时不再参考这些跨章备注。"
                    },
                    style = LocalAmberType.current.secondary,
                    color = workspaceColors().muted,
                )
            },
            confirmButton = {
                NovelGhostButton(
                    text = "清除",
                    onClick = {
                        if (target == GhostwriteClearTarget.Plan) onClearPlan()
                        else onClearUpcomingArc()
                        clearTarget = null
                    },
                    danger = true,
                )
            },
            dismissButton = {
                NovelQuietButton(text = "取消", onClick = { clearTarget = null })
            },
        )
    }

    quarantineToken?.let { token ->
        AlertDialog(
            onDismissRequest = { quarantineToken = null },
            containerColor = workspaceColors().paper,
            title = {
                Text(
                    text = "隔离损坏的连续代笔账本？",
                    color = workspaceColors().ink,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = "原文件会保留移动到隔离区，不会直接删除。" +
                        "隔离后请重新检查计划和正文，" +
                        "再决定是否创建新批次。",
                    style = LocalAmberType.current.secondary,
                    color = workspaceColors().muted,
                )
            },
            confirmButton = {
                NovelPrimaryButton(
                    text = "确认隔离",
                    onClick = {
                        onQuarantineBatchFailure(token)
                        quarantineToken = null
                    },
                    accent = true,
                    compact = true,
                )
            },
            dismissButton = {
                NovelQuietButton(text = "取消", onClick = { quarantineToken = null })
            },
        )
    }
}

@Composable
private fun GhostwriteSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionLabel(
        text = title,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
    AmberCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun GhostwriteField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    minLines: Int = 2,
) {
    val tokens = LocalAmberTokens.current
    val workspace = workspaceColors()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        maxLines = 8,
        shape = RoundedCornerShape(12.dp),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        textStyle = LocalAmberType.current.body,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tokens.accent,
            unfocusedBorderColor = tokens.line,
            focusedContainerColor = tokens.surface,
            unfocusedContainerColor = tokens.surface,
            disabledContainerColor = tokens.surface2,
            cursorColor = tokens.accent,
            focusedTextColor = tokens.ink,
            unfocusedTextColor = tokens.ink,
            focusedPlaceholderColor = workspace.muted,
            unfocusedPlaceholderColor = workspace.muted,
        ),
    )
}

@Composable
private fun GhostwriteValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = LocalAmberType.current.meta,
            color = workspaceColors().muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
            color = workspaceColors().ink,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun GhostwriteIssueList(title: String, issues: List<String>) {
    val workspace = workspaceColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(workspace.row, RoundedCornerShape(12.dp))
            .border(1.dp, workspace.hairline, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = LocalAmberType.current.meta.copy(fontWeight = FontWeight.SemiBold),
            color = workspace.muted,
        )
        issues.forEach { issue ->
            Text(
                text = "· $issue",
                style = LocalAmberType.current.meta,
                color = workspace.muted,
            )
        }
    }
}

private fun NovelChapterPlanRecord?.toDraft(): NovelChapterPlanDraft = NovelChapterPlanDraft(
    outlinePlacement = this?.outlinePlacement.orEmpty(),
    goalAndConflict = this?.goalAndConflict.orEmpty(),
    mustHappen = this?.mustHappen?.joinToString("\n").orEmpty(),
    mustNotHappen = this?.mustNotHappen?.joinToString("\n").orEmpty(),
    endingHook = this?.endingHook.orEmpty(),
    visibleFacts = this?.visibleFacts?.joinToString("\n").orEmpty(),
)

private fun NovelChapterPlanRecord?.statusLabel(): String = when (this?.status) {
    NovelChapterPlanStatus.Draft -> "草稿"
    NovelChapterPlanStatus.Confirmed -> "已确认"
    null -> "未创建"
}

internal fun NovelGhostwriteProgress?.isRunning(): Boolean = this?.phase in setOf(
    NovelGhostwritePhase.Starting,
    NovelGhostwritePhase.Writing,
    NovelGhostwritePhase.Accepting,
    NovelGhostwritePhase.Collecting,
    NovelGhostwritePhase.Syncing,
)

internal fun NovelGhostwriteProgress?.canAttemptRecovery(): Boolean {
    if (this?.pauseReason == NovelGhostwritePauseReason.CanonicalContinuityConflict) return false
    val recoveryPhase = this?.phase in setOf(
        NovelGhostwritePhase.Paused,
        NovelGhostwritePhase.Failed,
    )
    val recoveryContext = this?.candidateId != null || this?.pauseReason in setOf(
        NovelGhostwritePauseReason.CollectFailed,
        NovelGhostwritePauseReason.SyncFailed,
        NovelGhostwritePauseReason.NotificationUnavailable,
    )
    return recoveryPhase && recoveryContext
}

internal fun shouldClearGhostwriteBusy(
    progress: NovelGhostwriteProgress?,
    wasRunning: Boolean,
    startPending: Boolean,
): Boolean {
    if (progress.isRunning()) return false
    val terminalAfterStart = startPending && progress?.phase in setOf(
        NovelGhostwritePhase.Paused,
        NovelGhostwritePhase.WaitingUser,
        NovelGhostwritePhase.Failed,
    )
    return wasRunning || terminalAfterStart
}

private fun NovelGhostwriteProgress?.statusLabel(): String = when (this?.phase) {
    NovelGhostwritePhase.Starting -> "正在准备后台生成"
    NovelGhostwritePhase.Writing -> "代笔中·写整章"
    NovelGhostwritePhase.Accepting -> "代笔中·核对计划"
    NovelGhostwritePhase.Collecting -> "代笔中·自动收录"
    NovelGhostwritePhase.Syncing -> "代笔中·剧情同步"
    NovelGhostwritePhase.Paused -> "代笔已暂停"
    NovelGhostwritePhase.WaitingUser -> "本章已完成·等待新计划"
    NovelGhostwritePhase.Failed -> "代笔失败"
    NovelGhostwritePhase.Idle, null -> "尚未开始"
}

private fun NovelGhostwriteProgress.detailText(): String = detailMessage?.takeIf { it.isNotBlank() }
    ?: pauseReason?.displayText()
    ?: failureReason?.displayText()
    ?: if (partialCharacterCount > 0 && phase == NovelGhostwritePhase.Writing) {
        "已生成 $partialCharacterCount 字"
    } else {
        ""
    }

private fun NovelGhostwritePauseReason.displayText(): String = when (this) {
    NovelGhostwritePauseReason.UserPaused -> "已暂停代笔。"
    NovelGhostwritePauseReason.AcceptanceFailed -> "本章没有通过计划验收，已暂停。"
    NovelGhostwritePauseReason.ObviousRepetition -> "发现明显重复情节，已暂停。"
    NovelGhostwritePauseReason.BlockingContinuity -> "发现阻塞级连续性问题，已暂停。"
    NovelGhostwritePauseReason.CanonicalContinuityConflict ->
        "既有正文存在阻塞级连续性问题，需要先人工处理。"
    NovelGhostwritePauseReason.ContinuityAuditIncomplete -> "连续性检查未完整，已暂停。"
    NovelGhostwritePauseReason.CollectFailed -> "自动收录失败，已暂停。"
    NovelGhostwritePauseReason.SyncFailed -> "剧情同步失败，本章已保留，不会自动重写。"
    NovelGhostwritePauseReason.IncompleteCandidate -> "本章正文不完整，已暂停。"
    NovelGhostwritePauseReason.PlanMismatch -> "候选稿与当前计划不匹配，已暂停。"
    NovelGhostwritePauseReason.NotificationUnavailable -> "后台通知不可用，代笔已暂停。"
    NovelGhostwritePauseReason.ChapterCompleted -> "本章已完成，请确认下一章计划。"
}

private fun NovelGhostwriteFailureReason.displayText(): String = when (this) {
    NovelGhostwriteFailureReason.AlreadyRunning -> "已有代笔任务在运行。"
    NovelGhostwriteFailureReason.ProjectUnavailable -> "项目不可用。"
    NovelGhostwriteFailureReason.ProjectReadOnly -> "项目处于只读恢复状态。"
    NovelGhostwriteFailureReason.CollaborationModeRequired -> "请先切换到代笔模式。"
    NovelGhostwriteFailureReason.ReadinessBlocked -> "代笔条件尚未满足。"
    NovelGhostwriteFailureReason.NotificationPermissionRequired ->
        "请先允许 Amber 发送通知，才能开始后台代笔。"
    NovelGhostwriteFailureReason.ForegroundServiceUnavailable -> "无法启动后台生成服务。"
    NovelGhostwriteFailureReason.GenerationFailed -> "代笔生成失败。"
}

internal fun novelPlanLines(text: String): List<String> = text
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toList()

internal fun novelUpcomingArcLines(text: String): List<String> = novelPlanLines(text)
    .distinctBy { it.lowercase() }

private enum class GhostwriteClearTarget { Plan, Arc }

private const val MAX_PLAN_LINES = 32
private const val MAX_PLACEMENT_LENGTH = 500
private const val MAX_GOAL_LENGTH = 8_000
private const val MAX_ENDING_HOOK_LENGTH = 4_000

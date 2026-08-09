package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.ai.provider.ModelType
import app.amber.core.settings.findProvider
import app.amber.core.utils.plus
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchRecord
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.ui.components.ai.ModelSelector
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.hooks.rememberUserSettingsState
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Novel settings — single writing card + light danger row.
 * Model selector lives inside the card (no floating orphan control).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelSettingsPage(
    projectId: String,
    viewModel: NovelSettingsViewModel = koinViewModel(parameters = { parametersOf(projectId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val document = state.document
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tokens = LocalAmberTokens.current
    val settings by rememberUserSettingsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    if (state.loading || document == null) {
        NovelSettingsUnavailableState(
            loading = state.loading,
            message = state.errorMessage,
        )
        return
    }
    val canEdit = state.access == NovelProjectLoadAccess.ReadWrite && !state.busy

    var undoConfirm by remember { mutableStateOf(false) }
    var forkDialog by remember { mutableStateOf(false) }
    var renameBranchTarget by remember { mutableStateOf<NovelBranchRecord?>(null) }
    var pendingMarkdown by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val createMarkdownDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val payload = pendingMarkdown
        pendingMarkdown = null
        if (uri == null) {
            viewModel.reportError("已取消导出")
            return@rememberLauncherForActivityResult
        }
        if (payload == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(payload.second.toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开输出流")
                }.isSuccess
            }
            if (ok) viewModel.reportStatus("已保存 ${payload.first}")
            else viewModel.reportError("写入 Markdown 失败")
        }
    }
    val savedPolish = document?.project?.polishPreference.orEmpty()
    var polishPref by remember(savedPolish) { mutableStateOf(savedPolish) }
    val polishDirty = polishPref != savedPolish
    val branches = remember(document?.branches) {
        document?.branches
            ?.filter { it.lifecycle == NovelBranchLifecycle.Active }
            ?.sortedBy { it.name }
            .orEmpty()
    }
    val mainBranchId = document?.project?.mainBranchID

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = tokens.accent,
        unfocusedBorderColor = tokens.line,
        focusedContainerColor = tokens.surface,
        unfocusedContainerColor = tokens.surface,
        cursorColor = tokens.accent,
        focusedTextColor = tokens.ink,
        unfocusedTextColor = tokens.ink,
        focusedPlaceholderColor = tokens.ink4,
        unfocusedPlaceholderColor = tokens.ink4,
    )

    val fixedPolicy = document?.project?.modelPolicy as? NovelProjectModelPolicy.Fixed
    val selectedModelId = remember(fixedPolicy?.modelID, settings.chatModelId) {
        fixedPolicy?.modelID?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: settings.chatModelId
    }
    val followingGlobal = fixedPolicy == null
    val reviewPolicy = document?.project?.reviewModelPolicy
    val reviewFixed = reviewPolicy as? NovelProjectModelPolicy.Fixed
    val reviewFollowingGlobal = reviewFixed == null
    val selectedReviewModelId = remember(reviewFixed?.modelID, settings.chatModelId) {
        reviewFixed?.modelID?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: settings.chatModelId
    }
    val stateSyncPolicy = document?.project?.stateSyncModelPolicy
    val stateSyncFixed = stateSyncPolicy as? NovelProjectModelPolicy.Fixed
    val stateSyncFollowingWriting = stateSyncPolicy == null
    val stateSyncFollowingGlobal = stateSyncPolicy is NovelProjectModelPolicy.Global
    val selectedStateSyncModelId = remember(
        stateSyncFixed?.modelID,
        fixedPolicy?.modelID,
        settings.chatModelId,
        stateSyncFollowingWriting,
        stateSyncFollowingGlobal,
    ) {
        when {
            stateSyncFixed != null ->
                runCatching { Uuid.parse(stateSyncFixed.modelID) }.getOrNull()
            stateSyncFollowingWriting && fixedPolicy != null ->
                runCatching { Uuid.parse(fixedPolicy.modelID) }.getOrNull()
                    ?: settings.chatModelId
            else -> settings.chatModelId
        }
    }
    val projectName = document?.project?.name?.ifBlank { null }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = "小说设置",
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspace.canvas,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
            state.statusMessage?.let { msg ->
                item("status-top") {
                    NovelBanner(text = msg, tone = WorkspaceTone.Success)
                }
            }
            state.errorMessage?.let { msg ->
                item("error-top") {
                    NovelBanner(text = msg, tone = WorkspaceTone.Danger)
                }
            }
            if (state.access != NovelProjectLoadAccess.ReadWrite) {
                item("read-only") {
                    NovelBanner(
                        text = "项目处于只读恢复状态，可以查看设置，但不能修改。",
                        tone = WorkspaceTone.Warning,
                    )
                }
            }

            // One writing card: model + polish
            item("writing") {
                SectionLabel(
                    text = "写作",
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                AmberCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (projectName != null) {
                            Text(
                                text = projectName,
                                style = type.meta,
                                color = workspace.muted,
                            )
                        }

                        // —— 模型 ——
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "写作模型",
                                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                                    color = workspace.ink,
                                )
                                NovelModelScopePill(
                                    followingGlobal = followingGlobal,
                                )
                            }
                            ModelSelector(
                                modelId = selectedModelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                inline = true,
                                enabled = canEdit,
                                allowClear = !followingGlobal,
                                emptyLabel = "选择模型",
                                clearContentDescription = "改回跟随全局",
                                modifier = Modifier.fillMaxWidth(),
                                onClear = {
                                    viewModel.setModelPolicy(NovelProjectModelPolicy.Global)
                                },
                                onSelect = { model ->
                                    val provider = model.findProvider(settings.providers)
                                        ?: return@ModelSelector
                                    viewModel.setModelPolicy(
                                        NovelProjectModelPolicy.Fixed(
                                            providerID = provider.id.toString(),
                                            modelID = model.id.toString(),
                                        ),
                                    )
                                },
                            )
                            Text(
                                text = if (followingGlobal) {
                                    "跟随全局聊天模型；点上方可固定为本项目模型"
                                } else {
                                    "已固定；点清除可改回跟随全局"
                                },
                                style = type.meta,
                                color = workspace.muted,
                            )
                        }

                        HorizontalDivider(color = tokens.line, thickness = 1.dp)

                        // —— 审稿模型 ——
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "审稿模型",
                                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                                    color = workspace.ink,
                                )
                                NovelModelScopePill(
                                    followingGlobal = reviewFollowingGlobal,
                                )
                            }
                            ModelSelector(
                                modelId = selectedReviewModelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                inline = true,
                                enabled = canEdit,
                                allowClear = !reviewFollowingGlobal,
                                emptyLabel = "选择模型",
                                clearContentDescription = "改回跟随全局",
                                modifier = Modifier.fillMaxWidth(),
                                onClear = {
                                    viewModel.setModelPolicy(
                                        NovelProjectModelPolicy.Global,
                                        purpose = app.amber.feature.novel.domain.NovelModelPolicyPurpose.Review,
                                    )
                                },
                                onSelect = { model ->
                                    val provider = model.findProvider(settings.providers)
                                        ?: return@ModelSelector
                                    viewModel.setModelPolicy(
                                        NovelProjectModelPolicy.Fixed(
                                            providerID = provider.id.toString(),
                                            modelID = model.id.toString(),
                                        ),
                                        purpose = app.amber.feature.novel.domain.NovelModelPolicyPurpose.Review,
                                    )
                                },
                            )
                            Text(
                                text = if (reviewFollowingGlobal) {
                                    "代笔计划验收与连续性检查跟随全局聊天模型"
                                } else {
                                    "已固定审稿模型；点清除可改回跟随全局"
                                },
                                style = type.meta,
                                color = workspace.muted,
                            )
                        }

                        HorizontalDivider(color = tokens.line, thickness = 1.dp)

                        // —— 状态同步模型 ——
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "状态同步模型",
                                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                                    color = workspace.ink,
                                )
                                NovelModelScopePill(
                                    followingGlobal = stateSyncFollowingWriting || stateSyncFollowingGlobal,
                                    followingLabel = when {
                                        stateSyncFollowingWriting -> "跟随写作"
                                        stateSyncFollowingGlobal -> "跟随全局"
                                        else -> "已固定"
                                    },
                                )
                            }
                            ModelSelector(
                                modelId = selectedStateSyncModelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                inline = true,
                                enabled = canEdit,
                                allowClear = !stateSyncFollowingWriting,
                                emptyLabel = "选择模型",
                                clearContentDescription = "改回跟随写作模型",
                                modifier = Modifier.fillMaxWidth(),
                                onClear = { viewModel.clearStateSyncModelPolicy() },
                                onSelect = { model ->
                                    val provider = model.findProvider(settings.providers)
                                        ?: return@ModelSelector
                                    viewModel.setModelPolicy(
                                        NovelProjectModelPolicy.Fixed(
                                            providerID = provider.id.toString(),
                                            modelID = model.id.toString(),
                                        ),
                                        purpose = app.amber.feature.novel.domain.NovelModelPolicyPurpose.StateSync,
                                    )
                                },
                            )
                            Text(
                                text = when {
                                    stateSyncFollowingWriting ->
                                        "收录/同步剧情时默认用写作模型；点上方可单独指定"
                                    stateSyncFollowingGlobal ->
                                        "已设为跟随全局聊天模型（与写作模型可能不同）"
                                    else ->
                                        "已固定状态同步模型；点清除可改回跟随写作模型"
                                },
                                style = type.meta,
                                color = workspace.muted,
                            )
                        }

                        HorizontalDivider(color = tokens.line, thickness = 1.dp)

                        // —— 润色 ——
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "润色偏好",
                                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                                color = workspace.ink,
                            )
                            Text(
                                text = "整章润色时参考，可留空",
                                style = type.meta,
                                color = workspace.muted,
                            )
                            OutlinedTextField(
                                value = polishPref,
                                onValueChange = { polishPref = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = canEdit,
                                label = { Text("润色要求") },
                                minLines = 3,
                                maxLines = 6,
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors,
                                placeholder = {
                                    Text(
                                        "例如：文风更简洁，少用形容词",
                                        style = type.body,
                                        color = tokens.ink4,
                                    )
                                },
                                textStyle = type.body,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (polishDirty) {
                                    NovelPrimaryButton(
                                        text = if (state.busy) "保存中…" else "保存",
                                        onClick = { viewModel.setPolishPreference(polishPref) },
                                        enabled = canEdit,
                                        accent = true,
                                        compact = true,
                                    )
                                } else {
                                    Text(
                                        text = "已同步",
                                        style = type.meta,
                                        color = workspace.muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Branches
            item("branches") {
                SectionLabel(
                    text = "分支",
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                AmberCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (branches.isEmpty()) {
                            Text(
                                "暂无活动分支",
                                style = type.meta,
                                color = workspace.muted,
                            )
                        } else {
                            branches.forEach { branch ->
                                val isMain = branch.id == mainBranchId
                                val isSelected = branch.id == state.selectedBranchId
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) tokens.ink.copy(alpha = 0.06f)
                                            else workspace.canvas,
                                        )
                                        .border(1.dp, workspace.hairline, RoundedCornerShape(12.dp))
                                        .clickable(enabled = canEdit) {
                                            viewModel.selectBranch(branch.id)
                                        }
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = branch.name.ifBlank { "未命名分支" },
                                            style = type.body.copy(fontWeight = FontWeight.SemiBold),
                                            color = workspace.ink,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = buildString {
                                                if (isMain) append("主分支")
                                                if (isMain && isSelected) append(" · ")
                                                if (isSelected) append("当前")
                                            },
                                            style = type.meta,
                                            color = workspace.muted,
                                        )
                                    }
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        NovelGhostButton(
                                            text = "重命名",
                                            onClick = { renameBranchTarget = branch },
                                            enabled = canEdit,
                                        )
                                        if (!isMain) {
                                            NovelGhostButton(
                                                text = "设为主分支",
                                                onClick = { viewModel.setMainBranch(branch.id) },
                                                enabled = canEdit,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        NovelPrimaryButton(
                            text = if (state.busy) "处理中…" else "从当前 head Fork 分支",
                            onClick = { forkDialog = true },
                            enabled = canEdit && state.selectedBranchId != null,
                            accent = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "Fork 会从当前检查点复制一份剧情线，并自动切换过去。",
                            style = type.meta,
                            color = workspace.muted,
                        )
                    }
                }
            }

            // Export
            item("export") {
                SectionLabel(
                    text = "导出",
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(workspace.paper)
                        .border(1.dp, workspace.hairline, RoundedCornerShape(14.dp))
                        .clickable(enabled = canEdit) {
                            viewModel.exportMarkdown { name, content ->
                                pendingMarkdown = name to content
                                createMarkdownDoc.launch(name)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "导出当前分支 Markdown",
                            style = type.body.copy(fontWeight = FontWeight.SemiBold),
                            color = workspace.ink,
                        )
                        Text(
                            text = "按当前选中分支的章节顺序导出正文",
                            style = type.meta,
                            color = workspace.muted,
                        )
                    }
                }
            }

            // Danger — single quiet row
            item("danger") {
                SectionLabel(
                    text = "危险操作",
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(workspace.paper)
                        .border(1.dp, workspace.hairline, RoundedCornerShape(14.dp))
                        .clickable(enabled = canEdit) { undoConfirm = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "撤销最近一次收录",
                            style = type.body.copy(fontWeight = FontWeight.SemiBold),
                            color = workspace.red,
                        )
                        Text(
                            text = "回退上一次写入正文的操作",
                            style = type.meta,
                            color = workspace.muted,
                        )
                    }
                }
            }

                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }

    if (undoConfirm) {
        AlertDialog(
            onDismissRequest = { undoConfirm = false },
            containerColor = workspace.paper,
            title = {
                Text("撤销最近收录？", fontWeight = FontWeight.SemiBold, color = workspace.ink)
            },
            text = {
                Text(
                    "将回退上一次收录到正文的操作。",
                    style = type.secondary,
                    color = workspace.muted,
                )
            },
            confirmButton = {
                NovelPrimaryButton(
                    text = "撤销",
                    onClick = {
                        viewModel.undoHead()
                        undoConfirm = false
                    },
                    accent = true,
                    compact = true,
                )
            },
            dismissButton = {
                NovelQuietButton(
                    text = "取消",
                    onClick = { undoConfirm = false },
                )
            },
        )
    }

    if (forkDialog) {
        var forkName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (canEdit) forkDialog = false },
            containerColor = workspace.paper,
            title = {
                Text("Fork 分支", fontWeight = FontWeight.SemiBold, color = workspace.ink)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "从当前 head 复制一条新剧情线。",
                        style = type.secondary,
                        color = workspace.muted,
                    )
                    OutlinedTextField(
                        value = forkName,
                        onValueChange = { forkName = it },
                        singleLine = true,
                        enabled = canEdit,
                        label = { Text("分支名称") },
                        placeholder = { Text("分支名称") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors,
                    )
                }
            },
            confirmButton = {
                NovelPrimaryButton(
                    text = "创建",
                    onClick = {
                        val name = forkName.trim()
                        if (name.isNotEmpty()) {
                            viewModel.forkFromHead(name)
                            forkDialog = false
                        }
                    },
                    enabled = canEdit && forkName.isNotBlank(),
                    accent = true,
                    compact = true,
                )
            },
            dismissButton = {
                NovelQuietButton(
                    text = "取消",
                    onClick = { forkDialog = false },
                    enabled = canEdit,
                )
            },
        )
    }

    renameBranchTarget?.let { branch ->
        var name by remember(branch.id.rawValue) { mutableStateOf(branch.name) }
        AlertDialog(
            onDismissRequest = { if (canEdit) renameBranchTarget = null },
            containerColor = workspace.paper,
            title = {
                Text("重命名分支", fontWeight = FontWeight.SemiBold, color = workspace.ink)
            },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    enabled = canEdit,
                    label = { Text("分支名称") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors,
                )
            },
            confirmButton = {
                NovelPrimaryButton(
                    text = "保存",
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.renameBranch(branch.id, trimmed)
                            renameBranchTarget = null
                        }
                    },
                    enabled = canEdit && name.isNotBlank(),
                    accent = true,
                    compact = true,
                )
            },
            dismissButton = {
                NovelQuietButton(
                    text = "取消",
                    onClick = { renameBranchTarget = null },
                    enabled = canEdit,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelSettingsUnavailableState(
    loading: Boolean,
    message: String?,
) {
    val workspace = workspaceColors()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = "小说设置",
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = workspace.canvas,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (loading) "正在读取小说设置…" else message ?: "无法读取小说设置",
                style = LocalAmberType.current.body,
                color = if (loading) workspace.muted else workspace.red,
            )
        }
    }
}

@Composable
private fun NovelModelScopePill(
    followingGlobal: Boolean,
    followingLabel: String = "全局",
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val label = if (followingGlobal) followingLabel else "固定"
    val bg = if (followingGlobal) tokens.surface2 else tokens.accent.copy(alpha = 0.12f)
    val fg = if (followingGlobal) tokens.ink3 else tokens.accent
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
        )
    }
}

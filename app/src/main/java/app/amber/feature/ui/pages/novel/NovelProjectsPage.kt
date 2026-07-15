package app.amber.feature.ui.pages.novel

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.Screen
import app.amber.feature.novel.model.NovelProjectSummary
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.WorkspaceStatusPill
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.CustomColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.MoreVertical
import org.koin.androidx.compose.koinViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelProjectsPage(
    viewModel: NovelProjectsViewModel = koinViewModel(),
) {
    val navController = LocalNavController.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<NovelProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<NovelProjectSummary?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.openProjectId.collect { projectId ->
            showCreate = false
            navController.navigate(Screen.NovelWorkspace(projectId))
        }
    }

    Scaffold(
        containerColor = workspace.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("小说创作", fontWeight = FontWeight.Bold, color = workspace.ink)
                        Text(
                            "独立项目 · 与聊天隔离",
                            style = type.meta,
                            color = workspace.muted,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!state.busy) showCreate = true },
                containerColor = tokens.ink,
                contentColor = tokens.bg,
                shape = CircleShape,
            ) {
                Icon(HugeIcons.Add01, contentDescription = "新建项目")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AnimatedVisibility(
                visible = state.errorMessage != null,
                enter = fadeIn(tween(NovelMotion.FastMs)) + expandVertically(tween(NovelMotion.MediumMs)),
                exit = fadeOut(tween(NovelMotion.FastMs)) + shrinkVertically(tween(NovelMotion.FastMs)),
            ) {
                NovelBanner(
                    text = state.errorMessage.orEmpty(),
                    tone = WorkspaceTone.Danger,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            val listPhase = when {
                state.loading && state.projects.isEmpty() -> ProjectsPhase.Loading
                state.projects.isEmpty() -> ProjectsPhase.Empty
                else -> ProjectsPhase.List
            }
            AnimatedContent(
                targetState = listPhase,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { NovelMotion.fadeScale() },
                label = "novelProjectsPhase",
            ) { phase ->
                when (phase) {
                    ProjectsPhase.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("加载中…", style = type.secondary, color = workspace.muted)
                        }
                    }
                    ProjectsPhase.Empty -> {
                        NovelEmptyState(
                            title = "还没有小说项目",
                            subtitle = "从空白稿纸开始，或用题材 + 点子快速开篇。项目数据与聊天会话完全隔离。",
                            actionLabel = "新建项目",
                            onAction = { if (!state.busy) showCreate = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    ProjectsPhase.List -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 96.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item {
                                SectionLabel(text = "项目 ${state.projects.size}")
                            }
                            items(state.projects, key = { it.id.rawValue }) { project ->
                                NovelProjectCard(
                                    project = project,
                                    onOpen = {
                                        navController.navigate(
                                            Screen.NovelWorkspace(project.id.rawValue),
                                        )
                                    },
                                    onRename = { renameTarget = project },
                                    onDelete = { deleteTarget = project },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(NovelMotion.MediumMs),
                                        fadeOutSpec = tween(NovelMotion.FastMs),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        NovelCreateProjectDialog(
            busy = state.busy,
            errorMessage = state.errorMessage,
            onDismiss = { if (!state.busy) showCreate = false },
            onCreateBlank = { name -> viewModel.createBlank(name) },
            onCreateQuickStart = { name, genre, idea ->
                viewModel.createQuickStart(name, genre, idea)
            },
        )
    }

    renameTarget?.let { target ->
        NovelNameDialog(
            title = "重命名项目",
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.rename(target.id, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "确定删除「${target.name}」？此操作不可撤销，项目文件会被移除。",
                    style = type.secondary,
                    color = workspace.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) {
                    Text("删除", color = workspace.red, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
            containerColor = workspace.paper,
        )
    }
}

private enum class ProjectsPhase { Loading, Empty, List }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelProjectCard(
    project: NovelProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var menuExpanded by remember { mutableStateOf(false) }
    val formatter = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }

    AmberCard(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuExpanded = true },
            ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NovelIconCircle(icon = HugeIcons.BookOpen01)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = project.name.ifBlank { "未命名项目" },
                    style = type.sessionTitle,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "更新于 ${formatter.format(project.updatedAt)}",
                    style = type.meta,
                    color = workspace.muted,
                )
                if (project.isDegraded) {
                    WorkspaceStatusPill(text = "只读恢复", tone = WorkspaceTone.Danger)
                }
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        HugeIcons.MoreVertical,
                        contentDescription = "更多操作",
                        tint = workspace.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(HugeIcons.Edit02, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = workspace.red) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                HugeIcons.Delete02,
                                contentDescription = null,
                                tint = workspace.red,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NovelCreateProjectDialog(
    busy: Boolean,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onCreateBlank: (String) -> Unit,
    onCreateQuickStart: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var idea by remember { mutableStateOf("") }
    // Default to 快速开始 — matches the product path users expect (auto-generate settings).
    var quickStart by remember { mutableStateOf(true) }
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = LocalAmberTokens.current.ink,
        unfocusedBorderColor = workspace.hairline,
        focusedContainerColor = workspace.paper,
        unfocusedContainerColor = workspace.paper,
    )
    val canSubmit = name.isNotBlank() &&
        (!quickStart || (genre.isNotBlank() && idea.isNotBlank())) &&
        !busy

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = workspace.paper,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (quickStart) "快速开始" else "新建项目",
                    fontWeight = FontWeight.SemiBold,
                    color = workspace.ink,
                )
                Text(
                    if (quickStart) "填写题材与核心想法，生成初始设定建议" else "创建空白稿纸，稍后再完善设定",
                    style = type.meta,
                    color = workspace.muted,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = type.meta,
                        color = workspace.red,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovelChipButton(
                        text = "空白项目",
                        selected = !quickStart,
                        onClick = { quickStart = false },
                        enabled = !busy,
                    )
                    NovelChipButton(
                        text = "快速开始",
                        selected = quickStart,
                        onClick = { quickStart = true },
                        enabled = !busy,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                )
                if (quickStart) {
                    OutlinedTextField(
                        value = genre,
                        onValueChange = { genre = it },
                        label = { Text("题材") },
                        placeholder = { Text("如：都市异能、星际冒险") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                    )
                    OutlinedTextField(
                        value = idea,
                        onValueChange = { idea = it },
                        label = { Text("核心想法") },
                        placeholder = { Text("一句话讲清故事钩子") },
                        enabled = !busy,
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                    )
                }
            }
        },
        confirmButton = {
            NovelPrimaryButton(
                text = if (busy) "创建中…" else "创建",
                onClick = {
                    if (quickStart) onCreateQuickStart(name, genre, idea)
                    else onCreateBlank(name)
                },
                enabled = canSubmit,
                accent = true,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("取消", color = workspace.muted)
            }
        },
    )
}

@Composable
private fun NovelNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val workspace = workspaceColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = workspace.paper,
        title = { Text(title, fontWeight = FontWeight.SemiBold, color = workspace.ink) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LocalAmberTokens.current.ink,
                    unfocusedBorderColor = workspace.hairline,
                ),
            )
        },
        confirmButton = {
            NovelPrimaryButton(
                text = "确定",
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                accent = true,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = workspace.muted) }
        },
    )
}


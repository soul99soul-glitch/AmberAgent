package app.amber.feature.ui.pages.novel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.Screen
import app.amber.feature.novelworkspace.NovelWorkspaceProjectSummary
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceStatusPill
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.CustomColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.MoreVertical
import org.koin.androidx.compose.koinViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MAX_IMPORT_BYTES = 256 * 1024 * 1024

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<NovelWorkspaceProjectSummary?>(null) }
    var renameTarget by remember { mutableStateOf<NovelWorkspaceProjectSummary?>(null) }
    var pendingExport by remember {
        mutableStateOf<Pair<String, ByteArray>?>(null)
    }

    // Re-list on every composition entry: the ViewModel outlives the push, and its
    // cached list predates any book created inside the workspace (device-observed:
    // a newly created book was invisible until an app restart). In-app navigation
    // never fires Activity ON_RESUME, so the re-entry signal is composition itself.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val openImport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.beginImportRead()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream(minOf(64 * 1024, MAX_IMPORT_BYTES))
                        val chunk = ByteArray(64 * 1024)
                        var total = 0
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMPORT_BYTES) return@use null // oversize sentinel
                            out.write(chunk, 0, read)
                        }
                        out.toByteArray()
                    }
                }
            }
            viewModel.endImportRead()
            result.fold(
                onSuccess = { bytes ->
                    when {
                        bytes == null -> viewModel.reportError("导入文件超过上限（约 256MB）或无法打开")
                        bytes.isEmpty() -> viewModel.reportError("导入文件为空")
                        else -> viewModel.importZip(bytes) { }
                    }
                },
                onFailure = {
                    viewModel.reportError("无法读取导入文件：${it.message}")
                },
            )
        }
    }

    val createZipDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null) {
            viewModel.reportError("已取消导出")
            return@rememberLauncherForActivityResult
        }
        if (payload == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(payload.second) }
                        ?: error("无法打开输出流")
                }.isSuccess
            }
            if (ok) {
                viewModel.reportStatus("已保存工作区 ${payload.first}")
            } else {
                viewModel.reportError("写入导出文件失败")
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.openWorkspaceProjectId.collect { projectId ->
            showCreate = false
            navController.navigate(Screen.NovelMarkdown(projectId))
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
                actions = {
                    NovelQuietButton(
                        text = "导入",
                        onClick = {
                            openImport.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        },
                        enabled = !state.busy,
                    )
                },
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
            AnimatedVisibility(
                visible = state.statusMessage != null && state.errorMessage == null,
                enter = fadeIn(tween(NovelMotion.FastMs)) + expandVertically(tween(NovelMotion.MediumMs)),
                exit = fadeOut(tween(NovelMotion.FastMs)) + shrinkVertically(tween(NovelMotion.FastMs)),
            ) {
                NovelBanner(
                    text = state.statusMessage.orEmpty(),
                    tone = WorkspaceTone.Success,
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
                            subtitle = "用新的 Markdown 工作区格式开一本空白书。项目数据与聊天会话完全隔离。",
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
                            items(state.projects, key = { it.id }) { project ->
                                NovelProjectCard(
                                    project = project,
                                    busy = state.busy,
                                    onOpen = {
                                        navController.navigate(Screen.NovelMarkdown(project.id))
                                    },
                                    onRename = { renameTarget = project },
                                    onDelete = { deleteTarget = project },
                                    onExportZip = {
                                        viewModel.exportZip(project.id) { name, bytes ->
                                            pendingExport = name to bytes
                                            createZipDoc.launch(name)
                                        }
                                    },
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
            onCreate = { name -> viewModel.createBlankWorkspace(name) },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) deleteTarget = null },
            title = { Text("删除项目", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "确定删除「${target.name}」？此操作不可撤销，项目文件会被移除。",
                    style = type.secondary,
                    color = workspace.muted,
                )
            },
            confirmButton = {
                NovelGhostButton(
                    text = if (state.busy) "删除中…" else "删除",
                    onClick = {
                        viewModel.delete(target.id)
                        deleteTarget = null
                    },
                    danger = true,
                    enabled = !state.busy,
                )
            },
            dismissButton = {
                NovelQuietButton(
                    text = "取消",
                    onClick = { deleteTarget = null },
                    enabled = !state.busy,
                )
            },
            containerColor = workspace.paper,
        )
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { if (!state.busy) renameTarget = null },
            title = { Text("重命名项目", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                NovelGhostButton(
                    text = "确定",
                    onClick = {
                        viewModel.renameProject(target.id, name)
                        renameTarget = null
                    },
                    enabled = !state.busy && name.isNotBlank(),
                )
            },
            dismissButton = {
                NovelQuietButton(
                    text = "取消",
                    onClick = { renameTarget = null },
                    enabled = !state.busy,
                )
            },
            containerColor = workspace.paper,
        )
    }
}

private enum class ProjectsPhase { Loading, Empty, List }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelProjectCard(
    project: NovelWorkspaceProjectSummary,
    busy: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExportZip: () -> Unit,
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                WorkspaceStatusPill(text = "工作区", tone = WorkspaceTone.Success)
            }
            Box {
                NovelIconButton(
                    icon = HugeIcons.MoreVertical,
                    contentDescription = "更多操作",
                    onClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        enabled = !busy,
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出工作区") },
                        enabled = !busy,
                        onClick = {
                            menuExpanded = false
                            onExportZip()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = workspace.red) },
                        enabled = !busy,
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
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = LocalAmberTokens.current.ink,
        unfocusedBorderColor = workspace.hairline,
        focusedContainerColor = workspace.paper,
        unfocusedContainerColor = workspace.paper,
    )
    val canSubmit = name.isNotBlank() && !busy

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = workspace.paper,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "新建工作区书籍",
                    fontWeight = FontWeight.SemiBold,
                    color = workspace.ink,
                )
                Text(
                    "用新的 Markdown 工作区格式开一本空白书",
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
            }
        },
        confirmButton = {
            NovelPrimaryButton(
                text = if (busy) "创建中…" else "创建",
                onClick = { onCreate(name) },
                enabled = canSubmit,
                accent = true,
                compact = true,
            )
        },
        dismissButton = {
            NovelQuietButton(
                text = "取消",
                onClick = onDismiss,
                enabled = !busy,
            )
        },
    )
}

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.novelworkspace.NovelWorkspaceBookExport
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
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.Trash
import com.composables.icons.lucide.EllipsisVertical
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
    val importTooLargeError = stringResource(R.string.novel_import_file_too_large_or_unreadable)
    val importEmptyError = stringResource(R.string.novel_import_file_empty)
    val exportWriteError = stringResource(R.string.novel_export_write_failed)

    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<NovelWorkspaceProjectSummary?>(null) }
    var renameTarget by remember { mutableStateOf<NovelWorkspaceProjectSummary?>(null) }
    var bookExportTarget by remember { mutableStateOf<NovelWorkspaceProjectSummary?>(null) }
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
                        bytes == null -> viewModel.reportError(importTooLargeError)
                        bytes.isEmpty() -> viewModel.reportError(importEmptyError)
                        else -> viewModel.importZip(bytes) { }
                    }
                },
                onFailure = {
                    viewModel.reportError(
                        context.getString(R.string.novel_import_file_read_failed, it.message.orEmpty()),
                    )
                },
            )
        }
    }

    val createZipDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        val payload = pendingExport
        pendingExport = null
        // 用户取消（系统文件选择器返回 null）静默返回，与系统取消语义一致。
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(payload.second) }
                        ?: error("无法打开输出流")
                }.isSuccess
            }
            if (ok) {
                viewModel.reportStatus(
                    context.getString(R.string.novel_workspace_export_saved, payload.first),
                )
            } else {
                viewModel.reportError(exportWriteError)
            }
        }
    }

    // CreateDocument pins the mime at construction, so each book format gets its own
    // launcher; the write-back is shared with the zip flow via pendingExport.
    val writeBookExport: (Uri?) -> Unit = { uri: Uri? ->
        val payload = pendingExport
        pendingExport = null
        when {
            // 用户取消（uri == null）静默返回，与系统文件选择器的取消语义一致。
            uri == null -> Unit
            payload == null -> Unit
            else -> scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(payload.second) }
                            ?: error("无法打开输出流")
                    }.isSuccess
                }
                if (ok) {
                    viewModel.reportStatus(
                        context.getString(R.string.novel_book_export_saved, payload.first),
                    )
                } else {
                    viewModel.reportError(exportWriteError)
                }
            }
        }
    }
    val createTxtDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
        writeBookExport,
    )
    val createMarkdownDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
        writeBookExport,
    )
    val createEpubDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip"),
        writeBookExport,
    )

    val startBookExport: (NovelWorkspaceProjectSummary, NovelWorkspaceBookExport.Format) -> Unit =
        { target, format ->
            bookExportTarget = null
            scope.launch {
                val bytes = viewModel.exportBook(target.id, format) ?: return@launch
                val name = NovelWorkspaceBookExport.suggestFileName(target.name, format)
                pendingExport = name to bytes
                when (format) {
                    NovelWorkspaceBookExport.Format.TXT -> createTxtDoc.launch(name)
                    NovelWorkspaceBookExport.Format.MARKDOWN -> createMarkdownDoc.launch(name)
                    NovelWorkspaceBookExport.Format.EPUB -> createEpubDoc.launch(name)
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
                        Text(
                            stringResource(R.string.novel_projects_title),
                            fontWeight = FontWeight.Bold,
                            color = workspace.ink,
                        )
                        Text(
                            stringResource(R.string.novel_projects_subtitle),
                            style = type.meta,
                            color = workspace.muted,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                actions = {
                    NovelQuietButton(
                        text = stringResource(R.string.novel_import),
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
                Icon(
                    Lucide.Plus,
                    contentDescription = stringResource(R.string.novel_new_project),
                )
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
                            Text(
                                stringResource(R.string.novel_loading),
                                style = type.secondary,
                                color = workspace.muted,
                            )
                        }
                    }
                    ProjectsPhase.Empty -> {
                        NovelEmptyState(
                            title = stringResource(R.string.novel_projects_empty_title),
                            subtitle = stringResource(R.string.novel_projects_empty_subtitle),
                            actionLabel = stringResource(R.string.novel_new_project),
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
                                SectionLabel(
                                    text = stringResource(R.string.novel_projects_count, state.projects.size),
                                )
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
                                    onExportBook = { bookExportTarget = project },
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
            title = {
                Text(
                    stringResource(R.string.novel_delete_project_title),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    stringResource(R.string.novel_delete_project_message, target.name),
                    style = type.secondary,
                    color = workspace.muted,
                )
            },
            confirmButton = {
                NovelGhostButton(
                    text = if (state.busy) {
                        stringResource(R.string.novel_deleting)
                    } else {
                        stringResource(R.string.delete)
                    },
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
                    text = stringResource(R.string.cancel),
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
            title = {
                Text(
                    stringResource(R.string.novel_rename_project_title),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.novel_project_name)) },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                NovelGhostButton(
                    text = stringResource(R.string.confirm),
                    onClick = {
                        viewModel.renameProject(target.id, name)
                        renameTarget = null
                    },
                    enabled = !state.busy && name.isNotBlank(),
                )
            },
            dismissButton = {
                NovelQuietButton(
                    text = stringResource(R.string.cancel),
                    onClick = { renameTarget = null },
                    enabled = !state.busy,
                )
            },
            containerColor = workspace.paper,
        )
    }

    bookExportTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { bookExportTarget = null },
            containerColor = workspace.paper,
            title = {
                Text(
                    stringResource(R.string.novel_export_book_title),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.novel_export_book_description, target.name),
                        style = type.meta,
                        color = workspace.muted,
                    )
                    NovelGhostButton(
                        text = stringResource(R.string.novel_export_txt),
                        onClick = { startBookExport(target, NovelWorkspaceBookExport.Format.TXT) },
                    )
                    NovelGhostButton(
                        text = stringResource(R.string.chat_page_export_markdown),
                        onClick = { startBookExport(target, NovelWorkspaceBookExport.Format.MARKDOWN) },
                    )
                    NovelGhostButton(
                        text = stringResource(R.string.novel_export_epub),
                        onClick = { startBookExport(target, NovelWorkspaceBookExport.Format.EPUB) },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                NovelQuietButton(
                    text = stringResource(R.string.cancel),
                    onClick = { bookExportTarget = null },
                )
            },
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
    onExportBook: () -> Unit,
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
            NovelIconCircle(icon = Lucide.BookOpenText)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (project.name.isBlank()) {
                        stringResource(R.string.novel_untitled_project)
                    } else {
                        project.name
                    },
                    style = type.sessionTitle,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.novel_project_updated_at,
                        formatter.format(project.updatedAt),
                    ),
                    style = type.meta,
                    color = workspace.muted,
                )
                WorkspaceStatusPill(
                    text = stringResource(R.string.novel_workspace_status),
                    tone = WorkspaceTone.Success,
                )
            }
            Box {
                NovelIconButton(
                    icon = Lucide.EllipsisVertical,
                    contentDescription = stringResource(R.string.novel_more_actions),
                    onClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.novel_rename)) },
                        enabled = !busy,
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.novel_export_workspace)) },
                        enabled = !busy,
                        onClick = {
                            menuExpanded = false
                            onExportZip()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.novel_export_book_title)) },
                        enabled = !busy,
                        onClick = {
                            menuExpanded = false
                            onExportBook()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = workspace.red) },
                        enabled = !busy,
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Lucide.Trash,
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
                    stringResource(R.string.novel_create_workspace_book_title),
                    fontWeight = FontWeight.SemiBold,
                    color = workspace.ink,
                )
                Text(
                    stringResource(R.string.novel_create_workspace_book_subtitle),
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
                    label = { Text(stringResource(R.string.novel_project_name)) },
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
                text = if (busy) {
                    stringResource(R.string.novel_creating)
                } else {
                    stringResource(R.string.novel_create)
                },
                onClick = { onCreate(name) },
                enabled = canSubmit,
                accent = true,
                compact = true,
            )
        },
        dismissButton = {
            NovelQuietButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
                enabled = !busy,
            )
        },
    )
}

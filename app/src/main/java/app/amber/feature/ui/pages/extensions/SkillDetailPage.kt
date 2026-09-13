package app.amber.feature.ui.pages.extensions

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.core.ai.mcp.McpImportPreview
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ui.ConfirmDialog
import app.amber.feature.ui.components.ui.WorkspaceIconButton
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.WorkspaceStatusPill
import app.amber.feature.ui.components.ui.WorkspaceTextButton
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.CancellationException

@Composable
fun SkillDetailPage(skillName: String) {
    val vm = koinViewModel<SkillDetailVM>()
    LaunchedEffect(skillName) { vm.init(skillName) }

    val tree by vm.tree.collectAsStateWithLifecycle()
    val mcpConfig by vm.mcpConfig.collectAsStateWithLifecycle()
    val mcpImportPreview by vm.mcpImportPreview.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val context = LocalContext.current

    var editingFile by remember { mutableStateOf<SkillFile?>(null) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillFile?>(null) }
    val deleteFailedMsg = stringResource(R.string.skill_detail_page_delete_failed)

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = skillName,
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .amberCanvas()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    innerPadding + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp)
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            mcpConfig?.let { state ->
                SectionLabel(
                    text = stringResource(R.string.skill_detail_page_mcp_config_title),
                )
                SkillMcpConfigCard(
                    state = state,
                    onImport = {
                        vm.previewMcpConfig { error ->
                            error?.let { toaster.show(it) }
                        }
                    },
                )
            }
            SectionLabel(
                text = stringResource(R.string.setting_skill_detail_files_title),
            )
            SkillFilesPanel(
                nodes = tree,
                fileCount = remember(tree) { tree.countFiles() },
                onAdd = { showAddDialog = true },
                onEdit = { editingFile = it },
                onDelete = { deleteTarget = it },
            )
        }
    }

    editingFile?.let { skillFile ->
        var initialContent by remember(skillFile) { mutableStateOf<String?>(null) }
        LaunchedEffect(skillFile) {
            try {
                initialContent = vm.readFile(skillFile)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toaster.show(error.message ?: context.getString(R.string.workspace_read_failed))
                editingFile = null
            }
        }
        initialContent?.let { loadedContent ->
            EditFileDialog(
                skillFile = skillFile,
                initialContent = loadedContent,
                onDismiss = { editingFile = null },
                onConfirm = { content ->
                    vm.saveFile(skillFile.relativePath, content) { error ->
                        if (error == null) editingFile = null
                        else toaster.show(error)
                    }
                },
            )
        }
    }

    if (showAddDialog) {
        AddFileDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { fileName, content ->
                vm.saveFile(fileName, content) { error ->
                    if (error == null) showAddDialog = false
                    else toaster.show(error)
                }
            },
        )
    }

    ConfirmDialog(
        show = mcpImportPreview != null,
        title = stringResource(R.string.setting_skill_detail_import_mcp_title),
        confirmText = stringResource(R.string.setting_skill_detail_import_mcp_confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            vm.confirmMcpConfig { message -> toaster.show(message) }
        },
        onDismiss = vm::clearMcpImportPreview,
    ) {
        mcpImportPreview?.let { preview ->
            Text(preview.toRedactedSummary(context))
        }
    }

    ConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skill_detail_page_delete_file),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { skillFile ->
                vm.deleteFile(skillFile) { success ->
                    if (!success) toaster.show(deleteFailedMsg)
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.skill_detail_page_delete_confirm, deleteTarget?.relativePath ?: ""))
    }
}

@Composable
private fun SkillMcpConfigCard(
    state: SkillMcpConfigState,
    onImport: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = tokens.surface,
        borderColor = tokens.line,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WorkspaceLeadingIcon(icon = Lucide.FileText, tone = WorkspaceTone.Accent)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(R.string.skill_detail_page_mcp_config_title),
                    style = type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = tokens.ink,
                )
                Text(
                    text = state.error ?: stringResource(
                        R.string.skill_detail_page_mcp_config_desc,
                        state.serverCount,
                    ),
                    style = type.secondary,
                    color = tokens.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.error == null && state.serverCount > 0) {
                WorkspaceTextButton(
                    text = stringResource(R.string.skill_detail_page_mcp_config_import),
                    onClick = onImport,
                    tone = WorkspaceTone.Accent,
                )
            } else {
                WorkspaceStatusPill(
                    text = stringResource(R.string.error_title_tool_unavailable),
                    tone = WorkspaceTone.Neutral,
                )
            }
        }
    }
}

@Composable
private fun SkillFilesPanel(
    nodes: List<SkillFileNode>,
    fileCount: Int,
    onAdd: () -> Unit,
    onEdit: (SkillFile) -> Unit,
    onDelete: (SkillFile) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = tokens.surface,
        borderColor = tokens.line,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_skill_detail_files_title),
                style = type.sessionTitle,
                color = tokens.ink,
            )
            Text(
                text = stringResource(R.string.setting_skill_detail_file_count, fileCount),
                style = type.meta,
                color = tokens.ink2,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onAdd,
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text(
                    text = "+ " + stringResource(R.string.skill_detail_page_new_file),
                    style = type.tinyTag,
                    color = tokens.accent,
                )
            }
        }
        Hairline()
        if (nodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.setting_files_page_no_files),
                    style = type.secondary,
                    color = tokens.ink2,
                )
            }
        } else {
            FileTree(
                nodes = nodes,
                depth = 0,
                onEdit = onEdit,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun FileTree(
    nodes: List<SkillFileNode>,
    depth: Int,
    onEdit: (SkillFile) -> Unit,
    onDelete: (SkillFile) -> Unit,
) {
    nodes.forEachIndexed { index, node ->
        if (index > 0) Hairline()
        when (node) {
            is SkillFileNode.FileNode -> FileItem(
                skillFile = node.skillFile,
                depth = depth,
                onEdit = { onEdit(node.skillFile) },
                onDelete = { onDelete(node.skillFile) },
            )

            is SkillFileNode.DirNode -> DirItem(
                node = node,
                depth = depth,
                onEdit = onEdit,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun FileItem(
    skillFile: SkillFile,
    depth: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(start = (16 + depth * 18).dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                imageVector = Lucide.FileText,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tokens.ink2,
            )
            Text(
                text = skillFile.file.name,
                style = type.meta.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            WorkspaceStatusPill(text = "${skillFile.file.length()} B")
            WorkspaceIconButton(
                onClick = onEdit,
                icon = Lucide.FilePen,
                contentDescription = stringResource(R.string.edit),
                size = 30.dp,
                iconSize = 15.dp,
                showBorder = false,
                containerColor = Color.Transparent,
            )
            if (skillFile.relativePath != "SKILL.md") {
                WorkspaceIconButton(
                    onClick = onDelete,
                    icon = Lucide.Trash2,
                    contentDescription = stringResource(R.string.delete),
                    size = 30.dp,
                    iconSize = 15.dp,
                    showBorder = false,
                    containerColor = Color.Transparent,
                    tone = WorkspaceTone.Danger,
                )
            }
        }
    }
}

@Composable
private fun DirItem(
    node: SkillFileNode.DirNode,
    depth: Int,
    onEdit: (SkillFile) -> Unit,
    onDelete: (SkillFile) -> Unit,
) {
    var expanded by rememberSaveable(node.relativePath) { mutableStateOf(false) }
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clickable { expanded = !expanded }
                .padding(start = (16 + depth * 18).dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = tokens.ink3,
            )
            Icon(
                imageVector = if (expanded) Lucide.FolderOpen else Lucide.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tokens.ink2,
            )
            Text(
                text = node.name,
                style = type.meta.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            FileTree(
                nodes = node.children,
                depth = depth + 1,
                onEdit = onEdit,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun EditFileDialog(
    skillFile: SkillFile,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (content: String) -> Unit,
) {
    var content by rememberSaveable(skillFile.relativePath) { mutableStateOf(initialContent) }
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = tokens.raised,
        title = { Text(skillFile.relativePath, style = type.sessionTitle.copy(fontFamily = FontFamily.Monospace)) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.skill_detail_page_content)) },
                minLines = 10,
                maxLines = 20,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = skillDetailFieldColors(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(content) }) { Text(stringResource(R.string.skill_detail_page_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun List<SkillFileNode>.countFiles(): Int = sumOf { node ->
    when (node) {
        is SkillFileNode.FileNode -> 1
        is SkillFileNode.DirNode -> node.children.countFiles()
    }
}

private fun McpImportPreview.toRedactedSummary(context: Context): String = buildString {
    append(context.getString(R.string.setting_skill_detail_import_risk, risk))
    append("\n").append(context.getString(R.string.setting_skill_detail_import_server_count, serverCount))
    append("\n").append(context.getString(R.string.setting_skill_detail_import_digest, digest))
    servers.forEach { server ->
        append("\n\n").append(server.serverName)
        append("\ntransport：").append(server.transport.name.lowercase())
        append("\norigin：").append(server.origin)
        append("\nrisk：").append(server.risk)
        append("\nheader names：")
        append(
            server.headerNames
                .ifEmpty { listOf(context.getString(R.string.setting_skill_detail_import_no_headers)) }
                .joinToString("、")
        )
        server.note?.let { append("\n").append(context.getString(R.string.setting_skill_detail_import_note, it)) }
    }
}

@Composable
private fun AddFileDialog(
    onDismiss: () -> Unit,
    onConfirm: (fileName: String, content: String) -> Unit,
) {
    var fileName by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    val fileNameError = fileName.isNotBlank() && (fileName.contains('\\'))
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = tokens.raised,
        title = { Text(stringResource(R.string.skill_detail_page_new_file), style = type.sessionTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(stringResource(R.string.skill_detail_page_file_name)) },
                    placeholder = { Text("examples/basic.md", fontFamily = FontFamily.Monospace) },
                    supportingText = {
                        if (fileNameError) Text(
                            stringResource(R.string.skill_detail_page_file_name_invalid),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    isError = fileNameError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = skillDetailFieldColors(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.skill_detail_page_content)) },
                    minLines = 6,
                    maxLines = 14,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = skillDetailFieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(fileName.trim(), content) },
                enabled = fileName.isNotBlank() && !fileNameError,
            ) {
                Text(stringResource(R.string.skill_detail_page_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun skillDetailFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = LocalAmberTokens.current.surface2,
    unfocusedContainerColor = LocalAmberTokens.current.surface2,
    focusedBorderColor = LocalAmberTokens.current.accent,
    unfocusedBorderColor = LocalAmberTokens.current.line,
    focusedLabelColor = LocalAmberTokens.current.accent,
    unfocusedLabelColor = LocalAmberTokens.current.ink3,
    focusedTextColor = LocalAmberTokens.current.ink,
    unfocusedTextColor = LocalAmberTokens.current.ink,
    cursorColor = LocalAmberTokens.current.accent,
)

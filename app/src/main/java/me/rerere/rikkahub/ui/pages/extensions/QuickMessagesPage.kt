package me.rerere.rikkahub.ui.pages.extensions

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import app.amber.core.model.QuickMessage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.WorkspaceIconButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceLeadingIcon
import me.rerere.rikkahub.ui.components.ui.WorkspaceTextButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.WorkspaceTopBar
import me.rerere.rikkahub.ui.components.ui.workspaceBorder
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun QuickMessagesPage(vm: QuickMessagesVM = koinViewModel()) {
    val settings = vm.settings.collectAsStateWithLifecycle().value
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val workspace = workspaceColors()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<QuickMessage?>(null) }
    var deleteTarget by remember { mutableStateOf<QuickMessage?>(null) }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.assistant_page_quick_messages),
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.quick_messages_page_empty_action),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspace.canvas,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                QuickMessagesHeader(
                    count = settings.quickMessages.size,
                    onAdd = { showAddDialog = true },
                )
            }
            if (settings.quickMessages.isEmpty()) {
                item {
                    QuickMessagesEmptyState(onAdd = { showAddDialog = true })
                }
            }

            items(settings.quickMessages, key = { it.id }) { quickMessage ->
                QuickMessageCard(
                    quickMessage = quickMessage,
                    onEdit = { editTarget = quickMessage },
                    onDelete = { deleteTarget = quickMessage },
                )
            }
        }
    }

    if (showAddDialog) {
        EditQuickMessageDialog(
            title = stringResource(R.string.quick_messages_page_add_title),
            initialQuickMessage = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { title, content ->
                vm.addQuickMessage(title, content)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { quickMessage ->
        EditQuickMessageDialog(
            title = stringResource(R.string.quick_messages_page_edit_title),
            initialQuickMessage = quickMessage,
            onDismiss = { editTarget = null },
            onConfirm = { title, content ->
                vm.updateQuickMessage(
                    quickMessage.copy(
                        title = title,
                        content = content,
                    )
                )
                editTarget = null
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.quick_messages_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteQuickMessage(it.id) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.quick_messages_page_delete_message, deleteTarget?.title ?: ""))
    }
}

@Composable
private fun QuickMessagesHeader(
    count: Int,
    onAdd: () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = workspace.paper,
        border = workspaceBorder(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceLeadingIcon(
                icon = HugeIcons.Zap,
                tone = WorkspaceTone.Accent,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.assistant_page_quick_messages),
                    style = MaterialTheme.typography.titleSmall,
                    color = workspace.ink,
                )
                Text(
                    text = stringResource(R.string.quick_messages_page_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
            }
            WorkspaceTextButton(
                text = stringResource(R.string.quick_messages_page_empty_action),
                onClick = onAdd,
                tone = WorkspaceTone.Accent,
            )
        }
    }
}

@Composable
private fun QuickMessagesEmptyState(
    onAdd: () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = workspace.paper,
        border = workspaceBorder(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WorkspaceLeadingIcon(
                icon = HugeIcons.Zap,
                tone = WorkspaceTone.Neutral,
            )
            Text(
                text = stringResource(R.string.quick_messages_page_empty_title),
                style = MaterialTheme.typography.titleSmall,
                color = workspace.ink,
            )
            Text(
                text = stringResource(R.string.quick_messages_page_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
            )
            WorkspaceTextButton(
                text = stringResource(R.string.quick_messages_page_empty_action),
                onClick = onAdd,
                tone = WorkspaceTone.Accent,
            )
        }
    }
}

@Composable
private fun QuickMessageCard(
    quickMessage: QuickMessage,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val workspace = workspaceColors()

    Surface(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = workspace.paper,
        border = workspaceBorder(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceLeadingIcon(
                icon = HugeIcons.Zap,
                tone = WorkspaceTone.Accent,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = quickMessage.title.ifBlank { stringResource(R.string.quick_messages_page_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = quickMessage.content.ifBlank { stringResource(R.string.quick_messages_page_empty_content) },
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                WorkspaceIconButton(
                    onClick = { menuExpanded = true },
                    icon = HugeIcons.MoreVertical,
                    contentDescription = stringResource(R.string.skills_page_more_actions),
                    showBorder = false,
                    containerColor = workspace.paper,
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Edit01,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditQuickMessageDialog(
    title: String,
    initialQuickMessage: QuickMessage?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, content: String) -> Unit,
) {
    val workspace = workspaceColors()
    var quickMessageTitle by rememberSaveable(initialQuickMessage?.id) {
        mutableStateOf(initialQuickMessage?.title ?: "")
    }
    var quickMessageContent by rememberSaveable(initialQuickMessage?.id) {
        mutableStateOf(initialQuickMessage?.content ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(8.dp),
        containerColor = workspace.paper,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = workspace.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NotionQuickMessageField(
                    value = quickMessageTitle,
                    onValueChange = { quickMessageTitle = it },
                    label = { Text(stringResource(R.string.assistant_page_quick_message_title)) },
                    singleLine = true,
                )
                NotionQuickMessageField(
                    value = quickMessageContent,
                    onValueChange = { quickMessageContent = it },
                    label = { Text(stringResource(R.string.assistant_page_quick_message_content)) },
                    minLines = 4,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(quickMessageTitle.trim(), quickMessageContent.trim()) },
                enabled = quickMessageTitle.isNotBlank() && quickMessageContent.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = workspace.blue,
                    disabledContentColor = workspace.faint,
                ),
            ) {
                Text(stringResource(R.string.assistant_page_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = workspace.muted),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun NotionQuickMessageField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit),
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    val workspace = workspaceColors()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(6.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = workspace.note,
            unfocusedContainerColor = workspace.note,
            focusedBorderColor = workspace.blue,
            unfocusedBorderColor = workspace.hairline,
            focusedLabelColor = workspace.blue,
            unfocusedLabelColor = workspace.muted,
            cursorColor = workspace.blue,
        ),
    )
}

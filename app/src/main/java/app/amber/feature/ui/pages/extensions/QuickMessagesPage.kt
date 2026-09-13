package app.amber.feature.ui.pages.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.core.model.QuickMessage
import app.amber.core.utils.plus
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.ConfirmDialog
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Zap
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import org.koin.androidx.compose.koinViewModel

@Composable
fun QuickMessagesPage(vm: QuickMessagesVM = koinViewModel()) {
    val settings = vm.settings.collectAsStateWithLifecycle().value
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<QuickMessage?>(null) }
    var deleteTarget by remember { mutableStateOf<QuickMessage?>(null) }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.quick_messages_page_title),
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Lucide.Plus,
                            contentDescription = stringResource(R.string.quick_messages_page_empty_action),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .amberCanvas()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                QuickMessagesHeader(
                    count = settings.quickMessages.size,
                    onAdd = { showAddDialog = true },
                )
            }

            if (settings.quickMessages.isEmpty()) {
                item { QuickMessagesEmptyState(onAdd = { showAddDialog = true }) }
            } else {
                item {
                    SectionLabel(
                        text = stringResource(R.string.quick_messages_page_count, settings.quickMessages.size),
                        modifier = Modifier.padding(top = 20.dp, start = 2.dp, bottom = 2.dp),
                    )
                }
                itemsIndexed(
                    items = settings.quickMessages,
                    key = { _, quickMessage -> quickMessage.id },
                ) { index, quickMessage ->
                    QuickMessageCard(
                        quickMessage = quickMessage,
                        onEdit = { editTarget = quickMessage },
                        onDelete = { deleteTarget = quickMessage },
                        showDivider = index > 0,
                        groupedFirst = index == 0,
                        groupedLast = index == settings.quickMessages.lastIndex,
                    )
                }
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
                vm.updateQuickMessage(quickMessage.copy(title = title, content = content))
                editTarget = null
            },
        )
    }

    ConfirmDialog(
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
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = tokens.raised,
        borderColor = tokens.line2,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AmberLibraryTile(icon = Lucide.Zap)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.quick_messages_page_title),
                    style = type.sessionTitle,
                    color = tokens.ink,
                )
                Text(
                    text = stringResource(R.string.quick_messages_page_count, count),
                    style = type.meta,
                    color = tokens.ink2,
                )
            }
        }
        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .pressable(onClick = onAdd)
            .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(17.dp), tint = tokens.accent)
            Text(
                text = stringResource(R.string.quick_messages_page_empty_action),
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.accent,
            )
        }
    }
}

@Composable
private fun QuickMessagesEmptyState(onAdd: () -> Unit) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AmberLibraryTile(icon = Lucide.Zap)
            Text(
                text = stringResource(R.string.quick_messages_page_empty_title),
                style = type.sessionTitle,
                color = tokens.ink,
            )
            Text(
                text = stringResource(R.string.quick_messages_page_empty_hint),
                style = type.secondary,
                color = tokens.ink2,
            )
            TextButton(
                onClick = onAdd,
                colors = ButtonDefaults.textButtonColors(contentColor = tokens.accent),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(stringResource(R.string.quick_messages_page_empty_action))
            }
        }
    }
}

@Composable
private fun QuickMessageCard(
    quickMessage: QuickMessage,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    showDivider: Boolean,
    groupedFirst: Boolean,
    groupedLast: Boolean,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (showDivider) Hairline()
        val rowShape = RoundedCornerShape(
            topStart = if (groupedFirst) 14.dp else 0.dp,
            topEnd = if (groupedFirst) 14.dp else 0.dp,
            bottomStart = if (groupedLast) 14.dp else 0.dp,
            bottomEnd = if (groupedLast) 14.dp else 0.dp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .background(tokens.surface, rowShape)
                .border(1.dp, tokens.line, rowShape)
                .pressable(onClick = onEdit)
                .padding(start = 16.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AmberLibraryTile(icon = Lucide.Zap)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = quickMessage.title.ifBlank { stringResource(R.string.quick_messages_page_untitled) },
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = quickMessage.content.ifBlank { stringResource(R.string.quick_messages_page_empty_content) },
                    style = type.secondary,
                    color = tokens.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Lucide.EllipsisVertical,
                        contentDescription = stringResource(R.string.skills_page_more_actions),
                        modifier = Modifier.size(18.dp),
                        tint = tokens.ink3,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = { Icon(Lucide.Pencil, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Lucide.Trash2,
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
private fun AmberLibraryTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val tokens = LocalAmberTokens.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(tokens.surface2, RoundedCornerShape(9.dp))
            .border(1.dp, tokens.line, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tokens.ink2)
    }
}

@Composable
private fun EditQuickMessageDialog(
    title: String,
    initialQuickMessage: QuickMessage?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, content: String) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    var quickMessageTitle by rememberSaveable(initialQuickMessage?.id) {
        mutableStateOf(initialQuickMessage?.title ?: "")
    }
    var quickMessageContent by rememberSaveable(initialQuickMessage?.id) {
        mutableStateOf(initialQuickMessage?.content ?: "")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(18.dp),
            color = tokens.raised,
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line2),
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = title,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    style = type.sessionTitle,
                    color = tokens.ink,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    QuickMessageField(
                        value = quickMessageTitle,
                        onValueChange = { quickMessageTitle = it },
                        label = stringResource(R.string.quick_messages_page_title_label),
                        singleLine = true,
                    )
                    QuickMessageField(
                        value = quickMessageContent,
                        onValueChange = { quickMessageContent = it },
                        label = stringResource(R.string.quick_messages_page_content_label),
                        minLines = 5,
                        maxLines = 12,
                    )
                }
                Hairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = tokens.ink2),
                    ) { Text(stringResource(R.string.cancel)) }
                    androidx.compose.material3.Button(
                        onClick = { onConfirm(quickMessageTitle.trim(), quickMessageContent.trim()) },
                        enabled = quickMessageTitle.isNotBlank() && quickMessageContent.isNotBlank(),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.accent,
                            contentColor = tokens.accentInk,
                        ),
                    ) { Text(stringResource(R.string.common_save)) }
                }
            }
        }
    }
}

@Composable
private fun QuickMessageField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            modifier = Modifier.padding(start = 2.dp),
            style = type.tinyTag,
            color = tokens.ink2,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 44.dp else 110.dp)
                .background(tokens.surface2, RoundedCornerShape(11.dp))
                .border(1.dp, tokens.line, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            enabled = true,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = type.body.copy(color = tokens.ink),
            cursorBrush = SolidColor(tokens.accent),
        )
    }
}

package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType

private enum class MaterialKind(val value: String, val label: Int) {
    World("world", R.string.novel_material_world),
    Character("character", R.string.novel_material_character),
    Relationship("relationship", R.string.novel_material_relationship),
    Outline("masterOutline", R.string.novel_material_outline),
    Writing("writingRequirements", R.string.novel_material_writing),
    Custom("custom", R.string.novel_material_custom),
}

@Composable
internal fun MarkdownMaterialCreateSheet(
    busy: Boolean,
    writeLocked: Boolean,
    errorMessage: String?,
    decision: Boolean = false,
    initialBody: String = "",
    initialKind: String = "world",
    onCreate: (String, String, String, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(initialBody) { mutableStateOf(
        if (decision) initialBody.lineSequence().firstOrNull().orEmpty().trimStart('#', ' ').take(80) else "",
    ) }
    var body by remember(initialBody) { mutableStateOf(initialBody) }
    var kind by remember(initialKind) { mutableStateOf(MaterialKind.entries.first { it.value == initialKind }) }
    val editable = !busy && !writeLocked
    NovelReviewSheetFrame(stringResource(if (decision) R.string.novel_archive_decision else R.string.novel_add_material), onDismiss) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NovelReviewLockNotice(writeLocked)
            if (decision) {
                Text(stringResource(R.string.novel_archive_decision_help),
                    style = LocalAmberType.current.meta, color = workspaceColors().muted)
            } else {
                var open by remember { mutableStateOf(false) }
                Text(stringResource(R.string.novel_material_kind), style = LocalAmberType.current.meta)
                Box {
                    NovelGhostButton(stringResource(kind.label), { open = true },
                        enabled = editable, modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        MaterialKind.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(stringResource(item.label)) },
                                onClick = { kind = item; open = false })
                        }
                    }
                }
            }
            OutlinedTextField(value = title, onValueChange = { title = it.replace('\n', ' ').replace('\r', ' ') }, enabled = editable,
                label = { Text(stringResource(R.string.novel_material_title)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            NovelReviewTextEditor(stringResource(R.string.novel_material_body), body, editable,
                onValueChange = { body = it }, tag = "novel-material-body")
        }
        NovelReviewError(errorMessage)
        NovelPrimaryButton(stringResource(if (decision) R.string.novel_archive_decision else R.string.novel_create),
            onClick = { onCreate(if (decision) "decisionLog" else kind.value, title, body, onDismiss) },
            enabled = editable && title.isNotBlank() && body.isNotBlank(),
            accent = true, compact = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun MarkdownMaterialEditSheet(
    path: String,
    displayTitle: String,
    busy: Boolean,
    writeLocked: Boolean,
    errorMessage: String?,
    allowRenameDelete: Boolean,
    plot: Boolean = false,
    readRaw: suspend (String) -> String?,
    onSave: (String, String, String, () -> Unit) -> Unit,
    onDelete: (String, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val loaded by produceState<Result<String?>?>(null, path) {
        value = null
        value = runCatching { readRaw(path) }
    }
    val raw = loaded?.getOrNull()
    val parsed = remember(raw) { raw?.let { NovelWorkspaceMarkdown.parseFile(it) } }
    val originalTitle = parsed?.fields?.get("title") ?: displayTitle
    var title by remember(path, raw) { mutableStateOf(originalTitle) }
    var body by remember(path, raw) { mutableStateOf(parsed?.body.orEmpty()) }
    var confirmDelete by remember(path) { mutableStateOf(false) }
    val editable = !busy && !writeLocked && (raw != null || plot && loaded?.isSuccess == true)
    NovelReviewSheetFrame(displayTitle, onDismiss) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(path, style = LocalAmberType.current.meta, color = workspaceColors().muted)
            NovelReviewLockNotice(writeLocked)
            if (plot) Text(stringResource(R.string.novel_edit_current_plot_help),
                style = LocalAmberType.current.meta, color = workspaceColors().muted)
            when {
                loaded == null -> Text(stringResource(R.string.novel_review_reading))
                loaded?.isFailure == true || raw == null && !plot ->
                    NovelReviewError(stringResource(R.string.novel_review_load_error))
                else -> {
                    if (allowRenameDelete) OutlinedTextField(
                        value = title, onValueChange = { title = it.replace('\n', ' ').replace('\r', ' ') }, enabled = editable,
                        label = { Text(stringResource(R.string.novel_material_title)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    else Text(originalTitle, style = LocalAmberType.current.body, color = workspaceColors().ink)
                    NovelReviewTextEditor(stringResource(R.string.novel_material_body), body, editable,
                        onValueChange = { body = it }, tag = "novel-material-body")
                }
            }
        }
        NovelReviewError(errorMessage)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (allowRenameDelete) NovelGhostButton(stringResource(R.string.delete),
                onClick = { confirmDelete = true }, enabled = editable, danger = true,
                modifier = Modifier.weight(1f))
            NovelPrimaryButton(stringResource(R.string.chat_page_save),
                onClick = { onSave(title, body, raw.orEmpty(), onDismiss) },
                enabled = editable && (!allowRenameDelete || title.isNotBlank()) && (!plot || body.isNotBlank()),
                accent = true, compact = true, modifier = Modifier.weight(1f))
        }
    }
    if (confirmDelete && raw != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.novel_delete_material_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.novel_delete_material_message, originalTitle))
            Text(path, style = LocalAmberType.current.meta)
        } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(
            onClick = { confirmDelete = false; onDelete(raw, onDismiss) }, enabled = editable,
        ) { Text(stringResource(R.string.delete)) } },
    )
}

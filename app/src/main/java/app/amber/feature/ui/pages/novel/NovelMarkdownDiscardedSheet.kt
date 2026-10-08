package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun MarkdownDiscardChapterDialog(
    chapter: NovelMarkdownChapterUi,
    busy: Boolean,
    writeLocked: Boolean,
    errorMessage: String?,
    loadSnapshot: suspend () -> NovelMarkdownDiscardedSnapshotUi?,
    onDiscard: (String?, String, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val loaded by produceState<Result<NovelMarkdownDiscardedSnapshotUi?>?>(null, chapter.path) {
        value = null
        value = runCatching { loadSnapshot() }
    }
    val snapshot = loaded?.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.novel_discard_chapter)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.novel_discard_chapter_message, chapter.ordinal, chapter.title))
            Text(chapter.path, style = LocalAmberType.current.meta)
            if (loaded == null) Text(stringResource(R.string.novel_review_reading))
            else if (snapshot == null) NovelReviewError(stringResource(R.string.novel_review_load_error))
            NovelReviewLockNotice(writeLocked)
            NovelReviewError(errorMessage)
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(
            onClick = { snapshot?.let { onDiscard(it.headId, it.treeDigest, onDismiss) } },
            enabled = !busy && !writeLocked && snapshot != null,
        ) { Text(stringResource(R.string.novel_discard_chapter)) } },
    )
}

@Composable
internal fun MarkdownDiscardedSheet(
    busy: Boolean,
    writeLocked: Boolean,
    errorMessage: String?,
    loadSnapshot: suspend () -> NovelMarkdownDiscardedSnapshotUi?,
    readBody: suspend (String) -> String?,
    onRestore: (String, String?, String, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val loaded by produceState<Result<NovelMarkdownDiscardedSnapshotUi?>?>(null) {
        value = runCatching { loadSnapshot() }
    }
    val snapshot = loaded?.getOrNull()
    var selectedPath by remember { mutableStateOf<String?>(null) }
    val selected = snapshot?.entries?.find { it.path == selectedPath }
    val body by produceState<Result<String?>?>(null, selected?.path) {
        value = null
        value = selected?.let { runCatching { readBody(it.path) } }
    }
    var confirmRestore by remember { mutableStateOf(false) }
    NovelReviewSheetFrame(stringResource(R.string.novel_discarded_chapters), onDismiss) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NovelReviewLockNotice(writeLocked)
            if (selected == null) when {
                loaded == null -> Text(stringResource(R.string.novel_review_reading))
                snapshot == null -> NovelReviewError(stringResource(R.string.novel_review_load_error))
                snapshot.entries.isEmpty() -> Text(stringResource(R.string.novel_discarded_empty))
                else -> snapshot.entries.forEach { entry ->
                    NovelGhostButton(stringResource(R.string.novel_chapter_heading, entry.ordinal, entry.title),
                        onClick = { selectedPath = entry.path }, modifier = Modifier.fillMaxWidth())
                }
            } else {
                TextButton(onClick = { selectedPath = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.novel_discarded_chapters))
                }
                Text(stringResource(R.string.novel_chapter_heading, selected.ordinal, selected.title),
                    style = LocalAmberType.current.body, color = workspaceColors().ink)
                Text(stringResource(R.string.novel_restore_discarded_help),
                    style = LocalAmberType.current.meta, color = workspaceColors().muted)
                NovelReviewError(selected.restoreBlockedReason)
                when {
                    body == null -> Text(stringResource(R.string.novel_review_reading))
                    body?.getOrNull() == null -> NovelReviewError(stringResource(R.string.novel_review_load_error))
                    else -> NovelReviewReadOnlyText(stringResource(R.string.novel_material_body), body?.getOrNull().orEmpty())
                }
            }
        }
        NovelReviewError(errorMessage)
        if (selected != null) NovelPrimaryButton(stringResource(R.string.novel_restore_discarded),
            onClick = { confirmRestore = true },
            enabled = !busy && !writeLocked && body?.getOrNull() != null && selected.restoreBlockedReason == null,
            accent = true, compact = true, modifier = Modifier.fillMaxWidth())
    }
    if (confirmRestore && selected != null && snapshot != null) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text(stringResource(R.string.novel_restore_discarded)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.novel_restore_discarded_message, selected.ordinal, selected.title))
        } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(
            onClick = {
                confirmRestore = false
                onRestore(selected.path, snapshot.headId, snapshot.treeDigest, onDismiss)
            }, enabled = !busy && !writeLocked && selected.restoreBlockedReason == null,
        ) { Text(stringResource(R.string.novel_restore_discarded)) } },
    )
}

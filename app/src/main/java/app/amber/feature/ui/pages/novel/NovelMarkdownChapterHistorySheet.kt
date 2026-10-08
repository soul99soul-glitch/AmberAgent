package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import app.amber.feature.novelworkspace.NovelWorkspaceChapterHistorySnapshot
import app.amber.feature.novelworkspace.NovelWorkspaceChapterVersion
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A ledger version is read on selection; chapter lists keep only its small metadata. */
@Composable
internal fun MarkdownChapterHistorySheet(
    chapter: NovelMarkdownChapterUi,
    busy: Boolean,
    writeLocked: Boolean,
    errorMessage: String?,
    loadHistory: suspend (String) -> NovelWorkspaceChapterHistorySnapshot?,
    readVersion: suspend (String) -> String?,
    onRestore: (String, String?, String, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val loaded by produceState<Result<NovelWorkspaceChapterHistorySnapshot?>?>(null, chapter.path) {
        value = null
        value = runCatching { loadHistory(chapter.path) }
    }
    val snapshot = loaded?.getOrNull()
    var selected by remember(chapter.path) { mutableStateOf<NovelWorkspaceChapterVersion?>(null) }
    val selectedVersion = selected
    val raw by produceState<Result<String?>?>(null, chapter.path, selectedVersion?.contentHash) {
        value = null
        value = selectedVersion?.let { runCatching { readVersion(it.contentHash) } }
    }
    var confirmRestore by remember(chapter.path) { mutableStateOf(false) }
    val parsed = raw?.getOrNull()?.let { NovelWorkspaceMarkdown.parseFile(it) }
    NovelReviewSheetFrame(stringResource(R.string.novel_chapter_history), onDismiss) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(chapter.title, style = LocalAmberType.current.body, color = workspaceColors().ink)
            Text(stringResource(R.string.novel_history_scope_note),
                style = LocalAmberType.current.meta, color = workspaceColors().muted)
            NovelReviewLockNotice(writeLocked)
            if (selectedVersion == null) {
                when {
                    loaded == null -> Text(stringResource(R.string.novel_review_reading))
                    snapshot == null -> NovelReviewError(stringResource(R.string.novel_review_load_error))
                    snapshot.versions.isEmpty() -> Text(stringResource(R.string.novel_history_empty))
                    else -> snapshot.versions.forEach { version ->
                        NovelGhostButton(
                            text = historyVersionLabel(version, stringResource(R.string.novel_history_current)),
                            onClick = { selected = version }, modifier = Modifier.fillMaxWidth())
                    }
                }
            } else {
                TextButton(onClick = { selected = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.novel_chapter_history))
                }
                Text(historyVersionLabel(selectedVersion, stringResource(R.string.novel_history_current)),
                    style = LocalAmberType.current.meta, color = workspaceColors().muted)
                when {
                    raw == null -> Text(stringResource(R.string.novel_review_reading))
                    parsed == null -> NovelReviewError(stringResource(R.string.novel_review_load_error))
                    else -> {
                        parsed.fields["title"]?.let { title ->
                            Text(title, style = LocalAmberType.current.body, color = workspaceColors().ink)
                        }
                        NovelReviewReadOnlyText(stringResource(R.string.novel_history_preview), parsed.body)
                    }
                }
            }
        }
        NovelReviewError(errorMessage)
        if (selectedVersion != null) NovelPrimaryButton(
            text = stringResource(R.string.novel_history_restore),
            onClick = { confirmRestore = true },
            enabled = !busy && !writeLocked && !selectedVersion.isCurrent && parsed != null,
            accent = true, compact = true, modifier = Modifier.fillMaxWidth())
    }
    if (confirmRestore && snapshot != null && selectedVersion != null) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.novel_history_restore)) },
            text = { Text(stringResource(R.string.novel_history_restore_message)) },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) {
                Text(stringResource(R.string.cancel))
            } },
            confirmButton = { TextButton(
                onClick = {
                    confirmRestore = false
                    onRestore(selectedVersion.contentHash, snapshot.headId, snapshot.currentContentHash, onDismiss)
                }, enabled = !busy && !writeLocked) {
                Text(stringResource(R.string.novel_history_restore))
            } },
        )
    }
}

private fun historyVersionLabel(version: NovelWorkspaceChapterVersion, currentLabel: String): String =
    listOfNotNull(
        version.createdAt?.atZone(ZoneId.systemDefault())?.format(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)),
        version.message,
        currentLabel.takeIf { version.isCurrent },
    ).joinToString(" · ").ifBlank { version.contentHash.take(12) }

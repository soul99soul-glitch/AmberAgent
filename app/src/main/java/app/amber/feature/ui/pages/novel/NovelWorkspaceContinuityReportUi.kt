package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType

/** Progress remains on the workspace so stopping a full-book read never requires opening a dialog. */
@Composable
internal fun NovelWorkspaceContinuityStatus(
    checking: Boolean,
    checkedChapters: Int,
    totalChapters: Int,
    reportAvailable: Boolean,
    onStop: () -> Unit,
    onViewReport: () -> Unit,
) {
    if (!checking && !reportAvailable) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = when {
                checking && totalChapters > 0 -> stringResource(R.string.novel_consistency_progress, checkedChapters, totalChapters)
                checking -> stringResource(R.string.novel_consistency_checking)
                else -> stringResource(R.string.novel_consistency_report_title)
            },
            style = LocalAmberType.current.meta, color = workspaceColors().muted,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = if (checking) onStop else onViewReport, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (checking) R.string.stop else R.string.novel_consistency_view_report))
        }
    }
}

@Composable
internal fun NovelWorkspaceContinuityReportDialog(report: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = workspaceColors().paper,
        title = { Text(stringResource(R.string.novel_consistency_report_title),
            fontWeight = FontWeight.SemiBold, color = workspaceColors().ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.testTag("novel-continuity-report-scroll").verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(report, style = LocalAmberType.current.secondary, color = workspaceColors().ink,
                        modifier = Modifier.testTag("novel-continuity-report-text"))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.novel_dismiss), color = workspaceColors().ink)
        } },
    )
}

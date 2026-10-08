package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType

/** Clarifies the portable archive before the system chooses where to write it. */
@Composable
internal fun NovelWorkspaceExportDialog(
    projectName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = workspaceColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = colors.paper,
        title = { Text(stringResource(R.string.novel_export_workspace), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(projectName, style = LocalAmberType.current.body, color = colors.ink)
                Text(stringResource(R.string.novel_workspace_export_scope),
                    style = LocalAmberType.current.secondary, color = colors.muted)
            }
        },
        confirmButton = { NovelPrimaryButton(
            text = stringResource(R.string.export_title), onClick = onExport,
            enabled = !busy, accent = true, compact = true,
        ) },
        dismissButton = { NovelGhostButton(stringResource(R.string.cancel), onDismiss) },
    )
}

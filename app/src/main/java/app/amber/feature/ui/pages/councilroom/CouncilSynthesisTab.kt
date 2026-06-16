package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomStatus
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.WorkspaceTextButton
import app.amber.feature.ui.components.ui.workspaceColors

/**
 * 综合视图 — renders the host's synthesis (if any), a "synthesizing" progress
 * state, or a placeholder with a "request synthesis" affordance.
 *
 * Read-only in terminal mode (vm == null): no request button.
 */
@Composable
fun CouncilSynthesisTab(
    room: CouncilRoom,
    vm: CouncilRoomVM?,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val isFinalizing = room.status == CouncilRoomStatus.FINALIZING
    val hasSynthesis = room.synthesis.isNotBlank()

    Box(modifier = modifier) {
        when {
            hasSynthesis -> {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    MarkdownBlock(
                        content = room.synthesis,
                        style = MaterialTheme.typography.bodyMedium.copy(color = workspace.ink),
                    )
                    if (room.warnings.isNotEmpty()) {
                        Text(
                            text = room.warnings.joinToString(" · "),
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = workspace.amber,
                        )
                    }
                }
            }
            isFinalizing -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Host 正在综合…",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = workspace.muted,
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Host 尚未综合讨论结果。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = workspace.muted,
                        textAlign = TextAlign.Center,
                    )
                    if (vm != null) {
                        WorkspaceTextButton(
                            text = "请求综合",
                            onClick = { vm.requestSynthesize() },
                            modifier = Modifier.padding(top = 16.dp),
                            tone = app.amber.feature.ui.components.ui.WorkspaceTone.Accent,
                        )
                    }
                }
            }
        }
    }
}

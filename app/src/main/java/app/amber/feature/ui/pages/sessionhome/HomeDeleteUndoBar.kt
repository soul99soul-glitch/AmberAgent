package app.amber.feature.ui.pages.sessionhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.ui.components.ds.AmberContinuousShape
import app.amber.feature.ui.theme.LocalAmberTokens

/** Presentation only. The VM supplies the countdown and owns the deadline. */
@Composable
internal fun HomeDeleteUndoBar(
    count: Int,
    seconds: Int,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = LocalAmberTokens.current
    Surface(
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("home-delete-undo"),
        color = tokens.surface2,
        contentColor = tokens.ink,
        shape = AmberContinuousShape(20.dp),
        shadowElevation = 6.dp,
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.4f
            val title: @Composable () -> Unit = {
                Text(
                    text = pluralStringResource(R.plurals.session_home_deleted_count, count, count),
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val countdown: @Composable () -> Unit = {
                val description = stringResource(R.string.session_home_undo_seconds, seconds)
                Box(Modifier.size(40.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { seconds / 5f },
                        modifier = Modifier.size(34.dp),
                        color = tokens.accent,
                        strokeWidth = 2.dp,
                    )
                    Text(seconds.toString())
                }
            }
            val action: @Composable () -> Unit = {
                TextButton(
                    onClick = onUndo,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("home-delete-undo-action"),
                    colors = ButtonDefaults.textButtonColors(contentColor = tokens.accent),
                ) { Text(stringResource(R.string.session_home_undo)) }
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    title()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        countdown()
                        Spacer(Modifier.weight(1f))
                        action()
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    countdown()
                    Box(Modifier.weight(1f)) { title() }
                    action()
                }
            }
        }
    }
}

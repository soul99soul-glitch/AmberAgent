package app.amber.feature.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.amber.core.ai.generative.GenerativeWidgetSegment

/**
 * **T-C perf-layer scaffold** for `PerfFlags.USE_SPLIT_GENERATIVE_WIDGET_CARD`.
 *
 * GenerativeWidgetCard (1952 LOC) renders user-provided HTML/JS-like
 * widget code through a sandboxed renderer + per-widget-type branching
 * (charts, structured renderers, interactive blocks). The next sprint
 * should split by widget type so each branch is its own Composable.
 *
 * Defaults: flag=false → legacy path. Scaffold visible only behind
 * explicit flag flip.
 */
@Composable
fun GenerativeWidgetCardSplit(
    widget: GenerativeWidgetSegment.Widget,
    modifier: Modifier = Modifier,
    onAction: (String) -> Unit = {},
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "T4 GenerativeWidgetCard scaffold active",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "widgetCodeChars=${widget.widgetCode.length}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "TODO (post-device-QA): split by widget type (chart / " +
                    "structured / interactive / slides), each rendering " +
                    "in its own Composable.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

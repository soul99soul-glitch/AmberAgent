package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * **T-C perf-layer scaffold** for `PerfFlags.USE_SPLIT_MARKDOWN`.
 *
 * The full Markdown renderer (2061 LOC) is documented as a multi-day
 * sprint in `docs/td-rust-1a-feasibility.md`. This scaffold provides
 * the dispatcher target so a future device-verified path can plug in
 * here without touching the entry-point signature.
 *
 * Region candidates (for the future sprint): block-level renderers
 * (paragraph / heading / list / blockquote / code-block / table) each
 * as a leaf Composable consuming only the ASTNode subtree it needs.
 *
 * Defaults: flag=false → legacy MarkdownBlock path. Scaffold below
 * is only reachable behind the explicit flag flip.
 */
@Composable
fun MarkdownBlockSplit(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fillWidth: Boolean = true,
    streaming: Boolean = false,
    onStreamingVisibleFrame: (() -> Unit)? = null,
    onClickCitation: (String) -> Unit = {},
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "T4 Markdown scaffold active (streaming=$streaming, chars=${content.length})",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                content,
                style = style,
            )
            Text(
                "TODO: split into block-renderer Composables per AST node type.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

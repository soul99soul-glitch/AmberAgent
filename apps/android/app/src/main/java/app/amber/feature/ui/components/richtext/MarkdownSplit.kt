package app.amber.feature.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalTextStyle

/**
 * **T-C perf-layer scaffold** for `PerfFlags.USE_SPLIT_MARKDOWN`.
 *
 * Until block-level renderers land, delegate to [MarkdownBlockLegacy]
 * so streaming tail markers, batch reveal, and parse throttle stay intact.
 */
@Composable
fun MarkdownBlockSplit(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fillWidth: Boolean = true,
    streaming: Boolean = false,
    deferStreamingParse: Boolean = false,
    onStreamingVisibleFrame: (() -> Unit)? = null,
    onStreamingVisualActiveChange: ((Boolean) -> Unit)? = null,
    onClickCitation: (String) -> Unit = {},
) {
    MarkdownBlockLegacy(
        content = content,
        modifier = modifier,
        style = style,
        fillWidth = fillWidth,
        streaming = streaming,
        deferStreamingParse = deferStreamingParse,
        onStreamingVisibleFrame = onStreamingVisibleFrame,
        onStreamingVisualActiveChange = onStreamingVisualActiveChange,
        onClickCitation = onClickCitation,
    )
}

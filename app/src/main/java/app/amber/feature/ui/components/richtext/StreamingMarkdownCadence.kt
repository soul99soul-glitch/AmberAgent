package app.amber.feature.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate

// Follow the owning MarkdownBlock's mode, including callers that defer live parsing.
internal val LocalStreamingPublishCadence = compositionLocalOf { false }

internal fun streamingPublishCadenceEnabled(): Boolean =
    app.amber.agent.PerfFlags.STREAMING_PUBLISH_CADENCE_MARKDOWN

internal suspend fun <T> Flow<T>.collectMarkdownParses(
    sequential: Boolean,
    block: suspend (T) -> Unit,
) {
    if (sequential) conflate().collect { block(it) } else collectLatest { block(it) }
}

/** Fades appended ranges in drawing, keeping Markdown styles and text layout unchanged. */
internal class PublishBatchFade {
    internal data class Batch(val start: Int, val end: Int, val startNanos: Long)
    private var lastLength = -1
    internal var batches by mutableStateOf<List<Batch>>(emptyList())
        private set
    var nowNanos by mutableLongStateOf(0L)
    private var layoutResult by mutableStateOf<TextLayoutResult?>(null)
    val onTextLayout: (TextLayoutResult) -> Unit = { layoutResult = it }
    val modifier = Modifier
        .graphicsLayer {
            // DstOut must erase this text's layer, not the chat background.
            compositingStrategy = if (hasActive(nowNanos)) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }
        .drawWithCache {
            val layout = layoutResult
            val paths = batches.mapNotNull { batch ->
                val end = batch.end.coerceAtMost(layout?.layoutInput?.text?.length ?: 0)
                if (layout != null && batch.start < end) batch to layout.getPathForRange(batch.start, end) else null
            }
            onDrawWithContent {
                drawContent()
                paths.forEach { (batch, path) ->
                    val alpha = remainingOpacity(batch, nowNanos)
                    if (alpha > 0f) drawPath(path, Color.Black, alpha = alpha, blendMode = BlendMode.DstOut)
                }
            }
        }

    fun observe(length: Int, tailActive: Boolean, nowNanos: Long) {
        val retained = batches.filter { nowNanos - it.startNanos < PUBLISH_BATCH_FADE_NANOS }
        batches = when {
            length < lastLength -> emptyList()
            length > lastLength && tailActive && length > 0 -> retained + Batch(lastLength.coerceAtLeast(0), length, nowNanos)
            else -> retained
        }
        lastLength = length
    }

    fun hasActive(nowNanos: Long): Boolean =
        batches.any { nowNanos - it.startNanos < PUBLISH_BATCH_FADE_NANOS }

    fun remainingOpacity(batch: Batch, nowNanos: Long): Float {
        val progress = ((nowNanos - batch.startNanos).toFloat() / PUBLISH_BATCH_FADE_NANOS).coerceIn(0f, 1f)
        return 1f - codexStreamingAlphaProgress(progress)
    }
}

// The vote belongs to the text that redraws, including a final batch after completion.
@Composable
internal fun PublishBatchFade.drawingModifier(): Modifier {
    val fading by remember(this) { derivedStateOf { hasActive(nowNanos) } }
    return (if (fading) Modifier.preferredFrameRate(120f) else Modifier).then(modifier)
}

@Composable
internal fun rememberPublishBatchFade(length: Int, tailActive: Boolean): PublishBatchFade {
    val fade = remember { PublishBatchFade() }
    SideEffect { fade.observe(length, tailActive, fade.nowNanos) }
    val fading by remember { derivedStateOf { fade.hasActive(fade.nowNanos) } }
    LaunchedEffect(fading) {
        if (!fading) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (fade.hasActive(fade.nowNanos)) {
            withFrameNanos { frame ->
                fade.nowNanos += frame - previousFrame
                previousFrame = frame
            }
        }
    }
    return fade
}

private const val PUBLISH_BATCH_FADE_NANOS = 350_000_000L

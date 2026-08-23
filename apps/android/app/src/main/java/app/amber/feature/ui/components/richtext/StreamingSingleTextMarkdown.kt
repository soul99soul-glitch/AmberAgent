package app.amber.feature.ui.components.richtext

import android.os.SystemClock
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import app.amber.core.model.Assistant
import app.amber.core.model.AssistantAffectScope
import app.amber.core.utils.openUrl
import app.amber.feature.ui.components.message.MessageRenderCache
import app.amber.feature.ui.context.LocalSettings
import app.amber.highlight.HighlightToken
import app.amber.highlight.LocalHighlighter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext

/** Parse throttle window — same cadence as MARKDOWN_STREAMING_PARSE_THROTTLE_MS. */
internal const val STREAMING_SINGLE_TEXT_PARSE_THROTTLE_MS = 120L

/** Token-cache ceiling per streaming message — beyond it new blocks stay flat. */
internal const val STREAMING_CODE_HIGHLIGHT_MAX_BLOCKS = 32

/**
 * Streaming-phase single-Text markdown renderer (the iOS playbook's
 * "one text layout, incremental append" on Compose): the whole message body
 * renders as ONE growing [Text] — no block component tree, no re-layout of
 * already-rendered glyphs while streaming. Markdown styles apply live
 * ([mdNodeToAnnotatedString]) and the writing head fades in via the
 * distance-from-end reveal window ([applyStreamingWindowReveal]): newest chars
 * near-transparent, chars one window back fully opaque — no clock, no index
 * keys, so parse-tick mutations cannot open holes.
 *
 * Pipeline (same sources as the settled path):
 * 1. [MessageRenderCache.visualRegexText] — the assistant visual regex
 *    transform, applied exactly once (the settled path applies it before
 *    MarkdownBlock; this component receives the RAW part text and applies it
 *    itself).
 * 2. [rememberStreamingDisplayText] — the word-quantized pacing buffer, same
 *    source the settled MarkdownBlock uses (the pacer lives upstream of the
 *    render layer and is untouched).
 * 3. Throttled full parse via MarkdownParseCache (trailing-edge ~120ms,
 *    mirroring MARKDOWN_STREAMING_PARSE_THROTTLE_MS) so token bursts do not
 *    re-parse every frame.
 * 4. [mdNodeToAnnotatedString] — MdNode tree → AnnotatedString.
 *
 * The caller composes this ONLY while the message is actively streaming; the
 * settled path switches back to MarkdownBlock (single text → block tree is
 * the accepted streaming→settled degradation).
 */
@Composable
fun StreamingSingleTextMarkdown(
    text: String,
    assistant: Assistant?,
    modifier: Modifier = Modifier,
    streaming: Boolean = true,
    onSettled: (() -> Unit)? = null,
) {
    val renderSource = remember(text, assistant) {
        MessageRenderCache.visualRegexText(
            text = text,
            assistant = assistant,
            scope = AssistantAffectScope.ASSISTANT,
        )
    }
    // Pacing buffer — same release cadence as the settled MarkdownBlock path.
    // `streaming` follows the message loading flag: when generation ends the
    // buffer enters drain mode and keeps writing the buffered tail at the
    // streaming pace; [onSettled] fires once the tail is fully out so the
    // caller can swap to the settled renderer WITHOUT dumping the backlog.
    val visible = rememberStreamingDisplayText(
        content = renderSource,
        streaming = streaming,
    )
    var data by remember { mutableStateOf(parseMarkdownContent(visible)) }
    // The exact display-buffer string [data] was parsed from — the per-frame
    // tail appends after it (see displayAnnotated below).
    var parsedInput by remember { mutableStateOf(visible) }
    val updatedVisible by rememberUpdatedState(visible)
    var lastParseMs by remember { mutableLongStateOf(0L) }
    // Syntax-highlight tokens for CLOSED fenced code blocks. Keyed by
    // lang+body (closed bodies never change), so each block pays exactly one
    // QuickJS pass; a cancelled collectLatest tick resumes on the next tick
    // (cache misses are simply retried). Failures cache as empty so they do
    // not retry forever.
    val highlighter = LocalHighlighter.current
    val codeHighlights = remember { mutableStateMapOf<String, List<HighlightToken>>() }
    LaunchedEffect(Unit) {
        snapshotFlow { updatedVisible }
            .distinctUntilChanged()
            .transform { latest ->
                // Trailing-edge throttle: parse immediately when cold, else
                // wait out the window and always parse the LATEST content —
                // a mid-stream pause must not freeze the markdown structure.
                val nowMs = SystemClock.uptimeMillis()
                if (lastParseMs == 0L || nowMs - lastParseMs >= STREAMING_SINGLE_TEXT_PARSE_THROTTLE_MS) {
                    lastParseMs = nowMs
                    emit(latest)
                } else {
                    delay(STREAMING_SINGLE_TEXT_PARSE_THROTTLE_MS - (nowMs - lastParseMs))
                    lastParseMs = SystemClock.uptimeMillis()
                    emit(latest)
                }
            }
            .collectLatest { latest ->
                try {
                    val parsed = withContext(Dispatchers.Default) {
                        parseMarkdownContent(latest)
                    }
                    if (parsed !== data) {
                        data = parsed
                        parsedInput = latest
                    }
                    if (codeHighlights.size < STREAMING_CODE_HIGHLIGHT_MAX_BLOCKS) {
                        for (block in collectStreamingClosedCodeBlocks(parsed.tree, parsed.preprocessed)) {
                            if (codeHighlights.containsKey(block.key)) continue
                            if (codeHighlights.size >= STREAMING_CODE_HIGHLIGHT_MAX_BLOCKS) break
                            val tokens = runCatching {
                                highlighter.highlight(block.body, block.lang)
                            }.getOrDefault(emptyList())
                            codeHighlights[block.key] = tokens
                        }
                    }
                } catch (exception: Exception) {
                    exception.printStackTrace()
                }
            }
    }

    val singleTextStyle = rememberMarkdownSingleTextStyle()
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    val context = LocalContext.current
    val onClickUrl = remember(context) {
        { url: String -> context.openUrl(url) }
    }
    // Reading the snapshot map inside remember subscribes this cache to map
    // writes — a late-arriving token batch re-maps with the colored spans.
    val annotated = remember(data, singleTextStyle, onClickUrl, enableLatexRendering) {
        mdNodeToAnnotatedString(
            source = data.preprocessed,
            root = data.tree,
            style = singleTextStyle,
            onClickUrl = onClickUrl,
            codeHighlights = codeHighlights.toMap(),
            enableLatexRendering = enableLatexRendering,
        )
    }

    // Per-frame growth, styled at parse cadence: the display string follows
    // the pacer's PER-FRAME output — the styled annotated prefix (120ms
    // trailing-edge parse) plus the not-yet-parsed source tail appended raw.
    // Without this, text visibly arrives in ~120ms clumps: the pacer releases
    // 1-5 chars per frame, but the display only updated on parse ticks
    // (2026-08-16 forensics — "逐词淡入/滚动不平滑" = this quantization, not
    // the pacer). The raw tail sits inside the reveal window (semi-
    // transparent), so its ≤120ms of missing styles / raw markers are
    // practically invisible; the next tick re-derives everything cleanly. A
    // prefix mismatch (content replaced, visual-regex rewrite) falls back to
    // the styled snapshot until the next tick.
    val displayAnnotated = remember(annotated, visible, parsedInput) {
        if (visible.length > parsedInput.length && visible.startsWith(parsedInput)) {
            buildAnnotatedString {
                append(annotated)
                append(visible.substring(parsedInput.length))
            }
        } else {
            annotated
        }
    }

    // Ink-dry reveal, window form (审美第5条"墨随字速干"): a char's opacity is
    // a function of its DISTANCE FROM THE END of the final string — newest chars
    // near-transparent, chars `windowChars` back fully opaque. No clock
    // and no index keys: the window re-anchors at the end on every append, so
    // structural mutations (marker closure, gap-line insertion) that broke the
    // index-keyed clock (2026-08-16 blank-band forensics) cannot create holes.
    // Each char's alpha rises as newer text streams past it — the fade speed
    // tracks the generation rate with zero per-char state; after ~0.35s of
    // append silence the window dries out linearly to zero so the tail always
    // settles fully opaque ("收笔必是完整淡入"). A bounded frame loop drives
    // only that dry-out; the rebuild keys on the quantized window width, not
    // the frame clock, so steady streaming does not rebuild the string per
    // frame.
    val baseColorForReveal = LocalContentColor.current
    val releaseRate = remember { ReleaseRateTracker() }
    var lastAppendMs by remember { mutableLongStateOf(0L) }
    var settleNowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(visible.length) {
        val nowMs = SystemClock.uptimeMillis()
        releaseRate.record(nowMs, visible.length)
        lastAppendMs = nowMs
        while (SystemClock.uptimeMillis() - lastAppendMs < STREAMING_WINDOW_SETTLE_DRIVE_MS) {
            withFrameNanos { }
            settleNowMs = SystemClock.uptimeMillis()
        }
    }
    val idleMs = (settleNowMs - lastAppendMs).coerceAtLeast(0L)
    val windowChars = revealWindowChars(
        ratePerSecond = releaseRate.ratePerSecond(SystemClock.uptimeMillis()),
        idleMs = idleMs,
    )
    val revealActive = streaming || visible != renderSource || windowChars > 0
    val revealed = if (revealActive) {
        remember(displayAnnotated, windowChars, baseColorForReveal) {
            applyStreamingWindowReveal(
                annotated = displayAnnotated,
                windowChars = windowChars,
                baseColor = baseColorForReveal,
            )
        }
    } else {
        displayAnnotated
    }

    if (onSettled != null) {
        val updatedOnSettled by rememberUpdatedState(onSettled)
        // Settled = buffer drained AND the dry-out finished AND the final
        // parse tick landed — the swap to the settled renderer happens only
        // when no on-screen char is still mid-fade or raw, so the handover
        // changes nothing the user can see.
        val settled = !streaming && visible == renderSource && windowChars == 0 &&
            visible == parsedInput
        LaunchedEffect(settled) {
            if (settled) updatedOnSettled()
        }
    }

    SelectionContainer {
        Text(
            text = revealed,
            modifier = modifier.padding(start = 4.dp),
            style = LocalTextStyle.current,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
    }
}

/** 最近 ~1.5s 的释放速率（字符/秒），驱动淡入窗口宽度。 */
private class ReleaseRateTracker {
    private val samples = ArrayDeque<Pair<Long, Int>>()

    fun record(nowMs: Long, length: Int) {
        samples.addLast(nowMs to length)
        while (samples.isNotEmpty() && nowMs - samples.first().first > 1_500) {
            samples.removeFirst()
        }
    }

    fun ratePerSecond(nowMs: Long): Float {
        val newest = samples.lastOrNull() ?: return 0f
        val anchor = samples.firstOrNull { nowMs - it.first <= 1_500 } ?: return 0f
        val dt = (newest.first - anchor.first).coerceAtLeast(1L)
        return (newest.second - anchor.second).toFloat() / dt * 1000f
    }
}

/**
 * 淡入窗口：速率×0.15s，钳 4..48，按 4 字取整（速率逐帧抖动不触发整串重建）。
 * 上界 48 是故意的：窗口只需罩住"未解析原样尾巴"（≤ 速率×120ms 节拍，
 * cap 360 字/s 时 ≈43 字），再大就把淡入从"逐词"拉成"整段幽灵渐变"。
 * 静默 [STREAMING_WINDOW_DRY_DELAY_MS] 后线性收干，[STREAMING_WINDOW_DRY_MS]
 * 内必然归零——收干有完成时刻，交换渲染器前最后一个词必定完整淡入。
 * 纯函数，可单测。
 */
internal fun revealWindowChars(ratePerSecond: Float, idleMs: Long): Int {
    val base = (ratePerSecond * STREAMING_WINDOW_REVEAL_SECONDS).coerceIn(4f, 48f)
    val dry = if (idleMs <= STREAMING_WINDOW_DRY_DELAY_MS) {
        1f
    } else {
        (1f - (idleMs - STREAMING_WINDOW_DRY_DELAY_MS) / STREAMING_WINDOW_DRY_MS)
            .coerceAtLeast(0f)
    }
    val raw = base * dry
    if (raw < 1f) return 0
    return (raw / STREAMING_WINDOW_QUANTUM).toInt() * STREAMING_WINDOW_QUANTUM
}

internal fun applyStreamingWindowReveal(
    annotated: AnnotatedString,
    windowChars: Int,
    baseColor: Color,
): AnnotatedString {
    if (windowChars <= 0 || annotated.isEmpty()) return annotated
    val text = annotated.text
    val length = text.length
    var start = length
    var seen = 0
    while (start > 0 && seen < windowChars) {
        start = text.offsetByCodePoints(start, -1)
        seen++
    }
    var styledAny = false
    val built = buildAnnotatedString {
        append(annotated)
        var offset = start
        while (offset < length) {
            val codePoint = text.codePointAt(offset)
            val nextOffset = offset + Character.charCount(codePoint)
            // 距末尾越近越透明（新墨未干），越远越不透明；窗口边缘 progress=1
            // 与窗外无样式区连续。每个字的 alpha 随新文本流过而上升——淡入方向
            // 绝不能反（反过就是 2026-08-16 的"光扫过+隐形带"：新字不透即弹入，
            // 尾部渐变到隐形再跳回）。
            val distanceFromEnd = length - offset
            val progress = if (windowChars <= 1) 1f else {
                (distanceFromEnd.toFloat() / windowChars).coerceIn(0f, 1f)
            }
            if (progress < 1f) {
                styledAny = true
                addStyle(
                    style = streamingRevealSpanStyle(
                        baseColor = baseColor,
                        localProgress = progress,
                    ),
                    start = offset,
                    end = nextOffset,
                )
            }
            offset = nextOffset
        }
    }
    return if (styledAny) built else annotated
}

// 窗口时长贴着"未解析原样尾巴"的尺寸走：尾巴 ≤ 速率 × 解析节拍（120ms），
// 0.15s 给出 1.25x 余量——窗口永远罩住尾巴（原始 ** 记号不露白），又不放大成
// 整段幽灵渐变（0.3s × 360caps 曾钉满 120 字 ≈ 4 行，2026-08-16 录屏取证）。
private const val STREAMING_WINDOW_REVEAL_SECONDS = 0.15f

/** 静默宽限：上游停顿这么久而窗口保持全开（打字途中不提前收干）。 */
private const val STREAMING_WINDOW_DRY_DELAY_MS = 350f

/** 宽限过后窗口线性归零的时长——收干完成时刻有上界（≈ 350+550ms）。 */
private const val STREAMING_WINDOW_DRY_MS = 550f

/** 窗口宽度量化步长：低于一步的变化不值得整串重建。 */
private const val STREAMING_WINDOW_QUANTUM = 4

/** 收干驱动帧循环的上界（必须 > 宽限+收干时长）。 */
private const val STREAMING_WINDOW_SETTLE_DRIVE_MS = 1_400L

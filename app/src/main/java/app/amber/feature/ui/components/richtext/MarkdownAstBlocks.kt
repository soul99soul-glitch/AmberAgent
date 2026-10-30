package app.amber.feature.ui.components.richtext

import android.os.Trace
import app.amber.agent.BuildConfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Check
import app.amber.feature.ui.components.message.LocalSearchImageUrls
import app.amber.feature.ui.context.LocalChatFontScale
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.utils.amberTraceMeasure
import app.amber.feature.ui.utils.ChatPerfProbeToken
import app.amber.feature.ui.utils.LocalMarkdownReadinessProbe
import app.amber.feature.ui.utils.LocalMarkdownReadinessDispose
import app.amber.core.utils.openUrl
import app.amber.core.utils.toDp
import app.amber.feature.ui.components.richtext.tree.JvmMdNode
import app.amber.feature.ui.components.richtext.tree.MdNode
import app.amber.feature.ui.components.richtext.tree.MdNodeType
import app.amber.feature.ui.components.richtext.tree.textIn

@Composable
internal fun TraceMarkdownComposable(section: String, content: @Composable () -> Unit) {
    if (BuildConfig.DEBUG) {
        Trace.beginSection(section)
    }
    content()
    if (BuildConfig.DEBUG) {
        Trace.endSection()
    }
}

/**
 * When false, markdown nodes use wrap-content width instead of fill-max-width.
 * Used by user message bubbles to allow adaptive width.
 */
internal val LocalMarkdownFillWidth = compositionLocalOf { true }

/**
 * Alt text for a markdown image node.
 *
 * JVM `linkLabel` is the raw LINK_TEXT including `[ ]` (golden
 * `06-images.txt` pins `image=[Android robot logo]`). Native `linkLabel`
 * is the concatenated alt without brackets. Device tests match both forms
 * rather than rewriting this string, so the snapshot golden stays stable.
 */
internal fun markdownImageContentDescription(node: MdNode): String =
    node.linkLabel.orEmpty()
internal val LocalMarkdownSourceOffsetBase = compositionLocalOf { 0 }
internal val LocalMarkdownSyntheticSuffixStart = compositionLocalOf { Int.MAX_VALUE }
internal val LocalStreamingMarkdownMotionScope = compositionLocalOf<StreamingMarkdownMotionScope?> { null }

internal fun Modifier.fillWidthIf(fill: Boolean): Modifier = if (fill) fillMaxWidth() else this

/**
 * Test-only seam (TD.Rust.1a Phase 3-B parity rig). Renders a PRE-BUILT [result] through the
 * same render dispatch [MarkdownBlock] uses on the non-streaming path, so the parity test can
 * render two different trees (JVM vs native) side by side inside ONE `setContent` (a Compose
 * test rule allows only one). This is render-equivalent to `MarkdownBlock(content)` for a settled
 * (non-streaming, no stable-block, no live-suffix) result: that input drives `MarkdownBlock` into
 * exactly the `else` arm below, where every streaming CompositionLocal already equals its default
 * (`activeBaseOffset == 0` == LocalMarkdownSourceOffsetBase default, `syntheticSuffixStart ==
 * Int.MAX_VALUE` == LocalMarkdownSyntheticSuffixStart default, null tail/motion scope, empty
 * liveSuffix). The HTML-block arm dispatches to the identical `MarkdownNew(content)` either way.
 * See MarkdownTreeParityTest. Not for production use.
 */
@androidx.annotation.VisibleForTesting
@Composable
internal fun MarkdownTreeForParityTest(
    content: String,
    result: MarkdownParseResult,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    CompositionLocalProvider(LocalMarkdownFillWidth provides true) {
        if (result.hasHtmlBlocks) {
            MarkdownNew(content = content, modifier = modifier, style = style)
        } else {
            ProvideTextStyle(style) {
                Column(modifier = modifier.padding(start = 4.dp)) {
                    val nodeModifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current)
                    result.tree.children.fastForEach { child ->
                        MarkdownNode(
                            node = child,
                            content = result.preprocessed,
                            modifier = nodeModifier,
                        )
                    }
                }
            }
        }
    }
}

internal fun MarkdownParseResult.canRenderByTopLevelBlocks(): Boolean {
    return !hasHtmlBlocks && tree.children.size > 1
}

internal fun MarkdownParseResult.topLevelBlockCount(): Int {
    return tree.children.size
}

internal fun MarkdownParseResult.topLevelBlockKey(index: Int): String {
    val child = tree.children.getOrNull(index) ?: return "missing-$index"
    // 不把 AST offset 编进 key: 前缀文本变化 (翻译/regex 视觉变换/重新解析) 会平移
    // 所有后续 block 的 offset, 导致整条消息的虚拟化 LazyColumn item 全部换 key
    // 销毁重建 (闪烁 + 滚动锚点丢失)。type + 块序号在内容平移时保持稳定;
    // 块类型或结构变化时 key 仍会刷新。
    return "${child.type}:$index"
}

@Composable
internal fun MarkdownTopLevelBlock(
    data: MarkdownParseResult,
    blockIndex: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {}
) {
    val child = data.tree.children.getOrNull(blockIndex) ?: return
    // Virtualized history already has an AST and bypasses MarkdownBlockLegacy.
    // Register only a composed, real block; lazy-item disposal removes it again.
    val probe = LocalMarkdownReadinessProbe.current
    val measuredModifier = if (probe != null) {
        val dispose = LocalMarkdownReadinessDispose.current
        val token = remember(probe, data, blockIndex) { ChatPerfProbeToken.next() }
        var visible by remember(token) { mutableStateOf(false) }
        val registered = remember(token) { booleanArrayOf(false) }
        DisposableEffect(token, dispose) {
            onDispose { if (registered[0]) dispose?.invoke(token) }
        }
        // Read in composition so a later placement/viewport change schedules
        // the reporting effect again (an effect-only read is not subscribed).
        val visibleAtComposition = visible
        SideEffect {
            if (visibleAtComposition) {
                registered[0] = true
                probe(token, data.isPendingPlaceholder)
            } else if (registered[0]) {
                registered[0] = false
                dispose?.invoke(token)
            }
        }
        modifier.onGloballyPositioned { coordinates ->
            // Default boundsInWindow clips against ancestors, including the lazy
            // viewport. A prefetched/offscreen slice cannot certify readability.
            val bounds = coordinates.boundsInWindow()
            visible = coordinates.isAttached && bounds.width > 0f && bounds.height > 0f
        }
    } else modifier
    ProvideTextStyle(style) {
        Column(
            modifier = measuredModifier
                .padding(start = 4.dp)
                .amberTraceMeasure("Amber MarkdownBlock child measure")
        ) {
            MarkdownNode(
                node = child,
                content = data.preprocessed,
                onClickCitation = onClickCitation,
            )
        }
    }
}

// for debug
private fun dumpAst(node: MdNode, text: String, indent: String = "") {
    println("$indent${node.type} ${if (node.children.isEmpty()) node.textIn(text) else ""}")
    node.children.fastForEach {
        dumpAst(it, text, "$indent  ")
    }
}

object HeaderStyle {
    val H1 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp
    )

    val H2 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp
    )

    val H3 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp
    )

    val H4 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp
    )

    val H5 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 24.sp
    )

    val H6 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 22.sp
    )
}

@Composable
internal fun MarkdownNode(
    node: MdNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    listLevel: Int = 0,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    when (node.type) {
        // 文件根节点
        MdNodeType.Root -> {
            node.children.fastForEach { child ->
                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                    children = node.children,
                    child = child,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                MarkdownNode(
                    node = child,
                    content = content,
                    modifier = modifier,
                    onClickCitation = onClickCitation,
                    liveSuffix = childLiveSuffix.text,
                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                )
            }
        }

        // 段落
        MdNodeType.Paragraph -> {
            Paragraph(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
        }

        // 标题
        MdNodeType.Heading -> {
            // ATX_1..ATX_6 collapse to one Heading variant; level via headingLevel
            // accessor (mapping doc §3 E1, replaces the per-type when over ATX_n).
            val style = when (node.headingLevel) {
                1 -> HeaderStyle.H1
                2 -> HeaderStyle.H2
                3 -> HeaderStyle.H3
                4 -> HeaderStyle.H4
                5 -> HeaderStyle.H5
                6 -> HeaderStyle.H6
                else -> throw IllegalArgumentException("Unknown header type")
            }
            // 标题是绝对字号，不随 LocalTextStyle 缩放；乘聊天字号比例保持标题/正文
            // 比例。非聊天面（LocalChatFontScale 默认 1f）维持原值。
            val fontScale = LocalChatFontScale.current
            val scaledStyle = style.copy(
                fontSize = style.fontSize * fontScale,
                lineHeight = style.lineHeight * fontScale,
            )
            // 垂直 padding 同乘 scale，保持标题行盒与呼吸感的比例不随滑杆漂移。
            val headingPadding = when (node.headingLevel) {
                1 -> 16.dp
                2 -> 14.dp
                3 -> 12.dp
                4 -> 10.dp
                5 -> 8.dp
                6 -> 6.dp
                else -> 8.dp
            } * fontScale
            // Capture the body style BEFORE switching to the heading style, so
            // the live-suffix preview of the paragraph that follows the heading
            // renders as body text rather than leaking the heading's large bold.
            val bodyStyle = LocalTextStyle.current
            ProvideTextStyle(value = scaledStyle) {
                StreamingBlockReveal(node = node, modifier = modifier.fillWidthIf(LocalMarkdownFillWidth.current)) { revealModifier ->
                    FlowRow(
                        modifier = revealModifier,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Shape-agnostic heading content dispatch. Two tree shapes carry the
                        // heading's inline content differently:
                        //  - JVM (JetBrains): heading children = [`#` marker Unknown tokens…,
                        //    ATX_CONTENT → MdNodeType.Paragraph wrapper] (mapping doc §4 H1). The
                        //    inline content lives inside that Paragraph child.
                        //  - Native (pulldown): NO ATX_CONTENT wrapper and NO `#` markers — the
                        //    heading's children ARE the flat inline nodes
                        //    (NativeMdTreeTest.headingContentChildrenAreFlatInline).
                        // So: if a Paragraph child exists, render it (JVM); otherwise render the
                        // heading node itself through Paragraph, which walks the flat inline
                        // children via appendMarkdownNodeContent (native). The old code filtered
                        // for `child.type == Paragraph` and rendered nothing on the native shape.
                        val paragraphChild = node.children.fastFirstOrNull { it.type == MdNodeType.Paragraph }
                        // Edge shape: a marker-only ATX heading (`##` with no trailing inline text)
                        // has, on the JVM tree, ONLY an ATX_HEADER `#` marker child — an
                        // MdNodeType.Unknown leaf — and NO Paragraph wrapper. The old `?: node`
                        // fallback then routed that ATX node through Paragraph, whose leaf arm
                        // appended the literal `##` markers (a flag-OFF render regression). Native
                        // headings, by contrast, carry REAL inline children (no markers). So fall
                        // back to `node` only when it has at least one NON-Unknown child (real inline
                        // content to render); otherwise render nothing — matching the old code's
                        // "nothing for a wrapper-less heading" behavior for the marker-only shape and
                        // an empty native heading alike.
                        val contentNode = paragraphChild
                            ?: node.takeIf { it.children.fastFirstOrNull { c -> c.type != MdNodeType.Unknown } != null }
                        if (contentNode != null) {
                            val childLiveSuffix = if (paragraphChild != null) {
                                streamingLiveSuffixForLastRenderableChild(
                                    children = node.children,
                                    child = paragraphChild,
                                    liveSuffix = liveSuffix,
                                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                                )
                            } else {
                                // No wrapper child to attribute the suffix to — pass it straight to the
                                // heading-as-paragraph render (native shape).
                                StreamingLiveSuffix(liveSuffix, liveSuffixSourceOffset)
                            }
                            Paragraph(
                                node = contentNode,
                                content = content,
                                onClickCitation = onClickCitation,
                                modifier = Modifier.padding(vertical = headingPadding),
                                trim = true,
                                plainSuffixStyle = bodyStyle,
                                liveSuffix = childLiveSuffix.text,
                                liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                            )
                        }
                    }
                }
            }
        }

        // 列表
        MdNodeType.ListUnordered -> {
            UnorderedListNode(
                node = node,
                content = content,
                modifier = modifier.padding(vertical = 4.dp),
                onClickCitation = onClickCitation,
                level = listLevel,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
        }

        MdNodeType.ListOrdered -> {
            OrderedListNode(
                node = node,
                content = content,
                modifier = modifier.padding(vertical = 4.dp),
                onClickCitation = onClickCitation,
                level = listLevel,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
        }

        // Checkbox
        MdNodeType.TaskListMarker -> {
            // taskChecked accessor relocates the `[x]` text-slice (mapping doc §3 E6 / §4 H2).
            val isChecked = node.taskChecked == true
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = modifier,
            ) {
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .size(LocalTextStyle.current.fontSize.toDp() * 0.8f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isChecked) {
                        Icon(
                            imageVector = Lucide.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 引用块
        MdNodeType.Blockquote -> {
            ProvideTextStyle(LocalTextStyle.current.copy(fontStyle = FontStyle.Italic)) {
                // Bug fix: 之前用 drawWithContent + drawRect 把 bg 画在 content 之上 (蒙层覆盖
                // 文字), 深色下文字几乎看不见. 改 drawBehind 让 bg 画在 content 之后.
                // 同时降低 bgColor alpha (0.2 → 0.12), 在深色下也清爽.
                val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)
                StreamingBlockReveal(node = node, modifier = modifier) { revealModifier ->
                    Column(
                        modifier = revealModifier
                            .drawBehind {
                                drawRect(
                                    color = bgColor, size = size
                                )
                                drawRect(
                                    color = borderColor, size = Size(10f, size.height)
                                )
                            }
                            .padding(8.dp)
                    ) {
                        node.children.fastForEach { child ->
                            val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                                children = node.children,
                                child = child,
                                liveSuffix = liveSuffix,
                                liveSuffixSourceOffset = liveSuffixSourceOffset,
                            )
                            MarkdownNode(
                                node = child,
                                content = content,
                                onClickCitation = onClickCitation,
                                liveSuffix = childLiveSuffix.text,
                                liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                            )
                        }
                    }
                }
            }
        }

        // 链接 — only the INLINE_LINK form had a block-level arm originally. GFM_AUTOLINK (leaf)
        // and AUTOLINK (<url>) collapse to Link too, but the original `when` had no arm for them,
        // so they fell to `else` (recurse). Reproduce: handle only non-leaf, non-autolink Links
        // here; route the rest to the same child-recursion the `else` branch performs.
        MdNodeType.Link -> {
            if (node.isAutolink || node.children.isEmpty()) {
                node.children.fastForEach { child ->
                    val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                        children = node.children,
                        child = child,
                        liveSuffix = liveSuffix,
                        liveSuffixSourceOffset = liveSuffixSourceOffset,
                    )
                    MarkdownNode(
                        node = child,
                        content = content,
                        modifier = modifier,
                        onClickCitation = onClickCitation,
                        liveSuffix = childLiveSuffix.text,
                        liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                    )
                }
            } else {
                // INLINE_LINK. mapping doc §4 H6/KU-1. linkInnerText relocates the
                // LINK_TEXT→GFM_AUTOLINK/TEXT dig (1600-1601); linkHref relocates LINK_DESTINATION (E5).
                val linkText = node.linkInnerText ?: ""
                val linkDest = node.linkHref ?: ""
                val context = LocalContext.current
                Text(
                    text = linkText,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = modifier.clickable {
                        context.openUrl(linkDest)
                    })
            }
        }

        // 加粗和斜体
        MdNodeType.Emphasis -> {
            ProvideTextStyle(TextStyle(fontStyle = FontStyle.Italic)) {
                node.children.fastForEach { child ->
                    val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                        children = node.children,
                        child = child,
                        liveSuffix = liveSuffix,
                        liveSuffixSourceOffset = liveSuffixSourceOffset,
                    )
                    MarkdownNode(
                        node = child,
                        content = content,
                        modifier = modifier,
                        onClickCitation = onClickCitation,
                        liveSuffix = childLiveSuffix.text,
                        liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                    )
                }
            }
        }

        MdNodeType.Strong -> {
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.SemiBold)) {
                node.children.fastForEach { child ->
                    val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                        children = node.children,
                        child = child,
                        liveSuffix = liveSuffix,
                        liveSuffixSourceOffset = liveSuffixSourceOffset,
                    )
                    MarkdownNode(
                        node = child,
                        content = content,
                        modifier = modifier,
                        onClickCitation = onClickCitation,
                        liveSuffix = childLiveSuffix.text,
                        liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                    )
                }
            }
        }

        // GFM 特殊元素
        MdNodeType.Strikethrough -> {
            Text(
                text = node.textIn(content), textDecoration = TextDecoration.LineThrough, modifier = modifier
            )
        }

        MdNodeType.Table -> {
            TableNode(
                node = node,
                content = content,
                modifier = modifier,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
        }

        MdNodeType.HorizontalRule -> {
            StreamingBlockReveal(node = node) { revealModifier ->
                HorizontalDivider(
                    modifier = revealModifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )
            }
        }

        // 图片
        MdNodeType.Image -> {
            // alt = LINK_TEXT text (untrimmed, mapping doc §3 E4 — linkLabel relocates the
            // findChildOfTypeRecursive(LINK_TEXT) dig); src via imageSrc accessor.
            val altText = markdownImageContentDescription(node)
            val imageUrl = node.imageSrc ?: ""
            if (LocalSearchImageUrls.current?.contains(imageUrl) == true) return
            StreamingBlockReveal(node = node, modifier = modifier) { revealModifier ->
                Column(
                    modifier = revealModifier, horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 这里可以使用Coil等图片加载库加载图片
                    ZoomableAsyncImage(
                        model = imageUrl,
                        contentDescription = altText,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .widthIn(min = 120.dp, max = 360.dp)
                            .heightIn(min = 120.dp, max = 420.dp),
                    )
                }
            }
        }

        MdNodeType.MathInline -> {
            val formula = node.textIn(content)
            val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
            if (enableLatexRendering) {
                MathInline(
                    formula, modifier = modifier.padding(horizontal = 1.dp)
                )
            } else {
                Text(
                    text = formula,
                    fontFamily = FontFamily.Monospace,
                    modifier = modifier.padding(horizontal = 1.dp)
                )
            }
        }

        MdNodeType.MathBlock -> {
            val formula = node.textIn(content)
            val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
            StreamingBlockReveal(node = node, modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) { revealModifier ->
                if (enableLatexRendering) {
                    MathBlock(
                        formula,
                        modifier = revealModifier
                    )
                } else {
                    Text(
                        text = formula,
                        fontFamily = FontFamily.Monospace,
                        modifier = revealModifier
                    )
                }
            }
        }

        MdNodeType.InlineCode -> {
            val code = node.textIn(content).trim('`')
            Text(
                text = code, fontFamily = FontFamily.Monospace, modifier = modifier
            )
        }

        // 代码块 — CODE_BLOCK (indented) + CODE_FENCE (fenced) collapse to one MdNodeType.CodeBlock
        // (mapping doc §2 #8/#9, §4 H4). isFencedCode picks the original two render paths.
        MdNodeType.CodeBlock -> {
            val fenceRange = node.codeFenceContentRange
            if (!node.isFencedCode || fenceRange == null) {
                // Indented code block (or a malformed fence whose body could not be located —
                // matching the original CODE_FENCE arm's early-returns, which rendered nothing).
                if (node.isFencedCode) return
                val code = node.textIn(content)
                StreamingBlockReveal(node = node, modifier = modifier) { revealModifier ->
                    Text(
                        text = code,
                        modifier = revealModifier,
                    )
                }
            } else {
                // 这里不能直接取CODE_FENCE_CONTENT的内容，因为首行indent没有包含在内
                // 因此，需要往上找到最后一个EOL元素，用它来作为代码块的起始offset
                // (the EOL-backup body-offset computation now lives in JvmMdNode.codeFenceContentRange)
                val codeContentStartOffset = fenceRange.first
                val codeContentEndOffset = fenceRange.last + 1
                val rawCode = content.substring(codeContentStartOffset, codeContentEndOffset)

                val language = node.codeLang ?: "plaintext"
                val sourceOffsetBase = LocalMarkdownSourceOffsetBase.current
                val syntheticSuffixStart = LocalMarkdownSyntheticSuffixStart.current
                val endFenceEnd = node.codeFenceEndOffset
                val hasEnd = endFenceEnd != null && sourceOffsetBase + endFenceEnd <= syntheticSuffixStart
                val liveCodeSuffix = streamingCodeFenceLiveSuffixFor(
                    sourceOffsetBase = sourceOffsetBase,
                    codeContentStartOffset = codeContentStartOffset,
                    codeContentEndOffset = codeContentEndOffset,
                    syntheticSuffixStart = syntheticSuffixStart,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                val code = (rawCode + liveCodeSuffix).trimIndent()

                StreamingBlockReveal(node = node, modifier = Modifier.padding(bottom = 4.dp).fillMaxWidth()) { revealModifier ->
                    HighlightCodeBlock(
                        code = code,
                        language = language,
                        modifier = revealModifier,
                        completeCodeBlock = hasEnd
                    )
                }
            }
        }

        MdNodeType.Text -> {
            // Parity class [B] fix: resolve CommonMark backslash escapes here too. This is the
            // SECOND leaf-text render path (the standalone-`Text` composable used by `MarkdownNode`'s
            // per-child dispatch — Emphasis/Strong/Link loops AND the `Paragraph` FlowRow path taken
            // when the block contains an Image/MathBlock). The AnnotatedString leaf arm in
            // `appendMarkdownNodeContent` covers the grouped-paragraph path; both must apply the same
            // rule so `\!`/`\*` render as `!`/`*` regardless of which path the block routes through.
            // InlineCode/MathInline/CodeBlock have dedicated arms ABOVE this one, so code/math slices
            // stay raw. Native Text leaves carry no backslash (escape already sliced away) → idempotent.
            val text = resolveBackslashEscapes(node.textIn(content))
            Text(
                text = text,
                modifier = modifier,
            )
        }

        MdNodeType.HtmlBlock -> {
            val text = node.textIn(content)
            StreamingBlockReveal(node = node, modifier = modifier) { revealModifier ->
                SimpleHtmlBlock(
                    html = text, modifier = revealModifier
                )
            }
        }

        // 其他类型的节点，递归处理子节点
        else -> {
            // 递归处理其他节点的子节点
            node.children.fastForEach { child ->
                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                    children = node.children,
                    child = child,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                MarkdownNode(
                    node = child,
                    content = content,
                    modifier = modifier,
                    onClickCitation = onClickCitation,
                    liveSuffix = childLiveSuffix.text,
                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                )
            }
        }
    }
}

internal fun streamingCodeFenceLiveSuffixFor(
    sourceOffsetBase: Int,
    codeContentStartOffset: Int,
    codeContentEndOffset: Int,
    syntheticSuffixStart: Int,
    liveSuffix: String,
    liveSuffixSourceOffset: Int,
): String {
    if (liveSuffix.isEmpty()) return ""
    if (syntheticSuffixStart == Int.MAX_VALUE) return ""
    val absoluteCodeStart = sourceOffsetBase + codeContentStartOffset
    val absoluteCodeEnd = sourceOffsetBase + codeContentEndOffset
    if (liveSuffixSourceOffset < absoluteCodeStart) return ""
    if (liveSuffixSourceOffset < absoluteCodeEnd) return ""
    if (liveSuffixSourceOffset != syntheticSuffixStart) return ""
    return liveSuffix.withoutTrailingFenceTerminator()
}

private fun String.withoutTrailingFenceTerminator(): String {
    val trimmedEnd = trimEnd()
    val start = trimmedEnd.lastIndexOf('\n').let { if (it == -1) 0 else it + 1 }
    val lastLine = trimmedEnd.substring(start)
    if (!lastLine.matches(TRAILING_FENCE_TERMINATOR_REGEX)) return this
    return trimmedEnd.substring(0, start).removeSuffix("\n")
}

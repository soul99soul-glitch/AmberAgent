package app.amber.feature.ui.components.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Sparkles
import app.amber.agent.R
import app.amber.feature.ui.pages.chat.LocalChatTheme

private val LocalCardColor = staticCompositionLocalOf { Color.White }

/**
 * 以时间线/步骤卡片的形式展示一组思考过程。
 *
 * 适用于承载推理步骤、工具调用步骤，或两者混合的链式内容。组件支持：
 * - 在步骤较多时自动折叠，仅展示最后若干步
 * - 点击顶部控制条展开/收起全部步骤
 * - 通过 [collapsedAdaptiveWidth] 控制折叠态是否保持自适应宽度
 *
 * @param modifier 外层卡片的修饰符
 * @param cardColors 卡片配色
 * @param steps 需要渲染的步骤数据列表
 * @param collapsedVisibleCount 折叠时保留可见的尾部步骤数
 * @param collapsedAdaptiveWidth 是否在折叠态下使用内容自适应宽度
 * @param content 每个步骤的具体 UI，由 [ChainOfThoughtScope] 提供步骤构建能力
 */
@Composable
fun <T> ChainOfThought(
    modifier: Modifier = Modifier,
    // V3 设计稿: ChainOfThought wrapper 不要外框 (Card + surfaceColorAtElevation 是
    //   "灰色背景框")。每个 step 自己负责自己的视觉样式 (reasoning 用思考胶囊,
    //   工具用 capsule 等). 透明 + 0 elevation = 视觉无框, 还保留 Card semantics
    //   (rounded corners 不需要; 留给可能的未来需要).
    cardColors: CardColors = CardDefaults.cardColors(
        containerColor = Color.Transparent,
    ),
    steps: List<T>,
    collapsedVisibleCount: Int = 2,
    collapsedAdaptiveWidth: Boolean = false,
    animateContentChanges: Boolean = true,
    // V3 设计稿: reasoning step 不需要 wrapper 竖线；多 step 链条仍可保留 wrapper 竖线
    // 作时间线 visual.
    drawTimeline: Boolean = true,
    content: @Composable ChainOfThoughtScope.(T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val canCollapse = steps.size > collapsedVisibleCount
    val shouldFillCollapseControlWidth = expanded || !collapsedAdaptiveWidth

    // V3: cardColors 透明时, icon 后面用 scheme.background 遮挡, 避免 wrapper timeline
    // 竖线穿过 icon center. (LocalCardColor 之前是 cardColors.containerColor, 透明时遮挡失效)
    val maskColor = if (cardColors.containerColor == Color.Transparent) {
        MaterialTheme.colorScheme.background
    } else {
        cardColors.containerColor
    }
    CompositionLocalProvider(
        LocalCardColor provides maskColor
    ) {
        Card(
            modifier = modifier,
            colors = cardColors,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    // V3: padding 内缩到 0 (外框已透明, 不需要 inner padding)
                    .then(
                        if (animateContentChanges) {
                            Modifier.animateContentSize(
                                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
                            )
                        } else {
                            Modifier
                        }
                    ),
            ) {
                val visibleSteps = if (expanded || !canCollapse) {
                    steps
                } else {
                    steps.takeLast(collapsedVisibleCount)
                }

                // 显示展开/折叠按钮（统一在顶部）
                if (canCollapse) {
                    Box(
                        modifier = Modifier
                            .then(
                                if (shouldFillCollapseControlWidth) {
                                    Modifier.fillMaxWidth()
                                } else {
                                    Modifier
                                }
                            )
                            .clip(MaterialTheme.shapes.small)
                            .clickable { expanded = !expanded }
                            .minimumInteractiveComponentSize(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 左侧：图标区域（24.dp，和步骤图标对齐）
                            Box(
                                modifier = Modifier.width(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (expanded) Lucide.ArrowUp else Lucide.ArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }

                            // 右侧：文字区域（8.dp 间距后开始，和步骤 label 对齐）
                            Text(
                                modifier = Modifier.padding(start = 8.dp),
                                text = if (expanded) {
                                    stringResource(R.string.chain_of_thought_collapse)
                                } else {
                                    stringResource(
                                        R.string.chain_of_thought_show_more_steps,
                                        steps.size - collapsedVisibleCount
                                    )
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // V3: 左竖线用 chatTheme.thinkRule 替代 outlineVariant (faint 灰)
                // 这样多步骤链条跟主题 accent 走（Whisper 蓝 / Paper 砖红 / Plain 黑 / Midnight 靛蓝）
                // drawTimeline=false 时跳过竖线。
                val lineColor = app.amber.feature.ui.pages.chat.LocalChatTheme.current.thinkRule
                val scope = remember { ChainOfThoughtScopeImpl() }
                Box(
                    modifier = if (drawTimeline) {
                        Modifier.drawBehind {
                            val x = 12.dp.toPx()
                            val offsetPx = 18.dp.toPx()
                            drawLine(
                                color = lineColor,
                                start = Offset(x, offsetPx),
                                end = Offset(x, size.height - offsetPx),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                    } else {
                        Modifier
                    }
                ) {
                    Column {
                        visibleSteps.fastForEach { step ->
                            scope.content(step)
                        }
                    }
                }
            }
        }
    }
}

/**
 * [ChainOfThought] 内部使用的步骤渲染作用域。
 *
 * 通过该作用域可以声明单个步骤的图标、标题、附加信息以及可展开内容，
 * 并复用统一的时间线布局与交互行为。
 */
interface ChainOfThoughtScope {
    /**
     * 声明一个非受控步骤，由组件内部管理展开/折叠状态。
     *
     * @param icon 步骤图标
     * @param label 步骤标题区域
     * @param extra 标题右侧的附加信息
     * @param onClick 自定义点击行为；设置后优先于展开/折叠逻辑
     * @param collapsedAdaptiveWidth 是否在折叠且内容隐藏时使用自适应宽度
     * @param content 步骤展开后显示的内容；为 `null` 时步骤不可展开
     */
    @Composable
    fun ChainOfThoughtStep(
        icon: (@Composable () -> Unit)? = null,
        label: (@Composable () -> Unit),
        extra: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        collapsedAdaptiveWidth: Boolean = false,
        content: (@Composable () -> Unit)? = null,
    )

    /**
     * 声明一个受控步骤，由外部传入展开状态。
     *
     * 适合需要与外部状态联动的场景，例如“推理中预览 / 完成后收起”。
     *
     * @param expanded 当前是否处于展开状态
     * @param onExpandedChange 展开状态变化回调
     * @param icon 步骤图标
     * @param label 步骤标题区域
     * @param extra 标题右侧的附加信息
     * @param onClick 自定义点击行为；设置后优先于展开/折叠逻辑
     * @param collapsedAdaptiveWidth 是否在折叠且内容隐藏时使用自适应宽度
     * @param contentVisible 是否展示内容区域，可与 [expanded] 解耦
     * @param content 步骤内容；为 `null` 时步骤不可展开
     */
    @Composable
    fun ControlledChainOfThoughtStep(
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        icon: (@Composable () -> Unit)? = null,
        label: (@Composable () -> Unit),
        extra: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        collapsedAdaptiveWidth: Boolean = false,
        contentVisible: Boolean = expanded,
        /** true 时 content 起始 X 跟 step icon center (12dp) 对齐 (而非默认 32dp label 缩进). */
        flushContent: Boolean = false,
        /** true 时为该步骤绘制思考外框；普通工具步骤保持无框。 */
        framed: Boolean = false,
        content: (@Composable () -> Unit)? = null,
    )
}

private class ChainOfThoughtScopeImpl : ChainOfThoughtScope {
    @Composable
    override fun ChainOfThoughtStep(
        icon: @Composable (() -> Unit)?,
        label: @Composable (() -> Unit),
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        collapsedAdaptiveWidth: Boolean,
        content: @Composable (() -> Unit)?
    ) {
        var expanded by remember { mutableStateOf(false) }
        ChainOfThoughtStepContent(
            icon = icon,
            label = label,
            extra = extra,
            onClick = onClick,
            collapsedAdaptiveWidth = collapsedAdaptiveWidth,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            contentVisible = expanded,
            content = content,
        )
    }

    @Composable
    override fun ControlledChainOfThoughtStep(
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        icon: @Composable (() -> Unit)?,
        label: @Composable (() -> Unit),
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        collapsedAdaptiveWidth: Boolean,
        contentVisible: Boolean,
        flushContent: Boolean,
        framed: Boolean,
        content: @Composable (() -> Unit)?
    ) {
        ChainOfThoughtStepContent(
            icon = icon,
            label = label,
            extra = extra,
            onClick = onClick,
            collapsedAdaptiveWidth = collapsedAdaptiveWidth,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            contentVisible = contentVisible,
            flushContent = flushContent,
            framed = framed,
            content = content,
        )
    }

    @Composable
    private fun ChainOfThoughtStepContent(
        icon: @Composable (() -> Unit)?,
        label: @Composable (() -> Unit),
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        collapsedAdaptiveWidth: Boolean,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        contentVisible: Boolean,
        flushContent: Boolean = false,
        framed: Boolean = false,
        content: @Composable (() -> Unit)?
    ) {
        val hasContent = content != null
        val compactFramed = framed && !contentVisible
        // A collapsed framed step is the compact thinking capsule. It must wrap its label even
        // when the surrounding ThinkingBlock also contains tool steps; expanded content still
        // takes the full available width.
        val shouldFillMaxWidth = contentVisible || (!framed && !collapsedAdaptiveWidth)
        val frameTokens = app.amber.feature.ui.theme.LocalAmberTokens.current
        val chatTheme = LocalChatTheme.current
        val frameShape = RoundedCornerShape(18.dp)
        // Match tool capsules: a light tint over the conversation paper, kept opaque so
        // the background texture does not leak into the capsule.
        val capsuleBackground = chatTheme.accent
            .copy(alpha = if (chatTheme.isDark) 0.16f else 0.08f)
            .compositeOver(chatTheme.bg)
        val capsuleBorder = chatTheme.accent
            .copy(alpha = if (chatTheme.isDark) 0.24f else 0.14f)
            .compositeOver(chatTheme.bg)
        val stepBackground = if (framed) capsuleBackground else LocalCardColor.current
        // Expanded framed content keeps the original full-width card. In the collapsed state
        // the visual frame moves onto the compact label below so the 48dp hit target can remain
        // transparent around it.
        val frameModifier = if (framed && !compactFramed) {
            Modifier
                .clip(frameShape)
                // Keep the title's 28dp visual row centered in the same 48dp
                // touch box as the collapsed capsule. The visible expanded
                // frame starts at that same y=10dp edge; only its drawing is
                // inset, so the title and body layout do not move.
                .drawBehind {
                    val topInset = 10.dp.toPx()
                    val frameSize = androidx.compose.ui.geometry.Size(
                        width = size.width,
                        height = (size.height - topInset).coerceAtLeast(0f),
                    )
                    val topLeft = Offset(0f, topInset)
                    val radius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx())
                    drawRoundRect(
                        color = capsuleBackground,
                        topLeft = topLeft,
                        size = frameSize,
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = capsuleBorder,
                        topLeft = topLeft,
                        size = frameSize,
                        cornerRadius = radius,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
                .padding(horizontal = 8.dp)
        } else {
            Modifier
        }
        val compactInteractionSource = remember { MutableInteractionSource() }
        val compactInteractive = compactFramed && (onClick != null || hasContent)
        val stepAction = {
            if (onClick != null) onClick() else onExpandedChange(!expanded)
        }

        Column(
            modifier = frameModifier.then(
                if (shouldFillMaxWidth) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                }
            ),
        ) {
            // Label 行：Icon + Label + Extra + 指示器
            Box(
                modifier = Modifier
                    .then(
                        if (shouldFillMaxWidth) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier
                        }
                    )
                    .then(if (onClick != null || hasContent) {
                        if (compactInteractive) {
                            // The outer node provides the minimum touch target without drawing
                            // an indication. The inner visual node below observes the same
                            // interactions and clips the ripple to the actual CircleShape.
                            Modifier
                                .clickable(
                                    interactionSource = compactInteractionSource,
                                    indication = null,
                                    onClick = stepAction,
                                )
                                .minimumInteractiveComponentSize()
                        } else {
                            Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable(onClick = stepAction)
                                .minimumInteractiveComponentSize()
                        }
                    } else {
                        Modifier
                    }),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = if (compactFramed) {
                        Modifier
                            .clip(CircleShape)
                            .background(capsuleBackground, CircleShape)
                            .border(1.dp, capsuleBorder, CircleShape)
                            .then(
                                if (compactInteractive) {
                                    Modifier.indication(
                                        interactionSource = compactInteractionSource,
                                        indication = LocalIndication.current,
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 8.dp)
                    } else {
                        Modifier
                    },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        modifier = Modifier
                            .then(
                                if (shouldFillMaxWidth) {
                                    Modifier.fillMaxWidth()
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = if (framed) 6.dp else 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (framed) 4.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    // Icon（不透明背景遮住背后的连线）
                    Box(
                        modifier = Modifier.width(if (framed) 16.dp else 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (framed) 16.dp else 20.dp)
                                .background(stepBackground),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (icon != null) {
                                Box(
                                    modifier = Modifier.size(14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    icon()
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                    }

                    // Label
                    Box(
                        modifier = Modifier.then(
                            if (shouldFillMaxWidth) {
                                Modifier.weight(1f)
                            } else {
                                Modifier
                            }
                        )
                    ) {
                        label()
                    }

                    // Extra
                    if (extra != null) {
                        extra()
                    }

                    // 指示器：onClick 显示向右箭头，content 显示展开/折叠箭头
                    if (onClick != null) {
                        Icon(
                            imageVector = if (framed) Lucide.ChevronRight else Lucide.ArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(if (framed) 12.dp else 16.dp),
                            tint = if (framed) {
                                chatTheme.inkSoft
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    } else if (hasContent) {
                        Icon(
                            imageVector = if (framed) {
                                if (expanded) Lucide.ChevronUp else Lucide.ChevronDown
                            } else {
                                if (expanded) Lucide.ArrowUp else Lucide.ArrowDown
                            },
                            contentDescription = null,
                            modifier = Modifier.size(if (framed) 12.dp else 16.dp),
                            tint = if (framed) {
                                chatTheme.inkSoft
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    }
                }
            }

            // Framed thoughts have symmetric body insets (16dp including the outer frame).
            // Unframed steps retain their icon/label alignment.
            // 用 AnimatedVisibility 加 expand+fade 过渡, 替代之前直接 if-render 的硬切.
            val contentStartPadding = when {
                framed -> 8.dp
                flushContent -> 12.dp
                else -> 32.dp
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = contentVisible && hasContent,
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 220,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                    expandFrom = androidx.compose.ui.Alignment.Top,
                ) + androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(180),
                ),
                exit = androidx.compose.animation.shrinkVertically(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 180,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                    shrinkTowards = androidx.compose.ui.Alignment.Top,
                ) + androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(140),
                ),
            ) {
                Box(
                    modifier = Modifier
                        .then(
                            if (shouldFillMaxWidth) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier
                            }
                        )
                        .padding(
                            start = contentStartPadding,
                            end = if (framed) contentStartPadding else 0.dp,
                            // Keep the body attached to the same top edge as the capsule header;
                            // the previous top inset made expand/shrink look like a second jump.
                            top = 0.dp,
                            bottom = 8.dp,
                        )
                ) {
                    content?.invoke()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChainOfThoughtPreview() {
    // 定义步骤数据类
    data class StepData(
        val label: String,
        val icon: ImageVector?,
        val status: String?,
        val hasContent: Boolean = false,
        val hasOnClick: Boolean = false,
        val controlled: Boolean = false,
    )

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("Chain of thought")
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier.padding(innerPadding),
            ) {
                // 受控状态示例
                var controlledExpanded by remember { mutableStateOf(false) }

                ChainOfThought(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    steps = listOf(
                        StepData("Searching the web", Lucide.Search, "3 results", hasContent = true),
                        StepData("Reading documents", Lucide.Sparkles, "Completed", hasOnClick = true),
                        StepData(
                            "Analyzing results (controlled)",
                            Lucide.Sparkles,
                            "In progress",
                            hasContent = true,
                            controlled = true
                        ),
                        StepData("Step without icon", null, null),
                        StepData("Final step", Lucide.Sparkles, "Done"),
                    ),
                    collapsedVisibleCount = 2,
                ) { step ->
                    val iconComposable: (@Composable () -> Unit)? = step.icon?.let {
                        {
                            Icon(
                                imageVector = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    val labelComposable: @Composable () -> Unit = {
                        Text(step.label, style = MaterialTheme.typography.bodyMedium)
                    }
                    val extraComposable: (@Composable () -> Unit)? = step.status?.let {
                        {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val onClickHandler: (() -> Unit)? = if (step.hasOnClick) {
                        { /* Open bottom sheet */ }
                    } else null
                    val contentComposable: (@Composable () -> Unit)? = if (step.hasContent) {
                        {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (step.label.contains("Search")) {
                                    listOf(
                                        "example.com - Example Domain",
                                        "docs.example.com - Documentation",
                                        "blog.example.com - Blog Post"
                                    ).forEach { result ->
                                        Text(
                                            text = "• $result",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "This is expandable content showing detailed analysis. " +
                                            "It can contain multiple lines of text, code snippets, " +
                                            "or any other composable content.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    } else null

                    if (step.controlled) {
                        // 受控版本
                        ControlledChainOfThoughtStep(
                            expanded = controlledExpanded,
                            onExpandedChange = { controlledExpanded = it },
                            icon = iconComposable,
                            label = labelComposable,
                            extra = extraComposable,
                            onClick = onClickHandler,
                            content = contentComposable,
                        )
                    } else {
                        // 非受控版本
                        ChainOfThoughtStep(
                            icon = iconComposable,
                            label = labelComposable,
                            extra = extraComposable,
                            onClick = onClickHandler,
                            content = contentComposable,
                        )
                    }
                }
            }
        }
    }
}

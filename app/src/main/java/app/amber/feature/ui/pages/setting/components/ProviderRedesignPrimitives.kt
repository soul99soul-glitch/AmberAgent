package app.amber.feature.ui.pages.setting.components

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.utils.writeClipboardText
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.R
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.LocalThemeDesign

/*
 * Provider settings primitives — the Android form of the shared Amber "card group" language
 * (iOS insetGrouped): sans caption headers, surface cards with a 0.5dp hairline, label-above-value
 * rows, one status-chip vocabulary. Machine facts (model ids, hosts, flags) stay JetBrains Mono.
 */

internal data class ProviderSegOption<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
    val badge: String? = null,
)

/** Theme-pack card radius; 18dp matches the iOS grouped cards when no pack overrides it. */
@Composable
internal fun providerCardRadius(): Dp =
    LocalThemeDesign.current?.components?.cardRadius?.toFloat()?.dp ?: 18.dp

@Composable
internal fun providerDangerColor(): Color =
    if (LocalAmberTokens.current.isDark) Color(0xFFE5715E) else Color(0xFFC8402F)

/** Caption-style group header (iOS insetGrouped): sans, 12sp, ink3, optional count on the right. */
@Composable
internal fun ProviderSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.weight(1f),
            style = type.secondary.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
            color = t.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = type.secondary.copy(fontWeight = FontWeight.Medium),
                color = t.ink4,
            )
        }
    }
}

@Composable
internal fun ProviderCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalAmberTokens.current
    val shape = RoundedCornerShape(providerCardRadius())
    Column(
        modifier = modifier
            .clip(shape)
            .background(t.surface)
            .border(0.5.dp, t.line2, shape),
        content = content,
    )
}

/**
 * One slice of a card whose rows are separate lazy items (reorderable lists). The hairline is
 * drawn as one rounded rect extended past the inner edges, so stacked slices read as a single card.
 */
@Composable
internal fun ProviderGroupSegment(
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalAmberTokens.current
    val radius = providerCardRadius()
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp,
    )
    Column(
        modifier = modifier
            .clip(shape)
            .background(t.surface)
            .drawWithContent {
                drawContent()
                val stroke = 0.5.dp.toPx()
                val overshoot = radius.toPx() * 2
                val top = if (first) stroke / 2 else -overshoot
                val bottom = if (last) size.height - stroke / 2 else size.height + overshoot
                drawRoundRect(
                    color = t.line2,
                    topLeft = Offset(stroke / 2, top),
                    size = Size(size.width - stroke, bottom - top),
                    cornerRadius = CornerRadius((radius.toPx() - stroke / 2).coerceAtLeast(0f)),
                    style = Stroke(stroke),
                )
            },
        content = content,
    )
}

@Composable
internal fun ProviderHairline(modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(t.line.copy(alpha = 0.46f))
    )
}

/** Row separator inside a card; [inset] = leading element width + gap, like iOS. */
@Composable
internal fun ProviderRowDivider(inset: Dp = 14.dp) {
    val t = LocalAmberTokens.current
    Box(
        modifier = Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(t.line2)
    )
}

@Composable
internal fun ProviderMonogram(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    enabled: Boolean = true,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val displayText = text.trim().take(4).ifBlank { "AI" }
    val fontRatio = when {
        displayText.length >= 4 -> 0.27f
        displayText.length == 3 -> 0.31f
        else -> 0.37f
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(t.surface2)
            .graphicsLayer { alpha = if (enabled) 1f else 0.55f },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = displayText,
            style = type.body.copy(
                fontSize = (size.value * fontRatio).sp,
                lineHeight = (size.value * fontRatio * 1.2f).sp,
                fontWeight = FontWeight.Bold,
            ),
            color = t.ink2,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** 36dp rounded tile for template / model rows. */
@Composable
internal fun ProviderTile(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    icon: ImageVector? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint?.copy(alpha = 0.12f) ?: t.surface2),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint ?: t.ink3, modifier = Modifier.size(18.dp))
        } else {
            Text(
                text = text.take(4),
                style = type.meta.copy(fontSize = if (text.length > 2) 10.sp else 12.sp, fontWeight = FontWeight.Bold),
                color = tint ?: t.ink2,
                maxLines = 1,
            )
        }
    }
}

/** Standard card row: optional leading, title (+ subtitle), optional trailing. */
@Composable
internal fun ProviderListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleMono: Boolean = false,
    titleColor: Color = Color.Unspecified,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
    minHeight: Dp = if (subtitle != null) 62.dp else 52.dp,
    dimmed: Boolean = false,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 9.dp)
                .graphicsLayer { alpha = if (dimmed) 0.55f else 1f },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = if (titleMono) {
                        type.meta.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        type.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                    },
                    color = if (titleColor == Color.Unspecified) t.ink else titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                titleTrailing?.invoke(this)
            }
            subtitle?.invoke()
        }
        trailing?.invoke(this)
    }
}

@Composable
internal fun ProviderRowSubtitle(text: String, mono: Boolean = false) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Text(
        text = text,
        style = if (mono) type.meta.copy(fontSize = 11.5.sp) else type.secondary.copy(fontSize = 12.5.sp),
        color = t.ink3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal enum class ProviderTone { Ok, Neutral, Warn, Accent }

@Composable
private fun providerToneColors(tone: ProviderTone): Pair<Color, Color> {
    val t = LocalAmberTokens.current
    return when (tone) {
        ProviderTone.Ok -> Color(0xFF3DA35D).copy(alpha = 0.13f) to
            if (t.isDark) Color(0xFF6CC489) else Color(0xFF2F8A4C)
        ProviderTone.Warn -> Color(0xFFD98324).copy(alpha = 0.14f) to
            if (t.isDark) Color(0xFFE3A55A) else Color(0xFFA8641A)
        ProviderTone.Accent -> t.accent.copy(alpha = 0.13f) to t.accent
        ProviderTone.Neutral -> t.ink4.copy(alpha = 0.15f) to t.ink3
    }
}

/** The single status vocabulary for provider / model state (replaces the old auth badges). */
@Composable
internal fun ProviderStatusChip(
    text: String,
    tone: ProviderTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = if (tone == ProviderTone.Ok) Lucide.Check else null,
    mono: Boolean = false,
) {
    val type = LocalAmberType.current
    val (bg, fg) = providerToneColors(tone)
    Row(
        modifier = modifier
            .height(24.dp)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        }
        Text(
            text = text,
            style = if (mono) {
                type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            } else {
                type.secondary.copy(fontWeight = FontWeight.SemiBold)
            },
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ProviderIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 48.dp,
    iconSize: Dp = 20.dp,
    rotate180: Boolean = false,
) {
    val t = LocalAmberTokens.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (tint == Color.Unspecified) t.ink else tint,
            modifier = Modifier
                .size(iconSize)
                .then(if (rotate180) Modifier.rotate(180f) else Modifier),
        )
    }
}

/** Single-choice connected segmented control: 40dp, round ends, 1dp outline, accent-tinted pick. */
@Composable
internal fun <T> ProviderSegmented(
    options: List<ProviderSegOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(modifier = modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(CircleShape)
                .border(1.dp, t.line2, CircleShape),
        ) {
            options.forEachIndexed { index, option ->
                val on = option.value == selected
                if (index > 0) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(t.line2)
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (on) t.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .pressable(onClick = { onSelected(option.value) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val color = if (on) t.accent else t.ink2
                    if (option.icon != null) {
                        Icon(option.icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = option.label,
                        style = type.body.copy(
                            fontSize = if (height < 40.dp) 13.5.sp else 14.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (option.badge != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = option.badge,
                            style = type.meta.copy(fontSize = 12.sp),
                            color = color.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Separate multi-select chip (Material FilterChip shape): outlined when off, accent tint + ✓ when on. */
@Composable
internal fun ProviderFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(shape)
                .then(
                    if (selected) Modifier.background(t.accent.copy(alpha = 0.12f))
                    else Modifier.border(1.dp, t.line2, shape)
                )
                .padding(start = if (selected) 10.dp else 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (selected) {
                Icon(Lucide.Check, contentDescription = null, tint = t.accent, modifier = Modifier.size(14.dp))
            }
            Text(
                text = text,
                style = (if (mono) type.meta.copy(fontSize = 12.5.sp) else type.body.copy(fontSize = 13.5.sp))
                    .copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                color = if (selected) t.accent else t.ink2,
                maxLines = 1,
            )
        }
    }
}

/** Kept for the MCP editor: spaced pill choice. */
@Composable
internal fun <T> ProviderPillSeg(
    options: List<ProviderSegOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier.heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val on = option.value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .pressable(onClick = { onSelected(option.value) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(CircleShape)
                        .background(if (on) t.accent.copy(alpha = 0.10f) else t.surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        style = (if (mono) type.meta else type.secondary).copy(
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (on) t.accent else t.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProviderFieldLabel(text: String, modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(start = 2.dp, bottom = 7.dp),
        style = type.meta.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
        color = t.ink3,
        maxLines = 1,
    )
}

/** Pill search field shared by the provider list and the add-model sheet. */
@Composable
internal fun ProviderSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val shape = CircleShape
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(containerColor ?: t.surface)
            .border(0.5.dp, t.line2, shape)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.Search, contentDescription = null, tint = t.ink4, modifier = Modifier.size(16.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = type.body.copy(fontSize = 14.5.sp, color = t.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(t.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = type.body.copy(fontSize = 14.5.sp),
                            color = t.ink4,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            ProviderIconButton(
                imageVector = Lucide.X,
                contentDescription = stringResource(R.string.provider_filter_clear),
                onClick = { onValueChange("") },
                tint = t.ink3,
                size = 40.dp,
                iconSize = 16.dp,
            )
        }
    }
}

/**
 * Inside a card, fields render borderless as label-above-value rows (the card's hairlines do the
 * separating, the label turns accent while focused). [ProviderLabeledField] in the provider
 * package turns this on for its content.
 */
internal val LocalProviderFieldInCard = staticCompositionLocalOf { false }

/** Horizontal inset of card rows; consoles that already sit in a padded slot provide 0. */
internal val LocalProviderRowInset = staticCompositionLocalOf { 14.dp }

/** Card whose rows are separated by inset hairlines; build [rows] conditionally with buildList. */
@Composable
internal fun ProviderCardRows(
    rows: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    dividerInset: Dp = 14.dp,
) {
    if (rows.isEmpty()) return
    ProviderCard(modifier = modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            if (index > 0) ProviderRowDivider(dividerInset)
            row()
        }
    }
}

@Composable
internal fun ProviderTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    isError: Boolean = false,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Dp = 46.dp,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val inCard = LocalProviderFieldInCard.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val baseStyle: TextStyle = if (inCard) {
        if (mono) type.meta.copy(fontSize = 13.5.sp, lineHeight = 19.sp) else type.body.copy(fontSize = 15.sp, lineHeight = 21.sp)
    } else {
        if (mono) type.meta else type.body
    }
    val danger = providerDangerColor()
    // Don't merge LocalTextStyle: the Material typography's font family would override mono.
    val textStyle = baseStyle.copy(
        color = when {
            isError && inCard -> danger
            readOnly && inCard -> t.ink3
            else -> t.ink
        },
    )
    val fieldModifier = if (inCard) {
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (singleLine) 24.dp else minHeight)
    } else {
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface2)
            .border(
                1.dp,
                when {
                    isError -> danger
                    focused -> t.accent
                    else -> t.line.copy(alpha = 0.58f)
                },
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = fieldModifier,
        readOnly = readOnly,
        singleLine = singleLine,
        textStyle = textStyle,
        interactionSource = interaction,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(t.accent),
        decorationBox = { inner ->
            Box(contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = t.ink4,
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

/** Generic labeled field (MCP editor). Provider config uses the provider-package variant. */
@Composable
internal fun ProviderLabeledField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = modifier.padding(top = 12.dp)) {
        ProviderFieldLabel(label)
        Box(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun ProviderSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val context = LocalContext.current
    val inCard = LocalProviderFieldInCard.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var visible by remember { mutableStateOf(false) }
    val textStyle = type.meta.copy(
        color = t.ink,
        fontSize = if (inCard) 13.5.sp else 12.sp,
        lineHeight = 19.sp,
    )
    Row(
        modifier = if (inCard) {
            modifier.fillMaxWidth()
        } else {
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(t.surface2)
                .border(1.dp, if (focused) t.accent else t.line.copy(alpha = 0.58f), RoundedCornerShape(14.dp))
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = if (inCard) 0.dp else 4.dp),
            singleLine = !visible,
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            textStyle = textStyle,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(t.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = textStyle, color = t.ink4)
                    }
                    inner()
                }
            },
        )
        ProviderSmallIconButton(
            imageVector = if (visible) Lucide.EyeOff else Lucide.Eye,
            contentDescription = stringResource(
                if (visible) R.string.provider_secret_hide else R.string.provider_secret_show,
            ),
            onClick = { visible = !visible },
            size = if (inCard) 36.dp else 48.dp,
        )
        ProviderSmallIconButton(
            imageVector = Lucide.Copy,
            contentDescription = stringResource(R.string.copy),
            onClick = { context.writeClipboardText(value) },
            size = if (inCard) 36.dp else 48.dp,
        )
    }
}

@Composable
internal fun ProviderSmallIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 48.dp,
) {
    val t = LocalAmberTokens.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (tint == Color.Unspecified) t.ink3 else tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
internal fun ProviderToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalAmberTokens.current
    Box(
        modifier = modifier
            .size(48.dp)
            .pressable(onClick = { onCheckedChange(!checked) }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (checked) t.accent else t.line2)
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}

/** Mono capability flags on one line: first flag accented, trailing ellipsis instead of wrapping. */
@Composable
internal fun ProviderCapFlags(
    flags: List<String>,
    modifier: Modifier = Modifier,
    accentFirst: Boolean = true,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Text(
        text = buildAnnotatedString {
            flags.forEachIndexed { index, flag ->
                if (index > 0) withStyle(SpanStyle(color = t.ink4)) { append(" · ") }
                if (index == 0 && accentFirst) {
                    withStyle(SpanStyle(color = t.accent)) { append(flag) }
                } else {
                    append(flag)
                }
            }
        },
        modifier = modifier,
        style = type.meta.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
        color = t.ink3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Rounded-rect command button (44dp visual, 48dp touch). Default tone is soft accent; [accent]
 * fills it, [warn] / [danger] pick the semantic tints, [plain] is surface + hairline.
 */
@Composable
internal fun ProviderCommandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    imageVector: ImageVector? = null,
    enabled: Boolean = true,
    warn: Boolean = false,
    danger: Boolean = false,
    plain: Boolean = false,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val dangerColor = providerDangerColor()
    val (warnBg, warnFg) = providerToneColors(ProviderTone.Warn)
    val shape = RoundedCornerShape(14.dp)
    val bg = when {
        !enabled -> t.surface2
        accent -> t.accent
        danger -> dangerColor.copy(alpha = 0.12f)
        warn -> warnBg
        plain -> t.surface
        else -> t.accent.copy(alpha = 0.12f)
    }
    val fg = when {
        !enabled -> t.ink4
        accent -> t.accentInk
        danger -> dangerColor
        warn -> warnFg
        plain -> t.ink
        else -> t.accent
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(shape)
                .background(bg)
                .then(if (plain && enabled) Modifier.border(0.5.dp, t.line2, shape) else Modifier)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (imageVector != null) {
                Icon(imageVector, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* soft pill text button ("+ 添加" / "导出" / "+ 全选") */
@Composable
internal fun ProviderGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageVector: ImageVector? = null,
    accent: Boolean = true,
    enabled: Boolean = true,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val bg = when {
        !enabled -> t.surface2
        accent -> t.accent.copy(alpha = 0.10f)
        else -> t.surface2
    }
    val fg = when {
        !enabled -> t.ink4
        accent -> t.accent
        else -> t.ink2
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(CircleShape)
                .background(bg)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (imageVector != null) {
                Icon(
                    imageVector = imageVector,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = type.secondary.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Sheet title row: 18sp bold title, optional trailing action. */
@Composable
internal fun ProviderSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = type.body.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
            color = t.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke(this)
    }
}

/* split cancel/confirm bottom bar */
@Composable
internal fun ProviderSplitBar(
    cancelText: String,
    onCancel: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ProviderRowDivider(inset = 0.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderCommandButton(
                text = cancelText,
                onClick = onCancel,
                plain = true,
                modifier = Modifier.weight(1f),
            )
            ProviderCommandButton(
                text = confirmText,
                onClick = onConfirm,
                accent = true,
                enabled = confirmEnabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun ProviderSheetGrabber() {
    val t = LocalAmberTokens.current
    Box(
        Modifier
            .padding(top = 8.dp, bottom = 10.dp)
            .width(36.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(t.line2)
    )
}

/** Centered empty state used inside a card. */
@Composable
internal fun ProviderEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(t.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = t.ink3, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = type.body.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
            color = t.ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = type.secondary.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = t.ink3,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Collapsible row (advanced groups in the model editor). */
@Composable
internal fun ProviderExpandableRow(
    title: String,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalAmberTokens.current
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "expand")
    Column(Modifier.fillMaxWidth()) {
        ProviderListRow(
            title = title,
            subtitle = summary?.let { { ProviderRowSubtitle(it) } },
            onClick = onToggle,
            trailing = {
                Icon(
                    Lucide.ChevronDown,
                    contentDescription = null,
                    tint = t.ink4,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(rotation),
                )
            },
        )
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                content = content,
            )
        }
    }
}

/** Caption footnote under a card. */
@Composable
internal fun ProviderFootnote(text: String, modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        style = type.secondary.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
        color = t.ink3,
    )
}

/** Flags shown under a model id: type first, then capabilities. */
internal fun Model.providerCapFlags(): List<String> = buildList {
    add(
        when (type) {
            ModelType.CHAT -> "chat"
            ModelType.IMAGE -> "image"
            ModelType.EMBEDDING -> "embed"
        }
    )
    if (Modality.IMAGE in inputModalities) add("vision")
    if (ModelAbility.TOOL in abilities) add("tool")
    if (ModelAbility.REASONING in abilities) add("reason")
    if (type == ModelType.CHAT && Modality.IMAGE in outputModalities) add("image-out")
    contextWindowTokens.toContextLabel().takeIf { it.isNotBlank() }?.let(::add)
}

internal fun String.toProviderMonogram(): String {
    val normalized = trim().lowercase()
    when {
        "deepseek" in normalized -> return "DS"
        "kimi" in normalized || "月之暗面" in normalized -> return "K"
        "mimo" in normalized || "小米" in normalized -> return "米"
        "zhipu" in normalized || "智谱" in normalized || "z.ai" in normalized -> return "智"
    }
    val words = trim().split(Regex("\\s+|-|_")).filter { it.isNotBlank() }
    val first = words.firstOrNull() ?: return "AI"
    val firstChar = first.first()
    // CJK names read best as a single glyph; Latin names keep a single initial (iOS style).
    return if (Character.isLetter(firstChar) && firstChar.code > 0x2E80) {
        firstChar.toString()
    } else {
        firstChar.uppercase()
    }
}

internal fun String.toProviderSlug(): String =
    trim()
        .lowercase()
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"), "-")
        .trim('-')
        .ifBlank { "provider" }

internal fun ProviderSetting.providerSlugLabel(): String = when (this) {
    is ProviderSetting.OpenAI -> if (brand.name.lowercase() == "generic") {
        name.toProviderSlug()
    } else {
        brand.name.lowercase().replace('_', '-')
    }
    is ProviderSetting.Google -> "google-gemini"
    is ProviderSetting.Claude -> "claude"
}

/** Host part of the provider endpoint, for the mono list subtitle. */
internal fun ProviderSetting.providerHostLabel(): String {
    val raw = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
    }
    return raw.substringAfter("://").substringBefore('/').ifBlank { providerSlugLabel() }
}

internal fun Int?.toContextLabel(): String = when (val v = this) {
    null -> ""
    in 1_000_000..Int.MAX_VALUE -> "${v / 1_000_000}M"
    in 1_000..999_999 -> "${v / 1_000}K"
    else -> v.toString()
}

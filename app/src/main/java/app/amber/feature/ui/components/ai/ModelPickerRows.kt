package app.amber.feature.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.ui.components.ui.icons.HeartIcon
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.pages.setting.components.providerSlugLabel
import app.amber.core.utils.formatNumber

private val PickerGroupCorner = 16.dp

/**
 * Draws only the exterior edges of a grouped card segment so adjacent LazyColumn items merge
 * into one continuous card with a single 0.5dp hairline outline and zero doubled inner borders.
 */
private fun Modifier.pickerGroupOutline(
    isFirst: Boolean,
    isLast: Boolean,
    borderColor: Color,
    cornerRadius: Dp = PickerGroupCorner,
): Modifier = drawWithContent {
    drawContent()
    val strokePx = 0.5.dp.toPx()
    val half = strokePx / 2f
    val w = size.width
    val h = size.height
    val r = cornerRadius.toPx().coerceAtMost(minOf(w, h) / 2f)
    when {
        isFirst && isLast -> {
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(half, half),
                size = Size(w - strokePx, h - strokePx),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(strokePx),
            )
        }
        isFirst -> {
            val path = Path().apply {
                moveTo(half, h)
                lineTo(half, half + r)
                arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 180f, 90f, false)
                lineTo(w - half - r, half)
                arcTo(Rect(w - half - 2 * r, half, w - half, half + 2 * r), 270f, 90f, false)
                lineTo(w - half, h)
            }
            drawPath(path, color = borderColor, style = Stroke(strokePx))
        }
        isLast -> {
            val path = Path().apply {
                moveTo(half, 0f)
                lineTo(half, h - half - r)
                arcTo(Rect(half, h - half - 2 * r, half + 2 * r, h - half), 180f, -90f, false)
                lineTo(w - half - r, h - half)
                arcTo(Rect(w - half - 2 * r, h - half - 2 * r, w - half, h - half), 90f, -90f, false)
                lineTo(w - half, 0f)
            }
            drawPath(path, color = borderColor, style = Stroke(strokePx))
        }
        else -> {
            drawLine(borderColor, Offset(half, 0f), Offset(half, h), strokePx)
            drawLine(borderColor, Offset(w - half, 0f), Offset(w - half, h), strokePx)
        }
    }
}

@Composable
internal fun PickerProviderHeader(
    provider: ProviderSetting,
    modelCount: Int,
    active: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    showTopCorners: Boolean,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val shape = RoundedCornerShape(
        topStart = if (showTopCorners) PickerGroupCorner else 0.dp,
        topEnd = if (showTopCorners) PickerGroupCorner else 0.dp,
        bottomStart = if (expanded) 0.dp else PickerGroupCorner,
        bottomEnd = if (expanded) 0.dp else PickerGroupCorner,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .pickerGroupOutline(isFirst = showTopCorners, isLast = !expanded, borderColor = t.line),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = provider.providerSlugLabel(),
                style = type.body.copy(
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (active) t.accent else t.ink2,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = modelCount.toString(),
                style = type.meta.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"),
                color = t.ink4,
                maxLines = 1,
            )
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                tint = t.ink4,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer { rotationZ = if (expanded) 90f else 0f },
            )
        }
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.5.dp)
                    .background(t.line.copy(alpha = 0.72f)),
            )
        }
    }
}

@Composable
internal fun PickerModelRow(
    model: Model,
    providerSetting: ProviderSetting,
    isActive: Boolean,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    isLast: Boolean,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    isFirst: Boolean = false,
) {
    val t = LocalAmberTokens.current
    val shape = RoundedCornerShape(
        topStart = if (isFirst) PickerGroupCorner else 0.dp,
        topEnd = if (isFirst) PickerGroupCorner else 0.dp,
        bottomStart = if (isLast) PickerGroupCorner else 0.dp,
        bottomEnd = if (isLast) PickerGroupCorner else 0.dp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .pickerGroupOutline(isFirst = isFirst, isLast = isLast, borderColor = t.line),
    ) {
        ModelItemRow(
            model = model,
            providerSetting = providerSetting,
            isActive = isActive,
            onSelect = onSelect,
            onDismiss = onDismiss,
            modelFontSize = 14.5.sp,
            contextFontSize = 11.sp,
            verticalPadding = 7.dp,
            startPadding = 16.dp,
            endPadding = 8.dp,
            tail = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (isActive) {
                        Icon(
                            imageVector = Lucide.Check,
                            contentDescription = null,
                            tint = t.accent,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    FavoriteToggleIcon(
                        favorite = favorite,
                        isActive = isActive,
                        onToggle = onToggleFavorite,
                    )
                }
            },
        )
        if (!isLast) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.5.dp)
                    .background(t.line.copy(alpha = 0.65f)),
            )
        }
    }
}

@Composable
internal fun ModelItem(
    model: Model,
    providerSetting: ProviderSetting,
    select: Boolean,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tail: @Composable RowScope.() -> Unit = {},
    dragHandle: @Composable (RowScope.() -> Unit)? = null,
    // V3 active 模型的 thinking-level segment（slot）
    thinkingSegment: (@Composable () -> Unit)? = null,
) {
    val navController = LocalNavController.current
    val interactionSource = remember { MutableInteractionSource() }
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    // Amber Redesign §5: selected = accent text + weight 700, NO fill block / NO
    // border / NO check. §2: row = 0 radius full-bleed. §3: hairline between rows
    // (added by the caller's list; this row itself is borderless).
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { selected = select }
            .combinedClickable(
                enabled = true,
                onLongClick = {
                    onDismiss()
                    navController.navigate(
                        Screen.SettingProviderDetail(providerSetting.id.toString())
                    )
                },
                onClick = { onSelect(model) },
                interactionSource = interactionSource,
                indication = LocalIndication.current,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
        ) {
            // §6: mono model name, accent + 700 only when selected.
            Text(
                text = model.displayName,
                style = LocalAmberType.current.meta.copy(
                    fontSize = 14.sp,
                    fontWeight = if (select) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (select) chatTheme.accent else chatTheme.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // §6: right-aligned mono context number (tabular-nums)
            model.contextWindowTokens?.let { ctx ->
                Text(
                    text = ctx.formatNumber(),
                    style = LocalAmberType.current.meta.copy(
                        fontSize = 11.5.sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = tokens.ink3,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(42.dp),
                )
            }
            tail()
        }
        // ── 第二行：thinking-level segment（仅 active + REASONING 时显示）
        thinkingSegment?.let {
            Box(modifier = Modifier.padding(top = 6.dp)) {
                it()
            }
        }
    }
}

/** V3 grouped 卡内的 model 行 —— ModelItem 但去掉外层 Card 包装，仅渲染内容行。
 *  设计稿：同 provider 多 model 在一张 SubCard 内用 hairline 分隔，所以单行不需要自己的 Card。
 */
@Composable
internal fun ModelItemRow(
    model: Model,
    providerSetting: ProviderSetting,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    isActive: Boolean = false,
    modelFontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    contextFontSize: androidx.compose.ui.unit.TextUnit = 11.5.sp,
    verticalPadding: androidx.compose.ui.unit.Dp = 9.dp,
    endPadding: androidx.compose.ui.unit.Dp = 6.dp,
    startPadding: androidx.compose.ui.unit.Dp = 28.dp,
    contextWidth: androidx.compose.ui.unit.Dp = 42.dp,
    tail: @Composable RowScope.() -> Unit = {},
) {
    val navController = LocalNavController.current
    val interactionSource = remember { MutableInteractionSource() }
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    // Graphite §6.2 row: mono "name … ctx", accent only when selected, no logo/check/badge.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = isActive }
            .combinedClickable(
                enabled = true,
                onLongClick = {
                    onDismiss()
                    navController.navigate(
                        Screen.SettingProviderDetail(providerSetting.id.toString())
                    )
                },
                onClick = { onSelect(model) },
                interactionSource = interactionSource,
                indication = LocalIndication.current,
            )
            .padding(start = startPadding, end = endPadding, top = verticalPadding, bottom = verticalPadding),
    ) {
        Text(
            text = model.displayName,
            style = LocalAmberType.current.body.copy(
                fontSize = modelFontSize,
                lineHeight = 20.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (isActive) tokens.accent else tokens.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        model.contextWindowTokens?.let { ctx ->
            Text(
                text = ctx.formatNumber(),
                style = LocalAmberType.current.meta.copy(
                    fontSize = contextFontSize,
                    lineHeight = 14.sp,
                    fontFeatureSettings = "tnum",
                ),
                color = tokens.ink4,
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.width(contextWidth),
            )
        }
        tail()
    }
}

/** 收藏图标 —— 轻线心形，保持舒适点击热区同时降低列表视觉噪声 */
@Composable
private fun FavoriteToggleIcon(
    favorite: Boolean,
    isActive: Boolean,
    onToggle: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val desc = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (favorite) HeartIcon else Lucide.Heart,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = when {
                favorite -> tokens.accent
                isActive -> tokens.accent.copy(alpha = 0.72f)
                else -> tokens.ink4.copy(alpha = 0.78f)
            },
        )
    }
}

package app.amber.feature.ui.pages.setting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowUpDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import app.amber.agent.R
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.ui.AutoAIIcon
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.workspaceColors
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import sh.calvin.reorderable.ReorderableColumn
import kotlin.uuid.Uuid

private val SearchCardShape = RoundedCornerShape(18.dp)

private data class SearchV2Colors(
    val cardBg: Color,
    val hair: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val accent: Color,
    val modelLogoBg: Color,
)

@Composable
private fun searchV2Colors(): SearchV2Colors {
    val workspace = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    return SearchV2Colors(
        cardBg = workspace.paper,
        hair = workspace.hairline,
        ink = workspace.ink,
        inkSoft = workspace.muted,
        inkFaint = workspace.faint,
        accent = scheme.primary,
        modelLogoBg = workspace.row,
    )
}

@Composable
internal fun SearchHeroCard(
    enabled: Boolean,
    enabledCount: Int,
    serviceCount: Int,
    onCheckedChange: (Boolean) -> Unit,
) {
    val t = searchV2Colors()
    Surface(
        shape = SearchCardShape,
        color = t.cardBg,
        contentColor = t.ink,
        border = BorderStroke(1.dp, t.hair),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_search_agent_search),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = t.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.setting_page_search_agent_search_desc),
                        fontSize = 12.sp,
                        color = t.inkFaint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onCheckedChange,
                    trackColor = t.accent,
                    trackColorUnchecked = t.modelLogoBg,
                    thumbColor = t.cardBg,
                    thumbColorUnchecked = t.inkFaint,
                )
            }
            HairDivider(indent = 0.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(t.accent, CircleShape)
                )
                Text(
                    text = stringResource(
                        R.string.setting_page_search_enabled_services_summary,
                        enabledCount,
                        serviceCount,
                    ),
                    fontSize = 12.sp,
                    color = t.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}

@Composable
internal fun SearchServiceListCard(
    jinaEnabled: Boolean,
    duckDuckGoEnabled: Boolean,
    bingEnabled: Boolean,
    wikipediaEnabled: Boolean,
    hackerNewsEnabled: Boolean,
    googleWebViewFallbackEnabled: Boolean,
    onJinaEnabledChange: (Boolean) -> Unit,
    onDuckDuckGoEnabledChange: (Boolean) -> Unit,
    onBingEnabledChange: (Boolean) -> Unit,
    onWikipediaEnabledChange: (Boolean) -> Unit,
    onHackerNewsEnabledChange: (Boolean) -> Unit,
    onGoogleWebViewFallbackEnabledChange: (Boolean) -> Unit,
    services: List<SearchServiceOptions>,
    enabledServiceIds: List<Uuid>,
    onServiceMove: (Int, Int) -> Unit,
    onServiceEnabledChange: (SearchServiceOptions, Boolean) -> Unit,
    onEditService: (SearchServiceOptions) -> Unit,
) {
    val t = searchV2Colors()
    Surface(
        shape = SearchCardShape,
        color = t.cardBg,
        contentColor = t.ink,
        border = BorderStroke(1.dp, t.hair),
    ) {
        Column(Modifier.fillMaxWidth()) {
            SubGroupLabel(text = stringResource(R.string.setting_page_search_builtin_sources))
            BuiltinSearchRows(
                jinaEnabled = jinaEnabled,
                duckDuckGoEnabled = duckDuckGoEnabled,
                bingEnabled = bingEnabled,
                wikipediaEnabled = wikipediaEnabled,
                hackerNewsEnabled = hackerNewsEnabled,
                googleWebViewFallbackEnabled = googleWebViewFallbackEnabled,
                onJinaEnabledChange = onJinaEnabledChange,
                onDuckDuckGoEnabledChange = onDuckDuckGoEnabledChange,
                onBingEnabledChange = onBingEnabledChange,
                onWikipediaEnabledChange = onWikipediaEnabledChange,
                onHackerNewsEnabledChange = onHackerNewsEnabledChange,
                onGoogleWebViewFallbackEnabledChange = onGoogleWebViewFallbackEnabledChange,
            )

            SubGroupLabel(
                text = stringResource(R.string.setting_page_search_configured_services),
                trailing = stringResource(R.string.setting_page_search_drag_priority),
                modifier = Modifier.padding(top = 8.dp),
            )
            ReorderableColumn(
                list = services,
                onSettle = onServiceMove,
                modifier = Modifier.fillMaxWidth(),
            ) { index, service, isDragging ->
                ReorderableItem {
                    val scale by animateFloatAsState(
                        targetValue = if (isDragging) 0.985f else 1f,
                        label = "searchServiceDragScale",
                    )
                    ConfiguredSearchRow(
                        service = service,
                        rank = index + 1,
                        checked = service.id in enabledServiceIds,
                        showDivider = index < services.lastIndex,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                        dragHandle = {
                            val haptic = LocalHapticFeedback.current
                            DragDots(
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    },
                                )
                            )
                        },
                        onCheckedChange = { onServiceEnabledChange(service, it) },
                        onClick = { onEditService(service) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BuiltinSearchRows(
    jinaEnabled: Boolean,
    duckDuckGoEnabled: Boolean,
    bingEnabled: Boolean,
    wikipediaEnabled: Boolean,
    hackerNewsEnabled: Boolean,
    googleWebViewFallbackEnabled: Boolean,
    onJinaEnabledChange: (Boolean) -> Unit,
    onDuckDuckGoEnabledChange: (Boolean) -> Unit,
    onBingEnabledChange: (Boolean) -> Unit,
    onWikipediaEnabledChange: (Boolean) -> Unit,
    onHackerNewsEnabledChange: (Boolean) -> Unit,
    onGoogleWebViewFallbackEnabledChange: (Boolean) -> Unit,
) {
    BuiltinSearchRow(
        title = stringResource(R.string.setting_page_search_builtin_jina),
        description = stringResource(R.string.setting_page_search_builtin_jina_desc),
        checked = jinaEnabled,
        showDivider = true,
        onCheckedChange = onJinaEnabledChange,
    )
    BuiltinSearchRow(
        title = stringResource(R.string.setting_page_search_builtin_duckduckgo),
        description = stringResource(R.string.setting_page_search_builtin_duckduckgo_desc),
        checked = duckDuckGoEnabled,
        showDivider = true,
        onCheckedChange = onDuckDuckGoEnabledChange,
    )
    BuiltinSearchRow(
        title = stringResource(R.string.setting_page_search_builtin_bing),
        description = stringResource(R.string.setting_page_search_builtin_bing_desc),
        checked = bingEnabled,
        showDivider = true,
        onCheckedChange = onBingEnabledChange,
    )
    BuiltinSearchRow(
        title = stringResource(R.string.setting_page_search_builtin_wikipedia),
        description = stringResource(R.string.setting_page_search_builtin_wikipedia_desc),
        checked = wikipediaEnabled,
        showDivider = true,
        onCheckedChange = onWikipediaEnabledChange,
    )
    BuiltinSearchRow(
        title = stringResource(R.string.setting_page_search_builtin_hackernews),
        description = stringResource(R.string.setting_page_search_builtin_hackernews_desc),
        checked = hackerNewsEnabled,
        showDivider = true,
        onCheckedChange = onHackerNewsEnabledChange,
    )
    BuiltinSearchRow(
        title = stringResource(R.string.setting_page_search_google_webview_fallback),
        description = stringResource(R.string.setting_page_search_google_webview_fallback_desc),
        checked = googleWebViewFallbackEnabled,
        showDivider = false,
        onCheckedChange = onGoogleWebViewFallbackEnabledChange,
    )
}

@Composable
private fun BuiltinSearchRow(
    title: String,
    description: String,
    checked: Boolean,
    showDivider: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ServiceRowV2(
        title = title,
        description = description,
        logoName = title,
        checked = checked,
        showDivider = showDivider,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun ConfiguredSearchRow(
    service: SearchServiceOptions,
    rank: Int,
    checked: Boolean,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val name = SearchServiceOptions.TYPES[service::class] ?: "Search"
    ServiceRowV2(
        title = name,
        description = null,
        logoName = name,
        checked = checked,
        rank = rank,
        configured = true,
        showDivider = showDivider,
        modifier = modifier.clickable(onClick = onClick),
        dragHandle = dragHandle,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun ServiceRowV2(
    title: String,
    description: String?,
    logoName: String,
    checked: Boolean,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
    configured: Boolean = false,
    rank: Int? = null,
    dragHandle: @Composable (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val t = searchV2Colors()
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dragHandle != null) {
                dragHandle()
            }
            ServiceLogo(
                name = logoName,
                configured = configured,
                rank = rank,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = t.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description != null) {
                    Text(
                        text = description,
                        fontSize = 11.5.sp,
                        color = t.inkFaint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                trackColor = t.accent,
                trackColorUnchecked = t.modelLogoBg,
                thumbColor = t.cardBg,
                thumbColorUnchecked = t.inkFaint,
            )
            if (configured) {
                Icon(
                    imageVector = Lucide.ChevronRight,
                    contentDescription = null,
                    tint = t.inkFaint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (showDivider) {
            HairDivider(indent = if (configured) 84.dp else 60.dp)
        }
    }
}

@Composable
private fun ServiceLogo(
    name: String,
    configured: Boolean,
    rank: Int?,
) {
    val t = searchV2Colors()
    Box(Modifier.size(36.dp)) {
        CompositionLocalProvider(LocalContentColor provides t.inkSoft) {
            AutoAIIcon(
                name = name,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
                color = t.modelLogoBg,
            )
        }
        if (rank != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp),
                shape = CircleShape,
                color = t.cardBg,
                border = BorderStroke(1.dp, t.hair),
                contentColor = t.inkSoft,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = rank.toString(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    )
                }
            }
        }
    }
}

@Composable
private fun DragDots(modifier: Modifier = Modifier) {
    val t = searchV2Colors()
    Canvas(modifier.size(width = 16.dp, height = 26.dp)) {
        val radius = 1.5.dp.toPx()
        val gapX = 6.dp.toPx()
        val gapY = 6.dp.toPx()
        val startX = (size.width - gapX) / 2f
        val startY = (size.height - gapY * 2f) / 2f
        repeat(3) { row ->
            repeat(2) { col ->
                drawCircle(
                    color = t.inkFaint,
                    radius = radius,
                    center = androidx.compose.ui.geometry.Offset(
                        x = startX + col * gapX,
                        y = startY + row * gapY,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SubGroupLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val t = searchV2Colors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Medium,
            color = t.inkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Icon(
                imageVector = Lucide.ArrowUpDown,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(12.dp),
                tint = t.inkFaint,
            )
            Text(
                text = trailing,
                fontSize = 11.sp,
                color = t.inkFaint,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun SearchCommonOptionsCard(
    settings: Settings,
    onUpdate: (SearchCommonOptions) -> Unit,
) {
    val t = searchV2Colors()
    Surface(
        shape = SearchCardShape,
        color = t.cardBg,
        contentColor = t.ink,
        border = BorderStroke(1.dp, t.hair),
    ) {
        Column(Modifier.fillMaxWidth()) {
            SubGroupLabel(text = stringResource(R.string.setting_page_search_common_options))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_search_result_size),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = t.ink,
                    )
                    Text(
                        text = stringResource(R.string.setting_page_search_result_size_desc),
                        fontSize = 11.5.sp,
                        color = t.inkFaint,
                    )
                }
                SearchResultStepper(
                    value = settings.searchCommonOptions.resultSize,
                    onValueChange = { value ->
                        onUpdate(settings.searchCommonOptions.copy(resultSize = value))
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchResultStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    val t = searchV2Colors()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = t.modelLogoBg,
        contentColor = t.ink,
        border = BorderStroke(1.dp, t.hair),
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton(
                icon = Lucide.Minus,
                enabled = value > 1,
                onClick = { onValueChange((value - 1).coerceIn(1, 50)) },
            )
            StepperNumberField(
                value = value,
                onValueChange = onValueChange,
            )
            StepperButton(
                icon = Lucide.Plus,
                enabled = value < 50,
                onClick = { onValueChange((value + 1).coerceIn(1, 50)) },
            )
        }
    }
}

@Composable
private fun StepperNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    val t = searchV2Colors()
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value.toString()) }

    LaunchedEffect(value, focused) {
        if (!focused) {
            text = value.toString()
        }
    }

    fun updateFromText(raw: String) {
        val digits = raw.filter { it.isDigit() }.take(3)
        text = digits
        val parsed = digits.toIntOrNull() ?: return
        val coerced = parsed.coerceIn(1, 50)
        if (coerced != parsed) {
            text = coerced.toString()
        }
        onValueChange(coerced)
    }

    BasicTextField(
        value = text,
        onValueChange = ::updateFromText,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelMedium.copy(
            color = t.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum",
            textAlign = TextAlign.Center,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { focusManager.clearFocus() },
        ),
        cursorBrush = SolidColor(t.accent),
        modifier = Modifier
            .width(44.dp)
            .height(30.dp)
            .onFocusChanged { state ->
                val wasFocused = focused
                focused = state.isFocused
                if (wasFocused && !state.isFocused) {
                    if (text.isBlank()) {
                        text = value.toString()
                    } else {
                        updateFromText(text)
                    }
                }
            },
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                innerTextField()
            }
        },
    )
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val t = searchV2Colors()
    Box(
        modifier = Modifier
            .size(30.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) t.inkSoft else t.inkFaint.copy(alpha = 0.45f),
        )
    }
}

@Composable
internal fun SearchRecommendationFooter() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SubGroupLabel(
            text = stringResource(R.string.setting_page_search_recommend_title),
            modifier = Modifier.padding(horizontal = 0.dp),
        )
        RecommendationRow(
            title = stringResource(R.string.setting_page_search_recommend_zero_cost),
            detail = stringResource(R.string.setting_page_search_recommend_zero_cost_detail),
        )
        RecommendationRow(
            title = stringResource(R.string.setting_page_search_recommend_google),
            detail = stringResource(R.string.setting_page_search_recommend_google_detail),
        )
        RecommendationRow(
            title = stringResource(R.string.setting_page_search_recommend_quality),
            detail = stringResource(R.string.setting_page_search_recommend_quality_detail),
        )
    }
}

@Composable
private fun RecommendationRow(
    title: String,
    detail: String,
) {
    val t = searchV2Colors()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        contentColor = t.ink,
        border = BorderStroke(1.dp, t.hair),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = t.accent,
                maxLines = 1,
            )
            Text(
                text = detail,
                modifier = Modifier.weight(1f),
                fontSize = 12.5.sp,
                color = t.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HairDivider(indent: Dp) {
    val t = searchV2Colors()
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .height(1.dp)
            .background(t.hair)
    )
}

internal data class SearchServiceEditorTarget(
    val index: Int?,
    val service: SearchServiceOptions,
)

internal fun resolveSelectedSearchIndex(
    services: List<SearchServiceOptions>,
    selectedServiceId: Uuid?,
): Int {
    if (services.isEmpty()) return 0
    return selectedServiceId
        ?.let { id -> services.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
        ?: 0
}

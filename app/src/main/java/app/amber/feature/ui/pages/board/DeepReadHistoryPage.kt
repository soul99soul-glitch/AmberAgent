package app.amber.feature.ui.pages.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.border
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Rect
import app.amber.feature.ui.components.ds.pressable
import kotlin.math.roundToInt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.LocalAmberTokens
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.amber.agent.Screen
import app.amber.agent.R
import app.amber.agent.StandaloneSurfaces
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.hotlist.DeepReadHistoryItem
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.board.hotlist.deepread.DeepReadMoments
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ui.WorkspaceSearchField
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.pages.novel.animationsEnabled
import app.amber.core.utils.plus
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class DeepReadHistoryFilter { ALL, ACTIVE, EXPIRED, PINNED }

private fun DeepReadHistoryItem.matches(filter: DeepReadHistoryFilter): Boolean = when (filter) {
    DeepReadHistoryFilter.ALL -> true
    DeepReadHistoryFilter.ACTIVE -> !expired
    DeepReadHistoryFilter.EXPIRED -> expired
    DeepReadHistoryFilter.PINNED -> pinned
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepReadHistoryPage(
    repository: HotListRepository = koinInject(),
) {
    val navController = LocalNavController.current
    val history by repository.observeDeepReadHistory().collectAsStateWithLifecycle(initialValue = emptyList())
    val colors = workspaceColors()
    val tokens = LocalAmberTokens.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf(DeepReadHistoryFilter.ALL) }
    var rowsShown by remember { mutableStateOf(false) }

    val filtered = history.filter { item ->
        item.matches(statusFilter) && (
            query.isBlank() ||
                item.title.contains(query, ignoreCase = true) ||
                item.output?.summary?.contains(query, ignoreCase = true) == true
            )
    }
    LaunchedEffect(filtered.isNotEmpty()) { if (filtered.isNotEmpty()) rowsShown = true }

    Scaffold(
        modifier = Modifier
            .amberCanvas()
            .let {
                if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
                    it.deepReadPaper(night = DeepReadMoments.isNight(), tokens = tokens)
                } else it
            },
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.deep_read_history_title),
                titleStyle = deepReadEditorialSerif?.let { serif ->
                    LocalAmberType.current.screenTitle.copy(fontFamily = serif, fontWeight = FontWeight.Bold)
                },
                navigationIcon = { BackButton() },
            )
        },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { innerPadding ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.deep_read_history_empty), color = colors.muted)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("//", style = LocalAmberType.current.eyebrow, color = tokens.accent)
                    Text(stringResource(R.string.deep_read_history_title), style = LocalAmberType.current.eyebrow, color = tokens.ink2)
                    Text(filtered.size.toString(), style = LocalAmberType.current.eyebrow, color = tokens.ink3)
                    HorizontalDivider(Modifier.weight(1f), color = tokens.line)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceSearchField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = stringResource(R.string.deep_read_history_search_hint),
                    )
                    HistoryFilterBar(
                        selected = statusFilter,
                        onSelect = { statusFilter = it },
                    )
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.deep_read_history_no_match), color = colors.muted)
                    }
                }
            }
            itemsIndexed(filtered, key = { _, item -> item.topicId }) { index, item ->
                Column(Modifier.deepReadEntrance(index, rowsShown)) {
                    DeepReadHistoryRow(
                        item = item,
                        onClick = {
                            repository.rememberDeepReadHistoryPreview(item)
                            navController.navigate(
                                Screen.DeepRead(
                                    topicId = item.topicId,
                                    title = item.title,
                                    sourceUrl = item.sourceUrl,
                                    fromHistory = true,
                                )
                            )
                        },
                        onTogglePin = { pinned ->
                            scope.launch { repository.setDeepReadPinned(item.topicId, pinned) }
                        },
                    )
                    if (index != filtered.lastIndex) {
                        HorizontalDivider(color = tokens.line)
                    }
                }
            }
        }
    }
}

/**
 * Filter chips with one shared selection capsule that springs between them —
 * the counterpart of iOS's `matchedGeometryEffect` chip capsule, plus the
 * `.selection` haptic.
 */
@Composable
private fun HistoryFilterBar(
    selected: DeepReadHistoryFilter,
    onSelect: (DeepReadHistoryFilter) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val labels = DeepReadHistoryFilter.entries.associateWith {
        when (it) {
            DeepReadHistoryFilter.ALL -> stringResource(R.string.deep_read_filter_all)
            DeepReadHistoryFilter.ACTIVE -> stringResource(R.string.deep_read_active)
            DeepReadHistoryFilter.EXPIRED -> stringResource(R.string.deep_read_expired)
            DeepReadHistoryFilter.PINNED -> stringResource(R.string.deep_read_pinned)
        }
    }
    val bounds = remember { mutableStateMapOf<DeepReadHistoryFilter, Rect>() }
    val slot = bounds[selected]
    val capsuleX = remember { androidx.compose.animation.core.Animatable(0f) }
    val capsuleWidth = remember { androidx.compose.animation.core.Animatable(0f) }
    var capsulePlaced by remember { mutableStateOf(false) }
    val motion = animationsEnabled()
    LaunchedEffect(slot) {
        val s = slot ?: return@LaunchedEffect
        if (!capsulePlaced || !motion) {
            capsuleX.snapTo(s.left)
            capsuleWidth.snapTo(s.width)
            capsulePlaced = true
        } else {
            val spec = spring<Float>(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)
            launch { capsuleX.animateTo(s.left, spec) }
            capsuleWidth.animateTo(s.width, spec)
        }
    }
    // The capsule lives inside the scrollable box so it tracks the chips if the
    // row overflows and scrolls horizontally.
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        if (slot != null) {
            Box(
                Modifier
                    .absoluteOffset { IntOffset(capsuleX.value.roundToInt(), slot.top.roundToInt()) }
                    .width(with(density) { capsuleWidth.value.toDp() })
                    .height(with(density) { slot.height.toDp() })
                    .border(1.dp, tokens.accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .background(tokens.accent.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeepReadHistoryFilter.entries.forEach { option ->
                val isSelected = selected == option
                Box(
                    Modifier
                        .onGloballyPositioned {
                            val rect = it.boundsInParent()
                            if (bounds[option] != rect) bounds[option] = rect
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.Tab) {
                            if (!isSelected) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(option)
                            }
                        }
                        .heightIn(min = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        labels.getValue(option),
                        modifier = Modifier.padding(horizontal = 12.dp),
                        style = LocalAmberType.current.meta,
                        color = if (isSelected) tokens.accent else tokens.ink3,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeepReadHistoryRow(
    item: DeepReadHistoryItem,
    onClick: () -> Unit,
    onTogglePin: (Boolean) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
                    it.pressableDeepRead(onClick)
                } else {
                    it.pressable(onClick)
                }
            }
            .heightIn(min = 64.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(22.dp)
                .background(if (item.pinned) tokens.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(1.dp)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title,
                style = LocalAmberType.current.sessionTitle.copy(
                    fontFamily = deepReadEditorialSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                ),
                color = tokens.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatHistoryTime(item.updatedAt),
                    style = LocalAmberType.current.meta,
                    color = tokens.ink3,
                )
                // iOS history rows tag the generation template; custom ids fall
                // back to nothing since the catalog cannot name them.
                item.templateId
                    ?.takeIf { !it.startsWith(DeepReadTemplateIds.CUSTOM_PREFIX) }
                    ?.let { id ->
                        Icon(
                            DeepReadTemplateCatalog.icon(id),
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = tokens.ink3,
                        )
                        Text(
                            DeepReadTemplateCatalog.name(id),
                            style = LocalAmberType.current.meta,
                            color = tokens.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }
        }
        Column(
            modifier = Modifier.width(74.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            DeepReadHistoryStatus(
                text = when {
                    item.pinned -> stringResource(R.string.deep_read_pinned)
                    item.expired -> stringResource(R.string.deep_read_expired)
                    else -> stringResource(R.string.deep_read_active)
                },
                tone = when {
                    item.pinned -> HistoryTone.Accent
                    item.expired -> HistoryTone.Warning
                    else -> HistoryTone.Success
                },
            )
            TextButton(
                onClick = { onTogglePin(!item.pinned) },
                modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp),
            ) {
                Text(
                    if (item.pinned) stringResource(R.string.deep_read_unfavorite)
                    else stringResource(R.string.deep_read_favorite),
                    style = LocalAmberType.current.secondary,
                    color = tokens.ink2,
                )
            }
        }
    }
}

private enum class HistoryTone { Accent, Success, Warning }

@Composable
private fun DeepReadHistoryStatus(text: String, tone: HistoryTone) {
    val tokens = LocalAmberTokens.current
    val base = when (tone) {
        HistoryTone.Accent -> tokens.accent
        HistoryTone.Success -> tokens.signal
        HistoryTone.Warning -> MaterialTheme.colorScheme.tertiary
    }
    Surface(
        modifier = Modifier.heightIn(min = 22.dp),
        shape = CircleShape,
        color = base.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(1.dp, base.copy(alpha = 0.38f)),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).background(base, CircleShape))
            Text(text, style = LocalAmberType.current.tinyTag, color = base, maxLines = 1)
        }
    }
}

@Composable
private fun formatHistoryTime(timestamp: Long): String {
    if (timestamp <= 0L) return stringResource(R.string.board_time_unknown)
    return Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

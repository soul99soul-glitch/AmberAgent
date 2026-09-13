package app.amber.feature.ui.pages.stats

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChartColumn
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Rocket
import com.composables.icons.lucide.Zap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.theme.AmberMono
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.plus
import app.amber.core.utils.appLocale
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun StatsPage(vm: StatsVM = koinViewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_page_title), style = type.screenTitle, color = t.ink) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = t.bg,
                    scrolledContainerColor = t.bg,
                    titleContentColor = t.ink,
                    navigationIconContentColor = t.ink2,
                ),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        if (stats.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    HeatmapCard(
                        conversationsPerDay = stats.conversationsPerDay,
                    )
                }
                item {
                    StatsGrid(
                        stats = stats,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeatmapCard(conversationsPerDay: Map<LocalDate, Int>, modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(stringResource(R.string.stats_page_heatmap_title))

            ChatHeatmap(conversationsPerDay = conversationsPerDay)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_page_heatmap_less),
                    style = type.meta,
                    color = t.ink3,
                )
                Spacer(Modifier.width(2.dp))
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                    HeatmapCell(alpha = alpha, sizeDp = 10)
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.stats_page_heatmap_more),
                    style = type.meta,
                    color = t.ink3,
                )
            }
        }
    }
}

@Composable
private fun ChatHeatmap(conversationsPerDay: Map<LocalDate, Int>) {
    val appLocale = LocalContext.current.appLocale()
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)

    val numWeeks = 53
    val activeCounts = conversationsPerDay.values.filter { it > 0 }.sorted()
    val q1 = activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1 }
    val q2 = activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2 }
    val q3 = activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3 }
    val cellSize = 11.dp
    val cellSpacing = 2.dp
    // Month label row height
    val monthLabelHeight = 14.dp

    // Day-of-week labels (only Mon/Wed/Fri to save space, Sun=0)
    val dowLabels = listOf(
        "",
        stringResource(R.string.stats_page_dow_mon),
        "",
        stringResource(R.string.stats_page_dow_wed),
        "",
        stringResource(R.string.stats_page_dow_fri),
        ""
    )

    // Shared scroll state so month labels + grid scroll together
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Fixed left column: spacer for month label row + DOW labels
        Column(
            modifier = Modifier.width(12.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = type.meta.copy(fontSize = (type.meta.fontSize.value * 0.7f).sp),
                            color = t.ink3,
                        )
                    }
                }
            }
        }

        // Scrollable area: month labels + heatmap grid share one scroll state
        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Month labels row
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            // Graphite §3: the year axis label is a number (machine-fact) → MONO;
                            // month abbreviations are human-readable text → stay sans.
                            val isYearLabel = labelDate.monthValue == 1
                            Text(
                                text = if (isYearLabel) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, appLocale)
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                style = if (isYearLabel) {
                                    type.meta.copy(
                                        fontFamily = AmberMono,
                                        fontFeatureSettings = "tnum, zero",
                                    )
                                } else {
                                    type.meta
                                },
                                fontSize = (type.meta.fontSize.value * 0.75f).sp,
                                color = t.ink3,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // Heatmap grid — single Canvas instead of 53*7 = 371 individual Box composables.
            // Originally this caused the navigation enter animation to drop frames (the page
            // had to measure ~370 layout nodes on first composition) and the
            // rememberScrollState(initial=Int.MAX_VALUE) couldn't settle in time, making the
            // grid look like it briefly scrolled from the left before snapping right.
            HeatmapGridCanvas(
                conversationsPerDay = conversationsPerDay,
                startSunday = startSunday,
                today = today,
                numWeeks = numWeeks,
                q1 = q1,
                q2 = q2,
                q3 = q3,
                cellSize = cellSize,
                cellSpacing = cellSpacing,
            )
        }
    }
}

/**
 * Draw all 371 heatmap cells in a single Canvas pass. Compose's per-cell layout cost was the
 * dominant overhead during page entry; collapsing it to one DrawScope keeps the navigation
 * animation smooth and lets the parent horizontalScroll's initial=Int.MAX_VALUE settle on the
 * first frame (only one child to measure now).
 */
@Composable
private fun HeatmapGridCanvas(
    conversationsPerDay: Map<LocalDate, Int>,
    startSunday: LocalDate,
    today: LocalDate,
    numWeeks: Int,
    q1: Int,
    q2: Int,
    q3: Int,
    cellSize: androidx.compose.ui.unit.Dp,
    cellSpacing: androidx.compose.ui.unit.Dp,
) {
    val tokens = LocalAmberTokens.current
    val futureColor = tokens.surface2.copy(alpha = 0.45f)
    val emptyColor = tokens.surface2
    val baseColor = tokens.accent
    val density = LocalDensity.current
    val cellPx = with(density) { cellSize.toPx() }
    val spacingPx = with(density) { cellSpacing.toPx() }
    val cornerPx = with(density) { (cellSize / 4).toPx() }
    val totalWidth = cellSize * numWeeks + cellSpacing * (numWeeks - 1)
    val totalHeight = cellSize * 7 + cellSpacing * 6

    Canvas(
        modifier = Modifier.size(width = totalWidth, height = totalHeight),
    ) {
        val cellSizeOffset = Size(cellPx, cellPx)
        val corner = CornerRadius(cornerPx)
        for (weekIdx in 0 until numWeeks) {
            val x = weekIdx * (cellPx + spacingPx)
            for (dow in 0..6) {
                val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                val isFuture = date.isAfter(today)
                val count = if (isFuture) 0 else (conversationsPerDay[date] ?: 0)
                val color: Color = when {
                    isFuture -> futureColor
                    count == 0 -> emptyColor
                    count <= q1 -> baseColor.copy(alpha = 0.25f)
                    count <= q2 -> baseColor.copy(alpha = 0.5f)
                    count <= q3 -> baseColor.copy(alpha = 0.75f)
                    else -> baseColor
                }
                val y = dow * (cellPx + spacingPx)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = cellSizeOffset,
                    cornerRadius = corner,
                )
            }
        }
    }
}

@Composable
private fun HeatmapCell(alpha: Float, sizeDp: Int) {
    val tokens = LocalAmberTokens.current
    val color = when {
        alpha < 0f -> tokens.surface2.copy(alpha = 0.45f) // future
        alpha == 0f -> tokens.surface2
        else -> tokens.accent.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color)
    )
}

@Composable
private fun StatsGrid(stats: AppStats, modifier: Modifier = Modifier) {
    val appLocale = LocalContext.current.appLocale()
    AmberCard(modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Lucide.ChartColumn,
                    label = stringResource(R.string.stats_page_total_conversations),
                    value = formatCount(stats.totalConversations.toLong(), appLocale),
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Lucide.MessageCircle,
                    label = stringResource(R.string.stats_page_total_messages),
                    value = formatCount(stats.totalMessages.toLong(), appLocale),
                )
            }
            Hairline()
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Lucide.Cpu,
                    label = stringResource(R.string.stats_page_input_tokens),
                    value = formatTokens(stats.totalPromptTokens, appLocale),
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Lucide.Cpu,
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokens(stats.totalCompletionTokens, appLocale),
                )
            }
            if (stats.totalCachedTokens > 0) {
                Hairline()
                StatCard(
                    modifier = Modifier.fillMaxWidth(),
                    icon = Lucide.Zap,
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = formatTokens(stats.totalCachedTokens, appLocale),
                )
            }
            Hairline()
            StatCard(
                modifier = Modifier.fillMaxWidth(),
                icon = Lucide.Rocket,
                label = stringResource(R.string.stats_page_launch_count),
                value = formatCount(stats.launchCount.toLong(), appLocale),
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                .background(t.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = t.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        // Graphite §3: the stat number is a machine-fact → MONO with tabular + slashed-zero.
        // Keep headline size/weight so the visual hierarchy is unchanged; only the font swaps.
        Text(
            text = value,
            style = type.screenTitle.copy(
                fontSize = 20.sp,
                fontFamily = AmberMono,
                fontFeatureSettings = "tnum, zero",
            ),
            color = t.ink,
        )
        // Human label stays sans.
        Text(
            text = label,
            style = type.secondary,
            color = t.ink3,
        )
    }
}

private fun formatCount(count: Long, locale: Locale): String = when {
    count >= 1_000_000 -> "%.1fM".format(locale, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(locale, count / 1_000.0)
    else -> count.toString()
}

private fun formatTokens(count: Long, locale: Locale): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(locale, count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(locale, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(locale, count / 1_000.0)
    else -> count.toString()
}

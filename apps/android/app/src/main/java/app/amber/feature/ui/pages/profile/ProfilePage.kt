package app.amber.feature.ui.pages.profile

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.theme.JetBrainsMonoFamily
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.pages.stats.StatsVM
import app.amber.feature.ui.pages.sessionhome.SessionHomeVM
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import org.koin.androidx.compose.koinViewModel

/**
 * 个人资料 / 统计页：头像 + 昵称 + 徽章、五项统计卡、聊天活动热力图。
 * 数据复用 [StatsVM]（累计 token、每日活跃），连续天数由每日活跃推导。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePage(
    vm: StatsVM = koinViewModel(),
    sessionHomeVm: SessionHomeVM = koinViewModel(),
) {
    val tokens = LocalAmberTokens.current
    val settings = LocalSettings.current
    val stats by vm.stats.collectAsStateWithLifecycle()

    val nickname = settings.displaySetting.userNickname.ifBlank { "Amber 用户" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个人资料", fontWeight = FontWeight.Bold) },
                navigationIcon = { BackButton() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = tokens.bg),
            )
        },
        containerColor = tokens.bg,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(24.dp))

            // 头像 + 昵称 + 徽章
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                UIAvatar(
                    name = nickname,
                    value = settings.displaySetting.userAvatar,
                    size = 96.dp,
                    containerColor = tokens.accent,
                    editContainerColor = tokens.surface,
                    editContentColor = tokens.accent,
                    showEditBadge = false,
                    onUpdate = { newAvatar ->
                        sessionHomeVm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(userAvatar = newAvatar)
                            )
                        )
                    },
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = nickname,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "@${nickname.lowercase().replace(" ", "")}",
                        fontSize = 13.sp,
                        color = tokens.ink3,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.surface2)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(text = "Amber", fontSize = 11.sp, color = tokens.ink2)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // 五项统计卡
            ProfileStatsCard(stats = stats)

            Spacer(Modifier.height(32.dp))

            // 聊天活动热力图
            Text(
                text = "聊天活动",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.ink,
            )
            Spacer(Modifier.height(12.dp))
            // 加载中先占位，避免首帧闪现全空热力图
            if (stats.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.surface),
                )
            } else {
                ActivityHeatmap(days = stats.conversationsPerDay)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ProfileStatsCard(stats: app.amber.feature.ui.pages.stats.AppStats) {
    val tokens = LocalAmberTokens.current
    val totalTokens = stats.totalPromptTokens + stats.totalCompletionTokens
    val current = currentStreak(stats.conversationsPerDay)
    val longest = longestStreak(stats.conversationsPerDay)

    val items = listOf(
        formatCount(totalTokens) to "Token 数",
        formatInt(stats.totalMessages) to "消息",
        formatInt(stats.totalConversations) to "会话",
        "${current} 天" to "连续",
        "${longest} 天" to "近一年最长",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.surface)
            .padding(vertical = 16.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, (value, label) ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(40.dp)
                        .background(tokens.line),
                )
            }
            // 固定列宽，不再 weight 等分挤压；窄屏可左右滑动
            Column(
                modifier = Modifier.width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                    fontFamily = JetBrainsMonoFamily,
                    maxLines = 1,
                )
                Text(
                    text = label,
                    fontSize = 10.5.sp,
                    color = tokens.ink3,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * GitHub 风格贡献热力图：53 周 × 7 天，颜色深浅 = 当日活跃消息数。
 * 格子是固定正方形（边长由高度决定），整图宽度 = 53 × (cell+gap)，
 * 外层横向滚动，不再把 53 周压缩进屏宽导致格子变条形。
 */
@Composable
private fun ActivityHeatmap(days: Map<LocalDate, Int>) {
    val tokens = LocalAmberTokens.current

    val today = LocalDate.now()
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).minusWeeks(52)
    val weeks = 53

    val maxCount = max(1, days.values.maxOrNull() ?: 1)
    val emptyColor = tokens.line2.toArgb() // 无活动日用 line2，与 bg 拉开对比
    val accentArgb = tokens.accent.toArgb()
    val labelColor = tokens.ink3.toArgb()

    // 固定高度：7 行格子 + 底部标签；cell 边长由此推出，保证正方形
    val cellDp = 13.dp
    val gapDp = 3.dp
    val labelHeightDp = 18.dp
    val gridHeightDp = cellDp * 7 + gapDp * 6
    val totalHeightDp = gridHeightDp + labelHeightDp
    val totalWidthDp = (cellDp + gapDp) * weeks

    val hScroll = rememberScrollState()
    // 进入时定位到最右端（最近日期），往左滑才看历史
    LaunchedEffect(hScroll.maxValue) {
        if (hScroll.maxValue > 0) {
            hScroll.scrollTo(hScroll.maxValue)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(hScroll),
    ) {
        Box(
            modifier = Modifier
                .width(totalWidthDp)
                .height(totalHeightDp),
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val cellPx = cellDp.toPx()
                val gapPx = gapDp.toPx()
                val stepPx = cellPx + gapPx
                val labelY = gridHeightDp.toPx() + 12.dp.toPx()

                drawIntoCanvas { canvas ->
                    val paint = Paint().apply { isAntiAlias = true }
                    val textPaint = Paint().apply {
                        isAntiAlias = true
                        color = labelColor
                        textSize = 10.sp.toPx()
                        typeface = Typeface.DEFAULT
                    }
                    val cornerPx = 2.dp.toPx()

                    var lastMonth = -1
                    for (w in 0 until weeks) {
                        val weekStart = start.plusDays((w * 7).toLong())
                        val midDate = weekStart.plusDays(3)
                        if (midDate.monthValue != lastMonth && !midDate.isAfter(today)) {
                            lastMonth = midDate.monthValue
                            canvas.nativeCanvas.drawText(
                                "${midDate.monthValue}月",
                                w * stepPx,
                                labelY,
                                textPaint,
                            )
                        }
                        for (d in 0 until 7) {
                            val date = weekStart.plusDays(d.toLong())
                            val isFuture = date.isAfter(today)
                            val count = if (isFuture) 0 else (days[date] ?: 0)
                            paint.color = when {
                                isFuture -> tokens.line.toArgb()
                                count <= 0 -> emptyColor
                                else -> {
                                    val ratio = (count.toFloat() / maxCount).coerceIn(0.15f, 1f)
                                    blend(emptyColor, accentArgb, 0.25f + 0.75f * ratio)
                                }
                            }
                            val x = w * stepPx
                            val y = d * stepPx
                            canvas.nativeCanvas.drawRoundRect(
                                x, y, x + cellPx, y + cellPx,
                                cornerPx, cornerPx, paint,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun blend(c1: Int, c2: Int, t: Float): Int {
    val a = (c1 ushr 24) and 0xFF
    val r1 = (c1 ushr 16) and 0xFF
    val g1 = (c1 ushr 8) and 0xFF
    val b1 = c1 and 0xFF
    val r2 = (c2 ushr 16) and 0xFF
    val g2 = (c2 ushr 8) and 0xFF
    val b2 = c2 and 0xFF
    val r = (r1 + (r2 - r1) * t).toInt()
    val g = (g1 + (g2 - g1) * t).toInt()
    val b = (b1 + (b2 - b1) * t).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun currentStreak(days: Map<LocalDate, Int>): Int {
    var d = LocalDate.now()
    if ((days[d] ?: 0) == 0) d = d.minusDays(1)
    var s = 0
    while ((days[d] ?: 0) > 0) {
        s++
        d = d.minusDays(1)
    }
    return s
}

private fun longestStreak(days: Map<LocalDate, Int>): Int {
    if (days.isEmpty()) return 0
    var best = 0
    var cur = 0
    var d = days.keys.minOrNull()!!
    val end = LocalDate.now()
    while (!d.isAfter(end)) {
        if ((days[d] ?: 0) > 0) {
            cur++
            best = max(best, cur)
        } else {
            cur = 0
        }
        d = d.plusDays(1)
    }
    return best
}

private fun formatInt(n: Int): String = if (n >= 10000) formatCount(n.toLong()) else "$n"

private fun formatCount(n: Long): String = when {
    n >= 100_000_000 -> "${String.format("%.1f", n / 100_000_000.0)}亿"
    n >= 10_000 -> "${String.format("%.1f", n / 10_000.0)}万"
    else -> "$n"
}

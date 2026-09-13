package app.amber.feature.ui.pages.profile

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.pages.stats.StatsVM
import app.amber.feature.ui.pages.sessionhome.SessionHomeVM
import app.amber.core.utils.appLocale
import app.amber.agent.R
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import org.koin.androidx.compose.koinViewModel

/**
 * 个人资料 / 统计页：头像 + 可编辑昵称、五项统计卡、聊天活动热力图。
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

    val defaultNickname = stringResource(R.string.profile_default_nickname)
    val storedNickname = settings.displaySetting.userNickname
    val nickname = storedNickname.ifBlank { defaultNickname }
    var editingNickname by rememberSaveable { mutableStateOf(false) }
    var nicknameDraft by rememberSaveable(storedNickname) { mutableStateOf(storedNickname) }
    val nicknameFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun saveNickname() {
        val normalized = nicknameDraft.trim()
        if (normalized != storedNickname) {
            sessionHomeVm.updateSettings(
                settings.copy(
                    displaySetting = settings.displaySetting.copy(userNickname = normalized),
                )
            )
        }
        editingNickname = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    fun cancelNicknameEdit() {
        nicknameDraft = storedNickname
        editingNickname = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(editingNickname) {
        if (editingNickname) nicknameFocusRequester.requestFocus()
    }

    Scaffold(
        modifier = Modifier.amberCanvas(),
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.profile_title),
                navigationIcon = { BackButton() },
            )
        },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            // 头像 + 可编辑昵称
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    tokens.accent.copy(alpha = 0.54f).compositeOver(tokens.surface),
                                    tokens.accent.copy(alpha = 0.26f).compositeOver(tokens.surface),
                                ),
                            ),
                        )
                        .border(1.dp, tokens.line2, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides tokens.ink) {
                        UIAvatar(
                            name = nickname,
                            value = settings.displaySetting.userAvatar,
                            modifier = Modifier.fillMaxSize(),
                            size = 96.dp,
                            containerColor = Color.Transparent,
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
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (editingNickname) {
                    BasicTextField(
                        value = nicknameDraft,
                        onValueChange = { nicknameDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 42.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(tokens.surface)
                            .border(1.dp, tokens.accent.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                            .focusRequester(nicknameFocusRequester)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        singleLine = true,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(tokens.accent),
                        textStyle = LocalAmberType.current.screenTitle.copy(
                            fontSize = 20.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.ink,
                            textAlign = TextAlign.Center,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveNickname() }),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(role = Role.Button) { editingNickname = true }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = nickname,
                            modifier = Modifier.weight(1f, fill = false),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = Lucide.Pencil,
                            contentDescription = stringResource(R.string.edit),
                            modifier = Modifier.size(15.dp),
                            tint = tokens.ink3,
                        )
                    }
                }
                if (editingNickname) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { cancelNicknameEdit() }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = { saveNickname() }) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // 五项统计卡
            ProfileStatsCard(stats = stats)

            Spacer(Modifier.height(28.dp))

            // 聊天活动热力图
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(text = stringResource(R.string.profile_chat_activity))
                Spacer(Modifier.weight(1f))
                Text(
                    text = "53 W",
                    style = LocalAmberType.current.meta,
                    color = tokens.ink3,
                )
            }
            Spacer(Modifier.height(10.dp))
            // 加载中先占位，避免首帧闪现全空热力图
            if (stats.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.surface)
                        .border(1.dp, tokens.line, RoundedCornerShape(14.dp)),
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = tokens.surface,
                    border = BorderStroke(1.dp, tokens.line),
                ) {
                    Box(Modifier.padding(16.dp)) {
                        ActivityHeatmap(days = stats.conversationsPerDay)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ProfileStatsCard(stats: app.amber.feature.ui.pages.stats.AppStats) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val totalTokens = stats.totalPromptTokens + stats.totalCompletionTokens
    val current = currentStreak(stats.conversationsPerDay)
    val longest = longestStreak(stats.conversationsPerDay)

    val items = listOf(
        formatCount(totalTokens) to stringResource(R.string.profile_stats_tokens),
        formatInt(stats.totalMessages) to stringResource(R.string.stats_page_total_messages),
        formatInt(stats.totalConversations) to stringResource(R.string.stats_page_total_conversations),
        stringResource(R.string.profile_stats_days, current) to stringResource(R.string.profile_stats_streak),
        stringResource(R.string.profile_stats_days, longest) to stringResource(R.string.profile_stats_longest_streak),
    )

    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(tokens.surface)
            .border(1.dp, tokens.line, shape),
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = value,
                    style = type.meta.copy(
                        fontSize = 15.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = tokens.ink,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Visible,
                )
                Text(
                    text = label,
                    modifier = Modifier.heightIn(min = 24.dp),
                    style = type.tinyTag.copy(
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = tokens.ink3,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
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
    val type = LocalAmberType.current
    val appLocale = LocalContext.current.appLocale()
    val density = LocalDensity.current
    val monthLabelTemplate = stringResource(R.string.profile_heatmap_month_label)

    val today = LocalDate.now()
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).minusWeeks(52)
    val weeks = 53

    val maxCount = max(1, days.values.maxOrNull() ?: 1)
    val emptyColor = tokens.line2.toArgb() // 无活动日用 line2，与 bg 拉开对比
    val accentArgb = tokens.accent.toArgb()
    val labelColor = tokens.ink3.toArgb()

    // 参照原型：月份在网格上方，格子固定为正方形并允许横向滚动。
    val cellDp = 13.dp
    val gapDp = 3.dp
    val labelGridGapDp = 7.dp
    val gridHeightDp = cellDp * 7 + gapDp * 6
    // sp.toPx() includes the active fontScale. Derive the axis height from the same
    // Paint metrics used to draw the labels so an enlarged font never crosses the top edge.
    val monthTextSizePx = with(density) { 10.sp.toPx() }
    val monthFontMetrics = Paint().apply {
        isAntiAlias = true
        textSize = monthTextSizePx
        typeface = Typeface.DEFAULT
    }.fontMetrics
    val labelPaddingPx = with(density) { 2.dp.toPx() }
    val labelHeightPx =
        (monthFontMetrics.bottom - monthFontMetrics.top + labelPaddingPx * 2f)
            .coerceAtLeast(0f)
    val labelHeightDp = with(density) { labelHeightPx.toDp() }
    val totalHeightDp = labelHeightDp + labelGridGapDp + gridHeightDp
    val totalWidthDp = cellDp * weeks + gapDp * (weeks - 1)

    val hScroll = rememberScrollState()
    // 进入时定位到最右端（最近日期），往左滑才看历史
    LaunchedEffect(hScroll.maxValue) {
        if (hScroll.maxValue > 0) {
            hScroll.scrollTo(hScroll.maxValue)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
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
                    val labelY = labelPaddingPx - monthFontMetrics.top
                    val gridTopPx = labelHeightPx + labelGridGapDp.toPx()

                    drawIntoCanvas { canvas ->
                        val paint = Paint().apply { isAntiAlias = true }
                        val textPaint = Paint().apply {
                            isAntiAlias = true
                            color = labelColor
                            textSize = monthTextSizePx
                            typeface = Typeface.DEFAULT
                        }
                        val cornerPx = 2.dp.toPx()

                        var lastMonth = -1
                        var lastLabelWeek = -99
                        for (w in 0 until weeks) {
                            val weekStart = start.plusDays((w * 7).toLong())
                            if (
                                weekStart.monthValue != lastMonth &&
                                w - lastLabelWeek >= 3 &&
                                !weekStart.isAfter(today)
                            ) {
                                lastMonth = weekStart.monthValue
                                lastLabelWeek = w
                                val monthLabel = String.format(
                                    appLocale,
                                    monthLabelTemplate,
                                    weekStart.monthValue,
                                )
                                canvas.nativeCanvas.drawText(
                                    monthLabel,
                                    (w * stepPx).coerceAtMost(
                                        (size.width - textPaint.measureText(monthLabel)).coerceAtLeast(0f),
                                    ),
                                    labelY,
                                    textPaint,
                                )
                            } else if (weekStart.monthValue != lastMonth) {
                                lastMonth = weekStart.monthValue
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
                                val y = gridTopPx + d * stepPx
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_page_heatmap_less),
                style = type.tinyTag.copy(fontSize = 9.5.sp, lineHeight = 13.sp),
                color = tokens.ink3,
            )
            Spacer(Modifier.width(5.dp))
            listOf(
                tokens.line2,
                tokens.accent.copy(alpha = 0.30f),
                tokens.accent.copy(alpha = 0.52f),
                tokens.accent.copy(alpha = 0.78f),
                tokens.accent,
            ).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = stringResource(R.string.stats_page_heatmap_more),
                style = type.tinyTag.copy(fontSize = 9.5.sp, lineHeight = 13.sp),
                color = tokens.ink3,
            )
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

@Composable
private fun formatInt(n: Int): String = if (n >= 10000) formatCount(n.toLong()) else "$n"

@Composable
private fun formatCount(n: Long): String = when {
    n >= 100_000_000 -> stringResource(R.string.profile_count_billions, n / 100_000_000.0)
    n >= 10_000 -> stringResource(R.string.profile_count_ten_thousands, n / 10_000.0)
    else -> "$n"
}

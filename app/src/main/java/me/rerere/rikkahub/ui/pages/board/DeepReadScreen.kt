package me.rerere.rikkahub.ui.pages.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.CorePoint
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepAnalysis
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadAgent
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.Perspective
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.ReadingLink
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.TimelineEvent
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.ui.components.nav.BackButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepReadScreen(topicId: String, title: String) {
    val agent: DeepReadAgent = koinInject()
    val settingsStore: SettingsAggregator = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val confirmed = settings.agentRuntime.todayBoard.deepReadFirstUseConfirmed
    var loading by remember { mutableStateOf(true) }
    var output by remember { mutableStateOf<DeepReadOutput?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val progress by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount.coerceAtLeast(1)
            (listState.firstVisibleItemIndex + 1).toFloat() / total.toFloat()
        }
    }

    fun run(force: Boolean = false) {
        if (!confirmed) return
        loading = true
        error = null
        scope.launch {
            val result = agent.run(topicId = topicId, topicTitle = title, force = force)
            output = result.getOrNull()
            error = result.exceptionOrNull()?.message
            loading = false
        }
    }

    LaunchedEffect(topicId, confirmed) {
        if (confirmed) {
            run(force = false)
        } else {
            loading = false
            output = null
            error = null
        }
    }

    val palette = magazinePalette()
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("深度阅读", style = MaterialTheme.typography.titleSmall) },
                    navigationIcon = { BackButton() },
                    actions = {
                        if (confirmed) {
                            TextButton(onClick = { run(force = true) }, enabled = !loading) {
                                Text("重新生成")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background),
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = palette.accent,
                    trackColor = palette.line,
                )
            }
        },
        containerColor = palette.background,
    ) { innerPadding ->
        when {
            !confirmed -> DeepReadConfirmation(
                modifier = Modifier.padding(innerPadding),
                palette = palette,
                onConfirm = {
                    scope.launch {
                        settingsStore.update { current ->
                            current.copy(
                                agentRuntime = current.agentRuntime.copy(
                                    todayBoard = current.agentRuntime.todayBoard.copy(
                                        deepReadFirstUseConfirmed = true,
                                    )
                                )
                            )
                        }
                    }
                },
            )
            loading -> DeepReadLoading(Modifier.padding(innerPadding), palette)
            error != null -> DeepReadError(error.orEmpty(), Modifier.padding(innerPadding)) { run(force = true) }
            output != null -> DeepReadArticle(
                title = title,
                output = output!!,
                palette = palette,
                contentPadding = innerPadding,
                listState = listState,
            )
        }
    }
}

@Composable
private fun DeepReadConfirmation(
    modifier: Modifier,
    palette: MagazinePalette,
    onConfirm: () -> Unit,
) {
    Box(modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "深度阅读会消耗更多 tokens",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light, color = palette.ink),
            )
            Text(
                "每次生成约消耗 3 万 tokens。同一话题 24 小时内优先使用缓存。",
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp, color = palette.muted),
            )
            Button(onClick = onConfirm) {
                Text("继续生成")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeepReadArticle(
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    contentPadding: PaddingValues,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(palette.background),
        contentPadding = PaddingValues(
            start = 22.dp,
            end = 22.dp,
            top = contentPadding.calculateTopPadding() + 22.dp,
            bottom = contentPadding.calculateBottomPadding() + 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(46.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    output.topicType.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Light,
                        color = palette.muted,
                    ),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.5.sp,
                        color = palette.ink,
                    ),
                )
                Text(
                    output.summary,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 26.sp,
                        color = palette.ink,
                    ),
                )
                if (output.keyEntities.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        output.keyEntities.take(4).forEach { entity ->
                            EntityPill(entity, palette)
                        }
                    }
                }
            }
        }

        output.heroImageUrl?.takeIf { it.startsWith("http") }?.let { image ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(
                        model = image,
                        contentDescription = output.heroCaption ?: title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(248.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                    if (!output.heroCaption.isNullOrBlank()) {
                        Text(output.heroCaption, style = MaterialTheme.typography.labelSmall, color = palette.muted)
                    }
                }
            }
        }

        output.timeline?.takeIf { it.isNotEmpty() }?.let { timeline ->
            item { TimelineSection(timeline, palette) }
        }

        output.corePoints?.takeIf { it.isNotEmpty() }?.let { points ->
            item { CorePointsSection(type = output.topicType, points = points, palette = palette) }
        }

        item { AnalysisSection(output.analysis, palette) }

        if (output.extendedReading.isNotEmpty()) {
            item { ReadingSection(output.extendedReading, palette) }
        }
    }
}

@Composable
private fun EditorialSection(title: String, body: String, palette: MagazinePalette) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionKicker(title, palette)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = palette.ink),
        )
    }
}

@Composable
private fun TimelineSection(events: List<TimelineEvent>, palette: MagazinePalette) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionKicker("时间轴", palette)
        events.forEach { event ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                TimelineMarker(highlight = event.isHighlight, palette = palette)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(event.date, style = MaterialTheme.typography.labelMedium, color = palette.muted)
                    Text(
                        event.event,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp, color = palette.ink),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineMarker(highlight: Boolean, palette: MagazinePalette) {
    Box(Modifier.width(18.dp).height(54.dp), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(
                color = palette.line,
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(
                color = if (highlight) palette.accent else palette.line,
                radius = if (highlight) 5.dp.toPx() else 3.dp.toPx(),
                center = Offset(size.width / 2f, 7.dp.toPx()),
            )
        }
    }
}

@Composable
private fun CorePointsSection(type: String, points: List<CorePoint>, palette: MagazinePalette) {
    val title = when (type) {
        "product" -> "功能亮点"
        "person" -> "人物背景"
        "opinion" -> "核心论点"
        else -> "关键脉络"
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionKicker(title, palette)
        points.forEachIndexed { index, point ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Text(
                    "%02d".format(index + 1),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                    color = palette.accent,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(point.point, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Light, color = palette.ink))
                    if (!point.supporting.isNullOrBlank()) {
                        Text(point.supporting, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp), color = palette.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisSection(analysis: DeepAnalysis, palette: MagazinePalette) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SectionKicker("深度分析", palette)
        if (!analysis.coreDispute.isNullOrBlank()) {
            Text(
                analysis.coreDispute,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light, color = palette.ink),
            )
        }
        analysis.quotes.take(2).forEach { quote ->
            QuoteBlock(text = quote.text, attribution = quote.attribution, palette = palette)
        }
        analysis.perspectives.takeIf { it.isNotEmpty() }?.let { perspectives ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                perspectives.forEach { PerspectiveRow(it, palette) }
            }
        }
        if (!analysis.implications.isNullOrBlank()) {
            Text(
                analysis.implications,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = palette.ink),
            )
        }
    }
}

@Composable
private fun PerspectiveRow(perspective: Perspective, palette: MagazinePalette) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(perspective.holder ?: "观点", style = MaterialTheme.typography.labelMedium, color = palette.accent)
        Text(
            perspective.viewpoint,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = palette.ink),
        )
    }
}

@Composable
private fun QuoteBlock(text: String, attribution: String?, palette: MagazinePalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text("“", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light, color = palette.accent))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium.copy(
                    lineHeight = 28.sp,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Light,
                    color = palette.ink,
                ),
            )
            if (!attribution.isNullOrBlank()) {
                Text("— $attribution", style = MaterialTheme.typography.labelSmall, color = palette.muted)
            }
        }
    }
}

@Composable
private fun ReadingSection(links: List<ReadingLink>, palette: MagazinePalette) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionKicker("扩展阅读", palette)
        links.take(8).forEach { link ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.surface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { uriHandler.openUri(link.url) }, contentPadding = PaddingValues(0.dp)) {
                    Text(link.title, style = MaterialTheme.typography.bodyLarge.copy(color = palette.ink))
                }
                Text(link.source ?: link.url, style = MaterialTheme.typography.labelSmall, color = palette.muted)
            }
        }
    }
}

@Composable
private fun EntityPill(text: String, palette: MagazinePalette) {
    Surface(shape = RoundedCornerShape(50), color = palette.surface) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
        )
    }
}

@Composable
private fun SectionKicker(text: String, palette: MagazinePalette) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            letterSpacing = 3.5.sp,
            fontWeight = FontWeight.Light,
            color = palette.muted,
        ),
    )
}

@Composable
private fun DeepReadLoading(modifier: Modifier, palette: MagazinePalette) {
    Box(modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            CircularProgressIndicator(color = palette.accent)
            Text("正在生成深度阅读", color = palette.muted)
        }
    }
}

@Composable
private fun DeepReadError(error: String, modifier: Modifier, onRetry: () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(error.ifBlank { "生成失败，请稍后重试" }, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun magazinePalette(): MagazinePalette {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        MagazinePalette(
            background = Color(0xFF0F0F0F),
            surface = Color(0xFF181818),
            ink = Color(0xFFE5E7EB),
            muted = Color(0xFF9CA3AF),
            line = Color(0xFF374151),
            accent = Color(0xFFEF4444),
        )
    } else {
        MagazinePalette(
            background = Color(0xFFFAFAF8),
            surface = Color(0xFFF0F0EC),
            ink = Color(0xFF1A1A1A),
            muted = Color(0xFF6B7280),
            line = Color(0xFFD1D5DB),
            accent = Color(0xFFEF4444),
        )
    }
}

private data class MagazinePalette(
    val background: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
)

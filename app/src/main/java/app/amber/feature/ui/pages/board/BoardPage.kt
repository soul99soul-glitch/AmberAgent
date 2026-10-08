package app.amber.feature.ui.pages.board

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.NotebookTabs
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.History
import app.amber.agent.Screen
import app.amber.agent.R
import app.amber.agent.Screen.DeepRead
import app.amber.agent.StandaloneSurfaces
import app.amber.feature.board.TodayBoardHotListFilterMode
import app.amber.feature.board.hotlist.HotListDashboard
import app.amber.feature.board.hotlist.HOT_LIST_TOPIC_DISPLAY_LIMIT
import app.amber.feature.board.hotlist.HotListItem
import app.amber.feature.board.hotlist.HotListProviderIds
import app.amber.feature.board.hotlist.HotListProviderSnapshot
import app.amber.feature.board.hotlist.HotTopic
import app.amber.feature.board.hotlist.deepread.DeepReadMoments
import app.amber.feature.board.hotlist.presentationTitle
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ds.LiveDot
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayBoardPage() {
    val navController = LocalNavController.current
    val vm: BoardViewModel = koinInject()

    val settings by vm.settings.collectAsStateWithLifecycle()
    val boardEnabled = settings.agentRuntime.todayBoard.enabled
    val dashboard by vm.hotListDashboard.collectAsStateWithLifecycle(
        initialValue = HotListDashboard(emptyList(), emptyList(), 0L),
    )
    val issueCount by vm.deepReadCount.collectAsStateWithLifecycle(initialValue = 0)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val shareChooserTitle = stringResource(R.string.board_share_chooser)
    val sharePanelError = stringResource(R.string.board_share_panel_error)
    var pendingDeepRead by remember { mutableStateOf<PendingDeepReadRequest?>(null) }
    var selectedTopic by remember { mutableStateOf<HotTopic?>(null) }
    var showDeepReadCreate by remember { mutableStateOf(false) }
    var scatterTick by remember { mutableIntStateOf(0) }
    var scatterActive by remember { mutableStateOf(false) }
    var luckyPick by remember { mutableStateOf<HotTopic?>(null) }

    // Hidden gesture (iOS DiscoveryView): shaking scatters the feed like loose
    // pages, then a bottom banner offers one lucky headline. Off while the board
    // is disabled or any sheet/dialog/flow is active.
    rememberShakeDetector(
        enabled = boardEnabled && pendingDeepRead == null && selectedTopic == null &&
            !showDeepReadCreate && dashboard.topics.isNotEmpty(),
    ) {
        scatterTick++
        scatterActive = true
        if (luckyPick == null) luckyPick = dashboard.topics.random()
    }
    if (scatterActive) {
        LaunchedEffect(scatterTick) {
            delay(720)
            scatterActive = false
        }
    }
    if (luckyPick != null) {
        LaunchedEffect(luckyPick) {
            delay(3_500)
            luckyPick = null
        }
    }

    fun requestDeepRead(topic: HotTopic, forceRegenerate: Boolean = false) {
        if (settings.agentRuntime.todayBoard.deepReadFirstUseConfirmed) {
            scope.launch {
                val prepared = vm.prepareDeepReadTopic(topic, forceRegenerate = forceRegenerate)
                navController.navigate(
                    DeepRead(
                        topicId = prepared.id,
                        title = prepared.title,
                        sourceUrl = prepared.primaryUrl(),
                    )
                )
            }
        } else {
            pendingDeepRead = PendingDeepReadRequest(topic, forceRegenerate)
        }
    }

    fun shareTopic(topic: HotTopic) {
        val text = buildString {
            append(topic.title)
            topic.primaryUrl()?.let { url ->
                append('\n')
                append(url)
            }
        }
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(intent, shareChooserTitle))
        }.onFailure {
            Toast.makeText(context, sharePanelError, Toast.LENGTH_SHORT).show()
        }
    }

    val standaloneDeepRead = StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ
    Scaffold(
        modifier = Modifier
            .amberCanvas()
            // Paper grain + night lamp on the discovery surface too — iOS paints
            // the paper treatment under every reading page, not only articles.
            .let { if (standaloneDeepRead) it.deepReadPaper(night = DeepReadMoments.isNight(), tokens = LocalAmberTokens.current) else it },
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.deep_read_title),
                titleStyle = deepReadEditorialSerif?.let { serif ->
                    LocalAmberType.current.screenTitle.copy(fontFamily = serif, fontWeight = FontWeight.Bold)
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showDeepReadCreate = true }) {
                        Icon(Lucide.SquarePen, contentDescription = stringResource(R.string.deep_read_create_title))
                    }
                    IconButton(onClick = { navController.navigate(Screen.DeepReadHistory) }) {
                        Icon(Lucide.History, contentDescription = stringResource(R.string.deep_read_history_title))
                    }
                    IconButton(onClick = { navController.navigate(Screen.SettingTodayBoard) }) {
                        Icon(Lucide.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        if (!boardEnabled) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(
                        if (standaloneDeepRead) R.string.deepread_board_disabled
                        else R.string.board_disabled,
                    ),
                    style = LocalAmberType.current.body,
                )
            }
            return@Scaffold
        }

        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            HotListTab(
                modifier = Modifier.fillMaxSize(),
                dashboard = dashboard,
                filterMode = settings.agentRuntime.todayBoard.hotListFilterMode,
                issueCount = issueCount + 1,
                scatterSeed = scatterTick,
                scatterActive = scatterActive,
                onRefresh = vm::refreshHotList,
                onTopicClick = { topic -> selectedTopic = topic },
                onProviderItemClick = { provider, item ->
                    scope.launch { selectedTopic = vm.createProviderTopic(provider, item) }
                },
            )
            // Keep the last pick so the slide-out doesn't play over an empty shell.
            var bannerTopic by remember { mutableStateOf<HotTopic?>(null) }
            if (luckyPick != null) bannerTopic = luckyPick
            AnimatedVisibility(
                visible = luckyPick != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(
                    animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
                    initialOffsetY = { it },
                ) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                bannerTopic?.let { topic ->
                    DeepReadLuckyBanner(
                        pick = LuckyPick(
                            title = topic.title,
                            detail = topicMeta(topic).source ?: "",
                            onRead = {
                                luckyPick = null
                                selectedTopic = topic
                            },
                        ),
                        onDismiss = { luckyPick = null },
                    )
                }
            }
        }
    }

    pendingDeepRead?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingDeepRead = null },
            title = { Text(stringResource(R.string.deep_read_cost_title), style = LocalAmberType.current.sessionTitle) },
            text = { Text(stringResource(R.string.deep_read_cost_message), style = LocalAmberType.current.body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            vm.confirmDeepReadCost()
                            val prepared = vm.prepareDeepReadTopic(
                                request.topic,
                                forceRegenerate = request.forceRegenerate,
                            )
                            pendingDeepRead = null
                            navController.navigate(
                                DeepRead(
                                    topicId = prepared.id,
                                    title = prepared.title,
                                    sourceUrl = prepared.primaryUrl(),
                                )
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.continue_label))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeepRead = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    selectedTopic?.let { topic ->
        HotListActionSheet(
            topic = topic,
            onDismiss = { selectedTopic = null },
            onDeepRead = {
                selectedTopic = null
                requestDeepRead(topic)
            },
            onRegenerate = {
                selectedTopic = null
                requestDeepRead(topic, forceRegenerate = true)
            },
            onOpenOriginal = {
                selectedTopic = null
                topic.primaryUrl()?.let { url ->
                    runCatching { uriHandler.openUri(url) }
                }
            },
            onShare = {
                selectedTopic = null
                shareTopic(topic)
            },
        )
    }

    if (showDeepReadCreate) {
        DeepReadCreateSheet(
            onDismiss = { showDeepReadCreate = false },
            onStart = { title, seeds, templateId ->
                showDeepReadCreate = false
                scope.launch {
                    val topic = vm.prepareCustomDeepReadTopic(title, seeds, templateId)
                    requestDeepRead(topic)
                }
            },
        )
    }
}

private data class PendingDeepReadRequest(
    val topic: HotTopic,
    val forceRegenerate: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HotListActionSheet(
    topic: HotTopic,
    onDismiss: () -> Unit,
    onDeepRead: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenOriginal: () -> Unit,
    onShare: () -> Unit,
) {
    val sheetTokens = LocalAmberTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = sheetTokens.raised,
        contentColor = sheetTokens.ink,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(sheetTokens.line2),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                topic.title,
                style = LocalAmberType.current.sessionTitle.copy(
                    fontFamily = deepReadEditorialSerif,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 25.sp,
                ),
            )
            if (topic.sources.isNotEmpty()) {
                val sourceLabels = mutableListOf<String>()
                for (source in topic.sources) {
                    sourceLabels += "${hotListProviderName(source.providerId, source.providerName)} #${source.rank}"
                }
                Text(
                    sourceLabels.joinToString(" · "),
                    style = LocalAmberType.current.meta,
                    color = workspaceColors().muted,
                )
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = LocalAmberTokens.current.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, LocalAmberTokens.current.line),
                tonalElevation = 0.dp,
            ) {
                Column {
                    TopicActionRow(
                        label = stringResource(R.string.deep_read_title),
                        icon = Lucide.NotebookTabs,
                        highlight = true,
                        onClick = onDeepRead,
                    )
                    Hairline()
                    TopicActionRow(
                        label = stringResource(R.string.regenerate),
                        icon = Lucide.RotateCw,
                        onClick = onRegenerate,
                    )
                    Hairline()
                    TopicActionRow(
                        label = stringResource(R.string.board_open_original),
                        icon = Lucide.ArrowRight,
                        enabled = topic.primaryUrl() != null,
                        onClick = onOpenOriginal,
                    )
                    Hairline()
                    TopicActionRow(
                        label = stringResource(R.string.share),
                        icon = Lucide.Share2,
                        onClick = onShare,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun TopicActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val color = when {
        !enabled -> tokens.ink3
        highlight -> tokens.accent
        else -> tokens.ink
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (highlight) tokens.accent.copy(alpha = 0.12f) else tokens.surface2)
                .border(1.dp, if (highlight) tokens.accent.copy(alpha = 0.32f) else tokens.line, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = color)
        }
        Text(label, style = LocalAmberType.current.body, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HotListTab(
    modifier: Modifier = Modifier,
    dashboard: HotListDashboard,
    filterMode: TodayBoardHotListFilterMode,
    issueCount: Int = 0,
    scatterSeed: Int = 0,
    scatterActive: Boolean = false,
    onRefresh: () -> Unit,
    onTopicClick: (HotTopic) -> Unit,
    onProviderItemClick: (HotListProviderSnapshot, HotListItem) -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var listAppeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { listAppeared = true }

    LaunchedEffect(dashboard.shouldShowSkeleton) {
        if (dashboard.shouldShowSkeleton) onRefresh()
    }
    LaunchedEffect(dashboard.lastUpdatedAt, dashboard.providers.map { it.error to it.fetchedAt }) {
        if (isRefreshing) isRefreshing = false
    }

    val standaloneDeepRead = StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ
    val tokens = LocalAmberTokens.current
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            onRefresh()
            scope.launch {
                delay(15_000L)
                isRefreshing = false
            }
        },
        state = pullState,
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = tokens.raised,
                color = tokens.accent,
            )
        },
    ) {
        if (!dashboard.hasEnabledSources) {
            if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Lucide.NotebookTabs,
                        contentDescription = null,
                        tint = tokens.accent.copy(alpha = 0.7f),
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.deepread_board_no_enabled_sources),
                        style = LocalAmberType.current.secondary,
                        color = tokens.ink3,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    EmptyLine(stringResource(R.string.board_no_enabled_sources))
                }
            }
        } else if (dashboard.isEmpty) {
            HotListSkeleton()
        } else {
            Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Standalone paints a 64dp paper-fog at the bottom edge — the
                // matching bottom padding keeps the last row readable.
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 0.dp,
                    bottom = if (standaloneDeepRead) 72.dp else 0.dp,
                ),
            ) {
                // No persistent pull hint — iOS parity is `.refreshable` only;
                // PullToRefreshBox already renders its own indicator on drag.
                item("masthead") {
                    // Editorial masthead (iOS DiscoveryView): ink rule, issue
                    // dateline, serif headline with the hidden stamp egg. The
                    // festival badge rides inside the masthead's date row.
                    DeepReadMasthead(
                        issue = issueCount,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                    )
                }
                item {
                    RubricHead(
                        label = stringResource(R.string.board_combined_topics),
                        status = dashboard.lastUpdatedAt.takeIf { it > 0L }?.let { stringResource(R.string.board_updated_ago, timeAgo(it)) },
                        first = true,
                    )
                }
                if (dashboard.topics.isEmpty()) {
                    item {
                        EmptyLine(
                            if (filterMode == TodayBoardHotListFilterMode.FOCUS_ONLY) {
                                stringResource(
                                    if (standaloneDeepRead) R.string.deepread_board_focus_empty
                                    else R.string.board_focus_empty,
                                )
                            } else {
                                stringResource(R.string.board_combined_empty)
                            }
                        )
                    }
                }
                val topics = dashboard.topics.take(HOT_LIST_TOPIC_DISPLAY_LIMIT)
                itemsIndexed(topics, key = { _, it -> it.id }) { index, topic ->
                    // Rows rise with a short stagger; later rows enter instantly.
                    // A shake tosses the whole page like loose newspaper sheets.
                    val enter = Modifier
                        .deepReadEntrance(index, listAppeared)
                        .deepReadScatter(index, scatterSeed, scatterActive)
                    if (index == 0) {
                        LeadStory(
                            rank = topic.bestRank,
                            title = topic.title,
                            dek = null,
                            meta = topicMeta(topic),
                            onClick = { onTopicClick(topic) },
                            modifier = enter,
                        )
                    } else {
                        IndexRow(
                            rank = topic.bestRank,
                            title = topic.title,
                            meta = topicMeta(topic),
                            onClick = { onTopicClick(topic) },
                            last = index == topics.lastIndex,
                            modifier = enter,
                        )
                    }
                }
                dashboard.providers.forEach { provider ->
                    item("${provider.providerId}-head") {
                        RubricHead(
                            label = hotListProviderName(provider.providerId, provider.providerName),
                            status = provider.error?.let { stringResource(R.string.board_provider_error_ago, timeAgo(provider.fetchedAt)) }
                                ?: provider.fetchedAt.takeIf { it > 0L }?.let { timeAgo(it) },
                            first = false,
                        )
                    }
                    val providerItems = provider.items.take(12)
                    if (providerItems.isEmpty()) {
                        item("${provider.providerId}-empty") {
                            EmptyLine(provider.error ?: stringResource(R.string.board_no_data))
                        }
                    } else {
                        itemsIndexed(
                            providerItems,
                            key = { i, _ -> "${provider.providerId}-$i" },
                        ) { index, item ->
                            val scatter = Modifier.deepReadScatter(index, scatterSeed, scatterActive)
                            if (index == 0) {
                                LeadStory(
                                    rank = item.rank,
                                    title = item.presentationTitle,
                                    dek = null,
                                    meta = MetaData(
                                        source = hotListProviderName(provider.providerId, provider.providerName),
                                        detail = item.heat,
                                    ),
                                    onClick = { onProviderItemClick(provider, item) },
                                    modifier = scatter,
                                )
                            } else {
                                IndexRow(
                                    rank = item.rank,
                                    title = item.presentationTitle,
                                    meta = MetaData(
                                        source = hotListProviderName(provider.providerId, provider.providerName),
                                        detail = item.heat,
                                    ),
                                    onClick = { onProviderItemClick(provider, item) },
                                    last = index == providerItems.lastIndex,
                                    modifier = scatter,
                                )
                            }
                        }
                    }
                }
            }
            // Paper fog: the index dissolves into the page near the bottom edge —
            // the flat counterpart of iOS's per-card scrollTransition dimming.
            if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to tokens.bg.copy(alpha = 0f),
                                1f to tokens.bg,
                            ),
                        ),
                )
            }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Flat "newspaper index" rows — ported from redesign/aa-board.jsx.
// Machine facts (rank / source / time) use the mono `meta` style; human titles use
// the sans `sessionTitle` family. Single `accent` for lead rank; `signal` green only
// for live/done. Flat + 1dp `line` hairline; no cards, no elevation.
// ─────────────────────────────────────────────────────────────────────────────

/** mono「source · detail」line — source in ink-3, separator+detail in ink-4. */
private data class MetaData(val source: String?, val detail: String?)

/** Bottom 1dp hairline (tokens.line) drawn at the row's lower edge. */
private fun Modifier.bottomHairline(color: Color, show: Boolean = true): Modifier =
    if (!show) this else drawBehind {
        val y = size.height - 0.5.dp.toPx()
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    }

/** Zero-pad a rank to two digits, mirroring the JSX "01"/"02" formatting. */
private fun rank2(rank: Int): String =
    if (rank in 0..99) rank.toString().padStart(2, '0') else rank.toString()

/** Derive the topic meta line from its sources, keeping the same info the old pills showed. */
@Composable
private fun topicMeta(topic: HotTopic): MetaData {
    val labels = mutableListOf<String>()
    for (source in topic.sources.take(4)) {
        labels += "${hotListProviderName(source.providerId, source.providerName)} #${source.rank}"
    }
    return MetaData(
        source = labels.firstOrNull(),
        detail = labels.drop(1).joinToString(" · ").takeIf { it.isNotBlank() },
    )
}

@Composable
private fun hotListProviderName(providerId: String, fallback: String): String = when (providerId) {
    HotListProviderIds.BILIBILI -> stringResource(R.string.board_source_bilibili)
    HotListProviderIds.HACKER_NEWS -> stringResource(R.string.board_source_hacker_news)
    HotListProviderIds.WEIBO -> stringResource(R.string.board_source_weibo)
    HotListProviderIds.ZHIHU -> stringResource(R.string.board_source_zhihu)
    HotListProviderIds.ARXIV_AI -> stringResource(R.string.board_source_arxiv_ai)
    HotListProviderIds.INFOQ_AI -> stringResource(R.string.board_source_infoq_ai)
    HotListProviderIds.KR36 -> stringResource(R.string.board_source_36kr)
    HotListProviderIds.HUGGINGFACE_PAPERS -> stringResource(R.string.board_source_huggingface)
    HotListProviderIds.GITHUB_TRENDING_AI -> stringResource(R.string.board_source_github)
    else -> fallback
}

@Composable
private fun Meta(meta: MetaData, topPadding: Dp) {
    val t = LocalAmberTokens.current
    val source = meta.source
    val detail = meta.detail
    if (source.isNullOrBlank() && detail.isNullOrBlank()) return
    val mono = LocalAmberType.current.meta.copy(fontSize = 11.sp)
    Text(
        text = buildAnnotatedString {
            if (!source.isNullOrBlank()) {
                withStyle(SpanStyle(color = t.ink3)) { append(source) }
            }
            if (!detail.isNullOrBlank()) {
                withStyle(SpanStyle(color = t.ink4)) {
                    append(if (source.isNullOrBlank()) detail else " · $detail")
                }
            }
        },
        style = mono,
        modifier = Modifier.padding(top = topPadding),
    )
}

/** mono rubric label「// 综合热点」+ right-side live dot + status. */
@Composable
private fun RubricHead(label: String, status: String?, first: Boolean) {
    val t = LocalAmberTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) 16.dp else 28.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = t.accent)) { append("//") }
                withStyle(SpanStyle(color = t.ink2)) { append(" $label") }
            },
            style = LocalAmberType.current.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
        )
        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
            // Column rule continuing past the rubric — the flat editorial
            // divider newspapers run between section labels and the dateline.
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(t.line))
            Spacer(Modifier.width(10.dp))
        }
        if (!status.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveDot(dotSize = 6.dp)
                Text(status, style = LocalAmberType.current.meta.copy(fontSize = 11.sp), color = t.ink3)
            }
        }
    }
}

/** Hero row: big accent mono rank + 19.5sp title + optional dek + mono meta. */
@Composable
private fun LeadStory(rank: Int, title: String, dek: String?, meta: MetaData, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    val pressMod = if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
        Modifier.pressableDeepRead(onClick)
    } else {
        Modifier.pressable(onClick)
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(pressMod)
            .bottomHairline(t.line)
            .padding(top = 16.dp, bottom = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            rank2(rank),
            // Serif display figure on the lead story — the broadsheet "press"
            // number; index rows keep the mono index voice below.
            style = LocalAmberType.current.meta.copy(
                fontFamily = deepReadEditorialSerif ?: LocalAmberType.current.meta.fontFamily,
                // Three-digit ranks shrink rather than wrap inside the column.
                fontSize = if (rank > 99) 24.sp else 32.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 34.sp,
            ),
            color = t.accent,
            maxLines = 1,
            modifier = Modifier.width(52.dp).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = LocalAmberType.current.sessionTitle.copy(
                    fontFamily = deepReadEditorialSerif,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 28.sp,
                ),
                color = t.ink,
            )
            if (!dek.isNullOrBlank()) {
                Text(
                    dek,
                    style = LocalAmberType.current.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
                    color = t.ink3,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
            Meta(meta, topPadding = 9.dp)
        }
    }
}

/** Index row: small grey mono rank + 16sp 2-line title + mono meta. */
@Composable
private fun IndexRow(rank: Int, title: String, meta: MetaData, onClick: () -> Unit, last: Boolean, modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    val pressMod = if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
        Modifier.pressableDeepRead(onClick)
    } else {
        Modifier.pressable(onClick)
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(pressMod)
            .bottomHairline(t.line, show = !last)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            rank2(rank),
            style = LocalAmberType.current.meta.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = t.ink4,
            modifier = Modifier.width(52.dp).padding(top = 1.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = LocalAmberType.current.sessionTitle.copy(
                    fontFamily = deepReadEditorialSerif,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 21.sp,
                ),
                color = t.ink,
            )
            Meta(meta, topPadding = 6.dp)
        }
    }
}

@Composable
private fun HotListSkeleton() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
    ) {
        item {
            RubricHead(
                label = stringResource(R.string.board_skeleton_title),
                status = stringResource(R.string.board_updating),
                first = true,
            )
        }
        items(6) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .bottomHairline(LocalAmberTokens.current.line),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(0.84f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(LocalAmberTokens.current.surface2),
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.48f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(LocalAmberTokens.current.surface2),
                )
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 14.dp),
        style = LocalAmberType.current.secondary,
        color = LocalAmberTokens.current.ink3,
    )
}

@Composable
private fun timeAgo(timestamp: Long): String {
    if (timestamp <= 0L) return stringResource(R.string.board_time_unknown)
    val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> stringResource(R.string.board_time_just_now)
        minutes < 60 -> stringResource(R.string.board_time_minutes_ago, minutes)
        minutes < 24 * 60 -> stringResource(R.string.board_time_hours_ago, minutes / 60)
        else -> stringResource(R.string.board_time_days_ago, minutes / (24 * 60))
    }
}

private fun HotTopic.primaryUrl(): String? =
    sources
        .sortedBy { it.rank }
        .firstNotNullOfOrNull { source ->
            source.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }

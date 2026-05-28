package app.amber.feature.ui.pages.board

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MAX
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MIN
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.hotlist.DeepReadHistoryItem
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.board.hotlist.deepread.CorePoint
import app.amber.feature.board.hotlist.deepread.DeepAnalysis
import app.amber.feature.board.hotlist.deepread.DeepReadDiagram
import app.amber.feature.board.hotlist.deepread.DeepReadAgentRunManager
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationPhase
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadScheduler
import app.amber.feature.board.hotlist.deepread.DeepReadSectionQuality
import app.amber.feature.board.hotlist.deepread.DeepReadSectionState
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.board.hotlist.deepread.Perspective
import app.amber.feature.board.hotlist.deepread.ReadingLink
import app.amber.feature.board.hotlist.deepread.TimelineEvent
import app.amber.feature.board.hotlist.deepread.displayHeroCaption
import app.amber.feature.board.hotlist.deepread.displayHeroImageUrl
import app.amber.feature.board.hotlist.deepread.isComplete
import app.amber.feature.board.hotlist.deepread.statusOf
import app.amber.feature.board.hotlist.deepread.errorOf
import app.amber.feature.board.hotlist.deepread.hasAnyReadySection
import app.amber.feature.board.hotlist.deepread.withInferredSectionStates
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRenderer
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRepository
import app.amber.feature.board.hotlist.deepread.verifiedImageUrls
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.font.SlidesFontRepository
import app.amber.feature.ui.theme.LocalDarkMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.koin.compose.koinInject

@Composable
fun DeepReadScreen(
    topicId: String,
    title: String,
    sourceUrl: String? = null,
    initialForceRegenerate: Boolean = false,
    fromHistory: Boolean = false,
) {
    // T-C perf-layer dispatch — see PerfFlags + docs/visual-sanity-check.md.
    if (me.rerere.rikkahub.PerfFlags.USE_SPLIT_DEEPREAD_SCREEN) {
        DeepReadScreenSplit(
            topicId = topicId,
            title = title,
            sourceUrl = sourceUrl,
            initialForceRegenerate = initialForceRegenerate,
            fromHistory = fromHistory,
        )
        return
    }

    val agent: DeepReadAgentRunManager = koinInject()
    val deepReadScheduler: DeepReadScheduler = koinInject()
    val settingsStore: SettingsAggregator = koinInject()
    val hotListRepository: HotListRepository = koinInject()
    val fontRepository: SlidesFontRepository = koinInject()
    val templateRepository: DeepReadTemplateRepository = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val fontStates by fontRepository.fontsFlow.collectAsStateWithLifecycle()
    val customTemplates by templateRepository.observeTemplates().collectAsStateWithLifecycle()
    val invalidTemplateCount by templateRepository.observeInvalidTemplateCount().collectAsStateWithLifecycle()
    val confirmed = settings.agentRuntime.todayBoard.deepReadFirstUseConfirmed
    val board = settings.agentRuntime.todayBoard
    val deepReadFontScale = board.deepReadFontScale.coerceIn(DEEP_READ_FONT_SCALE_MIN, DEEP_READ_FONT_SCALE_MAX)
    val readingFontFamily = rememberBoardReadingFontFamily(
        mode = board.boardReadingFontMode,
        fontPackId = board.boardReadingFontPackId,
        fontStates = fontStates,
    )
    val templateFontCss = rememberDeepReadTemplateFontCss(
        mode = board.boardReadingFontMode,
        fontPackId = board.boardReadingFontPackId,
        fontStates = fontStates,
        fontScale = deepReadFontScale,
    )

    val cacheEntryFlow = remember(topicId, fromHistory) {
        hotListRepository.observeDeepReadEntry(topicId, includeExpired = fromHistory)
    }
    val initialCacheState = remember(topicId, fromHistory) {
        val preview = hotListRepository.deepReadHistoryPreview(topicId).takeIf { fromHistory }
        DeepReadCacheState(
            loaded = !fromHistory || preview != null,
            entry = preview,
        )
    }
    val cacheState by produceState(
        initialValue = initialCacheState,
        key1 = cacheEntryFlow,
    ) {
        cacheEntryFlow.collect { entry ->
            value = DeepReadCacheState(loaded = true, entry = entry)
        }
    }
    val cacheEntry = cacheState.entry
    val output = remember(cacheEntry) { cacheEntry?.output?.withInferredSectionStates() }
    val historyExpired = cacheEntry?.expired == true
    val historyLoading = fromHistory && !cacheState.loaded
    var runError by remember(topicId) { mutableStateOf<String?>(null) }
    var retryingStages by remember(topicId) { mutableStateOf<Set<DeepReadGenerationStage>>(emptySet()) }
    var initialForceConsumed by rememberSaveable(topicId, sourceUrl) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val darkTheme = LocalDarkMode.current
    DeepReadImmersiveWindowEffect(darkTheme = darkTheme)

    val backgroundRunning by deepReadScheduler
        .observeRunning(topicId)
        .collectAsStateWithLifecycle(initialValue = false)
    val lifecycleRunning = backgroundRunning || retryingStages.isNotEmpty()
    val anySectionRunning = lifecycleRunning &&
        output?.sectionStates.orEmpty().values.any { it.status == DeepReadSectionStatus.RUNNING }
    val verificationRunning = lifecycleRunning &&
        output?.verificationState?.status == DeepReadSectionStatus.RUNNING
    val phaseRunning = lifecycleRunning && output?.generationPhase?.isActiveDeepReadPhase() == true
    val generating = lifecycleRunning || anySectionRunning || verificationRunning || phaseRunning
    val complete = output?.isComplete() == true
    val hasBasicDraft = output?.hasBasicDraft() == true

    fun runAll(force: Boolean = false) {
        if (!confirmed) return
        if (backgroundRunning && !force) return
        runError = null
        deepReadScheduler.run(topicId = topicId, title = title, sourceUrl = sourceUrl, force = force)
    }

    fun runOne(stage: DeepReadGenerationStage) {
        if (stage in retryingStages) return
        runError = null
        retryingStages = retryingStages + stage
        scope.launch {
            try {
                agent.runSection(topicId = topicId, topicTitle = title, stage = stage, seedUrl = sourceUrl)
            } finally {
                retryingStages = retryingStages - stage
            }
        }
    }

    LaunchedEffect(topicId, confirmed, initialForceRegenerate, fromHistory) {
        if (confirmed && !fromHistory) {
            val shouldForce = initialForceRegenerate && !initialForceConsumed
            if (shouldForce) initialForceConsumed = true
            runAll(force = shouldForce)
        }
    }

    LaunchedEffect(Unit) {
        templateRepository.reload()
    }

    val palette = magazinePalette()
    val customTemplate = customTemplates.firstOrNull { it.id == board.deepReadTemplateId }
    val selectedCustomMissing = board.deepReadTemplateId.startsWith(DeepReadTemplateIds.CUSTOM_PREFIX) &&
        customTemplate == null
    val templateSelected = board.deepReadTemplateId == DeepReadTemplateIds.EDITORIAL_SLANT || customTemplate != null
    val firstFailureMessage = output?.firstFailureMessage()
    val firstFailedStage = output?.firstFailedStage()
    val failureRetryLabel = if (firstFailedStage != null) "仅重试这一段" else "重试"
    fun retryFirstFailure() {
        firstFailedStage?.let(::runOne) ?: runAll(force = false)
    }
    fun retryInitialFailure() {
        firstFailedStage?.let(::runOne) ?: runAll(force = true)
    }
    val initialDisplayError = (runError ?: firstFailureMessage)
        ?.takeIf { !generating && (output == null || !output.hasAnyReadySection()) }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        when {
            !confirmed -> DeepReadConfirmation(
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                palette = palette,
                fontFamily = readingFontFamily,
                fontScale = deepReadFontScale,
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

            historyLoading -> Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding())

            initialDisplayError != null -> DeepReadError(
                error = initialDisplayError,
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                onRetry = ::retryInitialFailure,
                retryLabel = failureRetryLabel,
            )

            fromHistory && output == null -> DeepReadError(
                error = "历史内容无法读取或已不存在",
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                onRetry = { runAll(force = true) },
                retryLabel = "重新生成",
            )

            fromHistory && output?.hasAnyReadySection() != true -> DeepReadError(
                error = "这条历史还没有可显示的内容",
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                onRetry = { runAll(force = true) },
                retryLabel = "重新生成",
            )

            templateSelected && !selectedCustomMissing -> {
                val data = remember(output, generating) {
                    (output?.withInferredSectionStates() ?: DeepReadOutput()).asVisibleGeneratingOutput(generating)
                }
                Box(Modifier.fillMaxSize()) {
                    DeepReadTemplateArticle(
                        title = title,
                        output = data,
                        palette = palette,
                        fontCss = templateFontCss,
                        customTemplateHtml = customTemplate?.html,
                        fontRepository = fontRepository,
                        fontScale = deepReadFontScale,
                        fallback = {
                            DeepReadArticle(
                                title = title,
                                output = data,
                                palette = palette,
                                fontFamily = readingFontFamily,
                                fontScale = deepReadFontScale,
                                listState = listState,
                                onRetrySection = ::runOne,
                            )
                        },
                    )

                    val noticeModifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                    when {
                        generating -> RunningStageNotice(
                            stages = data.sectionStates,
                            verificationState = data.verificationState,
                            generationPhase = data.generationPhase,
                            modifier = noticeModifier,
                        )
                        hasBasicDraft && !complete -> TemplateFallbackNotice(
                            message = "基础稿，可继续增强",
                            modifier = noticeModifier,
                        )
                        historyExpired -> TemplateFallbackNotice(
                            message = "内容已过 24 小时，可能需要重新生成",
                            modifier = noticeModifier,
                        )
                        runError != null && !complete -> DeepReadPartialErrorNotice(
                            error = runError.orEmpty(),
                            onRetry = { runAll(force = true) },
                            modifier = noticeModifier,
                        )
                        firstFailureMessage != null && !complete -> DeepReadPartialErrorNotice(
                            error = firstFailureMessage,
                            onRetry = ::retryFirstFailure,
                            retryLabel = failureRetryLabel,
                            modifier = noticeModifier,
                        )
                    }
                }
            }

            // Nothing usable yet — only initial fetch banner before first section persists.
            output == null || !output.hasAnyReadySection() -> {
                val displayError = runError ?: firstFailureMessage?.takeIf { !generating }
                if (displayError != null) {
                    DeepReadError(
                        error = displayError,
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                        onRetry = ::retryInitialFailure,
                        retryLabel = failureRetryLabel,
                    )
                } else {
                    val data = remember(output, generating) {
                        (output?.withInferredSectionStates() ?: DeepReadOutput())
                            .asVisibleGeneratingOutput(generating = true)
                    }
                    Box(Modifier.fillMaxSize()) {
                        DeepReadArticle(
                            title = title,
                            output = data,
                            palette = palette,
                            fontFamily = readingFontFamily,
                            fontScale = deepReadFontScale,
                            listState = listState,
                            onRetrySection = ::runOne,
                        )
                        RunningStageNotice(
                            stages = data.sectionStates,
                            verificationState = data.verificationState,
                            generationPhase = data.generationPhase,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            else -> {
                val data = output
                Box(Modifier.fillMaxSize()) {
                    if (complete && templateSelected) {
                        DeepReadTemplateArticle(
                            title = title,
                            output = data,
                            palette = palette,
                            fontCss = templateFontCss,
                            customTemplateHtml = customTemplate?.html,
                            fontRepository = fontRepository,
                            fontScale = deepReadFontScale,
                            fallback = {
                                DeepReadArticle(
                                    title = title,
                                    output = data,
                                    palette = palette,
                                    fontFamily = readingFontFamily,
                                    fontScale = deepReadFontScale,
                                    listState = listState,
                                    onRetrySection = ::runOne,
                                )
                            },
                        )
                    } else {
                        DeepReadArticle(
                            title = title,
                            output = data,
                            palette = palette,
                            fontFamily = readingFontFamily,
                            fontScale = deepReadFontScale,
                            listState = listState,
                            onRetrySection = ::runOne,
                        )
                    }

                    val noticeModifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 10.dp)

                    when {
                        selectedCustomMissing -> TemplateFallbackNotice(
                            message = "模板不可用，已回退默认排版",
                            modifier = noticeModifier,
                        )
                        invalidTemplateCount > 0 && !templateSelected -> TemplateFallbackNotice(
                            message = "$invalidTemplateCount 个模板不可用，已回退默认排版",
                            modifier = noticeModifier,
                        )
                        generating -> RunningStageNotice(
                            stages = data.sectionStates,
                            verificationState = data.verificationState,
                            generationPhase = data.generationPhase,
                            modifier = noticeModifier,
                        )
                        hasBasicDraft && !complete -> TemplateFallbackNotice(
                            message = "基础稿，可继续增强",
                            modifier = noticeModifier,
                        )
                        historyExpired -> TemplateFallbackNotice(
                            message = "内容已过 24 小时，可能需要重新生成",
                            modifier = noticeModifier,
                        )
                        runError != null && !complete -> DeepReadPartialErrorNotice(
                            error = runError.orEmpty(),
                            onRetry = { runAll(force = true) },
                            modifier = noticeModifier,
                        )
                        firstFailureMessage != null && !complete -> DeepReadPartialErrorNotice(
                            error = firstFailureMessage,
                            onRetry = ::retryFirstFailure,
                            retryLabel = failureRetryLabel,
                            modifier = noticeModifier,
                        )
                    }
                }
            }
        }
    }
}

private data class DeepReadCacheState(
    val loaded: Boolean = false,
    val entry: DeepReadHistoryItem? = null,
)

private fun DeepReadOutput.asVisibleGeneratingOutput(generating: Boolean): DeepReadOutput {
    if (sectionStates.isNotEmpty() || !generating) return this
    val states = DeepReadGenerationStage.entries.associateWith { stage ->
        DeepReadSectionState(
            status = if (stage == DeepReadGenerationStage.OVERVIEW) {
                DeepReadSectionStatus.RUNNING
            } else {
                DeepReadSectionStatus.PENDING
            }
        )
    }
    return copy(
        generationPhase = generationPhase.takeIf { it.isActiveDeepReadPhase() }
            ?: DeepReadGenerationPhase.COLLECTING,
        sectionStates = states,
    )
}

private fun DeepReadOutput.firstFailureMessage(): String? =
    verificationState
        .takeIf { it.status == DeepReadSectionStatus.FAILED }
        ?.errorMessage
        ?: sectionStates
            .values
            .firstOrNull { it.status == DeepReadSectionStatus.FAILED }
            ?.errorMessage

private fun DeepReadOutput.firstFailedStage(): DeepReadGenerationStage? =
    DeepReadGenerationStage.entries.firstOrNull { stage ->
        sectionStates[stage]?.status == DeepReadSectionStatus.FAILED
    }

private fun DeepReadOutput.hasBasicDraft(): Boolean =
    sectionQualities.values.any { it == DeepReadSectionQuality.BASIC }

private fun DeepReadGenerationPhase.isActiveDeepReadPhase(): Boolean =
    this == DeepReadGenerationPhase.COLLECTING ||
        this == DeepReadGenerationPhase.PLANNING ||
        this == DeepReadGenerationPhase.WRITING ||
        this == DeepReadGenerationPhase.VERIFYING

@Composable
private fun RunningStageNotice(
    stages: Map<DeepReadGenerationStage, app.amber.feature.board.hotlist.deepread.DeepReadSectionState>,
    verificationState: app.amber.feature.board.hotlist.deepread.DeepReadSectionState,
    generationPhase: DeepReadGenerationPhase,
    modifier: Modifier = Modifier,
) {
    val phaseLabel = when (generationPhase) {
        DeepReadGenerationPhase.COLLECTING -> "资料收集"
        DeepReadGenerationPhase.PLANNING -> "结构规划"
        DeepReadGenerationPhase.VERIFYING -> "补漏验真"
        else -> null
    }
    if (verificationState.status == DeepReadSectionStatus.RUNNING || phaseLabel != null) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 4.dp,
        ) {
            Text(
                "正在${phaseLabel ?: "补漏验真"}",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        return
    }
    val activeStage = DeepReadGenerationStage.entries.firstOrNull { stage ->
        stages[stage]?.status == DeepReadSectionStatus.RUNNING
    } ?: DeepReadGenerationStage.entries.firstOrNull { stage ->
        stages[stage]?.status != DeepReadSectionStatus.READY
    } ?: return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
    ) {
        Text(
            "分段写作：${activeStage.label}",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DeepReadPartialErrorNotice(
    error: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "重试",
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDeepReadError(error).detail.ifBlank { "后续段落生成中断，已保留已写出的内容" },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(retryLabel)
            }
        }
    }
}

@Composable
private fun TemplateFallbackNotice(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun DeepReadTemplateArticle(
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    fontCss: String,
    customTemplateHtml: String? = null,
    fontRepository: SlidesFontRepository,
    fontScale: Float,
    fallback: @Composable () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val darkTheme = LocalDarkMode.current
    var failedImageUrls by remember(output) { mutableStateOf<Set<String>>(emptySet()) }
    val displayOutput = remember(output, failedImageUrls) {
        output.withoutTemplateImages(failedImageUrls)
    }
    val rendered = remember(title, displayOutput, fontCss, customTemplateHtml, darkTheme) {
        runCatching {
            if (customTemplateHtml != null) {
                DeepReadTemplateRenderer.renderCustom(
                    title = title,
                    output = displayOutput,
                    templateHtml = customTemplateHtml,
                    fontCss = fontCss,
                    darkTheme = darkTheme,
                )
            } else {
                DeepReadTemplateRenderer.renderEditorialSlant(
                    title = title,
                    output = displayOutput,
                    fontCss = fontCss,
                    darkTheme = darkTheme,
                )
            }
        }.getOrNull()
    }
    var failed by remember(rendered) { mutableStateOf(rendered == null) }
    if (failed || rendered == null) {
        fallback()
        return
    }
    key(rendered.html.hashCode()) {
        DeepReadStaticTemplateWebView(
            html = rendered.html,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background),
            baseUrl = DEEP_READ_TEMPLATE_BASE_URL,
            allowedImageUrls = rendered.allowedImageUrls,
            allowedLinkUrls = rendered.allowedLinkUrls,
            fontRepository = fontRepository,
            textScale = fontScale,
            backgroundColor = palette.background,
            onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
            onMainFrameError = { failed = true },
            onImageError = { url -> failedImageUrls = failedImageUrls + url },
        )
    }
}

@Composable
private fun DeepReadImmersiveWindowEffect(darkTheme: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, darkTheme) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val previousStatusBarColor = window.statusBarColor
            val previousNavigationBarColor = window.navigationBarColor
            val previousSoftInputMode = window.attributes.softInputMode
            val previousCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode
            } else {
                null
            }
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            controller.apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                show(WindowInsetsCompat.Type.statusBars())
                hide(WindowInsetsCompat.Type.navigationBars())
            }
            onDispose {
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = previousStatusBarColor
                window.navigationBarColor = previousNavigationBarColor
                window.attributes = window.attributes.apply {
                    softInputMode = previousSoftInputMode
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && previousCutoutMode != null) {
                        layoutInDisplayCutoutMode = previousCutoutMode
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun DeepReadOutput.withoutTemplateImages(urls: Set<String>): DeepReadOutput {
    if (urls.isEmpty()) return this
    return copy(
        heroImageUrl = heroImageUrl?.takeUnless { it in urls },
        heroImageConfidence = if (heroImageUrl != null && heroImageUrl in urls) null else heroImageConfidence,
        imageAssets = imageAssets.filterNot { it.url in urls },
        timeline = timeline?.map { event ->
            if (event.imageUrl in urls) {
                event.copy(imageUrl = null, imageCaption = null)
            } else {
                event
            }
        },
        corePoints = corePoints?.map { point ->
            if (point.imageUrl in urls) {
                point.copy(imageUrl = null, imageCaption = null)
            } else {
                point
            }
        },
    )
}

private fun openHttpUrl(uriHandler: androidx.compose.ui.platform.UriHandler, url: String) {
    val scheme = runCatching { android.net.Uri.parse(url).scheme }.getOrNull()
    if (scheme == "http" || scheme == "https") {
        runCatching { uriHandler.openUri(url) }
    }
}

@Composable
private fun DeepReadConfirmation(
    modifier: Modifier,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
    fontScale: Float,
    onConfirm: () -> Unit,
) {
    DeepReadScaledText(fontScale) {
        Box(modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    "深度阅读会消耗更多 tokens",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light, color = palette.ink)
                        .withReadingFont(fontFamily),
                )
                Text(
                    "每次生成约消耗 3 万 tokens。同一话题 24 小时内优先使用缓存。",
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp, color = palette.muted)
                        .withReadingFont(fontFamily),
                )
                Button(onClick = onConfirm) {
                    Text("继续生成")
                }
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
    fontFamily: FontFamily?,
    fontScale: Float,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRetrySection: (DeepReadGenerationStage) -> Unit,
) {
    val verifiedImageUrls = remember(output.imageAssets) { output.verifiedImageUrls() }
    DeepReadScaledText(fontScale) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            item {
                MagazineHeroFrame(
                    title = title,
                    output = output,
                    status = output.statusOf(DeepReadGenerationStage.OVERVIEW),
                    errorMessage = output.errorOf(DeepReadGenerationStage.OVERVIEW),
                    onRetry = { onRetrySection(DeepReadGenerationStage.OVERVIEW) },
                    palette = palette,
                    fontFamily = fontFamily,
                )
            }

            if (output.hasBasicDraft()) {
                item {
                    ArticleInset {
                        BasicDraftNotice(palette = palette, fontFamily = fontFamily)
                    }
                }
            }

            item {
                ArticleInset {
                    NarrativeFrame(
                        output = output,
                        verifiedImageUrls = verifiedImageUrls,
                        status = output.statusOf(DeepReadGenerationStage.NARRATIVE),
                        errorMessage = output.errorOf(DeepReadGenerationStage.NARRATIVE),
                        onRetry = { onRetrySection(DeepReadGenerationStage.NARRATIVE) },
                        palette = palette,
                        fontFamily = fontFamily,
                    )
                }
            }

            output.diagram?.takeIf { it.nodes.size >= 2 }?.let { diagram ->
                item {
                    ArticleInset {
                        DiagramSection(
                            diagram = diagram,
                            palette = palette,
                            fontFamily = fontFamily,
                        )
                    }
                }
            }

            item {
                ArticleInset {
                    AnalysisFrame(
                        analysis = output.analysis,
                        status = output.statusOf(DeepReadGenerationStage.ANALYSIS),
                        errorMessage = output.errorOf(DeepReadGenerationStage.ANALYSIS),
                        onRetry = { onRetrySection(DeepReadGenerationStage.ANALYSIS) },
                        palette = palette,
                        fontFamily = fontFamily,
                    )
                }
            }

            item {
                ArticleInset {
                    ReadingFrame(
                        links = output.extendedReading,
                        status = output.statusOf(DeepReadGenerationStage.EXTENDED_READING),
                        errorMessage = output.errorOf(DeepReadGenerationStage.EXTENDED_READING),
                        onRetry = { onRetrySection(DeepReadGenerationStage.EXTENDED_READING) },
                        palette = palette,
                        fontFamily = fontFamily,
                    )
                }
            }
        }
    }
}

@Composable
private fun BasicDraftNotice(
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.accent.copy(alpha = 0.08f),
    ) {
        Text(
            "基础稿，可继续增强",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                color = palette.muted,
            ).withReadingFont(fontFamily),
        )
    }
}

@Composable
private fun DeepReadScaledText(
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val safeScale = fontScale.coerceIn(DEEP_READ_FONT_SCALE_MIN, DEEP_READ_FONT_SCALE_MAX)
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * safeScale),
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MagazineHeroFrame(
    title: String,
    output: DeepReadOutput,
    status: DeepReadSectionStatus,
    errorMessage: String?,
    onRetry: () -> Unit,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    when (status) {
        DeepReadSectionStatus.READY -> MagazineHero(
            title = title,
            output = output,
            palette = palette,
            fontFamily = fontFamily,
        )
        DeepReadSectionStatus.RUNNING -> HeroSkeleton(
            title = title,
            palette = palette,
            fontFamily = fontFamily,
        )
        DeepReadSectionStatus.FAILED -> SectionErrorCard(
            label = "概览生成失败",
            errorMessage = errorMessage,
            onRetry = onRetry,
            palette = palette,
            fontFamily = fontFamily,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 36.dp),
        )
        DeepReadSectionStatus.PENDING -> SectionPlaceholder(
            label = "概览待生成",
            palette = palette,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 36.dp),
        )
    }
}

@Composable
private fun NarrativeFrame(
    output: DeepReadOutput,
    verifiedImageUrls: Set<String>,
    status: DeepReadSectionStatus,
    errorMessage: String?,
    onRetry: () -> Unit,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    when (status) {
        DeepReadSectionStatus.READY -> Column(verticalArrangement = Arrangement.spacedBy(40.dp)) {
            output.timeline?.takeIf { it.isNotEmpty() }?.let { timeline ->
                TimelineSection(timeline, verifiedImageUrls, palette, fontFamily)
            }
            output.corePoints?.takeIf { it.isNotEmpty() }?.let { points ->
                CorePointsSection(
                    type = output.topicType,
                    points = points,
                    verifiedImageUrls = verifiedImageUrls,
                    palette = palette,
                    fontFamily = fontFamily,
                )
            }
        }
        DeepReadSectionStatus.RUNNING -> SectionSkeleton(
            label = "时间轴叙事",
            palette = palette,
            fontFamily = fontFamily,
            lineCount = 4,
        )
        DeepReadSectionStatus.FAILED -> SectionErrorCard(
            label = "时间轴叙事生成失败",
            errorMessage = errorMessage,
            onRetry = onRetry,
            palette = palette,
            fontFamily = fontFamily,
        )
        DeepReadSectionStatus.PENDING -> SectionPlaceholder(
            label = "时间轴叙事待生成",
            palette = palette,
        )
    }
}

@Composable
private fun AnalysisFrame(
    analysis: DeepAnalysis,
    status: DeepReadSectionStatus,
    errorMessage: String?,
    onRetry: () -> Unit,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    when (status) {
        DeepReadSectionStatus.READY -> AnalysisSection(analysis, palette, fontFamily)
        DeepReadSectionStatus.RUNNING -> SectionSkeleton(
            label = "深度分析",
            palette = palette,
            fontFamily = fontFamily,
            lineCount = 3,
        )
        DeepReadSectionStatus.FAILED -> SectionErrorCard(
            label = "深度分析生成失败",
            errorMessage = errorMessage,
            onRetry = onRetry,
            palette = palette,
            fontFamily = fontFamily,
        )
        DeepReadSectionStatus.PENDING -> SectionPlaceholder(
            label = "深度分析待生成",
            palette = palette,
        )
    }
}

@Composable
private fun ReadingFrame(
    links: List<ReadingLink>,
    status: DeepReadSectionStatus,
    errorMessage: String?,
    onRetry: () -> Unit,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    when (status) {
        DeepReadSectionStatus.READY -> if (links.isNotEmpty()) {
            ReadingSection(links, palette, fontFamily)
        } else {
            SectionPlaceholder(label = "暂无扩展阅读", palette = palette)
        }
        DeepReadSectionStatus.RUNNING -> SectionSkeleton(
            label = "扩展阅读",
            palette = palette,
            fontFamily = fontFamily,
            lineCount = 3,
        )
        DeepReadSectionStatus.FAILED -> SectionErrorCard(
            label = "扩展阅读生成失败",
            errorMessage = errorMessage,
            onRetry = onRetry,
            palette = palette,
            fontFamily = fontFamily,
        )
        DeepReadSectionStatus.PENDING -> SectionPlaceholder(
            label = "扩展阅读待生成",
            palette = palette,
        )
    }
}

@Composable
private fun HeroSkeleton(
    title: String,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 30.dp, top = 36.dp, end = 30.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "DEEP READ",
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 3.2.sp,
                fontWeight = FontWeight.Light,
                color = palette.accent,
            ),
        )
        Spacer(
            Modifier
                .width(126.dp)
                .height(1.dp)
                .background(palette.line),
        )
        Text(
            title,
            style = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.Light,
                lineHeight = 52.sp,
                color = palette.ink,
            ).withReadingFont(fontFamily),
        )
        SkeletonLine(palette = palette, widthFraction = 1f)
        SkeletonLine(palette = palette, widthFraction = 0.92f)
        SkeletonLine(palette = palette, widthFraction = 0.75f)
        Text(
            "正在写入概览…",
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
        )
    }
}

@Composable
private fun SectionSkeleton(
    label: String,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
    lineCount: Int = 3,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionKicker(label, palette)
        repeat(lineCount) { index ->
            val fraction = when (index) {
                lineCount - 1 -> 0.6f
                else -> 0.94f - index * 0.05f
            }
            SkeletonLine(palette = palette, widthFraction = fraction)
        }
        Text(
            "正在写入${label}…",
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
        )
    }
}

@Composable
private fun SkeletonLine(palette: MagazinePalette, widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(14.dp)
            .background(palette.surface, RoundedCornerShape(7.dp)),
    )
}

@Composable
private fun SectionErrorCard(
    label: String,
    errorMessage: String?,
    onRetry: () -> Unit,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall.withReadingFont(fontFamily),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall.withReadingFont(fontFamily),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text("仅重试这一段")
            }
        }
    }
}

@Composable
private fun SectionPlaceholder(
    label: String,
    palette: MagazinePalette,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = palette.surface.copy(alpha = 0.5f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = palette.muted,
        )
    }
}

@Composable
private fun ArticleInset(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp),
    ) {
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MagazineHero(
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    val image = remember(output.heroImageUrl, output.heroImageConfidence, output.imageAssets) {
        output.displayHeroImageUrl()
    }
    val imageCaption = remember(output.heroCaption, output.imageAssets, image) {
        output.displayHeroCaption(image)
    }
    var imageFailed by remember(image) { mutableStateOf(false) }
    val showImage = image != null && !imageFailed
    val sourceLabel = remember(output.references, output.extendedReading) {
        deepReadSourceLabel(output)
    }

    if (showImage) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .height(414.dp),
            ) {
                AsyncImage(
                    model = image,
                    contentDescription = imageCaption ?: title,
                    contentScale = ContentScale.Crop,
                    onError = { imageFailed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(342.dp)
                        .align(Alignment.TopCenter),
                )
                Canvas(Modifier.fillMaxSize()) {
                    val startY = 268.dp.toPx()
                    val endY = 346.dp.toPx()
                    val path = Path().apply {
                        moveTo(0f, startY)
                        lineTo(size.width, endY)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, palette.background)
                }
                SlantedHeroMeta(
                    type = output.topicType,
                    sourceLabel = sourceLabel,
                    palette = palette,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 30.dp, bottom = 38.dp, end = 30.dp),
                )
                imageCaption?.let { caption ->
                    Text(
                        caption,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 30.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.labelSmall.withReadingFont(fontFamily),
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HeroTextBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp),
                title = title,
                output = output,
                palette = palette,
                fontFamily = fontFamily,
                showKicker = false,
            )
        }
    } else {
        TextOnlyHero(
            title = title,
            output = output,
            palette = palette,
            fontFamily = fontFamily,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextOnlyHero(
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    val sourceCount = remember(output.references, output.extendedReading) {
        deepReadSourceCount(output)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 30.dp, top = 36.dp, end = 30.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DEEP READ",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 3.2.sp,
                    fontWeight = FontWeight.Light,
                    color = palette.accent,
                ),
            )
            Text(
                if (sourceCount > 0) "$sourceCount SOURCES" else "CHINESE REWRITE",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 2.6.sp,
                    fontWeight = FontWeight.Light,
                    color = palette.muted,
                ),
            )
        }
        Spacer(
            Modifier
                .width(126.dp)
                .height(1.dp)
                .background(palette.line),
        )
        HeroTextBlock(
            modifier = Modifier.fillMaxWidth(),
            title = title,
            output = output,
            palette = palette,
            fontFamily = fontFamily,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroTextBlock(
    modifier: Modifier,
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
    showKicker: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (showKicker) {
            Text(
                output.topicType.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Light,
                    color = palette.muted,
                ),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.Light,
                lineHeight = 52.sp,
                color = palette.ink,
            ).withReadingFont(fontFamily),
        )
        Text(
            output.summary,
            style = MaterialTheme.typography.bodyLarge.copy(
                lineHeight = 31.sp,
                color = palette.ink,
            ).withReadingFont(fontFamily),
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

@Composable
private fun SlantedHeroMeta(
    type: String,
    sourceLabel: String,
    palette: MagazinePalette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            type.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 3.2.sp,
                fontWeight = FontWeight.Light,
                color = palette.accent,
            ),
            maxLines = 1,
        )
        Text(
            sourceLabel,
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 2.4.sp,
                fontWeight = FontWeight.Light,
                color = palette.muted,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun deepReadSourceCount(output: DeepReadOutput): Int =
    (output.references.ifEmpty { output.extendedReading })
        .map { it.source ?: it.url }
        .filter { it.isNotBlank() }
        .distinct()
        .size

private fun deepReadSourceLabel(output: DeepReadOutput): String =
    deepReadSourceCount(output)
        .takeIf { it > 0 }
        ?.let { "$it SOURCES" }
        ?: "DEEP READ"

@Composable
private fun EditorialSection(title: String, body: String, palette: MagazinePalette, fontFamily: FontFamily?) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionKicker(title, palette)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = palette.ink)
                .withReadingFont(fontFamily),
        )
    }
}

@Composable
private fun TimelineSection(
    events: List<TimelineEvent>,
    verifiedImageUrls: Set<String>,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionKicker("时间轴", palette)
        events.forEach { event ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                TimelineMarker(highlight = event.isHighlight, palette = palette)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(event.date, style = MaterialTheme.typography.labelMedium, color = palette.muted)
                    Text(
                        event.event,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp, color = palette.ink)
                            .withReadingFont(fontFamily),
                    )
                    event.imageUrl?.takeIf { it in verifiedImageUrls }?.let { image ->
                        EditorialImage(
                            imageUrl = image,
                            caption = event.imageCaption,
                            palette = palette,
                            fontFamily = fontFamily,
                        )
                    }
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
private fun CorePointsSection(
    type: String,
    points: List<CorePoint>,
    verifiedImageUrls: Set<String>,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
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
                    Text(
                        point.point,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Light, color = palette.ink)
                            .withReadingFont(fontFamily),
                    )
                    val pointSupporting = point.supporting
                    if (!pointSupporting.isNullOrBlank()) {
                        Text(
                            pointSupporting,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp)
                                .withReadingFont(fontFamily),
                            color = palette.muted,
                        )
                    }
                    point.imageUrl?.takeIf { it in verifiedImageUrls }?.let { image ->
                        EditorialImage(
                            imageUrl = image,
                            caption = point.imageCaption,
                            palette = palette,
                            fontFamily = fontFamily,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagramSection(
    diagram: DeepReadDiagram,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    val label = when (diagram.type) {
        "causal_chain" -> "因果链"
        "process_flow" -> "流程图"
        "stakeholder_map" -> "关系图"
        "system_structure" -> "结构图"
        "comparison_matrix" -> "对比图"
        else -> "图解"
    }
    val nodeLabels = diagram.nodes.associate { it.id to it.label }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionKicker(label, palette)
        Text(
            diagram.title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light, color = palette.ink)
                .withReadingFont(fontFamily),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface.copy(alpha = 0.56f), RoundedCornerShape(8.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            diagram.nodes.take(8).forEachIndexed { index, node ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "%02d".format(index + 1),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
                        color = palette.accent,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            node.label,
                            style = MaterialTheme.typography.titleSmall.withReadingFont(fontFamily),
                            color = palette.ink,
                        )
                        node.group?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp)
                                    .withReadingFont(fontFamily),
                                color = palette.accent,
                            )
                        }
                        node.note?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp)
                                    .withReadingFont(fontFamily),
                                color = palette.muted,
                            )
                        }
                    }
                }
            }
            diagram.edges.take(12).takeIf { it.isNotEmpty() }?.let { edges ->
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.muted.copy(alpha = 0.18f))
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    edges.forEach { edge ->
                        val from = nodeLabels[edge.from] ?: edge.from
                        val to = nodeLabels[edge.to] ?: edge.to
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "$from -> $to",
                                style = MaterialTheme.typography.labelMedium.withReadingFont(fontFamily),
                                color = palette.ink,
                            )
                            edge.label?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp)
                                        .withReadingFont(fontFamily),
                                    color = palette.muted,
                                )
                            }
                        }
                    }
                }
            }
        }
        diagram.caption?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.withReadingFont(fontFamily),
                color = palette.muted,
            )
        }
    }
}

@Composable
private fun EditorialImage(
    imageUrl: String,
    caption: String?,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
) {
    var failed by remember(imageUrl) { mutableStateOf(false) }
    if (failed) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = caption,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(palette.surface),
        )
        caption?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.withReadingFont(fontFamily),
                color = palette.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AnalysisSection(analysis: DeepAnalysis, palette: MagazinePalette, fontFamily: FontFamily?) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SectionKicker("深度分析", palette)
        val analysisCoreDispute = analysis.coreDispute
        if (!analysisCoreDispute.isNullOrBlank()) {
            Text(
                analysisCoreDispute,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light, color = palette.ink)
                    .withReadingFont(fontFamily),
            )
        }
        analysis.quotes.take(2).forEach { quote ->
            QuoteBlock(text = quote.text, attribution = quote.attribution, palette = palette, fontFamily = fontFamily)
        }
        analysis.perspectives.takeIf { it.isNotEmpty() }?.let { perspectives ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                perspectives.forEach { PerspectiveRow(it, palette, fontFamily) }
            }
        }
        val analysisImplications = analysis.implications
        if (!analysisImplications.isNullOrBlank()) {
            Text(
                analysisImplications,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = palette.ink)
                    .withReadingFont(fontFamily),
            )
        }
    }
}

@Composable
private fun PerspectiveRow(perspective: Perspective, palette: MagazinePalette, fontFamily: FontFamily?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(perspective.holder ?: "观点", style = MaterialTheme.typography.labelMedium, color = palette.accent)
        Text(
            perspective.viewpoint,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = palette.ink)
                .withReadingFont(fontFamily),
        )
    }
}

@Composable
private fun QuoteBlock(text: String, attribution: String?, palette: MagazinePalette, fontFamily: FontFamily?) {
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
                ).withReadingFont(fontFamily),
            )
            if (!attribution.isNullOrBlank()) {
                Text("— $attribution", style = MaterialTheme.typography.labelSmall.withReadingFont(fontFamily), color = palette.muted)
            }
        }
    }
}

@Composable
private fun ReadingSection(links: List<ReadingLink>, palette: MagazinePalette, fontFamily: FontFamily?) {
    val uriHandler = LocalUriHandler.current
    val visibleLinks = links.take(8)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        SectionKicker("扩展阅读", palette)
        Spacer(Modifier.height(14.dp))
        visibleLinks.forEachIndexed { index, link ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openHttpUrl(uriHandler, link.url) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    "%02d".format(index + 1),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.5.sp,
                        color = palette.accent,
                    ),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        link.title,
                        style = MaterialTheme.typography.bodySmall.copy(
                            lineHeight = 20.sp,
                            color = palette.ink,
                        ).withReadingFont(fontFamily),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(link.source ?: link.url, style = MaterialTheme.typography.labelSmall, color = palette.muted)
                }
            }
            if (index != visibleLinks.lastIndex) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.line.copy(alpha = 0.55f)),
                )
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
private fun DeepReadLoading(
    modifier: Modifier,
    palette: MagazinePalette,
    output: DeepReadOutput?,
) {
    val stages = remember { DeepReadGenerationStage.entries }
    val states = output?.sectionStates.orEmpty()
    val readyCount = stages.count { states[it]?.status == DeepReadSectionStatus.READY }
    val activeStage = stages.firstOrNull { states[it]?.status == DeepReadSectionStatus.RUNNING }
        ?: stages.firstOrNull { (states[it]?.status ?: DeepReadSectionStatus.PENDING) != DeepReadSectionStatus.READY }
        ?: stages.first()
    val activeIndex = stages.indexOf(activeStage).coerceAtLeast(0)
    val progress = ((readyCount + 0.4f) / stages.size).coerceIn(0.12f, 0.92f)
    Box(modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 38.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text(
                "正在为你生成\n一篇深度好文",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light, color = palette.ink),
            )
            Text(
                "内容会按段写入：概览先出现，随后补齐叙事、分析和扩展阅读。",
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
                color = palette.muted,
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                stages.forEachIndexed { index, stage ->
                    val status = states[stage]?.status ?: DeepReadSectionStatus.PENDING
                    val active = stage == activeStage
                    val done = status == DeepReadSectionStatus.READY
                    val failed = status == DeepReadSectionStatus.FAILED
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                            active -> palette.surface
                            done -> palette.surface.copy(alpha = 0.72f)
                            else -> palette.surface.copy(alpha = 0.44f)
                        },
                        tonalElevation = if (active) 2.dp else 0.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            TimelineMarker(highlight = done || active, palette = palette)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    when {
                                        done -> "${stage.label} · 已写入"
                                        failed -> "${stage.label} · 失败"
                                        active -> "${stage.label} · 正在写入"
                                        else -> "${stage.label} · 排队中"
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Light),
                                    color = if (done || active) palette.ink else palette.muted,
                                )
                                Text(
                                    when (index) {
                                        0 -> "先生成可读概览、关键实体和真实来源图片"
                                        1 -> "再补时间轴叙事或故事性脉络"
                                        2 -> "继续写核心分歧、各方立场和影响"
                                        else -> "最后整理引用、相关阅读并写入缓存"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.muted,
                                )
                            }
                        }
                    }
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = palette.accent,
                trackColor = palette.line,
            )
        }
    }
}

@Composable
private fun DeepReadError(
    error: String,
    modifier: Modifier,
    onRetry: () -> Unit,
    retryLabel: String = "重试",
) {
    val ui = remember(error) { formatDeepReadError(error) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                ui.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ui.reason?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (ui.detail.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    SelectionContainer {
                        Text(
                            ui.detail,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            ui.suggestion?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRetry) { Text(retryLabel) }
        }
    }
}

private data class DeepReadErrorUi(
    val title: String,
    val reason: String?,
    val detail: String,
    val suggestion: String?,
)

private fun formatDeepReadError(error: String): DeepReadErrorUi {
    val normalized = error.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) {
        return DeepReadErrorUi(
            title = "深度阅读生成失败",
            reason = null,
            detail = "没有收到具体错误信息。",
            suggestion = "可以重试一次，或切换模型后再生成。",
        )
    }
    val lines = error.trim().lines().map { it.trim() }.filter { it.isNotBlank() }
    val firstLine = lines.firstOrNull().orEmpty()
    val httpCode = Regex("""HTTP\s*(\d{3})""", RegexOption.IGNORE_CASE)
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?: Regex("""response\D{0,12}(\d{3})""", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
    val title = when {
        "被拒绝" in firstLine || httpCode == "400" -> "模型请求被拒绝"
        httpCode in setOf("401", "403") -> "模型鉴权失败"
        httpCode == "429" -> "模型额度或频率受限"
        httpCode in setOf("500", "502", "503", "504") -> "模型服务异常"
        else -> "深度阅读生成失败"
    }
    val detail = lines
        .drop(1)
        .takeWhile { !it.startsWith("可能") && !it.startsWith("请") && !it.startsWith("当前") && !it.startsWith("这是") && !it.startsWith("请求") }
        .joinToString("\n")
        .ifBlank { normalized }
        .take(900)
    val suggestion = lines
        .lastOrNull()
        ?.takeIf { it != firstLine && it != detail && looksLikeSuggestion(it) }
        ?: defaultDeepReadErrorSuggestion(httpCode, normalized)
    return DeepReadErrorUi(
        title = title,
        reason = httpCode?.let { "HTTP $it" },
        detail = detail,
        suggestion = suggestion,
    )
}

private fun looksLikeSuggestion(text: String): Boolean =
    listOf("可以", "建议", "请", "可能", "稍后", "切换").any { it in text }

private fun defaultDeepReadErrorSuggestion(httpCode: String?, message: String): String? {
    val lower = message.lowercase()
    return when {
        httpCode == "400" && listOf("reject", "rejected", "safety", "policy", "blocked").any { it in lower } ->
            "可能是来源正文或提示词触发了模型安全策略。可以换一个模型，或减少来源正文后重试。"
        httpCode == "400" -> "请求被模型服务判定为无效。建议换模型或重新生成一次。"
        httpCode in setOf("401", "403") -> "请检查这个模型的 API Key、Base URL、模型名和账号权限。"
        httpCode == "429" -> "当前模型可能达到额度或频率限制，稍后重试或切换模型。"
        httpCode in setOf("500", "502", "503", "504") -> "这是模型服务端错误，稍后重试通常可以恢复。"
        else -> null
    }
}

private fun TextStyle.withReadingFont(fontFamily: FontFamily?): TextStyle =
    if (fontFamily == null) this else copy(fontFamily = fontFamily)

@Composable
private fun magazinePalette(): MagazinePalette {
    val dark = LocalDarkMode.current
    return if (dark) {
        MagazinePalette(
            background = Color(0xFF0B0A09),
            surface = Color(0xFF181410),
            ink = Color(0xFFF1ECE3),
            muted = Color(0xFFA89D90),
            line = Color(0xFF3A332B),
            accent = Color(0xFFD18752),
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

package me.rerere.rikkahub.ui.pages.board

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.agent.board.DeepReadTemplateIds
import me.rerere.rikkahub.data.agent.board.TodayBoardReadingFontMode
import me.rerere.rikkahub.data.agent.board.hotlist.HotListRepository
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.CorePoint
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepAnalysis
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadAgent
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadGenerationStage
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSectionState
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSectionStatus
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.Perspective
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.ReadingLink
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.TimelineEvent
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.isComplete
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.statusOf
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.errorOf
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.hasAnyReadySection
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.withInferredSectionStates
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateRenderer
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateRepository
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.verifiedImageUrls
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.font.FontPackState
import me.rerere.rikkahub.data.font.SlidesFontRepository
import me.rerere.rikkahub.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import java.io.ByteArrayInputStream
import java.io.File

@Composable
fun DeepReadScreen(topicId: String, title: String) {
    val agent: DeepReadAgent = koinInject()
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
    val readingFontFamily = rememberBoardReadingFontFamily(
        mode = board.boardReadingFontMode,
        fontPackId = board.boardReadingFontPackId,
        fontStates = fontStates,
    )
    val templateFontCss = rememberDeepReadTemplateFontCss(
        mode = board.boardReadingFontMode,
        fontPackId = board.boardReadingFontPackId,
        fontStates = fontStates,
    )

    val outputFlow = remember(topicId) {
        hotListRepository.observeDeepRead(topicId).map { it?.withInferredSectionStates() }
    }
    val output by outputFlow.collectAsStateWithLifecycle(initialValue = null)
    var runError by remember(topicId) { mutableStateOf<String?>(null) }
    var fullRunInFlight by remember(topicId) { mutableStateOf(false) }
    var retryingStages by remember(topicId) { mutableStateOf<Set<DeepReadGenerationStage>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val anySectionRunning = output?.sectionStates.orEmpty().values.any { it.status == DeepReadSectionStatus.RUNNING }
    val generating = fullRunInFlight || anySectionRunning || retryingStages.isNotEmpty()
    val complete = output?.isComplete() == true

    fun runAll(force: Boolean = false) {
        if (!confirmed) return
        if (fullRunInFlight) return
        fullRunInFlight = true
        runError = null
        scope.launch {
            val result = agent.run(topicId = topicId, topicTitle = title, force = force)
            runError = result.exceptionOrNull()?.message
            fullRunInFlight = false
        }
    }

    fun runOne(stage: DeepReadGenerationStage) {
        if (stage in retryingStages) return
        retryingStages = retryingStages + stage
        scope.launch {
            try {
                agent.runSection(topicId = topicId, topicTitle = title, stage = stage)
            } finally {
                retryingStages = retryingStages - stage
            }
        }
    }

    LaunchedEffect(topicId, confirmed) {
        if (confirmed) runAll(force = false)
    }

    LaunchedEffect(Unit) {
        templateRepository.reload()
    }

    val palette = magazinePalette()
    val customTemplate = customTemplates.firstOrNull { it.id == board.deepReadTemplateId }
    val selectedCustomMissing = board.deepReadTemplateId.startsWith(DeepReadTemplateIds.CUSTOM_PREFIX) &&
        customTemplate == null
    val templateSelected = board.deepReadTemplateId == DeepReadTemplateIds.EDITORIAL_SLANT || customTemplate != null
    val firstFailureMessage = output?.sectionStates
        ?.values
        ?.firstOrNull { it.status == DeepReadSectionStatus.FAILED }
        ?.errorMessage
    val initialDisplayError = (runError ?: firstFailureMessage)
        ?.takeIf { !generating && (output == null || output?.hasAnyReadySection() != true) }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        when {
            !confirmed -> DeepReadConfirmation(
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                palette = palette,
                fontFamily = readingFontFamily,
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

            initialDisplayError != null -> DeepReadError(
                error = initialDisplayError,
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                onRetry = { runAll(force = true) },
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
                        fallback = {
                            DeepReadArticle(
                                title = title,
                                output = data,
                                palette = palette,
                                fontFamily = readingFontFamily,
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
                            modifier = noticeModifier,
                        )
                        runError != null && !complete -> DeepReadPartialErrorNotice(
                            error = runError.orEmpty(),
                            onRetry = { runAll(force = true) },
                            modifier = noticeModifier,
                        )
                        firstFailureMessage != null && !complete -> DeepReadPartialErrorNotice(
                            error = firstFailureMessage,
                            onRetry = { runAll(force = true) },
                            modifier = noticeModifier,
                        )
                    }
                }
            }

            // Nothing usable yet — only initial fetch banner before first section persists.
            output == null || !output!!.hasAnyReadySection() -> {
                val displayError = runError ?: firstFailureMessage?.takeIf { !generating }
                if (displayError != null) {
                    DeepReadError(
                        error = displayError,
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                        onRetry = { runAll(force = true) },
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
                            listState = listState,
                            onRetrySection = ::runOne,
                        )
                        RunningStageNotice(
                            stages = data.sectionStates,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            else -> {
                val data = output!!
                Box(Modifier.fillMaxSize()) {
                    if (complete && templateSelected) {
                        DeepReadTemplateArticle(
                            title = title,
                            output = data,
                            palette = palette,
                            fontCss = templateFontCss,
                            customTemplateHtml = customTemplate?.html,
                            fontRepository = fontRepository,
                            fallback = {
                                DeepReadArticle(
                                    title = title,
                                    output = data,
                                    palette = palette,
                                    fontFamily = readingFontFamily,
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
                            modifier = noticeModifier,
                        )
                        runError != null && !complete -> DeepReadPartialErrorNotice(
                            error = runError.orEmpty(),
                            onRetry = { runAll(force = true) },
                            modifier = noticeModifier,
                        )
                    }
                }
            }
        }
    }
}

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
    return copy(sectionStates = states)
}

@Composable
private fun RunningStageNotice(
    stages: Map<DeepReadGenerationStage, me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSectionState>,
    modifier: Modifier = Modifier,
) {
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
            "正在生成：${activeStage.label}",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DeepReadPartialErrorNotice(error: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
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
                Text("重试")
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DeepReadTemplateArticle(
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    fontCss: String,
    customTemplateHtml: String? = null,
    fontRepository: SlidesFontRepository,
    fallback: @Composable () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var failedImageUrls by remember(output) { mutableStateOf<Set<String>>(emptySet()) }
    val displayOutput = remember(output, failedImageUrls) {
        output.withoutTemplateImages(failedImageUrls)
    }
    val rendered = remember(title, displayOutput, fontCss, customTemplateHtml) {
        runCatching {
            if (customTemplateHtml != null) {
                DeepReadTemplateRenderer.renderCustom(title, displayOutput, customTemplateHtml, fontCss)
            } else {
                DeepReadTemplateRenderer.renderEditorialSlant(title, displayOutput, fontCss)
            }
        }.getOrNull()
    }
    var failed by remember(rendered) { mutableStateOf(rendered == null) }
    if (failed || rendered == null) {
        fallback()
        return
    }
    key(rendered.html.hashCode()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.loadsImagesAutomatically = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val url = request?.url?.toString().orEmpty()
                            val mainFrame = request?.isForMainFrame == true
                            if (mainFrame && url == DEEP_READ_TEMPLATE_BASE_URL) return null
                            if (!mainFrame) {
                                interceptBuiltInDeepReadFont(view, url)?.let { return it }
                                fontRepository.interceptFontRequest(request)?.let { return it }
                                if (url in rendered.allowedImageUrls) return null
                            }
                            return emptyWebResponse()
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            if (url == DEEP_READ_TEMPLATE_BASE_URL) return false
                            val scheme = request?.url?.scheme
                            if ((scheme == "http" || scheme == "https") && url in rendered.allowedLinkUrls) {
                                runCatching { uriHandler.openUri(url) }
                                return true
                            }
                            return true
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            val url = request?.url?.toString().orEmpty()
                            when {
                                request?.isForMainFrame == true -> failed = true
                                url in rendered.allowedImageUrls -> failedImageUrls = failedImageUrls + url
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            val url = request?.url?.toString().orEmpty()
                            if (request?.isForMainFrame == true) {
                                failed = true
                            } else if (url in rendered.allowedImageUrls) {
                                failedImageUrls = failedImageUrls + url
                            }
                        }
                    }
                    loadDataWithBaseURL(
                        DEEP_READ_TEMPLATE_BASE_URL,
                        rendered.html,
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            },
            update = {},
        )
    }
}

private fun DeepReadOutput.withoutTemplateImages(urls: Set<String>): DeepReadOutput {
    if (urls.isEmpty()) return this
    return copy(
        heroImageUrl = heroImageUrl?.takeUnless { it in urls },
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

private fun emptyWebResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

private fun openHttpUrl(uriHandler: androidx.compose.ui.platform.UriHandler, url: String) {
    val scheme = runCatching { android.net.Uri.parse(url).scheme }.getOrNull()
    if (scheme == "http" || scheme == "https") {
        runCatching { uriHandler.openUri(url) }
    }
}

private const val DEEP_READ_TEMPLATE_BASE_URL = "https://amberagent.local/deepread/"
private const val DEEP_READ_BUILTIN_SERIF_FONT_URL = "https://amberagent.local/deepread-fonts/noto_serif_sc.otf"
private const val DEEP_READ_SLIDES_FONT_HOST = "amberagent.local"

private fun interceptBuiltInDeepReadFont(view: WebView?, url: String): WebResourceResponse? {
    if (url != DEEP_READ_BUILTIN_SERIF_FONT_URL) return null
    val input = view?.context?.resources?.openRawResource(R.font.noto_serif_sc) ?: return emptyWebResponse()
    return WebResourceResponse("font/otf", null, input)
}

@Composable
private fun DeepReadConfirmation(
    modifier: Modifier,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeepReadArticle(
    title: String,
    output: DeepReadOutput,
    palette: MagazinePalette,
    fontFamily: FontFamily?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRetrySection: (DeepReadGenerationStage) -> Unit,
) {
    val verifiedImageUrls = remember(output.imageAssets) { output.verifiedImageUrls() }
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
    val verifiedImageUrls = remember(output.imageAssets) { output.verifiedImageUrls() }
    val image = output.heroImageUrl?.takeIf { it in verifiedImageUrls }
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
                    contentDescription = output.heroCaption ?: title,
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
                output.heroCaption?.takeIf { it.isNotBlank() }?.let { caption ->
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
                    if (!point.supporting.isNullOrBlank()) {
                        Text(
                            point.supporting,
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
        if (!analysis.coreDispute.isNullOrBlank()) {
            Text(
                analysis.coreDispute,
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
        if (!analysis.implications.isNullOrBlank()) {
            Text(
                analysis.implications,
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
private fun DeepReadError(error: String, modifier: Modifier, onRetry: () -> Unit) {
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
            Button(onClick = onRetry) { Text("重试") }
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
private fun rememberDeepReadTemplateFontCss(
    mode: TodayBoardReadingFontMode,
    fontPackId: String?,
    fontStates: List<FontPackState>,
): String = remember(mode, fontPackId, fontStates) {
    when (mode) {
        TodayBoardReadingFontMode.SYSTEM -> deepReadFontVars(
            serif = "\"Noto Serif SC\",\"Source Han Serif SC\",\"Songti SC\",serif",
            sans = "\"PingFang SC\",\"Source Han Sans SC\",\"Noto Sans SC\",system-ui,sans-serif",
        )

        TodayBoardReadingFontMode.SERIF -> """
            @font-face{
              font-family:"AmberDeepReadSerif";
              src:url("$DEEP_READ_BUILTIN_SERIF_FONT_URL") format("opentype");
              font-weight:400;
              font-style:normal;
              font-display:swap;
            }
            ${deepReadFontVars(
            serif = "\"AmberDeepReadSerif\",\"Noto Serif SC\",\"Source Han Serif SC\",\"Songti SC\",serif",
            sans = "\"PingFang SC\",\"Source Han Sans SC\",\"Noto Sans SC\",system-ui,sans-serif",
        )}
        """.trimIndent()

        TodayBoardReadingFontMode.SLIDES_PACK -> {
            val state = fontStates
                .firstOrNull { it.pack.id == fontPackId && it.installed }
                ?.takeIf { File(it.installedPath.orEmpty()).isFile }
            if (state == null) {
                deepReadFontVars(
                    serif = "\"Noto Serif SC\",\"Source Han Serif SC\",\"Songti SC\",serif",
                    sans = "\"PingFang SC\",\"Source Han Sans SC\",\"Noto Sans SC\",system-ui,sans-serif",
                )
            } else {
                val family = "AmberDeepRead-${state.pack.id}"
                val format = when (state.pack.fileName.substringAfterLast('.', "").lowercase()) {
                    "otf" -> "opentype"
                    "ttf" -> "truetype"
                    "woff" -> "woff"
                    "woff2" -> "woff2"
                    else -> "truetype"
                }
                val source = "https://$DEEP_READ_SLIDES_FONT_HOST/fonts/${state.pack.id}/${state.pack.fileName}"
                val serif = "\"$family\",\"Noto Serif SC\",\"Source Han Serif SC\",\"Songti SC\",serif"
                val sans = "\"$family\",\"PingFang SC\",\"Source Han Sans SC\",\"Noto Sans SC\",system-ui,sans-serif"
                """
                    @font-face{
                      font-family:"$family";
                      src:url("$source") format("$format");
                      font-weight:400;
                      font-style:normal;
                      font-display:swap;
                    }
                    ${deepReadFontVars(serif = serif, sans = sans)}
                """.trimIndent()
            }
        }
    }
}

private fun deepReadFontVars(serif: String, sans: String): String =
    """
        :root{
          --deep-read-serif:$serif;
          --deep-read-sans:$sans;
        }
    """.trimIndent()

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

package app.amber.agent
import app.amber.agent.R

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import kotlinx.serialization.Serializable
import app.amber.highlight.Highlighter
import app.amber.highlight.LocalHighlighter
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.event.AppEvent
import app.amber.core.event.AppEventBus
import app.amber.core.memory.dream.MemoryDreamNotifier
import app.amber.feature.ui.activity.SafeModeActivity
import app.amber.feature.ui.components.ui.TTSController
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalSharedTransitionScope
import app.amber.feature.ui.context.LocalTTSState
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.hooks.containsPreference
import app.amber.feature.ui.hooks.readBooleanPreference
import app.amber.feature.ui.hooks.readStringPreference
import app.amber.feature.ui.hooks.rememberCustomTtsState
import app.amber.core.utils.openUrl
import app.amber.feature.ui.pages.assistant.AssistantPage
import app.amber.feature.ui.pages.assistant.detail.AssistantBasicPage
import app.amber.feature.ui.pages.assistant.detail.AssistantDetailPage
import app.amber.feature.ui.pages.assistant.detail.AssistantExtensionsPage
import app.amber.feature.ui.pages.assistant.detail.AssistantLocalToolPage
import app.amber.feature.ui.pages.assistant.detail.AssistantMcpPage
import app.amber.feature.ui.pages.assistant.detail.AssistantMemoryPage
import app.amber.feature.ui.pages.assistant.detail.AssistantPromptPage
import app.amber.feature.ui.pages.assistant.detail.AssistantRequestPage
import app.amber.feature.ui.pages.backup.BackupPage
import app.amber.feature.ui.pages.chat.ChatPage
import app.amber.feature.ui.pages.debug.DebugPage
import app.amber.feature.ui.pages.developer.DeveloperPage
import app.amber.feature.ui.pages.extensions.ExtensionsPage
import app.amber.feature.ui.pages.extensions.PromptPage
import app.amber.feature.ui.pages.extensions.QuickMessagesPage
import app.amber.feature.ui.pages.extensions.SkillDetailPage
import app.amber.feature.ui.pages.extensions.SkillsPage
import app.amber.feature.ui.pages.favorite.FavoritePage
import app.amber.feature.ui.pages.history.HistoryPage
import app.amber.feature.ui.pages.imggen.ImageGenPage
import app.amber.feature.ui.pages.live.LiveCompanionPage
import app.amber.feature.ui.pages.log.LogPage
import app.amber.feature.ui.pages.miniapp.MiniAppListPage
import app.amber.feature.ui.pages.miniapp.MiniAppRunnerPage
import app.amber.feature.ui.pages.miniapp.MiniAppSettingsPage
import app.amber.feature.ui.pages.search.SearchPage
import app.amber.feature.ui.pages.setting.SettingAboutPage
import app.amber.feature.ui.pages.setting.SettingAgentExecutionPage
import app.amber.feature.ui.pages.setting.SettingAgentExtensionsPage
import app.amber.feature.ui.pages.setting.SettingAgentMemoryPage
import app.amber.feature.ui.pages.setting.SettingAgentMemoryCompactionPage
import app.amber.feature.ui.pages.setting.SettingAgentMemoryLibraryPage
import app.amber.feature.ui.pages.setting.SettingAgentMemoryRecallPage
import app.amber.feature.ui.pages.setting.SettingAgentMemoryWorkerPage
import app.amber.feature.ui.pages.setting.SettingAgentPermissionsPage
import app.amber.feature.ui.pages.setting.SettingAgentRuntimeTasksPage
import app.amber.feature.ui.pages.setting.SettingCronTasksPage
import app.amber.feature.ui.pages.setting.SettingDisplayPage
import app.amber.feature.ui.pages.setting.SettingExperimentalICloudPage
import app.amber.feature.ui.pages.setting.SettingExperimentalModelCouncilPage
import app.amber.feature.ui.pages.setting.SettingExperimentalOfficeProPage
import app.amber.feature.ui.pages.setting.SettingExperimentalPage
import app.amber.feature.ui.pages.setting.SettingExperimentalSubAgentPage
import app.amber.feature.ui.pages.setting.SettingExperimentalWebMountPage
import app.amber.feature.ui.pages.board.TodayBoardPage
import app.amber.feature.ui.pages.board.SettingTodayBoardPage
import app.amber.feature.ui.pages.board.DeepReadScreen
import app.amber.feature.ui.pages.board.DeepReadHistoryPage
import app.amber.feature.ui.pages.board.DeepReadTemplateWorkbenchPage
import app.amber.feature.board.hotlist.deepread.DeepReadNotifier
import app.amber.feature.board.worker.BoardNotifier
import app.amber.feature.ui.pages.setting.SettingFilesPage
import app.amber.feature.ui.pages.setting.SettingMcpPage
import app.amber.feature.ui.pages.setting.SettingModelPage
import app.amber.feature.ui.pages.setting.SettingPage
import app.amber.feature.ui.pages.setting.SettingProviderDetailPage
import app.amber.feature.ui.pages.setting.SettingProviderPage
import app.amber.feature.ui.pages.setting.SettingSandboxPage
import app.amber.feature.ui.pages.setting.SettingSearchPage
import app.amber.feature.ui.pages.setting.SettingSlidesFontPage
import app.amber.feature.ui.pages.setting.SettingSystemAccessPage
import app.amber.feature.ui.pages.setting.SettingTTSPage
import app.amber.feature.ui.pages.setting.SettingWebPage
import app.amber.feature.ui.pages.share.handler.ShareHandlerPage
import app.amber.feature.ui.pages.stats.StatsPage
import app.amber.feature.ui.pages.translator.TranslatorPage
import app.amber.feature.ui.pages.webview.WebViewPage
import app.amber.feature.ui.theme.LocalDarkMode
import app.amber.feature.ui.theme.RikkahubTheme
import app.amber.core.utils.CrashHandler
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"

class RouteActivity : ComponentActivity() {
    private val highlighter by inject<Highlighter>()
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsAggregator>()
    private val oauthCallbackDispatcher by inject<app.amber.feature.webmount.oauth.OAuthCallbackDispatcher>()
    private var navStack: MutableList<NavKey>? = null
    private var newIntentHandler: ((Intent) -> Unit)? = null

    // Volume key listener registry — last registered handler wins
    internal val volumeKeyListeners = mutableListOf<(isVolumeUp: Boolean) -> Boolean>()

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isVolumeUp = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> true
                KeyEvent.KEYCODE_VOLUME_DOWN -> false
                else -> return super.dispatchKeyEvent(event)
            }
            if (volumeKeyListeners.lastOrNull()?.invoke(isVolumeUp) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        if (CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        // Phase 2 M2.0.3 fix: cold-start OAuth callback (e.g. AmberAgent's
        // process was killed during the browser handoff) lands here via
        // ACTION_VIEW on the first onCreate, NOT onNewIntent. Dispatch the
        // URI so WebMountOAuthClient's resume collector can complete the
        // token exchange from the persisted PendingOAuthEntry. The
        // dispatcher's SharedFlow is replay=1 + WebMountOAuthClient is
        // eagerly constructed at Koin start, so the event lands on a live
        // subscriber regardless of dispatch ordering.
        intent?.data?.takeIf { it.scheme == "amberagent" && it.host == "oauth" }?.let { uri ->
            oauthCallbackDispatcher.dispatch(uri)
        }
        setContent {
            RikkahubTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        .components {
                            add(OkHttpNetworkFetcherFactory(
                                callFactory = { okHttpClient },
                                cacheStrategy = { CacheControlCacheStrategy() },
                            ))
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                add(AnimatedImageDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes()
            }
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    @Composable
    private fun ShareHandler(backStack: MutableList<NavKey>, currentIntent: Intent?) {
        val shareAction = remember(currentIntent) { currentIntent?.action }
        val shareText = remember(currentIntent) { currentIntent?.extractSharedText().orEmpty() }
        val streamUris = remember(currentIntent) {
            when (currentIntent?.action) {
                Intent.ACTION_SEND -> currentIntent.extractSingleStreamUri().orEmpty()
                Intent.ACTION_SEND_MULTIPLE -> currentIntent.extractMultipleStreamUris().orEmpty()
                else -> emptyList()
            }
        }

        LaunchedEffect(shareAction, shareText, streamUris) {
            when (shareAction) {
                Intent.ACTION_SEND,
                Intent.ACTION_SEND_MULTIPLE -> {
                    backStack.add(Screen.ShareHandler(text = shareText, streamUris = streamUris))
                }

                Intent.ACTION_PROCESS_TEXT -> {
                    backStack.add(Screen.ShareHandler(text = shareText, streamUri = null))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // WebMount OAuth callback: amberagent://oauth/<provider>?code=...&state=...
        // Dispatched before any other handler so the awaiting OAuth flow gets
        // its callback even if other branches would also touch this intent.
        intent.data?.takeIf { it.scheme == "amberagent" && it.host == "oauth" }?.let { uri ->
            oauthCallbackDispatcher.dispatch(uri)
        }
        // Navigate to the chat screen if a conversation ID is provided
        intent.getStringExtra("conversationId")?.let { text ->
            navStack?.add(Screen.Chat(text))
        }
        if (intent.getBooleanExtra(MemoryDreamNotifier.EXTRA_OPEN_AGENT_MEMORY, false)) {
            navStack?.add(Screen.SettingAgentMemory)
        }
        if (intent.getBooleanExtra(BoardNotifier.EXTRA_OPEN_TODAY_BOARD, false)) {
            navStack?.add(Screen.TodayBoard)
        }
        deepReadScreenFromIntent(intent)?.let { screen ->
            navStack?.add(screen)
        }
        newIntentHandler?.invoke(intent)
    }

    private fun deepReadScreenFromIntent(intent: Intent): Screen.DeepRead? {
        if (!intent.getBooleanExtra(DeepReadNotifier.EXTRA_OPEN_DEEP_READ, false)) return null
        val topicId = intent.getStringExtra(DeepReadNotifier.EXTRA_TOPIC_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val title = intent.getStringExtra(DeepReadNotifier.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val sourceUrl = intent.getStringExtra(DeepReadNotifier.EXTRA_SOURCE_URL)
            ?.takeIf { it.isNotBlank() }
        return Screen.DeepRead(
            topicId = topicId,
            title = title,
            sourceUrl = sourceUrl,
            fromHistory = intent.getBooleanExtra(DeepReadNotifier.EXTRA_FROM_HISTORY, true),
        )
    }

    @Composable
    fun AppRoutes() {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val tts = rememberCustomTtsState()
        val eventBus = koinInject<AppEventBus>()

        val startScreen = remember {
            if (intent.getBooleanExtra(MemoryDreamNotifier.EXTRA_OPEN_AGENT_MEMORY, false)) {
                return@remember Screen.SettingAgentMemory
            }
            if (intent.getBooleanExtra(BoardNotifier.EXTRA_OPEN_TODAY_BOARD, false)) {
                return@remember Screen.TodayBoard
            }
            deepReadScreenFromIntent(intent)?.let { screen ->
                return@remember screen
            }
            val legacyCreateNew = if (containsPreference(LEGACY_CREATE_NEW_CONVERSATION_ON_START_PREF)) {
                readBooleanPreference(LEGACY_CREATE_NEW_CONVERSATION_ON_START_PREF, true)
            } else {
                null
            }
            val startMode = migrateLaunchStartMode(
                storedMode = readStringPreference(LAUNCH_START_MODE_PREF),
                legacyCreateNewConversationOnStart = legacyCreateNew,
            )
            resolveLaunchStartScreen(
                mode = startMode,
                lastConversationId = readStringPreference(LAST_CONVERSATION_ID_PREF),
                newConversationId = Uuid.random().toString(),
            )
        }

        val backStack = rememberNavBackStack(startScreen)
        var currentIntent by remember { mutableStateOf(intent) }
        SideEffect {
            this@RouteActivity.navStack = backStack
            this@RouteActivity.newIntentHandler = { currentIntent = it }
        }
        LaunchedEffect(backStack, tts) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.Speak -> tts.speak(event.text)
                    is AppEvent.OpenDeepRead -> backStack.add(
                        Screen.DeepRead(
                            topicId = event.topicId,
                            title = event.title,
                            sourceUrl = event.sourceUrl,
                            forceRegenerate = event.forceRegenerate,
                        )
                    )
                }
            }
        }

        ShareHandler(backStack, currentIntent)
        val appUriHandler = remember {
            object : UriHandler {
                override fun openUri(uri: String) {
                    this@RouteActivity.openUrl(uri)
                }
            }
        }

        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalUriHandler provides appUriHandler,
                LocalNavController provides Navigator(backStack),
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalHighlighter provides highlighter,
                LocalToaster provides toastState,
                LocalTTSState provides tts,
            ) {
                Toaster(
                    state = toastState,
                    darkTheme = LocalDarkMode.current,
                    richColors = true,
                    alignment = Alignment.TopCenter,
                    showCloseButton = true,
                )
                TTSController()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onBack = { backStack.removeLastOrNull() },
                        transitionSpec = {
                            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                            else {
                                // Standard push: previous page slides FULLY off-screen so the
                                // two pages don't visually overlap mid-transition. The old
                                // `-it / 2` (half-out) + scaleOut + fadeOut combo had the
                                // outgoing page sit in the left half of the screen during the
                                // transition (visible "torn" frame the user complained about),
                                // and the three simultaneous animation properties (offset +
                                // scale + alpha) were heavy enough to drop frames on
                                // expensive entry pages like Stats.
                                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                            }
                        },
                        popTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        predictivePopTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        entryProvider = entryProvider {
                            entry<Screen.Chat>(
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                        + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                ChatPage(
                                    id = Uuid.parse(key.id),
                                    text = key.text,
                                    files = key.files.map { it.toUri() },
                                    nodeId = key.nodeId?.let { Uuid.parse(it) }
                                )
                            }

                            entry<Screen.ShareHandler> { key ->
                                ShareHandlerPage(
                                    text = key.text,
                                    streamUris = key.streamUris.ifEmpty {
                                        key.streamUri?.let { listOf(it) }.orEmpty()
                                    }
                                )
                            }

                            entry<Screen.History> {
                                HistoryPage()
                            }

                            entry<Screen.Favorite> {
                                FavoritePage()
                            }

                            entry<Screen.Assistant> {
                                AssistantPage()
                            }

                            entry<Screen.AssistantDetail> { key ->
                                AssistantDetailPage(key.id)
                            }

                            entry<Screen.AssistantBasic> { key ->
                                AssistantBasicPage(key.id)
                            }

                            entry<Screen.AssistantPrompt> { key ->
                                AssistantPromptPage(key.id)
                            }

                            entry<Screen.AssistantMemory> { key ->
                                AssistantMemoryPage(key.id)
                            }

                            entry<Screen.AssistantRequest> { key ->
                                AssistantRequestPage(key.id)
                            }

                            entry<Screen.AssistantMcp> { key ->
                                AssistantMcpPage(key.id)
                            }

                            entry<Screen.AssistantLocalTool> { key ->
                                AssistantLocalToolPage(key.id)
                            }

                            entry<Screen.AssistantInjections> { key ->
                                AssistantExtensionsPage(key.id)
                            }

                            entry<Screen.Translator> {
                                TranslatorPage()
                            }

                            entry<Screen.LiveCompanion> {
                                LiveCompanionPage()
                            }

                            entry<Screen.Setting> {
                                SettingPage()
                            }

                            entry<Screen.Backup> {
                                BackupPage()
                            }

                            entry<Screen.ImageGen> {
                                ImageGenPage()
                            }

                            entry<Screen.WebView> { key ->
                                WebViewPage(key.url, key.content)
                            }

                            entry<Screen.SettingDisplay> {
                                SettingDisplayPage()
                            }

                            entry<Screen.SettingProvider> {
                                SettingProviderPage()
                            }

                            entry<Screen.SettingProviderDetail> { key ->
                                val id = Uuid.parse(key.providerId)
                                SettingProviderDetailPage(id = id)
                            }

                            entry<Screen.SettingModels> {
                                SettingModelPage()
                            }

                            entry<Screen.SettingAbout> {
                                SettingAboutPage()
                            }

                            entry<Screen.SettingAgentMemory> {
                                SettingAgentMemoryPage()
                            }

                            entry<Screen.SettingAgentMemoryRecall> {
                                SettingAgentMemoryRecallPage()
                            }

                            entry<Screen.SettingAgentMemoryWorker> {
                                SettingAgentMemoryWorkerPage()
                            }

                            entry<Screen.SettingAgentMemoryCompaction> {
                                SettingAgentMemoryCompactionPage()
                            }

                            entry<Screen.SettingAgentMemoryLibrary> {
                                SettingAgentMemoryLibraryPage()
                            }

                            entry<Screen.SettingAgentExtensions> {
                                SettingAgentExtensionsPage()
                            }

                            entry<Screen.SettingSlidesFonts> {
                                SettingSlidesFontPage()
                            }

                            entry<Screen.SettingCronTasks> {
                                SettingCronTasksPage()
                            }

                            entry<Screen.SettingAgentRuntimeTasks> {
                                SettingAgentRuntimeTasksPage()
                            }

                            entry<Screen.SettingAgentExecution> {
                                SettingAgentExecutionPage()
                            }

                            entry<Screen.SettingAgentPermissions> {
                                SettingAgentPermissionsPage()
                            }

                            entry<Screen.SettingSearch> {
                                SettingSearchPage()
                            }

                            entry<Screen.SettingTTS> {
                                SettingTTSPage()
                            }

                            entry<Screen.SettingMcp> {
                                SettingMcpPage()
                            }

                            entry<Screen.SettingFiles> {
                                SettingFilesPage()
                            }

                            entry<Screen.SettingSandbox> {
                                SettingSandboxPage()
                            }

                            entry<Screen.SettingExperimental> {
                                SettingExperimentalPage()
                            }

                            entry<Screen.SettingExperimentalICloud> {
                                SettingExperimentalICloudPage()
                            }

                            entry<Screen.SettingExperimentalOfficePro> {
                                SettingExperimentalOfficeProPage()
                            }

                            entry<Screen.SettingExperimentalSubAgent> {
                                SettingExperimentalSubAgentPage()
                            }

                            entry<Screen.SettingExperimentalModelCouncil> {
                                SettingExperimentalModelCouncilPage()
                            }

                            entry<Screen.SettingExperimentalWebMount> {
                                SettingExperimentalWebMountPage()
                            }

                            entry<Screen.TodayBoard> {
                                TodayBoardPage()
                            }

                            entry<Screen.DeepRead> { key ->
                                DeepReadScreen(
                                    topicId = key.topicId,
                                    title = key.title,
                                    sourceUrl = key.sourceUrl,
                                    initialForceRegenerate = key.forceRegenerate,
                                    fromHistory = key.fromHistory,
                                )
                            }

                            entry<Screen.DeepReadHistory> {
                                DeepReadHistoryPage()
                            }

                            entry<Screen.SettingTodayBoard> {
                                SettingTodayBoardPage()
                            }

                            entry<Screen.SettingTodayBoardDetail> { key ->
                                SettingTodayBoardPage(paneRoute = key.pane)
                            }

                            entry<Screen.DeepReadTemplateWorkbench> {
                                DeepReadTemplateWorkbenchPage()
                            }

                            entry<Screen.MiniAppList> {
                                MiniAppListPage()
                            }

                            entry<Screen.MiniAppRunner> { key ->
                                MiniAppRunnerPage(appId = key.appId)
                            }

                            entry<Screen.MiniAppSettings> {
                                MiniAppSettingsPage()
                            }

                            entry<Screen.MiniAppSettingsDetail> { key ->
                                MiniAppSettingsPage(groupRoute = key.group)
                            }

                            entry<Screen.SettingSystemAccess> {
                                SettingSystemAccessPage()
                            }

                            entry<Screen.SettingWeb> {
                                SettingWebPage()
                            }

                            entry<Screen.Developer> {
                                DeveloperPage()
                            }

                            entry<Screen.Debug> {
                                DebugPage()
                            }

                            entry<Screen.Log> {
                                LogPage()
                            }

                            entry<Screen.Extensions> {
                                ExtensionsPage()
                            }

                            entry<Screen.QuickMessages> {
                                QuickMessagesPage()
                            }

                            entry<Screen.Prompts> {
                                PromptPage()
                            }

                            entry<Screen.Skills> {
                                SkillsPage()
                            }

                            entry<Screen.SkillDetail> { key ->
                                SkillDetailPage(skillName = key.skillName)
                            }

                            entry<Screen.MessageSearch> {
                                SearchPage()
                            }

                            entry<Screen.Stats> {
                                StatsPage()
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun Intent.extractSharedText(): String {
    val text = getStringExtra(Intent.EXTRA_TEXT)
        ?: getStringExtra(Intent.EXTRA_HTML_TEXT)
        ?: getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        ?: ""
    val subject = getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
    return listOf(subject, text)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n\n")
}

@Suppress("DEPRECATION")
private fun Intent.extractSingleStreamUri(): List<String> =
    getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        ?.let { listOf(it.toString()) }
        .orEmpty()

@Suppress("DEPRECATION")
private fun Intent.extractMultipleStreamUris(): List<String> =
    getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        ?.map { it.toString() }
        .orEmpty()

sealed interface Screen : NavKey {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null
    ) : Screen

    @Serializable
    data class ShareHandler(
        val text: String,
        val streamUri: String? = null,
        val streamUris: List<String> = emptyList()
    ) : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data class AssistantBasic(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    @Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object LiveCompanion : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data class WebView(val url: String = "", val content: String = "") : Screen

    @Serializable
    data object SettingDisplay : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingAgentMemory : Screen

    @Serializable
    data object SettingAgentMemoryRecall : Screen

    @Serializable
    data object SettingAgentMemoryWorker : Screen

    @Serializable
    data object SettingAgentMemoryCompaction : Screen

    @Serializable
    data object SettingAgentMemoryLibrary : Screen

    @Serializable
    data object SettingAgentExtensions : Screen

    @Serializable
    data object SettingSlidesFonts : Screen

    @Serializable
    data object SettingCronTasks : Screen

    @Serializable
    data object SettingAgentRuntimeTasks : Screen

    @Serializable
    data object SettingAgentExecution : Screen

    @Serializable
    data object SettingAgentPermissions : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data object SettingTTS : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingSandbox : Screen

    @Serializable
    data object SettingExperimental : Screen

    @Serializable
    data object SettingExperimentalICloud : Screen

    @Serializable
    data object SettingExperimentalOfficePro : Screen

    @Serializable
    data object SettingExperimentalSubAgent : Screen

    @Serializable
    data object SettingExperimentalModelCouncil : Screen

    @Serializable
    data object SettingExperimentalWebMount : Screen

    @Serializable
    data object SettingSystemAccess : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object Developer : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    @Serializable
    data object Extensions : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    @Serializable
    data object Skills : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen

    @Serializable
    data object TodayBoard : Screen

    @Serializable
    data object MiniAppList : Screen

    @Serializable
    data class MiniAppRunner(val appId: String) : Screen

    @Serializable
    data object MiniAppSettings : Screen

    @Serializable
    data class MiniAppSettingsDetail(val group: String) : Screen

    @Serializable
    data object DeepReadTemplateWorkbench : Screen

    @Serializable
    data class DeepRead(
        val topicId: String,
        val title: String,
        val sourceUrl: String? = null,
        val forceRegenerate: Boolean = false,
        val fromHistory: Boolean = false,
    ) : Screen

    @Serializable
    data object DeepReadHistory : Screen

    @Serializable
    data object SettingTodayBoard : Screen

    @Serializable
    data class SettingTodayBoardDetail(val pane: String) : Screen
}

package app.amber.agent

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.amber.feature.ui.components.ds.enableAmberHdrWindow
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
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
import app.amber.highlight.Highlighter
import app.amber.highlight.LocalHighlighter
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.event.AppEvent
import app.amber.core.event.AppEventBus
import app.amber.core.memory.dream.MemoryDreamNotifier
import app.amber.feature.ui.activity.SafeModeActivity
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalSharedTransitionScope
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.hooks.containsPreference
import app.amber.feature.ui.hooks.readBooleanPreference
import app.amber.feature.ui.hooks.readStringPreference
import app.amber.feature.ui.overlay.AppModalOverlayHost
import app.amber.feature.ui.overlay.LocalAppModalOverlayHost
import app.amber.feature.ui.overlay.rememberAppModalOverlayHostState
import app.amber.feature.ui.pages.live.LiveCompanionDeepLink
import app.amber.core.utils.openUrl
import app.amber.feature.ui.pages.novel.NovelLaunchCurtain
import app.amber.feature.board.worker.BoardNotifier
import app.amber.feature.runtime.RunTerminalStore
import app.amber.feature.ui.theme.LocalDarkMode
import app.amber.feature.ui.theme.AmberAgentTheme
import app.amber.feature.ui.adaptive.rememberAdaptiveSceneStrategies
import app.amber.feature.ui.theme.rememberThemePageChromeDecorator
import app.amber.core.utils.base64Encode
import app.amber.core.utils.CrashHandler
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"
private const val ROUTE_TRANSITION_DURATION_MILLIS = 320
private val RouteTransitionEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val RouteTransitionSpec = tween<IntOffset>(
    durationMillis = ROUTE_TRANSITION_DURATION_MILLIS,
    easing = RouteTransitionEasing,
)
internal val RouteTransitionSpecFloat = tween<Float>(
    durationMillis = ROUTE_TRANSITION_DURATION_MILLIS,
    easing = RouteTransitionEasing,
)

internal fun <S> AnimatedContentTransitionScope<S>.routePushTransition(): ContentTransform =
    slideInHorizontally(animationSpec = RouteTransitionSpec) { it } togetherWith
        slideOutHorizontally(animationSpec = RouteTransitionSpec) { -it }

internal fun <S> AnimatedContentTransitionScope<S>.routePopTransition(): ContentTransform =
    slideInHorizontally(animationSpec = RouteTransitionSpec) { -it } togetherWith
        slideOutHorizontally(animationSpec = RouteTransitionSpec) { it }

class RouteActivity : ComponentActivity() {
    private val highlighter by inject<Highlighter>()
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsAggregator>()
    private val oauthCallbackDispatcher by inject<app.amber.feature.webmount.oauth.OAuthCallbackDispatcher>()
    private val runTerminalStore by inject<RunTerminalStore>()
    private val conversationRepository by inject<app.amber.core.repository.ConversationRepository>()
    private var navStack: MutableList<NavKey>? = null
    private var newIntentHandler: ((Intent) -> Unit)? = null
    private var shareIntentConsumed = false

    // Volume key listener registry — last registered handler wins
    internal val volumeKeyListeners = mutableListOf<(isVolumeUp: Boolean) -> Boolean>()

    companion object {
        const val EXTRA_OPEN_CHAT_PROMPT = "openChatPrompt"
        const val EXTRA_OPEN_LIVE_COMPANION = "openLiveCompanion"
        private const val STATE_SHARE_INTENT_CONSUMED = "shareIntentConsumed"
    }

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
        // Do NOT use SystemBarStyle.auto(): it follows *system* night mode and
        // re-applies on config/insets updates. On ColorOS that race wins over the
        // Compose SideEffect, so a light app under a dark system theme keeps
        // white status-bar icons on a pale canvas. AmberAgentTheme re-applies
        // light()/dark() from the *app* theme on every recomposition; start with
        // light icons-on-dark-icons style as the cold-start default.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        enableAmberHdrWindow()
        shareIntentConsumed = savedInstanceState?.getBoolean(STATE_SHARE_INTENT_CONSUMED) ?: false
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
        // 冷路径 Live 深链只在"真首次创建"request 一次：startScreen 的 remember 计算块
        // 会在 Activity 重建（深色切换等未入 configChanges 的变更）时重跑，在那里发
        // request 会把同一次通知重放成多余的滚动+脉冲（Phase 3 复审 P2）。
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_OPEN_LIVE_COMPANION, false) == true) {
            LiveCompanionDeepLink.request()
        }
        // Keep the launch window visible until settings are ready so Compose's
        // first frame uses the saved theme. A timeout preserves the dummy
        // settings fallback without blocking the main thread.
        var contentReady = false
        val contentView = findViewById<View>(android.R.id.content)
        contentView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!contentReady) return false
                contentView.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        lifecycleScope.launch {
            withTimeoutOrNull(2_000) {
                settingsStore.settingsFlow.first { !it.init }
            }
            // W16-B: refresh dynamic launcher shortcuts after settings are
            // initialized; failures never block startup.
            lifecycleScope.launch {
                runCatching {
                    app.amber.feature.tools.DynamicShortcutPublisher.publish(
                        context = this@RouteActivity,
                        settingsStore = settingsStore,
                        conversationRepository = conversationRepository,
                    )
                }
            }
            setContent {
                AmberAgentTheme {
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
            contentReady = true
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SHARE_INTENT_CONSUMED, shareIntentConsumed)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shareIntentConsumed = false
        setIntent(intent)
        // WebMount OAuth callback: amberagent://oauth/<provider>?code=...&state=...
        // Dispatched before any other handler so the awaiting OAuth flow gets
        // its callback even if other branches would also touch this intent.
        intent.data?.takeIf { it.scheme == "amberagent" && it.host == "oauth" }?.let { uri ->
            oauthCallbackDispatcher.dispatch(uri)
        }
        // Notification deep links use the same parser as cold start. A
        // malformed conversation identity is ignored; a valid conversation is
        // always the exact landing target, even when its runId is stale or
        // belongs to another conversation (the latter only loses run focus).
        notificationDeepLinkFrom(intent)?.let { link ->
            navStack?.add(Screen.Chat(link.conversationId))
            validateNotificationRunOwnership(intent, link)
        }
        taskSessionScreenFromIntent(intent)?.let { screen ->
            navStack?.add(screen)
        }
        if (intent.getBooleanExtra(MemoryDreamNotifier.EXTRA_OPEN_AGENT_MEMORY, false)) {
            navStack?.add(Screen.SettingAgentMemory)
        }
        if (intent.getBooleanExtra(BoardNotifier.EXTRA_OPEN_TODAY_BOARD, false)) {
            navStack?.add(Screen.TodayBoard)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_LIVE_COMPANION, false)) {
            LiveCompanionDeepLink.request()
            if (navStack?.lastOrNull() != Screen.LiveCompanion) navStack?.add(Screen.LiveCompanion)
        }
        deepReadScreenFromIntent(intent)?.let { screen ->
            navStack?.add(screen)
        }
        app.amber.feature.novel.workspace.NovelWorkspaceNotificationRoute.screenFrom(intent)?.let { screen ->
            if (navStack?.lastOrNull() != screen) navStack?.add(screen)
        }
        app.amber.feature.tools.DynamicShortcutPublisher.screenFromIntent(intent)?.let { route ->
            navigateToShortcutRoute(route)
        }
        newIntentHandler?.invoke(intent)
    }

    /**
     * W16-B: launcher shortcut routes reuse the same landing semantics as
     * notifications - a conversation shortcut opens that exact conversation,
     * prompts prefill a fresh chat draft without bypassing the send gate.
     */
    private fun navigateToShortcutRoute(route: app.amber.feature.tools.DynamicShortcutPublisher.Route) {
        when (route) {
            app.amber.feature.tools.DynamicShortcutPublisher.Route.NewChat ->
                navStack?.add(Screen.Chat(Uuid.random().toString()))
            is app.amber.feature.tools.DynamicShortcutPublisher.Route.Conversation -> {
                // A stale shortcut must not resurrect a deleted conversation
                // with its old UUID; land on a fresh chat instead.
                lifecycleScope.launch {
                    val exists = runCatching {
                        conversationRepository.existsConversationById(route.conversationId)
                    }.getOrDefault(false)
                    if (exists) {
                        navStack?.add(Screen.Chat(route.conversationId.toString()))
                    } else {
                        navStack?.add(Screen.Chat(Uuid.random().toString()))
                    }
                }
            }
            is app.amber.feature.tools.DynamicShortcutPublisher.Route.QuickPrompt ->
                navStack?.add(Screen.Chat(Uuid.random().toString(), route.prompt.base64Encode()))
        }
    }

    private fun shortcutStartScreen(route: app.amber.feature.tools.DynamicShortcutPublisher.Route): Screen =
        when (route) {
            app.amber.feature.tools.DynamicShortcutPublisher.Route.NewChat ->
                Screen.Chat(Uuid.random().toString())
            is app.amber.feature.tools.DynamicShortcutPublisher.Route.Conversation -> {
                // Cold start runs before composition; a single indexed DB
                // lookup keeps this synchronous. Stale shortcuts land on a
                // fresh chat instead of resurrecting the deleted UUID.
                val exists = runCatching {
                    kotlinx.coroutines.runBlocking {
                        conversationRepository.existsConversationById(route.conversationId)
                    }
                }.getOrDefault(false)
                if (exists) Screen.Chat(route.conversationId.toString())
                else Screen.Chat(Uuid.random().toString())
            }
            is app.amber.feature.tools.DynamicShortcutPublisher.Route.QuickPrompt ->
                Screen.Chat(Uuid.random().toString(), route.prompt.base64Encode())
        }

    /**
     * P1-05: verify that a notification deep link's runId belongs to its
     * conversationId and is still live. Navigation always lands on the
     * conversation page (the safe spot for any conversationId); run-specific
     * focus (the runId extra) is only carried when the run exists, is not
     * terminal and matches the conversation — otherwise it is stripped so no
     * downstream consumer can act on a stale run. No popup, no blocking.
     * The stop action ownership check lives in ChatService.stopGeneration
     * via RunOwnershipRegistry.
     */
    private fun validateNotificationRunOwnership(intent: Intent, link: NotificationDeepLink) {
        val runId = link.runId ?: return
        lifecycleScope.launch {
            val run = runCatching { runTerminalStore.get(runId) }.getOrNull()
            val valid = notificationRunIsOwned(link, run)
            if (!valid) {
                Log.w(
                    "RouteActivity",
                    "notification deep link run ownership rejected: run=$runId conversation=${link.conversationId} " +
                        "state=${run?.state}",
                )
                // Skip run-specific focus: strip the runId so nothing else
                // can act on the missing / stale / mismatched run.
                intent.removeExtra("runId")
            }
        }
    }

    @Composable
    fun AppRoutes() {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val eventBus = koinInject<AppEventBus>()
        // One app-level modal layer above every route (and above the two-pane list) so a route's
        // modal scrim covers the whole interactive window. The owning route publishes the content;
        // the shell keeps no modal-specific state.
        val appModalOverlay = rememberAppModalOverlayHostState()

        val notificationLink = remember { notificationDeepLinkFrom(intent) }
        val startScreen = remember {
            // Standalone products (app.amber.novel / app.amber.deepread) resolve
            // only their own surface deep-links; everything else lands on the
            // product home.
            standaloneStartScreen(intent)?.let { screen ->
                return@remember screen
            }
            // Cold-start notification taps must win over launch-mode/last-
            // conversation resolution. This prevents a mismatched run from
            // being hidden by an unrelated last session.
            notificationLink?.let { link ->
                return@remember Screen.Chat(link.conversationId)
            }
            if (intent.getBooleanExtra(MemoryDreamNotifier.EXTRA_OPEN_AGENT_MEMORY, false)) {
                return@remember Screen.SettingAgentMemory
            }
            if (intent.getBooleanExtra(BoardNotifier.EXTRA_OPEN_TODAY_BOARD, false)) {
                return@remember Screen.TodayBoard
            }
            if (intent.getBooleanExtra(EXTRA_OPEN_LIVE_COMPANION, false)) {
                return@remember Screen.LiveCompanion
            }
            taskSessionScreenFromIntent(intent)?.let { screen ->
                return@remember screen
            }
            deepReadScreenFromIntent(intent)?.let { screen ->
                return@remember screen
            }
            app.amber.feature.novel.workspace.NovelWorkspaceNotificationRoute.screenFrom(intent)?.let { screen ->
                return@remember screen
            }
            app.amber.feature.tools.DynamicShortcutPublisher.screenFromIntent(intent)?.let { route ->
                return@remember shortcutStartScreen(route)
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

        // Route creation is synchronous, while run ownership is persisted and
        // therefore checked asynchronously. Both cold and warm paths call the
        // same validator; invalid run focus is stripped without changing the
        // exact conversation landing target.
        LaunchedEffect(notificationLink) {
            notificationLink?.let { link ->
                validateNotificationRunOwnership(intent, link)
            }
        }

        val backStack = rememberNavBackStack(
            *if (startScreen is Screen.Chat) arrayOf(Screen.SessionHome, startScreen)
            else arrayOf(startScreen)
        )
        val navigator = remember(backStack) { Navigator(backStack) }
        val navigationEntries = remember { appNavigationEntries() }
        var currentIntent by remember { mutableStateOf(intent) }
        SideEffect {
            this@RouteActivity.navStack = backStack
            this@RouteActivity.newIntentHandler = { currentIntent = it }
        }
        LaunchedEffect(backStack) {
            eventBus.events.collect { event ->
                when (event) {
                    // MCP OAuth 回调由 McpOAuthCoordinator 订阅消费，这里无需处理
                    is AppEvent.McpOAuthCallback -> Unit
                    is AppEvent.OpenDeepRead -> backStack.add(
                        Screen.DeepRead(
                            topicId = event.topicId,
                            title = event.title,
                            sourceUrl = event.sourceUrl,
                            forceRegenerate = event.forceRegenerate,
                        )
                    )
                    // P6-02: handled by the chat screen (ChatPage), which
                    // routes it to the conversation owning the source image.
                    is AppEvent.EditGeneratedImage -> Unit
                }
            }
        }

        RouteShareDelivery(
            currentIntent = currentIntent,
            isConsumed = { shareIntentConsumed },
            onDelivery = { backStack.add(it) },
            onConsumed = { shareIntentConsumed = true },
        )
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
                LocalNavController provides navigator,
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalHighlighter provides highlighter,
                LocalToaster provides toastState,
                LocalAppModalOverlayHost provides appModalOverlay,
            ) {
                Toaster(
                    state = toastState,
                    darkTheme = LocalDarkMode.current,
                    richColors = true,
                    alignment = Alignment.TopCenter,
                    showCloseButton = true,
                )
                Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                            rememberThemePageChromeDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize().weight(1f),
                        onBack = { backStack.removeLastOrNull() },
                        sceneStrategies = rememberAdaptiveSceneStrategies(),
                        transitionSpec = {
                            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                            else routePushTransition()
                        },
                        popTransitionSpec = { routePopTransition() },
                        predictivePopTransitionSpec = { routePopTransition() },
                        entryProvider = navigationEntries,
                    )
                }
                if (StandaloneSurfaces.current == StandaloneSurfaces.NOVEL) {
                    // Cold-launch seal curtain — once per launch, skipped under
                    // reduce-motion/TalkBack inside the composable itself.
                    var curtainDone by rememberSaveable { mutableStateOf(false) }
                    if (!curtainDone) {
                        NovelLaunchCurtain(
                            onFinish = { curtainDone = true },
                        )
                    }
                }
                AppModalOverlayHost(appModalOverlay)
                }
            }
        }
    }
}

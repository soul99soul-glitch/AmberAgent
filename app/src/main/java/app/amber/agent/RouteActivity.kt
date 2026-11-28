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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import androidx.fragment.app.FragmentActivity
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
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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
import app.amber.core.infra.PreferencesKeys
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.settingsStore
import app.amber.core.utils.JsonInstant
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
import androidx.core.content.edit
import app.amber.feature.ui.utils.ChatPerfTrace
import app.amber.feature.ui.utils.ChatPerfHomeEntry
import app.amber.feature.ui.utils.HOME_FIRST_PATH
import app.amber.feature.ui.utils.NATIVE_STRESS_PATH
import org.json.JSONObject
import app.amber.feature.ui.overlay.AppModalOverlayHost
import app.amber.feature.ui.overlay.LocalAppModalOverlayHost
import app.amber.feature.ui.overlay.rememberAppModalOverlayHostState
import app.amber.feature.ui.pages.live.LiveCompanionDeepLink
import app.amber.core.utils.openUrl
import app.amber.feature.macgateway.ProactiveDeepLinkInbox
import app.amber.feature.runtime.RunTerminalStore
import app.amber.feature.ui.theme.LocalDarkMode
import app.amber.feature.ui.theme.AmberAgentTheme
import app.amber.feature.ui.adaptive.rememberAdaptiveSceneStrategies
import app.amber.feature.ui.theme.rememberThemePageChromeDecorator
import app.amber.core.utils.base64Encode
import app.amber.core.utils.CrashHandler
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"
internal const val STARTUP_THEME_PREFS_NAME = "amber_agent.preferences"
internal const val STARTUP_THEME_SNAPSHOT_KNOWN_PREF = "startup_theme_snapshot_known"
internal const val STARTUP_THEME_REQUIRES_CUSTOM_DISPLAY_PREF = "startup_theme_requires_custom_display"
private const val MINIMAL_THEME_PROBE_TIMEOUT_MS = 500L
private const val CUSTOM_THEME_READY_TIMEOUT_MS = 2_000L
private val DEFAULT_STARTUP_DISPLAY_SETTING = DisplaySetting()
private const val ROUTE_TRANSITION_DURATION_MILLIS = 320
private val RouteTransitionEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val RouteTransitionSpec = tween<IntOffset>(
    durationMillis = ROUTE_TRANSITION_DURATION_MILLIS,
    easing = RouteTransitionEasing,
)

internal fun <S> AnimatedContentTransitionScope<S>.routePushTransition(): ContentTransform =
    slideInHorizontally(animationSpec = RouteTransitionSpec) { it } togetherWith
        slideOutHorizontally(animationSpec = RouteTransitionSpec) { -it }

internal fun <S> AnimatedContentTransitionScope<S>.routePopTransition(): ContentTransform =
    slideInHorizontally(animationSpec = RouteTransitionSpec) { -it } togetherWith
        slideOutHorizontally(animationSpec = RouteTransitionSpec) { it }

class RouteActivity : FragmentActivity() {
    private val highlighter by inject<Highlighter>()
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsAggregator>()
    private val oauthCallbackDispatcher by inject<app.amber.feature.webmount.oauth.OAuthCallbackDispatcher>()
    private val runTerminalStore by inject<RunTerminalStore>()
    private val conversationRepository by inject<app.amber.core.repository.ConversationRepository>()
    private val proactiveDeepLinkInbox by inject<ProactiveDeepLinkInbox>()
    private var navStack: MutableList<NavKey>? = null
    private var newIntentHandler: ((Intent) -> Unit)? = null
    private var shareIntentConsumed = false
    private var proactiveDeepLinkConsumed = false
    private var launchedFromProactiveDeepLink = false

    // Volume key listener registry — last registered handler wins
    internal val volumeKeyListeners = mutableListOf<(isVolumeUp: Boolean) -> Boolean>()

    companion object {
        const val EXTRA_OPEN_CHAT_PROMPT = "openChatPrompt"
        const val EXTRA_OPEN_LIVE_COMPANION = "openLiveCompanion"
        private const val STATE_SHARE_INTENT_CONSUMED = "shareIntentConsumed"
        private const val STATE_DETAIL_SIGNAL = "pendingProactiveSignal"
        private const val STATE_DETAIL_GATEWAY = "pendingProactiveGateway"
        private const val STATE_DETAIL_DEVICE = "pendingProactiveDevice"
        private const val STATE_PROACTIVE_DEEP_LINK_CONSUMED = "proactiveDeepLinkConsumed"
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
        val perfHome = ChatPerfHomeEntry.enabled && intent?.getStringExtra("perfPathId") == HOME_FIRST_PATH
        ChatPerfHomeEntry.attach(this, if (perfHome) HOME_FIRST_PATH else NATIVE_STRESS_PATH)
        if (BuildConfig.CHAT_PERF_TRACE && !perfHome) {
            ChatPerfTrace.initializeIfNeeded(
                this,
                intent?.getStringExtra("perfScenario") ?: "chat",
            )
            Log.i("AmberPerfFixture", "route-created savedState=${savedInstanceState != null}")
        }
        enableAmberHdrWindow()
        shareIntentConsumed = savedInstanceState?.getBoolean(STATE_SHARE_INTENT_CONSUMED) ?: false
        proactiveDeepLinkConsumed = savedInstanceState?.getBoolean(STATE_PROACTIVE_DEEP_LINK_CONSUMED) ?: false
        if (CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        savedInstanceState?.getString(STATE_DETAIL_SIGNAL)?.let { pending ->
            proactiveDeepLinkInbox.submit(pending, savedInstanceState.getString(STATE_DETAIL_GATEWAY),
                savedInstanceState.getString(STATE_DETAIL_DEVICE))
        }
        val coldSignalId = consumeProactiveDeepLink(
            intent = intent,
            inbox = proactiveDeepLinkInbox,
            alreadyConsumed = proactiveDeepLinkConsumed,
        )
        if (coldSignalId != null) {
            proactiveDeepLinkConsumed = true
            launchedFromProactiveDeepLink = true
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
        // Shrink the pre-draw gate to the minimal appearance snapshot needed to
        // avoid a theme flash:
        // - colorMode and amoledDark are read synchronously from SharedPreferences
        //   inside AmberAgentTheme.
        // - Only non-default DataStore appearance overrides (custom base family,
        //   accent color, themePack or text weight) require waiting for
        //   SettingsAggregator before the first draw.
        // Compose setContent runs immediately so composition and settings decoding
        // overlap instead of blocking serially.
        val currentSettings = settingsStore.settingsFlow.value
        val knownCustomTheme = readStartupThemeRequiresCustomDisplay()
        var contentReady = !currentSettings.init || knownCustomTheme == false
        val contentView = findViewById<View>(android.R.id.content)
        if (!contentReady) {
            contentView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!contentReady) return false
                    contentView.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            })
        }
        val firstFrameNotified = AtomicBoolean(false)
        val firstDrawListener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (!contentReady || !firstFrameNotified.compareAndSet(false, true)) return
                contentView.post {
                    val observer = contentView.viewTreeObserver
                    if (observer.isAlive) {
                        observer.removeOnDrawListener(this)
                    }
                    (application as? AmberAgentApp)?.notifyFirstFrameRendered()
                    scheduleDynamicShortcutRefresh()
                }
            }
        }
        contentView.viewTreeObserver.addOnDrawListener(firstDrawListener)

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

        if (!contentReady) {
            lifecycleScope.launch {
                val requiresCustomTheme = knownCustomTheme ?: withTimeoutOrNull(MINIMAL_THEME_PROBE_TIMEOUT_MS) {
                    probeMinimalStartupThemeRequiresCustomDisplay()
                } ?: true
                if (requiresCustomTheme) {
                    withTimeoutOrNull(CUSTOM_THEME_READY_TIMEOUT_MS) {
                        settingsStore.settingsFlow.first { !it.init }
                    }
                }
                contentReady = true
                contentView.postInvalidateOnAnimation()
            }
        }
        // Keep the lightweight startup theme snapshot in sync whenever persisted
        // display settings change so the next cold start avoids any DataStore probe.
        lifecycleScope.launch {
            settingsStore.settingsFlow
                .filterNot { it.init }
                .map(::requiresCustomStartupTheme)
                .distinctUntilChanged()
                .collect(::writeStartupThemeRequiresCustomDisplay)
        }
    }

    private fun scheduleDynamicShortcutRefresh() {
        // W16-B: refresh dynamic launcher shortcuts off the main thread after
        // the first frame and full settings initialization; failures never block startup.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                settingsStore.settingsFlow.first { !it.init }
                app.amber.feature.tools.DynamicShortcutPublisher.publish(
                    context = this@RouteActivity,
                    settingsStore = settingsStore,
                    conversationRepository = conversationRepository,
                )
            }
        }
    }

    private fun readStartupThemeRequiresCustomDisplay(): Boolean? {
        if (!containsPreference(STARTUP_THEME_SNAPSHOT_KNOWN_PREF)) return null
        return readBooleanPreference(STARTUP_THEME_REQUIRES_CUSTOM_DISPLAY_PREF, true)
    }

    private fun writeStartupThemeRequiresCustomDisplay(requiresCustom: Boolean) {
        getSharedPreferences(STARTUP_THEME_PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(STARTUP_THEME_SNAPSHOT_KNOWN_PREF, true)
            putBoolean(STARTUP_THEME_REQUIRES_CUSTOM_DISPLAY_PREF, requiresCustom)
        }
    }

    private suspend fun probeMinimalStartupThemeRequiresCustomDisplay(): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val prefs = applicationContext.settingsStore.data.first()
                val rawDisplay = prefs[PreferencesKeys.DISPLAY_SETTING]
                val display = rawDisplay?.let {
                    runCatching { JsonInstant.decodeFromString<DisplaySetting>(it) }.getOrNull()
                } ?: DisplaySetting()
                hasCustomDisplayAppearance(display)
            }.getOrDefault(true)
        }

    private fun requiresCustomStartupTheme(settings: Settings): Boolean =
        hasCustomDisplayAppearance(settings.displaySetting)

    private fun hasCustomDisplayAppearance(display: DisplaySetting): Boolean {
        return display.amberBaseFamily != DEFAULT_STARTUP_DISPLAY_SETTING.amberBaseFamily ||
            display.accentColor != DEFAULT_STARTUP_DISPLAY_SETTING.accentColor ||
            display.themePack != null ||
            display.textWeight != DEFAULT_STARTUP_DISPLAY_SETTING.textWeight
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SHARE_INTENT_CONSUMED, shareIntentConsumed)
        outState.putBoolean(STATE_PROACTIVE_DEEP_LINK_CONSUMED, proactiveDeepLinkConsumed)
        proactiveDeepLinkInbox.pending.value?.let { request ->
            outState.putString(STATE_DETAIL_SIGNAL, request.signalId)
            outState.putString(STATE_DETAIL_GATEWAY, request.gatewayId)
            outState.putString(STATE_DETAIL_DEVICE, request.deviceId)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shareIntentConsumed = false
        proactiveDeepLinkConsumed = false
        setIntent(intent)
        // WebMount OAuth callback: amberagent://oauth/<provider>?code=...&state=...
        // Dispatched before any other handler so the awaiting OAuth flow gets
        // its callback even if other branches would also touch this intent.
        intent.data?.takeIf { it.scheme == "amberagent" && it.host == "oauth" }?.let { uri ->
            oauthCallbackDispatcher.dispatch(uri)
        }
        launchedFromProactiveDeepLink = consumeProactiveDeepLink(
            intent = intent,
            inbox = proactiveDeepLinkInbox,
        ) != null
        proactiveDeepLinkConsumed = launchedFromProactiveDeepLink
        val notification = notificationDeepLinkFrom(intent)
        val target = explicitIntentScreen(intent, notification)
        when {
            target is Screen.Chat -> {
                openExternalChat(target)
                notification?.let { validateNotificationRunOwnership(intent, it) }
            }
            target == Screen.PersonalAgent -> navStack?.let(::applyProactiveDeepLinkToBackStack)
            target != null -> {
                if (target == Screen.LiveCompanion) LiveCompanionDeepLink.request()
                if (navStack?.lastOrNull() != target) navStack?.add(target)
            }
            else -> app.amber.feature.tools.DynamicShortcutPublisher.screenFromIntent(intent)?.let {
                navigateToShortcutRoute(it, intent)
            }
        }
        newIntentHandler?.invoke(intent)
    }

    /**
     * W16-B: launcher shortcut routes reuse the same landing semantics as
     * notifications - a conversation shortcut opens that exact conversation,
     * prompts prefill a fresh chat draft without bypassing the send gate.
     */
    private fun navigateToShortcutRoute(route: app.amber.feature.tools.DynamicShortcutPublisher.Route, source: Intent) {
        when (route) {
            app.amber.feature.tools.DynamicShortcutPublisher.Route.NewChat ->
                openExternalChat(Screen.Chat(Uuid.random().toString()))
            is app.amber.feature.tools.DynamicShortcutPublisher.Route.Conversation -> {
                // A stale shortcut must not resurrect a deleted conversation
                // with its old UUID; land on a fresh chat instead.
                lifecycleScope.launch {
                    val exists = runCatching {
                        conversationRepository.existsConversationById(route.conversationId)
                    }.getOrDefault(false)
                    if (intent !== source) return@launch
                    if (exists) {
                        openExternalChat(Screen.Chat(route.conversationId.toString()))
                    } else {
                        openExternalChat(Screen.Chat(Uuid.random().toString()))
                    }
                }
            }
            is app.amber.feature.tools.DynamicShortcutPublisher.Route.QuickPrompt ->
                openExternalChat(Screen.Chat(Uuid.random().toString(), route.prompt.base64Encode()))
        }
    }

    private fun openExternalChat(chat: Screen.Chat) {
        navStack?.let { applyExternalChatToBackStack(it, chat, readLaunchStartMode()) }
    }

    private fun readLaunchStartMode(): LaunchStartMode = migrateLaunchStartMode(
        storedMode = readStringPreference(LAUNCH_START_MODE_PREF),
        legacyCreateNewConversationOnStart = if (containsPreference(LEGACY_CREATE_NEW_CONVERSATION_ON_START_PREF)) {
            readBooleanPreference(LEGACY_CREATE_NEW_CONVERSATION_ON_START_PREF, true)
        } else null,
    )

    private fun explicitIntentScreen(intent: Intent, notificationLink: NotificationDeepLink?): Screen? {
        if (launchedFromProactiveDeepLink) {
            return Screen.PersonalAgent
        }
        phoneUseScreenFromIntent(intent)?.let { screen ->
            return screen
        }
        if (openPersonalAgentFromIntent(intent)) {
            return Screen.PersonalAgent
        }
        // Cold-start notification taps must win over launch-mode/last-
        // conversation resolution. This prevents a mismatched run from
        // being hidden by an unrelated last session.
        notificationLink?.let { link ->
            return Screen.Chat(link.conversationId)
        }
        if (intent.getBooleanExtra(MemoryDreamNotifier.EXTRA_OPEN_AGENT_MEMORY, false)) {
            return Screen.SettingAgentMemory
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_LIVE_COMPANION, false)) {
            return Screen.LiveCompanion
        }
        taskSessionScreenFromIntent(intent)?.let { screen ->
            return screen
        }
        return null
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

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun AppRoutes() {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        // One app-level modal layer above every route (and above the two-pane list) so a route's
        // modal scrim covers the whole interactive window. The owning route publishes the content;
        // the shell keeps no modal-specific state.
        val appModalOverlay = rememberAppModalOverlayHostState()

        val notificationLink = remember { notificationDeepLinkFrom(intent) }
        val startMode = remember { readLaunchStartMode() }
        val startScreen = remember {
            if (ChatPerfHomeEntry.enabled && intent?.getStringExtra("perfPathId") == HOME_FIRST_PATH) {
                return@remember Screen.SessionHome
            }
            explicitIntentScreen(intent, notificationLink)?.let { return@remember it }
            app.amber.feature.tools.DynamicShortcutPublisher.screenFromIntent(intent)?.let { route ->
                return@remember shortcutStartScreen(route)
            }
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

        val initialBackStack = remember(startScreen, startMode) {
            if (startScreen is Screen.PhoneUseLive || startScreen == Screen.SettingPhoneUseDisplay) {
                listOf(Screen.PersonalAgent, startScreen)
            } else {
                initialBackStackFor(startScreen = startScreen, startMode = startMode)
            }
        }
        // Older saved stacks can contain route keys this version cannot deserialize.
        val backStack = key("main-navigation") {
            rememberNavBackStack(*initialBackStack.toTypedArray())
        }
        val navigator = remember(backStack) { Navigator(backStack) }
        val navigationEntries = remember { appNavigationEntries() }
        var currentIntent by remember { mutableStateOf(intent) }
        val perfDarkMode = if (ChatPerfHomeEntry.enabled) LocalDarkMode.current else false
        // Read the snapshot-backed stack IN composition: an effect-only read
        // does not subscribe this parent when NavDisplay changes its top entry.
        val currentTopEntry = backStack.lastOrNull()
        val perfTop = if (ChatPerfHomeEntry.enabled) currentTopEntry else null
        val perfTopRoute = when (perfTop) {
            is Screen.SessionHome -> "Home"
            is Screen.Chat -> "Chat"
            null -> "unknown"
            else -> "Other"
        }
        SideEffect {
            if (ChatPerfHomeEntry.enabled) {
                val c = resources.configuration
                val d = settings.displaySetting
                ChatPerfHomeEntry.route(perfTop is Screen.SessionHome, JSONObject().apply {
                    put("darkMode", perfDarkMode); put("themeFamily", d.amberBaseFamily)
                    put("accentColor", d.accentColor)
                    put("themePackApplied", d.themePack != null); put("fontSizeRatio", d.fontSizeRatio)
                    put("assistantBubble", d.showAssistantBubble); put("chatFontFamily", d.chatFontFamily.name)
                    put("fontScale", c.fontScale); put("locale", c.locales.toLanguageTags())
                    put("orientation", c.orientation); put("uiMode", c.uiMode)
                }, perfTopRoute, (perfTop as? Screen.Chat)?.id)
            }
            this@RouteActivity.navStack = backStack
            this@RouteActivity.newIntentHandler = { currentIntent = it }
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

        SharedTransitionLayout(modifier = Modifier.semantics { testTagsAsResourceId = true }) {
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
                AppModalOverlayHost(appModalOverlay)
                }
            }
        }
    }
}

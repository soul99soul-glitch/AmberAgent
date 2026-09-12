package app.amber.feature.ui.pages.miniapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.settings.CapabilityFlags
import app.amber.feature.miniapp.AndroidMiniAppUserConfirmation
import app.amber.feature.miniapp.CapabilityMiniAppSendGate
import app.amber.feature.miniapp.MiniAppAiBridge
import app.amber.feature.miniapp.MiniAppAndroidDeviceCapabilities
import app.amber.feature.miniapp.MiniAppConversationWriter
import app.amber.feature.miniapp.MiniAppHttpClient
import app.amber.feature.miniapp.MiniAppImageProxy
import app.amber.feature.miniapp.MiniAppRepository
import app.amber.feature.miniapp.MiniAppSandbox
import app.amber.feature.miniapp.MiniAppSearchBridge
import app.amber.feature.miniapp.MiniAppShell
import app.amber.feature.miniapp.MiniAppSpeechEngine
import app.amber.feature.miniapp.MiniAppStorage
import app.amber.feature.miniapp.MiniAppPermission
import app.amber.feature.miniapp.MiniAppSystemBridge
import app.amber.feature.miniapp.MiniAppWorkspaceWriter
import app.amber.feature.miniapp.bridge.MiniAppBridge
import app.amber.feature.miniapp.bridge.MiniAppTheme
import app.amber.core.service.ChatService
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.core.utils.writeClipboardText
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import java.io.ByteArrayInputStream
import kotlin.uuid.Uuid

@Composable
fun MiniAppRunnerPage(
    appId: String,
    repository: MiniAppRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var state by remember(appId) { mutableStateOf<MiniAppRunnerState>(MiniAppRunnerState.Loading) }
    var reloadKey by remember(appId) { mutableStateOf(0) }

    LaunchedEffect(appId) {
        state = MiniAppRunnerState.Loading
        when (val loaded = loadMiniAppRunnerState { repository.getById(appId) }) {
            MiniAppRunnerLoadState.Missing -> state = MiniAppRunnerState.Missing
            is MiniAppRunnerLoadState.Error -> state = MiniAppRunnerState.Error(loaded.message)
            is MiniAppRunnerLoadState.Ready -> {
                state = MiniAppRunnerState.Ready(loaded.app)
                markRunnerVisit(repository, appId)
            }
        }
    }

    MiniAppImmersiveWindowEffect()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            MiniAppRunnerState.Loading -> MiniAppRunnerChrome(
                title = stringResource(R.string.miniapp_title),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            MiniAppRunnerState.Missing -> MiniAppRunnerChrome(
                title = stringResource(R.string.miniapp_title),
            ) {
                MiniAppRunnerError(
                    message = stringResource(R.string.miniapp_not_found),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is MiniAppRunnerState.Error -> MiniAppRunnerChrome(
                title = stringResource(R.string.miniapp_title),
            ) {
                MiniAppRunnerError(
                    message = current.message,
                    modifier = Modifier.fillMaxSize(),
                    onRetry = {
                        state = MiniAppRunnerState.Loading
                        scope.launch {
                            when (val loaded = loadMiniAppRunnerState { repository.getById(appId) }) {
                                MiniAppRunnerLoadState.Missing -> state = MiniAppRunnerState.Missing
                                is MiniAppRunnerLoadState.Error -> state = MiniAppRunnerState.Error(loaded.message)
                                is MiniAppRunnerLoadState.Ready -> {
                                    reloadKey++
                                    state = MiniAppRunnerState.Ready(loaded.app)
                                    markRunnerVisit(repository, appId)
                                }
                            }
                        }
                    },
                )
            }

            is MiniAppRunnerState.Ready -> key(current.app.id) {
                MiniAppRunnerChrome(title = current.app.title) {
                    MiniAppWebView(
                        app = current.app,
                        reloadKey = reloadKey,
                        onError = { message -> state = MiniAppRunnerState.Error(message) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniAppRunnerChrome(
    title: String,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = workspaceColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(colors.canvas),
    ) {
        WorkspaceTopBar(
            title = title,
            navigationIcon = { BackButton() },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            content = content,
        )
    }
}

@Composable
private fun MiniAppImmersiveWindowEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
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
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowInsetsControllerCompat(window, window.decorView).apply {
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

@Composable
private fun MiniAppRunnerError(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message)
            onRetry?.let {
                TextButton(onClick = it) {
                    Text(stringResource(R.string.miniapp_retry))
                }
            }
        }
    }
}

private sealed interface MiniAppRunnerState {
    data object Loading : MiniAppRunnerState
    data object Missing : MiniAppRunnerState
    data class Ready(val app: MiniAppEntity) : MiniAppRunnerState
    data class Error(val message: String) : MiniAppRunnerState
}

internal sealed interface MiniAppRunnerLoadState {
    data object Missing : MiniAppRunnerLoadState
    data class Ready(val app: MiniAppEntity) : MiniAppRunnerLoadState
    data class Error(val message: String) : MiniAppRunnerLoadState
}

internal suspend fun loadMiniAppRunnerState(
    load: suspend () -> MiniAppEntity?,
): MiniAppRunnerLoadState = try {
    load()?.let { MiniAppRunnerLoadState.Ready(it) } ?: MiniAppRunnerLoadState.Missing
} catch (cancel: CancellationException) {
    throw cancel
} catch (error: Throwable) {
    MiniAppRunnerLoadState.Error(error.message ?: "小应用加载失败")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MiniAppWebView(
    app: MiniAppEntity,
    reloadKey: Int,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    repository: MiniAppRepository = koinInject(),
    settingsStore: SettingsAggregator = koinInject(),
    searchBridge: MiniAppSearchBridge = koinInject(),
    aiBridge: MiniAppAiBridge = koinInject(),
    conversationWriter: MiniAppConversationWriter = koinInject(),
    workspaceWriter: MiniAppWorkspaceWriter = koinInject(),
    capabilityFlags: CapabilityFlags = koinInject(),
    capabilityPermissionStore: CapabilityPermissionStore = koinInject(),
    chatService: ChatService = koinInject(),
) {
    val context = LocalContext.current
    val loadFailedMessage = stringResource(R.string.miniapp_load_failed)
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val appSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val miniAppSetting = appSettings.agentRuntime.miniApp
    val json = remember { Json { ignoreUnknownKeys = true } }
    val sessionToken = remember(app.id) { Uuid.random().toString() }
    val storage = remember { MiniAppStorage(context.applicationContext) }
    val httpClient = remember { MiniAppHttpClient() }
    val imageProxy = remember(context, httpClient) { MiniAppImageProxy(context, httpClient) }
    val permissions = remember(app.id, app.permissionsJson) {
        runCatching { json.decodeFromString<List<String>>(app.permissionsJson) }.getOrDefault(emptyList()).toSet()
    }
    val bridgeScript = remember {
        context.assets.open("miniapp/miniapp_bridge.js").bufferedReader().use { it.readText() }
    }
    val shellHtml = remember(context, app.id, app.htmlContent, bridgeScript, sessionToken) {
        MiniAppShell.inject(context, app.htmlContent, bridgeScript, sessionToken)
    }
    val sendGate = remember(capabilityFlags, capabilityPermissionStore, appSettings) {
        CapabilityMiniAppSendGate(
            capabilityFlags = capabilityFlags,
            permissionStore = capabilityPermissionStore,
            highRiskAutoApproved = { appSettings.agentRuntime.autoApproveHighRiskToolCalls },
        )
    }
    val background = MaterialTheme.colorScheme.background
    val foreground = MaterialTheme.colorScheme.onBackground
    val primary = MaterialTheme.colorScheme.primary
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    var webViewRef by remember(app.id) { mutableStateOf<WebView?>(null) }
    var bridgeRef by remember(app.id) { mutableStateOf<MiniAppBridge?>(null) }
    var systemOwnerRef by remember(app.id) { mutableStateOf<MiniAppAndroidDeviceCapabilities?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val foregroundProvider = remember(lifecycleOwner) {
        { lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED }
    }

    // P4 W11: share result comes back through the real ActivityResult flow;
    // completed means the system hand-off finished, not target-app delivery.
    val shareResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        systemOwnerRef?.completeShare(result.resultCode == android.app.Activity.RESULT_OK)
    }

    // P4 W10/W11: per-runner native system capability owner. Screen/haptics/
    // share/openURL act on this runner's activity window; speech is a separate
    // per-runner engine the owner closes on dispose.
    val systemOwner = remember(app.id, context, lifecycleOwner) {
        MiniAppAndroidDeviceCapabilities(
            context = context,
            activityProvider = { webViewRef?.context?.findActivity() ?: context.findActivity() },
            speechEngine = MiniAppSpeechEngine(
                context = context,
                foregroundProvider = foregroundProvider,
            ),
            shareLauncher = { intent ->
                withContext(Dispatchers.Main) { shareResultLauncher.launch(intent) }
            },
            openLauncher = { intent ->
                withContext(Dispatchers.Main) {
                    try {
                        context.startActivity(intent)
                        true
                    } catch (error: android.content.ActivityNotFoundException) {
                        false
                    }
                }
            },
            foregroundProvider = foregroundProvider,
        ).also { systemOwnerRef = it }
    }

    // Background: stop speech and release brightness/keep-awake leases while
    // the runner is not the active window (iOS willResignActive parity).
    DisposableEffect(lifecycleOwner, systemOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                systemOwner.suspendRunner()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef = this
                WebView.setWebContentsDebuggingEnabled(miniAppSetting.webViewDebugEnabled)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.databaseEnabled = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.blockNetworkLoads = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val scheme = request.url.scheme?.lowercase()
                        return when (scheme) {
                            "https" -> {
                                val allowed = runCatching {
                                    MiniAppSandbox(
                                        appId = app.id,
                                        declaredPermissions = permissions,
                                        settingProvider = { settingsStore.settingsFlow.value.agentRuntime.miniApp },
                                        grantDecision = { permission ->
                                            runBlocking { repository.grantDecision(app.id, permission) }
                                        },
                                    ).require(MiniAppPermission.ExternalImages)
                                }.isSuccess
                                if (allowed) imageProxy.load(request.url.toString()) else blockedResponse()
                            }
                            "http", "file", "content", "android_asset", "jar", "blob" -> blockedResponse()
                            "data", "about" -> null
                            else -> blockedResponse()
                        }
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                error?.description?.toString()
                            } else {
                                null
                            }
                            onError(message?.takeIf { it.isNotBlank() } ?: loadFailedMessage)
                        }
                    }
                }
                val sandbox = MiniAppSandbox(
                    appId = app.id,
                    declaredPermissions = permissions,
                    settingProvider = { settingsStore.settingsFlow.value.agentRuntime.miniApp },
                    grantDecision = { permission -> runBlocking { repository.grantDecision(app.id, permission) } },
                )
                addJavascriptInterface(
                    MiniAppBridge(
                        context = ctx,
                        webViewProvider = { webViewRef },
                        appId = app.id,
                        sessionToken = sessionToken,
                        appProvider = { app },
                        sandbox = sandbox,
                        repository = repository,
                        storage = storage,
                        httpClient = httpClient,
                        searchBridge = searchBridge,
                        aiBridge = aiBridge,
                        confirmation = AndroidMiniAppUserConfirmation(ctx),
                        systemBridge = MiniAppSystemBridge(ctx.applicationContext),
                        toast = { message -> Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show() },
                        clipboardCopy = { text -> ctx.writeClipboardText(text) },
                        updateBoardSummary = { summary ->
                            scope.launch { repository.updateBoardSummary(app.id, summary) }
                        },
                        launchApp = { targetAppId ->
                            navController.navigate(Screen.MiniAppRunner(targetAppId))
                        },
                        themeProvider = {
                            MiniAppTheme(
                                dark = isDark,
                                background = "#${background.toArgb().toUInt().toString(16).takeLast(6)}",
                                foreground = "#${foreground.toArgb().toUInt().toString(16).takeLast(6)}",
                                primary = "#${primary.toArgb().toUInt().toString(16).takeLast(6)}",
                            )
                        },
                        conversationWriter = conversationWriter,
                        workspaceWriter = workspaceWriter,
                        sendGate = sendGate,
                        systemCapabilityHandler = systemOwner,
                    ).also { bridgeRef = it },
                    "AmberNative",
                )
            }
        },
        update = {
            WebView.setWebContentsDebuggingEnabled(miniAppSetting.webViewDebugEnabled)
        },
    )

    LaunchedEffect(webViewRef, shellHtml, reloadKey) {
        webViewRef?.loadDataWithBaseURL(MiniAppShell.BASE_URL, shellHtml, "text/html", "utf-8", null)
    }

    DisposableEffect(app.id) {
        onDispose {
            // Owner first: late JS calls get runner_closed, speech stops and
            // screen leases restore before the WebView itself goes away.
            systemOwner.close()
            systemOwnerRef = null
            bridgeRef?.close()
            bridgeRef = null
            webViewRef?.destroy()
            webViewRef = null
        }
    }
}

private suspend fun markRunnerVisit(repository: MiniAppRepository, appId: String) {
    try {
        repository.markRun(appId)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        Log.w("MiniAppRunner", "Unable to persist runner visit for $appId", error)
    }
}

private fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

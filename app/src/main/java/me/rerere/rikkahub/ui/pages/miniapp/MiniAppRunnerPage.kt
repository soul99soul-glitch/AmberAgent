package me.rerere.rikkahub.ui.pages.miniapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.agent.miniapp.AndroidMiniAppUserConfirmation
import me.rerere.rikkahub.data.agent.miniapp.MiniAppAiBridge
import me.rerere.rikkahub.data.agent.miniapp.MiniAppHttpClient
import me.rerere.rikkahub.data.agent.miniapp.MiniAppImageProxy
import me.rerere.rikkahub.data.agent.miniapp.MiniAppRepository
import me.rerere.rikkahub.data.agent.miniapp.MiniAppSandbox
import me.rerere.rikkahub.data.agent.miniapp.MiniAppSearchBridge
import me.rerere.rikkahub.data.agent.miniapp.MiniAppShell
import me.rerere.rikkahub.data.agent.miniapp.MiniAppStorage
import me.rerere.rikkahub.data.agent.miniapp.MiniAppPermission
import me.rerere.rikkahub.data.agent.miniapp.MiniAppSystemBridge
import me.rerere.rikkahub.data.agent.miniapp.bridge.MiniAppBridge
import me.rerere.rikkahub.data.agent.miniapp.bridge.MiniAppTheme
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.db.entity.MiniAppEntity
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.writeClipboardText
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
        val loaded = runCatching { repository.getById(appId) }
            .getOrElse { error ->
                state = MiniAppRunnerState.Error(error.message ?: "小应用加载失败")
                return@LaunchedEffect
            }
        if (loaded == null) {
            state = MiniAppRunnerState.Missing
        } else {
            state = MiniAppRunnerState.Ready(loaded)
            repository.markRun(appId)
        }
    }

    MiniAppImmersiveWindowEffect()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            MiniAppRunnerState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            MiniAppRunnerState.Missing -> MiniAppRunnerError(
                message = "小应用不存在",
                modifier = Modifier.fillMaxSize(),
            )

            is MiniAppRunnerState.Error -> MiniAppRunnerError(
                message = current.message,
                modifier = Modifier.fillMaxSize(),
                onRetry = {
                    scope.launch {
                        val loaded = repository.getById(appId)
                        if (loaded == null) {
                            state = MiniAppRunnerState.Missing
                        } else {
                            reloadKey++
                            state = MiniAppRunnerState.Ready(loaded)
                        }
                    }
                },
            )

            is MiniAppRunnerState.Ready -> key(current.app.id) {
                MiniAppWebView(
                    app = current.app,
                    reloadKey = reloadKey,
                    onError = { message -> state = MiniAppRunnerState.Error(message) },
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                )
            }
        }
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
                    Text("重试")
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
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val appSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val miniAppSetting = appSettings.agentRuntime.miniApp
    val json = remember { Json { ignoreUnknownKeys = true } }
    val sessionToken = remember(app.id) { Uuid.random().toString() }
    val storage = remember { MiniAppStorage(context.applicationContext) }
    val httpClient = remember { MiniAppHttpClient() }
    val imageProxy = remember(httpClient) { MiniAppImageProxy(httpClient) }
    val permissions = remember(app.id, app.permissionsJson) {
        runCatching { json.decodeFromString<List<String>>(app.permissionsJson) }.getOrDefault(emptyList()).toSet()
    }
    val bridgeScript = remember {
        context.assets.open("miniapp/miniapp_bridge.js").bufferedReader().use { it.readText() }
    }
    val shellHtml = remember(app.id, app.htmlContent, bridgeScript, sessionToken) {
        MiniAppShell.inject(app.htmlContent, bridgeScript, sessionToken)
    }
    val background = MaterialTheme.colorScheme.background
    val foreground = MaterialTheme.colorScheme.onBackground
    val primary = MaterialTheme.colorScheme.primary
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    var webViewRef by remember(app.id) { mutableStateOf<WebView?>(null) }
    var bridgeRef by remember(app.id) { mutableStateOf<MiniAppBridge?>(null) }

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
                            onError(message?.takeIf { it.isNotBlank() } ?: "小应用加载失败")
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
                            runBlocking { repository.updateBoardSummary(app.id, summary) }
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
            bridgeRef?.close()
            bridgeRef = null
            webViewRef?.destroy()
            webViewRef = null
        }
    }
}

private fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

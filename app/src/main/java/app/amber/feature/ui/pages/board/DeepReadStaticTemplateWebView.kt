package app.amber.feature.ui.pages.board

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.R
import app.amber.core.font.SlidesFontRepository
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

internal const val DEEP_READ_TEMPLATE_BASE_URL = "https://amberagent.local/deepread/"
internal const val DEEP_READ_TEMPLATE_PREVIEW_BASE_URL = "https://amberagent.template-preview.local/"
internal const val DEEP_READ_BUILTIN_SERIF_FONT_URL = "https://amberagent.local/deepread-fonts/noto_serif_sc.otf"
internal const val DEEP_READ_SLIDES_FONT_HOST = "amberagent.local"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun DeepReadStaticTemplateWebView(
    html: String,
    modifier: Modifier = Modifier,
    baseUrl: String = DEEP_READ_TEMPLATE_BASE_URL,
    allowedImageUrls: Set<String> = emptySet(),
    allowedLinkUrls: Set<String> = emptySet(),
    fontRepository: SlidesFontRepository? = null,
    textScale: Float = 1.0f,
    backgroundColor: Color = Color.Transparent,
    onOpenLink: ((String) -> Unit)? = null,
    onMainFrameError: () -> Unit = {},
    onImageError: (String) -> Unit = {},
) {
    val textZoom = (textScale.coerceIn(0.75f, 1.5f) * 100f).roundToInt()
    val backgroundArgb = backgroundColor.toArgb()
    val signature = listOf(
        baseUrl,
        html.hashCode().toString(),
        allowedImageUrls.hashCode().toString(),
        allowedLinkUrls.hashCode().toString(),
        textZoom.toString(),
        backgroundArgb.toString(),
    ).joinToString(":")
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(backgroundArgb)
                configureDeepReadStaticSettings(textZoom)
                webViewClient = deepReadStaticTemplateWebViewClient(
                    baseUrl = baseUrl,
                    allowedImageUrls = allowedImageUrls,
                    allowedLinkUrls = allowedLinkUrls,
                    fontRepository = fontRepository,
                    onOpenLink = onOpenLink,
                    onMainFrameError = onMainFrameError,
                    onImageError = onImageError,
                )
                loadTemplateHtmlIfChanged(baseUrl, html, signature)
            }
        },
        update = { view ->
            view.setBackgroundColor(backgroundArgb)
            view.configureDeepReadStaticSettings(textZoom)
            view.webViewClient = deepReadStaticTemplateWebViewClient(
                baseUrl = baseUrl,
                allowedImageUrls = allowedImageUrls,
                allowedLinkUrls = allowedLinkUrls,
                fontRepository = fontRepository,
                onOpenLink = onOpenLink,
                onMainFrameError = onMainFrameError,
                onImageError = onImageError,
            )
            view.loadTemplateHtmlIfChanged(baseUrl, html, signature)
        },
    )
}

private fun deepReadStaticTemplateWebViewClient(
    baseUrl: String,
    allowedImageUrls: Set<String>,
    allowedLinkUrls: Set<String>,
    fontRepository: SlidesFontRepository?,
    onOpenLink: ((String) -> Unit)?,
    onMainFrameError: () -> Unit,
    onImageError: (String) -> Unit,
): WebViewClient =
    object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url?.toString().orEmpty()
            val mainFrame = request?.isForMainFrame == true
            if (mainFrame && url == baseUrl) return null
            if (!mainFrame) {
                interceptBuiltInDeepReadFont(view, url)?.let { return it }
                fontRepository?.interceptFontRequest(request)?.let { return it }
                if (url in allowedImageUrls) return null
            }
            return emptyDeepReadTemplateResponse()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val url = request?.url?.toString().orEmpty()
            if (url == baseUrl) return false
            val scheme = request?.url?.scheme
            if ((scheme == "http" || scheme == "https") && url in allowedLinkUrls) {
                onOpenLink?.invoke(url)
            }
            return true
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)
            handleTemplateLoadError(request, allowedImageUrls, onMainFrameError, onImageError)
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            handleTemplateLoadError(request, allowedImageUrls, onMainFrameError, onImageError)
        }
    }

private fun WebView.configureDeepReadStaticSettings(textZoom: Int) {
    settings.javaScriptEnabled = false
    settings.domStorageEnabled = false
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.loadsImagesAutomatically = true
    settings.mediaPlaybackRequiresUserGesture = true
    settings.setSupportMultipleWindows(false)
    settings.textZoom = textZoom
}

private fun WebView.loadTemplateHtmlIfChanged(baseUrl: String, html: String, signature: String) {
    if (tag == signature) return
    tag = signature
    loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
}

private fun handleTemplateLoadError(
    request: WebResourceRequest?,
    allowedImageUrls: Set<String>,
    onMainFrameError: () -> Unit,
    onImageError: (String) -> Unit,
) {
    val url = request?.url?.toString().orEmpty()
    when {
        request?.isForMainFrame == true -> onMainFrameError()
        url in allowedImageUrls -> onImageError(url)
    }
}

private fun interceptBuiltInDeepReadFont(view: WebView?, url: String): WebResourceResponse? {
    if (url != DEEP_READ_BUILTIN_SERIF_FONT_URL) return null
    val input = view?.context?.resources?.openRawResource(R.font.noto_serif_sc)
        ?: return emptyDeepReadTemplateResponse()
    return WebResourceResponse("font/otf", null, input)
}

internal fun emptyDeepReadTemplateResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

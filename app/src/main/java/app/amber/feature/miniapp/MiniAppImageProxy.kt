package app.amber.feature.miniapp

import android.content.Context
import android.webkit.WebResourceResponse
import app.amber.agent.R
import java.io.ByteArrayInputStream

class MiniAppImageProxy(
    private val context: Context,
    private val httpClient: MiniAppHttpClient,
) {
    fun load(url: String): WebResourceResponse {
        return runCatching {
            val image = httpClient.fetchImageBlocking(url)
            WebResourceResponse(
                image.contentType,
                "utf-8",
                ByteArrayInputStream(image.bytes),
            )
        }.getOrElse {
            WebResourceResponse(
                "image/svg+xml",
                "utf-8",
                ByteArrayInputStream(
                    offlinePlaceholderSvg(
                        context.getString(R.string.miniapp_image_load_failed),
                    ).toByteArray(),
                ),
            )
        }
    }
}

private fun offlinePlaceholderSvg(label: String) = """
    <svg xmlns="http://www.w3.org/2000/svg" width="800" height="480" viewBox="0 0 800 480">
      <defs><linearGradient id="g" x1="0" x2="1" y1="0" y2="1"><stop stop-color="#f3f4f6"/><stop offset="1" stop-color="#e5e7eb"/></linearGradient></defs>
      <rect width="800" height="480" fill="url(#g)"/>
      <text x="50%" y="50%" text-anchor="middle" dominant-baseline="middle" fill="#6b7280" font-size="28" font-family="sans-serif">$label</text>
    </svg>
""".trimIndent()

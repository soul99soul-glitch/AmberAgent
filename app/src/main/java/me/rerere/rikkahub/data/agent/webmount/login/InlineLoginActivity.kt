package me.rerere.rikkahub.data.agent.webmount.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.webmount.core.WebMountManager
import org.koin.android.ext.android.inject

/**
 * Phase 2 M2.3.2 — in-app login flow for cookie-auth stations.
 *
 * Triggered by the deep link `amberagent://webmount/login?station=<id>`.
 * Opens a visible WebView pointed at the station's `primaryLoginUrl()`.
 * The user logs in normally; the system [CookieManager] is process-global
 * (shared with the headless WebMount sessions), so once the page is
 * accepted the cookie is immediately available to any subsequent
 * `wm_open` / `wm_signed_fetch` call.
 *
 * The activity finishes when the user taps "Done", or when the
 * post-login URL leaves the station's login flow (`primaryLoginUrl`).
 *
 * Cookie persistence is best-effort — Android's `CookieManager.flush()`
 * is called on activity finish to commit any in-memory cookies.
 */
class InlineLoginActivity : Activity() {

    private val webMountManager: WebMountManager by inject()

    private var webView: WebView? = null
    private var statusLabel: TextView? = null
    private var stationId: String? = null
    private var loginCookieName: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Resolve the requested station from intent data.
        val data: Uri? = intent.data
        stationId = data?.getQueryParameter("station")
            ?: data?.lastPathSegment
        if (stationId.isNullOrBlank()) {
            Log.w(TAG, "InlineLogin invoked with no station id: $data")
            finish()
            return
        }
        val adapter = webMountManager.adapterOf(stationId!!)
        if (adapter == null) {
            Log.w(TAG, "InlineLogin: station '$stationId' is not registered")
            finish()
            return
        }
        val loginUrl = adapter.primaryLoginUrl()
        if (loginUrl.isNullOrBlank()) {
            Log.w(TAG, "InlineLogin: station '$stationId' has no primaryLoginUrl")
            finish()
            return
        }
        loginCookieName = adapter.endpoints.firstOrNull()
            ?.requiredCookieNames?.firstOrNull()

        // Build the UI programmatically — no XML needed for one-shot view.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        statusLabel = TextView(this).apply {
            text = getString(R.string.webmount_inline_login_status_loading, adapter.displayName)
            setPadding(32, 32, 32, 16)
            textSize = 14f
            setTextColor(0xFF333333.toInt())
        }
        root.addView(statusLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val doneButton = Button(this).apply {
            text = getString(R.string.webmount_inline_login_done)
            setOnClickListener { finish() }
        }
        root.addView(doneButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(32, 0, 32, 16) })

        val frame = FrameLayout(this)
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = settings.userAgentString // keep default Chrome UA
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    refreshStatus(url)
                }
            }
        }
        frame.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        root.addView(frame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f,
        ))
        setContentView(root)

        // The system CookieManager is shared with headless WebMount sessions;
        // explicitly accept third-party cookies so login flows that hop
        // through SSO providers (e.g. passport.bilibili.com) persist correctly.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView!!, true)
        webView!!.loadUrl(loginUrl)
    }

    private fun refreshStatus(url: String?) {
        val cookieName = loginCookieName ?: return
        val origin = url?.let { tryOrigin(it) } ?: return
        val cookies = CookieManager.getInstance().getCookie(origin) ?: ""
        val present = cookies.split(";").map { it.trim() }
            .any { it.startsWith("$cookieName=") && it.length > "$cookieName=".length }
        val label = statusLabel ?: return
        if (present) {
            label.text = getString(R.string.webmount_inline_login_status_signed_in, cookieName)
            label.setTextColor(0xFF1B5E20.toInt())
        } else {
            label.text = getString(R.string.webmount_inline_login_status_waiting, cookieName)
            label.setTextColor(0xFF333333.toInt())
        }
    }

    private fun tryOrigin(url: String): String? {
        val match = Regex("^(https?://[^/?#]+)").find(url) ?: return null
        return match.groupValues[1]
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            settings.javaScriptEnabled = false
            destroy()
        }
        webView = null
        // Persist captured cookies to disk before the system reclaims us.
        CookieManager.getInstance().flush()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WebMountInlineLogin"

        fun newIntent(activity: Activity, stationId: String): Intent {
            return Intent(activity, InlineLoginActivity::class.java).apply {
                data = Uri.parse("amberagent://webmount/login?station=$stationId")
            }
        }
    }
}

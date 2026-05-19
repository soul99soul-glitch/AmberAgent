package me.rerere.rikkahub.data.agent.webmount.login

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider
import me.rerere.rikkahub.data.agent.webmount.core.WebMountManager
import me.rerere.rikkahub.data.agent.webmount.core.WebMountStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * In-app login flow for cookie-auth stations.
 *
 * The visible WebView is controlled by [WebMountLoginController], so inline
 * login and the Settings dialog share URL polling, cookie detection, app-scheme
 * blocking, popup handling, and compatibility script injection.
 */
class InlineLoginActivity : Activity() {

    private val webMountManager: WebMountManager by inject()
    private val cookieProvider: WebMountCookieProvider by inject()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: WebMountLoginController? = null
    private var statusLabel: TextView? = null
    private var verifying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data: Uri? = intent.data
        val stationId = data?.getQueryParameter("station")
            ?: data?.lastPathSegment
        if (stationId.isNullOrBlank()) {
            Log.w(TAG, "InlineLogin invoked with no station id: $data")
            finish()
            return
        }
        val adapter = webMountManager.adapterOf(stationId)
        if (adapter == null) {
            Log.w(TAG, "InlineLogin: station '$stationId' is not registered")
            finish()
            return
        }
        val target = WebMountLoginTarget.fromAdapter(adapter)
        if (target.startUrl.isBlank()) {
            Log.w(TAG, "InlineLogin: station '$stationId' has no primaryLoginUrl")
            finish()
            return
        }

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
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 0, 32, 16)
        }
        val reloadButton = Button(this).apply {
            text = "Reload"
            setOnClickListener { controller?.reload() }
        }
        val doneButton = Button(this).apply {
            text = getString(R.string.webmount_inline_login_done)
            setOnClickListener {
                val status = controller?.manualCheck() ?: WebMountLoginStatus.Waiting
                if (status is WebMountLoginStatus.SignedIn) {
                    verifyAndFinish(target)
                } else {
                    renderStatus(target, status)
                }
            }
        }
        buttonRow.addView(reloadButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        buttonRow.addView(doneButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(buttonRow)

        val frame = FrameLayout(this)
        val loginController = WebMountLoginController(
            context = this,
            target = target,
            cookieProvider = cookieProvider,
            onStateChange = { state ->
                controller?.webView?.let { activeWebView ->
                    if (frame.childCount == 0 || frame.getChildAt(0) !== activeWebView) {
                        frame.removeAllViews()
                        frame.addView(
                            activeWebView,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                }
                state.blockedNavigation?.let {
                    statusLabel?.text = "Blocked app navigation: ${it.take(80)}"
                }
                if (state.renderProcessGone) {
                    statusLabel?.text = "Login page crashed. Tap Reload to try again."
                }
            },
            onLoginStatus = { status -> renderStatus(target, status) },
        )
        controller = loginController
        frame.addView(loginController.webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        root.addView(frame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        setContentView(root)
        loginController.start()
    }

    private fun verifyAndFinish(target: WebMountLoginTarget) {
        if (verifying) return
        val stationId = target.stationId
        if (stationId.isNullOrBlank()) {
            finish()
            return
        }
        verifying = true
        statusLabel?.text = "Verifying session..."
        uiScope.launch {
            val state = runCatching { webMountManager.probe(stationId) }.getOrNull()
            verifying = false
            if (isFinishing || isDestroyed) return@launch
            if (state == null || state.status == WebMountStatus.ERROR || state.status == WebMountStatus.LOGIN_REQUIRED) {
                renderStatus(
                    target,
                    WebMountLoginStatus.Failed(
                        state?.message ?: "Cookie exists, but ${target.displayName} probe did not confirm the session.",
                    ),
                )
            } else {
                finish()
            }
        }
    }

    private fun renderStatus(
        target: WebMountLoginTarget,
        status: WebMountLoginStatus,
    ) {
        val label = statusLabel ?: return
        when (status) {
            WebMountLoginStatus.Waiting,
            is WebMountLoginStatus.UrlMatched -> {
                val cookieName = target.requiredCookieSets.firstOrNull()?.joinToString(" + ")
                    ?: target.candidateCookieNames.firstOrNull()
                    ?: "session"
                label.text = getString(R.string.webmount_inline_login_status_waiting, cookieName)
                label.setTextColor(0xFF333333.toInt())
            }
            is WebMountLoginStatus.SignedIn -> {
                label.text = getString(
                    R.string.webmount_inline_login_status_signed_in,
                    status.cookieNames.joinToString(" + "),
                )
                label.setTextColor(0xFF1B5E20.toInt())
            }
            is WebMountLoginStatus.MissingCookies -> {
                label.text = "Missing cookies: ${status.missing.joinToString()}"
                label.setTextColor(0xFF333333.toInt())
            }
            is WebMountLoginStatus.Unknown -> {
                label.text = status.reason
                label.setTextColor(0xFF333333.toInt())
            }
            is WebMountLoginStatus.Failed -> {
                label.text = status.reason
                label.setTextColor(0xFFB71C1C.toInt())
            }
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        CookieManager.getInstance().flush()
        controller?.destroy()
        controller = null
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

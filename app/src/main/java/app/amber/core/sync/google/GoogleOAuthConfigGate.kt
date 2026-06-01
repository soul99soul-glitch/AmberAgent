package app.amber.core.sync.google

import android.content.Context
import app.amber.agent.BuildConfig

class GoogleOAuthConfigGate(private val context: Context) {
    fun status(): GoogleOAuthConfigStatus {
        val webClientId = generatedWebClientId()
        if (webClientId.isNullOrBlank()) {
            return GoogleOAuthConfigStatus(
                available = true,
                reason = "Drive 授权入口可用；本地缺少 default_web_client_id，若 Google 控制台未配置当前包名与签名，授权时会由 Google 返回错误：${BuildConfig.APPLICATION_ID}",
                credentialManagerAvailable = false,
            )
        }
        return GoogleOAuthConfigStatus(
            available = true,
            reason = "",
            webClientId = webClientId,
            credentialManagerAvailable = true,
        )
    }

    private fun generatedWebClientId(): String? {
        val id = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            BuildConfig.APPLICATION_ID,
        )
        if (id == 0) return null
        return context.getString(id).takeIf { it.isNotBlank() }
    }
}

data class GoogleOAuthConfigStatus(
    val available: Boolean,
    val reason: String,
    val webClientId: String = "",
    val credentialManagerAvailable: Boolean = false,
)

package app.amber.core.sync.google

import android.content.Context
import app.amber.agent.BuildConfig

class GoogleOAuthConfigGate(private val context: Context) {
    fun status(): GoogleOAuthConfigStatus {
        val webClientId = generatedWebClientId()
        if (!BuildConfig.GOOGLE_OAUTH_CONFIGURED) {
            return GoogleOAuthConfigStatus(
                available = false,
                reason = "当前包名缺少 Google OAuth client，无法授权 Drive：${BuildConfig.APPLICATION_ID}",
                credentialManagerAvailable = false,
            )
        }
        if (webClientId.isNullOrBlank()) {
            return GoogleOAuthConfigStatus(
                available = true,
                reason = "Drive 授权可用；缺少 default_web_client_id，暂不启用 Credential Manager ID token 登录。若 Google 控制台未配置当前包名与签名，授权时仍会失败：${BuildConfig.APPLICATION_ID}",
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

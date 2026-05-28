package app.amber.core.sync.google

import android.content.Context
import app.amber.agent.BuildConfig

class GoogleOAuthConfigGate(private val context: Context) {
    fun status(): GoogleOAuthConfigStatus {
        val webClientId = generatedWebClientId()
        if (webClientId.isNullOrBlank()) {
            return GoogleOAuthConfigStatus(
                available = true,
                reason = if (BuildConfig.GOOGLE_OAUTH_CONFIGURED) {
                    "Drive 授权可用；缺少 default_web_client_id，暂不启用 Credential Manager ID token 登录。"
                } else {
                    "Drive 授权入口已开启；当前包名缺少 google-services OAuth client，若 Google 控制台未配置包名与签名，授权时会失败：${BuildConfig.APPLICATION_ID}"
                },
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

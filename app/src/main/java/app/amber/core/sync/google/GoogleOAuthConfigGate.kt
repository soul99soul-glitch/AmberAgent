package app.amber.core.sync.google

import android.content.Context
import app.amber.agent.BuildConfig

class GoogleOAuthConfigGate(private val context: Context) {
    fun status(): GoogleOAuthConfigStatus {
        val webClientId = generatedWebClientId()
        if (!BuildConfig.GOOGLE_OAUTH_CONFIGURED) {
            val reason = buildString {
                append("当前 APK 缺少 Google Drive OAuth client，无法授权：")
                append(BuildConfig.APPLICATION_ID)
                append("。请使用包含该包名和签名 SHA-1 的 app/google-services.json 重新构建。")
            }
            return GoogleOAuthConfigStatus(
                available = false,
                reason = reason,
                credentialManagerAvailable = false,
            )
        }
        if (webClientId.isNullOrBlank()) {
            return GoogleOAuthConfigStatus(
                available = true,
                reason = "Drive 授权可用；缺少 default_web_client_id，" +
                    "暂不启用 Credential Manager ID token 登录。",
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

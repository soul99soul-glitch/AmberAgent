package app.amber.feature.ui.pages.chat

import androidx.annotation.StringRes
import app.amber.agent.R
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.providers.grok.GrokAuthStatus
import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.hasUsableAuth
import app.amber.core.settings.findProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class ChatConfigurationIssue(@param:StringRes val messageRes: Int) {
    MissingProvider(R.string.chat_setup_missing_provider),
    MissingModel(R.string.chat_setup_missing_model),
    DisabledProvider(R.string.chat_setup_disabled_provider),
    InvalidAddress(R.string.chat_setup_invalid_address),
    MissingKey(R.string.chat_setup_missing_key),
    CodexSignIn(R.string.chat_setup_codex_sign_in),
    GeminiSignIn(R.string.chat_setup_gemini_sign_in),
    GrokSignIn(R.string.chat_setup_grok_sign_in),
    AntigravitySignIn(R.string.chat_setup_antigravity_sign_in),
}

/** Uses the same effective provider (including model overrides) as generation. */
internal fun chatConfigurationIssue(
    model: Model?,
    providers: List<ProviderSetting>,
    oauthReady: Boolean? = null,
): ChatConfigurationIssue? {
    if (providers.isEmpty()) return ChatConfigurationIssue.MissingProvider
    if (model == null || model.modelId.isBlank()) return ChatConfigurationIssue.MissingModel
    val provider = model.findProvider(providers) ?: return ChatConfigurationIssue.MissingProvider
    if (!provider.enabled) return ChatConfigurationIssue.DisabledProvider
    if (provider is ProviderSetting.OpenAI && provider.authMode == OpenAIAuthMode.CODEX_OAUTH) {
        return if (oauthReady == false) ChatConfigurationIssue.CodexSignIn else null
    }
    if (provider is ProviderSetting.Google && provider.authMode == GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH) {
        return if (oauthReady == false) ChatConfigurationIssue.GeminiSignIn else null
    }
    if (provider is ProviderSetting.Google && provider.authMode == GoogleAuthMode.ANTIGRAVITY_OAUTH) {
        return if (oauthReady == false) ChatConfigurationIssue.AntigravitySignIn else null
    }
    if (provider is ProviderSetting.OpenAI && provider.authMode == OpenAIAuthMode.GROK_OAUTH) {
        return if (oauthReady == false) ChatConfigurationIssue.GrokSignIn else null
    }
    val address = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl
        // Vertex and OAuth construct their managed endpoint in the provider.
        is ProviderSetting.Google -> provider.baseUrl.takeUnless { provider.vertexAI }
        is ProviderSetting.Claude -> provider.baseUrl
    }
    if (address != null && address.trim().toHttpUrlOrNull() == null) return ChatConfigurationIssue.InvalidAddress
    // Custom compatible endpoints may intentionally use no authentication.
    if (provider is ProviderSetting.OpenAI && provider.brand == OpenAIBrand.GENERIC) return null
    return if (!provider.hasUsableAuth()) ChatConfigurationIssue.MissingKey else null
}

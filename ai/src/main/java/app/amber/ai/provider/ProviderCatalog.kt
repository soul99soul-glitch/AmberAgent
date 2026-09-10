package app.amber.ai.provider

import app.amber.ai.provider.providers.ClaudeProvider
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.OpenAIProvider
import app.amber.ai.provider.providers.grok.GrokOAuthClient

/** Explicit catalog for provider families shipped by Amber. */
class ProviderCatalog(
    private val openAIProvider: OpenAIProvider,
    private val googleProvider: GoogleProvider,
    private val claudeProvider: ClaudeProvider,
    private val grokOAuthClient: GrokOAuthClient? = null,
    private val openAITextGateway: TextModelGateway<ProviderSetting.OpenAI> = openAIProvider,
    private val openAIImageGateway: ImageModelGateway<ProviderSetting.OpenAI> = openAIProvider,
) {
    /** Grok 模型目录必须走 OAuth client；聊天仍复用 OpenAIProvider。 */
    suspend fun listModels(setting: ProviderSetting.OpenAI): List<Model> =
        if (setting.authMode == OpenAIAuthMode.GROK_OAUTH) {
            grokOAuthClient?.listModels(setting.id) ?: emptyList()
        } else text(setting).listModels(setting)
    @Suppress("UNCHECKED_CAST")
    fun <T : ProviderSetting> text(setting: T): TextModelGateway<T> = when (setting) {
        is ProviderSetting.OpenAI -> openAITextGateway
        is ProviderSetting.Google -> googleProvider
        is ProviderSetting.Claude -> claudeProvider
    } as TextModelGateway<T>

    @Suppress("UNCHECKED_CAST")
    fun <T : ProviderSetting> image(setting: T): ImageModelGateway<T> = when (setting) {
        is ProviderSetting.OpenAI -> openAIImageGateway
        is ProviderSetting.Google -> googleProvider
        is ProviderSetting.Claude -> claudeProvider
    } as ImageModelGateway<T>
}

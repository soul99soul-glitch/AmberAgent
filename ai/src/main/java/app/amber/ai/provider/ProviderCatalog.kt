package app.amber.ai.provider

import app.amber.ai.provider.providers.ClaudeProvider
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.OpenAIProvider

/** Explicit catalog for the three provider families shipped by Amber. */
class ProviderCatalog(
    private val openAIProvider: OpenAIProvider,
    private val googleProvider: GoogleProvider,
    private val claudeProvider: ClaudeProvider,
    private val openAITextGateway: TextModelGateway<ProviderSetting.OpenAI> = openAIProvider,
    private val openAIImageGateway: ImageModelGateway<ProviderSetting.OpenAI> = openAIProvider,
) {
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

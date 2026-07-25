package shared

import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.core.model.Assistant
import app.amber.core.settings.DEFAULT_AUTO_MODEL_ID
import app.amber.core.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class IosSettingsMutationsProviderTest {
    @Test
    fun providerDeletionClearsAuxiliaryAndAssistantModelReferences() {
        val removedChat = Model(modelId = "removed-chat", type = ModelType.CHAT)
        val removedImage = Model(modelId = "removed-image", type = ModelType.IMAGE)
        val removedProvider = ProviderSetting.OpenAI(models = listOf(removedChat, removedImage))
        val survivingChat = Model(modelId = "surviving-chat", type = ModelType.CHAT)
        val survivingProvider = ProviderSetting.OpenAI(models = listOf(survivingChat))
        val assistant = Assistant(
            chatModelId = removedChat.id,
            imageGenerationModelId = removedImage.id,
        )
        val settings = Settings(
            providers = listOf(removedProvider, survivingProvider),
            chatModelId = removedChat.id,
            titleModelId = removedChat.id,
            suggestionModelId = removedChat.id,
            ocrModelId = removedChat.id,
            compressModelId = removedChat.id,
            imageGenerationModelId = removedImage.id,
            assistants = listOf(assistant),
        )

        val updated = IosSettingsMutations.removeProvider(settings, removedProvider.id.toString())

        assertEquals(listOf(survivingProvider), updated.providers)
        assertEquals(survivingChat.id, updated.chatModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, updated.titleModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, updated.suggestionModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, updated.ocrModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, updated.compressModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, updated.imageGenerationModelId)
        assertNull(updated.assistants.single().chatModelId)
        assertNull(updated.assistants.single().imageGenerationModelId)
    }

    @Test
    fun providerDeletionProceedsWhenOnlyAnAssistantReferencesTheRemovedModel() {
        // Only one provider; its sole chat model is referenced by an assistant but
        // NOT by the top-level chatModelId, and no replacement CHAT model remains.
        // The assistant reference is resolved by nulling (Assistant.chatModelId is
        // nullable), so removal must proceed instead of silently no-op'ing.
        val removedChat = Model(modelId = "removed-chat", type = ModelType.CHAT)
        val removedProvider = ProviderSetting.OpenAI(models = listOf(removedChat))
        val assistant = Assistant(chatModelId = removedChat.id)
        val settings = Settings(
            providers = listOf(removedProvider),
            chatModelId = DEFAULT_AUTO_MODEL_ID,
            assistants = listOf(assistant),
        )

        val updated = IosSettingsMutations.removeProvider(settings, removedProvider.id.toString())

        assertTrue(updated.providers.isEmpty(), "Provider must be removed even when only an assistant references its model")
        assertNull(updated.assistants.single().chatModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, updated.chatModelId)
    }

    @Test
    fun codexAuthModeDoesNotOverwriteThePersistedEndpoint() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://proxy.example/v1",
            chatCompletionsPath = "/custom/chat",
            useResponseApi = false,
        )

        val updated = IosSettingsMutations.setOpenAIAuthMode(
            settings = Settings(providers = listOf(provider)),
            providerId = provider.id.toString(),
            authMode = OpenAIAuthMode.CODEX_OAUTH,
        ).providers.single() as ProviderSetting.OpenAI

        assertEquals(OpenAIAuthMode.CODEX_OAUTH, updated.authMode)
        assertEquals("https://proxy.example/v1", updated.baseUrl)
        assertEquals("/custom/chat", updated.chatCompletionsPath)
        assertFalse(updated.useResponseApi)
    }

    @Test
    fun codexModelRefreshMergesWithoutDestroyingExistingModelIdentity() {
        val existingId = Uuid.random()
        val provider = ProviderSetting.OpenAI(
            models = listOf(
                Model(
                    id = existingId,
                    modelId = "gpt-5.3-codex",
                    displayName = "Old display",
                    customHeaders = listOf(CustomHeader("X-Custom", "kept")),
                    contextWindowTokens = 123_456,
                ),
                Model(modelId = "private-model", displayName = "Private model"),
            ),
        )

        val updated = IosSettingsMutations.mergeProviderChatModels(
            settings = Settings(providers = listOf(provider)),
            providerId = provider.id.toString(),
            modelIds = listOf(
                "gpt-5.3-codex" to "GPT 5.3 Codex",
                "gpt-5.4" to "GPT 5.4",
            ),
        ).providers.single()

        assertEquals(3, updated.models.size)
        assertTrue(updated.models.any { it.modelId == "private-model" })
        val refreshed = updated.models.single { it.modelId == "gpt-5.3-codex" }
        assertEquals(existingId, refreshed.id)
        assertEquals("GPT 5.3 Codex", refreshed.displayName)
        assertEquals(123_456, refreshed.contextWindowTokens)
        assertEquals("kept", refreshed.customHeaders.single().value)
    }
}

package app.amber.feature.runtime

import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.providers.OpenAIProvider
import app.amber.ai.provider.providers.openai.StoredResponseApi
import app.amber.ai.provider.providers.openai.supportsResponsesResume
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.flow.first

/**
 * P6-01 — resolves the stored response of a run to a callable
 * [StoredResponseSession] for the recovery worker and the Stop path.
 *
 * Resolution is strict (plan §P6-01 严格适用范围): the run must have a
 * persisted cursor, the provider must still exist in settings under the
 * recorded id, and it must still strictly match the official configuration —
 * otherwise the session's [StoredResponseSession.api] is null and the caller
 * falls back to the Phase 1 recovery behavior.
 */
interface StoredResponseGateway {
    /**
     * @return null when the run has no stored response (nothing to resolve).
     * A non-null session with [StoredResponseSession.api] == null means a
     * cursor exists but the response is no longer resolvable (provider
     * deleted / support revoked).
     */
    suspend fun resolve(runId: String): StoredResponseSession?

    data class StoredResponseSession(
        val cursor: ResponseCursor,
        val providerSetting: ProviderSetting.OpenAI?,
        val api: StoredResponseApi?,
    )
}

class OpenAIStoredResponseGateway(
    private val resumeStore: ResponseResumeStore,
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
) : StoredResponseGateway {

    override suspend fun resolve(runId: String): StoredResponseGateway.StoredResponseSession? {
        val cursor = resumeStore.load(runId) ?: return null
        val providerSetting = settingsStore.settingsFlow.first().providers
            .filterIsInstance<ProviderSetting.OpenAI>()
            .firstOrNull { it.id.toString() == cursor.providerId }
        // The user switch (enableResponsesResume) gates the session too —
        // one resolution serves the Stop path and the recovery worker.
        if (
            providerSetting == null ||
            !providerSetting.supportsResponsesResume() ||
            !providerSetting.enableResponsesResume
        ) {
            return StoredResponseGateway.StoredResponseSession(cursor, providerSetting = null, api = null)
        }
        val provider = providerManager.getProviderByType(providerSetting) as? OpenAIProvider
        if (provider == null) {
            return StoredResponseGateway.StoredResponseSession(cursor, providerSetting, api = null)
        }
        return StoredResponseGateway.StoredResponseSession(
            cursor = cursor,
            providerSetting = providerSetting,
            api = provider.storedResponses,
        )
    }
}

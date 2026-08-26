package app.amber.agent.data.files

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.core.infra.AppScope
import app.amber.core.settings.prefs.AgentPrefs
import app.amber.core.settings.prefs.ChatPrefs
import app.amber.core.settings.prefs.ExtensionPrefs
import app.amber.core.settings.prefs.ProviderPrefs
import app.amber.core.settings.prefs.SearchPrefs
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.prefs.UIPrefs
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.feature.runtime.ApprovalHistoryEntry
import app.amber.feature.runtime.CasLedger
import java.io.File

/**
 * Shared fixtures for the Phase 2 CAS tests (P2-04 skill promotion, P2-07
 * soul import): an in-memory-backed SettingsAggregator (same construction as
 * SyncArchiveManagerIntegrationTest) and an in-memory CasLedger fake.
 */
object CasTestFixtures {

    fun settingsAggregator(context: Context, testRoot: File): SettingsAggregator {
        val appScope = AppScope()
        val dataStore = PreferenceDataStoreFactory.create {
            File(testRoot, "settings.preferences_pb")
        }
        val secretStore = SecretStore(
            backend = object : SecretStoreBackend {
                private val map = mutableMapOf<String, String>()
                override fun get(key: String): String? = map[key]
                override fun put(key: String, value: String) {
                    map[key] = value
                }
                override fun remove(key: String) {
                    map.remove(key)
                }
                override fun keys(): Set<String> = map.keys.toSet()
            },
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = "enc:$plaintext"
                override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
            },
        )
        return SettingsAggregator(
            dataStore = dataStore,
            uiPrefs = UIPrefs(dataStore, appScope),
            searchPrefs = SearchPrefs(dataStore, appScope, secretStore),
            agentPrefs = AgentPrefs(dataStore, appScope),
            providerPrefs = ProviderPrefs(dataStore, appScope, secretStore),
            chatPrefs = ChatPrefs(dataStore, appScope, secretStore),
            extensionPrefs = ExtensionPrefs(dataStore, appScope, secretStore),
            scope = appScope,
            secretRedactor = SecretRedactor(secretStore),
        )
    }

    /** In-memory CasLedger fake recording every entry (digests only). */
    class FakeCasLedger : CasLedger {
        val entries = mutableListOf<ApprovalHistoryEntry>()

        override suspend fun recordApproval(entry: ApprovalHistoryEntry) {
            entries += entry
        }

        override suspend fun approvedDigest(sessionId: String): String? =
            entries.lastOrNull { it.toolCallId == sessionId }
                ?.takeIf { it.decision == "approved" }
                ?.argsDigest

        override suspend fun recordOutcome(sessionId: String, outcome: String) {
            val latest = entries.lastOrNull { it.toolCallId == sessionId } ?: return
            entries += latest.copy(
                id = "outcome-${entries.size}",
                decision = if (outcome == "rejected") "denied" else latest.decision,
                outcome = outcome,
            )
        }
    }
}

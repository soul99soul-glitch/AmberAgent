package app.amber.agent

import android.content.Context
import android.content.SharedPreferences
import kotlin.uuid.Uuid

/**
 * Durable ID store for the Personal Agent main stage conversations.
 *
 * Local and per-gateway cloud hosts each own one stable main conversation UUID.
 * IDs are persisted synchronously via [SharedPreferences.Editor.commit] before being returned,
 * never adopt historical sessions, and are not rotated if the underlying conversation is deleted.
 */
class PersonalAgentMainSessionStore private constructor(
    private val prefs: SharedPreferences,
    private val idGenerator: () -> String,
) {
    constructor(context: Context) : this(
        prefs = (context.applicationContext ?: context).getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        ),
        idGenerator = { Uuid.random().toString() },
    )

    @Synchronized
    fun localMainConversationId(): String = getOrCreate(KEY_LOCAL_MAIN_CONVERSATION_ID)

    @Synchronized
    fun cloudMainConversationId(gatewayId: String): String {
        require(gatewayId.isNotBlank()) { "gatewayId must not be blank" }
        return getOrCreate(cloudMainKey(gatewayId))
    }

    @Synchronized
    fun peekCloudMainConversationId(gatewayId: String): String? {
        if (gatewayId.isBlank()) return null
        return prefs.getString(cloudMainKey(gatewayId), null)?.takeIf(::isValidUuid)
    }

    private fun getOrCreate(key: String): String {
        val existing = prefs.getString(key, null)?.takeIf(::isValidUuid)
        if (existing != null) return existing
        val created = idGenerator()
        check(isValidUuid(created)) { "Generated conversation id must be a valid UUID: $created" }
        val committed = prefs.edit().putString(key, created).commit()
        check(committed) { "Failed to persist personal agent main conversation id for key=$key" }
        return created
    }

    companion object {
        internal const val PREFS_NAME = "personal_agent"
        internal const val KEY_LOCAL_MAIN_CONVERSATION_ID = "local_main_conversation_id"
        private const val KEY_CLOUD_MAIN_CONVERSATION_PREFIX = "cloud_main_conversation_id:"

        internal fun cloudMainKey(gatewayId: String): String =
            "$KEY_CLOUD_MAIN_CONVERSATION_PREFIX$gatewayId"

        internal fun createForTesting(
            prefs: SharedPreferences,
            idGenerator: () -> String = { Uuid.random().toString() },
        ): PersonalAgentMainSessionStore = PersonalAgentMainSessionStore(prefs, idGenerator)

        private fun isValidUuid(value: String): Boolean =
            runCatching { Uuid.parse(value) }.isSuccess
    }
}

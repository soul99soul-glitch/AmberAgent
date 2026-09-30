package app.amber.core.recap

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

enum class RecapFailure { ERROR, NO_MODEL }

/** UI-facing recap state of one conversation. */
data class RecapState(
    val recap: ConversationRecap? = null,
    /** True once the on-disk file has been read (or found missing). */
    val loaded: Boolean = false,
    val generating: Boolean = false,
    val failure: RecapFailure? = null,
    /** The full branch is below the recap threshold (the paged window could not tell). */
    val ineligible: Boolean = false,
) {
    val failed: Boolean get() = failure != null
}

/**
 * One JSON file per conversation under `filesDir/conversation_recaps/`. The directory
 * is not one of the sync archive roots, so recaps stay out of backups and are rebuilt
 * on demand after a restore.
 */
class ConversationRecapStore(
    private val rootDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<RecapState>>()

    fun observe(conversationId: String): StateFlow<RecapState> = flowFor(conversationId).asStateFlow()

    fun current(conversationId: String): RecapState = flowFor(conversationId).value

    suspend fun ensureLoaded(conversationId: String): ConversationRecap? {
        val flow = flowFor(conversationId)
        if (flow.value.loaded) return flow.value.recap
        val recap = withContext(Dispatchers.IO) { read(conversationId) }
        flow.update { if (it.loaded) it else it.copy(recap = recap, loaded = true) }
        return flow.value.recap
    }

    suspend fun save(recap: ConversationRecap) {
        withContext(Dispatchers.IO) {
            rootDir.mkdirs()
            val target = fileFor(recap.conversationId)
            val temp = File(rootDir, "${target.name}.tmp")
            temp.writeText(json.encodeToString(ConversationRecap.serializer(), recap))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
        flowFor(recap.conversationId).update {
            it.copy(recap = recap, loaded = true, generating = false, failure = null, ineligible = false)
        }
    }

    fun setGenerating(conversationId: String, generating: Boolean) {
        flowFor(conversationId).update {
            if (generating) it.copy(generating = true, failure = null, ineligible = false) else it.copy(generating = false)
        }
    }

    fun markFailed(conversationId: String, failure: RecapFailure) {
        flowFor(conversationId).update { it.copy(loaded = true, generating = false, failure = failure) }
    }

    fun markIneligible(conversationId: String) {
        flowFor(conversationId).update { it.copy(loaded = true, generating = false, failure = null, ineligible = true) }
    }

    suspend fun delete(conversationId: String) {
        withContext(Dispatchers.IO) { fileFor(conversationId).delete() }
        // Keep the flow instance: a live ChatVM may still be collecting it.
        states[conversationId]?.value = RecapState(loaded = true)
    }

    private fun read(conversationId: String): ConversationRecap? {
        val file = fileFor(conversationId)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(ConversationRecap.serializer(), file.readText()) }
            .getOrNull()
            ?.takeIf { it.conversationId == conversationId }
    }

    private fun flowFor(conversationId: String): MutableStateFlow<RecapState> =
        states.getOrPut(conversationId) { MutableStateFlow(RecapState()) }

    private fun fileFor(conversationId: String): File {
        require(conversationId.matches(SAFE_ID)) { "Invalid conversation id" }
        return File(rootDir, "$conversationId.json")
    }

    companion object {
        const val DIRECTORY = "conversation_recaps"
        private val SAFE_ID = Regex("[A-Za-z0-9-]+")
    }
}

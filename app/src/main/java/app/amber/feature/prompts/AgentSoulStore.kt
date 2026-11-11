package app.amber.feature.prompts

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val AgentSoulJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Snapshot entry in the `AgentSoulState.history` list (C-SOUL-V1 contract).
 */
@Serializable
data class AgentSoulSnapshot(
    val id: String,
    val displayName: String = AgentSoulStore.DEFAULT_DISPLAY_NAME,
    val tagline: String = "",
    val soulMarkdown: String = "",
    val updatedAtMs: Long = 0L,
    val source: String = AgentSoulStore.SOURCE_ANDROID_UI,
)

/**
 * Unified cross-platform Agent Soul state (`GET /v2/soul` & `PUT /v2/soul` response in C-SOUL-V1).
 */
@Serializable
data class AgentSoulState(
    val revision: Long = 0L,
    val updatedAtMs: Long = 0L,
    val displayName: String = AgentSoulStore.DEFAULT_DISPLAY_NAME,
    val tagline: String = "",
    val soulMarkdown: String = "",
    val isDefault: Boolean = true,
    val source: String = AgentSoulStore.SOURCE_ANDROID_UI,
    val history: List<AgentSoulSnapshot> = emptyList(),
)

/**
 * Request body for `PUT /v2/soul` (C-SOUL-V1 contract).
 */
@Serializable
data class AgentSoulPutRequest(
    val displayName: String,
    val tagline: String,
    val soulMarkdown: String,
    val isDefault: Boolean,
    val updatedAtMs: Long,
    val source: String,
)

@Serializable
private data class PersistedAgentSoulEnvelope(
    val state: AgentSoulState = AgentSoulState(),
    val lastSyncedRevision: Long = 0L,
    val pendingSync: Boolean = false,
)

/**
 * Local store for the unified「形象人设」(Agent Soul) state and its 8-entry snapshot history.
 *
 * Decoupled from `AgentRuntimeSetting.agentSoulMarkdown` (which holds the underlying Android tool
 * & execution discipline guide `<agents_md>`).
 *
 * Local mutations (`update`, `restoreSnapshot`, `resetToDefault`) persist immediately and notify
 * [localMutationListener] to asynchronously `PUT /v2/soul` to the paired cloud gateway.
 * Remote sync updates (`applyRemoteSoul`) update local state and `lastSyncedRevision` without
 * firing [localMutationListener], preventing echo/loop `PUT` calls.
 */
class AgentSoulStore(
    private val storageFile: File? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    onLocalMutation: ((AgentSoulState) -> Unit)? = null,
) {
    constructor(
        context: Context,
        clock: () -> Long = { System.currentTimeMillis() },
        onLocalMutation: ((AgentSoulState) -> Unit)? = null,
    ) : this(
        storageFile = File(File(context.filesDir, "agent_soul").apply { mkdirs() }, "agent_soul_state.json"),
        clock = clock,
        onLocalMutation = onLocalMutation,
    )

    private val lock = Any()

    @Volatile
    private var localMutationListener: ((AgentSoulState) -> Unit)? = onLocalMutation

    private val _state: MutableStateFlow<AgentSoulState>
    val state: StateFlow<AgentSoulState>

    @Volatile
    var lastSyncedRevision: Long = 0L
        private set

    @Volatile
    var hasPendingSync: Boolean = false
        private set

    init {
        val loaded = loadFromDisk()
        val normalized = normalizeState(loaded.state)
        _state = MutableStateFlow(normalized)
        state = _state.asStateFlow()
        lastSyncedRevision = loaded.lastSyncedRevision.coerceAtLeast(0L)
        hasPendingSync = loaded.pendingSync
    }

    fun setLocalMutationListener(listener: ((AgentSoulState) -> Unit)?) {
        localMutationListener = listener
    }

    /**
     * Updates the current「形象人设」. Pushes the previous state onto `history` (capped at 8).
     * Returns `true` if the state actually changed.
     */
    fun update(
        displayName: String? = null,
        tagline: String? = null,
        soulMarkdown: String? = null,
        resetToDefault: Boolean = false,
        source: String = SOURCE_ANDROID_UI,
    ): Boolean {
        if (resetToDefault) {
            return resetToDefault(source = source)
        }
        val mutated: AgentSoulState? = synchronized(lock) {
            val current = _state.value
            val resolvedName = displayName
                ?.let { clamp(it.trim(), MAX_NAME_LENGTH) }
                ?.ifEmpty { current.displayName.ifBlank { DEFAULT_DISPLAY_NAME } }
                ?: current.displayName.ifBlank { DEFAULT_DISPLAY_NAME }
            val resolvedTagline = tagline
                ?.let { clamp(it.trim(), MAX_TAGLINE_LENGTH) }
                ?: current.tagline
            val resolvedSoul = soulMarkdown
                ?.let { clamp(it.trim(), MAX_SOUL_LENGTH) }
                ?: current.soulMarkdown
            val resolvedIsDefault = computeIsDefault(resolvedName, resolvedTagline, resolvedSoul)

            if (
                resolvedName == current.displayName &&
                resolvedTagline == current.tagline &&
                resolvedSoul == current.soulMarkdown &&
                resolvedIsDefault == current.isDefault
            ) {
                return@synchronized null
            }

            val nextHistory = pushSnapshot(current)
            val now = clock()
            val next = AgentSoulState(
                revision = current.revision + 1L,
                updatedAtMs = now,
                displayName = resolvedName,
                tagline = resolvedTagline,
                soulMarkdown = resolvedSoul,
                isDefault = resolvedIsDefault,
                source = source.ifBlank { SOURCE_ANDROID_UI },
                history = nextHistory,
            )
            _state.value = next
            hasPendingSync = true
            persistLocked(next, lastSyncedRevision, pendingSync = true)
            next
        }
        if (mutated != null) {
            localMutationListener?.invoke(mutated)
            return true
        }
        return false
    }

    /**
     * Restores a historical snapshot by its [snapshotId], pushing the current state onto `history`
     * so the restore itself can also be undone.
     */
    fun restoreSnapshot(
        snapshotId: String,
        source: String = SOURCE_ANDROID_UI,
    ): Boolean {
        val snapshot = synchronized(lock) {
            _state.value.history.firstOrNull { it.id == snapshotId }
        } ?: return false

        val isTargetDefault = computeIsDefault(
            snapshot.displayName,
            snapshot.tagline,
            snapshot.soulMarkdown,
        )
        return if (isTargetDefault) {
            resetToDefault(source = source)
        } else {
            update(
                displayName = snapshot.displayName,
                tagline = snapshot.tagline,
                soulMarkdown = snapshot.soulMarkdown,
                resetToDefault = false,
                source = source,
            )
        }
    }

    /**
     * Resets「形象人设」back to default Amber, preserving the previous state in `history`.
     */
    fun resetToDefault(source: String = SOURCE_ANDROID_UI): Boolean {
        val mutated: AgentSoulState? = synchronized(lock) {
            val current = _state.value
            if (current.isDefault &&
                current.displayName == DEFAULT_DISPLAY_NAME &&
                current.tagline.isEmpty() &&
                current.soulMarkdown.isEmpty()
            ) {
                return@synchronized null
            }
            val nextHistory = pushSnapshot(current)
            val now = clock()
            val next = AgentSoulState(
                revision = current.revision + 1L,
                updatedAtMs = now,
                displayName = DEFAULT_DISPLAY_NAME,
                tagline = "",
                soulMarkdown = "",
                isDefault = true,
                source = source.ifBlank { SOURCE_ANDROID_UI },
                history = nextHistory,
            )
            _state.value = next
            hasPendingSync = true
            persistLocked(next, lastSyncedRevision, pendingSync = true)
            next
        }
        if (mutated != null) {
            localMutationListener?.invoke(mutated)
            return true
        }
        return false
    }

    /**
     * Applies authoritative state received from `GET /v2/soul` or `PUT /v2/soul` response without
     * firing [localMutationListener] (no loop `PUT`).
     */
    fun applyRemoteSoul(remote: AgentSoulState): Boolean = synchronized(lock) {
        val current = _state.value
        val wasDirty = hasPendingSync
        val normalizedRemote = normalizeState(remote)
        val localDiffersFromRemote = !current.isDefault && (
            current.displayName != normalizedRemote.displayName ||
                current.tagline != normalizedRemote.tagline ||
                current.soulMarkdown != normalizedRemote.soulMarkdown
            )
        val mergedHistory = when {
            normalizedRemote.history.isNotEmpty() -> {
                val baseHistory = normalizedRemote.history.take(MAX_HISTORY)
                if ((wasDirty || current.revision > lastSyncedRevision) && localDiffersFromRemote) {
                    val top = baseHistory.firstOrNull()
                    if (top != null &&
                        top.displayName == current.displayName &&
                        top.tagline == current.tagline &&
                        top.soulMarkdown == current.soulMarkdown
                    ) {
                        baseHistory
                    } else {
                        val localSnap = AgentSoulSnapshot(
                            id = UUID.randomUUID().toString(),
                            displayName = current.displayName.ifBlank { DEFAULT_DISPLAY_NAME },
                            tagline = current.tagline,
                            soulMarkdown = current.soulMarkdown,
                            updatedAtMs = current.updatedAtMs.takeIf { it > 0L } ?: clock(),
                            source = current.source.ifBlank { SOURCE_ANDROID_UI },
                        )
                        (listOf(localSnap) + baseHistory).take(MAX_HISTORY)
                    }
                } else {
                    baseHistory
                }
            }
            localDiffersFromRemote -> pushSnapshot(current)
            else -> current.history
        }
        val next = normalizedRemote.copy(history = mergedHistory)
        val newSyncedRevision = maxOf(lastSyncedRevision, next.revision)
        val changed = next != current || newSyncedRevision != lastSyncedRevision
        _state.value = next
        lastSyncedRevision = newSyncedRevision
        hasPendingSync = false
        if (changed || wasDirty) {
            persistLocked(next, lastSyncedRevision, pendingSync = false)
        }
        changed
    }

    fun toPutRequest(state: AgentSoulState = _state.value): AgentSoulPutRequest =
        AgentSoulPutRequest(
            displayName = state.displayName.ifBlank { DEFAULT_DISPLAY_NAME },
            tagline = state.tagline,
            soulMarkdown = state.soulMarkdown,
            isDefault = state.isDefault,
            updatedAtMs = state.updatedAtMs.takeIf { it > 0L } ?: clock(),
            source = state.source.ifBlank { SOURCE_ANDROID_UI },
        )

    fun snapshotDigest(state: AgentSoulState = _state.value): String {
        if (state.history.isEmpty()) return "（无历史人设快照）"
        return state.history.take(3).mapIndexed { index, snap ->
            val preview = snap.tagline.ifBlank {
                snap.soulMarkdown.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(32)
            }.ifBlank { "历史人设" }
            "${index + 1}. ${snap.displayName} · $preview"
        }.joinToString("\n")
    }

    private fun pushSnapshot(current: AgentSoulState): List<AgentSoulSnapshot> {
        val hasContent = !current.isDefault ||
            current.displayName != DEFAULT_DISPLAY_NAME ||
            current.tagline.isNotBlank() ||
            current.soulMarkdown.isNotBlank()
        if (!hasContent) return current.history.take(MAX_HISTORY)

        val snapshot = AgentSoulSnapshot(
            id = UUID.randomUUID().toString(),
            displayName = current.displayName.ifBlank { DEFAULT_DISPLAY_NAME },
            tagline = current.tagline,
            soulMarkdown = current.soulMarkdown,
            updatedAtMs = current.updatedAtMs.takeIf { it > 0L } ?: clock(),
            source = current.source.ifBlank { SOURCE_ANDROID_UI },
        )
        return (listOf(snapshot) + current.history).take(MAX_HISTORY)
    }

    private fun normalizeState(raw: AgentSoulState): AgentSoulState {
        val name = clamp(raw.displayName.trim(), MAX_NAME_LENGTH).ifEmpty { DEFAULT_DISPLAY_NAME }
        val tagline = clamp(raw.tagline.trim(), MAX_TAGLINE_LENGTH)
        val soul = clamp(raw.soulMarkdown.trim(), MAX_SOUL_LENGTH)
        val isDef = computeIsDefault(name, tagline, soul)
        val normalizedHistory = raw.history.take(MAX_HISTORY).map { snap ->
            snap.copy(
                displayName = clamp(snap.displayName.trim(), MAX_NAME_LENGTH).ifEmpty { DEFAULT_DISPLAY_NAME },
                tagline = clamp(snap.tagline.trim(), MAX_TAGLINE_LENGTH),
                soulMarkdown = clamp(snap.soulMarkdown.trim(), MAX_SOUL_LENGTH),
            )
        }
        return raw.copy(
            displayName = name,
            tagline = tagline,
            soulMarkdown = soul,
            isDefault = isDef,
            source = raw.source.ifBlank { SOURCE_ANDROID_UI },
            history = normalizedHistory,
        )
    }

    private fun loadFromDisk(): PersistedAgentSoulEnvelope {
        val file = storageFile ?: return PersistedAgentSoulEnvelope()
        if (!file.exists()) return PersistedAgentSoulEnvelope()
        return runCatching {
            AgentSoulJson.decodeFromString(PersistedAgentSoulEnvelope.serializer(), file.readText())
        }.getOrElse { PersistedAgentSoulEnvelope() }
    }

    private fun persistLocked(state: AgentSoulState, syncedRevision: Long, pendingSync: Boolean = hasPendingSync) {
        val file = storageFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val payload = AgentSoulJson.encodeToString(
                PersistedAgentSoulEnvelope.serializer(),
                PersistedAgentSoulEnvelope(
                    state = state,
                    lastSyncedRevision = syncedRevision,
                    pendingSync = pendingSync,
                ),
            )
            file.writeText(payload)
        }
    }

    companion object {
        const val DEFAULT_DISPLAY_NAME = "Amber"
        const val MAX_HISTORY = 8
        const val MAX_NAME_LENGTH = 32
        const val MAX_TAGLINE_LENGTH = 120
        const val MAX_SOUL_LENGTH = 12_000

        const val SOURCE_ANDROID_UI = "android_ui"
        const val SOURCE_ANDROID_CHAT = "android_chat"

        fun computeIsDefault(
            displayName: String,
            tagline: String,
            soulMarkdown: String,
        ): Boolean {
            val normalizedName = displayName.trim().ifEmpty { DEFAULT_DISPLAY_NAME }
            return normalizedName == DEFAULT_DISPLAY_NAME &&
                tagline.trim().isEmpty() &&
                soulMarkdown.trim().isEmpty()
        }

        private fun clamp(text: String, limit: Int): String =
            if (text.length <= limit) text else text.take(limit)
    }
}

package me.rerere.rikkahub.data.agent.icloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ICloudDriveManager(
    context: Context,
    private val cookieProvider: ICloudDriveCookieProvider,
    private val client: ICloudDriveClient,
) {
    private val prefs = context.getSharedPreferences("amberagent_icloud_drive", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val nodeCache = ICloudDriveNodeCache()
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<ICloudDriveState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = loadState()
    }

    fun setVaultPath(path: String) {
        val normalized = ICloudDrivePath.normalizeUserPath(path)
        val previous = _state.value
        val readableBefore = previous.capability == ICloudDriveCapability.READ_ONLY ||
            previous.capability == ICloudDriveCapability.READ_WRITE
        val nextStatus = when {
            !previous.enabled -> ICloudDriveStatus.NOT_CONFIGURED
            readableBefore -> ICloudDriveStatus.READ_ONLY
            previous.status == ICloudDriveStatus.ERROR -> ICloudDriveStatus.ERROR
            else -> ICloudDriveStatus.LOGIN_REQUIRED
        }
        val nextCapability = when {
            readableBefore -> ICloudDriveCapability.READ_ONLY
            else -> ICloudDriveCapability.NONE
        }
        val message = when {
            !previous.enabled -> null
            readableBefore -> "iCloud Vault path saved. Run the read probe to validate this path."
            previous.status == ICloudDriveStatus.ERROR -> previous.message
            else -> "iCloud Vault path saved. Log in with WebView, then run the read probe."
        }
        val updatedAtMillis = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_VAULT_PATH, normalized)
            .putBoolean(KEY_WRITE_VALIDATED, false)
            .putString(KEY_LAST_STATUS, nextStatus.wireName)
            .putString(KEY_LAST_CAPABILITY, nextCapability.wireName)
            .putString(KEY_LAST_MESSAGE, message)
            .putLong(KEY_LAST_UPDATED_AT, updatedAtMillis)
            .apply()
        _state.value = loadState()
        nodeCache.clear()
    }

    fun loginSnapshot(): ICloudDriveLoginSnapshot {
        val cookies = cookieProvider.getCookies()
        val loginDetected = !cookies.isEmpty && cookies.value("X-APPLE-WEBAUTH-TOKEN") != null
        val endpoint = if (loginDetected) ICloudDriveWebEndpoints.preferredFor(cookies).firstOrNull() else null
        return ICloudDriveLoginSnapshot(
            loginDetected = loginDetected,
            endpointHint = endpoint,
            hasUploadToken = cookies.value("X-APPLE-WEBAUTH-VALIDATE") != null,
        )
    }

    fun nextAction(state: ICloudDriveState = _state.value): String {
        val login = loginSnapshot()
        return when {
            !state.enabled -> "Enable iCloud WebMount"
            !login.loginDetected -> "Open iCloud login and finish 2FA"
            state.capability == ICloudDriveCapability.NONE -> "Run the read probe"
            state.capability == ICloudDriveCapability.READ_ONLY -> "Run the write probe if you need writes"
            else -> "Ready"
        }
    }

    suspend fun probe(): ICloudDriveState = mutex.withLock {
        nodeCache.clear()
        updateStatus(ICloudDriveStatus.PROBING, ICloudDriveCapability.NONE, "Probing iCloud Drive session")
        runCatching {
            val session = createSession()
            client.requestDriveAccess(session)
            val vault = _state.value.vaultPath
            client.list(session, ICloudDrivePath.resolve(vault, "."))
            val writeValidated = prefs.getBoolean(KEY_WRITE_VALIDATED, false)
            updateStatus(
                status = if (writeValidated) ICloudDriveStatus.READ_WRITE else ICloudDriveStatus.READ_ONLY,
                capability = if (writeValidated) ICloudDriveCapability.READ_WRITE else ICloudDriveCapability.READ_ONLY,
                message = if (vault.isBlank()) {
                    "iCloud Drive root is readable. Configure a Vault path before using Obsidian files."
                } else {
                    "iCloud Drive Vault is readable: $vault"
                },
            )
        }.getOrElse { error ->
            val status = if (error.message?.contains("cookie", ignoreCase = true) == true) {
                ICloudDriveStatus.LOGIN_REQUIRED
            } else {
                ICloudDriveStatus.ERROR
            }
            updateStatus(status, ICloudDriveCapability.NONE, error.message ?: error.toString())
        }
    }

    suspend fun runWriteProbe(): ICloudDriveState = mutex.withLock {
        nodeCache.clear()
        updateStatus(ICloudDriveStatus.PROBING, ICloudDriveCapability.READ_ONLY, "Running iCloud write probe")
        runCatching {
            val session = createSession()
            client.requestDriveAccess(session)
            val probePath = ICloudDrivePath.resolve(
                vaultPath = _state.value.vaultPath,
                relativePath = PROBE_FILE,
            )
            val content = "AmberAgent iCloud write probe\n${System.currentTimeMillis()}\n"
            client.writeText(session, probePath, content, overwrite = true)
            val readBack = client.readText(session, probePath).content
            require(readBack == content) { "Write probe read-back mismatch" }
            client.delete(session, probePath)
            nodeCache.clear()
            prefs.edit().putBoolean(KEY_WRITE_VALIDATED, true).apply()
            updateStatus(
                status = ICloudDriveStatus.READ_WRITE,
                capability = ICloudDriveCapability.READ_WRITE,
                message = "iCloud Drive write probe passed",
            )
        }.getOrElse { error ->
            prefs.edit().putBoolean(KEY_WRITE_VALIDATED, false).apply()
            updateStatus(ICloudDriveStatus.READ_ONLY, ICloudDriveCapability.READ_ONLY, error.message ?: error.toString())
        }
    }

    suspend fun list(path: String, maxEntries: Int): ICloudDriveToolResult<ICloudDriveListResult> = withReadySession(requireWrite = false) { session ->
        val resolved = ICloudDrivePath.resolve(_state.value.vaultPath, path)
        val entries = client.list(session, resolved).map { rememberEntry(it) }
        val capped = entries.take(maxEntries.coerceIn(1, 500))
        ICloudDriveToolResult(
            state = _state.value,
            path = resolved.relativePath,
            value = ICloudDriveListResult(
                entries = capped,
                totalEntries = entries.size,
                truncated = capped.size < entries.size,
            ),
        )
    }

    suspend fun readText(
        path: String?,
        nodeRef: String?,
    ): ICloudDriveToolResult<ICloudDriveReadResult> = withReadySession(requireWrite = false) { session ->
        val decoded = nodeCache.resolve(nodeRef) ?: ICloudDriveNodeRefs.decode(nodeRef)
        val relativePath = decoded?.path ?: path
        require(!relativePath.isNullOrBlank()) { "path or node_ref is required" }
        val resolved = ICloudDrivePath.resolve(_state.value.vaultPath, relativePath)
        val result = client.readText(session, resolved, decoded)
        ICloudDriveToolResult(
            state = _state.value,
            path = resolved.relativePath,
            value = result.copy(entry = rememberEntry(result.entry)),
        )
    }

    suspend fun stat(
        path: String?,
        nodeRef: String?,
    ): ICloudDriveToolResult<ICloudDriveStatResult> = withReadySession(requireWrite = false) { session ->
        val decoded = nodeCache.resolve(nodeRef) ?: ICloudDriveNodeRefs.decode(nodeRef)
        val relativePath = decoded?.path ?: path
        require(!relativePath.isNullOrBlank()) { "path or node_ref is required" }
        val resolved = ICloudDrivePath.resolve(_state.value.vaultPath, relativePath)
        val result = client.stat(session, resolved, decoded)
        ICloudDriveToolResult(
            state = _state.value,
            path = resolved.relativePath,
            value = result.copy(entry = rememberEntry(result.entry)),
        )
    }

    suspend fun writeText(path: String, content: String, overwrite: Boolean): ICloudDriveToolResult<ICloudDriveEntry> =
        withReadySession(requireWrite = true) { session ->
            val resolved = ICloudDrivePath.resolve(_state.value.vaultPath, path)
            ICloudDriveToolResult(
                state = _state.value,
                path = resolved.relativePath,
                value = client.writeText(session, resolved, content, overwrite),
            )
        }

    suspend fun search(query: String, path: String, maxResults: Int): ICloudDriveToolResult<List<ICloudDriveSearchResult>> =
        withReadySession(requireWrite = false) { session ->
            val resolved = ICloudDrivePath.resolve(_state.value.vaultPath, path)
            ICloudDriveToolResult(
                state = _state.value,
                path = resolved.relativePath,
                value = client.search(session, resolved, query, maxResults.coerceIn(1, 50))
                    .map { rememberSearchResult(it) },
            )
        }

    private suspend fun <T> withReadySession(
        requireWrite: Boolean,
        block: suspend (ICloudDriveSession) -> ICloudDriveToolResult<T>,
    ): ICloudDriveToolResult<T> = withContext(Dispatchers.IO) {
        require(_state.value.enabled) { "iCloud Drive mount is not enabled" }
        require(_state.value.vaultPath.isNotBlank()) { "Configure an iCloud Vault path first" }
        if (requireWrite) {
            require(prefs.getBoolean(KEY_WRITE_VALIDATED, false)) {
                "iCloud write is disabled until the write probe passes"
            }
        }
        val session = createSession()
        client.requestDriveAccess(session)
        block(session)
    }

    private suspend fun createSession(): ICloudDriveSession = withContext(Dispatchers.IO) {
        require(_state.value.enabled) { "iCloud Drive mount is not enabled" }
        val clientId = prefs.getString(KEY_CLIENT_ID, null)
            ?: ICloudDriveClient.newClientId().also {
                prefs.edit().putString(KEY_CLIENT_ID, it).apply()
            }
        client.validateSession(clientId, cookieProvider.getCookies())
    }

    private fun rememberEntry(entry: ICloudDriveEntry): ICloudDriveEntry {
        val drivewsId = entry.drivewsId ?: return entry
        val ref = ICloudDriveNodeRefs.encode(
            ICloudDriveNodeRef(
                path = entry.path,
                drivewsId = drivewsId,
                docwsId = entry.docwsId,
                etag = entry.etag,
            )
        )
        nodeCache.put(ref, entry)
        return entry.copy(nodeRef = ref)
    }

    private fun rememberSearchResult(result: ICloudDriveSearchResult): ICloudDriveSearchResult {
        val drivewsId = result.drivewsId ?: return result
        val ref = ICloudDriveNodeRefs.encode(
            ICloudDriveNodeRef(
                path = result.path,
                drivewsId = drivewsId,
                docwsId = result.docwsId,
                etag = result.etag,
            )
        )
        nodeCache.put(
            ref,
            ICloudDriveEntry(
                path = result.path,
                name = result.path.substringAfterLast("/"),
                directory = false,
                sizeBytes = null,
                drivewsId = result.drivewsId,
                docwsId = result.docwsId,
                etag = result.etag,
            )
        )
        return result.copy(nodeRef = ref)
    }

    private fun updateStatus(
        status: ICloudDriveStatus,
        capability: ICloudDriveCapability,
        message: String?,
    ): ICloudDriveState {
        val updated = loadState().copy(
            status = status,
            capability = capability,
            message = message,
            updatedAtMillis = System.currentTimeMillis(),
        )
        _state.value = updated
        prefs.edit()
            .putString(KEY_LAST_STATUS, status.wireName)
            .putString(KEY_LAST_CAPABILITY, capability.wireName)
            .putString(KEY_LAST_MESSAGE, message)
            .putLong(KEY_LAST_UPDATED_AT, updated.updatedAtMillis)
            .apply()
        return updated
    }

    private fun loadState(): ICloudDriveState {
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val writeValidated = prefs.getBoolean(KEY_WRITE_VALIDATED, false)
        val storedStatus = prefs.getString(KEY_LAST_STATUS, null)?.let { raw ->
            ICloudDriveStatus.entries.firstOrNull { it.wireName == raw }
        }?.takeUnless { enabled && it == ICloudDriveStatus.NOT_CONFIGURED }
        val storedCapability = prefs.getString(KEY_LAST_CAPABILITY, null)?.let { raw ->
            ICloudDriveCapability.entries.firstOrNull { it.wireName == raw }
        }
        val defaultStatus = if (!enabled) {
            ICloudDriveStatus.NOT_CONFIGURED
        } else if (writeValidated) {
            ICloudDriveStatus.READ_WRITE
        } else {
            ICloudDriveStatus.LOGIN_REQUIRED
        }
        val defaultCapability = if (writeValidated) ICloudDriveCapability.READ_WRITE else ICloudDriveCapability.NONE
        return ICloudDriveState(
            enabled = enabled,
            vaultPath = prefs.getString(KEY_VAULT_PATH, "").orEmpty(),
            status = storedStatus ?: defaultStatus,
            capability = storedCapability ?: defaultCapability,
            message = prefs.getString(KEY_LAST_MESSAGE, null),
            updatedAtMillis = prefs.getLong(KEY_LAST_UPDATED_AT, 0L),
        )
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_VAULT_PATH = "vault_path"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_WRITE_VALIDATED = "write_validated"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LAST_CAPABILITY = "last_capability"
        private const val KEY_LAST_MESSAGE = "last_message"
        private const val KEY_LAST_UPDATED_AT = "last_updated_at"
        private const val PROBE_FILE = ".amberagent_probe.md"
    }
}

data class ICloudDriveToolResult<T>(
    val state: ICloudDriveState,
    val path: String,
    val value: T,
)

private class ICloudDriveNodeCache {
    private val byRef = linkedMapOf<String, ICloudDriveEntry>()

    @Synchronized
    fun put(ref: String, entry: ICloudDriveEntry) {
        byRef[ref] = entry
        while (byRef.size > MAX_ENTRIES) {
            val first = byRef.keys.firstOrNull() ?: break
            byRef.remove(first)
        }
    }

    @Synchronized
    fun clear() {
        byRef.clear()
    }

    @Synchronized
    fun resolve(ref: String?): ICloudDriveNodeRef? {
        if (ref.isNullOrBlank()) return null
        val entry = byRef[ref] ?: return null
        return ICloudDriveNodeRef(
            path = entry.path,
            drivewsId = entry.drivewsId,
            docwsId = entry.docwsId,
            etag = entry.etag,
        )
    }

    private companion object {
        const val MAX_ENTRIES = 1_000
    }
}

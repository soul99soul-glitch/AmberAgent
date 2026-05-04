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
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<ICloudDriveState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = loadState()
    }

    fun setVaultPath(path: String) {
        val normalized = ICloudDrivePath.normalizeUserPath(path)
        prefs.edit()
            .putString(KEY_VAULT_PATH, normalized)
            .putBoolean(KEY_WRITE_VALIDATED, false)
            .apply()
        _state.value = loadState().copy(
            status = if (_state.value.enabled) ICloudDriveStatus.NOT_CONFIGURED else ICloudDriveStatus.NOT_CONFIGURED,
            capability = ICloudDriveCapability.NONE,
            message = null,
        )
    }

    suspend fun probe(): ICloudDriveState = mutex.withLock {
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
            val readBack = client.readText(session, probePath)
            require(readBack == content) { "Write probe read-back mismatch" }
            client.delete(session, probePath)
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

    suspend fun list(path: String): ICloudDriveToolResult<List<ICloudDriveEntry>> = withReadySession(requireWrite = false) { session ->
        val resolved = ICloudDrivePath.resolve(_state.value.vaultPath, path)
        ICloudDriveToolResult(
            state = _state.value,
            path = resolved.relativePath,
            value = client.list(session, resolved),
        )
    }

    suspend fun readText(path: String): ICloudDriveToolResult<String> = withReadySession(requireWrite = false) { session ->
        val resolved = ICloudDrivePath.resolve(_state.value.vaultPath, path)
        ICloudDriveToolResult(
            state = _state.value,
            path = resolved.relativePath,
            value = client.readText(session, resolved),
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
                value = client.search(session, resolved, query, maxResults.coerceIn(1, 50)),
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
        }
        val storedCapability = prefs.getString(KEY_LAST_CAPABILITY, null)?.let { raw ->
            ICloudDriveCapability.entries.firstOrNull { it.wireName == raw }
        }
        val defaultStatus = if (!enabled) {
            ICloudDriveStatus.NOT_CONFIGURED
        } else if (writeValidated) {
            ICloudDriveStatus.READ_WRITE
        } else {
            ICloudDriveStatus.NOT_CONFIGURED
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

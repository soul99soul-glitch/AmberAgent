package app.amber.feature.miniapp

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.dao.MiniAppDAO
import me.rerere.rikkahub.data.db.dao.MiniAppAuditLogDAO
import me.rerere.rikkahub.data.db.dao.MiniAppGrantDAO
import me.rerere.rikkahub.data.db.dao.MiniAppSharedDataDAO
import me.rerere.rikkahub.data.db.dao.MiniAppVersionDAO
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MiniAppAuditLogEntity
import me.rerere.rikkahub.data.db.entity.MiniAppEntity
import me.rerere.rikkahub.data.db.entity.MiniAppGrantEntity
import me.rerere.rikkahub.data.db.entity.MiniAppSharedDataEntity
import me.rerere.rikkahub.data.db.entity.MiniAppVersionEntity
import java.security.MessageDigest
import kotlin.uuid.Uuid

class MiniAppRepository(
    private val database: AppDatabase,
    private val dao: MiniAppDAO,
    private val grantDao: MiniAppGrantDAO,
    private val versionDao: MiniAppVersionDAO,
    private val auditLogDao: MiniAppAuditLogDAO,
    private val sharedDataDao: MiniAppSharedDataDAO,
    private val json: Json,
) {
    fun observeAll(): Flow<List<MiniAppEntity>> = dao.observeAll()

    suspend fun getById(id: String): MiniAppEntity? = dao.getById(id)

    suspend fun saveGenerated(
        output: MiniAppGeneratedOutput,
        sourceConversationId: String? = null,
        sourceMessageId: String? = null,
    ): MiniAppEntity {
        MiniAppHtmlValidator.validate(output.html)
        val now = System.currentTimeMillis()
        val htmlHash = sha256(output.html)
        val entity = MiniAppEntity(
            id = Uuid.random().toString(),
            title = output.title.trim(),
            description = output.description.trim(),
            htmlContent = output.html,
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            iconEmoji = output.icon?.trim()?.ifBlank { null },
            category = output.category,
            permissionsJson = json.encodeToString(output.permissions),
            htmlHash = htmlHash,
            createdAt = now,
            updatedAt = now,
        )
        database.withTransaction {
            dao.upsert(entity)
            versionDao.upsert(
                MiniAppVersionEntity(
                    appId = entity.id,
                    versionNumber = entity.version,
                    htmlContent = entity.htmlContent,
                    htmlHash = htmlHash,
                    changeNote = "Initial version",
                    createdAt = now,
                )
            )
        }
        return entity
    }

    suspend fun saveRevision(
        appId: String,
        output: MiniAppGeneratedOutput,
        expectedBaseVersion: Int? = null,
        sourceMessageId: String? = null,
        changeNote: String? = null,
    ): MiniAppEntity? {
        MiniAppHtmlValidator.validate(output.html)
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val app = dao.getById(appId) ?: return@withTransaction null
            if (expectedBaseVersion != null && app.version != expectedBaseVersion) {
                return@withTransaction null
            }
            val nextVersion = versionDao.maxVersionNumber(app.id) + 1
            val htmlHash = sha256(output.html)
            val updated = app.copy(
                title = output.title.trim(),
                description = output.description.trim(),
                htmlContent = output.html,
                sourceMessageId = sourceMessageId ?: app.sourceMessageId,
                iconEmoji = output.icon?.trim()?.ifBlank { null },
                category = output.category,
                permissionsJson = json.encodeToString(output.permissions),
                htmlHash = htmlHash,
                version = nextVersion,
                updatedAt = now,
            )
            dao.upsert(updated)
            versionDao.upsert(
                MiniAppVersionEntity(
                    appId = updated.id,
                    versionNumber = nextVersion,
                    htmlContent = updated.htmlContent,
                    htmlHash = htmlHash,
                    changeNote = changeNote?.trim()?.take(240) ?: "MiniApp revision",
                    createdAt = now,
                )
            )
            versionDao.pruneOldVersions(updated.id, MINI_APP_VERSION_KEEP_LIMIT)
            updated
        }
    }

    suspend fun upsert(entity: MiniAppEntity) {
        MiniAppHtmlValidator.validate(entity.htmlContent)
        database.withTransaction {
            dao.upsert(entity)
            versionDao.upsert(
                MiniAppVersionEntity(
                    appId = entity.id,
                    versionNumber = entity.version,
                    htmlContent = entity.htmlContent,
                    htmlHash = entity.htmlHash ?: sha256(entity.htmlContent),
                    changeNote = "Saved version",
                    createdAt = System.currentTimeMillis(),
                )
            )
            versionDao.pruneOldVersions(entity.id, MINI_APP_VERSION_KEEP_LIMIT)
        }
    }

    suspend fun delete(id: String) {
        database.withTransaction {
            dao.deleteById(id)
            grantDao.deleteForApp(id)
            versionDao.deleteForApp(id)
            auditLogDao.deleteForApp(id)
            sharedDataDao.deleteForApp(id)
        }
    }

    fun observeVersions(appId: String): Flow<List<MiniAppVersionEntity>> = versionDao.observeForApp(appId)

    suspend fun getVersion(appId: String, versionNumber: Int): MiniAppVersionEntity? =
        versionDao.get(appId, versionNumber)

    suspend fun saveNewVersion(app: MiniAppEntity, htmlContent: String, changeNote: String? = null): MiniAppEntity {
        MiniAppHtmlValidator.validate(htmlContent)
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val nextVersion = versionDao.maxVersionNumber(app.id) + 1
            val hash = sha256(htmlContent)
            val updated = app.copy(
                htmlContent = htmlContent,
                htmlHash = hash,
                version = nextVersion,
                updatedAt = now,
            )
            dao.upsert(updated)
            versionDao.upsert(
                MiniAppVersionEntity(
                    appId = app.id,
                    versionNumber = nextVersion,
                    htmlContent = htmlContent,
                    htmlHash = hash,
                    changeNote = changeNote,
                    createdAt = now,
                )
            )
            versionDao.pruneOldVersions(app.id, MINI_APP_VERSION_KEEP_LIMIT)
            updated
        }
    }

    suspend fun restoreVersion(appId: String, versionNumber: Int): MiniAppEntity? {
        val app = dao.getById(appId) ?: return null
        val version = versionDao.get(appId, versionNumber) ?: return null
        return saveNewVersion(app, version.htmlContent, "Restored from v$versionNumber")
    }

    suspend fun setGrant(appId: String, permission: String, decision: MiniAppGrantDecision) {
        grantDao.upsert(
            MiniAppGrantEntity(
                appId = appId,
                permission = permission,
                decision = decision.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun grantDecision(appId: String, permission: String): MiniAppGrantDecision? {
        return grantDao.get(appId, permission)?.decision?.let {
            runCatching { MiniAppGrantDecision.valueOf(it) }.getOrNull()
        }
    }

    suspend fun markRun(id: String) = dao.markRun(id)

    suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned, System.currentTimeMillis())

    suspend fun rename(id: String, title: String, description: String) {
        dao.rename(
            id = id,
            title = title.trim().take(40).ifBlank { "未命名小应用" },
            description = description.trim().take(120),
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun updateBoardSummary(id: String, summary: String) {
        dao.updateBoardSummary(id, summary.trim().take(500), System.currentTimeMillis())
    }

    fun observeAuditLogs(appId: String, limit: Int = 100): Flow<List<MiniAppAuditLogEntity>> =
        auditLogDao.observeForApp(appId, limit)

    suspend fun audit(
        appId: String,
        method: String,
        permission: MiniAppPermission,
        summary: String,
        payload: String,
    ) {
        auditLogDao.insert(
            MiniAppAuditLogEntity(
                id = Uuid.random().toString(),
                appId = appId,
                method = method,
                permission = permission.value,
                summary = summary.take(180),
                payloadHash = sha256(payload),
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun sharedGet(appId: String, namespace: String, key: String): JsonElement? {
        val normalized = validateSharedNamespace(appId, namespace)
        val safeKey = validateSharedKey(key)
        return sharedDataDao.get(normalized, safeKey)?.value?.let {
            runCatching { json.parseToJsonElement(it) }.getOrNull()
        }
    }

    suspend fun sharedSet(appId: String, namespace: String, key: String, value: JsonElement) {
        val normalized = validateSharedNamespace(appId, namespace)
        val safeKey = validateSharedKey(key)
        val encoded = json.encodeToString(JsonElement.serializer(), value)
        val valueBytes = encoded.encodeToByteArray().size
        if (valueBytes > MINI_APP_SHARED_VALUE_BYTES) {
            throw MiniAppValidationException("SharedStore value is too large")
        }
        val currentBytes = sharedDataDao.namespaceBytes(normalized)
        val oldBytes = sharedDataDao.get(normalized, safeKey)?.value?.encodeToByteArray()?.size ?: 0
        if (currentBytes - oldBytes + valueBytes > MINI_APP_SHARED_NAMESPACE_BYTES) {
            throw MiniAppValidationException("SharedStore namespace is full")
        }
        sharedDataDao.upsert(
            MiniAppSharedDataEntity(
                namespace = normalized,
                key = safeKey,
                value = encoded,
                lastWriterId = appId,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun sharedRemove(appId: String, namespace: String, key: String) {
        sharedDataDao.delete(validateSharedNamespace(appId, namespace), validateSharedKey(key))
    }

    fun toCardRef(entity: MiniAppEntity): MiniAppCardRef {
        val permissions = runCatching {
            json.decodeFromString<List<String>>(entity.permissionsJson)
        }.getOrDefault(emptyList())
        return MiniAppCardRef(
            appId = entity.id,
            title = entity.title,
            description = entity.description,
            iconEmoji = entity.iconEmoji,
            category = entity.category,
            permissions = permissions,
            htmlHash = entity.htmlHash,
            version = entity.version,
        )
    }

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun validateSharedNamespace(appId: String, namespace: String): String {
        val normalized = namespace.trim().ifBlank { appId }
        if (normalized != appId) {
            throw MiniAppValidationException("Cross-app SharedStore namespaces are not granted yet")
        }
        return normalized
    }

    private fun validateSharedKey(key: String): String {
        val normalized = key.trim()
        if (normalized.length !in 1..64 || !SHARED_KEY_REGEX.matches(normalized)) {
            throw MiniAppValidationException("Invalid SharedStore key")
        }
        return normalized
    }

    private companion object {
        val SHARED_KEY_REGEX = Regex("""[a-zA-Z0-9._:-]+""")
        const val MINI_APP_SHARED_VALUE_BYTES = 32 * 1024
        const val MINI_APP_SHARED_NAMESPACE_BYTES = 2 * 1024 * 1024
    }
}

package app.amber.feature.recipe

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import app.amber.core.infra.PreferencesKeys
import app.amber.feature.runtime.ContentDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * P4-01 installed recipe registry (parity plan §10 P4-01).
 *
 * Storage follows the CapabilityPermissionStore pattern: one standalone
 * Settings DataStore key, no Room migration. Each record keeps the raw
 * manifest plus digests and exactly one previous version for a single
 * explicit rollback. All mutations are CAS inside one DataStore edit.
 */
@Serializable
data class RecipeRecord(
    val name: String,
    val manifestJson: String,
    val digest: String,
    val previous: RecipePreviousRecord? = null,
)

/** The version replaced by the current one; restored by one explicit rollback. */
@Serializable
data class RecipePreviousRecord(
    val manifestJson: String,
    val digest: String,
    /** Digest of the version that was active when this previous was saved. */
    val candidateDigest: String,
)

sealed class RecipeRegistryApplyResult {
    data class Applied(val name: String) : RecipeRegistryApplyResult()
    data class Stale(val reason: String) : RecipeRegistryApplyResult()
}

sealed class RecipeRegistryRollbackResult {
    data class RolledBack(val name: String) : RecipeRegistryRollbackResult()
    data class NoPrevious(val reason: String) : RecipeRegistryRollbackResult()
    data class Stale(val reason: String) : RecipeRegistryRollbackResult()
}

sealed class RecipeRegistryDeleteResult {
    data class Deleted(val name: String) : RecipeRegistryDeleteResult()
    data class NotFound(val name: String) : RecipeRegistryDeleteResult()
    data class Stale(val reason: String) : RecipeRegistryDeleteResult()
}

class RecipeRegistry(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Volatile
    private var latestSnapshot: List<RecipeRecord> = emptyList()

    private val recipesFlow: Flow<List<RecipeRecord>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decode(prefs[PreferencesKeys.RECIPE_REGISTRY]) }
        .distinctUntilChanged()

    suspend fun installed(): List<RecipeRecord> = recipesFlow.first().also { latestSnapshot = it }

    /** Non-suspending view used only at a model-round boundary. */
    fun installedSnapshot(): List<RecipeRecord> = latestSnapshot

    suspend fun get(name: String): RecipeRecord? = installed().firstOrNull { it.name == name }

    /**
     * CAS apply: the approval binds base (installed) + candidate (new manifest)
     * digests; apply re-reads the installed version inside the same edit and
     * rejects the write as stale when either side changed since the preview.
     * The replaced version is kept as [RecipeRecord.previous].
     */
    suspend fun apply(name: String, manifestJson: String, expectedBoundDigest: String): RecipeRegistryApplyResult {
        var result: RecipeRegistryApplyResult = RecipeRegistryApplyResult.Stale("no-op")
        dataStore.edit { prefs ->
            val records = decode(prefs[PreferencesKeys.RECIPE_REGISTRY]).toMutableList()
            val current = records.firstOrNull { it.name == name }
            val baseDigest = current?.digest ?: EMPTY_RECIPE_DIGEST
            val candidateDigest = ContentDigest.sha256(manifestJson)
            val bound = ContentDigest.bind(baseDigest, candidateDigest)
            if (bound != expectedBoundDigest) {
                result = RecipeRegistryApplyResult.Stale(
                    "The recipe or its installed version changed after the preview; re-run recipe_preview and approve the new digest."
                )
                return@edit
            }
            val next = RecipeRecord(
                name = name,
                manifestJson = manifestJson,
                digest = candidateDigest,
                previous = current?.let { existing ->
                    RecipePreviousRecord(
                        manifestJson = existing.manifestJson,
                        digest = existing.digest,
                        // CAS target for rollback: the version this apply
                        // installed (the current record's digest).
                        candidateDigest = candidateDigest,
                    )
                },
            )
            records.removeAll { it.name == name }
            records.add(next)
            prefs[PreferencesKeys.RECIPE_REGISTRY] = encode(records)
            result = RecipeRegistryApplyResult.Applied(name)
        }
        if (result is RecipeRegistryApplyResult.Applied) latestSnapshot = installed()
        return result
    }

    /**
     * One explicit rollback. CAS: the current version must still be the one
     * the previous snapshot was saved against; the restored version replaces
     * the current one and the previous snapshot is deleted (no second
     * rollback).
     */
    suspend fun rollback(name: String): RecipeRegistryRollbackResult {
        var result: RecipeRegistryRollbackResult = RecipeRegistryRollbackResult.NoPrevious("no-op")
        dataStore.edit { prefs ->
            val records = decode(prefs[PreferencesKeys.RECIPE_REGISTRY]).toMutableList()
            val index = records.indexOfFirst { it.name == name }
            if (index < 0) {
                result = RecipeRegistryRollbackResult.NoPrevious("Recipe '$name' is not installed")
                return@edit
            }
            val current = records[index]
            val previous = current.previous
            if (previous == null) {
                result = RecipeRegistryRollbackResult.NoPrevious("Recipe '$name' has no previous version to roll back to")
                return@edit
            }
            if (current.digest != previous.candidateDigest) {
                result = RecipeRegistryRollbackResult.Stale(
                    "The installed recipe changed again after the last promotion; a rollback would overwrite the newer version."
                )
                return@edit
            }
            records[index] = RecipeRecord(
                name = name,
                manifestJson = previous.manifestJson,
                digest = previous.digest,
                previous = null,
            )
            prefs[PreferencesKeys.RECIPE_REGISTRY] = encode(records)
            result = RecipeRegistryRollbackResult.RolledBack(name)
        }
        if (result is RecipeRegistryRollbackResult.RolledBack) latestSnapshot = installed()
        return result
    }

    /** Remove the recipe only when the caller still holds the expected digest. */
    suspend fun delete(name: String, expectedDigest: String): RecipeRegistryDeleteResult {
        var result: RecipeRegistryDeleteResult = RecipeRegistryDeleteResult.NotFound(name)
        dataStore.edit { prefs ->
            val records = decode(prefs[PreferencesKeys.RECIPE_REGISTRY]).toMutableList()
            val current = records.firstOrNull { it.name == name }
            when {
                current == null -> Unit
                current.digest != expectedDigest -> {
                    result = RecipeRegistryDeleteResult.Stale(
                        "The installed recipe changed after the delete approval; refresh the recipe digest and retry."
                    )
                }
                else -> {
                    records.removeAll { it.name == name && it.digest == expectedDigest }
                    prefs[PreferencesKeys.RECIPE_REGISTRY] = encode(records)
                    result = RecipeRegistryDeleteResult.Deleted(name)
                }
            }
        }
        if (result is RecipeRegistryDeleteResult.Deleted) latestSnapshot = installed()
        return result
    }

    private fun decode(raw: String?): List<RecipeRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(RecipeRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun encode(records: List<RecipeRecord>): String =
        json.encodeToString(ListSerializer(RecipeRecord.serializer()), records.sortedBy { it.name })
}

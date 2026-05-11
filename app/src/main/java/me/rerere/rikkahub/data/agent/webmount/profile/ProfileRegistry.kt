package me.rerere.rikkahub.data.agent.webmount.profile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.security.MessageDigest

/**
 * Phase 2 M2.1 — Loaded-profile registry.
 *
 * Bootstraps on construction:
 *  1. Scans the `assets/webmount/profiles/` directory for built-in JSON profiles
 *     (always granted full declared permissions).
 *  2. Reads `imported.<id>` entries from an EncryptedSharedPreferences
 *     file for user-imported profiles (read-only permissions only,
 *     unless the user opted in per-profile via [setNonReadOnlyGranted]).
 *
 * Exposes both an indexed [forOrigin] / [byId] lookup and a [entries]
 * StateFlow so the UI can recompose when the user imports / removes /
 * toggles a profile.
 *
 * Thread safety: bootstrap runs synchronously on the constructor thread;
 * mutating operations ([importJson], [remove], [setNonReadOnlyGranted])
 * are `@Synchronized` so race-free for the small caller set.
 */
class ProfileRegistry(
    private val context: Context,
    /**
     * For tests / instrumentation: override the asset scanner. The default
     * implementation walks the `webmount/profiles/` asset directory.
     */
    private val builtInLoader: BuiltInLoader = AssetsBuiltInLoader(context),
) {

    private val prefs: SharedPreferences? = tryCreateEncryptedPrefs(context)
    private val mem = mutableMapOf<String, StoredImportedBlob>() // fallback if encrypted prefs fail

    private val _entries = MutableStateFlow<List<SiteProfileEntry>>(emptyList())
    val entries: StateFlow<List<SiteProfileEntry>> = _entries.asStateFlow()

    init {
        reload()
    }

    /**
     * Synchronous refresh — reads built-ins + imported entries and rebuilds
     * the live index. Cheap (parses ~20 small JSON files); called once at
     * startup and again after every mutating operation.
     */
    @Synchronized
    fun reload() {
        val builtIn = builtInLoader.load()
            .mapNotNull { (rawJson, sha) -> tryParseBuiltIn(rawJson, sha) }
        val imported = readAllImportedBlobs()
            .mapNotNull { (id, blob) -> tryParseImported(id, blob) }
        // Dedup by id with built-in winning over user-imported. A user
        // shouldn't be able to shadow a built-in profile by importing one
        // with the same id — that would let a malicious profile pretend to
        // be the trusted version.
        val merged = (builtIn + imported.filter { it.profile.id !in builtIn.map { b -> b.profile.id }.toSet() })
            .sortedBy { it.profile.id }
        _entries.value = merged
        Log.i(TAG, "Loaded ${builtIn.size} built-in + ${imported.size} user-imported profiles")
    }

    /** Lookup by stable id. */
    fun byId(id: String): SiteProfileEntry? = _entries.value.firstOrNull { it.profile.id == id }

    /**
     * Return the profile that claims [url]'s origin, if any. Origin
     * matching is exact — no subdomain expansion. When multiple profiles
     * could match (a future scenario), built-ins win.
     */
    fun forUrl(url: String): SiteProfileEntry? {
        val origin = extractOrigin(url) ?: return null
        return _entries.value.firstOrNull { entry ->
            origin in entry.profile.origins
        }
    }

    /**
     * Parse + validate + persist a user-imported profile. Returns a
     * structured result so the UI can show either success or the exact
     * validation error.
     */
    @Synchronized
    fun importJson(rawJson: String): ImportResult {
        val parsed = runCatching { profileJson.decodeFromString(SiteProfile.serializer(), rawJson) }
            .getOrElse { return ImportResult.ParseError(it.message ?: "JSON parse failed") }
        runCatching { parsed.validate() }
            .onFailure { return ImportResult.ValidationError(it.message ?: "validation failed") }
        // Refuse to shadow a built-in profile id.
        if (_entries.value.any { it.profile.id == parsed.id && it is SiteProfileEntry.BuiltIn }) {
            return ImportResult.ConflictWithBuiltIn(parsed.id)
        }
        val sha = sha256Hex(rawJson)
        val blob = StoredImportedBlob(
            rawJson = rawJson,
            importedAtMs = System.currentTimeMillis(),
            grantedNonReadOnly = emptySet(),
            sha256 = sha,
        )
        writeImportedBlob(parsed.id, blob)
        reload()
        return ImportResult.Imported(parsed, sha)
    }

    @Synchronized
    fun remove(id: String): Boolean {
        // Built-ins are immovable.
        if (_entries.value.any { it.profile.id == id && it is SiteProfileEntry.BuiltIn }) return false
        val ok = removeImportedBlob(id)
        if (ok) reload()
        return ok
    }

    /**
     * Opt-in for non-read-only permissions on a user-imported profile.
     * `permissionWire` is the exact wire form from
     * [ProfilePermission.wire] (e.g. `call_page_fn:__zse_signRequest`).
     * Returns false if the profile isn't user-imported or doesn't declare
     * that permission.
     */
    @Synchronized
    fun setNonReadOnlyGranted(id: String, permissionWire: String, granted: Boolean): Boolean {
        val current = readImportedBlob(id) ?: return false
        val parsed = runCatching { profileJson.decodeFromString(SiteProfile.serializer(), current.rawJson) }
            .getOrNull() ?: return false
        if (permissionWire !in parsed.permissions) return false
        val perm = runCatching { ProfilePermission.parse(permissionWire, parsed.id) }.getOrNull() ?: return false
        if (perm.isReadOnly()) return false // already granted by default
        val nextSet = if (granted) current.grantedNonReadOnly + permissionWire else current.grantedNonReadOnly - permissionWire
        writeImportedBlob(id, current.copy(grantedNonReadOnly = nextSet))
        reload()
        return true
    }

    // ---- internals ---------------------------------------------------------

    private fun tryParseBuiltIn(rawJson: String, sha: String): SiteProfileEntry.BuiltIn? {
        val parsed = runCatching { profileJson.decodeFromString(SiteProfile.serializer(), rawJson) }
            .getOrElse { Log.w(TAG, "built-in profile json parse failed: ${it.message}"); return null }
        runCatching { parsed.validate() }
            .onFailure { Log.w(TAG, "built-in profile ${parsed.id} invalid: ${it.message}"); return null }
        val perms = parsed.permissions.mapNotNull {
            runCatching { ProfilePermission.parse(it, parsed.id) }.getOrNull()
        }.toSet()
        return SiteProfileEntry.BuiltIn(
            profile = parsed,
            effective = EffectivePermissions(granted = perms, withheld = emptySet()),
            sha256Hex = sha,
        )
    }

    private fun tryParseImported(id: String, blob: StoredImportedBlob): SiteProfileEntry.UserImported? {
        val parsed = runCatching { profileJson.decodeFromString(SiteProfile.serializer(), blob.rawJson) }
            .getOrElse { Log.w(TAG, "imported profile $id json parse failed: ${it.message}"); return null }
        runCatching { parsed.validate() }
            .onFailure { Log.w(TAG, "imported profile $id invalid: ${it.message}"); return null }
        val allPerms = parsed.permissions.mapNotNull {
            runCatching { ProfilePermission.parse(it, parsed.id) }.getOrNull()
        }
        // Read-only auto-granted; sensitive ones only if the user opted in
        // for the exact wire form.
        val granted = allPerms.filter { it.isReadOnly() || it.wire in blob.grantedNonReadOnly }.toSet()
        val withheld = allPerms.toSet() - granted
        return SiteProfileEntry.UserImported(
            profile = parsed,
            effective = EffectivePermissions(granted = granted, withheld = withheld),
            sha256Hex = blob.sha256,
            importedAtMs = blob.importedAtMs,
            grantedNonReadOnlyWireForms = blob.grantedNonReadOnly,
        )
    }

    private fun readImportedBlob(id: String): StoredImportedBlob? {
        val raw = prefs?.getString("imported.$id", null)
        if (raw != null) {
            return runCatching {
                profileJson.decodeFromString(StoredImportedBlob.serializer(), raw)
            }.getOrNull()
        }
        return mem["imported.$id"]
    }

    private fun readAllImportedBlobs(): Map<String, StoredImportedBlob> {
        if (prefs == null) {
            return mem.mapKeys { it.key.removePrefix("imported.") }
        }
        return prefs.all.mapNotNull { (k, v) ->
            if (!k.startsWith("imported.") || v !is String) return@mapNotNull null
            runCatching {
                profileJson.decodeFromString(StoredImportedBlob.serializer(), v)
            }.getOrNull()?.let { k.removePrefix("imported.") to it }
        }.toMap()
    }

    private fun writeImportedBlob(id: String, blob: StoredImportedBlob) {
        val raw = profileJson.encodeToString(StoredImportedBlob.serializer(), blob)
        if (prefs != null) {
            prefs.edit().putString("imported.$id", raw).apply()
        } else {
            mem["imported.$id"] = blob
        }
    }

    private fun removeImportedBlob(id: String): Boolean {
        val key = "imported.$id"
        if (prefs != null) {
            if (prefs.contains(key)) {
                prefs.edit().remove(key).apply()
                return true
            }
            return false
        }
        return mem.remove(key) != null
    }

    private fun tryCreateEncryptedPrefs(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PROFILE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (error: Throwable) {
        Log.e(TAG, "ProfileRegistry encrypted prefs unavailable, falling back to memory", error)
        null
    }

    /** Builds an interface so unit tests can supply a fake asset directory. */
    interface BuiltInLoader {
        /** Pairs of (rawJson, sha256Hex). Order doesn't matter. */
        fun load(): List<Pair<String, String>>
    }

    private class AssetsBuiltInLoader(private val context: Context) : BuiltInLoader {
        override fun load(): List<Pair<String, String>> {
            val assets = context.assets
            val files = runCatching { assets.list(ASSET_DIR) }.getOrNull() ?: emptyArray()
            return files.filter { it.endsWith(".json") }.mapNotNull { name ->
                runCatching {
                    val text = assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
                    text to sha256Hex(text)
                }.getOrNull()
            }
        }
    }

    companion object {
        private const val TAG = "WebMountProfileRegistry"
        private const val PROFILE_FILE = "amberagent_webmount_profiles"
        private const val ASSET_DIR = "webmount/profiles"

        internal fun extractOrigin(url: String): String? {
            val match = Regex("^(https?://[^/]+)").find(url) ?: return null
            return match.groupValues[1]
        }

        internal fun sha256Hex(text: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * One profile in the registry, plus its resolved trust + effective
 * permissions. Sealed so the UI can pattern-match on built-in vs
 * user-imported behaviour.
 */
sealed class SiteProfileEntry {
    abstract val profile: SiteProfile
    abstract val trust: ProfileTrustLevel
    abstract val effective: EffectivePermissions

    /** Hex SHA-256 of the canonical JSON source. */
    abstract val sha256Hex: String

    data class BuiltIn(
        override val profile: SiteProfile,
        override val effective: EffectivePermissions,
        override val sha256Hex: String,
    ) : SiteProfileEntry() {
        override val trust: ProfileTrustLevel = ProfileTrustLevel.BUILTIN
    }

    data class UserImported(
        override val profile: SiteProfile,
        override val effective: EffectivePermissions,
        override val sha256Hex: String,
        val importedAtMs: Long,
        /** Wire-form permissions the user explicitly opted into. */
        val grantedNonReadOnlyWireForms: Set<String>,
    ) : SiteProfileEntry() {
        override val trust: ProfileTrustLevel = ProfileTrustLevel.USER_IMPORTED
    }
}

/** Outcome of [ProfileRegistry.importJson]. */
sealed class ImportResult {
    data class Imported(val profile: SiteProfile, val sha256Hex: String) : ImportResult()
    data class ParseError(val message: String) : ImportResult()
    data class ValidationError(val message: String) : ImportResult()
    data class ConflictWithBuiltIn(val id: String) : ImportResult()
}

/** Persisted shape in encrypted prefs. Versioned for forward compat. */
@Serializable
internal data class StoredImportedBlob(
    val rawJson: String,
    val importedAtMs: Long,
    val grantedNonReadOnly: Set<String>,
    val sha256: String,
    val schemaVersion: Int = 1,
)

/**
 * Shared JSON parser configured with snake_case naming so the Kotlin
 * property names in [SiteProfile] (e.g. `loginCookie`) map to the
 * conventional manifest keys (`login_cookie`).
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val profileJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

package app.amber.feature.runtime

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.core.infra.PreferencesKeys
import app.amber.feature.tools.Capability
import app.amber.feature.tools.CapabilityPolicy
import java.util.UUID

/** Capability-permission history entries kept in the Settings DataStore. */
const val APPROVAL_HISTORY_MAX_ENTRIES = 50

/** A persisted scoped-policy payload is present but cannot be decoded safely. */
class CapabilityPermissionDecodeException(
    val preferenceKey: String,
    detail: String,
    cause: Throwable? = null,
) : IllegalStateException("Invalid $preferenceKey: $detail", cause)

/**
 * P2-01 approval audit entry (parity plan §P2-01 "history"): who, when, which
 * digest was approved, which run/effect it binds to, and the decision result.
 *
 * Sensitive parameters are never stored — only the SHA-256 [argsDigest] of the
 * approved tool call and a reference to the ledger [effectId].
 */
@Serializable
data class ApprovalHistoryEntry(
    val id: String,
    val capabilityId: String?,
    val toolName: String,
    val runId: String?,
    val toolCallId: String,
    val effectId: String?,
    /** SHA-256 digest of the approved args (no plaintext params). */
    val argsDigest: String,
    /** "approved" | "denied". */
    val decision: String,
    /** Who decided: "user" | "continuation" | "approve_all". */
    val source: String,
    val approvedAtMs: Long,
    /** P2-05: number of MCP servers in the approved import batch (import audit). */
    val serverCount: Int? = null,
    /** P2-05: batch risk label ("high" | "normal") of the import (import audit). */
    val riskLabel: String? = null,
    /** P2-05: import apply outcome ("pending" | "applied" | "rejected"). */
    val outcome: String? = null,
    /** P2-06: digest of the content before the CAS write (hash only, never plaintext). */
    val oldDigest: String? = null,
    /** P2-06: digest of the content after the CAS write (hash only, never plaintext). */
    val newDigest: String? = null,
) {
    val capability: Capability? get() = capabilityId?.let(Capability::byId)

    companion object {
        fun approved(
            capability: Capability?,
            toolName: String,
            runId: String?,
            toolCallId: String,
            effectId: String?,
            argsDigest: String,
            source: String,
            now: Long = System.currentTimeMillis(),
            serverCount: Int? = null,
            riskLabel: String? = null,
            outcome: String? = null,
            oldDigest: String? = null,
            newDigest: String? = null,
        ) = ApprovalHistoryEntry(
            id = UUID.randomUUID().toString(),
            capabilityId = capability?.id,
            toolName = toolName,
            runId = runId,
            toolCallId = toolCallId,
            effectId = effectId,
            argsDigest = argsDigest,
            decision = "approved",
            source = source,
            approvedAtMs = now,
            serverCount = serverCount,
            riskLabel = riskLabel,
            outcome = outcome,
            oldDigest = oldDigest,
            newDigest = newDigest,
        )

        fun denied(
            capability: Capability?,
            toolName: String,
            runId: String?,
            toolCallId: String,
            effectId: String?,
            argsDigest: String,
            source: String,
            now: Long = System.currentTimeMillis(),
        ) = ApprovalHistoryEntry(
            id = UUID.randomUUID().toString(),
            capabilityId = capability?.id,
            toolName = toolName,
            runId = runId,
            toolCallId = toolCallId,
            effectId = effectId,
            argsDigest = argsDigest,
            decision = "denied",
            source = source,
            approvedAtMs = now,
        )
    }
}

/**
 * P2-01 persistence for capability policies + recent approval history.
 *
 * Follows the CapabilityFlags pattern: standalone Settings DataStore keys
 * (`capability_policies`, sparse `capability_policy_overrides`,
 * `approval_history`), so the feature stays fully
 * independent of the big Settings aggregate and needs no Room migration.
 * Disabling the feature flag keeps the data (rollback rules, plan §17.2).
 */
class CapabilityPermissionStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val policyFlow: Flow<Map<Capability, CapabilityPolicy>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decodePolicies(prefs[PreferencesKeys.CAPABILITY_POLICIES]) }
        .distinctUntilChanged()

    /** Sparse non-global overrides; absent capabilities inherit by scope. */
    val scopedPolicyFlow: Flow<Map<CapabilityPermissionScopeKey, Map<Capability, CapabilityPolicy>>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decodeScopedPolicies(prefs[PreferencesKeys.CAPABILITY_POLICY_OVERRIDES]) }
        .distinctUntilChanged()

    val approvalHistoryFlow: Flow<List<ApprovalHistoryEntry>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decodeHistory(prefs[PreferencesKeys.APPROVAL_HISTORY]) }
        .distinctUntilChanged()

    /** Current explicit policies; capabilities without an entry are "unset". */
    suspend fun policies(): Map<Capability, CapabilityPolicy> = policyFlow.first()

    /** Current sparse non-global overrides. */
    suspend fun scopedPolicies(): Map<CapabilityPermissionScopeKey, Map<Capability, CapabilityPolicy>> =
        scopedPolicyFlow.first()

    /** Snapshot used for one run; the legacy global map remains unchanged. */
    suspend fun state(): CapabilityPermissionState = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            CapabilityPermissionState(
                policies = decodePolicies(prefs[PreferencesKeys.CAPABILITY_POLICIES]),
                scopedPolicies = decodeScopedPolicies(prefs[PreferencesKeys.CAPABILITY_POLICY_OVERRIDES]),
            )
        }
        .first()

    /** Set or remove (policy == null) the policy for one capability. */
    suspend fun setPolicy(capability: Capability, policy: CapabilityPolicy?) {
        dataStore.edit { prefs ->
            val current = decodePolicies(prefs[PreferencesKeys.CAPABILITY_POLICIES]).toMutableMap()
            if (policy == null) current.remove(capability) else current[capability] = policy
            prefs[PreferencesKeys.CAPABILITY_POLICIES] = encodePolicies(current)
        }
    }

    /**
     * Set or remove one assistant/workspace/conversation override.
     *
     * Session policy is intentionally process/run-local and must be supplied
     * in [CapabilityPermissionState] by the caller; it is never persisted.
     */
    suspend fun setScopedPolicy(
        scope: CapabilityPermissionScope,
        scopeId: String,
        capability: Capability,
        policy: CapabilityPolicy?,
    ) {
        require(scope != CapabilityPermissionScope.GLOBAL) {
            "Use setPolicy for the global capability policy"
        }
        require(scope != CapabilityPermissionScope.SESSION) {
            "Session capability policies are process-local"
        }
        val key = CapabilityPermissionScopeKey(scope, scopeId)
        dataStore.edit { prefs ->
            val current = decodeScopedPolicies(prefs[PreferencesKeys.CAPABILITY_POLICY_OVERRIDES]).toMutableMap()
            val policies = current[key].orEmpty().toMutableMap()
            if (policy == null) policies.remove(capability) else policies[capability] = policy
            if (policies.isEmpty()) current.remove(key) else current[key] = policies
            prefs[PreferencesKeys.CAPABILITY_POLICY_OVERRIDES] = encodeScopedPolicies(current)
        }
    }

    /** Append one approval entry, trimming to the newest [APPROVAL_HISTORY_MAX_ENTRIES]. */
    suspend fun recordApproval(entry: ApprovalHistoryEntry) {
        dataStore.edit { prefs ->
            val current = decodeHistory(prefs[PreferencesKeys.APPROVAL_HISTORY])
            val updated = (current + entry)
                .sortedByDescending { it.approvedAtMs }
                .take(APPROVAL_HISTORY_MAX_ENTRIES)
            prefs[PreferencesKeys.APPROVAL_HISTORY] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ApprovalHistoryEntry.serializer()),
                updated,
            )
        }
    }

    /**
     * The most recent approval decision for (runId, toolCallId), if any.
     * [runId] is null for approvals recorded outside the durable runtime
     * (no ledger effect): null matches entries stored without a runId.
     */
    suspend fun approvalFor(runId: String?, toolCallId: String): ApprovalHistoryEntry? =
        approvalHistoryFlow.first()
            .filter { it.toolCallId == toolCallId && it.runId == runId }
            .maxByOrNull { it.approvedAtMs }

    private fun decodePolicies(raw: String?): Map<Capability, CapabilityPolicy> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { decodePolicyObject(json.parseToJsonElement(raw)) }.getOrDefault(emptyMap())
    }

    private fun encodePolicies(policies: Map<Capability, CapabilityPolicy>): String =
        buildJsonObject {
            policies.forEach { (capability, policy) ->
                put(capability.id, policy.name)
            }
        }.toString()

    private fun decodeScopedPolicies(
        raw: String?,
    ): Map<CapabilityPermissionScopeKey, Map<Capability, CapabilityPolicy>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.parseToJsonElement(raw).jsonObject
                .flatMap { (scopeName, scopeElement) ->
                    val scope = CapabilityPermissionScope.entries.firstOrNull {
                        it.name.equals(scopeName, ignoreCase = true)
                    } ?: error("unknown scope '$scopeName'")
                    require(scope != CapabilityPermissionScope.GLOBAL) {
                        "global policies must use capability_policies"
                    }
                    require(scope != CapabilityPermissionScope.SESSION) {
                        "session policies are process-local and cannot be persisted"
                    }
                    scopeElement.jsonObject.mapNotNull { (scopeId, policyElement) ->
                        require(scopeId.isNotBlank()) { "scoped policy id must not be blank" }
                        val policies = decodeScopedPolicyObject(policyElement)
                        require(policies.isNotEmpty()) {
                            "scoped policy '$scopeName/$scopeId' has no capability entries"
                        }
                        CapabilityPermissionScopeKey(scope, scopeId) to policies
                    }
                }
                .toMap()
        } catch (error: CapabilityPermissionDecodeException) {
            throw error
        } catch (error: Exception) {
            throw CapabilityPermissionDecodeException(
                preferenceKey = PreferencesKeys.CAPABILITY_POLICY_OVERRIDES.name,
                detail = error.message ?: error::class.simpleName.orEmpty(),
                cause = error,
            )
        }
    }

    private fun encodeScopedPolicies(
        scopedPolicies: Map<CapabilityPermissionScopeKey, Map<Capability, CapabilityPolicy>>,
    ): String = buildJsonObject {
        CapabilityPermissionScope.entries
            .asSequence()
            .filter { it != CapabilityPermissionScope.GLOBAL && it != CapabilityPermissionScope.SESSION }
            .forEach { scope ->
                val values = scopedPolicies
                    .filterKeys { it.scope == scope && it.id.isNotBlank() }
                if (values.isNotEmpty()) {
                    put(scope.name.lowercase(), buildJsonObject {
                        values.forEach { (key, policies) ->
                            put(key.id, buildJsonObject {
                                policies.forEach { (capability, policy) -> put(capability.id, policy.name) }
                            })
                        }
                    })
                }
            }
    }.toString()

    private fun decodePolicyObject(element: JsonElement): Map<Capability, CapabilityPolicy> =
        element.jsonObject.mapNotNull { (key, value) ->
            val capability = Capability.byId(key) ?: return@mapNotNull null
            val policy = runCatching { CapabilityPolicy.valueOf(value.jsonPrimitive.content.uppercase()) }.getOrNull()
                ?: return@mapNotNull null
            capability to policy
        }.toMap()

    private fun decodeScopedPolicyObject(element: JsonElement): Map<Capability, CapabilityPolicy> =
        element.jsonObject.map { (key, value) ->
            val capability = Capability.byId(key)
                ?: error("unknown capability '$key'")
            val policy = runCatching { CapabilityPolicy.valueOf(value.jsonPrimitive.content.uppercase()) }
                .getOrElse { error("invalid policy for capability '$key'") }
            capability to policy
        }.toMap()

    private fun decodeHistory(raw: String?): List<ApprovalHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ApprovalHistoryEntry.serializer()),
                raw,
            )
        }.getOrDefault(emptyList())
    }
}

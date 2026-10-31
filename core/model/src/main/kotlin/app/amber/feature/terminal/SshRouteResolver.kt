package app.amber.feature.terminal

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pure-function SSH jump route resolver and validator.
 *
 * Enforces:
 * - Maximum [MAX_JUMP_HOSTS] intermediate jump hosts (up to 4 total hops).
 * - Cycle detection with explicit cycle path reporting (`jump_cycle`).
 * - Missing jump profile detection (`jump_profile_not_found`).
 * - Reverse-dependency validation when saving or deleting profiles.
 */
object SshRouteResolver {
    /** Maximum number of intermediate jump hosts (total hops <= MAX_JUMP_HOSTS + 1 = 4). */
    const val MAX_JUMP_HOSTS = 3
    const val MAX_TOTAL_HOPS = MAX_JUMP_HOSTS + 1

    /**
     * Resolves the ordered hop chain from outermost jump host (`route.first()`)
     * to final target (`route.last()`).
     *
     * Throws [SshRouteException] on missing profiles, cycles, or chains exceeding [MAX_JUMP_HOSTS].
     */
    fun resolveRoute(
        targetId: String,
        profiles: Map<String, SshProfile>,
    ): List<SshProfile> {
        val target = profiles[targetId]
            ?: throw SshRouteException(SshRouteError.ProfileNotFound(targetId))

        val reversed = mutableListOf<SshProfile>()
        val visitedOrder = mutableListOf<String>()
        val visitedSet = LinkedHashSet<String>()

        var current: SshProfile = target
        while (true) {
            if (!visitedSet.add(current.id)) {
                val cycleStart = visitedOrder.indexOf(current.id)
                val cyclePath = if (cycleStart >= 0) {
                    visitedOrder.subList(cycleStart, visitedOrder.size) + current.id
                } else {
                    visitedOrder + current.id
                }
                throw SshRouteException(SshRouteError.JumpCycle(cyclePath))
            }
            visitedOrder.add(current.id)
            reversed.add(current)

            val jumpCount = reversed.size - 1
            if (jumpCount > MAX_JUMP_HOSTS) {
                throw SshRouteException(
                    SshRouteError.JumpChainTooLong(
                        maxJumpHosts = MAX_JUMP_HOSTS,
                        actualJumpHosts = jumpCount,
                        chain = reversed.asReversed().map { it.id },
                    ),
                )
            }

            val nextJumpId = current.jumpProfileId ?: break
            val nextProfile = profiles[nextJumpId]
                ?: throw SshRouteException(
                    SshRouteError.JumpProfileNotFound(
                        profileId = current.id,
                        missingJumpId = nextJumpId,
                    ),
                )
            current = nextProfile
        }

        return reversed.asReversed()
    }

    fun resolveRoute(
        targetId: String,
        profiles: Iterable<SshProfile>,
    ): List<SshProfile> = resolveRoute(targetId, profiles.associateBy { it.id })

    fun resolveRoute(
        profiles: Iterable<SshProfile>,
        targetId: String,
    ): List<SshProfile> = resolveRoute(targetId, profiles.associateBy { it.id })

    /**
     * Validates that every profile in [profiles] resolves to a valid route.
     * Used during profile creation/update so changing a middle profile's `jumpProfileId`
     * cannot push a downstream dependent profile over [MAX_JUMP_HOSTS] or into a cycle.
     */
    fun validateAllRoutes(profiles: Iterable<SshProfile>) {
        val byId = profiles.associateBy { it.id }
        for (profile in byId.values) {
            if (profile.jumpProfileId != null) {
                resolveRoute(profile.id, byId)
            }
        }
    }

    /** Returns IDs of all profiles whose `jumpProfileId` directly references [jumpProfileId]. */
    fun dependentProfiles(
        jumpProfileId: String,
        profiles: Iterable<SshProfile>,
    ): List<String> = profiles
        .filter { it.jumpProfileId == jumpProfileId && it.id != jumpProfileId }
        .map { it.id }
        .sorted()

    fun dependentProfiles(
        profiles: Iterable<SshProfile>,
        jumpProfileId: String,
    ): List<String> = dependentProfiles(jumpProfileId, profiles)

    /** Returns IDs of all profiles whose `keyId` references [keyId]. */
    fun profilesUsingKey(
        keyId: String,
        profiles: Iterable<SshProfile>,
    ): List<String> = profiles
        .filter { it.keyId == keyId }
        .map { it.id }
        .sorted()

    fun profilesUsingKey(
        profiles: Iterable<SshProfile>,
        keyId: String,
    ): List<String> = profilesUsingKey(keyId, profiles)

    /**
     * Computes a deterministic SHA-256 hex digest over every hop's `(id, username, host, port, authMethod, keyId, credentialRevision)`
     * and accepted host key fingerprint so approval tokens bind to the full route.
     */
    fun routeDigest(route: List<SshProfile>): String {
        val canonical = Json.encodeToString(route)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Formats a human-readable route summary such as `bastion -> int-jump -> prod-db`. */
    fun formatRouteNames(route: List<SshProfile>): String =
        route.joinToString(" → ") { it.name }
}

sealed class SshRouteError(
    val code: String,
    val message: String,
) {
    data class ProfileNotFound(
        val profileId: String,
    ) : SshRouteError(
        code = "profile_not_found",
        message = "SSH profile '$profileId' was not found.",
    )

    data class JumpProfileNotFound(
        val profileId: String,
        val missingJumpId: String,
    ) : SshRouteError(
        code = "jump_profile_not_found",
        message = "Jump profile '$missingJumpId' referenced by '$profileId' was not found.",
    )

    data class JumpCycle(
        val cycle: List<String>,
    ) : SshRouteError(
        code = "jump_cycle",
        message = "SSH jump_profile_id forms a cycle: ${cycle.joinToString(" -> ")}.",
    )

    data class JumpChainTooLong(
        val maxJumpHosts: Int,
        val actualJumpHosts: Int,
        val chain: List<String>,
    ) : SshRouteError(
        code = "jump_chain_too_long",
        message = "SSH jump chain exceeds maximum of $maxJumpHosts jump hosts (${chain.joinToString(" -> ")}).",
    )

    data class InUseAsJump(
        val profileId: String,
        val dependentProfileIds: List<String>,
    ) : SshRouteError(
        code = "in_use_as_jump",
        message = "SSH profile '$profileId' is used as jump_profile_id by: ${dependentProfileIds.joinToString(", ")}.",
    )

    data class KeyInUse(
        val keyId: String,
        val usedByProfileIds: List<String>,
    ) : SshRouteError(
        code = "key_in_use",
        message = "SSH key '$keyId' is in use by profiles: ${usedByProfileIds.joinToString(", ")}.",
    )

    data class KeyNotFound(
        val keyId: String,
    ) : SshRouteError(
        code = "key_not_found",
        message = "SSH key '$keyId' was not found.",
    )
}

class SshRouteException(
    val error: SshRouteError,
    cause: Throwable? = null,
) : IllegalStateException(error.message, cause)

package me.rerere.rikkahub.data.agent.webmount.profile

/**
 * Trust tier a [SiteProfile] was loaded under. Drives the L4 hardening
 * rule: user-imported profiles get only read-only permissions by default;
 * signing / signed-fetch must be explicitly enabled per-profile.
 */
enum class ProfileTrustLevel {
    /**
     * Shipped with the APK; integrity is provided by the build process.
     * All declared permissions are usable on load.
     */
    BUILTIN,

    /**
     * Imported by the user from a local JSON file. Defaults to a
     * read-only subset of declared permissions; sensitive permissions
     * (signing, signed-fetch) require an explicit per-profile opt-in.
     */
    USER_IMPORTED,
}

/**
 * Carrier for an effective-permissions decision. The Registry exposes a
 * [SiteProfile] + this resolved set so callers (the agent bridge, the
 * audit UI) can both see the manifest's declared powers and the
 * subset that's currently usable.
 */
data class EffectivePermissions(
    val granted: Set<ProfilePermission>,
    val withheld: Set<ProfilePermission>,
) {
    fun contains(permission: ProfilePermission): Boolean = permission in granted

    fun hasCallPageFn(fnName: String): Boolean =
        granted.any { it is ProfilePermission.CallPageFn && it.fnName == fnName }

    fun hasReadCookie(name: String): Boolean =
        granted.any { it is ProfilePermission.ReadCookie && it.cookieName == name }

    fun isEmpty(): Boolean = granted.isEmpty()
}

/**
 * The set of permission verbs that are "read-only" — safe to grant
 * automatically to user-imported profiles. Anything else needs an
 * explicit per-profile opt-in via the settings UI.
 *
 * Public for the import-audit UI (Phase 2 M2.1 review N-1) to pre-classify
 * permissions as ✓ (auto-granted) vs ⚠ (will be withheld) at import time.
 */
fun ProfilePermission.isReadOnly(): Boolean = when (this) {
    is ProfilePermission.ReadCookie -> true
    ProfilePermission.DetectLogin -> true
    ProfilePermission.DetectRateLimit -> true
    is ProfilePermission.CallPageFn -> false
    is ProfilePermission.SendSigned -> false
}

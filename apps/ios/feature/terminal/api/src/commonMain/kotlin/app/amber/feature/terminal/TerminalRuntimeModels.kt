package app.amber.feature.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TerminalRuntimeKind(val wireName: String) {
    @SerialName("builtin_alpine")
    BUILTIN_ALPINE("builtin_alpine"),

    @SerialName("android_shell")
    ANDROID_SHELL("android_shell"),

    @SerialName("termux_external")
    TERMUX_EXTERNAL("termux_external"),

    @SerialName("remote_ssh")
    REMOTE_SSH("remote_ssh"),

    @SerialName("local_ios_tools")
    LOCAL_IOS_TOOLS("local_ios_tools"),

    @SerialName("remote_mosh")
    REMOTE_MOSH("remote_mosh"),

    @SerialName("ish_experimental")
    ISH_EXPERIMENTAL("ish_experimental");

    companion object {
        fun fromWire(value: String?): TerminalRuntimeKind? =
            entries.firstOrNull { it.wireName == value || it.name.equals(value, ignoreCase = true) }
    }
}

@Serializable
enum class TerminalRuntimeTier(val wireName: String) {
    @SerialName("stable")
    STABLE("stable"),

    @SerialName("experimental")
    EXPERIMENTAL("experimental");
}

@Serializable
enum class TerminalRuntimeLicenseClass(val wireName: String) {
    @SerialName("permissive")
    PERMISSIVE("permissive"),

    @SerialName("platform")
    PLATFORM("platform"),

    @SerialName("gpl_review_required")
    GPL_REVIEW_REQUIRED("gpl_review_required");
}

@Serializable
data class TerminalRuntimeCapability(
    val runtime: TerminalRuntimeKind,
    val tier: TerminalRuntimeTier,
    val supportsPty: Boolean,
    val supportsPackageInstall: Boolean,
    val supportsLongRunningJobs: Boolean,
    val supportsInteractiveLogin: Boolean,
    val supportsFileSync: Boolean,
    val appStoreSafeByDefault: Boolean,
    val supportsExternalCliByDefault: Boolean,
    val licenseClass: TerminalRuntimeLicenseClass,
    val summary: String,
)

object TerminalRuntimeCapabilities {
    val androidRuntimes: List<TerminalRuntimeKind> = listOf(
        TerminalRuntimeKind.BUILTIN_ALPINE,
        TerminalRuntimeKind.ANDROID_SHELL,
        TerminalRuntimeKind.TERMUX_EXTERNAL,
    )

    val iosRuntimes: List<TerminalRuntimeKind> = listOf(
        TerminalRuntimeKind.REMOTE_SSH,
        TerminalRuntimeKind.LOCAL_IOS_TOOLS,
        TerminalRuntimeKind.REMOTE_MOSH,
        TerminalRuntimeKind.ISH_EXPERIMENTAL,
    )

    val androidExternalCliRuntimes: List<TerminalRuntimeKind> = listOf(
        TerminalRuntimeKind.BUILTIN_ALPINE,
        TerminalRuntimeKind.TERMUX_EXTERNAL,
    )

    val iosExternalCliDefaultRuntimes: List<TerminalRuntimeKind> = emptyList()

    val all: List<TerminalRuntimeCapability> = listOf(
        TerminalRuntimeCapability(
            runtime = TerminalRuntimeKind.BUILTIN_ALPINE,
            tier = TerminalRuntimeTier.STABLE,
            supportsPty = true,
            supportsPackageInstall = true,
            supportsLongRunningJobs = true,
            supportsInteractiveLogin = true,
            supportsFileSync = true,
            appStoreSafeByDefault = false,
            supportsExternalCliByDefault = true,
            licenseClass = TerminalRuntimeLicenseClass.PLATFORM,
            summary = "Android built-in Alpine runtime with package installation.",
        ),
        TerminalRuntimeCapability(
            runtime = TerminalRuntimeKind.ANDROID_SHELL,
            tier = TerminalRuntimeTier.STABLE,
            supportsPty = false,
            supportsPackageInstall = false,
            supportsLongRunningJobs = false,
            supportsInteractiveLogin = false,
            supportsFileSync = false,
            appStoreSafeByDefault = false,
            supportsExternalCliByDefault = false,
            licenseClass = TerminalRuntimeLicenseClass.PLATFORM,
            summary = "Android system shell for limited device commands.",
        ),
        TerminalRuntimeCapability(
            runtime = TerminalRuntimeKind.TERMUX_EXTERNAL,
            tier = TerminalRuntimeTier.STABLE,
            supportsPty = false,
            supportsPackageInstall = true,
            supportsLongRunningJobs = true,
            supportsInteractiveLogin = false,
            supportsFileSync = false,
            appStoreSafeByDefault = false,
            supportsExternalCliByDefault = true,
            licenseClass = TerminalRuntimeLicenseClass.PLATFORM,
            summary = "Android external Termux runtime through RUN_COMMAND.",
        ),
        TerminalRuntimeCapability(
            runtime = TerminalRuntimeKind.REMOTE_SSH,
            tier = TerminalRuntimeTier.STABLE,
            supportsPty = false,
            supportsPackageInstall = false,
            supportsLongRunningJobs = true,
            supportsInteractiveLogin = false,
            supportsFileSync = false,
            appStoreSafeByDefault = true,
            supportsExternalCliByDefault = false,
            licenseClass = TerminalRuntimeLicenseClass.PERMISSIVE,
            summary = "iOS recommended remote exec runner. Password auth only in this MVP.",
        ),
        TerminalRuntimeCapability(
            runtime = TerminalRuntimeKind.LOCAL_IOS_TOOLS,
            tier = TerminalRuntimeTier.STABLE,
            supportsPty = false,
            supportsPackageInstall = false,
            supportsLongRunningJobs = false,
            supportsInteractiveLogin = false,
            supportsFileSync = true,
            appStoreSafeByDefault = true,
            supportsExternalCliByDefault = false,
            licenseClass = TerminalRuntimeLicenseClass.PERMISSIVE,
            summary = "iOS lightweight local tools runtime; not a Termux replacement.",
        ),
        TerminalRuntimeCapability(
            runtime = TerminalRuntimeKind.REMOTE_MOSH,
            tier = TerminalRuntimeTier.EXPERIMENTAL,
            supportsPty = true,
            supportsPackageInstall = true,
            supportsLongRunningJobs = true,
            supportsInteractiveLogin = true,
            supportsFileSync = true,
            appStoreSafeByDefault = false,
            supportsExternalCliByDefault = false,
            licenseClass = TerminalRuntimeLicenseClass.GPL_REVIEW_REQUIRED,
            summary = "Experimental iOS remote runtime for resilient mobile sessions.",
        ),
        TerminalRuntimeCapability(
            runtime = TerminalRuntimeKind.ISH_EXPERIMENTAL,
            tier = TerminalRuntimeTier.EXPERIMENTAL,
            supportsPty = true,
            supportsPackageInstall = true,
            supportsLongRunningJobs = true,
            supportsInteractiveLogin = true,
            supportsFileSync = true,
            appStoreSafeByDefault = false,
            supportsExternalCliByDefault = false,
            licenseClass = TerminalRuntimeLicenseClass.GPL_REVIEW_REQUIRED,
            summary = "Experimental embedded iSH Linux runtime gated out of stable builds.",
        ),
    )

    fun forRuntime(runtime: TerminalRuntimeKind): TerminalRuntimeCapability =
        all.first { it.runtime == runtime }

    fun supportedForExternalCliByDefault(runtime: TerminalRuntimeKind): Boolean =
        forRuntime(runtime).supportsExternalCliByDefault

    fun isAndroidRuntime(runtime: TerminalRuntimeKind): Boolean =
        runtime in androidRuntimes

    fun isIosRuntime(runtime: TerminalRuntimeKind): Boolean =
        runtime in iosRuntimes

    fun supportsAndroidExternalCli(runtime: TerminalRuntimeKind): Boolean =
        runtime in androidExternalCliRuntimes

    fun androidDefaultOrFallback(runtime: TerminalRuntimeKind): TerminalRuntimeKind =
        if (isAndroidRuntime(runtime)) runtime else TerminalRuntimeKind.BUILTIN_ALPINE
}

@Serializable
enum class TerminalJobStatus(val wireName: String) {
    @SerialName("queued")
    QUEUED("queued"),

    @SerialName("running")
    RUNNING("running"),

    @SerialName("completed")
    COMPLETED("completed"),

    @SerialName("failed")
    FAILED("failed"),

    @SerialName("cancelled")
    CANCELLED("cancelled"),

    @SerialName("timed_out")
    TIMED_OUT("timed_out");

    val running: Boolean
        get() = this == QUEUED || this == RUNNING
}

data class TerminalJobSnapshot(
    val jobId: String,
    val runtime: TerminalRuntimeKind,
    val status: TerminalJobStatus,
    val exitCode: Int?,
    val running: Boolean,
    val outputTail: String,
    val outputLogPath: String,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val error: String?,
)

data class TermuxRuntimeStatus(
    val installed: Boolean,
    val runCommandPermissionGranted: Boolean,
    val allowExternalAppsConfigured: Boolean?,
    val ready: Boolean,
    val message: String,
)

data class TerminalInstallPlan(
    val packages: List<String>,
    val command: String,
    val verifyCommand: String,
)

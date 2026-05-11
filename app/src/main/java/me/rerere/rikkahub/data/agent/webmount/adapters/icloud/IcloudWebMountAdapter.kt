package me.rerere.rikkahub.data.agent.webmount.adapters.icloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.rerere.rikkahub.data.agent.icloud.ICLOUD_CHINA_LOGIN_URL
import me.rerere.rikkahub.data.agent.icloud.ICLOUD_GLOBAL_LOGIN_URL
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveCapability
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveManager
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveState
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveStatus
import me.rerere.rikkahub.data.agent.webmount.cookie.EndpointSpec
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAdapter
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAuthMethod
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCapability
import me.rerere.rikkahub.data.agent.webmount.core.WebMountProbeResult
import me.rerere.rikkahub.data.agent.webmount.core.WebMountStationState
import me.rerere.rikkahub.data.agent.webmount.core.WebMountStatus

/**
 * iCloud as a WebMount station. M1.1 wraps the existing [ICloudDriveManager]
 * (which keeps owning iCloud-specific state — vault path, write probe flag,
 * etc.) and exposes the relevant slice as a [WebMountAdapter] so the unified
 * panel can render it.
 *
 *  - [externalStateFlow] mirrors `ICloudDriveManager.state`. The unified
 *    panel reads this; the existing iCloud-specific settings page still
 *    reads the manager directly.
 *
 *  - [probe] / [writeProbe] forward to the manager's methods. The legacy
 *    state machine in [ICloudDriveManager] runs unchanged.
 *
 *  - [tools] returns empty. The existing `ICloudDriveTools` keeps surfacing
 *    `icloud_*` tools. M1.6 will consolidate (after smoke-testing).
 */
class IcloudWebMountAdapter(
    private val driveManager: ICloudDriveManager,
    appScope: CoroutineScope,
) : WebMountAdapter {

    override val id: String = "icloud"
    override val displayName: String = "iCloud Drive"
    override val authMethods: Set<WebMountAuthMethod> = setOf(WebMountAuthMethod.COOKIE)
    override val capabilityHints: Set<WebMountCapability> = setOf(
        WebMountCapability.READ_ONLY,
        WebMountCapability.READ_WRITE,
    )
    override val toolNamePrefix: String = "icloud_"

    override val endpoints: List<EndpointSpec> = listOf(
        EndpointSpec(
            id = "global",
            displayName = "iCloud",
            loginUrl = ICLOUD_GLOBAL_LOGIN_URL,
            apiBase = "https://setup.icloud.com/setup/ws/1",
            origin = "https://www.icloud.com",
            cookieUrls = listOf(
                "https://www.icloud.com",
                "https://setup.icloud.com",
                "https://www.icloud.com/iclouddrive",
            ),
            requiredCookieNames = setOf("X-APPLE-WEBAUTH-TOKEN"),
        ),
        EndpointSpec(
            id = "china",
            displayName = "iCloud China",
            loginUrl = ICLOUD_CHINA_LOGIN_URL,
            apiBase = "https://setup.icloud.com.cn/setup/ws/1",
            origin = "https://www.icloud.com.cn",
            cookieUrls = listOf(
                "https://www.icloud.com.cn",
                "https://setup.icloud.com.cn",
                "https://www.icloud.com.cn/iclouddrive",
                "https://www.icloud.cn",
                "https://setup.icloud.cn",
                "https://www.icloud.cn/iclouddrive",
            ),
            requiredCookieNames = setOf("X-APPLE-WEBAUTH-TOKEN"),
        ),
    )

    override val externalStateFlow: StateFlow<WebMountStationState> =
        driveManager.state
            .map { it.toWebMountStationState() }
            .stateIn(
                scope = appScope,
                started = SharingStarted.Eagerly,
                initialValue = driveManager.state.value.toWebMountStationState(),
            )

    override suspend fun probe(): WebMountProbeResult =
        driveManager.probe().toProbeResult(isWriteProbe = false)

    override suspend fun writeProbe(): WebMountProbeResult =
        driveManager.runWriteProbe().toProbeResult(isWriteProbe = true)

    private fun ICloudDriveState.toWebMountStationState(): WebMountStationState =
        WebMountStationState(
            id = this@IcloudWebMountAdapter.id,
            displayName = this@IcloudWebMountAdapter.displayName,
            authMethods = this@IcloudWebMountAdapter.authMethods,
            enabled = enabled,
            status = status.toWebMountStatus(),
            capability = capability.toWebMountCapability(),
            message = message,
            updatedAtMillis = updatedAtMillis,
        )

    private fun ICloudDriveStatus.toWebMountStatus(): WebMountStatus = when (this) {
        ICloudDriveStatus.NOT_CONFIGURED -> WebMountStatus.NOT_CONFIGURED
        ICloudDriveStatus.LOGIN_REQUIRED -> WebMountStatus.LOGIN_REQUIRED
        ICloudDriveStatus.PROBING -> WebMountStatus.PROBING
        ICloudDriveStatus.READ_ONLY -> WebMountStatus.READ_ONLY
        ICloudDriveStatus.READ_WRITE -> WebMountStatus.READ_WRITE
        ICloudDriveStatus.ERROR -> WebMountStatus.ERROR
    }

    private fun ICloudDriveCapability.toWebMountCapability(): WebMountCapability = when (this) {
        ICloudDriveCapability.NONE -> WebMountCapability.NONE
        ICloudDriveCapability.READ_ONLY -> WebMountCapability.READ_ONLY
        ICloudDriveCapability.READ_WRITE -> WebMountCapability.READ_WRITE
    }

    /**
     * Map the manager's post-probe [ICloudDriveState] into a probe result.
     * The manager already mutated its own state — we just classify the
     * outcome so [WebMountManager] can include the right summary in any
     * future logging.
     */
    private fun ICloudDriveState.toProbeResult(isWriteProbe: Boolean): WebMountProbeResult =
        when (status) {
            ICloudDriveStatus.READ_WRITE ->
                WebMountProbeResult.success(WebMountCapability.READ_WRITE, message)
            ICloudDriveStatus.READ_ONLY -> {
                if (isWriteProbe) {
                    WebMountProbeResult.failed(message ?: "Write probe did not promote capability")
                } else {
                    WebMountProbeResult.success(WebMountCapability.READ_ONLY, message)
                }
            }
            ICloudDriveStatus.LOGIN_REQUIRED -> WebMountProbeResult.loginRequired(message)
            ICloudDriveStatus.PROBING -> WebMountProbeResult.failed(
                message ?: "iCloud probe still in progress",
            )
            ICloudDriveStatus.ERROR -> WebMountProbeResult.failed(message ?: "iCloud probe failed")
            ICloudDriveStatus.NOT_CONFIGURED -> WebMountProbeResult.loginRequired(
                message ?: "iCloud is not configured",
            )
        }
}

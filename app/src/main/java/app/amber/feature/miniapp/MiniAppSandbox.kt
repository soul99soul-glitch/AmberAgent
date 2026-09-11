package app.amber.feature.miniapp

import app.amber.core.settings.MiniAppSetting

class MiniAppSandbox(
    private val appId: String,
    declaredPermissions: Set<String>,
    private val setting: MiniAppSetting = MiniAppSetting(),
    private val settingProvider: (() -> MiniAppSetting)? = null,
    private val grantDecision: (String) -> MiniAppGrantDecision? = { null },
) {
    private val declared = declaredPermissions.intersect(MiniAppV3Permissions)
    private val currentSetting: MiniAppSetting
        get() = settingProvider?.invoke() ?: setting

    fun declaredPermissions(): Set<String> = declared

    fun require(permission: MiniAppPermission) {
        val setting = currentSetting
        if (!setting.enabled) {
            throw SecurityException("MiniApp is disabled")
        }
        if (permission.value !in declared) {
            throw SecurityException("Permission denied for $appId: ${permission.value}")
        }
        if (!permission.isGloballyEnabled(setting)) {
            throw SecurityException("Permission disabled: ${permission.value}")
        }
        when (grantDecision(permission.value)) {
            MiniAppGrantDecision.DENY -> throw SecurityException("Permission denied: ${permission.value}")
            MiniAppGrantDecision.ALLOW, null -> Unit
        }
    }

    /** True when the global settings currently allow [permission] (declared state ignored). */
    fun isGloballyEnabled(permission: MiniAppPermission): Boolean =
        currentSetting.let { it.enabled && permission.isGloballyEnabled(it) }

    fun systemCapabilitiesEnabled(): Boolean = currentSetting.let { it.enabled && it.systemCapabilitiesEnabled }

    private fun MiniAppPermission.isGloballyEnabled(setting: MiniAppSetting): Boolean {
        return when (this) {
            MiniAppPermission.Storage,
            MiniAppPermission.Toast,
            MiniAppPermission.Theme -> true
            MiniAppPermission.Network -> setting.networkEnabled
            MiniAppPermission.ExternalImages -> setting.externalImagesEnabled
            MiniAppPermission.Search -> setting.searchEnabled
            MiniAppPermission.ClipboardCopy -> setting.clipboardCopyEnabled
            MiniAppPermission.BoardSummaryUpdate -> setting.boardSummaryUpdateEnabled
            MiniAppPermission.HostContext -> setting.hostContextEnabled
            MiniAppPermission.HostSendToConversation,
            MiniAppPermission.HostCreateArtifact -> setting.hostWriteEnabled
            MiniAppPermission.AiGenerate -> setting.aiEnabled
            MiniAppPermission.SharedStore -> setting.sharedStoreEnabled
            MiniAppPermission.EventBus -> setting.eventBusEnabled
            MiniAppPermission.Launch -> setting.launchEnabled
            MiniAppPermission.Sensor -> setting.sensorEnabled
            MiniAppPermission.Location -> setting.locationEnabled
            MiniAppPermission.ClipboardRead -> setting.clipboardReadEnabled
            MiniAppPermission.Haptics,
            MiniAppPermission.Device,
            MiniAppPermission.Screen,
            MiniAppPermission.Speech,
            MiniAppPermission.Share,
            MiniAppPermission.OpenURL -> setting.systemCapabilitiesEnabled
        }
    }
}

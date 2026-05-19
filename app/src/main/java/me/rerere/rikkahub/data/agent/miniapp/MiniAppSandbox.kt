package me.rerere.rikkahub.data.agent.miniapp

import me.rerere.rikkahub.data.datastore.MiniAppSetting

class MiniAppSandbox(
    private val appId: String,
    declaredPermissions: Set<String>,
    private val setting: MiniAppSetting = MiniAppSetting(),
    private val grantDecision: (String) -> MiniAppGrantDecision? = { null },
) {
    private val declared = declaredPermissions.intersect(MiniAppV2Permissions)

    fun require(permission: MiniAppPermission) {
        if (!setting.enabled) {
            throw SecurityException("MiniApp is disabled")
        }
        if (permission.value !in declared) {
            throw SecurityException("Permission denied for $appId: ${permission.value}")
        }
        if (!permission.isGloballyEnabled()) {
            throw SecurityException("Permission disabled: ${permission.value}")
        }
        when (grantDecision(permission.value)) {
            MiniAppGrantDecision.DENY -> throw SecurityException("Permission denied: ${permission.value}")
            MiniAppGrantDecision.ALLOW, null -> Unit
        }
    }

    private fun MiniAppPermission.isGloballyEnabled(): Boolean {
        return when (this) {
            MiniAppPermission.Storage,
            MiniAppPermission.Toast,
            MiniAppPermission.Theme -> true
            MiniAppPermission.Network -> setting.networkEnabled
            MiniAppPermission.ExternalImages -> setting.externalImagesEnabled
            MiniAppPermission.Search -> setting.searchEnabled
            MiniAppPermission.ClipboardCopy -> setting.clipboardCopyEnabled
            MiniAppPermission.BoardSummaryUpdate -> setting.boardSummaryUpdateEnabled
        }
    }
}

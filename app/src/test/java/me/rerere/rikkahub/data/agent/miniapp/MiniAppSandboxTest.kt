package me.rerere.rikkahub.data.agent.miniapp

import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.datastore.MiniAppSetting

class MiniAppSandboxTest {
    @Test
    fun requiresDeclaredPermission() {
        val sandbox = MiniAppSandbox("app-1", setOf("storage"))

        sandbox.require(MiniAppPermission.Storage)
        assertTrue(runCatching { sandbox.require(MiniAppPermission.Theme) }.isFailure)
    }

    @Test
    fun respectsGlobalCapabilitySwitches() {
        val sandbox = MiniAppSandbox(
            appId = "app-1",
            declaredPermissions = setOf("network"),
            setting = MiniAppSetting(networkEnabled = false),
        )

        assertTrue(runCatching { sandbox.require(MiniAppPermission.Network) }.isFailure)
    }

    @Test
    fun respectsGrantDeny() {
        val sandbox = MiniAppSandbox(
            appId = "app-1",
            declaredPermissions = setOf("search"),
            grantDecision = { MiniAppGrantDecision.DENY },
        )

        assertTrue(runCatching { sandbox.require(MiniAppPermission.Search) }.isFailure)
    }
}

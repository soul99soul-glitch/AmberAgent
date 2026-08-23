package app.amber.feature.miniapp

import org.junit.Assert.assertTrue
import org.junit.Test
import app.amber.core.settings.MiniAppSetting

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

    @Test
    fun v3SensitiveCapabilitiesDefaultClosed() {
        val sensitive = listOf(
            MiniAppPermission.HostContext,
            MiniAppPermission.HostSendToConversation,
            MiniAppPermission.HostCreateArtifact,
            MiniAppPermission.Location,
            MiniAppPermission.ClipboardRead,
        )
        sensitive.forEach { permission ->
            val sandbox = MiniAppSandbox(
                appId = "app-1",
                declaredPermissions = setOf(permission.value),
            )
            assertTrue("Expected ${permission.value} to be disabled by default", runCatching {
                sandbox.require(permission)
            }.isFailure)
        }
    }

    @Test
    fun v3AppCapabilitiesDefaultOpen() {
        val capabilities = listOf(
            MiniAppPermission.AiGenerate,
            MiniAppPermission.Sensor,
            MiniAppPermission.SharedStore,
            MiniAppPermission.EventBus,
            MiniAppPermission.Launch,
        )
        capabilities.forEach { permission ->
            val sandbox = MiniAppSandbox(
                appId = "app-1",
                declaredPermissions = setOf(permission.value),
            )
            sandbox.require(permission)
        }
    }

    @Test
    fun v3PlatformCapabilitiesCanBeEnabledExplicitly() {
        val sandbox = MiniAppSandbox(
            appId = "app-1",
            declaredPermissions = setOf("sharedStore", "eventBus", "launch"),
            setting = MiniAppSetting(
                sharedStoreEnabled = true,
                eventBusEnabled = true,
                launchEnabled = true,
            ),
        )

        sandbox.require(MiniAppPermission.SharedStore)
        sandbox.require(MiniAppPermission.EventBus)
        sandbox.require(MiniAppPermission.Launch)
    }
}

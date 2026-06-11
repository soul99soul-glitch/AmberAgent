package app.amber.agent.data.agent.tools

import android.Manifest
import app.amber.feature.system.AgentPermissionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPermissionRegistryTest {
    @Test
    fun `sms read requires read sms permission`() {
        val capability = AgentPermissionRegistry.capabilities.single { it.id == "sms_read" }

        assertEquals(listOf(Manifest.permission.READ_SMS), capability.runtimePermissions.map { it.name })
    }

    @Test
    fun `calendar create requires read and write calendar permissions`() {
        val capability = AgentPermissionRegistry.capabilities.single { it.id == "calendar_write" }

        assertTrue(capability.runtimePermissions.map { it.name }.containsAll(
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        ))
    }
}

package app.amber.core.memory.dream

import app.amber.core.memory.model.MemoryWorkerSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDreamSchedulerTest {

    @Test
    fun `charging preference replaces idle gate`() {
        val constraints = memoryDreamWorkConstraints(
            MemoryWorkerSetting(runOnlyOnCharging = true, runOnlyOnIdle = true)
        )
        assertTrue(constraints.requiresCharging)
        assertFalse(constraints.requiresDeviceIdle)
    }

    @Test
    fun `idle gate applies only when charging preference is off`() {
        val constraints = memoryDreamWorkConstraints(
            MemoryWorkerSetting(runOnlyOnCharging = false, runOnlyOnIdle = true)
        )
        assertFalse(constraints.requiresCharging)
        assertTrue(constraints.requiresDeviceIdle)
    }

    @Test
    fun `both gates off keeps run unconstrained`() {
        val constraints = memoryDreamWorkConstraints(
            MemoryWorkerSetting(runOnlyOnCharging = false, runOnlyOnIdle = false)
        )
        assertFalse(constraints.requiresCharging)
        assertFalse(constraints.requiresDeviceIdle)
    }

    @Test
    fun `charging alone still gates`() {
        val constraints = memoryDreamWorkConstraints(
            MemoryWorkerSetting(runOnlyOnCharging = true, runOnlyOnIdle = false)
        )
        assertTrue(constraints.requiresCharging)
        assertFalse(constraints.requiresDeviceIdle)
    }
}

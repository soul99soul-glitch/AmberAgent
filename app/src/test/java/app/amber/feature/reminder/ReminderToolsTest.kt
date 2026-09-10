package app.amber.feature.reminder

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderToolsTest {
    @Test
    fun `crud tools use durable store and reschedule changes`() = runTest {
        val store = ReminderStore(Files.createTempDirectory("reminder-tools").toFile())
        val operations = RecordingOperations()
        val scheduler = ReminderScheduler(store, operations, exactAlarmAllowed = { true })
        val tools = ReminderTools(store, scheduler).getTools().associateBy { it.name }

        assertEquals(setOf("reminder_list", "reminder_create", "reminder_update", "reminder_delete"), tools.keys)
        assertTrue(tools.getValue("reminder_create").needsApproval)
        assertFalse(tools.getValue("reminder_create").allowsAutoApproval)

        tools.getValue("reminder_create").execute(buildJsonObject {
            put("title", "喝水")
            put("message", "现在喝水")
            put("trigger_at_epoch_ms", 123L)
            put("recurrence", "daily")
        })
        val created = store.list().single()
        assertEquals("喝水", created.title)
        assertEquals(ReminderRecurrence.DAILY, created.recurrence)
        assertEquals(1, operations.exact.size)

        tools.getValue("reminder_update").execute(buildJsonObject {
            put("reminder_id", created.id)
            put("message", "更新后的提醒")
            put("trigger_at_epoch_ms", 456L)
        })
        assertEquals("更新后的提醒", store.read(created.id)?.message)
        assertEquals(456L, store.read(created.id)?.nextFireAtEpochMs)
        assertEquals(2, operations.exact.size)

        tools.getValue("reminder_delete").execute(buildJsonObject {
            put("reminder_id", created.id)
        })
        assertNotNull(operations.cancelled.singleOrNull { it == created.id })
        assertTrue(store.read(created.id) == null)
    }

    private class RecordingOperations : ReminderAlarmOperations {
        val exact = mutableListOf<ReminderAlarmRequest>()
        val approximate = mutableListOf<ReminderAlarmRequest>()
        val cancelled = mutableListOf<String>()

        override fun scheduleExact(request: ReminderAlarmRequest) { exact += request }
        override fun scheduleApproximate(request: ReminderAlarmRequest) { approximate += request }
        override fun cancel(reminderId: String) { cancelled += reminderId }
    }
}

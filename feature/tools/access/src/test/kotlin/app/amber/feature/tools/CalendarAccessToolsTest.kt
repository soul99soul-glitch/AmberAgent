package app.amber.feature.tools

import android.app.Application
import android.content.ContextWrapper
import android.provider.CalendarContract
import app.amber.feature.system.AgentPermissionBroker
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalendarAccessToolsTest {
    private val existing = CalendarEventSnapshot(
        eventId = 42L,
        title = "原标题",
        beginEpochMs = 1_000L,
        endEpochMs = 2_000L,
    )

    @Test
    fun recurringEventWithoutDtendAllowsNonTimeUpdates() {
        val recurring = existing.copy(endEpochMs = 0L)
        val values = buildCalendarUpdateValues(
            Json.parseToJsonElement("""{"event_id":42,"title":"改标题","location":"会议室"}"""),
            recurring,
        )

        assertEquals(setOf(CalendarContract.Events.TITLE, CalendarContract.Events.EVENT_LOCATION), values.keySet())
        assertFalse(values.containsKey(CalendarContract.Events.DTSTART))
        assertFalse(values.containsKey(CalendarContract.Events.DTEND))
    }

    @Test
    fun recurringEventTimeChangesDoNotWriteInvalidDtend() {
        val recurring = existing.copy(endEpochMs = 0L)
        val error = runCatching {
            buildCalendarUpdateValues(
                Json.parseToJsonElement("""{"event_id":42,"end_epoch_ms":3000}"""), recurring,
            )
        }.exceptionOrNull()

        assertEquals("Time changes to recurring calendar events are not supported", error?.message)
    }

    @Test
    fun missingEventSnapshotProducesVisibleNotFound() {
        // update/delete 的执行链在查不到事件时走这一分支（生产 requireCalendarEvent
        // → requireCalendarEventSnapshot），错误信息包含精确 event_id。
        val error = runCatching { requireCalendarEventSnapshot(99L, null) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("Event not found: 99", error!!.message)
    }

    @Test
    fun existingSnapshotPassesThroughUnchanged() {
        assertEquals(existing, requireCalendarEventSnapshot(42L, existing))
    }

    @Test
    fun updateValues_onlyContainsFieldsProvidedByCaller() {
        val input = Json.parseToJsonElement(
            """{"event_id":42,"title":"新标题","start_epoch_ms":1500,"location":"会议室"}"""
        )

        val values = buildCalendarUpdateValues(input, existing)

        assertEquals(
            setOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
            ),
            values.keySet().toSet(),
        )
        assertEquals("新标题", values.getAsString(CalendarContract.Events.TITLE))
        assertEquals(1_500L, values.getAsLong(CalendarContract.Events.DTSTART))
        assertFalse(values.containsKey(CalendarContract.Events.DESCRIPTION))
        assertFalse(values.containsKey(CalendarContract.Events.DTEND))
    }

    @Test
    fun updateValues_rejectsInvalidTimeRangeUsingUnchangedSide() {
        val input = Json.parseToJsonElement("""{"event_id":42,"end_epoch_ms":500}""")

        val error = runCatching { buildCalendarUpdateValues(input, existing) }.exceptionOrNull()

        assertEquals("end_time must be after start_time", error?.message)
    }

    @Test
    fun updateValues_rejectsEmptyUpdate() {
        val input = Json.parseToJsonElement("""{"event_id":42}""")

        val error = runCatching { buildCalendarUpdateValues(input, existing) }.exceptionOrNull()

        assertEquals("At least one event field is required", error?.message)
    }

    @Test
    fun updateAndDeleteFactoriesRequireApprovalAndDisableAutoApproval() {
        // Factory-level flags are part of the public Tool contract; use null-safe
        // deps only because the assertions do not execute the tools.
        val context = ContextWrapper(null)
        val deps = SystemAccessDeps(
            activityStore = app.amber.feature.runtime.AgentToolActivityStore(),
            permissionBroker = AgentPermissionBroker(context, isDebugBuild = true),
        )
        val update = createCalendarUpdateTool(context, deps)
        val delete = createCalendarDeleteTool(context, deps)

        assertTrue(update.needsApproval)
        assertFalse(update.allowsAutoApproval)
        assertTrue(delete.needsApproval)
        assertFalse(delete.allowsAutoApproval)
    }
}

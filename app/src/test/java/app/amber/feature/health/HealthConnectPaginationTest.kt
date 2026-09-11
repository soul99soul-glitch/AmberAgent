package app.amber.feature.health

import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.StepsRecord
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthConnectPaginationTest {
    @Test
    fun `production pager sends ascending only on first request and stops at minus one`() = runTest {
        val range = TimeInstantRangeFilter.Builder()
            .setStartTime(Instant.ofEpochMilli(1L))
            .setEndTime(Instant.ofEpochMilli(2L))
            .build()
        val requests = mutableListOf<ReadRecordsRequestUsingFilters<StepsRecord>>()

        val records = readHealthConnectPages(StepsRecord::class.java, range) { request ->
            requests += request
            HealthConnectRecordPage(
                records = emptyList(),
                nextPageToken = if (requests.size == 1) 41L else -1L,
            )
        }

        assertTrue(records.isEmpty())
        assertEquals(2, requests.size)
        assertEquals(-1L, requests[0].pageToken)
        assertTrue(requests[0].isAscending)
        assertEquals(41L, requests[1].pageToken)
        assertEquals(1000, requests[0].pageSize)
        assertEquals(1000, requests[1].pageSize)
    }
}

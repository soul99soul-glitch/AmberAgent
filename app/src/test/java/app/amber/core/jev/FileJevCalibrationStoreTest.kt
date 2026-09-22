package app.amber.core.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 校准记录 JSONL 落盘：往返、有界重写、清除、坏行跳过。 */
class FileJevCalibrationStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(id: Int) = JevCalibrationRecord(
        timestamp = id * 1_000L,
        purpose = JevPurpose.TOOL_DISCOVERY,
        mode = JevMode.SHADOW,
        model = "jev-test",
        latencyMs = 12L,
        threshold = 0.5,
        scores = mapOf("t$id" to 0.42),
        incumbentTop1 = "t0",
        jevTop1 = null,
    )

    private fun store(maxRecords: Int = FileJevCalibrationStore.DEFAULT_MAX_RECORDS) =
        FileJevCalibrationStore(File(tmp.root, "jev/calibration.jsonl"), maxRecords = maxRecords)

    @Test
    fun roundTripPreservesRecords() {
        val store = store()
        store.append(record(1))
        store.append(record(2))
        val records = store.readAll()
        assertEquals(2, records.size)
        assertEquals(1_000L, records[0].timestamp)
        assertEquals(2_000L, records[1].timestamp)
        assertEquals(mapOf("t2" to 0.42), records[1].scores)
    }

    @Test
    fun rotationKeepsNewestHalf() {
        val store = store(maxRecords = 10)
        repeat(11) { store.append(record(it)) }
        val records = store.readAll()
        assertEquals(5, records.size)
        assertEquals(listOf(6_000L, 7_000L, 8_000L, 9_000L, 10_000L), records.map { it.timestamp })
    }

    @Test
    fun clearEmptiesLog() {
        val store = store()
        store.append(record(1))
        store.clear()
        assertTrue(store.readAll().isEmpty())
        store.append(record(2))
        assertEquals(listOf(2_000L), store.readAll().map { it.timestamp })
    }

    @Test
    fun malformedLinesAreSkipped() {
        val store = store()
        store.append(record(1))
        val file = File(tmp.root, "jev/calibration.jsonl")
        file.appendText("{not json}\n")
        store.append(record(2))

        val reopened = FileJevCalibrationStore(file)
        val records = reopened.readAll()
        assertEquals(2, records.size)
        assertNull(records[0].jevTop1)
        assertEquals(2_000L, records[1].timestamp)
    }
}

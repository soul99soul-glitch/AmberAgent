package app.amber.feature.board.hotlist.deepread

import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class DeepReadSchedulerTest {

    @Test
    fun `active work states recover topic ids from work name tags and omit finished work`() {
        val states = activeDeepReadWorkStates(
            listOf(
                workInfo("running", WorkInfo.State.RUNNING),
                workInfo("queued", WorkInfo.State.ENQUEUED),
                workInfo("finished", WorkInfo.State.SUCCEEDED),
                WorkInfo(
                    UUID.randomUUID(),
                    WorkInfo.State.RUNNING,
                    setOf(DeepReadScheduler.TAG, "unrelated-tag"),
                ),
            ),
        )

        assertEquals(
            mapOf(
                "running" to WorkInfo.State.RUNNING,
                "queued" to WorkInfo.State.ENQUEUED,
            ),
            states,
        )
    }

    private fun workInfo(topicId: String, state: WorkInfo.State): WorkInfo = WorkInfo(
        UUID.randomUUID(),
        state,
        setOf(DeepReadScheduler.TAG, DeepReadScheduler.workName(topicId)),
    )
}

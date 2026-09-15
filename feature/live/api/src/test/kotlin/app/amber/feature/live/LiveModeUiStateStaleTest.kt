package app.amber.feature.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D2（蓝图 v3 §4-D2）：卡片失效是派生态——签名漂移即 stale。 */
class LiveModeUiStateStaleTest {
    private val card = LiveModeCard(watching = "结论")

    @Test
    fun cardStaleReflectsSignatureDrift() {
        val current = LiveModeUiState(card = card, cardSignature = "a", lastSnapshotHash = "a")
        assertFalse(current.cardStale)
        assertTrue(current.copy(lastSnapshotHash = "b").cardStale)
    }

    @Test
    fun cardStaleRequiresCardAndSignature() {
        assertFalse(LiveModeUiState().cardStale)
        assertFalse(LiveModeUiState(card = card, lastSnapshotHash = "b").cardStale)
        assertFalse(
            LiveModeUiState(card = card, cardSignature = "a", lastSnapshotHash = null).cardStale,
        )
    }
}

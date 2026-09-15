package app.amber.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** D10（蓝图 v3 §7.2 P0-8）：归一化只忽略时钟噪声，金额/数量/正文变化必须产生新签名。 */
class LiveUiTreeProcessorNormalizeTest {
    @Test
    fun stableHashKeepsAmountChanges() {
        val first = LiveUiTreeProcessor.stableHash("com.example", "订单", "- text=14:20 价格 128")
        val clockOnly = LiveUiTreeProcessor.stableHash("com.example", "订单", "- text=14:21 价格 128")
        val priceChanged = LiveUiTreeProcessor.stableHash("com.example", "订单", "- text=14:20 价格 256")

        assertEquals(first, clockOnly)
        assertNotEquals(first, priceChanged)
    }
}

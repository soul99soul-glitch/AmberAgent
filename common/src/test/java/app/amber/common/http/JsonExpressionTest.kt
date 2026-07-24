package app.amber.common.http

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class JsonExpressionTest {

    @Test
    fun `numeric fields keep their original precision during evaluation`() {
        val root = buildJsonObject { put("value", 1.2345) }

        assertEquals("1.2345", evaluateJsonExpr("value + 0", root))
    }

    @Test
    fun `numeric fields evaluate independently of default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val root = buildJsonObject { put("value", 1.25) }

            assertEquals("1.25", evaluateJsonExpr("value", root))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `x and X remain valid field names while spaced x remains multiplication`() {
        val root = buildJsonObject {
            put("x", 4)
            put("X", 5)
        }

        assertEquals("9", evaluateJsonExpr("x + X", root))
        assertEquals("12", evaluateJsonExpr("x x 3", root))
    }
}

package app.amber.feature.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolsTest {
    @Test
    fun fileReadJsonDefaultsToBoundedOutput() {
        val content = "a".repeat(FILE_READ_DEFAULT_MAX_CHARS + 10)

        val payload = buildFileReadJson("notes/big.md", content, requestedMaxChars = null)
        val obj = Json.parseToJsonElement(payload.toString()).jsonObject

        assertEquals(FILE_READ_DEFAULT_MAX_CHARS, obj["content"]!!.jsonPrimitive.content.length)
        assertEquals(content.length, obj["total_size_chars"]!!.jsonPrimitive.int)
        assertTrue(obj["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(FILE_READ_DEFAULT_MAX_CHARS, obj["max_chars"]!!.jsonPrimitive.int)
    }

    @Test
    fun fileReadMaxCharsIsHardCapped() {
        assertEquals(FILE_READ_HARD_MAX_CHARS, normalizeFileReadMaxChars(Int.MAX_VALUE))
    }
}

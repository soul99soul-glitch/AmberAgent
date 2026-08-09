package app.amber.ai.core

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `wait` 模型可见的 schema、审批与终止契约。 */
class WaitToolDeclarationTest {

    @Test
    fun waitDeclarationKeepsSchemaAndLifecycleContract() {
        val tool = createWaitToolDeclaration()
        assertEquals("wait", tool.name)
        assertFalse(tool.needsApproval)
        assertTrue(tool.allowsAutoApproval)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("cell_id"), params.required)

        val cellId = params.properties["cell_id"]!!.jsonObject
        assertEquals("string", cellId["type"]?.jsonPrimitive?.contentOrNull)

        val timeout = params.properties["timeout_ms"]!!.jsonObject
        assertEquals("integer", timeout["type"]?.jsonPrimitive?.contentOrNull)
        val timeoutDescription = timeout["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("[1000, 60000]" in timeoutDescription)
        assertTrue("defaults to 10000" in timeoutDescription)

        val terminate = params.properties["terminate"]!!.jsonObject
        assertEquals("boolean", terminate["type"]?.jsonPrimitive?.contentOrNull)

        val description = tool.description
        listOf(
            "`cell_id` is returned by exec", "Blocks until the cell completes",
            "terminate=true abandons the cell",
        ).forEach { semantic ->
            assertTrue(semantic in description, "wait 描述缺少模型可见语义: $semantic")
        }
    }
}

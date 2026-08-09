package app.amber.ai.core

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `exec` 模型可见的 schema、审批与沙盒边界契约。 */
class ExecToolDeclarationTest {

    @Test
    fun execDeclarationKeepsSchemaAndSandboxContract() {
        val tool = createExecToolDeclaration()
        assertEquals("exec", tool.name)
        assertFalse(tool.needsApproval)
        assertTrue(tool.allowsAutoApproval)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("code"), params.required)

        val code = params.properties["code"]!!.jsonObject
        assertEquals("string", code["type"]?.jsonPrimitive?.contentOrNull)

        val timeout = params.properties["timeout_ms"]!!.jsonObject
        assertEquals("integer", timeout["type"]?.jsonPrimitive?.contentOrNull)
        val timeoutDescription = timeout["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("[1000, 30000]" in timeoutDescription)
        assertTrue("defaults to 10000" in timeoutDescription)

        val maxOutput = params.properties["max_output_chars"]!!.jsonObject
        assertEquals("integer", maxOutput["type"]?.jsonPrimitive?.contentOrNull)
        val maxOutputDescription = maxOutput["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("[1, 100000]" in maxOutputDescription)
        assertTrue("defaults to 10000" in maxOutputDescription)

        val description = tool.description
        listOf(
            "No DOM", "no Node", "no fs", "no network", "no imports", "console.log",
            "last expression's value", "synchronous", "no await/Promise needed",
            "nested calls inherit each tool's own approval policy", "ALL_TOOLS",
            "filter it to discover tools instead of guessing names", "NOT for generating SVG/widgets/HTML",
        ).forEach { semantic ->
            assertTrue(semantic in description, "exec 描述缺少模型可见语义: $semantic")
        }
    }
}

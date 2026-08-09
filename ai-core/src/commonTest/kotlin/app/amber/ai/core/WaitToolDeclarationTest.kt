package app.amber.ai.core

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
/**
 * P3-c: `wait` 工具声明契约（KMP 声明，iOS 执行在 Swift）。
 * 钉死参数形状（cell_id 必填、timeout_ms 默认 10000 且 clamp [1000, 60000]、
 * terminate 布尔）、阻塞语义描述文案、审批标志与 exec 同源
 * （needsApproval=false、allowsAutoApproval=true——容器本身不提示审批，
 * 开关是 execJavaScriptEnabled 门）、iosToolDeclaration 目录可发现性。
 */
class WaitToolDeclarationTest {

    @Test
    fun waitDeclarationPinsParametersAndApprovalFlags() {
        val tool = createWaitToolDeclaration()
        assertEquals("wait", tool.name)
        // 与 exec 同容器语义：不需要额外审批卡，逐助手/全局开关是门。
        assertFalse(tool.needsApproval, "与 exec 同源：needsApproval=false")
        assertTrue(tool.allowsAutoApproval, "与 exec 同源：allowsAutoApproval=true")

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("cell_id"), params.required)

        val cellId = params.properties["cell_id"]!!.jsonObject
        assertEquals("string", cellId["type"]?.jsonPrimitive?.contentOrNull)

        val timeout = params.properties["timeout_ms"]!!.jsonObject
        assertEquals("integer", timeout["type"]?.jsonPrimitive?.contentOrNull)
        val timeoutDescription = timeout["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("1000" in timeoutDescription && "60000" in timeoutDescription, "必须写明 wait timeout clamp 区间 [1000, 60000]")
        assertTrue("10000" in timeoutDescription, "必须写明默认 10000ms")

        val terminate = params.properties["terminate"]!!.jsonObject
        assertEquals("boolean", terminate["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun waitDescriptionCarriesBlockingSemantics() {
        val description = createWaitToolDeclaration().description
        // 阻塞语义必须明示（模型面英文文案统一）。
        assertTrue("Blocks until the cell completes" in description, "描述必须含阻塞语义文案")
        // L4: 模型面描述不得残留中文残句。
        assertFalse("完成前" in description, "模型面描述不得含中文残句")
        // cell_id 来源必须可理解（exec 超时 yield 返回）。
        assertTrue("cell_id" in description)
    }

    @Test
    fun waitResolvesThroughIosToolDeclarationCatalog() {
        val declarations = iosToolDeclarations(listOf("wait"))
        assertEquals(listOf("wait"), declarations.map { it.name })
        assertNotNull(iosToolDeclaration("wait"))
    }
}

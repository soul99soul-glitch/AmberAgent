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
 * P3-a: `exec` 纯求值工具声明契约（KMP 声明，iOS 执行在 Swift）。
 * 钉死参数形状（code 必填、timeout_ms 默认/clamp 文案、max_output_chars）、
 * 审批标志对齐 Android `eval_javascript`（Tool 默认：needsApproval=false、
 * allowsAutoApproval=true）、描述文案（无 DOM/Node/fs/网络/导入）与
 * iosToolDeclaration 目录可发现性（非常驻：进 deferred 池由 tool_search 命中）。
 */
class ExecToolDeclarationTest {

    @Test
    fun execDeclarationPinsParametersAndApprovalFlags() {
        val tool = createExecToolDeclaration()
        assertEquals("exec", tool.name)
        // Android eval_javascript 不设审批门（Tool 默认）——逐助手/全局开关是门。
        assertFalse(tool.needsApproval, "审批标志对齐 Android eval_javascript：needsApproval=false")
        assertTrue(tool.allowsAutoApproval, "审批标志对齐 Android eval_javascript：allowsAutoApproval=true")

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("code"), params.required)

        val code = params.properties["code"]!!.jsonObject
        assertEquals("string", code["type"]?.jsonPrimitive?.contentOrNull)

        val timeout = params.properties["timeout_ms"]!!.jsonObject
        assertEquals("integer", timeout["type"]?.jsonPrimitive?.contentOrNull)
        val timeoutDescription = timeout["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("1000" in timeoutDescription && "30000" in timeoutDescription, "必须写明 timeout clamp 区间")
        assertTrue("10000" in timeoutDescription, "必须写明默认 10000ms")

        val maxOutput = params.properties["max_output_chars"]!!.jsonObject
        assertEquals("integer", maxOutput["type"]?.jsonPrimitive?.contentOrNull)
        assertTrue("10000" in (maxOutput["description"]?.jsonPrimitive?.contentOrNull.orEmpty()), "必须写明默认 10000 字符")
        // P3-d 资源上限：输出必须写明硬上限 clamp（[1, 100000]），模型传超大值不得
        // 绕过截断（payload 会进入工具输出与账本，必须封顶）。
        assertTrue("100000" in (maxOutput["description"]?.jsonPrimitive?.contentOrNull.orEmpty()), "必须写明输出硬上限 100000 字符")
    }

    @Test
    fun execDescriptionCarriesSandboxBoundarySemantics() {
        val description = createExecToolDeclaration().description
        // 对齐规格文案：无 DOM/Node/fs/网络/导入；console 是输出通道；最后表达式是结果；
        // 明示不用于 SVG/widget/HTML。
        assertTrue("No DOM" in description)
        assertTrue("no fs" in description)
        assertTrue("no network" in description)
        assertTrue("no imports" in description)
        assertTrue("console.log" in description)
        assertTrue("last expression's value" in description)
        assertTrue("SVG" in description && "HTML" in description)
        // P3-b 嵌套 tools 桥契约：描述必须写明同步调用语义（JSC 无事件循环泵，
        // 不需要 await/Promise；v1 不支持并发 Promise.all）与嵌套调用继承各工具
        // 自身审批（容器不重复提示）。
        assertTrue("tools" in description)
        assertTrue("synchronous" in description)
        assertTrue("no await/Promise needed" in description)
        assertTrue("approval" in description)
        // P3-d ALL_TOOLS 发现契约：描述必须写明全局 ALL_TOOLS 元数据（每个可调用
        // 工具的 name/description）与过滤发现引导（不要猜工具名）。
        assertTrue("ALL_TOOLS" in description, "必须写明全局 ALL_TOOLS 发现元数据")
        assertTrue(
            "filter it to discover tools instead of guessing names" in description,
            "必须写明按 description 过滤发现的引导文案",
        )
    }

    @Test
    fun execResolvesThroughIosToolDeclarationCatalog() {
        val declarations = iosToolDeclarations(listOf("exec"))
        assertEquals(listOf("exec"), declarations.map { it.name })
        assertNotNull(iosToolDeclaration("exec"))
    }
}

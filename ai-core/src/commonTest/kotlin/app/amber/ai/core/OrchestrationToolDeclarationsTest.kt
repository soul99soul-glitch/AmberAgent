package app.amber.ai.core

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P1-c: 线程编排三工具声明契约（纯函数，iOS/Android 共用）。
 * 钉死参数形状、required、needsApproval=false、canonical path 语义文案与
 * iosToolDeclaration 目录可发现性（非常驻：进 deferred 池由 tool_search 命中）。
 */
class OrchestrationToolDeclarationsTest {

    // MARK: - spawn_agent

    @Test
    fun spawnAgentDeclarationPinsParametersAndFlags() {
        val tool = createSpawnAgentToolDeclaration()
        assertEquals("spawn_agent", tool.name)
        assertFalse(tool.needsApproval, "编排工具不设审批门（harness 拥有时机）")

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("task_name", "message"), params.required)

        val taskName = params.properties["task_name"]!!.jsonObject
        assertEquals("string", taskName["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("^[a-z0-9_]+$", taskName["pattern"]?.jsonPrimitive?.contentOrNull)

        val forkTurns = params.properties["fork_turns"]!!.jsonObject
        // L6: 实现接受任意正整数（"none" | "all" | 正整数字符串），schema 不再
        // 钉死 enum ["none","all","3"]——描述写明取值域。
        assertEquals("string", forkTurns["type"]?.jsonPrimitive?.contentOrNull)
        assertTrue(forkTurns["enum"] == null, "fork_turns 不得再钉死 enum——实现接受任意正整数")
        val forkDescription = forkTurns["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("\"none\"" in forkDescription, "描述必须写明 \"none\" 取值")
        assertTrue("\"all\"" in forkDescription, "描述必须写明 \"all\" 取值")
        assertTrue("positive-integer" in forkDescription, "描述必须写明正整数字符串取值")

        assertTrue("role_assistant_id" in params.properties)
    }

    @Test
    fun spawnAgentDescriptionCarriesCodexSemantics() {
        val description = createSpawnAgentToolDeclaration().description
        assertTrue("same tools as you" in description, "必须声明 spawned agent 与父同工具面")
        assertTrue("spawn its own subagents" in description, "必须声明可再 spawn 孙线程")
        assertTrue("/root/" in description, "必须说明 canonical path 约定")
        assertTrue("FINAL_ANSWER" in description, "必须说明终态回传")
    }

    // MARK: - list_agents / interrupt_agent

    @Test
    fun listAgentsDeclarationPinsOptionalPathPrefix() {
        val tool = createListAgentsToolDeclaration()
        assertEquals("list_agents", tool.name)
        assertFalse(tool.needsApproval)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertTrue("path_prefix" in params.properties, "path_prefix 可选")
        assertTrue(params.required.isNullOrEmpty(), "list_agents 无必填参数")
    }

    @Test
    fun interruptAgentDeclarationPinsRequiredTarget() {
        val tool = createInterruptAgentToolDeclaration()
        assertEquals("interrupt_agent", tool.name)
        assertFalse(tool.needsApproval)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("target"), params.required)
        val target = params.properties["target"]!!.jsonObject
        assertTrue(
            "child_thread_id" in (target["description"]?.jsonPrimitive?.contentOrNull ?: ""),
            "target 描述必须同时接受 child_thread_id 与 agent path",
        )
        assertTrue("agent path" in (target["description"]?.jsonPrimitive?.contentOrNull ?: ""))
    }

    @Test
    fun interruptAgentDescriptionSaysThreadIsPreserved() {
        val description = createInterruptAgentToolDeclaration().description
        assertTrue("stays Open" in description, "interrupt 不销毁线程")
        assertTrue("idle" in description, "idle 返回语义必须在描述里")
    }

    // MARK: - send_message / followup_task / wait_agent（P1-d）

    @Test
    fun sendMessageDeclarationPinsRequiredTargetAndMessage() {
        val tool = createSendMessageToolDeclaration()
        assertEquals("send_message", tool.name)
        assertFalse(tool.needsApproval, "编排工具不设审批门（harness 拥有时机）")

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("target", "message"), params.required)

        // 描述必须明示「投递不唤醒」：idle 目标的邮件留在 mailbox 直到其下次 run。
        val description = tool.description
        assertTrue("does not trigger a new turn" in description, "必须明示不触发新 turn")
        assertTrue("mailbox" in description, "必须说明 mailbox 语义")
        assertTrue("idle" in description, "必须说明 idle 目标的行为")
    }

    @Test
    fun followupTaskDeclarationPinsRequiredTargetAndWakeSemantics() {
        val tool = createFollowupTaskToolDeclaration()
        assertEquals("followup_task", tool.name)
        assertFalse(tool.needsApproval)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("target", "message"), params.required)

        val description = tool.description
        assertTrue("idle" in description, "必须说明 idle 唤醒语义")
        assertTrue("running" in description || "queued" in description, "必须说明运行中目标的排队语义")
    }

    @Test
    fun waitAgentDeclarationPinsOptionalTimeoutAndInterruptSemantics() {
        val tool = createWaitAgentToolDeclaration()
        assertEquals("wait_agent", tool.name)
        assertFalse(tool.needsApproval)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertTrue("timeout_ms" in params.properties, "timeout_ms 可选")
        assertTrue(params.required.isNullOrEmpty(), "wait_agent 无必填参数")

        val description = tool.description
        assertTrue("interrupted" in description, "必须说明被新输入打断的语义")
        assertTrue("5000" in description && "300000" in description, "必须写明 timeout clamp 区间")
    }

    // MARK: - catalog discoverability（非常驻，deferred 池）

    @Test
    fun orchestrationNamesResolveThroughIosToolDeclarationCatalog() {
        val declarations = iosToolDeclarations(listOf("spawn_agent", "list_agents", "interrupt_agent"))
        assertEquals(listOf("spawn_agent", "list_agents", "interrupt_agent"), declarations.map { it.name })
        assertNotNull(iosToolDeclaration("spawn_agent"))
        assertNotNull(iosToolDeclaration("list_agents"))
        assertNotNull(iosToolDeclaration("interrupt_agent"))
    }

    @Test
    fun messagingNamesResolveThroughIosToolDeclarationCatalog() {
        val declarations = iosToolDeclarations(listOf("send_message", "followup_task", "wait_agent"))
        assertEquals(listOf("send_message", "followup_task", "wait_agent"), declarations.map { it.name })
        assertNotNull(iosToolDeclaration("send_message"))
        assertNotNull(iosToolDeclaration("followup_task"))
        assertNotNull(iosToolDeclaration("wait_agent"))
    }

    // MARK: - session_search / session_read（跨会话读取，与 Android 当前会话
    // conversation_search/conversation_expand 语义错开）

    @Test
    fun sessionSearchDeclarationPinsRequiredQueryAndLimitBounds() {
        val tool = createSessionSearchToolDeclaration()
        assertEquals("session_search", tool.name)
        assertFalse(tool.needsApproval, "跨会话读取不设审批门（只读 pure）")

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("query"), params.required)

        val query = params.properties["query"]!!.jsonObject
        assertEquals("string", query["type"]?.jsonPrimitive?.contentOrNull)

        val limit = params.properties["limit"]!!.jsonObject
        assertEquals("integer", limit["type"]?.jsonPrimitive?.contentOrNull)
        val limitDescription = limit["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("20" in limitDescription, "limit 描述必须写明上限 20")
        assertTrue("8" in limitDescription, "limit 描述必须写明默认 8")

        val description = tool.description
        assertTrue("ALL conversations" in description, "必须声明跨全部会话搜索")
        assertTrue("session_read" in description, "描述必须引导 follow-up session_read")
    }

    @Test
    fun sessionReadDeclarationPinsRequiredConversationIdAndMessageBounds() {
        val tool = createSessionReadToolDeclaration()
        assertEquals("session_read", tool.name)
        assertFalse(tool.needsApproval, "跨会话读取不设审批门（只读 pure）")

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("conversation_id"), params.required)

        val conversationId = params.properties["conversation_id"]!!.jsonObject
        assertEquals("string", conversationId["type"]?.jsonPrimitive?.contentOrNull)
        val idDescription = conversationId["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("session_search" in idDescription, "conversation_id 必须说明取自 session_search 结果")

        val maxMessages = params.properties["max_messages"]!!.jsonObject
        assertEquals("integer", maxMessages["type"]?.jsonPrimitive?.contentOrNull)
        val maxDescription = maxMessages["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("50" in maxDescription, "max_messages 描述必须写明上限 50")
        assertTrue("20" in maxDescription, "max_messages 描述必须写明默认 20")

        val description = tool.description
        assertTrue("Read-only" in description, "必须声明只读")
        assertTrue("session_search" in description, "描述必须说明 id 来自 session_search 结果")
    }

    @Test
    fun sessionReadNamesResolveThroughIosToolDeclarationCatalog() {
        val declarations = iosToolDeclarations(listOf("session_search", "session_read"))
        assertEquals(listOf("session_search", "session_read"), declarations.map { it.name })
        assertNotNull(iosToolDeclaration("session_search"))
        assertNotNull(iosToolDeclaration("session_read"))
    }
}

package app.amber.ai.provider.providers.openai

import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.isStreamArgsReplace
import app.amber.ai.util.HttpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 端到端覆盖 ResponseAPI 流式事件解析 (parseResponseDelta) + ResponseStreamReconciler
 * 调和 + MessageStreamAccumulator 累积:
 *
 * - final message (done/completed/incomplete) 与累积一致时不重复内容, 但保留
 *   usage / annotations / finishReason
 * - final-only 的 refusal / annotations / tool args / text 作为增量 delta 合并
 * - tool arguments 区分 append (arguments.delta) 与 replace (arguments.done) 语义
 * - response.failed / response.incomplete 终止事件
 */
class ResponseAPIStreamReconcileTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    private fun parse(event: String): MessageChunk? =
        api.parseResponseDelta(Json.parseToJsonElement(event).jsonObject)

    /** 模拟 streamText: 每个事件 parse -> reconcile -> 累积, 返回最终 assistant 消息 */
    private fun accumulate(vararg events: String): UIMessage {
        val reconciler = ResponseStreamReconciler()
        val accumulator = MessageStreamAccumulator(listOf(UIMessage.user("go")))
        events.forEach { event ->
            parse(event)?.let { accumulator.append(reconciler.reconcile(it)) }
        }
        return accumulator.snapshot().last()
    }

    // ==================== 事件 JSON ====================

    private fun textDelta(text: String, itemId: String = "msg_1") =
        """{"type":"response.output_text.delta","item_id":"$itemId","output_index":0,"content_index":0,"delta":"$text"}"""

    private fun textDone(text: String, itemId: String = "msg_1") =
        """{"type":"response.output_text.done","item_id":"$itemId","output_index":0,"content_index":0,"text":"$text"}"""

    private fun refusalDelta(text: String, itemId: String = "msg_1") =
        """{"type":"response.refusal.delta","item_id":"$itemId","output_index":0,"content_index":0,"delta":"$text"}"""

    private fun reasoningDelta(text: String, itemId: String = "rs_1") =
        """{"type":"response.reasoning_summary_text.delta","item_id":"$itemId","output_index":0,"delta":"$text"}"""

    private fun annotationAdded(title: String, url: String, itemId: String = "msg_1") =
        """{"type":"response.output_text.annotation.added","item_id":"$itemId","output_index":0,"content_index":0,"annotation_index":0,"annotation":{"type":"url_citation","start_index":0,"end_index":5,"url":"$url","title":"$title"}}"""

    private fun toolAdded(
        itemId: String = "fc_1",
        callId: String? = "call_1",
        name: String = "search",
        arguments: String = "",
    ): String {
        val callIdField = callId?.let { """"call_id":"$it",""" } ?: ""
        return """{"type":"response.output_item.added","output_index":0,"item":{"id":"$itemId","type":"function_call","status":"in_progress",$callIdField"name":"$name","arguments":"$arguments"}}"""
    }

    private fun argsDelta(delta: String, itemId: String = "fc_1") =
        """{"type":"response.function_call_arguments.delta","item_id":"$itemId","output_index":0,"delta":"$delta"}"""

    private fun argsDone(arguments: String, itemId: String = "fc_1") =
        """{"type":"response.function_call_arguments.done","item_id":"$itemId","output_index":0,"arguments":"$arguments"}"""

    private fun completed(output: String, usage: String = DEFAULT_USAGE) =
        """{"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","output":[$output],"usage":$usage}}"""

    private fun messageOutput(content: String) =
        """{"type":"message","id":"msg_1","role":"assistant","content":[$content]}"""

    // ==================== parser 层 ====================

    @Test
    fun `arguments delta event parses as append tool delta`() {
        val chunk = parse(argsDelta("""{\"q\":"""))!!

        val tool = chunk.choices.single().delta!!.parts
            .filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("fc_1", tool.toolCallId)
        assertEquals("""{"q":""", tool.input)
        assertFalse("arguments.delta 应为 append 语义", tool.isStreamArgsReplace())
    }

    @Test
    fun `arguments done event parses as replace tool delta`() {
        val chunk = parse(argsDone("""{\"q\":\"amber\"}"""))!!

        val tool = chunk.choices.single().delta!!.parts
            .filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("""{"q":"amber"}""", tool.input)
        assertTrue("arguments.done 应为 replace 语义", tool.isStreamArgsReplace())
    }

    @Test
    fun `annotation added event parses as annotation-only delta`() {
        val chunk = parse(annotationAdded("A", "https://a.example"))!!

        val delta = chunk.choices.single().delta!!
        assertTrue(delta.parts.isEmpty())
        assertEquals(
            listOf(UIMessageAnnotation.UrlCitation(title = "A", url = "https://a.example")),
            delta.annotations
        )
    }

    @Test
    fun `response failed event throws provider error`() {
        try {
            parse("""{"type":"response.failed","response":{"id":"resp_1","status":"failed","error":{"code":"server_error","message":"boom"}}}""")
            fail("Expected HttpException")
        } catch (error: HttpException) {
            assertEquals("boom", error.message)
        }
    }

    @Test
    fun `response incomplete event carries finish reason and partial output`() {
        val chunk = parse(
            """{"type":"response.incomplete","response":{"id":"resp_1","model":"gpt-test","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[${messageOutput("""{"type":"output_text","text":"partial"}""")}],"usage":$DEFAULT_USAGE}}"""
        )!!

        assertEquals("max_output_tokens", chunk.choices.single().finishReason)
        assertEquals(15, chunk.usage?.totalTokens)
    }

    @Test
    fun `completed output parses url citations into annotations`() {
        val chunk = parse(
            completed(messageOutput("""{"type":"output_text","text":"Hello","annotations":[{"type":"url_citation","url":"https://a.example","title":"A"}]}"""))
        )!!

        assertEquals(
            listOf(UIMessageAnnotation.UrlCitation(title = "A", url = "https://a.example")),
            chunk.choices.single().delta?.annotations ?: chunk.choices.single().message?.annotations
        )
    }

    // ==================== reconcile + accumulate 端到端 ====================

    @Test
    fun `final text matching accumulated merges usage and annotations without duplicating text`() {
        val assistant = accumulate(
            textDelta("Hello"),
            textDelta(" world"),
            textDone("Hello world"),
            completed(messageOutput("""{"type":"output_text","text":"Hello world","annotations":[{"type":"url_citation","url":"https://a.example","title":"A"}]}""")),
        )

        assertEquals(
            "Hello world",
            assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text
        )
        assertEquals(15, assistant.usage?.totalTokens)
        assertEquals(
            listOf(UIMessageAnnotation.UrlCitation(title = "A", url = "https://a.example")),
            assistant.annotations
        )
    }

    @Test
    fun `text only in done event is emitted once`() {
        val assistant = accumulate(
            textDone("full text"),
            completed(messageOutput("""{"type":"output_text","text":"full text"}""")),
        )

        assertEquals(
            "full text",
            assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text
        )
    }

    @Test
    fun `arguments deltas append and done replaces without duplication`() {
        val assistant = accumulate(
            toolAdded(),
            argsDelta("""{\"q\":"""),
            argsDelta("""\"amber\"}"""),
            argsDone("""{\"q\":\"amber\"}"""),
            completed("""{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":"{\"q\":\"amber\"}"}"""),
        )

        val tool = assistant.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("fc_1", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("""{"q":"amber"}""", tool.input)
    }

    @Test
    fun `tool args only in final completed are merged into streamed tool`() {
        val assistant = accumulate(
            toolAdded(),
            completed("""{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":"{\"q\":\"amber\"}"}"""),
        )

        val tool = assistant.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("fc_1", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("""{"q":"amber"}""", tool.input)
    }

    @Test
    fun `final tool matches streamed tool by order when call id is unknown`() {
        val assistant = accumulate(
            toolAdded(callId = null),
            completed("""{"type":"function_call","id":"fc_1","call_id":"call_9","name":"search","arguments":"{\"x\":1}"}"""),
        )

        val tool = assistant.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("fc_1", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("""{"x":1}""", tool.input)
    }

    @Test
    fun `tool only in final completed is added as new part`() {
        val assistant = accumulate(
            textDelta("Hi"),
            completed(
                messageOutput("""{"type":"output_text","text":"Hi"}""") + "," +
                    """{"type":"function_call","id":"fc_9","call_id":"call_9","name":"lookup","arguments":"{\"k\":2}"}"""
            ),
        )

        assertEquals(
            "Hi",
            assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text
        )
        val tool = assistant.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("call_9", tool.toolCallId)
        assertEquals("lookup", tool.toolName)
        assertEquals("""{"k":2}""", tool.input)
    }

    @Test
    fun `streamed refusal is not duplicated by final message`() {
        val assistant = accumulate(
            refusalDelta("I cannot help"),
            completed(messageOutput("""{"type":"refusal","refusal":"I cannot help"}""")),
        )

        assertEquals(
            "I cannot help",
            assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text
        )
    }

    @Test
    fun `refusal only in final message is appended as delta`() {
        val assistant = accumulate(
            textDelta("Hi"),
            completed(messageOutput("""{"type":"output_text","text":"Hi"},{"type":"refusal","refusal":"Cannot do that"}""")),
        )

        val text = assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue("应保留流式文本", text.startsWith("Hi"))
        assertTrue("final-only refusal 应被合并", text.contains("Cannot do that"))
    }

    @Test
    fun `streamed annotation dedupes with final annotations`() {
        val assistant = accumulate(
            textDelta("x"),
            annotationAdded("A", "https://a.example"),
            completed(messageOutput("""{"type":"output_text","text":"x","annotations":[{"type":"url_citation","url":"https://a.example","title":"A"},{"type":"url_citation","url":"https://b.example","title":"B"}]}""")),
        )

        assertEquals(
            listOf(
                UIMessageAnnotation.UrlCitation(title = "A", url = "https://a.example"),
                UIMessageAnnotation.UrlCitation(title = "B", url = "https://b.example"),
            ),
            assistant.annotations
        )
    }

    @Test
    fun `streamed reasoning is not duplicated by final summary`() {
        val assistant = accumulate(
            reasoningDelta("think"),
            textDelta("answer"),
            completed(
                """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"think"}]},""" +
                    messageOutput("""{"type":"output_text","text":"answer"}""")
            ),
        )

        assertEquals(
            "think",
            assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning
        )
        assertEquals(
            "answer",
            assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text
        )
    }

    @Test
    fun `reasoning only in final output is forwarded`() {
        val assistant = accumulate(
            textDelta("answer"),
            completed(
                """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"deep think"}]},""" +
                    messageOutput("""{"type":"output_text","text":"answer"}""")
            ),
        )

        assertEquals(
            "deep think",
            assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning
        )
    }

    @Test
    fun `incomplete response completes partial text without duplication`() {
        val assistant = accumulate(
            textDelta("par"),
            """{"type":"response.incomplete","response":{"id":"resp_1","model":"gpt-test","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[${messageOutput("""{"type":"output_text","text":"partial"}""")}],"usage":$DEFAULT_USAGE}}""",
        )

        assertEquals(
            "partial",
            assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text
        )
    }

    @Test
    fun `replace control metadata does not leak into accumulated tool part`() {
        val assistant = accumulate(
            toolAdded(callId = null),
            argsDone("""{\"q\":\"amber\"}"""),
        )

        val tool = assistant.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("""{"q":"amber"}""", tool.input)
        assertFalse(tool.isStreamArgsReplace())
        assertNull(tool.metadata?.get("stream_tool_args_replace"))
    }

    companion object {
        private const val DEFAULT_USAGE =
            """{"input_tokens":10,"output_tokens":5,"total_tokens":15}"""
    }
}

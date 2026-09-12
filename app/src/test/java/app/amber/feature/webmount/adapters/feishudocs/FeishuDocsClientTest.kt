package app.amber.feature.webmount.adapters.feishudocs

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuDocsClientTest {

    @Test
    fun appendCalloutUsesDescendantRequestForTextChild() = runBlocking {
        val requestPaths = mutableListOf<String>()
        val requestBodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requestPaths += request.url.toString()
            requestBodies += request.bodyText()
            respond(
                content = """
                    {
                      "code": 0,
                      "data": {
                        "children": [{"block_id": "callout-real"}],
                        "block_id_relations": [
                          {"block_id": "callout", "real_block_id": "callout-real"},
                          {"block_id": "text", "real_block_id": "text-real"}
                        ]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        try {
            val data = FeishuDocsClient(http).appendRichBlock(
                accessToken = "token",
                documentId = "doc-1",
                blockKind = "callout",
                text = "Callout text",
                parentBlockId = "root-1",
            )

            assertEquals(1, requestPaths.size)
            assertEquals(
                "https://open.feishu.cn/open-apis/docx/v1/documents/doc-1/blocks/root-1/descendant",
                requestPaths.single(),
            )
            val payload = Json.parseToJsonElement(requestBodies.single()).jsonObject
            assertEquals("-1", payload["index"]!!.jsonPrimitive.content)
            assertEquals("callout", payload["children_id"]!!.jsonArray.single().jsonPrimitive.content)
            val descendants = payload["descendants"]!!.jsonArray
            assertEquals(2, descendants.size)
            val callout = descendants[0].jsonObject
            assertEquals("callout", callout["block_id"]!!.jsonPrimitive.content)
            assertEquals("19", callout["block_type"]!!.jsonPrimitive.content)
            assertEquals("text", callout["children"]!!.jsonArray.single().jsonPrimitive.content)
            assertTrue(callout["callout"]!!.jsonObject.containsKey("emoji_id"))
            val text = descendants[1].jsonObject
            assertEquals("text", text["block_id"]!!.jsonPrimitive.content)
            assertEquals("2", text["block_type"]!!.jsonPrimitive.content)
            assertEquals(
                "Callout text",
                text["text"]!!.jsonObject["elements"]!!.jsonArray.single()
                    .jsonObject["text_run"]!!.jsonObject["content"]!!.jsonPrimitive.content,
            )
            assertEquals("callout-real", data["children"]!!.jsonArray.single().jsonObject["block_id"]!!.jsonPrimitive.content)
        } finally {
            http.close()
        }
    }

    private suspend fun HttpRequestData.bodyText(): String = when (val content = body) {
        is TextContent -> content.text
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> content.readFrom().toInputStream().readBytes().decodeToString()
        else -> error("Unexpected request body type: ${content::class.qualifiedName}")
    }
}

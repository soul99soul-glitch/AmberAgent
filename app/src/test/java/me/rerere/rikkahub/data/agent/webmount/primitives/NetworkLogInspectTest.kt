package me.rerere.rikkahub.data.agent.webmount.primitives

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkLogInspectTest {
    @Test
    fun inspectRedactsQueryValuesAndKeepsOpaqueTemplateId() {
        val log = NetworkLog()
        log.record(buildJsonObject {
            put("type", JsonPrimitive("fetch_send"))
            put("method", JsonPrimitive("GET"))
            put("url", JsonPrimitive("https://example.com/api/feed?token=secret&page=1"))
            put("body_preview", JsonPrimitive("should-not-be-surfaced"))
        })

        val template = log.inspect("https://example.com/home").jsonObject["templates"]!!.jsonArray.first().jsonObject

        assertTrue(template["request_template_id"]!!.jsonPrimitive.content.startsWith("wmreq_"))
        assertEquals("/api/feed?token=<redacted>&page=<redacted>", template["path"]!!.jsonPrimitive.content)
        assertTrue(template["same_origin"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(template["replayable"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(template.toString().contains("secret"))
        assertFalse(template.toString().contains("should-not-be-surfaced"))
    }

    @Test
    fun inspectMarksCrossOriginAndPostAsNotReplayable() {
        val log = NetworkLog()
        log.record(buildJsonObject {
            put("type", JsonPrimitive("xhr_send"))
            put("method", JsonPrimitive("POST"))
            put("url", JsonPrimitive("https://api.example.net/graphql"))
        })

        val template = log.inspect("https://example.com/home").jsonObject["templates"]!!.jsonArray.first().jsonObject

        assertEquals("POST", template["method"]!!.jsonPrimitive.content)
        assertFalse(template["same_origin"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(template["replayable"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun snapshotRedactsBodiesAndResponsePayloads() {
        val log = NetworkLog()
        log.record(buildJsonObject {
            put("type", JsonPrimitive("fetch_done"))
            put("method", JsonPrimitive("GET"))
            put("url", JsonPrimitive("https://example.com/api/me?token=secret"))
            put("status", JsonPrimitive(200))
            put("body_preview", JsonPrimitive("""{"csrf":"secret"}"""))
            put("response_preview", JsonPrimitive("""{"data":{"token":"secret"},"ok":true}"""))
            put("response_chars", JsonPrimitive(42))
        })

        val event = log.snapshot().jsonObject["events"]!!.jsonArray.first().jsonObject
        val serialized = event.toString()

        assertFalse(serialized.contains("secret"))
        assertFalse(serialized.contains("body_preview"))
        assertFalse(serialized.contains("response_preview"))
        assertEquals("https://example.com/api/me?token=<redacted>", event["url"]!!.jsonPrimitive.content)
        assertEquals("json_object", event["response_kind"]!!.jsonPrimitive.content)
        assertTrue(event["response_shape"]!!.jsonObject["top_keys"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun inspectRefusesObviousMutatingGetTemplates() {
        val log = NetworkLog()
        log.record(buildJsonObject {
            put("type", JsonPrimitive("fetch_send"))
            put("method", JsonPrimitive("GET"))
            put("url", JsonPrimitive("https://example.com/api/update?op=publish"))
        })

        val template = log.inspect("https://example.com/home").jsonObject["templates"]!!.jsonArray.first().jsonObject

        assertFalse(template["replayable"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun redactedUrlRemovesQueryValuesAndFragment() {
        assertEquals(
            "https://example.com/callback?token=<redacted>&state=<redacted>",
            NetworkLog.redactedUrl("https://example.com/callback?token=secret&state=abc#access_token=hidden")
        )
    }
}

package app.amber.feature.webmount.adapters.feishudocs

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuDocsNetworkSummaryTest {
    @Test
    fun summarizesNetworkEventsWithoutLeakingRawSensitiveData() {
        val snapshot = buildJsonObject {
            put("last_event_seq", 2)
            put("buffer_newest_seq", 2)
            put("more_available", false)
            put("events", buildJsonArray {
                add(buildJsonObject {
                    put("seq", 1)
                    put("type", "fetch_send")
                    put("method", "POST")
                    put("url", "https://open.feishu.cn/open-apis/docx/v1/documents/AbCdEf1234567890/raw_content?tenant_access_token=SECRET")
                    put("body_preview", "{\"Authorization\":\"Bearer SECRET\"}")
                })
                add(buildJsonObject {
                    put("seq", 2)
                    put("type", "fetch_done")
                    put("method", "POST")
                    put("url", "https://open.feishu.cn/open-apis/docx/v1/documents/AbCdEf1234567890/raw_content?tenant_access_token=SECRET")
                    put("status", 200)
                    put("response_preview", "{\"code\":0,\"data\":{\"content\":\"private body\"}}")
                    put("response_chars", 50)
                })
                add(buildJsonObject {
                    put("seq", 3)
                    put("type", "fetch_done")
                    put("method", "GET")
                    put("url", "https://example.com/private/path?token=SECRET")
                    put("status", 200)
                    put("response_preview", "{\"secret\":\"body\"}")
                    put("response_chars", 20)
                })
            })
        }

        val summary = FeishuDocsNetworkSummary.summarize("wm_1", snapshot)
        val output = summary.toString()

        assertFalse(output.contains("tenant_access_token"))
        assertFalse(output.contains("Authorization"))
        assertFalse(output.contains("private body"))
        assertFalse(output.contains("body_preview"))
        assertFalse(output.contains("response_preview"))
        assertFalse(output.contains("example.com"))
        assertFalse(output.contains("private/path"))
        val event = summary["events"]!!.jsonArray.last().jsonObject
        assertTrue(event["endpoint_key"]!!.jsonPrimitive.content.contains("{token}"))
        assertTrue(event["is_doc_candidate"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(event["response_shape"]!!.jsonObject["top_keys"]!!.jsonArray.isNotEmpty())
    }
}

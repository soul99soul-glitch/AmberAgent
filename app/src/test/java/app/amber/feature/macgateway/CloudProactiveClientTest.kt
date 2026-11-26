package app.amber.feature.macgateway

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProactiveClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesLegacySummaryJsonWithoutNewP2AndP3FieldsAndPreservesUnknownOutcome() {
        val legacyJson = """
            {
              "monitors": [
                {
                  "id": "mon_1",
                  "title": "Docs",
                  "url": "https://example.com/feed",
                  "enabled": true,
                  "createdAtMs": 1000,
                  "nextRunAtMs": 2000
                }
              ],
              "webhooks": [
                {
                  "id": "wh_1",
                  "name": "CI",
                  "enabled": true,
                  "createdAtMs": 1000
                }
              ],
              "restraint": {
                "quietHoursEnabled": true,
                "quietHoursStart": "23:00",
                "quietHoursEnd": "08:00",
                "zoneId": "Asia/Shanghai",
                "dailyMaxPushes": 5,
                "pushesToday": 1,
                "remainingToday": 4,
                "bufferedPulseCount": 0,
                "mutedTopicsCount": 0
              },
              "recentRuns": [
                {
                  "id": "run_1",
                  "signalId": "sig_1",
                  "category": "WATCHTOWER",
                  "topicKey": "watchtower:1",
                  "title": "Docs",
                  "outcome": "future_server_outcome_v9",
                  "summary": "Unknown outcome preserved",
                  "timestampEpochMs": 3000
                }
              ]
            }
        """.trimIndent()

        val summary = json.decodeFromString(CloudProactiveSummary.serializer(), legacyJson)
        assertEquals(1, summary.monitors.size)
        assertEquals("immediate", summary.monitors.single().deliveryMode)
        assertEquals(1, summary.webhooks.size)
        assertTrue(summary.wakeups.isEmpty())
        assertTrue(summary.restraint.pulseEnabled)
        assertEquals("08:30", summary.restraint.pulseTime)
        assertTrue(summary.restraint.commitmentExtractionEnabled)
        assertTrue(summary.restraint.mutedTopics.isEmpty())
        assertEquals("future_server_outcome_v9", summary.recentRuns.single().outcome)
    }

    @Test
    fun callsAllProactiveV2EndpointsWithExpectedMethodsPathsPayloadsAnd404Handling() = runBlocking {
        data class RecordedCall(
            val method: String,
            val path: String,
            val auth: String?,
            val body: String,
        )

        val calls = CopyOnWriteArrayList<RecordedCall>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            calls += RecordedCall(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                auth = exchange.requestHeaders.getFirst("Authorization"),
                body = body,
            )
            val (status, payload) = when {
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/proactive/summary" ->
                    200 to """{"monitors":[{"id":"mon_1","title":"Amber","url":"https://github.com/o/r/releases.atom","deliveryMode":"digest","createdAtMs":1,"nextRunAtMs":2}],"webhooks":[{"id":"wh_1","name":"Deploy","createdAtMs":1}],"restraint":{"quietHoursEnabled":true,"quietHoursStart":"23:00","quietHoursEnd":"08:00","zoneId":"Asia/Shanghai","dailyMaxPushes":5,"pushesToday":1,"remainingToday":4,"bufferedPulseCount":2,"mutedTopicsCount":1,"pulseEnabled":true,"pulseTime":"08:30","commitmentExtractionEnabled":true,"mutedTopics":[{"topicKey":"watchtower:1","untilEpochMs":99999}]},"recentRuns":[],"wakeups":[{"id":"wk_abc","kind":"commitment","title":"回访 PR","triggerAtEpochMs":5000,"createdAtEpochMs":4000}]}"""
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/proactive/signals/wt_123" ->
                    200 to """{"event":{"seq":9,"id":"proactive:wt_123","kind":"proactive_message","role":"assistant","text":"Details","topicId":"proactive:wt_123","proactive":{"signalId":"wt_123","topicKey":"watchtower:1","category":"WATCHTOWER","title":"Release"}}}"""
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/proactive/signals/missing_1" ->
                    404 to """{"error":"signal_not_found"}"""
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/proactive/signals/wt_123/feedback" ->
                    200 to """{"ok":true,"action":"snooze","applied":true,"wakeupId":"wk_snooze_1"}"""
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/proactive/restraint/unmute" ->
                    200 to """{"restraint":{"quietHoursEnabled":true,"quietHoursStart":"23:00","quietHoursEnd":"08:00","zoneId":"Asia/Shanghai","dailyMaxPushes":5,"pushesToday":1,"remainingToday":4,"bufferedPulseCount":0,"mutedTopicsCount":0,"mutedTopics":[]}}"""
                exchange.requestMethod == "PATCH" && exchange.requestURI.path == "/v2/proactive/restraint" ->
                    200 to """{"restraint":{"quietHoursEnabled":false,"quietHoursStart":"22:30","quietHoursEnd":"07:30","zoneId":"Asia/Shanghai","dailyMaxPushes":8,"pushesToday":1,"remainingToday":7,"bufferedPulseCount":0,"mutedTopicsCount":0,"pulseEnabled":false,"pulseTime":"09:00","commitmentExtractionEnabled":false,"mutedTopics":[]}}"""
                exchange.requestMethod == "PATCH" && exchange.requestURI.path == "/v2/proactive/monitors/mon_1" ->
                    200 to """{"monitor":{"id":"mon_1","title":"Amber","url":"https://github.com/o/r/releases.atom","enabled":false,"deliveryMode":"digest","createdAtMs":1,"nextRunAtMs":2}}"""
                exchange.requestMethod == "DELETE" && exchange.requestURI.path == "/v2/proactive/monitors/mon_1" ->
                    200 to """{"deleted":true,"id":"mon_1"}"""
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/proactive/monitors" ->
                    201 to """{"monitor":{"id":"mon_gh","title":"soul99soul-glitch/AmberAgent Releases","url":"https://github.com/soul99soul-glitch/AmberAgent/releases.atom","enabled":true,"deliveryMode":"digest","createdAtMs":1,"nextRunAtMs":2}}"""
                exchange.requestMethod == "PATCH" && exchange.requestURI.path == "/v2/proactive/webhooks/wh_1" ->
                    200 to """{"webhook":{"id":"wh_1","name":"Deploy","enabled":false,"createdAtMs":1}}"""
                exchange.requestMethod == "DELETE" && exchange.requestURI.path == "/v2/proactive/webhooks/wh_1" ->
                    200 to """{"deleted":true,"id":"wh_1"}"""
                exchange.requestMethod == "DELETE" && exchange.requestURI.path == "/v2/proactive/wakeups/wk_abc" ->
                    200 to """{"cancelled":true,"id":"wk_abc"}"""
                else -> 500 to """{"error":"unexpected ${exchange.requestMethod} ${exchange.requestURI.path}"}"""
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok-p", scheme = "http")

            val summary = client.proactiveSummary()
            assertEquals(1, summary.wakeups.size)
            assertEquals("digest", summary.monitors.single().deliveryMode)
            assertEquals("watchtower:1", summary.restraint.mutedTopics.single().topicKey)

            val signal = client.proactiveSignal("wt_123")
            assertNotNull(signal)
            assertEquals("wt_123", signal!!.proactive?.signalId)

            assertNull(client.proactiveSignal("missing_1"))

            val fb = client.proactiveFeedback("wt_123", "snooze", snoozeMinutes = 90)
            assertTrue(fb.ok && fb.applied)
            assertEquals("wk_snooze_1", fb.wakeupId)

            val unmuted = client.proactiveUnmute("watchtower:1")
            assertEquals(0, unmuted.mutedTopicsCount)

            val patched = client.proactivePatchRestraint(
                CloudProactiveRestraintPatch(
                    quietHoursEnabled = false,
                    dailyMaxPushes = 8,
                    pulseEnabled = false,
                    pulseTime = "09:00",
                    commitmentExtractionEnabled = false,
                ),
            )
            assertFalse(patched.quietHoursEnabled)
            assertEquals("09:00", patched.pulseTime)
            assertFalse(patched.commitmentExtractionEnabled)

            val toggledMon = client.proactiveSetMonitorEnabled("mon_1", false)
            assertFalse(toggledMon.enabled)

            val digestMon = client.proactiveSetMonitorDeliveryMode("mon_1", "digest")
            assertEquals("digest", digestMon.deliveryMode)

            client.proactiveDeleteMonitor("mon_1")

            val createdGh = client.proactiveCreateGithubReleaseMonitor(
                owner = "soul99soul-glitch",
                repo = "AmberAgent",
                deliveryMode = "digest",
            )
            assertEquals("https://github.com/soul99soul-glitch/AmberAgent/releases.atom", createdGh.url)

            val toggledWh = client.proactiveSetWebhookEnabled("wh_1", false)
            assertFalse(toggledWh.enabled)
            client.proactiveDeleteWebhook("wh_1")
            client.proactiveCancelWakeup("wk_abc")

            assertTrue(calls.all { it.auth == "Bearer tok-p" })

            val ghCreateCall = calls.single { it.method == "POST" && it.path == "/v2/proactive/monitors" }
            val ghBody = json.parseToJsonElement(ghCreateCall.body).jsonObject
            assertEquals("soul99soul-glitch/AmberAgent Releases", ghBody["title"]!!.jsonPrimitive.content)
            assertEquals("https://github.com/soul99soul-glitch/AmberAgent/releases.atom", ghBody["url"]!!.jsonPrimitive.content)
            assertEquals("digest", ghBody["deliveryMode"]!!.jsonPrimitive.content)

            val fbCall = calls.single { it.method == "POST" && it.path == "/v2/proactive/signals/wt_123/feedback" }
            val fbBody = json.parseToJsonElement(fbCall.body).jsonObject
            assertEquals("snooze", fbBody["action"]!!.jsonPrimitive.content)
            assertEquals(90, fbBody["snoozeMinutes"]!!.jsonPrimitive.int)

            val patchRestraintCall = calls.single { it.method == "PATCH" && it.path == "/v2/proactive/restraint" }
            val restraintBody = json.parseToJsonElement(patchRestraintCall.body).jsonObject
            assertFalse(restraintBody["quietHoursEnabled"]!!.jsonPrimitive.boolean)
            assertFalse(restraintBody.containsKey("quietHoursStart"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun rejectsInvalidSignalIdAndInvalidGithubOwnerOrRepoBeforeSendingRequest() = runBlocking {
        val client = MacGatewayClient(listOf("127.0.0.1"), port = 1, fingerprint = "fp", token = "tok", scheme = "http")

        val badSignalIds = listOf("", " ", "bad/id", "../etc", "a?b=1", "a#frag", "a".repeat(49), "sig with space", "sig.dot")
        for (bad in badSignalIds) {
            assertTrue("expected rejection for signalId=$bad", runCatching { client.proactiveSignal(bad) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue("expected rejection for feedback signalId=$bad", runCatching { client.proactiveFeedback(bad, "opened") }.exceptionOrNull() is IllegalArgumentException)
        }

        val badGithubPairs = listOf(
            "." to "repo",
            ".." to "repo",
            "owner" to ".",
            "owner" to "..",
            "https://github.com/a" to "repo",
            "owner/sub" to "repo",
            "owner" to "repo/sub",
            "owner" to "repo?foo=1",
            "owner" to "repo#frag",
            "owner" to "repo..name",
            "-owner" to "repo",
            "owner-" to "repo",
            "own--er" to "repo",
            "owner" to ".hidden",
            "owner" to "repo.",
            "owner" to "repo name",
            "" to "repo",
            "owner" to "",
        )
        for ((owner, repo) in badGithubPairs) {
            val error = runCatching { client.proactiveCreateGithubReleaseMonitor(owner, repo) }.exceptionOrNull()
            assertTrue("expected rejection for $owner/$repo, got $error", error is IllegalArgumentException)
        }

        val badModeError = runCatching { client.proactiveSetMonitorDeliveryMode("mon_1", "weekly") }.exceptionOrNull()
        assertTrue(badModeError is IllegalArgumentException)
    }
}

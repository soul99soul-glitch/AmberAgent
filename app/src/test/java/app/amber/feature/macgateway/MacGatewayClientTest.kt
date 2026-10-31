package app.amber.feature.macgateway

import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacGatewayClientTest {
    private fun encode(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private val validJson =
        """{"v":1,"id":"gw","name":"Studio","addrs":["studio.local","192.168.1.5"],"port":47821,"fp":"abc=","s":"secret"}"""

    @Test
    fun parsesTheFullLinkAndTheBareValue() {
        val encoded = encode(validJson)
        val fromLink = MacGatewayPairingPayload.parse("amber://gateway/pair?p=$encoded")
        assertEquals(listOf("studio.local", "192.168.1.5"), fromLink?.addrs)
        assertEquals("secret", fromLink?.s)
        assertEquals(fromLink, MacGatewayPairingPayload.parse("  $encoded\n"))
    }

    @Test
    fun rejectsGarbageAndUnknownVersions() {
        assertNull(MacGatewayPairingPayload.parse(""))
        assertNull(MacGatewayPairingPayload.parse("amber://gateway/pair?p=!!!"))
        assertNull(MacGatewayPairingPayload.parse(encode(validJson.replace("\"v\":1", "\"v\":2"))))
        assertNull(MacGatewayPairingPayload.parse(encode(validJson.replace("[\"studio.local\",\"192.168.1.5\"]", "[]"))))
        assertNull(MacGatewayPairingPayload.parse("amber://gateway/pair?p=" + "A".repeat(5000)))
    }

    @Test
    fun reportsUnreachableWhenNoAddressAnswers() {
        val client = MacGatewayClient(listOf("127.0.0.1"), port = 1, fingerprint = "abc=", token = "t")
        val error = runCatching { runBlocking { client.status() } }.exceptionOrNull()
        assertTrue(error is MacGatewayException.Unreachable)
    }

    // ---------------------------------------------------------------------------------------
    // P4 / A4.2: the Mac owns the task list but not its order. The phone must present it stably,
    // because the 10 s refresh would otherwise reshuffle rows and `take(limit)` could hide a task
    // that is waiting for a human decision.
    // ---------------------------------------------------------------------------------------

    private fun session(
        key: String,
        state: String,
        updatedAt: Double = 0.0,
        abnormal: Boolean = false,
    ) = MacGatewayStatus.Session(
        key = key,
        agent = "codex",
        subject = "task $key",
        origin = "cli",
        state = state,
        abnormal = abnormal,
        monitored = false,
        updatedAt = updatedAt,
    )

    @Test
    fun waitingTasksSortAboveRunningAndFinishedOnes() {
        val sorted = MacGatewaySessionOrdering.sort(
            listOf(
                session("done", "completed", updatedAt = 900.0),
                session("stalled", "stalled", updatedAt = 800.0),
                session("run", "running", updatedAt = 700.0),
                session("wait", "waiting", updatedAt = 100.0),
            )
        )
        assertEquals(listOf("wait", "run", "stalled", "done"), sorted.map { it.key })
    }

    @Test
    fun orderingIsStableWhenTheGatewayReturnsTheSameSessionsInAnotherOrder() {
        val a = session("a", "running", updatedAt = 500.0)
        val b = session("b", "running", updatedAt = 500.0)
        val first = MacGatewaySessionOrdering.sort(listOf(a, b)).map { it.key }
        val second = MacGatewaySessionOrdering.sort(listOf(b, a)).map { it.key }
        assertEquals(first, second)
        assertEquals(listOf("a", "b"), first)
    }

    @Test
    fun theVisibleLimitNeverDropsAWaitingTaskInFavourOfAFinishedOne() {
        val sessions = listOf(
            session("done-1", "completed", updatedAt = 999.0),
            session("done-2", "completed", updatedAt = 998.0),
            session("wait", "waiting", updatedAt = 1.0),
        )
        val visible = MacGatewaySessionOrdering.visible(sessions, limit = 1)
        assertEquals(listOf("wait"), visible.map { it.key })
    }

    @Test
    fun abnormalSessionsRankWithStalledOnesAndUnknownStatesStayVisible() {
        val sorted = MacGatewaySessionOrdering.sort(
            listOf(
                session("finished", "completed", updatedAt = 10.0),
                session("weird", "some_future_state", updatedAt = 20.0),
                session("bad", "running", updatedAt = 30.0, abnormal = true),
            )
        )
        assertEquals(listOf("bad", "weird", "finished"), sorted.map { it.key })
    }

    @Test
    fun parsesSessionSummaryAndPendingApprovalAndBlocksHighRiskAllowOnClient() {
        val rawStatus = """
            {
              "gateway": { "id": "gw-1", "name": "Mac Studio" },
              "device": { "id": "dev-1", "name": "Pixel", "pushRegistered": false },
              "serverTime": 1000.0,
              "sessions": [
                {
                  "key": "s-1",
                  "agent": "amber",
                  "subject": "Deploy check",
                  "origin": "desktop",
                  "state": "waiting",
                  "abnormal": false,
                  "monitored": true,
                  "updatedAt": 1000.0,
                  "summary": "Waiting to run shell command",
                  "pendingApproval": {
                    "toolCallId": "tc-99",
                    "tool": "bash",
                    "detail": "rm -rf build",
                    "highRisk": true
                  }
                }
              ]
            }
        """.trimIndent()
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(MacGatewayStatus.serializer(), rawStatus)
        val session = parsed.sessions.single()
        assertEquals("Waiting to run shell command", session.summary)
        assertEquals("tc-99", session.pendingApproval?.toolCallId)
        assertEquals("bash", session.pendingApproval?.tool)
        assertEquals("rm -rf build", session.pendingApproval?.detail)
        assertEquals(true, session.pendingApproval?.highRisk)

        val client = MacGatewayClient(listOf("127.0.0.1"), port = 1, fingerprint = "abc=", token = "t")
        val error = runCatching {
            runBlocking {
                client.decide(sessionKey = "s-1", toolCallId = "tc-99", allow = true, highRisk = true)
            }
        }.exceptionOrNull()
        assertTrue(error is MacGatewayException.Server)
    }
}

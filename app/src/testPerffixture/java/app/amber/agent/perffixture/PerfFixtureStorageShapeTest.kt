package app.amber.agent.perffixture

import android.app.Application
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.JsonInstant
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PerfFixtureStorageShapeTest {
    @Test fun s2AndS3UseIdenticalSerializedTailMessages() {
        val context = RuntimeEnvironment.getApplication()
        val a = PerfFixtureScenarios.buildConversation(context, PerfFixtureScenarios.spec("S2"))
        val b = PerfFixtureScenarios.buildConversation(context, PerfFixtureScenarios.spec("S3"))
        assertEquals(120, a.messageNodes.size)
        assertEquals(1000, b.messageNodes.size)
        val left = JsonInstant.encodeToString(a.messageNodes.takeLast(8).map { it.messages })
        val right = JsonInstant.encodeToString(b.messageNodes.takeLast(8).map { it.messages })
        assertEquals(left, right)
        println("S2/S3 shared-tail message SHA256=" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(left.toByteArray()).joinToString("") { "%02x".format(it) })
    }
    @Test fun s5KeepsAllToolsAndSizeTiersAcrossEightDurableRows() {
        val context = RuntimeEnvironment.getApplication()
        val spec = PerfFixtureScenarios.spec("S5")
        val conversation = PerfFixtureScenarios.buildConversation(context, spec)
        val repeat = PerfFixtureScenarios.buildConversation(context, spec)
        assertEquals(8, conversation.messageNodes.size)
        assertEquals(JsonInstant.encodeToString(conversation), JsonInstant.encodeToString(repeat))
        val tools = conversation.messageNodes.flatMap { it.currentMessage.parts }.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(80, tools.size)
        assertEquals(80, tools.map { it.toolCallId }.toSet().size)
        val sizes = tools.map { (it.output.single() as UIMessagePart.Text).text.toByteArray().size }
        assertEquals(2, sizes.count { it == 1_048_576 })
        assertEquals(2, sizes.count { it == 262_144 })
        assertEquals(76, sizes.count { it == 1024 })
        assertTrue(sizes.sum() <= 4 * 1024 * 1024)
        conversation.messageNodes.forEach { node ->
            val rowBytes = JsonInstant.encodeToString(node.messages).toByteArray().size
            assertTrue("row bytes=$rowBytes", rowBytes < 1_100_000)
        }
    }
}

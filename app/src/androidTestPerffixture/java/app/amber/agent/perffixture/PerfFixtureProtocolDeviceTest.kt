package app.amber.agent.perffixture

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.ui.utils.ChatPerfCommandBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.FileInputStream

/** Compiled here; only the primary device owner runs these against the fixture APK. */
@RunWith(AndroidJUnit4::class)
class PerfFixtureProtocolDeviceTest {
    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return automation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }
    }

    @Test fun shellSerializedSnapshotNonceReachesActualReceiver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val command = instrumentation.context.assets.open("s8-snapshot-command.txt")
            .bufferedReader().use { it.readText() }.trim().replace("0123456789abcdef", nonce)
        val oldSnapshot = ChatPerfCommandBridge.snapshot
        try {
            ChatPerfCommandBridge.snapshot = { "conversation=receiver-owner nodes=1 loading=false" }
            shell(command)
            val deadline = SystemClock.elapsedRealtime() + 5000
            var matched = false
            while (SystemClock.elapsedRealtime() < deadline && !matched) {
                val log = shell("logcat -d -s AmberPerfFixture:I")
                matched = log.lineSequence().any {
                    "snapshot nonce=$nonce " in it && "conversation=receiver-owner" in it
                }
                if (!matched) SystemClock.sleep(50)
            }
            assertTrue("actual receiver did not receive nonce=$nonce", matched)
        } finally { ChatPerfCommandBridge.snapshot = oldSnapshot }
    }

    @Test fun s5RealRoomRowsRoundTripWithoutChangingCursorWindow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val koin = GlobalContext.get()
        val repository = koin.get<ConversationRepository>()
        val seeded = PerfFixtureScenarios.seed(context, repository, koin.get<SettingsAggregator>(), "S5")
        assertEquals(8, seeded.nodeCount)
        val verification = PerfFixtureScenarios.verifyPersisted(context, repository, "S5")
        assertTrue(verification.getBoolean("verified"))
        assertEquals(8, verification.getJSONArray("rows").length())
        // The standalone prepare CLI additionally repeats this in a fresh process.
    }
}

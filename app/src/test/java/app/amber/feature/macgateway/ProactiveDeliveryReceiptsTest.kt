package app.amber.feature.macgateway

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.ProactiveCardEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProactiveDeliveryReceiptsTest {
    @Test fun feedbackAndPushBeforePollSurviveRestartAndStayOnTheirPairing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("receipts-test", Context.MODE_PRIVATE)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.proactiveCardDao()
            val first = ProactiveDeliveryReceipts(dao, prefs)
            first.notified("gw", "device", "signal", 12)
            first.feedback("gw", "device", "signal", "snooze")
            first.feedback("gw", "device", "signal", "opened")
            assertNull(dao.findBySignal("gw", "device", "signal"))
            val restored = ProactiveDeliveryReceipts(dao, prefs)
            assertTrue(restored.handled("gw", "device", "signal"))
            assertFalse(restored.handled("gw", "other", "signal"))
            val card = ProactiveCardEntity("gw", "device", "proactive:signal", "signal", 10, "body", "{}", 99)
            restored.insertEvent(card.copy(deviceId = "other"))
            restored.insertEvent(card)
            restored.insertEvent(card)
            restored.feedback("gw", "device", "signal", "opened")
            assertTrue(prefs.all.isEmpty())
            assertFalse(restored.handled("gw", "other", "signal"))
            assertEquals(listOf(card.copy(notifiedAt = 12, feedbackState = "snooze")),
                dao.observeForGateway("gw", "device").first())
        } finally {
            db.close()
        }
    }
}

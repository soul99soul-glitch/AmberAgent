package app.amber.feature.macgateway

import android.content.SharedPreferences
import app.amber.agent.data.db.dao.ProactiveCardDao
import app.amber.agent.data.db.entity.ProactiveCardEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** FCM and detail feedback may arrive before poll delivers the Room row. Keep only these
 * unmatched receipts in preferences until the event is persisted. A detail fetch never
 * creates a timeline card. */
class ProactiveDeliveryReceipts(
    private val dao: ProactiveCardDao,
    private val pending: SharedPreferences?,
) {
    private val lock = Mutex()

    private fun key(gateway: String, device: String, signal: String): String =
        "${gateway.length}:$gateway${device.length}:$device$signal"

    suspend fun insertEvent(entity: ProactiveCardEntity): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(entity.gatewayId, entity.deviceId, entity.signalId)
            val time = pending?.getLong("n$key", 0L)?.takeIf { it > 0 }
            val feedback = pending?.getString("f$key", null)
            val inserted = dao.insert(entity.copy(notifiedAt = time, feedbackState = feedback))
            if (time != null || feedback != null) {
                if (inserted == -1L) {
                    // Idempotent replay after Room committed and before preferences cleanup.
                    time?.let { dao.markNotified(entity.gatewayId, entity.deviceId, entity.signalId, it) }
                    feedback?.let { dao.setFeedback(entity.gatewayId, entity.deviceId, entity.signalId, it) }
                }
                check(checkNotNull(pending).edit().remove("n$key").remove("f$key").commit())
            }
        }
    }

    suspend fun handled(gateway: String, device: String, signal: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = key(gateway, device, signal)
            val row = dao.findBySignal(gateway, device, signal)
            row?.notifiedAt != null || row?.feedbackState != null ||
                pending?.contains("n$key") == true || pending?.contains("f$key") == true
        }
    }

    suspend fun notified(gateway: String, device: String, signal: String, time: Long): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            if (dao.markNotified(gateway, device, signal, time) == 0 && dao.findBySignal(gateway, device, signal) == null) {
                pending?.let { check(it.edit().putLong("n${key(gateway, device, signal)}", time).commit()) }
            }
        }
    }

    suspend fun feedback(gateway: String, device: String, signal: String, state: String): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            if (dao.setFeedback(gateway, device, signal, state) == 0 && dao.findBySignal(gateway, device, signal) == null) {
                val prefKey = "f${key(gateway, device, signal)}"
                pending?.let {
                    if (state != "opened" || !it.contains(prefKey)) check(it.edit().putString(prefKey, state).commit())
                }
            }
        }
    }
}

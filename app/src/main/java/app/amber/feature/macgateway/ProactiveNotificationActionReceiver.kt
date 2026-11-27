package app.amber.feature.macgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Non-exported receiver for proactive notification background actions ("In 1 hour" / "Less like this").
 *
 * Strictly synchronous enqueue only: validates non-secret identifiers and hands off to
 * [ProactiveFeedbackWorker] via `enqueueUniqueWork(..., ExistingWorkPolicy.KEEP, ...)`.
 * Never transmits or stores gateway tokens in Intent extras or WorkData.
 */
class ProactiveNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val spec = parseEnqueueSpec(intent) ?: return
        val appContext = context.applicationContext
        val request = buildWorkRequest(spec)
        enqueueUniqueWork(appContext, spec.uniqueWorkName, request)
        runCatching {
            NotificationManagerCompat.from(appContext).cancel(spec.notificationTag, spec.notificationId)
        }
    }

    data class EnqueueSpec(
        val gatewayId: String,
        val deviceId: String,
        val signalId: String,
        val action: ProactiveFeedbackAction,
        val uniqueWorkName: String,
        val notificationTag: String,
        val notificationId: Int,
    )

    companion object {
        internal var enqueueWorkOverride: ((Context, String, OneTimeWorkRequest) -> Unit)? = null

        fun uniqueWorkName(gatewayId: String, deviceId: String, signalId: String, actionWire: String): String =
            "proactive-feedback/${Uri.encode(gatewayId)}/${Uri.encode(deviceId)}/$signalId/$actionWire"

        fun parseEnqueueSpec(intent: Intent?): EnqueueSpec? {
            if (intent == null) return null
            val feedbackAction = when (intent.action) {
                ACTION_PROACTIVE_SNOOZE -> ProactiveFeedbackAction.SNOOZE
                ACTION_PROACTIVE_MUTE -> ProactiveFeedbackAction.MUTE_TOPIC
                else -> return null
            }
            val gatewayId = intent.getStringExtra(ProactiveNotificationPresenter.EXTRA_GATEWAY_ID)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val deviceId = intent.getStringExtra(ProactiveNotificationPresenter.EXTRA_DEVICE_ID)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val signalId = intent.getStringExtra(ProactiveNotificationPresenter.EXTRA_SIGNAL_ID)
                ?.takeIf { ProactiveContract.isValidSignalId(it) }
                ?: return null
            val notificationTag = intent.getStringExtra(ProactiveNotificationPresenter.EXTRA_NOTIFICATION_TAG)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "proactive:$signalId"
            val notificationId = intent.getIntExtra(
                ProactiveNotificationPresenter.EXTRA_NOTIFICATION_ID,
                ProactiveNotificationPresenter.PROACTIVE_NOTIFICATION_ID,
            )
            return EnqueueSpec(
                gatewayId = gatewayId,
                deviceId = deviceId,
                signalId = signalId,
                action = feedbackAction,
                uniqueWorkName = uniqueWorkName(gatewayId, deviceId, signalId, feedbackAction.wire),
                notificationTag = notificationTag,
                notificationId = notificationId,
            )
        }

        fun buildWorkData(spec: EnqueueSpec): Data = Data.Builder()
            .putString(ProactiveFeedbackWorker.KEY_GATEWAY_ID, spec.gatewayId)
            .putString(ProactiveFeedbackWorker.KEY_DEVICE_ID, spec.deviceId)
            .putString(ProactiveFeedbackWorker.KEY_SIGNAL_ID, spec.signalId)
            .putString(ProactiveFeedbackWorker.KEY_ACTION, spec.action.wire)
            .build()

        fun buildWorkRequest(spec: EnqueueSpec): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ProactiveFeedbackWorker>()
                .setInputData(buildWorkData(spec))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

        private fun enqueueUniqueWork(
            context: Context,
            uniqueWorkName: String,
            request: OneTimeWorkRequest,
        ) {
            enqueueWorkOverride?.let { override ->
                override(context, uniqueWorkName, request)
                return
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

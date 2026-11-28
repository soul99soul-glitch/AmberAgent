package app.amber.core.automation.display.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.amber.agent.R
import app.amber.agent.phoneUseDisplaySettingsIntent
import app.amber.core.automation.display.EmbeddedAdbDisplayBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.net.InetSocketAddress

internal enum class PairingStage(val message: Int, val acceptsCode: Boolean = false, val finished: Boolean = false) {
    WAITING(R.string.vd_adb_waiting, acceptsCode = true),
    FOUND(R.string.vd_adb_found, acceptsCode = true),
    INVALID_CODE(R.string.vd_adb_six_digits, acceptsCode = true),
    PAIRING(R.string.vd_adb_pairing),
    CONNECTING(R.string.vd_adb_working),
    CONNECTED(R.string.vd_adb_success_connected, finished = true),
    DISCONNECTED(R.string.vd_adb_success_disconnected, finished = true),
    FAILED(R.string.vd_adb_pair_error, finished = true),
    DISCOVERY_UNAVAILABLE(R.string.vd_adb_permission_hint, finished = true),
}

/** Main-thread owner of the discovery subscription and the single reply for one prompt. */
internal class WirelessPairingRun(
    private val scope: CoroutineScope,
    private val discover: () -> Flow<InetSocketAddress?>,
    private val pair: suspend (InetSocketAddress?, String) -> Unit,
    private val connect: suspend () -> Unit,
    private val render: (PairingStage, Int?) -> Unit,
) {
    private var stage: PairingStage? = null
    private var endpoint: InetSocketAddress? = null
    private var discovery: Job? = null
    private var pairing: Job? = null

    fun start() {
        if (stage != null) return
        update(PairingStage.WAITING)
        discovery = scope.launch {
            try {
                discover().collect { address ->
                    if (stage?.acceptsCode != true || address == endpoint) return@collect
                    endpoint = address
                    update(if (address == null) PairingStage.WAITING else PairingStage.FOUND)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                update(PairingStage.DISCOVERY_UNAVAILABLE)
            }
        }
    }

    fun reply(code: String) {
        if (stage?.acceptsCode != true) return
        if (!code.matches(Regex("[0-9]{6}"))) {
            update(PairingStage.INVALID_CODE)
            return
        }
        val selected = endpoint
        update(PairingStage.PAIRING)
        discovery?.cancel()
        pairing = scope.launch {
            try {
                pair(selected, code)
                currentCoroutineContext().ensureActive()
                update(PairingStage.CONNECTING)
                connect()
                currentCoroutineContext().ensureActive()
                update(PairingStage.CONNECTED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                update(if (stage == PairingStage.CONNECTING) PairingStage.DISCONNECTED else PairingStage.FAILED)
            }
        }
    }

    fun cancel() {
        stage = null
        endpoint = null
        discovery?.cancel()
        pairing?.cancel()
    }

    private fun update(next: PairingStage) {
        stage = next
        render(next, endpoint?.port)
    }
}

/** Keeps a single actionable notification visible while Settings owns the pairing-code dialog. */
class WirelessAdbPairingNotification : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridge by inject<EmbeddedAdbDisplayBridge>()
    private var run: WirelessPairingRun? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PROMPT -> if (run == null) {
                val client = LocalAdbTlsClient(applicationContext)
                run = WirelessPairingRun(scope, client::pairingEndpoints, client::pairDiscovered, { bridge.start() }, ::render)
                run?.start()
            }
            REPLY -> {
                val active = run
                if (active == null) stopSelf() else {
                    val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString()?.trim().orEmpty()
                    active.reply(code)
                }
            }
            else -> cancelPrompt()
        }
        return START_NOT_STICKY
    }

    private fun render(stage: PairingStage, port: Int?) {
        val manager = requireNotNull(getSystemService(NotificationManager::class.java))
        val first = stage == PairingStage.WAITING && lastNotification == null
        val alert = stage == PairingStage.FOUND && port != announcedPort
        if (alert) announcedPort = port
        val notification = buildNotification(stage, port, alert)
        lastNotification = stage
        try {
            if (first) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                if (stage == PairingStage.CONNECTING) completedPairs.value++
                if (stage.finished) stopForeground(STOP_FOREGROUND_DETACH)
                manager.notify(ID, notification)
                if (stage.finished) stopSelf()
            }
        } catch (_: SecurityException) {
            cancelPrompt()
        }
    }

    internal fun buildNotification(stage: PairingStage, port: Int?, alert: Boolean): Notification {
        val text = if (stage == PairingStage.FOUND) getString(stage.message, port) else getString(stage.message)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(getString(R.string.vd_adb_section))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(
                this, ID, phoneUseDisplaySettingsIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setOnlyAlertOnce(!alert)
            .setGroup(CHANNEL)
            .setGroupAlertBehavior(if (alert) Notification.GROUP_ALERT_ALL else Notification.GROUP_ALERT_SUMMARY)
            .setAutoCancel(stage.finished)
        if (stage.acceptsCode) {
            val submit = actionIntent(REPLY, PendingIntent.FLAG_MUTABLE)
            val label = getString(R.string.vd_adb_code)
            val input = RemoteInput.Builder(CODE).setLabel(label).build()
            builder.addAction(Notification.Action.Builder(null, label, submit).addRemoteInput(input).build())
        }
        if (!stage.finished) {
            val cancel = actionIntent(CANCEL, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.vd_adb_cancel), cancel).build())
        }
        return builder.build()
    }

    private var announcedPort: Int? = null
    private var lastNotification: PairingStage? = null

    private fun actionIntent(action: String, mutability: Int): PendingIntent = PendingIntent.getService(
        this, ID, Intent(this, WirelessAdbPairingNotification::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or mutability,
    )

    private fun cancelPrompt() {
        run?.cancel()
        run = null
        lastNotification = null
        announcedPort = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int) = cancelPrompt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val completedPairs = MutableStateFlow(0)
        internal val pairRevision = completedPairs.asStateFlow()
        private const val CHANNEL = "wireless_adb_pairing"
        private const val ID = 49202
        private const val PROMPT = "wireless_adb.prompt"
        private const val REPLY = "wireless_adb.reply"
        private const val CANCEL = "wireless_adb.cancel"
        private const val CODE = "wireless_adb.code"

        fun show(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.vd_adb_section), NotificationManager.IMPORTANCE_HIGH))
            if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            context.startForegroundService(Intent(context, WirelessAdbPairingNotification::class.java).setAction(PROMPT))
            return true
        }
    }
}

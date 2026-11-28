package app.amber.core.automation.display

import android.content.Context
import android.os.IBinder
import app.amber.core.automation.display.adb.LocalAdbTlsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

internal class DisplayHelperBusyException : IllegalStateException("display_helper_session_busy")

internal data class RegisteredDisplayHelper(val binder: IBinder, val transport: Closeable)

/** The settings page and display sessions share one app-owned shell helper. */
class EmbeddedAdbDisplayBridge internal constructor(
    private val launch: suspend (Int?) -> RegisteredDisplayHelper,
) : DisplayBridge {
    constructor(context: Context) : this({ port ->
        val request = DisplayHelperBinderProvider.expect()
        var transport: Closeable? = null
        try {
            val connection = LocalAdbTlsClient(context.applicationContext).startHelper(request.token, port)
            transport = connection
            val binder = withTimeoutOrNull(10_000) { request.awaitBinder() }
                ?: error("display_helper_registration_timeout")
            RegisteredDisplayHelper(binder, connection).also { transport = null }
        } finally {
            request.close()
            transport?.close()
        }
    })

    private val startup = Mutex()
    private val lock = Any()
    private var stopCount = 0L
    private var current: Helper? = null
    private val connected = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = connected.asStateFlow()

    private inner class Helper(val registration: RegisteredDisplayHelper) : IBinder.DeathRecipient {
        val service = IAmberDisplayService.Stub.asInterface(registration.binder)
        var onDisconnect: ((String) -> Unit)? = null
        @Volatile var died = false
        override fun binderDied() {
            died = true
            retire(this, "display_helper_binder_died")
        }
    }

    suspend fun start(port: Int? = null) = withContext(Dispatchers.IO) {
        startup.withLock {
            val atStart = synchronized(lock) {
                if (current != null) return@withLock
                stopCount
            }
            val registration = launch(port)
            val helper = Helper(registration)
            try {
                registration.binder.linkToDeath(helper, 0)
                check(helper.service.uid() == AmberDisplayUserService.SHELL_UID) { "display_helper_uid_not_shell" }
                synchronized(lock) {
                    check(atStart == stopCount && !helper.died && registration.binder.isBinderAlive) {
                        "display_helper_disconnected_during_start"
                    }
                    current = helper
                    connected.value = true
                }
            } catch (failure: Throwable) {
                runCatching { registration.binder.unlinkToDeath(helper, 0) }
                registration.transport.close()
                throw failure
            }
        }
    }

    fun stop() {
        val helper = synchronized(lock) {
            stopCount++
            current
        }
        if (helper != null) retire(helper, "display_helper_stopped")
    }

    private fun retire(helper: Helper, reason: String) {
        val listener = synchronized(lock) {
            if (current !== helper) return
            current = null
            connected.value = false
            helper.onDisconnect.also { helper.onDisconnect = null }
        }
        try {
            listener?.invoke(reason)
        } finally {
            runCatching { helper.registration.binder.unlinkToDeath(helper, 0) }
            runCatching { helper.registration.transport.close() }
        }
    }

    override suspend fun connect(onDisconnected: (String) -> Unit): Result<ConnectedDisplayBridge> = try {
        start()
        val connection = synchronized(lock) {
            val helper = checkNotNull(current) { "display_helper_disconnected" }
            if (helper.onDisconnect != null) throw DisplayHelperBusyException()
            helper.onDisconnect = onDisconnected
            object : BinderDisplayConnection(helper.service) {
                override val transportName: String = "embedded_adb"

                override fun probeTransportAlive(): Boolean = !helper.died

                override fun interruptTransportForProbe(): Boolean {
                    // Leave the binder death recipient and session callback armed.
                    helper.registration.transport.close()
                    return true
                }

                override fun disconnect() {
                    synchronized(lock) {
                        if (helper.onDisconnect === onDisconnected) helper.onDisconnect = null
                    }
                }
            }
        }
        Result.success(connection)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}

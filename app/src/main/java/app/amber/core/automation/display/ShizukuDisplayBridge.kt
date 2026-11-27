package app.amber.core.automation.display

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.core.content.pm.PackageInfoCompat
import app.amber.agent.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

data class DisplayCapabilities(
    val apiLevel: Int,
    val uid: Int,
    val ownFocus: Boolean,
    val destroyContentOnRemoval: Boolean,
    val trusted: Boolean,
    val secondaryActivities: Boolean,
)

interface DisplayBridge {
    suspend fun connect(onDisconnected: (String) -> Unit): Result<ConnectedDisplayBridge>
}

interface ConnectedDisplayBridge {
    fun uid(): Int
    fun capabilities(): DisplayCapabilities
    fun createDisplay(
        runId: String,
        authorizedPackageName: String,
        generation: Long,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
    ): Int

    fun launchOnDisplay(generation: Long, displayId: Int, packageName: String): Int
    fun injectMotion(generation: Long, displayId: Int, observedPackageName: String, event: MotionEvent): Boolean
    fun injectKey(generation: Long, displayId: Int, observedPackageName: String, event: KeyEvent): Boolean
    fun release(generation: Long, reason: String)
    fun disconnect()
}

/**
 * App-process bridge to [AmberDisplayUserService] via Shizuku UserService.
 * Uses only public Shizuku APIs; never touches HiddenApiBypass in the app process.
 */
class ShizukuDisplayBridge(
    private val context: Context,
    private val bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
) : DisplayBridge {

    fun isShizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasShizukuPermission(): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return@runCatching false
        if (Shizuku.isPreV11()) return@runCatching false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizukuPermission(requestCode: Int): Boolean = runCatching {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return@runCatching false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return@runCatching true
        Shizuku.requestPermission(requestCode)
        true
    }.getOrDefault(false)

    override suspend fun connect(onDisconnected: (String) -> Unit): Result<ConnectedDisplayBridge> {
        if (!isShizukuRunning()) {
            return Result.failure(IllegalStateException("shizuku_not_running"))
        }
        val preV11 = runCatching { Shizuku.isPreV11() }.getOrDefault(true)
        if (preV11) {
            return Result.failure(IllegalStateException("shizuku_version_unsupported"))
        }
        if (!hasShizukuPermission()) {
            return Result.failure(IllegalStateException("shizuku_permission_denied"))
        }
        val shizukuUid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        if (shizukuUid != AmberDisplayUserService.SHELL_UID) {
            return Result.failure(IllegalStateException("shizuku_uid_not_shell_2000:$shizukuUid"))
        }

        val versionCode = resolveVersionCode(context)
        val args = Shizuku.UserServiceArgs(
            ComponentName(context, AmberDisplayUserService::class.java),
        )
            .daemon(false)
            .processNameSuffix(PROCESS_SUFFIX)
            .debuggable(BuildConfig.DEBUG)
            .version(versionCode)

        val deferred = CompletableDeferred<Pair<IBinder, IAmberDisplayService>>()
        val disconnectedNotified = AtomicBoolean(false)
        fun notifyDisconnected(reason: String) {
            if (disconnectedNotified.compareAndSet(false, true)) {
                onDisconnected(reason)
            }
        }

        val binderDeadListener = Shizuku.OnBinderDeadListener {
            notifyDisconnected("shizuku_binder_dead")
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null || !service.pingBinder()) {
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(IllegalStateException("user_service_invalid_binder"))
                    }
                    return
                }
                val proxy = IAmberDisplayService.Stub.asInterface(service)
                if (proxy == null) {
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(IllegalStateException("user_service_null_interface"))
                    }
                    return
                }
                if (!deferred.isCompleted) {
                    deferred.complete(service to proxy)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                notifyDisconnected("user_service_disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                notifyDisconnected("user_service_binding_died")
            }
        }

        return try {
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.bindUserService(args, connection)
            val (binder, service) = withTimeout(bindTimeoutMs) { deferred.await() }
            val deathRecipient = IBinder.DeathRecipient {
                notifyDisconnected("user_service_binder_died")
            }
            binder.linkToDeath(deathRecipient, 0)
            Result.success(
                BoundShizukuDisplayBridge(
                    args = args,
                    connection = connection,
                    binder = binder,
                    service = service,
                    binderDeadListener = binderDeadListener,
                    deathRecipient = deathRecipient,
                    onManualDisconnect = { disconnectedNotified.set(true) },
                ),
            )
        } catch (t: Throwable) {
            runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            Result.failure(t)
        }
    }

    private fun resolveVersionCode(context: Context): Int {
        val fromPkg = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info).toInt()
        }.getOrNull()
        if (fromPkg != null && fromPkg > 0) return fromPkg
        return BuildConfig.VERSION_CODE.toIntOrNull() ?: 1
    }

    private class BoundShizukuDisplayBridge(
        private val args: Shizuku.UserServiceArgs,
        private val connection: ServiceConnection,
        private val binder: IBinder,
        private val service: IAmberDisplayService,
        private val binderDeadListener: Shizuku.OnBinderDeadListener,
        private val deathRecipient: IBinder.DeathRecipient,
        private val onManualDisconnect: () -> Unit,
    ) : ConnectedDisplayBridge {

        private val disconnected = AtomicBoolean(false)

        override fun uid(): Int = runCatching { service.uid() }.getOrDefault(-1)

        override fun capabilities(): DisplayCapabilities {
            val bundle: Bundle = runCatching { service.capabilities() }.getOrNull() ?: Bundle.EMPTY
            return DisplayCapabilities(
                apiLevel = bundle.getInt(AmberDisplayUserService.CAP_API_LEVEL, 0),
                uid = bundle.getInt(AmberDisplayUserService.CAP_UID, -1),
                ownFocus = bundle.getBoolean(AmberDisplayUserService.CAP_OWN_FOCUS, false),
                destroyContentOnRemoval = bundle.getBoolean(AmberDisplayUserService.CAP_DESTROY_CONTENT_ON_REMOVAL, false),
                trusted = bundle.getBoolean(AmberDisplayUserService.CAP_TRUSTED, false),
                secondaryActivities = bundle.getBoolean(AmberDisplayUserService.CAP_SECONDARY_ACTIVITIES, false),
            )
        }

        override fun createDisplay(
            runId: String,
            authorizedPackageName: String,
            generation: Long,
            width: Int,
            height: Int,
            dpi: Int,
            surface: Surface,
        ): Int = runCatching {
            service.createDisplay(runId, authorizedPackageName, generation, width, height, dpi, surface)
        }.getOrElse {
            Log.w(TAG, "createDisplay IPC failed", it)
            AmberDisplayUserService.ERR_CREATE_FAILED
        }

        override fun launchOnDisplay(generation: Long, displayId: Int, packageName: String): Int =
            service.launchOnDisplay(generation, displayId, packageName)

        override fun injectMotion(generation: Long, displayId: Int, observedPackageName: String, event: MotionEvent): Boolean =
            service.injectMotion(generation, displayId, observedPackageName, event)

        override fun injectKey(generation: Long, displayId: Int, observedPackageName: String, event: KeyEvent): Boolean =
            service.injectKey(generation, displayId, observedPackageName, event)

        override fun release(generation: Long, reason: String) {
            runCatching { service.release(generation, reason) }
        }

        override fun disconnect() {
            if (!disconnected.compareAndSet(false, true)) return
            onManualDisconnect()
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    companion object {
        private const val TAG = "ShizukuDisplayBridge"
        private const val PROCESS_SUFFIX = "amber_display"
        private const val DEFAULT_BIND_TIMEOUT_MS = 8_000L
    }
}

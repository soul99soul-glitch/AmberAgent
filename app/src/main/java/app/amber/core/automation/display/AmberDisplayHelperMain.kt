package app.amber.core.automation.display

import android.app.ActivityManager
import android.content.AttributionSource
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.Looper
import android.system.Os
import android.util.Log
import androidx.annotation.Keep
import app.amber.agent.BuildConfig
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Entry point for the fixed, local ADB shell service. No command dispatch or listener socket. */
@Keep
object AmberDisplayHelperMain {
    private const val SHELL_PACKAGE = "com.android.shell"

    @JvmStatic
    fun main(args: Array<String>) {
        if (Os.getuid() != Process.SHELL_UID) exitProcess(1)
        try {
            require(args.isEmpty() && Build.VERSION.SDK_INT >= AmberDisplayUserService.MIN_API_LEVEL)
            val input = System.`in`.bufferedReader(Charsets.US_ASCII)
            val token = input.readLine()
            require(token != null && token.matches(Regex("[0-9a-f]{64}"))) { "Invalid display helper handshake" }

            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
            val system = activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as Context
            val service = AmberDisplayUserService(system.createPackageContext(SHELL_PACKAGE, 0))
            val host = registerWithHost(token, service)
            val hostDeath = IBinder.DeathRecipient { service.destroy() }
            host.linkToDeath(hostDeath, 0)
            thread(name = "display-helper-transport", isDaemon = true) {
                try {
                    input.read() // EOF or unexpected input ends the one-purpose transport.
                } finally {
                    service.destroy()
                }
            }
            try {
                Looper.loop()
            } finally {
                host.unlinkToDeath(hostDeath, 0)
                service.destroy()
            }
        } catch (e: Throwable) {
            Log.e("AmberDisplayHelper", "Display helper startup failed", e)
            exitProcess(1)
        }
    }

    /** Shell has no AMS-registered application thread; acquire the provider like Android's content CLI. */
    private fun registerWithHost(sessionToken: String, service: AmberDisplayUserService): IBinder {
        val authority = BuildConfig.APPLICATION_ID + ".displayhelper"
        val am = ActivityManager::class.java.getMethod("getService").invoke(null)
        val amClass = Class.forName("android.app.IActivityManager")
        val user = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
        val reference = Binder()
        val holder = checkNotNull(amClass.getMethod(
            "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
            IBinder::class.java, String::class.java,
        ).invoke(am, authority, user, reference, "AmberDisplayHelper")) { "Display helper provider unavailable" }
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
            val caller = AttributionSource.Builder(Os.getuid()).setPackageName(SHELL_PACKAGE).build()
            val extras = Bundle().apply { putBinder(DisplayHelperBinderProvider.HELPER_BINDER, service.asBinder()) }
            val reply = Class.forName("android.content.IContentProvider").getMethod(
                "call", AttributionSource::class.java, String::class.java, String::class.java,
                String::class.java, Bundle::class.java,
            ).invoke(provider, caller, authority, DisplayHelperBinderProvider.REGISTER_HELPER, sessionToken, extras) as? Bundle
            return checkNotNull(reply?.getBinder(DisplayHelperBinderProvider.HOST_BINDER)) { "Missing host lifetime binder" }
        } finally {
            amClass.getMethod("removeContentProviderExternalAsUser", String::class.java,
                IBinder::class.java, Int::class.javaPrimitiveType).invoke(am, authority, reference, user)
        }
    }
}

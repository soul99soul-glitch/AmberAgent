package app.amber.feature.ui.pages.chat

import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// This private protocol was verified against this firmware's oplus-framework.jar.
// Do not guess transaction numbers on another phone or after an OTA.
internal fun supportsPma110StreamingRate(model: String, display: String): Boolean =
    model == "PMA110" && display == "PMA110_17.0.0.104(CN01)"

internal class Pma110StreamingRate(private val service: IBinder, private val packageName: String) {
    private var active = false

    fun update(active: Boolean) {
        if (this.active == active) return
        this.active = active
        request(if (active) 3 else 0)
    }

    // Also clear a vote left by a killed process when a new chat screen is attached.
    fun release() {
        active = false
        request(0)
    }

    private fun request(rateId: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.oplus.screenmode.IOplusScreenMode")
            data.writeString(packageName)
            data.writeInt(rateId) // 3 = 120 Hz, 0 = remove this package's transient vote.
            if (!service.transact(12, data, reply, 0)) return
            reply.readException()
            Log.d("AmberStreamingRate", "vendor vote=$rateId accepted=${reply.readBoolean()}")
        } catch (e: Exception) {
            Log.w("AmberStreamingRate", "Vendor vote unavailable", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

@Composable
internal fun PreferPma110StreamingFrameRate(active: Boolean) {
    val packageName = LocalContext.current.packageName
    val controller = remember(packageName) {
        if (!supportsPma110StreamingRate(Build.MODEL, Build.DISPLAY)) null else {
            // No hidden-API exemption or permission bypass. If unavailable, keep SDK voting.
            runCatching {
                val service = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "oplusscreenmode") as? IBinder
                service?.let { Pma110StreamingRate(it, packageName) }
            }.onFailure { Log.w("AmberStreamingRate", "Vendor service unavailable", it) }.getOrNull()
        }
    } ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle, active) {
        controller.release()
        val observer = LifecycleEventObserver { _, _ ->
            controller.update(active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        controller.update(active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            controller.release()
        }
    }
}

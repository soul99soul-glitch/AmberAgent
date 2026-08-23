package app.amber.feature.tools

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.put
import app.amber.ai.core.Tool
import kotlin.coroutines.resume

fun createLocationCurrentTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "location_current",
    description = "Return the latest available device location from LocationManager after location permission is granted.",
    parameters = { obj() },
    execute = { input ->
        deps.trackSystemTool("location_current", "读取当前位置", "location_current", input) {
            val location = currentOrLatestLocation(context)
            textJson {
                if (location == null) {
                    put("available", false)
                    put("reason", "No recent location is available. Enable location providers or open a maps app once, then retry.")
                } else {
                    put("available", true)
                    put("provider", location.provider.orEmpty())
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy_meters", location.accuracy.toDouble())
                    put("time_epoch_ms", location.time)
                }
            }
        }
    }
)

@SuppressLint("MissingPermission")
private suspend fun currentOrLatestLocation(context: Context): Location? {
    val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
    latestLocation(locationManager)
        ?.takeIf { System.currentTimeMillis() - it.time < 5L * 60L * 1000L }
        ?.let { return it }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        for (provider in locationManager.getProviders(true)) {
            val current = withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine { continuation ->
                    val cancellationSignal = CancellationSignal()
                    continuation.invokeOnCancellation { cancellationSignal.cancel() }
                    locationManager.getCurrentLocation(
                        provider,
                        cancellationSignal,
                        context.mainExecutor
                    ) { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                }
            }
            if (current != null) return current
        }
    }

    return latestLocation(locationManager)
}

@SuppressLint("MissingPermission")
private fun latestLocation(locationManager: LocationManager): Location? {
    return locationManager.getProviders(true)
        .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
        .maxWithOrNull(compareBy<Location> { it.time }.thenByDescending { -it.accuracy })
}

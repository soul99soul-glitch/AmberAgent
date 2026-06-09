package app.amber.feature.tools

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.Tool

fun createBatteryStatusTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "battery_status",
    description = "Read device battery level, charging state, and power-save mode.",
    parameters = { obj() },
    execute = { input ->
        deps.trackSystemTool("battery_status", "读取电池状态", "apps", input) {
            textJson { put("battery", batteryStatusJson(context)) }
        }
    }
)

fun createNetworkStatusTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "network_status",
    description = "Read coarse connectivity status, transport type, and VPN/roaming hints.",
    parameters = { obj() },
    execute = { input ->
        deps.trackSystemTool("network_status", "读取网络状态", "apps", input) {
            textJson { put("network", networkStatusJson(context)) }
        }
    }
)

fun createWifiStatusTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "wifi_status",
    description = "Read Wi-Fi enabled state and redacted current SSID/IP if available. Does not scan nearby Wi-Fi.",
    parameters = { obj() },
    execute = { input ->
        deps.trackSystemTool("wifi_status", "读取 Wi-Fi 状态", "apps", input) {
            textJson { put("wifi", wifiStatusJson(context)) }
        }
    }
)

fun createDeviceInfoTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "device_info",
    description = "Read device brand, model, Android version, ABI, and screen metrics.",
    parameters = { obj() },
    execute = { input ->
        deps.trackSystemTool("device_info", "读取设备信息", "apps", input) {
            textJson { put("device", deviceInfoJson(context)) }
        }
    }
)

private fun batteryStatusJson(context: Context): JsonObject = buildJsonObject {
    val bm = context.getSystemService(BatteryManager::class.java)
    val pm = context.getSystemService(PowerManager::class.java)
    val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    put("level_percent", level)
    put("power_save_mode", pm?.isPowerSaveMode == true)
    put("charging", runCatching {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false))
}

private fun networkStatusJson(context: Context): JsonObject = buildJsonObject {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val network = cm?.activeNetwork
    val caps = cm?.getNetworkCapabilities(network)
    put("connected", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
    put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    put("vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
    put("transport", buildJsonArray {
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("bluetooth")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
    })
}

private fun wifiStatusJson(context: Context): JsonObject = buildJsonObject {
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    put("enabled", wifi?.isWifiEnabled == true)
    @Suppress("DEPRECATION")
    val info = runCatching { wifi?.connectionInfo }.getOrNull()
    val ssid = info?.ssid?.removeSurrounding("\"").orEmpty()
    put("ssid_redacted", if (ssid.isBlank() || ssid == WifiManager.UNKNOWN_SSID) "" else redactMiddle(ssid))
    @Suppress("DEPRECATION")
    put("ip_address_int", info?.ipAddress ?: 0)
}

private fun deviceInfoJson(context: Context): JsonObject = buildJsonObject {
    val metrics = context.resources.displayMetrics
    val uiMode = context.getSystemService(UiModeManager::class.java)
    put("brand", Build.BRAND.orEmpty())
    put("manufacturer", Build.MANUFACTURER.orEmpty())
    put("model", Build.MODEL.orEmpty())
    put("device", Build.DEVICE.orEmpty())
    put("android_sdk", Build.VERSION.SDK_INT)
    put("android_release", Build.VERSION.RELEASE.orEmpty())
    put("abis", buildJsonArray { Build.SUPPORTED_ABIS.forEach { add(it) } })
    put("screen_width_px", metrics.widthPixels)
    put("screen_height_px", metrics.heightPixels)
    put("density", metrics.density.toDouble())
    put("ui_mode_type", uiMode?.currentModeType ?: 0)
}

private fun redactMiddle(value: String): String {
    if (value.length <= 3) return "***"
    return value.take(2) + "***" + value.takeLast(1)
}

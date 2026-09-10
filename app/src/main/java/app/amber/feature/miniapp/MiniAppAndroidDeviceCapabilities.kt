package app.amber.feature.miniapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/**
 * P4 W10/W11: per-runner Android native system capability owner, mirroring
 * iOS `IOSMiniAppDeviceCapabilities`. It executes the real system calls only —
 * permission/grant/confirmation/audit stay in the protocol layer.
 *
 * `share` launches through a runner-injected fire-and-forget callback and the
 * runner reports the ActivityResult back via [completeShare]; `completed=true`
 * means the system hand-off finished, not that the target app delivered.
 *
 * Screen leases (brightness/keep-awake) only touch the runner's Window and are
 * released on suspend/close while this owner still owns the current value, so
 * an old runner cannot overwrite a newer owner's state.
 */
class MiniAppAndroidDeviceCapabilities(
    context: Context,
    private val activityProvider: () -> Activity?,
    private val speechEngine: MiniAppSpeechEngine,
    private val shareLauncher: suspend (Intent) -> Unit,
    private val openLauncher: suspend (Intent) -> Boolean,
    private val foregroundProvider: () -> Boolean = { true },
) : MiniAppSystemCapabilityHandler {
    private val appContext = context.applicationContext
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pendingShare: CompletableDeferred<Boolean>? = null

    // Screen lease state, only touched on the main thread.
    private var originalBrightness: Float? = null
    private var lastSetBrightness: Float? = null
    private var originalKeepAwake: Boolean? = null
    private var lastKeepAwake: Boolean = false

    override val supportedMethods: Set<String> = setOf(
        "haptics.impact", "haptics.notification", "haptics.selection",
        "device.getInfo", "device.getBattery", "qrcode.generate",
        "screen.getBrightness", "screen.setBrightness", "screen.setKeepAwake",
        "speech.getVoices", "speech.speak", "speech.stop", "speech.pause", "speech.resume",
        "share", "openURL",
    )

    override suspend fun dispatch(method: String, params: JsonObject): JsonElement {
        if (closed.get()) throw MiniAppBridgeException("runner_closed", "MiniApp runner is closed")
        return when (method) {
            "haptics.impact" -> {
                val style = params.stringOr("style") ?: "medium"
                if (style !in HAPTIC_IMPACT_STYLES) {
                    throw MiniAppValidationException("style must be light, medium, heavy, soft, or rigid")
                }
                val intensity = params.floatOr("intensity") ?: 1f
                if (intensity !in 0f..1f) throw MiniAppValidationException("intensity must be in 0...1")
                requireForeground()
                vibrate(impactEffect(style, intensity))
                ok()
            }

            "haptics.notification" -> {
                val type = params.stringOr("type") ?: "success"
                if (type !in setOf("success", "warning", "error")) {
                    throw MiniAppValidationException("type must be success, warning, or error")
                }
                requireForeground()
                vibrate(notificationEffect(type))
                ok()
            }

            "haptics.selection" -> {
                requireForeground()
                vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
                ok()
            }

            "device.getInfo" -> deviceInfo()
            "device.getBattery" -> batteryInfo()
            "qrcode.generate" -> generateQrCode(
                text = params.stringOr("text") ?: throw MiniAppValidationException("Missing parameter: text"),
                size = params.intOr("size") ?: 256,
            )

            "screen.getBrightness" -> withContext(Dispatchers.Main) {
                // Window brightness is an override (-1 = follow system); the
                // MiniApp contract reports the effective 0..1 brightness, so
                // read the system setting when this window has no override.
                val window = requireWindow()
                val override = window.attributes.screenBrightness
                val effective = if (override >= 0f && override <= 1f) {
                    override
                } else {
                    runCatching {
                        android.provider.Settings.System.getInt(
                            appContext.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS,
                        ) / 255f
                    }.getOrDefault(0.5f)
                }
                JsonPrimitive(effective.coerceIn(0f, 1f))
            }

            "screen.setBrightness" -> withContext(Dispatchers.Main) {
                val brightness = params.floatOr("brightness")
                    ?: throw MiniAppValidationException("Missing parameter: brightness")
                if (brightness !in 0f..1f) throw MiniAppValidationException("brightness must be in 0...1")
                val window = requireWindow()
                claimScreenLease()
                if (originalBrightness == null) {
                    originalBrightness = window.attributes.screenBrightness
                }
                window.attributes = window.attributes.apply { screenBrightness = brightness }
                lastSetBrightness = brightness
                ok()
            }

            "screen.setKeepAwake" -> withContext(Dispatchers.Main) {
                val enabled = params.booleanOr("enabled")
                    ?: throw MiniAppValidationException("Missing parameter: enabled")
                val window = requireWindow()
                claimScreenLease()
                if (originalKeepAwake == null) {
                    originalKeepAwake = window.attributes.flags and
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                }
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                lastKeepAwake = enabled
                ok()
            }

            "speech.getVoices", "speech.speak", "speech.stop", "speech.pause", "speech.resume" -> {
                // Starting or resuming speech needs a live runner; stop/pause
                // stay available as cleanup paths (iOS requireForeground parity).
                if (method == "speech.speak" || method == "speech.resume") requireForeground()
                speechEngine.dispatch(method, params)
            }

            "share" -> share(params)
            "openURL" -> openUrl(params)

            else -> throw MiniAppValidationException("Unsupported system method: $method")
        }
    }

    /** Runner reports the share ActivityResult: chooser completed / cancelled. */
    fun completeShare(completed: Boolean, error: Throwable? = null) {
        val pending = pendingShare ?: return
        pendingShare = null
        when (error) {
            null -> pending.complete(completed)
            else -> pending.completeExceptionally(error)
        }
    }

    /**
     * Releases transient resources while retaining this owner for a foreground
     * reload. A pending share sheet keeps covering the runner window, so its
     * leases (and any speech) stay owned until the sheet returns.
     */
    fun suspendRunner() {
        if (pendingShare != null) return
        speechEngine.suspendForBackground()
        if (closed.get()) return
        runOnMain { releaseScreenLeases() }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        speechEngine.close()
        runOnMain { releaseScreenLeases() }
        pendingShare?.let { pending ->
            pendingShare = null
            pending.completeExceptionally(MiniAppBridgeException("runner_closed", "MiniApp runner is closed"))
        }
    }

    // ------------------------------------------------------------------ device

    private fun deviceInfo(): JsonElement = buildJsonObject {
        put("systemVersion", Build.VERSION.RELEASE ?: "")
        put("deviceType", deviceTypeName())
        put("language", Locale.getDefault().toLanguageTag())
        put("timezone", TimeZone.getDefault().id)
        put("lowPowerMode", isPowerSaveMode())
        put("accessibility", buildJsonObject {
            put("accessibilityEnabled", secureSetting("accessibility_enabled") == "1")
            put("touchExplorationEnabled", secureSetting("touch_exploration_enabled") == "1")
            put("fontScale", appContext.resources.configuration.fontScale)
        })
    }

    private fun batteryInfo(): JsonElement {
        val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val levelPercent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val level: JsonElement = if (levelPercent in 0..100) {
            JsonPrimitive(levelPercent / 100f)
        } else {
            JsonNull
        }
        val status = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
        val state = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            -> "unplugged"
            else -> "unknown"
        }
        return buildJsonObject {
            put("level", level)
            put("state", state)
        }
    }

    private fun deviceTypeName(): String {
        val uiMode = appContext.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return when (uiMode) {
            Configuration.UI_MODE_TYPE_NORMAL -> "phone"
            Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
            else -> "unknown"
        }
    }

    private fun isPowerSaveMode(): Boolean =
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    private fun secureSetting(key: String): String? =
        Settings.Secure.getString(appContext.contentResolver, key)

    // ------------------------------------------------------------------ haptics

    private fun vibrate(effect: VibrationEffect) {
        val engine = vibrator()
        if (!engine.hasVibrator()) {
            throw MiniAppValidationException("Haptics are unavailable on this device")
        }
        engine.vibrate(effect)
    }

    private fun vibrator(): Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private fun impactEffect(style: String, intensity: Float): VibrationEffect {
        val amplitude = (intensity * 255).toInt().coerceIn(1, 255)
        return when (style) {
            "light" -> VibrationEffect.createOneShot(30, amplitude)
            "medium" -> VibrationEffect.createOneShot(45, amplitude)
            "heavy" -> VibrationEffect.createOneShot(80, amplitude)
            "soft" -> VibrationEffect.createWaveform(longArrayOf(0, 40), intArrayOf(0, amplitude), -1)
            else -> VibrationEffect.createWaveform(longArrayOf(0, 60), intArrayOf(0, amplitude), -1)
        }
    }

    private fun notificationEffect(type: String): VibrationEffect = when (type) {
        "success" -> VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), intArrayOf(0, 160, 0, 160), -1)
        "warning" -> VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), intArrayOf(0, 200, 0, 200), -1)
        else -> VibrationEffect.createWaveform(longArrayOf(0, 90, 70, 90), intArrayOf(0, 255, 0, 255), -1)
    }

    // ------------------------------------------------------------------ screen

    /**
     * Single-activity app: one active screen-lease owner per process. A new
     * runner claiming the lease first releases the previous owner's value, so
     * a stale runner can never restore over a newer owner's state (iOS
     * transient resource ownership parity). Lease state is only touched on
     * the main thread.
     */
    private fun claimScreenLease() {
        val previous = screenLeaseOwner
        if (previous != null && previous !== this) {
            previous.releaseScreenLeases()
        }
        screenLeaseOwner = this
    }

    /**
     * Restore only what this owner still owns: if another owner changed the
     * brightness after us, leave it alone. The keep-awake flag is restored
     * whenever the current flag still equals this owner's last value — in
     * either direction — so `setKeepAwake(false)` over an originally-true
     * flag is also restored.
     */
    private fun releaseScreenLeases() {
        if (screenLeaseOwner === this) {
            screenLeaseOwner = null
        }
        val window = activityProvider()?.window
        if (window != null) {
            originalBrightness?.let { original ->
                val ours = lastSetBrightness
                if (ours == null || window.attributes.screenBrightness == ours) {
                    window.attributes = window.attributes.apply { screenBrightness = original }
                }
            }
            originalKeepAwake?.let { original ->
                val flagSet = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                if (flagSet == lastKeepAwake) {
                    if (original) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
        originalBrightness = null
        lastSetBrightness = null
        originalKeepAwake = null
        lastKeepAwake = false
    }

    private fun requireWindow(): android.view.Window {
        val activity = activityProvider()
        if (activity == null || activity.isFinishing || activity.isDestroyed || !foregroundProvider()) {
            throw MiniAppBridgeException("not_foreground", "MiniApp runner is not in the foreground")
        }
        return activity.window
    }

    private fun requireForeground() {
        val activity = activityProvider()
        if (activity == null || activity.isFinishing || activity.isDestroyed || !foregroundProvider()) {
            throw MiniAppBridgeException("not_foreground", "MiniApp runner is not in the foreground")
        }
    }

    // ------------------------------------------------------------------ share / openURL

    private suspend fun share(params: JsonObject): JsonElement {
        if (pendingShare != null) {
            throw MiniAppBridgeException("busy", "A MiniApp share sheet is already presented.")
        }
        val text = params.stringOr("text")
        val url = params.stringOr("url")
        if (text.isNullOrEmpty() && url.isNullOrEmpty()) {
            throw MiniAppValidationException("share needs text or url")
        }
        if (text != null && text.length > 20_000) {
            throw MiniAppValidationException("text must contain at most 20000 characters")
        }
        val validatedUrl = url?.takeIf { it.isNotEmpty() }?.let { MiniAppOpenUrlValidator.validate(it).url }
        requireForeground()
        val shareText = buildString {
            text?.takeIf { it.isNotEmpty() }?.let { append(it) }
            validatedUrl?.let {
                if (isNotEmpty()) append("\n")
                append(it)
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        val pending = CompletableDeferred<Boolean>()
        pendingShare = pending
        try {
            shareLauncher(Intent.createChooser(intent, null))
            // completed=true only reports the chooser hand-off finished; it
            // does not prove the receiving app actually delivered the content.
            val completed = pending.await()
            return buildJsonObject { put("completed", completed) }
        } finally {
            pendingShare = null
        }
    }

    private suspend fun openUrl(params: JsonObject): JsonElement {
        val raw = params.stringOr("url") ?: throw MiniAppValidationException("Missing parameter: url")
        val url = MiniAppOpenUrlValidator.validate(raw).url
        requireForeground()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = openLauncher(intent)
        return buildJsonObject {
            put("opened", opened)
            put("url", url)
        }
    }

    // ------------------------------------------------------------------ qr

    private fun generateQrCode(text: String, size: Int): JsonElement {
        if (text.isEmpty() || text.encodeToByteArray().size > 1_024) {
            throw MiniAppValidationException("text must contain 1...1024 UTF-8 bytes")
        }
        if (size !in 128..1_024) {
            throw MiniAppValidationException("size must be in 128...1024")
        }
        val matrix = runCatching {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                1,
                1,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"),
            )
        }.getOrElse { throw MiniAppValidationException("QR code generation failed") }
        val moduleCount = matrix.width
        val unitCount = moduleCount + QUIET_ZONE_MODULES * 2
        val moduleScale = (size / unitCount).coerceAtLeast(1)
        val canvasSide = maxOf(size, unitCount * moduleScale)
        val codeSide = moduleCount * moduleScale
        val margin = (canvasSide - codeSide) / 2
        val bitmap = Bitmap.createBitmap(canvasSide, canvasSide, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            for (x in 0 until moduleCount) {
                for (y in 0 until moduleCount) {
                    if (matrix[x, y]) {
                        canvas.drawRect(
                            (margin + x * moduleScale).toFloat(),
                            (margin + y * moduleScale).toFloat(),
                            (margin + (x + 1) * moduleScale).toFloat(),
                            (margin + (y + 1) * moduleScale).toFloat(),
                            paint,
                        )
                    }
                }
            }
            val png = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw MiniAppValidationException("QR code image encoding failed")
                }
                output.toByteArray()
            }
            return buildJsonObject {
                put("dataURL", "data:image/png;base64," + Base64.getEncoder().encodeToString(png))
                put("width", canvasSide)
                put("height", canvasSide)
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun ok(): JsonElement = buildJsonObject { put("ok", true) }

    /** Runs immediately on the main thread, otherwise posts; never blocks main. */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    private fun JsonObject.stringOr(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.floatOr(key: String): Float? = this[key]?.jsonPrimitive?.floatOrNull

    private fun JsonObject.intOr(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.booleanOr(key: String): Boolean? {
        val primitive = this[key]?.jsonPrimitive ?: return null
        return when {
            primitive is kotlinx.serialization.json.JsonNull -> null
            primitive.contentOrNull?.equals("true", ignoreCase = true) == true -> true
            primitive.contentOrNull?.equals("false", ignoreCase = true) == true -> false
            primitive.intOrNull == 1 -> true
            primitive.intOrNull == 0 -> false
            else -> null
        }
    }

    private companion object {
        const val QUIET_ZONE_MODULES = 4
        val HAPTIC_IMPACT_STYLES = setOf("light", "medium", "heavy", "soft", "rigid")

        /** Active screen-lease owner; guarded by the main thread. */
        @Volatile
        private var screenLeaseOwner: MiniAppAndroidDeviceCapabilities? = null
    }
}

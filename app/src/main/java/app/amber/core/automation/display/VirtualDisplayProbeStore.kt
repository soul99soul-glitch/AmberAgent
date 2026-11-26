package app.amber.core.automation.display

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.amber.agent.BuildConfig

data class IsolationProbeResult(
    val passed: Boolean,
    val mainScreenInjectedEvents: Int,
    val targetReceivedEvents: Int,
    val textReadBackOk: Boolean,
    val frameMarkerOk: Boolean,
    val focusStable: Boolean,
    val residualAfterDisconnect: Int,
    val leakedToDefaultOnDestroy: Boolean,
    val detail: String,
) {
    fun meetsAllStrictCriteria(requiredTaps: Int = REQUIRED_PROBE_TAPS): Boolean =
        passed &&
            mainScreenInjectedEvents == 0 &&
            targetReceivedEvents >= requiredTaps &&
            textReadBackOk &&
            frameMarkerOk &&
            focusStable &&
            residualAfterDisconnect == 0 &&
            !leakedToDefaultOnDestroy

    companion object {
        const val REQUIRED_PROBE_TAPS = 20
    }
}

internal interface ProbeKvStore {
    fun getString(key: String): String?
    fun getLong(key: String, defaultValue: Long): Long
    fun getInt(key: String, defaultValue: Int): Int
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun write(values: Map<String, Any>)
    fun clear()
}

internal class SharedPrefsProbeKvStore(
    private val prefs: SharedPreferences,
) : ProbeKvStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getLong(key: String, defaultValue: Long): Long = prefs.getLong(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)
    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun write(values: Map<String, Any>) {
        prefs.edit().apply {
            values.forEach { (k, v) ->
                when (v) {
                    is String -> putString(k, v)
                    is Long -> putLong(k, v)
                    is Int -> putInt(k, v)
                    is Boolean -> putBoolean(k, v)
                }
            }
        }.commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }
}

/**
 * Persists the device-local dual-display isolation probe verdict keyed to
 * `Build.FINGERPRINT + versionCode`.
 *
 * Any OS update (fingerprint change) or App update (versionCode change) automatically
 * invalidates the stored verification and returns [VirtualDisplayCompatibility.UNVERIFIED].
 */
class VirtualDisplayProbeStore internal constructor(
    private val kv: ProbeKvStore,
    private val fingerprintProvider: () -> String,
    private val versionCodeProvider: () -> Long,
    private val environmentSupportedProvider: () -> Boolean,
) {

    constructor(context: Context) : this(
        kv = SharedPrefsProbeKvStore(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
        fingerprintProvider = { Build.FINGERPRINT.orEmpty() },
        versionCodeProvider = { resolveAppVersionCode(context) },
        environmentSupportedProvider = { isEnvironmentSupported(context) },
    )

    @Synchronized
    fun compatibility(): VirtualDisplayCompatibility {
        if (!environmentSupportedProvider()) {
            return VirtualDisplayCompatibility.UNSUPPORTED
        }
        if (!kv.contains(KEY_FINGERPRINT) || !kv.contains(KEY_VERSION_CODE)) {
            return VirtualDisplayCompatibility.UNVERIFIED
        }
        val storedFingerprint = kv.getString(KEY_FINGERPRINT).orEmpty()
        val storedVersionCode = kv.getLong(KEY_VERSION_CODE, -1L)
        if (storedFingerprint != fingerprintProvider() || storedVersionCode != versionCodeProvider()) {
            return VirtualDisplayCompatibility.UNVERIFIED
        }
        val result = readStoredResultLocked() ?: return VirtualDisplayCompatibility.UNVERIFIED
        return if (result.meetsAllStrictCriteria()) {
            VirtualDisplayCompatibility.VERIFIED
        } else {
            VirtualDisplayCompatibility.FAILED
        }
    }

    @Synchronized
    fun record(result: IsolationProbeResult) {
        val effectivePassed = result.meetsAllStrictCriteria()
        kv.write(
            mapOf(
                KEY_FINGERPRINT to fingerprintProvider(),
                KEY_VERSION_CODE to versionCodeProvider(),
                KEY_PASSED to effectivePassed,
                KEY_MAIN_INJECTED to result.mainScreenInjectedEvents,
                KEY_TARGET_RECEIVED to result.targetReceivedEvents,
                KEY_TEXT_READBACK_OK to result.textReadBackOk,
                KEY_FRAME_MARKER_OK to result.frameMarkerOk,
                KEY_FOCUS_STABLE to result.focusStable,
                KEY_RESIDUAL_AFTER_DISCONNECT to result.residualAfterDisconnect,
                KEY_LEAKED_TO_DEFAULT to result.leakedToDefaultOnDestroy,
                KEY_DETAIL to result.detail,
            ),
        )
    }

    @Synchronized
    fun lastResult(): IsolationProbeResult? {
        if (!kv.contains(KEY_FINGERPRINT) || !kv.contains(KEY_VERSION_CODE)) return null
        val storedFingerprint = kv.getString(KEY_FINGERPRINT).orEmpty()
        val storedVersionCode = kv.getLong(KEY_VERSION_CODE, -1L)
        if (storedFingerprint != fingerprintProvider() || storedVersionCode != versionCodeProvider()) {
            return null
        }
        return readStoredResultLocked()
    }

    @Synchronized
    fun clear() {
        kv.clear()
    }

    private fun readStoredResultLocked(): IsolationProbeResult? {
        if (!kv.contains(KEY_PASSED)) return null
        return IsolationProbeResult(
            passed = kv.getBoolean(KEY_PASSED, false),
            mainScreenInjectedEvents = kv.getInt(KEY_MAIN_INJECTED, -1),
            targetReceivedEvents = kv.getInt(KEY_TARGET_RECEIVED, 0),
            textReadBackOk = kv.getBoolean(KEY_TEXT_READBACK_OK, false),
            frameMarkerOk = kv.getBoolean(KEY_FRAME_MARKER_OK, false),
            focusStable = kv.getBoolean(KEY_FOCUS_STABLE, false),
            residualAfterDisconnect = kv.getInt(KEY_RESIDUAL_AFTER_DISCONNECT, -1),
            leakedToDefaultOnDestroy = kv.getBoolean(KEY_LEAKED_TO_DEFAULT, true),
            detail = kv.getString(KEY_DETAIL).orEmpty(),
        )
    }

    companion object {
        private const val PREFS_NAME = "virtual_display_probe"
        private const val KEY_FINGERPRINT = "build_fingerprint"
        private const val KEY_VERSION_CODE = "app_version_code"
        private const val KEY_PASSED = "passed"
        private const val KEY_MAIN_INJECTED = "main_screen_injected_events"
        private const val KEY_TARGET_RECEIVED = "target_received_events"
        private const val KEY_TEXT_READBACK_OK = "text_read_back_ok"
        private const val KEY_FRAME_MARKER_OK = "frame_marker_ok"
        private const val KEY_FOCUS_STABLE = "focus_stable"
        private const val KEY_RESIDUAL_AFTER_DISCONNECT = "residual_after_disconnect"
        private const val KEY_LEAKED_TO_DEFAULT = "leaked_to_default_on_destroy"
        private const val KEY_DETAIL = "detail"

        internal fun resolveAppVersionCode(context: Context): Long {
            val fromPkg = runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                PackageInfoCompat.getLongVersionCode(info)
            }.getOrNull()
            if (fromPkg != null && fromPkg > 0L) return fromPkg
            return BuildConfig.VERSION_CODE.toLongOrNull() ?: 1L
        }

        internal fun isEnvironmentSupported(context: Context): Boolean {
            if (!BuildConfig.PHONE_USE_VIRTUAL_DISPLAY_ALLOWED) return false
            if (Build.VERSION.SDK_INT < AmberDisplayUserService.MIN_API_LEVEL) return false
            return runCatching {
                context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,
                )
            }.getOrDefault(false)
        }
    }
}

package app.amber.document.nativebridge

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Gates the retained XLSX/calamine native reader. DOCX/PPTX/EPUB are JVM-only. */
object OfficeNativeSwitch {
    const val COMPONENT_NAME: String = "office"
    private const val TAG = "OfficeNativeSwitch"

    interface Config {
        fun enabled(): Boolean
        fun onLoadFailure(error: Throwable)
        fun onNativePanic(stage: String, error: Throwable?)
    }

    object DisabledConfig : Config {
        override fun enabled(): Boolean = false
        override fun onLoadFailure(error: Throwable) = Unit
        override fun onNativePanic(stage: String, error: Throwable?) = Unit
    }

    @Volatile
    var config: Config = DisabledConfig

    private val loadFailureReported = AtomicBoolean(false)
    private val firstSuccessLogged = AtomicBoolean(false)

    fun parseXlsxOrNull(file: File): String? {
        val cfg = config
        if (!cfg.enabled()) return null
        if (!OfficeParserNative.available) {
            if (loadFailureReported.compareAndSet(false, true)) {
                OfficeParserNative.lastLoadError()?.let(cfg::onLoadFailure)
            }
            return null
        }
        val native = try {
            OfficeParserNative.parseXlsx(file)
        } catch (error: Throwable) {
            cfg.onNativePanic("xlsx", error)
            return null
        }
        val output = when (native) {
            is OfficeParserNative.Result.Success -> native.output
            OfficeParserNative.Result.NativeUnavailable -> {
                cfg.onNativePanic("xlsx", null)
                return null
            }
        }
        if (firstSuccessLogged.compareAndSet(false, true)) {
            Log.i(TAG, "native xlsx ok: len=${output.length}")
        }
        return output
    }
}

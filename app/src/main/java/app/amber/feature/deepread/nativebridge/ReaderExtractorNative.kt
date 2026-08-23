package app.amber.feature.deepread.nativebridge

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal object ReaderExtractorNative {
    private const val TAG = "ReaderExtractorNative"
    private const val LIB_NAME = "reader_extractor"
    const val COMPONENT_NAME: String = "reader_extractor"

    interface Config {
        fun enabled(): Boolean
        fun onLoadFailure(error: Throwable)
        fun onNativePanic(stage: String, error: Throwable?)
    }

    object DisabledConfig : Config {
        override fun enabled(): Boolean = false
        override fun onLoadFailure(error: Throwable) {}
        override fun onNativePanic(stage: String, error: Throwable?) {}
    }

    @Volatile
    var config: Config = DisabledConfig

    private val loaded = AtomicBoolean(false)
    private val loadFailureReported = AtomicBoolean(false)
    @Volatile private var loadAttempted = false

    fun extractOrNull(html: String, baseUrl: String): ExtractedArticle? {
        val cfg = config
        if (!cfg.enabled() || !checkAvailability(cfg)) return null
        return try {
            extract(html, baseUrl)
        } catch (t: Throwable) {
            Log.w(TAG, "native extract threw — falling back to JVM", t)
            cfg.onNativePanic("extract", t)
            null
        }
    }

    private fun checkAvailability(cfg: Config): Boolean {
        ensureLoaded(cfg)
        return loaded.get()
    }

    private fun ensureLoaded(cfg: Config) {
        if (loaded.get() || loadAttempted) return
        synchronized(this) {
            if (loaded.get() || loadAttempted) return
            loadAttempted = true
            try {
                System.loadLibrary(LIB_NAME)
                loaded.set(true)
                Log.i(TAG, "loaded native library: $LIB_NAME")
            } catch (t: Throwable) {
                Log.w(TAG, "failed to load native library $LIB_NAME — will fall back to JVM", t)
                if (loadFailureReported.compareAndSet(false, true)) {
                    cfg.onLoadFailure(t)
                }
            }
        }
    }

    @JvmStatic
    external fun extract(html: String, baseUrl: String): ExtractedArticle?
}

data class ExtractedArticle(
    @JvmField val title: String,
    @JvmField val contentHtml: String,
    @JvmField val contentText: String,
    @JvmField val sectionCount: Int,
)

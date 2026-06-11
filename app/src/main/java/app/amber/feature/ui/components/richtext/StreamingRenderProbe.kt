package app.amber.feature.ui.components.richtext

import android.util.Log
import app.amber.agent.BuildConfig

private const val StreamingRenderProbeTag = "AmberStreamingRender"
private const val StreamingRenderProbeCapacity = 240

/**
 * Debug-only ring buffer for streaming render cadence. It records cheaply in
 * memory and only dumps when the log tag is enabled, so normal builds do not
 * pay per-frame logcat cost.
 */
internal object StreamingRenderProbe {
    private val lock = Any()
    private val events = ArrayDeque<String>(StreamingRenderProbeCapacity)

    fun isEnabled(): Boolean =
        BuildConfig.DEBUG && Log.isLoggable(StreamingRenderProbeTag, Log.DEBUG)

    fun record(event: () -> String) {
        if (!isEnabled()) return
        push("${System.currentTimeMillis()} ${event()}")
    }

    fun dump(reason: String) {
        if (!isEnabled()) return
        val snapshot = synchronized(lock) {
            if (events.isEmpty()) return
            events.joinToString(separator = "\n")
        }
        Log.d(StreamingRenderProbeTag, "dump reason=$reason\n$snapshot")
    }

    fun clear() {
        synchronized(lock) {
            events.clear()
        }
    }

    fun snapshot(): List<String> = synchronized(lock) {
        events.toList()
    }

    /** Debug overlay: display-buffer lag (target − visible chars). */
    @Volatile
    var displayBacklog: Int = 0
        internal set

    /** Debug overlay: AST re-parse ticks during streaming. */
    @Volatile
    var parseTickCount: Int = 0
        internal set

    /** Debug overlay: unparsed live suffix length on the active block. */
    @Volatile
    var liveSuffixLength: Int = 0
        internal set

    /** Debug overlay: block/table motion keys claimed on the active tail. */
    @Volatile
    var motionClaimCount: Int = 0
        internal set

    /** Clears per-session overlay counters when streaming fully settles. */
    fun resetStreamingDiagnostics() {
        displayBacklog = 0
        liveSuffixLength = 0
        parseTickCount = 0
    }

    private var instanceCounter = 0

    /**
     * Per-instance id for display-buffer probes. Multiple display buffers run
     * concurrently (reasoning block, text block, …) and interleave in the ring
     * buffer; without an id their visible/target sequences are inseparable.
     */
    fun nextInstanceId(): Int = synchronized(lock) { ++instanceCounter }

    fun push(event: String) {
        synchronized(lock) {
            while (events.size >= StreamingRenderProbeCapacity) {
                events.removeFirst()
            }
            events.addLast(event)
        }
    }
}

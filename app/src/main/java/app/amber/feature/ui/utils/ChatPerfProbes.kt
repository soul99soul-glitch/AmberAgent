package app.amber.feature.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * Composition-local probes used only while `CHAT_PERF_TRACE` is enabled and
 * only under the measured target message. They report content shape (never
 * user text): Markdown placeholder state and image settled state.
 */
internal val LocalMarkdownReadinessProbe = staticCompositionLocalOf<((token: Int, pending: Boolean) -> Unit)?> { null }
internal val LocalImageReadinessProbe = staticCompositionLocalOf<((token: Int, settled: Boolean) -> Unit)?> { null }
internal val LocalMarkdownReadinessDispose = staticCompositionLocalOf<((token: Int) -> Unit)?> { null }
internal val LocalImageReadinessDispose = staticCompositionLocalOf<((token: Int) -> Unit)?> { null }

internal object ChatPerfProbeToken {
    private val next = AtomicInteger(1)

    fun next(): Int = next.getAndIncrement()
}

/**
 * Test seam for the independent perf-fixture package. Production never sets
 * this, so real image URLs always go through the normal Coil fetchers.
 */
internal object ChatPerfImageModelOverride {
    @Volatile
    var resolve: ((String) -> String?)? = null
}

/**
 * Fixture-only command bridge. The fixture broadcast receiver drives the same
 * production ChatPage/ChatVM send path; it is never registered outside the
 * `perffixture` package because nothing else populates these slots.
 */
/**
 * Installs readiness probes for one message node. Safe to call for every
 * message: reports are keyed by node id and ignored unless that node is the
 * measured target, and non-target providers cost nothing outside trace builds.
 */
@Composable
internal fun ChatPerfNodeProbes(nodeKey: String, content: @Composable () -> Unit) {
    if (!ChatPerfTrace.enabled) {
        content()
        return
    }
    val generation = ChatPerfTrace.runGeneration()
    val markdownProbe = remember(generation, nodeKey) {
        { token: Int, pending: Boolean ->
            ChatPerfTrace.onMarkdownReadiness(generation, nodeKey, token, pending)
        }
    }
    val imageProbe = remember(generation, nodeKey) {
        { token: Int, settled: Boolean ->
            ChatPerfTrace.onMediaReadiness(generation, nodeKey, token, settled)
        }
    }
    val markdownDispose = remember(generation, nodeKey) {
        { token: Int -> ChatPerfTrace.onMarkdownReadinessDisposed(generation, nodeKey, token) }
    }
    val imageDispose = remember(generation, nodeKey) {
        { token: Int -> ChatPerfTrace.onMediaReadinessDisposed(generation, nodeKey, token) }
    }
    CompositionLocalProvider(
        LocalMarkdownReadinessProbe provides markdownProbe,
        LocalImageReadinessProbe provides imageProbe,
        LocalMarkdownReadinessDispose provides markdownDispose,
        LocalImageReadinessDispose provides imageDispose,
        content = content,
    )
}

internal object ChatPerfCommandBridge {
    private const val TAG = "AmberPerfFixture"
    private val interactionLock = Any()

    /** Fixture identity exists in both trace-on and trace-off packages. */
    @Volatile
    var fixtureRunId: String? = null
        private set
    private var pendingInteractionKind: String? = null
    private var interactionConfirmed = false

    fun prepareFixtureRun(runId: String) {
        synchronized(interactionLock) {
            fixtureRunId = runId
            pendingInteractionKind = null
            interactionConfirmed = false
        }
    }

    /** Receiver-side admission is based on fixture identity, not CHAT_PERF_TRACE. */
    fun beginInteraction(runId: String, kind: String): Boolean {
        synchronized(interactionLock) {
            if (fixtureRunId != runId) return false
            pendingInteractionKind = kind
            interactionConfirmed = false
            return true
        }
    }

    /**
     * Real UI response acknowledgement. Works with trace disabled; the on
     * package still separately records its internal interaction metric.
     */
    fun reportInteraction(kind: String, source: String): Boolean {
        val fixtureRun: String
        synchronized(interactionLock) {
            if (interactionConfirmed) return false
            if (pendingInteractionKind != kind) return false
            fixtureRun = fixtureRunId ?: return false
            interactionConfirmed = true
        }
        Log.i(
            TAG,
            "interaction-confirmed runId=$fixtureRun kind=$kind source=$source",
        )
        return true
    }

    @Volatile
    var sendText: ((String) -> Boolean)? = null

    @Volatile
    var stopGeneration: (() -> Boolean)? = null

    @Volatile
    var snapshot: (() -> String)? = null

    @Volatile
    var clearFocus: (() -> Boolean)? = null
}

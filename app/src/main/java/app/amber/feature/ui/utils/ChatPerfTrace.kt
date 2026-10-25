package app.amber.feature.ui.utils

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import app.amber.agent.BuildConfig
import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * Phase-2 non-debug diagnostics. Enabled only by `-PchatPerfTrace=true`
 * (`BuildConfig.CHAT_PERF_TRACE`); never tied to `BuildConfig.DEBUG`.
 *
 * A run is one EntryRequested -> draw/readable -> scripted interaction
 * lifecycle. Missing markers stay absent so the summarizer can fail closed.
 * `FirstContentDraw`/`FirstReadableDraw` require an explicit target contract
 * (message body or the S0 empty hero), not just a screen draw; see
 * [ChatPerfReadinessGate].
 *
 * The small JSON is written off the main thread only after a real record, to
 * a per-run directory, so separate pulls can never mix runs.
 */
internal object ChatPerfTrace {
    private const val TRACE_PREFIX = "AmberPerf"
    private const val DEFAULT_SNAPSHOT_DELAY_MS = 1_600L
    private const val DEFAULT_FINAL_WATCHDOG_MS = 10_000L
    private const val DEFAULT_FINAL_WINDOW_MS = 1_500.0
    private const val TAG = "AmberPerf"
    private val lock = Any()
    private val fileWriteLock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var pathId: String = NATIVE_STRESS_PATH
    private var scenario: String = "unknown"
    private var runId: String = "unknown"
    private var generation: Int = 0
    private val runGenerationState = mutableIntStateOf(0)
    private var exportRevision: Int = 0
    private var finalized = false
    private var finalInvalid = false
    private var finalInProgress = false
    private var snapshotJob: Job? = null
    private var finalJob: Job? = null
    private var watchdogJob: Job? = null

    // Test hooks only; production keeps the real window/delays.
    @Volatile internal var snapshotDelayMs: Long = DEFAULT_SNAPSHOT_DELAY_MS
    @Volatile internal var finalWatchdogMs: Long = DEFAULT_FINAL_WATCHDOG_MS
    @Volatile internal var finalWindowMs: Double = DEFAULT_FINAL_WINDOW_MS
    @Volatile internal var exportScope: CoroutineScope = ioScope
    @Volatile internal var clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() }
    private var finalStage = "entry-interaction"
    private var stageDeadlineMs = 0.0
    private var protocolFailure: String? = null
    private val stageHistory = mutableListOf<Pair<String, Double>>()
    private val markers = ChatPerfMarkerState(
        enabled = BuildConfig.CHAT_PERF_TRACE,
        clock = { clockNanos() },
    )
    private val readinessTracker = ChatPerfReadinessTracker()
    private val readinessGate = ChatPerfReadinessGate()
    private val visibilityGate = ChatPerfVisibilityGate()
    private val targetGenerationState = mutableIntStateOf(0)
    private var targetNodeId: String? = null
    private var emptyHeroComposed = false
    private var interactionRequestedAtMs: Double? = null
    private var interactionKind: String? = null
    private var interactionConfirmedAtMs: Double? = null
    private var interactionLatencyMs: Double? = null
    private var interactionSource: String? = null
    private var firstFocusObservedMs: Double? = null

    private var cookieCounter = 0
    private var nodesScanned = 0
    private var textsPrepared = 0
    private var charsPrepared = 0
    private var toolInputsPrepared = 0
    private var toolOutputCharsPrepared = 0
    private var planBuildMs = 0.0
    private var markdownParseCount = 0
    private var markdownParseMs = 0.0

    val enabled: Boolean get() = BuildConfig.CHAT_PERF_TRACE

    /**
     * Set by the measured host. Readiness changes can arrive after the first
     * draw (Markdown parse, image load, layout visibility); the host bumps a
     * Compose state so a fresh draw of the now-ready content can be observed.
     */
    @Volatile
    var readinessInvalidation: (() -> Unit)? = null

    /** Starts a new measured entry. Called by the fixture launcher on each click. */
    fun startRun(context: Context, scenarioId: String, externalRunId: String? = null, entryPath: String = NATIVE_STRESS_PATH) {
        if (!enabled) return
        synchronized(lock) {
            pathId = entryPath
            appContext = context.applicationContext
            scenario = scenarioId.take(48)
            runId = externalRunId
                ?.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                ?.take(80)
                ?.takeIf { it.isNotBlank() }
                ?: "${System.currentTimeMillis()}-${System.nanoTime().toString(16)}"
            generation++
            runGenerationState.intValue = generation
            exportRevision = 0
            finalized = false
            finalInvalid = false
            finalInProgress = false
            snapshotJob?.cancel()
            finalJob?.cancel()
            watchdogJob?.cancel()
            snapshotJob = null
            finalJob = null
            watchdogJob = null
            markers.reset()
            finalStage = if (scenario == "S7") "entry-window" else "entry-interaction"
            stageDeadlineMs = finalWatchdogMs.toDouble()
            protocolFailure = null
            stageHistory.clear()
            stageHistory += finalStage to 0.0
            readinessGate.clear()
            readinessTracker.clear()
            targetGenerationState.intValue = visibilityGate.beginTarget()
            targetNodeId = null
            emptyHeroComposed = false
            interactionRequestedAtMs = null
            interactionKind = null
            interactionConfirmedAtMs = null
            interactionLatencyMs = null
            interactionSource = null
            firstFocusObservedMs = null
            nodesScanned = 0
            textsPrepared = 0
            charsPrepared = 0
            toolInputsPrepared = 0
            toolOutputCharsPrepared = 0
            planBuildMs = 0.0
            markdownParseCount = 0
            markdownParseMs = 0.0
        }
        recordOnce("EntryRequested")
        // Content-free run binding for the atrace frame parser and negative
        // wrong-run tests. The run token is sanitized and capped at 80 chars.
        traceInstant("Run.$runId")
        scheduleWatchdog()
    }

    /** Fallback for diagnostic builds launched without the fixture launcher. */
    fun initializeIfNeeded(context: Context, scenarioId: String = "chat") {
        if (!enabled) return
        synchronized(lock) {
            if (appContext != null) return
        }
        startRun(context, scenarioId)
    }

    fun currentRunId(): String? = if (!enabled) null else synchronized(lock) { runId }

    fun runGeneration(): Int = if (!enabled) 0 else runGenerationState.intValue

    /** S7 has bounded phases, independent of the unchanged 1500ms sample. */
    fun beginFixtureStream(expectedRunId: String): Boolean {
        if (!enabled) return false
        synchronized(lock) {
            if (runId != expectedRunId || scenario != "S7" || finalStage != "entry-window" ||
                finalized || finalInvalid || finalInProgress || markers.elapsedMs() < finalWindowMs ||
                markers.elapsedMs() >= stageDeadlineMs
            ) return false
            finalStage = "stream"
            stageDeadlineMs = markers.elapsedMs() + 20_000.0
            stageHistory += finalStage to markers.elapsedMs()
            scheduleWatchdog()
            return true
        }
    }

    fun fixtureStreamSettled(expectedRunId: String) {
        if (!enabled) return
        synchronized(lock) {
            if (runId != expectedRunId || scenario != "S7" || finalStage != "stream" ||
                finalized || finalInvalid || finalInProgress || markers.elapsedMs() >= stageDeadlineMs
            ) return
            finalStage = "auxiliary-interaction"
            stageDeadlineMs = markers.elapsedMs() + 20_000.0
            stageHistory += finalStage to markers.elapsedMs()
            scheduleWatchdog()
        }
    }

    fun failFixtureStream(expectedRunId: String, reason: String) {
        if (!enabled) return
        val context = synchronized(lock) {
            if (runId != expectedRunId || finalized || finalInvalid) return
            protocolFailure = reason.take(80)
            appContext
        } ?: return
        exportScope.launch { writeSnapshot(context, expectedRunId, final = true) }
    }

    fun entryRequested() {
        recordOnce("EntryRequested")
    }

    fun dataReady() {
        recordOnce("DataReady")
    }

    fun initialPreparationReady() {
        recordOnce("InitialPreparationReady")
    }

    fun firstTimelineLayout() {
        recordOnce("FirstTimelineLayout")
    }

    /** The target message (newest settled node) or the explicit S0 hero. */
    fun targetGeneration(): Int = targetGenerationState.intValue

    fun beginTarget(kind: ChatPerfTargetKind, nodeId: Uuid?) {
        if (!enabled) return
        val id = nodeId?.toString()
        val generation = synchronized(lock) {
            if (readinessGate.kind == kind && targetNodeId == id) return
            // Probe tokens are run-scoped and keyed by node id; keep reports
            // that landed before this target switch. startRun() clears them.
            readinessGate.reset(kind)
            targetNodeId = id
            emptyHeroComposed = false
            visibilityGate.beginTarget(kind == ChatPerfTargetKind.EMPTY_HERO)
        }
        // Compose state read by the visibility observer, so a target change
        // restarts the observer instead of relying on a stale layout event.
        targetGenerationState.intValue = generation
    }

    fun onMarkdownReadiness(
        generationAtProbe: Int,
        nodeKey: String,
        token: Int,
        pending: Boolean,
    ) {
        if (!enabled) return
        val changed = synchronized(lock) {
            if (generation != generationAtProbe) return
            readinessTracker.markMarkdown(nodeKey, token, pending)
        }
        if (changed) readinessInvalidation?.invoke()
    }

    fun onMediaReadiness(
        generationAtProbe: Int,
        nodeKey: String,
        token: Int,
        settled: Boolean,
    ) {
        if (!enabled) return
        val changed = synchronized(lock) {
            if (generation != generationAtProbe) return
            readinessTracker.markMedia(nodeKey, token, settled)
        }
        if (changed) readinessInvalidation?.invoke()
    }

    fun onTargetVisible(generationAtReport: Int, visible: Boolean) {
        if (!enabled) return
        val changed = synchronized(lock) {
            if (readinessGate.kind != ChatPerfTargetKind.MESSAGE) return
            visibilityGate.report(generationAtReport, visible)
        }
        if (changed) readinessInvalidation?.invoke()
    }

    fun onEmptyHeroComposed() {
        if (!enabled) return
        val changed = synchronized(lock) {
            if (readinessGate.kind != ChatPerfTargetKind.EMPTY_HERO) return
            if (emptyHeroComposed) return
            emptyHeroComposed = true
            true
        }
        if (changed) readinessInvalidation?.invoke()
    }

    /**
     * Called from the measured layer's draw callback with the current route
     * alpha. Records only on the first qualifying draw; repeated draws do not
     * emit trace markers or wake the exporter again.
     */
    fun onTargetDraw(alpha: Float) {
        if (!enabled) return
        val decision = synchronized(lock) {
            val kind = readinessGate.kind ?: return
            val formatted = when (kind) {
                ChatPerfTargetKind.EMPTY_HERO -> emptyHeroComposed
                ChatPerfTargetKind.MESSAGE -> readinessTracker.formatted(targetNodeId.orEmpty())
            }
            val mediaSettled = when (kind) {
                ChatPerfTargetKind.EMPTY_HERO -> true
                ChatPerfTargetKind.MESSAGE -> readinessTracker.mediaSettled(targetNodeId.orEmpty())
            }
            val visible = when (kind) {
                ChatPerfTargetKind.EMPTY_HERO -> emptyHeroComposed
                ChatPerfTargetKind.MESSAGE -> visibilityGate.visible
            }
            readinessGate.onSample(
                ChatPerfReadinessSample(
                    draw = true,
                    alpha = alpha,
                    visible = visible,
                    formatted = formatted,
                    mediaSettled = mediaSettled,
                ),
            )
        }
        when (decision) {
            ChatPerfReadinessDecision.CONTENT_AND_READABLE -> {
                recordOnce("FirstContentDraw")
                recordOnce("FirstReadableDraw")
                scheduleSnapshot()
            }
            ChatPerfReadinessDecision.CONTENT -> {
                recordOnce("FirstContentDraw")
                scheduleSnapshot()
            }
            ChatPerfReadinessDecision.READABLE -> {
                recordOnce("FirstReadableDraw")
                scheduleSnapshot()
            }
            ChatPerfReadinessDecision.NONE -> Unit
        }
    }

    /**
     * Observation only, not the interaction metric: the composer can receive
     * focus automatically or from restore. Kept as an upper-bound witness.
     */
    fun observeFirstFocus() {
        if (!enabled) return
        val pendingInputRequest = synchronized(lock) {
            interactionRequestedAtMs != null &&
                interactionKind == "input-tap" &&
                !markers.has("FirstInteractive")
        }
        if (pendingInputRequest) {
            // A real tap after the fixture explicitly cleared focus. Auto-focus
            // without a pending input-tap request stays an observation only.
            confirmInteraction("input-focus")
            return
        }
        val recorded = synchronized(lock) {
            if (!markers.mark("FirstFocusObserved")) return
            firstFocusObservedMs = markers.relativeMs()["FirstFocusObserved"]
            true
        }
        if (recorded) traceInstant("FirstFocusObserved")
    }

    /**
     * The script announces the gesture it is about to perform. [kind] is a
     * fixed token such as `swipe`, never user text. The clock is the app's
     * monotonic clock, so the response latency is device-local.
     */
    fun interactionRequested(kind: String) {
        if (!enabled) return
        val requested = synchronized(lock) {
            if (finalized || finalInvalid || finalInProgress ||
                (scenario == "S7" && finalStage != "auxiliary-interaction")
            ) return
            if (markers.has("FirstInteractive")) return
            val at = markers.elapsedMs()
            if (interactionRequestedAtMs != null) return
            interactionRequestedAtMs = at
            interactionKind = kind.take(24)
            at
        }
        if (requested >= 0.0) traceInstant("InteractionRequested")
    }

    /**
     * The real UI response to [interactionRequested]. Automatic focus or
     * background work cannot satisfy it because no request is pending.
     */
    fun confirmInteraction(source: String) {
        if (!enabled) return
        val confirmed = synchronized(lock) {
            if (finalized || finalInvalid || finalInProgress) return
            val requested = interactionRequestedAtMs ?: return
            if (!markers.mark("FirstInteractive")) return
            val at = markers.elapsedMs()
            interactionConfirmedAtMs = at
            interactionLatencyMs = (at - requested).coerceAtLeast(0.0)
            interactionSource = source.take(32)
            true
        }
        if (confirmed) {
            traceInstant("FirstInteractive")
            Log.i(
                TAG,
                "interaction-confirmed runId=$runId source=${interactionSource ?: "unknown"} " +
                    "latencyMs=${round(interactionLatencyMs ?: 0.0)}",
            )
            scheduleFinal()
        }
    }

    fun onMarkdownReadinessDisposed(
        generationAtProbe: Int,
        nodeKey: String,
        token: Int,
    ) {
        if (!enabled) return
        val changed = synchronized(lock) {
            if (generation != generationAtProbe) return
            readinessTracker.removeMarkdown(nodeKey, token)
        }
        if (changed) readinessInvalidation?.invoke()
    }

    fun onMediaReadinessDisposed(
        generationAtProbe: Int,
        nodeKey: String,
        token: Int,
    ) {
        if (!enabled) return
        val changed = synchronized(lock) {
            if (generation != generationAtProbe) return
            readinessTracker.removeMedia(nodeKey, token)
        }
        if (changed) readinessInvalidation?.invoke()
    }

    fun beginInitialPreparation(): Int {
        if (!enabled) return 0
        val cookie = synchronized(lock) { ++cookieCounter }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.beginAsyncSection("$TRACE_PREFIX.InitialPreparation", cookie)
        }
        return cookie
    }

    fun endInitialPreparation(cookie: Int) {
        if (!enabled || cookie == 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.endAsyncSection("$TRACE_PREFIX.InitialPreparation", cookie)
        }
    }

    /** UTF-16 code units in Text outputs (String.length), without encoding bytes. */
    fun countToolOutputChars(tools: List<UIMessagePart.Tool>): Long =
        tools.sumOf { tool ->
            tool.output.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length.toLong() }
        }

    fun recordPreparation(
        nodes: Int = 0,
        texts: Int = 0,
        chars: Int = 0,
        toolInputs: Int = 0,
        toolOutputCharsPrepared: Long = 0L,
    ) {
        if (!enabled) return
        synchronized(lock) {
            nodesScanned += nodes
            textsPrepared += texts
            charsPrepared += chars
            toolInputsPrepared += toolInputs
            this.toolOutputCharsPrepared += toolOutputCharsPrepared.toInt().coerceAtLeast(0)
        }
    }

    fun recordPlanBuild(elapsedMs: Double) {
        if (!enabled) return
        synchronized(lock) { planBuildMs += elapsedMs }
    }

    fun recordMarkdownParse(elapsedMs: Double) {
        if (!enabled) return
        synchronized(lock) {
            markdownParseCount++
            markdownParseMs += elapsedMs
        }
    }

    private fun recordOnce(name: String) {
        if (!enabled) return
        val recorded = synchronized(lock) { markers.mark(name) }
        if (recorded) traceInstant(name)
    }

    private fun traceInstant(name: String) {
        if (!enabled) return
        Trace.beginSection("$TRACE_PREFIX.$name")
        Trace.endSection()
    }

    private fun scheduleSnapshot() {
        synchronized(lock) {
            if (finalized || finalInvalid || finalInProgress) return
            val context = appContext ?: return
            val expectedRunId = runId
            snapshotJob?.cancel()
            snapshotJob = exportScope.launch {
                delay(snapshotDelayMs)
                writeSnapshot(context, expectedRunId, final = false)
            }
        }
    }

    private fun scheduleFinal() {
        synchronized(lock) {
            if (finalized || finalInvalid || finalInProgress) return
            val context = appContext ?: return
            val expectedRunId = runId
            val remainingMs = (finalWindowMs - markers.elapsedMs()).coerceAtLeast(0.0)
            snapshotJob?.cancel()
            finalJob?.cancel()
            finalJob = exportScope.launch {
                if (remainingMs > 0.0) delay(remainingMs.toLong())
                writeSnapshot(context, expectedRunId, final = true)
            }
        }
    }

    private fun scheduleWatchdog() {
        synchronized(lock) {
            val context = appContext ?: return
            val expectedRunId = runId
            watchdogJob?.cancel()
            val expectedStage = finalStage
            val remainingMs = (stageDeadlineMs - markers.elapsedMs()).coerceAtLeast(0.0)
            watchdogJob = exportScope.launch {
                delay(kotlin.math.ceil(remainingMs).toLong())
                writeSnapshot(context, expectedRunId, final = true, timeoutStage = expectedStage)
            }
        }
    }

    private fun finalReasons(): List<String> {
        val required = listOf(
            "EntryRequested",
            "DataReady",
            "InitialPreparationReady",
            "FirstTimelineLayout",
            "FirstContentDraw",
            "FirstReadableDraw",
            "FirstInteractive",
        )
        val reasons = required.filterNot { markers.has(it) }.map { "missing $it" }.toMutableList()
        if (interactionRequestedAtMs == null || interactionConfirmedAtMs == null ||
            interactionLatencyMs == null
        ) {
            reasons += "missing scripted interaction request/response"
        }
        if (markers.elapsedMs() < finalWindowMs) reasons += "fixed window not finished"
        if (readinessGate.kind == null) reasons += "missing target"
        if (scenario == "S7" && finalStage != "auxiliary-interaction") reasons += "S7 stream not settled"
        protocolFailure?.let { reasons += it }
        return reasons
    }

    private fun writeSnapshot(context: Context, expectedRunId: String, final: Boolean, timeoutStage: String? = null) {
        val snapshot = synchronized(lock) {
            if (runId != expectedRunId) return
            if (timeoutStage != null && (timeoutStage != finalStage || markers.elapsedMs() < stageDeadlineMs)) return
            if (final) {
                if (finalized || finalInvalid || finalInProgress) return
            } else if (finalized || finalInvalid || finalInProgress) {
                return
            }
            if (!markers.has("EntryRequested")) return
            if (final) finalInProgress = true
            val reasons = if (final) finalReasons().toMutableList().apply {
                if (timeoutStage != null) add("phase timeout: $timeoutStage")
            } else emptyList()
            val status = when {
                !final -> "snapshot"
                reasons.isEmpty() -> "final"
                else -> "invalid"
            }
            exportRevision++
            ExportSnapshot(
                pathId = pathId,
                scenario = scenario,
                runId = runId,
                status = status,
                revision = exportRevision,
                stage = finalStage,
                stageHistory = stageHistory.toList(),
                reasons = reasons,
                targetKind = readinessGate.kind,
                phasesMs = markers.relativeMs(),
                interaction = interactionLatencyMs?.let {
                    InteractionSnapshot(
                        requestedAtMs = interactionRequestedAtMs ?: 0.0,
                        confirmedAtMs = interactionConfirmedAtMs ?: 0.0,
                        latencyMs = it,
                        kind = interactionKind ?: "unknown",
                        source = interactionSource ?: "unknown",
                    )
                },
                firstFocusObservedMs = firstFocusObservedMs,
                nodesScanned = nodesScanned,
                textsPrepared = textsPrepared,
                charsPrepared = charsPrepared,
                toolInputsPrepared = toolInputsPrepared,
                toolOutputCharsPrepared = toolOutputCharsPrepared,
                planBuildMs = planBuildMs,
                markdownParseCount = markdownParseCount,
                markdownParseMs = markdownParseMs,
            )
        }
        val root = context.getExternalFilesDir("chat-perf") ?: File(context.filesDir, "chat-perf")
        val directory = File(root, "runs/${snapshot.runId}").apply { mkdirs() }
        val file = File(directory, "metrics-${snapshot.scenario}.json")
        val json = JSONObject().apply {
            put("schema", 2)
            put("pathId", snapshot.pathId)
            put("scenario", snapshot.scenario)
            put("runId", snapshot.runId)
            put("package", context.packageName)
            put("buildType", BuildConfig.BUILD_TYPE)
            put("traceEnabled", enabled)
            put("status", snapshot.status)
            put("revision", snapshot.revision)
            put("finalizationStage", snapshot.stage)
            put("stageHistory", org.json.JSONArray().apply {
                snapshot.stageHistory.forEach { (name, at) ->
                    put(JSONObject().put("stage", name).put("atMs", round(at)))
                }
            })
            put("targetKind", snapshot.targetKind?.name ?: "NONE")
            if (snapshot.reasons.isNotEmpty()) {
                put("invalidReasons", org.json.JSONArray(snapshot.reasons))
            }
            put("phasesMs", JSONObject().apply {
                snapshot.phasesMs.forEach { (name, ms) -> put(name, round(ms)) }
            })
            snapshot.interaction?.let { interaction ->
                put("interaction", JSONObject().apply {
                    put("requestedAtMs", round(interaction.requestedAtMs))
                    put("confirmedAtMs", round(interaction.confirmedAtMs))
                    put("latencyMs", round(interaction.latencyMs))
                    put("kind", interaction.kind)
                    put("source", interaction.source)
                })
            }
            snapshot.firstFocusObservedMs?.let { put("firstFocusObservedMs", round(it)) }
            put("preparation", JSONObject().apply {
                put("nodesScanned", snapshot.nodesScanned)
                put("textsPrepared", snapshot.textsPrepared)
                put("charsPrepared", snapshot.charsPrepared)
                put("toolInputsPrepared", snapshot.toolInputsPrepared)
                put("toolOutputCharsPrepared", snapshot.toolOutputCharsPrepared)
                put("planBuildMs", round(snapshot.planBuildMs))
                put("markdownParseCount", snapshot.markdownParseCount)
                put("markdownParseMs", round(snapshot.markdownParseMs))
            })
        }
        try {
            synchronized(fileWriteLock) {
                if (!final) {
                    synchronized(lock) {
                        if (finalized || finalInvalid || finalInProgress) return
                    }
                }
                val tmp = File(directory, file.name + ".tmp")
                tmp.writeText(json.toString(2))
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
                synchronized(lock) {
                    if (runId == expectedRunId && final) {
                        finalInProgress = false
                        if (snapshot.status == "final") finalized = true else finalInvalid = true
                    }
                }
                val detail = if (snapshot.status == "final") {
                    "metrics-export runId=${snapshot.runId} status=final revision=${snapshot.revision} complete=true"
                } else if (final) {
                    "metrics-export runId=${snapshot.runId} status=invalid revision=${snapshot.revision} " +
                        "complete=false reasons=${snapshot.reasons.joinToString(";")}"
                } else {
                    "metrics-snapshot runId=${snapshot.runId} revision=${snapshot.revision} " +
                        "status=snapshot complete=false"
                }
                Log.i(TAG, detail)
            }
        } catch (error: Exception) {
            synchronized(lock) { if (final) finalInProgress = false }
            Log.w(TAG, "perf metrics export failed", error)
        }
    }

    private fun round(value: Double): Double = String.format(Locale.US, "%.3f", value).toDouble()

    private data class InteractionSnapshot(
        val requestedAtMs: Double,
        val confirmedAtMs: Double,
        val latencyMs: Double,
        val kind: String,
        val source: String,
    )

    private data class ExportSnapshot(
        val pathId: String,
        val scenario: String,
        val runId: String,
        val status: String,
        val revision: Int,
        val stage: String,
        val stageHistory: List<Pair<String, Double>>,
        val reasons: List<String>,
        val targetKind: ChatPerfTargetKind?,
        val phasesMs: Map<String, Double>,
        val interaction: InteractionSnapshot?,
        val firstFocusObservedMs: Double?,
        val nodesScanned: Int,
        val textsPrepared: Int,
        val charsPrepared: Int,
        val toolInputsPrepared: Int,
        val toolOutputCharsPrepared: Int,
        val planBuildMs: Double,
        val markdownParseCount: Int,
        val markdownParseMs: Double,
    )
}

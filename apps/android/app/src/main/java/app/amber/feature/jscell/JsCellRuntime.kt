package app.amber.feature.jscell

import android.util.Log
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "JsCellRuntime"
private const val POLL_MS = 20L

/**
 * P4-03 persistent JS cell runtime (parity plan §10 P4-03) — first version.
 *
 *  - Each cell owns exactly one worker thread and one isolated JS session
 *    (QuickJS context). Evaluations are serialized per cell.
 *  - Long tasks: `js_cell_run` returns `running` + cellId once the evaluation
 *    outlives [JsCellLimits.quickReturnMs]; `js_cell_wait` polls for new
 *    console output / terminal state with a caller timeout.
 *  - Hard limits: memory + JS stack depth are engine-enforced; output chars
 *    and total run time are enforced here (timeout => TERMINATED(time_limit));
 *    store size is enforced at the store/load API. A blocked evaluation that
 *    never returns cannot be preempted by the QuickJS wrapper (no interrupt
 *    handler) — the cell is still marked TERMINATED and its session is closed
 *    by the worker as soon as the evaluation returns (documented debug-only
 *    limitation of the first version).
 *  - Nested tool calls go through a strict whitelist ([nestedToolResolver]);
 *    v1 only admits read-only tools, never write tools, so no permission or
 *    ledger bypass is possible from inside JS.
 *  - Persistence: metadata + store content + terminal states only (DataStore).
 *    Cold start marks persisted RUNNING/WAITING cells TERMINATED(process_restart)
 *    instead of pretending they are alive.
 */
class JsCellRuntime(
    private val store: JsCellStore,
    private val engine: JsCellEngine,
    private val limits: JsCellLimits = JsCellLimits(),
    private val nestedToolResolver: (String) -> Tool? = { null },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val live = ConcurrentHashMap<String, LiveCell>()
    private val cellCounter = AtomicInteger(0)

    /** Cold-start: persisted non-terminal cells are marked TERMINATED(process_restart). */
    suspend fun recoverFromColdStart(): Int = store.reconcileAfterColdStart()

    suspend fun createCell(ownerRunId: String?): JsonObject {
        val now = System.currentTimeMillis()
        val cellId = "cell_${cellCounter.incrementAndGet()}_${UUID.randomUUID().toString().take(8)}"
        val cell = LiveCell(
            cellId = cellId,
            ownerRunId = ownerRunId,
            limits = limits,
            store = store,
            engine = engine,
            nestedToolResolver = nestedToolResolver,
            json = json,
            createdAtMs = now,
            initialStatus = JsCellStatus.WAITING,
        )
        live[cellId] = cell
        cell.persist()
        return buildJsonObject {
            put("status", JsCellStatus.WAITING.name.lowercase())
            put("cell_id", cellId)
            if (ownerRunId != null) put("owner_run_id", ownerRunId)
        }
    }

    /**
     * Execute [code] in the cell. Short evaluations complete inline and return
     * their result; evaluations still running after [JsCellLimits.quickReturnMs]
     * return `running` + cellId and are polled with `js_cell_wait`.
     */
    suspend fun runCell(cellId: String, code: String): JsonObject {
        val cell = requireLive(cellId) ?: return cellNotFound(cellId)
        synchronized(cell.lock) {
            when (cell.status) {
                JsCellStatus.RUNNING -> return busy(cellId)
                JsCellStatus.TERMINATED -> return terminated(cellId, cell.error)
                else -> {
                    // WAITING / COMPLETED / FAILED: start a fresh run (globals
                    // of the cell's session survive across runs — store/load is
                    // the explicit cross-cell-state mechanism).
                    cell.output.clear()
                    cell.outputTruncated = false
                    cell.terminalResult = null
                    cell.error = null
                    cell.status = JsCellStatus.RUNNING
                    cell.runStartedAtMs = System.currentTimeMillis()
                    cell.updatedAtMs = cell.runStartedAtMs
                }
            }
        }
        cell.persist() // RUNNING is metadata, not an in-flight stack
        cell.submitRun(code)
        val deadline = System.currentTimeMillis() + limits.quickReturnMs.coerceAtMost(limits.maxRunTimeMs)
        while (true) {
            synchronized(cell.lock) {
                if (enforceTimeLimitLocked(cell)) return cell.snapshot(cursor = 0, lastState = "terminated")
                if (cell.status !in setOf(JsCellStatus.RUNNING, JsCellStatus.WAITING)) {
                    return cell.snapshot(cursor = 0, lastState = cell.status.name.lowercase())
                }
            }
            if (System.currentTimeMillis() >= deadline) break
            delay(POLL_MS)
        }
        return cell.snapshot(cursor = 0, lastState = JsCellStatus.RUNNING.name.lowercase())
    }

    /**
     * Poll for new output / terminal state of [cellId]. Returns as soon as
     * new console output arrives, the cell reaches a terminal state, the
     * total run time limit is hit, or [timeoutMs] elapses.
     */
    suspend fun waitCell(cellId: String, timeoutMs: Long, cursor: Int = 0): JsonObject {
        val cell = live[cellId] ?: store.get(cellId)?.let { record ->
            return terminalSnapshotFrom(record, cursor)
        } ?: return cellNotFound(cellId)
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0)
        while (true) {
            synchronized(cell.lock) {
                if (cell.status != JsCellStatus.RUNNING) {
                    return cell.snapshot(cursor = cursor, lastState = cell.status.name.lowercase())
                }
                if (enforceTimeLimitLocked(cell)) return cell.snapshot(cursor = cursor, lastState = "terminated")
                if (cell.output.size > cursor) {
                    return cell.snapshot(cursor = cursor, lastState = JsCellStatus.RUNNING.name.lowercase())
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                return cell.snapshot(cursor = cursor, lastState = JsCellStatus.RUNNING.name.lowercase())
            }
            delay(POLL_MS)
        }
    }

    suspend fun terminateCell(cellId: String, reason: String = JsCellTerminationReasons.USER): JsonObject {
        val cell = live[cellId] ?: return cellNotFound(cellId)
        synchronized(cell.lock) {
            if (cell.status == JsCellStatus.TERMINATED) return terminated(cellId, cell.error)
            cell.status = JsCellStatus.TERMINATED
            cell.error = reason
            cell.updatedAtMs = System.currentTimeMillis()
            cell.terminated = true
        }
        cell.persist()
        cell.closeSessionAndShutdown()
        return buildJsonObject {
            put("status", JsCellStatus.TERMINATED.name.lowercase())
            put("cell_id", cellId)
            put("reason", reason)
        }
    }

    /** Persist small serializable state (size-capped). */
    suspend fun storeCell(cellId: String, value: String): JsonObject {
        val cell = live[cellId] ?: return cellNotFound(cellId)
        if (value.length > limits.storeBytes) {
            return buildJsonObject {
                put("status", "failed")
                put("error", "store_too_large")
                put("bytes", value.length)
                put("max_bytes", limits.storeBytes)
            }
        }
        synchronized(cell.lock) {
            cell.storeJson = value
            cell.updatedAtMs = System.currentTimeMillis()
        }
        cell.persist()
        return buildJsonObject {
            put("status", "stored")
            put("cell_id", cellId)
            put("bytes", value.length)
        }
    }

    suspend fun loadCell(cellId: String): JsonObject {
        val cell = live[cellId] ?: store.get(cellId)?.let { record ->
            return buildJsonObject {
                put("status", "loaded")
                put("cell_id", cellId)
                put("value", record.storeJson)
                put("bytes", record.storeJson.length)
            }
        } ?: return cellNotFound(cellId)
        val value = synchronized(cell.lock) { cell.storeJson }
        return buildJsonObject {
            put("status", "loaded")
            put("cell_id", cellId)
            put("value", value)
            put("bytes", value.length)
        }
    }

    /** Test/observability hook. */
    fun shutdownForTest() {
        live.values.forEach { it.closeSessionAndShutdown() }
        live.clear()
    }

    // ------------------------------------------------------------------

    private suspend fun requireLive(cellId: String): LiveCell? =
        live[cellId] ?: store.get(cellId)?.let { record ->
            // Hydrate a cell persisted by an earlier round of this process.
            // An in-process record can never be RUNNING without a live worker
            // (the singleton runtime owns all live cells), so a stale RUNNING
            // record is treated as idle WAITING.
            val status = if (record.statusEnum == JsCellStatus.RUNNING) JsCellStatus.WAITING else record.statusEnum
            val cell = LiveCell(
                cellId = record.cellId,
                ownerRunId = record.ownerRunId,
                limits = limits,
                store = store,
                engine = engine,
                nestedToolResolver = nestedToolResolver,
                json = json,
                createdAtMs = record.createdAtMs,
                updatedAtMs = record.updatedAtMs,
                initialStatus = status,
                storeJson = record.storeJson,
                lastOutput = record.lastOutput,
            )
            live[cellId] = cell
            cell
        }

    /**
     * Enforced under the cell lock: a RUNNING evaluation past
     * [JsCellLimits.maxRunTimeMs] is TERMINATED(time_limit). Returns true when
     * the limit just fired so the caller returns the terminal snapshot.
     */
    private fun enforceTimeLimitLocked(cell: LiveCell): Boolean {
        if (cell.status != JsCellStatus.RUNNING) return false
        if (System.currentTimeMillis() - cell.runStartedAtMs <= limits.maxRunTimeMs) return false
        cell.status = JsCellStatus.TERMINATED
        cell.error = JsCellTerminationReasons.TIME_LIMIT
        cell.updatedAtMs = System.currentTimeMillis()
        cell.terminated = true
        cell.persistBlocking()
        cell.closeSessionAndShutdown()
        return true
    }

    private fun terminalSnapshotFrom(record: JsCellRecord, cursor: Int): JsonObject = buildJsonObject {
        put("status", record.statusEnum.name.lowercase())
        put("cell_id", record.cellId)
        put("output", "")
        // The persisted output boundary is the whole recorded output; never
        // move the cursor backwards.
        put("cursor", maxOf(record.lastOutput.length, cursor))
        record.terminalResult?.let { put("result", it) }
        record.error?.let { put("error", it) }
    }

    private fun cellNotFound(cellId: String): JsonObject = buildJsonObject {
        put("status", "failed")
        put("error", "cell_not_found")
        put("cell_id", cellId)
    }

    private fun busy(cellId: String): JsonObject = buildJsonObject {
        put("status", "failed")
        put("error", "cell_busy")
        put("cell_id", cellId)
    }

    private fun terminated(cellId: String, reason: String?): JsonObject = buildJsonObject {
        put("status", "terminated")
        put("cell_id", cellId)
        reason?.let { put("error", it) }
    }

    /**
     * One live cell. All mutable state is guarded by [lock]; the QuickJS
     * session is only touched from the cell's worker thread (created lazily
     * before the first evaluate, closed by the worker after termination).
     */
    private class LiveCell(
        val cellId: String,
        val ownerRunId: String?,
        private val limits: JsCellLimits,
        private val store: JsCellStore,
        private val engine: JsCellEngine,
        private val nestedToolResolver: (String) -> Tool?,
        private val json: Json,
        createdAtMs: Long,
        updatedAtMs: Long = createdAtMs,
        initialStatus: JsCellStatus,
        storeJson: String = "{}",
        lastOutput: String = "",
    ) {
        val lock = Any()
        val createdAtMs: Long = createdAtMs
        var status: JsCellStatus = initialStatus
        var updatedAtMs: Long = updatedAtMs
        var storeJson: String = storeJson
        val output: ArrayList<String> = ArrayList()
        var outputCharsSoFar: Int = 0
        var outputTruncated: Boolean = false
        var terminalResult: String? = null
        var error: String? = null
        var runStartedAtMs: Long = 0L
        var terminated: Boolean = false
        var lastOutput: String = lastOutput

        private var session: JsCellSession? = null
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "js-cell-$cellId") }

        /** Append one console line, hard-capped at [JsCellLimits.outputChars] (exact joined length). */
        fun appendOutput(line: String) {
            synchronized(lock) {
                if (outputTruncated) return
                val separator = if (output.isEmpty()) 0 else 1
                if (outputCharsSoFar + separator + line.length > limits.outputChars) {
                    val budget = limits.outputChars - outputCharsSoFar - separator
                    if (budget > 0) {
                        output.add(line.take(budget))
                        outputCharsSoFar += separator + budget
                    }
                    outputTruncated = true
                    return
                }
                outputCharsSoFar += separator + line.length
                output.add(line)
            }
        }

        /** Submit one evaluation to the cell's worker thread. */
        fun submitRun(code: String) {
            worker.execute {
                val outcome: JsEvalOutcome = try {
                    val s = sessionOrCreate()
                    synchronized(lock) {
                        if (terminated) {
                            // terminate won before the evaluation started
                            closeSessionLocked()
                            return@execute
                        }
                    }
                    s.evaluate(code)
                } catch (error: Throwable) {
                    JsEvalOutcome.Failed(error.message ?: error.toString())
                }
                synchronized(lock) {
                    if (terminated) {
                        // time limit / user terminate won mid-evaluation: keep
                        // the terminal state, never overwrite it with a result
                        closeSessionLocked()
                        return@execute
                    }
                    when (outcome) {
                        is JsEvalOutcome.Completed -> {
                            status = JsCellStatus.COMPLETED
                            terminalResult = outcome.result
                        }
                        is JsEvalOutcome.Failed -> {
                            status = JsCellStatus.FAILED
                            error = outcome.error
                        }
                    }
                    updatedAtMs = System.currentTimeMillis()
                    lastOutput = output.joinToString("\n")
                }
                persistBlocking()
            }
        }

        /** Snapshot for tool responses: output delta since [cursor]. */
        fun snapshot(cursor: Int, lastState: String): JsonObject = buildJsonObject {
            synchronized(lock) {
                put("status", lastState)
                put("cell_id", cellId)
                val delta = if (cursor < output.size) output.subList(cursor, output.size).joinToString("\n") else ""
                put("output", delta)
                put("cursor", output.size.coerceAtLeast(cursor))
                terminalResult?.let { put("result", it) }
                error?.let { put("error", it) }
                if (outputTruncated) put("truncated", true)
            }
        }

        fun persist() {
            val record = record()
            runBlocking { store.upsert(record) }
        }

        /** Persist from the worker thread (suspend boundary). */
        fun persistBlocking() {
            val record = record()
            runBlocking { store.upsert(record) }
        }

        /** Queue the session close on the worker (FIFO after any in-flight evaluate), then stop the executor. */
        fun closeSessionAndShutdown() {
            runCatching {
                worker.execute {
                    synchronized(lock) { closeSessionLocked() }
                }
            }
            runCatching { worker.shutdown() }
        }

        private fun sessionOrCreate(): JsCellSession =
            synchronized(lock) {
                session ?: engine.createSession(
                    limits = limits,
                    console = { line -> appendOutput(line) },
                    callTool = { name, argsJson -> nestedCallTool(name, argsJson) },
                ).also { session = it }
            }

        private fun closeSessionLocked() {
            session?.let {
                session = null
                runCatching { it.close() }
            }
        }

        private fun record(): JsCellRecord = synchronized(lock) {
            JsCellRecord(
                cellId = cellId,
                ownerRunId = ownerRunId,
                status = status.name,
                createdAtMs = createdAtMs,
                updatedAtMs = updatedAtMs,
                storeJson = storeJson,
                lastOutput = lastOutput,
                terminalResult = terminalResult,
                error = error,
            )
        }

        /** Host `js_call_tool` implementation — whitelist-only, never bypasses tool capability. */
        private fun nestedCallTool(toolName: String, argsJson: String): String {
            val tool = nestedToolResolver(toolName)
            if (tool == null) {
                return """{"error":"tool_not_allowed","tool":"$toolName"}"""
            }
            return try {
                val args: JsonElement = json.parseToJsonElement(argsJson.ifBlank { "{}" })
                val parts = runBlocking { tool.execute(args) }
                val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                text.ifBlank { "{}" }
            } catch (error: Throwable) {
                Log.w(TAG, "nested tool $toolName failed", error)
                """{"error":"tool_failed","tool":"$toolName","message":"${error.message ?: error.toString()}"}"""
            }
        }
    }
}

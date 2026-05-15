package me.rerere.rikkahub.data.agent.board.aggregator

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.agent.board.BoardRepository
import me.rerere.rikkahub.data.agent.board.BoardSignalSourceType
import me.rerere.rikkahub.data.agent.board.TodayBoardSetting
import me.rerere.rikkahub.data.agent.board.TODAY_BOARD_HARD_MUTE_WEIGHT
import me.rerere.rikkahub.data.agent.board.collector.BoardSignalCollector
import me.rerere.rikkahub.data.agent.board.collector.RawBoardSignal
import me.rerere.rikkahub.data.db.entity.BoardSignalEntity
import me.rerere.rikkahub.data.db.entity.BoardWeightEntity
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Orchestrates signal collection, deduplication, scoring, and persistence.
 *
 * Lifecycle per agent run:
 *  1. [collectAll] - invokes all enabled pull-based collectors in parallel.
 *  2. [ingest] - called by push-based collectors (notifications) as signals arrive.
 *  3. [getFilteredSignals] - reads unprocessed signals from DB, applies weight-based
 *     scoring, and returns the batch ready for the BoardAgent.
 *
 * Dedup strategy: a signal is considered duplicate if either:
 *  - Same (sourceType, sourceRef) already exists in the DB within the last 24h.
 *  - Same (sourceType, contentHash) already exists in the DB within the last 24h.
 *
 * Scoring: each signal gets a base score of 0. The SignalScorer adjusts it by looking up
 * the BoardWeightEntity table for matching (sourceType, keyword) pairs. Signals whose
 * final score falls below [HARD_MUTE_THRESHOLD] are dropped before reaching the agent.
 */
class SignalAggregator(
    private val boardRepository: BoardRepository,
    private val collectors: List<BoardSignalCollector>,
    private val settingProvider: () -> TodayBoardSetting,
    private val onThresholdReached: (() -> Unit)? = null,
) {
    /**
     * Run all enabled pull-based collectors in parallel and persist their output.
     * Returns the count of newly ingested signals (after dedup).
     */
    suspend fun collectAll(): Int = coroutineScope {
        val setting = settingProvider()
        val enabledCollectors = collectors.filter { it.sourceType in setting.enabledSources }
        val batches = enabledCollectors.map { collector ->
            async(Dispatchers.IO) {
                runCatching { collector.collect() }
                    .onFailure { Log.w(TAG, "collector ${collector.sourceType} failed", it) }
                    .getOrElse { emptyList() }
                  .map { collector to it }
            }
        }.awaitAll()

        var ingested = 0
        for (batch in batches) {
            for ((collector, signal) in batch) {
                if (ingest(signal)) {
                    ingested++
                    runCatching { collector.onIngested(signal) }
                }
            }
        }
        ingested
    }

    /**
     * Ingest a single raw signal. Performs dedup check and persists if novel.
     * Returns true if the signal was actually stored (not a duplicate).
     *
     * Thread-safe: may be called from the notification listener's IO scope concurrently
     * with a scheduled collectAll(). Room handles the serialization at the DB layer.
     */
    private val ingestMutex = Mutex()

    suspend fun ingest(raw: RawBoardSignal): Boolean = ingestMutex.withLock {
        val now = System.currentTimeMillis()
        val dedupWindow = now - DEDUP_WINDOW_MS

        // Dedup by sourceRef
        val existingByRef = boardRepository.findSignalBySourceRef(raw.sourceType, raw.sourceRef)
        if (existingByRef != null) return false

        // Dedup by content hash
        val hash = contentHash(raw.sourceType, raw.title, raw.content)
        val existingByHash = boardRepository.findSignalByContentHash(hash, raw.sourceType, dedupWindow)
        if (existingByHash != null) return false

        val entity = BoardSignalEntity(
            id = Uuid.random().toString(),
            sourceType = raw.sourceType,
            sourceRef = raw.sourceRef,
            title = raw.title,
            content = raw.content,
            contentHash = hash,
            signalTime = raw.signalTime,
            metadataJson = raw.metadataJson,
            processed = false,
            processedAt = null,
            createdAt = now,
        )
        boardRepository.saveSignalIfAbsent(entity).also { saved ->
            if (saved) maybeTriggerIncrementalRun()
        }
    }

    /**
     * Check whether unprocessed signal count has crossed the user-configured threshold
     * and, if so, hand off to the scheduler to enqueue an incremental run. Kept narrow
     * on purpose — threshold logic lives here so the scheduler stays dumb.
     *
     * Debounced: after firing once, skips checks for [THRESHOLD_COOLDOWN_MS] to avoid
     * redundant DB queries and WorkManager enqueues during notification bursts.
     */
    @Volatile
    private var lastThresholdFiredMs = 0L

    private suspend fun maybeTriggerIncrementalRun() {
        val now = System.currentTimeMillis()
        if (now - lastThresholdFiredMs < THRESHOLD_COOLDOWN_MS) return
        val threshold = settingProvider().incrementalSignalThreshold.coerceAtLeast(1)
        val pending = boardRepository.countUnprocessedSignals()
        if (pending >= threshold) {
            lastThresholdFiredMs = now
            onThresholdReached?.invoke()
        }
    }

    /**
     * Read unprocessed signals, apply weight-based scoring, filter out hard-muted ones,
     * and return the batch sorted by score descending (highest priority first).
     *
     * Does NOT mark signals as processed - that is the caller's (BoardWorker) job after
     * the agent successfully produces a board.
     */
    suspend fun getFilteredSignals(limit: Int = 200): List<ScoredSignal> {
        val signals = boardRepository.getUnprocessedSignals(limit)
        if (signals.isEmpty()) return emptyList()

        val weights = boardRepository.getAllWeights()
        return signals
            .map { signal -> ScoredSignal(signal, score(signal, weights)) }
            .filter { it.score > HARD_MUTE_THRESHOLD }
            .sortedByDescending { it.score }
    }

    /** How many unprocessed signals are waiting? Used by the scheduler to check threshold. */
    suspend fun pendingCount(): Int = boardRepository.countUnprocessedSignals()

    // ---- Scoring --------------------------------------------------------------------

    private fun score(signal: BoardSignalEntity, weights: List<BoardWeightEntity>): Int {
        // Start with rule-based scoring by source type and content heuristics.
        var total = ruleBasedScore(signal)

        // Layer on user-configured keyword weights (BoardWeightEntity table).
        for (weight in weights) {
            if (weight.sourceType != signal.sourceType) continue
            val keyword = weight.keyword
            if (keyword == BoardWeightEntity.WHOLE_SOURCE) {
                total += weight.weight
            } else if (signal.title.contains(keyword, ignoreCase = true) ||
                signal.content.contains(keyword, ignoreCase = true)
            ) {
                total += weight.weight
            }
        }
        return total
    }

    /**
     * Automatic relevance scoring based on signal source type, timing, and content
     * heuristics. Runs before user keyword weights so it provides a sensible default
     * even when the weight table is empty (which it almost always is).
     */
    private fun ruleBasedScore(signal: BoardSignalEntity): Int {
        val now = System.currentTimeMillis()
        var score = 0

        when (signal.sourceType) {
            // ---- Calendar: the closer the event, the more important ----
            BoardSignalSourceType.CALENDAR -> {
                score += 5 // base: calendar events are inherently actionable
                val minutesUntil = (signal.signalTime - now) / 60_000L
                when {
                    minutesUntil in -30..30 -> score += 8  // imminent or just happened
                    minutesUntil in 31..120 -> score += 4  // coming up within 2h
                    minutesUntil in 121..360 -> score += 2 // later today
                }
            }

            // ---- Notifications: use priority hint from collector metadata ----
            BoardSignalSourceType.NOTIFICATION -> {
                val priority = extractMetaField(signal.metadataJson, "priority")
                when (priority) {
                    "high" -> score += 6   // communication app with @mention
                    "medium" -> score += 3 // communication app, no @mention
                    "low" -> score += 0    // other apps — rely on keyword weights
                }
            }

            // ---- Chat history: relevance score embedded by collector ----
            BoardSignalSourceType.CHAT_HISTORY -> {
                val relevance = extractMetaField(signal.metadataJson, "relevance")
                    .toIntOrNull() ?: 0
                score += relevance.coerceIn(0, 10)
            }

            // ---- Feishu messages: always somewhat important ----
            BoardSignalSourceType.FEISHU_MSG -> {
                score += 4
            }

            // ---- Feishu docs: document changes are moderately useful ----
            BoardSignalSourceType.FEISHU_DOC -> {
                score += 3
            }

            // ---- Time anchors: near-zero value, only survive as fillers ----
            BoardSignalSourceType.TIME -> {
                score -= 3 // effectively suppressed unless nothing else exists
            }
        }

        return score
    }

    private fun extractMetaField(json: String, key: String): String {
        val pattern = "\"$key\"\\s*:\\s*\"?([^\"\\s,}]+)\"?"
        return Regex(pattern).find(json)?.groupValues?.getOrNull(1).orEmpty()
    }

    // ---- Hashing --------------------------------------------------------------------

    private fun contentHash(sourceType: String, title: String, content: String): String {
        val input = "$sourceType|${title.lowercase().trim()}|${content.lowercase().trim().take(500)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    companion object {
        private const val TAG = "SignalAggregator"

        /** Signals within this window are checked for dedup. */
        private const val DEDUP_WINDOW_MS = 24L * 60L * 60L * 1000L

        /** Signals scoring at or below this are dropped (hard mute). */
        const val HARD_MUTE_THRESHOLD = TODAY_BOARD_HARD_MUTE_WEIGHT

        /** Cooldown after firing incremental trigger to avoid burst spam. */
        private const val THRESHOLD_COOLDOWN_MS = 60_000L
    }
}

/**
 * A signal paired with its computed relevance score. Passed to the BoardAgent so it can
 * see both the raw data and the system's prior on importance.
 */
data class ScoredSignal(
    val signal: BoardSignalEntity,
    val score: Int,
)

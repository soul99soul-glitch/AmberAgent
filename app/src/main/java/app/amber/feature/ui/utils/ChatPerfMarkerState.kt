package app.amber.feature.ui.utils

/**
 * Pure, testable marker state for a single measured entry. Missing phases stay
 * absent (never synthesized as 0) so the summarizer can fail closed.
 */
internal class ChatPerfMarkerState(
    val enabled: Boolean,
    private val clock: () -> Long,
) {
    private var startedAt: Long = 0L
    private val phases = LinkedHashMap<String, Long>()

    fun reset() {
        startedAt = clock()
        phases.clear()
    }

    /** Returns true only for the first successful record of [name]. */
    fun mark(name: String): Boolean {
        if (!enabled) return false
        if (startedAt == 0L) startedAt = clock()
        if (phases.containsKey(name)) return false
        phases[name] = clock() - startedAt
        return true
    }

    /** Milliseconds since [reset], or 0 before the first reset. */
    fun elapsedMs(): Double {
        if (startedAt == 0L) return 0.0
        return (clock() - startedAt) / 1_000_000.0
    }

    fun has(name: String): Boolean = phases.containsKey(name)

    fun relativeMs(): Map<String, Double> =
        phases.mapValues { (_, nanos) -> nanos / 1_000_000.0 }

    fun names(): List<String> = phases.keys.toList()
}

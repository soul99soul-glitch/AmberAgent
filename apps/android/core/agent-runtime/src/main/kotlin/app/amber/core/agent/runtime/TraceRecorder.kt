package app.amber.core.agent.runtime

interface TraceRecorder {
    suspend fun <T> span(
        name: String,
        kind: SpanKind,
        attributes: SpanAttrs = SpanAttrs.empty(),
        block: suspend SpanScope.() -> T,
    ): T
}

enum class SpanKind { RUN, MODEL_TURN, TOOL_CALL, HANDOFF, PERMISSION }

data class SpanAttrs(val entries: Map<String, String>) {
    companion object {
        fun empty(): SpanAttrs = SpanAttrs(emptyMap())
    }
}

interface SpanScope {
    val spanId: String
    fun setAttribute(key: String, value: String)
    fun setError(throwable: Throwable)
}

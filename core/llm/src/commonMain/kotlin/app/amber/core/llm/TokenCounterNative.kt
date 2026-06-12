package app.amber.core.llm

internal expect object TokenCounterNative {
    fun countBatch(tokenizerIds: Array<String>, texts: Array<String>): IntArray?
}

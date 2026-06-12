package app.amber.core.llm

internal actual object TokenCounterNative {
    actual fun countBatch(tokenizerIds: Array<String>, texts: Array<String>): IntArray? = null
}

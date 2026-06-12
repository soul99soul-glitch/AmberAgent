package app.amber.core.llm

internal actual object TokenCounterNative {
    init {
        try {
            System.loadLibrary("tokenizer")
        } catch (_: UnsatisfiedLinkError) {
            // Native library not available — callers should fall back to JVM approximation
        }
    }

    @JvmStatic
    external actual fun countBatch(tokenizerIds: Array<String>, texts: Array<String>): IntArray?
}

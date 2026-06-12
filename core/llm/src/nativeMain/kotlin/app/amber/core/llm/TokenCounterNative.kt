package app.amber.core.llm

import app.amber.core.native.AmberNativeBridge

internal actual object TokenCounterNative {
    actual fun countBatch(tokenizerIds: Array<String>, texts: Array<String>): IntArray? {
        if (tokenizerIds.size != texts.size) return null
        val results = IntArray(tokenizerIds.size)
        for (i in tokenizerIds.indices) {
            val count = AmberNativeBridge.tokenizerCount(tokenizerIds[i], texts[i])
            if (count < 0) return null
            results[i] = count
        }
        return results
    }
}

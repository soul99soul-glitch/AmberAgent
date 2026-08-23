package app.amber.agent

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amber.agent.data.model.nativebridge.RegexTransformerNative
import app.amber.agent.ui.components.richtext.nativebridge.MarkdownParserNative
import app.amber.common.http.evaluateJsonExpr
import app.amber.core.json.expr.JsonExprNative
import app.amber.core.sync.core.SyncCrypto
import app.amber.core.sync.core.SyncCryptoNative
import app.amber.feature.deepread.nativebridge.ReaderExtractorNative
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only smoke benchmark for Rust native paths.
 *
 * This intentionally has no timing assertion: it is evidence collection, not a
 * flaky pass/fail threshold. Run a single class on a physical arm64 device and
 * read `NativePathDeviceBench` lines from logcat.
 */
@RunWith(AndroidJUnit4::class)
class NativePathDeviceBenchmarkTest {

    @After
    fun resetNativeConfigs() {
        JsonExprNative.config = JsonExprNative.DisabledConfig
        ReaderExtractorNative.config = ReaderExtractorNative.DisabledConfig
        SyncCryptoNative.config = SyncCryptoNative.DisabledConfig
    }

    @Test
    fun nativePathsProduceDeviceTimingSample() {
        assertTrue(
            "Expected arm64 device, got ${Build.SUPPORTED_ABIS.joinToString()}",
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" },
        )

        JsonExprNative.config = EnabledJsonExprConfig
        ReaderExtractorNative.config = EnabledReaderExtractorConfig
        SyncCryptoNative.config = EnabledSyncCryptoConfig

        val markdownNative = measure("markdown_html_native", iterations = 80) {
            MarkdownParserNative.renderHtml(markdownSample) ?: error("markdown native unavailable")
        }
        val markdownJvm = measure("markdown_html_jvm", iterations = 80) {
            renderMarkdownHtmlJvm(markdownSample)
        }
        assertNotNull(MarkdownParserNative.renderHtml("hello"))

        val jsonNative = measure("json_expr_native", iterations = 2_000) {
            evaluateJsonExpr("balance.total + bonuses[1] * 3", jsonRoot)
        }
        JsonExprNative.config = JsonExprNative.DisabledConfig
        val jsonJvm = measure("json_expr_jvm", iterations = 2_000) {
            evaluateJsonExpr("balance.total + bonuses[1] * 3", jsonRoot)
        }
        assertEquals("156", evaluateJsonExpr("balance.total + bonuses[1] * 3", jsonRoot))

        val regexNative = measure("regex_native", iterations = 1_000) {
            RegexTransformerNative.apply(regexInput, regexFind, regexReplace)
                ?: error("regex native unavailable")
        }
        val regexJvm = measure("regex_jvm", iterations = 1_000) {
            regexFind.indices.fold(regexInput) { acc, i ->
                acc.replace(Regex(regexFind[i]), regexReplace[i])
            }
        }
        assertEquals(
            regexFind.indices.fold(regexInput) { acc, i ->
                acc.replace(Regex(regexFind[i]), regexReplace[i])
            },
            RegexTransformerNative.apply(regexInput, regexFind, regexReplace),
        )

        val syncNativeCrypto = SyncCrypto(nativeEnabled = true)
        val syncJvmCrypto = SyncCrypto(nativeEnabled = false)
        val syncNative = measure("sync_sha256_native", iterations = 300) {
            syncNativeCrypto.sha256(syncBytes)
        }
        val syncJvm = measure("sync_sha256_jvm", iterations = 300) {
            syncJvmCrypto.sha256(syncBytes)
        }
        assertEquals(syncJvmCrypto.sha256(syncBytes), syncNativeCrypto.sha256(syncBytes))

        val readerNative = measure("reader_extractor_native", iterations = 80) {
            ReaderExtractorNative.extractOrNull(readerHtml, "https://example.com/article")
                ?: error("reader native unavailable")
        }
        assertNotNull(ReaderExtractorNative.extractOrNull(readerHtml, "https://example.com/article"))

        listOf(
            markdownNative,
            markdownJvm,
            jsonNative,
            jsonJvm,
            regexNative,
            regexJvm,
            syncNative,
            syncJvm,
            readerNative,
        ).forEach { result ->
            Log.i(TAG, result.toLogLine())
            println("${TAG}: ${result.toLogLine()}")
        }
    }

    private fun renderMarkdownHtmlJvm(text: String): String {
        val tree = markdownParser.buildMarkdownTreeFromString(text)
        return HtmlGenerator(text, tree, markdownFlavour).generateHtml()
    }

    private inline fun <T> measure(
        name: String,
        iterations: Int,
        block: () -> T,
    ): TimingResult {
        var checksum = 0
        repeat(10) { checksum = checksum xor block().hashCode() }
        val start = SystemClock.elapsedRealtimeNanos()
        repeat(iterations) { checksum = checksum xor block().hashCode() }
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - start
        return TimingResult(
            name = name,
            iterations = iterations,
            totalMs = elapsedNs / 1_000_000.0,
            avgUs = elapsedNs / iterations / 1_000.0,
            checksum = checksum,
        )
    }

    private data class TimingResult(
        val name: String,
        val iterations: Int,
        val totalMs: Double,
        val avgUs: Double,
        val checksum: Int,
    ) {
        fun toLogLine(): String =
            "$name iterations=$iterations totalMs=${"%.3f".format(totalMs)} " +
                "avgUs=${"%.3f".format(avgUs)} checksum=$checksum"
    }

    private object EnabledJsonExprConfig : JsonExprNative.Config {
        override fun enabled(): Boolean = true
        override fun onLoadFailure(error: Throwable) {
            throw AssertionError("json-expr failed to load", error)
        }
        override fun onNativePanic(stage: String, error: Throwable?) {
            throw AssertionError("json-expr native failure at $stage", error)
        }
    }

    private object EnabledReaderExtractorConfig : ReaderExtractorNative.Config {
        override fun enabled(): Boolean = true
        override fun onLoadFailure(error: Throwable) {
            throw AssertionError("reader-extractor failed to load", error)
        }
        override fun onNativePanic(stage: String, error: Throwable?) {
            throw AssertionError("reader-extractor native failure at $stage", error)
        }
    }

    private object EnabledSyncCryptoConfig : SyncCryptoNative.Config {
        override fun enabled(): Boolean = true
        override fun onLoadFailure(error: Throwable) {
            throw AssertionError("sync-crypto failed to load", error)
        }
        override fun onNativePanic(stage: String, error: Throwable?) {
            throw AssertionError("sync-crypto native failure at $stage", error)
        }
    }

    private companion object {
        private const val TAG = "NativePathDeviceBench"

        private val markdownFlavour = GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
        private val markdownParser = MarkdownParser(markdownFlavour)

        private val markdownSample = buildString {
            repeat(12) { i ->
                appendLine("## Section $i")
                appendLine("Streaming markdown with **bold**, [link](https://example.com), and CJK 中文内容。")
                appendLine()
                appendLine("```kotlin")
                appendLine("fun render$i(value: String) = value.trim().uppercase()")
                appendLine("```")
                appendLine()
            }
        }

        private val jsonRoot = JsonObject(
            mapOf(
                "balance" to JsonObject(mapOf("total" to JsonPrimitive(120))),
                "bonuses" to kotlinx.serialization.json.JsonArray(
                    listOf(JsonPrimitive(5), JsonPrimitive(12)),
                ),
            ),
        )

        private val regexInput = buildString {
            repeat(80) { i ->
                append("item-$i status=todo priority=${i % 3}; ")
            }
        }
        private val regexFind = arrayOf("status=todo", "priority=([0-9])", "item-([0-9]+)")
        private val regexReplace = arrayOf("status=done", "p-$1", "row-$1")

        private val syncBytes = ByteArray(256 * 1024) { index -> (index * 31).toByte() }

        private val readerHtml = """
            <html><head><title>Native path article</title></head><body>
            <article>
              <h1>Native path article</h1>
              <p>This is a long article paragraph intended to exercise readability extraction on device.</p>
              <p>It includes enough text for the extractor to keep the section and produce readable content.</p>
              <p>中文段落也会保留，用来确认 UTF-8 内容在 JNI 往返时不被破坏。</p>
            </article>
            </body></html>
        """.trimIndent()
    }
}

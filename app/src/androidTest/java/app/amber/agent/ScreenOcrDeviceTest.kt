package app.amber.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.automation.CapturedScreenBitmap
import app.amber.core.automation.DefaultScreenOcrEngine
import app.amber.core.automation.MlKitScreenTextRecognizer
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenOcrMode
import app.amber.core.automation.ScreenOcrOutcome
import app.amber.core.automation.ScreenOcrSource
import app.amber.core.automation.ScreenSnapshot
import app.amber.core.automation.ScreenOcrCapabilities
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Partial on-device OCR evidence: offscreen WebView/Canvas `view.draw` into owned ML Kit
 * `text-recognition-chinese` 16.0.1. Does **not** go through production
 * `captureFreshBitmap` / Accessibility screenshot.
 *
 * Full production capture is [ScreenOcrProductionCaptureDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class ScreenOcrDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun webViewPixelsAreRecognizedAsBitmapOcrNotAccessibilityLabels() {
        recognizeFixture(ScreenOcrFixtureActivity.MODE_WEBVIEW, "webview")
    }

    @Test
    fun canvasPixelsAreRecognizedAsBitmapOcrNotAccessibilityLabels() {
        recognizeFixture(ScreenOcrFixtureActivity.MODE_CANVAS, "canvas")
    }

    @Test
    fun sufficientAccessibilityTreeSkipsScreenshotAndOcr() = runBlocking {
        val screenshotCalls = AtomicInteger(0)
        val engine = DefaultScreenOcrEngine(
            displaySizeProvider = { 1080 to 1920 },
            screenshotProvider = { _, _ ->
                screenshotCalls.incrementAndGet()
                error("sufficient tree must not capture")
            },
            bitmapRecognizer = { error("sufficient tree must not OCR") },
        )
        val snapshot = ScreenSnapshot(
            id = "sufficient",
            packageName = "app.amber.agent.ocrfixture",
            windowId = 1,
            nodes = listOf(
                node("0/0", "首页", clickable = true),
                node("0/1", "搜索商品", editable = true),
                node("0/2", "推荐列表", scrollable = true),
                node("0/3", "立即查看详情", clickable = true),
            ),
        )
        val observation = engine.observe(snapshot = snapshot, mode = ScreenOcrMode.AUTO)
        assertFalse(observation.triggered)
        assertEquals("tree_coverage_sufficient", observation.triggerReason)
        assertEquals(ScreenOcrOutcome.NOT_TRIGGERED, observation.outcome)
        assertEquals(ScreenOcrSource.UNAVAILABLE, observation.source)
        assertTrue(observation.blocks.isEmpty())
        assertNull(observation.screenshotSource)
        assertEquals(0, screenshotCalls.get())
        writeEvidence("sufficient-tree.txt", "triggered=${observation.triggered} reason=${observation.triggerReason} shots=${screenshotCalls.get()}\n")
    }

    @Test
    fun missingScreenshotIsUnavailableWithHonestDiagnostic() = runBlocking {
        val engine = DefaultScreenOcrEngine(
            displaySizeProvider = { 1080 to 1920 },
            screenshotProvider = { _, _ -> null },
            bitmapRecognizer = { error("no bitmap should be recognized") },
        )
        val observation = engine.observe(
            snapshot = ScreenSnapshot(
                id = "empty",
                packageName = "app.amber.agent.ocrfixture",
                windowId = 1,
                nodes = emptyList(),
            ),
            mode = ScreenOcrMode.ON,
        )
        assertTrue(observation.triggered)
        assertEquals(ScreenOcrOutcome.UNAVAILABLE, observation.outcome)
        assertEquals(ScreenOcrSource.UNAVAILABLE, observation.source)
        assertTrue(observation.diagnostics.contains("screenshot_unavailable"))
        assertTrue(observation.blocks.isEmpty())
        writeEvidence("screenshot-unavailable.txt", "diagnostics=${observation.diagnostics}\n")
    }

    private fun recognizeFixture(mode: String, evidenceName: String) {
        // Off-screen WebView/Canvas: real ML Kit on pixels. Not the production capture path.
        val width = 1080
        val height = 1600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val ready = CountDownLatch(1)
        var host: View? = null
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val view = if (mode == ScreenOcrFixtureActivity.MODE_CANVAS) {
                ScreenOcrFixtureActivity.createCanvasView(context)
            } else {
                ScreenOcrFixtureActivity.createWebView(context) {}
            }
            val specW = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val specH = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            view.measure(specW, specH)
            view.layout(0, 0, width, height)
            host = view
            ready.countDown()
        }
        check(ready.await(8, TimeUnit.SECONDS)) { "fixture $mode did not become ready" }
        Thread.sleep(if (mode == ScreenOcrFixtureActivity.MODE_WEBVIEW) 1500 else 200)
        instrumentation.runOnMainSync {
            val view = requireNotNull(host)
            view.draw(Canvas(bitmap))
        }
        savePng(bitmap, "$evidenceName-source.png")
        val axDump = dumpAxText(instrumentation.uiAutomation.rootInActiveWindow)
        writeEvidence("$evidenceName-ax.txt", axDump)
        try {
            val screenshotCalls = AtomicInteger(0)
            val engine = DefaultScreenOcrEngine(
                displaySizeProvider = { bitmap.width to bitmap.height },
                screenshotProvider = { _, _ ->
                    screenshotCalls.incrementAndGet()
                    CapturedScreenBitmap(
                        bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                        source = "offscreen_view_draw",
                        width = bitmap.width,
                        height = bitmap.height,
                    )
                },
                bitmapRecognizer = null,
                accessibilityRegionsProvider = { _, _, _ -> emptyList() },
                recognizerId = ScreenOcrCapabilities.RECOGNIZER_ID,
                ownedRecognizer = MlKitScreenTextRecognizer(),
            )
            val observation = runBlocking {
                withTimeout(25_000) {
                    engine.observe(
                        snapshot = ScreenSnapshot(
                            id = "ocr-$mode",
                            packageName = instrumentation.context.packageName,
                            windowId = 1,
                            nodes = listOf(
                                ScreenNode(
                                    ref = "0/0",
                                    label = "",
                                    viewId = "",
                                    className = if (mode == ScreenOcrFixtureActivity.MODE_CANVAS) {
                                        "android.view.View"
                                    } else {
                                        "android.webkit.WebView"
                                    },
                                    left = 0,
                                    top = 0,
                                    right = bitmap.width,
                                    bottom = bitmap.height,
                                    clickable = false,
                                    editable = false,
                                    scrollable = false,
                                    enabled = true,
                                ),
                            ),
                        ),
                        uiTreeDump = if (mode == ScreenOcrFixtureActivity.MODE_CANVAS) "canvas" else "webview",
                        mode = ScreenOcrMode.ON,
                    )
                }
            }
            engine.close()
            val joined = observation.blocks
                .filter { it.source == ScreenOcrSource.BITMAP_OCR }
                .joinToString("\n") { it.text }
            writeEvidence(
                "$evidenceName-ocr.txt",
                buildString {
                    appendLine("source=${observation.source}")
                    appendLine("outcome=${observation.outcome}")
                    appendLine("screenshotSource=${observation.screenshotSource}")
                    appendLine("recognizer=${observation.recognizer}")
                    appendLine("diagnostics=${observation.diagnostics}")
                    appendLine("screenshotCalls=${screenshotCalls.get()}")
                    appendLine("blocks=${observation.blocks.size}")
                    observation.blocks.forEach { block ->
                        appendLine("${block.source}\t${block.left},${block.top},${block.right},${block.bottom}\t${block.text}")
                    }
                    appendLine("joined=$joined")
                    appendLine("axHasChinese=${axDump.contains(ScreenOcrFixtureActivity.CHINESE)}")
                    appendLine("axHasLatin=${axDump.contains(ScreenOcrFixtureActivity.LATIN)}")
                },
            )
            assertEquals(ScreenOcrSource.BITMAP_OCR, observation.source)
            assertEquals(ScreenOcrOutcome.RECOGNIZED, observation.outcome)
            assertEquals("offscreen_view_draw", observation.screenshotSource)
            assertEquals(ScreenOcrCapabilities.RECOGNIZER_ID, observation.recognizer)
            assertTrue(observation.blocks.isNotEmpty())
            assertTrue(observation.blocks.all { it.source == ScreenOcrSource.BITMAP_OCR })
            assertTrue("Chinese missing in $joined", joined.contains("琥珀") && joined.contains("验证"))
            assertTrue("Latin missing in $joined", joined.contains("OCR-LATIN-MARK") || joined.contains("LATIN"))
            val clickable = observation.blocks.firstOrNull { block ->
                block.text.contains("立即") || block.text.contains("查看") || block.text.contains("详情")
            }
            assertTrue("clickable region missing in $joined", clickable != null)
            assertTrue(clickable!!.right > clickable.left && clickable.bottom > clickable.top)
            assertFalse(
                "AX labels must not supply the OCR strings",
                axDump.contains(ScreenOcrFixtureActivity.CHINESE) &&
                    axDump.contains(ScreenOcrFixtureActivity.LATIN),
            )
            assertEquals(1, screenshotCalls.get())
        } finally {
            if (host is WebView) {
                instrumentation.runOnMainSync { (host as WebView).destroy() }
            }
        }
    }

    private fun dumpAxText(node: AccessibilityNodeInfo?): String {
        if (node == null) return "(no-root)"
        val out = StringBuilder()
        fun walk(current: AccessibilityNodeInfo, depth: Int) {
            val text = listOfNotNull(
                current.text?.toString(),
                current.contentDescription?.toString(),
                current.hintText?.toString(),
            ).filter { it.isNotBlank() }
            if (text.isNotEmpty()) {
                out.append("  ".repeat(depth)).append(current.className).append(' ')
                    .append(text.joinToString(" | ")).append('\n')
            }
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }
        walk(node, 0)
        return out.toString()
    }

    private fun evidenceDir(): File {
        val dir = instrumentation.targetContext.getExternalFilesDir("amber-s9")
            ?: File(instrumentation.targetContext.filesDir, "amber-s9")
        if (!dir.exists()) check(dir.mkdirs())
        return dir
    }

    private fun savePng(bitmap: Bitmap, name: String) {
        val file = File(evidenceDir(), name)
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        println("ScreenOcrDeviceTest png=${file.absolutePath}")
    }

    private fun writeEvidence(name: String, text: String) {
        val file = File(evidenceDir(), name)
        file.writeText(text)
        println("ScreenOcrDeviceTest evidence=${file.absolutePath}")
    }

    private fun node(
        ref: String,
        label: String,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
    ) = ScreenNode(
        ref = ref,
        label = label,
        viewId = "id/$ref",
        className = "android.widget.TextView",
        left = 10,
        top = 10,
        right = 200,
        bottom = 80,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        enabled = true,
    )
}

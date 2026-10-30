package app.amber.core.automation

import app.amber.core.jev.JevClient
import app.amber.core.jev.JevDataScope
import app.amber.core.jev.JevDecisionCoordinator
import app.amber.core.jev.JevHttpRequest
import app.amber.core.jev.JevMode
import app.amber.core.jev.JevPurpose
import app.amber.core.jev.JevRuntime
import app.amber.core.jev.JevScreenGoalRunner
import app.amber.core.jev.JevSetting
import app.amber.core.jev.JevTransport
import app.amber.core.jev.JevTransportResponse
import app.amber.core.jev.JevUsageStore
import app.amber.core.settings.Settings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOcrFallbackTest {

    private fun sampleNode(
        ref: String,
        label: String = "",
        className: String = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        left: Int = 100,
        top: Int = 200,
        right: Int = 300,
        bottom: Int = 280,
    ) = ScreenNode(
        ref = ref,
        label = label,
        viewId = "id/$ref",
        className = className,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        enabled = true,
    )

    @Test
    fun normalAccessibilityTreeCoverageSkipsScreenshotAndOcrByDefault() = runTest {
        var screenshotCalls = 0
        var ocrCalls = 0
        val engine = DefaultScreenOcrEngine(
            displaySizeProvider = { 1080 to 2400 },
            screenshotProvider = { w, h ->
                screenshotCalls++
                CapturedScreenBitmap(bitmap = null, source = "test", width = w, height = h)
            },
            recognizer = { _, _, _, _ ->
                ocrCalls++
                listOf(RawOcrRegion("Should not run", 0, 0, 100, 100))
            },
        )
        val snapshot = ScreenSnapshot(
            id = "normal-tree",
            packageName = "com.example.app",
            windowId = 1,
            nodes = listOf(
                sampleNode("0/0", label = "首页", clickable = true),
                sampleNode("0/1", label = "搜索商品", editable = true),
                sampleNode("0/2", label = "推荐列表", scrollable = true),
                sampleNode("0/3", label = "立即查看详情", clickable = true),
            ),
        )

        val observation = engine.observe(snapshot = snapshot, mode = ScreenOcrMode.AUTO)

        assertFalse(observation.triggered)
        assertEquals("tree_coverage_sufficient", observation.triggerReason)
        assertFalse(observation.coverage.isLowCoverage)
        assertEquals(0, screenshotCalls)
        assertEquals(0, ocrCalls)
        assertTrue(observation.blocks.isEmpty())
    }

    @Test
    fun lowCoverageWebViewOrCanvasAutomaticallyTriggersOcrAndNormalizesCoordinates() = runTest {
        var screenshotCalls = 0
        var ocrCalls = 0
        val engine = DefaultScreenOcrEngine(
            displaySizeProvider = { 1000 to 2000 },
            screenshotProvider = { w, h ->
                screenshotCalls++
                CapturedScreenBitmap(bitmap = null, source = "accessibility_screenshot", width = w, height = h)
            },
            recognizer = { _, _, _, _ ->
                ocrCalls++
                listOf(
                    RawOcrRegion("确认订单详情", left = 100, top = 400, right = 500, bottom = 500, confidence = 0.95f),
                    RawOcrRegion("验证码 849201", left = 200, top = 800, right = 600, bottom = 900, confidence = 0.91f),
                )
            },
        )
        val lowCoverageSnapshot = ScreenSnapshot(
            id = "webview-low",
            packageName = "com.example.webview",
            windowId = 2,
            nodes = listOf(
                sampleNode("0/0", label = "", className = "android.webkit.WebView"),
                sampleNode("0/1", label = "", className = "android.view.View", clickable = true),
                sampleNode("0/2", label = "", className = "android.view.View", clickable = true),
            ),
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot, mode = ScreenOcrMode.AUTO)

        assertTrue(observation.triggered)
        assertEquals("no_visible_text", observation.triggerReason)
        assertTrue(observation.coverage.isLowCoverage)
        assertEquals(1, screenshotCalls)
        assertEquals(1, ocrCalls)
        assertEquals(2, observation.blocks.size)

        val first = observation.blocks[0]
        assertEquals("确认订单详情", first.text)
        assertEquals(0.1f, first.normalizedBounds.left, 0.001f)
        assertEquals(0.2f, first.normalizedBounds.top, 0.001f)
        assertEquals(0.5f, first.normalizedBounds.right, 0.001f)
        assertEquals(0.25f, first.normalizedBounds.bottom, 0.001f)
        assertEquals(0.3f, first.normalizedBounds.centerX, 0.001f)
        assertEquals(0.225f, first.normalizedBounds.centerY, 0.001f)
        assertFalse(first.isSensitive)

        val second = observation.blocks[1]
        assertTrue(second.isSensitive)
        assertEquals("验证码 ******", SensitiveScreenTextClassifier.redactIfSensitive(second.text))
    }

    @Test
    fun explicitModelModeOverridesAutoCoverageDecision() = runTest {
        var ocrCalls = 0
        val engine = DefaultScreenOcrEngine(
            displaySizeProvider = { 1080 to 2400 },
            screenshotProvider = { w, h -> CapturedScreenBitmap(null, "test", w, h) },
            recognizer = { _, _, _, _ ->
                ocrCalls++
                listOf(RawOcrRegion("显式识别文字", left = 270, top = 600, right = 810, bottom = 720))
            },
        )
        val healthySnapshot = ScreenSnapshot(
            id = "healthy",
            packageName = "com.example.app",
            windowId = 1,
            nodes = listOf(
                sampleNode("0/0", label = "标题一"),
                sampleNode("0/1", label = "标题二"),
                sampleNode("0/2", label = "标题三"),
            ),
        )

        // 1. Explicit ON forces OCR even when coverage is healthy
        val forced = engine.observe(
            snapshot = healthySnapshot,
            mode = ScreenOcrMode.parse("on"),
        )
        assertTrue(forced.triggered)
        assertEquals("explicit_model_request", forced.triggerReason)
        assertEquals(1, ocrCalls)
        assertEquals(1, forced.blocks.size)

        // 2. Explicit OFF suppresses OCR even when tree is empty
        val emptySnapshot = ScreenSnapshot("empty", "com.example.app", 1, emptyList())
        val suppressed = engine.observe(
            snapshot = emptySnapshot,
            mode = ScreenOcrMode.parse("off"),
        )
        assertFalse(suppressed.triggered)
        assertEquals("disabled_by_caller", suppressed.triggerReason)
        assertEquals(1, ocrCalls)
    }

    @Test
    fun jevScreenGoalRunnerAttachesStructuredOcrAndNormalizedCoordsOnLowCoverage() = runTest {
        var ocrCalls = 0
        val ocrEngine = DefaultScreenOcrEngine(
            displaySizeProvider = { 1000 to 2000 },
            screenshotProvider = { w, h -> CapturedScreenBitmap(null, "test", w, h) },
            recognizer = { _, _, _, _ ->
                ocrCalls++
                listOf(
                    RawOcrRegion("自绘页面已完成加载", left = 100, top = 500, right = 700, bottom = 600, confidence = 0.94f),
                )
            },
        )
        var capturedRequestJson = ""
        val transport = JevTransport { req: JevHttpRequest ->
            capturedRequestJson = req.body
            JevTransportResponse.Http(
                code = 200,
                body = """{"model":"jev-test","answers":{
                    "action":{"type":"choice","choice":"done","confidence":0.95},
                    "done":{"type":"noul","noul":0.96}
                }}""".toByteArray(),
                retryAfterHeader = null,
            )
        }
        val runtime = JevRuntime(
            coordinator = JevDecisionCoordinator(
                client = JevClient(transport),
                apiKeyProvider = { "test-key" },
                usageStore = JevUsageStore.IN_MEMORY,
                clock = { 1_000_000L },
            ),
            settingsProvider = {
                Settings(
                    jev = JevSetting(
                        enabled = true,
                        purposes = mapOf(JevPurpose.SCREEN_AUTOMATION to JevMode.ACTIVE),
                        dataScopes = setOf(JevDataScope.SCREEN_CONTENT, JevDataScope.TASK_TEXT),
                    )
                )
            },
        )
        val runner = JevScreenGoalRunner(
            runtime = runtime,
            mainDispatcher = StandardTestDispatcher(testScheduler),
            settle = {},
            ocrEngine = ocrEngine,
        )
        val webViewSnapshot = ScreenSnapshot(
            id = "webview-1",
            packageName = "com.example.canvas",
            windowId = 1,
            nodes = listOf(
                sampleNode("0/0", label = "", className = "android.webkit.WebView"),
            ),
        )
        val controller = object : AccessibilityController {
            override fun captureScreenSnapshot(): ScreenSnapshot = webViewSnapshot
            override suspend fun tap(x: Float, y: Float, durationMillis: Long) = true
            override suspend fun longPress(x: Float, y: Float, durationMillis: Long) = true
            override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long) = true
            override fun setFocusedText(text: String) = true
            override fun dumpUiTree(maxNodes: Int) = ""
            override fun findTextNodes(query: String, maxNodes: Int) = emptyList<AccessibilityTextMatch>()
            override fun back() = true
            override fun home() = true
        }

        val result = runner.run(
            controller = controller,
            packageName = "com.example.canvas",
            goal = "确认自绘页面加载完成",
            texts = emptyList(),
            runKey = "ocr-goal-run",
            ocrMode = ScreenOcrMode.AUTO,
        )

        assertEquals("completed", result["status"]!!.jsonPrimitive.content)
        assertEquals("true", result["ocr_triggered"]!!.jsonPrimitive.content)
        assertEquals("1", result["ocr_block_count"]!!.jsonPrimitive.content)
        assertTrue(capturedRequestJson.contains("自绘页面已完成加载"))
        val ocrBlock = result["ocr_blocks"]!!.jsonArray.single().jsonObject
        assertEquals("自绘页面已完成加载", ocrBlock["text"]!!.jsonPrimitive.content)
        val norm = ocrBlock["normalized_bounds"]!!.jsonObject
        assertEquals(0.4, norm["center_x"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(0.275, norm["center_y"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(1, ocrCalls)
    }
}

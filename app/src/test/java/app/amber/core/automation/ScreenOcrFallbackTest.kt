package app.amber.core.automation

import android.app.Application
import android.graphics.Bitmap
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenOcrFallbackTest {

    private fun bitmap(width: Int = 1000, height: Int = 2000): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun engine(
        width: Int = 1000,
        height: Int = 2000,
        recognizer: (suspend (Bitmap) -> BitmapOcrResult)? = null,
        accessibility: (ScreenSnapshot?, Int, Int) -> List<RawOcrRegion> = { _, _, _ -> emptyList() },
        screenshot: suspend (Int, Int) -> CapturedScreenBitmap? = { w, h ->
            CapturedScreenBitmap(bitmap = bitmap(w, h), source = "test", width = w, height = h)
        },
    ) = DefaultScreenOcrEngine(
        displaySizeProvider = { width to height },
        screenshotProvider = screenshot,
        bitmapRecognizer = recognizer,
        accessibilityRegionsProvider = accessibility,
    )

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

    private fun lowCoverageSnapshot() = ScreenSnapshot(
        id = "webview-low",
        packageName = "com.example.webview",
        windowId = 2,
        nodes = listOf(
            sampleNode("0/0", label = "", className = "android.webkit.WebView"),
            sampleNode("0/1", label = "", className = "android.view.View", clickable = true),
            sampleNode("0/2", label = "", className = "android.view.View", clickable = true),
        ),
    )

    @Test
    fun normalAccessibilityTreeCoverageSkipsScreenshotAndOcrByDefault() = runTest {
        var screenshotCalls = 0
        var ocrCalls = 0
        val engine = engine(
            recognizer = {
                ocrCalls++
                BitmapOcrResult(listOf(RawOcrRegion("Should not run", 0, 0, 100, 100)))
            },
            screenshot = { w, h ->
                screenshotCalls++
                CapturedScreenBitmap(bitmap = bitmap(w, h), source = "test", width = w, height = h)
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
        assertEquals(ScreenOcrOutcome.NOT_TRIGGERED, observation.outcome)
        assertEquals(ScreenOcrSource.UNAVAILABLE, observation.source)
        assertNull(observation.screenshotSource)
    }

    @Test
    fun lowCoverageWebViewOrCanvasAutomaticallyTriggersOcrAndNormalizesCoordinates() = runTest {
        var screenshotCalls = 0
        var ocrCalls = 0
        val engine = engine(
            recognizer = {
                ocrCalls++
                BitmapOcrResult(
                    listOf(
                        RawOcrRegion("确认订单详情", left = 100, top = 400, right = 500, bottom = 500, confidence = 0.95f),
                        RawOcrRegion("验证码 849201", left = 200, top = 800, right = 600, bottom = 900, confidence = 0.91f),
                    ),
                )
            },
            screenshot = { w, h ->
                screenshotCalls++
                CapturedScreenBitmap(bitmap = bitmap(w, h), source = "accessibility_screenshot", width = w, height = h)
            },
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        assertTrue(observation.triggered)
        assertEquals("no_visible_text", observation.triggerReason)
        assertTrue(observation.coverage.isLowCoverage)
        assertEquals(1, screenshotCalls)
        assertEquals(1, ocrCalls)
        assertEquals(2, observation.blocks.size)
        assertEquals(ScreenOcrOutcome.RECOGNIZED, observation.outcome)
        assertEquals(ScreenOcrSource.BITMAP_OCR, observation.source)
        assertEquals("accessibility_screenshot", observation.screenshotSource)
        assertTrue(observation.diagnostics.isEmpty())
        assertTrue(observation.blocks.all { it.source == ScreenOcrSource.BITMAP_OCR })

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
        // Model/receipt-facing text must not leak the OTP digits.
        assertFalse(observation.redactedText().contains("849201"))
    }

    @Test
    fun chineseAndLatinBlocksAreBothRecognizedFromOneBitmapPass() = runTest {
        var ocrCalls = 0
        val engine = engine(
            recognizer = {
                ocrCalls++
                BitmapOcrResult(
                    listOf(
                        RawOcrRegion("设置", 40, 100, 300, 160),
                        RawOcrRegion("Wi-Fi", 40, 200, 300, 260),
                        RawOcrRegion("Battery 82%", 40, 300, 500, 360),
                    ),
                )
            },
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        assertEquals(1, ocrCalls)
        assertEquals(listOf("设置", "Wi-Fi", "Battery 82%"), observation.blocks.map { it.text })
        assertEquals(ScreenOcrOutcome.RECOGNIZED, observation.outcome)
        assertTrue(observation.isBitmapOcr)
    }

    @Test
    fun explicitModelModeOverridesAutoCoverageDecision() = runTest {
        var ocrCalls = 0
        var screenshotCalls = 0
        val engine = engine(
            recognizer = {
                ocrCalls++
                BitmapOcrResult(listOf(RawOcrRegion("显式识别文字", left = 270, top = 600, right = 810, bottom = 720)))
            },
            screenshot = { w, h ->
                screenshotCalls++
                CapturedScreenBitmap(bitmap = bitmap(w, h), source = "test", width = w, height = h)
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
        assertEquals(1, screenshotCalls)
        assertEquals(ScreenOcrOutcome.NOT_TRIGGERED, suppressed.outcome)
    }

    @Test
    fun missingBitmapRecognizerDegradesToExtendedAccessibilityExplicitly() = runTest {
        val engine = engine(
            recognizer = null,
            accessibility = { _, _, _ ->
                listOf(RawOcrRegion("扩展无障碍标签", 100, 200, 400, 260, confidence = 0.85f))
            },
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        assertTrue(observation.triggered)
        assertEquals(ScreenOcrOutcome.DEGRADED, observation.outcome)
        assertEquals(ScreenOcrSource.EXTENDED_ACCESSIBILITY, observation.source)
        assertFalse(observation.isBitmapOcr)
        assertTrue(observation.diagnostics.contains("bitmap_recognizer_unavailable"))
        assertEquals(1, observation.blocks.size)
        assertEquals(ScreenOcrSource.EXTENDED_ACCESSIBILITY, observation.blocks.single().source)
        assertNull(observation.recognizer)
    }

    @Test
    fun recognizerFailureIsReportedAsDegradedNotAsEmptySuccess() = runTest {
        val engine = engine(
            recognizer = { BitmapOcrResult(failure = "model_not_found") },
            accessibility = { _, _, _ ->
                listOf(RawOcrRegion("回退标签", 100, 200, 400, 260))
            },
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        assertEquals(ScreenOcrOutcome.DEGRADED, observation.outcome)
        assertEquals(ScreenOcrSource.EXTENDED_ACCESSIBILITY, observation.source)
        assertTrue(observation.diagnostics.contains("model_not_found"))
        assertEquals(1, observation.blocks.size)
    }

    @Test
    fun unavailableScreenshotAndEmptyFallbackReportDiagnostics() = runTest {
        var ocrCalls = 0
        val engine = engine(
            recognizer = {
                ocrCalls++
                BitmapOcrResult(listOf(RawOcrRegion("不应出现", 10, 10, 100, 50)))
            },
            screenshot = { _, _ -> null },
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        assertTrue(observation.triggered)
        assertEquals(0, ocrCalls)
        assertEquals(ScreenOcrOutcome.UNAVAILABLE, observation.outcome)
        assertEquals(ScreenOcrSource.UNAVAILABLE, observation.source)
        assertTrue(observation.blocks.isEmpty())
        assertTrue(observation.diagnostics.contains("screenshot_unavailable"))
        assertNull(observation.screenshotSource)
    }

    @Test
    fun recognizerFoundNoTextIsDiagnosedInsteadOfSilentSuccess() = runTest {
        val engine = engine(recognizer = { BitmapOcrResult(emptyList()) })

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        assertEquals(ScreenOcrOutcome.UNAVAILABLE, observation.outcome)
        assertTrue(observation.diagnostics.contains("ocr_no_text"))
        assertEquals(ScreenOcrSource.UNAVAILABLE, observation.source)
    }

    @Test
    fun capturedBitmapIsRecycledAfterRecognition() = runTest {
        val frame = bitmap()
        var recycledDuringRecognition = true
        val engine = engine(
            recognizer = { bmp ->
                recycledDuringRecognition = bmp.isRecycled
                BitmapOcrResult(listOf(RawOcrRegion("完", 10, 10, 100, 50)))
            },
            screenshot = { _, _ -> CapturedScreenBitmap(frame, "test", frame.width, frame.height) },
        )

        engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        assertFalse(recycledDuringRecognition)
        assertTrue(frame.isRecycled)
    }

    @Test
    fun cancellingObservationRecyclesBitmapAndReturnsNoStaleBlocks() = runTest {
        val frame = bitmap()
        var recycledDuringRecognition = true
        val engine = engine(
            recognizer = { bmp ->
                recycledDuringRecognition = bmp.isRecycled
                delay(100)
                BitmapOcrResult(listOf(RawOcrRegion("旧帧文字", 10, 10, 200, 60)))
            },
            screenshot = { _, _ -> CapturedScreenBitmap(frame, "test", frame.width, frame.height) },
        )

        val job = launch { engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO) }
        advanceTimeBy(10)
        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertFalse(recycledDuringRecognition)
        assertTrue(frame.isRecycled)
    }

    @Test
    fun productionEngineWithoutCaptureSessionFailsExplicitly() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val engine = DefaultScreenOcrEngine(context, ScreenCaptureManager(context))
        try {
            val off = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.OFF)
            assertEquals(ScreenOcrOutcome.NOT_TRIGGERED, off.outcome)
            assertNull(off.screenshotSource)

            // ON with no Accessibility service / no MediaProjection session: must report a
            // diagnosable degradation instead of pretending OCR succeeded with zero blocks.
            val on = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.ON)
            assertTrue(on.triggered)
            assertEquals(ScreenOcrOutcome.UNAVAILABLE, on.outcome)
            assertTrue(on.diagnostics.contains("screenshot_unavailable"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun mixedBitmapAndAccessibilityResultsGetUniqueIdsAndKeepSources() = runTest {
        val shared = RawOcrRegion("共用文本", 100, 100, 300, 160)
        val engine = engine(
            recognizer = {
                BitmapOcrResult(
                    listOf(
                        shared,
                        RawOcrRegion("位图独有", 100, 200, 300, 260),
                    ),
                )
            },
            accessibility = { _, _, _ ->
                listOf(
                    shared,
                    RawOcrRegion("无障碍独有", 100, 300, 300, 360),
                )
            },
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        assertEquals(3, observation.blocks.size)
        assertEquals(observation.blocks.size, observation.blocks.map { it.id }.toSet().size)
        assertEquals(1, observation.blocks.count { it.text == "共用文本" })
        assertEquals(ScreenOcrSource.BITMAP_OCR, observation.blocks.single { it.text == "共用文本" }.source)
        assertEquals(1, observation.blocks.count { it.source == ScreenOcrSource.EXTENDED_ACCESSIBILITY })
        assertTrue(observation.blocks.all { it.id.startsWith("ocr_") })
        assertEquals(ScreenOcrOutcome.RECOGNIZED, observation.outcome)
    }

    @Test
    fun combinedBlockCountIsCappedAtSixtyFourWithUniqueIds() = runTest {
        val many = (0 until 80).map { index ->
            RawOcrRegion("共测-$index", 10, index * 10, 200, index * 10 + 8)
        }
        val engine = engine(
            recognizer = { BitmapOcrResult(many) },
            accessibility = { _, _, _ -> many },
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        assertEquals(64, observation.blocks.size)
        assertEquals(64, observation.blocks.map { it.id }.toSet().size)
        assertTrue(observation.blocks.all { it.source == ScreenOcrSource.BITMAP_OCR })
    }

    @Test
    fun bitmapRegionsWithInvalidBoundsDoNotCountAsRecognized() = runTest {
        val engine = engine(
            recognizer = {
                BitmapOcrResult(listOf(RawOcrRegion("无效位图文本", 500, 100, 500, 160)))
            },
            accessibility = { _, _, _ ->
                listOf(RawOcrRegion("有效无障碍文本", 100, 100, 400, 160))
            },
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        assertEquals(ScreenOcrOutcome.DEGRADED, observation.outcome)
        assertEquals(ScreenOcrSource.EXTENDED_ACCESSIBILITY, observation.source)
        assertTrue(observation.blocks.none { it.source == ScreenOcrSource.BITMAP_OCR })
        assertTrue(observation.diagnostics.contains("ocr_no_text"))
    }

    @Test
    fun standaloneFiveDigitOtpIsRedactedWithoutKeyword() = runTest {
        val engine = engine(
            recognizer = {
                BitmapOcrResult(listOf(RawOcrRegion("38491", 280, 100, 380, 160)))
            },
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        val value = observation.blocks.single()
        assertEquals("38491", value.text)
        assertTrue(value.isSensitive)
        assertEquals("******", value.redactedText)
        assertFalse(observation.redactedText().contains("38491"))
    }

    @Test
    fun splitOtpLabelAndStandaloneFourDigitValueAreBothRedacted() = runTest {
        val engine = engine(
            recognizer = {
                BitmapOcrResult(
                    listOf(
                        RawOcrRegion("验证码", 100, 100, 260, 160),
                        RawOcrRegion("8492", 280, 100, 380, 160),
                    ),
                )
            },
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        val label = observation.blocks.single { it.text == "验证码" }
        val value = observation.blocks.single { it.text == "8492" }
        assertTrue(label.isSensitive)
        assertTrue(value.isSensitive)
        assertEquals("******", value.redactedText)
        assertFalse(observation.redactedText().contains("8492"))
        assertTrue(observation.redactedText().contains("******"))
    }

    @Test
    fun ocrOverPasswordLabeledNodeIsMaskedWithoutSecretKeywords() = runTest {
        val snapshot = ScreenSnapshot(
            id = "password-label-screen",
            packageName = "com.example.app",
            windowId = 1,
            nodes = listOf(
                sampleNode(
                    "pw",
                    label = "密码",
                    editable = true,
                    left = 100,
                    top = 300,
                    right = 500,
                    bottom = 360,
                ),
            ),
        )
        val engine = engine(
            recognizer = {
                BitmapOcrResult(listOf(RawOcrRegion("hunter2", 120, 310, 400, 350)))
            },
        )

        val observation = engine.observe(snapshot = snapshot, mode = ScreenOcrMode.ON)

        val block = observation.blocks.single()
        assertTrue(block.isSensitive)
        assertEquals(SensitiveScreenTextClassifier.MASKED_SENSITIVE_TEXT, block.redactedText)
        assertFalse(observation.redactedText().contains("hunter2"))
        assertFalse(SensitiveScreenTextClassifier.isSensitive("hunter2"))
    }

    @Test
    fun ocrTextOverPasswordNodeBoundsIsMaskedWithoutKeywords() = runTest {
        val snapshot = ScreenSnapshot(
            id = "password-screen",
            packageName = "com.example.app",
            windowId = 1,
            nodes = listOf(
                sampleNode(
                    "pw",
                    label = "[password]",
                    editable = true,
                    left = 100,
                    top = 300,
                    right = 500,
                    bottom = 360,
                ),
            ),
        )
        val engine = engine(
            recognizer = {
                BitmapOcrResult(listOf(RawOcrRegion("hunter2", 120, 310, 400, 350)))
            },
        )

        val observation = engine.observe(snapshot = snapshot, mode = ScreenOcrMode.AUTO)

        val block = observation.blocks.single()
        assertTrue(block.isSensitive)
        assertEquals(SensitiveScreenTextClassifier.MASKED_SENSITIVE_TEXT, block.redactedText)
        assertFalse(observation.redactedText().contains("hunter2"))
    }

    @Test
    fun tokenLikeValueNextToSensitiveLabelIsMasked() = runTest {
        val engine = engine(
            recognizer = {
                BitmapOcrResult(
                    listOf(
                        RawOcrRegion("动态码", 100, 100, 260, 160),
                        RawOcrRegion("A1B2C3", 280, 100, 420, 160),
                    ),
                )
            },
        )

        val observation = engine.observe(snapshot = lowCoverageSnapshot(), mode = ScreenOcrMode.AUTO)

        val token = observation.blocks.single { it.text == "A1B2C3" }
        assertTrue(token.isSensitive)
        assertEquals(SensitiveScreenTextClassifier.MASKED_SENSITIVE_TEXT, token.redactedText)
        assertFalse(observation.redactedText().contains("A1B2C3"))
    }

    @Test
    fun jevResultAndRequestRedactStandaloneOtpAndPasswordBlocks() = runTest {
        val ocrEngine = engine(
            recognizer = {
                BitmapOcrResult(
                    listOf(
                        RawOcrRegion("验证码", 100, 100, 260, 160),
                        RawOcrRegion("8492", 280, 100, 380, 160),
                        RawOcrRegion("38491", 280, 180, 380, 240),
                        RawOcrRegion("hunter2", 120, 310, 400, 350),
                    ),
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
                        purposes = mapOf(
                            JevPurpose.PHONE_VERIFICATION to JevMode.ACTIVE,
                            JevPurpose.PHONE_ACTIONS to JevMode.ACTIVE,
                        ),
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
        val snapshot = ScreenSnapshot(
            id = "secret-screen",
            packageName = "com.example.canvas",
            windowId = 1,
            nodes = listOf(
                sampleNode("0/0", label = "", className = "android.webkit.WebView"),
                sampleNode("pw", label = "[password]", editable = true, left = 100, top = 300, right = 500, bottom = 360),
            ),
        )
        val controller = object : AccessibilityController {
            override fun captureScreenSnapshot(): ScreenSnapshot = snapshot
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
            goal = "确认页面",
            texts = emptyList(),
            runKey = "ocr-secret-run",
            ocrMode = ScreenOcrMode.AUTO,
        )

        val resultJson = result.toString()
        assertFalse(resultJson.contains("8492"))
        assertFalse(resultJson.contains("38491"))
        assertFalse(resultJson.contains("hunter2"))
        assertFalse(capturedRequestJson.contains("8492"))
        assertFalse(capturedRequestJson.contains("38491"))
        assertFalse(capturedRequestJson.contains("hunter2"))
        assertFalse(result["visible_text"]!!.jsonPrimitive.content.contains("8492"))
        assertFalse(result["visible_text"]!!.jsonPrimitive.content.contains("38491"))
        assertFalse(result["visible_text"]!!.jsonPrimitive.content.contains("hunter2"))
    }

    @Test
    fun jevScreenGoalRunnerAttachesStructuredOcrAndNormalizedCoordsOnLowCoverage() = runTest {
        var ocrCalls = 0
        val ocrEngine = engine(
            recognizer = {
                ocrCalls++
                BitmapOcrResult(
                    listOf(
                        RawOcrRegion("自绘页面已完成加载", left = 100, top = 500, right = 700, bottom = 600, confidence = 0.94f),
                    ),
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
                        purposes = mapOf(
                            JevPurpose.PHONE_VERIFICATION to JevMode.ACTIVE,
                            JevPurpose.PHONE_ACTIONS to JevMode.ACTIVE,
                        ),
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
        assertEquals("bitmap_ocr", result["ocr_source"]!!.jsonPrimitive.content)
        assertEquals("recognized", result["ocr_outcome"]!!.jsonPrimitive.content)
        assertTrue(capturedRequestJson.contains("自绘页面已完成加载"))
        val ocrBlock = result["ocr_blocks"]!!.jsonArray.single().jsonObject
        assertEquals("自绘页面已完成加载", ocrBlock["text"]!!.jsonPrimitive.content)
        assertEquals("bitmap_ocr", ocrBlock["source"]!!.jsonPrimitive.content)
        val norm = ocrBlock["normalized_bounds"]!!.jsonObject
        assertEquals(0.4, norm["center_x"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(0.275, norm["center_y"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(1, ocrCalls)
    }
}

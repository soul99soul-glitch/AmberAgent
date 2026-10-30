package app.amber.core.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Raw OCR region returned by a bitmap/window recognizer before coordinate normalization.
 */
data class RawOcrRegion(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float = 0.9f,
)

data class CapturedScreenBitmap(
    val bitmap: Bitmap?,
    val source: String,
    val width: Int,
    val height: Int,
)

/**
 * Classifies and redacts sensitive text (passwords, OTP codes, payment/card numbers)
 * so OCR summaries and step receipts never leak raw secrets in UI previews.
 */
object SensitiveScreenTextClassifier {
    private val SENSITIVE_KEYWORDS = Regex(
        "密码|验证码|校验码|动态码|安全码|支付密码|银行卡|身份证|cvv|pin|password|passcode|otp|verification\\s*code|security\\s*code|secret|token",
        RegexOption.IGNORE_CASE,
    )
    private val OTP_OR_CARD_DIGITS = Regex("\\b\\d{6,19}\\b")
    private val KEY_VALUE_SECRET = Regex(
        "((?:密码|验证码|校验码|动态码|安全码|支付密码|银行卡|身份证|cvv|pin|password|passcode|otp|verification\\s*code|security\\s*code|secret|token)\\s*(?:[:=：]|\\s+)\\s*)([^\\s\"',;，；]+)",
        RegexOption.IGNORE_CASE,
    )

    fun isSensitive(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "[password]" || trimmed == "[已隐藏敏感内容]") return true
        return SENSITIVE_KEYWORDS.containsMatchIn(trimmed) || OTP_OR_CARD_DIGITS.containsMatchIn(trimmed)
    }

    fun redactIfSensitive(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (!isSensitive(trimmed)) return trimmed
        var redacted = OTP_OR_CARD_DIGITS.replace(trimmed) { match ->
            val len = match.value.length
            if (len <= 6) "******" else "${match.value.take(2)}****${match.value.takeLast(2)}"
        }
        redacted = KEY_VALUE_SECRET.replace(redacted) { match ->
            val prefix = match.groupValues[1]
            val value = match.groupValues[2]
            if (value.all { it == '*' } || SENSITIVE_KEYWORDS.matches(value)) {
                match.value
            } else {
                "$prefix******"
            }
        }
        return redacted
    }
}

/**
 * Default on-device implementation of [ScreenOcrEngine].
 *
 * Guarantees:
 * - When [ScreenOcrMode.AUTO] is active and the Accessibility tree has sufficient text coverage
 *   (and no explicit `forceReason` is supplied), returns immediately with `triggered = false`
 *   and performs **zero screenshot capture and zero OCR computation**.
 * - When triggered (low coverage, post-action verification fallback, or [ScreenOcrMode.ON]),
 *   captures a screen bitmap and extracts text blocks with both pixel bounds and `[0, 1]`
 *   normalized coordinates ([NormalizedScreenBounds]).
 */
class DefaultScreenOcrEngine(
    private val displaySizeProvider: () -> Pair<Int, Int>,
    private val screenshotProvider: suspend (Int, Int) -> CapturedScreenBitmap?,
    private val recognizer: suspend (Bitmap?, ScreenSnapshot?, Int, Int) -> List<RawOcrRegion>,
) : ScreenOcrEngine {

    constructor(
        context: Context,
        screenCaptureManager: ScreenCaptureManager,
    ) : this(
        displaySizeProvider = { resolveDisplaySize(context) },
        screenshotProvider = { fallbackWidth, fallbackHeight ->
            captureBestEffortBitmap(screenCaptureManager, fallbackWidth, fallbackHeight)
        },
        recognizer = { bitmap, snapshot, width, height ->
            AndroidOnDeviceOcrRecognizer.recognize(context, bitmap, snapshot, width, height)
        },
    )

    override suspend fun observe(
        snapshot: ScreenSnapshot?,
        uiTreeDump: String,
        mode: ScreenOcrMode,
        forceReason: String?,
    ): ScreenOcrObservation {
        val (rawWidth, rawHeight) = displaySizeProvider()
        val displayWidth = rawWidth.coerceAtLeast(1)
        val displayHeight = rawHeight.coerceAtLeast(1)
        val coverage = ScreenTreeCoverageAnalyzer.analyzeUiTreeDump(uiTreeDump, snapshot)

        if (mode == ScreenOcrMode.OFF) {
            return ScreenOcrObservation(
                mode = mode,
                triggered = false,
                triggerReason = "disabled_by_caller",
                coverage = coverage,
                blocks = emptyList(),
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                screenshotSource = null,
            )
        }

        val shouldTrigger = mode == ScreenOcrMode.ON ||
            !forceReason.isNullOrBlank() ||
            coverage.isLowCoverage

        if (!shouldTrigger) {
            return ScreenOcrObservation(
                mode = mode,
                triggered = false,
                triggerReason = "tree_coverage_sufficient",
                coverage = coverage,
                blocks = emptyList(),
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                screenshotSource = null,
            )
        }

        val triggerReason = when {
            !forceReason.isNullOrBlank() -> forceReason
            mode == ScreenOcrMode.ON -> "explicit_model_request"
            else -> coverage.reason
        }

        val captured = runCatching { screenshotProvider(displayWidth, displayHeight) }.getOrNull()
        val effectiveWidth = (captured?.width?.takeIf { it > 0 } ?: displayWidth).coerceAtLeast(1)
        val effectiveHeight = (captured?.height?.takeIf { it > 0 } ?: displayHeight).coerceAtLeast(1)

        val rawRegions = try {
            runCatching {
                recognizer(captured?.bitmap, snapshot, effectiveWidth, effectiveHeight)
            }.getOrDefault(emptyList())
        } finally {
            // Recycle transient bitmap only if we own it and it's not shared.
            captured?.bitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    runCatching { bmp.recycle() }
                }
            }
        }

        val blocks = rawRegions
            .mapNotNull { region ->
                val cleanText = region.text.trim()
                if (cleanText.isEmpty()) return@mapNotNull null
                val left = region.left.coerceIn(0, effectiveWidth)
                val top = region.top.coerceIn(0, effectiveHeight)
                val right = region.right.coerceIn(left, effectiveWidth)
                val bottom = region.bottom.coerceIn(top, effectiveHeight)
                if (right <= left || bottom <= top) return@mapNotNull null
                region.copy(
                    text = cleanText,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                )
            }
            .distinctBy { "${it.text}|${it.left},${it.top},${it.right},${it.bottom}" }
            .take(MAX_OCR_BLOCKS)
            .mapIndexed { index, region ->
                ScreenOcrBlock(
                    id = "ocr_$index",
                    text = region.text,
                    confidence = region.confidence.coerceIn(0f, 1f),
                    left = region.left,
                    top = region.top,
                    right = region.right,
                    bottom = region.bottom,
                    normalizedBounds = NormalizedScreenBounds.fromPixelBounds(
                        left = region.left,
                        top = region.top,
                        right = region.right,
                        bottom = region.bottom,
                        displayWidth = effectiveWidth,
                        displayHeight = effectiveHeight,
                    ),
                    isSensitive = SensitiveScreenTextClassifier.isSensitive(region.text),
                )
            }

        return ScreenOcrObservation(
            mode = mode,
            triggered = true,
            triggerReason = triggerReason,
            coverage = coverage,
            blocks = blocks,
            displayWidth = effectiveWidth,
            displayHeight = effectiveHeight,
            screenshotSource = captured?.source ?: "accessibility_fallback",
        )
    }

    companion object {
        private const val MAX_OCR_BLOCKS = 64

        private fun resolveDisplaySize(context: Context): Pair<Int, Int> {
            val metrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            val width = metrics.widthPixels.takeIf { it > 0 }
                ?: context.resources.displayMetrics.widthPixels.takeIf { it > 0 }
                ?: 1080
            val height = metrics.heightPixels.takeIf { it > 0 }
                ?: context.resources.displayMetrics.heightPixels.takeIf { it > 0 }
                ?: 2400
            return width to height
        }

        private suspend fun captureBestEffortBitmap(
            screenCaptureManager: ScreenCaptureManager,
            fallbackWidth: Int,
            fallbackHeight: Int,
        ): CapturedScreenBitmap? {
            val service = AmberAccessibilityService.getActiveService()
            if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bmp = runCatching {
                    withTimeoutOrNull(2_500L) { service.takeScreenshotBitmap() }
                }.getOrNull()
                if (bmp != null) {
                    return CapturedScreenBitmap(
                        bitmap = bmp,
                        source = "accessibility_screenshot",
                        width = bmp.width.takeIf { it > 0 } ?: fallbackWidth,
                        height = bmp.height.takeIf { it > 0 } ?: fallbackHeight,
                    )
                }
            }
            val lastCapture = screenCaptureManager.lastResult
            if (lastCapture != null && lastCapture.file.exists()) {
                val decoded = runCatching { BitmapFactory.decodeFile(lastCapture.file.absolutePath) }.getOrNull()
                if (decoded != null) {
                    return CapturedScreenBitmap(
                        bitmap = decoded,
                        source = "media_projection",
                        width = decoded.width.takeIf { it > 0 } ?: lastCapture.width.takeIf { it > 0 } ?: fallbackWidth,
                        height = decoded.height.takeIf { it > 0 } ?: lastCapture.height.takeIf { it > 0 } ?: fallbackHeight,
                    )
                }
            }
            return null
        }
    }
}

/**
 * On-device OCR recognizer that combines:
 * 1. Reflective ML Kit / Play Services on-device TextRecognition when available on the device.
 * 2. Extended Accessibility node attributes (hintText, tooltipText, stateDescription, paneTitle,
 *    extras, semantic viewId tokens) for custom views / WebViews whose primary `text` is blank.
 */
object AndroidOnDeviceOcrRecognizer {
    suspend fun recognize(
        context: Context,
        bitmap: Bitmap?,
        snapshot: ScreenSnapshot?,
        displayWidth: Int,
        displayHeight: Int,
    ): List<RawOcrRegion> {
        val results = mutableListOf<RawOcrRegion>()

        // 1. Try on-device ML Kit / GMS Vision TextRecognizer if available at runtime.
        if (bitmap != null && !bitmap.isRecycled) {
            results += recognizeWithMlKitReflection(bitmap)
            if (results.isEmpty()) {
                results += recognizeWithGmsVisionReflection(context, bitmap)
            }
        }

        // 2. Supplement with deep Accessibility node metadata (hints, tooltips, stateDescription,
        //    paneTitle, and semantic resource-id labels on unlabeled interactive nodes).
        results += extractExtendedAccessibilityRegions(snapshot, displayWidth, displayHeight)

        return results
    }

    private suspend fun recognizeWithMlKitReflection(bitmap: Bitmap): List<RawOcrRegion> = runCatching {
        val inputImageClass = Class.forName("com.google.mlkit.vision.common.InputImage")
        val fromBitmap = inputImageClass.getMethod("fromBitmap", Bitmap::class.java, Int::class.javaPrimitiveType)
        val inputImage = fromBitmap.invoke(null, bitmap, 0) ?: return@runCatching emptyList()

        val textRecognitionClass = Class.forName("com.google.mlkit.vision.text.TextRecognition")
        val recognizerOptionsInterface = Class.forName("com.google.mlkit.vision.text.TextRecognizerOptionsInterface")
        val optionsObj = runCatching {
            val chineseOptionsClass = Class.forName("com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions\$Builder")
            val builder = chineseOptionsClass.getDeclaredConstructor().newInstance()
            chineseOptionsClass.getMethod("build").invoke(builder)
        }.getOrElse {
            val defaultOptionsClass = Class.forName("com.google.mlkit.vision.text.latin.TextRecognizerOptions")
            defaultOptionsClass.getField("DEFAULT_OPTIONS").get(null)
        } ?: return@runCatching emptyList()

        val getClient = textRecognitionClass.getMethod("getClient", recognizerOptionsInterface)
        val client = getClient.invoke(null, optionsObj) ?: return@runCatching emptyList()
        val processMethod = client.javaClass.getMethod("process", inputImageClass)
        val task = processMethod.invoke(client, inputImage) ?: return@runCatching emptyList()

        val visionText = awaitGmsTask(task) ?: return@runCatching emptyList()
        val getTextBlocks = visionText.javaClass.getMethod("getTextBlocks")
        val blocks = getTextBlocks.invoke(visionText) as? List<*> ?: return@runCatching emptyList()
        buildList {
            for (block in blocks) {
                if (block == null) continue
                val text = runCatching { block.javaClass.getMethod("getText").invoke(block) as? String }
                    .getOrNull()?.trim().orEmpty()
                val rect = runCatching { block.javaClass.getMethod("getBoundingBox").invoke(block) as? Rect }
                    .getOrNull()
                if (text.isNotEmpty() && rect != null) {
                    add(
                        RawOcrRegion(
                            text = text,
                            left = rect.left,
                            top = rect.top,
                            right = rect.right,
                            bottom = rect.bottom,
                            confidence = 0.92f,
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun recognizeWithGmsVisionReflection(context: Context, bitmap: Bitmap): List<RawOcrRegion> = runCatching {
        val builderClass = Class.forName("com.google.android.gms.vision.text.TextRecognizer\$Builder")
        val recognizer = builderClass.getDeclaredConstructor(Context::class.java)
            .newInstance(context)
            .let { builder -> builderClass.getMethod("build").invoke(builder) }
            ?: return@runCatching emptyList()
        try {
            val isOperational = recognizer.javaClass.getMethod("isOperational").invoke(recognizer) as? Boolean ?: false
            if (!isOperational) return@runCatching emptyList()
            val frameBuilderClass = Class.forName("com.google.android.gms.vision.Frame\$Builder")
            val frameBuilder = frameBuilderClass.getDeclaredConstructor().newInstance()
            frameBuilderClass.getMethod("setBitmap", Bitmap::class.java).invoke(frameBuilder, bitmap)
            val frame = frameBuilderClass.getMethod("build").invoke(frameBuilder)
            val frameClass = Class.forName("com.google.android.gms.vision.Frame")
            val sparseArray = recognizer.javaClass.getMethod("detect", frameClass).invoke(recognizer, frame)
                ?: return@runCatching emptyList()
            val size = sparseArray.javaClass.getMethod("size").invoke(sparseArray) as? Int ?: 0
            val valueAt = sparseArray.javaClass.getMethod("valueAt", Int::class.javaPrimitiveType)
            buildList {
                for (i in 0 until size) {
                    val item = valueAt.invoke(sparseArray, i) ?: continue
                    val text = runCatching { item.javaClass.getMethod("getValue").invoke(item) as? String }
                        .getOrNull()?.trim().orEmpty()
                    val rect = runCatching { item.javaClass.getMethod("getBoundingBox").invoke(item) as? Rect }
                        .getOrNull()
                    if (text.isNotEmpty() && rect != null) {
                        add(
                            RawOcrRegion(
                                text = text,
                                left = rect.left,
                                top = rect.top,
                                right = rect.right,
                                bottom = rect.bottom,
                                confidence = 0.88f,
                            )
                        )
                    }
                }
            }
        } finally {
            runCatching { recognizer.javaClass.getMethod("release").invoke(recognizer) }
        }
    }.getOrDefault(emptyList())

    private suspend fun awaitGmsTask(task: Any): Any? = suspendCancellableCoroutine { cont ->
        runCatching {
            val onSuccessListenerClass = Class.forName("com.google.android.gms.tasks.OnSuccessListener")
            val onFailureListenerClass = Class.forName("com.google.android.gms.tasks.OnFailureListener")
            val successProxy = java.lang.reflect.Proxy.newProxyInstance(
                onSuccessListenerClass.classLoader,
                arrayOf(onSuccessListenerClass),
            ) { _, method, args ->
                if (method.name == "onSuccess" && cont.isActive) {
                    cont.resume(args?.firstOrNull())
                }
                null
            }
            val failureProxy = java.lang.reflect.Proxy.newProxyInstance(
                onFailureListenerClass.classLoader,
                arrayOf(onFailureListenerClass),
            ) { _, method, _ ->
                if (method.name == "onFailure" && cont.isActive) {
                    cont.resume(null)
                }
                null
            }
            task.javaClass.getMethod("addOnSuccessListener", onSuccessListenerClass).invoke(task, successProxy)
            task.javaClass.getMethod("addOnFailureListener", onFailureListenerClass).invoke(task, failureProxy)
        }.onFailure {
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun extractExtendedAccessibilityRegions(
        snapshot: ScreenSnapshot?,
        displayWidth: Int,
        displayHeight: Int,
    ): List<RawOcrRegion> {
        val regions = mutableListOf<RawOcrRegion>()
        val service = AmberAccessibilityService.getActiveService()
        val root = runCatching { service?.rootInActiveWindow }.getOrNull()
        if (root != null) {
            var visited = 0
            fun visit(node: AccessibilityNodeInfo) {
                if (visited >= 180) return
                visited++
                if (!node.isPassword && node.isVisibleToUser) {
                    val candidates = buildList {
                        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                        node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            node.tooltipText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                            node.paneTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            node.stateDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                        }
                    }
                    val label = candidates.firstOrNull().orEmpty()
                    if (label.isNotEmpty()) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (rect.right > rect.left && rect.bottom > rect.top) {
                            regions += RawOcrRegion(
                                text = label,
                                left = rect.left.coerceIn(0, displayWidth),
                                top = rect.top.coerceIn(0, displayHeight),
                                right = rect.right.coerceIn(0, displayWidth),
                                bottom = rect.bottom.coerceIn(0, displayHeight),
                                confidence = 0.85f,
                            )
                        }
                    }
                }
                repeat(node.childCount) { i ->
                    node.getChild(i)?.let(::visit)
                }
            }
            runCatching { visit(root) }
        }
        snapshot?.nodes.orEmpty().forEach { node ->
            val fallbackText = node.label.trim().ifEmpty {
                semanticLabelFromViewId(node.viewId)
            }
            if (fallbackText.isNotEmpty() && fallbackText != "[password]" &&
                node.right > node.left && node.bottom > node.top
            ) {
                regions += RawOcrRegion(
                    text = fallbackText,
                    left = node.left.coerceIn(0, displayWidth),
                    top = node.top.coerceIn(0, displayHeight),
                    right = node.right.coerceIn(0, displayWidth),
                    bottom = node.bottom.coerceIn(0, displayHeight),
                    confidence = if (node.label.isNotBlank()) 0.9f else 0.72f,
                )
            }
        }
        return regions
    }

    internal fun semanticLabelFromViewId(viewId: String): String {
        val raw = viewId.substringAfterLast('/').trim()
        if (raw.isEmpty()) return ""
        val cleaned = raw
            .replace(Regex("^(btn|tv|iv|et|layout|container|view|ic|icon|action|menu|tab)_+", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
        return cleaned.takeIf { it.length in 2..40 }.orEmpty()
    }
}

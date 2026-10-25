package app.amber.core.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Raw OCR region returned by a recognizer before coordinate normalization.
 *
 * [confidence] is a heuristic score, not a calibrated ML Kit probability: the
 * bundled text-recognition result exposes text + bounds, not per-block scores.
 */
data class RawOcrRegion(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float = 0.9f,
)

/**
 * One captured frame. The engine takes ownership of [bitmap] and recycles it
 * after recognition (or after failure), so providers must not hand out cached
 * or shared bitmaps.
 */
data class CapturedScreenBitmap(
    val bitmap: Bitmap?,
    val source: String,
    val width: Int,
    val height: Int,
)

/**
 * Result of running a bitmap recognizer once.
 *
 * [failure] is a stable diagnostic code (`model_not_found`, `screenshot_unavailable`, ...).
 * A recognizer that finds no text returns `regions = emptyList(), failure = null`
 * and the engine surfaces it as `ocr_no_text` instead of a silent success.
 */
data class BitmapOcrResult(
    val regions: List<RawOcrRegion> = emptyList(),
    val failure: String? = null,
) {
    val succeeded: Boolean get() = failure == null
}

/**
 * Narrow seam over ML Kit's recognizer so JVM tests can drive success / failure /
 * task-cancel / close-deferral without loading the native pipeline.
 */
internal interface TextProcessClient : AutoCloseable {
    fun process(bitmap: Bitmap): Task<Text>
}

internal class MlKitTextProcessClient(
    private val recognizer: TextRecognizer,
) : TextProcessClient {
    override fun process(bitmap: Bitmap): Task<Text> =
        recognizer.process(InputImage.fromBitmap(bitmap, 0))

    override fun close() = recognizer.close()
}

/**
 * Compiled-in ML Kit text recognizer.
 *
 * Uses the **bundled** `text-recognition-chinese` model, which recognizes
 * Chinese (Simplified/Traditional) plus Latin script — see
 * [ScreenOcrCapabilities]. Bundle model => no Play Services download step at
 * runtime, no "model not downloaded" state on first use, and no reflection:
 * every symbol is resolved at compile time and survives R8.
 *
 * Lifecycle:
 * - the client is created lazily and cached (model load is expensive);
 * - [close] is safe while `process` is in flight: the client is closed only after
 *   the last in-flight recognition settles, so there is no use-after-close;
 * - a cancelled ML Kit Task resumes as `recognition_cancelled` instead of
 *   leaving the coroutine suspended forever.
 */
class MlKitScreenTextRecognizer internal constructor(
    private val clientFactory: () -> TextProcessClient = {
        MlKitTextProcessClient(
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
        )
    },
) : AutoCloseable {
    private val lock = Any()

    private var client: TextProcessClient? = null
    private var closed = false
    private var activeProcesses = 0

    suspend fun recognize(bitmap: Bitmap): BitmapOcrResult {
        if (bitmap.isRecycled) return BitmapOcrResult(failure = "bitmap_recycled")
        val recognizer = obtainClientForProcess()
            ?: return BitmapOcrResult(failure = "recognizer_closed")
        try {
            val task = recognizer.process(bitmap)
            return when (val outcome = task.awaitOutcome()) {
                is TaskOutcome.Success -> BitmapOcrResult(regions = outcome.value.toRawRegions())
                is TaskOutcome.Failure -> BitmapOcrResult(
                    failure = if (task.isCanceled) "recognition_cancelled" else mlKitFailureReason(outcome.error),
                )
                TaskOutcome.Cancelled -> BitmapOcrResult(failure = "recognition_cancelled")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return BitmapOcrResult(failure = mlKitFailureReason(error))
        } finally {
            releaseAfterProcess()
        }
    }

    override fun close() {
        val toClose = synchronized(lock) {
            closed = true
            if (activeProcesses == 0) {
                client.also { client = null }
            } else {
                null
            }
        }
        toClose?.let { runCatching { it.close() } }
    }

    private fun obtainClientForProcess(): TextProcessClient? = synchronized(lock) {
        if (closed) return null
        val current = client ?: clientFactory().also { client = it }
        activeProcesses++
        current
    }

    private fun releaseAfterProcess() {
        val toClose = synchronized(lock) {
            activeProcesses = (activeProcesses - 1).coerceAtLeast(0)
            if (closed && activeProcesses == 0) {
                client.also { client = null }
            } else {
                null
            }
        }
        toClose?.let { runCatching { it.close() } }
    }

    private sealed interface TaskOutcome<out T> {
        data class Success<T>(val value: T) : TaskOutcome<T>
        data class Failure(val error: Throwable) : TaskOutcome<Nothing>
        data object Cancelled : TaskOutcome<Nothing>
    }

    private suspend fun <T> Task<T>.awaitOutcome(): TaskOutcome<T> = suspendCancellableCoroutine { continuation ->
        // Resume on the completing/registration thread instead of hopping to the main
        // looper: the OCR call already runs off the UI thread, and a paused looper must
        // never strand the continuation (especially inside NonCancellable).
        addOnSuccessListener(DIRECT_EXECUTOR) { value ->
            if (continuation.isActive) continuation.resume(TaskOutcome.Success(value))
        }
        addOnFailureListener(DIRECT_EXECUTOR) { error ->
            if (continuation.isActive) continuation.resume(TaskOutcome.Failure(error))
        }
        // Without this listener a cancelled ML Kit Task never resumes the continuation;
        // inside NonCancellable that would hang the caller forever.
        addOnCanceledListener(DIRECT_EXECUTOR) {
            if (continuation.isActive) continuation.resume(TaskOutcome.Cancelled)
        }
    }

    private fun Text.toRawRegions(): List<RawOcrRegion> = buildList {
        textBlocks.forEach { block ->
            val lines = block.lines
            if (lines.isNotEmpty()) {
                lines.forEach { line -> line.toRawRegion()?.let(::add) }
            } else {
                block.toRawRegion()?.let(::add)
            }
        }
    }

    private fun Text.Line.toRawRegion(): RawOcrRegion? {
        val box = boundingBox ?: return null
        val clean = text.trim()
        if (clean.isEmpty() || box.right <= box.left || box.bottom <= box.top) return null
        return RawOcrRegion(clean, box.left, box.top, box.right, box.bottom)
    }

    private fun Text.TextBlock.toRawRegion(): RawOcrRegion? {
        val box = boundingBox ?: return null
        val clean = text.trim()
        if (clean.isEmpty() || box.right <= box.left || box.bottom <= box.top) return null
        return RawOcrRegion(clean, box.left, box.top, box.right, box.bottom)
    }
}

private val DIRECT_EXECUTOR: java.util.concurrent.Executor = java.util.concurrent.Executor { runnable -> runnable.run() }

/** Maps an ML Kit failure to a stable, receipt-safe diagnostic code. */
internal fun mlKitFailureReason(error: Throwable): String {
    if (error.isCancellationLike()) return "recognition_cancelled"
    val code = (error as? MlKitException)?.errorCode ?: return "recognition_failed"
    return when (code) {
        MlKitException.NOT_ENOUGH_SPACE -> "not_enough_space"
        MlKitException.MODEL_HASH_MISMATCH -> "model_hash_mismatch"
        MlKitException.MODEL_INCOMPATIBLE_WITH_TFLITE -> "model_incompatible"
        MlKitException.NOT_FOUND -> "model_not_found"
        MlKitException.UNAVAILABLE -> "recognizer_unavailable"
        MlKitException.NETWORK_ISSUE -> "network_issue"
        MlKitException.DEADLINE_EXCEEDED -> "recognition_timeout"
        MlKitException.CANCELLED -> "recognition_cancelled"
        MlKitException.INVALID_ARGUMENT -> "invalid_image"
        else -> "recognition_failed"
    }
}

/** Tasks may surface cancellation wrapped in RuntimeExecutionException. */
private fun Throwable.isCancellationLike(): Boolean {
    var current: Throwable? = this
    repeat(5) {
        val cause = current ?: return false
        if (cause is CancellationException) return true
        if (cause is MlKitException && cause.errorCode == MlKitException.CANCELLED) return true
        if (cause.message.orEmpty().contains("cancel", ignoreCase = true)) return true
        current = cause.cause
    }
    return false
}

/**
 * Default on-device implementation of [ScreenOcrEngine].
 *
 * Guarantees:
 * - When [ScreenOcrMode.AUTO] is active and the Accessibility tree has sufficient text coverage
 *   (and no explicit `forceReason` is supplied), returns immediately with `triggered = false`
 *   and performs **zero screenshot capture and zero OCR computation**.
 * - When triggered (low coverage, post-action verification fallback, or [ScreenOcrMode.ON]),
 *   captures a screen bitmap and extracts text with both pixel bounds and `[0, 1]`
 *   normalized coordinates ([NormalizedScreenBounds]).
 * - The observation reports its real [ScreenOcrObservation.source] / [ScreenOcrObservation.outcome]:
 *   Accessibility-only metadata is never presented as bitmap OCR, and a failed/empty
 *   pass carries a diagnostic code instead of a fake success.
 * - The captured bitmap is recycled exactly once after recognition, including on
 *   cancellation; a cancelled observation never returns blocks (so no stale action
 *   is dispatched from an outdated frame).
 */
class DefaultScreenOcrEngine(
    private val displaySizeProvider: () -> Pair<Int, Int>,
    private val screenshotProvider: suspend (Int, Int) -> CapturedScreenBitmap?,
    private val bitmapRecognizer: (suspend (Bitmap) -> BitmapOcrResult)? = null,
    private val accessibilityRegionsProvider: (
        snapshot: ScreenSnapshot?,
        displayWidth: Int,
        displayHeight: Int,
    ) -> List<RawOcrRegion> = { snapshot, width, height ->
        ExtendedAccessibilityOcrExtractor.extract(snapshot, width, height)
    },
    private val recognizerId: String? = null,
    private val ownedRecognizer: MlKitScreenTextRecognizer? = null,
) : ScreenOcrEngine {

    constructor(
        context: Context,
        screenCaptureManager: ScreenCaptureManager,
    ) : this(
        displaySizeProvider = { resolveDisplaySize(context) },
        screenshotProvider = { fallbackWidth, fallbackHeight ->
            captureFreshBitmap(
                hasActiveSession = { screenCaptureManager.hasActiveSession },
                captureNewFrame = {
                    // Only a live MediaProjection session is used; never prompt or decode history.
                    screenCaptureManager.capture(timeoutMillis = OCR_CAPTURE_TIMEOUT_MS)
                },
                accessibilityScreenshot = currentAccessibilityScreenshotProvider(),
                fallbackWidth = fallbackWidth,
                fallbackHeight = fallbackHeight,
            )
        },
        bitmapRecognizer = null,
        recognizerId = ScreenOcrCapabilities.RECOGNIZER_ID,
        ownedRecognizer = MlKitScreenTextRecognizer(),
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
            return notTriggeredObservation(
                mode = mode,
                reason = "disabled_by_caller",
                coverage = coverage,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )
        }

        val shouldTrigger = mode == ScreenOcrMode.ON ||
            !forceReason.isNullOrBlank() ||
            coverage.isLowCoverage

        if (!shouldTrigger) {
            return notTriggeredObservation(
                mode = mode,
                reason = "tree_coverage_sufficient",
                coverage = coverage,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )
        }

        val triggerReason = when {
            !forceReason.isNullOrBlank() -> forceReason
            mode == ScreenOcrMode.ON -> "explicit_model_request"
            else -> coverage.reason
        }

        var screenshotFailure: String? = null
        val captured = try {
            screenshotProvider(displayWidth, displayHeight)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            screenshotFailure = "screenshot_failed"
            null
        }
        val effectiveWidth = (captured?.width?.takeIf { it > 0 } ?: displayWidth).coerceAtLeast(1)
        val effectiveHeight = (captured?.height?.takeIf { it > 0 } ?: displayHeight).coerceAtLeast(1)
        val bitmap = captured?.bitmap?.takeIf { !it.isRecycled }

        var bitmapResult: BitmapOcrResult? = null
        try {
            val activeRecognizer = resolveRecognizer()
            when {
                bitmap == null -> {
                    screenshotFailure = screenshotFailure ?: "screenshot_unavailable"
                }
                activeRecognizer == null -> {
                    bitmapResult = BitmapOcrResult(failure = "bitmap_recognizer_unavailable")
                }
                else -> {
                    bitmapResult = try {
                        // ML Kit Task cannot be cancelled, and recycling the bitmap under a
                        // running recognizer would be a use-after-free. Await settle inside
                        // NonCancellable, then honor cancellation right after (before any
                        // block is handed to a caller, so no stale-frame action).
                        withContext(NonCancellable) { activeRecognizer(bitmap) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        BitmapOcrResult(failure = "recognition_failed")
                    }
                }
            }
        } finally {
            // Ownership contract: the engine recycles every provided bitmap exactly once.
            if (bitmap != null && !bitmap.isRecycled) {
                runCatching { bitmap.recycle() }
            }
        }

        // A cancelled observation must not hand blocks to a caller that could then
        // dispatch an action against an old frame.
        coroutineContext.ensureActive()

        val bitmapRegions = bitmapResult?.regions.orEmpty()
            .filter { it.text.isNotBlank() }
        val accessibilityRegions = try {
            accessibilityRegionsProvider(snapshot, effectiveWidth, effectiveHeight)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emptyList()
        }

        // One combined pass: bitmap first, then Accessibility metadata. Dedupe/limit/id
        // assignment happen on the merged list so a bitmap + a11y hit cannot both get
        // `ocr_0`, and the total never exceeds MAX_OCR_BLOCKS.
        val blocks = buildBlocks(
            bitmapRegions = bitmapRegions,
            accessibilityRegions = accessibilityRegions,
            snapshot = snapshot,
            displayWidth = effectiveWidth,
            displayHeight = effectiveHeight,
        )

        val hasBitmapText = blocks.any { it.source.isBitmapOcr }
        val source = when {
            hasBitmapText -> ScreenOcrSource.BITMAP_OCR
            blocks.isNotEmpty() -> ScreenOcrSource.EXTENDED_ACCESSIBILITY
            else -> ScreenOcrSource.UNAVAILABLE
        }
        val outcome = when {
            hasBitmapText -> ScreenOcrOutcome.RECOGNIZED
            blocks.isNotEmpty() -> ScreenOcrOutcome.DEGRADED
            else -> ScreenOcrOutcome.UNAVAILABLE
        }
        val bitmapFailure = bitmapResult?.failure
        val bitmapSucceeded = bitmapResult?.succeeded == true
        val diagnostics = buildList {
            screenshotFailure?.let(::add)
            bitmapFailure?.let(::add)
            if (bitmapSucceeded && !hasBitmapText) {
                add("ocr_no_text")
            }
        }

        return ScreenOcrObservation(
            mode = mode,
            triggered = true,
            triggerReason = triggerReason,
            coverage = coverage,
            blocks = blocks,
            displayWidth = effectiveWidth,
            displayHeight = effectiveHeight,
            screenshotSource = captured?.source,
            source = source,
            outcome = outcome,
            diagnostics = diagnostics,
            recognizer = if (bitmap != null && bitmapResult != null) recognizerId else null,
        )
    }

    override fun close() {
        ownedRecognizer?.close()
    }

    private fun resolveRecognizer(): (suspend (Bitmap) -> BitmapOcrResult)? {
        bitmapRecognizer?.let { return it }
        val owned = ownedRecognizer ?: return null
        return { frame -> owned.recognize(frame) }
    }

    private fun notTriggeredObservation(
        mode: ScreenOcrMode,
        reason: String,
        coverage: ScreenTreeCoverageReport,
        displayWidth: Int,
        displayHeight: Int,
    ) = ScreenOcrObservation(
        mode = mode,
        triggered = false,
        triggerReason = reason,
        coverage = coverage,
        blocks = emptyList(),
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        screenshotSource = null,
        source = ScreenOcrSource.UNAVAILABLE,
        outcome = ScreenOcrOutcome.NOT_TRIGGERED,
        diagnostics = emptyList(),
        recognizer = null,
    )

    private data class SourcedRegion(
        val region: RawOcrRegion,
        val source: ScreenOcrSource,
    )

    private fun buildBlocks(
        bitmapRegions: List<RawOcrRegion>,
        accessibilityRegions: List<RawOcrRegion>,
        snapshot: ScreenSnapshot?,
        displayWidth: Int,
        displayHeight: Int,
    ): List<ScreenOcrBlock> {
        // Bitmap hits come first so they win dedupe against Accessibility metadata.
        val candidates = buildList {
            bitmapRegions.forEach { add(SourcedRegion(it, ScreenOcrSource.BITMAP_OCR)) }
            accessibilityRegions.forEach { add(SourcedRegion(it, ScreenOcrSource.EXTENDED_ACCESSIBILITY)) }
        }
        val sanitized = candidates
            .mapNotNull { candidate ->
                val region = candidate.region
                val cleanText = region.text.trim()
                if (cleanText.isEmpty()) return@mapNotNull null
                val left = region.left.coerceIn(0, displayWidth)
                val top = region.top.coerceIn(0, displayHeight)
                val right = region.right.coerceIn(left, displayWidth)
                val bottom = region.bottom.coerceIn(top, displayHeight)
                if (right <= left || bottom <= top) return@mapNotNull null
                SourcedRegion(
                    region = region.copy(
                        text = cleanText,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                    ),
                    source = candidate.source,
                )
            }
            .distinctBy { candidate ->
                val region = candidate.region
                "${region.text}|${region.left},${region.top},${region.right},${region.bottom}"
            }
            .take(MAX_OCR_BLOCKS)

        val sensitiveBounds = sensitiveNodeBounds(snapshot)
        val baseSensitive = sanitized.map { candidate ->
            val text = candidate.region.text
            SensitiveScreenTextClassifier.isSensitive(text) ||
                SensitiveScreenTextClassifier.isStandaloneNumericSecret(text) ||
                intersectsAny(candidate.region, sensitiveBounds)
        }

        return sanitized.mapIndexed { index, candidate ->
            val region = candidate.region
            val text = region.text
            val intersectsSensitiveNode = intersectsAny(region, sensitiveBounds)
            val standaloneNumeric = SensitiveScreenTextClassifier.isStandaloneNumericSecret(text)
            val keywordSensitive = SensitiveScreenTextClassifier.isSensitive(text)
            // OTP split into label + value blocks: a token-like value adjacent to a
            // sensitive label is masked even when it has no keyword of its own.
            val contextualSecret = !keywordSensitive && !standaloneNumeric && !intersectsSensitiveNode &&
                SensitiveScreenTextClassifier.looksLikeSecretToken(text) &&
                sanitized.indices.any { other ->
                    other != index && baseSensitive[other] &&
                        isNearby(region, sanitized[other].region)
                }
            val sensitive = intersectsSensitiveNode || standaloneNumeric || keywordSensitive || contextualSecret
            val maskEntirely = intersectsSensitiveNode || contextualSecret
            ScreenOcrBlock(
                id = "ocr_$index",
                text = text,
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
                    displayWidth = displayWidth,
                    displayHeight = displayHeight,
                ),
                isSensitive = sensitive,
                source = candidate.source,
                redactedText = SensitiveScreenTextClassifier.redactForDisplay(
                    text = text,
                    maskEntirely = maskEntirely,
                ),
            )
        }
    }

    /**
     * Tree bounds that must hide intersecting OCR: password fields (`[password]`)
     * and any node whose own label is already classified sensitive ("密码",
     * "验证码", ...). Keywords on the OCR text are a separate, incomplete signal.
     */
    private fun sensitiveNodeBounds(snapshot: ScreenSnapshot?): List<Rect> =
        snapshot?.nodes.orEmpty()
            .asSequence()
            .filter { node ->
                node.right > node.left && node.bottom > node.top &&
                    (node.label == "[password]" ||
                        SensitiveScreenTextClassifier.isSensitive(node.label))
            }
            .map { Rect(it.left, it.top, it.right, it.bottom) }
            .toList()

    private fun intersectsAny(region: RawOcrRegion, bounds: List<Rect>): Boolean =
        bounds.any { rect ->
            region.left < rect.right && rect.left < region.right &&
                region.top < rect.bottom && rect.top < region.bottom
        }

    /** Same visual row and horizontally close: label/value pairs for OTP fields. */
    private fun isNearby(a: RawOcrRegion, b: RawOcrRegion): Boolean {
        val verticalGap = maxOf(0, maxOf(a.top, b.top) - minOf(a.bottom, b.bottom))
        val horizontalGap = maxOf(0, maxOf(a.left, b.left) - minOf(a.right, b.right))
        val rowHeight = maxOf(a.bottom - a.top, b.bottom - b.top, 32)
        return verticalGap <= rowHeight && horizontalGap <= rowHeight * 6
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
    }
}

/** OCR frame capture waits are short: a stale/late frame is worse than an explicit unavailable. */
internal const val OCR_CAPTURE_TIMEOUT_MS = 3_000L
private const val ACCESSIBILITY_SCREENSHOT_TIMEOUT_MS = 2_500L

internal fun currentAccessibilityScreenshotProvider(): (suspend () -> Bitmap?)? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val service = AmberAccessibilityService.getActiveService() ?: return null
    return { service.takeScreenshotBitmap() }
}

/**
 * Fresh-frame policy for OCR (no history, no implicit permission prompt):
 *
 * 1. An Accessibility screenshot is preferred when an active service exists;
 * 2. otherwise a **new** MediaProjection frame is requested only while the host
 *    already holds an active capture session (`hasActiveSession`);
 * 3. `ScreenCaptureManager.lastResult` is never decoded: a previous capture may be
 *    arbitrarily old and must not masquerade as this observation.
 *
 * Accessibility screenshot cancellation always propagates so the caller is not
 * given a later MediaProjection frame as if the wait succeeded. Other capture
 * failures degrade to `null` (engine reports `screenshot_unavailable`).
 * MediaProjection: caller cancellation propagates; a provider-internal
 * `CancellationException` while the caller is still active degrades.
 */
internal suspend fun captureFreshBitmap(
    hasActiveSession: () -> Boolean,
    captureNewFrame: suspend () -> ScreenCaptureResult,
    accessibilityScreenshot: (suspend () -> Bitmap?)?,
    fallbackWidth: Int,
    fallbackHeight: Int,
): CapturedScreenBitmap? {
    if (accessibilityScreenshot != null) {
        val bitmap = try {
            withTimeoutOrNull(ACCESSIBILITY_SCREENSHOT_TIMEOUT_MS) { accessibilityScreenshot() }
        } catch (cancellation: CancellationException) {
            // Do not swallow cancellation and continue to MediaProjection the way
            // the old `runCatching { takeScreenshotBitmap() }` + lastResult path did.
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        if (bitmap != null && !bitmap.isRecycled) {
            return CapturedScreenBitmap(
                bitmap = bitmap,
                source = "accessibility_screenshot",
                width = bitmap.width.takeIf { it > 0 } ?: fallbackWidth,
                height = bitmap.height.takeIf { it > 0 } ?: fallbackHeight,
            )
        }
    }

    if (!hasActiveSession()) return null
    val captured = try {
        captureNewFrame()
    } catch (cancellation: CancellationException) {
        coroutineContext.ensureActive()
        return null
    } catch (_: Throwable) {
        return null
    }
    if (captured.sizeBytes <= 0 || !captured.file.exists()) return null
    val decoded = try {
        BitmapFactory.decodeFile(captured.file.absolutePath)
    } catch (cancellation: CancellationException) {
        coroutineContext.ensureActive()
        null
    } catch (_: Throwable) {
        null
    } ?: return null
    return CapturedScreenBitmap(
        bitmap = decoded,
        source = "media_projection",
        width = captured.width.takeIf { it > 0 } ?: decoded.width,
        height = captured.height.takeIf { it > 0 } ?: decoded.height,
    )
}

/**
 * Extra Accessibility metadata that helps on custom views / WebViews whose primary
 * `text` is blank. Results are tagged [ScreenOcrSource.EXTENDED_ACCESSIBILITY] and
 * must not be presented as pixel OCR.
 */
object ExtendedAccessibilityOcrExtractor {
    fun extract(
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

package app.amber.core.automation

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.agent.SCREEN_CAPTURE_NOTIFICATION_CHANNEL_ID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.android.ext.android.inject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "ScreenCaptureService"

class ScreenCaptureService : Service() {
    private val captureManager: ScreenCaptureManager by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Uncaught exception in serviceScope", e)
    })
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionLock = Any()

    // Dedicated background thread for ImageReader callbacks and pixel copy/stride cropping (R6-5).
    private val captureThread = HandlerThread(
        "AmberScreenCaptureWorker",
        Process.THREAD_PRIORITY_BACKGROUND,
    ).apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    @Volatile
    private var captureSession: ActiveCaptureSession? = null
    private val idleStopRunnable = Runnable {
        releaseCaptureSession(stopProjection = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION_CAPTURE -> {
                startForegroundCompat()
                val requestId = intent.getStringExtra(ScreenCaptureManager.EXTRA_REQUEST_ID)
                    ?: return START_NOT_STICKY.also { stopSelf(startId) }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == 0 || resultData == null) {
                    captureManager.fail(requestId, IllegalArgumentException("Missing MediaProjection result data"))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    captureFromNewSession(requestId, resultCode, resultData, startId)
                }
                return START_NOT_STICKY
            }

            ACTION_CAPTURE_EXISTING -> {
                startForegroundCompat()
                val requestId = intent.getStringExtra(ScreenCaptureManager.EXTRA_REQUEST_ID)
                    ?: return START_NOT_STICKY.also { stopSelf(startId) }
                serviceScope.launch {
                    captureFromExistingSession(requestId, startId)
                }
                return START_NOT_STICKY
            }

            ACTION_STOP_SESSION -> {
                startForegroundCompat()
                releaseCaptureSession(stopProjection = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }

            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(idleStopRunnable)
        releaseCaptureSession(stopProjection = true)
        serviceScope.cancel()
        captureHandler.removeCallbacksAndMessages(null)
        captureThread.quitSafely()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, SCREEN_CAPTURE_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(getString(R.string.notification_screen_capture_running))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, RouteActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private suspend fun captureFromNewSession(requestId: String, resultCode: Int, resultData: Intent, startId: Int) {
        runCatching {
            val session = ensureCaptureSession(resultCode, resultData)
            captureAndSaveScreenshot(session)
        }.onSuccess { result ->
            captureManager.complete(requestId, result)
            scheduleIdleStop()
        }.onFailure { error ->
            Log.e(TAG, "screen capture failed", error)
            captureManager.fail(requestId, error)
            releaseCaptureSession(stopProjection = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private suspend fun captureFromExistingSession(requestId: String, startId: Int) {
        runCatching {
            val session = captureSession
                ?: error("No active screen capture session. Please approve screen capture again.")
            captureAndSaveScreenshot(session)
        }.onSuccess { result ->
            captureManager.complete(requestId, result)
            scheduleIdleStop()
        }.onFailure { error ->
            Log.e(TAG, "screen capture failed", error)
            captureManager.markSessionActive(false)
            captureManager.fail(requestId, error)
            releaseCaptureSession(stopProjection = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun ensureCaptureSession(resultCode: Int, resultData: Intent): ActiveCaptureSession = synchronized(sessionLock) {
        captureSession?.takeUnless { it.closed.get() }?.let { return it }
        val metrics = captureMetrics()
        val imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            MAX_READER_IMAGES,
        )
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = requireNotNull(projectionManager.getMediaProjection(resultCode, resultData)) {
            imageReader.close()
            "MediaProjection permission result did not create a projection"
        }
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                releaseCaptureSession(stopProjection = false)
                mainHandler.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        projection.registerCallback(projectionCallback, captureHandler)
        val virtualDisplay = runCatching {
            requireNotNull(
                projection.createVirtualDisplay(
                    "AmberAgentScreenCapture",
                    metrics.widthPixels,
                    metrics.heightPixels,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    captureHandler,
                ),
            ) { "MediaProjection failed to create a virtual display" }
        }.onFailure {
            runCatching { imageReader.setOnImageAvailableListener(null, null) }
            runCatching { imageReader.close() }
            runCatching { projection.unregisterCallback(projectionCallback) }
            runCatching { projection.stop() }
        }.getOrThrow()

        ActiveCaptureSession(
            projection = projection,
            projectionCallback = projectionCallback,
            imageReader = imageReader,
            virtualDisplay = virtualDisplay,
            metrics = metrics,
        ).also {
            captureSession = it
            captureManager.markSessionActive(true)
        }
    }

    /**
     * Captures one frame as a cropped [Bitmap] on the background capture thread without PNG encoding.
     */
    internal suspend fun captureBitmapOffMainThread(session: ActiveCaptureSession): Bitmap {
        check(!session.closed.get()) { "Screen capture session has been closed" }
        return try {
            session.awaitBitmap(
                width = session.metrics.widthPixels,
                height = session.metrics.heightPixels,
                workerHandler = captureHandler,
            )
        } finally {
            runCatching { session.imageReader.setOnImageAvailableListener(null, null) }
        }
    }

    /**
     * Explicit screenshot persistence path: only encodes PNG when writing the saved artifact file.
     */
    private suspend fun captureAndSaveScreenshot(session: ActiveCaptureSession): ScreenCaptureResult {
        val bitmap = captureBitmapOffMainThread(session)
        return try {
            withContext(Dispatchers.IO) {
                val output = createOutputFile()
                output.outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                ScreenCaptureResult(
                    file = output,
                    width = session.metrics.widthPixels,
                    height = session.metrics.heightPixels,
                    sizeBytes = output.length(),
                    createdAt = Instant.now(),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun ActiveCaptureSession.awaitBitmap(
        width: Int,
        height: Int,
        workerHandler: Handler,
    ): Bitmap = withTimeout(CAPTURE_TIMEOUT_MS) {
        if (closed.get()) error("Screen capture session is closed")
        // Try immediate acquisition on the calling IO thread (never main thread).
        val immediate = runCatching { imageReader.acquireLatestImage() }.getOrNull()
        if (immediate != null) {
            immediate.use { image ->
                return@withTimeout copyImageToCroppedBitmap(image, width, height)
            }
        }
        suspendCancellableCoroutine { continuation ->
            val listener = ImageReader.OnImageAvailableListener { reader ->
                if (closed.get() || !continuation.isActive) {
                    runCatching { reader.acquireLatestImage()?.close() }
                    runCatching { reader.setOnImageAvailableListener(null, null) }
                    return@OnImageAvailableListener
                }
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                    ?: return@OnImageAvailableListener
                val bitmapResult = runCatching {
                    image.use { img -> copyImageToCroppedBitmap(img, width, height) }
                }
                runCatching { reader.setOnImageAvailableListener(null, null) }
                if (continuation.isActive) {
                    bitmapResult
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.resumeWithException(it) }
                } else {
                    bitmapResult.getOrNull()?.recycle()
                }
            }
            // Deliver ImageReader callbacks strictly on the dedicated background HandlerThread.
            imageReader.setOnImageAvailableListener(listener, workerHandler)
            continuation.invokeOnCancellation {
                runCatching { imageReader.setOnImageAvailableListener(null, null) }
            }
        }
    }

    private fun scheduleIdleStop() {
        mainHandler.removeCallbacks(idleStopRunnable)
        mainHandler.postDelayed(idleStopRunnable, IDLE_STOP_DELAY_MS)
    }

    private fun releaseCaptureSession(stopProjection: Boolean) {
        val session = synchronized(sessionLock) {
            val existing = captureSession ?: return
            captureSession = null
            existing
        }
        if (!session.closed.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(idleStopRunnable)
        runCatching { session.imageReader.setOnImageAvailableListener(null, null) }
        runCatching { session.virtualDisplay.release() }
        runCatching { session.imageReader.close() }
        runCatching { session.projection.unregisterCallback(session.projectionCallback) }
        if (stopProjection) {
            runCatching { session.projection.stop() }
        }
        captureManager.markSessionActive(false)
    }

    private fun captureMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = getSystemService(WindowManager::class.java).maximumWindowMetrics
            val bounds = windowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun createOutputFile(): File {
        val dir = filesDir.resolve("amberagent/artifacts/screenshots")
        dir.mkdirs()
        return dir.resolve("screenshot-${System.currentTimeMillis()}.png")
    }

    companion object {
        const val ACTION_START_SESSION_CAPTURE = "app.amber.agent.action.SCREEN_CAPTURE_START_SESSION"
        const val ACTION_CAPTURE_EXISTING = "app.amber.agent.action.SCREEN_CAPTURE_EXISTING"
        const val ACTION_STOP_SESSION = "app.amber.agent.action.SCREEN_CAPTURE_STOP_SESSION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIFICATION_ID = 2101
        private const val MAX_READER_IMAGES = 2
        private const val CAPTURE_TIMEOUT_MS = 10_000L
        private const val IDLE_STOP_DELAY_MS = 5 * 60_000L

        internal fun copyImageToCroppedBitmap(image: Image, width: Int, height: Int): Bitmap {
            val plane = image.planes.first()
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowPadding = (plane.rowStride - pixelStride * width).coerceAtLeast(0)
            val buffer = plane.buffer
            buffer.rewind()
            if (rowPadding == 0) {
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    copyPixelsFromBuffer(buffer)
                }
            }
            val paddedWidth = width + rowPadding / pixelStride
            val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            return try {
                paddedBitmap.copyPixelsFromBuffer(buffer)
                Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
            } finally {
                paddedBitmap.recycle()
            }
        }
    }
}

internal data class ActiveCaptureSession(
    val projection: MediaProjection,
    val projectionCallback: MediaProjection.Callback,
    val imageReader: ImageReader,
    val virtualDisplay: VirtualDisplay,
    val metrics: DisplayMetrics,
    val closed: AtomicBoolean = AtomicBoolean(false),
)

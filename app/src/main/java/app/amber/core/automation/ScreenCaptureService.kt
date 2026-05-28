package app.amber.core.automation

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import app.amber.agent.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.SCREEN_CAPTURE_NOTIFICATION_CHANNEL_ID
import org.koin.android.ext.android.inject
import java.io.File
import java.time.Instant
import kotlin.coroutines.resume

private const val TAG = "ScreenCaptureService"

class ScreenCaptureService : Service() {
    private val captureManager: ScreenCaptureManager by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
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
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == 0 || resultData == null) {
                    captureManager.fail(IllegalArgumentException("Missing MediaProjection result data"))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    captureFromNewSession(resultCode, resultData, startId)
                }
                return START_NOT_STICKY
            }

            ACTION_CAPTURE_EXISTING -> {
                startForegroundCompat()
                serviceScope.launch {
                    captureFromExistingSession(startId)
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
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private suspend fun captureFromNewSession(resultCode: Int, resultData: Intent, startId: Int) {
        runCatching {
            val session = ensureCaptureSession(resultCode, resultData)
            captureOnce(session)
        }.onSuccess { result ->
            captureManager.complete(result)
            scheduleIdleStop()
        }.onFailure { error ->
            Log.e(TAG, "screen capture failed", error)
            captureManager.fail(error)
            releaseCaptureSession(stopProjection = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private suspend fun captureFromExistingSession(startId: Int) {
        runCatching {
            val session = captureSession
                ?: error("No active screen capture session. Please approve screen capture again.")
            captureOnce(session)
        }.onSuccess { result ->
            captureManager.complete(result)
            scheduleIdleStop()
        }.onFailure { error ->
            Log.e(TAG, "screen capture failed", error)
            captureManager.markSessionActive(false)
            captureManager.fail(error)
            releaseCaptureSession(stopProjection = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun ensureCaptureSession(resultCode: Int, resultData: Intent): ActiveCaptureSession {
        captureSession?.let { return it }
        val metrics = captureMetrics()
        val imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = requireNotNull(projectionManager.getMediaProjection(resultCode, resultData)) {
            "MediaProjection permission result did not create a projection"
        }
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                releaseCaptureSession(stopProjection = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        projection.registerCallback(projectionCallback, mainHandler)
        val virtualDisplay = requireNotNull(
            projection.createVirtualDisplay(
                "AmberAgentScreenCapture",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                mainHandler
            )
        ) { "MediaProjection failed to create a virtual display" }
        return ActiveCaptureSession(
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

    private suspend fun captureOnce(session: ActiveCaptureSession): ScreenCaptureResult {
        return try {
            val bitmap = session.imageReader.awaitBitmap(
                session.metrics.widthPixels,
                session.metrics.heightPixels
            )
            val output = createOutputFile()
            output.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            bitmap.recycle()
            ScreenCaptureResult(
                file = output,
                width = session.metrics.widthPixels,
                height = session.metrics.heightPixels,
                sizeBytes = output.length(),
                createdAt = Instant.now()
            )
        } finally {
            session.imageReader.setOnImageAvailableListener(null, null)
        }
    }

    private suspend fun ImageReader.awaitBitmap(width: Int, height: Int): Bitmap =
        withTimeout(10_000L) {
            acquireLatestImage()?.use { image ->
                return@withTimeout image.toBitmap(width, height)
            }
            suspendCancellableCoroutine { continuation ->
                setOnImageAvailableListener(
                    { reader ->
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        image.use {
                            setOnImageAvailableListener(null, null)
                            if (continuation.isActive) continuation.resume(it.toBitmap(width, height))
                        }
                    },
                    mainHandler
                )
                continuation.invokeOnCancellation {
                    setOnImageAvailableListener(null, null)
                }
            }
        }

    private fun android.media.Image.toBitmap(width: Int, height: Int): Bitmap {
        val plane = planes.first()
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val paddedWidth = width + rowPadding / plane.pixelStride
        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            height,
            Bitmap.Config.ARGB_8888
        )
        paddedBitmap.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
        paddedBitmap.recycle()
        return cropped
    }

    private fun scheduleIdleStop() {
        mainHandler.removeCallbacks(idleStopRunnable)
        mainHandler.postDelayed(idleStopRunnable, IDLE_STOP_DELAY_MS)
    }

    private fun releaseCaptureSession(stopProjection: Boolean) {
        val session = captureSession ?: return
        captureSession = null
        mainHandler.removeCallbacks(idleStopRunnable)
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
        const val ACTION_START_SESSION_CAPTURE = "me.rerere.amberagent.action.SCREEN_CAPTURE_START_SESSION"
        const val ACTION_CAPTURE_EXISTING = "me.rerere.amberagent.action.SCREEN_CAPTURE_EXISTING"
        const val ACTION_STOP_SESSION = "me.rerere.amberagent.action.SCREEN_CAPTURE_STOP_SESSION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIFICATION_ID = 2101
        private const val IDLE_STOP_DELAY_MS = 5 * 60_000L
    }
}

private data class ActiveCaptureSession(
    val projection: MediaProjection,
    val projectionCallback: MediaProjection.Callback,
    val imageReader: ImageReader,
    val virtualDisplay: VirtualDisplay,
    val metrics: DisplayMetrics,
)

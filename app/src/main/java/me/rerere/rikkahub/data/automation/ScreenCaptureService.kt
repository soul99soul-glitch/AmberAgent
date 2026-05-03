package me.rerere.rikkahub.data.automation

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
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
import me.rerere.rikkahub.R
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_CAPTURE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
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
            runCatching {
                captureOnce(resultCode, resultData)
            }.onSuccess { result ->
                captureManager.complete(result)
            }.onFailure { error ->
                Log.e(TAG, "screen capture failed", error)
                captureManager.fail(error)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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

    private suspend fun captureOnce(resultCode: Int, resultData: Intent): ScreenCaptureResult {
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
        var stopped = false
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopped = true
            }
        }
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        val virtualDisplay = requireNotNull(
            projection.createVirtualDisplay(
                "AmberAgentScreenCapture",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                Handler(Looper.getMainLooper())
            )
        ) { "MediaProjection failed to create a virtual display" }
        return try {
            val bitmap = imageReader.awaitBitmap(metrics.widthPixels, metrics.heightPixels)
            val output = createOutputFile()
            output.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            bitmap.recycle()
            ScreenCaptureResult(
                file = output,
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                sizeBytes = output.length(),
                createdAt = Instant.now()
            )
        } finally {
            virtualDisplay.release()
            imageReader.close()
            if (!stopped) projection.stop()
            projection.unregisterCallback(projectionCallback)
        }
    }

    private suspend fun ImageReader.awaitBitmap(width: Int, height: Int): Bitmap =
        withTimeout(10_000L) {
            suspendCancellableCoroutine { continuation ->
                setOnImageAvailableListener(
                    { reader ->
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        image.use {
                            val plane = it.planes.first()
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
                            setOnImageAvailableListener(null, null)
                            if (continuation.isActive) continuation.resume(cropped)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
                continuation.invokeOnCancellation {
                    setOnImageAvailableListener(null, null)
                }
            }
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
        const val ACTION_CAPTURE = "me.rerere.amberagent.action.SCREEN_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIFICATION_ID = 2101
    }
}

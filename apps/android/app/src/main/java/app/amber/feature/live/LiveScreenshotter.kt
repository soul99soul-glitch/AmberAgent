package app.amber.feature.live

import android.content.Context
import android.graphics.Bitmap
import app.amber.core.automation.AmberAccessibilityService
import java.io.File
import java.io.FileOutputStream

/**
 * 激进模式截屏：拍 → 缩放（长边 ≤1280）→ JPEG(80) → cache 文件。
 * 返回 file:// URI 字符串；ai 模块 FileEncoder.encodeBase64 对 file:// 有现成
 * 压缩编码支持，provider 侧无需任何改动。失败返回 null（调用方降级保守）。
 */
class LiveScreenshotter(private val context: Context) {

    suspend fun captureToFileUri(service: AmberAccessibilityService): String? {
        val raw = service.takeScreenshotBitmap() ?: return null
        val scaled = downscale(raw, MAX_LONG_EDGE)
        return runCatching {
            val dir = File(context.cacheDir, "live").apply { mkdirs() }
            val file = File(dir, "live_screenshot.jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            "file://${file.absolutePath}"
        }.getOrNull().also {
            if (scaled !== raw) raw.recycle()
        }
    }

    private fun downscale(src: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxLongEdge) return src
        val scale = maxLongEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        private const val MAX_LONG_EDGE = 1280
        private const val JPEG_QUALITY = 80
    }
}

package app.amber.feature.live

import android.content.Context
import android.graphics.Bitmap
import app.amber.core.automation.AmberAccessibilityService
import java.io.File
import java.io.FileOutputStream

/**
 * 激进模式截屏：优先按候选窗口截（API 34+ takeScreenshotOfWindow，气泡等 overlay 不混入），
 * 低版本/失败回退整屏 → 缩放（长边 ≤1280）→ JPEG(80) → cache 文件。
 * 返回 file:// URI 字符串；ai 模块 FileEncoder.encodeBase64 对 file:// 有现成
 * 压缩编码支持，provider 侧无需任何改动。失败返回 null（调用方降级保守）。
 *
 * 隐私（蓝图 v3 §7.2 P0-6）：截图即用即删——调用方在分析 run 结束后必须调 [cleanup]。
 */
class LiveScreenshotter(private val context: Context) {

    suspend fun captureToFileUri(service: AmberAccessibilityService, windowId: Int = -1): String? {
        val raw = service.takeScreenshotOfWindowBitmap(windowId)
            ?: service.takeScreenshotBitmap()
            ?: return null
        val scaled = downscale(raw, MAX_LONG_EDGE)
        return runCatching {
            val dir = File(context.cacheDir, "live").apply { mkdirs() }
            // 按次唯一文件名：与可能并发的上一次 run 的 cleanup 互不干涉（Phase 5 检查 #1）。
            val file = File(dir, "live_screenshot_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            "file://${file.absolutePath}"
        }.getOrNull().also {
            if (scaled !== raw) raw.recycle()
        }
    }

    /** 删除本次分析的截图文件（幂等；只删指定文件，不动目录里其他 run 的文件）。 */
    fun cleanup(fileUri: String?) {
        val path = fileUri?.removePrefix("file://") ?: return
        runCatching { File(path).delete() }
    }

    /** 目录级清扫：仅 start() 时清旧版本/孤儿残留（无对应 run 的滞留文件）。 */
    fun cleanupOrphans() {
        runCatching { File(context.cacheDir, "live").deleteRecursively() }
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

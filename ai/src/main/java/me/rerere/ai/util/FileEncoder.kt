package me.rerere.ai.util

import android.media.ExifInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Base64OutputStream
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.io.File

private val supportedTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
    "image/heic",
    "image/avif",
)

/** ISO BMFF ftyp brands for HEIC/HEIF containers (HEVC codec, Android 9+ / API 28). */
private val HEIF_BRANDS = setOf(
    "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
    "mif1", "msf1",
)

/** ISO BMFF ftyp brands for AVIF containers (AV1 codec, Android 12+ / API 31). */
private val AVIF_BRANDS = setOf("avif", "avis")

data class EncodedImage(
    val base64: String,
    val mimeType: String
)

class ImageEncodingException(
    val imageUrl: String,
    cause: Throwable
) : IllegalArgumentException("Failed to encode image: ${cause.message}", cause)

internal enum class ExifTransformType {
    NONE,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270,
}

internal fun mapExifOrientationToTransform(orientation: Int): ExifTransformType = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransformType.FLIP_HORIZONTAL
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransformType.ROTATE_180
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransformType.FLIP_VERTICAL
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransformType.TRANSPOSE
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransformType.ROTATE_90
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransformType.TRANSVERSE
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransformType.ROTATE_270
    ExifInterface.ORIENTATION_NORMAL,
    ExifInterface.ORIENTATION_UNDEFINED
    -> ExifTransformType.NONE

    else -> ExifTransformType.NONE
}

fun UIMessagePart.Image.encodeBase64(withPrefix: Boolean = true): Result<EncodedImage> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val mimeType = file.guessMimeType().getOrThrow()
            // 统一进行压缩处理
            val (encoded, outputMimeType) = file.compressAndEncode(mimeType)
            EncodedImage(
                base64 = if (withPrefix) "data:$outputMimeType;base64,$encoded" else encoded,
                mimeType = outputMimeType
            )
        }

        this.url.startsWith("data:") -> {
            // 从 data URL 提取 mime type
            val mimeType = url.substringAfter("data:").substringBefore(";")
            EncodedImage(
                base64 = if (withPrefix) url else url.substringAfter("base64,"),
                mimeType = mimeType
            )
        }
        this.url.startsWith("http") -> {
            // HTTP URL 无法确定 mime type，默认使用 image/png
            EncodedImage(base64 = url, mimeType = "image/png")
        }
        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}.recoverCatching { error ->
    throw if (error is ImageEncodingException) error else ImageEncodingException(url, error)
}

fun UIMessagePart.Video.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:video/mp4;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Audio.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:audio/mp3;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

private fun File.compressAndEncode(
    mimeType: String,
    maxDimension: Int = 2048,
    quality: Int = 85
): Pair<String, String> {
    // GIF 保持原样（可能是动图）
    if (mimeType == "image/gif") {
        return Pair(encodeToBase64Streaming(), mimeType)
    }

    // 读取图片尺寸
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(absolutePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) {
        throw IllegalArgumentException("Failed to decode image dimensions: $absolutePath")
    }

    // 强制压缩处理
    options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension)
    options.inJustDecodeBounds = false

    val bitmap = BitmapFactory.decodeFile(absolutePath, options)
        ?: throw IllegalArgumentException(
            when {
                mimeType == "image/heic" && android.os.Build.VERSION.SDK_INT < 28 ->
                    "Failed to decode image: HEIC format requires Android 9 or above"
                mimeType == "image/avif" && android.os.Build.VERSION.SDK_INT < 31 ->
                    "Failed to decode image: AVIF format requires Android 12 or above"
                else ->
                    "Failed to decode image: $absolutePath"
            }
        )
    val normalizedBitmap = normalizeByExif(bitmap)

    return try {
        val byteArrayOutputStream = ByteArrayOutputStream()
        // 强制使用 JPEG 格式，因为很多提供商不支持 webp
        Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP).use { base64Stream ->
            normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, base64Stream)
        }
        Pair(byteArrayOutputStream.toString(Charsets.ISO_8859_1.name()), "image/jpeg")
    } finally {
        if (normalizedBitmap !== bitmap) {
            normalizedBitmap.recycle()
        }
        bitmap.recycle()
    }
}

private fun File.normalizeByExif(bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = mapExifOrientationToTransform(orientation)
    return applyExifTransform(bitmap, transform)
}

private fun applyExifTransform(bitmap: Bitmap, transform: ExifTransformType): Bitmap {
    if (transform == ExifTransformType.NONE) return bitmap

    val matrix = Matrix()
    when (transform) {
        ExifTransformType.NONE -> return bitmap
        ExifTransformType.FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifTransformType.ROTATE_180 -> matrix.setRotate(180f)
        ExifTransformType.FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifTransformType.TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_90 -> matrix.setRotate(90f)
        ExifTransformType.TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_270 -> matrix.setRotate(270f)
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrElse { bitmap }
}

private fun File.encodeToBase64Streaming(): String {
    val byteArrayOutputStream = ByteArrayOutputStream()
    Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP).use { base64Stream ->
        inputStream().use { input ->
            input.copyTo(base64Stream, bufferSize = 8 * 1024)
        }
    }
    return byteArrayOutputStream.toString(Charsets.ISO_8859_1.name())
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    while ((height / inSampleSize) > reqHeight || (width / inSampleSize) > reqWidth) {
        inSampleSize *= 2
    }
    // Safety net: cap decoded pixel count at ~16 MP to prevent OOM on
    // ultra-high-res sensors (50 MP+) and panoramas.
    val maxPixels = 16_000_000L
    while ((width.toLong() / inSampleSize) * (height.toLong() / inSampleSize) > maxPixels) {
        inSampleSize *= 2
    }
    return inSampleSize
}

private fun File.guessMimeType(): Result<String> = runCatching {
    inputStream().use { input ->
        val bytes = ByteArray(16)
        val read = input.read(bytes)
        if (read < 12) error("File too short to determine MIME type")

        // 判断 HEIC/HEIF 格式：ISO BMFF ftyp box at offset 4.
        // Common brands: heic, heix, hevc, hevx, heim, heis, hevm, hevs,
        // mif1, msf1, avif, avis — all are HEIF-family containers that
        // Android 9+ (API 28) can decode via BitmapFactory.
        val ftypTag = bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII)
        if (ftypTag == "ftyp") {
            val brand = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)
            if (brand in HEIF_BRANDS) return@runCatching "image/heic"
            if (brand in AVIF_BRANDS) return@runCatching "image/avif"
        }

        // 判断 JPEG 格式：开头为 0xFF 0xD8
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return@runCatching "image/jpeg"
        }

        // 判断 PNG 格式：开头为 89 50 4E 47 0D 0A 1A 0A
        if (bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) {
            return@runCatching "image/png"
        }

        // 判断WebP格式：开头为 "RIFF" + 4字节长度 + "WEBP"
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12)
                .toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return@runCatching "image/webp"
        }

        // 判断 GIF 格式：开头为 "GIF89a" 或 "GIF87a"
        val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (header == "GIF89a" || header == "GIF87a") {
            return@runCatching "image/gif"
        }

        error(
            "Failed to guess MIME type: $header, ${
                bytes.joinToString(",") {
                    it.toUByte().toString()
                }
            }"
        )
    }
}

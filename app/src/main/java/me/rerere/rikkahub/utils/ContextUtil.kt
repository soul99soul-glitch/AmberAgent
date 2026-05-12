package me.rerere.rikkahub.utils

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private const val TAG = "ContextUtil"

/**
 * Read clipboard data as text
 */
fun Context.readClipboardText(): String {
    val clipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = clipboardManager.primaryClip ?: return ""
    val item = clip.getItemAt(0) ?: return ""
    return item.text.toString()
}

/**
 * 发起添加群流程
 *
 * @param key 由官网生成的key
 * @return 返回true表示呼起手Q成功，返回false表示呼起失败
 */
fun Context.joinQQGroup(key: String?): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
    intent.setData(("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$key").toUri())
    // 此Flag可根据具体产品需要自定义，如设置，则在加群界面按返回，返回手Q主界面，不设置，按返回会返回到呼起产品界面    //intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
        return true
    } catch (e: java.lang.Exception) {
        // 未安装手Q或安装的版本不支持
        return false
    }
}

/**
 * Write text into clipboard
 */
fun Context.writeClipboardText(text: String) {
    val clipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    runCatching {
        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        Log.i(TAG, "writeClipboardText: $text")
    }.onFailure {
        Log.e(TAG, "writeClipboardText: $text", it)
        Toast.makeText(this, "Failed to write text into clipboard", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Open a url
 */
fun Context.openUrl(url: String) {
    val normalizedUrl = normalizeExternalUrl(url)
    Log.i(TAG, "openUrl: $normalizedUrl")
    if (normalizedUrl.isBlank()) return
    val parsedUri = normalizedUrl.toUri()
    val bilibiliLink = parsedUri.toBilibiliLink()
    val fallbackUri = bilibiliLink?.webUri ?: parsedUri

    if (bilibiliLink != null) {
        if (tryOpenBilibiliUrl(bilibiliLink.appUris)) return
        if (tryOpenExternalUrl(fallbackUri)) return
    }

    runCatching {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        intent.launchUrl(this, fallbackUri)
    }.onFailure {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, fallbackUri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .withContextLaunchFlags(this)
            )
        }.onFailure { fallbackError ->
            fallbackError.printStackTrace()
            Toast.makeText(this, "Failed to open URL: $normalizedUrl", Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun normalizeExternalUrl(rawUrl: String): String {
    val trimmed = rawUrl
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("\\&", "&")
        .replace("\\(", "(")
        .replace("\\)", ")")
        .replace("\u200B", "")
        .replace("\u200C", "")
        .replace("\u200D", "")
        .replace("\uFEFF", "")
        .trim()
        .trim('<', '>', '"', '\'', '`')
        .trimMarkdownUrlTail()
    if (trimmed.isBlank()) return ""
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("bilibili://", ignoreCase = true) -> trimmed

        trimmed.startsWith("www.", ignoreCase = true) ||
            trimmed.startsWith("m.", ignoreCase = true) ||
            trimmed.startsWith("b23.tv", ignoreCase = true) -> "https://$trimmed"

        else -> trimmed
    }
}

private fun String.trimMarkdownUrlTail(): String {
    var result = this
    while (result.startsWith("(") && result.endsWith(")") && result.count { it == '(' } < result.count { it == ')' } + 1) {
        result = result.substring(1, result.lastIndex).trim()
    }
    while (result.isNotEmpty()) {
        val last = result.last()
        val shouldTrim = when (last) {
            ')' -> result.count { it == ')' } > result.count { it == '(' }
            ']' -> result.count { it == ']' } > result.count { it == '[' }
            '}' -> result.count { it == '}' } > result.count { it == '{' }
            '>' -> true
            '。', '，', '、', ',', ';' -> true
            else -> false
        }
        if (!shouldTrim) break
        result = result.dropLast(1).trimEnd()
    }
    return result
}

private fun Context.tryOpenBilibiliUrl(uris: List<Uri>): Boolean {
    val appCandidates = listOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "com.bilibili.app.blue",
    )
    appCandidates.forEach { packageName ->
        uris.forEach { uri ->
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(packageName)
                .withContextLaunchFlags(this)
            if (runCatching { startActivity(intent) }.isSuccess) {
                return true
            }
        }
    }
    return false
}

private fun Context.tryOpenExternalUrl(uri: Uri): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .withContextLaunchFlags(this)
    return runCatching { startActivity(intent) }.isSuccess
}

private data class BilibiliLink(
    val appUris: List<Uri>,
    val webUri: Uri,
)

private fun Uri.toBilibiliLink(): BilibiliLink? {
    val scheme = scheme?.lowercase()
    if (scheme == "bilibili") {
        val webUri = toBilibiliDeepLinkWebUri() ?: return BilibiliLink(appUris = listOf(this), webUri = this)
        return BilibiliLink(appUris = listOf(webUri, this).distinct(), webUri = webUri)
    }
    if (scheme != "http" && scheme != "https") return null
    val host = host?.lowercase()?.removePrefix("www.") ?: return null
    if (host == "b23.tv") return BilibiliLink(appUris = listOf(this), webUri = this)
    if (host != "bilibili.com" && host != "m.bilibili.com") return null

    val videoId = pathSegments
        .dropWhile { !it.equals("video", ignoreCase = true) }
        .drop(1)
        .firstOrNull()
        ?.takeIf { it.startsWith("BV", ignoreCase = true) || it.startsWith("av", ignoreCase = true) }

    if (videoId != null) {
        val webUri = buildBilibiliVideoWebUri(videoId)
        return BilibiliLink(
            appUris = listOf(webUri, buildBilibiliVideoAppUri(videoId)).distinct(),
            webUri = webUri,
        )
    }
    return BilibiliLink(appUris = listOf(this), webUri = this)
}

private fun Uri.toBilibiliDeepLinkWebUri(): Uri? {
    val host = host?.lowercase() ?: return null
    val firstPath = pathSegments.firstOrNull()
    return when (host) {
        "video" -> {
            val rawVideoId = firstPath ?: return null
            val videoId = when {
                rawVideoId.startsWith("BV", ignoreCase = true) -> rawVideoId
                rawVideoId.startsWith("av", ignoreCase = true) -> rawVideoId
                rawVideoId.all { it.isDigit() } -> "av$rawVideoId"
                else -> return null
            }
            buildBilibiliVideoWebUri(videoId)
        }

        "space" -> {
            val mid = firstPath?.takeIf { it.all { char -> char.isDigit() } } ?: return null
            buildUpon()
                .scheme("https")
                .authority("space.bilibili.com")
                .path(null)
                .appendPath(mid)
                .build()
        }

        else -> null
    }
}

private fun Uri.buildBilibiliVideoAppUri(videoId: String): Uri {
    val builder = Uri.Builder()
        .scheme("bilibili")
        .authority("video")
        .appendPath(videoId)
    encodedQuery?.let { builder.encodedQuery(it) }
    encodedFragment?.let { builder.encodedFragment(it) }
    return builder.build()
}

private fun Uri.buildBilibiliVideoWebUri(videoId: String): Uri {
    val builder = Uri.Builder()
        .scheme("https")
        .authority("www.bilibili.com")
        .appendPath("video")
        .appendPath(videoId)
    encodedQuery?.let { builder.encodedQuery(it) }
    encodedFragment?.let { builder.encodedFragment(it) }
    return builder.build()
}

private fun Intent.withContextLaunchFlags(context: Context): Intent {
    if (context !is Activity) {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return this
}

fun Context.getActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

fun Context.getComponentActivity(): ComponentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

fun Context.exportImage(
    activity: Activity,
    bitmap: Bitmap,
    fileName: String = "AmberAgent_${System.currentTimeMillis()}.png"
) {
    // 检查存储权限（Android 9及以下需要）
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1
            )
            return
        }
    }

    // 保存到相册
    var outputStream: OutputStream? = null
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10及以上使用MediaStore API
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                outputStream = contentResolver.openOutputStream(it)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream!!)
            }
        } else {
            // Android 9及以下直接写入文件
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val image = File(imagesDir, fileName)
            outputStream = FileOutputStream(image)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)

            // 通知图库更新
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(image)
            sendBroadcast(mediaScanIntent)
        }
        Log.i(TAG, "Image saved successfully: $fileName")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save image", e)
    } finally {
        outputStream?.close()
    }
}

fun Context.exportJpegImage(
    activity: Activity,
    bitmap: Bitmap,
    fileName: String = "AmberAgent_Widget_${System.currentTimeMillis()}.jpg"
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1
            )
            return false
        }
    }

    var outputStream: OutputStream? = null
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            outputStream = uri?.let { contentResolver.openOutputStream(it) }
            val stream = checkNotNull(outputStream) { "Unable to open image output stream" }
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream)
        } else {
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val image = File(imagesDir, fileName)
            outputStream = FileOutputStream(image)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, outputStream)

            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(image)
            sendBroadcast(mediaScanIntent)
        }
        Log.i(TAG, "JPEG image saved successfully: $fileName")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save JPEG image", e)
        false
    } finally {
        outputStream?.close()
    }
}

fun Context.exportImageFile(
    activity: Activity,
    file: File,
    fileName: String = "AmberAgent_${System.currentTimeMillis()}.png"
) {
    // 检查存储权限（Android 9及以下需要）
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1
            )
            return
        }
    }

    // 保存到相册
    var outputStream: OutputStream? = null
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10及以上使用MediaStore API
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                outputStream = contentResolver.openOutputStream(it)
                file.inputStream().copyTo(outputStream!!)
            }
        } else {
            // Android 9及以下直接写入文件
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val image = File(imagesDir, fileName)
            file.copyTo(image, overwrite = true)

            // 通知图库更新
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(image)
            sendBroadcast(mediaScanIntent)
        }
        Log.i(TAG, "Image file saved successfully: $fileName")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save image file", e)
    } finally {
        outputStream?.close()
    }
}

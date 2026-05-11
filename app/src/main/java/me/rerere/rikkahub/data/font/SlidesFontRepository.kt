package me.rerere.rikkahub.data.font

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.datastore.GenerativeUiSetting
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

private const val FONT_CATALOG_ASSET = "font-packs/slides-font-packs.json"
private const val FONT_MANIFEST_FILE = "manifest.json"
private const val FONT_HOST = "amberagent.local"

@Serializable
enum class FontPackCategory {
    @SerialName("serif")
    SERIF,

    @SerialName("sans")
    SANS,

    @SerialName("handwriting")
    HANDWRITING,

    @SerialName("mono")
    MONO,
}

@Serializable
data class FontPack(
    val id: String,
    val displayName: String,
    val category: FontPackCategory,
    val language: String,
    val familyCss: String,
    val fileName: String,
    val sourceUrl: String,
    val licenseName: String,
    val licenseUrl: String,
    val sourcePageUrl: String,
    val fileSizeBytes: Long,
    val sha256: String,
)

data class FontPackState(
    val pack: FontPack,
    val installedPath: String?,
    val installedAtMillis: Long?,
) {
    val installed: Boolean = installedPath != null
}

data class FontDownloadProgress(
    val packId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

data class SlidesFontHint(
    val pack: FontPack?,
    val requestedPackId: String,
)

@Serializable
private data class InstalledFontManifest(
    val fonts: List<InstalledFontRecord> = emptyList(),
)

@Serializable
private data class InstalledFontRecord(
    val id: String,
    val fileName: String,
    val sha256: String,
    val installedAtMillis: Long,
)

class SlidesFontRepository(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val fontsDir = File(context.filesDir, "fonts")
    private val manifestFile = File(fontsDir, FONT_MANIFEST_FILE)
    private val catalog: List<FontPack> by lazy { loadCatalog() }
    private val _fontsFlow = MutableStateFlow<List<FontPackState>>(emptyList())
    private val _downloadsFlow = MutableStateFlow<Map<String, FontDownloadProgress>>(emptyMap())

    val fontsFlow: StateFlow<List<FontPackState>> = _fontsFlow.asStateFlow()
    val downloadsFlow: StateFlow<Map<String, FontDownloadProgress>> = _downloadsFlow.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val installed = readManifest().fonts.associateBy { it.id }
        _fontsFlow.value = catalog.map { pack ->
            val record = installed[pack.id]?.takeIf { record ->
                val file = fontFile(pack, record.fileName)
                file.isFile && record.sha256.equals(pack.sha256, ignoreCase = true)
            }
            FontPackState(
                pack = pack,
                installedPath = record?.let { fontFile(pack, it.fileName).absolutePath },
                installedAtMillis = record?.installedAtMillis,
            )
        }
    }

    fun getPack(id: String): FontPack? = catalog.firstOrNull { it.id == id }

    suspend fun download(packId: String) = withContext(Dispatchers.IO) {
        val pack = getPack(packId) ?: error("未知字体包: $packId")
        fontsDir.mkdirs()
        val packDir = File(fontsDir, pack.id).apply { mkdirs() }
        val tmp = File(packDir, "${pack.fileName}.download")
        val target = File(packDir, pack.fileName)
        val request = Request.Builder().url(pack.sourceUrl).build()
        _downloadsFlow.updateProgress(pack.id, 0L, pack.fileSizeBytes)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("字体下载失败: HTTP ${response.code}")
            }
            val body = response.body
            val total = body.contentLength().takeIf { it > 0L } ?: pack.fileSizeBytes
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        _downloadsFlow.updateProgress(pack.id, downloaded, total)
                    }
                }
            }
        }
        val actualSha = sha256(tmp)
        if (!actualSha.equals(pack.sha256, ignoreCase = true)) {
            tmp.delete()
            error("字体校验失败: $actualSha")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        val records = readManifest().fonts
            .filterNot { it.id == pack.id }
            .plus(
                InstalledFontRecord(
                    id = pack.id,
                    fileName = pack.fileName,
                    sha256 = pack.sha256,
                    installedAtMillis = System.currentTimeMillis(),
                )
            )
        writeManifest(InstalledFontManifest(records.sortedBy { it.id }))
        _downloadsFlow.value = _downloadsFlow.value - pack.id
        refresh()
    }

    suspend fun delete(packId: String) = withContext(Dispatchers.IO) {
        val pack = getPack(packId) ?: return@withContext
        File(fontsDir, pack.id).deleteRecursively()
        writeManifest(
            InstalledFontManifest(readManifest().fonts.filterNot { it.id == pack.id })
        )
        refresh()
    }

    fun buildSlidesFontCss(specJson: String, setting: GenerativeUiSetting): String {
        val deck = runCatching { json.parseToJsonElement(specJson) as? JsonObject }.getOrNull()
        val requested = deck?.string("fontPack")
        val style = deck?.string("style")?.lowercase(Locale.ROOT).orEmpty()
        val fallback = when {
            style == "swiss" -> setting.slidesSwissFontPack
            style == "magazine" -> setting.slidesMagazineFontPack
            else -> null
        }
        val pack = listOfNotNull(requested, fallback)
            .firstNotNullOfOrNull { id -> installedPack(id) }
            ?: return ""
        val family = "AmberSlides-${pack.pack.id}"
        val format = when (pack.pack.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "otf" -> "opentype"
            "ttf" -> "truetype"
            else -> "truetype"
        }
        val source = "https://$FONT_HOST/fonts/${pack.pack.id}/${pack.pack.fileName}"
        val vars = when (pack.pack.category) {
            FontPackCategory.SERIF -> """
                --serif-zh: "$family", "Noto Serif SC", "Source Han Serif SC", "Songti SC", serif;
            """.trimIndent()

            FontPackCategory.SANS -> """
                --sans-zh: "$family", "PingFang SC", "Source Han Sans SC", "Noto Sans SC", sans-serif;
                --sans: "$family", "Inter", "Helvetica Neue", system-ui, sans-serif;
            """.trimIndent()

            FontPackCategory.HANDWRITING -> """
                --serif-zh: "$family", "Noto Serif SC", "Source Han Serif SC", serif;
                --sans-zh: "$family", "PingFang SC", "Noto Sans SC", sans-serif;
            """.trimIndent()

            FontPackCategory.MONO -> """
                --mono: "$family", "JetBrains Mono", ui-monospace, monospace;
            """.trimIndent()
        }
        return """
            @font-face{
              font-family:"$family";
              src:url("$source") format("$format");
              font-weight:400;
              font-style:normal;
              font-display:swap;
            }
            :root{$vars}
        """.trimIndent()
    }

    fun resolveSlidesFontHint(specJson: String, setting: GenerativeUiSetting): SlidesFontHint? {
        val deck = runCatching { json.parseToJsonElement(specJson) as? JsonObject }.getOrNull()
        val requested = deck?.string("fontPack")
        val style = deck?.string("style")?.lowercase(Locale.ROOT).orEmpty()
        val fallback = when {
            style == "swiss" -> setting.slidesSwissFontPack
            style == "magazine" -> setting.slidesMagazineFontPack
            else -> null
        }
        val packId = listOfNotNull(requested, fallback).firstOrNull() ?: return null
        if (installedPack(packId) != null) return null
        return SlidesFontHint(
            pack = getPack(packId),
            requestedPackId = packId,
        )
    }

    fun interceptFontRequest(request: WebResourceRequest?): WebResourceResponse? {
        val uri = request?.url ?: return null
        if (uri.scheme != "https" || uri.host != FONT_HOST) return null
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[0] != "fonts") return emptyResponse()
        val pack = getPack(segments[1]) ?: return emptyResponse()
        if (segments[2] != pack.fileName) return emptyResponse()
        val installed = installedPack(pack.id) ?: return emptyResponse()
        val file = File(installed.installedPath ?: return emptyResponse())
        if (!file.isFile) return emptyResponse()
        return WebResourceResponse(
            fontMimeType(pack.fileName),
            null,
            FileInputStream(file),
        )
    }

    private fun installedPack(id: String): FontPackState? =
        fontsFlow.value.firstOrNull { it.pack.id == id && it.installed }

    private fun loadCatalog(): List<FontPack> =
        context.assets.open(FONT_CATALOG_ASSET).use { input ->
            json.decodeFromString(
                ListSerializer(FontPack.serializer()),
                input.reader(Charsets.UTF_8).readText(),
            )
        }

    private fun readManifest(): InstalledFontManifest =
        runCatching {
            if (!manifestFile.isFile) return InstalledFontManifest()
            json.decodeFromString(InstalledFontManifest.serializer(), manifestFile.readText())
        }.getOrDefault(InstalledFontManifest())

    private fun writeManifest(manifest: InstalledFontManifest) {
        fontsDir.mkdirs()
        val tmp = File(fontsDir, "$FONT_MANIFEST_FILE.tmp")
        tmp.writeText(json.encodeToString(InstalledFontManifest.serializer(), manifest))
        if (manifestFile.exists()) manifestFile.delete()
        if (!tmp.renameTo(manifestFile)) {
            tmp.copyTo(manifestFile, overwrite = true)
            tmp.delete()
        }
    }

    private fun fontFile(pack: FontPack, fileName: String) = File(File(fontsDir, pack.id), fileName)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private fun MutableStateFlow<Map<String, FontDownloadProgress>>.updateProgress(
    packId: String,
    downloadedBytes: Long,
    totalBytes: Long,
) {
    value = value + (packId to FontDownloadProgress(packId, downloadedBytes, totalBytes))
}

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun fontMimeType(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "otf" -> "font/otf"
        "ttf" -> "font/ttf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

private fun emptyResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", "".byteInputStream())

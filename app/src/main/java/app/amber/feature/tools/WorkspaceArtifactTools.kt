package app.amber.feature.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.document.nativebridge.OfficeNativeSwitch
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import app.amber.feature.workspace.WorkspaceManager
import app.amber.feature.workspace.WorkspacePaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WorkspaceArtifactTools(
    private val context: Context,
    private val workspaceManager: WorkspaceManager,
    private val activityStore: AgentToolActivityStore,
) {
    fun getTools(): List<Tool> = listOf(
        httpRequestTool,
        downloadFileTool,
        archiveListTool,
        archiveExtractTool,
        archiveCreateTool,
        pdfReadTool,
        pdfRenderPageTool,
        officeReadTool,
        imageInfoTool,
        imageConvertTool,
        ocrImageTool,
    )

    private val httpRequestTool = Tool(
        name = "http_request",
        description = "Make a bounded HTTP/HTTPS request. Does not send app cookies or private device data. Response body is truncated at 512KB.",
        parameters = {
            obj(
                "method" to stringProp("HTTP method. Defaults to GET."),
                "url" to stringProp("HTTP or HTTPS URL."),
                "headers" to objectProp("Optional string headers."),
                "body" to stringProp("Optional request body."),
                "timeout_ms" to integerProp("Timeout in milliseconds. Defaults to 20000."),
                required = listOf("url")
            )
        },
        execute = { input ->
            trackArtifactTool("http_request", "HTTP 请求", input, runtime = "HttpURLConnection") {
                withContext(Dispatchers.IO) {
                    val result = request(input)
                    listOf(UIMessagePart.Text(result.toString()))
                }
            }
        }
    )

    private val downloadFileTool = Tool(
        name = "download_file",
        description = "Download an HTTP/HTTPS URL into the user-authorized /workspace. Defaults to /workspace/downloads/<filename>.",
        parameters = {
            obj(
                "url" to stringProp("HTTP or HTTPS URL to download."),
                "workspace_path" to stringProp("Optional destination path under /workspace."),
                required = listOf("url")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackArtifactTool("download_file", "下载文件", input, runtime = "HTTP -> SAF workspace") {
                withContext(Dispatchers.IO) {
                    val url = input.requiredString("url")
                    requireHttpUrl(url)
                    val connection = openConnection(url, "GET", timeoutMs = 30_000)
                    val status = connection.responseCode
                    require(status in 200..299) { "Download failed with HTTP $status" }
                    val bytes = connection.inputStream.use { it.readBytesLimited(MAX_DOWNLOAD_BYTES) }
                    val destination = input.string("workspace_path")
                        ?.takeIf { it.isNotBlank() }
                        ?: "downloads/${safeFileName(fileNameFromUrl(url))}"
                    val entry = workspaceManager.writeBytes(
                        relativePath = destination,
                        bytes = bytes,
                        mimeType = connection.contentType?.substringBefore(";") ?: "application/octet-stream"
                    )
                    textJson {
                        put("status", "saved")
                        put("path", entry.path)
                        put("size_bytes", bytes.size)
                        put("mime_type", entry.mimeType.orEmpty())
                    }
                }
            }
        }
    )

    private val archiveListTool = Tool(
        name = "archive_list",
        description = "List entries in a /workspace archive. Supports zip, tar, tar.gz, and tgz.",
        parameters = {
            obj(
                "path" to stringProp("Archive path under /workspace."),
                "limit" to integerProp("Maximum entries. Defaults to 200."),
                required = listOf("path")
            )
        },
        execute = { input ->
            trackArtifactTool("archive_list", "查看压缩包", input) {
                val path = input.requiredString("path")
                val limit = input.limit(default = 200, max = 1000)
                val bytes = workspaceManager.readBytes(path)
                val entries = listArchiveEntries(path, bytes, limit)
                textJson {
                    put("path", path)
                    put("entries", entries)
                }
            }
        }
    )

    private val archiveExtractTool = Tool(
        name = "archive_extract",
        description = "Extract a zip/tar/tar.gz/tgz archive inside /workspace. Blocks Zip Slip and path traversal.",
        parameters = {
            obj(
                "path" to stringProp("Archive path under /workspace."),
                "destination_path" to stringProp("Destination directory under /workspace. Defaults to /workspace/extracted/<archive-name>."),
                "overwrite" to booleanProp("Overwrite existing files. Defaults to false."),
                required = listOf("path")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackArtifactTool("archive_extract", "解压压缩包", input) {
                val path = input.requiredString("path")
                val destination = input.string("destination_path")?.takeIf { it.isNotBlank() }
                    ?: "extracted/${safeBaseName(path)}"
                val overwrite = input.boolean("overwrite") ?: false
                val bytes = workspaceManager.readBytes(path)
                val result = extractArchive(path, bytes, destination, overwrite)
                textJson {
                    put("path", path)
                    put("destination_path", destination)
                    put("files_written", result.files)
                    put("directories_seen", result.directories)
                    put("bytes_written", result.bytes)
                }
            }
        }
    )

    private val archiveCreateTool = Tool(
        name = "archive_create",
        description = "Create a zip archive from /workspace files or folders. Stage 1 supports format=zip.",
        parameters = {
            obj(
                "source_paths" to arrayProp("Workspace-relative files/folders to include."),
                "destination_path" to stringProp("Destination archive path under /workspace."),
                "format" to stringProp("Archive format. Stage 1 supports zip."),
                required = listOf("source_paths", "destination_path")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackArtifactTool("archive_create", "创建压缩包", input) {
                val format = input.string("format") ?: "zip"
                require(format == "zip") { "archive_create stage1 supports format=zip only" }
                val sources = input.jsonObject["source_paths"]?.jsonArrayStrings().orEmpty()
                require(sources.isNotEmpty()) { "source_paths must not be empty" }
                val bytes = createZip(sources)
                val entry = workspaceManager.writeBytes(
                    relativePath = input.requiredString("destination_path"),
                    bytes = bytes,
                    mimeType = "application/zip"
                )
                textJson {
                    put("path", entry.path)
                    put("format", "zip")
                    put("size_bytes", bytes.size)
                }
            }
        }
    )

    private val pdfReadTool = Tool(
        name = "pdf_read",
        description = "Extract text from a PDF file in /workspace using the bundled MuPDF parser.",
        parameters = {
            obj(
                "path" to stringProp("PDF path under /workspace."),
                "max_chars" to integerProp("Maximum text characters. Defaults to 20000."),
                required = listOf("path")
            )
        },
        execute = { input ->
            trackArtifactTool("pdf_read", "读取 PDF", input, runtime = "MuPDF") {
                val path = input.requiredString("path")
                val text = withTempWorkspaceFile(path) { file -> PdfParser.parserPdf(file) }
                val maxChars = input.limit("max_chars", default = 20_000, max = 80_000)
                textJson {
                    put("path", path)
                    put("text", text.take(maxChars))
                    put("text_chars", text.length)
                    put("truncated", text.length > maxChars)
                }
            }
        }
    )

    private val pdfRenderPageTool = Tool(
        name = "pdf_render_page",
        description = "Render one PDF page from /workspace to a PNG artifact in /workspace/previews.",
        parameters = {
            obj(
                "path" to stringProp("PDF path under /workspace."),
                "page" to integerProp("1-based page number. Defaults to 1."),
                "destination_path" to stringProp("Optional PNG output path under /workspace."),
                required = listOf("path")
            )
        },
        execute = { input ->
            trackArtifactTool("pdf_render_page", "渲染 PDF 页面", input, runtime = "PdfRenderer") {
                val path = input.requiredString("path")
                val pageIndex = (input.int("page") ?: 1).coerceAtLeast(1) - 1
                val png = renderPdfPage(path, pageIndex)
                val dest = input.string("destination_path")?.takeIf { it.isNotBlank() }
                    ?: "previews/${safeBaseName(path)}-page-${pageIndex + 1}.png"
                val entry = workspaceManager.writeBytes(dest, png, "image/png")
                textJson {
                    put("path", entry.path)
                    put("page", pageIndex + 1)
                    put("size_bytes", png.size)
                }
            }
        }
    )

    private val officeReadTool = Tool(
        name = "office_read",
        description = "Read basic text from docx, xlsx, or pptx files in /workspace.",
        parameters = {
            obj(
                "path" to stringProp("Office document path under /workspace."),
                "max_chars" to integerProp("Maximum text characters. Defaults to 20000."),
                required = listOf("path")
            )
        },
        execute = { input ->
            trackArtifactTool("office_read", "读取 Office 文档", input) {
                val path = input.requiredString("path")
                val text = withTempWorkspaceFile(path) { file ->
                    // Phase 2 Step 1: route docx/pptx/epub through OfficeNativeSwitch.
                    // Phase 3 D-1: xlsx also routes through Switch (net-new,
                    // no JVM XlsxParser; fallback is the in-app `parseXlsxText`
                    // helper; diff sampling for xlsx is hard-gated off until a
                    // JVM-side byte baseline exists).
                    when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                        "docx" -> OfficeNativeSwitch.parseDocxOrNull(file) {
                            DocxParser.parse(file)
                        } ?: DocxParser.parse(file)
                        "pptx" -> OfficeNativeSwitch.parsePptxOrNull(file) {
                            PptxParser.parse(file)
                        } ?: PptxParser.parse(file)
                        "xlsx" -> OfficeNativeSwitch.parseXlsxOrNull(file) {
                            parseXlsxText(file)
                        } ?: parseXlsxText(file)
                        else -> error("Unsupported Office extension: $path")
                    }
                }
                val maxChars = input.limit("max_chars", default = 20_000, max = 80_000)
                textJson {
                    put("path", path)
                    put("text", text.take(maxChars))
                    put("text_chars", text.length)
                    put("truncated", text.length > maxChars)
                }
            }
        }
    )

    private val imageInfoTool = Tool(
        name = "image_info",
        description = "Read basic image metadata from a /workspace image.",
        parameters = {
            obj(
                "path" to stringProp("Image path under /workspace."),
                required = listOf("path")
            )
        },
        execute = { input ->
            trackArtifactTool("image_info", "读取图片信息", input) {
                val path = input.requiredString("path")
                val bytes = workspaceManager.readBytes(path)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                textJson {
                    put("path", path)
                    put("width", options.outWidth)
                    put("height", options.outHeight)
                    put("mime_type", options.outMimeType.orEmpty())
                    put("size_bytes", bytes.size)
                    put("exif_orientation", readExifOrientation(bytes))
                }
            }
        }
    )

    private val imageConvertTool = Tool(
        name = "image_convert",
        description = "Convert or resize a /workspace image to png, jpg, or webp.",
        parameters = {
            obj(
                "path" to stringProp("Input image path under /workspace."),
                "destination_path" to stringProp("Output path under /workspace."),
                "format" to stringProp("png, jpg, or webp. Defaults from destination extension or png."),
                "max_width" to integerProp("Optional maximum width."),
                "max_height" to integerProp("Optional maximum height."),
                "quality" to integerProp("Output quality 1-100 for jpg/webp. Defaults to 90."),
                required = listOf("path", "destination_path")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackArtifactTool("image_convert", "转换图片", input) {
                val source = input.requiredString("path")
                val destination = input.requiredString("destination_path")
                val sourceBytes = workspaceManager.readBytes(source)
                val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
                    ?: error("Unable to decode image: $source")
                val scaled = scaleBitmapIfNeeded(
                    bitmap = bitmap,
                    maxWidth = input.int("max_width"),
                    maxHeight = input.int("max_height")
                )
                val format = (input.string("format") ?: destination.substringAfterLast('.', "png"))
                    .lowercase(Locale.ROOT)
                val bytes = encodeBitmap(scaled, format, input.limit("quality", default = 90, max = 100))
                val mime = when (format) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    else -> "image/png"
                }
                val entry = workspaceManager.writeBytes(destination, bytes, mime)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                textJson {
                    put("path", entry.path)
                    put("mime_type", mime)
                    put("size_bytes", bytes.size)
                }
            }
        }
    )

    private val ocrImageTool = Tool(
        name = "ocr_image",
        description = "OCR a /workspace image. Stage 1 reports runtime availability; use a VLM model if OCR runtime is unavailable.",
        parameters = {
            obj(
                "path" to stringProp("Image path under /workspace."),
                required = listOf("path")
            )
        },
        execute = { input ->
            trackArtifactTool("ocr_image", "图片 OCR", input) {
                textJson {
                    put("path", input.requiredString("path"))
                    put("status", "unavailable")
                    put("runtime", "stage1-no-local-ocr")
                    put("message", "Local OCR is not bundled yet. Use image_info plus a configured remote multimodal/VLM model for OCR in this build.")
                }
            }
        }
    )

    private suspend fun trackArtifactTool(
        toolName: String,
        title: String,
        input: JsonElement,
        runtime: String = "Workspace artifact",
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        val toolCallId = activityStore.startTool(
            toolName = toolName,
            title = title,
            inputPreview = input.toString().take(1200),
            runtime = runtime,
            workspace = "/workspace",
        )
        return try {
            val result = block()
            activityStore.complete(toolCallId, result.previewText())
            result
        } catch (error: Throwable) {
            activityStore.fail(toolCallId, error)
            throw error
        }
    }

    private fun request(input: JsonElement) = buildJsonObject {
        val url = input.requiredString("url")
        val method = (input.string("method") ?: "GET").uppercase(Locale.ROOT)
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) { "Unsupported HTTP method: $method" }
        requireHttpUrl(url)
        val timeout = input.limit("timeout_ms", default = 20_000, max = 120_000)
        val connection = openConnection(url, method, timeout)
        input.jsonObject["headers"]?.jsonObject?.forEach { (name, value) ->
            connection.setRequestProperty(name, value.toString().trim('"'))
        }
        input.string("body")?.let { body ->
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val body = stream?.use { String(it.readBytesLimited(MAX_HTTP_BODY_BYTES), Charsets.UTF_8) }.orEmpty()
        put("status_code", status)
        put("url", connection.url.toString())
        put("headers", buildJsonObject {
            connection.headerFields
                .filterKeys { it != null }
                .forEach { (name, values) -> put(name.orEmpty(), values.joinToString(", ")) }
        })
        put("body", body)
        put("truncated", body.toByteArray().size >= MAX_HTTP_BODY_BYTES)
    }

    private fun openConnection(url: String, method: String, timeoutMs: Int): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AmberAgent/0.8")
        }

    private fun requireHttpUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme == "http" || uri.scheme == "https") { "Only http and https URLs are allowed" }
    }

    private fun listArchiveEntries(path: String, bytes: ByteArray, limit: Int) = buildJsonArray {
        when (archiveType(path)) {
            ArchiveType.Zip -> ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var count = 0
                while (count < limit) {
                    val entry = zip.nextEntry ?: break
                    add(entryJson(entry.name, entry.isDirectory, entry.size))
                    count++
                }
            }
            ArchiveType.Tar, ArchiveType.TarGz -> tarInput(path, bytes).use { input ->
                var count = 0
                while (count < limit) {
                    val header = readTarHeader(input) ?: break
                    add(entryJson(header.name, header.directory, header.size))
                    skipFully(input, alignTarSize(header.size))
                    count++
                }
            }
        }
    }

    private suspend fun extractArchive(
        path: String,
        bytes: ByteArray,
        destination: String,
        overwrite: Boolean,
    ): ArchiveExtractResult {
        val stats = ArchiveExtractResult()
        when (archiveType(path)) {
            ArchiveType.Zip -> ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = safeArchiveTarget(destination, entry.name)
                    if (entry.isDirectory) {
                        stats.directories++
                    } else {
                        if (!overwrite && runCatching { workspaceManager.readBytes(target) }.isSuccess) {
                            error("Target already exists: $target")
                        }
                        val data = zip.readBytesLimited(MAX_ARCHIVE_ENTRY_BYTES)
                        workspaceManager.writeBytes(target, data, mimeFromName(target))
                        stats.files++
                        stats.bytes += data.size
                    }
                }
            }
            ArchiveType.Tar, ArchiveType.TarGz -> tarInput(path, bytes).use { input ->
                while (true) {
                    val header = readTarHeader(input) ?: break
                    val target = safeArchiveTarget(destination, header.name)
                    if (header.directory) {
                        stats.directories++
                    } else {
                        if (!overwrite && runCatching { workspaceManager.readBytes(target) }.isSuccess) {
                            error("Target already exists: $target")
                        }
                        require(header.size <= MAX_ARCHIVE_ENTRY_BYTES) {
                            "Archive entry is too large: ${header.name} (${header.size} bytes)"
                        }
                        val data = input.readExactly(header.size.toInt())
                        workspaceManager.writeBytes(target, data, mimeFromName(target))
                        skipFully(input, alignTarSize(header.size) - header.size)
                        stats.files++
                        stats.bytes += data.size
                    }
                }
            }
        }
        return stats
    }

    private suspend fun createZip(sources: List<String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            sources.forEach { source ->
                val entries = runCatching { workspaceManager.list(source) }.getOrNull()
                if (entries == null) {
                    val normalized = WorkspacePaths.normalize(source)
                    addZipEntry(zip, normalized, workspaceManager.readBytes(normalized))
                } else {
                    entries.forEach { entry ->
                        if (!entry.directory) addZipEntry(zip, entry.path, workspaceManager.readBytes(entry.path))
                    }
                }
            }
        }
        return output.toByteArray()
    }

    private fun addZipEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val safe = WorkspacePaths.normalize(path).removePrefix("./")
        zip.putNextEntry(ZipEntry(safe))
        zip.write(bytes)
        zip.closeEntry()
    }

    private suspend fun <T> withTempWorkspaceFile(path: String, block: (File) -> T): T =
        withContext(Dispatchers.IO) {
            val bytes = workspaceManager.readBytes(path)
            val ext = path.substringAfterLast('.', "bin")
            val file = File.createTempFile("amberagent-${safeBaseName(path)}", ".$ext", context.cacheDir)
            try {
                file.writeBytes(bytes)
                block(file)
            } finally {
                file.delete()
            }
        }

    private suspend fun renderPdfPage(path: String, pageIndex: Int): ByteArray = withTempWorkspaceFile(path) { file ->
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(pageIndex in 0 until renderer.pageCount) { "page is out of range. PDF has ${renderer.pageCount} pages." }
                renderer.openPage(pageIndex).use { page ->
                    val width = page.width.coerceAtLeast(1)
                    val height = page.height.coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        bitmap.recycle()
                        output.toByteArray()
                    }
                }
            }
        }
    }

    private fun parseXlsxText(file: File): String {
        val text = StringBuilder()
        java.util.zip.ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { it.name == "xl/sharedStrings.xml" || it.name.startsWith("xl/worksheets/sheet") }
                .forEach { entry ->
                    text.appendLine("## ${entry.name}")
                    text.appendLine(stripXml(zip.getInputStream(entry).bufferedReader().readText()))
                }
        }
        return text.toString()
    }

    private fun stripXml(xml: String): String =
        xml.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun readExifOrientation(bytes: ByteArray): Int =
        runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
        }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxWidth: Int?, maxHeight: Int?): Bitmap {
        val widthLimit = maxWidth?.takeIf { it > 0 } ?: bitmap.width
        val heightLimit = maxHeight?.takeIf { it > 0 } ?: bitmap.height
        val scale = minOf(widthLimit.toFloat() / bitmap.width, heightLimit.toFloat() / bitmap.height, 1f)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    private fun encodeBitmap(bitmap: Bitmap, format: String, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            val compressFormat = when (format) {
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                else -> Bitmap.CompressFormat.PNG
            }
            bitmap.compress(compressFormat, quality.coerceIn(1, 100), output)
            output.toByteArray()
        }

    private fun entryJson(name: String, directory: Boolean, size: Long) = buildJsonObject {
        put("path", name)
        put("directory", directory)
        if (size >= 0) put("size_bytes", size)
    }

    private fun tarInput(path: String, bytes: ByteArray) =
        if (archiveType(path) == ArchiveType.TarGz) GZIPInputStream(ByteArrayInputStream(bytes)) else ByteArrayInputStream(bytes)

    private fun readTarHeader(input: java.io.InputStream): TarHeader? {
        val header = input.readExactlyOrNull(512) ?: return null
        if (header.all { it.toInt() == 0 }) return null
        val name = header.stringField(0, 100)
        val size = header.stringField(124, 12).trim().toLongOrNull(radix = 8) ?: 0L
        val type = header.getOrNull(156)?.toInt()?.toChar()
        return TarHeader(
            name = name,
            size = size,
            directory = type == '5' || name.endsWith("/")
        )
    }

    private fun ByteArray.stringField(offset: Int, length: Int): String =
        copyOfRange(offset, offset + length)
            .takeWhile { it.toInt() != 0 }
            .toByteArray()
            .toString(Charsets.UTF_8)
            .trim()

    private fun alignTarSize(size: Long): Long = ((size + 511) / 512) * 512

    private fun safeArchiveTarget(destination: String, entryName: String): String {
        val cleanName = entryName.trim().replace("\\", "/")
        require(cleanName.isNotBlank() && !cleanName.startsWith("/") && !cleanName.contains("../")) {
            "Unsafe archive entry: $entryName"
        }
        return WorkspacePaths.normalize("${WorkspacePaths.normalize(destination)}/$cleanName")
    }

    private fun archiveType(path: String): ArchiveType {
        val lower = path.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".zip") -> ArchiveType.Zip
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveType.TarGz
            lower.endsWith(".tar") -> ArchiveType.Tar
            else -> error("Unsupported archive type: $path")
        }
    }

    private fun mimeFromName(name: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: "application/octet-stream"

    private fun fileNameFromUrl(url: String): String =
        URI(url).path?.substringAfterLast("/")?.takeIf { it.isNotBlank() } ?: "download.bin"

    private fun safeBaseName(path: String): String =
        safeFileName(path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "artifact" })

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file.bin" }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            val allowed = (limit - total).coerceAtMost(read)
            if (allowed > 0) {
                output.write(buffer, 0, allowed)
                total += allowed
            }
            if (total >= limit) break
        }
        return output.toByteArray()
    }

    private fun java.io.InputStream.readExactly(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(bytes, offset, length - offset)
            if (read < 0) error("Unexpected end of stream")
            offset += read
        }
        return bytes
    }

    private fun java.io.InputStream.readExactlyOrNull(length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(bytes, offset, length - offset)
            if (read < 0) return if (offset == 0) null else error("Unexpected end of stream")
            offset += read
        }
        return bytes
    }

    private fun skipFully(input: java.io.InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun obj(vararg properties: Pair<String, JsonElement>, required: List<String>? = null) =
        InputSchema.Obj(
            properties = buildJsonObject {
                properties.forEach { (name, schema) -> put(name, schema) }
            },
            required = required
        )

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun booleanProp(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun objectProp(description: String) = buildJsonObject {
        put("type", "object")
        put("description", description)
    }

    private fun arrayProp(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject { put("type", "string") })
    }

    private fun JsonElement.limit(name: String = "limit", default: Int, max: Int): Int =
        (int(name) ?: default).coerceIn(1, max)

    private fun JsonElement.jsonArrayStrings(): List<String> =
        jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }

    private fun List<UIMessagePart>.previewText(): String =
        joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)

    private enum class ArchiveType { Zip, Tar, TarGz }

    private data class TarHeader(
        val name: String,
        val size: Long,
        val directory: Boolean,
    )

    private data class ArchiveExtractResult(
        var files: Int = 0,
        var directories: Int = 0,
        var bytes: Int = 0,
    )

    companion object {
        private const val MAX_HTTP_BODY_BYTES = 512 * 1024
        private const val MAX_DOWNLOAD_BYTES = 128 * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRY_BYTES = 64 * 1024 * 1024
    }
}

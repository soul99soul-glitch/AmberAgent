package app.amber.core.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.common.android.Logging
import app.amber.agent.AppScope
import app.amber.agent.data.db.entity.ManagedFileEntity
import app.amber.core.repository.FilesRepository
import app.amber.core.utils.exportImage
import app.amber.core.utils.exportImageFile
import app.amber.core.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
    private val appScope: AppScope,
) {
    companion object {
        private const val TAG = "FilesManager"
        private const val MAX_CHAT_ATTACHMENT_BYTES = 128L * 1024 * 1024
    }

    suspend fun saveUploadFromUri(
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val resolvedName = displayName ?: getFileNameFromUri(uri) ?: "file"
        val resolvedMime = mimeType ?: getFileMimeType(uri) ?: "application/octet-stream"
        requireUriSizeWithinLimit(uri, MAX_CHAT_ATTACHMENT_BYTES)
        val target = createTargetFile(FileFolders.UPLOAD, resolvedName, resolvedMime)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyToWithinLimit(output, MAX_CHAT_ATTACHMENT_BYTES, resolvedName)
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        val now = System.currentTimeMillis()
        repository.insert(
            ManagedFileEntity(
                folder = FileFolders.UPLOAD,
                relativePath = "${FileFolders.UPLOAD}/${target.name}",
                displayName = resolvedName,
                mimeType = resolvedMime,
                sizeBytes = target.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun saveUploadFromBytes(
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(FileFolders.UPLOAD, displayName, mimeType)
        target.writeBytes(bytes)
        val now = System.currentTimeMillis()
        repository.insert(
            ManagedFileEntity(
                folder = FileFolders.UPLOAD,
                relativePath = "${FileFolders.UPLOAD}/${target.name}",
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = target.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun saveUploadText(
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(FileFolders.UPLOAD, displayName, mimeType)
        target.writeText(text)
        val now = System.currentTimeMillis()
        repository.insert(
            ManagedFileEntity(
                folder = FileFolders.UPLOAD,
                relativePath = "${FileFolders.UPLOAD}/${target.name}",
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = target.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ManagedFileEntity>> =
        repository.listByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        repository.listByFolder(folder).first()

    suspend fun get(id: Long): ManagedFileEntity? = repository.getById(id)

    suspend fun getByRelativePath(relativePath: String): ManagedFileEntity? = repository.getByPath(relativePath)

    fun getFile(entity: ManagedFileEntity): File =
        File(context.filesDir, entity.relativePath)

    suspend fun createChatFilesByContents(uris: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        createChatFilesByContentsBlocking(uris)
    }

    private fun createChatFilesByContentsBlocking(uris: List<Uri>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        uris.forEach { uri ->
            runCatching {
                val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                val sourceMime = getFileMimeType(uri)
                requireUriSizeWithinLimit(uri, MAX_CHAT_ATTACHMENT_BYTES)
                val fileName = buildUuidFileName(displayName = sourceName, mimeType = sourceMime)
                val file = dir.resolve(fileName)
                if (!file.exists()) {
                    file.createNewFile()
                }
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to open input stream for $uri")
                try {
                    inputStream.use { input ->
                        file.outputStream().use { output ->
                            input.copyToWithinLimit(output, MAX_CHAT_ATTACHMENT_BYTES, sourceName)
                        }
                    }
                } catch (error: Throwable) {
                    file.delete()
                    throw error
                }
                val guessedMime = sourceMime ?: guessMimeType(file, sourceName)
                trackUploadFile(file = file, displayName = sourceName, mimeType = guessedMime)
                newUris.add(file.toUri())
            }.onFailure {
                it.printStackTrace()
                Log.e(TAG, "createChatFilesByContents: Failed to save file from $uri", it)
                Logging.log(
                    TAG,
                    "createChatFilesByContents: Failed to save file from $uri ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
        return newUris
    }

    fun createChatFilesByByteArrays(byteArrays: List<ByteArray>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            require(byteArray.size <= MAX_CHAT_ATTACHMENT_BYTES) {
                "Chat attachment exceeds $MAX_CHAT_ATTACHMENT_BYTES byte limit"
            }
            val fileName = buildUuidFileName(displayName = "image.png", mimeType = "image/png")
            val file = dir.resolve(fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            val newUri = file.toUri()
            file.outputStream().use { outputStream ->
                outputStream.write(byteArray)
            }
            trackUploadFile(file = file, displayName = "image.png", mimeType = "image/png")
            newUris.add(newUri)
        }
        return newUris
    }

    private fun requireUriSizeWithinLimit(uri: Uri, maxBytes: Long) {
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        require(size < 0 || size <= maxBytes) { "Chat attachment exceeds $maxBytes byte limit" }
    }

    private fun InputStream.copyToWithinLimit(output: OutputStream, maxBytes: Long, label: String): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            require(total <= maxBytes - read) { "Chat attachment exceeds $maxBytes byte limit: $label" }
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Image -> {
                            if (part.url.startsWith("data:image")) {
                                val sourceByteArray = Base64.decode(part.url.substringAfter("base64,").toByteArray())
                                val bitmap = BitmapFactory.decodeByteArray(sourceByteArray, 0, sourceByteArray.size)
                                if (bitmap == null) {
                                    Log.w(
                                        TAG,
                                        "convertBase64ImagePartToLocalFile: undecodable image (${sourceByteArray.size} bytes); keeping data url part"
                                    )
                                    return@map part
                                }
                                val byteArray = bitmap.compressToPng()
                                val urls = createChatFilesByByteArrays(listOf(byteArray))
                                Log.i(
                                    TAG,
                                    "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
                                )
                                part.copy(
                                    url = urls.first().toString(),
                                )
                            } else {
                                part
                            }
                        }

                        else -> part
                    }
                }
            )
        }

    fun deleteChatFiles(uris: List<Uri>) {
        appScope.launch(Dispatchers.IO) {
            // Files under the workspace mirror are user-visible to Agent tools as
            // `/workspace/uploads/<name>` (and may have been moved/renamed inside the
            // workspace by the user or the Agent itself). Conversation deletion or
            // attachment removal must NOT drag those files into the bin — leave them in
            // place and let the user manage workspace storage explicitly.
            val relativePaths = mutableSetOf<String>()
            uris.filter { it.toString().startsWith("file:") }.forEach { uri ->
                runCatching {
                    val file = uri.toFile()
                    val relativePath = getRelativePathInFilesDir(file)
                    if (relativePath == null || !relativePath.startsWith("${FileFolders.UPLOAD}/")) {
                        return@runCatching
                    }
                    relativePaths.add(relativePath)
                    if (file.exists()) {
                        file.delete()
                    }
                }.onFailure {
                    Log.e(TAG, "deleteChatFiles: Failed to delete $uri", it)
                    Logging.log(TAG, "deleteChatFiles: Failed $uri ${it.message} | ${it.stackTraceToString()}")
                }
            }
            relativePaths.forEach { path ->
                runCatching {
                    repository.deleteByPath(path)
                }.onFailure {
                    Log.e(TAG, "deleteChatFiles: Failed to forget $path", it)
                    Logging.log(TAG, "deleteChatFiles: Failed $path ${it.message} | ${it.stackTraceToString()}")
                }
            }
        }
    }

    suspend fun countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            return@withContext Pair(0, 0)
        }
        val files = dir.listFiles() ?: return@withContext Pair(0, 0)
        val count = files.size
        val size = files.sumOf { it.length() }
        Pair(count, size)
    }

    suspend fun createChatTextFile(text: String): UIMessagePart.Document = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
        val file = dir.resolve(fileName)
        file.writeText(text)
        trackUploadFile(file = file, displayName = "pasted_text.txt", mimeType = "text/plain")
        return@withContext UIMessagePart.Document(
            url = file.toUri().toString(),
            fileName = "pasted_text.txt",
            mime = "text/plain"
        )
    }

    fun getImagesDir(): File {
        val dir = context.filesDir.resolve("images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Per-conversation storage for chat-inline generated images. Separate from
     * the global gallery `getImagesDir()` because chat-generated images live
     * and die with the conversation (user must explicitly export to MediaStore
     * via the long-press menu to keep them around).
     */
    fun getChatImagesDir(conversationId: Uuid): File {
        val dir = context.filesDir.resolve("chat_images").resolve(conversationId.toString())
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** Recursively delete all chat-inline generated images for a conversation. */
    fun deleteChatImagesDir(conversationId: Uuid) {
        val dir = context.filesDir.resolve("chat_images").resolve(conversationId.toString())
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createImageFileFromBase64(base64Data: String, filePath: String): File {
        val data = if (base64Data.startsWith("data:image")) {
            base64Data.substringAfter("base64,")
        } else {
            base64Data
        }

        val byteArray = Base64.decode(data.toByteArray())
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArray)
        return file
    }

    fun listImageFiles(): List<File> {
        val imagesDir = getImagesDir()
        return imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.toList()
            ?: emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveMessageImage(activityContext: Context, image: String) = withContext(Dispatchers.IO) {
        val activity = requireNotNull(activityContext.getActivity()) { "Activity not found" }
        when {
            image.startsWith("data:image") -> {
                val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                    ?: error("Cannot decode image data")
                activityContext.exportImage(activity, bitmap)
            }

            image.startsWith("file:") -> {
                val file = image.toUri().toFile()
                activityContext.exportImageFile(activity, file)
            }

            image.startsWith("/") -> {
                activityContext.exportImageFile(activity, File(image))
            }

            image.startsWith("http") -> {
                runCatching {
                    val url = URL(image)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connect()

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                        activityContext.exportImage(activity, bitmap)
                    } else {
                        Log.e(
                            TAG,
                            "saveMessageImage: Failed to download image from $image, response code: ${connection.responseCode}"
                        )
                    }
                }.getOrNull()
            }

            else -> error("Invalid image format")
        }
    }

    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): Int = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) return@withContext 0
        val files = dir.listFiles()?.filter { it.isFile } ?: return@withContext 0
        var inserted = 0
        files.forEach { file ->
            val relativePath = "${folder}/${file.name}"
            val existing = repository.getByPath(relativePath)
            if (existing == null) {
                val now = System.currentTimeMillis()
                val displayName = file.name
                val mimeType = guessMimeType(file, displayName)
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = file.lastModified().takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    )
                )
                inserted += 1
            }
        }
        inserted
    }

    suspend fun delete(id: Long, deleteFromDisk: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext false
        if (deleteFromDisk) {
            runCatching { getFile(entity).delete() }
        }
        repository.deleteById(id) > 0
    }

    private fun createTargetFile(folder: String, displayName: String, mimeType: String?): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, buildUuidFileName(displayName = displayName, mimeType = mimeType))
    }

    private fun buildUuidFileName(displayName: String?, mimeType: String?): String {
        val extFromName = displayName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it != displayName }
            ?.lowercase()
        val extFromMime = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.lowercase()) }
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()
        val ext = extFromName ?: extFromMime ?: "bin"
        return "${Uuid.random()}.$ext"
    }

    private fun trackUploadFile(file: File, displayName: String, mimeType: String) {
        val relativePath = "${FileFolders.UPLOAD}/${file.name}"
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = repository.getByPath(relativePath)
                if (existing != null) {
                    return@runCatching
                }
                val now = System.currentTimeMillis()
                repository.insert(
                    ManagedFileEntity(
                        folder = FileFolders.UPLOAD,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }.onFailure {
                Log.e(TAG, "trackUploadFile: Failed to track file ${file.absolutePath}", it)
                Logging.log(
                    TAG,
                    "trackUploadFile: Failed to track file ${file.absolutePath} ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
    }

    private fun getRelativePathInFilesDir(file: File): String? {
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val canonicalFilesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return null
        val basePath = canonicalFilesDir.path
        val filePath = canonicalFile.path
        if (!filePath.startsWith("$basePath${File.separator}")) {
            return null
        }
        return canonicalFile.relativeTo(canonicalFilesDir).path.replace(File.separatorChar, '/')
    }

    fun getFileNameFromUri(uri: Uri): String? {
        return runCatching {
            var fileName: String? = null
            val projection = arrayOf(
                OpenableColumns.DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val documentDisplayNameIndex =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (documentDisplayNameIndex != -1) {
                        fileName = cursor.getString(documentDisplayNameIndex)
                    } else {
                        val openableDisplayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (openableDisplayNameIndex != -1) {
                            fileName = cursor.getString(openableDisplayNameIndex)
                        }
                    }
                }
            }
            fileName
        }.onFailure {
            Log.w(TAG, "getFileNameFromUri: Failed to query display name for $uri", it)
        }.getOrNull()
    }

    fun getFileMimeType(uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> runCatching {
                context.contentResolver.getType(uri)
            }.onFailure {
                Log.w(TAG, "getFileMimeType: Failed to resolve MIME for $uri", it)
            }.getOrNull()
            // file:// URIs (workspace-staged shares, audio picker output, anything we
            // already copied into our own filesDir) used to silently return null and
            // fall through to the Document branch in ChatPage — so an audio file landed
            // in chat with a generic doc icon and the wrong mime tag. Resolve via the
            // path's extension instead.
            "file" -> {
                val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
                ext?.takeIf { it.isNotEmpty() }
                    ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            }
            else -> null
        }
    }

    private fun guessMimeType(file: File, fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }
        return sniffMimeType(file)
    }

    private fun sniffMimeType(file: File): String {
        val header = ByteArray(16)
        val read = runCatching {
            FileInputStream(file).use { input ->
                input.read(header)
            }
        }.getOrDefault(-1)

        if (read <= 0) return "application/octet-stream"

        // Magic numbers
        if (header.startsWithBytes(0x89, 0x50, 0x4E, 0x47)) return "image/png"
        if (header.startsWithBytes(0xFF, 0xD8, 0xFF)) return "image/jpeg"
        if (header.startsWithBytes(0x47, 0x49, 0x46, 0x38)) return "image/gif"
        if (header.startsWithBytes(0x25, 0x50, 0x44, 0x46)) return "application/pdf"
        if (header.startsWithBytes(0x50, 0x4B, 0x03, 0x04)) return "application/zip"
        if (header.startsWithBytes(0x50, 0x4B, 0x05, 0x06)) return "application/zip"
        if (header.startsWithBytes(0x50, 0x4B, 0x07, 0x08)) return "application/zip"
        if (header.startsWithBytes(0x52, 0x49, 0x46, 0x46) && header.sliceArray(8..11)
                .contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50))
        ) {
            return "image/webp"
        }

        // Heuristic: treat mostly printable UTF-8 as text/plain
        val textSample = runCatching {
            val sample = ByteArray(512)
            FileInputStream(file).use { input ->
                val len = input.read(sample)
                if (len <= 0) return@runCatching null
                sample.copyOf(len)
            }
        }.getOrNull()
        if (textSample != null && isLikelyText(textSample)) {
            return "text/plain"
        }

        return "application/octet-stream"
    }

    private fun isLikelyText(bytes: ByteArray): Boolean {
        var printable = 0
        var total = 0
        bytes.forEach { b ->
            val c = b.toInt() and 0xFF
            total += 1
            if (c == 0x09 || c == 0x0A || c == 0x0D) {
                printable += 1
            } else if (c in 0x20..0x7E) {
                printable += 1
            }
        }
        return total > 0 && printable.toDouble() / total >= 0.8
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (this.size < values.size) return false
        for (i in values.indices) {
            if ((this[i].toInt() and 0xFF) != values[i]) return false
        }
        return true
    }

    private fun Bitmap.compressToPng(): ByteArray = ByteArrayOutputStream().use {
        compress(Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
    }
}

object FileFolders {
    const val UPLOAD = "upload"
    const val SKILLS = "skills"
    /** Per-conversation generate_image output — `filesDir/chat_images/{convId}/…` */
    const val CHAT_IMAGES = "chat_images"
    /** Standalone ImgGenPage gallery — `filesDir/images/…` */
    const val IMAGES = "images"
}

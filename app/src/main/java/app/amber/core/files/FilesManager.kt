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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
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
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.utils.exportImage
import app.amber.core.utils.exportImageFile
import app.amber.core.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.Uuid

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
    private val appScope: AppScope,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
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
            } ?: error("Failed to open input stream for $uri")
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        try {
            withManagedFileWrite {
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
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun saveUploadFromBytes(
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        require(bytes.size.toLong() <= MAX_CHAT_ATTACHMENT_BYTES) {
            "Chat attachment exceeds $MAX_CHAT_ATTACHMENT_BYTES byte limit"
        }
        val target = createTargetFile(FileFolders.UPLOAD, displayName, mimeType)
        try {
            target.writeBytes(bytes)
            withManagedFileWrite {
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
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun saveUploadText(
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val encodedText = text.toByteArray(Charsets.UTF_8)
        require(encodedText.size.toLong() <= MAX_CHAT_ATTACHMENT_BYTES) {
            "Chat attachment exceeds $MAX_CHAT_ATTACHMENT_BYTES byte limit"
        }
        val target = createTargetFile(FileFolders.UPLOAD, displayName, mimeType)
        try {
            target.writeBytes(encodedText)
            withManagedFileWrite {
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
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ManagedFileEntity>> =
        repository.listByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        repository.listByFolder(folder).first()

    suspend fun get(id: Long): ManagedFileEntity? = repository.getById(id)

    suspend fun getByRelativePath(relativePath: String): ManagedFileEntity? = repository.getByPath(relativePath)

    fun getFile(entity: ManagedFileEntity): File =
        File(context.filesDir, entity.relativePath)

    suspend fun createChatFilesByContents(uris: List<Uri>): List<Uri> {
        // Carry the generation epoch through the staged file and managed-row write.
        val expectedRestoreEpoch = currentCoroutineContext()[SyncRestoreWriteEpoch]?.value
        return withContext(Dispatchers.IO) {
            createChatFilesByContentsBlocking(uris, expectedRestoreEpoch)
        }
    }

    private suspend fun createChatFilesByContentsBlocking(
        uris: List<Uri>,
        expectedRestoreEpoch: Long?,
    ): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        uris.forEach { uri ->
            var file: File? = null
            runCatching {
                val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                val sourceMime = getFileMimeType(uri)
                requireUriSizeWithinLimit(uri, MAX_CHAT_ATTACHMENT_BYTES)
                val fileName = buildUuidFileName(displayName = sourceName, mimeType = sourceMime)
                val targetFile = dir.resolve(fileName)
                file = targetFile
                if (!targetFile.exists()) {
                    targetFile.createNewFile()
                }
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to open input stream for $uri")
                inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyToWithinLimit(output, MAX_CHAT_ATTACHMENT_BYTES, sourceName)
                    }
                }
                val guessedMime = sourceMime ?: guessMimeType(targetFile, sourceName)
                trackUploadFile(
                    file = targetFile,
                    displayName = sourceName,
                    mimeType = guessedMime,
                    expectedRestoreEpoch = expectedRestoreEpoch,
                )
                newUris.add(targetFile.toUri())
            }.onFailure {
                file?.delete()
                if (it is CancellationException) throw it
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

    suspend fun createChatFilesByByteArrays(
        byteArrays: List<ByteArray>,
        expectedRestoreEpoch: Long? = null,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            require(byteArray.size.toLong() <= MAX_CHAT_ATTACHMENT_BYTES) {
                "Chat attachment exceeds $MAX_CHAT_ATTACHMENT_BYTES byte limit"
            }
            val fileName = buildUuidFileName(displayName = "image.png", mimeType = "image/png")
            val file = dir.resolve(fileName)
            try {
                if (!file.exists()) {
                    file.createNewFile()
                }
                file.outputStream().use { outputStream ->
                    outputStream.write(byteArray)
                }
                trackUploadFile(
                    file = file,
                    displayName = "image.png",
                    mimeType = "image/png",
                    expectedRestoreEpoch = expectedRestoreEpoch,
                )
                newUris.add(file.toUri())
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
        newUris
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
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage {
        val expectedRestoreEpoch = currentCoroutineContext()[SyncRestoreWriteEpoch]?.value
        return withContext(Dispatchers.IO) {
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
                                val urls = createChatFilesByByteArrays(
                                    byteArrays = listOf(byteArray),
                                    expectedRestoreEpoch = expectedRestoreEpoch,
                                )
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
    }

    fun deleteChatFiles(uris: List<Uri>, expectedRestoreEpoch: Long? = null) =
        appScope.launch(Dispatchers.IO + restoreEpochContext(expectedRestoreEpoch)) {
            runCatching { deleteChatFilesInternal(uris) }
                .onFailure {
                    Log.e(TAG, "deleteChatFiles: cleanup rejected or failed", it)
                    Logging.log(TAG, "deleteChatFiles: cleanup failed ${it.message} | ${it.stackTraceToString()}")
                }
        }

    /** Await cleanup when conversation deletion needs a decidable outcome. */
    suspend fun deleteChatFilesAndAwait(
        uris: List<Uri>,
        expectedRestoreEpoch: Long? = null,
    ) {
        val capturedEpoch = expectedRestoreEpoch
            ?: currentCoroutineContext()[SyncRestoreWriteEpoch]?.value
        withContext(Dispatchers.IO + restoreEpochContext(capturedEpoch)) {
            deleteChatFilesInternal(uris)
        }
    }

    private suspend fun deleteChatFilesInternal(uris: List<Uri>) {
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
            }.onFailure {
                Logging.log(TAG, "deleteChatFiles: Failed $uri ${it.message} | ${it.stackTraceToString()}")
            }
        }
        relativePaths.forEach { path ->
            try {
                withManagedFileWrite {
                    // Keep the local unlink beside the managed-file row delete
                    // so restore cannot replace the row between the two
                    // operations. This is one short path write, not a long
                    // attachment copy.
                    val file = File(context.filesDir, path)
                    check(!file.exists() || file.delete()) { "Failed to delete $path" }
                    repository.deleteByPath(path)
                }
            } catch (rejected: app.amber.core.sync.core.SyncRestoreWriteRejectedException) {
                // A stale cleanup must be observable to the suspend/awaiting
                // caller and must never continue to a physical unlink.
                throw rejected
            } catch (error: Throwable) {
                Log.e(TAG, "deleteChatFiles: Failed to forget $path", error)
                Logging.log(TAG, "deleteChatFiles: Failed $path ${error.message} | ${error.stackTraceToString()}")
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

    suspend fun createChatTextFile(text: String): UIMessagePart.Document {
        val encodedText = text.toByteArray(Charsets.UTF_8)
        require(encodedText.size.toLong() <= MAX_CHAT_ATTACHMENT_BYTES) {
            "Chat attachment exceeds $MAX_CHAT_ATTACHMENT_BYTES byte limit"
        }
        val expectedRestoreEpoch = currentCoroutineContext()[SyncRestoreWriteEpoch]?.value
        return withContext(Dispatchers.IO) {
            val dir = context.filesDir.resolve(FileFolders.UPLOAD)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
            val file = dir.resolve(fileName)
            try {
                file.writeBytes(encodedText)
                trackUploadFile(
                    file = file,
                    displayName = "pasted_text.txt",
                    mimeType = "text/plain",
                    expectedRestoreEpoch = expectedRestoreEpoch,
                )
                UIMessagePart.Document(
                    url = file.toUri().toString(),
                    fileName = "pasted_text.txt",
                    mime = "text/plain"
                )
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
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
        deleteChatImagesDirInternal(conversationId)
    }

    /**
     * Await image cleanup for a conversation deletion. The caller may pass the
     * epoch captured when its cleanup started so a stale cleanup is rejected
     * before it can remove images restored into the same directory.
     */
    suspend fun deleteChatImagesDirAndAwait(
        conversationId: Uuid,
        expectedRestoreEpoch: Long? = null,
    ) {
        val capturedEpoch = expectedRestoreEpoch
            ?: currentCoroutineContext()[SyncRestoreWriteEpoch]?.value
        withContext(Dispatchers.IO + restoreEpochContext(capturedEpoch)) {
            withManagedFileWrite {
                deleteChatImagesDirInternal(conversationId)
            }
        }
    }

    private fun deleteChatImagesDirInternal(conversationId: Uuid) {
        val dir = context.filesDir.resolve("chat_images").resolve(conversationId.toString())
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    /** Remove only unreferenced generated images owned by this conversation. */
    suspend fun deleteChatImageFiles(conversationId: Uuid, uris: List<Uri>) = withContext(Dispatchers.IO) {
        val root = context.filesDir.resolve("chat_images/$conversationId").canonicalFile
        uris.forEach { uri ->
            if (uri.scheme != "file") return@forEach
            val file = uri.toFile().canonicalFile
            if (file.parentFile == root && file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete generated image: $file")
            }
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
    suspend fun saveMessageImage(activityContext: Context, image: String): Boolean = withContext(Dispatchers.IO) {
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
                    try {
                        connection.connect()
                        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                            Log.e(
                                TAG,
                                "saveMessageImage: Failed to download image from $image, response code: ${connection.responseCode}"
                            )
                            return@runCatching false
                        }
                        connection.inputStream.use { input ->
                            val bitmap = BitmapFactory.decodeStream(input)
                                ?: error("Cannot decode downloaded image")
                            activityContext.exportImage(activity, bitmap)
                        }
                    } finally {
                        connection.disconnect()
                    }
                }.getOrElse {
                    if (it is CancellationException) throw it
                    Log.e(TAG, "saveMessageImage: Failed to download image from $image", it)
                    false
                }
            }

            else -> error("Invalid image format")
        }
    }

    /** Reconcile files from an external caller; durable rows are restore-gated. */
    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): Int =
        withContext(Dispatchers.IO) {
            syncFolderInternal(folder, gateWrites = true)
        }

    /**
     * Restore-owned reconciliation. [SyncArchiveManager] already holds the
     * restore gate while importing its file tree, so acquiring the same mutex
     * here would deadlock. Keep this entry internal and use it only from that
     * restore pipeline; background callbacks must call [syncFolder].
     */
    internal suspend fun syncFolderDuringRestore(folder: String = FileFolders.UPLOAD): Int =
        withContext(Dispatchers.IO) {
            syncFolderInternal(folder, gateWrites = false)
        }

    private suspend fun syncFolderInternal(folder: String, gateWrites: Boolean): Int {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) return 0
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0
        var inserted = 0
        files.forEach { file ->
            val relativePath = "${folder}/${file.name}"
            val now = System.currentTimeMillis()
            val displayName = file.name
            val mimeType = guessMimeType(file, displayName)
            val write: suspend () -> Boolean = suspend {
                if (repository.getByPath(relativePath) != null) {
                    false
                } else {
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
                    true
                }
            }
            if (if (gateWrites) withManagedFileWrite(write) else write()) {
                inserted += 1
            }
        }
        return inserted
    }

    suspend fun delete(id: Long, deleteFromDisk: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        withManagedFileWrite {
            val entity = repository.getById(id) ?: return@withManagedFileWrite false
            if (deleteFromDisk) {
                val deleted = runCatching {
                    val file = getFile(entity)
                    !file.exists() || file.delete()
                }.getOrDefault(false)
                if (!deleted) return@withManagedFileWrite false
            }
            repository.deleteById(id) > 0
        }
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

    private suspend fun trackUploadFile(
        file: File,
        displayName: String,
        mimeType: String,
        expectedRestoreEpoch: Long? = null,
    ) = withContext(restoreEpochContext(expectedRestoreEpoch)) {
        val relativePath = "${FileFolders.UPLOAD}/${file.name}"
        withManagedFileWrite {
            if (repository.getByPath(relativePath) != null) return@withManagedFileWrite
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
        }
    }

    private suspend fun <T> withManagedFileWrite(block: suspend () -> T): T =
        restoreWriteGate?.withCurrentWriterOrCancel(block) ?: block()

    private fun restoreEpochContext(expectedRestoreEpoch: Long?): CoroutineContext =
        expectedRestoreEpoch?.let(::SyncRestoreWriteEpoch) ?: EmptyCoroutineContext

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
    /** P2-04: replaced skill versions kept for one explicit rollback. */
    const val SKILLS_PREVIOUS = "skills_previous"
    /** Per-conversation generate_image output — `filesDir/chat_images/{convId}/…` */
    const val CHAT_IMAGES = "chat_images"
    /** Standalone ImgGenPage gallery — `filesDir/images/…` */
    const val IMAGES = "images"
}

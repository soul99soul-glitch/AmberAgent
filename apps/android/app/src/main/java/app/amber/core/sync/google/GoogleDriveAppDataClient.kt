package app.amber.core.sync.google

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import app.amber.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.net.URLEncoder

class GoogleDriveAppDataClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun findLatest(accessToken: String): GoogleDriveFile? {
        return listSnapshots(accessToken, pageSize = 1).firstOrNull()
    }

    suspend fun listSnapshots(accessToken: String, pageSize: Int = DEFAULT_SNAPSHOT_PAGE_SIZE): List<GoogleDriveFile> {
        val query = "trashed=false and (name='$SYNC_FILE_NAME' or name contains '$SYNC_FILE_PREFIX')"
        val request = Request.Builder()
            .url(
                "https://www.googleapis.com/drive/v3/files" +
                    "?spaces=appDataFolder" +
                    "&pageSize=$pageSize" +
                    "&orderBy=modifiedTime%20desc" +
                    "&q=${query.urlEncoded()}" +
                    "&fields=files(id,name,modifiedTime,size,version,appProperties)"
            )
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val response = httpClient.newCall(request).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            throwDriveError("list", response.code, body, accessToken)
        }
        return json.decodeFromString<GoogleDriveListResponse>(body)
            .files
    }

    suspend fun downloadToFile(accessToken: String, fileId: String, targetFile: File) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val response = httpClient.newCall(request).await()
        if (!response.isSuccessful) {
            val body = response.body.string()
            throwDriveError("download", response.code, body, accessToken)
        }
        targetFile.parentFile?.mkdirs()
        response.body.byteStream().use { input ->
            targetFile.outputStream().buffered().use { output ->
                input.copyToWithinLimit(output, MAX_DOWNLOAD_BYTES)
            }
        }
    }

    suspend fun upload(
        accessToken: String,
        archiveFile: File,
        fileName: String = SYNC_FILE_NAME,
        appProperties: Map<String, String> = emptyMap(),
        existingFileId: String?,
        onProgress: ((uploadedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): GoogleDriveFile {
        val metadata = buildJsonObject {
            put("name", fileName)
            if (existingFileId.isNullOrBlank()) {
                putJsonArray("parents") { add("appDataFolder") }
            }
            if (appProperties.isNotEmpty()) {
                put("appProperties", buildJsonObject {
                    appProperties.forEach { (key, value) ->
                        put(key, value)
                    }
                })
            }
        }.toString()
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(
                metadata.toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .addPart(
                archiveFile.asRequestBody("application/vnd.amberagent.backup+zip".toMediaType())
            )
            .build()
        val url = if (existingFileId.isNullOrBlank()) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,modifiedTime,size,version,appProperties"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=multipart&fields=id,name,modifiedTime,size,version,appProperties"
        }
        val requestBody = if (onProgress != null) {
            ProgressRequestBody(body, onProgress)
        } else {
            body
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .method(if (existingFileId.isNullOrBlank()) "POST" else "PATCH", requestBody)
            .build()
        val response = httpClient.newCall(request).await()
        val raw = response.body.string()
        if (!response.isSuccessful) {
            throwDriveError("upload", response.code, raw, accessToken)
        }
        return json.decodeFromString<GoogleDriveFile>(raw)
    }

    suspend fun delete(accessToken: String, fileId: String) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .addHeader("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        val response = httpClient.newCall(request).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            throwDriveError("delete", response.code, body, accessToken)
        }
    }

    private fun throwDriveError(action: String, code: Int, body: String, accessToken: String): Nothing {
        val driveError = runCatching {
            json.decodeFromString<GoogleDriveErrorResponse>(body).error
        }.getOrNull()
        val reasons = driveError?.errors.orEmpty().mapNotNull { it.reason }.toSet()
        val status = driveError?.status.orEmpty()
        val message = driveError?.message.orEmpty()
        val authRequired = code == 401 ||
            code == 403 && (
                "authError" in reasons ||
                    "insufficientPermissions" in reasons ||
                    status == "UNAUTHENTICATED" ||
                    message.contains("insufficient authentication", ignoreCase = true)
                )
        if (authRequired) {
            throw GoogleDriveAuthRequiredException(
                message = "Google Drive 授权已失效，请重新连接 Google 账号。",
                accessToken = accessToken,
            )
        }
        val detail = driveError?.message?.takeIf { it.isNotBlank() } ?: body.take(1_000)
        error("Google Drive $action failed: $code $detail")
    }

    companion object {
        const val SYNC_FILE_NAME = "amberagent-sync.amberbackup"
        const val SYNC_FILE_PREFIX = "amberagent-sync-"
        const val DEFAULT_SNAPSHOT_PAGE_SIZE = 20
    }
}

private const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024

private fun java.io.InputStream.copyToWithinLimit(output: java.io.OutputStream, limit: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(total <= limit - read) { "Google Drive backup exceeds ${limit} bytes" }
        output.write(buffer, 0, read)
        total += read
    }
}

private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit,
) : RequestBody() {
    override fun contentType() = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isDuplex(): Boolean = delegate.isDuplex()

    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        val totalBytes = runCatching { contentLength() }.getOrDefault(-1L)
        var uploadedBytes = 0L
        val countingSink = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                uploadedBytes += byteCount
                onProgress(uploadedBytes, totalBytes)
            }
        }.buffer()
        delegate.writeTo(countingSink)
        countingSink.flush()
    }
}

class GoogleDriveAuthRequiredException(
    message: String,
    val accessToken: String,
) : IllegalStateException(message)

@Serializable
private data class GoogleDriveErrorResponse(
    val error: GoogleDriveError? = null,
)

@Serializable
private data class GoogleDriveError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
    val errors: List<GoogleDriveErrorItem> = emptyList(),
)

@Serializable
private data class GoogleDriveErrorItem(
    val reason: String? = null,
)

@Serializable
data class GoogleDriveListResponse(
    val files: List<GoogleDriveFile> = emptyList(),
)

@Serializable
data class GoogleDriveFile(
    val id: String,
    val name: String,
    val modifiedTime: String? = null,
    val size: String? = null,
    val version: Long? = null,
    val appProperties: Map<String, String> = emptyMap(),
) {
    val revisionKey: String
        get() = version?.toString() ?: modifiedTime ?: id

    val backupCreatedAt: Long?
        get() = appProperties["createdAt"]?.toLongOrNull()

    val backupVersionName: String
        get() = appProperties["appVersionName"].orEmpty()

    val backupVersionCode: Long?
        get() = appProperties["appVersionCode"]?.toLongOrNull()

    val backupDeviceLabel: String
        get() = appProperties["deviceLabel"].orEmpty()

    val archiveVersion: Int?
        get() = appProperties["archiveVersion"]?.toIntOrNull()
}

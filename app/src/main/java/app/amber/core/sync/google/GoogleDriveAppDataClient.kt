package app.amber.core.sync.google

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class GoogleDriveAppDataClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun findLatest(accessToken: String): GoogleDriveFile? {
        val request = Request.Builder()
            .url(
                "https://www.googleapis.com/drive/v3/files" +
                    "?spaces=appDataFolder" +
                    "&q=name%3D%27$SYNC_FILE_NAME%27%20and%20trashed%3Dfalse" +
                    "&fields=files(id,name,modifiedTime,size,version)"
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
            .maxByOrNull { it.modifiedTime.orEmpty() }
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
                input.copyTo(output)
            }
        }
    }

    suspend fun upload(accessToken: String, archiveFile: File, existingFileId: String?): GoogleDriveFile {
        val metadata = buildJsonObject {
            put("name", SYNC_FILE_NAME)
            if (existingFileId.isNullOrBlank()) {
                putJsonArray("parents") { add("appDataFolder") }
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
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,modifiedTime,size,version"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=multipart&fields=id,name,modifiedTime,size,version"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .method(if (existingFileId.isNullOrBlank()) "POST" else "PATCH", body)
            .build()
        val response = httpClient.newCall(request).await()
        val raw = response.body.string()
        if (!response.isSuccessful) {
            throwDriveError("upload", response.code, raw, accessToken)
        }
        return json.decodeFromString<GoogleDriveFile>(raw)
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
) {
    val revisionKey: String
        get() = version?.toString() ?: modifiedTime ?: id
}

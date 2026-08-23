package app.amber.feature.icloud

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.core.agent.utils.JsonInstant
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.util.UUID

class ICloudDriveClient(
    private val httpClient: HttpClient,
    private val json: Json = JsonInstant,
) {
    suspend fun validateSession(
        clientId: String,
        cookies: ICloudDriveCookieBundle,
    ): ICloudDriveSession {
        require(!cookies.isEmpty && cookies.value("X-APPLE-WEBAUTH-TOKEN") != null) {
            "iCloud Web login cookie is missing. Open iCloud or China iCloud login first and finish 2FA."
        }
        var lastError: Throwable? = null
        ICloudDriveWebEndpoints.preferredFor(cookies).forEach { endpoint ->
            runCatching {
                val raw = postJson(
                    url = "${endpoint.setupEndpoint}/validate",
                    cookies = cookies,
                    body = JsonNull,
                    endpoint = endpoint,
                ).jsonObject
                return ICloudDriveResponseParser.parseSession(clientId, cookies, raw, endpoint)
            }.onFailure { error ->
                lastError = error
            }
        }
        throw IllegalStateException(
            "iCloud session validation failed for global and China endpoints: ${lastError?.message ?: "unknown error"}",
            lastError,
        )
    }

    suspend fun requestDriveAccess(session: ICloudDriveSession) {
        val state = postJson(
            url = "${session.endpoint.setupEndpoint}/requestWebAccessState",
            session = session,
            body = JsonNull,
        ).jsonObject
        val icdrsDisabled = state.boolean("isICDRSDisabled") ?: false
        if (!icdrsDisabled) return

        if (state.boolean("isDeviceConsentedForPCS") == false) {
            postJson(
                url = "${session.endpoint.setupEndpoint}/enableDeviceConsentForPCS",
                session = session,
                body = JsonNull,
            )
            error("iCloud requested device consent for encrypted Drive access. Approve the Apple prompt, then probe again.")
        }

        val pcs = postJson(
            url = "${session.endpoint.setupEndpoint}/requestPCS",
            session = session,
            body = buildJsonObject {
                put("appName", "iclouddrive")
                put("derivedFromUserAction", true)
            },
        ).jsonObject
        val status = pcs.string("status")
        if (status != null && status != "success") {
            val message = pcs.string("message") ?: "Unknown PCS response"
            error("iCloud Drive private access is not ready yet: $message")
        }
    }

    suspend fun list(session: ICloudDriveSession, resolvedPath: ICloudDriveResolvedPath): List<ICloudDriveEntry> {
        val node = resolveNode(session, resolvedPath.iCloudPath)
        require(node.isDirectory) { "Not a directory: ${resolvedPath.relativePath}" }
        return getChildren(session, node).map { child ->
            ICloudDriveEntry(
                path = ICloudDrivePath.join(resolvedPath.relativePath, child.name),
                name = child.name,
                directory = child.isDirectory,
                sizeBytes = child.sizeBytes,
                drivewsId = child.drivewsid,
                docwsId = child.docwsid,
                etag = child.etag,
            )
        }
    }

    suspend fun stat(
        session: ICloudDriveSession,
        resolvedPath: ICloudDriveResolvedPath,
        nodeRef: ICloudDriveNodeRef? = null,
    ): ICloudDriveStatResult {
        val (node, matchLevel) = resolveNodeWithHint(session, resolvedPath, nodeRef)
        return ICloudDriveStatResult(
            entry = node.toEntry(resolvedPath.relativePath),
            matchLevel = matchLevel,
        )
    }

    suspend fun readText(
        session: ICloudDriveSession,
        resolvedPath: ICloudDriveResolvedPath,
        nodeRef: ICloudDriveNodeRef? = null,
        maxBytes: Int = DEFAULT_READ_MAX_BYTES,
    ): ICloudDriveReadResult {
        val (node, matchLevel) = resolveNodeWithHint(session, resolvedPath, nodeRef)
        require(!node.isDirectory) { "Not a file: ${resolvedPath.relativePath}" }
        if (node.sizeBytes == 0L) {
            return ICloudDriveReadResult(
                content = "",
                entry = node.toEntry(resolvedPath.relativePath),
                matchLevel = matchLevel,
            )
        }
        val documentId = node.docwsid ?: error("iCloud node is missing docwsid: ${node.name}")
        val tokenResponse = getJson(
            url = "${session.docwsUrl}/ws/${node.zone}/download/by_id",
            session = session,
            extraParams = mapOf("document_id" to documentId),
        )
        val downloadUrl = tokenResponse.jsonObjectOrNull("data_token")?.string("url")
            ?: tokenResponse.jsonObjectOrNull("package_token")?.string("url")
            ?: error("iCloud did not return a download token for ${node.name}")
        return ICloudDriveReadResult(
            content = getText(downloadUrl, session, maxBytes),
            entry = node.toEntry(resolvedPath.relativePath),
            matchLevel = matchLevel,
        )
    }

    suspend fun writeText(
        session: ICloudDriveSession,
        resolvedPath: ICloudDriveResolvedPath,
        content: String,
        overwrite: Boolean,
    ): ICloudDriveEntry {
        require(resolvedPath.iCloudPath.isNotBlank()) { "path is required" }
        val parentPath = resolvedPath.iCloudPath.substringBeforeLast("/", "")
        val fileName = resolvedPath.iCloudPath.substringAfterLast("/")
        require(fileName.isNotBlank()) { "file name is required" }
        val parent = resolveNode(session, parentPath)
        require(parent.isDirectory) { "Parent is not a directory: $parentPath" }
        val existing = getChildren(session, parent).firstOrNull { it.name == fileName }
        if (existing != null) {
            require(!existing.isDirectory) { "Target already exists as a folder: $fileName" }
            require(overwrite) { "Target already exists. Pass overwrite=true to replace it." }
            moveToTrash(session, existing)
        }
        uploadText(session, parent, fileName, content)
        return ICloudDriveEntry(
            path = resolvedPath.relativePath,
            name = fileName,
            directory = false,
            sizeBytes = content.encodeToByteArray().size.toLong(),
        )
    }

    suspend fun delete(session: ICloudDriveSession, resolvedPath: ICloudDriveResolvedPath) {
        val node = resolveNode(session, resolvedPath.iCloudPath)
        moveToTrash(session, node)
    }

    suspend fun search(
        session: ICloudDriveSession,
        basePath: ICloudDriveResolvedPath,
        query: String,
        maxResults: Int,
    ): List<ICloudDriveSearchResult> {
        require(query.isNotBlank()) { "query is required" }
        val results = mutableListOf<ICloudDriveSearchResult>()
        suspend fun visit(relativePath: String) {
            if (results.size >= maxResults) return
            val resolved = ICloudDrivePath.resolve(basePath.vaultPath, relativePath)
            val node = resolveNode(session, resolved.iCloudPath)
            if (node.isDirectory) {
                getChildren(session, node).forEach { child ->
                    visit(ICloudDrivePath.join(relativePath, child.name))
                }
            } else {
                if (node.name.contains(query, ignoreCase = true)) {
                    results.add(
                        ICloudDriveSearchResult(
                            path = relativePath,
                            lineNumber = 0,
                            preview = node.name,
                            drivewsId = node.drivewsid,
                            docwsId = node.docwsid,
                            etag = node.etag,
                        )
                    )
                    return
                }
                if (node.sizeBytes != null && node.sizeBytes > MAX_SEARCH_FILE_BYTES) return
                val text = runCatching { readText(session, resolved).content }.getOrNull() ?: return
                val line = text.lineSequence().withIndex().firstOrNull { it.value.contains(query, ignoreCase = true) }
                if (line != null) {
                    results.add(
                        ICloudDriveSearchResult(
                            path = relativePath,
                            lineNumber = line.index + 1,
                            preview = line.value.take(240),
                            drivewsId = node.drivewsid,
                            docwsId = node.docwsid,
                            etag = node.etag,
                        )
                    )
                }
            }
        }
        visit(basePath.relativePath)
        return results
    }

    private suspend fun resolveNodeWithHint(
        session: ICloudDriveSession,
        resolvedPath: ICloudDriveResolvedPath,
        nodeRef: ICloudDriveNodeRef?,
    ): Pair<ICloudDriveNode, String> {
        val node = resolveNode(session, resolvedPath.iCloudPath)
        if (nodeRef == null) return node to "path"
        return node to if (node.matches(nodeRef)) "node_ref" else "path_fallback"
    }

    private fun ICloudDriveNode.matches(ref: ICloudDriveNodeRef): Boolean =
        drivewsid == ref.drivewsId &&
            (ref.docwsId == null || docwsid == ref.docwsId) &&
            (ref.etag == null || etag == ref.etag)

    private fun ICloudDriveNode.toEntry(relativePath: String): ICloudDriveEntry =
        ICloudDriveEntry(
            path = relativePath,
            name = name,
            directory = isDirectory,
            sizeBytes = sizeBytes,
            drivewsId = drivewsid,
            docwsId = docwsid,
            etag = etag,
        )

    private suspend fun resolveNode(session: ICloudDriveSession, path: String): ICloudDriveNode {
        var node = retrieveNode(session, ICLOUD_ROOT_DRIVEWS_ID)
        if (path.isBlank()) return node
        path.split("/").filter { it.isNotBlank() }.forEach { segment ->
            node = getChildren(session, node).firstOrNull { it.name == segment }
                ?: error("iCloud path not found: $path")
        }
        return node
    }

    private suspend fun getChildren(session: ICloudDriveSession, node: ICloudDriveNode): List<ICloudDriveNode> {
        val fresh = retrieveNode(session, node.drivewsid)
        val items = fresh.raw["items"]?.jsonArray ?: return emptyList()
        return items.map { ICloudDriveResponseParser.parseNode(it.jsonObject) }
    }

    private suspend fun retrieveNode(session: ICloudDriveSession, drivewsid: String): ICloudDriveNode {
        val raw = postJson(
            url = "${session.drivewsUrl}/retrieveItemDetailsInFolders",
            session = session,
            body = buildJsonArray {
                add(
                    buildJsonObject {
                        put("drivewsid", drivewsid)
                        put("partialData", false)
                    }
                )
            },
        )
        return ICloudDriveResponseParser.parseNode(raw.jsonArray.first().jsonObject)
    }

    private suspend fun uploadText(
        session: ICloudDriveSession,
        parent: ICloudDriveNode,
        fileName: String,
        content: String,
    ) {
        val uploadToken = webAuthValidateToken(session.cookies)
        val contentBytes = content.encodeToByteArray()
        val uploadInfo = postJson(
            url = "${session.docwsUrl}/ws/${parent.zone}/upload/web",
            cookies = session.cookies,
            params = session.params + mapOf("token" to uploadToken),
            body = buildJsonObject {
                put("filename", fileName)
                put("type", "FILE")
                put("content_type", "text/plain")
                put("size", contentBytes.size)
            },
            contentType = ContentType.Text.Plain,
            endpoint = session.endpoint,
        ).jsonArray.first().jsonObject
        val documentId = uploadInfo.string("document_id") ?: error("iCloud upload did not return document_id")
        val uploadUrl = uploadInfo.string("url") ?: error("iCloud upload did not return content URL")
        val contentResponse = postMultipart(uploadUrl, session, fileName, contentBytes)
            .jsonObjectOrNull("singleFile") ?: error("iCloud content upload did not return singleFile")
        val parentDocumentId = parent.docwsid ?: error("iCloud parent folder is missing docwsid: ${parent.name}")
        postJson(
            url = "${session.docwsUrl}/ws/${parent.zone}/update/documents",
            session = session,
            body = buildJsonObject {
                put(
                    "data",
                    buildJsonObject {
                        put("signature", contentResponse.string("fileChecksum").orEmpty())
                        put("wrapping_key", contentResponse.string("wrappingKey").orEmpty())
                        put("reference_signature", contentResponse.string("referenceChecksum").orEmpty())
                        put("size", contentResponse.long("size") ?: contentBytes.size.toLong())
                        contentResponse["receipt"]?.let { put("receipt", it) }
                    }
                )
                put("command", "add_file")
                put("create_short_guid", true)
                put("document_id", documentId)
                put(
                    "path",
                    buildJsonObject {
                        put("starting_document_id", parentDocumentId)
                        put("path", fileName)
                    }
                )
                put("allow_conflict", false)
                put(
                    "file_flags",
                    buildJsonObject {
                        put("is_writable", true)
                        put("is_executable", false)
                        put("is_hidden", false)
                    }
                )
                val now = System.currentTimeMillis()
                put("mtime", now)
                put("btime", now)
            },
            contentType = ContentType.Text.Plain,
        )
    }

    private suspend fun moveToTrash(session: ICloudDriveSession, node: ICloudDriveNode) {
        val etag = node.etag ?: error("iCloud node is missing etag: ${node.name}")
        postJson(
            url = "${session.drivewsUrl}/moveItemsToTrash",
            session = session,
            body = buildJsonObject {
                put(
                    "items",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("drivewsid", node.drivewsid)
                                put("etag", etag)
                                put("clientId", node.drivewsid)
                            }
                        )
                    }
                )
            },
        )
    }

    private suspend fun getJson(
        url: String,
        session: ICloudDriveSession,
        extraParams: Map<String, String> = emptyMap(),
    ): JsonObject =
        parseJson(
            httpClient.get(url) {
                addICloudHeaders(session.cookies, session.endpoint)
                (session.params + extraParams).forEach { (key, value) -> parameter(key, value) }
            },
        ).jsonObject

    private suspend fun getText(url: String, session: ICloudDriveSession, maxBytes: Int): String {
        val response = httpClient.get(url) {
            addICloudHeaders(session.cookies, session.endpoint)
            session.params.forEach { (key, value) -> parameter(key, value) }
        }
        val body = response.readTextWithinLimit(maxBytes)
        require(response.status.isSuccess()) { "iCloud download failed: ${response.status.value} ${body.take(500)}" }
        return body
    }

    private suspend fun HttpResponse.readTextWithinLimit(maxBytes: Int): String {
        val channel = bodyAsChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer)
            if (read <= 0) continue
            require(total <= maxBytes - read) { "iCloud file exceeds $maxBytes byte read limit" }
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private suspend fun postJson(
        url: String,
        session: ICloudDriveSession,
        body: JsonElement,
        contentType: ContentType = ContentType.Application.Json,
    ): JsonElement =
        postJson(url, session.cookies, session.params, body, contentType, session.endpoint)

    private suspend fun postJson(
        url: String,
        cookies: ICloudDriveCookieBundle,
        body: JsonElement,
        contentType: ContentType = ContentType.Application.Json,
        endpoint: ICloudDriveWebEndpoint = ICloudDriveWebEndpoints.GLOBAL,
    ): JsonElement =
        postJson(url, cookies, emptyMap(), body, contentType, endpoint)

    private suspend fun postJson(
        url: String,
        cookies: ICloudDriveCookieBundle,
        params: Map<String, String>,
        body: JsonElement,
        contentType: ContentType,
        endpoint: ICloudDriveWebEndpoint,
    ): JsonElement {
        val response = httpClient.post(url) {
            addICloudHeaders(cookies, endpoint)
            params.forEach { (key, value) -> parameter(key, value) }
            contentType(contentType)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        return parseJson(response)
    }

    private suspend fun postMultipart(
        url: String,
        session: ICloudDriveSession,
        fileName: String,
        content: ByteArray,
    ): JsonObject {
        val response = httpClient.post(url) {
            addICloudHeaders(session.cookies, session.endpoint)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            key = fileName,
                            value = content,
                            headers = Headers.build {
                                append(HttpHeaders.ContentDisposition, "form-data; name=\"$fileName\"; filename=\"$fileName\"")
                                append(HttpHeaders.ContentType, "text/plain")
                            },
                        )
                    }
                )
            )
        }
        return parseJson(response).jsonObject
    }

    private suspend fun parseJson(response: HttpResponse): JsonElement {
        val body = response.readTextWithinLimit(MAX_JSON_RESPONSE_BYTES)
        require(response.status.isSuccess()) { "iCloud request failed: ${response.status.value} ${body.take(500)}" }
        return json.parseToJsonElement(body)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.addICloudHeaders(
        cookies: ICloudDriveCookieBundle,
        endpoint: ICloudDriveWebEndpoint = ICloudDriveWebEndpoints.GLOBAL,
    ) {
        headers {
            append(HttpHeaders.Accept, "application/json, text/javascript, */*; q=0.01")
            append(HttpHeaders.UserAgent, SAFARI_USER_AGENT)
            append(HttpHeaders.Origin, endpoint.origin)
            append("Referer", endpoint.referer)
            if (!cookies.isEmpty) append(HttpHeaders.Cookie, cookies.header)
        }
    }

    private fun webAuthValidateToken(cookies: ICloudDriveCookieBundle): String {
        val raw = cookies.value("X-APPLE-WEBAUTH-VALIDATE")
            ?: error("iCloud write requires X-APPLE-WEBAUTH-VALIDATE cookie. Reopen iCloud login and try again.")
        return Regex("""\bt=([^:;]+)""").find(raw)?.groupValues?.getOrNull(1)
            ?: error("Unable to extract iCloud upload token from WebAuth cookie")
    }

    companion object {
        fun newClientId(): String = UUID.randomUUID().toString().lowercase()
        private const val SAFARI_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
        private const val MAX_SEARCH_FILE_BYTES = 512_000L
    }
}

private const val DEFAULT_READ_MAX_BYTES = 1024 * 1024
private const val MAX_JSON_RESPONSE_BYTES = 8 * 1024 * 1024

object ICloudDriveResponseParser {
    fun parseSession(
        clientId: String,
        cookies: ICloudDriveCookieBundle,
        raw: JsonObject,
        endpoint: ICloudDriveWebEndpoint = ICloudDriveWebEndpoints.GLOBAL,
    ): ICloudDriveSession {
        val dsid = raw.jsonObjectOrNull("dsInfo")?.string("dsid")
            ?: error("iCloud session is not authenticated: missing dsInfo.dsid")
        val webservices = raw.jsonObjectOrNull("webservices")
            ?: error("iCloud session is missing webservices")
        val drivewsUrl = webservices.jsonObjectOrNull("drivews")?.string("url")
            ?: error("iCloud Drive service is not available for this account")
        val docwsUrl = webservices.jsonObjectOrNull("docws")?.string("url")
            ?: error("iCloud document service is not available for this account")
        return ICloudDriveSession(
            clientId = clientId,
            dsid = dsid,
            drivewsUrl = drivewsUrl.trimEnd('/'),
            docwsUrl = docwsUrl.trimEnd('/'),
            cookies = cookies,
            endpoint = endpoint,
        )
    }

    fun parseNode(raw: JsonObject): ICloudDriveNode {
        val baseName = raw.string("name") ?: raw.string("drivewsid") ?: "<UNKNOWN>"
        val extension = raw.string("extension")
        val name = if (extension.isNullOrBlank() || baseName.endsWith(".$extension")) {
            baseName
        } else {
            "$baseName.$extension"
        }
        val drivewsid = raw.string("drivewsid") ?: error("iCloud node is missing drivewsid")
        return ICloudDriveNode(
            name = if (drivewsid == ICLOUD_ROOT_DRIVEWS_ID && baseName == drivewsid) "root" else name,
            type = raw.string("type")?.lowercase() ?: if (drivewsid == ICLOUD_ROOT_DRIVEWS_ID) "folder" else "unknown",
            drivewsid = drivewsid,
            docwsid = raw.string("docwsid"),
            zone = raw.string("zone") ?: "com.apple.CloudDocs",
            etag = raw.string("etag"),
            sizeBytes = raw.long("size"),
            raw = raw,
        )
    }
}

data class ICloudDriveSearchResult(
    val path: String,
    val lineNumber: Int,
    val preview: String,
    val nodeRef: String? = null,
    val drivewsId: String? = null,
    val docwsId: String? = null,
    val etag: String? = null,
)

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.long(name: String): Long? =
    this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject.jsonObjectOrNull(name: String): JsonObject? =
    this[name] as? JsonObject

package me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 飞书云文档 (Feishu Docs / Lark) API client. Uses `open.feishu.cn` OpenAPI
 * with `Authorization: Bearer <user_access_token>`. The token is fetched by
 * [FeishuDocsAdapter] from the OAuth client (see M1.5).
 *
 * Endpoints documented at open.feishu.cn/document. Surface size is
 * intentionally small for Phase 1 — list / read / create / append /
 * search — rich block-tree editing is left to Phase 2 once we know
 * which surfaces actually matter for the agent.
 */
class FeishuDocsClient(
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    suspend fun listFiles(
        accessToken: String,
        folderToken: String?,
        pageSize: Int,
        pageToken: String?,
    ): FeishuDocList {
        val response = http.get("$DRIVE_BASE/v1/files") {
            applyHeaders(accessToken)
            if (!folderToken.isNullOrBlank()) parameter("folder_token", folderToken)
            parameter("page_size", pageSize.coerceIn(1, 200))
            if (!pageToken.isNullOrBlank()) parameter("page_token", pageToken)
        }
        val data = parseFeishu(response, "list files").data ?: return FeishuDocList(emptyList(), null, false)
        val files = (data["files"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            FeishuDocSummary(
                token = obj.s("token") ?: return@mapNotNull null,
                name = obj.s("name") ?: "",
                type = obj.s("type") ?: "",
                url = obj.s("url"),
                ownerOpenId = obj.s("owner_id"),
                parentToken = obj.s("parent_token"),
                createdAtMs = (obj.l("created_time") ?: 0L) * 1000L,
                modifiedAtMs = (obj.l("modified_time") ?: 0L) * 1000L,
            )
        }
        return FeishuDocList(
            files = files,
            nextPageToken = data.s("next_page_token"),
            hasMore = data["has_more"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
        )
    }

    suspend fun readRawContent(accessToken: String, documentId: String): String? {
        require(documentId.isNotBlank()) { "document_id is required" }
        val response = http.get("$DOCX_BASE/v1/documents/$documentId/raw_content") {
            applyHeaders(accessToken)
        }
        val body = parseFeishu(response, "raw_content")
        val data = body.data ?: return null
        return data.s("content")
    }

    suspend fun createDocument(accessToken: String, title: String, folderToken: String?): FeishuDocSummary {
        val response = http.post("$DOCX_BASE/v1/documents") {
            applyHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("title", title)
                    folderToken?.let { put("folder_token", it) }
                }.toString()
            )
        }
        val data = parseFeishu(response, "create document").data
            ?: error("Feishu create document returned no data")
        val docObj = (data["document"] as? JsonObject) ?: data
        return FeishuDocSummary(
            token = docObj.s("document_id") ?: docObj.s("token") ?: error("missing document_id"),
            name = docObj.s("title") ?: title,
            type = "docx",
            url = docObj.s("document_id")?.let { "https://feishu.cn/docx/$it" },
            ownerOpenId = null,
            parentToken = folderToken,
            createdAtMs = System.currentTimeMillis(),
            modifiedAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * Append a single text block at the end of a document. The agent can call
     * this repeatedly to build up content; rich block trees (tables, callouts)
     * are deferred to Phase 2.
     */
    suspend fun appendTextBlock(accessToken: String, documentId: String, text: String): JsonObject {
        require(documentId.isNotBlank()) { "document_id is required" }
        require(text.isNotEmpty()) { "text is required" }
        // 飞书 docx text-block shape: block_type=2 (text), with text.elements[].text_run.content.
        val block = buildJsonObject {
            put("block_type", 2)
            put("text", buildJsonObject {
                put("elements", buildJsonArray {
                    add(buildJsonObject {
                        put("text_run", buildJsonObject {
                            put("content", text)
                        })
                    })
                })
                put("style", buildJsonObject {})
            })
        }
        val response = http.post("$DOCX_BASE/v1/documents/$documentId/blocks/$documentId/children") {
            applyHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("index", -1) // append
                put("children", buildJsonArray { add(block) })
            }.toString())
        }
        return parseFeishu(response, "append block").data ?: buildJsonObject {}
    }

    suspend fun search(accessToken: String, query: String, count: Int): List<FeishuDocSummary> {
        require(query.isNotBlank()) { "query is required" }
        val response = http.post("$SUITE_BASE/docs-api/search/object") {
            applyHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("search_key", query)
                put("count", count.coerceIn(1, 100))
                put("offset", 0)
            }.toString())
        }
        val data = parseFeishu(response, "docs search").data ?: return emptyList()
        return (data["docs_entities"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            FeishuDocSummary(
                token = obj.s("docs_token") ?: return@mapNotNull null,
                name = obj.s("title") ?: "",
                type = obj.s("docs_type") ?: "",
                url = obj.s("url"),
                ownerOpenId = obj.s("owner_id"),
                parentToken = null,
                createdAtMs = 0L,
                modifiedAtMs = (obj.l("update_time") ?: 0L) * 1000L,
            )
        }
    }

    /**
     * Cheap call used by adapter.probe — list the root folder with page_size=1.
     * 200 + non-zero code returns the standard envelope with the user's OpenAPI
     * surface visible.
     */
    suspend fun probe(accessToken: String): Boolean = runCatching {
        listFiles(accessToken, folderToken = null, pageSize = 1, pageToken = null)
        true
    }.getOrElse { false }

    // ----------------------------------------------------------------------

    private fun HttpRequestBuilder.applyHeaders(accessToken: String) {
        headers {
            append(HttpHeaders.Authorization, "Bearer $accessToken")
            append(HttpHeaders.Accept, "application/json")
        }
    }

    private suspend fun parseFeishu(response: HttpResponse, label: String): FeishuEnvelope {
        val text = response.bodyAsText()
        require(response.status.isSuccess()) {
            "$label HTTP ${response.status.value}: ${text.take(500)}"
        }
        val outer = (json.parseToJsonElement(text) as? JsonObject)
            ?: error("$label returned non-object body: ${text.take(300)}")
        // 飞书 envelope: {code, msg, data}. Non-zero code is a logical failure.
        val code = outer["code"]?.jsonPrimitive?.intOrNull
        if (code != null && code != 0) {
            val msg = outer["msg"]?.jsonPrimitive?.contentOrNull ?: "(no message)"
            error("$label failed (code=$code msg=$msg)")
        }
        return FeishuEnvelope(
            code = code,
            data = outer["data"] as? JsonObject,
        )
    }

    private fun JsonObject.s(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.l(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private data class FeishuEnvelope(val code: Int?, val data: JsonObject?)

    companion object {
        private const val DRIVE_BASE = "https://open.feishu.cn/open-apis/drive"
        private const val DOCX_BASE = "https://open.feishu.cn/open-apis/docx"
        private const val SUITE_BASE = "https://open.feishu.cn/open-apis/suite"
    }
}

data class FeishuDocSummary(
    val token: String,
    val name: String,
    val type: String,
    val url: String?,
    val ownerOpenId: String?,
    val parentToken: String?,
    val createdAtMs: Long,
    val modifiedAtMs: Long,
)

data class FeishuDocList(
    val files: List<FeishuDocSummary>,
    val nextPageToken: String?,
    val hasMore: Boolean,
)

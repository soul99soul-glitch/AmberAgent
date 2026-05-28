package app.amber.feature.webmount.adapters.feishudocs

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

    suspend fun listBlocks(
        accessToken: String,
        documentId: String,
        pageSize: Int,
        pageToken: String?,
    ): FeishuDocBlockList {
        require(documentId.isNotBlank()) { "document_id is required" }
        val response = http.get("$DOCX_BASE/v1/documents/$documentId/blocks") {
            applyHeaders(accessToken)
            parameter("page_size", pageSize.coerceIn(1, 500))
            if (!pageToken.isNullOrBlank()) parameter("page_token", pageToken)
        }
        val data = parseFeishu(response, "list blocks").data ?: return FeishuDocBlockList(emptyList(), null, false)
        val blocks = (data["items"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val blockId = obj.s("block_id") ?: return@mapNotNull null
            val blockType = obj.i("block_type")
            FeishuDocBlock(
                blockId = blockId,
                parentId = obj.s("parent_id"),
                blockType = blockType,
                text = obj.extractPlainText().takeIf { it.isNotBlank() },
                headingLevel = blockType?.takeIf { it in 3..11 }?.let { it - 2 },
                childrenCount = (obj["children"] as? JsonArray)?.size ?: 0,
                raw = obj,
            )
        }
        return FeishuDocBlockList(
            blocks = blocks,
            nextPageToken = data.s("next_page_token"),
            hasMore = data["has_more"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
        )
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
     * Append a single text block as a child of [parentBlockId] (or the document's
     * root block if null). The agent can call this repeatedly to build up
     * content; rich block trees (tables, callouts) are deferred to Phase 2.
     *
     * 飞书's docx API endpoint is `/documents/<document_id>/blocks/<block_id>/children`
     * where the second segment is the **parent block id**, not the document id.
     * When [parentBlockId] is null we resolve the root block id via
     * GET /documents/<document_id>/blocks?page_size=1 — the first entry is the
     * page-level root, and using its id is the documented contract.
     */
    suspend fun appendTextBlock(
        accessToken: String,
        documentId: String,
        text: String,
        parentBlockId: String? = null,
    ): JsonObject {
        require(documentId.isNotBlank()) { "document_id is required" }
        require(text.isNotEmpty()) { "text is required" }
        val resolvedParent = parentBlockId?.takeIf { it.isNotBlank() }
            ?: resolveRootBlockId(accessToken, documentId)
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
        val response = http.post("$DOCX_BASE/v1/documents/$documentId/blocks/$resolvedParent/children") {
            applyHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("index", -1) // append
                put("children", buildJsonArray { add(block) })
            }.toString())
        }
        return parseFeishu(response, "append block").data ?: buildJsonObject {}
    }

    /**
     * Phase 2 M2.4 — Append a rich-text block (heading / bullet / ordered /
     * callout) to a docx. Returns the same `data` envelope as
     * [appendTextBlock] — `children[].block_id` carries the new block ids
     * so the agent can chain further appends under the same parent.
     *
     * `blockKind` accepts:
     *  - `text` (≈ [appendTextBlock])
     *  - `heading1` ... `heading9` (block_type 3..11)
     *  - `bullet` (block_type 12) — single bullet-list item
     *  - `ordered` (block_type 13) — single ordered-list item
     *  - `callout` (block_type 19) — text wrapped in a default-style callout
     *
     * Multi-line rich text is out of Phase 2 scope; one block per call,
     * caller composes structures by chaining.
     */
    suspend fun appendRichBlock(
        accessToken: String,
        documentId: String,
        blockKind: String,
        text: String,
        parentBlockId: String? = null,
    ): JsonObject {
        require(documentId.isNotBlank()) { "document_id is required" }
        require(text.isNotEmpty()) { "text is required" }
        val (blockType, wrapInCallout) = blockTypeFor(blockKind)
        val resolvedParent = parentBlockId?.takeIf { it.isNotBlank() }
            ?: resolveRootBlockId(accessToken, documentId)
        val textRun = buildJsonObject {
            put("elements", buildJsonArray {
                add(buildJsonObject {
                    put("text_run", buildJsonObject {
                        put("content", text)
                    })
                })
            })
            put("style", buildJsonObject {})
        }
        val block = if (wrapInCallout) {
            // Callout is a container block: it carries no direct text;
            // instead it has a child `text` block. The Feishu API accepts
            // a `callout` block with a nested `children[0]` text block in
            // the same request.
            buildJsonObject {
                put("block_type", 19)
                put("callout", buildJsonObject {
                    // Default background; agents can hint a color in a
                    // future tool param. Phase 2 ships neutral.
                    put("background_color", 1)
                    put("border_color", 1)
                    put("emoji_id", "memo")
                })
                put("children", buildJsonArray {
                    add(buildJsonObject {
                        put("block_type", 2)
                        put("text", textRun)
                    })
                })
            }
        } else {
            buildJsonObject {
                put("block_type", blockType)
                put(BLOCK_FIELD_FOR_TYPE[blockType] ?: "text", textRun)
            }
        }
        val response = http.post("$DOCX_BASE/v1/documents/$documentId/blocks/$resolvedParent/children") {
            applyHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("index", -1)
                put("children", buildJsonArray { add(block) })
            }.toString())
        }
        return parseFeishu(response, "append rich block $blockKind").data ?: buildJsonObject {}
    }

    /** Map block_kind wire string → (block_type, is_callout_wrapper). */
    private fun blockTypeFor(blockKind: String): Pair<Int, Boolean> = when (blockKind.lowercase()) {
        "text", "paragraph" -> 2 to false
        "heading1", "h1" -> 3 to false
        "heading2", "h2" -> 4 to false
        "heading3", "h3" -> 5 to false
        "heading4", "h4" -> 6 to false
        "heading5", "h5" -> 7 to false
        "heading6", "h6" -> 8 to false
        "heading7", "h7" -> 9 to false
        "heading8", "h8" -> 10 to false
        "heading9", "h9" -> 11 to false
        "bullet" -> 12 to false
        "ordered" -> 13 to false
        "callout" -> 19 to true
        else -> error(
            "Unknown block_kind '$blockKind'. Valid: text, heading1..heading9, " +
                "bullet, ordered, callout"
        )
    }

    /**
     * Look up the document's root block id by listing its blocks. The first
     * returned block (parent_id == "") is the page-level root.
     */
    private suspend fun resolveRootBlockId(accessToken: String, documentId: String): String {
        val response = http.get("$DOCX_BASE/v1/documents/$documentId/blocks") {
            applyHeaders(accessToken)
            parameter("page_size", 1)
        }
        val data = parseFeishu(response, "list blocks").data ?: error("Failed to resolve root block")
        val first = (data["items"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: error("Document has no blocks (unexpected)")
        return first.s("block_id") ?: error("Root block missing block_id")
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
    private fun JsonObject.i(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.l(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private fun JsonObject.extractPlainText(): String {
        val chunks = mutableListOf<String>()
        TEXT_BLOCK_FIELDS.forEach { field ->
            (this[field] as? JsonObject)?.collectTextRuns(chunks)
        }
        return chunks.joinToString("").replace('\u00A0', ' ').trim()
    }

    private fun JsonElement.collectTextRuns(out: MutableList<String>) {
        when (this) {
            is JsonObject -> {
                this["text_run"]?.jsonObjectOrNull()?.s("content")?.let(out::add)
                this["equation"]?.jsonObjectOrNull()?.s("content")?.let(out::add)
                this["mention_user"]?.jsonObjectOrNull()?.s("name")?.let(out::add)
                this["mention_doc"]?.jsonObjectOrNull()?.s("title")?.let(out::add)
                values.forEach { it.collectTextRuns(out) }
            }
            is JsonArray -> forEach { it.collectTextRuns(out) }
            else -> Unit
        }
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private data class FeishuEnvelope(val code: Int?, val data: JsonObject?)

    companion object {
        private const val DRIVE_BASE = "https://open.feishu.cn/open-apis/drive"
        private const val DOCX_BASE = "https://open.feishu.cn/open-apis/docx"
        private const val SUITE_BASE = "https://open.feishu.cn/open-apis/suite"
        private val TEXT_BLOCK_FIELDS = listOf(
            "text",
            "heading1",
            "heading2",
            "heading3",
            "heading4",
            "heading5",
            "heading6",
            "heading7",
            "heading8",
            "heading9",
            "bullet",
            "ordered",
        )

        // Block-content field name keyed by block_type. text-flavored blocks
        // (text + heading1..heading9 + bullet + ordered) all carry their
        // content under a field named after the block kind in Feishu's API.
        // Callout (19) is a container and uses a different shape — see
        // [appendRichBlock].
        private val BLOCK_FIELD_FOR_TYPE: Map<Int, String> = mapOf(
            2 to "text",
            3 to "heading1",
            4 to "heading2",
            5 to "heading3",
            6 to "heading4",
            7 to "heading5",
            8 to "heading6",
            9 to "heading7",
            10 to "heading8",
            11 to "heading9",
            12 to "bullet",
            13 to "ordered",
        )
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

data class FeishuDocBlock(
    val blockId: String,
    val parentId: String?,
    val blockType: Int?,
    val text: String?,
    val headingLevel: Int?,
    val childrenCount: Int,
    val raw: JsonObject,
)

data class FeishuDocBlockList(
    val blocks: List<FeishuDocBlock>,
    val nextPageToken: String?,
    val hasMore: Boolean,
)

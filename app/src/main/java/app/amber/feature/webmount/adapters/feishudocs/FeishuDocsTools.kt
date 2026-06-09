package app.amber.feature.webmount.adapters.feishudocs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.utils.boolean
import app.amber.core.agent.utils.long
import app.amber.core.agent.utils.requiredString
import app.amber.core.agent.utils.string
import app.amber.feature.webmount.core.WebMountToolHooks
import app.amber.feature.webmount.oauth.WebMountOAuthClient
import app.amber.feature.webmount.primitives.SessionHandle
import app.amber.feature.webmount.primitives.WebViewPool

/**
 * 飞书云文档 tool surface.
 *
 *  - feishu_docs_resolve(document_id? / url? / doc_ref?)
 *  - feishu_docs_list(folder_token?, page_size?, page_token?)
 *  - feishu_docs_read(document_id? / url? / doc_ref?)
 *  - feishu_docs_blocks(document_id? / url? / doc_ref?)
 *  - feishu_docs_search(query, limit?)
 *  - feishu_docs_create(title, folder_token?)            ← needsApproval=true
 *  - feishu_docs_append_block(document_id, text)         ← needsApproval=true
 */
class FeishuDocsTools(
    private val client: FeishuDocsClient,
    private val pool: WebViewPool,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun probe(accessToken: String): Boolean = client.probe(accessToken)

    fun buildTools(hooks: WebMountToolHooks, oauth: WebMountOAuthClient): List<Tool> = listOf(
        resolveTool(hooks),
        snapshotTool(hooks),
        networkSummaryTool(hooks),
        markdownPackTool(hooks, oauth),
        listTool(hooks, oauth),
        readTool(hooks, oauth),
        blocksTool(hooks, oauth),
        searchTool(hooks, oauth),
        createTool(hooks, oauth),
        appendBlockTool(hooks, oauth),
        appendHeadingTool(hooks, oauth),
        appendListItemTool(hooks, oauth),
        appendCalloutTool(hooks, oauth),
    )

    private fun resolveTool(hooks: WebMountToolHooks) = Tool(
        name = "feishu_docs_resolve",
        description = """
            Resolve a 飞书 document URL, document_id, or doc_ref into a stable doc_ref. Use this before
            read/blocks when the user gives a pasted 飞书 link. If a wiki/non-docx URL cannot be read by
            OpenAPI, the result explicitly points to feishu_docs_snapshot.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("document_id", stringProp("飞书 docx document id"))
                    put("url", stringProp("飞书 document URL, e.g. https://.../docx/<id>"))
                    put("doc_ref", stringProp("Stable doc_ref returned by feishu_docs_list/search/resolve"))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("feishu_docs_resolve", "飞书 解析引用", input) {
                val result = runCatching {
                    val ref = resolveDocRef(input)
                    if (ref.docType != "docx") unsupportedDocRefJson(ref) else docRefJson(ref)
                }.getOrElse { error ->
                    feishuErrorJson(
                        code = "resolve_failed",
                        message = error.message ?: error.toString(),
                        nextAction = "Pass a Feishu docx URL/document_id/doc_ref, or open the document in WebMount and call feishu_docs_snapshot.",
                    )
                }
                listOf(UIMessagePart.Text(result.toString()))
            }
        },
    )

    private fun snapshotTool(hooks: WebMountToolHooks) = Tool(
        name = "feishu_docs_snapshot",
        description = """
            Read the currently opened 飞书/Lark cloud document page from a WebMount session. Use this when
            OpenAPI cannot resolve a wiki link, or when the user is already viewing the document in WebMount.
            The returned block_ref values are current-WebView-session hints for reading/reference only.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("WebMount session id returned by wm_open. Optional: if omitted, uses the first live Feishu document session."))
                    put("max_blocks", integerProp("Max visible blocks to return. Default 80, cap 300."))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("feishu_docs_snapshot", "飞书 当前页快照", input) {
                val result = runCatching {
                    val handle = requireFeishuSession(input.string("session_id"))
                    val payload = handle.callBridge(
                        method = "feishu_snapshot",
                        args = buildJsonObject {
                            put("max_blocks", (input.long("max_blocks") ?: 80L).coerceIn(1L, 300L))
                        },
                        timeoutMs = 8_000L,
                    )
                    val payloadObj = payload as? JsonObject ?: error("bridge returned non-object snapshot")
                    buildJsonObject {
                        put("session_id", handle.sessionId)
                        payloadObj.forEach { (key, value) -> put(key, value) }
                    }
                }.getOrElse { error ->
                    val message = error.message ?: error.toString()
                    feishuErrorJson(
                        code = if (message.startsWith("not_feishu_doc_page")) "not_feishu_doc_page" else "snapshot_failed",
                        message = message.removePrefix("not_feishu_doc_page:").trim(),
                        nextAction = "Open the Feishu document with wm_open, wait for it to finish loading, then call feishu_docs_snapshot with that session_id.",
                    )
                }
                listOf(UIMessagePart.Text(result.toString()))
            }
        },
    )

    private fun networkSummaryTool(hooks: WebMountToolHooks) = Tool(
        name = "feishu_docs_network_summary",
        description = """
            Return a sanitized Feishu-specific summary of the WebMount session network log. This is for
            diagnosing why a visible Feishu document cannot be read via OpenAPI; it never returns cookies,
            Authorization headers, full query strings, request bodies, or raw response bodies.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("WebMount session id. Optional: if omitted, uses the first live Feishu document session."))
                    put("network_since", integerProp("Return events with seq > this. Default 0."))
                    put("network_max", integerProp("Max events to summarize. Default 80, cap 200."))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("feishu_docs_network_summary", "飞书 网络摘要", input) {
                val result = runCatching {
                    val handle = requireFeishuSession(input.string("session_id"))
                    val since = (input.long("network_since") ?: 0L).coerceAtLeast(0L)
                    val max = (input.long("network_max") ?: 80L).coerceIn(1L, 200L).toInt()
                    FeishuDocsNetworkSummary.summarize(
                        sessionId = handle.sessionId,
                        snapshot = handle.networkLog.snapshot(since, max),
                    )
                }.getOrElse { error ->
                    feishuErrorJson(
                        code = "network_summary_failed",
                        message = error.message ?: error.toString(),
                        nextAction = "Call wm_state for the session to verify that WebMount network logging is active.",
                    )
                }
                listOf(UIMessagePart.Text(result.toString()))
            }
        },
    )

    private fun markdownPackTool(hooks: WebMountToolHooks, oauth: WebMountOAuthClient) = Tool(
        name = "feishu_docs_markdown_pack",
        description = """
            Convert a 飞书 document source into stable Markdown plus block_map. Input can be doc_ref/url/document_id
            (OpenAPI blocks), snapshot_json from feishu_docs_snapshot, or blocks_json from feishu_docs_blocks.
            Prefer this before summarizing, rewriting, PRD extraction, meeting notes, or daily reports.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("document_id", stringProp("飞书 docx document id"))
                    put("url", stringProp("飞书 document URL"))
                    put("doc_ref", stringProp("Stable doc_ref returned by feishu_docs_list/search/resolve"))
                    put("snapshot_json", stringProp("Raw JSON string returned by feishu_docs_snapshot"))
                    put("blocks_json", stringProp("Raw JSON string returned by feishu_docs_blocks"))
                    put("title", stringProp("Optional title override for OpenAPI block packing"))
                    put("start_char", integerProp("Start offset for chunked Markdown output. Default 0."))
                    put("max_chars", integerProp("Max Markdown chars to return. Default 60_000, cap 200_000."))
                    put("max_blocks", integerProp("OpenAPI block read cap. Default 500, cap 2_000."))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("feishu_docs_markdown_pack", "飞书 Markdown Pack", input) {
                val result = runCatching {
                    val start = (input.long("start_char") ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    val maxChars = (input.long("max_chars") ?: 60_000L).coerceIn(1L, 200_000L).toInt()
                    input.string("snapshot_json")?.takeIf { it.isNotBlank() }?.let { raw ->
                        return@runCatching FeishuDocsMarkdownPack.fromSnapshot(
                            snapshot = json.parseToJsonElement(raw).jsonObject,
                            startChar = start,
                            maxChars = maxChars,
                        )
                    }
                    input.string("blocks_json")?.takeIf { it.isNotBlank() }?.let { raw ->
                        return@runCatching FeishuDocsMarkdownPack.fromBlocksPayload(
                            payload = json.parseToJsonElement(raw).jsonObject,
                            startChar = start,
                            maxChars = maxChars,
                        )
                    }
                    val token = requireToken(oauth)
                    val ref = resolveDocRef(input)
                    require(ref.docType == "docx") {
                        "feishu_docs_markdown_pack OpenAPI mode requires docx; got ${ref.docType}. Use snapshot_json from feishu_docs_snapshot."
                    }
                    val maxBlocks = (input.long("max_blocks") ?: 500L).coerceIn(1L, 2_000L).toInt()
                    val (blocks, sourceTruncated) = readBlocksForPack(token, ref.documentId, maxBlocks)
                    FeishuDocsMarkdownPack.fromBlocks(
                        documentId = ref.documentId,
                        docRef = FeishuDocRefs.encode(ref),
                        blocks = blocks,
                        title = input.string("title"),
                        startChar = start,
                        maxChars = maxChars,
                        sourceTruncated = sourceTruncated,
                    )
                }.getOrElse { error ->
                    feishuErrorJson(
                        code = "markdown_pack_failed",
                        message = error.message ?: error.toString(),
                        nextAction = "Pass doc_ref/url/document_id for OpenAPI blocks, or pass snapshot_json from feishu_docs_snapshot.",
                    )
                }
                listOf(UIMessagePart.Text(result.toString()))
            }
        },
    )

    private fun listTool(hooks: WebMountToolHooks, oauth: WebMountOAuthClient) = Tool(
        name = "feishu_docs_list",
        description = """
            List 飞书 cloud-drive files. With `folder_token` omitted, lists the OAuth user's
            root drive. Returns each file's token, name, type (docx/sheet/bitable/folder/...),
            URL, and timestamps. `page_token` paginates.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("folder_token", stringProp("Folder token to list within. Omit for drive root."))
                    put("page_size", integerProp("1-200, default 50"))
                    put("page_token", stringProp("Pagination cursor"))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("feishu_docs_list", "飞书 文档列表", input) {
                val token = requireToken(oauth)
                val folder = input.string("folder_token")
                val pageSize = (input.long("page_size") ?: 50L).coerceIn(1L, 200L).toInt()
                val pageToken = input.string("page_token")
                val result = client.listFiles(token, folder, pageSize, pageToken)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("count", result.files.size)
                    put("next_page_token", result.nextPageToken)
                    put("has_more", result.hasMore)
                    put("files", buildJsonArray { result.files.forEach { add(summaryJson(it)) } })
                }.toString()))
            }
        },
    )

    private fun readTool(hooks: WebMountToolHooks, oauth: WebMountOAuthClient) = Tool(
        name = "feishu_docs_read",
        description = """
            Read a 飞书 docx document as Markdown text. Prefer `doc_ref`; `url` and `document_id`
            are also accepted. Supports chunking with `start_char` + `max_chars`.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("document_id", stringProp("飞书 docx document id"))
                    put("url", stringProp("飞书 document URL, e.g. https://.../docx/<id>"))
                    put("doc_ref", stringProp("Stable doc_ref returned by feishu_docs_list/search/resolve"))
                    put("start_char", integerProp("Start offset for chunked reads. Default 0."))
                    put("max_chars", integerProp("Truncate content to this many chars. Default 60_000, cap 200_000."))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("feishu_docs_read", "飞书 文档读取", input) {
                val result = runCatching {
                    val ref = resolveDocRef(input)
                    require(ref.docType == "docx") {
                        "feishu_docs_read currently supports docx documents; got ${ref.docType}. Use feishu_docs_snapshot for the visible WebView page."
                    }
                    val token = requireToken(oauth)
                    val docId = ref.documentId
                    val start = (input.long("start_char") ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    val maxChars = (input.long("max_chars") ?: 60_000L).coerceIn(1L, 200_000L).toInt()
                    val content = client.readRawContent(token, docId) ?: error("Document not found: $docId")
                    val safeStart = start.coerceAtMost(content.length)
                    val end = (safeStart + maxChars).coerceAtMost(content.length)
                    val truncated = end < content.length
                    buildJsonObject {
                        put("ok", true)
                        put("document_id", docId)
                        put("doc_ref", FeishuDocRefs.encode(ref))
                        put("doc_type", ref.docType)
                        put("content", content.substring(safeStart, end))
                        put("start_char", safeStart)
                        put("total_chars", content.length)
                        put("truncated", truncated)
                        if (truncated) put("next_start_char", end)
                    }
                }.getOrElse { error ->
                    feishuErrorJson(
                        code = "read_failed",
                        message = error.message ?: error.toString(),
                        nextAction = "If this is a wiki or non-docx link, open it in WebMount and call feishu_docs_snapshot. Otherwise reconnect Feishu OAuth or verify document permissions.",
                    )
                }
                listOf(UIMessagePart.Text(result.toString()))
            }
        },
    )

    private fun blocksTool(hooks: WebMountToolHooks, oauth: WebMountOAuthClient) = Tool(
        name = "feishu_docs_blocks",
        description = """
            List 飞书 docx blocks with stable block_ref values. Use it to build an outline,
            inspect block-level structure, or choose a parent_block_ref for append tools.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("document_id", stringProp("飞书 docx document id"))
                    put("url", stringProp("飞书 document URL, e.g. https://.../docx/<id>"))
                    put("doc_ref", stringProp("Stable doc_ref returned by feishu_docs_list/search/resolve"))
                    put("page_size", integerProp("1-500, default 100"))
                    put("page_token", stringProp("Pagination cursor"))
                    put("max_text_chars", integerProp("Truncate each block text to this many chars. Default 500."))
                    put("include_raw", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include raw block JSON for debugging. Default false.")
                    })
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("feishu_docs_blocks", "飞书 Block 列表", input) {
                val payload = runCatching {
                    val ref = resolveDocRef(input)
                    require(ref.docType == "docx") {
                        "feishu_docs_blocks currently supports docx documents; got ${ref.docType}. Use feishu_docs_snapshot for the visible WebView page."
                    }
                    val token = requireToken(oauth)
                    val pageSize = (input.long("page_size") ?: 100L).coerceIn(1L, 500L).toInt()
                    val maxTextChars = (input.long("max_text_chars") ?: 500L).coerceIn(0L, 20_000L).toInt()
                    val includeRaw = input.boolean("include_raw") == true
                    val result = client.listBlocks(
                        accessToken = token,
                        documentId = ref.documentId,
                        pageSize = pageSize,
                        pageToken = input.string("page_token"),
                    )
                    buildJsonObject {
                        put("ok", true)
                        put("document_id", ref.documentId)
                        put("doc_ref", FeishuDocRefs.encode(ref))
                        put("doc_type", ref.docType)
                        put("count", result.blocks.size)
                        put("next_page_token", result.nextPageToken)
                        put("has_more", result.hasMore)
                        put("blocks", buildJsonArray {
                            result.blocks.forEach { block ->
                                add(blockJson(ref.documentId, block, maxTextChars, includeRaw))
                            }
                        })
                    }
                }.getOrElse { error ->
                    feishuErrorJson(
                        code = "blocks_failed",
                        message = error.message ?: error.toString(),
                        nextAction = "If OpenAPI cannot read this document, use feishu_docs_snapshot for current visible content or feishu_docs_network_summary for diagnosis.",
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    private fun searchTool(hooks: WebMountToolHooks, oauth: WebMountOAuthClient) = Tool(
        name = "feishu_docs_search",
        description = "Search the user's 飞书 docs by keyword. Returns up to `limit` matching documents.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringProp("Search query"))
                    put("limit", integerProp("1-100, default 20"))
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            hooks.track("feishu_docs_search", "飞书 搜索", input) {
                val token = requireToken(oauth)
                val query = input.requiredString("query")
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 100L).toInt()
                val results = client.search(token, query, limit)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("query", query)
                    put("count", results.size)
                    put("results", buildJsonArray { results.forEach { add(summaryJson(it)) } })
                }.toString()))
            }
        },
    )

    private fun createTool(hooks: WebMountToolHooks, oauth: WebMountOAuthClient) = Tool(
        name = "feishu_docs_create",
        description = """
            Create a new empty 飞书 docx document with the given title. `folder_token` puts it in a
            specific folder; omit for the user's drive root. Returns the new document's token + URL.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", stringProp("Document title"))
                    put("folder_token", stringProp("Folder token (optional)"))
                },
                required = listOf("title"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            hooks.track("feishu_docs_create", "飞书 新建文档", input) {
                val token = requireToken(oauth)
                val title = input.requiredString("title")
                val folder = input.string("folder_token")
                val created = client.createDocument(token, title, folder)
                listOf(UIMessagePart.Text(summaryJson(created).toString()))
            }
        },
    )

    private fun appendBlockTool(hooks: WebMountToolHooks, oauth: WebMountOAuthClient) = Tool(
        name = "feishu_docs_append_block",
        description = """
            Append a paragraph of plain text to a 飞书 docx document. `parent_block_id` selects
            which block to append under — omit it and the tool resolves the document's root
            (page-level) block automatically by listing blocks once. Rich block trees (headings,
            tables, callouts) are out of Phase 1 scope; build text content by calling this
            multiple times. The agent is responsible for any newlines inside `text`.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("document_id", stringProp("Target document id"))
                    put("text", stringProp("Plain text to append as one paragraph"))
                    put("parent_block_id", stringProp("Parent block id (optional; defaults to document root)"))
                    put("parent_block_ref", stringProp("Stable parent block_ref from feishu_docs_blocks (optional)"))
                },
                required = listOf("document_id", "text"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            hooks.track("feishu_docs_append_block", "飞书 追加段落", input) {
                val token = requireToken(oauth)
                val docId = input.requiredString("document_id")
                val text = input.requiredString("text")
                val parentBlockId = resolveParentBlockId(input, docId)
                val response = client.appendTextBlock(token, docId, text, parentBlockId)
                // Flat output per WebMountAdapter kdoc convention. Extract the
                // useful fields from 飞书's response instead of nesting the raw
                // envelope. Block ids are what the agent actually wants to keep.
                val createdBlockIds: List<String> = (response["children"] as? JsonArray)
                    ?.mapNotNull { entry ->
                        (entry as? JsonObject)?.get("block_id")?.jsonPrimitive?.contentOrNull
                    }
                    ?: emptyList()
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("ok", true)
                    put("document_id", docId)
                    parentBlockId?.let { put("parent_block_id", it) }
                    put("appended_block_count", createdBlockIds.size)
                    if (createdBlockIds.isNotEmpty()) {
                        put("appended_block_ids", buildJsonArray {
                            createdBlockIds.forEach { add(JsonPrimitive(it)) }
                        })
                        put("appended_block_refs", buildJsonArray {
                            createdBlockIds.forEach { add(JsonPrimitive(FeishuDocRefs.encodeBlock(docId, it))) }
                        })
                    }
                }.toString()))
            }
        },
    )

    // ---- Phase 2 M2.4 rich-text tools ---------------------------------

    private fun appendHeadingTool(hooks: WebMountToolHooks, oauth: WebMountOAuthClient) = Tool(
        name = "feishu_docs_append_heading",
        description = """
            Append a heading block (H1-H9) to a 飞书 docx. `level` 1-9 maps to heading1..heading9
            (display-wise H1 is largest). Same shape and approval flow as feishu_docs_append_block.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("document_id", stringProp("Target document id"))
                    put("level", integerProp("Heading level 1-9"))
                    put("text", stringProp("Heading text content"))
                    put("parent_block_id", stringProp("Parent block id (optional)"))
                    put("parent_block_ref", stringProp("Stable parent block_ref from feishu_docs_blocks (optional)"))
                },
                required = listOf("document_id", "level", "text"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            hooks.track("feishu_docs_append_heading", "飞书 追加标题", input) {
                val token = requireToken(oauth)
                val docId = input.requiredString("document_id")
                val level = (input.long("level") ?: error("level is required (1-9)"))
                    .coerceIn(1L, 9L).toInt()
                val text = input.requiredString("text")
                val parentBlockId = resolveParentBlockId(input, docId)
                val response = client.appendRichBlock(
                    accessToken = token,
                    documentId = docId,
                    blockKind = "heading$level",
                    text = text,
                    parentBlockId = parentBlockId,
                )
                listOf(UIMessagePart.Text(formatAppendResult(docId, parentBlockId, response).toString()))
            }
        },
    )

    private fun appendListItemTool(hooks: WebMountToolHooks, oauth: WebMountOAuthClient) = Tool(
        name = "feishu_docs_append_list_item",
        description = """
            Append a single list-item block (bullet or ordered) to a 飞书 docx. Set `ordered=true`
            for numbered lists, false (default) for bullets. Lists are flat sequences of items in
            飞书's block tree — chain multiple calls to build a longer list. Each call returns the
            new item's block_id so the agent can later append a nested sub-item by passing
            `parent_block_id` = that id.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("document_id", stringProp("Target document id"))
                    put("text", stringProp("Item text content"))
                    put("ordered", buildJsonObject {
                        put("type", "boolean")
                        put("description", "true → numbered list; false (default) → bullet")
                    })
                    put("parent_block_id", stringProp("Parent block id (optional)"))
                    put("parent_block_ref", stringProp("Stable parent block_ref from feishu_docs_blocks (optional)"))
                },
                required = listOf("document_id", "text"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            hooks.track("feishu_docs_append_list_item", "飞书 追加列表项", input) {
                val token = requireToken(oauth)
                val docId = input.requiredString("document_id")
                val text = input.requiredString("text")
                val ordered = input.boolean("ordered") == true
                val parentBlockId = resolveParentBlockId(input, docId)
                val response = client.appendRichBlock(
                    accessToken = token,
                    documentId = docId,
                    blockKind = if (ordered) "ordered" else "bullet",
                    text = text,
                    parentBlockId = parentBlockId,
                )
                listOf(UIMessagePart.Text(formatAppendResult(docId, parentBlockId, response).toString()))
            }
        },
    )

    private fun appendCalloutTool(hooks: WebMountToolHooks, oauth: WebMountOAuthClient) = Tool(
        name = "feishu_docs_append_callout",
        description = """
            Append a callout block (a highlighted box with an emoji marker) containing a single
            paragraph of text. Useful for notes / warnings / quotes. The callout uses the default
            neutral styling — color customization is out of Phase 2 scope.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("document_id", stringProp("Target document id"))
                    put("text", stringProp("Callout text content"))
                    put("parent_block_id", stringProp("Parent block id (optional)"))
                    put("parent_block_ref", stringProp("Stable parent block_ref from feishu_docs_blocks (optional)"))
                },
                required = listOf("document_id", "text"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            hooks.track("feishu_docs_append_callout", "飞书 追加 Callout", input) {
                val token = requireToken(oauth)
                val docId = input.requiredString("document_id")
                val text = input.requiredString("text")
                val parentBlockId = resolveParentBlockId(input, docId)
                val response = client.appendRichBlock(
                    accessToken = token,
                    documentId = docId,
                    blockKind = "callout",
                    text = text,
                    parentBlockId = parentBlockId,
                )
                listOf(UIMessagePart.Text(formatAppendResult(docId, parentBlockId, response).toString()))
            }
        },
    )

    /** Shared output shape — matches feishu_docs_append_block (M2.0 D6 fix). */
    private fun formatAppendResult(
        docId: String,
        parentBlockId: String?,
        response: JsonObject,
    ): JsonObject {
        val createdBlockIds: List<String> = (response["children"] as? JsonArray)
            ?.mapNotNull { entry ->
                (entry as? JsonObject)?.get("block_id")?.jsonPrimitive?.contentOrNull
            }
            ?: emptyList()
        return buildJsonObject {
            put("ok", true)
            put("document_id", docId)
            parentBlockId?.let { put("parent_block_id", it) }
            put("appended_block_count", createdBlockIds.size)
            if (createdBlockIds.isNotEmpty()) {
                put("appended_block_ids", buildJsonArray {
                    createdBlockIds.forEach { add(JsonPrimitive(it)) }
                })
                put("appended_block_refs", buildJsonArray {
                    createdBlockIds.forEach { add(JsonPrimitive(FeishuDocRefs.encodeBlock(docId, it))) }
                })
            }
        }
    }

    private suspend fun requireToken(oauth: WebMountOAuthClient): String =
        oauth.getValidAccessToken("feishu")
            ?: error("飞书 OAuth 未连接 —— 在 WebMount Stations 设置页找到「飞书云文档」一行,先点「编辑凭据」配好 App ID / Secret,再点「Connect」。")

    private fun summaryJson(s: FeishuDocSummary): JsonObject = buildJsonObject {
        put("token", s.token)
        put("name", s.name)
        put("type", s.type)
        if (s.type == "docx") put("doc_ref", FeishuDocRefs.encode(s.token, s.type, s.url))
        s.url?.let { put("url", it) }
        s.ownerOpenId?.let { put("owner_open_id", it) }
        s.parentToken?.let { put("parent_token", it) }
        if (s.createdAtMs > 0) put("created_at_ms", s.createdAtMs)
        if (s.modifiedAtMs > 0) put("modified_at_ms", s.modifiedAtMs)
    }

    private fun docRefJson(ref: FeishuDocRef): JsonObject = buildJsonObject {
        put("ok", true)
        put("document_id", ref.documentId)
        put("doc_type", ref.docType)
        put("doc_ref", FeishuDocRefs.encode(ref))
        ref.sourceUrl?.let { put("url", it) }
        put("openapi_docx_readable", ref.docType == "docx")
    }

    private fun unsupportedDocRefJson(ref: FeishuDocRef): JsonObject = buildJsonObject {
        put("ok", false)
        put("document_id", ref.documentId)
        put("doc_type", ref.docType)
        ref.sourceUrl?.let { put("url", it) }
        put("openapi_docx_readable", false)
        put("fallback", "use_feishu_docs_snapshot")
        put("next_action", "Open the link in WebMount, then call feishu_docs_snapshot for the current visible page.")
        put(
            "message",
            "This link is a ${ref.docType} token. feishu_docs_read/blocks currently require a docx document id; use feishu_docs_snapshot for the visible WebView page, or pass the real docx id.",
        )
    }

    private fun blockJson(
        documentId: String,
        block: FeishuDocBlock,
        maxTextChars: Int,
        includeRaw: Boolean,
    ): JsonObject = buildJsonObject {
        put("block_id", block.blockId)
        put("block_ref", FeishuDocRefs.encodeBlock(documentId, block.blockId))
        block.parentId?.let { put("parent_id", it) }
        block.blockType?.let { put("block_type", it) }
        block.headingLevel?.let { put("heading_level", it) }
        put("children_count", block.childrenCount)
        block.text?.let { text ->
            val capped = if (maxTextChars <= 0) "" else text.take(maxTextChars)
            put("text", capped)
            put("text_chars", text.length)
            put("text_truncated", capped.length < text.length)
        }
        if (includeRaw) put("raw", block.raw)
    }

    private fun resolveDocRef(input: JsonElement): FeishuDocRef {
        FeishuDocRefs.decode(input.string("doc_ref"))?.let { return it }
        FeishuDocRefs.fromUrl(input.string("url"))?.let { return it }
        input.string("document_id")?.takeIf { it.isNotBlank() }?.let {
            return FeishuDocRef(documentId = it, docType = "docx")
        }
        error("document_id, url, or doc_ref is required")
    }

    private fun resolveParentBlockId(input: JsonElement, documentId: String): String? {
        val blockRef = FeishuDocRefs.decodeBlock(input.string("parent_block_ref"))
        if (blockRef != null) {
            require(blockRef.documentId == documentId) {
                "parent_block_ref belongs to ${blockRef.documentId}, not $documentId"
            }
            return blockRef.blockId
        }
        return input.string("parent_block_id")
    }

    private fun requireFeishuSession(sessionId: String?): SessionHandle {
        if (!sessionId.isNullOrBlank()) {
            val handle = pool.peek(sessionId) ?: error("WebMount session not found: $sessionId")
            require(isFeishuDocUrl(handle.loadState.value.currentUrl)) {
                "not_feishu_doc_page: WebMount session is not a Feishu/Lark document page: $sessionId"
            }
            return handle
        }
        return pool.listSessions().firstOrNull { handle ->
            isFeishuDocUrl(handle.loadState.value.currentUrl)
        } ?: error("No live Feishu document WebMount session found. Open the document with wm_open or pass session_id.")
    }

    private fun isFeishuDocUrl(url: String?): Boolean =
        FeishuDocRefs.isFeishuDocumentUrl(url)

    private suspend fun readBlocksForPack(
        accessToken: String,
        documentId: String,
        maxBlocks: Int,
    ): Pair<List<FeishuDocBlock>, Boolean> {
        val out = mutableListOf<FeishuDocBlock>()
        var pageToken: String? = null
        var hasMore = true
        while (hasMore && out.size < maxBlocks) {
            val page = client.listBlocks(
                accessToken = accessToken,
                documentId = documentId,
                pageSize = (maxBlocks - out.size).coerceIn(1, 500),
                pageToken = pageToken,
            )
            out += page.blocks
            hasMore = page.hasMore
            pageToken = page.nextPageToken
            if (page.blocks.isEmpty() || pageToken.isNullOrBlank()) break
        }
        return out to (hasMore && out.size >= maxBlocks)
    }

    private fun feishuErrorJson(
        code: String,
        message: String,
        nextAction: String,
    ): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
            put("next_action", nextAction)
        })
        put("next_action", nextAction)
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string"); put("description", description)
    }
    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer"); put("description", description)
    }
}

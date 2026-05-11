package me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.tools.boolean
import me.rerere.rikkahub.data.agent.tools.long
import me.rerere.rikkahub.data.agent.tools.requiredString
import me.rerere.rikkahub.data.agent.tools.string
import me.rerere.rikkahub.data.agent.webmount.core.WebMountToolHooks
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthClient

/**
 * 飞书云文档 tool surface.
 *
 *  - feishu_docs_list(folder_token?, page_size?, page_token?)
 *  - feishu_docs_read(document_id)
 *  - feishu_docs_search(query, limit?)
 *  - feishu_docs_create(title, folder_token?)            ← needsApproval=true
 *  - feishu_docs_append_block(document_id, text)         ← needsApproval=true
 */
class FeishuDocsTools(private val client: FeishuDocsClient) {

    suspend fun probe(accessToken: String): Boolean = client.probe(accessToken)

    fun buildTools(hooks: WebMountToolHooks, oauth: WebMountOAuthClient): List<Tool> = listOf(
        listTool(hooks, oauth),
        readTool(hooks, oauth),
        searchTool(hooks, oauth),
        createTool(hooks, oauth),
        appendBlockTool(hooks, oauth),
        appendHeadingTool(hooks, oauth),
        appendListItemTool(hooks, oauth),
        appendCalloutTool(hooks, oauth),
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
        description = "Read a 飞书 docx document as Markdown text. `document_id` is the doc id (visible in its URL).",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("document_id", stringProp("飞书 docx document id"))
                    put("max_chars", integerProp("Truncate content to this many chars. Default 60_000, cap 200_000."))
                },
                required = listOf("document_id"),
            )
        },
        execute = { input ->
            hooks.track("feishu_docs_read", "飞书 文档读取", input) {
                val token = requireToken(oauth)
                val docId = input.requiredString("document_id")
                val maxChars = (input.long("max_chars") ?: 60_000L).coerceIn(1L, 200_000L).toInt()
                val content = client.readRawContent(token, docId) ?: error("Document not found: $docId")
                val truncated = content.length > maxChars
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("document_id", docId)
                    put("content", if (truncated) content.take(maxChars) else content)
                    put("total_chars", content.length)
                    put("truncated", truncated)
                }.toString()))
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
                val parentBlockId = input.string("parent_block_id")
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
                val parentBlockId = input.string("parent_block_id")
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
                val parentBlockId = input.string("parent_block_id")
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
                val parentBlockId = input.string("parent_block_id")
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
            }
        }
    }

    private suspend fun requireToken(oauth: WebMountOAuthClient): String =
        oauth.getValidAccessToken("feishu")
            ?: error("飞书 OAuth not connected — connect via WebMount Stations → OAuth providers first")

    private fun summaryJson(s: FeishuDocSummary): JsonObject = buildJsonObject {
        put("token", s.token)
        put("name", s.name)
        put("type", s.type)
        s.url?.let { put("url", it) }
        s.ownerOpenId?.let { put("owner_open_id", it) }
        s.parentToken?.let { put("parent_token", it) }
        if (s.createdAtMs > 0) put("created_at_ms", s.createdAtMs)
        if (s.modifiedAtMs > 0) put("modified_at_ms", s.modifiedAtMs)
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string"); put("description", description)
    }
    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer"); put("description", description)
    }
}

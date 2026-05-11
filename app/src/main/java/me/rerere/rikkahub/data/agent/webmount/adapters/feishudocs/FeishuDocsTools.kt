package me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
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
            Append a paragraph of plain text to the end of a 飞书 docx document. Rich block trees
            (headings, tables, callouts) are out of Phase 1 scope; build text content by calling
            this multiple times. The agent is responsible for any newlines inside `text`.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("document_id", stringProp("Target document id"))
                    put("text", stringProp("Plain text to append as one paragraph"))
                },
                required = listOf("document_id", "text"),
            )
        },
        needsApproval = true,
        execute = { input ->
            hooks.track("feishu_docs_append_block", "飞书 追加段落", input) {
                val token = requireToken(oauth)
                val docId = input.requiredString("document_id")
                val text = input.requiredString("text")
                val response = client.appendTextBlock(token, docId, text)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("document_id", docId)
                    put("ok", true)
                    put("response", response)
                }.toString()))
            }
        },
    )

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

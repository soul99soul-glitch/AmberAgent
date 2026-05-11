package me.rerere.rikkahub.data.agent.webmount.adapters.juejin

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
import me.rerere.rikkahub.data.agent.webmount.cookie.EndpointSpec
import me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCookieBundle
import me.rerere.rikkahub.data.agent.webmount.core.WebMountToolHooks

class JuejinTools(private val client: JuejinClient) {

    suspend fun smokeProbe(cookies: WebMountCookieBundle): Boolean =
        runCatching {
            client.recommendArticles(cookies, limit = 1, cursor = null).items.isNotEmpty()
        }.getOrElse { false }

    fun buildTools(
        hooks: WebMountToolHooks,
        endpoints: List<EndpointSpec>,
        cookieProvider: WebMountCookieProvider,
    ): List<Tool> = listOf(
        feedTool(hooks, endpoints, cookieProvider),
        pinFeedTool(hooks, endpoints, cookieProvider),
        articleReadTool(hooks, endpoints, cookieProvider),
        searchTool(hooks, endpoints, cookieProvider),
        myPostsTool(hooks, endpoints, cookieProvider),
    )

    private fun feedTool(
        hooks: WebMountToolHooks,
        endpoints: List<EndpointSpec>,
        cookieProvider: WebMountCookieProvider,
    ) = Tool(
        name = "juejin_feed",
        description = """
            掘金推荐文章 feed. Returns up to `limit` articles with title / brief / author / view+digg+comment
            counts. Pass `cursor` from a prior call to paginate. Public — works anonymously but rate-limited.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", integerProp("1-50, default 20"))
                    put("cursor", stringProp("Pagination cursor from a prior call (default '0')"))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("juejin_feed", "掘金推荐", input) {
                val cookies = cookieProvider.getCookies(endpoints)
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 50L).toInt()
                val cursor = input.string("cursor")
                val listing = client.recommendArticles(cookies, limit, cursor)
                listOf(UIMessagePart.Text(buildListingJson("articles", listing).toString()))
            }
        },
    )

    private fun pinFeedTool(
        hooks: WebMountToolHooks,
        endpoints: List<EndpointSpec>,
        cookieProvider: WebMountCookieProvider,
    ) = Tool(
        name = "juejin_pins",
        description = "掘金沸点 (short messages) feed. Similar pagination shape as juejin_feed.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", integerProp("1-50, default 20"))
                    put("cursor", stringProp("Pagination cursor"))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("juejin_pins", "掘金沸点", input) {
                val cookies = cookieProvider.getCookies(endpoints)
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 50L).toInt()
                val cursor = input.string("cursor")
                val listing = client.shortMsgFeed(cookies, limit, cursor)
                listOf(UIMessagePart.Text(buildListingJson("pins", listing).toString()))
            }
        },
    )

    private fun articleReadTool(
        hooks: WebMountToolHooks,
        endpoints: List<EndpointSpec>,
        cookieProvider: WebMountCookieProvider,
    ) = Tool(
        name = "juejin_article_read",
        description = """
            Read a 掘金 article in full. `article_id` is the numeric string in the article's URL
            (juejin.cn/post/<id>). Returns title, full mark_content (Markdown), counts.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { put("article_id", stringProp("Numeric article id from the post URL")) },
                required = listOf("article_id"),
            )
        },
        execute = { input ->
            hooks.track("juejin_article_read", "掘金读取", input) {
                val cookies = cookieProvider.getCookies(endpoints)
                val articleId = input.requiredString("article_id")
                val article = client.articleDetail(cookies, articleId)
                    ?: error("Article not found: $articleId")
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("article_id", article.articleId)
                    put("title", article.title)
                    article.content?.let { put("content", it.take(60_000)) }
                    article.authorName?.let { put("author", it) }
                    article.viewCount?.let { put("view_count", it) }
                    article.diggCount?.let { put("digg_count", it) }
                    article.commentCount?.let { put("comment_count", it) }
                    put("created_at_ms", article.createdAtMs)
                    article.articleUrl?.let { put("url", it) }
                }.toString()))
            }
        },
    )

    private fun searchTool(
        hooks: WebMountToolHooks,
        endpoints: List<EndpointSpec>,
        cookieProvider: WebMountCookieProvider,
    ) = Tool(
        name = "juejin_search",
        description = "Full-text search 掘金 articles. Returns up to `limit` hits, same shape as juejin_feed.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringProp("Search keywords"))
                    put("limit", integerProp("1-50, default 20"))
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            hooks.track("juejin_search", "掘金搜索", input) {
                val cookies = cookieProvider.getCookies(endpoints)
                val query = input.requiredString("query")
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 50L).toInt()
                val listing = client.searchArticles(cookies, query, limit)
                val payload = buildListingJson("articles", listing)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("query", query)
                    payload.forEach { (k, v) -> put(k, v) }
                }.toString()))
            }
        },
    )

    private fun myPostsTool(
        hooks: WebMountToolHooks,
        endpoints: List<EndpointSpec>,
        cookieProvider: WebMountCookieProvider,
    ) = Tool(
        name = "juejin_my_posts",
        description = """
            List articles published by a 掘金 user. Pass the numeric user_id (the long number in the
            profile URL — `juejin.cn/user/<user_id>`). Works without login for public profiles but
            personal drafts require the user's own cookies.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("user_id", stringProp("Numeric 掘金 user id"))
                    put("limit", integerProp("1-50, default 20"))
                    put("cursor", stringProp("Pagination cursor"))
                },
                required = listOf("user_id"),
            )
        },
        execute = { input ->
            hooks.track("juejin_my_posts", "掘金作者文章", input) {
                val cookies = cookieProvider.getCookies(endpoints)
                val userId = input.requiredString("user_id")
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 50L).toInt()
                val cursor = input.string("cursor")
                val listing = client.myPosts(cookies, userId, limit, cursor)
                val payload = buildListingJson("articles", listing)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("user_id", userId)
                    payload.forEach { (k, v) -> put(k, v) }
                }.toString()))
            }
        },
    )

    private fun buildListingJson(itemsKey: String, listing: JuejinListing): JsonObject = buildJsonObject {
        put("cursor", listing.cursor)
        put("has_more", listing.hasMore)
        put("count", listing.items.size)
        put(itemsKey, buildJsonArray { listing.items.forEach { add(itemToJson(it)) } })
    }

    private fun itemToJson(item: JuejinItem): JsonObject = buildJsonObject {
        when (item) {
            is JuejinItem.Article -> {
                put("kind", "article")
                put("article_id", item.articleId)
                put("title", item.title)
                item.briefContent?.let { put("brief", it.take(400)) }
                item.authorName?.let { put("author", it) }
                item.viewCount?.let { put("view_count", it) }
                item.diggCount?.let { put("digg_count", it) }
                item.commentCount?.let { put("comment_count", it) }
                put("created_at_ms", item.createdAtMs)
                item.articleUrl?.let { put("url", it) }
            }
            is JuejinItem.Pin -> {
                put("kind", "pin")
                put("msg_id", item.msgId)
                item.content?.let { put("content", it.take(800)) }
                item.authorName?.let { put("author", it) }
                item.diggCount?.let { put("digg_count", it) }
                item.commentCount?.let { put("comment_count", it) }
                put("created_at_ms", item.createdAtMs)
            }
        }
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string"); put("description", description)
    }
    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer"); put("description", description)
    }
}

package app.amber.feature.webmount.adapters.reddit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.feature.tools.long
import app.amber.feature.tools.requiredString
import app.amber.feature.tools.string
import app.amber.feature.webmount.core.WebMountToolHooks

class RedditTools(private val client: RedditClient) {

    suspend fun smokeProbe(): Boolean =
        client.listing(subreddit = null, sort = RedditSort.HOT, limit = 1, after = null)
            .children.isNotEmpty()

    fun buildTools(hooks: WebMountToolHooks): List<Tool> = listOf(
        topTool(hooks),
        subredditReadTool(hooks),
        postReadTool(hooks),
        searchTool(hooks),
    )

    private fun topTool(hooks: WebMountToolHooks) = Tool(
        name = "reddit_top",
        description = """
            Read a Reddit subreddit's front page (or /r/all if subreddit omitted). Sort:
            hot (default) / new / top / rising / controversial. Returns post metadata
            and an `after` cursor for pagination.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("subreddit", stringProp("Subreddit name without leading r/. Omit for site-wide /r/all."))
                    put("sort", stringProp("hot | new | top | rising | controversial. Default hot."))
                    put("limit", integerProp("1-100, default 25"))
                    put("after", stringProp("Pagination cursor (the `after` from a previous call)."))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("reddit_top", "Reddit Listing", input) {
                val subreddit = input.string("subreddit")
                val sort = RedditSort.fromWire(input.string("sort"))
                val limit = (input.long("limit") ?: 25L).coerceIn(1L, 100L).toInt()
                val after = input.string("after")
                val listing = client.listing(subreddit, sort, limit, after)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("subreddit", subreddit ?: "all")
                    put("sort", sort.endpoint)
                    put("after", listing.after)
                    put("before", listing.before)
                    put("count", listing.children.size)
                    put("posts", buildJsonArray { listing.children.forEach { add(childToJson(it)) } })
                }.toString()))
            }
        },
    )

    private fun subredditReadTool(hooks: WebMountToolHooks) = Tool(
        name = "reddit_subreddit_read",
        description = "Read public 'about' metadata for a subreddit: subscribers, description, public_description, created_utc.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { put("subreddit", stringProp("Subreddit name without r/")) },
                required = listOf("subreddit"),
            )
        },
        execute = { input ->
            hooks.track("reddit_subreddit_read", "Reddit Subreddit", input) {
                val subreddit = input.requiredString("subreddit")
                val data = client.about(subreddit) ?: error("Subreddit not found: $subreddit")
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("subreddit", subreddit)
                    put("about", data)
                }.toString()))
            }
        },
    )

    private fun postReadTool(hooks: WebMountToolHooks) = Tool(
        name = "reddit_post_read",
        description = """
            Fetch a single Reddit post with its top-level comments. `permalink` is the path Reddit
            assigns each post (e.g. "/r/programming/comments/abcdef/post_title/"); the leading slash
            is optional. Returns the post + up to `comment_limit` first-level comments.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("permalink", stringProp("Reddit permalink path (without https://www.reddit.com prefix)"))
                    put("comment_limit", integerProp("1-200, default 30"))
                },
                required = listOf("permalink"),
            )
        },
        execute = { input ->
            hooks.track("reddit_post_read", "Reddit Post", input) {
                val permalink = input.requiredString("permalink")
                val limit = (input.long("comment_limit") ?: 30L).coerceIn(1L, 200L).toInt()
                val detail = client.postWithComments(permalink, limit)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("post", childToJson(detail.post))
                    put("comment_count", detail.comments.size)
                    put("comments", buildJsonArray { detail.comments.forEach { add(childToJson(it)) } })
                }.toString()))
            }
        },
    )

    private fun searchTool(hooks: WebMountToolHooks) = Tool(
        name = "reddit_search",
        description = """
            Full-text search Reddit. Without `subreddit`, searches all of Reddit; with it, restricts
            to that subreddit only. Returns post metadata in the same shape as reddit_top.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringProp("Search keywords"))
                    put("subreddit", stringProp("Restrict to one subreddit (optional)"))
                    put("limit", integerProp("1-100, default 25"))
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            hooks.track("reddit_search", "Reddit Search", input) {
                val query = input.requiredString("query")
                val subreddit = input.string("subreddit")
                val limit = (input.long("limit") ?: 25L).coerceIn(1L, 100L).toInt()
                val listing = client.search(query, subreddit, limit)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("query", query)
                    put("subreddit", subreddit)
                    put("count", listing.children.size)
                    put("posts", buildJsonArray { listing.children.forEach { add(childToJson(it)) } })
                }.toString()))
            }
        },
    )

    private fun childToJson(child: RedditChild): JsonObject = buildJsonObject {
        when (child) {
            is RedditChild.Post -> {
                put("kind", "post")
                put("id", child.id)
                child.subreddit?.let { put("subreddit", it) }
                child.author?.let { put("author", it) }
                child.title?.let { put("title", it) }
                child.url?.let { put("url", it) }
                child.permalink?.let { put("permalink", it) }
                child.selftext?.takeIf { it.isNotBlank() }?.let { put("selftext", it.take(2_000)) }
                child.score?.let { put("score", it) }
                child.numComments?.let { put("num_comments", it) }
                put("created_at_ms", child.createdAtMs)
                if (child.over18) put("over_18", true)
                if (child.isSelf) put("is_self", true)
            }
            is RedditChild.Comment -> {
                put("kind", "comment")
                put("id", child.id)
                child.author?.let { put("author", it) }
                child.body?.let { put("body", it.take(2_000)) }
                child.score?.let { put("score", it) }
                put("created_at_ms", child.createdAtMs)
                child.permalink?.let { put("permalink", it) }
                child.parentId?.let { put("parent_id", it) }
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

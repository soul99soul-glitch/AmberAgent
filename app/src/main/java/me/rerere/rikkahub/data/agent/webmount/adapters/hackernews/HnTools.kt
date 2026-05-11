package me.rerere.rikkahub.data.agent.webmount.adapters.hackernews

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.tools.boolean
import me.rerere.rikkahub.data.agent.tools.long
import me.rerere.rikkahub.data.agent.tools.requiredString
import me.rerere.rikkahub.data.agent.tools.string
import me.rerere.rikkahub.data.agent.webmount.core.WebMountToolHooks

/**
 * HackerNews tool surface. All anonymous, all read-only.
 *
 *  - `hn_top(feed?, limit?, hydrate?)` — top/new/best/ask/show/job feed
 *  - `hn_item_read(id, with_kids_text?)` — a single item with optional comment expansion
 *  - `hn_user_read(username)` — public profile
 *  - `hn_search(query, tags?, limit?)` — Algolia full-text
 */
class HnTools(private val client: HnClient) {

    /** Cheap reachability probe used by [HnAdapter.probe]. */
    suspend fun smokeProbe(): Boolean = client.storyIds(HnFeed.TOP, 1).isNotEmpty()

    fun buildTools(hooks: WebMountToolHooks): List<Tool> = listOf(
        topTool(hooks),
        itemReadTool(hooks),
        userReadTool(hooks),
        searchTool(hooks),
    )

    private fun topTool(hooks: WebMountToolHooks) = Tool(
        name = "hn_top",
        description = """
            Read a HackerNews story feed. `feed` defaults to "top"; also accepts new / best / ask / show / job.
            `limit` (1-50, default 20) caps results. `hydrate=true` (default) fetches each story's metadata
            in parallel and returns title/url/score/comments; `hydrate=false` returns just the id list.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("feed", stringProp("top | new | best | ask | show | job"))
                    put("limit", integerProp("1-50, default 20"))
                    put("hydrate", booleanProp("Fetch item details for each id (default true)"))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("hn_top", "HN Top", input) {
                val feed = HnFeed.fromWire(input.string("feed"))
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 50L).toInt()
                val hydrate = input.boolean("hydrate") ?: true
                val ids = client.storyIds(feed, limit)
                val itemsArr = if (hydrate) coroutineScope {
                    ids.map { id -> async { client.item(id) } }.awaitAll().filterNotNull().map { it.toJson() }
                } else ids.map { id -> buildJsonObject { put("id", id) } }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("feed", feed.endpoint)
                    put("count", itemsArr.size)
                    put("items", buildJsonArray { itemsArr.forEach { add(it) } })
                }.toString()))
            }
        },
    )

    private fun itemReadTool(hooks: WebMountToolHooks) = Tool(
        name = "hn_item_read",
        description = """
            Read a single HackerNews item by id (story / comment / job / poll). With `with_kids_text=true`,
            also hydrates direct child comments' text (one level only). Use this to follow a thread the
            agent found via hn_top or hn_search.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", integerProp("HN item id"))
                    put("with_kids_text", booleanProp("Hydrate first-level kid comments (default false)"))
                    put("max_kids", integerProp("Cap on hydrated kids when with_kids_text=true. Default 20."))
                },
                required = listOf("id"),
            )
        },
        execute = { input ->
            hooks.track("hn_item_read", "HN Item", input) {
                val id = input.requiredString("id").toLong()
                val withKidsText = input.boolean("with_kids_text") ?: false
                val maxKids = (input.long("max_kids") ?: 20L).coerceIn(0L, 100L).toInt()
                val item = client.item(id) ?: error("HN item not found: $id")
                val payload = buildJsonObject {
                    put("item", item.toJson())
                    if (withKidsText && item.kids.isNotEmpty()) {
                        val kidsItems = coroutineScope {
                            item.kids.take(maxKids).map { kidId -> async { client.item(kidId) } }
                                .awaitAll().filterNotNull()
                        }
                        put("kids", buildJsonArray { kidsItems.forEach { add(it.toJson()) } })
                        put("kids_truncated", item.kids.size > maxKids)
                    }
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    private fun userReadTool(hooks: WebMountToolHooks) = Tool(
        name = "hn_user_read",
        description = "Read a HackerNews user's public profile: karma, about, recent submissions.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { put("username", stringProp("HN username, e.g. 'pg'")) },
                required = listOf("username"),
            )
        },
        execute = { input ->
            hooks.track("hn_user_read", "HN User", input) {
                val username = input.requiredString("username")
                val user = client.user(username) ?: error("HN user not found: $username")
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("user", user)
                }.toString()))
            }
        },
    )

    private fun searchTool(hooks: WebMountToolHooks) = Tool(
        name = "hn_search",
        description = """
            Full-text search HackerNews via Algolia. `tags` is the Algolia tag filter
            (e.g. "story", "comment", "ask_hn", "show_hn", or "author_<user>"). Returns up to 50 hits.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringProp("Search keywords"))
                    put("tags", stringProp("Algolia tag filter (optional)"))
                    put("limit", integerProp("1-50, default 20"))
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            hooks.track("hn_search", "HN Search", input) {
                val query = input.requiredString("query")
                val tags = input.string("tags")
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 50L).toInt()
                val result = client.search(query, tags, limit)
                val payload = buildJsonObject {
                    put("query", result.query)
                    put("nb_hits", result.nbHits)
                    put("count", result.hits.size)
                    put("hits", buildJsonArray {
                        result.hits.forEach { hit ->
                            add(buildJsonObject {
                                put("id", hit.objectId)
                                put("title", hit.title)
                                put("author", hit.author)
                                put("url", hit.url)
                                put("points", hit.points)
                                put("num_comments", hit.numComments)
                                put("created_at_ms", hit.createdAtMs)
                            })
                        }
                    })
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    private fun HnItem.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        title?.let { put("title", it) }
        author?.let { put("author", it) }
        url?.let { put("url", it) }
        text?.let { put("text", it) }
        score?.let { put("score", it) }
        descendants?.let { put("descendants", it) }
        put("kids_count", kids.size)
        put("created_at_ms", createdAtMs)
        parent?.let { put("parent", it) }
        if (deleted) put("deleted", true)
        if (dead) put("dead", true)
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string"); put("description", description)
    }
    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer"); put("description", description)
    }
    private fun booleanProp(description: String) = buildJsonObject {
        put("type", "boolean"); put("description", description)
    }
}

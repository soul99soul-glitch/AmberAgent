package me.rerere.rikkahub.data.agent.webmount.adapters.bilibili

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.tools.long
import me.rerere.rikkahub.data.agent.tools.requiredString
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCookieBundle
import me.rerere.rikkahub.data.agent.webmount.core.WebMountToolHooks

class BilibiliTools(private val client: BilibiliClient) {

    suspend fun probe(cookies: WebMountCookieBundle): Boolean = client.probe(cookies)

    fun buildTools(hooks: WebMountToolHooks): List<Tool> = listOf(
        popularTool(hooks),
        videoInfoTool(hooks),
        searchTool(hooks),
        historyTool(hooks),
    )

    private fun popularTool(hooks: WebMountToolHooks) = Tool(
        name = "bilibili_hot_videos",
        description = """
            B站热门视频 (web-interface/popular). Anonymous-friendly; cookies improve geo-availability.
            Returns up to `limit` videos with bvid / title / owner / view count.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", integerProp("1-50, default 20"))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("bilibili_hot_videos", "B站热门", input) {
                val cookies = hooks.cookies()
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 50L).toInt()
                val videos = client.popular(cookies, limit)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("count", videos.size)
                    put("videos", buildJsonArray { videos.forEach { add(summaryJson(it)) } })
                }.toString()))
            }
        },
    )

    private fun videoInfoTool(hooks: WebMountToolHooks) = Tool(
        name = "bilibili_video_info",
        description = "Read full metadata for one B站 video by `bvid`: title, description, owner, stats.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("bvid", stringProp("Video bvid (e.g. BV1xxx...)"))
                },
                required = listOf("bvid"),
            )
        },
        execute = { input ->
            hooks.track("bilibili_video_info", "B站视频详情", input) {
                val cookies = hooks.cookies()
                val bvid = input.requiredString("bvid")
                val video = client.videoInfo(cookies, bvid) ?: error("Video not found: $bvid")
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("bvid", video.bvid)
                    put("aid", video.aid)
                    put("title", video.title)
                    video.desc?.let { put("desc", it.take(2_000)) }
                    video.ownerName?.let { put("owner_name", it) }
                    video.ownerMid?.let { put("owner_mid", it) }
                    put("duration_sec", video.durationSec)
                    put("view", video.view)
                    put("danmaku", video.danmaku)
                    put("reply", video.reply)
                    put("like", video.like)
                    put("coin", video.coin)
                    put("favorite", video.favorite)
                    put("share", video.share)
                    put("pubdate_ms", video.pubdateMs)
                    video.pic?.let { put("pic", it) }
                }.toString()))
            }
        },
    )

    private fun searchTool(hooks: WebMountToolHooks) = Tool(
        name = "bilibili_search",
        description = "Search B站 videos by keyword. Returns up to `limit` videos on page `page`. Anonymous-friendly.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringProp("Search keywords"))
                    put("page", integerProp("Page number, default 1"))
                    put("limit", integerProp("1-50, default 20"))
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            hooks.track("bilibili_search", "B站搜索", input) {
                val cookies = hooks.cookies()
                val query = input.requiredString("query")
                val page = (input.long("page") ?: 1L).coerceAtLeast(1L).toInt()
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 50L).toInt()
                val videos = client.search(cookies, query, page, limit)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("query", query)
                    put("page", page)
                    put("count", videos.size)
                    put("videos", buildJsonArray { videos.forEach { add(summaryJson(it)) } })
                }.toString()))
            }
        },
    )

    private fun historyTool(hooks: WebMountToolHooks) = Tool(
        name = "bilibili_user_history",
        description = "Read the user's view history. Requires login (SESSDATA cookie). Returns up to `limit` items, newest first.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", integerProp("1-50, default 20"))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            hooks.track("bilibili_user_history", "B站历史", input) {
                val cookies = hooks.requireCookies("SESSDATA")
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 50L).toInt()
                val items = client.history(cookies, limit)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("count", items.size)
                    put("items", buildJsonArray {
                        items.forEach { item ->
                            add(buildJsonObject {
                                item.bvid?.let { put("bvid", it) }
                                item.aid?.let { put("aid", it) }
                                put("title", item.title)
                                item.authorName?.let { put("author_name", it) }
                                item.cover?.let { put("cover", it) }
                                put("view_at_ms", item.viewAtMs)
                                item.progressSec?.let { put("progress_sec", it) }
                            })
                        }
                    })
                }.toString()))
            }
        },
    )

    private fun summaryJson(v: BilibiliVideoSummary): JsonObject = buildJsonObject {
        put("bvid", v.bvid)
        put("aid", v.aid)
        put("title", v.title)
        v.ownerName?.let { put("owner_name", it) }
        v.ownerMid?.let { put("owner_mid", it) }
        v.pic?.let { put("pic", it) }
        put("duration_sec", v.durationSec)
        put("view", v.viewCount)
        put("danmaku", v.danmakuCount)
        put("pubdate_ms", v.pubdateMs)
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string"); put("description", description)
    }
    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer"); put("description", description)
    }
}

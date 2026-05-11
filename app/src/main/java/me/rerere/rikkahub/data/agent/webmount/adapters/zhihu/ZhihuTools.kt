package me.rerere.rikkahub.data.agent.webmount.adapters.zhihu

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.tools.long
import me.rerere.rikkahub.data.agent.tools.requiredString
import me.rerere.rikkahub.data.agent.webmount.cookie.EndpointSpec
import me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCookieBundle
import me.rerere.rikkahub.data.agent.webmount.core.WebMountToolHooks

class ZhihuTools(private val client: ZhihuClient) {

    suspend fun probe(cookies: WebMountCookieBundle): Boolean = client.probe(cookies)

    fun buildTools(
        hooks: WebMountToolHooks,
        endpoints: List<EndpointSpec>,
        cookieProvider: WebMountCookieProvider,
    ): List<Tool> = listOf(
        feedTool(hooks, endpoints, cookieProvider),
        questionReadTool(hooks, endpoints, cookieProvider),
        answerReadTool(hooks, endpoints, cookieProvider),
        searchTool(hooks, endpoints, cookieProvider),
    )

    private fun feedTool(hooks: WebMountToolHooks, endpoints: List<EndpointSpec>, cp: WebMountCookieProvider) = Tool(
        name = "zhihu_feed",
        description = """
            知乎首页推荐 feed. Requires login (z_c0 cookie). Mix of questions / answers / articles.
            Some endpoints may return 403 due to 知乎's signing requirements — surfaced as a clear error
            so the agent can re-login and retry.
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
            hooks.track("zhihu_feed", "知乎首页", input) {
                val cookies = cp.getCookies(endpoints)
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 50L).toInt()
                val items = client.feed(cookies, limit)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("count", items.size)
                    put("items", buildJsonArray { items.forEach { add(feedItemJson(it)) } })
                }.toString()))
            }
        },
    )

    private fun questionReadTool(hooks: WebMountToolHooks, endpoints: List<EndpointSpec>, cp: WebMountCookieProvider) = Tool(
        name = "zhihu_question_read",
        description = """
            Read a 知乎 question with its top answers. `question_id` is the numeric id (the long
            number in the URL `zhihu.com/question/<id>`). Returns question metadata + up to
            `answer_limit` top answers.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("question_id", stringProp("Numeric question id"))
                    put("answer_limit", integerProp("1-20, default 5"))
                },
                required = listOf("question_id"),
            )
        },
        execute = { input ->
            hooks.track("zhihu_question_read", "知乎问题", input) {
                val cookies = cp.getCookies(endpoints)
                val questionId = input.requiredString("question_id")
                val answerLimit = (input.long("answer_limit") ?: 5L).coerceIn(1L, 20L).toInt()
                val question = client.question(cookies, questionId)
                    ?: error("Question not found: $questionId")
                val answers = client.answersFor(cookies, questionId, answerLimit, offset = 0)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("question", buildJsonObject {
                        put("id", question.id)
                        put("title", question.title)
                        question.detail?.takeIf { it.isNotBlank() }?.let { put("detail", it.take(4_000)) }
                        question.answerCount?.let { put("answer_count", it) }
                        question.followerCount?.let { put("follower_count", it) }
                        question.visitCount?.let { put("visit_count", it) }
                        put("created_ms", question.createdMs)
                        put("updated_ms", question.updatedMs)
                    })
                    put("answers", buildJsonArray { answers.forEach { add(answerJson(it, contentMax = 8_000)) } })
                }.toString()))
            }
        },
    )

    private fun answerReadTool(hooks: WebMountToolHooks, endpoints: List<EndpointSpec>, cp: WebMountCookieProvider) = Tool(
        name = "zhihu_answer_read",
        description = "Read a single 知乎 answer by id. Returns full content (HTML), author, stats.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("answer_id", stringProp("Numeric answer id"))
                    put("max_chars", integerProp("Content truncation cap, default 30000, hard cap 80000"))
                },
                required = listOf("answer_id"),
            )
        },
        execute = { input ->
            hooks.track("zhihu_answer_read", "知乎答案", input) {
                val cookies = cp.getCookies(endpoints)
                val answerId = input.requiredString("answer_id")
                val maxChars = (input.long("max_chars") ?: 30_000L).coerceIn(1L, 80_000L).toInt()
                val answer = client.answer(cookies, answerId)
                    ?: error("Answer not found: $answerId")
                listOf(UIMessagePart.Text(answerJson(answer, contentMax = maxChars).toString()))
            }
        },
    )

    private fun searchTool(hooks: WebMountToolHooks, endpoints: List<EndpointSpec>, cp: WebMountCookieProvider) = Tool(
        name = "zhihu_search",
        description = """
            Search 知乎 (general type — mixes questions, answers, articles, users). Returns up to
            `limit` hits with type / id / title / excerpt / url.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringProp("Search keywords"))
                    put("limit", integerProp("1-30, default 15"))
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            hooks.track("zhihu_search", "知乎搜索", input) {
                val cookies = cp.getCookies(endpoints)
                val query = input.requiredString("query")
                val limit = (input.long("limit") ?: 15L).coerceIn(1L, 30L).toInt()
                val hits = client.search(cookies, query, limit)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("query", query)
                    put("count", hits.size)
                    put("results", buildJsonArray {
                        hits.forEach { hit ->
                            add(buildJsonObject {
                                put("type", hit.type)
                                put("id", hit.id)
                                put("title", hit.title)
                                hit.excerpt?.let { put("excerpt", it.take(400)) }
                                hit.url?.let { put("url", it) }
                            })
                        }
                    })
                }.toString()))
            }
        },
    )

    private fun feedItemJson(item: ZhihuFeedItem): JsonObject = buildJsonObject {
        put("type", item.type)
        put("id", item.id)
        put("title", item.title)
        item.authorName?.let { put("author", it) }
        item.excerpt?.let { put("excerpt", it.take(500)) }
        item.voteupCount?.let { put("voteup", it) }
        item.commentCount?.let { put("comments", it) }
        item.url?.let { put("url", it) }
    }

    private fun answerJson(answer: ZhihuAnswer, contentMax: Int): JsonObject = buildJsonObject {
        put("id", answer.id)
        answer.questionId?.let { put("question_id", it) }
        answer.questionTitle?.let { put("question_title", it) }
        answer.authorName?.let { put("author", it) }
        answer.content?.let {
            val truncated = it.length > contentMax
            put("content", if (truncated) it.take(contentMax) else it)
            put("content_chars", it.length)
            if (truncated) put("content_truncated", true)
        }
        answer.voteupCount?.let { put("voteup", it) }
        answer.commentCount?.let { put("comments", it) }
        put("created_ms", answer.createdMs)
        put("updated_ms", answer.updatedMs)
        answer.url?.let { put("url", it) }
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string"); put("description", description)
    }
    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer"); put("description", description)
    }
}

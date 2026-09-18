package app.amber.core.jev

import app.amber.feature.tools.SemanticToolCandidate
import app.amber.feature.tools.SemanticToolRankResult
import app.amber.feature.tools.ToolSemanticSearch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具发现语义搜索（TOOL_DISCOVERY 用途）。
 *
 * 候选已由 ToolSearchIndex 按 profile/scope 硬过滤后给出；逐候选 Noul 判
 * 相关性，active 且未过期才返回应用结果，其余形态返回 null 走词面回退。
 * 每个分块独立缓存锚；runKey 由 forRun 捕获，用于单轮预算隔离。
 */
class JevToolSemanticSearch(private val runtime: JevRuntime) : ToolSemanticSearch {

    /** 捕获 run 身份，供单轮（run）出站预算记账。 */
    fun forRun(runKey: String?): ToolSemanticSearch = ToolSemanticSearch { query, category, limit, candidates ->
        rerankInternal(query, category, limit, candidates, runKey)
    }

    override suspend fun rerank(
        query: String,
        category: String?,
        limit: Int,
        candidates: List<SemanticToolCandidate>,
    ): SemanticToolRankResult? = rerankInternal(query, category, limit, candidates, runKey = null)

    private suspend fun rerankInternal(
        query: String,
        category: String?,
        limit: Int,
        candidates: List<SemanticToolCandidate>,
        runKey: String?,
    ): SemanticToolRankResult? {
        if (candidates.isEmpty() || query.isBlank()) return null
        val ranked = ArrayList<Pair<String, Double>>(candidates.size)
        candidates.chunked(JevLimits.MAX_QUESTIONS_PER_REQUEST).forEachIndexed { chunkIndex, chunk ->
            val state: kotlinx.serialization.json.JsonElement = buildJsonObject {
                put("task", "The assistant needs a tool for the user's request.")
                put("query", query.take(500))
                category?.let { put("category_filter", it) }
                put("tools", buildJsonArray {
                    chunk.forEach { candidate ->
                        add(buildJsonObject {
                            put("name", candidate.name)
                            put("category", candidate.category)
                            put("description", candidate.description)
                        })
                    }
                })
            }
            val questions = chunk.associate { candidate ->
                candidate.name to JevQuestion.Noul(
                    instructions = "Is the tool named ${candidate.name} in state.tools relevant to state.query?",
                )
            }
            val anchor = buildString {
                append("tools|")
                append(query.hashCode())
                append('|')
                append(category ?: "-")
                append('|')
                append(chunk.joinToString(",") { it.name }.hashCode())
                append('|')
                append(chunkIndex)
            }
            val outcome = runtime.decide(
                purpose = JevPurpose.TOOL_DISCOVERY,
                runKey = runKey,
                state = state,
                questions = questions,
                requiredScopes = setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT),
                cacheAnchor = anchor,
            ) ?: return null
            val evaluated = outcome.evaluated
            if (!outcome.applicable || evaluated == null) return null
            chunk.forEach { candidate ->
                val answer = evaluated.answers[candidate.name] as? JevAnswer.Noul ?: return@forEach
                if (answer.probability >= RELEVANCE_THRESHOLD) {
                    ranked += candidate.name to answer.probability
                }
            }
        }
        if (ranked.isEmpty()) return null
        val selected = ranked.sortedByDescending { it.second }.take(limit.coerceAtLeast(1)).map { it.first }
        return SemanticToolRankResult(rankedNames = selected, applied = true)
    }

    companion object {
        const val RELEVANCE_THRESHOLD = 0.5
    }
}

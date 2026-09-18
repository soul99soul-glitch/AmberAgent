package app.amber.core.jev

import app.amber.core.memory.model.MemoryRecord
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 记忆召回语义重排（MEMORY_RECALL 用途）。
 *
 * 候选与任务文本组装 state，逐条 Noul 判相关性；只有 active 且未过期才
 * 应用排序，其余形态（off/shadow/失败/超时/低置信）一律返回未应用。
 * 缓存锚 = 任务文本 + 候选集内容摘要：同一用户轮的工具循环内复用结果，
 * steer 带来新用户消息即自然失效。
 */
class JevMemoryReranker(private val runtime: JevRuntime) : MemorySemanticReranker {

    override suspend fun rerank(
        records: List<MemoryRecord>,
        taskText: String,
        runKey: String?,
    ): MemorySemanticResult {
        if (records.isEmpty() || taskText.isBlank()) return MemorySemanticResult.NOT_APPLIED
        val candidates = records.take(MAX_CANDIDATES)
        val anchor = buildString {
            append("mem|")
            append(taskText.hashCode())
            append('|')
            append(candidates.joinToString(",") { "${it.id}:${it.content.hashCode()}:${it.updatedAt}" }.hashCode())
        }
        val ranked = ArrayList<Pair<Int, Double>>(candidates.size)
        candidates.chunked(JevLimits.MAX_QUESTIONS_PER_REQUEST).forEachIndexed { chunkIndex, chunk ->
            val state = buildJsonObject {
                put("task", "The user's current task, in their own words.")
                put("task_text", taskText.take(2_000))
                put("note", "Rank the memories by relevance to the task. Judge each memory independently.")
                put("memories", buildJsonArray {
                    chunk.forEach { record ->
                        add(buildJsonObject {
                            put("id", record.id.toString())
                            put("content", record.content.take(CONTENT_SNIPPET_CHARS))
                            put("kind", record.kind.name.lowercase())
                            put("scope", record.scope.name.lowercase())
                        })
                    }
                })
            }
            val questions = chunk.associate { record ->
                record.id.toString() to JevQuestion.Noul(
                    instructions = "Is the memory with id ${record.id} in state.memories relevant to state.task_text?",
                )
            }
            val outcome = runtime.decide(
                purpose = JevPurpose.MEMORY_RECALL,
                runKey = runKey,
                state = state,
                questions = questions,
                requiredScopes = setOf(JevDataScope.PERSONAL_MEMORY, JevDataScope.TASK_TEXT),
                cacheAnchor = "$anchor|$chunkIndex",
            ) ?: return MemorySemanticResult.NOT_APPLIED
            val evaluated = outcome.evaluated
            if (!outcome.applicable || evaluated == null) return MemorySemanticResult.NOT_APPLIED
            chunk.forEach { record ->
                val answer = evaluated.answers[record.id.toString()] as? JevAnswer.Noul ?: return@forEach
                if (answer.probability >= RELEVANCE_THRESHOLD) {
                    ranked += record.id to answer.probability
                }
            }
        }
        if (ranked.isEmpty()) return MemorySemanticResult.NOT_APPLIED
        return MemorySemanticResult(
            rankedIds = ranked.sortedByDescending { it.second }.map { it.first },
            applied = true,
        )
    }

    companion object {
        /** 与判断服务候选上限一致；超出部分沿用词面排序（记录在指标覆盖里）。 */
        const val MAX_CANDIDATES = 64
        const val CONTENT_SNIPPET_CHARS = 400
        const val RELEVANCE_THRESHOLD = 0.5
    }
}

interface MemorySemanticReranker {
    suspend fun rerank(records: List<MemoryRecord>, taskText: String, runKey: String?): MemorySemanticResult
}

data class MemorySemanticResult(val rankedIds: List<Int>?, val applied: Boolean) {
    companion object {
        val NOT_APPLIED = MemorySemanticResult(rankedIds = null, applied = false)
    }
}

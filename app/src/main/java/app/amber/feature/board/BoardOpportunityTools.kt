package app.amber.feature.board

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.agent.data.db.dao.DocSubscriptionDAO
import app.amber.agent.data.db.dao.FeishuDocDependencyDAO
import app.amber.agent.data.db.entity.FeishuDocDependencyEntity
import app.amber.agent.data.db.entity.OpportunityEntity
import app.amber.agent.data.db.entity.ReferenceAnchorConfirmationMode
import app.amber.agent.data.db.entity.ReferenceAnchorEntity
import app.amber.agent.data.db.entity.ReferenceAnchorStatus
import app.amber.agent.data.db.entity.stableReferenceAnchorId
import app.amber.feature.office.radar.DocRadar
import app.amber.feature.tools.textJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

class BoardOpportunityTools(
    private val meetingPrepScanner: MeetingPrepOpportunityScanner,
    private val opportunityRepository: OpportunityRepository,
    private val boardRepository: BoardRepository,
    private val boardTaskRepository: BoardTaskRepository,
    private val docRadar: DocRadar,
    private val subscriptionDao: DocSubscriptionDAO,
    private val dependencyDao: FeishuDocDependencyDAO,
    private val anchorRepository: ReferenceAnchorRepository,
) {
    fun getTools(): List<Tool> = listOf(
        scanMeetingPrepTool,
        listOpportunitiesTool,
        monitorDocDependencyTool,
        taskRecordTool,
    )

    private val scanMeetingPrepTool = Tool(
        name = "board_scan_meeting_prep",
        description = "Scan upcoming calendar meetings for Feishu/Lark document links and create meeting_prep opportunities. It does not dispatch tasks or write documents.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("days", integerProp("Lookahead window in days: 3, 7, or 14. Defaults to 7."))
                }
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val days = input.int("days")?.coerceIn(3, 14) ?: 7
            val created = meetingPrepScanner.scanDays(days)
            val opportunities = opportunityRepository.getSuggested(limit = 12)
            textJson {
                put("created_or_updated", created)
                put("days", days)
                put("board_date", boardRepository.todayBoardDate())
                put("opportunities", opportunities.toJson())
                put("note", "Opportunities are suggestions only. Dispatch happens after the user chooses 派发.")
            }
        },
    )

    private val listOpportunitiesTool = Tool(
        name = "board_list_opportunities",
        description = "List current suggested Amber Board opportunities without dispatching them.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", integerProp("Maximum opportunities to return. Defaults to 20."))
                }
            )
        },
        execute = { input ->
            val opportunities = opportunityRepository.getSuggested(limit = input.int("limit")?.coerceIn(1, 50) ?: 20)
            textJson {
                put("opportunities", opportunities.toJson())
                put("count", opportunities.size)
            }
        },
    )

    private val taskRecordTool = Tool(
        name = "board_task_record",
        description = "Record progress or lifecycle changes for an existing BoardTask. This only updates Amber's task ledger; it must not execute external actions.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", stringProp("BoardTask id to update."))
                    put("action", stringProp("One of progress, waiting_user, blocked, done, dismissed, cancelled."))
                    put("message", stringProp("Short event message to show in the task timeline."))
                },
                required = listOf("task_id", "action"),
            )
        },
        execute = { input ->
            val taskId = input.requiredString("task_id")
            val action = input.requiredString("action")
            val message = input.string("message")
            val task = when (action) {
                "progress" -> boardTaskRepository.recordProgress(
                    taskId = taskId,
                    message = message.ifBlank { "任务有新进展" },
                )

                "waiting_user" -> boardTaskRepository.markWaitingUser(
                    taskId = taskId,
                    message = message.ifBlank { "等待用户确认" },
                )

                "blocked" -> boardTaskRepository.markBlocked(
                    taskId = taskId,
                    message = message.ifBlank { "任务遇到阻碍" },
                )

                "done" -> boardTaskRepository.markDone(taskId)
                "dismissed" -> boardTaskRepository.markDismissed(taskId)
                "cancelled" -> boardTaskRepository.cancel(taskId)
                else -> error("Unsupported action: $action")
            } ?: error("BoardTask not found: $taskId")
            textJson {
                put("task_id", task.id)
                put("state", task.state)
                put("chip_text", task.chipText)
                put("title", task.title)
                put("updated_at", task.updatedAt)
            }
        },
    )

    private val monitorDocDependencyTool = Tool(
        name = "board_monitor_doc_dependency",
        description = "Create a read-only dependency monitor between user's document and an upstream Feishu/Lark document, then extract conservative reference anchors for future dependency_stale opportunities.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("my_doc_url", stringProp("User-owned Feishu/Lark document URL to keep accurate."))
                    put("upstream_doc_url", stringProp("Upstream Feishu/Lark document URL used as source material."))
                    put("my_doc_title", stringProp("Optional title for the user's document."))
                    put("upstream_doc_title", stringProp("Optional title for the upstream document."))
                    put("relation_note", stringProp("Optional note describing how the upstream document supports the user's document."))
                },
                required = listOf("my_doc_url", "upstream_doc_url"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val myDocUrl = input.requiredString("my_doc_url")
            val upstreamDocUrl = input.requiredString("upstream_doc_url")
            val upstreamTitle = input.string("upstream_doc_title").ifBlank { "上游文档" }
            val myTitle = input.string("my_doc_title").ifBlank { "我的文档" }
            val relationNote = input.string("relation_note")

            val subscribeMessage = docRadar.subscribe(upstreamDocUrl, upstreamTitle)
            val subscription = subscriptionDao.getByUrl(upstreamDocUrl)
            val now = System.currentTimeMillis()
            val dependencyId = stableDependencyId(myDocUrl, upstreamDocUrl)
            dependencyDao.insert(
                FeishuDocDependencyEntity(
                    id = dependencyId,
                    upstreamUrl = upstreamDocUrl,
                    downstreamUrl = myDocUrl,
                    upstreamLabel = upstreamTitle,
                    downstreamLabel = myTitle,
                    relationNote = relationNote,
                    createdAt = now,
                    updatedAt = now,
                )
            )

            val anchorResult = buildReferenceAnchors(
                dependencyId = dependencyId,
                myDocUrl = myDocUrl,
                upstreamDocUrl = upstreamDocUrl,
            )
            anchorResult.anchors.forEach { anchorRepository.upsert(it) }

            textJson {
                put("dependency_id", dependencyId)
                put("subscription_id", subscription?.id.orEmpty())
                put("subscription_note", subscribeMessage ?: "上游文档已订阅并建立基线")
                put("created_anchors", anchorResult.anchors.size)
                put("auto_confirmed_anchors", anchorResult.anchors.count { it.status == ReferenceAnchorStatus.CONFIRMED })
                put("proposed_anchors", anchorResult.anchors.count { it.status == ReferenceAnchorStatus.PROPOSED })
                put("anchors", buildJsonArray {
                    anchorResult.anchors.take(12).forEach { anchor ->
                        add(buildJsonObject {
                            put("id", anchor.id)
                            put("status", anchor.status)
                            put("claim", anchor.myClaimText)
                            put("baseline_value", anchor.baselineValue)
                            put("upstream_hint", anchor.upstreamHint)
                            put("match_confidence", anchor.matchConfidence)
                        })
                    }
                })
                put("notes", buildJsonArray {
                    if (anchorResult.myDocReadFailed) add("我的文档正文读取失败，已只建立依赖关系")
                    if (anchorResult.upstreamReadFailed) add("上游文档正文读取失败，已只建立依赖关系")
                    add("Only high-confidence numeric anchors are auto-confirmed. Statements remain proposed until user confirmation.")
                })
            }
        },
    )

    private suspend fun buildReferenceAnchors(
        dependencyId: String,
        myDocUrl: String,
        upstreamDocUrl: String,
    ): AnchorBuildResult {
        val myText = docRadar.fetchPlainTextForAnalysis(myDocUrl)
        val upstreamText = docRadar.fetchPlainTextForAnalysis(upstreamDocUrl)
        if (myText.isNullOrBlank() || upstreamText.isNullOrBlank()) {
            return AnchorBuildResult(
                anchors = emptyList(),
                myDocReadFailed = myText.isNullOrBlank(),
                upstreamReadFailed = upstreamText.isNullOrBlank(),
            )
        }
        val numericAnchors = extractNumericClaims(myText)
            .take(MAX_NUMERIC_ANCHORS)
            .map { claim ->
                val match = findUpstreamMatch(claim, upstreamText)
                val confidence = match?.confidence ?: 0.55f
                val autoConfirmed = confidence >= AUTO_CONFIRM_THRESHOLD
                claim.toAnchor(
                    dependencyId = dependencyId,
                    myDocUrl = myDocUrl,
                    upstreamDocUrl = upstreamDocUrl,
                    upstreamHint = match?.snippet.orEmpty(),
                    confidence = confidence,
                    status = if (autoConfirmed) ReferenceAnchorStatus.CONFIRMED else ReferenceAnchorStatus.PROPOSED,
                    confirmationMode = if (autoConfirmed) {
                        ReferenceAnchorConfirmationMode.AUTO_HIGH_CONFIDENCE
                    } else {
                        ReferenceAnchorConfirmationMode.MANUAL
                    },
                )
            }
        val statementAnchors = extractStrongStatements(myText)
            .take(MAX_STATEMENT_ANCHORS)
            .map { statement ->
                val claim = CandidateClaim(
                    value = statement.take(80),
                    snippet = statement,
                    keywords = keywordSet(statement),
                )
                claim.toAnchor(
                    dependencyId = dependencyId,
                    myDocUrl = myDocUrl,
                    upstreamDocUrl = upstreamDocUrl,
                    upstreamHint = "",
                    confidence = 0.45f,
                    status = ReferenceAnchorStatus.PROPOSED,
                    confirmationMode = ReferenceAnchorConfirmationMode.MANUAL,
                )
            }
        return AnchorBuildResult(
            anchors = (numericAnchors + statementAnchors).distinctBy { it.dedupeKey },
            myDocReadFailed = false,
            upstreamReadFailed = false,
        )
    }

    private fun CandidateClaim.toAnchor(
        dependencyId: String,
        myDocUrl: String,
        upstreamDocUrl: String,
        upstreamHint: String,
        confidence: Float,
        status: String,
        confirmationMode: String,
    ): ReferenceAnchorEntity {
        val dedupeKey = "$dependencyId|${value.take(80)}|${snippet.take(120)}"
        val now = System.currentTimeMillis()
        return ReferenceAnchorEntity(
            id = stableReferenceAnchorId(dedupeKey),
            dedupeKey = dedupeKey,
            dependencyId = dependencyId,
            myDocRef = myDocUrl,
            myClaimText = snippet.take(500),
            upstreamDocRef = upstreamDocUrl,
            upstreamHint = upstreamHint.take(500),
            baselineValue = value.take(120),
            evidenceJson = buildJsonObject {
                put("claim_keywords", buildJsonArray { keywords.take(8).forEach { add(it) } })
                put("auto_confirm_rule", "numeric_exact_match_with_keyword_overlap")
            }.toString(),
            scoreJson = scoreJson(
                "value_match" to if (confidence >= 0.8f) 45 else 10,
                "keyword_overlap" to if (confidence >= AUTO_CONFIRM_THRESHOLD) 35 else 10,
                "source_snippet" to if (upstreamHint.isNotBlank()) 12 else 0,
            ),
            matchConfidence = confidence.coerceIn(0f, 1f),
            status = status,
            confirmationMode = confirmationMode,
            lastCheckedAt = now,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun extractNumericClaims(text: String): List<CandidateClaim> =
        NUMBER_REGEX.findAll(text)
            .map { match ->
                val snippet = sentenceAround(text, match.range.first, match.range.last)
                CandidateClaim(
                    value = match.value,
                    snippet = snippet,
                    keywords = keywordSet(snippet),
                )
            }
            .filter { it.snippet.length >= 8 }
            .distinctBy { "${it.value}|${it.snippet.take(80)}" }
            .toList()

    private fun extractStrongStatements(text: String): List<String> =
        text.split(STATEMENT_SPLIT_REGEX)
            .asSequence()
            .map { it.trim() }
            .filter { sentence -> sentence.length in 16..220 && STRONG_STATEMENT_HINTS.any { it in sentence } }
            .distinct()
            .toList()

    private fun findUpstreamMatch(claim: CandidateClaim, upstreamText: String): UpstreamMatch? {
        val occurrences = Regex(Regex.escape(claim.value)).findAll(upstreamText)
        var best: UpstreamMatch? = null
        for (occurrence in occurrences) {
            val snippet = sentenceAround(upstreamText, occurrence.range.first, occurrence.range.last)
            val overlap = claim.keywords.intersect(keywordSet(snippet)).size
            val confidence = when {
                overlap >= 2 -> 0.95f
                overlap == 1 -> 0.92f
                else -> 0.82f
            }
            if (best == null || confidence > best.confidence) {
                best = UpstreamMatch(snippet = snippet, confidence = confidence)
            }
        }
        return best
    }

    private fun sentenceAround(text: String, start: Int, end: Int): String {
        val left = text.lastIndexOfAny(SENTENCE_BOUNDARIES, start).let { if (it < 0) 0 else it + 1 }
        val right = text.indexOfAny(SENTENCE_BOUNDARIES, end).let { if (it < 0) text.length else it }
        return text.substring(left, right).trim().take(500)
    }

    private fun keywordSet(text: String): Set<String> =
        WORD_REGEX.findAll(text)
            .map { it.value.trim().lowercase() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .take(16)
            .toSet()

    private fun List<OpportunityEntity>.toJson() = buildJsonArray {
        forEach { opportunity ->
            add(buildJsonObject {
                put("id", opportunity.id)
                put("type", opportunity.opportunityType)
                put("title", opportunity.title)
                put("summary", opportunity.summary)
                put("source_type", opportunity.sourceType)
                put("source_ref", opportunity.sourceRef)
                put("confidence", opportunity.confidence)
                put("due_at", opportunity.dueAt)
                put("trigger_at", opportunity.triggerAt)
                put("expires_at", opportunity.expiresAt)
            })
        }
    }

    private fun stableDependencyId(myDocUrl: String, upstreamDocUrl: String): String =
        sha256Hex("dependency|$myDocUrl|$upstreamDocUrl").take(32)

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun JsonElement.string(name: String): String =
        jsonObject[name]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

    private fun JsonElement.requiredString(name: String): String =
        string(name).ifBlank { error("$name is required") }

    private fun JsonElement.int(name: String): Int? =
        jsonObject[name]?.jsonPrimitive?.intOrNull

    private data class CandidateClaim(
        val value: String,
        val snippet: String,
        val keywords: Set<String>,
    )

    private data class UpstreamMatch(
        val snippet: String,
        val confidence: Float,
    )

    private data class AnchorBuildResult(
        val anchors: List<ReferenceAnchorEntity>,
        val myDocReadFailed: Boolean,
        val upstreamReadFailed: Boolean,
    )

    companion object {
        private const val MAX_NUMERIC_ANCHORS = 20
        private const val MAX_STATEMENT_ANCHORS = 6
        private const val AUTO_CONFIRM_THRESHOLD = 0.92f
        private val NUMBER_REGEX = Regex("""-?\d+(?:\.\d+)?(?:%|％|万|亿|元|人|次|天|小时|GB|MB)?""")
        private val WORD_REGEX = Regex("""[\p{L}A-Za-z0-9_]{2,}""")
        private val STATEMENT_SPLIT_REGEX = Regex("""[。！？!?；;\n]+""")
        private val SENTENCE_BOUNDARIES = charArrayOf('\n', '。', '！', '？', '；', ';', '.', '!', '?')
        private val STRONG_STATEMENT_HINTS = listOf("必须", "显著", "核心", "至少", "不超过", "超过", "低于", "增长", "下降", "依赖", "引用")
        private val STOP_WORDS = setOf("the", "and", "for", "with", "this", "that")
    }
}

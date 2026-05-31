package app.amber.feature.board

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.agent.data.db.dao.DocChangeLogDAO
import app.amber.agent.data.db.dao.DocSubscriptionDAO
import app.amber.agent.data.db.entity.DocChangeLogEntity
import app.amber.agent.data.db.entity.DocSubscriptionEntity
import app.amber.agent.data.db.entity.OpportunityType
import app.amber.agent.data.db.entity.ReferenceAnchorEntity

class CompositeOpportunityScanner(
    private val scanners: List<OpportunityScanner>,
) : OpportunityScanner {
    override suspend fun scan(boardDate: String): Int {
        var total = 0
        scanners.forEach { scanner ->
            total += scanner.scan(boardDate)
        }
        return total
    }
}

class DependencyStaleOpportunityScanner(
    private val subscriptionDao: DocSubscriptionDAO,
    private val changeLogDao: DocChangeLogDAO,
    private val anchorRepository: ReferenceAnchorRepository,
    private val opportunityRepository: OpportunityRepository,
) : OpportunityScanner {
    override suspend fun scan(boardDate: String): Int {
        val subscriptions = subscriptionDao.getEnabled()
        if (subscriptions.isEmpty()) return 0
        var count = 0
        for (subscription in subscriptions) {
            val latestChange = changeLogDao.getRecent(subscription.id, limit = 1).firstOrNull() ?: continue
            val anchors = anchorRepository.activeByUpstream(subscription.docUrl)
                .ifEmpty { anchorRepository.activeByUpstream(subscription.docToken) }
            if (anchors.isEmpty()) continue
            val relevantAnchors = anchors.filter { anchor -> anchor.matchesChange(latestChange) }.take(MAX_ANCHORS_PER_OPPORTUNITY)
            if (relevantAnchors.isEmpty()) continue
            val sourceRef = "${latestChange.id}:${relevantAnchors.joinToString(",") { it.id }}"
            val score = score(subscription, latestChange, relevantAnchors)
            val evidence = evidenceJson {
                put("subscription_id", subscription.id)
                put("upstream_doc_url", subscription.docUrl)
                put("upstream_doc_title", subscription.docTitle)
                put("change_id", latestChange.id)
                put("change_summary", latestChange.summary)
                put("changed_sections_json", latestChange.changedSectionsJson)
                put("anchors", buildJsonArray {
                    relevantAnchors.forEach { anchor ->
                        add(
                            buildJsonObject {
                                put("anchor_id", anchor.id)
                                put("my_doc_ref", anchor.myDocRef)
                                put("my_claim_text", anchor.myClaimText)
                                put("baseline_value", anchor.baselineValue)
                                put("upstream_hint", anchor.upstreamHint)
                                put("match_confidence", anchor.matchConfidence)
                            }
                        )
                    }
                })
            }
            val scoreJson = scoreJson(
                "object_anchor" to 25,
                "material_anchor" to 20,
                "change_signal" to latestChange.changeSignalScore(),
                "anchor_confidence" to relevantAnchors.anchorScore(),
                "actionable_next_step" to 15,
            )
            val summary = "上游文档「${subscription.docTitle}」发生变化，可能影响 ${relevantAnchors.size} 处已确认引用。建议复核旧值并生成替换建议。"
            val entity = opportunity(
                type = OpportunityType.DEPENDENCY_STALE,
                sourceType = "feishu_doc",
                sourceRef = sourceRef,
                title = "复核依赖文档更新：${subscription.docTitle}",
                summary = summary,
                evidenceJson = evidence,
                scoreJson = scoreJson,
                confidence = confidenceFromScore(score),
                suggestedActionsJson = buildJsonArray {
                    add(JsonPrimitive("复核旧值与上游新内容"))
                    add(JsonPrimitive("生成我方文档修改建议"))
                    add(JsonPrimitive("等待确认后再写回"))
                }.toString(),
                dueAt = null,
                triggerAt = latestChange.detectedAt,
                expiresAt = latestChange.detectedAt + EXPIRE_AFTER_CHANGE_MS,
            )
            if (opportunityRepository.upsertSuggested(entity) != null) count++
        }
        return count
    }

    private fun ReferenceAnchorEntity.matchesChange(change: DocChangeLogEntity): Boolean {
        if (change.changedSectionsJson == "[]") return true
        val hint = upstreamHint.trim()
        if (hint.isBlank()) return true
        return change.changedSectionsJson.contains(hint, ignoreCase = true) ||
            hint.contains(change.summary, ignoreCase = true)
    }

    private fun score(
        subscription: DocSubscriptionEntity,
        change: DocChangeLogEntity,
        anchors: List<ReferenceAnchorEntity>,
    ): Int =
        (60 + change.changeSignalScore() + anchors.anchorScore()).coerceIn(0, 100)

    private fun DocChangeLogEntity.changeSignalScore(): Int =
        when {
            changedSectionsJson != "[]" -> 20
            effectiveChange >= 1000 -> 18
            effectiveChange >= 500 -> 14
            else -> 8
        }

    private fun List<ReferenceAnchorEntity>.anchorScore(): Int {
        val best = maxOfOrNull { (it.matchConfidence * 20).toInt() } ?: 0
        val countBoost = size.coerceAtMost(5)
        return (best + countBoost).coerceAtMost(25)
    }

    companion object {
        private const val MAX_ANCHORS_PER_OPPORTUNITY = 8
        private const val EXPIRE_AFTER_CHANGE_MS = 14L * 24L * 60L * 60L * 1000L
    }
}

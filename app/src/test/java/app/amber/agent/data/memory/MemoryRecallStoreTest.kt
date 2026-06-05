package app.amber.core.memory

import app.amber.ai.ui.UIMessage
import app.amber.core.memory.extraction.MemoryExtractor
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryRecallSetting
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.recall.MemoryRecallFreshness
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallStoreTest {
    @Test
    fun durableAutoWriteRequiresExplicitDurableCandidate() {
        val durableUser = candidate(
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.USER,
            confidence = 0.85f,
        )
        val durableFeedback = candidate(
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.FEEDBACK,
            confidence = 0.9f,
        )
        val lowConfidenceUser = durableUser.copy(confidence = 0.84f)
        val defaultedUser = durableUser.copy(scope = MemoryScope.LONG_TERM, kind = MemoryKind.USER)
        val sensitiveUser = durableUser.copy(content = "用户的密码是 123456。")
        val shortProject = candidate(
            scope = MemoryScope.SHORT_TERM,
            kind = MemoryKind.PROJECT,
            confidence = 0.72f,
        )

        assertTrue(MemoryExtractor.shouldAutoWriteCandidate(durableUser, explicitScope = true, explicitKind = true))
        assertTrue(MemoryExtractor.shouldAutoWriteCandidate(durableFeedback, explicitScope = true, explicitKind = true))
        assertTrue(MemoryExtractor.shouldAutoWriteCandidate(shortProject, explicitScope = true, explicitKind = true))
        assertFalse(MemoryExtractor.shouldAutoWriteCandidate(lowConfidenceUser, explicitScope = true, explicitKind = true))
        assertFalse(MemoryExtractor.shouldAutoWriteCandidate(defaultedUser, explicitScope = false, explicitKind = true))
        assertFalse(MemoryExtractor.shouldAutoWriteCandidate(defaultedUser, explicitScope = true, explicitKind = false))
        assertFalse(MemoryExtractor.shouldAutoWriteCandidate(sensitiveUser, explicitScope = true, explicitKind = true))
    }

    @Test
    fun durableUserCanRecallWithoutKeywordAndOutrankShortProject() {
        val now = day("2026-08-01")
        val settings = settings(maxItems = 1)
        val durablePreference = record(
            id = 1,
            content = "用户是素食者，不吃肉。",
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.USER,
            confidence = 0.95f,
            updatedAt = now - 20 * DAY,
        )
        val shortProject = record(
            id = 2,
            content = "当前短期项目是推荐模板整理。",
            scope = MemoryScope.SHORT_TERM,
            kind = MemoryKind.PROJECT,
            confidence = 0.95f,
            updatedAt = now,
            lastUsedAt = now,
        )

        val ranked = MemoryRecallStore.rankRecords(
            settings = settings,
            messages = listOf(UIMessage.user("今晚 推荐 外卖")),
            records = listOf(shortProject, durablePreference),
            now = now,
        )

        assertEquals(listOf(1), ranked.map { it.record.id })
        assertTrue(ranked.single().score.reasons.contains("durable-user"))
    }

    @Test
    fun feedbackRecallsWithoutKeyword() {
        val now = day("2026-08-01")
        val ranked = MemoryRecallStore.rankRecords(
            settings = settings(maxItems = 1),
            messages = listOf(UIMessage.user("帮我写一段团队介绍")),
            records = listOf(
                record(
                    id = 1,
                    content = "不要在回答里提 Stan。",
                    scope = MemoryScope.LONG_TERM,
                    kind = MemoryKind.FEEDBACK,
                    confidence = 0.88f,
                    updatedAt = now,
                )
            ),
            now = now,
        )

        assertEquals(listOf(1), ranked.map { it.record.id })
        assertTrue(ranked.single().score.reasons.contains("feedback"))
    }

    @Test
    fun activeShortProjectStillWinsProjectContinuationQuery() {
        val now = day("2026-08-01")
        val settings = settings(maxItems = 1)
        val durablePreference = record(
            id = 1,
            content = "用户偏好中文简洁回复。",
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.USER,
            confidence = 0.95f,
            updatedAt = now,
        )
        val activeProject = record(
            id = 2,
            content = "当前项目是 AmberAgent 记忆系统升级，需要继续实现召回和 Summary。",
            scope = MemoryScope.SHORT_TERM,
            kind = MemoryKind.PROJECT,
            confidence = 0.95f,
            updatedAt = now,
            lastUsedAt = now,
        )

        val ranked = MemoryRecallStore.rankRecords(
            settings = settings,
            messages = listOf(UIMessage.user("继续 AmberAgent 记忆系统项目")),
            records = listOf(durablePreference, activeProject),
            now = now,
        )

        assertEquals(listOf(2), ranked.map { it.record.id })
    }

    @Test
    fun expiredFutureIntentTimeAnchorsAreDecayed() {
        val now = day("2026-08-01")
        val terms = MemoryRecallStore.tokenize("今晚外卖")
        val asciiMonth = MemoryRecallStore.score(
            record = record(
                id = 1,
                content = "用户计划 2026-07 去新加坡。",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.USER,
            ),
            terms = terms,
            currentText = "今晚外卖",
            now = now,
        )
        val chineseMonth = MemoryRecallStore.score(
            record = record(
                id = 2,
                content = "用户计划 2026年7月 去新加坡。",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.USER,
            ),
            terms = terms,
            currentText = "今晚外卖",
            now = now,
        )
        val historical = MemoryRecallStore.score(
            record = record(
                id = 3,
                content = "用户 2026-07 去过新加坡。",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.USER,
            ),
            terms = terms,
            currentText = "今晚外卖",
            now = now,
        )

        assertEquals(MemoryRecallFreshness.TIME_DECAYED, asciiMonth.freshness)
        assertEquals(MemoryRecallFreshness.TIME_DECAYED, chineseMonth.freshness)
        assertTrue(asciiMonth.reasons.contains("time-decayed"))
        assertEquals(MemoryRecallFreshness.CURRENT, historical.freshness)
    }

    @Test
    fun conservativeTimeHintsDoNotDecayBareHistoricalChineseFacts() {
        val now = day("2026-08-01")
        val terms = MemoryRecallStore.tokenize("工作旅行")
        val plannedTrip = MemoryRecallStore.score(
            record = record(
                id = 1,
                content = "用户计划 2026年7月去新加坡旅行。",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.USER,
            ),
            terms = terms,
            currentText = "工作旅行",
            now = now,
        )
        val hired = MemoryRecallStore.score(
            record = record(
                id = 2,
                content = "用户 2025年3月入职。",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.USER,
            ),
            terms = terms,
            currentText = "工作旅行",
            now = now,
        )
        val wentToBeijing = MemoryRecallStore.score(
            record = record(
                id = 3,
                content = "用户 2025年3月去了北京。",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.USER,
            ),
            terms = terms,
            currentText = "工作旅行",
            now = now,
        )

        assertEquals(MemoryRecallFreshness.TIME_DECAYED, plannedTrip.freshness)
        assertEquals(MemoryRecallFreshness.CURRENT, hired.freshness)
        assertEquals(MemoryRecallFreshness.CURRENT, wentToBeijing.freshness)
    }

    @Test
    fun englishFutureHintsUseWordBoundaries() {
        val now = day("2026-08-01")
        val terms = MemoryRecallStore.tokenize("schedule")
        val realFutureHint = MemoryRecallStore.score(
            record = record(
                id = 1,
                content = "User will travel in 2026-07.",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.USER,
            ),
            terms = terms,
            currentText = "schedule",
            now = now,
        )
        val willing = MemoryRecallStore.score(
            record = record(
                id = 2,
                content = "User was willing to discuss 2026-07 history.",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.USER,
            ),
            terms = terms,
            currentText = "schedule",
            now = now,
        )
        val triple = MemoryRecallStore.score(
            record = record(
                id = 3,
                content = "User likes triple-checking 2026-07 reports.",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.USER,
            ),
            terms = terms,
            currentText = "schedule",
            now = now,
        )

        assertEquals(MemoryRecallFreshness.TIME_DECAYED, realFutureHint.freshness)
        assertEquals(MemoryRecallFreshness.CURRENT, willing.freshness)
        assertEquals(MemoryRecallFreshness.CURRENT, triple.freshness)
    }

    @Test
    fun noteDoesNotBecomeAlwaysEligibleWithoutTerms() {
        val now = day("2026-08-01")
        val ranked = MemoryRecallStore.rankRecords(
            settings = settings(maxItems = 5),
            messages = listOf(UIMessage.user("今晚吃什么")),
            records = listOf(
                record(
                    id = 1,
                    content = "一条无关的长期备注。",
                    scope = MemoryScope.LONG_TERM,
                    kind = MemoryKind.NOTE,
                    confidence = 1f,
                    updatedAt = now,
                )
            ),
            now = now,
        )

        assertTrue(ranked.isEmpty())
    }

    private fun settings(maxItems: Int): Settings =
        Settings(
            agentRuntime = AgentRuntimeSetting(
                memoryRecall = MemoryRecallSetting(maxItems = maxItems, maxPromptChars = 2_000),
            )
        )

    private fun candidate(
        scope: MemoryScope,
        kind: MemoryKind,
        confidence: Float,
    ) = MemoryCandidate(
        content = "User prefers concise Chinese replies.",
        scope = scope,
        kind = kind,
        confidence = confidence,
    )

    private fun record(
        id: Int,
        content: String,
        scope: MemoryScope,
        kind: MemoryKind,
        confidence: Float = 1f,
        updatedAt: Long = day("2026-06-01"),
        lastUsedAt: Long? = null,
    ) = MemoryRecord(
        id = id,
        content = content,
        scope = scope,
        kind = kind,
        assistantId = "__test__",
        confidence = confidence,
        updatedAt = updatedAt,
        lastUsedAt = lastUsedAt,
    )

    private companion object {
        const val DAY = 86_400_000L

        fun day(date: String): Long =
            LocalDate.parse(date)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
    }
}

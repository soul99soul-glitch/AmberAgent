package app.amber.feature.board.agent

import app.amber.feature.board.aggregator.ScoredSignal
import app.amber.agent.data.db.entity.BoardFocusRuleEntity
import java.util.Locale

object BoardPrompt {
    const val MAX_TODO_ITEMS = 5
    private const val SIGNAL_LIMIT = 80

    fun build(
        scoredSignals: List<ScoredSignal>,
        focusRules: List<BoardFocusRuleEntity>,
        nowMs: Long = System.currentTimeMillis(),
        locale: Locale = Locale.CHINESE,
    ): String = buildString {
        val chinese = locale.isChineseLocale()
        appendLine(
            if (chinese) {
                "你是 AmberAgent 的「今日看板」助理。基于下面的信号提炼今天最重要的待办事项。"
            } else {
                "You are AmberAgent's Today Board assistant. Extract the most important actionable items from the signals below."
            }
        )
        appendLine()
        appendLine(if (chinese) "## 输出要求" else "## Output requirements")
        appendLine(
            if (chinese) "- 仅输出 JSON，不要代码围栏、不要前后解释。"
            else "- Output JSON only; no code fences or surrounding explanation."
        )
        appendLine(
            if (chinese) {
                "- JSON 根必须包含两个键：`summary`（一句中文摘要，不超过 60 字）和 `items`（待办数组，最多 5 条）。"
            } else {
                "- The JSON root must contain `summary` (one concise English summary, no more than 60 words) and `items` (at most 5 action items)."
            }
        )
        appendLine(if (chinese) "- 每个 item 字段：" else "- Each item must contain:")
        appendLine(
            if (chinese) "  - `title`：string，一句话描述，≤ 60 字"
            else "  - `title`: string; one-sentence description, ≤ 60 characters"
        )
        appendLine(
            if (chinese) "  - `source_type`：string，必须与来源信号 source_type 一致"
            else "  - `source_type`: string; must match the source signal's source_type"
        )
        appendLine(
            if (chinese) "  - `source_ref`：string，必须**完全等于**传入信号里的某个 source_ref"
            else "  - `source_ref`: string; must exactly equal a source_ref from the supplied signals"
        )
        appendLine(if (chinese) "  - `urgency`：`high` / `medium` / `low`" else "  - `urgency`: `high` / `medium` / `low`")
        appendLine(
            if (chinese) "  - `reason`：string，说明你为什么认为这条是待办，≤ 100 字"
            else "  - `reason`: string; explain why this is actionable, ≤ 100 characters"
        )
        appendLine(
            if (chinese) "  - `suggestion`：string，具体下一步建议，≤ 100 字；没有建议时写 \"-\""
            else "  - `suggestion`: string; concrete next step, ≤ 100 characters; use \"-\" when none applies"
        )
        appendLine(
            if (chinese) "  - `signal_time`：number，原始信号的时间戳（ms）"
            else "  - `signal_time`: number; timestamp of the original signal in milliseconds"
        )
        appendLine()
        appendLine(if (chinese) "## 待办筛选" else "## Item selection")
        appendLine(
            if (chinese) "- 只输出用户需要行动、回复、准备、跟进、参加、提交、review 或确认的事项。"
            else "- Include only items that require the user to act, reply, prepare, follow up, attend, submit, review, or confirm."
        )
        appendLine(
            if (chinese) "- 最多 5 条；按紧急度、时间接近程度、对用户工作的影响排序。"
            else "- Return at most 5 items, ordered by urgency, proximity of deadlines, and impact on the user's work."
        )
        appendLine(
            if (chinese) "- 没有足够高价值待办时可以少给，甚至输出空数组 `items: []`。"
            else "- Return fewer items, or even an empty `items: []`, when there are not enough valuable actions."
        )
        appendLine(
            if (chinese) "- 不要为了填满数量，把普通聊天、测试 prompt 或系统触发信号包装成看板条目。"
            else "- Do not turn ordinary chats, test prompts, or system-triggered signals into board items just to fill the limit."
        )
        appendLine()
        appendLine(if (chinese) "## 规则" else "## Rules")
        appendLine(
            if (chinese) "- 不要编造不存在的信号或事实；所有 item 的 source_ref 必须来自下面的信号列表。"
            else "- Do not invent signals or facts; every item's source_ref must come from the signal list below."
        )
        appendLine(
            if (chinese) "- 可以把多个同源信号合并成一条 item（source_ref 填主信号）。"
            else "- You may merge multiple signals from the same source into one item; use the primary signal's source_ref."
        )
        appendLine(
            if (chinese) "- 语言一律使用**中文**。"
            else "- Write all user-visible summary, title, reason, and suggestion text in **English**."
        )
        appendLine(
            if (chinese) {
                "- 避免空洞建议（\"请关注\"、\"请处理\"），给出具体动作。"
            } else {
                "- Avoid vague suggestions (such as \"pay attention\" or \"handle this\"); give a concrete action."
            }
        )
        appendLine(
            if (chinese) {
                "- `chat_history` 只用于可延续的真实工作上下文；不要把长文生成测试、乱码/混合语言测试、重复 prompt 当作任务来源。"
            } else {
                "- Use `chat_history` only for real, actionable context; do not turn long-form generation tests, garbled/mixed-language tests, or repeated prompts into source tasks."
            }
        )
        appendLine()
        if (focusRules.isNotEmpty()) {
            appendLine(if (chinese) "## 用户关注点（软提示，非硬过滤）" else "## User focus (soft hints, not hard filters)")
            focusRules.take(30).forEach { appendLine("- ${it.content}") }
            appendLine()
        }
        appendLine(if (chinese) "## 当前时间" else "## Current time")
        appendLine(java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault()).toString())
        appendLine()
        appendLine(if (chinese) "## 信号列表（按 aggregator 打分降序）" else "## Signals (descending aggregator score)")
        scoredSignals.take(SIGNAL_LIMIT).forEachIndexed { idx, scored ->
            val s = scored.signal
            appendLine("### [$idx] ${s.sourceType} | score=${scored.score}")
            appendLine("- source_ref: ${s.sourceRef}")
            appendLine("- signal_time: ${s.signalTime}")
            appendLine("- title: ${s.title.take(120)}")
            val contentExcerpt = s.content.take(400).replace("\n", " ")
            appendLine("- content: $contentExcerpt")
            appendLine()
        }
    }

    private fun Locale.isChineseLocale(): Boolean = language.equals("zh", ignoreCase = true)
}

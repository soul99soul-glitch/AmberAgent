package me.rerere.rikkahub.data.agent.board.agent

import me.rerere.rikkahub.data.agent.board.TodayBoardDensity
import me.rerere.rikkahub.data.agent.board.aggregator.ScoredSignal
import me.rerere.rikkahub.data.db.entity.BoardFocusRuleEntity

object BoardPrompt {
    fun build(
        scoredSignals: List<ScoredSignal>,
        focusRules: List<BoardFocusRuleEntity>,
        density: TodayBoardDensity,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val caps = densityCaps(density)
        val signalLimit = signalLimit(density)
        return buildString {
            appendLine("你是 AmberAgent 的「今日看板」助理。基于下面的信号产出一份结构化 JSON 看板。")
            appendLine()
            appendLine("## 输出要求")
            appendLine("- 仅输出 JSON，不要代码围栏、不要前后解释。")
            appendLine("- JSON 根必须包含两个键：`summary`（一句中文摘要，不超过 60 字）和 `items`（条目数组）。")
            appendLine("- 每个 item 字段：")
            appendLine("  - `title`：string，一句话描述，≤ 60 字")
            appendLine("  - `source_type`：string，必须与来源信号 source_type 一致")
            appendLine("  - `source_ref`：string，必须**完全等于**传入信号里的某个 source_ref")
            appendLine("  - `urgency`：`high` / `medium` / `low`")
            appendLine("  - `category`：`action`（要做的事）/ `attention`（值得关注）/ `info`（今日动态）")
            appendLine("  - `reason`：string，说明你为什么认为这条值得关注，≤ 100 字")
            appendLine("  - `suggestion`：string，具体下一步建议，≤ 100 字；没有建议时写 \"-\"")
            appendLine("  - `signal_time`：number，原始信号的时间戳（ms）")
            appendLine()
            appendLine("## 密度上限")
            appendLine("- action ≤ ${caps.action}，attention ≤ ${caps.attention}，info ≤ ${caps.info}")
            appendLine("- 如果可选条目多于上限，按相关性和紧急度取前 N 条，其余丢弃。")
            appendLine()
            appendLine("## 规则")
            appendLine("- 不要编造不存在的信号或事实；所有 item 的 source_ref 必须来自下面的信号列表。")
            appendLine("- 同一个 source_ref 不要同时归到多个 category；选最相关的一类。")
            appendLine("- 可以把多个同源信号合并成一条 item（source_ref 填主信号）。")
            appendLine("- 语言一律使用**中文**。")
            appendLine("- 避免空洞建议（\"请关注\"、\"请处理\"），给出具体动作。")
            appendLine()
            if (focusRules.isNotEmpty()) {
                appendLine("## 用户关注点（软提示，非硬过滤）")
                focusRules.take(30).forEach { appendLine("- ${it.content}") }
                appendLine()
            }
            appendLine("## 当前时间")
            appendLine(java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault()).toString())
            appendLine()
            appendLine("## 信号列表（按 aggregator 打分降序）")
            scoredSignals.take(signalLimit).forEachIndexed { idx, scored ->
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
    }

    fun densityCaps(density: TodayBoardDensity): DensityCaps = when (density) {
        TodayBoardDensity.COMPACT -> DensityCaps(action = 5, attention = 3, info = 2)
        TodayBoardDensity.STANDARD -> DensityCaps(action = 8, attention = 5, info = 3)
        TodayBoardDensity.RICH -> DensityCaps(action = 12, attention = 8, info = 5)
    }

    fun signalLimit(density: TodayBoardDensity): Int = when (density) {
        TodayBoardDensity.COMPACT -> 30
        TodayBoardDensity.STANDARD -> 60
        TodayBoardDensity.RICH -> 100
    }

    data class DensityCaps(val action: Int, val attention: Int, val info: Int) {
        val total: Int get() = action + attention + info
    }
}

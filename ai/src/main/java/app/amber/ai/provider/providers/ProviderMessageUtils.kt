package app.amber.ai.provider.providers

import app.amber.ai.ui.UIMessagePart

/**
 * 消息 parts 按工具边界分组的结果
 * - Content: 普通内容（Text、Image、Reasoning 等）
 * - Tools: 连续的已执行工具
 */
internal sealed class PartGroup {
    data class Content(val parts: List<UIMessagePart>) : PartGroup()
    data class Tools(val tools: List<UIMessagePart.Tool>) : PartGroup()
}

internal fun groupPartsByToolBoundary(parts: List<UIMessagePart>): List<PartGroup> {
    val groups = ArrayList<PartGroup>()
    var start = 0
    while (start < parts.size) {
        val startsWithExecutedTool = parts[start].isExecutedTool()
        var end = start + 1
        while (end < parts.size && parts[end].isExecutedTool() == startsWithExecutedTool) {
            end += 1
        }

        val run = parts.subList(start, end)
        if (startsWithExecutedTool) {
            groups += PartGroup.Tools(run.map { it as UIMessagePart.Tool })
        } else {
            groups += PartGroup.Content(run.toList())
        }
        start = end
    }
    return groups
}

private fun UIMessagePart.isExecutedTool(): Boolean = this is UIMessagePart.Tool && isExecuted

package app.amber.core.files

/**
 * Minimal line-based unified diff (P2-04 skill promotion preview / P2-07
 * soul import preview). No external dependencies; good enough to show the
 * user (and the approval audit) what a candidate changes.
 */
object SimpleDiff {

    /** Unified diff with [contextLines] of context; "" when texts are equal. */
    fun unifiedDiff(oldText: String, newText: String, contextLines: Int = 2, fileLabel: String = "content"): String {
        val oldLines = oldText.lines()
        val newLines = newText.lines()
        if (oldLines == newLines) return ""
        val ops = buildOps(oldLines, newLines)
        return render(ops, contextLines, fileLabel)
    }

    private sealed class Op {
        data class Equal(val line: String) : Op()

        data class Delete(val line: String) : Op()

        data class Insert(val line: String) : Op()
    }

    /** Longest-common-subsequence edit script over the line arrays. */
    private fun buildOps(oldLines: List<String>, newLines: List<String>): List<Op> {
        val n = oldLines.size
        val m = newLines.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (oldLines[i] == newLines[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val ops = mutableListOf<Op>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (oldLines[i] == newLines[j]) {
                ops += Op.Equal(oldLines[i])
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops += Op.Delete(oldLines[i])
                i++
            } else {
                ops += Op.Insert(newLines[j])
                j++
            }
        }
        while (i < n) {
            ops += Op.Delete(oldLines[i])
            i++
        }
        while (j < m) {
            ops += Op.Insert(newLines[j])
            j++
        }
        return ops
    }

    private fun render(ops: List<Op>, contextLines: Int, fileLabel: String): String {
        // Mark op indexes that belong to a changed hunk (change + context).
        val inHunk = BooleanArray(ops.size)
        ops.forEachIndexed { index, op ->
            if (op !is Op.Equal) {
                for (k in maxOf(0, index - contextLines)..minOf(ops.lastIndex, index + contextLines)) {
                    inHunk[k] = true
                }
            }
        }
        if (inHunk.none { it }) return ""

        // Split the ops into contiguous hunks, tracking line numbers.
        data class Hunk(val oldStart: Int, val newStart: Int, val ops: List<Op>)

        val hunks = mutableListOf<Hunk>()
        var current = mutableListOf<Op>()
        var inCurrent = false
        var currentOldStart = 1
        var currentNewStart = 1
        var oldIndex = 1
        var newIndex = 1
        fun closeHunk() {
            if (current.isNotEmpty()) {
                hunks += Hunk(currentOldStart, currentNewStart, current)
                current = mutableListOf()
                inCurrent = false
            }
        }
        ops.forEachIndexed { index, op ->
            if (inHunk[index]) {
                if (!inCurrent) {
                    inCurrent = true
                    currentOldStart = oldIndex
                    currentNewStart = newIndex
                }
                current += op
            } else {
                closeHunk()
            }
            when (op) {
                is Op.Equal -> {
                    oldIndex++
                    newIndex++
                }
                is Op.Delete -> oldIndex++
                is Op.Insert -> newIndex++
            }
        }
        closeHunk()

        val sb = StringBuilder()
        sb.append("--- a/$fileLabel\n+++ b/$fileLabel\n")
        hunks.forEach { hunk ->
            val oldCount = hunk.ops.count { it is Op.Delete } + hunk.ops.count { it is Op.Equal }
            val newCount = hunk.ops.count { it is Op.Insert } + hunk.ops.count { it is Op.Equal }
            sb.append("@@ -${hunk.oldStart},$oldCount +${hunk.newStart},$newCount @@\n")
            hunk.ops.forEach { op ->
                when (op) {
                    is Op.Equal -> sb.append(" ${op.line}\n")
                    is Op.Delete -> sb.append("-${op.line}\n")
                    is Op.Insert -> sb.append("+${op.line}\n")
                }
            }
        }
        return sb.toString().trimEnd('\n')
    }
}

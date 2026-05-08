package me.rerere.rikkahub.data.agent.office.radar

import me.rerere.rikkahub.data.db.entity.FeishuDocSnapshotEntity

object FeishuDocumentDiffEngine {
    data class DiffResult(
        val addedChars: Int,
        val removedChars: Int,
        val effectiveChange: Int,
        val changedSections: List<String>,
        val isSignificant: Boolean,
        val diffSummary: String,
        val threshold: Int,
    )

    fun diff(
        oldSnapshot: FeishuDocSnapshotEntity,
        newSnapshot: FeishuDocSnapshotEntity,
        headingList: List<String>,
        threshold: Int = 500,
    ): DiffResult {
        val oldParagraphs = splitParagraphs(oldSnapshot.plainText)
        val newParagraphs = splitParagraphs(newSnapshot.plainText)

        val oldSet = oldParagraphs.toSet()
        val newSet = newParagraphs.toSet()

        val added = newParagraphs.filter { it !in oldSet }
        val removed = oldParagraphs.filter { it !in newSet }

        val addedChars = added.sumOf { it.length }
        val removedChars = removed.sumOf { it.length }
        val effectiveChange = (addedChars + removedChars).coerceAtLeast(0)

        val changedSections = findRelevantHeadings(added, removed, headingList)
        val isSignificant = effectiveChange >= threshold || hasStructuralChange(headingList, oldSnapshot, newSnapshot)

        val diffSummary = buildSummary(addedChars, removedChars, changedSections, isSignificant, threshold)

        return DiffResult(
            addedChars = addedChars,
            removedChars = removedChars,
            effectiveChange = effectiveChange,
            changedSections = changedSections,
            isSignificant = isSignificant,
            diffSummary = diffSummary,
            threshold = threshold,
        )
    }

    private fun splitParagraphs(text: String): List<String> =
        text.split(Regex("\n\n+|\n(?=#{1,6}\\s)"))
            .map { it.trim() }
            .filter { it.length >= 4 }

    private fun findRelevantHeadings(
        added: List<String>,
        removed: List<String>,
        headingList: List<String>,
    ): List<String> {
        val allChanged = added + removed
        return headingList.filter { heading ->
            allChanged.any { paragraph ->
                paragraph.contains(heading.takeLast(20))
            }
        }.take(20)
    }

    private fun hasStructuralChange(
        headingList: List<String>,
        oldSnapshot: FeishuDocSnapshotEntity,
        newSnapshot: FeishuDocSnapshotEntity,
    ): Boolean {
        val oldHeadings = try {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(oldSnapshot.headingListJson)
        } catch (_: Exception) {
            emptyList()
        }
        val newHeadings = try {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(newSnapshot.headingListJson)
        } catch (_: Exception) {
            emptyList()
        }
        return oldHeadings != newHeadings && (oldHeadings.isNotEmpty() || newHeadings.isNotEmpty())
    }

    private fun buildSummary(
        addedChars: Int,
        removedChars: Int,
        sections: List<String>,
        isSignificant: Boolean,
        threshold: Int,
    ): String = buildString {
        if (addedChars > 0 && removedChars > 0) {
            append("新增 ${addedChars}字，删除 ${removedChars}字")
        } else if (addedChars > 0) {
            append("新增 ${addedChars}字")
        } else if (removedChars > 0) {
            append("删除 ${removedChars}字")
        } else {
            append("无实质变更")
        }
        if (sections.isNotEmpty()) {
            append("，涉及章节: ${sections.take(3).joinToString("、")}")
        }
        if (!isSignificant && (addedChars > 0 || removedChars > 0)) {
            append("（低于${threshold}字阈值，不通知）")
        }
    }
}

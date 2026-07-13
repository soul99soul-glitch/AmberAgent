package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelStoryEventRecord

/**
 * V1 character experience matcher.
 *
 * Experiences come only from current-branch events referenced by
 * `stateSnapshot.eventIDs`. Matching uses the character material title against
 * `event.entityReferences` — exact normalized match first, then contains match.
 * Generic tags like "主角/反派" are intentionally not used.
 */
object NovelCharacterEventMatcher {
    data class Match(
        val event: NovelStoryEventRecord,
        val exact: Boolean,
    )

    fun matchExperiences(
        characterTitle: String,
        events: List<NovelStoryEventRecord>,
    ): List<Match> {
        val title = normalize(characterTitle)
        if (title.isEmpty()) return emptyList()
        val exact = mutableListOf<Match>()
        val contains = mutableListOf<Match>()
        for (event in events) {
            val refs = event.entityReferences.map { normalize(it) }.filter { it.isNotEmpty() }
            when {
                refs.any { it == title } -> exact += Match(event, exact = true)
                refs.any { it.contains(title) || title.contains(it) } ->
                    contains += Match(event, exact = false)
            }
        }
        // Exact matches first (sequence desc), then contains matches (sequence desc).
        return exact.sortedByDescending { it.event.sequence } +
            contains.sortedByDescending { it.event.sequence }
    }

    fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")
}

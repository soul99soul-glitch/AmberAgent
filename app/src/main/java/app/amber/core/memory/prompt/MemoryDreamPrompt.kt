package app.amber.core.memory.prompt

import app.amber.core.memory.dream.NearDuplicatePair
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.store.MemoryProfile

object MemoryDreamPrompt {
    fun build(
        records: List<MemoryRecord>,
        candidates: List<MemoryCandidate>,
        nearDuplicatePairs: List<NearDuplicatePair> = emptyList(),
        currentProfile: MemoryProfile? = null,
    ): String = """
        Review AmberAgent memories and produce a reviewable JSON diff.
        Return only JSON with keys: merge, promote, archive, supersede, topics, delete_suggestions, user_profile, notes.
        Do not invent new facts.
        Do not physically delete anything.

        Schema:
        {
          "merge": [
            {
              "target_memory_id": 1,
              "duplicate_memory_ids": [2, 3],
              "merged_content": "optional concise merged memory",
              "reason": "why these should be merged"
            }
          ],
          "promote": [4],
          "archive": [5],
          "supersede": [
            {
              "old_memory_ids": [6],
              "new_content": "new replacement memory text",
              "scope": "long_term",
              "kind": "user",
              "confidence": 0.86,
              "reason": "why this newer fact replaces the old memory"
            }
          ],
          "topics": [
            {
              "title": "short topic name",
              "member_memory_ids": [7, 8, 9],
              "content": "concise topic summary synthesized only from the member memories",
              "reason": "why these memories belong to one topic"
            }
          ],
          "delete_suggestions": ["candidate_id"],
          "user_profile": {
            "content": "short paragraph synthesizing the user's durable traits, preferences, and working style",
            "reason": "what changed since the current profile"
          },
          "notes": ["short reviewer note"]
        }

        Rules:
        - Merge only when memories describe the same durable fact.
        - Use supersede only when newer evidence clearly replaces or conflicts with older non-core memories.
        - Supersede creates a new memory and archives old memories; do not use it for duplicates.
        - Resolve each flagged near-duplicate pair: merge true duplicates, supersede when one clearly updates the other, otherwise leave it alone.
        - Promote short_term to long_term only when the fact is likely useful across future conversations.
        - Archive expired or stale project memories; do not archive durable user preference or feedback.
        - Topics group 2+ related non-core memories under one named summary. Members keep existing; nothing is archived or removed.
        - Topic content must only restate facts already present in its member memories; add nothing new.
        - Prefer updating an existing topic (same title) over creating near-duplicate topics.
        - At most 4 topics per review.
        - delete_suggestions may only contain pending candidate ids, never formal memory ids.
        - user_profile restates durable facts already present in user/feedback/core memories; add nothing new. Emit it only when the current profile is missing or materially stale — otherwise omit the key entirely.
        - Keep notes concrete and short.

        Memories:
        ${records.joinToString("\n") { record ->
            val title = record.topicTitle?.takeIf { record.kind == MemoryKind.TOPIC }
                ?.let { " \"$it\"" }.orEmpty()
            "- #${record.id} [${record.scope.wireName}/${record.kind.wireName}$title] ${record.content}"
        }}

        Pending candidates:
        ${candidates.joinToString("\n") { "- #${it.id} [${it.scope.wireName}/${it.kind.wireName}] ${it.content}" }}

        Near-duplicate pairs flagged by lexical overlap (verify semantics before acting):
        ${nearDuplicatePairs.joinToString("\n") { pair ->
            "- #${pair.firstId} ⇄ #${pair.secondId} — ${(pair.similarity * 100).toInt()}% similar"
        }.ifBlank { "(none)" }}

        Current user profile:
        ${currentProfile?.content?.trim()?.ifBlank { null } ?: "(none yet)"}
    """.trimIndent()
}

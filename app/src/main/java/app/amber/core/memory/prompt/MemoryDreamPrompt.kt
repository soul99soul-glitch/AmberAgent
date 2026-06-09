package app.amber.core.memory.prompt

import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryRecord

object MemoryDreamPrompt {
    fun build(records: List<MemoryRecord>, candidates: List<MemoryCandidate>): String = """
        Review AmberAgent memories and produce a reviewable JSON diff.
        Return only JSON with keys: merge, promote, archive, supersede, delete_suggestions, notes.
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
          "delete_suggestions": ["candidate_id"],
          "notes": ["short reviewer note"]
        }

        Rules:
        - Merge only when memories describe the same durable fact.
        - Use supersede only when newer evidence clearly replaces or conflicts with older non-core memories.
        - Supersede creates a new memory and archives old memories; do not use it for duplicates.
        - Promote short_term to long_term only when the fact is likely useful across future conversations.
        - Archive expired or stale project memories; do not archive durable user preference or feedback.
        - delete_suggestions may only contain pending candidate ids, never formal memory ids.
        - Keep notes concrete and short.

        Memories:
        ${records.joinToString("\n") { "- #${it.id} [${it.scope.wireName}/${it.kind.wireName}] ${it.content}" }}

        Pending candidates:
        ${candidates.joinToString("\n") { "- #${it.id} [${it.scope.wireName}/${it.kind.wireName}] ${it.content}" }}
    """.trimIndent()
}

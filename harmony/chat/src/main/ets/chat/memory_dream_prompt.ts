// memory_dream_prompt — MemoryDreamPrompt.kt 全文移植(D-085f)
//
// Android 基准: core/memory/prompt/MemoryDreamPrompt.kt(全文 54 行)
//   模板 trimIndent 逐字;记录/候选行 '- #id [scope/kind] content' joinToString('\n')

import type { MemoryCandidate, MemoryRecord } from './memory_models.ts';

export const buildMemoryDreamPrompt = (
  records: MemoryRecord[], candidates: MemoryCandidate[],
): string => {
  const recordLines: string = records.map(
    (r: MemoryRecord): string => `- #${r.id} [${r.scope}/${r.kind}] ${r.content}`).join('\n');
  const candidateLines: string = candidates.map(
    (c: MemoryCandidate): string => `- #${c.id} [${c.scope}/${c.kind}] ${c.content}`).join('\n');
  return 'Review AmberAgent memories and produce a reviewable JSON diff.\n' +
    'Return only JSON with keys: merge, promote, archive, supersede, delete_suggestions, notes.\n' +
    'Do not invent new facts.\n' +
    'Do not physically delete anything.\n' +
    '\n' +
    'Schema:\n' +
    '{\n' +
    '  "merge": [\n' +
    '    {\n' +
    '      "target_memory_id": 1,\n' +
    '      "duplicate_memory_ids": [2, 3],\n' +
    '      "merged_content": "optional concise merged memory",\n' +
    '      "reason": "why these should be merged"\n' +
    '    }\n' +
    '  ],\n' +
    '  "promote": [4],\n' +
    '  "archive": [5],\n' +
    '  "supersede": [\n' +
    '    {\n' +
    '      "old_memory_ids": [6],\n' +
    '      "new_content": "new replacement memory text",\n' +
    '      "scope": "long_term",\n' +
    '      "kind": "user",\n' +
    '      "confidence": 0.86,\n' +
    '      "reason": "why this newer fact replaces the old memory"\n' +
    '    }\n' +
    '  ],\n' +
    '  "delete_suggestions": ["candidate_id"],\n' +
    '  "notes": ["short reviewer note"]\n' +
    '}\n' +
    '\n' +
    'Rules:\n' +
    '- Merge only when memories describe the same durable fact.\n' +
    '- Use supersede only when newer evidence clearly replaces or conflicts with older non-core memories.\n' +
    '- Supersede creates a new memory and archives old memories; do not use it for duplicates.\n' +
    '- Promote short_term to long_term only when the fact is likely useful across future conversations.\n' +
    '- Archive expired or stale project memories; do not archive durable user preference or feedback.\n' +
    '- delete_suggestions may only contain pending candidate ids, never formal memory ids.\n' +
    '- Keep notes concrete and short.\n' +
    '\n' +
    'Memories:\n' +
    recordLines +
    '\n' +
    '\n' +
    'Pending candidates:\n' +
    candidateLines;
};

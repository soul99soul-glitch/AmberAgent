// memory_candidate_filter — MemoryCandidateFilter.kt 全文移植(D-085d)
//
// Android 基准: core/memory/extraction/MemoryCandidateFilter.kt(全文 50 行)
//   - normalize:lowercase + 仅留 letterOrDigit + take(200)
//   - 拒绝门:sensitive(候选自带 || 内容命中敏感词)|| tooWeak(trim < 12 ||
//     confidence < 0.45)|| duplicate(既有集 || 本批已收);reason 拼接
//     listOfNotNull(原 reason(非空白)/"sensitive"/"low_value"/"duplicate")
//     joinToString("; ");拒绝副本 status=filtered
//   - 接受副本 kind = normalizeKind(NOTE→PROJECT)
// 偏差:Kotlin Char.isLetterOrDigit = Unicode 字母/数字 → JS \p{L}\p{N} 逐码点

import type { MemoryCandidate, MemoryKind, MemoryRecord } from './memory_models.ts';
import { isSensitiveMemoryContent } from './memory_prompt_builder.ts';

export interface MemoryFilterResult {
  accepted: MemoryCandidate[];
  rejected: MemoryCandidate[];
}

const normalize = (text: string): string => {
  let out: string = '';
  for (const ch of text.toLowerCase()) {
    if (/[\p{L}\p{N}]/u.test(ch)) out += ch;
    if (out.length >= 200) break;
  }
  return out;
};

const normalizeKind = (kind: MemoryKind): MemoryKind => kind === 'note' ? 'project' : kind;

export const filterMemoryCandidates = (
  candidates: MemoryCandidate[], existing: MemoryRecord[],
): MemoryFilterResult => {
  const accepted: MemoryCandidate[] = [];
  const rejected: MemoryCandidate[] = [];
  const existingNormalized: Set<string> = new Set<string>(
    existing.map((r: MemoryRecord): string => normalize(r.content)));

  for (const candidate of candidates) {
    const normalized: string = normalize(candidate.content);
    const sensitive: boolean = candidate.sensitive || isSensitiveMemoryContent(candidate.content);
    const tooWeak: boolean = candidate.content.trim().length < 12 || candidate.confidence < 0.45;
    const duplicate: boolean = existingNormalized.has(normalized)
      || accepted.some((c: MemoryCandidate): boolean => normalize(c.content) === normalized);
    if (sensitive || tooWeak || duplicate) {
      const reasons: string[] = [];
      if (candidate.reason.trim().length > 0) reasons.push(candidate.reason);
      if (sensitive) reasons.push('sensitive');
      if (tooWeak) reasons.push('low_value');
      if (duplicate) reasons.push('duplicate');
      rejected.push({
        ...candidate,
        sensitive,
        status: 'filtered',
        reason: reasons.join('; '),
      });
    } else {
      accepted.push({ ...candidate, kind: normalizeKind(candidate.kind) });
    }
  }
  return { accepted, rejected };
};

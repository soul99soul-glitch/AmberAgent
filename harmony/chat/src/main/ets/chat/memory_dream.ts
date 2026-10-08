// memory_dream — dream 子系统核心(D-085f)
//
// Android 基准:
//   - MemoryWorkerDreamGate.kt(全文 12 行)
//   - MemoryDreamPlanner.kt(plan :39-72 / planWithModel :74-100 / planMaintenance
//     :129-186 / parseModelPlanJson :191-265 / mergeWith :269-285 / 模型 :287-320)
//   - MemoryDreamApplier.kt(全文 189 行 — apply :16-110 /
//     onlyApplicableToManagedMemories :112-172 / 守卫 :174-178 / summaryText
//     :180-183 / managedMemoryComparator :185-188)
// 偏差登记:
//   - resolveDaydreamModel(Planner.kt:102-120,daydreamModelId/daydreamFollowCompressModel/
//     modelId/followCompressModel/chatModelId 任务模型链)→ deps.resolveDaydreamModel;
//     鸿蒙任务模型设置未移植,entry 以 chat 模型顶替(D-085d 同偏差);
//   - daydreamReasoningLevel(MemoryWorkerSetting 默认 HIGH)→ entry 侧 generateText
//     用 chat 默认 reasoning(登记,任务模型切片闭合);
//   - JSON 解析失败文本 = JS SyntaxError(仅影响调用方 runCatching 吞掉的路径)

import type {
  MemoryCandidate, MemoryEvent, MemoryEventType, MemoryKind, MemoryRecord, MemoryScope,
} from './memory_models.ts';
import { makeMemoryEvent, memoryKindFromWireName, memoryScopeFromWireName } from './memory_models.ts';
import { canPromoteMemory, isMemoryActive, shouldArchiveIdleMemory } from './memory_lifecycle.ts';
import { isSensitiveMemoryContent } from './memory_prompt_builder.ts';
import { buildMemoryDreamPrompt } from './memory_dream_prompt.ts';
import type { MemoryAddParams } from './memory_write.ts';

// ===== 模型(MemoryDreamPlanner.kt:287-320) =====

export interface MemoryMergeSuggestion {
  targetMemoryId: number;
  duplicateMemoryIds: number[];
  mergedContent: string | null;
  reason: string;
}

export interface MemorySupersedeSuggestion {
  oldMemoryIds: number[];
  newContent: string;
  scope: MemoryScope;
  kind: MemoryKind;
  confidence: number;
  reason: string;
}

export interface MemoryDreamPlan {
  mergeSuggestions: MemoryMergeSuggestion[];
  promoteMemoryIds: number[];
  archiveMemoryIds: number[];
  ignoreCandidateIds: string[];
  supersedeSuggestions: MemorySupersedeSuggestion[];
  notes: string[];
}

export const makeMemoryDreamPlan = (
  opts: Partial<MemoryDreamPlan> = {},
): MemoryDreamPlan => ({
  mergeSuggestions: opts.mergeSuggestions ?? [],
  promoteMemoryIds: opts.promoteMemoryIds ?? [],
  archiveMemoryIds: opts.archiveMemoryIds ?? [],
  ignoreCandidateIds: opts.ignoreCandidateIds ?? [],
  supersedeSuggestions: opts.supersedeSuggestions ?? [],
  notes: opts.notes ?? [],
});

// hasChanges(:296-301)— notes 不计
export const memoryDreamPlanHasChanges = (plan: MemoryDreamPlan): boolean =>
  plan.mergeSuggestions.length > 0
  || plan.promoteMemoryIds.length > 0
  || plan.archiveMemoryIds.length > 0
  || plan.ignoreCandidateIds.length > 0
  || plan.supersedeSuggestions.length > 0;

// PersistedMemoryDreamPlan.summary(MemoryDreamPlanStore.kt:30-33)
export const memoryDreamPlanSummaryText = (plan: MemoryDreamPlan): string =>
  `合并 ${plan.mergeSuggestions.length} · 提升 ${plan.promoteMemoryIds.length} · ` +
  `归档 ${plan.archiveMemoryIds.length} · 替换 ${plan.supersedeSuggestions.length} · ` +
  `忽略候选 ${plan.ignoreCandidateIds.length}`;

// ===== MemoryWorkerDreamGate.kt 全文 =====

export interface MemoryDreamWorkerGate {
  enabled: boolean;
  dreamMaintenanceEnabled: boolean;
  dreamModelEnabled: boolean;
}

export const isAnyDreamEnabled = (worker: MemoryDreamWorkerGate): boolean =>
  worker.dreamMaintenanceEnabled || worker.dreamModelEnabled;

export const isDreamMaintenanceEnabled = (worker: MemoryDreamWorkerGate): boolean =>
  worker.dreamMaintenanceEnabled;

export const isDreamModelEnabled = (worker: MemoryDreamWorkerGate): boolean =>
  worker.dreamModelEnabled;

// ===== planMaintenance(Planner.kt:129-186) =====

const normalize = (text: string): string => {
  let out: string = '';
  for (const ch of text.toLowerCase()) {
    if (/[\p{L}\p{N}]/u.test(ch)) out += ch;
  }
  return out;
};

const distinctNumbers = (list: number[]): number[] => {
  const out: number[] = [];
  for (const n of list) {
    if (out.indexOf(n) < 0) out.push(n);
  }
  return out;
};

const distinctStrings = (list: string[]): string[] => {
  const out: string[] = [];
  for (const s of list) {
    if (out.indexOf(s) < 0) out.push(s);
  }
  return out;
};

// A previously merged/superseded record was deliberately restored by the user.
// Preserve that decision rather than merging the same related versions again.
const hasRelatedMemoryVersions = (records: MemoryRecord[]): boolean =>
  records.some((record: MemoryRecord): boolean => records.some((other: MemoryRecord): boolean =>
    record.id !== other.id && record.supersedesIds.indexOf(other.id) >= 0));

export const planDreamMaintenance = (
  records: MemoryRecord[], candidates: MemoryCandidate[],
  now: number = Date.now(),
): MemoryDreamPlan => {
  const activeRecords: MemoryRecord[] = records.filter(
    (r: MemoryRecord): boolean => isMemoryActive(r, now) && r.kind !== 'topic');
  const expiredProjects: MemoryRecord[] = records.filter((record: MemoryRecord): boolean =>
    !record.archived && record.kind !== 'topic'
    && ((record.expiresAt !== null && record.expiresAt <= now)
      || shouldArchiveIdleMemory(record, now)));
  // duplicateGroups(:140-157)— groupBy 保插入序;组排序 pinned>long_term>confidence>updatedAt
  const groups: Map<string, MemoryRecord[]> = new Map<string, MemoryRecord[]>();
  for (const r of activeRecords) {
    if (r.scope === 'core' || r.pinned) continue;
    const key: string = `${r.scope}/${r.kind}/${r.content.trim()}`;
    const list: MemoryRecord[] = groups.get(key) ?? [];
    list.push(r);
    groups.set(key, list);
  }
  const duplicateGroups: MemoryMergeSuggestion[] = [];
  for (const group of groups.values()) {
    if (group.length <= 1 || group[0].content.length < 8) continue;
    if (hasRelatedMemoryVersions(group)) continue;
    const sorted: MemoryRecord[] = [...group].sort((a: MemoryRecord, b: MemoryRecord): number => {
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
      const aLong: boolean = a.scope === 'long_term';
      const bLong: boolean = b.scope === 'long_term';
      if (aLong !== bLong) return aLong ? -1 : 1;
      if (a.confidence !== b.confidence) return b.confidence - a.confidence;
      return b.updatedAt - a.updatedAt;
    });
    duplicateGroups.push({
      targetMemoryId: sorted[0].id,
      duplicateMemoryIds: sorted.slice(1).map((r: MemoryRecord): number => r.id),
      mergedContent: sorted[0].content,
      reason: '内容高度重复，保留可信度或层级更高的一条。',
    });
  }
  const promoteIds: number[] = activeRecords
    .filter((record: MemoryRecord): boolean =>
      canPromoteMemory(record, now))
    .map((r: MemoryRecord): number => r.id);
  const activeNormalized: Set<string> = new Set<string>(
    activeRecords.map((r: MemoryRecord): string => normalize(r.content)));
  const noisyCandidateIds: string[] = candidates
    .filter((candidate: MemoryCandidate): boolean =>
      candidate.kind !== 'topic' && (candidate.content.trim().length < 12
      || candidate.confidence < 0.45
      || activeNormalized.has(normalize(candidate.content))))
    .map((c: MemoryCandidate): string => c.id);
  const notes: string[] = [];
  if (duplicateGroups.length > 0) {
    notes.push(`发现 ${duplicateGroups.length} 组可能重复的记忆，可合并后归档副本。`);
  }
  if (promoteIds.length > 0) {
    notes.push(`发现 ${promoteIds.length} 条反复使用的短期记忆，经至少两次实际引用或确认且跨越 14 天，可提升为长期记忆。`);
  }
  if (expiredProjects.length > 0) {
    notes.push(`发现 ${expiredProjects.length} 条过期或闲置记忆，可归档。`);
  }
  if (noisyCandidateIds.length > 0) {
    notes.push(`发现 ${noisyCandidateIds.length} 条低价值或重复候选，可忽略。`);
  }
  return {
    mergeSuggestions: duplicateGroups,
    promoteMemoryIds: distinctNumbers(promoteIds),
    archiveMemoryIds: distinctNumbers(expiredProjects.map((r: MemoryRecord): number => r.id)),
    ignoreCandidateIds: distinctStrings(noisyCandidateIds),
    supersedeSuggestions: [],
    notes,
  };
};

// ===== parseModelPlanJson(Planner.kt:191-265) =====

const contentOrNull = (v: unknown): string | null => {
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
};

const intOrNull = (v: unknown): number | null => {
  const text: string | null = contentOrNull(v);
  if (text === null || !/^-?\d+$/.test(text)) return null;
  const n: number = Number.parseInt(text, 10);
  return Number.isSafeInteger(n) ? n : null;
};

const floatOrNull = (v: unknown): number | null => {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string') {
    // 严格整串浮点(kotlinx floatOrNull 语义):parseFloat 前缀解析会把
    // "0.86oops" 当 0.86,污染 confidence 自动入库
    if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(v.trim())) return null;
    const n: number = Number.parseFloat(v.trim());
    return Number.isFinite(n) ? n : null;
  }
  return null;
};

const asArray = (v: unknown): unknown[] => Array.isArray(v) ? v : [];

export const parseDreamModelPlanJson = (
  raw: string, records: MemoryRecord[], candidates: MemoryCandidate[],
): MemoryDreamPlan => {
  const memoryIds: Set<number> = new Set<number>(records.filter(
    (record: MemoryRecord): boolean => record.kind !== 'topic').map((r: MemoryRecord): number => r.id));
  const candidateIds: Set<string> = new Set<string>(
    candidates.filter((candidate: MemoryCandidate): boolean => candidate.kind !== 'topic')
      .map((c: MemoryCandidate): string => c.id));
  let cleaned: string = raw.trim();
  if (cleaned.startsWith('```json')) cleaned = cleaned.substring(7);
  else if (cleaned.startsWith('```')) cleaned = cleaned.substring(3);
  if (cleaned.endsWith('```')) cleaned = cleaned.substring(0, cleaned.length - 3);
  cleaned = cleaned.trim();
  const start: number = cleaned.indexOf('{');
  const end: number = cleaned.lastIndexOf('}');
  if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
  const parsed: unknown = JSON.parse(cleaned);
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('Element is not a JsonObject');
  }
  const root: Record<string, unknown> = parsed as Record<string, unknown>;
  const merges: MemoryMergeSuggestion[] = [];
  for (const item of asArray(root['merge'])) {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) {
      throw new Error('Element is not a JsonObject');
    }
    const obj: Record<string, unknown> = item as Record<string, unknown>;
    const target: number | null = intOrNull(obj['target_memory_id']);
    if (target === null || !memoryIds.has(target)) continue;
    const duplicates: number[] = distinctNumbers(
      asArray(obj['duplicate_memory_ids'])
        .map(intOrNull)
        .filter((n: number | null): n is number =>
          n !== null && memoryIds.has(n) && n !== target));
    if (duplicates.length === 0) continue;
    merges.push({
      targetMemoryId: target,
      duplicateMemoryIds: duplicates,
      mergedContent: contentOrNull(obj['merged_content']),
      reason: contentOrNull(obj['reason']) ?? '',
    });
  }
  const supersedes: MemorySupersedeSuggestion[] = [];
  for (const item of asArray(root['supersede'])) {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) {
      throw new Error('Element is not a JsonObject');
    }
    const obj: Record<string, unknown> = item as Record<string, unknown>;
    const oldIds: number[] = distinctNumbers(
      asArray(obj['old_memory_ids'])
        .map(intOrNull)
        .filter((n: number | null): n is number => n !== null && memoryIds.has(n)));
    if (oldIds.length === 0) continue;
    const newContentRaw: string | null = contentOrNull(obj['new_content']);
    const newContent: string | null = newContentRaw !== null ? newContentRaw.trim() : null;
    if (newContent === null || newContent.length < 8) continue;
    if (contentOrNull(obj['kind']) === 'topic') continue;
    supersedes.push({
      oldMemoryIds: oldIds,
      newContent,
      scope: memoryScopeFromWireName(contentOrNull(obj['scope'])),
      kind: memoryKindFromWireName(contentOrNull(obj['kind'])),
      confidence: floatOrNull(obj['confidence']) ?? 0.7,
      reason: contentOrNull(obj['reason']) ?? '',
    });
  }
  return {
    mergeSuggestions: merges,
    promoteMemoryIds: distinctNumbers(
      asArray(root['promote']).map(intOrNull)
        .filter((n: number | null): n is number => n !== null && memoryIds.has(n))),
    archiveMemoryIds: distinctNumbers(
      asArray(root['archive']).map(intOrNull)
        .filter((n: number | null): n is number => n !== null && memoryIds.has(n))),
    ignoreCandidateIds: distinctStrings(
      asArray(root['delete_suggestions']).map(contentOrNull)
        .filter((s: string | null): s is string => s !== null && candidateIds.has(s))),
    supersedeSuggestions: supersedes,
    notes: asArray(root['notes']).map(contentOrNull)
      .filter((s: string | null): s is string => s !== null)
      .map((s: string): string => s.slice(0, 240))
      .slice(0, 6),
  };
};

// ===== mergeWith(Planner.kt:269-285) =====

export const mergeDreamPlanWith = (
  local: MemoryDreamPlan, other: MemoryDreamPlan | null,
): MemoryDreamPlan => {
  if (other === null) return local;
  const localTargets: Set<string> = new Set<string>(local.mergeSuggestions.map(
    (s: MemoryMergeSuggestion): string =>
      `${s.targetMemoryId}|${[...s.duplicateMemoryIds].sort((a: number, b: number): number => a - b).join(',')}`));
  const modelMerges: MemoryMergeSuggestion[] = other.mergeSuggestions.filter(
    (s: MemoryMergeSuggestion): boolean => !localTargets.has(
      `${s.targetMemoryId}|${[...s.duplicateMemoryIds].sort((a: number, b: number): number => a - b).join(',')}`));
  // supersede distinctBy(oldMemoryIds.toSet() to newContent) 保序
  const supersedeSeen: Set<string> = new Set<string>();
  const supersedeMerged: MemorySupersedeSuggestion[] = [];
  for (const s of [...local.supersedeSuggestions, ...other.supersedeSuggestions]) {
    const key: string =
      `${[...s.oldMemoryIds].sort((a: number, b: number): number => a - b).join(',')}|${s.newContent}`;
    if (!supersedeSeen.has(key)) {
      supersedeSeen.add(key);
      supersedeMerged.push(s);
    }
  }
  return {
    mergeSuggestions: [...local.mergeSuggestions, ...modelMerges].slice(0, 12),
    promoteMemoryIds: distinctNumbers(
      [...local.promoteMemoryIds, ...other.promoteMemoryIds]).slice(0, 24),
    archiveMemoryIds: distinctNumbers(
      [...local.archiveMemoryIds, ...other.archiveMemoryIds]).slice(0, 48),
    ignoreCandidateIds: distinctStrings(
      [...local.ignoreCandidateIds, ...other.ignoreCandidateIds]).slice(0, 48),
    supersedeSuggestions: supersedeMerged.slice(0, 12),
    notes: distinctStrings([...local.notes, ...other.notes]).slice(0, 12),
  };
};

// ===== plan 编排(Planner.kt:39-100) =====

export type MemoryDaydreamModelResolution =
  | { kind: 'ok'; modelId: string }
  | { kind: 'unavailable' };

export interface MemoryDreamPlannerDeps {
  worker: MemoryDreamWorkerGate;
  getAllRecords: () => Promise<MemoryRecord[]>;
  getPendingCandidates: () => Promise<MemoryCandidate[]>;
  // :80 !worker.enabled || !isModelDreamEnabled → null;模型/provider 空 → null
  //   (合并为 unavailable — Android 三处均 return null,无区分消费)
  resolveDaydreamModel: () => Promise<MemoryDaydreamModelResolution>;
  generateText: (prompt: string) => Promise<string>;
  addEvent: (event: MemoryEvent) => Promise<void>;
  now?: () => number;
}

export const runMemoryDreamPlan = async (
  deps: MemoryDreamPlannerDeps,
): Promise<MemoryDreamPlan> => {
  const nowFn: () => number = deps.now ?? ((): number => Date.now());
  const records: MemoryRecord[] = await deps.getAllRecords();
  const candidates: MemoryCandidate[] = await deps.getPendingCandidates();
  const localPlan: MemoryDreamPlan = isDreamMaintenanceEnabled(deps.worker)
    ? planDreamMaintenance(records, candidates, nowFn())
    : makeMemoryDreamPlan();
  // planWithModel(:74-100)runCatching → getOrNull(:49-51)
  let modelPlan: MemoryDreamPlan | null = null;
  try {
    const proposed: MemoryDreamPlan | null = await planDreamWithModel(deps, records, candidates);
    modelPlan = proposed === null ? null : dreamPlanOnlyApplicable(proposed, records, nowFn());
  } catch {
    modelPlan = null;
  }
  const plan: MemoryDreamPlan = dreamPlanOnlyApplicable(mergeDreamPlanWith(localPlan, modelPlan), records, nowFn());
  // DREAM_PLANNED 事件(:54-70)— source 四分支逐字
  let source: string = 'none';
  if (memoryDreamPlanHasChanges(localPlan) && modelPlan !== null
    && memoryDreamPlanHasChanges(modelPlan)) {
    source = 'merged';
  } else if (modelPlan !== null && memoryDreamPlanHasChanges(modelPlan)) {
    source = 'model';
  } else if (memoryDreamPlanHasChanges(localPlan)) {
    source = 'maintenance';
  }
  await deps.addEvent(makeMemoryEvent({
    type: 'dream_planned',
    message: `merge=${plan.mergeSuggestions.length}, promote=${plan.promoteMemoryIds.length}, ` +
      `archive=${plan.archiveMemoryIds.length}, supersede=${plan.supersedeSuggestions.length}, ` +
      `ignore=${plan.ignoreCandidateIds.length}, source=${source}`,
  }));
  return plan;
};

const planDreamWithModel = async (
  deps: MemoryDreamPlannerDeps,
  records: MemoryRecord[], candidates: MemoryCandidate[],
): Promise<MemoryDreamPlan | null> => {
  if (!deps.worker.enabled || !isDreamModelEnabled(deps.worker)) return null;
  const resolution: MemoryDaydreamModelResolution = await deps.resolveDaydreamModel();
  if (resolution.kind !== 'ok') return null;
  const prompt: string = buildMemoryDreamPrompt(
    records.filter((r: MemoryRecord): boolean => isMemoryActive(r, (deps.now ?? Date.now)()) && r.kind !== 'topic').slice(0, 80),
    candidates.filter((candidate: MemoryCandidate): boolean => candidate.kind !== 'topic').slice(0, 50),
    (deps.now ?? Date.now)());
  const text: string = await deps.generateText(prompt);
  return parseDreamModelPlanJson(text, records, candidates);
};

// ===== Applier(MemoryDreamApplier.kt 全文) =====

const dreamIsManaged = (r: MemoryRecord, now: number): boolean =>
  isMemoryActive(r, now) && r.scope !== 'core' && r.kind !== 'topic';

const dreamCanBeSuperseded = (r: MemoryRecord, now: number): boolean =>
  dreamIsManaged(r, now) && !r.pinned && !isSensitiveMemoryContent(r.content);

// managedMemoryComparator(:185-188)— long_term>pinned>confidence>updatedAt
const managedCompare = (a: MemoryRecord, b: MemoryRecord): number => {
  const aLong: boolean = a.scope === 'long_term';
  const bLong: boolean = b.scope === 'long_term';
  if (aLong !== bLong) return aLong ? -1 : 1;
  if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
  if (a.confidence !== b.confidence) return b.confidence - a.confidence;
  return b.updatedAt - a.updatedAt;
};

// onlyApplicableToManagedMemories(:112-172)
export const dreamPlanOnlyApplicable = (
  plan: MemoryDreamPlan, records: MemoryRecord[], now: number = Date.now(),
): MemoryDreamPlan => {
  const byId: Map<number, MemoryRecord> = new Map<number, MemoryRecord>(
    records.map((r: MemoryRecord): [number, MemoryRecord] => [r.id, r]));
  const supersedeSuggestions: MemorySupersedeSuggestion[] = [];
  for (const suggestion of plan.supersedeSuggestions) {
    if (suggestion.scope === 'core') continue;
    if (suggestion.kind === 'note') continue;
    if (suggestion.kind === 'topic') continue;
    if (suggestion.confidence < 0.70) continue;
    if (suggestion.newContent.trim().length < 8) continue;
    if (isSensitiveMemoryContent(suggestion.newContent)) continue;
    const oldRecords: MemoryRecord[] = [];
    const seenIds: Set<number> = new Set<number>();
    for (const id of suggestion.oldMemoryIds) {
      const r: MemoryRecord | undefined = byId.get(id);
      if (r !== undefined && dreamCanBeSuperseded(r, now) && !seenIds.has(r.id)) {
        seenIds.add(r.id);
        oldRecords.push(r);
      }
    }
    if (oldRecords.length === 0) continue;
    supersedeSuggestions.push({
      oldMemoryIds: oldRecords.map((r: MemoryRecord): number => r.id),
      newContent: suggestion.newContent.trim(),
      scope: suggestion.scope,
      kind: suggestion.kind,
      confidence: Math.min(1, Math.max(0, suggestion.confidence)),
      reason: suggestion.reason,
    });
  }
  const supersededIds: Set<number> = new Set<number>(
    supersedeSuggestions.flatMap((s: MemorySupersedeSuggestion): number[] => s.oldMemoryIds));
  const mergeSuggestions: MemoryMergeSuggestion[] = [];
  for (const suggestion of plan.mergeSuggestions) {
    const candidates: MemoryRecord[] = [];
    const seenIds: Set<number> = new Set<number>();
    for (const id of [suggestion.targetMemoryId, ...suggestion.duplicateMemoryIds]) {
      const r: MemoryRecord | undefined = byId.get(id);
      if (r !== undefined && dreamIsManaged(r, now) && !r.pinned && !supersededIds.has(r.id) && !seenIds.has(r.id)) {
        seenIds.add(r.id);
        candidates.push(r);
      }
    }
    if (candidates.length < 2) continue;
    if (hasRelatedMemoryVersions(candidates)) continue;
    if (candidates.some((record: MemoryRecord): boolean =>
      record.kind !== candidates[0].kind || record.scope !== candidates[0].scope)) continue;
    const target: MemoryRecord = [...candidates].sort(managedCompare)[0];
    const duplicateIds: number[] = candidates
      .filter((r: MemoryRecord): boolean => r.id !== target.id)
      .map((r: MemoryRecord): number => r.id);
    if (duplicateIds.length === 0) continue;
    mergeSuggestions.push({
      targetMemoryId: target.id,
      duplicateMemoryIds: duplicateIds,
      mergedContent: suggestion.mergedContent,
      reason: suggestion.reason,
    });
  }
  const mergeIds: Set<number> = new Set<number>(
    mergeSuggestions.flatMap((s: MemoryMergeSuggestion): number[] =>
      [s.targetMemoryId, ...s.duplicateMemoryIds]));
  const promoteMemoryIds: number[] = distinctNumbers(plan.promoteMemoryIds
    .map((id: number): MemoryRecord | undefined => byId.get(id))
    .filter((r: MemoryRecord | undefined): r is MemoryRecord =>
      r !== undefined && canPromoteMemory(r, now) && !mergeIds.has(r.id) && !supersededIds.has(r.id))
    .map((r: MemoryRecord): number => r.id));
  return {
    mergeSuggestions,
    promoteMemoryIds,
    archiveMemoryIds: distinctNumbers(plan.archiveMemoryIds
      .map((id: number): MemoryRecord | undefined => byId.get(id))
      .filter((r: MemoryRecord | undefined): r is MemoryRecord =>
        r !== undefined && r.kind !== 'topic' && !r.archived && (r.invalidatedAt ?? null) === null
        && (r.scope === 'short_term' && !r.pinned || r.expiresAt !== null && r.expiresAt <= now)
        && !mergeIds.has(r.id) && !supersededIds.has(r.id) && promoteMemoryIds.indexOf(r.id) < 0)
      .map((r: MemoryRecord): number => r.id)),
    ignoreCandidateIds: distinctStrings(plan.ignoreCandidateIds),
    supersedeSuggestions,
    notes: plan.notes,
  };
};

// summaryText(:180-183)
const dreamAppliedSummaryText = (plan: MemoryDreamPlan, prefix: string): string =>
  `${prefix}: merge=${plan.mergeSuggestions.length}, promote=${plan.promoteMemoryIds.length}, ` +
  `archive=${plan.archiveMemoryIds.length}, supersede=${plan.supersedeSuggestions.length}, ` +
  `ignore=${plan.ignoreCandidateIds.length}`;

export interface MemoryDreamApplierDeps {
  getAllRecords: () => Promise<MemoryRecord[]>;
  getAllCandidates: () => Promise<MemoryCandidate[]>;
  upsertRecord: (record: MemoryRecord) => Promise<MemoryRecord>;
  addMemory: (params: MemoryAddParams) => Promise<MemoryRecord>;
  updateCandidate: (candidate: MemoryCandidate) => Promise<void>;
  addEvent: (event: MemoryEvent) => Promise<void>;
  now?: () => number;
}

const logDreamEvent = (
  deps: MemoryDreamApplierDeps, type: MemoryEventType, memoryId: number | null, message: string,
): Promise<void> => deps.addEvent(makeMemoryEvent({ type, memoryId, message }));

// apply(:16-110)— 返回 applicablePlan;无变更早退(不打 DREAM_APPLIED)
export const runMemoryDreamApply = async (
  plan: MemoryDreamPlan, deps: MemoryDreamApplierDeps,
): Promise<MemoryDreamPlan> => {
  const records: MemoryRecord[] = await deps.getAllRecords();
  const byId: Map<number, MemoryRecord> = new Map<number, MemoryRecord>(
    records.map((r: MemoryRecord): [number, MemoryRecord] => [r.id, r]));
  const now: number = (deps.now ?? Date.now)();
  const applicablePlan: MemoryDreamPlan = dreamPlanOnlyApplicable(plan, records, now);
  if (!memoryDreamPlanHasChanges(applicablePlan)) return applicablePlan;

  for (const suggestion of applicablePlan.mergeSuggestions) {
    const target: MemoryRecord | undefined = byId.get(suggestion.targetMemoryId);
    if (target === undefined) continue;
    const trimmed: string | null = suggestion.mergedContent !== null
      ? suggestion.mergedContent.trim() : null;
    const mergedContent: string = trimmed !== null && trimmed.length >= 8
      ? trimmed : target.content;
    const duplicateRecords: MemoryRecord[] = suggestion.duplicateMemoryIds
      .map((id: number): MemoryRecord | undefined => byId.get(id))
      .filter((record: MemoryRecord | undefined): record is MemoryRecord => record !== undefined);
    await deps.upsertRecord({ ...target, content: mergedContent,
      supersedesIds: distinctNumbers([...target.supersedesIds, ...suggestion.duplicateMemoryIds,
        ...duplicateRecords.flatMap((record: MemoryRecord): number[] => record.supersedesIds)]) });
    await logDreamEvent(deps, 'memory_updated', target.id, 'Updated by dream merge.');
    for (const duplicateId of suggestion.duplicateMemoryIds) {
      const duplicate: MemoryRecord | undefined = byId.get(duplicateId);
      if (duplicate === undefined) continue;
      await deps.upsertRecord({ ...duplicate, archived: true });
      await logDreamEvent(deps, 'memory_archived', duplicateId,
        'Archived duplicate by dream merge.');
    }
  }

  for (const id of applicablePlan.promoteMemoryIds) {
    const record: MemoryRecord | undefined = byId.get(id);
    if (record === undefined) continue;
    if (record.scope === 'short_term') {
      await deps.upsertRecord({
        ...record, scope: 'long_term', assistantId: '__long_term__',
      });
      await logDreamEvent(deps, 'memory_updated', id, 'Promoted by dream cleanup.');
    }
  }

  for (const id of applicablePlan.archiveMemoryIds) {
    const record: MemoryRecord | undefined = byId.get(id);
    if (record === undefined) continue;
    await deps.upsertRecord({ ...record, archived: true });
    await logDreamEvent(deps, 'memory_archived', id, 'Archived by dream cleanup.');
  }

  for (const suggestion of applicablePlan.supersedeSuggestions) {
    const oldRecords: MemoryRecord[] = suggestion.oldMemoryIds
      .map((id: number): MemoryRecord | undefined => byId.get(id))
      .filter((r: MemoryRecord | undefined): r is MemoryRecord => r !== undefined);
    if (oldRecords.length === 0) continue;
    const sourceConversationId: string | null =
      oldRecords.find((r: MemoryRecord): boolean => r.sourceConversationId !== null)
        ?.sourceConversationId ?? null;
    const newRecord: MemoryRecord = await deps.addMemory({
      scope: suggestion.scope,
      kind: suggestion.kind,
      content: suggestion.newContent,
      sourceConversationId,
      sourceMessageIds: distinctStrings(
        oldRecords.flatMap((r: MemoryRecord): string[] => r.sourceMessageIds)),
      supersedesIds: distinctNumbers(oldRecords.map((r: MemoryRecord): number => r.id)),
      expiresAt: null,
      confidence: suggestion.confidence,
    });
    await logDreamEvent(deps, 'memory_created', newRecord.id,
      `Superseded memories: ${oldRecords.map((r: MemoryRecord): number => r.id).join(',')}.`);
    for (const oldRecord of oldRecords) {
      await deps.upsertRecord({ ...oldRecord, archived: true });
      await logDreamEvent(deps, 'memory_archived', oldRecord.id,
        `Archived by dream supersede -> new #${newRecord.id}.`);
    }
  }

  const candidates: MemoryCandidate[] = await deps.getAllCandidates();
  const candidatesById: Map<string, MemoryCandidate> = new Map<string, MemoryCandidate>(
    candidates.map((c: MemoryCandidate): [string, MemoryCandidate] => [c.id, c]));
  for (const id of applicablePlan.ignoreCandidateIds) {
    const candidate: MemoryCandidate | undefined = candidatesById.get(id);
    if (candidate === undefined || candidate.kind === 'topic') continue;
    await deps.updateCandidate({ ...candidate, status: 'ignored' });
  }

  await logDreamEvent(deps, 'dream_applied', null,
    dreamAppliedSummaryText(applicablePlan, 'Applied dream diff'));
  return applicablePlan;
};

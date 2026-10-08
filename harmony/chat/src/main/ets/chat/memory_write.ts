// memory_write — MemoryRepository.kt 写路径语义纯函数(D-085c)
//
// Android 基准: core/memory/store/MemoryRepository.kt
//   - addMemory(:102-133):十参,confidence coerce 0-1,supersedesIds distinct,
//     archived=false,createdAt/updatedAt=now;id = Room 自增(→ max(id)+1,空库 1)
//   - addMemory(assistantId, content)(:91-100):scope=scopeForBucket,
//     kind = SHORT_TERM→PROJECT 否则 NOTE
//   - updateContent(:84-89):不存在 → error("Memory record #$id not found"),
//     copy(content, updatedAt=now)
//   - deleteMemory(:145-147)
//   - scopeForBucket(:328-333,else→LONG_TERM)/bucketForScope(:336-340)
//   - toAssistantMemory(:218-227)
//   - getMemoriesOfAssistant(DAO :16-17 — WHERE assistant_id = :id,无 ORDER BY)
// 全部为数组变换(存储无关);entry JSON 文件层读写 + 串行化在 MemoryStore.ets。

import type { AssistantMemory } from './builtin_memory_tools.ts';
import type { MemoryKind, MemoryRecord, MemoryScope } from './memory_models.ts';
import { makeMemoryRecord } from './memory_models.ts';
import { invalidateMemoryTopicsForSource, pruneMemoryTopics } from './memory_topics.ts';

// MemoryRepository.kt:26-30 伴生常量
export const MEMORY_BUCKET_GLOBAL: string = '__global__';
export const MEMORY_BUCKET_SHORT_TERM: string = '__short_term__';
export const MEMORY_BUCKET_LONG_TERM: string = '__long_term__';

// bucketForScope(:336-340)
export const memoryBucketForScope = (scope: MemoryScope): string => {
  if (scope === 'core') return MEMORY_BUCKET_GLOBAL;
  if (scope === 'short_term') return MEMORY_BUCKET_SHORT_TERM;
  return MEMORY_BUCKET_LONG_TERM;
};

// scopeForBucket(:328-333)— else 分支 → long_term(逐字,未知桶不落 core)
export const memoryScopeForBucket = (assistantId: string): MemoryScope => {
  if (assistantId === MEMORY_BUCKET_GLOBAL) return 'core';
  if (assistantId === MEMORY_BUCKET_SHORT_TERM) return 'short_term';
  return 'long_term';
};

// addMemory(assistantId, content)(:91-100)的 scope/kind 推导
export const memoryKindForBucketAdd = (assistantId: string): MemoryKind =>
  memoryScopeForBucket(assistantId) === 'short_term' ? 'project' : 'note';

export interface MemoryAddParams {
  scope: MemoryScope;
  kind: MemoryKind;
  content: string;
  assistantId?: string;           // 默认 bucketForScope(scope)(:106)
  sourceConversationId?: string | null;
  sourceMessageIds?: string[];
  supersedesIds?: number[];
  expiresAt?: number | null;
  confidence?: number;            // 默认 1(:111),coerce 0-1(:125)
  pinned?: boolean;
  evidence?: string | null;
  reinforcementCount?: number;
  lastReinforcedAt?: number | null;
  lastReinforcementSource?: string | null;
}

export interface MemoryWriteResult {
  records: MemoryRecord[];
  record: MemoryRecord;
}

// Room 自增:max(id)+1;空库首 id = 1
export const nextMemoryId = (records: MemoryRecord[]): number => {
  let max = 0;
  for (const r of records) {
    if (r.id > max) max = r.id;
  }
  return max + 1;
};

const coerce01 = (v: number): number => Math.min(1, Math.max(0, v));

// addMemory(:102-133)— insert 后 getMemoryById 回读(本实现即所插记录);
//   supersedesIds distinct 保序(Kotlin distinct = 首次出现序)
export const appendMemoryRecord = (
  records: MemoryRecord[], params: MemoryAddParams, now: number,
): MemoryWriteResult => {
  if (params.kind === 'topic') throw new Error('主题只能通过主题聚合生成');
  const supersedes: number[] = [];
  for (const id of (params.supersedesIds ?? [])) {
    if (supersedes.indexOf(id) < 0) supersedes.push(id);
  }
  const record: MemoryRecord = makeMemoryRecord({
    id: nextMemoryId(records),
    content: params.content,
    scope: params.scope,
    kind: params.kind,
    assistantId: params.assistantId ?? memoryBucketForScope(params.scope),
    sourceConversationId: params.sourceConversationId ?? null,
    sourceMessageIds: params.sourceMessageIds ?? [],
    supersedesIds: supersedes,
    expiresAt: params.expiresAt ?? null,
    confidence: coerce01(params.confidence ?? 1),
    pinned: params.pinned ?? false,
    evidence: params.evidence ?? null,
    reinforcementCount: params.reinforcementCount ?? 0,
    lastReinforcedAt: params.lastReinforcedAt ?? null,
    lastReinforcementSource: params.lastReinforcementSource ?? null,
    archived: false,
    createdAt: now,
    updatedAt: now,
    lastUsedAt: null,
  });
  return { records: [...records, record], record };
};

// updateContent(:84-89)— 未命中抛错(消息逐字),命中 copy(content, updatedAt=now)
export const updateMemoryRecordContent = (
  records: MemoryRecord[], id: number, content: string, now: number,
): MemoryWriteResult => {
  const index: number = records.findIndex((r: MemoryRecord): boolean => r.id === id);
  if (index < 0) throw new Error(`Memory record #${id} not found`);
  if (records[index].kind === 'topic') throw new Error('主题摘要只读，请重新聚合');
  const updated: MemoryRecord = { ...records[index], content, updatedAt: now };
  const next: MemoryRecord[] = [...records];
  next[index] = updated;
  return { records: records[index].content === content ? next
    : invalidateMemoryTopicsForSource(next, id, now), record: updated };
};

// deleteMemory(:145-147)— 未命中 = SQL DELETE 0 行(no-op,不抛错)
export const deleteMemoryRecord = (
  records: MemoryRecord[], id: number, now: number = Date.now(),
): MemoryRecord[] => {
  if (records.find((record: MemoryRecord): boolean => record.id === id)?.kind === 'topic') {
    throw new Error('删除主题请使用主题专用操作');
  }
  const next: MemoryRecord[] = records.filter((record: MemoryRecord): boolean => record.id !== id);
  return pruneMemoryTopics(invalidateMemoryTopicsForSource(next, id, now), now).records;
};

// toAssistantMemory(:218-227)
export const memoryRecordToAssistantMemory = (record: MemoryRecord): AssistantMemory => ({
  id: record.id,
  content: record.content,
  scope: record.scope,
  kind: record.kind,
  expiresAt: record.expiresAt,
  confidence: record.confidence,
  pinned: record.pinned,
  archived: record.archived,
});

// getMemoriesOfAssistant(DAO :16-17)— assistant_id 精确匹配,插入序
export const memoriesOfAssistant = (
  records: MemoryRecord[], assistantId: string,
): AssistantMemory[] => records
  .filter((r: MemoryRecord): boolean => r.assistantId === assistantId && r.kind !== 'topic')
  .map(memoryRecordToAssistantMemory);

// upsertRecord(:135-143)+ toEntity(:247-263)映射 —
//   id==0 → insert(回读所插);否则 update(updatedAt=now 覆盖);
//   实体映射:assistantId 空白→bucketForScope、supersedesIds distinct、
//   confidence coerce 0-1、createdAt ≤0 → now、updatedAt 恒 = now
export const upsertMemoryRecord = (
  records: MemoryRecord[], record: MemoryRecord, now: number,
): MemoryWriteResult => {
  const original: MemoryRecord | undefined = records.find((r: MemoryRecord): boolean => r.id === record.id);
  if (record.kind === 'topic' || original?.kind === 'topic') {
    throw new Error('普通记忆写入不能创建或覆盖主题');
  }
  const supersedes: number[] = [];
  for (const id of record.supersedesIds) {
    if (supersedes.indexOf(id) < 0) supersedes.push(id);
  }
  const mapped: MemoryRecord = makeMemoryRecord({
    id: record.id,
    content: record.content,
    scope: record.scope,
    kind: record.kind,
    assistantId: record.assistantId.trim().length > 0
      ? record.assistantId : memoryBucketForScope(record.scope),
    sourceConversationId: record.sourceConversationId,
    sourceMessageIds: record.sourceMessageIds,
    supersedesIds: supersedes,
    expiresAt: record.expiresAt,
    confidence: Math.min(1, Math.max(0, record.confidence)),
    pinned: record.pinned,
    archived: record.archived,
    createdAt: record.createdAt > 0 ? record.createdAt : now,
    updatedAt: now,
    lastUsedAt: record.lastUsedAt,
    evidence: record.evidence ?? null,
    reinforcementCount: record.reinforcementCount ?? 0,
    lastReinforcedAt: record.lastReinforcedAt ?? null,
    lastReinforcementSource: record.lastReinforcementSource ?? null,
    invalidatedAt: record.invalidatedAt ?? null,
    topicTitle: record.topicTitle ?? null,
    memberIds: record.memberIds ?? [],
  });
  if (record.id === 0) {
    const withId: MemoryRecord = { ...mapped, id: nextMemoryId(records) };
    return { records: [...records, withId], record: withId };
  }
  const index: number = records.findIndex((r: MemoryRecord): boolean => r.id === record.id);
  if (index < 0) {
    // Room @Update 未命中 = 0 行(Android 随后 getMemoryById → null → 回传入参)
    return { records, record: mapped };
  }
  const next: MemoryRecord[] = [...records];
  next[index] = mapped;
  const changedSource: boolean = original !== undefined && (
    original.content !== mapped.content || original.kind !== mapped.kind || original.scope !== mapped.scope
    || original.archived !== mapped.archived || original.expiresAt !== mapped.expiresAt
    || original.invalidatedAt !== mapped.invalidatedAt);
  return { records: pruneMemoryTopics(changedSource
    ? invalidateMemoryTopicsForSource(next, record.id, now) : next, now).records, record: mapped };
};

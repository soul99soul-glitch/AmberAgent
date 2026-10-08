import type { MemoryRecord } from './memory_models.ts';
import { invalidateMemoryTopicsForSource, pruneMemoryTopics } from './memory_topics.ts';

export const restoreMemoryRecord = (records: MemoryRecord[], id: number, now: number): MemoryRecord[] => {
  const row: MemoryRecord | undefined = records.find((record: MemoryRecord): boolean => record.id === id);
  if (row === undefined) throw new Error(`Memory record #${id} not found`);
  if (row.kind === 'topic') throw new Error('主题摘要只读，请重新聚合');
  const restored: MemoryRecord = { ...row, archived: false, invalidatedAt: null,
    expiresAt: row.expiresAt !== null && row.expiresAt <= now ? null : row.expiresAt, updatedAt: now };
  const next: MemoryRecord[] = records.map((record: MemoryRecord): MemoryRecord => record.id === id ? restored : record);
  return pruneMemoryTopics(invalidateMemoryTopicsForSource(next, id, now), now).records;
};

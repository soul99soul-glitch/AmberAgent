import type { MemoryRecord } from './memory_models.ts';

export const MEMORY_PROMOTION_AGE_MS: number = 14 * 86400000;
export const MEMORY_IDLE_ARCHIVE_AGE_MS: number = 30 * 86400000;

export const isMemoryActive = (record: MemoryRecord, now: number): boolean =>
  !record.archived && (record.invalidatedAt ?? null) === null
  && (record.expiresAt === null || record.expiresAt > now);

export const canPromoteMemory = (record: MemoryRecord, now: number): boolean => {
  const recordedAt: number = record.createdAt > 0 ? record.createdAt : record.updatedAt;
  return isMemoryActive(record, now) && record.kind !== 'topic'
    && record.scope === 'short_term' && !record.pinned && recordedAt > 0
    && (record.reinforcementCount ?? 0) >= 2
    && (record.lastReinforcedAt ?? 0) - recordedAt >= MEMORY_PROMOTION_AGE_MS;
};

export const shouldArchiveIdleMemory = (record: MemoryRecord, now: number): boolean => {
  if (!isMemoryActive(record, now) || record.scope !== 'short_term'
    || record.kind === 'topic' || record.pinned || record.expiresAt !== null) return false;
  const recordedAt: number = record.createdAt > 0 ? record.createdAt : record.updatedAt;
  if (recordedAt <= 0) return false;
  const lastActivity: number = Math.max(recordedAt, record.updatedAt,
    record.lastUsedAt ?? 0, record.lastReinforcedAt ?? 0);
  return now - lastActivity >= MEMORY_IDLE_ARCHIVE_AGE_MS;
};

export const memoryLocalDate = (timestamp: number): string => {
  const date: Date = new Date(timestamp);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
};

export const memoryDateLabel = (record: MemoryRecord): string => {
  const recordedAt: number = record.createdAt > 0 ? record.createdAt : record.updatedAt;
  let label: string = recordedAt > 0 ? `记录日期=${memoryLocalDate(recordedAt)}` : '记录日期=未知';
  if (record.expiresAt !== null) label += `; 有效期至=${memoryLocalDate(record.expiresAt)}`;
  return label;
};

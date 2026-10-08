import type { MemoryRecord } from '@amber/chat-domain';

export type MemoryListFilter = 'active' | 'archived' | 'all';

export const isActiveMemoryRecord = (record: MemoryRecord, now: number): boolean =>
  !record.archived && (record.invalidatedAt ?? null) === null && (record.expiresAt === null || record.expiresAt > now);

export const countActiveMemories = (records: MemoryRecord[], now: number): number =>
  records.filter((record: MemoryRecord): boolean => record.kind !== 'topic' && isActiveMemoryRecord(record, now)).length;

export const memoryScopeLabel = (scope: string): string => {
  switch (scope) {
    case 'core': return '核心';
    case 'short_term': return '短期';
    case 'long_term': return '长期';
    default: return scope;
  }
};

export const memoryKindLabel = (kind: string): string => {
  switch (kind) {
    case 'user': return '用户偏好';
    case 'feedback': return '反馈';
    case 'project': return '项目';
    case 'reference': return '参考';
    case 'routine': return '习惯';
    case 'note': return '笔记';
    case 'topic': return '主题';
    default: return kind;
  }
};

export const filterMemoryRecords = (records: MemoryRecord[], filter: MemoryListFilter, query: string,
  now: number): MemoryRecord[] => {
  const term: string = query.trim().toLowerCase();
  return records.filter((record: MemoryRecord): boolean => {
    if (record.kind === 'topic') return false;
    const active: boolean = isActiveMemoryRecord(record, now);
    if (filter === 'active' && !active || filter === 'archived' && active) return false;
    const searchable: string = `${record.content} ${record.evidence ?? ''} ${record.id} ${record.scope} ${record.kind} ` +
      `${memoryScopeLabel(record.scope)} ${memoryKindLabel(record.kind)} ${active ? '活跃' : '已归档 已过期'}`;
    return term.length === 0 || searchable.toLowerCase().includes(term);
  }).sort((left: MemoryRecord, right: MemoryRecord): number => right.updatedAt - left.updatedAt || right.id - left.id);
};

export const memoryEventLabel = (type: string): string => {
  switch (type) {
    case 'memory_created': return '新增';
    case 'durable_memory_created': return '持久保存';
    case 'memory_updated': return '更新';
    case 'memory_archived': return '归档';
    case 'memory_invalidated': return '作废';
    case 'memory_restored': return '恢复';
    case 'memory_expired': return '过期归档';
    case 'extraction_started': return '开始提炼';
    case 'extraction_skipped': return '跳过提炼';
    case 'extraction_failed': return '提炼失败';
    case 'candidate_created': return '生成候选';
    case 'candidate_accepted': return '接受候选';
    case 'candidate_ignored': return '忽略候选';
    case 'dream_planned': return '整理计划';
    case 'dream_applied': return '完成整理';
    case 'dream_failed': return '整理失败';
    default: return type;
  }
};

export const relativeMemoryDate = (timestamp: number, now: number): string => {
  const elapsed: number = Math.max(0, now - timestamp);
  if (elapsed < 60000) return '刚刚';
  if (elapsed < 3600000) return `${Math.floor(elapsed / 60000)} 分钟前`;
  if (elapsed < 86400000) return `${Math.floor(elapsed / 3600000)} 小时前`;
  if (elapsed < 7 * 86400000) return `${Math.floor(elapsed / 86400000)} 天前`;
  return new Date(timestamp).toLocaleDateString();
};

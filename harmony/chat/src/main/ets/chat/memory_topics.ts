import { makeMemoryRecord } from './memory_models.ts';
import type { MemoryRecord } from './memory_models.ts';

export interface MemoryTopicSuggestion {
  title: string;
  summary: string;
  memberIds: number[];
}

export interface MemoryTopicApplyResult {
  records: MemoryRecord[];
  topicIds: number[];
  changed: boolean;
  staleCount: number;
  rejectedReasons: string[];
}

export interface MemoryTopicPruneResult {
  records: MemoryRecord[];
  changed: boolean;
}

export const isMemoryTopicSource = (record: MemoryRecord, now: number): boolean =>
  record.kind !== 'topic' && record.scope !== 'core' && !record.archived && record.invalidatedAt == null
  && (record.expiresAt === null || record.expiresAt > now);

const titleKey = (title: string): string => title.replace(/\s/g, '').toLowerCase();
const uniqueIds = (ids: number[]): number[] => [...new Set<number>(ids)];

const suggestionIssue = (suggestion: MemoryTopicSuggestion): string | null => {
  if (suggestion.title.trim().length === 0 || Array.from(suggestion.title.trim()).length > 12) {
    return '主题标题须为 1–12 个字符';
  }
  if (suggestion.summary.trim().length === 0) return '主题摘要不能为空';
  if (suggestion.memberIds.some((id: number): boolean => !Number.isInteger(id) || id <= 0
    || id > 2147483647)) return '主题成员 ID 须为正整数';
  if (uniqueIds(suggestion.memberIds).length < 2) return '主题须包含至少两条不同源记忆';
  return null;
};

export const buildMemoryTopicPrompt = (records: MemoryRecord[], now: number): string => {
  const sources: MemoryRecord[] = records.filter((record: MemoryRecord): boolean =>
    isMemoryTopicSource(record, now));
  const sourceIds: Set<number> = new Set<number>(sources.map((record: MemoryRecord): number => record.id));
  const topics = records.filter((record: MemoryRecord): boolean => record.kind === 'topic' && !record.archived)
    .map((record: MemoryRecord) => ({
      title: record.topicTitle ?? '',
      memberIds: record.memberIds.filter((id: number): boolean => sourceIds.has(id)),
    }));
  return '将以下已保存的源记忆按稳定主题归组。只返回 JSON：' +
    '{"topics":[{"title":"短标题","summary":"一句主题摘要","memberIds":[1,2]}]}。\n' +
    '标题最多 12 个字符；摘要只能概括提供的事实，不得添加事实。每组至少两个不同成员，' +
    '每条源记忆最多属于一组。优先沿用已有主题标题；不能有把大小写或空白变化当新标题的重复主题。' +
    '已有主题成员若未全部列出，不要把它们改分到其他主题；无合理分组时返回 {"topics":[]}。\n' +
    '源记忆：\n' + JSON.stringify(sources.map((record: MemoryRecord) => ({
      id: record.id, scope: record.scope, kind: record.kind,
      content: Array.from(record.content).slice(0, 120).join(''),
    }))) + '\n已有主题：\n' + JSON.stringify(topics);
};

export const decodeMemoryTopicSuggestions = (raw: string): MemoryTopicSuggestion[] => {
  const cleaned: string = raw.trim().replace(/^```(?:json)?\s*/, '').replace(/\s*```$/, '');
  const parsed: unknown = JSON.parse(cleaned);
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('主题结果须为 JSON 对象');
  }
  const list: unknown = (parsed as Record<string, unknown>)['topics'];
  if (!Array.isArray(list)) throw new Error('主题结果缺少 topics 数组');
  const suggestions: MemoryTopicSuggestion[] = [];
  const titles: Set<string> = new Set<string>();
  const assigned: Set<number> = new Set<number>();
  for (const item of list) {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) {
      throw new Error('主题条目须为对象');
    }
    const obj: Record<string, unknown> = item as Record<string, unknown>;
    if (typeof obj['title'] !== 'string' || typeof obj['summary'] !== 'string'
      || !Array.isArray(obj['memberIds']) || obj['memberIds'].some((id: unknown): boolean => typeof id !== 'number')) {
      throw new Error('主题条目的 title/summary/memberIds 类型不正确');
    }
    const suggestion: MemoryTopicSuggestion = {
      title: obj['title'].trim(), summary: obj['summary'].trim(),
      memberIds: uniqueIds(obj['memberIds'] as number[]),
    };
    const issue: string | null = suggestionIssue(suggestion);
    if (issue !== null) throw new Error(issue);
    const key: string = titleKey(suggestion.title);
    if (titles.has(key)) throw new Error('主题标题重复');
    if (suggestion.memberIds.some((id: number): boolean => assigned.has(id))) {
      throw new Error('同一源记忆不能分配给多个主题');
    }
    titles.add(key);
    suggestion.memberIds.forEach((id: number): void => { assigned.add(id); });
    suggestions.push(suggestion);
  }
  return suggestions;
};

// A summary based on removed/changed members cannot remain a fresh active summary.
// Preserve the topic identity for a later model grouping to revive it.
export const invalidateMemoryTopicsForSource = (
  records: MemoryRecord[], sourceId: number, now: number,
): MemoryRecord[] => records.map((record: MemoryRecord): MemoryRecord =>
  record.kind === 'topic' && !record.archived && record.memberIds.includes(sourceId)
    ? { ...record, archived: true, updatedAt: now } : record);

export const pruneMemoryTopics = (records: MemoryRecord[], now: number): MemoryTopicPruneResult => {
  const sources: Map<number, MemoryRecord> = new Map<number, MemoryRecord>(records
    .filter((record: MemoryRecord): boolean => isMemoryTopicSource(record, now))
    .map((record: MemoryRecord): [number, MemoryRecord] => [record.id, record]));
  let changed: boolean = false;
  const next: MemoryRecord[] = records.map((record: MemoryRecord): MemoryRecord => {
    if (record.kind !== 'topic') return record;
    const memberIds: number[] = uniqueIds(record.memberIds).filter((id: number): boolean => sources.has(id));
    const membershipChanged: boolean = memberIds.length !== record.memberIds.length
      || memberIds.some((id: number, index: number): boolean => id !== record.memberIds[index]);
    const archived: boolean = record.archived || memberIds.length < 2 || membershipChanged;
    if (!membershipChanged && archived === record.archived) return record;
    changed = true;
    return { ...record, memberIds, archived, updatedAt: now };
  });
  return { records: changed ? next : records, changed };
};

const sameSource = (live: MemoryRecord, baseline: MemoryRecord): boolean =>
  live.content === baseline.content && live.updatedAt === baseline.updatedAt
  && live.kind === baseline.kind && live.scope === baseline.scope
  && live.archived === baseline.archived && live.expiresAt === baseline.expiresAt
  && (live.invalidatedAt ?? null) === (baseline.invalidatedAt ?? null);

export const applyMemoryTopicSuggestions = (
  live: MemoryRecord[], baseline: MemoryRecord[], suggestions: MemoryTopicSuggestion[], now: number,
): MemoryTopicApplyResult => {
  const pruned: MemoryTopicPruneResult = pruneMemoryTopics(live, now);
  let records: MemoryRecord[] = pruned.records;
  let changed: boolean = pruned.changed;
  let staleCount: number = 0;
  const topicIds: number[] = [];
  const rejectedReasons: string[] = [];
  const byId: Map<number, MemoryRecord> = new Map<number, MemoryRecord>(live.map(
    (record: MemoryRecord): [number, MemoryRecord] => [record.id, record]));
  const baselineById: Map<number, MemoryRecord> = new Map<number, MemoryRecord>(baseline.map(
    (record: MemoryRecord): [number, MemoryRecord] => [record.id, record]));
  const titles: Set<string> = new Set<string>();
  const assigned: Set<number> = new Set<number>();
  for (const suggestion of suggestions) {
    const title: string = suggestion.title.trim();
    const summary: string = suggestion.summary.trim();
    const key: string = titleKey(title);
    const ids: number[] = uniqueIds(suggestion.memberIds);
    const issue: string | null = suggestionIssue(suggestion);
    if (issue !== null || titles.has(key) || ids.some((id: number): boolean => assigned.has(id))) {
      rejectedReasons.push(`${title}: ${issue ?? '重复标题或跨主题成员'}`);
      continue;
    }
    titles.add(key);
    ids.forEach((id: number): void => { assigned.add(id); });
    const stale: boolean = ids.some((id: number): boolean => {
      const current: MemoryRecord | undefined = byId.get(id);
      const original: MemoryRecord | undefined = baselineById.get(id);
      return current === undefined || original === undefined || !isMemoryTopicSource(current, now)
        || !isMemoryTopicSource(original, now) || !sameSource(current, original);
    });
    if (stale) {
      staleCount++;
      rejectedReasons.push(`${title}: 源记忆已变更或失效，请重新聚合`);
      continue;
    }
    const existing: MemoryRecord | undefined = records.find((record: MemoryRecord): boolean =>
      record.kind === 'topic' && titleKey(record.topicTitle ?? '') === key);
    // Historical members are retained only if still eligible and unchanged since the model snapshot.
    const members: number[] = uniqueIds([...(existing?.memberIds ?? []), ...ids]).filter((id: number): boolean => {
      const current: MemoryRecord | undefined = byId.get(id);
      const original: MemoryRecord | undefined = baselineById.get(id);
      return current !== undefined && original !== undefined && isMemoryTopicSource(current, now)
        && isMemoryTopicSource(original, now) && sameSource(current, original);
    });
    const conflict: boolean = records.some((record: MemoryRecord): boolean =>
      record.kind === 'topic' && !record.archived && record.id !== existing?.id
      && record.memberIds.some((id: number): boolean => members.includes(id)));
    if (conflict) {
      rejectedReasons.push(`${title}: 成员已属于其他有效主题`);
      continue;
    }
    if (existing !== undefined) {
      topicIds.push(existing.id);
      const sameMembers: boolean = members.length === existing.memberIds.length
        && members.every((id: number, index: number): boolean => id === existing.memberIds[index]);
      if (!existing.archived && existing.content === summary && sameMembers) continue;
      const updated: MemoryRecord = { ...existing, content: summary, memberIds: members,
        scope: 'long_term', assistantId: '__long_term__', archived: false, updatedAt: now };
      records = records.map((record: MemoryRecord): MemoryRecord => record.id === existing.id ? updated : record);
    } else {
      const id: number = records.reduce((max: number, record: MemoryRecord): number => Math.max(max, record.id), 0) + 1;
      const created: MemoryRecord = makeMemoryRecord({ id, content: summary, scope: 'long_term', kind: 'topic',
        assistantId: '__long_term__', topicTitle: title, memberIds: members, createdAt: now, updatedAt: now });
      records = [...records, created];
      topicIds.push(id);
    }
    changed = true;
  }
  return { records, topicIds, changed, staleCount, rejectedReasons };
};

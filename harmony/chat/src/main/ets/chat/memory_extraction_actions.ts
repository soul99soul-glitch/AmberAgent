import type { UIMessage } from './message.ts';
import type { MemoryKind, MemoryRecord, MemoryScope } from './memory_models.ts';
import { appendMemoryRecord } from './memory_write.ts';
import { isSensitiveMemoryContent } from './memory_prompt_builder.ts';
import { invalidateMemoryTopicsForSource } from './memory_topics.ts';

export type MemoryExtractionActionKind = 'add' | 'update' | 'invalidate' | 'confirm' | 'noop';
export interface MemoryExtractionAction {
  action: MemoryExtractionActionKind;
  content: string;
  evidence: string;
  sourceMessageId: string;
  sourceConversationId: string;
  scope: MemoryScope;
  kind: MemoryKind;
  confidence: number;
  expiresAt: number | null;
  targetId: number | null;
  // Token captured from the records shown to the worker, never from its JSON output.
  targetUpdatedAt: number | null;
}
export interface MemoryExtractionApplyResult {
  records: MemoryRecord[];
  changed: boolean;
  added: MemoryRecord[];
  updated: MemoryRecord[];
  invalidated: MemoryRecord[];
  confirmed: MemoryRecord[];
  rejectedReasons: string[];
}
export interface MemoryExtractionSource {
  id: string;
  evidenceText: string;
  createdAt: string;
  assistantContext: string;
}
const plainText = (message: UIMessage): string => {
  const texts: string[] = [];
  for (const part of message.parts) {
    if (part.type === 'text') texts.push(part.text);
  }
  return texts.join('\n').trim();
};
export const collectMemoryExtractionSources = (
  messages: UIMessage[], sourceIds: string[],
): MemoryExtractionSource[] => {
  const sources: MemoryExtractionSource[] = [];
  let previousAssistant: string = '';
  for (const message of messages) {
    if (message.role === 'assistant') {
      const text: string = plainText(message);
      if (text.length > 0) previousAssistant = text.slice(-600);
    } else if (message.role === 'user' && sourceIds.includes(message.id)) {
      const evidenceText: string = plainText(message).slice(0, 4000);
      if (evidenceText.length > 0) sources.push({
        id: message.id, evidenceText, createdAt: message.createdAt, assistantContext: previousAssistant,
      });
    }
  }
  return sources;
};

const dayText = (date: Date): string =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
export const memoryExpiryOn = (value: string): number | null => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const year: number = Number(value.slice(0, 4));
  const month: number = Number(value.slice(5, 7));
  const day: number = Number(value.slice(8, 10));
  const date: Date = new Date(year, month - 1, day);
  if (dayText(date) !== value) return null;
  return new Date(year, month - 1, day + 1).getTime();
};
export const normalizeMemoryRelativeDates = (content: string, sourceCreatedAt: string): string => {
  // Message timestamps are local ISO strings. Preserve the recorded day even if
  // the device changed time zone since the message was sent.
  const sourceDay: string = sourceCreatedAt.slice(0, 10);
  const end: number | null = memoryExpiryOn(sourceDay);
  if (end === null) return content;
  const base: Date = new Date(Number(sourceDay.slice(0, 4)), Number(sourceDay.slice(5, 7)) - 1,
    Number(sourceDay.slice(8, 10)));
  const nextDay = (offset: number): string => dayText(new Date(base.getFullYear(), base.getMonth(), base.getDate() + offset));
  const nextMonday: number = 8 - (base.getDay() === 0 ? 7 : base.getDay());
  const weekdays: string = '一二三四五六日天';
  return content.replace(/(?:下周|下星期)([一二三四五六日天])/g, (_match: string, weekday: string): string =>
    nextDay(nextMonday + Math.min(6, weekdays.indexOf(weekday))))
    .replace(/大后天/g, nextDay(3)).replace(/后天/g, nextDay(2))
    .replace(/明天/g, nextDay(1)).replace(/今天/g, sourceDay)
    .replace(/下周|下星期/g, `${nextDay(nextMonday)}所在周`);
};

// A conservative floor beneath evidence-backed rewrites. The model may resolve
// Chinese references, but cannot introduce unsupported Latin words or numbers.
export const isGroundedMemoryRewrite = (
  content: string, evidence: string, context: string, resolvedEvidence: string = evidence,
): boolean => {
  if ((evidence.match(/[\p{L}\p{N}]/gu) ?? []).length < 4) return false;
  if (content === evidence) return true;
  const source: string = `${evidence}\n${context}\n${resolvedEvidence}`.toLowerCase();
  const tokens: string[] = content.match(/[A-Za-z][A-Za-z0-9._+-]+|\d+(?:\.\d+)?/g) ?? [];
  return tokens.every((token: string): boolean => source.includes(token.toLowerCase()));
};
const liveTarget = (record: MemoryRecord, now: number): boolean => !record.archived
  && record.invalidatedAt == null && record.kind !== 'topic'
  && (record.expiresAt === null || record.expiresAt > now);

// Pure transaction body: caller serializes this with all other writes and
// persists result.records once. Skipped actions never prevent unrelated writes.
export const applyMemoryExtractionActions = (
  original: MemoryRecord[], actions: MemoryExtractionAction[], now: number,
): MemoryExtractionApplyResult => {
  let records: MemoryRecord[] = original;
  const result: MemoryExtractionApplyResult = {
    records, changed: false, added: [], updated: [], invalidated: [], confirmed: [], rejectedReasons: [],
  };
  const handled: Set<number> = new Set<number>();
  const written: Set<string> = new Set<string>();
  for (const item of actions) {
    if (item.action === 'noop') continue;
    if (!['add', 'update', 'invalidate', 'confirm'].includes(item.action)) {
      result.rejectedReasons.push('unknown_action');
      continue;
    }
    if (item.kind === 'topic' || isSensitiveMemoryContent(item.content) || isSensitiveMemoryContent(item.evidence)) {
      result.rejectedReasons.push('sensitive_or_topic');
      continue;
    }
    let target: MemoryRecord | undefined;
    if (item.action === 'add') {
      target = records.find((record: MemoryRecord): boolean => liveTarget(record, now)
        && record.content.trim() === item.content.trim());
    } else {
      target = records.find((record: MemoryRecord): boolean => record.id === item.targetId);
      if (target === undefined || !liveTarget(target, now) || target.updatedAt !== item.targetUpdatedAt) {
        result.rejectedReasons.push('target_stale_or_missing');
        continue;
      }
    }
    const confirmation: boolean = item.action === 'confirm'
      || ((item.action === 'add' || item.action === 'update') && target !== undefined
        && item.content.trim() === target.content.trim());
    if (target !== undefined && handled.has(target.id)) continue;
    if (confirmation && target !== undefined) {
      handled.add(target.id);
      if (target.sourceMessageIds.includes(item.sourceMessageId)
        || target.lastReinforcementSource === `${item.sourceConversationId}:${item.sourceMessageId}`) continue;
      const confirmed: MemoryRecord = { ...target,
        reinforcementCount: (target.reinforcementCount ?? 0) + 1, lastReinforcedAt: now,
        lastReinforcementSource: `${item.sourceConversationId}:${item.sourceMessageId}`,
        sourceMessageIds: [...new Set<string>([...target.sourceMessageIds, item.sourceMessageId])],
        updatedAt: now,
      };
      records = records.map((record: MemoryRecord): MemoryRecord => record.id === confirmed.id ? confirmed : record);
      result.confirmed.push(confirmed);
      continue;
    }
    if (target !== undefined && (target.pinned || target.scope === 'core')) {
      result.rejectedReasons.push('user_curated_target');
      continue;
    }
    if (item.action !== 'invalidate' && item.expiresAt !== null && item.expiresAt <= now) {
      result.rejectedReasons.push('expired_write');
      continue;
    }
    if (item.action !== 'invalidate' && written.has(item.content)) continue;
    if (target !== undefined) {
      handled.add(target.id);
      const archived: MemoryRecord = { ...target, archived: true, invalidatedAt: now, updatedAt: now };
      records = records.map((record: MemoryRecord): MemoryRecord => record.id === target?.id ? archived : record);
      records = invalidateMemoryTopicsForSource(records, target.id, now);
      if (item.action === 'invalidate') { result.invalidated.push(archived); continue; }
    }
    written.add(item.content);
    const appended = appendMemoryRecord(records, {
      content: item.content, scope: target?.scope ?? item.scope, kind: target?.kind ?? item.kind,
      assistantId: target?.assistantId,
      sourceConversationId: item.sourceConversationId, sourceMessageIds: [item.sourceMessageId],
      supersedesIds: target === undefined ? [] : [target.id],
      expiresAt: item.expiresAt ?? target?.expiresAt ?? null,
      confidence: target?.confidence ?? item.confidence, evidence: item.evidence,
    }, now);
    records = appended.records;
    if (target === undefined) result.added.push(appended.record);
    else result.updated.push(appended.record);
  }
  result.records = records;
  result.changed = records !== original;
  return result;
};

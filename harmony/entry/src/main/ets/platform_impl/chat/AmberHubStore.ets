// AmberHubStore — 轻量专题中心（主题聚合对话 + 专属指令 + 近 14 天 ≥4 条相似对话智能收拢建议）

import type { ConversationSummary, KeyValueStore } from '@amber/chat-domain';
import { newId } from '@amber/chat-domain';
import { getChatKvStore } from '../../di/AppContainer.ets';

export const AMBER_HUBS_STORAGE_KEY: string = 'amber_local_hubs_v1';
const SMART_HUB_WINDOW_MS: number = 14 * 24 * 60 * 60 * 1000;
const SMART_HUB_MIN_COUNT: number = 4;

export interface AmberHubRecord {
  id: string;
  title: string;
  description: string;
  customInstruction: string;
  conversationIds: string[];
  createdAt: number;
  updatedAt: number;
}

export interface SmartHubSuggestion {
  topicLabel: string;
  matchedConversations: ConversationSummary[];
}

export interface CreateAmberHubInput {
  title: string;
  description?: string;
  customInstruction?: string;
  conversationIds?: string[];
}

const STOP_WORDS: string[] = [
  '新对话', '未命名', '关于', '如何', '怎么', '什么', '为什么', '一个', '这个', '那个',
  '问题', '讨论', '记录', '整理', '一下', '帮助', '你好', 'chat', 'help', 'with', 'the',
  'and', 'for', 'from', 'that', 'this',
];

const parseConversationTimeMs = (iso: string): number => {
  const t: number = Date.parse(iso);
  return Number.isFinite(t) ? t : 0;
};

const extractClusterKeywords = (title: string): string[] => {
  const tokens: string[] = [];
  const clean: string = title.trim();
  if (clean.length === 0) return tokens;

  const latinMatches: string[] = clean.toLowerCase().match(/[a-z0-9]{3,}/g) ?? [];
  for (const w of latinMatches) {
    if (STOP_WORDS.indexOf(w) < 0 && tokens.indexOf(w) < 0) tokens.push(w);
  }

  const cjkRuns: string[] = clean.match(/[\u4e00-\u9fff]{2,}/g) ?? [];
  for (const run of cjkRuns) {
    const trimmed: string = run.length <= 6 ? run : run.slice(0, 4);
    if (STOP_WORDS.indexOf(trimmed) < 0 && tokens.indexOf(trimmed) < 0) tokens.push(trimmed);
    if (run.length >= 4) {
      const head: string = run.slice(0, 2);
      if (STOP_WORDS.indexOf(head) < 0 && tokens.indexOf(head) < 0) tokens.push(head);
    }
  }
  return tokens;
};

export const detectSmartHubCluster = (
  summaries: ConversationSummary[],
  existingHubs: AmberHubRecord[],
  nowMs: number = Date.now(),
): SmartHubSuggestion | null => {
  const claimedIds: Set<string> = new Set<string>();
  const existingTitles: Set<string> = new Set<string>();
  for (const hub of existingHubs) {
    existingTitles.add(hub.title.trim().toLowerCase());
    for (const cid of hub.conversationIds) claimedIds.add(cid);
  }

  const recent: ConversationSummary[] = summaries.filter((item: ConversationSummary): boolean => {
    if (claimedIds.has(item.id)) return false;
    const t: number = parseConversationTimeMs(item.updateAt || item.createAt);
    return t > 0 && nowMs - t <= SMART_HUB_WINDOW_MS;
  });

  if (recent.length < SMART_HUB_MIN_COUNT) return null;

  const buckets: Map<string, ConversationSummary[]> = new Map<string, ConversationSummary[]>();
  const displayLabel: Map<string, string> = new Map<string, string>();

  for (const item of recent) {
    const kws: string[] = extractClusterKeywords(item.title);
    for (const kw of kws) {
      const key: string = kw.toLowerCase();
      if (existingTitles.has(key)) continue;
      const list: ConversationSummary[] = buckets.get(key) ?? [];
      if (list.findIndex((existing: ConversationSummary): boolean => existing.id === item.id) < 0) {
        list.push(item);
        buckets.set(key, list);
        if (!displayLabel.has(key)) displayLabel.set(key, kw);
      }
    }
  }

  let bestKey: string = '';
  let bestList: ConversationSummary[] = [];
  buckets.forEach((list: ConversationSummary[], key: string): void => {
    if (list.length < SMART_HUB_MIN_COUNT) return;
    if (list.length > bestList.length || (list.length === bestList.length && key.length > bestKey.length)) {
      bestKey = key;
      bestList = list;
    }
  });

  if (bestKey.length === 0 || bestList.length < SMART_HUB_MIN_COUNT) return null;
  const sorted: ConversationSummary[] = bestList.slice().sort(
    (a: ConversationSummary, b: ConversationSummary): number =>
      parseConversationTimeMs(b.updateAt) - parseConversationTimeMs(a.updateAt),
  );
  return {
    topicLabel: displayLabel.get(bestKey) ?? bestKey,
    matchedConversations: sorted.slice(0, 12),
  };
};

const normalizeHubList = (raw: AmberHubRecord[]): AmberHubRecord[] => {
  const out: AmberHubRecord[] = [];
  for (const item of raw) {
    if (typeof item.id !== 'string' || typeof item.title !== 'string') continue;
    const title: string = item.title.trim();
    if (title.length === 0) continue;
    const ids: string[] = Array.isArray(item.conversationIds)
      ? item.conversationIds.filter((id: string): boolean => typeof id === 'string' && id.trim().length > 0)
      : [];
    out.push({
      id: item.id,
      title,
      description: typeof item.description === 'string' ? item.description.trim() : '',
      customInstruction: typeof item.customInstruction === 'string' ? item.customInstruction.trim() : '',
      conversationIds: ids,
      createdAt: typeof item.createdAt === 'number' ? item.createdAt : Date.now(),
      updatedAt: typeof item.updatedAt === 'number' ? item.updatedAt : Date.now(),
    });
  }
  return out.sort((a: AmberHubRecord, b: AmberHubRecord): number => b.updatedAt - a.updatedAt);
};

export const loadAmberHubs = async (): Promise<AmberHubRecord[]> => {
  try {
    const kv: KeyValueStore = getChatKvStore();
    const raw: string | null = await kv.get(AMBER_HUBS_STORAGE_KEY);
    if (raw === null || raw.trim().length === 0) return [];
    const parsed = JSON.parse(raw) as AmberHubRecord[];
    return Array.isArray(parsed) ? normalizeHubList(parsed) : [];
  } catch (_e) {
    return [];
  }
};

export const saveAmberHubs = async (hubs: AmberHubRecord[]): Promise<AmberHubRecord[]> => {
  const clean: AmberHubRecord[] = normalizeHubList(hubs);
  const kv: KeyValueStore = getChatKvStore();
  await kv.put(AMBER_HUBS_STORAGE_KEY, JSON.stringify(clean));
  return clean;
};

export const createAmberHub = async (input: CreateAmberHubInput): Promise<AmberHubRecord[]> => {
  const title: string = input.title.trim();
  if (title.length === 0) return await loadAmberHubs();
  const current: AmberHubRecord[] = await loadAmberHubs();
  const now: number = Date.now();
  const nextHub: AmberHubRecord = {
    id: newId(),
    title,
    description: (input.description ?? '').trim(),
    customInstruction: (input.customInstruction ?? '').trim(),
    conversationIds: (input.conversationIds ?? []).filter((id: string): boolean => id.trim().length > 0),
    createdAt: now,
    updatedAt: now,
  };
  return await saveAmberHubs([nextHub].concat(current));
};

export const toggleConversationInHub = async (
  hubId: string,
  conversationId: string,
): Promise<AmberHubRecord[]> => {
  const current: AmberHubRecord[] = await loadAmberHubs();
  const now: number = Date.now();
  const next: AmberHubRecord[] = current.map((hub: AmberHubRecord): AmberHubRecord => {
    if (hub.id !== hubId) return hub;
    const exists: boolean = hub.conversationIds.indexOf(conversationId) >= 0;
    const ids: string[] = exists
      ? hub.conversationIds.filter((id: string): boolean => id !== conversationId)
      : [conversationId].concat(hub.conversationIds);
    return {
      id: hub.id,
      title: hub.title,
      description: hub.description,
      customInstruction: hub.customInstruction,
      conversationIds: ids,
      createdAt: hub.createdAt,
      updatedAt: now,
    };
  });
  return await saveAmberHubs(next);
};

export const updateAmberHubInstruction = async (
  hubId: string,
  instruction: string,
): Promise<AmberHubRecord[]> => {
  const current: AmberHubRecord[] = await loadAmberHubs();
  const now: number = Date.now();
  const next: AmberHubRecord[] = current.map((hub: AmberHubRecord): AmberHubRecord => {
    if (hub.id !== hubId) return hub;
    return {
      id: hub.id,
      title: hub.title,
      description: hub.description,
      customInstruction: instruction.trim(),
      conversationIds: hub.conversationIds,
      createdAt: hub.createdAt,
      updatedAt: now,
    };
  });
  return await saveAmberHubs(next);
};

export const deleteAmberHub = async (hubId: string): Promise<AmberHubRecord[]> => {
  const current: AmberHubRecord[] = await loadAmberHubs();
  return await saveAmberHubs(current.filter((hub: AmberHubRecord): boolean => hub.id !== hubId));
};

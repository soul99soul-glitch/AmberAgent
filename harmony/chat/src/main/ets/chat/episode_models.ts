// episode_models — 单流对话 Episode 原地封存、3h 空闲分段与 Recent Episodes Index（<= 300 tokens）
// 纯域逻辑，零 SDK / UI 依赖；侧车持久化由 entry/ChatEpisodeStore 承担。

import type { UIMessage, UIMessagePart } from './message.ts';
import { toText } from './message.ts';

export const EPISODE_IDLE_TIMEOUT_SECONDS: number = 10800; // 3 hours
export const EPISODE_IDLE_TIMEOUT_MS: number = EPISODE_IDLE_TIMEOUT_SECONDS * 1000;
export const RECENT_EPISODES_MAX_ITEMS: number = 8;
export const RECENT_EPISODES_MAX_TOKENS: number = 300;
export const EPISODE_TITLE_MAX_CHARS: number = 18;
export const EPISODE_SUMMARY_MAX_CHARS: number = 56;

export type ChatEpisodeSealReason = 'manual_new_topic' | 'idle_timeout';

export interface ChatEpisodeRecord {
  id: string;
  afterMessageId: string;
  reason: ChatEpisodeSealReason;
  sealedAtMs: number;
  firstMessageAtMs: number;
  lastMessageAtMs: number;
  turnCount: number;
  title: string;
  summary: string;
  refined: boolean;
}

export interface ChatEpisodeLedger {
  version: 1;
  conversationId: string;
  episodes: ChatEpisodeRecord[];
}

export interface ChatEpisodeSealResult {
  ledger: ChatEpisodeLedger;
  sealed: ChatEpisodeRecord | null;
}

export interface ChatEpisodeSearchHit {
  episodeId: string;
  title: string;
  summary: string;
  sealedAtMs: number;
  excerpts: string[];
}

export const createEmptyEpisodeLedger = (conversationId: string): ChatEpisodeLedger => ({
  version: 1,
  conversationId,
  episodes: [],
});

const clampChars = (raw: string, maxChars: number): string => {
  const clean: string = raw.replace(/[\u0000-\u001f\u007f\u2028\u2029]+/g, ' ').replace(/\s+/g, ' ').trim();
  if (clean.length === 0) return '';
  const chars: string[] = Array.from(clean);
  if (chars.length <= maxChars) return clean;
  return `${chars.slice(0, Math.max(1, maxChars - 1)).join('')}…`;
};

const parseTimeMs = (isoOrEmpty: string | null | undefined, fallbackMs: number): number => {
  if (typeof isoOrEmpty !== 'string' || isoOrEmpty.trim().length === 0) return fallbackMs;
  const parsed: number = Date.parse(isoOrEmpty);
  return Number.isFinite(parsed) ? parsed : fallbackMs;
};

export const estimateEpisodeIndexTokens = (text: string): number => {
  if (text.length === 0) return 0;
  let tokens: number = 0;
  for (const ch of text) {
    tokens += ch.charCodeAt(0) > 127 ? 1.2 : 0.35;
  }
  return Math.ceil(tokens);
};

export const parseEpisodeLedger = (raw: string, conversationId: string): ChatEpisodeLedger => {
  if (raw.trim().length === 0) return createEmptyEpisodeLedger(conversationId);
  try {
    const parsed = JSON.parse(raw) as Partial<ChatEpisodeLedger>;
    if (parsed === null || typeof parsed !== 'object' || !Array.isArray(parsed.episodes)) {
      return createEmptyEpisodeLedger(conversationId);
    }
    const clean: ChatEpisodeRecord[] = [];
    for (const item of parsed.episodes) {
      if (item === null || typeof item !== 'object') continue;
      if (typeof item.id !== 'string' || item.id.length === 0) continue;
      if (typeof item.afterMessageId !== 'string' || item.afterMessageId.length === 0) continue;
      clean.push({
        id: item.id,
        afterMessageId: item.afterMessageId,
        reason: item.reason === 'idle_timeout' ? 'idle_timeout' : 'manual_new_topic',
        sealedAtMs: typeof item.sealedAtMs === 'number' ? item.sealedAtMs : 0,
        firstMessageAtMs: typeof item.firstMessageAtMs === 'number' ? item.firstMessageAtMs : 0,
        lastMessageAtMs: typeof item.lastMessageAtMs === 'number' ? item.lastMessageAtMs : 0,
        turnCount: typeof item.turnCount === 'number' ? Math.max(0, Math.floor(item.turnCount)) : 0,
        title: typeof item.title === 'string' ? clampChars(item.title, EPISODE_TITLE_MAX_CHARS) : '',
        summary: typeof item.summary === 'string' ? clampChars(item.summary, EPISODE_SUMMARY_MAX_CHARS) : '',
        refined: item.refined === true,
      });
    }
    return {
      version: 1,
      conversationId: typeof parsed.conversationId === 'string' && parsed.conversationId.length > 0
        ? parsed.conversationId : conversationId,
      episodes: clean,
    };
  } catch (_e) {
    return createEmptyEpisodeLedger(conversationId);
  }
};

const nonSystemMessages = (messages: UIMessage[]): UIMessage[] =>
  messages.filter((m: UIMessage): boolean => m.role !== 'system');

export const findLatestEpisodeBoundaryIndex = (
  messages: UIMessage[],
  ledger: ChatEpisodeLedger,
): number => {
  if (ledger.episodes.length === 0 || messages.length === 0) return -1;
  for (let i: number = ledger.episodes.length - 1; i >= 0; i--) {
    const boundaryId: string = ledger.episodes[i].afterMessageId;
    const idx: number = messages.findIndex((m: UIMessage): boolean => m.id === boundaryId);
    if (idx >= 0) return idx;
  }
  return -1;
};

export const unsealedSegmentMessages = (
  messages: UIMessage[],
  ledger: ChatEpisodeLedger,
): UIMessage[] => {
  const boundaryIdx: number = findLatestEpisodeBoundaryIndex(messages, ledger);
  const tail: UIMessage[] = boundaryIdx >= 0 ? messages.slice(boundaryIdx + 1) : messages;
  return nonSystemMessages(tail);
};

// 模型请求上下文仅装载当前活跃 Episode（保留前置 system 消息 + 最新边界之后的全部消息）
export const activeEpisodeMessages = (
  messages: UIMessage[],
  ledger: ChatEpisodeLedger,
): UIMessage[] => {
  const boundaryIdx: number = findLatestEpisodeBoundaryIndex(messages, ledger);
  if (boundaryIdx < 0) return messages;
  const systems: UIMessage[] = [];
  for (let i: number = 0; i <= boundaryIdx; i++) {
    if (messages[i].role === 'system') systems.push(messages[i]);
  }
  return [...systems, ...messages.slice(boundaryIdx + 1)];
};

const nextEpisodeId = (episodes: ChatEpisodeRecord[]): string => {
  let maxNum: number = episodes.length;
  for (const ep of episodes) {
    const match: RegExpExecArray | null = /^ep_(\d+)$/.exec(ep.id);
    if (match !== null) {
      const n: number = Number(match[1]);
      if (Number.isInteger(n) && n > maxNum) maxNum = n;
    }
  }
  return `ep_${String(maxNum + 1).padStart(4, '0')}`;
};

export const deterministicEpisodeSummary = (
  segment: UIMessage[],
): { title: string; summary: string; turnCount: number } => {
  let firstUserText: string = '';
  let lastAssistantText: string = '';
  let userTurns: number = 0;
  for (const m of segment) {
    const txt: string = toText(m).trim();
    if (m.role === 'user') {
      userTurns++;
      if (firstUserText.length === 0 && txt.length > 0) firstUserText = txt;
    } else if (m.role === 'assistant' && txt.length > 0) {
      lastAssistantText = txt;
    }
  }
  const titleSeed: string = firstUserText.length > 0 ? firstUserText : (lastAssistantText || '阶段讨论');
  const title: string = clampChars(titleSeed, EPISODE_TITLE_MAX_CHARS) || '阶段讨论';
  const summaryRaw: string = lastAssistantText.length > 0
    ? (firstUserText.length > 0 ? `${clampChars(firstUserText, 24)} → ${lastAssistantText}` : lastAssistantText)
    : firstUserText;
  const summary: string = clampChars(summaryRaw, EPISODE_SUMMARY_MAX_CHARS) || `${segment.length} 条消息已归档`;
  return { title, summary, turnCount: userTurns };
};

export const sealEpisodeIfEligible = (
  ledger: ChatEpisodeLedger,
  messages: UIMessage[],
  reason: ChatEpisodeSealReason,
  nowMs: number = Date.now(),
): ChatEpisodeSealResult => {
  const segment: UIMessage[] = unsealedSegmentMessages(messages, ledger);
  // 空边界幂等保护：当前活跃段没有非系统消息时绝不重复封存
  if (segment.length === 0) {
    return { ledger, sealed: null };
  }
  const firstAt: number = parseTimeMs(segment[0].createdAt, nowMs);
  const lastMsg: UIMessage = segment[segment.length - 1];
  const lastAt: number = parseTimeMs(lastMsg.finishedAt ?? lastMsg.createdAt, firstAt);

  if (reason === 'idle_timeout') {
    if (nowMs - lastAt < EPISODE_IDLE_TIMEOUT_MS) {
      return { ledger, sealed: null };
    }
  }

  const derived = deterministicEpisodeSummary(segment);
  const record: ChatEpisodeRecord = {
    id: nextEpisodeId(ledger.episodes),
    afterMessageId: lastMsg.id,
    reason,
    sealedAtMs: nowMs,
    firstMessageAtMs: firstAt,
    lastMessageAtMs: lastAt,
    turnCount: derived.turnCount,
    title: derived.title,
    summary: derived.summary,
    refined: false,
  };
  return {
    ledger: {
      version: 1,
      conversationId: ledger.conversationId,
      episodes: [...ledger.episodes, record],
    },
    sealed: record,
  };
};

export const updateEpisodeSummary = (
  ledger: ChatEpisodeLedger,
  episodeId: string,
  title: string,
  summary: string,
): ChatEpisodeLedger => {
  const nextTitle: string = clampChars(title, EPISODE_TITLE_MAX_CHARS);
  const nextSummary: string = clampChars(summary, EPISODE_SUMMARY_MAX_CHARS);
  if (nextTitle.length === 0 && nextSummary.length === 0) return ledger;
  let changed: boolean = false;
  const episodes: ChatEpisodeRecord[] = ledger.episodes.map((ep: ChatEpisodeRecord): ChatEpisodeRecord => {
    if (ep.id !== episodeId) return ep;
    changed = true;
    return {
      id: ep.id,
      afterMessageId: ep.afterMessageId,
      reason: ep.reason,
      sealedAtMs: ep.sealedAtMs,
      firstMessageAtMs: ep.firstMessageAtMs,
      lastMessageAtMs: ep.lastMessageAtMs,
      turnCount: ep.turnCount,
      title: nextTitle.length > 0 ? nextTitle : ep.title,
      summary: nextSummary.length > 0 ? nextSummary : ep.summary,
      refined: true,
    };
  });
  return changed ? { version: 1, conversationId: ledger.conversationId, episodes } : ledger;
};

const pad2 = (n: number): string => String(n).padStart(2, '0');

const formatEpisodeTimestamp = (ms: number): string => {
  if (!Number.isFinite(ms) || ms <= 0) return '--:--';
  const d: Date = new Date(ms);
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
};

export const formatRecentEpisodesIndex = (
  ledger: ChatEpisodeLedger,
  maxItems: number = RECENT_EPISODES_MAX_ITEMS,
  maxTokens: number = RECENT_EPISODES_MAX_TOKENS,
): string => {
  if (ledger.episodes.length === 0 || maxItems <= 0 || maxTokens <= 0) return '';
  const candidates: ChatEpisodeRecord[] = ledger.episodes.slice(Math.max(0, ledger.episodes.length - maxItems));
  const lines: string[] = candidates.map((ep: ChatEpisodeRecord): string =>
    `- [${ep.id} · ${formatEpisodeTimestamp(ep.sealedAtMs)}] ${clampChars(ep.title, EPISODE_TITLE_MAX_CHARS)}：${clampChars(ep.summary, EPISODE_SUMMARY_MAX_CHARS)}`,
  );
  const header: string = 'Recent Episodes Index（可用 episode_recall 调阅前序原文）：\n';
  // 超预算时优先舍弃较旧条目，严格控制在 <= maxTokens 内
  while (lines.length > 0) {
    const text: string = header + lines.join('\n');
    if (estimateEpisodeIndexTokens(text) <= maxTokens) return text;
    if (lines.length === 1) {
      // 单条仍超预算时裁剪摘要字符
      const maxChars: number = Math.max(24, Math.floor(maxTokens * 0.7));
      return clampChars(text, maxChars);
    }
    lines.shift();
  }
  return '';
};

const sliceMessagesForEpisode = (
  messages: UIMessage[],
  episodes: ChatEpisodeRecord[],
  targetIndex: number,
): UIMessage[] => {
  const endBoundaryId: string = episodes[targetIndex].afterMessageId;
  const endIdx: number = messages.findIndex((m: UIMessage): boolean => m.id === endBoundaryId);
  if (endIdx < 0) return [];
  let startIdx: number = 0;
  for (let i: number = targetIndex - 1; i >= 0; i--) {
    const prevIdx: number = messages.findIndex((m: UIMessage): boolean => m.id === episodes[i].afterMessageId);
    if (prevIdx >= 0) {
      startIdx = prevIdx + 1;
      break;
    }
  }
  return nonSystemMessages(messages.slice(startIdx, endIdx + 1));
};

const messageExcerpt = (m: UIMessage): string => {
  const roleLabel: string = m.role === 'user' ? '用户' : '助手';
  const textParts: string[] = [];
  for (const part of m.parts as UIMessagePart[]) {
    if (part.type === 'text' && part.text.trim().length > 0) {
      textParts.push(part.text.trim());
    }
  }
  const body: string = clampChars(textParts.join(' '), 180);
  return body.length > 0 ? `${roleLabel}: ${body}` : '';
};

export const searchEpisodeMessages = (
  ledger: ChatEpisodeLedger,
  messages: UIMessage[],
  episodeId?: string,
  query?: string,
  limit: number = 6,
): ChatEpisodeSearchHit[] => {
  if (ledger.episodes.length === 0) return [];
  const cleanId: string = (episodeId ?? '').trim();
  const cleanQuery: string = (query ?? '').trim().toLowerCase();
  const maxHits: number = Math.max(1, Math.min(10, limit));
  const hits: ChatEpisodeSearchHit[] = [];

  for (let i: number = ledger.episodes.length - 1; i >= 0; i--) {
    const ep: ChatEpisodeRecord = ledger.episodes[i];
    if (cleanId.length > 0 && ep.id !== cleanId) continue;
    const segment: UIMessage[] = sliceMessagesForEpisode(messages, ledger.episodes, i);
    const excerpts: string[] = [];
    for (const msg of segment) {
      const line: string = messageExcerpt(msg);
      if (line.length === 0) continue;
      if (cleanQuery.length > 0) {
        const matchedMsg: boolean = line.toLowerCase().includes(cleanQuery)
          || ep.title.toLowerCase().includes(cleanQuery)
          || ep.summary.toLowerCase().includes(cleanQuery);
        if (!matchedMsg) continue;
      }
      excerpts.push(line);
      if (excerpts.length >= 6) break;
    }
    if (cleanId.length === 0 && cleanQuery.length > 0 && excerpts.length === 0) {
      if (!ep.title.toLowerCase().includes(cleanQuery) && !ep.summary.toLowerCase().includes(cleanQuery)) {
        continue;
      }
    }
    hits.push({
      episodeId: ep.id,
      title: ep.title,
      summary: ep.summary,
      sealedAtMs: ep.sealedAtMs,
      excerpts,
    });
    if (hits.length >= maxHits) break;
  }
  return hits;
};

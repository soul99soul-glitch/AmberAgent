// ChatEpisodeStore — 单流对话 Episode 侧车账本存储（conversation-episodes/{conversationId}.json）
// - 基于 AtomicJsonFile 原子写 + KeyedAsyncMutex 串行化
// - 提供 startNewEpisode（空边界幂等防重）、sealIfIdleTimeoutExceeded（3h 空闲分段）与后台摘要提炼

import { fileIo } from '@kit.CoreFileKit';
import type { Context } from '@kit.AbilityKit';
import type {
  UIMessage, MessageNode, ChatEpisodeLedger, ChatEpisodeRecord, ChatEpisodeSealResult,
} from '@amber/chat-domain';
import {
  createEmptyEpisodeLedger, parseEpisodeLedger, sealEpisodeIfEligible,
  updateEpisodeSummary, unsealedSegmentMessages, projectEpisodeLedger,
} from '@amber/chat-domain';
import { KeyedAsyncMutex, writeTextFileAtomic } from '../AtomicJsonFile.ets';
import { sha256HexUtf8 } from '../EntryLocalToolPorts.ets';

const EPISODE_DIR: string = 'conversation-episodes';
const ledgerCache: Map<string, ChatEpisodeLedger> = new Map<string, ChatEpisodeLedger>();
const ledgerMutex: KeyedAsyncMutex = new KeyedAsyncMutex();
const listenersByConv: Map<string, Set<(ledger: ChatEpisodeLedger) => void>> =
  new Map<string, Set<(ledger: ChatEpisodeLedger) => void>>();

const safeFileId = (conversationId: string): string => {
  return /^[A-Za-z0-9_.-]{1,128}$/.test(conversationId) ? conversationId : `%${sha256HexUtf8(conversationId)}`;
};

const ledgerPathFor = (conversationId: string): string | null => {
  try {
    const ctx: Context | undefined = AppStorage.get<Context>('abilityContext');
    if (ctx === undefined) return null;
    return `${ctx.filesDir}/${EPISODE_DIR}/${safeFileId(conversationId)}.json`;
  } catch (_e) {
    return null;
  }
};

const notifyLedgerListeners = (conversationId: string, ledger: ChatEpisodeLedger): void => {
  const listeners: Set<(ledger: ChatEpisodeLedger) => void> | undefined = listenersByConv.get(conversationId);
  if (listeners === undefined) return;
  for (const listener of Array.from(listeners)) {
    try {
      listener(ledger);
    } catch (_e) {
      // 忽略已销毁订阅方
    }
  }
};

export const getCachedEpisodeLedger = (conversationId: string): ChatEpisodeLedger => {
  if (conversationId.length === 0) return createEmptyEpisodeLedger('');
  return ledgerCache.get(conversationId) ?? createEmptyEpisodeLedger(conversationId);
};

export const subscribeEpisodeLedger = (
  conversationId: string,
  listener: (ledger: ChatEpisodeLedger) => void,
): (() => void) => {
  if (conversationId.length === 0) return (): void => {};
  let set: Set<(ledger: ChatEpisodeLedger) => void> | undefined = listenersByConv.get(conversationId);
  if (set === undefined) {
    set = new Set<(ledger: ChatEpisodeLedger) => void>();
    listenersByConv.set(conversationId, set);
  }
  set.add(listener);
  return (): void => {
    set?.delete(listener);
    if (set !== undefined && set.size === 0) listenersByConv.delete(conversationId);
  };
};

export const loadEpisodeLedger = async (conversationId: string): Promise<ChatEpisodeLedger> => {
  if (conversationId.length === 0) return createEmptyEpisodeLedger('');
  const cached: ChatEpisodeLedger | undefined = ledgerCache.get(conversationId);
  if (cached !== undefined) return cached;
  const path: string | null = ledgerPathFor(conversationId);
  if (path === null) return createEmptyEpisodeLedger(conversationId);
  try {
    const raw: string = await fileIo.readText(path);
    const parsed: ChatEpisodeLedger = parseEpisodeLedger(raw, conversationId);
    const current: ChatEpisodeLedger | undefined = ledgerCache.get(conversationId);
    if (current !== undefined) return current;
    ledgerCache.set(conversationId, parsed);
    return parsed;
  } catch (_e) {
    const current: ChatEpisodeLedger | undefined = ledgerCache.get(conversationId);
    if (current !== undefined) return current;
    const empty: ChatEpisodeLedger = createEmptyEpisodeLedger(conversationId);
    ledgerCache.set(conversationId, empty);
    return empty;
  }
};

const saveEpisodeLedger = async (conversationId: string, ledger: ChatEpisodeLedger): Promise<void> => {
  const path: string | null = ledgerPathFor(conversationId);
  if (path !== null) {
    await writeTextFileAtomic(path, JSON.stringify(ledger));
  }
  ledgerCache.set(conversationId, ledger);
  notifyLedgerListeners(conversationId, ledger);
};

export const startNewEpisode = async (
  conversationId: string,
  messages: UIMessage[],
  nowMs: number = Date.now(),
  nodes?: MessageNode[],
): Promise<ChatEpisodeSealResult> => {
  if (conversationId.length === 0) {
    return { ledger: createEmptyEpisodeLedger(''), sealed: null };
  }
  return await ledgerMutex.withLock(conversationId, async (): Promise<ChatEpisodeSealResult> => {
    const current: ChatEpisodeLedger = await loadEpisodeLedger(conversationId);
    const ledger: ChatEpisodeLedger = nodes === undefined ? current : projectEpisodeLedger(current, nodes, true);
    const res: ChatEpisodeSealResult = sealEpisodeIfEligible(ledger, messages, 'manual_new_topic', nowMs);
    if (res.sealed === null) return res;
    await saveEpisodeLedger(conversationId, res.ledger);
    return res;
  });
};

export const sealIfIdleTimeoutExceeded = async (
  conversationId: string,
  messages: UIMessage[],
  nowMs: number = Date.now(),
  nodes?: MessageNode[],
): Promise<ChatEpisodeSealResult> => {
  if (conversationId.length === 0) {
    return { ledger: createEmptyEpisodeLedger(''), sealed: null };
  }
  return await ledgerMutex.withLock(conversationId, async (): Promise<ChatEpisodeSealResult> => {
    const current: ChatEpisodeLedger = await loadEpisodeLedger(conversationId);
    const ledger: ChatEpisodeLedger = nodes === undefined ? current : projectEpisodeLedger(current, nodes, true);
    const res: ChatEpisodeSealResult = sealEpisodeIfEligible(ledger, messages, 'idle_timeout', nowMs);
    if (res.sealed === null) return res;
    await saveEpisodeLedger(conversationId, res.ledger);
    return res;
  });
};

export interface RefinedEpisodeSummary {
  title: string;
  summary: string;
}

export const refineEpisodeSummaryInBackground = (
  conversationId: string,
  episode: ChatEpisodeRecord,
  messages: UIMessage[],
  refiner: (segment: UIMessage[]) => Promise<RefinedEpisodeSummary | null>,
): void => {
  if (conversationId.length === 0 || episode.turnCount < 2 || episode.refined) return;
  const endIdx: number = messages.findIndex((m: UIMessage): boolean => m.id === episode.afterMessageId);
  if (endIdx < 0) return;
  const ledger: ChatEpisodeLedger = getCachedEpisodeLedger(conversationId);
  const epIdx: number = ledger.episodes.findIndex((item: ChatEpisodeRecord): boolean => item.id === episode.id);
  let startIdx: number = 0;
  if (epIdx > 0) {
    const prevIdx: number = messages.findIndex(
      (m: UIMessage): boolean => m.id === ledger.episodes[epIdx - 1].afterMessageId,
    );
    if (prevIdx >= 0) startIdx = prevIdx + 1;
  }
  const segment: UIMessage[] = messages.slice(startIdx, endIdx + 1).filter(
    (m: UIMessage): boolean => m.role !== 'system',
  );
  if (segment.length < 2) return;

  Promise.resolve().then(async (): Promise<void> => {
    const refined = await refiner(segment);
    if (refined === null) return;
    await ledgerMutex.withLock(conversationId, async (): Promise<void> => {
      const latest: ChatEpisodeLedger = await loadEpisodeLedger(conversationId);
      const updated: ChatEpisodeLedger = updateEpisodeSummary(
        latest, episode.id, refined.title, refined.summary,
      );
      if (updated !== latest) {
        await saveEpisodeLedger(conversationId, updated);
      }
    });
  }).catch((): void => {
    // 后台低优摘要提炼失败时保留确定性标题摘要兜底，绝不打扰主对话
  });
};

export const activeEpisodeHeaderTitle = (ledger: ChatEpisodeLedger, messages: UIMessage[], fallbackTitle: string): string => {
  if (ledger.episodes.length === 0) return fallbackTitle;
  const active: UIMessage[] = unsealedSegmentMessages(messages, ledger);
  for (const m of active) {
    if (m.role === 'user') {
      for (const p of m.parts) {
        if (p.type === 'text' && p.text.trim().length > 0) {
          const clean: string = p.text.replace(/\s+/g, ' ').trim();
          const chars: string[] = Array.from(clean);
          return chars.length <= 18 ? clean : `${chars.slice(0, 17).join('')}…`;
        }
      }
    }
  }
  return '新话题';
};

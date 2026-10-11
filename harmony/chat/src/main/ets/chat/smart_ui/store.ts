// smart_ui/store — 智能 UI 草稿的实例级 KV 接线。
//
// 页面持有一个实例；写入串行化，短时间内同一个块的多次失焦保存合并。
// 不在模块级保存会话或状态，也不把草稿混入聊天输入框草稿。

import type { KeyValueStore } from '../kv_store.ts';
import type { SmartUiDocument } from './protocol.ts';
import type { SmartUiStateValue } from './interaction.ts';
import {
  checkSmartUiDraftSessionBudget, restoreSmartUiDraft, serializeSmartUiDraft,
  smartUiConversationKeyPrefix, smartUiDraftKey,
  SMART_UI_DRAFT_BLOCK_MAX_BYTES,
} from './persistence.ts';
import type {
  SmartUiDraftRestore, SmartUiDraftSerialization, SmartUiDraftSource,
} from './persistence.ts';

export type SmartUiDraftSaveReason =
  | 'saved'
  | 'document-not-ready'
  | 'invalid-source'
  | 'invalid-values'
  | 'block-too-large'
  | 'session-too-large'
  | 'quota-unknown'
  | 'storage-error'
  | 'cancelled';

export interface SmartUiDraftSaveResult {
  saved: boolean;
  reason: SmartUiDraftSaveReason;
  serialization: SmartUiDraftSerialization;
  error: string | null;
}

export interface SmartUiDraftCleanupResult {
  available: boolean;
  deleted: number;
  error: string | null;
}

export interface SmartUiDraftStore {
  load(document: SmartUiDocument, source: SmartUiDraftSource): Promise<SmartUiDraftRestore>;
  save(
    document: SmartUiDocument, source: SmartUiDraftSource,
    values: SmartUiStateValue[], updatedAt?: number,
  ): Promise<SmartUiDraftSaveResult>;
  saveDebounced(
    document: SmartUiDocument, source: SmartUiDraftSource,
    values: SmartUiStateValue[], updatedAt?: number, delayMs?: number,
  ): Promise<SmartUiDraftSaveResult>;
  /** Cancel this instance's debounce timers, persist their latest values, and wait for queued writes. */
  flush(): Promise<void>;
  remove(source: SmartUiDraftSource): Promise<void>;
  cleanupConversation(conversationId: string): Promise<SmartUiDraftCleanupResult>;
}

interface PendingSaveWaiter {
  resolve: (result: SmartUiDraftSaveResult) => void;
  reject: (error: Error) => void;
}

interface PendingSave {
  document: SmartUiDocument;
  source: SmartUiDraftSource;
  values: SmartUiStateValue[];
  updatedAt: number | undefined;
  timer: ReturnType<typeof setTimeout>;
  waiters: PendingSaveWaiter[];
}

const emptySerialization = (): SmartUiDraftSerialization => ({
  raw: null, envelope: null, bytes: 0, reason: 'invalid-source',
  diagnostics: ['草稿保存已取消'],
});

const cancelledResult = (): SmartUiDraftSaveResult => ({
  saved: false, reason: 'cancelled', serialization: emptySerialization(), error: null,
});

const storageError = (serialization: SmartUiDraftSerialization, error: unknown): SmartUiDraftSaveResult => ({
  saved: false, reason: 'storage-error', serialization,
  error: error instanceof Error ? error.message : String(error),
});

const serializeResult = (serialization: SmartUiDraftSerialization): SmartUiDraftSaveResult | null => {
  if (serialization.raw === null) {
    const reason: SmartUiDraftSaveReason = serialization.reason === 'document-not-ready'
      ? 'document-not-ready' : serialization.reason === 'invalid-source'
        ? 'invalid-source' : 'block-too-large';
    return { saved: false, reason, serialization, error: null };
  }
  return null;
};

class SmartUiDraftStoreImpl implements SmartUiDraftStore {
  private readonly kv: KeyValueStore;
  private writeTail: Promise<void> = Promise.resolve();
  private readonly pending: Map<string, PendingSave> = new Map<string, PendingSave>();

  constructor(kv: KeyValueStore) {
    this.kv = kv;
  }

  private enqueue<T>(task: () => Promise<T>): Promise<T> {
    const next: Promise<T> = this.writeTail.then(task, task);
    this.writeTail = next.then((): void => {}, (): void => {});
    return next;
  }

  async load(document: SmartUiDocument, source: SmartUiDraftSource): Promise<SmartUiDraftRestore> {
    await this.flushPendingKey(smartUiDraftKey(source));
    await this.writeTail;
    const raw: string | null = await this.kv.get(smartUiDraftKey(source));
    return restoreSmartUiDraft(raw, document, source);
  }

  async save(
    document: SmartUiDocument, source: SmartUiDraftSource,
    values: SmartUiStateValue[], updatedAt: number = Date.now(),
  ): Promise<SmartUiDraftSaveResult> {
    const serialization: SmartUiDraftSerialization = serializeSmartUiDraft(document, source, values, updatedAt);
    const early: SmartUiDraftSaveResult | null = serializeResult(serialization);
    if (early !== null) return early;
    const raw: string = serialization.raw as string;
    return this.enqueue(async (): Promise<SmartUiDraftSaveResult> => {
      if (this.kv.allEntries === undefined) {
        // Without enumeration the adapter cannot prove the per-conversation 128KiB limit.
        return { saved: false, reason: 'quota-unknown', serialization, error: null };
      }
      try {
        const entries: Array<{ key: string; value: string }> = await this.kv.allEntries();
        const prefix: string = smartUiConversationKeyPrefix(source.conversationId);
        const key: string = smartUiDraftKey(source);
        const existing: string[] = entries
          .filter((entry): boolean => entry.key.startsWith(prefix) && entry.key !== key)
          .map((entry): string => entry.value);
        const budget = checkSmartUiDraftSessionBudget(existing.concat([raw]));
        if (!budget.ok) {
          return { saved: false, reason: 'session-too-large', serialization, error: null };
        }
        await this.kv.put(key, raw);
        return { saved: true,
          reason: serialization.reason === 'invalid-values' ? 'invalid-values' : 'saved',
          serialization, error: null };
      } catch (error) {
        return storageError(serialization, error);
      }
    });
  }

  saveDebounced(
    document: SmartUiDocument, source: SmartUiDraftSource,
    values: SmartUiStateValue[], updatedAt: number = Date.now(), delayMs: number = 250,
  ): Promise<SmartUiDraftSaveResult> {
    const key: string = smartUiDraftKey(source);
    const boundedDelay: number = Math.max(0, Math.min(2000, Math.trunc(delayMs)));
    const current: PendingSave | undefined = this.pending.get(key);
    return new Promise<SmartUiDraftSaveResult>((resolve, reject): void => {
      const waiter: PendingSaveWaiter = { resolve, reject };
      if (current !== undefined) {
        clearTimeout(current.timer);
        current.document = document;
        current.source = source;
        current.values = values;
        current.updatedAt = updatedAt;
        current.waiters.push(waiter);
        current.timer = setTimeout((): void => this.flushPending(key), boundedDelay);
        return;
      }
      const pending: PendingSave = {
        document, source, values, updatedAt,
        timer: setTimeout((): void => this.flushPending(key), boundedDelay),
        waiters: [waiter],
      };
      this.pending.set(key, pending);
    });
  }

  private flushPending(key: string): void {
    void this.flushPendingKey(key);
  }

  private flushPendingKey(key: string): Promise<void> {
    const current: PendingSave | undefined = this.pending.get(key);
    if (current === undefined) return Promise.resolve();
    clearTimeout(current.timer);
    this.pending.delete(key);
    return this.save(current.document, current.source, current.values, current.updatedAt)
      .then((result: SmartUiDraftSaveResult): void => {
        for (const waiter of current.waiters) waiter.resolve(result);
      }, (error: Error): void => {
        for (const waiter of current.waiters) waiter.reject(error);
      });
  }

  async flush(): Promise<void> {
    const keys: string[] = [...this.pending.keys()];
    await Promise.all(keys.map((key: string): Promise<void> => this.flushPendingKey(key)));
    await this.writeTail;
  }

  private cancelPending(predicate: (pending: PendingSave) => boolean): void {
    for (const [key, current] of this.pending) {
      if (!predicate(current)) continue;
      clearTimeout(current.timer);
      this.pending.delete(key);
      const result: SmartUiDraftSaveResult = cancelledResult();
      for (const waiter of current.waiters) waiter.resolve(result);
    }
  }

  remove(source: SmartUiDraftSource): Promise<void> {
    this.cancelPending((pending: PendingSave): boolean => smartUiDraftKey(pending.source) === smartUiDraftKey(source));
    return this.enqueue((): Promise<void> => this.kv.delete(smartUiDraftKey(source)));
  }

  cleanupConversation(conversationId: string): Promise<SmartUiDraftCleanupResult> {
    this.cancelPending((pending: PendingSave): boolean => pending.source.conversationId === conversationId);
    return this.enqueue(async (): Promise<SmartUiDraftCleanupResult> => {
      if (this.kv.allEntries === undefined) {
        return { available: false, deleted: 0, error: 'KV adapter does not support entry enumeration' };
      }
      try {
        const prefix: string = smartUiConversationKeyPrefix(conversationId);
        const entries: Array<{ key: string; value: string }> = await this.kv.allEntries();
        const keys: string[] = entries.filter((entry): boolean => entry.key.startsWith(prefix))
          .map((entry): string => entry.key);
        for (const key of keys) await this.kv.delete(key);
        return { available: true, deleted: keys.length, error: null };
      } catch (error) {
        return { available: true, deleted: 0, error: error instanceof Error ? error.message : String(error) };
      }
    });
  }
}

export const createSmartUiDraftStore = (kv: KeyValueStore): SmartUiDraftStore => new SmartUiDraftStoreImpl(kv);

export { SMART_UI_DRAFT_BLOCK_MAX_BYTES };


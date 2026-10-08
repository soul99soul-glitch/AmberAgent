// RdbRepository — DeepReadRepository 的真实 relationalStore 实现。
// output_json 保存完整 DeepReadOutput；section state 的局部更新通过读改写 JSON 完成。

import { relationalStore } from '@kit.ArkData';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  DeepReadRepository, DeepReadCacheEntry, Observable, DeepReadOutput, DeepReadSectionState,
  DeepReadGenerationStage, DeepReadGenerationPhase,
} from '@amber/deepread-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.RdbRepository';
const DB_NAME: string = 'amber_deepread.db';
const TABLE: string = 'deepread_cache';
const TTL_MS: number = 24 * 60 * 60 * 1000;
const HISTORY_RETENTION_MS: number = 7 * 24 * 60 * 60 * 1000;
const COLUMNS: string[] = [
  'topic_id', 'title', 'source_url', 'output_json', 'phase', 'attempt_count',
  'last_error', 'created_at', 'updated_at', 'expires_at',
];

interface CacheRow {
  topicId: string;
  title: string;
  sourceUrl: string | null;
  outputJson: string;
  phase: string;
  attemptCount: number;
  lastError: string | null;
  createdAt: number;
  updatedAt: number;
  expiresAt: number;
}

interface ListenerSet {
  listeners: Set<(value: DeepReadCacheEntry | null) => void>;
  current: DeepReadCacheEntry | null | undefined;
  version: number;
}

interface HistoryListenerSet {
  listeners: Set<(value: DeepReadCacheEntry[]) => void>;
  current: DeepReadCacheEntry[] | undefined;
  version: number;
}

const schemaStatements: string[] = [
  `CREATE TABLE IF NOT EXISTS ${TABLE} (topic_id TEXT PRIMARY KEY, title TEXT NOT NULL, source_url TEXT NULL, output_json TEXT NOT NULL, phase TEXT NOT NULL, attempt_count INTEGER NOT NULL DEFAULT 0, last_error TEXT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_deepread_cache_title ON ${TABLE}(title)`,
  `CREATE INDEX IF NOT EXISTS idx_deepread_cache_source_url ON ${TABLE}(source_url)`,
  `CREATE INDEX IF NOT EXISTS idx_deepread_cache_updated_at ON ${TABLE}(updated_at)`,
  `CREATE INDEX IF NOT EXISTS idx_deepread_cache_expires_at ON ${TABLE}(expires_at)`,
];

const nullableString = (value: string): string | null => value.length === 0 ? null : value;

const rowToCacheRow = (rs: relationalStore.ResultSet): CacheRow => ({
  topicId: rs.getString(0),
  title: rs.getString(1),
  sourceUrl: nullableString(rs.getString(2)),
  outputJson: rs.getString(3),
  phase: rs.getString(4),
  attemptCount: rs.getLong(5),
  lastError: nullableString(rs.getString(6)),
  createdAt: rs.getLong(7),
  updatedAt: rs.getLong(8),
  expiresAt: rs.getLong(9),
});

const parseOutput = (raw: string): DeepReadOutput | null => {
  try {
    const parsed: DeepReadOutput = JSON.parse(raw) as DeepReadOutput;
    if (parsed === null || typeof parsed !== 'object') return null;
    return parsed;
  } catch (_) {
    return null;
  }
};

const rowToEntry = (row: CacheRow): DeepReadCacheEntry | null => {
  const output: DeepReadOutput | null = parseOutput(row.outputJson);
  if (output === null) return null;
  return {
    topicId: row.topicId,
    title: row.title,
    sourceUrl: row.sourceUrl,
    output: output,
    templateId: output.templateSnapshot?.id ?? output.templateId,
    phase: row.phase as DeepReadGenerationPhase,
    attemptCount: row.attemptCount,
    lastError: row.lastError,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
    expiresAt: row.expiresAt,
  };
};

const entryBucket = (entry: DeepReadCacheEntry): relationalStore.ValuesBucket => ({
  topic_id: entry.topicId,
  title: entry.title,
  source_url: entry.sourceUrl,
  output_json: JSON.stringify(entry.output),
  phase: entry.phase,
  attempt_count: entry.attemptCount,
  last_error: entry.lastError,
  created_at: entry.createdAt,
  updated_at: entry.updatedAt,
  expires_at: entry.expiresAt,
});

const entryWithFields = (
  entry: DeepReadCacheEntry,
  output: DeepReadOutput,
  phase: DeepReadGenerationPhase,
  attemptCount: number,
  lastError: string | null,
  updatedAt: number,
): DeepReadCacheEntry => ({
  topicId: entry.topicId,
  title: entry.title,
  sourceUrl: entry.sourceUrl,
  output: output,
  templateId: output.templateSnapshot?.id ?? output.templateId,
  phase: phase,
  attemptCount: attemptCount,
  lastError: lastError,
  createdAt: entry.createdAt,
  updatedAt: updatedAt,
  expiresAt: entry.expiresAt,
});

const outputWithSectionState = (
  output: DeepReadOutput, stage: DeepReadGenerationStage, state: DeepReadSectionState,
): DeepReadOutput => {
  const sectionStates: Record<string, DeepReadSectionState> = {};
  for (const key of Object.keys(output.sectionStates)) sectionStates[key] = output.sectionStates[key];
  sectionStates[stage] = state;
  return {
    topicType: output.topicType,
    generationComplete: output.generationComplete,
    generationPhase: output.generationPhase,
    summary: output.summary,
    keyEntities: output.keyEntities,
    timeline: output.timeline,
    corePoints: output.corePoints,
    analysis: output.analysis,
    extendedReading: output.extendedReading,
    heroImageQuery: output.heroImageQuery,
    heroImageUrl: output.heroImageUrl,
    heroCaption: output.heroCaption,
    heroImageConfidence: output.heroImageConfidence,
    imageAssets: output.imageAssets,
    diagram: output.diagram,
    visualDiagnostics: output.visualDiagnostics,
    references: output.references,
    sectionStates: sectionStates,
    sectionQualities: output.sectionQualities,
    inputSourceUrls: output.inputSourceUrls,
    inputSources: output.inputSources,
    inputText: output.inputText,
    inputUrlsText: output.inputUrlsText,
    templateId: output.templateId,
    templateSnapshot: output.templateSnapshot,
    templateArticle: output.templateArticle,
  };
};

class RdbDeepReadRepository implements DeepReadRepository {
  private store: relationalStore.RdbStore | null = null;
  private initPromise: Promise<relationalStore.RdbStore> | null = null;
  private mutationTail: Promise<void> = Promise.resolve();
  private observers: Map<string, ListenerSet> = new Map();
  private historyObservers: Map<number, HistoryListenerSet> = new Map();

  private enqueueMutation<T>(op: () => Promise<T>): Promise<T> {
    const next: Promise<T> = this.mutationTail.catch((): void => {}).then(op);
    this.mutationTail = next.then((): void => {}, (): void => {});
    return next;
  }

  private async initializeStore(): Promise<relationalStore.RdbStore> {
    const context: Context = AppStorage.get<Context>('abilityContext') as Context;
    const store: relationalStore.RdbStore = await relationalStore.getRdbStore(context, {
      name: DB_NAME,
      securityLevel: relationalStore.SecurityLevel.S1,
    });
    for (const sql of schemaStatements) await store.executeSql(sql);
    this.store = store;
    return store;
  }

  private async ensureStore(): Promise<relationalStore.RdbStore> {
    if (this.store !== null) return this.store;
    if (this.initPromise === null) {
      const attempt: Promise<relationalStore.RdbStore> = this.initializeStore();
      this.initPromise = attempt;
      const clearPending = (): void => {
        if (this.initPromise === attempt) this.initPromise = null;
      };
      attempt.then(clearPending, clearPending);
    }
    return this.initPromise;
  }

  private async queryOne(predicates: relationalStore.RdbPredicates): Promise<DeepReadCacheEntry | null> {
    const store: relationalStore.RdbStore = await this.ensureStore();
    const resultSet: relationalStore.ResultSet = await store.query(predicates, COLUMNS);
    try {
      if (!resultSet.goToNextRow()) return null;
      return rowToEntry(rowToCacheRow(resultSet));
    } finally {
      resultSet.close();
    }
  }

  private async queryById(topicId: string): Promise<DeepReadCacheEntry | null> {
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('topic_id', topicId);
    return this.queryOne(predicates);
  }

  private async notify(topicId: string): Promise<void> {
    const state: ListenerSet | undefined = this.observers.get(topicId);
    if (state !== undefined) {
      state.version += 1;
      const current: DeepReadCacheEntry | null = await this.queryById(topicId);
      state.current = current;
      for (const listener of state.listeners) {
        try { listener(current); } catch (_) { /* one listener must not block others */ }
      }
    }
    for (const item of this.historyObservers) {
      const limit: number = item[0];
      const history: HistoryListenerSet = item[1];
      history.version += 1;
      const entries: DeepReadCacheEntry[] = await this.listHistory(limit);
      history.current = entries;
      for (const listener of history.listeners) {
        try { listener(entries); } catch (_) { /* one listener must not block others */ }
      }
    }
  }

  private async saveEntry(entry: DeepReadCacheEntry, broadcast: boolean = true): Promise<void> {
    const store: relationalStore.RdbStore = await this.ensureStore();
    await store.insert(TABLE, entryBucket(entry), relationalStore.ConflictResolution.ON_CONFLICT_REPLACE);
    if (broadcast) await this.notify(entry.topicId);
  }

  async get(topicId: string): Promise<DeepReadCacheEntry | null> {
    return this.queryById(topicId);
  }

  async getByUrl(url: string): Promise<DeepReadCacheEntry | null> {
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('source_url', url);
    predicates.orderByDesc('updated_at');
    predicates.limitAs(1);
    return this.queryOne(predicates);
  }

  async getByTitle(title: string): Promise<DeepReadCacheEntry | null> {
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('title', title);
    predicates.orderByDesc('updated_at');
    predicates.limitAs(1);
    return this.queryOne(predicates);
  }

  async materializeFresh(topicId: string, title: string, url: string | null): Promise<DeepReadCacheEntry | null> {
    const now: number = Date.now();
    const direct: DeepReadCacheEntry | null = await this.get(topicId);
    if (direct !== null && direct.expiresAt > now) return direct;
    let fallback: DeepReadCacheEntry | null = null;
    if (url !== null && url.length > 0) {
      const byUrl: DeepReadCacheEntry | null = await this.getByUrl(url);
      if (byUrl !== null && byUrl.expiresAt > now) fallback = byUrl;
    }
    if (fallback === null && title.length > 0) {
      const byTitle: DeepReadCacheEntry | null = await this.getByTitle(title);
      if (byTitle !== null && byTitle.expiresAt > now) fallback = byTitle;
    }
    const candidate: DeepReadCacheEntry | null = fallback;
    if (candidate === null) return null;
    if (candidate.topicId === topicId) return candidate;
    const materialized: DeepReadCacheEntry = {
      topicId: topicId,
      title: title,
      sourceUrl: url ?? candidate.sourceUrl,
      output: candidate.output,
      templateId: candidate.output.templateSnapshot?.id ?? candidate.output.templateId,
      phase: candidate.phase,
      attemptCount: candidate.attemptCount,
      lastError: candidate.lastError,
      createdAt: candidate.createdAt,
      updatedAt: now,
      expiresAt: candidate.expiresAt,
    };
    await this.upsert(materialized);
    return materialized;
  }

  async upsert(entry: DeepReadCacheEntry): Promise<void> {
    return this.enqueueMutation(async (): Promise<void> => this.saveEntry(entry));
  }

  async updatePhase(topicId: string, phase: DeepReadGenerationPhase): Promise<void> {
    return this.enqueueMutation(async (): Promise<void> => {
      const entry: DeepReadCacheEntry | null = await this.get(topicId);
      if (entry === null) return;
      await this.saveEntry(entryWithFields(entry, entry.output, phase, entry.attemptCount, entry.lastError, Date.now()));
    });
  }

  async updateSectionState(topicId: string, stage: DeepReadGenerationStage, state: DeepReadSectionState): Promise<void> {
    return this.enqueueMutation(async (): Promise<void> => {
      const entry: DeepReadCacheEntry | null = await this.get(topicId);
      if (entry === null) return;
      const output: DeepReadOutput = outputWithSectionState(entry.output, stage, state);
      await this.saveEntry(entryWithFields(entry, output, entry.phase, entry.attemptCount, entry.lastError, Date.now()));
    });
  }

  async incrementAttempt(topicId: string, error: string): Promise<void> {
    return this.enqueueMutation(async (): Promise<void> => {
      const entry: DeepReadCacheEntry | null = await this.get(topicId);
      if (entry === null) return;
      await this.saveEntry(entryWithFields(
        entry, entry.output, entry.phase, entry.attemptCount + 1, error, Date.now(),
      ));
    });
  }

  async resetAttempt(topicId: string): Promise<void> {
    return this.enqueueMutation(async (): Promise<void> => {
      const entry: DeepReadCacheEntry | null = await this.get(topicId);
      if (entry === null) return;
      await this.saveEntry(entryWithFields(entry, entry.output, entry.phase, 0, null, Date.now()));
    });
  }

  observe(topicId: string): Observable<DeepReadCacheEntry | null> {
    let state: ListenerSet | undefined = this.observers.get(topicId);
    if (state === undefined) {
      state = {
        listeners: new Set<(value: DeepReadCacheEntry | null) => void>(),
        current: undefined,
        version: 0,
      };
      this.observers.set(topicId, state);
    }
    const local: ListenerSet = state;
    return {
      subscribe: (listener: (value: DeepReadCacheEntry | null) => void): (() => void) => {
        local.listeners.add(listener);
        if (local.current !== undefined) {
          try { listener(local.current); } catch (_) { /* ignore */ }
        } else {
          const initialVersion: number = local.version;
          this.get(topicId).then((value: DeepReadCacheEntry | null): void => {
            if (local.version !== initialVersion) return;
            local.current = value;
            if (local.listeners.has(listener)) {
              try { listener(value); } catch (_) { /* ignore */ }
            }
          }).catch((): void => {});
        }
        return (): void => {
          local.listeners.delete(listener);
          if (local.listeners.size === 0) this.observers.delete(topicId);
        };
      },
      getCurrent: (): DeepReadCacheEntry | null | undefined => local.current,
    };
  }

  async listHistory(limit: number): Promise<DeepReadCacheEntry[]> {
    const store: relationalStore.RdbStore = await this.ensureStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.orderByDesc('updated_at');
    if (limit > 0) predicates.limitAs(Math.floor(limit));
    const resultSet: relationalStore.ResultSet = await store.query(predicates, COLUMNS);
    try {
      const result: DeepReadCacheEntry[] = [];
      while (resultSet.goToNextRow()) {
        const entry: DeepReadCacheEntry | null = rowToEntry(rowToCacheRow(resultSet));
        if (entry !== null) result.push(entry);
      }
      return result;
    } finally {
      resultSet.close();
    }
  }

  observeHistory(limit: number): Observable<DeepReadCacheEntry[]> {
    const bound: number = limit > 0 ? Math.floor(limit) : 0;
    let current: DeepReadCacheEntry[] | undefined;
    return {
      subscribe: (listener: (value: DeepReadCacheEntry[]) => void): (() => void) => {
        let state: HistoryListenerSet | undefined = this.historyObservers.get(bound);
        if (state === undefined) {
          state = { listeners: new Set<(value: DeepReadCacheEntry[]) => void>(), current: undefined, version: 0 };
          this.historyObservers.set(bound, state);
        }
        const local: HistoryListenerSet = state;
        const callback = (entries: DeepReadCacheEntry[]): void => { current = entries; listener(entries); };
        local.listeners.add(callback);
        if (local.current !== undefined) callback(local.current);
        else {
          const initialVersion: number = local.version;
          this.listHistory(bound).then((entries: DeepReadCacheEntry[]): void => {
            if (!local.listeners.has(callback) || local.version !== initialVersion) return;
            local.current = entries;
            callback(entries);
          }).catch((error: Error): void => {
            hilog.warn(DOMAIN, TAG, 'history subscription initial load failed: %{public}s', error.message);
          });
        }
        return (): void => {
          local.listeners.delete(callback);
          if (local.listeners.size === 0 && this.historyObservers.get(bound) === local) this.historyObservers.delete(bound);
        };
      },
      getCurrent: (): DeepReadCacheEntry[] | undefined => current,
    };
  }

  async delete(topicId: string): Promise<void> {
    return this.enqueueMutation(async (): Promise<void> => {
      const store: relationalStore.RdbStore = await this.ensureStore();
      const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
      predicates.equalTo('topic_id', topicId);
      await store.delete(predicates);
      await this.notify(topicId);
    });
  }

  async purgeExpired(): Promise<number> {
    return this.enqueueMutation(async (): Promise<number> => {
      const store: relationalStore.RdbStore = await this.ensureStore();
      const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
      predicates.lessThan('expires_at', Date.now());
      const topicIds: string[] = await this.topicIdsFor(predicates, store);
      const deleted: number = await store.delete(predicates);
      for (const topicId of topicIds) await this.notify(topicId);
      return deleted;
    });
  }

  async purgeHistoryRetention(): Promise<number> {
    return this.enqueueMutation(async (): Promise<number> => {
      const store: relationalStore.RdbStore = await this.ensureStore();
      const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
      predicates.lessThan('expires_at', Date.now() - HISTORY_RETENTION_MS);
      const topicIds: string[] = await this.topicIdsFor(predicates, store);
      const deleted: number = await store.delete(predicates);
      for (const topicId of topicIds) await this.notify(topicId);
      return deleted;
    });
  }

  private async topicIdsFor(
    predicates: relationalStore.RdbPredicates,
    store: relationalStore.RdbStore,
  ): Promise<string[]> {
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['topic_id']);
    try {
      const topicIds: string[] = [];
      while (resultSet.goToNextRow()) topicIds.push(resultSet.getString(0));
      return topicIds;
    } finally {
      resultSet.close();
    }
  }
}

export const createRdbRepository = (): DeepReadRepository => {
  hilog.info(DOMAIN, TAG, 'createRdbRepository — relationalStore');
  return new RdbDeepReadRepository();
};

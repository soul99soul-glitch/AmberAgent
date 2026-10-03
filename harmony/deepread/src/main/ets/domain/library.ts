import type { DeepReadCacheEntry, DeepReadRepository } from '../platform/repository.ts';
import type { Observable } from '../platform/observable.ts';
import type { DeepReadActiveRun, DeepReadScheduler } from '../agent/scheduler.ts';
import { firstFailedStage, isComplete } from './helpers.ts';
import { deepReadToText } from './export.ts';

export type DeepReadLibraryStatus = 'running' | 'complete' | 'failed' | 'incomplete';
export type DeepReadLibraryFilter = 'all' | DeepReadLibraryStatus;

export interface DeepReadLibrarySnapshot {
  entries: DeepReadCacheEntry[];
  activeRuns: DeepReadActiveRun[];
}

/** Real admitted tasks without a persisted draft are represented without inventing a cache entry. */
export interface DeepReadLibraryRow {
  topicId: string;
  title: string;
  updatedAt: number;
  status: DeepReadLibraryStatus;
  entry: DeepReadCacheEntry | null;
}

export const observeDeepReadLibrary = (
  repository: DeepReadRepository, scheduler: DeepReadScheduler | null = null, limit: number = 0,
): Observable<DeepReadLibrarySnapshot> => {
  let current: DeepReadLibrarySnapshot | undefined;
  return {
    subscribe(callback: (snapshot: DeepReadLibrarySnapshot) => void): () => void {
      let entries: DeepReadCacheEntry[] | undefined;
      let activeRuns: DeepReadActiveRun[] = scheduler?.getActiveRuns?.() ?? [];
      const publish = (): void => {
        if (entries === undefined) return;
        current = { entries, activeRuns };
        callback(current);
      };
      const stopHistory = repository.observeHistory(limit).subscribe(value => { entries = value; publish(); });
      const stopRuns = scheduler?.observeActiveRuns().subscribe(value => { activeRuns = value; publish(); });
      return (): void => { stopHistory(); stopRuns?.(); };
    },
    getCurrent(): DeepReadLibrarySnapshot | undefined { return current; },
  };
};

export const queryDeepReadLibraryRows = (
  snapshot: DeepReadLibrarySnapshot, query: string, filter: DeepReadLibraryFilter,
): DeepReadLibraryRow[] => {
  const activeIds = snapshot.activeRuns.map(run => run.topicId);
  const rows: DeepReadLibraryRow[] = queryDeepReadLibrary(snapshot.entries, query, filter, activeIds).map(entry => ({
    topicId: entry.topicId, title: entry.title, updatedAt: entry.updatedAt,
    status: deepReadLibraryStatus(entry, activeIds), entry,
  }));
  if (filter === 'all' || filter === 'running') {
    const keyword = query.trim().toLocaleLowerCase();
    for (const run of snapshot.activeRuns) {
      if (snapshot.entries.some(entry => entry.topicId === run.topicId)) continue;
      if (keyword.length > 0 && !run.title.toLocaleLowerCase().includes(keyword)) continue;
      rows.push({ topicId: run.topicId, title: run.title, updatedAt: run.startedAt, status: 'running', entry: null });
    }
  }
  return rows.sort((a, b) => b.updatedAt - a.updatedAt);
};

export const deepReadLibraryStatus = (
  entry: DeepReadCacheEntry, activeTopicIds: string[] = [],
): DeepReadLibraryStatus => {
  if (activeTopicIds.includes(entry.topicId)) return 'running';
  if (isComplete(entry.output)) return 'complete';
  if (firstFailedStage(entry.output) !== null || (entry.lastError ?? '').trim().length > 0) return 'failed';
  return 'incomplete';
};

export const queryDeepReadLibrary = (
  entries: DeepReadCacheEntry[], query: string, filter: DeepReadLibraryFilter,
  activeTopicIds: string[] = [],
): DeepReadCacheEntry[] => {
  const keyword: string = query.trim().toLocaleLowerCase();
  return entries.filter((entry: DeepReadCacheEntry): boolean => {
    if (filter !== 'all' && deepReadLibraryStatus(entry, activeTopicIds) !== filter) return false;
    return keyword.length === 0 || entry.title.toLocaleLowerCase().includes(keyword)
      || deepReadToText(entry).toLocaleLowerCase().includes(keyword);
  });
};

// 首页只将真实作业显示为「运行中」；持久 phase 用于给中断/失败稿提供继续入口。
import type { ContinueCandidate, ContinueStatus } from '@amber/chat-domain';
import type { DeepReadActiveRun, DeepReadCacheEntry, DeepReadRepository, DeepReadScheduler } from '@amber/deepread-domain';

export const deepReadContinueCandidates = (
  entries: DeepReadCacheEntry[], scheduler: DeepReadScheduler,
): ContinueCandidate[] => {
  const candidates: ContinueCandidate[] = [];
  const activeIds: Set<string> = new Set<string>();
  const activeRuns: DeepReadActiveRun[] = scheduler.getActiveRuns?.() ?? [];
  for (const active of activeRuns) {
    activeIds.add(active.topicId);
    const entry: DeepReadCacheEntry | undefined = entries.find(
      (row: DeepReadCacheEntry): boolean => row.topicId === active.topicId);
    candidates.push({
      sourceKind: 'deepread',
      sourceId: active.topicId,
      route: { kind: 'deepread', topicId: active.topicId, title: active.title },
      title: active.title.length > 0 ? active.title : '深度阅读',
      summary: '正在生成',
      lastUpdatedAt: Math.max(active.startedAt, entry?.updatedAt ?? 0),
      status: 'running',
      priority: 0,
      isRunning: true,
    });
  }
  for (const entry of entries) {
    if (activeIds.has(entry.topicId)) continue;
    const running: boolean = scheduler.isRunning(entry.topicId);
    if (!running && entry.output.generationComplete) continue;
    let failed: boolean = entry.lastError !== null && entry.lastError.trim().length > 0;
    const sectionNames: string[] = Object.keys(entry.output.sectionStates);
    for (const name of sectionNames) {
      if (entry.output.sectionStates[name].status === 'FAILED') failed = true;
    }
    const hasProgress: boolean = entry.phase !== 'IDLE' || sectionNames.length > 0;
    const status: ContinueStatus = running ? 'running' : failed ? 'failed_resumable'
      : hasProgress ? 'paused' : 'waiting_user';
    const summary: string = running ? '正在生成' : failed ? '生成失败，可重试'
      : hasProgress ? '已暂停，可继续' : '待开始';
    candidates.push({
      sourceKind: 'deepread',
      sourceId: entry.topicId,
      route: { kind: 'deepread', topicId: entry.topicId, title: entry.title },
      title: entry.title.length > 0 ? entry.title : '深度阅读',
      summary: summary,
      lastUpdatedAt: entry.updatedAt,
      status: status,
      priority: 0,
      isRunning: running,
    });
  }
  candidates.sort((a: ContinueCandidate, b: ContinueCandidate): number => {
    if (a.isRunning !== b.isRunning) return a.isRunning ? -1 : 1;
    return b.lastUpdatedAt - a.lastUpdatedAt;
  });
  return candidates.slice(0, 3);
};

export const loadDeepReadContinueCandidates = async (
  repository: DeepReadRepository, scheduler: DeepReadScheduler,
): Promise<ContinueCandidate[]> => {
  const entries: DeepReadCacheEntry[] = await repository.listHistory(100);
  return deepReadContinueCandidates(entries, scheduler);
};

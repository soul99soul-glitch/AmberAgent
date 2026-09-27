// continue_candidate — 首页「继续」聚合（Android ContinueCandidate 对齐）
//
// 纯逻辑：候选模型 + 排序 + 合并；数据源由 entry 注入投影（会话/小说/深读/看板）。

export type ContinueSourceKind =
  | 'chat' | 'novel' | 'deepread' | 'council' | 'miniapp' | 'board' | 'cron' | 'image';

export type ContinueStatus =
  | 'draft' | 'paused' | 'waiting_user' | 'failed_resumable' | 'running';

export type ContinueRoute =
  | { kind: 'chat'; conversationId: string; nodeId?: string }
  | { kind: 'novel'; projectId: string; branchId?: string; jobId?: string }
  | { kind: 'deepread'; topicId: string; title: string }
  | { kind: 'council'; conversationId: string; runId?: string }
  | { kind: 'miniapp'; appId: string }
  | { kind: 'board' }
  | { kind: 'cron'; taskId?: string };

export interface ContinueCandidate {
  sourceKind: ContinueSourceKind;
  sourceId: string;
  route: ContinueRoute;
  title: string;
  summary: string;
  lastUpdatedAt: number;
  status: ContinueStatus;
  priority: number;
  isRunning: boolean;
}

export const STATUS_RANK: Record<ContinueStatus, number> = {
  running: 0,
  waiting_user: 1,
  paused: 2,
  failed_resumable: 3,
  draft: 4,
};

export const SOURCE_LABEL: Record<ContinueSourceKind, string> = {
  chat: '对话',
  novel: '小说',
  deepread: '深读',
  council: '议会',
  miniapp: '小应用',
  board: '看板',
  cron: '定时',
  image: '生图',
};

export const mergeContinueCandidates = (
  lists: ContinueCandidate[][],
  limit: number = 12,
): ContinueCandidate[] => {
  const flat: ContinueCandidate[] = [];
  for (const list of lists) {
    for (const item of list) flat.push(item);
  }
  flat.sort((a, b): number => {
    if (a.priority !== b.priority) return b.priority - a.priority;
    const ra = STATUS_RANK[a.status];
    const rb = STATUS_RANK[b.status];
    if (ra !== rb) return ra - rb;
    return b.lastUpdatedAt - a.lastUpdatedAt;
  });
  return flat.slice(0, Math.max(0, limit));
};

export const continueStatusText = (status: ContinueStatus): string => {
  switch (status) {
    case 'running': return '进行中';
    case 'waiting_user': return '待你确认';
    case 'paused': return '已暂停';
    case 'failed_resumable': return '可继续';
    case 'draft': return '草稿';
  }
};

// memory_portrait — 确定性三维记忆画像构建（身份与项目 / 偏好与禁区 / 近期议题）
// 零网络调用：纯函数清洗、去重、分桶并产出灵感对话起手句。

import type { MemoryKind, MemoryRecord, MemoryScope } from './memory_models.ts';

export type MemoryPortraitDimensionKey = 'identity_project' | 'preferences_boundaries' | 'recent_topics';
export type MemoryPortraitBucketFilter = 'all' | 'core' | 'short_term' | 'long_term';

export interface MemoryPortraitItem {
  id: number;
  content: string;
  kind: MemoryKind;
  scope: MemoryScope;
  pinned: boolean;
  topicTitle: string | null;
  updatedAt: number;
  reinforcementCount: number;
  sourceConversationId: string | null;
}

export interface MemoryPortraitDimension {
  key: MemoryPortraitDimensionKey;
  title: string;
  subtitle: string;
  items: MemoryPortraitItem[];
}

export interface MemoryPortrait {
  bucket: MemoryPortraitBucketFilter;
  totalActiveCount: number;
  filteredCount: number;
  dedupedCount: number;
  dimensions: MemoryPortraitDimension[];
  starterPrompts: string[];
}

export interface MemoryPortraitOptions {
  bucket?: MemoryPortraitBucketFilter;
  maxItemsPerDimension?: number;
  maxStarterPrompts?: number;
  nowMs?: number;
}

interface DeduplicateResult {
  kept: MemoryRecord[];
  dedupedCount: number;
}

const DEFAULT_STARTER_PROMPTS: string[] = [
  '帮我梳理今天最值得先推进的一件事',
  '回顾最近我们聊过的进展，看看还有什么没收口',
  '记住一个我的长期偏好或工作习惯',
  '基于你对我的了解，给我三个有启发的新点子',
];

const normalizeForDedup = (text: string): string => {
  return text
    .trim()
    .toLowerCase()
    .replace(/[\s.,!?;:'"()\[\]{}，。！？；：、“”‘’（）【】\-_·/\\]+/g, '');
};

const isPortraitEligibleRecord = (record: MemoryRecord, nowMs: number): boolean => {
  if (record.archived) return false;
  if (record.invalidatedAt !== undefined && record.invalidatedAt !== null && record.invalidatedAt > 0) {
    return false;
  }
  if (record.expiresAt !== null && record.expiresAt <= nowMs) return false;
  return record.content.trim().length > 0;
};

const recordPriority = (record: MemoryRecord): number => {
  const pinnedScore: number = record.pinned ? 1_000_000_000 : 0;
  const reinforcementScore: number = (record.reinforcementCount ?? 0) * 100_000;
  const confidenceScore: number = Math.round(record.confidence * 10_000);
  const recencyScore: number = Math.floor(record.updatedAt / 1000);
  return pinnedScore + reinforcementScore + confidenceScore + recencyScore;
};

const isNearDuplicateNormalized = (a: string, b: string): boolean => {
  if (a.length === 0 || b.length === 0) return false;
  if (a === b) return true;
  const shorter: string = a.length <= b.length ? a : b;
  const longer: string = a.length > b.length ? a : b;
  if (shorter.length < 10) return false;
  if (longer.indexOf(shorter) < 0) return false;
  return shorter.length / longer.length >= 0.72;
};

const deduplicateMemoryRecords = (records: MemoryRecord[]): DeduplicateResult => {
  const ordered: MemoryRecord[] = records.slice().sort((a: MemoryRecord, b: MemoryRecord): number => {
    const diff: number = recordPriority(b) - recordPriority(a);
    if (diff !== 0) return diff;
    return b.id - a.id;
  });
  const kept: MemoryRecord[] = [];
  const normList: string[] = [];
  let dedupedCount: number = 0;

  for (const record of ordered) {
    const norm: string = normalizeForDedup(record.content);
    let duplicated: boolean = false;
    for (const seen of normList) {
      if (isNearDuplicateNormalized(norm, seen)) {
        duplicated = true;
        break;
      }
    }
    if (duplicated) {
      dedupedCount += 1;
      continue;
    }
    kept.push(record);
    normList.push(norm);
  }
  return { kept, dedupedCount };
};

const dimensionOfRecord = (record: MemoryRecord): MemoryPortraitDimensionKey => {
  if (record.kind === 'feedback' || record.kind === 'routine') {
    return 'preferences_boundaries';
  }
  if (record.kind === 'user' || record.kind === 'project') {
    return 'identity_project';
  }
  if (record.kind === 'topic' || record.scope === 'short_term') {
    return 'recent_topics';
  }
  if (record.scope === 'core') {
    return 'identity_project';
  }
  return 'recent_topics';
};

const compareByImportance = (a: MemoryRecord, b: MemoryRecord): number => {
  if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
  const reinfDiff: number = (b.reinforcementCount ?? 0) - (a.reinforcementCount ?? 0);
  if (reinfDiff !== 0) return reinfDiff;
  const confDiff: number = b.confidence - a.confidence;
  if (Math.abs(confDiff) > 0.001) return confDiff > 0 ? 1 : -1;
  const timeDiff: number = b.updatedAt - a.updatedAt;
  if (timeDiff !== 0) return timeDiff;
  return b.id - a.id;
};

const compareByRecency = (a: MemoryRecord, b: MemoryRecord): number => {
  if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
  const timeDiff: number = b.updatedAt - a.updatedAt;
  if (timeDiff !== 0) return timeDiff;
  return b.id - a.id;
};

const toPortraitItem = (record: MemoryRecord): MemoryPortraitItem => ({
  id: record.id,
  content: record.content.trim(),
  kind: record.kind,
  scope: record.scope,
  pinned: record.pinned,
  topicTitle: record.topicTitle,
  updatedAt: record.updatedAt,
  reinforcementCount: record.reinforcementCount ?? 0,
  sourceConversationId: record.sourceConversationId,
});

const compactSnippet = (raw: string, maxChars: number = 22): string => {
  const line: string = raw.replace(/\s+/g, ' ').trim();
  if (line.length <= maxChars) return line;
  return `${line.slice(0, maxChars - 1)}…`;
};

export const buildMemoryStarterPrompts = (
  dimensions: MemoryPortraitDimension[],
  maxCount: number = 4,
): string[] => {
  const target: number = Math.max(1, maxCount);
  const prompts: string[] = [];
  const pushUnique = (text: string): void => {
    const clean: string = text.trim();
    if (clean.length === 0 || prompts.indexOf(clean) >= 0) return;
    prompts.push(clean);
  };

  const recentDim: MemoryPortraitDimension | undefined = dimensions.find(
    (d: MemoryPortraitDimension): boolean => d.key === 'recent_topics',
  );
  const identityDim: MemoryPortraitDimension | undefined = dimensions.find(
    (d: MemoryPortraitDimension): boolean => d.key === 'identity_project',
  );
  const prefDim: MemoryPortraitDimension | undefined = dimensions.find(
    (d: MemoryPortraitDimension): boolean => d.key === 'preferences_boundaries',
  );

  if (recentDim !== undefined && recentDim.items.length > 0) {
    const topRecent: MemoryPortraitItem = recentDim.items[0];
    const label: string = topRecent.topicTitle !== null && topRecent.topicTitle.trim().length > 0
      ? compactSnippet(topRecent.topicTitle, 20)
      : compactSnippet(topRecent.content, 20);
    pushUnique(`继续聊聊「${label}」，把下一步整理清楚`);
  }

  if (identityDim !== undefined && identityDim.items.length > 0) {
    const topIdentity: MemoryPortraitItem = identityDim.items[0];
    pushUnique(`结合「${compactSnippet(topIdentity.content, 20)}」，帮我想想今天可以怎么推进`);
  }

  if (prefDim !== undefined && prefDim.items.length > 0) {
    pushUnique(`按你记住的「偏好与禁区」，先帮我检查手头计划有没有踩坑`);
  }

  if (recentDim !== undefined && recentDim.items.length > 1) {
    const secondRecent: MemoryPortraitItem = recentDim.items[1];
    const label: string = secondRecent.topicTitle !== null && secondRecent.topicTitle.trim().length > 0
      ? compactSnippet(secondRecent.topicTitle, 20)
      : compactSnippet(secondRecent.content, 20);
    pushUnique(`回顾一下「${label}」里还没收口的细节`);
  }

  for (const fallback of DEFAULT_STARTER_PROMPTS) {
    if (prompts.length >= target) break;
    pushUnique(fallback);
  }

  return prompts.slice(0, target);
};

export const buildMemoryPortrait = (
  records: MemoryRecord[],
  options: MemoryPortraitOptions = {},
): MemoryPortrait => {
  const bucket: MemoryPortraitBucketFilter = options.bucket ?? 'all';
  const maxItems: number = Math.max(1, options.maxItemsPerDimension ?? 6);
  const maxStarters: number = Math.max(1, options.maxStarterPrompts ?? 4);
  const nowMs: number = options.nowMs ?? Date.now();

  const active: MemoryRecord[] = records.filter(
    (record: MemoryRecord): boolean => isPortraitEligibleRecord(record, nowMs),
  );
  const scoped: MemoryRecord[] = bucket === 'all'
    ? active
    : active.filter((record: MemoryRecord): boolean => record.scope === bucket);

  const deduped: DeduplicateResult = deduplicateMemoryRecords(scoped);
  const identityRecords: MemoryRecord[] = [];
  const prefRecords: MemoryRecord[] = [];
  const topicRecords: MemoryRecord[] = [];

  for (const record of deduped.kept) {
    const dim: MemoryPortraitDimensionKey = dimensionOfRecord(record);
    if (dim === 'identity_project') identityRecords.push(record);
    else if (dim === 'preferences_boundaries') prefRecords.push(record);
    else topicRecords.push(record);
  }

  identityRecords.sort(compareByImportance);
  prefRecords.sort(compareByImportance);
  topicRecords.sort(compareByRecency);

  const dimensions: MemoryPortraitDimension[] = [
    {
      key: 'identity_project',
      title: '身份与项目',
      subtitle: '你是谁、正在长期推进的事',
      items: identityRecords.slice(0, maxItems).map(toPortraitItem),
    },
    {
      key: 'preferences_boundaries',
      title: '偏好与禁区',
      subtitle: '协作习惯、表达风格与边界',
      items: prefRecords.slice(0, maxItems).map(toPortraitItem),
    },
    {
      key: 'recent_topics',
      title: '近期议题',
      subtitle: '最近反复关注与待续线索',
      items: topicRecords.slice(0, maxItems).map(toPortraitItem),
    },
  ];

  return {
    bucket,
    totalActiveCount: active.length,
    filteredCount: deduped.kept.length,
    dedupedCount: deduped.dedupedCount,
    dimensions,
    starterPrompts: buildMemoryStarterPrompts(dimensions, maxStarters),
  };
};

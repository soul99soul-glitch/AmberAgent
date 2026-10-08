// memory_models — memory 子系统数据模型(MemoryModels.kt + MemoryEnums.kt 逐字)
// Android 锚点:
//   core/model/.../MemoryEnums.kt(MemoryScope/MemoryKind,wireName + fromWireName 回退)
//   core/memory/api/.../MemoryModels.kt(MemoryRecord/MemoryCandidate/MemoryEvent/
//     MemoryCandidateStatus/MemoryEventType/MemoryRecallSetting)
// 本切片(D-085a)只含读路径所需;MemoryWorkerSetting(extraction/dream 写侧)随后续切片。

import { newId } from './ids.ts';

// ===== MemoryScope(MemoryEnums.kt:15-29)— wireName 即联合值 =====
export type MemoryScope = 'core' | 'short_term' | 'long_term';

// fromWireName:未知 → LONG_TERM 回退(:26-27)
export const memoryScopeFromWireName = (value: string | null): MemoryScope => {
  if (value === 'core' || value === 'short_term' || value === 'long_term') return value;
  return 'long_term';
};

// ===== MemoryKind(MemoryEnums.kt:32-55)— wireName 即联合值 =====
export type MemoryKind = 'user' | 'feedback' | 'project' | 'reference' | 'routine' | 'note' | 'topic';

// fromWireName:未知 → NOTE 回退(:52-53)
export const memoryKindFromWireName = (value: string | null): MemoryKind => {
  if (value === 'user' || value === 'feedback' || value === 'project' ||
    value === 'reference' || value === 'routine' || value === 'note' || value === 'topic') {
    return value;
  }
  return 'note';
};

// ===== MemoryCandidateStatus(MemoryModels.kt:20-37)— fromWireName → PENDING =====
export type MemoryCandidateStatus = 'pending' | 'accepted' | 'ignored' | 'filtered';

export const memoryCandidateStatusFromWireName = (value: string | null): MemoryCandidateStatus => {
  if (value === 'pending' || value === 'accepted' || value === 'ignored' || value === 'filtered') {
    return value;
  }
  return 'pending';
};

// ===== MemoryEventType(MemoryModels.kt:39-70)=====
// toEvent 回退(:291):未知 → EXTRACTION_SKIPPED
export type MemoryEventType =
  | 'extraction_started' | 'extraction_skipped' | 'candidate_created'
  | 'candidate_accepted' | 'candidate_ignored' | 'memory_created'
  | 'durable_memory_created' | 'memory_updated' | 'memory_archived'
  | 'memory_expired' | 'memory_restored' | 'memory_invalidated' | 'extraction_failed' | 'dream_planned'
  | 'dream_applied' | 'dream_failed';

export const memoryEventTypeFromWireName = (value: string | null): MemoryEventType => {
  switch (value) {
    case 'extraction_started':
    case 'extraction_skipped':
    case 'candidate_created':
    case 'candidate_accepted':
    case 'candidate_ignored':
    case 'memory_created':
    case 'durable_memory_created':
    case 'memory_updated':
    case 'memory_archived':
    case 'memory_expired':
    case 'memory_restored':
    case 'memory_invalidated':
    case 'extraction_failed':
    case 'dream_planned':
    case 'dream_applied':
    case 'dream_failed':
      return value;
    default:
      return 'extraction_skipped';
  }
};

// ===== MemoryRecord(MemoryModels.kt:72-88)=====
export interface MemoryRecord {
  id: number; // Room 自增 Int
  content: string;
  scope: MemoryScope;
  kind: MemoryKind;
  assistantId: string;
  sourceConversationId: string | null;
  sourceMessageIds: string[];
  supersedesIds: number[];
  expiresAt: number | null; // epoch ms
  confidence: number; // Float,0..1
  pinned: boolean;
  archived: boolean;
  createdAt: number;
  updatedAt: number;
  lastUsedAt: number | null;
  reinforcementCount?: number;
  lastReinforcedAt?: number | null;
  lastReinforcementSource?: string | null;
  invalidatedAt?: number | null;
  evidence?: string | null;
  topicTitle: string | null;
  memberIds: number[];
}

export const makeMemoryRecord = (
  opts: Partial<MemoryRecord> & Pick<MemoryRecord, 'id' | 'content' | 'scope' | 'kind'>,
): MemoryRecord => ({
  id: opts.id,
  content: opts.content,
  scope: opts.scope,
  kind: opts.kind,
  assistantId: opts.assistantId ?? '',
  sourceConversationId: opts.sourceConversationId ?? null,
  sourceMessageIds: opts.sourceMessageIds ?? [],
  supersedesIds: opts.supersedesIds ?? [],
  expiresAt: opts.expiresAt ?? null,
  confidence: opts.confidence ?? 1,
  pinned: opts.pinned ?? false,
  archived: opts.archived ?? false,
  createdAt: opts.createdAt ?? 0,
  updatedAt: opts.updatedAt ?? 0,
  lastUsedAt: opts.lastUsedAt ?? null,
  reinforcementCount: opts.reinforcementCount ?? 0,
  lastReinforcedAt: opts.lastReinforcedAt ?? null,
  lastReinforcementSource: opts.lastReinforcementSource ?? null,
  invalidatedAt: opts.invalidatedAt ?? null,
  evidence: opts.evidence ?? null,
  topicTitle: opts.topicTitle ?? null,
  memberIds: opts.memberIds ?? [],
});

// ===== MemoryCandidate(MemoryModels.kt:90-105)=====
export interface MemoryCandidate {
  id: string;
  content: string;
  scope: MemoryScope;
  kind: MemoryKind;
  sourceConversationId: string | null;
  sourceMessageIds: string[];
  expiresAt: number | null;
  confidence: number;
  reason: string;
  evidence?: string | null;
  sensitive: boolean;
  status: MemoryCandidateStatus;
  createdAt: number;
  updatedAt: number;
}

export const makeMemoryCandidate = (
  opts: Partial<MemoryCandidate> & Pick<MemoryCandidate, 'content' | 'scope' | 'kind'>,
): MemoryCandidate => {
  const createdAt: number = opts.createdAt ?? Date.now();
  return {
    id: opts.id ?? newId(),
    content: opts.content,
    scope: opts.scope,
    kind: opts.kind,
    sourceConversationId: opts.sourceConversationId ?? null,
    sourceMessageIds: opts.sourceMessageIds ?? [],
    expiresAt: opts.expiresAt ?? null,
    confidence: opts.confidence ?? 0.5,
    reason: opts.reason ?? '',
    evidence: opts.evidence ?? null,
    sensitive: opts.sensitive ?? false,
    status: opts.status ?? 'pending',
    createdAt,
    updatedAt: opts.updatedAt ?? createdAt, // updatedAt = createdAt(:104)
  };
};

// ===== MemoryEvent(MemoryModels.kt:107-119)=====
export interface MemoryEvent {
  id: string;
  type: MemoryEventType;
  conversationId: string | null;
  memoryId: number | null;
  candidateId: string | null;
  modelId: string | null;
  message: string;
  durationMs: number | null;
  messageCount: number | null;
  createdAt: number;
}

export const makeMemoryEvent = (
  opts: Partial<MemoryEvent> & Pick<MemoryEvent, 'type'>,
): MemoryEvent => ({
  id: opts.id ?? newId(),
  type: opts.type,
  conversationId: opts.conversationId ?? null,
  memoryId: opts.memoryId ?? null,
  candidateId: opts.candidateId ?? null,
  modelId: opts.modelId ?? null,
  message: opts.message ?? '',
  durationMs: opts.durationMs ?? null,
  messageCount: opts.messageCount ?? null,
  createdAt: opts.createdAt ?? Date.now(),
});

// ===== MemoryRecallSetting(MemoryModels.kt:121-125)=====
export interface MemoryRecallSetting {
  maxItems: number;
  maxPromptChars: number;
  debug: boolean;
}

export const DEFAULT_MEMORY_RECALL_SETTING: MemoryRecallSetting = Object.freeze({
  maxItems: 12,
  maxPromptChars: 2000,
  debug: false,
});

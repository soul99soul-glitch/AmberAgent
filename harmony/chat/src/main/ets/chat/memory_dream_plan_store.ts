// memory_dream_plan_store — dream plan 持久化纯逻辑(D-085g)
//
// Android 基准:
//   - MemoryDreamPlanStore.kt(全文 143 行:savePending :49-71 / recordAutoRun :73-93 /
//     saveApplied :95-117 / markApplied/markDismissed :119-125 / toPersisted :127-136 /
//     toStatus/toSource 回退 :138-142)
//   - MemoryDreamPlanDAO.kt(查询语义:getPendingPlan status='pending' ORDER created_at
//     DESC LIMIT 1;countPlansSince source+created_at>=;updatePendingStatus WHERE
//     status='pending' 全量;markApplied/markDismissed by id;insert REPLACE)
//   - MemoryDreamPlanEntity.kt(列形状)
// 序列化:kotlinx JsonInstant(encodeDefaults=true + explicitNulls)→ 全字段总是序列化,
//   camelCase 声明序;scope/kind 枚举按 @SerialName = wireName 字符串;
//   解码:缺省字段用声明默认(mergedContent=null/confidence=0.7f/reason=''),
//   未知枚举值 kotlinx 抛 SerializationException → 本实现同样抛错(不用 fromWireName 回退)。

import { newId } from './ids.ts';
import type {
  MemoryDreamPlan, MemoryMergeSuggestion, MemorySupersedeSuggestion,
} from './memory_dream.ts';
import { memoryDreamPlanSummaryText } from './memory_dream.ts';
import type { MemoryKind, MemoryScope } from './memory_models.ts';

// ===== 状态/来源枚举(MemoryDreamPlanStore.kt:10-19)=====
export type MemoryDreamPlanStatus = 'pending' | 'applied' | 'dismissed';
export type MemoryDreamPlanSource = 'manual' | 'auto';

// toStatus(:138-139):未知 → PENDING
export const memoryDreamPlanStatusFromWireName = (value: string | null): MemoryDreamPlanStatus =>
  value === 'applied' || value === 'dismissed' ? value : 'pending';

// toSource(:141-142):未知 → MANUAL
export const memoryDreamPlanSourceFromWireName = (value: string | null): MemoryDreamPlanSource =>
  value === 'auto' ? 'auto' : 'manual';

// ===== PersistedMemoryDreamPlan(:21-34)=====
export interface PersistedMemoryDreamPlan {
  id: string;
  plan: MemoryDreamPlan;
  status: MemoryDreamPlanStatus;
  source: MemoryDreamPlanSource;
  createdAt: number;
  appliedAt: number | null;
  dismissedAt: number | null;
}

// summary getter(:30-33)= memoryDreamPlanSummaryText
export const persistedDreamPlanSummary = (persisted: PersistedMemoryDreamPlan): string =>
  memoryDreamPlanSummaryText(persisted.plan);

// ===== Entity(MemoryDreamPlanEntity.kt 列形状)=====
export interface MemoryDreamPlanEntity {
  id: string;
  planJson: string;
  status: string;
  source: string;
  mergeCount: number;
  promoteCount: number;
  archiveCount: number;
  supersedeCount: number;
  ignoreCandidateCount: number;
  createdAt: number;
  appliedAt: number | null;
  dismissedAt: number | null;
}

// ===== plan JSON 线格式 =====

const scopeToWire = (scope: MemoryScope): string => scope;
const kindToWire = (kind: MemoryKind): string => kind;

const SCOPES: readonly string[] = ['core', 'short_term', 'long_term'];
const KINDS: readonly string[] = ['user', 'feedback', 'project', 'reference', 'routine', 'note'];

// kotlinx 枚举解码:未知 serial name → 抛错(非 fromWireName 回退)
const strictScope = (value: unknown): MemoryScope => {
  if (typeof value === 'string' && SCOPES.indexOf(value) >= 0) return value as MemoryScope;
  throw new Error(`Unknown MemoryScope serial name: ${String(value)}`);
};
const strictKind = (value: unknown): MemoryKind => {
  if (typeof value === 'string' && KINDS.indexOf(value) >= 0) return value as MemoryKind;
  throw new Error(`Unknown MemoryKind serial name: ${String(value)}`);
};

// encodeToString(serializer):encodeDefaults=true → 全部 6 字段总是输出,声明序
export const encodeDreamPlanJson = (plan: MemoryDreamPlan): string => JSON.stringify({
  mergeSuggestions: plan.mergeSuggestions.map((s: MemoryMergeSuggestion): object => ({
    targetMemoryId: s.targetMemoryId,
    duplicateMemoryIds: s.duplicateMemoryIds,
    mergedContent: s.mergedContent, // explicitNulls:null 字面量输出
    reason: s.reason,
  })),
  promoteMemoryIds: plan.promoteMemoryIds,
  archiveMemoryIds: plan.archiveMemoryIds,
  ignoreCandidateIds: plan.ignoreCandidateIds,
  supersedeSuggestions: plan.supersedeSuggestions.map((s: MemorySupersedeSuggestion): object => ({
    oldMemoryIds: s.oldMemoryIds,
    newContent: s.newContent,
    scope: scopeToWire(s.scope),
    kind: kindToWire(s.kind),
    confidence: s.confidence,
    reason: s.reason,
  })),
  notes: plan.notes,
});

// decodeFromString(serializer):缺省字段 → 声明默认
export const decodeDreamPlanJson = (text: string): MemoryDreamPlan => {
  const parsed: unknown = JSON.parse(text);
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('Element is not a JsonObject');
  }
  const root: Record<string, unknown> = parsed as Record<string, unknown>;
  const mergesRaw: unknown[] = Array.isArray(root['mergeSuggestions'])
    ? root['mergeSuggestions'] as unknown[] : [];
  const mergeSuggestions: MemoryMergeSuggestion[] = mergesRaw.map((item: unknown): MemoryMergeSuggestion => {
    const o: Record<string, unknown> = item as Record<string, unknown>;
    const merged: unknown = o['mergedContent'];
    return {
      targetMemoryId: o['targetMemoryId'] as number,
      duplicateMemoryIds: (o['duplicateMemoryIds'] as number[]) ?? [],
      mergedContent: typeof merged === 'string' ? merged : null,
      reason: typeof o['reason'] === 'string' ? o['reason'] as string : '',
    };
  });
  const supersedesRaw: unknown[] = Array.isArray(root['supersedeSuggestions'])
    ? root['supersedeSuggestions'] as unknown[] : [];
  const supersedeSuggestions: MemorySupersedeSuggestion[] = supersedesRaw.map(
    (item: unknown): MemorySupersedeSuggestion => {
      const o: Record<string, unknown> = item as Record<string, unknown>;
      return {
        oldMemoryIds: (o['oldMemoryIds'] as number[]) ?? [],
        newContent: o['newContent'] as string,
        scope: strictScope(o['scope']),
        kind: strictKind(o['kind']),
        confidence: typeof o['confidence'] === 'number' ? o['confidence'] : 0.7,
        reason: typeof o['reason'] === 'string' ? o['reason'] as string : '',
      };
    });
  return {
    mergeSuggestions,
    promoteMemoryIds: (root['promoteMemoryIds'] as number[]) ?? [],
    archiveMemoryIds: (root['archiveMemoryIds'] as number[]) ?? [],
    ignoreCandidateIds: (root['ignoreCandidateIds'] as string[]) ?? [],
    supersedeSuggestions,
    notes: (root['notes'] as string[]) ?? [],
  };
};

// ===== DAO 端口(MemoryDreamPlanDAO.kt 查询语义;实现侧负责 SQL 等价行为)=====
export interface MemoryDreamPlanDaoPort {
  // status='pending' ORDER created_at DESC LIMIT 1
  getPendingPlan: () => Promise<MemoryDreamPlanEntity | null>;
  // COUNT(*) WHERE source=:source AND created_at>=:createdAfter
  countPlansSince: (source: string, createdAfter: number) => Promise<number>;
  // UPDATE ... WHERE status='pending'(全量 pending 行)
  updatePendingStatus: (status: string, dismissedAt: number) => Promise<void>;
  markApplied: (id: string, appliedAt: number) => Promise<void>;
  markDismissed: (id: string, dismissedAt: number) => Promise<void>;
  // OnConflictStrategy.REPLACE
  insert: (entity: MemoryDreamPlanEntity) => Promise<void>;
  // 可选原子能力:同事务完成「dismiss 全量 pending + insert」—
  // 两次独立 DAO 调用并发交错会留下多个 pending;JSON 存储实现提供
  replacePendingAndInsert?: (
    status: string, dismissedAt: number, entity: MemoryDreamPlanEntity,
  ) => Promise<void>;
}

const toPersisted = (entity: MemoryDreamPlanEntity): PersistedMemoryDreamPlan => ({
  id: entity.id,
  plan: decodeDreamPlanJson(entity.planJson),
  status: memoryDreamPlanStatusFromWireName(entity.status),
  source: memoryDreamPlanSourceFromWireName(entity.source),
  createdAt: entity.createdAt,
  appliedAt: entity.appliedAt,
  dismissedAt: entity.dismissedAt,
});

const toEntity = (
  plan: MemoryDreamPlan, status: MemoryDreamPlanStatus, source: MemoryDreamPlanSource,
  now: number, appliedAt: number | null, dismissedAt: number | null,
): MemoryDreamPlanEntity => ({
  id: newId(), // Uuid.random().toString()
  planJson: encodeDreamPlanJson(plan),
  status,
  source,
  mergeCount: plan.mergeSuggestions.length,
  promoteCount: plan.promoteMemoryIds.length,
  archiveCount: plan.archiveMemoryIds.length,
  supersedeCount: plan.supersedeSuggestions.length,
  ignoreCandidateCount: plan.ignoreCandidateIds.length,
  createdAt: now,
  appliedAt,
  dismissedAt,
});

// ===== Store(MemoryDreamPlanStore.kt:36-143)=====
export interface MemoryDreamPlanStore {
  getPendingPlan: () => Promise<PersistedMemoryDreamPlan | null>;
  countAutoPlansSince: (createdAfter: number) => Promise<number>;
  savePending: (
    plan: MemoryDreamPlan, source: MemoryDreamPlanSource, now?: number,
  ) => Promise<PersistedMemoryDreamPlan>;
  recordAutoRun: (plan: MemoryDreamPlan, now?: number) => Promise<void>;
  saveApplied: (
    plan: MemoryDreamPlan, source: MemoryDreamPlanSource, now?: number,
  ) => Promise<PersistedMemoryDreamPlan>;
  markApplied: (id: string, now?: number) => Promise<void>;
  markDismissed: (id: string, now?: number) => Promise<void>;
}

export const createMemoryDreamPlanStore = (
  dao: MemoryDreamPlanDaoPort,
): MemoryDreamPlanStore => ({
  getPendingPlan: async (): Promise<PersistedMemoryDreamPlan | null> => {
    const entity: MemoryDreamPlanEntity | null = await dao.getPendingPlan();
    return entity === null ? null : toPersisted(entity);
  },
  // countAutoPlansSince(:46-47)— source=AUTO.wireName
  countAutoPlansSince: (createdAfter: number): Promise<number> =>
    dao.countPlansSince('auto', createdAfter),
  // savePending(:49-71)— 先把存量 pending 全部 dismissed,再插新 pending
  savePending: async (
    plan: MemoryDreamPlan, source: MemoryDreamPlanSource,
    now: number = Date.now(),
  ): Promise<PersistedMemoryDreamPlan> => {
    const entity: MemoryDreamPlanEntity = toEntity(plan, 'pending', source, now, null, null);
    if (dao.replacePendingAndInsert !== undefined) {
      await dao.replacePendingAndInsert('dismissed', now, entity);
    } else {
      await dao.updatePendingStatus('dismissed', now);
      await dao.insert(entity);
    }
    return toPersisted(entity);
  },
  // recordAutoRun(:73-93)— 直接插一条 dismissed/auto 记录(不动存量 pending)
  recordAutoRun: async (plan: MemoryDreamPlan, now: number = Date.now()): Promise<void> => {
    await dao.insert(toEntity(plan, 'dismissed', 'auto', now, null, now));
  },
  // saveApplied(:95-117)— 先 dismissed 存量 pending,再插 applied
  saveApplied: async (
    plan: MemoryDreamPlan, source: MemoryDreamPlanSource,
    now: number = Date.now(),
  ): Promise<PersistedMemoryDreamPlan> => {
    const entity: MemoryDreamPlanEntity = toEntity(plan, 'applied', source, now, now, null);
    if (dao.replacePendingAndInsert !== undefined) {
      await dao.replacePendingAndInsert('dismissed', now, entity);
      return toPersisted(entity);
    }
    await dao.updatePendingStatus('dismissed', now);
    await dao.insert(entity);
    return toPersisted(entity);
  },
  markApplied: (id: string, now: number = Date.now()): Promise<void> =>
    dao.markApplied(id, now),
  markDismissed: (id: string, now: number = Date.now()): Promise<void> =>
    dao.markDismissed(id, now),
});

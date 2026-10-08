// memory_dream_ops — Memory 页 dream/候选操作编排(D-088)
// Android 锚点:
//   SettingAgentMemoryVM.kt:
//     :30 LOW_CONFIDENCE_CANDIDATE_THRESHOLD = 0.60f
//     :95-99 acceptCandidate → MemoryRepository.acceptCandidate
//     :101-106 ignoreCandidate(getAllCandidates find ?: return → status IGNORED)
//     :108-137 ignoreLowConfidenceCandidates(filter pending && confidence<阈值 →
//       逐条 IGNORED → 非空打 CANDIDATE_IGNORED 事件 → 操作消息两态)
//     :143-168 planDream(planner.plan() → replacedPending = hasChanges && 存量
//       pending 非空 → hasChanges → savePending(MANUAL) → 三态消息;失败消息)
//     :171-198 applyDreamPlan(dreamPlan.value ?: return → applier.apply →
//       hasChanges → markApplied 否则 markDismissed → 两态消息;失败消息)
//     :200-204 dismissDreamPlan(dreamPlan.value ?: return → markDismissed)
//   MemoryRepository.kt:178-191 acceptCandidate 链(candidate → addMemory 全字段
//     透传 → updateCandidate status=ACCEPTED;未找到 error 逐字)
//   SettingAgentMemoryPage.kt:1036-1043 DreamReviewSection 摘要行(页内自建,
//     与 domain memoryDreamPlanSummaryText 不同 — 组/条后缀,此处逐字页版)
// 纯逻辑编排;存储/模型/计划 store 全部以 deps 注入(entry MemoryStore.ets +
//   AppContainer 任务模型链落地)。错误向调用方传播(VM runCatching → 失败消息),
//   不在此吞错。

import { makeMemoryEvent } from './memory_models.ts';
import type { MemoryCandidate, MemoryEvent, MemoryRecord } from './memory_models.ts';
import type { MemoryAddParams } from './memory_write.ts';
import {
  memoryDreamPlanHasChanges, runMemoryDreamApply, runMemoryDreamPlan,
} from './memory_dream.ts';
import type {
  MemoryDreamApplierDeps, MemoryDreamPlan, MemoryDreamPlannerDeps,
} from './memory_dream.ts';
import type { MemoryDreamPlanStore } from './memory_dream_plan_store.ts';

// ===== 常量(VM :30 + 操作消息逐字)=====

// :30 internal const val LOW_CONFIDENCE_CANDIDATE_THRESHOLD = 0.60f
export const LOW_CONFIDENCE_CANDIDATE_THRESHOLD: number = 0.60;

// :156-167 planDream 三态成功消息(逐字,含全角逗号)
export const MEMORY_DREAM_PLAN_SAVED_MESSAGE: string = '已生成 Dream 整理建议';
export const MEMORY_DREAM_PLAN_REPLACED_MESSAGE: string =
  '已生成 Dream 整理建议，上一份待审核建议已作废';
export const MEMORY_DREAM_PLAN_EMPTY_MESSAGE: string = '没有发现需要整理的记忆';
// :166 onFailure — "Dream 整理失败：${error.message ?: 简单类名}"(鸿蒙统一 Error.message)
export const memoryDreamPlanFailureMessage = (message: string): string =>
  `Dream 整理失败：${message}`;

// :186-194 applyDreamPlan 两态成功消息(逐字)
export const MEMORY_DREAM_APPLIED_MESSAGE: string = '已应用 Dream 整理建议';
export const MEMORY_DREAM_APPLY_EMPTY_MESSAGE: string = '没有可安全应用的 Dream 建议';
// :193 onFailure
export const memoryDreamApplyFailureMessage = (message: string): string =>
  `应用 Dream 建议失败：${message}`;

// :132-135 ignoreLowConfidence 两态消息(逐字)
export const MEMORY_NO_LOW_CONFIDENCE_MESSAGE: string = '没有低置信候选需要忽略';
export const memoryLowConfidenceIgnoredMessage = (count: number): string =>
  `已忽略 ${count} 条低置信候选`;

// ===== 审查卡摘要行(Page :1036-1040 页内版,逐字)=====

// 注意:与 memoryDreamPlanSummaryText(:180-183 domain 版,'合并 N · ...'无后缀)
//   不同 — 页版带 组/条 量词,忠实页实现
export const memoryDreamReviewSummaryText = (plan: MemoryDreamPlan): string =>
  `合并 ${plan.mergeSuggestions.length} 组 · 提升 ${plan.promoteMemoryIds.length} 条 · ` +
  `归档 ${plan.archiveMemoryIds.length} 条 · 替换 ${plan.supersedeSuggestions.length} 条 · ` +
  `忽略候选 ${plan.ignoreCandidateIds.length} 条`;

// ===== planDream(VM :143-168)=====

export interface MemoryDreamPlanOpDeps {
  planner: MemoryDreamPlannerDeps;
  planStore: MemoryDreamPlanStore;
}

export type MemoryDreamPlanOpResult =
  | { kind: 'saved'; replacedPending: boolean }
  | { kind: 'no_changes' };

// :146-152 — plan → replacedPending(hasChanges && getPendingPlan() != null)→
//   hasChanges → savePending(MANUAL);错误传播(VM onFailure → 失败消息)
export const planMemoryDreamOp = async (
  deps: MemoryDreamPlanOpDeps,
): Promise<MemoryDreamPlanOpResult> => {
  const plan: MemoryDreamPlan = await runMemoryDreamPlan(deps.planner);
  const hasChanges: boolean = memoryDreamPlanHasChanges(plan);
  const replacedPending: boolean =
    hasChanges && (await deps.planStore.getPendingPlan()) !== null;
  if (hasChanges) {
    await deps.planStore.savePending(plan, 'manual');
    return { kind: 'saved', replacedPending };
  }
  return { kind: 'no_changes' };
};

// ===== applyDreamPlan(VM :171-198)=====

export interface MemoryDreamApplyOpDeps {
  applier: MemoryDreamApplierDeps;
  planStore: MemoryDreamPlanStore;
}

export type MemoryDreamApplyOpResult =
  | { kind: 'no_plan' }
  | { kind: 'applied' }
  | { kind: 'dismissed_empty' };

// :172 dreamPlan.value ?: return → no_plan;:178-185 apply → hasChanges →
//   markApplied 否则 markDismissed
// 单飞互斥:apply 涉及「读 pending→长时间应用→mark」多步,并发调用会双应用
// (重复 supersede/归档/事件);单进程内串行即可
let dreamApplyTail: Promise<void> = Promise.resolve();

export const applyMemoryDreamPlanOp = (
  deps: MemoryDreamApplyOpDeps,
): Promise<MemoryDreamApplyOpResult> => {
  const run: Promise<MemoryDreamApplyOpResult> = dreamApplyTail
    .catch((): void => {})
    .then(async (): Promise<MemoryDreamApplyOpResult> => {
      const persisted = await deps.planStore.getPendingPlan();
  if (persisted === null) return { kind: 'no_plan' };
  const applied: MemoryDreamPlan = await runMemoryDreamApply(persisted.plan, deps.applier);
      if (memoryDreamPlanHasChanges(applied)) {
        await deps.planStore.markApplied(persisted.id);
        return { kind: 'applied' };
      }
      await deps.planStore.markDismissed(persisted.id);
      return { kind: 'dismissed_empty' };
    });
  dreamApplyTail = run.then((): void => {}, (): void => {});
  return run;
};

// ===== dismissDreamPlan(VM :200-204)=====

// dreamPlan.value ?: return → false;否则 markDismissed → true
export const dismissMemoryDreamPlanOp = (
  planStore: MemoryDreamPlanStore,
): Promise<boolean> => {
  // 与 apply 同链串行:apply 长时间执行期间的 dismiss 若即时落库,
  // 随后的 markApplied 会把用户已忽略的计划改回 applied
  const run: Promise<boolean> = dreamApplyTail
    .catch((): void => {})
    .then(async (): Promise<boolean> => {
      const persisted = await planStore.getPendingPlan();
      if (persisted === null) return false;
      await planStore.markDismissed(persisted.id);
      return true;
    });
  dreamApplyTail = run.then((): void => {}, (): void => {});
  return run;
};

// ===== 候选操作面 =====

export interface MemoryCandidateOpsDeps {
  getAllCandidates: () => Promise<MemoryCandidate[]>;
  addMemory: (params: MemoryAddParams) => Promise<MemoryRecord>;
  updateCandidate: (candidate: MemoryCandidate) => Promise<void>;
  addEvent: (event: MemoryEvent) => Promise<void>;
}

// acceptCandidate(MemoryRepository.kt:178-191)— candidate → addMemory(scope/kind/
//   content/sourceConversationId/sourceMessageIds/expiresAt/confidence 透传)→
//   updateCandidate(ACCEPTED);未找到 error("Memory candidate #$id not found")逐字。
//   Android 走 candidateDAO.getCandidateById,此处 getAllCandidates find — 查询语义
//   等价(同一张表按 id 唯一)。
export const acceptMemoryCandidate = async (
  deps: MemoryCandidateOpsDeps, id: string,
): Promise<MemoryRecord> => {
  const candidate: MemoryCandidate | undefined =
    (await deps.getAllCandidates()).find(
      (c: MemoryCandidate): boolean => c.id === id);
  if (candidate === undefined) {
    throw new Error(`Memory candidate #${id} not found`);
  }
  const record: MemoryRecord = await deps.addMemory({
    scope: candidate.scope,
    kind: candidate.kind,
    content: candidate.content,
    sourceConversationId: candidate.sourceConversationId,
    sourceMessageIds: candidate.sourceMessageIds,
    expiresAt: candidate.expiresAt,
    confidence: candidate.confidence,
    evidence: candidate.evidence ?? null,
  });
  await deps.updateCandidate({ ...candidate, status: 'accepted' });
  return record;
};

// ignoreCandidate(VM :101-106)— find ?: return(false)→ status IGNORED(true)
export const ignoreMemoryCandidate = async (
  deps: MemoryCandidateOpsDeps, id: string,
): Promise<boolean> => {
  const candidate: MemoryCandidate | undefined =
    (await deps.getAllCandidates()).find(
      (c: MemoryCandidate): boolean => c.id === id);
  if (candidate === undefined) return false;
  await deps.updateCandidate({ ...candidate, status: 'ignored' });
  return true;
};

export type MemoryIgnoreLowConfidenceResult =
  | { kind: 'ignored'; count: number }
  | { kind: 'none' };

// ignoreLowConfidenceCandidates(VM :108-137)— filter(pending && confidence<阈值)
//   → 逐条 IGNORED → 非空打 CANDIDATE_IGNORED 事件(文案 Kotlin Float 插值
//   0.60f → "0.6",逐字)
export const ignoreLowConfidenceMemoryCandidates = async (
  deps: MemoryCandidateOpsDeps,
): Promise<MemoryIgnoreLowConfidenceResult> => {
  const all: MemoryCandidate[] = await deps.getAllCandidates();
  const targets: MemoryCandidate[] = all.filter(
    (c: MemoryCandidate): boolean =>
      c.status === 'pending' && c.confidence < LOW_CONFIDENCE_CANDIDATE_THRESHOLD);
  for (const c of targets) {
    await deps.updateCandidate({ ...c, status: 'ignored' });
  }
  if (targets.length === 0) return { kind: 'none' };
  await deps.addEvent(makeMemoryEvent({
    type: 'candidate_ignored',
    message: `Batch ignored ${targets.length} pending candidates with confidence < 0.6`,
  }));
  return { kind: 'ignored', count: targets.length };
};

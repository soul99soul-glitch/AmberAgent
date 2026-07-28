// memory_dream_run — dream 运行编排(D-085g)
//
// Android 基准:
//   - MemoryDreamRunCoordinator.kt(全文 59 行:run :13-51 + MemoryDreamRunOutcome :54-59)
//   - MemoryDreamScheduler.kt(sync 门 :24-31 / computeDelayUntilNextNightWindowMs :69-90 /
//     WORK_NAME/MANUAL_RUN_NAME/NIGHT 窗口 :117-122)
//   - MemoryDreamWorker.kt(全文 60 行:doWork try/catch/finally + 重排门 :49-57)
//   - MemoryDreamNotifier.kt(MemoryDreamReviewNotifier 接口 :16-21 + 文案/ID 常量)
// 偏差登记:
//   - todayStart:Android 用 ZoneId.systemDefault();JS 本地时区等价(new Date setHours 0);
//   - WorkManager 约束(CONNECTED+batteryNotLow+idle)→ 鸿蒙平台调度切片实现,
//     纯延迟计算与门决策在此;runOnlyOnCharging 设置项 Android 调度器同样忽略
//     (SettingAgentMemoryPage.kt:514 注释登记);
//   - WorkManager unique-work REPLACE/自重排 → 由 entry 平台调度适配层承担(P1,设备 G-C 验证)。

import { isAnyDreamEnabled, memoryDreamPlanHasChanges } from './memory_dream.ts';
import type { MemoryDreamPlan, MemoryDreamWorkerGate } from './memory_dream.ts';
import type { MemoryDreamPlanStore } from './memory_dream_plan_store.ts';
import { makeMemoryEvent } from './memory_models.ts';
import type { MemoryEvent } from './memory_models.ts';

// ===== 门快照(MemoryWorkerSetting :137,:144-145,:149,:152)=====
export interface MemoryDreamRunGate extends MemoryDreamWorkerGate {
  dreamMaxDailyRuns: number; // 默认 1(:152)
}

// ===== MemoryDreamRunOutcome(:54-59)=====
export type MemoryDreamRunOutcome = 'disabled' | 'auto_daily_limit' | 'empty' | 'pending_review';

// ===== MemoryDreamReviewNotifier 接口(MemoryDreamNotifier.kt:16-21)=====
export interface MemoryDreamReviewNotifier {
  notifyRunning: () => void;
  notifyPendingReview: (plan: MemoryDreamPlan) => void;
  notifyFailed: (message: string) => void;
  cancel: () => void;
}

// 通知文案与 ID(MemoryDreamNotifier.kt:28-105)
export const DREAM_NOTIFY_RUNNING_TITLE: string = 'AmberAgent 正在生成整理建议';
export const DREAM_NOTIFY_RUNNING_TEXT: string = '正在检查短期和长期记忆';
export const DREAM_NOTIFY_PENDING_TITLE: string = '已生成记忆整理建议';
export const DREAM_NOTIFY_FAILED_TITLE: string = 'Daydream 整理失败';
// notifyFailed:contentText=message.take(120),bigText=message.take(500)(:64-65)
export const DREAM_NOTIFY_FAILED_TEXT_TAKE: number = 120;
export const DREAM_NOTIFY_FAILED_BIGTEXT_TAKE: number = 500;
export const DREAM_NOTIFICATION_ID: number = 8201;
export const DREAM_EXTRA_OPEN_AGENT_MEMORY: string = 'openAgentMemory';

// ===== RunCoordinator(:13-51)=====
export interface MemoryDreamRunCoordinatorDeps {
  worker: MemoryDreamRunGate;
  planStore: MemoryDreamPlanStore;
  notifier: MemoryDreamReviewNotifier;
  // MemoryDreamPlanProvider SAM(Planner.kt:28-30)
  planner: { plan: () => Promise<MemoryDreamPlan> };
  now?: () => number;
}

// 本地时区今日 0 点(ZoneId.systemDefault() 的 JS 等价)
export const localDayStart = (now: number): number => {
  const d: Date = new Date(now);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
};

export const runMemoryDreamRun = async (
  deps: MemoryDreamRunCoordinatorDeps, isManualRun: boolean,
): Promise<MemoryDreamRunOutcome> => {
  const nowFn: () => number = deps.now ?? ((): number => Date.now());
  const now: number = nowFn();
  // :19-22
  if (!deps.worker.enabled || !isAnyDreamEnabled(deps.worker)) {
    deps.notifier.cancel();
    return 'disabled';
  }
  // :23-35
  if (!isManualRun) {
    const todayStart: number = localDayStart(now);
    const autoRunsToday: number = await deps.planStore.countAutoPlansSince(todayStart);
    if (autoRunsToday >= Math.max(1, deps.worker.dreamMaxDailyRuns)) {
      deps.notifier.cancel();
      return 'auto_daily_limit';
    }
  }
  // :37-45
  deps.notifier.notifyRunning();
  const plan: MemoryDreamPlan = await deps.planner.plan();
  if (!memoryDreamPlanHasChanges(plan)) {
    if (!isManualRun) {
      await deps.planStore.recordAutoRun(plan, now);
    }
    deps.notifier.cancel();
    return 'empty';
  }
  // :47-50
  await deps.planStore.savePending(plan, isManualRun ? 'manual' : 'auto', now);
  deps.notifier.notifyPendingReview(plan);
  return 'pending_review';
};

// ===== Scheduler 纯逻辑 =====
export const MEMORY_DREAM_WORK_NAME: string = 'memory_dream_review';
export const MEMORY_DREAM_MANUAL_RUN_NAME: string = 'memory_dream_review_manual';

// sync 门(:24-31):关 → cancelUniqueWork;开 → scheduleNextNightRun
export const dreamSyncShouldSchedule = (worker: MemoryDreamWorkerGate): boolean =>
  worker.enabled && isAnyDreamEnabled(worker);

const NIGHT_START_HOUR: number = 0; // LocalTime.of(0, 0)
const NIGHT_END_HOUR: number = 6;   // LocalTime.of(6, 0)

// computeDelayUntilNextNightWindowMs(:69-90)— 本地夜窗 00:00–06:00
export const computeDelayUntilNextNightWindowMs = (
  now: Date, skipCurrentWindow: boolean = false,
): number => {
  const nightStart: Date = new Date(now.getTime());
  nightStart.setHours(NIGHT_START_HOUR, 0, 0, 0);
  const nightEnd: Date = new Date(now.getTime());
  nightEnd.setHours(NIGHT_END_HOUR, 0, 0, 0);
  // 已在今日窗口内 → 尽快运行;skipCurrentWindow → 明日 00:00(:80-84)
  if (now.getTime() >= nightStart.getTime() && now.getTime() < nightEnd.getTime()) {
    if (!skipCurrentWindow) return 0;
    const tomorrowStart: Date = new Date(nightStart.getTime());
    tomorrowStart.setDate(tomorrowStart.getDate() + 1);
    return Math.max(0, tomorrowStart.getTime() - now.getTime());
  }
  // nightStart 在未来今天(NIGHT_START=00:00 实际不可能,保留对应分支);
  // 否则(≥06:00)→ 明日 00:00(:88-89)
  let target: Date;
  if (now.getTime() < nightStart.getTime()) {
    target = nightStart;
  } else {
    target = new Date(nightStart.getTime());
    target.setDate(target.getDate() + 1);
  }
  return Math.max(0, target.getTime() - now.getTime());
};

// ===== Worker(MemoryDreamWorker.kt:21-58)=====
export interface MemoryDreamWorkerRunDeps {
  workerGate: MemoryDreamWorkerGate;
  // tags.contains(MANUAL_RUN_NAME)(:27)
  isManualRun: boolean;
  isStopped: () => boolean;
  runCoordinator: () => Promise<MemoryDreamRunOutcome>;
  addEvent: (event: MemoryEvent) => Promise<void>;
  notifyFailed: (message: string) => void;
  // scheduleNextNightRun(skipCurrentWindow=true);NonCancellable+runCatching 吞错(:52-56)
  reschedule: () => void;
}

// doWork:成功 'success';失败 → DREAM_FAILED 事件 take(500)+notifyFailed → 'failure';
//   finally:非手动且未停且门开 → 重排
export const runMemoryDreamWorkerOnce = async (
  deps: MemoryDreamWorkerRunDeps,
): Promise<'success' | 'failure'> => {
  try {
    try {
      await deps.runCoordinator();
      return 'success';
    } catch (error: unknown) {
      // error.message ?: error::class.java.simpleName(:36)
      const message: string = error instanceof Error
        ? (error.message !== '' ? error.message : error.constructor.name)
        : 'Error';
      await deps.addEvent(makeMemoryEvent({
        type: 'dream_failed',
        message: message.slice(0, 500), // take(500)(:38)
      }));
      deps.notifyFailed(message);
      return 'failure';
    }
  } finally {
    if (!deps.isManualRun && !deps.isStopped() && deps.workerGate.enabled
      && isAnyDreamEnabled(deps.workerGate)) {
      try {
        deps.reschedule();
      } catch {
        // runCatching 吞错(:53)
      }
    }
  }
};

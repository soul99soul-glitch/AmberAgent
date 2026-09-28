// BackgroundGenerationKeepAlive — 后台生成保活与真实传输进度(E13-B 冻结合同)
//
// API12 路线:startBackgroundRunning(context, ['dataTransfer'], wantAgent) 取得
//   系统 ContinuousTaskNotification,用**实际返回的** notificationId/slotType/
//   contentType 更新 systemLiveView.typeCode=8 的绝对值进度
//   (progress.currentValue=本租期真实接收内容字节,isPercentage:false,省略未知 max)。
// Chat 与 Novel 共享同一 UIAbility 的同一个长时任务(source+runId 活动集聚合)。
// desired 标志 + 单条串行操作尾:start/stop/publish 不会交错;stop 成功才清
//   持有事实,失败保留真实错误;发布合并到至多每秒一次且只在量变化时发送。
// 不承诺:无限保活/定时续约/假百分比/心跳进度。官方 DATA_TRANSFER 规则是
//   进度长时间未更新会被取消 — 无传输进展的长静默不保证续持,是否接受
//   绝对值进度属设备验收层。

import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { wantAgent } from '@kit.AbilityKit';
import { notificationManager } from '@kit.NotificationKit';
import type { Context } from '@kit.AbilityKit';
import type { WantAgent } from '@ohos.wantAgent';
import type { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.BgKeepAlive';
const LIVE_VIEW_TYPE_CODE: number = 8;
const PUBLISH_MIN_INTERVAL_MS: number = 1000;

export interface GenerationProgressSnapshot {
  source: 'chat' | 'novel';
  runId: string;
  generationActive: boolean;
  receivedChars: number;
  receivedBytes: number;
}

interface ActivityEntry { receivedChars: number; receivedBytes: number; }

const activities: Map<string, ActivityEntry> = new Map();
let desiredBackground: boolean = false;
let taskHeld: boolean = false;
let starting: boolean = false;
let notificationId: number = -1;
let notificationSlotType: notificationManager.SlotType = notificationManager.SlotType.LIVE_VIEW;
let notificationContentType: notificationManager.ContentType = notificationManager.ContentType.NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW;
let operationTail: Promise<void> = Promise.resolve();
let contextRef: Context | null = null;
let lastError: string = '';
let dirty: boolean = false;
let publishTimer: number = -1;
let lastPublishAt: number = 0;
let lastPublishedBytes: number = -1;

export const generationKeepAliveLastError = (): string => lastError;
export const generationKeepAliveHeld = (): boolean => taskHeld;

const aggregateBytes = (): number => {
  let total: number = 0;
  activities.forEach((entry: ActivityEntry): void => { total += entry.receivedBytes; });
  return total;
};

const formatBytes = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

const flushPublish = (): void => {
  if (!taskHeld || notificationId < 0) { dirty = false; return; }
  if (!dirty) return;
  const bytes: number = aggregateBytes();
  if (bytes === lastPublishedBytes) { dirty = false; return; } // 静默不造增长
  lastPublishedBytes = bytes;
  lastPublishAt = Date.now();
  dirty = false;
  const count: number = activities.size;
  const request: notificationManager.NotificationRequest = {
    id: notificationId,
    notificationSlotType: notificationSlotType,
    content: {
      notificationContentType: notificationContentType,
      systemLiveView: {
        typeCode: LIVE_VIEW_TYPE_CODE,
        title: count > 1 ? `正在生成 · ${count} 个任务` : '正在生成',
        text: `已接收 ${formatBytes(bytes)}`,
        progress: { currentValue: bytes, isPercentage: false },
      },
    },
  };
  notificationManager.publish(request).catch((error: BusinessError): void => {
    lastError = `publish failed: ${String(error)}`;
    hilog.warn(DOMAIN, TAG, 'publish progress failed: %{public}s', String(error));
  }).then((): void => {});
};

const schedulePublish = (immediate: boolean = false): void => {
  if (!taskHeld || notificationId < 0 || publishTimer >= 0) return;
  const wait: number = immediate ? 0 : Math.max(0, PUBLISH_MIN_INTERVAL_MS - (Date.now() - lastPublishAt));
  publishTimer = setTimeout((): void => {
    publishTimer = -1;
    flushPublish();
  }, wait);
};

type KeepAliveOperation = 'start' | 'stop';

const executeOperation = async (operation: KeepAliveOperation): Promise<void> => {
  if (operation === 'start') {
    if (taskHeld || starting) return;
    // 落地前复核:不再需要后台就不申请
    if (!desiredBackground || activities.size === 0) return;
    const context: Context | null = contextRef;
    if (context === null) return;
    starting = true;
    try {
      const want: WantAgent = await wantAgent.getWantAgent({
        wants: [{ bundleName: 'app.amber.deepread', abilityName: 'EntryAbility' }],
        actionType: wantAgent.OperationType.START_ABILITY,
        requestCode: 0,
      });
      const notification: backgroundTaskManager.ContinuousTaskNotification =
        await backgroundTaskManager.startBackgroundRunning(context, ['dataTransfer'], want);
      taskHeld = true;
      notificationId = notification.notificationId;
      notificationSlotType = notification.slotType;
      notificationContentType = notification.contentType;
      lastError = '';
      lastPublishedBytes = -1;
      hilog.info(DOMAIN, TAG, 'generation keep-alive started, notification=%{public}d', notificationId);
      // 落地后复核:等待期间已不再需要 → 立即停掉刚申请的任务
      if (!desiredBackground || activities.size === 0) {
        enqueueOperation('stop');
        return;
      }
      dirty = true;
      schedulePublish(true);
    } catch (error) {
      taskHeld = false;
      notificationId = -1;
      lastError = `start failed: ${String(error)}`;
      hilog.warn(DOMAIN, TAG, 'startBackgroundRunning failed: %{public}s', String(error));
    } finally {
      starting = false;
    }
    return;
  }
  if (!taskHeld) return;
  const context: Context | null = contextRef;
  if (context === null) return;
  try {
    await backgroundTaskManager.stopBackgroundRunning(context);
    taskHeld = false;
    notificationId = -1;
    dirty = false;
    lastError = '';
    hilog.info(DOMAIN, TAG, 'generation keep-alive stopped');
  } catch (error) {
    // 停止失败:保留仍持有的事实与真实错误,不谎称已停
    lastError = `stop failed: ${String(error)}`;
    hilog.warn(DOMAIN, TAG, 'stopBackgroundRunning failed: %{public}s', String(error));
  }
};

const enqueueOperation = (operation: KeepAliveOperation): Promise<void> => {
  const run: Promise<void> = operationTail.then((): Promise<void> => executeOperation(operation));
  operationTail = run.then((): void => {}, (): void => {});
  return run;
};

const evaluateTask = (): void => {
  if (activities.size > 0 && desiredBackground && !taskHeld) enqueueOperation('start').catch((): void => {});
  else if (activities.size === 0 && taskHeld) enqueueOperation('stop').catch((): void => {});
};

// 单 run 进度上报:generationActive=false 移除本 run 的活动项;A 结束不影响 B
export const reportGenerationProgress = (context: Context, snapshot: GenerationProgressSnapshot): void => {
  contextRef = context;
  const key: string = `${snapshot.source}:${snapshot.runId}`;
  if (snapshot.generationActive) {
    activities.set(key, { receivedChars: snapshot.receivedChars, receivedBytes: snapshot.receivedBytes });
  } else {
    activities.delete(key);
  }
  if (snapshot.receivedBytes > 0) {
    dirty = true;
    schedulePublish();
  }
  evaluateTask();
};

// 前/后台切换:desired 先行;串行操作完成才返回(调用方可 await)
export const setGenerationKeepAliveBackground = async (
  context: Context, backgrounded: boolean,
): Promise<void> => {
  contextRef = context;
  desiredBackground = backgrounded;
  if (!backgrounded) {
    if (taskHeld) await enqueueOperation('stop').catch((): void => {});
    return;
  }
  if (activities.size > 0) await enqueueOperation('start').catch((): void => {});
};

// Ability 销毁:使待发布的进度失效并做最终清理
export const disposeGenerationKeepAlive = async (context: Context): Promise<void> => {
  contextRef = context;
  desiredBackground = false;
  activities.clear();
  dirty = false;
  if (publishTimer >= 0) {
    clearTimeout(publishTimer);
    publishTimer = -1;
  }
  if (taskHeld) await enqueueOperation('stop').catch((): void => {});
};

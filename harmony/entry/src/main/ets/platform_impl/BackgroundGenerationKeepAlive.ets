import { getAppBundleName } from './ProductIdentity.ets';
// BackgroundGenerationKeepAlive — 后台生成保活与真实传输进度(E13-B 冻结合同)
//
// API12 路线:startBackgroundRunning(context, ['dataTransfer'], wantAgent) 取得
//   系统 ContinuousTaskNotification,在 LIVE_VIEW 渠道上按官方示例使用公开系统实况
//   类型,用实际 notificationId 更新 systemLiveView.typeCode=8 的绝对值进度
//   (progress.currentValue=本租期真实接收内容字节,isPercentage:false,省略未知 max)。
// Chat、Novel 与 DeepRead 共享同一 UIAbility 的同一个长时任务(source+runId 活动集聚合)。
// desired 标志 + 单条串行操作尾:start/stop/publish 不会交错;stop 成功才清
//   持有事实,失败保留真实错误;发布合并到至多每秒一次,仅内容或成员变化时发送。
// 锁屏/通知中心的 DATA_TRANSFER 模板已在真机验证;状态栏胶囊尚未接通。
// OpenHarmony owner 更新路径仅采用 template,普通应用传入的 capsule 不保证生效。
// 独立 Live View Kit 还需场景准入与 AGC 权益,不能把本接口当作替代。
// 不承诺:无限保活/定时续约/假百分比/心跳进度。官方 DATA_TRANSFER 规则是
//   进度长时间未更新会被取消 — 无传输进展的长静默不保证续持,是否接受
//   绝对值进度属设备验收层。

import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { wantAgent } from '@kit.AbilityKit';
import { notificationManager } from '@kit.NotificationKit';
import type { Context, Want } from '@kit.AbilityKit';
import type { WantAgent } from '@ohos.wantAgent';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.BgKeepAlive';
const LIVE_VIEW_TYPE_CODE: number = 8;
const PUBLISH_MIN_INTERVAL_MS: number = 1000;

export interface GenerationProgressSnapshot {
  source: 'chat' | 'novel' | 'deepread';
  runId: string;
  generationActive: boolean;
  receivedChars: number;
  receivedBytes: number;
  title?: string;
  conversationId?: string;
  topicId?: string;
  novelWorkspaceProjectId?: string;
}

const activities: Map<string, GenerationProgressSnapshot> = new Map();
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
let lastPublishedSignature: string = '';

export const generationKeepAliveLastError = (): string => lastError;
export const generationKeepAliveHeld = (): boolean => taskHeld;

const aggregateBytes = (): number => {
  let total: number = 0;
  activities.forEach((entry: GenerationProgressSnapshot): void => { total += entry.receivedBytes; });
  return total;
};

const formatBytes = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

const phaseLabel = (source: string): string => {
  if (source === 'novel') return '创作中';
  if (source === 'deepread') return '深读中';
  return '回复中';
};

const singleActivity = (): GenerationProgressSnapshot | undefined => {
  if (activities.size !== 1) return undefined;
  for (const entry of activities.values()) return entry;
  return undefined;
};

// 聚合时打开首页;单任务的文案与点击目标来自同一份快照。
const buildActivityWantAgent = (activity?: GenerationProgressSnapshot): Promise<WantAgent> => {
  const parameters: Record<string, string> = {};
  if (activity !== undefined) {
    if (activity.conversationId) parameters['conversationId'] = activity.conversationId;
    else if (activity.topicId) {
      parameters['topicId'] = activity.topicId;
      parameters['title'] = activity.title ?? '';
    }
    else if (activity.novelWorkspaceProjectId) parameters['novelWorkspaceProjectId'] = activity.novelWorkspaceProjectId;
  }
  const want: Want = { bundleName: getAppBundleName(), abilityName: 'EntryAbility', parameters: parameters };
  return wantAgent.getWantAgent({
    wants: [want], actionType: wantAgent.OperationType.START_ABILITY, requestCode: 990002,
    actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG],
  });
};

const flushPublish = async (): Promise<void> => {
  if (!taskHeld || notificationId < 0 || !desiredBackground || activities.size === 0) { dirty = false; return; }
  if (!dirty) return;
  const bytes: number = aggregateBytes();
  const count: number = activities.size;
  const activity: GenerationProgressSnapshot | undefined = singleActivity();
  const label: string = activity === undefined ? `${count} 个任务` : phaseLabel(activity.source);
  const title: string = activity !== undefined && activity.title ? `${activity.title.substring(0, 60)} · ${label}` : `Amber · ${label}`;
  const signature: string = `${bytes}|${count}|${title}|${activity?.title ?? ''}|${activity?.conversationId ?? ''}|${activity?.topicId ?? ''}|${activity?.novelWorkspaceProjectId ?? ''}`;
  if (signature === lastPublishedSignature) { dirty = false; return; }
  lastPublishAt = Date.now();
  dirty = false;
  const notificationSupported: boolean = canIUse('SystemCapability.Notification.Notification');
  const systemLiveViewType: notificationManager.ContentType =
    notificationManager.ContentType.NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW;
  const liveViewSlot: notificationManager.SlotType = notificationManager.SlotType.LIVE_VIEW;
  if (!notificationSupported || systemLiveViewType === undefined || liveViewSlot === undefined ||
    notificationSlotType !== liveViewSlot) {
    // typeCode=8 是 dataTransfer 业务类型,不是 notificationContentType。
    // 未知渠道不能套用 systemLiveView;保留系统已授予的长时任务事实。
    lastError = `progress unavailable: Notification=${notificationSupported}, slot=${notificationSlotType}, content=${notificationContentType}`;
    hilog.warn(DOMAIN, TAG, '%{public}s', lastError);
    return;
  }
  const request: notificationManager.NotificationRequest = {
    id: notificationId,
    notificationSlotType: notificationSlotType,
    // DATA_TRANSFER owner 更新必须携带 downloadTemplate (系统日志校验)。
    // 未知总量不传模板 progressValue。systemLiveView 的绝对接收量是请求值,
    // owner 更新可能不采用 content,不能据此承诺系统界面会展示接收量或 capsule。
    template: { name: 'downloadTemplate', data: { title: title, fileName: '模型输出' } },
    content: {
      // startBackgroundRunning 官方示例要求用公开类型更新系统分配的同一个 ID。
      // API24 返回的 contentType=8 是内部类型,不能直接用于发布。
      notificationContentType: systemLiveViewType,
      systemLiveView: {
        typeCode: LIVE_VIEW_TYPE_CODE,
        title: title,
        capsule: { title: `Amber · ${label}`, backgroundColor: '#FFB86728' },
        text: `已接收 ${formatBytes(bytes)}`,
        progress: { currentValue: bytes, isPercentage: false },
      },
    },
  };
  try {
    // 原生参数校验会在返回 Promise 前同步抛错,必须由同一个 catch 接住。
    request.wantAgent = await buildActivityWantAgent(activity);
    await notificationManager.publish(request);
    lastPublishedSignature = signature;
    lastError = '';
  } catch (error) {
    lastError = `publish failed: ${String(error)}`;
    hilog.warn(DOMAIN, TAG, 'publish progress failed: %{public}s', String(error));
  }
};

const schedulePublish = (immediate: boolean = false): void => {
  if (!taskHeld || notificationId < 0 || publishTimer >= 0) return;
  const wait: number = immediate ? 0 : Math.max(0, PUBLISH_MIN_INTERVAL_MS - (Date.now() - lastPublishAt));
  publishTimer = setTimeout((): void => {
    publishTimer = -1;
    const run: Promise<void> = operationTail.then((): Promise<void> => flushPublish());
    operationTail = run.then((): void => {}, (): void => {});
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
        wants: [{ bundleName: getAppBundleName(), abilityName: 'EntryAbility' }],
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
      lastPublishedSignature = '';
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
export const reportGenerationProgress = (_context: Context, snapshot: GenerationProgressSnapshot): void => {
  // Only UIAbility lifecycle owns the lease context; work extensions report progress too.
  const key: string = `${snapshot.source}:${snapshot.runId}`;
  if (snapshot.generationActive) {
    activities.set(key, snapshot);
  } else {
    activities.delete(key);
  }
  // 成员、名称或路由变化也更新;相同快照由 signature 去重,静默不造增长。
  dirty = true;
  schedulePublish();
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

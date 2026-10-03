import { getAppBundleName } from './ProductIdentity.ets';
// NotificationNotifier — Notifier 契约的真实实现(对照设计 §6.7 / §8.8)
// 3 类通知:running/completed/failed;同 topic 同 id 覆盖更新,cancel 撤销。
// 进度:notifyProgress 带 label+N% 文本(节流由调用方按快照变化驱动)。
// 点击回文章:wantAgent(EntryAbility + parameters topicId/title/sourceUrl)
//   → ChatListPage routeFromWant 消费 wantTopicId。
// 通知授权:EntryAbility loadContent 后一次性 requestEnableNotification(API 10+)。
// 模块说明:统一走 notificationManager —— @ohos.notification 旧模块已停止维护,
// 其 contentType/NotificationContent 即此前登记的冲突源,不再引入。

import { notificationManager } from '@kit.NotificationKit';
import { wantAgent } from '@kit.AbilityKit';
import type { Want } from '@kit.AbilityKit';

import { hilog } from '@kit.PerformanceAnalysisKit';
import { BusinessError } from '@kit.BasicServicesKit';
import type { Notifier } from '@amber/deepread-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Notifier';

// 生成完成通知固定 id(Android sendGenerationDoneNotification notificationId=1)
const GENERATION_DONE_ID: number = 990001;

// 字符串 key → 通知 id(number):djb2 取正,0 兜底为 1
export const notificationIdFor = (key: string): number => {
  let h: number = 5381;
  for (let i = 0; i < key.length; i++) {
    h = ((h << 5) + h + key.charCodeAt(i)) & 0x7fffffff;
  }
  return h === 0 ? 1 : h;
};

// 点击通知 → 拉起 EntryAbility；Want.parameters 只携带受长度校验的稳定 id，
// EntryAbility 再写入 AppStorage，由根页消费并建立正确返回栈。
const buildArticleWantAgent = (
  topicId: string, title: string, sourceUrl: string,
): Promise<object | null> => {
  const want: Want = {
    bundleName: getAppBundleName(),
    abilityName: 'EntryAbility',
    parameters: {
      topicId,
      title,
      sourceUrl,
    },
  };
  const wants: Array<Want> = [want];
  const info: wantAgent.WantAgentInfo = {
    wants: wants,
    actionType: wantAgent.OperationType.START_ABILITY,
    requestCode: 991,
    actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG],
  };
  return new Promise<object | null>((resolve): void => {
    wantAgent.getWantAgent(info).then((wa: object): void => {
      resolve(wa);
    }).catch((e: BusinessError): void => {
      hilog.warn(DOMAIN, TAG, 'wantAgent failed: %{public}s', String(e));
      resolve(null);
    });
  });
};

export const buildNovelJobWantAgent = (
  projectId: string, branchId: string, jobId: string,
): Promise<object | null> => {
  const want: Want = {
    bundleName: getAppBundleName(),
    abilityName: 'EntryAbility',
    parameters: {
      novelProjectId: projectId,
      novelBranchId: branchId,
      novelJobId: jobId,
    },
  };
  const info: wantAgent.WantAgentInfo = {
    wants: [want],
    actionType: wantAgent.OperationType.START_ABILITY,
    requestCode: notificationIdFor(`novel-polish:${jobId}`),
    actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG],
  };
  return new Promise<object | null>((resolve): void => {
    wantAgent.getWantAgent(info).then((wa: object): void => {
      resolve(wa);
    }).catch((e: BusinessError): void => {
      hilog.warn(DOMAIN, TAG, 'novel wantAgent failed: %{public}s', String(e));
      resolve(null);
    });
  });
};

export const publishNovelJobNotification = async (
  projectId: string, branchId: string, jobId: string, title: string, text: string,
): Promise<void> => {
  const wa: object | null = await buildNovelJobWantAgent(projectId, branchId, jobId);
  await publishBasicNotification(notificationIdFor(`novel-polish:${jobId}`), title, text, wa);
};

const buildBasicRequest = (
  id: number, title: string, text: string, wantAgentObj?: object | null,
): notificationManager.NotificationRequest => {
  const request: notificationManager.NotificationRequest = {
    id,
    content: {
      notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
      normal: { title: title.length > 0 ? title : 'Amber', text: text.length > 0 ? text : ' ' }
    }
  };
  if (wantAgentObj !== undefined && wantAgentObj !== null) {
    request.wantAgent = wantAgentObj;
  }
  return request;
};

// 普通(尽力而为)通知:失败仅记日志,不 reject —— 保持 fire-and-forget 调用方无 unhandled。
export const publishBasicNotification = (
  id: number, title: string, text: string, wantAgentObj?: object | null,
): Promise<void> => {
  // 返回真实 publish promise:调用方可 await 到平台落地(R22 完成发布竞态)
  return notificationManager.publish(buildBasicRequest(id, title, text, wantAgentObj))
    .catch((e: BusinessError): void => {
      hilog.warn(DOMAIN, TAG, 'publish %{public}d failed: %{public}s', id, String(e));
    });
};

// 严格发布:失败 reject(带日志)。仅供需要据落地结果决定后续动作的调用方
// (如 notifyCompleted 的 owner/撤同 id 判定);普通通知仍走上面的 best-effort。
export const publishBasicNotificationStrict = (
  id: number, title: string, text: string, wantAgentObj?: object | null,
): Promise<void> => {
  return notificationManager.publish(buildBasicRequest(id, title, text, wantAgentObj))
    .then((): void => {}, (e: BusinessError): void => {
      hilog.warn(DOMAIN, TAG, 'publish %{public}d failed: %{public}s', id, String(e));
      throw new Error(e.message);
    });
};

export const cancelNotification = (id: number): Promise<void> => {
  return notificationManager.cancel(id).catch((e: BusinessError): void => {
    hilog.warn(DOMAIN, TAG, 'cancel %{public}d failed: %{public}s', id, String(e));
  });
};

// 生成完成通知(ChatService.kt:1673-1683 对齐:title=助手/话题名,text=末条/摘要)
export const notifyGenerationDone = (senderName: string, contentText: string): void => {
  publishBasicNotification(GENERATION_DONE_ID, senderName, contentText);
};

// 生成失败通知(与完成同 id 覆盖;不含密钥,仅错误摘要)
export const notifyGenerationFailed = (senderName: string, errorText: string): void => {
  const brief: string = errorText.length > 80 ? `${errorText.substring(0, 80)}…` : errorText;
  publishBasicNotification(GENERATION_DONE_ID, `${senderName} · 生成失败`, brief);
};

// Cron 任务通知(与 DeepRead 同一 publish 入口)
export const notifyCronRunning = (taskId: string, title: string): void => {
  publishBasicNotification(notificationIdFor(`cron:${taskId}`), '定时任务运行中', title);
};

export const notifyCronCompleted = (taskId: string, title: string): void => {
  publishBasicNotification(notificationIdFor(`cron:${taskId}`), '定时任务完成', title);
};

export const notifyCronFailed = (taskId: string, title: string, errorText: string): void => {
  const brief: string = errorText.length > 60 ? `${errorText.substring(0, 60)}…` : errorText;
  publishBasicNotification(notificationIdFor(`cron:${taskId}`), `定时任务失败 · ${title}`, brief);
};

class NotificationNotifierImpl implements Notifier {
  // 每 topic 通知归属的 run token。notifyRunning 在 await 前记录持有者,
  // cancelRunning 仅在 token 匹配(或调用方未传 token)时撤销 → 旧 run 的迟到
  // cancel 不能撤掉新 run 已发布的同 id 通知。
  private ownerToken: Map<string, number> = new Map<string, number>();
  // Platform cancellation is asynchronous: a new publish must wait for an
  // already-started cancellation of this same notification ID to finish.
  private operationTails: Map<string, Promise<void>> = new Map<string, Promise<void>>();

  private enqueue(topicId: string, operation: () => Promise<void>): Promise<void> {
    const previous: Promise<void> = this.operationTails.get(topicId) ?? Promise.resolve();
    const run: Promise<void> = previous.then(operation);
    const settled: Promise<void> = run.then((): void => {}, (): void => {});
    this.operationTails.set(topicId, settled);
    settled.then((): void => {
      if (this.operationTails.get(topicId) === settled) this.operationTails.delete(topicId);
    });
    return run;
  }

  private claim(topicId: string, runToken?: number): void {
    if (runToken !== undefined) this.ownerToken.set(topicId, runToken);
  }

  private owns(topicId: string, runToken?: number): boolean {
    return runToken === undefined || this.ownerToken.get(topicId) === runToken;
  }

  async notifyRunning(topicId: string, title: string, runToken?: number): Promise<void> {
    this.claim(topicId, runToken);
    const wa: object | null = await buildArticleWantAgent(topicId, title, '');
    if (!this.owns(topicId, runToken)) return;
    // await 真实 publish:调用方 await 返回即平台已落地(R22)
    await this.enqueue(topicId, async (): Promise<void> => {
      if (!this.owns(topicId, runToken)) return;
      await publishBasicNotification(notificationIdFor(topicId), '正在深度阅读', title, wa);
    });
  }

  async notifyCompleted(topicId: string, title: string, complete: boolean, runToken?: number): Promise<void> {
    if (!this.owns(topicId, runToken)) return;
    const wa: object | null = await buildArticleWantAgent(topicId, title, '');
    if (!this.owns(topicId, runToken)) return;
    // 严格 await:发布失败必须上抛(P2-3),不能对调度层谎报「已发布」,
    // 否则 finally 不撤同一 id,「正在深度阅读」永久残留。
    await this.enqueue(topicId, async (): Promise<void> => {
      if (!this.owns(topicId, runToken)) return;
      await publishBasicNotificationStrict(notificationIdFor(topicId),
        complete ? '深度阅读完成' : '深度阅读部分完成', title, wa);
    });
  }

  async notifyFailed(topicId: string, title: string, errorMessage: string, runToken?: number): Promise<void> {
    if (!this.owns(topicId, runToken)) return;
    const wa: object | null = await buildArticleWantAgent(topicId, title, '');
    if (!this.owns(topicId, runToken)) return;
    await this.enqueue(topicId, async (): Promise<void> => {
      if (!this.owns(topicId, runToken)) return;
      await publishBasicNotificationStrict(notificationIdFor(topicId), '深度阅读失败',
        `${title} — ${errorMessage}`.slice(0, 120), wa);
    });
  }

  async cancelRunning(topicId: string, runToken?: number): Promise<void> {
    await this.enqueue(topicId, async (): Promise<void> => {
      const owner: number | undefined = this.ownerToken.get(topicId);
      // Recheck at execution, since a new owner can register while queued.
      if (runToken !== undefined && owner !== undefined && owner !== runToken) return;
      await cancelNotification(notificationIdFor(topicId));
      // The new owner's publication is queued after this cancellation; keep
      // its claim if it arrived while the platform operation was in flight.
      if (this.ownerToken.get(topicId) === owner) this.ownerToken.delete(topicId);
    });
  }
}

export const createNotificationNotifier = (): Notifier => {
  return new NotificationNotifierImpl();
};

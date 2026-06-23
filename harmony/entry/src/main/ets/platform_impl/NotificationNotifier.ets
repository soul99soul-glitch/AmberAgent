// NotificationNotifier — Notifier 契约的真实实现(对照设计 §6.7 / §8.8)
// 3 类通知:running(ongoing)/ completed / failed
// @kit.NotificationKit notificationManager
//
// 0.2:不申请 ContinuousTask,退化为普通 ongoing notification。
// TODO(toolchain/ArkTS 验证): notificationManager.publish / cancel 签名以 API 24 为准。

import { notificationManager } from '@kit.NotificationKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Notifier, NotificationKind } from '@amber/deepread-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Notifier';

const RUNNING_NOTIF_ID_BASE: number = 9000;
const COMPLETED_NOTIF_ID_BASE: number = 9100;

const notifId = (base: number, topicId: string): number => {
  // topicId hash → 小整数(避免碰撞,够 MVP)
  let h = 0;
  for (let i = 0; i < topicId.length; i++) {
    h = (h * 31 + topicId.charCodeAt(i)) & 0x3ff;
  }
  return base + h;
};

const buildRequest = (
  title: string,
  content: string,
  kind: NotificationKind,
  topicId: string,
): notificationManager.NotificationRequest => {
  const id = kind === 'running'
    ? notifId(RUNNING_NOTIF_ID_BASE, topicId)
    : notifId(COMPLETED_NOTIF_ID_BASE, topicId);
  // TODO(arkts): Want 契约 — 点通知跳回 DeepReadArticlePage(§6.7)
  return {
    id,
    content: {
      contentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
      normal: {
        title,
        text: content,
      },
    },
    notificationSlotType: kind === 'running'
      ? notificationManager.NotificationSlotType.SOCIAL_COMMUNICATION
      : notificationManager.NotificationSlotType.CONTENT_INFORMATION,
  } as notificationManager.NotificationRequest;
};

export const createNotificationNotifier = (): Notifier => {
  const notifyRunning = async (topicId: string, title: string): Promise<void> => {
    try {
      await notificationManager.publish(buildRequest('深度阅读中', title, 'running', topicId));
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'notifyRunning failed: %{public}s', String(e));
    }
  };

  const notifyCompleted = async (topicId: string, title: string, complete: boolean): Promise<void> => {
    try {
      const content = complete ? '深度阅读已完成' : '深度阅读部分完成,点击查看';
      await notificationManager.publish(buildRequest(title, content, 'completed', topicId));
      // 完成后取消 ongoing
      await notificationManager.cancel(notifId(RUNNING_NOTIF_ID_BASE, topicId));
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'notifyCompleted failed: %{public}s', String(e));
    }
  };

  const cancelRunning = async (topicId: string): Promise<void> => {
    try {
      await notificationManager.cancel(notifId(RUNNING_NOTIF_ID_BASE, topicId));
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'cancelRunning failed: %{public}s', String(e));
    }
  };

  const notifyFailed = async (topicId: string, title: string, errorMessage: string): Promise<void> => {
    try {
      await notificationManager.publish(buildRequest(`${title} 失败`, errorMessage, 'failed', topicId));
      await notificationManager.cancel(notifId(RUNNING_NOTIF_ID_BASE, topicId));
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'notifyFailed failed: %{public}s', String(e));
    }
  };

  return { notifyRunning, notifyCompleted, notifyFailed, cancelRunning };
};

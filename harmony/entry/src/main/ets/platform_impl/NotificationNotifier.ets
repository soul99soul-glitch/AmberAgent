// NotificationNotifier — Notifier 契约的真实实现(对照设计 §6.7 / §8.8)
// 3 类通知:running/completed/failed;同 topic 同 id 覆盖更新,cancel 撤销。
// 通知授权:EntryAbility onCreate 一次性 requestEnableNotification(API 10+)。
// 模块说明:统一走 notificationManager —— @ohos.notification 旧模块已停止维护,
// 其 contentType/NotificationContent 即此前登记的冲突源,不再引入。

import { notificationManager } from '@kit.NotificationKit';
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

export const publishBasicNotification = (id: number, title: string, text: string): Promise<void> => {
  const request: notificationManager.NotificationRequest = {
    id,
    content: {
      notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
      normal: { title: title.length > 0 ? title : 'Amber', text: text.length > 0 ? text : ' ' }
    }
  };
  return notificationManager.publish(request)
    .catch((e: BusinessError): void => {
      hilog.warn(DOMAIN, TAG, 'publish %{public}d failed: %{public}s', id, String(e));
    });
};

export const cancelNotification = (id: number): void => {
  notificationManager.cancel(id).catch((e: BusinessError): void => {
    hilog.warn(DOMAIN, TAG, 'cancel %{public}d failed: %{public}s', id, String(e));
  });
};

// 生成完成通知(ChatService.kt:1673-1683 对齐:title=助手名,text=末条消息 take(50))
export const notifyGenerationDone = (senderName: string, contentText: string): void => {
  publishBasicNotification(GENERATION_DONE_ID, senderName, contentText);
};

class NotificationNotifierImpl implements Notifier {
  async notifyRunning(topicId: string, title: string): Promise<void> {
    await publishBasicNotification(notificationIdFor(topicId), '正在深度阅读', title);
  }

  async notifyCompleted(topicId: string, title: string, complete: boolean): Promise<void> {
    // 同 id 覆盖 running 通知
    await publishBasicNotification(notificationIdFor(topicId),
      complete ? '深度阅读完成' : '深度阅读部分完成', title);
  }

  async notifyFailed(topicId: string, title: string, errorMessage: string): Promise<void> {
    await publishBasicNotification(notificationIdFor(topicId), '深度阅读失败',
      `${title} — ${errorMessage}`.slice(0, 120));
  }

  async cancelRunning(topicId: string): Promise<void> {
    cancelNotification(notificationIdFor(topicId));
  }
}

export const createNotificationNotifier = (): Notifier => {
  return new NotificationNotifierImpl();
};

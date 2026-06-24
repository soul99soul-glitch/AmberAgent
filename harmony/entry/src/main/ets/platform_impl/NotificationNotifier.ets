// NotificationNotifier — Notifier 契约的真实实现(对照设计 §6.7 / §8.8)
// 3 类通知:running/completed/failed。ArkTS 兼容:class implements Notifier。
// TODO(notification): notificationManager 的 ContentType/NotificationContent 类型
// 在 API 26 SDK 有模块冲突,需 DevEco 验证。当前用 hilog 输出,标 TODO 接真实通知。

import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Notifier } from '@amber/deepread-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Notifier';

class NotificationNotifier implements Notifier {
  async notifyRunning(topicId: string, title: string): Promise<void> {
    hilog.info(DOMAIN, TAG, '[notifyRunning] topicId=%{public}s title=%{public}s', topicId, title);
    // TODO(notification): 接真实 notificationManager.publish(NotificationRequest)
  }

  async notifyCompleted(topicId: string, title: string, complete: boolean): Promise<void> {
    hilog.info(DOMAIN, TAG, '[notifyCompleted] topicId=%{public}s complete=%{public}s',
      topicId, complete ? 'true' : 'false');
    // TODO(notification): 接真实 notificationManager.publish + cancel running
  }

  async notifyFailed(topicId: string, title: string, errorMessage: string): Promise<void> {
    hilog.warn(DOMAIN, TAG, '[notifyFailed] topicId=%{public}s msg=%{public}s', topicId, errorMessage);
  }

  async cancelRunning(topicId: string): Promise<void> {
    hilog.info(DOMAIN, TAG, '[cancelRunning] topicId=%{public}s', topicId);
  }
}

export const createNotificationNotifier = (): Notifier => {
  return new NotificationNotifier();
};

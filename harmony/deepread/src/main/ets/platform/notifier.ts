// Notifier — NotificationKit 封装的接口契约
// 三类通知:running / completed / failed,点击回 app 文章页

export type NotificationKind = 'running' | 'completed' | 'failed';

export interface NotificationRequest {
  topicId: string;
  title: string;
  kind: NotificationKind;
  sourceUrl: string | null;
  isComplete: boolean;
}

export interface Notifier {
  notifyRunning(topicId: string, title: string): Promise<void>;
  notifyCompleted(topicId: string, title: string, isComplete: boolean): Promise<void>;
  notifyFailed(topicId: string, title: string, errorMessage: string): Promise<void>;
  cancelRunning(topicId: string): Promise<void>;
}

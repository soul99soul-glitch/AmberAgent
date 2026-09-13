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
  // runToken = 调度层每轮作业的 token(R22):cancelRunning 只允许撤当前持有
  // 该 topic 通知的轮次,旧 run 的迟到 cancel 不能撤掉新 run 的通知。
  // 可选参数,既有调用方(不传)保持原语义。
  notifyRunning(topicId: string, title: string, runToken?: number): Promise<void>;
  notifyCompleted(topicId: string, title: string, isComplete: boolean, runToken?: number): Promise<void>;
  notifyFailed(topicId: string, title: string, errorMessage: string, runToken?: number): Promise<void>;
  cancelRunning(topicId: string, runToken?: number): Promise<void>;
}

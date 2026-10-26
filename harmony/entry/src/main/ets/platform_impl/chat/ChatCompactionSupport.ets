import { hilog } from '@kit.PerformanceAnalysisKit';
import type { CompactStore } from '@amber/chat-domain';
import { invalidateCompacts, ContextCompactionFailedError } from '@amber/chat-domain';

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'Chat.Compaction';

export function invalidateChatConversationCompacts(store: CompactStore, convId: string,
  reason: string): Promise<void> {
  return invalidateCompacts(store, convId, reason).catch((error: Error): void => {
    hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'compact invalidate failed: %{public}s', error.message);
  });
}

export function chatCompactionErrorText(error: Error): string {
  return error instanceof ContextCompactionFailedError
    ? '上下文已满，压缩失败，请配置任务模型' : error.message;
}


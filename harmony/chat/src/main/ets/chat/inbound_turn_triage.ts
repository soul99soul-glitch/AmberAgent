// inbound_turn_triage — 运行期自然语言持续接话分流与原位追问回执元数据
// 对齐 iOS ChatInboundTurnTriage.swift + ChatInboundFollowups.swift

import type { JsonObject } from './json.ts';
import type { UIMessage, UIMessagePart, UIMessagePartText } from './message.ts';

export type InboundTurnRoute = 'stop' | 'status' | 'steer' | 'independent';

export interface InboundTurnTriage {
  route: InboundTurnRoute;
  stopAll: boolean;
}

export type FollowupReceiptState = 'queued' | 'applied' | 'not_applied';

export interface FollowupReceiptInfo {
  state: FollowupReceiptState;
  followupId: string;
  runId: string;
}

export const CHAT_FOLLOWUP_STATE_KEY: string = 'chat_followup_state';
export const CHAT_FOLLOWUP_ID_KEY: string = 'chat_followup_id';
export const CHAT_FOLLOWUP_RUN_ID_KEY: string = 'chat_followup_run_id';

const STOP_ALL_PHRASES: string[] = [
  '全部停下', '全部停止', '停止所有任务', '暂停所有任务', 'stop all',
];

const STOP_PHRASES: string[] = [
  '停', '停止', '停一下', '暂停', '暂停一下', '先停一下', '取消', '取消当前任务', 'stop', 'pause',
];

const STATUS_PHRASES: string[] = [
  '进度', '现在到哪了', '现在进展到哪一步了', '进展如何', '现在怎么样了', '做到哪了', 'status', 'progress',
];

const INDEPENDENT_PREFIXES: string[] = [
  '另一个问题', '独立问题', '新任务', '另外帮我', '换个话题', '另开一个任务',
];

const STEER_PREFIXES: string[] = [
  '补充', '请改成', '改成', '顺便', '把刚才', '继续', '结合这张',
];

const normalizeForTriage = (raw: string): string =>
  raw.trim().replace(/[。.!！?？]+$/g, '').trim().toLowerCase();

export const triageInboundTurn = (
  rawText: string,
  hasAttachments: boolean = false,
  hasExplicitTarget: boolean = false,
): InboundTurnTriage => {
  const normalized: string = normalizeForTriage(rawText);
  if (!hasAttachments && normalized.length > 0) {
    if (STOP_ALL_PHRASES.includes(normalized)) {
      return { route: 'stop', stopAll: true };
    }
    if (STOP_PHRASES.includes(normalized)) {
      return { route: 'stop', stopAll: false };
    }
    if (STATUS_PHRASES.includes(normalized)) {
      return { route: 'status', stopAll: false };
    }
  }
  if (hasExplicitTarget) {
    return { route: 'steer', stopAll: false };
  }
  if (INDEPENDENT_PREFIXES.some((prefix: string): boolean => normalized.startsWith(prefix))) {
    return { route: 'independent', stopAll: false };
  }
  if (STEER_PREFIXES.some((prefix: string): boolean => normalized.startsWith(prefix))) {
    return { route: 'steer', stopAll: false };
  }
  // 灰区保守归入当前任务补充（STEER），避免无端中断或拆散主上下文
  return { route: 'steer', stopAll: false };
};

export const withFollowupReceiptMetadata = (
  parts: UIMessagePart[],
  state: FollowupReceiptState,
  followupId: string,
  runId: string = '',
): UIMessagePart[] => {
  const receiptMeta: JsonObject = {
    [CHAT_FOLLOWUP_STATE_KEY]: state,
    [CHAT_FOLLOWUP_ID_KEY]: followupId,
    [CHAT_FOLLOWUP_RUN_ID_KEY]: runId,
  };
  let tagged: boolean = false;
  const nextParts: UIMessagePart[] = parts.map((part: UIMessagePart): UIMessagePart => {
    if (!tagged && part.type === 'text') {
      tagged = true;
      const textPart: UIMessagePartText = part as UIMessagePartText;
      return {
        type: 'text',
        text: textPart.text,
        metadata: { ...(textPart.metadata ?? {}), ...receiptMeta },
      };
    }
    return part;
  });
  if (!tagged) {
    return [{ type: 'text', text: '', metadata: receiptMeta }, ...nextParts];
  }
  return nextParts;
};

export const readFollowupReceipt = (message: UIMessage): FollowupReceiptInfo | null => {
  for (const part of message.parts) {
    if (part.type !== 'text' || part.metadata === null) continue;
    const rawState = part.metadata[CHAT_FOLLOWUP_STATE_KEY];
    if (rawState !== 'queued' && rawState !== 'applied' && rawState !== 'not_applied') continue;
    const rawId = part.metadata[CHAT_FOLLOWUP_ID_KEY];
    const rawRun = part.metadata[CHAT_FOLLOWUP_RUN_ID_KEY];
    return {
      state: rawState,
      followupId: typeof rawId === 'string' ? rawId : '',
      runId: typeof rawRun === 'string' ? rawRun : '',
    };
  }
  return null;
};

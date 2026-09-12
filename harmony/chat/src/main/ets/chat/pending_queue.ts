// pending_queue.ts — FOLLOWUP 发送排队(纯逻辑层)
//
// Android 基准:
//   PendingUserMessage.kt(全文 66 行)
//   ConversationSession.kt enqueue/dequeue/cancel/move/convert/clear(:110-232)
//   ChatService.kt preparePendingMessageForDispatch(:1057-1073)
//
// 语义:生成中用户再发送 → 消息入队(满 20 拒绝);生成完成后循环
// 出队 → 派发(append user + 触发生成)→ 直至队空。
// STEER 插到既有 STEER 前缀之后;COLLECT 头出队时合并后续 leading
// collectable 为一条合成消息。
//
// 裁剪(登记 D-048):
//   - 持久化(Android PendingMessageStore 进程杀恢复)→ 鸿蒙 KV Port(pending_queue_store)
//   - STEER 生成中步间注入已落地:tool_loop.consumeSteerMessages + ChatPage.drainSteerMessages
//     (单跑 runChatTurn 无步间点,与 Android 单步路径同语义)
//   - 工具阻塞跳出(hasPendingOrUnexecutedTools)→ 鸿蒙尚无工具执行,
//     循环无条件排空
//   - previewText else 分支:Kotlin data class toString 无对应,用 JSON.stringify

import type { UIMessagePart, UIMessagePartText } from './message.ts';
import { newId } from './ids.ts';

// PendingUserMessage.kt:6
export const MAX_PENDING_USER_MESSAGES: number = 20;

// PendingUserMessage.kt:8-13(wire 值保大写,与 Android 序列化一致)
export type PendingUserMessageMode = 'FOLLOWUP' | 'STEER' | 'COLLECT';

// PendingUserMessage.kt:15-22
export interface PendingUserMessage {
  id: string;
  parts: UIMessagePart[];
  answer: boolean;
  mode: PendingUserMessageMode;
  createdAtMs: number;
}

export interface PendingUserMessageOpts {
  id?: string;
  parts?: UIMessagePart[];
  answer?: boolean;
  mode?: PendingUserMessageMode;
  createdAtMs?: number;
}

export const makePendingUserMessage = (
  opts: PendingUserMessageOpts = {},
): PendingUserMessage => ({
  id: opts.id ?? newId(),
  parts: opts.parts ?? [],
  answer: opts.answer ?? true,
  mode: opts.mode ?? 'FOLLOWUP',
  createdAtMs: opts.createdAtMs ?? Date.now(),
});

// PendingUserMessage.kt:24-25
export const isCollectablePending = (m: PendingUserMessage): boolean =>
  m.mode === 'COLLECT' && m.parts.every((p: UIMessagePart): boolean => p.type === 'text');

// PendingUserMessage.kt:27-29(已是 FOLLOWUP 原样返回 — 引用相等,测试锁定)
export const pendingAsFollowup = (m: PendingUserMessage): PendingUserMessage =>
  m.mode === 'FOLLOWUP' ? m : { ...m, mode: 'FOLLOWUP' };

// PendingUserMessage.kt:53-65
export const pendingPreviewText = (m: PendingUserMessage, maxChars: number = 180): string => {
  const text: string = m.parts.map((part: UIMessagePart): string => {
    switch (part.type) {
      case 'text':
        return (part as UIMessagePartText).text;
      case 'image':
        return '[图片]';
      case 'video':
        return '[视频]';
      case 'audio':
        return '[音频]';
      case 'document':
        return `[文件] ${(part as { fileName: string }).fileName}`;
      default:
        // Kotlin else 分支 = data class toString;ArkTS 无对应,JSON 代替(登记)
        return JSON.stringify(part);
    }
  }).join('\n').trim();
  if (text.length <= maxChars) return text;
  return text.substring(0, maxChars).trimEnd() + '...';
};

// PendingUserMessage.kt:31-51
export const buildCollectedPendingUserMessage = (
  messages: PendingUserMessage[],
): PendingUserMessage => {
  if (messages.length === 0) throw new Error('messages must not be empty');
  if (messages.length === 1) return pendingAsFollowup(messages[0]);
  // Kotlin buildString appendLine 序列忠实复刻,最后 trim
  let text: string = '下面是用户在上一轮运行时连续排队补充的消息，请按顺序处理：\n';
  messages.forEach((m: PendingUserMessage, index: number): void => {
    text += `\nQueued #${index + 1}:\n${pendingPreviewText(m, 4000)}\n`;
  });
  text = text.trim();
  return {
    id: messages.map((m: PendingUserMessage): string => m.id).join('+'),
    parts: [{ type: 'text', text, metadata: null }],
    answer: messages.some((m: PendingUserMessage): boolean => m.answer),
    mode: 'FOLLOWUP',
    createdAtMs: Math.min(...messages.map((m: PendingUserMessage): number => m.createdAtMs)),
  };
};

// ===== 队列操作(ConversationSession.kt:110-232,纯函数不可变版) =====

export interface EnqueueResult {
  messages: PendingUserMessage[];
  accepted: boolean;
}

// :110-132 — 满 MAX 拒绝(原数组引用不变);STEER 插到 STEER 前缀之后
export const enqueuePendingUserMessage = (
  current: PendingUserMessage[], message: PendingUserMessage,
): EnqueueResult => {
  if (current.length >= MAX_PENDING_USER_MESSAGES) {
    return { messages: current, accepted: false };
  }
  if (message.mode === 'STEER') {
    let steerPrefixSize: number = current.findIndex(
      (m: PendingUserMessage): boolean => m.mode !== 'STEER');
    if (steerPrefixSize < 0) steerPrefixSize = current.length;
    return {
      messages: [
        ...current.slice(0, steerPrefixSize),
        message,
        ...current.slice(steerPrefixSize),
      ],
      accepted: true,
    };
  }
  return { messages: [...current, message], accepted: true };
};

export interface DequeueNextResult {
  next: PendingUserMessage | null;
  rest: PendingUserMessage[];
}

// :134-144
export const dequeueNextPendingUserMessage = (
  current: PendingUserMessage[],
): DequeueNextResult => {
  if (current.length === 0) return { next: null, rest: current };
  return { next: current[0], rest: current.slice(1) };
};

export interface DequeueManyResult {
  consumed: PendingUserMessage[];
  rest: PendingUserMessage[];
}

// :146-156
export const dequeueSteerPendingUserMessages = (
  current: PendingUserMessage[],
): DequeueManyResult => {
  let i: number = 0;
  while (i < current.length && current[i].mode === 'STEER') i++;
  if (i === 0) return { consumed: [], rest: current };
  return { consumed: current.slice(0, i), rest: current.slice(i) };
};

// :158-168
export const dequeueLeadingCollectableMessages = (
  current: PendingUserMessage[],
): DequeueManyResult => {
  let i: number = 0;
  while (i < current.length && isCollectablePending(current[i])) i++;
  if (i === 0) return { consumed: [], rest: current };
  return { consumed: current.slice(0, i), rest: current.slice(i) };
};

export interface ChangedList {
  messages: PendingUserMessage[];
  changed: boolean;
}

// :170-181(未命中 changed=false + 原引用)
export const cancelPendingUserMessage = (
  current: PendingUserMessage[], messageId: string,
): ChangedList => {
  const next: PendingUserMessage[] = current.filter(
    (m: PendingUserMessage): boolean => m.id !== messageId);
  if (next.length === current.length) return { messages: current, changed: false };
  return { messages: next, changed: true };
};

// :183-207(offset=0 → false;target clamp 到 [0,lastIndex];原地 → false)
export const movePendingUserMessage = (
  current: PendingUserMessage[], messageId: string, offset: number,
): ChangedList => {
  if (offset === 0) return { messages: current, changed: false };
  const index: number = current.findIndex(
    (m: PendingUserMessage): boolean => m.id === messageId);
  if (index < 0) return { messages: current, changed: false };
  const target: number = Math.max(0, Math.min(index + offset, current.length - 1));
  if (target === index) return { messages: current, changed: false };
  const list: PendingUserMessage[] = [...current];
  const item: PendingUserMessage = list.splice(index, 1)[0];
  list.splice(target, 0, item);
  return { messages: list, changed: true };
};

// :209-226
export const convertSteerToFollowup = (
  current: PendingUserMessage[],
): ChangedList => {
  let changed: boolean = false;
  const next: PendingUserMessage[] = current.map((m: PendingUserMessage): PendingUserMessage => {
    if (m.mode === 'STEER') {
      changed = true;
      return pendingAsFollowup(m);
    }
    return m;
  });
  if (!changed) return { messages: current, changed: false };
  return { messages: next, changed: true };
};

// ===== 派发准备(ChatService.kt:1057-1073) =====

export interface DispatchPreparation {
  dispatch: PendingUserMessage;
  rest: PendingUserMessage[];
}

// current = 头消息出队后的剩余队列(Android 循环语义:先 dequeueNext 再 prepare)
export const preparePendingMessageForDispatch = (
  current: PendingUserMessage[], message: PendingUserMessage,
): DispatchPreparation => {
  if (isCollectablePending(message)) {
    const { consumed, rest } = dequeueLeadingCollectableMessages(current);
    return {
      dispatch: buildCollectedPendingUserMessage([message, ...consumed]),
      rest,
    };
  }
  if (message.mode === 'STEER') {
    return { dispatch: pendingAsFollowup(message), rest: current };
  }
  return { dispatch: message, rest: current };
};

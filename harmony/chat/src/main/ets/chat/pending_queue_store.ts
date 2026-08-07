// pending_queue_store.ts — 发送队列持久化(纯逻辑层 + KV Port 复用)
//
// Android 基准:
//   PendingMessageStore.kt(全文 104 行):
//     load(:32-40)            无文件 → [];解析失败 → Log.w + [](非静默,onError 回调等价)
//     persistBlocking(:48-60) 空 → delete;非空 → 原子写
//     recordEvent(:62-87)     jsonl 审计行 append(键序 created_at_ms/
//                             conversation_id/event/message_id?/count?/detail?)
//   ChatService.kt: 每次队列变更持久化(ConversationSession.kt:128-231 通知点),
//     enqueue 后 durable(:712),dequeue 后 durable(:1046-1053),session 创建时 load(:334)
//
// 存储形态:复用 KeyValueStore Port(kv_store.ts),键:
//   pending-queue/<conversationId>         — 队列 JSON(kotlinx 线格式 1:1)
//   pending-queue/<conversationId>.events  — 审计 jsonl
//
// 偏差登记:
//   - Android 原子写(tmp+rename,:95-103)→ KV put 单键天然原子,无中间态
//   - Android 文件 append → KV read-concat-write(单页面使用无并发写者)
//   - 审计事件子集:enqueue/dequeue/cancel(entry 现有交互);
//     clear/move/pending_tool_* 事件随对应 UI/工具循环落地时接入

import type { JsonObject, JsonValue } from './json.ts';
import { serializePart, parsePart } from './serialize.ts';
import type { KeyValueStore } from './kv_store.ts';
import type { PendingUserMessage, PendingUserMessageMode } from './pending_queue.ts';
import { makePendingUserMessage } from './pending_queue.ts';

// ===== 键规范 =====

export const pendingQueueKey = (conversationId: string): string =>
  `pending-queue/${conversationId}`;

export const pendingQueueAuditKey = (conversationId: string): string =>
  `pending-queue/${conversationId}.events`;

// KV has no compare-and-swap. Serialize every queue/audit mutation per
// conversation so read-concat-write audit appends and queue deletes cannot lose
// each other's updates.
const mutationTails: Map<string, Promise<void>> = new Map();
const enqueueMutation = (conversationId: string, mutation: () => Promise<void>): Promise<void> => {
  const previous: Promise<void> = mutationTails.get(conversationId) ?? Promise.resolve();
  const next: Promise<void> = previous.catch((): void => {}).then(mutation);
  mutationTails.set(conversationId, next);
  return next.finally((): void => {
    if (mutationTails.get(conversationId) === next) mutationTails.delete(conversationId);
  });
};

// ===== 窄化助手 =====

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const reqStr = (obj: JsonObject, key: string): string => {
  const v: JsonValue | undefined = obj[key];
  if (typeof v !== 'string') throw new Error(`missing field ${key}`);
  return v;
};

// ===== 序列化(kotlinx 线格式:声明序 id/parts/answer/mode/createdAtMs) =====

export const serializePendingUserMessage = (m: PendingUserMessage): JsonObject => ({
  id: m.id,
  parts: m.parts.map(serializePart) as JsonValue[],
  answer: m.answer,
  mode: m.mode,
  createdAtMs: m.createdAtMs,
});

export const serializePendingQueue = (messages: PendingUserMessage[]): string =>
  JSON.stringify(messages.map(serializePendingUserMessage));

// ===== 解析(ignoreUnknownKeys + 默认值,now 注入对齐 createdAtMs 缺省语义) =====

const parsePendingUserMessage = (v: JsonValue, now: () => number): PendingUserMessage => {
  if (!isObj(v)) throw new Error('pending message must be object');
  const id: string = reqStr(v, 'id');
  const partsRaw: JsonValue | undefined = v['parts'];
  if (!Array.isArray(partsRaw)) throw new Error('missing field parts');
  const answerRaw: JsonValue | undefined = v['answer'];
  const modeRaw: JsonValue | undefined = v['mode'];
  const createdRaw: JsonValue | undefined = v['createdAtMs'];
  let mode: PendingUserMessageMode = 'FOLLOWUP';
  if (modeRaw === 'FOLLOWUP' || modeRaw === 'STEER' || modeRaw === 'COLLECT') {
    mode = modeRaw;
  } else if (modeRaw !== undefined) {
    throw new Error(`unknown mode ${String(modeRaw)}`);
  }
  return makePendingUserMessage({
    id,
    parts: partsRaw.map((p: JsonValue) => parsePart(p, () => new Date().toISOString())),
    answer: typeof answerRaw === 'boolean' ? answerRaw : true,
    mode,
    createdAtMs: typeof createdRaw === 'number' ? createdRaw : now(),
  });
};

export const parsePendingQueue = (raw: string, now: () => number): PendingUserMessage[] => {
  const v: JsonValue = JSON.parse(raw) as JsonValue;
  if (!Array.isArray(v)) throw new Error('pending queue must be array');
  return v.map((m: JsonValue) => parsePendingUserMessage(m, now));
};

// ===== 持久化(PendingMessageStore.kt:48-60 — 空删非空写) =====

export const persistPendingQueue = async (
  store: KeyValueStore, conversationId: string, messages: PendingUserMessage[],
): Promise<void> => enqueueMutation(conversationId, async (): Promise<void> => {
  if (messages.length === 0) {
    await store.delete(pendingQueueKey(conversationId));
  } else {
    await store.put(pendingQueueKey(conversationId), serializePendingQueue(messages));
  }
});

export const clearPendingQueueStorage = async (
  store: KeyValueStore, conversationId: string,
): Promise<void> => enqueueMutation(conversationId, async (): Promise<void> => {
  await store.delete(pendingQueueKey(conversationId));
  await store.delete(pendingQueueAuditKey(conversationId));
});

// ===== 载入(PendingMessageStore.kt:32-40 — 无键/损坏均收敛 [],onError 非静默) =====

export const loadPendingQueue = async (
  store: KeyValueStore, conversationId: string,
  now: () => number = Date.now,
  onError?: (e: Error) => void,
): Promise<PendingUserMessage[]> => {
  const raw: string | null = await store.get(pendingQueueKey(conversationId));
  if (raw === null) return [];
  try {
    return parsePendingQueue(raw, now);
  } catch (e) {
    if (onError !== undefined) onError(e as Error);
    return [];
  }
};

// ===== 审计事件(recordEvent:62-87 — buildJsonObject 键序忠实) =====

export interface PendingQueueAuditEvent {
  event: string;
  messageId?: string;
  count?: number;
  detail?: string;
}

export const buildAuditEventLine = (
  conversationId: string, e: PendingQueueAuditEvent, nowMs: number,
): string => {
  const obj: JsonObject = {
    created_at_ms: nowMs,
    conversation_id: conversationId,
    event: e.event,
  };
  if (e.messageId !== undefined) obj['message_id'] = e.messageId;
  if (e.count !== undefined) obj['count'] = e.count;
  if (e.detail !== undefined) obj['detail'] = e.detail;
  return `${JSON.stringify(obj)}\n`;
};

const appendAuditEvent = async (
  store: KeyValueStore, conversationId: string, e: PendingQueueAuditEvent,
  nowMs: number,
): Promise<void> => {
  const key: string = pendingQueueAuditKey(conversationId);
  const existing: string | null = await store.get(key);
  await store.put(key, (existing ?? '') + buildAuditEventLine(conversationId, e, nowMs));
};

export const persistPendingQueueWithEvent = async (
  store: KeyValueStore, conversationId: string, messages: PendingUserMessage[],
  e: PendingQueueAuditEvent, nowMs: number = Date.now(),
): Promise<void> => enqueueMutation(conversationId, async (): Promise<void> => {
  if (messages.length === 0) {
    await store.delete(pendingQueueKey(conversationId));
  } else {
    await store.put(pendingQueueKey(conversationId), serializePendingQueue(messages));
  }
  await appendAuditEvent(store, conversationId, e, nowMs);
});

export const recordPendingQueueEvent = async (
  store: KeyValueStore, conversationId: string, e: PendingQueueAuditEvent,
  nowMs: number = Date.now(),
): Promise<void> => enqueueMutation(conversationId, async (): Promise<void> => {
  await appendAuditEvent(store, conversationId, e, nowMs);
});

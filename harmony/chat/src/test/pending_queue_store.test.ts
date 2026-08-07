// pending_queue_store.test.ts — 队列持久化纯逻辑(TDD 先行)
//
// Android 基准:
//   PendingMessageStore.kt(全文 104 行):load(:32-40)/persistBlocking(:48-60)/
//     recordEvent(:62-87)/queueFile-auditFile(:89-93)/writeTextAtomically(:95-103)
//   ChatService.kt: 每次变更持久化(ConversationSession.kt:128-231 通知点)、
//     enqueue 后 durable(:712)、dequeue 后 durable(:1046-1053)、
//     session 创建时 load(:334)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  pendingQueueKey, pendingQueueAuditKey,
  serializePendingUserMessage, serializePendingQueue, parsePendingQueue,
  persistPendingQueue, loadPendingQueue, clearPendingQueueStorage,
  buildAuditEventLine, recordPendingQueueEvent,
} from '../main/ets/chat/pending_queue_store.ts';
import { makePendingUserMessage } from '../main/ets/chat/pending_queue.ts';
import type { PendingUserMessage } from '../main/ets/chat/pending_queue.ts';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';

const msg = (id: string, text: string, mode: 'FOLLOWUP' | 'STEER' | 'COLLECT' = 'FOLLOWUP',
  answer: boolean = true, createdAtMs: number = 1000): PendingUserMessage =>
  makePendingUserMessage({
    id,
    parts: [{ type: 'text', text, metadata: null }],
    answer, mode, createdAtMs,
  });

describe('serializePendingUserMessage(kotlinx 线格式)', () => {
  it('字段名与声明序:id/parts/answer/mode/createdAtMs', () => {
    const raw: string = serializePendingQueue([msg('m1', '你好', 'STEER', false, 42)]);
    assert.equal(raw,
      '[{"id":"m1","parts":[{"type":"text","text":"你好"}],"answer":false,"mode":"STEER","createdAtMs":42}]');
  });

  it('单条序列化返回 JsonObject(键可直接取)', () => {
    const obj = serializePendingUserMessage(msg('m1', 'x'));
    assert.equal(obj['id'], 'm1');
    assert.equal(obj['mode'], 'FOLLOWUP');
  });
});

describe('parsePendingQueue(ignoreUnknownKeys + 默认值)', () => {
  it('round-trip 保持 answer/mode/createdAtMs/parts', () => {
    const src: PendingUserMessage[] = [
      msg('a', '甲', 'FOLLOWUP', true, 1),
      msg('b', '乙', 'STEER', false, 2),
      { ...msg('c', '丙'), parts: [{ type: 'image', url: 'data:image/png;base64,xx', metadata: null }] },
    ];
    const out: PendingUserMessage[] = parsePendingQueue(serializePendingQueue(src), () => 999);
    assert.deepEqual(out, src);
  });

  it('缺省字段回默认:answer=true/mode=FOLLOWUP/createdAtMs=注入 now', () => {
    const out: PendingUserMessage[] = parsePendingQueue(
      '[{"id":"m","parts":[{"type":"text","text":"t"}]}]', () => 777);
    assert.equal(out.length, 1);
    assert.equal(out[0].answer, true);
    assert.equal(out[0].mode, 'FOLLOWUP');
    assert.equal(out[0].createdAtMs, 777);
  });

  it('未知键容忍(kotlinx ignoreUnknownKeys)', () => {
    const out: PendingUserMessage[] = parsePendingQueue(
      '[{"id":"m","parts":[{"type":"text","text":"t"}],"futureField":1}]', () => 1);
    assert.equal(out.length, 1);
  });

  it('非法 JSON / 非数组 → 抛错(由 loadPendingQueue 收敛为 [])', () => {
    assert.throws(() => parsePendingQueue('not-json', () => 1));
    assert.throws(() => parsePendingQueue('{"a":1}', () => 1));
  });
});

describe('persistPendingQueue(PendingMessageStore.kt:48-60)', () => {
  it('非空 → put 到 pending-queue/<conversationId>', async () => {
    const store = createMemoryKeyValueStore();
    await persistPendingQueue(store, 'conv-1', [msg('m1', 'x')]);
    assert.equal(store.entries.has(pendingQueueKey('conv-1')), true);
    assert.equal(store.entries.size, 1);
  });

  it('空队列 → delete 键(Android file.delete() 语义)', async () => {
    const store = createMemoryKeyValueStore();
    await persistPendingQueue(store, 'conv-1', [msg('m1', 'x')]);
    await persistPendingQueue(store, 'conv-1', []);
    assert.equal(store.entries.has(pendingQueueKey('conv-1')), false);
  });
});

describe('loadPendingQueue(PendingMessageStore.kt:32-40)', () => {
  it('无键 → 空数组(不创建键)', async () => {
    const store = createMemoryKeyValueStore();
    const out: PendingUserMessage[] = await loadPendingQueue(store, 'conv-x');
    assert.deepEqual(out, []);
    assert.equal(store.entries.size, 0);
  });

  it('persist → load round-trip(进程死亡恢复语义)', async () => {
    const store = createMemoryKeyValueStore();
    const src: PendingUserMessage[] = [msg('m1', '排队一'), msg('m2', '排队二', 'STEER', false)];
    await persistPendingQueue(store, 'conv-1', src);
    const out: PendingUserMessage[] = await loadPendingQueue(store, 'conv-1');
    assert.deepEqual(out, src);
  });

  it('损坏数据 → 空数组 + onError 回调(Android Log.w + getOrDefault(emptyList) 非静默)', async () => {
    const store = createMemoryKeyValueStore();
    store.entries.set(pendingQueueKey('conv-1'), '{{{corrupt');
    let seen: Error | null = null;
    const out: PendingUserMessage[] = await loadPendingQueue(store, 'conv-1', undefined,
      (e: Error): void => { seen = e; });
    assert.deepEqual(out, []);
    assert.ok(seen !== null);
  });
});

describe('buildAuditEventLine(recordEvent:62-87,键序忠实)', () => {
  it('全字段:created_at_ms/conversation_id/event/message_id/count/detail + 换行', () => {
    const line: string = buildAuditEventLine('conv-1', {
      event: 'dequeue', messageId: 'm1', count: 3, detail: 'followup',
    }, 1234567890);
    assert.equal(line,
      '{"created_at_ms":1234567890,"conversation_id":"conv-1","event":"dequeue",'
      + '"message_id":"m1","count":3,"detail":"followup"}\n');
  });

  it('可选字段缺省即省略(explicitNulls 语义)', () => {
    const line: string = buildAuditEventLine('conv-1', { event: 'cancel', messageId: 'm9' }, 5);
    assert.equal(line,
      '{"created_at_ms":5,"conversation_id":"conv-1","event":"cancel","message_id":"m9"}\n');
  });
});

describe('recordPendingQueueEvent(jsonl append)', () => {
  it('并发 append 按调用序串行且不丢审计行', async () => {
    const store = createMemoryKeyValueStore();
    await Promise.all([
      recordPendingQueueEvent(store, 'conv-race', { event: 'enqueue', messageId: 'm1' }, 1),
      recordPendingQueueEvent(store, 'conv-race', { event: 'dequeue', messageId: 'm1' }, 2),
      recordPendingQueueEvent(store, 'conv-race', { event: 'cancel', messageId: 'm2' }, 3),
    ]);
    const blob: string = store.entries.get(pendingQueueAuditKey('conv-race')) ?? '';
    assert.deepEqual(blob.trim().split('\n').map((line: string) => JSON.parse(line).event),
      ['enqueue', 'dequeue', 'cancel']);
  });

  it('clear 同一会话的队列和审计', async () => {
    const store = createMemoryKeyValueStore();
    await persistPendingQueue(store, 'conv-clear', [msg('m1', 'x')]);
    await recordPendingQueueEvent(store, 'conv-clear', { event: 'enqueue', messageId: 'm1' }, 1);
    await clearPendingQueueStorage(store, 'conv-clear');
    assert.equal(store.entries.has(pendingQueueKey('conv-clear')), false);
    assert.equal(store.entries.has(pendingQueueAuditKey('conv-clear')), false);
  });
});

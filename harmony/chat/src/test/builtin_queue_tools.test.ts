// builtin_queue_tools.test.ts — D-059 conversation_queue_* 工具对
// Android 基准: ChatService.kt:2457-2544(createConversationQueueTools)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import type { PendingUserMessage } from '../main/ets/chat/pending_queue.ts';
import { makePendingUserMessage, pendingPreviewText } from '../main/ets/chat/pending_queue.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { ConversationQueueToolsDeps } from '../main/ets/chat/builtin_queue_tools.ts';
import { createConversationQueueTools } from '../main/ets/chat/builtin_queue_tools.ts';

interface CallLog {
  cancelOne: string[];
  clearAll: number;
}

// fake deps:可变队列 + 调用记录(Android getOrCreateSession 内存态语义)
const makeDeps = (initial: PendingUserMessage[]): {
  deps: ConversationQueueToolsDeps;
  log: CallLog;
  queue: () => PendingUserMessage[];
} => {
  let queue: PendingUserMessage[] = initial;
  const log: CallLog = { cancelOne: [], clearAll: 0 };
  const deps: ConversationQueueToolsDeps = {
    pendingProvider: (): Promise<PendingUserMessage[]> => Promise.resolve(queue),
    cancelOne: (messageId: string): Promise<void> => {
      log.cancelOne.push(messageId);
      queue = queue.filter((m: PendingUserMessage): boolean => m.id !== messageId);
      return Promise.resolve();
    },
    clearAll: (): Promise<void> => {
      log.clearAll += 1;
      queue = [];
      return Promise.resolve();
    },
  };
  return { deps, log, queue: (): PendingUserMessage[] => queue };
};

const textOf = (parts: UIMessagePart[]): JsonObject =>
  JSON.parse((parts[0] as { text: string }).text) as JsonObject;

const toolByName = (tools: AgentTool[], name: string): AgentTool => {
  const t: AgentTool | undefined = tools.find((x: AgentTool): boolean => x.name === name);
  assert.ok(t, `tool ${name} should exist`);
  return t;
};

test('conversation_queue_status:空队列 → status ok / count 0 / messages []', async () => {
  const { deps } = makeDeps([]);
  const tools: AgentTool[] = createConversationQueueTools(deps);
  const t: AgentTool = toolByName(tools, 'conversation_queue_status');
  const payload: JsonObject = textOf(await t.execute({}));
  assert.equal(payload['status'], 'ok');
  assert.equal(payload['count'], 0);
  assert.deepEqual(payload['messages'], []);
});

test('conversation_queue_status:条目键序 + mode 小写 + preview 默认 180', async () => {
  const m1: PendingUserMessage = makePendingUserMessage({
    id: 'm1', mode: 'FOLLOWUP', answer: true, createdAtMs: 123,
    parts: [{ type: 'text', text: 'hello queue', metadata: null }],
  });
  const m2: PendingUserMessage = makePendingUserMessage({
    id: 'm2', mode: 'STEER', answer: false, createdAtMs: 456,
    parts: [{ type: 'text', text: 'x'.repeat(300), metadata: null }],
  });
  const { deps } = makeDeps([m1, m2]);
  const t: AgentTool = toolByName(createConversationQueueTools(deps), 'conversation_queue_status');
  const payload: JsonObject = textOf(await t.execute({}));
  assert.equal(payload['count'], 2);
  const items: JsonValue = payload['messages'];
  assert.ok(Array.isArray(items));
  const first: JsonObject = items[0] as JsonObject;
  assert.deepEqual(Object.keys(first),
    ['index', 'id', 'mode', 'answer', 'created_at_ms', 'preview']);
  assert.equal(first['index'], 0);
  assert.equal(first['id'], 'm1');
  assert.equal(first['mode'], 'followup');
  assert.equal(first['answer'], true);
  assert.equal(first['created_at_ms'], 123);
  assert.equal(first['preview'], 'hello queue');
  const second: JsonObject = items[1] as JsonObject;
  assert.equal(second['mode'], 'steer');
  assert.equal(second['preview'], pendingPreviewText(m2));
});

test('conversation_queue_status:只读 — 不触发 cancel/clear', async () => {
  const { deps, log } = makeDeps([makePendingUserMessage({ id: 'm1' })]);
  const t: AgentTool = toolByName(createConversationQueueTools(deps), 'conversation_queue_status');
  await t.execute({});
  assert.equal(log.cancelOne.length, 0);
  assert.equal(log.clearAll, 0);
});

test('conversation_queue_cancel:按 id 命中 → cancelled + remaining 更新', async () => {
  const { deps, log } = makeDeps([
    makePendingUserMessage({ id: 'm1' }),
    makePendingUserMessage({ id: 'm2' }),
  ]);
  const t: AgentTool = toolByName(createConversationQueueTools(deps), 'conversation_queue_cancel');
  assert.equal(t.needsApproval, true);
  const payload: JsonObject = textOf(await t.execute({ message_id: 'm1' }));
  assert.equal(payload['status'], 'cancelled');
  assert.equal(payload['remaining'], 1);
  assert.deepEqual(log.cancelOne, ['m1']);
  assert.equal(log.clearAll, 0);
});

test('conversation_queue_cancel:按 id 未命中 → not_found(cancel 仍调用,Android 无条件派发)', async () => {
  const { deps, log } = makeDeps([makePendingUserMessage({ id: 'm1' })]);
  const t: AgentTool = toolByName(createConversationQueueTools(deps), 'conversation_queue_cancel');
  const payload: JsonObject = textOf(await t.execute({ message_id: 'nope' }));
  assert.equal(payload['status'], 'not_found');
  assert.equal(payload['remaining'], 1);
  assert.deepEqual(log.cancelOne, ['nope']);
});

test('conversation_queue_cancel:clear_all=true 有消息 → cancelled + remaining 0', async () => {
  const { deps, log } = makeDeps([
    makePendingUserMessage({ id: 'm1' }),
    makePendingUserMessage({ id: 'm2' }),
  ]);
  const t: AgentTool = toolByName(createConversationQueueTools(deps), 'conversation_queue_cancel');
  const payload: JsonObject = textOf(await t.execute({ clear_all: true }));
  assert.equal(payload['status'], 'cancelled');
  assert.equal(payload['remaining'], 0);
  assert.equal(log.clearAll, 1);
  assert.equal(log.cancelOne.length, 0);
});

test('conversation_queue_cancel:clear_all=true 空队列 → not_found(clear 仍调用)', async () => {
  const { deps, log } = makeDeps([]);
  const t: AgentTool = toolByName(createConversationQueueTools(deps), 'conversation_queue_cancel');
  const payload: JsonObject = textOf(await t.execute({ clear_all: true }));
  assert.equal(payload['status'], 'not_found');
  assert.equal(payload['remaining'], 0);
  assert.equal(log.clearAll, 1);
});

test('conversation_queue_cancel:无 id 且 clear_all 缺省 → require 文案逐字', async () => {
  const { deps } = makeDeps([makePendingUserMessage({ id: 'm1' })]);
  const t: AgentTool = toolByName(createConversationQueueTools(deps), 'conversation_queue_cancel');
  await assert.rejects(
    (): Promise<UIMessagePart[]> => t.execute({}),
    (e: Error): boolean => e.message === 'message_id is required unless clear_all=true',
  );
});

test("conversation_queue_cancel:clear_all 字符串 'true' → 视为 false(toBooleanStrictOrNull 语义)", async () => {
  const { deps } = makeDeps([makePendingUserMessage({ id: 'm1' })]);
  const t: AgentTool = toolByName(createConversationQueueTools(deps), 'conversation_queue_cancel');
  await assert.rejects(
    (): Promise<UIMessagePart[]> => t.execute({ clear_all: 'true' }),
    (e: Error): boolean => e.message === 'message_id is required unless clear_all=true',
  );
});

test('schema:描述逐字 + cancel needsApproval=true / status 默认 false', () => {
  const { deps } = makeDeps([]);
  const tools: AgentTool[] = createConversationQueueTools(deps);
  const status: AgentTool = toolByName(tools, 'conversation_queue_status');
  assert.equal(status.description,
    'Read queued user messages for the current conversation. This is read-only and never exposes messages from other conversations.');
  assert.equal(status.needsApproval, false);
  const cancel: AgentTool = toolByName(tools, 'conversation_queue_cancel');
  assert.equal(cancel.description,
    'Cancel one queued user message by id, or clear the current conversation queue. Requires approval because it changes user-entered pending messages.');
  assert.equal(cancel.needsApproval, true);
  const schema = cancel.parameters();
  assert.ok(schema !== null);
  const props: JsonObject = schema.properties;
  const messageId: JsonObject = props['message_id'] as JsonObject;
  assert.equal(messageId['description'],
    'Queued message id to cancel. Omit when clear_all=true.');
  const clearAll: JsonObject = props['clear_all'] as JsonObject;
  assert.equal(clearAll['description'],
    'Clear every queued message in the current conversation.');
});

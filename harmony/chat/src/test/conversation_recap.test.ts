import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeConversation, toMessageNode, makeUserMessage, makeAssistantMessage } from '../main/ets/index.ts';
import type { Conversation } from '../main/ets/index.ts';
import { ConversationRecapService, makeRecapInput, parseRecap, projectRecap, recapIsStale,
  recapEligible, decodeStoredRecap } from '../main/ets/chat/conversation_recap.ts';
import type { ConversationRecap, RecapServiceDeps } from '../main/ets/chat/conversation_recap.ts';

const conversation = (id = 'a'): Conversation => makeConversation(id,
  ['问题1', '回答1', '问题2', '回答2', '问题3', '回答3'].map((text, index) =>
    toMessageNode(index % 2 === 0 ? makeUserMessage(text) : makeAssistantMessage(text))));
const response = JSON.stringify({ overview: '已完成方案，还有部署待处理。', nodes: [
  { kind: 'decision', title: '选定方案', messageRef: 'm1' },
  { kind: 'artifact', title: '生成产物', messageRef: 'm6' },
  { kind: 'failure', title: '不可定位的引用', messageRef: 'm999' },
], nextSteps: ['部署'] });

test('structured recap validates JSON, maps only real references, and requires three user messages', () => {
  const conv = conversation();
  assert.equal(recapEligible(conv), true);
  assert.equal(recapEligible({ ...conv, messageNodes: conv.messageNodes.slice(0, 4) }), false);
  const input = makeRecapInput(conv, null);
  const recap = parseRecap('```json\n' + response + '\n```', conv.id, input);
  assert.equal(recap.nodes[0].messageId, conv.messageNodes[0].messages[0].id);
  assert.equal(recap.nodes[2].messageId, null);
  assert.throws(() => parseRecap('{}', conv.id, input), /不完整/);
  assert.throws(() => parseRecap('invalid', conv.id, input), /格式/);
  assert.throws(() => parseRecap(JSON.stringify({ overview: 'x', nodes: [], nextSteps: [] }), conv.id, input));
  assert.equal(decodeStoredRecap('{"nodes":[]}', conv.id), null);
  assert.deepEqual(decodeStoredRecap(JSON.stringify(recap), conv.id), recap);
  assert.equal(decodeStoredRecap(JSON.stringify(recap), 'different-owner'), null);
});

test('append is incremental; edits and branch switches re-evaluate and project source IDs', () => {
  const conv = conversation();
  const recap = parseRecap(response, conv.id, makeRecapInput(conv, null));
  assert.equal(recapIsStale(recap, conv), false);
  const appended = { ...conv, messageNodes: [...conv.messageNodes, toMessageNode(makeUserMessage('新增问题'))] };
  const increment = makeRecapInput(appended, recap);
  assert.match(increment.prompt, /m7 user: 新增问题/);
  assert.doesNotMatch(increment.prompt, /user: 问题1/);
  assert.equal(recapIsStale(recap, appended), true);
  const edited = { ...conv, messageNodes: conv.messageNodes.map((node, index) => index === 0
    ? { ...node, messages: [{ ...makeUserMessage('已编辑的问题'), id: node.messages[0].id }] } : node) };
  assert.equal(recapIsStale(recap, edited), true);
  assert.match(makeRecapInput(edited, recap).prompt, /user: 已编辑的问题/);
  const branched = { ...conv, messageNodes: conv.messageNodes.map((node, index) => index === 5
    ? { ...node, messages: [...node.messages, makeAssistantMessage('替代回答')], selectIndex: 1 } : node) };
  assert.equal(recapIsStale(recap, branched), true);
  assert.equal(projectRecap(recap, branched).nodes[1].messageId, null);
});

test('service merges requests, preserves owner after unsubscribe, and rejects invalidated results', async () => {
  const conv = conversation();
  let stored: ConversationRecap | null = null;
  let finish: (raw: string) => void = () => {};
  let calls = 0;
  const deps: RecapServiceDeps = {
    loadConversation: async () => conv, read: async () => stored,
    write: async (recap, current) => { if (!current()) return false; stored = recap; return true; },
    remove: async () => { stored = null; }, compactSummary: async () => '',
    generate: () => { calls++; return new Promise<string>((resolve) => { finish = resolve; }); },
  };
  const service = new ConversationRecapService(deps);
  const unsubscribe = service.subscribe(conv.id, () => {});
  const first = service.request(conv.id);
  assert.equal(service.request(conv.id), first);
  unsubscribe();
  while (calls === 0) await new Promise<void>((resolve) => setImmediate(resolve));
  finish(response); await first;
  assert.equal(service.state(conv.id).recap?.conversationId, conv.id);
  assert.equal(service.state(conv.id).loading, false);
  await service.request(conv.id); assert.equal(calls, 1, 'fresh cache skips provider');
  await service.invalidate(conv.id);
  const stale = service.request(conv.id);
  while (calls === 1) await new Promise<void>((resolve) => setImmediate(resolve));
  await service.invalidate(conv.id); finish(response); await stale;
  assert.equal(stored, null);
  assert.equal(service.state(conv.id).recap, null);
});

test('provider failure keeps an older recap available and permits retry', async () => {
  const conv = conversation();
  const old = parseRecap(response, conv.id, makeRecapInput(conv, null));
  const appended = { ...conv, messageNodes: [...conv.messageNodes, toMessageNode(makeUserMessage('继续'))] };
  let fail = true;
  const service = new ConversationRecapService({
    loadConversation: async () => appended, read: async () => old, remove: async () => {},
    compactSummary: async () => '', write: async () => true,
    generate: async () => { if (fail) throw new Error('服务不可用'); return response; },
  });
  await service.request(conv.id);
  assert.equal(service.state(conv.id).recap, old);
  assert.equal(service.state(conv.id).error, '服务不可用');
  fail = false; await service.request(conv.id);
  assert.equal(service.state(conv.id).error, '');
  assert.equal(service.state(conv.id).recap?.coveredThroughMessageId, appended.messageNodes.at(-1)?.messages[0].id);
});

test('completion during an active recap drains the latest conversation; explicit refresh regenerates', async () => {
  let conv = conversation();
  let stored: ConversationRecap | null = null;
  let finish: (raw: string) => void = () => {};
  let calls = 0;
  const service = new ConversationRecapService({
    loadConversation: async () => conv, read: async () => stored, remove: async () => {},
    compactSummary: async () => '', write: async (recap) => { stored = recap; return true; },
    generate: () => { calls++; return calls === 1
      ? new Promise<string>((resolve) => { finish = resolve; }) : Promise.resolve(response); },
  });
  const active = service.request(conv.id);
  while (calls === 0) await new Promise<void>((resolve) => setImmediate(resolve));
  conv = { ...conv, messageNodes: [...conv.messageNodes, toMessageNode(makeUserMessage('第4轮'))] };
  assert.equal(service.request(conv.id), active);
  finish(response); await active;
  assert.equal(calls, 2);
  assert.equal(service.state(conv.id).recap?.coveredThroughMessageId, conv.messageNodes.at(-1)?.messages[0].id);
  await service.request(conv.id, true);
  assert.equal(calls, 3);
});

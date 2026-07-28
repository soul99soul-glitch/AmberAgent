// builtin_conversation_tools.test.ts — conversation_* 工具组(D-058 TDD)
//
// Android 基准: app/feature/tools/ConversationContextTools.kt 全文
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createConversationContextTools } from '../main/ets/chat/builtin_conversation_tools.ts';
import type { ConversationContextToolsDeps } from '../main/ets/chat/builtin_conversation_tools.ts';
import { createMemoryCompactStore } from '../main/ets/chat/context_engine.ts';
import type { MemoryCompactStore } from '../main/ets/chat/context_engine.ts';
import { makeCompactPolicy } from '../main/ets/chat/context_compact.ts';
import type { ConversationCompact } from '../main/ets/chat/context_compact.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeUserMessage, makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { MessageChunk, UIMessage, UIMessagePart, UIMessagePartText } from '../main/ets/chat/message.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';

const textOf = (parts: UIMessagePart[]): string => (parts[0] as UIMessagePartText).text;
const jsonOf = (parts: UIMessagePart[]): Record<string, unknown> => JSON.parse(textOf(parts));

const seedConv = (): Conversation => makeConversation('c1', [
  toMessageNode(makeUserMessage('苹果多少钱一斤')),
  toMessageNode(makeAssistantMessage('苹果五块钱一斤')),
  toMessageNode(makeUserMessage('那香蕉呢')),
  toMessageNode(makeAssistantMessage('香蕉三块钱一斤')),
]);

const makeCompact = (over: Partial<ConversationCompact> = {}): ConversationCompact => ({
  id: 'compact-1',
  conversationId: 'c1',
  summary: '## 摘要\n用户问了苹果和香蕉的价格',
  level: 1,
  sourceStartIndex: 0,
  sourceEndIndex: 1,
  sourceMessageIds: [],
  tokenEstimate: 100,
  createdAt: 1000,
  updatedAt: 1000,
  status: 'completed',
  ...over,
});

const makeDeps = (conv: Conversation, extra: Partial<ConversationContextToolsDeps> = {}): {
  deps: ConversationContextToolsDeps; store: MemoryCompactStore;
} => {
  const store = createMemoryCompactStore();
  const deps: ConversationContextToolsDeps = {
    conversationProvider: () => Promise.resolve(conv),
    compactStore: store,
    policy: makeCompactPolicy({ keepRecentTurns: 8 }),
    modelContextWindowTokens: 128000,
    compactEngineDeps: {
      provider: {} as ChatStreamProvider,
      store,
    },
    ...extra,
  };
  return { deps, store };
};

const toolByName = (tools: AgentTool[], name: string): AgentTool =>
  tools.find((t: AgentTool): boolean => t.name === name) as AgentTool;

describe('conversation_context_status(:33-43)', () => {
  it('payload 键序逐字(effective 头条 + raw 对照;生命周期 idle)', async () => {
    const conv = seedConv();
    const { deps, store } = makeDeps(conv);
    await store.insertCompact(makeCompact());
    const tool = toolByName(createConversationContextTools(deps), 'conversation_context_status');
    const schema = tool.parameters();
    assert.ok(schema !== null && Object.keys(schema.properties).length === 0);
    const out = jsonOf(await tool.execute({}));
    const keys = Object.keys(out);
    assert.deepEqual(keys, [
      'enabled', 'notify_only', 'estimated_tokens', 'raw_tokens', 'context_window_tokens',
      'pressure_ratio', 'raw_pressure_ratio', 'summary_count', 'latest_status',
      'compact_lifecycle_status', 'next_action', 'raw_next_action',
    ]);
    assert.equal(out['enabled'], true);
    assert.equal(out['context_window_tokens'], 128000);
    assert.equal(out['summary_count'], 1);
    assert.equal(out['latest_status'], 'completed');
    assert.equal(out['compact_lifecycle_status'], 'idle');
    assert.equal(out['next_action'], 'below_threshold');
  });
});

describe('conversation_search(:45-86)', () => {
  it('消息命中带 node_index + previewAround 窗口;limit 默认 8 coerce 1..20', async () => {
    const conv = seedConv();
    const { deps } = makeDeps(conv);
    const tool = toolByName(createConversationContextTools(deps), 'conversation_search');
    const out = jsonOf(await tool.execute({ query: '香蕉' }));
    assert.equal(out['status'], 'ok');
    assert.equal(out['query'], '香蕉');
    const results = out['results'] as { source: string; id: string; node_index?: number; preview: string }[];
    assert.equal(results.length, 2);
    assert.equal(results[0].source, 'message');
    assert.equal(results[0].node_index, 2);
    assert.ok(results[0].preview.includes('香蕉'));
    assert.equal(results[1].node_index, 3);
  });
  it('compact 摘要命中 source=compact_summary 无 node_index,排在消息结果前', async () => {
    const conv = seedConv();
    const { deps, store } = makeDeps(conv);
    await store.insertCompact(makeCompact());
    const tool = toolByName(createConversationContextTools(deps), 'conversation_search');
    const out = jsonOf(await tool.execute({ query: '苹果' }));
    const results = out['results'] as { source: string; node_index?: number }[];
    assert.equal(results[0].source, 'compact_summary');
    assert.equal(results[0].node_index, undefined);
    assert.equal(results[1].source, 'message');
  });
});

describe('conversation_expand(:88-128)', () => {
  it('消息 id ± radius(默认 2,coerce 0..8);role 小写;text take(12000)', async () => {
    const conv = seedConv();
    const { deps } = makeDeps(conv);
    const tool = toolByName(createConversationContextTools(deps), 'conversation_expand');
    const midId = conv.messageNodes[1].messages[0].id;
    const out = jsonOf(await tool.execute({ source_id: midId, radius: 1 }));
    assert.equal(out['status'], 'ok');
    const messages = out['messages'] as { id: string; role: string; text: string }[];
    assert.equal(messages.length, 3);
    assert.equal(messages[1].id, midId);
    assert.equal(messages[0].role, 'user');
  });
  it('compact id → sourceMessageIds 过滤;未知 id → 空数组', async () => {
    const conv = seedConv();
    const { deps, store } = makeDeps(conv);
    const srcIds = [conv.messageNodes[0].messages[0].id, conv.messageNodes[1].messages[0].id];
    await store.insertCompact(makeCompact({ sourceMessageIds: srcIds }));
    const tool = toolByName(createConversationContextTools(deps), 'conversation_expand');
    const out = jsonOf(await tool.execute({ source_id: 'compact-1' }));
    const messages = out['messages'] as { id: string }[];
    assert.deepEqual(messages.map((m): string => m.id), srcIds);
    const empty = jsonOf(await tool.execute({ source_id: 'nope' }));
    assert.deepEqual(empty['messages'], []);
  });
});

describe('conversation_compact(:130-166)', () => {
  it('force 手动压缩:摘要入库,payload 键序(status/summary_id/count/tokens)', async () => {
    const conv = seedConv();
    // 极简 provider:流式回一段符合质量门的摘要(>2 句)
    const summaryChunk = {
      id: 'c', model: 'm',
      choices: [{
        index: 0,
        delta: {
          id: 'd', role: 'assistant',
          parts: [{ type: 'text', text: '用户先问苹果价格。助手回答五块。用户又问香蕉。', metadata: null }],
          annotations: [], createdAt: '2026-07-28T00:00:00Z', finishedAt: null,
          modelId: null, usage: null, translation: null,
        },
        message: null, finishReason: 'unknown',
      }],
      usage: null,
    };
    const provider: ChatStreamProvider = {
      streamText(_messages: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> {
        onChunk(summaryChunk as MessageChunk);
        return Promise.resolve();
      },
    };
    const store = createMemoryCompactStore();
    const deps: ConversationContextToolsDeps = {
      conversationProvider: () => Promise.resolve(conv),
      compactStore: store,
      policy: makeCompactPolicy({ keepRecentTurns: 1, forceRatio: 0.99 }),
      modelContextWindowTokens: 128000,
      compactEngineDeps: { provider, store },
    };
    const tool = toolByName(createConversationContextTools(deps), 'conversation_compact');
    const out = jsonOf(await tool.execute({ additional_prompt: '保留价格' }));
    assert.equal(out['status'], 'completed');
    assert.ok(typeof out['summary_id'] === 'string');
    const keys = Object.keys(out);
    assert.deepEqual(keys, [
      'status', 'summary_id', 'source_message_count',
      'estimated_tokens_before', 'estimated_tokens_after',
    ]);
    const compacts = await store.getCompacts('c1');
    assert.equal(compacts.length, 1);
  });
});

// builtin_session_tools.test.ts — session_* 历史会话工具组(D-058 TDD)
//
// Android 基准: app/feature/tools/ConversationHistoryTools.kt 全文
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createConversationHistoryTools } from '../main/ets/chat/builtin_session_tools.ts';
import type { ConversationHistoryToolsDeps } from '../main/ets/chat/builtin_session_tools.ts';
import { createMemoryConversationRepository } from '../main/ets/chat/persistence.ts';
import type { ConversationRepository } from '../main/ets/chat/persistence.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeUserMessage, makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool } from '../main/ets/chat/message.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { SessionAccessGrantStore } from '../main/ets/chat/session_grant_store.ts';

const textOf = (parts: UIMessagePart[]): string => (parts[0] as UIMessagePartText).text;
const jsonOf = (parts: UIMessagePart[]): Record<string, unknown> => JSON.parse(textOf(parts));
const toolByName = (tools: AgentTool[], name: string): AgentTool =>
  tools.find((t: AgentTool): boolean => t.name === name) as AgentTool;

const toolMsg = (name: string, executed: boolean): UIMessage => ({
  id: `tool-${name}`, role: 'assistant',
  parts: [{
    type: 'tool', toolCallId: `call-${name}`, toolName: name,
    input: '{"q":"x"}',
    output: executed ? [{ type: 'text', text: '工具输出内容', metadata: null }] : [],
    approvalState: { type: 'auto' }, metadata: null,
  } as UIMessagePartTool],
  annotations: [], createdAt: '2026-07-28T00:00:00Z', finishedAt: null,
  modelId: null, usage: null, translation: null,
});

interface Fixture {
  repo: ConversationRepository;
  current: Conversation;
  other: Conversation;
  sameAssistant: Conversation;
}

const seedFixture = async (): Promise<Fixture> => {
  const repo = createMemoryConversationRepository();
  const current: Conversation = makeConversation('cur', [
    toMessageNode(makeUserMessage('当前会话问题')),
  ], { assistantId: 'a1', title: '当前会话' });
  const sameAssistant: Conversation = makeConversation('hist-1', [
    toMessageNode(makeUserMessage('苹果和橙子的区别')),
    toMessageNode(makeAssistantMessage('苹果是红的,橙子是橙的')),
    toMessageNode(toolMsg('conversation_search', true)),
  ], { assistantId: 'a1', title: '水果讨论' });
  const other: Conversation = makeConversation('hist-2', [
    toMessageNode(makeUserMessage('另一个助手的问题')),
  ], { assistantId: 'a2', title: '无关会话' });
  await repo.save(current);
  await repo.save(sameAssistant);
  await repo.save(other);
  return { repo, current, other, sameAssistant };
};

const makeDeps = (fx: Fixture, grantStore?: SessionAccessGrantStore): ConversationHistoryToolsDeps => ({
  repository: fx.repo,
  currentConversationProvider: () => Promise.resolve(fx.current),
  grantStore,
});

describe('session_list(:38-79)', () => {
  it('默认 scope=current_assistant + limit 12;summary JSON 键序逐字', async () => {
    const fx = await seedFixture();
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx)), 'session_list');
    const out = jsonOf(await tool.execute({}));
    assert.equal(out['status'], 'ok');
    assert.equal(out['scope'], 'current_assistant');
    const sessions = out['sessions'] as Record<string, unknown>[];
    // a1 两个会话(当前 + 水果),不含 a2
    assert.equal(sessions.length, 2);
    const keys = Object.keys(sessions[0]);
    assert.deepEqual(keys, [
      'session_id', 'assistant_id', 'title', 'created_at', 'updated_at',
      'updated_date', 'message_nodes', 'is_pinned',
    ]);
    const fruit = sessions.find((s): boolean => s['session_id'] === 'hist-1');
    assert.equal(fruit?.['message_nodes'], 3);
    assert.equal(fruit?.['is_pinned'], false);
    assert.ok(typeof fruit?.['updated_date'] === 'string'
      && String(fruit?.['updated_date']).includes('T'));
  });
  it('scope=all 含其他助手;query 标题过滤;limit coerce 1..50', async () => {
    const fx = await seedFixture();
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx)), 'session_list');
    const all = jsonOf(await tool.execute({ scope: 'all' }));
    assert.equal((all['sessions'] as unknown[]).length, 3);
    const queried = jsonOf(await tool.execute({ scope: 'all', query: '水果' }));
    const sessions = queried['sessions'] as Record<string, unknown>[];
    assert.equal(sessions.length, 1);
    assert.equal(sessions[0]['title'], '水果讨论');
    const capped = jsonOf(await tool.execute({ scope: 'all', limit: 999 }));
    assert.equal((capped['sessions'] as unknown[]).length, 3);
  });
});

describe('session_search(:81-128;FTS→子串扫描 偏差登记)', () => {
  it('命中字段逐字(session_id/title/message_id/node_id/updated_at/snippet)', async () => {
    const fx = await seedFixture();
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx)), 'session_search');
    const out = jsonOf(await tool.execute({ query: '苹果' }));
    assert.equal(out['status'], 'ok');
    const hits = out['hits'] as Record<string, unknown>[];
    assert.equal(hits.length, 2); // user 提问 + assistant 回答
    assert.equal(hits[0]['session_id'], 'hist-1');
    assert.equal(hits[0]['title'], '水果讨论');
    assert.ok(typeof hits[0]['message_id'] === 'string');
    assert.ok(typeof hits[0]['node_id'] === 'string');
    assert.ok(typeof hits[0]['updated_at'] === 'number');
    assert.ok(String(hits[0]['snippet']).includes('苹果'));
  });
  it('scope=current_assistant 排除其他助手;session_ids 过滤;limit 截断', async () => {
    const fx = await seedFixture();
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx)), 'session_search');
    const scoped = jsonOf(await tool.execute({ query: '问题' }));
    const hits = scoped['hits'] as Record<string, unknown>[];
    assert.ok(hits.every((h): boolean => h['session_id'] !== 'hist-2'));
    const filtered = jsonOf(await tool.execute({
      query: '苹果', scope: 'all', session_ids: ['hist-2'],
    }));
    assert.deepEqual(filtered['hits'], []);
    const one = jsonOf(await tool.execute({ query: '苹果', limit: 1 }));
    assert.equal((one['hits'] as unknown[]).length, 1);
  });
});

describe('session_read(:130-174)', () => {
  it('needsApproval + allowsAutoApproval 标志;schema 必填 session_id', async () => {
    const fx = await seedFixture();
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx)), 'session_read');
    assert.equal(tool.needsApproval, true);
    assert.equal(tool.allowsAutoApproval, true);
    const schema = tool.parameters();
    assert.deepEqual(schema?.required, ['session_id']);
  });
  it('未知 session → not_found 文案;未知 grant → grant_denied 文案', async () => {
    const fx = await seedFixture();
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx)), 'session_read');
    const nf = jsonOf(await tool.execute({ session_id: 'nope' }));
    assert.deepEqual(nf, { status: 'failed', code: 'not_found', error: 'Unknown session_id: nope' });
    const gd = jsonOf(await tool.execute({ session_id: 'hist-1', grant_id: 'nope' }));
    assert.deepEqual(gd, {
      status: 'failed', code: 'grant_denied',
      error: 'Unknown or expired session access grant.',
    });
  });
  it('transcript 行格式 [role id] text;include_tools 展开 input/output_tail', async () => {
    const fx = await seedFixture();
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx)), 'session_read');
    const out = jsonOf(await tool.execute({ session_id: 'hist-1' }));
    assert.equal(out['status'], 'ok');
    assert.equal(out['message_count'], 3);
    assert.equal(out['max_chars'], 20000);
    assert.equal(out['truncated'], false);
    const transcript = String(out['transcript']);
    assert.ok(transcript.includes('[user '));
    assert.ok(transcript.includes('苹果和橙子的区别'));
    assert.ok(transcript.includes('[tool:conversation_search executed=true]'));
    const withTools = jsonOf(await tool.execute({ session_id: 'hist-1', include_tools: true }));
    const t2 = String(withTools['transcript']);
    assert.ok(t2.includes('[tool:conversation_search input={"q":"x"} output_tail=工具输出内容]'));
  });
  it('max_chars 截断双态(Android 长度启发逐字):标记态 + 填满态', async () => {
    const fx = await seedFixture();
    // 标记态:内容触发 ...[truncated]... 但 trim 后 < maxChars →
    //   truncated=false(Android truncated=transcript.length>=maxChars 同语义)
    const longConv: Conversation = makeConversation('hist-long', [
      toMessageNode(makeUserMessage('短问题')),
      toMessageNode(makeAssistantMessage(`长回答:${'一'.repeat(800)}`)),
      toMessageNode(makeAssistantMessage(`第二条:${'二'.repeat(800)}`)),
    ], { assistantId: 'a1', title: '长会话' });
    await fx.repo.save(longConv);
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx)), 'session_read');
    const marked = jsonOf(await tool.execute({ session_id: 'hist-long', max_chars: 1000 }));
    assert.ok(String(marked['transcript']).includes('...[truncated]...'));
    assert.equal(marked['truncated'], false);
    // 填满态:builder > maxChars 后被 take(maxChars) 截齐 → truncated=true
    const fillConv: Conversation = makeConversation('hist-fill', [
      toMessageNode(makeAssistantMessage(`填满:${'x'.repeat(941)}`)),
      toMessageNode(makeAssistantMessage('尾巴')),
    ], { assistantId: 'a1', title: '填满会话' });
    await fx.repo.save(fillConv);
    const filled = jsonOf(await tool.execute({ session_id: 'hist-fill', max_chars: 1000 }));
    assert.equal(String(filled['transcript']).length, 1000);
    assert.equal(filled['truncated'], true);
    const one = jsonOf(await tool.execute({ session_id: 'hist-1', max_messages: 1 }));
    assert.equal(one['message_count'], 1);
  });
  it('有效 grant → 放行且 recordUse 累计', async () => {
    const fx = await seedFixture();
    const store = new SessionAccessGrantStore();
    const grant = store.create(['hist-1'], 60000, '测试', 'cur');
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx, store)), 'session_read');
    const out = jsonOf(await tool.execute({ session_id: 'hist-1', grant_id: grant.grantId }));
    assert.equal(out['status'], 'ok');
    const used = store.get(grant.grantId)?.usedChars ?? 0;
    assert.ok(used > 0);
  });
});

describe('session_expand(:176-259)', () => {
  it('按消息 id 定位 → 半径重铺平;messages 字段逐字', async () => {
    const fx = await seedFixture();
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx)), 'session_expand');
    const midId = fx.sameAssistant.messageNodes[1].messages[0].id;
    const out = jsonOf(await tool.execute({
      session_id: 'hist-1', source_id: midId, radius: 1,
    }));
    assert.equal(out['status'], 'ok');
    const messages = out['messages'] as Record<string, unknown>[];
    assert.equal(messages.length, 3);
    assert.deepEqual(Object.keys(messages[0]), [
      'node_id', 'node_index', 'message_index', 'message_id', 'role', 'text',
    ]);
    assert.equal(messages[1]['message_id'], midId);
    assert.equal(messages[0]['node_index'], 0);
    assert.equal(messages[1]['role'], 'assistant');
    assert.equal(out['truncated'], false);
  });
  it('按节点 id 直中;未知 source → not_found 文案', async () => {
    const fx = await seedFixture();
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx)), 'session_expand');
    const nodeId = fx.sameAssistant.messageNodes[0].id;
    const out = jsonOf(await tool.execute({ session_id: 'hist-1', source_id: nodeId, radius: 0 }));
    const messages = out['messages'] as Record<string, unknown>[];
    assert.equal(messages.length, 1);
    assert.equal(messages[0]['node_id'], nodeId);
    const nf = jsonOf(await tool.execute({ session_id: 'hist-1', source_id: 'nope' }));
    assert.deepEqual(nf, {
      status: 'failed', code: 'not_found', error: 'source_id not found in session.',
    });
  });
  it('radius coerce 0..8;include_tools=false 时 tool 行退化 executed 形式', async () => {
    const fx = await seedFixture();
    const tool = toolByName(createConversationHistoryTools(makeDeps(fx)), 'session_expand');
    const toolMsgId = fx.sameAssistant.messageNodes[2].messages[0].id;
    const out = jsonOf(await tool.execute({
      session_id: 'hist-1', source_id: toolMsgId, radius: 99,
    }));
    const messages = out['messages'] as Record<string, unknown>[];
    assert.equal(messages.length, 3); // 全会话(coerce 8 覆盖)
    assert.ok(String(messages[2]['text']).includes('[tool:conversation_search executed=true]'));
  });
});

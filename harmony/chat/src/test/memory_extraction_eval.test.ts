import assert from 'node:assert/strict';
import test from 'node:test';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessage } from '../main/ets/chat/message.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import { makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import type { MemoryCandidate, MemoryRecord, MemoryEvent } from '../main/ets/chat/memory_models.ts';
import { applyMemoryExtractionActions } from '../main/ets/chat/memory_extraction_actions.ts';
import { runMemoryExtraction, resetMemoryExtractionDebounce } from '../main/ets/chat/memory_extractor.ts';
import type { MemoryExtractionDeps } from '../main/ets/chat/memory_extractor.ts';
const NOW = new Date(2026, 9, 3, 12).getTime();
const msg = (role: 'user' | 'assistant', text: string, id: string): UIMessage =>
  makeUIMessage(role, [{ type: 'text', text, metadata: null }], { id, createdAt: '2026-10-01T12:00:00' });
const old = () => makeMemoryRecord({ id: 1, content: '用户喜欢喝美式咖啡', scope: 'long_term', kind: 'user', updatedAt: 100 });
interface OutputCandidate { content: string; action?: string; evidence?: string; source_message_id?: string;
  update_memory_id?: number; scope?: string; kind?: string; confidence?: number; sensitive?: boolean; expires_on?: string; expires_in_days?: number }
const base = (content: string, over: Partial<OutputCandidate> = {}): OutputCandidate => ({
  content, action: 'add', source_message_id: 'u', scope: 'long_term', kind: 'user', confidence: .95, ...over,
});
const evaluate = async (userText: string, outputs: OutputCandidate[], opts: {
  assistantText?: string; records?: MemoryRecord[]; contaminate?: string; editDuringModel?: boolean;
  sourceEditDuringModel?: boolean; disableDuringModel?: boolean; previousUser?: string;
  canApply?: () => Promise<boolean>;
} = {}) => {
  resetMemoryExtractionDebounce();
  let records: MemoryRecord[] = opts.records ?? [];
  const candidates: MemoryCandidate[] = [];
  const events: MemoryEvent[] = [];
  const messages: UIMessage[] = [];
  if (opts.previousUser) messages.push(msg('user', opts.previousUser, 'prior'));
  if (opts.assistantText) messages.push(msg('assistant', opts.assistantText, 'a'));
  messages.push(msg('user', userText, 'u'));
  if (opts.contaminate) messages.push(makeUIMessage('assistant', [{ type: 'tool', toolCallId: 't', toolName: opts.contaminate,
    input: '{}', output: [], approvalState: { type: 'approved' }, metadata: null }], { id: 't' }));
  const conversation = makeConversation('eval', messages.map(toMessageNode));
  let prompt = '';
  const deps: MemoryExtractionDeps = {
    worker: { enabled: true, extractionEnabled: true, maxDailyRuns: 8 }, locale: 'zh-CN',
    canApply: opts.canApply,
    now: () => NOW, resolveWorkerModel: async () => ({ kind: 'ok', modelId: 'worker' }),
    generateText: async (text) => {
      prompt = text;
      if (opts.editDuringModel) records = records.map(r => ({ ...r, content: '并发编辑内容', updatedAt: 101 }));
      if (opts.disableDuringModel) deps.worker.enabled = false;
      if (opts.sourceEditDuringModel) conversation.messageNodes = [toMessageNode(msg('user', '用户已修改来源发言', 'u'))];
      return JSON.stringify({ candidates: outputs });
    },
    getAllActiveRecords: async () => records.filter(r => !r.archived),
    addCandidates: async list => { candidates.push(...list); }, addCandidate: async candidate => { candidates.push(candidate); },
    addMemory: async () => { throw new Error('Atomic callback must handle writes'); },
    applyActions: async (actions, now) => { const applied = applyMemoryExtractionActions(records, actions, now); records = applied.records; return applied; },
    addEvent: async event => { events.push(event); }, countEventsSince: async () => 0,
  };
  await runMemoryExtraction(conversation, deps);
  return { records, candidates, events, prompt };
};
test('中文01 逐字偏好与出处', async () => {
  const result = await evaluate('我喜欢喝美式咖啡', [base('用户喜欢喝美式咖啡', { evidence: '我喜欢喝美式咖啡' })]);
  assert.equal(result.records.length, 1); assert.equal(result.records[0].evidence, '我喜欢喝美式咖啡');
  assert.deepEqual(result.records[0].sourceMessageIds, ['u']);
});
test('中文02 指代已确认的助手方案', async () => {
  const result = await evaluate('就用第二个方案', [base('用户选用 SQLite', { evidence: '就用第二个方案' })], { assistantText: '第二个方案使用 SQLite' });
  assert.equal(result.records[0]?.content, '用户选用 SQLite');
});
test('中文03 简短应答不是充分证据', async () => {
  assert.equal((await evaluate('好', [base('用户选用 SQLite', { evidence: '好' })], { assistantText: '推荐 SQLite' })).records.length, 0);
});
test('中文04 助手不能冒充用户证据', async () => {
  assert.equal((await evaluate('继续解释一下', [base('推荐 SQLite', { evidence: '推荐 SQLite', source_message_id: 'a' })], { assistantText: '推荐 SQLite' })).records.length, 0);
});
test('中文05 虚构 Latin 事实拒绝', async () => {
  assert.equal((await evaluate('我平时喜欢喝咖啡', [base('用户喜欢 Espresso', { evidence: '我平时喜欢喝咖啡' })])).records.length, 0);
});
test('中文06 原文未出现的证据拒绝', async () => {
  const result = await evaluate('我平时喜欢喝咖啡', [base('我平时喜欢吃蛋糕')]);
  assert.equal(result.records.length, 0); assert.match(result.candidates[0].reason, /unverified_user_evidence/);
});
test('中文07 明天换算依据发言日期，保留到指定日结束', async () => {
  const result = await evaluate('明天要去东京', [base('明天要去东京', { scope: 'short_term', kind: 'project', expires_on: '2026-10-05' })]);
  assert.equal(result.records[0]?.content, '2026-10-02要去东京');
  assert.equal(result.records[0]?.expiresAt, new Date(2026, 9, 6).getTime());
});
test('中文08 过期计划拒绝', async () => {
  assert.equal((await evaluate('明天要去东京', [base('明天要去东京', { expires_on: '2026-10-02' })])).records.length, 0);
});
test('中文09 日期非法不会变成永久事实', async () => {
  assert.equal((await evaluate('我打算去东京旅行', [base('我打算去东京旅行', { expires_on: '2026-02-30' })])).records.length, 0);
});
test('中文10 update 归档旧版并保留 supersedes', async () => {
  const result = await evaluate('以后改喝拿铁咖啡', [base('用户以后改喝拿铁咖啡', { action: 'update', update_memory_id: 1, evidence: '以后改喝拿铁咖啡' })], { records: [old()] });
  assert.equal(result.records.length, 2); assert.equal(result.records[0].archived, true);
  assert.deepEqual(result.records[1].supersedesIds, [1]);
});
test('中文11 invalidate 归档而不抹掉历史', async () => {
  const result = await evaluate('我现在不再喜欢咖啡', [base('我现在不再喜欢咖啡', { action: 'invalidate', update_memory_id: 1 })], { records: [old()] });
  assert.equal(result.records[0].archived, true); assert.equal(result.records[0].content, old().content);
});
test('中文12 confirm 允许省略 add 字段并强化', async () => {
  const result = await evaluate('我仍然喜欢喝美式咖啡', [{ content: old().content, evidence: '我仍然喜欢喝美式咖啡', source_message_id: 'u', action: 'confirm', update_memory_id: 1 }], { records: [old()] });
  assert.equal(result.records[0].reinforcementCount, 1);
});
test('中文13 noop 没有写入', async () => {
  assert.equal((await evaluate('我仍然喜欢喝美式咖啡', [base('我仍然喜欢喝美式咖啡', { action: 'noop' })])).records.length, 0);
});
test('中文14 未知 delete 动作拒绝而非降级 add', async () => {
  assert.equal((await evaluate('我仍然喜欢喝美式咖啡', [base('我仍然喜欢喝美式咖啡', { action: 'delete' })])).records.length, 0);
});
test('中文15 未展示 target 拒绝', async () => {
  assert.equal((await evaluate('我已经不喜欢咖啡', [base('我已经不喜欢咖啡', { action: 'invalidate', update_memory_id: 999 })])).records.length, 0);
});
test('中文16 CAS 冲突保留并发编辑', async () => {
  const result = await evaluate('我已经不喜欢咖啡', [base('我已经不喜欢咖啡', { action: 'update', update_memory_id: 1 })], { records: [old()], editDuringModel: true });
  assert.equal(result.records.length, 1); assert.equal(result.records[0].content, '并发编辑内容'); assert.equal(result.records[0].archived, false);
});
test('中文17 敏感原文和显式 sensitive 均拒绝', async () => {
  assert.equal((await evaluate('我的银行卡号是123456', [base('用户账户已配置', { evidence: '我的银行卡号是123456' })])).records.length, 0);
  assert.equal((await evaluate('我平时喜欢喝美式咖啡', [base('我平时喜欢喝美式咖啡', { sensitive: true })])).records.length, 0);
});
test('中文18 网页与 MCP 污染门保留', async () => {
  for (const tool of ['search_web', 'scrape_web', 'mcp__search', 'mcp_call_tool']) {
    const result = await evaluate('我平时喜欢喝美式咖啡', [base('我平时喜欢喝美式咖啡')], { contaminate: tool });
    assert.equal(result.prompt, ''); assert.equal(result.records.length, 0);
  }
});
test('中文19 坏项不阻塞合法同批项', async () => {
  const result = await evaluate('我喜欢咖啡。我平时喜欢阅读科幻小说', [base('虚构其他用户原文'), base('我平时喜欢阅读科幻小说')]);
  assert.equal(result.records.length, 1); assert.equal(result.candidates.length, 1);
});
test('中文20 模型期间关闭开关或编辑来源取消写入', async () => {
  for (const opts of [{ disableDuringModel: true }, { sourceEditDuringModel: true }]) {
    assert.equal((await evaluate('我平时喜欢阅读科幻小说', [base('我平时喜欢阅读科幻小说')], opts)).records.length, 0);
  }
});
test('中文21 当前消息再次说明旧事实强化，不重写旧消息证据', async () => {
  const result = await evaluate(old().content, [base(old().content)], { records: [old()], previousUser: '旧发言不可反复计数' });
  assert.equal(result.records.length, 1); assert.equal(result.records[0].reinforcementCount, 1);
  assert.match(result.prompt, /source_message_ids when relevant: u\./);
});

test('中文22 延后提炼的相对行程没有显式有效期时也不能永久入库', async () => {
  const result = await evaluate('明天要去东京', [base('明天要去东京', { scope: 'short_term', kind: 'project' })]);
  assert.equal(result.records.length, 0); assert.match(result.candidates[0].reason, /expired_write/);
});

test('中文23 实际持久来源或权限变化由 live gate 阻止全部写入', async () => {
  let checks = 0;
  const result = await evaluate('我平时喜欢喝美式咖啡', [base('我平时喜欢喝美式咖啡'), base('不是原文')], {
    canApply: async () => { checks++; return false; },
  });
  assert.equal(checks, 1);
  assert.equal(result.records.length, 0);
  assert.equal(result.candidates.length, 0);
  assert.equal(result.events.at(-1)?.type, 'extraction_skipped');
});
test('中文24 live gate 允许时正常提交', async () => {
  let checks = 0;
  const result = await evaluate('我平时喜欢喝美式咖啡', [base('我平时喜欢喝美式咖啡')], {
    canApply: async () => { checks++; return true; },
  });
  assert.equal(checks, 1); assert.equal(result.records.length, 1);
});

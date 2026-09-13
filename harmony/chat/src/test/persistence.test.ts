// Chat 持久化规格测试 — RDB schema + 行映射 + Repository Port
//
// Android 基准(DATA_SCHEMA_MATRIX T01/T02):
//   ConversationEntity.kt: id/assistant_id/title/nodes(废弃)/create_at/update_at/suggestions/
//     is_pinned/auto_approve_tools;索引 (assistant_id,is_pinned,update_at)+(is_pinned,update_at)
//   MessageNodeEntity.kt: id/conversation_id(FK CASCADE)/node_index/messages(json)/select_index
//   ConversationDAO: 列表一律 ORDER BY is_pinned DESC, update_at DESC;
//     搜索 title LIKE '%x%' 同序(SQLite LIKE ASCII 大小写不敏感)
//
// 决定(D-013):
//   - Harmony conversation 表丢弃废弃 nodes 列(矩阵注明"迁移时可丢弃")
//   - 表名 conversation/message_node(Android Room 实为 conversationentity,矩阵已定名;
//     未来 Android DB 导入需表名映射,登记在案)
//   - create_at/update_at: epoch ms INTEGER ↔ 领域层 ISO 字符串互转

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  CHAT_SCHEMA_SQL,
  conversationToRow, rowToConversation,
  messageNodeToRow, rowToMessageNode,
  createMemoryConversationRepository,
} from '../main/ets/chat/persistence.ts';
import { makeConversation, toMessageNode, makeMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUserMessage, makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';

const FIXED_NOW = '2099-01-01T00:00:00.000Z';
const fixedNow = (): string => FIXED_NOW;

const convWithNodes = (): Conversation => {
  const user = makeUserMessage('问题');
  user.id = 'u1';
  user.createdAt = '2026-07-28T10:00:00.000Z';
  const asst = makeAssistantMessage('回答');
  asst.id = 'a1';
  asst.createdAt = '2026-07-28T10:01:00.000Z';
  return makeConversation('conv1', [toMessageNode(user), toMessageNode(asst)], {
    title: '标题',
    createAt: '2026-07-28T09:00:00.000Z',
    updateAt: '2026-07-28T10:01:00.000Z',
    chatSuggestions: ['s1', 's2'],
    isPinned: true,
    autoApproveToolCalls: true,
  });
};

// ===== schema =====

test('schema: conversation + message_node 表与索引;无废弃 nodes 列', () => {
  assert.match(CHAT_SCHEMA_SQL, /CREATE TABLE IF NOT EXISTS conversation \(/);
  assert.match(CHAT_SCHEMA_SQL, /CREATE TABLE IF NOT EXISTS message_node \(/);
  assert.match(CHAT_SCHEMA_SQL, /REFERENCES conversation\(id\) ON DELETE CASCADE/);
  assert.match(CHAT_SCHEMA_SQL, /CREATE INDEX IF NOT EXISTS idx_conversation_assistant_pinned_updated/);
  assert.match(CHAT_SCHEMA_SQL, /CREATE INDEX IF NOT EXISTS idx_conversation_pinned_updated/);
  assert.match(CHAT_SCHEMA_SQL, /CREATE INDEX IF NOT EXISTS idx_message_node_conversation/);
  const convTable = CHAT_SCHEMA_SQL.split('CREATE TABLE IF NOT EXISTS message_node')[0];
  assert.ok(!/^\s*nodes\s/m.test(convTable), 'conversation 表不应保留废弃 nodes 列');
});

// ===== 行映射 =====

test('conversationToRow: ISO→epoch ms、bool→int、suggestions JSON', () => {
  const row = conversationToRow(convWithNodes());
  assert.equal(row.id, 'conv1');
  assert.equal(row.assistant_id, '0950e2dc-9bd5-4801-afa3-aa887aa36b4e');
  assert.equal(row.title, '标题');
  assert.equal(row.create_at, Date.parse('2026-07-28T09:00:00.000Z'));
  assert.equal(row.update_at, Date.parse('2026-07-28T10:01:00.000Z'));
  assert.equal(row.suggestions, '["s1","s2"]');
  assert.equal(row.is_pinned, 1);
  assert.equal(row.auto_approve_tools, 1);
});

test('conversation 行 round-trip: row→domain 还原(nodes 由 message_node 行组装)', () => {
  const conv = convWithNodes();
  const row = conversationToRow(conv);
  const nodeRows = conv.messageNodes.map(
    (n, i: number) => messageNodeToRow(row.id, n, i, fixedNow));
  const back = rowToConversation(row, nodeRows, fixedNow);
  assert.deepEqual(back, conv);
});

test('messageNodeToRow: messages blob 为 UIMessage JSON 数组;node_index/select_index 保留', () => {
  const conv = convWithNodes();
  const branch = makeMessageNode([conv.messageNodes[0].messages[0]], 0, 'node-x');
  const row = messageNodeToRow('conv1', branch, 3, fixedNow);
  assert.equal(row.id, 'node-x');
  assert.equal(row.conversation_id, 'conv1');
  assert.equal(row.node_index, 3);
  assert.equal(row.select_index, 0);
  const parsed = JSON.parse(row.messages) as unknown[];
  assert.equal(parsed.length, 1);
  assert.equal((parsed[0] as { role: string }).role, 'user');
});

// ===== Memory Repository(DAO 语义) =====

test('repository: save→getById 完整还原;重复 save 替换 nodes(upsert)', async () => {
  const repo = createMemoryConversationRepository(fixedNow);
  const conv = convWithNodes();
  await repo.save(conv);
  const got = await repo.getById('conv1');
  assert.deepEqual(got, conv);

  // 追加一条消息后重存:nodes 数变为 3
  const extra = makeAssistantMessage('追问回答');
  extra.id = 'a2';
  extra.createdAt = '2026-07-28T10:02:00.000Z';
  const grown: Conversation = {
    ...conv,
    messageNodes: [...conv.messageNodes, toMessageNode(extra)],
    updateAt: '2026-07-28T10:02:00.000Z',
  };
  await repo.save(grown);
  const got2 = await repo.getById('conv1');
  assert.equal(got2?.messageNodes.length, 3);
});

test('repository: list 排序 is_pinned DESC, update_at DESC;assistantId 过滤', async () => {
  const repo = createMemoryConversationRepository(fixedNow);
  const mk = (id: string, pinned: boolean, updateAt: string, assistantId?: string): Conversation =>
    makeConversation(id, [], {
      title: id, isPinned: pinned, updateAt,
      createAt: '2026-07-28T00:00:00.000Z',
      ...(assistantId !== undefined ? { assistantId } : {}),
    });
  await repo.save(mk('c1', false, '2026-07-28T10:00:00.000Z'));
  await repo.save(mk('c2', true, '2026-07-28T08:00:00.000Z'));
  await repo.save(mk('c3', false, '2026-07-28T12:00:00.000Z'));
  await repo.save(mk('c4', false, '2026-07-28T13:00:00.000Z', 'other-assistant'));

  const all = await repo.list();
  assert.deepEqual(all.map((s): string => s.id), ['c2', 'c4', 'c3', 'c1'],
    '无过滤返回全部(Android getAll):pinned 优先,再按 update_at 倒序');
  const ofDefault = await repo.list('0950e2dc-9bd5-4801-afa3-aa887aa36b4e');
  assert.deepEqual(ofDefault.map((s): string => s.id), ['c2', 'c3', 'c1']);
  const ofOther = await repo.list('other-assistant');
  assert.deepEqual(ofOther.map((s): string => s.id), ['c4']);
  // summary 字段(LightConversationEntity 对齐)
  assert.equal(all[0].title, 'c2');
  assert.equal(all[0].isPinned, true);
});

test('repository: 同 update_at 的列表顺序按 id 升序稳定(分页边界不重复/遗漏)', async () => {
  const repo = createMemoryConversationRepository(fixedNow);
  const mk = (id: string, updateAt: string): Conversation =>
    makeConversation(id, [], { title: id, updateAt, createAt: '2026-07-28T00:00:00.000Z' });
  await repo.save(mk('bbb', '2026-07-28T10:00:00.000Z'));
  await repo.save(mk('aaa', '2026-07-28T10:00:00.000Z'));
  await repo.save(mk('ccc', '2026-07-28T10:00:00.000Z'));
  const first = await repo.list();
  const second = await repo.list();
  assert.deepEqual(first.map((s): string => s.id), ['aaa', 'bbb', 'ccc'],
    '同毫秒更新按 id ASC 兜底');
  assert.deepEqual(second.map((s): string => s.id), ['aaa', 'bbb', 'ccc'],
    '两次读取顺序一致(不受 Map 插入序影响)');
});

test('repository: listRecent limit;search 标题子串(ASCII 大小写不敏感)同序', async () => {
  const repo = createMemoryConversationRepository(fixedNow);
  const mk = (id: string, title: string, updateAt: string): Conversation =>
    makeConversation(id, [], { title, updateAt, createAt: '2026-07-28T00:00:00.000Z' });
  await repo.save(mk('c1', 'Alpha 发布', '2026-07-28T10:00:00.000Z'));
  await repo.save(mk('c2', 'beta 计划', '2026-07-28T11:00:00.000Z'));
  await repo.save(mk('c3', 'ALPHA 回顾', '2026-07-28T12:00:00.000Z'));

  const recent = await repo.listRecent(2);
  assert.deepEqual(recent.map((s): string => s.id), ['c3', 'c2']);
  const hits = await repo.search('alpha');
  assert.deepEqual(hits.map((s): string => s.id), ['c3', 'c1'], 'ASCII 大小写不敏感 + update_at DESC');
});

test('repository: delete 级联移除 nodes;getSummaryById;不存在返回 null', async () => {
  const repo = createMemoryConversationRepository(fixedNow);
  await repo.save(convWithNodes());
  const summary = await repo.getSummaryById('conv1');
  assert.equal(summary?.title, '标题');
  assert.equal(summary?.isPinned, true);

  await repo.delete('conv1');
  assert.equal(await repo.getById('conv1'), null);
  assert.equal(await repo.getSummaryById('conv1'), null);
  assert.deepEqual(await repo.list(), []);
});

test('repository: 分支节点(messages 数组 + selectIndex)持久化不丢分支语义', async () => {
  const repo = createMemoryConversationRepository(fixedNow);
  const v1 = makeAssistantMessage('版本1');
  v1.id = 'v1';
  v1.createdAt = '2026-07-28T10:00:00.000Z';
  const v2 = makeAssistantMessage('版本2');
  v2.id = 'v2';
  v2.createdAt = '2026-07-28T10:01:00.000Z';
  const node = makeMessageNode([v1, v2], 1, 'branch-node');
  const conv = makeConversation('cb', [node], {
    createAt: '2026-07-28T09:00:00.000Z', updateAt: '2026-07-28T10:01:00.000Z',
  });
  await repo.save(conv);
  const got = await repo.getById('cb');
  assert.equal(got?.messageNodes.length, 1);
  assert.equal(got?.messageNodes[0].messages.length, 2, '分支 alternatives 完整');
  assert.equal(got?.messageNodes[0].selectIndex, 1);
  assert.deepEqual(got?.messageNodes[0].messages[1], v2);
});

// R03:批量恢复(备份还原)在内存实现同样先整体 staging 再落库，且保留分支/元数据
test('repository: restoreConversations 批量写入并保留分支/元数据', async () => {
  const repo = createMemoryConversationRepository(fixedNow);
  const a = convWithNodes();
  const b = convWithNodes();
  b.id = 'conv2';
  b.messageNodes = [
    a.messageNodes[0],
    { id: 'bn', selectIndex: 1, messages: [a.messageNodes[1].messages[0], a.messageNodes[0].messages[0]] },
  ];
  await repo.restoreConversations([a, b]);
  const gotA = await repo.getById('conv1');
  const gotB = await repo.getById('conv2');
  assert.deepEqual(gotA, a);
  assert.equal(gotB?.messageNodes.length, 2);
  assert.equal(gotB?.messageNodes[1].selectIndex, 1);
  assert.equal(gotB?.isPinned, true, '元数据(isPinned)一并还原');
  // 覆盖更新:同 id 再次批量恢复替换原数据
  await repo.restoreConversations([makeConversation('conv1', [], { title: '覆盖' })]);
  assert.equal((await repo.getById('conv1'))?.title, '覆盖');
});

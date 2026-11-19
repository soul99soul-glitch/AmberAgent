import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { UIMessage } from '../main/ets/chat/message.ts';
import {
  createEmptyEpisodeLedger, parseEpisodeLedger, sealEpisodeIfEligible,
  activeEpisodeMessages, formatRecentEpisodesIndex, estimateEpisodeIndexTokens,
  searchEpisodeMessages, updateEpisodeSummary, projectEpisodeLedger, EPISODE_IDLE_TIMEOUT_MS,
} from '../main/ets/chat/episode_models.ts';
import { makeMessageNode } from '../main/ets/chat/conversation.ts';
import { createEpisodeRecallTool } from '../main/ets/chat/builtin_episode_recall_tool.ts';

const msg = (id: string, role: 'user' | 'assistant' | 'system', text: string, createdAtMs: number): UIMessage => ({
  id,
  role,
  parts: [{ type: 'text', text, metadata: null }],
  createdAt: new Date(createdAtMs).toISOString(),
  finishedAt: new Date(createdAtMs + 1000).toISOString(),
  modelId: null,
  usage: null,
  annotations: [],
});

test('a sealed boundary follows the selected message branch, and recall searches beyond the excerpt prefix', () => {
  const question = msg('u1', 'user', '旧问题', 1000);
  const original = msg('a1', 'assistant', '旧回答', 2000);
  const selected = msg('a2', 'assistant', '例'.repeat(240) + '尾部关键词', 2000);
  const next = msg('u2', 'user', '新问题', 3000);
  const { ledger } = sealEpisodeIfEligible(createEmptyEpisodeLedger('c1'), [question, original], 'manual_new_topic', 2500);
  const projected = projectEpisodeLedger(ledger, [
    makeMessageNode([question]), makeMessageNode([original, selected], 1), makeMessageNode([next]),
  ]);
  assert.deepEqual(activeEpisodeMessages([question, selected, next], projected).map(m => m.id), ['u2']);
  assert.match(searchEpisodeMessages(projected, [question, selected, next], undefined, '尾部关键词')[0].excerpts[0], /尾部关键词/);
  assert.deepEqual(parseEpisodeLedger(JSON.stringify(ledger), 'c2').episodes, []);
  assert.deepEqual(projectEpisodeLedger(ledger, [makeMessageNode([next])]).episodes, []);
});

test('sealEpisodeIfEligible: manual_new_topic is idempotent on empty boundary', () => {
  const m1 = msg('u1', 'user', '先聊一下鸿蒙顶栏布局', 1000);
  const m2 = msg('a1', 'assistant', '可以采用 54vp 窄渐进模糊带。', 2000);
  const empty = createEmptyEpisodeLedger('c1');

  const first = sealEpisodeIfEligible(empty, [m1, m2], 'manual_new_topic', 3000);
  assert.ok(first.sealed !== null);
  assert.equal(first.sealed?.id, 'ep_0001');
  assert.equal(first.sealed?.afterMessageId, 'a1');
  assert.equal(first.ledger.episodes.length, 1);

  // 空边界重复点「✦ 新话题」必须幂等返回，不重复插分割线
  const second = sealEpisodeIfEligible(first.ledger, [m1, m2], 'manual_new_topic', 4000);
  assert.equal(second.sealed, null);
  assert.equal(second.ledger.episodes.length, 1);
});

test('sealEpisodeIfEligible: idle_timeout enforces 3h (10800s) threshold and slices active context', () => {
  const sys = msg('s0', 'system', 'system rule', 500);
  const u1 = msg('u1', 'user', '第一段话题问题', 1000);
  const a1 = msg('a1', 'assistant', '第一段话题回答', 2000);
  const u2 = msg('u2', 'user', '三小时后的新问题', 2000 + EPISODE_IDLE_TIMEOUT_MS + 5000);

  const ledger0 = createEmptyEpisodeLedger('c1');
  // 未满 3h 不分段
  const tooEarly = sealEpisodeIfEligible(ledger0, [sys, u1, a1], 'idle_timeout', 2000 + EPISODE_IDLE_TIMEOUT_MS - 100);
  assert.equal(tooEarly.sealed, null);

  // 满 3h 自动封存前序段
  const sealed = sealEpisodeIfEligible(ledger0, [sys, u1, a1], 'idle_timeout', u2.createdAt ? Date.parse(u2.createdAt) : 0);
  assert.ok(sealed.sealed !== null);
  assert.equal(sealed.sealed?.reason, 'idle_timeout');

  // 上下文仅装载 system + 当前活跃段消息
  const active = activeEpisodeMessages([sys, u1, a1, u2], sealed.ledger);
  assert.deepEqual(active.map((m) => m.id), ['s0', 'u2']);
});

test('formatRecentEpisodesIndex caps at 8 entries and <= 300 tokens, and episode_recall searches archives', async () => {
  let ledger = createEmptyEpisodeLedger('c1');
  const messages: UIMessage[] = [];
  for (let i = 1; i <= 12; i++) {
    const u = msg(`u${i}`, 'user', `第 ${i} 个专题讨论：关于鸿蒙分布式能力与架构演进的第 ${i} 轮验证`, i * 10000);
    const a = msg(`a${i}`, 'assistant', `第 ${i} 个专题的结论已经沉淀在文档中，关键词 KEY_${i}`, i * 10000 + 2000);
    messages.push(u, a);
    const res = sealEpisodeIfEligible(ledger, messages, 'manual_new_topic', i * 10000 + 5000);
    ledger = res.ledger;
  }
  assert.equal(ledger.episodes.length, 12);
  ledger = updateEpisodeSummary(ledger, 'ep_0012', '十二轮收口总结', '完成分布式架构与关键路径收敛');
  assert.equal(ledger.episodes[11].refined, true);

  const parsed = parseEpisodeLedger(JSON.stringify(ledger), 'c1');
  assert.equal(parsed.episodes.length, 12);

  const index = formatRecentEpisodesIndex(parsed, 8, 300);
  assert.ok(index.includes('ep_0012'));
  assert.ok(estimateEpisodeIndexTokens(index) <= 300);

  const recall = createEpisodeRecallTool({
    loadLedger: async () => parsed,
    loadMessages: async () => messages,
  });
  const out = await recall.execute({ query: 'KEY_3', limit: 2 });
  assert.equal(out.length, 1);
  assert.equal(out[0].type, 'text');
  const payload = JSON.parse((out[0] as { type: 'text'; text: string }).text);
  assert.equal(payload.hits.length, 1);
  assert.equal(payload.hits[0].episodeId, 'ep_0003');
  assert.ok(searchEpisodeMessages(parsed, messages, 'ep_0001').length === 1);
});

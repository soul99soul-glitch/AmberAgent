// memory_summary 测试(D-093)
// 锚点:SettingAgentMemoryPage.kt:793-855(MemorySummarySection 三组过滤器)
import assert from 'node:assert/strict';
import test from 'node:test';
import type { AssistantMemory } from '../main/ets/chat/builtin_memory_tools.ts';
import { makeAssistantMemory } from '../main/ets/chat/builtin_memory_tools.ts';
import { memorySummaryGroups } from '../main/ets/chat/memory_summary.ts';

const mem = (
  id: number, scope: 'core' | 'short_term' | 'long_term',
  kind: 'user' | 'feedback' | 'project' | 'reference' | 'routine' | 'note',
  opts?: { archived?: boolean; pinned?: boolean; content?: string },
): AssistantMemory =>
  makeAssistantMemory(id, opts?.content ?? `内容 ${id}`, scope, kind,
    null, 1, opts?.pinned ?? false, opts?.archived ?? false);

test('stable:scope=core 任意 kind 入选(:804)', () => {
  const g = memorySummaryGroups(
    [mem(1, 'core', 'note'), mem(2, 'core', 'project')], [], []);
  assert.deepEqual(g.stable.map((m: AssistantMemory): number => m.id), [1, 2]);
});

test('stable:long_term 仅 user/feedback/routine/pinned 入选(:805-808)', () => {
  const g = memorySummaryGroups([], [
    mem(1, 'long_term', 'user'),
    mem(2, 'long_term', 'feedback'),
    mem(3, 'long_term', 'routine'),
    mem(4, 'long_term', 'note'),
    mem(5, 'long_term', 'note', { pinned: true }),
    mem(6, 'long_term', 'project'),
  ], []);
  assert.deepEqual(g.stable.map((m: AssistantMemory): number => m.id), [1, 2, 3, 5]);
});

test('stable:archived/sensitive 排除(:802-803);distinctBy(id) 首次保序(:810)', () => {
  const sensitive = mem(9, 'long_term', 'user', { content: '我的密码是 hunter2' });
  const g = memorySummaryGroups(
    [mem(1, 'core', 'note'), mem(2, 'core', 'note', { archived: true })],
    [mem(1, 'long_term', 'user'), sensitive],
    []);
  assert.deepEqual(g.stable.map((m: AssistantMemory): number => m.id), [1]);
  assert.equal(g.stable[0].scope, 'core'); // 重复 id 首次(core 侧)保留
});

test('longTermProjects:project/reference(:813-819);currentProjects:short_term project(:821-826)', () => {
  const g = memorySummaryGroups([], [
    mem(1, 'long_term', 'project'),
    mem(2, 'long_term', 'reference'),
    mem(3, 'long_term', 'note'),
    mem(4, 'long_term', 'project', { archived: true }),
  ], [
    mem(5, 'short_term', 'project'),
    mem(6, 'short_term', 'note'),
    mem(7, 'short_term', 'project', { content: '密码 sk-abcdef' }),
  ]);
  assert.deepEqual(g.longTermProjects.map((m: AssistantMemory): number => m.id), [1, 2]);
  assert.deepEqual(g.currentProjects.map((m: AssistantMemory): number => m.id), [5]);
});

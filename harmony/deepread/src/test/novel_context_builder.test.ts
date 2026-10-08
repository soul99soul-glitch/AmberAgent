import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { systemPrompt, MAX_SYSTEM_CHARS } from '../main/ets/novel/context_builder.ts';
import {
  makeNovelProject, makeNovelMaterial, makeNovelChapter, makeNovelMessage,
} from '../main/ets/novel/models.ts';
import type { NovelProject } from '../main/ets/novel/models.ts';

const NOW = 1_000_000;

const withParts = (over: Partial<NovelProject>): NovelProject => ({
  ...makeNovelProject({ name: '小说', now: NOW }),
  ...over,
});

test('includes enabled materials with Chinese label, excludes disabled', () => {
  const p = withParts({
    materials: [
      makeNovelMaterial({ kind: 'character', title: '主角', content: '勇敢', enabled: true, now: NOW }),
      makeNovelMaterial({ kind: 'world', title: '禁地', content: '秘密', enabled: false, now: NOW }),
    ],
  });
  const out = systemPrompt(p, 'write');
  assert.ok(out.includes('# 活资料'));
  assert.ok(out.includes('[人物] 主角'));
  assert.ok(out.includes('勇敢'));
  assert.ok(!out.includes('禁地'), 'disabled material excluded');
});

test('includes only last 3 chapters', () => {
  const chapters = [
    makeNovelChapter({ title: 'C1', content: 'c1', now: NOW }),
    makeNovelChapter({ title: 'C2', content: 'c2', now: NOW }),
    makeNovelChapter({ title: 'C3', content: 'c3', now: NOW }),
    makeNovelChapter({ title: 'C4', content: 'c4', now: NOW }),
  ];
  const out = systemPrompt(withParts({ chapters: chapters }), 'write');
  assert.ok(!out.includes('C1'), 'oldest chapter dropped');
  assert.ok(out.includes('C2') && out.includes('C3') && out.includes('C4'));
});

test('does not duplicate canonical discussion messages into the system prompt', () => {
  const messages = [
    makeNovelMessage({ role: 'user', mode: 'discuss', content: '你好', createdAt: NOW }),
    makeNovelMessage({ role: 'assistant', mode: 'discuss', content: '你好呀', createdAt: NOW }),
  ];
  const out = systemPrompt(withParts({ messages: messages }), 'discuss');
  assert.ok(!out.includes('# 最近对话'));
  assert.ok(!out.includes('你好'));
});

test('author plan, preferences, unresolved foreshadows and confirmed decisions enter ordinary context', () => {
  const project = withParts({});
  project.branchSettings = {
    thisChapterPlan: '本章调查水井', futurePlan: '五章后进入皇城', preferences: '不用第一人称',
    foreshadows: [
      { id: 'f1', title: '井底铜牌', content: '尚不揭晓主人', status: 'open', createdAt: NOW, resolvedAt: null },
      { id: 'f2', title: '旧信封', content: '已经揭晓', status: 'resolved', createdAt: NOW, resolvedAt: NOW },
    ],
    confirmedDecisions: [{ id: 'd1', title: '作者决定', content: '铜牌属于守卫', confirmedAt: NOW }],
  };
  const prompt = systemPrompt(project, 'write', 'whole_chapter');
  assert.ok(prompt.includes('本章调查水井'));
  assert.ok(prompt.includes('不用第一人称'));
  assert.ok(prompt.includes('尚不揭晓主人'));
  assert.ok(prompt.includes('铜牌属于守卫'));
  assert.ok(prompt.includes('五章后进入皇城'));
  assert.ok(!prompt.includes('旧信封'));
  assert.ok(!systemPrompt(project, 'discuss').includes('五章后进入皇城'));
});

test('always materials are never silently cut by the legacy helper character cap', () => {
  const big = 'x'.repeat(60_000);
  const p = withParts({
    materials: [makeNovelMaterial({ kind: 'world', title: 'w', content: big, enabled: true, now: NOW })],
  });
  const out = systemPrompt(p, 'write');
  assert.ok(out.length > MAX_SYSTEM_CHARS);
  assert.ok(out.includes(big), 'actual provider token policy must reject or retain the whole required material');
});

test('author plot projection remains a mandatory context section even when optional prose is too long', () => {
  const project = withParts({ authorPlot: '作者确认的剧情资料',
    chapters: [makeNovelChapter({ title: '长章', content: '正文'.repeat(40_000), now: NOW })] });
  const prompt = systemPrompt(project, 'write');
  assert.ok(prompt.includes('作者确认的剧情资料'));
  assert.ok(!prompt.includes('长章'), 'oversized optional chapter should be omitted whole');
});

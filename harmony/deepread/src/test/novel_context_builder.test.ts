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

test('discuss mode uses discuss instruction', () => {
  const p = systemPrompt(withParts({}), 'discuss');
  assert.ok(p.includes('策划伙伴'));
});

test('write + continuation uses continuation instruction', () => {
  const p = systemPrompt(withParts({}), 'write', 'continuation');
  assert.ok(p.includes('续写'));
});

test('write default granularity uses whole-chapter instruction', () => {
  const p = systemPrompt(withParts({}), 'write');
  assert.ok(p.includes('新一章'));
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

test('includes recent messages with role labels', () => {
  const messages = [
    makeNovelMessage({ role: 'user', mode: 'discuss', content: '你好', createdAt: NOW }),
    makeNovelMessage({ role: 'assistant', mode: 'discuss', content: '你好呀', createdAt: NOW }),
  ];
  const out = systemPrompt(withParts({ messages: messages }), 'discuss');
  assert.ok(out.includes('# 最近对话'));
  assert.ok(out.includes('用户：你好'));
  assert.ok(out.includes('助手：你好呀'));
});

test('whole prompt capped at MAX_SYSTEM_CHARS', () => {
  const big = 'x'.repeat(60_000);
  const p = withParts({
    materials: [makeNovelMaterial({ kind: 'world', title: 'w', content: big, enabled: true, now: NOW })],
  });
  const out = systemPrompt(p, 'write');
  assert.ok(out.length <= MAX_SYSTEM_CHARS);
});

test('sections omitted when empty', () => {
  const out = systemPrompt(withParts({}), 'write');
  assert.ok(!out.includes('# 活资料'));
  assert.ok(!out.includes('# 章节正文'));
  assert.ok(!out.includes('# 最近对话'));
});

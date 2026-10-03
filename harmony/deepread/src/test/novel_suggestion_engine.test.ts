import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  mapSuggestionKind, parseSuggestions, buildSuggestionUserPrompt, collectModelText,
  analyzeChapterSuggestions, MAX_SUGGESTIONS,
} from '../main/ets/novel/suggestion_engine.ts';
import type { NovelModelRunning, NovelModelEvent, NovelModelRequest } from '../main/ets/novel/model_running.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import { makeNovelProject, makeNovelChapter, makeNovelMaterial } from '../main/ets/novel/models.ts';
import { isNovelError } from '../main/ets/novel/error.ts';
import { materialSuggestionChapterDigest } from '../main/ets/novel/material_adoption.ts';

const NOW = 1_000_000;

const request = (userPrompt: string = 'u'): NovelModelRequest => ({
  runId: 'r1',
  projectId: 'p1',
  systemPrompt: 's',
  maxOutputTokens: null,
  modelTarget: { kind: 'global' },
  history: [],
  operation: { kind: 'turn', userPrompt },
  checkpoint: async (): Promise<void> => {},
});

// mock 模型:把预设事件异步推给订阅者
const scriptedModel = (events: NovelModelEvent[]): NovelModelRunning => ({
  async validate(): Promise<void> {},
  start(_req: NovelModelRequest) {
    const subs = new Set<(e: NovelModelEvent) => void>();
    setTimeout(() => {
      subs.forEach(cb => {
        for (let i = 0; i < events.length; i++) cb(events[i]);
      });
    }, 0);
    return {
      subscribe(cb: (e: NovelModelEvent) => void): () => void {
        subs.add(cb);
        return () => { subs.delete(cb); };
      },
    };
  },
  cancel(_runId: string): void {},
});

test('mapSuggestionKind: EN and CN aliases', () => {
  assert.equal(mapSuggestionKind('world'), 'world');
  assert.equal(mapSuggestionKind('世界观'), 'world');
  assert.equal(mapSuggestionKind('角色'), 'character');
  assert.equal(mapSuggestionKind('Character'), 'character');
  assert.equal(mapSuggestionKind('大纲'), 'outline');
  assert.equal(mapSuggestionKind('写作要求'), 'requirement');
  assert.equal(mapSuggestionKind('???'), 'other');
});

test('parseSuggestions: valid envelope', () => {
  const text = '{"suggestions":[{"kind":"character","title":"主角","content":"勇敢"}]}';
  const out = parseSuggestions(text, 'ch1', NOW);
  assert.equal(out.length, 1);
  assert.equal(out[0].kind, 'character');
  assert.equal(out[0].title, '主角');
  assert.equal(out[0].sourceChapterId, 'ch1');
  assert.equal(out[0].status, 'pending');
});

test('parseSuggestions: extracts first { to last } amid prose', () => {
  const text = '好的，这是建议：\n{"suggestions":[{"kind":"world","title":"w","content":"c"}]}\n谢谢';
  const out = parseSuggestions(text, 'ch1', NOW);
  assert.equal(out.length, 1);
});

test('parseSuggestions: empty suggestions', () => {
  assert.deepEqual(parseSuggestions('{"suggestions":[]}', 'ch1', NOW), []);
});

test('parseSuggestions: rejects invalid items instead of claiming no suggestions', () => {
  const text = '{"suggestions":[{"kind":"world","title":"","content":"c"},{"kind":"world","title":"t","content":""}]}';
  assert.throws(() => parseSuggestions(text, 'ch1', NOW), error => isNovelError(error) && error.code === 'invalid_output');
});

test('parseSuggestions: caps at MAX_SUGGESTIONS', () => {
  const items = [];
  for (let i = 0; i < MAX_SUGGESTIONS + 5; i++) {
    items.push(`{"kind":"other","title":"t${i}","content":"c"}`);
  }
  const text = `{"suggestions":[${items.join(',')}]}`;
  const out = parseSuggestions(text, 'ch1', NOW);
  assert.equal(out.length, MAX_SUGGESTIONS);
});

test('parseSuggestions: malformed JSON and wrong schemas are distinct from valid empty results', () => {
  for (const text of ['not json at all', '{broken}', '{}', '{"suggestions":{}}',
    '{"suggestions":[null]}', '{"suggestions":[{"kind":"world","title":1,"content":"c"}]}',
    '{"suggestions":[{"kind":"unknown","title":"t","content":"c"}]}']) {
    assert.throws(() => parseSuggestions(text, 'ch1', NOW), error => isNovelError(error) && error.code === 'invalid_output');
  }
});

test('buildSuggestionUserPrompt: includes project, materials, chapter', () => {
  const project = {
    ...makeNovelProject({ name: '长夜来信', now: NOW }),
    materials: [makeNovelMaterial({ kind: 'character', title: '主角', content: '设定', enabled: true, now: NOW })],
  };
  const chapter = makeNovelChapter({ title: '第一章', content: '正文内容', now: NOW });
  const prompt = buildSuggestionUserPrompt(project, chapter);
  assert.ok(prompt.includes('长夜来信'));
  assert.ok(prompt.includes('[人物] 主角: 设定'));
  assert.ok(prompt.includes('第一章'));
  assert.ok(prompt.includes('正文内容'));
});

test('collectModelText: returns assistant text from snapshot on completed', async () => {
  const model = scriptedModel([
    {
      kind: 'snapshot', messages: [makeAssistantMessage('Hello')], generationActive: true,
      textDeltasLive: true, transport: 'live',
    },
    { kind: 'completed' },
  ]);
  const text = await collectModelText(model, request(), 1000);
  assert.equal(text, 'Hello');
});

test('collectModelText: newest snapshot replaces prior assistant text', async () => {
  const model = scriptedModel([
    {
      kind: 'snapshot', messages: [makeAssistantMessage('abc')], generationActive: true,
      textDeltasLive: true, transport: 'live',
    },
    {
      kind: 'snapshot', messages: [makeAssistantMessage('XYZ')], generationActive: false,
      textDeltasLive: true, transport: 'live',
    },
    { kind: 'completed' },
  ]);
  const text = await collectModelText(model, request(), 1000);
  assert.equal(text, 'XYZ');
});

test('collectModelText: failed rejects', async () => {
  const model = scriptedModel([{ kind: 'failed', message: 'boom' }]);
  await assert.rejects(collectModelText(model, request(), 1000));
});

test('analyzeChapterSuggestions: parses model JSON into suggestions', async () => {
  const model = scriptedModel([
    {
      kind: 'snapshot',
      messages: [makeAssistantMessage(
        '{"suggestions":[{"kind":"character","title":"主角","content":"勇敢"}]}')],
      generationActive: false, textDeltasLive: false, transport: 'unavailable',
    },
    { kind: 'completed' },
  ]);
  const project = makeNovelProject({ name: 'p', now: NOW });
  const chapter = makeNovelChapter({ title: '第一章', content: '正文', now: NOW });
  const out = await analyzeChapterSuggestions(
    model, project, chapter, { kind: 'global' }, NOW, 1000);
  assert.equal(out.length, 1);
  assert.equal(out[0].title, '主角');
  assert.equal(out[0].sourceDigest, materialSuggestionChapterDigest(chapter));
});

test('analyzeChapterSuggestions: model failure remains a provider error', async () => {
  const model = scriptedModel([{ kind: 'failed', message: 'down' }]);
  const project = makeNovelProject({ name: 'p', now: NOW });
  const chapter = makeNovelChapter({ title: '第一章', content: '正文', now: NOW });
  await assert.rejects(analyzeChapterSuggestions(
    model, project, chapter, { kind: 'global' }, NOW, 1000),
  error => isNovelError(error) && error.code === 'provider' && error.message.includes('down'));
});

test('analyzeChapterSuggestions: validates the model and disables authoring tools', async () => {
  const observed: string[] = [];
  const model = scriptedModel([
    { kind: 'snapshot', messages: [makeAssistantMessage('{"suggestions":[]}')],
      generationActive: false, textDeltasLive: false, transport: 'unavailable' },
    { kind: 'completed' },
  ]);
  const originalStart = model.start;
  model.validate = async (): Promise<void> => { observed.push('validate'); };
  model.start = req => {
    observed.push('start');
    assert.equal(req.toolProfile, 'none');
    return originalStart(req);
  };
  const project = makeNovelProject({ name: 'p', now: NOW });
  const chapter = makeNovelChapter({ title: '第一章', content: '正文', now: NOW });
  assert.deepEqual(await analyzeChapterSuggestions(model, project, chapter, { kind: 'global' }, NOW, 1000), []);
  assert.deepEqual(observed, ['validate', 'start']);
});

test('analyzeChapterSuggestions: malformed model output is an output error, not a provider failure', async () => {
  const model = scriptedModel([
    { kind: 'snapshot', messages: [makeAssistantMessage('{"wrong":[]}')],
      generationActive: false, textDeltasLive: false, transport: 'unavailable' },
    { kind: 'completed' },
  ]);
  const project = makeNovelProject({ name: 'p', now: NOW });
  const chapter = makeNovelChapter({ title: '第一章', content: '正文', now: NOW });
  await assert.rejects(analyzeChapterSuggestions(model, project, chapter, { kind: 'global' }, NOW, 1000),
    error => isNovelError(error) && error.code === 'invalid_output');
});

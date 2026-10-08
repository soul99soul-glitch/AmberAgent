import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  SUGGESTION_SYSTEM_PROMPT, mapSuggestionKind, parseSuggestions, collectModelText, analyzeChapterSuggestions, MAX_SUGGESTIONS,
} from '../main/ets/novel/suggestion_engine.ts';
import type { NovelModelRunning, NovelModelEvent, NovelModelRequest } from '../main/ets/novel/model_running.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import { makeNovelProject, makeNovelChapter } from '../main/ets/novel/models.ts';
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

test('parseSuggestions: extracts first { to last } amid prose', () => {
  const text = '好的，这是建议：\n{"suggestions":[{"kind":"world","title":"w","content":"c"}]}\n谢谢';
  const out = parseSuggestions(text, 'ch1', NOW);
  assert.equal(out.length, 1);
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


test('the suggestion prompt example can be copied as a valid material proposal', () => {
  const example = SUGGESTION_SYSTEM_PROMPT.split('\n').find(line => line.startsWith('{"suggestions":['));
  assert.ok(example);
  assert.equal(parseSuggestions(example, 'chapter', NOW).length, 1);
});

test('invalid suggestion output gets one format correction using the original chapter and materials', async () => {
  const requests: NovelModelRequest[] = [];
  const model: NovelModelRunning = {
    async validate() {},
    start(req) {
      requests.push(req);
      return scriptedModel([
        { kind: 'snapshot', messages: [makeAssistantMessage(requests.length === 1
          ? '{"suggestions":[{"kind":"world|character","title":"灯塔","content":"港口的灯塔"}]}'
          : '{"suggestions":[{"kind":"world","title":"灯塔","content":"港口的灯塔"}]}')],
          generationActive: false, textDeltasLive: false, transport: 'live' },
        { kind: 'completed' },
      ]).start(req);
    },
    cancel() {},
  };
  const project = makeNovelProject({ name: '港口', now: NOW });
  const chapter = makeNovelChapter({ title: '第一章', content: '船驶过港口的灯塔。', now: NOW });
  const result = await analyzeChapterSuggestions(model, project, chapter, { kind: 'global' }, NOW);
  assert.equal(requests.length, 2);
  assert.equal(result[0].kind, 'world');
  assert.equal(result[0].sourceDigest, materialSuggestionChapterDigest(chapter));
  assert.notEqual(requests[0].runId, requests[1].runId);
  assert.equal(requests[1].toolProfile, 'none');
  assert.equal(requests[1].operation.kind, 'turn');
  if (requests[1].operation.kind === 'turn') {
    assert.ok(requests[1].operation.userPrompt.includes(chapter.content));
    assert.ok(requests[1].operation.userPrompt.includes('world|character'));
  }
});

test('format correction stops after one attempt, and transport failure never starts a correction', async () => {
  for (const failure of ['format', 'transport']) {
    let calls = 0;
    const model: NovelModelRunning = {
      async validate() {},
      start(req) {
        calls++;
        return scriptedModel(failure === 'format' ? [
          { kind: 'snapshot', messages: [makeAssistantMessage('{broken')],
            generationActive: false, textDeltasLive: false, transport: 'live' },
          { kind: 'completed' },
        ] : [{ kind: 'failed', message: '连接失败' }]).start(req);
      },
      cancel() {},
    };
    await assert.rejects(analyzeChapterSuggestions(model, makeNovelProject({ name: 'test', now: NOW }),
      makeNovelChapter({ title: '一', content: '正文', now: NOW }), { kind: 'global' }, NOW));
    assert.equal(calls, failure === 'format' ? 2 : 1);
  }
});

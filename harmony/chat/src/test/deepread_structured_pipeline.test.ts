import { test } from 'node:test';
import type { TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import type { IncomingHttpHeaders } from 'node:http';
import {
  run, runSection, isComplete, statusOf,
} from '@amber/deepread-domain';
import type {
  DeepReadOutput, HttpClient, HttpResponse, RunManagerDeps, UIMessage,
} from '@amber/deepread-domain';
import { createChatDeepReadAiClient } from '../main/ets/chat/deepread_ai_client.ts';
import { createOpenAIChatApi } from '../main/ets/chat/openai_chat_api.ts';
import { makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';

const MODEL = 'reasoning-only-fixture';
const SOURCE_URL = 'https://source.example/report';
const OVERVIEW = { topic_type: 'product', summary: '来源记录了产品发布、使用方式与反馈，目前仍需进一步核对实际表现；本文按原始资料整理事件和各方观点。', key_entities: ['产品'] };
const NARRATIVE = { timeline: [{ date: '2026-10-03', event: '来源记录产品公布之后开始接受用户反馈的经过。', is_highlight: true }],
  core_points: [{ point: '功能与实际使用之间仍需要更多验证。', supporting: '来源目前只提供首次使用反馈。' }] };
const ANALYSIS = { analysis: { core_dispute: '各方核心分歧在于这些功能是否足以解决真实使用场景的长期问题。',
  perspectives: [{ holder: '用户', viewpoint: '需要更多实际使用数据。' }], implications: '需要继续核对不同用户的反馈和来源材料。', quotes: [] } };
const EXTENDED = { extended_reading: [{ title: '用户提供的来源', url: SOURCE_URL, source: '资料' }], references: [], hero_image_url: '', hero_caption: '' };
const PLAN = { overview_angle: '按原始资料分析产品发布与使用反馈', narrative_slots: ['公布', '反馈'],
  analysis_questions: ['哪些表现还需要验证？'], stakeholders: ['用户'], required_source_ids: ['source'],
  stage_source_ids: { overview: ['source'], narrative: ['source'], analysis: ['source'], extended_reading: ['source'] } };

interface WireBody {
  model: string;
  stream: boolean;
  temperature: number;
  max_output_tokens: number;
  reasoning: { effort: string };
  tools?: Array<{ type: string }>;
  fixture_custom: { kept: boolean };
  input: Array<{ content?: string | Array<{ type: string; text?: string }> }>;
}
interface WireRequest { path: string; method: string; headers: IncomingHttpHeaders; body: WireBody; }
interface Script { text: string; holdOpen?: boolean; }

const event = (name: string, value: object): string => `event: ${name}\ndata: ${JSON.stringify(value)}\n\n`;
const responseSse = (script: Script, index: number): string => {
  const reasoning = event('response.reasoning_summary_text.delta', {
    type: 'response.reasoning_summary_text.delta', item_id: `reasoning-${index}`, delta: '仅供界面的模型思考，不能进入正文。',
  });
  const text = event('response.output_text.delta', {
    type: 'response.output_text.delta', item_id: `message-${index}`, delta: script.text,
  });
  return reasoning + text + (script.holdOpen ? '' : event('response.completed', {
    type: 'response.completed', response: { id: `resp-${index}`, model: MODEL, status: 'completed',
      output: [{ type: 'message', id: `message-${index}`, content: [{ type: 'output_text', text: script.text }] }] },
  }));
};
const normalScripts = (): Script[] => [PLAN, OVERVIEW, NARRATIVE, ANALYSIS, EXTENDED].map(value => ({ text: JSON.stringify(value) }));

// Real local HTTP/SSE boundary; only the external provider's responses are scripted.
// Production API builders, SSE parser, AiClient and RunManager remain in the path.
const fixture = async (t: TestContext, scripts: Script[], controller?: AbortController) => {
  const requests: WireRequest[] = [];
  const rawSnapshots: UIMessage[][] = [];
  const saved: DeepReadOutput[] = [];
  let current: DeepReadOutput | null = null;
  let nonStreamingCalls = 0;
  const server = createServer(async (req, res) => {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(Buffer.from(chunk));
    const index = requests.length;
    requests.push({ path: req.url ?? '', method: req.method ?? '', headers: req.headers,
      body: JSON.parse(Buffer.concat(chunks).toString('utf8')) as WireBody });
    const script = scripts[index];
    if (script === undefined) {
      res.writeHead(500); res.end('Unexpected request'); return;
    }
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
    const bytes = Buffer.from(responseSse(script, index));
    res.write(bytes.subarray(0, 41));
    setImmediate(() => {
      res.write(bytes.subarray(41));
      if (!script.holdOpen) res.end();
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  t.after(async () => {
    server.closeAllConnections();
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  });
  const address = server.address();
  assert.ok(address !== null && typeof address !== 'string');
  const baseUrl = `http://127.0.0.1:${address.port}/v1`;
  const http: HttpClient = {
    fetch: async (): Promise<HttpResponse> => { nonStreamingCalls += 1; throw new Error('Unexpected nonstream request'); },
    fetchStream: async (request, opts): Promise<HttpResponse> => {
      const bridge = new AbortController();
      const abort = (): void => bridge.abort();
      opts.signal?.addEventListener?.('abort', abort);
      if (opts.signal?.aborted) abort();
      try {
        const response = await fetch(request.url, { method: request.method, headers: request.headers,
          body: request.body, signal: bridge.signal });
        assert.ok(response.body !== null);
        const reader = response.body.getReader();
        while (true) {
          const result = await reader.read();
          if (result.done) break;
          const chunk = result.value;
          opts.onChunk(chunk.buffer.slice(chunk.byteOffset, chunk.byteOffset + chunk.byteLength) as ArrayBuffer, false);
          if (opts.shouldStop?.()) { await reader.cancel(); break; }
        }
        opts.onChunk(new ArrayBuffer(0), true);
        opts.onDataEnd?.();
        return { status: response.status, headers: Object.fromEntries(response.headers), body: '' };
      } finally {
        opts.signal?.removeEventListener?.('abort', abort);
      }
    },
  };
  const params = makeTextGenerationParams({
    model: makeChatModel({ modelId: MODEL, abilities: ['reasoning'], tools: ['search', 'image_generation'] }),
    temperature: 0.3, maxTokens: 4096, reasoningLevel: 'high',
    customBody: [{ key: 'fixture_custom', value: { kept: true } }],
  });
  const client = createChatDeepReadAiClient({
    api: createOpenAIChatApi({ http, setting: makeProviderSettingOpenAI({ baseUrl, useResponseApi: true }),
      headers: (): Record<string, string> => ({ Authorization: 'Bearer fixture-key', 'X-Custom': 'kept' }) }),
    params,
    onRawSnapshot: (messages): void => {
      rawSnapshots.push(structuredClone(messages));
      if (controller !== undefined && messages.some(message => message.parts.some(part =>
        part.type === 'text' && part.text === '分析阶段尚未结束的部分文本'))) controller.abort();
    },
  });
  const deps: RunManagerDeps = {
    writerMode: 'structured', model: MODEL, aiClient: client, playbookMarkdown: '', nowIso: () => '2026-10-03',
    prefetcher: {
      collect: async () => [{ sourceId: 'source', url: SOURCE_URL, title: '用户提供的发布资料', source: '用户',
        evidenceText: '原始资料记录了产品发布与首次用户反馈。'.repeat(30), credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [] }],
      cacheSize: () => 0,
    },
    collectRun: (messages, _label, signal, tools) => client.generateText({ model: MODEL, messages, signal,
      tools: tools?.map(tool => ({ ...tool, schema: tool.schema ?? {} })) }),
    repository: {
      get: () => current,
      save: (_id, _title, output): void => { current = structuredClone(output); saved.push(structuredClone(output)); },
      clear: (): void => { current = null; },
    },
  };
  return { deps, requests, rawSnapshots, saved, params, current: (): DeepReadOutput | null => current,
    nonStreamingCalls: (): number => nonStreamingCalls };
};
const prompt = (request: WireRequest): string => request.body.input.map(item =>
  typeof item.content === 'string' ? item.content : (item.content ?? []).map(part => part.text ?? '').join('')).join('\n');
const assertWireConfig = (requests: WireRequest[]): void => {
  for (const request of requests) {
    assert.equal(request.method, 'POST');
    assert.equal(request.path, '/v1/responses');
    assert.equal(request.headers.authorization, 'Bearer fixture-key');
    assert.equal(request.headers['x-custom'], 'kept');
    assert.equal(request.body.model, MODEL);
    assert.equal(request.body.stream, true);
    assert.equal(request.body.temperature, 0.3);
    assert.equal(request.body.max_output_tokens, 4096);
    assert.deepEqual(request.body.fixture_custom, { kept: true });
    assert.equal(request.body.reasoning.effort, 'high');
    assert.deepEqual(request.body.tools ?? [], []);
  }
};

test('RunManager structured reasoning-only model: four real HTTP SSE calls preserve configuration and complete persisted article', async t => {
  const f = await fixture(t, normalScripts());
  const result = await run(f.deps, 'article', '来源产品');
  assert.equal(result.ok, true, result.error ?? '');
  assert.equal(f.requests.length, 4);
  assert.equal(f.nonStreamingCalls(), 0);
  assertWireConfig(f.requests);
  assert.deepEqual(f.params.model.abilities, ['reasoning']);
  assert.deepEqual(f.params.model.tools, ['search', 'image_generation']);
  assert.equal(isComplete(result.output), true);
  assert.equal(result.output.summary, OVERVIEW.summary);
  assert.deepEqual(result.output.timeline?.map(item => ({ date: item.date, event: item.event, is_highlight: item.isHighlight })), NARRATIVE.timeline);
  assert.equal(result.output.analysis.coreDispute, ANALYSIS.analysis.core_dispute);
  assert.equal(result.output.sources?.[0].url, SOURCE_URL);
  assert.ok(prompt(f.requests[3]).includes(JSON.stringify(NARRATIVE.timeline[0].event)), 'Actual analysis HTTP body contains prior timeline event');
  assert.ok(prompt(f.requests[3]).includes('\"timeline\"'), 'Actual analysis HTTP body contains the structured prior timeline');
  assert.ok(prompt(f.requests[3]).includes(OVERVIEW.summary));
  assert.ok(f.rawSnapshots.some(messages => messages.some(message => message.parts.some(part => part.type === 'reasoning'))));
  assert.ok(!JSON.stringify(result.output).includes('仅供界面的模型思考'));
  assert.ok(f.current() !== null && isComplete(f.current()!));
  assert.equal(f.current()?.generationPhase, 'COMPLETE');
  assert.ok(f.saved.some(output => statusOf(output, 'NARRATIVE') === 'READY' && statusOf(output, 'ANALYSIS') !== 'READY'));
});

test('bad analysis JSON over real HTTP gets one JSON correction and no tool fallback', async t => {
  const scripts = normalScripts();
  scripts.splice(3, 0, { text: '自由文本不能作为完成的结构化分析' });
  const f = await fixture(t, scripts);
  const result = await run(f.deps, 'article', '来源产品');
  assert.equal(result.ok, true, result.error ?? '');
  assert.equal(f.requests.length, 5);
  assertWireConfig(f.requests);
  assert.match(prompt(f.requests[4]), /不是完整、可解析的 JSON/);
  assert.match(prompt(f.requests[4]), /自由文本不能作为完成的结构化分析/);
  assert.equal(result.output.sectionQualities.ANALYSIS, 'STANDARD');
  assert.equal(result.output.analysis.coreDispute, ANALYSIS.analysis.core_dispute);
  assert.ok(!JSON.stringify(result.output).includes(scripts[3].text));
  assert.ok(f.current() !== null && isComplete(f.current()!));
});

test('concise analysis with the current impact hierarchy completes through real HTTP without a false JSON failure', async t => {
  const scripts = normalScripts().slice(0, 3);
  const analysis = { analysis: { core_dispute: '开放权重能改变竞争格局吗？',
    perspectives: [{ holder: '开发者', viewpoint: '希望自行部署并核验模型。' },
      { holder: '企业', viewpoint: '重视数据控制与部署成本。' },
      { holder: '评测者', viewpoint: '等待独立测试核对发布主张。' }] },
    impacts: [{ target: '开发者', horizon: 'short', effect: '开放权重使开发者可以自行部署并检查模型，实际成本与能力仍需根据独立测试判断。' }],
    watch: ['关注独立评测是否支持发布主张。'], uncertainties: [] };
  scripts.push({ text: JSON.stringify(analysis) }, { text: JSON.stringify(analysis) });
  const f = await fixture(t, scripts);
  const result = await run(f.deps, 'article', '开放模型');
  assert.equal(statusOf(result.output, 'ANALYSIS'), 'READY', result.output.sectionStates.ANALYSIS?.errorMessage ?? '');
  assert.equal(isComplete(result.output), true);
  assert.equal(f.requests.length, 4, 'the valid section needs no JSON repair');
  assert.deepEqual(result.output.analysis.perspectives.map(p => p.viewpoint), analysis.analysis.perspectives.map(p => p.viewpoint));
  assert.equal(result.output.impacts?.[0].effect, analysis.impacts[0].effect);
  const partial = { ...result.output, generationComplete: false, analysis: { coreDispute: null, perspectives: [], implications: null, quotes: [] },
    impacts: [], sectionStates: { ...result.output.sectionStates, ANALYSIS: { status: 'FAILED' as const, errorMessage: '旧判定拒绝' } } };
  const retry = await fixture(t, [{ text: JSON.stringify(PLAN) }, { text: JSON.stringify(analysis) }]);
  await retry.deps.repository?.save('article', '开放模型', partial);
  const repaired = await runSection(retry.deps, 'article', '开放模型', 'ANALYSIS');
  assert.equal(isComplete(repaired.output), true);
  assert.equal(retry.requests.length, 2, 'section repair only asks for the plan and failed analysis');
  assert.equal(repaired.output.summary, partial.summary);
  assert.deepEqual(repaired.output.timeline, partial.timeline);
  assert.deepEqual(repaired.output.corePoints, partial.corePoints);
});

test('valid but empty analysis reports its missing content rather than invalid JSON over HTTP', async t => {
  const scripts = normalScripts().slice(0, 3).concat([
    { text: JSON.stringify({ analysis: {}, impacts: [{ target: '读者', horizon: 'short', effect: '这一条虽然有完整的影响描述，但没有分析中的争议或任何当事方立场，不能单独掩盖分析为空。' }], watch: [] }) },
    { text: JSON.stringify({ analysis: {}, impacts: [], watch: [] }) },
  ]);
  const f = await fixture(t, scripts);
  const result = await run(f.deps, 'article', '来源产品');
  assert.equal(statusOf(result.output, 'ANALYSIS'), 'FAILED');
  assert.match(result.output.sectionStates.ANALYSIS?.errorMessage ?? '', /分析内容不足/);
  assert.match(prompt(f.requests[4]), /分析内容/);
  assert.equal(result.output.summary, OVERVIEW.summary);
  const priorNarrative = f.saved.find(output => statusOf(output, 'NARRATIVE') === 'READY');
  assert.ok(priorNarrative);
  assert.deepEqual(result.output.timeline, priorNarrative.timeline);
  assert.equal(isComplete(result.output), false);
});

test('abort during analysis HTTP SSE keeps persisted earlier stages and never marks the partial ready', async t => {
  const controller = new AbortController();
  const scripts = normalScripts().slice(0, 3).concat({ text: '分析阶段尚未结束的部分文本', holdOpen: true });
  const f = await fixture(t, scripts, controller);
  const result = await run(f.deps, 'article', '来源产品', { signal: controller.signal });
  assert.equal(controller.signal.aborted, true);
  assert.equal(result.ok, false);
  assert.equal(result.error, 'aborted');
  assert.equal(f.requests.length, 4, 'Abort must stop before correction and extended-reading calls');
  assertWireConfig(f.requests);
  assert.ok(f.rawSnapshots.some(messages => messages.some(message => message.parts.some(part =>
    part.type === 'text' && part.text === '分析阶段尚未结束的部分文本'))));
  assert.equal(result.output.summary, OVERVIEW.summary);
  assert.deepEqual(result.output.timeline, f.current()?.timeline);
  assert.equal(statusOf(result.output, 'OVERVIEW'), 'READY');
  assert.equal(statusOf(result.output, 'NARRATIVE'), 'READY');
  assert.notEqual(statusOf(result.output, 'ANALYSIS'), 'READY');
  assert.equal(isComplete(result.output), false);
  assert.ok(!f.saved.some(output => statusOf(output, 'ANALYSIS') === 'READY' || isComplete(output)));
  assert.ok(!JSON.stringify(f.current()).includes('分析阶段尚未结束的部分文本'));
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { run, isComplete, makeEmptyDeepReadOutput, latestAssistantText, deepReadToMarkdown } from '@amber/deepread-domain';
import type { DeepReadOutput, HttpClient, HttpResponse, RunManagerDeps, UIMessage } from '@amber/deepread-domain';
import { createChatDeepReadAiClient } from '../main/ets/chat/deepread_ai_client.ts';
import { createOpenAIChatApi } from '../main/ets/chat/openai_chat_api.ts';
import { makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';

interface WireBody { model: string; stream: boolean; max_completion_tokens: number; max_tokens?: number; thinking: { type: string }; tools?: object[]; messages: { role: string; content: string }[]; }

test('MiMo SSE reasoning plus fragmented QA JSON reaches synthesis persistence and export through the production clients', async t => {
  const requests: WireBody[] = [];
  const snapshots: UIMessage[][] = [];
  const answer = JSON.stringify({ title: '新版问答文章', lede: '这是根据用户提供的材料形成的导语。', questions: [{ q: '为何发生变化？', a: '用户提供的原始材料记录了变化经过和已知事实，尚未确认的部分需要继续核实。', sources: [1, 999] }] });
  const sse = (delta: object, finish: string | null = null): string => `data: ${JSON.stringify({ id: 'fixture', model: 'mimo-v2.6-flash', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
  const server = createServer(async (request, response) => {
    const chunks: Buffer[] = []; for await (const chunk of request) chunks.push(Buffer.from(chunk));
    assert.equal(request.url, '/v1/chat/completions');
    requests.push(JSON.parse(Buffer.concat(chunks).toString('utf8')));
    response.writeHead(200, { 'Content-Type': 'text/event-stream' });
    const body = Buffer.from(sse({ role: 'assistant', reasoning_content: '这是模型思考，不能混进问答 JSON。' })
      + sse({ content: answer.slice(0, 53) }) + sse({ content: answer.slice(53) }) + sse({}, 'stop') + 'data: [DONE]\n\n');
    response.write(body.subarray(0, 37));
    setImmediate(() => { response.write(body.subarray(37, 159)); setImmediate(() => response.end(body.subarray(159))); });
  });
  await new Promise<void>((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  t.after(async () => { server.closeAllConnections(); await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve())); });
  const address = server.address(); assert.ok(address && typeof address !== 'string');
  const http: HttpClient = {
    fetch: async (): Promise<HttpResponse> => { throw new Error('synthesis must use production streaming'); },
    fetchStream: async (request, options): Promise<HttpResponse> => {
      const response = await fetch(request.url, { method: request.method, headers: request.headers, body: request.body });
      assert.ok(response.body); const reader = response.body.getReader();
      while (true) {
        const chunk = await reader.read(); if (chunk.done) break;
        options.onChunk(chunk.value.buffer.slice(chunk.value.byteOffset, chunk.value.byteOffset + chunk.value.byteLength) as ArrayBuffer, false);
      }
      options.onChunk(new ArrayBuffer(0), true); options.onDataEnd?.();
      return { status: response.status, headers: Object.fromEntries(response.headers), body: '' };
    },
  };
  const client = createChatDeepReadAiClient({
    api: createOpenAIChatApi({ http, setting: makeProviderSettingOpenAI({ brand: 'mimo', baseUrl: `http://127.0.0.1:${address.port}/v1` }), headers: () => ({ 'api-key': 'local-fixture' }) }),
    params: makeTextGenerationParams({ model: makeChatModel({ modelId: 'mimo-v2.6-flash', abilities: ['reasoning'] }), maxTokens: 8192, reasoningLevel: 'high' }),
    onRawSnapshot: messages => snapshots.push(structuredClone(messages)),
  });
  let disk = JSON.stringify({ ...makeEmptyDeepReadOutput(), templateId: 'deepread_qa', templateSnapshot: { id: 'deepread_qa', name: '问答解读', kind: 'synthesis', html: null, capturedAt: 1 } });
  let collected = 0;
  const deps: RunManagerDeps = {
    model: 'mimo-v2.6-flash', aiClient: client, writerMode: 'structured', playbookMarkdown: '', nowIso: () => '2026-10-06',
    prefetcher: { collect: async () => { collected++; return [{ sourceId: 'input:1', title: '用户资料', url: '', source: '文本', evidenceText: '用户提供的真实资料，记载了事件经过和后续变化。'.repeat(20), credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [] }]; }, cacheSize: () => 0 },
    collectRun: (messages, _label, signal, tools) => client.generateText({ model: 'mimo-v2.6-flash', messages, signal, tools: tools?.map(tool => ({ ...tool, schema: tool.schema ?? {} })) }),
    repository: { get: () => JSON.parse(disk) as DeepReadOutput, save: async (_id, _title, output) => { disk = JSON.stringify(output); }, clear() { throw new Error('must preserve inputs'); } },
  };
  const result = await run(deps, 'qa-topic', '待解读话题');
  assert.equal(result.ok, true); assert.equal(isComplete(result.output), true); assert.equal(collected, 1);
  assert.equal(requests.length, 1, 'template synthesis skips the classic planner and section requests');
  assert.equal(requests[0].model, 'mimo-v2.6-flash'); assert.equal(requests[0].stream, true);
  assert.equal(requests[0].max_completion_tokens, 8192); assert.equal(requests[0].max_tokens, undefined);
  assert.deepEqual(requests[0].thinking, { type: 'enabled' }); assert.equal(requests[0].tools?.length ?? 0, 0);
  assert.ok(requests[0].messages.some(message => message.content.includes('问答解读')));
  const finalMessages = snapshots.at(-1)!;
  assert.ok(finalMessages.some(message => message.parts.some(part => part.type === 'reasoning' && part.reasoning.includes('这是模型思考'))));
  assert.equal(latestAssistantText(finalMessages), answer);
  const saved = JSON.parse(disk) as DeepReadOutput;
  assert.deepEqual(saved.templateArticle, result.output.templateArticle);
  assert.equal(saved.templateArticle?.shape, 'template_synthesis'); assert.equal(saved.templateArticle?.sources[0].url, null);
  assert.deepEqual(saved.templateArticle?.qa?.[0].sources, [1]); assert.deepEqual(saved.sectionStates, {});
  assert.equal(saved.analysis.coreDispute, null);
  const markdown = deepReadToMarkdown({ topicId: 'qa-topic', title: '待解读话题', sourceUrl: null, output: saved, phase: 'COMPLETE', attemptCount: 0, lastError: null, createdAt: 1, updatedAt: 1, expiresAt: 2 });
  assert.ok(markdown.includes('## 为何发生变化？')); assert.ok(markdown.includes('[1] 用户资料'));
  assert.equal(markdown.includes('这是模型思考'), false);
});

// OpenAI Chat API 组装层规格测试(请求构建 → HttpClient Port → SSE 装配 → chunk 回调)
//
// Android 基准: ChatCompletionsAPI.kt
//   streamText(:165-291) — URL=baseUrl+chatCompletionsPath、Content-Type、
//     onEvent normalize→parse→emit、[DONE] close、error payload cancel+close(error)、
//     onFailure 非 2xx body 解析(parseErrorDetail 优先,解析失败保留根因)
//   generateText(:107-163) — 非 2xx "Failed to get response: CODE BODY"、choices[0].message
//
// 裁剪(D-014 延续):bearerResolver/customHeaders/configureReferHeaders 由 adapter
//   经 headers() 注入;secret 不进纯逻辑层。eventSource.cancel 在 Port 层无对应,
//   解析失败抛错即终止消费(底层流由 adapter signal 收尾)。

import { test } from 'node:test';
import assert from 'node:assert/strict';

import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';

import { createOpenAIChatApi, asChatStreamProvider } from '../main/ets/chat/openai_chat_api.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';
import type { MessageChunk } from '../main/ets/chat/message.ts';
import { makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { OpenAiStreamError } from '../main/ets/chat/openai_parse.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

const enc = new TextEncoder();

// 假 HttpClient:录制请求,按脚本回放 SSE 字节(可多段投递)
interface FakeScript {
  status: number;
  chunks: string[];      // 每段一次 onChunk 投递
  finalBody?: string;    // fetchStream 返回的 HttpResponse.body(错误路径用)
}
interface FakeCapture { req: HttpRequest | null; stopRequested?: boolean }
const fakeHttp = (script: FakeScript, capture: FakeCapture): HttpClient => ({
  fetch: (req: HttpRequest): Promise<HttpResponse> => {
    capture.req = req;
    return Promise.resolve({
      status: script.status, headers: {}, body: script.finalBody ?? script.chunks.join(''),
    });
  },
  fetchStream: (req: HttpRequest, opts: {
    onChunk: (c: ArrayBuffer, end: boolean) => void;
    onDataEnd?: () => void;
    shouldStop?: () => boolean;
  }): Promise<HttpResponse> => {
    capture.req = req;
    for (let i = 0; i < script.chunks.length; i++) {
      const isLast = i === script.chunks.length - 1;
      opts.onChunk(enc.encode(script.chunks[i]).buffer as ArrayBuffer, isLast);
      if (opts.shouldStop !== undefined && opts.shouldStop()) capture.stopRequested = true;
    }
    if (script.chunks.length === 0) {
      opts.onChunk(new ArrayBuffer(0), true);
    }
    opts.onDataEnd?.();
    return Promise.resolve({ status: script.status, headers: {}, body: script.finalBody ?? '' });
  },
});

const SSE_TEXT = [
  'data: {"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"你"},"finish_reason":null}]}',
  '',
  'data: {"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"好"},"finish_reason":null}]}',
  '',
  'data: [DONE]',
  '',
].join('\n');

const setting = makeProviderSettingOpenAI({});
const params = makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) });

test('streamText: 请求形状(url/method/Content-Type/body stream=true) + chunk 顺序 + done 收尾', async () => {
  const capture: FakeCapture = { req: null };
  const api = createOpenAIChatApi({
    http: fakeHttp({ status: 200, chunks: [SSE_TEXT] }, capture),
    setting,
  });
  const chunks: MessageChunk[] = [];
  await api.streamText([makeUserMessage('hi')], params, (c: MessageChunk): void => { chunks.push(c); });

  const req = capture.req;
  assert.ok(req !== null);
  assert.equal(req!.url, 'https://api.openai.com/v1/chat/completions');
  assert.equal(req!.method, 'POST');
  assert.equal(req!.headers['Content-Type'], 'application/json');
  const body = JSON.parse(req!.body ?? '{}') as JsonObject;
  assert.equal(body['stream'], true);
  assert.equal(body['model'], 'gpt-4o');
  assert.ok(Array.isArray(body['messages']));

  assert.equal(chunks.length, 2, '[DONE] 不产生 chunk');
  assert.equal(chunks[0].id, 'chatcmpl-1');
  assert.equal(chunks[1].choices[0].finishReason, 'unknown', 'null 归一 unknown');
});

test('streamText: 字节跨段切割(半个 SSE 行)由 assembler 正确拼合', async () => {
  const capture: FakeCapture = { req: null };
  const half = SSE_TEXT.slice(0, 60);
  const rest = SSE_TEXT.slice(60);
  const api = createOpenAIChatApi({
    http: fakeHttp({ status: 200, chunks: [half, rest] }, capture),
    setting,
  });
  const chunks: MessageChunk[] = [];
  await api.streamText([makeUserMessage('hi')], params, (c: MessageChunk): void => { chunks.push(c); });
  assert.equal(chunks.length, 2);
});

test('streamText: adapter 注入 headers 合并(auth/custom 由调用方提供)', async () => {
  const capture: FakeCapture = { req: null };
  const api = createOpenAIChatApi({
    http: fakeHttp({ status: 200, chunks: [SSE_TEXT] }, capture),
    setting,
    headers: (): Record<string, string> => ({ Authorization: 'Bearer k', 'X-Custom': 'v' }),
  });
  await api.streamText([makeUserMessage('hi')], params, (): void => {});
  assert.equal(capture.req?.headers['Authorization'], 'Bearer k');
  assert.equal(capture.req?.headers['X-Custom'], 'v');
  assert.equal(capture.req?.headers['Content-Type'], 'application/json');
});

test('streamText: 流内 error payload → OpenAiStreamError 传播,后续事件不再消费', async () => {
  const bad = [
    'data: {"id":"x","model":"m","choices":[{"index":0,"delta":{"content":"a"},"finish_reason":null}]}',
    '',
    'data: {"error":{"message":"rate limit","type":"rate_limit_error"}}',
    '',
    'data: {"id":"x","model":"m","choices":[{"index":0,"delta":{"content":"b"},"finish_reason":null}]}',
    '',
  ].join('\n');
  const api = createOpenAIChatApi({ http: fakeHttp({ status: 200, chunks: [bad] }, { req: null }), setting });
  const chunks: MessageChunk[] = [];
  await assert.rejects(
    api.streamText([makeUserMessage('hi')], params, (c: MessageChunk): void => { chunks.push(c); }),
    (e: unknown): boolean => e instanceof OpenAiStreamError && e.message === 'rate limit');
  assert.equal(chunks.length, 1, 'error 之后的 chunk 不投递');
});

test('streamText: 非 2xx + 可解析 error body → parseErrorDetail 错误', async () => {
  const body = '{"error":{"message":"invalid api key","type":"auth_error"}}';
  const api = createOpenAIChatApi({
    http: fakeHttp({ status: 401, chunks: [], finalBody: body }, { req: null }),
    setting,
  });
  await assert.rejects(
    api.streamText([makeUserMessage('hi')], params, (): void => {}),
    /invalid api key/);
});

test('streamText: 非 2xx + 不可解析 body → 保留根因(status+raw)', async () => {
  const api = createOpenAIChatApi({
    http: fakeHttp({ status: 502, chunks: [], finalBody: '<html>bad gateway</html>' }, { req: null }),
    setting,
  });
  await assert.rejects(
    api.streamText([makeUserMessage('hi')], params, (): void => {}),
    /502[\s\S]*bad gateway/);
});

test('generateText: 成功解析 message/usage/finishReason;非 2xx → Failed to get response', async () => {
  const okBody = JSON.stringify({
    id: 'chatcmpl-9', model: 'gpt-4o',
    choices: [{ index: 0, message: { role: 'assistant', content: 'answer' }, finish_reason: 'stop' }],
    usage: { prompt_tokens: 3, completion_tokens: 2, total_tokens: 5 },
  });
  const api = createOpenAIChatApi({
    http: fakeHttp({ status: 200, chunks: [], finalBody: okBody }, { req: null }),
    setting,
  });
  const chunk = await api.generateText([makeUserMessage('hi')], params);
  assert.equal(chunk.id, 'chatcmpl-9');
  assert.equal(chunk.choices[0].finishReason, 'stop');
  const msg = chunk.choices[0].message;
  assert.ok(msg !== null);
  assert.equal(msg!.parts[0].type === 'text' ? msg!.parts[0].text : '', 'answer');
  assert.equal(chunk.usage?.totalTokens, 5);

  const bad = createOpenAIChatApi({
    http: fakeHttp({ status: 500, chunks: [], finalBody: 'oops' }, { req: null }),
    setting,
  });
  await assert.rejects(bad.generateText([makeUserMessage('hi')], params),
    /Failed to get response: 500 oops/);
});

test('asChatStreamProvider: 适配 chat_turn 的 ChatStreamProvider 接口', async () => {
  const api = createOpenAIChatApi({
    http: fakeHttp({ status: 200, chunks: [SSE_TEXT] }, { req: null }),
    setting,
  });
  const provider = asChatStreamProvider(api, () => params);
  const chunks: MessageChunk[] = [];
  let dataEnded: boolean = false;
  await provider.streamText(
    [makeUserMessage('hi')],
    (c: MessageChunk): void => { chunks.push(c); },
    { onDataEnd: (): void => { dataEnded = true; } },
  );
  assert.equal(chunks.length, 2);
  assert.equal(dataEnded, true);
});

test('useResponseApi=true routes to Responses /responses endpoint', async () => {
  const captured: Array<{ url: string; body: string }> = [];
  const http = {
    fetch: async (req: { url: string; body?: string }) => {
      captured.push({ url: req.url, body: req.body ?? '' });
      return {
        status: 200,
        headers: {},
        body: JSON.stringify({
          id: 'resp_1',
          model: 'gpt-4o',
          status: 'completed',
          output: [{ type: 'message', content: [{ type: 'output_text', text: 'ok' }] }],
        }),
      };
    },
    fetchStream: async (req: { url: string; body?: string }) => {
      captured.push({ url: req.url, body: req.body ?? '' });
      return { status: 200, headers: {}, body: '' };
    },
  };
  const api = createOpenAIChatApi({
    http,
    setting: makeProviderSettingOpenAI({ useResponseApi: true, baseUrl: 'https://api.openai.com/v1' }),
  });
  const chunk = await api.generateText([makeUserMessage('hi')], params);
  assert.equal(captured.length, 1);
  assert.ok(captured[0].url.endsWith('/responses'));
  assert.equal(JSON.parse(captured[0].body)['stream'], false);
  assert.equal(chunk.choices[0].message!.parts[0].type, 'text');
});

test('openAIAuthHeaders: mimo_coding_plan/token-plan 主机用 api-key,其余 Bearer(:545-557)', async () => {
  const { openAIAuthHeaders } = await import('../main/ets/chat/openai_chat_api.ts');
  assert.deepEqual(openAIAuthHeaders('api_key', 'https://api.openai.com/v1', 'T'),
    { Authorization: 'Bearer T' });
  assert.deepEqual(openAIAuthHeaders('mimo_coding_plan', 'https://token-plan-cn.xiaomimimo.com/v1', 'T'),
    { 'api-key': 'T' });
  assert.deepEqual(openAIAuthHeaders('api_key', 'https://token-plan-cn.xiaomimimo.com/v1', 'T'),
    { 'api-key': 'T' }, 'token-plan-* xiaomimimo.com 主机即使 api_key 模式也用 api-key 头');
});

// ===== R06:200 但非 SSE / 零有效事件不得静默成功;R18:[DONE] 后立即收口 =====

test('R06/R18 openai streamText: 200 非 SSE/空流 → 抛错;解析到 [DONE] → 请求收口底层流', async () => {
  const body = '{"error":{"message":"bad gateway proxy","type":"proxy_error"}}';
  const nonSse = createOpenAIChatApi({
    http: fakeHttp({ status: 200, chunks: [body], finalBody: body }, { req: null }),
    setting,
  });
  await assert.rejects(
    nonSse.streamText([makeUserMessage('hi')], params, (): void => {}),
    /bad gateway proxy/, '200 + 普通 JSON 必须报错');

  const empty = createOpenAIChatApi({
    http: fakeHttp({ status: 200, chunks: [], finalBody: '' }, { req: null }),
    setting,
  });
  await assert.rejects(
    empty.streamText([makeUserMessage('hi')], params, (): void => {}),
    /Failed to get response: 200/, '200 零 SSE 事件必须失败');

  const capture: FakeCapture = { req: null };
  const ok = createOpenAIChatApi({
    http: fakeHttp({ status: 200, chunks: [SSE_TEXT] }, capture),
    setting,
  });
  await ok.streamText([makeUserMessage('hi')], params, (): void => {});
  assert.equal(capture.stopRequested, true, '[DONE] 后必须请求关闭底层流');
});

// key_roulette_wiring — chat provider 每请求轮换钉死(D-078,PD-008)
import test from 'node:test';
import assert from 'node:assert/strict';
import { createClaudeChatApi } from '../main/ets/chat/claude_chat_api.ts';
import { createGoogleChatApi } from '../main/ets/chat/google_chat_api.ts';
import {
  makeProviderSettingClaude, makeProviderSettingGoogle,
} from '../main/ets/chat/provider_settings.ts';
import { makeChatModel, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';
import type { KeyRoulette } from '../main/ets/search/search_service.ts';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';

const CLAUDE_OK_BODY: string = JSON.stringify({
  id: 'msg_1', type: 'message', role: 'assistant',
  content: [{ type: 'text', text: 'ok' }],
  model: 'claude-x', stop_reason: 'end_turn', stop_sequence: null,
  usage: { input_tokens: 1, output_tokens: 1 },
});

const GOOGLE_OK_BODY: string = JSON.stringify({
  candidates: [{
    content: { role: 'model', parts: [{ text: 'ok' }] },
    finishReason: 'STOP',
  }],
  usageMetadata: { promptTokenCount: 1, candidatesTokenCount: 1, totalTokenCount: 2 },
});

const fakeHttp = (body: string, seen: HttpRequest[]): HttpClient => ({
  fetch: (req: HttpRequest): Promise<HttpResponse> => {
    seen.push(req);
    return Promise.resolve({ status: 200, headers: {}, body });
  },
  fetchStream: (req: HttpRequest): Promise<HttpResponse> => {
    seen.push(req);
    return Promise.resolve({ status: 200, headers: {}, body: '' });
  },
});

// 轮换桩:记录调用,依次吐 key
const stubRoulette = (keys: string[]): { r: KeyRoulette; calls: Array<[string, string]> } => {
  const calls: Array<[string, string]> = [];
  let i: number = 0;
  return {
    calls,
    r: {
      next: (keysRaw: string, providerId: string = ''): string => {
        calls.push([keysRaw, providerId]);
        const k: string = keys[i % keys.length];
        i++;
        return k;
      },
    },
  };
};

const params = makeTextGenerationParams({ model: makeChatModel({ modelId: 'm' }) });

test('claude: 每请求 keyRoulette.next(apiKey, id) → x-api-key 轮换', async () => {
  const { r, calls } = stubRoulette(['K1', 'K2']);
  const seen: HttpRequest[] = [];
  const api = createClaudeChatApi({
    http: fakeHttp(CLAUDE_OK_BODY, seen),
    setting: makeProviderSettingClaude({ id: 'prov-c', apiKey: 'K1, K2' }),
    keyRoulette: r,
  });
  await api.generateText([makeUserMessage('hi')], params);
  await api.generateText([makeUserMessage('hi')], params);
  assert.deepEqual(calls, [['K1, K2', 'prov-c'], ['K1, K2', 'prov-c']]);
  assert.equal(seen[0].headers['x-api-key'], 'K1');
  assert.equal(seen[1].headers['x-api-key'], 'K2');
  assert.equal(seen[0].headers['anthropic-version'], '2023-06-01');
});

test('google: 每请求轮换 → x-goog-api-key 头(非 vertex)', async () => {
  const { r } = stubRoulette(['G1', 'G2']);
  const seen: HttpRequest[] = [];
  const api = createGoogleChatApi({
    http: fakeHttp(GOOGLE_OK_BODY, seen),
    setting: makeProviderSettingGoogle({ id: 'prov-g', apiKey: 'G1 G2' }),
    keyRoulette: r,
  });
  await api.generateText([makeUserMessage('hi')], params);
  await api.generateText([makeUserMessage('hi')], params);
  assert.equal(seen[0].headers['x-goog-api-key'], 'G1');
  assert.equal(seen[1].headers['x-goog-api-key'], 'G2');
});

test('google: vertexAI → key 作 query 参数(轮换值 encode)', async () => {
  const { r } = stubRoulette(['V,1']);
  const seen: HttpRequest[] = [];
  const api = createGoogleChatApi({
    http: fakeHttp(GOOGLE_OK_BODY, seen),
    setting: makeProviderSettingGoogle({ id: 'p', apiKey: 'V,1', vertexAI: true }),
    keyRoulette: r,
  });
  await api.generateText([makeUserMessage('hi')], params);
  assert.ok(seen[0].url.includes(`key=${encodeURIComponent('V,1')}`), seen[0].url);
});

test('缺省 roulette → DefaultKeyRoulette 随机取一(不抛错,取到的在列表内)', async () => {
  const seen: HttpRequest[] = [];
  const api = createClaudeChatApi({
    http: fakeHttp(CLAUDE_OK_BODY, seen),
    setting: makeProviderSettingClaude({ id: 'p', apiKey: 'A1, A2' }),
  });
  await api.generateText([makeUserMessage('hi')], params);
  assert.ok(['A1', 'A2'].includes(seen[0].headers['x-api-key']));
});

// ===== R06 / R18:Claude/Google 流式协议收口(chat provider 装配层)=====

const enc = new TextEncoder();

// 按脚本投递 SSE 段;记录消费方是否在事件后请求停止底层流
const streamingHttp = (
  chunks: string[],
  status: number,
  finalBody: string,
  onStop: () => void,
): HttpClient => ({
  fetch: async () => ({ status, headers: {}, body: finalBody }),
  fetchStream: async (_req, opts): Promise<HttpResponse> => {
    for (let i = 0; i < chunks.length; i++) {
      opts.onChunk(enc.encode(chunks[i]).buffer as ArrayBuffer, i === chunks.length - 1);
      if (opts.shouldStop !== undefined && opts.shouldStop()) onStop();
    }
    return { status, headers: {}, body: finalBody };
  },
});

test('R06/R18 provider: Claude/Google 200 非 SSE → 抛错;消息终止/流内错误 → 请求收口', async () => {
  // R06:200 回普通 JSON(非 SSE)必须报错,不再静默空回复
  const jsonBody = JSON.stringify({ error: { message: 'proxy json error' } });
  const claudeNonSse = createClaudeChatApi({
    http: streamingHttp([jsonBody], 200, jsonBody, (): void => {}),
    setting: makeProviderSettingClaude({ id: 'c', apiKey: 'K' }),
  });
  await assert.rejects(() => claudeNonSse.streamText([makeUserMessage('hi')], params, (): void => {}),
    /proxy json error/);
  const googleNonSse = createGoogleChatApi({
    http: streamingHttp([jsonBody], 200, jsonBody, (): void => {}),
    setting: makeProviderSettingGoogle({ id: 'g', apiKey: 'K' }),
  });
  await assert.rejects(() => googleNonSse.streamText([makeUserMessage('hi')], params, (): void => {}),
    /proxy json error/);

  // R18:协议终止帧后必须请求立即关闭底层流
  let claudeStopped = false;
  const claude = createClaudeChatApi({
    http: streamingHttp(['event: message_stop\ndata: {"type":"message_stop"}\n\n'], 200, '',
      (): void => { claudeStopped = true; }),
    setting: makeProviderSettingClaude({ id: 'c', apiKey: 'K' }),
  });
  await claude.streamText([makeUserMessage('hi')], params, (): void => {});
  assert.equal(claudeStopped, true, 'Claude message_stop 后必须请求关闭底层流');

  let googleStopped = false;
  const google = createGoogleChatApi({
    http: streamingHttp(['data: {"error":{"message":"google stream boom"}}\n\n'], 200, '',
      (): void => { googleStopped = true; }),
    setting: makeProviderSettingGoogle({ id: 'g', apiKey: 'K' }),
  });
  await assert.rejects(() => google.streamText([makeUserMessage('hi')], params, (): void => {}),
    /google stream boom/);
  assert.equal(googleStopped, true, 'Google 流内 error 后必须有收口路径');
});

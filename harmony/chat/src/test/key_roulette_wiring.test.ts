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

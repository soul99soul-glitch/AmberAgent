import test from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import { createGoogleChatApi } from '../main/ets/chat/google_chat_api.ts';
import { makeProviderModel, makeProviderSettingGoogle } from '../main/ets/chat/provider_settings.ts';
import { parseProviderSetting, serializeProviderSetting } from '../main/ets/chat/provider_settings_serialize.ts';
import { toChatModel } from '../main/ets/chat/context_assembly.ts';
import { makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { makeAssistantMessage, makeUserMessage, toText } from '../main/ets/chat/message.ts';
import { MessageStreamAccumulator } from '../main/ets/chat/stream_accumulator.ts';
import { parseMessageList, serializeMessageList } from '../main/ets/chat/serialize.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

test('Google builtin mode and metadata-only tail survive the real model/API/message round-trip', async () => {
  const setting = parseProviderSetting(serializeProviderSetting(makeProviderSettingGoogle({
    apiKey: 'fixture-key',
    models: [makeProviderModel({ modelId: 'gemini-2.5-flash', abilities: ['tool'], tools: ['search', 'url_context'] })],
  })));
  assert.equal(setting.type, 'google');
  if (setting.type !== 'google') throw new Error('Google fixture required');
  const model = toChatModel(setting.models[0]);
  assert.deepEqual(model.tools, ['search', 'url_context']);
  const localTool = { name: 'file_read', description: 'Read a workspace file', parameters: { type: 'object' } };
  const params = makeTextGenerationParams({ model, tools: [localTool] });
  const html = '<style>@media(prefers-color-scheme:dark){a{color:#fff}}</style>\n<a href="https://www.google.com/search?q=amber">Amber / 搜索</a>';
  const citation = { web: { uri: 'https://example.com/source', title: 'Source' } };
  const success = { retrievedUrl: 'https://example.com/read', urlRetrievalStatus: 'URL_RETRIEVAL_STATUS_SUCCESS' };
  const failure = { retrievedUrl: 'https://example.com/failed', urlRetrievalStatus: 'URL_RETRIEVAL_STATUS_ERROR' };
  const metadata: JsonObject = {
    groundingMetadata: { groundingChunks: [citation, citation], searchEntryPoint: { renderedContent: html } },
    urlContextMetadata: { urlMetadata: [success, failure, success] },
  };
  const events: JsonObject[] = [
    { candidates: [{ content: { role: 'model', parts: [{ text: '仅一份正文。' }] },
      groundingMetadata: { groundingChunks: [citation] } }] },
    { candidates: [{ ...metadata, finishReason: 'STOP' }] },
  ];
  const requests: HttpRequest[] = [];
  const http: HttpClient = {
    fetch: async (request): Promise<HttpResponse> => {
      requests.push(request);
      return { status: 200, headers: {}, body: JSON.stringify({ candidates: [{ ...metadata,
        content: { role: 'model', parts: [{ text: '非流式正文。' }] } }] }) };
    },
    fetchStream: async (request, opts): Promise<HttpResponse> => {
      requests.push(request);
      for (const event of events) {
        const bytes = new TextEncoder().encode(`data: ${JSON.stringify(event)}\n\n`);
        opts.onChunk(bytes.buffer as ArrayBuffer, false);
      }
      opts.onChunk(new ArrayBuffer(0), true);
      return { status: 200, headers: {}, body: '' };
    },
  };
  const api = createGoogleChatApi({ http, setting, keyRoulette: { next: () => 'fixture-key' } });
  const messages = [makeUserMessage('读取给定网址，并检索来源')];
  const accumulator = new MessageStreamAccumulator([...messages, makeAssistantMessage('')]);
  await api.streamText(messages, params, chunk => accumulator.append(chunk));
  const body = JSON.parse(requests[0].body ?? '') as JsonObject;
  assert.deepEqual(body['tools'], [{ googleSearch: {} }, { urlContext: {} }]);
  assert.equal(body['toolConfig'], undefined, 'builtin-only does not pretend mixed-tool context circulation');
  const restored = parseMessageList(serializeMessageList(accumulator.snapshot()));
  assert.equal(toText(restored[1]), '仅一份正文。');
  assert.deepEqual(restored[1].annotations, [
    { type: 'url_citation', title: 'Source', url: 'https://example.com/source' },
    { type: 'google_search_suggestions', html },
    { type: 'url_context', url: success.retrievedUrl, status: success.urlRetrievalStatus },
    { type: 'url_context', url: failure.retrievedUrl, status: failure.urlRetrievalStatus },
  ]);

  const nonstream = await api.generateText(messages, params);
  const reply = nonstream.choices[0].message;
  assert.ok(reply !== null);
  assert.equal(toText(reply), '非流式正文。');
  assert.ok(reply.annotations.some(annotation => annotation.type === 'google_search_suggestions' && annotation.html === html));
  assert.ok(reply.annotations.some(annotation => annotation.type === 'url_context'
    && annotation.url === failure.retrievedUrl && annotation.status === failure.urlRetrievalStatus));

  const imageOnly = toChatModel(makeProviderModel({ modelId: model.modelId, abilities: ['tool'], tools: ['image_generation'] }));
  await api.generateText(messages, makeTextGenerationParams({ model: imageOnly, tools: [localTool] }));
  assert.deepEqual((JSON.parse(requests[2].body ?? '') as JsonObject)['tools'], [{ functionDeclarations: [localTool] }]);
  await api.generateText(messages, makeTextGenerationParams({ model: imageOnly }));
  assert.equal((JSON.parse(requests[3].body ?? '') as JsonObject)['tools'], undefined, 'image-only never writes empty tools');
});

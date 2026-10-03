import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  createNovelCreation, createFileNovelRepository, createMemoryFileStore,
} from '@amber/deepread-domain';
import type { HttpClient, HttpRequest } from '@amber/deepread-domain';
import { createNovelInteractiveAdapter } from '../main/ets/chat/novel_interactive_adapter.ts';
import { createOpenAIChatApi, asChatStreamProvider } from '../main/ets/chat/openai_chat_api.ts';
import { makeProviderSettingOpenAI, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';

const contract = {
  outlinePlacement: '第三章转折', goalAndConflict: '找到来信，面对阻拦',
  mustHappen: ['发现信封'], mustNotHappen: ['揭露真凶'],
  endingHook: '陌生电话', visibleFacts: ['主角只知道邮戳'],
};
interface RawCompletionRequest {
  messages: Array<{ role: string; content: string }>;
  tools?: unknown[];
}

test('contract proposal carries its required JSON instruction through real adapter and raw OpenAI request', async () => {
  const raw: HttpRequest[] = [];
  const http: HttpClient = {
    async fetch() { throw new Error('unexpected nonstream request'); },
    async fetchStream(request, options) {
      raw.push(request);
      const payload: RawCompletionRequest = JSON.parse(request.body ?? '{}');
      const system = payload.messages.filter(message => message.role === 'system').map(message => message.content).join('\n');
      // Match the signed-device fixture: absent planning instruction produces ordinary prose.
      const answer = system.includes('你是小说本章策划助手') && system.includes('"goalAndConflict"')
        ? JSON.stringify(contract) : '邮差走进了雨中的小镇。';
      const event = { id: 'plan', model: 'fixture', choices: [{ index: 0,
        delta: { role: 'assistant', content: answer }, finish_reason: 'stop' }] };
      const sse = `data: ${JSON.stringify(event)}\n\ndata: [DONE]\n\n`;
      options.onChunk(new TextEncoder().encode(sse).buffer as ArrayBuffer, true);
      return { status: 200, headers: {}, body: '' };
    },
  };
  const api = createOpenAIChatApi({ http, setting: makeProviderSettingOpenAI({ baseUrl: 'https://fixture.invalid/v1' }) });
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({ systemPrompt: '普通聊天系统提示' }),
      provider: asChatStreamProvider(api, () => makeTextGenerationParams({ maxTokens: 4096 })),
      contextWindowTokens: 16_384, maxOutputTokens: 4096 }),
    createAbortController: () => new AbortController(),
  });
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: adapter });
  const project = await creation.create('实际策划请求');
  await creation.upsertMaterial(project.id, null, 'world', '邮路', '古镇以邮戳辨认来信', true);
  const before = await repository.workspaceStatus(project.id);
  const proposal = await creation.proposeChapterContract(project.id, '暂不公开真凶', before.cas);

  assert.equal(raw.length, 1);
  const payload: RawCompletionRequest = JSON.parse(raw[0].body ?? '{}');
  const system = payload.messages.filter(message => message.role === 'system').map(message => message.content).join('\n');
  assert.equal(system.match(/你是小说本章策划助手/g)?.length, 1);
  for (const field of Object.keys(contract)) assert.ok(system.includes(`"${field}"`), field);
  assert.match(system, /古镇以邮戳辨认来信/);
  assert.doesNotMatch(system, /先输出给作者阅读的自然正文|普通聊天系统提示/);
  assert.ok(payload.messages.some(message => message.role === 'user' && message.content === '暂不公开真凶'));
  assert.equal(payload.tools, undefined);
  assert.equal(proposal.contract.status, 'draft');
  for (const field of Object.keys(contract) as Array<keyof typeof contract>) {
    assert.deepEqual(proposal.contract[field], contract[field]);
  }
  assert.deepEqual((await repository.workspaceStatus(project.id)).cas, before.cas);
  assert.equal((await repository.loadProject(project.id)).branchSettings.chapterContract, undefined);
});

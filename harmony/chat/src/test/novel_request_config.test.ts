import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { NovelModelEvent, NovelModelRequest, NovelStructuredTaskOptions } from '@amber/deepread-domain';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { createNovelInteractiveAdapter } from '../main/ets/chat/novel_interactive_adapter.ts';
import { makeNovelTextGenerationParams, resolveNovelMaxOutputTokens } from '../main/ets/chat/novel_request_config.ts';
import { mergeNovelSystemMessages } from '../main/ets/chat/novel_context_policy.ts';
import { createNovelContextPolicy } from '../main/ets/chat/novel_context_policy.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';
import type { ChatToolDefinition, CustomBody, TextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { makeChatModel, makeTextGenerationParams, makeProviderSettingOpenAI } from '../main/ets/chat/provider_model.ts';
import { makeProviderSettingClaude } from '../main/ets/chat/provider_settings.ts';
import { buildChatCompletionRequest } from '../main/ets/chat/openai_request.ts';
import { buildResponsesRequestBody } from '../main/ets/chat/openai_responses_request.ts';
import { buildClaudeMessageRequest } from '../main/ets/chat/claude_request.ts';
import { buildGoogleCompletionRequestBody } from '../main/ets/chat/google_request.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import { makeSystemMessage, makeUserMessage, makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { MessageChunk, UIMessage } from '../main/ets/chat/message.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';
import { estimateTokens, estimateContextWindow } from '../main/ets/chat/context_compact.ts';

const bodies = (messages: UIMessage[], params: TextGenerationParams): JsonObject[] => [
  buildChatCompletionRequest({ messages, params, setting: makeProviderSettingOpenAI({}), stream: true }),
  buildResponsesRequestBody({ messages, params, setting: makeProviderSettingOpenAI({}), stream: true }),
  buildClaudeMessageRequest({ messages, params, setting: makeProviderSettingClaude({}), stream: true }),
  buildGoogleCompletionRequestBody({ messages, params }),
];

const completion: MessageChunk = {
  id: 'chunk', model: 'test', choices: [{ index: 0, delta: makeAssistantMessage('完成'),
    message: null, finishReason: 'unknown' }], usage: null,
};

test('Novel 真 provider payload 在工具模式保留作者必要资料和归档摘要，canonical 不改写', async () => {
  const history = [makeUserMessage('归档前讨论'), makeAssistantMessage('归档前答复')];
  const original = JSON.stringify(history);
  const source = makeTextGenerationParams({
    model: makeChatModel({ modelId: 'test', abilities: ['tool'], tools: ['search', 'url_context'] }),
    customBody: [{ key: 'generationConfig', value: { thinkingConfig: { thinkingBudget: 128 } } }],
  });
  let sending: UIMessage[] = [];
  let payloads: JsonObject[] = [];
  let checkpoint: UIMessage[] = [];
  const caps: number[] = [];
  const provider = (definitions: ChatToolDefinition[], cap: number): ChatStreamProvider => ({
    async streamText(messages, onChunk): Promise<void> {
      sending = messages;
      payloads = bodies(messages, makeNovelTextGenerationParams(source, definitions, cap));
      caps.push(cap);
      onChunk(completion);
    },
  });
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({
      assistant: makeAssistant({}), provider: provider([], 8192), contextWindowTokens: 4000,
      makeProviderForOutputTokens: cap => provider([], cap!),
      tools: [makeAgentTool({ name: 'novel_workspace_read', description: 'read',
        systemPrompt: () => 'TOOL_POLICY', execute: async () => [] })],
      toolPromptModel: source.model,
      makeProviderForStep: (definitions, cap) => provider(definitions, cap!),
    }),
    createAbortController: () => ({ signal: { aborted: false }, abort(): void {} }),
  });
  const request: NovelModelRequest = {
    runId: 'actual-payload', projectId: 'p', modelTarget: { kind: 'global' }, maxOutputTokens: null,
    systemPrompt: 'OLD_SYSTEM', history,
    context: { sections: [
      { key: 'author', required: true, text: 'AUTHOR_REQUIRED' },
      { key: 'archive', required: true, text: 'ARCHIVE_SUMMARY' },
    ], excludedHistoryMessageIds: history.map(message => message.id) },
    operation: { kind: 'turn', userPrompt: '继续创作' },
    checkpoint: async messages => { checkpoint = messages; },
  };
  const terminal = await new Promise<NovelModelEvent>(resolve => adapter.start(request).subscribe(event => {
    if (event.kind === 'completed' || event.kind === 'failed') resolve(event);
  }));
  assert.equal(terminal.kind, 'completed');
  assert.equal(sending.filter(message => message.role === 'system').length, 1);
  for (const body of payloads) {
    assert.match(JSON.stringify(body), /AUTHOR_REQUIRED/);
    assert.match(JSON.stringify(body), /ARCHIVE_SUMMARY/);
    assert.match(JSON.stringify(body), /TOOL_POLICY/);
    assert.equal(JSON.stringify(body).includes('归档前讨论'), false);
    assert.equal(JSON.stringify(body).includes('web_search'), false);
    assert.equal(JSON.stringify(body).includes('googleSearch'), false);
    assert.match(JSON.stringify(body), /novel_workspace_read/);
  }
  assert.deepEqual(caps, [1000]);
  assert.equal(payloads[0].max_tokens, 1000);
  assert.equal(payloads[1].max_output_tokens, 1000);
  assert.equal(payloads[2].max_tokens, 1000);
  assert.equal((payloads[3].generationConfig as JsonObject).maxOutputTokens, 1000);
  assert.equal(((payloads[3].generationConfig as JsonObject).thinkingConfig as JsonObject).thinkingBudget, 128);
  assert.ok(estimateTokens(sending) <= 3000);
  assert.equal(JSON.stringify(history), original);
  assert.deepEqual(checkpoint.slice(0, 2).map(message => message.id), history.map(message => message.id));
  assert.deepEqual(source.model.tools, ['search', 'url_context']);
});

test('Novel 禁用工具不携带 runtime tools 或模型内置工具；所有真实 provider builder 一致', () => {
  const source = makeTextGenerationParams({
    model: makeChatModel({ abilities: ['tool'], tools: ['search', 'url_context', 'image_generation'] }),
    tools: [{ name: 'global_tool', description: 'global', parameters: {} }],
    customBody: [{ key: 'thinking', value: { type: 'disabled' } }],
  });
  const params = makeNovelTextGenerationParams(source, [], 321);
  for (const body of bodies([makeSystemMessage('AUTHOR_REQUIRED'), makeUserMessage('开始')], params)) {
    assert.equal(body.tools, undefined);
    assert.match(JSON.stringify(body), /AUTHOR_REQUIRED/);
  }
  assert.deepEqual(params.customBody, source.customBody);
  assert.deepEqual(source.model.tools, ['search', 'url_context', 'image_generation']);
  assert.equal(source.tools.length, 1);
});

test('Novel customBody 不能覆盖上下文、模型、输出上限或审批工具目录，错误指向冲突字段', () => {
  const conflicts: CustomBody[] = [
    { key: 'messages', value: [] }, { key: 'system', value: 'replacement' },
    { key: 'systemInstruction', value: {} }, { key: 'contents', value: [] },
    { key: 'input', value: [] }, { key: 'instructions', value: 'replacement' },
    { key: 'model', value: 'other' }, { key: 'tools', value: [] },
    { key: 'tool_choice', value: 'auto' }, { key: 'toolConfig', value: {} },
    { key: 'max_tokens', value: 90000 }, { key: 'max_completion_tokens', value: 90000 },
    { key: 'max_output_tokens', value: 90000 },
    { key: 'generationConfig', value: { maxOutputTokens: 90000 } },
    { key: 'generationConfig', value: null },
  ];
  for (const item of conflicts) {
    assert.throws(() => makeNovelTextGenerationParams(makeTextGenerationParams({ customBody: [item] }), [], 123),
      error => error instanceof Error && error.message.includes(item.key));
  }
});

test('Novel 选择支持图像输出的全局模型仍发送文本创作和作者system，不修改全局模型', () => {
  const source = makeTextGenerationParams({
    model: makeChatModel({ modelId: 'gemini-image', outputModalities: ['text', 'image'] }),
  });
  const params = makeNovelTextGenerationParams(source, [], 456);
  const body = buildGoogleCompletionRequestBody({
    messages: [makeSystemMessage('AUTHOR_REQUIRED'), makeUserMessage('写一章')], params,
  });
  assert.match(JSON.stringify(body.systemInstruction), /AUTHOR_REQUIRED/);
  assert.equal((body.generationConfig as JsonObject).maxOutputTokens, 456);
  assert.equal((body.generationConfig as JsonObject).responseModalities, undefined);
  assert.deepEqual(params.model.outputModalities, ['text']);
  assert.deepEqual(source.model.outputModalities, ['text', 'image']);
});

test('Novel 显式输出上限原样保留，未设置时 main、budget 采用有限且相同的上限', () => {
  assert.equal(resolveNovelMaxOutputTokens(99999, 300, 1000), 99999);
  assert.equal(resolveNovelMaxOutputTokens(null, 300, 1000), 300);
  assert.equal(resolveNovelMaxOutputTokens(null, null, 1000), 250);
  assert.equal(resolveNovelMaxOutputTokens(null, null, null), Math.min(8192, estimateContextWindow(null) / 4));
  const request: NovelModelRequest = { runId: 'p', projectId: 'p', modelTarget: { kind: 'global' },
    maxOutputTokens: null, systemPrompt: '必要指令', history: [],
    operation: { kind: 'turn', userPrompt: '开始' }, checkpoint: async () => {} };
  assert.equal(createNovelContextPolicy(request, 1000).tokenBudget, 750);
  const messages = [makeSystemMessage('工具指令'), makeUserMessage('原文'), makeSystemMessage('作者决定')];
  const before = JSON.stringify(messages);
  assert.equal(mergeNovelSystemMessages(messages).filter(message => message.role === 'system').length, 1);
  assert.equal(JSON.stringify(messages), before);
});

test('actual state adapter overrides configured reasoning payloads only for state tasks', async () => {
  const source = makeTextGenerationParams({
    model: makeChatModel({ modelId: 'gemini-2.5-flash', abilities: ['reasoning'] }), reasoningLevel: 'high',
    customBody: [{ key: 'thinking', value: { type: 'enabled' } },
      { key: 'generationConfig', value: { thinkingConfig: { thinkingBudget: 128 }, temperature: 0.25 } }],
  });
  for (const taskOptions of [undefined, { kind: 'stateDelta', reasoningEnabled: false },
    { kind: 'stateRebuild', reasoningEnabled: true }] as (NovelStructuredTaskOptions | undefined)[]) {
    let payloads: JsonObject[] = [];
    const provider = (options?: NovelStructuredTaskOptions): ChatStreamProvider => ({
      async streamText(messages, onChunk) {
        const params = makeNovelTextGenerationParams(source, [], 8192, options);
        payloads = [buildChatCompletionRequest({ messages, params,
          setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.deepseek.com' }), stream: true }),
          buildClaudeMessageRequest({ messages, params, setting: makeProviderSettingClaude({}), stream: true }),
          buildGoogleCompletionRequestBody({ messages, params })];
        onChunk(completion);
      },
    });
    const adapter = createNovelInteractiveAdapter({ resolveRuntime: async () => ({
      assistant: makeAssistant({}), provider: provider(), contextWindowTokens: 32768,
      makeProviderForOutputTokens: (_cap, options) => provider(options),
    }), createAbortController: () => ({ signal: { aborted: false }, abort(): void {} }) });
    const terminal = await new Promise<NovelModelEvent>(resolve => adapter.start({
      runId: 'reasoning', projectId: 'p', modelTarget: { kind: 'global' }, maxOutputTokens: 8192,
      systemPrompt: '抽取正文', history: [], toolProfile: 'none', taskOptions,
      operation: { kind: 'turn', userPrompt: '真实正文' }, checkpoint: async () => {},
    }).subscribe(event => { if (event.kind === 'completed' || event.kind === 'failed') resolve(event); }));
    assert.equal(terminal.kind, 'completed');
    const google = (payloads[2].generationConfig as JsonObject).thinkingConfig as JsonObject;
    if (taskOptions?.reasoningEnabled === false) {
      assert.deepEqual(payloads[0].thinking, { type: 'disabled' });
      assert.deepEqual(payloads[1].thinking, { type: 'disabled' });
      assert.equal(google.thinkingBudget, 0); assert.equal(google.includeThoughts, false);
    } else if (taskOptions !== undefined) {
      assert.deepEqual(payloads[0].thinking, { type: 'enabled' });
      assert.deepEqual(payloads[1].thinking, { type: 'adaptive', display: 'summarized' });
      assert.equal(google.thinkingBudget, undefined);
    } else {
      assert.deepEqual(payloads[0].thinking, { type: 'enabled' });
      assert.equal((payloads[1].thinking as JsonObject).type, 'enabled');
      assert.equal(google.thinkingBudget, 128);
    }
    assert.equal((payloads[2].generationConfig as JsonObject).temperature, 0.25);
  }
  assert.equal(source.reasoningLevel, 'high');
});

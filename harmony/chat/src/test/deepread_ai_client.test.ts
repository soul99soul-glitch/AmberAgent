// DeepRead uses the existing provider HTTP/SSE implementations and Chat tool loop.
// These tests exercise the adapter through real request builders and parsers.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type {
  AbortSignalLike, GenerateTextParams, HttpClient, HttpRequest, HttpResponse, Tool,
} from '@amber/deepread-domain';
import { createChatDeepReadAiClient } from '../main/ets/chat/deepread_ai_client.ts';
import { createOpenAIChatApi } from '../main/ets/chat/openai_chat_api.ts';
import type { OpenAIChatApi } from '../main/ets/chat/openai_chat_api.ts';
import { createClaudeChatApi } from '../main/ets/chat/claude_chat_api.ts';
import { createGoogleChatApi } from '../main/ets/chat/google_chat_api.ts';
import { makeProviderSettingClaude, makeProviderSettingGoogle } from '../main/ets/chat/provider_settings.ts';
import {
  makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams,
} from '../main/ets/chat/provider_model.ts';
import type { TextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { makeSystemMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePartTool } from '../main/ets/chat/message.ts';

const WRITER = 'deep_read_write_overview';
const INPUT = { summary: '有来源的概览', topicId: 'topic-1' };
const RESULT = '{"status":"written"}';
const SCHEMA = {
  type: 'object',
  properties: { summary: { type: 'string' }, topicId: { type: 'string' } },
  required: ['summary', 'topicId'],
};
const encoder = new TextEncoder();

interface HttpCapture {
  requests: HttpRequest[];
  signals: Array<AbortSignalLike | undefined>;
  nonStreamingCalls: number;
}

// This is the same byte-boundary HTTP port used by provider API tests, with
// one scripted response per request. It does not fake the AiClient/tool loop.
const scriptedHttp = (
  scripts: string[], capture: HttpCapture, afterChunk?: () => void,
): HttpClient => ({
  fetch: async (): Promise<HttpResponse> => {
    capture.nonStreamingCalls += 1;
    throw new Error('DeepRead must use the shared SSE transport');
  },
  fetchStream: async (req, opts): Promise<HttpResponse> => {
    const index = capture.requests.length;
    capture.requests.push(req);
    capture.signals.push(opts.signal);
    assert.ok(index < scripts.length, `Unexpected provider request ${index + 1}`);
    const bytes = encoder.encode(scripts[index]);
    // A split within a line also verifies that shared SSE framing is used.
    const middle = Math.min(41, bytes.length);
    opts.onChunk(bytes.slice(0, middle).buffer as ArrayBuffer, false);
    opts.onChunk(bytes.slice(middle).buffer as ArrayBuffer, true);
    afterChunk?.();
    opts.onDataEnd?.();
    return { status: 200, headers: {}, body: '' };
  },
});

const data = (body: object): string => `data: ${JSON.stringify(body)}\n\n`;
const event = (name: string, body: object): string => `event: ${name}\n${data(body)}`;

const chatTool = (): string => data({
  id: 'chat-1', model: 'fixture-model',
  choices: [{ index: 0, delta: { role: 'assistant', tool_calls: [{
    index: 0, id: 'call-1', type: 'function',
    function: { name: WRITER, arguments: JSON.stringify(INPUT) },
  }] }, finish_reason: 'tool_calls' }],
}) + 'data: [DONE]\n\n';

const chatText = (text: string): string => data({
  id: 'chat-2', model: 'fixture-model',
  choices: [{ index: 0, delta: { role: 'assistant', content: text }, finish_reason: 'stop' }],
}) + 'data: [DONE]\n\n';

const responsesTool = (): string => {
  const item = { type: 'function_call', id: 'fc-item-1', call_id: 'call-1', name: WRITER, arguments: '' };
  return event('response.output_item.added', { type: 'response.output_item.added', item })
    + event('response.function_call_arguments.done', {
      type: 'response.function_call_arguments.done', item_id: 'fc-item-1', arguments: JSON.stringify(INPUT),
    }) + event('response.completed', {
      type: 'response.completed', response: {
        id: 'resp-1', model: 'fixture-model', status: 'completed',
        output: [{ ...item, arguments: JSON.stringify(INPUT) }],
      },
    });
};

const responsesText = (text: string): string => event('response.output_text.delta', {
  type: 'response.output_text.delta', item_id: 'msg-2', delta: text,
}) + event('response.completed', {
  type: 'response.completed', response: {
    id: 'resp-2', model: 'fixture-model', status: 'completed',
    output: [{ type: 'message', id: 'msg-2', content: [{ type: 'output_text', text }] }],
  },
});

const claudeTool = (): string => event('content_block_start', {
  index: 0, content_block: { type: 'tool_use', id: 'call-1', name: WRITER, input: {} },
}) + event('content_block_delta', {
  index: 0, delta: { type: 'input_json_delta', partial_json: JSON.stringify(INPUT) },
}) + event('message_delta', { delta: { stop_reason: 'tool_use' } })
  + event('message_stop', {});

const claudeText = (text: string): string => event('content_block_delta', {
  index: 0, delta: { type: 'text_delta', text },
}) + event('message_delta', { delta: { stop_reason: 'end_turn' } }) + event('message_stop', {});

const googleTool = (): string => data({
  candidates: [{ index: 0, content: { role: 'model', parts: [{
    functionCall: { name: WRITER, args: INPUT }, thoughtSignature: 'thought-1',
  }] }, finishReason: 'STOP' }],
});

const googleText = (text: string): string => data({
  candidates: [{ index: 0, content: { role: 'model', parts: [{ text }] }, finishReason: 'STOP' }],
});

type Protocol = 'chat' | 'responses' | 'claude' | 'google';
interface Fixture {
  toolStream: () => string;
  textStream: (text: string) => string;
  makeApi: (http: HttpClient) => OpenAIChatApi;
}
const fixtures: Record<Protocol, Fixture> = {
  chat: {
    toolStream: chatTool, textStream: chatText,
    makeApi: (http): OpenAIChatApi => createOpenAIChatApi({
      http, setting: makeProviderSettingOpenAI({ baseUrl: 'https://chat.test/v1' }),
      headers: (): Record<string, string> => ({ Authorization: 'Bearer fixture-key', 'X-Custom': 'kept' }),
    }),
  },
  responses: {
    toolStream: responsesTool, textStream: responsesText,
    makeApi: (http): OpenAIChatApi => createOpenAIChatApi({
      http, setting: makeProviderSettingOpenAI({ baseUrl: 'https://responses.test/v1', useResponseApi: true }),
      headers: (): Record<string, string> => ({ Authorization: 'Bearer fixture-key', 'X-Custom': 'kept' }),
    }),
  },
  claude: {
    toolStream: claudeTool, textStream: claudeText,
    makeApi: (http): OpenAIChatApi => createClaudeChatApi({
      http, setting: makeProviderSettingClaude({ apiKey: 'fixture-key' }),
      headers: (): Record<string, string> => ({ 'X-Custom': 'kept' }),
    }),
  },
  google: {
    toolStream: googleTool, textStream: googleText,
    makeApi: (http): OpenAIChatApi => createGoogleChatApi({
      http, setting: makeProviderSettingGoogle({ apiKey: 'fixture-key' }),
      headers: (): Record<string, string> => ({ 'X-Custom': 'kept' }),
    }),
  },
};

const capture = (): HttpCapture => ({ requests: [], signals: [], nonStreamingCalls: 0 });
const params = (): TextGenerationParams => makeTextGenerationParams({
  model: makeChatModel({ modelId: 'fixture-model', abilities: ['tool'] }),
  temperature: 0.3, maxTokens: 4096,
  customBody: [{ key: 'fixture_custom', value: { kept: true } }],
});
const requestParams = (tools?: Tool[]): GenerateTextParams => ({
  model: 'fixture-model', messages: [makeSystemMessage('只使用来源'), makeUserMessage('写入概览')],
  tools, maxSteps: 4, autoApproveTools: true,
});
const writerTool = (calls: string[], extra: { allowsAutoApproval?: boolean; isHighRisk?: boolean } = {}): Tool => ({
  name: WRITER, description: 'Write sourced overview', schema: SCHEMA, ...extra,
  execute: async (input: string) => {
    calls.push(input);
    return [{ type: 'text', text: RESULT, metadata: null }];
  },
});
const toolsIn = (messages: UIMessage[]): UIMessagePartTool[] => messages
  .flatMap((message) => message.parts)
  .filter((part): part is UIMessagePartTool => part.type === 'tool');

for (const protocol of Object.keys(fixtures) as Protocol[]) {
  test(`DeepRead ${protocol}: real wire tool schema, writer execution and result round trip`, async () => {
    const fixture = fixtures[protocol];
    const recorded = capture();
    const calls: string[] = [];
    const rawIds: string[] = [];
    const client = createChatDeepReadAiClient({
      api: fixture.makeApi(scriptedHttp([fixture.toolStream(), fixture.textStream('完成')], recorded)),
      params: params(),
      onRawSnapshot: (messages): void => {
        const pending = toolsIn(messages).find((tool) => tool.output.length === 0);
        if (pending !== undefined) rawIds.push(pending.toolCallId);
      },
    });
    const result = await client.generateText(requestParams([writerTool(calls)]));
    assert.deepEqual(calls.map((input) => JSON.parse(input)), [INPUT]);
    assert.equal(recorded.nonStreamingCalls, 0);
    assert.equal(recorded.requests.length, 2);
    const tool = toolsIn(result)[0];
    assert.ok(tool !== undefined);
    assert.ok(rawIds.includes(tool.toolCallId), 'Execution must retain the parsed tool call id');
    assert.equal(tool.toolName, WRITER);
    assert.deepEqual(tool.output, [{ type: 'text', text: RESULT, metadata: null }]);
    assert.ok(result.some((message) => message.role === 'assistant'
      && message.parts.some((part) => part.type === 'text' && part.text === '完成')));

    const first = JSON.parse(recorded.requests[0].body ?? '{}');
    const second = JSON.parse(recorded.requests[1].body ?? '{}');
    assert.deepEqual(first.fixture_custom, { kept: true });
    assert.deepEqual(second.fixture_custom, { kept: true });
    assert.equal(recorded.requests[0].headers['X-Custom'], 'kept');
    let schema: object;
    if (protocol === 'chat') {
      schema = first.tools[0].function.parameters;
      assert.equal(first.stream, true);
      const output = second.messages.find((message: { role: string }) => message.role === 'tool');
      assert.equal(output.tool_call_id, 'call-1');
      assert.equal(output.content, RESULT);
    } else if (protocol === 'responses') {
      schema = first.tools[0].parameters;
      assert.equal(first.stream, true);
      const input = second.input.find((item: { type: string }) => item.type === 'function_call');
      const output = second.input.find((item: { type: string }) => item.type === 'function_call_output');
      assert.equal(input.call_id, 'call-1', 'Responses wire call_id differs from output item id');
      assert.equal(output.call_id, 'call-1');
      assert.equal(output.output, RESULT);
    } else if (protocol === 'claude') {
      schema = first.tools[0].input_schema;
      assert.equal(first.stream, true);
      const blocks = second.messages.flatMap((message: { content: object[] }) => message.content);
      const use = blocks.find((block: { type: string }) => block.type === 'tool_use');
      const output = blocks.find((block: { type: string }) => block.type === 'tool_result');
      assert.equal(use.id, 'call-1');
      assert.equal(output.tool_use_id, 'call-1');
      assert.deepEqual(output.content, [{ type: 'text', text: RESULT }]);
    } else {
      schema = first.tools[0].functionDeclarations[0].parameters;
      assert.ok(recorded.requests[0].url.includes(':streamGenerateContent?alt=sse'));
      const parts = second.contents.flatMap((content: { parts: object[] }) => content.parts);
      const use = parts.find((part: { functionCall?: object }) => part.functionCall !== undefined);
      const output = parts.find((part: { functionResponse?: object }) => part.functionResponse !== undefined);
      assert.deepEqual(use.functionCall, { name: WRITER, args: INPUT });
      assert.equal(use.thoughtSignature, 'thought-1');
      assert.deepEqual(output.functionResponse, { name: WRITER, response: { result: RESULT } });
    }
    assert.deepEqual(schema, SCHEMA, 'DeepRead schema properties and required stay at the root');
  });
}

test('DeepRead plaintext planning keeps SSE for stream:false and does not echo input history', async () => {
  const recorded = capture();
  let starts = 0;
  const client = createChatDeepReadAiClient({
    api: fixtures.responses.makeApi(scriptedHttp([responsesText('{"queries":["来源"]}')], recorded)),
    params: params(), onRequestStart: (): void => { starts += 1; },
  });
  const output = await client.generateText({ ...requestParams(), maxSteps: 1, stream: false });
  assert.equal(recorded.nonStreamingCalls, 0);
  assert.equal(recorded.requests.length, 1);
  assert.equal(starts, 1);
  assert.equal(JSON.parse(recorded.requests[0].body ?? '{}').stream, true);
  const assistant = output.filter((message) => message.role === 'assistant');
  assert.equal(assistant.length, 1);
  assert.equal(assistant[0].parts[0].type === 'text' ? assistant[0].parts[0].text : '', '{"queries":["来源"]}');
});

test('DeepRead cancellation passes the same signal to HTTP and rejects a partial result', async () => {
  const recorded = capture();
  const controller = new AbortController();
  const snapshots: UIMessage[][] = [];
  const client = createChatDeepReadAiClient({
    api: fixtures.chat.makeApi(scriptedHttp([chatText('部分内容')], recorded, (): void => controller.abort())),
    params: params(), onRawSnapshot: (messages): void => { snapshots.push(messages); },
  });
  await assert.rejects(
    client.generateText({ ...requestParams(), signal: controller.signal }),
    (error: unknown): boolean => error instanceof Error && error.name === 'AbortError',
  );
  assert.equal(recorded.signals[0], controller.signal);
  assert.ok(snapshots.some((messages) => messages.some((message) => message.parts
    .some((part) => part.type === 'text' && part.text === '部分内容'))));
});

test('DeepRead explicit non-auto-approvable writer pauses without executing', async () => {
  const recorded = capture();
  const calls: string[] = [];
  const client = createChatDeepReadAiClient({
    api: fixtures.chat.makeApi(scriptedHttp([chatTool()], recorded)), params: params(),
  });
  const output = await client.generateText({
    ...requestParams([writerTool(calls, { allowsAutoApproval: false })]), autoApproveTools: false,
  });
  assert.deepEqual(calls, []);
  assert.equal(recorded.requests.length, 1);
  assert.equal(toolsIn(output)[0].approvalState.type, 'pending');
  assert.deepEqual(toolsIn(output)[0].output, []);
});

test('DeepRead rejects a non-auto-approvable writer in unattended mode before model or writer execution', async () => {
  const recorded = capture();
  const calls: string[] = [];
  const client = createChatDeepReadAiClient({
    api: fixtures.chat.makeApi(scriptedHttp([chatTool(), chatText('完成')], recorded)), params: params(),
  });
  await assert.rejects(client.generateText({
    ...requestParams([writerTool(calls, { allowsAutoApproval: false })]),
    autoApproveHighRiskTools: true,
  }), /深读工具不允许自动批准/);
  assert.deepEqual(calls, []);
  assert.equal(recorded.requests.length, 0);
});

test('DeepRead high-risk writer is not approved by the per-run name whitelist', async () => {
  const recorded = capture();
  const calls: string[] = [];
  const client = createChatDeepReadAiClient({
    api: fixtures.chat.makeApi(scriptedHttp([chatTool()], recorded)), params: params(),
  });
  const output = await client.generateText({
    ...requestParams([writerTool(calls, { isHighRisk: true })]),
    autoApproveHighRiskTools: false, autoApprovedToolNames: [WRITER],
  });
  assert.deepEqual(calls, []);
  assert.equal(toolsIn(output)[0].approvalState.type, 'pending');
});

test('DeepRead explicitly approved high-risk writer executes through the same tool loop', async () => {
  const recorded = capture();
  const calls: string[] = [];
  const client = createChatDeepReadAiClient({
    api: fixtures.chat.makeApi(scriptedHttp([chatTool(), chatText('完成')], recorded)), params: params(),
  });
  const output = await client.generateText({
    ...requestParams([writerTool(calls, { isHighRisk: true })]), autoApproveHighRiskTools: true,
  });
  assert.equal(calls.length, 1);
  assert.deepEqual(toolsIn(output)[0].output, [{ type: 'text', text: RESULT, metadata: null }]);
});

test('DeepRead keeps a run snapshot of model capabilities and nested custom body values', async () => {
  const recorded = capture();
  const original = params();
  const client = createChatDeepReadAiClient({
    api: fixtures.chat.makeApi(scriptedHttp([chatText('规划结果')], recorded)), params: original,
  });
  original.model.modelId = 'changed-after-start';
  original.model.abilities.length = 0;
  original.customBody[0].value = { kept: false };
  await client.generateText({ ...requestParams(), maxSteps: 1 });
  const body = JSON.parse(recorded.requests[0].body ?? '{}');
  assert.equal(body.model, 'fixture-model');
  assert.deepEqual(body.fixture_custom, { kept: true });
});

test('DeepRead rejects missing tool capability before starting an HTTP request', async () => {
  const recorded = capture();
  const generation = params();
  generation.model.abilities = [];
  const calls: string[] = [];
  const client = createChatDeepReadAiClient({
    api: fixtures.chat.makeApi(scriptedHttp([], recorded)), params: generation,
  });
  await assert.rejects(client.generateText(requestParams([writerTool(calls)])), /工具/);
  assert.equal(recorded.requests.length, 0);
  assert.deepEqual(calls, []);
});

for (const mode of ['writer', 'plaintext'] as const) {
  test(`DeepRead Responses ${mode} excludes inherited Chat builtin search/image generation`, async () => {
    const recorded = capture();
    const generation = params();
    generation.model.tools = ['search', 'image_generation'];
    const calls: string[] = [];
    const writer = mode === 'writer';
    const client = createChatDeepReadAiClient({
      api: fixtures.responses.makeApi(scriptedHttp(
        writer ? [responsesTool(), responsesText('完成')] : [responsesText('翻译结果')], recorded,
      )), params: generation,
    });
    await client.generateText({
      ...requestParams(writer ? [writerTool(calls)] : undefined), maxSteps: writer ? 4 : 1,
    });
    const body = JSON.parse(recorded.requests[0].body ?? '{}');
    const wireTools = body.tools ?? [];
    assert.deepEqual(wireTools.map((tool: { type: string; name?: string }) => ({ type: tool.type, name: tool.name })),
      writer ? [{ type: 'function', name: WRITER }] : []);
    assert.deepEqual(generation.model.tools, ['search', 'image_generation'], 'Only the DeepRead run copy removes inherited builtin tools');
    assert.deepEqual(body.fixture_custom, { kept: true });
    assert.equal(body.model, 'fixture-model');
    assert.equal(body.temperature, 0.3);
    assert.equal(body.max_output_tokens, 4096);
    assert.equal(calls.length, writer ? 1 : 0);
  });
}

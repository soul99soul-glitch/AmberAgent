import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { MessageChunk } from '../main/ets/chat/message.ts';
import { makeUIMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import { asResponsesChatStreamProvider, createOpenAIResponsesApi } from '../main/ets/chat/openai_responses_api.ts';
import { parseResponsesOutput, parseResponsesStreamEvent, ResponseStreamReconciler } from '../main/ets/chat/openai_responses_parse.ts';
import { MessageStreamAccumulator } from '../main/ets/chat/stream_accumulator.ts';
import { makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { runChatTurnWithTools } from '../main/ets/chat/tool_loop.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import { makeConversation } from '../main/ets/chat/conversation.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import { makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';
import { buildResponsesInput } from '../main/ets/chat/openai_responses_request.ts';

const params = makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-test' }) });
const setting = makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true });
const messages = [makeUserMessage('Hello')];
const output: JsonObject[] = [
  { type: 'message', content: [{ type: 'output_text', text: 'Partial reply' }] },
  { type: 'function_call', call_id: 'call_failed', name: 'write_file', arguments: '{}' },
];
const textOf = (chunk: MessageChunk): string => chunk.choices
  .flatMap((choice) => (choice.message ?? choice.delta)?.parts ?? [])
  .filter((part) => part.type === 'text').map((part) => part.text).join('');

test('Responses native opaque reasoning survives output parsing and reconciliation', () => {
  const response: JsonObject = {
    status: 'completed', output: [
      { type: 'reasoning', id: 'rs_native', encrypted_content: 'opaque', summary: [] },
      output[0],
    ],
  };
  const generated = parseResponsesOutput(response);
  const reasoning = generated.choices[0].message?.parts.find((part) => part.type === 'reasoning');
  assert.equal(reasoning?.metadata?.reasoning_id, 'rs_native');
  assert.equal(reasoning?.metadata?.encrypted_content, 'opaque');
  const reconciler = new ResponseStreamReconciler();
  const accumulator = new MessageStreamAccumulator(messages);
  accumulator.append(reconciler.reconcile(generated));
  const saved = accumulator.snapshot()[1].parts.find((part) => part.type === 'reasoning');
  assert.equal(saved?.metadata?.encrypted_content, 'opaque');
});

test('Responses final opaque reasoning enriches streamed summary without duplicating visible reasoning', () => {
  const reconciler = new ResponseStreamReconciler();
  const accumulator = new MessageStreamAccumulator(messages);
  const summary = parseResponsesStreamEvent({
    type: 'response.reasoning_summary_text.delta', item_id: 'rs_native', delta: 'Planning',
  });
  assert.ok(summary);
  accumulator.append(reconciler.reconcile(summary));
  accumulator.append(reconciler.reconcile(parseResponsesOutput({
    status: 'completed', output: [
      { type: 'reasoning', id: 'rs_native', encrypted_content: 'opaque', summary: [{ type: 'summary_text', text: 'Planning' }] },
      output[0],
    ],
  })));
  const reasoning = accumulator.snapshot()[1].parts.filter((part) => part.type === 'reasoning');
  assert.equal(reasoning.map((part) => part.reasoning).join(''), 'Planning');
  assert.equal(reasoning.find((part) => part.metadata?.reasoning_id === 'rs_native')?.metadata?.encrypted_content, 'opaque');
});

test('Responses reasoning identity preserves unstreamed final blocks beside a streamed block', () => {
  const reconciler = new ResponseStreamReconciler();
  const summary = parseResponsesStreamEvent({
    type: 'response.reasoning_summary_text.delta', item_id: 'rs_1', delta: 'First',
  });
  assert.ok(summary);
  reconciler.reconcile(summary);
  const final = reconciler.reconcile(parseResponsesOutput({
    status: 'completed', output: [
      { type: 'reasoning', id: 'rs_1', summary: [{ type: 'summary_text', text: 'First' }] },
      { type: 'reasoning', id: 'rs_2', summary: [{ type: 'summary_text', text: 'Second' }] },
    ],
  }));
  const reasoning = final.choices[0].delta?.parts.filter((part) => part.type === 'reasoning') ?? [];
  assert.equal(reasoning.find((part) => part.metadata?.reasoning_id === 'rs_1')?.reasoning, '');
  assert.equal(reasoning.find((part) => part.metadata?.reasoning_id === 'rs_2')?.reasoning, 'Second');
});

test('Responses HTTP 200 failed rejects with provider message and displayable partial without tools', async () => {
  const api = createOpenAIResponsesApi({
    http: {
      fetch: async () => ({ status: 200, headers: {}, body: JSON.stringify({
        status: 'failed', error: { message: 'upstream unavailable' }, output,
      }) }),
      fetchStream: async () => ({ status: 200, headers: {}, body: '' }),
    }, setting,
  });
  await assert.rejects(() => api.generateText(messages, params), (error: unknown) => {
    assert.ok(error instanceof Error);
    assert.match(error.message, /upstream unavailable/);
    const partial = (error as Error & { partialChunk: MessageChunk | null }).partialChunk;
    assert.ok(partial);
    assert.equal(textOf(partial), 'Partial reply');
    assert.equal(partial.choices[0].message?.parts.some((part) => part.type === 'tool'), false);
    return true;
  });
});

test('Responses output parser rejects content_filter and unknown incomplete reasons', () => {
  for (const reason of ['content_filter', 'unknown_failure']) {
    assert.throws(() => parseResponsesOutput({
      status: 'incomplete', incomplete_details: { reason }, output,
    }), new RegExp(reason));
  }
  assert.throws(() => parseResponsesOutput({ status: 'incomplete', output }), /incomplete/);
});

test('Responses legal output cap retains text and existing finishReason in both paths', () => {
  const response: JsonObject = {
    status: 'incomplete', incomplete_details: { reason: 'max_output_tokens' }, output,
  };
  const generated = parseResponsesOutput(response);
  const streamed = parseResponsesStreamEvent({ type: 'response.incomplete', response });
  assert.equal(textOf(generated), 'Partial reply');
  assert.equal(generated.choices[0].finishReason, 'max_output_tokens');
  assert.ok(streamed);
  assert.equal(streamed.choices[0].finishReason, 'max_output_tokens');
});

test('Responses stream content_filter rejects, keeps previously delivered text and never publishes final tools', async () => {
  const chunks: MessageChunk[] = [];
  let stopped = false;
  const events = [
    { type: 'response.output_text.delta', item_id: 'msg_1', delta: 'Partial reply' },
    { type: 'response.incomplete', response: {
      status: 'incomplete', incomplete_details: { reason: 'content_filter' }, output,
    } },
    { type: 'response.output_text.delta', item_id: 'msg_1', delta: 'ignored after failure' },
  ];
  const api = createOpenAIResponsesApi({
    http: {
      fetch: async () => ({ status: 200, headers: {}, body: '' }),
      fetchStream: async (_request, opts) => {
        const sse = events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join('');
        opts.onChunk(new TextEncoder().encode(sse).buffer as ArrayBuffer, true);
        stopped = opts.shouldStop?.() ?? false;
        return { status: 200, headers: {}, body: '' };
      },
    }, setting,
  });
  await assert.rejects(() => api.streamText(messages, params, (chunk) => { chunks.push(chunk); }), /content_filter/);
  assert.equal(chunks.map(textOf).join(''), 'Partial reply');
  assert.equal(chunks.flatMap((chunk) => chunk.choices).some((choice) =>
    (choice.delta ?? choice.message)?.parts.some((part) => part.type === 'tool')), false);
  assert.equal(stopped, true);
});

test('Responses stream failure preserves nested provider error and incomplete event checks missing status', () => {
  assert.throws(() => parseResponsesStreamEvent({
    type: 'response.failed', response: { error: { message: 'upstream unavailable' } },
  }), /upstream unavailable/);
  assert.throws(() => parseResponsesStreamEvent({
    type: 'response.incomplete', response: { incomplete_details: { reason: 'content_filter' }, output },
  }), /content_filter/);
});

test('Responses JSON-only terminal completion stops stream before trailing chunks', async () => {
  const chunks: MessageChunk[] = [];
  let stopped = false;
  const api = createOpenAIResponsesApi({
    http: {
      fetch: async () => ({ status: 200, headers: {}, body: '' }),
      fetchStream: async (_request, opts) => {
        const sse = [
          { type: 'response.completed', response: { status: 'completed', output: [output[0]] } },
          { type: 'response.output_text.delta', item_id: 'msg_1', delta: 'unexpected tail' },
        ].map((event) => `data: ${JSON.stringify(event)}\n\n`).join('');
        opts.onChunk(new TextEncoder().encode(sse).buffer as ArrayBuffer, true);
        stopped = opts.shouldStop?.() ?? false;
        return { status: 200, headers: {}, body: '' };
      },
    }, setting,
  });
  await api.streamText(messages, params, (chunk) => { chunks.push(chunk); });
  assert.equal(chunks.map(textOf).join(''), 'Partial reply');
  assert.equal(stopped, true);
});

test('Responses failed stream never executes speculative tools in the real tool loop', async () => {
  let executions = 0;
  const api = createOpenAIResponsesApi({
    setting,
    http: {
      fetch: async () => ({ status: 200, headers: {}, body: '' }),
      fetchStream: async (_request, opts) => {
        const events: JsonObject[] = [
          { type: 'response.output_item.added', item: {
            type: 'function_call', id: 'fc_1', call_id: 'call_1', name: 'spec_ro_tool', arguments: '{}',
          } },
          { type: 'response.output_text.delta', item_id: 'msg_1', delta: 'Partial reply' },
          { type: 'response.failed', response: { status: 'failed', error: { message: 'upstream failed' } } },
        ];
        for (const event of events) {
          opts.onChunk(new TextEncoder().encode(`data: ${JSON.stringify(event)}\n\n`).buffer as ArrayBuffer, false);
          await Promise.resolve();
        }
        return { status: 200, headers: {}, body: '' };
      },
    },
  });
  const provider = asResponsesChatStreamProvider(api, params);
  await assert.rejects(() => runChatTurnWithTools(makeConversation('failed-spec', []), 'Hello', {
    assistant: makeAssistant({}), provider, inputTransformers: [], outputTransformers: [],
    store: createMemoryConversationStore(), flushIntervalMs: 0,
    retrySetting: makeGenerationRetrySetting({ enabled: false }),
  }, {
    tools: [makeAgentTool({ name: 'spec_ro_tool', description: 'read only', execute: async () => {
      executions += 1;
      return [{ type: 'text', text: 'read result', metadata: null }];
    } })],
    makeProviderForStep: () => provider,
    speculativeEnabled: true,
  }), /upstream failed/);
  assert.equal(executions, 0);
});

test('Responses failed terminal preserves envelope-only text and reconciles overlapping partial text', async () => {
  for (const prefix of ['', 'Partial ']) {
    const chunks: MessageChunk[] = [];
    const api = createOpenAIResponsesApi({ setting, http: {
      fetch: async () => ({ status: 200, headers: {}, body: '' }),
      fetchStream: async (_request, opts) => {
        const events: JsonObject[] = [];
        if (prefix.length > 0) events.push({ type: 'response.output_text.delta', item_id: 'msg_1', delta: prefix });
        events.push({ type: 'response.failed', response: {
          status: 'failed', error: { message: 'blocked' }, output,
        } });
        opts.onChunk(new TextEncoder().encode(events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join('')).buffer as ArrayBuffer, true);
        return { status: 200, headers: {}, body: '' };
      },
    } });
    await assert.rejects(() => api.streamText(messages, params, (chunk) => { chunks.push(chunk); }), /blocked/);
    assert.equal(chunks.map(textOf).join(''), 'Partial reply');
    assert.equal(chunks.flatMap((chunk) => chunk.choices).some((choice) =>
      (choice.delta ?? choice.message)?.parts.some((part) => part.type === 'tool')), false);
  }
});

test('Responses successful stream publishes each completed call once, after transport success, preserving wire IDs', async () => {
  const chunks: MessageChunk[] = [];
  let beforeSuccessTools = 0;
  const api = createOpenAIResponsesApi({ setting, http: {
    fetch: async () => ({ status: 200, headers: {}, body: '' }),
    fetchStream: async (_request, opts) => {
      const calls: JsonObject[] = [1, 2].map((index) => ({
        type: 'function_call', id: `fc_${index}`, call_id: `wire_${index}`, name: 'spec_ro_tool', arguments: `{"n":${index}}`,
      }));
      const events: JsonObject[] = calls.map((item) => ({ type: 'response.output_item.added', item }));
      events.push({ type: 'response.completed', response: { status: 'completed', output: calls } });
      opts.onChunk(new TextEncoder().encode(events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join('')).buffer as ArrayBuffer, true);
      beforeSuccessTools = chunks.flatMap((chunk) => chunk.choices).flatMap((choice) =>
        (choice.delta ?? choice.message)?.parts ?? []).filter((part) => part.type === 'tool').length;
      return { status: 200, headers: {}, body: '' };
    },
  } });
  const accumulator = new MessageStreamAccumulator(messages);
  await api.streamText(messages, params, (chunk) => { chunks.push(chunk); accumulator.append(chunk); });
  assert.equal(beforeSuccessTools, 0);
  const tools = accumulator.snapshot()[1].parts.filter((part) => part.type === 'tool');
  assert.equal(tools.length, 2);
  assert.deepEqual(tools.map((part) => part.metadata?.openai_call_id), ['wire_1', 'wire_2']);
  assert.deepEqual(tools.map((part) => part.input), ['{"n":1}', '{"n":2}']);
});

test('Responses cancellation or HTTP failure after completed envelope never publishes buffered tools', async () => {
  for (const cancelled of [false, true]) {
    const signal = { aborted: false };
    const chunks: MessageChunk[] = [];
    const api = createOpenAIResponsesApi({ setting, http: {
      fetch: async () => ({ status: 200, headers: {}, body: '' }),
      fetchStream: async (_request, opts) => {
        opts.onChunk(new TextEncoder().encode(`data: ${JSON.stringify({
          type: 'response.completed', response: { status: 'completed', output: [output[1]] },
        })}\n\n`).buffer as ArrayBuffer, true);
        signal.aborted = cancelled;
        return { status: cancelled ? 200 : 503, headers: {}, body: 'unavailable' };
      },
    } });
    await assert.rejects(() => api.streamText(messages, params, (chunk) => { chunks.push(chunk); }, { signal }));
    assert.equal(chunks.flatMap((chunk) => chunk.choices).some((choice) =>
      (choice.delta ?? choice.message)?.parts.some((part) => part.type === 'tool')), false);
  }
});

test('Responses fragmented arguments and terminal-only call ID produce one complete replayable call', async () => {
  const api = createOpenAIResponsesApi({ setting, http: {
    fetch: async () => ({ status: 200, headers: {}, body: '' }),
    fetchStream: async (_request, opts) => {
      const events: JsonObject[] = [
        { type: 'response.output_item.added', item: { type: 'function_call', id: 'fc_1', name: 'spec_ro_tool', arguments: '' } },
        { type: 'response.function_call_arguments.delta', item_id: 'fc_1', delta: '{"q":' },
        { type: 'response.function_call_arguments.delta', item_id: 'fc_1', delta: '1}' },
        { type: 'response.function_call_arguments.done', item_id: 'fc_1', arguments: '{"q":1}' },
        { type: 'response.completed', response: { status: 'completed', output: [
          { type: 'function_call', id: 'fc_1', call_id: 'wire_final', name: 'spec_ro_tool', arguments: '{"q":1}' },
        ] } },
      ];
      opts.onChunk(new TextEncoder().encode(events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join('')).buffer as ArrayBuffer, true);
      return { status: 200, headers: {}, body: '' };
    },
  } });
  const accumulator = new MessageStreamAccumulator(messages);
  await api.streamText(messages, params, (chunk) => { accumulator.append(chunk); });
  const tools = accumulator.snapshot()[1].parts.filter((part) => part.type === 'tool');
  assert.equal(tools.length, 1);
  assert.equal(tools[0].input, '{"q":1}');
  assert.equal(tools[0].metadata?.openai_call_id, 'wire_final');
  const completed = makeUIMessage('assistant', tools.map((tool) => ({ ...tool,
    output: [{ type: 'text' as const, text: 'read result', metadata: null }] })));
  const replayCall = buildResponsesInput([...messages, completed]).find((item) =>
    typeof item === 'object' && item !== null && !Array.isArray(item) && item['type'] === 'function_call');
  assert.ok(typeof replayCall === 'object' && replayCall !== null && !Array.isArray(replayCall));
  assert.equal((replayCall as JsonObject)['call_id'], 'wire_final');
});

test('Responses EOF before successful terminal rejects and discards calls', async () => {
  const chunks: MessageChunk[] = [];
  const api = createOpenAIResponsesApi({ setting, http: {
    fetch: async () => ({ status: 200, headers: {}, body: '' }),
    fetchStream: async (_request, opts) => {
      const event = { type: 'response.output_item.added', item: {
        type: 'function_call', id: 'fc_1', call_id: 'wire_1', name: 'spec_ro_tool', arguments: '{}',
      } };
      opts.onChunk(new TextEncoder().encode(`data: ${JSON.stringify(event)}\n\n`).buffer as ArrayBuffer, true);
      return { status: 200, headers: {}, body: '' };
    },
  } });
  await assert.rejects(() => api.streamText(messages, params, (chunk) => { chunks.push(chunk); }), /before terminal/);
  assert.equal(chunks.flatMap((chunk) => chunk.choices).some((choice) =>
    (choice.delta ?? choice.message)?.parts.some((part) => part.type === 'tool')), false);
});

import { test } from 'node:test';
import assert from 'node:assert/strict';

import type { UIMessage, UIMessagePartTool } from '../main/ets/chat/message.ts';
import {
  annotateSmartUiWeatherBindings, projectWeatherReadTool, projectedField, resolveWeatherBinding,
  weatherBindingDescriptor, weatherBindingRef, SMART_UI_WEATHER_PATHS,
} from '../main/ets/chat/smart_ui/binding.ts';
import type { SmartUiToolSource } from '../main/ets/chat/smart_ui/binding.ts';

const source: SmartUiToolSource = {
  conversationId: 'conv-1', nodeId: 'node-1', messageId: 'message-1', toolCallId: 'call-1',
};

const tool = (text: string, toolCallId: string = source.toolCallId, toolName: string = 'weather_read'): UIMessagePartTool => ({
  type: 'tool', toolCallId, toolName, input: '{"location":"北京"}',
  output: [{ type: 'text', text, metadata: null }], approvalState: { type: 'auto' }, metadata: null,
});

test('weather binding ref and descriptor are bounded, deterministic, and allowlist-only', () => {
  assert.equal(weatherBindingRef('call-1'), 'weather_call-1');
  assert.notEqual(weatherBindingRef('call.a'), weatherBindingRef('call_a'));
  assert.match(weatherBindingRef('x'.repeat(200)), /^weather_[A-Za-z0-9_-]{1,40}$/);
  assert.deepEqual(weatherBindingDescriptor('call-1'), {
    ref: 'weather_call-1', source: 'tool', toolName: 'weather_read', paths: [...SMART_UI_WEATHER_PATHS],
  });
});

test('projection maps only bounded weather scalar fields from actual tool output', () => {
  const projection = projectWeatherReadTool(tool(JSON.stringify({
    ok: true, tool: 'weather_read', location: '北京, 中国', temperature_celsius: 21.5,
    condition: '多云', observed_at: '2026-10-09T06:30:00.000Z',
    humidity_percent: 45, forecast: [{ date: '2026-10-10', high_celsius: 19 }],
    secret: 'must not be projected',
  })), source);
  assert.equal(projection?.status, 'ready');
  assert.equal(projectedField(projection!, 'location'), '北京, 中国');
  assert.equal(projectedField(projection!, 'temperature_celsius'), 21.5);
  assert.equal(projectedField(projection!, 'condition'), '多云');
  assert.equal(projectedField(projection!, 'observed_at'), '2026-10-09T06:30:00.000Z');
  assert.equal(resolveWeatherBinding(projection!, projection!.ref, 'location'), '北京, 中国');
  assert.equal(resolveWeatherBinding(projection!, projection!.ref, 'temperature_celsius'), 21.5);
  assert.equal(resolveWeatherBinding(projection!, 'weather_other', 'location'), null);
  assert.equal(projection?.fields.length, 4);
});

test('missing, failed, malformed, and mismatched tool results produce placeholders only', () => {
  const pending: UIMessagePartTool = { ...tool('', source.toolCallId), output: [] };
  assert.equal(projectWeatherReadTool(pending, source)?.status, 'missing');
  assert.equal(projectedField(projectWeatherReadTool(pending, source)!, 'location'), null);
  const failed = projectWeatherReadTool(tool(JSON.stringify({ ok: false, tool: 'weather_read', reason: 'network' })), source);
  assert.equal(failed?.status, 'failed');
  assert.equal(projectedField(failed!, 'condition'), null);
  const malformed = projectWeatherReadTool(tool('{"ok":true'), source);
  assert.equal(malformed?.status, 'failed');
  assert.equal(projectWeatherReadTool(tool('{"ok":true,"tool":"weather_read"}'), {
    ...source, toolCallId: 'other',
  }), null);
  assert.equal(projectWeatherReadTool(tool('{"ok":true,"tool":"other"}'), source)?.status, 'failed');
  assert.equal(projectWeatherReadTool(tool('{"ok":true,"tool":"weather_read"}', source.toolCallId, 'other'), source), null);
});

test('missing allowed weather fields stay null instead of being synthesized', () => {
  const projection = projectWeatherReadTool(tool(JSON.stringify({
    ok: true, tool: 'weather_read', location: '北京', condition: '晴',
  })), source);
  assert.equal(projection?.status, 'ready');
  assert.equal(projectedField(projection!, 'temperature_celsius'), null);
  assert.equal(projectedField(projection!, 'observed_at'), null);
});

test('prepared-context annotation keeps tool output parseable and leaves persisted message untouched', () => {
  const originalTool: UIMessagePartTool = tool(JSON.stringify({
    ok: true, tool: 'weather_read', location: '北京', temperature_celsius: 21,
    condition: '晴', observed_at: '2026-10-09T06:30:00.000Z',
  }));
  const message: UIMessage = {
    id: 'assistant-1', role: 'assistant', parts: [originalTool], annotations: [],
    createdAt: '2026-10-09T06:30:00.000Z', finishedAt: null, modelId: null, usage: null, translation: null,
  };
  const prepared: UIMessage[] = annotateSmartUiWeatherBindings([message]);
  const preparedTool: UIMessagePartTool = prepared[0].parts[0] as UIMessagePartTool;
  assert.notEqual(prepared[0], message);
  assert.notEqual(preparedTool, originalTool);
  assert.equal(JSON.stringify(originalTool.output), JSON.stringify(message.parts[0].type === 'tool' ? message.parts[0].output : []));
  const originalPayload = JSON.parse((originalTool.output[0] as { text: string }).text) as Record<string, unknown>;
  const preparedPayload = JSON.parse((preparedTool.output[0] as { text: string }).text) as Record<string, unknown>;
  assert.equal(originalPayload['_ui'], undefined);
  assert.deepEqual(preparedPayload['_ui'], {
    ref: 'weather_call-1', source: 'tool', toolName: 'weather_read', paths: [...SMART_UI_WEATHER_PATHS],
  });
  const projection = projectWeatherReadTool(preparedTool, source);
  assert.equal(projection?.status, 'ready');
  assert.equal(projectedField(projection!, 'temperature_celsius'), 21);
  const twice: UIMessage[] = annotateSmartUiWeatherBindings(prepared);
  assert.equal(JSON.stringify((twice[0].parts[0] as UIMessagePartTool).output), JSON.stringify(preparedTool.output));
});

test('prepared annotation repairs a descriptor to the fixed path allowlist', () => {
  const tampered = tool(JSON.stringify({
    ok: true, tool: 'weather_read', location: '北京', temperature_celsius: 21,
    condition: '晴', observed_at: '2026-10-09T06:30:00.000Z',
    _ui: { ref: 'weather_call-1', source: 'tool', toolName: 'weather_read', paths: ['secret'] },
  }));
  const prepared = annotateSmartUiWeatherBindings([{
    id: 'assistant-2', role: 'assistant', parts: [tampered], annotations: [],
    createdAt: '2026-10-09T06:30:00.000Z', finishedAt: null, modelId: null, usage: null, translation: null,
  }]);
  const payload = JSON.parse(((prepared[0].parts[0] as UIMessagePartTool).output[0] as { text: string }).text) as Record<string, unknown>;
  assert.deepEqual((payload['_ui'] as Record<string, unknown>)['paths'], [...SMART_UI_WEATHER_PATHS]);
});


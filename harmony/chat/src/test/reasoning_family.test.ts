// reasoning_family 纯逻辑测试
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { makeProviderModel } from '../main/ets/chat/provider_settings.ts';
import { makeProviderSettingOpenAIVariant, makeProviderSettingGoogle } from '../main/ets/chat/provider_settings.ts';
import {
  reasoningFamilyOf,
  reasoningOptionsForModel,
  coerceToReasoningOptions,
  providerRoutingKey,
} from '../main/ets/chat/reasoning_family.ts';

const model = (modelId: string, abilities: string[] = ['reasoning']) =>
  makeProviderModel({ modelId, type: 'chat', abilities: abilities as any });

// ===== reasoningFamilyOf =====

test('claude opus 4-7 → CLAUDE_OPUS_47', () => {
  assert.equal(reasoningFamilyOf(model('claude-opus-4-7'), null), 'CLAUDE_OPUS_47');
});

test('claude opus 4-5 → CLAUDE_MAX', () => {
  assert.equal(reasoningFamilyOf(model('claude-opus-4-5'), null), 'CLAUDE_MAX');
});

test('claude mythos → CLAUDE_MAX', () => {
  assert.equal(reasoningFamilyOf(model('claude-mythos-mimir'), null), 'CLAUDE_MAX');
});

test('claude sonnet 4-6 → CLAUDE_HIGH', () => {
  assert.equal(reasoningFamilyOf(model('claude-sonnet-4-6'), null), 'CLAUDE_HIGH');
});

test('deepseek → DEEPSEEK', () => {
  assert.equal(reasoningFamilyOf(model('deepseek-chat'), null), 'DEEPSEEK');
});

test('kimi → BINARY', () => {
  assert.equal(reasoningFamilyOf(model('kimi-k2'), null), 'BINARY');
});

test('glm → BINARY', () => {
  assert.equal(reasoningFamilyOf(model('glm-4-plus'), null), 'BINARY');
});

test('gpt-5 → OPENAI', () => {
  assert.equal(reasoningFamilyOf(model('gpt-5'), null), 'OPENAI');
});

test('o3 → OPENAI', () => {
  assert.equal(reasoningFamilyOf(model('o3-mini'), null), 'OPENAI');
});

test('gemini(provider=google)→ GEMINI', () => {
  const g = makeProviderSettingGoogle({ apiKey: 'sk' });
  assert.equal(reasoningFamilyOf(model('gemini-2-pro'), g), 'GEMINI');
});

test('无 reasoning 能力 → NONE', () => {
  assert.equal(reasoningFamilyOf(model('gpt-3.5-turbo', []), null), 'NONE');
});

test('qwen plus 3.5 → BINARY', () => {
  assert.equal(reasoningFamilyOf(model('qwen-plus-3.5'), null), 'BINARY');
});

test('CLAUDE_OPUS_47 → 7 档(含 max)', () => {
  const opts = reasoningOptionsForModel(model('claude-opus-4-7'), null);
  assert.equal(opts.length, 7);
  assert.equal(opts[opts.length - 1].level, 'max');
});

test('coerce: auto 不在 DEEPSEEK 选项 → 降级 medium 或 first', () => {
  const opts = reasoningOptionsForModel(model('deepseek-chat'), null);
  const result = coerceToReasoningOptions('auto', opts);
  assert.ok(['off', 'high', 'max'].includes(result));
});

test('coerce: xhigh + 无 xhigh 但有 max → max', () => {
  const opts = reasoningOptionsForModel(model('claude-opus-4-5'), null);
  // CLAUDE_MAX 无 xhigh 但有 max → xhigh coerce 到 max
  assert.equal(coerceToReasoningOptions('xhigh', opts), 'max');
});

test('routingKey: openai deepseek endpoint → deepseek', () => {
  const o = makeProviderSettingOpenAIVariant({ apiKey: 'sk', baseUrl: 'https://api.deepseek.com/v1' });
  assert.equal(providerRoutingKey(o), 'deepseek');
});

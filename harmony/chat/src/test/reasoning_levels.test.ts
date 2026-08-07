// reasoning_levels + withReasoningLevelForModel 纯逻辑测试
//
// 覆盖:
//   reasoningLevelsForModel — deepseek/claude/gpt/kimi-glm/默认 段集 + 大小写 + label
//   withReasoningLevelForModel — 记忆更新 + reasoningLevel 同步 + 不变性

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { makeProviderModel } from '../main/ets/chat/provider_settings.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import {
  reasoningLevelForModel, withReasoningLevelForModel,
} from '../main/ets/chat/assistant.ts';
import {
  reasoningLevelsForModel, ReasoningLevelOption,
} from '../main/ets/chat/reasoning_levels.ts';

const chatModel = (modelId: string): ReturnType<typeof makeProviderModel> =>
  makeProviderModel({ modelId, type: 'chat', abilities: ['reasoning'] });

// ===== reasoningLevelsForModel 段集 =====

test('deepseek → off/high/max(3 段)', () => {
  const levels = reasoningLevelsForModel(chatModel('deepseek-chat'));
  assert.deepEqual(levels.map((o) => o.level), ['off', 'high', 'max']);
});

test('没有 reasoning ability 时不显示推理档位', () => {
  assert.deepEqual(reasoningLevelsForModel(makeProviderModel({ modelId: 'deepseek-chat', type: 'chat' })), []);
});

test('deepseek 大小写不敏感', () => {
  const levels = reasoningLevelsForModel(chatModel('DeepSeek-Reasoner'));
  assert.equal(levels.length, 3);
  assert.equal(levels[0].level, 'off');
});

test('claude → auto/low/med/high/xhigh/max(6 段,首段 auto)', () => {
  const levels = reasoningLevelsForModel(chatModel('claude-sonnet-4-5'));
  assert.deepEqual(levels.map((o) => o.level), ['auto', 'low', 'medium', 'high', 'xhigh', 'max']);
  assert.equal(levels[0].level, 'auto');
});

test('gpt → low/med/high/xhigh(4 段,无 off)', () => {
  const levels = reasoningLevelsForModel(chatModel('gpt-5'));
  assert.deepEqual(levels.map((o) => o.level), ['low', 'medium', 'high', 'xhigh']);
});

test('o-系列 → 同 gpt 段集', () => {
  for (const id of ['o1-preview', 'o3-mini', 'o4-high']) {
    const levels = reasoningLevelsForModel(chatModel(id));
    assert.equal(levels.length, 4);
    assert.equal(levels[0].level, 'low');
  }
});

test('codex → 同 gpt 段集', () => {
  const levels = reasoningLevelsForModel(chatModel('codex-latest'));
  assert.equal(levels.length, 4);
});

test('kimi → off/auto(2 段)', () => {
  const levels = reasoningLevelsForModel(chatModel('kimi-k2'));
  assert.deepEqual(levels.map((o) => o.level), ['off', 'auto']);
});

test('glm/zhipu → 同 kimi 段集', () => {
  assert.deepEqual(
    reasoningLevelsForModel(chatModel('glm-4')).map((o) => o.level),
    ['off', 'auto'],
  );
  assert.deepEqual(
    reasoningLevelsForModel(chatModel('zhipu-glm')).map((o) => o.level),
    ['off', 'auto'],
  );
});

test('默认(未知模型) → off/auto(2 段)', () => {
  const levels = reasoningLevelsForModel(chatModel('unknown-model'));
  assert.deepEqual(levels.map((o) => o.level), ['off', 'auto']);
});

test('label 正确:medium → med 缩写', () => {
  const claude = reasoningLevelsForModel(chatModel('claude'));
  const med = claude.find((o) => o.level === 'medium');
  assert.equal(med?.label, 'med');
});

test('返回值不可变性:修改返回数组不影响后续调用', () => {
  const a = reasoningLevelsForModel(chatModel('gpt-5'));
  a.push({ level: 'max', label: 'x' });
  const b = reasoningLevelsForModel(chatModel('gpt-5'));
  assert.equal(b.length, 4);
});

// ===== withReasoningLevelForModel 写侧 =====

test('withReasoningLevelForModel:更新记忆 map + reasoningLevel', () => {
  const a = makeAssistant({ reasoningLevel: 'auto' });
  const updated = withReasoningLevelForModel(a, 'model-1', 'high');
  assert.equal(updated.reasoningLevel, 'high');
  assert.equal(updated.rememberedReasoningLevelsByModelId['model-1'], 'high');
});

test('withReasoningLevelForModel:保留已有其他模型的记忆', () => {
  const a = makeAssistant({
    reasoningLevel: 'low',
    rememberedReasoningLevelsByModelId: { 'model-a': 'low' },
  });
  const updated = withReasoningLevelForModel(a, 'model-b', 'max');
  assert.equal(updated.rememberedReasoningLevelsByModelId['model-a'], 'low');
  assert.equal(updated.rememberedReasoningLevelsByModelId['model-b'], 'max');
});

test('withReasoningLevelForModel:覆盖同模型已有记忆', () => {
  const a = makeAssistant({
    reasoningLevel: 'low',
    rememberedReasoningLevelsByModelId: { 'model-1': 'low' },
  });
  const updated = withReasoningLevelForModel(a, 'model-1', 'high');
  assert.equal(updated.rememberedReasoningLevelsByModelId['model-1'], 'high');
});

test('withReasoningLevelForModel:modelId=null 只更新 reasoningLevel 不动记忆 map', () => {
  const a = makeAssistant({
    reasoningLevel: 'auto',
    rememberedReasoningLevelsByModelId: { 'model-a': 'low' },
  });
  const updated = withReasoningLevelForModel(a, null, 'high');
  assert.equal(updated.reasoningLevel, 'high');
  assert.equal(updated.rememberedReasoningLevelsByModelId['model-a'], 'low');
  assert.equal(Object.keys(updated.rememberedReasoningLevelsByModelId).length, 1);
});

test('withReasoningLevelForModel:不修改原 assistant(不可变)', () => {
  const a = makeAssistant({ reasoningLevel: 'auto' });
  withReasoningLevelForModel(a, 'model-1', 'high');
  assert.equal(a.reasoningLevel, 'auto');
  assert.equal(Object.keys(a.rememberedReasoningLevelsByModelId).length, 0);
});

test('读侧验证:withReasoningLevelForModel 后 reasoningLevelForModel 读回新值', () => {
  const a = makeAssistant({ reasoningLevel: 'auto' });
  const updated = withReasoningLevelForModel(a, 'model-1', 'high');
  // 记忆命中 → 返回记忆值(不走 default fallback)
  assert.equal(reasoningLevelForModel(updated, 'model-1', 'low'), 'high');
});

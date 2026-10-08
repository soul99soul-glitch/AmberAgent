// reasoning_levels + withReasoningLevelForModel 纯逻辑测试
//
// 覆盖:
//   reasoningLevelsForModel — 能力过滤 + 返回值隔离
//   withReasoningLevelForModel — 记忆更新 + reasoningLevel 同步 + 不变性

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { makeProviderModel } from '../main/ets/chat/provider_settings.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { withReasoningLevelForModel } from '../main/ets/chat/assistant.ts';
import {
  reasoningLevelsForModel,
} from '../main/ets/chat/reasoning_levels.ts';

const chatModel = (modelId: string): ReturnType<typeof makeProviderModel> =>
  makeProviderModel({ modelId, type: 'chat', abilities: ['reasoning'] });

test('没有 reasoning ability 时不显示推理档位', () => {
  assert.deepEqual(reasoningLevelsForModel(makeProviderModel({ modelId: 'deepseek-chat', type: 'chat' })), []);
});

test('返回值不可变性:修改返回数组不影响后续调用', () => {
  const a = reasoningLevelsForModel(chatModel('gpt-5'));
  a.push({ level: 'max', label: 'x' });
  const b = reasoningLevelsForModel(chatModel('gpt-5'));
  assert.equal(b.length, 4);
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

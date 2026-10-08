import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeUIMessage, makeAssistantMessage } from '../main/ets/chat/message.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { createReasoningLevelMetadataTransformer } from '../main/ets/chat/reasoning_level_metadata.ts';
import { applyVisualTransformersStreamingTail, applyOnGenerationFinish } from '../main/ets/chat/transformer_pipeline.ts';
import { thinkTagTransformer } from '../main/ets/chat/transformers.ts';
import type { UIMessagePartReasoning } from '../main/ets/chat/message.ts';

// 一条行为测试覆盖历史未知、原元数据、精确档位与 think-tag 路径，不做 UI 镜像测试。
test('请求档位只写入新思考，历史与原档位保留，流式和终态使用相同元数据', async () => {
  const thought = (time: string): UIMessagePartReasoning => ({
    type: 'reasoning', reasoning: 'thought', createdAt: time, finishedAt: null,
    metadata: { signature: 'provider-signature' },
  });
  const old = makeUIMessage('assistant', [thought('old')]);
  const native = makeUIMessage('assistant', [thought('new')]);
  const supplied = makeUIMessage('assistant', [{ ...thought('supplied'), metadata: { reasoningLevel: 'low' } }]);
  const tagged = makeAssistantMessage('<think>thinking</think>answer');
  const transformer = createReasoningLevelMetadataTransformer('xhigh', [old]);
  const ctx = { assistant: makeAssistant() };
  const pipeline = [thinkTagTransformer, transformer];
  const live = applyVisualTransformersStreamingTail([old, native], pipeline, ctx);
  assert.equal((live[1].parts[0] as UIMessagePartReasoning).metadata?.['reasoningLevel'], 'xhigh');
  const final = await applyOnGenerationFinish([old, native, supplied, tagged], pipeline, ctx);
  assert.equal((final[0].parts[0] as UIMessagePartReasoning).metadata?.['reasoningLevel'], undefined);
  assert.deepEqual((final[1].parts[0] as UIMessagePartReasoning).metadata,
    { signature: 'provider-signature', reasoningLevel: 'xhigh' });
  assert.equal((final[2].parts[0] as UIMessagePartReasoning).metadata?.['reasoningLevel'], 'low');
  assert.equal((final[3].parts[0] as UIMessagePartReasoning).metadata?.['reasoningLevel'], 'xhigh');
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { UIMessagePartReasoning } from '../main/ets/chat/message.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { createThinkTagTransformer } from '../main/ets/chat/transformers.ts';

test('think 闭合后的正文 chunk 和生成终态保持首次闭合时间', async () => {
  let now = '2026-09-30T00:00:02.000Z';
  const ctx = {
    assistant: makeAssistant(),
    clock: { nowIso: () => now, localTime: () => '', localDate: () => '' },
  };
  const transformer = createThinkTagTransformer();
  const initial = makeAssistantMessage('<think>comparison</think>');
  const first = transformer.visualTransformTail(ctx, initial);
  now = '2026-09-30T00:00:08.000Z';
  const continued = { ...initial, parts: [{ type: 'text' as const,
    text: '<think>comparison</think>continued **Markdown**', metadata: null }] };
  const live = transformer.visualTransformTail(ctx, continued);
  now = '2026-09-30T00:00:12.000Z';
  const final = await transformer.onGenerationFinish!(ctx, [continued]);
  const finishedAt = (first.parts[0] as UIMessagePartReasoning).finishedAt;
  assert.equal(finishedAt, '2026-09-30T00:00:02.000Z');
  assert.equal((live.parts[0] as UIMessagePartReasoning).finishedAt, finishedAt);
  assert.equal((final[0].parts[0] as UIMessagePartReasoning).finishedAt, finishedAt);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { makeAssistantMessage, makeUserMessage, toText } from '../main/ets/chat/message.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { applyOnGenerationFinish, applyVisualTransformersStreamingTail } from '../main/ets/chat/transformer_pipeline.ts';
import { createMemoryCitationTransformer, readMemoryCitationIds, memoryCitationIds,
  stripMemoryCitations } from '../main/ets/chat/memory_citations.ts';

test('引用仅接受实际注入的正整数 ID，去重并去除显示标签', () => {
  const answer = '喜欢中文[[memory:12]] [[memory:12]] [[memory:99]] [[memory:0]] [[memory:no]]';
  assert.deepEqual(memoryCitationIds(answer, [12, 13]), [12]);
  assert.equal(stripMemoryCitations(answer), '喜欢中文    [[memory:no]]');
});

test('只记本轮新 assistant 正文引用，历史和用户伪造不计；流式隐藏但不写引用 metadata', async () => {
  const old = makeAssistantMessage('旧回答[[memory:12]]');
  const user = makeUserMessage('用户伪造[[memory:12]]');
  const fresh = makeAssistantMessage('新回答[[memory:12]][[memory:12]][[memory:99]]');
  const transformer = createMemoryCitationTransformer('run-new', [old], () => [12, 13]);
  const ctx = { assistant: makeAssistant() };
  const live = applyVisualTransformersStreamingTail([old, user, fresh], [transformer], ctx);
  assert.equal(toText(live[2]), '新回答');
  assert.deepEqual(readMemoryCitationIds(live[2], 'run-new', [12]), []);
  const final = await applyOnGenerationFinish([old, user, fresh], [transformer], ctx);
  assert.equal(final[0], old);
  assert.equal(final[1], user);
  assert.equal(toText(final[2]), '新回答');
  assert.deepEqual(readMemoryCitationIds(final[2], 'run-new', [12, 13]), [12]);
  assert.deepEqual(readMemoryCitationIds(final[2], 'run-other', [12]), []);
  assert.deepEqual(readMemoryCitationIds(final[2], 'run-new', [13]), []);
  assert.deepEqual(readMemoryCitationIds({ ...user, parts: final[2].parts }, 'run-new', [12]), []);
});

test('未引用召回条目没有使用证据，已带 metadata 的新正文也按实际标签重算', async () => {
  const fresh = makeAssistantMessage('没有用到记忆');
  fresh.parts[0].metadata = { memoryCitationRunId: 'run-new', memoryCitationIds: [12] };
  const transformer = createMemoryCitationTransformer('run-new', [], () => [12]);
  const final = await applyOnGenerationFinish([fresh], [transformer], { assistant: makeAssistant() });
  assert.deepEqual(readMemoryCitationIds(final[0], 'run-new', [12]), []);
});

test('工具多轮生成会重复处理整段历史，先前新 assistant 的引用仍保留', async () => {
  const transformer = createMemoryCitationTransformer('run-new', [], () => [12, 13]);
  const ctx = { assistant: makeAssistant() };
  const first = await applyOnGenerationFinish([makeAssistantMessage('先用到[[memory:12]]')], [transformer], ctx);
  const second = await applyOnGenerationFinish([...first, makeAssistantMessage('再用到[[memory:13]]')], [transformer], ctx);
  assert.deepEqual(readMemoryCitationIds(second[0], 'run-new', [12, 13]), [12]);
  assert.deepEqual(readMemoryCitationIds(second[1], 'run-new', [12, 13]), [13]);
});

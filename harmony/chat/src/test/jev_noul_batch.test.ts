import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  buildJevNoulBatch, buildJevRequest, decodeJevResponse, readJevNoulBatchProbabilities,
} from '../main/ets/chat/jev_client.ts';

interface NoulBatchRequestBody {
  state: { candidates: Record<string, string> };
  questions: Record<string, { instructions: string }>;
}

test('noul batch questions identify their own candidate, then answers map to the original ids', () => {
  const keys = ['warm-id', 'cool-id'];
  const batch = buildJevNoulBatch('memory_recall', '偏好冷色', keys, {
    'warm-id': '用户偏好暖色', 'cool-id': '用户偏好冷色',
  });
  const request = buildJevRequest('typesafe', 'https://jev.test', 'key', 'model', batch.state, batch.questions);
  const body = JSON.parse(request.body) as NoulBatchRequestBody;
  assert.ok(body.questions.c0.instructions.includes('"warm-id"'));
  assert.ok(body.questions.c1.instructions.includes('"cool-id"'));
  assert.ok(!body.questions.c0.instructions.includes('"cool-id"'));
  assert.deepEqual(body.state.candidates, {
    'warm-id': '用户偏好暖色', 'cool-id': '用户偏好冷色',
  });
  const evaluation = decodeJevResponse('{"answers":{"c0":0.1,"c1":0.9}}', batch.questions, 'typesafe');
  assert.deepEqual(readJevNoulBatchProbabilities(keys, evaluation), { 'warm-id': 0.1, 'cool-id': 0.9 });
  assert.deepEqual(keys, ['warm-id', 'cool-id'], 'batch construction must preserve caller ordering');
});

test('noul candidate references are escaped and query truncation is preserved', () => {
  const id = 'quoted"candidate';
  const batch = buildJevNoulBatch('context_selection', 'x'.repeat(501), [id], { [id]: '候选上下文' });
  assert.ok(batch.questions.c0.instructions.includes(JSON.stringify(id)));
  assert.equal(batch.state.query, 'x'.repeat(500));
});

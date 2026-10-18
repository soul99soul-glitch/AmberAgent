import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildJevRequest, decodeJevResponse } from '../main/ets/chat/jev_client.ts';
import { JEV_PROVIDERS } from '../main/ets/chat/jev_models.ts';
import type { JevApiMode, JevQuestion } from '../main/ets/chat/jev_models.ts';

const questions: Record<string, JevQuestion> = {
  defect: { kind: 'noul', instructions: 'Does the review report a product defect?',
    trueCriteria: 'A product defect is reported.', falseCriteria: 'No product defect is reported.' },
  sentiment: { kind: 'choice', instructions: 'What is the overall sentiment of the review?',
    options: { positive: 'Mostly satisfied', mixed: 'Praise and complaints in one review', negative: 'Mostly dissatisfied' } },
};

test('TypeSafe-family HTTP requests encode criteria, and published Perplexity answers decode', () => {
  // https://docs.perplexity.ai/docs/decisions/quickstart
  // https://docs.typesafe.ai/api
  const response = JSON.stringify({ model: 'pplx-decider-latest', answers: {
    defect: { type: 'noul', noul: 0.9424522889347015 },
    sentiment: { type: 'choice', choice: 'mixed', confidence: 0.9255246944002182,
      probabilities: { positive: 0.020649883775315993, mixed: 0.9503497962668123, negative: 0.02900031995787183 } },
  } });
  for (const provider of ['typesafe', 'perplexity', 'liquid'] as const) {
    const style = JEV_PROVIDERS[provider];
    const request = buildJevRequest(style.contract, style.endpoint, 'test-key', style.defaultModel,
      { review: 'The headphones sound great, but the battery stopped charging after two weeks.' }, questions);
    const body = JSON.parse(request.body);
    assert.equal(request.url, style.endpoint);
    assert.equal(body.model, style.defaultModel);
    assert.deepEqual(body.questions.defect, { type: 'noul', instructions: questions.defect.instructions,
      criteria: { true: 'A product defect is reported.', false: 'No product defect is reported.' } });
    assert.deepEqual(body.questions.sentiment, { type: 'choice', instructions: questions.sentiment.instructions,
      criteria: { positive: 'Mostly satisfied', mixed: 'Praise and complaints in one review', negative: 'Mostly dissatisfied' } });
    assert.deepEqual(decodeJevResponse(response, questions, style.contract).answers, {
      defect: { kind: 'noul', probability: 0.9424522889347015 },
      sentiment: { kind: 'choice', selected: 'mixed' },
    });
  }
});

test('Liquid published noul response preserves probability and usage', () => {
  // https://docs.liquid.ai/lfm/models/d1
  const question: Record<string, JevQuestion> = { is_complaint: {
    kind: 'noul', instructions: 'Is this message a complaint from the customer?', trueCriteria: '', falseCriteria: '',
  } };
  const request = buildJevRequest('typesafe', JEV_PROVIDERS.liquid.endpoint, 'test-key', 'd1', {}, question);
  assert.deepEqual(JSON.parse(request.body).questions.is_complaint,
    { type: 'noul', instructions: question.is_complaint.instructions });
  assert.deepEqual(decodeJevResponse(JSON.stringify({ model: 'd1',
    answers: { is_complaint: { type: 'noul', noul: 0.999 } },
    usage: { input_tokens: 84, output_tokens: 0 },
  }), question, 'typesafe'), {
    model: 'd1', answers: { is_complaint: { kind: 'noul', probability: 0.999 } },
    usage: { inputTokens: 84, outputTokens: 0 },
  });
});

test('Vercel evaluation-model uses boolean probability and choice, with model in headers', () => {
  // Official v4 adapter: vercel/ai at b67b1b746341ca591bd1551fde809278ae7f9f44,
  // packages/gateway/src/gateway-evaluation-model.test.ts and provider evaluation-model-v4-question.ts.
  const request = buildJevRequest('vercel', JEV_PROVIDERS.vercel.endpoint, 'test-key', 'typesafe-ai/jev', {}, questions);
  const body = JSON.parse(request.body);
  assert.equal(request.url, `${JEV_PROVIDERS.vercel.endpoint}/evaluation-model`);
  assert.equal(request.headers['ai-model-id'], 'typesafe-ai/jev');
  assert.equal(request.headers['ai-evaluation-model-specification-version'], '4');
  assert.equal(body.model, undefined);
  assert.equal(body.questions.defect.type, 'boolean');
  assert.deepEqual(body.questions.defect.criteria,
    { true: 'A product defect is reported.', false: 'No product defect is reported.' });
  assert.deepEqual(body.questions.sentiment.criteria,
    { positive: 'Mostly satisfied', mixed: 'Praise and complaints in one review', negative: 'Mostly dissatisfied' });
  const evaluation = decodeJevResponse(JSON.stringify({ answers: {
    defect: { type: 'boolean', probability: 0.97 }, sentiment: { type: 'choice', choice: 'mixed' },
  }, usage: { inputTokens: 12, outputTokens: 0 } }), questions, 'vercel');
  assert.deepEqual(evaluation.answers,
    { defect: { kind: 'noul', probability: 0.97 }, sentiment: { kind: 'choice', selected: 'mixed' } });
  assert.deepEqual(evaluation.usage, { inputTokens: 12, outputTokens: 0 });
});

test('decoder rejects missing answers, incorrect dialects, invalid probabilities and unknown choices', () => {
  for (const mode of ['typesafe', 'vercel'] as JevApiMode[]) {
    const kind = mode === 'vercel' ? 'boolean' : 'noul';
    const field = mode === 'vercel' ? 'probability' : 'noul';
    const noul = { defect: questions.defect };
    const decode = (answer: unknown): void => {
      decodeJevResponse(JSON.stringify({ answers: { defect: answer } }), noul, mode);
    };
    for (const value of [-1, 1.01, '0.9', null]) {
      assert.throws(() => decode({ type: kind, [field]: value }), /invalid noul probability/);
    }
    assert.throws(() => decode(0.9), /invalid answer/);
    assert.throws(() => decode({ type: 'choice', choice: 'mixed' }), /invalid answer type/);
    assert.throws(() => decode({ type: kind, value: true }), /invalid noul probability/);
    assert.throws(() => decodeJevResponse('{"answers":{}}', noul, mode), /missing answer/);
    for (const choice of ['unlisted', 'toString']) {
      assert.throws(() => decodeJevResponse(JSON.stringify({ answers: {
        sentiment: { type: 'choice', choice },
      } }), { sentiment: questions.sentiment }, mode), /invalid choice selection/);
    }
  }
});

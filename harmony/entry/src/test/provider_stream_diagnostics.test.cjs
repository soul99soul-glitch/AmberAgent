const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');

function fixture(aborted = false) {
  const source = fs.readFileSync(path.join(__dirname,
    '../main/ets/platform_impl/ConversationRunDependencies.ets'), 'utf8');
  const body = source.match(/wrapStreamProvider: ([^]*?),\n    store:/)[1];
  const log = source.match(/const logProviderCompletion =[^]*?\n\};/)[0];
  const reports = [];
  const exports = {};
  vm.runInNewContext(ts.transpileModule(`${log}\nexports.wrap = ${body};`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, Error, ctx: { runId: 'run', conversation: { id: 'conversation' }, signal: { aborted } },
    DOMAIN: 1, TAG: 'diagnostic', hilog: { info: (_d, _t, _f, json) => reports.push(JSON.parse(json)) } });
  return { wrap: exports.wrap, reports };
}

test('actual provider wrapper retains chunks and records terminal reason without source text', async () => {
  const f = fixture();
  const chunks = [
    { choices: [{ delta: { parts: [{ type: 'text', text: 'PRIVATE OUTPUT' }] }, message: null, finishReason: null }] },
    { choices: [{ delta: null, message: null, finishReason: 'length' }] },
  ];
  const received = [];
  await f.wrap({ streamText: async (_m, emit) => chunks.forEach(emit) }).streamText([], c => received.push(c));
  assert.deepEqual(received, chunks);
  assert.equal(f.reports[0].finishReason, 'length');
  assert.equal(f.reports[0].characters, 14);
  assert.equal(f.reports[0].outcome, 'finished');
  assert.ok(!JSON.stringify(f.reports).includes('PRIVATE OUTPUT'));
});

test('actual provider wrapper distinguishes cancellation and preserves the provider failure', async () => {
  const error = new Error('provider failed');
  for (const aborted of [false, true]) {
    const f = fixture(aborted);
    await assert.rejects(f.wrap({ streamText: async () => { throw error; } }).streamText([], () => {}),
      e => e === error);
    assert.equal(f.reports[0].outcome, aborted ? 'cancelled' : 'failed');
  }
});

test('non-stream completion has the same diagnostics and preserves its response and error', async () => {
  const f = fixture();
  const chunk = { choices: [{ delta: null, message: { parts: [{ type: 'text', text: 'answer' }] }, finishReason: 'stop' }] };
  assert.equal(await f.wrap({ generateText: async () => chunk }).generateText([]), chunk);
  assert.equal(f.reports[0].finishReason, 'stop');
  assert.equal(f.reports[0].characters, 6);
  const error = new Error('nonstream failed');
  await assert.rejects(f.wrap({ generateText: async () => { throw error; } }).generateText([]), e => e === error);
  assert.equal(f.reports[1].outcome, 'failed');
  assert.equal(f.wrap({}).generateText, undefined);
});

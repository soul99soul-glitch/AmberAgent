const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const ENTRY = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
const fixture = () => {
  const calls = [];
  const imports = {
    '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: text => new TextEncoder().encode(text) }) } } },
    './BackgroundGenerationKeepAlive.ets': { reportGenerationProgress: (_context, value) => calls.push({ ...value }) },
  };
  const load = name => {
    const exports = {};
    const filename = path.join(ENTRY, name);
    vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    }).outputText, {
      exports, require: spec => spec === './GenerationProgressTracker.ets' ? load('GenerationProgressTracker.ets') : imports[spec],
      AppStorage: { get: () => ({}) }, Map, Promise, Error, String,
    }, { filename });
    return exports;
  };
  return { api: load('DeepReadGenerationActivity.ets'), calls };
};
const msg = (id, text, role = 'assistant') => ({ id, role, parts: [{ type: 'text', text }] });

test('P4: DeepRead activity starts at zero, baselines stages and counts actual UTF8 exactly once', () => {
  const { api, calls } = fixture();
  api.setDeepReadGenerationActivity('topic', 3, true);
  assert.equal(calls[0].receivedBytes, 0);
  assert.equal(calls[0].source, 'deepread');
  api.beginDeepReadGenerationStep('topic', 3, [msg('history', '已有正文')]);
  api.observeDeepReadGenerationSnapshot('topic', 3, [msg('history', '已有正文'), msg('plan', '你好'), msg('user', '忽略', 'user')]);
  assert.equal(calls.at(-1).receivedBytes, 6);
  api.beginDeepReadGenerationStep('topic', 3, [msg('history', '已有正文'), msg('plan', '你好')]);
  api.observeDeepReadGenerationSnapshot('topic', 3, [msg('plan', '你好'), msg('body', '新正文')]);
  assert.equal(calls.at(-1).receivedChars, 5);
  assert.equal(calls.at(-1).receivedBytes, 15);
  api.setDeepReadGenerationActivity('topic', 3, false);
  assert.equal(calls.at(-1).generationActive, false);
  assert.equal(calls.at(-1).receivedBytes, 15);
});

test('P4: old token finish and late snapshot cannot unregister or resurrect the replacement activity', () => {
  const { api, calls } = fixture();
  api.setDeepReadGenerationActivity('topic', 1, true);
  api.setDeepReadGenerationActivity('topic', 2, true);
  api.setDeepReadGenerationActivity('topic', 1, false);
  assert.equal(calls.at(-1).runId, 'topic:1');
  const before = calls.length;
  api.beginDeepReadGenerationStep('topic', 1, []);
  api.observeDeepReadGenerationSnapshot('topic', 1, [msg('late', '迟到正文')]);
  assert.equal(calls.length, before);
  api.observeDeepReadGenerationSnapshot('topic', 2, [msg('body', '正文')]);
  assert.equal(calls.at(-1).runId, 'topic:2');
  assert.equal(calls.at(-1).generationActive, true);
  api.setDeepReadGenerationActivity('topic', 2, false);
  assert.equal(calls.at(-1).runId, 'topic:2');
  assert.equal(calls.at(-1).generationActive, false);
});

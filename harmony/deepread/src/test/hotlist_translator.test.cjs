const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const fixture = (answer) => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/HotListTitleTranslator.ets');
  const exports = {}; const requests = [];
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: s => s === '@amber/deepread-domain' ? {
    makeSystemMessage: text => ({ role: 'system', parts: [{ type: 'text', text }] }),
    makeUserMessage: text => ({ role: 'user', parts: [{ type: 'text', text }] }),
  } : { getDeepReadTextRuntime: async () => ({ model: 'chosen-model', aiClient: {
    generateText: async request => { requests.push(request); return [
      ...request.messages, { role: 'assistant', parts: [{ type: 'text', text: answer }] },
    ]; },
  } }) }, Error, JSON, Array }, { filename });
  return { translate: exports.translateHotListTitles, requests };
};

test('hot title translation preserves original identities and reads only the assistant answer', async () => {
  const { translate, requests } = fixture('```json\n["人工智能新进展","机器人论文"]\n```');
  const result = await translate(['New AI advance', 'Robotics paper']);
  assert.equal(result['New AI advance'], '人工智能新进展');
  assert.equal(result['Robotics paper'], '机器人论文');
  assert.equal(requests[0].model, 'chosen-model');
  assert.equal(requests[0].messages[1].parts[0].text, '["New AI advance","Robotics paper"]');
});

test('invalid count or empty translations fail without publishing a partial title map', async () => {
  for (const value of ['["一个"]', '["一个", ""]', '{"a":"一个"}', '["一个", 12]']) {
    await assert.rejects(fixture(value).translate(['a', 'b']));
  }
  const empty = fixture('should not be called');
  await empty.translate([]);
  assert.equal(empty.requests.length, 0);
});

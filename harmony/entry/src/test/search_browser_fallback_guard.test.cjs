const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
process.env.TSX_TSCONFIG_PATH ??= path.resolve(__dirname, '../../../chat/tsconfig.json');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { makeAgentTool } = require('../../../chat/src/main/ets/chat/tool.ts');

const loadGuard = () => {
  const file = path.resolve(__dirname, '../main/ets/platform_impl/ConversationBrowserGuard.ets');
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText, { exports, require: name => {
    if (name === '@amber/chat-domain') return { makeAgentTool };
    throw new Error(`Unexpected browser guard import: ${name}`);
  } }, { filename: file });
  return exports;
};

test('availability does not acquire a lease and leaves the first wm_open usable', async () => {
  const guard = loadGuard();
  assert.match(guard.conversationBrowserUnavailableReason('chat'), /当前会话/);
  const leave = guard.bindConversationBrowserPage('chat');
  assert.equal(guard.conversationBrowserUnavailableReason('chat'), null);
  assert.equal(guard.conversationBrowserUnavailableReason('chat'), null);
  leave();
  assert.match(guard.conversationBrowserUnavailableReason('chat'), /当前会话/);
  guard.bindConversationBrowserPage('chat');
  // A query-created lease would have been invalidated by leave().
  assert.equal(guard.conversationBrowserUnavailableReason('chat'), null);
  let calls = 0;
  const tool = guard.guardConversationBrowserTools([makeAgentTool({ name: 'wm_open',
    execute: async () => { calls++; return []; } })], 'chat')[0];
  await tool.execute({ url: 'https://www.google.com/search?q=test' });
  assert.equal(calls, 1);
  assert.equal(guard.conversationBrowserUnavailableReason('chat'), null);
});

test('an existing lost lease remains unavailable until its run releases it', () => {
  const guard = loadGuard();
  const leave = guard.bindConversationBrowserPage('chat');
  const assertOwner = guard.assertConversationBrowserOwner('chat');
  assert.equal(guard.conversationBrowserUnavailableReason('chat'), null);
  leave();
  assert.throws(assertOwner, /页面已离开/);
  guard.bindConversationBrowserPage('chat');
  assert.match(guard.conversationBrowserUnavailableReason('chat'), /页面已离开/);
  assert.throws(() => guard.assertConversationBrowserOwner('chat'), /页面已离开/);
  guard.releaseConversationBrowserRun('chat', true);
  assert.match(guard.conversationBrowserUnavailableReason('chat'), /页面已离开/);
  guard.releaseConversationBrowserRun('chat', false);
  assert.equal(guard.conversationBrowserUnavailableReason('chat'), null);
});

test('an in-flight browser operation blocks both its owner and a newly visible conversation', async () => {
  const guard = loadGuard();
  guard.bindConversationBrowserPage('first');
  let complete;
  const first = guard.guardConversationBrowserTools([makeAgentTool({ name: 'wm_open',
    execute: () => new Promise(resolve => { complete = resolve; }) })], 'first')[0];
  const pending = first.execute({ url: 'https://www.google.com/search?q=first' });
  assert.match(guard.conversationBrowserUnavailableReason('first'), /占用/);
  guard.bindConversationBrowserPage('second');
  assert.match(guard.conversationBrowserUnavailableReason('first'), /当前会话/);
  assert.match(guard.conversationBrowserUnavailableReason('second'), /占用/);
  let secondCalls = 0;
  const second = guard.guardConversationBrowserTools([makeAgentTool({ name: 'wm_open',
    execute: async () => { secondCalls++; return []; } })], 'second')[0];
  await assert.rejects(second.execute({ url: 'https://www.bing.com/search?q=second' }), /占用/);
  assert.equal(secondCalls, 0);
  complete([]);
  const output = await pending;
  assert.equal(JSON.parse(output[0].text).status, 'outcome_unknown');
  assert.equal(guard.conversationBrowserUnavailableReason('second'), null);
  await second.execute({ url: 'https://www.bing.com/search?q=second' });
  assert.equal(secondCalls, 1);
});

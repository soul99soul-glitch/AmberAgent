const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
require('tsx/cjs');
const chat = require('../main/ets/index.ts');
const load = (name, imports) => {
  const exports = {};
  const file = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl', name);
  const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  vm.runInNewContext(code, { exports, require: name => {
    assert.ok(imports[name], name); return imports[name];
  }, Error, Promise, Map, Set, JSON, String, Date, Array }, { filename: file });
  return exports;
};

test('actual scoped subagent package setup preserves review on its replacement terminal-hook dispatcher', async () => {
  let effects = 0; let evaluations = 0;
  const review = async () => { evaluations++; return ['破坏性']; };
  const definition = chat.makeAgentTool({ name: 'file_write', description: 'write', execute: async () => {
    effects++; return [{ type: 'text', text: 'written', metadata: null }];
  } });
  const loop = { tools: [definition, chat.makeAgentTool({ name: 'recipes_list', description: 'catalog', execute: async () => [] })],
    dispatcher: new chat.AgentToolDispatcher({ autoApprovalReview: review }) };
  const scoped = load('ScopedRecipeLoop.ets', { '@amber/chat-domain': chat });
  await scoped.configureEntryScopedRecipeLoop(loop, { listInstalled: async () => [] }, null, null, review);
  const pending = await loop.dispatcher.execute({ type: 'tool', toolCallId: 'same', toolName: 'file_write', input: '{}',
    output: [], metadata: { terminal_target: 'pinned' }, approvalState: { type: 'auto' } }, definition, true, true);
  assert.equal(pending.approvalState.type, 'pending'); assert.equal(effects, 0); assert.equal(evaluations, 1);
  assert.equal(pending.metadata.terminal_target, 'pinned');
  await loop.dispatcher.execute({ ...pending, approvalState: { type: 'approved' } }, definition, true, true);
  assert.equal(effects, 1); assert.equal(evaluations, 1);
});

test('actual Entry gate binds immutable selected user task, never output/reasoning; per-run callbacks stay separate', async () => {
  const kv = chat.createMemoryKeyValueStore();
  await chat.saveJevSettings(kv, chat.makeJevSettings({ mode: 'active', autoApproval: { mode: 'active', allowToolMetadata: true, allowTaskText: true } }));
  const states = [];
  const support = load('JevAutoApprovalSupport.ets', { '@amber/chat-domain': chat,
    '../di/AppContainer.ets': { getChatKvStore: () => kv }, './JevSupport.ets': { jevEvaluatePurpose: async (purpose, state, _questions, _signal, expectedSettings) => {
      assert.equal(expectedSettings.autoApproval.allowTaskText, true); assert.equal(expectedSettings.autoApproval.allowToolMetadata, true);
      assert.equal(purpose, 'auto_approval'); states.push(state);
      return { ok: true, shadow: false, reason: '', evaluation: { model: 'judge', usage: null,
        answers: { destructive: { kind: 'noul', probability: .9 } } } };
    } } });
  const firstMessages = [chat.makeUserMessage('first task'), chat.makeUIMessage('assistant', [
    { type: 'reasoning', reasoning: 'REASONING_SECRET', metadata: null },
    { type: 'tool', toolCallId: 'old', toolName: 'file_read', input: '{}', approvalState: { type: 'auto' }, metadata: null,
      output: [{ type: 'text', text: 'OUTPUT_SECRET', metadata: null }] }])];
  const first = support.createEntryJevAutoApprovalReview(firstMessages);
  firstMessages[0].parts[0].text = 'changed task';
  const second = support.createEntryJevAutoApprovalReview([chat.makeUserMessage('second task')]);
  const call = { type: 'tool', toolCallId: 'same', toolName: 'file_write', input: '{}', output: [], metadata: null, approvalState: { type: 'auto' } };
  const definition = chat.makeAgentTool({ name: 'file_write', description: 'write', execute: async () => [] });
  await new chat.AgentToolDispatcher({ autoApprovalReview: first }).execute(call, definition, true, true);
  await new chat.AgentToolDispatcher({ autoApprovalReview: second }).execute(call, definition, true, true);
  assert.equal(states.length, 2); assert.match(JSON.stringify(states[0].user_requests), /first task/);
  assert.match(JSON.stringify(states[1].user_requests), /second task/);
  assert.doesNotMatch(JSON.stringify(states), /OUTPUT_SECRET|REASONING_SECRET|changed task/);
});

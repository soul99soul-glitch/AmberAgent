// E12 C loop group: actual tool_loop/recipe_loop/dispatcher + goal adapter with
// controlled host ports. Proves ASK sibling pause, STARTED-before-effect once,
// completion by fresh observation, replan on document change, both-auto tap
// admission, save-failure zero-effect and cold recovery never replaying.
const { test } = require('node:test');
const assert = require('node:assert/strict');
require('tsx/cjs');
const conversations = require('../main/ets/chat/conversation.ts');
const messages = require('../main/ets/chat/message.ts');
const { makeAssistant } = require('../main/ets/chat/assistant.ts');
const { makeAgentTool } = require('../main/ets/chat/tool.ts');
const { runAgenticToolLoop, runToolLoopContinuation } = require('../main/ets/chat/tool_loop.ts');
const { applyToolApprovalToConversation } = require('../main/ets/chat/tool_approval.ts');
const { AgentToolDispatcher, defaultToolInvocationHooks } = require('../main/ets/chat/tool_dispatcher.ts');
const { PermissionDecisionResolver } = require('../main/ets/chat/tool_permission.ts');
const goal = require('../main/ets/chat/webmount/goal.ts');
const approval = require('../main/ets/chat/jev_approval.ts');

const obs = (revision, text, extra = {}) => ({
  token: { sessionId: 'main', documentId: 'doc-1', revision },
  url: 'https://a.com/page', title: 'Page', text,
  elements: [{ ref: 'wm-0', tag: 'a', text: 'next page', href: 'https://a.com/next', inputType: null, options: [] }],
  ...extra,
});
const goalPart = (id = 'call-goal-1') => ({
  type: 'tool', toolCallId: id, toolName: 'wm_run_goal',
  input: JSON.stringify({ goal: 'read the next page', completion_text: 'done-marker' }),
  output: [], approvalState: { type: 'auto' }, metadata: null,
});

function fixture(observeScript, decideScript, review = null) {
  const events = [];
  const state = { calls: { observe: 0, choose: 0 } };
  const store = {
    conversation: null,
    async load() { return this.conversation; },
    async save(conv) {
      this.conversation = conv;
      const text = JSON.stringify(conv);
      if (text.includes('wm_run_goal') && text.includes('"phase":"started"')) events.push('save:started');
    },
  };
  const ports = {
    now: () => 1000,
    assertUsable: async () => {},
    observe: async () => observeScript[Math.min(state.calls.observe++, observeScript.length - 1)],
    choose: async () => { state.calls.choose++; return decideScript[Math.min(state.calls.choose - 1, decideScript.length - 1)]; },
  };
  const click = makeAgentTool({
    name: 'wm_click', description: 'click', parameters: () => null,
    needsApproval: true, allowsAutoApproval: false, mandatoryApproval: review === null,
    execute: async () => {
      events.push('effect:click');
      return [{ type: 'text', text: '{"clicked":true}', metadata: null }];
    },
  });
  const scroll = makeAgentTool({
    name: 'wm_scroll', description: 'scroll', parameters: () => null,
    execute: async () => [{ type: 'text', text: '{"scrolled":true}', metadata: null }],
  });
  const goalTool = goal.createWebMountGoalTool();
  const adapter = goal.createWebMountGoalAdapter(ports);
  const dispatcher = new AgentToolDispatcher({ hooks: defaultToolInvocationHooks(), autoApprovalReview: review });
  const assistant = makeAssistant({});
  const providerFor = (script) => ({
    streamText: async (_messages, onChunk) => {
      const next = script.shift();
      if (next) for (const chunk of next) onChunk(chunk);
    },
  });
  const makeDeps = (script) => ({
    assistant,
    provider: providerFor(script),
    store,
    inputTransformers: [],
    outputTransformers: [],
  });
  const makeLoop = (script) => ({
    tools: [goalTool, click, scroll],
    goalAdapter: adapter,
    dispatcher,
    makeProviderForStep: () => providerFor(script),
  });
  const chunk = (part) => ({
    id: 'c', model: 'm', choices: [{ index: 0, delta: messages.makeUIMessage('assistant', [part]), message: null, finishReason: 'tool_calls' }],
    usage: null,
  });
  const textChunk = (text) => ({
    id: 'c', model: 'm', choices: [{ index: 0, delta: messages.makeUIMessage('assistant', [{ type: 'text', text, metadata: null }]), message: null, finishReason: 'stop' }],
    usage: null,
  });
  return { events, state, store, makeDeps, makeLoop, chunk, textChunk, assistant };
}

const seedConversation = () => conversations.makeConversation('c1', [
  conversations.toMessageNode(messages.makeUserMessage('run the goal')),
]);
const pendingGoalPart = (conv) => {
  const last = conversations.currentMessages(conv).at(-1);
  return last.parts.find((p) => p.type === 'tool' && p.toolName === 'wm_run_goal');
};

test('goal ASK holds the run, approval saves STARTED before one effect, completion comes from a fresh observation', async () => {
  const f = fixture(
    [obs(0, 'first page'), obs(0, 'first page'), obs(0, 'all done-marker now')],
    [{ kind: 'action', candidateId: 'cand-0', reason: '' }],
  );
  const script = [[f.chunk(goalPart())], [f.textChunk('完成')]];
  let conv = await runAgenticToolLoop(seedConversation(), f.makeDeps(script), f.makeLoop(script));
  let part = pendingGoalPart(conv);
  assert.equal(part.approvalState.type, 'pending');
  assert.equal(part.metadata.goal_v1.phase, 'awaiting_approval');
  assert.equal(part.metadata.goal_v1.pendingStep.toolName, 'wm_click');
  assert.equal(part.metadata.goal_v1.pendingStep.metadata.wm_document_v1.revision, 0);
  assert.deepEqual(f.events, []); // no effect before approval

  // 审批投影:subject 绑定保存的 child 与 goal 请求身份
  const lastMsg = conversations.currentMessages(conv).at(-1);
  const subject = approval.buildJevApprovalSubject(
    { conversationId: conv.id, messageId: lastMsg.id, partIndex: lastMsg.parts.length - 1, toolCallId: part.toolCallId },
    part, 'user-msg');
  assert.equal(subject.action.toolName, 'wm_click');
  assert.match(subject.packageHash, /^goal-[0-9a-f]{8}$/);

  conv = applyToolApprovalToConversation(conv, part.toolCallId, { kind: 'approved' },
    { messageId: lastMsg.id, partIndex: lastMsg.parts.length - 1 });
  conv = await runToolLoopContinuation(conv, f.makeDeps(script), f.makeLoop(script));
  part = pendingGoalPart(conv);
  assert.equal(part.metadata.goal_v1.phase, 'finished');
  const output = JSON.parse(part.output[0].text);
  assert.equal(output.status, 'completed');
  assert.deepEqual(f.events, ['save:started', 'effect:click']); // STARTED 落盘先于唯一一次效果
});

test('document change while waiting discards the approved child and seeks fresh approval', async () => {
  const f = fixture(
    [obs(0, 'first page'), obs(1, 'edited page'), obs(1, 'edited page'), obs(1, 'all done-marker now')],
    [{ kind: 'action', candidateId: 'cand-0', reason: '' }, { kind: 'action', candidateId: 'cand-0', reason: '' }],
  );
  const script = [[f.chunk(goalPart())], [f.textChunk('完成')]];
  let conv = await runAgenticToolLoop(seedConversation(), f.makeDeps(script), f.makeLoop(script));
  let part = pendingGoalPart(conv);
  assert.equal(part.approvalState.type, 'pending');
  const lastMsg = conversations.currentMessages(conv).at(-1);
  conv = applyToolApprovalToConversation(conv, part.toolCallId, { kind: 'approved' },
    { messageId: lastMsg.id, partIndex: lastMsg.parts.length - 1 });
  // 等待期间文档 revision 已变:旧 approved ref 不得执行
  conv = await runToolLoopContinuation(conv, f.makeDeps(script), f.makeLoop(script));
  part = pendingGoalPart(conv);
  assert.equal(part.metadata.goal_v1.phase, 'awaiting_approval');
  assert.equal(part.metadata.goal_v1.pendingStep.metadata.wm_document_v1.revision, 1);
  assert.deepEqual(f.events, []);
  assert.equal(f.state.calls.choose, 2);
  // 新一轮批准 → 文档一致 → 执行并完成
  const lastMsg2 = conversations.currentMessages(conv).at(-1);
  conv = applyToolApprovalToConversation(conv, part.toolCallId, { kind: 'approved' },
    { messageId: lastMsg2.id, partIndex: lastMsg2.parts.length - 1 });
  conv = await runToolLoopContinuation(conv, f.makeDeps(script), f.makeLoop(script));
  part = pendingGoalPart(conv);
  assert.equal(JSON.parse(part.output[0].text).status, 'completed');
  assert.deepEqual(f.events, ['save:started', 'effect:click']);
});

test('both-auto settings still force human admission for wm_tap/wm_fetch_replay', () => {
  const resolver = new PermissionDecisionResolver();
  const tap = makeAgentTool({
    name: 'wm_tap', description: 'tap', parameters: () => null,
    needsApproval: true, allowsAutoApproval: false, mandatoryApproval: true,
    execute: async () => [],
  });
  const part = { type: 'tool', toolCallId: 't', toolName: 'wm_tap', input: '{}', output: [], approvalState: { type: 'auto' }, metadata: null };
  const decision = resolver.resolve(tap, part, true, true, [], 'normal');
  assert.equal(decision.action, 'ask');
  assert.equal(decision.source, 'webmount_explicit_approval');
  // approved/denied 仍权威
  const approved = { ...part, approvalState: { type: 'approved' } };
  assert.equal(resolver.resolve(tap, approved, true, true, [], 'normal').action, 'allow');
  // 其他工具语义不变:双 auto 仍放行普通 mandatory 工具
  const other = makeAgentTool({
    name: 'wm_click', description: 'click', parameters: () => null,
    needsApproval: true, allowsAutoApproval: false, mandatoryApproval: true,
    execute: async () => [],
  });
  const otherPart = { ...part, toolName: 'wm_click' };
  assert.equal(resolver.resolve(other, otherPart, true, true, [], 'normal').action, 'allow');
});

test('STARTED save failure means zero effect; cold recovery marks outcome_unknown once and never replays', async () => {
  const f = fixture(
    [obs(0, 'first page'), obs(0, 'first page')],
    [{ kind: 'action', candidateId: 'cand-0', reason: '' }],
  );
  const baseSave = f.store.save.bind(f.store);
  f.store.save = async (conv) => {
    if (JSON.stringify(conv).includes('"phase":"started"')) throw new Error('controlled save failure');
    return baseSave(conv);
  };
  const script = [[f.chunk(goalPart())], [f.textChunk('完成')]];
  let conv = await runAgenticToolLoop(seedConversation(), f.makeDeps(script), f.makeLoop(script));
  const lastMsg = conversations.currentMessages(conv).at(-1);
  const part = pendingGoalPart(conv);
  conv = applyToolApprovalToConversation(conv, part.toolCallId, { kind: 'approved' },
    { messageId: lastMsg.id, partIndex: lastMsg.parts.length - 1 });
  await assert.rejects(runToolLoopContinuation(conv, f.makeDeps(script), f.makeLoop(script)), /controlled save failure/);
  assert.deepEqual(f.events, []);

  const startedConv = conversations.makeConversation('c2', [
    conversations.toMessageNode(messages.makeUserMessage('goal')),
    conversations.toMessageNode(messages.makeUIMessage('assistant', [{
      type: 'tool', toolCallId: 'g', toolName: 'wm_run_goal',
      input: JSON.stringify({ goal: 'g', completion_text: 'done' }),
      output: [], approvalState: { type: 'auto' },
      metadata: {
        goal_v1: {
          version: 1,
          request: {
            sessionId: 'main', goal: 'g', completionText: 'done', allowedActions: [],
            draftValue: null, maxActionDecisions: 12, maxSeconds: 60, maxNoProgress: 3,
            decisionSource: 'main_model',
          },
          phase: 'started', observation: null,
          pendingStep: {
            type: 'tool', toolCallId: 'goal-1-1', toolName: 'wm_click', input: '{}',
            output: [], approvalState: { type: 'auto' }, metadata: null,
          },
          decisions: 1, noProgress: 0, startedAtMillis: 1000, deadlineMillis: 61000,
        },
      },
    }])),
  ]);
  const recovered = goal.recoverInterruptedWebMountGoals(startedConv);
  const recoveredPart = conversations.currentMessages(recovered).at(-1).parts[0];
  assert.equal(JSON.parse(recoveredPart.output[0].text).status, 'outcome_unknown');
  assert.equal(recoveredPart.metadata.goal_v1.phase, 'finished');
  assert.equal(goal.recoverInterruptedWebMountGoals(recovered), recovered); // 幂等,不二次改写
});

test('auto approval review pauses real goal child before STARTED/effect and resumes its pinned document once', async () => {
  let evaluations = 0;
  const f = fixture([obs(0, 'first'), obs(0, 'first'), obs(0, 'done-marker')],
    [{ kind: 'action', candidateId: 'cand-0', reason: '' }], async () => { evaluations++; return ['超出任务范围']; });
  const script = [[f.chunk(goalPart())], [f.textChunk('done')]];
  const loop = { ...f.makeLoop(script), autoApproveTools: true, autoApproveHighRiskTools: true };
  let conv = await runAgenticToolLoop(seedConversation(), f.makeDeps(script), loop);
  let p = pendingGoalPart(conv);
  assert.equal(p.approvalState.type, 'pending'); assert.equal(p.metadata.goal_v1.phase, 'awaiting_approval');
  assert.deepEqual(f.events, []); assert.equal(evaluations, 1);
  assert.equal(require('../main/ets/chat/jev_auto_approval.ts').jevAutoApprovalReason(p), '自动批准复核：超出任务范围');
  assert.equal(p.metadata.goal_v1.pendingStep.metadata.wm_document_v1.revision, 0);
  assert.match(JSON.stringify(p.metadata.goal_v1.pendingStep.metadata.permission_trace), /jev_auto_approval.*超出任务范围/);
  conv = applyToolApprovalToConversation(conv, p.toolCallId, { kind: 'approved' });
  conv = await runToolLoopContinuation(conv, f.makeDeps(script), loop);
  assert.equal(f.events.filter(e => e === 'effect:click').length, 1);
  assert.equal(evaluations, 1);
});

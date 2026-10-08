import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AgentToolDispatcher } from '../main/ets/chat/tool_dispatcher.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePartTool } from '../main/ets/chat/message.ts';

const part = (input = '{}'): UIMessagePartTool => ({ type: 'tool', toolCallId: 'risk-call', toolName: 'file_write', input,
  output: [], metadata: { terminal_target: { profileId: 'pinned' } }, approvalState: { type: 'auto' } });
const write = (effect: () => void) => makeAgentTool({ name: 'file_write', description: 'write',
  needsApproval: true, execute: async () => { effect(); return [{ type: 'text', text: 'done', metadata: null }]; } });

test('final dispatcher review converts autoallow to saved pending before effects and preserves target', async () => {
  let effects = 0; let evaluations = 0;
  const dispatcher = new AgentToolDispatcher({ autoApprovalReview: async () => { evaluations++; return ['破坏性']; } });
  const definition = write(() => effects++);
  const pending = await dispatcher.execute(part(), definition, true, true);
  assert.equal(pending?.approvalState.type, 'pending');
  assert.equal(effects, 0); assert.equal(evaluations, 1);
  assert.deepEqual(pending?.metadata?.['terminal_target'], { profileId: 'pinned' });
  const approved = await dispatcher.execute({ ...pending!, approvalState: { type: 'approved' } }, definition, true, true);
  assert.equal(effects, 1); assert.equal(evaluations, 1); assert.equal(approved?.output.length, 1);
});

test('original ask must never execute a side effect through the direct dispatcher', async () => {
  let effects = 0;
  const pending = await new AgentToolDispatcher().execute(part(), write(() => effects++), false);
  assert.equal(pending?.approvalState.type, 'pending'); assert.equal(effects, 0);
});

import { createJevAutoApprovalReview, buildJevAutoApprovalBatch, jevAutoApprovalRiskReasons } from '../main/ets/chat/jev_auto_approval.ts';
import { makeJevSettings } from '../main/ets/chat/jev_models.ts';
import type { JevEvaluateResult, JevEvaluation } from '../main/ets/chat/jev_models.ts';
import { makeConversation, currentMessages, toMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { runAgenticToolLoop, runToolLoopContinuation } from '../main/ets/chat/tool_loop.ts';
import { TerminalController } from '../main/ets/chat/terminal/control.ts';
import { ChatStreamSubAgentGenerationPort, GenerationSubAgentRunner } from '../main/ets/chat/subagent_runner.ts';
import { makeProviderModel } from '../main/ets/chat/provider_settings.ts';
import { makeSubAgentDefinition } from '../main/ets/chat/agent_prompt_config.ts';
import { makeSubAgentTaskSpec } from '../main/ets/chat/subagent_models.ts';
import { makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';

const evaluation = (values: Record<string, number>): JevEvaluation => ({ answers: Object.fromEntries(Object.entries(values)
  .map(([key, probability]) => [key, { kind: 'noul', probability }])), model: 'judge', usage: null });
const result = (values: Record<string, number>, shadow = false): JevEvaluateResult => ({ ok: true, reason: '', shadow,
  evaluation: evaluation(values) });
const settings = () => makeJevSettings({ mode: 'active', model: 'judge', apiKey: 'controlled',
  autoApproval: { mode: 'active', allowTaskText: true, allowToolMetadata: true } });
const local = () => new AgentToolDispatcher().resolveDecision(write(() => {}), part(), true, true);

test('risk boundary and explicit authorization excuse only destruction and off-task', () => {
  assert.deepEqual(jevAutoApprovalRiskReasons(evaluation({ destructive: .799, offTask: .799, exfiltration: .799 })), []);
  assert.deepEqual(jevAutoApprovalRiskReasons(evaluation({ destructive: .8, offTask: .8 })), ['破坏性', '超出任务范围']);
  assert.deepEqual(jevAutoApprovalRiskReasons(evaluation({ destructive: .9, offTask: .9, authorized: .8 })), []);
  assert.deepEqual(jevAutoApprovalRiskReasons(evaluation({ destructive: .9, offTask: .9, exfiltration: .8, authorized: .9 })), ['外发数据']);
  assert.deepEqual(jevAutoApprovalRiskReasons(evaluation({ destructive: NaN, exfiltration: Infinity })), []);
});

test('request is bounded task/tool data, includes command tail and excludes outputs and metadata secrets', () => {
  const call = { ...part('a'.repeat(2200) + ';rm -rf target'), output: [{ type: 'text' as const, text: 'OUTPUT_SECRET', metadata: null }],
    metadata: { secret: 'METADATA_SECRET' } };
  const batch = buildJevAutoApprovalBatch(call, local(), ['OLD_SECRET', 'a'.repeat(2000), 'two', 'three']);
  assert.equal(Object.keys(batch.questions).length, 4);
  assert.ok(JSON.stringify(batch.state).length < 4000);
  assert.match(String(batch.state['parameters']), /rm -rf target$/);
  assert.doesNotMatch(JSON.stringify(batch.state), /OUTPUT_SECRET|METADATA_SECRET|OLD_SECRET/);
});

test('off and unconsented tool scope make zero requests; shadow and deadline preserve allow', async () => {
  let requests = 0;
  for (const config of [makeJevSettings(), makeJevSettings({ mode: 'active', autoApproval: { mode: 'active', allowTaskText: true } })]) {
    const review = createJevAutoApprovalReview({ loadSettings: async () => config, recentUserTexts: ['task'],
      evaluate: async () => { requests++; return result({ destructive: .99 }); } });
    assert.deepEqual(await review(part(), local()), []);
  }
  assert.equal(requests, 0);
  for (const response of [result({ destructive: .99 }, true), { ok: false, reason: 'deadline', shadow: false, evaluation: null }]) {
    const review = createJevAutoApprovalReview({ loadSettings: async () => settings(), recentUserTexts: ['task'], evaluate: async () => response });
    assert.deepEqual(await review(part(), local()), []);
  }
});

test('revoked consent after evaluation discards risk result', async () => {
  let current = settings();
  const review = createJevAutoApprovalReview({ loadSettings: async () => current, recentUserTexts: ['task'], evaluate: async () => {
    current = makeJevSettings({ ...current, autoApproval: { ...current.autoApproval, allowTaskText: false } });
    return result({ destructive: .9 });
  } });
  assert.deepEqual(await review(part(), local()), []);
});

test('local ask/deny, explicit approval and static low risk require zero evaluations', async () => {
  let evaluations = 0;
  const dispatcher = new AgentToolDispatcher({ autoApprovalReview: async () => { evaluations++; return ['破坏性']; } });
  const definition = write(() => {});
  await dispatcher.resolveReviewedDecision(definition, part(), false);
  await dispatcher.resolveReviewedDecision(null, part(), true, true);
  await dispatcher.resolveReviewedDecision(definition, { ...part(), approvalState: { type: 'approved' } }, true, true);
  await dispatcher.resolveReviewedDecision(makeAgentTool({ name: 'file_read', description: 'read', execute: async () => [] }),
    { ...part(), toolName: 'file_read' }, true, true);
  assert.equal(evaluations, 0);
});

test('preflight and execute evaluate once; input changes and fresh run get distinct reviews', async () => {
  let evaluations = 0; let effects = 0;
  const deps = { autoApprovalReview: async () => { evaluations++; return []; } };
  const dispatcher = new AgentToolDispatcher(deps); const definition = write(() => effects++);
  await Promise.all([dispatcher.resolveReviewedDecision(definition, part(), true, true),
    dispatcher.resolveReviewedDecision(definition, part(), true, true)]);
  await dispatcher.execute(part(), definition, true, true);
  await dispatcher.execute(part('{"changed":true}'), definition, true, true);
  await new AgentToolDispatcher(deps).execute(part(), definition, true, true);
  assert.equal(evaluations, 3); assert.equal(effects, 3);
});

test('abort while reviewer returns and reviewer failure have no extra fallback effect', async () => {
  let effects = 0; const controller = new TerminalController();
  const dispatcher = new AgentToolDispatcher({ autoApprovalReview: async () => { controller.abort(); return ['破坏性']; } });
  assert.equal(await dispatcher.execute(part(), write(() => effects++), true, true, [], 'normal', undefined, controller.signal), null);
  assert.equal(effects, 0);
  const failed = new AgentToolDispatcher({ autoApprovalReview: async () => { throw new Error('unavailable'); } });
  assert.equal((await failed.execute(part(), write(() => effects++), true, true))?.output.length, 1);
  assert.equal(effects, 1);
});

const chunk = (parts: import('../main/ets/chat/message.ts').UIMessagePart[]) => ({ id: 'c', model: 'm', usage: null,
  choices: [{ index: 0, delta: makeUIMessage('assistant', parts), message: null, finishReason: 'unknown' as const }] });

test('real frontend loop persists review pending and pinned target; approved continuation executes once', async () => {
  let effects = 0; let evaluations = 0; const saved: import('../main/ets/chat/conversation.ts').Conversation[] = [];
  const dispatcher = new AgentToolDispatcher({ autoApprovalReview: async () => { evaluations++; return ['破坏性']; } });
  let round = 0;
  const provider = { streamText: async (_messages: unknown, onChunk: (c: import('../main/ets/chat/message.ts').MessageChunk) => void) => {
    onChunk(round++ === 0 ? chunk([part()]) : chunk([{ type: 'text', text: 'done', metadata: null }]));
  } };
  const deps = { assistant: makeAssistant({}), inputTransformers: [], outputTransformers: [], provider, store: { save: async (conv: import('../main/ets/chat/conversation.ts').Conversation) => { saved.push(conv); } } };
  const loop = { tools: [write(() => { assert.ok(saved.some(c => currentMessages(c).some(m => m.parts.some(p => p.type === 'tool' && p.approvalState.type === 'pending')))); effects++; })],
    dispatcher, makeProviderForStep: () => provider, autoApproveTools: true, autoApproveHighRiskTools: true, captureInvocationMetadata: () => ({ terminal_target: { profileId: 'pinned' } }) };
  const pending = await runAgenticToolLoop(makeConversation('front', [toMessageNode(makeUserMessage('update file'))]), deps, loop);
  const pendingPart = currentMessages(pending).at(-1)!.parts[0] as UIMessagePartTool;
  assert.equal(pendingPart.approvalState.type, 'pending'); assert.equal(effects, 0); assert.equal(evaluations, 1);
  assert.match(JSON.stringify(pendingPart.metadata?.['permission_trace']), /jev_auto_approval.*破坏性/);
  const approved = { ...pending, messageNodes: pending.messageNodes.map(n => ({ ...n, messages: n.messages.map(m => ({ ...m,
    parts: m.parts.map(p => p.type === 'tool' ? { ...p, approvalState: { type: 'approved' as const } } : p) })) })) };
  await runToolLoopContinuation(approved, deps, loop);
  assert.equal(effects, 1); assert.equal(evaluations, 1);
});

test('actual child generation receives request scope and returns existing approval_required result without recipe tools', async () => {
  let effects = 0; let scopes = 0;
  const port = new ChatStreamSubAgentGenerationPort({ resolveModel: async () => makeProviderModel({ id: 'm', modelId: 'm' }),
    resolveAssistant: async () => ({ assistant: makeAssistant({}), generationRetry: makeGenerationRetrySetting({ enabled: false }), autoApproveTools: true, autoApproveHighRiskTools: true }),
    makeProvider: () => ({ streamText: async (_messages, onChunk) => onChunk(chunk([part()])) }),
    configureRecipeLoop: async (loop, request) => { assert.ok(request.messages.some(m => m.role === 'user')); scopes++;
      loop.dispatcher = new AgentToolDispatcher({ autoApprovalReview: async () => ['破坏性'] }); },
  });
  const answer = await new GenerationSubAgentRunner(port).run(makeSubAgentDefinition({ id: 'child', name: 'Child', description: 'Scoped child', systemPrompt: 'Do requested task', toolAllowlist: ['file_write'] }),
    makeSubAgentTaskSpec({ objective: 'change file', outputFormat: 'status', toolsAndSources: 'file_write', boundaries: 'Only requested file' }), [write(() => effects++)], () => {}, () => {});
  assert.equal(effects, 0); assert.equal(scopes, 1); assert.match(JSON.stringify(answer), /approval_required/);
});

test('metadata-only consent reviews destructive/exfiltration without sending user task or alignment questions', async () => {
  let requests = 0;
  const config = makeJevSettings({ mode: 'active', autoApproval: { mode: 'active', allowToolMetadata: true, allowTaskText: false } });
  const review = createJevAutoApprovalReview({ loadSettings: async () => config, recentUserTexts: ['TASK_NOT_CONSENTED'],
    evaluate: async (state, questions) => { requests++; assert.equal(state['user_requests'], undefined);
      assert.deepEqual(Object.keys(questions), ['destructive', 'exfiltration']);
      assert.doesNotMatch(JSON.stringify(state), /TASK_NOT_CONSENTED/); return result({ destructive: .9 }); } });
  assert.deepEqual(await review(part(), local()), ['破坏性']); assert.equal(requests, 1);
});

test('review transports the original build consent snapshot to the evaluator dispatch boundary', async () => {
  const expected = settings(); let sent = false;
  const review = createJevAutoApprovalReview({ loadSettings: async () => expected, recentUserTexts: ['task'],
    evaluate: async (_state, _questions, _signal, snapshot) => {
      sent = true; assert.equal(snapshot, expected);
      return { ok: false, reason: 'settings_changed', shadow: false, evaluation: null };
    } });
  assert.deepEqual(await review(part(), local()), []); assert.equal(sent, true);
});

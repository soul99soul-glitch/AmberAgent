import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePartTool } from '../main/ets/chat/message.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { AgentToolDispatcher } from '../main/ets/chat/tool_dispatcher.ts';
import { PermissionDecisionResolver } from '../main/ets/chat/tool_permission.ts';
import { TerminalController } from '../main/ets/chat/terminal/control.ts';
import type { RecipeDescriptor, RecipeManifest, RecipeRunCheckpoint } from '../main/ets/chat/recipes/models.ts';
import type { RecipeExecutionPort, RecipeImportPreview, RecipeStore } from '../main/ets/chat/recipes/ports.ts';
import { canonicalRecipeJSON } from '../main/ets/chat/recipes/validation.ts';
import { createRecipeLoopAdapter } from '../main/ets/chat/recipes/runner.ts';
import { createRecipeTools } from '../main/ets/chat/recipes/tools.ts';
import * as recipeRunner from '../main/ets/chat/recipes/runner.ts';
import { makeConversation, makeMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import { toolActivityStatus, toolActivityOutputTail, toolOutputJson } from '../main/ets/chat/tool_activity.ts';

const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;
const descriptor = (): RecipeDescriptor => {
  const manifest: RecipeManifest = { schema: 'amber.recipe.v1', name: 'normalize', version: '1', description: 'Normalize JSON',
    inputs: { path: 'string', destination: 'string' }, steps: [
      { id: 'read', tool: 'file_read', arguments: { path: '${input.path}' } },
      { id: 'compute', tool: 'python_execute', arguments: { code: 'print(stdin)', stdin: '${step.read.output.content}' } },
      { id: 'save', tool: 'file_write', arguments: { path: '${input.destination}', content: '${step.compute.output.stdout}' } },
    ], outputs: { saved_path: '${step.save.output.path}' } };
  return { manifest, canonicalJSON: canonicalRecipeJSON(manifest), hash: 'fixture-hash' };
};
const parent = (name: string = 'recipe__normalize', input: string = '{"path":"a.json","destination":"b.json"}'): UIMessagePartTool => ({
  type: 'tool', toolName: name, toolCallId: 'parent', input, output: [], approvalState: { type: 'auto' }, metadata: { recipe_manual: true },
});
const payload = (p: UIMessagePartTool): JsonObject => JSON.parse((p.output[0] as { text: string }).text) as JsonObject;
const checkpoint = (p: UIMessagePartTool): RecipeRunCheckpoint => p.metadata!['recipe_v1'] as unknown as RecipeRunCheckpoint;
const output = (p: UIMessagePartTool, value: JsonObject): UIMessagePartTool => ({ ...p, output: [{ type: 'text', text: JSON.stringify(value), metadata: null }] });

const fixture = () => {
  const d = descriptor();
  const calls: string[] = [], captures: UIMessagePartTool[] = [], saved: UIMessagePartTool[] = [];
  const defs: AgentTool[] = ['file_read', 'python_execute', 'file_write'].map((name) => makeAgentTool({
    name, description: name, needsApproval: name !== 'file_read', allowsAutoApproval: false,
    execute: async (v: JsonValue) => {
      assert.equal(checkpoint(saved[saved.length - 1]!).phase, 'started');
      assert.equal(checkpoint(saved[saved.length - 1]!).pendingStep!.toolName, name);
      calls.push(name);
      const j = v as JsonObject;
      const value: JsonObject = name === 'file_read' ? { content: '{"b":2}' } : name === 'python_execute' ?
        { status: 'completed', stdout: j['stdin']! } : { path: j['path']! };
      return [{ type: 'text' as const, text: JSON.stringify(value), metadata: null }];
    },
  }));
  const dispatcher = new AgentToolDispatcher({ resolver: new PermissionDecisionResolver(), hooks: [] });
  const preview: RecipeImportPreview = { workspacePath: 'recipe.json', candidate: d, baseHash: null,
    envelope: { tools: defs.map((t) => t.name), mutates: true, needsApproval: true, risk: 'high' } };
  let applied = 0;
  const store: RecipeStore = { listInstalled: async () => [{ descriptor: d, enabled: true }], prepareImport: async () => clone(preview),
    applyImport: async (p) => { assert.deepEqual(p, preview); applied++; return d; }, setEnabled: async () => {}, remove: async () => {} };
  const port: RecipeExecutionPort = {
    primitive: (name) => defs.find((v) => v.name === name) ?? null,
    capture: (p) => { captures.push(clone(p)); return { terminal_target: { host: 'original' } }; },
    decide: (p, def) => dispatcher.resolveDecision(def, p, false, false, []),
    dispatch: (p, def, signal) => dispatcher.execute(p, def, false, false, [], 'normal', undefined, signal),
    saveParent: async (p) => { saved.push(clone(p)); },
  };
  const adapter = createRecipeLoopAdapter({ store, installed: [{ descriptor: d, enabled: true }] });
  return { d, defs, store, adapter, port, calls, captures, saved, applied: () => applied };
};

test('one approval advances only its pinned step; binding/capture/started saves use real dispatcher', async () => {
  const f = fixture();
  const initial = await f.adapter.prepare(parent(), f.defs);
  let p = await f.adapter.advance(initial, f.port);
  assert.equal(p.approvalState.type, 'pending'); assert.deepEqual(p.output, []);
  assert.deepEqual(f.calls, ['file_read']);
  assert.equal(checkpoint(p).pendingStep!.metadata!['terminal_target'] !== undefined, true);
  const id = checkpoint(p).pendingStep!.toolCallId;
  f.d.manifest.version = '2';
  p = await f.adapter.prepare({ ...p, approvalState: { type: 'approved' } }, f.defs);
  assert.equal(checkpoint(p).descriptor.manifest.version, '1');
  p = await f.adapter.advance(p, f.port);
  assert.deepEqual(f.calls, ['file_read', 'python_execute']); assert.equal(p.approvalState.type, 'pending');
  assert.notEqual(checkpoint(p).pendingStep!.toolCallId, id); assert.equal(f.captures.length, 3);
  p = await f.adapter.advance({ ...p, approvalState: { type: 'approved' } }, f.port);
  assert.equal(payload(p)['status'], 'succeeded'); assert.deepEqual(payload(p)['outputs'], { saved_path: 'b.json' });
  assert.equal(p.metadata!['recipe_manual'], true);
  assert.equal(f.saved.filter((s) => checkpoint(s).phase === 'started').length, 3);
});

test('deny or failed result stops the next effect without replaying completed read', async () => {
  const f = fixture();
  let p = await f.adapter.advance(await f.adapter.prepare(parent(), f.defs), f.port);
  p = await f.adapter.advance({ ...p, approvalState: { type: 'denied', reason: 'No write' } }, f.port);
  assert.equal(payload(p)['status'], 'failed'); assert.equal(payload(p)['error_code'], 'step_denied');
  assert.deepEqual(f.calls, ['file_read']);
  const g = fixture();
  g.port.dispatch = async (part) => output(part, { ok: false, error: 'broken' });
  const failed = await g.adapter.advance(await g.adapter.prepare(parent(), g.defs), g.port);
  assert.equal(payload(failed)['status'], 'failed'); assert.equal(payload(failed)['failed_step'], 'read');
  assert.equal(g.captures.length, 1);
});

test('failed started save performs no effect; failed result save leaves started and never replays', async () => {
  const f = fixture();
  f.port.saveParent = async () => { throw new Error('disk unavailable'); };
  await assert.rejects(f.adapter.advance(await f.adapter.prepare(parent(), f.defs), f.port), /disk unavailable/);
  assert.deepEqual(f.calls, []);
  const g = fixture();
  let durable: UIMessagePartTool | null = null;
  g.port.saveParent = async (p) => {
    if (checkpoint(p).phase === 'ready') throw new Error('result save failed');
    durable = clone(p); g.saved.push(clone(p));
  };
  await assert.rejects(g.adapter.advance(await g.adapter.prepare(parent(), g.defs), g.port), /result save failed/);
  assert.deepEqual(g.calls, ['file_read']); assert.equal(checkpoint(durable!).phase, 'started');
  g.port.saveParent = async (p) => { durable = clone(p); };
  const resumed = await g.adapter.advance(durable!, g.port);
  assert.equal(payload(resumed)['status'], 'outcome_unknown'); assert.deepEqual(g.calls, ['file_read']);
});

test('cancel and timeout wait for actual dispatch cancellation before stopping', async () => {
  for (const timeout of [false, true]) {
    const f = fixture(); f.d.manifest.steps[0]!.timeoutSeconds = 1; f.d.canonicalJSON = canonicalRecipeJSON(f.d.manifest);
    const adapter = createRecipeLoopAdapter({ store: f.store, installed: [{ descriptor: f.d, enabled: true }] });
    const controller = new TerminalController(); let settled = false;
    f.port.dispatch = (p, _t, signal?: AbortSignalLike) => new Promise((resolve) => {
      signal!.addEventListener!('abort', () => { setTimeout(() => { settled = true; resolve(output(p, { status: 'cancelled' })); }, 5); });
      if (!timeout) controller.abort();
    });
    const p = await adapter.advance(await adapter.prepare(parent(), f.defs), f.port, controller.signal);
    assert.equal(settled, true); assert.equal(payload(p)['status'], timeout ? 'failed' : 'cancelled');
    assert.equal(f.captures.length, 1);
  }
});

test('import pins preview through approval and explicit allow; wrappers cannot directly execute', async () => {
  const f = fixture(); const tools = createRecipeTools({ store: f.store, installed: [{ descriptor: f.d, enabled: true }], primitives: f.defs });
  const importDef = tools.find((v) => v.name === 'recipe_import')!;
  f.port.primitive = (name) => name === 'recipe_import' ? importDef : f.defs.find((v) => v.name === name) ?? null;
  let p = await f.adapter.prepare(parent('recipe_import', '{"workspace_path":"recipe.json"}'), f.defs);
  p = await f.adapter.advance(p, f.port); assert.equal(p.approvalState.type, 'pending'); assert.deepEqual(p.output, []); assert.equal(f.applied(), 0);
  const stillPending = await f.adapter.advance(p, f.port);
  assert.equal(stillPending.approvalState.type, 'pending'); assert.equal(f.applied(), 0);
  f.store.prepareImport = async () => { throw new Error('must not recreate preview'); };
  p = await f.adapter.prepare({ ...p, approvalState: { type: 'approved' } }, f.defs);
  p = await f.adapter.advance(p, f.port); assert.equal(payload(p)['status'], 'succeeded'); assert.equal(f.applied(), 1);
  for (const name of ['recipe_import', 'recipe__normalize']) {
    const result = await tools.find((v) => v.name === name)!.execute({});
    assert.equal(JSON.parse((result[0] as { text: string }).text).error_code, 'recipe_adapter_required');
  }
  const g = fixture(); g.port.primitive = () => importDef;
  const dispatcher = new AgentToolDispatcher({ resolver: new PermissionDecisionResolver(), hooks: [] });
  g.port.decide = (part, def) => dispatcher.resolveDecision(def, part, true, true, []);
  const allowed = await g.adapter.advance(await g.adapter.prepare(parent('recipe_import', '{"workspace_path":"recipe.json"}'), g.defs), g.port);
  assert.equal(payload(allowed)['status'], 'succeeded'); assert.equal(g.applied(), 1);
});

test('ask_user consumes the real parent answer through dispatcher; approval does not invent an answer', async () => {
  const f = fixture();
  f.d.manifest.steps = [{ id: 'ask', tool: 'ask_user', arguments: { question: 'Continue?' } }];
  f.d.manifest.outputs = {}; f.d.canonicalJSON = canonicalRecipeJSON(f.d.manifest);
  let executed = false;
  const ask = makeAgentTool({ name: 'ask_user', description: 'Question', needsApproval: true,
    execute: async () => { executed = true; throw new Error('ask_user answer is supplied by dispatcher'); } });
  f.defs.push(ask);
  const adapter = createRecipeLoopAdapter({ store: f.store, installed: [{ descriptor: f.d, enabled: true }] });
  let p = await adapter.advance(await adapter.prepare(parent(), f.defs), f.port);
  const stillWaiting = await adapter.advance({ ...p, approvalState: { type: 'approved' } }, f.port);
  assert.equal(stillWaiting.approvalState.type, 'pending'); assert.deepEqual(stillWaiting.output, []); assert.equal(executed, false);
  p = await adapter.advance({ ...p, approvalState: { type: 'answered', answer: 'Yes, continue.' } }, f.port);
  assert.equal(payload(p)['status'], 'succeeded'); assert.equal(checkpoint(p).stepOutputs['ask'], 'Yes, continue.');
  assert.equal(executed, false);
});

test('cold Recipe recovery finishes started without effects and keeps cancelled/unknown activity states', async () => {
  for (const name of ['recipe__normalize', 'recipe_import']) {
    const cancelled = output(parent(name), { status: 'cancelled', message: 'Cancelled before the next effect.' });
    assert.equal(toolActivityStatus(cancelled, false, toolOutputJson(cancelled)), 'cancelled');
    const unknown = output(parent(name), { status: 'outcome_unknown', message: 'No saved result; do not replay.' });
    assert.equal(toolActivityStatus(unknown, false, toolOutputJson(unknown)), 'failed');
    assert.match(toolActivityOutputTail(unknown, toolOutputJson(unknown)), /No saved result; do not replay/);
  }
  const ordinary = output(parent('ordinary_tool'), { status: 'cancelled' });
  assert.equal(toolActivityStatus(ordinary, false, toolOutputJson(ordinary)), 'succeeded');

  const f = fixture();
  const ready = await f.adapter.prepare(parent(), f.defs);
  const progress: RecipeRunCheckpoint = { ...checkpoint(ready), nextIndex: 1, completedSteps: ['read'],
    stepOutputs: { read: '{"content":"{\\"b\\":2}"}' } };
  const startedCheckpoint: RecipeRunCheckpoint = { ...progress, phase: 'started',
    pendingStep: recipeRunner.nextRecipeStep(progress) };
  const started: UIMessagePartTool = { ...ready, toolCallId: 'interrupted',
    metadata: { ...ready.metadata, recipe_v1: clone(startedCheckpoint) as unknown as JsonValue } };
  const waiting: UIMessagePartTool = { ...started, toolCallId: 'waiting', approvalState: { type: 'pending' },
    metadata: { ...started.metadata, recipe_v1: { ...clone(startedCheckpoint), phase: 'awaiting_approval' } as unknown as JsonValue } };
  const ended = output({ ...started, toolCallId: 'ended' }, { status: 'succeeded' });
  const foreign: UIMessagePartTool = { ...started, toolName: 'ordinary_tool', toolCallId: 'foreign' };
  const text = { type: 'text' as const, text: 'Keep this content.', metadata: null };
  const otherBranch = makeUIMessage('assistant', [text], { id: 'other-branch' });
  const message = makeUIMessage('assistant', [text, started, ready, waiting, ended, foreign], { id: 'started-message' });
  const node = makeMessageNode([otherBranch, message], 1, 'changed-node');
  const untouchedNode = makeMessageNode([makeUIMessage('user', [text])], 0, 'untouched-node');
  const conversation = makeConversation('cold-recipe', [node, untouchedNode], { title: 'Preserve title' });
  const recovered = recipeRunner.recoverInterruptedRecipes(conversation);
  assert.notEqual(recovered, conversation);
  assert.equal(recovered.messageNodes[1], untouchedNode);
  assert.equal(recovered.messageNodes[0]!.selectIndex, 1);
  assert.equal(recovered.messageNodes[0]!.messages[0], otherBranch);
  const recoveredMessage = recovered.messageNodes[0]!.messages[1]!;
  assert.notEqual(recoveredMessage, message);
  for (const index of [0, 2, 3, 4, 5]) assert.equal(recoveredMessage.parts[index], message.parts[index]);
  const recoveredPart = recoveredMessage.parts[1] as UIMessagePartTool;
  assert.equal(payload(recoveredPart)['status'], 'outcome_unknown');
  assert.equal(payload(recoveredPart)['failed_step'], 'compute');
  assert.deepEqual(payload(recoveredPart)['completed_steps'], ['read']);
  assert.equal(checkpoint(recoveredPart).phase, 'finished');
  assert.equal(checkpoint(recoveredPart).pendingStep, null);
  assert.equal(toolActivityStatus(recoveredPart, false, toolOutputJson(recoveredPart)), 'failed');
  assert.match(toolActivityOutputTail(recoveredPart, toolOutputJson(recoveredPart)), /will not be replayed/);
  assert.equal(checkpoint(started).phase, 'started');
  assert.deepEqual(started.output, []);
  assert.equal(recipeRunner.recoverInterruptedRecipes(recovered), recovered);
  assert.deepEqual(f.calls, []); assert.deepEqual(f.captures, []); assert.deepEqual(f.saved, []);
  assert.equal(f.applied(), 0);
});

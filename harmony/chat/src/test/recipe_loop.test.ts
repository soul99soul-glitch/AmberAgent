import { jevAutoApprovalReason } from '../main/ets/chat/jev_auto_approval.ts';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeConversation, currentMessages, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { ConversationStore, ChatStreamProvider, ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { MessageChunk, UIMessagePart, UIMessagePartTool } from '../main/ets/chat/message.ts';
import { makeUIMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import type { ChatToolDefinition } from '../main/ets/chat/provider_model.ts';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import { AgentToolDispatcher } from '../main/ets/chat/tool_dispatcher.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';
import { createToolSearchTool, TOOL_SEARCH_AUTO_THRESHOLD } from '../main/ets/chat/builtin_introspection_tools.ts';
import { runToolLoopContinuation, runChatTurnWithTools } from '../main/ets/chat/tool_loop.ts';
import type { ToolLoopOptions } from '../main/ets/chat/tool_loop.ts';
import type { InstalledRecipe, RecipeDescriptor, RecipeManifest, RecipeRunCheckpoint } from '../main/ets/chat/recipes/models.ts';
import type { RecipeStore, RecipeImportPreview } from '../main/ets/chat/recipes/ports.ts';
import { createRecipeLoopAdapter, createRecipeRun } from '../main/ets/chat/recipes/runner.ts';
import { createRecipeTools } from '../main/ets/chat/recipes/tools.ts';
import { canonicalRecipeJSON, recipeEnvelope } from '../main/ets/chat/recipes/validation.ts';

const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const output = (value: JsonObject): UIMessagePart[] => [{ type: 'text', text: JSON.stringify(value), metadata: null }];
const payload = (part: UIMessagePartTool): JsonObject => JSON.parse((part.output[0] as { text: string }).text) as JsonObject;
const call = (id: string, name: string, input: JsonObject, metadata: JsonObject | null = null): UIMessagePartTool => ({
  type: 'tool', toolCallId: id, toolName: name, input: JSON.stringify(input), output: [], approvalState: { type: 'auto' }, metadata,
});
const partOf = (conv: Conversation, id: string): UIMessagePartTool => {
  const part = currentMessages(conv).flatMap((message) => message.parts).find((part) => part.type === 'tool' && part.toolCallId === id);
  assert.ok(part && part.type === 'tool'); return part;
};
const checkpoint = (part: UIMessagePartTool): RecipeRunCheckpoint => part.metadata!['recipe_v1'] as unknown as RecipeRunCheckpoint;
const approve = (conv: Conversation, id: string): Conversation => ({ ...conv, messageNodes: conv.messageNodes.map((node) => ({ ...node,
  messages: node.messages.map((message, index) => index !== node.selectIndex ? message : { ...message,
    parts: message.parts.map((part) => part.type === 'tool' && part.toolCallId === id ? { ...part, approvalState: { type: 'approved' as const } } : part),
  }),
})) });
const conversation = (parts: UIMessagePartTool[]): Conversation => makeConversation('recipe-fixture', [
  toMessageNode(makeUserMessage('Run the requested Recipe')), toMessageNode(makeUIMessage('assistant', parts)),
]);
const descriptor = (manifest: RecipeManifest): RecipeDescriptor => {
  const canonicalJSON = canonicalRecipeJSON(manifest); const bytes = Buffer.from(canonicalJSON); const size = Buffer.alloc(8);
  size.writeBigUInt64BE(BigInt(bytes.length));
  const hash = createHash('sha256').update('amber.recipe.package.v1\0').update(size).update(bytes).digest('hex');
  return { manifest: copy(manifest), canonicalJSON, hash };
};
const writeRecipe = (version: string = '1'): RecipeDescriptor => descriptor({ schema: 'amber.recipe.v1', name: 'copy_text', version,
  description: 'Copy a Workspace text file', inputs: { source: 'string', destination: 'string' }, steps: [
    { id: 'read', tool: 'file_read', arguments: { path: '${input.source}' } },
    { id: 'write', tool: 'file_write', arguments: { path: '${input.destination}', content: '${step.read.output.content}' } },
  ], outputs: { path: '${step.write.output.path}' },
});
const persisted = (candidate: RecipeDescriptor, installed: InstalledRecipe[] = []) => {
  const state = { installed: copy(installed), applies: 0, previews: 0, saved: [] as Conversation[] };
  const store: RecipeStore = {
    listInstalled: async () => copy(state.installed),
    prepareImport: async (workspacePath, primitives) => {
      state.previews++;
      return { workspacePath, candidate: copy(candidate), baseHash: state.installed.find((entry) => entry.descriptor.manifest.name === candidate.manifest.name)?.descriptor.hash ?? null,
        envelope: recipeEnvelope(candidate.manifest, primitives) };
    },
    applyImport: async (preview: RecipeImportPreview) => {
      state.applies++; state.installed = [{ descriptor: copy(preview.candidate), enabled: true }]; return copy(preview.candidate);
    },
    setEnabled: async (name, expectedHash, enabled) => {
      const entry = state.installed.find((entry) => entry.descriptor.manifest.name === name); assert.equal(entry?.descriptor.hash, expectedHash);
      entry!.enabled = enabled;
    },
    remove: async (name, expectedHash) => {
      assert.equal(state.installed.find((entry) => entry.descriptor.manifest.name === name)?.descriptor.hash, expectedHash);
      state.installed = state.installed.filter((entry) => entry.descriptor.manifest.name !== name);
    },
  };
  const conversations: ConversationStore = { save: async (conv) => { state.saved.push(copy(conv)); } };
  return { state, store, conversations };
};
const chunk = (parts: UIMessagePart[]): MessageChunk => ({ id: 'fixture', model: 'fixture-model', usage: null,
  choices: [{ index: 0, delta: makeUIMessage('assistant', parts), message: null, finishReason: 'unknown' }],
});
const scripted = (rounds: UIMessagePart[][]) => {
  const state = { streams: 0, schemas: [] as ChatToolDefinition[][] };
  const provider: ChatStreamProvider = { streamText: async (_messages, onChunk) => {
    const index = state.streams++; assert.ok(index < rounds.length, 'loop requested an unexpected model round');
    onChunk(chunk(rounds[index]!));
  } };
  return { state, provider, factory: (defs: ChatToolDefinition[]) => { state.schemas.push(copy(defs)); return provider; } };
};
const deps = (provider: ChatStreamProvider, store: ConversationStore): ChatTurnDeps => ({
  assistant: makeAssistant({}), provider, store, inputTransformers: [], outputTransformers: [],
});
const primitives = (calls: string[]): AgentTool[] => [
  makeAgentTool({ name: 'file_read', description: 'Read Workspace text', parameters: () => makeInputSchemaObj({ path: { type: 'string' } }, ['path']),
    execute: async () => { calls.push('read'); return output({ content: 'original text' }); } }),
  makeAgentTool({ name: 'file_write', description: 'Write Workspace text', needsApproval: true, allowsAutoApproval: false,
    parameters: () => makeInputSchemaObj({ path: { type: 'string' }, content: { type: 'string' } }, ['path', 'content']),
    execute: async (value: JsonValue) => { const input = value as JsonObject; calls.push('write:' + input['path']); return output({ path: input['path']! }); } }),
];

// Real loop/adapter/dispatcher; JSON-cloned store snapshots model durable state.
test('manual AUTO checkpoint runs and resumes without model calls; missing parent fails without streaming', async () => {
  const d = writeRecipe(); const calls: string[] = []; const defs = primitives(calls);
  const f = persisted(d, [{ descriptor: d, enabled: true }]); const model = scripted([]);
  const parent = call('manual', 'recipe__copy_text', { source: 'input.txt', destination: 'output.txt' },
    { recipe_manual: true, recipe_v1: createRecipeRun(d, { source: 'input.txt', destination: 'output.txt' }) as unknown as JsonValue });
  const loop: ToolLoopOptions = { tools: [...defs, ...createRecipeTools({ store: f.store, installed: f.state.installed, primitives: defs })],
    recipeAdapter: createRecipeLoopAdapter({ store: f.store, installed: f.state.installed }), manualToolCallId: 'manual',
    dispatcher: new AgentToolDispatcher({ hooks: [] }), makeProviderForStep: model.factory };
  let conv = await runToolLoopContinuation(conversation([parent]), deps(model.provider, f.conversations), loop);
  const pending = partOf(conv, 'manual'); assert.equal(pending.approvalState.type, 'pending'); assert.deepEqual(pending.output, []);
  assert.deepEqual(calls, ['read']); assert.equal(checkpoint(pending).phase, 'awaiting_approval'); assert.equal(model.state.streams, 0);
  conv = await runToolLoopContinuation(approve(conv, 'manual'), deps(model.provider, f.conversations), loop);
  const result = partOf(conv, 'manual'); assert.equal(payload(result)['status'], 'succeeded'); assert.equal(checkpoint(result).phase, 'finished');
  assert.deepEqual(calls, ['read', 'write:output.txt']); assert.equal(model.state.streams, 0);
  assert.deepEqual(partOf(f.state.saved.at(-1)!, 'manual'), result);
  await assert.rejects(runToolLoopContinuation(makeConversation('missing', [toMessageNode(makeUserMessage('No parent'))]),
    deps(model.provider, f.conversations), loop), /missing its persisted parent/);
  assert.equal(model.state.streams, 0); assert.deepEqual(calls, ['read', 'write:output.txt']);
});

test('mixed batch pauses later effects, resumes the pinned old Recipe and preserves checkpoint through ordinary writeback', async () => {
  const d = writeRecipe(); const calls: string[] = []; const defs = primitives(calls);
  const f = persisted(d, [{ descriptor: d, enabled: true }]);
  const model = scripted([[call('recipe', 'recipe__copy_text', { source: 'input.txt', destination: 'recipe-output.txt' }),
    call('ordinary', 'file_write', { path: 'ordinary-output.txt', content: 'ordinary text' })], [{ type: 'text', text: 'Complete', metadata: null }]]);
  const loop: ToolLoopOptions = { tools: [...defs, ...createRecipeTools({ store: f.store, installed: f.state.installed, primitives: defs })],
    recipeAdapter: createRecipeLoopAdapter({ store: f.store, installed: f.state.installed }), autoApprovedToolNames: ['file_write'],
    dispatcher: new AgentToolDispatcher({ hooks: [] }), makeProviderForStep: model.factory,
    captureInvocationMetadata: () => ({ fixture_target: 'original' }) };
  let conv = await runChatTurnWithTools(makeConversation('mixed'), 'Copy and then write', deps(model.provider, f.conversations), loop);
  const pending = partOf(conv, 'recipe'); const pinned = copy(checkpoint(pending));
  assert.equal(pending.approvalState.type, 'pending'); assert.deepEqual(calls, ['read']); assert.equal(model.state.streams, 1);
  assert.deepEqual(partOf(conv, 'ordinary').output, []);
  const replacement = writeRecipe('2'); f.state.installed = [{ descriptor: replacement, enabled: true }];
  loop.tools = [...defs, ...createRecipeTools({ store: f.store, installed: f.state.installed, primitives: defs })];
  loop.recipeAdapter = createRecipeLoopAdapter({ store: f.store, installed: f.state.installed });
  conv = await runToolLoopContinuation(approve(conv, 'recipe'), deps(model.provider, f.conversations), loop);
  const result = partOf(conv, 'recipe');
  assert.equal(payload(result)['status'], 'succeeded'); assert.equal(payload(result)['hash'], d.hash);
  assert.deepEqual(checkpoint(result).descriptor, pinned.descriptor); assert.equal(checkpoint(result).phase, 'finished');
  assert.equal(checkpoint(result).executionId, pinned.executionId); assert.deepEqual(checkpoint(result).completedSteps, ['read', 'write']);
  assert.deepEqual(calls, ['read', 'write:recipe-output.txt', 'write:ordinary-output.txt']);
  assert.ok(partOf(conv, 'ordinary').output.length > 0); assert.equal(model.state.streams, 2);
  assert.deepEqual(checkpoint(partOf(f.state.saved.at(-1)!, 'recipe')), checkpoint(result));
});

test('same-turn import refreshes search/schema/execution; disabled and deleted next rounds cannot use a stale wrapper map', async () => {
  const d = descriptor({ schema: 'amber.recipe.v1', name: 'double_number', version: '1', description: 'Double a number', inputs: { amount: 'number' },
    steps: [{ id: 'compute', tool: 'math_double', arguments: { amount: '${input.amount}' } }], outputs: { doubled: '${step.compute.output.doubled}' } });
  const f = persisted(d); const mathInputs: number[] = [];
  const math = makeAgentTool({ name: 'math_double', description: 'Arithmetic primitive', parameters: () => makeInputSchemaObj({ amount: { type: 'number' } }, ['amount']),
    execute: async (value) => { const amount = (value as JsonObject)['amount'] as number; mathInputs.push(amount); return output({ doubled: amount * 2 }); } });
  const defs: AgentTool[] = [math, ...Array.from({ length: TOOL_SEARCH_AUTO_THRESHOLD + 1 }, (_, index) => makeAgentTool({
    name: 'fixture_' + index, description: 'Unused catalog filler', execute: async () => output({ unused: true }),
  }))];
  const model = scripted([
    [call('search-installed', 'tool_search', { query: 'recipe__double_number' })],
    [call('run-installed', 'recipe__double_number', { amount: 21 })],
    [call('run-disabled', 'recipe__double_number', { amount: 22 })],
    [call('search-deleted', 'tool_search', { query: 'recipe__double_number' })],
    [call('run-deleted', 'recipe__double_number', { amount: 23 })],
    [{ type: 'text', text: 'Finished catalog checks', metadata: null }],
  ]);
  let refreshes = 0;
  const loop: ToolLoopOptions = { tools: [], dispatcher: new AgentToolDispatcher({ hooks: [] }), makeProviderForStep: model.factory };
  const refresh = async (): Promise<AgentTool[]> => {
    if (refreshes === 3) await f.store.setEnabled(d.manifest.name, d.hash, false);
    if (refreshes === 4) await f.store.remove(d.manifest.name, d.hash);
    refreshes++;
    const installed = await f.store.listInstalled(); const catalog = [...defs, ...createRecipeTools({ store: f.store, installed, primitives: defs })];
    loop.recipeAdapter = createRecipeLoopAdapter({ store: f.store, installed });
    return [...catalog, createToolSearchTool(createToolRegistry(catalog))];
  };
  loop.tools = await refresh(); refreshes = 0; loop.refreshTools = refresh;
  const prepared = await loop.recipeAdapter!.prepare(call('import', 'recipe_import', { workspace_path: 'recipe.json' }), defs);
  const approved = { ...prepared, approvalState: { type: 'approved' as const } };
  const conv = await runToolLoopContinuation(conversation([approved]), deps(model.provider, f.conversations), loop);
  assert.equal(f.state.applies, 1); assert.equal(f.state.previews, 1); assert.equal(payload(partOf(conv, 'import'))['status'], 'succeeded');
  assert.deepEqual(payload(partOf(conv, 'search-installed'))['expanded_tools'], ['recipe__double_number']);
  assert.equal(model.state.schemas[0]!.some((schema) => schema.name === 'recipe__double_number'), false);
  assert.equal(model.state.schemas[1]!.some((schema) => schema.name === 'math_double'), false, 'hidden primitive must still be callable inside Recipe');
  const wrapper = model.state.schemas[1]!.find((schema) => schema.name === 'recipe__double_number')!;
  assert.deepEqual(wrapper.parameters, { type: 'object', properties: { amount: { type: 'number' } }, required: ['amount'] });
  assert.deepEqual(payload(partOf(conv, 'run-installed'))['outputs'], { doubled: 42 }); assert.deepEqual(mathInputs, [21]);
  for (const schemas of model.state.schemas.slice(2)) assert.equal(schemas.some((schema) => schema.name === 'recipe__double_number'), false);
  assert.equal(payload(partOf(conv, 'run-disabled'))['status'], 'failed'); assert.equal(payload(partOf(conv, 'run-deleted'))['status'], 'failed');
  assert.deepEqual(payload(partOf(conv, 'search-deleted'))['expanded_tools'], []); assert.deepEqual(f.state.installed, []);
  const scoped = scripted([[call('scope-denied', 'recipe__double_number', { amount: 24 })], [{ type: 'text', text: 'Scope check complete', metadata: null }]]);
  const scopedConv = await runChatTurnWithTools(makeConversation('scoped'), 'Call an unavailable Recipe', deps(scoped.provider, f.conversations), {
    tools: defs, recipeAdapter: createRecipeLoopAdapter({ store: f.store, installed: [{ descriptor: d, enabled: true }] }),
    dispatcher: new AgentToolDispatcher({ hooks: [] }), makeProviderForStep: scoped.factory,
  });
  assert.equal(payload(partOf(scopedConv, 'scope-denied'))['status'], 'failed'); assert.deepEqual(mathInputs, [21]);
});

test('auto approval review pauses real Recipe primitive checkpoint, retains target and resumes one effect', async () => {
  const d = writeRecipe(); const f = persisted(d, [{ descriptor: d, enabled: true }]);
  const effects: string[] = []; let reviewed = 0;
  const defs = primitives(effects);
  const model = scripted([[call('parent-review', 'recipe__copy_text', { source: 'input.txt', destination: 'output.txt' })],
    [{ type: 'text', text: 'complete', metadata: null }]]);
  const dispatcher = new AgentToolDispatcher({ autoApprovalReview: async () => { reviewed++; return ['破坏性']; } });
  const loop: ToolLoopOptions = { tools: [...defs, ...createRecipeTools({ store: f.store, installed: f.state.installed, primitives: defs })],
    recipeAdapter: createRecipeLoopAdapter({ store: f.store, installed: f.state.installed }), dispatcher,
    autoApproveTools: true, autoApproveHighRiskTools: true, makeProviderForStep: model.factory,
    captureInvocationMetadata: () => ({ terminal_target: { profileId: 'pinned' } }) };
  let conv = await runChatTurnWithTools(makeConversation('recipe-review'), 'copy', deps(model.provider, f.conversations), loop);
  let parent = partOf(conv, 'parent-review');
  assert.equal(parent.approvalState.type, 'pending'); assert.equal(checkpoint(parent).phase, 'awaiting_approval');
  assert.equal(jevAutoApprovalReason(parent), '自动批准复核：破坏性');
  assert.deepEqual(effects, ['read']); assert.equal(reviewed, 1);
  const child = checkpoint(parent).pendingStep!;
  assert.deepEqual(child.metadata!['terminal_target'], { profileId: 'pinned' });
  assert.match(JSON.stringify(child.metadata!['permission_trace']), /jev_auto_approval.*破坏性/);
  assert.equal(checkpoint(partOf(f.state.saved.at(-1)!, 'parent-review')).phase, 'awaiting_approval');
  conv = await runToolLoopContinuation(approve(conv, 'parent-review'), deps(model.provider, f.conversations), loop);
  parent = partOf(conv, 'parent-review');
  assert.equal(checkpoint(parent).phase, 'finished'); assert.deepEqual(effects, ['read', 'write:output.txt']); assert.equal(reviewed, 1);
});

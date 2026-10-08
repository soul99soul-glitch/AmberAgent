import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
import * as chatDomain from '../main/ets/index.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { Assistant } from '../main/ets/chat/assistant.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { SubAgentDefinition } from '../main/ets/chat/agent_prompt_config.ts';
import { makeSubAgentDefinition } from '../main/ets/chat/agent_prompt_config.ts';
import type {
  MessageChunk, UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool,
} from '../main/ets/chat/message.ts';
import {
  makeUIMessage, makeAssistantMessage, makeUserMessage, toText,
} from '../main/ets/chat/message.ts';
import type { ProviderModel } from '../main/ets/chat/provider_settings.ts';
import { makeProviderModel } from '../main/ets/chat/provider_settings.ts';
import type {
  ChatToolDefinition, ReasoningLevel, TextGenerationParams,
} from '../main/ets/chat/provider_model.ts';
import type { CustomHeader } from '../main/ets/chat/assistant.ts';
import type { GenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';
import { makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';
import { makeSubAgentTaskSpec } from '../main/ets/chat/subagent_models.ts';
import type { SubAgentTaskSpec } from '../main/ets/chat/subagent_models.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import {
  ChatStreamSubAgentGenerationPort, GenerationSubAgentRunner,
} from '../main/ets/chat/subagent_runner.ts';
import type {
  SubAgentAssistantResolution, SubAgentGenerationPort,
} from '../main/ets/chat/subagent_runner.ts';
import type {
  ChatStreamSubAgentGenerationPortDeps, SubAgentGenerationRequest,
} from '../main/ets/index.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';
import type { ToolLoopOptions } from '../main/ets/chat/tool_loop.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { RecipeDescriptor, RecipeManifest } from '../main/ets/chat/recipes/models.ts';
import type { RecipeStore } from '../main/ets/chat/recipes/ports.ts';
import { canonicalRecipeJSON } from '../main/ets/chat/recipes/validation.ts';
import { createRecipeTools } from '../main/ets/chat/recipes/tools.ts';
import { scopedSubAgentTools } from '../main/ets/chat/subagent_tool_scope.ts';

interface DefinitionChanges {
  modelId?: string | null;
  temperature?: number | null;
  reasoningLevel?: ReasoningLevel | null;
  maxTurns?: number;
  outputBudgetChars?: number;
}

interface MutableAbortSignal extends AbortSignalLike {
  aborted: boolean;
}

const VISIBLE_ANSWER: string = '可见答案已经生成。';

const model = (id: string, modelId: string = id): ProviderModel => makeProviderModel({
  id,
  modelId,
  displayName: id,
});

const parentAssistant = (): Assistant => makeAssistant({
  id: 'parent-assistant',
  chatModelId: 'assistant-model-setting-id',
  imageGenerationModelId: 'image-model-setting-id',
  name: 'Parent',
  avatar: { type: 'emoji', content: 'P' },
  useAssistantAvatar: true,
  tags: ['parent-tag'],
  systemPrompt: 'Parent prompt',
  temperature: 0.8,
  topP: 0.7,
  contextMessageSize: 20,
  streamOutput: false,
  enableMemory: true,
  useGlobalMemory: true,
  enableRecentChatsReference: true,
  messageTemplate: 'parent {{ message }}',
  presetMessages: [makeUserMessage('parent preset')],
  quickMessageIds: ['quick-1'],
  regexes: [{
    id: 'regex-1', name: 'secret rewrite', enabled: true,
    findRegex: 'secret', replaceString: 'redacted', affectingScope: ['user'], visualOnly: false,
  }],
  reasoningLevel: 'high',
  maxTokens: 4096,
  customHeaders: [{ name: 'x-parent', value: 'kept' }],
  customBodies: [{ key: 'parent_body', value: true }],
  mcpServers: ['mcp-1'],
  localTools: ['time_info', 'workspace_files'],
  toolProfile: 'coding',
  background: 'parent-bg',
  backgroundOpacity: 0.4,
  enabledSkills: ['workspace-writer'],
  enableTimeReminder: true,
  rememberedReasoningLevelsByModelId: { 'model-a': 'xhigh' },
});

const definition = (
  toolAllowlist: string[] = [],
  changes?: DefinitionChanges,
): SubAgentDefinition => makeSubAgentDefinition({
  id: 'micro-poet',
  name: 'Micro Poet',
  description: 'Use when a tiny creative task should run without external tools.',
  systemPrompt: 'Boundaries: do not use external sources. Report output as a concise final answer.',
  toolAllowlist,
  dynamic: true,
  modelId: changes?.modelId,
  temperature: changes?.temperature,
  reasoningLevel: changes?.reasoningLevel,
  maxTurns: changes?.maxTurns,
  outputBudgetChars: changes?.outputBudgetChars,
});

const task = (): SubAgentTaskSpec => makeSubAgentTaskSpec({
  objective: '写一个一句话答案。',
  outputFormat: '一句话。',
  toolsAndSources: 'No tools.',
  boundaries: 'Do not use external sources.',
});

const inheritedRetry = (): GenerationRetrySetting => makeGenerationRetrySetting({
  enabled: true,
  maxRetries: 3,
  initialDelayMs: 17,
  maxDelayMs: 51,
  jitterRatio: 0,
});

const assistantResolution = (
  assistant: Assistant = parentAssistant(),
): SubAgentAssistantResolution => ({
  assistant,
  generationRetry: inheritedRetry(),
  autoApproveTools: true,
  autoApproveHighRiskTools: false,
});

class FakeGenerationPort implements SubAgentGenerationPort {
  readonly modelResolveCalls: (string | null)[] = [];
  readonly requests: SubAgentGenerationRequest[] = [];
  overrideModel: ProviderModel | null = null;
  currentModel: ProviderModel | null = model('current-setting-id', 'current-api-model');
  currentAssistant: SubAgentAssistantResolution | null = assistantResolution();
  assistantResolveCalls: number = 0;
  scripts: ((request: SubAgentGenerationRequest) => Promise<UIMessage[]>)[] = [];

  resolveModel(modelSettingId: string | null): Promise<ProviderModel | null> {
    this.modelResolveCalls.push(modelSettingId);
    return Promise.resolve(modelSettingId === null ? this.currentModel : this.overrideModel);
  }

  resolveAssistant(): Promise<SubAgentAssistantResolution | null> {
    this.assistantResolveCalls++;
    return Promise.resolve(this.currentAssistant);
  }

  run(request: SubAgentGenerationRequest): Promise<UIMessage[]> {
    this.requests.push(request);
    const script = this.scripts[this.requests.length - 1];
    if (script === undefined) {
      request.onUpdate([request.messages[0], makeAssistantMessage(VISIBLE_ANSWER)]);
      return Promise.resolve([request.messages[0], makeAssistantMessage(VISIBLE_ANSWER)]);
    }
    return script(request);
  }
}

const report = async (
  request: SubAgentGenerationRequest, summary: string = 'structured summary',
): Promise<void> => {
  const reportTool: AgentTool | undefined = request.tools.find(
    (tool: AgentTool): boolean => tool.name === 'subagent_report');
  assert.ok(reportTool !== undefined);
  await reportTool.execute({ summary });
};

const reportingScript = (
  messages: UIMessage[], summary: string = 'structured summary',
): ((request: SubAgentGenerationRequest) => Promise<UIMessage[]>) =>
  async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
    request.onUpdate(messages);
    await report(request, summary);
    return messages;
  };

const run = async (
  port: FakeGenerationPort,
  def: SubAgentDefinition = definition(),
  tools: AgentTool[] = [],
  liveTextValues: string[] = [],
  livePartsValues: UIMessagePart[][] = [],
  signal?: AbortSignalLike,
) => new GenerationSubAgentRunner(port).run(
  def,
  task(),
  tools,
  (text: string): void => { liveTextValues.push(text); },
  (parts: UIMessagePart[]): void => { livePartsValues.push(parts); },
  signal,
);

const echoTool = (name: string): AgentTool => makeAgentTool({
  name,
  description: name,
  execute: (): Promise<UIMessagePart[]> => Promise.resolve([
    { type: 'text', text: 'ok', metadata: null },
  ]),
});

const textChunk = (text: string): MessageChunk => ({
  id: 'chunk', model: 'model',
  choices: [{
    index: 0,
    delta: {
      id: 'delta', role: 'assistant',
      parts: [{ type: 'text', text, metadata: null }],
      annotations: [], createdAt: '2026-07-29T00:00:00Z', finishedAt: null,
      modelId: null, usage: null, translation: null,
    },
    message: null, finishReason: 'unknown',
  }],
  usage: null,
});

const toolChunk = (callId: string, toolName: string, input: string): MessageChunk => ({
  id: 'chunk', model: 'model',
  choices: [{
    index: 0,
    delta: {
      id: 'delta', role: 'assistant',
      parts: [{
        type: 'tool', toolCallId: callId, toolName, input, output: [],
        approvalState: { type: 'auto' }, metadata: null,
      }],
      annotations: [], createdAt: '2026-07-29T00:00:00Z', finishedAt: null,
      modelId: null, usage: null, translation: null,
    },
    message: null, finishReason: 'unknown',
  }],
  usage: null,
});

describe('ChatStreamSubAgentGenerationPort', () => {
  it('awaits Recipe configuration on the actual scoped child loop and executes its primitive', async () => {
    const source = await readFile(new URL('../../../entry/src/main/ets/platform_impl/ScopedRecipeLoop.ets', import.meta.url), 'utf8');
    const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
    const platform = { exports: {} as { configureEntryScopedRecipeLoop: (loop: ToolLoopOptions, store: RecipeStore, guard: null) => Promise<void> } };
    new Function('require', 'exports', 'module', compiled)((name: string) => {
      if (name === '@amber/chat-domain') return chatDomain;
      throw new Error('Unexpected platform fixture import: ' + name);
    }, platform.exports, platform);
    const manifest: RecipeManifest = { schema: 'amber.recipe.v1', name: 'child_read', version: '1',
      description: 'Read within the child scope', inputs: { path: 'string' },
      steps: [{ id: 'read', tool: 'file_read', arguments: { path: '${input.path}' } }],
      outputs: { content: '${step.read.output.content}' } };
    const descriptor: RecipeDescriptor = { manifest, canonicalJSON: canonicalRecipeJSON(manifest), hash: 'child-fixture-hash' };
    const installed = [{ descriptor, enabled: true }];
    const recipeStore: RecipeStore = { listInstalled: async () => installed,
      prepareImport: async () => { throw new Error('Unexpected management call'); },
      applyImport: async () => { throw new Error('Unexpected management call'); },
      setEnabled: async () => { throw new Error('Unexpected management call'); },
      remove: async () => { throw new Error('Unexpected management call'); } };
    let reads: number = 0;
    const primitive = makeAgentTool({ name: 'file_read', description: 'Child read', execute: async input => {
      assert.deepEqual(input, { path: 'child.json' }); reads++;
      return [{ type: 'text', text: '{"content":"child content"}', metadata: null }];
    } });
    const wrapper = createRecipeTools({ store: recipeStore, installed, primitives: [primitive] })
      .find(tool => tool.name === 'recipe__child_read')!;
    const scopedTools = scopedSubAgentTools([primitive, wrapper]);
    assert.equal(scopedTools.find(tool => tool.name === wrapper.name), wrapper);
    assert.deepEqual(wrapper.recipeEnvelope, { tools: ['file_read'], mutates: false, needsApproval: false, risk: 'normal' });
    const newManifest: RecipeManifest = { ...manifest, name: 'outside_child' };
    installed.push({ descriptor: { manifest: newManifest, canonicalJSON: canonicalRecipeJSON(newManifest), hash: 'new-fixture-hash' }, enabled: true });
    let configured: boolean = false;
    let callbackCalls: number = 0;
    let providerCalls: number = 0;
    const observedConfiguration: boolean[] = [];
    let latestParts: UIMessagePart[] = [];
    const deps: ChatStreamSubAgentGenerationPortDeps = {
      resolveModel: async () => model('child-model'), resolveAssistant: async () => assistantResolution(),
      configureRecipeLoop: async (loop: ToolLoopOptions): Promise<void> => {
        callbackCalls++; assert.equal(loop.invocationContext, 'subagent');
        assert.equal(loop.tools.find(tool => tool.name === wrapper.name), wrapper);
        const originalReport = loop.tools.find(tool => tool.name === 'subagent_report');
        assert.ok(originalReport); const originalSchema = originalReport.parameters();
        assert.ok(originalSchema?.required?.includes('summary'));
        await Promise.resolve();
        await platform.exports.configureEntryScopedRecipeLoop(loop, recipeStore, null);
        const refreshed = await loop.refreshTools!();
        const refreshedReport = refreshed.find(tool => tool.name === originalReport.name);
        assert.equal(refreshedReport, originalReport);
        assert.deepEqual(refreshedReport!.parameters(), originalSchema);
        assert.equal(refreshedReport!.execute, originalReport.execute);
        assert.ok(!refreshed.some(tool => tool.name === 'recipe__outside_child'));
        configured = true;
      },
      makeProvider: (_model, params) => ({ streamText: async (_messages, onChunk) => {
        observedConfiguration.push(configured); providerCalls++;
        if (providerCalls === 1) {
          assert.ok(params.tools.some(tool => tool.name === wrapper.name));
          assert.ok(params.tools.some(tool => tool.name === 'subagent_report'));
          assert.ok(!params.tools.some(tool => tool.name === 'recipe__outside_child'));
          onChunk(toolChunk('child-recipe-call', wrapper.name, '{"path":"child.json"}'));
          onChunk(toolChunk('child-report-call', 'subagent_report', '{"summary":"child scoped report"}'));
        } else onChunk(textChunk('child Recipe completed'));
      } }),
    };
    const childResult = await new GenerationSubAgentRunner(new ChatStreamSubAgentGenerationPort(deps)).run(
      definition(['file_read', wrapper.name], { maxTurns: 3 }), task(), scopedTools,
      () => {}, parts => { latestParts = parts; },
    );
    assert.equal(childResult.status, 'completed', childResult.error);
    assert.equal(childResult.summary, 'child scoped report');
    assert.equal(callbackCalls, 1); assert.ok(observedConfiguration.length > 0);
    assert.ok(observedConfiguration.every(value => value)); assert.equal(reads, 1);
    const parent = latestParts.find(part => part.type === 'tool' && part.toolName === wrapper.name) as UIMessagePartTool;
    assert.ok(parent); assert.equal(parent.approvalState.type, 'auto');
    const result: JsonObject = JSON.parse((parent.output[0] as UIMessagePartText).text) as JsonObject;
    assert.equal(result['status'], 'succeeded'); assert.deepEqual(result['outputs'], { content: 'child content' });
    assert.equal(parent.metadata!['recipe_v1'] !== undefined, true);
  });

  it('seeds every supplied retry-history message into the shared child loop', async () => {
    let providerMessages: UIMessage[] = [];
    const provider: ChatStreamProvider = {
      streamText(
        messages: UIMessage[], onChunk: (chunk: MessageChunk) => void,
      ): Promise<void> {
        providerMessages = messages;
        onChunk(textChunk('retry complete'));
        return Promise.resolve();
      },
    };
    const publicDeps: ChatStreamSubAgentGenerationPortDeps = {
      resolveModel: (): Promise<ProviderModel | null> => Promise.resolve(model('setting-id')),
      resolveAssistant: (): Promise<SubAgentAssistantResolution | null> =>
        Promise.resolve(assistantResolution()),
      makeProvider: (
        _model: ProviderModel, _params: TextGenerationParams, _headers: CustomHeader[],
      ): ChatStreamProvider => provider,
    };
    const port = new ChatStreamSubAgentGenerationPort(publicDeps);
    const retryHistory: UIMessage[] = [
      makeUserMessage('original task'),
      makeAssistantMessage(VISIBLE_ANSWER),
      makeUserMessage('Internal supervisor reminder'),
    ];

    const publicRequest: SubAgentGenerationRequest = {
      model: model('setting-id'),
      assistant: makeAssistant({ streamOutput: true }),
      generationRetry: inheritedRetry(),
      messages: retryHistory,
      tools: [],
      maxSteps: 1,
      autoApproveTools: false,
      autoApproveHighRiskTools: false,
      onUpdate: (): void => {},
    };
    const output = await port.run(publicRequest);

    assert.ok(providerMessages.some(
      (message: UIMessage): boolean => message.role === 'assistant' && toText(message) === VISIBLE_ANSWER));
    assert.ok(providerMessages.some(
      (message: UIMessage): boolean => message.role === 'user' &&
        toText(message) === 'Internal supervisor reminder'));
    assert.equal(output.slice(0, retryHistory.length).map(toText).join('|'),
      retryHistory.map(toText).join('|'));
  });

  it('uses selected-model session defaults for AUTO reasoning and null maxTokens through the real loop', async () => {
    const observedParams: TextGenerationParams[] = [];
    const selectedModel: ProviderModel = makeProviderModel({
      id: 'setting-id', modelId: 'deepseek-reasoner', displayName: 'setting-id',
      abilities: ['reasoning'],
    });
    const port = new ChatStreamSubAgentGenerationPort({
      resolveModel: (): Promise<ProviderModel | null> => Promise.resolve(selectedModel),
      resolveAssistant: (): Promise<SubAgentAssistantResolution | null> =>
        Promise.resolve(assistantResolution(makeAssistant({
          reasoningLevel: 'auto', maxTokens: null,
        }))),
      makeProvider: (
        _model: ProviderModel, params: TextGenerationParams, _headers: CustomHeader[],
      ): ChatStreamProvider => ({
        streamText(
          _messages: UIMessage[], onChunk: (chunk: MessageChunk) => void,
        ): Promise<void> {
          observedParams.push(params);
          onChunk(textChunk('deepseek answer'));
          return Promise.resolve();
        },
      }),
    });

    const result = await new GenerationSubAgentRunner(port).run(
      definition([], { reasoningLevel: null }),
      task(),
      [],
      (): void => {},
      (): void => {},
    );

    assert.equal(result.status, 'completed');
    assert.ok(observedParams.length > 0);
    assert.equal(observedParams[0].reasoningLevel, 'high');
    assert.equal(observedParams[0].maxTokens, null);
  });

  it('passes scoped tools, report, isolated generation params, headers, and inherited retries through the real loop', async () => {
    const factoryParams: TextGenerationParams[] = [];
    const observedParams: TextGenerationParams[] = [];
    const observedHeaders: CustomHeader[][] = [];
    let providerAttempts: number = 0;
    let scopedToolAttempts: number = 0;
    const parent = parentAssistant();
    const selectedModel = model('setting-id', 'api-model');
    selectedModel.customHeaders = [{ name: 'x-model', value: 'model' }];
    selectedModel.customBodies = [{ key: 'model_body', value: 'kept' }];
    const scopedTool = makeAgentTool({
      name: 'file_read',
      description: 'read child file',
      execute: (): Promise<UIMessagePart[]> => {
        scopedToolAttempts++;
        if (scopedToolAttempts === 1) return Promise.reject(new Error('network reset'));
        return Promise.resolve([{ type: 'text', text: 'read ok', metadata: null }]);
      },
    });
    const port = new ChatStreamSubAgentGenerationPort({
      resolveModel: (modelId: string | null): Promise<ProviderModel | null> =>
        Promise.resolve(modelId === 'setting-id' || modelId === null ? selectedModel : null),
      resolveAssistant: (): Promise<SubAgentAssistantResolution | null> =>
        Promise.resolve(assistantResolution(parent)),
      makeProvider: (
        _model: ProviderModel, params: TextGenerationParams, headers: CustomHeader[],
      ): ChatStreamProvider => {
        factoryParams.push(params);
        return {
          streamText(
            _messages: UIMessage[], onChunk: (chunk: MessageChunk) => void,
          ): Promise<void> {
            observedParams.push(params);
            observedHeaders.push(headers);
            providerAttempts++;
            if (providerAttempts === 1) return Promise.reject(new Error('network reset'));
            if (providerAttempts === 2) {
              onChunk(toolChunk('call-read', 'file_read', '{}'));
              onChunk(toolChunk('call-report', 'subagent_report', '{"summary":"done"}'));
            } else {
              onChunk(textChunk('finished'));
            }
            return Promise.resolve();
          },
        };
      },
    });

    const result = await new GenerationSubAgentRunner(port).run(
      definition(['file_read'], {
        modelId: 'setting-id', temperature: 0.25, reasoningLevel: 'low', maxTurns: 4,
      }),
      task(),
      [scopedTool],
      (): void => {},
      (): void => {},
    );

    assert.equal(result.status, 'completed');
    assert.equal(providerAttempts, 3);
    assert.equal(factoryParams.length, 2);
    assert.equal(scopedToolAttempts, 2);
    const firstToolStep = observedParams.find(
      (params: TextGenerationParams): boolean => params.tools.length > 0);
    assert.ok(firstToolStep !== undefined);
    assert.deepEqual(firstToolStep.tools.map(
      (tool: ChatToolDefinition): string => tool.name), ['file_read', 'subagent_report']);
    assert.equal(firstToolStep.temperature, 0.25);
    assert.equal(firstToolStep.reasoningLevel, 'low');
    assert.equal(firstToolStep.topP, 0.7);
    assert.equal(firstToolStep.maxTokens, 4096);
    assert.deepEqual(firstToolStep.customBody, [
      { key: 'parent_body', value: true },
      { key: 'model_body', value: 'kept' },
    ]);
    assert.deepEqual(observedHeaders[0], [
      { name: 'x-parent', value: 'kept' },
      { name: 'x-model', value: 'model' },
    ]);
  });
});

describe('GenerationSubAgentRunner model resolution and isolation', () => {
  it('selects a valid setting UUID override and does not resolve the current chat model', async () => {
    const port = new FakeGenerationPort();
    port.overrideModel = model('override-setting-uuid', 'override-api-model');
    port.scripts.push(reportingScript([makeUserMessage('task'), makeAssistantMessage('done')]));

    await run(port, definition([], { modelId: 'override-setting-uuid' }));

    assert.deepEqual(port.modelResolveCalls, ['override-setting-uuid']);
    assert.equal(port.requests[0].model.id, 'override-setting-uuid');
  });

  it('silently falls back from a stale override to the current chat model', async () => {
    const port = new FakeGenerationPort();
    port.overrideModel = null;
    port.scripts.push(reportingScript([makeUserMessage('task'), makeAssistantMessage('done')]));

    await run(port, definition([], { modelId: 'stale-setting-uuid' }));

    assert.deepEqual(port.modelResolveCalls, ['stale-setting-uuid', null]);
    assert.equal(port.requests[0].model.id, 'current-setting-id');
  });

  it('fails with the exact error when neither override nor current model is configured', async () => {
    const port = new FakeGenerationPort();
    port.overrideModel = null;
    port.currentModel = null;

    await assert.rejects(
      run(port, definition([], { modelId: 'missing-setting-uuid' })),
      new Error('Current chat model is not configured'),
    );
  });

  it('resolves the assistant separately and does not report a model error when it is missing', async () => {
    const port = new FakeGenerationPort();
    port.currentAssistant = null;

    await assert.rejects(run(port), new Error('Current assistant is not configured'));
    assert.deepEqual(port.modelResolveCalls, [null]);
    assert.equal(port.assistantResolveCalls, 1);
  });

  it('builds the exact isolated assistant/runtime while preserving unrelated inherited fields', async () => {
    const port = new FakeGenerationPort();
    port.scripts.push(reportingScript([makeUserMessage('task'), makeAssistantMessage('done')]));

    await run(port, definition([], { temperature: 0.25, reasoningLevel: 'low' }));

    const request = port.requests[0];
    const assistant = request.assistant;
    assert.equal(assistant.name, 'Micro Poet');
    assert.equal(assistant.systemPrompt, definition().systemPrompt);
    assert.equal(assistant.streamOutput, true);
    assert.equal(assistant.contextMessageSize, 0);
    assert.equal(assistant.enableMemory, false);
    assert.equal(assistant.useGlobalMemory, false);
    assert.equal(assistant.enableRecentChatsReference, false);
    assert.deepEqual(assistant.presetMessages, []);
    assert.deepEqual(assistant.quickMessageIds, []);
    assert.deepEqual(assistant.regexes, []);
    assert.deepEqual(assistant.mcpServers, []);
    assert.deepEqual(assistant.localTools, []);
    assert.deepEqual(assistant.enabledSkills, []);
    assert.equal(assistant.enableTimeReminder, false);
    assert.equal(assistant.messageTemplate, '{{ message }}');
    assert.equal(assistant.temperature, 0.25);
    assert.equal(assistant.reasoningLevel, 'low');

    assert.equal(assistant.maxTokens, 4096);
    assert.equal(assistant.topP, 0.7);
    assert.deepEqual(assistant.customHeaders, [{ name: 'x-parent', value: 'kept' }]);
    assert.deepEqual(assistant.customBodies, [{ key: 'parent_body', value: true }]);
    assert.deepEqual(assistant.avatar, { type: 'emoji', content: 'P' });
    assert.deepEqual(assistant.tags, ['parent-tag']);
    assert.equal(assistant.background, 'parent-bg');
    assert.equal(assistant.backgroundOpacity, 0.4);
    assert.equal(assistant.toolProfile, 'coding');

    assert.deepEqual(request.generationRetry, inheritedRetry());
  });
});

describe('GenerationSubAgentRunner first child generation', () => {
  it('uses exact child budgets 1, 2, and 4 through the real shared provider loop', async () => {
    for (const turns of [1, 2, 4]) {
      let providerCalls: number = 0;
      let emittedToolCalls: number = 0;
      let executedToolCalls: number = 0;
      const repeatedTool = makeAgentTool({
        name: 'file_read',
        description: 'read child file',
        execute: (): Promise<UIMessagePart[]> => {
          executedToolCalls++;
          return Promise.resolve([{ type: 'text', text: 'read ok', metadata: null }]);
        },
      });
      const selectedModel = model('setting-id', 'api-model');
      const publicDeps: ChatStreamSubAgentGenerationPortDeps = {
        resolveModel: (): Promise<ProviderModel | null> => Promise.resolve(selectedModel),
        resolveAssistant: (): Promise<SubAgentAssistantResolution | null> =>
          Promise.resolve(assistantResolution()),
        makeProvider: (
          _model: ProviderModel, params: TextGenerationParams, _headers: CustomHeader[],
        ): ChatStreamProvider => ({
          streamText(
            _messages: UIMessage[], onChunk: (chunk: MessageChunk) => void,
          ): Promise<void> {
            providerCalls++;
            emittedToolCalls++;
            if (params.tools.length > 0) {
              onChunk(toolChunk(`call-${providerCalls}`, 'file_read', '{}'));
              return Promise.resolve();
            }
            onChunk(textChunk('final child step'));
            onChunk(toolChunk(`call-${providerCalls}`, 'file_read', '{}'));
            return Promise.reject(new Error(
              'invalid function arguments JSON string in tool_call'));
          },
        }),
      };
      const port = new ChatStreamSubAgentGenerationPort(publicDeps);

      const result = await new GenerationSubAgentRunner(port).run(
        definition([], { maxTurns: turns }),
        task(),
        [repeatedTool],
        (): void => {},
        (): void => {},
      );

      assert.equal(result.status, 'completed');
      assert.equal(providerCalls, turns);
      assert.equal(emittedToolCalls, turns);
      assert.equal(executedToolCalls, Math.max(turns - 1, 0));
    }
  });

  it('uses one task user message, supplied scoped tools plus report, exact turns, and global approvals', async () => {
    const port = new FakeGenerationPort();
    const childTool = echoTool('file_read');
    port.scripts.push(reportingScript([makeUserMessage('task'), makeAssistantMessage('done')]));

    await run(port, definition(['file_read'], { maxTurns: 4 }), [childTool]);

    const request = port.requests[0];
    assert.equal(request.messages.length, 1);
    assert.equal(request.messages[0].role, 'user');
    const promptPart: UIMessagePart = request.messages[0].parts[0];
    assert.equal(promptPart.type, 'text');
    const promptTextPart: UIMessagePartText = promptPart as UIMessagePartText;
    assert.ok(promptTextPart.text.includes('写一个一句话答案。'));
    assert.ok(promptTextPart.text.includes('Context from parent:\n(none)'));
    assert.deepEqual(request.tools.map((tool: AgentTool): string => tool.name), [
      'file_read', 'subagent_report',
    ]);
    assert.equal(request.maxSteps, 4);
    assert.equal(request.autoApproveTools, true);
    assert.equal(request.autoApproveHighRiskTools, false);
  });
});

describe('GenerationSubAgentRunner live and final rendering', () => {
  it('streams last-assistant reasoning then text and flattens all message parts', async () => {
    const port = new FakeGenerationPort();
    const reasoningMessage = makeUIMessage('assistant', [
      {
        type: 'reasoning', reasoning: '先想一想\n再检查', createdAt: '2026-07-29T00:00:00Z',
        finishedAt: null, metadata: null,
      },
      { type: 'text', text: VISIBLE_ANSWER, metadata: null },
    ]);
    const messages: UIMessage[] = [makeUserMessage('task'), reasoningMessage];
    port.scripts.push(reportingScript(messages));
    const liveTextValues: string[] = [];
    const livePartsValues: UIMessagePart[][] = [];

    const result = await run(port, definition(), [], liveTextValues, livePartsValues);

    assert.equal(result.status, 'completed');
    assert.ok(liveTextValues.includes(`> 💭 先想一想\n> 再检查\n\n${VISIBLE_ANSWER}`));
    assert.equal(liveTextValues[liveTextValues.length - 1], VISIBLE_ANSWER);
    assert.equal(livePartsValues[livePartsValues.length - 1].length, 3);
  });

  it('keeps search tool parts available for render-time presentation', async () => {
    const port = new FakeGenerationPort();
    const searchTool: UIMessagePartTool = {
      type: 'tool', toolCallId: 'call-search', toolName: 'search_web',
      input: '{"query":"Will Smith tour"}',
      output: [{ type: 'text', text: '{"items":[]}', metadata: null }],
      approvalState: { type: 'auto' }, metadata: null,
    };
    const messages: UIMessage[] = [
      makeUserMessage('task'),
      makeUIMessage('assistant', [searchTool, { type: 'text', text: VISIBLE_ANSWER, metadata: null }]),
    ];
    port.scripts.push(reportingScript(messages));
    const livePartsValues: UIMessagePart[][] = [];

    await run(port, definition(), [], [], livePartsValues);

    assert.ok(livePartsValues[livePartsValues.length - 1].some(
      (part: UIMessagePart): boolean => {
        if (part.type !== 'tool') return false;
        const toolPart: UIMessagePartTool = part as UIMessagePartTool;
        return toolPart.toolName === 'search_web';
      }));
  });

  it('joins nonblank distinct assistant text with blank lines and truncates the display budget', async () => {
    const port = new FakeGenerationPort();
    const messages: UIMessage[] = [
      makeUserMessage('task'),
      makeAssistantMessage(' first '),
      makeAssistantMessage('first'),
      makeAssistantMessage('second'),
    ];
    port.scripts.push(reportingScript(messages));
    const liveTextValues: string[] = [];

    await run(port, definition([], { outputBudgetChars: 11 }), [], liveTextValues);

    assert.equal(liveTextValues[liveTextValues.length - 1], 'first\n\nseco');
  });
});

describe('GenerationSubAgentRunner provider errors and cancellation', () => {
  it('returns FAILED for an ordinary provider error even after visible text', async () => {
    const port = new FakeGenerationPort();
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate([request.messages[0], makeAssistantMessage(VISIBLE_ANSWER)]);
      throw new Error('network unavailable');
    });

    const result = await run(port);

    assert.equal(result.status, 'failed');
    assert.ok(result.error.includes('network unavailable'));
  });

  it('uses the narrow report-argument fallback based on the definition allowlist, not executable tool count', async () => {
    const recoverable = async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate([request.messages[0], makeAssistantMessage(VISIBLE_ANSWER)]);
      throw new Error('invalid params: invalid function arguments json string for tool_call_id call_1');
    };

    const emptyAllowlistPort = new FakeGenerationPort();
    emptyAllowlistPort.scripts.push(recoverable);
    const recovered = await run(emptyAllowlistPort, definition([]), [echoTool('unexpected_executable')]);
    assert.equal(recovered.status, 'completed');
    assert.equal(recovered.summary, VISIBLE_ANSWER);
    assert.ok(recovered.risks.some((risk: string): boolean =>
      risk.includes('Structured subagent report failed')));

    const declaredToolPort = new FakeGenerationPort();
    declaredToolPort.scripts.push(recoverable);
    const failed = await run(declaredToolPort, definition(['file_read']), []);
    assert.equal(failed.status, 'failed');
  });

  it('returns FAILED for matching report-argument markers when visible display text is blank', async () => {
    const port = new FakeGenerationPort();
    port.scripts.push(async (): Promise<UIMessage[]> => {
      throw new Error('invalid function arguments json string for tool_call call-1');
    });

    const result = await run(port, definition([]));

    assert.equal(result.status, 'failed');
  });

  it('truncates text-only supervisor display before returning an ordinary error', async () => {
    const port = new FakeGenerationPort();
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate([request.messages[0], makeAssistantMessage('abcdefghij')]);
      throw new Error('network unavailable');
    });
    const liveTextValues: string[] = [];

    const result = await run(
      port, definition([], { outputBudgetChars: 4 }), [], liveTextValues);

    assert.equal(result.status, 'failed');
    assert.equal(liveTextValues[liveTextValues.length - 1], 'abcd');
  });

  it('requires both provider error markers for the narrow fallback', async () => {
    const port = new FakeGenerationPort();
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate([request.messages[0], makeAssistantMessage(VISIBLE_ANSWER)]);
      throw new Error('invalid function arguments json string without the other marker');
    });

    const result = await run(port);

    assert.equal(result.status, 'failed');
  });

  it('bounds normal provider error text at 300 characters', async () => {
    const port = new FakeGenerationPort();
    port.scripts.push(async (): Promise<UIMessage[]> => {
      throw new Error('x'.repeat(500));
    });

    const result = await run(port);

    assert.equal(result.status, 'failed');
    assert.equal(result.error.length, 300);
  });

  it('propagates AbortError instead of returning a result', async () => {
    const port = new FakeGenerationPort();
    port.scripts.push(async (): Promise<UIMessage[]> => {
      const error = new Error('cancelled');
      error.name = 'AbortError';
      throw error;
    });

    await assert.rejects(run(port), (error: Error): boolean => error.name === 'AbortError');
  });

  it('rechecks the signal after a provider returns a partial snapshot', async () => {
    const port = new FakeGenerationPort();
    const signal: MutableAbortSignal = { aborted: false };
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate([request.messages[0], makeAssistantMessage('partial')]);
      signal.aborted = true;
      return [request.messages[0], makeAssistantMessage('partial')];
    });

    await assert.rejects(run(port, definition(), [], [], [], signal),
      (error: Error): boolean => error.name === 'AbortError');
  });
});

describe('GenerationSubAgentRunner approval and report retry', () => {
  it('returns approval_required when the report retry stops at a pending tool', async () => {
    const port = new FakeGenerationPort();
    const first = [makeUserMessage('task'), makeAssistantMessage('Original visible answer')];
    port.scripts.push(async request => { request.onUpdate(first); return first; });
    port.scripts.push(async request => {
      const pending: UIMessagePartTool = {
        type: 'tool', toolCallId: 'retry-pending', toolName: 'session_read', input: '{}', output: [],
        approvalState: { type: 'pending' }, metadata: null,
      };
      const latest = [...request.messages, makeUIMessage('assistant', [pending])];
      request.onUpdate(latest);
      return latest;
    });
    const display: string[] = [];
    const result = await run(port, definition(['session_read']), [], display);
    assert.equal(result.status, 'approval_required');
    assert.equal(result.summary, 'Subagent requested approval for session_read.');
    assert.equal(display.at(-1), 'Original visible answer');
    assert.equal(port.requests.length, 2);
  });

  it('inspects only the final message pending tools and returns the exact approval result', async () => {
    const port = new FakeGenerationPort();
    const oldPending: UIMessagePartTool = {
      type: 'tool', toolCallId: 'old', toolName: 'old_tool', input: '{}', output: [],
      approvalState: { type: 'pending' }, metadata: null,
    };
    const finalPending: UIMessagePartTool = {
      type: 'tool', toolCallId: 'new', toolName: 'memory_write', input: '{}', output: [],
      approvalState: { type: 'pending' }, metadata: null,
    };
    const messages: UIMessage[] = [
      makeUIMessage('assistant', [oldPending]),
      makeUIMessage('assistant', [finalPending]),
    ];
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate(messages);
      return messages;
    });

    const result = await run(port);

    assert.equal(result.status, 'approval_required');
    assert.equal(result.summary, 'Subagent requested approval for memory_write.');
    assert.deepEqual(result.risks, ['Subagent cannot self-approve sensitive tools.']);
    assert.deepEqual(result.recommendedNextSteps, [
      'Main agent should decide whether to ask the user for approval in the parent conversation.',
    ]);
    assert.equal(port.requests.length, 1);
  });

  it('publishes truncated text-only supervisor display before approval_required', async () => {
    const port = new FakeGenerationPort();
    const pending: UIMessagePartTool = {
      type: 'tool', toolCallId: 'new', toolName: 'memory_write', input: '{}', output: [],
      approvalState: { type: 'pending' }, metadata: null,
    };
    const messages: UIMessage[] = [makeUIMessage('assistant', [
      {
        type: 'reasoning', reasoning: 'hidden reasoning', createdAt: '2026-07-29T00:00:00Z',
        finishedAt: null, metadata: null,
      },
      { type: 'text', text: 'abcdefghij', metadata: null },
      pending,
    ])];
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate(messages);
      return messages;
    });
    const liveTextValues: string[] = [];

    const result = await run(
      port, definition([], { outputBudgetChars: 4 }), [], liveTextValues);

    assert.equal(result.status, 'approval_required');
    assert.equal(liveTextValues[liveTextValues.length - 1], 'abcd');
  });

  it('uses retry visible text when the first generation display is blank', async () => {
    const port = new FakeGenerationPort();
    const firstMessages: UIMessage[] = [makeUserMessage('task'), makeAssistantMessage('')];
    const retryMessages: UIMessage[] = [
      makeUserMessage('task'), makeAssistantMessage(''), makeUserMessage('reminder'),
      makeAssistantMessage('retry visible answer'),
    ];
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate(firstMessages);
      return firstMessages;
    });
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate(retryMessages);
      return retryMessages;
    });
    const liveTextValues: string[] = [];

    const result = await run(port, definition(), [], liveTextValues);

    assert.equal(result.status, 'completed');
    assert.equal(result.summary, 'retry visible answer');
    assert.equal(liveTextValues[liveTextValues.length - 1], 'retry visible answer');
  });

  it('does not replace an original visible answer with report-only retry text', async () => {
    const port = new FakeGenerationPort();
    const firstMessages: UIMessage[] = [makeUserMessage('task'), makeAssistantMessage(VISIBLE_ANSWER)];
    const retryMessages: UIMessage[] = [
      makeUserMessage('task'), makeAssistantMessage(VISIBLE_ANSWER), makeUserMessage('reminder'),
      makeAssistantMessage('report-only retry text'),
    ];
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate(firstMessages);
      return firstMessages;
    });
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate(retryMessages);
      return retryMessages;
    });
    const liveTextValues: string[] = [];

    const result = await run(port, definition(), [], liveTextValues);

    assert.equal(result.summary, VISIBLE_ANSWER);
    assert.equal(liveTextValues[liveTextValues.length - 1], VISIBLE_ANSWER);
    assert.equal(liveTextValues.includes('report-only retry text'), false);
  });

  it('appends the exact reminder and retries a missing report for exactly two steps without replacing live text', async () => {
    const port = new FakeGenerationPort();
    const firstMessages: UIMessage[] = [makeUserMessage('task'), makeAssistantMessage(VISIBLE_ANSWER)];
    const retryMessages: UIMessage[] = [
      makeUserMessage('task'), makeAssistantMessage(VISIBLE_ANSWER),
      makeUserMessage('reminder'), makeAssistantMessage('internal retry chatter'),
    ];
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate(firstMessages);
      return firstMessages;
    });
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate(retryMessages);
      await report(request, 'captured after retry');
      return retryMessages;
    });
    const liveTextValues: string[] = [];
    const livePartsValues: UIMessagePart[][] = [];

    const result = await run(port, definition([], { maxTurns: 1 }), [], liveTextValues, livePartsValues);

    assert.equal(port.requests.length, 2);
    assert.equal(port.requests[0].maxSteps, 1);
    assert.equal(port.requests[1].maxSteps, 2);
    const retryInput = port.requests[1].messages;
    const reminder = retryInput[retryInput.length - 1];
    assert.equal(reminder.role, 'user');
    const reminderPart: UIMessagePart = reminder.parts[0];
    assert.equal(reminderPart.type, 'text');
    const reminderTextPart: UIMessagePartText = reminderPart as UIMessagePartText;
    assert.equal(reminderTextPart.text,
      'Internal supervisor reminder: call `subagent_report` now with the compact structured result.\n' +
      'The report tool is injected directly into this subagent run and may not appear in tools_list/catalog output.\n' +
      'Do not repeat the full visible answer; keep any final text short.');
    assert.equal(result.summary, 'captured after retry');
    assert.equal(liveTextValues[liveTextValues.length - 1], VISIBLE_ANSWER);
    assert.equal(liveTextValues.includes('internal retry chatter'), false);
    assert.ok(livePartsValues[livePartsValues.length - 1].some(
      (part: UIMessagePart): boolean => {
        if (part.type !== 'text') return false;
        const textPart: UIMessagePartText = part as UIMessagePartText;
        return textPart.text === 'internal retry chatter';
      }));
  });

  it('keeps the original answer and records a report retry error as fallback risk', async () => {
    const port = new FakeGenerationPort();
    const firstMessages: UIMessage[] = [makeUserMessage('task'), makeAssistantMessage(VISIBLE_ANSWER)];
    port.scripts.push(async (request: SubAgentGenerationRequest): Promise<UIMessage[]> => {
      request.onUpdate(firstMessages);
      return firstMessages;
    });
    port.scripts.push(async (): Promise<UIMessage[]> => {
      throw new Error('report retry failed');
    });
    const liveTextValues: string[] = [];

    const result = await run(port, definition(), [], liveTextValues);

    assert.equal(result.status, 'completed');
    assert.equal(result.summary, VISIBLE_ANSWER);
    assert.equal(liveTextValues[liveTextValues.length - 1], VISIBLE_ANSWER);
    assert.ok(result.risks.some((risk: string): boolean => risk.includes('report retry failed')));
  });
});

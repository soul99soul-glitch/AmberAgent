import type { CouncilGenerateRequest, DeepReadPlaybookRepository, FileStore, HttpClient,
  ModelCouncilTextRunner } from '@amber/deepread-domain';
import type { AgentTool, Assistant, ChatModel, ChatStreamProvider, ChatToolDefinition, CompactStore,
  Conversation, ConversationRepository, KeyValueStore, McpSettingsPort, McpToolActivityPort,
  McpToolIdentity, MemoryToolDeps, MoshRuntime, PluginStore, PosixWorkspaceManager, PythonRuntime,
  RecipeStore, SessionAccessGrantStore, SkillMetadata, SkillWorkspacePort, SSHProfileStore,
  TerminalRuntime, ToolInvocationRequest, ToolLoopOptions } from '@amber/chat-domain';
import { AgentToolActivityStore, createClipboardTool, createDeepReadPlaybookTools,
  createHealthSummaryTool, createMcpServerTools, createRunPlanUpdateTool, loadSearchPrefs,
  makeAgentTool, makeConversation, McpManager, SkillManager } from '@amber/chat-domain';
import { loadAgentRuntimeSnapshot } from './AgentRuntimePrefs.ets';
import type { AgentRuntimeSnapshot } from './AgentRuntimePrefs.ets';
import { createCouncilSeatLoop } from './CouncilAssembly.ets';
import type { CouncilAssemblyDeps, CouncilExtraToolsContext, CouncilLiveScope, CouncilLocalPorts } from './CouncilAssembly.ets';
import { createCouncilSeatStore } from './CouncilSeatStore.ets';
import type { CouncilSeatStore } from './CouncilSeatStore.ets';
import { createCouncilToolRunner } from './CouncilToolRunner.ets';
import type { CouncilSeatExecution } from './CouncilToolRunner.ets';
import { inflateRawEntry } from './EntryDocumentParser.ets';
import { createEntryClipboardPort, sha256HexUtf8 } from './EntryLocalToolPorts.ets';
import { createFeishuDocsTools } from './FeishuDocsTools.ets';
import { createEntryHealthReadPort } from './HealthReadPort.ets';
import { createImageGenTool, imageGenToolAvailable } from './ImageGenTool.ets';
import type { EditSourceImage } from './ImageGenTool.ets';
import { createJevStatusTool, jevToolSearchReranker } from './JevSupport.ets';
import { createModelCouncilRunner } from './ModelCouncilRunner.ets';
import { ensureSearchSdk } from './SearchSdkInit.ets';
import { createSoulTools } from './SoulTools.ets';

// The composition root injects initialized ports. A Council run never resolves a Chat runtime.
export interface CouncilRunPreparationDeps {
  http: HttpClient;
  files: FileStore;
  kv: KeyValueStore;
  getAssistants: () => Promise<Assistant[]>;
  workspace: PosixWorkspaceManager;
  getTerminalRuntime: () => Promise<TerminalRuntime>;
  getMoshRuntime: () => Promise<MoshRuntime>;
  getSSHProfiles: () => Promise<SSHProfileStore>;
  getPythonRuntime: () => Promise<PythonRuntime>;
  initMcp: () => Promise<void>;
  mcpManager: McpManager;
  mcpSettings: McpSettingsPort;
  skillManager: SkillManager;
  skillWorkspace: SkillWorkspacePort;
  setSkillEnabled: (assistantId: string, name: string, enabled: boolean) => Promise<void>;
  memory: MemoryToolDeps;
  compactStore: CompactStore;
  loadCompressPrompt: () => Promise<string>;
  repository: ConversationRepository;
  grantStore: SessionAccessGrantStore;
  getRecipeStore: () => Promise<RecipeStore>;
  getPluginStore: () => Promise<PluginStore>;
  getAgentTaskTools: () => Promise<AgentTool[]>;
  getAgentCronTools: () => Promise<AgentTool[]>;
  getPermissionsStatusTool: () => AgentTool;
  getAgentPromptConfigTool: () => AgentTool;
  playbookRepository: DeepReadPlaybookRepository;
}

const activityPort = (store: AgentToolActivityStore): McpToolActivityPort => ({
  startTool: (name: string, title: string, preview: string, runtime: string): string =>
    store.startTool(name, title, preview, runtime),
  complete: (id: string, preview: string): void => { store.complete(id, preview); },
  fail: (id: string, error: Error): void => { store.fail(id, error); },
});

const mcpTargets = (manager: McpManager, assistant: Assistant | null): McpToolIdentity[] => {
  if (assistant === null) return [];
  const targets: McpToolIdentity[] = [];
  for (const tool of manager.getAllAvailableTools(assistant.mcpServers)) {
    if (tool.serverId !== undefined) targets.push({ serverId: tool.serverId, toolName: tool.name });
  }
  return targets;
};

const seatMcpTools = (frozen: AgentTool[], manager: McpManager, assistant: Assistant,
  store: AgentToolActivityStore): AgentTool[] => {
  const current: AgentTool[] = createMcpServerTools({ manager, assistantIds: assistant.mcpServers.slice(),
    activity: activityPort(store) });
  const tools: AgentTool[] = [];
  for (const original of frozen) {
    const target: McpToolIdentity | undefined = original.mcpTarget;
    if (target === undefined) continue;
    const actual: AgentTool | undefined = current.find((tool: AgentTool): boolean => tool.mcpTarget !== undefined
      && tool.mcpTarget.serverId === target.serverId && tool.mcpTarget.toolName === target.toolName);
    if (actual === undefined) continue;
    tools.push(makeAgentTool({ name: original.name, description: original.description,
      parameters: original.parameters, systemPrompt: original.systemPrompt,
      needsApproval: original.needsApproval, allowsAutoApproval: original.allowsAutoApproval,
      mandatoryApproval: original.mandatoryApproval, recipeEnvelope: original.recipeEnvelope,
      pluginEnvelope: original.pluginEnvelope, mcpTarget: target, execute: actual.execute }));
  }
  return tools;
};

export const prepareCouncilToolRunner = async (deps: CouncilRunPreparationDeps): Promise<ModelCouncilTextRunner> => {
  const assistants: Assistant[] = await deps.getAssistants();
  if (assistants.length === 0) throw new Error('议会工具执行需要已配置的默认助手');
  const assistant: Assistant = JSON.parse(JSON.stringify(assistants[0])) as Assistant;
  const runtime: AgentRuntimeSnapshot = await loadAgentRuntimeSnapshot(deps.kv);
  const search = await loadSearchPrefs(deps.kv);
  const globalFeishuEnabled: boolean = (await deps.kv.get('webmount_global_enabled')) === 'true';
  const feishuEnabled: boolean = globalFeishuEnabled || assistant.localTools.indexOf('webmount') >= 0;
  const imageGenConfigured: boolean = await imageGenToolAvailable();
  const allSkills: SkillMetadata[] = JSON.parse(JSON.stringify(deps.skillManager.listSkills())) as SkillMetadata[];
  const terminalEnabled: boolean = assistant.localTools.indexOf('terminal') >= 0;
  const local: CouncilLocalPorts = { workspace: deps.workspace,
    terminalRuntime: terminalEnabled ? await deps.getTerminalRuntime() : null,
    moshRuntime: terminalEnabled ? await deps.getMoshRuntime() : null,
    sshProfiles: terminalEnabled ? await deps.getSSHProfiles() : null,
    pythonRuntime: assistant.localTools.indexOf('python') >= 0 ? await deps.getPythonRuntime() : null };
  ensureSearchSdk();
  await deps.initMcp();
  // Build exposed aliases once. Seat scope wrappers retain these names and canonical targets.
  const mcpTools: AgentTool[] = createMcpServerTools({ manager: deps.mcpManager,
    assistantIds: assistant.mcpServers.slice() });
  const recipeStore: RecipeStore = await deps.getRecipeStore();
  const pluginStore: PluginStore = await deps.getPluginStore();
  const compressPrompt: string = await deps.loadCompressPrompt();
  const taskTools: AgentTool[] = await deps.getAgentTaskTools();
  const cronTools: AgentTool[] = await deps.getAgentCronTools();
  const commonTools: AgentTool[] = taskTools.concat(cronTools, [deps.getPermissionsStatusTool(),
    createJevStatusTool(), createRunPlanUpdateTool(), createHealthSummaryTool(createEntryHealthReadPort()),
    deps.getAgentPromptConfigTool()], createSoulTools(), createDeepReadPlaybookTools(deps.playbookRepository));
  const readLiveScope = async (assistantId: string): Promise<CouncilLiveScope> => {
    const current: Assistant | null = (await deps.getAssistants()).find(
      (item: Assistant): boolean => item.id === assistantId) ?? null;
    const currentSearch = await loadSearchPrefs(deps.kv);
    const currentGlobalFeishu: boolean = (await deps.kv.get('webmount_global_enabled')) === 'true';
    return { assistant: current, runtime: await loadAgentRuntimeSnapshot(deps.kv),
      searchEnabled: currentSearch.enableWebSearch,
      feishuEnabled: currentGlobalFeishu || (current !== null && current.localTools.indexOf('webmount') >= 0),
      imageGenConfigured: await imageGenToolAvailable(), mcpTargets: mcpTargets(deps.mcpManager, current) };
  };
  const remainingToolsFactory = async (context: CouncilExtraToolsContext): Promise<AgentTool[]> => {
    const tools: AgentTool[] = commonTools.slice();
    if (context.feishuEnabled) tools.push(...createFeishuDocsTools(context.assistant.localTools.indexOf('webmount') >= 0));
    if (context.assistant.localTools.indexOf('clipboard') >= 0) tools.push(createClipboardTool(createEntryClipboardPort()));
    if (context.imageGenConfigured) tools.push(createImageGenTool({ conversationId: context.conversationId,
      sourceImageResolver: (): Promise<EditSourceImage | null> => Promise.resolve(null) }));
    return tools;
  };
  const seatAssembly = (activityStore: AgentToolActivityStore): CouncilAssemblyDeps => ({ assistant, runtime, activityStore, local,
    skills: { enabledSkills: assistant.enabledSkills.slice(), allSkills, skillManager: deps.skillManager,
      setSkillEnabled: (name: string, enable: boolean): Promise<void> => deps.setSkillEnabled(assistant.id, name, enable),
      workspace: deps.skillWorkspace, inflateRaw: inflateRawEntry },
    mcpManagement: { settings: deps.mcpSettings, manager: deps.mcpManager, skills: deps.skillManager,
      assistantIds: assistant.mcpServers.slice() }, mcpTools: seatMcpTools(mcpTools, deps.mcpManager, assistant, activityStore),
    memory: runtime.enableCoreMemory || runtime.enableShortTermMemory || runtime.enableLongTermMemory ? deps.memory : null,
    searchSettings: search, feishuEnabled, imageGenConfigured,
    // DeepRead ModelConfig/ChatModel has no context-window field. No Chat default is substituted.
    context: { compactStore: deps.compactStore, compressPrompt, modelContextWindowTokens: null },
    history: { repository: deps.repository, grantStore: deps.grantStore }, recipeStore, pluginStore,
    reranker: jevToolSearchReranker(), remainingToolsFactory, readLiveScope });
  return createCouncilToolRunner({ http: deps.http, offRunner: createModelCouncilRunner(deps.http),
    prepareSeat: async (request: CouncilGenerateRequest): Promise<CouncilSeatExecution> => {
      if (request.key === undefined || (request.toolMode !== 'search' && request.toolMode !== 'full')) {
        throw new Error('议会席位工具执行配置不完整');
      }
      const mode: 'search' | 'full' = request.toolMode;
      const store: CouncilSeatStore = createCouncilSeatStore(deps.files, request.key);
      const conversation: Conversation = makeConversation(`council_${sha256HexUtf8(JSON.stringify(request.key))}`,
        [], { assistantId: assistant.id });
      const assembly: CouncilAssemblyDeps = seatAssembly(new AgentToolActivityStore());
      return { assistant, conversation, store,
        makeLoop: (provider: (definitions: ChatToolDefinition[]) => ChatStreamProvider, model: ChatModel,
          beforeEffect?: (invocation: ToolInvocationRequest) => Promise<void>): Promise<ToolLoopOptions> =>
          createCouncilSeatLoop(assembly, conversation, store, provider, model, mode, beforeEffect) };
    } });
};

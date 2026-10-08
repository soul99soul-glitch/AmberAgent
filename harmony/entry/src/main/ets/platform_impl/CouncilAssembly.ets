import { createEntryJevAutoApprovalReview } from './JevAutoApprovalSupport.ets';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTool, AgentToolActivityStore, Assistant, ChatModel, ChatStreamProvider,
  ChatToolDefinition, CompactStore, Conversation, ConversationRepository, CreateSkillToolsDeps,
  JsonObject, JsonValue, LocalToolOption, McpManagementToolsDeps, McpServerConfig, McpSettingsPort,
  McpTool, McpToolIdentity, MemoryToolDeps, MoshRuntime, PluginStore, PosixWorkspaceManager,
  PythonRuntime, RecipeStore, SearchSettings, SessionAccessGrantStore, SSHProfileStore,
  TerminalApprovalGuard, TerminalRuntime, ToolInvocationHook, ToolInvocationRequest,
  ToolInvocationResult, ToolLoopOptions, ToolSearchReranker, UIMessagePart, UIMessagePartTool } from '@amber/chat-domain';
import { AgentToolDispatcher, cloneMcpServerConfig, createConversationContextTools,
  createConversationHistoryTools, createMcpManagementTools, createMemoryTools, createSearchTools,
  createSkillTools, createTerminalApprovalGuard, defaultToolInvocationHooks, makeAgentTool,
  makeCompactPolicy, makeMcpCommonOptions, patchAssistant, PermissionDecisionResolver,
  ToolInvocationBarrierError, currentMessages } from '@amber/chat-domain';
import type { AgentRuntimeSnapshot } from './AgentRuntimePrefs.ets';
import { createAssistantLocalPrimitives } from './AssistantLocalPrimitives.ets';
import type { CouncilSeatStore } from './CouncilSeatStore.ets';
import { createRunToolCatalog, scopeRunTools } from './RunToolCatalog.ets';
import type { RunToolCatalogSnapshot } from './RunToolCatalog.ets';

export interface CouncilLocalPorts {
  workspace: PosixWorkspaceManager;
  terminalRuntime: TerminalRuntime | null;
  moshRuntime: MoshRuntime | null;
  sshProfiles: SSHProfileStore | null;
  pythonRuntime: PythonRuntime | null;
}
export interface CouncilContextPorts {
  compactStore: CompactStore;
  compressPrompt: string;
  modelContextWindowTokens: number | null;
}
export interface CouncilHistoryPorts {
  repository: ConversationRepository;
  grantStore: SessionAccessGrantStore;
}
export interface CouncilLiveScope {
  assistant: Assistant | null;
  runtime: AgentRuntimeSnapshot;
  searchEnabled: boolean;
  feishuEnabled: boolean;
  imageGenConfigured: boolean;
  mcpTargets: McpToolIdentity[];
}
export interface CouncilExtraToolsContext {
  conversationId: string;
  assistant: Assistant;
  feishuEnabled: boolean;
  imageGenConfigured: boolean;
  currentConversationProvider: () => Promise<Conversation>;
}
export interface CouncilAssemblyDeps {
  assistant: Assistant;
  runtime: AgentRuntimeSnapshot;
  activityStore: AgentToolActivityStore;
  local: CouncilLocalPorts;
  skills: CreateSkillToolsDeps;
  mcpManagement: McpManagementToolsDeps;
  mcpTools: AgentTool[];
  memory: MemoryToolDeps | null;
  searchSettings: SearchSettings;
  feishuEnabled: boolean;
  imageGenConfigured: boolean;
  context: CouncilContextPorts | null;
  history: CouncilHistoryPorts | null;
  recipeStore: RecipeStore;
  pluginStore: PluginStore;
  reranker?: ToolSearchReranker;
  remainingToolsFactory: (context: CouncilExtraToolsContext) => Promise<AgentTool[]>;
  readLiveScope: (assistantId: string) => Promise<CouncilLiveScope>;
}

const SEARCH_TOOLS: string[] = ['search_web', 'scrape_web', 'search_sources_status', 'search_strategy_explain'];
const hasTarget = (targets: McpToolIdentity[], target: McpToolIdentity): boolean => targets.some(
  (candidate: McpToolIdentity): boolean => candidate.serverId === target.serverId && candidate.toolName === target.toolName);
const hasSeatHost = (tool: AgentTool): boolean => !tool.name.startsWith('wm_')
  && !tool.name.startsWith('webview_') && !tool.name.startsWith('subagent_')
  && tool.name !== 'javascript_execute' && tool.name !== 'conversation_queue_status'
  && tool.name !== 'conversation_queue_cancel' && tool.name !== 'deep_read_open';
const memoryEnabled = (runtime: AgentRuntimeSnapshot): boolean => runtime.enableCoreMemory
  || runtime.enableShortTermMemory || runtime.enableLongTermMemory;
const catalogOnly = (tool: AgentTool): boolean => tool.name === 'tool_search' || tool.name === 'tools_list'
  || tool.name === 'tool_policy_explain' || tool.name === 'recipes_list' || tool.name.startsWith('recipe_')
  || tool.name === 'plugins_list' || tool.name.startsWith('plugin_');

const resolveMcpTarget = (
  configs: McpServerConfig[], targets: McpToolIdentity[], input: JsonObject,
): McpToolIdentity => {
  const candidates: McpServerConfig[] = configs.filter((server: McpServerConfig): boolean =>
    server.commonOptions.enable
    && (input['server_id'] === undefined || input['server_id'] === null || input['server_id'] === '' || server.id === input['server_id'])
    && (input['name'] === undefined || input['name'] === null || input['name'] === '' || server.commonOptions.name === input['name'])
    && targets.some((target: McpToolIdentity): boolean => target.serverId === server.id && target.toolName === input['tool_name'])
    && server.commonOptions.tools.some((tool: McpTool): boolean => tool.enable && tool.name === input['tool_name']));
  if (candidates.length !== 1) throw new Error('MCP 工具不在议会已授权的范围内，或需要明确服务器身份');
  return { serverId: candidates[0].id, toolName: input['tool_name'] as string };
};

const effectiveAssistant = (frozen: Assistant, live: CouncilLiveScope): Assistant => {
  const current: Assistant | null = live.assistant;
  if (current === null || current.id !== frozen.id) throw new Error('议会使用的默认助手已移除，无法继续执行工具');
  return patchAssistant(frozen, {
    localTools: frozen.localTools.filter((option: LocalToolOption): boolean => current.localTools.indexOf(option) >= 0),
    enabledSkills: frozen.enabledSkills.filter((name: string): boolean => current.enabledSkills.indexOf(name) >= 0),
    mcpServers: frozen.mcpServers.filter((id: string): boolean => current.mcpServers.indexOf(id) >= 0),
  });
};

// Preserve the initial exposed aliases; revoking one duplicate must not rename another.
const scopedMcpManagement = (
  deps: CouncilAssemblyDeps, assistant: Assistant, targets: McpToolIdentity[],
): AgentTool[] => {
  const original: McpSettingsPort = deps.mcpManagement.settings;
  const servers = (configs: McpServerConfig[]): McpServerConfig[] => configs.filter(
    (server: McpServerConfig): boolean => assistant.mcpServers.indexOf(server.id) >= 0).map(
    (server: McpServerConfig): McpServerConfig => cloneMcpServerConfig(server, server.id, makeMcpCommonOptions({
      enable: server.commonOptions.enable, name: server.commonOptions.name, headers: server.commonOptions.headers,
      tools: server.commonOptions.tools.filter((tool: McpTool): boolean => hasTarget(targets,
        { serverId: server.id, toolName: tool.name })),
    })));
  const settings: McpSettingsPort = {
    getMcpServers: (): McpServerConfig[] => servers(original.getMcpServers()),
    getCurrentAssistantMcpServerIds: (): string[] => assistant.mcpServers,
    updateMcpServers: (update: (old: McpServerConfig[]) => McpServerConfig[]): void => original.updateMcpServers(update),
    subscribeMcpServers: (listener: (configs: McpServerConfig[]) => void): (() => void) =>
      original.subscribeMcpServers((configs: McpServerConfig[]): void => listener(servers(configs))),
  };
  const tools: AgentTool[] = createMcpManagementTools({ settings, manager: deps.mcpManagement.manager,
    skills: deps.mcpManagement.skills, assistantIds: assistant.mcpServers });
  return tools.map((tool: AgentTool): AgentTool => {
    if (tool.name !== 'mcp_call_tool') return tool;
    return makeAgentTool({ name: tool.name, description: tool.description, parameters: tool.parameters,
      systemPrompt: tool.systemPrompt, needsApproval: tool.needsApproval, allowsAutoApproval: tool.allowsAutoApproval,
      mandatoryApproval: tool.mandatoryApproval, recipeEnvelope: tool.recipeEnvelope,
      pluginEnvelope: tool.pluginEnvelope, mcpTarget: tool.mcpTarget,
      execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
        const obj: JsonObject = input as JsonObject;
        const live: CouncilLiveScope = await deps.readLiveScope(deps.assistant.id);
        const current: Assistant = effectiveAssistant(deps.assistant, live);
        const allowed: McpToolIdentity[] = targets.filter((target: McpToolIdentity): boolean =>
          current.mcpServers.indexOf(target.serverId) >= 0 && hasTarget(live.mcpTargets, target));
        const target: McpToolIdentity = resolveMcpTarget(settings.getMcpServers(), allowed, obj);
        const pinned: JsonObject = {};
        // arkts-no-delete: copy every key except the alias name, then pin the server id.
        Object.keys(obj).forEach((key: string): void => { if (key !== 'name') pinned[key] = obj[key]; });
        pinned['server_id'] = target.serverId;
        return tool.execute(pinned, signal);
      },
    });
  });
};

export const createCouncilSeatLoop = async (
  deps: CouncilAssemblyDeps, conversation: Conversation, store: CouncilSeatStore,
  makeProviderForStep: (definitions: ChatToolDefinition[]) => ChatStreamProvider,
  toolPromptModel: ChatModel, mode: 'search' | 'full',
  beforeEffect?: (request: ToolInvocationRequest) => Promise<void>,
): Promise<ToolLoopOptions> => {
  if (conversation.assistantId !== deps.assistant.id) throw new Error('议会席位助手身份不匹配');
  const conversationProvider = async (): Promise<Conversation> => {
    const current: Conversation | null = await store.load();
    if (current === null) throw new Error('议会席位保存的会话不存在');
    return current;
  };
  let currentMcpTargets: McpToolIdentity[] = [];
  const primitives = async (live: CouncilLiveScope): Promise<AgentTool[]> => {
    const assistant: Assistant = effectiveAssistant(deps.assistant, live);
    const searches: AgentTool[] = deps.searchSettings.enableWebSearch && live.searchEnabled
      ? createSearchTools(deps.searchSettings) : [];
    if (mode === 'search') return searches.filter((tool: AgentTool): boolean => SEARCH_TOOLS.indexOf(tool.name) >= 0);
    const targets: McpToolIdentity[] = [];
    for (const tool of deps.mcpTools) {
      if (tool.mcpTarget !== undefined && assistant.mcpServers.indexOf(tool.mcpTarget.serverId) >= 0
        && hasTarget(live.mcpTargets, tool.mcpTarget)) targets.push(tool.mcpTarget);
    }
    currentMcpTargets = targets;
    const skills: CreateSkillToolsDeps = { enabledSkills: assistant.enabledSkills, allSkills: deps.skills.allSkills,
      skillManager: deps.skills.skillManager, setSkillEnabled: deps.skills.setSkillEnabled,
      workspace: deps.skills.workspace, inflateRaw: deps.skills.inflateRaw };
    const tools: AgentTool[] = [
      ...createAssistantLocalPrimitives({ assistant, conversationId: conversation.id, workspace: deps.local.workspace,
        activityStore: deps.activityStore, terminalRuntime: deps.local.terminalRuntime, moshRuntime: deps.local.moshRuntime,
        sshProfiles: deps.local.sshProfiles, pythonRuntime: deps.local.pythonRuntime }),
      ...createSkillTools(skills), ...scopedMcpManagement(deps, assistant, targets),
      ...deps.mcpTools.filter((tool: AgentTool): boolean => tool.mcpTarget !== undefined && hasTarget(targets, tool.mcpTarget)),
      ...(deps.memory !== null && memoryEnabled(deps.runtime) && memoryEnabled(live.runtime)
        ? createMemoryTools(deps.memory) : []),
      ...searches,
      ...await deps.remainingToolsFactory({ conversationId: conversation.id, assistant,
        feishuEnabled: deps.feishuEnabled && live.feishuEnabled,
        imageGenConfigured: deps.imageGenConfigured && live.imageGenConfigured,
        currentConversationProvider: conversationProvider }),
    ];
    if (deps.context !== null) {
      const compact = deps.runtime.contextCompaction;
      tools.push(...createConversationContextTools({ conversationProvider, compactStore: deps.context.compactStore,
        policy: makeCompactPolicy({ enabled: compact.enabled && live.runtime.contextCompaction.enabled,
          notifyOnly: compact.notifyOnly || live.runtime.contextCompaction.notifyOnly,
          precompactRatio: compact.precompactRatio, forceRatio: compact.forceRatio,
          keepRecentTurns: compact.keepRecentTurns, maxSummaryTokens: compact.maxSummaryTokens }),
        modelContextWindowTokens: deps.context.modelContextWindowTokens,
        compactEngineDeps: { provider: makeProviderForStep([]), store: deps.context.compactStore,
          compressPrompt: deps.context.compressPrompt } }));
    }
    if (deps.history !== null) tools.push(...createConversationHistoryTools({ repository: deps.history.repository,
      currentConversationProvider: conversationProvider, grantStore: deps.history.grantStore }));
    return tools.filter(hasSeatHost);
  };
  const terminalGuard: TerminalApprovalGuard | null = deps.assistant.localTools.indexOf('terminal') >= 0
    && deps.local.terminalRuntime !== null && deps.local.sshProfiles !== null
    ? createTerminalApprovalGuard(deps.local.terminalRuntime, deps.local.sshProfiles, deps.local.moshRuntime ?? undefined) : null;
  let currentTools: AgentTool[] = [];
  let snapshot: RunToolCatalogSnapshot | null = null;
  const resolver: PermissionDecisionResolver = new PermissionDecisionResolver();
  const hooks: ToolInvocationHook[] = defaultToolInvocationHooks();
  if (terminalGuard !== null) hooks.push(terminalGuard.hook);
  hooks.push({ before: async (request: ToolInvocationRequest): Promise<ToolInvocationResult | null> => {
    try {
      const live: CouncilLiveScope = await deps.readLiveScope(deps.assistant.id);
      const available: AgentTool[] = await primitives(live);
      const toolDef: AgentTool | null = available.find((tool: AgentTool): boolean => tool.name === request.tool.toolName)
        ?? currentTools.find((tool: AgentTool): boolean => tool.name === request.tool.toolName && catalogOnly(tool)) ?? null;
      if (toolDef === null) throw new Error('该工具的议会执行权限已撤销');
      if (request.tool.toolName === 'mcp_call_tool') {
        const savedIdentity: JsonObject | null = (request.tool.metadata?.['council_mcp_target'] ?? null) as JsonObject | null;
        const target: McpToolIdentity | null = savedIdentity !== null
          && typeof savedIdentity['serverId'] === 'string' && typeof savedIdentity['toolName'] === 'string'
          ? { serverId: savedIdentity['serverId'] as string, toolName: savedIdentity['toolName'] as string } : null;
        if (target === null || !hasTarget(currentMcpTargets, target)) throw new Error('保存的 MCP 工具身份已不可用');
        const args: JsonObject = request.parsedArgs as JsonObject;
        if (args['tool_name'] !== target.toolName) throw new Error('保存的 MCP 工具身份与参数不匹配');
        args['server_id'] = target.serverId;
        // arkts-no-delete: resolveMcpTarget treats a null alias name as absent.
        args['name'] = null;
      }
      const auto: boolean = deps.runtime.autoApproveAllToolCalls && live.runtime.autoApproveAllToolCalls;
      const high: boolean = deps.runtime.autoApproveHighRiskToolCalls && live.runtime.autoApproveHighRiskToolCalls;
      if (resolver.resolve(toolDef, request.tool, auto, high, [], 'model_council').action !== 'allow') {
        throw new Error('工具自动批准权限已撤销，请重新发起本席位工具请求');
      }
      if (beforeEffect !== undefined) await beforeEffect(request);
      return null;
    } catch (error) {
      throw new ToolInvocationBarrierError(error instanceof Error ? error.message : String(error)) as Error;
    }
  } });
  const firstLive: CouncilLiveScope = await deps.readLiveScope(deps.assistant.id);
  const options: ToolLoopOptions = { tools: [], dispatcher: new AgentToolDispatcher({ hooks, autoApprovalReview: createEntryJevAutoApprovalReview(currentMessages(await store.load() ?? conversation)) }),
    captureInvocationMetadata: (part: UIMessagePartTool): JsonObject | null => {
      const metadata: JsonObject = terminalGuard?.captureInvocationMetadata(part) ?? {};
      if (part.toolName === 'mcp_call_tool') {
        const target: McpToolIdentity = resolveMcpTarget(deps.mcpManagement.settings.getMcpServers(), currentMcpTargets,
          JSON.parse(part.input.trim().length === 0 ? '{}' : part.input) as JsonObject);
        const targetJSON: JsonObject = { 'serverId': target.serverId, 'toolName': target.toolName };
        metadata['council_mcp_target'] = targetJSON;
      }
      return Object.keys(metadata).length > 0 ? metadata : null;
    },
    makeProviderForStep, toolPromptModel, invocationContext: 'model_council',
    autoApproveTools: deps.runtime.autoApproveAllToolCalls && firstLive.runtime.autoApproveAllToolCalls,
    autoApproveHighRiskTools: deps.runtime.autoApproveHighRiskToolCalls && firstLive.runtime.autoApproveHighRiskToolCalls,
    speculativeEnabled: deps.runtime.speculativeToolExecution.enabled && firstLive.runtime.speculativeToolExecution.enabled,
    speculativeMaxConcurrentTools: Math.min(deps.runtime.speculativeToolExecution.maxConcurrentTools,
      firstLive.runtime.speculativeToolExecution.maxConcurrentTools),
    maxSteps: Math.min(deps.runtime.maxToolLoopSteps, firstLive.runtime.maxToolLoopSteps) };
  const refresh = async (live: CouncilLiveScope): Promise<AgentTool[]> => {
    const tools: AgentTool[] = await primitives(live);
    options.autoApproveTools = deps.runtime.autoApproveAllToolCalls && live.runtime.autoApproveAllToolCalls;
    options.autoApproveHighRiskTools = deps.runtime.autoApproveHighRiskToolCalls && live.runtime.autoApproveHighRiskToolCalls;
    if (mode === 'full') {
      snapshot = await createRunToolCatalog({ primitives: tools, profile: 'full', conversationId: conversation.id,
        recipeStore: deps.recipeStore, pluginStore: deps.pluginStore, reranker: deps.reranker });
      options.recipeAdapter = snapshot.recipeAdapter;
      options.pluginAdapter = snapshot.pluginAdapter;
      currentTools = snapshot.tools;
    } else currentTools = tools;
    return scopeRunTools(currentTools, conversation.id, deps.activityStore);
  };
  options.tools = await refresh(firstLive);
  options.refreshTools = async (): Promise<AgentTool[]> => refresh(await deps.readLiveScope(deps.assistant.id));
  return options;
};

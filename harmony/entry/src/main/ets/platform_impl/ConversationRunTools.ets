// Per-run tool catalog. No callback below depends on a ChatPage instance.
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  AgentTool, AgentToolActivityStore, Assistant, ChatStreamProvider,
  ChatToolDefinition, Conversation, DeepReadOpenEvent, InstalledPlugin, InstalledRecipe,
  JsonValue, LocalToolOption, McpToolActivityPort, PendingUserMessage, PluginLoopAdapter,
  PluginStore, RecipeLoopAdapter, RecipeStore, TerminalApprovalGuard, TextGenerationParams,
  ToolLoopOptions, ToolRegistry, UIMessage, UIMessagePart, UIMessagePartImage,
} from '@amber/chat-domain';
import {
  AgentToolDispatcher, asChatStreamProvider, createClipboardTool,
  createConversationContextTools, createConversationHistoryTools, createConversationQueueTools,
  createDeepReadOpenTool, createDeepReadPlaybookTools, createHealthSummaryTool,
  createMcpManagementTools, createMcpServerTools, createMemoryTools, createPluginTools,
  createRecipeLoopAdapter, createRecipeTools, createRegistryIntrospectionTools,
  createRunPlanUpdateTool, createSearchTools, createSkillTools, createTerminalApprovalGuard,
  createToolRegistry, createToolSearchTool, currentMessages, defaultToolInvocationHooks,
  filterToolProfile, makeAgentTool, makeCompactPolicy, makeTextGenerationParams, patchAssistant,
} from '@amber/chat-domain';
import {
  getAgentPromptConfigTool, getChatRepository, getCompactStore,
  getDeepReadPlaybookRepository, getMcpManager, getPermissionsStatusTool,
  getPluginStore, getRecipeStore, getSessionGrantStore, getSkillManager,
  getSkillWorkspacePort, getSubAgentTools, getWorkspaceManager,
} from '../di/AppContainer.ets';
import type { ChatRuntime, SubAgentToolsContext } from '../di/AppContainer.ets';
import { systemLanguage } from './AgentRuntimePrefs.ets';
import { mutateAssistant } from './AssistantMutation.ets';
import { createAssistantLocalPrimitives } from './AssistantLocalPrimitives.ets';
import { guardConversationBrowserTools } from './ConversationBrowserGuard.ets';
import { buildConversationCompressProvider } from './ConversationRunDependencies.ets';
import type { DetachedConversationContext, PreparedConversationSeed } from './ConversationRunService.ets';
import { inflateRawEntry } from './EntryDocumentParser.ets';
import { createEntryClipboardPort, sha256HexUtf8 } from './EntryLocalToolPorts.ets';
import { createEntryPluginAdapter } from './EntryPluginRuntime.ets';
import { createFeishuDocsTools } from './FeishuDocsTools.ets';
import { createEntryHealthReadPort } from './HealthReadPort.ets';
import { createImageGenTool } from './ImageGenTool.ets';
import type { EditSourceImage } from './ImageGenTool.ets';
import { createJevStatusTool, jevToolSearchReranker } from './JevSupport.ets';
import { getMcpSettingsStore } from './McpSettingsStore.ets';
import { getMemoryToolDeps } from './MemoryStore.ets';
import { createSoulTools } from './SoulTools.ets';
import { getWebMountSession } from './WebMountSession.ets';
import { createWebMountTools } from './WebMountTools.ets';

const mcpActivityPort = (store: AgentToolActivityStore): McpToolActivityPort => ({
  startTool: (toolName: string, title: string, inputPreview: string, runtime: string): string =>
    store.startTool(toolName, title, inputPreview, runtime),
  complete: (toolCallId: string, outputPreview: string): void => {
    store.complete(toolCallId, outputPreview);
  },
  fail: (toolCallId: string, error: Error): void => {
    store.fail(toolCallId, error);
  },
});

const scopedToConversation = (tools: AgentTool[], ctx: DetachedConversationContext): AgentTool[] =>
  tools.map((tool: AgentTool): AgentTool => makeAgentTool({
    name: tool.name,
    description: tool.description,
    parameters: tool.parameters,
    systemPrompt: tool.systemPrompt,
    needsApproval: tool.needsApproval,
    allowsAutoApproval: tool.allowsAutoApproval,
    mandatoryApproval: tool.mandatoryApproval,
    recipeEnvelope: tool.recipeEnvelope,
    pluginEnvelope: tool.pluginEnvelope,
    mcpTarget: tool.mcpTarget,
    execute: (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> =>
      ctx.activityStore.withConversation(ctx.conversationId,
        (): Promise<UIMessagePart[]> => tool.execute(input, signal)),
  }));

const setSkillEnabled = async (rt: ChatRuntime, name: string, enable: boolean): Promise<void> => {
  await mutateAssistant(rt.assistant.id, (assistant: Assistant): Assistant => {
    const skills: string[] = enable
      ? (assistant.enabledSkills.indexOf(name) >= 0
        ? assistant.enabledSkills : assistant.enabledSkills.concat([name]))
      : assistant.enabledSkills.filter((item: string): boolean => item !== name);
    return patchAssistant(assistant, { enabledSkills: skills });
  });
};

const isAllowedEditMime = (mime: string): boolean =>
  mime === 'image/png' || mime === 'image/jpeg' || mime === 'image/webp';

const editMimeExt = (mime: string): string =>
  mime === 'image/jpeg' ? 'jpg' : (mime === 'image/webp' ? 'webp' : 'png');

const editMimeByName = (name: string): string => {
  const dot: number = name.lastIndexOf('.');
  if (dot < 0) return 'application/octet-stream';
  const ext: string = name.substring(dot + 1).toLowerCase();
  if (ext === 'png') return 'image/png';
  if (ext === 'jpg' || ext === 'jpeg') return 'image/jpeg';
  if (ext === 'webp') return 'image/webp';
  return 'application/octet-stream';
};

const readUriBytes = (uri: string): Uint8Array => {
  const file: fileIo.File = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
  try {
    const stat: fileIo.Stat = fileIo.statSync(file.fd);
    if (stat.size > 10 * 1024 * 1024) throw new Error(`文件超过 10MB: ${stat.size} bytes`);
    const buffer: ArrayBuffer = new ArrayBuffer(stat.size);
    fileIo.readSync(file.fd, buffer, { offset: 0, length: stat.size });
    return new Uint8Array(buffer);
  } finally {
    fileIo.closeSync(file);
  }
};

const loadEditSourceBytes = async (url: string): Promise<EditSourceImage | null> => {
  try {
    if (url.startsWith('data:')) {
      const comma: number = url.indexOf(',');
      if (comma < 0) return null;
      const mime: string = url.substring(5, comma).split(';')[0];
      if (!isAllowedEditMime(mime)) return null;
      const bytes: Uint8Array = new util.Base64Helper().decodeSync(url.substring(comma + 1));
      return { bytes, mime, name: `source.${editMimeExt(mime)}` };
    }
    if (url.startsWith('file://')) {
      const mime: string = editMimeByName(url);
      if (!isAllowedEditMime(mime)) return null;
      const bytes: Uint8Array = readUriBytes(url);
      const name: string = url.substring(url.lastIndexOf('/') + 1);
      return { bytes, mime, name: name.length > 0 ? name : `source.${editMimeExt(mime)}` };
    }
    return null;
  } catch {
    return null;
  }
};

const resolveEditSourceImage = async (ctx: DetachedConversationContext): Promise<EditSourceImage | null> => {
  const messages: UIMessage[] = currentMessages(ctx.conversation);
  for (let index: number = messages.length - 1; index >= 0; index--) {
    if (messages[index].role !== 'user') continue;
    for (const part of messages[index].parts) {
      if (part.type !== 'image') continue;
      const resolved: EditSourceImage | null = await loadEditSourceBytes((part as UIMessagePartImage).url);
      if (resolved !== null) return resolved;
    }
  }
  return null;
};

const prepareSubAgentTools = async (ctx: DetachedConversationContext): Promise<void> => {
  ctx.subAgentTools = [];
  if (!ctx.seed.subAgentEnabled) return;
  try {
    const subAgentContext: SubAgentToolsContext = {
      parentConversationId: ctx.conversationId,
      parentToolsProvider: (): AgentTool[] => ctx.baseTools,
      parentRuntime: ctx.seed.runtime,
      parentAgentRuntime: ctx.seed.agentRuntime,
    };
    ctx.subAgentTools = await getSubAgentTools(subAgentContext);
  } catch (error) {
    const message: string = error instanceof Error ? error.message : String(error);
    hilog.warn(0x0001, 'Chat.RunTools', 'prepareSubAgentTools failed: %{public}s', message);
  }
};

const warnIfToolAbilityMissing = (ctx: DetachedConversationContext): void => {
  const rt: ChatRuntime = ctx.seed.runtime;
  if (rt.modelAbilities.indexOf('tool') >= 0) return;
  if (!ctx.seed.searchSettings.enableWebSearch
    && getMcpManager().getAllAvailableTools([...rt.assistant.mcpServers]).length === 0) return;
  ctx.publish({ errorMsg: '当前模型未开启工具调用，请在设置内启用模型的工具能力，再使用搜索/MCP等功能' });
};

export const buildConversationToolLoop = async (ctx: DetachedConversationContext): Promise<ToolLoopOptions> => {
  const seed: PreparedConversationSeed = ctx.seed;
  const rt: ChatRuntime = seed.runtime;
  const conversation: Conversation = ctx.conversation;
  const conversationProvider: () => Promise<Conversation> = (): Promise<Conversation> =>
    Promise.resolve(ctx.conversation);
  const localOptions: LocalToolOption[] = rt.assistant.localTools;
  const terminalGuard: TerminalApprovalGuard | null = localOptions.indexOf('terminal') >= 0
    && rt.terminalRuntime !== null && rt.sshProfiles !== null
    ? createTerminalApprovalGuard(rt.terminalRuntime, rt.sshProfiles, rt.moshRuntime ?? undefined) : null;
  const webmountEnabled: boolean = seed.webmountGlobalEnabled || localOptions.indexOf('webmount') >= 0;
  const runtime = seed.agentRuntime;
  const compact = runtime.contextCompaction;
  const profiledTools: AgentTool[] = guardConversationBrowserTools(filterToolProfile([
    ...createAssistantLocalPrimitives({ assistant: rt.assistant, conversationId: ctx.conversationId,
      workspace: getWorkspaceManager(), activityStore: ctx.activityStore,
      terminalRuntime: rt.terminalRuntime, moshRuntime: rt.moshRuntime,
      sshProfiles: rt.sshProfiles, pythonRuntime: rt.pythonRuntime }),
    ...(webmountEnabled ? createWebMountTools(getWebMountSession(),
      seed.webmountEvalEnabled || localOptions.indexOf('webmount_eval') >= 0,
      localOptions.indexOf('webmount') >= 0) : []),
    ...(webmountEnabled ? createFeishuDocsTools(localOptions.indexOf('webmount') >= 0) : []),
    ...createSkillTools({
      enabledSkills: rt.assistant.enabledSkills,
      allSkills: rt.allSkills,
      skillManager: getSkillManager(),
      setSkillEnabled: (name: string, enable: boolean): Promise<void> => setSkillEnabled(rt, name, enable),
      workspace: getSkillWorkspacePort(),
      inflateRaw: inflateRawEntry,
    }),
    ...createMcpManagementTools({ settings: getMcpSettingsStore(), manager: getMcpManager(),
      skills: getSkillManager(), assistantIds: [...rt.assistant.mcpServers] }),
    ...createMcpServerTools({ manager: getMcpManager(), activity: mcpActivityPort(ctx.activityStore),
      assistantIds: [...rt.assistant.mcpServers] }),
    ...((runtime.enableCoreMemory || runtime.enableShortTermMemory || runtime.enableLongTermMemory)
      ? createMemoryTools(getMemoryToolDeps(
        new Intl.DateTimeFormat(systemLanguage(), { dateStyle: 'medium' }).format(new Date()))) : []),
    ...createConversationContextTools({
      conversationProvider,
      compactStore: getCompactStore(),
      policy: makeCompactPolicy({ enabled: compact.enabled, notifyOnly: compact.notifyOnly,
        precompactRatio: compact.precompactRatio, forceRatio: compact.forceRatio,
        keepRecentTurns: compact.keepRecentTurns, maxSummaryTokens: compact.maxSummaryTokens }),
      modelContextWindowTokens: rt.modelContextWindowTokens,
      compactEngineDeps: { provider: buildConversationCompressProvider(seed), store: getCompactStore(),
        compressPrompt: seed.taskPrompts.compressPrompt },
    }),
    ...(seed.imageGenConfigured ? [createImageGenTool({ conversationId: ctx.conversationId,
      sourceImageResolver: (): Promise<EditSourceImage | null> => resolveEditSourceImage(ctx) })] : []),
    ...createConversationHistoryTools({ repository: getChatRepository(),
      currentConversationProvider: conversationProvider, grantStore: getSessionGrantStore() }),
    ...createConversationQueueTools({
      pendingProvider: (): Promise<PendingUserMessage[]> => Promise.resolve(ctx.pending()),
      cancelOne: (messageId: string): Promise<void> => ctx.cancelPending(messageId),
      clearAll: (): Promise<void> => ctx.clearPending(),
    }),
    ...seed.agentTaskTools,
    getPermissionsStatusTool(), createJevStatusTool(), ...seed.agentCronTools,
    createRunPlanUpdateTool(), createHealthSummaryTool(createEntryHealthReadPort()),
    ...createSoulTools(),
    ...(localOptions.indexOf('clipboard') >= 0 ? [createClipboardTool(createEntryClipboardPort())] : []),
    getAgentPromptConfigTool(), ...createDeepReadPlaybookTools(getDeepReadPlaybookRepository()),
    createDeepReadOpenTool({
      bus: { hasCollectors: (): boolean => ctx.hasNavigationCollector(),
        emit: (event: DeepReadOpenEvent): void => ctx.emitDeepRead(event) },
      sha256Hex: sha256HexUtf8,
    }),
    ...(seed.searchSettings.enableWebSearch ? createSearchTools(seed.searchSettings) : []),
  ], rt.assistant.toolProfile).tools, ctx.conversationId);
  await prepareSubAgentTools(ctx);
  warnIfToolAbilityMissing(ctx);
  const recipeStore: RecipeStore = await getRecipeStore();
  const pluginStore: PluginStore = await getPluginStore();
  const primitiveRegistry: ToolRegistry = createToolRegistry(profiledTools);
  const pluginPrimitives: AgentTool[] = [...profiledTools, ...createRegistryIntrospectionTools(primitiveRegistry),
    createToolSearchTool(primitiveRegistry, rt.assistant.toolProfile, jevToolSearchReranker())];
  let recipeAdapter: RecipeLoopAdapter = createRecipeLoopAdapter({ store: recipeStore, installed: [] });
  let pluginAdapter: PluginLoopAdapter = createEntryPluginAdapter(pluginStore, [], pluginPrimitives, ctx.conversationId);
  const catalog = (installed: InstalledRecipe[], installedPlugins: InstalledPlugin[]): AgentTool[] => {
    recipeAdapter = createRecipeLoopAdapter({ store: recipeStore, installed });
    const recipeTools: AgentTool[] = filterToolProfile(createRecipeTools({
      store: recipeStore, installed, primitives: profiledTools,
    }), rt.assistant.toolProfile).tools;
    pluginAdapter = createEntryPluginAdapter(pluginStore, installedPlugins, pluginPrimitives, ctx.conversationId);
    const pluginTools: AgentTool[] = filterToolProfile(createPluginTools({
      store: pluginStore, installed: installedPlugins, primitives: pluginPrimitives,
    }), rt.assistant.toolProfile).tools;
    const registry: ToolRegistry = createToolRegistry([...profiledTools, ...recipeTools, ...pluginTools]);
    ctx.baseTools = [...registry.tools(), ...createRegistryIntrospectionTools(registry)];
    return scopedToConversation([...ctx.baseTools,
      createToolSearchTool(registry, rt.assistant.toolProfile, jevToolSearchReranker()), ...ctx.subAgentTools], ctx);
  };
  const tools: AgentTool[] = catalog(await recipeStore.listInstalled(), await pluginStore.listInstalled(pluginPrimitives));
  const options: ToolLoopOptions = {
    dispatcher: new AgentToolDispatcher({ hooks: terminalGuard === null
      ? defaultToolInvocationHooks() : [...defaultToolInvocationHooks(), terminalGuard.hook] }),
    captureInvocationMetadata: terminalGuard === null ? undefined : terminalGuard.captureInvocationMetadata,
    tools,
    recipeAdapter,
    pluginAdapter,
    refreshTools: async (): Promise<AgentTool[]> => {
      const refreshed: AgentTool[] = catalog(await recipeStore.listInstalled(), await pluginStore.listInstalled(pluginPrimitives));
      options.recipeAdapter = recipeAdapter;
      options.pluginAdapter = pluginAdapter;
      return refreshed;
    },
    makeProviderForStep: (definitions: ChatToolDefinition[]): ChatStreamProvider =>
      asChatStreamProvider(rt.api, (): TextGenerationParams => makeTextGenerationParams({
        model: rt.params.model, temperature: rt.params.temperature, topP: rt.params.topP,
        maxTokens: rt.params.maxTokens, tools: definitions, reasoningLevel: rt.params.reasoningLevel,
        customBody: rt.params.customBody,
      })),
    consumeSteerMessages: (): Promise<UIMessage[]> => ctx.consumeSteer(),
    toolPromptModel: rt.params.model,
    autoApproveTools: runtime.autoApproveAllToolCalls || conversation.autoApproveToolCalls,
    autoApproveHighRiskTools: runtime.autoApproveHighRiskToolCalls,
    speculativeEnabled: runtime.speculativeToolExecution.enabled,
    speculativeMaxConcurrentTools: runtime.speculativeToolExecution.maxConcurrentTools,
    maxSteps: runtime.maxToolLoopSteps,
  };
  return options;
};

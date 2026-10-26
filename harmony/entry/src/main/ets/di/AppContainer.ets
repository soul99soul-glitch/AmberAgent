// Composition root and caller-compatible exports. Resource state belongs to the runtime factories.
import type { SkillMetadata, TaskModelReference, MiniAppRecord, ChatStreamProvider, ChatTurnDeps, ToolLoopOptions, KeyValueStore, Assistant, AgentTool, SubAgentGenerationRequest, Conversation, JevSettings, JevQuestion, JevEvaluateResult, JsonObject, PluginStore, InstalledPlugin, PluginLoopAdapter, TerminalApprovalGuard } from '@amber/chat-domain';
import { patchAssistant, loadJevSettings, AgentToolDispatcher, createTerminalApprovalGuard } from '@amber/chat-domain';
import { createConversationToolComposition } from './ConversationToolComposition.ets';
import type { ChatExecutionResources, ConversationPreparationPorts, ConversationTurnPorts } from '../platform_impl/ConversationRunDependencies.ets';
import { prepareChatRuntime, prepareConversationSeed, buildConversationTurnDeps, buildConversationCompressProvider } from '../platform_impl/ConversationRunDependencies.ets';
import type { ConversationAuxiliaryPorts } from '../platform_impl/ConversationRunAuxiliary.ets';
import { completeConversationAuxiliary } from '../platform_impl/ConversationRunAuxiliary.ets';
import type { ConversationRunExecution, ConversationRunToolPorts } from '../platform_impl/ConversationRunPorts.ets';
import type { PreparedConversationSeed, DetachedConversationContext } from '../platform_impl/ConversationRunService.ets';
import { ConversationRunService } from '../platform_impl/ConversationRunService.ets';
import { buildConversationToolLoop } from '../platform_impl/ConversationRunTools.ets';
import { createEntryBase64ImageTransformer } from '../platform_impl/Base64ImageSupport.ets';
import { createEntryDocumentReader } from '../platform_impl/DocumentSupport.ets';
import { createEntryOcrTransformer, loadOcrSeed, findModelByIdInProviders } from '../platform_impl/OcrSupport.ets';
import { createEntryPlaceholderValues } from '../platform_impl/PlaceholderSupport.ets';
import { imageGenToolAvailable, createImageGenTool } from '../platform_impl/ImageGenTool.ets';
import { jevNoulBatch, createJevStatusTool, jevToolSearchReranker, jevChoiceSelect, jevEvaluate } from '../platform_impl/JevSupport.ets';
import { beginJevContextRun } from '../platform_impl/JevContextSupport.ets';
import { getMemoryReadRepository, getMemoryLibraryOps, getMemoryTopicStoreOps, getMemoryExtractionStoreOps, getMemoryToolDeps } from '../platform_impl/MemoryStore.ets';
import type { MiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import { getMiniAppPlatform, getMiniAppRepositorySync } from '../platform_impl/MiniAppPlatform.ets';
import { canCommitMemoryExtraction } from '../platform_impl/MemoryExtractionCommitGuard.ets';
import { getConversationRecapService } from '../platform_impl/ConversationRecapSupport.ets';
import { notifyGenerationDone } from '../platform_impl/NotificationNotifier.ets';
import { compactConversationNow } from '../platform_impl/ContextCompactionSupport.ets';
import { evaluateJevCompletion } from '../platform_impl/JevCompletionSupport.ets';
import { scheduleJevApprovalTriage, cancelJevApprovalTriage } from '../platform_impl/JevApprovalTriage.ets';
import { materializeDocumentParts, materializeConversationDocuments } from '../platform_impl/DocumentAttachmentSupport.ets';
import { getChatCouncilTools } from '../platform_impl/CouncilChatSupport.ets';
import { createEntryModelSlotPort } from '../platform_impl/ModelSlotPort.ets';
import { createEntryWeatherPort } from '../platform_impl/WeatherPort.ets';
import { createWebMountParityTools } from '../platform_impl/WebMountParityTools.ets';
import { createFeishuDocsTools } from '../platform_impl/FeishuDocsTools.ets';
import { createSoulTools } from '../platform_impl/SoulTools.ets';
import { guardPage, createWebMountTools } from '../platform_impl/WebMountTools.ets';
import { createWebMountAdvancedTools } from '../platform_impl/WebMountAdvancedTools.ets';
import type { ProviderModelRuntime } from './ProviderModelRuntime.ets';
import { TASK_COMPRESS_MODEL_KEY, TASK_TITLE_MODEL_KEY, TASK_SUGGESTION_MODEL_KEY, createProviderModelRuntime } from './ProviderModelRuntime.ets';
import type { AppResources } from './AppResources.ets';
import { createAppResources } from './AppResources.ets';
import type { AgentTaskRuntime } from './AgentTaskRuntime.ets';
import { createAgentTaskRuntime } from './AgentTaskRuntime.ets';
import type { LocalExecutionResources } from './LocalExecutionResources.ets';
import { createLocalExecutionResources } from './LocalExecutionResources.ets';
import type { AgentSettingsRuntime } from './AgentSettingsRuntime.ets';
import { createAgentSettingsRuntime } from './AgentSettingsRuntime.ets';
import type { ToolCapabilityRuntime } from './ToolCapabilityRuntime.ets';
import { createToolCapabilityRuntime } from './ToolCapabilityRuntime.ets';
import type { CronRuntime } from './CronRuntime.ets';
import { createCronRuntime } from './CronRuntime.ets';
import type { SubAgentRuntime } from './SubAgentRuntime.ets';
import { createSubAgentRuntime } from './SubAgentRuntime.ets';
import type { DeepReadRuntime } from './DeepReadRuntime.ets';
import { createDeepReadRuntime } from './DeepReadRuntime.ets';
import type { CouncilRuntime } from './CouncilRuntime.ets';
import { createCouncilRuntime } from './CouncilRuntime.ets';
import type { NovelRuntime } from './NovelRuntime.ets';
import { createNovelRuntime } from './NovelRuntime.ets';
import type { ChatProviderRuntime, ChatRuntime } from './ChatRuntimeContracts.ets';
import { createEntryJevAutoApprovalReview } from '../platform_impl/JevAutoApprovalSupport.ets';
import type { HttpClient, Storage, AbortSignalLike, ModelCouncilTextRunner } from '@amber/deepread-domain';
import { initializeJevTriageHost } from '../platform_impl/JevTriageHost.ets';
import { getValidCodexToken } from '../platform_impl/CodexOAuthClient.ets';
import { getValidGrokToken } from '../platform_impl/GrokOAuthClient.ets';
import { prepareCouncilToolRunner } from '../platform_impl/CouncilRunPreparation.ets';
import { mutateAssistant } from '../platform_impl/AssistantMutation.ets';
import { createChatRdbRepository } from '../platform_impl/ChatRdbRepository.ets';
import { getMcpSettingsStore, initMcpSettingsStore } from '../platform_impl/McpSettingsStore.ets';
import { getMcpFilesStore } from '../platform_impl/McpFilesStore.ets';
import { configureEntryScopedRecipeLoop } from '../platform_impl/ScopedRecipeLoop.ets';
import { createEntryPluginAdapter } from '../platform_impl/EntryPluginRuntime.ets';
import { ensureSearchSdk } from '../platform_impl/SearchSdkInit.ets';
import { loadNovelResearchTools } from '../platform_impl/NovelResearchSupport.ets';
// The root wires concrete platform adapters. Each runtime owns its cached resources and lifecycle.
const resources: AppResources = createAppResources({
    initializeHost: (httpClient: HttpClient): void => {
        initializeJevTriageHost({ kv: (): KeyValueStore => resources.getChatKvStore(),
            loadConversation: (id: string): Promise<Conversation | null> => resources.getChatRepository().getById(id),
            mutateConversation: (id: string, update: (latest: Conversation) => Conversation | null | Promise<Conversation | null>): Promise<Conversation | null> => getConversationRunService().mutateStoredConversation(id, update),
            loadSettings: (): Promise<JevSettings> => loadJevSettings(resources.getChatKvStore()),
            evaluate: (state: JsonObject, questions: Record<string, JevQuestion>, signal?: AbortSignalLike): Promise<JevEvaluateResult> => jevEvaluate(state, questions, signal) });
    },
    ensureSearchSdk: ensureSearchSdk,
    createChatRdbRepository: createChatRdbRepository
});
const tasks: AgentTaskRuntime = createAgentTaskRuntime();
const localExecution: LocalExecutionResources = createLocalExecutionResources({
    getChatKvStore: resources.getChatKvStore,
    getAgentTaskStore: tasks.getAgentTaskStore
});
const providers: ProviderModelRuntime = createProviderModelRuntime({
    getChatKvStore: resources.getChatKvStore,
    getChatAssistants: resources.getChatAssistants,
    getHttpClient: (): HttpClient => resources.getAppContainer().httpClient,
    getStorage: (): Storage => resources.getAppContainer().storage,
    getValidCodexToken: getValidCodexToken,
    getValidGrokToken: getValidGrokToken,
    findModelByIdInProviders: findModelByIdInProviders
});
const agentSettings: AgentSettingsRuntime = createAgentSettingsRuntime({
    getChatKvStore: resources.getChatKvStore
});
const capabilities: ToolCapabilityRuntime = createToolCapabilityRuntime({
    getChatKvStore: resources.getChatKvStore,
    getChatRepository: resources.getChatRepository,
    getChatAssistants: resources.getChatAssistants,
    getToolActivityStore: resources.getToolActivityStore,
    getTerminalRuntime: localExecution.getTerminalRuntime,
    getMoshRuntime: localExecution.getMoshRuntime,
    getSSHProfileStore: localExecution.getSSHProfileStore,
    getPythonRuntime: localExecution.getPythonRuntime,
    resolveChatRuntime: (assistantId?: string, exactModelId?: string, exactProviderId?: string): Promise<ChatRuntime | null> => resolveChatRuntime(assistantId, exactModelId, exactProviderId),
    ensureSearchSdk: ensureSearchSdk,
    getMcpSettingsStore: getMcpSettingsStore,
    initMcpSettingsStore: initMcpSettingsStore,
    getMcpFilesStore: getMcpFilesStore,
    createWebMountTools: createWebMountTools
});
const cron: CronRuntime = createCronRuntime({
    getChatKvStore: resources.getChatKvStore,
    getChatRepository: resources.getChatRepository,
    resolveChatRuntime: (assistantId?: string): Promise<ChatRuntime | null> => resolveChatRuntime(assistantId),
    getWorkspaceManager: capabilities.getWorkspaceManager,
    getToolActivityStore: resources.getToolActivityStore,
    ensureSearchSdk: ensureSearchSdk,
    initMcpManager: capabilities.initMcpManager,
    getMcpManager: capabilities.getMcpManager,
    getAgentTaskStore: tasks.getAgentTaskStore
});
const subAgents: SubAgentRuntime = createSubAgentRuntime({
    getChatKvStore: resources.getChatKvStore,
    resolveChatProviderChoice: providers.resolveChatProviderChoice,
    resolveTaskChatModelChoice: providers.resolveTaskChatModelChoice,
    getChatAssistants: resources.getChatAssistants,
    buildProviderApi: providers.buildProviderApi,
    prepareChoiceAuth: providers.prepareChoiceAuth,
    jevChoiceSelect: jevChoiceSelect,
    configureRecipeLoop: async (loop: ToolLoopOptions, request: SubAgentGenerationRequest, parentConversationId: string | null): Promise<void> => {
        const review = createEntryJevAutoApprovalReview(request.messages);
        loop.dispatcher = new AgentToolDispatcher({ autoApprovalReview: review });
        if (!loop.tools.some((tool: AgentTool): boolean => tool.name.startsWith('recipe_') || tool.name === 'recipes_list' || tool.name.startsWith('plugin_') || tool.name === 'plugins_list'))
            return;
        const hasTerminal: boolean = loop.tools.some((tool: AgentTool): boolean => tool.name.startsWith('terminal_'));
        const guard: TerminalApprovalGuard | null = hasTerminal ? createTerminalApprovalGuard(await getTerminalRuntime(), await getSSHProfileStore(), await getMoshRuntime()) : null;
        const pluginStore: PluginStore = await getPluginStore();
        await configureEntryScopedRecipeLoop(loop, await getRecipeStore(), guard, { store: pluginStore,
            createAdapter: (installed: InstalledPlugin[], primitives: AgentTool[]): PluginLoopAdapter => createEntryPluginAdapter(pluginStore, installed, primitives, parentConversationId) }, review);
    },
    hydrateSubAgentSetting: agentSettings.hydrateSubAgentSetting,
    getHydratedSubAgentSetting: agentSettings.getHydratedSubAgentSetting,
    getAgentTaskStore: tasks.getAgentTaskStore,
    getSessionGrantStore: resources.getSessionGrantStore,
    isModelCouncilEnabled: agentSettings.isModelCouncilEnabled
});
const deepRead: DeepReadRuntime = createDeepReadRuntime({
    getHttpClient: (): HttpClient => resources.getAppContainer().httpClient,
    getStorage: (): Storage => resources.getAppContainer().storage,
    getPrefetcher: resources.getPrefetcher,
    getAiClient: resources.getAiClient,
    getNotifier: resources.getNotifier,
    getRepository: resources.getRepository,
    getChatKvStore: resources.getChatKvStore,
    getFileStore: resources.getFileStore,
    resolveDeepReadRuntimeCore: providers.resolveDeepReadRuntimeCore,
    ensureSearchSdk: ensureSearchSdk
});
const council: CouncilRuntime = createCouncilRuntime({
    getHttpClient: (): HttpClient => resources.getAppContainer().httpClient,
    getModelRegistry: resources.getModelRegistry,
    getFileStore: resources.getFileStore,
    prepareCouncilToolRunner: (): Promise<ModelCouncilTextRunner> => prepareCouncilToolRunner({
        http: getAppContainer().httpClient, files: getFileStore(), kv: getChatKvStore(),
        getAssistants: getChatAssistants, workspace: getWorkspaceManager(),
        getTerminalRuntime, getMoshRuntime, getSSHProfiles: getSSHProfileStore, getPythonRuntime,
        initMcp: initMcpManager, mcpManager: getMcpManager(), mcpSettings: getMcpSettingsStore(),
        skillManager: getSkillManager(), skillWorkspace: getSkillWorkspacePort(),
        setSkillEnabled: async (assistantId: string, name: string, enabled: boolean): Promise<void> => {
            await mutateAssistant(assistantId, (assistant: Assistant): Assistant => patchAssistant(assistant, {
                enabledSkills: enabled
                    ? (assistant.enabledSkills.indexOf(name) >= 0 ? assistant.enabledSkills : assistant.enabledSkills.concat([name]))
                    : assistant.enabledSkills.filter((item: string): boolean => item !== name),
            }));
        },
        memory: getMemoryToolDeps(new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date())),
        compactStore: getCompactStore(),
        loadCompressPrompt: async (): Promise<string> => (await loadTaskPrompts()).compressPrompt,
        repository: getChatRepository(), grantStore: getSessionGrantStore(),
        getRecipeStore, getPluginStore, getAgentTaskTools, getAgentCronTools,
        getPermissionsStatusTool, getAgentPromptConfigTool, playbookRepository: getDeepReadPlaybookRepository(),
    })
});
const novel: NovelRuntime = createNovelRuntime({
    getFileStore: resources.getFileStore,
    getChatKvStore: resources.getChatKvStore,
    resolveChatRuntime: (assistantId?: string, exactModelId?: string, exactProviderId?: string): Promise<ChatRuntime | null> => resolveChatRuntime(assistantId, exactModelId, exactProviderId),
    buildProviderApi: providers.buildProviderApi,
    loadNovelResearchTools: loadNovelResearchTools
});
export const getAppContainer = resources.getAppContainer;
export const getNotifier = resources.getNotifier;
export const getPrefetcher = resources.getPrefetcher;
export const getAiClient = resources.getAiClient;
export const getRepository = resources.getRepository;
export const getFileStore = resources.getFileStore;
export const getModelRegistry = resources.getModelRegistry;
export const getChatKvStore = resources.getChatKvStore;
export const getChatRepository = resources.getChatRepository;
export const getCompactStore = resources.getCompactStore;
export const getSessionGrantStore = resources.getSessionGrantStore;
export const getToolActivityStore = resources.getToolActivityStore;
export const getChatAssistants = resources.getChatAssistants;
export const getAgentTaskStore = tasks.getAgentTaskStore;
export const getAgentTaskScheduler = tasks.getAgentTaskScheduler;
export const getAgentTaskTools = tasks.getAgentTaskTools;
export const getSSHProfileStore = localExecution.getSSHProfileStore;
export const getTerminalRuntime = localExecution.getTerminalRuntime;
export const getPythonRuntime = localExecution.getPythonRuntime;
export const getMoshRuntime = localExecution.getMoshRuntime;
export const interruptTerminalForBackground = localExecution.interruptTerminalForBackground;
export const getChatModelSelection = providers.getChatModelSelection;
export const saveChatModelSelection = providers.saveChatModelSelection;
export const resolveChatProviderChoice = providers.resolveChatProviderChoice;
export const readTaskModelReference = providers.readTaskModelReference;
export const saveTaskModelPair = providers.saveTaskModelPair;
export const loadTaskModelReferences = providers.loadTaskModelReferences;
export const loadTaskModelIds = providers.loadTaskModelIds;
export const saveTaskModelId = providers.saveTaskModelId;
export const loadTaskPrompts = providers.loadTaskPrompts;
export const saveTaskPrompt = providers.saveTaskPrompt;
export const loadDeepReadModelOptions = providers.loadDeepReadModelOptions;
export const loadNovelModelOptions = providers.loadNovelModelOptions;
export const resolveTaskChatModelChoice = providers.resolveTaskChatModelChoice;
export const resolveConfiguredTaskModel = providers.resolveConfiguredTaskModel;
export const resolveTaskModelReference = providers.resolveTaskModelReference;
export const resolveTaskModelReferenceChain = providers.resolveTaskModelReferenceChain;
export const taskModelReferenceForChoice = providers.taskModelReferenceForChoice;
export const currentChatModelReference = providers.currentChatModelReference;
export const resolveTaskModelChain = providers.resolveTaskModelChain;
export const currentChatModelUuid = providers.currentChatModelUuid;
export const describeChatModel = providers.describeChatModel;
export const buildProviderApi = providers.buildProviderApi;
export const prepareProviderApi = providers.prepareProviderApi;
export const getAgentPromptConfigRepository = agentSettings.getAgentPromptConfigRepository;
export const getAgentPromptConfigTool = agentSettings.getAgentPromptConfigTool;
export const getPermissionBroker = agentSettings.getPermissionBroker;
export const getPermissionsStatusTool = agentSettings.getPermissionsStatusTool;
export const getAgentPromptSettingsPort = agentSettings.getAgentPromptSettingsPort;
export const hydrateSubAgentSetting = agentSettings.hydrateSubAgentSetting;
export const refreshSubAgentSetting = agentSettings.refreshSubAgentSetting;
export const isModelCouncilEnabled = agentSettings.isModelCouncilEnabled;
export const getMcpManager = capabilities.getMcpManager;
export const initMcpManager = capabilities.initMcpManager;
export const getSkillManager = capabilities.getSkillManager;
export const getSkillWorkspacePort = capabilities.getSkillWorkspacePort;
export const getWorkspaceManager = capabilities.getWorkspaceManager;
export const resetWorkspaceManager = capabilities.resetWorkspaceManager;
export const getRecipeStore = capabilities.getRecipeStore;
export const getRecipePrimitives = capabilities.getRecipePrimitives;
export const startRecipeFromPage = capabilities.startRecipeFromPage;
export const getPluginStore = capabilities.getPluginStore;
export const getPluginPrimitives = capabilities.getPluginPrimitives;
export const startPluginFromPage = capabilities.startPluginFromPage;
export const exportPluginFromPage = capabilities.exportPluginFromPage;
export const getAgentCronManager = cron.getAgentCronManager;
export const getAgentCronTools = cron.getAgentCronTools;
export const isAgentCronExecutionAvailable = cron.isAgentCronExecutionAvailable;
export const rescheduleCronTasksOnStartup = cron.rescheduleCronTasksOnStartup;
export const getSubAgentManager = subAgents.getSubAgentManager;
export const getSubAgentTools = subAgents.getSubAgentTools;
export const getDeepReadArtifactStore = deepRead.getDeepReadArtifactStore;
export const getDeepReadTextRuntime = deepRead.getDeepReadTextRuntime;
export const getDeepReadScheduler = deepRead.getDeepReadScheduler;
export const getDeepReadPlaybookRepository = deepRead.getDeepReadPlaybookRepository;
export const getCouncilRunner = council.getCouncilRunner;
export const prepareCouncilManager = council.prepareCouncilManager;
export const getCouncilManager = council.getCouncilManager;
export const getNovelProjectRepository = novel.getNovelProjectRepository;
export const getNovelWorkspaceService = novel.getNovelWorkspaceService;
export const getNovelCreation = novel.getNovelCreation;
export type { ChatProviderChoice, ChatProviderRuntime, ChatRuntime, SubAgentToolsContext, TaskPrompts } from './ChatRuntimeContracts.ets';
export type { ChatModelSelection } from './ProviderModelRuntime.ets';
export { TASK_TITLE_MODEL_KEY } from './ProviderModelRuntime.ets';
export { TASK_SUGGESTION_MODEL_KEY } from './ProviderModelRuntime.ets';
export { TASK_COMPRESS_MODEL_KEY } from './ProviderModelRuntime.ets';
export { TASK_IMAGE_MODEL_KEY } from './ProviderModelRuntime.ets';
export { TASK_DEEPREAD_MODEL_KEY } from './ProviderModelRuntime.ets';
export type { TaskModelIds } from './ProviderModelRuntime.ets';
export type { TaskModelReferences } from './ProviderModelRuntime.ets';
export { TASK_TITLE_PROMPT_KEY } from './ProviderModelRuntime.ets';
export { TASK_SUGGESTION_PROMPT_KEY } from './ProviderModelRuntime.ets';
export { TASK_COMPRESS_PROMPT_KEY } from './ProviderModelRuntime.ets';
export type { NovelModelOption } from './ProviderModelRuntime.ets';
export type { DeepReadModelOption } from './ProviderModelRuntime.ets';
export type { DeepReadTextRuntime } from './DeepReadRuntime.ets';
const executionResources: ChatExecutionResources = {
    getTerminalRuntime, getMoshRuntime, getPythonRuntime, getSSHProfileStore,
    listSkills: (): SkillMetadata[] => getSkillManager().listSkills(),
};
const createConversationExecution = (): ConversationRunExecution => {
    const preparation: ConversationPreparationPorts = {
        kv: getChatKvStore(), resolveProviderRuntime: providers.resolveChatProviderRuntime,
        getTerminalRuntime, getMoshRuntime, getPythonRuntime, getSSHProfileStore,
        listSkills: executionResources.listSkills,
        ensureSearchSdk, initializeMcp: initMcpManager,
        readCompressReference: (): Promise<TaskModelReference> => readTaskModelReference(TASK_COMPRESS_MODEL_KEY),
        resolveTaskChoice: resolveTaskModelReference, loadTaskPrompts, loadOcrSeed, hydrateSubAgentSetting,
        imageGenAvailable: imageGenToolAvailable, loadAgentTaskTools: getAgentTaskTools, loadAgentCronTools: getAgentCronTools,
    };
    const turnPorts: ConversationTurnPorts = {
        repository: getChatRepository(), compactStore: getCompactStore(),
        memoryRepository: getMemoryReadRepository(), memoryLibrary: getMemoryLibraryOps(), memoryTopics: getMemoryTopicStoreOps(),
        rerankContext: jevNoulBatch, loadTaskPrompts, prepareProviderApi, beginJevContextRun,
        loadMiniApp: (id: string): Promise<MiniAppRecord | null> => getMiniAppPlatform().then((platform: MiniAppPlatform): Promise<MiniAppRecord | null> => platform.repository.getById(id)),
        miniAppRepository: getMiniAppRepositorySync(), createDocumentReader: createEntryDocumentReader,
        createPlaceholderValues: createEntryPlaceholderValues, createOcrTransformer: createEntryOcrTransformer,
        createBase64ImageTransformer: createEntryBase64ImageTransformer,
    };
    const auxiliary: ConversationAuxiliaryPorts = {
        loadTaskPrompts,
        readTitleReference: (): Promise<TaskModelReference> => readTaskModelReference(TASK_TITLE_MODEL_KEY),
        readSuggestionReference: (): Promise<TaskModelReference> => readTaskModelReference(TASK_SUGGESTION_MODEL_KEY),
        readCompressReference: (): Promise<TaskModelReference> => readTaskModelReference(TASK_COMPRESS_MODEL_KEY),
        resolveTaskChoice: resolveTaskModelReference, resolveTaskChoiceChain: resolveTaskModelReferenceChain,
        referenceForChoice: taskModelReferenceForChoice, prepareProviderApi,
        extractionStore: getMemoryExtractionStoreOps(), memoryRepository: getMemoryReadRepository(),
        canCommitExtraction: canCommitMemoryExtraction,
        requestRecap: async (id: string): Promise<string> => {
            const service = getConversationRecapService();
            await service.request(id);
            return service.state(id).error;
        }, notifyGenerationDone,
    };
    const tools: ConversationRunToolPorts = createConversationToolComposition({
        createEntryJevAutoApprovalReview: createEntryJevAutoApprovalReview,
        getAgentPromptConfigTool: getAgentPromptConfigTool,
        getChatAssistants: getChatAssistants,
        getChatKvStore: getChatKvStore,
        getChatRepository: getChatRepository,
        getCompactStore: getCompactStore,
        getDeepReadPlaybookRepository: getDeepReadPlaybookRepository,
        getMcpManager: getMcpManager,
        getPermissionsStatusTool: getPermissionsStatusTool,
        getPluginStore: getPluginStore,
        getRecipeStore: getRecipeStore,
        getSessionGrantStore: getSessionGrantStore,
        getSkillManager: getSkillManager,
        getSkillWorkspacePort: getSkillWorkspacePort,
        getSubAgentTools: getSubAgentTools,
        getWorkspaceManager: getWorkspaceManager,
        mutateAssistant: mutateAssistant,
        getChatCouncilTools: getChatCouncilTools,
        createEntryModelSlotPort: createEntryModelSlotPort,
        createEntryWeatherPort: createEntryWeatherPort,
        createWebMountParityTools: createWebMountParityTools,
        createFeishuDocsTools: createFeishuDocsTools,
        createImageGenTool: createImageGenTool,
        createJevStatusTool: createJevStatusTool,
        jevToolSearchReranker: jevToolSearchReranker,
        getMcpSettingsStore: getMcpSettingsStore,
        createSoulTools: createSoulTools,
        createWebMountTools: createWebMountTools,
        guardPage: guardPage,
        createWebMountAdvancedTools: createWebMountAdvancedTools,
        getHttpClient: (): HttpClient => resources.getAppContainer().httpClient,
        compressProvider: (seed: PreparedConversationSeed): ChatStreamProvider => buildConversationCompressProvider(seed, turnPorts)
    });
    return {
        prepareSeed: (conversation: Conversation): Promise<PreparedConversationSeed> => prepareConversationSeed(conversation, preparation),
        buildTurnDeps: (ctx: DetachedConversationContext): ChatTurnDeps => buildConversationTurnDeps(ctx, turnPorts),
        buildToolLoop: (ctx: DetachedConversationContext): Promise<ToolLoopOptions> => buildConversationToolLoop(ctx, tools),
        completeAuxiliary: (ctx: DetachedConversationContext, finalized: Conversation, manualToolCallId: string): void => completeConversationAuxiliary(ctx, finalized, manualToolCallId, auxiliary),
        compactConversation: compactConversationNow, evaluateCompletion: evaluateJevCompletion,
        scheduleApprovalTriage: scheduleJevApprovalTriage, cancelApprovalTriage: cancelJevApprovalTriage,
        materializeDocumentParts, materializeConversationDocuments,
    };
};
let conversationRunService: ConversationRunService | null = null;
export const getConversationRunService = (): ConversationRunService => {
    if (conversationRunService === null)
        conversationRunService = new ConversationRunService({ repository: getChatRepository(), kv: getChatKvStore(), compactStore: getCompactStore(), loadAssistants: getChatAssistants }, createConversationExecution());
    return conversationRunService;
};
export const resolveChatRuntime = async (assistantId?: string, exactModelId?: string, exactProviderId?: string): Promise<ChatRuntime | null> => {
    const runtime: ChatProviderRuntime | null = await providers.resolveChatProviderRuntime(assistantId, exactModelId, exactProviderId);
    return runtime === null ? null : prepareChatRuntime(runtime, executionResources);
};

// Builds platform capabilities for a captured run; live catalog refresh remains in ConversationRunTools.
import type { SubAgentToolsContext, ChatRuntime } from './ChatRuntimeContracts.ets';
import type { McpSettingsPort, AgentTool, AgentToolActivityStore, Assistant, ChatStreamProvider, Conversation, DeepReadOpenEvent, LocalToolOption, McpToolActivityPort, PendingUserMessage, TerminalApprovalGuard, TextGenerationParams, UIMessage, UIMessagePart, UIMessagePartImage, JsonObject, JevWebCandidate, MessageChunk, WebMountGoalCandidate, WebMountGoalDecision, WebMountGoalHostPorts, WebMountGoalRequest, WebMountObservation, AutoApprovalReview, ConversationRepository, KeyValueStore, CompactStore, SkillWorkspacePort, SessionAccessGrantStore, McpManager, SkillManager, PosixWorkspaceManager, RecipeStore, PluginStore, ModelSlotPort, WeatherPort, ToolSearchReranker } from '@amber/chat-domain';
import { asChatStreamProvider, createClipboardTool, createConversationContextTools, createConversationHistoryTools, createConversationQueueTools, createDeepReadOpenTool, createDeepReadPlaybookTools, createHealthSummaryTool, createPersonalContextTools, createWeatherReadTool, createDeviceScheduleTools, createMcpManagementTools, createMcpServerTools, createMemoryTools, createRunPlanUpdateTool, createSearchTools, createSkillTools, createTerminalApprovalGuard, createProviderManagementTools, PROVIDERS_KEY, createModelSlotTool, createMcpDescribeTool, currentMessages, filterToolProfile, makeCompactPolicy, makeTextGenerationParams, patchAssistant, createWebMountGoalAdapter, createWebMountGoalTool, makeSystemMessage, makeUserMessage, toText } from '@amber/chat-domain';
import type { WebMountParityToolsDeps } from '../platform_impl/WebMountParityTools.ets';
import type { WebMountAdvancedToolsDeps } from '../platform_impl/WebMountAdvancedTools.ets';
import type { ImageGenToolDeps, EditSourceImage } from '../platform_impl/ImageGenTool.ets';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { AbortSignalLike, HttpClient, DeepReadPlaybookRepository } from '@amber/deepread-domain';
import { systemLanguage } from '../platform_impl/AgentRuntimePrefs.ets';
import { sharedKvMutex } from '../platform_impl/AtomicJsonFile.ets';
import { createAssistantLocalPrimitives } from '../platform_impl/AssistantLocalPrimitives.ets';
import { guardConversationBrowserTools, assertConversationBrowserOwner, conversationBrowserUnavailableReason } from '../platform_impl/ConversationBrowserGuard.ets';
import { loadWebMountSites, siteAllowsUrl } from '../platform_impl/WebMountSites.ets';
import type { DetachedConversationContext, PreparedConversationSeed } from '../platform_impl/ConversationRunService.ets';
import { inflateRawEntry } from '../platform_impl/EntryDocumentParser.ets';
import { createEntryClipboardPort, sha256HexUtf8 } from '../platform_impl/EntryLocalToolPorts.ets';
import { createEntryPersonalContextPort } from '../platform_impl/PersonalContextPort.ets';
import { createEntryDeviceSchedulePort } from '../platform_impl/DeviceSchedulePort.ets';
import type { RunToolCatalogSnapshot } from '../platform_impl/RunToolCatalog.ets';
import { createRunToolCatalog } from '../platform_impl/RunToolCatalog.ets';
import { createEntryHealthReadPort } from '../platform_impl/HealthReadPort.ets';
import { getMemoryToolDeps } from '../platform_impl/MemoryStore.ets';
import type { WebMountSession } from '../platform_impl/WebMountSession.ets';
import { getWebMountSession } from '../platform_impl/WebMountSession.ets';
import { chooseJevWebCandidate } from '../platform_impl/JevWebDecision.ets';
import type { ConversationRunToolPorts, PreparedConversationTools } from '../platform_impl/ConversationRunPorts.ets';
export interface ConversationToolCompositionPorts {
    createEntryJevAutoApprovalReview: (messages: UIMessage[], currentMessagesProvider?: () => UIMessage[]) => AutoApprovalReview;
    getAgentPromptConfigTool: () => AgentTool;
    getChatAssistants: () => Promise<Assistant[]>;
    getChatKvStore: () => KeyValueStore;
    getChatRepository: () => ConversationRepository;
    getCompactStore: () => CompactStore;
    getDeepReadPlaybookRepository: () => DeepReadPlaybookRepository;
    getMcpManager: () => McpManager;
    getPermissionsStatusTool: () => AgentTool;
    getPluginStore: () => Promise<PluginStore>;
    getRecipeStore: () => Promise<RecipeStore>;
    getSessionGrantStore: () => SessionAccessGrantStore;
    getSkillManager: () => SkillManager;
    getSkillWorkspacePort: () => SkillWorkspacePort;
    getSubAgentTools: (ctx: SubAgentToolsContext) => Promise<AgentTool[]>;
    getWorkspaceManager: () => PosixWorkspaceManager;
    mutateAssistant: (assistantId: string, mutator: (current: Assistant) => Assistant) => Promise<Assistant | null>;
    getChatCouncilTools: () => Promise<AgentTool[]>;
    createEntryModelSlotPort: (assistantId: string) => ModelSlotPort;
    createEntryWeatherPort: (geocoderLocale: string) => WeatherPort;
    createWebMountParityTools: (deps: WebMountParityToolsDeps) => AgentTool[];
    createFeishuDocsTools: (assistantEnabled?: boolean) => AgentTool[];
    createImageGenTool: (deps: ImageGenToolDeps) => AgentTool;
    createJevStatusTool: () => AgentTool;
    jevToolSearchReranker: () => ToolSearchReranker;
    getMcpSettingsStore: () => McpSettingsPort;
    createSoulTools: () => AgentTool[];
    createWebMountTools: (session: WebMountSession, includeEval: boolean, assistantEnabled?: boolean) => AgentTool[];
    guardPage: (session: WebMountSession, kv: KeyValueStore, assistantEnabled: boolean, mutating?: boolean) => Promise<string | null>;
    createWebMountAdvancedTools: (deps: WebMountAdvancedToolsDeps) => AgentTool[];
    getHttpClient: () => HttpClient;
    compressProvider: (seed: PreparedConversationSeed) => ChatStreamProvider;
}
export const createConversationToolComposition = (ports: ConversationToolCompositionPorts): ConversationRunToolPorts => {
    // E12(goal):主模型选择器 — 同一 parent runtime API/身份,只许选有限候选 ID 或 handback
    const runMainModelGoalChoice = async (rt: ChatRuntime, request: WebMountGoalRequest, observation: WebMountObservation, candidates: WebMountGoalCandidate[], signal?: AbortSignalLike): Promise<WebMountGoalDecision> => {
        const lines: string[] = candidates.map((candidate: WebMountGoalCandidate): string => `${candidate.id} [${candidate.kind}] ${candidate.description}`);
        const systemPrompt: string = 'You are selecting exactly one next browser action for a bounded goal. '
            + 'Answer with exactly one candidate id from the list, or the single word handback. Never invent new ids.';
        const userText: string = [
            `Goal: ${request.goal}`,
            `Completion signal (matched against URL/title/visible text): ${request.completionText}`,
            `Page: ${observation.title} — ${observation.url}`,
            `Visible summary: ${observation.text.substring(0, 600)}`,
            'Candidates:',
            ...lines,
        ].join('\n');
        const provider: ChatStreamProvider = asChatStreamProvider(rt.api, (): TextGenerationParams => makeTextGenerationParams({
            model: rt.params.model, temperature: 0, maxTokens: 64, tools: [],
        }));
        const prompt: UIMessage[] = [makeSystemMessage(systemPrompt), makeUserMessage(userText)];
        let answer: string = '';
        if (provider.generateText !== undefined) {
            const chunk: MessageChunk = await provider.generateText(prompt, { signal: signal });
            const choice = chunk.choices[0];
            answer = choice !== undefined
                ? (choice.message !== null ? toText(choice.message) : choice.delta !== null ? toText(choice.delta) : '') : '';
        }
        else {
            let latest: string = '';
            await provider.streamText(prompt, (chunk: MessageChunk): void => {
                const choice = chunk.choices[0];
                if (choice !== undefined && choice.delta !== null)
                    latest = toText(choice.delta);
            }, { signal: signal });
            answer = latest;
        }
        const trimmed: string = answer.trim();
        if (/^cand-\d+$/.test(trimmed) && candidates.some((candidate: WebMountGoalCandidate): boolean => candidate.id === trimmed)) {
            return { kind: 'action', candidateId: trimmed, reason: 'main_model_choice' };
        }
        return { kind: 'handback', candidateId: null,
            reason: trimmed === 'handback' ? 'main_model_handback' : 'invalid_decision' };
    };
    const mcpActivityPort = (store: AgentToolActivityStore): McpToolActivityPort => ({
        startTool: (toolName: string, title: string, inputPreview: string, runtime: string): string => store.startTool(toolName, title, inputPreview, runtime),
        complete: (toolCallId: string, outputPreview: string): void => {
            store.complete(toolCallId, outputPreview);
        },
        fail: (toolCallId: string, error: Error): void => {
            store.fail(toolCallId, error);
        },
    });
    const setSkillEnabled = async (rt: ChatRuntime, name: string, enable: boolean): Promise<void> => {
        await ports.mutateAssistant(rt.assistant.id, (assistant: Assistant): Assistant => {
            const skills: string[] = enable
                ? (assistant.enabledSkills.indexOf(name) >= 0
                    ? assistant.enabledSkills : assistant.enabledSkills.concat([name]))
                : assistant.enabledSkills.filter((item: string): boolean => item !== name);
            return patchAssistant(assistant, { enabledSkills: skills });
        });
    };
    const isAllowedEditMime = (mime: string): boolean => mime === 'image/png' || mime === 'image/jpeg' || mime === 'image/webp';
    const editMimeExt = (mime: string): string => mime === 'image/jpeg' ? 'jpg' : (mime === 'image/webp' ? 'webp' : 'png');
    const editMimeByName = (name: string): string => {
        const dot: number = name.lastIndexOf('.');
        if (dot < 0)
            return 'application/octet-stream';
        const ext: string = name.substring(dot + 1).toLowerCase();
        if (ext === 'png')
            return 'image/png';
        if (ext === 'jpg' || ext === 'jpeg')
            return 'image/jpeg';
        if (ext === 'webp')
            return 'image/webp';
        return 'application/octet-stream';
    };
    const readUriBytes = (uri: string): Uint8Array => {
        const file: fileIo.File = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
        try {
            const stat: fileIo.Stat = fileIo.statSync(file.fd);
            if (stat.size > 10 * 1024 * 1024)
                throw new Error(`文件超过 10MB: ${stat.size} bytes`);
            const buffer: ArrayBuffer = new ArrayBuffer(stat.size);
            fileIo.readSync(file.fd, buffer, { offset: 0, length: stat.size });
            return new Uint8Array(buffer);
        }
        finally {
            fileIo.closeSync(file);
        }
    };
    const loadEditSourceBytes = async (url: string): Promise<EditSourceImage | null> => {
        try {
            if (url.startsWith('data:')) {
                const comma: number = url.indexOf(',');
                if (comma < 0)
                    return null;
                const mime: string = url.substring(5, comma).split(';')[0];
                if (!isAllowedEditMime(mime))
                    return null;
                const bytes: Uint8Array = new util.Base64Helper().decodeSync(url.substring(comma + 1));
                return { bytes, mime, name: `source.${editMimeExt(mime)}` };
            }
            if (url.startsWith('file://')) {
                const mime: string = editMimeByName(url);
                if (!isAllowedEditMime(mime))
                    return null;
                const bytes: Uint8Array = readUriBytes(url);
                const name: string = url.substring(url.lastIndexOf('/') + 1);
                return { bytes, mime, name: name.length > 0 ? name : `source.${editMimeExt(mime)}` };
            }
            return null;
        }
        catch {
            return null;
        }
    };
    const resolveEditSourceImage = async (ctx: DetachedConversationContext): Promise<EditSourceImage | null> => {
        const messages: UIMessage[] = currentMessages(ctx.activeConversation());
        for (let index: number = messages.length - 1; index >= 0; index--) {
            if (messages[index].role !== 'user')
                continue;
            for (const part of messages[index].parts) {
                if (part.type !== 'image')
                    continue;
                const resolved: EditSourceImage | null = await loadEditSourceBytes((part as UIMessagePartImage).url);
                if (resolved !== null)
                    return resolved;
            }
        }
        return null;
    };
    const warnIfToolAbilityMissing = (ctx: DetachedConversationContext): void => {
        const rt: ChatRuntime = ctx.seed.runtime;
        if (rt.modelAbilities.indexOf('tool') >= 0)
            return;
        if (!ctx.seed.searchSettings.enableWebSearch
            && ports.getMcpManager().getAllAvailableTools([...rt.assistant.mcpServers]).length === 0)
            return;
        ctx.publish({ errorMsg: '当前模型未开启工具调用，请在设置内启用模型的工具能力，再使用搜索/MCP等功能' });
    };
    const prepareTools = async (ctx: DetachedConversationContext, compressProvider: ChatStreamProvider): Promise<PreparedConversationTools> => {
        const seed: PreparedConversationSeed = ctx.seed;
        const rt: ChatRuntime = seed.runtime;
        const conversation: Conversation = ctx.activeConversation();
        const conversationProvider: () => Promise<Conversation> = (): Promise<Conversation> => Promise.resolve(ctx.activeConversation());
        const localOptions: LocalToolOption[] = rt.assistant.localTools;
        const terminalGuard: TerminalApprovalGuard | null = localOptions.indexOf('terminal') >= 0
            && rt.terminalRuntime !== null && rt.sshProfiles !== null
            ? createTerminalApprovalGuard(rt.terminalRuntime, rt.sshProfiles, rt.moshRuntime ?? undefined) : null;
        const webmountEnabled: boolean = seed.webmountGlobalEnabled || localOptions.indexOf('webmount') >= 0;
        const runtime = seed.agentRuntime;
        const compact = runtime.contextCompaction;
        // E12:浏览器 owner 断言惰性求值 — 循环构建时该会话未必持有可见页面;
        //   首次实际页面操作时绑定租约,离页后租约失效即拒绝(E6 语义保持)
        const ownerAssertion = (() => {
            let cached: (() => void) | null = null;
            return (): void => {
                if (cached === null)
                    cached = assertConversationBrowserOwner(ctx.conversationId);
                cached();
            };
        })();
        const goalHostPorts: WebMountGoalHostPorts = {
            observe: (signal?: AbortSignalLike): Promise<WebMountObservation> => getWebMountSession().observeDocument(signal),
            assertUsable: async (): Promise<void> => {
                ownerAssertion();
                const blocked: string | null = await ports.guardPage(getWebMountSession(), ports.getChatKvStore(), localOptions.indexOf('webmount') >= 0, true);
                if (blocked !== null)
                    throw new Error(blocked);
            },
            choose: async (request: WebMountGoalRequest, observation: WebMountObservation, candidates: WebMountGoalCandidate[], signal?: AbortSignalLike): Promise<WebMountGoalDecision> => {
                if (request.decisionSource === 'jev') {
                    // E11 有限候选接缝:OFF/SHADOW 即 handback,不自动降级主模型
                    const jevCandidates: JevWebCandidate[] = candidates.map((candidate: WebMountGoalCandidate): JevWebCandidate => ({
                        id: candidate.id,
                        toolName: candidate.part.toolName,
                        input: JSON.parse(candidate.part.input) as JsonObject,
                        description: candidate.description,
                    }));
                    const jev = await chooseJevWebCandidate({
                        sessionId: observation.token.sessionId,
                        revision: `${observation.token.documentId}:${observation.token.revision}`,
                        pageSummary: `${observation.title} ${observation.text.substring(0, 500)}`.substring(0, 600),
                    }, jevCandidates, signal);
                    return { kind: jev.kind === 'action' ? 'action' : 'handback',
                        candidateId: jev.candidateId, reason: jev.reason };
                }
                return runMainModelGoalChoice(rt, request, observation, candidates, signal);
            },
            now: (): number => Date.now(),
        };
        const goalAdapter = createWebMountGoalAdapter(goalHostPorts);
        const councilTools: AgentTool[] = await ports.getChatCouncilTools();
        const browserFallback = async (url: string): Promise<string | null> => {
            if (!ctx.baseTools.some((tool: AgentTool): boolean => tool.name === 'wm_open')) {
                return 'The current tool profile does not provide wm_open.';
            }
            const unavailable: string | null = conversationBrowserUnavailableReason(ctx.conversationId);
            if (unavailable !== null) return unavailable;
            const kv: KeyValueStore = ports.getChatKvStore();
            if (localOptions.indexOf('webmount') < 0 && await kv.get('webmount_global_enabled') !== 'true') {
                return 'WebMount is disabled. Enable it in browser settings to use this fallback.';
            }
            const sites = await loadWebMountSites(kv);
            const ownerUnavailable: string | null = conversationBrowserUnavailableReason(ctx.conversationId);
            if (ownerUnavailable !== null) return ownerUnavailable;
            return siteAllowsUrl(sites, url) ? null
                : 'This search site is not registered in WebMount settings. The link can be opened manually.';
        };
        const profiledTools: AgentTool[] = guardConversationBrowserTools(filterToolProfile([
            ...councilTools,
            ...createAssistantLocalPrimitives({ assistant: rt.assistant, conversationId: ctx.conversationId,
                workspace: ports.getWorkspaceManager(), activityStore: ctx.activityStore,
                terminalRuntime: rt.terminalRuntime, moshRuntime: rt.moshRuntime,
                sshProfiles: rt.sshProfiles, pythonRuntime: rt.pythonRuntime }),
            ...(webmountEnabled ? ports.createWebMountTools(getWebMountSession(), seed.webmountEvalEnabled || localOptions.indexOf('webmount_eval') >= 0, localOptions.indexOf('webmount') >= 0) : []),
            ...(webmountEnabled ? ports.createWebMountAdvancedTools({
                session: getWebMountSession(),
                assistantEnabled: localOptions.indexOf('webmount') >= 0,
                assertOwner: ownerAssertion,
            }) : []),
            ...(webmountEnabled ? ports.createWebMountParityTools({
                session: getWebMountSession(),
                assistantEnabled: localOptions.indexOf('webmount') >= 0,
                assertOwner: ownerAssertion,
                currentChoice: rt.resolvedChoice,
                currentModelSupportsImage: rt.modelSupportsImageInput,
            }) : []),
            ...(webmountEnabled ? [createWebMountGoalTool()] : []),
            ...(webmountEnabled ? ports.createFeishuDocsTools(localOptions.indexOf('webmount') >= 0) : []),
            ...createMcpManagementTools({ settings: ports.getMcpSettingsStore(), manager: ports.getMcpManager(),
                skills: ports.getSkillManager(), assistantIds: [...rt.assistant.mcpServers] }),
            ...createProviderManagementTools({ store: ports.getChatKvStore(), http: ports.getHttpClient(),
                withLock: (action: () => Promise<UIMessagePart[]>): Promise<UIMessagePart[]> => sharedKvMutex.withLock(PROVIDERS_KEY, action) }),
            createModelSlotTool({ port: ports.createEntryModelSlotPort(rt.assistant.id) }),
            createMcpDescribeTool({ settings: ports.getMcpSettingsStore(), assistantIds: [...rt.assistant.mcpServers] }),
            ...createMcpServerTools({ manager: ports.getMcpManager(), activity: mcpActivityPort(ctx.activityStore),
                assistantIds: [...rt.assistant.mcpServers] }),
            ...((runtime.enableCoreMemory || runtime.enableShortTermMemory || runtime.enableLongTermMemory)
                ? createMemoryTools(getMemoryToolDeps(new Intl.DateTimeFormat(systemLanguage(), { dateStyle: 'medium' }).format(new Date()))) : []),
            ...createConversationContextTools({
                conversationProvider,
                compactStore: ports.getCompactStore(),
                policy: makeCompactPolicy({ enabled: compact.enabled, notifyOnly: compact.notifyOnly,
                    precompactRatio: compact.precompactRatio, forceRatio: compact.forceRatio,
                    keepRecentTurns: compact.keepRecentTurns, maxSummaryTokens: compact.maxSummaryTokens }),
                modelContextWindowTokens: rt.modelContextWindowTokens,
                compactEngineDeps: { provider: compressProvider, store: ports.getCompactStore(),
                    compressPrompt: seed.taskPrompts.compressPrompt },
            }),
            ...(seed.imageGenConfigured ? [ports.createImageGenTool({ conversationId: ctx.conversationId,
                    sourceImageResolver: (): Promise<EditSourceImage | null> => resolveEditSourceImage(ctx) })] : []),
            ...createConversationHistoryTools({ repository: ports.getChatRepository(),
                currentConversationProvider: conversationProvider, grantStore: ports.getSessionGrantStore() }),
            ...createConversationQueueTools({
                pendingProvider: (): Promise<PendingUserMessage[]> => Promise.resolve(ctx.pending()),
                cancelOne: (messageId: string): Promise<void> => ctx.cancelPending(messageId),
                clearAll: (): Promise<void> => ctx.clearPending(),
            }),
            ...seed.agentTaskTools,
            ports.getPermissionsStatusTool(), ports.createJevStatusTool(), ...seed.agentCronTools,
            createRunPlanUpdateTool(), createHealthSummaryTool(createEntryHealthReadPort()),
            ...ports.createSoulTools(),
            ...(localOptions.indexOf('clipboard') >= 0 ? [createClipboardTool(createEntryClipboardPort())] : []),
            ...createPersonalContextTools({
                port: createEntryPersonalContextPort(),
                // Only the Claude request path forwards image blocks inside tool results.
                includeImages: rt.modelSupportsImageInput && rt.resolvedChoice.provider.type === 'claude',
                writeWorkspaceBytes: async (path: string, bytes: Uint8Array, mime: string): Promise<string> => (await ports.getWorkspaceManager().writeBytes(path, bytes, mime)).path,
            }),
            createWeatherReadTool(ports.createEntryWeatherPort(systemLanguage()), { language: systemLanguage().startsWith('zh') ? 'zh' : 'en' }),
            ...createDeviceScheduleTools(createEntryDeviceSchedulePort()),
            ports.getAgentPromptConfigTool(), ...createDeepReadPlaybookTools(ports.getDeepReadPlaybookRepository()),
            createDeepReadOpenTool({
                bus: { hasCollectors: (): boolean => ctx.hasNavigationCollector(),
                    emit: (event: DeepReadOpenEvent): void => ctx.emitDeepRead(event) },
                sha256Hex: sha256HexUtf8,
            }),
            ...(seed.searchSettings.enableWebSearch ? createSearchTools(seed.searchSettings, { browserFallback }) : []),
        ], rt.assistant.toolProfile).tools, ctx.conversationId);
        return { profiledTools, terminalGuard, goalAdapter: webmountEnabled ? goalAdapter : undefined };
    };
    const loadSubAgentTools = (ctx: DetachedConversationContext): Promise<AgentTool[]> => ports.getSubAgentTools({ parentConversationId: ctx.conversationId, parentToolsProvider: (): AgentTool[] => ctx.baseTools, parentRuntime: ctx.seed.runtime, parentAgentRuntime: ctx.seed.agentRuntime });
    const createCurrentSkillTools = async (ctx: DetachedConversationContext): Promise<AgentTool[]> => {
        const rt: ChatRuntime = ctx.seed.runtime;
        const latestAssistant: Assistant | undefined = (await ports.getChatAssistants()).find((assistant: Assistant): boolean => assistant.id === rt.assistant.id);
        return createSkillTools({ enabledSkills: latestAssistant?.enabledSkills ?? [], allSkills: ports.getSkillManager().listSkills(), skillManager: ports.getSkillManager(), setSkillEnabled: (name: string, enable: boolean): Promise<void> => setSkillEnabled(rt, name, enable), workspace: ports.getSkillWorkspacePort(), inflateRaw: inflateRawEntry });
    };
    const createCatalog = async (ctx: DetachedConversationContext, primitives: AgentTool[]): Promise<RunToolCatalogSnapshot> => createRunToolCatalog({ primitives, profile: ctx.seed.runtime.assistant.toolProfile, conversationId: ctx.conversationId, recipeStore: await ports.getRecipeStore(), pluginStore: await ports.getPluginStore(), reranker: ports.jevToolSearchReranker() });
    const createAutoApprovalReview = (ctx: DetachedConversationContext): AutoApprovalReview => ports.createEntryJevAutoApprovalReview(currentMessages(ctx.activeConversation()), (): UIMessage[] => currentMessages(ctx.activeConversation()));
    return { prepareTools, loadSubAgentTools, createSkillTools: createCurrentSkillTools, createCatalog, warnIfToolAbilityMissing, createAutoApprovalReview, compressProvider: ports.compressProvider };
};

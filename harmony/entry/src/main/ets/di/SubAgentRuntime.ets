// Owns subagent lifecycle and isolated generation ports.
import type { KeyValueStore, Assistant, OpenAIChatApi, TextGenerationParams, ProviderSetting, ProviderModel, AgentTool, ChatStreamProvider, CustomHeader, SubAgentRuntimeSetting, SubAgentManagerDeps, SubAgentRunner, SubAgentGenerationRequest, SubAgentAssistantResolution, SubAgentGenerationPort, ProviderAuthSnapshot, NovelResponsesStreamOptions, ToolLoopOptions } from '@amber/chat-domain';
import { loadProviders, SessionAccessGrantStore, AgentTaskStore, asChatStreamProvider, makeGenerationRetrySetting, SubAgentManager, GenerationSubAgentRunner, ChatStreamSubAgentGenerationPort, SubAgentTools } from '@amber/chat-domain';
import { createEntrySubAgentTranscriptPort, subAgentTranscriptPathForRun } from '../platform_impl/SubAgentStore.ets';
import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import { loadAgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import { util } from '@kit.ArkTS';
import type { ChatProviderChoice, ChatRuntime, SubAgentToolsContext } from './ChatRuntimeContracts.ets';
import type { JevChoiceResult } from '../platform_impl/JevSupport.ets';
export interface SubAgentRuntimePorts {
    getChatKvStore: () => KeyValueStore;
    resolveChatProviderChoice: (assistant?: Assistant) => Promise<ChatProviderChoice | null>;
    resolveTaskChatModelChoice: (modelId: string, fallbackChoice?: ChatProviderChoice) => Promise<ChatProviderChoice | null>;
    getChatAssistants: () => Promise<Assistant[]>;
    buildProviderApi: (chosen: ProviderSetting, customHeaderRecord: Record<string, string>, oauthAccountId?: string, responses?: NovelResponsesStreamOptions) => OpenAIChatApi;
    prepareChoiceAuth: (provider: ProviderSetting) => Promise<ProviderAuthSnapshot>;
    jevChoiceSelect: (purpose: string, taskHint: string, candidates: Record<string, string>) => Promise<JevChoiceResult | null>;
    configureRecipeLoop: (loop: ToolLoopOptions, request: SubAgentGenerationRequest, conversationId: string | null) => Promise<void>;
    hydrateSubAgentSetting: () => Promise<SubAgentRuntimeSetting>;
    getHydratedSubAgentSetting: () => SubAgentRuntimeSetting;
    getAgentTaskStore: () => Promise<AgentTaskStore>;
    getSessionGrantStore: () => SessionAccessGrantStore;
    isModelCouncilEnabled: () => Promise<boolean>;
}
export interface SubAgentRuntime {
    getSubAgentManager: () => Promise<SubAgentManager>;
    getSubAgentTools: (ctx: SubAgentToolsContext) => Promise<AgentTool[]>;
    buildSubAgentGenerationPort: (parentRuntime?: ChatRuntime | null, parentAgentRuntime?: AgentRuntimeSnapshot | null, parentConversationId?: string | null) => SubAgentGenerationPort;
}
export const createSubAgentRuntime = (ports: SubAgentRuntimePorts): SubAgentRuntime => {
    // 子代理 generation port(SubAgentRunner.kt:52-55 model/assistant 解析):
    //   resolveModel = findModelById(modelId) ?: getCurrentChatModel(= resolveChatProviderChoice)
    //   resolveAssistant = getCurrentAssistant + agentRuntime generationRetry/autoApprove 快照
    //     (runner 内部 isolatedAssistant 应用隔离字段;此处只返回 parent + retry/flags)
    //   makeProvider = buildProviderApi + asChatStreamProvider(同步;provider 由
    //     resolveModel 阶段已解析并以 model.id 为键缓存,makeProvider 按 model.id 取)
    const buildSubAgentGenerationPort = (parentRuntime: ChatRuntime | null = null, parentAgentRuntime: AgentRuntimeSnapshot | null = null, parentConversationId: string | null = null): SubAgentGenerationPort => {
        // model.id → 已刷新的 provider/accountId(resolveModel 异步解析后缓存;makeProvider 同步取)
        //   并行子代理按各自 model.id 取 provider,不互相干扰;同 model → 同 provider(正确)
        const providerByModelId: Map<string, ProviderAuthSnapshot> = new Map<string, ProviderAuthSnapshot>();
        // D5-1:Jev 模型路由(无 override 且有任务上下文时):候选 = 当前聊天模型 +
        //   各启用服务商首个 chat 模型(≤6);ACTIVE 用选中,SHADOW/失败/无路由空间 → null
        const jevRouteModel = async (taskHint: string): Promise<ChatProviderChoice | null> => {
            const current: ChatProviderChoice | null = parentRuntime === null
                ? await ports.resolveChatProviderChoice() : parentRuntime.resolvedChoice;
            const providers: ProviderSetting[] = (await loadProviders(ports.getChatKvStore())) ?? [];
            const candidates: Record<string, string> = {};
            const choices: Map<string, ChatProviderChoice> = new Map<string, ChatProviderChoice>();
            if (current !== null) {
                candidates[current.model.id] = `当前聊天模型 ${current.provider.name}/${current.model.modelId}`;
                choices.set(current.model.id, current);
            }
            for (const p of providers) {
                if (choices.size >= 6)
                    break;
                if (!p.enabled)
                    continue;
                const m: ProviderModel | undefined = p.models.find((mm: ProviderModel): boolean => mm.type === 'chat');
                if (m !== undefined && !choices.has(m.id)) {
                    candidates[m.id] = `${p.name}/${m.modelId}`;
                    choices.set(m.id, { provider: p, model: m, providerContainerId: p.id });
                }
            }
            if (choices.size <= 1)
                return null;
            const r = await ports.jevChoiceSelect('model_routing', taskHint.substring(0, 300), candidates);
            if (r === null || r.shadow)
                return null;
            return choices.get(r.selected) ?? null;
        };
        const resolveModel = async (modelSettingId: string | null, taskHint?: string): Promise<ProviderModel | null> => {
            // Per-role override → Jev 路由(有上下文时)→ fallback current chat model
            //   (SubAgentRunner.kt:52-54 语义的 Jev 增强,失败/未配置回退链不变)
            let choice: ChatProviderChoice | null = null;
            if (modelSettingId !== null && modelSettingId.length > 0) {
                choice = await ports.resolveTaskChatModelChoice(modelSettingId, parentRuntime?.resolvedChoice);
            }
            if (choice === null && taskHint !== undefined && taskHint.length > 0) {
                choice = await jevRouteModel(taskHint);
            }
            if (choice === null)
                choice = parentRuntime === null
                    ? await ports.resolveChatProviderChoice() : parentRuntime.resolvedChoice;
            if (choice === null)
                return null;
            providerByModelId.set(choice.model.id, await ports.prepareChoiceAuth(choice.provider));
            return choice.model;
        };
        const resolveAssistant = async (): Promise<SubAgentAssistantResolution | null> => {
            const assistants: Assistant[] = await ports.getChatAssistants();
            if (assistants.length === 0)
                return null;
            const assistant: Assistant = parentRuntime === null ? assistants[0] : parentRuntime.assistant;
            const snapshot: AgentRuntimeSnapshot = parentAgentRuntime ?? await loadAgentRuntimeSnapshot(ports.getChatKvStore());
            const generationRetry = makeGenerationRetrySetting({
                enabled: snapshot.generationRetry.enabled,
                maxRetries: snapshot.generationRetry.maxRetries,
            });
            return {
                assistant,
                generationRetry,
                autoApproveTools: snapshot.autoApproveAllToolCalls,
                autoApproveHighRiskTools: snapshot.autoApproveHighRiskToolCalls,
            };
        };
        const makeProvider = (model: ProviderModel, params: TextGenerationParams, headers: CustomHeader[]): ChatStreamProvider => {
            // resolveModel 在 makeProvider 之前 await 完成并按 model.id 缓存 provider;
            //   缺失 = resolveModel 未被调用(契约违反),显式抛错(fail-closed,不伪成功)
            const cached: ProviderAuthSnapshot | undefined = providerByModelId.get(model.id);
            if (cached === undefined) {
                throw new Error(`Subagent provider not resolved for model ${model.id}`);
            }
            const customHeaderRecord: Record<string, string> = {};
            headers.forEach((h: CustomHeader): void => {
                const name: string = h.name.trim();
                if (name.length === 0)
                    return;
                customHeaderRecord[name] = h.value.trim();
            });
            const api: OpenAIChatApi = ports.buildProviderApi(cached.provider, customHeaderRecord, cached.accountId);
            return asChatStreamProvider(api, (): TextGenerationParams => params);
        };
        return new ChatStreamSubAgentGenerationPort({ resolveModel, resolveAssistant, makeProvider,
            configureRecipeLoop: (loop: ToolLoopOptions, request: SubAgentGenerationRequest): Promise<void> => ports.configureRecipeLoop(loop, request, parentConversationId),
        });
    };
    // Runner 单例(Koin single 等价;generation port 不持有会话状态)
    let subAgentRunner: SubAgentRunner | null = null;
    const getSubAgentRunner = (): SubAgentRunner => {
        if (subAgentRunner === null) {
            subAgentRunner = new GenerationSubAgentRunner(buildSubAgentGenerationPort());
        }
        return subAgentRunner;
    };
    // Manager 单例(Koin single 等价;deps 复用共享 store/transcript/time/path)
    let subAgentManager: SubAgentManager | null = null;
    let subAgentManagerInit: Promise<SubAgentManager> | null = null;
    const getSubAgentManager = async (): Promise<SubAgentManager> => {
        if (subAgentManager !== null)
            return subAgentManager;
        // Promise 单例:并发首访不得创建双 manager(双 runs Map/双任务注册)
        if (subAgentManagerInit === null) {
            subAgentManagerInit = (async (): Promise<SubAgentManager> => {
                await ports.hydrateSubAgentSetting();
                const deps: SubAgentManagerDeps = {
                    getSubAgentSetting: (): SubAgentRuntimeSetting => {
                        // 同步读缓存;manager 每次 start/listBuiltIns/runtimeSummary 调用
                        //   前由调用方确保已水合(ChatPage 发送前 await hydrateSubAgentSetting)
                        return ports.getHydratedSubAgentSetting();
                    },
                    runner: getSubAgentRunner(),
                    agentTaskStore: await ports.getAgentTaskStore(),
                    sessionAccessGrantStore: ports.getSessionGrantStore(),
                    transcript: createEntrySubAgentTranscriptPort(),
                    newId: (): string => util.generateRandomUUID(false),
                    now: (): number => Date.now(),
                    sleep: (ms: number): Promise<void> => new Promise<void>((resolve): void => { setTimeout(resolve, ms); }),
                    transcriptPathForRun: (runId: string): string => subAgentTranscriptPathForRun(runId),
                };
                const manager: SubAgentManager = new SubAgentManager(deps);
                subAgentManager = manager;
                return manager;
            })();
        }
        try {
            return await subAgentManagerInit;
        }
        catch (e) {
            subAgentManagerInit = null; // 失败不缓存,允许后续重试
            throw e instanceof Error ? e : new Error(String(e));
        }
    };
    const getSubAgentTools = async (ctx: SubAgentToolsContext): Promise<AgentTool[]> => {
        await ports.hydrateSubAgentSetting();
        const manager: SubAgentManager = await getSubAgentManager();
        const councilEnabled: boolean = await ports.isModelCouncilEnabled();
        const tools: SubAgentTools = new SubAgentTools({
            manager,
            runner: ctx.parentRuntime === undefined ? undefined : new GenerationSubAgentRunner(buildSubAgentGenerationPort(ctx.parentRuntime, ctx.parentAgentRuntime ?? null, ctx.parentConversationId)),
            parentConversationId: ctx.parentConversationId,
            parentToolsProvider: ctx.parentToolsProvider,
            isModelCouncilEnabled: (): boolean => councilEnabled,
        });
        return tools.getTools();
    };
    return { getSubAgentManager, getSubAgentTools, buildSubAgentGenerationPort };
};

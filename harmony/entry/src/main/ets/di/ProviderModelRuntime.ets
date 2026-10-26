// Provider/model selection and authenticated API construction, without local execution side effects.
import type { HttpClient, Storage } from '@amber/deepread-domain';
import { getChatKeyRoulette } from '../platform_impl/KeyRouletteSupport.ets';
import type { KeyValueStore, Assistant, OpenAIChatApi, ProviderSetting, ProviderSettingOpenAIVariant, ProviderSettingClaude, ProviderSettingGoogle, ProviderModel, ModelType, TaskModelPair, TaskModelReference, ProviderRuntimeSnapshot, ProviderAuthSnapshot, NovelResponsesStreamOptions, ProviderOAuthSnapshot, TextGenerationParams } from '@amber/chat-domain';
import { loadProviders, makeProviderSettingOpenAI, createOpenAIChatApi, createClaudeChatApi, createGoogleChatApi, hasUsableAuth, openAIAuthHeaders, copyProviderSettingWithModels, taskModelReferenceId, taskModelReferenceFromId, findTaskProviderModel, prepareProviderRuntime, prepareProviderAuth, DEFAULT_AUTO_MODEL_ID, DEFAULT_TITLE_PROMPT, DEFAULT_SUGGESTION_PROMPT, DEFAULT_COMPRESS_PROMPT, createKvResponseResumeStore } from '@amber/chat-domain';
import { loadTaskModelReference, storeTaskModelPair } from '../platform_impl/TaskModelSelectionSupport.ets';
import { resolveEntryGoogleAuth } from '../platform_impl/GoogleAuthSupport.ets';
import { encodeImageForChat, encodeImageDetailed } from '../platform_impl/EntryImageEncoder.ets';
import type { ChatProviderChoice, ChatProviderRuntime, TaskPrompts } from './ChatRuntimeContracts.ets';
export interface ChatModelSelection {
    providerId: string;
    modelId: string;
}
// ===== D-087:任务模型解析链 =====
// Android 锚点:
//   PreferencesKeys.kt(infra):29-30/:36 — 'title_model'/'suggestion_model'/'compress_model'
//   ChatPrefs.kt:71-77 — parseUuidOrNull 失败 → DEFAULT_AUTO_MODEL_ID
//   PreferencesStore.kt:479-481 — resolveTaskChatModel = findModelById ?: getCurrentChatModel
export const TASK_TITLE_MODEL_KEY: string = 'title_model';
export const TASK_SUGGESTION_MODEL_KEY: string = 'suggestion_model';
export const TASK_COMPRESS_MODEL_KEY: string = 'compress_model';
// 生图模型槽(Android imageGenerationModel 语义;AUTO/空 = 未配置,工具不装配)
export const TASK_IMAGE_MODEL_KEY: string = 'image_generation_model';
export const TASK_DEEPREAD_MODEL_KEY: string = 'deepread_task_model';
export interface TaskModelIds {
    titleModelId: string;
    suggestionModelId: string;
    compressModelId: string;
    imageModelId: string;
}
export interface TaskModelReferences {
    titleModel: TaskModelReference;
    suggestionModel: TaskModelReference;
    compressModel: TaskModelReference;
    imageModel: TaskModelReference;
}
// D-094:任务提示词读写 —
//   PreferencesKeys.kt:32-37 — 'title_prompt'/'suggestion_prompt'/'compress_prompt'
//   ChatPrefs.kt:81-84 读:键缺失 → DEFAULT_*;已存值(含空串)逐字返回
//   ChatPrefs.kt:99-102 写:直存字符串
export const TASK_TITLE_PROMPT_KEY: string = 'title_prompt';
export const TASK_SUGGESTION_PROMPT_KEY: string = 'suggestion_prompt';
export const TASK_COMPRESS_PROMPT_KEY: string = 'compress_prompt';
export interface NovelModelOption {
    providerId: string;
    // ProviderModel.id；固定策略只保存这个配置 UUID，不保存模型 API 名。
    modelId: string;
    label: string;
    providerLabel: string;
    available: boolean;
}
export interface DeepReadModelOption {
    providerId: string;
    modelId: string;
    label: string;
    providerLabel: string;
    available: boolean;
}
export interface ProviderModelRuntimePorts {
    getChatKvStore: () => KeyValueStore;
    getChatAssistants: () => Promise<Assistant[]>;
    getHttpClient: () => HttpClient;
    getStorage: () => Storage;
    getValidCodexToken: (providerId: string) => Promise<ProviderOAuthSnapshot | null>;
    getValidGrokToken: (providerId: string) => Promise<ProviderOAuthSnapshot | null>;
    findModelByIdInProviders: (providers: ProviderSetting[], id: string) => ProviderModel | null;
}
export interface ProviderModelRuntime {
    resolveChatProviderRuntime: (assistantId?: string, exactModelId?: string, exactProviderId?: string) => Promise<ChatProviderRuntime | null>;
    prepareChoiceAuth: (provider: ProviderSetting) => Promise<ProviderAuthSnapshot>;
    getChatModelSelection: () => Promise<ChatModelSelection | null>;
    saveChatModelSelection: (sel: ChatModelSelection) => Promise<void>;
    resolveChatProviderChoice: (assistant?: Assistant) => Promise<ChatProviderChoice | null>;
    readTaskModelReference: (key: string) => Promise<TaskModelReference>;
    saveTaskModelPair: (key: string, pair: TaskModelPair) => Promise<void>;
    loadTaskModelReferences: () => Promise<TaskModelReferences>;
    loadTaskModelIds: () => Promise<TaskModelIds>;
    saveTaskModelId: (key: string, modelUuid: string) => Promise<void>;
    loadTaskPrompts: () => Promise<TaskPrompts>;
    saveTaskPrompt: (key: string, value: string) => Promise<void>;
    loadDeepReadModelOptions: () => Promise<DeepReadModelOption[]>;
    loadNovelModelOptions: () => Promise<NovelModelOption[]>;
    resolveTaskChatModelChoice: (modelId: string, fallbackChoice?: ChatProviderChoice) => Promise<ChatProviderChoice | null>;
    resolveConfiguredTaskModel: (reference: TaskModelReference, type: ModelType) => Promise<ChatProviderChoice | null>;
    resolveTaskModelReference: (reference: TaskModelReference, fallbackChoice?: ChatProviderChoice) => Promise<ChatProviderChoice | null>;
    resolveTaskModelReferenceChain: (references: TaskModelReference[], fallbackChoice?: ChatProviderChoice) => Promise<ChatProviderChoice | null>;
    taskModelReferenceForChoice: (choice: ChatProviderChoice) => TaskModelReference;
    currentChatModelReference: () => Promise<TaskModelReference>;
    resolveTaskModelChain: (candidates: string[], fallbackChoice?: ChatProviderChoice) => Promise<ChatProviderChoice | null>;
    currentChatModelUuid: () => Promise<string>;
    describeChatModel: () => Promise<string>;
    buildProviderApi: (chosen: ProviderSetting, customHeaderRecord: Record<string, string>, oauthAccountId?: string, responses?: NovelResponsesStreamOptions) => OpenAIChatApi;
    prepareProviderApi: (provider: ProviderSetting, customHeaderRecord: Record<string, string>) => Promise<OpenAIChatApi>;
    resolveDeepReadRuntimeCore: () => Promise<ProviderRuntimeSnapshot>;
    resolveProviderRuntimeCore: (choice: ChatProviderChoice, assistant: Assistant) => Promise<ProviderRuntimeSnapshot>;
    resolveConfiguredTaskChatModel: (modelId: string) => Promise<ChatProviderChoice | null>;
    resolveConfiguredProviderChatModel: (providerId: string, modelId: string) => Promise<ChatProviderChoice | null>;
}
export const createProviderModelRuntime = (ports: ProviderModelRuntimePorts): ProviderModelRuntime => {
    // ===== Chat 装配(D-019/D-021/D-023) =====
    // 持久化:会话仓库 = ChatRdbRepository(relationalStore 真实实现,D-013 schema;
    //   初始化失败自动回退 memory + hilog 警告,非静默)。assistants/providers 走
    //   Preferences KV(真实持久化)。
    // Provider 选择(D-023):用户选择(Preferences 持久化 providerId/modelId)优先,
    //   未选择或选择失效 → 回退 D-019 策略(第一个 hasUsableAuth 且非空 models 的
    //   openai variant + 首模型)。选择器页 = ChatModelPickerPage。
    // 注(已核实 Android 基准):ProviderTemplatePickerSheet 只提供 generic
    //   OpenAI()/Google()/Claude() 空模板,无预置种子列表 → 不做 provider 种子数据。
    const SELECTED_PROVIDER_KEY: string = 'chat_selected_provider_id';
    const SELECTED_MODEL_KEY: string = 'chat_selected_model_id';
    const getChatModelSelection = async (): Promise<ChatModelSelection | null> => {
        const storage = ports.getStorage();
        const providerId: string = await storage.get<string>(SELECTED_PROVIDER_KEY, '');
        const modelId: string = await storage.get<string>(SELECTED_MODEL_KEY, '');
        if (providerId.length === 0 || modelId.length === 0)
            return null;
        return { providerId, modelId };
    };
    const saveChatModelSelection = async (sel: ChatModelSelection): Promise<void> => {
        const storage = ports.getStorage();
        await storage.set<string>(SELECTED_PROVIDER_KEY, sel.providerId);
        await storage.set<string>(SELECTED_MODEL_KEY, sel.modelId);
    };
    // D-084:Model.findProvider(PreferencesStore.kt:500-507)— 生成路径 checkOverwrite
    //   恒 true(GenerationHandler.kt:155):model.providerOverwrite 非 null →
    //   overwrite 副本(models = [])替代容器 provider;容器反查未命中时 overwrite
    //   不兜底(此处容器已命中,语义逐字)
    const effectiveProvider = (p: ProviderSetting, m: ProviderModel): ProviderSetting => m.providerOverwrite !== null ? copyProviderSettingWithModels(m.providerOverwrite, []) : p;
    const makeProviderChoice = (p: ProviderSetting, m: ProviderModel): ChatProviderChoice | null => {
        const provider: ProviderSetting = effectiveProvider(p, m);
        return hasUsableAuth(provider) ? { provider, model: m, providerContainerId: p.id } : null;
    };
    // D-080:导出供 ChatPage 发送门(canSendWithoutChatModel 等价物)使用
    const resolveChatProviderChoice = async (assistant?: Assistant): Promise<ChatProviderChoice | null> => {
        const providers = await loadProviders(ports.getChatKvStore());
        if (providers === null)
            return null;
        // Assistant-scoped model takes precedence over the global selection.
        if (assistant !== undefined && assistant.chatModelId !== null
            && assistant.chatModelId.trim().length > 0) {
            for (const p of providers) {
                if (!hasUsableAuth(p))
                    continue;
                for (const m of p.models) {
                    if (m.id === assistant.chatModelId && m.type === 'chat') {
                        const choice: ChatProviderChoice | null = makeProviderChoice(p, m);
                        if (choice !== null)
                            return choice;
                    }
                }
            }
        }
        // 用户选择优先(D-023;D-046 起不再限定 openai 变体)
        const selection = await getChatModelSelection();
        if (selection !== null) {
            for (const p of providers) {
                if (p.id !== selection.providerId || !hasUsableAuth(p))
                    continue;
                for (const m of p.models) {
                    if (m.modelId === selection.modelId && m.type === 'chat') {
                        const choice: ChatProviderChoice | null = makeProviderChoice(p, m);
                        if (choice !== null)
                            return choice;
                    }
                }
            }
        }
        // 回退 D-019 策略:第一个有可用认证且含 chat 模型的 provider + 首个 chat 模型
        for (const p of providers) {
            if (!hasUsableAuth(p))
                continue;
            for (const m of p.models) {
                if (m.type !== 'chat')
                    continue;
                const choice: ChatProviderChoice | null = makeProviderChoice(p, m);
                if (choice !== null)
                    return choice;
            }
        }
        return null;
    };
    const readTaskModelReference = (key: string): Promise<TaskModelReference> => loadTaskModelReference(ports.getChatKvStore(), key);
    const saveTaskModelPair = (key: string, pair: TaskModelPair): Promise<void> => storeTaskModelPair(ports.getChatKvStore(), key, pair);
    const loadTaskModelReferences = async (): Promise<TaskModelReferences> => ({
        titleModel: await readTaskModelReference(TASK_TITLE_MODEL_KEY),
        suggestionModel: await readTaskModelReference(TASK_SUGGESTION_MODEL_KEY),
        compressModel: await readTaskModelReference(TASK_COMPRESS_MODEL_KEY),
        imageModel: await readTaskModelReference(TASK_IMAGE_MODEL_KEY),
    });
    const readTaskModelId = async (key: string): Promise<string> => {
        return taskModelReferenceId(await readTaskModelReference(key));
    };
    const loadTaskModelIds = async (): Promise<TaskModelIds> => ({
        titleModelId: await readTaskModelId(TASK_TITLE_MODEL_KEY),
        suggestionModelId: await readTaskModelId(TASK_SUGGESTION_MODEL_KEY),
        compressModelId: await readTaskModelId(TASK_COMPRESS_MODEL_KEY),
        imageModelId: await readTaskModelId(TASK_IMAGE_MODEL_KEY),
    });
    // D-094:任务模型写路径(SettingModelPage.kt onSelect/onClear)—
    //   onSelect → it.id 直存;onClear → DEFAULT_AUTO_MODEL_ID 字面量
    //   (ChatPrefs.kt:94-96 writeTo 直存 uuid 字符串;读侧空/缺失亦归一 AUTO)
    const saveTaskModelId = async (key: string, modelUuid: string): Promise<void> => {
        const storage = ports.getStorage();
        await storage.set<string>(key, modelUuid);
    };
    const loadTaskPrompts = async (): Promise<TaskPrompts> => {
        const storage = ports.getStorage();
        const title: string = await storage.get<string>(TASK_TITLE_PROMPT_KEY, DEFAULT_TITLE_PROMPT);
        const suggestion: string = await storage.get<string>(TASK_SUGGESTION_PROMPT_KEY, DEFAULT_SUGGESTION_PROMPT);
        const compress: string = await storage.get<string>(TASK_COMPRESS_PROMPT_KEY, DEFAULT_COMPRESS_PROMPT);
        return { titlePrompt: title, suggestionPrompt: suggestion, compressPrompt: compress };
    };
    const saveTaskPrompt = async (key: string, value: string): Promise<void> => {
        const storage = ports.getStorage();
        await storage.set<string>(key, value);
    };
    // 当前 chat 模型的 uuid 等价物(Android settings.chatModelId 对应):
    //   选择态 → providers 内命中模型的 m.id;未选择 → AUTO(findById 必失,
    //   resolveTaskChatModel 内部回退 getCurrentChatModel,语义等价)
    const resolveCurrentChatModelUuid = async (providers: ProviderSetting[]): Promise<string> => {
        const selection = await getChatModelSelection();
        if (selection === null)
            return DEFAULT_AUTO_MODEL_ID;
        for (const p of providers) {
            if (p.id === selection.providerId) {
                for (const m of p.models) {
                    if (m.modelId === selection.modelId && m.type === 'chat')
                        return m.id;
                }
            }
        }
        return DEFAULT_AUTO_MODEL_ID;
    };
    // 查找已配置且可认证的 chat 模型，不包含当前聊天模型兜底。
    const resolveConfiguredTaskChatModel = async (modelId: string): Promise<ChatProviderChoice | null> => {
        const providers = await loadProviders(ports.getChatKvStore());
        if (providers === null)
            return null;
        const found: ProviderModel | null = ports.findModelByIdInProviders(providers, modelId);
        if (found === null || found.type !== 'chat')
            return null;
        for (const p of providers) {
            if (!hasUsableAuth(p))
                continue;
            for (const m of p.models) {
                if (m.id === found.id && m.type === 'chat') {
                    return makeProviderChoice(p, m);
                }
            }
        }
        return null;
    };
    // 小说固定模型必须精确匹配已持久化的 provider.id + ProviderModel.id。
    // 这里故意不委托任务模型解析链：后者的全局 Chat fallback 适合辅助任务，
    // 但会破坏 Novel fixed policy 的 fail-closed 语义。
    const resolveConfiguredProviderChatModel = async (providerId: string, modelId: string): Promise<ChatProviderChoice | null> => {
        const providers = await loadProviders(ports.getChatKvStore());
        if (providers === null)
            return null;
        for (const provider of providers) {
            if (provider.id !== providerId)
                continue;
            if (!hasUsableAuth(provider))
                return null;
            for (const model of provider.models) {
                if (model.id === modelId && model.type === 'chat') {
                    return makeProviderChoice(provider, model);
                }
            }
            return null;
        }
        return null;
    };
    const loadDeepReadModelOptions = async (): Promise<DeepReadModelOption[]> => {
        const providers: ProviderSetting[] = (await loadProviders(ports.getChatKvStore())) ?? [];
        const options: DeepReadModelOption[] = [];
        for (const provider of providers) {
            for (const model of provider.models) {
                if (model.type !== 'chat')
                    continue;
                options.push({
                    providerId: provider.id, modelId: model.id,
                    label: model.displayName.trim().length > 0 ? model.displayName : model.modelId,
                    providerLabel: provider.name,
                    available: makeProviderChoice(provider, model) !== null,
                });
            }
        }
        return options;
    };
    // C5 项目策略 picker 的唯一 provider/model 事实源。不可用模型仍返回，
    // 以便固定选择失效后 UI 能原位呈现并引导用户修复，而不是静默替换。
    const loadNovelModelOptions = async (): Promise<NovelModelOption[]> => {
        const providers = await loadProviders(ports.getChatKvStore());
        if (providers === null)
            return [];
        const options: NovelModelOption[] = [];
        for (const provider of providers) {
            for (const model of provider.models) {
                if (model.type !== 'chat')
                    continue;
                options.push({
                    providerId: provider.id,
                    modelId: model.id,
                    label: model.displayName.trim().length > 0 ? model.displayName : model.modelId,
                    providerLabel: provider.name,
                    available: hasUsableAuth(provider) && makeProviderChoice(provider, model) !== null,
                });
            }
        }
        return options;
    };
    // resolveTaskChatModel(PreferencesStore.kt:479-481)—
    //   findModelById(modelId) ?: getCurrentChatModel(= resolveChatProviderChoice)
    const resolveTaskChatModelChoice = async (modelId: string, fallbackChoice?: ChatProviderChoice): Promise<ChatProviderChoice | null> => (await resolveConfiguredTaskChatModel(modelId)) ?? fallbackChoice ?? resolveChatProviderChoice();
    const resolveConfiguredTaskModel = async (reference: TaskModelReference, type: ModelType): Promise<ChatProviderChoice | null> => {
        if (reference.kind === 'legacy' && type === 'chat') {
            return resolveConfiguredTaskChatModel(reference.modelId);
        }
        const providers: ProviderSetting[] | null = await loadProviders(ports.getChatKvStore());
        if (providers === null)
            return null;
        const configured = findTaskProviderModel(providers, reference, type);
        return configured === null ? null : makeProviderChoice(configured.provider, configured.model);
    };
    const resolveTaskModelReference = async (reference: TaskModelReference, fallbackChoice?: ChatProviderChoice): Promise<ChatProviderChoice | null> => (await resolveConfiguredTaskModel(reference, 'chat')) ?? fallbackChoice ?? resolveChatProviderChoice();
    const resolveTaskModelReferenceChain = async (references: TaskModelReference[], fallbackChoice?: ChatProviderChoice): Promise<ChatProviderChoice | null> => {
        for (const reference of references) {
            const choice: ChatProviderChoice | null = await resolveConfiguredTaskModel(reference, 'chat');
            if (choice !== null)
                return choice;
        }
        return fallbackChoice ?? resolveChatProviderChoice();
    };
    const taskModelReferenceForChoice = (choice: ChatProviderChoice): TaskModelReference => taskModelReferenceFromId(choice.model.id, choice.providerContainerId);
    const currentChatModelReference = async (): Promise<TaskModelReference> => {
        const choice: ChatProviderChoice | null = await resolveChatProviderChoice();
        return choice === null ? taskModelReferenceFromId(DEFAULT_AUTO_MODEL_ID) : taskModelReferenceForChoice(choice);
    };
    // 链式解析:候选全部按顺序尝试，最后才回退当前聊天模型。
    const resolveTaskModelChain = async (candidates: string[], fallbackChoice?: ChatProviderChoice): Promise<ChatProviderChoice | null> => {
        for (const id of candidates) {
            const choice: ChatProviderChoice | null = await resolveConfiguredTaskChatModel(id);
            if (choice !== null)
                return choice;
        }
        return fallbackChoice ?? resolveChatProviderChoice();
    };
    // 当前 chat 模型 uuid(供任务链 chatModelId 实参)
    const currentChatModelUuid = async (): Promise<string> => {
        const providers = await loadProviders(ports.getChatKvStore());
        if (providers === null)
            return DEFAULT_AUTO_MODEL_ID;
        return resolveCurrentChatModelUuid(providers);
    };
    // 头部展示用:「provider 名 / modelId」;未配置返回 ''
    const describeChatModel = async (): Promise<string> => {
        const choice = await resolveChatProviderChoice();
        if (choice === null)
            return '';
        return `${choice.provider.name} / ${choice.model.modelId}`;
    };
    // D-087:三变体 api 构建公共化(resolveChatRuntime 与任务模型链路共用;
    //   分发/auth/header 语义与 D-046/D-078 逐字一致,未落地传输显式抛错)
    const buildProviderApi = (chosen: ProviderSetting, customHeaderRecord: Record<string, string>, oauthAccountId: string = '', responses?: NovelResponsesStreamOptions): OpenAIChatApi => {
        // useResponseApi=true 走 createOpenAIChatApi 内部分流(Responses);不再在此 throw
        if (!hasUsableAuth(chosen)) {
            throw new Error('Provider 鉴权未配置或当前 Harmony 版本不支持');
        }
        if (chosen.type === 'claude') {
            const claudeSetting: ProviderSettingClaude = chosen as ProviderSettingClaude;
            return createClaudeChatApi({
                http: ports.getHttpClient(),
                setting: claudeSetting,
                headers: (): Record<string, string> => customHeaderRecord,
                encodeImage: encodeImageDetailed,
                // D-078:每请求 LRU 轮换(ClaudeProvider.kt:68/:74,PD-008 消除)
                keyRoulette: getChatKeyRoulette(),
            });
        }
        if (chosen.type === 'google') {
            const googleSetting: ProviderSettingGoogle = chosen as ProviderSettingGoogle;
            return createGoogleChatApi({
                http: ports.getHttpClient(),
                setting: googleSetting,
                headers: (): Record<string, string> => customHeaderRecord,
                encodeImage: encodeImageDetailed,
                resolveAuth: resolveEntryGoogleAuth,
                // D-078:每请求 LRU 轮换(GoogleProvider.kt:77/:134)
                keyRoulette: getChatKeyRoulette(),
            });
        }
        const openaiSetting: ProviderSettingOpenAIVariant = chosen as ProviderSettingOpenAIVariant;
        // D-078:bearerResolver 语义(ChatCompletionsAPI.kt:103-105)— token 不再
        //   一次性解析,headers() 每请求调用时轮换(ChatCompletionsAPI.kt:102-105
        //   bearerResolver 每请求语义;PD-008 消除)
        const nextToken: () => string = (): string => getChatKeyRoulette().next(openaiSetting.apiKey, openaiSetting.id);
        return createOpenAIChatApi({
            http: ports.getHttpClient(),
            setting: makeProviderSettingOpenAI({
                id: openaiSetting.id,
                enabled: openaiSetting.enabled,
                name: openaiSetting.name,
                baseUrl: openaiSetting.baseUrl,
                chatCompletionsPath: openaiSetting.chatCompletionsPath,
                useResponseApi: openaiSetting.useResponseApi,
                authMode: openaiSetting.authMode,
                brand: openaiSetting.brand,
            }),
            headers: (): Record<string, string> => {
                const auth: Record<string, string> = openAIAuthHeaders(openaiSetting.authMode, openaiSetting.baseUrl, nextToken());
                // codex 后端要求 ChatGPT-Account-Id(有 accountId 时注入)
                if (oauthAccountId.length > 0)
                    auth['ChatGPT-Account-Id'] = oauthAccountId;
                // grok cli-chat-proxy 要求 CLI 身份头(iOS IOSGrokCliProxyIdentity 同款)
                if (openaiSetting.authMode === 'grok_oauth') {
                    auth['User-Agent'] = 'grok-shell/0.2.101 (ios; arm64)';
                    auth['x-grok-client-identifier'] = 'grok-shell';
                }
                // customHeaders 后写覆盖(auth 冲突时 custom 赢,同 claude/google 层语义)
                for (const k of Object.keys(customHeaderRecord))
                    auth[k] = customHeaderRecord[k];
                return auth;
            },
            // D-038:图片编码(data:/http(s): 直通,file:// 读文件编码;失败即抛)
            encodeImage: encodeImageForChat,
            // P6-01:官方 OpenAI + api_key 时注入 resume cursor KV Port
            resumeStore: responses === undefined && openaiSetting.useResponseApi === true
                ? createKvResponseResumeStore(ports.getChatKvStore())
                : undefined,
            enableResponsesResume: openaiSetting.useResponseApi === true,
            runId: responses?.runId,
            resumeFrom: responses?.resumeFrom,
            resumeMessages: responses?.partialMessages,
            onResumeCheckpoint: responses?.onCheckpoint,
        });
    };
    // Auxiliary tasks keep their existing model/parameter/header policy and share OAuth refresh.
    const prepareProviderApi = async (provider: ProviderSetting, customHeaderRecord: Record<string, string>): Promise<OpenAIChatApi> => {
        const headers: Record<string, string> = {};
        for (const key of Object.keys(customHeaderRecord))
            headers[key] = customHeaderRecord[key];
        const auth: ProviderAuthSnapshot = await prepareProviderAuth({
            provider, refreshCodex: ports.getValidCodexToken, refreshGrok: ports.getValidGrokToken,
        });
        return buildProviderApi(auth.provider, headers, auth.accountId);
    };
    const resolveProviderRuntimeCore = (choice: ChatProviderChoice, assistant: Assistant): Promise<ProviderRuntimeSnapshot> => prepareProviderRuntime({
        provider: choice.provider, model: choice.model, assistant,
        refreshCodex: ports.getValidCodexToken,
        refreshGrok: ports.getValidGrokToken,
        buildApi: buildProviderApi,
    });
    const resolveDeepReadRuntimeCore = async (): Promise<ProviderRuntimeSnapshot> => {
        const reference: TaskModelReference = await readTaskModelReference(TASK_DEEPREAD_MODEL_KEY);
        const choice: ChatProviderChoice | null = reference.kind === 'auto'
            ? await resolveChatProviderChoice()
            : await resolveConfiguredTaskModel(reference, 'chat');
        if (choice === null) {
            throw new Error(reference.kind === 'auto'
                ? '请先在提供商设置中配置可用的聊天模型。'
                : '指定的深读模型已不可用，请检查提供商认证或重新选择模型。');
        }
        const assistants: Assistant[] = await ports.getChatAssistants();
        return resolveProviderRuntimeCore(choice, assistants[0]);
    };
    const resolveChatProviderRuntime = async (assistantId?: string, exactModelId?: string, exactProviderId?: string): Promise<ChatProviderRuntime | null> => {
        // 助手解析(D-028):会话绑定 assistantId 优先,未命中/未传 → 首个(D-019 延续)
        const assistants: Assistant[] = await ports.getChatAssistants();
        let assistant: Assistant = assistants[0];
        if (assistantId !== undefined) {
            for (const a of assistants) {
                if (a.id === assistantId) {
                    assistant = a;
                    break;
                }
            }
        }
        // Novel/任务显式指定模型时必须精确解析，不得静默改用默认聊天模型。
        // 普通 Chat 仍维持 Assistant.chatModelId → 全局选择的既有顺序。
        const choice = exactProviderId !== undefined
            ? await resolveConfiguredProviderChatModel(exactProviderId, exactModelId ?? '')
            : exactModelId !== undefined && exactModelId.length > 0
                ? await resolveConfiguredTaskChatModel(exactModelId)
                : await resolveChatProviderChoice(assistant);
        if (choice === null)
            return null;
        const runtime: ProviderRuntimeSnapshot = await resolveProviderRuntimeCore(choice, assistant);
        const model: ProviderModel = runtime.model;
        const params: TextGenerationParams = runtime.params;
        const api: OpenAIChatApi = runtime.api;
        return {
            api, resolvedChoice: choice, assistant, params,
            contextMessageSize: runtime.defaults.contextMessageSize,
            modelContextWindowTokens: model.contextWindowTokens,
            modelSupportsImageInput: model.inputModalities.includes('image'),
            modelAbilities: model.abilities,
        };
    };
    const prepareChoiceAuth = (provider: ProviderSetting): Promise<ProviderAuthSnapshot> => prepareProviderAuth({ provider, refreshCodex: ports.getValidCodexToken, refreshGrok: ports.getValidGrokToken });
    return { resolveChatProviderRuntime, prepareChoiceAuth, getChatModelSelection, saveChatModelSelection, resolveChatProviderChoice, readTaskModelReference, saveTaskModelPair, loadTaskModelReferences, loadTaskModelIds, saveTaskModelId, loadTaskPrompts, saveTaskPrompt, loadDeepReadModelOptions, loadNovelModelOptions, resolveTaskChatModelChoice, resolveConfiguredTaskModel, resolveTaskModelReference, resolveTaskModelReferenceChain, taskModelReferenceForChoice, currentChatModelReference, resolveTaskModelChain, currentChatModelUuid, describeChatModel, buildProviderApi, prepareProviderApi, resolveDeepReadRuntimeCore, resolveProviderRuntimeCore, resolveConfiguredTaskChatModel, resolveConfiguredProviderChatModel };
};

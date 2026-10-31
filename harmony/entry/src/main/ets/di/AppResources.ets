// Shared persistence, HTTP and activity resources; all lazy instances belong to this factory.
import type { AiClient, HttpClient, Storage, DeepReadRepository, SearchProviderRegistry, Notifier, SourcePrefetcher, FileStore, ModelRegistry } from '@amber/deepread-domain';
import { createSourcePrefetcher, createStorageModelRegistry } from '@amber/deepread-domain';
import { createRcpHttpClient } from '../platform_impl/RcpHttpClient.ets';
import { createPreferencesStorage } from '../platform_impl/PreferencesStorage.ets';
import { createRdbRepository } from '../platform_impl/RdbRepository.ets';
import { createOpenAiCompatibleAiClient } from '../platform_impl/OpenAiCompatibleAiClient.ets';
import { createTavilyAndFallbackRegistry } from '../platform_impl/SearchRegistry.ets';
import { createNotificationNotifier } from '../platform_impl/NotificationNotifier.ets';
import { createFileStore } from '../platform_impl/FileStore.ets';
import { createEntryAbortController } from '../platform_impl/EntryAbortController.ets';
import type { ConversationRepository, KeyValueStore, Assistant, CompactStore, SearchSettings } from '@amber/chat-domain';
import { loadAssistants, makeAssistant, saveAssistants, SessionAccessGrantStore, AgentToolActivityStore, loadSearchPrefs } from '@amber/chat-domain';
import { createChatKvStore } from '../platform_impl/ChatKvStore.ets';
import { createEntrySecretFieldStore } from '../platform_impl/ProviderKeyVault.ets';
import { initializeEntryGoogleAuth } from '../platform_impl/GoogleAuthSupport.ets';
import { createEntryCompactStore } from '../platform_impl/EntryCompactStore.ets';
// 容器单例
export interface Container {
    httpClient: HttpClient;
    storage: Storage;
    repository: DeepReadRepository;
    searchRegistry: SearchProviderRegistry;
    prefetcher: SourcePrefetcher;
    aiClient: AiClient;
    notifier: Notifier;
    fileStore: FileStore;
    modelRegistry: ModelRegistry;
}
export interface AppResourcesPorts {
    initializeHost: (httpClient: HttpClient) => void;
    ensureSearchSdk: () => void;
    createChatRdbRepository: () => ConversationRepository;
}
export interface AppResources {
    getAppContainer: () => Container;
    getNotifier: () => Notifier;
    getPrefetcher: () => SourcePrefetcher;
    getAiClient: () => AiClient;
    getRepository: () => DeepReadRepository;
    getFileStore: () => FileStore;
    getModelRegistry: () => ModelRegistry;
    getChatKvStore: () => KeyValueStore;
    getChatRepository: () => ConversationRepository;
    getCompactStore: () => CompactStore;
    getSessionGrantStore: () => SessionAccessGrantStore;
    getToolActivityStore: () => AgentToolActivityStore;
    getChatAssistants: () => Promise<Assistant[]>;
}
export const createAppResources = (ports: AppResourcesPorts): AppResources => {
    let cached: Container | null = null;
    const buildContainer = (): Container => {
        const httpClient: HttpClient = createRcpHttpClient();
        initializeEntryGoogleAuth(httpClient);
        ports.initializeHost(httpClient);
        const storage: Storage = createPreferencesStorage(createEntrySecretFieldStore());
        const repository: DeepReadRepository = createRdbRepository();
        // 深度阅读复用聊天「搜索服务」设置(已配置 API + 免费源开关);均在搜索时惰性读取
        const searchRegistry: SearchProviderRegistry = createTavilyAndFallbackRegistry(httpClient, storage, (): Promise<SearchSettings> => loadSearchPrefs(getChatKvStore()), ports.ensureSearchSdk);
        const prefetcher: SourcePrefetcher = createSourcePrefetcher(httpClient, searchRegistry, createEntryAbortController);
        const aiClient: AiClient = createOpenAiCompatibleAiClient(httpClient, storage);
        const notifier: Notifier = createNotificationNotifier();
        const fileStore: FileStore = createFileStore();
        const modelRegistry: ModelRegistry = createStorageModelRegistry(storage);
        return {
            httpClient, storage, repository, searchRegistry, prefetcher, aiClient, notifier,
            fileStore, modelRegistry,
        };
    };
    const getAppContainer = (): Container => {
        if (cached === null) {
            cached = buildContainer();
        }
        return cached;
    };
    const getNotifier = (): Notifier => getAppContainer().notifier;
    const getPrefetcher = (): SourcePrefetcher => getAppContainer().prefetcher;
    const getAiClient = (): AiClient => getAppContainer().aiClient;
    const getRepository = (): DeepReadRepository => getAppContainer().repository;
    const getFileStore = (): FileStore => getAppContainer().fileStore;
    const getModelRegistry = (): ModelRegistry => getAppContainer().modelRegistry;
    let chatKv: KeyValueStore | null = null;
    const getChatKvStore = (): KeyValueStore => {
        if (chatKv === null) {
            chatKv = createChatKvStore(getAppContainer().storage);
        }
        return chatKv;
    };
    let chatRepo: ConversationRepository | null = null;
    const getChatRepository = (): ConversationRepository => {
        if (chatRepo === null) {
            // D-021:relationalStore 真实实现;init 失败内部回退 memory(hilog 警告,非静默)
            chatRepo = ports.createChatRdbRepository();
        }
        return chatRepo;
    };
    // D-055:compact 存储单例(ConversationContextRepository.kt 的 KV 形态)
    let compactStore: CompactStore | null = null;
    const getCompactStore = (): CompactStore => {
        if (compactStore === null) {
            compactStore = createEntryCompactStore(getChatKvStore());
        }
        return compactStore;
    };
    // D-058:session 访问授权 store 单例(SessionAccessGrantStore.kt;签发方 history
    //   subagent 未移植,store 仅承载 validate/recordUse)
    let sessionGrantStore: SessionAccessGrantStore | null = null;
    const getSessionGrantStore = (): SessionAccessGrantStore => {
        if (sessionGrantStore === null) {
            sessionGrantStore = new SessionAccessGrantStore();
        }
        return sessionGrantStore;
    };
    let toolActivityStore: AgentToolActivityStore | null = null;
    const getToolActivityStore = (): AgentToolActivityStore => {
        if (toolActivityStore === null) {
            toolActivityStore = new AgentToolActivityStore();
        }
        return toolActivityStore;
    };
    const getChatAssistants = async (): Promise<Assistant[]> => {
        const kv = getChatKvStore();
        const loaded = await loadAssistants(kv);
        if (loaded !== null && loaded.length > 0)
            return loaded;
        // 空库:种子落库 — 临时 id 不存在于 KV 时,扩展/工具/MCP/记忆等子页
        // (裸 loadAssistants 按 id find)会报"助手不存在"
        const seeded: Assistant[] = [makeAssistant({ name: '默认助手' })];
        await saveAssistants(kv, seeded);
        return seeded;
    };
    return { getAppContainer, getNotifier, getPrefetcher, getAiClient, getRepository, getFileStore, getModelRegistry, getChatKvStore, getChatRepository, getCompactStore, getSessionGrantStore, getToolActivityStore, getChatAssistants };
};

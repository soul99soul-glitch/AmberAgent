// Owns DeepRead scheduler, artifact and playbook lifecycle.
import type { HttpClient, Storage, Notifier, RunManagerDeps, AiClient, UIMessage, Tool, ToolDefinition, DeepReadOutput, DeepReadScheduler, DeepReadCacheEntry, Observable, SchedulerRunOptions, DeepReadTemplateSnapshot, SearchHit, DeepReadRepository, SearchProviderRegistry, SourcePrefetcher, FileStore, AbortSignalLike } from '@amber/deepread-domain';
import { isInterruptedPhase, createScheduler, createSourcePrefetcher, DeepReadPlaybookRepository } from '@amber/deepread-domain';
import { DeepReadRunRepositoryAdapter } from '../platform_impl/DeepReadRunRepository.ets';
import { DeepReadArtifactStore } from '../platform_impl/DeepReadArtifactStore.ets';
import { setDeepReadGenerationActivity, beginDeepReadGenerationStep, observeDeepReadGenerationSnapshot } from '../platform_impl/DeepReadGenerationActivity.ets';
import { createTavilyAndFallbackRegistry } from '../platform_impl/SearchRegistry.ets';
import { captureDeepReadTemplate } from '../platform_impl/DeepReadTemplate.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { searchDeepReadGoogle } from '../platform_impl/DeepReadGoogleSearch.ets';
import { createEntryAbortController } from '../platform_impl/EntryAbortController.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Context } from '@kit.AbilityKit';
import type { KeyValueStore, ProviderRuntimeSnapshot, SearchSettings } from '@amber/chat-domain';
import { createChatDeepReadAiClient, loadSearchPrefs } from '@amber/chat-domain';
import { sha256HexUtf8 } from '../platform_impl/EntryLocalToolPorts.ets';
import { util } from '@kit.ArkTS';
const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Container';
export interface DeepReadTextRuntime {
    aiClient: AiClient;
    model: string;
}
export interface DeepReadRuntimePorts {
    getHttpClient: () => HttpClient;
    getStorage: () => Storage;
    getPrefetcher: () => SourcePrefetcher;
    getAiClient: () => AiClient;
    getNotifier: () => Notifier;
    getRepository: () => DeepReadRepository;
    getChatKvStore: () => KeyValueStore;
    getFileStore: () => FileStore;
    resolveDeepReadRuntimeCore: () => Promise<ProviderRuntimeSnapshot>;
    ensureSearchSdk: () => void;
}
export interface DeepReadRuntime {
    getDeepReadArtifactStore: () => DeepReadArtifactStore;
    getDeepReadTextRuntime: () => Promise<DeepReadTextRuntime>;
    getDeepReadScheduler: () => Promise<DeepReadScheduler>;
    getDeepReadPlaybookRepository: () => DeepReadPlaybookRepository;
}
export const createDeepReadRuntime = (ports: DeepReadRuntimePorts): DeepReadRuntime => {
    let deepReadArtifactStore: DeepReadArtifactStore | null = null;
    const getDeepReadArtifactStore = (): DeepReadArtifactStore => {
        if (deepReadArtifactStore === null) {
            deepReadArtifactStore = new DeepReadArtifactStore(ports.getRepository(), ports.getChatKvStore());
        }
        return deepReadArtifactStore;
    };
    const getDeepReadTextRuntime = async (): Promise<DeepReadTextRuntime> => {
        const runtime: ProviderRuntimeSnapshot = await ports.resolveDeepReadRuntimeCore();
        return { model: runtime.model.modelId, aiClient: createChatDeepReadAiClient({ api: runtime.api, params: runtime.params }) };
    };
    const toolToModelTool = (tool: ToolDefinition): Tool => ({
        name: tool.name,
        description: tool.description,
        schema: tool.schema ?? {},
        allowsAutoApproval: tool.allowsAutoApproval,
        isHighRisk: tool.isHighRisk,
        execute: tool.execute,
    });
    let deepReadScheduler: DeepReadScheduler | null = null;
    let deepReadSchedulerInit: Promise<DeepReadScheduler> | null = null;
    const getDeepReadScheduler = (): Promise<DeepReadScheduler> => {
        if (deepReadScheduler !== null)
            return Promise.resolve(deepReadScheduler);
        if (deepReadSchedulerInit !== null)
            return deepReadSchedulerInit;
        deepReadSchedulerInit = (async (): Promise<DeepReadScheduler> => {
            const runRepository: DeepReadRunRepositoryAdapter = new DeepReadRunRepositoryAdapter(ports.getRepository(), (entry: DeepReadCacheEntry): Promise<void> => getDeepReadArtifactStore().save(entry.topicId).then((): void => { }));
            await runRepository.hydrate();
            const createRunManager = async (topicId?: string, title?: string, runToken?: number, options?: SchedulerRunOptions): Promise<RunManagerDeps> => {
                if (topicId !== undefined)
                    await runRepository.load(topicId);
                const runtime: ProviderRuntimeSnapshot = await ports.resolveDeepReadRuntimeCore();
                const cachedOutput: DeepReadOutput | null = topicId === undefined ? null : runRepository.get(topicId, title ?? '');
                let templateSnapshot: DeepReadTemplateSnapshot | undefined = options?.templateSnapshot
                    ?? (options?.force ? undefined : cachedOutput?.templateSnapshot);
                if (templateSnapshot === undefined) {
                    const templateId: string = await ports.getStorage().get<string>('deepread_template_id', 'none');
                    templateSnapshot = await captureDeepReadTemplate(ports.getStorage(), templateId);
                }
                const searchRegistry: SearchProviderRegistry = createTavilyAndFallbackRegistry(ports.getHttpClient(), ports.getStorage(), (): Promise<SearchSettings> => loadSearchPrefs(ports.getChatKvStore()), ports.ensureSearchSdk, { searchGoogle: (query: string, resultSize: number, signal?: AbortSignalLike): Promise<SearchHit[]> => searchDeepReadGoogle(topicId ?? '', query, resultSize, signal) });
                const prefetcher: SourcePrefetcher = createSourcePrefetcher(ports.getHttpClient(), searchRegistry, createEntryAbortController);
                const playbookMarkdown: string = (await getDeepReadPlaybookRepository().read()).markdown;
                const model: string = runtime.model.modelId;
                const aiClient: AiClient = createChatDeepReadAiClient({
                    api: runtime.api, params: runtime.params,
                    onRequestStart: (history: UIMessage[]): void => {
                        if (topicId !== undefined && runToken !== undefined)
                            beginDeepReadGenerationStep(topicId, runToken, history);
                    },
                    onRawSnapshot: (messages: UIMessage[]): void => {
                        if (topicId !== undefined && runToken !== undefined)
                            observeDeepReadGenerationSnapshot(topicId, runToken, messages);
                    },
                });
                const collectRun = (messages: UIMessage[], _label: string, signal?: import('@amber/deepread-domain').AbortSignalLike, tools?: ToolDefinition[]): Promise<UIMessage[]> => {
                    hilog.info(DOMAIN, TAG, 'collectRun call: msgs=%{public}d tools=%{public}d', messages.length, tools?.length ?? 0);
                    return aiClient.generateText({
                        model,
                        messages,
                        tools: tools?.map(toolToModelTool),
                        maxSteps: 32,
                        autoApproveTools: true,
                        autoApproveHighRiskTools: true,
                        autoApprovedToolNames: tools?.map((tool: ToolDefinition): string => tool.name),
                        signal,
                    }).then((out: UIMessage[]): UIMessage[] => {
                        // 诊断:模型响应形态(文本/工具调用占比)— "未按约定调用分段写入工具"排查
                        let kinds: string = '';
                        for (const m of out) {
                            for (const p of m.parts)
                                kinds += p.type + ',';
                        }
                        hilog.info(DOMAIN, TAG, 'collectRun done: msgs=%{public}d parts=[%{public}s]', out.length, kinds);
                        return out;
                    });
                };
                const runManager: RunManagerDeps = {
                    onProgress: options?.onProgress,
                    prefetcher,
                    aiClient,
                    model,
                    writerMode: getProductKind() === 'deepread' || !runtime.model.abilities.includes('tool') ? 'structured' : 'tools',
                    templateSnapshot,
                    playbookMarkdown,
                    collectRun,
                    repository: runRepository,
                    nowIso: (): string => new Date().toISOString().slice(0, 10),
                };
                return runManager;
            };
            // Reading history and active jobs does not require a configured model.
            // The actual job always captures its provider through createRunManager.
            const runManager: RunManagerDeps = {
                prefetcher: ports.getPrefetcher(), aiClient: ports.getAiClient(), model: '',
                playbookMarkdown: '', nowIso: (): string => new Date().toISOString().slice(0, 10),
                collectRun: (): Promise<UIMessage[]> => Promise.reject(new Error('深读运行配置尚未捕获')),
                repository: runRepository,
            };
            const scheduler: DeepReadScheduler = createScheduler({
                runManager,
                createRunManager,
                observeOutput: (topicId: string): Observable<DeepReadOutput> => mapCacheEntryObservable(ports.getRepository().observe(topicId)),
                isInterruptedPhase,
                createAbortController: (): import('@amber/deepread-domain').AbortControllerLike => createEntryAbortController(),
                notifier: ports.getNotifier(),
                onRunActivity: setDeepReadGenerationActivity,
            });
            deepReadScheduler = scheduler;
            return scheduler;
        })();
        deepReadSchedulerInit.catch((): void => { deepReadSchedulerInit = null; });
        return deepReadSchedulerInit;
    };
    const mapCacheEntryObservable = (observable: Observable<DeepReadCacheEntry | null>): Observable<DeepReadOutput> => ({
        subscribe: (callback: (output: DeepReadOutput) => void): (() => void) => observable.subscribe((entry: DeepReadCacheEntry | null): void => {
            if (entry !== null)
                callback(entry.output);
        }),
        getCurrent: (): DeepReadOutput | undefined => {
            const entry: DeepReadCacheEntry | null | undefined = observable.getCurrent();
            return entry === null || entry === undefined ? undefined : entry.output;
        },
    });
    // D-127:DeepReadPlaybookRepository 单例(BoardModule.kt:80-83 Koin single)—
    //   根 filesDir/deep_read_playbook/;默认 markdown = rawfile
    //   deepread/deep_read_playbook.md(Android assets 逐字拷贝);sha256 =
    //   CryptoArchitectureKit(D-061 既有);System.currentTimeMillis → Date.now
    let playbookRepository: DeepReadPlaybookRepository | null = null;
    const getDeepReadPlaybookRepository = (): DeepReadPlaybookRepository => {
        if (playbookRepository === null) {
            playbookRepository = new DeepReadPlaybookRepository({
                store: ports.getFileStore(),
                sha256Hex: sha256HexUtf8,
                nowMs: (): number => Date.now(),
                loadDefaultMarkdown: (): Promise<string> => {
                    const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
                    const bytes: Uint8Array = ctx.resourceManager.getRawFileContentSync('deepread/deep_read_playbook.md');
                    const text: string = util.TextDecoder.create('utf-8').decodeToString(bytes);
                    return Promise.resolve(text);
                },
            });
        }
        return playbookRepository;
    };
    return { getDeepReadArtifactStore, getDeepReadTextRuntime, getDeepReadScheduler, getDeepReadPlaybookRepository };
};

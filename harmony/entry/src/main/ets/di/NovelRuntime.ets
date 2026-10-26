// Owns Novel repository, workspace and creation services.
import type { FileStore, AbortSignalLike, NovelProjectRepository, NovelModelTarget, NovelPolishEffects, DurablePolishJob, NovelStructuredTaskOptions, NovelRuntimeSnapshot, PolishChapterResult } from '@amber/deepread-domain';
import { createNovelCreation, NovelCreation, createFileNovelRepository, NovelAuditController, projectPolishOutcomes } from '@amber/deepread-domain';
import { loadNovelModelDefaults, loadNovelPolishPreference } from '../platform_impl/NovelStandaloneDefaults.ets';
import { createEntryNovelChatRunAdapter } from '../platform_impl/NovelChatRunAdapter.ets';
import { scheduleNovelPolishWork, cancelNovelPolishWork } from '../platform_impl/NovelPolishWorkScheduler.ets';
import { publishNovelJobNotification } from '../platform_impl/NotificationNotifier.ets';
import { createNovelWorkspaceService, NovelWorkspaceService } from '../platform_impl/NovelWorkspaceService.ets';
import type { KeyValueStore, Assistant, OpenAIChatApi, TextGenerationParams, ProviderSetting, AgentTool, ChatStreamProvider, ChatToolDefinition, NovelInteractiveRuntimeConfig, NovelFrozenRuntimeConfiguration, NovelResponsesStreamOptions, NovelWorkspaceToolPort } from '@amber/chat-domain';
import { makeNovelTextGenerationParams, resolveNovelMaxOutputTokens, applyNovelStateReasoning, freezeNovelRuntimeConfiguration, readNovelRuntimeConfiguration, supportsResponsesResume, mergeCustomParams, asChatStreamProvider, makeGenerationRetrySetting, createAskUserTool, createNovelWorkspaceTools, novelAuditToolResult, executeNovelProjectOperation } from '@amber/chat-domain';
import type { ChatRuntime } from './ChatRuntimeContracts.ets';
export interface NovelRuntimePorts {
    getFileStore: () => FileStore;
    getChatKvStore: () => KeyValueStore;
    resolveChatRuntime: (assistantId?: string, exactModelId?: string, exactProviderId?: string) => Promise<ChatRuntime | null>;
    buildProviderApi: (chosen: ProviderSetting, customHeaderRecord: Record<string, string>, oauthAccountId?: string, responses?: NovelResponsesStreamOptions) => OpenAIChatApi;
    loadNovelResearchTools: (kv: KeyValueStore) => Promise<AgentTool[]>;
}
export interface NovelRuntime {
    getNovelProjectRepository: () => NovelProjectRepository;
    getNovelWorkspaceService: () => NovelWorkspaceService;
    getNovelCreation: () => NovelCreation;
}
export const createNovelRuntime = (ports: NovelRuntimePorts): NovelRuntime => {
    // ===== Novel Creation 装配 =====
    let novelCreation: NovelCreation | null = null;
    let novelProjectRepository: NovelProjectRepository | null = null;
    let novelWorkspaceService: NovelWorkspaceService | null = null;
    const getNovelProjectRepository = (): NovelProjectRepository => {
        if (novelProjectRepository === null) {
            novelProjectRepository = createFileNovelRepository(ports.getFileStore());
        }
        return novelProjectRepository;
    };
    const getNovelWorkspaceService = (): NovelWorkspaceService => {
        if (novelWorkspaceService === null) {
            novelWorkspaceService = createNovelWorkspaceService(getNovelProjectRepository(), getNovelCreation());
        }
        return novelWorkspaceService;
    };
    const getNovelCreation = (): NovelCreation => {
        if (novelCreation === null) {
            const polishEffects: NovelPolishEffects = {
                async schedule(job: DurablePolishJob): Promise<void> {
                    scheduleNovelPolishWork({
                        projectId: job.projectId, branchId: job.branchId, jobId: job.jobId,
                    });
                },
                async cancel(job: DurablePolishJob): Promise<void> {
                    cancelNovelPolishWork({
                        projectId: job.projectId, branchId: job.branchId, jobId: job.jobId,
                    });
                },
                async notify(job: DurablePolishJob): Promise<void> {
                    const outcomes: PolishChapterResult[] = projectPolishOutcomes(job);
                    const succeeded: number = outcomes.filter((item: PolishChapterResult): boolean => item.status === 'success').length;
                    const failed: number = outcomes.filter((item: PolishChapterResult): boolean => item.status === 'failed').length;
                    const skipped: number = outcomes.filter((item: PolishChapterResult): boolean => item.status === 'driftSkipped').length;
                    const remaining: number = outcomes.filter((item: PolishChapterResult): boolean => item.status === 'unprocessed').length;
                    const summary: string = `成功 ${succeeded}/${job.targets.length} · 失败 ${failed} · 跳过 ${skipped} · 未处理 ${remaining}`;
                    let title: string = '批量润色';
                    let text: string = summary;
                    if (job.stage === 'completed') {
                        title = failed + skipped + remaining === 0 ? '批量润色完成' : '批量润色处理结束';
                    }
                    else if (job.stage === 'failed') {
                        title = '批量润色失败';
                        text = `${summary}；${job.failure ?? ''}`;
                    }
                    else if (job.stage === 'cancelled') {
                        title = '批量润色已取消';
                    }
                    else if (job.stage === 'paused') {
                        title = '批量润色已暂停';
                    }
                    else if (job.stage === 'waiting_system') {
                        title = '批量润色等待后台调度';
                    }
                    else if (job.cursor < job.targets.length) {
                        const target = job.targets[job.cursor];
                        text = `正在处理第 ${target.ordinal} 章；${summary}`;
                    }
                    await publishNovelJobNotification(job.projectId, job.branchId, job.jobId, title, text.slice(0, 120));
                },
            };
            novelCreation = createNovelCreation({
                loadModelDefaults: loadNovelModelDefaults,
                loadPolishPreference: loadNovelPolishPreference,
                repository: getNovelProjectRepository(),
                modelRunning: createEntryNovelChatRunAdapter(resolveNovelInteractiveRuntime),
                polishEffects,
            });
        }
        return novelCreation;
    };
    const novelWorkspaceToolPort = (projectId: string): NovelWorkspaceToolPort => ({
        projectOperation: (name, input) => executeNovelProjectOperation(getNovelCreation(), projectId, name, input),
        list: async (input) => {
            const result = await getNovelCreation().workspaceList(projectId, input.path ?? '');
            return { root: result.root, entries: result.entries };
        },
        read: async (input) => {
            const result = await getNovelCreation().workspaceRead(projectId, input.path);
            return { path: result.path, content: result.content };
        },
        grep: async (input) => {
            const result = await getNovelCreation().workspaceGrep(projectId, input.query, input.path ?? '', input.max_results ?? 50);
            return {
                query: result.query,
                // JsonObject 的索引签名不能被 ArkTS class/interface 实现；保留与 grep
                // CLI 相同的 path:line:text 事实格式，避免把未类型化对象绕过严格检查。
                matches: result.matches.map((match): string => `${match.path}:${match.line}:${match.text}`),
            };
        },
        status: async () => {
            const status = await getNovelCreation().workspaceStatus(projectId);
            return {
                active_branch_id: status.activeBranchId,
                active_branch_name: status.activeBranchName,
                head: status.cas.head,
                tree_digest: status.cas.treeDigest,
                can_undo: status.canUndo,
                unresolved_from_ordinal: status.unresolvedFromOrdinal,
                plot_stale: status.plotStale,
                pending_proposal_count: status.pendingProposalCount,
            };
        },
        write: async (input) => {
            const proposal = await getNovelCreation().workspaceWriteResult(projectId, input.proposal_id, input.patches);
            return {
                proposal_id: proposal.proposalId,
                status: proposal.status,
                branch_id: proposal.expectedCas.branchId,
                patch_count: proposal.patches.length,
            };
        },
        audit: async (signal?: AbortSignalLike) => {
            const controller: NovelAuditController = new NovelAuditController();
            const cancel: () => void = (): void => { controller.cancel(); };
            if (signal !== undefined) {
                if (signal.aborted)
                    controller.cancel();
                else
                    signal.addEventListener?.('abort', cancel);
            }
            try {
                const report = await getNovelCreation().runContinuityAudit(projectId, controller);
                return novelAuditToolResult(report);
            }
            finally {
                if (signal !== undefined)
                    signal.removeEventListener?.('abort', cancel);
            }
        },
        ingestSettingProposals: async (sourceMessageId: string, text: string) => {
            const proposals = await getNovelCreation().ingestSettingProposals(projectId, sourceMessageId, text);
            return {
                ingested: proposals.length,
                titles: proposals.map((p): string => p.title),
            };
        },
    });
    const novelRuntimeConfig = async (runtime: ChatRuntime, projectId: string, snapshot?: NovelRuntimeSnapshot): Promise<NovelInteractiveRuntimeConfig> => {
        const frozen: NovelFrozenRuntimeConfiguration | null = snapshot === undefined ? null :
            readNovelRuntimeConfiguration(snapshot.configurationJson, runtime.resolvedChoice.provider, runtime.resolvedChoice.model, runtime.assistant);
        const params: TextGenerationParams = frozen === null ? runtime.params : frozen.params;
        const assistant: Assistant = frozen === null ? runtime.assistant : frozen.assistant;
        const contextWindowTokens: number | null = frozen === null ? runtime.modelContextWindowTokens : frozen.contextWindowTokens;
        const defaultCap: number = resolveNovelMaxOutputTokens(null, params.maxTokens, contextWindowTokens);
        const customHeaders: Record<string, string> = {};
        for (const header of mergeCustomParams(runtime.assistant, runtime.resolvedChoice.model).customHeaders) {
            if (header.name.trim().length > 0)
                customHeaders[header.name.trim()] = header.value.trim();
        }
        const makeProvider = (definitions: ChatToolDefinition[], cap: number, taskOptions?: NovelStructuredTaskOptions, responses?: NovelResponsesStreamOptions): ChatStreamProvider => {
            const api: OpenAIChatApi = responses === undefined ? runtime.api :
                ports.buildProviderApi(runtime.resolvedChoice.provider, customHeaders, '', responses);
            return asChatStreamProvider(api, (): TextGenerationParams => applyNovelStateReasoning(makeNovelTextGenerationParams(params, definitions, cap, taskOptions), runtime.resolvedChoice.provider, taskOptions));
        };
        return {
            assistant,
            runtimeSnapshot: snapshot ?? {
                providerId: runtime.resolvedChoice.providerContainerId, modelId: runtime.resolvedChoice.model.id,
                configurationJson: freezeNovelRuntimeConfiguration(runtime.resolvedChoice.provider, runtime.resolvedChoice.model, assistant, params, contextWindowTokens),
                responsesProviderId: runtime.resolvedChoice.provider.id,
            },
            responsesProviderId: runtime.resolvedChoice.provider.id,
            responsesResumeSupported: runtime.resolvedChoice.provider.type === 'openai' &&
                supportsResponsesResume(runtime.resolvedChoice.provider),
            provider: makeProvider([], defaultCap),
            makeProviderForOutputTokens: (cap: number | null, taskOptions?: NovelStructuredTaskOptions, responses?: NovelResponsesStreamOptions): ChatStreamProvider => makeProvider([], cap ?? defaultCap, taskOptions, responses),
            tools: [...createNovelWorkspaceTools(novelWorkspaceToolPort(projectId)), createAskUserTool(),
                ...await ports.loadNovelResearchTools(ports.getChatKvStore())],
            makeProviderForStep: (definitions: ChatToolDefinition[], cap?: number | null, responses?: NovelResponsesStreamOptions): ChatStreamProvider => makeProvider(definitions, cap ?? defaultCap, undefined, responses),
            toolPromptModel: params.model,
            contextWindowTokens,
            maxOutputTokens: params.maxTokens,
            retrySetting: makeGenerationRetrySetting({}),
        };
    };
    // C5:Novel 与 Chat 共用生成深模块。global 复用现有 Chat 选择；fixed 只接受
    // 指定 provider/model UUID 的精确命中。两种固定失败都返回 null，调用端原位 fail closed。
    // 旧 ModelRegistry 不再参与 Novel 的模型解析，防止固定选择被 legacy/global 静默替换。
    const resolveNovelInteractiveRuntime = async (modelTarget: NovelModelTarget, projectId: string, snapshot?: NovelRuntimeSnapshot): Promise<NovelInteractiveRuntimeConfig | null> => {
        const runtime: ChatRuntime | null = snapshot !== undefined
            ? await ports.resolveChatRuntime(undefined, snapshot.modelId, snapshot.providerId)
            : modelTarget.kind === 'global'
                ? await ports.resolveChatRuntime()
                : await ports.resolveChatRuntime(undefined, modelTarget.modelId, modelTarget.providerId);
        return runtime === null ? null : novelRuntimeConfig(runtime, projectId, snapshot);
    };
    return { getNovelProjectRepository, getNovelWorkspaceService, getNovelCreation };
};

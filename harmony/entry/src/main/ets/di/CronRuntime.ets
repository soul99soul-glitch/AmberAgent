// Owns Cron initialization and the background conversation executor.
import type { AbortSignalLike } from '@amber/deepread-domain';
import { notifyCronRunning, notifyCronCompleted, notifyCronFailed } from '../platform_impl/NotificationNotifier.ets';
import { createEntryAbortController } from '../platform_impl/EntryAbortController.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { ConversationRepository, KeyValueStore, TextGenerationParams, AgentTool, ChatStreamProvider, ChatToolDefinition, ChatTurnDeps } from '@amber/chat-domain';
import { makeTextGenerationParams, McpManager, AgentToolActivityStore, PosixWorkspaceManager, AgentTaskStore, AgentCronManager, asChatStreamProvider, AgentCronTools, runChatTurnWithTools, conversationHasPendingOrUnexecutedTools, createTimeTool, createWorkspaceTools, createSearchTools, loadSearchPrefs, createToolRegistry, filterToolProfile, createMcpServerTools, thinkTagTransformer, regexOutputTransformer } from '@amber/chat-domain';
import { createEntryCronPersistencePort, createEntryCronSchedulerPort, AGENT_CRON_EXECUTION_AVAILABLE } from '../platform_impl/AgentCronStore.ets';
import { loadAgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import { beginConversationRun } from '../platform_impl/ConversationRunGuard.ets';
import type { ChatRuntime } from './ChatRuntimeContracts.ets';
const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Container';
export interface CronRuntimePorts {
    getChatKvStore: () => KeyValueStore;
    getChatRepository: () => ConversationRepository;
    resolveChatRuntime: (assistantId?: string, exactModelId?: string, exactProviderId?: string) => Promise<ChatRuntime | null>;
    getWorkspaceManager: () => PosixWorkspaceManager;
    getToolActivityStore: () => AgentToolActivityStore;
    ensureSearchSdk: () => void;
    initMcpManager: () => Promise<void>;
    getMcpManager: () => McpManager;
    getAgentTaskStore: () => Promise<AgentTaskStore>;
}
export interface CronRuntime {
    getAgentCronManager: () => Promise<AgentCronManager>;
    getAgentCronTools: () => Promise<AgentTool[]>;
    isAgentCronExecutionAvailable: () => boolean;
    rescheduleCronTasksOnStartup: () => void;
}
export const createCronRuntime = (ports: CronRuntimePorts): CronRuntime => {
    let agentCronManagerPromise: Promise<AgentCronManager> | null = null;
    // 无头执行不暴露依赖页面的工具，待审批结果不得当作成功完成。
    const createEntryCronRunExecutor = (): ((taskId: string, signal?: AbortSignalLike) => Promise<void>) => {
        return async (taskId: string, signal?: AbortSignalLike): Promise<void> => {
            const manager: AgentCronManager = await getAgentCronManager();
            const tasks = await manager.listTasksSnapshot();
            const task = tasks.find((t): boolean => t.id === taskId);
            if (task === undefined) {
                throw new Error(`Cron task not found: ${taskId}`);
            }
            if (signal?.aborted)
                throw new Error('Cron run cancelled');
            const releaseRun: () => void = beginConversationRun(task.conversationId.length > 0 ? task.conversationId : `cron-${task.id}`);
            notifyCronRunning(task.id, task.title);
            try {
                let conversation = task.conversationId.length > 0
                    ? await ports.getChatRepository().getById(task.conversationId)
                    : null;
                if (conversation === null) {
                    conversation = {
                        id: task.conversationId.length > 0 ? task.conversationId : `cron-${task.id}`,
                        assistantId: '',
                        title: task.title,
                        createAt: new Date().toISOString(),
                        updateAt: new Date().toISOString(),
                        chatSuggestions: [],
                        isPinned: false,
                        autoApproveToolCalls: false,
                        messageNodes: [],
                    };
                    await ports.getChatRepository().save(conversation);
                }
                const runtime = await ports.resolveChatRuntime(conversation.assistantId.length > 0 ? conversation.assistantId : undefined);
                if (runtime === null) {
                    throw new Error('Cron run failed: no usable chat provider/model');
                }
                if (signal?.aborted)
                    throw new Error('Cron run cancelled');
                const searchSettings = await loadSearchPrefs(ports.getChatKvStore());
                const execution = await loadAgentRuntimeSnapshot(ports.getChatKvStore());
                ports.ensureSearchSdk();
                await ports.initMcpManager();
                if (signal?.aborted)
                    throw new Error('Cron run cancelled');
                const localTools = runtime.assistant.localTools;
                const availableTools: AgentTool[] = [
                    createTimeTool(),
                    ...(localTools.indexOf('workspace_files') >= 0
                        ? createWorkspaceTools(ports.getWorkspaceManager(), ports.getToolActivityStore()) : []),
                    ...(searchSettings.enableWebSearch ? createSearchTools(searchSettings) : []),
                    ...createMcpServerTools({
                        manager: ports.getMcpManager(), assistantIds: [...runtime.assistant.mcpServers],
                    }),
                ];
                const tools: AgentTool[] = createToolRegistry(filterToolProfile(availableTools, runtime.assistant.toolProfile).tools).tools();
                const deps: ChatTurnDeps = {
                    assistant: runtime.assistant,
                    inputTransformers: [],
                    outputTransformers: [thinkTagTransformer, regexOutputTransformer],
                    provider: asChatStreamProvider(runtime.api, (): TextGenerationParams => runtime.params),
                    store: ports.getChatRepository(),
                    contextMessageSize: runtime.contextMessageSize,
                    abortSignal: signal,
                };
                const out = await runChatTurnWithTools(conversation, `这是定时任务。只能使用当前提供的工具；需要人工审批时停止，不得宣称操作已完成。\n${task.prompt}`, deps, {
                    tools,
                    invocationContext: 'cron',
                    autoApproveTools: false,
                    autoApproveHighRiskTools: false,
                    maxSteps: execution.maxToolLoopSteps,
                    toolPromptModel: runtime.params.model,
                    makeProviderForStep: (definitions: ChatToolDefinition[]): ChatStreamProvider => asChatStreamProvider(runtime.api, (): TextGenerationParams => makeTextGenerationParams({
                        model: runtime.params.model,
                        temperature: runtime.params.temperature,
                        topP: runtime.params.topP,
                        maxTokens: runtime.params.maxTokens,
                        reasoningLevel: runtime.params.reasoningLevel,
                        customBody: runtime.params.customBody,
                        tools: definitions,
                    })),
                });
                if (signal?.aborted)
                    throw new Error('Cron run cancelled');
                await ports.getChatRepository().save(out);
                if (conversationHasPendingOrUnexecutedTools(out)) {
                    throw new Error('定时任务需要人工审批或未完成工具执行，请打开任务会话处理');
                }
                notifyCronCompleted(task.id, task.title);
            }
            catch (e) {
                const message: string = e instanceof Error ? e.message : String(e);
                if (!signal?.aborted)
                    notifyCronFailed(task.id, task.title, message);
                throw new Error(message);
            }
            finally {
                releaseRun();
            }
        };
    };
    const getAgentCronManager = (): Promise<AgentCronManager> => {
        if (agentCronManagerPromise === null) {
            agentCronManagerPromise = (async (): Promise<AgentCronManager> => new AgentCronManager(createEntryCronPersistencePort(ports.getChatKvStore()), createEntryCronSchedulerPort({
                manager: (): Promise<AgentCronManager> => getAgentCronManager(),
                runExecutor: createEntryCronRunExecutor(),
                createAbortController: createEntryAbortController,
            }), await ports.getAgentTaskStore()))();
        }
        return agentCronManagerPromise;
    };
    // Cron 执行器已接线:暴露四件工具(创建/列表/立即运行/删除)
    const getAgentCronTools = async (): Promise<AgentTool[]> => {
        const manager = await getAgentCronManager();
        return new AgentCronTools(manager).getTools();
    };
    const isAgentCronExecutionAvailable = (): boolean => AGENT_CRON_EXECUTION_AVAILABLE;
    // 未接执行器时明确跳过启动重排，避免应用内定时器制造失败运行。
    const rescheduleCronTasksOnStartup = (): void => {
        if (!AGENT_CRON_EXECUTION_AVAILABLE) {
            hilog.warn(DOMAIN, TAG, 'Cron execution unavailable; startup reschedule skipped');
            return;
        }
        getAgentCronManager()
            .then((manager: AgentCronManager): Promise<void> => manager.rescheduleAll())
            .catch((e: Error) => {
            hilog.error(DOMAIN, TAG, 'rescheduleCronTasks failed: %{public}s', e.message);
        });
    };
    return { getAgentCronManager, getAgentCronTools, isAgentCronExecutionAvailable, rescheduleCronTasksOnStartup };
};

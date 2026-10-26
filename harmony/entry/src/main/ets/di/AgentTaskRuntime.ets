// Owns the durable task store and scheduler shared by automation and local execution.
import type { AgentTool } from '@amber/chat-domain';
import { AgentTaskStore, AgentTaskScheduler, AgentTaskTools } from '@amber/chat-domain';
import { createEntryAgentTaskFilePort, agentCronTaskDir, agentCronFilesDir } from '../platform_impl/AgentCronStore.ets';
export interface AgentTaskRuntime {
    getAgentTaskStore: () => Promise<AgentTaskStore>;
    getAgentTaskScheduler: () => Promise<AgentTaskScheduler>;
    getAgentTaskTools: () => Promise<AgentTool[]>;
}
export const createAgentTaskRuntime = (): AgentTaskRuntime => {
    // ===== D-130:agent_cron 装配(AgentInfraModule.kt:40/:64) =====
    // AgentTaskStore/AgentCronManager 为异步创建(文件/KV 加载)→ 缓存
    //   Promise 单例;AmberAgentApp.kt:216-224 rescheduleCronTasks 等价 =
    //   rescheduleCronTasksOnStartup(EntryAbility.onCreate fire-and-forget)。
    let agentTaskStorePromise: Promise<AgentTaskStore> | null = null;
    const getAgentTaskStore = (): Promise<AgentTaskStore> => {
        if (agentTaskStorePromise === null) {
            const init: Promise<AgentTaskStore> = AgentTaskStore.create({
                taskDir: agentCronTaskDir(),
                appFilesDir: agentCronFilesDir(),
                files: createEntryAgentTaskFilePort(),
            });
            // 失败不缓存 rejected Promise,否则依赖方(如 subagent manager)的重试
            // 会永远复用同一个失败结果
            init.catch((): void => {
                if (agentTaskStorePromise === init)
                    agentTaskStorePromise = null;
            });
            agentTaskStorePromise = init;
        }
        return agentTaskStorePromise;
    };
    // D-131:AgentTaskScheduler 复用上面的 AgentTaskStore Promise(Koin single 等价)。
    let agentTaskSchedulerPromise: Promise<AgentTaskScheduler> | null = null;
    const getAgentTaskScheduler = (): Promise<AgentTaskScheduler> => {
        if (agentTaskSchedulerPromise === null) {
            agentTaskSchedulerPromise = getAgentTaskStore().then((store: AgentTaskStore): AgentTaskScheduler => new AgentTaskScheduler(store));
        }
        return agentTaskSchedulerPromise;
    };
    // agent_task_* 六件(LocalTools/ChatService.kt:2299 无条件，紧随 conversation_queue_* 之后)。
    const getAgentTaskTools = async (): Promise<AgentTool[]> => {
        const files = createEntryAgentTaskFilePort();
        return new AgentTaskTools(await getAgentTaskScheduler(), {
            outputExists: (path: string): Promise<boolean> => files.exists(path),
        }).getTools();
    };
    return { getAgentTaskStore, getAgentTaskScheduler, getAgentTaskTools };
};

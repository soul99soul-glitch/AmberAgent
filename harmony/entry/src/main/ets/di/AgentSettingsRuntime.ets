// Owns prompt, permission and hydrated subagent settings resources.
import type { KeyValueStore, AgentTool, SubAgentRuntimeSetting, AgentPromptSettingsPort } from '@amber/chat-domain';
import { makeSubAgentRuntimeSetting, AgentPromptConfigRepository, createAgentPromptConfigTool, AgentPermissionBroker, createPermissionsStatusTool } from '@amber/chat-domain';
import type { ModelCouncilRuntimeSetting, ModelCouncilSeat } from '@amber/deepread-domain';
import { createEntryPromptConfigFilePort, createEntryCouncilStrategy, createEntryAgentPromptSettingsPort, promptConfigFilesDir } from '../platform_impl/PromptConfigStore.ets';
import { createEntryPermissionPlatformPort, auditPermissionUseLog } from '../platform_impl/PermissionStatusPortImpl.ets';
export interface AgentSettingsRuntimePorts {
    getChatKvStore: () => KeyValueStore;
}
export interface AgentSettingsRuntime {
    getHydratedSubAgentSetting: () => SubAgentRuntimeSetting;
    getAgentPromptConfigRepository: () => AgentPromptConfigRepository<ModelCouncilRuntimeSetting, ModelCouncilSeat>;
    getAgentPromptConfigTool: () => AgentTool;
    getPermissionBroker: () => AgentPermissionBroker;
    getPermissionsStatusTool: () => AgentTool;
    getAgentPromptSettingsPort: () => AgentPromptSettingsPort<ModelCouncilRuntimeSetting>;
    hydrateSubAgentSetting: () => Promise<SubAgentRuntimeSetting>;
    refreshSubAgentSetting: () => Promise<SubAgentRuntimeSetting>;
    isModelCouncilEnabled: () => Promise<boolean>;
}
export const createAgentSettingsRuntime = (ports: AgentSettingsRuntimePorts): AgentSettingsRuntime => {
    // D-128:AgentPromptConfigRepository 单例(Koin 注册表 prompts 模块)—
    //   filesDir/agent_prompts/ 四镜像文件;设置读写 = agent_runtime 文档 RMW;
    //   council 策略直配 deepread HAR 运行时类型(全字段显式拷贝零丢失)
    let promptConfigRepository: AgentPromptConfigRepository<ModelCouncilRuntimeSetting, ModelCouncilSeat> | null = null;
    const getAgentPromptConfigRepository = (): AgentPromptConfigRepository<ModelCouncilRuntimeSetting, ModelCouncilSeat> => {
        if (promptConfigRepository === null) {
            promptConfigRepository = new AgentPromptConfigRepository<ModelCouncilRuntimeSetting, ModelCouncilSeat>(promptConfigFilesDir(), createEntryPromptConfigFilePort(), createEntryCouncilStrategy());
        }
        return promptConfigRepository;
    };
    // agent_prompt_config 工具的 settings 端口:共享单例 + subagent 更新后同步刷新
    // hydrate 缓存 — 否则工具路径改配置后,manager 的同步读仍拿到旧快照(进程内不生效)
    const toolSettings = (): AgentPromptSettingsPort<ModelCouncilRuntimeSetting> => ({
        getSubAgentSetting: hydrateSubAgentSetting,
        updateSubAgentSetting: async (next: SubAgentRuntimeSetting): Promise<void> => {
            await getAgentPromptSettingsPort().updateSubAgentSetting(next);
            cachedSubAgentSetting = next;
        },
        getCouncilSetting: (): Promise<ModelCouncilRuntimeSetting> => getAgentPromptSettingsPort().getCouncilSetting(),
        updateCouncilSetting: (next: ModelCouncilRuntimeSetting): Promise<void> => getAgentPromptSettingsPort().updateCouncilSetting(next),
    });
    // agent_prompt_config 工具 deps(LocalTools.kt:98-100 lazy + :183 装配)
    const getAgentPromptConfigTool = (): AgentTool => createAgentPromptConfigTool<ModelCouncilRuntimeSetting, ModelCouncilSeat>({
        repository: getAgentPromptConfigRepository(),
        settings: toolSettings(),
    });
    // D-129:AgentPermissionBroker 单例(Koin system 模块 — AgentPermissionBroker
    //   (context,isDebugBuild));平台调用下沉 createEntryPermissionPlatformPort
    //   (atManager.checkAccessTokenSync;special access 六类无对应 → Unsupported)
    let permissionBroker: AgentPermissionBroker | null = null;
    const getPermissionBroker = (): AgentPermissionBroker => {
        if (permissionBroker === null) {
            permissionBroker = new AgentPermissionBroker(createEntryPermissionPlatformPort(), auditPermissionUseLog);
        }
        return permissionBroker;
    };
    // permissions_status 工具(LocalTools.kt:94 lazy + :180 装配位)
    const getPermissionsStatusTool = (): AgentTool => createPermissionsStatusTool(getPermissionBroker());
    // ===== D-132c:Subagent 装配 =====
    // Android 锚点:
    //   SubAgentManager.kt(生命周期/transcript/task board)+ SubAgentRunner.kt
    //     (isolated assistant/child loop)+ SubAgentTools.kt(五件公共工具)
    //   Koin:SubAgentManager single;SubAgentTools per-conversation(ChatService:2299)
    // 复用约束(交接宪章):
    //   - PromptConfigStore.ets 唯一 agent_runtime parser/serializer/RMW;
    //     createEntryAgentPromptSettingsPort(getChatKvStore()) 复用于
    //     agent_prompt_config 工具与 subagent manager,不新建第二套 settings store。
    //   - 共享 AgentTaskStore(getAgentTaskStore)/SessionAccessGrantStore(getSessionGrantStore)。
    //   - provider/model/assistant 解析与 resolveChatRuntime 同口径
    //     (resolveTaskChatModelChoice/resolveChatProviderChoice/buildProviderApi)。
    //   - 仅薄 hydrated snapshot bridge 缓存当前设置,不负责 parser/serializer/RMW。
    // 共享 AgentPromptSettingsPort 单例(D-128 agent_prompt_config 工具与 subagent
    //   manager 共用同一 KV 'agent_runtime' RMW)
    let agentPromptSettingsPort: AgentPromptSettingsPort<ModelCouncilRuntimeSetting> | null = null;
    const getAgentPromptSettingsPort = (): AgentPromptSettingsPort<ModelCouncilRuntimeSetting> => {
        if (agentPromptSettingsPort === null) {
            agentPromptSettingsPort = createEntryAgentPromptSettingsPort(ports.getChatKvStore());
        }
        return agentPromptSettingsPort;
    };
    // 薄 hydrated snapshot bridge:只缓存/刷新共享 port 的当前 subagent 设置,
    //   不负责 parser/serializer/RMW(交接宪章:manager.getSubAgentSetting 同步读)。
    //   首次读 → 水合;后续返回缓存;refreshSubAgentSetting 用于设置变更后刷新。
    let cachedSubAgentSetting: SubAgentRuntimeSetting | null = null;
    const hydrateSubAgentSetting = async (): Promise<SubAgentRuntimeSetting> => {
        if (cachedSubAgentSetting === null) {
            cachedSubAgentSetting = await getAgentPromptSettingsPort().getSubAgentSetting();
        }
        return cachedSubAgentSetting;
    };
    const refreshSubAgentSetting = async (): Promise<SubAgentRuntimeSetting> => {
        cachedSubAgentSetting = await getAgentPromptSettingsPort().getSubAgentSetting();
        return cachedSubAgentSetting;
    };
    // council enabled 状态由工具层单独读取既有 council settings(不塞回 Manager)
    const isModelCouncilEnabled = async (): Promise<boolean> => {
        const council: ModelCouncilRuntimeSetting = await getAgentPromptSettingsPort().getCouncilSetting();
        return council.enabled;
    };
    const getHydratedSubAgentSetting = (): SubAgentRuntimeSetting => cachedSubAgentSetting ?? makeSubAgentRuntimeSetting();
    return { getHydratedSubAgentSetting, getAgentPromptConfigRepository, getAgentPromptConfigTool, getPermissionBroker, getPermissionsStatusTool, getAgentPromptSettingsPort, hydrateSubAgentSetting, refreshSubAgentSetting, isModelCouncilEnabled };
};

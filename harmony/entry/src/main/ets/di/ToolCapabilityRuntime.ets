// Owns MCP, skill/workspace and packaged recipe/plugin capability resources.
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Context, common } from '@kit.AbilityKit';
import type { ConversationRepository, KeyValueStore, Assistant, SkillWorkspacePort, AgentTool, JsonObject, RecipeStore, PluginStore, InstalledPlugin, PluginPackage, PluginDescriptor, UIMessagePartTool, McpSettingsPort, McpFilesPort } from '@amber/chat-domain';
import { loadAssistants, saveAssistants, patchAssistant, McpManager, SkillManager, AgentToolActivityStore, PosixWorkspaceManager, createSearchTools, loadSearchPrefs, createToolRegistry, filterToolProfile, createMcpServerTools, createRegistryIntrospectionTools, createToolSearchTool, newId, SSHProfileStore, TerminalRuntime, PythonRuntime, MoshRuntime } from '@amber/chat-domain';
import { createMcpHttpPort } from '../platform_impl/McpHttpPortImpl.ets';
import { createEntrySkillFilePort, createEntrySkillWorkspacePort, createEntryBuiltinSkillAssetPort } from '../platform_impl/SkillStore.ets';
import { createEntryWorkspaceManager } from '../platform_impl/WorkspaceStore.ets';
import { createEntryRecipeStore } from '../platform_impl/RecipeStore.ets';
import { createAssistantLocalPrimitives } from '../platform_impl/AssistantLocalPrimitives.ets';
import { startEntryRecipeFromPage } from '../platform_impl/RecipePageLaunch.ets';
import { createEntryPluginStore } from '../platform_impl/PluginStore.ets';
import { createEntryPluginAdapter, exportEntryPlugin } from '../platform_impl/EntryPluginRuntime.ets';
import { startEntryPluginFromPage } from '../platform_impl/PluginPageLaunch.ets';
import type { WebMountSession } from '../platform_impl/WebMountSession.ets';
import { getWebMountSession } from '../platform_impl/WebMountSession.ets';
import type { ChatRuntime } from './ChatRuntimeContracts.ets';
const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Container';
export interface ToolCapabilityRuntimePorts {
    getChatKvStore: () => KeyValueStore;
    getChatRepository: () => ConversationRepository;
    getChatAssistants: () => Promise<Assistant[]>;
    getToolActivityStore: () => AgentToolActivityStore;
    getTerminalRuntime: () => Promise<TerminalRuntime>;
    getMoshRuntime: () => Promise<MoshRuntime>;
    getSSHProfileStore: () => Promise<SSHProfileStore>;
    getPythonRuntime: () => Promise<PythonRuntime>;
    resolveChatRuntime: (assistantId?: string, exactModelId?: string, exactProviderId?: string) => Promise<ChatRuntime | null>;
    ensureSearchSdk: () => void;
    getMcpSettingsStore: () => McpSettingsPort;
    initMcpSettingsStore: () => Promise<void>;
    getMcpFilesStore: () => McpFilesPort;
    createWebMountTools: (session: WebMountSession, allowEval: boolean, allowBrowserControl?: boolean) => AgentTool[];
}
export interface ToolCapabilityRuntime {
    getMcpManager: () => McpManager;
    initMcpManager: () => Promise<void>;
    getSkillManager: () => SkillManager;
    getSkillWorkspacePort: () => SkillWorkspacePort;
    getWorkspaceManager: () => PosixWorkspaceManager;
    resetWorkspaceManager: () => void;
    getRecipeStore: () => Promise<RecipeStore>;
    getRecipePrimitives: () => Promise<AgentTool[]>;
    startRecipeFromPage: (name: string, expectedHash: string, inputs: JsonObject, shouldNavigate: () => boolean) => Promise<void>;
    getPluginStore: () => Promise<PluginStore>;
    getPluginPrimitives: () => Promise<AgentTool[]>;
    startPluginFromPage: (toolName: string, input: JsonObject, title: string, shouldNavigate: () => boolean, expectedPackageHash?: string | null) => Promise<void>;
    exportPluginFromPage: (id: string, hash: string, destination: string) => Promise<void>;
}
export const createToolCapabilityRuntime = (ports: ToolCapabilityRuntimePorts): ToolCapabilityRuntime => {
    // D-120:McpManager 单例(McpManager.kt 全文移植的容器位);
    //   init 顺序:settings store hydrate(首发射回放)→ manager 构建(订阅即收真值,
    //   与 Android settingsFlow collect 时序等价);ChatPage.aboutToAppear 调 initMcpManager
    let mcpManager: McpManager | null = null;
    const getMcpManager = (): McpManager => {
        if (mcpManager === null) {
            mcpManager = new McpManager({
                http: createMcpHttpPort(),
                settings: ports.getMcpSettingsStore(),
                files: ports.getMcpFilesStore(),
                log: (msg: string): void => {
                    hilog.info(0x0001, 'Mcp.Manager', '%{public}s', msg);
                },
            });
        }
        return mcpManager;
    };
    const initMcpManager = async (): Promise<void> => {
        await ports.initMcpSettingsStore();
        getMcpManager();
    };
    // D-123:SkillManager 单例(SkillManager.kt + Koin single)— filesDir/skills 根;
    //   deleteSkill 级联清理全 assistants enabledSkills(:114-125)→ 异步持久化
    //   (域层同步回调 → fire-and-forget + warn 日志,登记)
    let skillManager: SkillManager | null = null;
    const getSkillManager = (): SkillManager => {
        if (skillManager === null) {
            const ctx = AppStorage.get<Context>('abilityContext') as Context;
            skillManager = new SkillManager({
                port: createEntrySkillFilePort(),
                skillsRoot: `${ctx.filesDir}/skills`,
                // D-126:内置技能 rawfile 资产 + Log.w(SkillManager.kt:82)→ hilog
                assets: createEntryBuiltinSkillAssetPort(),
                log: (message: string): void => {
                    hilog.warn(DOMAIN, TAG, '%{public}s', message);
                },
                onSkillDeleted: (name: string): void => {
                    loadAssistants(ports.getChatKvStore())
                        .then(async (list: Assistant[] | null): Promise<void> => {
                        if (list === null)
                            return;
                        const next: Assistant[] = list.map((a: Assistant): Assistant => a.enabledSkills.indexOf(name) >= 0
                            ? patchAssistant(a, {
                                enabledSkills: a.enabledSkills.filter((n: string): boolean => n !== name),
                            })
                            : a);
                        await saveAssistants(ports.getChatKvStore(), next);
                    })
                        .catch((e: Error): void => {
                        hilog.warn(DOMAIN, TAG, 'onSkillDeleted persist failed: %{public}s', String(e));
                    });
                },
            });
        }
        return skillManager;
    };
    // D-123:skills 导入用 workspace 读面(POSIX 镜像目录;SAF 层不移植,登记)
    let skillWorkspacePort: SkillWorkspacePort | null = null;
    const getSkillWorkspacePort = (): SkillWorkspacePort => {
        if (skillWorkspacePort === null) {
            skillWorkspacePort = createEntrySkillWorkspacePort();
        }
        return skillWorkspacePort;
    };
    // D-124:PosixWorkspaceManager 单例(WorkspaceManager.kt + Koin single)—
    //   根 = filesDir/amberagent/workspace-mirror(SAF 层不移植,镜像即 workspace,
    //   configured 恒 true,登记);ChatPage workspace_files 门 → createWorkspaceTools
    let workspaceManager: PosixWorkspaceManager | null = null;
    const getWorkspaceManager = (): PosixWorkspaceManager => {
        if (workspaceManager === null) {
            workspaceManager = createEntryWorkspaceManager();
        }
        return workspaceManager;
    };
    // D2-1:自选工作区目录变更后重建管理器(rootAbs 在构造时注入)
    const resetWorkspaceManager = (): void => {
        workspaceManager = null;
        recipeStorePromise = null;
    };
    let recipeStorePromise: Promise<RecipeStore> | null = null;
    const getRecipeStore = (): Promise<RecipeStore> => {
        if (recipeStorePromise === null) {
            const context: common.UIAbilityContext = AppStorage.get<common.UIAbilityContext>('abilityContext') as common.UIAbilityContext;
            const init: Promise<RecipeStore> = createEntryRecipeStore(context, getWorkspaceManager());
            init.catch((): void => {
                if (recipeStorePromise === init)
                    recipeStorePromise = null;
            });
            recipeStorePromise = init;
        }
        return recipeStorePromise;
    };
    const recipePagePrimitives = async (assistant: Assistant): Promise<AgentTool[]> => {
        const terminal: boolean = assistant.localTools.indexOf('terminal') >= 0;
        const python: boolean = assistant.localTools.indexOf('python') >= 0;
        return filterToolProfile(createAssistantLocalPrimitives({
            assistant, conversationId: '', workspace: getWorkspaceManager(), activityStore: ports.getToolActivityStore(),
            terminalRuntime: terminal ? await ports.getTerminalRuntime() : null,
            moshRuntime: terminal ? await ports.getMoshRuntime() : null,
            sshProfiles: terminal ? await ports.getSSHProfileStore() : null,
            pythonRuntime: python ? await ports.getPythonRuntime() : null,
        }), assistant.toolProfile).tools;
    };
    const getRecipePrimitives = async (): Promise<AgentTool[]> => {
        const assistants: Assistant[] = await ports.getChatAssistants();
        return recipePagePrimitives(assistants[0]);
    };
    const startRecipeFromPage = async (name: string, expectedHash: string, inputs: JsonObject, shouldNavigate: () => boolean): Promise<void> => {
        const assistants: Assistant[] = await ports.getChatAssistants();
        const assistant: Assistant = assistants[0];
        await startEntryRecipeFromPage({ store: await getRecipeStore(), primitives: await recipePagePrimitives(assistant),
            assistant, repository: ports.getChatRepository() }, name, expectedHash, inputs, shouldNavigate);
    };
    let pluginStorePromise: Promise<PluginStore> | null = null;
    const getPluginStore = (): Promise<PluginStore> => {
        if (pluginStorePromise === null) {
            const context: common.UIAbilityContext = AppStorage.get<common.UIAbilityContext>('abilityContext') as common.UIAbilityContext;
            const init: Promise<PluginStore> = createEntryPluginStore(context, getPluginPrimitives);
            init.catch((): void => {
                if (pluginStorePromise === init)
                    pluginStorePromise = null;
            });
            pluginStorePromise = init;
        }
        return pluginStorePromise;
    };
    const pluginPagePrimitives = async (assistant: Assistant): Promise<AgentTool[]> => {
        ports.ensureSearchSdk();
        await initMcpManager();
        const kv = ports.getChatKvStore();
        const search = await loadSearchPrefs(kv);
        const webMount: boolean = assistant.localTools.indexOf('webmount') >= 0;
        const webMountEnabled: boolean = webMount || await kv.get('webmount_global_enabled') === 'true';
        const base: AgentTool[] = filterToolProfile([
            ...await recipePagePrimitives(assistant),
            ...createMcpServerTools({ manager: getMcpManager(), assistantIds: [...assistant.mcpServers] }),
            ...(search.enableWebSearch ? createSearchTools(search) : []),
            ...(webMountEnabled ? ports.createWebMountTools(getWebMountSession(), false, webMount) : []),
        ], assistant.toolProfile).tools;
        const registry = createToolRegistry(base);
        return [...registry.tools(), ...createRegistryIntrospectionTools(registry),
            createToolSearchTool(registry, assistant.toolProfile)];
    };
    const getPluginPrimitives = async (): Promise<AgentTool[]> => {
        const assistants: Assistant[] = await ports.getChatAssistants();
        return pluginPagePrimitives(assistants[0]);
    };
    const startPluginFromPage = async (toolName: string, input: JsonObject, title: string, shouldNavigate: () => boolean, expectedPackageHash: string | null = null): Promise<void> => {
        const assistants: Assistant[] = await ports.getChatAssistants();
        const assistant: Assistant = assistants[0];
        if (await ports.resolveChatRuntime(assistant.id) === null)
            throw new Error('请先配置可用的 Chat 供应商和模型。');
        const primitives: AgentTool[] = await pluginPagePrimitives(assistant);
        const store: PluginStore = await getPluginStore();
        const installed: InstalledPlugin[] = await store.listInstalled(primitives);
        if (expectedPackageHash !== null && !installed.some((item: InstalledPlugin): boolean => {
            const candidate: PluginPackage | null = item.package;
            return item.enabled && candidate !== null && candidate.hash === expectedPackageHash
                && candidate.tools.some((tool: PluginDescriptor): boolean => tool.toolId === toolName);
        })) {
            throw new Error('插件已更新或停用，请重新选择。');
        }
        const parent: UIMessagePartTool = { type: 'tool', toolCallId: newId(), toolName,
            input: JSON.stringify(input), output: [], approvalState: { type: 'auto' }, metadata: { plugin_manual: true } };
        await startEntryPluginFromPage({ assistant, primitives, adapter: createEntryPluginAdapter(store, installed, primitives),
            repository: ports.getChatRepository() }, parent, title, shouldNavigate);
    };
    const exportPluginFromPage = async (id: string, hash: string, destination: string): Promise<void> => {
        await exportEntryPlugin(await getPluginStore(), getWorkspaceManager(), id, hash, destination);
    };
    return { getMcpManager, initMcpManager, getSkillManager, getSkillWorkspacePort, getWorkspaceManager, resetWorkspaceManager, getRecipeStore, getRecipePrimitives, startRecipeFromPage, getPluginStore, getPluginPrimitives, startPluginFromPage, exportPluginFromPage };
};

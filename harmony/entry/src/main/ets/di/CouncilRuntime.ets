// Owns the shared Council runner and active manager.
import type { HttpClient, FileStore, ModelRegistry, ModelCouncilTextRunner, ModelCouncilRuntimeSetting } from '@amber/deepread-domain';
import { createModelCouncilManager, ModelCouncilManager } from '@amber/deepread-domain';
import { createModelCouncilRunner } from '../platform_impl/ModelCouncilRunner.ets';
export interface CouncilRuntimePorts {
    getHttpClient: () => HttpClient;
    getModelRegistry: () => ModelRegistry;
    getFileStore: () => FileStore;
    prepareCouncilToolRunner: () => Promise<ModelCouncilTextRunner>;
}
export interface CouncilRuntime {
    getCouncilRunner: () => ModelCouncilTextRunner;
    prepareCouncilManager: (setting: ModelCouncilRuntimeSetting) => Promise<ModelCouncilManager>;
    getCouncilManager: () => ModelCouncilManager | null;
}
export const createCouncilRuntime = (ports: CouncilRuntimePorts): CouncilRuntime => {
    // ===== Model Council 装配 =====
    let councilRunner: ModelCouncilTextRunner | null = null;
    let councilManager: ModelCouncilManager | null = null;
    const getCouncilRunner = (): ModelCouncilTextRunner => {
        if (councilRunner === null) {
            councilRunner = createModelCouncilRunner(ports.getHttpClient());
        }
        return councilRunner;
    };
    // 用当前模型库 + 给定设置创建(替换)共享 manager;CouncilPage 点击开始时调用,
    // 随后导航到 CouncilRunPage 通过 getCouncilManager() 共享同一实例。
    const prepareCouncilManager = async (setting: ModelCouncilRuntimeSetting): Promise<ModelCouncilManager> => {
        if (councilManager !== null && councilManager.listActive().length > 0)
            return councilManager;
        const pool = await ports.getModelRegistry().list();
        const runner: ModelCouncilTextRunner = setting.toolMode === 'search' || setting.toolMode === 'full'
            ? await ports.prepareCouncilToolRunner() : getCouncilRunner();
        if (councilManager !== null && councilManager.listActive().length > 0)
            return councilManager;
        councilManager = createModelCouncilManager({
            runner: runner,
            fileStore: ports.getFileStore(),
            modelPool: pool,
            setting: setting,
        });
        return councilManager;
    };
    const getCouncilManager = (): ModelCouncilManager | null => councilManager;
    return { getCouncilRunner, prepareCouncilManager, getCouncilManager };
};

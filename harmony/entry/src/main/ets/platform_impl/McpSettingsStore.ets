// McpSettingsStore — McpSettingsPort 的 entry 实现
//
// D-120:domain McpManager 的 settings 面(SettingsAggregator 快照/更新/订阅)
// 接到 ChatKvStore(MCP_SERVERS_KEY,D-116 线格式)。
//
// 偏差登记:
// - SettingsAggregator.settingsFlow.value 同步快照 → 内存缓存(KV 为异步;
//   init() 在容器构建期 hydrated,缓存为权威,写穿异步持久化)
// - settingsFlow collect 初值发射 → subscribe 立即回放当前快照(StateFlow 语义)
// - getCurrentAssistant() 由 ChatPage 经 provider 注入(rt.assistant 页面态;
//   Android 全域 settings 当前 assistant 的页面级等价,登记)

import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  loadMcpServers, saveMcpServers, MCP_SERVERS_KEY,
} from '@amber/chat-domain';
import type { KeyValueStore, McpServerConfig, McpSettingsPort } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'Mcp.Settings';

class EntryMcpSettingsStore implements McpSettingsPort {
  private cache: McpServerConfig[] = [];
  private hydrated: boolean = false;
  private readonly listeners: Array<(configs: McpServerConfig[]) => void> = [];
  private assistantIdsProvider: () => string[] = (): string[] => [];

  async init(): Promise<void> {
    if (this.hydrated) return;
    this.hydrated = true;
    try {
      const loaded: McpServerConfig[] | null = await loadMcpServers(getChatKvStore());
      this.cache = loaded ?? [];
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'init load failed: %{public}s', String(e));
      this.cache = [];
    }
    // DataStore 首发射语义:hydrate 完成即对订阅者回放(manager init collect
    // 据此为既有 enabled servers 建连 — settingsFlow 真值发射等价)
    for (const l of this.listeners) l(this.cache);
  }

  setAssistantIdsProvider(provider: () => string[]): void {
    this.assistantIdsProvider = provider;
  }

  getMcpServers(): McpServerConfig[] {
    return this.cache;
  }

  getCurrentAssistantMcpServerIds(): string[] {
    return this.assistantIdsProvider();
  }

  updateMcpServers(updater: (old: McpServerConfig[]) => McpServerConfig[]): void {
    this.cache = updater(this.cache);
    // 写穿持久化(缓存权威;失败仅日志 — DataStore 异步落盘同语义)
    const snapshot: McpServerConfig[] = this.cache;
    saveMcpServers(getChatKvStore(), snapshot).catch((e: Error): void => {
      hilog.error(DOMAIN, TAG, 'persist failed: %{public}s', e.message);
    });
    for (const l of this.listeners) l(snapshot);
  }

  subscribeMcpServers(listener: (configs: McpServerConfig[]) => void): () => void {
    this.listeners.push(listener);
    listener(this.cache); // StateFlow 初值发射(:85-114 collect 立即触发)
    return (): void => {
      const i: number = this.listeners.indexOf(listener);
      if (i >= 0) this.listeners.splice(i, 1);
    };
  }
}

let instance: EntryMcpSettingsStore | null = null;

export const getMcpSettingsStore = (): EntryMcpSettingsStore => {
  if (instance === null) {
    instance = new EntryMcpSettingsStore();
  }
  return instance;
};

// 容器构建期调用:hydrate 缓存(见 AppContainer)
export const initMcpSettingsStore = async (): Promise<void> => {
  await getMcpSettingsStore().init();
};

// 测试/诊断用 key 透传
export const MCP_SERVERS_PREF_KEY: string = MCP_SERVERS_KEY;

// kv 直读(设置页初次渲染辅助;订阅走 store)
export const loadMcpServersOnce = async (kv: KeyValueStore): Promise<McpServerConfig[]> =>
  (await loadMcpServers(kv)) ?? [];

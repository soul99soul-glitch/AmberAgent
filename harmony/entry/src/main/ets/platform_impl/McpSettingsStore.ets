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
  private hydration: Promise<void> | null = null;
  private readonly pendingUpdates: Array<(old: McpServerConfig[]) => McpServerConfig[]> = [];
  private persistQueue: Promise<void> = Promise.resolve();
  private readonly listeners: Array<(configs: McpServerConfig[]) => void> = [];
  private assistantIdsProvider: () => string[] = (): string[] => [];

  private snapshot(configs: McpServerConfig[]): McpServerConfig[] {
    return JSON.parse(JSON.stringify(configs)) as McpServerConfig[];
  }

  async init(): Promise<void> {
    if (this.hydration !== null) {
      await this.hydration;
      return;
    }
    this.hydration = (async (): Promise<void> => {
      let loaded: McpServerConfig[] = [];
      try {
        loaded = (await loadMcpServers(getChatKvStore())) ?? [];
      } catch (e) {
        hilog.error(DOMAIN, TAG, 'init load failed: %{public}s', String(e));
      }
      this.cache = loaded;
      // Apply writes made while the asynchronous load was in flight to the
      // loaded value, instead of allowing hydration to overwrite them.
      const queuedUpdates = this.pendingUpdates.splice(0);
      for (const updater of queuedUpdates) {
        this.cache = this.snapshot(updater(this.snapshot(this.cache)));
      }
      this.hydrated = true;
      const current: McpServerConfig[] = this.snapshot(this.cache);
      for (const l of this.listeners) {
        try {
          l(this.snapshot(current));
        } catch (e) {
          hilog.warn(DOMAIN, TAG, 'listener failed: %{public}s', String(e));
        }
      }
      if (queuedUpdates.length > 0) this.persistSnapshot(this.cache);
    })();
    await this.hydration;
  }

  private persistSnapshot(configs: McpServerConfig[]): void {
    const snapshot: McpServerConfig[] = this.snapshot(configs);
    this.persistQueue = this.persistQueue.then(async (): Promise<void> => {
      try {
        await saveMcpServers(getChatKvStore(), snapshot);
      } catch (e) {
        hilog.error(DOMAIN, TAG, 'persist failed: %{public}s', String(e));
      }
    });
  }

  setAssistantIdsProvider(provider: () => string[]): void {
    this.assistantIdsProvider = provider;
  }

  getMcpServers(): McpServerConfig[] {
    return this.snapshot(this.cache);
  }

  getCurrentAssistantMcpServerIds(): string[] {
    return [...this.assistantIdsProvider()];
  }

  updateMcpServers(updater: (old: McpServerConfig[]) => McpServerConfig[]): void {
    if (!this.hydrated) {
      // Do not apply against the empty pre-hydration cache: replaying the same
      // updater after load is what preserves a concurrent edit.
      this.pendingUpdates.push(updater);
      return;
    }
    this.cache = this.snapshot(updater(this.snapshot(this.cache)));
    // Publish and persist immutable snapshots. Persistence is serialized so a
    // slower earlier write cannot overwrite a newer settings update.
    const current: McpServerConfig[] = this.snapshot(this.cache);
    this.persistSnapshot(current);
    for (const l of this.listeners) {
      try {
        l(this.snapshot(current));
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'listener failed: %{public}s', String(e));
      }
    }
  }

  subscribeMcpServers(listener: (configs: McpServerConfig[]) => void): () => void {
    this.listeners.push(listener);
    listener(this.snapshot(this.cache)); // StateFlow 初值发射(:85-114 collect 立即触发)
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

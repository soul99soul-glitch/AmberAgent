// AgentSoulSync — 跨端统一「形象人设」(C-SOUL-V1)双向同步协调器与响应式状态发布
//
// 铁律与职责:
//   - UI 响应式状态统一经 AppStorage(agentSoulDisplayName / agentSoulTagline /
//     agentSoulIsDefault / agentSoulRevision)发布,禁止 export let 承载 UI 状态;
//   - 远端拉取(GET /v2/soul)更新本地后绝不触发回环 PUT;
//   - 首次连接若服务端 revision == 0 且本地已有非默认自定义形象人设,执行一次性
//     PUT /v2/soul(source="harmony_ui")播种至云端;
//   - 若远端未配置、不可达或旧版网关返回 404,静默降级为纯本地模式,绝不向用户抛错。

import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  AgentSoulState, AgentSoulUpdatePatch, AgentSoulMutationResult,
  AgentSoulRemoteApplyResult, RemoteAgentSoulPayload, KeyValueStore,
} from '@amber/chat-domain';
import {
  AgentSoulStore, DEFAULT_AGENT_SOUL_NAME,
} from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  MacGatewayClient, MacGatewayError, loadMacGatewayConnection,
  type MacGatewayConnection, type CloudAgentSoulPayload,
  type CloudAgentSoulPutRequest, type CloudAgentSoulWriteSource,
} from './MacGatewayClient.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'AgentSoulSync';

export const APP_STORAGE_SOUL_DISPLAY_NAME: string = 'agentSoulDisplayName';
export const APP_STORAGE_SOUL_TAGLINE: string = 'agentSoulTagline';
export const APP_STORAGE_SOUL_IS_DEFAULT: string = 'agentSoulIsDefault';
export const APP_STORAGE_SOUL_REVISION: string = 'agentSoulRevision';

export interface SoulGatewayClientLike {
  getSoul(): Promise<CloudAgentSoulPayload>;
  putSoul(req: CloudAgentSoulPutRequest): Promise<CloudAgentSoulPayload>;
}

export interface AgentSoulCloudSyncDeps {
  store: AgentSoulStore;
  loadConnection: () => Promise<MacGatewayConnection | null>;
  clientFactory: (connection: MacGatewayConnection) => SoulGatewayClientLike;
  onStatePublished?: (state: AgentSoulState) => void;
}

export const publishAgentSoulToAppStorage = (state: AgentSoulState): void => {
  try {
    AppStorage.setOrCreate<string>(APP_STORAGE_SOUL_DISPLAY_NAME, state.displayName || DEFAULT_AGENT_SOUL_NAME);
    AppStorage.setOrCreate<string>(APP_STORAGE_SOUL_TAGLINE, state.tagline);
    AppStorage.setOrCreate<boolean>(APP_STORAGE_SOUL_IS_DEFAULT, state.isDefault);
    const prevTick: number = AppStorage.get<number>(APP_STORAGE_SOUL_REVISION) ?? 0;
    AppStorage.setOrCreate<number>(APP_STORAGE_SOUL_REVISION, prevTick + 1);
  } catch (_e) {
    // 非 ArkUI 测试宿主环境无 AppStorage 时静默忽略
  }
};

const toRemotePayload = (raw: CloudAgentSoulPayload): RemoteAgentSoulPayload => {
  const payload: RemoteAgentSoulPayload = {
    revision: raw.revision,
    updatedAtMs: raw.updatedAtMs,
    displayName: raw.displayName,
    tagline: raw.tagline,
    soulMarkdown: raw.soulMarkdown,
    isDefault: raw.isDefault,
    source: raw.source,
    history: Array.isArray(raw.history) ? raw.history : [],
  };
  return payload;
};

export class AgentSoulCloudSynchronizer {
  private readonly deps: AgentSoulCloudSyncDeps;
  private pullInFlight: Promise<AgentSoulState> | null = null;

  constructor(deps: AgentSoulCloudSyncDeps) {
    this.deps = deps;
  }

  getStore(): AgentSoulStore {
    return this.deps.store;
  }

  private publish(state: AgentSoulState): void {
    publishAgentSoulToAppStorage(state);
    if (this.deps.onStatePublished !== undefined) {
      this.deps.onStatePublished(state);
    }
  }

  async hydrateLocal(forceReload: boolean = false): Promise<AgentSoulState> {
    const state: AgentSoulState = await this.deps.store.load(forceReload);
    this.publish(state);
    return state;
  }

  private async resolveConnection(
    explicitConn?: MacGatewayConnection | null,
  ): Promise<MacGatewayConnection | null> {
    if (explicitConn !== undefined) return explicitConn;
    try {
      return await this.deps.loadConnection();
    } catch (_e) {
      return null;
    }
  }

  async syncFromCloudOnce(explicitConn?: MacGatewayConnection | null): Promise<AgentSoulState> {
    if (this.pullInFlight !== null) {
      return this.pullInFlight;
    }
    const task: Promise<AgentSoulState> = this.doSyncFromCloud(explicitConn);
    this.pullInFlight = task;
    try {
      return await task;
    } finally {
      if (this.pullInFlight === task) {
        this.pullInFlight = null;
      }
    }
  }

  private async doSyncFromCloud(explicitConn?: MacGatewayConnection | null): Promise<AgentSoulState> {
    const local: AgentSoulState = await this.deps.store.load();
    this.publish(local);
    const conn: MacGatewayConnection | null = await this.resolveConnection(explicitConn);
    if (conn === null) return local;

    try {
      const client: SoulGatewayClientLike = this.deps.clientFactory(conn);
      const remoteRaw: CloudAgentSoulPayload = await client.getSoul();
      const applyRes: AgentSoulRemoteApplyResult = await this.deps.store.applyRemoteSoul(
        toRemotePayload(remoteRaw),
      );
      if (applyRes.shouldSeedRemote) {
        // 首次连接:服务端 revision == 0 且本地已有自定义非默认人设 → 一次性上传播种
        await this.pushStateToCloud(applyRes.state, 'harmony_ui', conn);
        return await this.deps.store.load();
      }
      if (applyRes.applied) {
        // 远端版本更新了本地存储:仅发布 UI 状态,严禁回环触发 PUT
        this.publish(applyRes.state);
      }
      return applyRes.state;
    } catch (e) {
      const err: Error = e as Error;
      if (err instanceof MacGatewayError && err.statusCode === 404) {
        hilog.info(DOMAIN, TAG, 'remote /v2/soul returned 404; staying local-only');
      } else {
        hilog.warn(DOMAIN, TAG, 'syncFromCloudOnce skipped: %{public}s', err.message);
      }
      return local;
    }
  }

  async observeRemoteSoulRevision(
    soulRevision: number | undefined,
    explicitConn?: MacGatewayConnection | null,
  ): Promise<void> {
    if (typeof soulRevision !== 'number' || !Number.isFinite(soulRevision)) return;
    const rev: number = Math.max(0, Math.floor(soulRevision));
    const local: AgentSoulState = await this.deps.store.load();
    if (rev > local.localLastSyncedRevision) {
      await this.syncFromCloudOnce(explicitConn);
      return;
    }
    if (rev === 0 && !local.isDefault && local.localLastSyncedRevision === 0) {
      await this.syncFromCloudOnce(explicitConn);
    }
  }

  private async pushStateToCloud(
    state: AgentSoulState,
    source: CloudAgentSoulWriteSource,
    explicitConn?: MacGatewayConnection | null,
  ): Promise<void> {
    const conn: MacGatewayConnection | null = await this.resolveConnection(explicitConn);
    if (conn === null) return;
    const req: CloudAgentSoulPutRequest = {
      displayName: state.displayName,
      tagline: state.tagline,
      soulMarkdown: state.soulMarkdown,
      isDefault: state.isDefault,
      updatedAtMs: state.updatedAtMs > 0 ? state.updatedAtMs : Date.now(),
      source: source,
    };
    try {
      const remote: CloudAgentSoulPayload = await this.deps.clientFactory(conn).putSoul(req);
      const synced: AgentSoulState = await this.deps.store.markSyncedFromPut(toRemotePayload(remote));
      this.publish(synced);
    } catch (e) {
      const err: Error = e as Error;
      if (err instanceof MacGatewayError && err.statusCode === 404) {
        hilog.info(DOMAIN, TAG, 'PUT /v2/soul returned 404; degraded to local-only');
      } else {
        hilog.warn(DOMAIN, TAG, 'PUT /v2/soul failed (degraded): %{public}s', err.message);
      }
    }
  }

  async updateLocalAndSync(
    patch: AgentSoulUpdatePatch,
    syncSource: CloudAgentSoulWriteSource,
  ): Promise<AgentSoulMutationResult> {
    const res: AgentSoulMutationResult = await this.deps.store.updateLocal(patch);
    this.publish(res.state);
    if (res.changed) {
      this.pushStateToCloud(res.state, syncSource).catch((): void => {});
    }
    return res;
  }

  async restoreHistoryAndSync(
    snapshotId: string,
    syncSource: CloudAgentSoulWriteSource = 'harmony_ui',
  ): Promise<AgentSoulMutationResult> {
    const res: AgentSoulMutationResult = await this.deps.store.restoreHistory(snapshotId, syncSource);
    this.publish(res.state);
    if (res.changed) {
      this.pushStateToCloud(res.state, syncSource).catch((): void => {});
    }
    return res;
  }

  async resetToDefaultAndSync(
    syncSource: CloudAgentSoulWriteSource = 'harmony_ui',
  ): Promise<AgentSoulMutationResult> {
    const res: AgentSoulMutationResult = await this.deps.store.resetToDefault(syncSource);
    this.publish(res.state);
    if (res.changed) {
      this.pushStateToCloud(res.state, syncSource).catch((): void => {});
    }
    return res;
  }
}

let singletonSync: AgentSoulCloudSynchronizer | null = null;
let singletonKvRef: KeyValueStore | null = null;

export const getAgentSoulSynchronizer = (): AgentSoulCloudSynchronizer => {
  const kv: KeyValueStore = getChatKvStore();
  if (singletonSync === null || singletonKvRef !== kv) {
    singletonKvRef = kv;
    singletonSync = new AgentSoulCloudSynchronizer({
      store: new AgentSoulStore(kv),
      loadConnection: (): Promise<MacGatewayConnection | null> => loadMacGatewayConnection(),
      clientFactory: (c: MacGatewayConnection): SoulGatewayClientLike => MacGatewayClient.of(c),
    });
  }
  return singletonSync;
};

export const loadAgentSoulState = (forceReload: boolean = false): Promise<AgentSoulState> =>
  getAgentSoulSynchronizer().hydrateLocal(forceReload);

export const hydrateAgentSoulOnForeground = (): void => {
  const sync: AgentSoulCloudSynchronizer = getAgentSoulSynchronizer();
  sync.hydrateLocal().then((): Promise<AgentSoulState> => sync.syncFromCloudOnce()).catch((): void => {});
};

export const syncAgentSoulFromCloud = (conn?: MacGatewayConnection | null): void => {
  getAgentSoulSynchronizer().syncFromCloudOnce(conn).catch((): void => {});
};

export const observeGatewaySoulRevision = (
  soulRevision: number | undefined,
  conn?: MacGatewayConnection | null,
): void => {
  getAgentSoulSynchronizer().observeRemoteSoulRevision(soulRevision, conn).catch((): void => {});
};

export const updateAgentSoulFromUi = (
  displayName: string,
  tagline: string,
  soulMarkdown: string,
): Promise<AgentSoulMutationResult> =>
  getAgentSoulSynchronizer().updateLocalAndSync({
    displayName,
    tagline,
    soulMarkdown,
    source: 'harmony_ui',
  }, 'harmony_ui');

export const restoreAgentSoulSnapshotFromUi = (snapshotId: string): Promise<AgentSoulMutationResult> =>
  getAgentSoulSynchronizer().restoreHistoryAndSync(snapshotId, 'harmony_ui');

export const resetAgentSoulFromUi = (): Promise<AgentSoulMutationResult> =>
  getAgentSoulSynchronizer().resetToDefaultAndSync('harmony_ui');

export const updateAgentSoulFromChat = (patch: AgentSoulUpdatePatch): Promise<AgentSoulMutationResult> =>
  getAgentSoulSynchronizer().updateLocalAndSync({
    displayName: patch.displayName,
    tagline: patch.tagline,
    soulMarkdown: patch.soulMarkdown,
    source: 'harmony_chat',
    nowMs: patch.nowMs,
  }, 'harmony_chat');

export const resetAgentSoulFromChat = (): Promise<AgentSoulMutationResult> =>
  getAgentSoulSynchronizer().resetToDefaultAndSync('harmony_chat');

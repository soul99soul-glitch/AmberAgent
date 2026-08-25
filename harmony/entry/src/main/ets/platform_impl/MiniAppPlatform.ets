// MiniAppPlatform — MiniApp 子系统 entry 组装(单例)
//
// 组成:
//   - repository:文件持久化仓库(MiniAppFiles;内存实现为缓存层)
//   - storage:MiniAppStorage(ChatKvStore 适配;键 "$appId:$key",三重限额)
//   - setting:MiniAppSetting 持久化(Storage 键 'mini_app_setting')
//   - sandbox:权限门(声明集 + 全局开关 + grant 记忆)
//   - eventBus / launchLimiter / aiBudget(HAR 实例工厂,测试隔离语义)
//   - sha256:CryptoArchitectureKit SHA-256(@ohos.security.cryptoFramework)
//
// 组装依赖 ChatKvStore(Preferences Storage)与 MiniAppFiles;均惰性单例。

import type {
  KeyValueStore, MiniAppSetting, MiniAppEventBus, MiniAppLaunchLimiter,
  MiniAppAiBudget, MiniAppRepository, MiniAppRecord, MiniAppVersionRecord,
  MiniAppAuditEntry, MiniAppGrantDecision, MiniAppGeneratedOutput,
  MiniAppPermission, JsonValue,
} from '@amber/chat-domain';
import {
  MiniAppStorage, makeMiniAppSetting, loadMiniAppSetting, saveMiniAppSetting,
  MINI_APP_SETTING_KEY, createMiniAppEventBus, createMiniAppLaunchLimiter,
  createMiniAppAiBudget, MiniAppSandbox,
} from '@amber/chat-domain';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  createFileMiniAppRepository, miniAppFilesDir, MiniAppFileRepository,
} from './MiniAppFiles.ets';

export interface MiniAppPlatform {
  repository: MiniAppFileRepository;
  storage: MiniAppStorage;
  loadSetting(): Promise<MiniAppSetting>;
  saveSetting(setting: MiniAppSetting): Promise<void>;
  settingSnapshot(): MiniAppSetting;
  refreshSetting(): Promise<MiniAppSetting>;
  eventBus: MiniAppEventBus;
  launchLimiter: MiniAppLaunchLimiter;
  aiBudget: MiniAppAiBudget;
  sandboxFor(appId: string, declared: string[]): MiniAppSandbox;
}

class MiniAppPlatformImpl implements MiniAppPlatform {
  private settingWriteTail: Promise<void> = Promise.resolve();
  readonly repository: MiniAppFileRepository;
  readonly storage: MiniAppStorage;
  readonly eventBus: MiniAppEventBus;
  readonly launchLimiter: MiniAppLaunchLimiter;
  readonly aiBudget: MiniAppAiBudget;
  private kv: KeyValueStore;

  constructor(
    repository: MiniAppFileRepository,
    storage: MiniAppStorage,
    eventBus: MiniAppEventBus,
    launchLimiter: MiniAppLaunchLimiter,
    aiBudget: MiniAppAiBudget,
    kv: KeyValueStore,
  ) {
    this.repository = repository;
    this.storage = storage;
    this.eventBus = eventBus;
    this.launchLimiter = launchLimiter;
    this.aiBudget = aiBudget;
    this.kv = kv;
  }

  loadSetting(): Promise<MiniAppSetting> {
    return loadMiniAppSetting(this.kv);
  }

  async saveSetting(setting: MiniAppSetting): Promise<void> {
    // 写串行:快速切换多个开关时,并发 load/modify/save 的后写者会覆盖先写者
    const run: Promise<void> = this.settingWriteTail
      .catch((): void => {})
      .then(async (): Promise<void> => {
        await saveMiniAppSetting(this.kv, setting);
        cachedSetting = setting; // 持久化成功后才更新缓存(失败不留内存/磁盘分叉)
      });
    this.settingWriteTail = run.then((): void => {}, (): void => {});
    return run;
  }

  settingSnapshot(): MiniAppSetting {
    return cachedSetting;
  }

  async refreshSetting(): Promise<MiniAppSetting> {
    // 等待在途写完成再读盘:否则失败回滚路径可能在排队写入前读到旧值,
    // 又在写入成功后把 cachedSetting 覆盖回旧值
    await this.settingWriteTail.catch((): void => {});
    cachedSetting = await loadMiniAppSetting(this.kv);
    return cachedSetting;
  }

  sandboxFor(appId: string, declared: string[]): MiniAppSandbox {
    return new MiniAppSandbox(appId, declared, {
      setting: (): MiniAppSetting => cachedSetting,
      grantLookup: (permission: string): MiniAppGrantDecision | null =>
        this.repository.grantSync(appId, permission),
    });
  }
}

export const sha256Hex = (input: string): string => {
  const md = cryptoFramework.createMd('SHA256');
  const bytes: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(input);
  md.updateSync({ data: bytes });
  const out = md.digestSync();
  let hex: string = '';
  for (const b of out.data) {
    hex += b.toString(16).padStart(2, '0');
  }
  return hex;
};

let platformInstance: MiniAppPlatform | null = null;

// 进程内设置快照(同步读;Android settingsFlow.value 语义 —
//   aboutToAppear/发送前 refreshSetting,transform 调用时读快照)
let cachedSetting: MiniAppSetting = makeMiniAppSetting();

export const createMiniAppPlatform = async (): Promise<MiniAppPlatform> => {
  const kv: KeyValueStore = getChatKvStore();
  const repository: MiniAppFileRepository = await createFileMiniAppRepository({
    baseDir: miniAppFilesDir(),
    sha256Hex: sha256Hex,
    clock: (): number => Date.now(),
    idGen: (): string => util.generateRandomUUID(false),
  });
  // MiniAppStorage 配额核算:kv(ChatKvStore)实现 allEntries → Preferences
  //   getAll 前缀扫描(Android prefs.all 等价);无需注入空快照
  const storage: MiniAppStorage = new MiniAppStorage(kv);
  cachedSetting = await loadMiniAppSetting(kv);
  const eventBus: MiniAppEventBus = createMiniAppEventBus(
    (): string => util.generateRandomUUID(false));
  const launchLimiter: MiniAppLaunchLimiter = createMiniAppLaunchLimiter();
  // Android LocalDate.now():本地日界(非 UTC;否则 +8 时区 8 点才重置预算)
  const localDayKey = (): string => {
    const d: Date = new Date();
    const month: string = String(d.getMonth() + 1).padStart(2, '0');
    const day: string = String(d.getDate()).padStart(2, '0');
    return `${d.getFullYear()}-${month}-${day}`;
  };
  const aiBudget: MiniAppAiBudget = createMiniAppAiBudget({
    appId: 'shared',
    dayKey: localDayKey,
    store: kv,
  });
  return new MiniAppPlatformImpl(
    repository, storage, eventBus, launchLimiter, aiBudget, kv);
};

let platformInitPromise: Promise<MiniAppPlatform> | null = null;

export const getMiniAppPlatform = async (): Promise<MiniAppPlatform> => {
  if (platformInstance !== null) return platformInstance;
  // Promise 单例:并发首访不得创建双实例(双 repository/双写 index.json)
  if (platformInitPromise === null) {
    platformInitPromise = createMiniAppPlatform();
  }
  try {
    platformInstance = await platformInitPromise;
    return platformInstance;
  } catch (e) {
    platformInitPromise = null; // 失败不缓存,允许重试
    throw e instanceof Error ? e : new Error(String(e));
  }
};

export const MINI_APP_SETTING_STORAGE_KEY: string = MINI_APP_SETTING_KEY;

// ===== 同步 repository 句柄(同步组装期依赖;经 getMiniAppPlatform 惰性解析) =====
// createMiniAppOutputTransformer 需要 repository 对象(非 getter);而平台单例为
// 异步装配。此处提供代理,各方法先解析平台再委托,保证同步构造安全。
class MiniAppRepositoryProxy implements MiniAppRepository {
  async listAll(): Promise<MiniAppRecord[]> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.listAll();
  }
  async getById(id: string): Promise<MiniAppRecord | null> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.getById(id);
  }
  async saveGenerated(
    output: MiniAppGeneratedOutput,
    sourceConversationId: string | null = null,
    sourceMessageId: string | null = null,
  ): Promise<MiniAppRecord> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.saveGenerated(output, sourceConversationId, sourceMessageId);
  }
  async saveRevision(
    appId: string,
    output: MiniAppGeneratedOutput,
    expectedBaseVersion: number | null = null,
    sourceMessageId: string | null = null,
    changeNote: string | null = null,
  ): Promise<MiniAppRecord | null> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.saveRevision(
      appId, output, expectedBaseVersion, sourceMessageId, changeNote);
  }
  async markRun(id: string): Promise<void> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.markRun(id);
  }
  async setPinned(id: string, pinned: boolean): Promise<void> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.setPinned(id, pinned);
  }
  async rename(id: string, title: string, description: string): Promise<void> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.rename(id, title, description);
  }
  async updateBoardSummary(id: string, summary: string): Promise<void> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.updateBoardSummary(id, summary);
  }
  async delete(id: string): Promise<void> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.delete(id);
  }
  async listVersions(appId: string): Promise<MiniAppVersionRecord[]> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.listVersions(appId);
  }
  async getVersion(appId: string, versionNumber: number): Promise<MiniAppVersionRecord | null> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.getVersion(appId, versionNumber);
  }
  async restoreVersion(appId: string, versionNumber: number): Promise<MiniAppRecord | null> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.restoreVersion(appId, versionNumber);
  }
  async getGrant(appId: string, permission: string): Promise<MiniAppGrantDecision | null> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.getGrant(appId, permission);
  }
  async setGrant(appId: string, permission: string, decision: MiniAppGrantDecision): Promise<void> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.setGrant(appId, permission, decision);
  }
  async appendAudit(
    appId: string,
    method: string,
    permission: MiniAppPermission,
    summary: string,
    payload: string,
  ): Promise<void> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.appendAudit(appId, method, permission, summary, payload);
  }
  async listAudit(appId: string, limit: number = 100): Promise<MiniAppAuditEntry[]> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.listAudit(appId, limit);
  }
  async sharedGet(appId: string, namespace: string, key: string): Promise<JsonValue | null> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.sharedGet(appId, namespace, key);
  }
  async sharedSet(appId: string, namespace: string, key: string, value: JsonValue): Promise<void> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.sharedSet(appId, namespace, key, value);
  }
  async sharedRemove(appId: string, namespace: string, key: string): Promise<void> {
    const p: MiniAppPlatform = await getMiniAppPlatform();
    return p.repository.sharedRemove(appId, namespace, key);
  }
}

let repositoryProxy: MiniAppRepository | null = null;
export const getMiniAppRepositorySync = (): MiniAppRepository => {
  if (repositoryProxy === null) {
    repositoryProxy = new MiniAppRepositoryProxy();
  }
  return repositoryProxy;
};

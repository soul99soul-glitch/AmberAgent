// ManagedFileStore — managed_files JSON 持久化(D-086)
// Android 基准:ManagedFileDAO.kt(Room)→ 本文件 JSON 等价;
//   Room @Insert(REPLACE)+AUTOINCREMENT 语义:nextId 高水位不复用,
//   relative_path 唯一冲突 → 删旧换新 id;@Update 未命中 no-op;
//   deleteBy* 返回受影响行数;listByFolder ORDER created_at DESC。
// 存储:filesDir/managed_files.json({nextId, rows});写操作 enqueueWrite 串行。
import { Context } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  createFilesRepository, deleteChatFiles, conversationFileUris, clearPendingQueueStorage,
  FILE_FOLDERS_UPLOAD, FILE_FOLDERS_SKILLS, FILE_FOLDERS_CHAT_IMAGES, FILE_FOLDERS_IMAGES,
} from '@amber/chat-domain';
import { removeFavoritesOfConversation } from './FavoriteStore.ets';
import { deleteConversationCompactData } from './EntryCompactStore.ets';
import { getConversationRecapService } from './ConversationRecapSupport.ets';
import { writeTextFileAtomic, readGuardedJson } from './AtomicJsonFile.ets';
import type { GuardedJsonRead } from './AtomicJsonFile.ets';
import type {
  DeleteChatFilesDeps, FilesRepository, ManagedFileDaoPort, ManagedFileEntity,
  Conversation, ConversationRepository, KeyValueStore,
} from '@amber/chat-domain';

interface ManagedFileFileShape {
  nextId: number;
  rows: ManagedFileEntity[];
}

let conversationFileMutationTail: Promise<void> = Promise.resolve();

export const enqueueConversationFileMutation = (
  mutation: () => Promise<void>,
): Promise<void> => {
  const next: Promise<void> = conversationFileMutationTail
    .catch((): void => {})
    .then(mutation);
  conversationFileMutationTail = next;
  return next;
};

const emptyShape = (): ManagedFileFileShape => ({ nextId: 1, rows: [] });

const storePath = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${ctx.filesDir}/managed_files.json`;
};

// 缺失 → 空表(真首次初始化);损坏 → 隔离并保留标记;IO 错 → 错误态。
// 三种非 ok 态不可混同:只有 missing 才允许作为写基准,否则整库会被空快照覆盖
// (MemoryStore 同策略,但这里读操作也区分并抛出,供 UI 判定错误态)
interface ShapeRead {
  error: string; // '' = 可作写基准(ok 或 missing);非空 = 禁止覆写
  shape: ManagedFileFileShape | null; // 仅 ok 时非空
}

const parseShape = (text: string): ManagedFileFileShape | null => {
  try {
    const parsed: ManagedFileFileShape = JSON.parse(text) as ManagedFileFileShape;
    if (typeof parsed.nextId === 'number' && Array.isArray(parsed.rows)) return parsed;
    return null;
  } catch {
    return null;
  }
};

const loadShape = async (): Promise<ShapeRead> => {
  const outcome: GuardedJsonRead<ManagedFileFileShape> =
    await readGuardedJson<ManagedFileFileShape>(storePath(), parseShape);
  if (outcome.status === 'ok') {
    const okRead: ShapeRead = { error: '', shape: outcome.value };
    return okRead;
  }
  if (outcome.status === 'missing') {
    const absent: ShapeRead = { error: '', shape: null };
    return absent;
  }
  const reason: string = outcome.status === 'blocked'
    ? 'managed_files.json 损坏已被隔离,拒绝覆盖(需先恢复或显式清除 .quarantined)'
    : 'managed_files.json 读取失败,拒绝覆盖';
  const failed: ShapeRead = { error: reason, shape: null };
  return failed;
};

// 读/写共用:错误态一律抛出。读操作抛出可让上层(文件管理页)进入错误态、
// 禁用破坏性清理;写操作抛出可避免以空快照覆盖仍在隔离中的旧数据。
const requireHealthyShape = async (): Promise<ManagedFileFileShape> => {
  const read: ShapeRead = await loadShape();
  if (read.error !== '') throw new Error(read.error);
  return read.shape !== null ? read.shape : emptyShape();
};


const writeShape = async (shape: ManagedFileFileShape): Promise<void> =>
  writeTextFileAtomic(storePath(), JSON.stringify(shape));

// 写串行队列(MemoryStore.enqueueWrite 同构)
let writeQueue: Promise<void> = Promise.resolve();
const enqueueWrite = <T>(op: () => Promise<T>): Promise<T> => {
  const run: Promise<T> = writeQueue.then(op);
  writeQueue = run.then((): void => {}, (): void => {});
  return run;
};

const withId = (file: ManagedFileEntity, id: number): ManagedFileEntity => {
  // 显式构造(entry 严格 ArkTS:交叉类型参数不接受对象字面量,不用 makeManagedFileEntity)
  const copy: ManagedFileEntity = {
    id, folder: file.folder, relativePath: file.relativePath,
    displayName: file.displayName, mimeType: file.mimeType,
    sizeBytes: file.sizeBytes, createdAt: file.createdAt, updatedAt: file.updatedAt,
  };
  return copy;
};

const dao: ManagedFileDaoPort = {
  insert: (file: ManagedFileEntity): Promise<number> =>
    enqueueWrite(async (): Promise<number> => {
      const shape: ManagedFileFileShape = await requireHealthyShape();
      // relative_path 唯一冲突 → 删旧行(REPLACE)
      const byPath: number = shape.rows.findIndex(
        (r: ManagedFileEntity): boolean => r.relativePath === file.relativePath);
      if (byPath >= 0 && shape.rows[byPath].id !== file.id) {
        shape.rows.splice(byPath, 1);
      }
      let id: number = file.id;
      if (id === 0) {
        id = shape.nextId; // AUTOINCREMENT 高水位
        shape.nextId += 1;
        shape.rows.push(withId(file, id));
      } else {
        const byId: number = shape.rows.findIndex(
          (r: ManagedFileEntity): boolean => r.id === id);
        if (byId >= 0) shape.rows[byId] = file;
        else shape.rows.push(file);
        if (id >= shape.nextId) shape.nextId = id + 1;
      }
      await writeShape(shape);
      return id;
    }),
  update: (file: ManagedFileEntity): Promise<void> =>
    enqueueWrite(async (): Promise<void> => {
      const shape: ManagedFileFileShape = await requireHealthyShape();
      const index: number = shape.rows.findIndex(
        (r: ManagedFileEntity): boolean => r.id === file.id);
      if (index < 0) return; // @Update 未命中 = 0 行
      shape.rows[index] = file;
      await writeShape(shape);
    }),
  getById: async (id: number): Promise<ManagedFileEntity | null> => {
    const shape: ManagedFileFileShape = await requireHealthyShape();
    const found: ManagedFileEntity | undefined = shape.rows.find(
      (r: ManagedFileEntity): boolean => r.id === id);
    return found !== undefined ? found : null;
  },
  getByPath: async (relativePath: string): Promise<ManagedFileEntity | null> => {
    const shape: ManagedFileFileShape = await requireHealthyShape();
    const found: ManagedFileEntity | undefined = shape.rows.find(
      (r: ManagedFileEntity): boolean => r.relativePath === relativePath);
    return found !== undefined ? found : null;
  },
  listByFolder: async (folder: string): Promise<ManagedFileEntity[]> => {
    const shape: ManagedFileFileShape = await requireHealthyShape();
    return shape.rows.filter((r: ManagedFileEntity): boolean => r.folder === folder)
      .sort((a: ManagedFileEntity, b: ManagedFileEntity): number => b.createdAt - a.createdAt);
  },
  deleteById: (id: number): Promise<number> =>
    enqueueWrite(async (): Promise<number> => {
      const shape: ManagedFileFileShape = await requireHealthyShape();
      const before: number = shape.rows.length;
      shape.rows = shape.rows.filter((r: ManagedFileEntity): boolean => r.id !== id);
      await writeShape(shape);
      return before - shape.rows.length;
    }),
  deleteByPath: (relativePath: string): Promise<number> =>
    enqueueWrite(async (): Promise<number> => {
      const shape: ManagedFileFileShape = await requireHealthyShape();
      const before: number = shape.rows.length;
      shape.rows = shape.rows.filter(
        (r: ManagedFileEntity): boolean => r.relativePath !== relativePath);
      await writeShape(shape);
      return before - shape.rows.length;
    }),
  deleteByFolder: (folder: string): Promise<number> =>
    enqueueWrite(async (): Promise<number> => {
      const shape: ManagedFileFileShape = await requireHealthyShape();
      const before: number = shape.rows.length;
      shape.rows = shape.rows.filter(
        (r: ManagedFileEntity): boolean => r.folder !== folder);
      await writeShape(shape);
      return before - shape.rows.length;
    }),
};

let cached: FilesRepository | null = null;

// FilesRepository 单例(FilesManager 跟踪/查询入口)
export const getFilesRepository = (): FilesRepository => {
  if (cached === null) cached = createFilesRepository(dao);
  return cached;
};

// ===== D-096:会话删除清理(deleteChatFiles 调用侧 deps + cleanup 编排)=====

const filesDirPath = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return ctx.filesDir;
};

// getRelativePathInFilesDir(FilesManager.kt:512-518):file:// 去前缀后须落在
//   filesDir 内 → 相对路径化;canonical 解析简化为前缀比较(沙箱内无 symlink 面,登记)
export const getDeleteChatFilesDeps = (): DeleteChatFilesDeps => ({
  toRelativePath: (uri: string): string | null => {
    if (!uri.startsWith('file://')) return null;
    const path: string = uri.substring('file://'.length);
    const base: string = filesDirPath();
    return path.startsWith(`${base}/`) ? path.substring(base.length + 1) : null;
  },
  fileExists: (relativePath: string): Promise<boolean> =>
    fileIo.access(`${filesDirPath()}/${relativePath}`),
  deleteFile: (relativePath: string): Promise<void> =>
    fileIo.unlink(`${filesDirPath()}/${relativePath}`),
  deleteByPath: (relativePath: string): Promise<number> =>
    getFilesRepository().deleteByPath(relativePath),
});

// cleanupDeletedConversation(ConversationRepository.kt:425-432)—
//   favoriteDAO.deleteByConversation → FavoriteStore 级联(KV 真实存储);
//   deleteChatImagesDir:依附 generate_image 工具,未移植(P1 登记)
// R11:补 compact 摘要(compacts/<id> + .events)清理,并防在途摘要迟到复活
//   (删除标记 + EntryCompactStore 同键写队列,详见该文件)。
export const cleanupDeletedConversation = async (
  conv: Conversation, repository: ConversationRepository, kv: KeyValueStore,
): Promise<void> => enqueueConversationFileMutation(async (): Promise<void> => {
  const safeUris: string[] = [];
  const summaries = await repository.list();
  for (const uri of conversationFileUris(conv)) {
    let referenced: boolean = false;
    for (const summary of summaries) {
      const other: Conversation | null = await repository.getById(summary.id);
      if (other !== null && conversationFileUris(other).indexOf(uri) >= 0) {
        referenced = true;
        break;
      }
    }
    if (!referenced) safeUris.push(uri);
  }
  await deleteChatFiles(getDeleteChatFilesDeps(), safeUris);
  await clearPendingQueueStorage(kv, conv.id);
  // compact/events 清理是幂等安全动作,先于 favorites 执行,避免收藏读损时被跳过
  await deleteConversationCompactData(kv, conv.id);
  await getConversationRecapService().invalidate(conv.id);
  // 收藏数据损坏时 removeFavoritesOfConversation 会拒绝写(保留原值);该级联失败
  // 不应中断删除编排本身,记录后继续(附件/摘要/待发队列已清理)。
  try {
    await removeFavoritesOfConversation(conv.id);
  } catch (e) {
    hilog.warn(0x0001, 'ManagedFileStore',
      'favorites cascade skipped (corrupt store?): %{public}s', (e as Error).message);
  }
});

// ===== UI-F20:文件管理「清理孤立文件」安全边界 =====
// 1) 登记库必须健康(缺失/损坏/IO 错 → 拒绝清理,绝不把空快照当删除依据);
// 2) 删除前重新拉取登记(不信任页面缓存)并核验全部会话引用;
// 3) 扫描只列出候选,实际 unlink 走本函数,由调用方在预览确认后执行。
export type ManagedRegistryStatus = 'ok' | 'missing' | 'error';

export const MANAGED_FILE_FOLDERS: string[] =
  [FILE_FOLDERS_UPLOAD, FILE_FOLDERS_SKILLS, FILE_FOLDERS_CHAT_IMAGES, FILE_FOLDERS_IMAGES];

export interface ManagedRegistryHealth {
  status: ManagedRegistryStatus;
  error: string; // 非 ok 时的用户可读原因
  trackedPaths: string[]; // 健康快照里的相对路径(供候选核验)
}

export const checkManagedFilesHealth = async (): Promise<ManagedRegistryHealth> => {
  const read: ShapeRead = await loadShape();
  if (read.error !== '') {
    const bad: ManagedRegistryHealth = { status: 'error', error: read.error, trackedPaths: [] };
    return bad;
  }
  if (read.shape === null) {
    const missing: ManagedRegistryHealth =
      { status: 'missing', error: '登记库不存在', trackedPaths: [] };
    return missing;
  }
  const paths: string[] = [];
  for (const row of read.shape.rows) paths.push(row.relativePath);
  const ok: ManagedRegistryHealth = { status: 'ok', error: '', trackedPaths: paths };
  return ok;
};

// 清理前的「实时」健康视图:重新取健康登记,而不是直接用页面已缓存的 rows
export const loadManagedRegistryForCleanup = async (): Promise<ManagedRegistryHealth> =>
  checkManagedFilesHealth();

const allTrackedPaths = (registry: ManagedRegistryHealth): string[] => registry.trackedPaths;

// RDB 初始化失败时 ChatRdbRepository 置 true,list/getById 回退内存快照(通常为空)。
// 降级期的「引用集合」不可信:必须拒绝清理,否则会把仍被会话引用的文件当孤儿。
const CHAT_STORAGE_DEGRADED_KEY: string = 'chatStorageDegraded';
const DEGRADED_REF_ERROR: string =
  '会话存储处于降级(内存回退)状态,引用核验不可信,已拒绝清理';

export const isChatStorageDegraded = (): boolean =>
  AppStorage.get<boolean>(CHAT_STORAGE_DEGRADED_KEY) === true;

// planned 是外部入口(UI 经预览传入),须最小结构校验:仅允许
//   MANAGED_FILE_FOLDERS 内的「单层」文件名,拒绝 ../、多级路径与空名。
export const isSafeOrphanPath = (relativePath: string): boolean => {
  if (relativePath.length === 0) return false;
  if (relativePath.indexOf('\\') >= 0) return false;
  const segments: string[] = relativePath.split('/');
  if (segments.length !== 2) return false;
  const folder: string = segments[0];
  const name: string = segments[1];
  if (MANAGED_FILE_FOLDERS.indexOf(folder) < 0) return false;
  if (name.length === 0 || name === '.' || name === '..') return false;
  if (name.indexOf('..') >= 0) return false;
  return true;
};

// 会话引用集合:遍历所有会话的附件 uri,转成 filesDir 相对路径
// (getRelativePathInFilesDir 语义;非 filesDir 内/非 file:// → 忽略)
// 降级标记前后各查一次:repo.list 触发 init,失败会在期间置 true → after 命中即拒绝。
export const referencedUploadPaths = async (
  repository: ConversationRepository,
): Promise<string[]> => {
  if (isChatStorageDegraded()) throw new Error(DEGRADED_REF_ERROR);
  const base: string = filesDirPath();
  const uris: string[] = [];
  const summaries = await repository.list();
  for (const summary of summaries) {
    const conv: Conversation | null = await repository.getById(summary.id);
    if (conv === null) continue;
    for (const uri of conversationFileUris(conv)) {
      if (uri.startsWith('file://')) uris.push(uri);
    }
  }
  if (isChatStorageDegraded()) throw new Error(DEGRADED_REF_ERROR);
  const paths: string[] = [];
  for (const uri of uris) {
    const abs: string = uri.substring('file://'.length);
    if (!abs.startsWith(`${base}/`)) continue;
    const rel: string = abs.substring(base.length + 1);
    if (paths.indexOf(rel) < 0) paths.push(rel);
  }
  return paths;
};

export interface OrphanScanResult {
  error: string; // 非空 → 候选不可信,禁止清理
  candidates: string[]; // 磁盘存在、登记无、会话无引用的相对路径
}

// 只扫描,不删除。调用方须先展示 candidates,再由用户确认后调用 deleteManagedOrphans。
export const scanManagedOrphans = async (
  repository: ConversationRepository,
): Promise<OrphanScanResult> => {
  const registry: ManagedRegistryHealth = await loadManagedRegistryForCleanup();
  if (registry.status !== 'ok') {
    const blocked: OrphanScanResult = { error: registry.error, candidates: [] };
    return blocked;
  }
  const known: string[] = allTrackedPaths(registry);
  let referenced: string[] = [];
  try {
    referenced = await referencedUploadPaths(repository);
  } catch (e) {
    // 降级/引用核验失败 → 候选不可信,禁止清理
    const blocked: OrphanScanResult = { error: (e as Error).message, candidates: [] };
    return blocked;
  }
  const registryFresh: ManagedRegistryHealth = await loadManagedRegistryForCleanup();
  if (registryFresh.status !== 'ok') {
    // 扫描期间登记库又被写坏 → 放弃,绝不用过期快照做删除依据
    const blocked: OrphanScanResult = { error: registryFresh.error, candidates: [] };
    return blocked;
  }
  const knownFresh: string[] = allTrackedPaths(registryFresh);
  const candidates: string[] = [];
  for (const folder of MANAGED_FILE_FOLDERS) {
    let names: string[] = [];
    try {
      names = await fileIo.listFile(`${filesDirPath()}/${folder}`);
    } catch {
      continue; // 目录不存在 → 该 folder 无候选
    }
    for (const name of names) {
      const rel: string = `${folder}/${name}`;
      if (knownFresh.indexOf(rel) >= 0 || known.indexOf(rel) >= 0) continue;
      if (referenced.indexOf(rel) >= 0) continue;
      if (candidates.indexOf(rel) < 0) candidates.push(rel);
    }
  }
  candidates.sort();
  const result: OrphanScanResult = { error: '', candidates };
  return result;
};

// 临界段内的核心逻辑:锁已由 deleteManagedOrphans 持有。
// 会话引用快照在本锁内采集 —— ChatRdbRepository.save/delete/restore 也走
// enqueueConversationFileMutation(经核:list/getById 只 ensureStore 不取该锁,
// 故内部调用不会自嵌套死锁),因此临界段内不会有新会话引用落库。
// 但 ManagedFile 登记写走独立的 enqueueWrite 队列,不受本锁约束 → 逐条 unlink 前
// 重新读最新登记,且检查后立即同步 unlink,消除 await 窗口。
const deleteManagedOrphansLocked = async (
  repository: ConversationRepository, planned: string[],
): Promise<number> => {
  if (isChatStorageDegraded()) throw new Error(DEGRADED_REF_ERROR);
  const registry: ManagedRegistryHealth = await loadManagedRegistryForCleanup();
  if (registry.status !== 'ok') throw new Error(registry.error);
  const known: string[] = allTrackedPaths(registry);
  const referenced: string[] = await referencedUploadPaths(repository);
  if (isChatStorageDegraded()) throw new Error(DEGRADED_REF_ERROR);
  let removed: number = 0;
  for (const rel of planned) {
    if (known.indexOf(rel) >= 0) continue; // 确认期间已被登记
    if (referenced.indexOf(rel) >= 0) continue; // 确认期间被会话引用
    // 最新登记再查:注册写（trackUploadFile）不入本锁,可能在快照后新增该文件
    const latest: ManagedRegistryHealth = await loadManagedRegistryForCleanup();
    if (latest.status !== 'ok') throw new Error(latest.error);
    if (allTrackedPaths(latest).indexOf(rel) >= 0) continue;
    // 检查后不 await,直接同步 unlink:同一微任务内无其他写者可插入
    try {
      fileIo.unlinkSync(`${filesDirPath()}/${rel}`);
      removed += 1;
    } catch {
      // 单文件失败跳过(可能已不存在)
    }
  }
  return removed;
};

// 用户确认后的执行入口:整段(refresh 登记 → reference 快照 → 逐条删除)置于
// 既有 enqueueConversationFileMutation 临界段内,与会话写入互斥。
// planned 为外部输入,先用 isSafeOrphanPath 过滤,非法项一律不删。
export const deleteManagedOrphans = async (
  repository: ConversationRepository, planned: string[],
): Promise<number> => {
  const valid: string[] = [];
  for (const rel of planned) {
    if (isSafeOrphanPath(rel) && valid.indexOf(rel) < 0) valid.push(rel);
  }
  let removed: number = 0;
  await enqueueConversationFileMutation(async (): Promise<void> => {
    removed = await deleteManagedOrphansLocked(repository, valid);
  });
  return removed;
};

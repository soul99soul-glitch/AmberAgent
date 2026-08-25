// ManagedFileStore — managed_files JSON 持久化(D-086)
// Android 基准:ManagedFileDAO.kt(Room)→ 本文件 JSON 等价;
//   Room @Insert(REPLACE)+AUTOINCREMENT 语义:nextId 高水位不复用,
//   relative_path 唯一冲突 → 删旧换新 id;@Update 未命中 no-op;
//   deleteBy* 返回受影响行数;listByFolder ORDER created_at DESC。
// 存储:filesDir/managed_files.json({nextId, rows});写操作 enqueueWrite 串行。
import { Context } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import {
  createFilesRepository, deleteChatFiles, conversationFileUris, clearPendingQueueStorage,
} from '@amber/chat-domain';
import { removeFavoritesOfConversation } from './FavoriteStore.ets';
import { writeTextFileAtomic, quarantineIfStillInvalid } from './AtomicJsonFile.ets';
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

// 缺失 → 空表;损坏 → quarantineFile 保留 .bad 后按空表回退(MemoryStore.readJsonArray 同策略)
const readShape = async (): Promise<ManagedFileFileShape> => {
  const path: string = storePath();
  let text: string;
  try {
    const ok: boolean = await fileIo.access(path);
    if (!ok) return emptyShape();
    text = await fileIo.readText(path);
  } catch {
    return emptyShape();
  }
  try {
    const parsed: ManagedFileFileShape = JSON.parse(text) as ManagedFileFileShape;
    if (typeof parsed.nextId === 'number' && Array.isArray(parsed.rows)) {
      return parsed;
    }
  } catch {
    // 落入下方隔离
  }
  await quarantineIfStillInvalid(path, (t: string): boolean => {
    try {
      const again: ManagedFileFileShape = JSON.parse(t) as ManagedFileFileShape;
      return !(typeof again.nextId === 'number' && Array.isArray(again.rows));
    } catch {
      return true;
    }
  });
  return emptyShape();
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
      const shape: ManagedFileFileShape = await readShape();
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
      const shape: ManagedFileFileShape = await readShape();
      const index: number = shape.rows.findIndex(
        (r: ManagedFileEntity): boolean => r.id === file.id);
      if (index < 0) return; // @Update 未命中 = 0 行
      shape.rows[index] = file;
      await writeShape(shape);
    }),
  getById: async (id: number): Promise<ManagedFileEntity | null> => {
    const shape: ManagedFileFileShape = await readShape();
    const found: ManagedFileEntity | undefined = shape.rows.find(
      (r: ManagedFileEntity): boolean => r.id === id);
    return found !== undefined ? found : null;
  },
  getByPath: async (relativePath: string): Promise<ManagedFileEntity | null> => {
    const shape: ManagedFileFileShape = await readShape();
    const found: ManagedFileEntity | undefined = shape.rows.find(
      (r: ManagedFileEntity): boolean => r.relativePath === relativePath);
    return found !== undefined ? found : null;
  },
  listByFolder: async (folder: string): Promise<ManagedFileEntity[]> => {
    const shape: ManagedFileFileShape = await readShape();
    return shape.rows.filter((r: ManagedFileEntity): boolean => r.folder === folder)
      .sort((a: ManagedFileEntity, b: ManagedFileEntity): number => b.createdAt - a.createdAt);
  },
  deleteById: (id: number): Promise<number> =>
    enqueueWrite(async (): Promise<number> => {
      const shape: ManagedFileFileShape = await readShape();
      const before: number = shape.rows.length;
      shape.rows = shape.rows.filter((r: ManagedFileEntity): boolean => r.id !== id);
      await writeShape(shape);
      return before - shape.rows.length;
    }),
  deleteByPath: (relativePath: string): Promise<number> =>
    enqueueWrite(async (): Promise<number> => {
      const shape: ManagedFileFileShape = await readShape();
      const before: number = shape.rows.length;
      shape.rows = shape.rows.filter(
        (r: ManagedFileEntity): boolean => r.relativePath !== relativePath);
      await writeShape(shape);
      return before - shape.rows.length;
    }),
  deleteByFolder: (folder: string): Promise<number> =>
    enqueueWrite(async (): Promise<number> => {
      const shape: ManagedFileFileShape = await readShape();
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
  await removeFavoritesOfConversation(conv.id);
});

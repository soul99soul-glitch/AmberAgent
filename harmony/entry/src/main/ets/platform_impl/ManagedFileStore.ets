// ManagedFileStore — managed_files JSON 持久化(D-086)
// Android 基准:ManagedFileDAO.kt(Room)→ 本文件 JSON 等价;
//   Room @Insert(REPLACE)+AUTOINCREMENT 语义:nextId 高水位不复用,
//   relative_path 唯一冲突 → 删旧换新 id;@Update 未命中 no-op;
//   deleteBy* 返回受影响行数;listByFolder ORDER created_at DESC。
// 存储:filesDir/managed_files.json({nextId, rows});写操作 enqueueWrite 串行。
import { Context } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { createFilesRepository, deleteChatFiles, conversationFileUris } from '@amber/chat-domain';
import type {
  DeleteChatFilesDeps, FilesRepository, ManagedFileDaoPort, ManagedFileEntity,
  Conversation,
} from '@amber/chat-domain';

interface ManagedFileFileShape {
  nextId: number;
  rows: ManagedFileEntity[];
}

const EMPTY_SHAPE: ManagedFileFileShape = { nextId: 1, rows: [] };

const storePath = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${ctx.filesDir}/managed_files.json`;
};

// 缺失/损坏 → 空表(MemoryStore.readJsonArray 同策略)
const readShape = async (): Promise<ManagedFileFileShape> => {
  const path: string = storePath();
  try {
    const ok: boolean = await fileIo.access(path);
    if (!ok) return EMPTY_SHAPE;
    const text: string = await fileIo.readText(path);
    const parsed: ManagedFileFileShape = JSON.parse(text) as ManagedFileFileShape;
    if (typeof parsed.nextId !== 'number' || !Array.isArray(parsed.rows)) {
      return EMPTY_SHAPE;
    }
    return parsed;
  } catch {
    return EMPTY_SHAPE;
  }
};

const writeShape = async (shape: ManagedFileFileShape): Promise<void> => {
  const path: string = storePath();
  const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
  const file: fileIo.File = await fileIo.open(path, mode);
  try {
    await fileIo.write(file.fd, JSON.stringify(shape));
  } finally {
    await fileIo.close(file);
  }
};

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
//   favoriteDAO.deleteByConversation:favorites 面未移植(无对应表);
//   deleteChatImagesDir:依附 generate_image 工具,未移植(P1 登记)
export const cleanupDeletedConversation = async (conv: Conversation): Promise<void> => {
  await deleteChatFiles(getDeleteChatFilesDeps(), conversationFileUris(conv));
};

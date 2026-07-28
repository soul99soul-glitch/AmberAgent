// MemoryStore — 记忆记录 JSON 文件存储(D-085b,读路径 + touch)
// Android 基准:MemoryRepository.kt(Room)→ 本文件以 JSON 文件持久化;
//   DAO 无 ORDER BY → 数组序即插入序;id 自增语义留待写路径切片(addMemory 等)。
//   查询/更新语义纯函数在 HAR(filterActiveMemoryRecords/touchMemoryRecords)。
import { Context } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import type { MemoryReadRepository, MemoryRecord, MemoryScope } from '@amber/chat-domain';
import { filterActiveMemoryRecords, touchMemoryRecords } from '@amber/chat-domain';

const MEMORY_FOLDER: string = 'memory';
const MEMORY_FILE: string = 'memory_records.json';

const memoryFilePath = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${ctx.filesDir}/${MEMORY_FOLDER}/${MEMORY_FILE}`;
};

const readAll = async (): Promise<MemoryRecord[]> => {
  const path: string = memoryFilePath();
  try {
    const ok: boolean = await fileIo.access(path);
    if (!ok) return []; // 文件不存在 → 空库(等价空表)
    const text: string = await fileIo.readText(path);
    const parsed: MemoryRecord[] = JSON.parse(text) as MemoryRecord[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return []; // 损坏 → 空(不崩溃;写路径切片落地备份策略)
  }
};

const writeAll = async (records: MemoryRecord[]): Promise<void> => {
  const path: string = memoryFilePath();
  const dir: string = path.substring(0, path.lastIndexOf('/'));
  try {
    await fileIo.mkdir(dir, true);
  } catch {
    // 已存在
  }
  const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
  const file: fileIo.File = await fileIo.open(path, mode);
  try {
    await fileIo.write(file.fd, JSON.stringify(records));
  } finally {
    await fileIo.close(file);
  }
};

// 突变串行化(touch 与后续写路径切片互斥)
let writeQueue: Promise<void> = Promise.resolve();

const enqueueWrite = (op: () => Promise<void>): Promise<void> => {
  const next: Promise<void> = writeQueue.then(op, op);
  writeQueue = next.catch((): void => {});
  return next;
};

// MemoryReadRepository 实现(MemoryRepository.kt:69-72 getActiveRecords +
//   :149-153 touchMemories 空 ids no-op)
export const getMemoryReadRepository = (): MemoryReadRepository => ({
  getActiveRecords: async (scopes: MemoryScope[], now: number): Promise<MemoryRecord[]> => {
    const all: MemoryRecord[] = await readAll();
    return filterActiveMemoryRecords(all, scopes, now);
  },
  touchMemories: (ids: number[]): Promise<void> => {
    if (ids.length === 0) return Promise.resolve(); // MemoryRepository.kt:150
    return enqueueWrite(async (): Promise<void> => {
      const all: MemoryRecord[] = await readAll();
      await writeAll(touchMemoryRecords(all, ids, Date.now()));
    });
  },
});

// MemoryStore — 记忆记录 JSON 文件存储(D-085b,读路径 + touch)
// Android 基准:MemoryRepository.kt(Room)→ 本文件以 JSON 文件持久化;
//   DAO 无 ORDER BY → 数组序即插入序;id 自增语义留待写路径切片(addMemory 等)。
//   查询/更新语义纯函数在 HAR(filterActiveMemoryRecords/touchMemoryRecords)。
import { Context } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import type {
  AssistantMemory, MemoryAddParams, MemoryReadRepository, MemoryRecord, MemoryScope,
  MemoryToolDeps, MemoryToolWriteRequest,
} from '@amber/chat-domain';
import {
  appendMemoryRecord, deleteMemoryRecord, filterActiveMemoryRecords, memoriesOfAssistant,
  memoryRecordToAssistantMemory, touchMemoryRecords, updateMemoryRecordContent,
} from '@amber/chat-domain';

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

// 突变串行化(touch 与写路径互斥;泛型 — op 结果透传给调用方)
let writeQueue: Promise<void> = Promise.resolve();

const enqueueWrite = <T>(op: () => Promise<T>): Promise<T> => {
  const next: Promise<T> = writeQueue.then(op, op);
  writeQueue = next.then((): void => {}, (): void => {});
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

// ===== D-085c:写路径(MemoryRepository.kt addMemory/updateContent/deleteMemory) =====

// addMemory(:102-133)— id 自增在 appendMemoryRecord(max+1);返回所插记录
const addMemory = (params: MemoryAddParams): Promise<MemoryRecord> =>
  enqueueWrite(async (): Promise<MemoryRecord> => {
    const all: MemoryRecord[] = await readAll();
    const out = appendMemoryRecord(all, params, Date.now());
    await writeAll(out.records);
    return out.record;
  });

// updateContent(:84-89)— 未命中错误消息逐字,经 enqueueWrite 传播
const updateContent = (id: number, content: string): Promise<MemoryRecord> =>
  enqueueWrite(async (): Promise<MemoryRecord> => {
    const all: MemoryRecord[] = await readAll();
    const out = updateMemoryRecordContent(all, id, content, Date.now());
    await writeAll(out.records);
    return out.record;
  });

// deleteMemory(:145-147)— 未命中 no-op
const deleteMemory = (id: number): Promise<void> =>
  enqueueWrite(async (): Promise<void> => {
    const all: MemoryRecord[] = await readAll();
    await writeAll(deleteMemoryRecord(all, id));
  });

// getMemoriesOfAssistant(DAO :16-17)— 桶精确匹配,插入序
const listMemoriesOfAssistant = async (assistantId: string): Promise<AssistantMemory[]> =>
  memoriesOfAssistant(await readAll(), assistantId);

// memoryBucket(ChatService.kt:2400-2405)— else → long_term 桶(逐字)
const memoryBucket = (scope: string): string => {
  if (scope === 'core') return '__global__';
  if (scope === 'short_term') return '__short_term__';
  return '__long_term__';
};

// createMemoryTools 接线(ChatService.kt:2345-2397):
//   onList — core→getGlobalMemories()/short/long 桶,else → [](:2357-2362);
//   onCreation — source 非空白拼 '\nSource: '(:2365-2369)+ addMemory(
//     assistantId=memoryBucket(scope.wireName))(:2370-2379);
//   onUpdate → updateContent;onDelete → deleteMemory
export const getMemoryToolDeps = (todayText: string): MemoryToolDeps => ({
  todayText,
  onList: async (scope: string): Promise<AssistantMemory[]> => {
    if (scope !== 'core' && scope !== 'short_term' && scope !== 'long_term') return [];
    return listMemoriesOfAssistant(memoryBucket(scope));
  },
  onCreation: async (request: MemoryToolWriteRequest): Promise<AssistantMemory> => {
    const finalContent: string = request.source === null || request.source.trim().length === 0
      ? request.content
      : `${request.content}\nSource: ${request.source}`;
    const record: MemoryRecord = await addMemory({
      scope: request.scope,
      kind: request.kind,
      content: finalContent,
      assistantId: memoryBucket(request.scope),
      sourceConversationId: request.sourceConversationId,
      sourceMessageIds: request.sourceMessageIds,
      expiresAt: request.expiresAt,
      confidence: request.confidence,
    });
    return memoryRecordToAssistantMemory(record);
  },
  onUpdate: async (id: number, content: string): Promise<AssistantMemory> =>
    memoryRecordToAssistantMemory(await updateContent(id, content)),
  onDelete: (id: number): Promise<void> => deleteMemory(id),
});

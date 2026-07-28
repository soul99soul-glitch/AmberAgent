// MemoryStore — 记忆记录 JSON 文件存储(D-085b,读路径 + touch)
// Android 基准:MemoryRepository.kt(Room)→ 本文件以 JSON 文件持久化;
//   DAO 无 ORDER BY → 数组序即插入序;id 自增语义留待写路径切片(addMemory 等)。
//   查询/更新语义纯函数在 HAR(filterActiveMemoryRecords/touchMemoryRecords)。
import { Context } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import type {
  AssistantMemory, MemoryAddParams, MemoryCandidate, MemoryEvent, MemoryEventType,
  MemoryExportFs, MemoryImportExportDeps, MemoryReadRepository, MemoryRecord, MemoryScope,
  MemoryToolDeps, MemoryToolWriteRequest,
} from '@amber/chat-domain';
import {
  appendMemoryRecord, deleteMemoryRecord, filterActiveMemoryRecords, memoriesOfAssistant,
  memoryRecordToAssistantMemory, touchMemoryRecords, updateMemoryRecordContent,
  upsertMemoryRecord,
} from '@amber/chat-domain';

const MEMORY_FOLDER: string = 'memory';
const MEMORY_FILE: string = 'memory_records.json';
const CANDIDATES_FILE: string = 'memory_candidates.json';
const EVENTS_FILE: string = 'memory_events.json';

const memoryPath = (name: string): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${ctx.filesDir}/${MEMORY_FOLDER}/${name}`;
};

const readJsonArray = async <T>(path: string): Promise<T[]> => {
  try {
    const ok: boolean = await fileIo.access(path);
    if (!ok) return []; // 文件不存在 → 空库(等价空表)
    const text: string = await fileIo.readText(path);
    const parsed: T[] = JSON.parse(text) as T[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return []; // 损坏 → 空(不崩溃;写路径切片落地备份策略)
  }
};

const writeJsonArray = async <T>(path: string, items: T[]): Promise<void> => {
  const dir: string = path.substring(0, path.lastIndexOf('/'));
  try {
    await fileIo.mkdir(dir, true);
  } catch {
    // 已存在
  }
  const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
  const file: fileIo.File = await fileIo.open(path, mode);
  try {
    await fileIo.write(file.fd, JSON.stringify(items));
  } finally {
    await fileIo.close(file);
  }
};

const readAll = (): Promise<MemoryRecord[]> => readJsonArray<MemoryRecord>(memoryPath(MEMORY_FILE));

const writeAll = (records: MemoryRecord[]): Promise<void> =>
  writeJsonArray<MemoryRecord>(memoryPath(MEMORY_FILE), records);

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

// ===== D-085d:候选 + 遥测事件持久化 =====

// addCandidate/addCandidates(MemoryRepository.kt:166-172)— 追加(insertAll)
const addCandidate = (candidate: MemoryCandidate): Promise<void> =>
  enqueueWrite(async (): Promise<void> => {
    const all: MemoryCandidate[] = await readJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE));
    await writeJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE), [...all, candidate]);
  });

const addCandidates = (candidates: MemoryCandidate[]): Promise<void> => {
  if (candidates.length === 0) return Promise.resolve(); // insertAll(空)= no-op
  return enqueueWrite(async (): Promise<void> => {
    const all: MemoryCandidate[] = await readJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE));
    await writeJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE), [...all, ...candidates]);
  });
};

// addEvent(:203-205)— message take(2_000) 在 toEntity(:316),此处截断对齐
const addEvent = (event: MemoryEvent): Promise<void> =>
  enqueueWrite(async (): Promise<void> => {
    const all: MemoryEvent[] = await readJsonArray<MemoryEvent>(memoryPath(EVENTS_FILE));
    const truncated: MemoryEvent = {
      id: event.id, type: event.type, conversationId: event.conversationId,
      memoryId: event.memoryId, candidateId: event.candidateId, modelId: event.modelId,
      message: event.message.slice(0, 2000), durationMs: event.durationMs,
      messageCount: event.messageCount, createdAt: event.createdAt,
    };
    await writeJsonArray<MemoryEvent>(memoryPath(EVENTS_FILE), [...all, truncated]);
  });

// countEventsSince(MemoryEventDAO :21-22)— COUNT WHERE event_type = :t AND created_at >= :after
const countEventsSince = async (type: MemoryEventType, createdAfter: number): Promise<number> => {
  const all: MemoryEvent[] = await readJsonArray<MemoryEvent>(memoryPath(EVENTS_FILE));
  return all.filter((e: MemoryEvent): boolean => e.type === type && e.createdAt >= createdAfter)
    .length;
};

// getAllActiveRecords(MemoryRepository.kt:74-75)— archived=0 && (expires null||>now),插入序
const getAllActiveRecords = async (now: number): Promise<MemoryRecord[]> => {
  const all: MemoryRecord[] = await readAll();
  return all.filter((r: MemoryRecord): boolean =>
    !r.archived && (r.expiresAt === null || r.expiresAt > now));
};

// runMemoryExtraction 的存储面 deps(模型解析/generateText/locale 由 ChatPage 供给)
export interface MemoryExtractionStoreOps {
  addCandidates: (candidates: MemoryCandidate[]) => Promise<void>;
  addCandidate: (candidate: MemoryCandidate) => Promise<void>;
  addMemory: (params: MemoryAddParams) => Promise<MemoryRecord>;
  addEvent: (event: MemoryEvent) => Promise<void>;
  countEventsSince: (type: MemoryEventType, createdAfter: number) => Promise<number>;
  getAllActiveRecords: (now: number) => Promise<MemoryRecord[]>;
}

export const getMemoryExtractionStoreOps = (): MemoryExtractionStoreOps => ({
  addCandidates,
  addCandidate,
  addMemory,
  addEvent,
  countEventsSince,
  getAllActiveRecords,
});

// ===== D-085e:导出导入存储面 + FS 实现 =====

// getRecentEvents(MemoryEventDAO :15-16)— created_at DESC LIMIT :limit
const getRecentEvents = async (limit: number): Promise<MemoryEvent[]> => {
  const all: MemoryEvent[] = await readJsonArray<MemoryEvent>(memoryPath(EVENTS_FILE));
  const sorted: MemoryEvent[] = [...all].sort((a: MemoryEvent, b: MemoryEvent): number =>
    b.createdAt - a.createdAt);
  return sorted.slice(0, limit);
};

// upsertRecord(MemoryRepository.kt:135-143)
const upsertRecord = (record: MemoryRecord): Promise<MemoryRecord> =>
  enqueueWrite(async (): Promise<MemoryRecord> => {
    const all: MemoryRecord[] = await readAll();
    const out = upsertMemoryRecord(all, record, Date.now());
    await writeAll(out.records);
    return out.record;
  });

// MemoryExportFs 的 fileIo 实现(任意目录 — 导出目标由调用方选,
//   设置面 UI 移植后接 picker 路径)
const walkFilesRec = async (dir: string, out: string[]): Promise<void> => {
  let names: string[];
  try {
    names = await fileIo.listFile(dir);
  } catch {
    return; // 不存在/不可读 → 空(File.walkTopDown 对不存在目录产出空序列)
  }
  for (const name of names) {
    const full: string = `${dir}/${name}`;
    try {
      const stat: fileIo.Stat = await fileIo.stat(full);
      if (stat.isDirectory()) {
        await walkFilesRec(full, out);
      } else {
        out.push(full);
      }
    } catch {
      // 单点失败跳过
    }
  }
};

const entryMemoryExportFs: MemoryExportFs = {
  mkdirs: async (path: string): Promise<void> => {
    try {
      await fileIo.mkdir(path, true);
    } catch {
      // 已存在
    }
  },
  writeText: async (path: string, content: string): Promise<void> => {
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
    const file: fileIo.File = await fileIo.open(path, mode);
    try {
      await fileIo.write(file.fd, content);
    } finally {
      await fileIo.close(file);
    }
  },
  readText: (path: string): Promise<string> => fileIo.readText(path),
  exists: (path: string): Promise<boolean> => fileIo.access(path),
  walkFiles: async (path: string): Promise<string[]> => {
    const out: string[] = [];
    await walkFilesRec(path, out);
    return out;
  },
};

// exportMemoriesTo/importMemoriesFrom 的 deps(UI 触发随设置面切片)
export const getMemoryImportExportDeps = (): MemoryImportExportDeps => ({
  fs: entryMemoryExportFs,
  getAllRecords: readAll,
  getRecentEvents,
  upsertRecord,
});

// ===== D-085f:dream 存储面 =====

// getPendingCandidates(MemoryCandidateDAO :19-20)— status='pending' ORDER created_at DESC
const getPendingCandidates = async (): Promise<MemoryCandidate[]> => {
  const all: MemoryCandidate[] = await readJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE));
  return all.filter((c: MemoryCandidate): boolean => c.status === 'pending').reverse();
};

// getAllCandidates(DAO :22-23)— ORDER created_at DESC(文件为时间升序 → 反转)
const getAllCandidates = async (): Promise<MemoryCandidate[]> => {
  const all: MemoryCandidate[] = await readJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE));
  return [...all].reverse();
};

// updateCandidate(MemoryRepository.kt:174-176)— copy(updatedAt=now) 后 update;
//   Room @Update 未命中 = 0 行(no-op)
const updateCandidate = (candidate: MemoryCandidate): Promise<void> =>
  enqueueWrite(async (): Promise<void> => {
    const all: MemoryCandidate[] = await readJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE));
    const index: number = all.findIndex((c: MemoryCandidate): boolean => c.id === candidate.id);
    if (index < 0) return;
    const updated: MemoryCandidate = {
      id: candidate.id, content: candidate.content, scope: candidate.scope,
      kind: candidate.kind, sourceConversationId: candidate.sourceConversationId,
      sourceMessageIds: candidate.sourceMessageIds, expiresAt: candidate.expiresAt,
      confidence: Math.min(1, Math.max(0, candidate.confidence)), reason: candidate.reason,
      sensitive: candidate.sensitive, status: candidate.status,
      createdAt: candidate.createdAt, updatedAt: Date.now(),
    };
    const next: MemoryCandidate[] = [...all];
    next[index] = updated;
    await writeJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE), next);
  });

export interface MemoryDreamStoreOps {
  getAllRecords: () => Promise<MemoryRecord[]>;
  getPendingCandidates: () => Promise<MemoryCandidate[]>;
  getAllCandidates: () => Promise<MemoryCandidate[]>;
  upsertRecord: (record: MemoryRecord) => Promise<MemoryRecord>;
  addMemory: (params: MemoryAddParams) => Promise<MemoryRecord>;
  updateCandidate: (candidate: MemoryCandidate) => Promise<void>;
  addEvent: (event: MemoryEvent) => Promise<void>;
}

// runMemoryDreamPlan/runMemoryDreamApply 的存储面 deps
//   (模型解析/generateText/调度触发随 dream 调度切片与设置面,P1 登记)
export const getMemoryDreamStoreOps = (): MemoryDreamStoreOps => ({
  getAllRecords: readAll,
  getPendingCandidates,
  getAllCandidates,
  upsertRecord,
  addMemory,
  updateCandidate,
  addEvent,
});

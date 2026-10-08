import { Context } from '@kit.AbilityKit';
// MemoryStore — 记忆记录 JSON 文件存储(D-085b,读路径 + touch)
// Android 基准:MemoryRepository.kt(Room)→ 本文件以 JSON 文件持久化;
//   DAO 无 ORDER BY → 数组序即插入序;id 自增语义留待写路径切片(addMemory 等)。
//   查询/更新语义纯函数在 HAR(filterActiveMemoryRecords/touchMemoryRecords)。import { Context } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import {
  createMemoryDreamPlanStore, memoryDreamPlanSummaryText,
  DREAM_NOTIFICATION_ID, DREAM_NOTIFY_FAILED_TITLE, DREAM_NOTIFY_PENDING_TITLE,
  DREAM_NOTIFY_RUNNING_TEXT, DREAM_NOTIFY_RUNNING_TITLE,
} from '@amber/chat-domain';
import type {
  AssistantMemory, MemoryAddParams, MemoryCandidate, MemoryEvent, MemoryEventType,
  MemoryExportFs, MemoryImportExportDeps, MemoryReadRepository, MemoryRecord, MemoryScope,
  MemoryToolDeps, MemoryToolWriteRequest, MemoryDerivedDocument, MemoryTopicApplyResult,
  MemoryTopicSuggestion, MemoryExtractionAction, MemoryExtractionApplyResult, MemoryProfile, MemoryProfilePassOutput, MemorySemanticMerge,
} from '@amber/chat-domain';
import type {
  MemoryDreamPlan, MemoryDreamPlanDaoPort, MemoryDreamPlanEntity,
  MemoryDreamPlanStore, MemoryDreamReviewNotifier,
} from '@amber/chat-domain';

import {
  appendMemoryRecord, deleteMemoryRecord, filterActiveMemoryRecords, memoriesOfAssistant,
  memoryRecordToAssistantMemory, touchMemoryRecords, updateMemoryRecordContent,
  upsertMemoryRecord, applyMemoryExtractionActions, reinforceMemoryRecords, restoreMemoryRecord, isMemoryActive, makeMemoryEvent,
  applyMemoryTopicSuggestions, makeMemoryRecord, pruneMemoryTopics, memoryProfileSources, buildMemoryProfile,
  nearDuplicateCandidates, planConfirmedMemoryMerges, remapMemoryProfileItems, invalidateMemoryTopicsForSource,
} from '@amber/chat-domain';
import { writeTextFileAtomic, readGuardedJson } from './AtomicJsonFile.ets';
import type { GuardedJsonRead } from './AtomicJsonFile.ets';
import { publishBasicNotification, cancelNotification } from './NotificationNotifier.ets';
import { listMemoryDocuments, readMemoryDocument, syncMemoryDocuments } from './MemoryDocumentStore.ets';

import { refreshMemoryProfile, writeMemoryProfile } from './MemoryOptimizationStore.ets';
import type { MemoryOptimizationApplyResult } from './MemoryOptimizationStore.ets';

const MEMORY_FOLDER: string = 'memory';
const MEMORY_FILE: string = 'memory_records.json';
const CANDIDATES_FILE: string = 'memory_candidates.json';
const EVENTS_FILE: string = 'memory_events.json';

const memoryPath = (name: string): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${ctx.filesDir}/${MEMORY_FOLDER}/${name}`;
};

// 结构校验:仅接受 JSON 数组;解析失败/非数组 → null(触发隔离,不是空库)
const parseJsonArray = <T>(text: string): T[] | null => {
  try {
    const parsed = JSON.parse(text) as T[];
    return Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
};

// 只读路径:损坏/IO 错时降级为空(不写回,故不丢数据;召回/列表为空可恢复),
// 避免把「读失败」变成上层调用链的硬错误。写路径不走这里(见 requireArrayForWrite)。
const readJsonArray = async <T>(path: string): Promise<T[]> => {
  const outcome: GuardedJsonRead<T[]> =
    await readGuardedJson<T[]>(path, (t: string): T[] | null => parseJsonArray<T>(t));
  return outcome.status === 'ok' && outcome.value !== null ? outcome.value : [];
};

const writeJsonArray = async <T>(path: string, items: T[]): Promise<void> =>
  writeTextFileAtomic(path, JSON.stringify(items));

// 写基准三态:missing → 可作首次初始化(空数组);blocked/io_error → 拒绝写入。
// 仅返回错误文案,由调用方决定如何暴露(此处一律 throw,不静默用空快照覆盖)。
interface ArrayWriteBase<T> {
  error: string;
  items: T[] | null;
}

const loadArrayForWrite = async <T>(path: string): Promise<ArrayWriteBase<T>> => {
  const outcome: GuardedJsonRead<T[]> =
    await readGuardedJson<T[]>(path, (t: string): T[] | null => parseJsonArray<T>(t));
  if (outcome.status === 'ok') {
    const ok: ArrayWriteBase<T> = { error: '', items: outcome.value };
    return ok;
  }
  if (outcome.status === 'missing') {
    const firstInit: ArrayWriteBase<T> = { error: '', items: [] };
    return firstInit;
  }
  const reason: string = outcome.status === 'blocked'
    ? '存储文件损坏已被隔离,拒绝以空数据覆盖(需恢复或显式重置)'
    : '存储文件读取失败,拒绝覆盖';
  const bad: ArrayWriteBase<T> = { error: reason, items: null };
  return bad;
};

const requireArrayForWrite = async <T>(path: string): Promise<T[]> => {
  const read: ArrayWriteBase<T> = await loadArrayForWrite<T>(path);
  if (read.error !== '') throw new Error(read.error);
  return read.items !== null ? read.items : [];
};

const normalizeRecords = (records: MemoryRecord[]): MemoryRecord[] => records.map(
  (record: MemoryRecord): MemoryRecord => makeMemoryRecord(record));
const readAll = async (): Promise<MemoryRecord[]> =>
  normalizeRecords(await readJsonArray<MemoryRecord>(memoryPath(MEMORY_FILE)));

// 写路径基准:损坏/IO 错时抛错(不再返回空库)
const readAllForWrite = async (): Promise<MemoryRecord[]> =>
  normalizeRecords(await requireArrayForWrite<MemoryRecord>(memoryPath(MEMORY_FILE)));


const writeAll = (records: MemoryRecord[]): Promise<void> =>
  writeJsonArray<MemoryRecord>(memoryPath(MEMORY_FILE), records);

const publishMemoryRevision = (): void => {
  AppStorage.setOrCreate<number>('memoryRevision', (AppStorage.get<number>('memoryRevision') ?? 0) + 1);
};

export class MemoryDocumentRefreshError extends Error {
  public readonly sourceSaved: boolean;
  public readonly documentError: string;
  public readonly topicResult: MemoryTopicApplyResult | null;
  constructor(message: string, topicResult: MemoryTopicApplyResult | null = null, sourceSaved: boolean = true) {
    super(sourceSaved ? `记忆源已保存，文档刷新失败：${message}` : `记忆文档刷新失败：${message}`);
    this.documentError = message; this.topicResult = topicResult; this.sourceSaved = sourceSaved;
  }
}

// The successful source commit is never rolled back after a derived file failure.
const saveRecords = async (records: MemoryRecord[], topicResult: MemoryTopicApplyResult | null = null): Promise<void> => {
  const next: MemoryRecord[] = pruneMemoryTopics(records, Date.now()).records;
  await writeAll(next);
  publishMemoryRevision();
  try {
    const profile: MemoryProfile | null = await refreshMemoryProfile(next, Date.now());
    await syncMemoryDocuments(next, Date.now(), profile);
  }
  catch (error) {
    throw new MemoryDocumentRefreshError(error instanceof Error ? error.message : String(error), topicResult);
  }
};

const syncProjection = async (records: MemoryRecord[], now: number,
  topicResult: MemoryTopicApplyResult | null = null): Promise<void> => {
  try { await syncMemoryDocuments(records, now, await refreshMemoryProfile(records, now)); }
  catch (error) {
    throw new MemoryDocumentRefreshError(error instanceof Error ? error.message : String(error), topicResult, false);
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
  reinforceMemories: (ids: number[], sourceId: string): Promise<void> => enqueueWrite(async (): Promise<void> => {
    const all: MemoryRecord[] = await readAllForWrite();
    const next: MemoryRecord[] = reinforceMemoryRecords(all, ids, Date.now(), sourceId);
    if (next.some((row: MemoryRecord, index: number): boolean => row !== all[index])) await saveRecords(next);
  }),
  touchMemories: (ids: number[]): Promise<void> => {
    if (ids.length === 0) return Promise.resolve(); // MemoryRepository.kt:150
    return enqueueWrite(async (): Promise<void> => {
      const all: MemoryRecord[] = await readAllForWrite();
      await writeAll(touchMemoryRecords(all, ids, Date.now()));
    });
  },
});

// ===== D-085c:写路径(MemoryRepository.kt addMemory/updateContent/deleteMemory) =====

// addMemory(:102-133)— id 自增在 appendMemoryRecord(max+1);返回所插记录
const addMemory = (params: MemoryAddParams): Promise<MemoryRecord> =>
  enqueueWrite(async (): Promise<MemoryRecord> => {
    const all: MemoryRecord[] = await readAllForWrite();
    const out = appendMemoryRecord(all, params, Date.now());
    await saveRecords(out.records);
    return out.record;
  });

// updateContent(:84-89)— 未命中错误消息逐字,经 enqueueWrite 传播
const updateContent = (id: number, content: string): Promise<MemoryRecord> =>
  enqueueWrite(async (): Promise<MemoryRecord> => {
    const all: MemoryRecord[] = await readAllForWrite();
    const out = updateMemoryRecordContent(all, id, content, Date.now());
    await saveRecords(out.records);
    return out.record;
  });

// deleteMemory(:145-147)— 未命中 no-op
const deleteMemory = (id: number): Promise<void> =>
  enqueueWrite(async (): Promise<void> => {
    const all: MemoryRecord[] = await readAllForWrite();
    await saveRecords(deleteMemoryRecord(all, id));
  });

const updatePinned = (id: number, pinned: boolean): Promise<MemoryRecord> =>
  enqueueWrite(async (): Promise<MemoryRecord> => {
    const all: MemoryRecord[] = await readAllForWrite();
    const index: number = all.findIndex((r: MemoryRecord): boolean => r.id === id);
    if (index < 0) throw new Error(`Memory record #${id} not found`);
    const row: MemoryRecord = all[index];
    if (row.kind === 'topic') throw new Error('主题为自动生成的只读记忆');
    const updated: MemoryRecord = {
      id: row.id, content: row.content, scope: row.scope, kind: row.kind,
      assistantId: row.assistantId, sourceConversationId: row.sourceConversationId,
      sourceMessageIds: row.sourceMessageIds, supersedesIds: row.supersedesIds,
      expiresAt: row.expiresAt, confidence: row.confidence, pinned,
      archived: row.archived, createdAt: row.createdAt, updatedAt: Date.now(),
      lastUsedAt: row.lastUsedAt,
      topicTitle: row.topicTitle, memberIds: row.memberIds,
      evidence: row.evidence, invalidatedAt: row.invalidatedAt,
      reinforcementCount: row.reinforcementCount, lastReinforcedAt: row.lastReinforcedAt,
      lastReinforcementSource: row.lastReinforcementSource,
    };
    const next: MemoryRecord[] = [...all];
    next[index] = updated;
    await saveRecords(next);
    return updated;
  });

// getMemoriesOfAssistant(DAO :16-17)— 桶精确匹配,插入序
const listMemoriesOfAssistant = async (assistantId: string): Promise<AssistantMemory[]> =>
  memoriesOfAssistant((await readAll()).filter((row: MemoryRecord): boolean => isMemoryActive(row, Date.now())), assistantId);

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
    const all: MemoryCandidate[] =
      await requireArrayForWrite<MemoryCandidate>(memoryPath(CANDIDATES_FILE));
    await writeJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE), [...all, candidate]);
  });

const addCandidates = (candidates: MemoryCandidate[]): Promise<void> => {
  if (candidates.length === 0) return Promise.resolve(); // insertAll(空)= no-op
  return enqueueWrite(async (): Promise<void> => {
    const all: MemoryCandidate[] =
      await requireArrayForWrite<MemoryCandidate>(memoryPath(CANDIDATES_FILE));
    await writeJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE), [...all, ...candidates]);
  });
};

// addEvent(:203-205)— message take(2_000) 在 toEntity(:316),此处截断对齐
const addEvent = (event: MemoryEvent): Promise<void> =>
  enqueueWrite(async (): Promise<void> => {
    const all: MemoryEvent[] =
      await requireArrayForWrite<MemoryEvent>(memoryPath(EVENTS_FILE));
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
    r.kind !== 'topic' && isMemoryActive(r, now));
};

// runMemoryExtraction 的存储面 deps(模型解析/generateText/locale 由 ChatPage 供给)
const applyExtractionActions = (actions: MemoryExtractionAction[], now: number, beforeCommit?: () => Promise<void>): Promise<MemoryExtractionApplyResult> =>
  enqueueWrite(async (): Promise<MemoryExtractionApplyResult> => {
    const all: MemoryRecord[] = await readAllForWrite();
    if (beforeCommit !== undefined) await beforeCommit();
    const out: MemoryExtractionApplyResult = applyMemoryExtractionActions(all, actions, Math.max(now, Date.now()));
    if (out.changed) await saveRecords(out.records);
    return out;
  });

export interface MemoryExtractionStoreOps {
  applyActions: (actions: MemoryExtractionAction[], now: number, beforeCommit?: () => Promise<void>) => Promise<MemoryExtractionApplyResult>;
  addCandidates: (candidates: MemoryCandidate[]) => Promise<void>;
  addCandidate: (candidate: MemoryCandidate) => Promise<void>;
  addMemory: (params: MemoryAddParams) => Promise<MemoryRecord>;
  addEvent: (event: MemoryEvent) => Promise<void>;
  countEventsSince: (type: MemoryEventType, createdAfter: number) => Promise<number>;
  getAllActiveRecords: (now: number) => Promise<MemoryRecord[]>;
}

export const getMemoryExtractionStoreOps = (): MemoryExtractionStoreOps => ({
  applyActions: applyExtractionActions,
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
    const all: MemoryRecord[] = await readAllForWrite();
    const out = upsertMemoryRecord(all, record, Date.now());
    await saveRecords(out.records);
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
// (显式排序:文件序不保证 createdAt 序,reverse() 假设不成立)
const candidateByCreatedDesc = (a: MemoryCandidate, b: MemoryCandidate): number =>
  b.createdAt - a.createdAt;

const getPendingCandidates = async (): Promise<MemoryCandidate[]> => {
  const all: MemoryCandidate[] = await readJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE));
  return all.filter((c: MemoryCandidate): boolean => c.status === 'pending')
    .sort(candidateByCreatedDesc);
};

// getAllCandidates(DAO :22-23)— ORDER created_at DESC
const getAllCandidates = async (): Promise<MemoryCandidate[]> => {
  const all: MemoryCandidate[] = await readJsonArray<MemoryCandidate>(memoryPath(CANDIDATES_FILE));
  return [...all].sort(candidateByCreatedDesc);
};

// updateCandidate(MemoryRepository.kt:174-176)— copy(updatedAt=now) 后 update;
//   Room @Update 未命中 = 0 行(no-op)
const updateCandidate = (candidate: MemoryCandidate): Promise<void> =>
  enqueueWrite(async (): Promise<void> => {
    const all: MemoryCandidate[] =
      await requireArrayForWrite<MemoryCandidate>(memoryPath(CANDIDATES_FILE));
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

// ===== D-089:记忆库面(MemoryPage 三桶列表 + CRUD + 事件) =====

interface MemoryChangeEventFields {
  type: MemoryEventType;
  memoryId: number;
  message: string;
  createdAt: number;
}

const restoreMemory = (id: number): Promise<void> => enqueueWrite(async (): Promise<void> => {
  const all: MemoryRecord[] = await readAllForWrite();
  const now: number = Date.now();
  const next: MemoryRecord[] = restoreMemoryRecord(all, id, now);
  await saveRecords(next);
  const events: MemoryEvent[] = await requireArrayForWrite<MemoryEvent>(memoryPath(EVENTS_FILE));
  const fields: MemoryChangeEventFields = {
    type: 'memory_restored', memoryId: id, message: '用户恢复记忆', createdAt: now,
  };
  await writeJsonArray<MemoryEvent>(memoryPath(EVENTS_FILE), [...events, makeMemoryEvent(fields)]);
});

export interface MemoryLibraryOps {
  getAllRecords: () => Promise<MemoryRecord[]>;
  restoreMemory: (id: number) => Promise<void>;
  // getMemoriesOfAssistant(DAO :16-17)— 桶精确匹配,插入序(含 archived,
  //   Android Flow 不过滤,UI 全量展示)
  listBucket: (bucket: string) => Promise<AssistantMemory[]>;
  // MemoryRepository.kt:102-133 addMemory / :84-89 updateContent / :145-147 deleteMemory
  addMemory: (params: MemoryAddParams) => Promise<MemoryRecord>;
  updateContent: (id: number, content: string) => Promise<MemoryRecord>;
  deleteMemory: (id: number) => Promise<void>;
  // MemoryEventDAO :15-16 — created_at DESC LIMIT :limit(VM getRecentEventsFlow(100))
  getRecentEvents: (limit: number) => Promise<MemoryEvent[]>;
  listBucketRecords: (bucket: string) => Promise<MemoryRecord[]>;
  updatePinned: (id: number, pinned: boolean) => Promise<MemoryRecord>;
}

export const getMemoryLibraryOps = (): MemoryLibraryOps => ({
  getAllRecords: readAllForWrite,
  restoreMemory,
  listBucket: listMemoriesOfAssistant,
  listBucketRecords: async (bucket: string): Promise<MemoryRecord[]> =>
    (await readAll()).filter((record: MemoryRecord): boolean => record.assistantId === bucket),
  addMemory,
  updateContent,
  deleteMemory,
  updatePinned,
  getRecentEvents,
});

const applyMemoryOptimization = (baseline: MemoryRecord[], suggestions: MemoryTopicSuggestion[],
  pass: MemoryProfilePassOutput | null, now: number, beforeCommit?: () => Promise<void>): Promise<MemoryOptimizationApplyResult> =>
  enqueueWrite(async (): Promise<MemoryOptimizationApplyResult> => {
    const live: MemoryRecord[] = await readAllForWrite();
    if (beforeCommit !== undefined) await beforeCommit();
    const commitNow: number = Math.max(now, Date.now());
    const scopes: MemoryScope[] = ['core', 'short_term', 'long_term'];
    const baselineSources: MemoryRecord[] = memoryProfileSources(baseline, scopes, commitNow);
    const liveSources: MemoryRecord[] = memoryProfileSources(live, scopes, commitNow);
    const profileSourcesUnchanged: boolean = baselineSources.length === liveSources.length && baselineSources.every((source: MemoryRecord): boolean =>
      liveSources.some((row: MemoryRecord): boolean => row.id === source.id && row.content === source.content
        && row.updatedAt === source.updatedAt && row.scope === source.scope && row.kind === source.kind));
    const merges: MemorySemanticMerge[] = pass === null ? [] : planConfirmedMemoryMerges(
      nearDuplicateCandidates(baseline, now), pass.duplicates, live, commitNow);
    let next: MemoryRecord[] = live;
    for (const merge of merges) {
      next = next.map((row: MemoryRecord): MemoryRecord => row.id === merge.winner.id ? merge.winner
        : row.id === merge.loser.id ? merge.loser : row);
      next = invalidateMemoryTopicsForSource(next, merge.loser.id, commitNow);
    }
    const topic: MemoryTopicApplyResult = applyMemoryTopicSuggestions(next, baseline, suggestions, commitNow);
    let profile: MemoryProfile | null = null;
    if (pass !== null && profileSourcesUnchanged) {
      profile = buildMemoryProfile(remapMemoryProfileItems(pass.profile, merges),
        memoryProfileSources(topic.records, scopes, commitNow), commitNow);
    }
    const out: MemoryOptimizationApplyResult = { records: topic.records, topicIds: topic.topicIds,
      changed: topic.changed || merges.length > 0, staleCount: topic.staleCount,
      rejectedReasons: topic.rejectedReasons, profileUpdated: profile !== null,
      mergedIds: merges.map((merge: MemorySemanticMerge): number => merge.loser.id) };
    if (out.changed) await saveRecords(out.records, out);
    if (profile !== null) {
      await writeMemoryProfile(profile);
      publishMemoryRevision();
    }
    await syncProjection(out.records, commitNow, out);
    if (merges.length > 0) {
      const events: MemoryEvent[] = await requireArrayForWrite<MemoryEvent>(memoryPath(EVENTS_FILE));
      const additions: MemoryEvent[] = merges.map((merge: MemorySemanticMerge): MemoryEvent => {
        const fields: MemoryChangeEventFields = {
        type: 'memory_archived', memoryId: merge.loser.id, createdAt: commitNow,
        message: `模型确认完全同义，合并至 #${merge.winner.id}；原文保留，可恢复。`,
      };
        return makeMemoryEvent(fields);
      });
      await writeJsonArray<MemoryEvent>(memoryPath(EVENTS_FILE), events.concat(additions));
    }
    return out;
  });

export interface MemoryTopicStoreOps {
  snapshotProfile: () => Promise<MemoryProfile | null>;
  applyOptimization: (baseline: MemoryRecord[], suggestions: MemoryTopicSuggestion[], pass: MemoryProfilePassOutput | null,
    now: number, beforeCommit?: () => Promise<void>) => Promise<MemoryOptimizationApplyResult>;
  snapshotForTopics: () => Promise<MemoryRecord[]>;
  applyTopics: (baseline: MemoryRecord[], suggestions: MemoryTopicSuggestion[], now: number,
    beforeCommit?: () => Promise<void>) => Promise<MemoryTopicApplyResult>;
  deleteTopic: (id: number) => Promise<void>;
  syncDocuments: () => Promise<void>;
  listDocuments: () => Promise<MemoryDerivedDocument[]>;
  readDocument: (relativePath: string) => Promise<string | null>;
}

const refreshDocuments = async (): Promise<void> => {
  const records: MemoryRecord[] = await readAllForWrite();
  const pruned = pruneMemoryTopics(records, Date.now());
  if (pruned.changed) await saveRecords(pruned.records);
  else await syncProjection(records, Date.now());
};

export const getMemoryTopicStoreOps = (): MemoryTopicStoreOps => {
  const ops: MemoryTopicStoreOps = {
  snapshotProfile: (): Promise<MemoryProfile | null> => enqueueWrite(async (): Promise<MemoryProfile | null> =>
    refreshMemoryProfile(await readAllForWrite(), Date.now())),
  applyOptimization: applyMemoryOptimization,
  snapshotForTopics: (): Promise<MemoryRecord[]> => enqueueWrite(async (): Promise<MemoryRecord[]> => {
    const records: MemoryRecord[] = await readAllForWrite();
    return pruneMemoryTopics(records, Date.now()).records;
  }),
  applyTopics: (baseline: MemoryRecord[], suggestions: MemoryTopicSuggestion[], now: number,
    beforeCommit?: () => Promise<void>): Promise<MemoryTopicApplyResult> =>
    enqueueWrite(async (): Promise<MemoryTopicApplyResult> => {
      const live: MemoryRecord[] = await readAllForWrite();
      if (beforeCommit !== undefined) await beforeCommit();
      const commitNow: number = Math.max(now, Date.now());
      const result: MemoryTopicApplyResult = applyMemoryTopicSuggestions(live, baseline, suggestions, commitNow);
      if (result.changed) await saveRecords(result.records, result);
      else await syncProjection(result.records, commitNow, result);
      return result;
    }),
  deleteTopic: (id: number): Promise<void> => enqueueWrite(async (): Promise<void> => {
    const records: MemoryRecord[] = await readAllForWrite();
    const record: MemoryRecord | undefined = records.find((item: MemoryRecord): boolean => item.id === id);
    if (record === undefined) return;
    if (record.kind !== 'topic') throw new Error('该记忆不是主题');
    await saveRecords(records.filter((item: MemoryRecord): boolean => item.id !== id));
  }),
  syncDocuments: (): Promise<void> => enqueueWrite(async (): Promise<void> => {
    await refreshDocuments();
    publishMemoryRevision();
  }),
  listDocuments: (): Promise<MemoryDerivedDocument[]> => enqueueWrite(async (): Promise<MemoryDerivedDocument[]> => {
    await refreshDocuments();
    return listMemoryDocuments();
  }),
  readDocument: (relativePath: string): Promise<string | null> => enqueueWrite(async (): Promise<string | null> => {
    await refreshDocuments();
    return readMemoryDocument(relativePath);
  }),
  };
  return ops;
};

// ===== D-085g:dream plan JSON 持久化 + Store/Notifier =====

const DREAM_PLANS_FILE: string = 'memory_dream_plans.json';

// MemoryDreamPlanDAO 查询语义的 JSON 数组等价:
//   getPendingPlan = status='pending' ORDER created_at DESC LIMIT 1
//   countPlansSince = source 匹配 && created_at >= after
//   updatePendingStatus = 全量 pending 行
//   insert = REPLACE by id
const dreamPlanDao: MemoryDreamPlanDaoPort = {
  getPendingPlan: async (): Promise<MemoryDreamPlanEntity | null> => {
    const all: MemoryDreamPlanEntity[] =
      await readJsonArray<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE));
    let best: MemoryDreamPlanEntity | null = null;
    for (const row of all) {
      if (row.status === 'pending' && (best === null || row.createdAt > best.createdAt)) {
        best = row;
      }
    }
    return best;
  },
  countPlansSince: async (source: string, createdAfter: number): Promise<number> => {
    const all: MemoryDreamPlanEntity[] =
      await readJsonArray<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE));
    return all.filter((row: MemoryDreamPlanEntity): boolean =>
      row.source === source && row.createdAt >= createdAfter).length;
  },
  // 原子「dismiss 全量 pending + insert」:单次 enqueueWrite,防并发交错留多个 pending
  replacePendingAndInsert: (status: string, dismissedAt: number,
    entity: MemoryDreamPlanEntity): Promise<void> =>
    enqueueWrite(async (): Promise<void> => {
      const all: MemoryDreamPlanEntity[] =
        await requireArrayForWrite<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE));
      const next: MemoryDreamPlanEntity[] = all.map(
        (row: MemoryDreamPlanEntity): MemoryDreamPlanEntity => {
          if (row.status !== 'pending') return row;
          return {
            id: row.id, planJson: row.planJson, status, source: row.source,
            mergeCount: row.mergeCount, promoteCount: row.promoteCount,
            archiveCount: row.archiveCount, supersedeCount: row.supersedeCount,
            ignoreCandidateCount: row.ignoreCandidateCount, createdAt: row.createdAt,
            appliedAt: row.appliedAt, dismissedAt,
          };
        });
      const replaced: number = next.findIndex(
        (row: MemoryDreamPlanEntity): boolean => row.id === entity.id);
      if (replaced >= 0) next[replaced] = entity;
      else next.push(entity);
      await writeJsonArray<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE), next);
    }),
  updatePendingStatus: (status: string, dismissedAt: number): Promise<void> =>
    enqueueWrite(async (): Promise<void> => {
      const all: MemoryDreamPlanEntity[] =
        await requireArrayForWrite<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE));
      const next: MemoryDreamPlanEntity[] = all.map(
        (row: MemoryDreamPlanEntity): MemoryDreamPlanEntity => {
          if (row.status !== 'pending') return row;
          return {
            id: row.id, planJson: row.planJson, status, source: row.source,
            mergeCount: row.mergeCount, promoteCount: row.promoteCount,
            archiveCount: row.archiveCount, supersedeCount: row.supersedeCount,
            ignoreCandidateCount: row.ignoreCandidateCount, createdAt: row.createdAt,
            appliedAt: row.appliedAt, dismissedAt,
          };
        });
      await writeJsonArray<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE), next);
    }),
  markApplied: (id: string, appliedAt: number): Promise<void> =>
    enqueueWrite(async (): Promise<void> => {
      const all: MemoryDreamPlanEntity[] =
        await requireArrayForWrite<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE));
      const index: number = all.findIndex(
        (row: MemoryDreamPlanEntity): boolean => row.id === id);
      if (index < 0) return;
      const row: MemoryDreamPlanEntity = all[index];
      const updated: MemoryDreamPlanEntity = {
        id: row.id, planJson: row.planJson, status: 'applied', source: row.source,
        mergeCount: row.mergeCount, promoteCount: row.promoteCount,
        archiveCount: row.archiveCount, supersedeCount: row.supersedeCount,
        ignoreCandidateCount: row.ignoreCandidateCount, createdAt: row.createdAt,
        appliedAt, dismissedAt: row.dismissedAt,
      };
      const next: MemoryDreamPlanEntity[] = [...all];
      next[index] = updated;
      await writeJsonArray<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE), next);
    }),
  markDismissed: (id: string, dismissedAt: number): Promise<void> =>
    enqueueWrite(async (): Promise<void> => {
      const all: MemoryDreamPlanEntity[] =
        await requireArrayForWrite<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE));
      const index: number = all.findIndex(
        (row: MemoryDreamPlanEntity): boolean => row.id === id);
      if (index < 0) return;
      const row: MemoryDreamPlanEntity = all[index];
      const updated: MemoryDreamPlanEntity = {
        id: row.id, planJson: row.planJson, status: 'dismissed', source: row.source,
        mergeCount: row.mergeCount, promoteCount: row.promoteCount,
        archiveCount: row.archiveCount, supersedeCount: row.supersedeCount,
        ignoreCandidateCount: row.ignoreCandidateCount, createdAt: row.createdAt,
        appliedAt: row.appliedAt, dismissedAt,
      };
      const next: MemoryDreamPlanEntity[] = [...all];
      next[index] = updated;
      await writeJsonArray<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE), next);
    }),
  insert: (entity: MemoryDreamPlanEntity): Promise<void> =>
    enqueueWrite(async (): Promise<void> => {
      const all: MemoryDreamPlanEntity[] =
        await requireArrayForWrite<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE));
      const index: number = all.findIndex(
        (row: MemoryDreamPlanEntity): boolean => row.id === entity.id);
      const next: MemoryDreamPlanEntity[] = [...all];
      if (index >= 0) next[index] = entity;
      else next.push(entity);
      await writeJsonArray<MemoryDreamPlanEntity>(memoryPath(DREAM_PLANS_FILE), next);
    }),
};

// runMemoryDreamRun / 设置面手动触发的存储面 deps
export const getMemoryDreamPlanStore = (): MemoryDreamPlanStore =>
  createMemoryDreamPlanStore(dreamPlanDao);

// MemoryDreamReviewNotifier 真实适配:notificationManager 发布(与 deepread
//   NotificationNotifier 同策略;文案逐字,同 DREAM_NOTIFICATION_ID 覆盖更新)。
class MemoryDreamNotifierImpl implements MemoryDreamReviewNotifier {
  notifyRunning(): void {
    publishBasicNotification(DREAM_NOTIFICATION_ID, DREAM_NOTIFY_RUNNING_TITLE, DREAM_NOTIFY_RUNNING_TEXT);
  }

  notifyPendingReview(plan: MemoryDreamPlan): void {
    publishBasicNotification(DREAM_NOTIFICATION_ID, DREAM_NOTIFY_PENDING_TITLE, memoryDreamPlanSummaryText(plan));
  }

  notifyFailed(message: string): void {
    // contentText=take(120)(MemoryDreamNotifier.kt:64-65)
    publishBasicNotification(DREAM_NOTIFICATION_ID, DREAM_NOTIFY_FAILED_TITLE, message.slice(0, 120));
  }

  cancel(): void {
    cancelNotification(DREAM_NOTIFICATION_ID);
  }
}

export const createMemoryDreamNotifier = (): MemoryDreamReviewNotifier =>
  new MemoryDreamNotifierImpl();

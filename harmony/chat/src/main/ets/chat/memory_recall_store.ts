// memory_recall_store — MemoryRecallStore.kt 编排层移植
// Android 锚点:core/memory/recall/MemoryRecallStore.kt:14-53(buildPrompt/recall/
//   recallSelections)。纯函数(rankRecords/score/tokenize)在 memory_recall.ts(D-085a)。
// 仓库抽象:Android MemoryRepository(Room) → 本接口由 entry JSON 文件存储实现
//   (MemoryStore.ets;DAO 无 ORDER BY → 插入序,id 自增 = max+1)。

import type { UIMessage } from './message.ts';
import type { MemoryRecallSetting, MemoryRecord, MemoryScope } from './memory_models.ts';
import { isMemoryActive } from './memory_lifecycle.ts';
import { buildMemoryContext } from './memory_prompt_builder.ts';
import type { MemoryRecallSelection } from './memory_recall.ts';
import { memoryRecallScoreToDebugText, rankMemoryRecords } from './memory_recall.ts';

// Settings.agentRuntime 记忆门面(PreferencesStore.kt:152-154,174)
export interface MemoryRecallRuntimeGate {
  enableCoreMemory: boolean;
  enableShortTermMemory: boolean;
  enableLongTermMemory: boolean;
  memoryRecall: MemoryRecallSetting;
}

// MemoryRepository 读路径子集(getActiveRecords:69-72 + touchMemories)
export interface MemoryReadRepository {
  getActiveRecords: (scopes: MemoryScope[], now: number) => Promise<MemoryRecord[]>;
  touchMemories: (ids: number[]) => Promise<void>;
  reinforceMemories?: (ids: number[], sourceId: string) => Promise<void>;
}

// recallSelections(:38-53):scopes 门(三开关,空 → [])→ getActiveRecords → rankRecords
export const recallMemorySelections = async (
  gate: MemoryRecallRuntimeGate,
  messages: UIMessage[],
  repository: MemoryReadRepository,
  now: number = Date.now(),
): Promise<MemoryRecallSelection[]> => {
  const scopes: MemoryScope[] = [];
  if (gate.enableCoreMemory) scopes.push('core');
  if (gate.enableShortTermMemory) scopes.push('short_term');
  if (gate.enableLongTermMemory) scopes.push('long_term');
  if (scopes.length === 0) return [];
  const records: MemoryRecord[] = await repository.getActiveRecords(scopes, now);
  return rankMemoryRecords(gate.memoryRecall, messages, filterActiveMemoryRecords(records, scopes, now), now);
};

// recall(:34-36)
export const recallMemoryRecords = async (
  gate: MemoryRecallRuntimeGate,
  messages: UIMessage[],
  repository: MemoryReadRepository,
  now: number = Date.now(),
): Promise<MemoryRecord[]> => {
  const selections: MemoryRecallSelection[] =
    await recallMemorySelections(gate, messages, repository, now);
  return selections.map((s: MemoryRecallSelection): MemoryRecord => s.record);
};

// buildPrompt(:17-32):selections → touchMemories(ids) → buildMemoryContext
//   (debug 时 debugDetails = id → score.toDebugText())
export const buildMemoryRecallPrompt = async (
  gate: MemoryRecallRuntimeGate,
  messages: UIMessage[],
  repository: MemoryReadRepository,
  now: number = Date.now(),
  // D5-1:Jev 记忆召回后置钩子(entry 注入;对本地排序结果二次筛选,失败回退原序)
  afterRank?: (selections: MemoryRecallSelection[]) => Promise<MemoryRecallSelection[]>,
): Promise<string> => {
  let selections: MemoryRecallSelection[] =
    await recallMemorySelections(gate, messages, repository, now);
  if (afterRank !== undefined) {
    try {
      selections = await afterRank(selections);
    } catch (_e) {
      // 钩子失败回退本地排序
    }
  }
  selections = selections.filter((selection: MemoryRecallSelection): boolean => isMemoryActive(selection.record, now));
  const records: MemoryRecord[] = selections.map(
    (s: MemoryRecallSelection): MemoryRecord => s.record);
  await repository.touchMemories(records.map((r: MemoryRecord): number => r.id));
  const debug: boolean = gate.memoryRecall.debug;
  const debugDetails: Record<number, string> = {};
  if (debug) {
    for (const s of selections) {
      debugDetails[s.record.id] = memoryRecallScoreToDebugText(s.score);
    }
  }
  return buildMemoryContext(records, debug, debugDetails, now);
};

// ===== DAO 查询语义纯函数(entry JSON 存储复用) =====

// MemoryDAO.kt:22-23 — scope IN (:scopes) AND archived = 0 AND
//   (expires_at IS NULL OR expires_at > :now);无 ORDER BY → 插入序原样保留
export const filterActiveMemoryRecords = (
  records: MemoryRecord[], scopes: MemoryScope[], now: number,
): MemoryRecord[] => records.filter((r: MemoryRecord): boolean =>
  scopes.indexOf(r.scope) >= 0 && isMemoryActive(r, now));

// MemoryDAO.kt:43-44 + MemoryRepository.kt:149-153 — UPDATE last_used_at WHERE id IN (:ids);
//   空 ids no-op 由调用方(repository 等价物)守卫;返回新数组,未命中记录原样
export const touchMemoryRecords = (
  records: MemoryRecord[], ids: number[], usedAt: number,
): MemoryRecord[] => records.map((r: MemoryRecord): MemoryRecord =>
  ids.indexOf(r.id) >= 0 ? { ...r, lastUsedAt: usedAt } : r);

// Actual citation / user confirmation only. Retrieval itself never increments reinforcement.
// sourceKey is the generating turn id so repeated completion callbacks are idempotent.
export const reinforceMemoryRecords = (
  records: MemoryRecord[], ids: number[], reinforcedAt: number, sourceKey: string | null = null,
): MemoryRecord[] => records.map((record: MemoryRecord): MemoryRecord => {
  if (ids.indexOf(record.id) < 0 || !isMemoryActive(record, reinforcedAt)) return record;
  if (sourceKey !== null && record.lastReinforcementSource === sourceKey) return record;
  if (sourceKey === null && record.lastReinforcedAt === reinforcedAt) return record;
  return { ...record, reinforcementCount: (record.reinforcementCount ?? 0) + 1,
    lastReinforcedAt: reinforcedAt, lastReinforcementSource: sourceKey };
});

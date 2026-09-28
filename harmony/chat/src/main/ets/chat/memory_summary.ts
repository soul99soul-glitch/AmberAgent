// memory_summary — 记忆汇总卡三组过滤器(D-093)
// Android 锚点:SettingAgentMemoryPage.kt:793-855 MemorySummarySection
//   stableMemories = (core + longTerm).filter(!archived && !sensitive &&
//     (scope==CORE || kind∈{USER,FEEDBACK,ROUTINE} || pinned)).distinctBy(id)
//   longTermProjects = longTerm.filter(!archived && !sensitive &&
//     kind∈{PROJECT,REFERENCE})
//   currentProjects = shortTerm.filter(!archived && !sensitive && kind==PROJECT)
//   isSummarySensitive = isSensitiveMemoryContent(:907-909)
// 展示层(take(6)/'#{id} [scope/kind] content' maxLines 2)在 entry 页。

import { isSensitiveMemoryContent } from './memory_prompt_builder.ts';
import type { AssistantMemory } from './builtin_memory_tools.ts';

export interface MemorySummaryGroups {
  stable: AssistantMemory[];
  longTermProjects: AssistantMemory[];
  currentProjects: AssistantMemory[];
}

const summaryVisible = (m: AssistantMemory): boolean =>
  m.kind !== 'topic' && !m.archived && !isSensitiveMemoryContent(m.content);

const isStableKind = (m: AssistantMemory): boolean =>
  m.scope === 'core'
  || m.kind === 'user' || m.kind === 'feedback' || m.kind === 'routine'
  || m.pinned;

// :800-811 — core+longTerm 串联后过滤;distinctBy(id)= 首次出现保序
export const memorySummaryGroups = (
  coreMemories: AssistantMemory[],
  longTermMemories: AssistantMemory[],
  shortTermMemories: AssistantMemory[],
): MemorySummaryGroups => {
  const stable: AssistantMemory[] = [];
  const seen: Set<number> = new Set<number>();
  for (const m of [...coreMemories, ...longTermMemories]) {
    if (!summaryVisible(m) || !isStableKind(m)) continue;
    if (seen.has(m.id)) continue;
    seen.add(m.id);
    stable.push(m);
  }
  return {
    stable,
    longTermProjects: longTermMemories.filter(
      (m: AssistantMemory): boolean =>
        summaryVisible(m) && (m.kind === 'project' || m.kind === 'reference')),
    currentProjects: shortTermMemories.filter(
      (m: AssistantMemory): boolean => summaryVisible(m) && m.kind === 'project'),
  };
};

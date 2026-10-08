import { isComplete } from '../domain/helpers.ts';
import { synthesisTemplate } from '../domain/synthesis_templates.ts';
// deepReadProgressSnapshot — 进度百分比快照(照搬 Android DeepReadProgress.kt)
//
// 供 UI 胶囊与通知共用:准备 6% → 收集 10% → 规划 24% → 写作按段递增
// (34/56/78,段 RUNNING 取段中值)→ 收尾 94% → 补漏 96% → 完成 100%。
// isExpired:24h TTL 派生(DEEP_READ_TTL_MS),历史页徽章/过期提示共用。

import type { DeepReadCacheEntry } from './repository.ts';
import type { DeepReadOutput } from '../domain/models.ts';
import type { DeepReadGenerationStage } from '../domain/enums.ts';
import { STAGE_ORDER, DEEP_READ_TTL_MS } from '../domain/enums.ts';

export interface DeepReadProgressSnapshot {
  percent: number;
  label: string;
}

// 写作段基点:OVERVIEW 34 / NARRATIVE 56 / ANALYSIS 78,EXTENDED_READING 收尾前 88
const STAGE_BASE_PERCENT: Record<DeepReadGenerationStage, number> = {
  OVERVIEW: 34,
  NARRATIVE: 56,
  ANALYSIS: 78,
  EXTENDED_READING: 88,
};

const writingProgress = (output: DeepReadOutput): DeepReadProgressSnapshot => {
  for (let i = 0; i < STAGE_ORDER.length; i++) {
    const stage: DeepReadGenerationStage = STAGE_ORDER[i];
    if (output.sectionStates[stage]?.status === 'RUNNING') {
      return { percent: STAGE_BASE_PERCENT[stage] + 10, label: `正在${stageLabel(stage)}` };
    }
  }
  // 全部段非 RUNNING(段间空隙)→ 取最后完成段基点+10
  return { percent: 34, label: '正在撰写' };
};

const stageLabel = (stage: DeepReadGenerationStage): string => {
  if (stage === 'OVERVIEW') return '撰写结论与导语';
  if (stage === 'NARRATIVE') return '梳理关键判断与脉络';
  if (stage === 'ANALYSIS') return '分析立场与影响';
  return '整理来源';
};

const sectionsReady = (output: DeepReadOutput): boolean =>
  STAGE_ORDER.every((s: DeepReadGenerationStage): boolean =>
    output.sectionStates[s]?.status === 'READY');

export const deepReadProgressSnapshot = (
  output: DeepReadOutput | null, running: boolean,
): DeepReadProgressSnapshot => {
  if (output === null) return { percent: running ? 6 : 0, label: running ? '正在准备' : '未开始' };
  if (isComplete(output) || output.generationPhase === 'COMPLETE' || (output.generationComplete && sectionsReady(output))) {
    return { percent: 100, label: '已完成' };
  }
  if (output.generationPhase === 'VERIFYING') return { percent: 96, label: '正在补漏' };
  if (sectionsReady(output)) return { percent: 94, label: '正在收尾' };
  if (output.generationPhase === 'COLLECTING') return { percent: 10, label: '正在收集资料' };
  if (output.generationPhase === 'PLANNING') return { percent: 24, label: '正在规划结构' };
  if (output.generationPhase === 'WRITING') {
    const template = synthesisTemplate(output.templateArticle?.template ?? output.templateSnapshot?.id ?? output.templateId);
    if (template !== null) return { percent: 56, label: `正在生成${template.name}` };
    return writingProgress(output);
  }
  // IDLE
  if (!running) return { percent: 0, label: '未开始' };
  if (STAGE_ORDER.some((s: DeepReadGenerationStage): boolean =>
    output.sectionStates[s] !== undefined)) {
    return writingProgress(output);
  }
  return { percent: 6, label: '正在准备' };
};

// 缓存条目是否已过 24h TTL(expired 派生;entry.expiresAt 为权威,缺省用 createdAt 推)
export const isCacheEntryExpired = (
  entry: DeepReadCacheEntry | null, nowMs: number = Date.now(),
): boolean => {
  if (entry === null) return false;
  const expiresAt: number = entry.expiresAt > 0
    ? entry.expiresAt : entry.createdAt + DEEP_READ_TTL_MS;
  return nowMs >= expiresAt;
};

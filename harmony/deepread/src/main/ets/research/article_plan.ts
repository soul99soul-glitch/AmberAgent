// Article Plan — 1 次非流式 LLM 调用 + 本地 fallback
// 照搬 Android DeepReadResearchHarness.kt(parsePlan/normalizePlan/fallbackPlan/buildPlanningPrompt)
//
// 关键 Android 保真度:
// - normalizePlan 过滤掉不在 pack 已知 sourceIds 中的虚构 id(LLM 可能 hallucinate)
// - stringList take(20), stageSourceIds take(STAGE_EVIDENCE_MAX)
// - fallbackPlan 用 pack.stageCards 各 stage 的 sourceId 填 stageSourceIds
// - generateArticlePlan:LLM 失败/解析失败 → fallback

import type { AiClient } from '../platform/ai_client.ts';
import type { AbortSignalLike } from '../platform/runtime_api.ts';
import type { UIMessage } from '../agent/message.ts';
import { makeUserMessage, latestAssistantText } from '../agent/message.ts';
import type { DeepReadEvidencePack, DeepReadArticlePlan } from './evidence_pack.ts';
import { STAGE_ORDER, STAGE_EVIDENCE_MAX } from '../domain/enums.ts';
import type { DeepReadGenerationStage } from '../domain/enums.ts';

const STAGE_KEY: Record<DeepReadGenerationStage, string> = {
  OVERVIEW: 'overview',
  NARRATIVE: 'narrative',
  ANALYSIS: 'analysis',
  EXTENDED_READING: 'extended_reading',
};
const STRING_LIST_MAX = 20;
const NARRATIVE_SLOTS_MAX = 6;
const ANALYSIS_QUESTIONS_MAX = 8;
const STAKEHOLDERS_MAX = 8;
const COVERAGE_CHECKS_MAX = 10;

// fallbackPlan — 照搬 Android DeepReadResearchHarness.kt:62-91
// 各 stage 的 sourceIds 优先取 pack.cardsByStage[stage];该段 bucket 为空时
// 从全局卡池(四段并集)补满,避免降级计划给后段分配零证据(R24)。
export const fallbackPlan = (title: string, pack: DeepReadEvidencePack): DeepReadArticlePlan => {
  // 全局池:按 STAGE_ORDER 顺序汇总四段 bucket 的 sourceId(去重,保持 credibility 排序后的相对顺序)
  const globalIds: string[] = [];
  for (const stage of STAGE_ORDER) {
    for (const c of pack.cardsByStage[stage]) {
      if (!globalIds.includes(c.sourceId)) globalIds.push(c.sourceId);
    }
  }
  const stageSourceIds: Record<string, string[]> = {};
  for (const stage of STAGE_ORDER) {
    const filled: string[] = pack.cardsByStage[stage].map(c => c.sourceId);
    // bucket 不足 STAGE_EVIDENCE_MAX 时从全局池补(与 normalizePlan 的 allIds 补满同源)
    for (const id of globalIds) {
      if (filled.length >= STAGE_EVIDENCE_MAX) break;
      if (!filled.includes(id)) filled.push(id);
    }
    stageSourceIds[STAGE_KEY[stage]] = filled;
  }
  const allStageIds = STAGE_ORDER.flatMap(stage => stageSourceIds[STAGE_KEY[stage]]);
  const distinctIds = Array.from(new Set(allStageIds));
  const target = Math.min(requiredTargetFor(pack), distinctIds.length);
  const requiredSourceIds = distinctIds.slice(0, target);

  return {
    overviewAngle: `从已核查来源解释「${title}」发生了什么、为什么值得读,以及哪些结论仍需保守表达。`,
    narrativeSlots: [
      '背景和直接触发因素',
      '关键进展或时间线',
      '当前状态与后续观察点',
    ],
    analysisQuestions: [
      '核心矛盾是什么,各方到底在争什么?',
      '这件事会影响哪些用户、公司、行业或公共议题?',
      '有哪些反方证据、不确定点或互相矛盾的说法需要降格表达?',
    ],
    stakeholders: [],
    riskOrUncertainty: [
      '来源之间未互相印证的事实不得写成定论。',
      '没有来源支撑的价格、时间、人物表态、因果关系需要跳过或标注为不确定。',
    ],
    requiredSourceIds,
    stageSourceIds,
    coverageChecks: [],
  };
};

const requiredTargetFor = (pack: DeepReadEvidencePack): number => {
  let count = 0;
  for (const stage of STAGE_ORDER) count += pack.cardsByStage[stage].length;
  if (count >= 12) return 10;
  if (count >= 8) return 8;
  return count;
};

// buildPlanningPrompt — 照搬 Android DeepReadResearchHarness.kt:93-130
export const buildPlanningPrompt = (title: string, pack: DeepReadEvidencePack, playbookMd: string): string => {
  const sourceLines = pack.allSources.slice(0, 12).map((s, i) =>
    `${i + 1}. [${s.sourceId}] ${s.title} (${s.url}) — credibility:${s.credibility}\n   ${s.evidenceText.slice(0, 300)}`,
  ).join('\n');
  return [
    `话题标题:${title}`,
    playbookMd.length > 0 ? `## 本地规则\n${playbookMd.slice(0, 12000)}` : '',
    `## 已抓取来源(${pack.allSources.length} 个)`,
    sourceLines,
    '',
    '## 任务',
    '基于上述来源,为这篇文章制定写作计划。返回 JSON,字段:',
    '- overview_angle: 一句话定调',
    '- narrative_slots: 叙事应覆盖的要点(数组)',
    '- analysis_questions: 分析段应回答的问题(数组)',
    '- stakeholders: 利益相关方(数组)',
    '- risk_or_uncertainty: 不确定/风险点(数组)',
    '- required_source_ids: 必须引用的 sourceId(数组)',
    '- stage_source_ids: 各 stage 应引用的 sourceId,格式 {"overview": [...], "narrative": [...], "analysis": [...], "extended_reading": [...]}',
    '- coverage_checks: 质量检查清单(数组)',
    '',
    '只返回 JSON,不要其他文字。不要创造不存在的 source_id。',
  ].filter(line => line !== '').join('\n');
};

// 容忍解析:整文本 → 代码块 → 大括号平衡
// 照搬 Android jsonCandidates(简化版:取第一个可解析的候选)
export const parsePlanJson = (text: string): Record<string, unknown> | null => {
  const candidates: string[] = [];
  const trimmed = text.trim();
  if (trimmed.length > 0) candidates.push(trimmed);

  // 代码块 ```json ... ```
  const fenceRe = /```(?:json)?\s*([\s\S]*?)```/gi;
  let fm: RegExpExecArray | null;
  while ((fm = fenceRe.exec(text)) !== null) {
    if (fm[1] && fm[1].trim().length > 0) candidates.push(fm[1].trim());
  }

  // 大括号平衡(字符串/转义感知)— 照搬 Android extractBalancedObjects
  const balanced = extractBalancedObjects(text);
  for (const b of balanced) candidates.push(b);

  for (const candidate of candidates) {
    const parsed = tryParseJson(candidate);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
  }
  return null;
};

// 照搬 Android extractBalancedObjects(字符串/转义感知的大括号匹配)
const extractBalancedObjects = (text: string): string[] => {
  const objects: string[] = [];
  let start = -1;
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (escaped) { escaped = false; continue; }
    if (char === '\\' && inString) { escaped = true; continue; }
    if (char === '"') { inString = !inString; continue; }
    if (inString) continue;
    if (char === '{') {
      if (depth === 0) start = i;
      depth++;
    } else if (char === '}') {
      depth--;
      if (depth === 0 && start >= 0) {
        objects.push(text.slice(start, i + 1));
        start = -1;
      }
    }
  }
  return objects;
};

const tryParseJson = (s: string): unknown | null => {
  try { return JSON.parse(s); } catch { return null; }
};

// 类型守卫辅助
const isString = (v: unknown): v is string => typeof v === 'string';
const toStringArray = (v: unknown): string[] => {
  if (!Array.isArray(v)) return [];
  return v
    .filter((x): x is string => typeof x === 'string')
    .map(s => s.trim())
    .filter(s => s.length > 0)
    .slice(0, STRING_LIST_MAX);
};

// normalizePlan — 照搬 Android DeepReadResearchHarness.kt:141-167
// 关键:过滤掉不在 pack 已知 sourceIds 中的虚构 id
export const normalizePlan = (
  parsed: Record<string, unknown>,
  title: string,
  pack: DeepReadEvidencePack,
): DeepReadArticlePlan => {
  const fallback = fallbackPlan(title, pack);
  const allIds = new Set<string>();
  for (const stage of STAGE_ORDER) {
    for (const c of pack.cardsByStage[stage]) allIds.add(c.sourceId);
  }

  // stage_source_ids:过滤 + distinct + take(STAGE_EVIDENCE_MAX) + fallback 合并
  const stageSourceIdsRaw = (parsed.stage_source_ids ?? parsed.stageSourceIds ?? {}) as Record<string, unknown>;
  const normalizedStageSources: Record<string, string[]> = {};
  for (const stage of STAGE_ORDER) {
    const stageKey = STAGE_KEY[stage];
    const fallbackIds = fallback.stageSourceIds[stageKey] ?? [];
    const parsedIds = toStringArray(stageSourceIdsRaw[stageKey] ?? stageSourceIdsRaw[stage] ?? stageSourceIdsRaw[stage.toLowerCase()] ?? stageSourceIdsRaw[stage.toUpperCase()] ?? [])
      .filter(id => allIds.has(id))
      .slice(0, STAGE_EVIDENCE_MAX);
    normalizedStageSources[stageKey] = mergeWithFallbackIds(parsedIds, fallbackIds, allIds);
  }

  // requiredSourceIds:union(parsed + all stage ids + fallback), filter allIds, take target
  const unionIds = STAGE_ORDER.flatMap(s => normalizedStageSources[STAGE_KEY[s]]);
  const parsedRequired = toStringArray(parsed.required_source_ids ?? parsed.requiredSourceIds);
  const requiredSourceIds = Array.from(new Set([...parsedRequired, ...unionIds, ...fallback.requiredSourceIds]))
    .filter(id => allIds.has(id))
    .slice(0, requiredTargetFor(pack));

  const overviewAngle = isString(parsed.overview_angle)
    ? parsed.overview_angle
    : (isString(parsed.overviewAngle) ? parsed.overviewAngle : fallback.overviewAngle);
  const overviewTrim = overviewAngle.trim();

  return {
    overviewAngle: overviewTrim.length > 0 ? overviewTrim : fallback.overviewAngle,
    narrativeSlots: (toStringArray(parsed.narrative_slots ?? parsed.narrativeSlots).length > 0
      ? toStringArray(parsed.narrative_slots ?? parsed.narrativeSlots) : fallback.narrativeSlots).slice(0, NARRATIVE_SLOTS_MAX),
    analysisQuestions: (toStringArray(parsed.analysis_questions ?? parsed.analysisQuestions).length > 0
      ? toStringArray(parsed.analysis_questions ?? parsed.analysisQuestions) : fallback.analysisQuestions).slice(0, ANALYSIS_QUESTIONS_MAX),
    stakeholders: (toStringArray(parsed.stakeholders).length > 0
      ? toStringArray(parsed.stakeholders) : fallback.stakeholders).slice(0, STAKEHOLDERS_MAX),
    riskOrUncertainty: (toStringArray(parsed.risk_or_uncertainty ?? parsed.riskOrUncertainty).length > 0
      ? toStringArray(parsed.risk_or_uncertainty ?? parsed.riskOrUncertainty) : fallback.riskOrUncertainty).slice(0, STAKEHOLDERS_MAX),
    requiredSourceIds: requiredSourceIds.length > 0 ? requiredSourceIds : fallback.requiredSourceIds,
    stageSourceIds: normalizedStageSources,
    coverageChecks: (toStringArray(parsed.coverage_checks ?? parsed.coverageChecks).length > 0
      ? toStringArray(parsed.coverage_checks ?? parsed.coverageChecks) : fallback.coverageChecks).slice(0, COVERAGE_CHECKS_MAX),
  };
};

// 照搬 Android withFallbackIds(:332-341)
// 合并 parsed + fallback,filter allIds,distinct,补足 STAGE_EVIDENCE_MIN,cap STAGE_EVIDENCE_MAX
const mergeWithFallbackIds = (
  parsedIds: string[],
  fallbackIds: string[],
  allIds: Set<string>,
): string[] => {
  const merged = Array.from(new Set([...parsedIds, ...fallbackIds]))
    .filter(id => allIds.has(id));
  const minCount = Math.min(4, allIds.size);  // STAGE_EVIDENCE_MIN
  if (merged.length >= minCount) {
    return merged.slice(0, STAGE_EVIDENCE_MAX);
  }
  // 不够时从 allIds 补(保持顺序)
  const allArr = Array.from(allIds);
  const filled = Array.from(new Set([...merged, ...allArr]));
  return filled.slice(0, STAGE_EVIDENCE_MAX);
};

// 主入口:1 次 LLM 调用,失败 fallback。signal 贯穿 → 取消时真实中止 LLM 调用
// (R20;abort 由调用方 createRunContext 依据 signal 状态统一归一,不在此宽判 message)。
export const generateArticlePlan = async (
  ai: AiClient,
  model: string,
  title: string,
  pack: DeepReadEvidencePack,
  playbookMd: string,
  signal?: AbortSignalLike,
): Promise<DeepReadArticlePlan> => {
  const fallback = fallbackPlan(title, pack);
  try {
    const messages = await ai.generateText({
      model,
      messages: [makeUserMessage(buildPlanningPrompt(title, pack, playbookMd))],
      stream: false,
      maxSteps: 1,  // planning 不需要 tool
      signal,
    });
    const text = extractAssistantText(messages);
    const parsed = parsePlanJson(text);
    if (parsed === null) return fallback;
    return normalizePlan(parsed, title, pack);
  } catch {
    return fallback;
  }
};

const extractAssistantText = (messages: UIMessage[]): string => latestAssistantText(messages);

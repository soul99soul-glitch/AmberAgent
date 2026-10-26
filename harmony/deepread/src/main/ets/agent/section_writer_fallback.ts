// Pure fallback text and derived narrative content.
import type { DeepReadGenerationStage } from '../domain/enums.ts';
import { OVERVIEW_SUMMARY_STORAGE_MAX_CHARS, ANALYSIS_IMPLICATIONS_MAX } from '../domain/enums.ts';
import type { TimelineEvent, CorePoint, ReadingLink } from '../domain/models.ts';
import { cleanText, safeTake } from './section_writer_codec.ts';

// ===== fallback 文本构造(照搬 Android fallbackBody/cleanAssistantFallback/isUsefulFallbackText) =====

const fallbackTextMax = (stage: DeepReadGenerationStage): number => {
  switch (stage) {
    case 'OVERVIEW': return OVERVIEW_SUMMARY_STORAGE_MAX_CHARS;
    case 'NARRATIVE': return 1_200;
    case 'ANALYSIS': return ANALYSIS_IMPLICATIONS_MAX;
    case 'EXTENDED_READING': return 600;
  }
};

const isUsefulFallbackText = (text: string): boolean => {
  const cjk = (text.match(/[\u4e00-\u9fff]/g) ?? []).length;
  return text.length >= 24 && cjk >= 12;
};

export const buildFallbackBody = (
  stage: DeepReadGenerationStage,
  assistantText: string,
  sources: ReadingLink[],
  topicTitle: string,
): string => {
  const cleaned = cleanAssistantFallbackPure(assistantText, fallbackTextMax(stage));
  if (isUsefulFallbackText(cleaned)) return cleaned;
  // 取第一个 source 的 excerpt(此处 source 是 ReadingLink,用 title 模拟)
  const firstSource = sources.find(s => s.title.trim().length > 0);
  if (firstSource !== undefined) {
    const srcText = cleanText(firstSource.title, fallbackTextMax(stage));
    if (srcText.length > 0) return srcText;
  }
  switch (stage) {
    case 'OVERVIEW': return `围绕「${topicTitle}」，当前来源已经提供了可继续阅读的基础事实，但模型未按约定写入结构化概览。`;
    case 'NARRATIVE': return `围绕「${topicTitle}」，现有来源显示事件已有多个公开节点，后续应优先沿时间线补齐关键进展。`;
    case 'ANALYSIS': return `围绕「${topicTitle}」，核心分析应聚焦已公开事实、各方立场和可能影响，避免把未证实推断写成定论。`;
    case 'EXTENDED_READING': return '';
  }
};

// 纯净版 cleanAssistantFallback(不污染 String prototype)
const cleanAssistantFallbackPure = (text: string, max: number): string => {
  const withoutFences = text.replace(/```[\s\S]*?```/g, ' ');
  const joined = withoutFences
    .split('\n')
    .map(line => line.trim().replace(/^[#\-*\s]+/, ''))
    .filter(line => {
      if (line.includes('deep_read_')) return false;
      if (line.includes('调用') && line.includes('工具')) return false;
      if (line.toLowerCase() === '好的') return false;
      return true;
    })
    .join(' ');
  return cleanText(joined, max);
};

const fallbackSentences = (text: string, limit: number): string[] =>
  text.split(/[。！？!?]\s*|\n+/)
    .map(s => cleanText(s, 220))
    .filter(s => s.length > 0)
    .slice(0, limit);

export const toFallbackCorePoints = (text: string): CorePoint[] =>
  fallbackSentences(text, 4).map(sentence => ({
    point: safeTake(sentence, 42),
    supporting: sentence.length > 42 ? cleanText(sentence, 240) : null,
    imageUrl: null,
    imageCaption: null,
  }));

export const sourcesToFallbackTimeline = (sources: ReadingLink[]): TimelineEvent[] =>
  sources.slice(0, 4).map((source, index) => {
    const date = source.source ?? `来源 ${index + 1}`;
    const eventText = source.title;
    return {
      date: cleanText(date, 40),
      event: cleanText(eventText, 260),
      isHighlight: false,
      imageUrl: null,
      imageCaption: null,
    } as TimelineEvent;
  }).filter(t => t.event.trim().length > 0);


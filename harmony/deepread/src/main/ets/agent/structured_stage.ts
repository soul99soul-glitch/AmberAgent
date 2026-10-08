// Text models write three editorial sections; numbered sources are assembled locally.
import type { DeepReadGenerationStage } from '../domain/enums.ts';
import { WRITER_TOOL_NAMES, VISUALS_TOOL_NAME, DIAGRAM_TOOL_NAME } from '../domain/enums.ts';
import type { DeepReadOutput } from '../domain/models.ts';
import type { SectionWriterTools } from './section_writer_tools.ts';
import { parsePlanJson } from '../research/article_plan.ts';
import { statusOf } from '../domain/helpers.ts';

export interface StructuredStageFailure {
  message: string;
  retryInstruction: string;
}

export const structuredStageFailureReason = (stage: DeepReadGenerationStage, text: string): StructuredStageFailure => {
  const parsed = parsePlanJson(text);
  if (parsed === null) return {
    message: '模型返回的内容格式不完整，请补全章节重试。',
    retryInstruction: '模型响应不是完整、可解析的 JSON，可能被截断或包含格式错误。',
  };
  switch (stage) {
    case 'OVERVIEW': return { message: '导语内容不足，请补全章节重试。',
      retryInstruction: 'JSON 可解析，但缺少有效导语：summary 至少需要24字。' };
    case 'NARRATIVE': return { message: '事件叙事或关键判断内容不足，请补全章节重试。',
      retryInstruction: 'JSON 可解析，但缺少叙事内容：需要一条至少20字的 timeline.event，或至少8字的 core_points.point / 20字的 supporting。' };
    case 'ANALYSIS': {
      const analysis = parsed['analysis'];
      const missingAnalysis = analysis === null || typeof analysis !== 'object' || Array.isArray(analysis);
      return { message: '分析内容不足，请补全章节重试。',
        retryInstruction: missingAnalysis
          ? 'JSON 可解析，但缺少 analysis 对象；请在其中提供 core_dispute 和 perspectives。'
          : 'JSON 可解析，但缺少有效分析内容：请展开至少一项立场或影响，viewpoint / implications 至少20字；简短争议或立场也可配合至少20字的 impacts.effect。' };
    }
    case 'EXTENDED_READING': return { message: '缺少可用的来源资料，请补全章节重试。',
      retryInstruction: 'JSON 可解析，但缺少可用的真实来源链接或已验证的视觉资料。' };
  }
};

export const structuredStageSchema = (stage: DeepReadGenerationStage): string => {
  switch (stage) {
    case 'OVERVIEW': return '{"topic_type":"event|opinion|product|person","bottom_line":"不超过40字的一句话结论","summary":"约120-250字完整导语","hero_image_url":"候选池真实图片或空字符串","hero_caption":"图片说明"}';
    case 'NARRATIVE': return '{"timeline":[{"date":"日期","event":"事件","is_highlight":true,"why":"为什么是转折点"}],"core_points":[{"point":"关键判断","supporting":"为什么重要","sources":[1,2]}]}';
    case 'ANALYSIS': return '{"analysis":{"core_dispute":"核心分歧","perspectives":[{"holder":"当事方","interest":"诉求或利害","viewpoint":"立场与理由","quote":"可靠原话或空字符串","quote_by":"姓名或机构，身份","sources":[1]}]},"impacts":[{"target":"受影响对象","horizon":"short|long","effect":"影响"}],"watch":["接下来关注什么、为什么"],"uncertainties":[{"claim":"待确认说法","status":"single_source|conflicting|pending_official"}]}';
    case 'EXTENDED_READING': return '{"extended_reading":[{"title":"来源标题","url":"实际提供的URL","source":"来源"}],"references":[{"title":"参考","url":"实际提供的URL","source":"来源"}],"hero_image_url":"候选池图片或空字符串","hero_caption":"说明"}';
  }
};

export const structuredArticleBody = (output: DeepReadOutput): object => ({
  topic_type: output.topicType, bottom_line: output.bottomLine, summary: output.summary, key_entities: output.keyEntities,
  timeline: output.timeline?.map(event => ({ date: event.date, event: event.event, is_highlight: event.isHighlight, why: event.why })) ?? null,
  core_points: output.corePoints, analysis: { core_dispute: output.analysis.coreDispute,
    perspectives: output.analysis.perspectives.map(p => ({ holder: p.holder, interest: p.interest, viewpoint: p.viewpoint, quote: p.quote, quote_by: p.quoteBy, sources: p.sources })), implications: output.analysis.implications, quotes: output.analysis.quotes },
  impacts: output.impacts, watch: output.watch, uncertainties: output.uncertainties, sources: output.sources,
  extended_reading: output.extendedReading, references: output.references,
  hero_image_url: output.heroImageUrl, hero_caption: output.heroCaption, diagram: output.diagram,
});

export const writeStructuredStage = async (writer: SectionWriterTools, stage: DeepReadGenerationStage, text: string): Promise<boolean> => {
  const parsed = parsePlanJson(text);
  if (parsed === null) return false;
  const tools = writer.tools(new Set([stage]));
  if (stage === 'OVERVIEW' || stage === 'EXTENDED_READING') {
    if (parsed['hero_image_url'] !== undefined || parsed['image_assets'] !== undefined) {
      await tools.find(tool => tool.name === VISUALS_TOOL_NAME)?.execute(JSON.stringify(parsed));
    }
  }
  if (stage === 'NARRATIVE' || stage === 'EXTENDED_READING') {
    const diagram = parsed['diagram'];
    if (diagram !== null && typeof diagram === 'object' && !Array.isArray(diagram)) {
      const type = (diagram as Record<string, unknown>)['type'];
      if (stage === 'EXTENDED_READING' || type === 'stakeholder_map' || type === 'system_structure' || type === 'comparison_matrix') {
        await tools.find(tool => tool.name === DIAGRAM_TOOL_NAME)?.execute(JSON.stringify(diagram));
      }
    }
  }
  let input: object = parsed;
  if (stage === 'ANALYSIS') {
    const analysis = parsed['analysis'];
    if (analysis === null || typeof analysis !== 'object' || Array.isArray(analysis)) return false;
    input = { ...analysis, references: parsed['references'], ...(parsed['impacts'] !== undefined ? { impacts: parsed['impacts'] } : {}),
      ...(parsed['watch'] !== undefined ? { watch: parsed['watch'] } : {}),
      ...(parsed['uncertainties'] !== undefined ? { uncertainties: parsed['uncertainties'] } : {}) };
  }
  await tools.find(tool => tool.name === WRITER_TOOL_NAMES[stage])?.execute(JSON.stringify(input));
  return statusOf(writer.current(), stage) === 'READY';
};

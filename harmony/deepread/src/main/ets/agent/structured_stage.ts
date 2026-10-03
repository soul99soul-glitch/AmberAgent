// Normal text models write the same four sections through the existing validators.
import type { DeepReadGenerationStage } from '../domain/enums.ts';
import { WRITER_TOOL_NAMES, VISUALS_TOOL_NAME, DIAGRAM_TOOL_NAME } from '../domain/enums.ts';
import type { DeepReadOutput } from '../domain/models.ts';
import type { SectionWriterTools } from './section_writer_tools.ts';
import { parsePlanJson } from '../research/article_plan.ts';
import { statusOf } from '../domain/helpers.ts';

export const structuredStageSchema = (stage: DeepReadGenerationStage): string => {
  switch (stage) {
    case 'OVERVIEW': return '{"topic_type":"event|opinion|product|person","summary":"约120-250字完整导语","key_entities":["实体"]}';
    case 'NARRATIVE': return '{"timeline":[{"date":"日期","event":"事件","is_highlight":true}],"core_points":[{"point":"关键脉络","supporting":"为什么重要"}]}';
    case 'ANALYSIS': return '{"analysis":{"core_dispute":"核心分歧","perspectives":[{"holder":"利益方","viewpoint":"立场"}],"implications":"影响","quotes":[{"text":"来源原话","attribution":"出处"}]}}';
    case 'EXTENDED_READING': return '{"extended_reading":[{"title":"来源标题","url":"实际提供的URL","source":"来源"}],"references":[{"title":"参考","url":"实际提供的URL","source":"来源"}],"hero_image_url":"候选池图片或空字符串","hero_caption":"说明"}';
  }
};

export const structuredArticleBody = (output: DeepReadOutput): object => ({
  topic_type: output.topicType, summary: output.summary, key_entities: output.keyEntities,
  timeline: output.timeline?.map(event => ({ date: event.date, event: event.event, is_highlight: event.isHighlight })) ?? null,
  core_points: output.corePoints, analysis: { core_dispute: output.analysis.coreDispute,
    perspectives: output.analysis.perspectives, implications: output.analysis.implications, quotes: output.analysis.quotes },
  extended_reading: output.extendedReading, references: output.references,
  hero_image_url: output.heroImageUrl, hero_caption: output.heroCaption, diagram: output.diagram,
});

export const writeStructuredStage = async (writer: SectionWriterTools, stage: DeepReadGenerationStage, text: string): Promise<boolean> => {
  const parsed = parsePlanJson(text);
  if (parsed === null) return false;
  const tools = writer.tools(new Set([stage]));
  if (stage === 'EXTENDED_READING') {
    if (parsed['hero_image_url'] !== undefined || parsed['image_assets'] !== undefined) {
      await tools.find(tool => tool.name === VISUALS_TOOL_NAME)?.execute(JSON.stringify(parsed));
    }
    const diagram = parsed['diagram'];
    if (diagram !== null && typeof diagram === 'object' && !Array.isArray(diagram)) {
      await tools.find(tool => tool.name === DIAGRAM_TOOL_NAME)?.execute(JSON.stringify(diagram));
    }
  }
  let input: object = parsed;
  if (stage === 'ANALYSIS') {
    const analysis = parsed['analysis'];
    if (analysis === null || typeof analysis !== 'object' || Array.isArray(analysis)) return false;
    input = { ...analysis, references: parsed['references'] };
  }
  await tools.find(tool => tool.name === WRITER_TOOL_NAMES[stage])?.execute(JSON.stringify(input));
  return statusOf(writer.current(), stage) === 'READY';
};

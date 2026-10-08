import type { DeepReadTemplateSource, DeepReadTemplateBrief, DeepReadTemplateAnswer, DeepReadTemplateCamp, DeepReadTemplateDebate, DeepReadTemplateEvent, DeepReadTemplateTurn, DeepReadTemplateTimeline, DeepReadTemplateReviewView, DeepReadTemplateReviewSplit, DeepReadTemplateReviewSpec, DeepReadTemplateReviewScore, DeepReadTemplateReview, DeepReadTemplateArticle } from './synthesis_article.ts';
export type { DeepReadTemplateSource, DeepReadTemplateBrief, DeepReadTemplateAnswer, DeepReadTemplateCamp, DeepReadTemplateDebate, DeepReadTemplateEvent, DeepReadTemplateTurn, DeepReadTemplateTimeline, DeepReadTemplateReviewView, DeepReadTemplateReviewSplit, DeepReadTemplateReviewSpec, DeepReadTemplateReviewScore, DeepReadTemplateReview, DeepReadTemplateArticle } from './synthesis_article.ts';
export { hasSynthesisBody, synthesisArticleMarkdown } from './synthesis_article.ts';
import { parsePlanJson } from '../research/article_plan.ts';
import type { DeepReadSource } from '../research/source_prefetcher.ts';

export type DeepReadSynthesisTemplateId = 'deepread_auto' | 'deepread_brief' | 'deepread_qa' | 'deepread_debate' | 'deepread_timeline' | 'deepread_review';
export interface DeepReadSynthesisTemplate { id: DeepReadSynthesisTemplateId; name: string; description: string; }
export const DEEPREAD_SYNTHESIS_TEMPLATES: DeepReadSynthesisTemplate[] = [
  { id: 'deepread_auto', name: '自动', description: '读完资料后由 Agent 选择最合适的模板。' },
  { id: 'deepread_brief', name: '速览简报', description: '5 条要点、背景、影响与待核实，更快更省。' },
  { id: 'deepread_qa', name: '问答解读', description: '读者最关心的问题，逐一回答并标注来源。' },
  { id: 'deepread_debate', name: '观点交锋', description: '核心争议、各方阵营与原话，以及你可以怎么看。' },
  { id: 'deepread_timeline', name: '时间线报道', description: '带来源的详细时间节点，解读转折点。' },
  { id: 'deepread_review', name: '评测汇总', description: '多家评测的共识与分歧、规格和打分对照。' },
];
export const synthesisTemplate = (id: string | undefined): DeepReadSynthesisTemplate | null =>
  DEEPREAD_SYNTHESIS_TEMPLATES.find(template => template.id === id) ?? null;
export const parseSynthesisPick = (text: string): DeepReadSynthesisTemplate | null => {
  const raw = parsePlanJson(text);
  const template = synthesisTemplate(raw === null ? undefined : string(raw['template']));
  return template?.id === 'deepread_auto' ? null : template;
};
const numbered = (sources: DeepReadSource[]): DeepReadSource[] => sources.filter(source => source.evidenceText.trim().length > 0).slice(0, 12);
const sourcesBlock = (sources: DeepReadSource[], limit: number): string => numbered(sources).map((source, index) =>
  `[${index + 1}] ${source.title}\n${source.evidenceText.trim().slice(0, limit)}`).join('\n\n');
export const synthesisPickPrompt = (title: string, sources: DeepReadSource[]): string =>
  `你要为话题「${title}」选择最合适的深度阅读写法。只看下面来源标题和开头，不要写文章。\n${DEEPREAD_SYNTHESIS_TEMPLATES.slice(1).map(template => `- ${template.id}：${template.name}，${template.description}`).join('\n')}\n- magazine：经典杂志长文，适合背景复杂、需要全面梳理时间线与各方分析的重大事件\n只输出 JSON：{"template":"deepread_brief","reason":"一句话理由"}\n\n## 来源\n${sourcesBlock(sources, 160)}`;
const SCHEMAS: Record<string, string> = {
  deepread_brief: '{"points":["5条要点，每条50字以内"],"background":"100-200字背景","impact":"100-200字影响","uncertain":["0-4条待核实说法"]}',
  deepread_qa: '{"questions":[{"q":"5-8个问题由浅入深，每题30字内","a":"80-220字回答","sources":[1,2]}]}',
  deepread_debate: '{"dispute":"核心争议，80字内","camps":[{"stance":"pro|con|neutral","label":"阵营名称","holders":["代表人物或机构"],"argument":"主要论点160字内","quote":"来源原话，没有则空","quote_by":"说话人","sources":[1]}],"takeaway":"权衡各方，不替读者站队，160字内"}，camps需2-4个阵营',
  deepread_timeline: '{"events":[{"date":"具体日期","event":"事件60字内","turning":false,"sources":[1]}],"turns":[{"date":"转折日期","why":"120字内解读"}]}，events按时间顺序10-15项，转折1-3项',
  deepread_review: '{"verdict":"60字内总结","consensus":["3-5条共识"],"splits":[{"topic":"分歧10字内","views":[{"source":1,"view":"看法40字内"}]}],"specs":[{"name":"规格","value":"值"}],"scores":[{"source":1,"score":"原样打分","note":"备注"}],"conclusion":"160字内购买建议"}，无打分则scores空数组',
};
export const synthesisPrompt = (template: DeepReadSynthesisTemplate, title: string, sources: DeepReadSource[]): string =>
  `你是深度阅读的编辑，用「${template.name}」把下面来源整理成关于「${title}」的中文深度阅读。\n只用来源事实，不编造；所有文字使用简体中文。只输出合法JSON，不要解释和代码围栏。每个输出都应包含title（30字内）和lede（80-160字导语）。sources为依据的来源编号数组。\n## 模板要求\n${SCHEMAS[template.id]}\n\n## 来源\n${sourcesBlock(sources, 1200)}`;
const string = (value: unknown): string => typeof value === 'string' ? value.trim() : '';
const strings = (value: unknown, limit: number): string[] => Array.isArray(value) ? value.map(string).filter(text => text.length > 0).slice(0, limit) : [];
const objects = (value: unknown): Record<string, unknown>[] => Array.isArray(value) ? value.filter(item => item !== null && typeof item === 'object' && !Array.isArray(item)) as Record<string, unknown>[] : [];
const integer = (value: unknown): number | null => {
  const number = typeof value === 'number' ? value : typeof value === 'string' && value.trim().length > 0 ? Number(value) : NaN;
  return Number.isInteger(number) ? number : null;
};
export const parseSynthesisArticle = (text: string, template: DeepReadSynthesisTemplate, title: string, sources: DeepReadSource[]): DeepReadTemplateArticle | null => {
  const raw = parsePlanJson(text);
  if (raw === null || template.id === 'deepread_auto') return null;
  const numberedSources = numbered(sources);
  const cited = (value: unknown): number[] => Array.isArray(value) ? value.map(integer).filter((id): id is number => id !== null && id > 0 && id <= numberedSources.length) : [];
  const article: DeepReadTemplateArticle = { shape: 'template_synthesis', template: template.id, title: string(raw['title']) || title, lede: string(raw['lede']),
    sources: numberedSources.map((source, index) => ({ id: index + 1, title: source.title, url: source.url || null, site: source.url.match(/^https?:\/\/([^/]+)/i)?.[1] ?? source.source ?? '资料' })) };
  switch (template.id) {
    case 'deepread_brief': {
      const points = strings(raw['points'], 5);
      if (points.length === 0) return null;
      article.brief = { points, background: string(raw['background']), impact: string(raw['impact']), uncertain: strings(raw['uncertain'], 4) }; break;
    }
    case 'deepread_qa': {
      const qa: DeepReadTemplateAnswer[] = objects(raw['questions']).map(item => ({ question: string(item['q']), answer: string(item['a']), sources: cited(item['sources']) })).filter(item => item.question.length > 0 && item.answer.length > 0).slice(0, 8);
      if (qa.length === 0) return null; article.qa = qa; break;
    }
    case 'deepread_debate': {
      const camps: DeepReadTemplateCamp[] = objects(raw['camps']).map(item => ({ stance: ['pro', 'con', 'neutral'].includes(string(item['stance'])) ? string(item['stance']) : 'neutral', label: string(item['label']), holders: strings(item['holders'], 4), argument: string(item['argument']), quote: string(item['quote']), quoteBy: string(item['quote_by']), sources: cited(item['sources']) })).filter(item => item.argument.length > 0).slice(0, 4);
      if (camps.length < 2) return null;
      article.debate = { dispute: string(raw['dispute']), camps, takeaway: string(raw['takeaway']) }; break;
    }
    case 'deepread_timeline': {
      const events: DeepReadTemplateEvent[] = objects(raw['events']).map(item => ({ date: string(item['date']), event: string(item['event']), turning: item['turning'] === true, sources: cited(item['sources']) })).filter(item => item.event.length > 0).slice(0, 15);
      if (events.length < 3) return null;
      article.timeline = { events, turns: objects(raw['turns']).map(item => ({ date: string(item['date']), why: string(item['why']) })).filter(item => item.why.length > 0).slice(0, 3) }; break;
    }
    case 'deepread_review': {
      const verdict = string(raw['verdict']); if (verdict.length === 0) return null;
      const splits: DeepReadTemplateReviewSplit[] = objects(raw['splits']).map(item => ({ topic: string(item['topic']), views: objects(item['views']).map(view => ({ source: integer(view['source']) ?? 0, view: string(view['view']) })).filter(view => cited([view.source]).length > 0 && view.view.length > 0) })).filter(item => item.topic.length > 0 && item.views.length > 0).slice(0, 4);
      const specs: DeepReadTemplateReviewSpec[] = objects(raw['specs']).map(item => ({ name: string(item['name']), value: string(item['value']) })).filter(item => item.name.length > 0 && item.value.length > 0).slice(0, 8);
      const scores: DeepReadTemplateReviewScore[] = objects(raw['scores']).map(item => ({ source: integer(item['source']) ?? 0, score: string(item['score']), note: string(item['note']) })).filter(item => cited([item.source]).length > 0 && item.score.length > 0);
      article.review = { verdict, consensus: strings(raw['consensus'], 5), splits, specs, scores, conclusion: string(raw['conclusion']) }; break;
    }
  }
  return article;
};

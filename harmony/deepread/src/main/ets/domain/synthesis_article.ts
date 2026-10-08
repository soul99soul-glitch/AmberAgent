export interface DeepReadTemplateSource { id: number; title: string; url: string | null; site: string; }
export interface DeepReadTemplateBrief { points: string[]; background: string; impact: string; uncertain: string[]; }
export interface DeepReadTemplateAnswer { question: string; answer: string; sources: number[]; }
export interface DeepReadTemplateCamp { stance: string; label: string; holders: string[]; argument: string; quote: string; quoteBy: string; sources: number[]; }
export interface DeepReadTemplateDebate { dispute: string; camps: DeepReadTemplateCamp[]; takeaway: string; }
export interface DeepReadTemplateEvent { date: string; event: string; turning: boolean; sources: number[]; }
export interface DeepReadTemplateTurn { date: string; why: string; }
export interface DeepReadTemplateTimeline { events: DeepReadTemplateEvent[]; turns: DeepReadTemplateTurn[]; }
export interface DeepReadTemplateReviewView { source: number; view: string; }
export interface DeepReadTemplateReviewSplit { topic: string; views: DeepReadTemplateReviewView[]; }
export interface DeepReadTemplateReviewSpec { name: string; value: string; }
export interface DeepReadTemplateReviewScore { source: number; score: string; note: string; }
export interface DeepReadTemplateReview { verdict: string; consensus: string[]; splits: DeepReadTemplateReviewSplit[]; specs: DeepReadTemplateReviewSpec[]; scores: DeepReadTemplateReviewScore[]; conclusion: string; }
export interface DeepReadTemplateArticle {
  shape: 'template_synthesis'; template: string; title: string; lede: string; sources: DeepReadTemplateSource[];
  brief?: DeepReadTemplateBrief; qa?: DeepReadTemplateAnswer[]; debate?: DeepReadTemplateDebate;
  timeline?: DeepReadTemplateTimeline; review?: DeepReadTemplateReview;
}
/** Completion belongs to the selected content shape, never synthetic magazine stages. */
export const hasSynthesisBody = (article: DeepReadTemplateArticle): boolean => {
  if (article.shape !== 'template_synthesis') return false;
  switch (article.template) {
    case 'deepread_brief': return (article.brief?.points.some(point => point.trim().length > 0) ?? false);
    case 'deepread_qa': return article.qa?.some(item => item.question.trim().length > 0 && item.answer.trim().length > 0) ?? false;
    case 'deepread_debate': return (article.debate?.camps.filter(camp => camp.argument.trim().length > 0).length ?? 0) >= 2;
    case 'deepread_timeline': return (article.timeline?.events.filter(event => event.event.trim().length > 0).length ?? 0) >= 3;
    case 'deepread_review': return (article.review?.verdict.trim().length ?? 0) > 0;
    default: return false;
  }
};
export const synthesisArticleMarkdown = (article: DeepReadTemplateArticle, includeSources: boolean = true): string => {
  const refs = (ids: number[]): string => ids.length > 0 ? ` ${ids.map(id => `[${id}]`).join('')}` : '';
  const bullets = (items: string[]): string => `${items.map(item => `- ${item}`).join('\n')}\n\n`;
  let body = `# ${article.title}\n\n${article.lede.length > 0 ? `> ${article.lede}\n\n` : ''}`;
  if (article.brief) {
    body += `## 要点\n${bullets(article.brief.points)}`;
    if (article.brief.background) body += `## 背景\n\n${article.brief.background}\n\n`;
    if (article.brief.impact) body += `## 影响\n\n${article.brief.impact}\n\n`;
    if (article.brief.uncertain.length) body += `## 待核实\n${bullets(article.brief.uncertain)}`;
  }
  for (const answer of article.qa ?? []) body += `## ${answer.question}\n\n${answer.answer}${refs(answer.sources)}\n\n`;
  if (article.debate) {
    body += `## 核心争议\n\n${article.debate.dispute}\n\n`;
    for (const camp of article.debate.camps) {
      const stance = camp.stance === 'pro' ? '支持' : camp.stance === 'con' ? '反对' : '中立';
      body += `### ${stance} · ${camp.label}${camp.holders.length ? `（${camp.holders.join('、')}）` : ''}\n\n${camp.argument}${refs(camp.sources)}\n\n`;
      if (camp.quote) body += `> “${camp.quote}”${camp.quoteBy ? ` —— ${camp.quoteBy}` : ''}\n\n`;
    }
    if (article.debate.takeaway) body += `## 你可以怎么看\n\n${article.debate.takeaway}\n\n`;
  }
  if (article.timeline) {
    body += `## 时间线\n${bullets(article.timeline.events.map(event => `**${event.date}**${event.turning ? '（转折）' : ''} ${event.event}${refs(event.sources)}`))}`;
    if (article.timeline.turns.length) body += `## 转折点\n${bullets(article.timeline.turns.map(turn => `**${turn.date}** ${turn.why}`))}`;
  }
  if (article.review) {
    body += `## 结论\n\n**${article.review.verdict}**\n\n`;
    if (article.review.consensus.length) body += `## 各家共识\n${bullets(article.review.consensus)}`;
    for (const split of article.review.splits) body += `## 分歧：${split.topic}\n${bullets(split.views.map(view => `[${view.source}] ${view.view}`))}`;
    if (article.review.specs.length) body += `## 关键规格\n${bullets(article.review.specs.map(spec => `${spec.name}：${spec.value}`))}`;
    if (article.review.scores.length) body += `## 打分\n${bullets(article.review.scores.map(score => `[${score.source}] ${score.score}${score.note ? `（${score.note}）` : ''}`))}`;
    if (article.review.conclusion) body += `## 买不买\n\n${article.review.conclusion}\n\n`;
  }
  if (includeSources) {
    const escapeLabel = (text: string): string => text.replace(/([\\`*_{}\[\]()<>#!|$])/g, '\\$1');
    body += `## 来源\n${bullets(article.sources.map(source => {
      const title: string = escapeLabel(source.title);
      const url: string = (source.url ?? '').replace(/\(/g, '%28').replace(/\)/g, '%29');
      const link: string = /^https?:\/\/[^\s<>"']+$/i.test(url) ? `[${title}](${url})` : title;
      return `[${source.id}] ${link}${source.site ? ` · ${escapeLabel(source.site)}` : ''}`;
    }))}`;
  }
  return body.trim();
};

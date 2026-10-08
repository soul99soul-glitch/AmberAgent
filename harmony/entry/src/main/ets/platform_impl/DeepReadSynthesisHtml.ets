import type { DeepReadTemplateArticle } from '@amber/deepread-domain';
import { deepReadMarkdownHtml, escapeDeepReadHtml, safeDeepReadLink } from './DeepReadMarkdownHtml.ets';

const esc = escapeDeepReadHtml;
const md = deepReadMarkdownHtml;
const inline = (text: string): string => md(text).replace(/^<p>/, '').replace(/<\/p>$/, '');

const templateName = (id: string): string => {
  switch (id) {
    case 'deepread_qa': return '问答解读';
    case 'deepread_debate': return '观点交锋';
    case 'deepread_timeline': return '时间线报道';
    case 'deepread_review': return '评测汇总';
    default: return '速览简报';
  }
};

const section = (title: string, body: string, className: string = ''): string =>
  `<section${className.length > 0 ? ` class="${className}"` : ''}><h2 class="sec">${esc(title)}</h2>${body}</section>`;

const list = (items: string[], ordered: boolean = false): string => {
  const tag: string = ordered ? 'ol' : 'ul';
  let body: string = '';
  for (const item of items) body += `<li>${inline(item)}</li>`;
  return `<${tag}${ordered ? ' class="numbered"' : ''}>${body}</${tag}>`;
};

const citations = (ids: number[], validIds: Set<number>): string => {
  const seen: Set<number> = new Set<number>();
  let body: string = '';
  for (const id of ids) {
    if (!validIds.has(id) || seen.has(id)) continue;
    seen.add(id);
    body += `<a href="#dr-source-${id}" aria-label="来源 ${id}">[${id}]</a>`;
  }
  return body.length > 0 ? `<sup class="cite">${body}</sup>` : '';
};

// Keep citation marks with the final paragraph; lists and tables keep their own block layout.
const block = (text: string, ids: number[], validIds: Set<number>): string => {
  const html: string = md(text);
  const marks: string = citations(ids, validIds);
  if (marks.length === 0) return html;
  return /<\/p>\s*$/.test(html) ? html.replace(/<\/p>\s*$/, `${marks}</p>`) : html + `<p>${marks}</p>`;
};

const sourceSite = (article: DeepReadTemplateArticle, id: number): string => {
  for (const source of article.sources) if (source.id === id) return source.site;
  return '';
};

export const renderDeepReadSynthesisBody = (article: DeepReadTemplateArticle): string => {
  const validIds: Set<number> = new Set<number>();
  for (const source of article.sources) {
    if (Number.isInteger(source.id) && source.id > 0) validIds.add(source.id);
  }
  let html: string = '<article class="synthesis-template"><section class="headline">'
    + `<p class="kicker">${templateName(article.template)}</p><h1 class="title">${esc(article.title)}</h1>`;
  if (article.lede.length > 0) html += `<div class="summary markdown-body">${md(article.lede)}</div>`;
  html += '</section>';

  const brief = article.brief;
  if (brief !== undefined && brief !== null) {
    html += section('要点', list(brief.points, true));
    if (brief.background.length > 0) html += section('背景', md(brief.background));
    if (brief.impact.length > 0) html += section('影响', md(brief.impact));
    if (brief.uncertain.length > 0) html += section('待核实', list(brief.uncertain), 'uncertain');
  }

  if (article.qa !== undefined && article.qa !== null) {
    html += '<section class="qa">';
    for (let index: number = 0; index < article.qa.length; index++) {
      const answer = article.qa[index];
      html += `<div class="qa-item"><p class="q"><span class="qn">Q${index + 1}</span>`
        + `<span>${inline(answer.question)}</span></p><div class="a">`
        + block(answer.answer, answer.sources, validIds) + '</div></div>';
    }
    html += '</section>';
  }

  const debate = article.debate;
  if (debate !== undefined && debate !== null) {
    if (debate.dispute.length > 0) html += section('核心争议', `<p class="line">${inline(debate.dispute)}</p>`, 'fact');
    let camps: string = '';
    for (const camp of debate.camps) {
      const stance: string = camp.stance === 'pro' || camp.stance === 'con' ? camp.stance : 'neutral';
      const label: string = stance === 'pro' ? '支持' : stance === 'con' ? '反对' : '中立';
      camps += `<div class="camp ${stance}"><p class="camp-head"><span class="badge">${label}</span><b>${esc(camp.label)}</b></p>`;
      if (camp.holders.length > 0) camps += `<p class="holders">${esc(camp.holders.join('、'))}</p>`;
      camps += block(camp.argument, camp.sources, validIds);
      if (camp.quote.length > 0) {
        camps += `<blockquote><p>“${esc(camp.quote)}”</p>`
          + (camp.quoteBy.length > 0 ? `<small>—— ${esc(camp.quoteBy)}</small>` : '') + '</blockquote>';
      }
      camps += '</div>';
    }
    html += section('各方阵营', camps, 'camps');
    if (debate.takeaway.length > 0) html += section('你可以怎么看', `<div class="claim">${md(debate.takeaway)}</div>`, 'argument');
  }

  const timeline = article.timeline;
  if (timeline !== undefined && timeline !== null) {
    let events: string = '<ol>';
    for (const event of timeline.events) {
      events += `<li${event.turning ? ' class="turn"' : ''}><span class="date">${esc(event.date)}</span>`
        + `<p>${inline(event.event)}${citations(event.sources, validIds)}</p></li>`;
    }
    html += section('时间线', events + '</ol>', 'events');
    if (timeline.turns.length > 0) {
      let turns: string = '';
      for (const turn of timeline.turns) turns += `<div class="party"><b>${esc(turn.date)}</b>${md(turn.why)}</div>`;
      html += section('转折点', turns, 'parties');
    }
  }

  const review = article.review;
  if (review !== undefined && review !== null) {
    html += section('结论', `<p class="line">${inline(review.verdict)}</p>`, 'verdict');
    if (review.consensus.length > 0) html += section('各家共识', `<div class="pair"><div class="good"><b>一致认为</b>${list(review.consensus)}</div></div>`, 'proscons');
    for (const split of review.splits) {
      let views: string = '';
      for (const view of split.views) views += `<div class="party"><b>${esc(sourceSite(article, view.source))}</b>`
        + `<p>${inline(view.view)}${citations([view.source], validIds)}</p></div>`;
      html += section(`分歧 · ${split.topic}`, views, 'parties');
    }
    if (review.specs.length > 0) {
      let specs: string = '<div class="table-wrap"><table><tbody>';
      for (const spec of review.specs) specs += `<tr><th scope="row">${esc(spec.name)}</th><td>${esc(spec.value)}</td></tr>`;
      html += section('关键规格', specs + '</tbody></table></div>', 'specs');
    }
    if (review.scores.length > 0) {
      let scores: string = '<div class="score-grid">';
      for (const score of review.scores) {
        scores += `<div><span class="score">${esc(score.score)}</span><b>${esc(sourceSite(article, score.source))}`
          + `${citations([score.source], validIds)}</b>`
          + (score.note.length > 0 ? `<small>${esc(score.note)}</small>` : '') + '</div>';
      }
      html += section('打分对照', scores + '</div>', 'scores');
    }
    if (review.conclusion.length > 0) html += section('买不买', `<div class="claim">${md(review.conclusion)}</div>`, 'argument');
  }

  if (validIds.size > 0) {
    const seen: Set<number> = new Set<number>();
    let sources: string = '<ol>';
    for (const source of article.sources) {
      if (!validIds.has(source.id) || seen.has(source.id)) continue;
      seen.add(source.id);
      const url: string = source.url === null ? '' : safeDeepReadLink(source.url);
      const title: string = esc(source.title.length > 0 ? source.title : source.url ?? '');
      sources += `<li id="dr-source-${source.id}"><span class="n">[${source.id}]</span><div>`
        + (url.length > 0 ? `<a href="${esc(url)}">${title}</a>` : title)
        + `<small>${esc(source.site)}</small></div></li>`;
    }
    html += section('来源', sources + '</ol>', 'refs');
  }
  return html + '</article>';
};

export const DEEPREAD_SYNTHESIS_CSS: string = `
.synthesis-template{min-width:0;overflow-wrap:anywhere;}
.synthesis-template .headline{margin-bottom:24px;}
.synthesis-template section p{margin:0 0 10px;}
.synthesis-template .numbered{margin:0;padding-left:1.4em;}
.synthesis-template li{line-height:1.65;}
.synthesis-template .numbered li{font-size:calc(15px * var(--ds));margin:0 0 8px;}
.synthesis-template .cite{font-family:var(--dr-sans,var(--dr-font));font-size:calc(10px * var(--ds));color:var(--dr-accent);margin-left:2px;}
.synthesis-template .cite a{display:inline;text-decoration:none;}
.synthesis-template .qa-item{padding:14px 0;border-top:1px solid var(--dr-border);}
.synthesis-template .qa-item .q{font-size:calc(17px * var(--ds));line-height:1.5;font-weight:600;margin:0 0 8px;display:flex;gap:10px;text-indent:0;text-align:left;}
.synthesis-template .qn{font-family:var(--dr-sans,var(--dr-font));font-size:calc(12px * var(--ds));font-weight:700;color:var(--dr-accent);padding-top:3px;flex:0 0 auto;}
.synthesis-template .q>span:last-child{min-width:0;}
.synthesis-template .a{padding-left:30px;}
.synthesis-template .a p{font-size:calc(15px * var(--ds));line-height:1.75;}
.synthesis-template .line{font-size:calc(19px * var(--ds));font-weight:600;line-height:1.55;}
.synthesis-template .camp{--synthesis-note:var(--dr-muted);border:1px solid var(--dr-border);border-left:4px solid var(--synthesis-note);border-radius:12px;padding:12px 14px;margin:0 0 10px;}
.synthesis-template .camp.pro{--synthesis-note:#71927d;}
.synthesis-template .camp.con{--synthesis-note:var(--dr-accent);}
.synthesis-template .camp-head{display:flex;gap:8px;align-items:baseline;flex-wrap:wrap;text-indent:0;text-align:left;}
.synthesis-template .badge{color:var(--dr-ink);border:1px solid var(--synthesis-note);border-radius:5px;padding:1px 6px;font-family:var(--dr-sans,var(--dr-font));font-size:calc(11px * var(--ds));flex:0 0 auto;}
.synthesis-template .camp-head b{font-size:calc(16px * var(--ds));}
.synthesis-template .camp p{font-size:calc(15px * var(--ds));line-height:1.7;}
.synthesis-template .camp p.holders{font-family:var(--dr-sans,var(--dr-font));font-size:calc(12px * var(--ds));color:var(--dr-muted);margin:0 0 8px;}
.synthesis-template .camp p.camp-head{margin:0 0 4px;}
.synthesis-template .camp blockquote{margin:8px 0 0;padding:0 0 0 10px;border:0;border-left:2px solid var(--dr-border);border-radius:0;background:none;color:var(--dr-ink);}
.synthesis-template .camp blockquote p{font-style:italic;margin:0;}
.synthesis-template .camp blockquote::before{content:none;}
.synthesis-template .camp blockquote small{font-family:var(--dr-sans,var(--dr-font));font-size:calc(11px * var(--ds));color:var(--dr-muted);}
.synthesis-template .claim{border-left:3px solid var(--dr-accent);padding:14px 16px;background:var(--dr-surface);border-radius:0 12px 12px 0;}
.synthesis-template .claim>:last-child{margin-bottom:0;}
.synthesis-template .events ol{list-style:none;margin:0;padding:0 0 0 16px;border-left:2px solid var(--dr-border);}
.synthesis-template .events li{position:relative;padding:0 0 16px;}
.synthesis-template .events .date{display:block;margin-bottom:4px;}
.synthesis-template .events li.turn .date::before{content:'● ';color:var(--dr-accent);}
.synthesis-template .events li.turn p{font-weight:600;}
.synthesis-template .party{border-top:1px solid var(--dr-border);padding:12px 0;}
.synthesis-template .party>b{font-family:var(--dr-sans,var(--dr-font));font-size:calc(12px * var(--ds));color:var(--dr-muted);display:block;margin-bottom:6px;}
.synthesis-template .pair .good{border-left:3px solid #71927d;padding:12px 14px;background:var(--dr-surface);border-radius:0 12px 12px 0;}
.synthesis-template .pair ul{padding-left:1.3em;margin:8px 0 0;}
.synthesis-template .specs table{width:100%;table-layout:fixed;}
.synthesis-template .specs th,.synthesis-template .specs td{min-width:0;text-align:left;overflow-wrap:anywhere;}
.synthesis-template .specs th{width:36%;font-weight:600;}
.synthesis-template .score-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(110px,100%),1fr));gap:10px;}
.synthesis-template .score-grid>div{min-width:0;border:1px solid var(--dr-border);border-radius:12px;padding:12px;text-align:center;}
.synthesis-template .score-grid .score{display:block;font-size:calc(26px * var(--ds));font-weight:700;color:var(--dr-accent);}
.synthesis-template .score-grid b{display:block;font-family:var(--dr-sans,var(--dr-font));font-size:calc(12px * var(--ds));margin-top:2px;}
.synthesis-template .score-grid small{display:block;font-family:var(--dr-sans,var(--dr-font));font-size:calc(11px * var(--ds));color:var(--dr-muted);margin-top:4px;}
.synthesis-template .refs ol{list-style:none;margin:0;padding:0;}
.synthesis-template .refs li{display:grid;grid-template-columns:30px minmax(0,1fr);gap:6px;padding:8px 0;border-top:1px solid var(--dr-border);scroll-margin-top:20px;}
.synthesis-template .refs .n{font-family:var(--dr-sans,var(--dr-font));font-size:calc(11px * var(--ds));color:var(--dr-accent);padding-top:2px;}
.synthesis-template .refs li>div{min-width:0;overflow-wrap:anywhere;}
.synthesis-template .refs a{font-size:calc(14px * var(--ds));line-height:1.5;color:var(--dr-ink);text-decoration:none;}
.synthesis-template .refs small{display:block;font-family:var(--dr-sans,var(--dr-font));font-size:calc(11px * var(--ds));color:var(--dr-muted);}
`;

// DeepRead template rendering: all article chapters share one safe Markdown renderer.
import type { DeepReadOutput, DeepReadTemplateSnapshot, Storage } from '@amber/deepread-domain';
import {
  makeEmptyDeepReadOutput, ReadingLink, DeepReadDiagram, IMAGE_CONFIDENCE,
  displayHeroImageUrl, displayHeroCaption, DEEPREAD_SYNTHESIS_TEMPLATES,
} from '@amber/deepread-domain';
import {
  deepReadMarkdownHtml, escapeDeepReadHtml, safeDeepReadLink, safeDeepReadImage,
} from './DeepReadMarkdownHtml.ets';

import { readerCanvas, readerStyleCss, readerLayoutCss, readerSectionOrder } from './DeepReadAppearance.ets';
import { renderDeepReadSynthesisBody, DEEPREAD_SYNTHESIS_CSS } from './DeepReadSynthesisHtml.ets';

export const DEEPREAD_TEMPLATE_NONE: string = 'none';
export const DEEPREAD_TEMPLATE_EDITORIAL_SLANT: string = 'editorial_slant';
export interface DeepReadTemplateDef { id: string; name: string; description: string; }
export const BUILTIN_TEMPLATES: DeepReadTemplateDef[] = [
  DEEPREAD_SYNTHESIS_TEMPLATES[0],
  { id: DEEPREAD_TEMPLATE_NONE, name: '默认杂志', description: '纯文字杂志长文：时间轴、脉络、分析与延伸阅读。' },
  { id: DEEPREAD_TEMPLATE_EDITORIAL_SLANT, name: '斜切图文', description: '头图与文中配图的图文版。' },
  ...DEEPREAD_SYNTHESIS_TEMPLATES.slice(1),
];
export const isTemplateSelectable = (id: string): boolean => id === DEEPREAD_TEMPLATE_EDITORIAL_SLANT
  || DEEPREAD_SYNTHESIS_TEMPLATES.some((template): boolean => template.id === id);

export interface DeepReadRenderOptions {
  readerLayout?: string;
  readerStyle?: string;
  dark?: boolean;
  fontScale?: number;
  fontSerif?: boolean;
  background?: string;
  foreground?: string;
  muted?: string;
  surface?: string;
  border?: string;
  accent?: string;
}
export type EditorialSlantOptions = DeepReadRenderOptions;

const CSS_ARTICLE: string = `
body { margin:0; padding:24px 20px 40px; background:var(--dr-bg); color:var(--dr-ink);
  font-size:calc(15.5px * var(--ds)); font-family:var(--dr-font); line-height:1.75; overflow-wrap:anywhere; }
h1,h2,h3,h4,h5,h6 { color:var(--dr-ink); line-height:1.35; }
.title { font-size:calc(30px * var(--ds)); margin:0 0 10px; }
.sec { font-size:calc(20px * var(--ds)); margin:28px 0 12px; }
.sec::before { content:''; display:inline-block; width:22px; height:3px;
  background:var(--dr-accent); margin-right:10px; vertical-align:middle; }
p { margin:0 0 14px; } a { color:var(--dr-accent); }
.kicker,.date,.holder { font-size:calc(12px * var(--ds)); font-weight:600; }
.kicker,.holder { color:var(--dr-accent); }
.date { color:var(--dr-muted); }
.kicker { letter-spacing:2px; margin-bottom:8px; }
.meta,.cap,.by,.src { color:var(--dr-muted); font-size:calc(12px * var(--ds)); }
.meta { margin-bottom:18px; } .cap { margin-top:6px; } .by { display:block; margin-top:8px; }
.bottom-line { font-size:calc(19px * var(--ds)); font-weight:700; line-height:1.55; margin:14px 0; }
.summary { font-size:calc(16px * var(--ds)); color:var(--dr-muted); }
.point h3 { font-size:calc(20px * var(--ds)); line-height:1.45; margin:0 0 8px; }
.cite { white-space:nowrap; font-size:calc(11px * var(--ds)); margin-left:5px; }
.cite a { text-decoration:none; margin-right:3px; }
.timeline-why,.interest { color:var(--dr-muted); font-size:calc(13px * var(--ds)); }
.impacts { list-style:none; margin:0; padding:0; }
.impacts li { border-bottom:1px solid var(--dr-border); padding:12px 0; }
.impact-target { font-weight:700; margin-bottom:5px; }
.impact-target small,.claim-status { display:inline-block; color:var(--dr-muted); font-size:calc(11px * var(--ds));
  background:var(--dr-surface); border:1px solid var(--dr-border); padding:2px 7px; border-radius:5px; margin-left:8px; }
.claim-status { margin-left:0; margin-right:8px; } .watch { margin-top:18px; }
.uncertain li { margin:10px 0; } .src-no { margin-right:8px; }
.reading [id] { scroll-margin-top:20px; }
.entities { list-style:none; padding:0; margin:16px 0; }
.entities li { display:inline-block; background:var(--dr-surface); border:1px solid var(--dr-border);
  border-radius:14px; padding:4px 12px; font-size:calc(13px * var(--ds)); margin:0 6px 8px 0; }
.timeline { border-left:2px solid var(--dr-border); padding-left:18px; }
.ev,.point { margin-bottom:20px; } .point { padding:14px; background:var(--dr-surface); border-radius:12px; }
.dispute,.implication,blockquote { background:var(--dr-surface); border:1px solid var(--dr-border);
  border-radius:12px; padding:14px 16px; margin:14px 0; }
.persp { border-top:1px solid var(--dr-border); padding:12px 0; }
figure { margin:16px 0 22px; } img.hero,img.inline { width:100%; height:auto; border-radius:12px; display:block; }
.reading a { color:var(--dr-ink); display:block; min-height:32px; padding:10px 0; border-bottom:1px solid var(--dr-border); }
.reading .src { display:block; } .diagram-node { background:var(--dr-surface); border:1px solid var(--dr-border);
  border-radius:10px; padding:12px 14px; margin:10px 0; }
.diagram-edge { margin:8px 0; } .diagram-note { margin-top:6px; }
pre { overflow-x:auto; padding:14px; background:var(--dr-surface); border-radius:10px; white-space:pre; }
code { font-family:monospace; font-size:.92em; background:var(--dr-surface); }
.table-wrap { overflow-x:auto; margin:14px 0; } table { border-collapse:collapse; min-width:100%; }
th,td { border:1px solid var(--dr-border); padding:8px 10px; min-width:80px; }
hr { border:0; border-top:1px solid var(--dr-border); margin:24px 0; }
`;
const CSP: string = "default-src 'none'; script-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'; style-src 'unsafe-inline'; img-src https: http: data:; font-src 'none'";
const esc = escapeDeepReadHtml;
const md = deepReadMarkdownHtml;
const inlineMarkdown = (raw: string): string => md(raw).replace(/^<p>/, '').replace(/<\/p>$/, '');
const hasNewHierarchy = (o: DeepReadOutput): boolean => (o.bottomLine ?? '').trim().length > 0
  || (o.sources ?? []).length > 0 || (o.impacts ?? []).length > 0 || (o.watch ?? []).length > 0
  || (o.uncertainties ?? []).length > 0 || (o.corePoints ?? []).some((p): boolean => (p.sources ?? []).length > 0)
  || (o.timeline ?? []).some((e): boolean => (e.why ?? '').trim().length > 0)
  || o.analysis.perspectives.some((p): boolean => (p.interest ?? '').trim().length > 0
    || (p.quote ?? '').trim().length > 0 || (p.quoteBy ?? '').trim().length > 0 || (p.sources ?? []).length > 0);
const citations = (ids: number[] | undefined, o: DeepReadOutput): string => {
  const count: number = (o.sources ?? []).length;
  const valid: number[] = [];
  for (const id of ids ?? []) if (Number.isInteger(id) && id > 0 && id <= count && !valid.includes(id)) valid.push(id);
  return valid.length > 0 ? '<sup class="cite">' + valid.map((id: number): string =>
    `<a href="#dr-source-${id}" aria-label="来源 ${id}">[${id}]</a>`).join('') + '</sup>' : '';
};
const color = (raw: string | undefined, fallback: string): string =>
  raw !== undefined && /^#[0-9a-fA-F]{6}$/.test(raw.trim()) ? raw.trim() : fallback;
const scale = (raw: number | undefined): string =>
  (raw === undefined || !Number.isFinite(raw) ? 1 : Math.max(0.7, Math.min(1.8, raw))).toFixed(3);
const renderStyle = (opts?: DeepReadRenderOptions): string => {
  const font: string = opts?.fontSerif === true ? "Georgia,'Noto Serif SC',serif" : "'HarmonyOS Sans SC',sans-serif";
  return `<style>${CSS_ARTICLE}:root{--ds:${scale(opts?.fontScale)};`
    + `--dr-bg:${color(opts?.background, '#EFE7D6')};--dr-ink:${color(opts?.foreground, '#1B1A17')};`
    + `--dr-muted:${color(opts?.muted, '#8F8B80')};--dr-surface:${color(opts?.surface, '#FAF9F5')};`
    + `--dr-border:${color(opts?.border, '#E4E0D6')};--dr-accent:${color(opts?.accent, '#B8623A')};`
    + `--dr-font:${font}}body{font-family:var(--dr-font);font-size:calc(15.5px * var(--ds))}</style>`;
};
const head = (opts?: DeepReadRenderOptions): string =>
  '<meta charset="utf-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/>'
  + `<meta http-equiv="Content-Security-Policy" content="${esc(CSP)}"/>` + renderStyle(opts);
const image = (url: string | null, caption: string | null, hero: boolean = false): string => {
  const safe: string = url === null ? '' : safeDeepReadImage(url);
  if (safe.length === 0) return '';
  return `<figure><img class="${hero ? 'hero' : 'inline'}" src="${esc(safe)}" alt="${esc(caption ?? '')}"/>`
    + (caption !== null && caption.trim().length > 0 ? `<figcaption class="cap">${esc(caption)}</figcaption>` : '') + '</figure>';
};
const renderOverview = (o: DeepReadOutput): string => {
  let html: string = o.summary.trim().length > 0 ? `<div class="summary">${md(o.summary)}</div>` : '';
  if (!hasNewHierarchy(o) && o.keyEntities.length > 0) {
    html += '<ul class="entities">';
    for (const entity of o.keyEntities) html += `<li>${esc(entity)}</li>`;
    html += '</ul>';
  }
  return html;
};
const renderNarrative = (o: DeepReadOutput): string => {
  if (o.timeline === null || o.timeline.length === 0) return '';
  let html: string = '<section><h2 class="sec">时间轴</h2><div class="timeline">';
  for (const ev of o.timeline) {
    html += `<div class="ev"><div class="date">${esc(ev.date)}${ev.isHighlight ? ' · 关键节点' : ''}</div>`
      + md(ev.event)
      + (ev.isHighlight && (ev.why ?? '').trim().length > 0
        ? `<p class="timeline-why">转折：${inlineMarkdown(ev.why ?? '')}</p>` : '')
      + image(ev.imageUrl, ev.imageCaption) + '</div>';
  }
  return html + '</div></section>';
};
const renderCorePoints = (o: DeepReadOutput): string => {
  if (o.corePoints === null || o.corePoints.length === 0) return '';
  let html: string = '<section><h2 class="sec">关键判断</h2>';
  for (const point of o.corePoints) {
    html += '<div class="point"><h3>' + inlineMarkdown(point.point) + citations(point.sources, o) + '</h3>'
      + (point.supporting !== null ? md(point.supporting) : '') + image(point.imageUrl, point.imageCaption) + '</div>';
  }
  return html + '</section>';
};
const renderAnalysis = (o: DeepReadOutput): string => {
  const a = o.analysis;
  let html: string = '';
  if (a.coreDispute !== null && a.coreDispute.trim().length > 0) html += `<div class="dispute">${md(a.coreDispute)}</div>`;
  for (const p of a.perspectives) {
    html += `<div class="persp"><div class="holder">${esc(p.holder ?? '')}</div>`
      + ((p.interest ?? '').trim().length > 0 ? `<p class="interest">诉求：${inlineMarkdown(p.interest ?? '')}</p>` : '')
      + md(p.viewpoint) + citations(p.sources, o)
      + ((p.quote ?? '').trim().length > 0 ? `<blockquote>${esc(p.quote ?? '')}`
        + ((p.quoteBy ?? '').trim().length > 0 ? `<span class="by">—— ${esc(p.quoteBy ?? '')}</span>` : '') + '</blockquote>' : '')
      + '</div>';
  }
  for (const q of a.quotes) html += `<blockquote>${md(q.text)}<span class="by">${esc(q.attribution ?? '')}</span></blockquote>`;
  return html.length > 0 ? '<section><h2 class="sec">各方立场</h2>' + html + '</section>' : '';
};
const renderLinks = (heading: string, links: ReadingLink[]): string => {
  let html: string = '';
  for (const link of links) {
    const url: string = safeDeepReadLink(link.url);
    const meta: string = [link.source ?? '', link.publishedAt ?? ''].filter((s: string): boolean => s.length > 0).join(' · ');
    const label: string = esc(link.title.length > 0 ? link.title : link.url);
    html += url.length > 0 ? `<a href="${esc(url)}">${label}<span class="src">${esc(meta)}</span></a>`
      : `<div>${label}<span class="src">${esc(meta)}</span></div>`;
  }
  return html.length > 0 ? '<section>' + (heading.length > 0 ? `<h2 class="sec">${esc(heading)}</h2>` : '')
    + `<div class="reading">${html}</div></section>` : '';
};
const renderOutlook = (o: DeepReadOutput): string => {
  let html: string = '';
  const impacts = (o.impacts ?? []).filter((item): boolean => item.effect.trim().length > 0);
  if (impacts.length > 0) {
    html += '<ul class="impacts">';
    for (const item of impacts) {
      const horizon: string = item.horizon === 'short' ? '短期' : item.horizon === 'long' ? '长期' : '';
      html += `<li><p class="impact-target">${esc(item.target)}`
        + (horizon.length > 0 ? `<small>${horizon}</small>` : '') + '</p>' + md(item.effect) + '</li>';
    }
    html += '</ul>';
  }
  if (o.analysis.implications !== null && o.analysis.implications.trim().length > 0) {
    html += `<div class="implication">${md(o.analysis.implications)}</div>`;
  }
  const watch: string[] = (o.watch ?? []).map((text: string): string => text.trim()).filter((text: string): boolean => text.length > 0);
  if (watch.length > 0) html += '<div class="watch"><div class="holder">接下来关注</div><ul>'
    + watch.map((text: string): string => `<li>${inlineMarkdown(text)}</li>`).join('') + '</ul></div>';
  return html.length > 0 ? '<section><h2 class="sec">影响与走向</h2>' + html + '</section>' : '';
};
const renderUncertainties = (o: DeepReadOutput): string => {
  let html: string = '';
  for (const item of o.uncertainties ?? []) {
    const claim: string = item instanceof Object ? item.claim : item;
    const status: string = item instanceof Object ? item.status : '';
    if (claim.trim().length === 0) continue;
    const label: string = status === 'single_source' ? '单一来源' : status === 'conflicting' ? '来源矛盾'
      : status === 'pending_official' ? '待官方确认' : '';
    html += '<li>' + (label.length > 0 ? `<span class="claim-status">${label}</span>` : '') + inlineMarkdown(claim.trim()) + '</li>';
  }
  return html.length > 0 ? '<section class="uncertain"><h2 class="sec">待核实</h2><ul>' + html + '</ul></section>' : '';
};
const renderSources = (o: DeepReadOutput): string => {
  const sources: ReadingLink[] = o.sources ?? [];
  if (sources.length === 0) return renderLinks('参考来源', o.references);
  let html: string = '';
  const cited: Set<number> = new Set<number>();
  for (const point of o.corePoints ?? []) for (const id of point.sources ?? []) cited.add(id);
  for (const party of o.analysis.perspectives) for (const id of party.sources ?? []) cited.add(id);
  const seen: Set<string> = new Set<string>();
  for (let index: number = 0; index < sources.length; index++) {
    const link: ReadingLink = sources[index];
    const number: number = index + 1;
    const url: string = safeDeepReadLink(link.url);
    seen.add(link.url.length > 0 ? link.url : link.title);
    const label: string = esc(link.title.length > 0 ? link.title : link.source ?? `来源 ${number}`);
    const meta: string = [link.source ?? '', link.publishedAt ?? '', cited.has(number) ? '本文引用' : '']
      .filter((text: string): boolean => text.length > 0).join(' · ');
    const inner: string = `<span class="src-no">[${number}]</span>${label}<span class="src">${esc(meta)}</span>`;
    html += url.length > 0 ? `<a id="dr-source-${number}" href="${esc(url)}">${inner}</a>`
      : `<div id="dr-source-${number}">${inner}</div>`;
  }
  // Mixed old/new outputs retain legacy reading material without changing generation citation numbers.
  const extra: ReadingLink[] = [];
  for (const link of o.references.concat(o.extendedReading)) {
    const key: string = link.url.length > 0 ? link.url : link.title;
    if (seen.has(key)) continue;
    seen.add(key);
    extra.push(link);
  }
  if (extra.length > 0) html += '<div class="legacy-sources"><div class="holder">补充来源</div>' + renderLinks('', extra) + '</div>';
  return '<section><h2 class="sec">来源</h2><div class="reading">' + html + '</div></section>';
};
const renderDiagram = (d: DeepReadDiagram | null): string => {
  if (d === null) return '';
  let html: string = `<section><h2 class="sec">${esc(d.title.length > 0 ? d.title : '关系图解')}</h2>`;
  if (d.reason !== null) html += md(d.reason);
  for (let i: number = 0; i < d.nodes.length; i++) {
    const node = d.nodes[i];
    html += `<div class="diagram-node"><strong>${i + 1}. ${esc(node.label)}</strong>`
      + (node.group !== null ? `<div class="src">${esc(node.group)}</div>` : '')
      + (node.note !== null ? `<div class="diagram-note">${md(node.note)}</div>` : '') + '</div>';
  }
  for (const edge of d.edges) {
    const from = d.nodes.find((n): boolean => n.id === edge.from);
    const to = d.nodes.find((n): boolean => n.id === edge.to);
    if (from === undefined || to === undefined) continue;
    html += `<div class="diagram-edge">${esc(from.label)} → ${esc(to.label)}`
      + (edge.label !== null ? `: ${esc(edge.label)}` : '') + '</div>';
  }
  if (d.caption !== null) html += `<div class="cap">${esc(d.caption)}</div>`;
  return html + '</section>';
};
const renderGallery = (o: DeepReadOutput): string => {
  const used: Set<string> = new Set<string>();
  const hero: string | null = displayHeroImageUrl(o);
  if (hero !== null) used.add(hero);
  for (const ev of o.timeline ?? []) if (ev.imageUrl !== null) used.add(ev.imageUrl);
  for (const point of o.corePoints ?? []) if (point.imageUrl !== null) used.add(point.imageUrl);
  let html: string = '';
  for (const asset of o.imageAssets) {
    if (asset.confidence === IMAGE_CONFIDENCE.REJECT || safeDeepReadLink(asset.url).length === 0 || used.has(asset.url)) continue;
    used.add(asset.url);
    html += image(asset.url, asset.caption);
    if (asset.source !== null && safeDeepReadImage(asset.url).length > 0) html += `<div class="src">${esc(asset.source)}</div>`;
  }
  return html.length > 0 ? '<section><h2 class="sec">相关图片</h2>' + html + '</section>' : '';
};
interface ArticleChapter { key: string; html: string; }
const sectionOrder = (topic: string): string[] => {
  if (topic === 'opinion') return ['core_points', 'analysis', 'outlook', 'uncertainties', 'timeline', 'diagram', 'images', 'reading', 'references'];
  if (topic === 'product') return ['core_points', 'diagram', 'analysis', 'outlook', 'uncertainties', 'timeline', 'images', 'reading', 'references'];
  if (topic === 'person') return ['core_points', 'timeline', 'analysis', 'uncertainties', 'outlook', 'diagram', 'images', 'reading', 'references'];
  return ['core_points', 'timeline', 'diagram', 'analysis', 'uncertainties', 'outlook', 'images', 'reading', 'references'];
};
const chapters = (o: DeepReadOutput | null, layout: string = 'classic'): ArticleChapter[] => {
  if (o === null) return [];
  const article: ArticleChapter[] = [
    { key: 'topic', html: `<div class="meta">${esc(o.topicType)}</div>` },
    { key: 'bottom_line', html: (o.bottomLine ?? '').trim().length > 0
      ? `<p class="bottom-line">${inlineMarkdown(o.bottomLine ?? '')}</p>` : '' },
    { key: 'summary', html: renderOverview(o) },
    { key: 'hero', html: image(displayHeroImageUrl(o), displayHeroCaption(o), true) },
  ];
  const sections: ArticleChapter[] = [
    { key: 'timeline', html: renderNarrative(o) },
    { key: 'core_points', html: renderCorePoints(o) },
    { key: 'analysis', html: renderAnalysis(o) },
    { key: 'outlook', html: renderOutlook(o) },
    { key: 'uncertainties', html: renderUncertainties(o) },
    { key: 'diagram', html: renderDiagram(o.diagram) },
    { key: 'images', html: renderGallery(o) },
    { key: 'reading', html: (o.sources ?? []).length > 0 ? '' : renderLinks('扩展阅读', o.extendedReading) },
    { key: 'references', html: renderSources(o) },
  ];
  const order: string[] = layout === 'classic' ? sectionOrder(o.topicType)
    : readerSectionOrder(o.topicType, layout).map((key: string): string => key === 'core' ? 'core_points' : key).concat(['images', 'reading', 'references']);
  for (const key of order) {
    const chapter: ArticleChapter | undefined = sections.find((item: ArticleChapter): boolean => item.key === key);
    if (chapter !== undefined) article.push(chapter);
  }
  return article;
};
// Built-in articles share the same canvas and style. Only magazine articles can reorder chapters.
const appearanceStyle = (opts: DeepReadRenderOptions | undefined, applyLayout: boolean): string => {
  const canvas = readerCanvas(opts?.readerStyle ?? 'classic', opts?.dark ?? false);
  return `:root{--dr-bg:${canvas.background};--dr-ink:${canvas.foreground};--dr-muted:${canvas.muted};`
    + `--dr-surface:${canvas.surface};--dr-border:${canvas.border};--dr-sans:'HarmonyOS Sans SC',sans-serif}`
    + (applyLayout ? readerLayoutCss(opts?.readerLayout ?? 'classic') : '')
    + readerStyleCss(opts?.readerStyle ?? 'classic');
};
export const renderEditorialSlantHtml = (title: string, o: DeepReadOutput | null, opts?: DeepReadRenderOptions): string => {
  const article: ArticleChapter[] = chapters(o, opts?.readerLayout ?? 'classic');
  let body: string = '<header class="headline"><div class="kicker">DEEP READ · 深度阅读</div>'
    + `<h1 class="title">${esc(title)}</h1>`;
  for (const chapter of article) {
    if (chapter.key === 'topic' || chapter.key === 'bottom_line' || chapter.key === 'summary') body += chapter.html;
  }
  body += '</header>';
  for (const chapter of article) {
    if (chapter.key !== 'topic' && chapter.key !== 'bottom_line' && chapter.key !== 'summary') body += chapter.html;
  }
  return `<!DOCTYPE html><html><head>${head(opts)}<style>${appearanceStyle(opts, true)}</style></head><body>${body}</body></html>`;
};

export interface CustomTemplateDef { id: string; name: string; description?: string; html: string; createdAt: number; updatedAt?: number; }
const CUSTOM_TEMPLATES_KEY: string = 'deepread_custom_templates';
export const MAX_TEMPLATE_BYTES: number = 96 * 1024;
interface CustomTemplateDoc { id?: string; name?: string; description?: string; html?: string; createdAt?: number; updatedAt?: number; }
export const loadCustomTemplates = async (storage: Storage): Promise<CustomTemplateDef[]> => {
  const raw: string = await storage.get<string>(CUSTOM_TEMPLATES_KEY, '[]');
  const docs = JSON.parse(raw) as CustomTemplateDoc[];
  if (!Array.isArray(docs)) throw new Error('模板存储格式错误');
  const out: CustomTemplateDef[] = [];
  for (const d of docs) {
    if (d.id === undefined || d.name === undefined || d.html === undefined) throw new Error('模板存储缺少字段');
    out.push({ id: d.id, name: d.name, description: d.description ?? '', html: d.html,
      createdAt: d.createdAt ?? 0, updatedAt: d.updatedAt });
  }
  return out;
};
export const saveCustomTemplates = async (storage: Storage, list: CustomTemplateDef[]): Promise<void> => {
  for (const item of list) {
    const error: string = validateTemplateHtml(item.html);
    if (error.length > 0) throw new Error(error);
  }
  await storage.set<string>(CUSTOM_TEMPLATES_KEY, JSON.stringify(list));
};
const safeAttributeUrl = (attr: string, raw: string): boolean => {
  if (attr.toLowerCase() === 'href') return /^#[a-z0-9_-]*$/i.test(raw) || safeDeepReadLink(raw).length > 0;
  return safeDeepReadImage(raw).length > 0;
};
// Retained for old stored templates; new templates reject changes instead of silently stripping content.
export const sanitizeTemplateHtml = (raw: string): string => {
  let out: string = raw;
  let previous: string = '';
  while (previous !== out) {
    previous = out;
    out = out.replace(/<\s*(script|iframe|object|embed|form|input|button|textarea|select|link|base|audio|video|svg|math)\b[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gis, '');
    out = out.replace(/<\s*(script|iframe|object|embed|form|input|button|textarea|select|link|base|audio|video|svg|math)\b[^>]*\/?\s*>/gis, '');
    out = out.replace(/<\s*meta\b[^>]*>/gis, (tag: string): string =>
      !/\bhttp-equiv\b/i.test(tag) && (/\bcharset\s*=/i.test(tag) || /\bname\s*=\s*["']?viewport\b/i.test(tag)) ? tag : '');
    out = out.replace(/\s(on[a-z]+|srcdoc)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gis, '');
    out = out.replace(/\b(href|src)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gis,
      (match: string, attr: string, value: string): string => {
        const unquoted: string = value.startsWith('"') || value.startsWith("'") ? value.substring(1, value.length - 1) : value;
        return safeAttributeUrl(attr, unquoted.trim()) ? match : `${attr}=""`;
      });
    out = out.replace(/@import\b[^;]*(;|$)/gis, '');
    out = out.replace(/url\s*\([^)]*\)/gis, '');
    out = out.replace(/expression\s*\([^)]*\)/gis, '');
  }
  return out.trim();
};
export const hasTemplatePlaceholders = (raw: string): boolean =>
  /\{\{\s*(title|kicker|topic|bottom_line|summary|timeline|core_points|analysis|outlook|uncertainties|reading|references|sources|hero|images|diagram|content)\s*\}\}/.test(raw);
const utf8Bytes = (raw: string): number => {
  let bytes: number = 0;
  for (let i: number = 0; i < raw.length; i++) {
    const code: number = raw.charCodeAt(i);
    if (code < 0x80) bytes++;
    else if (code < 0x800) bytes += 2;
    else if (code >= 0xD800 && code <= 0xDBFF && i + 1 < raw.length && raw.charCodeAt(i + 1) >= 0xDC00 && raw.charCodeAt(i + 1) <= 0xDFFF) { bytes += 4; i++; }
    else bytes += 3;
  }
  return bytes;
};
export const validateTemplateHtml = (raw: string): string => {
  if (utf8Bytes(raw) > MAX_TEMPLATE_BYTES) return '模板超过 96 KiB UTF-8 大小限制';
  if (raw.trim().length === 0) return '模板内容为空';
  if (!hasTemplatePlaceholders(raw)) return '模板至少需要一个内容占位符,如 {{summary}} 或 {{content}}';
  if (sanitizeTemplateHtml(raw) !== raw.trim()) return '模板包含被禁元素、事件、外部 CSS 或不安全链接,请修改后重试';
  return '';
};
export const starterTemplateHtml = (): string =>
  '<style>article{max-width:760px;margin:auto}h1{font-size:calc(30px * var(--ds));line-height:1.3}</style>\n'
  + '<article><div class="kicker">{{kicker}}</div><h1>{{title}}</h1>\n{{content}}</article>';

export const captureDeepReadTemplate = async (
  storage: Storage, id: string, now: number = Date.now(),
): Promise<DeepReadTemplateSnapshot> => {
  const synthesis = DEEPREAD_SYNTHESIS_TEMPLATES.find((template): boolean => template.id === id);
  if (synthesis !== undefined) {
    return { id: id, name: synthesis.name, kind: 'synthesis', html: null, capturedAt: now };
  }
  if (id === DEEPREAD_TEMPLATE_NONE) {
    return { id: id, name: '默认排版', kind: 'native', html: null, capturedAt: now };
  }
  if (id === DEEPREAD_TEMPLATE_EDITORIAL_SLANT) {
    return { id: id, name: '杂志 · 立场', kind: 'editorial', html: null, capturedAt: now };
  }
  const templates: CustomTemplateDef[] = await loadCustomTemplates(storage);
  const template: CustomTemplateDef | undefined = templates.find((item: CustomTemplateDef): boolean => item.id === id);
  if (template === undefined) throw new Error('所选模板已不存在，请重新选择');
  const error: string = validateTemplateHtml(template.html);
  if (error.length > 0) throw new Error(`所选模板不可用：${error}`);
  return { id: template.id, name: template.name, kind: 'custom', html: template.html, capturedAt: now };
};

export interface DeepReadArticleTemplateResolution {
  template: DeepReadTemplateSnapshot;
  fallback: 'legacy' | 'missing' | null;
  message: string;
}
export const resolveDeepReadArticleTemplate = (output: DeepReadOutput | null): DeepReadArticleTemplateResolution => {
  const snapshot: DeepReadTemplateSnapshot | undefined = output?.templateSnapshot;
  if (snapshot !== undefined && (snapshot.kind === 'native' || snapshot.kind === 'editorial'
    || (snapshot.kind === 'synthesis' && DEEPREAD_SYNTHESIS_TEMPLATES.some((template): boolean => template.id === snapshot.id))
    || (snapshot.kind === 'custom' && snapshot.html !== null && validateTemplateHtml(snapshot.html).length === 0))) {
    return { template: snapshot, fallback: null, message: '' };
  }
  const id: string | undefined = output?.templateId;
  const synthesis = DEEPREAD_SYNTHESIS_TEMPLATES.find((template): boolean => template.id === id);
  if (snapshot === undefined && synthesis !== undefined) {
    return { template: { id: synthesis.id, name: synthesis.name, kind: 'synthesis', html: null, capturedAt: 0 },
      fallback: 'legacy', message: '' };
  }
  if (snapshot === undefined && id === DEEPREAD_TEMPLATE_EDITORIAL_SLANT) {
    return { template: { id: id, name: '杂志 · 立场', kind: 'editorial', html: null, capturedAt: 0 },
      fallback: 'legacy', message: '旧文章未保存模板快照，使用原内置模板。' };
  }
  const missing: boolean = snapshot !== undefined || (id !== undefined && id !== DEEPREAD_TEMPLATE_NONE);
  return { template: { id: DEEPREAD_TEMPLATE_NONE, name: '默认排版', kind: 'native', html: null, capturedAt: 0 },
    fallback: missing ? 'missing' : 'legacy',
    message: missing ? '文章模板快照缺失或不可用，已使用默认排版。' : '旧文章未保存模板归属，已使用默认排版。' };
};

export const renderCapturedDeepReadTemplateHtml = (
  title: string, output: DeepReadOutput | null, opts?: DeepReadRenderOptions,
): string => {
  if (output?.templateArticle !== undefined) {
    return `<!DOCTYPE html><html><head>${head(opts)}<style>${DEEPREAD_SYNTHESIS_CSS}\n${appearanceStyle(opts, false)}</style></head><body>${renderDeepReadSynthesisBody(output.templateArticle)}</body></html>`;
  }
  const template: DeepReadTemplateSnapshot = resolveDeepReadArticleTemplate(output).template;
  return template.kind === 'custom' && template.html !== null
    ? renderCustomTemplateHtml(template.html, title, output, opts) : renderEditorialSlantHtml(title, output, opts);
};
export const renderCustomTemplateHtml = (
  templateHtml: string, title: string, o: DeepReadOutput | null, opts?: DeepReadRenderOptions,
): string => {
  // Slots documented in comments do not consume visible article chapters.
  let out: string = sanitizeTemplateHtml(templateHtml).replace(/<!--[\s\S]*?(?:-->|$)/g, '');
  const article: ArticleChapter[] = chapters(o);
  const hasContent: boolean = /\{\{\s*content\s*\}\}/.test(out);
  let missing: string = '';
  let all: string = '';
  for (const chapter of article) all += chapter.html;
  // One replacement pass prevents generated article text from becoming another template slot.
  const slots: string[] = [];
  out = out.replace(/\{\{\s*(title|kicker|topic|bottom_line|summary|timeline|core_points|analysis|outlook|uncertainties|reading|references|sources|hero|images|diagram|content)\s*\}\}/g,
    (_match: string, key: string): string => {
      const chapterKey: string = key === 'sources' ? 'references' : key;
      slots.push(chapterKey);
      if (key === 'title') return esc(title);
      if (key === 'kicker') return 'DEEP READ · 深度阅读';
      if (key === 'content') return all;
      const chapter = article.find((c: ArticleChapter): boolean => c.key === chapterKey);
      return chapter !== undefined ? chapter.html : '';
    });
  if (!hasContent) for (const chapter of article) if (!slots.includes(chapter.key)) missing += chapter.html;
  if (!/<\s*html\b/i.test(out)) return `<!DOCTYPE html><html><head>${head(opts)}</head><body>${out}${missing}</body></html>`;
  if (/<\s*\/\s*head\s*>/i.test(out)) out = out.replace(/<\s*\/\s*head\s*>/i, head(opts) + '</head>');
  else out = out.replace(/<\s*html\b[^>]*>/i, (tag: string): string => tag + `<head>${head(opts)}</head>`);
  if (/<\s*\/\s*body\s*>/i.test(out)) out = out.replace(/<\s*\/\s*body\s*>/i, missing + '</body>');
  else if (/<\s*\/\s*html\s*>/i.test(out)) out = out.replace(/<\s*\/\s*html\s*>/i, missing + '</html>');
  else out += missing;
  return out;
};
export const buildSampleTemplateOutput = (): DeepReadOutput => {
  const o: DeepReadOutput = makeEmptyDeepReadOutput();
  o.bottomLine = '模板让关键判断、证据和后续影响各有清晰位置。';
  o.topicType = 'event';
  o.summary = '这是一段示例导语。**重要信息**会保留 Markdown 排版,各章节都可以在模板中阅读。';
  o.keyEntities = ['Amber', '深度阅读', '模板'];
  o.timeline = [{ date: '2026-09-30', event: '示例事件：模板系统上线', isHighlight: true,
    why: '读者可以在不同版式中核查同一组判断与资料。', imageUrl: null, imageCaption: null }];
  o.corePoints = [{ point: '版式应帮助读者理解判断并核查依据', supporting: '模板中的所有内容均可阅读。',
    sources: [1], imageUrl: null, imageCaption: null }];
  o.analysis = { coreDispute: '示例核心争议', perspectives: [{ holder: '读者', viewpoint: '文字和资料需要完整呈现。',
    interest: '减少查找关键信息和核对来源的成本。', quote: '模板是内容的骨架。', quoteBy: '示例引语', sources: [1] }],
    quotes: [], implications: null };
  o.impacts = [{ target: '读者', horizon: 'short', effect: '更快定位判断与对应证据。' }];
  o.watch = ['继续检查不同字体与屏幕尺寸下的阅读效果。'];
  o.uncertainties = [{ claim: '新排版对长文阅读效率的改善幅度。', status: 'single_source' }];
  o.diagram = { type: 'flow', title: '阅读流程', reason: null, nodes: [{ id: 'a', label: '资料', note: '保留来源', group: null },
    { id: 'b', label: '文章', note: null, group: null }], edges: [{ from: 'a', to: 'b', label: '整理' }], caption: '示例关系图解' };
  o.sources = [{ title: '示例参考资料', url: 'https://example.com/source', source: 'example.com', publishedAt: '2026-09-30' }];
  return o;
};

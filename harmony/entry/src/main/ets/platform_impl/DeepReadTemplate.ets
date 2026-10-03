// DeepRead template rendering: all article chapters share one safe Markdown renderer.
import type { DeepReadOutput, DeepReadTemplateSnapshot, Storage } from '@amber/deepread-domain';
import {
  makeEmptyDeepReadOutput, ReadingLink, DeepReadDiagram, IMAGE_CONFIDENCE,
  displayHeroImageUrl, displayHeroCaption,
} from '@amber/deepread-domain';
import {
  deepReadMarkdownHtml, escapeDeepReadHtml, safeDeepReadLink, safeDeepReadImage,
} from './DeepReadMarkdownHtml.ets';

export const DEEPREAD_TEMPLATE_NONE: string = 'none';
export const DEEPREAD_TEMPLATE_EDITORIAL_SLANT: string = 'editorial_slant';
export interface DeepReadTemplateDef { id: string; name: string; description: string; }
export const BUILTIN_TEMPLATES: DeepReadTemplateDef[] = [
  { id: DEEPREAD_TEMPLATE_NONE, name: '默认排版', description: '原生列表排版' },
  { id: DEEPREAD_TEMPLATE_EDITORIAL_SLANT, name: '杂志 · 立场', description: '衬线标题与完整章节(WebView 渲染)' },
];
export const isTemplateSelectable = (id: string): boolean => id === DEEPREAD_TEMPLATE_EDITORIAL_SLANT;

export interface DeepReadRenderOptions {
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
h1.title { font-size:calc(30px * var(--ds)); margin:0 0 10px; }
h2.sec { font-size:calc(20px * var(--ds)); margin:28px 0 12px; }
h2.sec::before { content:''; display:inline-block; width:22px; height:3px;
  background:var(--dr-accent); margin-right:10px; vertical-align:middle; }
p { margin:0 0 14px; } a { color:var(--dr-accent); }
.kicker,.date,.holder { font-size:calc(12px * var(--ds)); font-weight:600; }
.kicker,.holder { color:var(--dr-accent); }
.date { color:var(--dr-muted); }
.kicker { letter-spacing:2px; margin-bottom:8px; }
.meta,.cap,.by,.src { color:var(--dr-muted); font-size:calc(12px * var(--ds)); }
.meta { margin-bottom:18px; } .cap { margin-top:6px; } .by { display:block; margin-top:8px; }
.summary { font-size:calc(17px * var(--ds)); border-left:3px solid var(--dr-accent); padding-left:14px; }
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
  if (o.keyEntities.length > 0) {
    html += '<ul class="entities">';
    for (const entity of o.keyEntities) html += `<li>${esc(entity)}</li>`;
    html += '</ul>';
  }
  return html;
};
const renderNarrative = (o: DeepReadOutput): string => {
  if (o.timeline === null || o.timeline.length === 0) return '';
  let html: string = '<section><h2 class="sec">时间线</h2><div class="timeline">';
  for (const ev of o.timeline) {
    html += `<div class="ev"><div class="date">${esc(ev.date)}${ev.isHighlight ? ' · 关键节点' : ''}</div>`
      + md(ev.event) + image(ev.imageUrl, ev.imageCaption) + '</div>';
  }
  return html + '</div></section>';
};
const renderCorePoints = (o: DeepReadOutput): string => {
  if (o.corePoints === null || o.corePoints.length === 0) return '';
  let html: string = '<section><h2 class="sec">核心要点</h2>';
  for (const point of o.corePoints) {
    html += '<div class="point">' + md(point.point)
      + (point.supporting !== null ? md(point.supporting) : '') + image(point.imageUrl, point.imageCaption) + '</div>';
  }
  return html + '</section>';
};
const renderAnalysis = (o: DeepReadOutput): string => {
  const a = o.analysis;
  let html: string = '';
  if (a.coreDispute !== null && a.coreDispute.trim().length > 0) html += `<div class="dispute">${md(a.coreDispute)}</div>`;
  for (const p of a.perspectives) {
    html += `<div class="persp"><div class="holder">${esc(p.holder ?? '')}</div>${md(p.viewpoint)}</div>`;
  }
  for (const q of a.quotes) html += `<blockquote>${md(q.text)}<span class="by">${esc(q.attribution ?? '')}</span></blockquote>`;
  if (a.implications !== null && a.implications.trim().length > 0) html += `<div class="implication">${md(a.implications)}</div>`;
  return html.length > 0 ? '<section><h2 class="sec">深度分析</h2>' + html + '</section>' : '';
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
  return html.length > 0 ? `<section><h2 class="sec">${heading}</h2><div class="reading">${html}</div></section>` : '';
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
const chapters = (o: DeepReadOutput | null): ArticleChapter[] => o === null ? [] : [
  { key: 'topic', html: `<div class="meta">${esc(o.topicType)}</div>` },
  { key: 'hero', html: image(displayHeroImageUrl(o), displayHeroCaption(o), true) },
  { key: 'summary', html: renderOverview(o) },
  { key: 'timeline', html: renderNarrative(o) },
  { key: 'core_points', html: renderCorePoints(o) },
  { key: 'analysis', html: renderAnalysis(o) },
  { key: 'diagram', html: renderDiagram(o.diagram) },
  { key: 'images', html: renderGallery(o) },
  { key: 'reading', html: renderLinks('扩展阅读', o.extendedReading) },
  { key: 'references', html: renderLinks('参考来源', o.references) },
];
export const renderEditorialSlantHtml = (title: string, o: DeepReadOutput | null, opts?: DeepReadRenderOptions): string => {
  let body: string = '<div class="kicker">DEEP READ · 深度阅读</div>' + `<h1 class="title">${esc(title)}</h1>`;
  for (const chapter of chapters(o)) body += chapter.html;
  return `<!DOCTYPE html><html><head>${head(opts)}</head><body>${body}</body></html>`;
};

export interface CustomTemplateDef { id: string; name: string; html: string; createdAt: number; updatedAt?: number; }
const CUSTOM_TEMPLATES_KEY: string = 'deepread_custom_templates';
export const MAX_TEMPLATE_BYTES: number = 96 * 1024;
interface CustomTemplateDoc { id?: string; name?: string; html?: string; createdAt?: number; updatedAt?: number; }
export const loadCustomTemplates = async (storage: Storage): Promise<CustomTemplateDef[]> => {
  const raw: string = await storage.get<string>(CUSTOM_TEMPLATES_KEY, '[]');
  const docs = JSON.parse(raw) as CustomTemplateDoc[];
  if (!Array.isArray(docs)) throw new Error('模板存储格式错误');
  const out: CustomTemplateDef[] = [];
  for (const d of docs) {
    if (d.id === undefined || d.name === undefined || d.html === undefined) throw new Error('模板存储缺少字段');
    out.push({ id: d.id, name: d.name, html: d.html, createdAt: d.createdAt ?? 0, updatedAt: d.updatedAt });
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
  /\{\{\s*(title|kicker|topic|summary|timeline|core_points|analysis|reading|references|hero|images|diagram|content)\s*\}\}/.test(raw);
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
    || (snapshot.kind === 'custom' && snapshot.html !== null && validateTemplateHtml(snapshot.html).length === 0))) {
    return { template: snapshot, fallback: null, message: '' };
  }
  const id: string | undefined = output?.templateId;
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
  out = out.replace(/\{\{\s*(title|kicker|topic|summary|timeline|core_points|analysis|reading|references|hero|images|diagram|content)\s*\}\}/g,
    (_match: string, key: string): string => {
      slots.push(key);
      if (key === 'title') return esc(title);
      if (key === 'kicker') return 'DEEP READ · 深度阅读';
      if (key === 'content') return all;
      const chapter = article.find((c: ArticleChapter): boolean => c.key === key);
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
  o.summary = '这是一段示例导语。**重要信息**会保留 Markdown 排版,各章节都可以在模板中阅读。';
  o.keyEntities = ['Amber', '深度阅读', '模板'];
  o.timeline = [{ date: '2026-09-30', event: '示例事件:模板系统上线', isHighlight: true, imageUrl: null, imageCaption: null }];
  o.corePoints = [{ point: '核心要点', supporting: '模板中的所有内容均可阅读。', imageUrl: null, imageCaption: null }];
  o.analysis = { coreDispute: '示例核心争议', perspectives: [{ holder: '读者', viewpoint: '文字和资料需要完整呈现。' }],
    quotes: [{ text: '模板是内容的骨架。', attribution: '示例引语' }], implications: '版式选择不应改变内容。' };
  o.diagram = { type: 'flow', title: '阅读流程', reason: null, nodes: [{ id: 'a', label: '资料', note: '保留来源', group: null },
    { id: 'b', label: '文章', note: null, group: null }], edges: [{ from: 'a', to: 'b', label: '整理' }], caption: '示例关系图解' };
  o.extendedReading = [{ title: '示例延伸阅读', url: 'https://example.com', source: 'example.com', publishedAt: null }];
  o.references = [{ title: '示例参考来源', url: 'https://example.com/source', source: 'example.com', publishedAt: '2026-09-30' }];
  return o;
};

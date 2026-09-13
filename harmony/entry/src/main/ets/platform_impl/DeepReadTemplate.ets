// DeepReadTemplate — 模板子系统(port Android feature/board/hotlist/deepread/template)
//
// DeepReadTemplateModels.kt(29 行)/ DeepReadTemplateRenderer.kt(683 行 editorial_slant)
// 的鸿蒙等价:editorial_slant 内置模板 = DeepReadOutput → HTML(杂志风)。
// 安全区:图片/链接 URL 白名单(http/https/data:image),文本全部 HTML 转义。
// 自定义模板(Agent 生成/工作台)后续梯队;当前提供内置模板选择与渲染。

import type { DeepReadOutput } from '@amber/deepread-domain';

export const DEEPREAD_TEMPLATE_NONE: string = 'none';
export const DEEPREAD_TEMPLATE_EDITORIAL_SLANT: string = 'editorial_slant';

export interface DeepReadTemplateDef {
  id: string;
  name: string;
  description: string;
}

export const BUILTIN_TEMPLATES: DeepReadTemplateDef[] = [
  { id: DEEPREAD_TEMPLATE_NONE, name: '默认排版', description: '原生列表排版' },
  { id: DEEPREAD_TEMPLATE_EDITORIAL_SLANT, name: '杂志 · 立场', description: '衬线标题+分栏杂志风(WebView 渲染)' },
];

export const isTemplateSelectable = (id: string): boolean =>
  id === DEEPREAD_TEMPLATE_EDITORIAL_SLANT;

// ---- HTML 工具 ----

const escapeHtml = (raw: string): string => raw
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

// URL 白名单:http/https(图片额外允许 data:image)
const safeLinkUrl = (raw: string): string =>
  /^https?:\/\//i.test(raw.trim()) ? raw.trim() : '';
const safeImageUrl = (raw: string): string =>
  (/^https?:\/\//i.test(raw.trim()) || /^data:image\//i.test(raw.trim())) ? raw.trim() : '';

// ---- editorial_slant 渲染 ----

// 字号全部乘 var(--ds)(默认 1 → 与原样式逐字等价);fontScale 通过注入的
// :root{--ds} 生效,纯 CSS、无需 JS(F29)。class ds-serif 切换正文字体族。
const CSS_EDITORIAL: string = `
  :root { --ds: 1; }
  body { margin:0; padding:24px 20px 40px; background:#F7F5EF; color:#1B1A17;
         font-family:-apple-system,'HarmonyOS Sans SC',sans-serif; line-height:1.7; }
  body.ds-serif { font-family:Georgia,'Noto Serif SC',serif; }
  .kicker { font-size:calc(12px * var(--ds)); letter-spacing:2px; color:#B8623A; font-weight:600;
            text-transform:uppercase; margin-bottom:8px; }
  h1.title { font-size:calc(30px * var(--ds)); line-height:1.25; margin:0 0 6px; font-weight:700;
             font-family:Georgia,'Noto Serif SC',serif; }
  .meta { font-size:calc(12px * var(--ds)); color:#8F8B80; margin-bottom:18px; }
  .hero { width:100%; border-radius:14px; margin:0 0 20px; display:block; }
  .hero-cap { font-size:calc(11px * var(--ds)); color:#8F8B80; margin:-14px 0 20px; }
  h2.sec { font-size:calc(20px * var(--ds)); font-weight:700; margin:30px 0 10px;
           font-family:Georgia,'Noto Serif SC',serif; }
  h2.sec::before { content:''; display:inline-block; width:22px; height:3px;
                   background:#B8623A; margin-right:10px; vertical-align:middle; }
  p { font-size:calc(15.5px * var(--ds)); margin:0 0 14px; }
  .summary { font-size:calc(17px * var(--ds)); line-height:1.75; font-weight:500; color:#33312B;
             border-left:3px solid #B8623A; padding-left:14px; margin:0 0 22px; }
  ul.entities { list-style:none; padding:0; margin:0 0 20px; }
  ul.entities li { display:inline-block; background:#fff; border:1px solid #E4E0D6;
                   border-radius:14px; padding:4px 12px; font-size:calc(13px * var(--ds)); margin:0 6px 8px 0; }
  .timeline { border-left:2px solid #E4E0D6; margin:0 0 20px; padding-left:18px; }
  .timeline .ev { margin-bottom:14px; }
  .timeline .d { font-size:calc(12px * var(--ds)); color:#B8623A; font-weight:600; }
  .timeline .e { font-size:calc(14.5px * var(--ds)); color:#33312B; }
  .points li { font-size:calc(14.5px * var(--ds)); margin-bottom:8px; }
  .dispute { background:#fff; border:1px solid #E4E0D6; border-radius:12px;
             padding:14px 16px; font-size:calc(14.5px * var(--ds)); margin-bottom:18px; }
  .persp { border-top:1px solid #E4E0D6; padding:12px 0; }
  .persp .holder { font-size:calc(12px * var(--ds)); color:#B8623A; font-weight:600; }
  .persp .view { font-size:calc(14.5px * var(--ds)); color:#33312B; }
  blockquote { margin:14px 0; padding:10px 16px; background:#fff; border-radius:10px;
               font-size:calc(14.5px * var(--ds)); font-style:italic; color:#33312B; }
  blockquote .by { display:block; font-style:normal; font-size:calc(12px * var(--ds)); color:#8F8B80; margin-top:6px; }
  .reading a { display:block; font-size:calc(14px * var(--ds)); color:#1B1A17; text-decoration:none;
               padding:8px 0; border-bottom:1px solid #E4E0D6; }
  .reading a .src { color:#8F8B80; font-size:calc(12px * var(--ds)); }
  .implication { font-size:calc(14.5px * var(--ds)); color:#33312B; background:#FFF5F0; border-radius:10px;
                 padding:12px 14px; margin-bottom:12px; }
  img.inline { width:100%; border-radius:12px; margin:10px 0 6px; display:block; }
  .cap { font-size:calc(11px * var(--ds)); color:#8F8B80; margin-bottom:14px; }
`;

// 显示偏好(字号倍率/字体模式)。fontScale 经安全夹取 + 定点格式化,只作为
// 数值写进 :root{--ds};字体模式只切预定义 class。两者均无字符串注入面。
export interface EditorialSlantOptions {
  fontScale?: number;
  fontSerif?: boolean;
}

const clampScale = (raw: number | undefined): number => {
  if (raw === undefined || !Number.isFinite(raw)) return 1;
  if (raw < 0.8) return 0.8;
  if (raw > 1.3) return 1.3;
  return raw;
};

const formatScale = (scale: number): string => scale.toFixed(3);

const esc = escapeHtml;

const renderOverview = (o: DeepReadOutput): string => {
  let html: string = '';
  if (o.summary.trim().length > 0) {
    html += `<p class="summary">${esc(o.summary)}</p>`;
  }
  if (o.keyEntities.length > 0) {
    html += '<ul class="entities">';
    for (const e of o.keyEntities.slice(0, 12)) html += `<li>${esc(e)}</li>`;
    html += '</ul>';
  }
  return html;
};

const renderNarrative = (o: DeepReadOutput): string => {
  if (o.timeline === null || o.timeline.length === 0) return '';
  let html: string = '<h2 class="sec">时间线</h2><div class="timeline">';
  for (const ev of o.timeline.slice(0, 8)) {
    html += `<div class="ev"><span class="d">${esc(ev.date)}</span><br/>`
      + `<span class="e">${esc(ev.event)}</span></div>`;
  }
  html += '</div>';
  return html;
};

const renderAnalysis = (o: DeepReadOutput): string => {
  let html: string = '';
  const hasAny: boolean = o.analysis.coreDispute !== null || o.analysis.perspectives.length > 0
    || o.analysis.implications !== null || o.analysis.quotes.length > 0;
  if (!hasAny) return '';
  html += '<h2 class="sec">深度分析</h2>';
  if (o.analysis.coreDispute !== null) {
    html += `<div class="dispute">${esc(o.analysis.coreDispute)}</div>`;
  }
  for (const p of o.analysis.perspectives.slice(0, 8)) {
    const holder: string = p.holder !== null ? p.holder : '';
    html += `<div class="persp"><span class="holder">${esc(holder)}</span>`
      + `<div class="view">${esc(p.viewpoint)}</div></div>`;
  }
  for (const q of o.analysis.quotes.slice(0, 6)) {
    html += '<blockquote>“' + esc(q.text) + '”';
    if (q.attribution !== null && q.attribution.length > 0) {
      html += `<span class="by">— ${esc(q.attribution)}</span>`;
    }
    html += '</blockquote>';
  }
  if (o.analysis.implications !== null) {
    html += `<div class="implication">${esc(o.analysis.implications)}</div>`;
  }
  return html;
};

const renderExtendedReading = (o: DeepReadOutput): string => {
  if (o.extendedReading.length === 0) return '';
  let html: string = '<h2 class="sec">扩展阅读</h2><div class="reading">';
  for (const l of o.extendedReading.slice(0, 10)) {
    const url: string = safeLinkUrl(l.url);
    if (url.length === 0) continue;
    const src: string = l.source !== null ? l.source : '';
    html += `<a href="${esc(url)}" target="_blank">${esc(l.title)}`
      + (src.length > 0 ? ` <span class="src">${esc(src)}</span>` : '') + '</a>';
  }
  html += '</div>';
  return html;
};

// editorial_slant 完整 HTML(生成失败/空输出 → 仅标题壳,fallback 由调用方判定)
// opts:显示偏好(fontScale → --ds;fontSerif → body.ds-serif)。无 JS,F29。
export const renderEditorialSlantHtml = (
  title: string,
  o: DeepReadOutput | null,
  opts?: EditorialSlantOptions,
): string => {
  const safeTitle: string = esc(title);
  let body: string = '';
  if (o !== null) {
    body += renderOverview(o);
    body += renderNarrative(o);
    body += renderAnalysis(o);
    body += renderExtendedReading(o);
  }
  const scale: string = formatScale(clampScale(opts?.fontScale));
  const bodyClass: string = opts !== undefined && opts.fontSerif === true ? ' class="ds-serif"' : '';
  return `<!DOCTYPE html><html><head><meta charset="utf-8" />`
    + `<meta name="viewport" content="width=device-width, initial-scale=1" />`
    + `<style>${CSS_EDITORIAL}:root{--ds:${scale}}</style></head><body${bodyClass}>`
    + `<div class="kicker">DEEP READ · 深度阅读</div>`
    + `<h1 class="title">${safeTitle}</h1>`
    + body
    + `</body></html>`;
};

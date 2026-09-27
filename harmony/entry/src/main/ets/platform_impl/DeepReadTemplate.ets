// DeepReadTemplate — 模板子系统(port Android feature/board/hotlist/deepread/template)
//
// DeepReadTemplateModels.kt(29 行)/ DeepReadTemplateRenderer.kt(683 行 editorial_slant)
// 的鸿蒙等价:editorial_slant 内置模板 = DeepReadOutput → HTML(杂志风)。
// 安全区:图片/链接 URL 白名单(http/https/data:image),文本全部 HTML 转义。
// 自定义模板(Agent 生成/工作台,D1-1):见文件下方「自定义模板」节;
//   当前提供内置模板选择与渲染 + 自定义模板存储/安检/渲染。

import type { DeepReadOutput, Storage } from '@amber/deepread-domain';
import {
  makeEmptyDeepReadOutput, TimelineEvent, Perspective, Quote, ReadingLink,
} from '@amber/deepread-domain';

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

// ================= 自定义模板(D1-1;双端 🔵「自定义 HTML 模板(严格安检)」) =================
//
// 模型:用户 HTML 壳 + 占位符 {{title}}/{{kicker}}/{{summary}}/{{timeline}}/{{analysis}}/{{reading}},
//   占位区复用上方内置渲染器(文本全转义、URL 白名单),用户静态部分过严格安检。
// 安检(严格,宁可误杀):整元素剔除 script/iframe/object/embed/form/表单控件/link/meta/base/
//   audio/video/svg/math;剥离 on* 事件属性;封 javascript:/vbscript: 协议;
//   CSS 剥离 @import / url() / expression()(外部资源与旧式注入全断)。
// 存储:KV deepread_custom_templates(JSON 数组);选中模板 id 仍走 deepread_template_id。

export interface CustomTemplateDef {
  id: string;        // custom_{timestamp}
  name: string;
  html: string;
  createdAt: number;
}

const CUSTOM_TEMPLATES_KEY: string = 'deepread_custom_templates';

interface CustomTemplateDoc {
  id?: string;
  name?: string;
  html?: string;
  createdAt?: number;
}

export const loadCustomTemplates = async (storage: Storage): Promise<CustomTemplateDef[]> => {
  const raw: string = await storage.get<string>(CUSTOM_TEMPLATES_KEY, '[]');
  try {
    const arr = JSON.parse(raw) as CustomTemplateDoc[];
    const out: CustomTemplateDef[] = [];
    for (const d of arr) {
      if (d.id === undefined || d.name === undefined || d.html === undefined) continue;
      out.push({ id: d.id, name: d.name, html: d.html, createdAt: d.createdAt ?? 0 });
    }
    return out;
  } catch (_e) {
    return [];
  }
};

export const saveCustomTemplates = async (
  storage: Storage, list: CustomTemplateDef[],
): Promise<void> => {
  await storage.set<string>(CUSTOM_TEMPLATES_KEY, JSON.stringify(list));
};

export const sanitizeTemplateHtml = (raw: string): string => {
  // fixpoint 循环:单遍替换可被嵌套重组绕过(<scr<script>ipt> → <script>),
  //   反复执行直到输出不再变化(每次迭代必然变短或收敛,不会死循环)
  let s: string = raw;
  let prev: string = '';
  while (prev !== s) {
    prev = s;
    s = s.replace(/<\s*(script|iframe|object|embed|form|input|button|textarea|select|link|meta|base|audio|video|svg|math)\b[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gis, '');
    s = s.replace(/<\s*(script|iframe|object|embed|form|input|button|textarea|select|link|meta|base|audio|video|svg|math)\b[^>]*\/?\s*>/gis, '');
    s = s.replace(/\son[a-zA-Z]+\s*=\s*"[^"]*"/g, '');
    s = s.replace(/\son[a-zA-Z]+\s*=\s*'[^']*'/g, '');
    s = s.replace(/\son[a-zA-Z]+\s*=\s*[^\s>]+/g, '');
    // href/src scheme 白名单(javascript:/vbscript: 黑名单可被 HTML 实体绕过;
    //   仅放行 http(s)/data:image/页内锚)
    s = s.replace(/(href|src)\s*=\s*"([^"]*)"/gis, (_m: string, attr: string, val: string): string =>
      /^(https?:|data:image|#)/i.test(val.trim()) ? `${attr}="${val}"` : `${attr}=""`);
    s = s.replace(/(href|src)\s*=\s*'([^']*)'/gis, (_m: string, attr: string, val: string): string =>
      /^(https?:|data:image|#)/i.test(val.trim()) ? `${attr}='${val}'` : `${attr}=''`);
    s = s.replace(/@import\b[^;]*/gis, '');
    s = s.replace(/url\s*\([^)]*\)/gis, '');
    s = s.replace(/expression\s*\([^)]*\)/gis, '');
  }
  return s.trim();
};

export const hasTemplatePlaceholders = (raw: string): boolean =>
  /\{\{\s*(title|kicker|summary|timeline|analysis|reading)\s*\}\}/.test(raw);

// 自定义模板渲染:先安检,再占位符替换(章节先于 title/kicker — 标题里若巧合
//   含 {{analysis}} 字样不会被二次替换污染);无 <html> 骨架则补最小骨架。
//   output 为 null(生成中/失败)时内容占位区清空,仅留用户静态壳与标题。
export const renderCustomTemplateHtml = (
  templateHtml: string, title: string, o: DeepReadOutput | null,
): string => {
  let out: string = sanitizeTemplateHtml(templateHtml);
  if (o !== null) {
    out = out.replace(/\{\{\s*summary\s*\}\}/g, renderOverview(o));
    out = out.replace(/\{\{\s*timeline\s*\}\}/g, renderNarrative(o));
    out = out.replace(/\{\{\s*analysis\s*\}\}/g, renderAnalysis(o));
    out = out.replace(/\{\{\s*reading\s*\}\}/g, renderExtendedReading(o));
  } else {
    out = out.replace(/\{\{\s*(summary|timeline|analysis|reading)\s*\}\}/g, '');
  }
  out = out.replace(/\{\{\s*title\s*\}\}/g, esc(title));
  out = out.replace(/\{\{\s*kicker\s*\}\}/g, 'DEEP READ · 深度阅读');
  if (!/<\s*html/i.test(out)) {
    out = `<!DOCTYPE html><html><head><meta charset="utf-8" />`
      + `<meta name="viewport" content="width=device-width, initial-scale=1" />`
      + `</head><body>${out}</body></html>`;
  }
  return out;
};

// 工作台预览用示例输出(静态;复用 makeEmptyDeepReadOutput 保证字段完整)
export const buildSampleTemplateOutput = (): DeepReadOutput => {
  const o: DeepReadOutput = makeEmptyDeepReadOutput();
  o.summary = '这是一段示例导语,用于预览自定义模板的真实渲染效果。占位符会被替换为对应章节的安全 HTML。';
  o.keyEntities = ['示例实体', 'Amber', '深度阅读'];
  const tl: TimelineEvent[] = [
    { date: '2026-09-27', event: '示例事件:模板系统上线', isHighlight: false, imageUrl: null, imageCaption: null },
    { date: '2026-09-28', event: '示例事件:支持自定义 HTML 模板', isHighlight: true, imageUrl: null, imageCaption: null },
  ];
  o.timeline = tl;
  const perspectives: Perspective[] = [
    { holder: '支持者', viewpoint: '占位符足以覆盖常见版式,且没有注入面。' },
    { holder: '谨慎者', viewpoint: '必须严格安检,宁可少放能力。' },
  ];
  const quotes: Quote[] = [{ text: '模板是内容的骨架。', attribution: '示例引语' }];
  o.analysis = {
    coreDispute: '示例核心争议:自定义模板应该支持多大的自由度?',
    perspectives: perspectives,
    quotes: quotes,
    implications: '示例影响:自定义模板让深读版式脱离发版节奏。',
  };
  const links: ReadingLink[] = [
    { title: '示例延伸阅读', url: 'https://example.com', source: 'example.com', publishedAt: null },
  ];
  o.extendedReading = links;
  return o;
};

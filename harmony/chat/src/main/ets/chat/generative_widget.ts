// show-widget is a display protocol. Original message text remains the persisted source.
import type { JsonObject, JsonValue } from './json.ts';
import { renderWidgetSpec } from './generative_widget_specs.ts';

export interface GenerativeWidgetAction { id: string; label: string; instruction: string; }
export interface GenerativeWidget {
  title: string;
  renderer: string;
  html: string;
  complete: boolean;
  actions: GenerativeWidgetAction[];
  notice: string;
}
export interface GenerativeWidgetSegment {
  kind: 'text' | 'widget' | 'loading' | 'error';
  start: number;
  content: string;
  widget: GenerativeWidget | null;
}
const object = (value: JsonValue | undefined): JsonObject | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null;
const string = (value: JsonValue | undefined): string => typeof value === 'string' ? value : '';
const MAX_HTML = 120_000;

export const widgetActionPrompt = (title: string, instruction: string): string =>
  `关于“${title || '可视化组件'}”：${instruction}`;

const actions = (value: JsonValue | undefined): GenerativeWidgetAction[] => {
  const result: GenerativeWidgetAction[] = [];
  if (!Array.isArray(value)) return result;
  for (const item of value) {
    const obj = object(item);
    if (obj === null) continue;
    const label = string(obj.label).trim();
    const instruction = string(obj.instruction).trim();
    // Matches the existing mobile protocol's native prompt actions. No URLs/tools/bridge calls.
    if (label.length < 1 || label.length > 20 || instruction.length < 1 || instruction.length > 240 ||
      /<\/?system|ignore previous|system prompt|developer message|tool call|https?:\/\/|打开链接|执行工具/i.test(instruction)) continue;
    result.push({ id: string(obj.id).slice(0, 48) || label, label, instruction });
    if (result.length === 3) break;
  }
  return result;
};

export const sanitizeWidgetHtml = (html: string): string => {
  if (html.length > MAX_HTML) throw new Error('组件内容过大，无法显示');
  return html
    .replace(/<\s*(script|iframe|object|embed|form|foreignObject)\b[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi, '')
    .replace(/<\s*\/?(?:script|iframe|object|embed|form|foreignObject|meta|link|base)\b[^>]*>/gi, '')
    .replace(/\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
    .replace(/\s+(?:href|xlink:href|src|srcset|poster|background|action|srcdoc)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi,
      (all: string, quoted: string): string => {
        const value = quoted.replace(/^["']|["']$/g, '');
        return /^#[\w:-]+$/.test(value) || /^data:image\/(?:png|jpeg|jpg|gif|webp);base64,[a-z0-9+/=\s]+$/i.test(value) ? all : '';
      })
    .replace(/@import\s+[^;]+;?/gi, '')
    .replace(/url\(\s*(['"]?)(?!#[\w:-]+\1\s*\))[^)]*\)/gi, 'none')
    .replace(/position\s*:\s*(?:fixed|sticky|-webkit-sticky)/gi, 'position:relative');
};

// CSP, JS-disabled Web and file/network restrictions are the enforcement boundary;
// sanitizing preserves visual HTML/SVG and removes dead or misleading unsafe controls.
export const widgetDocument = (html: string, dark: boolean): string => {
  const safe = sanitizeWidgetHtml(html);
  const styles = safe.match(/<style\b[^>]*>[\s\S]*?<\/style\s*>/gi)?.join('\n') ?? '';
  const bodyMatch = /<body\b[^>]*>([\s\S]*?)<\/body\s*>/i.exec(safe);
  const body = (bodyMatch === null ? safe : bodyMatch[1])
    .replace(/<!doctype[^>]*>|<\/?(?:html|head|body)\b[^>]*>/gi, '');
  // Reserved markers belong to our structured chart labels, not arbitrary SVG.
  const chartLabelStyles = dark ? '.amber-chart-axis-label{fill:#D3D0C9}' +
    '.amber-chart-legend-label[fill="#2563eb"]{fill:#93B4FF}' +
    '.amber-chart-legend-label[fill="#16a34a"]{fill:#86DCA0}' +
    '.amber-chart-legend-label[fill="#ea580c"]{fill:#FDBA74}' +
    '.amber-chart-legend-label[fill="#9333ea"]{fill:#D8B4FE}' : '';
  return '<!doctype html><html><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'; img-src data:; base-uri \'none\'; form-action \'none\'">' +
    styles + '<style>html,body{margin:0;padding:8px;box-sizing:border-box;overflow-wrap:anywhere;' +
    `color:${dark ? '#ECE8DF' : '#25221D'};background:transparent;font:15px sans-serif}` +
    'svg{max-width:100%;height:auto}img{max-width:100%}details summary{cursor:pointer}' +
    chartLabelStyles +
    // Script-driven full_html decks retain every page in a static vertical preview.
    '#deck,.slides{height:auto!important;overflow:visible!important}' +
    '.slide{display:block!important;position:relative!important;opacity:1!important;transform:none!important;' +
    'height:auto!important;min-height:240px;visibility:visible!important;margin-bottom:16px}</style>' +
    '</head><body>' + body + '</body></html>';
};

const partialString = (json: string, key: string): string => {
  const match = new RegExp('"' + key + '"\\s*:\\s*"').exec(json);
  if (match === null) return '';
  let value = '';
  let cursor = match.index + match[0].length;
  while (cursor < json.length) {
    const ch = json[cursor++];
    if (ch === '"') break;
    if (ch !== '\\') { value += ch; continue; }
    if (cursor >= json.length) break;
    const escaped = json[cursor++];
    if (escaped === 'u') {
      const hex = json.slice(cursor, cursor + 4);
      if (!/^[0-9a-f]{4}$/i.test(hex)) break;
      value += String.fromCharCode(parseInt(hex, 16)); cursor += 4;
    } else {
      const pairs: Record<string, string> = { n: '\n', r: '\r', t: '\t', b: '\b', f: '\f', '"': '"', '\\': '\\', '/': '/' };
      value += pairs[escaped] ?? '';
    }
  }
  return value;
};

const parseWidget = (raw: string, complete: boolean): GenerativeWidget => {
  const parsed = object(JSON.parse(raw) as JsonValue);
  if (parsed === null) throw new Error('组件 JSON 需要是对象');
  const renderer = string(parsed.renderer).toLowerCase() || 'html';
  const spec = object(parsed.spec);
  const code = string(parsed.widget_code);
  let html = code;
  let notice = '';
  if (renderer === 'full_html' || renderer === 'guizang_html') {
    html = string(spec?.html);
    notice = '静态预览，脚本和外部资源不执行';
  } else if (renderer !== 'html' && renderer !== 'svg') {
    const rendered = renderWidgetSpec(renderer, parsed.spec ?? parsed);
    if (rendered !== null) {
      html = rendered;
      notice = renderer === 'vchart' ? '图表静态预览' : renderer === 'slides' ? '幻灯片静态内容预览' : '';
    }
    else if (html.length > 0) notice = `${renderer} 静态封面预览`;
    else throw new Error(`当前无法显示 ${renderer} 的此种结构，请使用 SVG 或静态 HTML`);
  }
  if (!/<\s*(?:svg|div|section|article|table|ul|ol|p|figure|main|aside|header|footer|html|details)\b/i.test(html)) {
    throw new Error('组件没有可显示的 HTML 或 SVG 内容');
  }
  sanitizeWidgetHtml(html);
  return { title: string(parsed.title).trim().slice(0, 100) || '可视化组件', renderer, html,
    complete, actions: complete ? actions(parsed.actions) : [], notice };
};

export const parseGenerativeWidgets = (content: string, streaming: boolean): GenerativeWidgetSegment[] => {
  const segments: GenerativeWidgetSegment[] = [];
  const opener = /^[\t ]*(`{3,}|~{3,})([^\r\n]*)(?:\r?\n|$)/gm;
  let textStart = 0;
  let match: RegExpExecArray | null;
  while ((match = opener.exec(content)) !== null) {
    const language = match[2].trim().split(/\s/)[0].toLowerCase();
    const bodyStart = opener.lastIndex;
    const closer = new RegExp('^[\\t ]*' + match[1][0] + '{' + match[1].length + ',}[\\t ]*(?:\\r?\\n|$)', 'gm');
    closer.lastIndex = bodyStart;
    const end = closer.exec(content);
    const blockEnd = end === null ? content.length : closer.lastIndex;
    opener.lastIndex = blockEnd;
    if (!['show-widget', 'widget', 'generative-ui'].includes(language)) {
      if (end === null) break;
      continue;
    }
    if (match.index > textStart) segments.push({ kind: 'text', start: textStart,
      content: content.slice(textStart, match.index), widget: null });
    const body = content.slice(bodyStart, end === null ? content.length : end.index).trim();
    try {
      segments.push({ kind: 'widget', start: match.index, content: '',
        widget: parseWidget(body, end !== null || !streaming) });
    } catch (error) {
      if (streaming && end === null) {
        const html = partialString(body, 'widget_code');
        const renderer = partialString(body, 'renderer');
        const usable = renderer !== 'full_html' && renderer !== 'guizang_html' &&
          html.length >= 40 && html.length <= MAX_HTML && /<\s*(?:svg|div|section|p)\b/i.test(html);
        segments.push({ kind: usable ? 'widget' : 'loading', start: match.index, content: '',
          widget: usable ? { title: partialString(body, 'title') || '可视化组件', renderer: 'html', html,
            complete: false, actions: [], notice: '' } : null });
      } else {
        segments.push({ kind: 'error', start: match.index, content: (error as Error).message, widget: null });
      }
    }
    textStart = blockEnd;
    if (end === null) break;
  }
  if (textStart < content.length) segments.push({ kind: 'text', start: textStart, content: content.slice(textStart), widget: null });
  return segments.length === 0 ? [{ kind: 'text', start: 0, content, widget: null }] : segments;
};

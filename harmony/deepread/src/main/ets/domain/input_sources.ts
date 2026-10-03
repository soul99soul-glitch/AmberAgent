import type { DeepReadSource } from '../research/source_prefetcher.ts';

export const MAX_INPUT_SOURCE_CHARS = 40_000;
export const MAX_GENERATION_SOURCES = 10;
export type DeepReadInputKind = 'text' | 'web' | 'file' | 'search';
export type DeepReadInputStatus = 'pending' | 'ready' | 'failed';

/** Saved source body, separate from the ten-source generation budget. */
export interface DeepReadInputSource {
  id: string;
  kind: DeepReadInputKind;
  title: string;
  url: string | null;
  content: string;
  status: DeepReadInputStatus;
  error: string | null;
  truncated: boolean;
  note: string | null;
  researchSource?: DeepReadSource;
}

export interface DeepReadCollectionIssue { url: string; title: string; error: string; }

const cleanText = (text: string): string => text.replace(/\u0000/g, '')
  .replace(/\r\n?/g, '\n').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();

export const makeInputSource = (
  kind: DeepReadInputKind, title: string, content: string, url: string | null = null,
  error: string | null = null,
): DeepReadInputSource => {
  const normalized = cleanText(content);
  const truncated = normalized.length > MAX_INPUT_SOURCE_CHARS;
  return {
    id: `input-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`, kind, title, url,
    content: normalized.substring(0, MAX_INPUT_SOURCE_CHARS),
    status: error !== null ? 'failed' : normalized.length > 0 ? 'ready' : 'pending', error, truncated,
    note: truncated ? `内容已截断：每个来源最多保存 ${MAX_INPUT_SOURCE_CHARS} 字符。` : null,
  };
};

export const sourceInputs = (text: string, urls: string): DeepReadInputSource[] => {
  const sources: DeepReadInputSource[] = [];
  if (text.trim().length > 0) sources.push(makeInputSource('text', '粘贴文本', text));
  const seen = new Set<string>();
  for (const raw of urls.split(/\r?\n/)) {
    const url = raw.trim();
    if (url.length === 0 || seen.has(url)) continue;
    seen.add(url);
    const error = /^https?:\/\/[^\s/?#]+(?:[/?#][^\s]*)?$/i.test(url) ? null : '请输入有效的 HTTP 或 HTTPS 网页链接。';
    sources.push(makeInputSource('web', url, '', url, error));
  }
  return sources;
};

export const mergeCollectedSources = (
  inputs: DeepReadInputSource[], collected: DeepReadSource[], issues: DeepReadCollectionIssue[],
  refreshWeb: boolean = false,
): DeepReadInputSource[] => {
  const result = inputs.slice();
  for (const research of collected) {
    const index = result.findIndex(source => source.url === research.url && source.url !== null
      && (!refreshWeb || source.kind === 'web' || source.kind === 'search'));
    // Continuation reuses verified bodies; force replaces only successfully reread web sources.
    if (index >= 0 && result[index].status === 'ready' && result[index].kind !== 'text' && !refreshWeb) continue;
    const context = index >= 0 && result[index].kind === 'text' ? result[index].content : '';
    const content = context.length > 0 ? `榜单背景：\n${context}\n\n网页正文：\n${research.evidenceText}` : research.evidenceText;
    const source = makeInputSource(research.source === 'seed' ? 'web' : 'search', research.title, content, research.url);
    source.researchSource = { ...research, evidenceText: '' };
    if (index >= 0) { source.id = result[index].id; result[index] = source; }
    else result.push(source);
  }
  for (const issue of issues) {
    const index = result.findIndex(source => source.url === issue.url && source.url !== null);
    if (index >= 0 && result[index].status === 'ready') continue;
    const source = makeInputSource(issue.url.length > 0 ? 'web' : 'search', issue.title, '', issue.url || null, issue.error);
    if (index >= 0) { source.id = result[index].id; result[index] = source; }
    else if (!result.some(existing => existing.title === source.title && existing.error === source.error)) result.push(source);
  }
  return result.map(source => source.status === 'pending' && source.kind === 'web'
    ? { ...source, status: 'failed' as const, error: '未取得网页正文，请检查链接或重试。' } : source);
};

export const generationSources = (inputs: DeepReadInputSource[]): DeepReadSource[] => inputs
  .filter(source => source.status === 'ready' && source.content.trim().length > 0)
  .slice(0, MAX_GENERATION_SOURCES).map(source => source.researchSource !== undefined
    ? { ...source.researchSource, evidenceText: source.content } : {
    sourceId: source.id, url: source.url ?? '', title: source.title,
    source: source.kind === 'file' ? '用户文件' : source.kind === 'text' ? '用户文本' : '用户网页',
    evidenceText: source.content, credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [],
  });

export const inputSourceLabel = (source: DeepReadInputSource): string => {
  if (source.status === 'failed') return source.error ?? '采集失败';
  if (source.status === 'pending') return '等待采集';
  return `${source.content.length} 字符${source.truncated ? ' · 已截断' : ''}`;
};

export const extractDocxSourceText = (xml: string): string => {
  const prepared = xml.replace(/<w:tab\s*\/>/g, '<w:t>\t</w:t>')
    .replace(/<w:br\s*\/>/g, '<w:t>\n</w:t>').replace(/<\/w:p>/g, '<w:t>\n</w:t>');
  const chunks: string[] = [];
  const pattern = /<w:t(?:\s[^>]*)?>([\s\S]*?)<\/w:t>/g;
  let match: RegExpExecArray | null = pattern.exec(prepared);
  while (match !== null) {
    chunks.push(match[1].replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'").replace(/&#(x?[0-9a-f]+);/gi, (_value: string, code: string): string =>
        String.fromCodePoint(parseInt(code.charAt(0).toLowerCase() === 'x' ? code.slice(1) : code,
          code.charAt(0).toLowerCase() === 'x' ? 16 : 10))).replace(/&amp;/g, '&'));
    match = pattern.exec(prepared);
  }
  return cleanText(chunks.join(''));
};

/** Decoder injection keeps native encoding APIs out of the testable domain. */
export const decodeInputSourceText = (
  bytes: Uint8Array, decode: (bytes: Uint8Array, encoding: string) => string,
): string => {
  let encoding = 'utf-8';
  if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) encoding = 'utf-16le';
  if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) encoding = 'utf-16be';
  try { return decode(bytes, encoding).replace(/^\uFEFF/, ''); }
  catch (error) {
    if (encoding !== 'utf-8') throw error;
    return decode(bytes, 'gb18030').replace(/^\uFEFF/, '');
  }
};

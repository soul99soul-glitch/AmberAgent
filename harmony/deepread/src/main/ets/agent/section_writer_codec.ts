// Pure section content decoding, normalization and readiness rules.
import { parseSourceNumbers } from './hierarchy_fields.ts';
import type { UIMessagePartText } from './message.ts';
import type { DeepReadOutput } from '../domain/models.ts';
import type { DeepReadGenerationStage } from '../domain/enums.ts';
import {
  IMAGE_CONFIDENCE, OVERVIEW_SUMMARY_MIN_CHARS, PERSPECTIVES_MAX, QUOTES_MAX, ENTITY_MAX_CHARS,
  READING_LINK_TITLE_MAX, READING_LINK_SOURCE_MAX, TIMELINE_EVENT_MAX, CORE_POINT_MAX,
  CORE_SUPPORTING_MAX, PERSPECTIVE_VIEWPOINT_MAX, PERSPECTIVE_HOLDER_MAX, QUOTE_TEXT_MAX,
  QUOTE_ATTR_MAX, HERO_CAPTION_MAX,
} from '../domain/enums.ts';
import type { TimelineEvent, CorePoint, ReadingLink, Perspective, Quote } from '../domain/models.ts';

// ===== 文本工具(照搬 Android cleanText / safeTake) =====

// safeTake — 照搬 Android:不切断 UTF-16 代理对(emoji 安全)
export const safeTake = (s: string, n: number): string => {
  if (n <= 0) return '';
  if (s.length <= n) return s;
  const cut = s.charCodeAt(n - 1) >= 0xD800 && s.charCodeAt(n - 1) <= 0xDBFF ? n - 1 : n;
  return s.substring(0, cut);
};

// cleanText — 照搬 Android:\s+ → 单空格 + trim + safeTake
export const cleanText = (s: string, max: number): string =>
  safeTake(s.replace(/\s+/g, ' ').trim(), max);

export const isHttpUrl = (url: string): boolean =>
  url.startsWith('http://') || url.startsWith('https://');

// ===== JSON 解析边界(对照 json_schema_validator.ts) =====

export const asObject = (input: string): Record<string, unknown> => {
  try {
    const parsed = JSON.parse(input);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
  } catch { /* 空 input → 空对象 */ }
  return {};
};

export const objString = (o: Record<string, unknown>, name: string): string | null => {
  const v = o[name];
  if (typeof v !== 'string') return null;
  const trimmed = v.trim();
  return trimmed.length > 0 ? trimmed : null;
};

const objBool = (o: Record<string, unknown>, name: string): boolean | null => {
  const v = o[name];
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') {
    if (v === 'true') return true;
    if (v === 'false') return false;
  }
  return null;
};

const objArray = (o: Record<string, unknown>, name: string): unknown[] => {
  const v = o[name];
  return Array.isArray(v) ? v : [];
};

export const objObjectList = (o: Record<string, unknown>, name: string): Record<string, unknown>[] => {
  return objArray(o, name).filter((x): x is Record<string, unknown> =>
    x !== null && typeof x === 'object' && !Array.isArray(x));
};

// stringList 从 array of string 提取(照搬 Android jsonPrimitive.contentOrNull)
export const stringListFrom = (o: Record<string, unknown>, name: string, max: number): string[] => {
  const arr = objArray(o, name);
  const out: string[] = [];
  for (const el of arr) {
    if (typeof el === 'string') {
      const c = cleanText(el, max);
      if (c.length > 0) out.push(c);
    }
  }
  return out;
};

export const urlString = (o: Record<string, unknown>, name: string): string | null => {
  const s = objString(o, name);
  return s !== null && isHttpUrl(s) ? s : null;
};

// ===== ReadingLink 解析(照搬 Android readingLinks) =====

export const parseReadingLinks = (o: Record<string, unknown>, name: string): ReadingLink[] => {
  return objObjectList(o, name).map(obj => {
    const url = urlString(obj, 'url');
    if (url === null) return null;
    const title = objString(obj, 'title');
    const finalTitle = title !== null
      ? cleanText(title, READING_LINK_TITLE_MAX)
      : url.split('://')[1]?.split('/')[0] ?? url;
    const source = objString(obj, 'source');
    return {
      title: finalTitle,
      url,
      source: source !== null ? cleanText(source, READING_LINK_SOURCE_MAX) : null,
      publishedAt: null,
    } as ReadingLink;
  }).filter((x): x is ReadingLink => x !== null);
};

// ===== merge 辅助(照搬 Android mergeStrings/mergeReadingLinks/mergeImageAssets) =====

export const mergeStrings = (existing: string[], incoming: string[], limit: number): string[] => {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const raw of [...existing, ...incoming]) {
    const c = cleanText(raw, ENTITY_MAX_CHARS);
    if (c.length === 0) continue;
    const key = c.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(c);
    if (out.length >= limit) break;
  }
  return out;
};

export const urlKey = (url: string): string => url.trim().replace(/\/+$/, '');

export const mergeReadingLinks = (existing: ReadingLink[], incoming: ReadingLink[], limit: number): ReadingLink[] => {
  const seen = new Set<string>();
  const out: ReadingLink[] = [];
  for (const link of [...existing, ...incoming]) {
    if (!isHttpUrl(link.url)) continue;
    const key = urlKey(link.url);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(link);
    if (out.length >= limit) break;
  }
  return out;
};

// ===== content 检查(照搬 Android hasOverviewContent 等) =====

export const hasOverviewContent = (o: DeepReadOutput): boolean =>
  o.summary.trim().length >= OVERVIEW_SUMMARY_MIN_CHARS;

export const hasNarrativeContent = (o: DeepReadOutput): boolean => {
  const timeline = o.timeline ?? [];
  if (timeline.some(e => e.event.trim().length >= 20)) return true;
  const corePoints = o.corePoints ?? [];
  return corePoints.some(p => p.point.trim().length >= 8 || (p.supporting?.trim().length ?? 0) >= 20);
};

export const hasAnalysisContent = (o: DeepReadOutput): boolean => {
  if ((o.analysis.coreDispute?.trim().length ?? 0) >= 20) return true;
  if ((o.analysis.implications?.trim().length ?? 0) >= 20) return true;
  if (o.analysis.perspectives.some(p => p.viewpoint.trim().length >= 20)) return true;
  if (o.analysis.quotes.some(q => q.text.trim().length >= 8)) return true;
  // The current editorial schema writes consequences into impacts rather than
  // implications. A concise dispute/perspective can accompany that full analysis.
  const hasPosition = (o.analysis.coreDispute?.trim().length ?? 0) > 0 ||
    o.analysis.perspectives.some(p => p.viewpoint.trim().length > 0);
  return hasPosition && (o.impacts?.some(impact => impact.effect.trim().length >= 20) ?? false);
};

export const hasExtendedReadingContent = (o: DeepReadOutput): boolean =>
  o.extendedReading.some(l => isHttpUrl(l.url))
  || (o.heroImageUrl !== null && o.heroImageConfidence === IMAGE_CONFIDENCE.HERO)
  || (o.diagram?.nodes.length ?? 0) >= 2;

export const statusReadyFor = (o: DeepReadOutput, stage: DeepReadGenerationStage): boolean => {
  switch (stage) {
    case 'OVERVIEW': return hasOverviewContent(o);
    case 'NARRATIVE': return hasNarrativeContent(o);
    case 'ANALYSIS': return hasAnalysisContent(o);
    case 'EXTENDED_READING': return hasExtendedReadingContent(o);
  }
};

// ===== 子解析器(照搬 Android timeline/corePoints/perspectives/quotes/imageAssets/diagram) =====

export const parseTimeline = (o: Record<string, unknown>): TimelineEvent[] =>
  objObjectList(o, 'timeline').map(obj => {
    const event = objString(obj, 'event');
    if (event === null) return null;
    const date = objString(obj, 'date');
    return {
      date: date !== null ? cleanText(date, 80) : '',
      event: cleanText(event, TIMELINE_EVENT_MAX),
      isHighlight: objBool(obj, 'is_highlight') ?? false,
      why: objString(obj, 'why') !== null ? cleanText(objString(obj, 'why')!, 180) : null,
      imageUrl: urlString(obj, 'image_url'),
      imageCaption: (() => { const c = objString(obj, 'image_caption'); return c !== null ? cleanText(c, HERO_CAPTION_MAX) : null; })(),
    } as TimelineEvent;
  }).filter((x): x is TimelineEvent => x !== null);

export const parseCorePoints = (o: Record<string, unknown>, sourceCount: number = 0): CorePoint[] =>
  objObjectList(o, 'core_points').map(obj => {
    const point = objString(obj, 'point');
    if (point === null) return null;
    const supporting = objString(obj, 'supporting');
    return {
      point: cleanText(point, CORE_POINT_MAX),
      sources: parseSourceNumbers(obj, sourceCount),
      supporting: supporting !== null ? cleanText(supporting, CORE_SUPPORTING_MAX) : null,
      imageUrl: urlString(obj, 'image_url'),
      imageCaption: (() => { const c = objString(obj, 'image_caption'); return c !== null ? cleanText(c, HERO_CAPTION_MAX) : null; })(),
    } as CorePoint;
  }).filter((x): x is CorePoint => x !== null);

export const parsePerspectives = (o: Record<string, unknown>, sourceCount: number = 0): Perspective[] =>
  objObjectList(o, 'perspectives').map(obj => {
    const viewpoint = objString(obj, 'viewpoint');
    if (viewpoint === null) return null;
    const holder = objString(obj, 'holder');
    return {
      viewpoint: cleanText(viewpoint, PERSPECTIVE_VIEWPOINT_MAX),
      interest: objString(obj, 'interest') !== null ? cleanText(objString(obj, 'interest')!, 120) : null,
      quote: objString(obj, 'quote') !== null ? cleanText(objString(obj, 'quote')!, QUOTE_TEXT_MAX) : null,
      quoteBy: objString(obj, 'quote_by') !== null ? cleanText(objString(obj, 'quote_by')!, QUOTE_ATTR_MAX) : null,
      sources: parseSourceNumbers(obj, sourceCount),
      holder: holder !== null ? cleanText(holder, PERSPECTIVE_HOLDER_MAX) : null,
    } as Perspective;
  }).filter((x): x is Perspective => x !== null).slice(0, PERSPECTIVES_MAX);

export const parseQuotes = (o: Record<string, unknown>): Quote[] =>
  objObjectList(o, 'quotes').map(obj => {
    const text = objString(obj, 'text');
    if (text === null) return null;
    const attribution = objString(obj, 'attribution');
    return {
      text: cleanText(text, QUOTE_TEXT_MAX),
      attribution: attribution !== null ? cleanText(attribution, QUOTE_ATTR_MAX) : null,
    } as Quote;
  }).filter((x): x is Quote => x !== null).slice(0, QUOTES_MAX);

// ===== Writer output 工厂(对应 Android ok/missing JSON) =====
export const makeWriterOutput = (status: string, payload: Record<string, unknown>): UIMessagePartText => ({
  type: 'text',
  text: JSON.stringify({ status, ...payload }),
  metadata: null,
});

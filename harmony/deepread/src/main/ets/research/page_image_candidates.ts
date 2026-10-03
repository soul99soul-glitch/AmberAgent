// Page image metadata only; no DOM dependency, network probes or inferred image sizes.
import type { ImageCandidate } from './image_scorer.ts';

const decodeEntities = (value: string): string => value.replace(
  /&(amp|lt|gt|quot|apos|nbsp|#\d+|#x[0-9a-f]+);/gi,
  (whole: string, body: string): string => {
    const name = body.toLowerCase();
    switch (name) {
      case 'amp': return '&';
      case 'lt': return '<';
      case 'gt': return '>';
      case 'quot': return '"';
      case 'apos': return '\'';
      case 'nbsp': return ' ';
      default: {
        const code = name.startsWith('#x') ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10);
        if (!Number.isFinite(code) || code < 0 || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) {
          return whole;
        }
        return String.fromCodePoint(code);
      }
    }
  },
);

// Exact attribute tokens avoid treating data-src as src. Quotes may contain '>'.
const parseAttributes = (text: string): Map<string, string> => {
  const attrs = new Map<string, string>();
  const attrRe = /([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g;
  let match: RegExpExecArray | null;
  while ((match = attrRe.exec(text)) !== null) {
    const name = match[1].toLowerCase();
    if (!attrs.has(name)) attrs.set(name, decodeEntities(match[2] ?? match[3] ?? match[4] ?? ''));
  }
  return attrs;
};

const dimension = (value: string | undefined): number => {
  if (value === undefined || !/^\d+(?:\.\d+)?$/.test(value.trim())) return 0;
  const size = Number(value);
  return Number.isFinite(size) && size > 0 ? size : 0;
};

const normalizePath = (path: string): string => {
  const parts = path.split('/');
  const result: string[] = [''];
  for (let i = 1; i < parts.length; i++) {
    if (parts[i] === '.') continue;
    if (parts[i] === '..') {
      if (result.length > 1) result.pop();
    } else result.push(parts[i]);
  }
  return result.join('/') || '/';
};

const resolveImageUrl = (value: string, baseUrl: string): string | null => {
  const reference = value.trim();
  if (reference.length === 0 || reference.startsWith('#') || reference.startsWith('?')) return null;
  if (/^[a-z][a-z0-9+.-]*:/i.test(reference)) {
    const absolute = /^(https?):\/\/([^/?#\s\\]+)([^?#\s\\]*)([?#][^\s\\]*)?$/i.exec(reference);
    if (absolute === null) return null;
    return absolute[1].toLowerCase() + '://' + absolute[2] + absolute[3] + (absolute[4] ?? '');
  }
  const base = /^(https?):\/\/([^/?#\s\\]+)([^?#\s\\]*)/i.exec(baseUrl);
  if (base === null) return null;
  if (reference.startsWith('//')) return resolveImageUrl(base[1] + ':' + reference, baseUrl);
  if (/[\s\\]/.test(reference)) return null;
  const suffixAt = reference.search(/[?#]/);
  const path = suffixAt < 0 ? reference : reference.slice(0, suffixAt);
  const suffix = suffixAt < 0 ? '' : reference.slice(suffixAt);
  const directory = (base[3] || '/').slice(0, (base[3] || '/').lastIndexOf('/') + 1);
  return base[1].toLowerCase() + '://' + base[2] + normalizePath(path.startsWith('/') ? path : directory + path) + suffix;
};

// Descriptors choose the source URL, not a fabricated natural width/height.
const largestSrcsetUrl = (srcset: string, baseUrl: string): string | null => {
  let bestUrl: string | null = null;
  let bestSize = 0;
  let descriptorKind = '';
  let index = 0;
  while (index < srcset.length) {
    while (index < srcset.length && /[\s,]/.test(srcset[index])) index++;
    const urlStart = index;
    while (index < srcset.length && !/\s/.test(srcset[index])) index++;
    let reference = srcset.slice(urlStart, index);
    let descriptor = '1x';
    if (reference.endsWith(',')) {
      reference = reference.replace(/,+$/, '');
    } else {
      const descriptorStart = index;
      while (index < srcset.length && srcset[index] !== ',') index++;
      descriptor = srcset.slice(descriptorStart, index).trim() || '1x';
    }
    const match = /^(\d+(?:\.\d+)?|\.\d+)(w|x)$/.exec(descriptor);
    if (match === null) continue;
    const size = Number(match[1]);
    if (size <= 0 || (match[2] === 'w' && !Number.isInteger(size))) continue;
    const url = resolveImageUrl(reference, baseUrl);
    if (url === null) continue;
    if (descriptorKind.length === 0) descriptorKind = match[2];
    if (match[2] !== descriptorKind) continue;
    if (size > bestSize) {
      bestUrl = url;
      bestSize = size;
    }
  }
  return bestUrl;
};

const imgCandidate = (attrs: Map<string, string>, baseUrl: string): ImageCandidate | null => {
  let url: string | null = null;
  for (const name of ['srcset', 'data-srcset']) {
    url = largestSrcsetUrl(attrs.get(name) ?? '', baseUrl);
    if (url !== null) break;
  }
  if (url === null) {
    for (const name of ['data-src', 'data-original', 'src']) {
      url = resolveImageUrl(attrs.get(name) ?? '', baseUrl);
      if (url !== null) break;
    }
  }
  if (url === null) return null;
  return {
    url,
    width: dimension(attrs.get('width')),
    height: dimension(attrs.get('height')),
    altText: attrs.get('alt') ?? '',
    sourceUrl: baseUrl,
  };
};

const mergeCandidates = (candidates: ImageCandidate[]): ImageCandidate[] => {
  const byUrl = new Map<string, ImageCandidate>();
  for (const candidate of candidates) {
    const existing = byUrl.get(candidate.url);
    if (existing === undefined) {
      byUrl.set(candidate.url, candidate);
      continue;
    }
    // Fill only missing metadata, so a duplicate cannot replace explicit sizes.
    if (existing.width === 0) existing.width = candidate.width;
    if (existing.height === 0) existing.height = candidate.height;
    if (existing.altText.length === 0) existing.altText = candidate.altText;
  }
  return Array.from(byUrl.values());
};

export const extractPageImageCandidates = (html: string, baseUrl: string): ImageCandidate[] => {
  const candidates: ImageCandidate[] = [];
  let ogImage: ImageCandidate | null = null;
  let twitterImage: ImageCandidate | null = null;
  // Only inspect real tags, excluding comments and script/style string examples.
  const tagRe = /<!--[\s\S]*?(?:-->|$)|<(script|style)\b[^>]*>[\s\S]*?<\/\1\s*>|<([a-z][a-z0-9:-]*)\b((?:"[^"]*"|'[^']*'|[^'">])*)>/gi;
  let match: RegExpExecArray | null;
  while ((match = tagRe.exec(html)) !== null) {
    if (match[2] === undefined) continue;
    const tag = match[2].toLowerCase();
    if (tag !== 'meta' && tag !== 'img') continue;
    const attrs = parseAttributes(match[3]);
    if (tag === 'img') {
      const candidate = imgCandidate(attrs, baseUrl);
      if (candidate !== null) candidates.push(candidate);
      continue;
    }
    const key = (attrs.get('property') ?? attrs.get('name') ?? '').toLowerCase();
    const content = attrs.get('content') ?? '';
    const isOgRoot = key === 'og:image' || key === 'og:image:url'
      || (key === 'og:image:secure_url' && ogImage === null);
    const isTwitterRoot = key === 'twitter:image' || key === 'twitter:image:src';
    if (isOgRoot || isTwitterRoot) {
      const url = resolveImageUrl(content, baseUrl);
      const image: ImageCandidate | null = url === null ? null : {
        url, width: 0, height: 0, altText: '', sourceUrl: baseUrl,
      };
      if (isOgRoot) ogImage = image;
      else twitterImage = image;
      if (image !== null) candidates.push(image);
      continue;
    }
    const current = key.startsWith('og:image:') ? ogImage : (key.startsWith('twitter:image:') ? twitterImage : null);
    if (current === null) continue;
    if (key.endsWith(':width')) current.width = dimension(content);
    else if (key.endsWith(':height')) current.height = dimension(content);
    else if (key.endsWith(':alt')) current.altText = content;
    else if (key === 'og:image:secure_url') {
      const url = resolveImageUrl(content, baseUrl);
      if (url !== null) current.url = url;
    }
  }
  return mergeCandidates(candidates);
};

import type { HotItem } from './NewsNowProvider.ets';

// 指定官方 RSS 的 item/title/link 子集；不是通用 HTML 或 Atom 解析器。
const decodeXmlText = (value: string): string => value.replace(
  /&(amp|lt|gt|quot|apos|#\d+|#x[0-9a-f]+);/gi,
  (whole: string, body: string): string => {
    const name: string = body.toLowerCase();
    switch (name) {
      case 'amp': return '&';
      case 'lt': return '<';
      case 'gt': return '>';
      case 'quot': return '"';
      case 'apos': return '\'';
      default: {
        const code: number = name.startsWith('#x') ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10);
        if (!Number.isFinite(code) || code < 0 || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) return whole;
        return String.fromCodePoint(code);
      }
    }
  },
);

const rssTagText = (block: string, tag: string): string => {
  const match: RegExpExecArray | null = new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)</${tag}>`, 'i').exec(block);
  if (match === null) return '';
  const body: string = match[1].trim();
  if (body.startsWith('<![CDATA[') && body.endsWith(']]>')) return body.slice(9, -3).trim();
  return decodeXmlText(body).trim();
};

export const parseHotListRss = (xml: string, limit: number = 12): HotItem[] => {
  const items: HotItem[] = [];
  const itemRe: RegExp = /<item\b[^>]*>([\s\S]*?)<\/item>/gi;
  let match: RegExpExecArray | null = itemRe.exec(xml);
  while (match !== null && items.length < limit) {
    const title: string = rssTagText(match[1], 'title');
    const url: string = rssTagText(match[1], 'link');
    if (title.length > 0 && /^https?:\/\//i.test(url)
      && !items.some((item: HotItem): boolean => item.url === url)) {
      items.push({ rank: items.length + 1, title, url, heat: '' });
    }
    match = itemRe.exec(xml);
  }
  return items;
};

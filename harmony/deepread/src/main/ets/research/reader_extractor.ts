// ReaderExtractor — ArkTS regex readability fallback + native double-path
// 照搬 Android DeepReadSourcePrefetcher.kt:651-686
//
// 双路径:优先 native(Rust NAPI,Plan 8),失败/空/短结果(<18 字符)时 fallback 到 ArkTS regex。
// Plan 8 升级为 Rust NAPI;阶段 1(本文件)只做 ArkTS fallback,双路径入口已就位。

import { READER_NATIVE_MIN_CHARS } from '../domain/enums.ts';

export interface ExtractedArticle {
  title: string;
  contentText: string;
  contentHtml: string;
  sectionCount: number;
}

// 照搬 Android extractReadableTextJvm 的 regex 链
// 注意顺序:先剥离 script/style/noscript/svg/canvas 块,再处理换行语义,再去标签
const SCRIPT_STYLE_RE = /<(script|style|noscript|svg|canvas)\b[\s\S]*?<\/\1>/gi;
const BR_RE = /<br\s*\/?>/gi;
const BLOCK_CLOSE_RE = /<\/(p|div|section|article|li|h[1-6])>/gi;
const TAG_RE = /<[^>]+>/g;
const INLINE_WS_RE = /[ \t\v\f\r]+/g;
const MULTI_NL_RE = /\n\s*\n+/g;
const TITLE_RE = /<title[^>]*>([\s\S]*?)<\/title>/i;

const htmlUnescape = (s: string): string =>
  s
    .replaceAll('&amp;', '&')
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&#39;', "'")
    .replaceAll('&apos;', "'");

// extractReadableTextArkTs — 照搬 Android extractReadableTextJvm
// 返回完整 ExtractedArticle(含 title / contentHtml 留空 / sectionCount)
export const extractReadableTextArkTs = (html: string, baseUrl: string | null = null): ExtractedArticle => {
  // 1. title
  const titleMatch = html.match(TITLE_RE);
  const title = titleMatch ? titleMatch[1].trim() : (baseUrl ?? '');

  // 2. 剥离 script/style/noscript/svg/canvas 块(Android 同样)
  let cleaned = html.replace(SCRIPT_STYLE_RE, ' ');

  // 3. 块级换行语义:<br> → \n,</p|div|...> → \n(去标签前保留段落)
  cleaned = cleaned.replace(BR_RE, '\n');
  cleaned = cleaned.replace(BLOCK_CLOSE_RE, '\n');

  // 4. 去剩余 HTML 标签
  cleaned = cleaned.replace(TAG_RE, ' ');

  // 5. HTML 实体反转义
  cleaned = htmlUnescape(cleaned);

  // 6. 压缩空白(行内空白 → 单空格;多空行 → 单空行)
  cleaned = cleaned.replace(INLINE_WS_RE, ' ');
  cleaned = cleaned.replace(MULTI_NL_RE, '\n');

  // 7. 逐行 trim + 过滤 < 18 字符的短行(照搬 Android filter { line.length >= 18 })
  //    再 distinct 去重(照搬 Android)
  const lines: string[] = [];
  const seen = new Set<string>();
  for (const raw of cleaned.split('\n')) {
    const line = raw.trim();
    if (line.length < READER_NATIVE_MIN_CHARS) continue;
    if (seen.has(line)) continue;
    seen.add(line);
    lines.push(line);
  }
  const contentText = lines.join('\n');

  return {
    title,
    contentText,
    contentHtml: '',
    sectionCount: lines.length,
  };
};

// 双路径入口:照搬 Android extractReadableText (DeepReadSourcePrefetcher.kt:651-661)
// - 优先 native(nativeExtractor 注入);成功且 >= 18 字符 → 用 native
// - native 抛错 / 返回 null / 结果 < 18 字符 → fallback 到 ArkTS
export const extractReadableText = (
  html: string,
  url: string | null,
  nativeExtractor?: (html: string, baseUrl: string) => ExtractedArticle | null,
): string => {
  if (url && nativeExtractor) {
    try {
      const native = nativeExtractor(html, url);
      if (native !== null && native.contentText.length >= READER_NATIVE_MIN_CHARS) {
        return native.contentText;
      }
    } catch {
      // 落到 ArkTS fallback(照搬 Android runCatching ?: null)
    }
  }
  return extractReadableTextArkTs(html, url).contentText;
};

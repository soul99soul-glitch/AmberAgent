// Deep Read topic_id 派生 — HarmonyOS 独立 app 统一规则
// 参见 design 3.5。Android 两条路径(hot-list / template-demo)都不适用,
// 鸿蒙版用 URL 优先 + 规范化。
//
// 注意:本文件用 node 内置 crypto(测试用)。鸿蒙运行时需替换为
// @ohos.security.cryptoHash,通过 adapter 注入(后续 Platform 层实现)。

import { createHash } from 'crypto';

const HTTP_PREFIX = /^https?:\/\//i;

const TRACKING_PARAMS = new Set([
  'utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content',
  'spm', 'from', 'ref', 'ref_src', 'fbclid', 'gclid',
]);

export const normalizeUrl = (url: string): string => {
  // 1. 小写 host(path 可能大小写敏感,保留)
  // 2. 去 fragment
  // 3. 去 tracking param
  try {
    const u = new URL(url);
    u.hash = '';
    u.hostname = u.hostname.toLowerCase();
    const params = new URLSearchParams(u.search);
    const filtered = new URLSearchParams();
    for (const [k, v] of params.entries()) {
      if (!TRACKING_PARAMS.has(k.toLowerCase())) filtered.append(k, v);
    }
    u.search = filtered.toString();
    return u.toString();
  } catch {
    return url;
  }
};

export const normalizeTitle = (title: string): string => {
  // 照搬 Android HotListRepository.kt:208-212: lowercase + \s+ → 单空格 + trim
  return title.toLowerCase().replace(/\s+/g, ' ').trim();
};

const sha256Hex32 = (input: string): string => {
  return createHash('sha256').update(input, 'utf-8').digest('hex').substring(0, 32);
};

export const deriveTopicId = (input: { url: string | null | undefined; title: string }): string => {
  if (input.url && HTTP_PREFIX.test(input.url)) {
    return sha256Hex32(normalizeUrl(input.url));
  }
  // 非 http URL(ftp 等)或 url 为空 → 走 title 路径
  return sha256Hex32(normalizeTitle(input.title));
};

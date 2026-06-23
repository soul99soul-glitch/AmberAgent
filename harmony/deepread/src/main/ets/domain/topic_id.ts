// Deep Read topic_id 派生 — HarmonyOS 独立 app 统一规则(参见 design 3.5)
//
// 双运行时兼容:Web 全局(URL/URLSearchParams/crypto)通过 runtime 接口注入,
// 不直接 import 'crypto'。本文件 ArkTS 可编译。node 测试由 topic_id_node.ts
// 提供默认 runtime(barrel re-export)。

const HTTP_PREFIX = /^https?:\/\//i;

const TRACKING_PARAMS = new Set([
  'utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content',
  'spm', 'from', 'ref', 'ref_src', 'fbclid', 'gclid',
]);

// runtime 接口:各运行时注入原生实现(node)或 shim(ArkTS)
export interface TopicIdRuntime {
  normalizeUrlInput: (url: string) => string;
  sha256Hex32: (input: string) => string;
}

export const normalizeTitle = (title: string): string => {
  // 照搬 Android HotListRepository.kt:208-212: lowercase + \s+ → 单空格 + trim
  return title.toLowerCase().replace(/\s+/g, ' ').trim();
};

// 工厂:可注入 runtime
export const createTopicIdWithRuntime = (runtime: TopicIdRuntime) => {
  return (input: { url: string | null | undefined; title: string }): string => {
    if (input.url && HTTP_PREFIX.test(input.url)) {
      return runtime.sha256Hex32(runtime.normalizeUrlInput(input.url));
    }
    return runtime.sha256Hex32(normalizeTitle(input.title));
  };
};

// ArkTS 兼容的 URL normalize(不依赖全局 URL,纯字符串解析)
// 用于 ArkTS 运行时的默认 normalizeUrlInput(与 node 行为等价)
export const normalizeUrlArkTs = (url: string): string => {
  // protocol://host[:port]/path?search#fragment
  const m = /^([^:?#]+):\/\/([^/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$/.exec(url);
  if (m === null) return url;
  const protocol = m[1];
  const host = m[2].toLowerCase();
  const path = m[3];
  const searchRaw = m[4] ?? '';
  // 去 tracking param + 去 fragment
  let kept = '';
  if (searchRaw.length > 1) {
    const qs = searchRaw.slice(1).split('&');
    const out: string[] = [];
    for (const pair of qs) {
      const eq = pair.indexOf('=');
      const k = eq >= 0 ? pair.slice(0, eq) : pair;
      if (!TRACKING_PARAMS.has(k.toLowerCase())) out.push(pair);
    }
    kept = out.length > 0 ? '?' + out.join('&') : '';
  }
  return `${protocol}://${host}${path}${kept}`;
};

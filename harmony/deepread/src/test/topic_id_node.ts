// topic_id 的 node 运行时 — 仅 node 测试用(import 'crypto' + 全局 URL)。
// ArkTS 不编译本文件(barrel 只在 node 环境引用)。
// ArkTS 入口用 topic_id.ts 的 createTopicIdWithRuntime + arkts_shims 注入 runtime。

import { createHash } from 'crypto';
import { createTopicIdWithRuntime, normalizeUrlArkTs, normalizeTitle } from '../main/ets/domain/topic_id.ts';
import type { TopicIdRuntime } from '../main/ets/domain/topic_id.ts';

const TRACKING_PARAMS = new Set([
  'utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content',
  'spm', 'from', 'ref', 'ref_src', 'fbclid', 'gclid',
]);

// node 运行时:用 node 原生 URL + URLSearchParams + crypto
const nodeRuntime: TopicIdRuntime = {
  normalizeUrlInput: (url: string): string => {
    try {
      const u = new URL(url);
      u.hash = '';
      u.hostname = u.hostname.toLowerCase();
      const params = new URLSearchParams(u.search);
      const filtered = new URLSearchParams();
      params.forEach((v: string, k: string): void => {
        if (!TRACKING_PARAMS.has(k.toLowerCase())) filtered.append(k, v);
      });
      u.search = filtered.toString();
      return u.toString();
    } catch {
      return url;
    }
  },
  sha256Hex32: (input: string): string =>
    createHash('sha256').update(input, 'utf-8').digest('hex').substring(0, 32),
};

// node 默认 deriveTopicId + normalizeUrl(barrel re-export,保持向后兼容)
export const deriveTopicId = createTopicIdWithRuntime(nodeRuntime);
export const normalizeUrl = (url: string): string => nodeRuntime.normalizeUrlInput(url);
export { nodeRuntime as defaultNodeRuntime, normalizeUrlArkTs, createTopicIdWithRuntime, normalizeTitle };
export type { TopicIdRuntime };

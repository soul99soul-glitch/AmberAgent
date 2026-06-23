// interleaveSearchResults — round-robin 合并多 query 结果
// 照搬 Android DeepReadSourcePrefetcher.kt:526-544
//
// 每轮取各 bucket 同 index 位置的 hit,跨 bucket 按 URL 去重,cap MAX_SEARCH_RESULTS。
// Android 在 interleaving 前先做 bucket 内 distinctBy(url);此处用单一 seen set 统一处理
// (bucket 内重复也会被 seen 捕获,语义等价)。

import type { SearchHit } from '../platform/search.ts';
import { MAX_SEARCH_RESULTS } from '../domain/enums.ts';

export const interleaveSearchResults = (buckets: SearchHit[][]): SearchHit[] => {
  const result: SearchHit[] = [];
  const seen = new Set<string>();
  let maxLen = 0;
  for (const b of buckets) {
    if (b.length > maxLen) maxLen = b.length;
  }
  for (let i = 0; i < maxLen; i++) {
    for (const bucket of buckets) {
      if (result.length >= MAX_SEARCH_RESULTS) return result;
      if (i >= bucket.length) continue;
      const hit = bucket[i];
      if (!hit) continue;
      if (seen.has(hit.url)) continue;  // 跨 bucket + bucket 内去重
      seen.add(hit.url);
      result.push(hit);
    }
  }
  return result;
};

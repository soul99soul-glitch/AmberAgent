// NewsNowProvider — NewsNow 聚合热搜源(对照 Android NewsNowPresets.kt)
// API: https://newsnow.busiyi.world/api/s?id=<source_id>
// 两种用法:
//   1. fetchAllSources() → 按源分组的完整热榜(知乎/微博/抖音… 各自 12 条)
//   2. search(queries) → SearchProvider 接口,用关键词匹配热榜条目(供 prefetcher 用)

import type { AbortSignalLike, HttpClient, HttpRequest, HttpResponse, SearchProvider, SearchHit } from '@amber/deepread-domain';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.NewsNow';
const ENDPOINT: string = 'https://newsnow.busiyi.world/api/s';

// R20:取消已发生时不再发起下一路 HTTP;真实中止底层外呼
const isAborted = (signal?: AbortSignalLike): boolean =>
  signal !== undefined && signal.aborted;

// 热榜预设(对照 Android NewsNowPresets.ALL)
interface NewsNowPreset {
  id: string;
  displayName: string;
}

const PRESETS: NewsNowPreset[] = [
  { id: 'zhihu', displayName: '知乎热榜' },
  { id: 'weibo', displayName: '微博热搜' },
  { id: 'douyin', displayName: '抖音热搜' },
  { id: 'bilibili-hot-search', displayName: 'B站热搜' },
  { id: 'v2ex-share', displayName: 'V2EX' },
  { id: 'github-trending-today', displayName: 'GitHub趋势' },
  { id: '36kr-quick', displayName: '36氪快讯' },
  { id: 'wallstreetcn-hot', displayName: '华尔街见闻' },
  { id: 'cls-telegraph', displayName: '财联社' },
];

// 单条热榜
interface HotItem {
  rank: number;
  title: string;
  url: string;
  heat: string;
}

// 一个源的完整热榜
interface SourceSection {
  sourceId: string;
  sourceName: string;
  items: HotItem[];
  error: string | null;
  fetchedAt: number;
}

// NewsNow API 返回
interface NewsNowApiItem {
  title: string;
  url: string;
  extra: Record<string, string> | null;
}

interface NewsNowApiResponse {
  status: string;
  id: string;
  items: NewsNowApiItem[];
}

export class NewsNowProvider implements SearchProvider {
  readonly name: string = 'newsnow';
  private http: HttpClient;

  constructor(http: HttpClient) {
    this.http = http;
  }

  // 抓取单个源的热榜(signal 可选:取消时真实中止该 HTTP)
  async fetchSource(preset: NewsNowPreset, signal?: AbortSignalLike): Promise<SourceSection> {
    const result: SourceSection = {
      fetchedAt: Date.now(),
      sourceId: preset.id,
      sourceName: preset.displayName,
      items: [],
      error: null,
    };
    try {
      const req: HttpRequest = {
        url: `${ENDPOINT}?id=${preset.id}`,
        method: 'GET',
        headers: {
          'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
          'Accept': 'application/json, text/plain, */*',
          'Referer': 'https://newsnow.busiyi.world/',
        },
      };
      const resp: HttpResponse = await this.http.fetch(req, signal === undefined ? undefined : { signal: signal });
      if (resp.status !== 200) {
        result.error = `HTTP ${resp.status}`;
        hilog.warn(DOMAIN, TAG, '%{public}s: HTTP %{public}d', preset.id, resp.status);
        return result;
      }
      hilog.info(DOMAIN, TAG, '%{public}s: body len=%{public}d first100=%{public}s',
        preset.id, resp.body.length, resp.body.substring(0, 100));
      const data: NewsNowApiResponse = JSON.parse(resp.body) as NewsNowApiResponse;
      if (data.items === undefined || data.items === null) {
        result.error = 'no items';
        hilog.warn(DOMAIN, TAG, '%{public}s: no items field, status=%{public}s', preset.id, data.status);
        return result;
      }
      hilog.info(DOMAIN, TAG, '%{public}s: items array len=%{public}d', preset.id, data.items.length);
      for (let i = 0; i < data.items.length && i < 12; i++) {
        const apiItem: NewsNowApiItem = data.items[i];
        const heatVal: string = (apiItem.extra !== null && apiItem.extra !== undefined)
          ? (apiItem.extra['info'] ?? '')
          : '';
        const item: HotItem = {
          rank: i + 1,
          title: apiItem.title,
          url: apiItem.url,
          heat: heatVal,
        };
        result.items.push(item);
      }
      hilog.info(DOMAIN, TAG, '%{public}s: parsed items=%{public}d', preset.id, result.items.length);
    } catch (e) {
      result.error = String(e).substring(0, 60);
      hilog.error(DOMAIN, TAG, '%{public}s: PARSE ERROR: %{public}s', preset.id, String(e).substring(0, 120));
    }
    return result;
  }

  // 抓取所有源(并行;signal 可选)
  async fetchAllSources(signal?: AbortSignalLike): Promise<SourceSection[]> {
    const promises: Promise<SourceSection>[] = [];
    for (let i = 0; i < PRESETS.length; i++) {
      promises.push(this.fetchSource(PRESETS[i], signal));
    }
    const results: SourceSection[] = await Promise.all(promises);
    return results;
  }

  // 综合热点:从所有源各取前 N 条,合并按出现频次排序
  buildCombinedTopics(allSections: SourceSection[]): HotItem[] {
    // 简化:取每个源的第 1 条(头条)合并,去重
    const combined: HotItem[] = [];
    const seenTitles: Set<string> = new Set();
    for (let si = 0; si < allSections.length; si++) {
      const section: SourceSection = allSections[si];
      for (let ii = 0; ii < section.items.length; ii++) {
        const item: HotItem = section.items[ii];
        const key: string = item.title.substring(0, 12);
        if (!seenTitles.has(key)) {
          seenTitles.add(key);
          combined.push(item);
        }
      }
    }
    return combined;
  }

  // SearchProvider 接口:用关键词匹配热榜条目(signal 可选:R20 取消贯穿)
  async search(queries: string[], signal?: AbortSignalLike): Promise<SearchHit[]> {
    if (isAborted(signal)) return [];
    const allSections: SourceSection[] = await this.fetchAllSources(signal);
    const hits: SearchHit[] = [];
    for (let si = 0; si < allSections.length; si++) {
      if (isAborted(signal)) break;
      const section: SourceSection = allSections[si];
      for (let qi = 0; qi < queries.length; qi++) {
        const q: string = queries[qi].toLowerCase();
        for (let ii = 0; ii < section.items.length; ii++) {
          const item: HotItem = section.items[ii];
          if (item.title.toLowerCase().includes(q)) {
            const hit: SearchHit = {
              title: item.title,
              url: item.url,
              snippet: `${section.sourceName} · ${item.heat}`,
              source: 'newsnow',
            };
            hits.push(hit);
          }
        }
      }
    }
    return hits;
  }
}

export const createNewsNowProvider = (http: HttpClient): NewsNowProvider => {
  return new NewsNowProvider(http);
};

export { PRESETS as NEWSNOW_PRESETS };
export type { NewsNowPreset, HotItem, SourceSection };

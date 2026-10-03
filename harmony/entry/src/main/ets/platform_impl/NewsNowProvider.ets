// NewsNowProvider — NewsNow 聚合热搜源(对照 Android NewsNowPresets.kt)
// API: https://newsnow.busiyi.world/api/s?id=<source_id>
// 两种用法:
//   1. fetchAllSources() → 按源分组的完整热榜(知乎/微博/抖音… 各自 12 条)
//   2. search(queries) → SearchProvider 接口,用关键词匹配热榜条目(供 prefetcher 用)

import type { AbortSignalLike, HttpClient, HttpRequest, HttpResponse, SearchProvider, SearchHit } from '@amber/deepread-domain';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { parseHotListRss } from './HotListRss.ets';
import { DEEPREAD_DISCOVERY_SOURCES, discoveryHttpUrl, parseHackerNewsHotItem,
  parseHuggingFaceHotItems, parseGithubTrendingHotItems } from '@amber/deepread-domain';

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
  feedUrls?: string[];
  category?: string;
  defaultEnabled?: boolean;
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

const RSS_PRESETS: NewsNowPreset[] = [
  { id: 'arxiv_ai', displayName: 'Arxiv AI', feedUrls: ['https://rss.arxiv.org/rss/cs.AI', 'https://rss.arxiv.org/rss/cs.CL'] },
  { id: 'infoq_ai', displayName: 'InfoQ AI', feedUrls: ['https://feed.infoq.com/ai-ml-data-eng/'] },
];

export const HOTLIST_PRESETS: NewsNowPreset[] = DEEPREAD_DISCOVERY_SOURCES;

// 单条热榜
interface HotItem {
  rank: number;
  title: string;
  url: string;
  heat: string;
  displayTitle?: string;
  summary?: string;
}

// 一个源的完整热榜
interface SourceSection {
  sourceId: string;
  sourceName: string;
  items: HotItem[];
  error: string | null;
  fetchedAt: number;
  stale?: boolean;
}

interface RssFeedResult {
  items: HotItem[];
  error: string | null;
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
    if (preset.id === 'hacker_news' || preset.id === 'huggingface_papers' || preset.id === 'github_trending_ai') {
      return this.fetchBuiltinSource(preset, signal);
    }
    if (preset.feedUrls !== undefined) return this.fetchRssSource(preset, signal);
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
          url: discoveryHttpUrl(apiItem.url),
          heat: heatVal,
        };
        result.items.push(item);
      }
      result.fetchedAt = Date.now();
      hilog.info(DOMAIN, TAG, '%{public}s: parsed items=%{public}d', preset.id, result.items.length);
    } catch (e) {
      result.error = String(e).substring(0, 60);
      hilog.error(DOMAIN, TAG, '%{public}s: PARSE ERROR: %{public}s', preset.id, String(e).substring(0, 120));
    }
    return result;
  }

  private async fetchBuiltinSource(preset: NewsNowPreset, signal?: AbortSignalLike): Promise<SourceSection> {
    const result: SourceSection = { sourceId: preset.id, sourceName: preset.displayName,
      fetchedAt: Date.now(), items: [], error: null };
    const read = async (url: string): Promise<string> => {
      if (isAborted(signal)) throw new Error('已取消热点刷新');
      const response = await this.http.fetch({ url, method: 'GET', headers: {
        'Accept': preset.id === 'github_trending_ai' ? 'text/html' : 'application/json',
        'User-Agent': 'Mozilla/5.0',
      } }, { signal });
      if (response.status !== 200) throw new Error(`HTTP ${response.status}`);
      return response.body;
    };
    try {
      if (preset.id === 'hacker_news') {
        const ids: number[] = JSON.parse(await read('https://hacker-news.firebaseio.com/v0/topstories.json')) as number[];
        if (!Array.isArray(ids)) throw new Error('Hacker News 榜单格式无效');
        const items = await Promise.all(ids.slice(0, 12).map(async (id: number, index: number): Promise<HotItem | null> => {
          if (!Number.isSafeInteger(id) || id < 1) throw new Error('Hacker News 条目标识无效');
          return parseHackerNewsHotItem(await read(`https://hacker-news.firebaseio.com/v0/item/${id}.json`), index + 1);
        }));
        result.items = items.filter((item: HotItem | null): boolean => item !== null) as HotItem[];
      } else if (preset.id === 'huggingface_papers') {
        result.items = parseHuggingFaceHotItems(await read('https://huggingface.co/api/daily_papers'));
      } else {
        result.items = parseGithubTrendingHotItems(await read('https://github.com/trending'));
      }
      if (result.items.length === 0) result.error = '榜单未返回有效条目';
    } catch (error) {
      result.error = String(error).substring(0, 120);
    }
    result.fetchedAt = Date.now();
    return result;
  }

  private async fetchRssFeed(url: string, signal?: AbortSignalLike): Promise<RssFeedResult> {
    try {
      const response: HttpResponse = await this.http.fetch({
        url, method: 'GET', headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
          'Accept': 'application/rss+xml, application/xml, text/xml',
        },
      }, { signal });
      if (response.status !== 200) return { items: [], error: `HTTP ${response.status}` };
      const items: HotItem[] = parseHotListRss(response.body);
      return { items, error: items.length > 0 ? null : '订阅源未返回有效条目' };
    } catch (e) {
      return { items: [], error: String(e).substring(0, 60) };
    }
  }

  private async fetchRssSource(preset: NewsNowPreset, signal?: AbortSignalLike): Promise<SourceSection> {
    const feeds: RssFeedResult[] = await Promise.all((preset.feedUrls ?? []).map(
      (url: string): Promise<RssFeedResult> => this.fetchRssFeed(url, signal)));
    const items: HotItem[] = [];
    // 同源多个 feed 交错取条目，cs.AI 不会占满上限而挡住 cs.CL。
    for (let index = 0; index < 12 && items.length < 12; index++) {
      for (const feed of feeds) {
        const item: HotItem | undefined = feed.items[index];
        if (item !== undefined && !items.some((old: HotItem): boolean => old.url === item.url)) {
          items.push({ rank: items.length + 1, title: item.title, url: item.url, heat: '' });
          if (items.length >= 12) break;
        }
      }
    }
    const errors: string[] = feeds.filter((feed: RssFeedResult): boolean => feed.error !== null)
      .map((feed: RssFeedResult): string => feed.error ?? '');
    return {
      sourceId: preset.id, sourceName: preset.displayName, items, fetchedAt: Date.now(),
      error: errors.length > 0 ? errors.join('；') : null,
    };
  }

  // 抓取所有源(并行;signal 可选)
  async fetchAllSources(signal?: AbortSignalLike, enabledIds?: string[]): Promise<SourceSection[]> {
    if (isAborted(signal)) return [];
    // 搜索 provider 的既有默认保留 NewsNow；看板显式传启用列表，可包含英语 RSS。
    const presets: NewsNowPreset[] = enabledIds === undefined ? PRESETS : HOTLIST_PRESETS.filter(
      (preset: NewsNowPreset): boolean => enabledIds.includes(preset.id));
    const promises: Promise<SourceSection>[] = [];
    for (let i = 0; i < presets.length; i++) {
      promises.push(this.fetchSource(presets[i], signal));
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

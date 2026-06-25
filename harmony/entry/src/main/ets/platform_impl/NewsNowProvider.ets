// NewsNowProvider — NewsNow 聚合热搜源(对照 Android NewsNowPresets.kt)
// API: https://newsnow.busiyi.world/api/s?id=<source_id>
// 返回 { items: [{ title, url, extra: { info } }] }
// 12 个国内热榜(知乎/微博/抖音/B站/V2EX/GitHub/36氪/虎扑/雪球/华尔街见闻/财联社)
// 作为 Deep Read 的 SearchProvider:用话题标题匹配热榜条目,命中就当来源。

import type { HttpClient, HttpRequest, HttpResponse, SearchProvider, SearchHit } from '@amber/deepread-domain';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.NewsNow';
const ENDPOINT: string = 'https://newsnow.busiyi.world/api/s';

// 热榜 ID → 显示名(对照 Android NewsNowPresets.ALL)
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

// NewsNow API 返回的单条
interface NewsNowItem {
  title: string;
  url: string;
}

// NewsNow API 响应
interface NewsNowResponse {
  code: number;
  data: NewsNowItem[];
}

class NewsNowProvider implements SearchProvider {
  readonly name: string = 'newsnow';
  private http: HttpClient;

  constructor(http: HttpClient) {
    this.http = http;
  }

  async search(queries: string[]): Promise<SearchHit[]> {
    const hits: SearchHit[] = [];
    // 对每个热榜源抓取,用 query 关键词匹配标题
    for (let pi = 0; pi < PRESETS.length; pi++) {
      const preset: NewsNowPreset = PRESETS[pi];
      try {
        const req: HttpRequest = {
          url: `${ENDPOINT}?id=${preset.id}`,
          method: 'GET',
          headers: {},
        };
        const resp: HttpResponse = await this.http.fetch(req);
        if (resp.status !== 200) {
          continue;
        }
        const data: NewsNowResponse = JSON.parse(resp.body) as NewsNowResponse;
        if (data.data === undefined || data.data === null) {
          continue;
        }
        // 匹配:query 关键词出现在 title 里就命中
        for (let qi = 0; qi < queries.length; qi++) {
          const q: string = queries[qi].toLowerCase();
          for (let ii = 0; ii < data.data.length; ii++) {
            const item: NewsNowItem = data.data[ii];
            if (item.title.toLowerCase().includes(q) || q.includes(item.title.toLowerCase().substring(0, 8))) {
              const hit: SearchHit = {
                title: item.title,
                url: item.url,
                snippet: `${preset.displayName} · ${item.title}`,
                source: 'newsnow',
              };
              hits.push(hit);
            }
          }
        }
        // 每个源最多取前 3 条匹配,避免单源刷屏
        // (简化:不限数量,interleaveSearchResults 会 cap)
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'NewsNow fetch failed for %{public}s: %{public}s', preset.id, String(e));
      }
    }
    return hits;
  }
}

export const createNewsNowProvider = (http: HttpClient): SearchProvider => {
  return new NewsNowProvider(http);
};

// 导出热榜列表(供 UI 做热点入口选择)
export { PRESETS as NEWSNOW_PRESETS };
export type { NewsNowPreset };

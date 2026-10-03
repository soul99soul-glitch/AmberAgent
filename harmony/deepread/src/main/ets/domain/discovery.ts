export type DeepReadDiscoveryCategory = 'AI · 英文源' | '社交热搜' | '科技数码' | '财经' | '体育';
export interface DeepReadDiscoverySource {
    id: string;
    displayName: string;
    category: DeepReadDiscoveryCategory;
    feedUrls?: string[];
    defaultEnabled?: boolean;
}
export interface DeepReadDiscoveryItem {
    rank: number;
    title: string;
    url: string;
    heat: string;
    summary?: string;
}
export const DEEPREAD_DISCOVERY_CATEGORIES: DeepReadDiscoveryCategory[] = ['AI · 英文源', '社交热搜', '科技数码', '财经', '体育'];
export const DEEPREAD_DISCOVERY_SOURCES: DeepReadDiscoverySource[] = [
    { id: 'hacker_news', displayName: 'Hacker News', category: 'AI · 英文源' },
    { id: 'arxiv_ai', displayName: 'Arxiv AI', category: 'AI · 英文源', feedUrls: ['https://rss.arxiv.org/rss/cs.AI', 'https://rss.arxiv.org/rss/cs.CL'] },
    { id: 'infoq_ai', displayName: 'InfoQ AI', category: 'AI · 英文源', feedUrls: ['https://feed.infoq.com/ai-ml-data-eng/'] },
    { id: 'huggingface_papers', displayName: 'HuggingFace Papers', category: 'AI · 英文源' },
    { id: 'github_trending_ai', displayName: 'GitHub AI', category: 'AI · 英文源' },
    { id: 'zhihu', displayName: '知乎热榜', category: '社交热搜' },
    { id: 'weibo', displayName: '微博热搜', category: '社交热搜' },
    { id: 'douyin', displayName: '抖音热搜', category: '社交热搜' },
    { id: 'bilibili-hot-search', displayName: 'B站热搜', category: '科技数码' },
    { id: 'ithome', displayName: 'IT 之家', category: '科技数码' },
    { id: 'sspai', displayName: '少数派', category: '科技数码', defaultEnabled: false },
    { id: 'juejin', displayName: '掘金', category: '科技数码', defaultEnabled: false },
    { id: '36kr-quick', displayName: '36氪快讯', category: '科技数码' },
    { id: 'coolapk', displayName: '酷安', category: '科技数码', defaultEnabled: false },
    { id: 'v2ex-share', displayName: 'V2EX', category: '科技数码' },
    { id: 'github-trending-today', displayName: 'GitHub趋势', category: '科技数码' },
    { id: 'xueqiu-hotstock', displayName: '雪球热股', category: '财经', defaultEnabled: false },
    { id: 'wallstreetcn-hot', displayName: '华尔街见闻', category: '财经' },
    { id: 'cls-telegraph', displayName: '财联社', category: '财经' },
    { id: 'hupu-zhugandaoretie', displayName: '虎扑步行街', category: '体育', defaultEnabled: false },
];

export const discoveryHttpUrl = (value: string | null | undefined): string =>
    value !== undefined && value !== null && /^https?:\/\/[^\s/?#]+(?:[/?#][^\s]*)?$/i.test(value.trim()) ? value.trim() : '';

interface HackerNewsItem { title?: string; url?: string; deleted?: boolean; dead?: boolean; score?: number; }
export const parseHackerNewsHotItem = (text: string, rank: number): DeepReadDiscoveryItem | null => {
    const item: HackerNewsItem = JSON.parse(text) as HackerNewsItem;
    if (item === null || item.deleted === true || item.dead === true || typeof item.title !== 'string' || item.title.trim().length === 0) return null;
    return { rank, title: item.title.trim(), url: discoveryHttpUrl(item.url), heat: Number.isFinite(item.score) ? `${item.score} points` : '' };
};
interface HuggingFacePaper { paper?: { id?: string; title?: string; summary?: string }; upvotes?: number; }
export const parseHuggingFaceHotItems = (text: string, limit: number = 12): DeepReadDiscoveryItem[] => {
    const papers: HuggingFacePaper[] = JSON.parse(text) as HuggingFacePaper[];
    if (!Array.isArray(papers)) throw new Error('HuggingFace 榜单格式无效');
    const result: DeepReadDiscoveryItem[] = [];
    for (let index = 0; index < papers.length && result.length < limit; index++) {
        const entry = papers[index];
        if (entry === null || typeof entry.paper?.title !== 'string' || entry.paper.title.trim().length === 0) continue;
        const id = entry.paper.id;
        result.push({ rank: index + 1, title: entry.paper.title.trim(),
            url: typeof id === 'string' && /^[a-zA-Z0-9._-]+$/.test(id) ? `https://huggingface.co/papers/${id}` : '',
            heat: Number.isFinite(entry.upvotes) ? `${entry.upvotes} votes` : '',
            summary: typeof entry.paper.summary === 'string' ? entry.paper.summary.trim() : undefined });
    }
    return result;
};
const htmlText = (text: string): string => text.replace(/<[^>]+>/g, ' ').replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/\s+/g, ' ').trim();
export const parseGithubTrendingHotItems = (html: string, limit: number = 12): DeepReadDiscoveryItem[] => {
    const blocks: string[] = html.match(/<article\b[^>]*>[\s\S]*?<\/article>/gi) ?? [html];
    const result: DeepReadDiscoveryItem[] = [];
    for (const block of blocks) {
        const pattern = /<h2\b[^>]*>[\s\S]*?<a\b[^>]*href=["'](\/[^"']+)["'][^>]*>/gi;
        let match = pattern.exec(block);
        while (match !== null && result.length < limit) {
            const path = match[1];
            if (/^\/[a-zA-Z0-9_.-]+\/[a-zA-Z0-9_.-]+$/.test(path) && !result.some(item => item.url === `https://github.com${path}`)) {
                const description = block.match(/<p\b[^>]*>([\s\S]*?)<\/p>/i)?.[1];
                result.push({ rank: result.length + 1, title: path.slice(1), url: `https://github.com${path}`, heat: '',
                    summary: description === undefined ? undefined : htmlText(description) });
            }
            match = pattern.exec(block);
        }
        if (result.length >= limit) break;
    }
    return result;
};

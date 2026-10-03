// HotListAggregator — 跨源热榜话题聚合(基于 Android HotListAggregator.kt)
//
// 标题归一化(去标点/空白折叠/小写)→ 实体抽取(别名词典 + 英文 token + 中文连续段)
// → 实体 Jaccard≥0.5 且交集≥2 判定;中文同语言标题另保留 bigram 相似匹配
// → 分组合并出 HotTopic(sources/sourceCount/bestRank/latestFetchedAt)
// → 排序:sourceCount↓ → bestRank↑ → latestFetchedAt↓,取 limit。

import type { SourceSection, HotItem } from './NewsNowProvider.ets';

export interface HotTopicSource {
  providerId: string;
  providerName: string;
  rank: number;
  title: string;
  url: string;
  heat: string;
  displayTitle?: string;
  summary?: string;
}

export interface HotTopic {
  id: string;
  title: string;
  displayTitle?: string;
  sources: HotTopicSource[];
  sourceCount: number;
  bestRank: number;
  latestFetchedAt: number;
}

interface Candidate {
  providerId: string;
  providerName: string;
  fetchedAt: number;
  providerItemCount: number;
  item: HotItem;
  normalizedTitle: string;
  entities: Set<string>;
}

// 别名词典(Android DEFAULT_ALIASES 全量;alias → canonical)
const DEFAULT_ALIASES: Record<string, string> = {
  '马斯克': 'elon_musk', 'musk': 'elon_musk', 'elon': 'elon_musk', 'elonmusk': 'elon_musk',
  'openai': 'openai', 'chatgpt': 'openai', 'gpt': 'openai',
  '人工智能': 'ai', 'llm': 'llm', '大模型': 'llm',
  'agent': 'agent', '智能体': 'agent',
  'claude': 'claude', 'anthropic': 'claude',
  'deepseek': 'deepseek', 'gemini': 'gemini',
  '机器人': 'robotics', 'robot': 'robotics', 'robotics': 'robotics',
  '具身智能': 'embodied_ai', '自动驾驶': 'autonomous_driving',
  '芯片': 'chip', '半导体': 'semiconductor',
  '数码': 'digital', '3c': 'digital', '智能硬件': 'smart_hardware',
  'gpt5': 'gpt_5', 'gpt-5': 'gpt_5',
  '苹果': 'apple', 'apple': 'apple', 'iphone': 'iphone', 'ios': 'ios',
  '谷歌': 'google', 'google': 'google',
  '微软': 'microsoft', 'microsoft': 'microsoft',
  'tesla': 'tesla', '特斯拉': 'tesla',
  '英伟达': 'nvidia', 'nvidia': 'nvidia',
};

const ENGLISH_STOPWORDS: string[] = ['the', 'and', 'for', 'with', 'from', 'this', 'that', 'news'];
const CHINESE_STOPWORDS: string[] = ['热搜', '最新', '今日', '官方', '回应', '发布', '宣布', '网友', '视频'];

const normalizeTitle = (raw: string): string =>
  raw.toLowerCase()
    .replace(/[#【】\[\]()《》"'“”‘’!！?？,，。.:：;；/\\|_\-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

const isChinese = (ch: string): boolean => ch >= '\u4e00' && ch <= '\u9fff';
const hasChinese = (s: string): boolean => {
  for (let i = 0; i < s.length; i++) {
    if (isChinese(s.charAt(i))) return true;
  }
  return false;
};

const extractEntities = (title: string): Set<string> => {
  const normalized: string = normalizeTitle(title);
  const entities: string[] = [];
  for (const alias of Object.keys(DEFAULT_ALIASES)) {
    if (normalized.includes(alias) && entities.indexOf(DEFAULT_ALIASES[alias]) < 0) {
      entities.push(DEFAULT_ALIASES[alias]);
    }
  }
  // 英文 token([a-z][a-z0-9.+-]*,去尾点,≥2,滤停用词)
  const lower: string = title.toLowerCase();
  const tokenRe: RegExp = /[a-z][a-z0-9.+-]*/g;
  let token: string = tokenRe.exec(lower)?.[0] ?? '';
  while (token.length > 0) {
    const cleaned: string = token.replace(/\.$/, '');
    if (cleaned.length >= 2 && ENGLISH_STOPWORDS.indexOf(cleaned) < 0
      && entities.indexOf(cleaned) < 0) {
      entities.push(cleaned);
    }
    token = tokenRe.exec(lower)?.[0] ?? '';
  }
  // 中文连续段(长度 2-10,滤停用词)
  const chunkRe: RegExp = /[\u4e00-\u9fff]{2,10}/g;
  let chunk: string = chunkRe.exec(title)?.[0] ?? '';
  while (chunk.length > 0) {
    if (CHINESE_STOPWORDS.indexOf(chunk) < 0 && entities.indexOf(chunk) < 0) {
      entities.push(chunk);
    }
    chunk = chunkRe.exec(title)?.[0] ?? '';
  }
  return new Set<string>(entities);
};

const bigramsOf = (s: string): string[] => {
  const out: string[] = [];
  for (let i = 0; i + 1 < s.length; i++) {
    out.push(s.substring(i, i + 2));
  }
  return out;
};

const bigramJaccard = (left: string, right: string): number => {
  const a: string[] = bigramsOf(left);
  const b: string[] = bigramsOf(right);
  if (a.length === 0 || b.length === 0) return 0;
  let inter: number = 0;
  for (const g of a) {
    if (b.indexOf(g) >= 0) inter++;
  }
  const union: number = a.length + b.length - inter;
  return union === 0 ? 0 : inter / union;
};

const areSameTopic = (left: Candidate, right: Candidate): boolean => {
  if (left.normalizedTitle.length > 0 && left.normalizedTitle === right.normalizedTitle) return true;
  let shared: number = 0;
  left.entities.forEach((e: string): void => {
    if (right.entities.has(e)) shared++;
  });
  // AI/agent 或出版社/电讯前缀等两词不能代表同一事件。
  // 以既有 token/别名集合的重合比例判断，避免把相关领域的文章变成同一研究输入。
  const union: number = left.entities.size + right.entities.size - shared;
  const entityMatch: boolean = shared >= 2 && shared / union >= 0.5;
  if (!hasChinese(left.normalizedTitle) && !hasChinese(right.normalizedTitle)) return entityMatch;
  if (entityMatch) return true;
  if (shared === 1 && left.normalizedTitle === right.normalizedTitle) return true;
  if (hasChinese(left.item.title) === hasChinese(right.item.title)) {
    if (bigramJaccard(left.normalizedTitle, right.normalizedTitle) >= 0.4
      && left.normalizedTitle.length >= 6 && right.normalizedTitle.length >= 6) return true;
  }
  return false;
};

// 组内选标题:源条目数多者优先 → rank 小者 → 标题长者
const chooseTitle = (group: Candidate[]): Candidate => {
  let best: Candidate = group[0];
  for (const c of group) {
    if (c.providerItemCount > best.providerItemCount
      || (c.providerItemCount === best.providerItemCount && c.item.rank < best.item.rank)
      || (c.providerItemCount === best.providerItemCount && c.item.rank === best.item.rank
        && c.item.title.length > best.item.title.length)) {
      best = c;
    }
  }
  return best;
};

export const aggregateHotTopics = (
  sections: SourceSection[], limit: number,
): HotTopic[] => {
  const candidates: Candidate[] = [];
  for (const sec of sections) {
    for (const item of sec.items) {
      candidates.push({
        providerId: sec.sourceId,
        providerName: sec.sourceName,
        fetchedAt: sec.fetchedAt,
        providerItemCount: sec.items.length,
        item: item,
        // 翻译只用于显示，不能改变原始话题身份及其研究来源分组。
        normalizedTitle: normalizeTitle(item.title),
        entities: extractEntities(item.title),
      });
    }
  }
  const groups: Candidate[][] = [];
  for (const c of candidates) {
    let placed: boolean = false;
    for (const g of groups) {
      let hit: boolean = false;
      for (const e of g) {
        if (areSameTopic(e, c)) {
          hit = true;
          break;
        }
      }
      if (hit) {
        g.push(c);
        placed = true;
        break;
      }
    }
    if (!placed) groups.push([c]);
  }

  const topics: HotTopic[] = groups.map((g: Candidate[]): HotTopic => {
    const sources: HotTopicSource[] = g.map((c: Candidate): HotTopicSource => ({
      providerId: c.providerId,
      providerName: c.providerName,
      rank: c.item.rank,
      title: c.item.title,
      url: c.item.url,
      heat: c.item.heat,
      summary: c.item.summary,
      displayTitle: c.item.displayTitle,
    }));
    let bestRank: number = sources[0].rank;
    let latest: number = g[0].fetchedAt;
    const providerIds: string[] = [];
    for (const s of sources) {
      if (s.rank < bestRank) bestRank = s.rank;
      if (s.providerId !== undefined && providerIds.indexOf(s.providerId) < 0) {
        providerIds.push(s.providerId);
      }
    }
    for (const c of g) {
      if (c.fetchedAt > latest) latest = c.fetchedAt;
    }
    const chosen: Candidate = chooseTitle(g);
    return {
      id: chosen.item.url.length > 0 ? chosen.item.url : chosen.item.title,
      title: chosen.item.title,
      displayTitle: chosen.item.displayTitle,
      sources: sources,
      sourceCount: providerIds.length,
      bestRank: bestRank,
      latestFetchedAt: latest,
    };
  });

  topics.sort((a: HotTopic, b: HotTopic): number => {
    if (a.sourceCount !== b.sourceCount) return b.sourceCount - a.sourceCount;
    if (a.bestRank !== b.bestRank) return a.bestRank - b.bestRank;
    return b.latestFetchedAt - a.latestFetchedAt;
  });
  return topics.slice(0, Math.max(0, limit));
};

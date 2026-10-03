import type { HotItem, SourceSection } from './NewsNowProvider.ets';
import type { HotTopic } from './HotListAggregator.ets';

export interface HotListCacheSnapshot {
  sections: SourceSection[];
}

export const parseHotListFocusKeywords = (raw: string): string[] => {
  const keywords: string[] = [];
  const seen: string[] = [];
  for (const part of raw.split(/[,，、;；\n\t]/)) {
    const keyword: string = part.trim();
    if (keyword.length === 0 || seen.includes(keyword.toLowerCase())) continue;
    keywords.push(keyword);
    seen.push(keyword.toLowerCase());
  }
  return keywords.slice(0, 80);
};

export const hotTopicSeedUrls = (topic: HotTopic): string[] => {
  const urls: string[] = [];
  for (const source of topic.sources) {
    const url: string = source.url.trim();
    if (/^https?:\/\//i.test(url) && !urls.includes(url)) urls.push(url);
  }
  return urls;
};

const copyItem = (item: HotItem, displayTitle?: string): HotItem => {
  const copy: HotItem = { rank: item.rank, title: item.title, url: item.url, heat: item.heat };
  if (item.summary !== undefined) copy.summary = item.summary;
  if (displayTitle !== undefined && displayTitle.trim().length > 0) copy.displayTitle = displayTitle;
  return copy;
};

const copySection = (section: SourceSection, items: HotItem[]): SourceSection => ({
  sourceId: section.sourceId,
  sourceName: section.sourceName,
  items,
  fetchedAt: section.fetchedAt,
  error: section.error,
  stale: section.stale === true,
});

// 只缓存原始榜单；源开关、关注和翻译开关每次从原始数据生成新投影。
export const projectHotListSections = (
  raw: SourceSection[], enabledIds: string[], keywords: string[], mode: string, translate: boolean,
): SourceSection[] => {
  const sections: SourceSection[] = [];
  for (const section of raw) {
    if (!enabledIds.includes(section.sourceId)) continue;
    const items: HotItem[] = section.items.map((item: HotItem): HotItem =>
      copyItem(item, translate ? item.displayTitle : undefined));
    if (keywords.length === 0 || mode === 'all') {
      sections.push(copySection(section, items));
      continue;
    }
    const matches = (item: HotItem): boolean => {
      const text: string = [item.title, item.displayTitle ?? '', section.sourceName, item.heat, item.url]
        .join(' ').toLowerCase();
      return keywords.some((keyword: string): boolean => containsKeyword(text, keyword));
    };
    const matched: HotItem[] = items.filter(matches);
    const visible: HotItem[] = mode === 'focus_only'
      ? matched : matched.concat(items.filter((item: HotItem): boolean => !matches(item)));
    sections.push(copySection(section, visible));
  }
  return sections;
};

const containsKeyword = (text: string, keyword: string): boolean => {
  const key: string = keyword.toLowerCase();
  if (key.length === 0) return false;
  if (key.length > 3 || !/^[a-z0-9+\-]+$/.test(key)) return text.includes(key);
  let offset: number = text.indexOf(key);
  while (offset >= 0) {
    const before: string = offset > 0 ? text.charAt(offset - 1) : '';
    const after: string = text.charAt(offset + key.length);
    if (!/[a-z0-9+\-]/.test(before) && !/[a-z0-9+\-]/.test(after)) return true;
    offset = text.indexOf(key, offset + 1);
  }
  return false;
};

export const hotTopicMatchesFocus = (topic: HotTopic, keywords: string[]): boolean => {
  const titles: string[] = [topic.title, topic.displayTitle ?? ''];
  for (const source of topic.sources) {
    titles.push(source.title, source.displayTitle ?? '', source.providerName, source.heat);
  }
  const text: string = titles.join(' ').toLowerCase();
  return keywords.some((keyword: string): boolean => containsKeyword(text, keyword));
};

export const mergeHotListSections = (previous: SourceSection[], fetched: SourceSection[]): SourceSection[] => {
  const result: SourceSection[] = previous.slice();
  for (const next of fetched) {
    const index: number = result.findIndex((section: SourceSection): boolean => section.sourceId === next.sourceId);
    const old: SourceSection | undefined = index >= 0 ? result[index] : undefined;
    let merged: SourceSection;
    if (next.error !== null && next.items.length === 0 && old !== undefined && old.items.length > 0) {
      merged = copySection(old, old.items.map((item: HotItem): HotItem => copyItem(item, item.displayTitle)));
      merged.error = next.error;
      merged.stale = true;
    } else {
      const items: HotItem[] = next.items.map((item: HotItem): HotItem => {
        const cached: HotItem | undefined = old?.items.find((candidate: HotItem): boolean =>
          candidate.title === item.title && candidate.url === item.url);
        return copyItem(item, item.displayTitle ?? cached?.displayTitle);
      });
      merged = copySection(next, items);
      merged.stale = false;
    }
    if (index >= 0) result[index] = merged;
    else result.push(merged);
  }
  return result;
};

export const shouldRefreshHotList = (
  raw: SourceSection[], enabledIds: string[], intervalMinutes: number, now: number,
): boolean => {
  const interval: number = Math.max(15, intervalMinutes) * 60_000;
  for (const id of enabledIds) {
    const section: SourceSection | undefined = raw.find((candidate: SourceSection): boolean => candidate.sourceId === id);
    if (section === undefined || section.error !== null || section.fetchedAt <= 0
      || now - section.fetchedAt >= interval) return true;
  }
  return false;
};

export const latestHotListUpdate = (raw: SourceSection[], enabledIds: string[]): number => {
  let latest: number = 0;
  for (const section of raw) {
    if (enabledIds.includes(section.sourceId) && (section.error === null || section.items.length > 0)) {
      latest = Math.max(latest, section.fetchedAt);
    }
  }
  return latest;
};

export const untranslatedHotListTitles = (raw: SourceSection[], enabledIds: string[]): string[] => {
  const titles: string[] = [];
  for (const section of raw) {
    if (!enabledIds.includes(section.sourceId)) continue;
    for (const item of section.items) {
      if (!/[\u4e00-\u9fff]/.test(item.title) && item.title.trim().length > 0
        && (item.displayTitle === undefined || item.displayTitle.trim().length === 0)
        && !titles.includes(item.title)) titles.push(item.title);
    }
  }
  return titles;
};

export const withHotListTitleTranslations = (
  raw: SourceSection[], translations: Record<string, string>,
): SourceSection[] => raw.map((section: SourceSection): SourceSection => copySection(section,
  section.items.map((item: HotItem): HotItem => copyItem(item, translations[item.title] ?? item.displayTitle))));

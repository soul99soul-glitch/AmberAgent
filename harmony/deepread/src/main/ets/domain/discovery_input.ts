import { makeInputSource } from './input_sources.ts';
import type { DeepReadInputSource } from './input_sources.ts';
import { discoveryHttpUrl } from './discovery.ts';

export interface DeepReadHotspot {
    title: string;
    source: string;
    rank: number;
    url: string | null;
    summary?: string;
}
export const discoveryHotspotInputs = (items: DeepReadHotspot[]): DeepReadInputSource[] => items
    .filter(item => item.title.trim().length > 0).map(item => {
        const url = discoveryHttpUrl(item.url);
        const content = [item.title.trim(), `来源：${item.source}`, `排名：${item.rank}`,
            item.summary?.trim() ? `摘要：${item.summary.trim()}` : ''].filter(line => line.length > 0).join('\n');
        const input = makeInputSource('text', `${item.source} · ${item.title.trim()}`, content, url || null);
        input.researchSource = { sourceId: input.id, title: item.title.trim(), url, source: item.source,
            evidenceText: '', credibility: 'low', freshness: 'recent', publishedAt: null, imageCandidates: [] };
        return input;
    });
const digest = (value: string): string => {
    let hash = 2166136261;
    for (let index = 0; index < value.length; index++) {
        hash ^= value.charCodeAt(index);
        hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16).padStart(8, '0');
};
export const discoveryArticleParams = (
    title: string, inputs: DeepReadInputSource[], urls: string[], force: boolean = false,
): Record<string, string> => {
    const seeds = urls.map(discoveryHttpUrl).filter((url, index, all) => url.length > 0 && all.indexOf(url) === index);
    const identity = JSON.stringify({ title: title.trim(), sources: inputs.map(input => ({ title: input.title, url: input.url })) });
    const params: Record<string, string> = { topicId: seeds[0] ?? `discovery-${digest(identity)}`, title,
        inputSourcesJson: JSON.stringify(inputs), seedUrlsJson: JSON.stringify(seeds) };
    if (seeds.length > 0) params['sourceUrl'] = seeds[0];
    if (force) params['force'] = 'true';
    return params;
};

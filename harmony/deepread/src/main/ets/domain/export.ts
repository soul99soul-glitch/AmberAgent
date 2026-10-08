import { synthesisArticleMarkdown } from './synthesis_article.ts';
// Export the saved article, including chapters that are absent from a chosen template.
import type { DeepReadCacheEntry } from '../platform/repository.ts';
import type { DeepReadImageAsset, ReadingLink } from './models.ts';
import { displayHeroCaption, displayHeroImageUrl, isComplete } from './helpers.ts';
import { IMAGE_CONFIDENCE } from './enums.ts';

const safeUrl = (raw: string): string => /^https?:\/\/[^\s<>"']+$/i.test(raw.trim()) ? raw.trim() : '';
const escapeMarkdown = (raw: string): string => raw.replace(/([\\`*_{}\[\]()<>#!|])/g, '\\$1');
const inline = (raw: string, markdown: boolean): string => markdown ? escapeMarkdown(raw) : raw;
const heading = (text: string, markdown: boolean, level: number = 2): string =>
  markdown ? `${'#'.repeat(level)} ${escapeMarkdown(text)}` : text;

const sourceIds = (ids: number[] | undefined): string =>
  (ids?.length ?? 0) > 0 ? ' ' + (ids ?? []).map(id => `[${id}]`).join('') : '';

const imageText = (url: string | null, caption: string | null, markdown: boolean): string => {
  const safe: string = url === null ? '' : safeUrl(url);
  const parts: string[] = [];
  if (safe.length > 0) {
    parts.push(markdown ? `![${escapeMarkdown(caption ?? '')}](<${safe}>)` : `图片：${safe}`);
  }
  if (caption !== null && caption.trim().length > 0) parts.push(inline(caption, markdown));
  return parts.join('\n\n');
};

const readingLinkText = (link: ReadingLink, markdown: boolean): string => {
  const safe: string = safeUrl(link.url);
  const title: string = link.title.trim().length > 0 ? link.title : link.url;
  const label: string = inline(title, markdown);
  let text: string = markdown && safe.length > 0 ? `[${label}](<${safe}>)` : label;
  if (!markdown && link.url.length > 0 && link.url !== title) text += `\n${link.url}`;
  // Unsafe URLs remain visible as text, never as executable Markdown links.
  if (markdown && safe.length === 0 && link.url.length > 0 && link.url !== title) {
    text += `\n${escapeMarkdown(link.url)}`;
  }
  if (link.source !== null && link.source.length > 0) text += `\n来源：${inline(link.source, markdown)}`;
  if (link.publishedAt !== null && link.publishedAt.length > 0) text += `\n发布时间：${inline(link.publishedAt, markdown)}`;
  return text;
};

const assetText = (asset: DeepReadImageAsset, markdown: boolean): string => {
  const parts: string[] = [imageText(asset.url, asset.caption, markdown)];
  if (asset.source !== null && asset.source.length > 0) parts.push(`图片来源：${inline(asset.source, markdown)}`);
  return parts.join('\n\n');
};

const serializeArticle = (entry: DeepReadCacheEntry, markdown: boolean,
  renderProse: (source: string) => string = (source: string): string => source): string => {
  const prose: (source: string) => string = (source: string): string => markdown ? source : renderProse(source);
  const output = entry.output;
  if (output.templateArticle !== undefined) {
    const article = output.templateArticle;
    if (markdown) return synthesisArticleMarkdown(article);
    // Source metadata is literal text; parsing it as prose loses URLs and can alter titles.
    const sources: string = article.sources.map(source =>
      `[${source.id}] ${source.title}${source.site ? ` · ${source.site}` : ''}${source.url ? `\n${source.url}` : ''}`).join('\n\n');
    return `${renderProse(synthesisArticleMarkdown(article, false))}\n\n来源\n\n${sources}`.trim();
  }
  const parts: string[] = [heading(entry.title, markdown, 1),
    `主题类型：${inline(output.topicType, markdown)}`,
    `生成状态：${isComplete(output) ? '完整' : '部分稿'}`,
    `主题 ID：${inline(entry.topicId, markdown)}`,
    `保存时间：${new Date(entry.updatedAt).toISOString()}`];
  if (entry.sourceUrl !== null && entry.sourceUrl.length > 0) {
    parts.push(`主题来源：${inline(entry.sourceUrl, markdown)}`);
  }
  const hasNewHierarchy: boolean = (output.bottomLine?.trim().length ?? 0) > 0
    || (output.sources?.length ?? 0) > 0 || (output.impacts?.length ?? 0) > 0
    || (output.watch?.length ?? 0) > 0 || (output.uncertainties?.length ?? 0) > 0
    || (output.corePoints ?? []).some(point => (point.sources?.length ?? 0) > 0)
    || (output.timeline ?? []).some(event => (event.why?.length ?? 0) > 0)
    || output.analysis.perspectives.some(perspective => (perspective.interest?.length ?? 0) > 0
      || (perspective.quote?.length ?? 0) > 0 || (perspective.quoteBy?.length ?? 0) > 0
      || (perspective.sources?.length ?? 0) > 0);
  if ((output.bottomLine?.trim().length ?? 0) > 0) {
    parts.push(heading('一句话结论', markdown), prose(output.bottomLine ?? ''));
  }
  if (output.summary.trim().length > 0) parts.push(heading('摘要', markdown), prose(output.summary));
  // Entity chips belong to older articles; do not reintroduce them into the new hierarchy.
  if (!hasNewHierarchy && output.keyEntities.length > 0) {
    parts.push(heading('关键实体', markdown), output.keyEntities.map(entity => `- ${inline(entity, markdown)}`).join('\n'));
  }

  const usedImages: Set<string> = new Set<string>();
  const hero: string | null = displayHeroImageUrl(output);
  if (hero !== null) {
    parts.push(heading('主图', markdown), imageText(hero, displayHeroCaption(output, hero), markdown));
    usedImages.add(hero);
  }
  if (output.corePoints !== null && output.corePoints.length > 0) {
    parts.push(heading('关键判断', markdown));
    for (let index: number = 0; index < output.corePoints.length; index++) {
      const point = output.corePoints[index];
      parts.push(heading(`要点 ${index + 1}`, markdown, 3), prose(point.point) + sourceIds(point.sources));
      if (point.supporting !== null && point.supporting.length > 0) parts.push(prose(point.supporting));
      const image: string = imageText(point.imageUrl, point.imageCaption, markdown);
      if (image.length > 0) parts.push(image);
      if (point.imageUrl !== null) usedImages.add(point.imageUrl);
    }
  }
  if (output.timeline !== null && output.timeline.length > 0) {
    parts.push(heading('时间轴', markdown));
    for (const event of output.timeline) {
      parts.push(heading(`${event.date}${event.isHighlight ? ' · 关键节点' : ''}`, markdown, 3), prose(event.event));
      if ((event.why?.length ?? 0) > 0) parts.push(`转折：${prose(event.why ?? '')}`);
      const image: string = imageText(event.imageUrl, event.imageCaption, markdown);
      if (image.length > 0) parts.push(image);
      if (event.imageUrl !== null) usedImages.add(event.imageUrl);
    }
  }
  const analysis = output.analysis;
  if ((analysis.coreDispute?.length ?? 0) > 0 || analysis.perspectives.length > 0 || analysis.quotes.length > 0) {
    parts.push(heading('各方立场', markdown));
    if (analysis.coreDispute !== null && analysis.coreDispute.length > 0) {
      parts.push(heading('核心争议', markdown, 3), prose(analysis.coreDispute));
    }
    if (analysis.perspectives.length > 0) {
      parts.push(heading('各方观点', markdown, 3));
      for (const perspective of analysis.perspectives) {
        if (perspective.holder !== null && perspective.holder.length > 0) parts.push(inline(perspective.holder, markdown));
        if ((perspective.interest?.length ?? 0) > 0) parts.push(`诉求：${inline(perspective.interest ?? '', markdown)}`);
        parts.push(prose(perspective.viewpoint) + sourceIds(perspective.sources));
        if ((perspective.quote?.length ?? 0) > 0) {
          const quote: string = inline(perspective.quote ?? '', markdown);
          parts.push(markdown ? quote.split('\n').map(line => `> ${line}`).join('\n') : quote);
        }
        if ((perspective.quoteBy?.length ?? 0) > 0) parts.push(`— ${inline(perspective.quoteBy ?? '', markdown)}`);
      }
    }
    if (analysis.quotes.length > 0) {
      parts.push(heading('相关引述', markdown, 3));
      for (const quote of analysis.quotes) {
        parts.push(markdown ? quote.text.split('\n').map(line => `> ${line}`).join('\n') : prose(quote.text));
        if (quote.attribution !== null && quote.attribution.length > 0) parts.push(`— ${inline(quote.attribution, markdown)}`);
      }
    }
  }
  if ((output.impacts?.length ?? 0) > 0 || (output.watch?.length ?? 0) > 0
    || (analysis.implications?.length ?? 0) > 0) {
    parts.push(heading('影响与走向', markdown));
    for (const impact of output.impacts ?? []) {
      const horizon: string = impact.horizon === 'long' ? '长期' : impact.horizon === 'short' ? '短期' : '';
      parts.push(`- ${inline(impact.target, markdown)}${horizon.length > 0 ? `（${horizon}）` : ''}：${prose(impact.effect)}`);
    }
    if ((analysis.implications?.length ?? 0) > 0) parts.push(prose(analysis.implications ?? ''));
    if ((output.watch?.length ?? 0) > 0) {
      parts.push(heading('接下来关注', markdown, 3), (output.watch ?? []).map(item => `- ${prose(item)}`).join('\n'));
    }
  }
  if ((output.uncertainties?.length ?? 0) > 0) {
    parts.push(heading('待核实', markdown));
    for (const item of output.uncertainties ?? []) {
      const claim: string = typeof item === 'string' ? item : item.claim;
      const status: string = typeof item === 'string' ? '' : item.status;
      const label: string = status === 'single_source' ? '单一来源'
        : status === 'conflicting' ? '来源矛盾'
          : status === 'pending_official' ? '待官方确认' : '';
      parts.push(`- ${label.length > 0 ? `【${label}】` : ''}${prose(claim)}`);
    }
  }
  const diagram = output.diagram;
  if (diagram !== null) {
    parts.push(heading(diagram.title.length > 0 ? diagram.title : '关系图', markdown),
      `图示类型：${inline(diagram.type, markdown)}`);
    if (diagram.reason !== null && diagram.reason.length > 0) parts.push(inline(diagram.reason, markdown));
    for (const node of diagram.nodes) {
      parts.push(`- ${inline(node.label, markdown)}（${inline(node.id, markdown)}）`
        + (node.group !== null && node.group.length > 0 ? ` · ${inline(node.group, markdown)}` : ''));
      if (node.note !== null && node.note.length > 0) parts.push(inline(node.note, markdown));
    }
    for (const edge of diagram.edges) {
      const from = diagram.nodes.find(node => node.id === edge.from);
      const to = diagram.nodes.find(node => node.id === edge.to);
      // A legacy unmatched endpoint is retained by its stored ID instead of losing the relationship.
      parts.push(`${inline(from?.label ?? edge.from, markdown)} → ${inline(to?.label ?? edge.to, markdown)}`
        + (edge.label !== null && edge.label.length > 0 ? `：${inline(edge.label, markdown)}` : ''));
    }
    if (diagram.caption !== null && diagram.caption.length > 0) parts.push(inline(diagram.caption, markdown));
  }
  const gallery: DeepReadImageAsset[] = output.imageAssets.filter(asset =>
    asset.confidence !== IMAGE_CONFIDENCE.REJECT && safeUrl(asset.url).length > 0 && !usedImages.has(asset.url));
  if (gallery.length > 0) {
    parts.push(heading('相关图片', markdown));
    for (const asset of gallery) {
      if (usedImages.has(asset.url)) continue;
      usedImages.add(asset.url);
      parts.push(assetText(asset, markdown));
    }
  }
  // Captions/source on reused images still belong to the saved output.
  for (const asset of output.imageAssets) {
    if (asset.confidence === IMAGE_CONFIDENCE.REJECT || safeUrl(asset.url).length === 0 || gallery.includes(asset)) continue;
    if (asset.caption !== null && asset.caption.length > 0) {
      parts.push(`图片说明：${inline(asset.caption, markdown)}`);
    }
    if (asset.source !== null && asset.source.length > 0) parts.push(`图片来源：${inline(asset.source, markdown)}`);
  }
  if ((output.sources?.length ?? 0) > 0) {
    // Keep generation positions intact, including URL-less text/file sources.
    const sources: ReadingLink[] = (output.sources ?? []).slice();
    for (const link of output.references.concat(output.extendedReading)) {
      if (!sources.some(source => source.url.length > 0 && source.url === link.url
        || source.url.length === 0 && link.url.length === 0 && source.title === link.title)) sources.push(link);
    }
    parts.push(heading('来源', markdown), sources.map((link, index) =>
      `[${index + 1}] ${readingLinkText(link, markdown)}`).join('\n\n'));
  } else {
    if (output.references.length > 0) {
      parts.push(heading('参考资料', markdown), output.references.map(link => readingLinkText(link, markdown)).join('\n\n'));
    }
    if (output.extendedReading.length > 0) {
      parts.push(heading('延伸阅读', markdown), output.extendedReading.map(link => readingLinkText(link, markdown)).join('\n\n'));
    }
  }
  if ((output.inputSourceUrls?.length ?? 0) > 0) {
    parts.push(heading('研究输入', markdown), '以下是启动研究时提供的链接；这份列表不表示正文已引用这些来源。',
      (output.inputSourceUrls ?? []).map(url => `- ${inline(url, markdown)}`).join('\n'));
  }
  return parts.filter(part => part.length > 0).join('\n\n') + '\n';
};

export const deepReadToMarkdown = (entry: DeepReadCacheEntry): string => serializeArticle(entry, true);
export const deepReadToText = (entry: DeepReadCacheEntry,
  renderProse: (source: string) => string = (source: string): string => source): string =>
  serializeArticle(entry, false, renderProse);

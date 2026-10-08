// The two native editions share numbering and question order, while retaining their typography.
import type { CorePoint, DeepReadOutput, Perspective, ReadingLink } from '@amber/deepread-domain';

export const nativeArticleSectionOrder = (topicType: string): string[] => {
  if (topicType === 'opinion') return ['core', 'analysis', 'outlook', 'uncertainties', 'timeline', 'diagram'];
  if (topicType === 'product') return ['core', 'diagram', 'analysis', 'outlook', 'uncertainties', 'timeline'];
  if (topicType === 'person') return ['core', 'timeline', 'analysis', 'uncertainties', 'outlook', 'diagram'];
  return ['core', 'timeline', 'diagram', 'analysis', 'uncertainties', 'outlook'];
};

export const nativeArticleSources = (output: DeepReadOutput): ReadingLink[] =>
  (output.sources?.length ?? 0) > 0 ? output.sources as ReadingLink[] : output.references;

// A resumed draft can retain old references alongside its new numbered generation list.
// Keep those links visible without giving them an existing generation citation number.
export const nativeSupplementarySources = (output: DeepReadOutput): ReadingLink[] => {
  if ((output.sources?.length ?? 0) === 0) return [];
  const seen: string[] = [];
  for (const link of output.sources ?? []) seen.push(link.url.length > 0 ? link.url : link.title);
  const result: ReadingLink[] = [];
  for (const link of output.references.concat(output.extendedReading)) {
    const key: string = link.url.length > 0 ? link.url : link.title;
    if (seen.includes(key)) continue;
    seen.push(key);
    result.push(link);
  }
  return result;
};

export const nativeCitationNumbers = (output: DeepReadOutput, ids: number[] | undefined): number[] => {
  const count: number = output.sources?.length ?? 0;
  const seen: number[] = [];
  for (const id of ids ?? []) {
    if (Number.isInteger(id) && id > 0 && id <= count && !seen.includes(id)) seen.push(id);
  }
  return seen;
};

export const nativeArticleLinkUrl = (raw: string): string =>
  /^https?:\/\/[^\s/?#<>"']+(?:[/?#][^\s<>"']*)?$/i.test(raw.trim()) ? raw.trim() : '';

export const nativeArticleSourceCited = (output: DeepReadOutput, id: number): boolean =>
  (output.corePoints?.some((point: CorePoint): boolean => nativeCitationNumbers(output, point.sources).includes(id)) ?? false)
    || output.analysis.perspectives.some((perspective: Perspective): boolean =>
      nativeCitationNumbers(output, perspective.sources).includes(id));

export const nativeArticleHasHierarchy = (output: DeepReadOutput): boolean =>
  (output.bottomLine?.trim().length ?? 0) > 0 || (output.sources?.length ?? 0) > 0
    || (output.impacts?.length ?? 0) > 0 || (output.watch?.length ?? 0) > 0
    || (output.uncertainties?.length ?? 0) > 0
    || (output.corePoints?.some((point: CorePoint): boolean => (point.sources?.length ?? 0) > 0) ?? false)
    || output.analysis.perspectives.some((perspective: Perspective): boolean => (perspective.interest?.trim().length ?? 0) > 0
      || (perspective.quote?.trim().length ?? 0) > 0 || (perspective.sources?.length ?? 0) > 0);

// New editorial fields are parsed at the existing model JSON boundary.
import type { DeepReadImpact, DeepReadUncertainty } from '../domain/models.ts';

const objArray = (o: Record<string, unknown>, name: string): unknown[] => Array.isArray(o[name]) ? o[name] as unknown[] : [];
const objString = (o: Record<string, unknown>, name: string): string | null => {
  const value = o[name]; return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null;
};
const objObjectList = (o: Record<string, unknown>, name: string): Record<string, unknown>[] =>
  objArray(o, name).filter((value): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value));
const cleanText = (value: string, limit: number): string => {
  const text = value.replace(/\s+/g, ' ').trim();
  const cut = text.charCodeAt(limit - 1) >= 0xD800 && text.charCodeAt(limit - 1) <= 0xDBFF ? limit - 1 : limit;
  return text.length > limit ? text.substring(0, cut) : text;
};

export const parseSourceNumbers = (o: Record<string, unknown>, sourceCount: number = 0): number[] =>
  Array.from(new Set(objArray(o, 'sources').filter((value): value is number =>
    typeof value === 'number' && Number.isInteger(value) && value > 0 && (sourceCount === 0 || value <= sourceCount))));

export const parseImpacts = (o: Record<string, unknown>): DeepReadImpact[] => {
  const result: DeepReadImpact[] = [];
  for (const item of objObjectList(o, 'impacts')) {
    const effect = objString(item, 'effect'); const horizon = objString(item, 'horizon');
    if (effect === null || (horizon !== 'short' && horizon !== 'long')) continue;
    result.push({ target: cleanText(objString(item, 'target') ?? '', 80), horizon, effect: cleanText(effect, 240) });
  }
  return result.slice(0, 4);
};

export const parseUncertainties = (o: Record<string, unknown>): (string | DeepReadUncertainty)[] => {
  const result: (string | DeepReadUncertainty)[] = [];
  for (const item of objArray(o, 'uncertainties')) {
    if (typeof item === 'string') {
      const claim = cleanText(item, 180); if (claim.length > 0) result.push(claim);
    } else if (item !== null && typeof item === 'object' && !Array.isArray(item)) {
      const value = item as Record<string, unknown>; const claim = objString(value, 'claim');
      if (claim === null) continue;
      const rawStatus = objString(value, 'status');
      const status = rawStatus === 'single_source' || rawStatus === 'conflicting' || rawStatus === 'pending_official' ? rawStatus : '';
      result.push({ claim: cleanText(claim, 180), status });
    }
  }
  return result.slice(0, 4);
};


// Stable preferences compiled by the model. Sources remain the authority;
// content snapshots catch edits even if an importer preserves updatedAt.
import type { MemoryKind, MemoryRecord, MemoryScope } from './memory_models.ts';
import { isMemoryActive } from './memory_lifecycle.ts';
import { tokenizeMemoryQuery, USER_ALWAYS_ELIGIBLE_CONFIDENCE } from './memory_recall.ts';

export interface MemoryProfileItem {
  text: string;
  memoryIds: number[];
}

export interface MemoryProfileSourceSnapshot {
  id: number;
  content: string;
  updatedAt: number;
  scope: MemoryScope;
  kind: MemoryKind;
}

export interface MemoryProfile {
  items: MemoryProfileItem[];
  sources: MemoryProfileSourceSnapshot[];
  evaluatedSources: MemoryProfileSourceSnapshot[];
  generatedAt: number;
}

export interface MemoryProfileSelection {
  prompt: string;
  coveredIds: number[];
}

export interface MemoryProfileStorage {
  snapshotProfile: () => MemoryProfile | null;
  saveProfile: (profile: MemoryProfile | null) => Promise<void>;
}

export const MINIMUM_MEMORY_PROFILE_SOURCES: number = 3;
export const MAX_MEMORY_PROFILE_ITEMS: number = 12;
const MAX_ITEM_CHARS: number = 120;
const MAX_PROFILE_CHARS: number = 800;

export const memoryProfileSources = (
  records: MemoryRecord[], scopes: MemoryScope[], now: number,
): MemoryRecord[] => records.filter((record: MemoryRecord): boolean =>
  scopes.indexOf(record.scope) >= 0 && isMemoryActive(record, now) && !record.pinned &&
  record.scope !== 'core' && record.expiresAt === null &&
  (record.kind === 'feedback' || (record.kind === 'user' &&
    record.scope === 'long_term' && record.confidence >= USER_ALWAYS_ELIGIBLE_CONFIDENCE)));

const snapshotSource = (record: MemoryRecord): MemoryProfileSourceSnapshot => ({
  id: record.id, content: record.content, updatedAt: record.updatedAt,
  scope: record.scope, kind: record.kind,
});

const matchesSnapshot = (record: MemoryRecord, source: MemoryProfileSourceSnapshot): boolean =>
  record.id === source.id && record.content === source.content &&
  record.updatedAt === source.updatedAt && record.scope === source.scope && record.kind === source.kind;

export const buildMemoryProfile = (
  items: MemoryProfileItem[], sources: MemoryRecord[], now: number,
): MemoryProfile | null => {
  if (sources.length < MINIMUM_MEMORY_PROFILE_SOURCES) return null;
  const byId: Map<number, MemoryRecord> = new Map<number, MemoryRecord>();
  for (const source of sources) byId.set(source.id, source);
  const kept: MemoryProfileItem[] = [];
  const covered: Set<number> = new Set<number>();
  let totalChars: number = 0;
  for (const item of items.slice(0, MAX_MEMORY_PROFILE_ITEMS)) {
    const text: string = item.text.replace(/[\r\n]+/g, ' ').replace(/[<>]/g, '').trim();
    if (text.length === 0 || text.length > MAX_ITEM_CHARS || totalChars + text.length > MAX_PROFILE_CHARS) continue;
    const tokens: Set<string> = tokenizeMemoryQuery(text);
    const ids: number[] = [];
    for (const id of item.memoryIds) {
      const source: MemoryRecord | undefined = byId.get(id);
      if (source === undefined || ids.indexOf(id) >= 0) continue;
      const sourceTokens: Set<string> = tokenizeMemoryQuery(source.content);
      let grounded: boolean = false;
      for (const token of tokens) {
        if (sourceTokens.has(token)) { grounded = true; break; }
      }
      if (grounded) ids.push(id);
    }
    if (ids.length === 0) continue;
    kept.push({ text, memoryIds: ids });
    for (const id of ids) covered.add(id);
    totalChars += text.length;
  }
  if (kept.length === 0) return null;
  return {
    items: kept,
    sources: sources.filter((record: MemoryRecord): boolean => covered.has(record.id)).map(snapshotSource),
    evaluatedSources: sources.map(snapshotSource),
    generatedAt: now,
  };
};

const validSourceSnapshot = (source: MemoryProfileSourceSnapshot): boolean =>
  source != null && Number.isSafeInteger(source.id) && source.id > 0 &&
  source.content != null && String(source.content) === source.content && Number.isFinite(source.updatedAt) &&
  (source.scope === 'core' || source.scope === 'short_term' || source.scope === 'long_term') &&
  (source.kind === 'user' || source.kind === 'feedback');

const validMemoryProfile = (profile: MemoryProfile | null): boolean => {
  if (profile === null || !Array.isArray(profile.items) || !Array.isArray(profile.sources) ||
    !Array.isArray(profile.evaluatedSources) || !Number.isFinite(profile.generatedAt) ||
    profile.items.length === 0 || profile.items.length > MAX_MEMORY_PROFILE_ITEMS || profile.sources.length === 0) return false;
  if (!profile.sources.every(validSourceSnapshot) || !profile.evaluatedSources.every(validSourceSnapshot)) return false;
  let totalChars: number = 0;
  for (const item of profile.items) {
    if (item == null || item.text == null || String(item.text) !== item.text ||
      item.text.trim().length === 0 || item.text.length > MAX_ITEM_CHARS || /[<>\r\n]/.test(item.text) ||
      !Array.isArray(item.memoryIds) || item.memoryIds.length === 0 ||
      !item.memoryIds.every((id: number): boolean => Number.isSafeInteger(id) && id > 0)) return false;
    totalChars += item.text.length;
  }
  return totalChars <= MAX_PROFILE_CHARS;
};

export const parseStoredMemoryProfile = (text: string): MemoryProfile | null => {
  try {
    const profile: MemoryProfile = JSON.parse(text) as MemoryProfile;
    return validMemoryProfile(profile) ? profile : null;
  } catch (_error) { return null; }
};

export const coveredMemoryProfileRecords = (
  profile: MemoryProfile | null, records: MemoryRecord[], scopes: MemoryScope[], now: number,
): MemoryRecord[] | null => {
  if (profile === null || !validMemoryProfile(profile)) return null;
  const byId: Map<number, MemoryRecord> = new Map<number, MemoryRecord>();
  for (const record of memoryProfileSources(records, scopes, now)) byId.set(record.id, record);
  const covered: MemoryRecord[] = [];
  const ids: Set<number> = new Set<number>();
  for (const source of profile.sources) {
    const record: MemoryRecord | undefined = byId.get(source.id);
    if (record === undefined || !matchesSnapshot(record, source) || ids.has(source.id)) return null;
    covered.push(record);
    ids.add(source.id);
  }
  // A malformed persisted profile must never inject unattributed statements.
  const referenced: Set<number> = new Set<number>();
  for (const item of profile.items) {
    if (item.text.trim().length === 0 || item.text.length > MAX_ITEM_CHARS || /[<>\r\n]/.test(item.text) || item.memoryIds.length === 0) return null;
    for (const id of item.memoryIds) {
      if (!ids.has(id)) return null;
      referenced.add(id);
    }
  }
  if (profile.items.length > MAX_MEMORY_PROFILE_ITEMS || referenced.size !== ids.size) return null;
  return covered;
};

export const isMemoryProfileCurrent = (
  profile: MemoryProfile | null, sources: MemoryRecord[], records: MemoryRecord[],
  scopes: MemoryScope[], now: number,
): boolean => {
  if (profile === null || coveredMemoryProfileRecords(profile, records, scopes, now) === null) return false;
  const evaluated: Map<number, MemoryProfileSourceSnapshot> = new Map<number, MemoryProfileSourceSnapshot>();
  for (const source of profile.evaluatedSources) evaluated.set(source.id, source);
  return sources.every((record: MemoryRecord): boolean => {
    const source: MemoryProfileSourceSnapshot | undefined = evaluated.get(record.id);
    return source !== undefined && matchesSnapshot(record, source);
  });
};

export const selectMemoryProfile = (
  profile: MemoryProfile | null, records: MemoryRecord[], scopes: MemoryScope[],
  selectedIds: number[] | null, now: number, maxChars: number,
): MemoryProfileSelection => {
  const covered: MemoryRecord[] | null = coveredMemoryProfileRecords(profile, records, scopes, now);
  if (profile === null || covered === null || (selectedIds !== null &&
    covered.some((record: MemoryRecord): boolean => selectedIds.indexOf(record.id) < 0))) {
    return { prompt: '', coveredIds: [] };
  }
  const lines: string[] = profile.items.map((item: MemoryProfileItem): string =>
    `- ${item.text} (memory_id=${item.memoryIds.join(', ')})`);
  const prompt: string = `<user-profile>\n以下画像均有记忆来源；回答引用时使用 [[memory:编号]]。\n${lines.join('\n')}\n</user-profile>`;
  if (prompt.length > maxChars / 2) return { prompt: '', coveredIds: [] };
  return { prompt, coveredIds: covered.map((record: MemoryRecord): number => record.id) };
};

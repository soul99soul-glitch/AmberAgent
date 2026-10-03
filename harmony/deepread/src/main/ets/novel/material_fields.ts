import type { NovelMaterialFields, NovelMaterialInjectionMode } from './models.ts';

export interface NormalizedNovelMaterialFields {
  aliases: string[];
  tags: string[];
  customKind: string;
  injectionMode: NovelMaterialInjectionMode;
}

const names = (values: string[] | undefined): string[] => {
  const seen: Set<string> = new Set();
  const result: string[] = [];
  for (const value of values ?? []) {
    const clean: string = value.trim();
    const key: string = clean.toLocaleLowerCase();
    if (clean.length === 0 || seen.has(key)) continue;
    seen.add(key);
    result.push(clean);
  }
  return result;
};

export const normalizeNovelMaterialFields = (
  fields: NovelMaterialFields, enabled: boolean = true,
): NormalizedNovelMaterialFields => ({
  aliases: names(fields.aliases),
  tags: names(fields.tags),
  customKind: fields.customKind?.trim() ?? '',
  injectionMode: fields.injectionMode ?? (enabled ? 'always' : 'off'),
});

// An absent proposed field means retain the target's field, rather than clear it.
export const novelMaterialAdoptionFields = (
  proposed: NovelMaterialFields, current: NovelMaterialFields | undefined, enabled: boolean = true,
): NormalizedNovelMaterialFields => normalizeNovelMaterialFields({
  aliases: proposed.aliases ?? current?.aliases,
  tags: proposed.tags ?? current?.tags,
  customKind: proposed.customKind ?? current?.customKind,
  injectionMode: proposed.injectionMode ?? current?.injectionMode,
}, enabled);

export const optionalNovelMaterialFields = (fields: NovelMaterialFields): NovelMaterialFields => {
  const normalized: NormalizedNovelMaterialFields = normalizeNovelMaterialFields(fields);
  return {
    aliases: fields.aliases === undefined ? undefined : normalized.aliases,
    tags: fields.tags === undefined ? undefined : normalized.tags,
    customKind: fields.customKind === undefined ? undefined : normalized.customKind,
    injectionMode: fields.injectionMode,
  };
};

export interface NovelMaterialFieldsPayload {
  aliases?: unknown;
  tags?: unknown;
  customKind?: unknown;
  customName?: unknown;
  injectionMode?: unknown;
  injection?: unknown;
}

export const parseNovelMaterialFields = (payload: NovelMaterialFieldsPayload): NovelMaterialFields => {
  const list = (value: unknown, label: string): string[] | undefined => {
    if (value === undefined) return undefined;
    if (!Array.isArray(value) || value.some(item => typeof item !== 'string')) {
      throw new Error(`资料${label}必须是文本数组`);
    }
    return value as string[];
  };
  const mode: unknown = payload.injectionMode ?? payload.injection;
  if (mode !== undefined && mode !== 'always' && mode !== 'smart' && mode !== 'off') {
    throw new Error('资料注入方式必须是 always、smart 或 off');
  }
  const custom: unknown = payload.customKind ?? payload.customName;
  if (custom !== undefined && typeof custom !== 'string') throw new Error('资料自定义类别必须是文本');
  return optionalNovelMaterialFields({ aliases: list(payload.aliases, '别名'), tags: list(payload.tags, '标签'),
    customKind: custom as string | undefined, injectionMode: mode as NovelMaterialInjectionMode | undefined });
};

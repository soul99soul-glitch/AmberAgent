// injection_serialize — 提示词注入序列化(D-100)
//
// Android 基准:
//   core/model/.../Assistant.kt:144-233(InjectionPosition SerialName 蛇形;
//     PromptInjection.ModeInjection/RegexInjection 声明序 + 默认值;Lorebook)
//   ExtensionPrefs.kt:96-112(writeTo = JsonInstant.encodeToString 全量;
//     readFrom = decodeJsonOrNull ?: emptyList)
//   core/agent-utils/.../Json.kt:12-19(JsonInstant:ignoreUnknownKeys/
//     encodeDefaults=true/isLenient/allowTrailingComma)
// 存储形态(PreferencesKeys.kt:74-75 冻结键):
//   'mode_injections' = List<ModeInjection>(具体类型,无 type 判别字段)
//   'lorebooks' = List<Lorebook>(entries = List<RegexInjection> 具体类型)
// 线格式:键序 = Kotlin 声明序;encodeDefaults=true → 默认字段也落键。
// decode 容错 = kotlinx 语义:缺字段 → 默认(id 缺失 → 生成,Uuid.random() 默认);
//   未知键忽略;类型错/枚举非法/坏 JSON → 整体 null(decodeJsonOrNull)。
import type { JsonObject, JsonValue } from './json.ts';
import { newId } from './ids.ts';
import type {
  InjectionPosition, InjectionRole, Lorebook, PromptInjection, RegexInjection,
} from './prompt_injection_transformer.ts';

const POSITIONS: InjectionPosition[] = [
  'before_system_prompt', 'after_system_prompt', 'top_of_chat',
  'bottom_of_chat', 'at_depth',
];

const fail = (): never => {
  throw new Error('injection decode failed');
};

const strField = (j: JsonObject, key: string, fallback: string): string => {
  const v: JsonValue | undefined = j[key];
  if (v === undefined) return fallback;
  return typeof v === 'string' ? v : fail();
};

const numField = (j: JsonObject, key: string, fallback: number): number => {
  const v: JsonValue | undefined = j[key];
  if (v === undefined) return fallback;
  return typeof v === 'number' ? v : fail();
};

const boolField = (j: JsonObject, key: string, fallback: boolean): boolean => {
  const v: JsonValue | undefined = j[key];
  if (v === undefined) return fallback;
  return typeof v === 'boolean' ? v : fail();
};

const positionField = (j: JsonObject): InjectionPosition => {
  const v: JsonValue | undefined = j['position'];
  if (v === undefined) return 'after_system_prompt';
  if (typeof v === 'string' && POSITIONS.indexOf(v as InjectionPosition) >= 0) {
    return v as InjectionPosition;
  }
  return fail();
};

const roleField = (j: JsonObject): InjectionRole => {
  const v: JsonValue | undefined = j['role'];
  if (v === undefined) return 'user';
  if (v === 'user' || v === 'assistant') return v;
  return fail();
};

const keywordsField = (j: JsonObject): string[] => {
  const v: JsonValue | undefined = j['keywords'];
  if (v === undefined) return [];
  if (!Array.isArray(v)) return fail();
  const out: string[] = [];
  for (const item of v) {
    if (typeof item !== 'string') return fail();
    out.push(item);
  }
  return out;
};

const asObject = (v: JsonValue): JsonObject =>
  (typeof v === 'object' && v !== null && !Array.isArray(v)) ? v as JsonObject : fail();

// ===== toJson(声明序逐字;encodeDefaults 全量) =====

export const modeInjectionToJson = (mi: PromptInjection): JsonObject => ({
  id: mi.id,
  name: mi.name,
  enabled: mi.enabled,
  priority: mi.priority,
  position: mi.position,
  content: mi.content,
  injectDepth: mi.injectDepth,
  role: mi.role,
});

export const regexInjectionToJson = (ri: RegexInjection): JsonObject => ({
  id: ri.id,
  name: ri.name,
  enabled: ri.enabled,
  priority: ri.priority,
  position: ri.position,
  content: ri.content,
  injectDepth: ri.injectDepth,
  role: ri.role,
  keywords: ri.keywords,
  useRegex: ri.useRegex,
  caseSensitive: ri.caseSensitive,
  scanDepth: ri.scanDepth,
  constantActive: ri.constantActive,
});

export const lorebookToJson = (lb: Lorebook): JsonObject => ({
  id: lb.id,
  name: lb.name,
  description: lb.description,
  enabled: lb.enabled,
  entries: lb.entries.map((e: RegexInjection): JsonObject => regexInjectionToJson(e)),
});

// ===== fromJson(缺字段 → 默认;类型/枚举错 → throw → 整体 null) =====

export const modeInjectionFromJson = (j: JsonObject): PromptInjection => ({
  id: strField(j, 'id', newId()),
  name: strField(j, 'name', ''),
  enabled: boolField(j, 'enabled', true),
  priority: numField(j, 'priority', 0),
  position: positionField(j),
  content: strField(j, 'content', ''),
  injectDepth: numField(j, 'injectDepth', 4),
  role: roleField(j),
});

export const regexInjectionFromJson = (j: JsonObject): RegexInjection => ({
  ...modeInjectionFromJson(j),
  keywords: keywordsField(j),
  useRegex: boolField(j, 'useRegex', false),
  caseSensitive: boolField(j, 'caseSensitive', false),
  scanDepth: numField(j, 'scanDepth', 4),
  constantActive: boolField(j, 'constantActive', false),
});

export const lorebookFromJson = (j: JsonObject): Lorebook => {
  const rawEntries: JsonValue | undefined = j['entries'];
  const entries: RegexInjection[] = [];
  if (rawEntries !== undefined) {
    if (!Array.isArray(rawEntries)) fail();
    const arr: JsonValue[] = rawEntries as JsonValue[];
    for (const e of arr) entries.push(regexInjectionFromJson(asObject(e)));
  }
  return {
    id: strField(j, 'id', newId()),
    name: strField(j, 'name', ''),
    description: strField(j, 'description', ''),
    enabled: boolField(j, 'enabled', true),
    entries,
  };
};

// ===== list 编解码(ExtensionPrefs.kt:96-112 writeTo/readFrom) =====

export const encodeModeInjections = (list: PromptInjection[]): string =>
  JSON.stringify(list.map((mi: PromptInjection): JsonObject => modeInjectionToJson(mi)));

export const encodeLorebooks = (list: Lorebook[]): string =>
  JSON.stringify(list.map((lb: Lorebook): JsonObject => lorebookToJson(lb)));

// decodeJsonOrNull:任何解析/类型失败 → null(调用方 ?: emptyList)
export const decodeModeInjections = (raw: string): PromptInjection[] | null => {
  try {
    const parsed: JsonValue = JSON.parse(raw) as JsonValue;
    if (!Array.isArray(parsed)) return null;
    return parsed.map((v: JsonValue): PromptInjection => modeInjectionFromJson(asObject(v)));
  } catch {
    return null;
  }
};

export const decodeLorebooks = (raw: string): Lorebook[] | null => {
  try {
    const parsed: JsonValue = JSON.parse(raw) as JsonValue;
    if (!Array.isArray(parsed)) return null;
    return parsed.map((v: JsonValue): Lorebook => lorebookFromJson(asObject(v)));
  } catch {
    return null;
  }
};

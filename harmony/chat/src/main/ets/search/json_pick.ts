// json_pick — 容错 JSON 取值助手(kotlinx ignoreUnknownKeys 语义的取字段面)
// D-068 起 provider 共用;等价此前各 provider 内的局部副本
import type { JsonObject, JsonValue } from '../chat/json.ts';

export const asRecord = (v: JsonValue | undefined): JsonObject =>
  (typeof v === 'object' && v !== null && !Array.isArray(v)) ? v as JsonObject : {};

export const asArray = (v: JsonValue | undefined): JsonValue[] => Array.isArray(v) ? v : [];

export const strOrNull = (v: JsonValue | undefined): string | null =>
  typeof v === 'string' ? v : null;

export const numOrNull = (v: JsonValue | undefined): number | null =>
  typeof v === 'number' ? v : null;

// jsonPrimitive.content:String 原串;Number/Boolean 字符串化;对象/数组/缺失 → null
export const primitiveContentOrNull = (j: JsonObject, key: string): string | null => {
  const v: JsonValue | undefined = j[key];
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
};

// jsonPrimitive.contentOrNull:仅 String(非字符串原始类型 → null)
export const stringContentOrNull = (j: JsonObject, key: string): string | null => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'string' ? v : null;
};

export const requireQuery = (params: JsonObject): string => {
  const q: string | null = primitiveContentOrNull(params, 'query');
  if (q === null) throw new Error('query is required');
  return q;
};

// miniapp_output_parser — JSON 提取(围栏 + 裸括号配对)/ normalize / parse
//
// Android 基准: feature/miniapp/MiniAppOutputParser.kt(全文 102 行)
// 偏差:
//   - kotlinx lenient Json → JSON.parse(strict)。测试覆盖均为严格 JSON;
//     kotlinx lenient 的容错(未加引号键等)不移植(登记)
//   - kotlinx SerializationException → MiniAppParseException(models 定义)
//   - 显式字段提取(requireStringField 等)替代 kotlinx 反射解码

import {
  MINI_APP_CATEGORIES, MINI_APP_PERMISSION_ALIASES, MINI_APP_V3_PERMISSIONS,
  MiniAppParseException, MiniAppValidationException,
} from './miniapp_models.ts';
import type { MiniAppGeneratedOutput } from './miniapp_models.ts';
import { validateMiniAppHtml } from './miniapp_html_validator.ts';

const isRecord = (v: unknown): v is Record<string, unknown> =>
  v !== null && typeof v === 'object' && !Array.isArray(v);

const safeJsonParse = (s: string): unknown => {
  try {
    return JSON.parse(s);
  } catch (_e) {
    throw new MiniAppParseException('invalid json');
  }
};

const requireStringField = (obj: Record<string, unknown>, key: string): string => {
  const v: unknown = obj[key];
  if (typeof v !== 'string') throw new MiniAppParseException(`missing field: ${key}`);
  return v;
};

const optionalStringField = (obj: Record<string, unknown>, key: string): string | null => {
  const v: unknown = obj[key];
  if (v === undefined || v === null) return null;
  if (typeof v !== 'string') throw new MiniAppParseException(`invalid field: ${key}`);
  return v;
};

const optionalStringListField = (obj: Record<string, unknown>, key: string): string[] => {
  const v: unknown = obj[key];
  if (v === undefined || v === null) return [];
  if (!Array.isArray(v)) throw new MiniAppParseException(`invalid field: ${key}`);
  const out: string[] = [];
  for (const item of v) {
    if (typeof item !== 'string') throw new MiniAppParseException(`invalid field: ${key}`);
    out.push(item);
  }
  return out;
};

// MiniAppOutputParser.kt:31-36(别名映射 + distinct 保序)
const normalizePermissions = (output: MiniAppGeneratedOutput): MiniAppGeneratedOutput => {
  const permissions: string[] = [];
  for (const permission of output.permissions) {
    const alias: string = MINI_APP_PERMISSION_ALIASES[permission]
      ?? MINI_APP_PERMISSION_ALIASES[permission.toLowerCase()]
      ?? permission;
    if (!permissions.includes(alias)) permissions.push(alias);
  }
  return { ...output, permissions };
};

// MiniAppOutputParser.kt:38-59
const validateOutput = (output: MiniAppGeneratedOutput): void => {
  const title: string = output.title.trim();
  if (title.length === 0 || title.length > 20) {
    throw new MiniAppValidationException('Title must be 1-20 characters');
  }
  const description: string = output.description.trim();
  if (description.length === 0 || description.length > 80) {
    throw new MiniAppValidationException('Description must be 1-80 characters');
  }
  const icon: string = output.icon === null ? '' : output.icon.trim();
  if (icon.length > 2) {
    throw new MiniAppValidationException('Icon must be at most 2 characters');
  }
  if (!MINI_APP_CATEGORIES.includes(output.category)) {
    throw new MiniAppValidationException(`Unsupported category: ${output.category}`);
  }
  const unknown: string[] = output.permissions.filter(
    (p: string): boolean => !(MINI_APP_V3_PERMISSIONS as string[]).includes(p));
  if (unknown.length > 0) {
    throw new MiniAppValidationException(`Unsupported MiniApp permissions: ${unknown.join(',')}`);
  }
  validateMiniAppHtml(output.html);
};

// kotlinx 非空类型默认值语义:显式 null ≠ 缺省,视为解码失败(保留原文);
//   icon 为 String? 不受此限
const rejectExplicitNull = (obj: Record<string, unknown>, key: string): void => {
  if (obj[key] === null) throw new MiniAppParseException(`null field: ${key}`);
};

// kotlinx decodeFromJsonElement<MiniAppGeneratedOutput> 语义
const decodeGeneratedOutput = (parsed: unknown): MiniAppGeneratedOutput => {
  if (!isRecord(parsed)) throw new MiniAppParseException('not an object');
  rejectExplicitNull(parsed, 'category');
  rejectExplicitNull(parsed, 'permissions');
  const category: string | null = optionalStringField(parsed, 'category');
  return {
    title: requireStringField(parsed, 'title'),
    description: requireStringField(parsed, 'description'),
    icon: optionalStringField(parsed, 'icon'),
    category: category !== null ? category : 'tool',
    permissions: optionalStringListField(parsed, 'permissions'),
    html: requireStringField(parsed, 'html'),
  };
};

// ===== JSON 提取(MiniAppOutputParser.kt:61-101)=====

const extractFencedJson = (text: string): string | null => {
  const fenceStart: number = text.indexOf('```');
  if (fenceStart < 0) return null;
  const nl: number = text.indexOf('\n', fenceStart + 3);
  const contentStart: number = nl >= 0 ? nl + 1 : fenceStart + 3;
  const fenceEnd: number = text.indexOf('```', contentStart);
  if (fenceEnd < 0) return null;
  const content: string = text.substring(contentStart, fenceEnd);
  const stripped: string = content.startsWith('json') ? content.substring(4) : content;
  const trimmed: string = stripped.trim();
  return trimmed.startsWith('{') ? trimmed : null;
};

const extractJsonObject = (text: string): string | null => {
  const fenced: string | null = extractFencedJson(text);
  if (fenced !== null) return fenced;
  const start: number = text.indexOf('{');
  if (start < 0) return null;
  let depth: number = 0;
  let inString: boolean = false;
  let escaped: boolean = false;
  for (let index: number = start; index < text.length; index++) {
    const c: string = text[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (c === '\\') {
        escaped = true;
      } else if (c === '"') {
        inString = false;
      }
    } else if (c === '"') {
      inString = true;
    } else if (c === '{') {
      depth++;
    } else if (c === '}') {
      depth--;
      if (depth === 0) return text.substring(start, index + 1);
    }
  }
  return null;
};

// ===== Parser(MiniAppOutputParser.kt:15-29)=====

export class MiniAppOutputParser {
  parseOrNull(text: string): MiniAppGeneratedOutput | null {
    const candidate: string | null = extractJsonObject(text);
    if (candidate === null) return null;
    try {
      const output: MiniAppGeneratedOutput = this.decode(candidate);
      const normalized: MiniAppGeneratedOutput = normalizePermissions(output);
      validateOutput(normalized);
      return normalized;
    } catch (error) {
      if (error instanceof MiniAppValidationException || error instanceof MiniAppParseException) {
        return null;
      }
      throw error;
    }
  }

  parse(text: string): MiniAppGeneratedOutput {
    const candidate: string | null = extractJsonObject(text);
    if (candidate === null) {
      throw new MiniAppValidationException('No MiniApp JSON object found');
    }
    const output: MiniAppGeneratedOutput = this.decode(candidate);
    const normalized: MiniAppGeneratedOutput = normalizePermissions(output);
    validateOutput(normalized);
    return normalized;
  }

  private decode(candidate: string): MiniAppGeneratedOutput {
    return decodeGeneratedOutput(safeJsonParse(candidate));
  }
}

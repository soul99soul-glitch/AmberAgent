// webmount/har — 完整 HAR 1.2 消费:校验 → archive → opaque replay templates
//
// 只消费用户经设置页选择的文件;导入本身零网络请求。校验 HAR 1.2 结构
// (log/version/creator/pages/entries 的 request/response/timings,-1 表不可用),
// 保留完整已校验 JSON(含扩展字段)。超 2MiB/200 entries 拒绝。
// replay 资格与 Android NetworkLog 一致:GET/HEAD + 当前同源 + 非 mutation-like。

import type { JsonObject } from '../json.ts';
import type { WebMountHarArchive, WebMountReplayTemplate } from './models.ts';
import { isWebMountAbsoluteHttpUrl, isWebMountMutatingReplayUrl, webMountOriginOf } from './url.ts';

export const WEBMOUNT_HAR_MAX_BYTES: number = 2 * 1024 * 1024;
export const WEBMOUNT_HAR_MAX_ENTRIES: number = 200;

type JsonMap = Record<string, unknown>;

const isObj = (value: unknown): boolean =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const isStr = (value: unknown): boolean => typeof value === 'string';
const isNum = (value: unknown): boolean => typeof value === 'number' && Number.isFinite(value);
const isSize = (value: unknown): boolean => isNum(value) && (value as number) >= -1;

const fail = (where: string, reason: string): never => {
  throw new Error(`webmount har invalid: ${where} ${reason}`);
};

const requireObj = (value: unknown, where: string): JsonMap => {
  if (!isObj(value)) fail(where, 'must be an object');
  return value as JsonMap;
};
const requireStr = (value: unknown, where: string): string => {
  if (!isStr(value)) fail(where, 'must be a string');
  return value as string;
};
const requireNum = (value: unknown, where: string): void => {
  if (!isNum(value)) fail(where, 'must be a number');
};
const requireSize = (value: unknown, where: string): void => {
  if (!isSize(value)) fail(where, 'must be a number >= -1');
};
const requireNameValueArray = (value: unknown, where: string): void => {
  if (!Array.isArray(value)) fail(where, 'must be an array');
  const items: unknown[] = value as unknown[];
  items.forEach((item: unknown, index: number): void => {
    const entry: JsonMap = requireObj(item, `${where}[${index}]`);
    requireStr(entry['name'], `${where}[${index}].name`);
    requireStr(entry['value'], `${where}[${index}].value`);
  });
};

const validateRequest = (value: unknown, where: string): void => {
  const request: JsonMap = requireObj(value, where);
  requireStr(request['method'], `${where}.method`);
  const url: string = requireStr(request['url'], `${where}.url`);
  if (!isWebMountAbsoluteHttpUrl(url)) fail(`${where}.url`, 'must be an absolute http/https URL');
  requireStr(request['httpVersion'], `${where}.httpVersion`);
  requireNameValueArray(request['cookies'], `${where}.cookies`);
  requireNameValueArray(request['headers'], `${where}.headers`);
  requireNameValueArray(request['queryString'], `${where}.queryString`);
  requireSize(request['headersSize'], `${where}.headersSize`);
  requireSize(request['bodySize'], `${where}.bodySize`);
  if (request['postData'] !== undefined) requireObj(request['postData'], `${where}.postData`);
};

const validateResponse = (value: unknown, where: string): void => {
  const response: JsonMap = requireObj(value, where);
  requireNum(response['status'], `${where}.status`);
  requireStr(response['statusText'], `${where}.statusText`);
  requireStr(response['httpVersion'], `${where}.httpVersion`);
  requireNameValueArray(response['cookies'], `${where}.cookies`);
  requireNameValueArray(response['headers'], `${where}.headers`);
  const content: JsonMap = requireObj(response['content'], `${where}.content`);
  requireSize(content['size'], `${where}.content.size`);
  if (content['mimeType'] !== undefined) requireStr(content['mimeType'], `${where}.content.mimeType`);
  if (content['text'] !== undefined) requireStr(content['text'], `${where}.content.text`);
  requireStr(response['redirectURL'], `${where}.redirectURL`);
  requireSize(response['headersSize'], `${where}.headersSize`);
  requireSize(response['bodySize'], `${where}.bodySize`);
};

const validateTimings = (value: unknown, where: string): void => {
  const timings: JsonMap = requireObj(value, where);
  for (const key of ['send', 'wait', 'receive']) requireSize(timings[key], `${where}.${key}`);
  for (const key of ['blocked', 'dns', 'connect', 'ssl']) {
    if (timings[key] !== undefined) requireSize(timings[key], `${where}.${key}`);
  }
};

export const parseWebMountHar = (text: string, archiveId: string, nowMs: number): WebMountHarArchive => {
  if (archiveId.trim().length === 0) fail('archiveId', 'must be non-empty');
  if (text.length > WEBMOUNT_HAR_MAX_BYTES) fail('file', `exceeds ${WEBMOUNT_HAR_MAX_BYTES} bytes`);
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (_e) {
    fail('file', 'is not valid JSON');
  }
  const root: JsonMap = requireObj(parsed, 'root');
  const log: JsonMap = requireObj(root['log'], 'log');
  const version: string = requireStr(log['version'], 'log.version');
  if (!version.startsWith('1.2')) fail('log.version', `must be HAR 1.2, got "${version}"`);
  const creator: JsonMap = requireObj(log['creator'], 'log.creator');
  requireStr(creator['name'], 'log.creator.name');
  requireStr(creator['version'], 'log.creator.version');
  if (log['browser'] !== undefined) requireObj(log['browser'], 'log.browser');
  if (log['pages'] !== undefined) {
    if (!Array.isArray(log['pages'])) fail('log.pages', 'must be an array');
    (log['pages'] as unknown[]).forEach((page: unknown, index: number): void => {
      const map: JsonMap = requireObj(page, `log.pages[${index}]`);
      requireStr(map['id'], `log.pages[${index}].id`);
      requireStr(map['startedDateTime'], `log.pages[${index}].startedDateTime`);
      requireStr(map['title'], `log.pages[${index}].title`);
      requireObj(map['pageTimings'], `log.pages[${index}].pageTimings`);
    });
  }
  if (!Array.isArray(log['entries'])) fail('log.entries', 'must be an array');
  const entries: unknown[] = log['entries'] as unknown[];
  if (entries.length > WEBMOUNT_HAR_MAX_ENTRIES) fail('log.entries', `exceeds ${WEBMOUNT_HAR_MAX_ENTRIES} entries`);
  entries.forEach((entry: unknown, index: number): void => {
    const where: string = `log.entries[${index}]`;
    const map: JsonMap = requireObj(entry, where);
    requireStr(map['startedDateTime'], `${where}.startedDateTime`);
    requireNum(map['time'], `${where}.time`);
    if ((map['time'] as number) < 0) fail(`${where}.time`, 'must be >= 0');
    validateRequest(map['request'], `${where}.request`);
    validateResponse(map['response'], `${where}.response`);
    validateTimings(map['timings'], `${where}.timings`);
  });
  return { id: archiveId, importedAtMillis: nowMs, har: parsed as JsonObject };
};

// 已校验 archive → 全部条目的 opaque template(含 POST 等;replay 资格另判)
export const webMountHarTemplates = (
  archive: WebMountHarArchive, sessionId: string,
): WebMountReplayTemplate[] => {
  const log: Record<string, unknown> = (archive.har as Record<string, unknown>)['log'] as Record<string, unknown>;
  const entries: unknown[] = log['entries'] as unknown[];
  const templates: WebMountReplayTemplate[] = [];
  entries.forEach((entry: unknown, index: number): void => {
    const request: Record<string, unknown> = (entry as Record<string, unknown>)['request'] as Record<string, unknown>;
    const url: string = request['url'] as string;
    const origin: string | null = webMountOriginOf(url);
    if (origin === null) return; // validated archives cannot reach this
    templates.push({
      id: `${archive.id}-${index}`,
      sessionId: sessionId,
      sourceId: archive.id,
      source: 'imported_har',
      documentId: null,
      origin: origin,
      method: (request['method'] as string).toUpperCase(),
      url: url,
    });
  });
  return templates;
};

// Android NetworkLog 边界:仅 GET/HEAD、当前同源、非 mutation-like;其余零网络
export const webMountReplayAllowed = (
  template: WebMountReplayTemplate, currentUrl: string,
): boolean => {
  if (template.method !== 'GET' && template.method !== 'HEAD') return false;
  const currentOrigin: string | null = webMountOriginOf(currentUrl);
  return currentOrigin !== null && currentOrigin === template.origin
    && !isWebMountMutatingReplayUrl(template.url);
};

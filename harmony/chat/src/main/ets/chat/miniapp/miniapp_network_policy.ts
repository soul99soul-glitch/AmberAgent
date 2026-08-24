// miniapp_network_policy — HttpClient 限额常量 + URL 校验组合逻辑(不含真实 HTTP)
//
// Android 基准: feature/miniapp/MiniAppNetwork.kt MiniAppHttpClient(90-203) 纯逻辑部分
// 偏差:
//   - 不实现真实 HTTP(OkHttp/流读写);只承载:
//       a) 限额常量(HTTPS only / GET+POST / body≤128KB / 响应≤1MB / 图片≤2MB /
//          重定向≤3 / MAX_TEXT_CHARS 512KB / MINI_APP_USER_AGENT)
//       b) buildRequest 纯逻辑(URL guard + method 校验 + header allowlist +
//          body 大小校验)
//       c) responseType 归一化
//   - isAllowedHeader 提取为纯函数;header 值截断 take(500) 保留
//   - JsonObject.string 读取保留(key 缺失/非字符串 → 报错/空串)
//   - 异步 check(MiniAppUrlGuard.check 为 async)→ buildRequestPlan 为 async

import { MiniAppValidationException, utf8ByteLength } from './miniapp_models.ts';
import { MiniAppUrlGuard } from './miniapp_url_guard.ts';

export const MINI_APP_USER_AGENT: string = 'AmberAgent-MiniApp/2';
export const MINI_APP_MAX_REQUEST_BODY_BYTES: number = 128 * 1024;
export const MINI_APP_MAX_RESPONSE_BYTES: number = 1024 * 1024;
export const MINI_APP_MAX_TEXT_CHARS: number = 512 * 1024;
export const MINI_APP_MAX_IMAGE_BYTES: number = 2 * 1024 * 1024;
export const MINI_APP_MAX_REDIRECTS: number = 3;
export const MINI_APP_ACCEPT_HEADER: string = 'application/json,text/plain,*/*;q=0.7';
export const MINI_APP_IMAGE_ACCEPT_HEADER: string =
  'image/avif,image/webp,image/png,image/jpeg,image/svg+xml,image/*;q=0.8';

// MiniAppNetwork.kt:187-192
export const isAllowedRequestHeader = (name: string): boolean => {
  const lower: string = name.toLowerCase();
  if (lower === 'cookie' || lower === 'authorization') return false;
  if (lower.startsWith('proxy-') || lower.startsWith('x-forwarded-')) return false;
  return lower === 'accept' || lower === 'content-type' || lower === 'user-agent';
};

export type MiniAppResponseType = 'text' | 'json' | 'dataurl';

// 参数读取(JsonObject.string:194;缺失 → throw IllegalArgumentException)
const readStringParam = (params: Record<string, unknown>, key: string): string => {
  const v: unknown = params[key];
  if (typeof v !== 'string') throw new MiniAppValidationException(`Missing parameter: ${key}`);
  return v;
};

const optionalStringParam = (params: Record<string, unknown>, key: string): string | null => {
  const v: unknown = params[key];
  if (v === undefined || v === null) return null;
  if (typeof v !== 'string') throw new MiniAppValidationException(`Missing parameter: ${key}`);
  return v;
};

const optionalHeadersParam = (params: Record<string, unknown>): Record<string, unknown> => {
  const v: unknown = params['headers'];
  if (v === undefined || v === null) return {};
  if (typeof v !== 'object' || Array.isArray(v)) throw new MiniAppValidationException('Invalid headers');
  return v as Record<string, unknown>;
};

const optionalNumberParam = (params: Record<string, unknown>, key: string): number | null => {
  const v: unknown = params[key];
  if (v === undefined || v === null) return null;
  if (typeof v !== 'number') throw new MiniAppValidationException(`Missing parameter: ${key}`);
  return v;
};

export interface MiniAppRequestPlan {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string | null;
}

export interface MiniAppRequestPlanOpts {
  acceptHeader?: string;
}

// MiniAppNetwork.kt:144-167(buildRequest 纯逻辑,不含 OkHttp 构建)
export const buildMiniAppRequestPlan = async (
  guard: MiniAppUrlGuard,
  params: Record<string, unknown>,
  opts: MiniAppRequestPlanOpts = {},
): Promise<MiniAppRequestPlan> => {
  const urlParamRaw: string | null = optionalStringParam(params, 'url');
  if (urlParamRaw === null) throw new MiniAppValidationException('Missing url');
  const urlParam: string = urlParamRaw;
  await guard.check(urlParam);
  const methodRaw: string | null = optionalStringParam(params, 'method');
  const method: string = (methodRaw === null ? 'GET' : methodRaw).toUpperCase();
  if (method !== 'GET' && method !== 'POST') {
    throw new MiniAppValidationException('Only GET and POST are supported');
  }
  const headers: Record<string, string> = {};
  headers['accept'] = opts.acceptHeader ?? MINI_APP_ACCEPT_HEADER;
  headers['user-agent'] = MINI_APP_USER_AGENT;
  const rawHeaders: Record<string, unknown> = optionalHeadersParam(params);
  for (const key of Object.keys(rawHeaders)) {
    const normalized: string = key.trim();
    if (!isAllowedRequestHeader(normalized)) continue;
    const v: unknown = rawHeaders[key];
    const value: string = typeof v === 'string' ? v : '';
    headers[normalized] = value.slice(0, 500);
  }
  let body: string | null = null;
  const bodyParam: string | null = optionalStringParam(params, 'body');
  if (bodyParam !== null) {
    const bytes: number = utf8ByteLength(bodyParam);
    if (bytes > MINI_APP_MAX_REQUEST_BODY_BYTES) {
      throw new MiniAppValidationException('Request body is too large');
    }
    body = bodyParam;
  }
  return { url: urlParam, method, headers, body };
};

// MiniAppNetwork.kt:105-106(responseType 归一化,缺省 'text')
export const resolveMiniAppResponseType = (params: Record<string, unknown>): MiniAppResponseType => {
  const raw: string | null = optionalStringParam(params, 'responseType');
  const lower: string = (raw === null ? 'text' : raw).toLowerCase();
  if (lower === 'json') return 'json';
  if (lower === 'dataurl') return 'dataurl';
  return 'text';
};

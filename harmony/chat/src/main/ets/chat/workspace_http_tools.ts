import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool } from './tool.ts';
import { safeFileName } from './workspace_artifact_files.ts';
import type { ArtifactToolTracker } from './workspace_artifact_tool.ts';
import { inputLimit, integerProp, objectProp, stringProp, textJson } from './workspace_artifact_tool.ts';
import { PosixWorkspaceManager, toolInputRequiredString, toolInputString } from './workspace.ts';
import { utf8Decode } from './zip_archive.ts';

// ===== 常量(:854-861) =====

export const MAX_HTTP_BODY_BYTES: number = 512 * 1024;

export const MAX_DOWNLOAD_BYTES: number = 128 * 1024 * 1024;

// ===== 平台端口 =====

export interface ArtifactHttpRequest {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: string | null;
  timeoutMs: number;
  // 响应体上限(字节):平台在响应头阶段按 Content-Length 预检,
  // 超限立即中断,避免整包收完才发现超限的内存峰值
  maxResponseBytes?: number;
}

export interface ArtifactHttpResponse {
  status: number;
  // connection.url(重定向后最终 URL,:450)
  url: string;
  // headerFields:null 键(状态行)已滤;values joinToString(', ')(:451-454)
  headers: Record<string, string>;
  body: Uint8Array;
}

export type ArtifactHttpPort = (req: ArtifactHttpRequest) => Promise<ArtifactHttpResponse>;

// ===== URL/文件名(:469-472/:712-724) =====

// java.net.URI.getScheme 逐字:首个 ':' 前段;'http'/'https' 精确小写比较
//   ('HTTP://x' → scheme 'HTTP' → 拒绝,与 Kotlin == 比较同语义)
export const requireHttpUrl = (url: string): void => {
  const idx: number = url.indexOf(':');
  const scheme: string = idx >= 0 ? url.substring(0, idx) : '';
  if (scheme !== 'http' && scheme !== 'https') {
    throw new Error('Only http and https URLs are allowed');
  }
};

export const fileNameFromUrl = (url: string): string => {
  // URI(url).path:去 scheme://authority,截 ?/#
  let rest: string = url;
  const schemeIdx: number = rest.indexOf('://');
  if (schemeIdx >= 0) rest = rest.substring(schemeIdx + 3);
  const pathStart: number = rest.indexOf('/');
  let path: string = pathStart >= 0 ? rest.substring(pathStart) : '';
  const q: number = path.search(/[?#]/);
  if (q >= 0) path = path.substring(0, q);
  const name: string = path.substring(path.lastIndexOf('/') + 1);
  return name.length > 0 ? name : 'download.bin';
};

export interface WorkspaceHttpToolsDeps {
  workspaceManager: PosixWorkspaceManager;
  http: ArtifactHttpPort;
}

export const createWorkspaceHttpTools = (deps: WorkspaceHttpToolsDeps, track: ArtifactToolTracker): AgentTool[] => {
  const mgr: PosixWorkspaceManager = deps.workspaceManager;
  // :432-458 — request 体(status/headers/body/截断标志)
  const request = async (input: JsonValue): Promise<JsonObject> => {
    const url: string = toolInputRequiredString(input, 'url');
    const rawMethod: string = toolInputString(input, 'method') ?? 'GET';
    const method: string = rawMethod.toUpperCase();
    if (['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'].indexOf(method) < 0) {
      throw new Error(`Unsupported HTTP method: ${method}`);
    }
    requireHttpUrl(url);
    const timeout: number = inputLimit(input, 'timeout_ms', 20000, 120000);
    const headers: Record<string, string> = {};
    const rawHeaders: JsonValue | undefined =
      (input as JsonObject | null)?.['headers'] as JsonValue | undefined;
    if (rawHeaders !== null && rawHeaders !== undefined &&
      typeof rawHeaders === 'object' && !Array.isArray(rawHeaders)) {
      const rec: JsonObject = rawHeaders as JsonObject;
      Object.keys(rec).forEach((name: string): void => {
        // value.toString().trim('"') 逐字:字符串 → JSON 引号串 → 去首尾 '"'
        const v: JsonValue = rec[name] as JsonValue;
        const s: string = typeof v === 'string' ? JSON.stringify(v) : String(v);
        headers[name] = s.replace(/^"+|"+$/g, '');
      });
    }
    const body: string | null = toolInputString(input, 'body');
    const resp: ArtifactHttpResponse =
      await deps.http({
        method, url, headers, body, timeoutMs: timeout,
        maxResponseBytes: MAX_HTTP_BODY_BYTES,
      });
    const capped: Uint8Array = resp.body.subarray(0, MAX_HTTP_BODY_BYTES);
    const text: string = utf8Decode(capped);
    return {
      status_code: resp.status,
      url: resp.url,
      headers: resp.headers as unknown as JsonValue,
      body: text,
      truncated: resp.body.length >= MAX_HTTP_BODY_BYTES,
    };
  };
  return [
    // :62-83 — http_request
    makeAgentTool({
      name: 'http_request',
      description: 'Make a bounded HTTP/HTTPS request. Does not send app cookies or ' +
        'private device data. Response body is truncated at 512KB.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          method: stringProp('HTTP method. Defaults to GET.'),
          url: stringProp('HTTP or HTTPS URL.'),
          headers: objectProp('Optional string headers.'),
          body: stringProp('Optional request body.'),
          timeout_ms: integerProp('Timeout in milliseconds. Defaults to 20000.'),
        },
        required: ['url'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('http_request', 'HTTP 请求', input, 'HttpURLConnection',
          async (): Promise<UIMessagePart[]> => textJson(await request(input))),
    }),
    // :86-125 — download_file
    makeAgentTool({
      name: 'download_file',
      description: 'Download an HTTP/HTTPS URL into the user-authorized /workspace. ' +
        'Defaults to /workspace/downloads/<filename>.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          url: stringProp('HTTP or HTTPS URL to download.'),
          workspace_path: stringProp('Optional destination path under /workspace.'),
        },
        required: ['url'],
      }),
      needsApproval: true,
      allowsAutoApproval: false,
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('download_file', '下载文件', input, 'HTTP -> SAF workspace',
          async (): Promise<UIMessagePart[]> => {
            const url: string = toolInputRequiredString(input, 'url');
            requireHttpUrl(url);
            const resp: ArtifactHttpResponse = await deps.http(
              {
                method: 'GET', url, headers: {}, body: null, timeoutMs: 30000,
                maxResponseBytes: MAX_DOWNLOAD_BYTES,
              });
            if (resp.status < 200 || resp.status > 299) {
              throw new Error(`Download failed with HTTP ${resp.status}`);
            }
            if (resp.body.length > MAX_DOWNLOAD_BYTES) {
              throw new Error(`Download exceeds the ${MAX_DOWNLOAD_BYTES} byte limit`);
            }
            const rawDest: string | null = toolInputString(input, 'workspace_path');
            const destination: string =
              rawDest !== null && rawDest.trim().length > 0
                ? rawDest
                : `downloads/${safeFileName(fileNameFromUrl(url))}`;
            const contentType: string | undefined = resp.headers['content-type'] ??
              resp.headers['Content-Type'];
            const mime: string = contentType !== undefined
              ? contentType.split(';')[0]
              : 'application/octet-stream';
            const entry = await mgr.writeBytes(destination, resp.body, mime);
            return textJson({
              status: 'saved',
              path: entry.path,
              size_bytes: resp.body.length,
              mime_type: entry.mimeType ?? '',
            });
          }),
    })
  ];
};

// sync_webdav — WebDAV 备份客户端（对齐 Android WebDavClient 最小面）
//
// put / get / list / mkcol；Basic Auth；路径 = url + path + segments。
// HTTP 经 DAV binary transport Port；本文件纯逻辑可单测。

import { utf8Decode } from './zip_archive.ts';

export interface WebDavConfig {
  url: string;
  username: string;
  password: string;
  path: string;
}

export interface WebDavRemoteFile {
  name: string;
  href: string;
  size: number;
  lastModified: string;
}

const basicAuth = (username: string, password: string): string =>
  `Basic ${btoaUtf8(`${username}:${password}`)}`;

// ArkTS 无全局 btoa；手写 UTF-8 → base64
const btoaUtf8 = (s: string): string => {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const bytes: number[] = [];
  for (let i = 0; i < s.length; i++) {
    let c: number = s.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) {
      const low: number = s.charCodeAt(i + 1);
      if (low >= 0xdc00 && low <= 0xdfff) {
        c = 0x10000 + ((c - 0xd800) << 10) + low - 0xdc00;
        i++;
      } else c = 0xfffd;
    } else if (c >= 0xdc00 && c <= 0xdfff) c = 0xfffd;
    if (c < 0x80) bytes.push(c);
    else if (c < 0x800) bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    else if (c < 0x10000) bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    else bytes.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 0x3f), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
  }
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i];
    const b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
    const b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
    out += alphabet[b0 >> 2];
    out += alphabet[((b0 & 3) << 4) | (b1 >> 4)];
    out += i + 1 < bytes.length ? alphabet[((b1 & 15) << 2) | (b2 >> 6)] : '=';
    out += i + 2 < bytes.length ? alphabet[b2 & 63] : '=';
  }
  return out;
};

export const webDavBuildUrl = (config: WebDavConfig, segments: string[]): string => {
  const base = config.url.replace(/\/+$/, '');
  const parts: string[] = [];
  const path = config.path.replace(/^\/+|\/+$/g, '');
  if (path.length > 0) parts.push(path);
  for (const seg of segments) {
    const s = seg.replace(/^\/+|\/+$/g, '');
    if (s.length > 0) parts.push(encodeURIComponent(s));
  }
  if (parts.length === 0) return base;
  return `${base}/${parts.join('/')}`;
};

export interface WebDavClient {
  put(remoteName: string, data: Uint8Array, contentType?: string): Promise<void>;
  get(remoteName: string): Promise<Uint8Array>;
  list(): Promise<WebDavRemoteFile[]>;
  mkcol(remoteName: string): Promise<void>;
}

// 专用 DAV port 保持二进制字节；LLM HttpClient 的文本契约不适合备份。
export interface WebDavRequest {
  url: string;
  method: 'PUT' | 'GET' | 'PROPFIND' | 'MKCOL';
  headers: Record<string, string>;
  body?: Uint8Array | string;
}

export interface WebDavResponse {
  status: number;
  body: Uint8Array;
}

export interface WebDavTransport {
  fetch(request: WebDavRequest): Promise<WebDavResponse>;
}

const xmlText = (text: string): string => text.replace(
  /&(lt|gt|quot|apos|amp|#\d+|#x[\da-fA-F]+);/g,
  (match: string, entity: string): string => {
    if (entity === 'lt') return '<';
    if (entity === 'gt') return '>';
    if (entity === 'quot') return '"';
    if (entity === 'apos') return "'";
    if (entity === 'amp') return '&';
    const codePoint: number = entity.startsWith('#x')
      ? parseInt(entity.substring(2), 16) : parseInt(entity.substring(1), 10);
    return String.fromCodePoint(codePoint);
  });

const davTagText = (xml: string, tag: string): string => {
  const pattern = new RegExp(`<(?:[\\w.-]+:)?${tag}\\b[^>]*>([^<]*)<\\/(?:[\\w.-]+:)?${tag}>`, 'i');
  const match: RegExpExecArray | null = pattern.exec(xml);
  return match === null ? '' : xmlText(match[1]).trim();
};

const parseDavFiles = (xml: string): WebDavRemoteFile[] => {
  const files: WebDavRemoteFile[] = [];
  const responses = /<(?:[\w.-]+:)?response\b[^>]*>([\s\S]*?)<\/(?:[\w.-]+:)?response>/gi;
  let match: RegExpExecArray | null;
  while ((match = responses.exec(xml)) !== null) {
    const entry: string = match[1];
    const href: string = davTagText(entry, 'href');
    if (href.length === 0 || href.endsWith('/') || /<(?:[\w.-]+:)?collection\b/i.test(entry)) continue;
    // propstat 按属性分组；一个缺失属性的 404 不代表整个文件不存在。
    const propstats = /<(?:[\w.-]+:)?propstat\b[^>]*>([\s\S]*?)<\/(?:[\w.-]+:)?propstat>/gi;
    let properties: string = '';
    let propstat: RegExpExecArray | null;
    let hasPropstat: boolean = false;
    while ((propstat = propstats.exec(entry)) !== null) {
      hasPropstat = true;
      if (/\s200\s/.test(davTagText(propstat[1], 'status'))) properties += propstat[1];
    }
    if (hasPropstat && properties.length === 0) continue;
    if (!hasPropstat) {
      const status: string = davTagText(entry, 'status');
      if (status.length > 0 && !/\s200\s/.test(status)) continue;
      properties = entry;
    }
    const name: string = decodeURIComponent(href.substring(href.lastIndexOf('/') + 1));
    const length: number = Number(davTagText(properties, 'getcontentlength'));
    files.push({ name, href, size: Number.isFinite(length) && length > 0 ? length : 0,
      lastModified: davTagText(properties, 'getlastmodified') });
  }
  return files;
};

export const createWebDavClient = (
  http: WebDavTransport, config: WebDavConfig,
): WebDavClient => {
  const authHeader = (): string => basicAuth(config.username, config.password);
  return {
    put: async (remoteName: string, data: Uint8Array, contentType: string = 'application/octet-stream'): Promise<void> => {
      const resp: WebDavResponse = await http.fetch({
        url: webDavBuildUrl(config, [remoteName]), method: 'PUT',
        headers: { Authorization: authHeader(), 'Content-Type': contentType }, body: data,
      });
      if (resp.status < 200 || resp.status >= 300) throw new Error(`WebDAV PUT failed: ${resp.status}`);
    },
    get: async (remoteName: string): Promise<Uint8Array> => {
      const resp: WebDavResponse = await http.fetch({
        url: webDavBuildUrl(config, [remoteName]), method: 'GET', headers: { Authorization: authHeader() },
      });
      if (resp.status < 200 || resp.status >= 300) throw new Error(`WebDAV GET failed: ${resp.status}`);
      return resp.body;
    },
    list: async (): Promise<WebDavRemoteFile[]> => {
      const url: string = webDavBuildUrl(config, []);
      const resp: WebDavResponse = await http.fetch({
        url: url.endsWith('/') ? url : `${url}/`, method: 'PROPFIND',
        headers: { Authorization: authHeader(), Depth: '1' },
      });
      if (resp.status !== 207) throw new Error(`WebDAV PROPFIND failed: ${resp.status}`);
      return parseDavFiles(utf8Decode(resp.body));
    },
    mkcol: async (remoteName: string): Promise<void> => {
      const resp: WebDavResponse = await http.fetch({
        url: webDavBuildUrl(config, [remoteName]), method: 'MKCOL', headers: { Authorization: authHeader() },
      });
      // DAV MKCOL 405 表示目标已存在；409 为父目录缺失，必须报告。
      if ((resp.status < 200 || resp.status >= 300) && resp.status !== 405) {
        throw new Error(`WebDAV MKCOL failed: ${resp.status}`);
      }
    },
  };
};

export const webDavBackupFileName = (now: number = Date.now()): string =>
  `amber-backup-${new Date(now).toISOString().replace(/[:.]/g, '-').substring(0, 19)}.abin`;

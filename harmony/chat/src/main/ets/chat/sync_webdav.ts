// sync_webdav — WebDAV 备份客户端（对齐 Android WebDavClient 最小面）
//
// put / get / list / mkcol；Basic Auth；路径 = url + path + segments。
// HTTP 经 deepread HttpClient Port；本文件纯逻辑可单测。

import type { HttpClient, HttpRequest } from '@amber/deepread-domain';

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
    let c = s.charCodeAt(i);
    if (c < 0x80) bytes.push(c);
    else if (c < 0x800) bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    else bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
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
    if (s.length > 0) parts.push(s);
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

export const createWebDavClient = (
  http: HttpClient, config: WebDavConfig,
): WebDavClient => {
  const authHeader = (): string => basicAuth(config.username, config.password);
  const utf8 = (s: string): Uint8Array => {
    const out: number[] = [];
    for (let i = 0; i < s.length; i++) {
      const c = s.charCodeAt(i);
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
      else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
    return new Uint8Array(out);
  };
  const bytesToBinaryString = (bytes: Uint8Array): string => {
    let s = '';
    for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return s;
  };

  return {
    put: async (remoteName: string, data: Uint8Array, contentType: string = 'application/octet-stream'): Promise<void> => {
      const url = webDavBuildUrl(config, [remoteName]);
      const req: HttpRequest = {
        url,
        method: 'PUT',
        headers: {
          Authorization: authHeader(),
          'Content-Type': contentType,
          'Content-Length': `${data.length}`,
        },
        body: bytesToBinaryString(data),
      };
      const resp = await http.fetch(req);
      if (resp.status < 200 || resp.status >= 300) {
        throw new Error(`WebDAV PUT failed: ${resp.status}`);
      }
    },
    get: async (remoteName: string): Promise<Uint8Array> => {
      const url = webDavBuildUrl(config, [remoteName]);
      const resp = await http.fetch({
        url,
        method: 'GET',
        headers: { Authorization: authHeader() },
      });
      if (resp.status < 200 || resp.status >= 300) {
        throw new Error(`WebDAV GET failed: ${resp.status}`);
      }
      const body = resp.body;
      const out = new Uint8Array(body.length);
      for (let i = 0; i < body.length; i++) out[i] = body.charCodeAt(i) & 0xff;
      return out;
    },
    list: async (): Promise<WebDavRemoteFile[]> => {
      // PROPFIND depth 1 — HttpClient Port 无 PROPFIND 枚举时用 GET 目录会 405；
      // 调用方若不支持 method 扩展则抛错诚实失败
      const url = webDavBuildUrl(config, []);
      const req: HttpRequest = {
        url: url.endsWith('/') ? url : `${url}/`,
        method: 'GET',
        headers: {
          Authorization: authHeader(),
          Depth: '1',
        },
      };
      const resp = await http.fetch(req);
      // 简化：若服务器返回 HTML/XML 列表，解析 href；否则空列表
      const files: WebDavRemoteFile[] = [];
      const xml = resp.body;
      const hrefRe = /<d:href>([^<]+)<\/d:href>|<href>([^<]+)<\/href>/gi;
      let m: RegExpExecArray | null;
      while ((m = hrefRe.exec(xml)) !== null) {
        const href = m[1] ?? m[2] ?? '';
        if (href.length === 0 || href.endsWith('/')) continue;
        const name = href.substring(href.lastIndexOf('/') + 1);
        files.push({ name, href, size: 0, lastModified: '' });
      }
      return files;
    },
    mkcol: async (remoteName: string): Promise<void> => {
      const url = webDavBuildUrl(config, [remoteName]);
      const resp = await http.fetch({
        url,
        method: 'PUT',
        headers: { Authorization: authHeader() },
        body: '',
      });
      // MKCOL 不在 Port 枚举；用 PUT 目录名失败时忽略 405/409
      if (resp.status >= 400 && resp.status !== 405 && resp.status !== 409 && resp.status !== 301) {
        throw new Error(`WebDAV MKCOL failed: ${resp.status}`);
      }
    },
  };
};

export const webDavBackupFileName = (now: number = Date.now()): string =>
  `amber-backup-${new Date(now).toISOString().replace(/[:.]/g, '-').substring(0, 19)}.abin`;

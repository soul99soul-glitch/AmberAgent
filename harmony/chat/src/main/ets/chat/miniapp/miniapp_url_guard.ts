// miniapp_url_guard — SSRF 纯逻辑:URL 解析 + IP/CIDR 判定
//
// Android 基准: feature/miniapp/MiniAppNetwork.kt MiniAppUrlGuard(30-88)
// 偏差:
//   - InetAddress 解析 → resolveHost: (host) => Promise<string[]> 注入
//     (无注入时仅做字面 IP 校验,DNS 解析留给 entry/adapter)
//   - InetAddress.getByAddress → parseIpText(手写 IPv4/IPv6 文本解析,返回 null = 解析失败)
//   - isAnyLocalAddress → 0.0.0.0(IPv4 a==0)/IPv6 first==0(::,未嵌入映射)
//   - HttpUrl(okhttp) → MiniAppUrlRecord{protocol, host} 最小结构(仅校验所需字段)
//   - Java 异常类型 → MiniAppValidationException
// 语义锚点(与 Android 测试对齐):
//   - '::ffff:127.0.0.1' → 嵌入 IPv4 且映射前缀前 10 字节全零 → 递归判定
//   - 解析出多个地址任一被封 → 拒绝
//   - resolveHost 抛错/返回空 → "Unable to resolve host"

import { MiniAppValidationException } from './miniapp_models.ts';

export interface MiniAppUrlRecord {
  protocol: string;
  host: string;
}

export type MiniAppHostResolver = (host: string) => Promise<string[]>;

const isHex = (c: string): boolean =>
  (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');

const hexVal = (c: string): number => {
  if (c >= '0' && c <= '9') return c.charCodeAt(0) - '0'.charCodeAt(0);
  if (c >= 'a' && c <= 'f') return c.charCodeAt(0) - 'a'.charCodeAt(0) + 10;
  return c.charCodeAt(0) - 'A'.charCodeAt(0) + 10;
};

// inet_aton 数字段解析:0x 前缀十六进制 / 0 前缀八进制 / 十进制;非法 → null
const parseIpSegment = (seg: string): number | null => {
  if (seg.length === 0) return null;
  let radix: number = 10;
  let digits: string = seg;
  if (seg.length > 2 && seg[0] === '0' && (seg[1] === 'x' || seg[1] === 'X')) {
    radix = 16;
    digits = seg.substring(2);
  } else if (seg.length > 1 && seg[0] === '0') {
    radix = 8;
    digits = seg.substring(1);
  }
  if (digits.length === 0) return null;
  let v: number = 0;
  for (let i = 0; i < digits.length; i++) {
    if (!isHex(digits[i])) return null;
    const d: number = hexVal(digits[i]);
    if (d >= radix) return null;
    v = v * radix + d;
    if (v > 0xFFFFFFFF) return null;
  }
  return v;
};

// IPv4 文本 → 4 字节,失败 → null。
// 覆盖 inet_aton 全形态(RFC 标准库行为,浏览器/系统解析器会把下列 host
// 规范化为对应 IPv4 — 只认点分十进制会漏拦):
//   "2130706433"(纯十进制 32 位)、"0x7f000001"(十六进制)、
//   "017700000001"(八进制)、"127.1"(a.b 缩写)、"127.0x1.1"(混合进制)
const parseIpv4Text = (s: string): number[] | null => {
  const parts: string[] = s.split('.');
  if (parts.length === 0 || parts.length > 4) return null;
  const values: number[] = [];
  for (const part of parts) {
    const v: number | null = parseIpSegment(part);
    if (v === null) return null;
    values.push(v);
  }
  if (parts.length === 4) {
    // 全 4 段:每段一个字节
    if (values.some((v: number): boolean => v > 0xFF)) return null;
    return values;
  }
  // <4 段:末段承载剩余字节(n 段时末段占 4-n+1 字节:"a"=32位,"a.b" 中 b=24位…)
  const lastIdx: number = values.length - 1;
  for (let i = 0; i < lastIdx; i++) {
    if (values[i] > 0xFF) return null;
  }
  const tailBytes: number = 5 - values.length;
  const tailMax: number = 2 ** (8 * tailBytes) - 1;
  if (values[lastIdx] > tailMax) return null;
  const out: number[] = [];
  for (let i = 0; i < lastIdx; i++) out.push(values[i]);
  for (let shift = 8 * (tailBytes - 1); shift >= 0; shift -= 8) {
    out.push((values[lastIdx] >> shift) & 0xFF);
  }
  return out;
};

const startsWithIgnoreCase = (s: string, prefix: string): boolean =>
  s.toLowerCase().startsWith(prefix);

// 解析 IPv4-mapped IPv6 文本(::ffff:1.2.3.4 / ::ffff:102:304),失败 → null
const parseIpv4MappedTail = (tail: string): number[] | null => {
  const v4: number[] | null = parseIpv4Text(tail);
  if (v4 !== null) return v4;
  const parts: string[] = tail.split(':');
  if (parts.length !== 2) return null;
  const hi: string = parts[0];
  const lo: string = parts[1];
  if (hi.length > 4 || lo.length > 4) return null;
  if (![...hi].every(isHex) || ![...lo].every(isHex)) return null;
  let h: number = 0;
  for (let i: number = 0; i < hi.length; i++) h = (h << 4) | hexVal(hi[i]);
  let l: number = 0;
  for (let i: number = 0; i < lo.length; i++) l = (l << 4) | hexVal(lo[i]);
  return [(h >> 8) & 0xff, h & 0xff, (l >> 8) & 0xff, l & 0xff];
};

// IPv6 文本 → 16 字节,失败 → null
const parseIpv6Text = (s: string): number[] | null => {
  const ipv4MappedIdx: number = s.toLowerCase().indexOf('::ffff:');
  if (ipv4MappedIdx === 0) {
    const tail: string = s.substring('::ffff:'.length);
    const v4: number[] | null = parseIpv4MappedTail(tail);
    if (v4 === null) return null;
    return [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, ...v4];
  }
  const head: string = ipv4MappedIdx >= 0 ? s.substring(0, ipv4MappedIdx) : s;
  let groups: string[];
  if (head.includes('::')) {
    const parts: string[] = head.split('::');
    if (parts.length > 2) return null;
    const left: string[] = parts[0].length === 0 ? [] : parts[0].split(':');
    const right: string[] = parts.length > 1 && parts[1].length > 0 ? parts[1].split(':') : [];
    const fill: number = 8 - left.length - right.length;
    if (fill < 1) return null;
    groups = [...left, ...Array<string>(fill).fill('0'), ...right];
  } else {
    groups = head.split(':');
  }
  const out: number[] = [];
  for (const g of groups) {
    if (g.length === 0 || g.length > 4) return null;
    if (![...g].every(isHex)) return null;
    let v: number = 0;
    for (let i: number = 0; i < g.length; i++) v = (v << 4) | hexVal(g[i]);
    out.push((v >> 8) & 0xff, v & 0xff);
  }
  if (out.length !== 16) return null;
  return out;
};

// IP 文本 → 字节数组(IPv4 = 4 / IPv6 = 16),失败 → null
//   含 ':' 一律按 IPv6 解析(含 ::ffff:1.2.3.4 嵌入形式);其余交给 IPv4 的
//   inet_aton 解析 — 覆盖无点数字形式("2130706433"/"0x7f000001"),非数字串自然返回 null
const parseIpText = (s: string): number[] | null => {
  if (s.includes(':')) return parseIpv6Text(s);
  return parseIpv4Text(s);
};

const isBlockedAddress = (bytes: number[]): boolean => {
  if (bytes.length === 4) {
    const a: number = bytes[0];
    const b: number = bytes[1];
    return a === 0 ||
      a === 10 ||
      a === 127 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      a >= 224;
  }
  if (bytes.length === 16) {
    const first: number = bytes[0];
    const second: number = bytes[1];
    const ipv4Mapped: boolean = bytes.slice(0, 10).every((v: number): boolean => v === 0) &&
      bytes[10] === 0xff && bytes[11] === 0xff;
    if (ipv4Mapped) {
      return isBlockedAddress(bytes.slice(12, 16));
    }
    return first === 0 ||
      first === 0xff ||
      (first & 0xfe) === 0xfc ||
      (first === 0xfe && (second & 0xc0) === 0x80);
  }
  return true;
};

// ===== URL 解析(最小子集:scheme + host)=====

const parseUrlRecord = (raw: string): MiniAppUrlRecord | null => {
  const trimmed: string = raw.trim();
  const schemeIdx: number = trimmed.indexOf('://');
  if (schemeIdx <= 0) return null;
  const scheme: string = trimmed.substring(0, schemeIdx).toLowerCase();
  if (scheme !== 'http' && scheme !== 'https') return null;
  const rest: string = trimmed.substring(schemeIdx + 3);
  let authority: string = rest;
  const slashIdx: number = rest.indexOf('/');
  const queryIdx: number = rest.indexOf('?');
  const hashIdx: number = rest.indexOf('#');
  let first: number = rest.length;
  for (const idx of [slashIdx, queryIdx, hashIdx]) {
    if (idx >= 0 && idx < first) first = idx;
  }
  if (first < rest.length) authority = rest.substring(0, first);
  // 去除 userinfo(okhttp userInfo 不影响 host;Android check 不过滤 userinfo,保留语义)
  let host: string = authority;
  const atIdx: number = authority.lastIndexOf('@');
  if (atIdx >= 0) host = authority.substring(atIdx + 1);
  // host 提取(okhttp 规则):[IPv6 字面量] 可带 :port,']' 后仅允许 :port;
  //   裸 host 单冒号视为端口分隔;多冒号(未加方括号的 IPv6)为非法 authority
  if (host.startsWith('[')) {
    const closeIdx: number = host.indexOf(']');
    if (closeIdx < 0) return null;
    const literal: string = host.substring(1, closeIdx);
    const tail: string = host.substring(closeIdx + 1);
    if (tail.length > 0 && !tail.startsWith(':')) return null;
    host = literal;
  } else {
    const colonIdx: number = host.lastIndexOf(':');
    if (colonIdx >= 0) {
      if (host.indexOf(':') !== colonIdx) return null;
      host = host.substring(0, colonIdx);
    }
  }
  if (host.length === 0) return null;
  return { protocol: scheme, host };
};

// ===== 重定向相对引用解析(okhttp HttpUrl.resolve 子集;失败 → null) =====

// RFC 3986 5.2.4 remove_dot_segments
const removeDotSegments = (path: string): string => {
  const segs: string[] = path.split('/');
  const out: string[] = [];
  for (const seg of segs) {
    if (seg === '.') continue;
    if (seg === '..') {
      if (out.length > 1) out.pop();
      continue;
    }
    out.push(seg);
  }
  let resolved: string = out.join('/');
  if (path.endsWith('/.') || path.endsWith('/..')) {
    if (!resolved.endsWith('/')) resolved += '/';
  }
  return resolved.length > 0 ? resolved : '/';
};

interface MiniAppUrlParts {
  scheme: string;
  authority: string;
  path: string;
}

const splitUrl = (raw: string): MiniAppUrlParts | null => {
  const trimmed: string = raw.trim();
  const schemeIdx: number = trimmed.indexOf('://');
  if (schemeIdx <= 0) return null;
  const scheme: string = trimmed.substring(0, schemeIdx).toLowerCase();
  if (scheme !== 'http' && scheme !== 'https') return null;
  const rest: string = trimmed.substring(schemeIdx + 3);
  let authority: string = rest;
  let tail: string = '';
  const cut: number = rest.search(/[/?#]/);
  if (cut >= 0) {
    authority = rest.substring(0, cut);
    tail = rest.substring(cut);
  }
  let end: number = tail.length;
  const q: number = tail.indexOf('?');
  const h: number = tail.indexOf('#');
  if (q >= 0) end = Math.min(end, q);
  if (h >= 0) end = Math.min(end, h);
  return { scheme, authority, path: tail.substring(0, end) };
};

// 绝对/协议相对/根相对/相对 location → 绝对 URL;非法 → null(调用方按 Invalid redirect 拒绝)
export const resolveRedirectUrl = (fromUrl: string, location: string): string | null => {
  const loc: string = location.trim();
  if (loc.length === 0) return null;
  const lower: string = loc.toLowerCase();
  if (lower.startsWith('http://') || lower.startsWith('https://')) return loc;
  const base: MiniAppUrlParts | null = splitUrl(fromUrl);
  if (base === null) return null;
  if (loc.startsWith('//')) return `${base.scheme}:${loc}`;
  if (loc.startsWith('/')) return `${base.scheme}://${base.authority}${removeDotSegments(loc)}`;
  if (loc.startsWith('?') || loc.startsWith('#')) {
    return `${base.scheme}://${base.authority}${base.path}${loc}`;
  }
  const dirEnd: number = base.path.lastIndexOf('/');
  const dir: string = dirEnd <= 0 ? '/' : base.path.substring(0, dirEnd + 1);
  return `${base.scheme}://${base.authority}${removeDotSegments(`${dir}${loc}`)}`;
};

// host 是否为字面 IP(entry 侧跳过 DNS 预检;等价 Android InetAddress.getAllByName 字面短路)
export const isIpLiteralHost = (host: string): boolean => parseIpText(host) !== null;

// ===== Guard =====

export class MiniAppUrlGuard {
  constructor(private readonly resolveHost: MiniAppHostResolver | null = null) {}

  // Android check() 同步;鸿蒙 DNS 解析为异步 → check 变 async(登记偏差)
  async check(rawUrl: string): Promise<MiniAppUrlRecord> {
    const url: MiniAppUrlRecord | null = parseUrlRecord(rawUrl);
    if (url === null) throw new MiniAppValidationException('Invalid URL');
    if (url.protocol !== 'https') {
      throw new MiniAppValidationException('Only https URLs are allowed');
    }
    await this.resolveAllowed(url.host);
    return url;
  }

  async resolveAllowed(host: string): Promise<string[]> {
    if (this.resolveHost === null) {
      // 无解析器 → 仅字面 IP 校验(域名假设由 adapter 层解析后逐地址放行)
      const literal: number[] | null = parseIpText(host);
      if (literal !== null && isBlockedAddress(literal)) {
        throw new MiniAppValidationException('Blocked private or reserved host');
      }
      return [];
    }
    let addresses: string[];
    try {
      addresses = await this.resolveHost(host);
    } catch (_e) {
      throw new MiniAppValidationException('Unable to resolve host');
    }
    if (addresses.length === 0) {
      throw new MiniAppValidationException('Blocked private or reserved host');
    }
    for (const address of addresses) {
      const bytes: number[] | null = parseIpText(address.trim());
      if (bytes === null || isBlockedAddress(bytes)) {
        throw new MiniAppValidationException('Blocked private or reserved host');
      }
    }
    return addresses;
  }
}
